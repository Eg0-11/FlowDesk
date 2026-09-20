package com.flowdesk.mcp.monitoring.snapshot;

import java.util.Optional;

/**
 * 监控快照查询端口（FD-0015）。
 *
 * <p>纯 Java 接口：没有 Spring、没有 MCP、没有 JSON。它只回答一个问题 ——
 * 「这个资产的监控快照是多少？」—— 因此换数据源不需要动协议与工具代码。</p>
 *
 * <p><b>只读</b>：端口上只有查询方法，没有任何写方法。这不是「暂时没写」，
 * 而是本阶段的边界：写监控数据需要采集器、鉴权与审计，它们不在这个服务的职责里。
 * 工具层也只认识这个端口，因此协议层不可能凭空多出写能力。</p>
 */
public interface MonitoringSnapshotSource {

    /**
     * 按资产标识查询监控快照。
     *
     * <p>返回 {@link Optional#empty()} 表示「<b>查过了</b>，这个资产没有快照」（例如
     * 演示数据里 {@code AST-900003} 就没有）；数据源不可用时必须抛
     * {@link SnapshotSourceUnavailableException}，而不是返回空 —— 两者含义完全不同。</p>
     *
     * @param assetId 已经过格式校验的资产标识
     * @return 快照；没有时为空
     */
    Optional<MonitoringSnapshot> findSnapshotById(String assetId);

    /**
     * 数据源自身的来源标识（只读）。
     *
     * <p>「未找到」也要带来源：演示数据说「没有」与真实监控系统说「没有」是两件不同的事。
     * 数据源不可用时该方法不产生任何来源声明（它会抛异常）。</p>
     *
     * <p><b>不得返回 {@code null}</b>：未命中却没有来源，等于给出「查过了没有」却说不清
     * 这话是谁说的。{@code null} 被视为<b>非法数据源响应</b>，工具层会把它收敛为
     * {@code MONITORING_SOURCE_UNAVAILABLE}（{@code isError=true}），
     * 而不是写成 {@code MONITORING_SNAPSHOT_NOT_FOUND + "source":null}。</p>
     *
     * @return 来源标识，<b>必须非 null</b>
     */
    SnapshotOrigin origin();
}
