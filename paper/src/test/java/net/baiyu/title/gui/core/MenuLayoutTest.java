package net.baiyu.title.gui.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MenuLayout 布局解析快照测试：用设计文档 §1.3.2 的真实字符画
 * 做快照断言，同时覆盖 9 字符校验、行数边界、slots 升序、slot 非唯一抛错、
 * roles 集合与防御性拷贝。
 */
class MenuLayoutTest {

    /** 装扮管理菜单布局快照。 */
    @Test
    void wardrobeLayoutSnapshot() {
        final MenuLayout layout = MenuLayout.of(
                "####P####",
                "#XXXXXXX#",
                "##ABCDE##");

        assertEquals(3, layout.rows());
        assertEquals(4, layout.slot('P'));
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16}, layout.slots('X'));
        assertEquals(20, layout.slot('A'));
        assertEquals(21, layout.slot('B'));
        assertEquals(22, layout.slot('C'));
        assertEquals(23, layout.slot('D'));
        assertEquals(24, layout.slot('E'));
        assertArrayEquals(new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 19, 25, 26},
                layout.slots(MenuLayout.BORDER));
        assertEquals(Set.of('#', 'P', 'X', 'A', 'B', 'C', 'D', 'E'), layout.roles());
    }

    /** 仓库菜单布局快照。 */
    @Test
    void repositoryLayoutSnapshot() {
        final MenuLayout layout = MenuLayout.of(
                "####P####",
                "#XXXXXXX#",
                "#XXXXXXX#",
                "#XXXXXXX#",
                "#XXXXXXX#",
                "#ABCDEFG#");

        assertEquals(6, layout.rows());
        assertEquals(4, layout.slot('P'));
        assertArrayEquals(new int[]{
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43}, layout.slots('X'));
        assertEquals(46, layout.slot('A'));
        assertEquals(47, layout.slot('B'));
        assertEquals(48, layout.slot('C'));
        assertEquals(49, layout.slot('D'));
        assertEquals(50, layout.slot('E'));
        assertEquals(51, layout.slot('F'));
        assertEquals(52, layout.slot('G'));
        assertEquals(Set.of('#', 'P', 'X', 'A', 'B', 'C', 'D', 'E', 'F', 'G'), layout.roles());
    }

    /** slots 返回值必须严格升序（跨行扫描顺序保证）。 */
    @Test
    void slotsAreStrictlyAscending() {
        final MenuLayout layout = MenuLayout.of(
                "#########",
                "#XXXXXXX#",
                "#XXXXXXX#");
        final int[] slots = layout.slots('X');
        assertEquals(14, slots.length);
        for (int i = 1; i < slots.length; i++) {
            assertTrue(slots[i - 1] < slots[i],
                    "槽位应严格升序: slots[" + (i - 1) + "]=" + slots[i - 1] + ", slots[" + i + "]=" + slots[i]);
        }
    }

    /** 每行必须恰为 9 个字符：8 字符与 10 字符都拒绝。 */
    @Test
    void rowMustBeExactlyNineChars() {
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.of("########"));
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.of("##########"));
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.of("#########", ""));
    }

    /** 行数边界：0 行与 7 行拒绝，1 行与 6 行接受。 */
    @Test
    void rowCountBounds() {
        assertThrows(IllegalArgumentException.class, MenuLayout::of);
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.of(
                "#########", "#########", "#########", "#########",
                "#########", "#########", "#########"));
        assertEquals(1, MenuLayout.of("####O####").rows());
        assertEquals(6, MenuLayout.of(
                "#########", "#########", "#########",
                "#########", "#########", "#########").rows());
    }

    /** slot()：非唯一角色抛 IllegalStateException，缺失角色同样抛。 */
    @Test
    void slotRejectsNonUniqueAndMissingRoles() {
        final MenuLayout layout = MenuLayout.of("##X#P#X##");
        assertThrows(IllegalStateException.class, () -> layout.slot('X'));
        assertThrows(IllegalStateException.class, () -> layout.slot('Q'));
        assertEquals(4, layout.slot('P'));
    }

    /** slots()：缺失角色返回空数组；has() 与之一致。 */
    @Test
    void slotsOfMissingRoleIsEmpty() {
        final MenuLayout layout = MenuLayout.of("####P####");
        assertArrayEquals(new int[0], layout.slots('Q'));
        assertFalse(layout.has('Q'));
        assertTrue(layout.has('P'));
        assertTrue(layout.has(MenuLayout.BORDER));
    }

    /** slots() 返回防御性拷贝：改写返回数组不影响内部状态。 */
    @Test
    void slotsReturnsDefensiveCopy() {
        final MenuLayout layout = MenuLayout.of("####P####");
        final int[] first = layout.slots(MenuLayout.BORDER);
        first[0] = 99;
        assertArrayEquals(new int[]{0, 1, 2, 3, 5, 6, 7, 8}, layout.slots(MenuLayout.BORDER));
    }

    /** roles() 不可变：外部改写被拒绝。 */
    @Test
    void rolesSetIsImmutable() {
        final MenuLayout layout = MenuLayout.of("####P####");
        assertThrows(UnsupportedOperationException.class, () -> layout.roles().add('Z'));
    }
}
