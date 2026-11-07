package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.purchase.BeginResult;
import net.baiyu.title.service.purchase.CompleteResult;
import net.baiyu.title.service.purchase.OrderCreator;
import net.baiyu.title.service.purchase.OrderSettler;
import net.baiyu.title.service.purchase.PaymentOutcome;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import net.baiyu.title.service.purchase.RecoveryReport;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 购买服务：订单状态机唯一入口。
 * <p>状态机（沿边条件迁移，DB 条件 UPDATE 唯一裁决）：
 * beginPurchase = 权威复核 → 售卖占位 → 扣称号币 → RESERVED 落库提交 →
 * 独立提交 RESERVED→PAYING → 返回外部支付指令；beginCustomPurchase = CUSTOM
 * 订单同链（titleId 留空，履约时建称号回填）；completePayment = PAYING→FULFILLED
 * 履约 / PAYING→REFUNDED 补偿；recoverOrphans = 启动孤儿收敛（RESERVED 恒退款、
 * PAYING 按 {@link RecoveryPolicy}、MANUAL 留置）；resolveManual = 管理员裁决。
 * <p>纪律：全程写线程；称号币只在 RESERVE 事务扣（流水同事务，不变量 §十 9）、
 * Vault/物品只在 PAYING 主线程动作一次（不双花）；同玩家同称号存在非终态订单即拒绝
 * （幂等防重）；缓存更新只在 commit 之后。Pre 事件由 paper 侧在调用本服务前于主线程发出。
 */
public final class PurchaseService {

    private final OrderRepository orderRepository;
    private final TransactionRunner txRunner;
    private final DbExecutor dbExecutor;
    private final OrderCreator creator;
    private final OrderSettler settler;
    private final RecoveryPolicy recoveryPolicy;

