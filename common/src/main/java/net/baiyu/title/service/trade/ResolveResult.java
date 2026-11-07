package net.baiyu.title.service.trade;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;

/**
 * 拒绝/撤回/管理员销毁的统一结果（密封类型穷举分支，调用方 switch 处理）。
 */
public sealed interface ResolveResult {

    /**
     * 解决成功（交易已迁入目标终态）。
     *
     * @param trade 终态交易单快照（含标的）
     */
    record Resolved(Trade trade) implements ResolveResult {
    }

    /**
     * 交易不存在。
     *
     * @param tradeId 交易 id
     */
    record UnknownTrade(long tradeId) implements ResolveResult {
    }

    /**
     * 交易已被并发解决（PENDING 之外的状态不可再迁出，不变量 §十 8）。
     *
     * @param tradeId 交易 id
     * @param status  当前终态
     */
    record AlreadyResolved(long tradeId, TradeStatus status) implements ResolveResult {
    }

    /**
     * 操作者不是本操作要求的交易方（拒绝限目标方、撤回限发起方）。
     *
     * @param tradeId 交易 id
     */
    record NotParticipant(long tradeId) implements ResolveResult {
    }
}
