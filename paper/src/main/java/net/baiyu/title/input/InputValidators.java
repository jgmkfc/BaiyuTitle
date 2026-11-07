package net.baiyu.title.input;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 常用输入校验器工厂：长度（码点）/整数范围/金额/
 * 日期区间/称号 ID 列表，全部纯函数、零 Bukkit 依赖。
 *
 * <p>只解析不落业务：日期区间的"单日期=截止且起始定为当天"（业务设计
 * §1.3.2.6）在解析层实现；写库归一化、窗口生效与否等业务判断
 * 归 common 服务层。解析辅助方法（parseXxx）供 M6 编辑器接线复用，
 * 与校验器共享同一套格式规则，保证"校验过的必可解析"。</p>
 */
public final class InputValidators {

    /** 金额上限（含；防手滑天文数字）。 */
    public static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(1_000_000_000L);

    /** 金额格式：非负整数或最多两位小数（不接受符号、科学计数法）。 */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");

    /** 严格 y-M-d 解析器：月日容许 1~2 位；STRICT 拒绝 2 月 30 日等无效日期。 */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("u-M-d").withResolverStyle(ResolverStyle.STRICT);

    private InputValidators() {
    }

    /**
     * 长度校验（按 Unicode 码点计长——中文、emoji 均按 1 计）。
     *
     * @param min 最小码点数（含）
     * @param max 最大码点数（含）
     * @return 校验器（失败键 {@code input.error.length}，占位 min/max/actual）
     */
    public static InputValidator length(int min, int max) {
        return input -> {
            final int count = input.codePointCount(0, input.length());
            if (count < min || count > max) {
                return Optional.of(InputError.of("input.error.length",
                        "min", String.valueOf(min), "max", String.valueOf(max),
                        "actual", String.valueOf(count)));
            }
            return Optional.empty();
        };
    }

    /**
     * 整数范围校验。
     *
     * @param min 最小值（含）
     * @param max 最大值（含）
     * @return 校验器（失败键 {@code input.error.not-a-number} / {@code input.error.out-of-range}）
     */
    public static InputValidator integerRange(long min, long max) {
        return input -> {
            final Optional<Long> value = parseInteger(input);
            if (value.isEmpty()) {
                return Optional.of(InputError.of("input.error.not-a-number"));
            }
            if (value.get() < min || value.get() > max) {
                return Optional.of(InputError.of("input.error.out-of-range",
                        "min", String.valueOf(min), "max", String.valueOf(max)));
            }
            return Optional.empty();
        };
    }

    /**
     * 金额校验：非负、最多两位小数、不超过 {@link #MAX_AMOUNT}。
     *
     * @return 校验器（失败键 {@code input.error.amount-invalid} / {@code input.error.out-of-range}）
     */
    public static InputValidator amount() {
        return input -> {
            final Optional<BigDecimal> value = parseAmount(input);
            if (value.isEmpty()) {
                return Optional.of(InputError.of("input.error.amount-invalid"));
            }
            if (value.get().compareTo(MAX_AMOUNT) > 0) {
                return Optional.of(InputError.of("input.error.out-of-range",
                        "min", "0", "max", MAX_AMOUNT.toPlainString()));
            }
            return Optional.empty();
        };
    }

    /**
     * 获取窗口日期区间校验：{@code y-m-d|y-m-d} 或单日期（=截止，起始定为当天）。
     * <p>闭区间：起始不得晚于截止（同日合法）。"当天"经供应器逐次取值，
     * 会话跨零点也拿到新日期。</p>
     *
     * @param today 当天日期供应器（生产传 {@code LocalDate::now}，测试注入固定值）
     * @return 校验器（失败键 {@code input.error.date-format} / {@code input.error.date-order}）
     */
    public static InputValidator dateWindow(Supplier<LocalDate> today) {
        Objects.requireNonNull(today, "当天日期供应器不能为空");
        return input -> {
            final Optional<DateWindow> window = parseDateWindow(input, today.get());
            if (window.isEmpty()) {
                return Optional.of(InputError.of("input.error.date-format"));
            }
            if (window.get().start().isAfter(window.get().end())) {
                return Optional.of(InputError.of("input.error.date-order"));
            }
            return Optional.empty();
        };
    }

