package net.baiyu.title.domain.title;

import java.util.Locale;

/**
 * 购买条件组合逻辑。
 * <p>仅当条件数 ≥ 2 时有意义；OR 且含权限条件且玩家持有该权限时走免单分支。不支持嵌套。
 */
public enum CombineLogic {

    /** 全部条件同时满足并全部扣付。 */
    AND,

    /** 任选其一条件满足并扣付。 */
    OR;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static CombineLogic fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("条件组合逻辑不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的条件组合逻辑: " + raw);
        }
    }
}
