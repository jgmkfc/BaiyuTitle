package net.baiyu.title.gui.trade;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.trade.OfferItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 选择交易称号菜单：顺序 ①选己方 → ②选对方 →
 * ③确认报价（草稿详情）。
 *
 * <p>X = 该侧玩家的可交易称号（完整称号副本，类似仓库；不可交易与
 * 已失效项由视图门面排除）：点击选中 / 取消选中（附魔 = 选中），
 * 每侧至多 {@value TradeDraft#MAX_PER_SIDE} 个；A = 下一步（lore 提示
 * 下一阶段），从 OTHER 进入草稿详情时双侧全空则报错。设计无翻页键，
 * 超出 28 格的条目不展示（与玩家选择菜单同精神）。</p>
 */
public final class TradeTitleSelectMenu extends AbstractPagedMenu<TradeViewService.TradableTitle> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "######A##");

    /** 本菜单选择的草稿交易方。 */
    private final TradeDraft.Side side;

    /** 会话草稿（构造时主线程取得；流程外进入时为 null，条目降级为空）。 */
    private final @Nullable TradeDraft draft;

    /**
     * 构造选择交易称号菜单（主线程：取会话草稿并推进流程阶段）。
     *
     * @param ctx  菜单上下文
     * @param side 草稿交易方
     */
    public TradeTitleSelectMenu(MenuContext ctx, TradeDraft.Side side) {
        super(ctx, 1);
        this.side = Objects.requireNonNull(side, "草稿交易方不能为空");
        this.draft = ctx.session().tradeDraft();
        if (draft != null) {
            draft.setPhase(side == TradeDraft.Side.SELF
                    ? TradeDraft.Phase.SELECT_SELF : TradeDraft.Phase.SELECT_OTHER);
        }
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.trade-select.title",
                Placeholder.component("side", lang(side == TradeDraft.Side.SELF
                        ? "menu.trade-select.side.self" : "menu.trade-select.side.other")),
                Placeholder.unparsed("target", draft == null ? "?" : draft.targetName()));
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.TradeTitleSelect(side);
    }

    /** 布局无翻页键：屏蔽基类默认的 A/C 翻页角色（A 是下一步键）。 */
    @Override
    protected char prevRole() {
        return '\0';
    }

    /** 布局无翻页键：屏蔽基类默认的 A/C 翻页角色。 */
    @Override
    protected char nextRole() {
        return '\0';
    }

    /** 异步取该侧玩家的可交易称号（纯缓存读；草稿缺失时为空列表）。 */
    @Override
    protected List<TradeViewService.TradableTitle> fetchEntries() {
        if (draft == null) {
            return List.of();
        }
        return ctx.deps().tradeViews().tradableTitles(ownerUuid());
    }

    /** 称号条目：仓库版渲染 + 附魔 = 已选中；点击切换选中。 */
    @Override
    protected GuiItem renderEntry(TradeViewService.TradableTitle entry) {
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().warehouseItem(
                new TitleItemRenderer.OwnedTitleView(
                        entry.title(), entry.expiresOn(), entry.equipped()));
        final boolean selected = draft != null && draft.isSelected(side, entry.ownershipId());
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), selected, 1, event -> toggle(entry));
    }

    /** A 下一步键（lore 按阶段提示下一步是什么）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        if (draft == null) {
            feedback("menu.trade-select.draft-missing");
        }
        gui.setItem(LAYOUT.slot('A'), ctx.deps().itemFactory().item(Material.ARROW,
                lang("menu.trade-select.next.name"),
                langList(side == TradeDraft.Side.SELF
                        ? "menu.trade-select.next.lore.self"
                        : "menu.trade-select.next.lore.other"),
                event -> advance()));
    }

    /** 点击条目：增删切换，达每侧上限时报错。 */
    private void toggle(TradeViewService.TradableTitle entry) {
        if (draft == null) {
            feedback("menu.trade-select.draft-missing");
            back();
            return;
        }
        final TradeDraft.ToggleResult result = draft.toggle(side,
                new OfferItem(entry.ownershipId(), entry.title().id()));
        if (result == TradeDraft.ToggleResult.LIMIT_REACHED) {
            feedback("menu.trade-select.limit",
                    Placeholder.parsed("max", Integer.toString(TradeDraft.MAX_PER_SIDE)));
            return;
        }
        refresh();
    }

    /** A 下一步：SELF → 选对方；OTHER → 草稿详情（双侧全空报错）。 */
    private void advance() {
        if (draft == null) {
            feedback("menu.trade-select.draft-missing");
            back();
            return;
        }
        if (side == TradeDraft.Side.SELF) {
            navigate(new MenuSpec.TradeTitleSelect(TradeDraft.Side.OTHER));
            return;
        }
        if (draft.isEmpty()) {
            feedback("menu.trade-select.empty-offer");
            return;
        }
        navigate(new MenuSpec.TradeDetailDraft());
    }

    /** 该侧标的持有者：SELF = 自己，OTHER = 交易目标。 */
    private UUID ownerUuid() {
        return side == TradeDraft.Side.SELF
                ? ctx.player().getUniqueId()
                : Objects.requireNonNull(draft, "草稿不能为空").targetUuid();
    }
}
