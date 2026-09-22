package com.flowdesk.agent.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件研判 Graph 的状态契约（FD-0018-A）。
 *
 * <p><b>所有 state key、类型与合并策略只在这里声明一次</b>，节点与服务都引用这些常量 ——
 * 散落的魔法字符串会让「谁写了这个键、用什么策略合并」无法审计。</p>
 *
 * <table border="1">
 *   <caption>状态键、类型与合并策略</caption>
 *   <tr><th>常量</th><th>key</th><th>类型</th><th>合并策略</th><th>写入者</th></tr>
 *   <tr><td>{@link #CALL}</td><td>{@code call}</td><td>{@link IncidentTriageCall}</td>
 *       <td>REPLACE</td><td>调用方（输入）创建；各节点在执行中写入自己的产物</td></tr>
 *   <tr><td>{@link #ROUTE}</td><td>{@code route}</td><td>{@code String}</td>
 *       <td>REPLACE</td><td>{@code verify_contracts}</td></tr>
 *   <tr><td>{@link #EXECUTION_PATH}</td><td>{@code executionPath}</td><td>{@code List<String>}</td>
 *       <td><b>APPEND</b></td><td>每个节点各自追加自己的节点名</td></tr>
 * </table>
 *
 * <h2>为什么只有一个「富对象」键</h2>
 * <p>{@link IncidentTriageCall} 的字段形状（由它自己声明并由测试锁定）是：</p>
 * <ul>
 *   <li>{@code requestId}：本次请求标识（调用方创建时给定）；</li>
 *   <li>{@code knowledge}：{@code KnowledgeEvidence}（知识分支三态）；</li>
 *   <li>{@code asset} / {@code monitoring}：两个查询结果（含失败分类）；</li>
 *   <li>{@code answer}：最终答案；{@code usedEvidenceIds}：实际引用的编号；</li>
 *   <li>{@code contractViolation}：是否出现过端口契约违约。</li>
 * </ul>
 *
 * <p>原因见 {@link IncidentTriageCall} 的说明：Graph 框架为每个 {@code NodeOutput} 生成快照时会对
 * 状态做序列化克隆，富对象经过克隆会退化成 Map。因此状态里只保留三个键：一个集中携带本次调用
 * 全部产物的上下文对象，加上两个<b>简单值</b>键（路由与执行路径）—— 后两者能安全地从最终状态读回。</p>
 *
 * <p>{@code executionPath} 是唯一的 APPEND 键：每个节点执行时把自己的名字追加进去，
 * 因此这条路径来自<b>真实执行</b>，不是结束后拼出来的。</p>
 */
final class IncidentTriageStateKeys {

    /** 输入与节点间交换的调用上下文。 */
    static final String CALL = "call";

    /** 闸门路由结果（条件边的输入，也是日志的 graphRoute）。 */
    static final String ROUTE = "route";

    /** 真实执行过的节点名（APPEND）。 */
    static final String EXECUTION_PATH = "executionPath";

    private IncidentTriageStateKeys() {
    }

    /**
     * 状态键与合并策略的集中声明。
     *
     * @return KeyStrategy 工厂（编译期被 Graph 取一次）
     */
    static KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
            strategies.put(CALL, KeyStrategy.REPLACE);
            strategies.put(ROUTE, KeyStrategy.REPLACE);
            strategies.put(EXECUTION_PATH, KeyStrategy.APPEND);
            return strategies;
        };
    }

    /**
     * 供测试与文档引用的状态键清单（顺序与声明一致）。
     *
     * @return 状态键列表
     */
    static List<String> declaredKeys() {
        return List.of(CALL, ROUTE, EXECUTION_PATH);
    }
}
