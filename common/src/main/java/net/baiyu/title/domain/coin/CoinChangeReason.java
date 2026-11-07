package net.baiyu.title.domain.coin;

import java.util.Locale;

/**
 * 称号币变动原因（对应 V3 表 {@code coin_log} 的 reason 列）。
 * <p>每次余额变动必有一条日志（同事务，不变量 §十 9）。
 */
public enum CoinChangeReason {

    /** 管理员增发。 */
    ADMIN_GIVE,

    /** 管理员直接设置余额。 */
    ADMIN_SET,

    /** 管理员扣减。 */
    ADMIN_TAKE,

    /** 商店购买扣款。 */
    PURCHASE,

    /** 订单失败退款。 */
    REFUND,

    /** 自定义称号付款。 */
    CUSTOM_TITLE;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static CoinChangeReason fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("称号币变动原因不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的称号币变动原因: " + raw);
        }
    }
}
