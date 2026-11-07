package net.baiyu.title.gui.render;

import net.baiyu.title.domain.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 称号物品渲染门面单测：仓库 / 选择两上下文的组装顺序、
 * 名称三段拼接（空格回归红线）、glint 语义与图标材质解析——
 * 全部走纯函数重载（括号格式显式注入），不触碰 Bukkit 运行时与配置。
 */
class TitleItemRendererTest {

    /** 传统 § 码序列化器。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 括号格式样例（config 默认值语义：&f[ 空格括号）。 */
    private static final TitleItemRenderer.NameBrackets BRACKETS =
            new TitleItemRenderer.NameBrackets("&f[ ", " &f]");

    /** 被测渲染门面（配置提供器在纯函数路径上不被触碰）。 */
    private final TitleItemRenderer renderer = RendererFixtures.renderer();

    /** 选择菜单条目：状态 + 简介 + 操作行按序组装，未装扮 → 点击装扮。 */
    @Test
    void selectionItem_assemblesStatusDescriptionAction() {
        TitleItemRenderer.RenderedItem item = renderer.selectionItem(
                view(title("竹子测试", "minecraft:bamboo", List.of("这是用来测试竹子的")),
                        LocalDate.of(2027, 5, 14), false),
                false, BRACKETS);

        assertEquals(List.of(
                "§b ■ §f状态:",
                "  §f · 到期时间: §e2027年5月14号",
                "  §7 × 未装扮",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试竹子的",
                " ",
                "§8[§a✔§8] §f点击装扮"), legacy(item.lore()));
        assertEquals(Material.BAMBOO, item.material());
    }

    /** 已装扮条目操作行为点击卸下（含文档原文的小写 x）。 */
    @Test
    void selectionItem_equippedShowsUnequipAction() {
        TitleItemRenderer.RenderedItem item = renderer.selectionItem(
                view(title("权限测试", "minecraft:name_tag", List.of("这是用来测试权限的")), null, true),
                false, BRACKETS);

        List<String> lore = legacy(item.lore());
        assertEquals("  §a ✔ 已装扮", lore.get(2));
        assertEquals("§8[§cx§8] §7点击卸下", lore.get(lore.size() - 1));
    }

    /** 仓库条目 = 选择条目去掉最后两行，且恒无 glint。 */
    @Test
    void warehouseItem_dropsActionRowsAndNeverGlints() {
        Title title = title("竹子测试", "minecraft:bamboo", List.of("这是用来测试竹子的"));
        TitleItemRenderer.OwnedTitleView view = view(title, LocalDate.of(2027, 5, 14), false);

        List<String> selection = legacy(renderer.selectionItem(view, true, BRACKETS).lore());
        TitleItemRenderer.RenderedItem warehouse = renderer.warehouseItem(view, BRACKETS);

        assertEquals(selection.subList(0, selection.size() - 2), legacy(warehouse.lore()));
        assertFalse(warehouse.glint(), "仓库条目无选中态，恒不附魔");
    }

    /** glint 跟随选中态（选择菜单暂选 / 随机池标记）。 */
    @Test
    void selectionItem_glintFollowsSelection() {
        TitleItemRenderer.OwnedTitleView view =
                view(title("竹子测试", "minecraft:bamboo", List.of()), null, false);
        assertTrue(renderer.selectionItem(view, true, BRACKETS).glint());
        assertFalse(renderer.selectionItem(view, false, BRACKETS).glint());
    }

    /** 名称三段拼接：括号段空格逐字保留（§1.5.2 空格回归红线），显示为 &f[ 竹子测试 ]。 */
    @Test
    void displayName_keepsBracketSpaces() {
        TitleItemRenderer.RenderedItem item = renderer.warehouseItem(
                view(title("竹子测试", "minecraft:bamboo", List.of()), null, false), BRACKETS);

        assertEquals("[ 竹子测试 ]", plain(item.name()), "括号内空格必须逐字保留");
        // 序列化器会折叠冗余 §f 并在白色段后的无样式空格前补 §r——展示效果与文档
        // 示例 &f[ 竹子测试 ] 一致（称号名自带 <white> 前缀被并入前括号段同色）
        assertEquals("§f[ 竹子测试§r §f]", LEGACY.serialize(item.name()),
                "三段分别归一化拼接的展示快照");
    }

    /** 图标材质解析：命名空间键 / 裸枚举名皆可，未知材质兜底 NAME_TAG。 */
    @Test
    void resolveMaterial_namespacedBareAndFallback() {
        assertEquals(Material.CHEST, TitleItemRenderer.resolveMaterial("minecraft:chest"));
        assertEquals(Material.BAMBOO, TitleItemRenderer.resolveMaterial("BAMBOO"));
        assertEquals(Material.NAME_TAG, TitleItemRenderer.resolveMaterial("definitely_not_a_material"));
    }

    /** 构造最小合法称号（名称按写入时归一化规则带 <white> 前缀）。 */
    private static Title title(String plainName, String iconMaterial, List<String> description) {
        return Title.builder()
                .name("<white>" + plainName)
                .namePlain(plainName)
                .iconMaterial(iconMaterial)
                .description(description)
                .build();
    }

    /** 构造已持有称号视图。 */
    private static TitleItemRenderer.OwnedTitleView view(Title title, LocalDate expiresOn, boolean equipped) {
        return new TitleItemRenderer.OwnedTitleView(title, expiresOn, equipped);
    }

    /** 组件列表 → 传统 § 码字符串列表。 */
    private static List<String> legacy(List<Component> lore) {
        return lore.stream().map(LEGACY::serialize).toList();
    }

    /** 组件转纯文本。 */
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
