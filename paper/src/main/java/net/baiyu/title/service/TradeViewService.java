package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.Page;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 交易视图门面：把交易服务的读路径结果组装成
 * 报价列表 / 详情 / 历史三族菜单的视图对象，并提供可交易称号清单。
 *
 * <p>线程约定：{@link #tradableTitles} 只读缓存快照（零 IO，任意线程）；
 * 其余查询在菜单异步 loadData 线程调用，内部对交易服务读 future
 * {@code join()}（实际 JDBC 仍只发生在 DbExecutor 读线程池）。</p>
 *
 * <p>裁决记录（可交易清单口径）：<b>不排除</b>已在其他 PENDING 报价中的
 * 标的——「交易期间不锁称号，成交事务内全量复核」，冲突由成交复核收敛。</p>
 */
public final class TradeViewService {

    /** 交易服务（读路径）。 */
    private final TradeService trades;

    /** 在线玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /** 插件配置提供器（总开关，随 reload 刷新）。 */
    private final Supplier<PluginConfig> configSupplier;

    /** 文本 / 物品格式化器（时间串、快照组件、图标解析）。 */
    private final TradeViewFormatter formatter;

    /** 时刻 → 展示文本的时区。 */
    private final ZoneId displayZone;

    /**
     * 构造交易视图门面（生产装配：展示时区取服务器默认时区）。
     *
     * @param trades         交易服务
     * @param profileCache   在线玩家档案缓存
     * @param catalogCache   称号目录缓存
     * @param configSupplier 插件配置提供器
     * @param rawLang        语言原文取词函数
     */
    public TradeViewService(TradeService trades, PlayerProfileCache profileCache,
                            TitleCatalogCache catalogCache, Supplier<PluginConfig> configSupplier,
                            Function<String, String> rawLang) {
        this(trades, profileCache, catalogCache, configSupplier, rawLang, ZoneId.systemDefault());
    }

    /**
     * 构造交易视图门面（显式时区重载，供单测注入固定时区）。
     *
     * @param trades         交易服务
     * @param profileCache   在线玩家档案缓存
     * @param catalogCache   称号目录缓存
     * @param configSupplier 插件配置提供器
     * @param rawLang        语言原文取词函数
     * @param displayZone    展示时区
     */
    public TradeViewService(TradeService trades, PlayerProfileCache profileCache,
                            TitleCatalogCache catalogCache, Supplier<PluginConfig> configSupplier,
                            Function<String, String> rawLang, ZoneId displayZone) {
        this.trades = Objects.requireNonNull(trades, "交易服务不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
        this.displayZone = Objects.requireNonNull(displayZone, "展示时区不能为空");
        this.formatter = new TradeViewFormatter(catalogCache, configSupplier, rawLang, displayZone);
    }

    /** @return 交易总开关是否开启（config trade.enabled；GUI 全入口据此隐藏 / 拒绝） */
    public boolean enabled() {
        return configSupplier.get().trade.enabled;
    }

    /**
     * 可交易称号视图（选择交易称号菜单 / 报价详情草稿态条目源）。
     *
     * @param ownershipId 所有权实例 id（构造 OfferItem 的键）
     * @param title       称号
     * @param expiresOn   到期日期（null = 永久）
     * @param equipped    是否被任一栏位引用（渲染"装扮中"态）
     */
    public record TradableTitle(long ownershipId, Title title,
                                @Nullable LocalDate expiresOn, boolean equipped) {

        /** 紧凑构造：称号必填。 */
        public TradableTitle {
            Objects.requireNonNull(title, "称号不能为空");
        }
    }

    /**
     * 玩家当前可交易的称号清单（任意线程；纯缓存读）：ACTIVE 未到期所有权 ×
     * 目录未软删 × tradable（BOND/CUSTOM/GROUP 由领域模型恒 false），
     * 按称号 id 升序。
     *
     * @param owner 持有者 UUID
     * @return 可交易称号视图列表（档案未挂载时为空列表）
     */
    public List<TradableTitle> tradableTitles(UUID owner) {
        Objects.requireNonNull(owner, "持有者 UUID 不能为空");
        final Optional<PlayerProfileCache.PlayerProfile> maybeProfile = profileCache.get(owner);
        if (maybeProfile.isEmpty()) {
            return List.of();
        }
        final PlayerProfileCache.PlayerProfile profile = maybeProfile.get();
        final long now = System.currentTimeMillis();
        final List<TradableTitle> result = new ArrayList<>();
        for (Ownership ownership : profile.activeOwnerships().values()) {
            if (!ownership.isActive() || ownership.isExpired(now)) {
                continue;
            }
            catalogCache.byId(ownership.titleId())
                    .filter(title -> !title.isDeleted() && title.tradable())
                    .ifPresent(title -> result.add(new TradableTitle(ownership.id(), title,
                            expiresOn(ownership),
                            WardrobeViewService.isReferencedAnywhere(profile, title.id()))));
        }
        result.sort(Comparator.comparingInt(view -> view.title().id()));
        return List.copyOf(result);
    }

    /**
     * 与玩家相关的待处理报价（报价列表菜单条目源；异步 loadData 线程调用）。
     * <p>已过过期时刻、尚待周期扫描收敛的报价不再列出。</p>
     *
     * @param viewer 观察者 UUID
     * @return 摘要视图列表
     */
    public List<TradeOfferView> pendingOffers(UUID viewer) {
        Objects.requireNonNull(viewer, "观察者 UUID 不能为空");
        final long now = System.currentTimeMillis();
        return trades.pendingFor(viewer).join().stream()
                .filter(trade -> !trade.isExpiredAt(now))
                .map(trade -> summarizeFor(viewer, trade, now))
                .toList();
    }

    /**
     * 个人交易历史分页（异步 loadData 线程调用）。
     *
     * @param viewer 观察者 UUID
     * @param page   页码（1 起）
     * @param size   每页条数
     * @return 摘要视图分页
     */
    public Page<TradeOfferView> personalHistory(UUID viewer, int page, int size) {
        Objects.requireNonNull(viewer, "观察者 UUID 不能为空");
        final long now = System.currentTimeMillis();
        final Page<Trade> raw = trades.personalHistory(viewer, page, size).join();
        return new Page<>(raw.items().stream().map(trade -> summarizeFor(viewer, trade, now)).toList(),
                raw.page(), raw.size(), raw.totalItems());
    }

    /**
     * 全服交易历史分页（管理端；异步 loadData 线程调用）。
     *
     * @param filter   状态过滤（可空 = 全部）
     * @param nameLike 玩家名模糊串（可空 = 不过滤）
     * @param page     页码（1 起）
     * @param size     每页条数
     * @return 摘要视图分页（管理视角：EXPIRED 独立呈现）
     */
    public Page<TradeOfferView> globalHistory(@Nullable TradeStatus filter, @Nullable String nameLike,
                                              int page, int size) {
        final long now = System.currentTimeMillis();
        final Page<Trade> raw = trades.globalHistory(filter, nameLike, page, size).join();
        return new Page<>(raw.items().stream().map(trade -> summarizeAdmin(trade, now)).toList(),
                raw.page(), raw.size(), raw.totalItems());
    }

    /**
     * 报价详情视图（异步 loadData 线程调用）。
     *
     * @param tradeId 交易 id
     * @param viewer  观察者 UUID
     * @return 详情视图；交易不存在时为空
     */
    public Optional<TradeDetailView> detail(long tradeId, UUID viewer) {
        Objects.requireNonNull(viewer, "观察者 UUID 不能为空");
        final long now = System.currentTimeMillis();
        return trades.findWithItems(tradeId).join()
                .map(trade -> assembleDetail(trade, viewer, now));
    }

    /**
     * 状态文案语言键（玩家视角 EXPIRED 归入"已拒绝"——裁决 §十一 10；
     * 管理视角独立呈现）。
     *
     * @param status 有效状态
     * @param admin  是否管理视角
     * @return 语言键（trade.status.*）
     */
    public static String statusKey(TradeStatus status, boolean admin) {
        Objects.requireNonNull(status, "交易状态不能为空");
        if (status == TradeStatus.EXPIRED && !admin) {
            return "trade.status.rejected";
        }
        return "trade.status." + status.name().toLowerCase(Locale.ROOT);
    }

    /** 有效状态：PENDING 且已过过期时刻按 EXPIRED 呈现（不等周期扫描）。 */
    static TradeStatus effectiveStatus(Trade trade, long now) {
        return trade.status() == TradeStatus.PENDING && trade.isExpiredAt(now)
                ? TradeStatus.EXPIRED : trade.status();
    }

    /** 观察者视角摘要（对手方 / 方向 / 状态键按视角折算）。 */
    TradeOfferView summarizeFor(UUID viewer, Trade trade, long now) {
        final boolean incoming = viewer.equals(trade.targetUuid());
        final TradeStatus effective = effectiveStatus(trade, now);
        return new TradeOfferView(trade.id(), effective, statusKey(effective, false), incoming,
                trade.initiatorName(), trade.targetName(),
                incoming ? trade.initiatorName() : trade.targetName(),
                trade.itemsOf(TradeSide.INITIATOR).size(), trade.itemsOf(TradeSide.TARGET).size(),
                formatter.timeText(trade.createdAt()), formatter.timeText(trade.expiresAt()));
    }

    /** 管理视角摘要（无对手方语义；状态键管理视角）。 */
    TradeOfferView summarizeAdmin(Trade trade, long now) {
        final TradeStatus effective = effectiveStatus(trade, now);
        return new TradeOfferView(trade.id(), effective, statusKey(effective, true), false,
                trade.initiatorName(), trade.targetName(), trade.targetName(),
                trade.itemsOf(TradeSide.INITIATOR).size(), trade.itemsOf(TradeSide.TARGET).size(),
                formatter.timeText(trade.createdAt()), formatter.timeText(trade.expiresAt()));
    }

    /** 组装详情视图：角色判定 + 双侧标的视图（名称快照 + 目录现状图标）。 */
    TradeDetailView assembleDetail(Trade trade, UUID viewer, long now) {
        final TradeStatus effective = effectiveStatus(trade, now);
        final TradeDetailView.Role role =
                effective == TradeStatus.PENDING && viewer.equals(trade.targetUuid())
                        ? TradeDetailView.Role.RECEIVER : TradeDetailView.Role.VIEWER;
        return new TradeDetailView(trade.id(), effective, statusKey(effective, false),
                trade.initiatorUuid(), trade.initiatorName(),
                trade.targetUuid(), trade.targetName(), role,
                formatter.itemViews(trade.itemsOf(TradeSide.INITIATOR)),
                formatter.itemViews(trade.itemsOf(TradeSide.TARGET)),
                formatter.timeText(trade.createdAt()), formatter.timeText(trade.expiresAt()),
                formatter.resolvedByText(trade));
    }

    /** 到期时刻（UTC epoch 毫秒）→ 展示日期；永久为 null。 */
    private @Nullable LocalDate expiresOn(Ownership ownership) {
        return ownership.expiresAt() == null
                ? null
                : Instant.ofEpochMilli(ownership.expiresAt()).atZone(displayZone).toLocalDate();
    }
}
