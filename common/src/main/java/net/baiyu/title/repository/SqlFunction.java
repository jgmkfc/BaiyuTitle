package net.baiyu.title.repository;

import java.sql.SQLException;

/**
 * 允许抛出 {@link SQLException} 的函数式接口（JDBC 版 {@code Function}）。
 * <p>用于 {@link TransactionRunner} 的事务体与只读连接体。
 *
 * @param <T> 入参类型（通常为 {@link java.sql.Connection}）
 * @param <R> 返回类型
 */
@FunctionalInterface
public interface SqlFunction<T, R> {

    /**
     * 执行函数体。
     *
     * @param input 入参
     * @return 结果
     * @throws SQLException JDBC 操作失败时
     */
    R apply(T input) throws SQLException;
}
