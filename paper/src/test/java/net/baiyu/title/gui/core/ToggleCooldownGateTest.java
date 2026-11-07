package net.baiyu.title.gui.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装扮切换冷却闸门纯逻辑测试：开关为 0 时恒放行、窗口内二次调用给出剩余时长、
 * 玩家之间互不影响、清理后可立即再次放行。
 *
 * <p>冷却窗口取真实时钟（{@link System#currentTimeMillis()}），因此断言只用
 * "等于 0 / 大于 0 / 不超过窗口"这类不依赖精确时点的判据，不做 sleep。</p>
 */
class ToggleCooldownGateTest {

    @Test
    void zeroCooldownAlwaysAllows() {
        final ToggleCooldownGate gate = new ToggleCooldownGate();
        final UUID player = UUID.randomUUID();

        assertEquals(0L, gate.tryAcquire(player, 0), "冷却关闭时首次应放行");
        assertEquals(0L, gate.tryAcquire(player, 0), "冷却关闭时连续调用仍应放行");
    }

    @Test
    void negativeCooldownTreatedAsDisabled() {
        final ToggleCooldownGate gate = new ToggleCooldownGate();
        final UUID player = UUID.randomUUID();

        assertEquals(0L, gate.tryAcquire(player, -5), "负值等同关闭冷却");
        assertEquals(0L, gate.tryAcquire(player, -5), "负值不记账");
    }

    @Test
    void secondCallWithinWindowReportsRemainingTime() {
        final ToggleCooldownGate gate = new ToggleCooldownGate();
        final UUID player = UUID.randomUUID();

        assertEquals(0L, gate.tryAcquire(player, 1), "首次调用应放行");
        final long remaining = gate.tryAcquire(player, 1);
        assertTrue(remaining > 0L, "窗口内第二次调用应被拦下");
        assertTrue(remaining <= 1000L, "剩余时长不应超过冷却窗口，实际 " + remaining + "ms");
    }

    @Test
    void differentPlayersDoNotShareCooldown() {
        final ToggleCooldownGate gate = new ToggleCooldownGate();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        assertEquals(0L, gate.tryAcquire(first, 10), "甲首次放行");
        assertEquals(0L, gate.tryAcquire(second, 10), "乙不受甲影响");
        assertTrue(gate.tryAcquire(first, 10) > 0L, "甲仍在冷却");
    }

    @Test
    void evictedPlayerCanActImmediately() {
        final ToggleCooldownGate gate = new ToggleCooldownGate();
        final UUID player = UUID.randomUUID();

        assertEquals(0L, gate.tryAcquire(player, 10), "首次放行");
        assertTrue(gate.tryAcquire(player, 10) > 0L, "已进入冷却");

        gate.evict(player);
        assertEquals(0L, gate.tryAcquire(player, 10), "清理后应重新放行");
    }
}
