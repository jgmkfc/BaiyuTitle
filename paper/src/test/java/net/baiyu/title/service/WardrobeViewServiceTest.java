package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装扮视图门面读路径单测。
 *
 * <p>只驱动纯缓存读路径 {@code getView} / {@code ownedTitles}——两份缓存
 * 显式播种、装扮服务用抛错桩兜底（读路径不应触碰任何仓储 / 事务），
 * 不触碰 Bukkit。</p>
 */
class WardrobeViewServiceTest {

    /** 展示时区固定 UTC（到期毫秒 → 日期换算可复现）。 */
    private static final ZoneId UTC = ZoneOffset.UTC;

    /** 被测玩家。 */
    private static final UUID PLAYER = new UUID(7L, 42L);

    /** 共享执行器（读路径不触碰它；类结束统一关闭）。 */
    private static final DbExecutor DB_EXECUTOR = new DbExecutor();

    /** 玩家档案缓存（逐测试播种）。 */
    private final PlayerProfileCache profileCache = new PlayerProfileCache();

    /** 称号目录缓存（逐测试播种）。 */
    private final TitleCatalogCache catalogCache = new TitleCatalogCache();

    /** 被测门面（栏位上限解析器固定 3）。 */
    private final WardrobeViewService service = new WardrobeViewService(
            stubWardrobeService(), profileCache, catalogCache, uuid -> 3, UTC);

    @AfterAll
    static void shutdownExecutor() {
        DB_EXECUTOR.shutdown(Duration.ofSeconds(3));
    }

    /** 档案未挂载：栏位按默认补齐（启用 + 固定 + 默认称号），持有列表为空。 */
    @Test
    void getView_withoutProfile_fillsDefaultSlots() {
        WardrobeView view = service.getView(PLAYER, 3);

        assertEquals(3, view.maxSlots());
        assertEquals(3, view.slots().size());
        for (int i = 0; i < 3; i++) {
            WardrobeView.SlotView slot = view.slots().get(i);
            assertEquals(i + 1, slot.slotNo());
            assertTrue(slot.enabled(), "无记录栏位默认启用");
            assertEquals(SlotMode.FIXED, slot.mode());
            assertNull(slot.fixedTitle(), "无记录栏位应展示默认称号");
        }
        assertTrue(view.ownedTitles().isEmpty());
    }

    /** 固定栏位：有效引用解析出称号；到期所有权 / 软删称号引用降级为默认（null）。 */
    @Test
    void getView_fixedSlot_degradesInvalidReferences() {
        long now = System.currentTimeMillis();
        catalogCache.replaceAll(List.of(title(1, "竹子测试"), title(2, "乙"), deletedTitle(3, "已删")));
        profileCache.put(new PlayerProfileCache.PlayerProfile(PLAYER, "Tester",
                Map.of(1, ownership(1, null),
                        2, ownership(2, now - 1_000L),   // 已到期
                        3, ownership(3, null)),           // 称号已软删
                List.of(new SlotConfig(1, true, SlotMode.FIXED, 1),
                        new SlotConfig(2, false, SlotMode.FIXED, 2),
                        new SlotConfig(3, true, SlotMode.FIXED, 3)),
                Map.of(), 0L));

        WardrobeView view = service.getView(PLAYER, 3);

        assertEquals(1, view.slots().get(0).fixedTitle().id());
        assertFalse(view.slots().get(1).enabled(), "停用状态原样透传");
        assertNull(view.slots().get(1).fixedTitle(), "到期所有权引用降级默认称号");
        assertNull(view.slots().get(2).fixedTitle(), "软删称号引用降级默认称号");
    }

    /** 随机栏位：候选池只留仍有效项且升序；无所有权 / 到期 / 软删项剔除。 */
    @Test
    void getView_randomSlot_filtersInvalidPoolEntries() {
        long now = System.currentTimeMillis();
        catalogCache.replaceAll(List.of(title(1, "甲"), title(2, "乙"), deletedTitle(4, "已删")));
        profileCache.put(new PlayerProfileCache.PlayerProfile(PLAYER, "Tester",
                Map.of(1, ownership(1, null),
                        2, ownership(2, now - 1_000L),
                        4, ownership(4, null)),
                List.of(new SlotConfig(1, true, SlotMode.RANDOM, null)),
                Map.of(1, Set.of(4, 2, 1, 99)), 0L));

        WardrobeView view = service.getView(PLAYER, 1);
        WardrobeView.SlotView slot = view.slots().get(0);

        assertTrue(slot.isRandom());
        assertEquals(List.of(1), List.copyOf(slot.randomPool()),
                "池中仅保留 ACTIVE 未到期且未软删的称号（99 无所有权、2 到期、4 软删）");
    }

