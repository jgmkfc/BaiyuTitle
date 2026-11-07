package net.baiyu.title.chat;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天展示门面的到期降级纯函数单测（"到期卸下"链路的渲染侧收口）。
 * <p>两级防线：①渲染层按时间戳<b>即时</b>隐藏——所有权
 * {@code expires_at < now} 时视图立刻降级默认称号，不等 ExpiryService 收敛；
 * ②sweep 收敛提交后档案缓存移除所有权并清栏位引用，视图保持默认（终态一致，
 * 无视觉跳变）。本测试只播种两份缓存，零 Bukkit、零数据库。
 */
class ChatDisplayCacheTest {

    /** 被测玩家。 */
    private static final UUID PLAYER = new UUID(3L, 14L);

    /** 被测称号 id。 */
    private static final int TITLE_ID = 7;

    /** 基准时刻。 */
    private static final long NOW = 1_700_000_000_000L;

    private final PlayerProfileCache profileCache = new PlayerProfileCache();
    private final TitleCatalogCache catalogCache = new TitleCatalogCache();
    private final ChatDisplayCache cache = new ChatDisplayCache(profileCache, catalogCache);

    /** 佩戴中的称号未到期：固定栏位视图恰含该候选。 */
    @Test
    void wornTitle_stillValid_showsCandidate() {
        seedCatalog();
        profileCache.put(profileWearing(ownership(NOW + 60_000L)));

        List<ChatDisplayCache.EquippedSlotView> views = cache.equippedSlots(PLAYER, NOW);

        assertEquals(2, views.size(), "固定 + 随机两个启用栏位各产出一个视图");
        assertFalse(views.getFirst().isDefaultTitle());
        assertEquals(TITLE_ID, views.getFirst().candidates().getFirst().id());
        assertEquals(TITLE_ID, views.get(1).candidates().getFirst().id(),
                "随机池中有效称号应保留为候选");
    }

    /** 佩戴中的称号刚过期：sweep 尚未收敛时视图已按时间戳即时降级默认。 */
    @Test
    void wornTitle_expiredByTimestamp_degradesImmediately() {
        seedCatalog();
        profileCache.put(profileWearing(ownership(NOW - 1_000L)));

        List<ChatDisplayCache.EquippedSlotView> views = cache.equippedSlots(PLAYER, NOW);

        assertEquals(2, views.size(), "启用栏位仍产出视图（降级而非消失）");
        assertTrue(views.stream().allMatch(ChatDisplayCache.EquippedSlotView::isDefaultTitle),
                "过期所有权在固定栏位与随机池中均应立即降级默认称号");
    }

    /** sweep 收敛后（缓存移除所有权 + 清栏位引用）：视图保持默认，终态一致。 */
    @Test
    void wornTitle_afterSweepConvergence_staysDefault() {
        seedCatalog();
        profileCache.put(profileWearing(ownership(NOW - 1_000L)));

        // 模拟 ExpiryService.expireOne 提交后的缓存同步（withoutTitle = 移除所有权+清引用）
        profileCache.updateIfPresent(PLAYER, profile -> profile.withoutTitle(TITLE_ID));

        PlayerProfileCache.PlayerProfile converged = profileCache.get(PLAYER).orElseThrow();
        assertTrue(converged.activeOwnerships().isEmpty(), "收敛后所有权应被移除");
        assertEquals(Set.of(), converged.randomPools().get(2), "随机池引用应被清除");
        List<ChatDisplayCache.EquippedSlotView> views = cache.equippedSlots(PLAYER, NOW);
        assertEquals(2, views.stream().filter(ChatDisplayCache.EquippedSlotView::isDefaultTitle)
                .count(), "固定栏位与随机栏位均降级默认（与到期前的即时降级终态一致）");
    }

    // ---------- 播种工具 ----------

    /** 目录缓存放入被测称号（NORMAL / 可见 / 未软删）。 */
    private void seedCatalog() {
        catalogCache.put(Title.builder().id(TITLE_ID)
                .name("<white>[ 国庆 ]").namePlain("[ 国庆 ]")
                .createdAt(1L).updatedAt(1L).build());
    }

    /** 固定栏位 1 佩戴被测称号、随机栏位 2 池含被测称号的档案。 */
    private PlayerProfileCache.PlayerProfile profileWearing(Ownership ownership) {
        return new PlayerProfileCache.PlayerProfile(PLAYER, "Player",
                Map.of(TITLE_ID, ownership),
                List.of(new SlotConfig(1, true, SlotMode.FIXED, TITLE_ID),
                        new SlotConfig(2, true, SlotMode.RANDOM, null)),
                Map.of(2, Set.of(TITLE_ID)), 0);
    }

    /** 构造 ACTIVE 所有权（指定到期时刻）。 */
    private static Ownership ownership(long expiresAt) {
        return new Ownership(1L, TITLE_ID, PLAYER, "Player", GrantSource.PURCHASE, null,
                NOW - 100_000L, expiresAt, OwnershipStatus.ACTIVE, null, null);
    }
}
