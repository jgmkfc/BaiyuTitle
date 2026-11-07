package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.Page;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.trade.AcceptResult;
import net.baiyu.title.service.trade.CreateResult;
import net.baiyu.title.service.trade.OfferCreator;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.service.trade.OfferValidator;
import net.baiyu.title.service.trade.ResolveResult;
import net.baiyu.title.service.trade.TradeBlockReason;
import net.baiyu.title.service.trade.TradeResolver;
import net.baiyu.title.service.trade.TradeSettler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 交易服务：玩家间称号交易状态机唯一入口。
 * <p>状态机 PENDING→COMPLETED/REJECTED/EXPIRED/DESTROYED，PENDING 唯一可迁出；
 * 交易期间不锁称号，成交事务内全量复核，复核失败整单作废（DESTROYED）；
 * 并发接受由条件迁移唯一裁决。BOND/CUSTOM/GROUP 恒不可交易；sold_count 不触碰。
 * <p>总开关 config trade.enabled 把守创建与接受两个入口；报价 TTL 由
 * trade.expire-hours 决定（默认 72h）。Pre 事件由 paper 侧在调用本服务前于
 * 主线程发出；Post 事件与羁绊检测经授予钩子在 commit 之后接线。
 */
public final class TradeService {

    private final TradeRepository tradeRepository;
    private final TransactionRunner txRunner;
    private final DbExecutor dbExecutor;
    private final Supplier<PluginConfig.TradeSettings> settings;
    private final PlayerProfileCache profileCache;
    private final OfferCreator creator;
    private final TradeSettler settler;
    private final TradeResolver resolver;

    /**
     * 构造交易服务。
     *
     * @param tradeRepository     交易仓储
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param settings            交易设置供应器（reload 后读到新值）
     * @param postGrantHook       授予提交后钩子（可空 = no-op；羁绊检测接线位）
     */
    public TradeService(TradeRepository tradeRepository, OwnershipRepository ownershipRepository,
                        SlotRepository slotRepository, AuditRepository auditRepository,
                        TransactionRunner txRunner, DbExecutor dbExecutor,
                        TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                        Supplier<PluginConfig.TradeSettings> settings,
                        @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.tradeRepository = Objects.requireNonNull(tradeRepository, "交易仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.settings = Objects.requireNonNull(settings, "交易设置供应器不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        OfferValidator validator = new OfferValidator(
                Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空"),
                Objects.requireNonNull(catalogCache, "称号目录缓存不能为空"));
        this.creator = new OfferCreator(tradeRepository,
                Objects.requireNonNull(auditRepository, "审计仓储不能为空"), txRunner, validator);
        this.settler = new TradeSettler(tradeRepository, ownershipRepository,
                Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空"), auditRepository,
                txRunner, validator, Objects.requireNonNull(profileCache, "玩家档案缓存不能为空"),
                postGrantHook);
        this.resolver = new TradeResolver(tradeRepository, auditRepository, txRunner);
    }

    /**
     * 创建报价：写事务内权威校验（每侧 ≤12、双侧不同时为空、归属/未到期/可交易、
     * 无重复标的）→ 插 PENDING 交易 + 标的（TTL = trade.expire-hours）。
     *
     * @param initiator      发起方 UUID
     * @param initiatorName  发起方名称
     * @param target         目标方 UUID
     * @param targetName     目标方名称
     * @param initiatorItems 发起方标的引用（可为空列表）
     * @param targetItems    目标方标的引用（可为空列表）
     * @return 创建结果（Created / Rejected）
     */
    public CompletableFuture<CreateResult> createOffer(UUID initiator, String initiatorName,
                                                       UUID target, String targetName,
                                                       List<OfferItem> initiatorItems,
                                                       List<OfferItem> targetItems) {
        Objects.requireNonNull(initiator, "发起方 UUID 不能为空");
        Objects.requireNonNull(initiatorName, "发起方名称不能为空");
        Objects.requireNonNull(target, "目标方 UUID 不能为空");
        Objects.requireNonNull(targetName, "目标方名称不能为空");
        List<OfferItem> mine = List.copyOf(initiatorItems);
        List<OfferItem> theirs = List.copyOf(targetItems);
        return dbExecutor.submitWrite(() -> {
            PluginConfig.TradeSettings current = settings.get();
            if (!current.enabled) {
                return new CreateResult.Rejected(TradeBlockReason.DISABLED, null);
            }
            CreateResult result = creator.create(initiator, initiatorName, target, targetName,
                    mine, theirs, current.expireHours);
            if (result instanceof CreateResult.Created) {
                // 提交后写穿发起计数（PAPI %byt_player_trade_initiated% 零 DB IO 数据源）
                profileCache.updateIfPresent(initiator,
                        profile -> profile.withTradeInitiated(profile.tradeInitiated() + 1));
            }
            return result;
        });
    }

    /**
     * 接受报价：条件迁移 PENDING→COMPLETED 唯一裁决（并发接受仅一方成功）；
     * 成交事务内全量复核，复核失败整单作废（DESTROYED，双方零变化）；
     * 成交后佩戴中标的清栏位引用，提交后双方档案缓存写穿。
     *
     * @param tradeId  交易 id
     * @param acceptor 接受者（必须是目标方）
     * @return 接受结果
     */
    public CompletableFuture<AcceptResult> accept(long tradeId, Actor acceptor) {
        Objects.requireNonNull(acceptor, "接受者不能为空");
        return dbExecutor.submitWrite(() -> {
            if (!settings.get().enabled) {
                return new AcceptResult.Disabled(tradeId);
            }
            AcceptResult result = settler.accept(tradeId, acceptor);
            if (result instanceof AcceptResult.Completed(Trade trade)) {
                // 提交后写穿双方完成计数（PAPI %byt_player_trade_completed% 零 DB IO 数据源）
                profileCache.updateIfPresent(trade.initiatorUuid(),
                        profile -> profile.withTradeCompleted(profile.tradeCompleted() + 1));
                profileCache.updateIfPresent(trade.targetUuid(),
                        profile -> profile.withTradeCompleted(profile.tradeCompleted() + 1));
            }
            return result;
        });
    }

    /**
     * 拒绝报价（限目标方）：PENDING→REJECTED。
     *
     * @param tradeId 交易 id
     * @param actor   操作者（必须是目标方）
     * @return 解决结果
     */
    public CompletableFuture<ResolveResult> reject(long tradeId, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> resolver.resolve(tradeId, actor,
                TradeResolver.Role.TARGET, TradeStatus.REJECTED, "TRADE_REJECT", null));
    }

