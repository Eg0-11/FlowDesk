package com.flowdesk.bootstrap.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;

/**
 * 资产侧查询结果的 HTTP 形状（FD-0017-B）。
 *
 * <p>三种结果的字段集合<b>互不相同</b>，这正是本 DTO 存在的理由 ——
 * 直接把 {@link AssetQueryResult} 交给 Jackson 会把「只对某些状态有意义的字段」也一并序列化，
 * 于是调用方必须自己记住「哪些 {@code null} 是可以忽略的、哪些是异常」。</p>
 *
 * <table border="1">
 *   <caption>按 outcome 决定的字段</caption>
 *   <tr><th>outcome</th><th>输出字段</th></tr>
 *   <tr><td>{@code FOUND}</td>
 *       <td>{@code outcome}、{@code assetId}、{@code assetType}、{@code status}、{@code source}</td></tr>
 *   <tr><td>{@code NOT_FOUND}</td>
 *       <td>{@code outcome}、{@code assetId}、{@code source}（<b>不</b>伪造资产详情）</td></tr>
 *   <tr><td>{@code FAILED}</td>
 *       <td>{@code outcome}、{@code failure}（<b>不</b>输出 {@code assetId}/{@code source}/伪造数据）</td></tr>
 * </table>
 *
 * <p>{@code FOUND} 的来源取自命中的记录（{@code AssetView.source}），因为
 * {@code AssetQueryResult} 的顶层 {@code source} 按契约只在 {@code NOT_FOUND} 时才有值。</p>
 *
 * <p>所有字段都是扁平标量或稳定枚举名，<b>不</b>包含 MCP 原始报文、端点、会话标识、
 * 异常类名与消息、提示词或密钥。取值为 {@code null} 的字段由
 * {@link JsonInclude.Include#NON_NULL} 直接省略，而不是输出 {@code "failure":null}
 * 这类需要调用方额外解释的空值。</p>
 *
 * @param outcome   结果三态：{@code FOUND} / {@code NOT_FOUND} / {@code FAILED}
 * @param assetId   被查询的资产标识；{@code FOUND} 时来自记录，{@code NOT_FOUND} 时来自远端回显
 * @param assetType 资产类型；仅 {@code FOUND}
 * @param status    资产状态；仅 {@code FOUND}
 * @param source    数据来源（{@code DEMO}/{@code REAL}）；{@code FOUND} 与 {@code NOT_FOUND} 都有
 * @param failure   稳定失败分类；仅 {@code FAILED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetDiagnosisAssetResponse(String outcome,
                                          String assetId,
                                          String assetType,
                                          String status,
                                          String source,
                                          String failure) {

    /**
     * 把应用层的资产查询结果映射成 HTTP 形状。
     *
     * @param result 资产查询结果（非 {@code null}；编排层已把违约挡在结果之外）
     * @return 响应片段
     */
    public static AssetDiagnosisAssetResponse from(AssetQueryResult result) {
        return switch (result.outcome()) {
            case FOUND -> {
                AssetView asset = result.requireAsset();
                yield new AssetDiagnosisAssetResponse(result.outcome().name(), asset.assetId(), asset.assetType(),
                        asset.status(), asset.source().name(), null);
            }
            case NOT_FOUND -> new AssetDiagnosisAssetResponse(result.outcome().name(), result.assetId(), null, null,
                    result.source().name(), null);
            case FAILED -> new AssetDiagnosisAssetResponse(result.outcome().name(), null, null, null, null,
                    result.failure().name());
        };
    }
}
