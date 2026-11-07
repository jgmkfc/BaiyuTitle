package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExpiryService} 到期收敛单测：过期 ACTIVE + 佩戴引用 → sweep →
 * status=EXPIRED、栏位/随机池引用清除、审计落表、档案缓存同步。
 */
class ExpiryServiceTest extends BaseRepositoryTest {

    /** sweep 收敛过期所有权并清除一切佩戴引用。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void sweep_expiresAndClearsWardrobeReferences(TestDialect dialect) {
        initDatabase(dialect);
        PlayerProfileCache profileCache = new PlayerProfileCache();
        ExpiryService service = new ExpiryService(ownershipRepository, slotRepository,
                auditRepository, txRunner, dbExecutor, profileCache);

        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        int expiredTitle = 7;
        int keptTitle = 8;

        // 过期 ACTIVE + 永久 ACTIVE 各一条；过期称号被佩戴在栏位与随机池中
        Ownership expiredGrant = new Ownership(0, expiredTitle, player, "Player",
                GrantSource.PURCHASE, null, now - 10_000, now - 1_000, OwnershipStatus.ACTIVE, null, null);
        long expiredId = runInTx(c -> ownershipRepository.insertGrant(c, expiredGrant));
        runInTx(c -> ownershipRepository.insertGrant(c, new Ownership(0, keptTitle, player, "Player",
                GrantSource.ADMIN, null, now - 10_000, null, OwnershipStatus.ACTIVE, null, null)));
        runInTx(c -> {
            slotRepository.upsert(c, player, new SlotConfig(0, true, SlotMode.FIXED, expiredTitle));
            slotRepository.upsert(c, player, new SlotConfig(1, true, SlotMode.FIXED, keptTitle));
            slotRepository.replacePool(c, player, 2, Set.of(expiredTitle, keptTitle));
            return null;
        });

        // 档案缓存中挂载引用，验证提交后同步
        profileCache.put(new PlayerProfileCache.PlayerProfile(player, "Player",
                Map.of(expiredTitle, expiredGrant),
                List.of(new SlotConfig(0, true, SlotMode.FIXED, expiredTitle)),
                Map.of(2, Set.of(expiredTitle, keptTitle)), 0));

        assertEquals(1, service.sweep().join(), "应恰好收敛一条过期所有权");

        // 所有权：过期行变 EXPIRED，永久行保持 ACTIVE
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, expiredTitle)).isEmpty());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, keptTitle)).isPresent());
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM " + tables.titleOwnership()
                + " WHERE \"id\"=" + expiredId + " AND \"status\"='EXPIRED' AND \"active_marker\" IS NULL"));

        // 栏位引用：过期称号被清空，其余保留
        List<SlotConfig> slots = withConnection(c -> slotRepository.load(c, player));
        assertNull(slots.get(0).titleId(), "过期称号的固定栏位引用应被清空");
        assertEquals(keptTitle, slots.get(1).titleId());
        assertEquals(Set.of(keptTitle), withConnection(c -> slotRepository.loadPool(c, player, 2)));

        // 审计与缓存
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='EXPIRE' AND \"ownership_id\"=" + expiredId));
        PlayerProfileCache.PlayerProfile profile = profileCache.get(player).orElseThrow();
        assertTrue(profile.activeOwnerships().isEmpty(), "缓存中的过期所有权应被移除");
        assertNull(profile.slots().getFirst().titleId(), "缓存栏位引用应同步清空");
        assertEquals(Set.of(keptTitle), profile.randomPools().get(2));

        // 幂等：再扫一轮无事可做
        assertEquals(0, service.sweep().join());
    }
}
