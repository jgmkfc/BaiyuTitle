package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.exception.DuplicateActiveException;
import net.baiyu.title.service.executor.DbExecutor;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 羁绊服务：集齐自动授予与回溯补发的唯一入口。
 * <p>触发：本类实现 {@link OwnershipService.PostGrantHook}——任一称号授予提交后
 * 入队写线程检测任务；集齐（全部需求称号 ACTIVE 且未到期）且未持有 → 自动授予
 * （source=BOND，恒永久）。级联允许（BOND 授予再触发检测），终止保证：
 * 集齐检测的已持有集合只增不减 + 已持有短路，每个羁绊至多授予一次。
 * <p>失去条件称号不回收羁绊（红线；本类只补不收）。回溯补发
 * {@link #backfill}：新建/修改羁绊需求后按 DB 所有权全表扫描（离线玩家也补），
 * 写线程分批（每批 {@value BACKFILL_BATCH_SIZE} 人一个事务）。
 */
public final class BondService implements OwnershipService.PostGrantHook {

    /** 回溯补发批大小（分批 50）。 */
    private static final int BACKFILL_BATCH_SIZE = 50;

    /** 所有权仓储。 */
    private final OwnershipRepository ownershipRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 称号目录缓存（羁绊反向索引 + 需求集）。 */
    private final TitleCatalogCache catalogCache;

    /** 玩家档案缓存（提交后增量更新）。 */
    private final PlayerProfileCache profileCache;

    /** 下游钩子（paper 侧 Post 事件桥接；本类的级联在内部收敛，不经下游回流）。 */
    private final OwnershipService.PostGrantHook downstream;

    /**
     * 构造羁绊服务。
     *
     * @param ownershipRepository 所有权仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param downstream          羁绊授予后的下游钩子（可空 = no-op）
     */
    public BondService(OwnershipRepository ownershipRepository, AuditRepository auditRepository,
                       TransactionRunner txRunner, DbExecutor dbExecutor,
                       TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                       @Nullable OwnershipService.PostGrantHook downstream) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.downstream = downstream == null ? OwnershipService.PostGrantHook.NO_OP : downstream;
    }

    /**
     * 授予提交后钩子：入队一个独立写任务做集齐检测（不在授予调用栈内展开新事务，
     * 队列 FIFO 保证与后续写操作串行）；来源仅作签名透传，检测不区分来源。
     */
    @Override
    public void onGranted(UUID owner, int titleId, GrantSource source) {
        checkNow(owner, titleId);
    }

    /**
     * 显式触发一次集齐检测（写线程任务；测试与管理命令入口）。
     *
     * @param owner   玩家 UUID
     * @param titleId 新获得的称号 id（作为检测种子）
     * @return 本次（含级联）自动授予的羁绊称号数
     */
    public CompletableFuture<Integer> checkNow(UUID owner, int titleId) {
        Objects.requireNonNull(owner, "玩家 UUID 不能为空");
        return dbExecutor.submitWrite(() -> checkOnWriter(owner, titleId));
    }

    /**
     * 回溯补发：对全服已集齐给定羁绊需求的玩家补授（含离线——按 DB 所有权
     * 全表扫描取候选，逐批事务内权威复核差集后授予）。
     * <p>paper 侧在 {@code setBondRequirements} / 创建羁绊称号完成后调用。
     *
     * @param bondId 羁绊称号 id
     * @param actor  操作者（审计）
     * @return 补授人数
     */
    public CompletableFuture<Integer> backfill(int bondId, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> {
            Title bond = bondOf(bondId);
            if (bond == null) {
                return 0;
            }
            List<UUID> candidates = txRunner.withConnection(c ->
                    ownershipRepository.findOwnersHavingAll(c, bond.bondRequirements()));
            boolean cascadable = !catalogCache.bondsRequiring(bondId).isEmpty();
            int granted = 0;
            for (int from = 0; from < candidates.size(); from += BACKFILL_BATCH_SIZE) {
                List<UUID> batch = candidates.subList(from,
                        Math.min(from + BACKFILL_BATCH_SIZE, candidates.size()));
                long now = System.currentTimeMillis();
                List<BondGrant> grants = txRunner.inTransaction(c -> {
                    List<BondGrant> done = new ArrayList<>();
                    for (UUID owner : batch) {
                        List<Ownership> actives = ownershipRepository.findActiveByOwner(c, owner);
                        Set<Integer> owned = ownedTitleIds(actives, now);
                        // 差集权威复核：已持有短路 + 需求全 ACTIVE 未到期（候选查询不含到期过滤）
                        if (owned.contains(bondId) || !owned.containsAll(bond.bondRequirements())) {
                            continue;
                        }
                        String ownerName = actives.getFirst().ownerName();
                        long id = insertBondGrantInTx(c, bondId, owner, ownerName, actor, now);
                        done.add(new BondGrant(owner, ownerName, id));
                    }
                    return done;
                });
                for (BondGrant grant : grants) {
                    mergeIntoCache(grant, bondId, now);
                    downstream.onGranted(grant.owner(), bondId, GrantSource.BOND);
                    if (cascadable) {
                        granted += checkOnWriter(grant.owner(), bondId); // 级联（羁绊套羁绊）
                    }
                }
                granted += grants.size();
            }
            return granted;
        });
    }

    /**
     * 写线程检测主体（worklist 级联）：种子称号 → 反向索引取候选羁绊 →
     * 集齐且未持有 → 独立事务授予 → 新授予的羁绊再入队为种子。
     * <p>终止保证：owned 集合只增不减、已持有短路，work 项数 ≤ 授予数 + 1。
     */
    private int checkOnWriter(UUID owner, int seedTitleId) {
        long now = System.currentTimeMillis();
        List<Ownership> actives = txRunner.withConnection(c ->
                ownershipRepository.findActiveByOwner(c, owner));
        Set<Integer> owned = ownedTitleIds(actives, now);
        if (owned.isEmpty()) {
            return 0;
        }
        String ownerName = actives.getFirst().ownerName();
        Deque<Integer> work = new ArrayDeque<>();
        work.add(seedTitleId);
        int granted = 0;
        while (!work.isEmpty()) {
            int changed = work.poll();
            for (int bondId : catalogCache.bondsRequiring(changed)) {
                Title bond = bondOf(bondId);
                if (bond == null || owned.contains(bondId)
                        || !owned.containsAll(bond.bondRequirements())) {
                    continue;
                }
                Long ownershipId = grantBond(bondId, owner, ownerName, now);
                if (ownershipId != null) {
                    owned.add(bondId);
                    mergeIntoCache(new BondGrant(owner, ownerName, ownershipId), bondId, now);
                    downstream.onGranted(owner, bondId, GrantSource.BOND);
                    work.add(bondId);
                    granted++;
                }
            }
        }
        return granted;
    }

    /**
     * 独立事务授予羁绊（撞唯一约束时视为并发已持有，零副作用返回空）。
     */
    private @Nullable Long grantBond(int bondId, UUID owner, String ownerName, long now) {
        try {
            return txRunner.inTransaction(c ->
                    insertBondGrantInTx(c, bondId, owner, ownerName, Actor.system(), now));
        } catch (DuplicateActiveException e) {
            return null;
        }
    }

    /**
     * 事务内插入羁绊授予行 + 审计（恒永久：expiresAt=null）。
     */
    private long insertBondGrantInTx(Connection c, int bondId, UUID owner,
                                     String ownerName, Actor actor, long now) throws SQLException {
        long ownershipId = ownershipRepository.insertGrant(c, new Ownership(0, bondId, owner,
                ownerName, GrantSource.BOND, null, now, null, OwnershipStatus.ACTIVE, null, null));
        auditRepository.insert(c, new AuditRepository.AuditEntry(
                "BOND_GRANT", actor.uuid(), actor.name(), owner, bondId, ownershipId,
                null, null, null, now));
        return ownershipId;
    }

    /**
     * 提交后把羁绊授予合入档案缓存（离线玩家 no-op）。
     */
    private void mergeIntoCache(BondGrant grant, int bondId, long now) {
        Ownership granted = new Ownership(grant.ownershipId(), bondId, grant.owner(),
                grant.ownerName(), GrantSource.BOND, null, now, null,
                OwnershipStatus.ACTIVE, null, null);
        profileCache.updateIfPresent(grant.owner(), profile -> profile.withOwnership(granted));
    }

    /**
     * 从缓存取可参与检测的羁绊称号（未软删、BOND 种类、需求非空）。
     */
    private @Nullable Title bondOf(int bondId) {
        return catalogCache.byId(bondId)
                .filter(t -> !t.isDeleted() && t.category() == TitleCategory.BOND
                        && !t.bondRequirements().isEmpty())
                .orElse(null);
    }

    /**
     * ACTIVE 且未到期的持有称号 id 集合（到期未收敛的所有权不算集齐）。
     */
    private static Set<Integer> ownedTitleIds(List<Ownership> actives, long now) {
        return actives.stream()
                .filter(o -> !o.isExpired(now))
                .map(Ownership::titleId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 单条羁绊授予回执。
     */
    private record BondGrant(UUID owner, String ownerName, long ownershipId) {
    }
}
