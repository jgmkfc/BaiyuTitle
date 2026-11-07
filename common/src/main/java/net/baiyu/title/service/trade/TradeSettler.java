package net.baiyu.title.service.trade;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.exception.DuplicateActiveException;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.OwnershipService;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 交易成交器（TradeService 的写线程协作件七"交易 accept"时序）。
 * <p>单事务：查 PENDING+未过期 → 全量复核（失败→同事务作废 DESTROYED，双方零变化）
 * → PENDING→COMPLETED 条件迁移唯一裁决（0 行=并发已解决放弃）→ 先整单
 * endActive(TRADED) 再整单对侧 insertGrant(复制 expires_at) → 清旧主佩戴引用
 * → 审计 → commit；交割途中标的被跨端并发转走则整体回滚，另起事务作废。
 * 提交后双方档案缓存写穿 + 授予钩子（不变量 §十 12）。
 */
public final class TradeSettler {

    private final TradeRepository tradeRepository;
    private final OwnershipRepository ownershipRepository;
    private final SlotRepository slotRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final OfferValidator validator;
    private final PlayerProfileCache profileCache;
    private final OwnershipService.PostGrantHook postGrantHook;

    /**
     * 构造成交器。
     *
     * @param tradeRepository     交易仓储
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param validator           报价标的校验器（复核用）
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op；羁绊检测接线位）
     */
    public TradeSettler(TradeRepository tradeRepository, OwnershipRepository ownershipRepository,
                        SlotRepository slotRepository, AuditRepository auditRepository,
                        TransactionRunner txRunner, OfferValidator validator,
                        PlayerProfileCache profileCache,
                        @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.tradeRepository = Objects.requireNonNull(tradeRepository, "交易仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.validator = Objects.requireNonNull(validator, "报价标的校验器不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.postGrantHook = postGrantHook == null ? OwnershipService.PostGrantHook.NO_OP : postGrantHook;
    }

    /**
     * 接受报价（写线程调用）：事务成交，提交后缓存写穿。
     *
     * @param tradeId  交易 id
     * @param acceptor 接受者（必须是目标方）
     * @return 接受结果
     */
    public AcceptResult accept(long tradeId, Actor acceptor) {
        long now = System.currentTimeMillis();
        AcceptTx outcome;
        try {
            outcome = txRunner.inTransaction(c -> acceptInTx(c, tradeId, acceptor, now));
        } catch (TradeAbort raced) {
            // 复核之后交割之前标的被跨端并发转走：整单已回滚，另起事务作废
            return destroyAfterRollback(tradeId, acceptor, raced.failReason(), now);
        } catch (DuplicateActiveException dup) {
            // 接收方在复核后又获得同款 ACTIVE（跨端并发）：同样回滚后作废
            return destroyAfterRollback(tradeId, acceptor,
                    TradeBlockReason.RECIPIENT_ALREADY_OWNS.name(), now);
        }
        if (outcome.transfers() != null) {
            writeThrough(outcome.transfers());
        }
        return outcome.result();
    }

    /**
     * 接受事务主体：状态判定 → 复核 → 条件迁移 → 交割 → 审计。
     */
    private AcceptTx acceptInTx(Connection c, long tradeId, Actor acceptor, long now)
            throws SQLException {
        Trade trade = tradeRepository.findWithItems(c, tradeId).orElse(null);
        if (trade == null) {
            return AcceptTx.plain(new AcceptResult.UnknownTrade(tradeId));
        }
        if (!trade.isPending()) {
            return AcceptTx.plain(new AcceptResult.AlreadyResolved(tradeId, trade.status()));
        }
        if (!trade.targetUuid().equals(acceptor.uuid())) {
            return AcceptTx.plain(new AcceptResult.NotTarget(tradeId));
        }
        if (trade.isExpiredAt(now)) {
            // 顺手收敛：过期报价迁 EXPIRED（展示归入"已拒绝"）
            if (tradeRepository.transition(c, tradeId, TradeStatus.PENDING, TradeStatus.EXPIRED,
                    Actor.system().name(), "EXPIRED", now)) {
                audit(c, "TRADE_EXPIRE", Actor.system(), trade, "EXPIRED", now);
            }
            return AcceptTx.plain(new AcceptResult.Expired(tradeId));
        }
        OfferValidator.ValidatedOffer offer;
        try {
            offer = validator.validate(c, trade.initiatorUuid(), trade.targetUuid(),
                    refsOf(trade, TradeSide.INITIATOR), refsOf(trade, TradeSide.TARGET), now);
        } catch (TradeAbort recheckFailure) {
            // 复核失败整单作废（此前只有读，无需回滚；双方所有权零变化）
            if (!tradeRepository.transition(c, tradeId, TradeStatus.PENDING, TradeStatus.DESTROYED,
                    acceptor.name(), recheckFailure.failReason(), now)) {
                return AcceptTx.plain(new AcceptResult.AlreadyResolved(tradeId, statusOf(c, tradeId)));
            }
            audit(c, "TRADE_DESTROY", acceptor, trade, recheckFailure.failReason(), now);
            return AcceptTx.plain(new AcceptResult.Invalidated(tradeId, recheckFailure.failReason()));
        }
        // 唯一裁决：并发接受仅一方迁移成功（不变量 §十 8）
        if (!tradeRepository.transition(c, tradeId, TradeStatus.PENDING, TradeStatus.COMPLETED,
                acceptor.name(), null, now)) {
            return AcceptTx.plain(new AcceptResult.AlreadyResolved(tradeId, statusOf(c, tradeId)));
        }
        List<Transfer> transfers = exchange(c, trade, offer, now);
        audit(c, "TRADE_COMPLETE", acceptor, trade, null, now);
        Trade completed = tradeRepository.findWithItems(c, tradeId).orElseThrow(
                () -> new IllegalStateException("成交后交易单不可见: " + tradeId));
        return new AcceptTx(new AcceptResult.Completed(completed), transfers);
    }

    /**
     * 交割：先整单转出（同款对倒时先全部离手，避免撞 (owner, title) 唯一约束），
     * 再整单对侧授予（复制原到期时刻）并清旧主佩戴引用。
     */
    private List<Transfer> exchange(Connection c, Trade trade,
                                    OfferValidator.ValidatedOffer offer, long now) throws SQLException {
        for (TradeItem item : trade.items()) {
            if (!ownershipRepository.endActive(c, item.ownershipId(), OwnershipStatus.TRADED,
                    "TRADE:" + trade.id(), now)) {
                throw new TradeAbort(TradeBlockReason.ITEM_NOT_OWNED, item.titleId());
            }
        }
        List<Transfer> transfers = new ArrayList<>(trade.items().size());
        for (TradeItem item : trade.items()) {
            boolean fromInitiator = item.side() == TradeSide.INITIATOR;
            UUID oldOwner = fromInitiator ? trade.initiatorUuid() : trade.targetUuid();
            UUID newOwner = fromInitiator ? trade.targetUuid() : trade.initiatorUuid();
            String newOwnerName = fromInitiator ? trade.targetName() : trade.initiatorName();
            Ownership old = offer.ownershipsById().get(item.ownershipId());
            long grantId = ownershipRepository.insertGrant(c, new Ownership(0, item.titleId(),
                    newOwner, newOwnerName, GrantSource.TRADE, trade.id(), now, old.expiresAt(),
                    OwnershipStatus.ACTIVE, null, null));
            slotRepository.clearTitleEverywhere(c, oldOwner, item.titleId());
            transfers.add(new Transfer(oldOwner, new Ownership(grantId, item.titleId(), newOwner,
                    newOwnerName, GrantSource.TRADE, trade.id(), now, old.expiresAt(),
                    OwnershipStatus.ACTIVE, null, null)));
        }
        return transfers;
    }

    /**
     * 交割途中回滚后的作废：新事务条件迁移 PENDING→DESTROYED + 审计。
     */
    private AcceptResult destroyAfterRollback(long tradeId, Actor acceptor, String reason, long now) {
        return txRunner.inTransaction(c -> {
            if (!tradeRepository.transition(c, tradeId, TradeStatus.PENDING, TradeStatus.DESTROYED,
                    acceptor.name(), reason, now)) {
                return new AcceptResult.AlreadyResolved(tradeId, statusOf(c, tradeId));
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry("TRADE_DESTROY",
                    acceptor.uuid(), acceptor.name(), null, null, null, "trade", tradeId, reason, now));
            return new AcceptResult.Invalidated(tradeId, reason);
        });
    }

    /**
     * 提交后写穿：先整单移除旧主（含栏位引用），再整单合入新主并回调授予钩子
     * （同款对倒时先移除后合入才不会误删刚到手的所有权）。
     */
    private void writeThrough(List<Transfer> transfers) {
        for (Transfer transfer : transfers) {
            profileCache.updateIfPresent(transfer.oldOwner(),
                    profile -> profile.withoutTitle(transfer.granted().titleId()));
        }
        for (Transfer transfer : transfers) {
            profileCache.updateIfPresent(transfer.granted().ownerUuid(),
                    profile -> profile.withOwnership(transfer.granted()));
            postGrantHook.onGranted(transfer.granted().ownerUuid(), transfer.granted().titleId(),
                    GrantSource.TRADE);
        }
    }

    /**
     * 把交易标的还原为报价引用（复核复用创建口径）。
     */
    private static List<OfferItem> refsOf(Trade trade, TradeSide side) {
        return trade.itemsOf(side).stream()
                .map(item -> new OfferItem(item.ownershipId(), item.titleId())).toList();
    }

    /**
     * 读取交易当前状态（条件迁移 0 行命中后的败方回执用）。
     */
    private TradeStatus statusOf(Connection c, long tradeId) throws SQLException {
        return tradeRepository.findWithItems(c, tradeId).map(Trade::status)
                .orElseThrow(() -> new IllegalStateException("交易单不可见: " + tradeId));
    }

    /**
     * 写一条交易审计（target 记对手方，refTable=trade）。
     */
    private void audit(Connection c, String eventType, Actor actor, Trade trade,
                       @Nullable String detail, long now) throws SQLException {
        auditRepository.insert(c, new AuditRepository.AuditEntry(eventType, actor.uuid(),
                actor.name(), trade.initiatorUuid(), null, null, "trade", trade.id(), detail, now));
    }

    /**
     * 接受事务的回执（成交时携带提交后写穿所需的转移清单）。
     *
     * @param result    接受结果
     * @param transfers 转移清单（未成交时为空）
     */
    private record AcceptTx(AcceptResult result, @Nullable List<Transfer> transfers) {

        /** 无转移清单的普通回执。 */
        static AcceptTx plain(AcceptResult result) {
            return new AcceptTx(result, null);
        }
    }

    /**
     * 单标的转移回执（缓存写穿与授予钩子取材）。
     *
     * @param oldOwner 旧主 UUID
     * @param granted  新主的所有权实例（含生成主键）
     */
    private record Transfer(UUID oldOwner, Ownership granted) {
    }
}
