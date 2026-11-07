package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 购买资格判定引擎（纯函数：零 Bukkit、零数据库、零可变状态）。
 * <p>输入称号聚合与玩家上下文快照，输出 {@link Eligibility} 三分支；
 * 语义对照业务文档 §1.1.2.1（与/或组合、"或"含权限免单）、§1.1.2.5（窗口/总数限定）、
 * §1.3.2.4.1（已拥有→跳转仓库）与设计 02 §9（经济降级）。
 * <p>本引擎只做展示预判与分支预裁决；权威裁决（防重、防超卖、防透支）
 * 在 {@link net.baiyu.title.service.PurchaseService} 写线程事务内以条件 UPDATE 完成。
 */
public final class EligibilityEngine {

    /**
     * 判定玩家对给定称号的购买资格。
     * <p>裁决顺序：已拥有优先短路（业务 §1.3.2.4.1：已拥有恒显示"跳转仓库"）；
     * 其余阻断原因累积为集合（种类不可购/软删/隐藏/窗口外/售罄/经济不可用/条件不满足）。
     *
     * @param title    称号聚合
     * @param snapshot 玩家上下文快照
     * @return 判定结果
     */
    public Eligibility evaluate(Title title, PurchaseSnapshot snapshot) {
        Objects.requireNonNull(title, "称号不能为空");
        Objects.requireNonNull(snapshot, "上下文快照不能为空");
        Map<ConditionType, Boolean> status = conditionStatus(title, snapshot);

        // 已拥有短路：ACTIVE 持有期间禁止重复购买，商店点击跳转仓库
        if (snapshot.alreadyOwned()) {
            return new Eligibility.Owned(status);
        }

        Set<BlockReason> reasons = EnumSet.noneOf(BlockReason.class);
        if (title.category() != TitleCategory.NORMAL) {
            reasons.add(BlockReason.NOT_PURCHASABLE);
        }
        if (title.isDeleted()) {
            reasons.add(BlockReason.DELETED);
        }
        if (!title.visible()) {
            reasons.add(BlockReason.HIDDEN);
        }
        if (!title.isWithinWindow(snapshot.today())) {
            reasons.add(BlockReason.OUTSIDE_WINDOW);
        }
        if (title.isSoldOut()) {
            reasons.add(BlockReason.SOLD_OUT);
        }
        boolean combinationMet = isCombinationMet(title, status);
        if (!combinationMet) {
            reasons.add(BlockReason.CONDITIONS_UNMET);
            // 组合未满足且含 Vault 条件而经济不可用时，额外标注经济原因
            // （"或"逻辑下其他分支满足则整体可购，经济缺失不构成阻断）
            if (!snapshot.economyAvailable() && status.containsKey(ConditionType.VAULT)) {
                reasons.add(BlockReason.ECONOMY_UNAVAILABLE);
            }
        }
        if (!reasons.isEmpty()) {
            return new Eligibility.CannotBuy(reasons, status);
        }
        return new Eligibility.CanBuy(status, isFreeOfCharge(title, snapshot));
    }

    /**
     * 逐条件求值（按称号条件声明顺序；免费称号返回空 Map）。
     *
     * @param title    称号聚合
     * @param snapshot 玩家上下文快照
     * @return 条件类型 → 是否满足 的有序明细
     */
    public Map<ConditionType, Boolean> conditionStatus(Title title, PurchaseSnapshot snapshot) {
        Map<ConditionType, Boolean> status = new LinkedHashMap<>();
        for (PurchaseCondition condition : title.conditions()) {
            status.put(condition.type(), isSatisfied(condition, snapshot));
        }
        return status;
    }

    /**
     * 单条件求值：VAULT 需经济可用且余额足额；COIN 比余额；ITEM 比持有数；PERMISSION 查持有。
     */
    private static boolean isSatisfied(PurchaseCondition condition, PurchaseSnapshot snapshot) {
        return switch (condition) {
            case VaultCondition vault -> snapshot.economyAvailable()
                    && snapshot.vaultBalance().compareTo(vault.amount()) >= 0;
            case CoinCondition coin -> snapshot.coinBalance() >= coin.amount();
            case ItemCondition item -> snapshot.itemCount(item.itemBase64()) >= item.amount();
            case PermissionCondition permission -> snapshot.hasPermission(permission.node());
        };
    }

    /**
     * 组合逻辑求值：免费（条件空集）恒满足；AND = 全满足；OR = 满足任一。
     * <p>组合逻辑仅条件数 ≥ 2 时有意义，单条件时两种逻辑等价。
     */
    private static boolean isCombinationMet(Title title, Map<ConditionType, Boolean> status) {
        if (status.isEmpty()) {
            return true;
        }
        if (title.combineLogic() == CombineLogic.OR && status.size() >= 2) {
            return status.containsValue(Boolean.TRUE);
        }
        return !status.containsValue(Boolean.FALSE);
    }

    /**
     * 免单判定：免费称号恒免单；"或"逻辑（条件 ≥ 2）含权限条件且玩家持有时免单。
     */
    private static boolean isFreeOfCharge(Title title, PurchaseSnapshot snapshot) {
        if (title.isFree()) {
            return true;
        }
        if (title.combineLogic() != CombineLogic.OR || title.conditions().size() < 2) {
            return false;
        }
        return title.conditions().stream()
                .anyMatch(condition -> condition instanceof PermissionCondition permission
                        && snapshot.hasPermission(permission.node()));
    }
}
