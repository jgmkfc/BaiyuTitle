package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.LongArgument;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.service.TradeService;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.trade.ResolveResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * {@code /byt trade ...}：交易系统入口。
 *
 * <ul>
 *   <li>{@code trade} —— 打开报价列表（baiyutitle.use，仅玩家）</li>
 *   <li>{@code trade history} —— 打开个人交易记录（baiyutitle.use，仅玩家）</li>
 *   <li>{@code trade admin} —— 打开全服交易记录菜单（baiyutitle.admin，仅玩家）</li>
 *   <li>{@code trade admin cancel <id>} —— 强制销毁 PENDING 报价
 *       （baiyutitle.admin；玩家 / 控制台皆可）</li>
 * </ul>
 *
 * <p>总开关裁决：{@code trade.enabled=false} 时玩家向入口（列表 / 记录）
 * 一律拒绝并提示；管理向入口（全服记录 / 强制取消）不受影响——
 * 与服务层"关闭后仍放行清理存量报价"的语义一致。</p>
 */
public final class TradeCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 菜单路由。 */
    private final MenuRouter router;

    /** 交易视图门面（总开关判定）。 */
    private final TradeViewService tradeViews;

    /** 交易服务（管理员强制取消入口）。 */
    private final TradeService tradeService;

    /**
     * 构造交易子命令。
     *
     * @param support      命令层公用工具
     * @param router       菜单路由
     * @param tradeViews   交易视图门面
     * @param tradeService 交易服务
     */
    public TradeCommand(CommandSupport support, MenuRouter router,
                        TradeViewService tradeViews, TradeService tradeService) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.router = Objects.requireNonNull(router, "菜单路由不能为空");
        this.tradeViews = Objects.requireNonNull(tradeViews, "交易视图门面不能为空");
        this.tradeService = Objects.requireNonNull(tradeService, "交易服务不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        final CommandAPICommand history = new CommandAPICommand("history")
                .withPermission("baiyutitle.use")
                .executesPlayer((player, args) -> {
                    if (rejectWhenDisabled(player)) {
                        return;
                    }
                    router.open(player, new MenuSpec.TradeHistory(1));
                });
        return new CommandAPICommand("trade")
                .withPermission("baiyutitle.use")
                .withSubcommands(history, buildAdmin())
                .executesPlayer((player, args) -> {
                    if (rejectWhenDisabled(player)) {
                        return;
                    }
                    router.open(player, new MenuSpec.TradeList(1));
                });
    }

    /** {@code admin [cancel <id>]}：全服记录菜单 / 强制销毁报价。 */
    private CommandAPICommand buildAdmin() {
        final CommandAPICommand cancel = new CommandAPICommand("cancel")
                .withArguments(new LongArgument("id", 1))
                .executes((sender, args) -> {
                    adminCancel(sender, (long) args.get("id"));
                });
        return new CommandAPICommand("admin")
                .withPermission("baiyutitle.admin")
                .withSubcommands(cancel)
                .executes((sender, args) -> {
                    if (sender instanceof Player player) {
                        router.open(player, new MenuSpec.AdminTradeLog(1));
                    } else {
                        support.send(sender, "command.error.player-only");
                    }
                });
    }

    /** 管理员强制销毁：PENDING → DESTROYED（不限交易方；结果四分支回执）。 */
    private void adminCancel(CommandSender sender, long tradeId) {
        tradeService.adminCancel(tradeId, support.actorOf(sender))
                .whenComplete((result, failure) -> support.runSync(() -> {
                    if (failure != null) {
                        support.handleFailure(sender, failure);
                        return;
                    }
                    final var id = Placeholder.unparsed("id", Long.toString(tradeId));
                    switch (result) {
                        case ResolveResult.Resolved ignored ->
                                support.send(sender, "trade.admin-cancel.success", id);
                        case ResolveResult.AlreadyResolved already -> support.send(sender,
                                "trade.admin-cancel.already", id,
                                Placeholder.unparsed("state", already.status().name()));
                        case ResolveResult.UnknownTrade ignored ->
                                support.send(sender, "trade.admin-cancel.unknown", id);
                        case ResolveResult.NotParticipant ignored ->
                                support.send(sender, "command.error.internal");
                    }
                }));
    }

    /** 总开关关闭时拒绝玩家向入口并提示。 */
    private boolean rejectWhenDisabled(Player player) {
        if (tradeViews.enabled()) {
            return false;
        }
        support.send(player, "trade.disabled");
        return true;
    }
}
