package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import net.baiyu.title.repository.RedeemRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.repository.codec.StatementBinders;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 兑换仓储 JDBC 实现（表 V5：redeem_code / redeem_code_use / redeem_card）。
 * <p>并发安全全靠条件 UPDATE（不变量 §十 14）：兑换码用量
 * {@code WHERE max_uses=0 OR used_count<max_uses}；称号卡核销
 * {@code WHERE redeemed_at IS NULL} 单次防刷。
 */
public final class JdbcRedeemRepository implements RedeemRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造兑换仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcRedeemRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public long insertCode(Connection c, RedeemCode code) throws SQLException {
        String sql = "INSERT INTO " + tables.redeemCode()
                + " (\"code\", \"title_id\", \"max_uses\", \"used_count\", \"per_player_uses\","
                + " \"valid_from\", \"valid_until\", \"enabled\", \"created_by\", \"created_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.code());
            ps.setInt(2, code.titleId());
            ps.setInt(3, code.maxUses());
            ps.setInt(4, code.usedCount());
            ps.setInt(5, code.perPlayerUses());
            StatementBinders.setNullableLong(ps, 6, code.validFrom());
            StatementBinders.setNullableLong(ps, 7, code.validUntil());
            ps.setBoolean(8, code.enabled());
            StatementBinders.setNullableString(ps, 9, code.createdBy());
            ps.setLong(10, code.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入兑换码后未取得生成主键");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public Optional<RedeemCode> findCodeByCode(Connection c, String code) throws SQLException {
        String sql = "SELECT * FROM " + tables.redeemCode() + " WHERE \"code\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCode(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean tryConsumeUse(Connection c, long codeId) throws SQLException {
        String sql = "UPDATE " + tables.redeemCode()
                + " SET \"used_count\" = \"used_count\" + 1"
                + " WHERE \"id\"=? AND \"enabled\"=TRUE AND (\"max_uses\" = 0 OR \"used_count\" < \"max_uses\")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public boolean setCodeEnabled(Connection c, long codeId, boolean enabled) throws SQLException {
        String sql = "UPDATE " + tables.redeemCode() + " SET \"enabled\"=? WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, codeId);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public void recordUse(Connection c, long codeId, UUID player, @Nullable Long ownershipId, long usedAt)
            throws SQLException {
        String sql = "INSERT INTO " + tables.redeemCodeUse()
                + " (\"code_id\", \"player_uuid\", \"used_at\", \"ownership_id\") VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            StatementBinders.setUuid(ps, 2, player);
            ps.setLong(3, usedAt);
            StatementBinders.setNullableLong(ps, 4, ownershipId);
            ps.executeUpdate();
        }
    }

    @Override
    public int countUsesByPlayer(Connection c, long codeId, UUID player) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tables.redeemCodeUse()
                + " WHERE \"code_id\"=? AND \"player_uuid\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            StatementBinders.setUuid(ps, 2, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public void insertCard(Connection c, RedeemCard card) throws SQLException {
        String sql = "INSERT INTO " + tables.redeemCard()
                + " (\"card_uuid\", \"title_id\", \"hold_days_override\", \"issued_by\", \"issued_at\","
                + " \"redeemed_by\", \"redeemed_at\", \"ownership_id\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, card.cardUuid());
            ps.setInt(2, card.titleId());
            StatementBinders.setNullableInt(ps, 3, card.holdDaysOverride());
            StatementBinders.setNullableString(ps, 4, card.issuedBy());
            ps.setLong(5, card.issuedAt());
            StatementBinders.setNullableUuid(ps, 6, card.redeemedBy());
            StatementBinders.setNullableLong(ps, 7, card.redeemedAt());
            StatementBinders.setNullableLong(ps, 8, card.ownershipId());
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<RedeemCard> findCard(Connection c, UUID cardUuid) throws SQLException {
        String sql = "SELECT * FROM " + tables.redeemCard() + " WHERE \"card_uuid\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, cardUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCard(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean tryRedeemCard(Connection c, UUID cardUuid, UUID redeemedBy, long now) throws SQLException {
        String sql = "UPDATE " + tables.redeemCard()
                + " SET \"redeemed_by\"=?, \"redeemed_at\"=?"
                + " WHERE \"card_uuid\"=? AND \"redeemed_at\" IS NULL";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, redeemedBy);
            ps.setLong(2, now);
            StatementBinders.setUuid(ps, 3, cardUuid);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public void bindCardOwnership(Connection c, UUID cardUuid, long ownershipId) throws SQLException {
        String sql = "UPDATE " + tables.redeemCard()
                + " SET \"ownership_id\"=? WHERE \"card_uuid\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ownershipId);
            StatementBinders.setUuid(ps, 2, cardUuid);
            ps.executeUpdate();
        }
    }

    /**
     * 单行映射为兑换码领域对象。
     */
    private static RedeemCode mapCode(ResultSet rs) throws SQLException {
        return new RedeemCode(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getInt("title_id"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getInt("per_player_uses"),
                RowMappers.getNullableLong(rs, "valid_from"),
                RowMappers.getNullableLong(rs, "valid_until"),
                rs.getBoolean("enabled"),
                rs.getString("created_by"),
                rs.getLong("created_at"));
    }

    /**
     * 单行映射为称号卡领域对象。
     */
    private static RedeemCard mapCard(ResultSet rs) throws SQLException {
        return new RedeemCard(
                RowMappers.getUuid(rs, "card_uuid"),
                rs.getInt("title_id"),
                RowMappers.getNullableInt(rs, "hold_days_override"),
                rs.getString("issued_by"),
                rs.getLong("issued_at"),
                RowMappers.getNullableUuid(rs, "redeemed_by"),
                RowMappers.getNullableLong(rs, "redeemed_at"),
                RowMappers.getNullableLong(rs, "ownership_id"));
    }
}
