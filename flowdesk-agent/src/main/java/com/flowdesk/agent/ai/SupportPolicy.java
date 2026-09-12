package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.IssueType;

/**
 * 工单支持策略：本地确定性数据，不来自网络、数据库或 MCP。
 *
 * @param issueType     工单类型
 * @param priority      优先级
 * @param handlingGroup 处理组
 * @param firstAction   首个处理动作
 */
public record SupportPolicy(IssueType issueType,
                            String priority,
                            String handlingGroup,
                            String firstAction) {

    /**
     * 按工单类型查询支持策略。
     *
     * <p>{@code switch} 覆盖 {@link IssueType} 的全部取值且没有 {@code default} 分支，
     * 因此新增枚举值会直接引发编译错误，不可能出现“悄悄返回兜底策略”的情况。</p>
     *
     * @param issueType 工单类型，不可为 {@code null}
     * @return 对应的固定策略
     */
    public static SupportPolicy lookup(IssueType issueType) {
        return switch (issueType) {
            case ACCOUNT_LOCK -> new SupportPolicy(issueType, "P2", "账号与权限组", "核验申请人身份并解除账号锁定");
            case VPN_FAILURE -> new SupportPolicy(issueType, "P1", "网络与接入组", "采集 VPN 客户端日志并切换备用接入点");
            case DEVICE_OFFLINE -> new SupportPolicy(issueType, "P3", "终端运维组", "下发远程唤醒并核对最近一次心跳时间");
        };
    }
}
