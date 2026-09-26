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
 * 工单状态变更的真实 HTTP 验收（FD-0023-C / C-R1 / C-R2）：完整生命周期、ETag 递增、陈旧版本 412，
 * 以及 428 / 400 / 409 三种拒绝形态；另外固定「列表接口不返回 ETag」这一事实，
 * 说明写请求的 {@code If-Match} 只能来自单条详情。
 *
 * <p>FD-0023-C-R2 追加两条：版本漂移后**详情仍可读且刷新后的 ETag 可用**（页面「刷新解锁」的前提），
 * 以及**缺少版本前置条件的写入一定被拒绝**（页面锁定期「一个 POST 都不发」的服务端保障）。</p>
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
     * FD-0023-C-R1 依赖的后端事实：**同一状态下版本也会漂移**。
     *
     * <p>页面在写请求前会拿一次详情做预检，只要预检的强 ETag 与「用户当前看到的详情」不同就取消写入
     * —— 哪怕状态一模一样。这条用例证明这种「状态相同但版本不同」的局面在真实后端是可出现的
     * （reassign 换了处理人、状态仍是 IN_PROGRESS），所以按 ETag 而不是按状态判断目标是否漂移是必要的。</p>
     */
    @Test
    void theVersionCanDriftWhileTheStatusStaysTheSame() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        ResponseEntity<String> assigned = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", etagOf(created));
        ResponseEntity<String> started = postJson("/api/v1/tickets/" + id + "/start", null, etagOf(assigned));
        assertThat(read(started).path("status").asText()).isEqualTo("IN_PROGRESS");

        // 别人把处理人从 alice 换成 bob：状态仍是 IN_PROGRESS，但版本前进了
        ResponseEntity<String> reassigned = postJson("/api/v1/tickets/" + id + "/reassign",
                "{\"assigneeId\":\"bob\"}", etagOf(started));

        assertThat(read(reassigned).path("status").asText())
                .as("状态没变")
                .isEqualTo(read(started).path("status").asText());
        assertThat(etagOf(reassigned))
                .as("版本前进了：只看状态判断不出漂移，必须看强 ETag")
                .isNotEqualTo(etagOf(started));

        // 用漂移前的 ETag 写 → 412，且不改数据（页面据此提示版本冲突）
        ResponseEntity<String> stale = postJson("/api/v1/tickets/" + id + "/resolve",
                "{\"resolution\":\"虚构：过期版本\"}", etagOf(started));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);

        ResponseEntity<String> detail = get("/api/v1/tickets/" + id);
        assertThat(read(detail).path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(read(detail).path("resolution").isNull()).as("412 不得写入解决说明").isTrue();
    }

    /**
     * FD-0023-C-R1 依赖的另一条后端事实：单条详情响应的强 ETag 与响应体版本**始终自洽**
     * （{@code ETag == "\"version\""}）。页面的预检就按这条不变量判断响应是否可信。
     */
    @Test
    void theSingleTicketAlwaysCarriesAStrongEtagThatMatchesItsVersion() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        for (int round = 0; round < 3; round++) {
            ResponseEntity<String> detail = get("/api/v1/tickets/" + id);
            String etag = etagOf(detail);
            assertThat(etag)
                    .as("强 ETag 形如 \"n\"（不是 W/ 弱校验）")
                    .matches("^\"[0-9]+\"$");
            assertThat(etag)
                    .as("ETag 必须等于响应体版本")
                    .isEqualTo("\"" + read(detail).path("version").asLong() + "\"");

            if (round == 0) {
                postJson("/api/v1/tickets/" + id + "/assign", "{\"assigneeId\":\"alice\"}", etag);
            }
            else if (round == 1) {
                postJson("/api/v1/tickets/" + id + "/start", null, etag);
            }
        }
    }

    /**
     * FD-0023-C-R2 依赖的后端事实：**版本漂移后详情仍然可读**。
     *
     * <p>页面在锁定操作区之后，详情区必须还能显示服务端的最新状态（否则用户无法判断发生了什么）。
     * 这条用例证明「状态不变但版本前进」之后，单条详情依然可读，且**新 ETag 与响应体版本仍自洽**
     * —— 这正是页面「刷新解锁」路径成立的前提。</p>
     */
    @Test
    void theDetailStaysReadableAfterTheVersionDrifts() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();
        ResponseEntity<String> assigned = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", etagOf(created));
        ResponseEntity<String> started = postJson("/api/v1/tickets/" + id + "/start", null, etagOf(assigned));

        // 漂移：状态不变（IN_PROGRESS）但版本前进
        postJson("/api/v1/tickets/" + id + "/reassign", "{\"assigneeId\":\"bob\"}", etagOf(started));

        ResponseEntity<String> afterDrift = get("/api/v1/tickets/" + id);
        assertThat(afterDrift.getStatusCode()).as("漂移后详情仍然可读").isEqualTo(HttpStatus.OK);
        String refreshedETag = etagOf(afterDrift);
        assertThat(refreshedETag)
                .as("刷新拿到的仍是规范强 ETag")
                .matches("^\"[0-9]+\"$");
        assertThat(refreshedETag)
                .as("刷新后的 ETag 与响应体版本自洽 —— 这是页面解锁（重建操作区）的唯一依据")
                .isEqualTo("\"" + read(afterDrift).path("version").asLong() + "\"");
        assertThat(refreshedETag)
                .as("刷新后的版本必须比漂移前更新")
                .isNotEqualTo(etagOf(started));

        // 刷新拿到的新 ETag 是可用的：以它写入成功
        ResponseEntity<String> resolved = postJson("/api/v1/tickets/" + id + "/resolve",
                "{\"resolution\":\"虚构：按刷新后的版本解决\"}", refreshedETag);
        assertThat(resolved.getStatusCode()).as("刷新后按新版本可以正常写入").isEqualTo(HttpStatus.OK);
        assertThat(read(resolved).path("status").asText()).isEqualTo("RESOLVED");
    }

    /**
     * FD-0023-C-R2 依赖的另一条后端事实：**缺少 If-Match 的写请求一定被拒绝**。
     *
     * <p>页面在锁定状态下必须「一个 POST 都不发」；这条用例是它背后的服务端保障：
     * 即使真发了没带版本前置条件的写请求，服务端也不会执行（428），数据不变。
     * 同时确认用陈旧 ETag 写入得到 412 —— 两条路径都不会改动工单。</p>
     */
    @Test
    void aWriteWithoutAVersionPreconditionIsAlwaysRejected() throws Exception {
        ResponseEntity<String> created = postJson("/api/v1/tickets", fictionalCreateBody(), null);
        String id = read(created).path("id").asText();

        ResponseEntity<String> withoutIfMatch = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", null);
        assertThat(withoutIfMatch.getStatusCode())
                .as("缺前置条件必须被拒绝")
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<String> after = get("/api/v1/tickets/" + id);
        assertThat(read(after).path("status").asText())
                .as("被拒绝的写入不得改变状态")
                .isEqualTo("NEW");
        assertThat(read(after).path("assigneeId").isNull())
                .as("被拒绝的写入不得写入处理人")
                .isTrue();

        // 陈旧 ETag 同样不得写入
        ResponseEntity<String> stale = postJson("/api/v1/tickets/" + id + "/assign",
                "{\"assigneeId\":\"alice\"}", "\"99\"");
        assertThat(stale.getStatusCode())
                .as("陈旧版本必须得到 412")
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
        ResponseEntity<String> stillNew = get("/api/v1/tickets/" + id);
        assertThat(read(stillNew).path("status").asText()).as("412 之后状态不变").isEqualTo("NEW");
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
