package net.baiyu.title.domain.condition;

import java.util.Locale;

/**
 * 购买条件类型（对应 {@code title_condition} 表的 cond_type 列）。
 * <p>每称号每类型至多一条（DB UNIQUE(title_id, cond_type)）。
 */
public enum ConditionType {

    /** Vault 经济货币条件。 */
    VAULT,

    /** 称号币条件。 */
    COIN,

    /** 物品条件（物品存 Base64 序列化）。 */
    ITEM,

    /** 权限节点条件。 */
    PERMISSION;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static ConditionType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("购买条件类型不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的购买条件类型: " + raw);
        }
    }
}
