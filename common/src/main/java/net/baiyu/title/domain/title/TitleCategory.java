package net.baiyu.title.domain.title;

import java.util.Locale;

/**
 * 称号种类。
 */
public enum TitleCategory {

    /** 普通称号：0..4 条购买条件，条件空集即免费称号。 */
    NORMAL,

    /** 组称号：不走条件表，佩戴资格 = 有效组成员。 */
    GROUP,

    /** 羁绊称号：条件在羁绊需求表，逻辑恒 AND，授予恒永久。 */
    BOND,

    /** 自定义称号：条件空集，付款走 config 四种价格并经订单结算。 */
    CUSTOM;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static TitleCategory fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("称号种类不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的称号种类: " + raw);
        }
    }
}
