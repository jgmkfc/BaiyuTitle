package net.baiyu.title.gui.render;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理上下文 lore 区段组装器。
 *
 * <p>四个区段组：基本信息（表头 + ID 行 + 公开状态行）、购买信息
 * （类型行 + 逐条件价格行，无独立表头——模板字符画中与基本信息仅空行相隔）、
 * 简介区（复用 {@link LoreComposer#descriptionSection}）、编辑操作行。
 * 类型行文案与价格行模板键与商店上下文共用（{@link ShopLoreComposer#typesText}
 * / {@code gui-item.line.price-*}），保证两处展示一致。</p>
 *
 * <p>条件物品名组件经 {@code itemNames} 映射传入（键 = 条件 Base64 串）——
 * 反序列化触碰 Bukkit，由菜单在主线程解析后注入；本类保持纯函数可单测。</p>
 */
public final class AdminLoreComposer {

    /** lore 模板取词接缝（生产为 {@code lang::raw}）。 */
    private final LoreTemplateSource templates;

    /** 通用分区组装器（简介区与区段拼装复用）。 */
    private final LoreComposer loreComposer;

    /** 商店区段组装器（类型行文案复用）。 */
    private final ShopLoreComposer shopLoreComposer;

    /**
     * 构造管理 lore 区段组装器。
     *
     * @param templates        lore 模板取词接缝
     * @param loreComposer     通用分区组装器
     * @param shopLoreComposer 商店区段组装器
     */
    public AdminLoreComposer(LoreTemplateSource templates, LoreComposer loreComposer,
                             ShopLoreComposer shopLoreComposer) {
        this.templates = Objects.requireNonNull(templates, "lore 模板取词接缝不能为空");
        this.loreComposer = Objects.requireNonNull(loreComposer, "通用分区组装器不能为空");
        this.shopLoreComposer = Objects.requireNonNull(shopLoreComposer, "商店区段组装器不能为空");
    }

    /**
     * 组装管理菜单条目完整 lore。
     *
     * @param title     称号
     * @param itemNames 条件物品 Base64 → 本地化物品名组件（缺失兜底 {@code ?}）
     * @return 完整 lore 组件行
     */
    public List<Component> adminLore(Title title, Map<String, Component> itemNames) {
        Objects.requireNonNull(title, "称号不能为空");
        Objects.requireNonNull(itemNames, "物品名映射不能为空（可传空映射）");
        return loreComposer.compose(
                basicSection(title),
                purchaseSection(title, itemNames),
                loreComposer.descriptionSection(title.description()),
                List.of(line("gui-item.admin.action.edit")));
    }

    /** 基本信息区：表头 + ID 行 + 公开状态行。 */
    private List<Component> basicSection(Title title) {
        return List.of(
                line("gui-item.admin.section.basic"),
                line("gui-item.admin.line.id", LoreLines.value("id", Integer.toString(title.id()))),
                line(title.visible() ? "gui-item.admin.line.visible" : "gui-item.admin.line.hidden"));
    }

    /** 购买信息区：类型行 + 逐条件价格行（无独立表头——§1.3.2.4.3 字符画）。 */
    private List<Component> purchaseSection(Title title, Map<String, Component> itemNames) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("gui-item.admin.line.type",
                LoreLines.value("types", shopLoreComposer.typesText(title))));
        for (PurchaseCondition condition : title.conditions()) {
            lines.add(switch (condition) {
                case VaultCondition vault -> line("gui-item.line.price-vault",
                        LoreLines.value("amount", plainNumber(vault.amount())));
                case CoinCondition coin -> line("gui-item.line.price-coin",
                        LoreLines.value("amount", Long.toString(coin.amount())));
                case ItemCondition item -> line("gui-item.line.price-item",
                        LoreLines.component("item", itemNames.getOrDefault(item.itemBase64(), Component.text("?"))),
                        LoreLines.value("amount", Integer.toString(item.amount())));
                case PermissionCondition permission -> line("gui-item.line.price-permission",
                        LoreLines.value("node", permission.node()));
            });
        }
        return List.copyOf(lines);
    }

    /** BigDecimal → 无尾零普通数字串（与商店价格行同口径）。 */
    private static String plainNumber(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** 单行组件构建（共享渲染管线）。 */
    private Component line(String key, TagResolver... resolvers) {
        return LoreLines.line(templates, key, resolvers);
    }
}
