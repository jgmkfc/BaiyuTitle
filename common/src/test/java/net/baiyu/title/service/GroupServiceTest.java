package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.service.group.GroupCreateResult;
import net.baiyu.title.service.group.GroupDeleteResult;
import net.baiyu.title.service.group.MemberChangeResult;
import net.baiyu.title.service.group.ReconcileReport;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GroupService} 双方言测试：组 CRUD、成员变更收授、
 * 权限对账补授/补收、删除组全量收回，以及"被移出组后 reconcile 前窗口内
 * 不可新装扮"（GroupService 侧闭环）。
 */
class GroupServiceTest extends BaseRepositoryTest {

    /** 测试管理员。 */
    private static final Actor ADMIN = new Actor(UUID.randomUUID(), "Admin");

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;
    private GroupService service;

    /** 创建组：组行 + GROUP 称号（绑定 groupId、默认隐藏、强制不可交易）+ 缓存写穿。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void createGroup_createsGroupTitleAndCache(TestDialect dialect) {
        initDatabase(dialect);
        newService();

        GroupCreateResult.Created created = assertInstanceOf(GroupCreateResult.Created.class,
                service.createGroup("beihai_city", "&f北海市", ADMIN).join());
        assertTrue(created.group().id() > 0);
        assertEquals("beihai_city", created.group().name());
        assertEquals("title.group.beihai_city", created.group().permissionNode());
        Title title = created.title();
        assertEquals(TitleCategory.GROUP, title.category());
        assertEquals((Integer) created.group().id(), title.groupId());
        assertFalse(title.visible(), "组称号默认不上架（业务 §1.1.2.2）");
        assertFalse(title.tradable(), "组称号强制不可交易（不变量 §十 5）");
        assertEquals("<white>北海市", title.name());
        assertTrue(catalogCache.byId(title.id()).isPresent(), "提交后目录缓存写穿");
        assertEquals(1L, countRows(tables.playerGroup()));

        // 非法组名与重名分别拒绝
        assertInstanceOf(GroupCreateResult.InvalidName.class,
                service.createGroup("Bad-Name", null, ADMIN).join());
        assertInstanceOf(GroupCreateResult.DuplicateName.class,
                service.createGroup("beihai_city", null, ADMIN).join());
        assertEquals(1L, countRows(tables.playerGroup()), "拒绝路径零落库");
    }

    /** 加组授予（恒 ACTIVE 无到期）、重复加组幂等、移出收回并卸下装扮。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void addAndRemoveMember_grantsAndRevokes(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        GroupCreateResult.Created created = create("pine_town");
        int groupId = created.group().id();
        int titleId = created.title().id();
        UUID member = UUID.randomUUID();

        MemberChangeResult.Added added = assertInstanceOf(MemberChangeResult.Added.class,
                service.addMember(groupId, member, "Mia", ADMIN).join());
        assertNotNull(added.ownershipId(), "首次入组应授予组称号");
        Ownership ownership = withConnection(c ->
                ownershipRepository.findActive(c, member, titleId)).orElseThrow();
        assertEquals(GrantSource.GROUP, ownership.source());
        assertNull(ownership.expiresAt(), "组称号恒 ACTIVE 直至移出");
        assertEquals((Long) (long) groupId, ownership.sourceRef());

        assertInstanceOf(MemberChangeResult.AlreadyMember.class,
                service.addMember(groupId, member, "Mia", ADMIN).join());

        // 佩戴后移出：member 行删除、所有权 REVOKED、栏位引用清空
        runInTx(c -> {
            slotRepository.upsert(c, member, new SlotConfig(1, true, SlotMode.FIXED, titleId));
            return null;
        });
        MemberChangeResult.Removed removed = assertInstanceOf(MemberChangeResult.Removed.class,
                service.removeMember(groupId, member, ADMIN).join());
        assertTrue(removed.titleRevoked());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, member, titleId)).isEmpty());
        assertNull(withConnection(c -> slotRepository.load(c, member)).getFirst().titleId(),
                "移出必须卸下佩戴");
        assertInstanceOf(MemberChangeResult.NotMember.class,
                service.removeMember(groupId, member, ADMIN).join());
        assertInstanceOf(MemberChangeResult.GroupNotFound.class,
                service.addMember(9999, member, "Mia", ADMIN).join());
    }

    /** 对账：权限持有者补授；双失（无表内记录且无权限）补收并卸下；表内成员保留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void reconcile_grantsAndRevokesByEffectiveMembership(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        GroupCreateResult.Created created = create("bamboo_village");
        int groupId = created.group().id();
        int titleId = created.title().id();
        String node = created.group().permissionNode();
        UUID player = UUID.randomUUID();

        // 仅凭权限的有效成员：补授
        ReconcileReport granted = service.reconcile(player, "Perm", Set.of(node)).join();
        assertEquals(List.of(titleId), granted.grantedTitleIds());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, titleId)).isPresent());

        // 幂等：再对账无变更
        assertFalse(service.reconcile(player, "Perm", Set.of(node)).join().hasChanges());

        // 失去权限（且不在 member 表）：补收 + 卸下佩戴
        runInTx(c -> {
            slotRepository.upsert(c, player, new SlotConfig(1, true, SlotMode.FIXED, titleId));
            return null;
        });
        ReconcileReport revoked = service.reconcile(player, "Perm", Set.of()).join();
        assertEquals(List.of(titleId), revoked.revokedTitleIds());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, titleId)).isEmpty());
        assertNull(withConnection(c -> slotRepository.load(c, player)).getFirst().titleId());

        // member 表成员即便无权限也是有效成员：不补收
        UUID tableMember = UUID.randomUUID();
        service.addMember(groupId, tableMember, "Tab", ADMIN).join();
        assertFalse(service.reconcile(tableMember, "Tab", Set.of()).join().hasChanges());
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, tableMember, titleId)).isPresent());
    }

    /** M1 评审遗留 2：失权后 reconcile 前的窗口内（仍持 ACTIVE），组资格钩子挡住新装扮。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void membershipVerifier_blocksEquipBeforeReconcile(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        GroupCreateResult.Created created = create("snow_fort");
        int titleId = created.title().id();
        String node = created.group().permissionNode();
        UUID player = UUID.randomUUID();
        service.reconcile(player, "Perm", Set.of(node)).join(); // 凭权限补授

        // 资格钩子 = member 表 ∪ 当前权限集（paper 侧组合方式的等价物）
        Set<String> currentPerms = Set.of(); // 权限已被外部收回，reconcile 尚未运行
        WardrobeService wardrobe = new WardrobeService(ownershipRepository, slotRepository,
                txRunner, dbExecutor, catalogCache, profileCache, ignored -> 3,
                (uuid, tid) -> service.isTableMemberOf(uuid, tid)
                        || currentPerms.contains(node));
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, titleId)).isPresent(),
                "窗口内所有权仍 ACTIVE");
        assertEquals(WardrobeService.WardrobeResult.TITLE_NOT_OWNED,
                wardrobe.setSlotTitle(player, 1, titleId).join(),
                "reconcile 前窗口内不可新装扮组称号");

        // 入 member 表后即为有效成员：钩子放行
        service.addMember(created.group().id(), player, "Perm", ADMIN).join();
        assertEquals(WardrobeService.WardrobeResult.SUCCESS,
                wardrobe.setSlotTitle(player, 1, titleId).join());
    }

    /** 删除组：软删组 + 软删组称号 + 全量收回（含权限补授的持有者）；历史行保留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void deleteGroup_softDeletesAndRevokesAllHolders(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        GroupCreateResult.Created created = create("old_guild");
        int groupId = created.group().id();
        int titleId = created.title().id();
        UUID tableMember = UUID.randomUUID();
        UUID permHolder = UUID.randomUUID();
        service.addMember(groupId, tableMember, "Tab", ADMIN).join();
        service.reconcile(permHolder, "Perm", Set.of(created.group().permissionNode())).join();

        GroupDeleteResult.Deleted deleted = assertInstanceOf(GroupDeleteResult.Deleted.class,
                service.deleteGroup(groupId, ADMIN).join());
        assertEquals((Integer) titleId, deleted.titleId());
        assertEquals(2, deleted.revokedCount(), "表内成员与权限持有者都要收回");
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, tableMember, titleId)).isEmpty());
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, permHolder, titleId)).isEmpty());
        assertEquals(2L, countRows(tables.titleOwnership()),
                "软删不回收组称号历史（REVOKED 历史行完整保留）");
        Title stored = withConnection(c -> titleRepository.loadAll(c, true)).stream()
                .filter(t -> t.id() == titleId).findFirst().orElseThrow();
        assertTrue(stored.isDeleted(), "组称号随组软删");
        assertTrue(catalogCache.byId(titleId).orElseThrow().isDeleted(), "缓存同步软删");
        assertTrue(service.listGroups().join().isEmpty(), "软删组不再出现在列表");
        assertInstanceOf(GroupDeleteResult.NotFound.class,
                service.deleteGroup(groupId, ADMIN).join());
    }

    /** 查询门面：listGroups / groupInfo / groupTitleOf。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void queries_exposeGroupsAndMembers(TestDialect dialect) {
        initDatabase(dialect);
        newService();
        GroupCreateResult.Created created = create("query_town");
        int groupId = created.group().id();
        service.addMember(groupId, UUID.randomUUID(), "M1", ADMIN).join();
        service.addMember(groupId, UUID.randomUUID(), "M2", ADMIN).join();

        assertEquals(1, service.listGroups().join().size());
        var info = service.groupInfo(groupId).join().orElseThrow();
        assertEquals("query_town", info.group().name());
        assertEquals(2, info.members().size());
        assertEquals(created.title().id(), info.title().id());
        assertEquals(created.title().id(), service.groupTitleOf(groupId).orElseThrow().id());
        assertEquals(Optional.empty(), service.groupInfo(9999).join());
    }

    // ---------- 工具 ----------

    /** 构造服务（同时新建两个缓存实例）。 */
    private void newService() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        service = new GroupService(groupRepository, titleRepository, ownershipRepository,
                slotRepository, auditRepository, txRunner, dbExecutor, catalogCache, profileCache,
                null);
    }

    /** 创建组的便捷入口（组称号名 = 组名）。 */
    private GroupCreateResult.Created create(String name) {
        return assertInstanceOf(GroupCreateResult.Created.class,
                service.createGroup(name, null, ADMIN).join());
    }
}
