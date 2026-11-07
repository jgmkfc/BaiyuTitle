package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.order.PurchaseOrder;

import java.util.Objects;

/**
 * 发起购买的结果（{@link net.baiyu.title.service.PurchaseService#beginPurchase} 返回，
 * 密封类型，调用方 switch 穷举）。
 */
public sealed interface BeginResult {

    /**
     * 已开单：订单处于 PAYING，paper 侧按支付指令执行外部支付后回调 completePayment。
     *
     * @param order 订单（state = PAYING）
     * @param plan  外部支付指令
     */
    record Begun(PurchaseOrder order, PaymentPlan plan) implements BeginResult {

        /** 紧凑构造器：判空。 */
        public Begun {
            Objects.requireNonNull(order, "订单不能为空");
            Objects.requireNonNull(plan, "支付指令不能为空");
        }
    }

    /**
     * 已拒绝：权威复核未通过，未产生任何持久化副作用（事务整体回滚）。
     *
     * @param reason 拒绝原因
     */
    record Rejected(RejectReason reason) implements BeginResult {

        /** 紧凑构造器：判空。 */
        public Rejected {
            Objects.requireNonNull(reason, "拒绝原因不能为空");
        }
    }

    /**
     * 开单拒绝原因（写线程事务内权威复核的失败分支）。
     */
    enum RejectReason {

        /** 称号不存在或已软删。 */
        TITLE_NOT_FOUND,

        /** 称号种类不支持商店直购（GROUP/BOND/CUSTOM）。 */
        NOT_PURCHASABLE,

        /** 称号处于隐藏状态。 */
        HIDDEN,

        /** 不在获取窗口期内。 */
        OUTSIDE_WINDOW,

        /** 已售罄（tryReserveSoldCount 条件 UPDATE 未命中）。 */
        SOLD_OUT,

        /** 已 ACTIVE 持有同款（持有期间禁止重复购买）。 */
        ALREADY_OWNED,

        /** 同玩家同称号已存在非终态订单（幂等防重六）。 */
        PENDING_ORDER_EXISTS,

        /** 称号币余额不足（tryAdd 条件 UPDATE 未命中，防透支）。 */
        INSUFFICIENT_COINS,

        /** 应付项含 Vault 而经济系统不可用。 */
        ECONOMY_UNAVAILABLE,

        /** "或"分支非法（未选择、所选类型不在条件集内）。 */
        INVALID_BRANCH,

        /** 所选/应付条件按快照判定不满足（如权限分支未持有该权限）。 */
        CONDITIONS_UNMET,

        /** 自定义称号名称非法（剥色后为空或超过 50 码点）。 */
        NAME_INVALID
    }
}
