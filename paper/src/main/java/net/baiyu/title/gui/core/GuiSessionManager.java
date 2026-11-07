package net.baiyu.title.gui.core;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GUI 会话管理器：会话创建 / 获取 / 失效 / 超时清扫。
 *
 * <p>清扫策略：{@link #sweep()} 由 TaskScheduler 以
 * {@link #SWEEP_INTERVAL}（5 分钟）为周期的<b>主线程同步任务</b>驱动
 * （本阶段只提供方法，注册在组合根改造阶段接线）；空闲超过
 * {@link #IDLE_TIMEOUT}（15 分钟）且无打开菜单、无输入会话的会话被移除。</p>
 *
 * <p>线程纪律：所有方法仅主线程调用（GUI 事件、路由、退出监听、同步清扫），
 * 内部用普通 HashMap，不做同步。</p>
 */
public final class GuiSessionManager {

    /** 建议的清扫周期（TaskScheduler 注册用）。 */
    public static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    /** 空闲超时阈值：超过且无菜单无输入 → 移除。 */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);

    /** 会话表（键 = 玩家 UUID）。 */
    private final Map<UUID, GuiSession> sessions = new HashMap<>();

    /**
     * 取玩家会话，不存在则新建；同时刷新活跃时间。
     *
     * @param player 玩家
     * @return 会话（永不为 null）
     */
    public GuiSession get(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        final GuiSession session = sessions.computeIfAbsent(player.getUniqueId(), id -> new GuiSession());
        session.touch();
        return session;
    }

    /**
     * 只读窥视（不创建、不刷新活跃时间）。
     *
     * @param uuid 玩家 UUID
     * @return 会话；不存在时为 null
     */
    public @Nullable GuiSession peek(UUID uuid) {
        return sessions.get(Objects.requireNonNull(uuid, "玩家 UUID 不能为空"));
    }

    /**
     * 立即失效并移除某玩家的会话（玩家退出监听调用）。
     *
     * @param uuid 玩家 UUID
     */
    public void invalidate(UUID uuid) {
        sessions.remove(Objects.requireNonNull(uuid, "玩家 UUID 不能为空"));
    }

    /** 清空全部会话（onDisable / reload 收尾调用；调用前应先 router.closeAll()）。 */
    public void clear() {
        sessions.clear();
    }

    /**
     * 执行一轮空闲清扫（主线程同步任务周期调用）。
     *
     * @return 本轮移除的会话数
     */
    public int sweep() {
        final long now = System.currentTimeMillis();
        final long idleMillis = IDLE_TIMEOUT.toMillis();
        final int before = sessions.size();
        sessions.values().removeIf(session -> session.isIdle(now, idleMillis));
        return before - sessions.size();
    }

    /** @return 当前会话总数（监控 / 测试用） */
    public int size() {
        return sessions.size();
    }
}
