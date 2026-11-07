package net.baiyu.title.gui.shop;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.gui.repository.RepositoryMenu;
import net.baiyu.title.service.PurchaseFlow;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.WardrobeView;
import net.baiyu.title.service.purchase.BlockReason;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.PurchaseAbort;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/**
 * 商店菜单。
 *
 * <p>X = 商店条目（{@code ShopQueryService} 预裁决）：左键按资格三分支——
 * 可购买 → 确认购买（{@link ShopPurchaseConfirm} + 通用确认菜单一次性 token）、
 * 已拥有 → 跳转仓库（定位到该称号所在页）、不可购 → 按原因提示；
 * 右键 → 详情菜单。P = 返回服务器主菜单；A 箱子 → 装扮管理；B / F 翻页；
 * C 筛选、D 排序 → 原地替换进入编辑菜单（应用 / 取消再替换回商店，
 * 返回栈不涨）；E 自定义称号付款菜单（总开关关闭时隐藏入口）、
 * G 报价列表（总开关关闭时拒绝）。</p>
 *
 * <p>线程纪律：构造时主线程收集 {@link ShopQueryService.ShopContext}
 * 与会话中的筛选 / 排序快照；{@link #fetchEntries} 在异步 loadData 线程
 * 消费纯数据，零 Bukkit 零 DB IO。</p>
 */
