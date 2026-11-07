package net.baiyu.title.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 经济门面：Vault 经济的最小抽象，
 * 消费方（商店查询 / 购买支付 / 自定义称号付款）只依赖本接口，
 * 双实现由组合根 {@code PluginAssembly} 依 Vault 在场与否选择——
 * 在场 {@code VaultEconomyGateway}、缺失 {@code NoEconomyGateway} 降级。
 *
 * <p>线程纪律：{@link #balance} / {@link #withdraw} 触碰
 * Bukkit / Vault 服务，<b>仅主线程</b>调用；{@link #isAvailable} /
 * {@link #format} 无副作用可任意线程。降级语义：经济不可用时
 * 金币类称号照常渲染，资格判定为不可购且状态区展示「经济系统不可用」。</p>
 */
public interface EconomyGateway {

    /**
     * 经济系统当前是否可用（Vault 在场且已有经济 provider 注册）。
     *
     * @return 可用返回 true
     */
    boolean isAvailable();

    /**
     * 查询玩家经济余额（<b>仅主线程</b>）。
     *
     * @param player 玩家 UUID
     * @return 余额；经济不可用时为 0
     */
    BigDecimal balance(UUID player);

    /**
     * 扣款（<b>仅主线程</b>；调用方应先足额预验，本方法失败即整体中止支付）。
     *
     * @param player 玩家 UUID
     * @param amount 扣款金额（正数）
     * @return 扣款成功返回 true；经济不可用 / 余额不足 / 响应失败返回 false
     */
    boolean withdraw(UUID player, BigDecimal amount);

    /**
     * 金额格式化为经济插件的展示文案（聊天提示用；lore 数字对照
     * 业务设计 §1.3.2.4.1 走无尾零普通数字，不经本方法）。
     *
     * @param amount 金额
     * @return 格式化文案
     */
    String format(BigDecimal amount);
}
