package net.baiyu.title.listener;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.RedeemCardItems;
import net.baiyu.title.service.RedeemService;
import net.baiyu.title.service.redeem.RedeemResult;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 称号卡右键兑换监听（可以用称号卡兑换称号）。
 *
 * <p>右键手持称号卡（PDC 带卡片 UUID）→ 取消交互 →
 * {@link RedeemService#redeemCard} 写线程核销 → 主线程按结果反馈：
 * 成功先<b>二次校验持有</b>再消耗物品（数量 -1）。防刷双保险：
 * 服务端条件 UPDATE 单次核销（同卡并发只成一次）+ 每玩家在途栅栏
 * （核销未返回前吞掉重复右键）。</p>
 *
 * <p>线程纪律：事件与消耗均主线程；future 完成后统一 runTask 跳回。</p>
 */
public final class RedeemCardListener implements Listener {

    /** 插件实例（主线程调度与日志）。 */
    private final Plugin plugin;

    /** 兑换服务。 */
    private final RedeemService redeemService;

    /** 称号卡物品编解码器。 */
    private final RedeemCardItems cardItems;

    /** 语言门面。 */
    private final Lang lang;

    /** 称号目录缓存（成功反馈的称号取名）。 */
    private final TitleCatalogCache catalogCache;

    /** 称号物品渲染门面（带括号称号名）。 */
    private final TitleItemRenderer renderer;

    /** 在途栅栏：核销未返回的玩家集合（仅主线程读写）。 */
    private final Set<UUID> inFlight = new HashSet<>();

    /**
     * 构造称号卡右键兑换监听。
     *
     * @param plugin        插件实例
     * @param redeemService 兑换服务
     * @param cardItems     称号卡物品编解码器
     * @param lang          语言门面
     * @param catalogCache  称号目录缓存
     * @param renderer      称号物品渲染门面
     */
    public RedeemCardListener(Plugin plugin, RedeemService redeemService, RedeemCardItems cardItems,
                              Lang lang, TitleCatalogCache catalogCache, TitleItemRenderer renderer) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.redeemService = Objects.requireNonNull(redeemService, "兑换服务不能为空");
        this.cardItems = Objects.requireNonNull(cardItems, "称号卡编解码器不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.renderer = Objects.requireNonNull(renderer, "称号物品渲染门面不能为空");
    }

    /**
     * 右键交互：手持物品带卡片 PDC 才接管（主手 / 副手各自成事件，
     * 各按自己手上的物品判定）。
     *
     * @param event 玩家交互事件
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Optional<UUID> cardUuid = cardItems.extract(event.getItem());
        if (cardUuid.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        final Player player = event.getPlayer();
        if (!inFlight.add(player.getUniqueId())) {
            return; // 在途栅栏：上一次核销未返回，吞掉重复右键
        }
        redeemService.redeemCard(player.getUniqueId(), player.getName(), cardUuid.get())
                .whenComplete((result, failure) -> runSync(() -> {
                    inFlight.remove(player.getUniqueId());
                    if (failure != null) {
                        plugin.getComponentLogger().warn("称号卡核销失败（玩家 {}，卡片 {}）",
                                player.getName(), cardUuid.get(), failure);
                        send(player, "command.error.internal");
                        return;
                    }
                    handleResult(player, cardUuid.get(), result);
                }));
    }

    /** 结果分派：成功消耗物品并反馈；拒绝按原因反馈（物品不动）。 */
    private void handleResult(Player player, UUID cardUuid, RedeemResult result) {
        switch (result) {
            case RedeemResult.Redeemed redeemed -> {
                consumeOne(player, cardUuid);
                send(player, "redeem.success",
                        Placeholder.component("name", titleName(redeemed.titleId())));
            }
            case RedeemResult.Rejected rejected -> send(player, "redeem.reject."
                    + rejected.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
    }

    /**
     * 消耗一张卡（主线程；消耗前二次校验持有——逐格比对 PDC 卡片 UUID，
     * 核销后卡片已在 DB 判死，未持有时静默跳过不影响已授予结果）。
     */
    private void consumeOne(Player player, UUID cardUuid) {
        if (!player.isOnline()) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || cardItems.extract(stack).filter(cardUuid::equals).isEmpty()) {
                continue;
            }
            if (stack.getAmount() <= 1) {
                inventory.setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            return;
        }
    }

    /** 目录缓存取称号展示名（含软删行取名兜底；缺失退回 #id）。 */
    private Component titleName(int titleId) {
        return catalogCache.byId(titleId)
                .map(renderer::nameWithBrackets)
                .orElseGet(() -> Component.text("#" + titleId));
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
