package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.IncidentTriageService;
import com.flowdesk.application.ai.IncidentTriageUseCase;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * AI 关闭时事件研判 Graph 与用例都不存在（FD-0018-A）。
 *
 * <p>交付默认配置（{@code flowdesk.ai.enabled=false}）下既没有用例 Bean，也没有图 ——
 * 因此不可能有模型调用。两个查询端口仍然存在（FD-0016 的能力，本阶段不改动）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_incident_triage_disabled;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class IncidentTriageDisabledWiringTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void noTriageBeanOrGraphExistsWhileAiIsDisabled() {
        assertThat(this.context.getBeanNamesForType(IncidentTriageUseCase.class))
                .as("AI 关闭时不得出现事件研判用例")
                .isEmpty();
        assertThat(this.context.getBeanNamesForType(IncidentTriageService.class)).isEmpty();
    }

    @Test
    void theTwoQueryPortsFromFd0016AreStillWired() {
        assertThat(this.context.getBeansOfType(AssetQueryPort.class)).hasSize(1);
        assertThat(this.context.getBeansOfType(MonitoringSnapshotQueryPort.class)).hasSize(1);
    }

    @Test
    void noToolCallbackOrMcpClientBeanWasIntroduced() {
        assertThat(this.context.getBeanNamesForType(org.springframework.ai.tool.ToolCallback.class))
                .as("本阶段不注册任何模型可见的工具")
                .isEmpty();
    }
}
