package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcTitleRepository} 双方言往返单测（表 V1）。
 */
class TitleRepositoryTest extends BaseRepositoryTest {

    /** insert → loadAll 全字段一致（含条件与羁绊需求）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insert_thenLoadAll_roundTripsAllFields(TestDialect dialect) {
        initDatabase(dialect);
        UUID creator = UUID.randomUUID();
        List<PurchaseCondition> conditions = List.of(
                new VaultCondition(new BigDecimal("123.45")),
                new CoinCondition(66),
                new ItemCondition("QkFTRTY0REFUQQ==", 32),
                new PermissionCondition("baiyutitle.vip"));
        Title normal = Title.builder()
                .name("<#d9afd9>稗<#d2b4da>玉")
                .namePlain("稗玉")
                .category(TitleCategory.NORMAL)
                .combineLogic(CombineLogic.OR)
                .visible(true)
                .tradable(true)
                .rarity("传说")
                .description(List.of("第一行", "第二行"))
                .iconMaterial("minecraft:bamboo")
                .holdDurationDays(30)
                .limitMode(LimitMode.WINDOW)
                .windowStart(LocalDate.of(2026, 7, 1))
                .windowEnd(LocalDate.of(2026, 7, 10))
                .createdBy(creator)
                .createdByName("BaiYu")
                .createdAt(1_000L)
                .updatedAt(2_000L)
                .conditions(conditions)
                .build();
        int normalId = runInTx(c -> titleRepository.insert(c, normal));

        Title bond = Title.builder()
                .name("<white>岁寒三友")
                .namePlain("岁寒三友")
                .category(TitleCategory.BOND)
                .createdAt(3_000L)
                .updatedAt(3_000L)
                .bondRequirements(Set.of(normalId))
                .build();
        int bondId = runInTx(c -> titleRepository.insert(c, bond));

        List<Title> loaded = withConnection(c -> titleRepository.loadAll(c));
        assertEquals(2, loaded.size());

        Title loadedNormal = loaded.stream().filter(t -> t.id() == normalId).findFirst().orElseThrow();
        // 条件列表顺序不保证，规整后整体比较；其余字段用 record equals 全量比较
        assertEquals(new HashSet<>(conditions), new HashSet<>(loadedNormal.conditions()));
        assertEquals(normal.toBuilder().id(normalId).conditions(List.of()).build(),
                loadedNormal.toBuilder().conditions(List.of()).build());

        Title loadedBond = loaded.stream().filter(t -> t.id() == bondId).findFirst().orElseThrow();
        assertEquals(bond.toBuilder().id(bondId).build(), loadedBond);
        assertEquals(Set.of(normalId), loadedBond.bondRequirements());
    }

    /** update 可编辑字段后 loadAll 反映新值。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void update_editsAreVisibleOnReload(TestDialect dialect) {
        initDatabase(dialect);
        int id = runInTx(c -> titleRepository.insert(c, simpleTitle("<white>原名", "原名")));
        Title current = withConnection(c -> titleRepository.loadAll(c)).getFirst();
        Title edited = current.toBuilder()
                .name("<red>新名").namePlain("新名")
                .rarity("史诗")
                .visible(false)
                .holdDurationDays(7)
                .limitMode(LimitMode.TOTAL).totalCap(10)
                .description(List.of("新说明"))
                .updatedAt(9_999L)
                .build();
        runInTx(c -> {
            titleRepository.update(c, edited);
            return null;
        });
        Title reloaded = withConnection(c -> titleRepository.loadAll(c)).getFirst();
        assertEquals(edited, reloaded);
        assertEquals(id, reloaded.id());
    }

    /** softDelete 后 loadAll(includeDeleted=false) 不含、includeDeleted=true 含。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void softDelete_hiddenFromDefaultLoad(TestDialect dialect) {
        initDatabase(dialect);
        int keepId = runInTx(c -> titleRepository.insert(c, simpleTitle("<white>保留", "保留")));
        int dropId = runInTx(c -> titleRepository.insert(c, simpleTitle("<white>删除", "删除")));
        runInTx(c -> {
            titleRepository.softDelete(c, dropId, 5_000L);
            return null;
        });

        List<Title> visible = withConnection(c -> titleRepository.loadAll(c, false));
        assertEquals(1, visible.size());
        assertEquals(keepId, visible.getFirst().id());

        List<Title> all = withConnection(c -> titleRepository.loadAll(c, true));
        assertEquals(2, all.size());
        Title deleted = all.stream().filter(t -> t.id() == dropId).findFirst().orElseThrow();
        assertTrue(deleted.isDeleted());
        assertEquals(5_000L, deleted.deletedAt());
    }

    /** tryReserveSoldCount 到 cap 后返回 false；release 后可再预留。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryReserveSoldCount_capsAndReleases(TestDialect dialect) {
        initDatabase(dialect);
        Title limited = simpleTitle("<white>限量", "限量").toBuilder()
                .limitMode(LimitMode.TOTAL).totalCap(2).build();
        int id = runInTx(c -> titleRepository.insert(c, limited));

        assertTrue(reserve(id), "第 1 份预留应成功");
        assertTrue(reserve(id), "第 2 份预留应成功");
        assertFalse(reserve(id), "超出 cap 应返回 false");
        assertEquals(2, loadById(id).soldCount());

        // release 回退一份后可再预留
        runInTx(c -> {
            titleRepository.releaseSoldCount(c, id);
            return null;
        });
        assertEquals(1, loadById(id).soldCount());
        assertTrue(reserve(id), "释放后应可再预留");
        assertFalse(reserve(id));

        // 不存在的称号预留返回 false
        assertFalse(reserve(999_999));
    }

    /** 非 TOTAL 模式预留恒成功（sold_count 仍然递增计数）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryReserveSoldCount_unlimitedAlwaysSucceeds(TestDialect dialect) {
        initDatabase(dialect);
        int id = runInTx(c -> titleRepository.insert(c, simpleTitle("<white>不限量", "不限量")));
        for (int i = 0; i < 3; i++) {
            assertTrue(reserve(id));
        }
        assertEquals(3, loadById(id).soldCount());
    }

    /** replaceConditions 全量替换（空集 = 免费）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void replaceConditions_swapsAndClears(TestDialect dialect) {
        initDatabase(dialect);
        int id = runInTx(c -> titleRepository.insert(c, simpleTitle("<white>条件", "条件").toBuilder()
                .conditions(List.of(new CoinCondition(5))).build()));

        runInTx(c -> {
            titleRepository.replaceConditions(c, id,
                    List.of(new VaultCondition(new BigDecimal("1.00")), new CoinCondition(7)));
            return null;
        });
        assertEquals(Set.of(new VaultCondition(new BigDecimal("1.00")), new CoinCondition(7)),
                new HashSet<>(loadById(id).conditions()));

        runInTx(c -> {
            titleRepository.replaceConditions(c, id, List.of());
            return null;
        });
        Title free = loadById(id);
        assertTrue(free.conditions().isEmpty());
        assertTrue(free.isFree(), "条件空集即免费称号");
    }

    /** 在写线程事务中预留一份售卖名额。 */
    private boolean reserve(int titleId) {
        return runInTx(c -> titleRepository.tryReserveSoldCount(c, titleId));
    }

    /** 按 id 装载单条称号（loadAll 内存过滤）。 */
    private Title loadById(int titleId) {
        Title title = withConnection(c -> titleRepository.loadAll(c, true)).stream()
                .filter(t -> t.id() == titleId)
                .findFirst()
                .orElse(null);
        assertNotNull(title, "称号应存在: id=" + titleId);
        return title;
    }

    /** 最小合法称号。 */
    private static Title simpleTitle(String name, String plain) {
        return Title.builder()
                .name(name)
                .namePlain(plain)
                .createdAt(1_000L)
                .updatedAt(1_000L)
                .build();
    }
}
