package net.baiyu.title.gui.shop;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 每玩家商店视图状态：
 * 已应用的筛选 / 排序 / 商店页码，跨商店 / 筛选 / 排序菜单往返保持，
 * 会话失效（退出 / 空闲清扫）时随会话一并丢弃。
 *
 * <p>线程纪律与 {@code GuiSession} 一致：全部读写只在主线程发生
 * （GUI 事件与路由），类本身不做同步。筛选 / 排序菜单编辑期的暂存
 * （pending）也挂在本状态：菜单打开时以已应用值播种、逐次点击改写，
 * 应用经 {@link #applyFilter} / {@link #applySort} 落定并清空暂存，
 * 取消 / ESC 只清暂存不动已应用值。</p>
 */
public final class ShopViewState {

    /** 已应用的筛选（默认四维全部）。 */
    private ShopFilter filter = ShopFilter.DEFAULT;

    /** 已应用的排序（默认时间新→老）。 */
    private ShopSort sort = ShopSort.DEFAULT;

    /** 商店当前页码（1 起；筛选 / 排序菜单取消回跳用）。 */
    private int page = 1;

    /** 筛选菜单编辑中的暂存；不在编辑流程中时为 null。 */
    private @Nullable ShopFilter pendingFilter;

    /** 排序菜单编辑中的暂存；不在编辑流程中时为 null。 */
    private @Nullable ShopSort pendingSort;

    /** @return 已应用的筛选 */
    public ShopFilter filter() {
        return filter;
    }

    /** @return 已应用的排序 */
    public ShopSort sort() {
        return sort;
    }

    /** @return 商店当前页码（1 起） */
    public int page() {
        return page;
    }

    /**
     * 记录商店当前页码（商店菜单构造 / 翻页时写回）。
     *
     * @param page 页码（不足 1 时按 1 处理）
     */
    public void updatePage(int page) {
        this.page = Math.max(1, page);
    }

    /**
     * 应用新筛选（筛选菜单确认时调用）。
     *
     * @param filter 新筛选
     */
    public void applyFilter(ShopFilter filter) {
        this.filter = Objects.requireNonNull(filter, "筛选不能为空");
    }

    /**
     * 应用新排序（排序菜单确认时调用）。
     *
     * @param sort 新排序
     */
    public void applySort(ShopSort sort) {
        this.sort = Objects.requireNonNull(sort, "排序不能为空");
    }

    /** @return 筛选暂存；无暂存时回落到已应用筛选 */
    public ShopFilter pendingFilterOrApplied() {
        return pendingFilter != null ? pendingFilter : filter;
    }

    /**
     * 改写筛选暂存（筛选菜单循环切换时逐次调用）。
     *
     * @param pending 新暂存值
     */
    public void setPendingFilter(ShopFilter pending) {
        this.pendingFilter = Objects.requireNonNull(pending, "筛选暂存不能为空");
    }

    /** 丢弃筛选暂存（应用落定 / 取消 / ESC 时调用）。 */
    public void clearPendingFilter() {
        this.pendingFilter = null;
    }

    /** @return 排序暂存；无暂存时回落到已应用排序 */
    public ShopSort pendingSortOrApplied() {
        return pendingSort != null ? pendingSort : sort;
    }

    /**
     * 改写排序暂存（排序菜单单选 / 翻转时逐次调用）。
     *
     * @param pending 新暂存值
     */
    public void setPendingSort(ShopSort pending) {
        this.pendingSort = Objects.requireNonNull(pending, "排序暂存不能为空");
    }

    /** 丢弃排序暂存（应用落定 / 取消 / ESC 时调用）。 */
    public void clearPendingSort() {
        this.pendingSort = null;
    }

    /** 恢复筛选与排序为默认值，并清空全部暂存。 */
    public void reset() {
        this.filter = ShopFilter.DEFAULT;
        this.sort = ShopSort.DEFAULT;
        this.pendingFilter = null;
        this.pendingSort = null;
    }
}
