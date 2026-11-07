package net.baiyu.title.service.trade;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;

/**
 * 接受报价结果（密封类型穷举分支，调用方 switch 处理）。
 */
public sealed interface AcceptResult {

    /**
     * 成交：双方标的已互换、佩戴引用已清、缓存已同步。
     *
     * @param trade 终态交易单快照（status=COMPLETED，含标的）
     */
    record Completed(Trade trade) implements AcceptResult {
    }

    /**
     * 成交复核失败，整单作废（PENDING→DESTROYED，双方零变化；
     * M7 DoD"标的缺失作废"）。
     *
     * @param tradeId    交易 id
     * @param failReason 机器可读失败原因（如 {@code ITEM_NOT_OWNED:7}）
     */
    record Invalidated(long tradeId, String failReason) implements AcceptResult {
    }

    /**
     * 报价已过期（本次调用顺手迁移 PENDING→EXPIRED，不等周期扫描）。
     *
     * @param tradeId 交易 id
     */
    record Expired(long tradeId) implements AcceptResult {
    }

    /**
     * 交易已被并发解决（条件迁移 0 行命中；并发接受仅一方成功的败方结果）。
     *
     * @param tradeId 交易 id
     * @param status  当前终态
     */
    record AlreadyResolved(long tradeId, TradeStatus status) implements AcceptResult {
    }

    /**
     * 交易不存在。
     *
     * @param tradeId 交易 id
     */
    record UnknownTrade(long tradeId) implements AcceptResult {
    }

    /**
     * 操作者不是报价目标方（只有目标方可接受）。
     *
     * @param tradeId 交易 id
     */
    record NotTarget(long tradeId) implements AcceptResult {
    }

    /**
     * 交易总开关关闭（config trade.enabled=false，全入口封禁）。
     *
     * @param tradeId 交易 id
     */
    record Disabled(long tradeId) implements AcceptResult {
    }
}
