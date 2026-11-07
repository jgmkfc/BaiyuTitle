package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.codec.RowMappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 交易行映射伴生类（{@link JdbcTradeRepository} 专用，包内可见）。
 * <p>拆出行映射细节以保持仓储主类聚焦 SQL 与流程（无上帝类约束）。
 */
final class TradeRowMappers {

    private TradeRowMappers() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 单行映射为交易单领域对象。
     *
     * @param rs    结果集（游标已指向当前行）
     * @param items 标的列表（历史分页等场景可传空列表表示不装载）
     * @return 交易单
     * @throws SQLException 取值失败时
     */
    static Trade mapTrade(ResultSet rs, List<TradeItem> items) throws SQLException {
        return new Trade(
                rs.getLong("id"),
                RowMappers.getUuid(rs, "initiator_uuid"),
                rs.getString("initiator_name"),
                RowMappers.getUuid(rs, "target_uuid"),
                rs.getString("target_name"),
                TradeStatus.fromString(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                RowMappers.getNullableLong(rs, "resolved_at"),
                rs.getString("resolved_by"),
                rs.getString("fail_reason"),
                items);
    }

    /**
     * 单行映射为交易标的领域对象。
     *
     * @param rs 结果集（游标已指向当前行）
     * @return 交易标的
     * @throws SQLException 取值失败时
     */
    static TradeItem mapItem(ResultSet rs) throws SQLException {
        return new TradeItem(
                rs.getLong("id"),
                rs.getLong("trade_id"),
                TradeSide.fromString(rs.getString("side")),
                rs.getLong("ownership_id"),
                rs.getInt("title_id"),
                rs.getString("title_name_snapshot"));
    }

    /**
     * 以给定标的列表重建交易单（record 不可变，装载标的后整体替换）。
     *
     * @param t     原交易单
     * @param items 标的列表
     * @return 携带标的的新交易单
     */
    static Trade withItems(Trade t, List<TradeItem> items) {
        return new Trade(t.id(), t.initiatorUuid(), t.initiatorName(), t.targetUuid(), t.targetName(),
                t.status(), t.createdAt(), t.expiresAt(), t.resolvedAt(), t.resolvedBy(), t.failReason(), items);
    }
}
