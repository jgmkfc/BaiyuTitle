package net.baiyu.title.domain.order;

import java.util.Locale;

/**
 * 购买订单状态（RESERVED→PAYING→FULFILLED，失败补偿 REFUNDED）。
 * <p>订单只沿状态机边迁移（条件 UPDATE from-state，不变量 §十 8）。
 */
public enum OrderState {

    /** 已预留：称号币已扣、sold_count 已占位，尚未触碰 Vault。 */
    RESERVED,

    /** 支付中：允许触碰 Vault/物品；崩溃恢复按 config purchase.recovery 处理。 */
    PAYING,

    /** 已履约：授予完成（终态）。 */
    FULFILLED,

    /** 已失败（终态）。 */
    FAILED,

    /** 已退款：币已退、sold_count 已释放（终态）。 */
    REFUNDED,

    /** 待人工处理：崩溃恢复无法自动裁决，等待管理员 resolve。 */
    MANUAL;

    /**
     * 是否终态（FULFILLED/FAILED/REFUNDED；MANUAL 等待管理员裁决不算终态）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == FULFILLED || this == FAILED || this == REFUNDED;
    }

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static OrderState fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("订单状态不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的订单状态: " + raw);
        }
    }
}
