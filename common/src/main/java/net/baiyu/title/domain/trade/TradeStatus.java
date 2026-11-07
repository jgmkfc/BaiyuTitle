package net.baiyu.title.domain.trade;

import java.util.Locale;

/**
 * 交易状态（PENDING 是唯一可迁出状态）。
 */
public enum TradeStatus {

    /** 待处理（唯一可迁出状态）。 */
    PENDING,

    /** 已成交。 */
    COMPLETED,

    /** 已拒绝（限目标方拒绝报价）。 */
    REJECTED,

    /** 已过期（独立存储，展示归入"已拒绝"）。 */
    EXPIRED,

    /**
     * 已作废（发起方撤回 / 管理员销毁 / 成交复核失败整单作废——M7 裁决取
     * DESTROYED 而非 REJECTED，双方所有权零变化）。
     */
    DESTROYED;

    /**
     * 是否终态（PENDING 之外皆为终态）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static TradeStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("交易状态不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的交易状态: " + raw);
        }
    }
}
