package net.baiyu.title.gui.wardrobe;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.WardrobeService;
import net.baiyu.title.service.WardrobeView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

import java.util.List;

/**
 * 装扮管理菜单（{@code /byt} 默认入口）。
 *
 * <p>X = 栏位占位符（启用 NAME_TAG / 禁用 BARRIER，从左到右栏位 1 起）：
 * 左键进入该栏位的称号选择菜单、右键开关栏位启用；P = 返回服务器主菜单；
 * A 箱子 → 仓库；B 金锭 → 商店；C 玩家头 → 个人信息聊天输出
 * （chat.personal-info 模板经 PAPI 内部解析器逐行展开）；D 铁砧 →
 * 自定义称号付款菜单（总开关关闭时隐藏入口）；E 纸 → 报价列表
 * （总开关关闭时拒绝）。菜单不做业务判断，只渲染 {@link WardrobeView}
 * 与转发点击。</p>
 */
public final class WardrobeMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "####P####",
            "#XXXXXXX#",
            "##ABCDE##");

    /** 栏位上限（构造时主线程解析的权限快照，带入异步 loadData）。 */
    private final int maxSlots;

    /** 装扮视图（loadData 异步装载）。 */
    private WardrobeView view;

    /**
     * 构造装扮管理菜单（主线程：同步解析栏位上限）。
     *
     * @param ctx 菜单上下文
     */
    public WardrobeMenu(MenuContext ctx) {
        super(ctx);
        this.maxSlots = ctx.deps().wardrobeViews().maxSlotsOf(ctx.player().getUniqueId());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.wardrobe.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.Wardrobe();
    }

    /** 异步预取装扮视图（纯缓存读，零 Bukkit）。 */
    @Override
    protected void loadData() {
        view = ctx.deps().wardrobeViews().getView(ctx.player().getUniqueId(), maxSlots);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('P'), mainMenuButton());
        paintSlots(gui);
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('A'), items.item(Material.CHEST,
                lang("menu.wardrobe.button.repository.name"),
                langList("menu.wardrobe.button.repository.lore"),
                event -> navigate(new MenuSpec.Repository(1))));
        gui.setItem(LAYOUT.slot('B'), items.item(Material.GOLD_INGOT,
                lang("menu.wardrobe.button.shop.name"),
                langList("menu.wardrobe.button.shop.lore"),
                event -> navigate(new MenuSpec.Shop(1))));
        gui.setItem(LAYOUT.slot('C'), items.playerHead(ctx.player(),
                lang("menu.wardrobe.button.info.name"),
                langList("menu.wardrobe.button.info.lore"),
                event -> ctx.deps().personalInfo().send(ctx.player())));
        if (ctx.deps().configHandler().getConfig().shop.customEnabled) {
            gui.setItem(LAYOUT.slot('D'), items.item(Material.ANVIL,
                    lang("menu.wardrobe.button.custom.name"),
                    langList("menu.wardrobe.button.custom.lore"),
                    event -> navigate(new MenuSpec.CustomTitle())));
        }
        gui.setItem(LAYOUT.slot('E'), items.item(Material.PAPER,
                lang("menu.wardrobe.button.trade.name"),
                langList("menu.wardrobe.button.trade.lore"),
                event -> openTradeList()));
    }

    /** E 报价列表入口：总开关关闭时拒绝并提示。 */
    private void openTradeList() {
        if (!ctx.deps().tradeViews().enabled()) {
            feedback("trade.disabled");
            return;
        }
        navigate(new MenuSpec.TradeList(1));
    }

    /** 渲染栏位占位符（从左到右栏位 1 起；超出布局容量的栏位不渲染）。 */
    private void paintSlots(BaseGui gui) {
        final int[] slots = LAYOUT.slots('X');
        final List<WardrobeView.SlotView> slotViews = view.slots();
        final int count = Math.min(slots.length, slotViews.size());
        for (int i = 0; i < count; i++) {
            gui.setItem(slots[i], slotItem(slotViews.get(i)));
        }
    }

    /** 构造单个栏位物品（启用 NAME_TAG / 禁用 BARRIER；左键选择、右键开关）。 */
    private GuiItem slotItem(WardrobeView.SlotView slot) {
        final Material material = slot.enabled() ? Material.NAME_TAG : Material.BARRIER;
        final Component name = lang("menu.wardrobe.slot.name",
                Placeholder.parsed("slot", Integer.toString(slot.slotNo())));
        final List<Component> lore = langList("menu.wardrobe.slot.lore",
                Placeholder.component("status", lang(slot.enabled()
                        ? "menu.wardrobe.slot.status.enabled"
                        : "menu.wardrobe.slot.status.disabled")),
                Placeholder.component("display", wearingDisplay(slot)));
        return ctx.deps().itemFactory().item(material, name, lore, event -> {
            if (event.isRightClick()) {
                toggleSlot(slot.slotNo());
            } else {
                navigate(new MenuSpec.TitleSelect(slot.slotNo()));
            }
        });
    }

    /** 栏位"当前佩戴"展示：随机 → 随机(池大小)；固定有效称号 → 带括号名；否则默认称号。 */
    private Component wearingDisplay(WardrobeView.SlotView slot) {
        final TitleItemRenderer renderer = ctx.deps().renderer();
        if (slot.isRandom()) {
            return lang("menu.wardrobe.display.random",
                    Placeholder.parsed("count", Integer.toString(slot.randomPool().size())));
        }
        return slot.fixedTitle() != null
                ? renderer.nameWithBrackets(slot.fixedTitle())
                : renderer.defaultTitleName();
    }

    /** 右键开关栏位：冷却闸门放行后提交变更，回主线程按结果刷新或提示。 */
    private void toggleSlot(int slotNo) {
        if (!acquireToggleCooldown()) {
            return;
        }
        ctx.deps().wardrobeViews().toggleSlot(ctx.player().getUniqueId(), slotNo)
                .whenComplete((result, failure) -> runSync(() -> {
                    if (!ctx.player().isOnline()) {
                        return;
                    }
                    if (failure != null) {
                        ctx.deps().plugin().getComponentLogger()
                                .warn("栏位开关失败（玩家 {}，栏位 {}）", ctx.player().getName(), slotNo, failure);
                        feedback("command.error.internal");
                        return;
                    }
                    if (result == WardrobeService.WardrobeResult.SUCCESS) {
                        refreshTabDisplay();
                        refresh();
                    } else {
                        feedback(resultKey(result));
                    }
                }));
    }

    /** 失败结果 → 语言键映射（switch 表达式穷尽性防呆）。 */
    static String resultKey(WardrobeService.WardrobeResult result) {
        return switch (result) {
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS 不是失败结果");
            case SLOT_OUT_OF_RANGE -> "menu.common.result.slot-out-of-range";
            case TITLE_NOT_OWNED -> "menu.common.result.title-not-owned";
        };
    }
}
