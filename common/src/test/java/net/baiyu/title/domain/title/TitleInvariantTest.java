package net.baiyu.title.domain.title;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Title} 领域不变量单测。
 */
class TitleInvariantTest {

    /** 基础构建器（NORMAL、无限制、必填字段就绪）。 */
    private static TitleBuilder base() {
        return Title.builder()
                .name("<white>测试称号")
                .namePlain("测试称号")
                .createdAt(1L)
                .updatedAt(1L);
    }

    /** 不变量 2：每条件类型至多一条，重复类型抛错。 */
    @Test
    void duplicateConditionType_throws() {
        List<net.baiyu.title.domain.condition.PurchaseCondition> duplicated =
                List.of(new CoinCondition(10), new CoinCondition(20));
        assertThrows(IllegalArgumentException.class,
                () -> base().conditions(duplicated).build());
        // 不同类型各一条合法
        Title ok = base().conditions(List.of(
                new CoinCondition(10),
                new VaultCondition(new BigDecimal("9.50")),
                new PermissionCondition("baiyutitle.vip"))).build();
        assertEquals(3, ok.conditions().size());
    }

    /** 不变量 3：NONE 模式静默清空窗口与总数字段。 */
    @Test
    void limitModeNone_clearsWindowAndCap() {
        Title title = base()
                .limitMode(LimitMode.NONE)
                .windowStart(LocalDate.of(2026, 7, 1))
                .windowEnd(LocalDate.of(2026, 7, 10))
                .totalCap(5)
                .build();
        assertNull(title.windowStart());
        assertNull(title.windowEnd());
        assertNull(title.totalCap());
    }

    /** 不变量 3：WINDOW 模式必须有截止日，且清空总数上限。 */
    @Test
    void limitModeWindow_requiresEndAndClearsCap() {
        assertThrows(IllegalArgumentException.class,
                () -> base().limitMode(LimitMode.WINDOW).build());
        assertThrows(IllegalArgumentException.class, () -> base()
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.of(2026, 7, 10))
                .windowEnd(LocalDate.of(2026, 7, 1))
                .build());
        Title title = base()
                .limitMode(LimitMode.WINDOW)
                .windowEnd(LocalDate.of(2026, 7, 10))
                .totalCap(99)
                .build();
        assertNull(title.totalCap());
        assertNull(title.windowStart());
        assertEquals(LocalDate.of(2026, 7, 10), title.windowEnd());
    }

    /** 不变量 3：TOTAL 模式必须 totalCap ≥ 1，且清空窗口字段。 */
    @Test
    void limitModeTotal_requiresCapAndClearsWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> base().limitMode(LimitMode.TOTAL).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().limitMode(LimitMode.TOTAL).totalCap(0).build());
        Title title = base()
                .limitMode(LimitMode.TOTAL)
                .totalCap(3)
                .windowStart(LocalDate.of(2026, 7, 1))
                .windowEnd(LocalDate.of(2026, 7, 10))
                .build();
        assertEquals(3, title.totalCap());
        assertNull(title.windowStart());
        assertNull(title.windowEnd());
    }

    /** 不变量 5：BOND/GROUP/CUSTOM 强制不可交易（静默覆写）。 */
    @Test
    void nonNormalCategories_forceUntradable() {
        for (TitleCategory category : new TitleCategory[]{
                TitleCategory.BOND, TitleCategory.GROUP, TitleCategory.CUSTOM}) {
            Title title = base().category(category).tradable(true).build();
            assertFalse(title.tradable(), category + " 必须强制不可交易");
        }
        assertTrue(base().category(TitleCategory.NORMAL).tradable(true).build().tradable());
    }

    /** 不变量 6：羁绊需求包含自身抛错（已落库 id > 0 时）。 */
    @Test
    void bondRequirements_containingSelf_throws() {
        assertThrows(IllegalArgumentException.class, () -> base()
                .id(5)
                .category(TitleCategory.BOND)
                .bondRequirements(Set.of(3, 5))
                .build());
        // 不含自身合法
        Title ok = base().id(5).category(TitleCategory.BOND).bondRequirements(Set.of(3, 4)).build();
        assertEquals(Set.of(3, 4), ok.bondRequirements());
    }

    /** 条件/羁绊需求只属于对应种类：其余种类静默清空。 */
    @Test
    void categoryScopedCollections_silentlyCleared() {
        Title bond = base()
                .id(9)
                .category(TitleCategory.BOND)
                .conditions(List.of(new CoinCondition(10)))
                .build();
        assertTrue(bond.conditions().isEmpty(), "BOND 不使用条件表");

        Title normal = base().bondRequirements(Set.of(1, 2)).build();
        assertTrue(normal.bondRequirements().isEmpty(), "NORMAL 不使用羁绊需求表");
    }

    /** 裁决 2：获取窗口闭区间，contains 含两端点。 */
    @Test
    void acquireWindow_closedIntervalContainsEndpoints() {
        AcquireWindow window = AcquireWindow.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10));
        assertTrue(window.contains(LocalDate.of(2026, 7, 1)), "起始端点当天可获取");
        assertTrue(window.contains(LocalDate.of(2026, 7, 10)), "截止端点当天可获取");
        assertTrue(window.contains(LocalDate.of(2026, 7, 5)));
        assertFalse(window.contains(LocalDate.of(2026, 6, 30)));
        assertFalse(window.contains(LocalDate.of(2026, 7, 11)));

        // 只有截止日：无下界
        AcquireWindow deadlineOnly = AcquireWindow.deadlineOnly(LocalDate.of(2026, 7, 10));
        assertTrue(deadlineOnly.contains(LocalDate.of(2000, 1, 1)));
        assertTrue(deadlineOnly.contains(LocalDate.of(2026, 7, 10)));
        assertFalse(deadlineOnly.contains(LocalDate.of(2026, 7, 11)));
    }

    /** Title.isWithinWindow 与窗口值对象口径一致（非 WINDOW 恒可获取）。 */
    @Test
    void isWithinWindow_delegatesToWindow() {
        Title windowed = base()
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.of(2026, 7, 1))
                .windowEnd(LocalDate.of(2026, 7, 10))
                .build();
        assertTrue(windowed.isWithinWindow(LocalDate.of(2026, 7, 1)));
        assertTrue(windowed.isWithinWindow(LocalDate.of(2026, 7, 10)));
        assertFalse(windowed.isWithinWindow(LocalDate.of(2026, 7, 11)));

        Title unlimited = base().build();
        assertTrue(unlimited.isWithinWindow(LocalDate.of(1999, 1, 1)));
    }
}
