package net.baiyu.title.hook.vault;

import net.baiyu.title.service.EconomyGateway;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Vault 经济门面桥接：经 {@link VaultHook} 取
 * 经济 provider，把 {@link EconomyGateway} 的最小契约适配到 Vault API。
 *
 * <p>可用性逐次动态判定（{@link VaultHook} 监听 ServiceRegisterEvent 惰性
 * 刷新 provider）：Vault 在场但经济插件晚注册 / 中途卸载的场景自动收敛，
 * 不可用窗口内余额按 0、扣款按失败返回。{@link #balance} / {@link #withdraw}
 * 触碰 Bukkit 服务，仅主线程调用（接口契约）。</p>
 */
public final class VaultEconomyGateway implements EconomyGateway {

    /** Vault 钩子（provider 持有者，随服务注册事件惰性刷新）。 */
    private final VaultHook vaultHook;

    /**
     * 构造 Vault 经济门面桥接。
     *
     * @param vaultHook Vault 钩子（组合根从 Hook.Vault 取出后注入）
     */
    public VaultEconomyGateway(VaultHook vaultHook) {
        this.vaultHook = Objects.requireNonNull(vaultHook, "Vault 钩子不能为空");
    }

    @Override
    public boolean isAvailable() {
        return vaultHook.isHookLoaded() && vaultHook.isEconomyLoaded();
    }

    @Override
    public BigDecimal balance(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        if (!isAvailable()) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(vaultHook.getEconomy().getBalance(offline(player)));
    }

    @Override
    public boolean withdraw(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(amount, "扣款金额不能为空");
        if (!isAvailable() || amount.signum() <= 0) {
            return false;
        }
        EconomyResponse response =
                vaultHook.getEconomy().withdrawPlayer(offline(player), amount.doubleValue());
        return response != null && response.transactionSuccess();
    }

    @Override
    public String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "金额不能为空");
        if (!isAvailable()) {
            return amount.stripTrailingZeros().toPlainString();
        }
        return vaultHook.getEconomy().format(amount.doubleValue());
    }

    /** UUID → OfflinePlayer（不触发网络查询，Vault API 的标准入参形态）。 */
    private static OfflinePlayer offline(UUID player) {
        return Bukkit.getOfflinePlayer(player);
    }
}
