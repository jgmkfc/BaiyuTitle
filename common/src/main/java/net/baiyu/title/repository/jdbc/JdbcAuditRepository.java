package net.baiyu.title.repository.jdbc;

import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.StatementBinders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 审计日志仓储 JDBC 实现（表 V5：audit_log，只追加）。
 * <p>与业务变更同事务写入，保证审计与事实一致。
 */
public final class JdbcAuditRepository implements AuditRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造审计日志仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcAuditRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public void insert(Connection c, AuditEntry e) throws SQLException {
        String sql = "INSERT INTO " + tables.auditLog()
                + " (\"event_type\", \"actor_uuid\", \"actor_name\", \"target_uuid\", \"title_id\","
                + " \"ownership_id\", \"ref_table\", \"ref_id\", \"detail\", \"created_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.eventType());
            StatementBinders.setNullableUuid(ps, 2, e.actorUuid());
            ps.setString(3, e.actorName());
            StatementBinders.setNullableUuid(ps, 4, e.targetUuid());
            StatementBinders.setNullableInt(ps, 5, e.titleId());
            StatementBinders.setNullableLong(ps, 6, e.ownershipId());
            StatementBinders.setNullableString(ps, 7, e.refTable());
            StatementBinders.setNullableLong(ps, 8, e.refId());
            StatementBinders.setNullableString(ps, 9, e.detail());
            ps.setLong(10, e.createdAt());
            ps.executeUpdate();
        }
    }
}
