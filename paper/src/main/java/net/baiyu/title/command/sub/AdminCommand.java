package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.LongArgument;
import dev.jorel.commandapi.arguments.MultiLiteralArgument;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.service.PurchaseService;
import net.baiyu.title.service.purchase.CompleteResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /byt admin ...}：管理端入口（权限 baiyutitle.admin）。
 *
 * <ul>
 *   <li>{@code admin} —— 打开管理主菜单（仅玩家）</li>
 *   <li>{@code admin order resolve <id> grant|refund} —— 裁决 MANUAL 留置订单
 *       （玩家 / 控制台皆可；{@code PurchaseService.resolveManual}）</li>
 * </ul>
 */
public final class AdminCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 菜单路由（管理主菜单入口）。 */
    private final MenuRouter router;

    /** 购买服务（MANUAL 订单裁决入口）。 */
    private final PurchaseService purchaseService;

    /**
     * 构造管理子命令。
     *
     * @param support         命令层公用工具
     * @param router          菜单路由
     * @param purchaseService 购买服务
     */
    public AdminCommand(CommandSupport support, MenuRouter router,
                        PurchaseService purchaseService) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.router = Objects.requireNonNull(router, "菜单路由不能为空");
        this.purchaseService = Objects.requireNonNull(purchaseService, "购买服务不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("admin")
                .withPermission("baiyutitle.admin")
                .withSubcommands(buildOrder())
                .executes((sender, args) -> {
                    if (sender instanceof Player player) {
                        router.open(player, new MenuSpec.Admin(1));
                    } else {
                        support.send(sender, "command.error.player-only");
                    }
                });
    }

    /** {@code order resolve <id> grant|refund}：MANUAL 留置订单裁决。 */
    private CommandAPICommand buildOrder() {
        return new CommandAPICommand("order").withSubcommands(
                new CommandAPICommand("resolve")
                        .withArguments(new LongArgument("id", 1),
                                new MultiLiteralArgument("action", "grant", "refund"))
                        .executes((sender, args) -> {
                            final long orderId = (long) args.get("id");
                            final boolean grant = "grant".equals(args.get("action"));
                            purchaseService.resolveManual(orderId, grant, support.actorOf(sender))
                                    .whenComplete((result, failure) -> support.runSync(() -> {
                                        if (failure != null) {
                                            support.handleFailure(sender, failure);
                                            return;
                                        }
                                        reply(sender, result);
                                    }));
                        }));
    }

    /** 收敛结果 → 回执文案（五分支穷举）。 */
    private void reply(CommandSender sender, CompleteResult result) {
        switch (result) {
            case CompleteResult.Fulfilled fulfilled -> support.send(sender,
                    "command.admin.resolve.fulfilled",
                    Placeholder.unparsed("order", Long.toString(fulfilled.orderId())));
            case CompleteResult.Refunded refunded -> support.send(sender,
                    "command.admin.resolve.refunded",
                    Placeholder.unparsed("order", Long.toString(refunded.orderId())));
            case CompleteResult.MarkedManual manual -> support.send(sender,
                    "command.admin.resolve.manual",
                    Placeholder.unparsed("order", Long.toString(manual.orderId())),
                    Placeholder.unparsed("reason", manual.reason()));
            case CompleteResult.AlreadyResolved already -> support.send(sender,
                    "command.admin.resolve.already",
                    Placeholder.unparsed("order", Long.toString(already.orderId())),
                    Placeholder.unparsed("state", already.currentState().name()));
            case CompleteResult.UnknownOrder unknown -> support.send(sender,
                    "command.admin.resolve.unknown",
                    Placeholder.unparsed("order", Long.toString(unknown.orderId())));
        }
    }
}
