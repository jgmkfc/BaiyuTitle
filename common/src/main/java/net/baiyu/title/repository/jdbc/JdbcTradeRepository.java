package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.Page;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.codec.StatementBinders;
import net.baiyu.title.repository.codec.UuidCodec;
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
 * 交易仓储 JDBC 实现（表 V4：trade / trade_item）。
 * <p>状态迁移一律条件 UPDATE（{@code WHERE status=from}，0 行 = 并发已解决放弃，
 * 不变量 §十 8）；历史分页不装载标的（记录级不强制，见 {@link Trade} 说明）。
 * <p>行映射细节见 {@link TradeRowMappers} 伴生类。
 */
public final class JdbcTradeRepository implements TradeRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造交易仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcTradeRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public long insert(Connection c, Trade t, List<TradeItem> items) throws SQLException {
        String sql = "INSERT INTO " + tables.trade()
                + " (\"initiator_uuid\", \"initiator_name\", \"target_uuid\", \"target_name\","
                + " \"status\", \"created_at\", \"expires_at\", \"resolved_at\", \"resolved_by\", \"fail_reason\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long tradeId;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            StatementBinders.setUuid(ps, 1, t.initiatorUuid());
            ps.setString(2, t.initiatorName());
            StatementBinders.setUuid(ps, 3, t.targetUuid());
            ps.setString(4, t.targetName());
            ps.setString(5, t.status().name());
            ps.setLong(6, t.createdAt());
            ps.setLong(7, t.expiresAt());
            StatementBinders.setNullableLong(ps, 8, t.resolvedAt());
            StatementBinders.setNullableString(ps, 9, t.resolvedBy());
            StatementBinders.setNullableString(ps, 10, t.failReason());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入交易单后未取得生成主键");
                }
                tradeId = keys.getLong(1);
            }
        }
        insertItems(c, tradeId, items);
        return tradeId;
    }

    /**
     * 批量插入交易标的（与主表插入同一事务连接）。
     */
    private void insertItems(Connection c, long tradeId, List<TradeItem> items) throws SQLException {
        if (items.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tables.tradeItem()
                + " (\"trade_id\", \"side\", \"ownership_id\", \"title_id\", \"title_name_snapshot\")"
                + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (TradeItem item : items) {
                ps.setLong(1, tradeId);
                ps.setString(2, item.side().name());
                ps.setLong(3, item.ownershipId());
                ps.setInt(4, item.titleId());
                ps.setString(5, item.titleNameSnapshot());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public boolean transition(Connection c, long tradeId, TradeStatus from, TradeStatus to,
                              String by, String reason, long now) throws SQLException {
        String sql = "UPDATE " + tables.trade()
                + " SET \"status\"=?, \"resolved_at\"=?, \"resolved_by\"=?, \"fail_reason\"=?"
                + " WHERE \"id\"=? AND \"status\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, to.name());
            ps.setLong(2, now);
            StatementBinders.setNullableString(ps, 3, by);
            StatementBinders.setNullableString(ps, 4, reason);
            ps.setLong(5, tradeId);
            ps.setString(6, from.name());
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public Optional<Trade> findWithItems(Connection c, long tradeId) throws SQLException {
        String sql = "SELECT * FROM " + tables.trade() + " WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(TradeRowMappers.mapTrade(rs, loadItems(c, tradeId)));
            }
        }
    }

    @Override
    public List<Trade> findPendingFor(Connection c, UUID player) throws SQLException {
        String sql = "SELECT * FROM " + tables.trade()
                + " WHERE (\"initiator_uuid\"=? OR \"target_uuid\"=?) AND \"status\"='PENDING'"
                + " ORDER BY \"id\"";
        List<Trade> bare;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            StatementBinders.setUuid(ps, 2, player);
            bare = queryTrades(ps);
        }
        // 玩家侧 PENDING 交易极少，逐单装载标的即可
        List<Trade> result = new ArrayList<>(bare.size());
        for (Trade t : bare) {
            result.add(TradeRowMappers.withItems(t, loadItems(c, t.id())));
        }
        return result;
    }

    @Override
    public List<Long> pickExpiredPending(Connection c, long now, int batch) throws SQLException {
        String sql = "SELECT \"id\" FROM " + tables.trade()
                + " WHERE \"status\"='PENDING' AND \"expires_at\" < ? ORDER BY \"expires_at\" LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, batch);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    @Override
    public Page<Trade> personalHistory(Connection c, UUID player, int page, int size) throws SQLException {
        String where = " WHERE (\"initiator_uuid\"=? OR \"target_uuid\"=?)";
        byte[] uuid = UuidCodec.toBytes(player);
        return queryPage(c, where, List.of(uuid, uuid), page, size);
    }

    @Override
    public Page<Trade> globalHistory(Connection c, @Nullable TradeStatus filter, @Nullable String nameLike,
                                     int page, int size) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            where.append(" AND \"status\"=?");
            params.add(filter.name());
        }
        if (nameLike != null && !nameLike.isBlank()) {
            // 用户输入中的 %/_ 是 LIKE 通配符（_ 还是 Minecraft 名合法字符），须转义；
            // 转义符选 !（连接会话 SQL_MODE 含 NO_BACKSLASH_ESCAPES，不能依赖反斜杠），
            // 显式 ESCAPE 子句保证四方言一致
            String escaped = nameLike.trim()
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_");
            String like = "%" + escaped + "%";
            where.append(" AND (\"initiator_name\" LIKE ? ESCAPE '!' OR \"target_name\" LIKE ? ESCAPE '!')");
            params.add(like);
            params.add(like);
        }
        return queryPage(c, where.toString(), params, page, size);
    }

    @Override
    public int countByPlayer(Connection c, UUID player, boolean initiatedOnly, boolean completedOnly)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tables.trade() + " WHERE "
                + (initiatedOnly ? "\"initiator_uuid\"=?" : "(\"initiator_uuid\"=? OR \"target_uuid\"=?)")
                + (completedOnly ? " AND \"status\"='" + TradeStatus.COMPLETED.name() + "'" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            if (!initiatedOnly) {
                StatementBinders.setUuid(ps, 2, player);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 通用分页查询（COUNT + LIMIT/OFFSET 两条 SQL，共用 WHERE 与参数；
     * 按创建时刻倒序，同刻按 id 倒序保证稳定分页）。
     */
    private Page<Trade> queryPage(Connection c, String where, List<Object> params, int page, int size)
            throws SQLException {
        int total;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + tables.trade() + where)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getInt(1) : 0;
            }
        }
        String sql = "SELECT * FROM " + tables.trade() + where
                + " ORDER BY \"created_at\" DESC, \"id\" DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = bindParams(ps, params);
            ps.setInt(i++, size);
            ps.setInt(i, (page - 1) * size);
            return new Page<>(queryTrades(ps), page, size, total);
        }
    }

    /**
     * 顺序绑定动态参数（本类分页仅出现字符串与 UUID 字节两类）。
     *
     * @return 下一个可用参数序号
     */
    private static int bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object param : params) {
            if (param instanceof byte[] bytes) {
                ps.setBytes(i++, bytes);
            } else {
                ps.setString(i++, (String) param);
            }
        }
        return i;
    }

    /**
     * 执行查询并映射为交易单列表（不装载标的）。
     */
    private static List<Trade> queryTrades(PreparedStatement ps) throws SQLException {
        List<Trade> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(TradeRowMappers.mapTrade(rs, List.of()));
            }
        }
        return result;
    }

    /**
     * 装载单笔交易的全部标的。
     */
    private List<TradeItem> loadItems(Connection c, long tradeId) throws SQLException {
        String sql = "SELECT * FROM " + tables.tradeItem() + " WHERE \"trade_id\"=? ORDER BY \"id\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tradeId);
            List<TradeItem> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(TradeRowMappers.mapItem(rs));
                }
            }
            return items;
        }
    }
}
