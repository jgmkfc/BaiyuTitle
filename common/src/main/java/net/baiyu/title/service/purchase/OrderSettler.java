package net.baiyu.title.service.purchase;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.coin.CoinChangeReason;
import net.baiyu.title.domain.coin.CoinLogEntry;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.exception.DuplicateActiveException;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.OwnershipService;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 订单收敛器（PurchaseService 的内部协作者，方法只允许在写线程调用）。
 * <p>承载状态机的三条收敛边：履约（from→FULFILLED + 授予 + 审计）、
 * 退款补偿（from→REFUNDED + 退称号币 + 释放售卖占位 + 流水）、转人工（from→MANUAL）。
 * <p>幂等纪律：每条边先做条件迁移（条件 UPDATE from-state），0 行命中即返回
 * {@link CompleteResult.AlreadyResolved} 且零副作用；缓存更新只在事务提交之后。
 */
public final class OrderSettler {

    /** 一天的毫秒数（持有有效期换算）。 */
    private static final long DAY_MILLIS = 86_400_000L;

    private final OrderRepository orderRepository;
    private final TitleRepository titleRepository;
    private final OwnershipRepository ownershipRepository;
    private final CoinRepository coinRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final TitleCatalogCache catalogCache;
    private final PlayerProfileCache profileCache;
    private final OwnershipService.PostGrantHook postGrantHook;

    /** 自定义订单履约器（CUSTOM 订单先建称号再授予并回填 title_id）。 */
    private final CustomOrderSettler customSettler;

    /**
     * 构造订单收敛器。
     *
     * @param orderRepository     订单仓储
     * @param titleRepository     称号仓储（释放售卖占位）
     * @param ownershipRepository 所有权仓储（履约授予）
     * @param coinRepository      称号币仓储（退款）
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param catalogCache        称号目录缓存（sold_count 写穿）
     * @param profileCache        玩家档案缓存（提交后增量更新）
     * @param postGrantHook       授予提交后钩子（可空 = no-op；paper 侧接羁绊检测/Post 事件）
     */
    public OrderSettler(OrderRepository orderRepository, TitleRepository titleRepository,
                        OwnershipRepository ownershipRepository, CoinRepository coinRepository,
                        AuditRepository auditRepository, TransactionRunner txRunner,
                        TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                        @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "订单仓储不能为空");
        this.titleRepository = Objects.requireNonNull(titleRepository, "称号仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.coinRepository = Objects.requireNonNull(coinRepository, "称号币仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.postGrantHook = postGrantHook == null ? OwnershipService.PostGrantHook.NO_OP : postGrantHook;
        this.customSettler = new CustomOrderSettler(orderRepository, titleRepository,
                ownershipRepository, auditRepository, txRunner, catalogCache, profileCache,
                this.postGrantHook);
    }

    /**
     * 履约：事务内 from→FULFILLED 条件迁移 + insertGrant(PURCHASE) + 审计；
     * 提交后合入档案缓存并回调授予钩子。
     * <p>授予撞唯一约束（期间被管理员授予同款等）时整体回滚并转人工，
     * 绝不让"钱货两讫失败"静默吞掉（fail_reason=DUPLICATE_ACTIVE）。
     *
     * @param order 订单（含价格快照）
     * @param from  期望的当前状态（PAYING / MANUAL）
     * @param actor 操作者（买家 / SYSTEM / 管理员）
     * @return 收敛结果
     */
    public CompleteResult fulfil(PurchaseOrder order, OrderState from, Actor actor) {
        if (order.titleId() == null) {
            if (order.isCustom() && order.customName() != null) {
                // CUSTOM 订单履约：先建称号再授予并回填 title_id
                return customSettler.fulfil(order, from, actor);
            }
            // 缺 customName 的异常订单留置人工兜底
            return markManual(order, from, "CUSTOM_UNSUPPORTED", actor);
        }
        long now = System.currentTimeMillis();
        int titleId = order.titleId();
        Long expiresAt = catalogCache.byId(titleId)
                .filter(t -> t.holdDurationDays() > 0)
                .map(t -> now + t.holdDurationDays() * DAY_MILLIS)
                .orElse(null);
        Ownership pending = new Ownership(0, titleId, order.playerUuid(), order.playerName(),
                GrantSource.PURCHASE, order.id(), now, expiresAt, OwnershipStatus.ACTIVE, null, null);
        Long ownershipId;
        try {
            ownershipId = txRunner.inTransaction(c -> {
                if (!orderRepository.transition(c, order.id(), from, OrderState.FULFILLED, null, now)) {
                    return null;
                }
                long id = ownershipRepository.insertGrant(c, pending);
                auditRepository.insert(c, new AuditRepository.AuditEntry(
                        "PURCHASE", actor.uuid(), actor.name(), order.playerUuid(), titleId, id,
                        "purchase_order", order.id(), "{\"orderUuid\":\"" + order.orderUuid() + "\"}", now));
                return id;
            });
        } catch (DuplicateActiveException e) {
            return markManual(order, from, "DUPLICATE_ACTIVE", actor);
        }
        if (ownershipId == null) {
            return alreadyResolved(order.id());
        }
        Ownership granted = new Ownership(ownershipId, titleId, order.playerUuid(), order.playerName(),
                GrantSource.PURCHASE, order.id(), now, expiresAt, OwnershipStatus.ACTIVE, null, null);
        profileCache.updateIfPresent(order.playerUuid(), profile -> profile.withOwnership(granted));
        postGrantHook.onGranted(order.playerUuid(), titleId, GrantSource.PURCHASE);
        return new CompleteResult.Fulfilled(order.id(), ownershipId);
    }

