package net.baiyu.title.service;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.group.ReconcileReport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家生命周期服务（paper 视图门面层）。
 *
 * <p><b>登录预载时序（强制约束）</b>：改名同步、到期收敛、
 * DB 预载与 {@link PlayerProfileCache#put} 必须放进<b>同一个</b>
 * {@link DbExecutor#submitWrite} 任务串行执行——写线程单线程串行化保证
 * 预载期间不可能有其他写服务的增量更新插队，杜绝"陈旧快照覆盖新增量"
 * 导致缓存与 DB 长期脱节（见 {@code PlayerProfileCache.put} 的 Javadoc 时序警示）。</p>
 *
 * <p>退出驱逐延迟 30 秒：短线重进免于重复预载；
 * 到点仍离线才驱逐，重进后由新预载整体替换。</p>
 */
public final class PlayerLifecycleService {

    /** 退出后延迟驱逐档案的时长（tick；30 秒 × 20 tick/秒）。 */
    private static final long EVICT_DELAY_TICKS = 30L * 20L;

    /** 插件实例（延迟驱逐调度归属）。 */
    private final BaiYuTitle plugin;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 所有权仓储（预载 ACTIVE 所有权）。 */
    private final OwnershipRepository ownershipRepository;

    /** 装扮栏位仓储（预载栏位与随机池）。 */
    private final SlotRepository slotRepository;

    /** 称号币仓储（改名同步 + 预载余额）。 */
    private final CoinRepository coinRepository;

    /** 交易仓储（预载交易计数——PAPI %byt_player_trade_*% 的零 IO 数据源）。 */
    private final TradeRepository tradeRepository;

    /** 到期收敛服务（登录预载前对该玩家先收敛一次）。 */
    private final ExpiryService expiryService;

    /** 玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 玩家组服务（登录组对账）。 */
    private final GroupService groupService;

    /** 组资格桥接（主线程权限快照收集 / 退出驱逐）。 */
    private final GroupAccessBridge groupAccessBridge;

    /** 栏位上限解析器（登录时主线程解析后冻结入档案快照）。 */
    private final WardrobeService.SlotLimitResolver slotLimitResolver;

    /**
     * 构造玩家生命周期服务。
     *
     * @param plugin              插件实例
     * @param dbExecutor          数据库执行器
     * @param txRunner            事务执行器
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param coinRepository      称号币仓储
     * @param tradeRepository     交易仓储
     * @param expiryService       到期收敛服务
     * @param profileCache        玩家档案缓存
     * @param groupService        玩家组服务
     * @param groupAccessBridge   组资格桥接
     * @param slotLimitResolver   栏位上限解析器（仅主线程触碰权限系统）
     */
    public PlayerLifecycleService(BaiYuTitle plugin, DbExecutor dbExecutor, TransactionRunner txRunner,
                                  OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                                  CoinRepository coinRepository, TradeRepository tradeRepository,
                                  ExpiryService expiryService,
                                  PlayerProfileCache profileCache, GroupService groupService,
                                  GroupAccessBridge groupAccessBridge,
                                  WardrobeService.SlotLimitResolver slotLimitResolver) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.coinRepository = Objects.requireNonNull(coinRepository, "称号币仓储不能为空");
        this.tradeRepository = Objects.requireNonNull(tradeRepository, "交易仓储不能为空");
        this.expiryService = Objects.requireNonNull(expiryService, "到期收敛服务不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.groupService = Objects.requireNonNull(groupService, "玩家组服务不能为空");
        this.groupAccessBridge = Objects.requireNonNull(groupAccessBridge, "组资格桥接不能为空");
        this.slotLimitResolver = Objects.requireNonNull(slotLimitResolver, "栏位上限解析器不能为空");
    }

    /**
     * 处理玩家登录：主线程先冻结组权限快照（Bukkit 权限系统只在主线程触碰），
     * 随后同一个写任务内串行完成 改名同步 → 组对账（补授/补收）→
     * 到期收敛（仅该玩家）→ DB 预载（所有权 / 栏位 / 随机池 / 余额）→ 档案挂载。
     * <p>组对账必须在档案装载之前：对账的授予/收回走 updateIfPresent 增量合并，
     * 档案未挂载时是 no-op，靠随后的全量预载读到对账后的权威状态。
     * 任务完成后聊天路径即可读到快照；失败时档案不挂载，聊天降级为
     * 默认称号展示（调用方负责日志）。</p>
     *
     * @param player 登录玩家（主线程取 UUID 与名字后进入异步链，不跨线程持有实体）
     * @return 预载完成 future
     */
    public CompletableFuture<Void> handleJoin(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Set<String> heldGroupNodes = groupAccessBridge.snapshot(player);
        // 栏位上限主线程冻结（权限系统仅主线程）：随档案快照供 PAPI 零 IO 取用
        int maxSlots = slotLimitResolver.maxSlotsOf(uuid);
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            syncPlayerName(uuid, name, now);
            ReconcileReport report = groupService.reconcileOnWriter(uuid, name, heldGroupNodes);
            if (report.hasChanges()) {
                plugin.getComponentLogger().info("登录组对账：玩家 {} 补授 {} 项、补收 {} 项组称号。",
                        name, report.grantedTitleIds().size(), report.revokedTitleIds().size());
            }
            expiryService.convergeOwner(uuid);
            profileCache.put(loadProfile(uuid, name, maxSlots));
            return null;
        });
    }

    /**
     * 处理玩家退出：延迟 30 秒后仍离线才驱逐档案与组权限快照（短线重进不打断）。
     * <p>主线程调用；到点回调亦在主线程（在线判定依赖 Bukkit API）。</p>
     *
     * @param player 退出玩家
     */
    public void handleQuit(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(uuid) == null) {
                profileCache.evict(uuid);
                groupAccessBridge.evict(uuid);
            }
        }, EVICT_DELAY_TICKS);
    }

    /**
     * 改名同步：确保称号币行存在并刷新其玩家名缓存列（独立小事务；
     * 所有权行的 owner_name 为授予时刻的历史快照，不回写）。
     */
    private void syncPlayerName(UUID uuid, String name, long now) {
        txRunner.inTransaction(c -> {
            coinRepository.ensureRow(c, uuid, name, now);
            return null;
        });
    }

    /**
     * 预载玩家档案（写线程上的无事务读；到期收敛已在同任务前置完成，
     * 读到的 ACTIVE 所有权不含"已到期未收敛"行）。
     * <p>交易计数一并预载（发起 = 任意状态的发起方；完成 = 任一侧 COMPLETED），
     * 此后由交易结算写穿增量维护——PAPI 交易变量因此零 DB IO。</p>
     */
    private PlayerProfileCache.PlayerProfile loadProfile(UUID uuid, String name, int maxSlots) {
        return txRunner.withConnection(c -> {
            List<Ownership> actives = ownershipRepository.findActiveByOwner(c, uuid);
            Map<Integer, Ownership> byTitleId = new HashMap<>();
            for (Ownership ownership : actives) {
                byTitleId.put(ownership.titleId(), ownership);
            }
            List<SlotConfig> slots = slotRepository.load(c, uuid);
            Map<Integer, Set<Integer>> randomPools = new HashMap<>();
            for (SlotConfig slot : slots) {
                if (slot.isRandom()) {
                    randomPools.put(slot.slotIndex(),
                            slotRepository.loadPool(c, uuid, slot.slotIndex()));
                }
            }
            long coinBalance = coinRepository.getBalance(c, uuid);
            int tradeInitiated = tradeRepository.countByPlayer(c, uuid, true, false);
            int tradeCompleted = tradeRepository.countByPlayer(c, uuid, false, true);
            return new PlayerProfileCache.PlayerProfile(uuid, name, byTitleId, slots, randomPools,
                    coinBalance, maxSlots, tradeInitiated, tradeCompleted);
        });
    }
}
