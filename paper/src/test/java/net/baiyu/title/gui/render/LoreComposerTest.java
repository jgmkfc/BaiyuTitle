package net.baiyu.title.gui.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分区 lore 组装快照单测（模板逐字对）。
 *
 * <p>模板源为真实交付的 zh_CN.yml（{@link ZhLoreTemplateFixture}）；断言把组件
 * 逐行序列化为传统 § 码字符串，与设计文档 lore 字符画逐行对照（& → §，
 * 其余逐字一致）——序列化对组件树结构不敏感，只看最终展示效果。</p>
 */
class LoreComposerTest {

    /** 传统 § 码序列化器（结构不敏感的展示层快照）。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 被测组装器（模板 = 真实 zh_CN.yml）。 */
    private final LoreComposer composer = new LoreComposer(ZhLoreTemplateFixture.zhTemplates());

    /**
     * 快照：称号选择菜单"竹子测试"完整 lore（业务设计 §1.3.2.4.2 例1 逐行逐字，
     * 未装扮 + 限时到期 2027年5月14号 + 单行简介 + 点击装扮操作行）。
     */
    @Test
    void selectionLore_bambooExample_matchesDesignDocVerbatim() {
        List<Component> lore = composer.compose(
                composer.statusSection(LocalDate.of(2027, 5, 14), false),
                composer.descriptionSection(List.of("这是用来测试竹子的")),
                composer.actionLine(LoreComposer.ActionType.EQUIP));

        assertEquals(List.of(
                "§b ■ §f状态:",
                "  §f · 到期时间: §e2027年5月14号",
                "  §7 × 未装扮",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试竹子的",
                " ",
                "§8[§a✔§8] §f点击装扮"), legacy(lore));
    }

    /**
     * 快照：称号选择菜单"权限测试"完整 lore（业务设计 §1.3.2.4.2 例2，
     * 已装扮 + 永久 + 点击卸下）。表头裁决：例2 原文 {@code 个人状态:} 与例1
     * {@code 状态:} 不一致，判为商店表头误写，统一取例1 {@code 状态:}
     * （其余各行逐字对照，含 {@code [&cx&8]} 的小写 x）。
     */
    @Test
    void selectionLore_permissionExample_equippedAndPermanent() {
        List<Component> lore = composer.compose(
                composer.statusSection(null, true),
                composer.descriptionSection(List.of("这是用来测试权限的")),
                composer.actionLine(LoreComposer.ActionType.UNEQUIP));

        assertEquals(List.of(
                "§b ■ §f状态:",
                "  §f · 到期时间: §e永久",
                "  §a ✔ 已装扮",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试权限的",
                " ",
                "§8[§cx§8] §7点击卸下"), legacy(lore));
    }

    /** 仓库版 = 去掉最后两行（空行 + 操作行）——业务设计 §1.3.2.4.2 明文规则。 */
    @Test
    void warehouseLore_dropsTrailingActionRows() {
        List<Component> full = composer.compose(
                composer.statusSection(LocalDate.of(2027, 5, 14), false),
                composer.descriptionSection(List.of("这是用来测试竹子的")),
                composer.actionLine(LoreComposer.ActionType.EQUIP));
        List<Component> warehouse = composer.compose(
                composer.statusSection(LocalDate.of(2027, 5, 14), false),
                composer.descriptionSection(List.of("这是用来测试竹子的")));

        assertEquals(legacy(full).subList(0, full.size() - 2), legacy(warehouse));
    }

    /** 稀有度行逐字对照商店示例 {@code &b ■  &f稀有度: &6史诗}；无稀有度整行省略。 */
    @Test
    void raritySection_matchesDocLineAndOmitsWhenAbsent() {
        assertEquals(List.of("§b ■  §f稀有度: §6史诗"), legacy(composer.raritySection("史诗")));
        assertTrue(composer.raritySection(null).isEmpty(), "null 稀有度应整行省略");
        assertTrue(composer.raritySection("  ").isEmpty(), "空白稀有度应整行省略");
    }

    /** 多行简介逐行套用正文模板；空简介整区省略。 */
    @Test
    void descriptionSection_multiLineAndEmpty() {
        assertEquals(List.of(
                "§b ■  §f简介:",
                "  §f  第一行",
                "  §f  第二行"), legacy(composer.descriptionSection(List.of("第一行", "第二行"))));
        assertTrue(composer.descriptionSection(List.of()).isEmpty(), "空简介应整区省略");
    }

    /** compose：空组整体跳过，不产生悬空空行；非空组之间恰一空行。 */
    @Test
    void compose_skipsEmptyGroupsWithoutDanglingBlank() {
        List<Component> lore = composer.compose(
                composer.statusSection(null, false),
                composer.descriptionSection(List.of()),
                composer.actionLine(LoreComposer.ActionType.EQUIP));

        assertEquals(List.of(
                "§b ■ §f状态:",
                "  §f · 到期时间: §e永久",
                "  §7 × 未装扮",
                " ",
                "§8[§a✔§8] §f点击装扮"), legacy(lore));
        assertTrue(composer.compose(composer.descriptionSection(List.of())).isEmpty(),
                "全空组应得到空 lore");
    }

    /** 稀有度行与简介区并作一组时相邻无空行（对照商店示例的相邻布局）。 */
    @Test
    void rarityAndDescription_shareOneGroupWithoutBlankBetween() {
        List<Component> group = composer.compose(concat(
                composer.raritySection("史诗"),
                composer.descriptionSection(List.of("2025国庆限定"))));

        assertEquals(List.of(
                "§b ■  §f稀有度: §6史诗",
                "§b ■  §f简介:",
                "  §f  2025国庆限定"), legacy(group));
    }

    /** 组件列表 → 传统 § 码字符串列表。 */
    private static List<String> legacy(List<Component> lore) {
        return lore.stream().map(LEGACY::serialize).toList();
    }

    /** 拼接两个区段为一组。 */
    private static List<Component> concat(List<Component> a, List<Component> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
