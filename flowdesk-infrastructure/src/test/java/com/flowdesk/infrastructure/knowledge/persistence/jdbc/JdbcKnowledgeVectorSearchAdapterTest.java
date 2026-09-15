package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import com.flowdesk.application.knowledge.port.out.KnowledgeVectorMatch;
import com.flowdesk.domain.knowledge.EmbeddingDescriptor;
import com.flowdesk.domain.knowledge.KnowledgeQueryEmbedding;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * pgvector 检索适配器的可执行部分（RAG 4/6，JDBC 替身）。
 *
 * <p>{@code <=>} 与 {@code vector} 只有 PostgreSQL + pgvector 能执行，因此这里不验证
 * 「相似度算得对不对」（那是 Testcontainers 集成测试的职责），而是用<b>手写 JDBC 替身</b>
 * 验证三件在无 Docker 环境也必须成立的事：</p>
 * <ol>
 *   <li><b>SQL 原样下发</b>：适配器下发的就是 {@link KnowledgeRetrievalSql#SELECT_MATCHES}；</li>
 *   <li><b>所有值都是参数绑定</b>：11 个参数按 SQL 占位符顺序绑定，其中查询向量出现三次
 *       （相似度计算、阈值过滤、距离排序），描述符三项各出现两次（文档行与向量行）；</li>
 *   <li><b>数据库异常被映射为安全的内部检索错误</b>：消息与日志里不得出现 SQL、连接串或向量。</li>
 * </ol>
 *
 * <p>行映射与顺序保持用替身 {@link ResultSet} 直接驱动 {@code MATCH_ROW_MAPPER} 验证。</p>
 */
class JdbcKnowledgeVectorSearchAdapterTest {

    private static final EmbeddingDescriptor DESCRIPTOR =
            EmbeddingDescriptor.of("dashscope", "text-embedding-v4");

    private RecordingJdbc jdbc;

    private JdbcKnowledgeVectorSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        this.jdbc = new RecordingJdbc();
        this.adapter = new JdbcKnowledgeVectorSearchAdapter(JdbcClient.create(this.jdbc.dataSource()));
    }

    @Test
    void sendsTheDocumentedSqlWithAllElevenParametersBoundInOrder() {
        this.jdbc.willFailOnQuery(new java.sql.SQLException("syntax error at or near \"<=\""));

        assertThatThrownBy(() -> this.adapter.search(queryEmbedding(), 0.35, 7))
                .isInstanceOf(KnowledgeApplicationException.class)
                .extracting(thrown -> ((KnowledgeApplicationException) thrown).errorCode())
                .isEqualTo(KnowledgeApplicationErrorCode.KNOWLEDGE_RETRIEVAL_FAILURE);

        assertThat(this.jdbc.preparedSql()).isEqualTo(KnowledgeRetrievalSql.SELECT_MATCHES);

        List<Object> parameters = this.jdbc.parameters();
        assertThat(parameters).as("必须绑定 11 个参数").hasSize(KnowledgeRetrievalSql.PARAMETER_COUNT);

        String literal = (String) parameters.get(0);
        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(parameters.get(1)).isEqualTo("dashscope");
        assertThat(parameters.get(2)).isEqualTo("text-embedding-v4");
        assertThat(parameters.get(3)).isEqualTo(1024);
        assertThat(parameters.get(4)).isEqualTo("dashscope");
        assertThat(parameters.get(5)).isEqualTo("text-embedding-v4");
        assertThat(parameters.get(6)).isEqualTo(1024);
        assertThat(parameters.get(7)).as("阈值过滤复用同一个查询向量").isEqualTo(literal);
        assertThat(parameters.get(8)).isEqualTo(0.35);
        assertThat(parameters.get(9)).as("排序复用同一个查询向量").isEqualTo(literal);
        assertThat(parameters.get(10)).isEqualTo(7);
    }

    @Test
    void anEmptyResultIsReturnedAsAnEmptyList() {
        assertThat(this.adapter.search(queryEmbedding(), 0.5, 5)).isEmpty();
        assertThat(this.jdbc.preparedSql()).isEqualTo(KnowledgeRetrievalSql.SELECT_MATCHES);
    }

    @Test
    void theFailureMessageAndCauseLeakNeitherSqlNorQueryVector() {
        this.jdbc.willFailOnQuery(new java.sql.SQLException(
                "ERROR: operator does not exist: vector <=> unknown jdbc:postgresql://db:5432/flowdesk"));

        KnowledgeApplicationException thrown = (KnowledgeApplicationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> this.adapter.search(queryEmbedding(), 0.5, 5));

        assertThat(thrown.getMessage())
                .doesNotContain("SELECT")
                .doesNotContain("knowledge_document")
                .doesNotContain("jdbc:")
                .doesNotContain("[");
        assertThat(thrown.getCause()).as("原始数据库异常只作为 cause 保留在服务端").isNotNull();
    }

    @Test
    void rejectsAnInvalidThresholdOrTopKBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> this.adapter.search(queryEmbedding(), -0.1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.adapter.search(queryEmbedding(), 1.1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.adapter.search(queryEmbedding(), Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.adapter.search(queryEmbedding(), 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.adapter.search(null, 0.5, 5))
                .isInstanceOf(NullPointerException.class);

        assertThat(this.jdbc.preparedSql())
                .as("入参防线必须在访问数据库之前生效")
                .isNull();
    }

    // ---------- 行映射 ----------

    @Test
    void mapsEveryColumnOfARowIncludingUnicodeText() {
        UUID documentId = UUID.fromString("33333333-4444-5555-6666-777777777777");
        Map<String, Object> row = Map.of(
                "document_id", documentId,
                "document_version", 9L,
                "document_title", "VPN 故障处理手册",
                "chunk_index", 2,
                "chunk_sha256", "abcdef0123456789".repeat(4),
                "content", "第一步：检查隧道状态 \uD83D\uDE00",
                "score", 0.873421);

        KnowledgeVectorMatch match = mapRow(row);

        assertThat(match.documentId().value()).isEqualTo(documentId);
        assertThat(match.documentVersion()).isEqualTo(9L);
        assertThat(match.documentTitle()).isEqualTo("VPN 故障处理手册");
        assertThat(match.chunkIndex()).isEqualTo(2);
        assertThat(match.chunkSha256().value()).isEqualTo("abcdef0123456789".repeat(4));
        assertThat(match.content()).isEqualTo("第一步：检查隧道状态 \uD83D\uDE00");
        assertThat(match.score()).isEqualTo(0.873421);
    }

    @Test
    void stripsPaddingFromAFixedWidthDigestColumn() {
        // CHAR(64) 在部分数据库里会补空格：映射时必须 strip，否则领域值对象会拒绝
        Map<String, Object> row = new HashMap<>();
        row.put("document_id", UUID.randomUUID());
        row.put("document_version", 1L);
        row.put("document_title", "标题");
        row.put("chunk_index", 0);
        row.put("chunk_sha256", "abcdef0123456789".repeat(4) + "  ");
        row.put("content", "正文");
        row.put("score", 0.5);

        KnowledgeVectorMatch match = mapRow(row);

        assertThat(match.chunkSha256().value()).isEqualTo("abcdef0123456789".repeat(4));
    }

    @Test
    void theRowMapperKeepsTheOrderItIsGiven() {
        // 顺序由 SQL 决定；映射器只按行号映射，不做任何重排
        List<KnowledgeVectorMatch> mapped = List.of(
                mapRow(row(0.9)),
                mapRow(row(0.5)),
                mapRow(row(0.1)));

        assertThat(mapped).extracting(KnowledgeVectorMatch::score).containsExactly(0.9, 0.5, 0.1);
    }

    // ---------- 辅助 ----------

    /**
     * 用替身结果集驱动行映射（{@code RowMapper} 声明了受检异常，这里统一包装）。
     *
     * @param row 列名 → 值
     * @return 映射结果
     */
    private static KnowledgeVectorMatch mapRow(Map<String, Object> row) {
        try {
            return JdbcKnowledgeVectorSearchAdapter.MATCH_ROW_MAPPER.mapRow(resultSet(row), 0);
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> row(double score) {
        Map<String, Object> row = new HashMap<>();
        row.put("document_id", UUID.randomUUID());
        row.put("document_version", 1L);
        row.put("document_title", "标题");
        row.put("chunk_index", 0);
        row.put("chunk_sha256", "abcdef0123456789".repeat(4));
        row.put("content", "正文");
        row.put("score", score);
        return row;
    }

    private static KnowledgeQueryEmbedding queryEmbedding() {
        float[] vector = new float[EmbeddingDescriptor.REQUIRED_DIMENSIONS];
        Arrays.fill(vector, 0.25f);
        return new KnowledgeQueryEmbedding(DESCRIPTOR, vector);
    }

    /**
     * 用动态代理构造一个只读 {@link ResultSet} 替身：按列名返回给定值。
     *
     * @param row 列名 → 值
     * @return ResultSet 替身
     */
    private static ResultSet resultSet(Map<String, Object> row) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if (!row.containsKey(args[0])) {
                        throw new IllegalArgumentException("未定义的列: " + args[0]);
                    }
                    return row.get(args[0]);
                });
    }

    /**
     * 手写 JDBC 替身：记录下发的 SQL 与绑定参数，可让查询失败或返回空结果集。
     *
     * <p>用它而不是 H2 的原因：H2 在 <b>prepare 阶段</b>就会拒绝 {@code <=>}，
     * 参数根本来不及绑定；而这个替身能同时观测「SQL 原样下发」「参数绑定顺序」
     * 与「数据库异常映射」三件事。</p>
     */
    private static final class RecordingJdbc {

        private final List<Object> parameters =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private volatile String preparedSql;

        private volatile java.sql.SQLException queryFailure;

        void willFailOnQuery(java.sql.SQLException failure) {
            this.queryFailure = failure;
        }

        String preparedSql() {
            return this.preparedSql;
        }

        List<Object> parameters() {
            synchronized (this.parameters) {
                return List.copyOf(this.parameters);
            }
        }

        javax.sql.DataSource dataSource() {
            return (javax.sql.DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { javax.sql.DataSource.class },
                    (proxy, method, args) -> {
                        if (!"getConnection".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        return connection();
                    });
        }

        private Object connection() {
            return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { java.sql.Connection.class },
                    (proxy, method, args) -> {
                        if (!"prepareStatement".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        this.preparedSql = (String) args[0];
                        return preparedStatement();
                    });
        }

        private Object preparedStatement() {
            return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { java.sql.PreparedStatement.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("set") && args != null && args.length >= 2
                                && args[0] instanceof Integer) {
                            this.parameters.add(args[1]);
                            return null;
                        }
                        if ("executeQuery".equals(name)) {
                            if (this.queryFailure != null) {
                                throw this.queryFailure;
                            }
                            return emptyResultSet();
                        }
                        if ("getUpdateCount".equals(name)) {
                            return -1;
                        }
                        if ("close".equals(name) || "isClosed".equals(name)) {
                            return "isClosed".equals(name) ? Boolean.TRUE : null;
                        }
                        if (("getConnection".equals(name))) {
                            return connection();
                        }
                        // 其余方法（getWarnings、setFetchSize 等）返回无害默认值
                        return defaultValue(method.getReturnType());
                    });
        }

        private static ResultSet emptyResultSet() {
            return (ResultSet) Proxy.newProxyInstance(RecordingJdbc.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, args) -> {
                        if ("next".equals(method.getName())) {
                            return Boolean.FALSE;
                        }
                        if ("getWarnings".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == void.class) {
                return null;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == double.class) {
                return 0.0d;
            }
            if (type == float.class) {
                return 0.0f;
            }
            return 0;
        }
    }
}
