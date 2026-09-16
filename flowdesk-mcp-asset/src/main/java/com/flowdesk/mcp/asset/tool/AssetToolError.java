package com.flowdesk.mcp.asset.tool;

/**
 * 工具失败的稳定错误码（FD-0014）。
 *
 * <p>这些码是机器可读的稳定契约，调用方不需要解析文案。</p>
 *
 * <h2>「未找到」不是「失败」</h2>
 * <p>{@code ASSET_NOT_FOUND} <b>不</b>在这里 —— 它是查询成功得到的答案，
 * 由工具返回 {@code isError=false} 的内容。放在这里会让「资产不存在」看起来像「工具坏了」。</p>
 *
 * <h2>内容形状</h2>
 * <p>失败时 MCP 结果的内容是<b>固定 JSON</b>：
 * {@code {"error":"<码>","message":"<固定文案>"}}。
 * 文案里刻意不含引号与反斜杠，因此这个字符串本身就是合法 JSON（有单元测试锁定这一点）。</p>
 */
public enum AssetToolError {

    /**
     * 输入不是合法资产标识：缺失、{@code null}、空白、超长或不符合 {@code AST-[0-9]{6}}。
     * <p>不合法是<b>调用方</b>的问题，与数据源状态无关，因此优先于数据源判断。</p>
     */
    INVALID_ASSET_ID("assetId 必须形如 AST-000001（AST- 加 6 位数字）"),

    /**
     * 资产数据源不可用：没有配置真实数据源、连接失败、上游异常，或目录实现抛出任何其它运行时异常。
     * <p>这才是「工具执行失败」，与「未找到」严格区分。</p>
     */
    ASSET_SOURCE_UNAVAILABLE("资产数据源当前不可用");

    private final String message;

    AssetToolError(String message) {
        this.message = message;
    }

    /**
     * @return 固定的安全文案（不含路径、配置、凭据或上游原文）
     */
    public String message() {
        return this.message;
    }

    /**
     * @return 失败内容的固定 JSON（就是 MCP 工具结果里的 text content）
     */
    public String content() {
        return "{\"error\":\"" + name() + "\",\"message\":\"" + this.message + "\"}";
    }
}
