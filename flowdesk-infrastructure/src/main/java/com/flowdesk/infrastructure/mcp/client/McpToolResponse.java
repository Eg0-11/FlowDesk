package com.flowdesk.infrastructure.mcp.client;

/**
 * 一次工具调用的原始响应（FD-0016，基础设施内部类型）。
 *
 * <p>只承载两件事：远端是否把这次调用标记为失败，以及结果的文本载荷。
 * 解析与校验在 {@link AssetPayloadParser} / {@link SnapshotPayloadParser} 里做，
 * 因此这里不会把未经校验的内容交给上层。</p>
 *
 * @param remoteError 远端 {@code isError} 是否为 {@code true}
 * @param text        响应里唯一的 text content
 */
record McpToolResponse(boolean remoteError, String text) {
}
