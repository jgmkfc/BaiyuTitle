package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.order.OrderKind;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcOrderRepository} 双方言单测（表 V3：purchase_order）。
 */
class OrderRepositoryTest extends BaseRepositoryTest {

    /** insert → findById 全字段往返（可空列非空/全空两态）；不存在的 id 返回空。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_thenFindById_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        // SHOP 订单：全部价格快照与 OR 分支非空
        PurchaseOrder full = new PurchaseOrder(0, UUID.randomUUID(), player, "Alice", 7,
                OrderKind.SHOP, ConditionType.VAULT, new BigDecimal("12.50"), 30L,
                "BASE64ITEM", 5, "byt.vip", null, OrderState.RESERVED, null, 1_000L, 1_000L);
        long id = runInTx(c -> orderRepository.insert(c, full));
        assertTrue(id > 0, "主键应由数据库生成");

        PurchaseOrder found = withConnection(c -> orderRepository.findById(c, id)).orElseThrow();
        // DECIMAL 标度因方言而异（SQLite 无定点类型），金额按数值比较后以实际标度重建期望值
        assertEquals(0, full.vaultAmount().compareTo(found.vaultAmount()), "Vault 金额数值应一致");
        assertEquals(new PurchaseOrder(id, full.orderUuid(), player, "Alice", 7,
                OrderKind.SHOP, ConditionType.VAULT, found.vaultAmount(), 30L,
                "BASE64ITEM", 5, "byt.vip", null, OrderState.RESERVED, null, 1_000L, 1_000L), found);

        // CUSTOM 订单：可空列全空（title_id 履约后才回填）
        PurchaseOrder custom = new PurchaseOrder(0, UUID.randomUUID(), player, "Alice", null,
                OrderKind.CUSTOM, null, null, null, null, null, null, "<red>王者",
                OrderState.PAYING, null, 2_000L, 2_000L);
        long customId = runInTx(c -> orderRepository.insert(c, custom));
        assertEquals(new PurchaseOrder(customId, custom.orderUuid(), player, "Alice", null,
                        OrderKind.CUSTOM, null, null, null, null, null, null, "<red>王者",
                        OrderState.PAYING, null, 2_000L, 2_000L),
                withConnection(c -> orderRepository.findById(c, customId)).orElseThrow());
        assertTrue(withConnection(c -> orderRepository.findById(c, 999_999L)).isEmpty());
    }

    /** transition：正确 from 迁移成功并落 fail_reason/updated_at；错误 from 返回 false 且行不变。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void transition_onlyFromExpectedState(TestDialect dialect) {
        initDatabase(dialect);
        long id = runInTx(c -> orderRepository.insert(c, reserved(UUID.randomUUID(), 7, 1_000L)));

        boolean toPaying = runInTx(c -> orderRepository.transition(c, id,
                OrderState.RESERVED, OrderState.PAYING, null, 2_000L));
        assertTrue(toPaying);
        boolean toRefunded = runInTx(c -> orderRepository.transition(c, id,
                OrderState.PAYING, OrderState.REFUNDED, "余额不足", 3_000L));
        assertTrue(toRefunded);
        PurchaseOrder after = withConnection(c -> orderRepository.findById(c, id)).orElseThrow();
        assertEquals(OrderState.REFUNDED, after.state());
        assertEquals("余额不足", after.failReason());
        assertEquals(3_000L, after.updatedAt());

        // 错误 from：0 行命中返回 false，且行内容不变
        boolean fromWrongState = runInTx(c -> orderRepository.transition(c, id,
                OrderState.PAYING, OrderState.FULFILLED, null, 4_000L));
        assertFalse(fromWrongState);
        assertEquals(after, withConnection(c -> orderRepository.findById(c, id)).orElseThrow());
    }

    /** findNonTerminal：命中 RESERVED/PAYING/MANUAL（含 MANUAL），按 id 升序；终态不命中。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findNonTerminal_includesManualExcludesTerminal(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        long reservedId = insertWithState(player, 1, OrderState.RESERVED);
        long payingId = insertWithState(player, 2, OrderState.PAYING);
        long manualId = insertWithState(player, 3, OrderState.MANUAL);
        insertWithState(player, 4, OrderState.FULFILLED);
        insertWithState(player, 5, OrderState.FAILED);
        insertWithState(player, 6, OrderState.REFUNDED);

        List<PurchaseOrder> found = withConnection(c -> orderRepository.findNonTerminal(c));
        assertEquals(List.of(reservedId, payingId, manualId),
                found.stream().map(PurchaseOrder::id).toList());
    }

    /** countFulfilled：只统计目标称号的 FULFILLED 订单。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void countFulfilled_onlyTargetTitle(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        insertWithState(player, 7, OrderState.FULFILLED);
        insertWithState(player, 7, OrderState.FULFILLED);
        insertWithState(player, 7, OrderState.RESERVED); // 非终态不计
        insertWithState(player, 8, OrderState.FULFILLED); // 其他称号不计

        assertEquals(2, (int) withConnection(c -> orderRepository.countFulfilled(c, 7)));
        assertEquals(0, (int) withConnection(c -> orderRepository.countFulfilled(c, 9)));
    }

    /** findFulfilledBuyers：只含目标称号的 FULFILLED 单，按履约时刻倒序；同一玩家多次购买产生多行。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findFulfilledBuyers_orderedByFulfilTimeDesc(TestDialect dialect) {
        initDatabase(dialect);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        insertBuyerOrder(alice, "Alice", 7, OrderState.FULFILLED, 1_000L);
        insertBuyerOrder(bob, "Bob", 7, OrderState.FULFILLED, 3_000L);
        insertBuyerOrder(alice, "Alice", 7, OrderState.FULFILLED, 2_000L); // 复购（到期后再购）
        insertBuyerOrder(alice, "Alice", 7, OrderState.RESERVED, 4_000L); // 非履约不计
        insertBuyerOrder(bob, "Bob", 8, OrderState.FULFILLED, 5_000L); // 其他称号不计

        List<OrderRepository.FulfilledBuyer> buyers =
                withConnection(c -> orderRepository.findFulfilledBuyers(c, 7));
        assertEquals(List.of(
                new OrderRepository.FulfilledBuyer(bob, "Bob", 3_000L),
                new OrderRepository.FulfilledBuyer(alice, "Alice", 2_000L),
                new OrderRepository.FulfilledBuyer(alice, "Alice", 1_000L)), buyers);
        assertTrue(withConnection(c -> orderRepository.findFulfilledBuyers(c, 9)).isEmpty(),
                "无履约单的称号返回空列表");
    }

    /** existsNonTerminal：同玩家同称号的非终态订单命中；终态订单、他人订单不命中。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void existsNonTerminal_scopedToPlayerAndTitle(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        insertWithState(player, 7, OrderState.RESERVED);
        insertWithState(player, 8, OrderState.FULFILLED);

        assertTrue((boolean) withConnection(c -> orderRepository.existsNonTerminal(c, player, 7)));
        assertFalse((boolean) withConnection(c -> orderRepository.existsNonTerminal(c, player, 8)),
                "终态订单不应阻止新订单");
        assertFalse((boolean) withConnection(c -> orderRepository.existsNonTerminal(c, UUID.randomUUID(), 7)),
                "他人订单不应命中");
    }

    /** fillTitleId：CUSTOM 订单履约后回填 title_id 并刷新 updated_at。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void fillTitleId_backfillsCustomOrder(TestDialect dialect) {
        initDatabase(dialect);
        long id = runInTx(c -> orderRepository.insert(c, new PurchaseOrder(0, UUID.randomUUID(),
                UUID.randomUUID(), "Alice", null, OrderKind.CUSTOM, null, null, null, null, null,
                null, "<red>王者", OrderState.PAYING, null, 1_000L, 1_000L)));
        assertNull(withConnection(c -> orderRepository.findById(c, id)).orElseThrow().titleId());

        runInTx(c -> {
            orderRepository.fillTitleId(c, id, 42, 5_000L);
            return null;
        });
        PurchaseOrder after = withConnection(c -> orderRepository.findById(c, id)).orElseThrow();
        assertEquals(42, after.titleId());
        assertEquals(5_000L, after.updatedAt());
    }

    /** 插入一笔指定状态的订单并返回主键。 */
    private long insertWithState(UUID player, int titleId, OrderState state) {
        return runInTx(c -> orderRepository.insert(c, new PurchaseOrder(0, UUID.randomUUID(),
                player, "Buyer", titleId, OrderKind.SHOP, null, null, null, null, null, null,
                null, state, null, 1_000L, 1_000L)));
    }

    /** 插入一笔带买家名与 updated_at（履约时刻）的订单并返回主键。 */
    private long insertBuyerOrder(UUID player, String name, int titleId,
                                  OrderState state, long updatedAt) {
        return runInTx(c -> orderRepository.insert(c, new PurchaseOrder(0, UUID.randomUUID(),
                player, name, titleId, OrderKind.SHOP, null, null, null, null, null, null,
                null, state, null, 500L, updatedAt)));
    }

    /** 构造 RESERVED 状态的最小 SHOP 订单。 */
    private static PurchaseOrder reserved(UUID player, int titleId, long createdAt) {
        return new PurchaseOrder(0, UUID.randomUUID(), player, "Buyer", titleId, OrderKind.SHOP,
                null, null, null, null, null, null, null, OrderState.RESERVED, null,
                createdAt, createdAt);
    }
}
