package net.baiyu.title.input;

import net.baiyu.title.service.AdminTitleService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入校验器纯函数测试：长度码点计长（中文 / emoji）、整数范围、
 * 金额格式、日期区间解析边界（单日期=截止且起始定为当天）、
 * 称号 ID 列表非法输入全覆盖。
 */
class InputValidatorsTest {

    /** 聊天输入与持物输入必须识别同一组取消口令。 */
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "CANCEL", "stop", "STOP"})
    void sharedCancelWordsAreCaseInsensitive(String input) {
        assertTrue(InputSessionManager.isCancelInput(input));
    }

    @Test
    void itemCostAmountAcceptsFullInventoryRange() {
        final InputValidator validator = InputValidators.integerRange(
                1, AdminTitleService.MAX_ITEM_COST_AMOUNT);

        assertOk(validator.check("1"));
        assertOk(validator.check("2304"));
        assertError(validator.check("0"), "input.error.out-of-range");
        assertError(validator.check("2305"), "input.error.out-of-range");
    }

    /** 断言校验失败且报错键匹配。 */
    private static InputError assertError(Optional<InputError> result, String expectedKey) {
        assertTrue(result.isPresent(), "应校验失败，实际通过");
        assertEquals(expectedKey, result.get().langKey());
        return result.get();
    }

    /** 断言校验通过。 */
    private static void assertOk(Optional<InputError> result) {
        assertTrue(result.isEmpty(), () -> "应校验通过，实际报错: " + result.map(InputError::langKey).orElse(""));
    }

    /** 长度校验：按 Unicode 码点计长。 */
    @Nested
    class Length {

        /** 中文按码点计 1：四汉字通过 1~4，五汉字超限并回报实际码点数。 */
        @Test
        void chineseCountsByCodePoint() {
            final InputValidator validator = InputValidators.length(1, 4);
            assertOk(validator.check("称号系统"));
            final InputError error = assertError(validator.check("称号系统五"), "input.error.length");
            assertEquals("5", error.placeholders().get("actual"));
            assertEquals("1", error.placeholders().get("min"));
            assertEquals("4", error.placeholders().get("max"));
        }

        /** emoji（代理对，char 长 2）按码点计 1。 */
        @Test
        void emojiCountsAsOneCodePoint() {
            final InputValidator validator = InputValidators.length(1, 2);
            assertOk(validator.check("😀😀"));       // 4 char / 2 码点
            assertError(validator.check("😀😀😀"), "input.error.length"); // 3 码点
        }

        /** 边界：恰为 min / max 通过；空串低于 min=1 失败。 */
        @Test
        void boundaries() {
            final InputValidator validator = InputValidators.length(2, 3);
            assertOk(validator.check("ab"));
            assertOk(validator.check("abc"));
            assertError(validator.check("a"), "input.error.length");
            assertError(validator.check(""), "input.error.length");
            assertError(validator.check("abcd"), "input.error.length");
        }
    }

    /** 整数范围校验。 */
    @Nested
    class IntegerRange {

        /** 合法：边界值含端点；容许前后空白与负数区间。 */
        @Test
        void acceptsBoundariesAndNegatives() {
            final InputValidator validator = InputValidators.integerRange(-5, 10);
            assertOk(validator.check("-5"));
            assertOk(validator.check("10"));
            assertOk(validator.check("0"));
            assertOk(validator.check(" 7 "));
        }

        /** 非整数输入一律 not-a-number（小数 / 文本 / 空串 / 超 long 溢出）。 */
        @ParameterizedTest
        @ValueSource(strings = {"abc", "1.5", "", "99999999999999999999", "1|2"})
        void rejectsNonNumbers(String input) {
            assertError(InputValidators.integerRange(0, 100).check(input), "input.error.not-a-number");
        }

        /** 越界：低于 min / 高于 max 回报区间占位符。 */
        @Test
        void rejectsOutOfRange() {
            final InputValidator validator = InputValidators.integerRange(0, 36500);
            final InputError error = assertError(validator.check("-1"), "input.error.out-of-range");
            assertEquals("0", error.placeholders().get("min"));
            assertEquals("36500", error.placeholders().get("max"));
            assertError(validator.check("36501"), "input.error.out-of-range");
        }
    }

    /** 金额校验：非负、最多两位小数。 */
    @Nested
    class Amount {

        /** 合法格式：整数、一位、两位小数。 */
        @ParameterizedTest
        @ValueSource(strings = {"0", "10", "10.5", "10.55", " 3.20 "})
        void acceptsValidAmounts(String input) {
            assertOk(InputValidators.amount().check(input));
        }

        /** 非法格式：负数、三位小数、科学计数、缺整数位、尾点、空串。 */
        @ParameterizedTest
        @ValueSource(strings = {"-1", "10.555", "1e5", ".5", "1.", "", "abc", "+3"})
        void rejectsInvalidAmounts(String input) {
            assertError(InputValidators.amount().check(input), "input.error.amount-invalid");
        }

        /** 超上限走 out-of-range；恰为上限通过。 */
        @Test
        void capBoundary() {
            assertOk(InputValidators.amount().check("1000000000"));
            assertError(InputValidators.amount().check("1000000001"), "input.error.out-of-range");
        }

        /** 解析辅助：保留两位小数值。 */
        @Test
        void parseAmountValue() {
            assertEquals(Optional.of(new BigDecimal("10.55")), InputValidators.parseAmount("10.55"));
            assertEquals(Optional.empty(), InputValidators.parseAmount("10.555"));
        }
    }

    /** 日期区间校验与解析。 */
    @Nested
    class DateWindow {

        /** 固定"当天"（供应器注入，纯函数可测）。 */
        private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);

        private final InputValidator validator = InputValidators.dateWindow(() -> TODAY);

        /** 双日期区间：起止合法即通过；同日（闭区间单日窗口）合法。 */
        @Test
        void acceptsPairAndSameDay() {
            assertOk(validator.check("2026-09-29|2026-10-31"));
            assertOk(validator.check("2026-09-29|2026-09-29"));
            assertOk(validator.check(" 2026-9-2 | 2026-10-1 ")); // 月日一位 + 空白容错
        }

        /** 单日期=截止、起始定为当天。 */
        @Test
        void singleDateMeansDeadlineFromToday() {
            assertOk(validator.check("2026-12-31"));
            assertEquals(
                    Optional.of(new InputValidators.DateWindow(TODAY, LocalDate.of(2026, 12, 31))),
                    InputValidators.parseDateWindow("2026-12-31", TODAY));
            // 截止恰为当天：闭区间合法
            assertOk(validator.check("2026-7-27"));
            // 截止早于当天：区间为空 → date-order
            assertError(validator.check("2026-07-26"), "input.error.date-order");
        }

        /** 起始晚于截止 → date-order。 */
        @Test
        void rejectsReversedRange() {
            assertError(validator.check("2026-10-31|2026-09-29"), "input.error.date-order");
        }

        /** 格式错误：非法日期（STRICT 拒绝 2 月 30 / 平年 2-29）、结构错误。 */
        @ParameterizedTest
        @ValueSource(strings = {
                "2026-02-30", "2026-02-29", "2026-13-01", "abc",
                "2026-09-29|", "|2026-09-29", "2026-09-29|2026-10-01|2026-11-01",
                "2026/09/29", ""})
        void rejectsMalformed(String input) {
            assertError(validator.check(input), "input.error.date-format");
        }

        /** 闰年 2-29 合法（2028 为闰年）。 */
        @Test
        void acceptsLeapDay() {
            assertOk(validator.check("2028-02-29"));
        }

        /** 解析辅助：双日期区间不校验先后（分层归校验器）。 */
        @Test
        void parseKeepsReversedPair() {
            assertEquals(
                    Optional.of(new InputValidators.DateWindow(
                            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 9, 29))),
                    InputValidators.parseDateWindow("2026-10-31|2026-09-29", TODAY));
        }
    }

    /** 称号 ID 列表校验与解析。 */
    @Nested
    class TitleIdList {

        private final InputValidator validator = InputValidators.titleIdList();

        /** 合法：单个 / 多个 / 分隔符旁空白。 */
        @Test
        void acceptsValidLists() {
            assertOk(validator.check("5"));
            assertOk(validator.check("1|2|3"));
            assertOk(validator.check(" 1 | 2 | 30 "));
        }

        /** 空输入 → empty。 */
        @Test
        void rejectsBlank() {
            assertError(validator.check(""), "input.error.empty");
            assertError(validator.check("   "), "input.error.empty");
        }

        /** 非法项：零 / 负数 / 非数字 / 空项 / 小数。 */
        @ParameterizedTest
        @ValueSource(strings = {"0", "-3", "a", "1||2", "1|2|", "|1", "1.5", "1|x|3"})
        void rejectsInvalidTokens(String input) {
            assertError(validator.check(input), "input.error.id-list-invalid");
        }

        /** 重复项：报出重复的 ID。 */
        @Test
        void rejectsDuplicates() {
            final InputError error = assertError(validator.check("1|2|1"), "input.error.id-list-duplicate");
            assertEquals("1", error.placeholders().get("id"));
        }

        /** 解析辅助：保序不去重（去重检测归校验器）。 */
        @Test
        void parseKeepsOrderAndDuplicates() {
            assertEquals(Optional.of(List.of(3, 1, 2)), InputValidators.parseTitleIds("3|1|2"));
            assertEquals(Optional.of(List.of(1, 1)), InputValidators.parseTitleIds("1|1"));
            assertEquals(Optional.empty(), InputValidators.parseTitleIds("1|0"));
        }
    }
}
