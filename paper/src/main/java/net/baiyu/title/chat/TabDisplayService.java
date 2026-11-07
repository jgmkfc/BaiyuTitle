package net.baiyu.title.chat;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.config.PluginConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tab 列表称号展示服务（配置项 {@code title.is-tab} 的唯一消费方）。
 *
 * <p>只写 {@link Player#playerListName(Component)}，<b>不碰头顶</b>：头顶称号
 * 只能用记分板队伍前缀实现，会与任何记分板 / 前缀 / TAB 类插件争抢队伍归属，
 * 冲突面远大于收益，故刻意不实现（配置注释同步说明）。</p>
 *
 * <p>数据来源与聊天前缀完全一致——{@link ChatDisplayService#buildPrefix(UUID)}
 * 读 volatile 缓存快照，<b>零 DB IO</b>；本服务只在主线程执行 Bukkit 操作，
 * 异步回调方统一使用 {@link #requestRefresh(UUID)} / {@link #requestRefreshAll()}
 * 切回主线程，避免裸调 {@code Bukkit} 调度器散落各处。</p>
 *
 * <p>开关关闭时把玩家列表名复位为原版（{@code null} = 默认显示名），
 * 因此运行中把 {@code is-tab} 改回 false 并 reload 也能清除已写入的前缀。</p>
 */
public final class TabDisplayService {

    /** 插件实例（调度器归属）。 */
    private final BaiYuTitle plugin;

    /** 聊天称号前缀构建服务（复用同一套缓存与格式快照）。 */
    private final ChatDisplayService chatDisplayService;

    /** 配置提供器（每次刷新取最新开关，随 reload 生效）。 */
    private final Supplier<PluginConfig> configSupplier;

    /**
     * 构造 Tab 列表称号展示服务。
     *
     * @param plugin             插件实例
     * @param chatDisplayService 聊天称号前缀构建服务
     * @param configSupplier     配置提供器（生产传 {@code configHandler::getConfig}）
     */
    public TabDisplayService(BaiYuTitle plugin, ChatDisplayService chatDisplayService,
                             Supplier<PluginConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.chatDisplayService = Objects.requireNonNull(chatDisplayService, "聊天前缀服务不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "配置提供器不能为空");
    }

    /**
     * 刷新单个玩家的玩家列表名（<b>必须在主线程调用</b>）。
     *
     * <p>开关关闭或玩家已离线时复位为默认名；开关开启时写入
     * 「称号前缀 + 玩家名」，前缀与聊天栏完全同构（含末尾分隔段）。</p>
     *
     * @param player 目标玩家
     */
    public void refresh(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Tab 列表刷新只能在主线程执行");
        }
        if (!player.isOnline()) {
            return;
        }
        if (!configSupplier.get().title.isTab) {
            player.playerListName(null);
            return;
        }
        final Optional<Component> prefix = chatDisplayService.buildPrefix(player.getUniqueId());
        player.playerListName(prefix
                .map(component -> component.append(Component.text(player.getName())))
                .orElseGet(() -> Component.text(player.getName())));
    }

    /**
     * 从任意线程请求刷新单个玩家的玩家列表名（切主线程执行，玩家离线则丢弃）。
     *
     * @param player 目标玩家 UUID
     */
    public void requestRefresh(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            final Player online = Bukkit.getPlayer(player);
            if (online != null) {
                refresh(online);
            }
        });
    }

    /**
     * 从任意线程请求刷新全部在线玩家的玩家列表名（切主线程执行）。
     *
     * <p>用于 reload（lang / 括号段 / 分隔段变化）与到期收敛（称号失效需即时
     * 从 Tab 消失）等整批场景。</p>
     */
    public void requestRefreshAll() {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    /**
     * 刷新全部在线玩家的玩家列表名（<b>必须在主线程调用</b>）。
     */
    public void refreshAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /**
     * 复位全部在线玩家的玩家列表名为默认名（插件停用收尾，避免前缀残留在
     * 客户端 Tab 列表直到重连）。
     */
    public void resetAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.playerListName(null);
        }
    }
}
