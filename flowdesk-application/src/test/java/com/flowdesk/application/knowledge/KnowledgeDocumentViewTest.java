package com.flowdesk.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.domain.knowledge.DocumentFormat;
import com.flowdesk.domain.knowledge.DocumentTitle;
import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDocumentStatus;
import com.flowdesk.domain.knowledge.OriginalFilename;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeDocumentView} 测试。
 *
 * <p>重点是<b>结构性</b>约束：视图里根本没有内容键与任何路径字段，
 * 因此「响应绝不泄漏 contentKey / 磁盘路径」不依赖响应组装时的纪律。</p>
 */
class KnowledgeDocumentViewTest {

    private static final KnowledgeDocumentId ID =
            KnowledgeDocumentId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test
    void mapsEveryFieldFromTheAggregate() {
        KnowledgeDocument document = document();

        KnowledgeDocumentView view = KnowledgeDocumentView.from(document, 3L);

        assertThat(view.id()).isEqualTo(ID.value());
        assertThat(view.title()).isEqualTo("季度运维报告");
        assertThat(view.originalFilename()).isEqualTo("report.pdf");
        assertThat(view.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(view.mediaType()).isEqualTo("application/pdf");
        assertThat(view.sizeBytes()).isEqualTo(1024L);
        assertThat(view.sha256().value()).isEqualTo(DIGEST);
        assertThat(view.status()).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(view.version()).isEqualTo(3L);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void exposesNoContentKeyComponent() {
        assertThat(componentNames()).doesNotContain("contentKey", "content_key", "key");
    }

    @Test
    void exposesNoPathOrStorageComponent() {
        // 视图里不能出现任何可能承载磁盘路径、临时文件或存储根目录的字段
        assertThat(componentNames()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("path"));
        assertThat(componentNames()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("root"));
        assertThat(componentNames()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("tmp"));
        assertThat(componentNames()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("temp"));
    }

    @Test
    void exposesExactlyTheDocumentedFields() {
        assertThat(componentNames()).containsExactlyInAnyOrder("id", "title", "originalFilename", "format",
                "mediaType", "sizeBytes", "sha256", "status", "version", "createdAt", "updatedAt");
    }

    @Test
    void viewItselfIsImmutable() {
        assertThat(KnowledgeDocumentView.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    private static List<String> componentNames() {
        return Arrays.stream(KnowledgeDocumentView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static KnowledgeDocument document() {
        return KnowledgeDocument.create(ID, DocumentTitle.of("季度运维报告"),
                OriginalFilename.of("report.pdf"), DocumentFormat.PDF, "application/pdf", 1024L,
                Sha256Digest.of(DIGEST), "content-key-1", NOW);
    }
}
