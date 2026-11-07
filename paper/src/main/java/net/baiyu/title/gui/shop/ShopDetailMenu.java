package net.baiyu.title.gui.shop;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.ShopQueryService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 商店详情菜单。
 *
 * <p>T = 商店条目完整副本（同一渲染链，不可交互）；H 书 = 时间信息
 * （创建时间 / 获取窗口 / 持有有效期，仅悬停）；I 书 = 统计信息
 * （点击进入购买者列表菜单）；J 书 = 隐藏信息（物品名 = 称号 ID 序数，
 * 内部 ID / 种类 / 所属组 / 权限节点 / 创建者 / 可交易 / 已售，仅悬停）；
 * A = 返回商店（back 弹栈重建，页码随规格恢复；返回物统一 REDSTONE——§11⑥）。</p>
 *
 * <p>详情含隐藏称号（由商店条目进入，查询只挡软删）；进入后称号被删除的
 * 竞态按缺失提示 + 仅渲染返回键降级。</p>
 */
public final class ShopDetailMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#T#HIJZZ#",
            "##A######");

    /** 目标称号 id。 */
    private final int titleId;

    /** 玩家商店上下文（构造时主线程收集）。 */
    private final ShopQueryService.ShopContext context;

    /** 详情视图（loadData 异步装载；称号缺失时为 null）。 */
    private @Nullable ShopDetailView view;

    /**
     * 构造商店详情菜单（主线程：收集玩家上下文）。
     *
     * @param ctx     菜单上下文
     * @param titleId 目标称号 id
     */
    public ShopDetailMenu(MenuContext ctx, int titleId) {
        super(ctx);
        this.titleId = titleId;
        this.context = ctx.deps().shopViews().collectContext(ctx.player());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.shop.detail.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.ShopDetail(titleId);
    }

    /** 异步装载详情视图（只挡软删；缺失置 null 降级渲染）。 */
    @Override
    protected void loadData() {
        view = ctx.deps().shopViews()
                .detail(ctx.player().getUniqueId(), context, titleId)
                .orElse(null);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('A'), ctx.deps().buttons().back(event -> back()));
        if (view == null) {
            feedback("menu.shop.detail.missing");
            return;
        }
        final ShopEntryView entry = view.entry();
        final GuiItemFactory items = ctx.deps().itemFactory();
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().shopItem(entry);
        gui.setItem(LAYOUT.slot('T'), items.item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1, null));
        gui.setItem(LAYOUT.slot('H'), items.item(Material.BOOK,
                lang("menu.shop.detail.time.name"),
                langList("menu.shop.detail.time.lore",
                        Placeholder.parsed("created", dateText(entry.title().createdAt())),
                        Placeholder.parsed("window", windowText(entry.title())),
                        Placeholder.parsed("duration", durationText(entry.title().holdDurationDays()))),
                null));
        gui.setItem(LAYOUT.slot('I'), items.item(Material.BOOK,
                lang("menu.shop.detail.stats.name"),
                langList("menu.shop.detail.stats.lore"),
                event -> navigate(new MenuSpec.BuyerList(titleId, 1))));
        gui.setItem(LAYOUT.slot('J'), hiddenInfoItem(entry.title()));
    }

    /** J 键隐藏信息（物品名 = 称号 ID 序数——业务表格备注）。 */
    private GuiItem hiddenInfoItem(Title title) {
        final String none = raw("menu.shop.detail.value.none");
        return ctx.deps().itemFactory().item(Material.BOOK,
                lang("menu.shop.detail.hidden.name",
                        Placeholder.parsed("id", Integer.toString(title.id()))),
                langList("menu.shop.detail.hidden.lore",
                        Placeholder.parsed("id", Integer.toString(title.id())),
                        Placeholder.parsed("category", categoryText(title)),
                        Placeholder.parsed("group", none),
                        Placeholder.parsed("node", permissionNodeText(title, none)),
                        Placeholder.parsed("creator", title.createdByName() != null
                                ? title.createdByName()
                                : raw("menu.shop.detail.value.console")),
                        Placeholder.parsed("tradable", raw(title.tradable()
                                ? "menu.shop.detail.value.yes" : "menu.shop.detail.value.no")),
                        Placeholder.parsed("sold", Integer.toString(title.soldCount()))),
                null);
    }

    /** 称号种类展示文案。 */
    private String categoryText(Title title) {
        return raw(switch (title.category()) {
            case NORMAL -> "menu.shop.detail.category.normal";
            case BOND -> "menu.shop.detail.category.bond";
            case GROUP -> "menu.shop.detail.category.group";
            case CUSTOM -> "menu.shop.detail.category.custom";
        });
    }

    /** 权限条件节点（无权限条件时展示"无"）。 */
    private static String permissionNodeText(Title title, String none) {
        for (PurchaseCondition condition : title.conditions()) {
            if (condition instanceof PermissionCondition permission) {
                return permission.node();
            }
        }
        return none;
    }

    /** 创建时刻 → 日期文案（UTC 存储，展示按服务器本地时区；format.date 值级插值）。 */
    private String dateText(long epochMillis) {
        final LocalDate date = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        return raw("format.date")
                .replace("<year>", Integer.toString(date.getYear()))
                .replace("<month>", Integer.toString(date.getMonthValue()))
                .replace("<day>", Integer.toString(date.getDayOfMonth()));
    }

    /** 获取窗口文案：WINDOW → 起止范围；其余 → "无"。 */
    private String windowText(Title title) {
        if (title.limitMode() != LimitMode.WINDOW) {
            return raw("menu.shop.detail.value.none");
        }
        return raw("menu.shop.detail.value.window-range")
                .replace("<start>", windowDateText(title.windowStart()))
                .replace("<end>", windowDateText(title.windowEnd()));
    }

    /** 窗口日期文案（format.window-date 值级插值；null = 空串——只有截止日的窗口）。 */
    private String windowDateText(@Nullable LocalDate date) {
        if (date == null) {
            return "";
        }
        return raw("format.window-date")
                .replace("<year>", Integer.toString(date.getYear()))
                .replace("<month>", Integer.toString(date.getMonthValue()))
                .replace("<day>", Integer.toString(date.getDayOfMonth()));
    }

    /** 持有有效期文案：0 = 永久；365 整数倍按年、否则按天（与商店 lore 一致）。 */
    private String durationText(int days) {
        if (days == 0) {
            return raw("format.permanent");
        }
        if (days % 365 == 0) {
            return raw("format.duration-years").replace("<years>", Integer.toString(days / 365));
        }
        return raw("format.duration-days").replace("<days>", Integer.toString(days));
    }

    /** 原始模板取词便捷转发。 */
    private String raw(String key) {
        return ctx.deps().lang().raw(key);
    }
}
