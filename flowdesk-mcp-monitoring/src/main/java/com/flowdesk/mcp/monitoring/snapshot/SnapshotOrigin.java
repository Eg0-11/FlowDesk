package com.flowdesk.mcp.monitoring.snapshot;

/**
 * 快照的来源标识（FD-0015）。
 *
 * <p>工具返回的 {@code source} 字段就是它：{@code DEMO} 表示这条记录来自内置的**虚构**演示数据，
 * 不是任何真实监控系统。调用方一眼就能看出这一点，因此本阶段的演示路径永远不会被误当成真实监控。</p>
 */
public enum SnapshotOrigin {

    /** 内置虚构演示数据。 */
    DEMO,

    /** 真实监控系统（本阶段未接入）。 */
    REAL
}
