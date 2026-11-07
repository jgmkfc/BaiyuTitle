package net.baiyu.title.placeholder;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatDisplayCache;
import net.baiyu.title.chat.ChatDisplayService;
import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PAPI 变量内部解析器逐变量纯函数单测。
 *
 * <p>缓存快照、时钟、随机源与语言函数全部显式注入，零 Bukkit 零 DB IO；
 * 覆盖：全变量取值、多类型称号每类各 +1、随机栏"随机(池大小)"格式、
 * 到期/软删条目剔除、离线空值语义与模板 token 展开。</p>
 */
class InternalPlaceholderResolverTest {

    /** 在线玩家。 */
    private static final UUID PLAYER = new UUID(1L, 1L);

    /** 离线玩家（档案未挂载）。 */
    private static final UUID OFFLINE = new UUID(2L, 2L);

    /** 判定基准时刻。 */
    private static final long NOW = 100_000L;

    /** 展示格式样例（括号段带空格；默认称号自带括号）。 */
    private static final ChatDisplayService.DisplayFormat FORMAT =
            new ChatDisplayService.DisplayFormat("<gray>[ ", "<gray> ]", " ", " ", "<white>[新人]");

    private final PlayerProfileCache profileCache = new PlayerProfileCache();
    private final TitleCatalogCache catalogCache = new TitleCatalogCache();

    /** 被测解析器（语言函数用映射桩）。 */
    private final InternalPlaceholderResolver resolver = new InternalPlaceholderResolver(
            profileCache, catalogCache, new ChatDisplayCache(profileCache, catalogCache),
            () -> FORMAT, InternalPlaceholderResolverTest::rawLang);

    @BeforeEach
    void seed() {
        catalogCache.replaceAll(List.of(
                title(1, "<red>剑圣", TitleCategory.NORMAL, List.of(), null),
                // 多类型称号：金币 + 称号币 双条件，vault 与 coin 计数各 +1
                title(2, "<yellow>诗人", TitleCategory.NORMAL,
                        List.of(new VaultCondition(BigDecimal.TEN), new CoinCondition(5L)), null),
                title(3, "collector", TitleCategory.NORMAL,
                        List.of(new ItemCondition("QUJD", 3)), null),
                title(4, "elite", TitleCategory.NORMAL,
                        List.of(new PermissionCondition("byt.test")), null),
                title(5, "guild", TitleCategory.GROUP, List.of(), null),
                title(6, "bond", TitleCategory.BOND, List.of(), null),
                // 已软删：即便持有也不计数、不展示
                title(7, "ghost", TitleCategory.NORMAL, List.of(), 50_000L),
                title(8, "expired", TitleCategory.NORMAL, List.of(), null)));
        Map<Integer, Ownership> owned = new HashMap<>();
        for (int titleId = 1; titleId <= 7; titleId++) {
            owned.put(titleId, ownership(titleId, null));
        }
        owned.put(8, ownership(8, NOW - 1)); // 已到期未收敛：渲染与计数即时剔除
        profileCache.put(new PlayerProfileCache.PlayerProfile(PLAYER, "Tester", owned,
                List.of(new SlotConfig(1, true, SlotMode.FIXED, 1),
                        new SlotConfig(2, true, SlotMode.RANDOM, null),
                        new SlotConfig(3, false, SlotMode.FIXED, null)),
                Map.of(2, Set.of(1, 2, 8)),
                123L, 4, 4, 2));
    }

    /** 称号币 / 交易计数 / 栏位总数：直读档案快照字段。 */
    @Test
    void scalarFieldsComeFromProfileSnapshot() {
        assertEquals("123", resolve("player_coin"));
        assertEquals("4", resolve("player_title_slots"));
        assertEquals("4", resolve("player_trade_initiated"));
        assertEquals("2", resolve("player_trade_completed"));
    }

    /** 称号总数：ACTIVE 未到期且未软删才计入（软删 7 与到期 8 剔除）。 */
    @Test
    void amountCountsOnlyValidOwnerships() {
        assertEquals("6", resolve("player_title_amount"));
    }

    /** 分类计数：多类型称号每命中一类各 +1（称号 2 同时计入 vault 与 coin）。 */
    @Test
    void amountByTypeCountsEachMatchingType() {
        assertEquals("1", resolve("player_title_amount_free"));
        assertEquals("1", resolve("player_title_amount_vault"));
        assertEquals("1", resolve("player_title_amount_coin"));
        assertEquals("1", resolve("player_title_amount_item"));
        assertEquals("1", resolve("player_title_amount_permission"));
        assertEquals("1", resolve("player_title_amount_group"));
        assertEquals("1", resolve("player_title_amount_bond"));
    }

