/**
 * FlowDesk AI 编排包：application 层 AI 用例的 Spring AI 实现。
 *
 * <p>本包负责用 {@code ChatClient} 编排提示词与本地工具，并维护请求级的工具调用记录。
 * 它只依赖 application 的抽象与一个按名称注入的 {@code ChatClient}，
 * 不直接依赖 infrastructure 模块。</p>
 *
 * <p>包内的四条链路彼此独立：</p>
 * <ul>
 *   <li><b>普通聊天</b>（{@code ChatClientAiService}）：不注册任何工具；</li>
 *   <li><b>工具冒烟</b>（{@code ChatClientAiService}）：单次请求内注册本地只读工具，
 *       验证「模型请求工具 → 真实执行 → 结果回传 → 汇总」闭环；</li>
 *   <li><b>知识库问答</b>（{@code GroundedKnowledgeAnswerService}，RAG 5/6）：
 *       先经 application 的检索用例取得证据，再由 {@code KnowledgeAnswerPromptBuilder}
 *       把问题、允许的引用编号与证据序列化成<b>确定性 JSON</b>（数据只能出现在字符串值里，
 *       无法变成新字段或新证据块），调用模型一次，最后用 {@code GroundedCitationValidator}
 *       校验答案里的引用：按 <b>ASCII 方括号引用协议</b>，只有 {@code [K[1-9][0-9]*]} 是规范引用，
 *       K/k 前缀的非纯字母记号一律按畸形引用处理（{@code [K-1]}、{@code [Kx1]}、{@code [Known1]}、
 *       {@code [ K999]} 等），只有完整的纯 ASCII 字母单词（{@code [Known]}、{@code [Kubernetes]}）
 *       才是普通文本；任何畸形引用或未知编号都使整次作答失败。
 *       <b>没有检索命中就不调用模型</b>，校验失败即失败（不修正、不重试）。
 *       问题取值来自 application 层的 {@code KnowledgeQueryNormalizer}，
 *       与检索送给查询向量端口的是同一个字符串。</li>
 *   <li><b>资产诊断</b>（{@code AssetDiagnosisService}，FD-0017-A/B）：
 *       由编排层<b>确定性</b>调用 FD-0016 的两个只读查询端口各一次（顺序固定资产 → 监控，
 *       第一次失败或未命中也要执行第二次），把可用证据以 A1/M1 固定编号交给模型写诊断，
 *       再用 {@code AssetDiagnosisCitationValidator} 校验引用；两侧的真实
 *       {@code QueryOutcome}/{@code QueryFailure} 原样保留在结果里（失败绝不被改写成「没有数据」）。
 *       <b>没有命中证据就不调用模型</b>，端口违约（抛异常或返回 {@code null}）按
 *       {@code PORT_CONTRACT_VIOLATION} 失败。模型请求里没有工具、没有会话记忆、没有流式输出，
 *       本包也<b>不</b>把远端 MCP 能力注册成模型可见的工具。</li>
 * </ul>
 *
 * <p>问答与诊断两条链路都被刻意限制在「读证据 → 写答案」：不注册工具、不启用会话记忆、
 * 不做流式输出。结构化隔离与系统指令<b>降低</b>提示词注入风险，但<b>不能防止</b>模型违背提示词 ——
 * 最终仍以输出侧的引用后校验为准（它只验证编号来源、只处理 ASCII 方括号协议，
 * 既不证明事实性，也不做 Markdown / HTML 实体 / Unicode 同形字符的归一）。</p>
 */
package com.flowdesk.agent.ai;
