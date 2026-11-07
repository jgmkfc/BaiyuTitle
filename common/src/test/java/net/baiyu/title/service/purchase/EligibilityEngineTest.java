package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EligibilityEngine} 判定矩阵单测（纯函数枚举）：
 * AND/OR × vault/coin/item/permission × 满足/不满足 + 免费 + 已拥有 + 售罄 + 窗口
 * + 经济降级 + 隐藏/软删/种类不可购 + 原因集累积。
 */
class EligibilityEngineTest {

    /** 测试物品的 Base64 占位串。 */
    private static final String BAMBOO = "BAMBOO_BASE64";

    /** 测试权限节点。 */
    private static final String NODE = "title.test";

    /** 判定基准日。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 22);

    private final EligibilityEngine engine = new EligibilityEngine();

    // ---------- 判定矩阵：逐类型 × 满足/不满足 ----------

    /** AND 全满足：四类条件齐备且全满足 → 可购、非免单、明细全真。 */
    @Test
    void andLogic_allMet_canBuy() {
        Title title = title(CombineLogic.AND, allConditions());
        Eligibility result = engine.evaluate(title, snapshotMeetingAll());
        Eligibility.CanBuy canBuy = assertInstanceOf(Eligibility.CanBuy.class, result);
        assertFalse(canBuy.freeOfCharge(), "付费组合不是免单");
        assertEquals(Map.of(ConditionType.VAULT, true, ConditionType.COIN, true,
                ConditionType.ITEM, true, ConditionType.PERMISSION, true), canBuy.conditionStatus());
    }

    /** AND 单缺其一（逐类型枚举）：任一类型不满足 → 不可购 CONDITIONS_UNMET，明细定位到该类型。 */
    @ParameterizedTest(name = "缺{0}")
    @EnumSource(ConditionType.class)
    void andLogic_oneUnmet_cannotBuy(ConditionType unmet) {
        Title title = title(CombineLogic.AND, allConditions());
        Eligibility result = engine.evaluate(title, snapshotMissing(unmet));
        Eligibility.CannotBuy cannot = assertInstanceOf(Eligibility.CannotBuy.class, result);
        assertTrue(cannot.has(BlockReason.CONDITIONS_UNMET));
        assertFalse(cannot.conditionStatus().get(unmet), "缺失类型的明细应为不满足");
        cannot.conditionStatus().forEach((type, met) ->
                assertEquals(type != unmet, met, "其余类型的明细应为满足: " + type));
    }

    /** OR 仅满足其一（逐类型枚举）：任一类型满足 → 可购。 */
    @ParameterizedTest(name = "仅{0}满足")
    @EnumSource(ConditionType.class)
    void orLogic_singleMet_canBuy(ConditionType met) {
        Title title = title(CombineLogic.OR, allConditions());
        Eligibility result = engine.evaluate(title, snapshotOnly(met));
        Eligibility.CanBuy canBuy = assertInstanceOf(Eligibility.CanBuy.class, result);
        assertTrue(canBuy.conditionStatus().get(met));
        // 仅当满足的分支是权限时免单（"或"含权限且持有 → 免单）
        assertEquals(met == ConditionType.PERMISSION, canBuy.freeOfCharge());
    }

    /** OR 全不满足：四类条件均不满足 → 不可购 CONDITIONS_UNMET。 */
    @Test
    void orLogic_noneMet_cannotBuy() {
        Title title = title(CombineLogic.OR, allConditions());
        Eligibility result = engine.evaluate(title, snapshotMeetingNone());
        Eligibility.CannotBuy cannot = assertInstanceOf(Eligibility.CannotBuy.class, result);
        assertTrue(cannot.has(BlockReason.CONDITIONS_UNMET));
        assertFalse(cannot.conditionStatus().containsValue(true));
    }

    /** OR 含权限且持有：即便其余分支也满足，一律标记免单（服务端强制语义）。 */
    @Test
    void orLogic_permissionHeld_isFreeOfCharge() {
        Title title = title(CombineLogic.OR, allConditions());
        Eligibility.CanBuy canBuy = assertInstanceOf(Eligibility.CanBuy.class,
                engine.evaluate(title, snapshotMeetingAll()));
        assertTrue(canBuy.freeOfCharge(), "或逻辑持有权限即免单");
    }

    // ---------- 免费 / 已拥有 ----------

