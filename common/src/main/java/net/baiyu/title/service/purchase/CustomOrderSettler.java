package net.baiyu.title.service.purchase;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.text.PlainTextExtractor;

import java.util.Objects;

/**
 * 自定义订单履约器（{@link OrderSettler} 的内部协作者，方法只允许在写线程调用）。
 * <p>CUSTOM 订单履约：同一事务内 from→FULFILLED 条件迁移 →
 * 以订单 customName（开单时已归一化校验）创建 CUSTOM 称号（visible=false 按业务
 * §1.1.2.5；tradable 由领域层强制 false，不变量 §十 5）→ 授予（恒永久，
 * source=CUSTOM）→ fillTitleId 回填 → 审计；提交后写穿目录与档案缓存并回调授予钩子。
 */
final class CustomOrderSettler {

    private final OrderRepository orderRepository;
    private final TitleRepository titleRepository;
    private final OwnershipRepository ownershipRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final TitleCatalogCache catalogCache;
    private final PlayerProfileCache profileCache;
    private final OwnershipService.PostGrantHook postGrantHook;

    /**
     * 构造自定义订单履约器（依赖与 {@link OrderSettler} 同源）。
     */
    CustomOrderSettler(OrderRepository orderRepository, TitleRepository titleRepository,
                       OwnershipRepository ownershipRepository, AuditRepository auditRepository,
                       TransactionRunner txRunner, TitleCatalogCache catalogCache,
                       PlayerProfileCache profileCache, OwnershipService.PostGrantHook postGrantHook) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "订单仓储不能为空");
        this.titleRepository = Objects.requireNonNull(titleRepository, "称号仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.postGrantHook = Objects.requireNonNull(postGrantHook, "授予钩子不能为空");
    }

    /**
     * 履约 CUSTOM 订单：条件迁移命中才建称号 + 授予 + 回填（0 行命中即幂等空转）。
     * <p>称号是全新行，授予不会撞唯一约束；恒永久授予（自定义称号无持有有效期语义）。
     *
     * @param order 订单（customName 非空）
     * @param from  期望的当前状态（PAYING / MANUAL）
     * @param actor 操作者
     * @return 收敛结果
     */
    CompleteResult fulfil(PurchaseOrder order, OrderState from, Actor actor) {
        long now = System.currentTimeMillis();
        String name = order.customName();
        String plain = PlainTextExtractor.extract(name);
        CustomTx done = txRunner.inTransaction(c -> {
            if (!orderRepository.transition(c, order.id(), from, OrderState.FULFILLED, null, now)) {
                return null;
            }
            Title fresh = Title.builder()
                    .name(name).namePlain(plain).category(TitleCategory.CUSTOM)
                    .visible(false)
                    .createdBy(order.playerUuid()).createdByName(order.playerName())
                    .createdAt(now).updatedAt(now)
                    .build();
            int titleId = titleRepository.insert(c, fresh);
            long ownershipId = ownershipRepository.insertGrant(c, new Ownership(0, titleId,
                    order.playerUuid(), order.playerName(), GrantSource.CUSTOM, order.id(),
                    now, null, OwnershipStatus.ACTIVE, null, null));
            orderRepository.fillTitleId(c, order.id(), titleId, now);
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "CUSTOM_FULFIL", actor.uuid(), actor.name(), order.playerUuid(), titleId,
                    ownershipId, "purchase_order", order.id(),
                    "{\"orderUuid\":\"" + order.orderUuid() + "\"}", now));
            return new CustomTx(fresh.toBuilder().id(titleId).build(), ownershipId);
        });
        if (done == null) {
            // 幂等空转：重查当前状态供调用方呈现（与 OrderSettler 同口径）
            return txRunner.withConnection(c -> orderRepository.findById(c, order.id()))
                    .<CompleteResult>map(o -> new CompleteResult.AlreadyResolved(order.id(), o.state()))
                    .orElseGet(() -> new CompleteResult.UnknownOrder(order.id()));
        }
        catalogCache.put(done.title());
        Ownership granted = new Ownership(done.ownershipId(), done.title().id(), order.playerUuid(),
                order.playerName(), GrantSource.CUSTOM, order.id(), now, null,
                OwnershipStatus.ACTIVE, null, null);
        profileCache.updateIfPresent(order.playerUuid(), profile -> profile.withOwnership(granted));
        postGrantHook.onGranted(order.playerUuid(), done.title().id(), GrantSource.CUSTOM);
        return new CompleteResult.Fulfilled(order.id(), done.ownershipId());
    }

    /**
     * 履约事务的提交后回执。
     *
     * @param title       已建 CUSTOM 称号（含生成 id）
     * @param ownershipId 新所有权 id
     */
    private record CustomTx(Title title, long ownershipId) {
    }
}
