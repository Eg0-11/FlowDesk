package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 语句级拦截数据源（测试基础设施）。
 *
 * <p>提供两个可观测/可控能力，用来把「写入失败」与「零写入」变成客观事实而不是推测：</p>
 * <ul>
 *   <li><b>语句计数</b>：统计自上次复位以来下发了多少条语句 —— 用于断言前置校验
 *       发生在「开启事务与执行 SQL 之前」（计数必须为 0）；</li>
 *   <li><b>定点失败</b>：让第 N 条语句抛出 {@link SQLException}，用来精确地在
 *       「第一片已插入、第二片还没插入」的位置制造失败，从而验证事务整体回滚。</li>
 * </ul>
 *
 * <p>本类只用于测试，不进入生产代码。</p>
 */
final class StatementInterceptingDataSource implements DataSource {

    private final DataSource delegate;

    private final AtomicInteger statements = new AtomicInteger();

    private volatile int failOnStatementNumber;

    StatementInterceptingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    int statementsExecuted() {
        return this.statements.get();
    }

    void resetStatements() {
        this.statements.set(0);
    }

    /**
     * @param statementNumber 从 1 开始的语句序号；0 表示关闭定点失败
     */
    void failOnStatement(int statementNumber) {
        this.failOnStatementNumber = statementNumber;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return intercept(this.delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return intercept(this.delegate.getConnection(username, password));
    }

    private Connection intercept(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (("prepareStatement".equals(name) || "createStatement".equals(name))) {
                        int executed = this.statements.incrementAndGet();
                        if (executed == this.failOnStatementNumber) {
                            throw new SQLException("模拟数据库写入失败（第 " + executed + " 条语句）", "08006");
                        }
                    }
                    return invoke(connection, method, args);
                });
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
}
