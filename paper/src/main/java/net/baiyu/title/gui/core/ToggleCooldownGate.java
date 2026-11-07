package net.baiyu.title.gui.core;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装扮切换冷却闸门（配置项 {@code title.toggles-cool-down} 的唯一消费方）。
 *
 * <p>防止玩家连点菜单反复提交装扮变更，把写线程淹没成一串无意义的
 * DB 事务。只在<b>玩家主动变更佩戴状态</b>的入口加闸：装扮菜单栏位开关与
 * 称号选择菜单的"应用"。翻页、筛选、打开详情等纯读操作一律放行。</p>
 *
 * <p>状态是纯内存时间戳表（{@link ConcurrentHashMap}），玩家退出时经
 * {@link #evict(UUID)} 清理，避免长跑累积；插件停用时整体丢弃即可，
 * 不参与持久化，reload 也不重建（冷却窗口跨 reload 保持连续）。</p>
 */
public final class ToggleCooldownGate {

    /** 每玩家上次成功放行的时刻（UTC epoch 毫秒）。 */
    private final Map<UUID, Long> lastActionAt = new ConcurrentHashMap<>();

    /**
     * 尝试占用一次操作额度。
     *
     * <p>冷却秒数 {@code <= 0} 时视为关闭冷却，恒放行且不记账。</p>
     *
     * @param player          玩家 UUID
     * @param cooldownSeconds 冷却窗口（秒）
     * @return {@code 0} 表示放行（已记录本次时刻）；大于 0 表示仍在冷却中，
     *         值为剩余毫秒（调用方向上取整为秒用于提示）
     */
    public long tryAcquire(UUID player, int cooldownSeconds) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        if (cooldownSeconds <= 0) {
            return 0L;
        }
        final long now = System.currentTimeMillis();
        final long windowMillis = cooldownSeconds * 1000L;
        final Long previous = lastActionAt.get(player);
        if (previous != null) {
            final long elapsed = now - previous;
            if (elapsed >= 0 && elapsed < windowMillis) {
                return windowMillis - elapsed;
            }
        }
        lastActionAt.put(player, now);
        return 0L;
    }

    /**
     * 清理某玩家的冷却记录（退出时调用）。
     *
     * @param player 玩家 UUID
     */
    public void evict(UUID player) {
        if (player != null) {
            lastActionAt.remove(player);
        }
    }

    /**
     * 清空全部冷却记录（插件停用收尾）。
     */
    public void clear() {
        lastActionAt.clear();
    }
}
