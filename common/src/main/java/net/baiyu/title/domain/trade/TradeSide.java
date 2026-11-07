package net.baiyu.title.domain.trade;

import java.util.Locale;

/**
 * 交易方（对应 V4 表 {@code trade_item} 的 side 列）。
 */
public enum TradeSide {

    /** 发起方。 */
    INITIATOR,

    /** 目标方。 */
    TARGET;

    /**
     * 对侧交易方。
     *
     * @return 另一侧
     */
    public TradeSide opposite() {
        return this == INITIATOR ? TARGET : INITIATOR;
    }

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static TradeSide fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("交易方不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的交易方: " + raw);
        }
    }
}
