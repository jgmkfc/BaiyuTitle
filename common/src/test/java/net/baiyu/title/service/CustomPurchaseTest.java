package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.order.OrderKind;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义称号订单全链双方言测试：CUSTOM 开单（config 价格四选一、
 * 名称归一化校验、防重）→ 履约先建称号再授予并回填 title_id；支付失败退款
 * 零建号；孤儿恢复 grant 策略可收敛 CUSTOM 订单。
 */
class CustomPurchaseTest extends BaseRepositoryTest {

    /** 自定义免单权限节点（config custom-title.permission-node 的测试值）。 */
    private static final String FREE_NODE = "baiyutitle.custom.free";

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;

    /** 称号币全链：扣币留 CUSTOM_TITLE 流水 → 履约建 CUSTOM 称号 + 授予 + 回填。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void coinChain_createsTitleGrantsAndBackfills(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        seedCoins(player, 500);

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginCustomPurchase(player, "Alice", "大侠", ConditionType.COIN,
                        snapshot(true, Set.of())).join());
        PurchaseOrder order = begun.order();
        assertEquals(OrderKind.CUSTOM, order.kind());
        assertEquals(OrderState.PAYING, order.state());
        assertNull(order.titleId(), "履约前 titleId 留空");
        assertEquals("<white>大侠", order.customName(), "归一化名进 customName（&f 规则）");
        assertEquals((Long) 200L, order.coinAmount());
        assertEquals(300L, balance(player), "称号币在 RESERVE 事务扣除");
        assertEquals(1L, coinLogCount("CUSTOM_TITLE"), "扣币留 CUSTOM_TITLE 流水");
        assertFalse(begun.plan().requiresExternalAction(), "纯称号币订单无外部支付动作");

        CompleteResult.Fulfilled fulfilled = assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(order.id(), PaymentOutcome.ok()).join());
        PurchaseOrder after = findOrder(order.id());
        assertEquals(OrderState.FULFILLED, after.state());
        assertTrue(after.titleId() != null && after.titleId() > 0, "履约后回填 title_id");
        Title created = withConnection(c -> titleRepository.loadAll(c, true)).stream()
                .filter(t -> t.id() == after.titleId()).findFirst().orElseThrow();
        assertEquals(TitleCategory.CUSTOM, created.category());
        assertEquals("<white>大侠", created.name());
        assertFalse(created.visible(), "自定义称号默认不可见（业务 §1.1.2.5）");
        assertFalse(created.tradable(), "自定义称号强制不可交易（不变量 §十 5）");
        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, player, after.titleId())).orElseThrow();
        assertEquals(fulfilled.ownershipId(), ownership.id());
        assertEquals(GrantSource.CUSTOM, ownership.source());
        assertEquals((Long) order.id(), ownership.sourceRef());
        assertNull(ownership.expiresAt(), "自定义称号授予恒永久");
        assertTrue(catalogCache.byId(after.titleId()).isPresent(), "提交后目录缓存写穿");
        assertEquals(1L, auditCount("CUSTOM_FULFIL"));
    }

    /** Vault 分支支付失败：REFUNDED、零建号零授予。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void vaultFailure_refundsWithoutCreatingTitle(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginCustomPurchase(player, "Alice", "&6豪侠", ConditionType.VAULT,
                        snapshot(true, Set.of())).join());
        assertEquals(0, new BigDecimal("80").compareTo(begun.plan().vaultAmount()),
                "支付指令携带 config 的 Vault 价格");

        assertInstanceOf(CompleteResult.Refunded.class, service.completePayment(
                begun.order().id(), PaymentOutcome.failed("VAULT_SHORT")).join());
        assertEquals(OrderState.REFUNDED, findOrder(begun.order().id()).state());
        assertEquals(0L, countRows(tables.title()), "退款不得建号");
        assertEquals(0L, countRows(tables.titleOwnership()));

        // 经济不可用时 Vault 分支直接拒绝
        assertEquals(BeginResult.RejectReason.ECONOMY_UNAVAILABLE, rejectReason(
                service.beginCustomPurchase(player, "Alice", "豪侠", ConditionType.VAULT,
                        snapshot(false, Set.of())).join()));
    }

    /** 权限分支：未持节点拒绝；持有即免单（零应付）并可履约。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void permissionBranch_freeWhenHeld(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();

        assertEquals(BeginResult.RejectReason.CONDITIONS_UNMET, rejectReason(
                service.beginCustomPurchase(player, "Alice", "义士", ConditionType.PERMISSION,
                        snapshot(true, Set.of())).join()));

        BeginResult.Begun begun = assertInstanceOf(BeginResult.Begun.class,
                service.beginCustomPurchase(player, "Alice", "义士", ConditionType.PERMISSION,
                        snapshot(true, Set.of(FREE_NODE))).join());
        assertEquals(FREE_NODE, begun.plan().permissionNode());
        assertNull(begun.order().coinAmount());
        assertNull(begun.order().vaultAmount());
        assertInstanceOf(CompleteResult.Fulfilled.class,
                service.completePayment(begun.order().id(), PaymentOutcome.ok()).join());
        assertEquals(1L, countRows(tables.title()));
    }

    /** 名称校验与防重：51 码点/空名拒绝；同玩家非终态 CUSTOM 订单在途拒新单。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void nameValidationAndPendingGuard(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        seedCoins(player, 500);

        assertEquals(BeginResult.RejectReason.NAME_INVALID, rejectReason(
                service.beginCustomPurchase(player, "Alice", "字".repeat(51), ConditionType.COIN,
                        snapshot(true, Set.of())).join()));
        assertEquals(BeginResult.RejectReason.NAME_INVALID, rejectReason(
                service.beginCustomPurchase(player, "Alice", "  ", ConditionType.COIN,
                        snapshot(true, Set.of())).join()));
        assertEquals(BeginResult.RejectReason.INVALID_BRANCH, rejectReason(
                service.beginCustomPurchase(player, "Alice", "大侠", null,
                        snapshot(true, Set.of())).join()));
        assertEquals(0L, countRows(tables.purchaseOrder()), "拒绝路径零落库");

        assertInstanceOf(BeginResult.Begun.class,
                service.beginCustomPurchase(player, "Alice", "大侠", ConditionType.COIN,
                        snapshot(true, Set.of())).join());
        assertEquals(BeginResult.RejectReason.PENDING_ORDER_EXISTS, rejectReason(
                service.beginCustomPurchase(player, "Alice", "二号", ConditionType.COIN,
                        snapshot(true, Set.of())).join()));
        assertEquals(300L, balance(player), "防重拒绝不得二次扣币");
    }

    /** 余额不足：整体回滚零残留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insufficientCoins_rollsBack(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        seedCoins(player, 199);

        assertEquals(BeginResult.RejectReason.INSUFFICIENT_COINS, rejectReason(
                service.beginCustomPurchase(player, "Alice", "大侠", ConditionType.COIN,
                        snapshot(true, Set.of())).join()));
        assertEquals(0L, countRows(tables.purchaseOrder()));
        assertEquals(199L, balance(player));
        assertEquals(0L, coinLogCount("CUSTOM_TITLE"));
    }

    /** 孤儿恢复：PAYING 的 CUSTOM 订单按 grant 策略履约（建称号 + 授予 + 回填）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void recovery_grantPolicyFulfilsCustomOrphan(TestDialect dialect) {
        initDatabase(dialect);
        PurchaseService service = newService(RecoveryPolicy.GRANT);
        UUID player = UUID.randomUUID();
        seedCoins(player, 200);
        long orderId = assertInstanceOf(BeginResult.Begun.class,
                service.beginCustomPurchase(player, "Alice", "浴火", ConditionType.COIN,
                        snapshot(true, Set.of())).join()).order().id();

        RecoveryReport report = service.recoverOrphans().join();
        assertEquals(1, report.payingGranted());
        PurchaseOrder after = findOrder(orderId);
        assertEquals(OrderState.FULFILLED, after.state());
        assertTrue(after.titleId() != null && after.titleId() > 0);
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, player, after.titleId())).isPresent());
    }

    // ---------- 工具 ----------

    /** 构造服务：coin=200 / vault=80 / 物品 3 颗钻石 / 免单权限节点固定。 */
    private PurchaseService newService(RecoveryPolicy policy) {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        PluginConfig.CustomTitleSettings settings = new PluginConfig.CustomTitleSettings();
        settings.coinPrice = 200;
        settings.vaultPrice = 80.0;
        settings.itemMaterial = "minecraft:diamond";
        settings.itemAmount = 3;
        settings.permissionNode = FREE_NODE;
        return new PurchaseService(orderRepository, titleRepository, ownershipRepository,
                coinRepository, auditRepository, txRunner, dbExecutor, catalogCache, profileCache,
                null, policy, () -> settings);
    }

