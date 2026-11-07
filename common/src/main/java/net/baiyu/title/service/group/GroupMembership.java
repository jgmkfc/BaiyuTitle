package net.baiyu.title.service.group;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.repository.GroupRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.OwnershipService;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 组成员变更与对账（GroupService 的内部协作者，方法只允许在写线程调用）。
 * <p>语义：加入 = member 行 + 授予组称号
 * （同事务）；移出 = 删 member 行 + 收回并卸下（同事务；仍持权限者由下次
 * reconcile 补授）；对账 = 有效成员（member 表 ∪ 权限持有者）与 ACTIVE 所有权
 * 差异化补授/补收——权限布尔由 paper 主线程预收集传入，本类零 Bukkit 依赖。
 */
public final class GroupMembership {

    private final GroupRepository groupRepository;
    private final OwnershipRepository ownershipRepository;
    private final TransactionRunner txRunner;
    private final PlayerProfileCache profileCache;
    private final GroupTitleOps titleOps;
    private final OwnershipService.PostGrantHook postGrantHook;

    /**
     * 构造组成员变更协作者。
     *
     * @param groupRepository     玩家组仓储
     * @param ownershipRepository 所有权仓储
     * @param txRunner            事务执行器
     * @param profileCache        玩家档案缓存
     * @param titleOps            组称号授予/收回原语
     * @param postGrantHook       授予提交后钩子（可空 = no-op；羁绊检测入口）
     */
    public GroupMembership(GroupRepository groupRepository, OwnershipRepository ownershipRepository,
                           TransactionRunner txRunner, PlayerProfileCache profileCache,
                           GroupTitleOps titleOps,
                           @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "玩家组仓储不能为空");
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.titleOps = Objects.requireNonNull(titleOps, "组称号原语不能为空");
        this.postGrantHook = postGrantHook == null ? OwnershipService.PostGrantHook.NO_OP : postGrantHook;
    }

    /**
     * 写线程主体：加入组（member 行 + 授予组称号同事务；提交后缓存写穿 + 钩子）。
     */
    public MemberChangeResult addMember(int groupId, UUID player, String playerName, Actor actor) {
        long now = System.currentTimeMillis();
        Title title = titleOps.groupTitleOf(groupId).orElse(null);
        MemberChangeResult result = txRunner.inTransaction(c -> {
            if (groupRepository.findById(c, groupId).filter(g -> !g.isDeleted()).isEmpty()) {
                return new MemberChangeResult.GroupNotFound(groupId);
            }
            boolean alreadyIn = groupRepository.findGroupsOf(c, player).stream()
                    .anyMatch(g -> g.id() == groupId);
            if (alreadyIn) {
                return new MemberChangeResult.AlreadyMember(groupId);
            }
            groupRepository.addMember(c, new GroupMember(groupId, player, playerName, actor.name(), now));
            Long ownershipId = title == null ? null
                    : titleOps.grantInTx(c, title.id(), groupId, player, playerName, now, actor);
            return new MemberChangeResult.Added(groupId, ownershipId);
        });
        if (result instanceof MemberChangeResult.Added(int ignored, Long ownershipId)
                && ownershipId != null && title != null) {
            mergeGrantedIntoCache(player, playerName, title.id(), groupId, ownershipId, now);
            postGrantHook.onGranted(player, title.id(), GrantSource.GROUP);
        }
        return result;
    }

    /**
     * 写线程主体：移出组（删 member 行 + 收回卸佩戴同事务；提交后缓存写穿）。
     */
    public MemberChangeResult removeMember(int groupId, UUID player, Actor actor) {
        long now = System.currentTimeMillis();
        Title title = titleOps.groupTitleOf(groupId).orElse(null);
        MemberChangeResult result = txRunner.inTransaction(c -> {
            if (groupRepository.findById(c, groupId).filter(g -> !g.isDeleted()).isEmpty()) {
                return new MemberChangeResult.GroupNotFound(groupId);
            }
            if (!groupRepository.removeMember(c, groupId, player)) {
                return new MemberChangeResult.NotMember(groupId);
            }
            boolean revoked = title != null
                    && titleOps.revokeInTx(c, title.id(), groupId, player, "GROUP_REMOVE", now, actor);
            return new MemberChangeResult.Removed(groupId, revoked);
        });
        if (result instanceof MemberChangeResult.Removed(int ignored, boolean revoked)
                && revoked && title != null) {
            profileCache.updateIfPresent(player, profile -> profile.withoutTitle(title.id()));
        }
        return result;
    }

    /**
     * 写线程主体：对账（单事务差异化补授/补收；提交后缓存写穿 + 逐补授回调钩子）。
     *
     * @param heldPermissionNodes 玩家当前持有的组权限节点集合（paper 主线程预收集）
     */
    public ReconcileReport reconcile(UUID player, String playerName, Set<String> heldPermissionNodes) {
        long now = System.currentTimeMillis();
        Actor actor = Actor.system();
        ReconcileTx tx = txRunner.inTransaction(c -> {
            Set<Integer> memberOf = groupRepository.findGroupsOf(c, player).stream()
                    .map(PlayerGroup::id).collect(Collectors.toSet());
            List<GrantedEntry> granted = new ArrayList<>();
            List<Integer> revoked = new ArrayList<>();
            for (PlayerGroup group : groupRepository.findAll(c, false)) {
                Optional<Title> title = titleOps.groupTitleOf(group.id());
                if (title.isEmpty()) {
                    continue; // 组称号缺失/已软删：无可补授收
                }
                int titleId = title.get().id();
                boolean valid = memberOf.contains(group.id())
                        || heldPermissionNodes.contains(group.permissionNode());
                boolean owned = ownershipRepository.findActive(c, player, titleId).isPresent();
                if (valid && !owned) {
                    Long id = titleOps.grantInTx(c, titleId, group.id(), player, playerName, now, actor);
                    if (id != null) {
                        granted.add(new GrantedEntry(titleId, group.id(), id));
                    }
                } else if (!valid && owned
                        && titleOps.revokeInTx(c, titleId, group.id(), player,
                                "GROUP_RECONCILE", now, actor)) {
                    revoked.add(titleId);
                }
            }
            return new ReconcileTx(granted, revoked);
        });
        for (GrantedEntry entry : tx.granted()) {
            mergeGrantedIntoCache(player, playerName, entry.titleId(), entry.groupId(),
                    entry.ownershipId(), now);
        }
        for (Integer titleId : tx.revoked()) {
            profileCache.updateIfPresent(player, profile -> profile.withoutTitle(titleId));
        }
        // 钩子在全部缓存写穿之后逐条回调（羁绊检测按最终态判定）
        for (GrantedEntry entry : tx.granted()) {
            postGrantHook.onGranted(player, entry.titleId(), GrantSource.GROUP);
        }
        return new ReconcileReport(
                tx.granted().stream().map(GrantedEntry::titleId).toList(), tx.revoked());
    }

    /**
     * 提交后把新授予的组称号所有权合入档案缓存。
     */
    private void mergeGrantedIntoCache(UUID player, String playerName, int titleId, int groupId,
                                       long ownershipId, long now) {
        Ownership granted = new Ownership(ownershipId, titleId, player, playerName,
                GrantSource.GROUP, (long) groupId, now, null, OwnershipStatus.ACTIVE, null, null);
        profileCache.updateIfPresent(player, profile -> profile.withOwnership(granted));
    }

    /**
     * 对账事务的提交后回执。
     */
    private record ReconcileTx(List<GrantedEntry> granted, List<Integer> revoked) {
    }

    /**
     * 单条补授回执。
     */
    private record GrantedEntry(int titleId, int groupId, long ownershipId) {
    }
}
