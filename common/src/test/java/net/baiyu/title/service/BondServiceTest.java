package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BondService} 双方言测试：集齐自动授予（松竹梅→岁寒三友）、
 * 级联（羁绊套羁绊）、已持有短路、回溯补发（含离线/到期需求排除）、
 * 失去条件称号不回收羁绊。
 */
class BondServiceTest extends BaseRepositoryTest {

    /** 测试管理员。 */
    private static final Actor ADMIN = new Actor(UUID.randomUUID(), "Admin");

    private TitleCatalogCache catalogCache;
    private PlayerProfileCache profileCache;
    private BondService bondService;
    private OwnershipService ownershipService;

    /** 集齐触发：授予第三个条件称号后自动获得羁绊（source=BOND，恒永久）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void grantingLastRequirement_autoGrantsBond(TestDialect dialect) {
        initDatabase(dialect);
        newServices();
        int pine = seedNormal("松");
        int bamboo = seedNormal("竹");
        int plum = seedNormal("梅");
        int bond = seedBond("岁寒三友", Set.of(pine, bamboo, plum));
        UUID player = UUID.randomUUID();

        grantViaService(player, pine);
        grantViaService(player, bamboo);
        awaitBondQueue();
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, bond)).isEmpty(),
                "未集齐不得授予");

        grantViaService(player, plum);
        awaitBondQueue();
        Ownership granted = withConnection(c ->
                ownershipRepository.findActive(c, player, bond)).orElseThrow();
        assertEquals(GrantSource.BOND, granted.source());
        assertNull(granted.expiresAt(), "羁绊授予恒永久");
        assertEquals(1L, auditCount("BOND_GRANT"));

        // 已持有短路：再触发检测零重复授予
        assertEquals(0, bondService.checkNow(player, plum).join());
        assertEquals(1L, auditCount("BOND_GRANT"));
    }

    /** 级联：羁绊 A 集齐后自动授予，随即使依赖 A 的羁绊 B 也集齐并授予（同一次检测内收敛）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void cascade_bondGrantTriggersDependentBond(TestDialect dialect) {
        initDatabase(dialect);
        newServices();
        int a = seedNormal("甲");
        int b = seedNormal("乙");
        int c1 = seedNormal("丙");
        int bondA = seedBond("小羁绊", Set.of(a, b));
        int bondB = seedBond("大羁绊", Set.of(bondA, c1));
        UUID player = UUID.randomUUID();

        grantViaService(player, b);
        grantViaService(player, c1);
        awaitBondQueue();
        grantViaService(player, a); // 触发：bondA 集齐 → 级联 bondB 集齐
        awaitBondQueue();

        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, bondA)).isPresent());
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, bondB)).isPresent(),
                "羁绊授予须级联检测依赖它的羁绊");
        assertEquals(2L, auditCount("BOND_GRANT"));
    }

    /** 回溯补发：已集齐者（含离线，按 DB 扫描）补授；已持有者与到期需求者排除。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void backfill_grantsCompletedOwnersOnly(TestDialect dialect) {
        initDatabase(dialect);
        newServices();
        int pine = seedNormal("松");
        int bamboo = seedNormal("竹");
        UUID completed = UUID.randomUUID();   // 集齐（离线：不在档案缓存）
        UUID alreadyHeld = UUID.randomUUID(); // 集齐但已持有羁绊
        UUID partial = UUID.randomUUID();     // 只有一个条件
        UUID expired = UUID.randomUUID();     // 条件之一已到期未收敛
        seedOwnership(completed, pine, null);
        seedOwnership(completed, bamboo, null);
        seedOwnership(alreadyHeld, pine, null);
        seedOwnership(alreadyHeld, bamboo, null);
        seedOwnership(partial, pine, null);
        seedOwnership(expired, pine, null);
        seedOwnership(expired, bamboo, 1_000L); // 已过期
        int bond = seedBond("岁寒二友", Set.of(pine, bamboo));
        seedOwnership(alreadyHeld, bond, null);

        assertEquals(1, bondService.backfill(bond, ADMIN).join(), "只补授 completed 一人");
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, completed, bond)).isPresent());
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, partial, bond)).isEmpty());
        assertTrue(withConnection(c ->
                ownershipRepository.findActive(c, expired, bond)).isEmpty(),
                "到期未收敛的条件不算集齐");
        // 幂等：再跑一次零新增
        assertEquals(0, bondService.backfill(bond, ADMIN).join());
    }

    /** 失去条件称号不回收羁绊（红线）：收回需求称号后羁绊保持 ACTIVE。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void losingRequirement_doesNotRevokeBond(TestDialect dialect) {
        initDatabase(dialect);
        newServices();
        int pine = seedNormal("松");
        int bamboo = seedNormal("竹");
        int bond = seedBond("岁寒二友", Set.of(pine, bamboo));
        UUID player = UUID.randomUUID();
        grantViaService(player, pine);
        grantViaService(player, bamboo);
        awaitBondQueue();
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, bond)).isPresent());

        assertTrue(ownershipService.revoke(player, pine, "TEST", ADMIN).join());
        awaitBondQueue();
        assertTrue(withConnection(c -> ownershipRepository.findActive(c, player, bond)).isPresent(),
                "失去条件称号不得回收羁绊");
    }

    // ---------- 工具 ----------

    /** 构造服务：OwnershipService 的授予钩子接 BondService（生产装配的等价物）。 */
    private void newServices() {
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        bondService = new BondService(ownershipRepository, auditRepository, txRunner, dbExecutor,
                catalogCache, profileCache, null);
        ownershipService = new OwnershipService(ownershipRepository, slotRepository,
                auditRepository, txRunner, dbExecutor, catalogCache, profileCache, bondService);
    }

