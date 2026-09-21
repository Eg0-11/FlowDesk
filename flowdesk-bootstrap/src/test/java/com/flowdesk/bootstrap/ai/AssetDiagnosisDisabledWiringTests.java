package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.agent.ai.AssetDiagnosisService;
import com.flowdesk.application.ai.AssetDiagnosisUseCase;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * AI 关闭时资产诊断 Agent 不存在（FD-0017-A）。
 *
 * <p>交付默认配置（{@code flowdesk.ai.enabled=false}）下：诊断用例与它的实现类都不得出现在容器里，
 * 因此既没有诊断能力，也没有任何模型调用可能。两个查询端口仍然存在 ——
 * 那是 FD-0016 交付的独立能力，本阶段<b>不</b>改动它。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowdesk_asset_diagnosis_disabled;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class AssetDiagnosisDisabledWiringTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void noDiagnosisBeanExistsWhileAiIsDisabled() {
        assertThat(this.context.getBeanNamesForType(AssetDiagnosisUseCase.class))
                .as("AI 关闭时不得出现诊断用例")
                .isEmpty();
        assertThat(this.context.getBeanNamesForType(AssetDiagnosisService.class)).isEmpty();
    }

    @Test
    void theTwoQueryPortsFromFd0016AreStillWired() {
        assertThat(this.context.getBeansOfType(AssetQueryPort.class)).hasSize(1);
        assertThat(this.context.getBeansOfType(MonitoringSnapshotQueryPort.class)).hasSize(1);
    }
}
