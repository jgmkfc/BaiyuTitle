package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 装扮服务：多栏位装扮配置的唯一写入口。
 * <p>校验（不变量 §十 10 / §十 13）：栏位下标 ∈ [1, maxSlots]（上限由
 * {@link SlotLimitResolver} 按权限解析，入口线程同步取得后带入异步链）；
 * 目标称号与随机池每项必须是本人 ACTIVE 且未到期的所有权（事务内权威复核），
 * 且称号未软删（目录缓存判定——软删不回收所有权，但不可再装扮）；
 * GROUP 称号另经 {@link MembershipVerifier} 复核成员资格（M1 评审遗留 2：
 * 被移出组后 reconcile 前的窗口内不可新装扮）。
 * <p>校验失败返回 {@link WardrobeResult} 枚举结果，不抛业务异常。
 */
public final class WardrobeService {

    /** 所有权仓储（装扮目标权威校验）。 */
    private final OwnershipRepository ownershipRepository;

    /** 装扮栏位仓储。 */
    private final SlotRepository slotRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 称号目录缓存（软删过滤；写线程读缓存与软删写穿同线程串行，无竞态）。 */
    private final TitleCatalogCache catalogCache;

    /** 玩家档案缓存（提交后增量更新）。 */
    private final PlayerProfileCache profileCache;

    /** 栏位上限解析器（paper 侧按权限实现，测试注入常量）。 */
    private final SlotLimitResolver slotLimitResolver;

    /** 组称号成员资格校验钩子（GroupService 接入）。 */
    private final MembershipVerifier membershipVerifier;

