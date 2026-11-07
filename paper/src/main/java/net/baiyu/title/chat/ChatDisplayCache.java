package net.baiyu.title.chat;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.wardrobe.SlotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天展示视图门面：把两份 volatile 缓存快照组合成
 * 佩戴栏位视图列表，供聊天渲染与 PAPI 消费。
 *
 * <p><b>零 DB IO 纪律</b>：本类只读
 * {@link PlayerProfileCache} 与 {@link TitleCatalogCache} 的不可变快照，
 * 可在任意线程调用（含 AsyncChatEvent 的异步线程）。</p>
 *
 * <p>视图裁决：</p>
 * <ul>
 *   <li>停用栏位不产出视图；{@code expires_at < now} 的所有权条目直接跳过
 *       （渲染层按时间戳即时隐藏，不等 ExpiryService 收敛五）；</li>
 *   <li>固定栏位的称号失效（到期 / 软删 / 所有权缺失）时降级为默认称号视图，
 *       与收敛后 titleId 置空的终态一致，避免视觉跳变；</li>
 *   <li>档案未挂载（预载窗口）或玩家从未配置过栏位时，产出单条隐式默认
 *       称号视图（新玩家展示默认称号）。</li>
 * </ul>
 */
public final class ChatDisplayCache {

    /** 隐式默认视图的栏位下标（栏位下标从 1 起，见 WardrobeService 校验区间）。 */
    private static final int IMPLICIT_SLOT_INDEX = 1;

    /** 隐式默认视图列表（档案缺失 / 从未配置栏位时复用的常量）。 */
    private static final List<EquippedSlotView> IMPLICIT_DEFAULT =
            List.of(new EquippedSlotView(IMPLICIT_SLOT_INDEX, false, List.of()));

    /** 在线玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /**
     * 构造聊天展示视图门面。
     *
     * @param profileCache 在线玩家档案缓存
     * @param catalogCache 称号目录缓存
     */
    public ChatDisplayCache(PlayerProfileCache profileCache, TitleCatalogCache catalogCache) {
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
    }

    /**
     * 查询玩家当前的佩戴栏位视图列表（以当前系统时间判定到期）。
     *
     * @param player 玩家 UUID
     * @return 佩戴栏位视图列表（不可变；空列表 = 无任何可展示栏位）
     */
    public List<EquippedSlotView> equippedSlots(UUID player) {
        return equippedSlots(player, System.currentTimeMillis());
    }

    /**
     * 查询玩家当前的佩戴栏位视图列表（显式时刻重载，供单测注入时钟）。
     *
     * @param player 玩家 UUID
     * @param now    当前时刻（UTC epoch 毫秒；到期判定基准）
     * @return 佩戴栏位视图列表（不可变；空列表 = 全部栏位被玩家主动停用）
     */
    public List<EquippedSlotView> equippedSlots(UUID player, long now) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Optional<PlayerProfileCache.PlayerProfile> maybeProfile = profileCache.get(player);
        if (maybeProfile.isEmpty()) {
            return IMPLICIT_DEFAULT;
        }
        PlayerProfileCache.PlayerProfile profile = maybeProfile.get();
        if (profile.slots().isEmpty()) {
            return IMPLICIT_DEFAULT;
        }
        List<EquippedSlotView> views = new ArrayList<>(profile.slots().size());
        for (SlotConfig slot : profile.slots()) {
            if (!slot.enabled()) {
                continue;
            }
            views.add(slot.isRandom()
                    ? randomView(profile, slot, now)
                    : fixedView(profile, slot, now));
        }
        return List.copyOf(views);
    }

    /**
     * 构建固定栏位视图：目标称号有效则恰含一个候选；否则降级默认称号（候选空集）。
     */
    private EquippedSlotView fixedView(PlayerProfileCache.PlayerProfile profile,
                                       SlotConfig slot, long now) {
        List<Title> candidates = slot.titleId() == null
                ? List.of()
                : resolve(profile, slot.titleId(), now).map(List::of).orElse(List.of());
        return new EquippedSlotView(slot.slotIndex(), false, candidates);
    }

    /**
     * 构建随机栏位视图：候选 = 随机池中仍有效的称号，按称号 id 升序
     * （集合迭代序不稳定，排序保证同池取样分布可复现，便于单测）。
     */
    private EquippedSlotView randomView(PlayerProfileCache.PlayerProfile profile,
                                        SlotConfig slot, long now) {
        Set<Integer> pool = profile.randomPools().getOrDefault(slot.slotIndex(), Set.of());
        List<Title> candidates = pool.stream()
                .sorted()
                .map(titleId -> resolve(profile, titleId, now))
                .flatMap(Optional::stream)
                .toList();
        return new EquippedSlotView(slot.slotIndex(), true, candidates);
    }

    /**
     * 解析单个称号引用：要求本人持有 ACTIVE 且未到期的所有权，且称号在目录中
     * 未软删；任一不满足返回空。
     */
    private Optional<Title> resolve(PlayerProfileCache.PlayerProfile profile, int titleId, long now) {
        Ownership ownership = profile.activeOwnerships().get(titleId);
        if (ownership == null || !ownership.isActive() || ownership.isExpired(now)) {
            return Optional.empty();
        }
        return catalogCache.byId(titleId).filter(title -> !title.isDeleted());
    }

    /**
     * 佩戴栏位视图（聊天渲染与 PAPI 共用的不可变快照）。
     *
     * @param slotIndex  栏位下标（从 1 起）
     * @param random     是否随机模式（PAPI"随机(池大小)"展示依据）
     * @param candidates 候选称号：固定栏位有效时恰一个；随机栏位为有效池
     *                   （按称号 id 升序）；空集 = 展示默认称号
     */
    public record EquippedSlotView(int slotIndex, boolean random, List<Title> candidates) {

        /**
         * 紧凑构造器：候选列表防御性拷贝（null 视为空集）。
         */
        public EquippedSlotView {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /**
         * 是否降级为默认称号展示（候选空集）。
         *
         * @return 展示默认称号返回 true
         */
        public boolean isDefaultTitle() {
            return candidates.isEmpty();
        }
    }
}
