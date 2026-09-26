package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 工单状态变更的真实 HTTP 验收（FD-0023-C）：完整生命周期、ETag 递增、陈旧版本 412，
 * 以及 428 / 400 / 409 三种拒绝形态；另外固定「列表接口不返回 ETag」这一事实，
 * 说明写请求的 {@code If-Match} 只能来自单条详情。
 *
 * <p>只使用虚构数据；不触碰任何付费接口。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketLifecycleWebTests {

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theWholeLifecycleRisesThroughEveryStatusWithRisingEtags() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = read(created).path("id").asText();
        assertThat(read(created).path("status").asText()).isEqualTo("NEW");
        String etag = etagOf(created);
        assertThat(etag).as("新建工单的 ETag 是强校验且等于当前版本").isEqualTo("\"0\"");

        ResponseEntity<String> assigned = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", etag);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(assigned).path("status").asText()).isEqualTo("ASSIGNED");
        assertThat(read(assigned).path("assigneeId").asText()).isEqualTo("alice");
        assertThat(etagOf(assigned)).as("每成功变更一次，版本与 ETag 都 +1").isEqualTo("\"1\"");

        ResponseEntity<String> started = postJson("/api/v1/tickets/" + id + "/start", null, etagOf(assigned));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(started).path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(etagOf(started)).isEqualTo("\"2\"");

        ResponseEntity<String> reassigned = postJson("/api/v1/tickets/" + id + "/reassign",
                "{\"assigneeId\":\"bob\"}", etagOf(started));
        assertThat(reassigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(reassigned).path("assigneeId").asText()).isEqualTo("bob");
        assertThat(read(reassigned).path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(etagOf(reassigned)).isEqualTo("\"3\"");

        ResponseEntity<String> resolved = postJson("/api/v1/tickets/" + id + "/resolve",
                "{\"resolution\":\"按流程重启并观察 30 分钟（虚构）\"}", etagOf(reassigned));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(resolved).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(read(resolved).path("resolution").asText()).contains("虚构");
        assertThat(read(resolved).path("resolvedAt").isNull()).isFalse();
        assertThat(etagOf(resolved)).isEqualTo("\"4\"");

        ResponseEntity<String> closed = postJson("/api/v1/tickets/" + id + "/close", null, etagOf(resolved));
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(closed).path("status").asText()).isEqualTo("CLOSED");
        assertThat(read(closed).path("closedAt").isNull()).isFalse();
        assertThat(etagOf(closed)).isEqualTo("\"5\"");

        // 结束后再取一次详情：状态与 ETag 与最后一次变更一致
        ResponseEntity<String> detail = get("/api/v1/tickets/" + id);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(detail).path("status").asText()).isEqualTo("CLOSED");
        assertThat(etagOf(detail)).isEqualTo("\"5\"");
        assertThat(read(detail).path("version").asLong()).isEqualTo(5L);
    }

    @Test
    void aStaleIfMatchIsRejectedWith412AndChangesNothing() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();
        String staleEtag = etagOf(created);

        ResponseEntity<String> assigned = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", staleEtag);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 用已经过期的 ETag 再发一次写请求 → 412，并且状态/版本不变
        ResponseEntity<String> conflict = postJson("/api/v1/tickets/" + id + "/start", null, staleEtag);
        assertThat(conflict.getStatusCode()).as("陈旧版本必须 412").isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(read(conflict).path("code").asText()).isEqualTo("TICKET_VERSION_CONFLICT");
        assertThat(conflict.getHeaders().getFirst(HttpHeaders.ETAG)).as("失败响应不带成功态 ETag").isNull();

        ResponseEntity<String> detail = get("/api/v1/tickets/" + id);
        assertThat(read(detail).path("status").asText()).as("412 不得改变状态").isEqualTo("ASSIGNED");
        assertThat(etagOf(detail)).as("412 不得改变版本").isEqualTo("\"1\"");
    }

    @Test
    void aMissingIfMatchIsRejectedWith428() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        ResponseEntity<String> missing = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", null);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void aMalformedIfMatchIsRejectedWith400() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        ResponseEntity<String> malformed = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", "not-an-etag");

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(malformed).path("code").asText()).isEqualTo("INVALID_IF_MATCH");
    }

    @Test
    void anIllegalTransitionIsRejectedWith409() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        // NEW 上直接「开始处理」是非法转换
        ResponseEntity<String> illegal = postJson("/api/v1/tickets/" + id + "/start", null, etagOf(created));

        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(illegal).path("code").asText()).isEqualTo("ILLEGAL_STATUS_TRANSITION");
    }

    @Test
    void theListEndpointCarriesNoETagSoTheHeaderMustComeFromTheSingleTicket() throws Exception {
        postJson("/api/v1/tickets", fictionalCreateBody(), null);

        ResponseEntity<String> list = get("/api/v1/tickets?page=0&size=5");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getHeaders().getFirst(HttpHeaders.ETAG))
                .as("列表没有单一版本号，因此不返回 ETag：写请求的 If-Match 只能取自单条详情")
                .isNull();
    }

    /**
     * @return 一条虚构工单的创建请求体
     */
    private static String fictionalCreateBody() {
        return "{\"title\":\"虚构：状态变更验收工单\",\"description\":\"FD-0023-C 的虚构演示数据\","
                + "\"category\":\"OTHER\",\"priority\":\"P3\",\"requesterId\":\"demo\"}";
    }

    private ResponseEntity<String> postJson(String path, String body, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (ifMatch != null) {
            headers.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return this.client.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return this.client.getForEntity(path, String.class);
    }

    private static String etagOf(ResponseEntity<String> response) {
        return response.getHeaders().getFirst(HttpHeaders.ETAG);
    }

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        assertThat(response.getBody()).isNotNull();
        return this.objectMapper.readTree(response.getBody());
    }
}