    /** 持有列表：按称号 id 升序；到期 / 软删项不出现；栏位引用标记正确。 */
    @Test
    void ownedTitles_sortsAndFlagsReferences() {
        long now = System.currentTimeMillis();
        catalogCache.replaceAll(List.of(
                title(5, "戊"), title(2, "乙"), title(8, "辛"), deletedTitle(6, "已删")));
        profileCache.put(new PlayerProfileCache.PlayerProfile(PLAYER, "Tester",
                Map.of(5, ownership(5, null),
                        2, ownership(2, epochOf(2027, 5, 14)),
                        8, ownership(8, now - 1_000L),   // 已到期 → 不出现
                        6, ownership(6, null)),           // 软删 → 不出现
                List.of(new SlotConfig(1, true, SlotMode.FIXED, 5),
                        new SlotConfig(2, true, SlotMode.RANDOM, null)),
                Map.of(2, Set.of(2)), 0L));

        List<WardrobeView.OwnedTitle> owned = service.ownedTitles(PLAYER);

        assertEquals(List.of(2, 5), owned.stream().map(o -> o.title().id()).toList(),
                "按称号 id 升序，且到期 / 软删项不出现");
        assertTrue(owned.get(0).referencedAnywhere(), "随机池成员计为已被引用");
        assertTrue(owned.get(1).referencedAnywhere(), "固定栏位引用计为已被引用");
        assertEquals(LocalDate.of(2027, 5, 14), owned.get(0).expiresOn(),
                "到期毫秒按展示时区换算为日期");
        assertNull(owned.get(1).expiresOn(), "永久持有 expiresOn 为 null");
    }

    /** 到期日正午（UTC）的 epoch 毫秒（避开日界，换算日期稳定）。 */
    private static long epochOf(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(UTC).toInstant().toEpochMilli()
                + 12L * 60 * 60 * 1000; // 正午，避开日界
    }

    /** 构造 ACTIVE 所有权（expiresAt 可空 = 永久）。 */
    private static Ownership ownership(int titleId, Long expiresAt) {
        return new Ownership(titleId, titleId, PLAYER, "Tester", GrantSource.ADMIN, null,
                1_000L, expiresAt, OwnershipStatus.ACTIVE, null, null);
    }

    /** 构造最小合法称号。 */
    private static Title title(int id, String name) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, true,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, null,
                List.<PurchaseCondition>of(), Set.of());
    }

    /** 构造软删称号。 */
    private static Title deletedTitle(int id, String name) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, true,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, 2_000L,
                List.<PurchaseCondition>of(), Set.of());
    }

    /** 抛错桩装扮服务：读路径断言零仓储 / 零事务触碰。 */
    private WardrobeService stubWardrobeService() {
        return new WardrobeService(new ThrowingOwnershipRepository(), new ThrowingSlotRepository(),
                new TransactionRunner(() -> {
                    throw new SQLException("视图读路径不应触碰数据库连接");
                }),
                DB_EXECUTOR, catalogCache, profileCache, uuid -> 3, null);
    }

    /** 所有权仓储抛错桩。 */
    private static final class ThrowingOwnershipRepository implements OwnershipRepository {
        @Override
        public long insertGrant(Connection c, Ownership o) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public boolean endActive(Connection c, long ownershipId, OwnershipStatus to,
                                 String reason, long now) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public List<Ownership> findActiveByOwner(Connection c, UUID owner) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public Optional<Ownership> findActive(Connection c, UUID owner, int titleId) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public List<Ownership> pickExpired(Connection c, long now, int batch) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public List<UUID> findOwnersHavingAll(Connection c, Set<Integer> titleIds) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }

        @Override
        public int countDistinctBuyers(Connection c, int titleId) {
            throw new UnsupportedOperationException("读路径不应触碰所有权仓储");
        }
    }

    /** 装扮栏位仓储抛错桩。 */
    private static final class ThrowingSlotRepository implements SlotRepository {
        @Override
        public List<SlotConfig> load(Connection c, UUID player) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }

        @Override
        public void upsert(Connection c, UUID player, SlotConfig slot) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }

        @Override
        public void clearTitleEverywhere(Connection c, UUID player, int titleId) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }

        @Override
        public void clearTitleForAllPlayers(Connection c, int titleId) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }

        @Override
        public Set<Integer> loadPool(Connection c, UUID player, int slotIndex) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }

        @Override
        public void replacePool(Connection c, UUID player, int slotIndex, Set<Integer> titleIds) {
            throw new UnsupportedOperationException("读路径不应触碰栏位仓储");
        }
    }
}
