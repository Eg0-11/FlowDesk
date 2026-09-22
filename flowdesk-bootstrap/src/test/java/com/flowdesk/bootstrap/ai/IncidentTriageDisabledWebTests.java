package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.IncidentTriageService;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 默认 profile 下的事件研判接口契约（FD-0018-B）。
 *
 * <p>{@code flowdesk.ai.enabled=false} 时必须同时满足两件事：接口<b>不存在</b>（404），
 * 并且上下文里<b>没有任何</b>可能发起模型调用的组件（研判用例、Graph 实现与控制器都不装配）。
 * 两个 FD-0016 查询端口仍然存在 —— 那是独立能力，与 AI 开关无关。</p>
 *
 * <p>本类与 {@code IncidentTriageDisabledWiringTests} 互补：那一份证明「Bean 不存在」，
 * 这一份证明「HTTP 上真的没有这个端点」，两者一起才能排除「Bean 没了但端点还在」，
 * 或「端点没了但上下文里仍有模型基础设施」这两种半边状态。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_incident_triage_disabled_web;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class IncidentTriageDisabledWebTests {

    private static final String TRIAGE_PATH = "/api/v1/ai/incident-triage";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theTriageEndpointIsNotRegisteredWhenAiIsDisabled() {
        ResponseEntity<String> response = this.restTemplate.postForEntity(TRIAGE_PATH,
                Map.of("assetId", "AST-900001", "question", "服务器出现持续告警，应该如何排查？"), String.class);

        assertThat(response.getStatusCode())
                .as("默认 profile 下不存在研判端点，也就不可能发起模型调用")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .as("404 走的是全局端点缺失契约，而不是研判自己的任何响应形状")
                .contains("urn:flowdesk:problem:endpoint-not-found")
                .doesNotContain("answer")
                .doesNotContain("usedEvidenceIds")
                .doesNotContain("executionPath")
                .doesNotContain("outcome");
    }

    @Test
    void noModelInfrastructureAndNoTriageBeanIsAssembled() {
        assertThat(this.applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class))
                .isFalse();
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(ChatClient.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(IncidentTriageUseCase.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(IncidentTriageService.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(IncidentTriageController.class)).isEmpty();
    }

    @Test
    void theTwoQueryPortsFromFd0016AreUntouchedByTheAiSwitch() {
        assertThat(this.applicationContext.getBeanNamesForType(
                com.flowdesk.application.integration.port.out.AssetQueryPort.class)).hasSize(1);
        assertThat(this.applicationContext.getBeanNamesForType(
                com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort.class)).hasSize(1);
    }
}
