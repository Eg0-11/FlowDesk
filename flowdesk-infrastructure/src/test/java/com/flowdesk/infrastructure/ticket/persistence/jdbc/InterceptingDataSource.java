package com.flowdesk.infrastructure.ticket.persistence.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 拦截型数据源（测试基础设施）：把连接与语句层面的可观测事实记录下来。
 *
 * <p>提供三类能力：</p>
 * <ul>
 *   <li><b>连接身份</b>：每条物理连接分配唯一 id，事件按「连接 id + 线程名」记录，
 *       因此可以断言「外层事务与列表查询用的是不是同一条连接」；</li>
 *   <li><b>连接设置</b>：记录 {@code setReadOnly} 与 {@code setTransactionIsolation} 的实际调用，
 *       因此可以断言事务的只读与隔离级别<b>真的落到了连接上</b>，而不是只断言模板字段；</li>
 *   <li><b>一次性钩子</b>：可以在「下一次分页查询即将下发」时插入动作，
 *       用来把并发写入精确卡在 COUNT 与分页查询之间。</li>
 * </ul>
 *
 * <p>本类只用于测试，不进入生产代码。</p>
 */
final class InterceptingDataSource implements DataSource {

    /**
     * 一条连接事件。
     *
     * @param connectionId 发生事件的连接 id
     * @param threadName   发生事件的线程名
     * @param description  事件描述，例如 {@code setReadOnly(true)}、{@code prepareStatement}
     * @param sql          语句 SQL（非语句事件为空串）
     */
    record Event(int connectionId, String threadName, String description, String sql) {
    }

    private final DataSource delegate;

    private final AtomicInteger connectionSequence = new AtomicInteger();

    private final AtomicInteger connections = new AtomicInteger();

    private final List<Event> events = new CopyOnWriteArrayList<>();

    private volatile Runnable pageQueryHook;

    InterceptingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    // ---------- 钩子 ----------

    /**
     * 安排一个只执行一次的钩子，在下一个分页查询真正下发之前运行。
     */
    void beforeNextPageQuery(Runnable hook) {
        this.pageQueryHook = hook;
    }

    // ---------- 观测 ----------

    int connectionsOpened() {
        return this.connections.get();
    }

    /**
     * 只清连接计数：夹具插入会用到连接，统计「本次操作期间」的连接数前必须先归零。
     */
    void resetConnectionCount() {
        this.connections.set(0);
    }

    void clearTrace() {
        this.events.clear();
    }

    void reset() {
        this.connections.set(0);
        this.events.clear();
        this.pageQueryHook = null;
    }

    List<Event> events() {
        return List.copyOf(this.events);
    }

    /**
     * 兼容早期断言形式：某个线程上的事件描述列表。
     */
    List<String> traceFor(String threadName) {
        List<String> selected = new ArrayList<>();
        for (Event event : this.events) {
            if (event.threadName().equals(threadName)) {
                selected.add(event.description());
            }
        }
        return Collections.unmodifiableList(selected);
    }

    /**
     * 出现过的连接 id，按首次出现顺序。
     */
    List<Integer> distinctConnectionIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Event event : this.events) {
            ids.add(event.connectionId());
        }
        return List.copyOf(ids);
    }

    /**
     * 找出执行了「SQL 中含指定片段」的那条连接。
     *
     * @param sqlFragment SQL 片段，例如 {@code __outer_marker__}
     * @return 连接 id；没有匹配事件时返回 {@code null}
     */
    Integer connectionIdRunning(String sqlFragment) {
        for (Event event : this.events) {
            if (event.sql().contains(sqlFragment)) {
                return event.connectionId();
            }
        }
        return null;
    }

    /**
     * 在连接上设置过指定只读标志的连接 id。
     */
    List<Integer> connectionIdsSettingReadOnly(boolean value) {
        return connectionIdsWithDescription("setReadOnly(" + value + ")");
    }

    /**
     * 在连接上设置过指定隔离级别的连接 id。
     */
    List<Integer> connectionIdsSettingIsolation(int isolationLevel) {
        return connectionIdsWithDescription("setTransactionIsolation(" + isolationLevel + ")");
    }

    /**
     * 某条连接上是否出现过指定描述的事件。
     */
    boolean hasEvent(int connectionId, String description) {
        for (Event event : this.events) {
            if (event.connectionId() == connectionId && event.description().equals(description)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某条连接上是否执行过<b>完全等于</b>给定 SQL 的语句（用于在没有 WHERE 的语句上做精确匹配）。
     */
    boolean hasStatementWithSql(int connectionId, String exactSql) {
        for (Event event : this.events) {
            if (event.connectionId() == connectionId && event.sql().equals(exactSql)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> connectionIdsWithDescription(String description) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Event event : this.events) {
            if (event.description().equals(description)) {
                ids.add(event.connectionId());
            }
        }
        return List.copyOf(ids);
    }

    // ---------- DataSource ----------

    @Override
    public Connection getConnection() throws SQLException {
        this.connections.incrementAndGet();
        return intercept(this.delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        this.connections.incrementAndGet();
        return intercept(this.delegate.getConnection(username, password));
    }

    private Connection intercept(Connection connection) {
        int connectionId = this.connectionSequence.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("setReadOnly".equals(name)) {
                        record(connectionId, "setReadOnly(" + args[0] + ")", "");
                    }
                    else if ("setTransactionIsolation".equals(name)) {
                        record(connectionId, "setTransactionIsolation(" + args[0] + ")", "");
                    }
                    else if (("prepareStatement".equals(name) || "createStatement".equals(name))
                            && args != null && args.length > 0) {
                        String sql = String.valueOf(args[0]);
                        record(connectionId, name, sql);
                        Runnable hook = this.pageQueryHook;
                        if (hook != null && sql.contains(" LIMIT ")) {
                            // 一次性：保证只有被安排的那次分页查询会被拦住
                            this.pageQueryHook = null;
                            hook.run();
                        }
                    }
                    return invoke(connection, method, args);
                });
    }

    private void record(int connectionId, String description, String sql) {
        this.events.add(new Event(connectionId, Thread.currentThread().getName(), description, sql));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return this.delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return this.delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("flowdesk.test");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return this.delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.delegate.isWrapperFor(iface);
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {

        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
