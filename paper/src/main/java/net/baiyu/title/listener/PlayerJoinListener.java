package net.baiyu.title.listener;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.chat.TabDisplayService;
import net.baiyu.title.service.PlayerLifecycleService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

/**
 * 玩家登录监听器（Join @LOW）。
 *
 * <p>只做转发：触发 {@link PlayerLifecycleService#handleJoin} 的异步预载链
 * 并挂失败日志，事件线程零阻塞、零 JDBC。LOW 优先级让预载尽早入队，
 * 缩短聊天路径读不到快照（降级默认称号）的窗口。</p>
 *
 * <p>预载成功后（无论成败）请求刷新一次 Tab 列表名：失败时读到的是隐式默认
 * 称号，与聊天栏降级行为一致（{@code title.is-tab} 关闭时该请求为空操作）。</p>
 */
public final class PlayerJoinListener implements Listener {

    /** 插件实例（失败日志）。 */
    private final BaiYuTitle plugin;

    /** 玩家生命周期服务。 */
    private final PlayerLifecycleService lifecycleService;

    /** Tab 列表称号展示服务。 */
    private final TabDisplayService tabDisplayService;

    /**
     * 构造玩家登录监听器。
     *
     * @param plugin             插件实例
     * @param lifecycleService   玩家生命周期服务
     * @param tabDisplayService  Tab 列表称号展示服务
     */
    public PlayerJoinListener(BaiYuTitle plugin, PlayerLifecycleService lifecycleService,
                              TabDisplayService tabDisplayService) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "玩家生命周期服务不能为空");
        this.tabDisplayService = Objects.requireNonNull(tabDisplayService, "Tab 展示服务不能为空");
    }

    /**
     * 处理登录事件：触发档案预载，完成后刷新 Tab 列表名。
     *
     * @param event 登录事件
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lifecycleService.handleJoin(player).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getComponentLogger().warn(
                        "玩家 {} 的档案预载失败，聊天称号将暂以默认称号展示（重进可重试）。",
                        player.getName(), failure);
            }
            // 预载链在 DB 线程收尾：切主线程写玩家列表名（缓存为隐式默认时不报错）。
            tabDisplayService.requestRefresh(player.getUniqueId());
        });
    }
}
