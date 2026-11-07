package net.baiyu.title.gui.core;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 通用玩家选择菜单请求：消费者注入
 * 「选中一个在线玩家之后做什么」，菜单本身不携带任何业务语义——
 * 本期消费者为管理端（选中 → 打开管理玩家仓库），M7 交易链
 * （选中 → 写入 TradeDraft.target → 选己方称号）复用同一菜单。
 *
 * <p>回调在主线程调用，且调用前菜单已复核目标仍在线；
 * 导航（open / replace）由回调自行发起，菜单不做默认跳转。</p>
 *
 * @param onSelect 单选回调（主线程；参数为选中的在线玩家）
 */
public record PlayerSelectRequest(Consumer<Player> onSelect) {

    /** 紧凑构造：回调必填。 */
    public PlayerSelectRequest {
        Objects.requireNonNull(onSelect, "选择回调不能为空");
    }
}
