package com.flowdesk.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.IssueType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 本地只读工具的行为测试：三种固定策略、未知类型明确失败、真实调用记录。
 */
class SupportPolicyToolsTest {

    private final SupportPolicyTools tools = new SupportPolicyTools();

    @Test
    void returnsFixedPolicyForAccountLock() {
        SupportPolicy policy = tools.lookupSupportPolicy("ACCOUNT_LOCK", context(new ToolInvocationRecorder()));

        assertThat(policy.issueType()).isEqualTo(IssueType.ACCOUNT_LOCK);
        assertThat(policy.priority()).isEqualTo("P2");
        assertThat(policy.handlingGroup()).isEqualTo("账号与权限组");
        assertThat(policy.firstAction()).isNotBlank();
    }

    @Test
    void returnsFixedPolicyForVpnFailure() {
        SupportPolicy policy = tools.lookupSupportPolicy("VPN_FAILURE", context(new ToolInvocationRecorder()));

        assertThat(policy.issueType()).isEqualTo(IssueType.VPN_FAILURE);
        assertThat(policy.priority()).isEqualTo("P1");
        assertThat(policy.handlingGroup()).isEqualTo("网络与接入组");
        assertThat(policy.firstAction()).isNotBlank();
    }

    @Test
    void returnsFixedPolicyForDeviceOffline() {
        SupportPolicy policy = tools.lookupSupportPolicy("DEVICE_OFFLINE", context(new ToolInvocationRecorder()));

        assertThat(policy.issueType()).isEqualTo(IssueType.DEVICE_OFFLINE);
        assertThat(policy.priority()).isEqualTo("P3");
        assertThat(policy.handlingGroup()).isEqualTo("终端运维组");
        assertThat(policy.firstAction()).isNotBlank();
    }

    @Test
    void acceptsLowerCaseAndPaddedIssueType() {
        SupportPolicy policy = tools.lookupSupportPolicy("  vpn_failure  ", context(new ToolInvocationRecorder()));

        assertThat(policy.issueType()).isEqualTo(IssueType.VPN_FAILURE);
    }

    @Test
    void failsExplicitlyForUnknownIssueType() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();

        assertThatThrownBy(() -> tools.lookupSupportPolicy("PRINTER_JAM", context(recorder)))
                .isInstanceOf(AiRequestException.class)
                .hasMessageContaining("issueType 不受支持");

        assertThat(recorder.invocations())
                .containsExactly(new ToolInvocation(SupportPolicyTools.TOOL_NAME, false));
    }

    @Test
    void failsExplicitlyForNullIssueType() {
        assertThatThrownBy(() -> tools.lookupSupportPolicy(null, context(new ToolInvocationRecorder())))
                .isInstanceOf(AiRequestException.class);
    }

    @Test
    void doesNotEchoUnknownInputBackToCaller() {
        assertThatThrownBy(() -> tools.lookupSupportPolicy("SECRET-INTERNAL-TYPE", context(new ToolInvocationRecorder())))
                .isInstanceOf(AiRequestException.class)
                .hasMessageNotContaining("SECRET-INTERNAL-TYPE");
    }

    @Test
    void recordsSuccessfulInvocationIntoRequestScopedRecorder() {
        ToolInvocationRecorder recorder = new ToolInvocationRecorder();

        tools.lookupSupportPolicy("DEVICE_OFFLINE", context(recorder));

        assertThat(recorder.invocations())
                .containsExactly(new ToolInvocation(SupportPolicyTools.TOOL_NAME, true));
        assertThat(recorder.anySucceeded()).isTrue();
    }

    @Test
    void toleratesAbsentToolContext() {
        assertThat(tools.lookupSupportPolicy("ACCOUNT_LOCK", null)).isNotNull();
        assertThat(tools.lookupSupportPolicy("ACCOUNT_LOCK", new ToolContext(Map.of()))).isNotNull();
    }

    @Test
    void isRegisteredUnderTheFixedToolNameWithoutExposingToolContext() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("lookup_support_policy");
        assertThat(callbacks[0].getToolDefinition().description()).isNotBlank();
        assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("issueType");
        assertThat(callbacks[0].getToolDefinition().inputSchema()).doesNotContain("toolContext");
    }

    private ToolContext context(ToolInvocationRecorder recorder) {
        return new ToolContext(Map.of(
                ToolInvocationRecorder.CONTEXT_KEY, recorder,
                ToolInvocationRecorder.REQUEST_ID_KEY, "test-request-id"));
    }
}
