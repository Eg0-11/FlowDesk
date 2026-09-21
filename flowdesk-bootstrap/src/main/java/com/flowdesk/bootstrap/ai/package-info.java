/**
 * FlowDesk AI HTTP 边界包：REST Controller、请求/响应 DTO 与异常到状态码的映射。
 *
 * <p>本包只依赖 {@code com.flowdesk.application.ai} 的用例接口，不接触 ChatClient、
 * 工具实现或具体编排类。</p>
 *
 * <p><b>资产诊断 HTTP 边界（FD-0017-B）</b>：{@code AssetDiagnosisController} 暴露
 * {@code POST /api/v1/ai/asset-diagnosis}，唯一协作者是 {@code AssetDiagnosisUseCase} ——
 * 它不注入 {@code ChatClient}、两个查询端口或 MCP 客户端，因此 HTTP 层无法决定编排顺序、
 * 无法选择工具，也无法自己发起一次查询。三个响应 DTO
 * （{@code AssetDiagnosisResponse} 与两个按侧拆分的 {@code …AssetResponse} /
 * {@code …MonitoringResponse}）把字段集合<b>按 outcome 决定</b>：{@code FOUND} 输出命中的
 * 白名单字段与来源，{@code NOT_FOUND} 只输出编号与来源，{@code FAILED} 只输出稳定失败分类；
 * 为 {@code null} 的字段直接省略，不把应用层对象交给 Jackson 自由序列化。
 * 诊断的状态语义与其他 AI 接口不同：<b>200 不代表两个依赖都成功</b> ——
 * 部分命中、两侧未命中、甚至两侧都 {@code DISABLED} 都是 200，
 * 数据状态只能从 {@code asset}/{@code monitoring} 的 {@code outcome} 与 {@code failure} 读出。</p>
 */
package com.flowdesk.bootstrap.ai;
