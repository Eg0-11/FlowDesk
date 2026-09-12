/**
 * FlowDesk AI 用例层根包：框架无关的 AI 用例接口、命令对象与结果对象。
 *
 * <p>本包刻意不出现 Spring、Spring AI、Web、Jackson 注解与类型：
 * 上游适配器（HTTP Controller）与下游实现（Agent 编排、模型适配器）都只依赖这里的抽象。</p>
 */
package com.flowdesk.application.ai;
