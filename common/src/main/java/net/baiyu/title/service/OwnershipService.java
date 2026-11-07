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
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.exception.DuplicateActiveException;
import net.baiyu.title.service.executor.DbExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 所有权服务：称号授予/回收的唯一入口。
 * <p>授予：写线程事务 insertGrant + audit；唯一约束冲突（已持有 ACTIVE 同款）
 * 转 {@link GrantResult.AlreadyOwned} 友好结果而非异常。
 * 回收：endActive + 清一切栏位引用 + audit（同事务，不变量 §十 10）。
 * <p>缓存合入在事务提交之后（不变量 §十 12）；提交后回调
 * {@link PostGrantHook}（由 paper 侧接 Post 事件与羁绊检测）。
 */
public final class OwnershipService {

    /** 所有权仓储。 */
    private final OwnershipRepository ownershipRepository;

    /** 装扮栏位仓储（回收时清引用）。 */
    private final SlotRepository slotRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 称号目录缓存（授予前存在性校验）。 */
    private final TitleCatalogCache catalogCache;

    /** 玩家档案缓存（提交后增量更新）。 */
    private final PlayerProfileCache profileCache;

    /** 授予提交后钩子。 */
    private final PostGrantHook postGrantHook;

    /** 回收提交后钩子。 */
    private final PostRevokeHook postRevokeHook;

    /**
     * 构造所有权服务（无回收钩子的兼容签名，回收钩子按 no-op）。
     *
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op）
     */
    public OwnershipService(OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                            AuditRepository auditRepository, TransactionRunner txRunner,
                            DbExecutor dbExecutor, TitleCatalogCache catalogCache,
                            PlayerProfileCache profileCache, @Nullable PostGrantHook postGrantHook) {
        this(ownershipRepository, slotRepository, auditRepository, txRunner, dbExecutor,
                catalogCache, profileCache, postGrantHook, null);
    }

