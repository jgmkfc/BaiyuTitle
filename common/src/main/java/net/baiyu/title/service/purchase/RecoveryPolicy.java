package net.baiyu.title.service.purchase;

import java.util.Locale;

/**
 * PAYING 孤儿订单的启动恢复策略（config 键 {@code purchase.recovery}，默认 grant；
 * 设计 01 裁决 7）。
 * <p>RESERVED 孤儿必未触碰 Vault，恒退款，不受本策略影响。
 */
public enum RecoveryPolicy {

    /** 默认：视作支付已完成，履约授予（玩家利益优先）。 */
    GRANT,

    /** 退款：退称号币、释放售卖占位（Vault/物品若已扣付需管理员线下补偿）。 */
    REFUND,

    /** 留置：标记 MANUAL，等待管理员逐单裁决。 */
    MANUAL;

    /**
     * 从配置字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 配置值
     * @return 对应策略
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static RecoveryPolicy fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("恢复策略不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的恢复策略（可选 grant/refund/manual）: " + raw);
        }
    }
}