    /** 落库 NORMAL 称号并刷缓存。 */
    private int seedNormal(String plain) {
        int id = runInTx(c -> titleRepository.insert(c, Title.builder()
                .name("<white>" + plain).namePlain(plain).createdAt(1L).updatedAt(1L).build()));
        reloadCatalog();
        return id;
    }

    /** 落库 BOND 称号（含需求集）并刷缓存。 */
    private int seedBond(String plain, Set<Integer> requirements) {
        int id = runInTx(c -> {
            int bondId = titleRepository.insert(c, Title.builder()
                    .name("<white>" + plain).namePlain(plain).category(TitleCategory.BOND)
                    .createdAt(1L).updatedAt(1L).build());
            titleRepository.replaceBondRequirements(c, bondId, requirements);
            return bondId;
        });
        reloadCatalog();
        return id;
    }

    /** 全量重载目录缓存（含羁绊反向索引重建）。 */
    private void reloadCatalog() {
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
    }

    /** 经 OwnershipService 授予（触发 PostGrantHook → 羁绊检测入队）。 */
    private void grantViaService(UUID player, int titleId) {
        assertInstanceOf(OwnershipService.GrantResult.Granted.class,
                ownershipService.grant(player, "Tester", titleId, GrantSource.ADMIN,
                        null, null, ADMIN).join());
    }

    /** 直接落库一条 ACTIVE 所有权（回溯测试的种子数据，不触发钩子）。 */
    private void seedOwnership(UUID player, int titleId, Long expiresAt) {
        runInTx(c -> ownershipRepository.insertGrant(c, new Ownership(0, titleId, player, "Seed",
                GrantSource.ADMIN, null, 1L, expiresAt, OwnershipStatus.ACTIVE, null, null)));
    }

    /** 等待羁绊检测队列排空（写线程 FIFO：屏障任务完成即此前的检测已执行）。 */
    private void awaitBondQueue() {
        dbExecutor.submitWrite(() -> null).join();
    }

    /** 按事件类型统计审计行数。 */
    private long auditCount(String eventType) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='" + eventType + "'");
    }
}
