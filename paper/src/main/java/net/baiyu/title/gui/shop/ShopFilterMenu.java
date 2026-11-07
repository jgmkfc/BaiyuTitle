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
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 商店筛选菜单。
 *
 * <p>H 获取方式 / I 购买状态 / J 稀有度（全部 + config rarities 动态）/
 * K 活动状态：四维单选，点击循环切换编辑暂存（挂 {@link ShopViewState}，
 * 选中值 ≠ 默认值时书本附魔）；A 应用 → 落定暂存并原地替换回商店第 1 页；
 * B 火药 → 暂存恢复默认（不跳转）；C 取消 → 丢弃暂存并替换回商店当前页；ESC 关闭同取消丢弃暂存。</p>
 */
public final class ShopFilterMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#HZIZJZK#",
            "##A#B#C##");

    /**
     * 构造商店筛选菜单（主线程：以已应用筛选播种编辑暂存）。
     *
     * @param ctx 菜单上下文
     */
    public ShopFilterMenu(MenuContext ctx) {
        super(ctx);
        final ShopViewState state = ctx.session().shopView();
        state.setPendingFilter(state.pendingFilterOrApplied());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.shop.filter.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.ShopFilter();
    }

    /** 玩家主动 ESC：丢弃编辑暂存（已应用筛选不动）。 */
    @Override
    protected void onClosed() {
        ctx.session().shopView().clearPendingFilter();
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        final ShopFilter pending = pending();
        paintOption(gui, 'H', "acquire", acquireText(pending.acquire()),
                pending.acquire() != ShopFilter.AcquireType.ALL);
        paintOption(gui, 'I', "own", ownText(pending.own()),
                pending.own() != ShopFilter.OwnState.ALL);
        paintOption(gui, 'J', "rarity", rarityText(pending.rarity()),
                pending.rarity() != null);
        paintOption(gui, 'K', "activity", activityText(pending.activity()),
                pending.activity() != ShopFilter.ActivityState.ALL);
        final MenuButtons buttons = ctx.deps().buttons();
        gui.setItem(LAYOUT.slot('A'), buttons.apply(event -> apply()));
        gui.setItem(LAYOUT.slot('B'), buttons.reset(event -> {
            ctx.session().shopView().setPendingFilter(ShopFilter.DEFAULT);
            refresh();
        }));
        gui.setItem(LAYOUT.slot('C'), buttons.cancel(event -> cancel()));
    }

    /** 放置单个维度书本（附魔 = 选中值 ≠ 默认值），点击循环切换该维度。 */
    private void paintOption(BaseGui gui, char role, String dimension, String value, boolean glint) {
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(Material.BOOK,
                lang("menu.shop.filter." + dimension + ".name"),
                langList("menu.shop.filter.lore", Placeholder.parsed("value", value)),
                glint, 1, event -> {
                    cycle(role);
                    refresh();
                }));
    }

    /** 循环切换指定维度的暂存值。 */
    private void cycle(char role) {
        final ShopViewState state = ctx.session().shopView();
        final ShopFilter p = pending();
        state.setPendingFilter(switch (role) {
            case 'H' -> new ShopFilter(nextEnum(ShopFilter.AcquireType.values(), p.acquire()),
                    p.own(), p.rarity(), p.activity());
            case 'I' -> new ShopFilter(p.acquire(),
                    nextEnum(ShopFilter.OwnState.values(), p.own()), p.rarity(), p.activity());
            case 'J' -> new ShopFilter(p.acquire(), p.own(), nextRarity(p.rarity()), p.activity());
            case 'K' -> new ShopFilter(p.acquire(), p.own(), p.rarity(),
                    nextEnum(ShopFilter.ActivityState.values(), p.activity()));
            default -> p;
        });
    }

    /** A 应用：落定暂存 → 商店回到第 1 页（原地替换，返回栈不动）。 */
    private void apply() {
        final ShopViewState state = ctx.session().shopView();
        state.applyFilter(state.pendingFilterOrApplied());
        state.clearPendingFilter();
        state.updatePage(1);
        ctx.deps().router().replace(ctx.player(), new MenuSpec.Shop(1));
    }

    /** C 取消：丢弃暂存 → 替换回商店当前页。 */
    private void cancel() {
        final ShopViewState state = ctx.session().shopView();
        state.clearPendingFilter();
        ctx.deps().router().replace(ctx.player(), new MenuSpec.Shop(state.page()));
    }

    /** 当前编辑暂存（构造时已播种，恒非空回落）。 */
    private ShopFilter pending() {
        return ctx.session().shopView().pendingFilterOrApplied();
    }

    /** 枚举循环取下一项。 */
    private static <T extends Enum<T>> T nextEnum(T[] values, T current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    /** 稀有度循环：全部(null) → config rarities 逐项 → 回到全部。 */
    private @Nullable String nextRarity(@Nullable String current) {
        final List<String> rarities = ctx.deps().configHandler().getConfig().rarities;
        if (rarities.isEmpty()) {
            return null;
        }
        if (current == null) {
            return rarities.getFirst();
        }
        final int index = rarities.indexOf(current);
        return index < 0 || index + 1 >= rarities.size() ? null : rarities.get(index + 1);
    }

    /** 获取方式选项文案。 */
    private String acquireText(ShopFilter.AcquireType acquire) {
        return raw(switch (acquire) {
            case ALL -> "menu.shop.filter.option.all";
            case FREE -> "shop.type.free";
            case VAULT -> "shop.type.vault";
            case COIN -> "shop.type.coin";
            case ITEM -> "shop.type.item";
            case PERMISSION -> "shop.type.permission";
            case BOND -> "shop.type.bond";
        });
    }

    /** 购买状态选项文案。 */
    private String ownText(ShopFilter.OwnState own) {
        return raw(switch (own) {
            case ALL -> "menu.shop.filter.option.all";
            case OWNED -> "menu.shop.filter.option.owned";
            case NOT_OWNED -> "menu.shop.filter.option.not-owned";
            case CAN_BUY -> "menu.shop.filter.option.can-buy";
            case CANNOT_BUY -> "menu.shop.filter.option.cannot-buy";
        });
    }

    /** 稀有度选项文案（null = 全部；稀有度名称按 config 原文展示）。 */
    private String rarityText(@Nullable String rarity) {
        return rarity != null ? rarity : raw("menu.shop.filter.option.all");
    }

    /** 活动状态选项文案。 */
    private String activityText(ShopFilter.ActivityState activity) {
        return raw(switch (activity) {
            case ALL -> "menu.shop.filter.option.all";
            case WINDOW_ACTIVE -> "menu.shop.filter.option.window-active";
            case WINDOW_ENDED -> "menu.shop.filter.option.window-ended";
            case TOTAL_OPEN -> "menu.shop.filter.option.total-open";
            case SOLD_OUT -> "menu.shop.filter.option.sold-out";
        });
    }

    /** 原始模板取词便捷转发。 */
    private String raw(String key) {
        return ctx.deps().lang().raw(key);
    }
}
