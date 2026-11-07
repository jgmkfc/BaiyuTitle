package net.baiyu.title.repository;

import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家组仓储（player_group / player_group_member）。
 * <p>组名唯一（服务层限 [a-z0-9_]）；删除为软删（组称号与历史保留）。
 */
public interface GroupRepository {

    /**
     * 插入玩家组（RETURN_GENERATED_KEYS 取回主键）。
     *
     * @param c     数据库连接
     * @param group 玩家组（id 忽略）
     * @return 生成的主键
     * @throws SQLException JDBC 失败时
     */
    int insert(Connection c, PlayerGroup group) throws SQLException;

    /**
     * 按 id 查组。
     *
     * @param c       数据库连接
     * @param groupId 组 id
     * @return 组；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<PlayerGroup> findById(Connection c, int groupId) throws SQLException;

    /**
     * 按组名查组（含软删行，由调用方按需过滤）。
     *
     * @param c    数据库连接
     * @param name 组名
     * @return 组；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<PlayerGroup> findByName(Connection c, String name) throws SQLException;

    /**
     * 查询全部组。
     *
     * @param c              数据库连接
     * @param includeDeleted 是否包含已软删组
     * @return 组列表
     * @throws SQLException JDBC 失败时
     */
    List<PlayerGroup> findAll(Connection c, boolean includeDeleted) throws SQLException;

    /**
     * 更新组展示名。
     *
     * @param c           数据库连接
     * @param groupId     组 id
     * @param displayName 新展示名（可空 = 清除）
     * @throws SQLException JDBC 失败时
     */
    void updateDisplayName(Connection c, int groupId, @Nullable String displayName) throws SQLException;

    /**
     * 软删组（置 deleted_at；组称号与所有权回收由服务层处理）。
     *
     * @param c       数据库连接
     * @param groupId 组 id
     * @param now     当前时刻（UTC epoch 毫秒）
     * @throws SQLException JDBC 失败时
     */
    void softDelete(Connection c, int groupId, long now) throws SQLException;

    /**
     * 添加组成员（PK(group_id, player_uuid) 冲突时由调用方先查重）。
     *
     * @param c      数据库连接
     * @param member 组成员
     * @throws SQLException JDBC 失败时
     */
    void addMember(Connection c, GroupMember member) throws SQLException;

    /**
     * 移除组成员。
     *
     * @param c       数据库连接
     * @param groupId 组 id
     * @param player  玩家 UUID
     * @return 实际删除返回 true；本就不在组内返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean removeMember(Connection c, int groupId, UUID player) throws SQLException;

    /**
     * 查询组的全部成员。
     *
     * @param c       数据库连接
     * @param groupId 组 id
     * @return 成员列表
     * @throws SQLException JDBC 失败时
     */
    List<GroupMember> findMembers(Connection c, int groupId) throws SQLException;

    /**
     * 按玩家查其所在的全部未软删组（组对账 reconcile 用）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @return 组列表
     * @throws SQLException JDBC 失败时
     */
    List<PlayerGroup> findGroupsOf(Connection c, UUID player) throws SQLException;
}
