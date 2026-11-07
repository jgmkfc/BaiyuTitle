package net.baiyu.title.gui.common;

import java.util.Comparator;

/**
 * 玩家名排序：按字母顺序排序，
 * 总体顺序为 字母 → 数字 → 符号。
 *
 * <p>实现为逐字符比较：先比字符类别（字母 0、数字 1、符号 2），
 * 同类别再按小写字母序比较；前缀相同者短名在前；完全同名忽略大小写时
 * 以原串自然序收尾保证全序稳定。纯函数、零 Bukkit 依赖，可直接单测。</p>
 */
public final class PlayerNameOrder {

    /** 玩家名比较器（字母 → 数字 → 符号，类内字母序）。 */
    public static final Comparator<String> COMPARATOR = PlayerNameOrder::compare;

    private PlayerNameOrder() {
    }

    /**
     * 比较两个玩家名。
     *
     * @param left  左名
     * @param right 右名
     * @return 负 / 零 / 正——left 先于 / 等于 / 后于 right
     */
    static int compare(String left, String right) {
        final int limit = Math.min(left.length(), right.length());
        for (int i = 0; i < limit; i++) {
            final char a = left.charAt(i);
            final char b = right.charAt(i);
            final int byRank = Integer.compare(rank(a), rank(b));
            if (byRank != 0) {
                return byRank;
            }
            final int byLower = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
            if (byLower != 0) {
                return byLower;
            }
        }
        final int byLength = Integer.compare(left.length(), right.length());
        return byLength != 0 ? byLength : left.compareTo(right);
    }

    /** 字符类别序：字母 0、数字 1、其余符号 2。 */
    private static int rank(char ch) {
        if (Character.isLetter(ch)) {
            return 0;
        }
        return Character.isDigit(ch) ? 1 : 2;
    }
}
