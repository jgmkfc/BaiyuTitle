package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.redeem.RedeemResult;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedeemService} 双方言测试：兑换码原子递减（并发栅栏恰
 * maxUses 成）、每玩家限次、有效期、禁用、称号卡单次核销（并发恰一成）、
 * holdDaysOverride 优先。
 */
class RedeemServiceTest extends BaseRepositoryTest {

    /** 测试管理员。 */
    private static final Actor ADMIN = new Actor(UUID.randomUUID(), "Admin");

    /** 一天的毫秒数。 */
    private static final long DAY_MILLIS = 86_400_000L;

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;
    private RedeemService service;

    /** 正常链：大小写归一、授予（source=REDEEM_CODE、按称号 holdDays）、留使用记录。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCode_happyPath(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(7);
        RedeemCode code = service.createCode("Vip2026", titleId, 0, 2, null, null, ADMIN).join();
        assertEquals("VIP2026", code.code(), "码面创建时大写归一化");
        UUID player = UUID.randomUUID();

        long before = System.currentTimeMillis();
        RedeemResult.Redeemed redeemed = assertInstanceOf(RedeemResult.Redeemed.class,
                service.redeemCode(player, "Alice", "  vip2026 ").join());
        assertEquals(titleId, redeemed.titleId());
        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, player, titleId)).orElseThrow();
        assertEquals(GrantSource.REDEEM_CODE, ownership.source());
        long sevenDays = 7L * DAY_MILLIS;
        assertTrue(ownership.expiresAt() >= before + sevenDays
                        && ownership.expiresAt() <= System.currentTimeMillis() + sevenDays,
                "按称号 holdDays 落到期时刻");
        assertEquals(1L, usedCount(code.id()));
        assertEquals(1L, countRows(tables.redeemCodeUse()));

        // 已持有期间再兑：拒绝且零消耗
        assertEquals(RedeemResult.RejectReason.ALREADY_OWNED, rejectReason(
                service.redeemCode(player, "Alice", "VIP2026").join()));
        assertEquals(1L, usedCount(code.id()), "拒绝路径不得消耗用量");
    }

    /** 每玩家限次：失去所有权后复兑仍受 perPlayerUses 约束。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCode_perPlayerLimit(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(0);
        service.createCode("ONCE", titleId, 0, 1, null, null, ADMIN).join();
        UUID player = UUID.randomUUID();
        assertInstanceOf(RedeemResult.Redeemed.class,
                service.redeemCode(player, "Alice", "ONCE").join());

        // 收回所有权后再兑：ALREADY_OWNED 不再命中，轮到每玩家限次拒绝
        runInTx(c -> {
            Ownership active = ownershipRepository.findActive(c, player, titleId).orElseThrow();
            ownershipRepository.endActive(c, active.id(), OwnershipStatus.REVOKED, "TEST", 2L);
            return null;
        });
        assertEquals(RedeemResult.RejectReason.CODE_PLAYER_LIMIT, rejectReason(
                service.redeemCode(player, "Alice", "ONCE").join()));
        // 其他玩家不受影响
        assertInstanceOf(RedeemResult.Redeemed.class,
                service.redeemCode(UUID.randomUUID(), "Bob", "ONCE").join());
    }

    /** 有效期与禁用：未生效/已过期/已停用/不存在分别拒绝，均零消耗。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCode_validityAndDisable(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(0);
        long now = System.currentTimeMillis();
        service.createCode("FUTURE", titleId, 0, 1, now + DAY_MILLIS, null, ADMIN).join();
        service.createCode("PAST", titleId, 0, 1, null, now - DAY_MILLIS, ADMIN).join();
        RedeemCode disabled = service.createCode("OFF", titleId, 0, 1, null, null, ADMIN).join();
        assertTrue(service.disableCode("off", ADMIN).join());
        UUID player = UUID.randomUUID();

        assertEquals(RedeemResult.RejectReason.CODE_OUTSIDE_VALIDITY, rejectReason(
                service.redeemCode(player, "Alice", "FUTURE").join()));
        assertEquals(RedeemResult.RejectReason.CODE_OUTSIDE_VALIDITY, rejectReason(
                service.redeemCode(player, "Alice", "PAST").join()));
        assertEquals(RedeemResult.RejectReason.CODE_DISABLED, rejectReason(
                service.redeemCode(player, "Alice", "OFF").join()));
        assertEquals(RedeemResult.RejectReason.CODE_NOT_FOUND, rejectReason(
                service.redeemCode(player, "Alice", "MISSING").join()));
        assertEquals(0L, usedCount(disabled.id()));
        assertEquals(0L, countRows(tables.redeemCodeUse()));
        assertEquals(0L, countRows(tables.titleOwnership()));
    }

    /** 并发原子递减：maxUses=3，8 玩家栅栏齐射，恰 3 人成功、其余 EXHAUSTED。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCode_concurrentConsumption_exactlyMaxUses(TestDialect dialect) throws Exception {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(0);
        RedeemCode code = service.createCode("RACE", titleId, 3, 1, null, null, ADMIN).join();

        List<RedeemResult> results = race(8, () -> {
            UUID player = UUID.randomUUID();
            return service.redeemCode(player, "Racer", "RACE").join();
        });

        long won = results.stream().filter(RedeemResult.Redeemed.class::isInstance).count();
        long exhausted = results.stream()
                .filter(r -> r instanceof RedeemResult.Rejected rejected
                        && rejected.reason() == RedeemResult.RejectReason.CODE_EXHAUSTED)
                .count();
        assertEquals(3, won, "恰 maxUses 人成功");
        assertEquals(5, exhausted, "其余全部 EXHAUSTED");
        assertEquals(3L, usedCount(code.id()), "used_count 恰等于成功数（不变量 §十 14）");
        assertEquals(3L, countRows(tables.redeemCodeUse()));
        assertEquals(3L, countRows(tables.titleOwnership()));
    }

    /** 称号卡：单次核销、holdDaysOverride=0 覆写为永久、回填 ownership_id。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCard_singleUseAndHoldOverride(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(30);
        RedeemCard card = service.issueCard(titleId, 0, ADMIN).join();
        UUID player = UUID.randomUUID();

        RedeemResult.Redeemed redeemed = assertInstanceOf(RedeemResult.Redeemed.class,
                service.redeemCard(player, "Alice", card.cardUuid()).join());
        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, player, titleId)).orElseThrow();
        assertEquals(GrantSource.REDEEM_CARD, ownership.source());
        assertNull(ownership.expiresAt(), "holdDaysOverride=0 优先于称号默认 30 天，落永久");
        RedeemCard stored = withConnection(c ->
                redeemRepository.findCard(c, card.cardUuid())).orElseThrow();
        assertTrue(stored.isRedeemed());
        assertEquals(player, stored.redeemedBy());
        assertEquals((Long) redeemed.ownershipId(), stored.ownershipId(), "核销必须回填所有权 id");

        // 二次核销拒绝
        assertEquals(RedeemResult.RejectReason.CARD_ALREADY_REDEEMED, rejectReason(
                service.redeemCard(UUID.randomUUID(), "Bob", card.cardUuid()).join()));
        assertEquals(RedeemResult.RejectReason.CARD_NOT_FOUND, rejectReason(
                service.redeemCard(player, "Alice", UUID.randomUUID()).join()));
    }

    /** 称号卡并发核销：4 玩家栅栏齐射同一张卡，恰一人成功。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void redeemCard_concurrentRedeem_exactlyOne(TestDialect dialect) throws Exception {
        initDatabase(dialect);
        newService();
        int titleId = seedTitle(0);
        RedeemCard card = service.issueCard(titleId, null, ADMIN).join();

        List<RedeemResult> results = race(4, () ->
                service.redeemCard(UUID.randomUUID(), "Racer", card.cardUuid()).join());

        assertEquals(1, results.stream().filter(RedeemResult.Redeemed.class::isInstance).count(),
                "单次核销恰一人成功（不变量 §十 14）");
        assertEquals(3, results.stream()
                .filter(r -> r instanceof RedeemResult.Rejected rejected
                        && rejected.reason() == RedeemResult.RejectReason.CARD_ALREADY_REDEEMED)
                .count());
        assertEquals(1L, countRows(tables.titleOwnership()));
    }

    // ---------- 工具 ----------

    /** 构造服务（同时新建两个缓存实例）。 */
    private void newService() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        service = new RedeemService(redeemRepository, ownershipRepository, auditRepository,
                txRunner, dbExecutor, catalogCache, profileCache, null);
    }

    /** 落库称号（带持有有效期天数）并刷缓存。 */
    private int seedTitle(int holdDays) {
        int id = runInTx(c -> titleRepository.insert(c, Title.builder()
                .name("<white>兑换奖品").namePlain("兑换奖品").holdDurationDays(holdDays)
                .createdAt(1L).updatedAt(1L).build()));
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
        return id;
    }

    /** 栅栏齐射：n 个外部业务线程同时执行兑换动作，收集全部结果。 */
    private List<RedeemResult> race(int racers, Supplier<RedeemResult> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CyclicBarrier gate = new CyclicBarrier(racers);
            List<Future<RedeemResult>> futures = new ArrayList<>(racers);
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return action.get();
                }));
            }
            List<RedeemResult> results = new ArrayList<>(racers);
            for (Future<RedeemResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 断言为拒绝并取原因。 */
    private static RedeemResult.RejectReason rejectReason(RedeemResult result) {
        return assertInstanceOf(RedeemResult.Rejected.class, result).reason();
    }

    /** 读兑换码已用次数。 */
    private long usedCount(long codeId) {
        return scalarLong("SELECT \"used_count\" FROM " + tables.redeemCode()
                + " WHERE \"id\"=" + codeId);
    }
}
