package net.baiyu.title.gui.shop;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuButtons;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

/**
 * 商店排序菜单。
 *
 * <p>H 时间 / I 价格 / J 购买热度 / K 稀有度：四键<b>单选</b>——点击未选中键
 * 切换为该键并取其首击方向（时间=新→老、价格=低→高、热度=高→低、
 * 稀有度=低→高，均按业务表格首列）；再点已选中键则方向翻转循环。
 * 选中键书本附魔；编辑暂存挂 {@link ShopViewState}。A 应用 → 落定暂存并
 * 原地替换回商店第 1 页；B 火药 → 暂存恢复默认（不跳转）；C 取消 →
 * 丢弃暂存并替换回商店当前页（与筛选菜单同构）；
 * ESC 关闭同取消丢弃暂存。</p>
 *
 * <p>裁决记录（承接 ShopSort 的排序阶段）：热度首击方向取菜单表格的
 * 高→低（与正文互斥处以表格为准）；首击方向语义只落在本菜单，
 * 改判无须动 {@link ShopSort} 纯函数。</p>
 */
public final class ShopSortMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#HZIZJZK#",
            "##A#B#C##");

    /**
     * 构造商店排序菜单（主线程：以已应用排序播种编辑暂存）。
     *
     * @param ctx 菜单上下文
     */
    public ShopSortMenu(MenuContext ctx) {
        super(ctx);
        final ShopViewState state = ctx.session().shopView();
        state.setPendingSort(state.pendingSortOrApplied());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.shop.sort.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.ShopSort();
    }

    /** 玩家主动 ESC：丢弃编辑暂存（已应用排序不动）。 */
    @Override
    protected void onClosed() {
        ctx.session().shopView().clearPendingSort();
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        paintKey(gui, 'H', ShopSort.Key.TIME, "time");
        paintKey(gui, 'I', ShopSort.Key.PRICE, "price");
        paintKey(gui, 'J', ShopSort.Key.POPULARITY, "popularity");
        paintKey(gui, 'K', ShopSort.Key.RARITY, "rarity");
        final MenuButtons buttons = ctx.deps().buttons();
        gui.setItem(LAYOUT.slot('A'), buttons.apply(event -> apply()));
        gui.setItem(LAYOUT.slot('B'), buttons.reset(event -> {
            ctx.session().shopView().setPendingSort(ShopSort.DEFAULT);
            refresh();
        }));
        gui.setItem(LAYOUT.slot('C'), buttons.cancel(event -> cancel()));
    }

    /** 放置单个排序键书本（附魔 = 当前选中键），点击单选 / 翻转。 */
    private void paintKey(BaseGui gui, char role, ShopSort.Key key, String dimension) {
        final ShopSort pending = pending();
        final boolean selected = pending.key() == key;
        final boolean ascending = selected ? pending.ascending() : firstClickAscending(key);
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(Material.BOOK,
                lang("menu.shop.sort." + dimension + ".name"),
                langList("menu.shop.sort.lore",
                        Placeholder.parsed("value", directionText(key, ascending))),
                selected, 1, event -> {
                    // 点击时重读暂存（不用绘制时的快照）：连点在重绘完成前也不会错翻
                    final ShopViewState state = ctx.session().shopView();
                    final ShopSort current = state.pendingSortOrApplied();
                    state.setPendingSort(current.key() == key
                            ? current.flipped()
                            : new ShopSort(key, firstClickAscending(key)));
                    refresh();
                }));
    }

    /** A 应用：落定暂存 → 商店回到第 1 页（原地替换，返回栈不动）。 */
    private void apply() {
        final ShopViewState state = ctx.session().shopView();
        state.applySort(state.pendingSortOrApplied());
        state.clearPendingSort();
        state.updatePage(1);
        ctx.deps().router().replace(ctx.player(), new MenuSpec.Shop(1));
    }

    /** C 取消：丢弃暂存 → 替换回商店当前页。 */
    private void cancel() {
        final ShopViewState state = ctx.session().shopView();
        state.clearPendingSort();
        ctx.deps().router().replace(ctx.player(), new MenuSpec.Shop(state.page()));
    }

    /** 当前编辑暂存（构造时已播种，恒非空回落）。 */
    private ShopSort pending() {
        return ctx.session().shopView().pendingSortOrApplied();
    }

    /** 各键首击方向（业务表格首列选项）：时间新→老、价格低→高、热度高→低、稀有度低→高。 */
    private static boolean firstClickAscending(ShopSort.Key key) {
        return switch (key) {
            case TIME, POPULARITY -> false;
            case PRICE, RARITY -> true;
        };
    }

    /** 方向展示文案：时间用 新→老/老→新，其余键用 低→高/高→低。 */
    private String directionText(ShopSort.Key key, boolean ascending) {
        final String suffix = switch (key) {
            case TIME -> ascending ? "oldest" : "newest";
            case PRICE, POPULARITY, RARITY -> ascending ? "asc" : "desc";
        };
        return ctx.deps().lang().raw("menu.shop.sort.option." + suffix);
    }
}
