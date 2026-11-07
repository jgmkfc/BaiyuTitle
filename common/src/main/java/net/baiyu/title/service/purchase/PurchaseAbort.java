package net.baiyu.title.service.purchase;

import java.util.Objects;

/**
 * 购买流程中止信号（内部流程控制异常，不携带堆栈）。
 * <p>在写线程事务体内抛出时经 {@code TransactionRunner} 原样透传并触发整体回滚
 * ——保证"任一权威复核失败即零持久化副作用"；由
 * {@link net.baiyu.title.service.PurchaseService} 捕获并转为
 * {@link BeginResult.Rejected} 友好结果，绝不外泄给调用方。
 */
public final class PurchaseAbort extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** 拒绝原因。 */
    private final transient BeginResult.RejectReason reason;

    /**
     * 构造中止信号。
     *
     * @param reason 拒绝原因
     */
    public PurchaseAbort(BeginResult.RejectReason reason) {
        // 流程控制用途：不写消息、不填堆栈（禁 suppression 无必要，保守保留）
        super(null, null, true, false);
        this.reason = Objects.requireNonNull(reason, "拒绝原因不能为空");
    }

    /**
     * 拒绝原因。
     *
     * @return 拒绝原因
     */
    public BeginResult.RejectReason reason() {
        return reason;
    }
}
