package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.order.OrderKind;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.purchase.BeginResult;
import net.baiyu.title.service.purchase.CompleteResult;
import net.baiyu.title.service.purchase.PaymentOutcome;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import net.baiyu.title.service.purchase.RecoveryReport;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PurchaseService} 订单状态机双方言测试：
 * 正常链 RESERVED→PAYING→FULFILLED 落所有权与流水、支付失败 REFUNDED 退币、
 * 防重（非终态订单拒新单）、双击幂等（重复 complete 空转）、恢复策略三态、
 * "或"免单强制、余额不足零残留。
 */
class PurchaseServiceTest extends BaseRepositoryTest {

    /** 测试权限节点。 */
    private static final String NODE = "title.test";

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;

    /** 正常链：begin 返回 PAYING 订单与支付指令，扣币留流水占位；complete 履约落所有权。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void happyPath_reservedPayingFulfilled(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND,
                List.of(new CoinCondition(200), new VaultCondition(new BigDecimal("80")))));
        seedCoins(player, 500);

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        assertEquals(OrderState.PAYING, begun.order().state());
        assertEquals(0, new BigDecimal("80").compareTo(begun.plan().vaultAmount()),
                "支付指令应含 Vault 金额");
        assertNull(begun.plan().itemBase64());
        assertEquals(OrderState.PAYING, findOrder(begun.order().id()).state(), "落库状态应为 PAYING");
        assertEquals(300L, balance(player), "称号币应在 RESERVE 事务扣除");
        assertEquals(1L, coinLogCount("PURCHASE"), "扣币必须同事务留流水");
        assertEquals(1L, soldCount(titleId), "售卖名额应占位");

        CompleteResult.Fulfilled fulfilled = assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(begun.order().id(), PaymentOutcome.ok()).join());
        assertEquals(OrderState.FULFILLED, findOrder(begun.order().id()).state());
        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, player, titleId)).orElseThrow();
        assertEquals(fulfilled.ownershipId(), ownership.id());
        assertEquals(GrantSource.PURCHASE, ownership.source());
        assertEquals(begun.order().id(), (long) ownership.sourceRef(), "所有权应回指订单");
        assertNull(ownership.expiresAt(), "无持有有效期应永久");
        assertEquals(1L, auditCount("PURCHASE"), "履约必须落审计");
    }

    /** 支付失败：REFUNDED 退币 + 释放占位 + REFUND 流水，订单记失败原因。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void paymentFailure_refundsAndReleases(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(200))));
        seedCoins(player, 500);

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        assertEquals(300L, balance(player));

        assertInstanceOf(CompleteResult.Refunded.class,
                service.completePayment(begun.order().id(), PaymentOutcome.failed("VAULT_SHORT")).join());
        PurchaseOrder after = findOrder(begun.order().id());
        assertEquals(OrderState.REFUNDED, after.state());
        assertEquals("VAULT_SHORT", after.failReason());
        assertEquals(500L, balance(player), "退款应恢复余额");
        assertEquals(1L, coinLogCount("REFUND"), "退款必须留 REFUND 流水");
        assertEquals(0L, soldCount(titleId), "退款应释放售卖占位");
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, titleId)).isEmpty());
    }

    /** 并发防重：同玩家同称号存在非终态订单时，第二单被拒且零残留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void duplicateBegin_rejectedByNonTerminalGuard(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(100))));
        seedCoins(player, 500);

        assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        BeginResult.Rejected second = assertInstanceOf(BeginResult.Rejected.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        assertEquals(BeginResult.RejectReason.PENDING_ORDER_EXISTS, second.reason());
        assertEquals(1L, countRows(tables.purchaseOrder()), "第二单不得落库");
        assertEquals(400L, balance(player), "第二单不得扣币");
        assertEquals(1L, soldCount(titleId), "第二单不得重复占位");
    }

    /** 双击语义：同一订单重复 complete，第二次幂等空转（不重复授予/不重复流水）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void doubleComplete_secondIsIdempotentNoOp(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(100))));
        seedCoins(player, 100);

        long orderId = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join()).order().id();
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(orderId, PaymentOutcome.ok()).join());

        CompleteResult.AlreadyResolved replay = assertInstanceOf(CompleteResult.AlreadyResolved.class,
                service.completePayment(orderId, PaymentOutcome.ok()).join());
        assertEquals(OrderState.FULFILLED, replay.currentState());
        assertEquals(1L, countRows(tables.titleOwnership()), "重复提交不得重复授予");
        assertEquals(1L, auditCount("PURCHASE"), "重复提交不得重复审计");
        // 重复上报"失败"同样空转，不得错误退款
        assertInstanceOf(CompleteResult.AlreadyResolved.class,
                service.completePayment(orderId, PaymentOutcome.failed("REPLAY")).join());
        assertEquals(0L, balance(player), "已履约订单不得再退款");
    }

    /** 余额不足：整体回滚零残留（无订单、无占位、无流水、余额不动）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insufficientCoins_rollsBackEverything(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(200))));
        seedCoins(player, 199);

        BeginResult.Rejected rejected = assertInstanceOf(BeginResult.Rejected.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join());
        assertEquals(BeginResult.RejectReason.INSUFFICIENT_COINS, rejected.reason());
        assertEquals(0L, countRows(tables.purchaseOrder()));
        assertEquals(0L, soldCount(titleId), "回滚后不得残留占位");
        assertEquals(199L, balance(player));
        assertEquals(0L, coinLogCount("PURCHASE"));
    }

    /** 已拥有拒购 + 售罄拒购（总数限定 cap=1 被他人买尽）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void alreadyOwnedAndSoldOut_rejected(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID buyerA = UUID.randomUUID();
        UUID buyerB = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of())
                .toBuilder().limitMode(LimitMode.TOTAL).totalCap(1).build());

        long orderId = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(buyerA, "Alice", titleId, null, snapshot()).join()).order().id();
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(orderId, PaymentOutcome.ok()).join());

        // A 已拥有 → ALREADY_OWNED（优先于售罄）
        assertEquals(BeginResult.RejectReason.ALREADY_OWNED,
                assertInstanceOf(BeginResult.Rejected.class,
                        service.beginPurchase(buyerA, "Alice", titleId, null, snapshot()).join()).reason());
        // B 遇 cap=1 已满 → SOLD_OUT（条件 UPDATE 未命中）
        assertEquals(BeginResult.RejectReason.SOLD_OUT,
                assertInstanceOf(BeginResult.Rejected.class,
                        service.beginPurchase(buyerB, "Bob", titleId, null, snapshot()).join()).reason());
    }

    /** 窗口外拒购（截止日已过）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void outsideWindow_rejected(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of()).toBuilder()
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.now().minusDays(9))
                .windowEnd(LocalDate.now().minusDays(1))
                .build());
        assertEquals(BeginResult.RejectReason.OUTSIDE_WINDOW,
                assertInstanceOf(BeginResult.Rejected.class, service.beginPurchase(
                        UUID.randomUUID(), "Alice", titleId, null, snapshot()).join()).reason());
    }

    /** "或"含权限且持有：无视 GUI 所选分支强制免单，订单快照记 PERMISSION、零扣款。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void orWithPermissionHeld_forcedFreeBranch(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.OR, List.of(
                new VaultCondition(new BigDecimal("80")), new PermissionCondition(NODE))));

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class, service.beginPurchase(
                player, "Alice", titleId, ConditionType.VAULT, snapshot(Set.of(NODE))).join());
        PurchaseOrder order = findOrder(begun.order().id());
        assertEquals(ConditionType.PERMISSION, order.chosenCond(), "服务端应强制权限免单分支");
        assertNull(order.vaultAmount(), "免单分支不得记 Vault 应付");
        assertNull(order.coinAmount());
        assertEquals(NODE, begun.plan().permissionNode());
        assertNull(begun.plan().vaultAmount());

        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(order.id(), PaymentOutcome.ok()).join());
        assertEquals(1L, soldCount(titleId), "免单仍占售卖名额");
    }

    /** "或"未选分支且无权限：INVALID_BRANCH 拒单。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void orWithoutBranch_rejectedInvalid(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        int titleId = seedTitle(normalTitle(CombineLogic.OR, List.of(
                new VaultCondition(new BigDecimal("80")), new CoinCondition(200))));
        assertEquals(BeginResult.RejectReason.INVALID_BRANCH,
                assertInstanceOf(BeginResult.Rejected.class, service.beginPurchase(
                        UUID.randomUUID(), "Alice", titleId, null, snapshot()).join()).reason());
    }

    /** 持有有效期：履约时按天数落 expires_at。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void holdDuration_setsExpiry(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of())
                .toBuilder().holdDurationDays(30).build());
        long before = System.currentTimeMillis();
        long orderId = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(player, "Alice", titleId, null, snapshot()).join()).order().id();
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(orderId, PaymentOutcome.ok()).join());
        long expiresAt = withConnection(c -> ownershipRepository.findActive(c, player, titleId))
                .orElseThrow().expiresAt();
        long thirtyDays = 30L * 86_400_000L;
        assertTrue(expiresAt >= before + thirtyDays
                        && expiresAt <= System.currentTimeMillis() + thirtyDays,
                "到期时刻应为授予时刻 + 30 天");
    }

    /** 恢复：RESERVED 孤儿恒退款；PAYING 孤儿按 grant 策略履约。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void recovery_reservedRefundedAndPayingGranted(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID crashed = UUID.randomUUID();
        UUID paying = UUID.randomUUID();
        int titleId = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(200))));
        seedCoins(crashed, 300);
        seedCoins(paying, 300);
        long reservedId = seedReservedOrphan(crashed, titleId, 200);
        long payingId = assertInstanceOf(BeginResult.Begun.class,
                service.beginPurchase(paying, "Bob", titleId, null, snapshot()).join()).order().id();

        RecoveryReport report = service.recoverOrphans().join();
        assertEquals(1, report.reservedRefunded());
        assertEquals(1, report.payingGranted());
        assertEquals(0, report.markedManual());
        assertEquals(OrderState.REFUNDED, findOrder(reservedId).state());
        assertEquals(300L, balance(crashed), "RESERVED 孤儿应退币");
        assertEquals(OrderState.FULFILLED, findOrder(payingId).state());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, paying, titleId)).isPresent(),
                "grant 策略应补授所有权");
        assertEquals(1L, soldCount(titleId), "退款释放 + 履约保留 = 占位 1");
    }

    /** 恢复：refund 策略退款、manual 策略留置后可由管理员裁决履约。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void recovery_refundAndManualPolicies(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        // refund 策略
        PurchaseService refundService = newService(RecoveryPolicy.REFUND);
        int refundTitle = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(100))));
        seedCoins(player, 100);
        long refundOrder = assertInstanceOf(BeginResult.Begun.class, refundService.beginPurchase(
                player, "Alice", refundTitle, null, snapshot()).join()).order().id();
        RecoveryReport refundReport = refundService.recoverOrphans().join();
        assertEquals(1, refundReport.payingRefunded());
        assertEquals(OrderState.REFUNDED, findOrder(refundOrder).state());
        assertEquals(100L, balance(player), "refund 策略应退币");

        // manual 策略：留置 → 管理员 resolve grant
        PurchaseService manualService = newService(RecoveryPolicy.MANUAL);
        int manualTitle = seedTitle(normalTitle(CombineLogic.AND, List.of(new CoinCondition(100))));
        long manualOrder = assertInstanceOf(BeginResult.Begun.class, manualService.beginPurchase(
                player, "Alice", manualTitle, null, snapshot()).join()).order().id();
        RecoveryReport manualReport = manualService.recoverOrphans().join();
        assertEquals(1, manualReport.markedManual());
        assertEquals(OrderState.MANUAL, findOrder(manualOrder).state());
        assertEquals(1, manualService.recoverOrphans().join().manualPending(),
                "再次恢复应留置 MANUAL 不动");

        assertInstanceOf(CompleteResult.Fulfilled.class, manualService.resolveManual(
                manualOrder, true, Actor.of(UUID.randomUUID(), "Admin")).join());
        assertEquals(OrderState.FULFILLED, findOrder(manualOrder).state());
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, player, manualTitle)).isPresent());
    }

    // ---------- 工具 ----------

    /** 构造服务（同时新建两个缓存实例）。 */
    private PurchaseService newService(RecoveryPolicy policy) {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        return new PurchaseService(orderRepository, titleRepository, ownershipRepository,
                coinRepository, auditRepository, txRunner, dbExecutor, catalogCache, profileCache,
                null, policy, PluginConfig.CustomTitleSettings::new);
    }

