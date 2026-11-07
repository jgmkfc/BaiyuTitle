package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.order.OrderState;

/**
 * 订单收敛结果（completePayment / recoverOrphans / resolveManual 共用的密封类型）。
 * <p>幂等纪律：状态迁移由条件 UPDATE 唯一裁决，重复提交命中 0 行时返回
 * {@link AlreadyResolved} 而非重复履约/重复退款（双击只扣一次的服务端最后防线）。
 */
public sealed interface CompleteResult {

    /**
     * 已履约：订单 FULFILLED，所有权已授予。
     *
     * @param orderId     订单 id
     * @param ownershipId 新所有权 id
     */
    record Fulfilled(long orderId, long ownershipId) implements CompleteResult { }

    /**
     * 已退款：订单 REFUNDED，称号币已退、售卖占位已释放。
     *
     * @param orderId 订单 id
     */
    record Refunded(long orderId) implements CompleteResult { }

    /**
     * 已转人工：订单 MANUAL，等待管理员 {@code /byt admin order resolve} 裁决。
     *
     * @param orderId 订单 id
     * @param reason  转人工原因
     */
    record MarkedManual(long orderId, String reason) implements CompleteResult { }

    /**
     * 幂等空转：订单已不在期望状态（并发/重复提交），本次调用未产生任何副作用。
     *
     * @param orderId      订单 id
     * @param currentState 订单当前状态
     */
    record AlreadyResolved(long orderId, OrderState currentState) implements CompleteResult { }

    /**
     * 订单不存在。
     *
     * @param orderId 订单 id
     */
    record UnknownOrder(long orderId) implements CompleteResult { }
}
