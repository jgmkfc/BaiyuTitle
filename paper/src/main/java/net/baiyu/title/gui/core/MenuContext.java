package net.baiyu.title.gui.core;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 菜单上下文：一个菜单实例服务一名玩家的一次打开，
 * 打包「玩家 + 依赖聚合 + 该玩家会话」，由 {@link MenuFactory} 构造后
 * 注入菜单构造器。
 *
 * @param player  目标玩家
 * @param deps    菜单依赖聚合
 * @param session 该玩家的 GUI 会话
 */
public record MenuContext(Player player, MenuDeps deps, GuiSession session) {

    /** 紧凑构造：全部成员必填。 */
    public MenuContext {
        Objects.requireNonNull(player, "玩家不能为空");
        Objects.requireNonNull(deps, "菜单依赖不能为空");
        Objects.requireNonNull(session, "玩家会话不能为空");
    }
}
