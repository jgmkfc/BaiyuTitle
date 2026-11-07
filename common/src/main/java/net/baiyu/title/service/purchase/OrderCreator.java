package net.baiyu.title.service.purchase;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.coin.CoinChangeReason;
import net.baiyu.title.domain.coin.CoinLogEntry;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.order.OrderKind;
import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.text.ColorNormalizer;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 开单器（PurchaseService 的内部协作者，方法只允许在写线程调用）。
 * <p>承载状态机的两条开单边：SHOP 开单 = 权威复核 → 售卖占位 →
 * 扣称号币 → RESERVED 落库提交 → 独立提交 RESERVED→PAYING；CUSTOM 开单 =
 * 名称归一化校验 → 按 config 付款价格裁决应付 → 同一 RESERVE/PAYING 链，
 * 订单 titleId 留空、customName 存归一化名（履约时先建称号再回填）。
 * <p>纪律：称号币只在 RESERVE 事务扣（流水同事务）；任一复核失败整体回滚零残留；
 * 缓存写穿只在 commit 之后。
 */
public final class OrderCreator {

    private final OrderRepository orderRepository;
    private final TitleRepository titleRepository;
    private final OwnershipRepository ownershipRepository;
    private final CoinRepository coinRepository;
    private final TransactionRunner txRunner;
    private final TitleCatalogCache catalogCache;
    private final PlayerProfileCache profileCache;

    /** 自定义称号付款价格（config custom-title；供应器随 reload 取新值）。 */
    private final Supplier<PluginConfig.CustomTitleSettings> customSettings;

