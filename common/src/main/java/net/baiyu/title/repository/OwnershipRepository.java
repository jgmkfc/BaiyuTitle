package net.baiyu.title.repository;

import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.repository.exception.DuplicateActiveException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 所有权仓储（title_ownership）。
 * <p>一行 = 一次持有实例，只追加不删除；每 (owner, title) 至多一条 ACTIVE
 * 由 {@code UNIQUE(owner_uuid, title_id, active_marker)} 结构化保证。
 */
public interface OwnershipRepository {

    /**
     * 插入授予行（status=ACTIVE 时 active_marker=TRUE，否则 NULL；
     * 违反唯一约束转抛 {@link DuplicateActiveException}）。
     *
     * @param c 数据库连接
     * @param o 所有权实例（id 忽略）
     * @return 生成的主键
     * @throws DuplicateActiveException 同 (owner, title) 已存在 ACTIVE 行时
     * @throws SQLException             其余 JDBC 失败时
     */
    long insertGrant(Connection c, Ownership o) throws SQLException;

    /**
     * 结束一条 ACTIVE 所有权（条件 UPDATE：{@code SET status=?, active_marker=NULL
     * WHERE id=? AND status='ACTIVE'}）。
     *
     * @param c           数据库连接
     * @param ownershipId 所有权 id
     * @param to          目标状态（EXPIRED/REVOKED/TRADED）
     * @param reason      结束原因（可空）
     * @param now         当前时刻（UTC epoch 毫秒）
     * @return 迁移成功返回 true；该行已非 ACTIVE 返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean endActive(Connection c, long ownershipId, OwnershipStatus to, String reason, long now) throws SQLException;

    /**
     * 查询玩家全部 ACTIVE 所有权。
     *
     * @param c     数据库连接
     * @param owner 玩家 UUID
     * @return ACTIVE 所有权列表
     * @throws SQLException JDBC 失败时
     */
    List<Ownership> findActiveByOwner(Connection c, UUID owner) throws SQLException;

    /**
     * 查询玩家对指定称号的 ACTIVE 所有权。
     *
     * @param c       数据库连接
     * @param owner   玩家 UUID
     * @param titleId 称号 id
     * @return ACTIVE 所有权；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<Ownership> findActive(Connection c, UUID owner, int titleId) throws SQLException;

    /**
     * 捞取一批已到期但仍为 ACTIVE 的所有权（ExpiryService 周期收敛用）。
     *
     * @param c     数据库连接
     * @param now   当前时刻（UTC epoch 毫秒）
     * @param batch 批大小
     * @return 到期所有权列表（最多 batch 条）
     * @throws SQLException JDBC 失败时
     */
    List<Ownership> pickExpired(Connection c, long now, int batch) throws SQLException;

    /**
     * 查询同时 ACTIVE 持有给定全部称号的玩家（羁绊回溯 backfill 用；
     * {@code GROUP BY owner HAVING COUNT(DISTINCT title_id) = N}）。
     *
     * @param c        数据库连接
     * @param titleIds 需求称号 id 集合（非空）
     * @return 满足的玩家 UUID 列表
     * @throws SQLException JDBC 失败时
     */
    List<UUID> findOwnersHavingAll(Connection c, Set<Integer> titleIds) throws SQLException;

    /**
     * 统计某称号的去重购买人数（source=PURCHASE 的不同 owner 数）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @return 去重购买人数
     * @throws SQLException JDBC 失败时
     */
    int countDistinctBuyers(Connection c, int titleId) throws SQLException;
}
