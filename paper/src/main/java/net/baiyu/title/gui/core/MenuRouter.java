package net.baiyu.title.gui.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 菜单路由：open / replace / back / closeAll 四语义，
 * 维护每玩家返回栈（存 {@link MenuSpec}，栈上限 {@link GuiSession#HISTORY_LIMIT}）。
 *
 * <p>语义约定：<ul>
 * <li>open——当前菜单（若非瞬态）压栈后打开新菜单；</li>
 * <li>replace——不入栈原地替换（筛选应用、翻页等）；</li>
 * <li>back——弹栈经 {@link MenuFactory} 按规格重建并重新 loadData
 * （天然最新数据）；栈空则关闭背包；</li>
 * <li>closeAll——清空导航状态并关闭背包（关闭动作以 navigating 标记括起，
 * 不触发「玩家主动 ESC」仲裁）。</li></ul></p>
 *
 * <p>router ↔ factory 互相依赖，用 {@link #bindFactory} 后置绑定打破循环：
 * 组合根先建 router → 组 MenuDeps → 建 factory → bindFactory。
 * 所有方法仅主线程调用。</p>
 */
public final class MenuRouter {

    /** 会话管理器。 */
    private final GuiSessionManager sessions;

    /** 菜单工厂（后置绑定，恰一次）。 */
    private MenuFactory factory;

    /**
     * 构造菜单路由。
     *
     * @param sessions 会话管理器
     */
    public MenuRouter(GuiSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "会话管理器不能为空");
    }

    /**
     * 后置绑定菜单工厂（组合根装配时调用，恰一次）。
     *
     * @param factory 菜单工厂
     * @throws IllegalStateException 重复绑定时
     */
    public void bindFactory(MenuFactory factory) {
        if (this.factory != null) {
            throw new IllegalStateException("菜单工厂已绑定，禁止重复绑定");
        }
        this.factory = Objects.requireNonNull(factory, "菜单工厂不能为空");
    }

    /**
     * 打开新菜单：当前菜单（若存在且非瞬态）压入返回栈。
     *
     * @param player 玩家
     * @param spec   目标菜单规格
     */
    public void open(Player player, MenuSpec spec) {
        final GuiSession session = sessions.get(player);
        final AbstractMenu menu = create(player, spec);
        final MenuSpec current = session.currentSpec();
        if (current != null && !session.isCurrentTransient()) {
            session.pushHistory(current);
        }
        session.setCurrent(spec, menu.isTransient());
        menu.open();
    }

    /**
     * 原地替换当前菜单：不入返回栈（筛选应用、状态切换等场景）。
     *
     * @param player 玩家
     * @param spec   目标菜单规格
     */
    public void replace(Player player, MenuSpec spec) {
        final GuiSession session = sessions.get(player);
        final AbstractMenu menu = create(player, spec);
        session.setCurrent(spec, menu.isTransient());
        menu.open();
    }

    /**
     * 返回上一级：弹栈经工厂重建并重新 loadData；栈空则关闭背包。
     *
     * @param player 玩家
     */
    public void back(Player player) {
        final GuiSession session = sessions.get(player);
        final MenuSpec target = session.popHistory();
        if (target == null) {
            closeAll(player);
            return;
        }
        final AbstractMenu menu = create(player, target);
        session.setCurrent(target, menu.isTransient());
        menu.open();
    }

    /**
     * 清空某玩家的导航状态并关闭其背包（back 到栈空 / 强制收口共用）。
     * <p>关闭动作以 navigating 标记括起：由此触发的 InventoryClose
     * 不进入「玩家主动 ESC」分支，不重复回调 onClosed。</p>
     *
     * @param player 玩家
     */
    public void closeAll(Player player) {
        final GuiSession session = sessions.get(player);
        final boolean hadMenu = session.currentSpec() != null;
        session.clearNavigation();
        if (hadMenu) {
            session.setNavigating(true);
            try {
                player.closeInventory();
            } finally {
                session.setNavigating(false);
            }
        }
    }

    /**
     * 关闭全体在线玩家的插件菜单（onDisable / reload 前置收口）。
     */
    public void closeAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            closeAll(player);
        }
    }

    /** 经工厂构建菜单（未绑定即编程错误，快速失败）。 */
    private AbstractMenu create(Player player, MenuSpec spec) {
        if (factory == null) {
            throw new IllegalStateException("菜单工厂尚未绑定（组合根须先 bindFactory）");
        }
        return factory.create(player, Objects.requireNonNull(spec, "菜单规格不能为空"));
    }
}
