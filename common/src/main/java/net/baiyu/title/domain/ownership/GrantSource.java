package net.baiyu.title.domain.ownership;

import java.util.Locale;

/**
 * 所有权授予来源（对应 V2 表 {@code title_ownership} 的 source 列）。
 */
public enum GrantSource {

    /** 商店购买（source_ref = 订单 id）。 */
    PURCHASE,

    /** 管理员授予（无视窗口/总数，不产生订单）。 */
    ADMIN,

    /** 交易转入（source_ref = 交易 id，复制原到期时刻）。 */
    TRADE,

    /** 羁绊集齐自动授予（恒永久）。 */
    BOND,

    /** 兑换码兑换。 */
    REDEEM_CODE,

    /** 称号卡核销。 */
    REDEEM_CARD,

    /** 组成员资格物化授予。 */
    GROUP,

    /** 自定义称号订单履约。 */
    CUSTOM;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static GrantSource fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("授予来源不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的授予来源: " + raw);
        }
    }
}
