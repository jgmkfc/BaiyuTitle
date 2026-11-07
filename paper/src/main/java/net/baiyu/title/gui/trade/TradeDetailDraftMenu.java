package net.baiyu.title.gui.trade;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.trade.OfferItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价详情菜单——草稿态。
 *
 * <p>A / B = 双方玩家头（lore：玩家名、称号总数、选中称号数；点击回到
 * 对应的选择称号菜单作修改）；X / Y = 双方已选标的（完整称号副本，
 * 点击移除）；C = 确认发起（进入确认菜单二次确认 → 创建报价 → 清草稿 →
 * 报价列表 + 对方聊天可点击通知）；D = 取消发起（清草稿回报价列表）。</p>
 */
public final class TradeDetailDraftMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "##A###B##",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "#XXX#YYY#",
            "##C###D##");

    /** 会话草稿（构造时主线程取得；流程外进入时为 null）。 */
    private final @Nullable TradeDraft draft;

    /** 己方可交易称号索引（所有权 id → 视图；loadData 异步装载）。 */
    private Map<Long, TradeViewService.TradableTitle> selfIndex = Map.of();

    /** 对方可交易称号索引（所有权 id → 视图；loadData 异步装载）。 */
    private Map<Long, TradeViewService.TradableTitle> otherIndex = Map.of();

    /** 己方称号总数（有效持有；loadData 异步装载）。 */
    private int selfOwnedTotal;

    /** 对方称号总数（有效持有；loadData 异步装载）。 */
    private int otherOwnedTotal;

    /**
     * 构造草稿详情菜单（主线程：取会话草稿并推进流程阶段）。
     *
     * @param ctx 菜单上下文
     */
    public TradeDetailDraftMenu(MenuContext ctx) {
        super(ctx);
        this.draft = ctx.session().tradeDraft();
        if (draft != null) {
            draft.setPhase(TradeDraft.Phase.CONFIRM);
        }
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
        return new MenuSpec.TradeDetailDraft();
    }

    /** 异步预取双方可交易称号索引与持有总数（纯缓存读）。 */
    @Override
    protected void loadData() {
        if (draft == null) {
            return;
        }
        final TradeViewService views = ctx.deps().tradeViews();
        selfIndex = index(views.tradableTitles(ctx.player().getUniqueId()));
        otherIndex = index(views.tradableTitles(draft.targetUuid()));
        selfOwnedTotal = ctx.deps().wardrobeViews().ownedTitles(ctx.player().getUniqueId()).size();
        otherOwnedTotal = ctx.deps().wardrobeViews().ownedTitles(draft.targetUuid()).size();
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        if (draft == null) {
            feedback("menu.trade-select.draft-missing");
            return;
        }
        paintHeads(gui);
        paintItems(gui, 'X', TradeDraft.Side.SELF, selfIndex);
        paintItems(gui, 'Y', TradeDraft.Side.OTHER, otherIndex);
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('C'), items.item(Material.LIME_STAINED_GLASS_PANE,
                lang("menu.trade-detail.button.confirm.name"),
                langList("menu.trade-detail.button.confirm.lore"),
                event -> confirmCreate()));
        gui.setItem(LAYOUT.slot('D'), items.item(Material.RED_STAINED_GLASS_PANE,
                lang("menu.trade-detail.button.cancel.name"),
                langList("menu.trade-detail.button.cancel.lore"),
                event -> cancelDraft()));
    }

    /** 可交易称号列表 → 所有权 id 索引。 */
    private static Map<Long, TradeViewService.TradableTitle> index(
            List<TradeViewService.TradableTitle> titles) {
        final Map<Long, TradeViewService.TradableTitle> byOwnership = new HashMap<>();
        for (TradeViewService.TradableTitle view : titles) {
            byOwnership.put(view.ownershipId(), view);
        }
        return byOwnership;
    }

    /** A / B 双方玩家头：lore 含总数与选中数，点击回对应选择菜单。 */
    private void paintHeads(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        gui.setItem(LAYOUT.slot('A'), items.playerHead(ctx.player(),
                lang("menu.trade-detail.head.name",
                        Placeholder.unparsed("player", ctx.player().getName())),
                langList("menu.trade-detail.head.draft-lore",
                        Placeholder.parsed("total", Integer.toString(selfOwnedTotal)),
                        Placeholder.parsed("selected",
                                Integer.toString(draft.count(TradeDraft.Side.SELF)))),
                event -> navigate(new MenuSpec.TradeTitleSelect(TradeDraft.Side.SELF))));
        gui.setItem(LAYOUT.slot('B'), items.playerHead(
                Bukkit.getOfflinePlayer(draft.targetUuid()),
                lang("menu.trade-detail.head.name",
                        Placeholder.unparsed("player", draft.targetName())),
                langList("menu.trade-detail.head.draft-lore",
                        Placeholder.parsed("total", Integer.toString(otherOwnedTotal)),
                        Placeholder.parsed("selected",
                                Integer.toString(draft.count(TradeDraft.Side.OTHER)))),
                event -> navigate(new MenuSpec.TradeTitleSelect(TradeDraft.Side.OTHER))));
    }

    /** 逐槽渲染某侧已选标的（视图索引缺失的项跳过——创建时服务端会复核）。 */
    private void paintItems(BaseGui gui, char role, TradeDraft.Side side,
                            Map<Long, TradeViewService.TradableTitle> viewIndex) {
        final int[] slots = LAYOUT.slots(role);
        final List<OfferItem> selected = draft.items(side);
        int slotIndex = 0;
        for (OfferItem item : selected) {
            if (slotIndex >= slots.length) {
                break;
            }
            final TradeViewService.TradableTitle view = viewIndex.get(item.ownershipId());
            if (view == null) {
                continue;
            }
            final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().warehouseItem(
                    new TitleItemRenderer.OwnedTitleView(
                            view.title(), view.expiresOn(), view.equipped()));
            gui.setItem(slots[slotIndex++], ctx.deps().itemFactory().item(
                    rendered.material(), rendered.name(), rendered.lore(), false, 1,
                    event -> {
                        draft.remove(side, item.ownershipId());
                        refresh();
                    }));
        }
    }

    /** C 确认发起：双侧全空报错；否则进入确认菜单二次确认。 */
    private void confirmCreate() {
        if (draft.isEmpty()) {
            feedback("menu.trade-select.empty-offer");
            return;
        }
        final TagResolver[] resolvers = {
                Placeholder.unparsed("target", draft.targetName()),
                Placeholder.parsed("mine", Integer.toString(draft.count(TradeDraft.Side.SELF))),
                Placeholder.parsed("theirs", Integer.toString(draft.count(TradeDraft.Side.OTHER)))};
        confirm(ConfirmRequest.of(
                lang("menu.trade-detail.confirm.create.name", resolvers),
                langList("menu.trade-detail.confirm.create.lore", resolvers),
                this::create));
    }

    /** 确认后创建报价：成功清草稿回报价列表；被拒绝回到草稿详情调整。 */
    private void create() {
        ctx.deps().tradeFlow().create(ctx.player(), draft.targetUuid(), draft.targetName(),
                draft.items(TradeDraft.Side.SELF), draft.items(TradeDraft.Side.OTHER),
                created -> {
                    if (!ctx.player().isOnline()) {
                        return;
                    }
                    if (created) {
                        ctx.session().clearTradeDraft();
                        resetToList();
                    } else if (ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
                        back();
                    }
                });
    }

    /** D 取消发起：清草稿并回报价列表。 */
    private void cancelDraft() {
        ctx.session().clearTradeDraft();
        resetToList();
    }

    /**
     * 回到报价列表：创建链的中间菜单（选玩家 / 选称号 / 草稿详情）不应
     * 留在返回栈里（草稿已清，回退无意义），故清空导航状态后重开列表。
     */
    private void resetToList() {
        ctx.session().clearNavigation();
        ctx.deps().router().open(ctx.player(), new MenuSpec.TradeList(1));
    }
}
