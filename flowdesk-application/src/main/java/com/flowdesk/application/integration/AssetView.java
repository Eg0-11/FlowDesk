package com.flowdesk.application.integration;

import java.util.regex.Pattern;

/**
 * 一条资产记录（FD-0016）：{@code asset_get} 命中结果的框架无关表示。
 *
 * <p>字段与远端载荷一一对应：{@code assetId}、{@code assetType}、{@code status}、{@code source}。
 * 不变量在构造期校验，因此「编号不符」或「来源缺失」的对象根本构造不出来。</p>
 *
 * <p><b>为什么 assetType / status 只校验形状而不是封闭枚举</b>：两个 MCP 服务把它们定义为
 * 「稳定枚举名」（例如 {@code SERVER} / {@code IN_SERVICE}），但<b>没有</b>公布完整取值集合，
 * 客户端凭空维护一份白名单会在真实数据出现新取值时误判。因此这里只要求它们是
 * 大写下划线风格的有界标识符（形状），而把「未知取值」的拒绝留给真正封闭的字段
 * ——例如监控的 {@code health}。这条取舍见 ADR 0013。</p>
 *
 * @param assetId   资产标识（{@code AST-123456}）
 * @param assetType 资产类型（大写下划线风格标识符，例如 {@code SERVER}）
 * @param status    资产状态（大写下划线风格标识符，例如 {@code IN_SERVICE}）
 * @param source    这条记录来自哪里（必须存在，不得为 {@code null}）
 */
public record AssetView(String assetId, String assetType, String status, SourceOrigin source) {

    /** 类型与状态允许的形状：大写字母开头，后接大写字母、数字或下划线，总长不超过 32。 */
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    /**
     * @throws IllegalArgumentException 任一不变量被破坏
     */
    public AssetView {
        if (!AssetIdentifier.isValid(assetId)) {
            throw new IllegalArgumentException("assetId 必须形如 AST-123456");
        }
        requireToken(assetType, "assetType");
        requireToken(status, "status");
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
    }

    private static void requireToken(String value, String field) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 必须是稳定枚举名（大写下划线标识符）");
        }
    }
}
