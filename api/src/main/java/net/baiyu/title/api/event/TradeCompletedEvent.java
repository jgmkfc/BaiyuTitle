package net.baiyu.title.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 交易完成事件（不可取消的事后通知）。
 *
 * <p>交易成交（PENDING→COMPLETED 事务提交，双方标的已互换）之后，
 * 于<b>主线程</b>派发；每侧标的的授予另有 {@link TitleGrantEvent} 通知。</p>
 */
public class TradeCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 交易 id。 */
    private final long tradeId;

    /** 发起方 UUID。 */
    private final UUID initiatorUuid;

    /** 发起方名称。 */
    private final String initiatorName;

    /** 目标方 UUID。 */
    private final UUID targetUuid;

    /** 目标方名称。 */
    private final String targetName;

    /**
     * 构造交易完成事件。
     *
     * @param tradeId       交易 id
     * @param initiatorUuid 发起方 UUID
     * @param initiatorName 发起方名称
     * @param targetUuid    目标方 UUID
     * @param targetName    目标方名称
     */
    public TradeCompletedEvent(long tradeId, @NotNull UUID initiatorUuid,
                               @NotNull String initiatorName, @NotNull UUID targetUuid,
                               @NotNull String targetName) {
        this.tradeId = tradeId;
        this.initiatorUuid = initiatorUuid;
        this.initiatorName = initiatorName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    /**
     * 交易 id。
     *
     * @return 交易 id
     */
    public long getTradeId() {
        return tradeId;
    }

    /**
     * 发起方 UUID。
     *
     * @return 发起方 UUID
     */
    public @NotNull UUID getInitiatorUuid() {
        return initiatorUuid;
    }

    /**
     * 发起方名称。
     *
     * @return 发起方名称
     */
    public @NotNull String getInitiatorName() {
        return initiatorName;
    }

    /**
     * 目标方 UUID。
     *
     * @return 目标方 UUID
     */
    public @NotNull UUID getTargetUuid() {
        return targetUuid;
    }

    /**
     * 目标方名称。
     *
     * @return 目标方名称
     */
    public @NotNull String getTargetName() {
        return targetName;
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
