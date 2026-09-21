/**
 * FlowDesk 智能体层根包：AI 编排与后续的 Graph、ReactAgent、RAG 编排、工具注册。
 *
 * <p>本模块依赖 {@code flowdesk-application}，只负责编排与智能体逻辑，
 * 不直接处理持久化与外部系统细节。</p>
 *
 * <p>当前已实现四件事（都在 {@code com.flowdesk.agent.ai}）：AI 用例编排与本地只读工具、
 * 基于检索证据的可审计回答（RAG 5/6）、可选的检索重排（RAG 6/6）、以及资产诊断编排
 * （固定调用两个 MCP 查询端口）。<b>尚未实现</b>的是 Graph、ReactAgent 与面向工单/知识的
 * 工具注册 —— 注意 RAG 全链路（1/6 上传、2/6 解析切片、3/6 向量化、4/6 检索、5/6 问答、
 * 6/6 重排）都已交付：向量化见 {@code flowdesk-infrastructure} 的 {@code knowledge.embedding}，
 * 检索与持久化见 {@code knowledge.persistence.jdbc}，检索接口为
 * {@code POST /api/v1/knowledge/search}，问答接口为 {@code POST /api/v1/ai/knowledge-answer}，
 * 资产诊断接口为 {@code POST /api/v1/ai/asset-diagnosis}。</p>
 */
package com.flowdesk.agent;
