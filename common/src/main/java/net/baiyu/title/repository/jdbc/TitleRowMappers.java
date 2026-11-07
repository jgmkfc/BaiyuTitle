package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.repository.codec.StatementBinders;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * {@link JdbcTitleRepository} 的行映射伴生类（拆分以守住单类行数红线）。
 * <p>职责：title 主行 / title_condition 行的 ResultSet 读出与
 * PreparedStatement 参数绑定；description 多行文本与 TEXT 列的互转。
 */
final class TitleRowMappers {

    /** description 多行文本在 TEXT 列中的分隔符。 */
    private static final String DESCRIPTION_SEPARATOR = "\n";

    private TitleRowMappers() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 将 title 主行 + 已聚合的条件/羁绊需求装配为领域对象。
     *
     * @param rs               定位在 title 行上的结果集
     * @param conditions       该称号的购买条件（可空视为空集）
     * @param bondRequirements 该称号的羁绊需求（可空视为空集）
     * @return 称号聚合
     * @throws SQLException 取值失败时
     */
    static Title mapTitle(ResultSet rs, List<PurchaseCondition> conditions, Set<Integer> bondRequirements)
            throws SQLException {
        return new Title(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("name_plain"),
                TitleCategory.fromString(rs.getString("category")),
                CombineLogic.fromString(rs.getString("combine_logic")),
                rs.getBoolean("visible"),
                rs.getBoolean("tradable"),
                rs.getString("rarity"),
                splitDescription(rs.getString("description")),
                rs.getString("icon_material"),
                rs.getInt("hold_duration_days"),
                LimitMode.fromString(rs.getString("limit_mode")),
                RowMappers.getLocalDate(rs, "window_start"),
                RowMappers.getLocalDate(rs, "window_end"),
                RowMappers.getNullableInt(rs, "total_cap"),
                rs.getInt("sold_count"),
                RowMappers.getNullableInt(rs, "group_id"),
                RowMappers.getNullableUuid(rs, "created_by"),
                rs.getString("created_by_name"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                RowMappers.getNullableLong(rs, "deleted_at"),
                conditions,
                bondRequirements);
    }

    /**
     * 将 title_condition 行映射为条件领域对象。
     *
     * @param rs 定位在 title_condition 行上的结果集
     * @return 购买条件
     * @throws SQLException 取值失败或行数据非法时
     */
    static PurchaseCondition mapCondition(ResultSet rs) throws SQLException {
        ConditionType type = ConditionType.fromString(rs.getString("cond_type"));
        return switch (type) {
            case VAULT -> new VaultCondition(requireNonNullColumn(rs.getBigDecimal("vault_amount"), "vault_amount"));
            case COIN -> new CoinCondition(rs.getLong("coin_amount"));
            case ITEM -> new ItemCondition(
                    rs.getString("item_data"),
                    rs.getInt("item_amount"));
            case PERMISSION -> new PermissionCondition(rs.getString("permission_node"));
        };
    }

    /**
     * 绑定 title_condition 插入参数（列序：title_id, cond_type, vault_amount,
     * coin_amount, item_data, item_amount, permission_node）。
     *
     * @param ps        预编译语句
     * @param titleId   称号 id
     * @param condition 购买条件
     * @throws SQLException 绑定失败时
     */
    static void bindConditionInsert(PreparedStatement ps, int titleId, PurchaseCondition condition)
            throws SQLException {
        ps.setInt(1, titleId);
        ps.setString(2, condition.type().name());
        BigDecimal vaultAmount = null;
        Long coinAmount = null;
        String itemData = null;
        Integer itemAmount = null;
        String permissionNode = null;
        switch (condition) {
            case VaultCondition vault -> vaultAmount = vault.amount();
            case CoinCondition coin -> coinAmount = coin.amount();
            case ItemCondition item -> {
                itemData = item.itemBase64();
                itemAmount = item.amount();
            }
            case PermissionCondition permission -> permissionNode = permission.node();
        }
        StatementBinders.setNullableBigDecimal(ps, 3, vaultAmount);
        StatementBinders.setNullableLong(ps, 4, coinAmount);
        StatementBinders.setNullableString(ps, 5, itemData);
        StatementBinders.setNullableInt(ps, 6, itemAmount);
        StatementBinders.setNullableString(ps, 7, permissionNode);
    }

    /**
     * 绑定 title 主行的公共可编辑列（insert/update 共用段，共 15 个参数）。
     * <p>列序：name, name_plain, category, combine_logic, visible, tradable,
     * rarity, description, icon_material, hold_duration_days, limit_mode,
     * window_start, window_end, total_cap, group_id。
     *
     * @param ps         预编译语句
     * @param startIndex 起始参数序号
     * @param t          称号聚合
     * @return 下一个可用参数序号
     * @throws SQLException 绑定失败时
     */
    static int bindEditableColumns(PreparedStatement ps, int startIndex, Title t) throws SQLException {
        int i = startIndex;
        ps.setString(i++, t.name());
        ps.setString(i++, t.namePlain());
        ps.setString(i++, t.category().name());
        ps.setString(i++, t.combineLogic().name());
        ps.setBoolean(i++, t.visible());
        ps.setBoolean(i++, t.tradable());
        StatementBinders.setNullableString(ps, i++, t.rarity());
        StatementBinders.setNullableString(ps, i++, joinDescription(t.description()));
        ps.setString(i++, t.iconMaterial());
        ps.setInt(i++, t.holdDurationDays());
        ps.setString(i++, t.limitMode().name());
        StatementBinders.setNullableLocalDate(ps, i++, t.windowStart());
        StatementBinders.setNullableLocalDate(ps, i++, t.windowEnd());
        StatementBinders.setNullableInt(ps, i++, t.totalCap());
        StatementBinders.setNullableInt(ps, i++, t.groupId());
        return i;
    }

    /**
     * description 多行列表 → TEXT 列（空列表存 NULL）。
     */
    static String joinDescription(List<String> description) {
        return description.isEmpty() ? null : String.join(DESCRIPTION_SEPARATOR, description);
    }

    /**
     * TEXT 列 → description 多行列表（NULL/空串还原为空列表）。
     */
    static List<String> splitDescription(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return List.of(raw.split(DESCRIPTION_SEPARATOR, -1));
    }

    /**
     * 断言非空列取值非 NULL（脏数据快速失败）。
     */
    private static <T> T requireNonNullColumn(T value, String column) throws SQLException {
        if (value == null) {
            throw new SQLException("条件行缺少必需列值: " + column);
        }
        return value;
    }
}
