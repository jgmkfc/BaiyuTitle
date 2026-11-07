package net.baiyu.title.command;

import dev.jorel.commandapi.CommandAPICommand;
import net.baiyu.title.command.sub.AdminCommand;
import net.baiyu.title.command.sub.CoinCommand;
import net.baiyu.title.command.sub.GroupCommand;
import net.baiyu.title.command.sub.HelpCommand;
import net.baiyu.title.command.sub.MenuCommand;
import net.baiyu.title.command.sub.PlayerAdminCommand;
import net.baiyu.title.command.sub.RedeemCommand;
import net.baiyu.title.command.sub.ReloadCommand;
import net.baiyu.title.command.sub.TitleAdminCommand;
import net.baiyu.title.command.sub.TradeCommand;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.MenuSpec;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 根命令组装：{@code /baiyutitle}（别名 {@code /byt}）。
 *
 * <p>当前子集：help / reload / coin / title / player / open|repo / menu / shop /
 * admin/ trade/ group / redeem|code。
 * 默认执行器：玩家 → 打开装扮管理菜单（§6 首行）；控制台 → 帮助。</p>
 */
public final class CommandRoot {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 菜单路由（默认执行器开装扮管理菜单）。 */
    private final MenuRouter router;

    /** 帮助子命令。 */
    private final HelpCommand help;

    /** 重载子命令。 */
    private final ReloadCommand reload;

    /** 称号币子命令。 */
    private final CoinCommand coin;

    /** 称号管理子命令。 */
    private final TitleAdminCommand titleAdmin;

    /** 玩家称号管理子命令。 */
    private final PlayerAdminCommand playerAdmin;

    /** 菜单入口子命令（open / menu / shop）。 */
    private final MenuCommand menu;

    /** 管理端子命令（admin / admin order resolve）。 */
    private final AdminCommand admin;

    /** 交易子命令（trade / trade history / trade admin [cancel]）。 */
    private final TradeCommand trade;

    /** 玩家组子命令（group create/delete/add/remove/list/info）。 */
    private final GroupCommand group;

    /** 兑换子命令（redeem|code / redeem create/card/disable）。 */
    private final RedeemCommand redeem;

    /**
     * 构造根命令组装器。
     *
     * @param support     命令层公用工具
     * @param router      菜单路由
     * @param help        帮助子命令
     * @param reload      重载子命令
     * @param coin        称号币子命令
     * @param titleAdmin  称号管理子命令
     * @param playerAdmin 玩家称号管理子命令
     * @param menu        菜单入口子命令
     * @param admin       管理端子命令
     * @param trade       交易子命令
     * @param group       玩家组子命令
     * @param redeem      兑换子命令
     */
    public CommandRoot(CommandSupport support, MenuRouter router, HelpCommand help,
                       ReloadCommand reload, CoinCommand coin, TitleAdminCommand titleAdmin,
                       PlayerAdminCommand playerAdmin, MenuCommand menu, AdminCommand admin,
                       TradeCommand trade, GroupCommand group, RedeemCommand redeem) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.router = Objects.requireNonNull(router, "菜单路由不能为空");
        this.help = Objects.requireNonNull(help, "帮助子命令不能为空");
        this.reload = Objects.requireNonNull(reload, "重载子命令不能为空");
        this.coin = Objects.requireNonNull(coin, "称号币子命令不能为空");
        this.titleAdmin = Objects.requireNonNull(titleAdmin, "称号管理子命令不能为空");
        this.playerAdmin = Objects.requireNonNull(playerAdmin, "玩家称号管理子命令不能为空");
        this.menu = Objects.requireNonNull(menu, "菜单入口子命令不能为空");
        this.admin = Objects.requireNonNull(admin, "管理端子命令不能为空");
        this.trade = Objects.requireNonNull(trade, "交易子命令不能为空");
        this.group = Objects.requireNonNull(group, "玩家组子命令不能为空");
        this.redeem = Objects.requireNonNull(redeem, "兑换子命令不能为空");
    }

    /**
     * 注册命令树（onEnable 调用恰一次；reload 白名单不重建命令）。
     */
    public void register() {
        final List<CommandAPICommand> subcommands = new ArrayList<>(List.of(
                help.build(), reload.build(), coin.build(),
                titleAdmin.build(), playerAdmin.build(), admin.build(), trade.build(),
                group.build(), redeem.build()));
        subcommands.addAll(menu.build());
        new CommandAPICommand("baiyutitle")
                .withAliases("byt")
                .withPermission("baiyutitle.command")
                .withSubcommands(subcommands.toArray(CommandAPICommand[]::new))
                .executes((sender, args) -> {
                    if (sender instanceof Player player) {
                        router.open(player, new MenuSpec.Wardrobe());
                    } else {
                        support.sendLines(sender, "command.help.lines");
                    }
                })
                .register();
    }
}
