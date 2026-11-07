package net.baiyu.title.service;

import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.service.trade.CreateResult;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.service.trade.TradeBlockReason;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TradeService#createOffer} 单测：落库形态、结构校验、权威校验、
 * 不可交易类别拒建。
 */
class TradeCreateOfferTest extends TradeTestSupport {

    /** 创建成功：PENDING 落库、TTL=trade.expire-hours、双侧标的与名称快照、审计。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void createOffer_persistsPendingOffer(TestDialect dialect) {
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

        assertEquals(TradeStatus.PENDING, trade.status());
        assertEquals("Alice", trade.initiatorName());
        assertEquals("Bob", trade.targetName());
        assertEquals(72 * 3_600_000L, trade.expiresAt() - trade.createdAt(),
                "TTL 应等于 trade.expire-hours 默认 72 小时");
        assertNull(trade.resolvedAt());
        assertEquals(2, trade.items().size());
        assertEquals(ownX, trade.itemsOf(TradeSide.INITIATOR).getFirst().ownershipId());
        assertEquals("<white>甲", trade.itemsOf(TradeSide.INITIATOR).getFirst().titleNameSnapshot());
        assertEquals(ownY, trade.itemsOf(TradeSide.TARGET).getFirst().ownershipId());
        // 双方 pendingFor 均可见；审计落表
        assertEquals(List.of(trade.id()),
                service.pendingFor(alice).join().stream().map(Trade::id).toList());
        assertEquals(List.of(trade.id()),
                service.pendingFor(bob).join().stream().map(Trade::id).toList());
        assertEquals(1L, auditCount("TRADE_CREATE"));
    }

    /** 结构校验：总开关/自交易/双侧全空/每侧上限/重复标的（全部零落库）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void createOffer_structuralRejections(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        tradeSettings.enabled = false;
        assertEquals(TradeBlockReason.DISABLED,
                rejectedReason(service, alice, bob, List.of(new OfferItem(1, 1)), List.of()));
        tradeSettings.enabled = true;

        assertEquals(TradeBlockReason.SELF_TRADE,
                rejectedReason(service, alice, alice, List.of(new OfferItem(1, 1)), List.of()));
        assertEquals(TradeBlockReason.EMPTY_OFFER,
                rejectedReason(service, alice, bob, List.of(), List.of()));

        List<OfferItem> thirteen = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            thirteen.add(new OfferItem(i, i));
        }
        assertEquals(TradeBlockReason.TOO_MANY_ITEMS,
                rejectedReason(service, alice, bob, thirteen, List.of()));
        assertEquals(TradeBlockReason.TOO_MANY_ITEMS,
                rejectedReason(service, alice, bob, List.of(), thirteen));

        assertEquals(TradeBlockReason.DUPLICATE_ITEM, rejectedReason(service, alice, bob,
                List.of(new OfferItem(9, 1), new OfferItem(9, 1)), List.of()));
        // 同一所有权横跨双侧同样算重复
        assertEquals(TradeBlockReason.DUPLICATE_ITEM, rejectedReason(service, alice, bob,
                List.of(new OfferItem(9, 1)), List.of(new OfferItem(9, 1))));

        assertEquals(0L, countRows(tables.trade()), "任何拒绝都不得落库");
    }

    /** 权威校验：归属/到期/软删/对侧已持有（全部零落库）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void createOffer_authoritativeRejections(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        int titleZ = seedTitle(tradableTitle("丙"));

        // 标的不属于发起方（属于对方 / 纯属虚构）
        long bobsOwn = grant(bob, "Bob", titleY, null);
        assertEquals(TradeBlockReason.ITEM_NOT_OWNED, rejectedReason(service, alice, bob,
                List.of(new OfferItem(bobsOwn, titleY)), List.of()));
        assertEquals(TradeBlockReason.ITEM_NOT_OWNED, rejectedReason(service, alice, bob,
                List.of(new OfferItem(999_999L, titleX)), List.of()));
        // 接受方侧标的同样校验归属
        assertEquals(TradeBlockReason.ITEM_NOT_OWNED, rejectedReason(service, alice, bob,
                List.of(), List.of(new OfferItem(999_999L, titleY))));

        // 标的已到期
        long expired = grant(alice, "Alice", titleZ, System.currentTimeMillis() - 1_000L);
        assertEquals(TradeBlockReason.ITEM_EXPIRED, rejectedReason(service, alice, bob,
                List.of(new OfferItem(expired, titleZ)), List.of()));

        // 称号已软删
        long ownX = grant(alice, "Alice", titleX, null);
        runInTx(c -> {
            titleRepository.softDelete(c, titleX, System.currentTimeMillis());
            return null;
        });
        reloadCatalog();
        assertEquals(TradeBlockReason.TITLE_NOT_FOUND, rejectedReason(service, alice, bob,
                List.of(new OfferItem(ownX, titleX)), List.of()));

        // 对侧已 ACTIVE 持有同款且未在本单转出
        long aliceY = grant(alice, "Alice", titleY, null);
        assertEquals(TradeBlockReason.RECIPIENT_ALREADY_OWNS, rejectedReason(service, alice, bob,
                List.of(new OfferItem(aliceY, titleY)), List.of()));

        assertEquals(0L, countRows(tables.trade()), "任何拒绝都不得落库");
    }

    /** 不可交易拒建：NORMAL tradable=false 与 BOND/CUSTOM/GROUP 三类别恒拒。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void createOffer_nonTradableRejected(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        int locked = seedTitle(Title.builder().name("<white>锁").namePlain("锁")
                .tradable(false).createdAt(1L).updatedAt(1L).build());
        assertNotTradable(service, alice, bob, locked);
        for (TitleCategory category : List.of(TitleCategory.BOND, TitleCategory.CUSTOM,
                TitleCategory.GROUP)) {
            int titleId = seedTitle(Title.builder().name("<white>" + category)
                    .namePlain(category.name()).category(category).createdAt(1L).updatedAt(1L)
                    .build());
            assertNotTradable(service, alice, bob, titleId);
        }
        assertEquals(0L, countRows(tables.trade()));
    }

    /** 授予后报价该称号，断言 NOT_TRADABLE 拒绝且携带称号 id。 */
    private void assertNotTradable(TradeService service, UUID alice, UUID bob, int titleId) {
        long own = grant(alice, "Alice", titleId, null);
        CreateResult result = service.createOffer(alice, "Alice", bob, "Bob",
                List.of(new OfferItem(own, titleId)), List.of()).join();
        CreateResult.Rejected rejected = assertInstanceOf(CreateResult.Rejected.class, result);
        assertEquals(TradeBlockReason.NOT_TRADABLE, rejected.reason());
        assertEquals(titleId, rejected.titleId());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, alice, titleId)).isPresent(),
                "拒建不得影响所有权");
    }

    /** 发起报价并取回拒绝原因。 */
    private TradeBlockReason rejectedReason(TradeService service, UUID initiator, UUID target,
                                            List<OfferItem> mine, List<OfferItem> theirs) {
        CreateResult result = service.createOffer(initiator, "Alice", target, "Bob",
                mine, theirs).join();
        return assertInstanceOf(CreateResult.Rejected.class, result).reason();
    }
}
