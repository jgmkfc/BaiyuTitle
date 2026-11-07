package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.Page;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcTradeRepository} 双方言单测（表 V4：trade / trade_item）。
 */
class TradeRepositoryTest extends BaseRepositoryTest {

    /** insert（主表 + 明细）→ findWithItems 全字段往返（含可空列非空/全空两态）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_thenFindWithItems_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        UUID initiator = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // 已解决交易：resolved_at / resolved_by / fail_reason 全部非空
        Trade resolved = new Trade(0, initiator, "Alice", target, "Bob", TradeStatus.COMPLETED,
                1_000L, 2_000L, 3_000L, "Admin", "复核失败", List.of());
        List<TradeItem> items = List.of(
                item(TradeSide.INITIATOR, 11L, 7, "<white>甲"),
                item(TradeSide.TARGET, 22L, 8, "<red>乙"));
        long id = runInTx(c -> tradeRepository.insert(c, resolved, items));

        Trade found = withConnection(c -> tradeRepository.findWithItems(c, id)).orElseThrow();
        // 明细主键由数据库生成且按插入序返回，以实际 id 重建期望值后整体 equals
        assertEquals(items.size(), found.items().size());
        List<TradeItem> expectedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            long itemId = found.items().get(i).id();
            assertTrue(itemId > 0, "明细主键应由数据库生成");
            TradeItem in = items.get(i);
            expectedItems.add(new TradeItem(itemId, id, in.side(), in.ownershipId(), in.titleId(),
                    in.titleNameSnapshot()));
        }
        assertEquals(new Trade(id, initiator, "Alice", target, "Bob", TradeStatus.COMPLETED,
                1_000L, 2_000L, 3_000L, "Admin", "复核失败", expectedItems), found);

        // 待处理交易：可空列全部为 NULL；不存在的 id 返回空
        long pendingId = runInTx(c -> tradeRepository.insert(c,
                pending(initiator, "Alice", target, "Bob", 1_000L), List.of()));
        assertEquals(new Trade(pendingId, initiator, "Alice", target, "Bob", TradeStatus.PENDING,
                        1_000L, 73_000L, null, null, null, List.of()),
                withConnection(c -> tradeRepository.findWithItems(c, pendingId)).orElseThrow());
        assertTrue(withConnection(c -> tradeRepository.findWithItems(c, 999_999L)).isEmpty());
    }

    /** transition：正确 from 迁移成功；错误 from 返回 false 且行内容不变。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void transition_onlyFromExpectedStatus(TestDialect dialect) {
        initDatabase(dialect);
        long id = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob", 1_000L), List.of()));

        boolean migrated = runInTx(c -> tradeRepository.transition(c, id,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "Bob", null, 5_000L));
        assertTrue(migrated);
        Trade after = withConnection(c -> tradeRepository.findWithItems(c, id)).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, after.status());
        assertEquals(5_000L, after.resolvedAt());
        assertEquals("Bob", after.resolvedBy());

        // 错误 from：0 行命中返回 false，且行内容不变
        boolean migratedAgain = runInTx(c -> tradeRepository.transition(c, id,
                TradeStatus.PENDING, TradeStatus.REJECTED, "Eve", "并发", 6_000L));
        assertFalse(migratedAgain);
        assertEquals(after, withConnection(c -> tradeRepository.findWithItems(c, id)).orElseThrow());
    }

    /** findPendingFor：作为发起方与接收方均命中，非 PENDING 与他人交易不命中。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findPendingFor_hitsBothSides(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        long asInitiator = runInTx(c -> tradeRepository.insert(c,
                pending(player, "P", UUID.randomUUID(), "X", 1_000L),
                List.of(item(TradeSide.INITIATOR, 11L, 7, "<white>甲"))));
        long asTarget = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Y", player, "P", 2_000L), List.of()));
        // 干扰项：本人已成交交易、他人 PENDING 交易均不应命中
        long completedId = runInTx(c -> tradeRepository.insert(c,
                pending(player, "P", UUID.randomUUID(), "Z", 3_000L), List.of()));
        runInTx(c -> tradeRepository.transition(c, completedId,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "Z", null, 4_000L));
        runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "A", UUID.randomUUID(), "B", 1_000L), List.of()));

        List<Trade> found = withConnection(c -> tradeRepository.findPendingFor(c, player));
        assertEquals(List.of(asInitiator, asTarget), found.stream().map(Trade::id).toList());
        assertEquals(1, found.getFirst().items().size(), "PENDING 查询应装载标的");
        assertEquals(11L, found.getFirst().items().getFirst().ownershipId());
    }

    /** pickExpiredPending：只取已过期的 PENDING，按过期时刻升序并遵守批大小。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void pickExpiredPending_onlyExpiredPending(TestDialect dialect) {
        initDatabase(dialect);
        long now = 10_000L;
        long late = insertPendingExpiring(9_000L);
        long early = insertPendingExpiring(8_000L);
        insertPendingExpiring(20_000L); // 未过期不命中
        long resolvedId = insertPendingExpiring(1_000L); // 已过期但已成交不命中
        runInTx(c -> tradeRepository.transition(c, resolvedId,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "B", null, 900L));

        assertEquals(List.of(early, late), withConnection(c -> tradeRepository.pickExpiredPending(c, now, 10)));
        assertEquals(List.of(early), withConnection(c -> tradeRepository.pickExpiredPending(c, now, 1)),
                "批大小应生效");
    }

    /** personalHistory：任一侧命中，按创建时刻倒序分页，COUNT 与页内容一致。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void personalHistory_paginatesNewestFirst(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        long t1 = runInTx(c -> tradeRepository.insert(c, pending(player, "P", UUID.randomUUID(), "X", 1_000L), List.of()));
        long t2 = runInTx(c -> tradeRepository.insert(c, pending(UUID.randomUUID(), "Y", player, "P", 2_000L), List.of()));
        long t3 = runInTx(c -> tradeRepository.insert(c, pending(player, "P", UUID.randomUUID(), "Z", 3_000L), List.of()));
        // 无关玩家的交易不计入
        runInTx(c -> tradeRepository.insert(c, pending(UUID.randomUUID(), "A", UUID.randomUUID(), "B", 9_000L), List.of()));

        Page<Trade> page1 = withConnection(c -> tradeRepository.personalHistory(c, player, 1, 2));
        assertEquals(3, page1.totalItems());
        assertEquals(List.of(t3, t2), idsOf(page1));
        Page<Trade> page2 = withConnection(c -> tradeRepository.personalHistory(c, player, 2, 2));
        assertEquals(3, page2.totalItems());
        assertEquals(List.of(t1), idsOf(page2));
    }

    /** globalHistory：状态过滤与玩家名模糊过滤（发起方/目标方双侧命中）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void globalHistory_filtersByStatusAndName(TestDialect dialect) {
        initDatabase(dialect);
        long completedId = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob", 1_000L), List.of()));
        runInTx(c -> tradeRepository.transition(c, completedId,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "Bob", null, 2_000L));
        long pendingId = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Carol", UUID.randomUUID(), "Dave", 3_000L), List.of()));

        // 无过滤：全部命中
        assertEquals(2, withConnection(c -> tradeRepository.globalHistory(c, null, null, 1, 10)).totalItems());
        // 状态过滤
        Page<Trade> completed = withConnection(c -> tradeRepository.globalHistory(c, TradeStatus.COMPLETED, null, 1, 10));
        assertEquals(1, completed.totalItems());
        assertEquals(List.of(completedId), idsOf(completed));
        // 玩家名模糊过滤：发起方与目标方均可命中
        assertEquals(List.of(pendingId), idsOf(globalByName("Caro")));
        assertEquals(List.of(completedId), idsOf(globalByName("Bob")));
        // 状态 + 名称组合过滤
        assertEquals(List.of(pendingId),
                idsOf(withConnection(c -> tradeRepository.globalHistory(c, TradeStatus.PENDING, "Dave", 1, 10))));
    }

    /** globalHistory：LIKE 通配符按字面转义（ESCAPE '!'），"Mr_X" 不误匹配 "MrAX"。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void globalHistory_escapesLikeWildcards(TestDialect dialect) {
        initDatabase(dialect);
        long underscoreId = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Mr_X", UUID.randomUUID(), "T1", 1_000L), List.of()));
        runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "MrAX", UUID.randomUUID(), "T2", 2_000L), List.of()));

        // "_" 必须按字面匹配（未转义时 MrAX 也会被单字符通配命中）
        assertEquals(List.of(underscoreId), idsOf(globalByName("Mr_X")));
        // "%" 同样按字面处理：无人名含百分号，应零命中
        assertEquals(0, globalByName("%").totalItems());
    }

    /** countByPlayer：initiatedOnly / completedOnly 四种组合。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void countByPlayer_flagCombinations(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        // 作为发起方：1 笔成交 + 1 笔待处理；作为目标方：1 笔成交
        long initCompleted = runInTx(c -> tradeRepository.insert(c,
                pending(player, "P", UUID.randomUUID(), "X", 1_000L), List.of()));
        runInTx(c -> tradeRepository.transition(c, initCompleted,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "X", null, 2_000L));
        runInTx(c -> tradeRepository.insert(c, pending(player, "P", UUID.randomUUID(), "Y", 3_000L), List.of()));
        long targetCompleted = runInTx(c -> tradeRepository.insert(c,
                pending(UUID.randomUUID(), "Z", player, "P", 4_000L), List.of()));
        runInTx(c -> tradeRepository.transition(c, targetCompleted,
                TradeStatus.PENDING, TradeStatus.COMPLETED, "P", null, 5_000L));

        assertEquals(3, count(player, false, false));
        assertEquals(2, count(player, true, false));
        assertEquals(2, count(player, false, true));
        assertEquals(1, count(player, true, true));
    }

    /** 在读线程上执行 countByPlayer。 */
    private int count(UUID player, boolean initiatedOnly, boolean completedOnly) {
        return withConnection(c -> tradeRepository.countByPlayer(c, player, initiatedOnly, completedOnly));
    }

