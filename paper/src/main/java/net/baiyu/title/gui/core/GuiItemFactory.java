package net.baiyu.title.gui.core;

import dev.triumphteam.gui.components.GuiAction;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * TriumphGUI 物品构造唯一入口（排程方案风险 1 的缓解点）：
 * 全插件禁止散落 {@code new ItemStack} / {@code ItemMeta} 构造 GUI 物品，
 * 一律经本工厂——TriumphGUI 3.1.13 对 1.21.11 数据组件（斜体 / glint / 头颅）
 * 的兼容问题被收敛在此单点，出问题只改一处。
 *
 * <p>约定：物品名与 lore 逐行
 * {@code decorationIfAbsent(ITALIC, FALSE)}——压掉原版紫色斜体默认值，
 * 但不覆盖文案里显式声明的斜体；glint 用 1.20.5+ 的
 * {@code enchantment_glint_override} 数据组件（不再走附魔 + 隐藏标志）。</p>
 *
 * <p>无状态、线程安全；但 Bukkit 物品构造按纪律仅在主线程调用。</p>
 */
public final class GuiItemFactory {

    /** 单格堆叠上限（数量夹取用）。 */
    private static final int MAX_STACK = 64;

    /**
     * 构造完整参数的 GUI 物品。
     *
     * @param material 物品材质
     * @param name     物品名（须来自 Lang / 渲染门面构建的组件）
     * @param lore     lore 组件列表（可为空列表）
     * @param glint    是否附魔光泽（选中态标记；false 时保持材质默认）
     * @param amount   显示数量（夹取到 1~64）
     * @param action   点击回调；null 表示纯展示不响应
     * @return GUI 物品
     */
    public GuiItem item(Material material, Component name, List<Component> lore,
                        boolean glint, int amount,
                        @Nullable GuiAction<InventoryClickEvent> action) {
        final ItemStack stack = new ItemStack(
                Objects.requireNonNull(material, "物品材质不能为空"),
                Math.clamp(amount, 1, MAX_STACK));
        applyDisplay(stack, name, lore, glint);
        return new GuiItem(stack, action);
    }

    /**
     * 构造常用形态的 GUI 物品（数量 1、无光泽）。
     *
     * @param material 物品材质
     * @param name     物品名
     * @param lore     lore 组件列表
     * @param action   点击回调；null 表示纯展示不响应
     * @return GUI 物品
     */
    public GuiItem item(Material material, Component name, List<Component> lore,
                        @Nullable GuiAction<InventoryClickEvent> action) {
        return item(material, name, lore, false, 1, action);
    }

    /**
     * 构造玩家头颅物品（个人信息键 / 玩家选择菜单 / 购买者列表的接缝——
     * 头颅皮肤数据组件的兼容处理收敛在此）。
     *
     * @param owner  头颅归属玩家；null 时为无皮肤默认头
     * @param name   物品名
     * @param lore   lore 组件列表
     * @param action 点击回调；null 表示纯展示不响应
     * @return GUI 物品
     */
    public GuiItem playerHead(@Nullable OfflinePlayer owner, Component name, List<Component> lore,
                              @Nullable GuiAction<InventoryClickEvent> action) {
        final ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (owner != null) {
            stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        }
        applyDisplay(stack, name, lore, false);
        return new GuiItem(stack, action);
    }

    /**
     * 统一套用展示元数据：名称 / lore 压斜体、按需开 glint 数据组件。
     */
    private static void applyDisplay(ItemStack stack, Component name, List<Component> lore, boolean glint) {
        Objects.requireNonNull(name, "物品名不能为空");
        Objects.requireNonNull(lore, "物品 lore 不能为空（可传空列表）");
        stack.editMeta(meta -> {
            meta.displayName(unitalic(name));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream().map(GuiItemFactory::unitalic).toList());
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            }
        });
    }

    /** 未显式声明斜体时压为非斜体（保留文案里显式的 &lt;italic&gt;）。 */
    private static Component unitalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
