package com.flowdesk.infrastructure.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.AssetView;
import com.flowdesk.application.integration.QueryFailure;
import com.flowdesk.application.integration.SourceOrigin;
import java.util.Set;

/**
 * {@code asset_get} 载荷解析（FD-0016）。
 *
 * <p>只认两种成功形状（与资产 MCP 服务公布的一致）与一种错误形状：</p>
 * <table border="1">
 *   <caption>asset_get 的载荷</caption>
 *   <tr><th>形状</th><th>字段</th><th>结果</th></tr>
 *   <tr><td>命中</td><td>{@code assetId,assetType,status,source}</td><td>{@code FOUND}</td></tr>
 *   <tr><td>未找到</td><td>{@code assetId,found,error,message,source}</td>
 *       <td>{@code NOT_FOUND}（{@code found} 必须为 {@code false}、{@code error} 必须是
 *           {@code ASSET_NOT_FOUND}、编号必须与请求一致、{@code source} 必须存在）</td></tr>
 *   <tr><td>远端失败（{@code isError=true}）</td><td>{@code error,message}</td>
 *       <td>{@code ASSET_SOURCE_UNAVAILABLE} → {@code UNAVAILABLE}；其它错误码 →
 *           {@code REMOTE_TOOL_ERROR}；形状不合法同样按 {@code REMOTE_TOOL_ERROR}
 *           （远端已经声明这次调用失败，就不能再被解读成任何「成功」）</td></tr>
 * </table>
 *
 * <p>其余一切（非法 JSON、非对象、字段集合不符、类型不符、编号错配、{@code source} 缺失或
 * 未知、枚举形状不符、数值越界）一律 {@code INVALID_RESPONSE}。</p>
 *
 * <p><b>关于 {@code source:null}</b>：资产服务在「未命中且数据源给不出来源」时会写
 * {@code "source":null}（该缺陷已在监控服务侧修复，但按任务边界不改动已验收的资产服务）。
 * 客户端不补默认值、不把它当成 {@code NOT_FOUND}，而是按 {@code INVALID_RESPONSE} 拒绝 ——
 * 「查过了没有，但不知道这话是谁说的」不足以支撑一个业务结论。</p>
 */
final class AssetPayloadParser {

    /** 命中形状的字段集合。 */
    static final Set<String> HIT_FIELDS = Set.of("assetId", "assetType", "status", "source");

    /** 未找到形状的字段集合。 */
    static final Set<String> NOT_FOUND_FIELDS = Set.of("assetId", "found", "error", "message", "source");

    /** 远端失败形状的字段集合。 */
    static final Set<String> ERROR_FIELDS = Set.of("error", "message");

    /** 未找到的错误码。 */
    static final String NOT_FOUND_CODE = "ASSET_NOT_FOUND";

    /** 远端声明「资产数据源不可用」的错误码。 */
    static final String SOURCE_UNAVAILABLE_CODE = "ASSET_SOURCE_UNAVAILABLE";

    private AssetPayloadParser() {
    }

    /**
     * @param requestedAssetId 请求里的资产标识（必须与响应回显一致）
     * @param response         原始工具响应
     * @return 三态结果，永不为 {@code null}
     */
    static AssetQueryResult parse(String requestedAssetId, McpToolResponse response) {
        if (response.remoteError()) {
            return remoteFailure(response.text());
        }
        JsonNode payload = PayloadFields.readObject(response.text());
        if (payload == null) {
            return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        if (PayloadFields.hasExactFields(payload, HIT_FIELDS)) {
            return hit(requestedAssetId, payload);
        }
        if (PayloadFields.hasExactFields(payload, NOT_FOUND_FIELDS)) {
            return notFound(requestedAssetId, payload);
        }
        return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
    }

    private static AssetQueryResult hit(String requestedAssetId, JsonNode payload) {
        String assetId = PayloadFields.textOf(payload, "assetId");
        String assetType = PayloadFields.textOf(payload, "assetType");
        String status = PayloadFields.textOf(payload, "status");
        SourceOrigin source = PayloadFields.sourceOf(payload);

        if (assetId == null || assetType == null || status == null || source == null
                || !assetId.equals(requestedAssetId)) {
            return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        try {
            return AssetQueryResult.found(new AssetView(assetId, assetType, status, source));
        }
        catch (IllegalArgumentException ex) {
            // 形状不满足应用层不变量（例如 assetType 不是稳定枚举名）
            return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
    }

    private static AssetQueryResult notFound(String requestedAssetId, JsonNode payload) {
        String assetId = PayloadFields.textOf(payload, "assetId");
        Boolean found = PayloadFields.booleanOf(payload, "found");
        String error = PayloadFields.textOf(payload, "error");
        String message = PayloadFields.textOf(payload, "message");
        SourceOrigin source = PayloadFields.sourceOf(payload);

        if (assetId == null || found == null || error == null || message == null || source == null) {
            return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        if (found.booleanValue() || !NOT_FOUND_CODE.equals(error) || !assetId.equals(requestedAssetId)
                || !AssetIdentifier.isValid(assetId)) {
            return AssetQueryResult.failed(QueryFailure.INVALID_RESPONSE);
        }
        return AssetQueryResult.notFound(assetId, source);
    }

    private static AssetQueryResult remoteFailure(String text) {
        JsonNode payload = PayloadFields.readObject(text);
        if (payload == null || !PayloadFields.hasExactFields(payload, ERROR_FIELDS)) {
            return AssetQueryResult.failed(QueryFailure.REMOTE_TOOL_ERROR);
        }
        String error = PayloadFields.textOf(payload, "error");
        String message = PayloadFields.textOf(payload, "message");
        if (error == null || message == null) {
            return AssetQueryResult.failed(QueryFailure.REMOTE_TOOL_ERROR);
        }
        return AssetQueryResult.failed(
                SOURCE_UNAVAILABLE_CODE.equals(error) ? QueryFailure.UNAVAILABLE : QueryFailure.REMOTE_TOOL_ERROR);
    }
}
