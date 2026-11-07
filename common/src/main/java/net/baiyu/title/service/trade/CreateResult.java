package net.baiyu.title.service.trade;

import net.baiyu.title.domain.trade.Trade;
import org.jetbrains.annotations.Nullable;

/**
 * 创建报价结果（密封类型穷举分支，调用方 switch 处理）。
 */
public sealed interface CreateResult {

    /**
     * 创建成功（交易已落库为 PENDING）。
     *
     * @param trade 已落库交易单快照（含标的）
     */
    record Created(Trade trade) implements CreateResult {
    }

    /**
     * 创建被拒绝（零持久化副作用）。
     *
     * @param reason  阻断原因
     * @param titleId 关联称号 id（结构性拒绝时为空）
     */
    record Rejected(TradeBlockReason reason, @Nullable Integer titleId) implements CreateResult {
    }
}
