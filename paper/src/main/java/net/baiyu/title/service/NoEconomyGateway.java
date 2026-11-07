package net.baiyu.title.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 经济缺失降级实现：Vault 不在场时由组合根选用。
 * 恒不可用、余额恒 0、扣款恒失败——金币类称号照常渲染，
 * 资格判定自然落入「经济系统不可用」分支，全插件零硬依赖。
 */
public final class NoEconomyGateway implements EconomyGateway {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public BigDecimal balance(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return BigDecimal.ZERO;
    }

    @Override
    public boolean withdraw(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(amount, "扣款金额不能为空");
        return false;
    }

    @Override
    public String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "金额不能为空");
        return amount.stripTrailingZeros().toPlainString();
    }
}
