package com.flowdesk.application.knowledge.query;

import java.text.Normalizer;

/**
 * 用户问题的<b>唯一</b>规范化实现（RAG 4/6 与 5/6 共用）。
 *
 * <h2>为什么必须有这么一个共享组件</h2>
 * <p>检索链路会把「查询向量端口收到的字符串」当作真正的用户问题：向量由它生成、日志与排障以它为准。
 * 生成链路（问答）则把同一个问题写进模型提示词。如果两条链路各自做一次规范化，
 * 就会出现「检索用的是 {@code NFC + strip} 之后的值，而模型看到的是原始值」——
 * 两者可能不同（首尾空白、组合字符），而且差异不会报错，只会让「模型回答的问题」
 * 与「检索证据对应的问题」悄悄错位。</p>
 *
 * <p>因此规范化只在这里实现一次：{@code KnowledgeRetrievalService} 与
 * {@code GroundedKnowledgeAnswerService} 都调用 {@link #normalize(String)}，
 * 两处拿到的必然是逐字符相同的字符串。</p>
 *
 * <h2>只做规范化，不做校验</h2>
 * <p>本组件<b>不</b>判断合法性：空值、code point 上限、控制字符、{@code topK} 与 {@code minScore}
 * 的范围仍然<b>只</b>由检索用例（{@code KnowledgeRetrievalService}）决定。把它做成「纯函数 +
 * 无异常」是刻意的：调用方不需要处理异常，也就不可能因为异常分类不同而分叉出第二套规则。</p>
 *
 * <p>顺序与检索链路原有实现完全一致：先 NFC，再 {@code strip()}。两者都是幂等的，
 * 因此对已经规范化的值再次调用不会有任何变化。</p>
 */
public final class KnowledgeQueryNormalizer {

    private KnowledgeQueryNormalizer() {
    }

    /**
     * 把原始问题规范化为检索与生成共用的形式。
     *
     * @param rawQuery 原始问题；{@code null} 视为空串（合法性由检索用例判定）
     * @return NFC 规范化并去除首尾空白之后的问题（可能是空串，但绝不是 {@code null}）
     */
    public static String normalize(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        return Normalizer.normalize(rawQuery, Normalizer.Form.NFC).strip();
    }
}
