package net.baiyu.title.service.trade;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 交易流程中止信号（内部流程控制异常，不携带堆栈；参照 PurchaseAbort）。
 * <p>两处用途：创建报价校验失败时经 {@code TransactionRunner} 透传触发整体回滚，
 * 由 {@link OfferCreator} 转为 {@link CreateResult.Rejected}；成交交割途中
 * 标的被并发转走时同样回滚整单，由 {@link TradeSettler} 在新事务中把交易
 * 作废为 DESTROYED。绝不外泄给服务调用方。
 */
public final class TradeAbort extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** 阻断原因。 */
    private final transient TradeBlockReason reason;

    /** 关联称号 id（结构性拒绝无关联称号时为空）。 */
    private final transient Integer titleId;

    /**
     * 构造中止信号。
     *
     * @param reason  阻断原因
     * @param titleId 关联称号 id（可空）
     */
    public TradeAbort(TradeBlockReason reason, @Nullable Integer titleId) {
        // 流程控制用途：不写消息、不填堆栈（禁 suppression 无必要，保守保留）
        super(null, null, true, false);
        this.reason = Objects.requireNonNull(reason, "阻断原因不能为空");
        this.titleId = titleId;
    }

    /**
     * 阻断原因。
     *
     * @return 阻断原因
     */
    public TradeBlockReason reason() {
        return reason;
    }

    /**
     * 关联称号 id。
     *
     * @return 称号 id；无关联时为空
     */
    public @Nullable Integer titleId() {
        return titleId;
    }

    /**
     * 机器可读的失败原因串（trade.fail_reason 列取值，如 {@code ITEM_NOT_OWNED:7}）。
     *
     * @return 原因串
     */
    public String failReason() {
        return titleId == null ? reason.name() : reason.name() + ":" + titleId;
    }
}
