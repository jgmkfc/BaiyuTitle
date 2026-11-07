package net.baiyu.title.domain.order;

import java.util.Locale;

/**
 * 订单种类（对应 V3 表 {@code purchase_order} 的 kind 列）。
 */
public enum OrderKind {

    /** 商店购买订单。 */
    SHOP,

    /** 自定义称号订单（履约时先建称号再授予，回填 title_id）。 */
    CUSTOM;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static OrderKind fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("订单种类不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的订单种类: " + raw);
        }
    }
}
