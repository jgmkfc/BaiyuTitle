package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.order.PurchaseOrder;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * 外部支付指令（订单进入 PAYING 后，paper 侧主线程需要执行/复核的动作清单）。
 * <p>字段直接取自订单的价格快照列（订单快照 = 实际应付项：AND 为全部条件、
 * OR 为所选分支）；称号币不在其中——它已在 RESERVED 事务内以条件 UPDATE 结清。
 * <p>paper 侧契约（主线程同 tick）：先全量预验（Vault 余额、物品数量、权限持有），
 * 全满足后一次性 withdrawPlayer + 扣物品，再回调
 * {@link net.baiyu.title.service.PurchaseService#completePayment}。
 *
 * @param vaultAmount    应付 Vault 金额（可空 = 无此项）
 * @param itemBase64     应付物品的 Base64 序列化串（可空 = 无此项）
 * @param itemAmount     应付物品数量（与 itemBase64 成对出现）
 * @param permissionNode 需复核持有的权限节点（可空；"或"免单分支或"与"权限要求）
 */
public record PaymentPlan(
        @Nullable BigDecimal vaultAmount,
        @Nullable String itemBase64,
        @Nullable Integer itemAmount,
        @Nullable String permissionNode) {

    /**
     * 是否存在需要主线程执行的外部动作（免费/纯称号币订单为 false，可直接完成支付）。
     *
     * @return 存在返回 true
     */
    public boolean requiresExternalAction() {
        return vaultAmount != null || itemBase64 != null || permissionNode != null;
    }

    /**
     * 从订单价格快照构建支付指令。
     *
     * @param order 订单
     * @return 支付指令
     */
    public static PaymentPlan of(PurchaseOrder order) {
        return new PaymentPlan(order.vaultAmount(), order.itemData(),
                order.itemAmount(), order.permissionNode());
    }
}
