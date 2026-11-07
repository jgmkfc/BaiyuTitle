package net.baiyu.title.gui.trade;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.service.TradeDetailView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 报价详情菜单——已建报价（RECEIVER / VIEWER 两态）。
 *
 * <p>报价不可二次修改：标的点击仅切换附魔浏览标记，
 * 不移除不跳转。RECEIVER（PENDING 报价的目标方）：左侧 = 己方标的、
 * 右侧 = 发起方标的，C 同意 / D 拒绝（都经确认菜单二次确认）；
 * VIEWER（发起方回看 / 管理端 / 终态报价）：左侧 = 发起方、右侧 = 目标方，
 * C = 状态信息（只读），D = 边框。</p>
 */
public final class TradeDetailOfferMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "##A###B##",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "##C###D##");

    /** 交易 id。 */
    private final long offerId;

    /** 详情视图（loadData 异步装载；交易不存在时为 null）。 */
    private @Nullable TradeDetailView view;

    /** 浏览标记（所有权 id；实例内存活，点击切换附魔）。 */
    private final Set<Long> marks = new HashSet<>();

    /**
     * 构造已建报价详情菜单。
     *
     * @param ctx     菜单上下文
     * @param offerId 交易 id
     */
    public TradeDetailOfferMenu(MenuContext ctx, long offerId) {
        super(ctx);
        this.offerId = offerId;
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.trade-detail.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.TradeDetail(offerId);
    }

    /** 异步装载详情视图（视图门面内部走 DB 读线程池）。 */
    @Override
    protected void loadData() {
        view = ctx.deps().tradeViews().detail(offerId, ctx.player().getUniqueId()).orElse(null);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        if (view == null) {
            feedback("menu.trade-detail.missing");
            return;
        }
        final boolean receiver = view.role() == TradeDetailView.Role.RECEIVER;
        // 接收方视角：左侧 = 己方（目标方）标的；其余视角保持发起方在左
        paintHead(gui, 'A', receiver ? view.targetUuid() : view.initiatorUuid(),
                receiver ? view.targetName() : view.initiatorName(),
                receiver ? view.targetItems().size() : view.initiatorItems().size());
        paintHead(gui, 'B', receiver ? view.initiatorUuid() : view.targetUuid(),
                receiver ? view.initiatorName() : view.targetName(),
                receiver ? view.initiatorItems().size() : view.targetItems().size());
        paintItems(gui, 'X', receiver ? view.targetItems() : view.initiatorItems());
        paintItems(gui, 'Y', receiver ? view.initiatorItems() : view.targetItems());
        if (receiver) {
            paintReceiverButtons(gui);
        } else {
            paintViewerInfo(gui);
        }
    }

    /** 双方玩家头（只读；lore 含该侧标的数）。 */
    private void paintHead(BaseGui gui, char role, UUID uuid, String name, int count) {
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().playerHead(
                Bukkit.getOfflinePlayer(uuid),
                lang("menu.trade-detail.head.name", Placeholder.unparsed("player", name)),
                langList("menu.trade-detail.head.offer-lore",
                        Placeholder.parsed("count", Integer.toString(count))),
                null));
    }

    /** 逐槽渲染一侧标的（点击仅切换浏览标记，原位更新不重查）。 */
    private void paintItems(BaseGui gui, char role, List<TradeDetailView.ItemView> items) {
        final int[] slots = LAYOUT.slots(role);
        final int count = Math.min(slots.length, items.size());
        for (int i = 0; i < count; i++) {
            final int slot = slots[i];
            final TradeDetailView.ItemView item = items.get(i);
            gui.setItem(slot, offerItem(gui, slot, item));
        }
    }

    /** 单个标的物品（附魔 = 已标记；点击切换后原位重绘）。 */
    private GuiItem offerItem(BaseGui gui, int slot, TradeDetailView.ItemView item) {
        return ctx.deps().itemFactory().item(item.icon(), item.name(),
                langList("menu.trade-detail.item.mark-lore"),
                marks.contains(item.ownershipId()), 1, event -> {
                    if (!marks.remove(item.ownershipId())) {
                        marks.add(item.ownershipId());
                    }
                    gui.updateItem(slot, offerItem(gui, slot, item));
                });
    }

    /** RECEIVER：C 同意 / D 拒绝（均二次确认）。 */
    private void paintReceiverButtons(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        final TagResolver player = Placeholder.unparsed("player", view.initiatorName());
        gui.setItem(LAYOUT.slot('C'), items.item(Material.LIME_STAINED_GLASS_PANE,
                lang("menu.trade-detail.button.accept.name"),
                langList("menu.trade-detail.button.accept.lore"),
                event -> confirm(ConfirmRequest.of(
                        lang("menu.trade-detail.confirm.accept.name", player),
                        langList("menu.trade-detail.confirm.accept.lore", player),
                        () -> ctx.deps().tradeFlow().accept(ctx.player(), offerId, this::settleBack)))));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.RED_STAINED_GLASS_PANE,
                lang("menu.trade-detail.button.reject.name"),
                langList("menu.trade-detail.button.reject.lore"),
                event -> confirm(ConfirmRequest.of(
                        lang("menu.trade-detail.confirm.reject.name", player),
                        langList("menu.trade-detail.confirm.reject.lore", player),
                        () -> ctx.deps().tradeFlow().reject(ctx.player(), offerId, this::settleBack)))));
    }

    /** VIEWER：C 状态信息（只读），D 归入边框。 */
    private void paintViewerInfo(BaseGui gui) {
        gui.setItem(LAYOUT.slot('C'), ctx.deps().itemFactory().item(Material.PAPER,
                lang("menu.trade-detail.status.name",
                        Placeholder.component("status", lang(view.statusKey()))),
                langList("menu.trade-detail.status.lore",
                        Placeholder.parsed("created", view.createdText()),
                        Placeholder.parsed("expires", view.expiresText()),
                        Placeholder.unparsed("by", view.resolvedByText())),
                null));
        gui.setItem(LAYOUT.slot('D'), ctx.deps().buttons().border());
    }

    /** 同意 / 拒绝落定：确认菜单仍在前台时 back 弹栈重建本菜单（状态即时反映）。 */
    private void settleBack() {
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
            back();
        }
    }
}
