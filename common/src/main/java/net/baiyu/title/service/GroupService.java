package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.GroupRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.group.GroupCreateResult;
import net.baiyu.title.service.group.GroupDeleteResult;
import net.baiyu.title.service.group.GroupInfo;
import net.baiyu.title.service.group.GroupLifecycle;
import net.baiyu.title.service.group.GroupMembership;
import net.baiyu.title.service.group.GroupTitleOps;
import net.baiyu.title.service.group.MemberChangeResult;
import net.baiyu.title.service.group.ReconcileReport;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家组服务：组 CRUD、成员变更与权限对账的唯一入口。
 * <p>语义：创建组自动建 GROUP 称号并绑定 groupId（权限节点
 * {@code title.group.<name>} 的 Bukkit 注册在 paper 侧）；删除组 = 软删组 +
 * 软删组称号 + 收回全部持有者的 ACTIVE 所有权（历史完整保留）；有效成员 =
 * member 表 ∪ 权限持有者，对账差异化补授/补收。
 * <p>本类是门面：组建删主体在 {@link GroupLifecycle}，成员变更与对账主体在
 * {@link GroupMembership}，组称号授予/收回原语在 {@link GroupTitleOps}；
 * 写操作统一提交写线程，缓存写穿只在 commit 之后。
 */
public final class GroupService {

    private final GroupRepository groupRepository;
    private final TransactionRunner txRunner;
    private final DbExecutor dbExecutor;
    private final TitleCatalogCache catalogCache;
    private final GroupTitleOps titleOps;
    private final GroupLifecycle lifecycle;
    private final GroupMembership membership;