    /**
     * 构造所有权服务。
     *
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op）
     * @param postRevokeHook      回收提交后钩子（可空 = no-op；paper 侧接 Post 事件）
     */
    public OwnershipService(OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                            AuditRepository auditRepository, TransactionRunner txRunner,
                            DbExecutor dbExecutor, TitleCatalogCache catalogCache,
                            PlayerProfileCache profileCache, @Nullable PostGrantHook postGrantHook,
                            @Nullable PostRevokeHook postRevokeHook) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.postGrantHook = postGrantHook == null ? PostGrantHook.NO_OP : postGrantHook;
        this.postRevokeHook = postRevokeHook == null ? PostRevokeHook.NO_OP : postRevokeHook;
    }

    /**
     * 授予称号：事务 insertGrant + audit，提交后合入档案缓存并回调钩子。
     * <p>羁绊称号授予恒永久（到期时刻强制置空）。
     *
     * @param owner     持有者 UUID
     * @param ownerName 持有者名称（缓存列）
     * @param titleId   称号 id
     * @param source    授予来源
     * @param sourceRef 来源引用 id（可空；如订单/交易 id）
     * @param expiresAt 到期时刻（可空 = 永久；UTC epoch 毫秒）
     * @param actor     操作者
     * @return 授予结果（Granted / AlreadyOwned / TitleNotFound）
     */
    public CompletableFuture<GrantResult> grant(UUID owner, String ownerName, int titleId,
                                                GrantSource source, @Nullable Long sourceRef,
                                                @Nullable Long expiresAt, Actor actor) {
        Objects.requireNonNull(owner, "持有者 UUID 不能为空");
        Objects.requireNonNull(ownerName, "持有者名称不能为空");
        Objects.requireNonNull(source, "授予来源不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        Optional<Title> title = catalogCache.byId(titleId).filter(t -> !t.isDeleted());
        if (title.isEmpty()) {
            return CompletableFuture.completedFuture(new GrantResult.TitleNotFound(titleId));
        }
        // 羁绊授予恒永久（BOND 种类或 BOND 来源均强制）
        Long effectiveExpiresAt =
                title.get().category() == TitleCategory.BOND || source == GrantSource.BOND
                        ? null : expiresAt;
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            Ownership pending = new Ownership(0, titleId, owner, ownerName, source, sourceRef,
                    now, effectiveExpiresAt, OwnershipStatus.ACTIVE, null, null);
            long ownershipId;
            try {
                ownershipId = txRunner.inTransaction(c -> {
                    long id = ownershipRepository.insertGrant(c, pending);
                    auditRepository.insert(c, new AuditRepository.AuditEntry(
                            "GRANT", actor.uuid(), actor.name(), owner, titleId, id,
                            null, sourceRef,
                            "{\"source\":\"" + source + "\"}", now));
                    return id;
                });
            } catch (DuplicateActiveException e) {
                return new GrantResult.AlreadyOwned(titleId);
            }
            Ownership granted = new Ownership(ownershipId, titleId, owner, ownerName, source,
                    sourceRef, now, effectiveExpiresAt, OwnershipStatus.ACTIVE, null, null);
            profileCache.updateIfPresent(owner, profile -> profile.withOwnership(granted));
            postGrantHook.onGranted(owner, titleId, source);
            return new GrantResult.Granted(ownershipId);
        });
    }

    /**
     * 回收称号：事务 endActive(REVOKED) + 清一切栏位引用 + audit，提交后更新档案缓存。
     *
     * @param owner   持有者 UUID
     * @param titleId 称号 id
     * @param reason  回收原因
     * @param actor   操作者
     * @return 回收成功 true；玩家无该称号 ACTIVE 所有权时 false
     */
    public CompletableFuture<Boolean> revoke(UUID owner, int titleId, String reason, Actor actor) {
        Objects.requireNonNull(owner, "持有者 UUID 不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            boolean revoked = txRunner.inTransaction(c -> {
                Optional<Ownership> active = ownershipRepository.findActive(c, owner, titleId);
                if (active.isEmpty()
                        || !ownershipRepository.endActive(c, active.get().id(),
                                OwnershipStatus.REVOKED, reason, now)) {
                    return false;
                }
                slotRepository.clearTitleEverywhere(c, owner, titleId);
                auditRepository.insert(c, new AuditRepository.AuditEntry(
                        "REVOKE", actor.uuid(), actor.name(), owner, titleId, active.get().id(),
                        null, null, reason, now));
                return true;
            });
            if (revoked) {
                profileCache.updateIfPresent(owner, profile -> profile.withoutTitle(titleId));
                postRevokeHook.onRevoked(owner, titleId, reason == null ? "" : reason);
            }
            return revoked;
        });
    }

    /**
     * 读路径：查询玩家全部 ACTIVE 所有权（读池，不走事务）。
     *
     * @param owner 玩家 UUID
     * @return ACTIVE 所有权列表
     */
    public CompletableFuture<List<Ownership>> findActiveByOwner(UUID owner) {
        Objects.requireNonNull(owner, "玩家 UUID 不能为空");
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> ownershipRepository.findActiveByOwner(c, owner)));
    }

    /**
     * 授予结果（密封类型穷举分支，调用方 switch 处理）。
     */
    public sealed interface GrantResult {

        /**
         * 授予成功。
         *
         * @param ownershipId 新所有权 id
         */
        record Granted(long ownershipId) implements GrantResult { }

        /**
         * 已持有同款 ACTIVE 所有权（不变量 §十 1 的友好呈现）。
         *
         * @param titleId 称号 id
         */
        record AlreadyOwned(int titleId) implements GrantResult { }

        /**
         * 称号不存在或已软删。
         *
         * @param titleId 称号 id
         */
        record TitleNotFound(int titleId) implements GrantResult { }
    }

    /**
     * 授予提交后钩子（在写线程、事务提交之后回调；
     * paper 侧切主线程发 Post 事件并入队羁绊检测）。
     */
    @FunctionalInterface
    public interface PostGrantHook {

        /** 空实现（默认）。 */
        PostGrantHook NO_OP = (owner, titleId, source) -> { };

        /**
         * 授予事务提交后回调。
         *
         * @param owner   持有者 UUID
         * @param titleId 称号 id
         * @param source  授予来源（各授予入口按自身语义传入）
         */
        void onGranted(UUID owner, int titleId, GrantSource source);

        /**
         * 顺序组合多个钩子（组合根装配"Post 事件桥接 + 羁绊检测"等链路用）。
         *
         * @param hooks 钩子列表（按声明顺序回调）
         * @return 组合钩子
         */
        static PostGrantHook compose(PostGrantHook... hooks) {
            PostGrantHook[] frozen = hooks.clone();
            return (owner, titleId, source) -> {
                for (PostGrantHook hook : frozen) {
                    hook.onGranted(owner, titleId, source);
                }
            };
        }
    }

    /**
     * 回收提交后钩子（在写线程、事务提交之后回调；paper 侧切主线程发 Post 事件）。
     * <p>只覆盖经 {@link #revoke} 的回收入口；组对账收回、交易转出与到期收敛
     * 各有专属语义，不经本钩子。</p>
     */
    @FunctionalInterface
    public interface PostRevokeHook {

        /** 空实现（默认）。 */
        PostRevokeHook NO_OP = (owner, titleId, reason) -> { };

        /**
         * 回收事务提交后回调。
         *
         * @param owner   原持有者 UUID
         * @param titleId 称号 id
         * @param reason  回收原因（非空；无原因时为空串）
         */
        void onRevoked(UUID owner, int titleId, String reason);
    }
}
