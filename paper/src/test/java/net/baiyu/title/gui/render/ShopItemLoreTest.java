package net.baiyu.title.gui.render;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.service.purchase.EligibilityEngine;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 商店菜单 lore 快照单测（逐行逐字对照）。
 *
 * <p>模板源为真实交付的 zh_CN.yml；资格判定走真实
 * {@link EligibilityEngine}（快照注入），断言把组件逐行序列化为传统 § 码
 * 字符串与设计文档示例对照（&amp; → §，其余逐字一致）。覆盖形态：
 * 免费 / 单条件满足 / 多条件与部分不满足 / 或逻辑（含免单）/
 * 售罄 / 窗口外 / 经济不可用 / 已拥有。</p>
 */
class ShopItemLoreTest {

    /** 传统 § 码序列化器（结构不敏感的展示层快照）。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 文档示例基准日。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 22);

    /** 括号格式样例（config 默认值语义）。 */
    private static final TitleItemRenderer.NameBrackets BRACKETS =
            new TitleItemRenderer.NameBrackets("&f[ ", " &f]");

    /** 条件物品序列化串样例（键与条件产物逐字节一致即可，内容不参与断言）。 */
    private static final String BAMBOO64 = "BAMBOO-BASE64";

    /** 资格判定引擎（纯函数）。 */
    private static final EligibilityEngine ENGINE = new EligibilityEngine();

    /** 被测渲染门面（模板 = 真实 zh_CN.yml；配置提供器纯函数路径不触碰）。 */
    private final TitleItemRenderer renderer = RendererFixtures.renderer();

