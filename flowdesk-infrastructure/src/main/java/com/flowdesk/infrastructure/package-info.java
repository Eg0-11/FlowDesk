/**
 * FlowDesk 基础设施层根包：模型、数据库、Redis、向量库等外部系统的适配器。
 *
 * <p>本模块依赖 {@code flowdesk-application}，实现应用层定义的输出端口，
 * 把外部系统差异收敛在适配器内部。</p>
 *
 * <p>当前已提供 DeepSeek 的 OpenAI 兼容传输适配（见
 * {@code com.flowdesk.infrastructure.ai}）；数据库、Redis、向量库适配器尚未引入。</p>
 */
package com.flowdesk.infrastructure;
