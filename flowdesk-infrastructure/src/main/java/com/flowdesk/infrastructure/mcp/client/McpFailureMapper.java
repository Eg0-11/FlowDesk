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
 * <h2>映射规则</h2>
 * <ul>
 *   <li>超时（{@link TimeoutException}、{@link HttpTimeoutException}、{@link java.net.SocketTimeoutException}）
 *       → {@link QueryFailure#TIMEOUT}；SDK 的请求超时是 Reactor 的 {@code timeout}，
 *       因此在链上表现为 {@link TimeoutException}；</li>
 *   <li>被中断的调用 → 恢复线程中断标志并返回 {@link QueryFailure#UNAVAILABLE}
 *       （<b>绝不吞掉中断</b>：调用方据此决定要不要放弃整条链路）；</li>
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
        QueryFailure failure = QueryFailure.UNAVAILABLE;
        Throwable current = thrown;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof InterruptedException) {
                // 保持中断语义：把标志放回去，让上层能感知这次调用是被打断的
                Thread.currentThread().interrupt();
                return QueryFailure.UNAVAILABLE;
            }
            if (current instanceof TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
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

        // 超时优先：即使外层是 ReactiveException/CompletionException 也按 TIMEOUT 归类
        return failure == QueryFailure.UNAVAILABLE && containsTimeout(thrown) ? QueryFailure.TIMEOUT : failure;
    }

    private static boolean containsTimeout(Throwable thrown) {
        Throwable current = thrown;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof TimeoutException || current instanceof HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
