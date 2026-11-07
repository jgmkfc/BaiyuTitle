package net.baiyu.title.service.purchase;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 外部支付结果（paper 侧主线程执行 {@link PaymentPlan} 后回报）。
 *
 * @param success    是否成功（Vault 扣款 + 物品扣除 + 权限复核全部到位）
 * @param failReason 失败原因（成功时为空；落订单 fail_reason 列）
 */
public record PaymentOutcome(boolean success, @Nullable String failReason) {

    /** 紧凑构造器：失败时必须给出原因。 */
    public PaymentOutcome {
        if (!success) {
            Objects.requireNonNull(failReason, "支付失败必须给出原因");
        }
    }

    /**
     * 成功结果。
     *
     * @return 成功结果
     */
    public static PaymentOutcome ok() {
        return new PaymentOutcome(true, null);
    }

    /**
     * 失败结果。
     *
     * @param failReason 失败原因
     * @return 失败结果
     */
    public static PaymentOutcome failed(String failReason) {
        return new PaymentOutcome(false, failReason);
    }
}