    /**
     * 退款补偿：事务内 from→REFUNDED 条件迁移 + 退称号币（含流水 REFUND）
     * + 释放售卖占位 + 审计；提交后更新档案余额与目录 sold_count 缓存。
     *
     * @param order  订单（含价格快照；coin_amount 非空才有币可退）
     * @param from   期望的当前状态（RESERVED / PAYING / MANUAL）
     * @param reason 退款原因（落 fail_reason 列）
     * @param actor  操作者
     * @return 收敛结果
     */
    public CompleteResult refund(PurchaseOrder order, OrderState from, String reason, Actor actor) {
        long now = System.currentTimeMillis();
        RefundTx result = txRunner.inTransaction(c -> {
            if (!orderRepository.transition(c, order.id(), from, OrderState.REFUNDED, reason, now)) {
                return null;
            }
            Long balanceAfter = null;
            if (order.coinAmount() != null) {
                coinRepository.ensureRow(c, order.playerUuid(), order.playerName(), now);
                coinRepository.tryAdd(c, order.playerUuid(), order.coinAmount());
                balanceAfter = coinRepository.getBalance(c, order.playerUuid());
                coinRepository.log(c, new CoinLogEntry(0, order.playerUuid(), order.coinAmount(),
                        balanceAfter, CoinChangeReason.REFUND, actor.name(), order.id(), now));
            }
            if (order.titleId() != null) {
                titleRepository.releaseSoldCount(c, order.titleId());
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "PURCHASE_REFUND", actor.uuid(), actor.name(), order.playerUuid(),
                    order.titleId(), null, "purchase_order", order.id(), reason, now));
            return new RefundTx(balanceAfter);
        });
        if (result == null) {
            return alreadyResolved(order.id());
        }
        if (result.balanceAfter() != null) {
            profileCache.updateIfPresent(order.playerUuid(),
                    profile -> profile.withCoinBalance(result.balanceAfter()));
        }
        if (order.titleId() != null) {
            adjustCachedSoldCount(catalogCache, order.titleId(), -1);
        }
        return new CompleteResult.Refunded(order.id());
    }

    /**
     * 转人工：事务内 from→MANUAL 条件迁移 + 审计（占位与已扣款保持原样，等管理员裁决）。
     *
     * @param order  订单
     * @param from   期望的当前状态
     * @param reason 转人工原因（落 fail_reason 列）
     * @param actor  操作者
     * @return 收敛结果
     */
    public CompleteResult markManual(PurchaseOrder order, OrderState from, String reason, Actor actor) {
        long now = System.currentTimeMillis();
        boolean done = txRunner.inTransaction(c -> {
            if (!orderRepository.transition(c, order.id(), from, OrderState.MANUAL, reason, now)) {
                return false;
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "PURCHASE_MANUAL", actor.uuid(), actor.name(), order.playerUuid(),
                    order.titleId(), null, "purchase_order", order.id(), reason, now));
            return true;
        });
        return done ? new CompleteResult.MarkedManual(order.id(), reason) : alreadyResolved(order.id());
    }

    /**
     * 目录缓存 sold_count 写穿（提交后调用；缓存未装载该称号时静默跳过）。
     *
     * @param catalogCache 目录缓存
     * @param titleId      称号 id
     * @param delta        变化量（占位 +1 / 释放 -1）
     */
    public static void adjustCachedSoldCount(TitleCatalogCache catalogCache, int titleId, int delta) {
        catalogCache.byId(titleId).ifPresent(title -> catalogCache.put(
                title.toBuilder().soldCount(Math.max(0, title.soldCount() + delta)).build()));
    }

    /**
     * 幂等空转结果（重查当前状态供调用方呈现；行已消失时按未知订单处理）。
     */
    private CompleteResult alreadyResolved(long orderId) {
        return txRunner.withConnection(c -> orderRepository.findById(c, orderId))
                .<CompleteResult>map(o -> new CompleteResult.AlreadyResolved(orderId, o.state()))
                .orElseGet(() -> new CompleteResult.UnknownOrder(orderId));
    }

    /**
     * 退款事务的提交后回执。
     *
     * @param balanceAfter 退款后余额（无币可退时为空）
     */
    private record RefundTx(@Nullable Long balanceAfter) {
    }
}