    /** 上下文快照（今天 + 经济可用性 + 持有权限）。 */
    private static PurchaseSnapshot snapshot(boolean economyAvailable, Set<String> permissions) {
        return new PurchaseSnapshot(LocalDate.now(), economyAvailable, BigDecimal.ZERO, 0,
                permissions, Map.of(), false);
    }

    /** 断言为拒绝并取原因。 */
    private static BeginResult.RejectReason rejectReason(BeginResult result) {
        return assertInstanceOf(BeginResult.Rejected.class, result).reason();
    }

    /** 直接铺底称号币余额（种子数据，不留流水）。 */
    private void seedCoins(UUID player, long amount) {
        runInTx(c -> {
            coinRepository.ensureRow(c, player, "Seed", 1L);
            coinRepository.tryAdd(c, player, amount);
            return null;
        });
    }

    /** 按 id 读订单。 */
    private PurchaseOrder findOrder(long orderId) {
        return withConnection(c -> orderRepository.findById(c, orderId)).orElseThrow();
    }

    /** 读玩家称号币余额。 */
    private long balance(UUID player) {
        return withConnection(c -> coinRepository.getBalance(c, player));
    }

    /** 按 reason 统计币流水行数。 */
    private long coinLogCount(String reason) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.coinLog()
                + " WHERE \"reason\"='" + reason + "'");
    }

    /** 按事件类型统计审计行数。 */
    private long auditCount(String eventType) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='" + eventType + "'");
    }
}
