package net.baiyu.title.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.baiyu.title.config.ConfigHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Listener;

import java.util.Objects;
import java.util.Optional;

/**
 * 聊天称号监听器：插件唯一的聊天事件监听。
 *
 * <p>注册方式：不用注解——优先级取 config {@code chat.listener-priority}
 * （默认 MONITOR，改动需重启），由 ListenerHandler 以显式
 * {@code registerEvent(..., ignoreCancelled=true)} 注册。</p>
 *
 * <p>渲染策略：<b>包装</b>既有 {@code e.renderer()} 而非整体替换——前缀
 * 组件在事件处理时构建一次（随机栏位取样一次，同消息全观察者一致），
 * 渲染时逐观察者委托其他插件最终确定的渲染器再前置前缀，与
 * EssentialsXChat 等聊天格式化插件共存。</p>
 *
 * <p>线程纪律：AsyncChatEvent 在异步线程触发；本路径零 DB IO、
 * 零 Bukkit 状态修改，只读 volatile 缓存快照与配置字段。</p>
 */
public final class ChatListener implements Listener {

    /** 配置处理器（聊天展示总开关）。 */
    private final ConfigHandler configHandler;

    /** 聊天称号前缀构建服务。 */
    private final ChatDisplayService chatDisplayService;

    /**
     * 构造聊天称号监听器。
     *
     * @param configHandler      配置处理器
     * @param chatDisplayService 聊天称号前缀构建服务
     */
    public ChatListener(ConfigHandler configHandler, ChatDisplayService chatDisplayService) {
        this.configHandler = Objects.requireNonNull(configHandler, "配置处理器不能为空");
        this.chatDisplayService = Objects.requireNonNull(chatDisplayService, "前缀构建服务不能为空");
    }

    /**
     * 处理聊天事件：构建一次前缀并包装渲染器。
     * <p>无注解：由 ListenerHandler 按配置优先级显式注册。</p>
     *
     * @param event 聊天事件
     */
    public void onChat(AsyncChatEvent event) {
        if (!configHandler.getConfig().title.isChat) {
            return;
        }
        Optional<Component> maybePrefix =
                chatDisplayService.buildPrefix(event.getPlayer().getUniqueId());
        if (maybePrefix.isEmpty()) {
            return;
        }
        Component prefix = maybePrefix.get();
        ChatRenderer delegate = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) ->
                prefix.append(delegate.render(source, sourceDisplayName, message, viewer)));
    }
}
