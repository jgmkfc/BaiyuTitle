package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.repository.exception.DuplicateActiveException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcOwnershipRepository} 双方言单测（表 V2：title_ownership）。
 */
class OwnershipRepositoryTest extends BaseRepositoryTest {

    /** insertGrant → findActive / findActiveByOwner 全字段往返。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insertGrant_thenFindActive_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        UUID owner = UUID.randomUUID();
        Ownership grant = grant(owner, 7, GrantSource.PURCHASE, 12_345L, 99_999L);
        long id = runInTx(c -> ownershipRepository.insertGrant(c, grant));

        Optional<Ownership> found = withConnection(c -> ownershipRepository.findActive(c, owner, 7));
        assertTrue(found.isPresent());
        assertEquals(withId(grant, id), found.get());

        List<Ownership> all = withConnection(c -> ownershipRepository.findActiveByOwner(c, owner));
        assertEquals(List.of(withId(grant, id)), all);

        // 其他玩家 / 其他称号查不到
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, owner, 8)).isEmpty());
        assertTrue(withConnection(c -> ownershipRepository.findActiveByOwner(c, UUID.randomUUID())).isEmpty());
    }

    /** 同 (owner, title) 双 ACTIVE 违反唯一约束，转抛 DuplicateActiveException。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void duplicateActive_throwsDuplicateActiveException(TestDialect dialect) {
        initDatabase(dialect);
        UUID owner = UUID.randomUUID();
        runInTx(c -> ownershipRepository.insertGrant(c, grant(owner, 7, GrantSource.ADMIN, null, null)));

        DuplicateActiveException thrown = assertThrows(DuplicateActiveException.class,
                () -> runInTx(c -> ownershipRepository.insertGrant(c,
                        grant(owner, 7, GrantSource.PURCHASE, 1L, null))));
        assertEquals(owner, thrown.ownerUuid());
        assertEquals(7, thrown.titleId());

        // 不同称号 / 不同玩家不冲突
        runInTx(c -> ownershipRepository.insertGrant(c, grant(owner, 8, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(UUID.randomUUID(), 7, GrantSource.ADMIN, null, null)));
    }

    /** endActive 条件迁移：成功一次、重复迁移返回 false；结束后可再 insertGrant。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void endActive_thenRegrant_allowed(TestDialect dialect) {
        initDatabase(dialect);
        UUID owner = UUID.randomUUID();
        long firstId = runInTx(c -> ownershipRepository.insertGrant(c, grant(owner, 7, GrantSource.ADMIN, null, null)));

        assertTrue(endActive(firstId, OwnershipStatus.REVOKED, "回收", 5_000L));
        assertFalse(endActive(firstId, OwnershipStatus.EXPIRED, "再次", 6_000L),
                "已非 ACTIVE 的行不允许再次迁移");
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, owner, 7)).isEmpty());

        // 唯一约束的 active_marker 已置 NULL，可再次授予
        long secondId = runInTx(c -> ownershipRepository.insertGrant(c, grant(owner, 7, GrantSource.PURCHASE, 2L, null)));
        assertTrue(secondId > firstId);
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, owner, 7)).isPresent());
    }

    /** pickExpired 只取"已过期且仍 ACTIVE"的行，遵守批大小。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void pickExpired_onlyExpiredActive(TestDialect dialect) {
        initDatabase(dialect);
        long now = 100_000L;
        UUID expiredOwner = UUID.randomUUID();
        UUID futureOwner = UUID.randomUUID();
        UUID permanentOwner = UUID.randomUUID();
        UUID endedOwner = UUID.randomUUID();

        long expiredId = runInTx(c -> ownershipRepository.insertGrant(c,
                grant(expiredOwner, 1, GrantSource.PURCHASE, null, now - 1)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(futureOwner, 1, GrantSource.PURCHASE, null, now + 60_000)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(permanentOwner, 1, GrantSource.ADMIN, null, null)));
        // 已到期但状态已 EXPIRED 的历史行不应再被捞取
        runInTx(c -> ownershipRepository.insertGrant(c, new Ownership(0, 1, endedOwner, "Ended",
                GrantSource.PURCHASE, null, 1_000L, now - 500, OwnershipStatus.EXPIRED, now - 100, "EXPIRED")));

        List<Ownership> picked = withConnection(c -> ownershipRepository.pickExpired(c, now, 10));
        assertEquals(1, picked.size());
        assertEquals(expiredId, picked.getFirst().id());
        assertEquals(expiredOwner, picked.getFirst().ownerUuid());

        // 批大小限制
        assertEquals(0, withConnection(c -> ownershipRepository.pickExpired(c, now, 0)).size());
    }

    /** findOwnersHavingAll：只返回同时 ACTIVE 持有全部需求称号的玩家。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void findOwnersHavingAll_intersectionOnly(TestDialect dialect) {
        initDatabase(dialect);
        UUID complete = UUID.randomUUID();
        UUID alsoComplete = UUID.randomUUID();
        UUID partial = UUID.randomUUID();
        UUID lostOne = UUID.randomUUID();

        runInTx(c -> ownershipRepository.insertGrant(c, grant(complete, 1, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(complete, 2, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(complete, 3, GrantSource.ADMIN, null, null)));

        runInTx(c -> ownershipRepository.insertGrant(c, grant(alsoComplete, 1, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(alsoComplete, 2, GrantSource.ADMIN, null, null)));

        runInTx(c -> ownershipRepository.insertGrant(c, grant(partial, 1, GrantSource.ADMIN, null, null)));

        long lostId = runInTx(c -> ownershipRepository.insertGrant(c, grant(lostOne, 2, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.insertGrant(c, grant(lostOne, 1, GrantSource.ADMIN, null, null)));
        runInTx(c -> ownershipRepository.endActive(c, lostId, OwnershipStatus.TRADED, "交易转出", 9_000L));

        List<UUID> owners = withConnection(c -> ownershipRepository.findOwnersHavingAll(c, Set.of(1, 2)));
        assertEquals(Set.of(complete, alsoComplete), Set.copyOf(owners));
    }

    /** 在写线程事务中结束一条 ACTIVE 所有权。 */
    private boolean endActive(long ownershipId, OwnershipStatus to, String reason, long now) {
        return runInTx(c -> ownershipRepository.endActive(c, ownershipId, to, reason, now));
    }

    /** 构造 ACTIVE 授予行。 */
    private static Ownership grant(UUID owner, int titleId, GrantSource source, Long sourceRef, Long expiresAt) {
        return new Ownership(0, titleId, owner, "Tester", source, sourceRef,
                1_000L, expiresAt, OwnershipStatus.ACTIVE, null, null);
    }

    /** 以数据库生成 id 重建期望值。 */
    private static Ownership withId(Ownership o, long id) {
        return new Ownership(id, o.titleId(), o.ownerUuid(), o.ownerName(), o.source(), o.sourceRef(),
                o.acquiredAt(), o.expiresAt(), o.status(), o.endedAt(), o.endedReason());
    }
}
