package net.baiyu.title.repository;

import net.baiyu.title.domain.coin.CoinLogEntry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 称号币仓储（player_coin / coin_log）。
 * <p>余额恒 ≥ 0（条件 UPDATE 禁透支）；每次变动必有一条流水（同事务，不变量 §十 9）。
 */
public interface CoinRepository {

    /**
     * 查询玩家余额。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @return 余额；无记录时为 0
     * @throws SQLException JDBC 失败时
     */
    long getBalance(Connection c, UUID player) throws SQLException;

    /**
     * 原子增减余额（delta &lt; 0 时条件 UPDATE {@code WHERE balance >= -delta} 防透支；
     * 无记录时先补一条余额 0 的种子行——先 UPDATE 后 INSERT 两步式，规避方言 upsert 差异）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @param delta  变动量（正 = 入账，负 = 扣款）
     * @return 变动成功返回 true；余额不足返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean tryAdd(Connection c, UUID player, long delta) throws SQLException;

    /**
     * 直接设置余额（管理员 set；同时刷新玩家名缓存列）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @param name   玩家名称（缓存列）
     * @param value  目标余额（≥ 0）
     * @throws SQLException JDBC 失败时
     */
    void set(Connection c, UUID player, String name, long value) throws SQLException;

    /**
     * 确保余额行存在并刷新玩家名缓存列（服务层持有玩家名时在
     * {@link #tryAdd} 前调用，避免种子行留下空名）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @param name   玩家名称（缓存列）
     * @param now    当前时刻（UTC epoch 毫秒）
     * @throws SQLException JDBC 失败时
     */
    void ensureRow(Connection c, UUID player, String name, long now) throws SQLException;

    /**
     * 写入一条称号币流水（与余额变动同事务）。
     *
     * @param c 数据库连接
     * @param e 流水条目（id 忽略）
     * @throws SQLException JDBC 失败时
     */
    void log(Connection c, CoinLogEntry e) throws SQLException;
}
