package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 所有权服务管理授予测试（人工验收 E："管理员窗口外可给予"的自动化对应）。
 * <p>业务语义唯一来源 Baiyu Title.md §1.1.2.5：
 * 「管理员直接给予不算，`获取窗口`外管理员也可以正常给予活动限定称号」。
 * 设计 01 进一步裁决：管理员 player give 无视窗口/总数（不产生订单、不占 sold_count）。
 * 因此 {@link OwnershipService#grant} 刻意<b>不做</b>窗口/售罄校验——本测试锁定该行为。
 */
class OwnershipServiceTest extends BaseRepositoryTest {

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;

    /** 窗口早已结束的活动限定称号：ADMIN 授予仍成功（绕过商店规则）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void adminGrant_outsideWindow_succeeds(TestDialect dialect) {
        initDatabase(dialect);
        OwnershipService service = newService();
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(Title.builder().name("<white>[ 国庆 ]").namePlain("[ 国庆 ]")
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.now().minusDays(30))
                .windowEnd(LocalDate.now().minusDays(10))
                .createdAt(1L).updatedAt(1L).build());

        OwnershipService.GrantResult.Granted granted = assertInstanceOf(
                OwnershipService.GrantResult.Granted.class,
                service.grant(player, "Alice", titleId, GrantSource.ADMIN, null, null,
                        Actor.of(UUID.randomUUID(), "Admin")).join());

        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, player, titleId)).orElseThrow();
        assertEquals(granted.ownershipId(), ownership.id());
        assertEquals(GrantSource.ADMIN, ownership.source());
        assertNull(ownership.expiresAt(), "未指定到期时刻 = 永久持有");
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='GRANT'"), "管理授予必须落审计");
    }

    /** 已售罄的总数限定称号：ADMIN 授予仍成功，且不占用 sold_count。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void adminGrant_soldOutTotal_succeedsWithoutReserving(TestDialect dialect) {
        initDatabase(dialect);
        OwnershipService service = newService();
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(Title.builder().name("<white>[ 老玩家 ]").namePlain("[ 老玩家 ]")
                .limitMode(LimitMode.TOTAL).totalCap(1).soldCount(1)
                .createdAt(1L).updatedAt(1L).build());

        assertInstanceOf(OwnershipService.GrantResult.Granted.class,
                service.grant(player, "Alice", titleId, GrantSource.ADMIN, null, null,
                        Actor.of(UUID.randomUUID(), "Admin")).join());

        assertEquals(1L, scalarLong("SELECT \"sold_count\" FROM " + tables.title()
                + " WHERE \"id\"=" + titleId), "管理授予不产生订单、不占 sold_count");
        assertEquals(1L, withConnection(c ->
                        ownershipRepository.findActiveByOwner(c, player)).size(),
                "授予应落一条 ACTIVE 所有权");
    }

    // ---------- 工具 ----------

    /** 构造所有权服务（同时新建两个缓存实例）。 */
    private OwnershipService newService() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        return new OwnershipService(ownershipRepository, slotRepository, auditRepository,
                txRunner, dbExecutor, catalogCache, profileCache, null);
    }

    /** 落库并刷入目录缓存，返回称号 id。 */
    private int seedTitle(Title title) {
        int id = runInTx(c -> titleRepository.insert(c, title));
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
        return id;
    }
}
