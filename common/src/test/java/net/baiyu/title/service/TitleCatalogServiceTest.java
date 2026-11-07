package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.PlayerProfileCache.PlayerProfile;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleCatalogService} 单测（按测试策略只跑 H2）：创建归一化落库 +
 * 缓存写穿、名称长度红线、setConditions 不变量、软删后缓存不可见。
 */
class TitleCatalogServiceTest extends BaseRepositoryTest {

    /** 称号目录缓存。 */
    private TitleCatalogCache catalogCache;

    /** 玩家档案缓存。 */
    private PlayerProfileCache profileCache;

    /** 被测服务。 */
    private TitleCatalogService service;

    @BeforeEach
    void setUpService() {
        initDatabase(TestDialect.H2);
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        service = new TitleCatalogService(titleRepository, slotRepository, txRunner,
                dbExecutor, catalogCache, profileCache);
    }

    /** create：三格式名称归一化为 MiniMessage 落库，缓存写穿与 DB 一致。 */
    @Test
    void create_normalizesNameAndWritesThroughCache() {
        Title created = service.create("&c红头&#00ff00绿", TitleCategory.NORMAL, Actor.console()).join();

        assertTrue(created.id() > 0);
        assertEquals("<red>红头<#00ff00>绿", created.name(), "名称应归一化为 MiniMessage");
        assertEquals("红头绿", created.namePlain(), "纯文本列应剥掉全部颜色标签");
        assertFalse(created.visible(), "新建 NORMAL 在配置购买条件前默认隐藏");

        // 缓存写穿
        assertEquals(created, catalogCache.byId(created.id()).orElseThrow());
        assertEquals(List.of(created), service.snapshot());

        // DB 落库一致
        Title persisted = withConnection(c -> titleRepository.loadAll(c)).getFirst();
        assertEquals(created, persisted);

        // 无颜色开头前置 <white>（§1.5.2.2 &f 规则）；GROUP/CUSTOM 默认不上架
        Title custom = service.create("朴素名", TitleCategory.CUSTOM, Actor.console()).join();
        assertEquals("<white>朴素名", custom.name());
        assertFalse(custom.visible(), "CUSTOM 默认不可见");
        assertFalse(custom.tradable(), "CUSTOM 强制不可交易");
    }

    /** 名称剥色后超过 50 码点拒绝创建。 */
    @Test
    void create_rejectsNameOver50CodePoints() {
        String fifty = "称".repeat(50);
        Title ok = service.create("&c" + fifty, TitleCategory.NORMAL, Actor.console()).join();
        assertEquals(fifty, ok.namePlain(), "恰好 50 码点应放行");

        String fiftyOne = "称".repeat(51);
        assertThrows(IllegalArgumentException.class,
                () -> service.create("&c" + fiftyOne, TitleCategory.NORMAL, Actor.console()),
                "51 码点必须拒绝");
        // 颜色码不占码点额度：净长 51 即使带色也拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.create("&#ff0000" + fiftyOne, TitleCategory.NORMAL, Actor.console()));
        // 剥色后为空也拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.create("&c&l", TitleCategory.NORMAL, Actor.console()));
    }

    /** setConditions：NORMAL 正常替换；非 NORMAL 且条件非空强制拒绝（不变量 §十 2/5）。 */
    @Test
    void setConditions_enforcesCategoryInvariant() {
        Title normal = service.create("&a普通", TitleCategory.NORMAL, Actor.console()).join();
        Title updated = service.setConditions(normal.id(), CombineLogic.OR,
                List.of(new CoinCondition(10), new VaultCondition(new BigDecimal("2.50")))).join();
        assertEquals(CombineLogic.OR, updated.combineLogic());
        assertEquals(2, updated.conditions().size());

        // 落库与缓存一致
        Title persisted = withConnection(c -> titleRepository.loadAll(c)).stream()
                .filter(t -> t.id() == normal.id()).findFirst().orElseThrow();
        assertEquals(new HashSet<>(updated.conditions()), new HashSet<>(persisted.conditions()));
        assertEquals(updated, catalogCache.byId(normal.id()).orElseThrow());

        // 自定义称号不允许设置条件
        Title custom = service.create("&b自定义", TitleCategory.CUSTOM, Actor.console()).join();
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.setConditions(custom.id(), CombineLogic.AND,
                        List.of(new CoinCondition(1))).join());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());

        // 重复条件类型由领域不变量拦截
        CompletionException duplicated = assertThrows(CompletionException.class,
                () -> service.setConditions(normal.id(), CombineLogic.AND,
                        List.of(new CoinCondition(1), new CoinCondition(2))).join());
        assertInstanceOf(IllegalArgumentException.class, duplicated.getCause());
    }

    /** 软删：缓存视图不可见、DB 默认装载不含、再编辑被拒绝。 */
    @Test
    void delete_hidesFromCacheAndRejectsFurtherEdits() {
        Title first = service.create("&a保留", TitleCategory.NORMAL, Actor.console()).join();
        Title second = service.create("&c删除", TitleCategory.NORMAL, Actor.console()).join();

        Title deleted = service.delete(second.id()).join();
        assertTrue(deleted.isDeleted());
        assertFalse(deleted.visible(), "软删同时清可见性");

        // 缓存列表视图不可见，byId 仍可取到（供历史渲染取名）
        assertEquals(List.of(first), service.snapshot());
        assertTrue(catalogCache.byId(second.id()).orElseThrow().isDeleted());

        // DB 默认装载不含软删行
        List<Title> visible = withConnection(c -> titleRepository.loadAll(c, false));
        assertEquals(List.of(first), visible);

        // 软删后再编辑以参数异常拒绝
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.rename(second.id(), "&a复活").join());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    /** 软删同事务卸下全部玩家的装扮引用，并同步清除在线档案缓存（§五 delete 契约）。 */
    @Test
    void delete_clearsWardrobeReferencesEverywhere() {
        Title doomed = service.create("&c将删", TitleCategory.NORMAL, Actor.console()).join();
        int titleId = doomed.id();

        // 两名玩家分别以固定栏位与随机池引用该称号
        UUID wearer = UUID.randomUUID();
        UUID pooler = UUID.randomUUID();
        runInTx(c -> {
            slotRepository.upsert(c, wearer, new SlotConfig(1, true, SlotMode.FIXED, titleId));
            slotRepository.upsert(c, pooler, new SlotConfig(2, true, SlotMode.RANDOM, null));
            slotRepository.replacePool(c, pooler, 2, Set.of(titleId));
            return null;
        });
        // 佩戴者在线：档案缓存中同样持有引用
        profileCache.put(new PlayerProfile(wearer, "Wearer", Map.of(),
                List.of(new SlotConfig(1, true, SlotMode.FIXED, titleId)),
                Map.of(2, Set.of(titleId)), 0L));

        service.delete(titleId).join();

        // DB 侧：固定栏位引用置空、随机池删行（保留栏位行本身）
        List<SlotConfig> wearerSlots = withConnection(c -> slotRepository.load(c, wearer));
        assertEquals(1, wearerSlots.size());
        assertNull(wearerSlots.getFirst().titleId(), "固定栏位引用应被置空");
        assertTrue(withConnection(c -> slotRepository.loadPool(c, pooler, 2)).isEmpty(),
                "随机池引用应被删除");

        // 缓存侧：在线档案的栏位与随机池引用同步清除
        PlayerProfile cached = profileCache.get(wearer).orElseThrow();
        assertNull(cached.slots().getFirst().titleId());
        assertTrue(cached.randomPools().get(2).isEmpty());
    }
}
