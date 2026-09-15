package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 检索 SQL 的结构契约测试（RAG 4/6）。
 *
 * <p>{@code <=>} 与 {@code ?::vector} 只有 PostgreSQL + pgvector 能执行，因此这条 SQL
 * 的「形态」必须在没有 Docker 的环境里也能被锁住：过滤条件、排序键顺序、
 * 参数绑定形态、只读性、以及「保留距离排序以便使用 HNSW 索引」这几条，
 * 一旦被改坏都是<b>静默</b>的错误（结果仍然返回，只是相关性变差或漏检）。</p>
 */
class KnowledgeRetrievalSqlTest {

    private static final String SQL = KnowledgeRetrievalSql.SELECT_MATCHES;

    @Test
    void onlySearchesIndexedDocuments() {
        assertThat(SQL).contains("WHERE d.status = 'INDEXED'");
        assertThat(SQL).doesNotContain("status = 'PARSED'").doesNotContain("status = 'INDEXING'");
    }

    @Test
    void matchesTheDescriptorOnBothTheDocumentAndTheVectorRow() {
        assertThat(SQL).contains("d.embedding_provider = ?")
                .contains("d.embedding_model = ?")
                .contains("d.embedding_dimensions = ?")
                .contains("e.provider = ?")
                .contains("e.model = ?")
                .contains("e.embedding_dimensions = ?");
    }

    @Test
    void joinsTheChunkTableAndRequiresTheDigestToMatch() {
        assertThat(SQL).contains("JOIN knowledge_document_chunks c ON c.document_id = e.document_id")
                .contains("c.chunk_index = e.chunk_index")
                .contains("c.sha256 = e.chunk_sha256");
    }

    @Test
    void computesTheScoreAsOneMinusTheCosineDistance() {
        assertThat(SQL).contains("1 - (e.embedding <=> ?::vector) AS score");
    }

    @Test
    void filtersByTheThresholdInclusively() {
        assertThat(SQL).contains("AND 1 - (e.embedding <=> ?::vector) >= ?");
        assertThat(SQL).as("阈值必须含边界（>=），不能写成 >").doesNotContain(") > ?");
    }

    @Test
    void ordersByDistanceFirstAndThenByStableTieBreakers() {
        String orderBy = SQL.substring(SQL.indexOf("ORDER BY"));

        assertThat(orderBy)
                .startsWith("ORDER BY e.embedding <=> ?::vector ASC")
                .contains("e.document_id ASC")
                .contains("e.chunk_index ASC");
        assertThat(orderBy.indexOf("e.embedding <=> ?::vector ASC"))
                .as("距离必须是第一排序键").isLessThan(orderBy.indexOf("e.document_id ASC"));
    }

    @Test
    void keepsTheDistanceOperatorFormSoTheHnswIndexCanBeUsed() {
        assertThat(SQL).contains("<=>");
        assertThat(SQL)
                .as("不得用 (1 - distance) DESC 代替距离升序：那会让 HNSW 索引失效")
                .doesNotContain("(1 - (e.embedding <=> ?::vector)) DESC")
                .doesNotContain("DESC LIMIT");
    }

    @Test
    void limitsWithABoundParameter() {
        assertThat(SQL).endsWith("LIMIT ?");
        assertThat(SQL).doesNotContain("LIMIT 10").doesNotContain("LIMIT 5");
    }

    @Test
    void bindsEveryValueAndNeverConcatenatesInput() {
        long placeholders = SQL.chars().filter(character -> character == '?').count();

        assertThat(placeholders)
                .as("占位符个数必须与适配器绑定的参数个数一致")
                .isEqualTo(KnowledgeRetrievalSql.PARAMETER_COUNT);
        assertThat(SQL)
                .as("查询向量只以 ?::vector 形式出现（相似度、阈值、排序各一次）")
                .contains("1 - (e.embedding <=> ?::vector) AS score")
                .contains("1 - (e.embedding <=> ?::vector) >= ?")
                .contains("ORDER BY e.embedding <=> ?::vector ASC");
        assertThat(SQL.split("<=> \\?::vector", -1).length - 1)
                .as("距离表达式恰好出现三次").isEqualTo(3);
        assertThat(SQL).doesNotContain("'[", "format(", "concat(");
    }

    @Test
    void staysReadOnly() {
        assertThat(SQL.toUpperCase(java.util.Locale.ROOT))
                .doesNotContain("FOR UPDATE")
                .doesNotContain("INSERT ")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ");
    }

    @Test
    void selectsOnlyTheColumnsTheContractNeeds() {
        assertThat(KnowledgeRetrievalSql.MATCH_COLUMNS)
                .contains("d.id AS document_id", "d.version AS document_version", "d.title AS document_title",
                        "e.chunk_index AS chunk_index", "e.chunk_sha256 AS chunk_sha256", "c.content AS content")
                .as("响应里不返回向量").doesNotContain("embedding AS");
    }
}
