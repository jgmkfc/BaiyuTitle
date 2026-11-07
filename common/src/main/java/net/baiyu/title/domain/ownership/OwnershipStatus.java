package net.baiyu.title.domain.ownership;

import java.util.Locale;

/**
 * 所有权状态（一行 = 一次持有实例，只追加不删除，状态流转）。
 */
public enum OwnershipStatus {

    /** 有效持有（每 (owner, title) 至多一条 ACTIVE）。 */
    ACTIVE,

    /** 已到期（ExpiryService 周期收敛）。 */
    EXPIRED,

    /** 已回收（管理员回收 / 组资格双失）。 */
    REVOKED,

    /** 已交易转出（新行在对侧复制同一到期时刻）。 */
    TRADED;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static OwnershipStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("所有权状态不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的所有权状态: " + raw);
        }
    }
}
