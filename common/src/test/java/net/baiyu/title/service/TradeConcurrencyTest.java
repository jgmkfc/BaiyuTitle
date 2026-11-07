package net.baiyu.title.service;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.service.trade.AcceptResult;
import net.baiyu.title.service.trade.OfferItem;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 并发接受仅一方成功测试。
 * <p>DbExecutor 写线程串行是<b>实现机制</b>，本测试刻意从外部 2 个业务线程经
 * 栅栏同时提交 {@link TradeService#accept}，只验证<b>最终不变量</b>：
 * 恰一次 Completed、恰一次 AlreadyResolved(COMPLETED)，交割恰好发生一次。
 */
class TradeConcurrencyTest extends TradeTestSupport {

    /** 双线程栅栏齐射同一笔报价：恰一成交一败退，所有权互换恰好一次。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void concurrentAccept_exactlyOneWins(TestDialect dialect) throws Exception {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long ownX = grant(alice, "Alice", titleX, null);
        long ownY = grant(bob, "Bob", titleY, null);
        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));

        List<AcceptResult> results = race(service, trade.id(), bob);

        assertEquals(1, results.stream()
                        .filter(AcceptResult.Completed.class::isInstance).count(),
                "恰一方成交");
        AcceptResult.AlreadyResolved loser = results.stream()
                .filter(AcceptResult.AlreadyResolved.class::isInstance)
                .map(AcceptResult.AlreadyResolved.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("败方应拿到 AlreadyResolved"));
        assertEquals(TradeStatus.COMPLETED, loser.status());

        // 交割恰好一次：旧行各一条 TRADED、新行各一条、无重复授予
        assertEquals("TRADED", ownershipStatus(ownX));
        assertEquals("TRADED", ownershipStatus(ownY));
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM " + tables.titleOwnership()
                + " WHERE \"source\"='TRADE' AND \"source_ref\"=" + trade.id()));
        assertEquals(TradeStatus.COMPLETED, tradeStatus(trade.id()));
        assertEquals(1L, auditCount("TRADE_COMPLETE"));
        assertInstanceOf(AcceptResult.AlreadyResolved.class,
                service.accept(trade.id(), Actor.of(bob, "Bob")).join(), "事后补刀同样败退");
    }

    /** 栅栏齐射：2 个外部业务线程同时提交 accept，收集全部结果。 */
    private List<AcceptResult> race(TradeService service, long tradeId, UUID acceptor)
            throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier gate = new CyclicBarrier(2);
            List<Future<AcceptResult>> futures = new ArrayList<>(2);
            for (int i = 0; i < 2; i++) {
                futures.add(racers.submit(() -> {
                    gate.await();
                    return service.accept(tradeId, Actor.of(acceptor, "Bob")).join();
                }));
            }
            List<AcceptResult> results = new ArrayList<>(2);
            for (Future<AcceptResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            racers.shutdownNow();
        }
    }
}
