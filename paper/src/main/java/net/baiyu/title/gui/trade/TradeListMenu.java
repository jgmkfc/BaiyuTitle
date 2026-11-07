package net.baiyu.title.gui.trade;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.core.PlayerSelectRequest;
import net.baiyu.title.service.TradeOfferView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 报价列表菜单。
 *
 * <p>X = 与自己相关的待处理报价（纸；点击进入报价详情）；A / E 翻页；
 * B 铁砧 = 创建报价（需 {@code baiyutitle.trade.create} 权限；进入通用
 * 玩家选择菜单，选中后建草稿 → 选己方称号）；C 金锭 = 返回上级
 * （无上级关闭）；D 纸 = 个人交易记录。</p>
 */
public final class TradeListMenu extends AbstractPagedMenu<TradeOfferView> {

    /** 发起报价所需权限（默认 true，无权限只能接受）。 */
    public static final String CREATE_PERMISSION = "baiyutitle.trade.create";

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##ABCDE##");

    /**
     * 构造报价列表菜单。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public TradeListMenu(MenuContext ctx, int page) {
        super(ctx, page);
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.trade-list.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.TradeList(page());
    }

    /** 下一页 = E 键（上一页保持基类默认 A 键）。 */
    @Override
    protected char nextRole() {
        return 'E';
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.TradeList(newPage), false);
    }

    /** 异步取待处理报价（视图门面内部走 DB 读线程池）。 */
    @Override
    protected List<TradeOfferView> fetchEntries() {
        return ctx.deps().tradeViews().pendingOffers(ctx.player().getUniqueId());
    }

    /** 报价条目：纸物品，摘要 lore，点击进入详情。 */
    @Override
    protected GuiItem renderEntry(TradeOfferView view) {
        return ctx.deps().itemFactory().item(Material.PAPER,
                lang("menu.trade-list.entry.name",
                        Placeholder.unparsed("player", view.counterpartName())),
                langList("menu.trade-list.entry.lore",
                        Placeholder.component("direction", lang(view.incoming()
                                ? "menu.trade-list.direction.incoming"
                                : "menu.trade-list.direction.outgoing")),
                        Placeholder.parsed("mine", Integer.toString(view.mineCount())),
                        Placeholder.parsed("theirs", Integer.toString(view.theirsCount())),
                        Placeholder.parsed("created", view.createdText()),
                        Placeholder.parsed("expires", view.expiresText())),
                event -> navigate(new MenuSpec.TradeDetail(view.tradeId())));
    }

    /** 底栏功能键（翻页 A / E 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('B'), items.item(Material.ANVIL,
                lang("menu.trade-list.button.create.name"),
                langList("menu.trade-list.button.create.lore"),
                event -> startCreate()));
        gui.setItem(LAYOUT.slot('C'), items.item(Material.GOLD_INGOT,
                lang("menu.trade-list.button.return.name"),
                langList("menu.trade-list.button.return.lore"),
                event -> back()));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.PAPER,
                lang("menu.trade-list.button.history.name"),
                langList("menu.trade-list.button.history.lore"),
                event -> navigate(new MenuSpec.TradeHistory(1))));
    }

    /** B 创建报价：总开关 + 发起权限把守后进入玩家选择菜单。 */
    private void startCreate() {
        if (!ctx.deps().tradeViews().enabled()) {
            feedback("trade.disabled");
            return;
        }
        if (!ctx.player().hasPermission(CREATE_PERMISSION)) {
            feedback("menu.trade-list.create-no-permission");
            return;
        }
        navigate(new MenuSpec.PlayerSelect(new PlayerSelectRequest(this::beginDraft)));
    }

    /** 选中交易目标：不可与自己交易；建草稿并进入选己方称号菜单。 */
    private void beginDraft(Player target) {
        if (target.getUniqueId().equals(ctx.player().getUniqueId())) {
            feedback("trade.reject.self-trade");
            return;
        }
        ctx.session().beginTradeDraft(new TradeDraft(target.getUniqueId(), target.getName()));
        ctx.deps().router().open(ctx.player(), new MenuSpec.TradeTitleSelect(TradeDraft.Side.SELF));
    }
}