    /** 已启用栏位数：停用栏不计；上限内未配置的栏位按默认启用计入。 */
    @Test
    void enabledSlotsCountDefaultsUnconfiguredToEnabled() {
        assertEquals("3", resolve("player_title_slots_enabled"));
    }

    /** 逐栏位展示：固定栏 = 括号单元；随机栏 = 随机(有效池大小)；停用栏 = lang 文案。 */
    @Test
    void perSlotDisplayCoversAllSlotStates() {
        assertEquals("<gray>[ <reset><red>剑圣<reset><gray> ]", resolve("player_title_using_1"));
        assertEquals("随机(2)", resolve("player_title_using_2"), "池含到期称号 8，应剔除后计 2");
        assertEquals("已禁用", resolve("player_title_using_3"));
        assertEquals("<white>[新人]", resolve("player_title_using_4"), "上限内未配置栏位 = 默认称号");
        assertEquals("", resolve("player_title_using_5"), "超出上限为空");
    }

    /** 拼接预览：启用栏位以分隔段连接；随机栏取样恰一次；停用栏不出现。 */
    @Test
    void usingPreviewJoinsEnabledSlots() {
        String preview = resolver.resolve(PLAYER, "player_title_using", NOW, new FixedRandom(1))
                .orElseThrow();
        assertEquals("<gray>[ <reset><red>剑圣<reset><gray> ] "
                + "<gray>[ <reset><yellow>诗人<reset><gray> ]", preview);
    }

    /** 离线玩家：计数类 0、展示类空串（业务离线语义）。 */
    @Test
    void offlinePlayerDegradesToZeroAndEmpty() {
        assertEquals("0", resolveFor(OFFLINE, "player_coin"));
        assertEquals("0", resolveFor(OFFLINE, "player_title_amount"));
        assertEquals("0", resolveFor(OFFLINE, "player_title_amount_free"));
        assertEquals("0", resolveFor(OFFLINE, "player_title_slots"));
        assertEquals("0", resolveFor(OFFLINE, "player_title_slots_enabled"));
        assertEquals("0", resolveFor(OFFLINE, "player_trade_initiated"));
        assertEquals("", resolveFor(OFFLINE, "player_title_using"));
        assertEquals("", resolveFor(OFFLINE, "player_title_using_1"));
    }

    /** 未知变量：返回空（PAPI 按变量未解析呈现）。 */
    @Test
    void unknownParamsResolveEmpty() {
        assertTrue(resolver.resolve(PLAYER, "bogus", NOW, new FixedRandom(0)).isEmpty());
        assertTrue(resolver.resolve(PLAYER, "player_title_amount_bogus", NOW, new FixedRandom(0)).isEmpty());
        assertTrue(resolver.resolve(PLAYER, "player_title_using_x", NOW, new FixedRandom(0)).isEmpty());
        assertTrue(resolver.resolve(PLAYER, "player_title_using_0", NOW, new FixedRandom(0)).isEmpty());
    }

    /** 模板展开：命中 token 替换为变量值，未知 token 原样保留。 */
    @Test
    void expandReplacesKnownTokensAndKeepsUnknown() {
        assertEquals("币:123 数:6 未知:%byt_nope%",
                resolver.expand("币:%byt_player_coin% 数:%byt_player_title_amount% 未知:%byt_nope%",
                        PLAYER));
    }

    // ---- 构造器与桩 ----

    private String resolve(String params) {
        return resolveFor(PLAYER, params);
    }

    private String resolveFor(UUID player, String params) {
        return resolver.resolve(player, params, NOW, new FixedRandom(0)).orElseThrow();
    }

    /** 语言原文桩（papi.* 键）。 */
    private static String rawLang(String key) {
        return switch (key) {
            case "papi.random" -> "随机(<count>)";
            case "papi.slot-disabled" -> "已禁用";
            default -> key;
        };
    }

    /** 构造 ACTIVE 所有权（expiresAt 可空 = 永久）。 */
    private static Ownership ownership(int titleId, Long expiresAt) {
        return new Ownership(titleId, titleId, PLAYER, "Tester", GrantSource.ADMIN, null,
                1_000L, expiresAt, OwnershipStatus.ACTIVE, null, null);
    }

    /** 构造最小合法称号（种类 / 条件 / 软删可配）。 */
    private static Title title(int id, String name, TitleCategory category,
                               List<PurchaseCondition> conditions, Long deletedAt) {
        return new Title(id, name, name, category, CombineLogic.AND, true, true,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, deletedAt, conditions, Set.of());
    }

    /** 固定随机源：nextInt(bound) 恒返回给定值（越界时取 bound-1）。 */
    private record FixedRandom(int value) implements RandomGenerator {

        @Override
        public int nextInt(int bound) {
            return Math.min(value, bound - 1);
        }

        @Override
        public long nextLong() {
            return value;
        }
    }
}
