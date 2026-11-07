package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.repository.codec.StatementBinders;
import net.baiyu.title.repository.exception.DuplicateActiveException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 所有权仓储 JDBC 实现（表 V2：title_ownership）。
 * <p>唯一约束 {@code UNIQUE(owner_uuid, title_id, active_marker)} 结构化保证
 * 每 (owner, title) 至多一条 ACTIVE：insert 时 status=ACTIVE 则
 * active_marker=TRUE 否则 NULL（NULL 不参与唯一比较）。
 */
public final class JdbcOwnershipRepository implements OwnershipRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造所有权仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcOwnershipRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public long insertGrant(Connection c, Ownership o) throws SQLException {
        String sql = "INSERT INTO " + tables.titleOwnership()
                + " (\"title_id\", \"owner_uuid\", \"owner_name\", \"source\", \"source_ref\","
                + " \"acquired_at\", \"expires_at\", \"status\", \"active_marker\", \"ended_at\", \"ended_reason\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, o.titleId());
            StatementBinders.setUuid(ps, 2, o.ownerUuid());
            ps.setString(3, o.ownerName());
            ps.setString(4, o.source().name());
            StatementBinders.setNullableLong(ps, 5, o.sourceRef());
            ps.setLong(6, o.acquiredAt());
            StatementBinders.setNullableLong(ps, 7, o.expiresAt());
            ps.setString(8, o.status().name());
            // ACTIVE=TRUE 否则 NULL：NULL 不参与三列唯一约束比较
            StatementBinders.setTrueOrNull(ps, 9, o.status() == OwnershipStatus.ACTIVE);
            StatementBinders.setNullableLong(ps, 10, o.endedAt());
            StatementBinders.setNullableString(ps, 11, o.endedReason());
            try {
                ps.executeUpdate();
            } catch (SQLException e) {
                if (JdbcSupport.isUniqueViolation(e)) {
                    throw new DuplicateActiveException(o.ownerUuid(), o.titleId(), e);
                }
                throw e;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入所有权后未取得生成主键");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public boolean endActive(Connection c, long ownershipId, OwnershipStatus to, String reason, long now)
            throws SQLException {
        String sql = "UPDATE " + tables.titleOwnership()
                + " SET \"status\"=?, \"active_marker\"=NULL, \"ended_at\"=?, \"ended_reason\"=?"
                + " WHERE \"id\"=? AND \"status\"='ACTIVE'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, to.name());
            ps.setLong(2, now);
            StatementBinders.setNullableString(ps, 3, reason);
            ps.setLong(4, ownershipId);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public List<Ownership> findActiveByOwner(Connection c, UUID owner) throws SQLException {
        String sql = "SELECT * FROM " + tables.titleOwnership()
                + " WHERE \"owner_uuid\"=? AND \"status\"='ACTIVE' ORDER BY \"id\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, owner);
            return queryList(ps);
        }
    }

    @Override
    public Optional<Ownership> findActive(Connection c, UUID owner, int titleId) throws SQLException {
        String sql = "SELECT * FROM " + tables.titleOwnership()
                + " WHERE \"owner_uuid\"=? AND \"title_id\"=? AND \"status\"='ACTIVE'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, owner);
            ps.setInt(2, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public List<Ownership> pickExpired(Connection c, long now, int batch) throws SQLException {
        String sql = "SELECT * FROM " + tables.titleOwnership()
                + " WHERE \"status\"='ACTIVE' AND \"expires_at\" IS NOT NULL AND \"expires_at\" < ?"
                + " ORDER BY \"expires_at\" LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, batch);
            return queryList(ps);
        }
    }

    @Override
    public List<UUID> findOwnersHavingAll(Connection c, Set<Integer> titleIds) throws SQLException {
        if (titleIds.isEmpty()) {
            throw new IllegalArgumentException("需求称号集合不能为空");
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(titleIds.size(), "?"));
        String sql = "SELECT \"owner_uuid\" FROM " + tables.titleOwnership()
                + " WHERE \"status\"='ACTIVE' AND \"title_id\" IN (" + placeholders + ")"
                + " GROUP BY \"owner_uuid\" HAVING COUNT(DISTINCT \"title_id\") = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (int titleId : titleIds) {
                ps.setInt(i++, titleId);
            }
            ps.setInt(i, titleIds.size());
            List<UUID> owners = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(RowMappers.getUuid(rs, "owner_uuid"));
                }
            }
            return owners;
        }
    }

    @Override
    public int countDistinctBuyers(Connection c, int titleId) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT \"owner_uuid\") FROM " + tables.titleOwnership()
                + " WHERE \"title_id\"=? AND \"source\"='" + GrantSource.PURCHASE.name() + "'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 执行查询并映射为所有权列表。
     */
    private static List<Ownership> queryList(PreparedStatement ps) throws SQLException {
        List<Ownership> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }
        return result;
    }

    /**
     * 单行映射为所有权领域对象。
     */
    private static Ownership mapRow(ResultSet rs) throws SQLException {
        return new Ownership(
                rs.getLong("id"),
                rs.getInt("title_id"),
                RowMappers.getUuid(rs, "owner_uuid"),
                rs.getString("owner_name"),
                GrantSource.fromString(rs.getString("source")),
                RowMappers.getNullableLong(rs, "source_ref"),
                rs.getLong("acquired_at"),
                RowMappers.getNullableLong(rs, "expires_at"),
                OwnershipStatus.fromString(rs.getString("status")),
                RowMappers.getNullableLong(rs, "ended_at"),
                rs.getString("ended_reason"));
    }
}
