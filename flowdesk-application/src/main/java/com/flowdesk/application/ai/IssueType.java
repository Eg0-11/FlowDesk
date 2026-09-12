package com.flowdesk.application.ai;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具调用冒烟用例支持的工单类型。
 *
 * <p>这里是允许值的唯一定义处：HTTP 边界校验与本地工具都以此为准，
 * 保证“未知类型明确失败”而不是让模型自由编造。</p>
 */
public enum IssueType {

    /** 账号锁定。 */
    ACCOUNT_LOCK,

    /** VPN 故障。 */
    VPN_FAILURE,

    /** 设备离线。 */
    DEVICE_OFFLINE;

    /** 允许值的展示文本，用于错误提示。 */
    public static final String ALLOWED_VALUES = "ACCOUNT_LOCK, VPN_FAILURE, DEVICE_OFFLINE";

    /**
     * 宽松解析：去空白、忽略大小写。
     *
     * @param raw 原始文本，可为 {@code null}
     * @return 匹配到的枚举值，未匹配时为空
     */
    public static Optional<IssueType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(value -> value.name().equals(normalized))
                .findFirst();
    }

    /**
     * 严格解析。
     *
     * @param raw 原始文本
     * @return 匹配到的枚举值
     * @throws AiRequestException 未匹配到允许值；错误信息不回显调用方传入的原始文本
     */
    public static IssueType require(String raw) {
        return parse(raw).orElseThrow(() -> new AiRequestException(
                "issueType 不受支持，允许值：" + ALLOWED_VALUES));
    }
}
