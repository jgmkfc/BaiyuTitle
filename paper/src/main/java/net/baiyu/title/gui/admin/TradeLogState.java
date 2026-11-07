package net.baiyu.title.gui.admin;

import net.baiyu.title.domain.trade.TradeStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 全服交易记录菜单的会话态：状态筛选 +
 * 玩家名模糊搜索串。挂在 GuiSession 上，跨翻页 / back 重建保持，
 * 随会话失效丢弃。线程纪律：仅主线程读写。
 *
 * <p>筛选循环顺序照设计表格：全部 → 等待中 → 已完成 → 已销毁 → 已拒绝。
 * （EXPIRED 行归入「全部」展示，状态文案按）</p>
 */
public final class TradeLogState {

    /** 筛选循环顺序（null = 全部）。 */
    private static final TradeStatus[] CYCLE = {
            null, TradeStatus.PENDING, TradeStatus.COMPLETED,
            TradeStatus.DESTROYED, TradeStatus.REJECTED};

    /** 当前状态筛选；null = 全部。 */
    private @Nullable TradeStatus filter;

    /** 玩家名模糊搜索串；null = 不过滤。 */
    private @Nullable String nameLike;

    /** @return 当前状态筛选；null = 全部 */
    public @Nullable TradeStatus filter() {
        return filter;
    }

    /** 循环切换到下一个筛选状态（B 漏斗键）。 */
    public void cycleFilter() {
        int index = 0;
        for (int i = 0; i < CYCLE.length; i++) {
            if (CYCLE[i] == filter) {
                index = i;
                break;
            }
        }
        filter = CYCLE[(index + 1) % CYCLE.length];
    }

    /** @return 玩家名模糊搜索串；null = 不过滤 */
    public @Nullable String nameLike() {
        return nameLike;
    }

    /**
     * 设置玩家名模糊搜索串。
     *
     * @param nameLike 搜索串；null = 清除过滤
     */
    public void setNameLike(@Nullable String nameLike) {
        this.nameLike = nameLike;
    }
}
