package net.baiyu.title.domain.condition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Vault 经济货币购买条件（对应 V1 表列 vault_amount DECIMAL(18,2)）。
 *
 * @param amount 货币金额（必须为正数；构造时归一化为 2 位小数标度）
 */
public record VaultCondition(BigDecimal amount) implements PurchaseCondition {

    /**
     * 校验金额非空且为正，并统一到列语义的 2 位小数标度。
     * <p>SQLite 数值亲和存储会丢失 DECIMAL 标度（1.00 读回为 1），BigDecimal 的
     * equals 又对标度敏感——在此归一保证跨方言往返后值对象相等。
     */
    public VaultCondition {
        Objects.requireNonNull(amount, "Vault 金额不能为空");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Vault 金额必须为正数: " + amount);
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public ConditionType type() {
        return ConditionType.VAULT;
    }
}
