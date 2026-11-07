package net.baiyu.title.service.redeem;

import java.util.Objects;

/**
 * 兑换结果（{@link net.baiyu.title.service.RedeemService} 的兑换码与称号卡
 * 两条路径共用，密封类型，调用方 switch 穷举）。
 */
public sealed interface RedeemResult {

    /**
     * 兑换成功：所有权已授予。
     *
     * @param ownershipId 新所有权 id
     * @param titleId     兑换到的称号 id
     */
    record Redeemed(long ownershipId, int titleId) implements RedeemResult { }

    /**
     * 兑换被拒（零副作用：不消耗用量/不核销卡片）。
     *
     * @param reason 拒绝原因
     */
    record Rejected(RejectReason reason) implements RedeemResult {

        /** 紧凑构造器：判空。 */
        public Rejected {
            Objects.requireNonNull(reason, "拒绝原因不能为空");
        }
    }

    /**
     * 兑换拒绝原因。
     */
    enum RejectReason {

        /** 兑换码不存在。 */
        CODE_NOT_FOUND,

        /** 兑换码已停用。 */
        CODE_DISABLED,

        /** 不在兑换码有效期内（未生效或已过期）。 */
        CODE_OUTSIDE_VALIDITY,

        /** 兑换码总用量已耗尽（条件 UPDATE 未命中，不变量 §十 14）。 */
        CODE_EXHAUSTED,

        /** 达到每玩家使用次数上限。 */
        CODE_PLAYER_LIMIT,

        /** 称号卡不存在。 */
        CARD_NOT_FOUND,

        /** 称号卡已被核销（条件 UPDATE 单次防刷，不变量 §十 14）。 */
        CARD_ALREADY_REDEEMED,

        /** 目标称号不存在或已软删。 */
        TITLE_MISSING,

        /** 已 ACTIVE 持有目标称号（不消耗用量/不核销）。 */
        ALREADY_OWNED
    }
}
