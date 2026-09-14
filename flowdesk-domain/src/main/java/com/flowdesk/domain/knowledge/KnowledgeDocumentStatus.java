package com.flowdesk.domain.knowledge;

/**
 * 知识文档处理状态。
 *
 * <h2>合法转换</h2>
 * <pre>
 * UPLOADED ──markParsing──▶ PARSING ──markParsed──────▶ PARSED
 * PARSE_FAILED ──markParsing──┘        └──markParseFailed──▶ PARSE_FAILED
 *
 * PARSED ──markIndexing──▶ INDEXING ──markIndexed──────────▶ INDEXED
 * INDEX_FAILED ──markIndexing──┘      └──markIndexFailed────▶ INDEX_FAILED
 * </pre>
 * <p>其余转换一律拒绝：</p>
 * <ul>
 *   <li>{@code PARSING} 不能被再次领取（避免两个解析同时进行）；</li>
 *   <li>{@code PARSED} 不能重复解析（内容已定型）；</li>
 *   <li>非 {@code PARSING} 状态不能完成或失败解析；</li>
 *   <li>{@code INDEXING} 不能被第二个请求重复领取（避免两批向量互相覆盖）；</li>
 *   <li>{@code INDEXED} 不能重复索引（向量已定型，主动重建索引不在本阶段范围内）；</li>
 *   <li>{@code UPLOADED}/{@code PARSING}/{@code PARSE_FAILED} 不能直接索引：
 *       没有切片就没有可嵌入的内容。</li>
 * </ul>
 *
 * <p>{@code PARSE_FAILED → PARSING} 与 {@code INDEX_FAILED → INDEXING} 是有意放开的：
 * 失败通常来自内容问题或上游临时故障，修好之后应当可以用最新版本重试，
 * 而不是必须重新上传（重新上传会得到新的文档标识，已解析/已索引的工作全部作废）。</p>
 */
public enum KnowledgeDocumentStatus {

    /** 已上传：原始内容已存储且元数据已入库，尚未解析。 */
    UPLOADED,

    /** 解析中：已被某个请求通过 CAS 领取，正在进行读取/解析/切片。 */
    PARSING,

    /** 已解析：切片已原子写入，尚未向量化。 */
    PARSED,

    /** 解析失败：可用最新版本重新领取（{@code PARSE_FAILED → PARSING}）。 */
    PARSE_FAILED,

    /** 索引中：已被某个请求通过 CAS 领取，正在生成并写入切片向量。 */
    INDEXING,

    /** 已索引：切片向量已原子写入，可供后续检索阶段使用。 */
    INDEXED,

    /** 索引失败：可用最新版本重新领取（{@code INDEX_FAILED → INDEXING}）。 */
    INDEX_FAILED
}