    /**
     * 构造装扮服务。
     *
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param slotLimitResolver   栏位上限解析器
     * @param membershipVerifier  组称号成员资格校验钩子（可空 = 恒放行）
     */
    public WardrobeService(OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                           TransactionRunner txRunner, DbExecutor dbExecutor,
                           TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                           SlotLimitResolver slotLimitResolver,
                           @Nullable MembershipVerifier membershipVerifier) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.slotLimitResolver = Objects.requireNonNull(slotLimitResolver, "栏位上限解析器不能为空");
        this.membershipVerifier =
                membershipVerifier == null ? MembershipVerifier.ALLOW_ALL : membershipVerifier;
    }

    /**
     * 设置固定栏位称号（titleId 为空 = 展示默认称号）；保留栏位既有启用状态。
     *
     * @param player    玩家 UUID
     * @param slotIndex 栏位下标（1 起）
     * @param titleId   称号 id（可空）
     * @return 操作结果
     */
    public CompletableFuture<WardrobeResult> setSlotTitle(UUID player, int slotIndex,
                                                          @Nullable Integer titleId) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        if (outOfRange(player, slotIndex)) {
            return CompletableFuture.completedFuture(WardrobeResult.SLOT_OUT_OF_RANGE);
        }
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            SlotConfig saved = txRunner.inTransaction(c -> {
                if (titleId != null && !ownsActive(c, player, titleId, now)) {
                    return null;
                }
                SlotConfig slot = new SlotConfig(slotIndex,
                        enabledOf(c, player, slotIndex), SlotMode.FIXED, titleId);
                slotRepository.upsert(c, player, slot);
                return slot;
            });
            if (saved == null) {
                return WardrobeResult.TITLE_NOT_OWNED;
            }
            profileCache.updateIfPresent(player, profile -> profile.withSlot(saved));
            return WardrobeResult.SUCCESS;
        });
    }

    /**
     * 启用/停用栏位（保留栏位既有模式与称号；栏位不存在时按默认配置补建）。
     *
     * @param player    玩家 UUID
     * @param slotIndex 栏位下标（1 起）
     * @param enabled   是否启用
     * @return 操作结果
     */
    public CompletableFuture<WardrobeResult> setSlotEnabled(UUID player, int slotIndex, boolean enabled) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        if (outOfRange(player, slotIndex)) {
            return CompletableFuture.completedFuture(WardrobeResult.SLOT_OUT_OF_RANGE);
        }
        return dbExecutor.submitWrite(() -> {
            SlotConfig saved = txRunner.inTransaction(c -> {
                SlotConfig current = findSlot(c, player, slotIndex)
                        .orElse(new SlotConfig(slotIndex, true, SlotMode.FIXED, null));
                SlotConfig updated = new SlotConfig(slotIndex, enabled, current.mode(), current.titleId());
                slotRepository.upsert(c, player, updated);
                return updated;
            });
            profileCache.updateIfPresent(player, profile -> profile.withSlot(saved));
            return WardrobeResult.SUCCESS;
        });
    }

    /**
     * 设置随机栏位并全量替换候选池（池内每项同样校验 ACTIVE 未到期）。
     *
     * @param player    玩家 UUID
     * @param slotIndex 栏位下标（1 起）
     * @param pool      候选池称号 id 集合
     * @return 操作结果
     */
    public CompletableFuture<WardrobeResult> setSlotRandom(UUID player, int slotIndex, Set<Integer> pool) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Set<Integer> poolCopy = pool == null ? Set.of() : Set.copyOf(pool);
        if (outOfRange(player, slotIndex)) {
            return CompletableFuture.completedFuture(WardrobeResult.SLOT_OUT_OF_RANGE);
        }
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            SlotConfig saved = txRunner.inTransaction(c -> {
                for (Integer poolTitleId : poolCopy) {
                    if (!ownsActive(c, player, poolTitleId, now)) {
                        return null;
                    }
                }
                SlotConfig slot = new SlotConfig(slotIndex,
                        enabledOf(c, player, slotIndex), SlotMode.RANDOM, null);
                slotRepository.upsert(c, player, slot);
                slotRepository.replacePool(c, player, slotIndex, poolCopy);
                return slot;
            });
            if (saved == null) {
                return WardrobeResult.TITLE_NOT_OWNED;
            }
            profileCache.updateIfPresent(player,
                    profile -> profile.withSlot(saved).withRandomPool(slotIndex, poolCopy));
            return WardrobeResult.SUCCESS;
        });
    }

    /**
     * 读路径：装载玩家全部栏位配置（读池，不走事务；登录预载用）。
     *
     * @param player 玩家 UUID
     * @return 栏位配置列表（按栏位下标升序）
     */
    public CompletableFuture<List<SlotConfig>> loadSlots(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> slotRepository.load(c, player)));
    }

    /**
     * 栏位下标越界判定（在调用线程同步解析上限，避免写线程触碰权限系统）。
     */
    private boolean outOfRange(UUID player, int slotIndex) {
        int maxSlots = slotLimitResolver.maxSlotsOf(player);
        return slotIndex < 1 || slotIndex > maxSlots;
    }

    /**
     * 事务内权威校验：称号未软删（目录缓存判定，不变量 §十 13——软删不回收
     * 所有权但不可再装扮），GROUP 称号另过成员资格钩子（不变量 §十 10：
     * 栏位引用必属本人 ACTIVE 未到期，GROUP 另验成员资格），
     * 且玩家对其持有 ACTIVE 未到期的所有权。
     */
    private boolean ownsActive(Connection c, UUID player, int titleId, long now) throws SQLException {
        Title title = catalogCache.byId(titleId).filter(t -> !t.isDeleted()).orElse(null);
        if (title == null) {
            return false;
        }
        if (title.category() == TitleCategory.GROUP
                && !membershipVerifier.isActiveMember(player, titleId)) {
            return false;
        }
        return ownershipRepository.findActive(c, player, titleId)
                .filter(ownership -> !ownership.isExpired(now))
                .isPresent();
    }

    /**
     * 事务内取栏位既有启用状态（无记录时默认启用）。
     */
    private boolean enabledOf(Connection c, UUID player, int slotIndex) throws SQLException {
        return findSlot(c, player, slotIndex).map(SlotConfig::enabled).orElse(true);
    }

    /**
     * 事务内查找单条栏位配置。
     */
    private Optional<SlotConfig> findSlot(Connection c, UUID player, int slotIndex) throws SQLException {
        return slotRepository.load(c, player).stream()
                .filter(slot -> slot.slotIndex() == slotIndex)
                .findFirst();
    }

    /**
     * 栏位上限解析器（paper 侧按 config groups 首个权限命中实现；测试注入常量）。
     */
    @FunctionalInterface
    public interface SlotLimitResolver {

        /**
         * 解析玩家的装扮栏位上限。
         *
         * @param player 玩家 UUID
         * @return 栏位上限（≥ 0）
         */
        int maxSlotsOf(UUID player);
    }

    /**
     * 组称号成员资格校验钩子（M1 评审遗留 2：装扮 GROUP 称号时复核有效成员资格，
     * 覆盖"被移出组后 reconcile 前的窗口内"仍持有 ACTIVE 所有权的空档）。
     * <p>调用时机：写线程装扮事务内。实现方（paper 侧接 GroupService）只允许做
     * DbExecutor 线程安全的 DB / 缓存读，禁止触碰 Bukkit API。
     */
    @FunctionalInterface
    public interface MembershipVerifier {

        /** 恒放行实现（默认；未接入组服务时使用）。 */
        MembershipVerifier ALLOW_ALL = (player, titleId) -> true;

        /**
         * 复核玩家对某 GROUP 称号的有效成员资格（member 表 ∪ 权限持有者）。
         *
         * @param player  玩家 UUID
         * @param titleId GROUP 称号 id
         * @return 有效成员返回 true
         */
        boolean isActiveMember(UUID player, int titleId);
    }

    /**
     * 装扮操作结果。
     */
    public enum WardrobeResult {

        /** 操作成功。 */
        SUCCESS,

        /** 栏位下标越界（不在 [1, maxSlots] 内）。 */
        SLOT_OUT_OF_RANGE,

        /**
         * 目标称号（或随机池某项）不是本人 ACTIVE 未到期的所有权、称号已软删，
         * 或 GROUP 称号成员资格复核未通过。
         */
        TITLE_NOT_OWNED
    }
}
