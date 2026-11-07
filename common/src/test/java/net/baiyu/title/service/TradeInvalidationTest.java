package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.service.trade.AcceptResult;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成交复核失败作废（"标的缺失作废"）与成交后佩戴引用/缓存同步单测。
 * <p>作废 = PENDING→DESTROYED 且双方所有权零变化。
 */
class TradeInvalidationTest extends TradeTestSupport {

    /** 标的被另一笔交易转走：后到的接受整单作废，双方零变化。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_afterItemTradedAway_destroysOffer(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        int titleZ = seedTitle(tradableTitle("丙"));
        long ownX = grant(alice, "Alice", titleX, null);
        long ownY = grant(bob, "Bob", titleY, null);
        long ownZ = grant(carol, "Carol", titleZ, null);

        // 交易期间不锁称号：同一标的可同时挂在两笔 PENDING 报价上
        Trade viaBob = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));
        Trade viaCarol = createPending(service, alice, "Alice", carol, "Carol",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownZ, titleZ)));

        assertInstanceOf(AcceptResult.Completed.class,
                service.accept(viaBob.id(), Actor.of(bob, "Bob")).join());
        long rowsAfterFirst = countRows(tables.titleOwnership());

        // 后到的接受：标的已转走 → 复核失败整单作废
        AcceptResult second = service.accept(viaCarol.id(), Actor.of(carol, "Carol")).join();
        AcceptResult.Invalidated invalidated =
                assertInstanceOf(AcceptResult.Invalidated.class, second);
        assertEquals("ITEM_NOT_OWNED:" + titleX, invalidated.failReason());
        assertEquals(TradeStatus.DESTROYED, tradeStatus(viaCarol.id()));

        // 双方零变化：Carol 的标的仍 ACTIVE、无任何新增所有权行
        assertEquals("ACTIVE", ownershipStatus(ownZ));
        assertEquals(rowsAfterFirst, countRows(tables.titleOwnership()),
                "作废不得产生任何所有权变动");
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, bob, titleX)).isPresent(),
                "先成交的一单不受影响");
    }

    /** 标的在报价挂起期间到期：接受时整单作废，标的行原样保留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_afterItemExpired_destroysOffer(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long ownX = grant(alice, "Alice", titleX, System.currentTimeMillis() + 3_600_000L);
        long ownY = grant(bob, "Bob", titleY, null);
        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));

        forceOwnershipExpiry(ownX, System.currentTimeMillis() - 1_000L);

        AcceptResult result = service.accept(trade.id(), Actor.of(bob, "Bob")).join();
        assertEquals("ITEM_EXPIRED:" + titleX,
                assertInstanceOf(AcceptResult.Invalidated.class, result).failReason());
        assertEquals(TradeStatus.DESTROYED, tradeStatus(trade.id()));
        assertEquals("ACTIVE", ownershipStatus(ownX), "作废不代扣到期，收敛留给 ExpiryService");
        assertEquals("ACTIVE", ownershipStatus(ownY));
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, bob, titleX)).isEmpty());
    }

    /** 称号在挂起期间被软删或改为不可交易：接受时整单作废。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_afterTitleRemovedOrLocked_destroysOffer(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int deletedId = seedTitle(tradableTitle("删"));
        int lockedId = seedTitle(tradableTitle("锁"));
        long ownDeleted = grant(alice, "Alice", deletedId, null);
        long ownLocked = grant(alice, "Alice", lockedId, null);
        Trade deletedOffer = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownDeleted, deletedId)), List.of());
        Trade lockedOffer = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownLocked, lockedId)), List.of());

        // 软删 + 改不可交易，重载目录缓存（复核走缓存快照）
        runInTx(c -> {
            titleRepository.softDelete(c, deletedId, System.currentTimeMillis());
            Title locked = catalogCache.byId(lockedId).orElseThrow();
            titleRepository.update(c, locked.toBuilder().tradable(false).build());
            return null;
        });
        reloadCatalog();

        assertEquals("TITLE_NOT_FOUND:" + deletedId,
                assertInstanceOf(AcceptResult.Invalidated.class,
                        service.accept(deletedOffer.id(), Actor.of(bob, "Bob")).join()).failReason());
        assertEquals("NOT_TRADABLE:" + lockedId,
                assertInstanceOf(AcceptResult.Invalidated.class,
                        service.accept(lockedOffer.id(), Actor.of(bob, "Bob")).join()).failReason());
        assertEquals(TradeStatus.DESTROYED, tradeStatus(deletedOffer.id()));
        assertEquals(TradeStatus.DESTROYED, tradeStatus(lockedOffer.id()));
        assertEquals("ACTIVE", ownershipStatus(ownDeleted));
        assertEquals("ACTIVE", ownershipStatus(ownLocked));
    }

    /** 佩戴中标的成交：DB 栏位/随机池引用清除，双方档案缓存写穿。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void accept_wornItem_clearsSlotsAndSyncsCache(TestDialect dialect) {
        initDatabase(dialect);
        TradeService service = newTradeService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int titleX = seedTitle(tradableTitle("甲"));
        int titleY = seedTitle(tradableTitle("乙"));
        long ownX = grant(alice, "Alice", titleX, null);
        long ownY = grant(bob, "Bob", titleY, null);
        // Alice 在固定栏位与随机池佩戴 X；Bob 固定栏位佩戴 Y
        runInTx(c -> {
            slotRepository.upsert(c, alice, new SlotConfig(0, true, SlotMode.FIXED, titleX));
            slotRepository.replacePool(c, alice, 1, Set.of(titleX));
            slotRepository.upsert(c, bob, new SlotConfig(0, true, SlotMode.FIXED, titleY));
            return null;
        });
        profileCache.put(new PlayerProfileCache.PlayerProfile(alice, "Alice",
                Map.of(titleX, activeOf(alice, titleX)),
                List.of(new SlotConfig(0, true, SlotMode.FIXED, titleX)),
                Map.of(1, Set.of(titleX)), 0));
        profileCache.put(new PlayerProfileCache.PlayerProfile(bob, "Bob",
                Map.of(titleY, activeOf(bob, titleY)),
                List.of(new SlotConfig(0, true, SlotMode.FIXED, titleY)), Map.of(), 0));

        Trade trade = createPending(service, alice, "Alice", bob, "Bob",
                List.of(new OfferItem(ownX, titleX)), List.of(new OfferItem(ownY, titleY)));
        assertInstanceOf(AcceptResult.Completed.class,
                service.accept(trade.id(), Actor.of(bob, "Bob")).join());

        // DB：旧主佩戴引用全部清除
        assertNull(withConnection(c -> slotRepository.load(c, alice)).getFirst().titleId(),
                "互换后佩戴中标的应被卸下");
        assertEquals(Set.of(), withConnection(c -> slotRepository.loadPool(c, alice, 1)));
        assertNull(withConnection(c -> slotRepository.load(c, bob)).getFirst().titleId());

        // 缓存：旧主移除（含栏位引用）、新主合入（source=TRADE）
        PlayerProfileCache.PlayerProfile aliceProfile = profileCache.get(alice).orElseThrow();
        assertFalse(aliceProfile.activeOwnerships().containsKey(titleX));
        assertEquals(GrantSource.TRADE, aliceProfile.activeOwnerships().get(titleY).source());
        assertNull(aliceProfile.slots().getFirst().titleId());
        assertEquals(Set.of(), aliceProfile.randomPools().get(1));
        PlayerProfileCache.PlayerProfile bobProfile = profileCache.get(bob).orElseThrow();
        assertFalse(bobProfile.activeOwnerships().containsKey(titleY));
        assertEquals(GrantSource.TRADE, bobProfile.activeOwnerships().get(titleX).source());
        assertNull(bobProfile.slots().getFirst().titleId());
    }

    /** 从数据库取该玩家该称号的 ACTIVE 所有权（构造缓存种子用）。 */
    private net.baiyu.title.domain.ownership.Ownership activeOf(UUID owner, int titleId) {
        return withConnection(c -> ownershipRepository.findActive(c, owner, titleId)).orElseThrow();
    }
}
