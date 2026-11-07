package net.baiyu.title.gui.trade;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractRemotePagedMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.repository.Page;
import net.baiyu.title.service.TradeOfferView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

/**
 * 个人交易记录菜单：仅显示与自己相关的记录。
 *
 * <p>X = 交易摘要（纸；对方、称号数、时间、状态；点击查看只读详情）；
 * A / C 翻页；B = 返回上级。
 * 状态展示按裁决 §十一 10：EXPIRED 归入"已拒绝"。</p>
 */
public final class TradeHistoryMenu extends AbstractRemotePagedMenu<TradeOfferView> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##A#B#C##");

    /**
     * 构造个人交易记录菜单。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public TradeHistoryMenu(MenuContext ctx, int page) {
        super(ctx, page);
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.trade-history.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.TradeHistory(page());
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.TradeHistory(newPage), false);
    }

    /** 异步取一页个人历史（视图门面内部走 DB 读线程池）。 */
    @Override
    protected Page<TradeOfferView> fetchPage(int page, int size) {
        return ctx.deps().tradeViews().personalHistory(ctx.player().getUniqueId(), page, size);
    }

    /** 摘要条目：纸物品，点击进入只读详情。 */
    @Override
    protected GuiItem renderEntry(TradeOfferView view) {
        return ctx.deps().itemFactory().item(Material.PAPER,
                lang("menu.trade-history.entry.name",
                        Placeholder.unparsed("player", view.counterpartName())),
                langList("menu.trade-history.entry.lore",
                        Placeholder.component("direction", lang(view.incoming()
                                ? "menu.trade-list.direction.incoming"
                                : "menu.trade-list.direction.outgoing")),
                        Placeholder.component("status", lang(view.statusKey())),
                        Placeholder.parsed("mine", Integer.toString(view.mineCount())),
                        Placeholder.parsed("theirs", Integer.toString(view.theirsCount())),
                        Placeholder.parsed("created", view.createdText())),
                event -> navigate(new MenuSpec.TradeDetail(view.tradeId())));
    }

    /** 底栏 B 返回键（翻页 A / C 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().back(event -> back()));
    }
}
