package net.baiyu.title.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 称号购买前事件（可取消）。
 *
 * <p>玩家在商店确认购买后、任何数据库写发生之前，于<b>主线程</b>同步派发；
 * 取消后本次购买静默中止，不产生订单、不扣任何款项。</p>
 */
public class TitlePrePurchaseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 买家。 */
    private final Player buyer;

    /** 目标称号 id。 */
    private final int titleId;

    /** 取消标记。 */
    private boolean cancelled;

    /**
     * 构造称号购买前事件。
     *
     * @param buyer   买家
     * @param titleId 目标称号 id
     */
    public TitlePrePurchaseEvent(@NotNull Player buyer, int titleId) {
        this.buyer = buyer;
        this.titleId = titleId;
    }

    /**
     * 买家。
     *
     * @return 买家
     */
    public @NotNull Player getBuyer() {
        return buyer;
    }

    /**
     * 目标称号 id。
     *
     * @return 称号 id
     */
    public int getTitleId() {
        return titleId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit 事件系统要求的静态处理器列表。
     *
     * @return 处理器列表
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
