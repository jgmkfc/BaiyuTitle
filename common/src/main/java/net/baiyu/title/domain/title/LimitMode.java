package net.baiyu.title.domain.title;

import java.util.Locale;

/**
 * 获取限制模式。
 */
public enum LimitMode {

    /** 无限制。 */
    NONE,

    /** 时间窗口限制：闭区间日期窗口内才可获取。 */
    WINDOW,

    /** 总数限制：按购买份数计的售卖上限。 */
    TOTAL;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static LimitMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("获取限制模式不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的获取限制模式: " + raw);
        }
    }
}
