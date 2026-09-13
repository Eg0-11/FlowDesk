package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;

/**
 * 原始文件内容存储输出端口。
 *
 * <p>元数据进数据库，原始文件走这个端口 —— 两者是<b>完全不同</b>的存储介质，
 * 因此也要能分别替换（本阶段提供本地文件系统适配器，将来可以换成 MinIO/S3，
 * 而不用改动应用层与领域层）。见 {@code docs/adr/0005-knowledge-document-upload-storage.md}。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>内容键必须由适配器<b>根据文档标识</b>生成，绝不使用原始文件名，也绝不由调用方指定；</li>
 *   <li>写入必须<b>先落到临时位置</b>，全部校验通过后再原子移动到最终位置，
 *       因此「最终位置出现了一个对象」就意味着它已经完整且经过校验；</li>
 *   <li>不允许覆盖已有对象：目标已存在时必须失败；</li>
 *   <li>任何失败都必须清理临时文件；</li>
 *   <li>实际读取到 0 字节时以 {@link KnowledgeApplicationErrorCode#EMPTY_DOCUMENT_CONTENT}
 *       失败（这是内容层面的完整性判断，不能等到写库之后才发现）；</li>
 *   <li>其它失败以 {@link KnowledgeApplicationErrorCode#CONTENT_STORAGE_FAILURE} 抛出，
 *       不泄漏本地路径与底层异常细节。</li>
 * </ul>
 */
public interface KnowledgeDocumentContentStore {

    /**
     * 保存原始内容。
     *
     * <p>流由适配器负责关闭（它自己打开的）；调用方只提供流。</p>
     *
     * @param documentId 文档标识，用于生成内容键
     * @param source     内容源
     * @return 不透明内容键、实际字节数与摘要
     * @throws KnowledgeApplicationException 内容为空、写入失败或目标已存在
     */
    StoredContent store(KnowledgeDocumentId documentId, ContentSource source);

    /**
     * 删除已存储的内容，用于元数据写入失败后的补偿。
     *
     * <p>内容键基于唯一文档标识生成，因此补偿删除只会命中本次上传的那个对象，
     * 不可能误删其它文档的内容。删除不存在的对象不算失败。</p>
     *
     * @param contentKey 内容键
     * @return 确实删除了对象返回 {@code true}；对象本来就不存在返回 {@code false}
     * @throws KnowledgeApplicationException 无法判断或删除失败
     */
    boolean delete(String contentKey);
}
