package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.CoinCondition;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 限量抢购并发防超卖测试（"多线程 H2 抢购不超卖"）。
 * <p>DbExecutor 写线程串行是<b>实现机制</b>，本测试刻意从外部 8 个业务线程
 * 经栅栏同时提交 {@link PurchaseService#beginPurchase}，只验证<b>最终不变量</b>：
 * 占位订单总数（非 REFUNDED 态）≤ cap、sold_count 恰等于占位数、失败者拿到
 * SOLD_OUT / PENDING_ORDER_EXISTS 拒绝、失败者称号币零损失。
 */
class PurchaseConcurrencyTest extends BaseRepositoryTest {

    /** 抢购称号单价（称号币）。 */
    private static final long PRICE = 100L;

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;

    /** cap=1：4 玩家 × 各双击 2 次 = 8 并发；恰一人占到，双击者拿防重拒绝。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void capOne_eightConcurrentAttempts_exactlyOneReserves(TestDialect dialect) throws Exception {
        initDatabase(dialect);
        PurchaseService service = newService();
        int titleId = seedTitle(cappedTitle(1));
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        players.forEach(p -> seedCoins(p, PRICE));
        List<UUID> attempts = new ArrayList<>(players);
        attempts.addAll(players);

        List<BeginResult> results = race(service, titleId, attempts);

        List<BeginResult.Begun> begun = results.stream()
                .filter(BeginResult.Begun.class::isInstance)
                .map(BeginResult.Begun.class::cast).toList();
        assertEquals(1, begun.size(), "cap=1 只允许一单占位");
        Map<BeginResult.RejectReason, Long> rejects = results.stream()
                .filter(BeginResult.Rejected.class::isInstance)
                .map(BeginResult.Rejected.class::cast)
                .collect(Collectors.groupingBy(BeginResult.Rejected::reason, Collectors.counting()));
        // 胜者的另一次点击撞防重护栏（existsNonTerminal 先于占位判定），其余 6 次售罄
        assertEquals(Map.of(BeginResult.RejectReason.SOLD_OUT, 6L,
                        BeginResult.RejectReason.PENDING_ORDER_EXISTS, 1L), rejects,
                "失败者只允许 SOLD_OUT / PENDING_ORDER_EXISTS 两种拒绝");
        assertEquals(1L, soldCount(titleId), "sold_count 恰等于占位数");
        assertEquals(1L, occupyingOrders(titleId), "非 REFUNDED 订单总数不得超 cap");
        UUID winner = begun.getFirst().order().playerUuid();
        for (UUID player : players) {
            assertEquals(player.equals(winner) ? 0L : PRICE, balance(player),
                    "失败者称号币必须零损失");
        }

        // 履约后占位保持不变（终态 FULFILLED 计入售卖份数）
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(begun.getFirst().order().id(), PaymentOutcome.ok()).join());
        assertEquals(1L, soldCount(titleId));
    }

    /** cap=3：8 个不同玩家并发；恰 3 单占位，REFUNDED 是唯一释放通道且释放后可补位。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void capThree_eightRacers_threeReserve_refundReleasesSeat(TestDialect dialect) throws Exception {
        initDatabase(dialect);
        PurchaseService service = newService();
        int titleId = seedTitle(cappedTitle(3));
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID player = UUID.randomUUID();
            seedCoins(player, PRICE);
            players.add(player);
        }

        List<BeginResult> results = race(service, titleId, players);

        List<BeginResult.Begun> begun = results.stream()
                .filter(BeginResult.Begun.class::isInstance)
                .map(BeginResult.Begun.class::cast).toList();
        assertEquals(3, begun.size(), "cap=3 恰 3 单占位");
        assertEquals(5, results.stream()
                        .filter(r -> r instanceof BeginResult.Rejected rejected
                                && rejected.reason() == BeginResult.RejectReason.SOLD_OUT).count(),
                "其余 5 人全部 SOLD_OUT");
        assertEquals(3L, soldCount(titleId));
        assertEquals(3L, occupyingOrders(titleId));
        Set<UUID> winners = begun.stream()
                .map(b -> b.order().playerUuid()).collect(Collectors.toSet());
        for (UUID player : players) {
            assertEquals(winners.contains(player) ? 0L : PRICE, balance(player),
                    "只有占位者被扣币");
        }

        // 支付失败 → REFUNDED 释放名额（唯一释放通道六），后来者补位至再次售罄
        assertInstanceOf(CompleteResult.Refunded.class, service.completePayment(
                begun.getFirst().order().id(), PaymentOutcome.failed("VAULT_SHORT")).join());
        assertEquals(2L, soldCount(titleId), "REFUNDED 应释放一个名额");
        UUID latecomer = UUID.randomUUID();
        seedCoins(latecomer, PRICE);
        assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(latecomer, "Late", titleId, null, snapshot()).join(),
                "释放后的名额可被后来者占用");
        assertEquals(3L, soldCount(titleId));
        UUID beyondCap = UUID.randomUUID();
        seedCoins(beyondCap, PRICE);
        assertEquals(BeginResult.RejectReason.SOLD_OUT,
                assertInstanceOf(BeginResult.Rejected.class, service.beginPurchase(
                        beyondCap, "Beyond", titleId, null, snapshot()).join()).reason());
    }

    // ---------- 工具 ----------

    /** 栅栏齐射：n 个外部业务线程同时提交 beginPurchase，收集全部结果。 */
    private List<BeginResult> race(PurchaseService service, int titleId, List<UUID> buyers)
            throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(buyers.size());
        try {
            CyclicBarrier gate = new CyclicBarrier(buyers.size());
            List<Future<BeginResult>> futures = new ArrayList<>(buyers.size());
            for (UUID buyer : buyers) {
                futures.add(racers.submit(() -> {
                    gate.await();
                    return service.beginPurchase(buyer, "Racer", titleId, null, snapshot()).join();
                }));
            }
            List<BeginResult> results = new ArrayList<>(futures.size());
            for (Future<BeginResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            racers.shutdownNow();
        }
    }

    /** 构造服务（同时新建两个缓存实例）。 */
    private PurchaseService newService() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        return new PurchaseService(orderRepository, titleRepository, ownershipRepository,
                coinRepository, auditRepository, txRunner, dbExecutor, catalogCache, profileCache,
                null, RecoveryPolicy.GRANT, PluginConfig.CustomTitleSettings::new);
    }

    /** 总数限定的称号币计价称号。 */
    private static Title cappedTitle(int cap) {
        return Title.builder().name("<white>[ 限量 ]").namePlain("[ 限量 ]")
                .conditions(List.of(new CoinCondition(PRICE)))
                .limitMode(LimitMode.TOTAL).totalCap(cap)
                .createdAt(1L).updatedAt(1L).build();
    }

    /** 落库并刷入目录缓存，返回称号 id。 */
    private int seedTitle(Title title) {
        int id = runInTx(c -> titleRepository.insert(c, title));
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
        return id;
    }

    /** 直接铺底称号币余额（种子数据，不留流水）。 */
    private void seedCoins(UUID player, long amount) {
        runInTx(c -> {
            coinRepository.ensureRow(c, player, "Seed", 1L);
            coinRepository.tryAdd(c, player, amount);
            return null;
        });
    }

    /** 占位订单数：非 REFUNDED 态订单总数（sold_count 对账口径十 4）。 */
    private long occupyingOrders(int titleId) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.purchaseOrder()
                + " WHERE \"title_id\"=" + titleId + " AND \"state\" <> 'REFUNDED'");
    }

    /** 读称号 sold_count。 */
    private long soldCount(int titleId) {
        return scalarLong("SELECT \"sold_count\" FROM " + tables.title() + " WHERE \"id\"=" + titleId);
    }

    /** 读玩家称号币余额。 */
    private long balance(UUID player) {
        return withConnection(c -> coinRepository.getBalance(c, player));
    }

    /** 空快照（今天在窗口内、无权限、无物品）。 */
    private static PurchaseSnapshot snapshot() {
        return new PurchaseSnapshot(LocalDate.now(), true, BigDecimal.ZERO, 0,
                Set.of(), Map.of(), false);
    }
}