    /**
     * 构造玩家组服务。
     *
     * @param groupRepository     玩家组仓储
     * @param titleRepository     称号仓储（自动建组称号）
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储（收回时清引用）
     * @param auditRepository     审计仓储
     * @param txRunner            事务执行器
     * @param dbExecutor          数据库执行器
     * @param catalogCache        称号目录缓存
     * @param profileCache        玩家档案缓存
     * @param postGrantHook       授予提交后钩子（可空 = no-op；羁绊检测入口）
     */
    public GroupService(GroupRepository groupRepository, TitleRepository titleRepository,
                        OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                        AuditRepository auditRepository, TransactionRunner txRunner,
                        DbExecutor dbExecutor, TitleCatalogCache catalogCache,
                        PlayerProfileCache profileCache,
                        @Nullable OwnershipService.PostGrantHook postGrantHook) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "玩家组仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.titleOps = new GroupTitleOps(
                Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空"),
                Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空"),
                Objects.requireNonNull(auditRepository, "审计仓储不能为空"), catalogCache);
        this.lifecycle = new GroupLifecycle(groupRepository, titleRepository, auditRepository,
                txRunner, catalogCache,
                Objects.requireNonNull(profileCache, "玩家档案缓存不能为空"), titleOps);
        this.membership = new GroupMembership(groupRepository, ownershipRepository, txRunner,
                profileCache, titleOps, postGrantHook);
    }

    /**
     * 创建玩家组：组名校验 → 同事务插组 + 自动建 GROUP 称号（绑定 groupId、
     * visible=false、tradable 领域强制 false）→ 提交后目录缓存写穿。
     * <p>权限节点 {@code title.group.<name>} 的注册由 paper 侧在本 future 完成后执行。
     *
     * @param name         组名（限 [a-z0-9_]）
     * @param rawTitleName 组称号原始名称（可空 = 用组名；三格式颜色码均可）
     * @param actor        操作者
     * @return 创建结果
     */
    public CompletableFuture<GroupCreateResult> createGroup(String name, @Nullable String rawTitleName,
                                                            Actor actor) {
        Objects.requireNonNull(name, "组名不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> lifecycle.create(name, rawTitleName, actor));
    }

    /**
     * 删除玩家组：同事务软删组 + 软删组称号 + 收回全部持有者的 ACTIVE 所有权
     * （含仅凭权限对账补授的持有者）；历史行完整保留（红线"软删不回收组称号历史"）。
     *
     * @param groupId 组 id
     * @param actor   操作者
     * @return 删除结果
     */
    public CompletableFuture<GroupDeleteResult> deleteGroup(int groupId, Actor actor) {
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> lifecycle.delete(groupId, actor));
    }

    /**
     * 加入组：member 行 + 授予组称号（恒 ACTIVE 直至移出），同事务。
     *
     * @param groupId    组 id
     * @param player     玩家 UUID
     * @param playerName 玩家名称
     * @param actor      操作者
     * @return 变更结果
     */
    public CompletableFuture<MemberChangeResult> addMember(int groupId, UUID player,
                                                           String playerName, Actor actor) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> membership.addMember(groupId, player, playerName, actor));
    }

    /**
     * 移出组：删 member 行 + 收回组称号并卸下装扮，同事务
     * （仍持组权限节点者由下次 reconcile 重新补授）。
     *
     * @param groupId 组 id
     * @param player  玩家 UUID
     * @param actor   操作者
     * @return 变更结果
     */
    public CompletableFuture<MemberChangeResult> removeMember(int groupId, UUID player, Actor actor) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> membership.removeMember(groupId, player, actor));
    }

    /**
     * 组对账（登录/权限变更/周期触发）：有效成员 = member 表 ∪ 权限持有者，
     * 差异化补授/补收。权限布尔由 paper 主线程预收集为节点集合传入。
     *
     * @param player              玩家 UUID
     * @param playerName          玩家名称
     * @param heldPermissionNodes 玩家持有的组权限节点集合（{@code title.group.<name>}）
     * @return 对账回执
     */
    public CompletableFuture<ReconcileReport> reconcile(UUID player, String playerName,
                                                        Set<String> heldPermissionNodes) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        return dbExecutor.submitWrite(() ->
                reconcileOnWriter(player, playerName, heldPermissionNodes));
    }

    /**
     * （写线程专用）组对账主体：登录预载须在<b>同一个</b>写任务内串行完成
     * 「对账 → 档案装载」，经 {@link #reconcile} 二次入队会造成写线程自等待
     * 死锁，故单独暴露本方法供既有写任务直接调用。
     *
     * @param player              玩家 UUID
     * @param playerName          玩家名称
     * @param heldPermissionNodes 玩家持有的组权限节点集合（paper 主线程预收集）
     * @return 对账回执
     */
    public ReconcileReport reconcileOnWriter(UUID player, String playerName,
                                             Set<String> heldPermissionNodes) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
        Set<String> nodes = heldPermissionNodes == null ? Set.of() : Set.copyOf(heldPermissionNodes);
        return membership.reconcile(player, playerName, nodes);
    }

    /**
     * 读路径：全部未软删组（读池，不走事务）。
     *
     * @return 组列表
     */
    public CompletableFuture<List<PlayerGroup>> listGroups() {
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> groupRepository.findAll(c, false)));
    }

    /**
     * 读路径：组详情（组 + 组称号 + member 表成员列表）。
     *
     * @param groupId 组 id
     * @return 组详情；组不存在或已软删时为空
     */
    public CompletableFuture<Optional<GroupInfo>> groupInfo(int groupId) {
        return dbExecutor.submitRead(() -> txRunner.withConnection(c -> {
            PlayerGroup group = groupRepository.findById(c, groupId)
                    .filter(g -> !g.isDeleted()).orElse(null);
            if (group == null) {
                return Optional.empty();
            }
            return Optional.of(new GroupInfo(group, titleOps.groupTitleOf(groupId).orElse(null),
                    groupRepository.findMembers(c, groupId)));
        }));
    }

    /**
     * （写线程同步）按 member 表判定玩家是否 GROUP 称号对应组的成员——
     * {@link WardrobeService.MembershipVerifier} 的 common 侧实现；权限侧并集
     * 由 paper 组合（装扮事务内无法触碰 Bukkit 权限系统）。
     *
     * @param player  玩家 UUID
     * @param titleId GROUP 称号 id
     * @return member 表内且组未软删返回 true
     */
    public boolean isTableMemberOf(UUID player, int titleId) {
        Integer groupId = catalogCache.byId(titleId)
                .filter(t -> !t.isDeleted() && t.category() == TitleCategory.GROUP)
                .map(Title::groupId).orElse(null);
        if (groupId == null) {
            return false;
        }
        return txRunner.withConnection(c -> groupRepository.findGroupsOf(c, player)).stream()
                .anyMatch(g -> g.id() == groupId);
    }

    /**
     * 同步只读：组对应的 GROUP 称号（目录缓存）。
     *
     * @param groupId 组 id
     * @return 组称号；不存在或已软删时为空
     */
    public Optional<Title> groupTitleOf(int groupId) {
        return titleOps.groupTitleOf(groupId);
    }
}
