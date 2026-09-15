package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunk;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import java.util.List;

/**
 * 解析产物的写入与读取端口。
 *
 * <h2>为什么「完成解析」是一个原子端口方法</h2>
 * <p>完成阶段要做四件事：校验文档仍是 {@code PARSING} 且版本匹配、删除旧切片、
 * 批量插入新切片、把文档更新为 {@code PARSED} 并把版本加 1。
 * 这四步必须<b>整体成功或整体回滚</b> —— 否则会出现「文档是 PARSED 但切片只写了一半」
 * 或「切片是新的但文档仍停在 PARSING」这类自相矛盾的状态。
 * 把它定义成<b>一个端口方法</b>，事务边界就属于适配器（基础设施）而不是应用层：
 * 应用层因此继续保持「不认识事务」的纯粹性，而原子性由端口契约强制。</p>
 *
 * <p>解析与切片本身<b>不</b>在这个事务里：它们耗时且与数据库无关，
 * 持有事务只会长时间占用连接（见 ADR 0006）。</p>
 *
 * <h2>读取端口</h2>
 * <p>{@link #countChunks(KnowledgeDocumentId)} 与
 * {@link #findChunks(KnowledgeDocumentId, int, int)} 供索引（向量化）阶段分页读取切片使用，
 * 不提供公开 HTTP 接口：切片内容只在服务端链路内流转。</p>
 */
public interface KnowledgeDocumentChunkStore {

    /**
     * 原子完成解析：替换切片并把文档标记为 {@code PARSED}（单个数据库事务）。
     *
     * @param parsedDocument  已调用 {@code markParsed} 的文档聚合
     * @param expectedVersion <b>领取解析后的版本</b>（即 {@code repository.update} 返回的版本，
     *                        也就是调用前从库里读到的版本 + 1）；实现必须在事务内校验
     *                        「行仍是 {@code PARSING} 且版本等于该值」，否则整体拒绝
     * @param chunks          本次解析产生的全部切片，按 {@code chunkIndex} 升序
     * @return 更新后的文档及其新版本（{@code expectedVersion + 1}）
     * @throws KnowledgeApplicationException 版本不匹配、文档不在 {@code PARSING} 状态，
     *                                       或任一写入失败（此时整体回滚，不留下部分切片）
     */
    VersionedKnowledgeDocument completeParsing(KnowledgeDocument parsedDocument, long expectedVersion,
            List<KnowledgeDocumentChunk> chunks);

    /**
     * 统计某文档的切片数。
     *
     * @param documentId 文档标识
     * @return 切片数量
     */
    long countChunks(KnowledgeDocumentId documentId);

    /**
     * 分页读取切片（供索引/向量化阶段按 {@code batch-size} 分批读取）。
     *
     * @param documentId 文档标识
     * @param offset     起始序号
     * @param limit      最多返回条数
     * @return 按 {@code chunkIndex} 升序的切片
     */
    List<KnowledgeDocumentChunk> findChunks(KnowledgeDocumentId documentId, int offset, int limit);
}
