/**
 * FlowDesk 基础设施层根包：后续存放数据库、Redis、向量库、DeepSeek 等适配器。
 *
 * <p>本模块依赖 {@code flowdesk-application}，实现应用层定义的输出端口，
 * 把外部系统差异收敛在适配器内部。</p>
 *
 * <p>当前阶段（FD-0001）只建立包结构，尚未引入数据库、Redis、MQ、向量库与模型依赖。</p>
 */
package com.flowdesk.infrastructure;
