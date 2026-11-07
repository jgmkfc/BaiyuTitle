package net.baiyu.title.service;

import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * 羁绊获得通知钩子（paper 侧）：{@code BondService} 的 downstream——
 * 仅在羁绊称号自动授予提交后被回调（含级联与回溯补发）。
 *
 * <p>线程约定：回调发生在写线程（commit 之后），本类只做入队切主线程；
 * 主线程侧玩家在线才发 lang 消息（离线静默，不做补发——羁绊授予本身
 * 已落库，下次登录预载即可见）。插件停用窗口内丢弃，不再入队。</p>
 */
public final class BondNotifyHook implements OwnershipService.PostGrantHook {

    /** 插件实例（主线程调度归属与启用判定）。 */
    private final BaiYuTitle plugin;

    /** 语言门面。 */
    private final Lang lang;

    /** 称号目录缓存（羁绊称号取名，零 DB IO）。 */
    private final TitleCatalogCache catalogCache;

    /**
     * 构造羁绊获得通知钩子。
     *
     * @param plugin       插件实例
     * @param lang         语言门面
     * @param catalogCache 称号目录缓存
     */
    public BondNotifyHook(BaiYuTitle plugin, Lang lang, TitleCatalogCache catalogCache) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
    }

    /**
     * 羁绊授予提交后回调（写线程）：切主线程后通知在线玩家。
     *
     * @param owner   持有者 UUID
     * @param titleId 羁绊称号 id
     * @param source  授予来源（本钩子只挂在羁绊下游，恒为 BOND，签名透传）
     */
    @Override
    public void onGranted(UUID owner, int titleId, GrantSource source) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> notifyOnMain(owner, titleId));
    }

    /**
     * 主线程通知主体：玩家在线才发消息。
     */
    private void notifyOnMain(UUID owner, int titleId) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            return;
        }
        Component name = catalogCache.byId(titleId)
                .map(title -> ColorParser.of(title.name()).build())
                .orElseGet(() -> Component.text("#" + titleId));
        player.sendMessage(lang.component("prefix")
                .append(lang.component("bond.granted", Placeholder.component("name", name))));
    }
}
