package net.baiyu.title.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 交易接受前事件（可取消）。
 *
 * <p>目标方点击同意后、任何数据库写发生之前，于<b>主线程</b>同步派发；
 * 取消后本次接受静默中止，报价保持 PENDING 状态不变。</p>
 */
public class TradePreAcceptEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 接受者（报价目标方）。 */
    private final Player acceptor;

    /** 交易 id。 */
    private final long tradeId;

    /** 取消标记。 */
    private boolean cancelled;

    /**
     * 构造交易接受前事件。
     *
     * @param acceptor 接受者（报价目标方）
     * @param tradeId  交易 id
     */
    public TradePreAcceptEvent(@NotNull Player acceptor, long tradeId) {
        this.acceptor = acceptor;
        this.tradeId = tradeId;
    }

    /**
     * 接受者（报价目标方）。
     *
     * @return 接受者
     */
    public @NotNull Player getAcceptor() {
        return acceptor;
    }

    /**
     * 交易 id。
     *
     * @return 交易 id
     */
    public long getTradeId() {
        return tradeId;
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
