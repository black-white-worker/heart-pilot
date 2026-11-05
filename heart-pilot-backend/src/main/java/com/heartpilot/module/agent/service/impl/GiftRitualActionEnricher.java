package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.requirement.GiftKeywordService;
import com.heartpilot.module.agent.requirement.RequirementStateService;
import com.heartpilot.module.agent.requirement.StructuredRequirement;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.ActionLanguageService.GiftIdea;
import com.heartpilot.module.agent.service.PlanningContext;
import com.heartpilot.module.agent.service.WebSearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 表达型行动富化器（礼物/仪式）。 候选礼物以"AI 分析用户输入后生成的具体商品方向"为主（GiftPlan.ideas）。
 *
 * <p>流水线：基于结构化需求生成差异化关键词池（3 套方案强制不同品类）→ 纯 Java 黑名单过滤 （自动把排除项转为电商搜索排除语法）→ 按关键词调 WebSearchService
 * 搜真实网页摘要 （searchapi.io，失败降级纯 AI）→ LLM 基于摘要校准价格与品牌 → 每个候选附多平台电商跳转链接 + 1-2
 * 条真实网页参考来源。
 */
@Service
public class GiftRitualActionEnricher extends AbstractActionEnricher {
    /** 展示的最大候选条数 */
    private static final int MAX_IDEAS = 8;

    /** 每个礼物候选最多附带的参考来源条数 */
    private static final int MAX_SOURCES_PER_IDEA = 2;

    private final ActionLanguageService language;
    private final RequirementStateService requirementState;
    private final GiftKeywordService keywordService;
    private final WebSearchService webSearch;

    public GiftRitualActionEnricher(
            ActionLanguageService language,
            RequirementStateService requirementState,
            GiftKeywordService keywordService,
            WebSearchService webSearch,
            ObjectMapper json) {
        super(json);
        this.language = language;
        this.requirementState = requirementState;
        this.keywordService = keywordService;
        this.webSearch = webSearch;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.GIFT_RITUAL;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        // 结构化需求（步骤 1 已持久化，是黑名单过滤与关键词生成的约束来源）
        StructuredRequirement requirement = null;
        RequirementStateService.RequirementSnapshot snapshot =
                requirementState.get(context.task().getId());
        if (snapshot != null && snapshot.requirement() != null) {
            requirement = snapshot.requirement();
        }

        // Tier2：关键词池前置 + 黑名单过滤 + 多平台跳转链接（先出关键词，才能按关键词做搜索校准）
        GiftKeywordService.GiftKeywordPlan keywordPlan =
                keywordService.build(
                        requirement,
                        (context.task().getObjective() == null
                                ? ""
                                : context.task().getObjective()));
        // 搜索增强：按每组第一个关键词搜真实网页摘要（searchapi.io → 失败降级纯 AI）
        Map<String, List<WebSearchService.SearchResult>> keywordSources = new LinkedHashMap<>();
        List<ActionLanguageService.GiftSearchEvidence> searchEvidence = new ArrayList<>();
        for (GiftKeywordService.KeywordGroup group : keywordPlan.groups()) {
            String head = group.keywords().getFirst();
            List<WebSearchService.SearchResult> results = webSearch.search(head);
            keywordSources.put(head, results);
            searchEvidence.add(new ActionLanguageService.GiftSearchEvidence(head, results));
        }

        // LLM 基于真实搜索摘要校准价格区间与品牌名后生成礼物候选
        ActionLanguageService.GiftPlan gift =
                language.draftGift(
                        goalText(draft, context),
                        context.task().getObjective(),
                        context.budget(),
                        searchEvidence);

        List<Map<String, Object>> giftIdeas =
                attachKeywordUrls(gift.ideas(), keywordPlan, keywordSources);
        boolean linked =
                giftIdeas.stream()
                        .anyMatch(i -> !((List<?>) i.getOrDefault("urls", List.of())).isEmpty());

        // instruction 只写引导性内容：准备思路 + AI 候选方向；
        // 准备事项/预算安排/执行步骤由 payload 结构化渲染，不再重复拼进正文
        StringBuilder instruction = new StringBuilder();
        instruction.append("准备思路：").append(gift.preparation());
        if (!giftIdeas.isEmpty()) {
            instruction.append("\n根据你的需求分析，候选礼物方向：");
            for (Map<String, Object> idea : giftIdeas) {
                instruction.append("\n- ").append(idea.get("title"));
            }
            instruction.append(
                    linked
                            ? "\n已为你生成多平台搜索链接（淘宝 / 京东 / 抖音），点击直达搜索结果页。"
                            : "\n（搜索链接暂不可用，以上为 AI 基于你输入的喜好与预算推荐）");
        }
        item.setInstruction(instruction.toString());
        item.setTimingSuggestion("选一个对方没有压力、值得被记得的日子");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preparation", gift.preparation());
        data.put("budgetText", gift.budgetText());
        data.put("steps", gift.steps());
        data.put("giftIdeas", giftIdeas);
        data.put("searchStatus", linked ? "KEYWORD_URLS" : "AI_ONLY");
        // 搜索增强状态：serpapi / 空（未搜到，纯 AI）——前端据此展示校准状态小字
        data.put("searchSource", webSearch.lastSource());
        // 付费搜索额度简单提示（额度耗尽时前端可见，未配置付费 key 为空）
        data.put("searchQuotaTip", webSearch.quotaTip());
        data.put("excludedTerms", keywordPlan.excludedTerms());
        item.setPayloadJson(payload(data));

        // 收集来源引用：电商跳转链接 + 搜索参考来源（去重后写入 sourceReferences）
        List<String> refs = new ArrayList<>();
        giftIdeas.forEach(
                idea -> {
                    Object raw = idea.get("urls");
                    if (raw instanceof List<?> urls) {
                        for (Object url : urls) {
                            if (url instanceof Map<?, ?> entry && entry.get("url") != null) {
                                String u = String.valueOf(entry.get("url"));
                                if (!refs.contains(u)) refs.add(u);
                            }
                        }
                    }
                    Object srcRaw = idea.get("sources");
                    if (srcRaw instanceof List<?> sources) {
                        for (Object src : sources) {
                            if (src instanceof Map<?, ?> entry && entry.get("url") != null) {
                                String u = String.valueOf(entry.get("url"));
                                if (!refs.contains(u)) refs.add(u);
                            }
                        }
                    }
                });
        item.setSourceReferencesJson(refs(refs));
        return new EnrichedAction(item, refs);
    }

