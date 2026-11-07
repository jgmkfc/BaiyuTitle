package net.baiyu.title.gui.repository;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.WardrobeView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/**
 * 仓库菜单（业务设计 §1.3.2.3.7.1；底栏 7 键 {@code #ABCDEFG#} 以表格为准，
 * 字符画底栏为笔误——表现层裁决 §11②）。
 *
 * <p>X = 称号条目（仓库版 lore 无操作行、恒无附魔，点击无操作——§11③）；
 * P = 返回服务器主菜单；A 箱子 → 装扮管理；B / F 翻页；C 筛选、D 排序 →
 * 复用商店的筛选 / 排序菜单（§4：作用于仓库视图状态的语义留 M5+ 接入，
 * 当前应用 / 取消回跳商店）；E 自定义称号付款菜单（总开关关闭时隐藏入口）、
 * G 兑换码输入（与 {@code /byt redeem} 共用同一流程）。</p>
 */
public final class RepositoryMenu extends AbstractPagedMenu<WardrobeView.OwnedTitle> {

    /** 布局字符画（顶栏条目区与底栏键位均照表格）。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "####P####",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#ABCDEFG#");

    /**
     * 条目序号 → 所在页码（1 起；商店"跳转仓库"定位复用，与本菜单分页容量同源）。
     *
     * @param entryIndex 条目在持有列表中的序号（0 起）
     * @return 页码（1 起）
     */
    public static int pageOf(int entryIndex) {
        return Math.max(0, entryIndex) / LAYOUT.slots('X').length + 1;
    }

    /**
     * 构造仓库菜单。
     *
     * @param ctx  菜单上下文
     * @param page 初始页码（1 起）
     */
    public RepositoryMenu(MenuContext ctx, int page) {
        super(ctx, page);
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.repository.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.Repository(page());
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

    /** 翻页写回会话当前规格：导航离开 / back 重建都落在正确页码。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.Repository(newPage), false);
    }

    /** 异步预取持有称号列表（纯缓存读）。 */
    @Override
    protected List<WardrobeView.OwnedTitle> fetchEntries() {
        return ctx.deps().wardrobeViews().ownedTitles(ctx.player().getUniqueId());
    }

    /** 仓库条目：只读展示（无点击回调，§11③；仓库版 lore 恒无 glint）。 */
    @Override
    protected GuiItem renderEntry(WardrobeView.OwnedTitle owned) {
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().warehouseItem(
                new TitleItemRenderer.OwnedTitleView(
                        owned.title(), owned.expiresOn(), owned.referencedAnywhere()));
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1, null);
    }

    /** 顶栏 P 与底栏功能键（翻页 B / F 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('P'), mainMenuButton());
        gui.setItem(LAYOUT.slot('A'), items.item(Material.CHEST,
                lang("menu.repository.button.wardrobe.name"),
                langList("menu.repository.button.wardrobe.lore"),
                event -> navigate(new MenuSpec.Wardrobe())));
        gui.setItem(LAYOUT.slot('C'), items.item(Material.HOPPER,
                lang("menu.repository.button.filter.name"),
                langList("menu.repository.button.filter.lore"),
                event -> navigate(new MenuSpec.ShopFilter())));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.COMPARATOR,
                lang("menu.repository.button.sort.name"),
                langList("menu.repository.button.sort.lore"),
                event -> navigate(new MenuSpec.ShopSort())));
        if (ctx.deps().configHandler().getConfig().shop.customEnabled) {
            gui.setItem(LAYOUT.slot('E'), items.item(Material.ANVIL,
                    lang("menu.repository.button.custom.name"),
                    langList("menu.repository.button.custom.lore"),
                    event -> navigate(new MenuSpec.CustomTitle())));
        }
        gui.setItem(LAYOUT.slot('G'), items.item(Material.NAME_TAG,
                lang("menu.repository.button.redeem.name"),
                langList("menu.repository.button.redeem.lore"),
                event -> ctx.deps().redeemCodes().open(ctx.player(), spec())));
    }
}
