package net.baiyu.title.domain.wardrobe;

import java.util.Locale;

/**
 * 装扮栏位模式。
 */
public enum SlotMode {

    /** 固定展示指定称号。 */
    FIXED,

    /** 从随机池中随机展示。 */
    RANDOM;

    /**
     * 从字符串安全解析（大小写不敏感，容忍前后空白）。
     *
     * @param raw 原始字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 当 raw 为空或不是合法取值时
     */
    public static SlotMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("栏位模式不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的栏位模式: " + raw);
        }
    }
}
