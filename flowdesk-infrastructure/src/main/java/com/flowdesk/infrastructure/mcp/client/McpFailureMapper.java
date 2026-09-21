package com.flowdesk.infrastructure.mcp.client;

import com.flowdesk.application.integration.QueryFailure;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 把 SDK 抛出的异常映射成稳定的应用层失败分类（FD-0016）。
 *
 * <h2>为什么必须走整条 cause 链</h2>
 * <p>官方 SDK 的信封比较厚：同步方法内部是 Reactor 的 {@code block()}，非运行期异常会被包成
 * {@code ReactiveException}；HTTP 层又可能再包一层。因此这里逐层向下找<b>根因</b>，
 * 并带上环路保护（异常链自引用时不会死循环）。</p>
 *
 * <h2>中断优先（FD-0016-R1）</h2>
 * <p>中断检查在<b>分类之前</b>单独扫一遍整条链：只要链上任何位置是
 * {@link InterruptedException}，就恢复线程中断标志并归为 {@link QueryFailure#UNAVAILABLE}。
 * 不能把它并在分类循环里「谁先匹配谁返回」——那样外层是 {@link IOException} 或
 * {@link McpTransportException} 时会提前结束遍历，把里层的中断吞掉。</p>
 *
 * <h2>映射规则</h2>
 * <ul>
 *   <li><b>中断</b>（链上任意位置）→ 恢复中断标志 + {@link QueryFailure#UNAVAILABLE}
 *       （<b>绝不吞掉中断</b>：调用方据此决定要不要放弃整条链路）；</li>
 *   <li>超时（{@link TimeoutException}、{@link HttpTimeoutException}、{@link java.net.SocketTimeoutException}）
 *       → {@link QueryFailure#TIMEOUT}；SDK 的请求超时是 Reactor 的 {@code timeout}，
 *       因此在链上表现为 {@link TimeoutException}；</li>
 *   <li>JSON-RPC 错误（{@link McpError}）→ {@link QueryFailure#REMOTE_TOOL_ERROR}
 *       ——远端明确拒绝了这次调用，这不是「没有数据」；</li>
 *   <li>传输层错误（{@link McpTransportException} 及其会话子类）、{@link IOException}
 *       与其它运行期异常 → {@link QueryFailure#UNAVAILABLE}。</li>
 * </ul>
 *
 * <p>任何情况下都<b>不</b>返回「未找到」：失败与「查过了没有」是两件事。</p>
 */
final class McpFailureMapper {

    private static final int MAX_DEPTH = 16;

    private McpFailureMapper() {
    }

    /**
     * @param thrown 原始异常（可以是 {@code null}）
     * @return 稳定失败分类
     */
    static QueryFailure classify(Throwable thrown) {
        // 1) 中断优先，且必须扫完整条链：外层是 IOException / 传输异常也不能漏掉里层的中断
        if (restoreInterruptIfPresent(thrown)) {
            return QueryFailure.UNAVAILABLE;
        }

        // 2) 其余分类保持既有契约与既有优先级（超时 → JSON-RPC 错误 → 传输/IO）
        QueryFailure failure = QueryFailure.UNAVAILABLE;
        Throwable current = thrown;
        Set<Throwable> seen = newIdentitySet();

        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (isTimeout(current)) {
                failure = QueryFailure.TIMEOUT;
                break;
            }
            if (current instanceof McpError) {
                failure = QueryFailure.REMOTE_TOOL_ERROR;
                break;
            }
            if (current instanceof McpTransportException
                    || current instanceof McpTransportSessionNotFoundException
                    || current instanceof McpTransportSessionClosedException
                    || current instanceof IOException) {
                failure = QueryFailure.UNAVAILABLE;
                break;
            }
            current = current.getCause();
        }

        // 超时优先：即使外层是 ReactiveException/CompletionException/传输异常也按 TIMEOUT 归类
        return failure == QueryFailure.UNAVAILABLE && containsTimeout(thrown) ? QueryFailure.TIMEOUT : failure;
    }

    /**
     * 扫描整条 cause 链，发现中断就恢复当前线程的中断标志。
     *
     * <p>扫描是<b>有界</b>的（最多 {@value #MAX_DEPTH} 层）且带环路保护，
     * 因此异常链自引用或超长都不会拖住调用。分类之外也复用它：关闭路径同样不允许吞掉中断。</p>
     *
     * @param thrown 原始异常（可以是 {@code null}）
     * @return 链上是否出现过 {@link InterruptedException}
     */
    static boolean restoreInterruptIfPresent(Throwable thrown) {
        Throwable current = thrown;
        Set<Throwable> seen = newIdentitySet();

        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsTimeout(Throwable thrown) {
        Throwable current = thrown;
        Set<Throwable> seen = newIdentitySet();

        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (isTimeout(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable thrown) {
        return thrown instanceof TimeoutException
                || thrown instanceof HttpTimeoutException
                || thrown instanceof java.net.SocketTimeoutException;
    }

    private static Set<Throwable> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
