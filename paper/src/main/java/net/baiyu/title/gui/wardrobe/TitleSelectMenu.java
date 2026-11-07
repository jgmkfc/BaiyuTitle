package net.baiyu.title.gui.wardrobe;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.core.SlotSelectState;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.SlotSelection;
import net.baiyu.title.service.WardrobeService;
import net.baiyu.title.service.WardrobeView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 称号选择菜单。
 *
 * <p>首格 = 随机佩戴入口（CHEST，切换 randomMode）；其余 X = 动态称号条目：
 * 普通模式左键单选暂选（附魔标记）、随机模式左键多选池增删、任意条目右键 =
 * 暂定卸下；A / C 翻页；B 红石 = 应用并返回（{@code applySlotSelection}）；
 * 直接 ESC 退出不保存（{@link #onClosed()} 丢弃暂存，§3.5）。</p>
 *
 * <p>暂存挂在 {@link SlotSelectState}（会话级）：翻页 / 点击刷新不丢失；
 * 应用成功或 ESC 才清除。</p>
 */
public final class TitleSelectMenu extends AbstractPagedMenu<TitleSelectMenu.Entry> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##A#B#C##");

    /** 目标栏位号（1 起）。 */
    private final int slotNo;

    /** 装扮视图（loadData 异步装载；含本栏位当前配置与持有称号）。 */
    private WardrobeView view;

    /** 栏位上限（构造时主线程解析，带入异步 loadData）。 */
    private final int maxSlots;

    /**
     * 构造称号选择菜单（主线程：同步解析栏位上限）。
     *
     * @param ctx    菜单上下文
     * @param slotNo 目标栏位号（1 起）
     */
    public TitleSelectMenu(MenuContext ctx, int slotNo) {
        super(ctx, 1);
        this.slotNo = slotNo;
        this.maxSlots = ctx.deps().wardrobeViews().maxSlotsOf(ctx.player().getUniqueId());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.select.title", Placeholder.parsed("slot", Integer.toString(slotNo)));
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.TitleSelect(slotNo);
    }

    /** 异步预取：先装载装扮视图，再交给分页基类整理条目。 */
    @Override
    protected void loadData() {
        view = ctx.deps().wardrobeViews().getView(ctx.player().getUniqueId(), maxSlots);
        super.loadData();
    }

    /** 条目 = 随机佩戴入口（恒为第 1 页首格，§11⑮）+ 全部持有称号。 */
    @Override
    protected List<Entry> fetchEntries() {
        final List<Entry> entries = new ArrayList<>(view.ownedTitles().size() + 1);
        entries.add(new RandomToggleEntry());
        for (WardrobeView.OwnedTitle owned : view.ownedTitles()) {
            entries.add(new TitleEntry(owned));
        }
        return entries;
    }

    @Override
    protected GuiItem renderEntry(Entry entry) {
        return switch (entry) {
            case RandomToggleEntry ignored -> randomToggleItem();
            case TitleEntry titleEntry -> titleItem(titleEntry.owned());
        };
    }

    /** 底栏 B 键：应用并返回（翻页 A / C 由分页基类按默认角色放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        gui.setItem(LAYOUT.slot('B'), ctx.deps().itemFactory().item(Material.REDSTONE,
                lang("menu.select.apply.name"),
                langList("menu.select.apply.lore"),
                event -> applyAndReturn()));
    }

    /** ESC 主动退出：丢弃未应用的暂存。 */
    @Override
    protected void onClosed() {
        ctx.session().clearSlotSelect();
    }

    /** 随机佩戴入口物品（CHEST；glint = 随机模式开启）。 */
    private GuiItem randomToggleItem() {
        final SlotSelectState state = state();
        final Component status = lang(state.isRandomMode()
                ? "menu.select.random.status.on"
                : "menu.select.random.status.off");
        return ctx.deps().itemFactory().item(Material.CHEST,
                lang("menu.select.random.name"),
                langList("menu.select.random.lore",
                        Placeholder.component("status", status),
                        Placeholder.parsed("count", Integer.toString(state.randomPool().size()))),
                state.isRandomMode(), 1,
                event -> {
                    state.setRandomMode(!state.isRandomMode());
                    refresh();
                });
    }

    /** 称号条目物品：左键选择 / 入池、右键暂定卸下。 */
    private GuiItem titleItem(WardrobeView.OwnedTitle owned) {
        final SlotSelectState state = state();
        final int titleId = owned.title().id();
        final boolean equippedInSlot = isFixedInThisSlot(titleId);
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().selectionItem(
                new TitleItemRenderer.OwnedTitleView(owned.title(), owned.expiresOn(), equippedInSlot),
                isSelected(state, titleId, equippedInSlot));
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1,
                event -> {
                    if (event.isRightClick()) {
                        state.markUnequip();
                    } else if (state.isRandomMode()) {
                        state.toggleRandom(titleId);
                    } else {
                        state.selectFixed(titleId);
                    }
                    refresh();
                });
    }

    /** 选中态判定：随机 = 在池中；普通 = 暂选项，未暂选时回落到当前佩戴项。 */
    private static boolean isSelected(SlotSelectState state, int titleId, boolean equippedInSlot) {
        if (state.isRandomMode()) {
            return state.randomPool().contains(titleId);
        }
        if (state.pendingTitleId() != null) {
            return state.pendingTitleId() == titleId;
        }
        return !state.isPendingUnequip() && equippedInSlot;
    }

    /** 该称号是否为本栏位当前固定佩戴项（操作行"点击卸下"判据）。 */
    private boolean isFixedInThisSlot(int titleId) {
        return view.slots().stream()
                .filter(slot -> slot.slotNo() == slotNo)
                .anyMatch(slot -> slot.fixedTitle() != null && slot.fixedTitle().id() == titleId);
    }

    /**
     * 取（或初始化）本栏位的暂存状态：首次进入按当前栏位配置播种随机模式与
     * 候选池；刷新 / 翻页复用既有暂存不重播。仅主线程调用。
     */
    private SlotSelectState state() {
        SlotSelectState state = ctx.session().slotSelect();
        if (state == null || state.slotNo() != slotNo) {
            state = ctx.session().beginSlotSelect(slotNo);
            seed(state);
        }
        return state;
    }

    /** 按当前栏位配置播种暂存（随机模式 + 现有候选池）。 */
    private void seed(SlotSelectState state) {
        view.slots().stream()
                .filter(slot -> slot.slotNo() == slotNo)
                .findFirst()
                .filter(WardrobeView.SlotView::isRandom)
                .ifPresent(slot -> {
                    state.setRandomMode(true);
                    slot.randomPool().forEach(state::toggleRandom);
                });
    }

    /** B 键：把暂存折叠为选择意图并提交；无更改则直接返回。 */
    private void applyAndReturn() {
        final SlotSelectState state = state();
        final SlotSelection selection;
        if (state.isRandomMode()) {
            selection = new SlotSelection.Random(Set.copyOf(state.randomPool()));
        } else if (state.isPendingUnequip()) {
            selection = new SlotSelection.Unequip();
        } else if (state.pendingTitleId() != null) {
            selection = new SlotSelection.Fixed(state.pendingTitleId());
        } else {
            // 无任何更改：不落库，直接清暂存返回
            ctx.session().clearSlotSelect();
            back();
            return;
        }
        // 确有变更才计冷却：反复点"应用"但不改内容不应被节流干扰。
        if (!acquireToggleCooldown()) {
            return;
        }
        ctx.deps().wardrobeViews().applySlotSelection(ctx.player().getUniqueId(), slotNo, selection)
                .whenComplete((result, failure) -> runSync(() -> handleApplyResult(result, failure)));
    }

    /** 应用结果回调（主线程）：成功清暂存返回；失败提示并刷新为最新数据。 */
    private void handleApplyResult(WardrobeService.WardrobeResult result, Throwable failure) {
        if (!ctx.player().isOnline()) {
            return;
        }
        if (failure != null) {
            ctx.deps().plugin().getComponentLogger()
                    .warn("装扮应用失败（玩家 {}，栏位 {}）", ctx.player().getName(), slotNo, failure);
            feedback("command.error.internal");
            return;
        }
        if (result == WardrobeService.WardrobeResult.SUCCESS) {
            ctx.session().clearSlotSelect();
            refreshTabDisplay();
            feedback("menu.select.apply-success");
            back();
        } else {
            feedback(WardrobeMenu.resultKey(result));
            refresh();
        }
    }

    /** 条目类型（首格随机入口 + 称号条目的密封和类型）。 */
    sealed interface Entry {
    }

    /** 随机佩戴入口条目（恒为条目列表首元素）。 */
    record RandomToggleEntry() implements Entry {
    }

    /**
     * 称号条目。
     *
     * @param owned 持有称号视图
     */
    record TitleEntry(WardrobeView.OwnedTitle owned) implements Entry {
    }
}
