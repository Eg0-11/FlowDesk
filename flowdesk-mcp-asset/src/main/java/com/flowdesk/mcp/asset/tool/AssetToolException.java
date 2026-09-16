package com.flowdesk.mcp.asset.tool;

/**
 * 工具失败异常（FD-0014）。
 *
 * <h2>为什么消息就是响应内容</h2>
 * <p>MCP Server 一侧（Spring AI 的 {@code McpToolUtils}）把工具方法抛出的
 * {@link Exception#getMessage()} <b>原样</b>放进 {@code CallToolResult} 的文本内容，
 * 并把 {@code isError} 置为 {@code true}。因此这里的消息<b>不是</b>给人看的诊断信息，
 * 而是一段<b>固定、安全、可解析的 JSON 载荷</b>：</p>
 * <pre>
 * {"error":"INVALID_ASSET_ID","message":"assetId 必须形如 AST-000001（AST- 加 6 位数字）"}
 * </pre>
 *
 * <p>这样成功与失败两种结果的内容形状一致（都是 JSON），调用方可以统一解析；
 * 而「异常消息可能泄漏内部细节」这个常见风险在这里被结构性排除：
 * 消息只来自 {@link AssetToolError} 的固定文案，不拼接任何输入、路径、配置或上游原文。</p>
 */
public class AssetToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AssetToolError error;

    /**
     * @param error 稳定失败类别
     */
    public AssetToolException(AssetToolError error) {
        super(error.content());
        this.error = error;
    }

    /**
     * @return 稳定失败类别
     */
    public AssetToolError error() {
        return this.error;
    }
}
