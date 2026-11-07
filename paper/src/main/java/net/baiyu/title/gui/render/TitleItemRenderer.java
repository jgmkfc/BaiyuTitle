package net.baiyu.title.gui.render;

import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.text.ColorNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 动态称号物品渲染门面：四上下文
 * （shopItem / warehouseItem / adminItem / tradeItem）中已实现
 * {@link #shopItem}、{@link #warehouseItem} 与称号选择菜单条目
 * {@link #selectionItem}，其余随后续里程碑扩展。
 *
 * <p>产出为纯值对象 {@link RenderedItem}（材质 + 名称组件 + lore 组件行 + glint），
 * ItemStack 构造留给 {@code gui.core.GuiItemFactory}（风险 1 单点）——
 * 本类因此零 Bukkit 运行时依赖（Material 仅枚举常量），可直接单测。</p>
 *
 * <p>名称 / lore 全部先 {@link ColorNormalizer} 归一化再经 MiniMessage 直接构建
 * Component（与聊天路径 {@code chat.ChatDisplayService#parse} 同方案，
 * 勿用 ColorParser——其 build() 触碰 Bukkit 且做占位符探测）。
 * 名称 = 括号前段 + 称号名 + 括号后段三段分别归一化拼接（不 trim 空格）。</p>
 */
public final class TitleItemRenderer {

    /** MiniMessage 解析器（无状态单例，线程安全）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** 图标材质解析失败时的兜底材质（与 TitleBuilder 默认 {@code minecraft:name_tag} 一致）。 */
    private static final Material FALLBACK_MATERIAL = Material.NAME_TAG;

    /** 分区 lore 组装器。 */
    private final LoreComposer loreComposer;

    /** 商店上下文 lore 区段组装器。 */
    private final ShopLoreComposer shopLoreComposer;

    /** 管理上下文 lore 区段组装器。 */
    private final AdminLoreComposer adminLoreComposer;

    /** 插件配置提供器（每次渲染取最新括号格式，随 reload 刷新）。 */
    private final Supplier<PluginConfig> configSupplier;

    /**
     * 构造称号物品渲染门面。
     *
     * @param loreComposer      分区 lore 组装器
     * @param shopLoreComposer  商店上下文 lore 区段组装器
     * @param adminLoreComposer 管理上下文 lore 区段组装器
     * @param configSupplier    插件配置提供器（生产传 {@code configHandler::getConfig}；
     *                          纯函数单测走显式 {@link NameBrackets} 重载，不触碰它）
     */
    public TitleItemRenderer(LoreComposer loreComposer, ShopLoreComposer shopLoreComposer,
                             AdminLoreComposer adminLoreComposer,
                             Supplier<PluginConfig> configSupplier) {
        this.loreComposer = Objects.requireNonNull(loreComposer, "lore 组装器不能为空");
        this.shopLoreComposer = Objects.requireNonNull(shopLoreComposer, "商店 lore 组装器不能为空");
        this.adminLoreComposer = Objects.requireNonNull(adminLoreComposer, "管理 lore 组装器不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
    }

    /**
     * 已持有称号视图（仓库 / 称号选择上下文的渲染入参；
     * 由视图门面服务在异步 loadData 阶段预计算，本类不做业务判断）。
     *
     * @param title     称号（名称已归一化 MiniMessage 存储）
     * @param expiresOn 到期日期（null = 永久；epoch 毫秒转日期由视图服务负责）
     * @param equipped  是否装扮中
     */
    public record OwnedTitleView(Title title, @Nullable LocalDate expiresOn, boolean equipped) {

        /** 紧凑构造：称号必填。 */
        public OwnedTitleView {
            Objects.requireNonNull(title, "称号不能为空");
        }
    }

    /**
     * 渲染结果值对象：菜单侧转交 {@code GuiItemFactory#item(Material, Component,
     * List, boolean, int, GuiAction)} 构造实际物品。
     *
     * @param material 物品材质（称号图标）
     * @param name     物品名组件
     * @param lore     lore 组件行
     * @param glint    是否附魔光泽（选中态标记）
     */
    public record RenderedItem(Material material, Component name, List<Component> lore, boolean glint) {

        /** 紧凑构造：判空与 lore 防御拷贝。 */
        public RenderedItem {
            Objects.requireNonNull(material, "物品材质不能为空");
            Objects.requireNonNull(name, "物品名不能为空");
            lore = List.copyOf(Objects.requireNonNull(lore, "lore 行不能为空（可传空列表）"));
        }
    }

    /**
     * 名称括号格式快照（config {@code title.prefix-brackets} / {@code suffix-brackets}）。
     *
     * @param prefix 称号前括号段（含空格，不 trim）
     * @param suffix 称号后括号段（含空格，不 trim）
     */
    public record NameBrackets(String prefix, String suffix) {

        /** 紧凑构造：两段必填。 */
        public NameBrackets {
            Objects.requireNonNull(prefix, "前括号段不能为空");
            Objects.requireNonNull(suffix, "后括号段不能为空");
        }

        /**
         * 从插件配置提取括号格式快照。
         *
         * @param config 插件配置
         * @return 括号格式快照
         */
        public static NameBrackets of(PluginConfig config) {
            return new NameBrackets(config.title.prefixBrackets, config.title.suffixBrackets);
        }
    }

    /**
     * 仓库菜单条目：状态区 + 简介区，
     * <b>去掉最后两行</b>操作提示（含前置空行），无 glint。
     *
     * @param view 已持有称号视图
     * @return 渲染结果
     */
    public RenderedItem warehouseItem(OwnedTitleView view) {
        return warehouseItem(view, NameBrackets.of(configSupplier.get()));
    }

    /**
     * 纯函数：仓库菜单条目（括号格式显式注入，供单测）。
     *
     * @param view     已持有称号视图
     * @param brackets 名称括号格式快照
     * @return 渲染结果
     */
    public RenderedItem warehouseItem(OwnedTitleView view, NameBrackets brackets) {
        List<Component> lore = loreComposer.compose(
                loreComposer.statusSection(view.expiresOn(), view.equipped()),
                loreComposer.descriptionSection(view.title().description()));
        return new RenderedItem(resolveMaterial(view.title().iconMaterial()),
                displayName(view.title(), brackets), lore, false);
    }

    /**
     * 称号选择菜单条目：状态区 + 简介区 + 操作行
     * （已装扮 → 点击卸下，未装扮 → 点击装扮）；glint = 暂选态（表现层设计 §4：
     * 普通模式单选暂选附魔、随机模式多选池附魔，由菜单侧按会话状态传入）。
     *
     * @param view     已持有称号视图
     * @param selected 是否处于暂选 / 随机池选中态（附魔光泽）
     * @return 渲染结果
     */
    public RenderedItem selectionItem(OwnedTitleView view, boolean selected) {
        return selectionItem(view, selected, NameBrackets.of(configSupplier.get()));
    }

    /**
     * 纯函数：称号选择菜单条目（括号格式显式注入，供单测）。
     *
     * @param view     已持有称号视图
     * @param selected 是否处于选中态
     * @param brackets 名称括号格式快照
     * @return 渲染结果
     */
    public RenderedItem selectionItem(OwnedTitleView view, boolean selected, NameBrackets brackets) {
        LoreComposer.ActionType action = view.equipped()
                ? LoreComposer.ActionType.UNEQUIP
                : LoreComposer.ActionType.EQUIP;
        List<Component> lore = loreComposer.compose(
                loreComposer.statusSection(view.expiresOn(), view.equipped()),
                loreComposer.descriptionSection(view.title().description()),
                loreComposer.actionLine(action));
        return new RenderedItem(resolveMaterial(view.title().iconMaterial()),
                displayName(view.title(), brackets), lore, selected);
    }

    /**
     * 商店菜单条目：购买区 + 个人状态区 + 限定区 +
     * 稀有度行与简介区（相邻无空行，并作一组）+ 底行操作提示
     * （点击购买 / 无法购买 / 跳转仓库），恒无 glint。
     *
     * @param view 商店条目视图（{@code ShopQueryService} 预计算）
     * @return 渲染结果
     */
    public RenderedItem shopItem(ShopEntryView view) {
        return shopItem(view, NameBrackets.of(configSupplier.get()));
    }

    /**
     * 纯函数：商店菜单条目（括号格式显式注入，供单测）。
     *
     * @param view     商店条目视图
     * @param brackets 名称括号格式快照
     * @return 渲染结果
     */
    public RenderedItem shopItem(ShopEntryView view, NameBrackets brackets) {
        Title title = view.title();
        List<Component> rarityAndDescription = new ArrayList<>(
                loreComposer.raritySection(title.rarity()));
        rarityAndDescription.addAll(loreComposer.descriptionSection(title.description()));
        List<Component> lore = loreComposer.compose(
                shopLoreComposer.purchaseSection(view, brackets),
                shopLoreComposer.personalSection(view, brackets),
                shopLoreComposer.limitSection(title),
                rarityAndDescription,
                shopLoreComposer.actionLine(view.eligibility()));
        return new RenderedItem(resolveMaterial(title.iconMaterial()),
                displayName(title, brackets), lore, false);
    }

    /**
     * 管理菜单条目：基本信息区 + 购买信息区 +
     * 简介区 + 编辑操作行，恒无 glint；含隐藏称号。
     *
     * @param title     称号
     * @param itemNames 条件物品 Base64 → 本地化物品名组件（主线程预解析）
     * @return 渲染结果
     */
    public RenderedItem adminItem(Title title, Map<String, Component> itemNames) {
        return adminItem(title, itemNames, NameBrackets.of(configSupplier.get()));
    }

    /**
     * 纯函数：管理菜单条目（括号格式显式注入，供单测）。
     *
     * @param title     称号
     * @param itemNames 条件物品 Base64 → 本地化物品名组件
     * @param brackets  名称括号格式快照
     * @return 渲染结果
     */
    public RenderedItem adminItem(Title title, Map<String, Component> itemNames, NameBrackets brackets) {
        return new RenderedItem(resolveMaterial(title.iconMaterial()),
                displayName(title, brackets), adminLoreComposer.adminLore(title, itemNames), false);
    }

    /**
     * 带括号的称号名组件（装扮菜单"当前佩戴"预览等非物品场景复用）。
     *
     * @param title 称号
     * @return 括号前段 + 称号名 + 括号后段拼接的组件（不 trim 空格）
     */
    public Component nameWithBrackets(Title title) {
        return displayName(Objects.requireNonNull(title, "称号不能为空"),
                NameBrackets.of(configSupplier.get()));
    }

    /**
     * 默认称号完整组件（config {@code title.default-title}，自带括号不再套段——
     * 与聊天路径 {@code ChatDisplayService#renderSlot} 的降级语义一致）。
     *
     * @return 默认称号组件
     */
    public Component defaultTitleName() {
        return parse(configSupplier.get().title.defaultTitle);
    }

    /**
     * 名称组件：括号前段 + 称号名 + 括号后段三段分别归一化构建后拼接
     * （不 trim 空格——与聊天渲染同一条 §1.5.2 空格回归红线）。
     */
    private static Component displayName(Title title, NameBrackets brackets) {
        return Component.text()
                .append(parse(brackets.prefix()))
                .append(parse(title.name()))
                .append(parse(brackets.suffix()))
                .build();
    }

    /**
     * 解析图标材质：支持 {@code minecraft:name_tag} 命名空间键与裸枚举名，
     * 未知材质兜底 NAME_TAG（包级可见供单测）。
     */
    static Material resolveMaterial(String iconMaterial) {
        Material material = Material.matchMaterial(iconMaterial);
        return material != null ? material : FALLBACK_MATERIAL;
    }

    /** 归一化并解析为组件（空白与空串原样保留，不 trim）。 */
    private static Component parse(String raw) {
        return MINI_MESSAGE.deserialize(ColorNormalizer.normalize(raw));
    }
}
