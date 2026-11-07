package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.gui.shop.ShopDetailView;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.gui.shop.ShopFilter;
import net.baiyu.title.gui.shop.ShopSort;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.EligibilityEngine;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商店查询门面纯函数单测（M4；筛选 / 排序语义对照业务设计
 * §1.3.2.3.1.2/3）：目录与档案缓存显式播种、商店上下文直接注入，
 * 只驱动 {@code queryShop} / {@code detail} 读路径——零 Bukkit、零 DB。
 */
class ShopQueryServiceTest {

    /** 固定基准日。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 22);

    /** 被测玩家。 */
    private static final UUID PLAYER = new UUID(11L, 77L);

    /** 条件物品序列化串样例。 */
    private static final String BAMBOO64 = "BAMBOO-BASE64";

    /** 稀有度顺序表（config 默认低→高）。 */
    private static final List<String> RARITIES =
            List.of("Timoris", "Amoris", "Doloris", "Mortis", "Oblivionis");

    /** 称号目录缓存（逐测试播种）。 */
    private final TitleCatalogCache catalogCache = new TitleCatalogCache();

    /** 玩家档案缓存（逐测试播种）。 */
    private final PlayerProfileCache profileCache = new PlayerProfileCache();

    /** 被测门面（经济门面用降级实现占位——查询路径只消费注入的上下文）。 */
    private final ShopQueryService service = new ShopQueryService(
            catalogCache, profileCache, new EligibilityEngine(), new NoEconomyGateway(),
            material -> Component.text(material.name()), () -> RARITIES, () -> TODAY);

    /** 默认上下文：金币 100 / 称号币 100、无权限、无物品、经济可用。 */
    private ShopQueryService.ShopContext context;

    @BeforeEach
    void seed() {
        // id / createdAt：1 免费(1000)、2 金币80(2000, Amoris)、3 称号币200(3000, 已拥有+装扮)、
        // 4 物品(4000)、5 权限(5000, Timoris)、6 隐藏、7 窗口已过(7000)、8 售罄(8000)、
        // 9 羁绊(9000)、10 总数未满(10000, 热度 9)
        catalogCache.replaceAll(List.of(
                normal(1, 1_000L, List.of()),
                normal(2, 2_000L, List.of(new VaultCondition(BigDecimal.valueOf(80))))
                        .toBuilder().rarity("Amoris").soldCount(3).build(),
                normal(3, 3_000L, List.of(new CoinCondition(200)))
                        .toBuilder().soldCount(5).build(),
                normal(4, 4_000L, List.of(new ItemCondition(BAMBOO64, 32))),
                normal(5, 5_000L, List.of(new PermissionCondition("title.test")))
                        .toBuilder().rarity("Timoris").build(),
                normal(6, 6_000L, List.of()).toBuilder().visible(false).build(),
                normal(7, 7_000L, List.of()).toBuilder().limitMode(LimitMode.WINDOW)
                        .windowStart(LocalDate.of(2025, 9, 30))
                        .windowEnd(LocalDate.of(2025, 10, 7)).build(),
                normal(8, 8_000L, List.of(new VaultCondition(BigDecimal.valueOf(80))))
                        .toBuilder().limitMode(LimitMode.TOTAL).totalCap(1).soldCount(1).build(),
                normal(9, 9_000L, List.of()).toBuilder().category(TitleCategory.BOND)
                        .bondRequirements(Set.of(1, 3)).build(),
                normal(10, 10_000L, List.of(new CoinCondition(50)))
                        .toBuilder().limitMode(LimitMode.TOTAL).totalCap(30).soldCount(9).build()));
        profileCache.put(new PlayerProfileCache.PlayerProfile(PLAYER, "Tester",
                Map.of(3, new Ownership(3, 3, PLAYER, "Tester", GrantSource.ADMIN, null,
                        1_000L, null, OwnershipStatus.ACTIVE, null, null)),
                List.of(new SlotConfig(1, true, SlotMode.FIXED, 3)),
                Map.of(), 100L));
        context = new ShopQueryService.ShopContext(
                new PurchaseSnapshot(TODAY, true, BigDecimal.valueOf(100), 100L,
                        Set.of(), Map.of(BAMBOO64, 0), false),
                Map.of(BAMBOO64, Component.text("竹子")));
    }

    /** 默认查询：隐藏称号剔除，其余按时间新→老（createdAt 降序）。 */
    @Test
    void queryShop_defaultSort_excludesHiddenAndSortsNewestFirst() {
        List<Integer> ids = ids(query(ShopFilter.DEFAULT, ShopSort.DEFAULT));
        assertEquals(List.of(10, 9, 8, 7, 5, 4, 3, 2, 1), ids);
        assertFalse(ids.contains(6), "隐藏称号不进商店列表");
    }

