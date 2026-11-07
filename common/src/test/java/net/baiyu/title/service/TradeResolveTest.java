package net.baiyu.title.service;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.service.trade.ResolveResult;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 拒绝/撤回/管理员销毁与过期扫描单测：状态机全边迁移收口
 * （PENDING→REJECTED/DESTROYED/EXPIRED；终态不可再迁出），标的全程不动。
 */
class TradeResolveTest extends TradeTestSupport {

    /** 拒绝限目标方、撤回限发起方、管理员不限；成功迁移后标的仍 ACTIVE。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void resolve_rolesAndTerminalStates(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        long ownX = grant(alice, "Alice", titleX, null);
        List<OfferItem> mine = List.of(new OfferItem(ownX, titleX));

        // 拒绝：发起方越权 → NotParticipant；目标方 → REJECTED
        Trade first = createPending(service, alice, "Alice", bob, "Bob", mine, List.of());
        assertInstanceOf(ResolveResult.NotParticipant.class,
                service.reject(first.id(), Actor.of(alice, "Alice")).join());
        Trade rejected = assertInstanceOf(ResolveResult.Resolved.class,
                service.reject(first.id(), Actor.of(bob, "Bob")).join()).trade();
        assertEquals(TradeStatus.REJECTED, rejected.status());
        assertEquals("Bob", rejected.resolvedBy());

        // 撤回：目标方越权 → NotParticipant；发起方 → DESTROYED（fail_reason=CANCELLED）
        Trade second = createPending(service, alice, "Alice", bob, "Bob", mine, List.of());
        assertInstanceOf(ResolveResult.NotParticipant.class,
                service.cancel(second.id(), Actor.of(bob, "Bob")).join());
        Trade cancelled = assertInstanceOf(ResolveResult.Resolved.class,
                service.cancel(second.id(), Actor.of(alice, "Alice")).join()).trade();
        assertEquals(TradeStatus.DESTROYED, cancelled.status());
        assertEquals("CANCELLED", cancelled.failReason());

        // 管理员销毁：非交易方也可
        Trade third = createPending(service, alice, "Alice", bob, "Bob", mine, List.of());
        Trade destroyed = assertInstanceOf(ResolveResult.Resolved.class,
                service.adminCancel(third.id(), Actor.console()).join()).trade();
        assertEquals(TradeStatus.DESTROYED, destroyed.status());
        assertEquals("ADMIN_CANCEL", destroyed.failReason());

        // 终态不可再迁出（不变量 §十 8）
        assertEquals(TradeStatus.REJECTED, assertInstanceOf(ResolveResult.AlreadyResolved.class,
                service.reject(first.id(), Actor.of(bob, "Bob")).join()).status());
        assertEquals(TradeStatus.DESTROYED, assertInstanceOf(ResolveResult.AlreadyResolved.class,
                service.cancel(second.id(), Actor.of(alice, "Alice")).join()).status());
        assertInstanceOf(ResolveResult.UnknownTrade.class,
                service.reject(404L, Actor.of(bob, "Bob")).join());

        // 解决类操作不触碰任何所有权
        assertEquals("ACTIVE", ownershipStatus(ownX));
        assertEquals(1L, auditCount("TRADE_REJECT"));
        assertEquals(2L, auditCount("TRADE_DESTROY"));
    }

    /** 过期扫描：只收敛已过期 PENDING，幂等，审计落表。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void sweepExpired_convergesOnlyExpiredPending(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        long now = 10_000L;
        long expiredA = insertBareTrade(1_000L, 2_000L);
        long expiredB = insertBareTrade(1_000L, 3_000L);
        long alive = insertBareTrade(1_000L, 99_000L);

        assertEquals(2, service.sweepExpired(now).join());
        assertEquals(TradeStatus.EXPIRED, tradeStatus(expiredA));
        assertEquals(TradeStatus.EXPIRED, tradeStatus(expiredB));
        assertEquals(TradeStatus.PENDING, tradeStatus(alive));
        assertEquals(0, service.sweepExpired(now).join(), "重复扫描应幂等");
        assertEquals(2L, auditCount("TRADE_EXPIRE"));
    }

    /** 查询门面：个人/全服历史分页与 PAPI 计数包装。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void queryFacades_wrapRepository(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long ownX = grant(alice, "Alice", titleX, null);
        long ownY = grant(alice, "Alice", titleY, null);

        Trade completedTrade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of());
        service.accept(completedTrade.id(), Actor.of(bob, "Bob")).join();
        Trade pendingTrade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownY, titleY)), List.of());

        assertEquals(2, service.countByPlayer(alice, false, false).join());
        assertEquals(2, service.countByPlayer(alice, true, false).join());
        assertEquals(1, service.countByPlayer(alice, false, true).join());
        assertEquals(2, service.personalHistory(alice, 1, 10).join().totalItems());
        assertEquals(List.of(completedTrade.id()),
                service.globalHistory(TradeStatus.COMPLETED, null, 1, 10).join()
                        .items().stream().map(Trade::id).toList());
        assertEquals(pendingTrade.id(),
                service.findWithItems(pendingTrade.id()).join().orElseThrow().id());
    }

    /** 直插一笔无标的 PENDING 交易（过期扫描种子）。 */
    private long insertBareTrade(long createdAt, long expiresAt) {
        return runInTx(c -> tradeRepository.insert(c, new Trade(0, UUID.randomUUID(), "I",
                UUID.randomUUID(), "T", TradeStatus.PENDING, createdAt, expiresAt,
                null, null, null, List.of()), List.of()));
    }
}
