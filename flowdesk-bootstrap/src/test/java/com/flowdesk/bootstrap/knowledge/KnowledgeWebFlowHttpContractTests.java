package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * FD-0023-D：网页「上传 → 按 ID 查看 → 解析」流程所依赖的 HTTP 契约的真实回放。
 *
 * <p>这一组用例的用意是：把前端 {@code knowledge.js} 实际会发出的那几次请求，
 * 用真实 HTTP 契约逐条重放一遍，证明三件事在<b>服务端</b>是成立的：</p>
 * <ol>
 *   <li>上传返回 <b>201 + Location</b>，且<b>不带 ETag</b>；查询单条文档同样<b>不带 ETag</b>。
 *       因此页面上的版本号只能来自响应体，解析前的重新查询不是可选项。</li>
 *   <li>由「查询到的版本」构造的带双引号 <code>If-Match</code> 可以让解析成功，
 *       且成功响应的 <b>ETag 与响应体 version 始终一致</b>。</li>
 *   <li>用「陈旧版本」构造的 <code>If-Match</code> 会被 <b>412</b> 挡住，
 *       页面因此在写之前就被拦住 —— 这正是前端漂移检查要防护的场景。</li>
 * </ol>
 *
 * <p>本类只使用虚构样例文件（自造文本 / 自造 Markdown），
 * <b>不调用</b>索引接口、检索接口、AI 接口或任何供应商模型，也不清理数据库卷。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_knowledge_web_flow_it"
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "flowdesk.knowledge.storage.root=target/knowledge-web-flow-it",
        "flowdesk.knowledge.upload.max-size=1MB",
        // FD-0023-D-R1：把「提取文本上限」压到很小，这样一份虚构的短文本就能稳定触发
        // 413（提取文本超限 / EXTRACTED_TEXT_TOO_LARGE），而不必构造接近 1MB 的样本。
        // 注意「提取上限必须大于 overlap」这条不变量（见 KnowledgeChunkingProperties），
        // 因此 overlap 必须一起压低。这里**只压 overlap，不改 chunk-size**：
        // 改 chunk-size 会改变切片数量，从而影响本类既有的切片数断言（实测 1 → 3），
        // 那等于为了让新用例通过而改动了别的已验证行为。
        "flowdesk.knowledge.chunking.overlap=0",
        "flowdesk.knowledge.chunking.max-extracted-code-points=30"
})
@AutoConfigureMockMvc
class KnowledgeWebFlowHttpContractTests {

    private static final String BASE_PATH = "/api/v1/knowledge/documents";

    private static final Path STORAGE_ROOT = Path.of("target", "knowledge-web-flow-it");

    /** 虚构样本文档：只用于本机验证，不代表任何真实业务数据。 */
    private static final String FICTIONAL_REPORT =
            "示例运维月报（虚构）：本月共收到 12 个工单，均已关闭。";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearState() throws IOException {
        this.jdbcClient.sql("DELETE FROM knowledge_document_chunks").update();
        this.jdbcClient.sql("DELETE FROM knowledge_documents").update();
        deleteRecursively(STORAGE_ROOT);
    }

    // ---------- ① 页面「上传」这一步的真实契约 ----------

