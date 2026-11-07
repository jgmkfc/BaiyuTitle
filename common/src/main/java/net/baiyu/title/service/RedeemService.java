package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.RedeemRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.redeem.RedeemResult;
import net.baiyu.title.service.redeem.RedemptionEngine;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 兑换服务：兑换码与称号卡的唯一入口。
 * <p>玩家向兑换主体在 {@link RedemptionEngine}（条件 UPDATE 原子递减 / 单次核销，
 * 不变量 §十 14）；本类承载码面归一化、管理端建码/停用/发卡与线程调度。
 * 管理端参数非法以 {@link IllegalArgumentException} 完成 future（与目录服务同口径）。
 */
public final class RedeemService {

    private final RedeemRepository redeemRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final DbExecutor dbExecutor;
    private final TitleCatalogCache catalogCache;
    private final RedemptionEngine engine;

    /**
     * 构造兑换服务。
     *
     * @param redeemRepository    兑换仓储
     * @param ownershipRepository 所有权仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op；羁绊检测入口）
     */
    public RedeemService(RedeemRepository redeemRepository, OwnershipRepository ownershipRepository,
                         AuditRepository auditRepository, TransactionRunner txRunner,
                         DbExecutor dbExecutor, TitleCatalogCache catalogCache,
                         PlayerProfileCache profileCache,
                         @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.redeemRepository = Objects.requireNonNull(redeemRepository, "兑换仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.engine = new RedemptionEngine(redeemRepository, ownershipRepository, auditRepository,
                txRunner, catalogCache,
                Objects.requireNonNull(profileCache, "玩家档案缓存不能为空"), postGrantHook);
    }

    /**
     * 兑换码兑换：大写归一化 → 事务内校验（启用/有效期/每玩家限次/未持有）→
     * tryConsumeUse 原子递减 → recordUse + 授予（source=REDEEM_CODE，按称号 holdDays）。
     *
     * @param player     玩家 UUID
     * @param playerName 玩家名称
     * @param rawCode    原始码面（容忍大小写与前后空白）
     * @return 兑换结果
     */
    public CompletableFuture<RedeemResult> redeemCode(UUID player, String playerName, String rawCode) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
        String code = normalize(rawCode);
        return dbExecutor.submitWrite(() -> engine.redeemCode(player, playerName, code));
    }

    /**
     * 称号卡核销：事务内校验（存在/未核销/未持有）→ tryRedeemCard 条件 UPDATE
     * 单次核销 → 授予（holdDaysOverride 优先，0 = 永久）→ bindCardOwnership 回填。
     * <p>成功后 paper 侧主线程消耗物品。
     *
     * @param player     玩家 UUID
     * @param playerName 玩家名称
     * @param cardUuid   卡片 UUID（物品 PDC 同值）
     * @return 兑换结果
     */
    public CompletableFuture<RedeemResult> redeemCard(UUID player, String playerName, UUID cardUuid) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
        Objects.requireNonNull(cardUuid, "卡片 UUID 不能为空");
        return dbExecutor.submitWrite(() -> engine.redeemCard(player, playerName, cardUuid));
    }

    /**
     * 创建兑换码（管理端）。
     *
     * @param rawCode       原始码面（大写归一化后落库）
     * @param titleId       目标称号 id
     * @param maxUses       总用量上限（0 = 无限）
     * @param perPlayerUses 每玩家限次（0 = 无限）
     * @param validFrom     生效时刻（可空 = 立即；UTC epoch 毫秒）
     * @param validUntil    失效时刻（可空 = 永久；UTC epoch 毫秒）
     * @param actor         操作者
     * @return 含生成 id 的兑换码
     */
    public CompletableFuture<RedeemCode> createCode(String rawCode, int titleId, int maxUses,
                                                    int perPlayerUses, @Nullable Long validFrom,
                                                    @Nullable Long validUntil, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        String code = normalize(rawCode);
        return dbExecutor.submitWrite(() -> {
            requireTitle(titleId);
            long now = System.currentTimeMillis();
            RedeemCode fresh = new RedeemCode(0, code, titleId, maxUses, 0, perPlayerUses,
                    validFrom, validUntil, true, actor.name(), now);
            long id = txRunner.inTransaction(c -> {
                if (redeemRepository.findCodeByCode(c, code).isPresent()) {
                    throw new IllegalArgumentException("兑换码已存在: " + code);
                }
                long codeId = redeemRepository.insertCode(c, fresh);
                auditRepository.insert(c, new AuditRepository.AuditEntry(
                        "REDEEM_CODE_CREATE", actor.uuid(), actor.name(), null, titleId, null,
                        "redeem_code", codeId, "{\"code\":\"" + code + "\"}", now));
                return codeId;
            });
            return new RedeemCode(id, code, titleId, maxUses, 0, perPlayerUses,
                    validFrom, validUntil, true, actor.name(), now);
        });
    }

    /**
     * 停用兑换码。
     *
     * @param rawCode 原始码面
     * @param actor   操作者
     * @return 停用成功 true；兑换码不存在 false
     */
    public CompletableFuture<Boolean> disableCode(String rawCode, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        String code = normalize(rawCode);
        return dbExecutor.submitWrite(() -> txRunner.inTransaction(c -> {
            RedeemCode found = redeemRepository.findCodeByCode(c, code).orElse(null);
            if (found == null || !redeemRepository.setCodeEnabled(c, found.id(), false)) {
                return false;
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "REDEEM_CODE_DISABLE", actor.uuid(), actor.name(), null, found.titleId(), null,
                    "redeem_code", found.id(), "{\"code\":\"" + code + "\"}",
                    System.currentTimeMillis()));
            return true;
        }));
    }

    /**
     * 发称号卡：insertCard 落库并返回 cardUuid（paper 侧写入物品 PDC）。
     *
     * @param titleId          目标称号 id
     * @param holdDaysOverride 持有天数覆写（可空 = 按称号默认；0 = 永久）
     * @param actor            操作者
     * @return 新称号卡
     */
    public CompletableFuture<RedeemCard> issueCard(int titleId, @Nullable Integer holdDaysOverride,
                                                   Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> {
            requireTitle(titleId);
            long now = System.currentTimeMillis();
            RedeemCard card = new RedeemCard(UUID.randomUUID(), titleId, holdDaysOverride,
                    actor.name(), now, null, null, null);
            txRunner.inTransaction(c -> {
                redeemRepository.insertCard(c, card);
                auditRepository.insert(c, new AuditRepository.AuditEntry(
                        "REDEEM_CARD_ISSUE", actor.uuid(), actor.name(), null, titleId, null,
                        "redeem_card", null, "{\"cardUuid\":\"" + card.cardUuid() + "\"}", now));
                return null;
            });
            return card;
        });
    }

    /**
     * 管理端前置校验：目标称号必须存在且未软删。
     */
    private void requireTitle(int titleId) {
        if (catalogCache.byId(titleId).filter(t -> !t.isDeleted()).isEmpty()) {
            throw new IllegalArgumentException("称号不存在或已删除: id=" + titleId);
        }
    }

    /**
     * 码面大写归一化（容忍前后空白）。
     */
    private static String normalize(String rawCode) {
        Objects.requireNonNull(rawCode, "兑换码码面不能为空");
        return rawCode.trim().toUpperCase(Locale.ROOT);
    }
}
