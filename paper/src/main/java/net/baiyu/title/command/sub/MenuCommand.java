package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.MenuSpec;

import java.util.List;
import java.util.Objects;

/**
 * 菜单入口子命令：
 * {@code /byt open}（别名 {@code repo}）→ 仓库菜单；
 * {@code /byt menu} → 装扮管理菜单；
 * {@code /byt shop} → 商店菜单（第 1 页）。
 * 权限 {@code baiyutitle.use}，仅玩家。
 */
public final class MenuCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 菜单路由。 */
    private final MenuRouter router;

    /**
     * 构造菜单入口子命令。
     *
     * @param support 命令层公用工具
     * @param router  菜单路由
     */
    public MenuCommand(CommandSupport support, MenuRouter router) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.router = Objects.requireNonNull(router, "菜单路由不能为空");
    }

    /**
     * 构建全部菜单入口子命令。
     *
     * @return 子命令列表（open / menu / shop）
     */
    public List<CommandAPICommand> build() {
        final CommandAPICommand open = new CommandAPICommand("open")
                .withAliases("repo")
                .withPermission("baiyutitle.use")
                .executesPlayer((player, args) -> {
                    router.open(player, new MenuSpec.Repository(1));
                });
        final CommandAPICommand menu = new CommandAPICommand("menu")
                .withPermission("baiyutitle.use")
                .executesPlayer((player, args) -> {
                    router.open(player, new MenuSpec.Wardrobe());
                });
        final CommandAPICommand shop = new CommandAPICommand("shop")
                .withPermission("baiyutitle.use")
                .executesPlayer((player, args) -> {
                    router.open(player, new MenuSpec.Shop(1));
                });
        return List.of(open, menu, shop);
    }
}
