package net.baiyu.title.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.chat.ChatListener;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.input.InputSessionManager;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.PluginManager;

import java.util.Locale;
import java.util.Objects;

/**
 * 监听器注册器：集中注册插件全部事件监听。
 *
 * <p>Join/Quit 监听走注解注册（优先级固定 LOW / MONITOR）；聊天渲染监听
 * 优先级取 config {@code chat.listener-priority}（默认 MONITOR，非法值回退并告警），
 * 以显式 {@code registerEvent(..., ignoreCancelled=true)} 注册；输入会话
 * 管理器（聊天拦截 @LOWEST）与其铁砧子处理器走注解注册。</p>
 *
 * <p>生命周期：{@link #register()} 仅在主线程 onEnable 时调用一次；
 * reload 白名单不重建监听器（CommandAPI 同源约束），优先级改动需重启。</p>
 */
public final class ListenerHandler {

    /** 聊天监听优先级的默认值 / 非法配置回退值。 */
    private static final EventPriority DEFAULT_CHAT_PRIORITY = EventPriority.MONITOR;

    /** 插件实例。 */
    private final BaiYuTitle plugin;

    /** 配置处理器（读取聊天监听优先级）。 */
    private final ConfigHandler configHandler;

    /** 聊天称号监听器。 */
    private final ChatListener chatListener;

    /** 玩家登录监听器。 */
    private final PlayerJoinListener joinListener;

    /** 玩家退出监听器。 */
    private final PlayerQuitListener quitListener;

    /** 输入会话管理器（聊天拦截 + 铁砧子处理器）。 */
    private final InputSessionManager inputSessionManager;

    /** 称号卡右键兑换监听器。 */
    private final RedeemCardListener redeemCardListener;

    /**
     * 构造监听器注册器。
     *
     * @param plugin              插件实例
     * @param configHandler       配置处理器
     * @param chatListener        聊天称号监听器
     * @param joinListener        玩家登录监听器
     * @param quitListener        玩家退出监听器
     * @param inputSessionManager 输入会话管理器
     * @param redeemCardListener  称号卡右键兑换监听器
     */
    public ListenerHandler(BaiYuTitle plugin, ConfigHandler configHandler, ChatListener chatListener,
                           PlayerJoinListener joinListener, PlayerQuitListener quitListener,
                           InputSessionManager inputSessionManager,
                           RedeemCardListener redeemCardListener) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.configHandler = Objects.requireNonNull(configHandler, "配置处理器不能为空");
        this.chatListener = Objects.requireNonNull(chatListener, "聊天称号监听器不能为空");
        this.joinListener = Objects.requireNonNull(joinListener, "玩家登录监听器不能为空");
        this.quitListener = Objects.requireNonNull(quitListener, "玩家退出监听器不能为空");
        this.inputSessionManager = Objects.requireNonNull(inputSessionManager, "输入会话管理器不能为空");
        this.redeemCardListener = Objects.requireNonNull(redeemCardListener, "称号卡兑换监听器不能为空");
    }

    /**
     * 注册全部监听器（主线程 onEnable 调用一次；随插件停用自动注销）。
     */
    public void register() {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(joinListener, plugin);
        pluginManager.registerEvents(quitListener, plugin);
        pluginManager.registerEvents(inputSessionManager, plugin);
        pluginManager.registerEvents(inputSessionManager.anvilHandler(), plugin);
        pluginManager.registerEvents(inputSessionManager.heldItemHandler(), plugin);
        pluginManager.registerEvents(redeemCardListener, plugin);
        pluginManager.registerEvent(AsyncChatEvent.class, chatListener, chatPriority(),
                (listener, event) -> {
                    if (event instanceof AsyncChatEvent chatEvent) {
                        ((ChatListener) listener).onChat(chatEvent);
                    }
                },
                plugin, true);
    }

    /**
     * 解析聊天监听优先级：config {@code chat.listener-priority} 大小写不敏感；
     * 非法值回退 {@link #DEFAULT_CHAT_PRIORITY} 并打控制台告警。
     */
    private EventPriority chatPriority() {
        String configured = configHandler.getConfig().chat.listenerPriority;
        try {
            return EventPriority.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            plugin.getComponentLogger().warn(
                    "config.yml 的 chat.listener-priority 配置值 [{}] 非法，已回退 {}。",
                    configured, DEFAULT_CHAT_PRIORITY);
            return DEFAULT_CHAT_PRIORITY;
        }
    }
}
