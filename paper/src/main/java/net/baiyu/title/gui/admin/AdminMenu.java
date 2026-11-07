package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.core.PlayerSelectRequest;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.ItemStackCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理主菜单。
 *
 * <p>X = 全部称号（<b>含隐藏</b>，id 升序；管理专用 lore §1.3.2.4.3），
 * 左右键皆进入管理详情菜单；P = 返回服务器主菜单；A / E 翻页；
 * B 箱子 = 管理玩家仓库（通用玩家选择菜单注入管理回调：选中 →
 * 管理玩家仓库菜单）；C 金锭 = 返回商店菜单；D 书 = 全服交易记录。</p>
 *
 * <p>线程纪律：条目来自目录缓存快照（异步 fetchEntries 零 IO）；
 * 条件物品名需要 Bukkit 反序列化，在主线程 renderEntry 阶段按需解析并
 * 以实例级映射缓存（同一 Base64 只解一次）。</p>
 */
public final class AdminMenu extends AbstractPagedMenu<Title> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "####P####",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##ABCDE##");

    /** 条件物品名缓存：Base64 → 本地化物品名组件（主线程读写）。 */
    private final Map<String, Component> itemNameCache = new HashMap<>();

    /**
     * 构造管理主菜单。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public AdminMenu(MenuContext ctx, int page) {
        super(ctx, page);
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.Admin(page());
    }

    /** 下一页 = E 键（上一页保持基类默认 A 键）。 */
    @Override
    protected char nextRole() {
        return 'E';
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.Admin(newPage), false);
    }

    /** 异步取全部称号（含隐藏；目录缓存快照，零 IO）。 */
    @Override
    protected List<Title> fetchEntries() {
        return ctx.deps().adminViews().allTitles();
    }

    /** 称号条目：管理专用 lore；左右键皆进入管理详情菜单。 */
    @Override
    protected GuiItem renderEntry(Title entry) {
        final TitleItemRenderer.RenderedItem rendered =
                ctx.deps().renderer().adminItem(entry, itemNamesFor(entry));
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1,
                event -> navigate(new MenuSpec.AdminEditor(entry.id(), 1)));
    }

    /** 顶栏 P 与底栏功能键（翻页 A / E 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('P'), mainMenuButton());
        gui.setItem(LAYOUT.slot('B'), items.item(Material.CHEST,
                lang("menu.admin.button.player-repo.name"),
                langList("menu.admin.button.player-repo.lore"),
                event -> navigate(new MenuSpec.PlayerSelect(new PlayerSelectRequest(
                        target -> ctx.deps().router().open(ctx.player(),
                                new MenuSpec.AdminPlayerRepository(target.getUniqueId(), 1)))))));
        gui.setItem(LAYOUT.slot('C'), items.item(Material.GOLD_INGOT,
                lang("menu.admin.button.shop.name"),
                langList("menu.admin.button.shop.lore"),
                event -> navigate(new MenuSpec.Shop(1))));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.BOOK,
                lang("menu.admin.button.trade-log.name"),
                langList("menu.admin.button.trade-log.lore"),
                event -> navigate(new MenuSpec.AdminTradeLog(1))));
    }

    /**
     * 条目的条件物品名子集（主线程；同一 Base64 只反序列化一次，
     * 损坏数据兜底 {@code ?}）。
     */
    private Map<String, Component> itemNamesFor(Title title) {
        final Map<String, Component> subset = new HashMap<>();
        for (final PurchaseCondition condition : title.conditions()) {
            if (condition instanceof ItemCondition item) {
                subset.put(item.itemBase64(), itemNameCache.computeIfAbsent(item.itemBase64(),
                        base64 -> ItemStackCodec.tryDecode(base64)
                                .map(stack -> ctx.deps().itemNames().displayComponent(stack.getType()))
                                .orElse(Component.text("?"))));
            }
        }
        return subset;
    }
}
