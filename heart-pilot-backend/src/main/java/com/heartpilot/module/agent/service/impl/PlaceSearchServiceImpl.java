package com.heartpilot.module.agent.service.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.heartpilot.infrastructure.ai.tool.WebSearchTool;
import com.heartpilot.module.agent.service.PlaceSearchService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 地点检索与行程证据构建服务。 负责：把用户目标拆成检索主题 → 调高德 POI 文本检索 + 网页搜索取真实地点 → 严格按城市范围筛选 → 选出行程地点 → 按距离选出行方式算实时路线 →
 * 产出可核验的 JourneyEvidence。
 *
 * <p>可靠性设计要点： - 严格城市范围过滤（matchesRequestedScope）：高德 citylimit 对复合地名可能失效， 必须用返回的省/市/区/地址二次确认，绝不接受范围外
 * POI - 路线方式按直线距离自适应：≤1.8km 步行、≤6km 骑行、更远驾车 - 高德未配 Key 或接口异常时降级（返回空/DEGRADED），不编造地点 -
 * 营业状态由高德营业时间字段尽力推导（inferBusinessStatus），仅供参考
 */
@Service
public class PlaceSearchServiceImpl implements PlaceSearchService {
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);

    /** 高德 POI 文本检索接口 */
    private static final String AMAP_URL = "https://restapi.amap.com/v3/place/text";

    /** 高德步行路线规划接口 */
    private static final String AMAP_WALKING_URL = "https://restapi.amap.com/v3/direction/walking";

    /** 高德骑行路线规划接口（v4） */
    private static final String AMAP_BICYCLING_URL =
            "https://restapi.amap.com/v4/direction/bicycling";

    /** 高德驾车路线规划接口 */
    private static final String AMAP_DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";

    /** 检索/核验时间戳格式（东八区） */
    private static final DateTimeFormatter SEARCH_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一次任务最多拆出的检索主题数，控制外部调用量 */
    private static final int MAX_TOPICS = 5;

    /** 地图卡片总数硬上限：防止某个类别 POI 特别多时一次塞太多 */
    private static final int HARD_MAX_CARDS = 20;

    /** 每类最少补充的候选卡片数（主线卡片之外） */
    private static final int MIN_CANDIDATES_PER_CATEGORY = 2;

    /** 每类最多补充的候选卡片数（主线卡片之外），按该类实际 POI 数量动态取中间值 */
    private static final int MAX_CANDIDATES_PER_CATEGORY = 4;

    /** 高德 Web 服务 Key */
    private final String amapKey;

    /** 网页搜索工具，补充高德 POI 之外的公开信息 */
    private final WebSearchTool webSearch;

    /** LLM 地点关键词提取器：把口语诉求翻译成干净的高德 POI 搜索词 */
    private final PlaceKeywordExtractor keywordExtractor;

    /** 构造器注入高德 Key、网页搜索工具与关键词提取器。 */
    public PlaceSearchServiceImpl(
            @Value("${AMAP_MAPS_API_KEY:}") String amapKey,
            WebSearchTool webSearch,
            PlaceKeywordExtractor keywordExtractor) {
        this.amapKey = amapKey;
        this.webSearch = webSearch;
        this.keywordExtractor = keywordExtractor;
    }

    /** 按主题分组检索地点。 先从目标推断至多 5 个检索主题，每个主题分别查高德 POI + 网页搜索， 用 poiId（缺失时用 name|address）去重后合并。 */
    @Override
    public SearchResult search(String city, String objective) {
        return searchWithPoolSize(city, objective, 10);
    }

    /**
     * 拉取候选点位池（Tier2 候选池前置）。 与 search 唯一区别：每个主题取更多 POI（每类最多 18 个）， 供候选挑选 Agent 从池内挑选排序，减少 LLM
     * 凭空编造地点的空间。
     */
    @Override
    public SearchResult searchPool(String city, String objective) {
        return searchWithPoolSize(city, objective, 18);
    }

    /** 基于已挑选点位构建行程证据（候选挑选 Agent → 路线 + 地图卡片）。 相邻点位间逐条规划实时路线；至少两个点位才计算路线。 */
    @Override
    public JourneyEvidence researchFromPool(String city, List<Place> selectedPlaces) {
        if (selectedPlaces == null) selectedPlaces = List.of();
        List<RoutePlan> routes = new ArrayList<>();
        if (amapKey != null && !amapKey.isBlank()) {
            for (int i = 0; i + 1 < selectedPlaces.size(); i++) {
                RoutePlan route = planRoute(selectedPlaces.get(i), selectedPlaces.get(i + 1));
                if (route != null) routes.add(route);
            }
        }
        String notice;
        if (selectedPlaces.isEmpty()) {
            notice = "候选池中没有满足约束的可核验地点，系统不会编造店名或地址。";
        } else if (selectedPlaces.size() == 1) {
            notice = "已取得一个真实地点，至少需要两个地点才能计算地点间路线。";
        } else if (routes.isEmpty()) {
            notice = "地点已取得，但实时路线暂不可用；出发前请打开地图链接核验。";
        } else {
            notice = "距离、耗时和出行方式来自高德地图实时路线；出发前仍建议核验路况与营业状态。";
        }
        List<MapCard> cards = new ArrayList<>();
        int limit = Math.min(selectedPlaces.size(), 12);
        for (int index = 0; index < limit; index++) {
            Place place = selectedPlaces.get(index);
            RoutePlan routeFromPrevious = null;
            if (index > 0 && index - 1 < routes.size()) routeFromPrevious = routes.get(index - 1);
            String[] coordinates = place.location().split(",", 2);
            cards.add(
                    new MapCard(
                            place.poiId(),
                            place.name(),
                            place.address(),
                            place.type(),
                            place.tel(),
                            coordinates.length > 0 ? coordinates[0] : "",
                            coordinates.length > 1 ? coordinates[1] : "",
                            place.mapUrl(),
                            place.photoUrl(),
                            place.rating(),
                            place.businessHours(),
                            place.businessStatus(),
                            place.statusCheckedAt(),
                            place.businessHours().isBlank()
                                    ? "NO_OPENING_HOURS"
                                    : "DERIVED_FROM_AMAP_OPENING_HOURS",
                            "高德地图",
                            place.mapUrl(),
                            routeFromPrevious));
        }
        return new JourneyEvidence(
                "按类别独立检索",
                city,
                selectedPlaces.stream().map(Place::name).toList().toString(),
                selectedPlaces,
                routes,
                selectedPlaces.isEmpty() ? "DEGRADED" : "LIVE",
                notice,
                SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))),
                cards);
    }

    /** 按主题分组检索地点，perTopicLimit 控制每个主题取的 POI 数量（普通检索 10，候选池 18） */
    private SearchResult searchWithPoolSize(String city, String objective, int perTopicLimit) {
        List<SearchTopic> topics = inferTopics(objective);
        List<SearchGroup> groups = new ArrayList<>();
        Map<String, Place> allPlaces = new LinkedHashMap<>();
        for (SearchTopic topic : topics) {
            List<Place> places =
                    amapKey == null || amapKey.isBlank()
                            ? List.of()
                            : searchAmap(city, topic, perTopicLimit);
            places.forEach(
                    place ->
                            allPlaces.putIfAbsent(
                                    place.poiId().isBlank()
                                            ? place.name() + "|" + place.address()
                                            : place.poiId(),
                                    place));
            String webSources = webSearch.searchLocalPlaces(city, topic.query(), topic.label(), 4);
            groups.add(
                    new SearchGroup(
                            topic.label(),
                            topic.query(),
                            places,
                            webSources,
                            topic.intentCategory(),
                            topic.amapTypeCodes(),
                            topic.searchKeywords()));
        }
        String keywords = String.join("、", topics.stream().map(SearchTopic::label).toList());
        return new SearchResult(
                "按类别独立检索", city, keywords, new ArrayList<>(allPlaces.values()), "", groups);
    }

    /** 检索地点并直接产出行程证据：先 search 拿分组结果，再 buildJourneyEvidence 选点+算路线。 */
    @Override
    public JourneyResearchResult researchJourney(String city, String objective) {
        SearchResult searchResult = search(city, objective);
        return new JourneyResearchResult(searchResult, buildJourneyEvidence(searchResult));
    }

    /** 从检索结果构建可核验的行程证据。 选最多 4 个行程地点，相邻地点间逐条算路线；根据选点/路线情况生成降级说明 notice， 无地点→DEGRADED，有地点→LIVE。 */
    @Override
    public JourneyEvidence buildJourneyEvidence(SearchResult searchResult) {
        // 选 6 个路线点（每个类别取第一个），用于路线规划与默认展示
        List<Place> selectedPlaces = selectItineraryPlaces(searchResult, 6);
        List<RoutePlan> routes = new ArrayList<>();
        // 相邻地点间逐条规划路线
        if (amapKey != null && !amapKey.isBlank()) {
            for (int i = 0; i + 1 < selectedPlaces.size(); i++) {
                RoutePlan route = planRoute(selectedPlaces.get(i), selectedPlaces.get(i + 1));
                if (route != null) routes.add(route);
            }
        }
        String notice;
        if (selectedPlaces.isEmpty()) {
            notice =
                    amapKey == null || amapKey.isBlank()
                            ? "未配置高德地图密钥，暂时无法检索可核验的地图地点。"
                            : "高德地图服务已配置，但按当前地点范围严格筛选后没有合格结果；请检查地点范围是否包含多个城市，或调整检索条件后重试。";
        } else if (selectedPlaces.size() == 1) {
            notice = "已取得一个真实地点，至少需要两个地点才能计算地点间路线。";
        } else if (routes.isEmpty()) {
            notice = "地点已取得，但实时路线暂不可用；出发前请打开地图链接核验。";
        } else {
            notice = "距离、耗时和出行方式来自高德地图实时路线；短途步行，中途骑行，较远路程驾车，出发前仍建议核验路况与营业状态。";
        }
        // 地图卡片：为全部候选地点建卡片（上限 12 个），路线信息只附在 selectedPlaces 上
        List<MapCard> allCards = buildMapCards(searchResult.places(), routes, selectedPlaces);
        return new JourneyEvidence(
                searchResult.provider(),
                searchResult.city(),
                searchResult.keywords(),
                selectedPlaces,
                routes,
                selectedPlaces.isEmpty() ? "DEGRADED" : "LIVE",
                notice,
                SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))),
                allCards);
    }

    /** 为候选地点建地图卡片。默认展示前 6 个，其余通过前端"展开更多"查看； 路线信息（上一站驾车/步行）只附在 selectedPlaces 范围内的卡片上。 */
    private List<MapCard> buildMapCards(
            List<Place> places, List<RoutePlan> routes, List<Place> selectedPlaces) {
        List<MapCard> cards = new ArrayList<>();
        int limit = Math.min(places.size(), 12);
        for (int index = 0; index < limit; index++) {
            Place place = places.get(index);
            // routes 按相邻地点对顺序排列：routes.get(i) 是 places[i]→places[i+1] 的路线，
            // 因此第 index 个地点（index>0）的"上一段路线"对应 routes.get(index-1)；首地点无来路
            // 只有在 selectedPlaces 序列中的地点才附带路线信息
            RoutePlan routeFromPrevious = null;
            if (index > 0 && selectedPlaces.contains(place) && index - 1 < routes.size()) {
                routeFromPrevious = routes.get(index - 1);
            }
            cards.add(toMapCard(place, routeFromPrevious));
        }
        return cards;
    }

    /**
     * 把单个 Place 组装成 MapCard（含经纬度拆分、营业时间兜底标记、高德来源标记）。 categoryOverride 非空时覆盖 place.type()，用于把检索类别
     * label（如"过山车""美食"） 写进卡片，方便前端与等待确认文本按类别分组展示。
     */
    private MapCard toMapCard(Place place, RoutePlan routeFromPrevious) {
        return toMapCard(place, routeFromPrevious, null);
    }

    private MapCard toMapCard(Place place, RoutePlan routeFromPrevious, String categoryOverride) {
        String[] coordinates = place.location().split(",", 2);
        String category =
                (categoryOverride != null && !categoryOverride.isBlank())
                        ? categoryOverride
                        : place.type();
        return new MapCard(
                place.poiId(),
                place.name(),
                place.address(),
                category,
                place.tel(),
                coordinates.length > 0 ? coordinates[0] : "",
                coordinates.length > 1 ? coordinates[1] : "",
                place.mapUrl(),
                place.photoUrl(),
                place.rating(),
                place.businessHours(),
                place.businessStatus(),
                place.statusCheckedAt(),
                place.businessHours().isBlank()
                        ? "NO_OPENING_HOURS"
                        : "DERIVED_FROM_AMAP_OPENING_HOURS",
                "高德地图",
                place.mapUrl(),
                routeFromPrevious);
    }

    /**
     * 基于已持久化的候选池，按类别均衡随机重抽一批候选卡片（不调外部 API）。 行程主线 selectedPlaces 与路线 routes 原样保留，主线卡片排最前并附路线；
     * 其余位置从每个类别内部洗牌后抽取：每类最少补 {@link #MIN_CANDIDATES_PER_CATEGORY} 个、 最多补 {@link
     * #MAX_CANDIDATES_PER_CATEGORY} 个，按该类实际 POI 数动态取中间值。 总卡片数根据候选池实际大小动态封顶（最少 10、最多 {@link
     * #HARD_MAX_CARDS}）， 前端默认展示前 6 个，"展开全部"按钮显示剩余。seed 不同 → 抽样顺序不同 → 每次换一批。
     */
    @Override
    public JourneyEvidence reshuffleCandidateCards(
            JourneyEvidence current, SearchResult pool, long seed) {
        List<Place> selectedPlaces = current.places();
        List<RoutePlan> routes = current.routes();

        // 主线卡片排最前，路线信息按相邻位置附在第 i 个地点上
        List<MapCard> cards = new ArrayList<>();
        for (int i = 0; i < selectedPlaces.size(); i++) {
            RoutePlan rp = (i > 0 && i - 1 < routes.size()) ? routes.get(i - 1) : null;
            cards.add(toMapCard(selectedPlaces.get(i), rp));
        }

        // 主线里已有的地点不再作为候选重复出现
        java.util.Set<String> selectedKeys = new java.util.HashSet<>();
        for (Place p : selectedPlaces) selectedKeys.add(placeKey(p));
        // 上次已经展示过的候选卡片也排除，让"换一批"每次都尽量露出新地点，
        // 避免 shuffle 后又翻回用户刚看过的卡片（主线带路线除外）。
        for (MapCard c : current.mapCards()) {
            if (c.poiId() != null && !c.poiId().isBlank()) selectedKeys.add(c.poiId());
        }

        // 总卡片数按候选池实际大小动态封顶：池小就全展示，池大就封顶 HARD_MAX_CARDS
        int effectiveMax = Math.max(10, Math.min(pool.places().size(), HARD_MAX_CARDS));

        // 每个分组内部用 seed 驱动的 Random 洗牌，再按该类 POI 数量动态取前 N 个候选
        java.util.Random rnd = new java.util.Random(seed);
        for (SearchGroup group : pool.groups()) {
            if (cards.size() >= effectiveMax) break;
            List<Place> shuffled = new ArrayList<>(group.places());
            java.util.Collections.shuffle(shuffled, rnd);
            // 每类补几个：按该类 POI 数线性插值，最少 MIN_CANDIDATES_PER_CATEGORY、最多 MAX_CANDIDATES_PER_CATEGORY
            int perCategory =
                    Math.max(
                            MIN_CANDIDATES_PER_CATEGORY,
                            Math.min(MAX_CANDIDATES_PER_CATEGORY, shuffled.size()));
            int picked = 0;
            for (Place p : shuffled) {
                if (picked >= perCategory) break;
                if (cards.size() >= effectiveMax) break;
                if (selectedKeys.contains(placeKey(p))) continue;
                // 把该类别 label（如"过山车""美食"）写进卡片 category 字段，
                // 等待确认文本按类别分组时就能直接用，不用再回查 pool。
                cards.add(toMapCard(p, null, group.label()));
                picked++;
            }
        }

        return new JourneyEvidence(
                current.provider(),
                current.city(),
                current.topics(),
                selectedPlaces,
                routes,
                current.sourceStatus(),
                current.notice(),
                current.searchedAt(),
                cards);
    }

    /** 从分组结果中挑选行程地点：优先每个分组取第一个（保证类别多样性）， 不足 limit 时再从全部地点里补齐，按 placeKey 去重。 */
    private List<Place> selectItineraryPlaces(SearchResult searchResult, int limit) {
        Map<String, Place> selected = new LinkedHashMap<>();
        for (SearchGroup group : searchResult.groups()) {
            if (group.places().isEmpty()) continue;
            Place place = group.places().getFirst();
            selected.putIfAbsent(placeKey(place), place);
            if (selected.size() >= limit) return new ArrayList<>(selected.values());
        }
        for (Place place : searchResult.places()) {
            selected.putIfAbsent(placeKey(place), place);
            if (selected.size() >= limit) break;
        }
        return new ArrayList<>(selected.values());
    }

    private String placeKey(Place place) {
        return place.poiId().isBlank()
                ? place.name() + "|" + place.address() + "|" + place.location()
                : place.poiId();
    }

    /**
     * 规划两地点间的实时路线。 先按直线距离选出行方式（步行/骑行/驾车），再调对应高德路线接口取首条路径， 提取距离、耗时、折线，并拼接高德 uri 导航链接。任何异常返回
     * null（路线降级）。
     */
    private RoutePlan planRoute(Place origin, Place destination) {
        if (origin.location().isBlank() || destination.location().isBlank()) return null;
        try {
            // 用直线距离决定调用哪种路线接口
            String mode =
                    modeForDistance(
                            directDistanceMeters(origin.location(), destination.location()));
            String endpoint =
                    switch (mode) {
                        case "BICYCLING" -> AMAP_BICYCLING_URL;
                        case "DRIVING" -> AMAP_DRIVING_URL;
                        default -> AMAP_WALKING_URL;
                    };
            String response =
                    HttpUtil.get(
                            endpoint,
                            Map.of(
                                    "key",
                                    amapKey,
                                    "origin",
                                    origin.location(),
                                    "destination",
                                    destination.location()));
            JSONObject root = JSONUtil.parseObj(response);
            JSONObject route =
                    "BICYCLING".equals(mode)
                            ? root.getJSONObject("data")
                            : root.getJSONObject("route");
            JSONArray paths = route == null ? null : route.getJSONArray("paths");
            if (paths == null || paths.isEmpty()) return null;
            JSONObject path = paths.getJSONObject(0);
            long distanceMeters = path.getLong("distance", 0L);
            long durationSeconds = path.getLong("duration", 0L);
            String polyline = routePolyline(path);
            if (distanceMeters <= 0 || durationSeconds <= 0) return null;
            String navigationUrl =
                    "https://uri.amap.com/navigation?from="
                            + origin.location()
                            + ","
                            + URLEncoder.encode(origin.name(), StandardCharsets.UTF_8)
                            + "&to="
                            + destination.location()
                            + ","
                            + URLEncoder.encode(destination.name(), StandardCharsets.UTF_8)
                            + "&mode="
                            + navigationMode(mode)
                            + "&policy=1&src=heart-pilot&coordinate=gaode&callnative=0";
            return new RoutePlan(
                    origin.name(),
                    destination.name(),
                    distanceMeters,
                    Math.max(1, Math.round(durationSeconds / 60.0)),
                    mode,
                    navigationUrl,
                    "LIVE",
                    SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))),
                    "高德地图",
                    "RECOMMENDED_" + mode,
                    polyline);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按直线距离选择出行方式：≤1.8km 步行，≤6km 骑行，更远驾车。 */
    static String modeForDistance(double distanceMeters) {
        if (distanceMeters <= 1_800) return "WALKING";
        if (distanceMeters <= 6_000) return "BICYCLING";
        return "DRIVING";
    }

    private String navigationMode(String mode) {
        return switch (mode) {
            case "BICYCLING" -> "ride";
            case "DRIVING" -> "car";
            default -> "walk";
        };
    }

    /** 用 Haversine 公式计算两经纬度坐标间的直线距离（米），仅用于选择出行方式。 坐标格式 "lng,lat"，解析失败返回 Double.MAX_VALUE 以走最远路线。 */
    private double directDistanceMeters(String origin, String destination) {
        String[] from = origin.split(",", 2);
        String[] to = destination.split(",", 2);
        if (from.length < 2 || to.length < 2) return Double.MAX_VALUE;
        double fromLng = Math.toRadians(Double.parseDouble(from[0]));
        double fromLat = Math.toRadians(Double.parseDouble(from[1]));
        double toLng = Math.toRadians(Double.parseDouble(to[0]));
        double toLat = Math.toRadians(Double.parseDouble(to[1]));
        double latDelta = toLat - fromLat;
        double lngDelta = toLng - fromLng;
        double value =
                Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                        + Math.cos(fromLat)
                                * Math.cos(toLat)
                                * Math.sin(lngDelta / 2)
                                * Math.sin(lngDelta / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }

    /**
     * 调用高德 POI 文本检索接口取一个主题下的地点。 citylimit=true 限定城市，extensions=all 取详情；逐条 POI 先按主题过滤（matchesTopic），
     * 再按行政范围过滤（matchesRequestedScope），最后组装 Place。接口失败记 warn 并返回空。 perTopicLimit：普通检索 10，候选池前置检索
     * 18（供候选挑选 Agent 使用）。
     */
    private List<Place> searchAmap(String city, SearchTopic topic, int perTopicLimit) {
        String keywords = topic.query();
        try {
            // 高德 city 参数只认"XX市"/"XX"/adcode，不认"XX省XX市"这种省+市拼接；
            // 传拼接值会导致 citylimit 失效、返回全国结果。去掉省前缀，只保留"XX市"。
            String amapCity = city == null ? "" : city.replaceFirst("^.+省", "");
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("key", amapKey);
            parameters.put("keywords", keywords);
            parameters.put("city", amapCity);
            parameters.put("citylimit", "true");
            parameters.put("offset", String.valueOf(Math.max(perTopicLimit + 4, 14)));
            parameters.put("page", "1");
            parameters.put("extensions", "all");
            if (!topic.amapTypeCodes().isEmpty()) {
                parameters.put("types", String.join("|", topic.amapTypeCodes()));
            }
            String response = HttpUtil.get(AMAP_URL, parameters);
            JSONObject root = JSONUtil.parseObj(response);
            if (!"1".equals(root.getStr("status", ""))) {
                log.warn(
                        "AMap place search failed: city={}, keywords={}, info={}, infocode={}",
                        city,
                        keywords,
                        root.getStr("info", "unknown"),
                        root.getStr("infocode", "unknown"));
                return List.of();
            }
            JSONArray pois = root.getJSONArray("pois");
            Map<String, Place> uniquePlaces = new LinkedHashMap<>();
            if (pois != null) {
                for (int i = 0; i < Math.min(perTopicLimit, pois.size()); i++) {
                    JSONObject poi = pois.getJSONObject(i);
                    String poiId = poi.getStr("id", "").trim();
                    String name = poi.getStr("name", "未命名地点");
                    String address = poi.getStr("address", "地址待确认");
                    String location = poi.getStr("location", "");
                    String type = poi.getStr("type", "本地生活");
                    String typeCode = poi.getStr("typecode", "");
                    String tel = poi.getStr("tel", "");
                    if (!matchesTopic(keywords, name, type, typeCode)) continue;
                    String provinceName = poi.getStr("pname", "");
                    String cityName = poi.getStr("cityname", "");
                    String districtName = poi.getStr("adname", "");
                    if (!matchesRequestedScope(
                            city, provinceName, cityName, districtName, address)) {
                        log.info(
                                "AMap scope reject: requested='{}', pname='{}', cityname='{}', adname='{}', name='{}'",
                                city,
                                provinceName,
                                cityName,
                                districtName,
                                name);
                        continue;
                    }
                    JSONObject business = poi.getJSONObject("business");
                    JSONObject bizExt = poi.getJSONObject("biz_ext");
                    String businessHours =
                            firstNotBlank(
                                    business == null ? "" : business.getStr("opentime_today", ""),
                                    business == null ? "" : business.getStr("opentime_week", ""),
                                    bizExt == null ? "" : bizExt.getStr("opentime2", ""),
                                    bizExt == null ? "" : bizExt.getStr("open_time", ""));
                    String rating =
                            firstNotBlank(
                                    business == null ? "" : business.getStr("rating", ""),
                                    bizExt == null ? "" : bizExt.getStr("rating", ""));
                    JSONArray photos = poi.getJSONArray("photos");
                    String photoUrl =
                            photos == null || photos.isEmpty()
                                    ? ""
                                    : photos.getJSONObject(0).getStr("url", "");
                    String checkedAt =
                            SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
                    String businessStatus = inferBusinessStatus(businessHours);
                    String mapUrl =
                            !poiId.isBlank()
                                    ? "https://www.amap.com/place/" + poiId
                                    : location.isBlank()
                                            ? ""
                                            : "https://uri.amap.com/marker?position="
                                                    + location
                                                    + "&name="
                                                    + URLEncoder.encode(
                                                            name, StandardCharsets.UTF_8);
                    String uniqueKey =
                            !poiId.isBlank() ? poiId : name + "|" + address + "|" + location;
                    uniquePlaces.putIfAbsent(
                            uniqueKey,
                            new Place(
                                    poiId,
                                    name,
                                    address,
                                    type,
                                    tel,
                                    location,
                                    mapUrl,
                                    rating,
                                    businessHours,
                                    businessStatus,
                                    checkedAt,
                                    photoUrl,
                                    topic.intentCategory(),
                                    typeCode));
                }
            }
            return new ArrayList<>(uniquePlaces.values());
        } catch (Exception e) {
            log.warn(
                    "AMap place search request failed: city={}, keywords={}, error={}",
                    city,
                    keywords,
                    e.toString());
            return List.of();
        }
    }

    /**
     * 严格校验 POI 是否落在请求范围内。 逐级匹配：区 → 市 → 省 → 地址文本；任一级命中即接受，否则丢弃。 这是防止高德 citylimit 失效后混入外市 POI 的最后防线。
     */
    static boolean matchesRequestedScope(
            String requestedScope,
            String provinceName,
            String cityName,
            String districtName,
            String address) {
        String requested = normalizeRegion(requestedScope);
        if (requested.isBlank()) return false;
        String province = normalizeRegion(provinceName);
        String city = normalizeRegion(cityName);
        String district = normalizeRegion(districtName);
        String fullAddress = normalizeRegion(address);

        // AMap can ignore citylimit when a free-form composite scope cannot be resolved. Never
        // accept a POI unless its returned administrative area is explicitly part of the scope.
        if (!district.isBlank() && requested.contains(district)) return true;
        if (!city.isBlank() && requested.contains(city)) return true;
        if (!province.isBlank() && requested.equals(province)) return true;
        return !fullAddress.isBlank()
                && (fullAddress.contains(requested) || requested.contains(fullAddress));
    }

    static boolean matchesTopic(String topic, String name, String type) {
        return matchesTopic(topic, name, type, "");
    }

    static boolean matchesTopic(String topic, String name, String type, String typeCode) {
        var category = LocalPlaceIntentCatalog.classify(topic);
        if (category.isPresent()) {
            // 命中已知类别：用类别规则做精度过滤，避免搜出无关 POI
            return category.get().matches(topic, name, type, typeCode);
        }
        // 动态词（CUSTOM）：先走细分词强规则（游泳/游艇/海鲜），
        // 未命中细规则时要求 POI 名称/类型包含归一化后的用户关键词，不再无条件放行——
        // 否则"睡觉"会匹配"天津市"、"宠物摄影"会匹配"照相馆"等与意图无关的 POI。
        Optional<LocalPlaceIntentCatalog.SpecificIntentRule> specific =
                LocalPlaceIntentCatalog.specificFor(topic);
        if (specific.isPresent()) {
            return specific.get().matches(safeText(name) + " " + safeText(type));
        }
        String keyword = normalizeIntent(topic);
        if (keyword.isBlank()) return false;
        return (safeText(name) + " " + safeText(type)).contains(keyword);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 归一化行政地名：去除空白与标点分隔符，并剥掉末尾的行政后缀（省/市/区/县/自治区/自治州等）， 使"山东省"与"山东"、"南宁市"与"南宁"能互相匹配，用于
     * matchesRequestedScope 的宽松范围校验。
     */
    private static String normalizeRegion(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s,，、/\\-|]+", "")
                .replaceAll("(?:壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|地区|盟|省|市|区|县)$", "")
                .trim();
    }

    /**
     * 从用户目标文本推断检索主题列表（最多 MAX_TOPICS 个）。
     *
     * <p>优先走 LLM 关键词提取：把完整需求串（目标｜问题｜场所偏好｜预算）交给
     * {@link PlaceKeywordExtractor}，提炼成干净的高德 POI 短词，每个短词直接成为一个
     * SearchTopic。LLM 不可用或返回空时，回退到原有规则切句（按标点切句 + 否定句过滤 +
     * LocalPlaceIntentCatalog 归类），保证任何输入都能产出检索主题。
     */
    private List<SearchTopic> inferTopics(String objective) {
        String safeObjective = objective == null ? "" : objective.trim();

        // 第一优先级：LLM 提取的干净关键词，每个词直接作为一个检索主题
        List<String> llmKeywords = keywordExtractor.extract(safeObjective);
        if (!llmKeywords.isEmpty()) {
            List<SearchTopic> llmTopics = new ArrayList<>();
            for (String kw : llmKeywords) {
                SearchTopic topic = buildTopicFromKeyword(kw);
                llmTopics.add(topic);
                if (llmTopics.size() >= MAX_TOPICS) break;
            }
            if (!llmTopics.isEmpty()) {
                log.info("地点检索使用 LLM 关键词主题：{}",
                        llmTopics.stream().map(SearchTopic::label).toList());
                return llmTopics;
            }
        }

        // 回退：规则切句归类（旧逻辑）
        return ruleBasedTopics(safeObjective);
    }

    /**
     * 把单个 LLM 关键词包装成 SearchTopic。 用 {@link LocalPlaceIntentCatalog} 归类：命中已知类别时挂上高德类型码做精度过滤；
     * 未命中按 CUSTOM 动态词处理，关键词原词直接作为高德检索词。
     */
    private SearchTopic buildTopicFromKeyword(String keyword) {
        String label = shorten(keyword, 24);
        var category = LocalPlaceIntentCatalog.classify(keyword);
        final String searchWord = keyword;
        String query =
                category.map(LocalPlaceIntentCatalog::searchKeywords)
                        .filter(ks -> !ks.isEmpty())
                        .map(ks -> ks.get(0))
                        .orElse(searchWord);
        return new SearchTopic(
                label,
                query,
                category.map(Enum::name).orElse("CUSTOM"),
                category.map(LocalPlaceIntentCatalog::amapTypesForRequest).orElseGet(List::of),
                category.map(LocalPlaceIntentCatalog::searchKeywords)
                        .orElseGet(() -> List.of(searchWord)));
    }

    /**
     * 规则切句回退路径：按标点切句，过滤否定句和纯预算句，归一化口语后归类到
     * LocalPlaceIntentCatalog，同标签去重。
     */
    private List<SearchTopic> ruleBasedTopics(String safeObjective) {
        List<String> clauses =
                List.of(safeObjective.split("[｜；。！？，,、？?\\n]+")).stream()
                        .filter(value -> !value.trim().matches("^(?:不|不要|别|避免|排除|拒绝|不能).*"))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
        Map<String, SearchTopic> topics = new LinkedHashMap<>();
        for (String clause : clauses) {
            if (clause.matches("^(?:\\d+(?:\\.\\d+)?元?|未限定)$")) continue;
            String intent = normalizeIntent(clause);
            if (intent.isBlank()) intent = clause;
            String label = shorten(intent, 24);
            var category = LocalPlaceIntentCatalog.classify(intent);
            final String searchWord = intent;
            String query =
                    category.map(LocalPlaceIntentCatalog::searchKeywords)
                            .filter(ks -> !ks.isEmpty())
                            .map(ks -> ks.get(0))
                            .orElse(searchWord);
            topics.putIfAbsent(
                    label,
                    new SearchTopic(
                            label,
                            query,
                            category.map(Enum::name).orElse("CUSTOM"),
                            category.map(LocalPlaceIntentCatalog::amapTypesForRequest)
                                    .orElseGet(List::of),
                            category.map(LocalPlaceIntentCatalog::searchKeywords)
                                    .orElseGet(() -> List.of(searchWord))));
            if (topics.size() >= MAX_TOPICS) break;
        }
        return new ArrayList<>(topics.values());
    }

    /**
     * 归一化用户口语意图：去掉开头的礼貌/请求前缀（"请问""我想找""有没有"等）和结尾的语气/推荐后缀（"呢""吗""推荐一下"等），
     * 以及"附近/周边/相关/地方/地点"等无信息量泛词，得到干净的核心关键词，再交给高德全文搜索。
     */
    private static String normalizeIntent(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceFirst(
                        "^(?:请问|我想知道|我想|想要|想|帮我找|帮我|请帮我找|请帮我|有没有|有|有哪些|哪里有|哪里可以|去|玩|喝|吃|找)+", "")
                .replaceFirst("^(?:地方|地点)", "")
                .replaceAll("(?:的地方|相关地点|相关信息|哪里有|哪里可以|怎么样|怎么安排|推荐一下|推荐|吗|呢)$", "")
                .replaceAll("^[\\d.、\\s]+", "")
                // 去掉"附近/周边/相关"等无信息量泛词，让 keywords 聚焦核心名词
                .replaceAll("(?:附近|周边|相关)", "")
                .trim();
    }

    private String firstNotBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    /** 把路径各 step 的折线坐标拼接成一条完整 polyline 字符串。 相邻 step 的折线首尾坐标重复，拼时跳过上一段末尾坐标，避免折返点。 */
    private String routePolyline(JSONObject path) {
        JSONArray steps = path.getJSONArray("steps");
        if (steps == null || steps.isEmpty()) return "";
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            String segment = steps.getJSONObject(index).getStr("polyline", "").trim();
            if (segment.isBlank()) continue;
            if (!segments.isEmpty()) {
                int separator = segment.indexOf(';');
                segment = separator < 0 ? "" : segment.substring(separator + 1);
            }
            if (!segment.isBlank()) segments.add(segment);
        }
        return String.join(";", segments);
    }

    /** Best-effort status derived from the opening-hours field returned by AMap. */
    private String inferBusinessStatus(String hours) {
        if (hours == null || hours.isBlank()) return "UNKNOWN";
        String normalized = hours.replace('：', ':');
        if (normalized.contains("24小时") || normalized.contains("00:00-24:00")) return "OPEN";
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")
                        .matcher(normalized);
        if (!matcher.find()) return "UNKNOWN";
        LocalTime start =
                LocalTime.of(
                        Integer.parseInt(matcher.group(1)) % 24,
                        Integer.parseInt(matcher.group(2)));
        LocalTime end =
                LocalTime.of(
                        Integer.parseInt(matcher.group(3)) % 24,
                        Integer.parseInt(matcher.group(4)));
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Shanghai"));
        boolean open =
                end.isAfter(start)
                        ? !now.isBefore(start) && now.isBefore(end)
                        : !now.isBefore(start) || now.isBefore(end);
        return open ? "OPEN" : "CLOSED";
    }

    private String shorten(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }

    private record SearchTopic(
            String label,
            String query,
            String intentCategory,
            List<String> amapTypeCodes,
            List<String> searchKeywords) {}
}