    /**
     * 构造开单器。
     *
     * @param orderRepository     订单仓储
     * @param titleRepository     称号仓储（售卖占位）
     * @param ownershipRepository 所有权仓储（已拥有复核）
     * @param coinRepository      称号币仓储
     * @param txRunner            事务执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param customSettings      自定义称号付款价格供应器
     */
    public OrderCreator(OrderRepository orderRepository, TitleRepository titleRepository,
                        OwnershipRepository ownershipRepository, CoinRepository coinRepository,
                        TransactionRunner txRunner, TitleCatalogCache catalogCache,
                        PlayerProfileCache profileCache,
                        Supplier<PluginConfig.CustomTitleSettings> customSettings) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "订单仓储不能为空");
        this.titleRepository = Objects.requireNonNull(titleRepository, "称号仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.coinRepository = Objects.requireNonNull(coinRepository, "称号币仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.customSettings = Objects.requireNonNull(customSettings, "自定义付款配置供应器不能为空");
    }

    /**
     * SHOP 开单（缓存静态校验 → 应付裁决 → RESERVE 事务 → PAYING 独立提交）。
     *
     * @param player     买家 UUID
     * @param playerName 买家名称
     * @param titleId    目标称号 id
     * @param chosenCond "或"逻辑所选分支（AND/免费传空）
     * @param snapshot   玩家上下文快照（主线程预收集）
     * @return 发起结果
     */
    public BeginResult beginShop(UUID player, String playerName, int titleId,
                                 @Nullable ConditionType chosenCond, PurchaseSnapshot snapshot) {
        Title title = catalogCache.byId(titleId).filter(t -> !t.isDeleted()).orElse(null);
        if (title == null) {
            return new BeginResult.Rejected(BeginResult.RejectReason.TITLE_NOT_FOUND);
        }
        if (title.category() != TitleCategory.NORMAL) {
            return new BeginResult.Rejected(BeginResult.RejectReason.NOT_PURCHASABLE);
        }
        if (!title.visible()) {
            return new BeginResult.Rejected(BeginResult.RejectReason.HIDDEN);
        }
        if (!title.isWithinWindow(snapshot.today())) {
            return new BeginResult.Rejected(BeginResult.RejectReason.OUTSIDE_WINDOW);
        }
        long now = System.currentTimeMillis();
        UUID orderUuid = UUID.randomUUID();
        try {
            ChargeResolver.Charges charges = ChargeResolver.resolve(title, chosenCond, snapshot);
            // RESERVE 事务：防重 → 已拥有 → 占位 → 扣币（含流水）→ 插订单（任一失败整体回滚）
            ReserveTx reserved = txRunner.inTransaction(c -> {
                if (orderRepository.existsNonTerminal(c, player, titleId)) {
                    throw new PurchaseAbort(BeginResult.RejectReason.PENDING_ORDER_EXISTS);
                }
                if (ownershipRepository.findActive(c, player, titleId).isPresent()) {
                    throw new PurchaseAbort(BeginResult.RejectReason.ALREADY_OWNED);
                }
                if (!titleRepository.tryReserveSoldCount(c, titleId)) {
                    throw new PurchaseAbort(BeginResult.RejectReason.SOLD_OUT);
                }
                Long balanceAfter = null;
                if (charges.coinAmount() != null) {
                    balanceAfter = chargeCoins(c, player, playerName, charges.coinAmount(), now);
                }
                long orderId = orderRepository.insert(c, new PurchaseOrder(0, orderUuid, player,
                        playerName, titleId, OrderKind.SHOP, charges.chosenCond(),
                        charges.vaultAmount(), charges.coinAmount(), charges.itemBase64(),
                        charges.itemAmount(), charges.permissionNode(), null,
                        OrderState.RESERVED, null, now, now));
                if (balanceAfter != null) {
                    coinRepository.log(c, new CoinLogEntry(0, player, -charges.coinAmount(),
                            balanceAfter, CoinChangeReason.PURCHASE, playerName, orderId, now));
                }
                return new ReserveTx(orderId, balanceAfter);
            });
            // RESERVED 单独提交后才允许触碰 Vault：独立事务推进 PAYING
            txRunner.inTransaction(c -> orderRepository.transition(c, reserved.orderId(),
                    OrderState.RESERVED, OrderState.PAYING, null, now));
            // 提交后缓存写穿
            OrderSettler.adjustCachedSoldCount(catalogCache, titleId, 1);
            writeThroughBalance(player, reserved.balanceAfter());
            PurchaseOrder paying = new PurchaseOrder(reserved.orderId(), orderUuid, player,
                    playerName, titleId, OrderKind.SHOP, charges.chosenCond(), charges.vaultAmount(),
                    charges.coinAmount(), charges.itemBase64(), charges.itemAmount(),
                    charges.permissionNode(), null, OrderState.PAYING, null, now, now);
            return new BeginResult.Begun(paying, PaymentPlan.of(paying));
        } catch (PurchaseAbort abort) {
            return new BeginResult.Rejected(abort.reason());
        }
    }

    /**
     * CUSTOM 开单：名称归一化校验 → config 价格裁决 → RESERVE/PAYING 链
     * （不占售卖名额、不查目录——称号在履约时才创建）。
     * <p>约定：CUSTOM 订单的 item_data 列存 config 的材质 namespaced key
     * （非 Base64 序列化物品），paper 侧按订单 kind 区分扣付口径。
     *
     * @param player       买家 UUID
     * @param playerName   买家名称
     * @param rawTitleName 原始称号名（三格式颜色码均可）
     * @param chosenCond   所选付款方式（四选一，必填）
     * @param snapshot     玩家上下文快照
     * @return 发起结果
     */
    public BeginResult beginCustom(UUID player, String playerName, String rawTitleName,
                                   @Nullable ConditionType chosenCond, PurchaseSnapshot snapshot) {
        String name;
        try {
            name = ColorNormalizer.normalizeName(rawTitleName);
            TitleCatalogService.validateName(name);
        } catch (IllegalArgumentException e) {
            return new BeginResult.Rejected(BeginResult.RejectReason.NAME_INVALID);
        }
        long now = System.currentTimeMillis();
        UUID orderUuid = UUID.randomUUID();
        try {
            ChargeResolver.Charges charges = resolveCustomCharges(chosenCond, snapshot);
            ReserveTx reserved = txRunner.inTransaction(c -> {
                // 防重：同玩家已有非终态 CUSTOM 订单即拒绝（titleId 为空，无法用 existsNonTerminal）
                boolean pendingCustom = orderRepository.findNonTerminal(c).stream()
                        .anyMatch(o -> o.isCustom() && o.playerUuid().equals(player));
                if (pendingCustom) {
                    throw new PurchaseAbort(BeginResult.RejectReason.PENDING_ORDER_EXISTS);
                }
                Long balanceAfter = null;
                if (charges.coinAmount() != null) {
                    balanceAfter = chargeCoins(c, player, playerName, charges.coinAmount(), now);
                }
                long orderId = orderRepository.insert(c, new PurchaseOrder(0, orderUuid, player,
                        playerName, null, OrderKind.CUSTOM, charges.chosenCond(),
                        charges.vaultAmount(), charges.coinAmount(), charges.itemBase64(),
                        charges.itemAmount(), charges.permissionNode(), name,
                        OrderState.RESERVED, null, now, now));
                if (balanceAfter != null) {
                    coinRepository.log(c, new CoinLogEntry(0, player, -charges.coinAmount(),
                            balanceAfter, CoinChangeReason.CUSTOM_TITLE, playerName, orderId, now));
                }
                return new ReserveTx(orderId, balanceAfter);
            });
            txRunner.inTransaction(c -> orderRepository.transition(c, reserved.orderId(),
                    OrderState.RESERVED, OrderState.PAYING, null, now));
            writeThroughBalance(player, reserved.balanceAfter());
            PurchaseOrder paying = new PurchaseOrder(reserved.orderId(), orderUuid, player,
                    playerName, null, OrderKind.CUSTOM, charges.chosenCond(), charges.vaultAmount(),
                    charges.coinAmount(), charges.itemBase64(), charges.itemAmount(),
                    charges.permissionNode(), name, OrderState.PAYING, null, now, now);
            return new BeginResult.Begun(paying, PaymentPlan.of(paying));
        } catch (PurchaseAbort abort) {
            return new BeginResult.Rejected(abort.reason());
        }
    }

    /**
     * 按 config 价格把所选付款方式裁决为应付项（业务 §1.3.2.3.1.4 四选一）：
     * Vault 需经济可用；权限方式需已持有节点（持有即免单）；
     * 价格 ≤ 0 的货币方式视为免费。
     */
    private ChargeResolver.Charges resolveCustomCharges(@Nullable ConditionType chosen,
                                                        PurchaseSnapshot snapshot) {
        if (chosen == null) {
            throw new PurchaseAbort(BeginResult.RejectReason.INVALID_BRANCH);
        }
        PluginConfig.CustomTitleSettings cfg = customSettings.get();
        return switch (chosen) {
            case VAULT -> {
                if (!snapshot.economyAvailable()) {
                    throw new PurchaseAbort(BeginResult.RejectReason.ECONOMY_UNAVAILABLE);
                }
                BigDecimal price = cfg.vaultPrice > 0 ? BigDecimal.valueOf(cfg.vaultPrice) : null;
                yield new ChargeResolver.Charges(chosen, price, null, null, null, null);
            }
            case COIN -> new ChargeResolver.Charges(chosen, null,
                    cfg.coinPrice > 0 ? cfg.coinPrice : null, null, null, null);
            case ITEM -> new ChargeResolver.Charges(chosen, null, null,
                    cfg.itemMaterial, cfg.itemAmount, null);
            case PERMISSION -> {
                if (!snapshot.hasPermission(cfg.permissionNode)) {
                    throw new PurchaseAbort(BeginResult.RejectReason.CONDITIONS_UNMET);
                }
                yield new ChargeResolver.Charges(chosen, null, null, null, null, cfg.permissionNode);
            }
        };
    }

    /**
     * RESERVE 事务内扣称号币（先确保余额行、后条件 UPDATE 防透支），返回扣后余额。
     */
    private Long chargeCoins(Connection c, UUID player, String playerName,
                             long amount, long now) throws SQLException {
        coinRepository.ensureRow(c, player, playerName, now);
        if (!coinRepository.tryAdd(c, player, -amount)) {
            throw new PurchaseAbort(BeginResult.RejectReason.INSUFFICIENT_COINS);
        }
        return coinRepository.getBalance(c, player);
    }

    /**
     * 提交后把扣币回执写穿档案缓存（无币应付时为 no-op）。
     */
    private void writeThroughBalance(UUID player, @Nullable Long balanceAfter) {
        if (balanceAfter != null) {
            profileCache.updateIfPresent(player, profile -> profile.withCoinBalance(balanceAfter));
        }
    }

    /**
     * RESERVE 事务的提交后回执。
     *
     * @param orderId      新订单 id
     * @param balanceAfter 扣币后余额（无币应付时为空）
     */
    private record ReserveTx(long orderId, @Nullable Long balanceAfter) {
    }
}
