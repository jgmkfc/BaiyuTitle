package net.baiyu.title.service;

import net.baiyu.title.api.event.TitlePurchasedEvent;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.service.purchase.BeginResult;
import net.baiyu.title.service.purchase.CompleteResult;
import net.baiyu.title.service.purchase.PaymentOutcome;
import net.baiyu.title.service.purchase.PaymentPlan;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 购买执行链（paper 侧主线程编排）：确认菜单点击后走
 * beginPurchase → 主线程外部支付（Vault 扣款 / 物品扣除 / 权限复核）→
 * completePayment 的完整链路；回滚 / 幂等语义全部由
 * {@link PurchaseService} 的订单状态机保证，本类只做主线程动作与玩家反馈。
 *
 * <p>主线程契约（{@link PaymentPlan} Javadoc）：同 tick 先全量预验
 * （权限持有、Vault 余额、物品数量——计数口径与
 * {@code ShopQueryService#collectContext} 一致：主背包 + isSimilar），
 * 全满足后一次性 withdraw + 扣物品，再回报 {@link PaymentOutcome}。
 * 称号币不在支付指令中（RESERVE 事务已结清）。</p>
 *
 * <p>事件契约：{@code TitlePrePurchaseEvent}（可取消）经 {@link PrePurchaseGate}
 * 在主线程、{@code beginPurchase} 之前发出，取消即静默中止零副作用；
 * {@link TitlePurchasedEvent} 在订单履约（commit 之后）回到主线程时派发。</p>
 */
public final class PurchaseFlow {

    /** 插件实例（主线程调度与日志）。 */
    private final Plugin plugin;

    /** 购买服务（订单状态机唯一入口）。 */
    private final PurchaseService purchaseService;

    /** 经济门面（Vault 扣款，仅主线程触碰）。 */
    private final EconomyGateway economyGateway;

    /** 语言门面（玩家反馈取词）。 */
    private final Lang lang;

    /** 称号物品渲染门面（成功提示中的称号名展示）。 */
    private final TitleItemRenderer renderer;

    /** 购买前事件闸门（生产装配发 TitlePrePurchaseEvent；单测注入桩）。 */
    private final PrePurchaseGate preGate;

    /**
     * 构造购买执行链。
     *
     * @param plugin          插件实例
     * @param purchaseService 购买服务
     * @param economyGateway  经济门面
     * @param lang            语言门面
     * @param renderer        称号物品渲染门面
     * @param preGate         购买前事件闸门（返回 false = Pre 事件被取消）
     */
    public PurchaseFlow(Plugin plugin, PurchaseService purchaseService,
                        EconomyGateway economyGateway, Lang lang, TitleItemRenderer renderer,
                        PrePurchaseGate preGate) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.purchaseService = Objects.requireNonNull(purchaseService, "购买服务不能为空");
        this.economyGateway = Objects.requireNonNull(economyGateway, "经济门面不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.renderer = Objects.requireNonNull(renderer, "称号物品渲染门面不能为空");
        this.preGate = Objects.requireNonNull(preGate, "购买前事件闸门不能为空");
    }

    /**
     * 发起并执行完整购买链（<b>仅主线程</b>；确认菜单一次性 token 已挡双击，
     * 服务端条件 UPDATE 是幂等的最后防线）。
     * <p>先发可取消的 Pre 事件：被取消即静默中止（零 DB 写、零扣款），
     * 仅回调 {@code onSettled} 解锁菜单。</p>
     *
     * @param player    买家
     * @param entry     商店条目视图（携带主线程预收集的快照）
     * @param chosen    "或"逻辑所选分支（与确认菜单展示的应付项一致；AND / 免费为空）
     * @param onSettled 链路终局回调（主线程；成功 / 拒绝 / 失败都会恰好调用一次）
     */
    public void start(Player player, ShopEntryView entry, @Nullable ConditionType chosen,
                      Runnable onSettled) {
        Objects.requireNonNull(player, "买家不能为空");
        Objects.requireNonNull(entry, "商店条目不能为空");
        Objects.requireNonNull(onSettled, "终局回调不能为空");
        if (!preGate.allow(player, entry.title().id())) {
            onSettled.run();
            return;
        }
        purchaseService.beginPurchase(player.getUniqueId(), player.getName(),
                        entry.title().id(), chosen, entry.snapshot())
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("发起购买失败（玩家 {}，称号 {}）",
                                player.getName(), entry.title().id(), failure);
                        send(player, "command.error.internal");
                        onSettled.run();
                        return;
                    }
                    switch (result) {
                        case BeginResult.Rejected rejected -> {
                            send(player, rejectKey(rejected.reason()));
                            onSettled.run();
                        }
                        case BeginResult.Begun begun -> executePayment(player, entry, begun, onSettled);
                    }
                }));
    }

    /**
     * "或"分支自动裁决：按条件声明顺序取首个满足的分支
     * （含权限且持有时服务端恒强制免单权限分支，与此处传值无关）；
     * AND / 免费 / 单条件返回 null。
     *
     * @param entry 商店条目视图
     * @return 所选分支；无需选择时为 null
     */
    public static @Nullable ConditionType chooseBranch(ShopEntryView entry) {
        if (entry.title().combineLogic() != CombineLogic.OR || entry.title().conditions().size() < 2) {
            return null;
        }
        for (Map.Entry<ConditionType, Boolean> status : entry.eligibility().conditionStatus().entrySet()) {
            if (Boolean.TRUE.equals(status.getValue())) {
                return status.getKey();
            }
        }
        return null;
    }

    /**
     * 开单拒绝原因 → 语言键（{@code shop.reject.*} 全枚举双语已预配）。
     *
     * @param reason 拒绝原因
     * @return 语言键
     */
    public static String rejectKey(BeginResult.RejectReason reason) {
        return "shop.reject." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 主线程执行外部支付并回报服务收敛（成功履约 / 失败退款）。 */
    private void executePayment(Player player, ShopEntryView entry,
                                BeginResult.Begun begun, Runnable onSettled) {
        PaymentOutcome outcome = performExternalActions(player, begun.plan());
        purchaseService.completePayment(begun.order().id(), outcome)
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("订单收敛失败（订单 {}，玩家 {}）",
                                begun.order().id(), player.getName(), failure);
                        send(player, "command.error.internal");
                    } else {
                        if (result instanceof CompleteResult.Fulfilled fulfilled) {
                            // Post 事件：commit 之后回到主线程派发
                            new TitlePurchasedEvent(player, entry.title().id(),
                                    fulfilled.orderId()).callEvent();
                        }
                        announce(player, entry, result);
                    }
                    onSettled.run();
                }));
    }

    /** 同 tick 先全量预验、后一次性扣付；失败原因落订单 fail_reason 列（内部串，非玩家文案）。 */
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
        ItemStack probe = null;
        if (plan.itemBase64() != null) {
            try {
                probe = ItemStack.deserializeBytes(Base64.getDecoder().decode(plan.itemBase64()));
            } catch (RuntimeException e) {
                return PaymentOutcome.failed("ITEM_DESERIALIZE_FAILED");
            }
            if (countSimilar(player, probe) < plan.itemAmount()) {
                return PaymentOutcome.failed("ITEM_INSUFFICIENT");
            }
        }
        // 预验全过，一次性执行（同 tick 无并发介入；Vault 响应失败即中止，物品未动）
        if (plan.vaultAmount() != null
                && !economyGateway.withdraw(player.getUniqueId(), plan.vaultAmount())) {
            return PaymentOutcome.failed("VAULT_WITHDRAW_FAILED");
        }
        if (probe != null) {
            removeSimilar(player, probe, plan.itemAmount());
        }
        return PaymentOutcome.ok();
    }

    /** 终局播报（在线才发；文案全部走 lang）。 */
    private void announce(Player player, ShopEntryView entry, CompleteResult result) {
        if (!player.isOnline()) {
            return;
        }
        switch (result) {
            case CompleteResult.Fulfilled ignored -> send(player, "menu.shop.result.success",
                    Placeholder.component("title", renderer.nameWithBrackets(entry.title())));
            case CompleteResult.Refunded ignored -> send(player, "menu.shop.result.payment-failed");
            case CompleteResult.MarkedManual manual -> send(player, "menu.shop.result.manual",
                    Placeholder.parsed("order", Long.toString(manual.orderId())));
            case CompleteResult.AlreadyResolved ignored ->
                    send(player, "menu.shop.result.already-resolved");
            case CompleteResult.UnknownOrder ignored -> send(player, "command.error.internal");
        }
    }

    /** 主背包持有数统计（与 ShopQueryService.collectContext 同口径）。 */
    private static int countSimilar(Player player, ItemStack probe) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(probe)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** 从主背包扣除给定数量的同类物品（预验已保证足额）。 */
    private static void removeSimilar(Player player, ItemStack probe, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !stack.isSimilar(probe)) {
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

    /** 带前缀的玩家提示。 */
    private void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
    }

    /** 跳回主线程（插件停用中静默丢弃）。 */
    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 购买前事件闸门（主线程同步调用）：生产装配发 {@code TitlePrePurchaseEvent}
     * 并返回未被取消；单测注入桩验证"取消则服务不被调"分支。
     */
    @FunctionalInterface
    public interface PrePurchaseGate {

        /**
         * 是否放行本次购买。
         *
         * @param buyer   买家
         * @param titleId 目标称号 id
         * @return 放行返回 true；Pre 事件被取消返回 false
         */
        boolean allow(Player buyer, int titleId);
    }
}
