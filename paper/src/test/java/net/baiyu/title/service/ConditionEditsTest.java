package net.baiyu.title.service;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 购买条件列表编辑纯函数测试：增改保持「每类型至多一条」、
 * 清除与清价联动保留声明顺序、入参列表不被修改。
 */
class ConditionEditsTest {

    /** 现有条件样例：金币 + 称号币 + 权限（声明顺序敏感）。 */
    private static final List<PurchaseCondition> BASE = List.of(
            new VaultCondition(BigDecimal.valueOf(80)),
            new CoinCondition(200),
            new PermissionCondition("title.test"));

    /** upsert：无同类型 → 追加到尾部，原有顺序不动。 */
    @Test
    void upsert_appendsWhenTypeAbsent() {
        ItemCondition item = new ItemCondition("BASE64", 5);
        List<PurchaseCondition> result = ConditionEdits.upsert(BASE, item);
        assertEquals(4, result.size());
        assertEquals(item, result.get(3));
        assertEquals(BASE, result.subList(0, 3));
    }

    /** upsert：有同类型 → 旧条目移除、新条目排尾（每类型至多一条）。 */
    @Test
    void upsert_replacesExistingType() {
        VaultCondition replacement = new VaultCondition(BigDecimal.valueOf(500));
        List<PurchaseCondition> result = ConditionEdits.upsert(BASE, replacement);
        assertEquals(3, result.size());
        assertEquals(new CoinCondition(200), result.get(0));
        assertEquals(new PermissionCondition("title.test"), result.get(1));
        assertEquals(replacement, result.get(2));
    }

    /** without：移除指定类型；类型不存在时内容不变。 */
    @Test
    void without_removesOnlyGivenType() {
        List<PurchaseCondition> result = ConditionEdits.without(BASE, ConditionType.COIN);
        assertEquals(List.of(BASE.get(0), BASE.get(2)), result);
        assertEquals(BASE, ConditionEdits.without(BASE, ConditionType.ITEM));
    }

    /** retain：清价联动只留保留集内的条件，顺序保持。 */
    @Test
    void retain_keepsOnlySelectedTypes() {
        List<PurchaseCondition> result = ConditionEdits.retain(
                BASE, Set.of(ConditionType.VAULT, ConditionType.PERMISSION));
        assertEquals(List.of(BASE.get(0), BASE.get(2)), result);
        assertTrue(ConditionEdits.retain(BASE, Set.of()).isEmpty(), "空保留集清空全部条件");
    }

    /** 全部方法不修改入参列表（BASE 为不可变列表，变异会直接抛异常——防御性断言）。 */
    @Test
    void edits_doNotMutateInput() {
        ConditionEdits.upsert(BASE, new CoinCondition(1));
        ConditionEdits.without(BASE, ConditionType.VAULT);
        ConditionEdits.retain(BASE, Set.of(ConditionType.COIN));
        assertEquals(3, BASE.size());
    }
}
