package net.baiyu.title.service;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.input.AnvilInputRequest;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.input.InputValidators;
import net.baiyu.title.service.redeem.RedeemResult;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/** Shared redeem-code input flow used by commands and menus. */
public final class RedeemCodeFlow {

    private final Plugin plugin;
    private final InputSessionManager inputs;
    private final RedeemService redeemService;
    private final Lang lang;
    private final TitleCatalogCache catalogCache;
    private final TitleItemRenderer renderer;

    public RedeemCodeFlow(Plugin plugin, InputSessionManager inputs,
                          RedeemService redeemService, Lang lang,
                          TitleCatalogCache catalogCache, TitleItemRenderer renderer) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.inputs = Objects.requireNonNull(inputs, "输入会话管理器不能为空");
        this.redeemService = Objects.requireNonNull(redeemService, "兑换服务不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.renderer = Objects.requireNonNull(renderer, "称号物品渲染门面不能为空");
    }

    /** Opens the redeem-code anvil and optionally returns to the originating menu. */
    public void open(Player player, @Nullable MenuSpec returnTo) {
        inputs.openAnvilInput(player, new AnvilInputRequest(
                "menu.redeem.anvil-title",
                InputValidators.length(1, 64),
                code -> redeem(player, code),
                () -> {
                },
                returnTo));
    }

    private void redeem(Player player, String code) {
        redeemService.redeemCode(player.getUniqueId(), player.getName(), code)
                .whenComplete((result, failure) -> runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (failure != null) {
                        final Throwable cause = failure instanceof CompletionException
                                && failure.getCause() != null ? failure.getCause() : failure;
                        if (cause instanceof IllegalArgumentException invalid) {
                            send(player, "command.redeem.failed",
                                    Placeholder.unparsed("reason", String.valueOf(invalid.getMessage())));
                            return;
                        }
                        plugin.getComponentLogger().warn(
                                "兑换码兑换失败（玩家 {}）", player.getName(), cause);
                        send(player, "command.error.internal");
                        return;
                    }
                    switch (result) {
                        case RedeemResult.Redeemed redeemed -> send(player, "redeem.success",
                                Placeholder.component("name", titleName(redeemed.titleId())));
                        case RedeemResult.Rejected rejected -> send(player, "redeem.reject."
                                + rejected.reason().name().toLowerCase(Locale.ROOT)
                                .replace('_', '-'));
                    }
                }));
    }

    private Component titleName(int titleId) {
        return catalogCache.byId(titleId)
                .map(renderer::nameWithBrackets)
                .orElseGet(() -> Component.text("#" + titleId));
    }

    private void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
    }

    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
