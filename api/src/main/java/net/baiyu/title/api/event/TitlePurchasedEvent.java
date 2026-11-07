package net.baiyu.title.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 称号购买完成事件（不可取消的事后通知）。
 *
 * <p>购买订单履约（数据库事务提交）之后，于<b>主线程</b>派发；
 * 此刻称号已归属买家。授予本身另有 {@link TitleGrantEvent} 通知。</p>
 */
public class TitlePurchasedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 买家。 */
    private final Player buyer;

    /** 已购称号 id。 */
    private final int titleId;

    /** 购买订单 id。 */
    private final long orderId;

    /**
     * 构造称号购买完成事件。
     *
     * @param buyer   买家
     * @param titleId 已购称号 id
     * @param orderId 购买订单 id
     */
    public TitlePurchasedEvent(@NotNull Player buyer, int titleId, long orderId) {
        this.buyer = buyer;
        this.titleId = titleId;
        this.orderId = orderId;
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
     * 已购称号 id。
     *
     * @return 称号 id
     */
    public int getTitleId() {
        return titleId;
    }

    /**
     * 购买订单 id。
     *
     * @return 订单 id
     */
    public long getOrderId() {
        return orderId;
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
