package net.baiyu.title.gui.core;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.repository.Page;

import java.util.List;

/**
 * 数据库分页菜单基类：与
 * {@link AbstractPagedMenu}（全量预取 + 客户端分页）不同，本基类逐页
 * 走服务的 DB 分页查询（{@link Page} 携带总数），适配交易历史 /
 * 全服交易记录这类不宜全量装载的列表。
 *
 * <p>条目槽位 = 布局中 {@link #entryRole()}（默认 'X'）的全部槽位，
 * 每页容量即槽位数并作为查询 size 传给 {@link #fetchPage}；
 * 翻页按 {@link Page#totalPages()} 夹取，变更时经
 * {@link #onPageChanged(int)} 写回会话后 {@link #refresh()} 重取。</p>
 *
 * @param <T> 条目视图对象类型（服务预计算，菜单不做业务判断）
 */
public abstract class AbstractRemotePagedMenu<T> extends AbstractMenu {

    /** 当前页数据（loadData 异步写入，主线程经调度器交接后读取）。 */
    private Page<T> pageData;

    /** 当前页码（1 起）。 */
    private int page;

    /**
     * 构造数据库分页菜单。
     *
     * @param ctx         菜单上下文
     * @param initialPage 初始页码（1 起；通常来自 MenuSpec 的 page 参数）
     */
    protected AbstractRemotePagedMenu(MenuContext ctx, int initialPage) {
        super(ctx);
        this.page = Math.max(1, initialPage);
    }

    /**
     * 查询一页数据（异步线程调用；内部经服务读路径走 DB 读线程池）。
     *
     * @param page 页码（1 起）
     * @param size 每页条数（= 条目槽位数）
     * @return 分页结果
     */
    protected abstract Page<T> fetchPage(int page, int size);

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
     * 页码变更回调（翻页 / 跳回首页时、refresh 之前调用）：
     * 子类在此把新页码写回会话规格，使 back 重建落在正确页。
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

    /** 跳回第 1 页并刷新（筛选 / 搜索条件变更后调用）。 */
    protected final void jumpToFirstPage() {
        page = 1;
        onPageChanged(1);
        refresh();
    }

    /** 异步取当前页；页码超出实际总页数（数据缩水）时收敛后重取一次。 */
    @Override
    protected void loadData() {
        Page<T> fetched = fetchPage(page, pageSize());
        if (fetched.items().isEmpty() && page > 1) {
            page = Math.max(1, fetched.totalPages());
            fetched = fetchPage(page, pageSize());
        }
        pageData = fetched;
    }

    /** 主线程填充：边框 → 条目 → 翻页键 → 子类附加键。 */
    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        paintEntries(gui);
        paintPagination(gui);
        populateExtras(gui);
    }

    /** 逐槽渲染当前页条目（不足一页的尾部槽位留空）。 */
    private void paintEntries(BaseGui gui) {
        final int[] slots = layout().slots(entryRole());
        final List<T> items = pageData == null ? List.of() : pageData.items();
        final int count = Math.min(slots.length, items.size());
        for (int i = 0; i < count; i++) {
            gui.setItem(slots[i], renderEntry(items.get(i)));
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

    /** 翻页：按已知总页数夹取，变更时写回会话页码并刷新（重新查询）。 */
    private void flipTo(int targetPage) {
        final int totalPages = pageData == null ? 1 : Math.max(1, pageData.totalPages());
        final int clamped = Math.clamp(targetPage, 1, totalPages);
        if (clamped == page) {
            return;
        }
        page = clamped;
        onPageChanged(clamped);
        refresh();
    }

    /** @return 每页容量（条目角色槽位数；角色缺失时兜底 1，避免除零）。 */
    private int pageSize() {
        return Math.max(1, layout().slots(entryRole()).length);
    }
}
