/**
 * FlowDesk 智能体层根包：AI 编排与后续的 Graph、ReactAgent、RAG 编排、工具注册。
 *
 * <p>本模块依赖 {@code flowdesk-application}，只负责编排与智能体逻辑，
 * 不直接处理持久化与外部系统细节。</p>
 *
 * <p>当前已实现 AI 用例编排与本地只读工具（见 {@code com.flowdesk.agent.ai}）；
 * Graph、ReactAgent 与「基于检索结果的答案编排」（RAG 5/6 及之后）尚未实现 ——
 * 注意向量化（RAG 3/6）与检索（RAG 4/6）本身已经交付：
 * 见 {@code flowdesk-infrastructure} 的 {@code knowledge.embedding} 与
 * {@code knowledge.persistence.jdbc}，以及检索接口 {@code POST /api/v1/knowledge/search}。</p>
 */
package com.flowdesk.agent;
