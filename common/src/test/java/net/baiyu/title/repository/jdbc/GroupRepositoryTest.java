package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcGroupRepository} 双方言单测（表 V5：player_group / player_group_member）。
 */
class GroupRepositoryTest extends BaseRepositoryTest {

    /** insert → findById / findByName 全字段往返（可空列非空/全空两态）；不存在返回空。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_thenFind_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        int id = runInTx(c -> groupRepository.insert(c,
                new PlayerGroup(0, "vip_alpha", "VIP 甲组", "Admin", 1_000L, null)));
        assertTrue(id > 0, "主键应由数据库生成");
        PlayerGroup expected = new PlayerGroup(id, "vip_alpha", "VIP 甲组", "Admin", 1_000L, null);
        assertEquals(expected, withConnection(c -> groupRepository.findById(c, id)).orElseThrow());
        assertEquals(expected, withConnection(c -> groupRepository.findByName(c, "vip_alpha")).orElseThrow());

        // 可空列全空（控制台创建、无展示名）
        int bareId = runInTx(c -> groupRepository.insert(c,
                new PlayerGroup(0, "bare", null, null, 2_000L, null)));
        assertEquals(new PlayerGroup(bareId, "bare", null, null, 2_000L, null),
                withConnection(c -> groupRepository.findById(c, bareId)).orElseThrow());
        assertTrue(withConnection(c -> groupRepository.findById(c, 999_999)).isEmpty());
        assertTrue(withConnection(c -> groupRepository.findByName(c, "missing")).isEmpty());
    }

    /** findAll：includeDeleted 开关生效，按 id 升序；findByName 含软删行。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findAll_respectsIncludeDeletedFlag(TestDialect dialect) {
        initDatabase(dialect);
        int aliveId = insertGroup("alive");
        int deletedId = insertGroup("gone");
        runInTx(c -> {
            groupRepository.softDelete(c, deletedId, 5_000L);
            return null;
        });

        assertEquals(List.of(aliveId),
                idsOf(withConnection(c -> groupRepository.findAll(c, false))));
        assertEquals(List.of(aliveId, deletedId),
                idsOf(withConnection(c -> groupRepository.findAll(c, true))));
        // findByName 含软删行（接口约定：由调用方按需过滤）
        assertEquals(5_000L, withConnection(c -> groupRepository.findByName(c, "gone"))
                .orElseThrow().deletedAt());
    }

    /** updateDisplayName：可更新为新值，也可清除为 NULL。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void updateDisplayName_setsAndClears(TestDialect dialect) {
        initDatabase(dialect);
        int id = runInTx(c -> groupRepository.insert(c,
                new PlayerGroup(0, "vip", "旧名", "Admin", 1_000L, null)));

        runInTx(c -> {
            groupRepository.updateDisplayName(c, id, "新名");
            return null;
        });
        assertEquals("新名", withConnection(c -> groupRepository.findById(c, id)).orElseThrow().displayName());

        runInTx(c -> {
            groupRepository.updateDisplayName(c, id, null);
            return null;
        });
        assertNull(withConnection(c -> groupRepository.findById(c, id)).orElseThrow().displayName());
    }

    /** softDelete：置 deleted_at；重复软删不覆盖首次时刻（WHERE deleted_at IS NULL 守卫）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void softDelete_isIdempotentOnTimestamp(TestDialect dialect) {
        initDatabase(dialect);
        int id = insertGroup("vip");

        runInTx(c -> {
            groupRepository.softDelete(c, id, 5_000L);
            return null;
        });
        assertEquals(5_000L, withConnection(c -> groupRepository.findById(c, id)).orElseThrow().deletedAt());

        runInTx(c -> {
            groupRepository.softDelete(c, id, 9_000L);
            return null;
        });
        assertEquals(5_000L, withConnection(c -> groupRepository.findById(c, id)).orElseThrow().deletedAt(),
                "重复软删不应覆盖首次软删时刻");
    }

    /** 成员管理：addMember → findMembers 按 (added_at, player_name) 排序往返；removeMember 二态。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void members_addFindRemove(TestDialect dialect) {
        initDatabase(dialect);
        int groupId = insertGroup("vip");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // 同一 added_at 时按名字排序：故意乱序插入
        GroupMember b = new GroupMember(groupId, second, "Bob", null, 2_000L);
        GroupMember a = new GroupMember(groupId, first, "Alice", "Admin", 2_000L);
        runInTx(c -> {
            groupRepository.addMember(c, b);
            groupRepository.addMember(c, a);
            return null;
        });

        assertEquals(List.of(a, b), withConnection(c -> groupRepository.findMembers(c, groupId)));

        boolean removed = runInTx(c -> groupRepository.removeMember(c, groupId, first));
        assertTrue(removed);
        boolean removedAgain = runInTx(c -> groupRepository.removeMember(c, groupId, first));
        assertFalse(removedAgain, "已不在组内应返回 false");
        assertEquals(List.of(b), withConnection(c -> groupRepository.findMembers(c, groupId)));
    }

    /** findGroupsOf：只命中本人所在且未软删的组。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findGroupsOf_excludesDeletedAndOthers(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        int aliveId = insertGroup("alive");
        int deletedId = insertGroup("gone");
        int othersId = insertGroup("others");
        runInTx(c -> {
            groupRepository.addMember(c, new GroupMember(aliveId, player, "P", null, 1_000L));
            groupRepository.addMember(c, new GroupMember(deletedId, player, "P", null, 1_000L));
            groupRepository.addMember(c, new GroupMember(othersId, UUID.randomUUID(), "X", null, 1_000L));
            groupRepository.softDelete(c, deletedId, 5_000L);
            return null;
        });

        assertEquals(List.of(aliveId), idsOf(withConnection(c -> groupRepository.findGroupsOf(c, player))));
        assertTrue(withConnection(c -> groupRepository.findGroupsOf(c, UUID.randomUUID())).isEmpty());
    }

    /** 插入一个最小玩家组并返回主键。 */
    private int insertGroup(String name) {
        return runInTx(c -> groupRepository.insert(c, new PlayerGroup(0, name, null, null, 1_000L, null)));
    }

    /** 组 id 列表。 */
    private static List<Integer> idsOf(List<PlayerGroup> groups) {
        return groups.stream().map(PlayerGroup::id).toList();
    }
}