    /** 快照：文档例1「竹子测试」——物品单条件满足 + 限时 2 年 → 点击购买。 */
    @Test
    void shopLore_bambooExample_matchesDesignDocVerbatim() {
        Title title = normal("竹子测试", List.of(new ItemCondition(BAMBOO64, 32)))
                .toBuilder().holdDurationDays(730)
                .description(List.of("这是用来测试竹子的")).build();
        PurchaseSnapshot snapshot = snapshot(true, 0, 0, Set.of(), Map.of(BAMBOO64, 48), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e物品",
                "  §f · 价格: §e竹子 × 32",
                "  §f · 限时: §e2年",
                " ",
                "§b ■ §f个人状态:",
                "  §f · 背包: §a竹子 × 48",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试竹子的",
                " ",
                "§8[§a✔§8] §f点击购买"), shopLore(title, snapshot));
    }

    /** 快照：文档例2「货币测试」——称号币与金币"与"组合部分不满足 → 无法购买。 */
    @Test
    void shopLore_currencyExample_andPartiallyUnmet() {
        Title title = normal("货币测试",
                List.of(new CoinCondition(200), new VaultCondition(BigDecimal.valueOf(500))))
                .toBuilder().description(List.of("这是用来测试经济货币的")).build();
        PurchaseSnapshot snapshot = snapshot(true, 685, 160, Set.of(), Map.of(), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e称号币§f与§e金币",
                "  §f · 称号币: §e200",
                "  §f · 金币: §e500",
                " ",
                "§b ■ §f个人状态:",
                "  §f · 称号币: §7160",
                "  §f · 金币: §a685",
                " ",
                "§b ■  §f简介:",
                "  §f  这是用来测试经济货币的",
                " ",
                "§8[§c×§8] §7无法购买"), shopLore(title, snapshot));
    }

    /** 快照：文档例3「国庆」——金币或权限 + 时间限定窗口外（2026.7.22）→ 无法购买。 */
    @Test
    void shopLore_nationalDayExample_orLogicOutsideWindow() {
        Title title = normal("国庆",
                List.of(new VaultCondition(BigDecimal.valueOf(80)),
                        new PermissionCondition("title.test")))
                .toBuilder().combineLogic(CombineLogic.OR)
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.of(2025, 9, 30)).windowEnd(LocalDate.of(2025, 10, 7))
                .rarity("史诗").description(List.of("2025国庆限定")).build();
        PurchaseSnapshot snapshot = snapshot(true, 685, 0, Set.of(), Map.of(), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e金币§f或§e权限",
                "  §f · 金币: §e80",
                "  §f · 权限: §etitle.test",
                " ",
                "§b ■ §f个人状态:",
                "  §f · 金币: §a685",
                "  §f · 权限: §7无",
                " ",
                "§b ■  §f限定:",
                "  §f · 期限: §e2025年9月30日~2025年10月7日",
                " ",
                "§b ■  §f稀有度: §6史诗",
                "§b ■  §f简介:",
                "  §f  2025国庆限定",
                " ",
                "§8[§c×§8] §7无法购买"), shopLore(title, snapshot));
    }

    /** 快照：文档例4「老玩家」——总数限定未满（17/30）金币满足 → 点击购买。 */
    @Test
    void shopLore_veteranExample_totalCapOpen() {
        Title title = veteranTitle(17);
        PurchaseSnapshot snapshot = snapshot(true, 685, 0, Set.of(), Map.of(), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e金币",
                "  §f · 金币: §e80",
                " ",
                "§b ■ §f个人状态:",
                "  §f · 金币: §a685",
                " ",
                "§b ■  §f限定:",
                "  §f · 总数: §e30",
                " ",
                "§b ■  §f稀有度: §6史诗",
                "§b ■  §f简介:",
                "  §f  购买了你就是官方认定老玩家",
                " ",
                "§8[§a✔§8] §f点击购买"), shopLore(title, snapshot));
    }

    /** 快照：文档例6「权限测试」——已拥有 + 未装扮 → 跳转仓库。 */
    @Test
    void shopLore_ownedExample_gotoRepository() {
        Title title = normal("权限测试", List.of(new PermissionCondition("title.test")))
                .toBuilder().rarity("史诗").description(List.of("这是用来测试权限的")).build();
        PurchaseSnapshot snapshot = snapshot(true, 0, 0, Set.of("title.test"), Map.of(), true);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e权限",
                "  §f · 权限: §etitle.test",
                " ",
                "§b ■ §f个人状态:",
                "  §a ✔ 已拥有",
                "  §7 × 未装扮",
                " ",
                "§b ■  §f稀有度: §6史诗",
                "§b ■  §f简介:",
                "  §f  这是用来测试权限的",
                " ",
                "§8[§a->§8] §f跳转仓库"), shopLore(title, snapshot));
    }

    /** 免费称号：类型行=免费、无价格行、个人状态区整体省略 → 点击购买。 */
    @Test
    void shopLore_freeTitle_omitsPersonalSection() {
        Title title = normal("免费测试", List.of())
                .toBuilder().description(List.of("免费领取")).build();
        PurchaseSnapshot snapshot = snapshot(true, 0, 0, Set.of(), Map.of(), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e免费",
                " ",
                "§b ■  §f简介:",
                "  §f  免费领取",
                " ",
                "§8[§a✔§8] §f点击购买"), shopLore(title, snapshot));
    }

    /** 售罄（30/30）：限定区仍展示总数，底行翻为无法购买。 */
    @Test
    void shopLore_soldOut_flipsActionRow() {
        Title title = veteranTitle(30);
        PurchaseSnapshot snapshot = snapshot(true, 685, 0, Set.of(), Map.of(), false);

        List<String> lore = shopLore(title, snapshot);
        assertEquals("  §f · 总数: §e30", lore.get(8), "售罄后限定区总数行不变");
        assertEquals("  §f · 金币: §a685", lore.get(5), "余额充足时个人状态仍为满足色");
        assertEquals("§8[§c×§8] §7无法购买", lore.get(lore.size() - 1));
    }

    /** 经济不可用：Vault 个人状态行整行替换为「经济系统不可用」。 */
    @Test
    void shopLore_economyUnavailable_showsDegradedStatusLine() {
        Title title = normal("金币测试", List.of(new VaultCondition(BigDecimal.valueOf(80))));
        PurchaseSnapshot snapshot = snapshot(false, 0, 0, Set.of(), Map.of(), false);

        assertEquals(List.of(
                "§b ■  §f购买:",
                "  §f · 类型: §e金币",
                "  §f · 金币: §e80",
                " ",
                "§b ■ §f个人状态:",
                "  §7 × 经济系统不可用",
                " ",
                "§8[§c×§8] §7无法购买"), shopLore(title, snapshot));
    }

    /** 或逻辑持有权限（免单分支）：权限行=有（&a）→ 点击购买。 */
    @Test
    void shopLore_orLogicWithHeldPermission_canBuy() {
        Title title = normal("或权限",
                List.of(new VaultCondition(BigDecimal.valueOf(80)),
                        new PermissionCondition("title.test")))
                .toBuilder().combineLogic(CombineLogic.OR).build();
        PurchaseSnapshot snapshot = snapshot(true, 685, 0, Set.of("title.test"), Map.of(), false);

        List<String> lore = shopLore(title, snapshot);
        assertEquals("  §f · 权限: §a有", lore.get(7), "持有权限的个人状态行为满足色的「有」");
        assertEquals("§8[§a✔§8] §f点击购买", lore.get(lore.size() - 1));
    }

    /** 商店条目恒无 glint（选中态是选择菜单语义）。 */
    @Test
    void shopItem_neverGlints() {
        Title title = normal("免费测试", List.of());
        PurchaseSnapshot snapshot = snapshot(true, 0, 0, Set.of(), Map.of(), false);
        assertFalse(renderer.shopItem(entry(title, snapshot), BRACKETS).glint());
    }

    /** 渲染商店条目并把 lore 序列化为 § 码字符串列表。 */
    private List<String> shopLore(Title title, PurchaseSnapshot snapshot) {
        return legacy(renderer.shopItem(entry(title, snapshot), BRACKETS).lore());
    }

    /** 组装商店条目视图（资格走真实引擎；物品名映射按需注入）。 */
    private static ShopEntryView entry(Title title, PurchaseSnapshot snapshot) {
        return new ShopEntryView(title, ENGINE.evaluate(title, snapshot), false, snapshot,
                Map.of(BAMBOO64, Component.text("竹子")), null, List.of());
    }

    /** 构造最小合法 NORMAL 称号（名称按写入时归一化规则带 <white> 前缀）。 */
    private static Title normal(String plainName, List<PurchaseCondition> conditions) {
        return Title.builder()
                .name("<white>" + plainName)
                .namePlain(plainName)
                .conditions(conditions)
                .build();
    }

    /** 文档例4「老玩家」称号（总数限定 30，已售份数参数化）。 */
    private static Title veteranTitle(int soldCount) {
        return normal("老玩家", List.of(new VaultCondition(BigDecimal.valueOf(80))))
                .toBuilder().limitMode(LimitMode.TOTAL).totalCap(30).soldCount(soldCount)
                .rarity("史诗").description(List.of("购买了你就是官方认定老玩家")).build();
    }

    /** 构造购买上下文快照（文档基准日 2026.7.22）。 */
    private static PurchaseSnapshot snapshot(boolean economyAvailable, long vaultBalance,
                                             long coinBalance, Set<String> permissions,
                                             Map<String, Integer> itemCounts, boolean owned) {
        return new PurchaseSnapshot(TODAY, economyAvailable, BigDecimal.valueOf(vaultBalance),
                coinBalance, permissions, itemCounts, owned);
    }

    /** 组件列表 → 传统 § 码字符串列表。 */
    private static List<String> legacy(List<Component> lore) {
        return lore.stream().map(LEGACY::serialize).toList();
    }
}
