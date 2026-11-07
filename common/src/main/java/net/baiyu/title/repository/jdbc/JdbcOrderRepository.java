package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.order.OrderKind;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.repository.OrderRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 购买订单仓储 JDBC 实现（表 V3：purchase_order）。
 * <p>订单只沿状态机边迁移：{@link #transition} 条件 UPDATE
 * {@code WHERE id=? AND state=from}（不变量 §十 8）；order_uuid 为幂等键。
 */
public final class JdbcOrderRepository implements OrderRepository {

    /** 非终态状态名 SQL 字面量清单（由 {@link OrderState#isTerminal()} 推导，枚举演进自动跟随）。 */
    private static final String NON_TERMINAL_STATES = Arrays.stream(OrderState.values())
            .filter(state -> !state.isTerminal())
            .map(state -> "'" + state.name() + "'")
            .collect(Collectors.joining(", "));

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造购买订单仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcOrderRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public long insert(Connection c, PurchaseOrder o) throws SQLException {
        String sql = "INSERT INTO " + tables.purchaseOrder()
                + " (\"order_uuid\", \"player_uuid\", \"player_name\", \"title_id\", \"kind\", \"chosen_cond\","
                + " \"vault_amount\", \"coin_amount\", \"item_data\", \"item_amount\", \"permission_node\","
                + " \"custom_name\", \"state\", \"fail_reason\", \"created_at\", \"updated_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            StatementBinders.setUuid(ps, 1, o.orderUuid());
            StatementBinders.setUuid(ps, 2, o.playerUuid());
            ps.setString(3, o.playerName());
            StatementBinders.setNullableInt(ps, 4, o.titleId());
            ps.setString(5, o.kind().name());
            StatementBinders.setNullableString(ps, 6, o.chosenCond() == null ? null : o.chosenCond().name());
            StatementBinders.setNullableBigDecimal(ps, 7, o.vaultAmount());
            StatementBinders.setNullableLong(ps, 8, o.coinAmount());
            StatementBinders.setNullableString(ps, 9, o.itemData());
            StatementBinders.setNullableInt(ps, 10, o.itemAmount());
            StatementBinders.setNullableString(ps, 11, o.permissionNode());
            StatementBinders.setNullableString(ps, 12, o.customName());
            ps.setString(13, o.state().name());
            StatementBinders.setNullableString(ps, 14, o.failReason());
            ps.setLong(15, o.createdAt());
            ps.setLong(16, o.updatedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入订单后未取得生成主键");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public boolean transition(Connection c, long orderId, OrderState from, OrderState to,
                              @Nullable String failReason, long now) throws SQLException {
        String sql = "UPDATE " + tables.purchaseOrder()
                + " SET \"state\"=?, \"fail_reason\"=?, \"updated_at\"=?"
                + " WHERE \"id\"=? AND \"state\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, to.name());
            StatementBinders.setNullableString(ps, 2, failReason);
            ps.setLong(3, now);
            ps.setLong(4, orderId);
            ps.setString(5, from.name());
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public List<PurchaseOrder> findNonTerminal(Connection c) throws SQLException {
        String sql = "SELECT * FROM " + tables.purchaseOrder()
                + " WHERE \"state\" IN (" + NON_TERMINAL_STATES + ") ORDER BY \"id\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            List<PurchaseOrder> orders = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(mapOrder(rs));
                }
            }
            return orders;
        }
    }

    @Override
    public int countFulfilled(Connection c, int titleId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tables.purchaseOrder()
                + " WHERE \"title_id\"=? AND \"state\"='" + OrderState.FULFILLED.name() + "'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public Optional<PurchaseOrder> findById(Connection c, long orderId) throws SQLException {
        String sql = "SELECT * FROM " + tables.purchaseOrder() + " WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapOrder(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean existsNonTerminal(Connection c, UUID player, int titleId) throws SQLException {
        String sql = "SELECT 1 FROM " + tables.purchaseOrder()
                + " WHERE \"player_uuid\"=? AND \"title_id\"=? AND \"state\" IN (" + NON_TERMINAL_STATES + ")"
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            ps.setInt(2, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<FulfilledBuyer> findFulfilledBuyers(Connection c, int titleId) throws SQLException {
        String sql = "SELECT \"player_uuid\", \"player_name\", \"updated_at\" FROM " + tables.purchaseOrder()
                + " WHERE \"title_id\"=? AND \"state\"='" + OrderState.FULFILLED.name() + "'"
                + " ORDER BY \"updated_at\" DESC, \"id\" DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            List<FulfilledBuyer> buyers = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    buyers.add(new FulfilledBuyer(
                            RowMappers.getUuid(rs, "player_uuid"),
                            rs.getString("player_name"),
                            rs.getLong("updated_at")));
                }
            }
            return buyers;
        }
    }

    @Override
    public void fillTitleId(Connection c, long orderId, int titleId, long now) throws SQLException {
        String sql = "UPDATE " + tables.purchaseOrder()
                + " SET \"title_id\"=?, \"updated_at\"=? WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            ps.setLong(2, now);
            ps.setLong(3, orderId);
            ps.executeUpdate();
        }
    }

    /**
     * 单行映射为订单领域对象。
     */
    private static PurchaseOrder mapOrder(ResultSet rs) throws SQLException {
        String chosenCond = rs.getString("chosen_cond");
        return new PurchaseOrder(
                rs.getLong("id"),
                RowMappers.getUuid(rs, "order_uuid"),
                RowMappers.getUuid(rs, "player_uuid"),
                rs.getString("player_name"),
                RowMappers.getNullableInt(rs, "title_id"),
                OrderKind.fromString(rs.getString("kind")),
                chosenCond == null ? null : ConditionType.fromString(chosenCond),
                rs.getBigDecimal("vault_amount"),
                RowMappers.getNullableLong(rs, "coin_amount"),
                rs.getString("item_data"),
                RowMappers.getNullableInt(rs, "item_amount"),
                rs.getString("permission_node"),
                rs.getString("custom_name"),
                OrderState.fromString(rs.getString("state")),
                rs.getString("fail_reason"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