    /** 可见 NORMAL 称号底座。 */
    private static Title normalTitle(CombineLogic logic, List<PurchaseCondition> conditions) {
        return Title.builder().name("<white>[ 竹子测试 ]").namePlain("[ 竹子测试 ]")
                .combineLogic(logic).conditions(conditions).createdAt(1L).updatedAt(1L).build();
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

    /** 制造 RESERVED 孤儿：占位 + 扣币 + RESERVED 订单（模拟提交 PAYING 前崩溃）。 */
    private long seedReservedOrphan(UUID player, int titleId, long coinAmount) {
        return runInTx(c -> {
            titleRepository.tryReserveSoldCount(c, titleId);
            coinRepository.tryAdd(c, player, -coinAmount);
            return orderRepository.insert(c, new PurchaseOrder(0, UUID.randomUUID(), player,
                    "Crashed", titleId, OrderKind.SHOP, null, null, coinAmount, null, null, null,
                    null, OrderState.RESERVED, null, 1_000L, 1_000L));
        });
    }

    /** 权限空集快照。 */
    private static PurchaseSnapshot snapshot() {
        return snapshot(Set.of());
    }

    /** 指定持有权限的快照（服务侧只消费 today/经济可用/权限）。 */
    private static PurchaseSnapshot snapshot(Set<String> permissions) {
        return new PurchaseSnapshot(LocalDate.now(), true, BigDecimal.ZERO, 0,
                permissions, Map.of(), false);
    }

    /** 按 id 读订单。 */
    private PurchaseOrder findOrder(long orderId) {
        return withConnection(c -> orderRepository.findById(c, orderId)).orElseThrow();
    }

    /** 读玩家称号币余额。 */
    private long balance(UUID player) {
        return withConnection(c -> coinRepository.getBalance(c, player));
    }

    /** 读称号 sold_count。 */
    private long soldCount(int titleId) {
        return scalarLong("SELECT \"sold_count\" FROM " + tables.title() + " WHERE \"id\"=" + titleId);
    }

    /** 按 reason 统计币流水行数。 */
    private long coinLogCount(String reason) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.coinLog() + " WHERE \"reason\"='" + reason + "'");
    }

    /** 按事件类型统计审计行数。 */
    private long auditCount(String eventType) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='" + eventType + "'");
    }
}