    @Test
    void theUploadStepReturns201WithLocationAndDeliberatelyNoETag() throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "monthly-report.txt", "text/plain",
                                FICTIONAL_REPORT.getBytes(StandardCharsets.UTF_8)))
                        .param("title", "示例运维月报"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.format").value("TEXT"))
                .andExpect(jsonPath("$.mediaType").value("text/plain"))
                .andReturn();

        JsonNode body = body(result);

        // 页面要靠 Location 之外的东西继续操作，所以 id 必须出现在响应体里。
        assertThat(body.path("id").asText())
                .as("上传响应体必须带上文档 ID，页面才能让用户复制它")
                .isNotBlank();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .as("Location 必须指向该文档自身的查询地址")
                .isEqualTo(BASE_PATH + "/" + body.path("id").asText());
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .as("上传响应刻意不带 ETag：页面不能从上传结果里拿版本去写")
                .isNull();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("上传响应不得泄漏存储实现细节")
                .doesNotContain("contentKey")
                .doesNotContain("content_key")
                .doesNotContain("kdoc-");
    }

    // ---------- ② 页面「按 ID 查看」这一步的真实契约 ----------

    @Test
    void theLookupStepReturnsMetadataWithoutETagSoTheVersionOnlyComesFromTheBody() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        MvcResult result = this.mockMvc.perform(get(BASE_PATH + "/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.title").value("示例运维月报"))
                .andExpect(jsonPath("$.originalFilename").value("monthly-report.txt"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .as("单条查询刻意不带 ETag：页面显示的版本只是快照，写之前必须重新查询")
                .isNull();

        JsonNode body = body(result);
        assertThat(body.path("sha256").asText()).isNotBlank();
        assertThat(body.path("sizeBytes").asLong()).isPositive();
        assertThat(body.path("createdAt").asText()).isNotBlank();
        // 还没有解析，因此这些字段应当缺省（@JsonInclude(NON_NULL)）
        assertThat(body.has("parsedAt")).as("未解析的文档不该有 parsedAt").isFalse();
        assertThat(body.has("indexedAt")).as("未索引的文档不该有 indexedAt").isFalse();
        assertThat(body.has("embeddingModel")).as("未索引的文档不该有 embeddingModel").isFalse();
        assertThat(body.toString())
                .as("查询响应不得泄漏存储实现细节")
                .doesNotContain("contentKey")
                .doesNotContain("content_key")
                .doesNotContain("kdoc-");
    }

    @Test
    void lookingUpAnUnknownOrMalformedIdentifierIsRejectedDistinctly() throws Exception {
        // 未知但格式合法的 UUID → 404
        this.mockMvc.perform(get(BASE_PATH + "/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_FOUND"));

        // 非规范 UUID → 400（页面据此提示「不是规范的 36 位 UUID」）
        this.mockMvc.perform(get(BASE_PATH + "/{id}", "1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- ③ 页面「解析」这一步的真实契约 ----------

    @Test
    void parsingWithTheVersionTakenFromTheLookupSucceedsAndTheETagMatchesTheBodyVersion() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        // 页面动作：先 GET 拿到用户看到的版本，再由它构造带双引号的 If-Match。
        JsonNode lookedUp = lookup(documentId);
        String ifMatch = quotedVersionOf(lookedUp);
        assertThat(ifMatch).as("UPLOADED 文档的版本应为 0").isEqualTo("\"0\"");

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, ifMatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.status").value("PARSED"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andReturn();

        JsonNode body = body(result);
        long bodyVersion = body.path("version").asLong();

        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .as("解析成功的 ETag 必须与响应体版本一致")
                .isEqualTo("\"" + bodyVersion + "\"");
        assertThat(bodyVersion)
                .as("上传后的解析把版本推到 2，而不是 1：页面不得假定「只加 1」")
                .isEqualTo(2L);

        // 页面会立刻用权威结果刷新展示；库里的事实必须一致。
        assertThat(statusOf(documentId)).isEqualTo("PARSED");
        assertThat(versionOf(documentId)).isEqualTo(bodyVersion);
        assertThat(chunkCountOf(documentId)).isEqualTo(1L);
    }

    @Test
    void aStaleIfMatchIsRejectedWith412SoThePageCanNeverOverwriteNewerWork() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        // 用户看到的是版本 0，但文档在别处已经被解析过（版本推进到 2）。
        JsonNode lookedUpEarly = lookup(documentId);
        String staleIfMatch = quotedVersionOf(lookedUpEarly);
        assertThat(staleIfMatch).isEqualTo("\"0\"");

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, staleIfMatch))
                .andExpect(status().isOk());

        long versionAfterFirstParse = versionOf(documentId);
        assertThat(versionAfterFirstParse).isEqualTo(2L);

        // 页面此时若还拿着旧版本去写，必须被 412 挡住，且状态/版本不变。
        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, staleIfMatch))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_VERSION_CONFLICT"));

        assertThat(versionOf(documentId))
                .as("被 412 拒绝的请求不得改变版本")
                .isEqualTo(versionAfterFirstParse);
        assertThat(statusOf(documentId)).isEqualTo("PARSED");
    }

    @Test
    void aMissingIfMatchIsRejectedWith428AndChangesNothing() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        assertThat(statusOf(documentId))
                .as("缺少 If-Match 的请求不得推进状态")
                .isEqualTo("UPLOADED");
        assertThat(versionOf(documentId)).isEqualTo(0L);
        assertThat(chunkCountOf(documentId)).isZero();
    }

    @Test
    void aMalformedIfMatchIsRejectedWith400NotWithASilentWrite() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        // 页面必须构造「带双引号的十进制」；这些形状都必须被拒绝，且一个都不许写进去。
        for (String malformed : new String[] {"0", "W/\"0\"", "*", "\"01\"", "\"-1\"", "\"abc\"", "\"\""}) {
            this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                            .header(HttpHeaders.IF_MATCH, malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        }

        assertThat(statusOf(documentId))
                .as("非法 If-Match 一律不得写库")
                .isEqualTo("UPLOADED");
        assertThat(versionOf(documentId)).isEqualTo(0L);
    }

    @Test
    void reparsingAnAlreadyParsedDocumentWithTheCurrentVersionIsA409NotASilentRewrite() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, quotedVersionOf(lookup(documentId))))
                .andExpect(status().isOk());

        long versionAfterParse = versionOf(documentId);

        // 页面对 PARSED 文档会禁用按钮；即便被绕过，服务端也必须以 409 拒绝。
        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"" + versionAfterParse + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_DOCUMENT_NOT_PARSABLE"));

        assertThat(versionOf(documentId))
                .as("409 不得改变版本")
                .isEqualTo(versionAfterParse);
    }

    // ---------- ④ 解析失败后页面展示 failureCode 与可重新解析的事实 ----------

    @Test
    void aCorruptedDocumentLandsInParseFailedWithAFailureCodeAndCanBeReparsedByThePage() throws Exception {
        // 虚构样例：文件头合法（能通过上传校验），但内部结构已损坏 —— 解析阶段才会失败。
        JsonNode uploaded = upload("示例损坏文档", "broken.pdf", "application/pdf",
                KnowledgeParseFixtures.corruptedPdf());
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, quotedVersionOf(lookup(documentId))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_PARSE_FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CORRUPTED_DOCUMENT"));

        assertThat(statusOf(documentId))
                .as("解析失败后文档进入 PARSE_FAILED，页面据此重新启用「解析」按钮")
                .isEqualTo("PARSE_FAILED");
        assertThat(versionOf(documentId))
                .as("失败也会推进版本（占位 +1、补偿 +1），页面必须重新查询而不是沿用旧版本")
                .isEqualTo(2L);
        assertThat(failureCodeOf(documentId)).isEqualTo("CORRUPTED_DOCUMENT");
        assertThat(chunkCountOf(documentId)).isZero();

        // 页面对 PARSE_FAILED 文档允许再次点击解析：重新查询拿到的就是「补偿后」的当前版本。
        JsonNode failedLookup = lookup(documentId);
        assertThat(failedLookup.path("status").asText()).isEqualTo("PARSE_FAILED");
        assertThat(quotedVersionOf(failedLookup))
                .as("失败后重新查询得到的 If-Match 应当是补偿后的版本，而不是用户最初看到的 \"0\"")
                .isEqualTo("\"2\"");

        // 内容仍未修复，因此再次解析依然得到 422（而不是 412/409）——
        // 这证明「用当前版本构造 If-Match」这条路径在契约上被接受，失败原因只是内容。
        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, quotedVersionOf(failedLookup)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failureCode").value("CORRUPTED_DOCUMENT"));
    }

    // ---------- ⑤ 页面「服务端是最终校验者」的凭据 ----------

    /**
     * FD-0023-D-R1：解析返回 413 时，响应体同样是 {@code application/problem+json}，
     * 而不是 {@code ParsedDocumentResponse}。
     *
     * <p>页面必须按错误体渲染（固定 title/detail + {@code failureCode}）。
     * 这里锁定服务端一侧的形状：413 的字段是
     * {@code type/title/status/detail/instance/code/failureCode}，
     * <b>不含</b> {@code documentId/chunkCount/parsedAt} ——
     * 前端若把两者混为一谈，就会把「文档过大」当成文档标题，并显示出空的切片数与解析完成时间。</p>
     *
     * <p>本用例用一份<b>虚构的短文本文档</b>触发「提取文本超过上限」：
     * 本类把 {@code max-extracted-code-points} 压到 30（overlap 同步压到 0），
     * 因此一份几十字符的样本即可稳定触发，且不会撞上 {@code upload.max-size=1MB}
     * 这道<b>上传</b>阶段的限制（两者是不同的闸门）。
     * 不调用索引、检索、AI 或任何供应商模型。</p>
     */
    @Test
    void anOversizedExtractedTextIsAProblemWithAFailureCodeAndNeverAParsedResponse() throws Exception {
        // 虚构样例：一段普通文本，只是超过了本用例压小后的「提取文本」上限（30 code points）。
        String smallButOverTheLine = "示例超长文本（虚构），仅用于触发提取上限：这一段一共有六十个字符左右。";
        JsonNode uploaded = upload("示例超长文档", "over-limit.txt", "text/plain",
                smallButOverTheLine.getBytes(StandardCharsets.UTF_8));
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, quotedVersionOf(lookup(documentId))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"))
                .andExpect(jsonPath("$.failureCode").value("EXTRACTED_TEXT_TOO_LARGE"))
                .andExpect(jsonPath("$.title").value("文档过大"))
                .andExpect(jsonPath("$.detail").value("文档提取出的文本超过允许的最大长度"))
                // 关键：这是错误体，不是解析结果 —— 绝不能带解析结果字段。
                .andExpect(jsonPath("$.documentId").doesNotExist())
                .andExpect(jsonPath("$.chunkCount").doesNotExist())
                .andExpect(jsonPath("$.parsedAt").doesNotExist());

        assertThat(statusOf(documentId))
                .as("超限同样进入 PARSE_FAILED（而不是永久停在 PARSING）")
                .isEqualTo("PARSE_FAILED");
        assertThat(failureCodeOf(documentId)).isEqualTo("EXTRACTED_TEXT_TOO_LARGE");
    }

    @Test
    void theServerRejectsUnsupportedFormatsRegardlessOfWhatThePageClaims() throws Exception {
        // 页面的提示只列出 .pdf/.docx/.md/.txt；但真正的判定在服务端。
        this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "archive.zip", "application/zip",
                                new byte[] {0x50, 0x4B, 0x03, 0x04}))
                        .param("title", "示例不支持格式"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));
    }

    @Test
    void missingTitleOrFileIsRejectedWith400SoThePageCanExplainIt() throws Exception {
        this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                FICTIONAL_REPORT.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(multipart(BASE_PATH)
                        .param("title", "只有标题"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void thePageFlowNeverTouchesTheIndexOrSearchEndpoints() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        // 记录：本类全程只走 documents 的 POST / GET / parse。这里显式断言
        // 索引与检索端点在默认（未启用嵌入）配置下的行为，作为「页面没有调用它们」的旁证。
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"));

        assertThat(statusOf(documentId))
                .as("索引端点被调用时也不得读取或改动文档")
                .isEqualTo("UPLOADED");
        assertThat(versionOf(documentId))
                .as("索引端点被调用时也不得改动版本")
                .isEqualTo(0L);
    }

    // ---------- FD-0023-E：手动索引的真实 HTTP 契约（Basic 模式） ----------

    /**
     * FD-0023-E：Basic 模式（Embedding 未启用）下，页面发出的真实索引请求
     * 会得到 503，且文档不被读取、不修改 —— 这就是「不得对已启用的真实 Embedding
     * 服务发送索引请求」在测试环境的对应事实：本类全程运行在 Embedding 关闭的配置上。
     */
    @Test
    void indexingAParsedDocumentInBasicModeReturns503AndLeavesTheDocumentUntouched() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        // 页面动作：查询 → 解析（版本 0 → 2）→ 勾选费用确认后再索引。
        lookup(documentId);
        this.mockMvc.perform(post(BASE_PATH + "/{id}/parse", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        MvcResult result = this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"))
                .andExpect(jsonPath("$.status").value(503))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("错误响应不得泄漏内部细节")
                .doesNotContain("contentKey")
                .doesNotContain("Exception");

        // 503 是确定结果：文档必须原样（PARSED / v2），页面据此提示「请求没有执行」。
        assertThat(statusOf(documentId)).as("Basic 模式 503 不得改动文档状态").isEqualTo("PARSED");
        assertThat(versionOf(documentId)).as("Basic 模式 503 不得改动版本").isEqualTo(2L);
    }

    /** 缺 If-Match → 428；格式非法 → 400。这两类请求根本到不了向量化服务。 */
    @Test
    void indexPreconditionViolationsAre428And400BeforeAnythingElse() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));

        assertThat(statusOf(documentId)).isEqualTo("UPLOADED");
        assertThat(versionOf(documentId)).isEqualTo(0L);
    }

    /**
     * 不规范 ID → 400（控制器层在进入应用服务前就拒绝，Basic 模式也一样）。
     *
     * <p>Basic 模式下「未启用向量化」的 503 检查先于仓储查询（见
     * {@code KnowledgeDocumentIndexingService.index}：不读仓储、不改状态），
     * 因此未知 UUID 得到 <b>503</b> 而不是 404。404 分支由应用层单测与
     * 浏览器合成响应（FD-0023-E）覆盖。</p>
     */
    @Test
    void indexMalformedIdIs400AndUnknownIdIsShadowedBy503InBasicMode() throws Exception {
        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", java.util.UUID.randomUUID())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"));

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", "1-1-1-1-1")
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /**
     * Basic 模式下 503 的检查先于状态校验，因此 UPLOADED 文档同样得到 503 而不是
     * 409 NOT_INDEXABLE（409 由应用层单测 {@code rejectsDocumentsThatAreNotParsedOrIndexFailed…}
     * 与浏览器合成响应覆盖）。这里同时核实 503 不会读取或修改文档：状态与版本原样。
     */
    @Test
    void indexOnAnUploadedDocumentInBasicModeIs503AndChangesNothing() throws Exception {
        JsonNode uploaded = uploadFictionalReport();
        String documentId = uploaded.path("id").asText();

        this.mockMvc.perform(post(BASE_PATH + "/{id}/index", documentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"))
                .andExpect(jsonPath("$.status").value(503));

        assertThat(statusOf(documentId)).isEqualTo("UPLOADED");
        assertThat(versionOf(documentId)).isEqualTo(0L);
    }

    // ---------- FD-0023-F：知识检索的真实 HTTP 契约（Basic 模式） ----------

    /** 检索端点（KnowledgeSearchController.BASE_PATH + "/search"）——注意不是 documents 前缀。 */
    private static final String SEARCH_PATH = "/api/v1/knowledge/search";

    /**
     * FD-0023-F：Basic 模式（Embedding 未启用）下，页面发出的真实检索请求得到
     * 503 {@code KNOWLEDGE_EMBEDDING_DISABLED} —— 这就是「不得调用真实 DashScope /
     * 重排服务」在测试环境的对应事实：本类全程运行在 Embedding 关闭的配置上。
     * 503 时检索没有执行：没有调用查询向量端口，也没有访问向量检索端口（未产生费用）。
     */
    @Test
    void searchingInBasicModeReturns503WithTheEmbeddingDisabledContract() throws Exception {
        MvcResult result = this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"FlowDesk 的工单如何创建\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_DISABLED"))
                .andExpect(jsonPath("$.type").value("urn:flowdesk:problem:knowledge-embedding-disabled"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("向量化未启用"))
                .andExpect(jsonPath("$.detail").value("当前环境未启用文档向量化"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("错误响应不得泄漏内部细节")
                .doesNotContain("Exception")
                .doesNotContain("contentKey");
    }

    /**
     * 缺 query（含空请求体）→ 400 {@code INVALID_REQUEST} + 固定 detail「检索请求不合法」，
     * 在校验阶段就拒绝：不调用查询向量端口。空 body 与「缺 query」契约完全一致（FD-0011-R1）；
     * 坏 JSON 保留全局的「请求体不是合法 JSON」契约（同样 400）。
     */
    @Test
    void searchingWithoutAQueryIs400BeforeAnythingElse() throws Exception {
        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("检索请求不合法"));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("检索请求不合法"));

        this.mockMvc.perform(post(SEARCH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- 辅助 ----------

    private JsonNode uploadFictionalReport() throws Exception {
        return upload("示例运维月报", "monthly-report.txt", "text/plain",
                FICTIONAL_REPORT.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode upload(String title, String fileName, String contentType, byte[] content) throws Exception {
        MvcResult result = this.mockMvc.perform(multipart(BASE_PATH)
                        .file(new MockMultipartFile("file", fileName, contentType, content))
                        .param("title", title))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** 页面「按 ID 查看」这一步：只读、无 ETag，版本只能从响应体读。 */
    private JsonNode lookup(String documentId) throws Exception {
        MvcResult result = this.mockMvc.perform(get(BASE_PATH + "/{id}", documentId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .as("查询接口不带 ETag，因此 If-Match 只能由响应体版本构造")
                .isNull();
        return body(result);
    }

    /** 页面构造 If-Match 的方式：带双引号的十进制版本号。 */
    private static String quotedVersionOf(JsonNode documentBody) {
        return "\"" + documentBody.path("version").asLong() + "\"";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return this.objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String statusOf(String documentId) {
        return this.jdbcClient.sql("SELECT status FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).single();
    }

    private long versionOf(String documentId) {
        Long version = this.jdbcClient.sql("SELECT version FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(Long.class).single();
        return version == null ? -1L : version;
    }

    private String failureCodeOf(String documentId) {
        return this.jdbcClient.sql("SELECT parse_failure_code FROM knowledge_documents WHERE id = ?")
                .param(1, documentId).query(String.class).optional().orElse(null);
    }

    private long chunkCountOf(String documentId) {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM knowledge_document_chunks WHERE document_id = ?")
                .param(1, documentId).query(Long.class).single();
        return count == null ? -1L : count;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
