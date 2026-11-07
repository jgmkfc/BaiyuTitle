package net.baiyu.title.repository;

import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.Title;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * 称号仓储（title / title_condition / title_bond_requirement）。
 * <p>方法一律 {@code (Connection, ...)} 签名、不开事务、不切线程；
 * 事务由服务层通过 {@link TransactionRunner} 持有。
 */
public interface TitleRepository {

    /**
     * 插入称号（含聚合内的条件与羁绊需求；RETURN_GENERATED_KEYS 取回主键）。
     *
     * @param c 数据库连接
     * @param t 称号聚合（id 忽略）
     * @return 生成的主键
     * @throws SQLException JDBC 失败时
     */
    int insert(Connection c, Title t) throws SQLException;

    /**
     * 更新称号主行的可编辑字段（不含 sold_count / deleted_at / created_*；
     * 条件与羁绊需求请分别走 {@link #replaceConditions} / {@link #replaceBondRequirements}）。
     *
     * @param c 数据库连接
     * @param t 称号聚合（按 id 定位）
     * @throws SQLException JDBC 失败时
     */
    void update(Connection c, Title t) throws SQLException;

    /**
     * 软删称号（置 deleted_at；历史完整保留，不变量 §十 13）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @param now     当前时刻（UTC epoch 毫秒）
     * @throws SQLException JDBC 失败时
     */
    void softDelete(Connection c, int titleId, long now) throws SQLException;

    /**
     * 全量装载称号（跳过软删；含条件/羁绊需求聚合装载，3 条 SQL 内）。
     *
     * @param c 数据库连接
     * @return 称号列表
     * @throws SQLException JDBC 失败时
     */
    default List<Title> loadAll(Connection c) throws SQLException {
        return loadAll(c, false);
    }

    /**
     * 全量装载称号（含条件/羁绊需求聚合装载，3 条 SQL 内）。
     *
     * @param c              数据库连接
     * @param includeDeleted 是否包含已软删称号
     * @return 称号列表
     * @throws SQLException JDBC 失败时
     */
    List<Title> loadAll(Connection c, boolean includeDeleted) throws SQLException;

    /**
     * 预留一份售卖名额（条件 UPDATE：{@code sold_count = sold_count + 1
     * WHERE id=? AND (limit_mode <> 'TOTAL' OR sold_count < total_cap)}，防超卖）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @return 预留成功返回 true；已售罄或称号不存在返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean tryReserveSoldCount(Connection c, int titleId) throws SQLException;

    /**
     * 释放一份售卖名额（订单 REFUNDED/FAILED 补偿；条件 UPDATE 防负数）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @throws SQLException JDBC 失败时
     */
    void releaseSoldCount(Connection c, int titleId) throws SQLException;

    /**
     * 全量替换购买条件（先删后插；每类型至多一条由 DB UNIQUE 兜底）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @param conds   新条件集（空集 = 免费）
     * @throws SQLException JDBC 失败时
     */
    void replaceConditions(Connection c, int titleId, List<PurchaseCondition> conds) throws SQLException;

    /**
     * 全量替换羁绊需求（先删后插）。
     *
     * @param c        数据库连接
     * @param bondId   羁绊称号 id
     * @param required 需求称号 id 集合
     * @throws SQLException JDBC 失败时
     */
    void replaceBondRequirements(Connection c, int bondId, Set<Integer> required) throws SQLException;
}
