package com.flowdesk.domain.knowledge;

/**
 * 知识文档处理状态。
 *
 * <h2>合法转换</h2>
 * <pre>
 * UPLOADED ──markParsing──▶ PARSING ──markParsed──────▶ PARSED
 * PARSE_FAILED ──markParsing──┘        └──markParseFailed──▶ PARSE_FAILED
 * </pre>
 * <p>其余转换一律拒绝：{@code PARSING} 不能被再次领取（避免两个解析同时进行），
 * {@code PARSED} 不能重复解析（内容已定型），非 {@code PARSING} 状态不能完成或失败。</p>
 *
 * <p>{@code PARSE_FAILED → PARSING} 是有意放开的：解析失败通常来自内容问题或临时资源问题，
 * 人工修正后应当可以用最新版本重试，而不是必须重新上传（重新上传会得到新的文档标识）。</p>
 */
public enum KnowledgeDocumentStatus {

    /** 已上传：原始内容已存储且元数据已入库，尚未解析。 */
    UPLOADED,

    /** 解析中：已被某个请求通过 CAS 领取，正在进行读取/解析/切片。 */
    PARSING,

    /** 已解析：切片已原子写入。 */
    PARSED,

    /** 解析失败：可用最新版本重新领取（{@code PARSE_FAILED → PARSING}）。 */
    PARSE_FAILED
}
