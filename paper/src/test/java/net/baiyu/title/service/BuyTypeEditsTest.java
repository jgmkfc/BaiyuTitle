package net.baiyu.title.service;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.gui.admin.BuyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 购买类型集应用裁决专测（BuyTypeSelect 清价联动——服务级纯函数）：
 * 空集/互斥组合拒绝、种类锁定空操作、免费清空条件、
 * 应用时被取消类型的价格条件整条清除且新启用类型不落条件行。
 */
class BuyTypeEditsTest {

    /** 现有条件样例：金币 + 称号币 + 物品（声明顺序敏感）。 */
    private static final List<PurchaseCondition> PRICED = List.of(
            new VaultCondition(BigDecimal.valueOf(80)),
            new CoinCondition(200),
            new ItemCondition("BASE64", 3));

    /** 空选择集：拒绝。 */
    @Test
    void plan_emptySelection_rejected() {
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(normal(CombineLogic.AND, PRICED), Set.of()));
    }

    /** 互斥组合（免费+金币 / 羁绊+称号币）：拒绝。 */
    @Test
    void plan_exclusiveCombination_rejected() {
        Title title = normal(CombineLogic.AND, PRICED);
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(title, EnumSet.of(BuyType.FREE, BuyType.VAULT)));
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(title, EnumSet.of(BuyType.BOND, BuyType.COIN)));
    }

    /** 普通称号不可切换为羁绊/组称号。 */
    @Test
    void plan_normalToBondOrGroup_rejected() {
        Title title = normal(CombineLogic.AND, PRICED);
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(title, EnumSet.of(BuyType.BOND)));
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(title, EnumSet.of(BuyType.GROUP)));
    }

    /** 种类锁定称号：与现状一致 → 空操作；不一致 → 拒绝。 */
    @Test
    void plan_categoryLockedTitle_noOpOrReject() {
        Title bond = Title.builder().id(9).name("<white>羁绊").namePlain("羁绊")
                .category(TitleCategory.BOND).build();
        assertInstanceOf(BuyTypeEdits.Plan.NoOp.class,
                BuyTypeEdits.plan(bond, EnumSet.of(BuyType.BOND)));
        assertInstanceOf(BuyTypeEdits.Plan.Reject.class,
                BuyTypeEdits.plan(bond, EnumSet.of(BuyType.VAULT)));
    }

    /** 免费：条件全清且组合逻辑强制回 AND（免费 = 条件空集的结构约定）。 */
    @Test
    void plan_free_clearsAllConditions() {
        BuyTypeEdits.Plan plan = BuyTypeEdits.plan(
                normal(CombineLogic.OR, PRICED), EnumSet.of(BuyType.FREE));
        BuyTypeEdits.Plan.Apply apply = assertInstanceOf(BuyTypeEdits.Plan.Apply.class, plan);
        assertEquals(CombineLogic.AND, apply.logic());
        assertEquals(List.of(), apply.conditions());
    }

    /**
     * 清价联动核心：保留 称号币 + 启用 权限 → 金币/物品条件整条清除、
     * 称号币条件原样保留、新启用的权限不落条件行（录价即启用），
     * 组合逻辑保留现值。
     */
    @Test
    void plan_apply_clearsCancelledTypePricesOnly() {
        BuyTypeEdits.Plan plan = BuyTypeEdits.plan(normal(CombineLogic.OR, PRICED),
                EnumSet.of(BuyType.COIN, BuyType.PERMISSION));
        BuyTypeEdits.Plan.Apply apply = assertInstanceOf(BuyTypeEdits.Plan.Apply.class, plan);
        assertEquals(CombineLogic.OR, apply.logic());
        assertEquals(List.of(new CoinCondition(200)), apply.conditions());
    }

    /** 全保留应用：条件表原样（幂等应用不丢数据）。 */
    @Test
    void plan_applySameTypes_keepsConditionsIntact() {
        BuyTypeEdits.Plan plan = BuyTypeEdits.plan(normal(CombineLogic.AND, PRICED),
                EnumSet.of(BuyType.VAULT, BuyType.COIN, BuyType.ITEM));
        BuyTypeEdits.Plan.Apply apply = assertInstanceOf(BuyTypeEdits.Plan.Apply.class, plan);
        assertEquals(PRICED, apply.conditions());
    }

    /** 构造最小合法 NORMAL 称号（名称按写入时归一化规则带 <white> 前缀）。 */
    private static Title normal(CombineLogic logic, List<PurchaseCondition> conditions) {
        return Title.builder().id(7).name("<white>测试").namePlain("测试")
                .combineLogic(logic).conditions(conditions).build();
    }
}
