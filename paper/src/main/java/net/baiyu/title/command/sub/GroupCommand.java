package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.GroupNameArgument;
import net.baiyu.title.command.argument.PlayerNameArgument;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.service.GroupNameCache;
import net.baiyu.title.service.GroupPermissionRegistrar;
import net.baiyu.title.service.GroupService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@code /byt group ...}：玩家组管理（权限 baiyutitle.admin）。
 *
 * <ul>
 *   <li>{@code create <name> [称号名]} —— 建组 + 自动建组称号 + 注册权限节点</li>
 *   <li>{@code delete <group>} —— 软删组与组称号，收回全部持有者（历史保留）</li>
 *   <li>{@code add|remove <group> <player>} —— 成员进出（同事务授予/收回组称号）</li>
 *   <li>{@code list} / {@code info <group>} —— 组列表 / 组详情（member 表成员）</li>
 * </ul>
 *
 * <p>组名解析与补全都走 {@link GroupNameCache} 快照（补全零 DB IO 红线）；
 * 结果回发与建删后的权限节点/快照维护在 {@link GroupReplies}。</p>
 */
public final class GroupCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 玩家组服务。 */
    private final GroupService groupService;

    /** 组名缓存（组名参数解析）。 */
    private final GroupNameCache groupNameCache;

    /** 结果回发层。 */
    private final GroupReplies replies;

    /** 组名参数工厂。 */
    private final GroupNameArgument groupNames;

    /** 玩家名参数工厂。 */
    private final PlayerNameArgument playerNames;

    /**
     * 构造玩家组管理子命令。
     *
     * @param support             命令层公用工具
     * @param groupService        玩家组服务
     * @param groupNameCache      组名缓存
     * @param permissionRegistrar 组权限节点注册器
     * @param groupNames          组名参数工厂
     * @param playerNames         玩家名参数工厂
     */
    public GroupCommand(CommandSupport support, GroupService groupService,
                        GroupNameCache groupNameCache, GroupPermissionRegistrar permissionRegistrar,
                        GroupNameArgument groupNames, PlayerNameArgument playerNames) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.groupService = Objects.requireNonNull(groupService, "玩家组服务不能为空");
        this.groupNameCache = Objects.requireNonNull(groupNameCache, "组名缓存不能为空");
        this.replies = new GroupReplies(support, groupService, groupNameCache, permissionRegistrar);
        this.groupNames = Objects.requireNonNull(groupNames, "组名参数工厂不能为空");
        this.playerNames = Objects.requireNonNull(playerNames, "玩家名参数工厂不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("group")
                .withPermission("baiyutitle.admin")
                .withSubcommands(buildCreate(), buildDelete(), buildAdd(), buildRemove(),
                        buildList(), buildInfo());
    }

    /** {@code create <name> [称号名]}：称号名可省（用组名）、贪婪吃下含空格色码全文。 */
    private CommandAPICommand buildCreate() {
        return new CommandAPICommand("create")
                .withArguments(new StringArgument("name"))
                .withOptionalArguments(new GreedyStringArgument("titleName"))
                .executes((sender, args) -> {
                    final String titleName = (String) args.getOptional("titleName").orElse(null);
                    groupService.createGroup((String) args.get("name"), titleName,
                                    support.actorOf(sender))
                            .whenComplete(replyOn(sender, result -> replies.create(sender, result)));
                });
    }

    /** {@code delete <group>}。 */
    private CommandAPICommand buildDelete() {
        return new CommandAPICommand("delete")
                .withArguments(groupNames.create("group"))
                .executes((sender, args) -> {
                    withGroup(sender, args.get("group"), (group, name) ->
                            groupService.deleteGroup(group.id(), support.actorOf(sender))
                                    .whenComplete(replyOn(sender, result ->
                                            replies.delete(sender, name, result))));
                });
    }

    /** {@code add <group> <player>}。 */
    private CommandAPICommand buildAdd() {
        return new CommandAPICommand("add")
                .withArguments(groupNames.create("group"), playerNames.create("player"))
                .executes((sender, args) -> {
                    withGroup(sender, args.get("group"), (group, name) ->
                            withPlayer(sender, args.get("player"), target ->
                                    groupService.addMember(group.id(), target.uuid(), target.name(),
                                                    support.actorOf(sender))
                                            .whenComplete(replyOn(sender, result ->
                                                    replies.memberChange(sender, name,
                                                            target.name(), result)))));
                });
    }

    /** {@code remove <group> <player>}。 */
    private CommandAPICommand buildRemove() {
        return new CommandAPICommand("remove")
                .withArguments(groupNames.create("group"), playerNames.create("player"))
                .executes((sender, args) -> {
                    withGroup(sender, args.get("group"), (group, name) ->
                            withPlayer(sender, args.get("player"), target ->
                                    groupService.removeMember(group.id(), target.uuid(),
                                                    support.actorOf(sender))
                                            .whenComplete(replyOn(sender, result ->
                                                    replies.memberChange(sender, name,
                                                            target.name(), result)))));
                });
    }

    /** {@code list}：读线程取全量未删组。 */
    private CommandAPICommand buildList() {
        return new CommandAPICommand("list")
                .executes((sender, args) -> {
                    groupService.listGroups().whenComplete(replyOn(sender, groups -> {
                        if (groups.isEmpty()) {
                            support.send(sender, "command.group.list-empty");
                            return;
                        }
                        support.send(sender, "command.group.list-header",
                                Placeholder.unparsed("count", String.valueOf(groups.size())));
                        for (PlayerGroup group : groups) {
                            support.sendPlain(sender, "command.group.list-entry",
                                    Placeholder.unparsed("id", String.valueOf(group.id())),
                                    Placeholder.unparsed("group", group.name()));
                        }
                    }));
                });
    }

    /** {@code info <group>}：读线程取组详情（member 表成员列表）。 */
    private CommandAPICommand buildInfo() {
        return new CommandAPICommand("info")
                .withArguments(groupNames.create("group"))
                .executes((sender, args) -> {
                    withGroup(sender, args.get("group"), (group, name) ->
                            groupService.groupInfo(group.id())
                                    .whenComplete(replyOn(sender, info ->
                                            replies.info(sender, name, info))));
                });
    }

    /** 异步收尾模板：future 完成后跳主线程，失败统一收尾、成功走结果回发。 */
    private <T> BiConsumer<T, Throwable> replyOn(CommandSender sender, Consumer<T> reply) {
        return (result, failure) -> support.runSync(() -> {
            if (failure != null) {
                support.handleFailure(sender, failure);
            } else {
                reply.accept(result);
            }
        });
    }

    /** 公共前置：组名经缓存快照解析（零 DB IO），未知组名回发不存在。 */
    private void withGroup(CommandSender sender, Object groupArg,
                           BiConsumer<PlayerGroup, String> action) {
        final String groupName = (String) groupArg;
        final Optional<PlayerGroup> group = groupNameCache.byName(groupName);
        if (group.isEmpty()) {
            replies.notFound(sender, groupName);
            return;
        }
        action.accept(group.get(), groupName);
    }

    /** 公共前置：玩家名解析（在线优先，其次本地离线缓存）。 */
    private void withPlayer(CommandSender sender, Object playerArg,
                            Consumer<CommandSupport.ResolvedPlayer> action) {
        final String playerName = (String) playerArg;
        final Optional<CommandSupport.ResolvedPlayer> target = support.resolve(playerName);
        if (target.isEmpty()) {
            support.send(sender, "command.error.player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }
        action.accept(target.get());
    }
}
