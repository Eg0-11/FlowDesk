/**
 * FlowDesk 应用层根包：用例服务与输入输出端口。
 *
 * <p>本模块依赖 {@code flowdesk-domain}，以端口的形式描述外部协作契约，
 * 由上层的 {@code flowdesk-agent}、{@code flowdesk-infrastructure} 提供适配实现。
 * 本包及其子包只使用 JDK 与 {@code com.flowdesk.domain}，不依赖任何运行时框架。</p>
 *
 * <p>当前包含两部分：</p>
 * <ul>
 *   <li>{@code com.flowdesk.application.ai} —— AI 用例接口与命令/结果对象；</li>
 *   <li>{@code com.flowdesk.application.ticket} —— 工单用例、输入输出端口、只读视图与
 *       乐观并发契约。</li>
 * </ul>
 */
package com.flowdesk.application;
