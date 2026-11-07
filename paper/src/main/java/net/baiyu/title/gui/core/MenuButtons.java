package net.baiyu.title.gui.core;

import dev.triumphteam.gui.components.GuiAction;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.translation.Lang;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 通用按钮构造器：边框 / 翻页 / 返回 / 关闭 /
 * 应用 / 取消 / 重置 / 返回主菜单等跨菜单复用按钮的唯一出处。
 *
 * <p>物品裁决：返回上级统一 REDSTONE（§11⑥）、关闭 BARRIER、
 * 应用 LIME_STAINED_GLASS_PANE、取消 RED_STAINED_GLASS_PANE、
 * 重置 GUNPOWDER、翻页 ARROW、返回主菜单 CLOCK、
 * 边框 GRAY_STAINED_GLASS_PANE。
 * 文案一律取 lang {@code menu.common.*}，禁止硬编码。</p>
 */
public final class MenuButtons {

    /** 物品构造工厂（唯一入口）。 */
    private final GuiItemFactory items;

    /** 语言门面。 */
    private final Lang lang;

    /**
     * 构造通用按钮构造器。
     *
     * @param items 物品构造工厂
     * @param lang  语言门面
     */
    public MenuButtons(GuiItemFactory items, Lang lang) {
        this.items = Objects.requireNonNull(items, "物品构造工厂不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
    }

    /** @return 边框物品（灰色玻璃板，不响应点击） */
    public GuiItem border() {
        return build(Material.GRAY_STAINED_GLASS_PANE, "border", null);
    }

    /**
     * @param action 点击回调
     * @return 上一页按钮（ARROW）
     */
    public GuiItem prevPage(GuiAction<InventoryClickEvent> action) {
        return build(Material.ARROW, "prev", action);
    }

    /**
     * @param action 点击回调
     * @return 下一页按钮（ARROW）
     */
    public GuiItem nextPage(GuiAction<InventoryClickEvent> action) {
        return build(Material.ARROW, "next", action);
    }

    /**
     * @param action 点击回调（通常 {@code e -> back()}）
     * @return 返回上级按钮（REDSTONE，全插件统一）
     */
    public GuiItem back(GuiAction<InventoryClickEvent> action) {
        return build(Material.REDSTONE, "back", action);
    }

    /**
     * @param action 点击回调
     * @return 关闭菜单按钮（BARRIER）
     */
    public GuiItem close(GuiAction<InventoryClickEvent> action) {
        return build(Material.BARRIER, "close", action);
    }

    /**
     * @param action 点击回调
     * @return 应用按钮（LIME_STAINED_GLASS_PANE）
     */
    public GuiItem apply(GuiAction<InventoryClickEvent> action) {
        return build(Material.LIME_STAINED_GLASS_PANE, "apply", action);
    }

    /**
     * @param action 点击回调
     * @return 取消按钮（RED_STAINED_GLASS_PANE）
     */
    public GuiItem cancel(GuiAction<InventoryClickEvent> action) {
        return build(Material.RED_STAINED_GLASS_PANE, "cancel", action);
    }

    /**
     * @param action 点击回调
     * @return 重置按钮（GUNPOWDER；筛选 / 排序菜单恢复默认）
     */
    public GuiItem reset(GuiAction<InventoryClickEvent> action) {
        return build(Material.GUNPOWDER, "reset", action);
    }

    /**
     * @param action 点击回调（执行 config 指定的服务器主菜单指令）
     * @return 返回主菜单按钮（CLOCK，9x6 列表菜单顶栏 P 键）
     */
    public GuiItem mainMenu(GuiAction<InventoryClickEvent> action) {
        return build(Material.CLOCK, "main-menu", action);
    }

    /** 按 {@code menu.common.<key>.name/.lore} 取词并构造按钮。 */
    private GuiItem build(Material material, String key, @Nullable GuiAction<InventoryClickEvent> action) {
        final String base = "menu.common." + key;
        return items.item(material,
                lang.component(base + ".name"),
                lang.list(base + ".lore"),
                action);
    }
}