    /**
     * 称号 ID 列表校验：{@code id|id|id}，每项为正整数，禁止重复。
     *
     * @return 校验器（失败键 {@code input.error.empty} /
     *         {@code input.error.id-list-invalid} / {@code input.error.id-list-duplicate}）
     */
    public static InputValidator titleIdList() {
        return input -> {
            if (input.isBlank()) {
                return Optional.of(InputError.of("input.error.empty"));
            }
            final Optional<List<Integer>> ids = parseTitleIds(input);
            if (ids.isEmpty()) {
                return Optional.of(InputError.of("input.error.id-list-invalid"));
            }
            final Set<Integer> seen = new HashSet<>();
            for (final int id : ids.get()) {
                if (!seen.add(id)) {
                    return Optional.of(InputError.of("input.error.id-list-duplicate",
                            "id", String.valueOf(id)));
                }
            }
            return Optional.empty();
        };
    }

    // ---- 解析辅助（校验器与编辑器接线共用同一套格式规则） ----

    /**
     * 解析整数（前后容许空白）。
     *
     * @param input 输入文本
     * @return 解析值；格式非法为空
     */
    public static Optional<Long> parseInteger(String input) {
        try {
            return Optional.of(Long.parseLong(input.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 解析金额（非负、最多两位小数；不做上限判断）。
     *
     * @param input 输入文本
     * @return 解析值；格式非法为空
     */
    public static Optional<BigDecimal> parseAmount(String input) {
        final String trimmed = input.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(trimmed));
    }

    /**
     * 解析获取窗口：双日期 {@code start|end}，或单日期视为截止、起始定为
     * {@code today}。<b>只做结构与日期合法性解析</b>，
     * 起止先后关系由调用方（校验器）判断。
     *
     * @param input 输入文本
     * @param today 当天日期（单日期输入时充当起始）
     * @return 解析出的闭区间窗口；格式非法为空
     */
    public static Optional<DateWindow> parseDateWindow(String input, LocalDate today) {
        Objects.requireNonNull(today, "当天日期不能为空");
        final String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        final String[] parts = trimmed.split("\\|", -1);
        try {
            if (parts.length == 1) {
                return Optional.of(new DateWindow(today, LocalDate.parse(parts[0].trim(), DATE_FORMAT)));
            }
            if (parts.length == 2) {
                return Optional.of(new DateWindow(
                        LocalDate.parse(parts[0].trim(), DATE_FORMAT),
                        LocalDate.parse(parts[1].trim(), DATE_FORMAT)));
            }
            return Optional.empty();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * 解析称号 ID 列表：{@code |} 分隔的正整数，保序、<b>不去重</b>
     * （重复检测归校验器，便于报出重复项）。
     *
     * @param input 输入文本
     * @return ID 列表（不可变）；任一项非法或输入为空则为空
     */
    public static Optional<List<Integer>> parseTitleIds(String input) {
        final String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        final String[] parts = trimmed.split("\\|", -1);
        final List<Integer> ids = new ArrayList<>(parts.length);
        for (final String part : parts) {
            try {
                final int id = Integer.parseInt(part.trim());
                if (id < 1) {
                    return Optional.empty();
                }
                ids.add(id);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(ids));
    }

    /**
     * 获取窗口闭区间（天粒度）。
     * <p>record 本身不校验起止先后——解析与校验分层，见
     * {@link #parseDateWindow} 与 {@link #dateWindow}。</p>
     *
     * @param start 起始日期（含）
     * @param end   截止日期（含）
     */
    public record DateWindow(LocalDate start, LocalDate end) {

        /** 紧凑构造：起止必填。 */
        public DateWindow {
            Objects.requireNonNull(start, "起始日期不能为空");
            Objects.requireNonNull(end, "截止日期不能为空");
        }
    }
}
