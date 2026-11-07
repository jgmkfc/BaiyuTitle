package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 应付项裁决器（纯函数）：把称号条件集 + GUI 所选分支 + 快照裁决为订单价格快照。
 * <p>语义：AND = 全部条件应付；OR（条件 ≥ 2）= 单分支应付，
 * 且服务端强制"含权限且玩家持有 → 免单权限分支"（无视 GUI 传入的分支）。
 * 免费称号 = 应付项空集。
 * <p>裁决失败以 {@link PurchaseAbort} 中止（分支非法 / 权限未持有 / 经济不可用）。
 */
public final class ChargeResolver {

    private ChargeResolver() {
    }

    /**
     * 订单应付项（即订单表的价格快照列；仅实际应付的列非空）。
     *
     * @param chosenCond     "或"分支快照（AND/免费为空）
     * @param vaultAmount    应付 Vault 金额（可空）
     * @param coinAmount     应付称号币（可空）
     * @param itemBase64     应付物品 Base64（可空）
     * @param itemAmount     应付物品数量（可空）
     * @param permissionNode 需持有的权限节点（可空）
     */
    public record Charges(
            @Nullable ConditionType chosenCond,
            @Nullable BigDecimal vaultAmount,
            @Nullable Long coinAmount,
            @Nullable String itemBase64,
            @Nullable Integer itemAmount,
            @Nullable String permissionNode) {

        /** 免费称号的空应付项。 */
        public static final Charges FREE = new Charges(null, null, null, null, null, null);
    }

    /**
     * 裁决应付项。
     *
     * @param title     称号（NORMAL 种类）
     * @param requested GUI 传入的"或"分支（AND/免费可空）
     * @param snapshot  玩家上下文快照（权限持有 / 经济可用性）
     * @return 应付项
     * @throws PurchaseAbort 分支非法（INVALID_BRANCH）、应付权限未持有（CONDITIONS_UNMET）、
     *                       应付含 Vault 而经济不可用（ECONOMY_UNAVAILABLE）时
     */
    public static Charges resolve(Title title, @Nullable ConditionType requested,
                                  PurchaseSnapshot snapshot) {
        List<PurchaseCondition> conditions = title.conditions();
        if (conditions.isEmpty()) {
            return Charges.FREE;
        }
        boolean orLogic = title.combineLogic() == CombineLogic.OR && conditions.size() >= 2;
        if (!orLogic) {
            return build(null, conditions, snapshot);
        }
        // 服务端强制免单：含权限条件且玩家持有 → 恒走权限分支
        PurchaseCondition branch = conditions.stream()
                .filter(c -> c instanceof PermissionCondition p && snapshot.hasPermission(p.node()))
                .findFirst()
                .orElseGet(() -> requested == null ? null
                        : conditions.stream().filter(c -> c.type() == requested).findFirst().orElse(null));
        if (branch == null) {
            throw new PurchaseAbort(BeginResult.RejectReason.INVALID_BRANCH);
        }
        return build(branch.type(), List.of(branch), snapshot);
    }

    /**
     * 把应付条件列表铺到价格快照列，并做快照侧硬校验
     * （权限必须已持有；Vault 应付必须经济可用——Vault 余额/物品数量留待 PAYING 主线程重验）。
     */
    private static Charges build(@Nullable ConditionType chosen,
                                 List<PurchaseCondition> charges, PurchaseSnapshot snapshot) {
        BigDecimal vault = null;
        Long coin = null;
        String itemBase64 = null;
        Integer itemAmount = null;
        String permission = null;
        for (PurchaseCondition charge : charges) {
            switch (charge) {
                case VaultCondition v -> {
                    if (!snapshot.economyAvailable()) {
                        throw new PurchaseAbort(BeginResult.RejectReason.ECONOMY_UNAVAILABLE);
                    }
                    vault = v.amount();
                }
                case CoinCondition c -> coin = c.amount();
                case ItemCondition i -> {
                    itemBase64 = i.itemBase64();
                    itemAmount = i.amount();
                }
                case PermissionCondition p -> {
                    if (!snapshot.hasPermission(p.node())) {
                        throw new PurchaseAbort(BeginResult.RejectReason.CONDITIONS_UNMET);
                    }
                    permission = p.node();
                }
            }
        }
        return new Charges(chosen, vault, coin, itemBase64, itemAmount, permission);
    }
}
