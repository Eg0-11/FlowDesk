/**
 * FlowDesk 领域层根包：领域实体、值对象与领域规则。
 *
 * <p>本模块只依赖 {@code flowdesk-shared}，刻意不依赖 Spring、JPA、Web、AI 及任何基础设施模块，
 * 以保证领域模型与框架、存储、外部服务彻底解耦。</p>
 *
 * <p>当前已实现工单聚合与生命周期状态机，见 {@code com.flowdesk.domain.ticket}；
 * 其余业务领域尚未实现。</p>
 */
package com.flowdesk.domain;
