package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 登录预载并发时序测试（契约锁定）。
 *
 * <p>契约：join 预载的"DB 读取 + {@link PlayerProfileCache#put}"必须放进
 * <b>同一个</b> {@code DbExecutor.submitWrite} 任务内串行执行（paper 侧
 * {@code PlayerLifecycleService.handleJoin} 即此实现的镜像）。写线程单线程
 * 串行化保证：与增量更新（如授予）以任意顺序交错提交时，最终缓存快照
 * 总与 DB 一致——预载不可能用陈旧快照覆盖后提交的增量。</p>
 */
class PreloadTimingTest extends BaseRepositoryTest {

    /** 预载与增量授予以两种顺序交错提交，最终缓存都必须同时反映两者。 */
    @Test
    void preloadAndIncrementalGrant_interleaved_cacheStaysConsistent() {
        initDatabase(TestDialect.H2);
        TitleCatalogCache titleCache = new TitleCatalogCache();
        PlayerProfileCache profileCache = new PlayerProfileCache();
        titleCache.replaceAll(List.of(title(1, "甲"), title(2, "乙"), title(3, "丙")));
        OwnershipService ownershipService = new OwnershipService(
                ownershipRepository, slotRepository, auditRepository,
                txRunner, dbExecutor, titleCache, profileCache, null);
        UUID player = UUID.randomUUID();

        // 基线：玩家已持有称号 1
        ownershipService.grant(player, "P", 1, GrantSource.ADMIN, null, null, Actor.console()).join();

        // 交错一：预载先提交、授予 2 紧随其后（不等待彼此）——
        // 预载快照虽不含 2，但授予的缓存增量在同一写线程上后序应用
        CompletableFuture<Void> preload1 = simulateJoinPreload(profileCache, player);
        CompletableFuture<OwnershipService.GrantResult> grant2 = ownershipService.grant(
                player, "P", 2, GrantSource.ADMIN, null, null, Actor.console());
        CompletableFuture.allOf(preload1, grant2).join();
        assertInstanceOf(OwnershipService.GrantResult.Granted.class, grant2.join());
        assertEquals(Set.of(1, 2), ownedTitleIds(profileCache, player),
                "预载不得用陈旧快照覆盖后提交的授予增量");

        // 交错二：授予 3 先提交、预载紧随其后——预载读取的 DB 已含 3
        CompletableFuture<OwnershipService.GrantResult> grant3 = ownershipService.grant(
                player, "P", 3, GrantSource.ADMIN, null, null, Actor.console());
        CompletableFuture<Void> preload2 = simulateJoinPreload(profileCache, player);
        CompletableFuture.allOf(grant3, preload2).join();
        assertEquals(Set.of(1, 2, 3), ownedTitleIds(profileCache, player),
                "预载整体替换快照时必须包含此前一切已提交增量");
    }

    /**
     * 模拟 join 预载：与 paper 侧 PlayerLifecycleService.handleJoin 相同的
     * 关键结构——读 DB 与挂载缓存在同一个 submitWrite 任务内完成。
     */
    private CompletableFuture<Void> simulateJoinPreload(PlayerProfileCache profileCache, UUID player) {
        return dbExecutor.submitWrite(() -> {
            PlayerProfileCache.PlayerProfile profile = txRunner.withConnection(c -> {
                Map<Integer, Ownership> byTitleId = new HashMap<>();
                for (Ownership ownership : ownershipRepository.findActiveByOwner(c, player)) {
                    byTitleId.put(ownership.titleId(), ownership);
                }
                List<SlotConfig> slots = slotRepository.load(c, player);
                return new PlayerProfileCache.PlayerProfile(player, "P", byTitleId, slots, Map.of(), 0L);
            });
            profileCache.put(profile);
            return null;
        });
    }

    /** 缓存中该玩家的 ACTIVE 称号 id 集合。 */
    private static Set<Integer> ownedTitleIds(PlayerProfileCache cache, UUID player) {
        return cache.get(player).orElseThrow().activeOwnerships().keySet();
    }

    /** 构造最小合法称号（目录缓存用）。 */
    private static Title title(int id, String name) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, true,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, null,
                List.<PurchaseCondition>of(), Set.of());
    }
}
