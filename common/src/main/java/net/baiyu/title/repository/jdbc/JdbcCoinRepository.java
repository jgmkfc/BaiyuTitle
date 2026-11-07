package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.coin.CoinLogEntry;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.StatementBinders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * 称号币仓储 JDBC 实现（表 V3：player_coin / coin_log）。
 * <p>扣款用条件 UPDATE {@code WHERE balance >= ?} 禁透支（不变量 §十 9）；
 * 种子行采用先 UPDATE 后 INSERT 两步式，规避四方言 upsert 语法差异
 * （写操作单线程串行，无并发插入竞态）。
 */
public final class JdbcCoinRepository implements CoinRepository {

    /** 种子行的占位玩家名（服务层持有真名时应先走 {@link #ensureRow} 刷新）。 */
    private static final String PLACEHOLDER_NAME = "";

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造称号币仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcCoinRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public long getBalance(Connection c, UUID player) throws SQLException {
        String sql = "SELECT \"balance\" FROM " + tables.playerCoin() + " WHERE \"player_uuid\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    @Override
    public boolean tryAdd(Connection c, UUID player, long delta) throws SQLException {
        if (delta == 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        // 不存在行时先补余额 0 的种子行（两步式 upsert）
        seedRowIfAbsent(c, player, now);
        String sql;
        if (delta > 0) {
            sql = "UPDATE " + tables.playerCoin()
                    + " SET \"balance\" = \"balance\" + ?, \"updated_at\"=? WHERE \"player_uuid\"=?";
        } else {
            // 扣款：条件 UPDATE 禁透支
            sql = "UPDATE " + tables.playerCoin()
                    + " SET \"balance\" = \"balance\" + ?, \"updated_at\"=?"
                    + " WHERE \"player_uuid\"=? AND \"balance\" >= ?";
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setLong(2, now);
            StatementBinders.setUuid(ps, 3, player);
            if (delta < 0) {
                ps.setLong(4, -delta);
            }
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public void set(Connection c, UUID player, String name, long value) throws SQLException {
        Objects.requireNonNull(name, "玩家名称不能为空");
        if (value < 0) {
            throw new IllegalArgumentException("余额不能设为负数: " + value);
        }
        long now = System.currentTimeMillis();
        String updateSql = "UPDATE " + tables.playerCoin()
                + " SET \"player_name\"=?, \"balance\"=?, \"updated_at\"=? WHERE \"player_uuid\"=?";
        try (PreparedStatement update = c.prepareStatement(updateSql)) {
            update.setString(1, name);
            update.setLong(2, value);
            update.setLong(3, now);
            StatementBinders.setUuid(update, 4, player);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        insertRowIgnoringDuplicate(c, player, name, value, now);
    }

    @Override
    public void ensureRow(Connection c, UUID player, String name, long now) throws SQLException {
        Objects.requireNonNull(name, "玩家名称不能为空");
        String updateSql = "UPDATE " + tables.playerCoin()
                + " SET \"player_name\"=? WHERE \"player_uuid\"=?";
        try (PreparedStatement update = c.prepareStatement(updateSql)) {
            update.setString(1, name);
            StatementBinders.setUuid(update, 2, player);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        insertRowIgnoringDuplicate(c, player, name, 0L, now);
    }

    @Override
    public void log(Connection c, CoinLogEntry e) throws SQLException {
        String sql = "INSERT INTO " + tables.coinLog()
                + " (\"player_uuid\", \"delta\", \"balance_after\", \"reason\", \"actor\", \"ref_id\", \"created_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, e.playerUuid());
            ps.setLong(2, e.delta());
            ps.setLong(3, e.balanceAfter());
            ps.setString(4, e.reason().name());
            ps.setString(5, e.actor());
            StatementBinders.setNullableLong(ps, 6, e.refId());
            ps.setLong(7, e.createdAt());
            ps.executeUpdate();
        }
    }

    /**
     * 不存在余额行时补种子行（余额 0、占位名）；已存在则不动。
     */
    private void seedRowIfAbsent(Connection c, UUID player, long now) throws SQLException {
        String probeSql = "UPDATE " + tables.playerCoin()
                + " SET \"updated_at\"=? WHERE \"player_uuid\"=?";
        try (PreparedStatement probe = c.prepareStatement(probeSql)) {
            probe.setLong(1, now);
            StatementBinders.setUuid(probe, 2, player);
            if (probe.executeUpdate() > 0) {
                return;
            }
        }
        insertRowIgnoringDuplicate(c, player, PLACEHOLDER_NAME, 0L, now);
    }

    /**
     * 插入余额行；主键冲突静默忽略（覆盖 MySQL useAffectedRows=true 时
     * 无变化 UPDATE 报 0 行的边界，两步式 upsert 的兜底）。
     */
    private void insertRowIgnoringDuplicate(Connection c, UUID player, String name, long balance, long now)
            throws SQLException {
        String insertSql = "INSERT INTO " + tables.playerCoin()
                + " (\"player_uuid\", \"player_name\", \"balance\", \"updated_at\") VALUES (?, ?, ?, ?)";
        try (PreparedStatement insert = c.prepareStatement(insertSql)) {
            StatementBinders.setUuid(insert, 1, player);
            insert.setString(2, name);
            insert.setLong(3, balance);
            insert.setLong(4, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            if (!JdbcSupport.isUniqueViolation(e)) {
                throw e;
            }
        }
    }
}
