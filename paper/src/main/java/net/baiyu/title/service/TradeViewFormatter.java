package net.baiyu.title.service;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.text.ColorNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 交易视图文本 / 物品格式化器（{@link TradeViewService} 的拆分件）：
 * 时间展示串、标的名称快照组件与图标解析、处理人占位文案。
 *
 * <p>名称取<b>下单时快照</b>（归一化 MiniMessage，套 config 括号段）；
 * 图标按目录现状解析，称号已软删或材质非法时兜底 NAME_TAG。
 * 纯函数式（只读缓存快照与配置），任意线程可调。</p>
 */
final class TradeViewFormatter {

    /** MiniMessage 解析器（无状态单例，线程安全）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** 标的图标解析失败 / 称号已软删时的兜底材质。 */
    private static final Material FALLBACK_ICON = Material.NAME_TAG;

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /** 插件配置提供器（名称括号段，随 reload 刷新）。 */
    private final Supplier<PluginConfig> configSupplier;

    /** 语言原文取词（时间模板 / 占位文案）。 */
    private final Function<String, String> rawLang;

    /** 时刻 → 展示文本的时区。 */
    private final ZoneId displayZone;

    /**
     * 构造格式化器。
     *
     * @param catalogCache   称号目录缓存
     * @param configSupplier 插件配置提供器
     * @param rawLang        语言原文取词函数
     * @param displayZone    展示时区
     */
    TradeViewFormatter(TitleCatalogCache catalogCache, Supplier<PluginConfig> configSupplier,
                       Function<String, String> rawLang, ZoneId displayZone) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
        this.rawLang = Objects.requireNonNull(rawLang, "语言原文取词函数不能为空");
        this.displayZone = Objects.requireNonNull(displayZone, "展示时区不能为空");
    }

    /**
     * 时刻（UTC epoch 毫秒）→ 展示时区日期时间文案（format.date-time 值级插值）。
     *
     * @param epochMillis 时刻
     * @return 展示串
     */
    String timeText(long epochMillis) {
        final LocalDateTime time = Instant.ofEpochMilli(epochMillis)
                .atZone(displayZone).toLocalDateTime();
        return rawLang.apply("format.date-time")
                .replace("<year>", Integer.toString(time.getYear()))
                .replace("<month>", Integer.toString(time.getMonthValue()))
                .replace("<day>", Integer.toString(time.getDayOfMonth()))
                .replace("<hour>", String.format("%02d", time.getHour()))
                .replace("<minute>", String.format("%02d", time.getMinute()));
    }

    /**
     * 标的条目视图：快照名套括号段构组件；图标按目录现状解析，缺失兜底。
     *
     * @param items 交易标的列表
     * @return 条目视图列表
     */
    List<TradeDetailView.ItemView> itemViews(List<TradeItem> items) {
        final PluginConfig.TitleSettings titleSettings = configSupplier.get().title;
        final List<TradeDetailView.ItemView> views = new ArrayList<>(items.size());
        for (TradeItem item : items) {
            final Component name = Component.text()
                    .append(parse(titleSettings.prefixBrackets))
                    .append(parse(item.titleNameSnapshot()))
                    .append(parse(titleSettings.suffixBrackets))
                    .build();
            final Material icon = catalogCache.byId(item.titleId())
                    .filter(title -> !title.isDeleted())
                    .map(title -> Material.matchMaterial(title.iconMaterial()))
                    .orElse(FALLBACK_ICON);
            views.add(new TradeDetailView.ItemView(item.ownershipId(), item.titleId(), name, icon));
        }
        return views;
    }

    /**
     * 处理人展示串（未解决时取占位文案）。
     *
     * @param trade 交易单
     * @return 展示串
     */
    String resolvedByText(Trade trade) {
        return trade.resolvedBy() != null ? trade.resolvedBy()
                : rawLang.apply("menu.trade-detail.status.none");
    }

    /** 归一化并解析为组件（与渲染门面同方案：ColorNormalizer + MiniMessage）。 */
    private static Component parse(String raw) {
        return MINI_MESSAGE.deserialize(ColorNormalizer.normalize(raw));
    }
}
