package net.baiyu.title.cache;

import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.wardrobe.SlotConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 在线玩家档案缓存。
 * <p>装载策略：登录异步预载后整体挂载；写服务事务提交后增量更新
 * （变更 = 整体替换不可变 {@link PlayerProfile}，读方永远看到一致快照）。
 * <p>驱逐：M1 提供立即 {@link #evict(UUID)}；退出延迟 30 秒驱逐由 paper 侧
 * 调度器在 M2 组合（调度到点后调用本方法即可）。
 */
public final class PlayerProfileCache {

    /** 主索引：玩家 UUID → 不可变档案快照。 */
    private final ConcurrentHashMap<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    /**
     * 查询玩家档案快照。
     *
     * @param player 玩家 UUID
     * @return 档案快照；不在线（未装载）时为空
     */
    public Optional<PlayerProfile> get(UUID player) {
        return Optional.ofNullable(profiles.get(player));
    }

    /**
     * 挂载（或整体替换）玩家档案（登录预载完成 / 全量重建后调用）。
     * <p><b>时序警示（接线约束）</b>：DB 预载与本方法必须放进同一个
     * {@code DbExecutor.submitWrite} 任务串行执行——若预载在读池、挂载在主线程，
     * 预载窗口内写线程提交的 {@link #updateIfPresent} 增量（档案未挂载时 no-op）
     * 会被随后 put 的陈旧快照整体覆盖，缓存与 DB 长期脱节直到玩家重进。
     *
     * @param profile 档案快照
     */
    public void put(PlayerProfile profile) {
        profiles.put(profile.uuid(), profile);
    }

    /**
     * 原子增量更新：玩家在缓存中时以旧快照计算新快照并整体替换；
     * 不在缓存中时什么也不做（离线玩家的变更下次预载自然收敛）。
     *
     * @param player 玩家 UUID
     * @param change 快照变换函数（必须无副作用）
     */
    public void updateIfPresent(UUID player, UnaryOperator<PlayerProfile> change) {
        profiles.computeIfPresent(player, (uuid, current) -> change.apply(current));
    }

    /**
     * 对全部在线档案原子增量更新（称号软删等全局事件提交后调用）。
     *
     * @param change 快照变换函数（必须无副作用）
     */
    public void updateAll(UnaryOperator<PlayerProfile> change) {
        profiles.replaceAll((uuid, current) -> change.apply(current));
    }

    /**
     * 立即驱逐玩家档案（退出延迟驱逐到点 / 强制失效时调用）。
     *
     * @param player 玩家 UUID
     */
    public void evict(UUID player) {
        profiles.remove(player);
    }

    /**
     * 清空全部档案（reload 全量重建前调用）。
     */
    public void clear() {
        profiles.clear();
    }

    /**
     * 玩家档案不可变快照（一切变更 = 整体替换）。
     *
     * @param uuid             玩家 UUID
     * @param name             玩家名
     * @param activeOwnerships ACTIVE 所有权索引（称号 id → 所有权实例）
     * @param slots            装扮栏位配置（按栏位下标升序）
     * @param randomPools      随机栏位候选池（栏位下标 → 称号 id 集合）
     * @param coinBalance      称号币余额
     * @param maxSlots         栏位上限快照（登录时主线程按权限解析；PAPI 零 IO 数据源，
     *                         会话中权限变更不追更，重进刷新）
     * @param tradeInitiated   发起交易总数（任意状态；创建报价提交后写穿）
     * @param tradeCompleted   完成交易总数（任一侧 COMPLETED；成交提交后双方写穿）
     */
    public record PlayerProfile(
            UUID uuid,
            String name,
            Map<Integer, Ownership> activeOwnerships,
            List<SlotConfig> slots,
            Map<Integer, Set<Integer>> randomPools,
            long coinBalance,
            int maxSlots,
            int tradeInitiated,
            int tradeCompleted) {

        /**
         * 紧凑构造器：判空 + 深度防御性拷贝（内层集合一并转不可变）。
         */
        public PlayerProfile {
            Objects.requireNonNull(uuid, "玩家 UUID 不能为空");
            Objects.requireNonNull(name, "玩家名不能为空");
            activeOwnerships = activeOwnerships == null ? Map.of() : Map.copyOf(activeOwnerships);
            slots = slots == null ? List.of() : List.copyOf(slots);
            if (randomPools == null) {
                randomPools = Map.of();
            } else {
                Map<Integer, Set<Integer>> frozen = new HashMap<>();
                randomPools.forEach((slotIndex, pool) -> frozen.put(slotIndex, Set.copyOf(pool)));
                randomPools = Map.copyOf(frozen);
            }
            if (maxSlots < 0) {
                throw new IllegalArgumentException("栏位上限不能为负: " + maxSlots);
            }
            if (tradeInitiated < 0 || tradeCompleted < 0) {
                throw new IllegalArgumentException(
                        "交易计数不能为负: initiated=" + tradeInitiated + ", completed=" + tradeCompleted);
            }
        }

        /**
         * 兼容构造器（六参签名）：栏位上限按业务默认 1、交易计数按 0。
         *
         * @param uuid             玩家 UUID
         * @param name             玩家名
         * @param activeOwnerships ACTIVE 所有权索引
         * @param slots            装扮栏位配置
         * @param randomPools      随机栏位候选池
         * @param coinBalance      称号币余额
         */
        public PlayerProfile(UUID uuid, String name, Map<Integer, Ownership> activeOwnerships,
                             List<SlotConfig> slots, Map<Integer, Set<Integer>> randomPools,
                             long coinBalance) {
            this(uuid, name, activeOwnerships, slots, randomPools, coinBalance, 1, 0, 0);
        }

        /**
         * 替换称号币余额，返回新快照。
         *
         * @param newBalance 新余额
         * @return 新快照
         */
        public PlayerProfile withCoinBalance(long newBalance) {
            return new PlayerProfile(uuid, name, activeOwnerships, slots, randomPools, newBalance,
                    maxSlots, tradeInitiated, tradeCompleted);
        }

        /**
         * 替换发起交易总数，返回新快照。
         *
         * @param value 新计数
         * @return 新快照
         */
        public PlayerProfile withTradeInitiated(int value) {
            return new PlayerProfile(uuid, name, activeOwnerships, slots, randomPools, coinBalance,
                    maxSlots, value, tradeCompleted);
        }

        /**
         * 替换完成交易总数，返回新快照。
         *
         * @param value 新计数
         * @return 新快照
         */
        public PlayerProfile withTradeCompleted(int value) {
            return new PlayerProfile(uuid, name, activeOwnerships, slots, randomPools, coinBalance,
                    maxSlots, tradeInitiated, value);
        }

        /**
         * 合入一条 ACTIVE 所有权（按称号 id 覆盖），返回新快照。
         *
         * @param ownership 所有权实例
         * @return 新快照
         */
        public PlayerProfile withOwnership(Ownership ownership) {
            Map<Integer, Ownership> merged = new HashMap<>(activeOwnerships);
            merged.put(ownership.titleId(), ownership);
            return new PlayerProfile(uuid, name, merged, slots, randomPools, coinBalance,
                    maxSlots, tradeInitiated, tradeCompleted);
        }

        /**
         * 失去一个称号：移除其所有权，并清除一切栏位引用
         * （固定栏位引用置空、随机池剔除；与 DB 侧 clearTitleEverywhere 对齐），返回新快照。
         *
         * @param titleId 称号 id
         * @return 新快照
         */
        public PlayerProfile withoutTitle(int titleId) {
            Map<Integer, Ownership> remaining = new HashMap<>(activeOwnerships);
            remaining.remove(titleId);
            return new PlayerProfile(uuid, name, remaining, slots, randomPools, coinBalance,
                    maxSlots, tradeInitiated, tradeCompleted)
                    .withSlotRefsCleared(titleId);
        }

        /**
         * 清除一切栏位引用但保留所有权（称号软删用：软删不回收所有权、
         * 历史完整保留，仅卸下装扮；与 DB 侧 clearTitleForAllPlayers 对齐），返回新快照。
         *
         * @param titleId 称号 id
         * @return 新快照
         */
        public PlayerProfile withSlotRefsCleared(int titleId) {
            List<SlotConfig> scrubbedSlots = slots.stream()
                    .map(slot -> Objects.equals(slot.titleId(), titleId)
                            ? new SlotConfig(slot.slotIndex(), slot.enabled(), slot.mode(), null)
                            : slot)
                    .toList();
            Map<Integer, Set<Integer>> scrubbedPools = new HashMap<>();
            randomPools.forEach((slotIndex, pool) -> {
                Set<Integer> filtered = new HashSet<>(pool);
                filtered.remove(titleId);
                scrubbedPools.put(slotIndex, filtered);
            });
            return new PlayerProfile(uuid, name, activeOwnerships, scrubbedSlots, scrubbedPools,
                    coinBalance, maxSlots, tradeInitiated, tradeCompleted);
        }

        /**
         * 写入（或覆盖）一条栏位配置，保持按栏位下标升序，返回新快照。
         *
         * @param slot 栏位配置
         * @return 新快照
         */
        public PlayerProfile withSlot(SlotConfig slot) {
            List<SlotConfig> merged = new ArrayList<>(slots);
            merged.removeIf(existing -> existing.slotIndex() == slot.slotIndex());
            merged.add(slot);
            merged.sort(Comparator.comparingInt(SlotConfig::slotIndex));
            return new PlayerProfile(uuid, name, activeOwnerships, merged, randomPools, coinBalance,
                    maxSlots, tradeInitiated, tradeCompleted);
        }

        /**
         * 全量替换某栏位的随机池，返回新快照。
         *
         * @param slotIndex 栏位下标
         * @param pool      新候选池（称号 id 集合）
         * @return 新快照
         */
        public PlayerProfile withRandomPool(int slotIndex, Set<Integer> pool) {
            Map<Integer, Set<Integer>> merged = new HashMap<>(randomPools);
            merged.put(slotIndex, Set.copyOf(pool));
            return new PlayerProfile(uuid, name, activeOwnerships, slots, merged, coinBalance,
                    maxSlots, tradeInitiated, tradeCompleted);
        }
    }
}
