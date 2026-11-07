package net.baiyu.title.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 称号授予事件（不可取消的事后通知）。
 *
 * <p>任一授予入口（购买 / 管理员给予 / 交易转入 / 羁绊 / 兑换 / 组 / 自定义）
 * 的数据库事务提交之后，于<b>主线程</b>派发；持有者可能已离线，故只携带 UUID。</p>
 */
public class TitleGrantEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 持有者 UUID。 */
    private final UUID owner;

    /** 称号 id。 */
    private final int titleId;

    /** 授予来源（如 PURCHASE / ADMIN / TRADE / BOND / REDEEM_CODE / REDEEM_CARD / GROUP / CUSTOM）。 */
    private final String source;

    /**
     * 构造称号授予事件。
     *
     * @param owner   持有者 UUID
     * @param titleId 称号 id
     * @param source  授予来源名称
     */
    public TitleGrantEvent(@NotNull UUID owner, int titleId, @NotNull String source) {
        this.owner = owner;
        this.titleId = titleId;
        this.source = source;
    }

    /**
     * 持有者 UUID。
     *
     * @return 持有者 UUID
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
     * 授予来源名称。
     *
     * @return 授予来源
     */
    public @NotNull String getSource() {
        return source;
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
