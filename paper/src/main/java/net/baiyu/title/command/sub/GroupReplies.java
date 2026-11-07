package net.baiyu.title.command.sub;

import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.service.GroupNameCache;
import net.baiyu.title.service.GroupPermissionRegistrar;
import net.baiyu.title.service.GroupService;
import net.baiyu.title.service.group.GroupCreateResult;
import net.baiyu.title.service.group.GroupDeleteResult;
import net.baiyu.title.service.group.GroupInfo;
import net.baiyu.title.service.group.MemberChangeResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code /byt group} 的结果回发层（{@link GroupCommand} 的内部协作者）。
 *
 * <p>全部方法在主线程调用（命令层已 runSync 跳回）；建删成功侧顺带完成
 * 权限节点注册/注销与组名快照刷新（快照刷新走后台读线程，失败仅使
 * 补全短暂陈旧，不影响后续命令执行——执行期未知组名会回发不存在）。</p>
 */
final class GroupReplies {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 玩家组服务（快照刷新数据源）。 */
    private final GroupService groupService;

    /** 组名缓存（建删后刷新）。 */
    private final GroupNameCache groupNameCache;

    /** 组权限节点注册器。 */
    private final GroupPermissionRegistrar permissionRegistrar;

    /**
     * 构造回发层。
     *
     * @param support             命令层公用工具
     * @param groupService        玩家组服务
     * @param groupNameCache      组名缓存
     * @param permissionRegistrar 组权限节点注册器
     */
    GroupReplies(CommandSupport support, GroupService groupService,
                 GroupNameCache groupNameCache, GroupPermissionRegistrar permissionRegistrar) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.groupService = Objects.requireNonNull(groupService, "玩家组服务不能为空");
        this.groupNameCache = Objects.requireNonNull(groupNameCache, "组名缓存不能为空");
        this.permissionRegistrar = Objects.requireNonNull(permissionRegistrar, "组权限注册器不能为空");
    }

    /** 建组结果回发：成功侧注册权限节点并刷新组名快照。 */
    void create(CommandSender sender, GroupCreateResult result) {
        switch (result) {
            case GroupCreateResult.Created(PlayerGroup group, var title) -> {
                permissionRegistrar.register(group.name());
                refreshNameCache();
                support.send(sender, "command.group.create-success",
                        Placeholder.unparsed("group", group.name()),
                        Placeholder.unparsed("id", String.valueOf(title.id())),
                        Placeholder.component("name", ColorParser.of(title.name()).build()),
                        Placeholder.unparsed("node", group.permissionNode()));
            }
            case GroupCreateResult.InvalidName(String input) ->
                    support.send(sender, "command.group.create-invalid-name",
                            Placeholder.unparsed("input", String.valueOf(input)));
            case GroupCreateResult.DuplicateName(String dup) ->
                    support.send(sender, "command.group.create-duplicate",
                            Placeholder.unparsed("group", dup));
        }
    }

    /** 删组结果回发：成功侧注销权限节点并刷新组名快照。 */
    void delete(CommandSender sender, String groupName, GroupDeleteResult result) {
        switch (result) {
            case GroupDeleteResult.Deleted deleted -> {
                permissionRegistrar.unregister(groupName);
                refreshNameCache();
                support.send(sender, "command.group.delete-success",
                        Placeholder.unparsed("group", groupName),
                        Placeholder.unparsed("count", String.valueOf(deleted.revokedCount())));
            }
            case GroupDeleteResult.NotFound ignored -> notFound(sender, groupName);
        }
    }

    /** 成员变更结果回发（add/remove 共用，密封类型穷举）。 */
    void memberChange(CommandSender sender, String groupName, String playerName,
                      MemberChangeResult result) {
        final TagResolver[] resolvers = {
                Placeholder.unparsed("group", groupName),
                Placeholder.unparsed("player", playerName)};
        switch (result) {
            case MemberChangeResult.Added ignored ->
                    support.send(sender, "command.group.add-success", resolvers);
            case MemberChangeResult.AlreadyMember ignored ->
                    support.send(sender, "command.group.add-already", resolvers);
            case MemberChangeResult.Removed ignored ->
                    support.send(sender, "command.group.remove-success", resolvers);
            case MemberChangeResult.NotMember ignored ->
                    support.send(sender, "command.group.remove-not-member", resolvers);
            case MemberChangeResult.GroupNotFound ignored -> notFound(sender, groupName);
        }
    }

    /** 组详情回发（组头 + 组称号行 + member 表成员列表）。 */
    void info(CommandSender sender, String groupName, Optional<GroupInfo> info) {
        if (info.isEmpty()) {
            notFound(sender, groupName);
            return;
        }
        final GroupInfo detail = info.get();
        support.send(sender, "command.group.info-header",
                Placeholder.unparsed("group", detail.group().name()),
                Placeholder.unparsed("id", String.valueOf(detail.group().id())));
        if (detail.title() == null) {
            support.sendPlain(sender, "command.group.info-title-missing");
        } else {
            support.sendPlain(sender, "command.group.info-title",
                    Placeholder.component("name", ColorParser.of(detail.title().name()).build()));
        }
        if (detail.members().isEmpty()) {
            support.sendPlain(sender, "command.group.info-members-empty");
            return;
        }
        support.sendPlain(sender, "command.group.info-members-header",
                Placeholder.unparsed("count", String.valueOf(detail.members().size())));
        for (GroupMember member : detail.members()) {
            support.sendPlain(sender, "command.group.info-member-entry",
                    Placeholder.unparsed("player", member.playerName()));
        }
    }

    /** 组不存在的统一回发。 */
    void notFound(CommandSender sender, String groupName) {
        support.send(sender, "command.group.not-found",
                Placeholder.unparsed("group", groupName));
    }

    /** 建删成功后的组名快照全量刷新（后台读线程；失败仅补全短暂陈旧）。 */
    private void refreshNameCache() {
        groupService.listGroups().whenComplete((groups, failure) -> {
            if (failure == null) {
                groupNameCache.replaceAll(groups);
            }
        });
    }
}
