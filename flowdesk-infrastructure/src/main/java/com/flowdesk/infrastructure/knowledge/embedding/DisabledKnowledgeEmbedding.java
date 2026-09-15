package com.flowdesk.infrastructure.knowledge.embedding;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeDocumentEmbeddingStore;
import com.flowdesk.application.knowledge.port.out.KnowledgeEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeQueryEmbeddingPort;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorSearchPort;
import com.flowdesk.application.knowledge.port.out.VersionedKnowledgeDocument;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentChunkEmbedding;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import java.util.List;

/**
 * 向量化关闭时的占位实现（默认 profile）。
 *
 * <h2>为什么需要它，而不是「没有 Bean」</h2>
 * <p>默认环境要能启动、要能回答问题。如果关闭时干脆不注册端口 Bean，索引用例本身就无法装配，
 * 结果是一个含糊的启动失败；而注册一个「永远拒绝」的实现，可以让 HTTP 契约保持稳定：</p>
 * <ul>
 *   <li>索引请求得到明确的 <b>503 {@code KNOWLEDGE_EMBEDDING_DISABLED}</b>；</li>
 *   <li>用例服务在<b>读仓储之前</b>就会拒绝（开关检查），这里的实现在正常情况下不会被触达；</li>
 *   <li>万一有人绕过用例直接调用端口，得到的仍然是同一个稳定错误，而不是 NPE 或静默成功。</li>
 * </ul>
 */
public final class DisabledKnowledgeEmbedding {

    private DisabledKnowledgeEmbedding() {
    }

    /**
     * 关闭状态下的向量生成端口：任何调用都被拒绝。
     */
    public static final class Port implements KnowledgeEmbeddingPort {

        @Override
        public List<float[]> embedAll(List<String> texts, EmbeddingDescriptor descriptor) {
            throw disabled();
        }
    }

    /**
     * 关闭状态下的向量写入端口：任何调用都被拒绝，绝不写库。
     */
    public static final class Store implements KnowledgeDocumentEmbeddingStore {

        @Override
        public VersionedKnowledgeDocument completeIndexing(KnowledgeDocument indexedDocument,
                long expectedVersion, List<KnowledgeDocumentChunkEmbedding> embeddings) {

            throw disabled();
        }
    }

    /**
     * 关闭状态下的查询向量端口（RAG 4/6）：任何调用都被拒绝，绝不调用模型。
     */
    public static final class QueryPort implements KnowledgeQueryEmbeddingPort {

        @Override
        public float[] embedQuery(String query, EmbeddingDescriptor descriptor) {
            throw disabled();
        }
    }

    /**
     * 关闭状态下的向量检索端口（RAG 4/6）：任何调用都被拒绝，绝不访问向量表。
     *
     * <p>默认环境（H2）没有 pgvector，也不应该有人绕过用例服务直接调用检索端口；
     * 用占位实现替换真实适配器，可以让「未启用向量化」这条边界在装配层就封闭。</p>
     */
    public static final class Search implements KnowledgeVectorSearchPort {

        @Override
        public List<KnowledgeVectorMatch> search(KnowledgeQueryEmbedding queryEmbedding, double minScore,
                int topK) {

            throw disabled();
        }
    }

    private static KnowledgeApplicationException disabled() {
        return new KnowledgeApplicationException(KnowledgeApplicationErrorCode.KNOWLEDGE_EMBEDDING_DISABLED,
                "当前环境未启用文档向量化");
    }
}
