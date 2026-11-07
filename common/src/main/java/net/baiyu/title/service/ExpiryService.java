package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 到期收敛服务：把"已到期但仍 ACTIVE"的所有权批量收敛为 EXPIRED。
 * <p>时序：写线程 pickExpired(now, 200) → 逐条独立事务
 * endActive(EXPIRED) + 清一切栏位引用 + audit → 提交后更新档案缓存。
 * 逐条事务保证单条失败不拖垮整批；渲染层按时间戳即时隐藏，不依赖本服务的收敛时点。
 * <p>本类不自带定时器——周期调度（默认 60s）与登录预载前的收敛均由 paper 侧
 * TaskScheduler 调用 {@link #sweep()} 完成。
 */
public final class ExpiryService {

    /** 每轮收敛的批大小。 */
    private static final int SWEEP_BATCH_SIZE = 200;

    /** 到期收敛的所有权结束原因。 */
    private static final String EXPIRE_REASON = "EXPIRED";

    /** 所有权仓储。 */
    private final OwnershipRepository ownershipRepository;

    /** 装扮栏位仓储（到期时清引用）。 */
    private final SlotRepository slotRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 玩家档案缓存（提交后增量更新）。 */
    private final PlayerProfileCache profileCache;

    /**
     * 构造到期收敛服务。
     *
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param profileCache        玩家档案缓存
     */
    public ExpiryService(OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                         AuditRepository auditRepository, TransactionRunner txRunner,
                         DbExecutor dbExecutor, PlayerProfileCache profileCache) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
    }

    /**
     * 执行一轮到期收敛（最多处理 {@value SWEEP_BATCH_SIZE} 条；
     * 剩余留待下一轮，避免单轮长事务霸占写线程）。
     *
     * @return 本轮成功收敛的所有权条数
     */
    public CompletableFuture<Integer> sweep() {
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            List<Ownership> expired = txRunner.withConnection(c ->
                    ownershipRepository.pickExpired(c, now, SWEEP_BATCH_SIZE));
            int converged = 0;
            for (Ownership ownership : expired) {
                if (expireOne(ownership, now)) {
                    converged++;
                }
            }
            return converged;
        });
    }

    /**
     * 单玩家同步收敛：把该玩家全部
     * "已到期但仍 ACTIVE"的所有权逐条收敛为 EXPIRED。
     * <p><b>线程约束</b>：调用方必须已在 DbExecutor 写线程上（登录预载写任务
     * 内联调用，与随后的档案装载 + 挂载在同一写任务串行；内部事务的
     * 写线程断言是运行时防线）。请勿在主线程或读线程调用。
     *
     * @param player 玩家 UUID
     * @return 本次收敛的所有权条数
     */
    public int convergeOwner(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        long now = System.currentTimeMillis();
        List<Ownership> actives = txRunner.withConnection(c ->
                ownershipRepository.findActiveByOwner(c, player));
        int converged = 0;
        for (Ownership ownership : actives) {
            if (ownership.isExpired(now) && expireOne(ownership, now)) {
                converged++;
            }
        }
        return converged;
    }

    /**
     * 收敛单条到期所有权：独立事务 endActive(EXPIRED) + 清栏位引用 + audit，
     * 提交后更新档案缓存。
     *
     * @return 本条收敛成功返回 true（并发下已非 ACTIVE 返回 false）
     */
    private boolean expireOne(Ownership ownership, long now) {
        boolean ended = txRunner.inTransaction(c -> {
            if (!ownershipRepository.endActive(c, ownership.id(),
                    OwnershipStatus.EXPIRED, EXPIRE_REASON, now)) {
                return false;
            }
            slotRepository.clearTitleEverywhere(c, ownership.ownerUuid(), ownership.titleId());
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "EXPIRE", null, Actor.system().name(), ownership.ownerUuid(),
                    ownership.titleId(), ownership.id(), null, null, null, now));
            return true;
        });
        if (ended) {
            profileCache.updateIfPresent(ownership.ownerUuid(),
                    profile -> profile.withoutTitle(ownership.titleId()));
        }
        return ended;
    }
}