    /** 时间翻转：老→新。 */
    @Test
    void queryShop_timeAscending_oldestFirst() {
        assertEquals(List.of(1, 2, 3, 4, 5, 7, 8, 9, 10),
                ids(query(ShopFilter.DEFAULT, ShopSort.DEFAULT.flipped())));
    }

    /** 获取方式筛选：类型包含匹配，与组合逻辑无关；羁绊按种类。 */
    @Test
    void queryShop_acquireFilters() {
        assertEquals(List.of(7, 1), ids(query(filterAcquire(ShopFilter.AcquireType.FREE),
                ShopSort.DEFAULT)), "免费=条件空集（含窗口已过的免费称号）");
        assertEquals(List.of(8, 2), ids(query(filterAcquire(ShopFilter.AcquireType.VAULT),
                ShopSort.DEFAULT)));
        assertEquals(List.of(10, 3), ids(query(filterAcquire(ShopFilter.AcquireType.COIN),
                ShopSort.DEFAULT)));
        assertEquals(List.of(4), ids(query(filterAcquire(ShopFilter.AcquireType.ITEM),
                ShopSort.DEFAULT)));
        assertEquals(List.of(5), ids(query(filterAcquire(ShopFilter.AcquireType.PERMISSION),
                ShopSort.DEFAULT)));
        assertEquals(List.of(9), ids(query(filterAcquire(ShopFilter.AcquireType.BOND),
                ShopSort.DEFAULT)));
    }

    /** 购买状态筛选：已拥有 / 未拥有 / 能购买 / 不能购买（以资格三分支裁决）。 */
    @Test
    void queryShop_ownStateFilters() {
        assertEquals(List.of(3), ids(query(filterOwn(ShopFilter.OwnState.OWNED), ShopSort.DEFAULT)));
        assertFalse(ids(query(filterOwn(ShopFilter.OwnState.NOT_OWNED), ShopSort.DEFAULT))
                .contains(3));
        // 能购买：1 免费、2 金币(余额100≥80)、10 称号币(100≥50 且总数未满)
        assertEquals(List.of(10, 2, 1),
                ids(query(filterOwn(ShopFilter.OwnState.CAN_BUY), ShopSort.DEFAULT)));
        // 不能购买：9 羁绊种类、8 售罄、7 窗口外、5 权限未持有、4 物品不足
        assertEquals(List.of(9, 8, 7, 5, 4),
                ids(query(filterOwn(ShopFilter.OwnState.CANNOT_BUY), ShopSort.DEFAULT)));
    }

    /** 稀有度筛选：名称精确匹配。 */
    @Test
    void queryShop_rarityFilter() {
        ShopFilter filter = new ShopFilter(ShopFilter.AcquireType.ALL, ShopFilter.OwnState.ALL,
                "Timoris", ShopFilter.ActivityState.ALL);
        assertEquals(List.of(5), ids(query(filter, ShopSort.DEFAULT)));
    }

    /** 活动状态筛选：窗口已过 / 总数未满 / 已售罄。 */
    @Test
    void queryShop_activityFilters() {
        assertEquals(List.of(7), ids(query(filterActivity(ShopFilter.ActivityState.WINDOW_ENDED),
                ShopSort.DEFAULT)));
        assertEquals(List.of(), ids(query(filterActivity(ShopFilter.ActivityState.WINDOW_ACTIVE),
                ShopSort.DEFAULT)));
        assertEquals(List.of(10), ids(query(filterActivity(ShopFilter.ActivityState.TOTAL_OPEN),
                ShopSort.DEFAULT)));
        assertEquals(List.of(8), ids(query(filterActivity(ShopFilter.ActivityState.SOLD_OUT),
                ShopSort.DEFAULT)));
    }

    /** 价格排序低→高：免费恒最前，有价按标尺升序，无价方式恒最后（组内时间新→老）。 */
    @Test
    void queryShop_priceAscending() {
        List<Integer> ids = ids(query(ShopFilter.DEFAULT, new ShopSort(ShopSort.Key.PRICE, true)));
        // 免费组（7,1 按新→老）→ 有价组 50(10) < 80(8) < 80(2) < 200(3) → 无价组（9,5,4 新→老）
        assertEquals(List.of(7, 1, 10, 8, 2, 3, 9, 5, 4), ids);
    }

    /** 价格排序高→低：两端组不翻转，仅有价组内反向。 */
    @Test
    void queryShop_priceDescending_keepsEndGroups() {
        List<Integer> ids = ids(query(ShopFilter.DEFAULT, new ShopSort(ShopSort.Key.PRICE, false)));
        assertEquals(List.of(7, 1, 3, 8, 2, 10, 9, 5, 4), ids);
    }