public final class ShopMenu extends AbstractPagedMenu<ShopEntryView> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "####P####",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#ABCDEFG#");

    /** 不可购原因的展示优先级（原因集取首个命中项提示）。 */
    private static final List<BlockReason> REASON_PRIORITY = List.of(
            BlockReason.SOLD_OUT, BlockReason.OUTSIDE_WINDOW, BlockReason.HIDDEN,
            BlockReason.DELETED, BlockReason.NOT_PURCHASABLE,
            BlockReason.ECONOMY_UNAVAILABLE, BlockReason.CONDITIONS_UNMET);

    /** 玩家商店上下文（构造时主线程收集，带入异步查询）。 */
    private final ShopQueryService.ShopContext context;

    /** 筛选快照（构造时主线程读会话状态）。 */
    private final ShopFilter filter;

    /** 排序快照（构造时主线程读会话状态）。 */
    private final ShopSort sort;

    /**
     * 构造商店菜单（主线程：收集上下文 + 读会话筛选 / 排序，并把页码写回会话）。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public ShopMenu(MenuContext ctx, int page) {
        super(ctx, page);
        this.context = ctx.deps().shopViews().collectContext(ctx.player());
        final ShopViewState state = ctx.session().shopView();
        this.filter = state.filter();
        this.sort = state.sort();
        state.updatePage(Math.max(1, page));
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.shop.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.Shop(page());
    }

    /** 上一页 = B 键（底栏 7 键布局）。 */
    @Override
    protected char prevRole() {
        return 'B';
    }

    /** 下一页 = F 键（底栏 7 键布局）。 */
    @Override
    protected char nextRole() {
        return 'F';
    }

    /** 翻页写回会话规格与视图状态页码：back 重建 / 筛选取消回跳都落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.Shop(newPage), false);
        ctx.session().shopView().updatePage(newPage);
    }

    /** 异步查询商店条目（纯数据消费：缓存快照 + 主线程预收集的上下文）。 */
    @Override
    protected List<ShopEntryView> fetchEntries() {
        return ctx.deps().shopViews().queryShop(ctx.player().getUniqueId(), context, filter, sort);
    }

    /** 商店条目：左键按资格三分支、右键详情。 */
    @Override
    protected GuiItem renderEntry(ShopEntryView entry) {
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().shopItem(entry);
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1, event -> {
                    if (event.isRightClick()) {
                        navigate(new MenuSpec.ShopDetail(entry.title().id()));
                    } else {
                        handleLeftClick(entry);
                    }
                });
    }

    /** 顶栏 P 与底栏功能键（翻页 B / F 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('P'), mainMenuButton());
        gui.setItem(LAYOUT.slot('A'), items.item(Material.CHEST,
                lang("menu.shop.button.wardrobe.name"),
                langList("menu.shop.button.wardrobe.lore"),
                event -> navigate(new MenuSpec.Wardrobe())));
        gui.setItem(LAYOUT.slot('C'), items.item(Material.HOPPER,
                lang("menu.shop.button.filter.name"),
                langList("menu.shop.button.filter.lore"),
                event -> ctx.deps().router().replace(ctx.player(), new MenuSpec.ShopFilter())));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.COMPARATOR,
                lang("menu.shop.button.sort.name"),
                langList("menu.shop.button.sort.lore"),
                event -> ctx.deps().router().replace(ctx.player(), new MenuSpec.ShopSort())));
        if (ctx.deps().configHandler().getConfig().shop.customEnabled) {
            gui.setItem(LAYOUT.slot('E'), items.item(Material.ANVIL,
                    lang("menu.shop.button.custom.name"),
                    langList("menu.shop.button.custom.lore"),
                    event -> navigate(new MenuSpec.CustomTitle())));
        }
        gui.setItem(LAYOUT.slot('G'), items.item(Material.PAPER,
                lang("menu.shop.button.trade.name"),
                langList("menu.shop.button.trade.lore"),
                event -> openTradeList()));
    }

    /** G 报价列表入口：总开关关闭时拒绝并提示。 */
    private void openTradeList() {
        if (!ctx.deps().tradeViews().enabled()) {
            feedback("trade.disabled");
            return;
        }
        navigate(new MenuSpec.TradeList(1));
    }

    /** 左键分派：可购买 → 确认；已拥有 → 跳转仓库对应页；不可购 → 原因提示。 */
    private void handleLeftClick(ShopEntryView entry) {
        switch (entry.eligibility()) {
            case Eligibility.CanBuy ignored -> openPurchaseConfirm(entry);
            case Eligibility.Owned ignored -> navigate(
                    new MenuSpec.Repository(repositoryPageOf(entry.title().id())));
            case Eligibility.CannotBuy cannot -> feedback(reasonKey(cannot));
        }
    }

    /**
     * 弹出购买确认或付款选择菜单（T1-03）。
     * <ul>
     *   <li>OR 逻辑下满足分支 ≥ 2 且无权限免单强制 → 导航到付款方式选择菜单</li>
     *   <li>否则 → 直接弹确认菜单（原有路径，应付裁决失败时提示）</li>
     * </ul>
     */
    private void openPurchaseConfirm(ShopEntryView entry) {
        if (ShopPaymentSelectMenu.needsSelection(entry)) {
            navigate(new net.baiyu.title.gui.core.MenuSpec.ShopPaymentSelect(entry.title().id()));
            return;
        }
        try {
            confirm(ShopPurchaseConfirm.build(ctx, entry, null, this::settleBack));
        } catch (PurchaseAbort abort) {
            feedback(PurchaseFlow.rejectKey(abort.reason()));
        }
    }

    /** 购买链终局：确认菜单仍在前台时返回商店（重建即拿到最新资格 / 售量）。 */
    private void settleBack() {
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
            back();
        }
    }

    /** 已拥有 → 仓库定位页：按持有列表（id 升序，与仓库菜单同序）求所在页。 */
    private int repositoryPageOf(int titleId) {
        final List<WardrobeView.OwnedTitle> owned =
                ctx.deps().wardrobeViews().ownedTitles(ctx.player().getUniqueId());
        for (int i = 0; i < owned.size(); i++) {
            if (owned.get(i).title().id() == titleId) {
                return RepositoryMenu.pageOf(i);
            }
        }
        return 1;
    }

    /** 不可购原因集 → 提示语言键（按展示优先级取首个命中项）。 */
    private static String reasonKey(Eligibility.CannotBuy cannot) {
        for (BlockReason reason : REASON_PRIORITY) {
            if (cannot.has(reason)) {
                return switch (reason) {
                    case SOLD_OUT -> "shop.reject.sold-out";
                    case OUTSIDE_WINDOW -> "shop.reject.outside-window";
                    case HIDDEN -> "shop.reject.hidden";
                    case DELETED -> "shop.reject.title-not-found";
                    case NOT_PURCHASABLE -> "shop.reject.not-purchasable";
                    case ECONOMY_UNAVAILABLE -> "shop.reject.economy-unavailable";
                    case CONDITIONS_UNMET -> "shop.reject.conditions-unmet";
                };
            }
        }
        return "shop.reject.conditions-unmet";
    }
}
