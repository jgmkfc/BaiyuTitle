package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.gui.core.AbstractRemotePagedMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.input.ChatInputRequest;
import net.baiyu.title.input.InputValidators;
import net.baiyu.title.repository.Page;
import net.baiyu.title.service.TradeOfferView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 全服交易记录菜单：包含所有状态、不含商店
 * （系统）出售记录（trade 表本就只存玩家间交易）。
 *
 * <p>X = 交易记录（纸；发起方 / 接收方 / 各自标的数 / 状态 / 时间，
 * 点击查看只读详情）；A / E 翻页；B 漏斗 = 状态筛选循环
 * （全部 → 等待中 → 已完成 → 已销毁 → 已拒绝）；C 屏障 = 关闭菜单；
 * D 望远镜 = 聊天栏输入玩家名模糊搜索（输入 {@code *} 清除搜索）。
 * 筛选 / 搜索态挂 {@link TradeLogState}（会话内保持）。</p>
 */
public final class AdminTradeLogMenu extends AbstractRemotePagedMenu<TradeOfferView> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##ABCDE##");

    /** 搜索串上限（玩家名最长 16，留余量给容错输入）。 */
    private static final int SEARCH_MAX_LENGTH = 32;

    /** 筛选 / 搜索态（会话级）。 */
    private final TradeLogState state;

    /**
     * 构造全服交易记录菜单（主线程：取会话态）。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public AdminTradeLogMenu(MenuContext ctx, int page) {
        super(ctx, page);
        this.state = ctx.session().tradeLog();
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.trade-log.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.AdminTradeLog(page());
    }

    /** 下一页 = E 键（上一页保持基类默认 A 键）。 */
    @Override
    protected char nextRole() {
        return 'E';
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.AdminTradeLog(newPage), false);
    }

    /** 异步取一页全服历史（按会话态过滤；视图门面内部走 DB 读线程池）。 */
    @Override
    protected Page<TradeOfferView> fetchPage(int page, int size) {
        return ctx.deps().tradeViews().globalHistory(state.filter(), state.nameLike(), page, size);
    }

    /** 记录条目：纸物品（管理视角状态文案），点击进入只读详情。 */
    @Override
    protected GuiItem renderEntry(TradeOfferView view) {
        return ctx.deps().itemFactory().item(Material.PAPER,
                lang("menu.admin.trade-log.entry.name",
                        Placeholder.unparsed("initiator", view.initiatorName()),
                        Placeholder.unparsed("target", view.targetName())),
                langList("menu.admin.trade-log.entry.lore",
                        Placeholder.component("status", lang(view.statusKey())),
                        Placeholder.parsed("initiator-count", Integer.toString(view.initiatorCount())),
                        Placeholder.parsed("target-count", Integer.toString(view.targetCount())),
                        Placeholder.parsed("created", view.createdText()),
                        Placeholder.parsed("expires", view.expiresText())),
                event -> navigate(new MenuSpec.TradeDetail(view.tradeId())));
    }

    /** 底栏功能键（翻页 A / E 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('B'), items.item(Material.HOPPER,
                lang("menu.admin.trade-log.filter.name"),
                langList("menu.admin.trade-log.filter.lore",
                        Placeholder.component("current", lang(filterOptionKey(state.filter())))),
                event -> {
                    state.cycleFilter();
                    jumpToFirstPage();
                }));
        gui.setItem(LAYOUT.slot('C'), ctx.deps().buttons().close(
                event -> ctx.deps().router().closeAll(ctx.player())));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.SPYGLASS,
                lang("menu.admin.trade-log.search.name"),
                langList("menu.admin.trade-log.search.lore",
                        Placeholder.unparsed("current", state.nameLike() == null
                                ? raw("menu.admin.trade-log.search.none") : state.nameLike())),
                event -> openSearchInput()));
    }

    /** D 搜索：聊天栏输入玩家名（模糊；{@code *} 清除），完成后回本菜单第 1 页。 */
    private void openSearchInput() {
        ctx.deps().inputs().openChatInput(ctx.player(), new ChatInputRequest(
                "menu.admin.trade-log.search.prompt",
                InputValidators.length(1, SEARCH_MAX_LENGTH),
                text -> state.setNameLike("*".equals(text) ? null : text),
                () -> {
                },
                new MenuSpec.AdminTradeLog(1)));
    }

    /** 筛选状态 → 选项文案语言键（null = 全部）。 */
    private static String filterOptionKey(@Nullable TradeStatus filter) {
        return "menu.admin.trade-log.filter.option."
                + (filter == null ? "all" : filter.name().toLowerCase(Locale.ROOT));
    }

    /** 语言原文便捷取词（搜索"无"占位）。 */
    private String raw(String key) {
        return ctx.deps().lang().raw(key);
    }
}
