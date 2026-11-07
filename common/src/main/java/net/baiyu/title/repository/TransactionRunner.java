package net.baiyu.title.repository;

import net.baiyu.title.repository.exception.RepositoryException;
import net.baiyu.title.service.executor.DbExecutor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 事务执行器。
 * <p>Repository 方法一律 {@code (Connection, ...)} 签名、不开事务、不切线程；
 * 事务由 Service 通过 {@link #inTransaction(SqlFunction)} 持有，且只允许在
 * {@link DbExecutor} 写线程调用（运行时断言线程名，不变量 §十 11）。
 * 读操作走 {@link #withConnection(SqlFunction)}，不开事务、断言在 DbExecutor 读/写线程。
 */
public final class TransactionRunner {

    /** 连接提供者（由 DatabaseHandler::getConnection 适配）。 */
    private final ConnectionProvider connectionProvider;

    /**
     * 构造事务执行器。
     *
     * @param connectionProvider 连接提供者
     */
    public TransactionRunner(ConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "连接提供者不能为空");
    }

    /**
     * 在单连接事务内执行写操作：成功 commit、异常 rollback，
     * finally 恢复 autoCommit 并归还连接。
     * <p>只允许在写线程调用；{@link SQLException} 统一包装为
     * {@link RepositoryException}，运行时异常（如
     * {@link net.baiyu.title.repository.exception.DuplicateActiveException}）原样透传。
     *
     * @param work 事务体（接收已关闭自动提交的连接）
     * @param <T>  返回类型
     * @return 事务体返回值
     * @throws IllegalStateException 当前线程不是写线程时
     * @throws RepositoryException   JDBC 操作失败时
     */
    public <T> T inTransaction(SqlFunction<Connection, T> work) {
        Objects.requireNonNull(work, "事务体不能为空");
        DbExecutor.assertWriterThread();
        try (Connection connection = connectionProvider.get()) {
            return runTransactional(connection, work);
        } catch (SQLException e) {
            throw new RepositoryException("数据库事务执行失败", e);
        }
    }

    /**
     * 事务骨架：关自动提交 → 执行 → commit；任何异常（含 commit 自身失败，
     * 如 SQLite BUSY）先 rollback 再原样上抛；finally 恢复 autoCommit
     * （连接归还由调用方 try-with-resources 负责）。
     * <p>commit 必须与事务体同在受保护区：若 commit 抛出后不回滚，事务仍在
     * 连接上打开，finally 的 setAutoCommit 会按 JDBC 语义隐式提交它，
     * 造成"上报失败但数据已入库"、缓存与 DB 脱节。
     */
    private static <T> T runTransactional(Connection connection, SqlFunction<Connection, T> work)
            throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (Throwable t) {
            rollbackQuietly(connection, t);
            throw t;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreFailure) {
                // 绝不顶替在途异常（如 DuplicateActiveException 的友好分支）；
                // 坏连接随 try-with-resources 归还后由 Hikari 检测驱逐
            }
        }
    }

    /**
     * 回滚事务；回滚自身的异常作为 suppressed 附加到原始异常上，不掩盖根因。
     */
    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /**
     * 借一个连接执行只读操作（不开事务），用后归还。
     * <p>供 {@link DbExecutor} 读/写线程上的无事务查询使用；请勿在其中执行写语句。
     * 运行时断言当前线程属于 DbExecutor（红线"所有 JDBC 只在 DbExecutor 线程"），
     * 防止 GUI/命令代码在 Bukkit 主线程直接触碰 DB 造成 tick 卡顿。
     *
     * @param work 只读操作体
     * @param <T>  返回类型
     * @return 操作体返回值
     * @throws IllegalStateException 当前线程不是 DbExecutor 线程时
     * @throws RepositoryException   JDBC 操作失败时
     */
    public <T> T withConnection(SqlFunction<Connection, T> work) {
        Objects.requireNonNull(work, "操作体不能为空");
        DbExecutor.assertDbThread();
        try (Connection connection = connectionProvider.get()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new RepositoryException("数据库读操作失败", e);
        }
    }
}