    /**
     * 把 AI 候选与关键词池分组配对：每个候选附品类、搜索关键词、多平台跳转链接与搜索参考来源。 候选数超过分组数时循环复用第一组关键词，保证每个候选都有可点击的搜索入口。
     *
     * @param keywordSources 关键词 → 搜回的网页摘要（WebSearchService 产出；空列表表示未搜到）
     */
    private List<Map<String, Object>> attachKeywordUrls(
            List<GiftIdea> aiIdeas,
            GiftKeywordService.GiftKeywordPlan keywordPlan,
            Map<String, List<WebSearchService.SearchResult>> keywordSources) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (aiIdeas == null) return result;
        List<GiftKeywordService.KeywordGroup> groups = keywordPlan.groups();
        int index = 0;
        for (GiftIdea idea : aiIdeas) {
            if (idea == null || idea.title() == null || idea.title().isBlank()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", idea.title().trim());
            row.put("reason", idea.reason() == null ? "" : idea.reason().trim());
            row.put("priceHint", idea.priceHint() == null ? "" : idea.priceHint().trim());
            if (!groups.isEmpty()) {
                GiftKeywordService.KeywordGroup group = groups.get(index % groups.size());
                row.put("category", group.category());
                row.put("keywords", group.keywords());
                String head = group.keywords().getFirst();
                List<GiftKeywordService.PlatformUrl> urls =
                        keywordPlan.urlsByKeyword().getOrDefault(head, List.of());
                row.put("urls", urls);
                // 参考来源：搜回的网页链接（最多 2 条），用户点进去自己看详情
                List<WebSearchService.SearchResult> sources =
                        keywordSources.getOrDefault(head, List.of());
                if (sources.isEmpty()) {
                    row.put("sources", List.of());
                } else {
                    row.put(
                            "sources",
                            sources.stream()
                                    .limit(MAX_SOURCES_PER_IDEA)
                                    .map(
                                            s ->
                                                    Map.of(
                                                            "title",
                                                            (s.title() == null
                                                                            || s.title().isBlank())
                                                                    ? s.url()
                                                                    : s.title(),
                                                            "url",
                                                            s.url()))
                                    .toList());
                }
            } else {
                row.put("urls", List.of());
                row.put("sources", List.of());
            }
            result.add(row);
            index++;
            if (result.size() >= MAX_IDEAS) break;
        }
        return result;
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank()
                ? context.task().getObjective()
                : goal + "：" + context.task().getObjective();
    }
}
