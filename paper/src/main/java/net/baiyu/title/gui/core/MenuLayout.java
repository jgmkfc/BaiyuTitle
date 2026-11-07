package net.baiyu.title.gui.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字符画布局 DSL：把设计文档中的菜单布局字符画
 * 原样解析为「角色字符 → 槽位数组」的映射。
 *
 * <p>每行恰 9 个字符、1~6 行；行内第 c 列（0 起）对应槽位 {@code 行号*9+c}。
 * 约定 {@link #BORDER}（{@code '#'}）为边框角色；其余字符含义由各菜单自定
 * （如 X=条目、P=返回主菜单、A~G=功能键、Z=空气）。</p>
 *
 * <p>不可变：解析完成后只读，可安全作为菜单类的静态常量共享。</p>
 */
public final class MenuLayout {

    /** 边框角色字符。 */
    public static final char BORDER = '#';

    /** 每行槽位数（箱子菜单固定 9 列）。 */
    private static final int COLUMNS = 9;

    /** 最大行数（六行大箱子）。 */
    private static final int MAX_ROWS = 6;

    /** 行数。 */
    private final int rows;

    /** 角色 → 升序槽位数组（内部持有，读取时防御性拷贝）。 */
    private final Map<Character, int[]> slotsByRole;

    private MenuLayout(int rows, Map<Character, int[]> slotsByRole) {
        this.rows = rows;
        this.slotsByRole = slotsByRole;
    }

    /**
     * 解析布局字符画。
     *
     * @param rows 行模板（每行恰 9 字符，共 1~6 行）
     * @return 解析后的布局
     * @throws IllegalArgumentException 行数越界或某行长度不是 9 时
     */
    public static MenuLayout of(String... rows) {
        Objects.requireNonNull(rows, "布局行模板不能为空");
        if (rows.length < 1 || rows.length > MAX_ROWS) {
            throw new IllegalArgumentException("布局行数必须在 1~" + MAX_ROWS + " 之间，实际 " + rows.length + " 行");
        }
        final Map<Character, List<Integer>> collected = new LinkedHashMap<>();
        for (int r = 0; r < rows.length; r++) {
            final String row = rows[r];
            if (row == null || row.length() != COLUMNS) {
                throw new IllegalArgumentException(
                        "布局第 " + (r + 1) + " 行必须恰为 " + COLUMNS + " 个字符: " + row);
            }
            for (int c = 0; c < COLUMNS; c++) {
                collected.computeIfAbsent(row.charAt(c), k -> new ArrayList<>()).add(r * COLUMNS + c);
            }
        }
        final Map<Character, int[]> byRole = new LinkedHashMap<>();
        // 逐行逐列扫描天然升序，无需再排序
        collected.forEach((role, list) ->
                byRole.put(role, list.stream().mapToInt(Integer::intValue).toArray()));
        return new MenuLayout(rows.length, byRole);
    }

    /** @return 布局行数（1~6） */
    public int rows() {
        return rows;
    }

    /**
     * 取某角色的全部槽位。
     *
     * @param role 角色字符
     * @return 升序槽位数组（防御性拷贝）；角色不存在时返回空数组
     */
    public int[] slots(char role) {
        final int[] slots = slotsByRole.get(role);
        return slots == null ? new int[0] : Arrays.copyOf(slots, slots.length);
    }

    /**
     * 取某角色的唯一槽位（用于 P/单一功能键等恰出现一次的角色）。
     *
     * @param role 角色字符
     * @return 唯一槽位
     * @throws IllegalStateException 角色不存在或出现多于一次时
     */
    public int slot(char role) {
        final int[] slots = slotsByRole.get(role);
        if (slots == null) {
            throw new IllegalStateException("布局中不存在角色 '" + role + "'");
        }
        if (slots.length != 1) {
            throw new IllegalStateException(
                    "角色 '" + role + "' 在布局中出现 " + slots.length + " 次，非唯一槽位");
        }
        return slots[0];
    }

    /**
     * @param role 角色字符
     * @return 布局中是否出现该角色
     */
    public boolean has(char role) {
        return slotsByRole.containsKey(role);
    }

    /** @return 布局中出现过的全部角色字符（含边框 '#'；不可变） */
    public Set<Character> roles() {
        return Set.copyOf(slotsByRole.keySet());
    }
}