    /**
     * 构造购买服务。
     *
     * @param orderRepository     订单仓储
     * @param titleRepository     称号仓储
     * @param ownershipRepository 所有权仓储
     * @param coinRepository      称号币仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op）
     * @param recoveryPolicy      PAYING 孤儿恢复策略（config purchase.recovery）
     * @param customSettings      自定义称号付款价格供应器（config custom-title）
     */
    public PurchaseService(OrderRepository orderRepository, TitleRepository titleRepository,
                           OwnershipRepository ownershipRepository, CoinRepository coinRepository,
                           AuditRepository auditRepository, TransactionRunner txRunner,
                           DbExecutor dbExecutor, TitleCatalogCache catalogCache,
                           PlayerProfileCache profileCache,
                           @Nullable OwnershipService.PostGrantHook postGrantHook,
                           RecoveryPolicy recoveryPolicy,
                           Supplier<PluginConfig.CustomTitleSettings> customSettings) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "订单仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "恢复策略不能为空");
        this.creator = new OrderCreator(orderRepository, titleRepository, ownershipRepository,
                coinRepository, txRunner, catalogCache, profileCache, customSettings);
        this.settler = new OrderSettler(orderRepository, titleRepository, ownershipRepository,
                coinRepository, Objects.requireNonNull(auditRepository, "审计仓储不能为空"),
                txRunner, catalogCache, profileCache, postGrantHook);
    }

    /**
     * 发起购买（SHOP 订单）：写线程事务内权威复核并推进到 PAYING。
     * <p>任一复核失败即整体回滚、零持久化副作用；成功返回 PAYING 订单与外部支付指令，
     * paper 侧主线程执行 Vault/物品动作后回调 {@link #completePayment}。
     *
     * @param player     买家 UUID
     * @param playerName 买家名称
     * @param titleId    目标称号 id
     * @param chosenCond "或"逻辑所选分支（AND/免费传空；服务端强制"含权限且持有→免单"）
     * @param snapshot   玩家上下文快照（主线程预收集）
     * @return 发起结果（Begun / Rejected）
     */
    public CompletableFuture<BeginResult> beginPurchase(UUID player, String playerName, int titleId,
                                                        @Nullable ConditionType chosenCond,
                                                        PurchaseSnapshot snapshot) {
        Objects.requireNonNull(player, "买家 UUID 不能为空");
        Objects.requireNonNull(playerName, "买家名称不能为空");
        Objects.requireNonNull(snapshot, "上下文快照不能为空");
        return dbExecutor.submitWrite(() ->
                creator.beginShop(player, playerName, titleId, chosenCond, snapshot));
    }

    /**
     * 发起自定义称号购买（CUSTOM 订单六 / 业务 §1.3.2.3.1.4）：
     * 名称归一化校验 → 按 config custom-title 价格裁决应付 → RESERVE→PAYING。
     * <p>订单 titleId 留空、customName 存归一化名；履约时先建 CUSTOM 称号
     * （不可交易、默认隐藏）再授予并回填 title_id。paper 侧主线程执行支付指令后
     * 回调 {@link #completePayment}。
     *
     * @param player       买家 UUID
     * @param playerName   买家名称
     * @param rawTitleName 原始称号名（铁砧输入，三格式颜色码均可）
     * @param chosenCond   所选付款方式（VAULT/COIN/ITEM/PERMISSION 四选一）
     * @param snapshot     玩家上下文快照（主线程预收集）
     * @return 发起结果（Begun / Rejected）
     */
    public CompletableFuture<BeginResult> beginCustomPurchase(UUID player, String playerName,
                                                              String rawTitleName,
                                                              @Nullable ConditionType chosenCond,
                                                              PurchaseSnapshot snapshot) {
        Objects.requireNonNull(player, "买家 UUID 不能为空");
        Objects.requireNonNull(playerName, "买家名称不能为空");
        Objects.requireNonNull(snapshot, "上下文快照不能为空");
        return dbExecutor.submitWrite(() ->
                creator.beginCustom(player, playerName, rawTitleName, chosenCond, snapshot));
    }

    /**
     * 完成支付：外部支付成功 → PAYING→FULFILLED 履约授予；失败 → PAYING→REFUNDED 补偿。
     * <p>幂等：重复回调（双击/重试）时条件迁移 0 行命中，返回 AlreadyResolved 零副作用。
     *
     * @param orderId 订单 id
     * @param outcome 外部支付结果
     * @return 收敛结果
     */
    public CompletableFuture<CompleteResult> completePayment(long orderId, PaymentOutcome outcome) {
        Objects.requireNonNull(outcome, "支付结果不能为空");
        return dbExecutor.submitWrite(() -> {
            PurchaseOrder order = txRunner.withConnection(c -> orderRepository.findById(c, orderId))
                    .orElse(null);
            if (order == null) {
                return new CompleteResult.UnknownOrder(orderId);
            }
            Actor buyer = Actor.of(order.playerUuid(), order.playerName());
            return outcome.success()
                    ? settler.fulfil(order, OrderState.PAYING, buyer)
                    : settler.refund(order, OrderState.PAYING, outcome.failReason(), buyer);
        });
    }

    /**
     * 启动孤儿订单恢复（onEnable 调用一次）：RESERVED 恒退款（必未触碰 Vault）；
     * PAYING 按策略 grant/refund/manual；既有 MANUAL 留置不动。
     * <p>逐单独立事务收敛，单笔失败不影响其余；条件迁移保证重复执行幂等。
     *
     * @return 恢复报告（供控制台告警）
     */
    public CompletableFuture<RecoveryReport> recoverOrphans() {
        return dbExecutor.submitWrite(() -> {
            List<PurchaseOrder> orphans = txRunner.withConnection(orderRepository::findNonTerminal);
            int reservedRefunded = 0;
            int granted = 0;
            int refunded = 0;
            int markedManual = 0;
            int manualPending = 0;
            for (PurchaseOrder order : orphans) {
                switch (order.state()) {
                    case RESERVED -> {
                        settler.refund(order, OrderState.RESERVED, "RECOVERY_RESERVED", Actor.system());
                        reservedRefunded++;
                    }
                    case PAYING -> {
                        switch (recoveryPolicy) {
                            case GRANT -> {
                                CompleteResult result =
                                        settler.fulfil(order, OrderState.PAYING, Actor.system());
                                if (result instanceof CompleteResult.MarkedManual) {
                                    markedManual++;
                                } else {
                                    granted++;
                                }
                            }
                            case REFUND -> {
                                settler.refund(order, OrderState.PAYING, "RECOVERY_REFUND", Actor.system());
                                refunded++;
                            }
                            case MANUAL -> {
                                settler.markManual(order, OrderState.PAYING, "RECOVERY_MANUAL", Actor.system());
                                markedManual++;
                            }
                        }
                    }
                    case MANUAL -> manualPending++;
                    default -> { } // findNonTerminal 不返回终态
                }
            }
            return new RecoveryReport(reservedRefunded, granted, refunded, markedManual, manualPending);
        });
    }

    /**
     * 管理员裁决 MANUAL 留置订单（{@code /byt admin order resolve <id> grant|refund}）。
     *
     * @param orderId 订单 id
     * @param grant   true = 履约授予；false = 退款补偿
     * @param actor   管理员操作者
     * @return 收敛结果（订单不在 MANUAL 态时返回 AlreadyResolved）
     */
    public CompletableFuture<CompleteResult> resolveManual(long orderId, boolean grant, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> {
            PurchaseOrder order = txRunner.withConnection(c -> orderRepository.findById(c, orderId))
                    .orElse(null);
            if (order == null) {
                return new CompleteResult.UnknownOrder(orderId);
            }
            if (order.state() != OrderState.MANUAL) {
                return new CompleteResult.AlreadyResolved(orderId, order.state());
            }
            return grant
                    ? settler.fulfil(order, OrderState.MANUAL, actor)
                    : settler.refund(order, OrderState.MANUAL, "ADMIN_REFUND", actor);
        });
    }
}
