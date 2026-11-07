package net.baiyu.title.repository.jdbc;

import net.baiyu.title.repository.AuditRepository.AuditEntry;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcAuditRepository} 双方言单测（表 V5：audit_log，只追加）。
 * <p>仓储只有写入口，读侧用直接 SQL 按 id 升序取回全表核对。
 */
class AuditRepositoryTest extends BaseRepositoryTest {

    /** insert：全字段条目与全可空条目各一条，按插入序读回全列往返。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_appendsAndRoundTrips(TestDialect dialect) {
        initDatabase(dialect);
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AuditEntry full = new AuditEntry("GRANT", actor, "Admin", target, 7, 11L,
                "purchase_order", 99L, "{\"days\":30}", 1_000L);
        AuditEntry minimal = new AuditEntry("RELOAD", null, "SYSTEM", null, null, null,
                null, null, null, 2_000L);
        runInTx(c -> {
            auditRepository.insert(c, full);
            auditRepository.insert(c, minimal);
            return null;
        });

        assertEquals(List.of(full, minimal), readAllOrdered());
    }

    /** 只追加语义：同内容条目重复写入互不影响，行数只增不减且主键各不相同。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_appendOnlyAllowsDuplicates(TestDialect dialect) {
        initDatabase(dialect);
        AuditEntry entry = new AuditEntry("REVOKE", null, "SYSTEM", UUID.randomUUID(), 7, null,
                null, null, null, 1_000L);
        runInTx(c -> {
            auditRepository.insert(c, entry);
            auditRepository.insert(c, entry);
            return null;
        });

        assertEquals(2, countRows(tables.auditLog()));
        assertEquals(List.of(entry, entry), readAllOrdered());
        long distinctIds = scalarLong("SELECT COUNT(DISTINCT \"id\") FROM " + tables.auditLog());
        assertEquals(2, distinctIds, "自增主键应各不相同");
    }

    /** 按 id 升序读回全部审计条目（映射回 {@link AuditEntry} 以复用 record 等值比较）。 */
    private List<AuditEntry> readAllOrdered() {
        return withConnection(c -> {
            String sql = "SELECT * FROM " + tables.auditLog() + " ORDER BY \"id\"";
            List<AuditEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertTrue(rs.getLong("id") > 0, "主键应由数据库生成");
                    entries.add(new AuditEntry(
                            rs.getString("event_type"),
                            RowMappers.getNullableUuid(rs, "actor_uuid"),
                            rs.getString("actor_name"),
                            RowMappers.getNullableUuid(rs, "target_uuid"),
                            RowMappers.getNullableInt(rs, "title_id"),
                            RowMappers.getNullableLong(rs, "ownership_id"),
                            rs.getString("ref_table"),
                            RowMappers.getNullableLong(rs, "ref_id"),
                            rs.getString("detail"),
                            rs.getLong("created_at")));
                }
            }
            return entries;
        });
    }
}
