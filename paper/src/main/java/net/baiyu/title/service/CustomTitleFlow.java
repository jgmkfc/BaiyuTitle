package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.gui.shop.PaymentOptionView;
import net.baiyu.title.service.purchase.BeginResult;
import net.baiyu.title.service.purchase.CompleteResult;
import net.baiyu.title.service.purchase.PaymentOutcome;
import net.baiyu.title.service.purchase.PaymentPlan;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 自定义称号执行链：
 * {@link #options} 把 config {@code custom-title} 四种付款方式预计算为
 * 付款菜单视图；{@link #create} 走 beginCustomPurchase → 主线程支付 →
 * completePayment 完整链路，成功后全服公告（lang {@code custom-title.announcement}）。
 *
 * <p>CUSTOM 订单支付口径（与 {@link PurchaseFlow} 分道，阶段 1 既定约定）：
 * 订单 item_data 列存 config 的材质 namespaced key（非 Base64 物品），
 * 物品扣付按<b>材质计数</b>（任意同材质物品均可抵扣）；Vault / 权限口径同商店链。
 * 名称归一化与 50 码点红线由服务端 {@code TitleCatalogService.validateName}
 * 权威裁决，铁砧侧 {@code InputValidators.length} 只做输入长度预检。</p>
 */
public final class CustomTitleFlow {

    /** MiniMessage 解析器（订单 customName 已归一化，直接反序列化展示）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** 插件实例（主线程调度与日志）。 */
    private final Plugin plugin;

    /** 购买服务（CUSTOM 订单状态机入口）。 */
    private final PurchaseService purchaseService;

    /** 经济门面（Vault 可用性与扣款，仅主线程触碰）。 */
    private final EconomyGateway economyGateway;

    /** 玩家档案缓存（快照的称号币余额来源）。 */
    private final PlayerProfileCache profileCache;

    /** 插件配置提供器（custom-title 段随 reload 取新值）。 */
    private final Supplier<PluginConfig> configSupplier;

    /** 语言门面。 */
    private final Lang lang;

    /** 付款选项组装器（H/I/J/K 视图预计算）。 */
    private final CustomPaymentOptions paymentOptions;

    /**
     * 构造自定义称号执行链。
     *
     * @param plugin          插件实例
     * @param purchaseService 购买服务
     * @param economyGateway  经济门面
     * @param profileCache    玩家档案缓存
     * @param configSupplier  插件配置提供器
     * @param lang            语言门面
     * @param materialNames   物品名解析器
     */
    public CustomTitleFlow(Plugin plugin, PurchaseService purchaseService,
                           EconomyGateway economyGateway, PlayerProfileCache profileCache,
                           Supplier<PluginConfig> configSupplier, Lang lang,
                           Function<Material, Component> materialNames) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.purchaseService = Objects.requireNonNull(purchaseService, "购买服务不能为空");
        this.economyGateway = Objects.requireNonNull(economyGateway, "经济门面不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.paymentOptions = new CustomPaymentOptions(economyGateway, configSupplier, lang,
                Objects.requireNonNull(materialNames, "物品名解析器不能为空"));
    }

    /**
     * 付款选项视图（<b>仅主线程</b>；H/I/J/K 固定顺序）：价格 ≤ 0 的货币方式
     * 展示为免费；Vault 缺失 H 不可用、权限节点未配置 K 不可用、材质非法 J
     * 不可用（组装规则见 {@link CustomPaymentOptions}）。
     *
     * @param player 在线玩家
     * @return 四付款选项视图（不可变）
     */
    public List<PaymentOptionView> options(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        return paymentOptions.options();
    }

    /**
     * 发起并执行完整创建链（<b>仅主线程</b>；确认菜单一次性 token 已挡双击，
     * 服务端防重复核是幂等的最后防线）。
     *
     * @param player    创建者
     * @param payment   所选付款方式（VAULT/COIN/ITEM/PERMISSION 四选一）
     * @param rawName   原始称号名（铁砧输入，三格式颜色码均可）
     * @param onSettled 链路终局回调（主线程；成功 / 拒绝 / 失败都会恰好调用一次）
     */
    public void create(Player player, ConditionType payment, String rawName, Runnable onSettled) {
        Objects.requireNonNull(player, "创建者不能为空");
        Objects.requireNonNull(payment, "付款方式不能为空");
        Objects.requireNonNull(rawName, "称号名不能为空");
        Objects.requireNonNull(onSettled, "终局回调不能为空");
        purchaseService.beginCustomPurchase(player.getUniqueId(), player.getName(),
                        rawName, payment, snapshot(player))
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("发起自定义称号创建失败（玩家 {}）",
                                player.getName(), failure);
                        send(player, "command.error.internal");
                        onSettled.run();
                        return;
                    }
                    switch (result) {
                        case BeginResult.Rejected rejected -> {
                            send(player, PurchaseFlow.rejectKey(rejected.reason()));
                            onSettled.run();
                        }
                        case BeginResult.Begun begun -> executePayment(player, begun, onSettled);
                    }
                }));
    }

    /** 主线程收集购买上下文快照（权限节点只需含 config 免单节点）。 */
    private PurchaseSnapshot snapshot(Player player) {
        PluginConfig.CustomTitleSettings cfg = configSupplier.get().customTitle;
        boolean economyAvailable = economyGateway.isAvailable();
        String node = cfg.permissionNode;
        Set<String> held = node != null && !node.isBlank() && player.hasPermission(node)
                ? Set.of(node) : Set.of();
        long coins = profileCache.get(player.getUniqueId())
                .map(PlayerProfileCache.PlayerProfile::coinBalance)
                .orElse(0L);
        return new PurchaseSnapshot(LocalDate.now(), economyAvailable,
                economyAvailable ? economyGateway.balance(player.getUniqueId()) : BigDecimal.ZERO,
                coins, held, Map.of(), false);
    }

    /** 主线程执行外部支付并回报服务收敛（成功履约建号 / 失败退款）。 */
    private void executePayment(Player player, BeginResult.Begun begun, Runnable onSettled) {
        PaymentOutcome outcome = performExternalActions(player, begun.plan());
        purchaseService.completePayment(begun.order().id(), outcome)
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("自定义称号订单收敛失败（订单 {}，玩家 {}）",
                                begun.order().id(), player.getName(), failure);
                        send(player, "command.error.internal");
                    } else {
                        announce(player, begun.order().customName(), result);
                    }
                    onSettled.run();
                }));
    }

    /**
     * 同 tick 先全量预验、后一次性扣付（CUSTOM 口径：item_data 为材质 key，
     * 物品按材质计数抵扣；失败原因落订单 fail_reason 列，内部串非玩家文案）。
     */
    private PaymentOutcome performExternalActions(Player player, PaymentPlan plan) {
        if (!plan.requiresExternalAction()) {
            return PaymentOutcome.ok();
        }
        if (!player.isOnline()) {
            return PaymentOutcome.failed("PLAYER_OFFLINE");
        }
        if (plan.permissionNode() != null && !player.hasPermission(plan.permissionNode())) {
            return PaymentOutcome.failed("PERMISSION_LOST");
        }
        if (plan.vaultAmount() != null) {
            if (!economyGateway.isAvailable()) {
                return PaymentOutcome.failed("ECONOMY_UNAVAILABLE");
            }
            if (economyGateway.balance(player.getUniqueId()).compareTo(plan.vaultAmount()) < 0) {
                return PaymentOutcome.failed("VAULT_INSUFFICIENT");
            }
        }
        Material material = null;
        if (plan.itemBase64() != null) {
            material = Material.matchMaterial(plan.itemBase64());
            if (material == null) {
                return PaymentOutcome.failed("ITEM_MATERIAL_UNKNOWN");
            }
            if (countByType(player, material) < plan.itemAmount()) {
                return PaymentOutcome.failed("ITEM_INSUFFICIENT");
            }
        }
        // 预验全过，一次性执行（同 tick 无并发介入；Vault 响应失败即中止，物品未动）
        if (plan.vaultAmount() != null
                && !economyGateway.withdraw(player.getUniqueId(), plan.vaultAmount())) {
            return PaymentOutcome.failed("VAULT_WITHDRAW_FAILED");
        }
        if (material != null) {
            removeByType(player, material, plan.itemAmount());
        }
        return PaymentOutcome.ok();
    }

    /** 终局播报：履约成功→全服公告 + 私发成功提示；其余分支私发对应文案。 */
    private void announce(Player player, String customName, CompleteResult result) {
        switch (result) {
            case CompleteResult.Fulfilled ignored -> {
                Component name = MINI_MESSAGE.deserialize(customName);
                plugin.getServer().sendMessage(lang.component("custom-title.announcement",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.component("name", name)));
                if (player.isOnline()) {
                    send(player, "menu.custom.result.success", Placeholder.component("name", name));
                }
            }
            case CompleteResult.Refunded ignored -> send(player, "menu.shop.result.payment-failed");
            case CompleteResult.MarkedManual manual -> send(player, "menu.shop.result.manual",
                    Placeholder.parsed("order", Long.toString(manual.orderId())));
            case CompleteResult.AlreadyResolved ignored ->
                    send(player, "menu.shop.result.already-resolved");
            case CompleteResult.UnknownOrder ignored -> send(player, "command.error.internal");
        }
    }

    /** 主背包同材质物品计数（CUSTOM 口径：只看材质不比 NBT）。 */
    private static int countByType(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** 从主背包扣除给定数量的同材质物品（预验已保证足额）。 */
    private static void removeByType(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            remaining -= take;
            if (take >= stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    /** 带前缀的玩家提示（离线静默丢弃）。 */
    private void send(Player player, String key, TagResolver... resolvers) {
        if (player.isOnline()) {
            player.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
        }
    }

    /** 跳回主线程（插件停用中静默丢弃）。 */
    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
