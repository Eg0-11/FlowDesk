/**
 * 主服务的 MCP 客户端接入（FD-0016）。
 *
 * <p>本包实现应用层的两个查询端口（资产查询、监控快照查询），通过官方 MCP Java SDK 的
 * Streamable HTTP 客户端访问两个<b>独立进程</b>里的 MCP 服务：</p>
 * <ul>
 *   <li>资产服务：{@code http://127.0.0.1:8091/mcp}，固定工具 {@code asset_get}；</li>
 *   <li>监控服务：{@code http://127.0.0.1:8092/mcp}，固定工具 {@code monitoring_snapshot_get}。</li>
 * </ul>
 *
 * <p>层次与边界：</p>
 * <ul>
 *   <li><b>不</b>依赖 {@code flowdesk-mcp-asset} / {@code flowdesk-mcp-monitoring}：
 *       契约靠测试锁定，而不是靠模块依赖；</li>
 *   <li><b>不</b>内嵌 MCP Server，也<b>不</b>把远端工具注册到 {@code ChatClient}：
 *       这里只有「客户端按次调用一个固定工具」；</li>
 *   <li>每次查询<b>独立建立一个会话</b>（initialize → 一次 tools/call → 释放），
 *       没有连接池、后台重连与应用级重试；理由与代价见 ADR 0013。</li>
 * </ul>
 */
package com.flowdesk.infrastructure.mcp.client;
