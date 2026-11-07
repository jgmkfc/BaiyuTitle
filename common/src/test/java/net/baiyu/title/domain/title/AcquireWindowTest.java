package net.baiyu.title.domain.title;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 获取窗口边界单测（日期解析项：闭区间端点含）。
 */
class AcquireWindowTest {

    /** 完整窗口闭区间：两端点含、区间外两侧均不含。 */
    @Test
    void fullWindow_closedIntervalEndpoints() {
        AcquireWindow window = AcquireWindow.of(
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20));

        assertTrue(window.contains(LocalDate.of(2026, 7, 10)), "起始端点应含");
        assertTrue(window.contains(LocalDate.of(2026, 7, 20)), "截止端点应含");
        assertTrue(window.contains(LocalDate.of(2026, 7, 15)));
        assertFalse(window.contains(LocalDate.of(2026, 7, 9)), "起始前一天不应含");
        assertFalse(window.contains(LocalDate.of(2026, 7, 21)), "截止后一天不应含");
    }

    /** 单日窗口（起 = 止）：仅当天命中。 */
    @Test
    void singleDayWindow_onlyThatDay() {
        LocalDate day = LocalDate.of(2026, 7, 27);
        AcquireWindow window = AcquireWindow.of(day, day);

        assertTrue(window.contains(day));
        assertFalse(window.contains(day.minusDays(1)));
        assertFalse(window.contains(day.plusDays(1)));
    }

    /** 只有截止日（§1.5.2 单日期语义）：无下界，截止端点含。 */
    @Test
    void deadlineOnly_noLowerBound() {
        AcquireWindow window = AcquireWindow.deadlineOnly(LocalDate.of(2026, 12, 31));

        assertFalse(window.hasStart());
        assertTrue(window.contains(LocalDate.of(2020, 1, 1)), "只有截止日时无下界");
        assertTrue(window.contains(LocalDate.of(2026, 12, 31)));
        assertFalse(window.contains(LocalDate.of(2027, 1, 1)));
    }

    /** 跨年窗口：LocalDate 比较跨年正确（等价校验 CHAR(10) ISO 字符串的字典序假设）。 */
    @Test
    void crossYearWindow_comparesCorrectly() {
        AcquireWindow window = AcquireWindow.of(
                LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 5));

        assertTrue(window.contains(LocalDate.of(2026, 12, 31)));
        assertTrue(window.contains(LocalDate.of(2027, 1, 1)));
        assertFalse(window.contains(LocalDate.of(2026, 12, 24)));
        assertFalse(window.contains(LocalDate.of(2027, 1, 6)));
    }

    /** 不变量：截止日必填；起始晚于截止拒绝。 */
    @Test
    void invariants_rejected() {
        assertThrows(NullPointerException.class, () -> AcquireWindow.of(LocalDate.now(), null));
        assertThrows(IllegalArgumentException.class, () -> AcquireWindow.of(
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 20)));
    }
}
