package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcSlotRepository} 双方言单测（表 V2：player_slot / player_slot_pool）。
 */
class SlotRepositoryTest extends BaseRepositoryTest {

    /** upsert 先插后改：load 反映最新值且不产生重复行。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void upsert_insertThenUpdate(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        runInTx(c -> {
            slotRepository.upsert(c, player, new SlotConfig(0, true, SlotMode.FIXED, 7));
            slotRepository.upsert(c, player, new SlotConfig(1, true, SlotMode.RANDOM, null));
            return null;
        });
        assertEquals(List.of(
                        new SlotConfig(0, true, SlotMode.FIXED, 7),
                        new SlotConfig(1, true, SlotMode.RANDOM, null)),
                withConnection(c -> slotRepository.load(c, player)));

        // 同键再次 upsert 走 UPDATE 分支
        runInTx(c -> {
            slotRepository.upsert(c, player, new SlotConfig(0, false, SlotMode.RANDOM, null));
            return null;
        });
        List<SlotConfig> reloaded = withConnection(c -> slotRepository.load(c, player));
        assertEquals(2, reloaded.size(), "upsert 不应产生重复行");
        assertEquals(new SlotConfig(0, false, SlotMode.RANDOM, null), reloaded.getFirst());

        // 玩家隔离
        assertTrue(withConnection(c -> slotRepository.load(c, UUID.randomUUID())).isEmpty());
    }

    /** clearTitleEverywhere：固定栏位置 NULL + 随机池删行，其余引用不动。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void clearTitleEverywhere_scrubsSlotAndPool(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        runInTx(c -> {
            slotRepository.upsert(c, player, new SlotConfig(0, true, SlotMode.FIXED, 7));
            slotRepository.upsert(c, player, new SlotConfig(1, true, SlotMode.FIXED, 8));
            slotRepository.replacePool(c, player, 2, Set.of(7, 9));
            // 旁观玩家持有同款称号引用，不应被波及
            slotRepository.upsert(c, bystander, new SlotConfig(0, true, SlotMode.FIXED, 7));
            slotRepository.replacePool(c, bystander, 0, Set.of(7));
            return null;
        });

        runInTx(c -> {
            slotRepository.clearTitleEverywhere(c, player, 7);
            return null;
        });

        List<SlotConfig> slots = withConnection(c -> slotRepository.load(c, player));
        assertNull(slots.get(0).titleId(), "栏位 0 的称号 7 引用应被置空");
        assertEquals(8, slots.get(1).titleId(), "其他称号引用不受影响");
        assertEquals(Set.of(9), withConnection(c -> slotRepository.loadPool(c, player, 2)));

        assertEquals(7, withConnection(c -> slotRepository.load(c, bystander)).getFirst().titleId());
        assertEquals(Set.of(7), withConnection(c -> slotRepository.loadPool(c, bystander, 0)));
    }

    /** replacePool 全量替换与清空。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void replacePool_swapsAndClears(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        runInTx(c -> {
            slotRepository.replacePool(c, player, 0, Set.of(1, 2, 3));
            return null;
        });
        assertEquals(Set.of(1, 2, 3), withConnection(c -> slotRepository.loadPool(c, player, 0)));

        runInTx(c -> {
            slotRepository.replacePool(c, player, 0, Set.of(2, 4));
            return null;
        });
        assertEquals(Set.of(2, 4), withConnection(c -> slotRepository.loadPool(c, player, 0)));

        runInTx(c -> {
            slotRepository.replacePool(c, player, 0, Set.of());
            return null;
        });
        assertTrue(withConnection(c -> slotRepository.loadPool(c, player, 0)).isEmpty());

        // 不同栏位互不影响
        runInTx(c -> {
            slotRepository.replacePool(c, player, 1, Set.of(5));
            slotRepository.replacePool(c, player, 0, Set.of(6));
            return null;
        });
        assertEquals(Set.of(5), withConnection(c -> slotRepository.loadPool(c, player, 1)));
        assertEquals(Set.of(6), withConnection(c -> slotRepository.loadPool(c, player, 0)));
    }
}