    /** 热度排序高→低（默认方向）：按已售份数。 */
    @Test
    void queryShop_popularityDescending() {
        List<Integer> ids = ids(query(ShopFilter.DEFAULT,
                new ShopSort(ShopSort.Key.POPULARITY, false)));
        // 热度：10(9) > 3(5) > 2(3) > 8(1) > 其余 0（按新→老：9,7,5,4,1）
        assertEquals(List.of(10, 3, 2, 8, 9, 7, 5, 4, 1), ids);
    }

    /** 稀有度排序低→高：按 config 列表位置，无稀有度恒最后（组内时间新→老）。 */
    @Test
    void queryShop_rarityAscending_unknownLast() {
        List<Integer> ids = ids(query(ShopFilter.DEFAULT, new ShopSort(ShopSort.Key.RARITY, true)));
        assertEquals(List.of(5, 2), ids.subList(0, 2), "Timoris(5) 在 Amoris(2) 之前");
        assertEquals(List.of(10, 9, 8, 7, 4, 3, 1), ids.subList(2, ids.size()),
                "无稀有度组恒最后并按时间新→老");
        List<Integer> flipped = ids(query(ShopFilter.DEFAULT,
                new ShopSort(ShopSort.Key.RARITY, false)));
        assertEquals(List.of(2, 5), flipped.subList(0, 2), "翻转后 Amoris 在前");
        assertEquals(List.of(10, 9, 8, 7, 4, 3, 1), flipped.subList(2, flipped.size()),
                "无稀有度组不随方向翻转");
    }

    /** 条目视图：已拥有 → Owned + 装扮态；物品名子集只携带本称号条件映射。 */
    @Test
    void queryShop_entryViewCarriesOwnershipAndItemNames() {
        Map<Integer, ShopEntryView> byId = query(ShopFilter.DEFAULT, ShopSort.DEFAULT).stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.title().id(), e -> e));
        assertInstanceOf(Eligibility.Owned.class, byId.get(3).eligibility());
        assertTrue(byId.get(3).equipped(), "栏位固定引用计为已装扮");
        assertTrue(byId.get(3).snapshot().alreadyOwned());
        assertEquals(Map.of(BAMBOO64, Component.text("竹子")), byId.get(4).itemNames());
        assertEquals(Map.of(), byId.get(2).itemNames(), "无物品条件的条目不携带物品名映射");
    }

    /** 羁绊条目：需求视图按 id 升序、名称取目录、持有态取所有权。 */
    @Test
    void queryShop_bondRequirementViews() {
        ShopEntryView bond = query(filterAcquire(ShopFilter.AcquireType.BOND), ShopSort.DEFAULT)
                .get(0);
        assertEquals(List.of(
                        new ShopEntryView.BondRequirementView("<white>称号1", false),
                        new ShopEntryView.BondRequirementView("<white>称号3", true)),
                bond.bondRequirements());
    }

    /** 详情：存在返回条目视图；软删 / 不存在为空。 */
    @Test
    void detail_returnsEntryOrEmpty() {
        Optional<ShopDetailView> detail = service.detail(PLAYER, context, 2);
        assertTrue(detail.isPresent());
        assertEquals(2, detail.orElseThrow().entry().title().id());
        assertTrue(service.detail(PLAYER, context, 999).isEmpty());
    }

    /** 执行查询。 */
    private List<ShopEntryView> query(ShopFilter filter, ShopSort sort) {
        return service.queryShop(PLAYER, context, filter, sort);
    }

    /** 条目 → 称号 id 列表。 */
    private static List<Integer> ids(List<ShopEntryView> entries) {
        return entries.stream().map(entry -> entry.title().id()).toList();
    }

    /** 仅获取方式维度的筛选。 */
    private static ShopFilter filterAcquire(ShopFilter.AcquireType acquire) {
        return new ShopFilter(acquire, ShopFilter.OwnState.ALL, null, ShopFilter.ActivityState.ALL);
    }

    /** 仅购买状态维度的筛选。 */
    private static ShopFilter filterOwn(ShopFilter.OwnState own) {
        return new ShopFilter(ShopFilter.AcquireType.ALL, own, null, ShopFilter.ActivityState.ALL);
    }

    /** 仅活动状态维度的筛选。 */
    private static ShopFilter filterActivity(ShopFilter.ActivityState activity) {
        return new ShopFilter(ShopFilter.AcquireType.ALL, ShopFilter.OwnState.ALL, null, activity);
    }

    /** 构造最小合法 NORMAL 称号。 */
    private static Title normal(int id, long createdAt, List<PurchaseCondition> conditions) {
        return Title.builder()
                .id(id)
                .name("<white>称号" + id)
                .namePlain("称号" + id)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .conditions(conditions)
                .build();
    }
}
