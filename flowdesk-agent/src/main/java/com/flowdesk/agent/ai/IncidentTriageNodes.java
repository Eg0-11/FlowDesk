package com.flowdesk.agent.ai;

/**
 * 事件研判 Graph 的节点名与路由值（FD-0018-A）。
 *
 * <p>节点名与路由值同样是<b>契约</b>：它们会出现在 {@code executionPath}（可审计的执行轨迹）
 * 与日志的 {@code graphRoute} 里，因此集中声明、不做字符串拼接。</p>
 *
 * <pre>
 * START → validate_asset → retrieve_knowledge → query_asset → query_monitoring
 *       → verify_contracts → evidence_gate ─┬─ evidence_available → generate_answer
 *                                          │                       → validate_citations → finish
 *                                          ├─ no_evidence        → fallback_answer → finish
 *                                          └─ contract_violation → finish
 *       finish → END
 * </pre>
 */
final class IncidentTriageNodes {

    /** 校验 assetId（唯一入口，非法时抛 AiRequestException）。 */
    static final String VALIDATE_ASSET = "validate_asset";

    /** 知识检索（输入合法性由检索用例判定）。 */
    static final String RETRIEVE_KNOWLEDGE = "retrieve_knowledge";

    /** 资产查询。 */
    static final String QUERY_ASSET = "query_asset";

    /** 监控快照查询。 */
    static final String QUERY_MONITORING = "query_monitoring";

    /** 契约核验：端口违约、状态形状、并决定闸门路由。 */
    static final String VERIFY_CONTRACTS = "verify_contracts";

    /** 证据闸门（真正的条件边源节点）。 */
    static final String EVIDENCE_GATE = "evidence_gate";

    /** 生成研判（调用模型一次）。 */
    static final String GENERATE_ANSWER = "generate_answer";

    /** 引用校验（失败即整次失败）。 */
    static final String VALIDATE_CITATIONS = "validate_citations";

    /** 无证据时的固定降级回答（不调用模型）。 */
    static final String FALLBACK_ANSWER = "fallback_answer";

    /** 端口契约违约时的终止节点（不调用模型；由服务统一抛稳定失败）。 */
    static final String CONTRACT_VIOLATION = "contract_violation";

    /** 正常终点。 */
    static final String FINISH = "finish";

    /** 路由值：有可用证据。 */
    static final String ROUTE_EVIDENCE_AVAILABLE = "evidence_available";

    /** 路由值：没有任何可用证据。 */
    static final String ROUTE_NO_EVIDENCE = "no_evidence";

    /** 路由值：端口契约违约。 */
    static final String ROUTE_CONTRACT_VIOLATION = "contract_violation";

    private IncidentTriageNodes() {
    }
}
