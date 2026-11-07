package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.gui.shop.ShopDetailView;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.gui.shop.ShopFilter;
import net.baiyu.title.gui.shop.ShopSort;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.EligibilityEngine;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 商店查询门面：把目录 / 档案缓存快照 + 玩家上下文
 * 组装为商店菜单的视图对象 {@link ShopEntryView}，购买资格由
 * {@link EligibilityEngine} 预裁决，筛选 / 排序委托 {@link ShopFilter} /
 * {@link ShopSort} 纯函数——菜单只渲染与转发点击，零业务判断。
 *
 * <p>线程约定（与 {@code WardrobeViewService} 同型）：
 * {@link #collectContext} 触碰权限系统 / 背包 / Vault，<b>仅主线程</b>
 * （菜单构造时同步收集后带入异步链）；{@link #queryShop} / {@link #detail}
 * 只读 volatile 不可变缓存快照与传入上下文，可在异步 loadData 线程调用
 * （零 Bukkit、零 DB IO）。</p>
 *
 * <p>裁决记录：①CUSTOM 称号即使可见也不进商店列表（自定义称号走独立入口，
 * 商店类型枚举无「自定义」——业务 §1.3.2.4.1）；②已拥有判定 = 本人 ACTIVE
 * 且未到期所有权（与装扮 / 聊天路径同判据）；③GROUP 组名待组模块
 * 接入组名解析，当前恒 null（所属组行省略）。</p>
 */
public final class ShopQueryService {

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /** 在线玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 购买资格判定引擎（纯函数）。 */
    private final EligibilityEngine eligibilityEngine;

    /** 经济门面（可用性与余额，仅 {@link #collectContext} 触碰）。 */
    private final EconomyGateway economyGateway;

    /** 物品名解析器（生产为 {@code itemNameRegistry::displayComponent}）。 */
    private final Function<Material, Component> materialNames;

    /** 稀有度顺序表提供器（生产为 {@code () -> config.rarities}，随 reload 刷新）。 */
    private final Supplier<List<String>> raritiesSupplier;

    /** 当天日期提供器（生产为 {@code LocalDate::now}；单测注入固定日期）。 */
    private final Supplier<LocalDate> todaySupplier;

    /**
     * 构造商店查询门面。
     *
     * @param catalogCache      称号目录缓存
     * @param profileCache      在线玩家档案缓存
     * @param eligibilityEngine 购买资格判定引擎
     * @param economyGateway    经济门面
     * @param materialNames     物品名解析器
     * @param raritiesSupplier  稀有度顺序表提供器
     * @param todaySupplier     当天日期提供器
     */
    public ShopQueryService(TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                            EligibilityEngine eligibilityEngine, EconomyGateway economyGateway,
                            Function<Material, Component> materialNames,
                            Supplier<List<String>> raritiesSupplier,
                            Supplier<LocalDate> todaySupplier) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.eligibilityEngine = Objects.requireNonNull(eligibilityEngine, "资格判定引擎不能为空");
        this.economyGateway = Objects.requireNonNull(economyGateway, "经济门面不能为空");
        this.materialNames = Objects.requireNonNull(materialNames, "物品名解析器不能为空");
        this.raritiesSupplier = Objects.requireNonNull(raritiesSupplier, "稀有度顺序表提供器不能为空");
        this.todaySupplier = Objects.requireNonNull(todaySupplier, "当天日期提供器不能为空");
    }

    /**
     * 玩家商店上下文（一次主线程收集、供整页查询复用的纯数据）。
     *
     * @param base      基础购买快照（{@code alreadyOwned} 恒 false，逐称号派生时覆写）
     * @param itemNames 条件物品 Base64 → 本地化物品名组件（键与条件序列化产物逐字节一致）
     */
    public record ShopContext(PurchaseSnapshot base, Map<String, Component> itemNames) {

        /** 紧凑构造：判空 + 防御性拷贝。 */
        public ShopContext {
            Objects.requireNonNull(base, "基础购买快照不能为空");
            itemNames = itemNames == null ? Map.of() : Map.copyOf(itemNames);
        }
    }

    /**
     * 收集玩家商店上下文（<b>仅主线程</b>：权限布尔、背包物品计数、Vault 余额；
     * 称号币余额取档案缓存）。只收集商店可见称号实际引用的权限节点与条件物品，
     * 避免全量扫描。
     *
     * @param player 在线玩家
     * @return 商店上下文
     */
    public ShopContext collectContext(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        boolean economyAvailable = economyGateway.isAvailable();
        BigDecimal vaultBalance = economyAvailable
                ? economyGateway.balance(player.getUniqueId())
                : BigDecimal.ZERO;
        long coinBalance = profileCache.get(player.getUniqueId())
                .map(PlayerProfileCache.PlayerProfile::coinBalance)
                .orElse(0L);
        Set<String> heldPermissions = new HashSet<>();
        Map<String, Integer> heldItemCounts = new HashMap<>();
        Map<String, Component> itemNames = new HashMap<>();
        for (Title title : catalogCache.visibleOnly()) {
            for (PurchaseCondition condition : title.conditions()) {
                switch (condition) {
                    case PermissionCondition permission -> {
                        if (player.hasPermission(permission.node())) {
                            heldPermissions.add(permission.node());
                        }
                    }
                    case ItemCondition item -> {
                        if (!heldItemCounts.containsKey(item.itemBase64())) {
                            probeItem(player, item.itemBase64(), heldItemCounts, itemNames);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        PurchaseSnapshot base = new PurchaseSnapshot(todaySupplier.get(), economyAvailable,
                vaultBalance, coinBalance, heldPermissions, heldItemCounts, false);
        return new ShopContext(base, itemNames);
    }

    /**
     * 查询商店条目列表（任意线程；上下文由主线程经 {@link #collectContext} 预收集）。
     *
     * @param player  玩家 UUID
     * @param context 商店上下文
     * @param filter  筛选状态
     * @param sort    排序状态
     * @return 通过筛选并按排序键排好的条目视图列表（不可变）
     */
    public List<ShopEntryView> queryShop(UUID player, ShopContext context,
                                         ShopFilter filter, ShopSort sort) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(context, "商店上下文不能为空");
        Objects.requireNonNull(filter, "筛选状态不能为空");
        Objects.requireNonNull(sort, "排序状态不能为空");
        PlayerProfileCache.PlayerProfile profile = profileCache.get(player).orElse(null);
        long now = System.currentTimeMillis();
        List<ShopEntryView> entries = new ArrayList<>();
        for (Title title : catalogCache.visibleOnly()) {
            if (title.category() == TitleCategory.CUSTOM) {
                continue;
            }
            ShopEntryView entry = entry(title, context, profile, now);
            if (filter.matches(entry)) {
                entries.add(entry);
            }
        }
        entries.sort(sort.comparator(raritiesSupplier.get()));
        return List.copyOf(entries);
    }

    /**
     * 查询单称号详情视图（任意线程；右键详情入口，含隐藏称号——
     * 详情菜单由商店条目进入，此处只挡软删）。
     *
     * @param player  玩家 UUID
     * @param context 商店上下文
     * @param titleId 称号 id
     * @return 详情视图；称号不存在或已软删时为空
     */
    public Optional<ShopDetailView> detail(UUID player, ShopContext context, int titleId) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(context, "商店上下文不能为空");
        PlayerProfileCache.PlayerProfile profile = profileCache.get(player).orElse(null);
        long now = System.currentTimeMillis();
        return catalogCache.byId(titleId)
                .filter(title -> !title.isDeleted())
                .map(title -> new ShopDetailView(entry(title, context, profile, now)));
    }

    /** 组装单条目视图：派生已拥有快照 → 资格判定 → 装扮态 / 物品名子集 / 羁绊需求。 */
    private ShopEntryView entry(Title title, ShopContext context,
                                @Nullable PlayerProfileCache.PlayerProfile profile, long now) {
        boolean owned = profile != null && isOwnedActive(profile, title.id(), now);
        PurchaseSnapshot snapshot = withOwnership(context.base(), owned);
        Eligibility eligibility = eligibilityEngine.evaluate(title, snapshot);
        boolean equipped = owned && WardrobeViewService.isReferencedAnywhere(profile, title.id());
        return new ShopEntryView(title, eligibility, equipped, snapshot,
                itemNamesFor(title, context), null, bondRequirements(title, profile, now));
    }

    /** 派生逐称号快照：仅覆写 alreadyOwned，其余字段共享基础快照。 */
    private static PurchaseSnapshot withOwnership(PurchaseSnapshot base, boolean owned) {
        if (base.alreadyOwned() == owned) {
            return base;
        }
        return new PurchaseSnapshot(base.today(), base.economyAvailable(), base.vaultBalance(),
                base.coinBalance(), base.heldPermissions(), base.heldItemCounts(), owned);
    }

    /** 已拥有判定：本人 ACTIVE 且未到期所有权（与装扮 / 聊天路径同判据）。 */
    private static boolean isOwnedActive(PlayerProfileCache.PlayerProfile profile,
                                         int titleId, long now) {
        Ownership ownership = profile.activeOwnerships().get(titleId);
        return ownership != null && ownership.isActive() && !ownership.isExpired(now);
    }

    /** 条目的条件物品名子集（只携带本称号条件涉及的映射）。 */
    private static Map<String, Component> itemNamesFor(Title title, ShopContext context) {
        Map<String, Component> subset = new LinkedHashMap<>();
        for (PurchaseCondition condition : title.conditions()) {
            if (condition instanceof ItemCondition item) {
                Component name = context.itemNames().get(item.itemBase64());
                if (name != null) {
                    subset.put(item.itemBase64(), name);
                }
            }
        }
        return subset;
    }

    /** 羁绊需求视图：按需求称号 id 升序，名称取目录缓存（含软删行取名兜底）。 */
    private List<ShopEntryView.BondRequirementView> bondRequirements(
            Title title, @Nullable PlayerProfileCache.PlayerProfile profile, long now) {
        if (title.category() != TitleCategory.BOND || title.bondRequirements().isEmpty()) {
            return List.of();
        }
        List<ShopEntryView.BondRequirementView> requirements = new ArrayList<>();
        for (int requiredId : title.bondRequirements().stream().sorted().toList()) {
            String name = catalogCache.byId(requiredId).map(Title::name).orElse("#" + requiredId);
            boolean owned = profile != null && isOwnedActive(profile, requiredId, now);
            requirements.add(new ShopEntryView.BondRequirementView(name, owned));
        }
        return List.copyOf(requirements);
    }

    /** 反序列化条件物品并统计背包持有数 + 解析物品名（失败按 0 / ? 防御降级）。 */
    private void probeItem(Player player, String itemBase64,
                           Map<String, Integer> heldItemCounts, Map<String, Component> itemNames) {
        try {
            ItemStack probe = ItemStack.deserializeBytes(Base64.getDecoder().decode(itemBase64));
            int count = 0;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && stack.isSimilar(probe)) {
                    count += stack.getAmount();
                }
            }
            heldItemCounts.put(itemBase64, count);
            itemNames.put(itemBase64, materialNames.apply(probe.getType()));
        } catch (RuntimeException e) {
            heldItemCounts.put(itemBase64, 0);
            itemNames.put(itemBase64, Component.text("?"));
        }
    }
}