    /** 免费称号（条件空集）：可购且免单，明细为空。 */
    @Test
    void freeTitle_canBuyFreeOfCharge() {
        Title title = title(CombineLogic.AND, List.of());
        Eligibility.CanBuy canBuy = assertInstanceOf(Eligibility.CanBuy.class,
                engine.evaluate(title, snapshotMeetingNone()));
        assertTrue(canBuy.freeOfCharge());
        assertTrue(canBuy.conditionStatus().isEmpty());
    }

    /** 已拥有短路：即便同时售罄/窗口外，也返回 Owned（商店点击跳转仓库）。 */
    @Test
    void owned_shortCircuitsEverything() {
        Title soldOut = titleBase(CombineLogic.AND, List.of())
                .limitMode(LimitMode.TOTAL).totalCap(1).soldCount(1).build();
        Eligibility result = engine.evaluate(soldOut, snapshotBuilder().owned(true).build());
        assertInstanceOf(Eligibility.Owned.class, result);
    }

    // ---------- 售罄 / 窗口 ----------

    /** 售罄：TOTAL 达上限 → SOLD_OUT；未达上限 → 可购。 */
    @Test
    void totalCap_soldOutBlocks() {
        Title selling = titleBase(CombineLogic.AND, List.of())
                .limitMode(LimitMode.TOTAL).totalCap(2).soldCount(1).build();
        assertInstanceOf(Eligibility.CanBuy.class, engine.evaluate(selling, snapshotMeetingNone()));

        Title soldOut = titleBase(CombineLogic.AND, List.of())
                .limitMode(LimitMode.TOTAL).totalCap(2).soldCount(2).build();
        Eligibility.CannotBuy cannot = assertInstanceOf(Eligibility.CannotBuy.class,
                engine.evaluate(soldOut, snapshotMeetingNone()));
        assertEquals(Set.of(BlockReason.SOLD_OUT), cannot.reasons());
    }

    /** 获取窗口：闭区间端点当天可购；窗口外 → OUTSIDE_WINDOW。 */
    @Test
    void window_closedIntervalBoundaries() {
        Title title = titleBase(CombineLogic.AND, List.of())
                .limitMode(LimitMode.WINDOW).windowStart(TODAY).windowEnd(TODAY.plusDays(7)).build();
        assertInstanceOf(Eligibility.CanBuy.class, engine.evaluate(title, snapshotMeetingNone()),
                "起始端点当天应可购（闭区间）");

        Title ended = titleBase(CombineLogic.AND, List.of())
                .limitMode(LimitMode.WINDOW).windowStart(TODAY.minusDays(9))
                .windowEnd(TODAY.minusDays(1)).build();
        Eligibility.CannotBuy cannot = assertInstanceOf(Eligibility.CannotBuy.class,
                engine.evaluate(ended, snapshotMeetingNone()));
        assertEquals(Set.of(BlockReason.OUTSIDE_WINDOW), cannot.reasons());
    }

    // ---------- 经济降级 / 隐藏 / 软删 / 种类 ----------

    /** 经济不可用 + AND 含 Vault：CONDITIONS_UNMET 与 ECONOMY_UNAVAILABLE 并存。 */
    @Test
    void economyUnavailable_andVault_blocks() {
        Title title = title(CombineLogic.AND, List.of(new VaultCondition(new BigDecimal("80"))));
        Eligibility.CannotBuy cannot = assertInstanceOf(Eligibility.CannotBuy.class,
                engine.evaluate(title, snapshotBuilder().economy(false).build()));
        assertEquals(Set.of(BlockReason.CONDITIONS_UNMET, BlockReason.ECONOMY_UNAVAILABLE),
                cannot.reasons());
    }

    /** 经济不可用 + OR 另一分支满足：仍可购（经济缺失不阻断其他分支）。 */
    @Test
    void economyUnavailable_orOtherBranchMet_canBuy() {
        Title title = title(CombineLogic.OR, List.of(
                new VaultCondition(new BigDecimal("80")), new CoinCondition(200)));
        Eligibility.CanBuy canBuy = assertInstanceOf(Eligibility.CanBuy.class,
                engine.evaluate(title, snapshotBuilder().economy(false).coins(200).build()));
        assertFalse(canBuy.conditionStatus().get(ConditionType.VAULT));
        assertTrue(canBuy.conditionStatus().get(ConditionType.COIN));
    }

