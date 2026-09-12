package com.flowdesk.agent.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求级工具调用记录器。
 *
 * <p>设计约束（对应 FD-0002 第 6 节）：</p>
 * <ul>
 *   <li><b>请求隔离</b>：每个 HTTP 请求创建一个独立实例，经 {@code ToolContext} 传给工具；
 *       不存在静态注册表，也不使用 {@code ThreadLocal}，并用一个累加器证明并发写入不丢失。</li>
 *   <li><b>线程安全</b>：内部使用 {@link CopyOnWriteArrayList}，允许多个工具执行线程并发写入。</li>
 *   <li><b>自动释放</b>：实例只被本次请求的局部变量与 {@code ToolContext} 引用，
 *       请求结束后即可被回收，没有无限增长的全局 Map。</li>
 * </ul>
 *
 * <p>记录内容只包含工具名与成功状态，不保存工具参数或工具结果。</p>
 */
public final class ToolInvocationRecorder {

    /** {@code ToolContext} 中存放本记录器的键。 */
    public static final String CONTEXT_KEY = "flowdesk.toolInvocationRecorder";

    /** {@code ToolContext} 中存放服务端 requestId 的键。 */
    public static final String REQUEST_ID_KEY = "flowdesk.requestId";

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();

    private final AtomicInteger totalRecorded = new AtomicInteger();

    /**
     * 记录一次工具调用。
     *
     * @param name    工具名
     * @param success 是否成功
     */
    public void record(String name, boolean success) {
        invocations.add(new ToolInvocation(name, success));
        totalRecorded.incrementAndGet();
    }

    /**
     * @return 已记录调用的不可变快照
     */
    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /**
     * @return 是否至少发生过一次成功的工具调用
     */
    public boolean anySucceeded() {
        return invocations.stream().anyMatch(ToolInvocation::success);
    }

    /**
     * @return 通过 {@link #record} 累计写入的次数，用于并发写入不丢失的校验
     */
    public int totalRecorded() {
        return totalRecorded.get();
    }
}
