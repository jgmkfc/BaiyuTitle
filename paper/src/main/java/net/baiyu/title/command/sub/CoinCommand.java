package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.LongArgument;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.PlayerNameArgument;
import net.baiyu.title.service.CoinService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code /byt coin ...}：称号币查询与管理。
 *
 * <ul>
 *   <li>{@code bal} —— 查自己（baiyutitle.use，仅玩家）</li>
 *   <li>{@code bal <player>} —— 查他人（baiyutitle.command.coin.others）</li>
 *   <li>{@code give|set|take <player> <amount>} —— 管理（baiyutitle.admin）</li>
 * </ul>
 */
public final class CoinCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 称号币服务。 */
    private final CoinService coinService;

    /** 玩家名参数工厂。 */
    private final PlayerNameArgument playerNames;

    /**
     * 构造称号币子命令。
     *
     * @param support     命令层公用工具
     * @param coinService 称号币服务
     * @param playerNames 玩家名参数工厂
     */
    public CoinCommand(CommandSupport support, CoinService coinService, PlayerNameArgument playerNames) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.coinService = Objects.requireNonNull(coinService, "称号币服务不能为空");
        this.playerNames = Objects.requireNonNull(playerNames, "玩家名参数工厂不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("coin")
                .withSubcommands(buildBalance(),
                        buildMutation("give"), buildMutation("set"), buildMutation("take"));
    }

    /**
     * {@code bal [player]}：无参查自己（仅玩家），带参查他人（单独权限，在执行器内裁决——
     * CommandAPI 权限只能挂整条命令，可选参数的差异化权限需手动检查）。
     */
    private CommandAPICommand buildBalance() {
        return new CommandAPICommand("bal")
                .withOptionalArguments(playerNames.create("player"))
                .executes((sender, args) -> {
                    final Optional<String> targetName = args.getOptional("player").map(String.class::cast);
                    if (targetName.isEmpty()) {
                        querySelf(sender);
                    } else {
                        queryOther(sender, targetName.get());
                    }
                });
    }

    /** 查自己余额（仅玩家；baiyutitle.use）。 */
    private void querySelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            support.send(sender, "command.error.player-only");
            return;
        }
        if (!player.hasPermission("baiyutitle.use")) {
            support.send(sender, "command.error.no-permission");
            return;
        }
        coinService.getBalance(player.getUniqueId()).whenComplete((balance, failure) ->
                support.runSync(() -> {
                    if (failure != null) {
                        support.handleFailure(sender, failure);
                        return;
                    }
                    support.send(sender, "command.coin.balance-self",
                            Placeholder.unparsed("balance", String.valueOf(balance)));
                }));
    }

    /** 查他人余额（baiyutitle.command.coin.others；在线+本地离线缓存）。 */
    private void queryOther(CommandSender sender, String targetName) {
        if (!sender.hasPermission("baiyutitle.command.coin.others")) {
            support.send(sender, "command.error.no-permission");
            return;
        }
        final Optional<CommandSupport.ResolvedPlayer> target = support.resolve(targetName);
        if (target.isEmpty()) {
            support.send(sender, "command.error.player-not-found",
                    Placeholder.unparsed("player", targetName));
            return;
        }
        coinService.getBalance(target.get().uuid()).whenComplete((balance, failure) ->
                support.runSync(() -> {
                    if (failure != null) {
                        support.handleFailure(sender, failure);
                        return;
                    }
                    support.send(sender, "command.coin.balance-other",
                            Placeholder.unparsed("player", target.get().name()),
                            Placeholder.unparsed("balance", String.valueOf(balance)));
                }));
    }

    /**
     * {@code give|set|take <player> <amount>}（baiyutitle.admin）。
     * <p>give/take 下限 1；set 下限 0（清零是合法管理操作）。</p>
     */
    private CommandAPICommand buildMutation(String verb) {
        final long minAmount = "set".equals(verb) ? 0L : 1L;
        return new CommandAPICommand(verb)
                .withPermission("baiyutitle.admin")
                .withArguments(playerNames.create("player"), new LongArgument("amount", minAmount))
                .executes((sender, args) -> {
                    final String targetName = (String) args.get("player");
                    final long amount = (long) args.get("amount");
                    final Optional<CommandSupport.ResolvedPlayer> target = support.resolve(targetName);
                    if (target.isEmpty()) {
                        support.send(sender, "command.error.player-not-found",
                                Placeholder.unparsed("player", targetName));
                        return;
                    }
                    mutate(sender, verb, target.get(), amount);
                });
    }

    /** 执行变动并回发结果（take 失败=余额不足，give/set 由服务保证成功或异常）。 */
    private void mutate(CommandSender sender, String verb, CommandSupport.ResolvedPlayer target, long amount) {
        final var actor = support.actorOf(sender);
        final var future = switch (verb) {
            case "give" -> coinService.give(target.uuid(), target.name(), amount, actor);
            case "take" -> coinService.take(target.uuid(), target.name(), amount, actor);
            default -> coinService.set(target.uuid(), target.name(), amount, actor);
        };
        future.whenComplete((result, failure) -> support.runSync(() -> {
            if (failure != null) {
                support.handleFailure(sender, failure);
                return;
            }
            final TagResolver[] resolvers = {
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("balance", String.valueOf(result.balance()))};
            if (result.success()) {
                support.send(sender, "command.coin." + verb + "-success", resolvers);
            } else {
                support.send(sender, "command.coin.take-failed", resolvers);
            }
        }));
    }
}
