package com.flowdesk.infrastructure.knowledge.persistence.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 语句级拦截数据源（测试基础设施）。
 *
 * <p>把「代码到底下发了什么 SQL、怎么下发的」变成可观测、可控的事实，而不是推测：</p>
 * <ul>
 *   <li><b>语句计数</b>：统计自上次复位以来 prepare 了多少条语句 —— 用于断言前置校验
 *       发生在「开启事务与执行 SQL 之前」（计数必须为 0）；</li>
 *   <li><b>定点失败</b>：让第 N 条语句在 prepare 时抛出 {@link SQLException}；</li>
 *   <li><b>语义失败</b>（FD-0010-R1）：按 SQL 片段让某条语句失败，
 *       不依赖「第几条」这种与实现强耦合的序号；</li>
 *   <li><b>批处理可观测</b>（FD-0010-R1）：分别统计 {@code addBatch()}、
 *       {@code executeBatch()} 与单条 {@code executeUpdate()} 调用，
 *       并记录每条被执行语句的 SQL 与每批的行数 —— 用于证明写入走的是<b>真正的 JDBC 批处理</b>，
 *       而不是 N 次逐条 {@code executeUpdate()}。</li>
 * </ul>
 *
 * <p>本类只用于测试，不进入生产代码。</p>
 */
final class StatementInterceptingDataSource implements DataSource {

    private final DataSource delegate;

    private final AtomicInteger statements = new AtomicInteger();

    private final AtomicInteger batchAdds = new AtomicInteger();

    private final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

    private final List<String> batchExecutionSql = Collections.synchronizedList(new ArrayList<>());

    private final List<String> singleExecutionSql = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger batchFailures = new AtomicInteger();

    private volatile int failOnStatementNumber;

    private volatile int failOnBatchExecutionNumber;

    private volatile String failOnSqlFragment;

    StatementInterceptingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    // ---------- 观测 ----------

    int statementsExecuted() {
        return this.statements.get();
    }

    /** @return {@code addBatch()} 被调用的总次数 */
    int batchAdds() {
        return this.batchAdds.get();
    }

    /** @return {@code executeBatch()} 的批数 */
    int batchExecutions() {
        return this.batchSizes.size();
    }

    /** @return 被注入失败的批次数（这些批次没有成功下发） */
    int batchExecutionsFailed() {
        return this.batchFailures.get();
    }

    /** @return 每一批的行数，按执行顺序 */
    List<Integer> batchSizes() {
        synchronized (this.batchSizes) {
            return List.copyOf(this.batchSizes);
        }
    }

    /** @return 通过 {@code executeBatch()} 执行的语句 SQL，按执行顺序 */
    List<String> batchExecutionSql() {
        synchronized (this.batchExecutionSql) {
            return List.copyOf(this.batchExecutionSql);
        }
    }

    /** @return 通过单条 {@code executeUpdate()}/{@code execute()} 执行的语句 SQL，按执行顺序 */
    List<String> singleExecutionSql() {
        synchronized (this.singleExecutionSql) {
            return List.copyOf(this.singleExecutionSql);
        }
    }

    // ---------- 控制 ----------

    void resetStatements() {
        this.statements.set(0);
        this.batchAdds.set(0);
        this.batchFailures.set(0);
        this.batchSizes.clear();
        this.batchExecutionSql.clear();
        this.singleExecutionSql.clear();
    }

    /**
     * @param statementNumber 从 1 开始的语句序号；0 表示关闭定点失败
     */
    void failOnStatement(int statementNumber) {
        this.failOnStatementNumber = statementNumber;
    }

    /**
     * 让第 N 次 {@code executeBatch()} 失败：此前的批次已经真实下发，因此这是
     * 「前一批已执行、后一批失败」的场景。
     *
     * @param batchNumber 从 1 开始的批次序号；0 表示关闭
     */
    void failOnBatchExecution(int batchNumber) {
        this.failOnBatchExecutionNumber = batchNumber;
    }

    /**
     * 让 SQL 中包含指定片段的语句在 prepare 阶段失败（与序号无关的语义化失败注入）。
     *
     * @param sqlFragment SQL 片段；{@code null} 表示关闭
     */
    void failOnSql(String sqlFragment) {
        this.failOnSqlFragment = sqlFragment;
    }

    // ---------- DataSource ----------

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
                    if ("prepareStatement".equals(name) || "createStatement".equals(name)) {
                        String sql = (args != null && args.length > 0 && args[0] instanceof String text) ? text : "";
                        int prepared = this.statements.incrementAndGet();
                        if (prepared == this.failOnStatementNumber) {
                            throw new SQLException("模拟数据库写入失败（第 " + prepared + " 条语句）", "08006");
                        }
                        String fragment = this.failOnSqlFragment;
                        if (fragment != null && sql.contains(fragment)) {
                            throw new SQLException("模拟数据库写入失败（匹配 " + fragment + "）", "08006");
                        }
                        return interceptStatement(invoke(connection, method, args), sql);
                    }
                    return invoke(connection, method, args);
                });
    }

    /**
     * 包装语句对象：统计批与单条执行，并按配置注入失败。
     *
     * @param statement 真实语句
     * @param sql       该语句的 SQL（{@code createStatement} 时为空串）
     * @return 代理语句
     */
    private Object interceptStatement(Object statement, String sql) {
        Class<?>[] interfaces = statement instanceof CallableStatement ? new Class<?>[] { CallableStatement.class }
                : statement instanceof PreparedStatement ? new Class<?>[] { PreparedStatement.class }
                        : new Class<?>[] { Statement.class };
        AtomicInteger pendingRows = new AtomicInteger();
        return Proxy.newProxyInstance(Statement.class.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "addBatch" -> {
                            // addBatch() 无参（预留语句）与 addBatch(String)（普通语句）都计数
                            this.batchAdds.incrementAndGet();
                            pendingRows.incrementAndGet();
                        }
                        case "clearBatch" -> pendingRows.set(0);
                        case "executeBatch", "executeLargeBatch" -> {
                            int rows = pendingRows.getAndSet(0);
                            int batchNumber = this.batchSizes.size() + this.batchFailures.get() + 1;
                            if (batchNumber == this.failOnBatchExecutionNumber) {
                                this.batchFailures.incrementAndGet();
                                throw new SQLException("模拟写批次失败（第 " + batchNumber + " 批）", "08006");
                            }
                            this.batchSizes.add(rows);
                            this.batchExecutionSql.add(sql);
                        }
                        case "executeUpdate", "executeLargeUpdate", "execute" -> this.singleExecutionSql.add(sql);
                        default -> {
                            // 其余方法直接转发
                        }
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    // ---------- 其余 DataSource 方法 ----------

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
