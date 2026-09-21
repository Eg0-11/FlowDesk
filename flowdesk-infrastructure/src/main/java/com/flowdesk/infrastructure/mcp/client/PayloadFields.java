package com.flowdesk.infrastructure.mcp.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.application.integration.SourceOrigin;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 远端载荷的严格读取工具（FD-0016）。
 *
 * <p>两个方向的严格性都在这里：</p>
 * <ul>
 *   <li><b>解析层</b>：拒绝尾随内容（两段 JSON 拼接）与重复字段名 —— 前者说明对端在流里多写了
 *       东西，后者会让「读到的是哪一个值」取决于解析器实现；</li>
 *   <li><b>字段层</b>：{@link #hasExactFields(JsonNode, Set)} 要求字段集合<b>完全等于</b>已公布的
 *       集合。多字段说明远端契约变了（可能是新字段，也可能是塞进了不该塞的东西），
 *       少字段说明这条响应不完整 —— 两种都不猜，直接按非法响应拒绝。</li>
 * </ul>
 *
 * <p>所有取值方法在「缺失、类型不符、空白、超范围」时返回 {@code null}，
 * 由调用方统一映射为 {@link com.flowdesk.application.integration.QueryFailure#INVALID_RESPONSE}。</p>
 */
final class PayloadFields {

    /** 单条工具载荷的长度上限（字符）：本阶段两个服务的载荷都不到 200 字符。 */
    static final int MAX_PAYLOAD_CHARS = 65536;

    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PayloadFields() {
    }

    /**
     * @param text 工具载荷
     * @return JSON 对象节点；不是合法 JSON、不是对象或超长时为 {@code null}
     */
    static JsonNode readObject(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            return root != null && root.isObject() ? root : null;
        }
        catch (Exception ex) {
            // 解析细节（含原文片段）不向上传递
            return null;
        }
    }

    /**
     * @param node     载荷
     * @param expected 期望的字段集合
     * @return 字段集合是否完全一致
     */
    static boolean hasExactFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.size() == expected.size() && actual.containsAll(expected);
    }

    /**
     * @param node  载荷
     * @param field 字段名
     * @return 非空白字符串；缺失、非字符串或空白时为 {@code null}
     */
    static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /**
     * @param node  载荷
     * @param field 字段名
     * @return 整数值；缺失、非整数或超出 {@code int} 范围时为 {@code null}
     */
    static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        return value.intValue();
    }

    /**
     * @param node  载荷
     * @param field 字段名
     * @return 布尔值；缺失或非布尔时为 {@code null}（字符串 {@code "false"} 不算 false）
     */
    static Boolean booleanOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    /**
     * @param node  载荷
     * @param field 字段名
     * @return 时刻；缺失或不是合法的 ISO-8601 时刻时为 {@code null}
     */
    static Instant instantOf(JsonNode node, String field) {
        String text = textOf(node, field);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * 读取并校验 {@code source}：必须存在，且只能是 {@code DEMO} 或 {@code REAL}。
     *
     * <p>缺失或为 {@code null} 一律拒绝，而不是补一个默认值 —— 补默认值等于伪造血缘；
     * 也绝不会把 {@code DEMO} 提升成 {@code REAL}。</p>
     *
     * @param node 载荷
     * @return 来源；不合法时为 {@code null}
     */
    static SourceOrigin sourceOf(JsonNode node) {
        String text = textOf(node, "source");
        if (text == null) {
            return null;
        }
        for (SourceOrigin origin : SourceOrigin.values()) {
            if (origin.name().equals(text)) {
                return origin;
            }
        }
        return null;
    }

    /**
     * 读取并校验一个封闭枚举字段。
     *
     * @param node  载荷
     * @param field 字段名
     * @param type  枚举类型
     * @param <E>   枚举
     * @return 枚举常量；缺失或取值未知时为 {@code null}
     */
    static <E extends Enum<E>> E enumOf(JsonNode node, String field, Class<E> type) {
        String text = textOf(node, field);
        if (text == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        return null;
    }
}
