package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.repository.GroupRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.repository.codec.StatementBinders;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家组仓储 JDBC 实现（表 V5：player_group / player_group_member）。
 * <p>组删除为软删（置 deleted_at，历史保留）；成员表 PK(group_id, player_uuid)，
 * 查重由服务层负责（接口约定）。
 */
public final class JdbcGroupRepository implements GroupRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造玩家组仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcGroupRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public int insert(Connection c, PlayerGroup group) throws SQLException {
        String sql = "INSERT INTO " + tables.playerGroup()
                + " (\"name\", \"display_name\", \"created_by\", \"created_at\", \"deleted_at\")"
                + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, group.name());
            StatementBinders.setNullableString(ps, 2, group.displayName());
            StatementBinders.setNullableString(ps, 3, group.createdBy());
            ps.setLong(4, group.createdAt());
            StatementBinders.setNullableLong(ps, 5, group.deletedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入玩家组后未取得生成主键");
                }
                return keys.getInt(1);
            }
        }
    }

    @Override
    public Optional<PlayerGroup> findById(Connection c, int groupId) throws SQLException {
        String sql = "SELECT * FROM " + tables.playerGroup() + " WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            return queryOne(ps);
        }
    }

    @Override
    public Optional<PlayerGroup> findByName(Connection c, String name) throws SQLException {
        String sql = "SELECT * FROM " + tables.playerGroup() + " WHERE \"name\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            return queryOne(ps);
        }
    }

    @Override
    public List<PlayerGroup> findAll(Connection c, boolean includeDeleted) throws SQLException {
        String sql = "SELECT * FROM " + tables.playerGroup()
                + (includeDeleted ? "" : " WHERE \"deleted_at\" IS NULL")
                + " ORDER BY \"id\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            return queryGroups(ps);
        }
    }

    @Override
    public void updateDisplayName(Connection c, int groupId, @Nullable String displayName) throws SQLException {
        String sql = "UPDATE " + tables.playerGroup() + " SET \"display_name\"=? WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setNullableString(ps, 1, displayName);
            ps.setInt(2, groupId);
            ps.executeUpdate();
        }
    }

    @Override
    public void softDelete(Connection c, int groupId, long now) throws SQLException {
        String sql = "UPDATE " + tables.playerGroup()
                + " SET \"deleted_at\"=? WHERE \"id\"=? AND \"deleted_at\" IS NULL";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, groupId);
            ps.executeUpdate();
        }
    }

    @Override
    public void addMember(Connection c, GroupMember member) throws SQLException {
        String sql = "INSERT INTO " + tables.playerGroupMember()
                + " (\"group_id\", \"player_uuid\", \"player_name\", \"added_by\", \"added_at\")"
                + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, member.groupId());
            StatementBinders.setUuid(ps, 2, member.playerUuid());
            ps.setString(3, member.playerName());
            StatementBinders.setNullableString(ps, 4, member.addedBy());
            ps.setLong(5, member.addedAt());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean removeMember(Connection c, int groupId, UUID player) throws SQLException {
        String sql = "DELETE FROM " + tables.playerGroupMember()
                + " WHERE \"group_id\"=? AND \"player_uuid\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            StatementBinders.setUuid(ps, 2, player);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public List<GroupMember> findMembers(Connection c, int groupId) throws SQLException {
        String sql = "SELECT * FROM " + tables.playerGroupMember()
                + " WHERE \"group_id\"=? ORDER BY \"added_at\", \"player_name\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            List<GroupMember> members = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(mapMember(rs));
                }
            }
            return members;
        }
    }

    @Override
    public List<PlayerGroup> findGroupsOf(Connection c, UUID player) throws SQLException {
        String sql = "SELECT g.* FROM " + tables.playerGroup() + " g"
                + " JOIN " + tables.playerGroupMember() + " m ON m.\"group_id\" = g.\"id\""
                + " WHERE m.\"player_uuid\"=? AND g.\"deleted_at\" IS NULL ORDER BY g.\"id\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            return queryGroups(ps);
        }
    }

    /**
     * 执行查询并取至多一行映射为组。
     */
    private static Optional<PlayerGroup> queryOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(mapGroup(rs)) : Optional.empty();
        }
    }

    /**
     * 执行查询并映射为组列表。
     */
    private static List<PlayerGroup> queryGroups(PreparedStatement ps) throws SQLException {
        List<PlayerGroup> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapGroup(rs));
            }
        }
        return result;
    }

    /**
     * 单行映射为玩家组领域对象。
     */
    private static PlayerGroup mapGroup(ResultSet rs) throws SQLException {
        return new PlayerGroup(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                RowMappers.getNullableLong(rs, "deleted_at"));
    }

    /**
     * 单行映射为组成员领域对象。
     */
    private static GroupMember mapMember(ResultSet rs) throws SQLException {
        return new GroupMember(
                rs.getInt("group_id"),
                RowMappers.getUuid(rs, "player_uuid"),
                rs.getString("player_name"),
                rs.getString("added_by"),
                rs.getLong("added_at"));
    }
}