    /**
     * 发起方撤回报价：PENDING→DESTROYED。
     *
     * @param tradeId 交易 id
     * @param actor   操作者（必须是发起方）
     * @return 解决结果
     */
    public CompletableFuture<ResolveResult> cancel(long tradeId, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> resolver.resolve(tradeId, actor,
                TradeResolver.Role.INITIATOR, TradeStatus.DESTROYED, "TRADE_DESTROY", "CANCELLED"));
    }

    /**
     * 管理员销毁报价：PENDING→DESTROYED（不限交易方）。
     *
     * @param tradeId 交易 id
     * @param actor   管理员操作者
     * @return 解决结果
     */
    public CompletableFuture<ResolveResult> adminCancel(long tradeId, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> resolver.resolve(tradeId, actor,
                TradeResolver.Role.ANY, TradeStatus.DESTROYED, "TRADE_DESTROY", "ADMIN_CANCEL"));
    }

    /**
     * 过期扫描：批量把已过期的 PENDING 迁为 EXPIRED（paper 侧 TaskScheduler 周期调用）。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 本轮成功收敛的交易笔数
     */
    public CompletableFuture<Integer> sweepExpired(long now) {
        return dbExecutor.submitWrite(() -> resolver.sweepExpired(now));
    }

    /**
     * 读路径：查询与玩家相关的全部 PENDING 交易（含标的）。
     *
     * @param player 玩家 UUID
     * @return PENDING 交易列表
     */
    public CompletableFuture<List<Trade>> pendingFor(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> tradeRepository.findPendingFor(c, player)));
    }

    /**
     * 读路径：按 id 查交易单（含标的；报价详情菜单用）。
     *
     * @param tradeId 交易 id
     * @return 交易单；不存在时为空
     */
    public CompletableFuture<Optional<Trade>> findWithItems(long tradeId) {
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> tradeRepository.findWithItems(c, tradeId)));
    }

    /**
     * 读路径：玩家个人交易历史分页（任一侧，按创建时刻倒序）。
     *
     * @param player 玩家 UUID
     * @param page   页码（从 1 起）
     * @param size   每页条数
     * @return 分页结果
     */
    public CompletableFuture<Page<Trade>> personalHistory(UUID player, int page, int size) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> tradeRepository.personalHistory(c, player, page, size)));
    }

    /**
     * 读路径：全服交易历史分页（管理端；可按状态与玩家名模糊过滤）。
     *
     * @param filter   状态过滤（可空 = 全部）
     * @param nameLike 玩家名模糊串（可空 = 不过滤）
     * @param page     页码（从 1 起）
     * @param size     每页条数
     * @return 分页结果
     */
    public CompletableFuture<Page<Trade>> globalHistory(@Nullable TradeStatus filter,
                                                        @Nullable String nameLike,
                                                        int page, int size) {
        return dbExecutor.submitRead(() -> txRunner.withConnection(
                c -> tradeRepository.globalHistory(c, filter, nameLike, page, size)));
    }

    /**
     * 读路径：统计玩家交易数（PAPI %byt_player_trade_*% 计数用）。
     *
     * @param player        玩家 UUID
     * @param initiatedOnly 仅统计其作为发起方的交易
     * @param completedOnly 仅统计已成交（COMPLETED）的交易
     * @return 交易数
     */
    public CompletableFuture<Integer> countByPlayer(UUID player, boolean initiatedOnly,
                                                    boolean completedOnly) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return dbExecutor.submitRead(() -> txRunner.withConnection(
                c -> tradeRepository.countByPlayer(c, player, initiatedOnly, completedOnly)));
    }
}
