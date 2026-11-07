package net.baiyu.title.domain.condition;

/**
 * 购买条件密封接口。
 * <p>四种实现与 {@link ConditionType} 一一对应；组合逻辑见
 * {@link net.baiyu.title.domain.title.CombineLogic}。
 * 免费称号 = 条件空集（结构上保证"免费不可组合"）。
 */
public sealed interface PurchaseCondition
        permits VaultCondition, CoinCondition, ItemCondition, PermissionCondition {

    /**
     * 条件类型。
     *
     * @return 与实现类一一对应的类型枚举
     */
    ConditionType type();
}
