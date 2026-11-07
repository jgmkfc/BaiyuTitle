package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.gui.shop.ShopDetailView;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.input.ChatInputRequest;
import net.baiyu.title.input.HeldItemInputRequest;
import net.baiyu.title.input.InputValidator;
import net.baiyu.title.input.InputValidators;
import net.baiyu.title.service.AdminTitleService;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.TitleEditorView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 管理详情（称号编辑器）菜单。
 *
 * <p>T = 商店条目完整 lore 副本（同一渲染链，不可交互）；X = 编辑项
 * （19 项分 2 页——裁决⑩：第 1 页项 1~9、第 2 页项 11~19，项号 10 为
 * 设计文档预留空号；构建见 {@link EditorButtons}）；A / C = 编辑页翻页；
 * B 红石 = 返回管理主菜单（back 弹栈重建）。</p>
 *
 * <p>写操作时序（接缝约定）：聊天输入完成后输入管理器先跑 onComplete
 * 再 replace 回本菜单，服务 future 落定时经 {@link #write} 再刷新一次当前
 * 编辑器——写穿缓存下窗口极小，两次重建保证 lore 即时反映最新值。</p>
 */
public final class AdminTitleEditorMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#ZZZTZZZ#",
            "#ZXXXXXZ#",
            "#ZXXXXXZ#",
            "#ZZZZZZZ#",
            "##A#B#C##");

    /** 编辑页总数（19 项分 2 页）。 */
    static final int EDITOR_PAGES = 2;

    /** 目标称号 id。 */
    private final int titleId;

    /** 当前编辑页（1 起，夹取到 {@value EDITOR_PAGES} 页内）。 */
    private int editorPage;

    /** 玩家商店上下文（构造时主线程收集；T 副本渲染用）。 */
    private final ShopQueryService.ShopContext context;

    /** 编辑器视图（loadData 异步装载；称号缺失时为 null）。 */
    private @Nullable TitleEditorView view;

    /** T 副本条目视图（loadData 异步装载；称号缺失时为 null）。 */
    private @Nullable ShopEntryView detailEntry;

    /**
     * 构造称号编辑器菜单（主线程：收集玩家上下文）。
     *
     * @param ctx        菜单上下文
     * @param titleId    目标称号 id
     * @param editorPage 编辑页码（1 起）
     */
    public AdminTitleEditorMenu(MenuContext ctx, int titleId, int editorPage) {
        super(ctx);
        this.titleId = titleId;
        this.editorPage = Math.clamp(editorPage, 1, EDITOR_PAGES);
        this.context = ctx.deps().shopViews().collectContext(ctx.player());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.editor.title", Placeholder.unparsed("id", Integer.toString(titleId)));
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.AdminEditor(titleId, editorPage);
    }

    /** 异步装载编辑器视图与 T 副本条目（均只读缓存快照）。 */
    @Override
    protected void loadData() {
        view = ctx.deps().adminViews().editorView(titleId).orElse(null);
        detailEntry = ctx.deps().shopViews()
                .detail(ctx.player().getUniqueId(), context, titleId)
                .map(ShopDetailView::entry)
                .orElse(null);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().back(event -> back()));
        if (view == null) {
            feedback("menu.admin.editor.missing");
            return;
        }
        if (detailEntry != null) {
            final TitleItemRenderer.RenderedItem copy = ctx.deps().renderer().shopItem(detailEntry);
            gui.setItem(LAYOUT.slot('T'), ctx.deps().itemFactory()
                    .item(copy.material(), copy.name(), copy.lore(), copy.glint(), 1, null));
        }
        gui.setItem(LAYOUT.slot('A'), ctx.deps().buttons().prevPage(event -> flipTo(editorPage - 1)));
        gui.setItem(LAYOUT.slot('C'), ctx.deps().buttons().nextPage(event -> flipTo(editorPage + 1)));
        final int[] slots = LAYOUT.slots('X');
        final List<GuiItem> items = new EditorButtons(this, ctx, view).page(editorPage);
        for (int i = 0; i < items.size() && i < slots.length; i++) {
            gui.setItem(slots[i], items.get(i));
        }
    }

    /** 编辑页翻页：夹取到合法区间，变更时写回会话规格并刷新。 */
    private void flipTo(int target) {
        final int clamped = Math.clamp(target, 1, EDITOR_PAGES);
        if (clamped == editorPage) {
            return;
        }
        editorPage = clamped;
        ctx.session().setCurrent(new MenuSpec.AdminEditor(titleId, clamped), false);
        refresh();
    }

    // ---- EditorButtons 接缝（包内可见） ----

    /** @return 目标称号 id */
    int titleId() {
        return titleId;
    }

    /**
     * 开启聊天输入编辑：完成回调触发写操作并挂 {@link #write} 收尾，
     * 完成 / 取消后输入管理器自动 replace 回本编辑器（returnTo 带当前页）。
     *
     * @param promptSuffix 提示键后缀（{@code menu.admin.editor.prompt.<suffix>}）
     * @param validator    输入校验器
     * @param operation    输入文本 → 服务写操作 future
     */
    void chatEdit(String promptSuffix, InputValidator validator,
                  Function<String, CompletableFuture<?>> operation) {
        ctx.deps().inputs().openChatInput(ctx.player(), new ChatInputRequest(
                "menu.admin.editor.prompt." + promptSuffix, validator,
                input -> write(operation.apply(input)),
                () -> {
                },
                new MenuSpec.AdminEditor(titleId, editorPage)));
    }

    /** 开启手持物品左键确认编辑；完成与取消均返回当前编辑页。 */
    void heldItemEdit(String promptSuffix,
                      Function<ItemStack, CompletableFuture<?>> operation) {
        ctx.deps().inputs().openHeldItemInput(ctx.player(), new HeldItemInputRequest(
                "menu.admin.editor.prompt." + promptSuffix,
                item -> write(operation.apply(item)),
                () -> {
                },
                new MenuSpec.AdminEditor(titleId, editorPage)));
    }

    /** 物品价格链式输入：先录数量，再切换到持物左键确认，最后执行写操作。 */
    void itemCostEdit(BiFunction<ItemStack, Integer, CompletableFuture<?>> operation) {
        final MenuSpec returnTo = new MenuSpec.AdminEditor(titleId, editorPage);
        ctx.deps().inputs().openChatInput(ctx.player(), new ChatInputRequest(
                "menu.admin.editor.prompt.price-item",
                InputValidators.integerRange(1, AdminTitleService.MAX_ITEM_COST_AMOUNT),
                input -> {
                    final int amount = InputValidators.parseInteger(input).orElseThrow().intValue();
                    ctx.deps().inputs().openHeldItemInput(ctx.player(), new HeldItemInputRequest(
                            "menu.admin.editor.prompt.price-item-held",
                            item -> write(operation.apply(item, amount)),
                            () -> {
                            },
                            returnTo));
                },
                () -> {
                },
                returnTo,
                false));
    }

    /**
     * 写操作收尾：future 落定后回主线程——失败告警 + 提示，随后若玩家仍停留
     * 在本称号的编辑器且不在输入会话中，原地替换重建（lore 即时反映最新值）。
     *
     * @param operation 服务写操作 future
     */
    void write(CompletableFuture<?> operation) {
        operation.whenComplete((result, failure) -> runSync(() -> {
            if (failure != null) {
                ctx.deps().plugin().getComponentLogger()
                        .warn("管理编辑操作失败（称号 {}）", titleId, failure);
                feedback("menu.admin.editor.failed");
            }
            refreshIfCurrent();
        }));
    }

    /** 给玩家发一条编辑器提示（EditorButtons 转发 feedback）。 */
    void hint(String key) {
        feedback(key);
    }

    /** 导航到子菜单（EditorButtons 转发 navigate——protected 跨类不可见）。 */
    void goTo(MenuSpec spec) {
        navigate(spec);
    }

    /** 玩家仍停留在本称号编辑器且不在输入会话中时，原地替换重建取最新数据。 */
    private void refreshIfCurrent() {
        if (!ctx.player().isOnline() || ctx.session().isExpectingInput()) {
            return;
        }
        if (ctx.session().currentSpec() instanceof MenuSpec.AdminEditor current
                && current.titleId() == titleId) {
            ctx.deps().router().replace(ctx.player(),
                    new MenuSpec.AdminEditor(titleId, current.editorPage()));
        }
    }
}
