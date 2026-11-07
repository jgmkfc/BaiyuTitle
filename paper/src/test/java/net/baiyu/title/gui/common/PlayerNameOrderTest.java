package net.baiyu.title.gui.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家名排序测试：总体顺序
 * 字母 → 数字 → 符号，类内按（忽略大小写的）字母序。
 */
class PlayerNameOrderTest {

    /** 总体顺序：字母组在前、数字组居中、符号组最后，各组内字母序。 */
    @Test
    void sort_lettersThenDigitsThenSymbols() {
        List<String> names = new ArrayList<>(List.of(
                "_hidden", "1st_Player", "zeta", "Alpha", "9lives", "bravo", "~tilde"));
        names.sort(PlayerNameOrder.COMPARATOR);
        assertEquals(List.of(
                "Alpha", "bravo", "zeta", "1st_Player", "9lives", "_hidden", "~tilde"), names);
    }

    /** 逐字符类别优先：首段相同后，后续位置的字母仍排在数字与符号之前。 */
    @Test
    void compare_ranksPerCharacterPosition() {
        assertTrue(PlayerNameOrder.compare("aa", "a1") < 0, "第二位 字母 应先于 数字");
        assertTrue(PlayerNameOrder.compare("a1", "a_") < 0, "第二位 数字 应先于 符号");
        assertTrue(PlayerNameOrder.compare("Steve", "Steve_alt") < 0, "短名前缀在前");
    }

    /** 字母序忽略大小写；完全同名（忽略大小写）时以原串自然序收尾保证全序。 */
    @Test
    void compare_caseInsensitiveWithStableTieBreak(){
        assertTrue(PlayerNameOrder.compare("apple", "Banana") < 0, "a 应先于 B（忽略大小写）");
        assertTrue(PlayerNameOrder.compare("Apple", "apple") != 0, "同名不同大小写仍有稳定全序");
        assertEquals(0, PlayerNameOrder.compare("Notch", "Notch"), "同串比较为 0");
    }
}
