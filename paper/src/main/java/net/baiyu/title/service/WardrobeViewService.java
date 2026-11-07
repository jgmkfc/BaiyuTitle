package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 装扮视图门面：把两份缓存快照组装成装扮 / 仓库菜单的
 * 视图对象 {@link WardrobeView}，并把菜单侧的变更意图 {@link SlotSelection}
 * 适配到 {@link WardrobeService} 写入口。
 *
 * <p>线程约定：{@link #getView} / {@link #ownedTitles} 只读 volatile 不可变
 * 缓存快照，可在异步 loadData 线程调用（零 Bukkit、零 DB IO）；
 * {@link #maxSlotsOf} 触碰权限系统，<b>仅主线程</b>调用（菜单构造时同步取得
 * 后带入异步链）；变更方法主线程发起，服务内部调度并返回 future。</p>
 *
 * <p>视图裁决（与 {@code chat.ChatDisplayCache} 同源）：所有权须 ACTIVE 且
 * 未到期、称号在目录中未软删，任一不满足即视为无效——固定栏位降级默认称号、
 * 随机池剔除该项、持有列表不显示。</p>
 */
public final class WardrobeViewService {

    /** 装扮服务（变更写入口）。 */
    private final WardrobeService wardrobeService;

    /** 在线玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /** 栏位上限解析器（与 WardrobeService 共用同一实例，仅主线程调用）。 */
    private final WardrobeService.SlotLimitResolver slotLimitResolver;

    /** 到期时刻 → 展示日期的时区（UTC 存储 + 展示层按服务器本地时区换算）。 */
    private final ZoneId displayZone;

    /**
     * 构造装扮视图门面（生产装配：展示时区取服务器默认时区）。
     *
     * @param wardrobeService   装扮服务
     * @param profileCache      在线玩家档案缓存
     * @param catalogCache      称号目录缓存
     * @param slotLimitResolver 栏位上限解析器（与 WardrobeService 同一实例）
     */
    public WardrobeViewService(WardrobeService wardrobeService, PlayerProfileCache profileCache,
                               TitleCatalogCache catalogCache,
                               WardrobeService.SlotLimitResolver slotLimitResolver) {
        this(wardrobeService, profileCache, catalogCache, slotLimitResolver, ZoneId.systemDefault());
    }

    /**
     * 构造装扮视图门面（显式时区重载，供单测注入固定时区）。
     *
     * @param wardrobeService   装扮服务
     * @param profileCache      在线玩家档案缓存
     * @param catalogCache      称号目录缓存
     * @param slotLimitResolver 栏位上限解析器
     * @param displayZone       到期日期展示时区
     */
    public WardrobeViewService(WardrobeService wardrobeService, PlayerProfileCache profileCache,
                               TitleCatalogCache catalogCache,
                               WardrobeService.SlotLimitResolver slotLimitResolver, ZoneId displayZone) {
        this.wardrobeService = Objects.requireNonNull(wardrobeService, "装扮服务不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.slotLimitResolver = Objects.requireNonNull(slotLimitResolver, "栏位上限解析器不能为空");
        this.displayZone = Objects.requireNonNull(displayZone, "展示时区不能为空");
    }

    /**
     * 解析玩家的装扮栏位上限（<b>仅主线程</b>：内部触碰权限系统）。
     *
     * @param player 玩家 UUID
     * @return 栏位上限（≥ 0）
     */
    public int maxSlotsOf(UUID player) {
        return slotLimitResolver.maxSlotsOf(Objects.requireNonNull(player, "玩家 UUID 不能为空"));
    }

    /**
     * 组装装扮视图（任意线程；上限由主线程经 {@link #maxSlotsOf} 预解析后带入）。
     *
     * @param player   玩家 UUID
     * @param maxSlots 栏位上限（主线程预解析）
     * @return 装扮视图（栏位 1..maxSlots 逐个成视图，无记录按默认补齐）
     */
    public WardrobeView getView(UUID player, int maxSlots) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        final long now = System.currentTimeMillis();
        final PlayerProfileCache.PlayerProfile profile = profileCache.get(player).orElse(null);
        final List<WardrobeView.SlotView> slots = new ArrayList<>(Math.max(0, maxSlots));
        for (int slotNo = 1; slotNo <= maxSlots; slotNo++) {
            slots.add(slotView(profile, slotNo, now));
        }
        return new WardrobeView(maxSlots, slots, ownedTitles(player));
    }

    /**
     * 玩家当前持有的有效称号列表（任意线程；仓库 / 称号选择菜单条目源）。
     *
     * @param player 玩家 UUID
     * @return 按称号 id 升序的持有视图列表（档案未挂载时为空列表）
     */
    public List<WardrobeView.OwnedTitle> ownedTitles(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        final Optional<PlayerProfileCache.PlayerProfile> maybeProfile = profileCache.get(player);
        if (maybeProfile.isEmpty()) {
            return List.of();
        }
        final PlayerProfileCache.PlayerProfile profile = maybeProfile.get();
        final long now = System.currentTimeMillis();
        final List<WardrobeView.OwnedTitle> owned = new ArrayList<>();
        for (Ownership ownership : profile.activeOwnerships().values()) {
            resolveValid(profile, ownership.titleId(), now).ifPresent(title ->
                    owned.add(new WardrobeView.OwnedTitle(title, expiresOn(ownership),
                            isReferencedAnywhere(profile, title.id()))));
        }
        owned.sort(Comparator.comparingInt(view -> view.title().id()));
        return List.copyOf(owned);
    }

    /**
     * 开关栏位启用状态（主线程发起；当前状态取自缓存快照，无记录按默认启用）。
     *
     * @param player 玩家 UUID
     * @param slotNo 栏位号（1 起）
     * @return 操作结果 future（DB 线程完成，回 UI 须自行跳主线程）
     */
    public CompletableFuture<WardrobeService.WardrobeResult> toggleSlot(UUID player, int slotNo) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        final boolean currentlyEnabled = profileCache.get(player)
                .flatMap(profile -> profile.slots().stream()
                        .filter(slot -> slot.slotIndex() == slotNo)
                        .findFirst())
                .map(SlotConfig::enabled)
                .orElse(true);
        return wardrobeService.setSlotEnabled(player, slotNo, !currentlyEnabled);
    }

    /**
     * 应用栏位选择（主线程发起）：三种意图映射到装扮服务对应写入口。
     *
     * @param player    玩家 UUID
     * @param slotNo    栏位号（1 起）
     * @param selection 选择意图
     * @return 操作结果 future
     */
    public CompletableFuture<WardrobeService.WardrobeResult> applySlotSelection(
            UUID player, int slotNo, SlotSelection selection) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(selection, "选择意图不能为空");
        return switch (selection) {
            case SlotSelection.Fixed fixed -> wardrobeService.setSlotTitle(player, slotNo, fixed.titleId());
            case SlotSelection.Random random -> wardrobeService.setSlotRandom(player, slotNo, random.pool());
            case SlotSelection.Unequip ignored -> wardrobeService.setSlotTitle(player, slotNo, null);
        };
    }

    /** 组装单个栏位视图（无档案 / 无记录按默认：启用 + 固定 + 默认称号）。 */
    private WardrobeView.SlotView slotView(@Nullable PlayerProfileCache.PlayerProfile profile,
                                           int slotNo, long now) {
        final SlotConfig config = profile == null ? null : profile.slots().stream()
                .filter(slot -> slot.slotIndex() == slotNo)
                .findFirst()
                .orElse(null);
        if (config == null) {
            return new WardrobeView.SlotView(slotNo, true, SlotMode.FIXED, null, Set.of());
        }
        if (config.isRandom()) {
            final Set<Integer> rawPool = profile.randomPools().getOrDefault(slotNo, Set.of());
            final Set<Integer> validPool = new LinkedHashSet<>();
            for (Integer titleId : rawPool.stream().sorted().toList()) {
                resolveValid(profile, titleId, now).ifPresent(title -> validPool.add(title.id()));
            }
            return new WardrobeView.SlotView(slotNo, config.enabled(), SlotMode.RANDOM, null, validPool);
        }
        final Title fixedTitle = config.titleId() == null
                ? null
                : resolveValid(profile, config.titleId(), now).orElse(null);
        return new WardrobeView.SlotView(slotNo, config.enabled(), SlotMode.FIXED, fixedTitle, Set.of());
    }

    /** 解析有效称号引用：本人 ACTIVE 未到期所有权 + 目录未软删（与聊天路径同判据）。 */
    private Optional<Title> resolveValid(PlayerProfileCache.PlayerProfile profile, int titleId, long now) {
        final Ownership ownership = profile.activeOwnerships().get(titleId);
        if (ownership == null || !ownership.isActive() || ownership.isExpired(now)) {
            return Optional.empty();
        }
        return catalogCache.byId(titleId).filter(title -> !title.isDeleted());
    }

    /** 称号是否被任一栏位引用（固定引用或随机池成员；不看栏位启用状态；
     * 包级共享——{@code ShopQueryService} 复用同一判据渲染商店"已装扮"态）。 */
    static boolean isReferencedAnywhere(PlayerProfileCache.PlayerProfile profile, int titleId) {
        for (SlotConfig slot : profile.slots()) {
            if (Objects.equals(slot.titleId(), titleId)) {
                return true;
            }
        }
        for (Set<Integer> pool : profile.randomPools().values()) {
            if (pool.contains(titleId)) {
                return true;
            }
        }
        return false;
    }

    /** 到期时刻（UTC epoch 毫秒）→ 展示日期；永久为 null。 */
    private @Nullable LocalDate expiresOn(Ownership ownership) {
        return ownership.expiresAt() == null
                ? null
                : Instant.ofEpochMilli(ownership.expiresAt()).atZone(displayZone).toLocalDate();
    }
}
