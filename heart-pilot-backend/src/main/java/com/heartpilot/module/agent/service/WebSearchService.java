package com.heartpilot.module.agent.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 送礼链路网页搜索服务（礼物搜索增强）。
 *
 * <p>搜索源与降级链：searchapi.io 网页搜索（主）→ 空结果（纯 AI 推荐 + 平台搜索跳转链接）。 任何一层失败都不阻断送礼规划主流程。
 *
 * <p>可靠性设计：
 *
 * <ul>
 *   <li>Caffeine 本地缓存：同一关键词在 TTL（默认 12 小时）内直接命中，不重复消耗付费额度；只缓存非空结果，避免一次失败污染半天
 *   <li>请求超时：每次外部调用都带超时，失败立即降级，不占用送礼规划线程
 *   <li>额度耗尽 / 鉴权失败 / 请求异常：写入能力状态记录器（前端可读），本次请求返回空列表由调用方降级纯 AI
 * </ul>
 *
 * <p>DuckDuckGo 免费 HTML 搜索层已移除：结果页给不出商品级价格（淘宝/京东商品需登录、价格不进搜索索引）， 且机房出口 IP 被稳定拦截（HTTP
 * 202）。历史实现见提交 1c0b409。
 */
@Service
public class WebSearchService {
    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    /** searchapi.io 搜索端点（与现有 WebSearchTool 同一家，复用同一把 key） */
    private static final String SERP_ENDPOINT = "https://www.searchapi.io/api/v1/search";

    /** 单条搜索结果：标题 + 摘要 + 真实网页链接 */
    public record SearchResult(String title, String snippet, String url) {}

    private final Cache<String, List<SearchResult>> cache;
    private final Duration requestTimeout;
    private final CapabilityStatusRecorder statusRecorder;
    private final int maxResultsPerKeyword;
    private final String searchApiKey;

    /** 最近一次实际命中的搜索源：serpapi / 空（未搜到，纯 AI） */
    private final AtomicReference<String> lastSource = new AtomicReference<>("");

    /** 付费搜索额度是否已耗尽（额度耗尽后向前端输出简单提示） */
    private final AtomicBoolean serpQuotaExhausted = new AtomicBoolean(false);

    public WebSearchService(
            CapabilityStatusRecorder statusRecorder,
            @Value("${gift-search.max-results-per-keyword:5}") int maxResultsPerKeyword,
            @Value("${gift-search.cache-ttl-minutes:720}") int cacheTtlMinutes,
            @Value("${gift-search.request-timeout-millis:6000}") int requestTimeoutMillis,
            @Value("${search-api.api-key:}") String searchApiKey) {
        this.statusRecorder = statusRecorder;
        // 每组关键词最多取 5 条（放宽到 5 条让 LLM 自己筛），下限 1
        this.maxResultsPerKeyword = Math.max(1, maxResultsPerKeyword);
        this.searchApiKey = searchApiKey;
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(Math.max(1, cacheTtlMinutes)))
                        .maximumSize(2000)
                        .build();
        this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMillis));

        if (searchApiKey == null || searchApiKey.isBlank()) {
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.NO_KEY,
                    "未配置 SEARCH_API_KEY，送礼搜索降级为纯 AI 推荐");
        } else {
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.OK, "searchapi.io 付费搜索（待首次调用验证）");
        }
    }

    /**
     * 对外搜索入口（供 GiftRitualActionEnricher 调用）。 流程：缓存 → searchapi.io → 空结果（纯 AI）。
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<SearchResult> cached = cache.getIfPresent(query);
        if (cached != null) return cached;
        return serpSearch(query);
    }

    /**
     * 调用 searchapi.io。额度耗尽 / 鉴权失败 / 请求异常都写入能力状态记录器（前端可读）， 额度耗尽时置 serpQuotaExhausted
     * 标记，用于“额度简单提示”。失败一律返回空列表。
     */
    private List<SearchResult> serpSearch(String query) {
        if (searchApiKey == null || searchApiKey.isBlank()) {
            return List.of(); // 未配置 key：直接纯 AI 降级
        }
        try {
            String response =
                    HttpUtil.get(
                            SERP_ENDPOINT,
                            Map.of("q", query, "api_key", searchApiKey, "engine", "baidu"),
                            (int) requestTimeout.toMillis());
            JSONObject root = JSONUtil.parseObj(response);
            if (root.containsKey("error")) {
                String error = root.getStr("error", "");
                log.warn("searchapi.io 返回错误：{}", error);
                if (error.toLowerCase().contains("all of the searches") || error.contains("429")) {
                    serpQuotaExhausted.set(true);
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.QUOTA_EXHAUSTED,
                            "搜索 key 额度已用完，送礼搜索已降级为纯 AI 推荐");
                } else if (error.contains("401") || error.toLowerCase().contains("api key")) {
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.AUTH_FAILED, "搜索 key 无效或已过期");
                } else {
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.ERROR,
                            error.length() > 120 ? error.substring(0, 120) : error);
                }
                return List.of();
            }
            JSONArray items = root.getJSONArray("organic_results");
            if (items == null || items.isEmpty()) return List.of();
            statusRecorder.recordWebSearch(CapabilityStatusRecorder.Status.OK, "搜索服务正常");
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < items.size() && results.size() < maxResultsPerKeyword; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.getStr("title", "");
                String snippet = item.getStr("snippet", "");
                String link = item.getStr("link", "");
                if (link.isBlank()) continue;
                results.add(new SearchResult(title, snippet, link));
            }
            if (!results.isEmpty()) {
                lastSource.set("serpapi");
                cache.put(query, results);
            }
            return results;
        } catch (Exception e) {
            log.warn("searchapi.io 搜索失败 query={} 原因={}", query, e.toString());
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.ERROR, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 最近一次实际命中的搜索源：serpapi / 空（未搜到，纯 AI） */
    public String lastSource() {
        return lastSource.get();
    }

    /** 付费搜索额度提示文案（额度耗尽时前端可见，未配置 key 不提示） */
    public String quotaTip() {
        if (searchApiKey == null || searchApiKey.isBlank()) return "";
        return serpQuotaExhausted.get() ? "付费搜索额度已用完，已降级为 AI 推荐" : "";
    }
}
