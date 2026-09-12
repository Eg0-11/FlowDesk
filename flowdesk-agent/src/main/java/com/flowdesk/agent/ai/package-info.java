/**
 * FlowDesk AI 编排包：application 层 AI 用例的 Spring AI 实现。
 *
 * <p>本包负责用 {@code ChatClient} 编排提示词与本地工具，并维护请求级的工具调用记录。
 * 它只依赖 application 的抽象与一个按名称注入的 {@code ChatClient}，
 * 不直接依赖 infrastructure 模块。</p>
 */
package com.flowdesk.agent.ai;
