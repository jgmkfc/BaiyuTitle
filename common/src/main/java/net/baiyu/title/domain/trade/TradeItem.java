package net.baiyu.title.domain.trade;

import java.util.Objects;

/**
 * 交易标的条目（对应 {@code trade_item} 表）。
 * <p>引用具体所有权实例，剩余持有时间随实例转移。
 *
 * @param id                主键（未落库时为 0）
 * @param tradeId           所属交易 id
 * @param side              标的所属交易方
 * @param ownershipId       所有权实例 id
 * @param titleId           称号 id
 * @param titleNameSnapshot 称号名称快照（下单时的归一化 MiniMessage）
 */
public record TradeItem(
        long id,
        long tradeId,
        TradeSide side,
        long ownershipId,
        int titleId,
        String titleNameSnapshot) {

    /**
     * 紧凑构造器：必填字段判空。
     */
    public TradeItem {
        Objects.requireNonNull(side, "交易方不能为空");
        Objects.requireNonNull(titleNameSnapshot, "称号名称快照不能为空");
    }
}
