package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.purchase.BeginResult;
import net.baiyu.title.service.purchase.CompleteResult;
import net.baiyu.title.service.purchase.PaymentOutcome;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期复购计份语义测试（人工验收 E："过期复购计份至售罄"的自动化对应）。
 * <p>业务语义唯一来源 Baiyu Title.md §1.1.2.5：
 * 「总数按购买份数算，即同个玩家可以多次购买（例如有`持有有效期`的称号可以在
 * 玩家的称号过期后再次购买）」且「达到最大总数后禁止购买，但仍然保留在商店菜单中」。
 * <p>推论：到期收敛（EXPIRED）与主动收回（REVOKED）
 * 都<b>不释放</b> sold_count——卖出过就计份；唯一释放通道是订单 REFUNDED。
 */
class ExpiryRepurchaseTest extends BaseRepositoryTest {

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;

    /** cap=3：买 → 到期收敛 → 复购成功；sold_count 累计 2（旧份额不释放）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void expiredRepurchase_accumulatesSoldCount(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService();
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(cappedTitle(3));

        buyAndFulfil(service, player, titleId);
        assertEquals(1L, soldCount(titleId));
        expireAndSweep(titleId);

        // 复购成功：新订单 + 新 ACTIVE 所有权；sold_count 累计 2
        buyAndFulfil(service, player, titleId);
        assertEquals(2L, soldCount(titleId), "过期复购按购买份数累计计份");
        assertEquals(1L, ownershipCount(titleId,"EXPIRED"));
        assertEquals(1L, ownershipCount(titleId,"ACTIVE"));
    }

    /** cap=1：买 → 到期收敛 → 复购被 SOLD_OUT 拒绝（到期不释放份额，计份至售罄）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void capOne_expiredRepurchase_rejectedSoldOut(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService();
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(cappedTitle(1));

        buyAndFulfil(service, player, titleId);
        expireAndSweep(titleId);

        // 业务 §1.1.2.5「达到最大总数后禁止购买」——总数按购买份数算，到期不退份
        assertEquals(BeginResult.RejectReason.SOLD_OUT,
                assertInstanceOf(BeginResult.Rejected.class, service.beginPurchase(
                        player, "Alice", titleId, null, snapshot()).join()).reason());
        assertEquals(1L, soldCount(titleId), "到期收敛不得释放 sold_count");
    }

    /** cap=1：买 → 管理员收回（REVOKED）→ 复购同样 SOLD_OUT（收回也不释放份额）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void capOne_revokedRepurchase_rejectedSoldOut(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService();
        OwnershipService ownershipService = new OwnershipService(ownershipRepository,
                slotRepository, auditRepository, txRunner, dbExecutor, catalogCache,
                profileCache, null);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(cappedTitle(1));

        buyAndFulfil(service, player, titleId);
        assertTrue(ownershipService.revoke(player, titleId, "ADMIN_TEST",
                Actor.of(UUID.randomUUID(), "Admin")).join(), "收回应成功");
        assertEquals(1L, ownershipCount(titleId,"REVOKED"));

        assertEquals(BeginResult.RejectReason.SOLD_OUT,
                assertInstanceOf(BeginResult.Rejected.class, service.beginPurchase(
                        player, "Alice", titleId, null, snapshot()).join()).reason());
        assertEquals(1L, soldCount(titleId), "主动收回不得释放 sold_count（仅 REFUNDED 释放）");
    }

    // ---------- 工具 ----------

    /** 构造购买服务（同时新建两个缓存实例）。 */
    private PurchaseService newService() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        return new PurchaseService(orderRepository, titleRepository, ownershipRepository,
                coinRepository, auditRepository, txRunner, dbExecutor, catalogCache, profileCache,
                null, RecoveryPolicy.GRANT, PluginConfig.CustomTitleSettings::new);
    }

    /** 免费 + 总数限定 + 30 天持有有效期的称号（复购场景底座）。 */
    private static Title cappedTitle(int cap) {
        return Title.builder().name("<white>[ 限量 ]").namePlain("[ 限量 ]")
                .limitMode(LimitMode.TOTAL).totalCap(cap).holdDurationDays(30)
                .createdAt(1L).updatedAt(1L).build();
    }

    /** 完整走一遍 begin → completePayment(ok) 履约链。 */
    private void buyAndFulfil(PurchaseService service, UUID player, int titleId) {
        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(begun.order().id(), PaymentOutcome.ok()).join());
    }

    /** 把该称号 ACTIVE 所有权的到期时刻改到过去并跑一轮 sweep 收敛为 EXPIRED。 */
    private void expireAndSweep(int titleId) {
        long past = System.currentTimeMillis() - 60_000L;
        runInTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + tables.titleOwnership()
                    + " SET \"expires_at\"=? WHERE \"title_id\"=? AND \"status\"='ACTIVE'")) {
                ps.setLong(1, past);
                ps.setInt(2, titleId);
                ps.executeUpdate();
            }
            return null;
        });
        ExpiryService expiryService = new ExpiryService(ownershipRepository, slotRepository,
                auditRepository, txRunner, dbExecutor, profileCache);
        assertEquals(1, expiryService.sweep().join(), "应恰收敛一条过期所有权");
        assertEquals(1L, ownershipCount(titleId,"EXPIRED"));
    }

    /** 读称号 sold_count。 */
    private long soldCount(int titleId) {
        return scalarLong("SELECT \"sold_count\" FROM " + tables.title() + " WHERE \"id\"=" + titleId);
    }

    /** 按状态统计该称号的所有权行数（每测试单玩家，无须再按玩家过滤）。 */
    private long ownershipCount(int titleId, String status) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.titleOwnership()
                + " WHERE \"title_id\"=" + titleId + " AND \"status\"='" + status + "'");
    }

    /** 落库并刷入目录缓存，返回称号 id。 */
    private int seedTitle(Title title) {
        int id = runInTx(c -> titleRepository.insert(c, title));
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
        return id;
    }

    /** 空快照（今天、无权限、无物品）。 */
    private static PurchaseSnapshot snapshot() {
        return new PurchaseSnapshot(LocalDate.now(), true, BigDecimal.ZERO, 0,
                Set.of(), Map.of(), false);
    }
}
