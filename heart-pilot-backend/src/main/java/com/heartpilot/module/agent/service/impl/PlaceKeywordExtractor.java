package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 地点搜索关键词 LLM 提取器。
 *
 * <p>职责：把用户在地点见面表单里填的所有输入（目标与约束、场所类型多选、
 * 希望回答的问题、明确边界、预算、人数）交给大模型，提炼成 3-6 个干净的
 * 高德 POI 搜索关键词（每个词是 2-6 字的名词短语），再交给
 * {@link PlaceSearchServiceImpl} 按"一个关键词一组地点"检索。
 *
 * <p>为什么需要这一层：旧逻辑把问句原文（"哪家店适合安静聊天""下雨时有什么室内备选"）
 * 直接当高德关键词搜，结果要么搜不到要么搜出无关 POI；"场所偏好：吃饭"带前缀也匹配不上
 * {@link LocalPlaceIntentCatalog} 类别。LLM 负责把口语诉求翻译成地图可搜的短词。
 *
 * <p>可靠性：模型不可用或解析失败时返回空列表，调用方自动回退到原有规则切句，
 * 绝不因关键词提取失败阻断地点检索主流程。
 */
@Service
public class PlaceKeywordExtractor {
    private static final Logger log = LoggerFactory.getLogger(PlaceKeywordExtractor.class);

    /** 系统提示词：约束输出为 JSON 数组，每个关键词是短名词短语 */
    private static final String SYSTEM_PROMPT =
            """
            你是地点搜索关键词提取器。根据用户的约会/见面需求，提取 3-6 个用于在高德地图上
            搜索 POI 的关键词。每个关键词必须是 2-6 个字的名词短语，不要问句，不要长句。

            提取规则：
            1. 从"场所偏好"里直接取类别词：吃饭→餐厅，咖啡→咖啡馆，公园→公园，看展→展览馆，
               散步→步道/公园，其他场所按用户原词。
            2. 从"希望回答的问题"里反推出隐含的地点类型：
               "哪家店适合安静聊天"→咖啡馆 茶室；
               "下雨时有什么室内备选"→室内场所 商场；
               "两个地点之间怎么走"→这是路线问题，不要提取成地点关键词。
            3. 从"目标与约束"里提取核心诉求地点词（如"喜欢广西菜"→广西菜餐厅）。
            4. 明确边界里的否定词（不要/别/不推荐）不要提取成关键词。
            5. 预算、人数、时间段不是地点关键词，不要输出。
            6. 关键词之间用逗号分隔，去重，总个数 3-6 个。

            只输出 JSON，格式：{"keywords":["餐厅","咖啡馆","公园"]}，不要输出任何额外解释。
            """;

    private final ChatClient client;
    private final boolean enabled;

    /** 构造器注入 DashScope 聊天模型与 API Key，Key 缺失时禁用 LLM 降级路径。 */
    public PlaceKeywordExtractor(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }

    /**
     * 从用户完整需求文本中提取地点搜索关键词。
     *
     * @param requirements PlaceActionEnricher 拼好的完整需求串（目标｜问题｜场所偏好｜预算）
     * @return 去重后的关键词列表；模型不可用/超时/解析失败时返回空列表，由调用方回退规则
     */
    public List<String> extract(String requirements) {
        if (!enabled || requirements == null || requirements.isBlank()) {
            return List.of();
        }
        try {
            KeywordResponse resp =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user("用户需求：\n" + requirements)
                            .call()
                            .entity(KeywordResponse.class);
            if (resp == null || resp.keywords() == null || resp.keywords().isEmpty()) {
                return List.of();
            }
            // 清洗：去空白、去重、去空串、限 6 个，每个词截断到 12 字
            List<String> cleaned = new ArrayList<>();
            for (String kw : resp.keywords()) {
                if (kw == null) continue;
                String t = kw.trim();
                if (t.isEmpty()) continue;
                if (t.length() > 12) t = t.substring(0, 12);
                cleaned.add(t);
                if (cleaned.size() >= 6) break;
            }
            List<String> deduped = new ArrayList<>(new LinkedHashSet<>(cleaned));
            log.info("LLM 地点关键词提取结果：{}", deduped);
            return deduped;
        } catch (Exception e) {
            // 任何异常都不阻断主流程，调用方回退规则切句
            log.warn("地点关键词 LLM 提取失败，回退规则切句：{}", e.toString());
            return List.of();
        }
    }

    /** Spring AI 结构化输出绑定：对应 JSON {"keywords":["...","..."]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeywordResponse(List<String> keywords) {}
}
