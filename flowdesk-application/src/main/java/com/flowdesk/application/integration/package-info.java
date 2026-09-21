/**
 * 外部只读查询集成端口（FD-0016）。
 *
 * <p>主服务要能查询两个独立服务的数据：资产目录（{@code asset_get}）与监控快照
 * （{@code monitoring_snapshot_get}）。本包<b>只</b>定义这两个查询的输出端口、不可变的结果对象
 * 与稳定的失败分类，不包含任何传输细节：没有 Spring、没有 Spring AI、没有 MCP SDK，
 * 也不引用两个 MCP 服务模块的任何类型。</p>
 *
 * <p>实现放在基础设施层（本阶段是 MCP Streamable HTTP 客户端适配器）。
 * 后续 Agent 只需要依赖这里的端口就能拿到结构化结果，而不必知道数据是怎么取回来的。</p>
 *
 * <h2>三态结果</h2>
 * <p>每个端口的结果都是 {@link com.flowdesk.application.integration.QueryOutcome} 三态：
 * {@code FOUND}（查到了）、{@code NOT_FOUND}（<b>查过了</b>，没有这条数据）、
 * {@code FAILED}（这次查询没有成功，原因见 {@link com.flowdesk.application.integration.QueryFailure}）。
 * 「没有数据」与「查不出来」是两件事，调用方必须能区分 —— 把能力缺失说成业务结论，
 * 或者把「未找到」说成失败，都会让上游做出错误的降级决定。</p>
 */
package com.flowdesk.application.integration;
