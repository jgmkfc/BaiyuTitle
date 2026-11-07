package net.baiyu.title.repository;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接提供者（函数式接口）。
 * <p>由连接池适配，典型写法：{@code databaseHandler::getConnection}。
 * 调用方负责关闭取得的连接（归还连接池）。
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * 取得一个数据库连接。
     *
     * @return 池化连接（close 即归还）
     * @throws SQLException 取连接失败时
     */
    Connection get() throws SQLException;
}
