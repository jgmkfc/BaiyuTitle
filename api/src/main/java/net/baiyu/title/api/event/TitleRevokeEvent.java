package net.baiyu.title.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 称号回收事件（不可取消的事后通知）。
 *
 * <p>称号回收（管理员收回等经所有权服务回收入口的操作）的数据库事务提交之后，
 * 于<b>主线程</b>派发；持有者可能已离线，故只携带 UUID。
 * 组对账收回、交易转出与到期收敛各有专属语义，不经本事件通知。</p>
 */
public class TitleRevokeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 原持有者 UUID。 */
    private final UUID owner;

    /** 称号 id。 */
    private final int titleId;

    /** 回收原因（机器可读串，如管理端收回备注）。 */
    private final String reason;

    /**
     * 构造称号回收事件。
     *
     * @param owner   原持有者 UUID
     * @param titleId 称号 id
     * @param reason  回收原因
     */
    public TitleRevokeEvent(@NotNull UUID owner, int titleId, @NotNull String reason) {
        this.owner = owner;
        this.titleId = titleId;
        this.reason = reason;
    }

    /**
     * 原持有者 UUID。
     *
     * @return 原持有者 UUID
     */
    public @NotNull UUID getOwner() {
        return owner;
    }

    /**
     * 称号 id。
     *
     * @return 称号 id
     */
    public int getTitleId() {
        return titleId;
    }

    /**
     * 回收原因。
     *
     * @return 回收原因
     */
    public @NotNull String getReason() {
        return reason;
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
