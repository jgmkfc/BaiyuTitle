package net.baiyu.title.domain.condition;

/**
 * 称号币购买条件（对应 V1 表列 coin_amount BIGINT）。
 *
 * @param amount 称号币数量（必须为正数）
 */
public record CoinCondition(long amount) implements PurchaseCondition {

    /**
     * 校验数量为正。
     */
    public CoinCondition {
        if (amount <= 0) {
            throw new IllegalArgumentException("称号币数量必须为正数: " + amount);
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.COIN;
    }
}
