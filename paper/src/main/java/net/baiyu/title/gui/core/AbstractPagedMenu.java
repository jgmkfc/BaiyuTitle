package net.baiyu.title.gui.core;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;

import java.util.List;

/**
 * 分页菜单基类：条目异步预取 + 主线程逐条渲染 + 翻页。
 *
 * <p>条目槽位 = 布局中 {@link #entryRole()}（默认 'X'）的全部槽位，
 * 每页容量即槽位数；上一页 / 下一页按钮角色由子类按布局覆写
 * （默认 A / C，对应 {@code ##A#B#C##} 式底栏）。翻页时经
 * {@link #onPageChanged(int)} 把新页码写回会话状态（子类落到
 * ShopViewState 等具体字段），再 {@link #refresh()} 重取数据。</p>
 *
 * @param <T> 条目视图对象类型（服务预计算，菜单不做业务判断）
 */
public abstract class AbstractPagedMenu<T> extends AbstractMenu {

    /** 预取的全部条目（loadData 异步写入，主线程经调度器交接后读取）。 */
    private List<T> entries = List.of();

    /** 当前页码（1 起）。 */
    private int page;

    /**
     * 构造分页菜单。
     *
     * @param ctx         菜单上下文
     * @param initialPage 初始页码（1 起；通常来自 MenuSpec 的 page 参数）
     */
    protected AbstractPagedMenu(MenuContext ctx, int initialPage) {
        super(ctx);
        this.page = Math.max(1, initialPage);
    }

    /** @return 全量条目列表（异步线程调用；服务查询走这里） */
    protected abstract List<T> fetchEntries();

    /**
     * 渲染单个条目（主线程调用）。
     *
     * @param entry 条目视图对象
     * @return GUI 物品（含点击回调）
     */
    protected abstract GuiItem renderEntry(T entry);

    /** @return 条目角色字符（默认 'X'，与字符画布局一致） */
    protected char entryRole() {
        return 'X';
    }

    /** @return 上一页按钮角色（默认 'A'；子类按布局覆写） */
    protected char prevRole() {
        return 'A';
    }

    /** @return 下一页按钮角色（默认 'C'；子类按布局覆写） */
    protected char nextRole() {
        return 'C';
    }

    /**
     * 页码变更回调（翻页点击时、refresh 之前调用）：
     * 子类在此把新页码写回会话状态（如 ShopViewState.page），
     * 使 back 重建 / 规格重放能落在正确页。
     *
     * @param newPage 新页码（1 起）
     */
    protected void onPageChanged(int newPage) {
    }

    /**
     * 追加填充钩子（主线程；条目与翻页之外的功能键在此放置）。
     *
     * @param gui 目标 GUI
     */
    protected void populateExtras(BaseGui gui) {
    }

    /** @return 当前页码（1 起） */
    protected final int page() {
        return page;
    }

    /** @return 总页数（至少 1） */
    protected final int pageCount() {
        final int pageSize = pageSize();
        if (pageSize == 0) {
            return 1;
        }
        return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
    }

    /** @return 当前页条目（防御性子列表拷贝；测试与子类统计用） */
    protected final List<T> currentPageEntries() {
        final int pageSize = pageSize();
        final int from = Math.min((page - 1) * pageSize, entries.size());
        final int to = Math.min(from + pageSize, entries.size());
        return List.copyOf(entries.subList(from, to));
    }

    /** 异步预取条目（子类覆写时须先调 super）。 */
    @Override
    protected void loadData() {
        entries = List.copyOf(fetchEntries());
    }

    /** 主线程填充：边框 → 页码夹取 → 条目 → 翻页键 → 子类附加键。 */
    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        page = Math.min(page, pageCount());
        paintEntries(gui);
        paintPagination(gui);
        populateExtras(gui);
    }

    /** 逐槽渲染当前页条目（不足一页的尾部槽位留空）。 */
    private void paintEntries(BaseGui gui) {
        final int[] slots = layout().slots(entryRole());
        final int from = (page - 1) * slots.length;
        for (int i = 0; i < slots.length; i++) {
            final int index = from + i;
            if (index >= entries.size()) {
                break;
            }
            gui.setItem(slots[i], renderEntry(entries.get(index)));
        }
    }

    /** 放置上一页 / 下一页按钮（布局缺相应角色则跳过）。 */
    private void paintPagination(BaseGui gui) {
        final MenuButtons buttons = ctx.deps().buttons();
        if (layout().has(prevRole())) {
            button(gui, prevRole(), buttons.prevPage(event -> flipTo(page - 1)));
        }
        if (layout().has(nextRole())) {
            button(gui, nextRole(), buttons.nextPage(event -> flipTo(page + 1)));
        }
    }

    /** 翻页：夹取到合法区间，变更时写回会话页码并刷新（重新 loadData）。 */
    private void flipTo(int targetPage) {
        final int clamped = Math.clamp(targetPage, 1, pageCount());
        if (clamped == page) {
            return;
        }
        page = clamped;
        onPageChanged(clamped);
        refresh();
    }

    /** @return 每页容量（条目角色槽位数；角色缺失时为 0） */
    private int pageSize() {
        return layout().slots(entryRole()).length;
    }
}
