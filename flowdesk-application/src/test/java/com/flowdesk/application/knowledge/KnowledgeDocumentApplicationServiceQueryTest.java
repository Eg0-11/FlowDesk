package com.flowdesk.application.knowledge;

import static com.flowdesk.application.knowledge.KnowledgeTestSupport.DOCUMENT_ID;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.UPLOADED_AT;
import static com.flowdesk.application.knowledge.KnowledgeTestSupport.assertApplicationError;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.query.GetKnowledgeDocumentQuery;
import com.flowdesk.application.knowledge.service.KnowledgeDocumentApplicationService;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 查询用例测试：命中、未命中、参数校验，以及「查询无副作用」。
 */
class KnowledgeDocumentApplicationServiceQueryTest {

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private final RecordingKnowledgePorts.RecordingDocumentRepository repository =
            new RecordingKnowledgePorts.RecordingDocumentRepository();

    private final RecordingKnowledgePorts.RecordingContentStore contentStore =
            new RecordingKnowledgePorts.RecordingContentStore();

    private final RecordingKnowledgePorts.RecordingIdGenerator idGenerator =
            new RecordingKnowledgePorts.RecordingIdGenerator();

    private final RecordingKnowledgePorts.RecordingTimeProvider timeProvider =
            new RecordingKnowledgePorts.RecordingTimeProvider(UPLOADED_AT);

    private final KnowledgeDocumentApplicationService service = new KnowledgeDocumentApplicationService(
            this.repository, this.contentStore, this.idGenerator, this.timeProvider, 1024L * 1024L);

    @Test
    void returnsTheStoredDocument() {
        this.repository.willFind(document(), 0L);

        KnowledgeDocumentView view = this.service.get(new GetKnowledgeDocumentQuery(DOCUMENT_ID));

        assertThat(view.id()).isEqualTo(DOCUMENT_ID.value());
        assertThat(view.title()).isEqualTo("季度运维报告");
        assertThat(view.originalFilename()).isEqualTo("report.pdf");
        assertThat(view.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(view.sizeBytes()).isEqualTo(1024L);
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(UPLOADED_AT);
    }

    @Test
    void returnsTheStoredVersion() {
        this.repository.willFind(document(), 7L);

        assertThat(this.service.get(new GetKnowledgeDocumentQuery(DOCUMENT_ID)).version()).isEqualTo(7L);
    }

    @Test
    void reportsMissingDocument() {
        assertApplicationError(() -> this.service.get(new GetKnowledgeDocumentQuery(DOCUMENT_ID)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    @Test
    void reportsMissingDocumentEvenWhenAnotherIdIsStored() {
        this.repository.willFind(document(), 0L);
        KnowledgeDocumentId other = KnowledgeDocumentId.of(UUID.randomUUID());

        assertApplicationError(() -> this.service.get(new GetKnowledgeDocumentQuery(other)),
                KnowledgeApplicationErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND);
    }

    @Test
    void rejectsNullQueryOrNullId() {
        assertApplicationError(() -> this.service.get(null),
                KnowledgeApplicationErrorCode.INVALID_QUERY);
        assertApplicationError(() -> this.service.get(new GetKnowledgeDocumentQuery(null)),
                KnowledgeApplicationErrorCode.INVALID_QUERY);
    }

    @Test
    void queryHasNoSideEffects() {
        this.repository.willFind(document(), 0L);

        this.service.get(new GetKnowledgeDocumentQuery(DOCUMENT_ID));
        this.service.get(new GetKnowledgeDocumentQuery(DOCUMENT_ID));
        assertApplicationError(() -> this.service.get(null), KnowledgeApplicationErrorCode.INVALID_QUERY);

        assertThat(this.repository.findCalls()).isEqualTo(2);
        assertThat(this.repository.insertCalls()).as("查询不得写入").isZero();
        assertThat(this.contentStore.storeCalls()).as("查询不得触碰内容存储").isZero();
        assertThat(this.contentStore.deleteCalls()).isZero();
        assertThat(this.idGenerator.calls()).as("查询不得生成标识").isZero();
        assertThat(this.timeProvider.calls()).as("查询不得读取时间").isZero();
    }

    private static KnowledgeDocument document() {
        return KnowledgeDocument.create(DOCUMENT_ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.pdf"), DocumentFormat.PDF, "application/pdf", 1024L,
                Sha256Digest.of(DIGEST), "content-key-1", UPLOADED_AT);
    }
}
