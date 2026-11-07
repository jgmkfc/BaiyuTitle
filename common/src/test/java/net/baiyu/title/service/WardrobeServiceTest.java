package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.service.WardrobeService.WardrobeResult;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WardrobeService} 单测（按测试策略只跑 H2）：栏位边界、所有权校验、
 * 软删称号拒绝装扮（不变量 §十 13）。
 */
class WardrobeServiceTest extends BaseRepositoryTest {

    /** 测试统一栏位上限。 */
    private static final int MAX_SLOTS = 3;

    /** 称号目录缓存。 */
    private TitleCatalogCache catalogCache;

    /** 玩家档案缓存。 */
    private PlayerProfileCache profileCache;

    /** 被测服务。 */
    private WardrobeService service;

    /** 测试玩家。 */
    private UUID player;

    @BeforeEach
    void setUpService() {
        initDatabase(TestDialect.H2);
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        service = new WardrobeService(ownershipRepository, slotRepository, txRunner,
                dbExecutor, catalogCache, profileCache, ignored -> MAX_SLOTS, null);
        player = UUID.randomUUID();
    }

    /** 落库一个称号、写入缓存并授予玩家 ACTIVE 所有权，返回称号 id。 */
    private int grantOwnedTitle(String name, String plain) {
        return grantOwnedTitle(Title.builder()
                .name(name).namePlain(plain).createdAt(1_000L).updatedAt(1_000L).build());
    }

    /** 落库给定称号、写入缓存并授予玩家 ACTIVE 所有权，返回称号 id。 */
    private int grantOwnedTitle(Title title) {
        int id = runInTx(c -> titleRepository.insert(c, title));
        Title stored = withConnection(c -> titleRepository.loadAll(c, true)).stream()
                .filter(t -> t.id() == id).findFirst().orElseThrow();
        catalogCache.put(stored);
        runInTx(c -> ownershipRepository.insertGrant(c, new Ownership(0, id, player, "Tester",
                GrantSource.ADMIN, null, 1_000L, null, OwnershipStatus.ACTIVE, null, null)));
        return id;
    }

    /** 正常装扮：持有 ACTIVE 所有权即可写入固定栏位并落库。 */
    @Test
    void setSlotTitle_succeedsForOwnedTitle() {
        int titleId = grantOwnedTitle("<white>正常", "正常");
        assertEquals(WardrobeResult.SUCCESS, service.setSlotTitle(player, 1, titleId).join());

        List<SlotConfig> slots = withConnection(c -> slotRepository.load(c, player));
        assertEquals(1, slots.size());
        assertEquals((Integer) titleId, slots.getFirst().titleId());
    }

    /** 栏位越界与未持有称号分别拒绝。 */
    @Test
    void setSlotTitle_rejectsOutOfRangeAndUnowned() {
        int titleId = grantOwnedTitle("<white>合法", "合法");
        assertEquals(WardrobeResult.SLOT_OUT_OF_RANGE, service.setSlotTitle(player, 0, titleId).join());
        assertEquals(WardrobeResult.SLOT_OUT_OF_RANGE,
                service.setSlotTitle(player, MAX_SLOTS + 1, titleId).join());
        // 另一名玩家未持有该称号
        assertEquals(WardrobeResult.TITLE_NOT_OWNED,
                service.setSlotTitle(UUID.randomUUID(), 1, titleId).join());
    }

    /** 软删称号拒绝新装扮：固定栏位与随机池均被挡（不变量 §十 13）。 */
    @Test
    void setSlot_rejectsSoftDeletedTitle() {
        int titleId = grantOwnedTitle("<white>已删", "已删");
        // 模拟软删写穿：缓存中的该称号带 deleted_at（所有权保留不回收）
        Title deleted = catalogCache.byId(titleId).orElseThrow().toBuilder()
                .visible(false).deletedAt(5_000L).build();
        catalogCache.put(deleted);

        assertEquals(WardrobeResult.TITLE_NOT_OWNED, service.setSlotTitle(player, 1, titleId).join(),
                "软删后固定栏位不可再装扮");
        assertEquals(WardrobeResult.TITLE_NOT_OWNED,
                service.setSlotRandom(player, 2, Set.of(titleId)).join(),
                "软删后随机池不可再收录");
        assertTrue(withConnection(c -> slotRepository.load(c, player)).isEmpty(),
                "拒绝路径不得落库任何栏位行");
    }

    /**
     * M1 评审遗留 2 用例：GROUP 称号仍持有 ACTIVE 所有权、但成员资格钩子拒绝
     * （被移出组/失去权限后 reconcile 前的窗口），不可新装扮；资格恢复即放行；
     * NORMAL 称号不受钩子影响。
     */
    @Test
    void setSlotTitle_groupTitleRequiresMembership() {
        int groupTitleId = grantOwnedTitle(Title.builder()
                .name("<white>[ 北海市 ]").namePlain("[ 北海市 ]")
                .category(TitleCategory.GROUP).groupId(1).visible(false)
                .createdAt(1_000L).updatedAt(1_000L).build());
        int normalTitleId = grantOwnedTitle("<white>普通", "普通");

        // 资格钩子恒拒：GROUP 称号被挡（所有权仍 ACTIVE），NORMAL 不受影响
        WardrobeService denying = new WardrobeService(ownershipRepository, slotRepository,
                txRunner, dbExecutor, catalogCache, profileCache, ignored -> MAX_SLOTS,
                (uuid, titleId) -> false);
        assertEquals(WardrobeResult.TITLE_NOT_OWNED,
                denying.setSlotTitle(player, 1, groupTitleId).join(),
                "reconcile 前窗口内不可新装扮组称号");
        assertEquals(WardrobeResult.TITLE_NOT_OWNED,
                denying.setSlotRandom(player, 2, Set.of(groupTitleId)).join(),
                "随机池同样复核组资格");
        assertEquals(WardrobeResult.SUCCESS, denying.setSlotTitle(player, 1, normalTitleId).join(),
                "NORMAL 称号不过组资格钩子");

        // 资格恢复（钩子放行）即可装扮
        WardrobeService allowing = new WardrobeService(ownershipRepository, slotRepository,
                txRunner, dbExecutor, catalogCache, profileCache, ignored -> MAX_SLOTS,
                (uuid, titleId) -> true);
        assertEquals(WardrobeResult.SUCCESS, allowing.setSlotTitle(player, 1, groupTitleId).join());
    }

    /** 清空固定栏位（titleId=null）与停用栏位不受所有权校验限制。 */
    @Test
    void setSlotTitle_allowsClearingWithoutOwnership() {
        int titleId = grantOwnedTitle("<white>可清", "可清");
        assertEquals(WardrobeResult.SUCCESS, service.setSlotTitle(player, 1, titleId).join());

        assertEquals(WardrobeResult.SUCCESS, service.setSlotTitle(player, 1, null).join());
        assertNull(withConnection(c -> slotRepository.load(c, player)).getFirst().titleId());

        assertEquals(WardrobeResult.SUCCESS, service.setSlotEnabled(player, 1, false).join());
        assertFalse(withConnection(c -> slotRepository.load(c, player)).getFirst().enabled());
    }
}
