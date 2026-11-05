package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import com.heartpilot.infrastructure.ai.tool.WebSearchTool;
import com.heartpilot.module.agent.service.PlaceSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceSearchServiceTest {
    @Test
    void missingExternalKeysProducesExplicitDegradedEvidenceWithoutInventingPlaces() {
        PlaceSearchServiceImpl service =
                new PlaceSearchServiceImpl(
                        "",
                        new WebSearchTool("", mock(CapabilityStatusRecorder.class)),
                        mock(PlaceKeywordExtractor.class));

        PlaceSearchService.JourneyResearchResult result =
                service.researchJourney("南宁", "看展览、吃晚餐并散步");

        assertEquals("DEGRADED", result.evidence().sourceStatus());
        assertTrue(result.evidence().places().isEmpty());
        assertTrue(result.evidence().routes().isEmpty());
        assertTrue(result.evidence().mapCards().isEmpty());
        assertTrue(result.evidence().notice().contains("地图地点"));
    }

    @Test
    void rejectsPlacesOutsideTheRequestedCityEvenWhenAmapReturnsThem() {
        assertTrue(
                PlaceSearchServiceImpl.matchesRequestedScope(
                        "南宁河池", "广西壮族自治区", "南宁市", "青秀区", "民族大道"));
        assertTrue(
                PlaceSearchServiceImpl.matchesRequestedScope(
                        "南宁青秀区", "广西壮族自治区", "南宁市", "青秀区", "民族大道"));
        assertEquals(
                false,
                PlaceSearchServiceImpl.matchesRequestedScope(
                        "南宁河池", "北京市", "北京市", "朝阳区", "国家体育场南路1号"));
    }

    @Test
    void swimmingSearchRejectsUnrelatedShoppingMalls() {
        assertTrue(PlaceSearchServiceImpl.matchesTopic("游泳馆", "广西体育中心游泳馆", "体育休闲服务;运动场馆"));
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("游泳馆", "某某健身中心", "体育休闲服务;健身中心"));
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("游泳馆", "万象城", "购物服务;购物中心"));
    }

    @Test
    void preservesCustomIntentInsteadOfMappingItToFixedCategories() {
        PlaceSearchServiceImpl service =
                new PlaceSearchServiceImpl(
                        "",
                        new WebSearchTool("", mock(CapabilityStatusRecorder.class)),
                        mock(PlaceKeywordExtractor.class));

        PlaceSearchService.SearchResult result = service.search("上海市", "去玩游艇");

        assertEquals("游艇", result.groups().getFirst().label());
        assertTrue(result.groups().getFirst().query().contains("游艇"));
        assertEquals(false, result.keywords().contains("运动"));
    }

    @Test
    void extractsOnlyConcreteQuestionKeywords() {
        PlaceSearchServiceImpl service =
                new PlaceSearchServiceImpl(
                        "",
                        new WebSearchTool("", mock(CapabilityStatusRecorder.class)),
                        mock(PlaceKeywordExtractor.class));

        PlaceSearchService.SearchResult result = service.search("天津市", "有摩天轮，去酒吧\n喝啤酒");

        assertEquals(
                List.of("摩天轮", "酒吧", "啤酒"),
                result.groups().stream().map(PlaceSearchService.SearchGroup::label).toList());
        assertEquals(false, result.keywords().contains("目标"));
        assertEquals(false, result.keywords().contains("优先"));
    }

    @Test
    void genericCityPoisCannotSatisfyAnUnrelatedKeyword() {
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("睡觉", "天津市", "地名地址信息;省级地名"));
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("睡觉", "天津市第一中学", "学校"));
    }

    @Test
    void commonLocalLifeTopicsMatchAmapCategoryNames() {
        assertTrue(PlaceSearchServiceImpl.matchesTopic("美食", "青岛菜馆", "餐饮服务;中餐厅"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("景点", "栈桥", "风景名胜;风景名胜相关"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("住宿", "青岛海景酒店", "住宿服务;宾馆酒店"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("海鲜", "海鲜大排档", "餐饮服务;中餐厅"));
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("景点", "青岛商场", "购物服务;商场"));
    }

    @Test
    void unifiedIntentCatalogSupportsAmapTypeCodes() {
        assertTrue(PlaceSearchServiceImpl.matchesTopic("逛街", "万象城", "", "060100"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("看电影", "某影城", "", "080601"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("健身", "市民健身中心", "", "070100"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("看展", "城市展览馆", "", "140200"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("酒吧", "海边清吧", "", "080500"));
        assertTrue(PlaceSearchServiceImpl.matchesTopic("停车", "地下停车场", "", "150900"));
    }

    @Test
    void unknownIntentKeepsExactKeywordFallback() {
        assertTrue(PlaceSearchServiceImpl.matchesTopic("宠物摄影", "岛城宠物摄影馆", "生活服务", ""));
        assertEquals(false, PlaceSearchServiceImpl.matchesTopic("宠物摄影", "岛城照相馆", "生活服务", ""));

        PlaceSearchServiceImpl service =
                new PlaceSearchServiceImpl(
                        "",
                        new WebSearchTool("", mock(CapabilityStatusRecorder.class)),
                        mock(PlaceKeywordExtractor.class));
        PlaceSearchService.SearchGroup group = service.search("青岛市", "宠物摄影").groups().getFirst();
        assertEquals("CUSTOM", group.intentCategory());
        assertEquals(List.of("宠物摄影"), group.searchKeywords());
        assertTrue(group.amapTypeCodes().isEmpty());
    }
}
