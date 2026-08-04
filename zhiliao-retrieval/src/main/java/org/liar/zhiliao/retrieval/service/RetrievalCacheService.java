package org.liar.zhiliao.retrieval.service;

import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 两级缓存服务。
 *
 * <p>两个缓存区，均走 L1 Caffeine → L2 Redis：</p>
 * <ul>
 *   <li><b>query_rewrite</b>（cache:rewrite:*）：{规范化查询} → LLM 改写后的查询文本，TTL 1h</li>
 *   <li><b>retrieval_result</b>（cache:retrieval:*）：{规范化查询}:{部门ID} → RankedChunk 列表，TTL 24h</li>
 * </ul>
 *
 * <p>注意：retrieval_result 的 key 中拼接了 deptSuffix 用于权限隔离，
 * 同一规范化查询在不同部门维度下缓存不同结果。</p>
 */
@Service
public class RetrievalCacheService {

    // ==================== query_rewrite 缓存 ====================

    /**
     * 获取缓存的查询改写结果。
     *
     * @param normalizedQuery normalize() 后的规范化查询
     * @return 改写后的查询文本，未命中返回 null
     */
    @Cacheable(value = "query_rewrite")
    public String getRewrite(String normalizedQuery) {
        return null;
    }

    /**
     * 写入查询改写缓存。
     *
     * @param normalizedQuery normalize() 后的规范化查询
     * @param rewriteResult   LLM 改写后的查询文本
     */
    @CachePut(value = "query_rewrite")
    public void putRewrite(String normalizedQuery, String rewriteResult) {
    }

    /**
     * 全量淘汰 query_rewrite 缓存区。
     * 文档更新时由 DocumentUpdateEventListener 调用。
     */
    @CacheEvict(value = "query_rewrite", allEntries = true)
    public void evictAllRewrite() {
    }

    // ==================== retrieval_result 缓存 ====================

    /**
     * 获取缓存的检索结果。
     * key 由 canonicalQuery 和 deptSuffix 复合构成，天然实现部门间缓存隔离。
     *
     * @param canonicalQuery 用于缓存 key 的规范查询（通常是 normalize 后的值）
     * @param deptSuffix     部门 ID 后缀，格式 "deptId1_deptId2"，由调用方计算
     * @return 缓存的 RankedChunk 列表，未命中返回 null
     */
    @Cacheable(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
    public List<RankedChunk> getCachedRetrieval(String canonicalQuery, String deptSuffix) {
        return null;
    }

    /**
     * 写入检索结果缓存。
     *
     * @param canonicalQuery 用于缓存 key 的规范查询
     * @param deptSuffix     部门 ID 后缀
     * @param rankedChunks   检索结果列表
     * @return 写入的结果列表
     */
    @CachePut(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
    public List<RankedChunk> putRetrieval(String canonicalQuery, String deptSuffix, List<RankedChunk> rankedChunks) {
        return rankedChunks;
    }

    /**
     * 全量淘汰 retrieval_result 缓存区。
     * 文档更新时由 DocumentUpdateEventListener 调用。
     */
    @CacheEvict(value = "retrieval_result", allEntries = true)
    public void evictAllRetrieval() {
    }
}
