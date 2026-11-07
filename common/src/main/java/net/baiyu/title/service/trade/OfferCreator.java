package net.baiyu.title.service.trade;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 报价创建器（TradeService 的写线程协作件）：结构校验 → 写事务内权威校验
 * → 插 PENDING 交易 + 标的 + 审计。
 * <p>任一校验失败即整体回滚、零持久化副作用（{@link TradeAbort} 透传触发回滚，
 * 此处捕获转 {@link CreateResult.Rejected} 友好结果）。
 * 交易总开关由服务层把守，本类只管创建本身。
 */
public final class OfferCreator {

    /** 交易仓储。 */
    private final TradeRepository tradeRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 报价标的校验器。 */
    private final OfferValidator validator;

    /**
     * 构造报价创建器。
     *
     * @param tradeRepository 交易仓储
     * @param auditRepository 审计仓储
     * @param txRunner        事务执行器
     * @param validator       报价标的校验器
     */
    public OfferCreator(TradeRepository tradeRepository, AuditRepository auditRepository,
                        TransactionRunner txRunner, OfferValidator validator) {
        this.tradeRepository = Objects.requireNonNull(tradeRepository, "交易仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.validator = Objects.requireNonNull(validator, "报价标的校验器不能为空");
    }

    /**
     * 创建报价（写线程调用）：先做无 IO 的结构校验，再进写事务权威校验并落库。
     *
     * @param initiator      发起方 UUID
     * @param initiatorName  发起方名称
     * @param target         目标方 UUID
     * @param targetName     目标方名称
     * @param initiatorItems 发起方标的引用（可为空列表）
     * @param targetItems    目标方标的引用（可为空列表）
     * @param expireHours    报价存活小时数（config trade.expire-hours）
     * @return 创建结果（Created / Rejected）
     */
    public CreateResult create(UUID initiator, String initiatorName, UUID target, String targetName,
                               List<OfferItem> initiatorItems, List<OfferItem> targetItems,
                               int expireHours) {
        CreateResult.Rejected structural =
                structuralCheck(initiator, target, initiatorItems, targetItems);
        if (structural != null) {
            return structural;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + expireHours * 3_600_000L;
        try {
            Trade created = txRunner.inTransaction(c -> {
                OfferValidator.ValidatedOffer offer = validator.validate(
                        c, initiator, target, initiatorItems, targetItems, now);
                List<TradeItem> items = new ArrayList<>();
                buildItems(items, TradeSide.INITIATOR, initiatorItems, offer);
                buildItems(items, TradeSide.TARGET, targetItems, offer);
                Trade pending = new Trade(0, initiator, initiatorName, target, targetName,
                        TradeStatus.PENDING, now, expiresAt, null, null, null, List.of());
                long tradeId = tradeRepository.insert(c, pending, items);
                auditRepository.insert(c, new AuditRepository.AuditEntry(
                        "TRADE_CREATE", initiator, initiatorName, target, null, null,
                        "trade", tradeId, "{\"items\":" + items.size() + "}", now));
                return tradeRepository.findWithItems(c, tradeId).orElseThrow(
                        () -> new IllegalStateException("刚插入的交易单不可见: " + tradeId));
            });
            return new CreateResult.Created(created);
        } catch (TradeAbort abort) {
            return new CreateResult.Rejected(abort.reason(), abort.titleId());
        }
    }

    /**
     * 结构校验（无 IO）：自交易/每侧上限/双侧全空/重复标的。
     *
     * @return 拒绝结果；全部通过时为 null
     */
    private static CreateResult.Rejected structuralCheck(UUID initiator, UUID target,
                                                         List<OfferItem> initiatorItems,
                                                         List<OfferItem> targetItems) {
        if (initiator.equals(target)) {
            return new CreateResult.Rejected(TradeBlockReason.SELF_TRADE, null);
        }
        if (initiatorItems.size() > Trade.MAX_ITEMS_PER_SIDE
                || targetItems.size() > Trade.MAX_ITEMS_PER_SIDE) {
            return new CreateResult.Rejected(TradeBlockReason.TOO_MANY_ITEMS, null);
        }
        if (initiatorItems.isEmpty() && targetItems.isEmpty()) {
            return new CreateResult.Rejected(TradeBlockReason.EMPTY_OFFER, null);
        }
        Set<Long> seen = new HashSet<>();
        for (OfferItem ref : initiatorItems) {
            if (!seen.add(ref.ownershipId())) {
                return new CreateResult.Rejected(TradeBlockReason.DUPLICATE_ITEM, ref.titleId());
            }
        }
        for (OfferItem ref : targetItems) {
            if (!seen.add(ref.ownershipId())) {
                return new CreateResult.Rejected(TradeBlockReason.DUPLICATE_ITEM, ref.titleId());
            }
        }
        return null;
    }

    /**
     * 以校验通过的报价快照构造标的行（名称快照取创建时刻的归一化 MiniMessage 名）。
     */
    private static void buildItems(List<TradeItem> out, TradeSide side,
                                   List<OfferItem> refs, OfferValidator.ValidatedOffer offer) {
        for (OfferItem ref : refs) {
            out.add(new TradeItem(0, 0, side, ref.ownershipId(), ref.titleId(),
                    offer.titlesById().get(ref.ownershipId()).name()));
        }
    }
}
