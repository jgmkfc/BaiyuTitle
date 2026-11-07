package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.IntegerArgument;
import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.PlayerNameArgument;
import net.baiyu.title.command.argument.TitleIdArgument;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.service.WardrobeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code /byt player ...}：玩家称号管理（权限 baiyutitle.admin）。
 *
 * <ul>
 *   <li>{@code give <player> <titleId>} —— 直接授予（有效期按称号 holdDurationDays）</li>
 *   <li>{@code remove <player> <titleId>} —— 收回（佩戴中由服务同事务卸为默认）</li>
 *   <li>{@code set <player> <titleId> [slot]} —— 装扮到栏位（slot 默认 1）</li>
 * </ul>
 */
public final class PlayerAdminCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 称号目录服务（查称号与有效期）。 */
    private final TitleCatalogService catalogService;

    /** 所有权服务。 */
    private final OwnershipService ownershipService;

    /** 装扮服务。 */
    private final WardrobeService wardrobeService;

    /** 玩家名参数工厂。 */
    private final PlayerNameArgument playerNames;

    /** 称号 ID 参数工厂。 */
    private final TitleIdArgument titleIds;

    /**
     * 构造玩家称号管理子命令。
     *
     * @param support          命令层公用工具
     * @param catalogService   称号目录服务
     * @param ownershipService 所有权服务
     * @param wardrobeService  装扮服务
     * @param playerNames      玩家名参数工厂
     * @param titleIds         称号 ID 参数工厂
     */
    public PlayerAdminCommand(CommandSupport support, TitleCatalogService catalogService,
                              OwnershipService ownershipService, WardrobeService wardrobeService,
                              PlayerNameArgument playerNames, TitleIdArgument titleIds) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.catalogService = Objects.requireNonNull(catalogService, "称号目录服务不能为空");
        this.ownershipService = Objects.requireNonNull(ownershipService, "所有权服务不能为空");
        this.wardrobeService = Objects.requireNonNull(wardrobeService, "装扮服务不能为空");
        this.playerNames = Objects.requireNonNull(playerNames, "玩家名参数工厂不能为空");
        this.titleIds = Objects.requireNonNull(titleIds, "称号 ID 参数工厂不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("player")
                .withPermission("baiyutitle.admin")
                .withSubcommands(buildGive(), buildRemove(), buildSet());
    }

    /** {@code give <player> <titleId>}。 */
    private CommandAPICommand buildGive() {
        return new CommandAPICommand("give")
                .withArguments(playerNames.create("player"), titleIds.create("titleId"))
                .executes((sender, args) -> {
                    withTarget(sender, args.get("player"), args.get("titleId"),
                        (target, title) -> {
                            final Long expiresAt = title.holdDurationDays() > 0
                                    ? System.currentTimeMillis() + title.holdDurationDays() * 86_400_000L
                                    : null;
                            ownershipService.grant(target.uuid(), target.name(), title.id(),
                                            GrantSource.ADMIN, null, expiresAt, support.actorOf(sender))
                                    .whenComplete((result, failure) -> support.runSync(() -> {
                                        if (failure != null) {
                                            support.handleFailure(sender, failure);
                                            return;
                                        }
                                        replyGrant(sender, target, title, result);
                                    }));
                        });
                });
    }

    /** 按授予结果回发消息。 */
    private void replyGrant(CommandSender sender, CommandSupport.ResolvedPlayer target,
                            Title title, OwnershipService.GrantResult result) {
        final TagResolver[] resolvers = titleResolvers(target, title);
        switch (result) {
            case OwnershipService.GrantResult.Granted ignored ->
                    support.send(sender, "command.player.give-success", resolvers);
            case OwnershipService.GrantResult.AlreadyOwned ignored ->
                    support.send(sender, "command.player.give-already-owned", resolvers);
            case OwnershipService.GrantResult.TitleNotFound notFound ->
                    support.send(sender, "command.player.title-not-found",
                            Placeholder.unparsed("id", String.valueOf(notFound.titleId())));
        }
    }

    /** {@code remove <player> <titleId>}。 */
    private CommandAPICommand buildRemove() {
        return new CommandAPICommand("remove")
                .withArguments(playerNames.create("player"), titleIds.create("titleId"))
                .executes((sender, args) -> {
                    withTarget(sender, args.get("player"), args.get("titleId"),
                        (target, title) -> ownershipService.revoke(target.uuid(), title.id(),
                                        "管理员收回", support.actorOf(sender))
                                .whenComplete((revoked, failure) -> support.runSync(() -> {
                                    if (failure != null) {
                                        support.handleFailure(sender, failure);
                                        return;
                                    }
                                    support.send(sender, revoked
                                                    ? "command.player.remove-success"
                                                    : "command.player.remove-not-owned",
                                            titleResolvers(target, title));
                                })));
                });
    }

    /** {@code set <player> <titleId> [slot]}（slot 默认 1）。 */
    private CommandAPICommand buildSet() {
        return new CommandAPICommand("set")
                .withArguments(playerNames.create("player"), titleIds.create("titleId"))
                .withOptionalArguments(new IntegerArgument("slot", 1))
                .executes((sender, args) -> {
                    final int slot = (int) args.getOptional("slot").orElse(1);
                    withTarget(sender, args.get("player"), args.get("titleId"),
                            (target, title) -> wardrobeService.setSlotTitle(target.uuid(), slot, title.id())
                                    .whenComplete((result, failure) -> support.runSync(() -> {
                                        if (failure != null) {
                                            support.handleFailure(sender, failure);
                                            return;
                                        }
                                        replySet(sender, target, title, slot, result);
                                    })));
                });
    }

    /** 按装扮结果回发消息。 */
    private void replySet(CommandSender sender, CommandSupport.ResolvedPlayer target,
                          Title title, int slot, WardrobeService.WardrobeResult result) {
        final TagResolver slotResolver = Placeholder.unparsed("slot", String.valueOf(slot));
        switch (result) {
            case SUCCESS -> support.send(sender, "command.player.set-success",
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.component("name", nameComponent(title)), slotResolver);
            case SLOT_OUT_OF_RANGE -> support.send(sender, "command.player.set-out-of-range",
                    Placeholder.unparsed("player", target.name()), slotResolver);
            case TITLE_NOT_OWNED -> support.send(sender, "command.player.set-not-owned",
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.component("name", nameComponent(title)));
        }
    }

    /**
     * 公共前置：解析目标玩家与称号（缓存查询，主线程），任一失败回发错误，
     * 全部就绪才执行动作。
     */
    private void withTarget(CommandSender sender, Object playerArg, Object titleIdArg,
                            TargetAction action) {
        final String targetName = (String) playerArg;
        final int titleId = (int) titleIdArg;
        final Optional<CommandSupport.ResolvedPlayer> target = support.resolve(targetName);
        if (target.isEmpty()) {
            support.send(sender, "command.error.player-not-found",
                    Placeholder.unparsed("player", targetName));
            return;
        }
        final Optional<Title> title = catalogService.findById(titleId)
                .filter(found -> !found.isDeleted());
        if (title.isEmpty()) {
            support.send(sender, "command.player.title-not-found",
                    Placeholder.unparsed("id", String.valueOf(titleId)));
            return;
        }
        action.run(target.get(), title.get());
    }

    /** 目标玩家 + 称号双占位符。 */
    private static TagResolver[] titleResolvers(CommandSupport.ResolvedPlayer target, Title title) {
        return new TagResolver[]{
                Placeholder.unparsed("player", target.name()),
                Placeholder.component("name", nameComponent(title))};
    }

    /** 称号名组件（存储值已是 MiniMessage，直接解析）。 */
    private static Component nameComponent(Title title) {
        return ColorParser.of(title.name()).build();
    }

    /** 目标就绪后的动作。 */
    @FunctionalInterface
    private interface TargetAction {

        /**
         * 执行动作。
         *
         * @param target 已解析的目标玩家
         * @param title  已解析的称号（未软删）
         */
        void run(CommandSupport.ResolvedPlayer target, Title title);
    }
}
