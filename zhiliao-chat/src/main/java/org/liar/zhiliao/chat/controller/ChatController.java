package org.liar.zhiliao.chat.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.chat.security.InputFilter;
import org.liar.zhiliao.chat.service.ChatService;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.service.TitleGenerationService;
import org.liar.zhiliao.retrieval.tools.KnowledgeRetrievalTool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话控制器。
 * Sentinel 资源名 "chat"：
 * - 限流：单用户每分钟 5 次（用户维度）
 * - 熔断：LLM API 异常时降级到知识库文档片段
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService assistant;
    private final ConversationService conversationService;
    private final TitleGenerationService titleGenerationService;
    private final InputFilter inputFilter;
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;

    @GetMapping(produces = "text/html;charset=utf-8")
    @SentinelResource(
            value = "chat",
            blockHandler = "chatBlockHandler",
            fallback = "chatFallback"
    )
    public Flux<String> chat(String memoryId, String message) {
        // 输入长度限制
        if (message != null && message.length() > 200) {
            message = message.substring(0, 200);
        }

        // Prompt 注入检测
        String rejection = inputFilter.check(message);
        if (rejection != null) {
            return Flux.just("输入被拒绝：" + rejection);
        }

        final String finalMessage = message;

        conversationService.touchConversation(memoryId);

        // 正常 LLM 流式回答
        return assistant.chat(memoryId, finalMessage)
                .doOnComplete(() -> {
                    if (conversationService.tryUpdateTitleIfDefault(memoryId, "生成中...")) {
                        titleGenerationService.generateTitleAsync(memoryId, finalMessage);
                    }
                });
    }

    /**
     * 限流降级：单用户每分钟超过 5 次时触发。
     */
    public Flux<String> chatBlockHandler(String memoryId, String message, BlockException e) {
        log.warn("Rate limited: memoryId={}, message={}, reason={}", memoryId, truncate(message, 50), e.getClass().getSimpleName());

        if (e instanceof FlowException) {
            return Flux.just("请求过于频繁，每分钟限 5 次，请稍后重试。");
        } else if (e instanceof AuthorityException) {
            return Flux.just("当前用户无访问权限。");
        } else if (e instanceof DegradeException) {
            return fallbackToDocs(message);
        }
        return Flux.just("请求被限流，请稍后重试。");
    }

    /**
     * 熔断降级：LLM API 异常时触发。
     * 兜底策略：返回知识库文档片段。
     */
    public Flux<String> chatFallback(String memoryId, String message, Throwable t) {
        log.warn("Circuit broken or exception: memoryId={}, message={}, error={}",
                memoryId, truncate(message, 50), t.getMessage());

        // 检查是否已被过滤或限流拒绝
        String rejection = inputFilter.check(message);
        if (rejection != null) {
            return Flux.just("输入被拒绝：" + rejection);
        }

        return fallbackToDocs(message);
    }

    /**
     * 熔断降级核心逻辑：从知识库检索文档片段返回。
     */
    private Flux<String> fallbackToDocs(String message) {
        // 直接从知识库检索文档片段
        try {
            String docs = knowledgeRetrievalTool.retrieveKnowledge(message);
            if (docs != null && !docs.isEmpty()) {
                return Flux.just("AI 服务暂时繁忙，以下是从知识库找到的相关内容供参考：\n\n" + docs);
            }
        } catch (Exception e) {
            log.warn("Fallback retrieval failed: {}", e.getMessage());
        }
        return Flux.just("AI 服务暂时不可用，请稍后再试。");
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

}
