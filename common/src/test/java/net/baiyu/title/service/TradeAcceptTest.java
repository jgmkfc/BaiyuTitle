package net.baiyu.title.service;

import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.service.trade.AcceptResult;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TradeService#accept} 单测：成交互换（复制到期时刻）、把关分支、
 * 同款对倒、重启后 PENDING 可接受。
 */
class TradeAcceptTest extends TradeTestSupport {

    /** 成交互换：双方旧行 TRADED、对侧新行 ACTIVE 且复制 expires_at、终态回执。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_exchangesBothSides(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long xExpiry = System.currentTimeMillis() + 7 * 24 * 3_600_000L;
        long ownX = grant(alice, "Alice", titleX, xExpiry);
        long ownY = grant(bob, "Bob", titleY, null);
        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));

        // 总开关关闭时接受被封禁，且交易保持 PENDING
        tradeSettings.enabled = false;
        assertInstanceOf(AcceptResult.Disabled.class,
                service.accept(trade.id(), Actor.of(bob, "Bob")).join());
        assertEquals(TradeStatus.PENDING, tradeStatus(trade.id()));
        tradeSettings.enabled = true;

        AcceptResult result = service.accept(trade.id(), Actor.of(bob, "Bob")).join();
        Trade completed = assertInstanceOf(AcceptResult.Completed.class, result).trade();
        assertEquals(TradeStatus.COMPLETED, completed.status());
        assertEquals("Bob", completed.resolvedBy());

        // 旧行 TRADED，新行 ACTIVE、source=TRADE、source_ref=交易 id、复制到期时刻
        assertEquals("TRADED", ownershipStatus(ownX));
        assertEquals("TRADED", ownershipStatus(ownY));
        Ownership bobsX = withConnection(c ->
                ownershipRepository.findActive(c, bob, titleX)).orElseThrow();
        assertEquals(GrantSource.TRADE, bobsX.source());
        assertEquals(trade.id(), bobsX.sourceRef());
        assertEquals(xExpiry, bobsX.expiresAt(), "剩余持有时间应随实例转移");
        Ownership alicesY = withConnection(c ->
                ownershipRepository.findActive(c, alice, titleY)).orElseThrow();
        assertTrue(alicesY.isPermanent());
        assertEquals(1L, auditCount("TRADE_COMPLETE"));

        // 终态不可再迁出：重复接受 / 二次解决均返回 AlreadyResolved
        assertEquals(TradeStatus.COMPLETED, assertInstanceOf(AcceptResult.AlreadyResolved.class,
                service.accept(trade.id(), Actor.of(bob, "Bob")).join()).status());
    }

    /** 把关分支：未知交易、非目标方、过期报价（顺手迁 EXPIRED）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_guardBranches(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        long ownX = grant(alice, "Alice", titleX, null);

        assertInstanceOf(AcceptResult.UnknownTrade.class,
                service.accept(404L, Actor.of(bob, "Bob")).join());

        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of());
        // 发起方不能接受自己的报价
        assertInstanceOf(AcceptResult.NotTarget.class,
                service.accept(trade.id(), Actor.of(alice, "Alice")).join());
        assertEquals(TradeStatus.PENDING, tradeStatus(trade.id()));

        // 过期报价接受时顺手收敛为 EXPIRED，标的不动
        long expiredOffer = runInTx(c -> tradeRepository.insert(c,
                new Trade(0, alice, "Alice", bob, "Bob", TradeStatus.PENDING,
                        1_000L, 2_000L, null, null, null, List.of()), List.of()));
        assertInstanceOf(AcceptResult.Expired.class,
                service.accept(expiredOffer, Actor.of(bob, "Bob")).join());
        assertEquals(TradeStatus.EXPIRED, tradeStatus(expiredOffer));
        assertEquals("ACTIVE", ownershipStatus(ownX));
    }

    /** 同款对倒：双方各持同一称号并互换，两阶段交割不撞唯一约束。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_sameTitleSwapSucceeds(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        long aliceX = grant(alice, "Alice", titleX, null);
        long bobX = grant(bob, "Bob", titleX, System.currentTimeMillis() + 3_600_000L);
        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(aliceX, titleX)), List.of(new OfferItem(bobX, titleX)));

        assertInstanceOf(AcceptResult.Completed.class,
                service.accept(trade.id(), Actor.of(bob, "Bob")).join());

        assertEquals("TRADED", ownershipStatus(aliceX));
        assertEquals("TRADED", ownershipStatus(bobX));
        // 双方各得对方那份（Alice 拿到限时那份、Bob 拿到永久那份）
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, alice, titleX))
                .orElseThrow().expiresAt() != null);
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, bob, titleX))
                .orElseThrow().isPermanent());
    }

    /** 重启后 PENDING 可接受：全新服务实例直读数据库完成成交（等价模拟重启）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_pendingSurvivesRestart(TestDialect dialect) {
        initDatabase(dialect);
        TradeService before = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long ownX = grant(alice, "Alice", titleX, null);
        long ownY = grant(bob, "Bob", titleY, null);
        Trade trade = createPending(before, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));

        // "重启"：新缓存从 DB 重载、新服务实例，不共享旧实例任何内存状态
        TradeService after = newIndependentService();
        assertEquals(List.of(trade.id()),
                after.pendingFor(bob).join().stream().map(Trade::id).toList(),
                "重启后 PENDING 报价应保留");
        assertInstanceOf(AcceptResult.Completed.class,
                after.accept(trade.id(), Actor.of(bob, "Bob")).join());
        assertEquals("TRADED", ownershipStatus(ownX));
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, bob, titleX)).isPresent());
    }
}
