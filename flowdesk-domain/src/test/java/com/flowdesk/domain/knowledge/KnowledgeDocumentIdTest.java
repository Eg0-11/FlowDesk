package com.flowdesk.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link KnowledgeDocumentId} 值对象测试：严格 UUID 解析与相等性。
 */
class KnowledgeDocumentIdTest {

    private static final UUID UUID_VALUE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void createsFromUuid() {
        assertThat(KnowledgeDocumentId.of(UUID_VALUE).value()).isEqualTo(UUID_VALUE);
    }

    @Test
    void rejectsNullUuid() {
        assertThatThrownBy(() -> KnowledgeDocumentId.of(null))
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(KnowledgeErrorCode.INVALID_DOCUMENT_ID);
    }

    @Test
    void parsesCanonicalUuidIgnoringCase() {
        assertThat(KnowledgeDocumentId.parse("11111111-2222-3333-4444-555555555555").value())
                .isEqualTo(UUID_VALUE);
        assertThat(KnowledgeDocumentId.parse("11111111-2222-3333-4444-555555555555".toUpperCase()).value())
                .isEqualTo(UUID_VALUE);
    }

    @Test
    void parsesRoundTripWithToString() {
        KnowledgeDocumentId id = KnowledgeDocumentId.of(UUID.randomUUID());

        assertThat(KnowledgeDocumentId.parse(id.toString())).isEqualTo(id);
    }

    @ParameterizedTest(name = "[{0}] 必须被拒绝")
    @ValueSource(strings = {
            "1-1-1-1-1",
            "111111112222333344445555555555555",
            "11111111-2222-3333-4444-555555555555 ",
            " 11111111-2222-3333-4444-555555555555",
            "11111111_2222_3333_4444_555555555555",
            "11111111-2222-3333-4444-55555555555z",
            "not-a-uuid",
            "",
    })
    void rejectsNonCanonicalText(String raw) {
        assertThatThrownBy(() -> KnowledgeDocumentId.parse(raw))
                .isInstanceOf(KnowledgeDomainException.class)
                .extracting(thrown -> ((KnowledgeDomainException) thrown).errorCode())
                .isEqualTo(KnowledgeErrorCode.INVALID_DOCUMENT_ID);
    }

    @Test
    void rejectsNullText() {
        assertThatThrownBy(() -> KnowledgeDocumentId.parse(null))
                .isInstanceOf(KnowledgeDomainException.class);
    }

    @Test
    void doesNotEchoTheInvalidInput() {
        String sentinel = "sentinel-document-id";

        assertThatThrownBy(() -> KnowledgeDocumentId.parse(sentinel))
                .hasMessageNotContaining(sentinel);
    }

    @Test
    void equalityIsBasedOnTheUuid() {
        assertThat(KnowledgeDocumentId.of(UUID_VALUE)).isEqualTo(KnowledgeDocumentId.of(UUID_VALUE));
        assertThat(KnowledgeDocumentId.of(UUID_VALUE)).hasSameHashCodeAs(KnowledgeDocumentId.of(UUID_VALUE));
        assertThat(KnowledgeDocumentId.of(UUID_VALUE)).isNotEqualTo(KnowledgeDocumentId.of(UUID.randomUUID()));
    }
}
