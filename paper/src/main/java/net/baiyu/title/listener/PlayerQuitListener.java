package net.baiyu.title.listener;

import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.gui.core.ToggleCooldownGate;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.service.PlayerLifecycleService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * 玩家退出监听器（Quit @MONITOR）。
 *
 * <p>MONITOR 优先级：在所有插件处理完退出后收尾，只做状态清理不改事件——
 * 先行政取消输入会话（onCancel 回调仍可安全执行），再立即失效 GUI 会话
 * （导航栈 / 暂存随人走），清掉装扮切换冷却记录，最后调度档案延迟驱逐。</p>
 */
public final class PlayerQuitListener implements Listener {

    /** 玩家生命周期服务。 */
    private final PlayerLifecycleService lifecycleService;

    /** GUI 会话管理器。 */
    private final GuiSessionManager sessionManager;

    /** 输入会话管理器。 */
    private final InputSessionManager inputSessionManager;

    /** 装扮切换冷却闸门（退出时清理时间戳，避免长跑累积）。 */
    private final ToggleCooldownGate cooldownGate;

    /**
     * 构造玩家退出监听器。
     *
     * @param lifecycleService    玩家生命周期服务
     * @param sessionManager      GUI 会话管理器
     * @param inputSessionManager 输入会话管理器
     * @param cooldownGate        装扮切换冷却闸门
     */
    public PlayerQuitListener(PlayerLifecycleService lifecycleService, GuiSessionManager sessionManager,
                              InputSessionManager inputSessionManager, ToggleCooldownGate cooldownGate) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "玩家生命周期服务不能为空");
        this.sessionManager = Objects.requireNonNull(sessionManager, "GUI 会话管理器不能为空");
        this.inputSessionManager = Objects.requireNonNull(inputSessionManager, "输入会话管理器不能为空");
        this.cooldownGate = Objects.requireNonNull(cooldownGate, "切换冷却闸门不能为空");
    }

    /**
     * 处理退出事件：取消输入会话 → 清 GUI 会话 → 清冷却记录 → 调度延迟驱逐。
     *
     * @param event 退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        inputSessionManager.cancel(event.getPlayer().getUniqueId());
        sessionManager.invalidate(event.getPlayer().getUniqueId());
        cooldownGate.evict(event.getPlayer().getUniqueId());
        lifecycleService.handleQuit(event.getPlayer());
    }
}