    /** 只按玩家名模糊过滤的全局历史第一页。 */
    private Page<Trade> globalByName(String nameLike) {
        return withConnection(c -> tradeRepository.globalHistory(c, null, nameLike, 1, 10));
    }

    /** 插入一笔指定过期时刻的 PENDING 交易（无标的）。 */
    private long insertPendingExpiring(long expiresAt) {
        return runInTx(c -> tradeRepository.insert(c, new Trade(0, UUID.randomUUID(), "I", UUID.randomUUID(), "T",
                TradeStatus.PENDING, 100L, expiresAt, null, null, null, List.of()), List.of()));
    }

    /** 页内交易 id 列表。 */
    private static List<Long> idsOf(Page<Trade> page) {
        return page.items().stream().map(Trade::id).toList();
    }

    /** 构造 PENDING 交易（过期时刻 = 创建时刻 + 72 秒示意值）。 */
    private static Trade pending(UUID initiator, String initiatorName, UUID target, String targetName, long createdAt) {
        return new Trade(0, initiator, initiatorName, target, targetName, TradeStatus.PENDING,
                createdAt, createdAt + 72_000L, null, null, null, List.of());
    }

    /** 构造未落库交易标的（id / tradeId 由插入生成）。 */
    private static TradeItem item(TradeSide side, long ownershipId, int titleId, String snapshot) {
        return new TradeItem(0, 0, side, ownershipId, titleId, snapshot);
    }
}
