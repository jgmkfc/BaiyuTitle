package net.baiyu.title.gui.admin;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 购买类型推导与暂挂互斥规则测试：
 * {@link BuyType#derive} 的种类/条件映射 + {@link AdminEditState#toggle}
 * 的「免费/羁绊/组互斥清空其他」。
 */
class BuyTypeTest {

    /** 推导：NORMAL 条件空集 = 免费；有条件 = 按条件类型映射。 */
    @Test
    void derive_normalTitle_mapsConditions() {
        assertEquals(Set.of(BuyType.FREE), BuyType.derive(normal(List.of())));
        assertEquals(Set.of(BuyType.VAULT, BuyType.COIN), BuyType.derive(normal(List.of(
                new VaultCondition(BigDecimal.valueOf(80)), new CoinCondition(200)))));
    }

    /** 推导：BOND/GROUP/CUSTOM 由种类决定（CUSTOM 条件恒空按免费展示）。 */
    @Test
    void derive_categoryDrivenTitles() {
        assertEquals(Set.of(BuyType.BOND), BuyType.derive(category(TitleCategory.BOND)));
        assertEquals(Set.of(BuyType.GROUP), BuyType.derive(category(TitleCategory.GROUP)));
        assertEquals(Set.of(BuyType.FREE), BuyType.derive(category(TitleCategory.CUSTOM)));
    }

    /** 互斥属性：免费/羁绊/组称号互斥，其余四类可组合。 */
    @Test
    void exclusiveFlags() {
        assertTrue(BuyType.FREE.exclusive());
        assertTrue(BuyType.BOND.exclusive());
        assertTrue(BuyType.GROUP.exclusive());
        assertFalse(BuyType.VAULT.exclusive());
        assertFalse(BuyType.COIN.exclusive());
        assertFalse(BuyType.ITEM.exclusive());
        assertFalse(BuyType.PERMISSION.exclusive());
    }

    /** 切换：启用互斥类型清空其他全部类型。 */
    @Test
    void toggle_exclusiveClearsOthers() {
        AdminEditState state = new AdminEditState(1, Set.of(BuyType.VAULT, BuyType.COIN));
        state.toggle(BuyType.FREE);
        assertEquals(Set.of(BuyType.FREE), state.pending());
    }

    /** 切换：启用普通类型清掉互斥类型并可继续组合。 */
    @Test
    void toggle_normalTypeClearsExclusives() {
        AdminEditState state = new AdminEditState(1, Set.of(BuyType.FREE));
        state.toggle(BuyType.VAULT);
        assertEquals(Set.of(BuyType.VAULT), state.pending());
        state.toggle(BuyType.ITEM);
        assertEquals(Set.of(BuyType.VAULT, BuyType.ITEM), state.pending());
    }

    /** 切换：再次点击已启用类型 = 关闭；允许全部关闭（应用时另行拦截）。 */
    @Test
    void toggle_reclickDisables() {
        AdminEditState state = new AdminEditState(1, Set.of(BuyType.VAULT));
        state.toggle(BuyType.VAULT);
        assertTrue(state.pending().isEmpty());
    }

    /** 构造最小合法 NORMAL 称号。 */
    private static Title normal(List<PurchaseCondition> conditions) {
        return Title.builder().name("<white>测试").namePlain("测试").conditions(conditions).build();
    }

    /** 构造指定种类的最小合法称号。 */
    private static Title category(TitleCategory category) {
        return Title.builder().name("<white>测试").namePlain("测试").category(category).build();
    }
}
