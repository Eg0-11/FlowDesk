package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import java.util.UUID;
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
 * 工单网页第一阶段（FD-0023-B）依赖的接口行为：列表翻页、单条详情、新建与错误处理。
 *
 * <p>这些用例走**真实 HTTP**（随机端口上的真实 Tomcat），只使用**虚构**的工单数据。
 * 其中一条还证明：网页下拉框的取值确实来自后端枚举（页面不另立一套取值，也不改 API 契约）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketWebFlowTests {

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theTicketListPagesForwardAndBackwardAccordingToTheServerFlags() throws Exception {
        createFictionalTicket("虚构：会议室投影仪无法开机", "按遥控器无反应，电源指示灯不亮（演示数据）");
        createFictionalTicket("虚构：邮箱收不到外部邮件", "同事可以发送，但外部域被退回（演示数据）");
        createFictionalTicket("虚构：办公软件启动报错", "提示配置文件损坏（演示数据）");

        JsonNode first = listPage(0, 2);
        assertThat(first.path("hasPrevious").asBoolean())
                .as("第一页不该有上一页")
                .isFalse();
        assertThat(first.path("hasNext").asBoolean())
                .as("第一页应当还有下一页")
                .isTrue();
        assertThat(first.path("page").asInt()).isEqualTo(0);
        assertThat(first.path("items")).as("第一页应当取满 size=2").hasSize(2);

        int totalPages = first.path("totalPages").asInt();
        assertThat(totalPages).as("至少要两页才能验证翻页").isGreaterThan(1);

        JsonNode last = listPage(totalPages - 1, 2);
        assertThat(last.path("page").asInt()).isEqualTo(totalPages - 1);
        assertThat(last.path("hasNext").asBoolean())
                .as("最后一页不能再有下一页")
                .isFalse();
        assertThat(last.path("hasPrevious").asBoolean())
                .as("从最后一页可以返回上一页")
                .isTrue();
        assertThat(last.path("items").size()).isLessThanOrEqualTo(2);
    }

    @Test
    void aSingleTicketCanBeOpenedByItsIdAndAnUnknownIdIsNotFound() throws Exception {
        JsonNode created = createFictionalTicket("虚构：打印队列卡住", "有三份文档停在队列里（演示数据）");
        String id = created.path("id").asText();

        ResponseEntity<String> detail = this.client.getForEntity("/api/v1/tickets/" + id, String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .as("详情返回的就是刚才创建的那条工单")
                .contains(id)
                .contains("虚构：打印队列卡住")
                .contains("\"status\":\"NEW\"")
                .contains("\"category\":\"OTHER\"")
                .contains("\"priority\":\"P3\"");
        assertThat(detail.getBody())
                .as("页面要展示的解决时间与关闭时间必须在响应里存在（新建的工单为 null）")
                .contains("\"resolvedAt\"")
                .contains("\"closedAt\"");

        ResponseEntity<String> missing =
                this.client.getForEntity("/api/v1/tickets/" + UUID.randomUUID(), String.class);
        assertThat(missing.getStatusCode())
                .as("未知编号必须是 404，页面才能显示「未找到该工单」")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).contains("code");
    }

    @Test
    void createRejectsAnInvalidPayloadWithAContractError() {
        String invalid = "{\"title\":\"   \",\"description\":\"描述\",\"category\":\"OTHER\","
                + "\"priority\":\"P3\",\"requesterId\":\"alice\"}";

        ResponseEntity<String> response = post("/api/v1/tickets", invalid);

        assertThat(response.getStatusCode())
                .as("空标题必须被拒绝：页面据此展示 400 状态")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("code");
    }

    @Test
    void thePageUsesTheBackendEnumValuesForCategoryAndPriority() {
        ResponseEntity<String> script = this.client.getForEntity("/app.js", String.class);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = script.getBody();
        assertThat(body).isNotNull();

        for (TicketCategory category : TicketCategory.values()) {
            assertThat(body)
                    .as("页面里的分类取值必须与后端枚举一致：%s", category)
                    .contains("'" + category.name() + "'");
        }
        for (TicketPriority priority : TicketPriority.values()) {
            assertThat(body)
                    .as("页面里的优先级取值必须与后端枚举一致：%s", priority)
                    .contains("'" + priority.name() + "'");
        }
    }

    @Test
    void theRealEmptyListContractHasZeroTotalPages() throws Exception {
        // 用一个不可能命中的过滤条件取得「真实空列表」响应（与库里有几条数据无关）
        ResponseEntity<String> response = this.client.getForEntity(
                "/api/v1/tickets?page=0&size=10&requesterId=no-such-requester-fd0023br2", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode empty = this.objectMapper.readTree(response.getBody());

        assertThat(empty.path("items")).as("空列表：items 是空数组").isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("totalPages").asInt())
                .as("后端空列表契约：totalPages=0（页面文案与按钮状态据此渲染）")
                .isZero();
        assertThat(empty.path("page").asInt()).isZero();
        assertThat(empty.path("hasNext").asBoolean()).isFalse();
        assertThat(empty.path("hasPrevious").asBoolean()).isFalse();
    }

    /**
     * @param title       标题（虚构）
     * @param description 描述（虚构）
     * @return 创建响应解析后的 JSON
     */
    private JsonNode createFictionalTicket(String title, String description) throws Exception {
        String payload = "{\"title\":\"" + title + "\",\"description\":\"" + description + "\","
                + "\"category\":\"OTHER\",\"priority\":\"P3\",\"requesterId\":\"web-demo\"}";

        ResponseEntity<String> response = post("/api/v1/tickets", payload);
        assertThat(response.getStatusCode()).as("创建虚构工单应当得到 201").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return this.objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> post(String path, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return this.client.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
    }

    /**
     * @param page 页码（0 起）
     * @param size 每页条数
     * @return 列表响应解析后的 JSON
     */
    private JsonNode listPage(int page, int size) throws Exception {
        ResponseEntity<String> response =
                this.client.getForEntity("/api/v1/tickets?page=" + page + "&size=" + size, String.class);
        assertThat(response.getStatusCode()).as("列表接口应当可用").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return this.objectMapper.readTree(response.getBody());
    }
}
