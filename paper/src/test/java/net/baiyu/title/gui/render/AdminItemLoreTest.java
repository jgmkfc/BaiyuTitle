package net.baiyu.title.gui.render;

import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 管理菜单条目 lore 快照测试：
 * 基本信息区（ID / 公开状态）+ 购买信息区（类型 / 价格 / 权限）+
 * 简介区 + 编辑操作行；模板 = 真实 zh_CN.yml。
 */
class AdminItemLoreTest {

    /** 传统 § 码序列化器。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 括号格式样例（config 默认值语义）。 */
    private static final TitleItemRenderer.NameBrackets BRACKETS =
            new TitleItemRenderer.NameBrackets("&f[ ", " &f]");

    /** 条件物品序列化串样例（仅作物品名映射键）。 */
    private static final String BAMBOO64 = "BAMBOO-BASE64";

    /** 被测渲染门面。 */
    private final TitleItemRenderer renderer = RendererFixtures.renderer();

    /** 快照：文档例「竹子测试」——物品或权限、价格与权限行、简介、编辑行。 */
    @Test
    void adminLore_bambooExample_matchesDesignDocVerbatim() {
        Title title = normal("竹子测试",
                List.of(new ItemCondition(BAMBOO64, 48), new PermissionCondition("title.test")))
                .toBuilder().id(17).combineLogic(CombineLogic.OR)
                .description(List.of("这是用来测试竹子的")).build();

        assertEquals(List.of(
                "§7■ §f基本信息:",
                "  §f · ID: §e17",
                "  §f · 公开状态: §a✔ 可见",
                " ",
                "  §f · 购买类型: §e物品§f或§e权限",
                "  §f · 价格: §e竹子 × 48",
                "  §f · 权限: §etitle.test",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试竹子的",
                " ",
                "§8[§6✎§8] §f编辑"), adminLore(title));
    }

    /** 隐藏称号：公开状态行翻为「× 隐藏」（管理菜单含隐藏称号的核心区别）。 */
    @Test
    void adminLore_hiddenTitle_showsHiddenLine() {
        Title title = normal("隐藏测试", List.of())
                .toBuilder().id(3).visible(false).build();

        assertEquals(List.of(
                "§7■ §f基本信息:",
                "  §f · ID: §e3",
                "  §f · 公开状态: §c× 隐藏",
                " ",
                "  §f · 购买类型: §e免费",
                " ",
                "§8[§6✎§8] §f编辑"), adminLore(title));
    }

    /** 羁绊称号：类型行=羁绊、无价格行；无简介时该区整体省略。 */
    @Test
    void adminLore_bondTitle_typeOnlyNoPriceLines() {
        Title title = Title.builder()
                .name("<white>羁绊测试").namePlain("羁绊测试")
                .category(TitleCategory.BOND).id(9).build();

        assertEquals(List.of(
                "§7■ §f基本信息:",
                "  §f · ID: §e9",
                "  §f · 公开状态: §a✔ 可见",
                " ",
                "  §f · 购买类型: §e羁绊",
                " ",
                "§8[§6✎§8] §f编辑"), adminLore(title));
    }

    /** 管理条目恒无 glint。 */
    @Test
    void adminItem_neverGlints() {
        assertFalse(renderer.adminItem(normal("免费测试", List.of()),
                Map.of(), BRACKETS).glint());
    }

    /** 渲染管理条目并把 lore 序列化为 § 码字符串列表。 */
    private List<String> adminLore(Title title) {
        return renderer.adminItem(title, Map.of(BAMBOO64, Component.text("竹子")), BRACKETS).lore()
                .stream().map(LEGACY::serialize).toList();
    }

    /** 构造最小合法 NORMAL 称号（名称按写入时归一化规则带 <white> 前缀）。 */
    private static Title normal(String plainName, List<PurchaseCondition> conditions) {
        return Title.builder()
                .name("<white>" + plainName)
                .namePlain(plainName)
                .conditions(conditions)
                .build();
    }
}