    /** 隐藏 / 软删 / 非 NORMAL 种类：各自命中对应原因；多因并存时原因集累积。 */
    @Test
    void hiddenDeletedCategory_reasonsAccumulate() {
        Title hidden = titleBase(CombineLogic.AND, List.of()).visible(false).build();
        assertEquals(Set.of(BlockReason.HIDDEN), cannotReasons(hidden));

        Title deleted = titleBase(CombineLogic.AND, List.of()).deletedAt(1L).build();
        assertEquals(Set.of(BlockReason.DELETED), cannotReasons(deleted));

        Title group = titleBase(CombineLogic.AND, List.of())
                .category(TitleCategory.GROUP).groupId(1).visible(true).build();
        assertEquals(Set.of(BlockReason.NOT_PURCHASABLE), cannotReasons(group));

        Title hiddenSoldOut = titleBase(CombineLogic.AND, List.of())
                .visible(false).limitMode(LimitMode.TOTAL).totalCap(1).soldCount(1).build();
        assertEquals(Set.of(BlockReason.HIDDEN, BlockReason.SOLD_OUT), cannotReasons(hiddenSoldOut));
    }

    // ---------- 工具 ----------

    /** 取不可购原因集（断言结果必为 CannotBuy）。 */
    private Set<BlockReason> cannotReasons(Title title) {
        return assertInstanceOf(Eligibility.CannotBuy.class,
                engine.evaluate(title, snapshotMeetingNone())).reasons();
    }

    /** 四类条件齐备的条件集（Vault 80 / 币 200 / 竹子×32 / title.test）。 */
    private static List<PurchaseCondition> allConditions() {
        return List.of(new VaultCondition(new BigDecimal("80")), new CoinCondition(200),
                new ItemCondition(BAMBOO, 32), new PermissionCondition(NODE));
    }

    /** 构建可见 NORMAL 称号。 */
    private static Title title(CombineLogic logic, List<PurchaseCondition> conditions) {
        return titleBase(logic, conditions).build();
    }

    /** 称号构建器底座。 */
    private static net.baiyu.title.domain.title.TitleBuilder titleBase(
            CombineLogic logic, List<PurchaseCondition> conditions) {
        return Title.builder().id(7).name("<white>[ 竹子测试 ]").namePlain("[ 竹子测试 ]")
                .combineLogic(logic).conditions(conditions).createdAt(1L).updatedAt(1L);
    }

    /** 全满足快照。 */
    private static PurchaseSnapshot snapshotMeetingAll() {
        return snapshotBuilder().build();
    }

    /** 全不满足快照（有经济但余额/物品/权限皆空）。 */
    private static PurchaseSnapshot snapshotMeetingNone() {
        return new PurchaseSnapshot(TODAY, true, BigDecimal.ZERO, 0, Set.of(), Map.of(), false);
    }

    /** 仅缺指定类型的快照。 */
    private static PurchaseSnapshot snapshotMissing(ConditionType unmet) {
        SnapshotBuilder builder = snapshotBuilder();
        switch (unmet) {
            case VAULT -> builder.vault("79.99");
            case COIN -> builder.coins(199);
            case ITEM -> builder.items(31);
            case PERMISSION -> builder.permissions(Set.of());
        }
        return builder.build();
    }

    /** 仅满足指定类型的快照。 */
    private static PurchaseSnapshot snapshotOnly(ConditionType met) {
        SnapshotBuilder builder = snapshotBuilder()
                .vault("0").coins(0).items(0).permissions(Set.of());
        switch (met) {
            case VAULT -> builder.vault("80");
            case COIN -> builder.coins(200);
            case ITEM -> builder.items(32);
            case PERMISSION -> builder.permissions(Set.of(NODE));
        }
        return builder.build();
    }

    /** 默认全满足的快照构建器。 */
    private static SnapshotBuilder snapshotBuilder() {
        return new SnapshotBuilder();
    }

    /** 快照测试构建器（默认值 = 全满足）。 */
    private static final class SnapshotBuilder {
        private boolean economy = true;
        private BigDecimal vault = new BigDecimal("685");
        private long coins = 200;
        private int items = 48;
        private Set<String> permissions = Set.of(NODE);
        private boolean owned;

        SnapshotBuilder economy(boolean value) { this.economy = value; return this; }

        SnapshotBuilder vault(String value) { this.vault = new BigDecimal(value); return this; }

        SnapshotBuilder coins(long value) { this.coins = value; return this; }

        SnapshotBuilder items(int value) { this.items = value; return this; }

        SnapshotBuilder permissions(Set<String> value) { this.permissions = value; return this; }

        SnapshotBuilder owned(boolean value) { this.owned = value; return this; }

        PurchaseSnapshot build() {
            return new PurchaseSnapshot(TODAY, economy, vault, coins,
                    permissions, Map.of(BAMBOO, items), owned);
        }
    }
}
