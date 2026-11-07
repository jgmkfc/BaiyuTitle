package net.baiyu.title.service.redeem;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.RedeemRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.OwnershipService;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * 兑换执行引擎（RedeemService 的内部协作者，方法只允许在写线程调用）。
 * <p>并发纪律（不变量 §十 14）：兑换码 {@code tryConsumeUse} 条件 UPDATE 原子
 * 递减防超用；称号卡 {@code tryRedeemCard} 条件 UPDATE 单次核销防刷；校验 →
 * 消耗 → 记录 → 授予同一事务，任一拒绝分支在任何写入之前返回（零残留）。
 * 授予后提交前回填关联（recordUse / bindCardOwnership），提交后写穿档案缓存
 * 并回调授予钩子（羁绊检测入口）。
 */
public final class RedemptionEngine {

    /** 一天的毫秒数（持有有效期换算）。 */
    private static final long DAY_MILLIS = 86_400_000L;

    private final RedeemRepository redeemRepository;
    private final OwnershipRepository ownershipRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final TitleCatalogCache catalogCache;
    private final PlayerProfileCache profileCache;
    private final OwnershipService.PostGrantHook postGrantHook;

    /**
     * 构造兑换执行引擎（依赖与 RedeemService 同源）。
     *
     * @param redeemRepository    兑换仓储
     * @param ownershipRepository 所有权仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op）
     */
    public RedemptionEngine(RedeemRepository redeemRepository,
                            OwnershipRepository ownershipRepository,
                            AuditRepository auditRepository, TransactionRunner txRunner,
                            TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                            @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.redeemRepository = Objects.requireNonNull(redeemRepository, "兑换仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.postGrantHook = postGrantHook == null ? OwnershipService.PostGrantHook.NO_OP : postGrantHook;
    }

    /**
     * 写线程主体：兑换码兑换（码面已大写归一化）。
     */
    public RedeemResult redeemCode(UUID player, String playerName, String code) {
        long now = System.currentTimeMillis();
        Outcome outcome = txRunner.inTransaction(c -> {
            RedeemCode found = redeemRepository.findCodeByCode(c, code).orElse(null);
            if (found == null) {
                return Outcome.rejected(RedeemResult.RejectReason.CODE_NOT_FOUND);
            }
            if (!found.enabled()) {
                return Outcome.rejected(RedeemResult.RejectReason.CODE_DISABLED);
            }
            if (!found.isWithinValidity(now)) {
                return Outcome.rejected(RedeemResult.RejectReason.CODE_OUTSIDE_VALIDITY);
            }
            Title title = existingTitle(found.titleId());
            if (title == null) {
                return Outcome.rejected(RedeemResult.RejectReason.TITLE_MISSING);
            }
            if (ownershipRepository.findActive(c, player, title.id()).isPresent()) {
                return Outcome.rejected(RedeemResult.RejectReason.ALREADY_OWNED);
            }
            if (found.perPlayerUses() > 0
                    && redeemRepository.countUsesByPlayer(c, found.id(), player)
                            >= found.perPlayerUses()) {
                return Outcome.rejected(RedeemResult.RejectReason.CODE_PLAYER_LIMIT);
            }
            if (!redeemRepository.tryConsumeUse(c, found.id())) {
                return Outcome.rejected(RedeemResult.RejectReason.CODE_EXHAUSTED);
            }
            Long expiresAt = holdExpiry(title.holdDurationDays(), now);
            long ownershipId = insertGrantInTx(c, title.id(), player, playerName,
                    GrantSource.REDEEM_CODE, found.id(), expiresAt, now);
            redeemRepository.recordUse(c, found.id(), player, ownershipId, now);
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "REDEEM_CODE", player, playerName, player, title.id(), ownershipId,
                    "redeem_code", found.id(), "{\"code\":\"" + code + "\"}", now));
            return Outcome.granted(ownershipId, title.id(), expiresAt);
        });
        return settle(outcome, player, playerName, GrantSource.REDEEM_CODE, now);
    }

    /**
     * 写线程主体：称号卡核销（holdDaysOverride 优先，0 = 永久）。
     */
    public RedeemResult redeemCard(UUID player, String playerName, UUID cardUuid) {
        long now = System.currentTimeMillis();
        Outcome outcome = txRunner.inTransaction(c -> {
            RedeemCard card = redeemRepository.findCard(c, cardUuid).orElse(null);
            if (card == null) {
                return Outcome.rejected(RedeemResult.RejectReason.CARD_NOT_FOUND);
            }
            if (card.isRedeemed()) {
                return Outcome.rejected(RedeemResult.RejectReason.CARD_ALREADY_REDEEMED);
            }
            Title title = existingTitle(card.titleId());
            if (title == null) {
                return Outcome.rejected(RedeemResult.RejectReason.TITLE_MISSING);
            }
            if (ownershipRepository.findActive(c, player, title.id()).isPresent()) {
                return Outcome.rejected(RedeemResult.RejectReason.ALREADY_OWNED);
            }
            if (!redeemRepository.tryRedeemCard(c, cardUuid, player, now)) {
                return Outcome.rejected(RedeemResult.RejectReason.CARD_ALREADY_REDEEMED);
            }
            int holdDays = card.hasHoldOverride()
                    ? card.holdDaysOverride() : title.holdDurationDays();
            Long expiresAt = holdExpiry(holdDays, now);
            long ownershipId = insertGrantInTx(c, title.id(), player, playerName,
                    GrantSource.REDEEM_CARD, null, expiresAt, now);
            redeemRepository.bindCardOwnership(c, cardUuid, ownershipId);
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "REDEEM_CARD", player, playerName, player, title.id(), ownershipId,
                    "redeem_card", null, "{\"cardUuid\":\"" + cardUuid + "\"}", now));
            return Outcome.granted(ownershipId, title.id(), expiresAt);
        });
        return settle(outcome, player, playerName, GrantSource.REDEEM_CARD, now);
    }

    /**
     * 提交后收敛：授予成功则写穿档案缓存并回调钩子。
     */
    private RedeemResult settle(Outcome outcome, UUID player, String playerName,
                                GrantSource source, long now) {
        if (outcome.reason() != null) {
            return new RedeemResult.Rejected(outcome.reason());
        }
        Ownership granted = new Ownership(outcome.ownershipId(), outcome.titleId(), player,
                playerName, source, null, now, outcome.expiresAt(), OwnershipStatus.ACTIVE,
                null, null);
        profileCache.updateIfPresent(player, profile -> profile.withOwnership(granted));
        postGrantHook.onGranted(player, outcome.titleId(), source);
        return new RedeemResult.Redeemed(outcome.ownershipId(), outcome.titleId());
    }

    /**
     * 事务内插入兑换授予行（已持有已在事务内前置排除，单写线程下不会撞唯一约束）。
     */
    private long insertGrantInTx(Connection c, int titleId, UUID player, String playerName,
                                 GrantSource source, @Nullable Long sourceRef,
                                 @Nullable Long expiresAt, long now) throws SQLException {
        return ownershipRepository.insertGrant(c, new Ownership(0, titleId, player, playerName,
                source, sourceRef, now, expiresAt, OwnershipStatus.ACTIVE, null, null));
    }

    /**
     * 从缓存取未软删称号（不存在返回空）。
     */
    private @Nullable Title existingTitle(int titleId) {
        return catalogCache.byId(titleId).filter(t -> !t.isDeleted()).orElse(null);
    }

    /**
     * 持有有效期换算（0 = 永久返回空）。
     */
    private static @Nullable Long holdExpiry(int holdDays, long now) {
        return holdDays > 0 ? now + holdDays * DAY_MILLIS : null;
    }

    /**
     * 兑换事务的提交后回执（reason 非空 = 拒绝，其余字段无效）。
     */
    private record Outcome(@Nullable RedeemResult.RejectReason reason, long ownershipId,
                           int titleId, @Nullable Long expiresAt) {

        /** 拒绝回执。 */
        static Outcome rejected(RedeemResult.RejectReason reason) {
            return new Outcome(reason, 0, 0, null);
        }

        /** 授予回执。 */
        static Outcome granted(long ownershipId, int titleId, @Nullable Long expiresAt) {
            return new Outcome(null, ownershipId, titleId, expiresAt);
        }
    }
}
