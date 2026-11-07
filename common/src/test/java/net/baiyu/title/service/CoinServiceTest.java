package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoinService} 审计一致性单测（不变量 §十 9：每次变动必有 coin_log，
 * balance_after 链条自洽；take 余额不足返回失败结果且不产生流水）。
 */
class CoinServiceTest extends BaseRepositoryTest {

    /** give/take/set 全链路：流水行数与 balance_after 链条一致。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void giveTakeSet_auditChainConsistent(TestDialect dialect) {
        initDatabase(dialect);
        CoinService service = newService();
        UUID player = UUID.randomUUID();
        Actor admin = Actor.of(UUID.randomUUID(), "Admin");

        CoinService.CoinResult give = service.give(player, "Player", 100, admin).join();
        assertTrue(give.success());
        assertEquals(100L, give.balance());

        CoinService.CoinResult take = service.take(player, "Player", 30, admin).join();
        assertTrue(take.success());
        assertEquals(70L, take.balance());

        CoinService.CoinResult set = service.set(player, "Player", 200, admin).join();
        assertTrue(set.success());
        assertEquals(200L, set.balance());

        assertEquals(200L, service.getBalance(player).join());
        assertEquals(3L, countRows(tables.coinLog()), "三次变动应各留一条流水");

        // balance_after 链条：从 0 起每行 prev + delta == balance_after
        List<long[]> rows = loadLogRows(player);
        long running = 0L;
        for (long[] row : rows) {
            running += row[0];
            assertEquals(running, row[1], "流水链条断裂: delta=" + row[0]);
        }
        assertEquals(200L, running, "链条终点应等于当前余额");
        // 具体链值：+100→100，-30→70，+130→200（set 的 delta = 新值 − 旧值）
        assertEquals(100L, rows.get(0)[0]);
        assertEquals(-30L, rows.get(1)[0]);
        assertEquals(130L, rows.get(2)[0]);
    }

    /** take 余额不足：返回失败结果、余额不变、不产生流水。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void take_insufficientBalance_failsWithoutLog(TestDialect dialect) {
        initDatabase(dialect);
        CoinService service = newService();
        UUID player = UUID.randomUUID();
        Actor admin = Actor.of(UUID.randomUUID(), "Admin");

        service.give(player, "Player", 50, admin).join();

        CoinService.CoinResult failed = service.take(player, "Player", 51, admin).join();
        assertFalse(failed.success(), "余额不足必须返回失败结果");
        assertEquals(50L, failed.balance(), "失败结果应携带当前余额");
        assertEquals(50L, service.getBalance(player).join(), "失败后余额不变");
        assertEquals(1L, countRows(tables.coinLog()), "失败的扣减不得产生流水");
    }

    /** 提交后写穿档案缓存：在线玩家余额直读缓存与库中一致。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void mutations_updateProfileCache(TestDialect dialect) {
        initDatabase(dialect);
        PlayerProfileCache cache = new PlayerProfileCache();
        CoinService service = new CoinService(coinRepository, txRunner, dbExecutor, cache);
        UUID player = UUID.randomUUID();
        cache.put(new PlayerProfileCache.PlayerProfile(player, "Player", null, null, null, 0));

        service.give(player, "Player", 88, Actor.console()).join();
        assertEquals(88L, cache.get(player).orElseThrow().coinBalance(), "提交后缓存余额应写穿");
        assertEquals(88L, service.getBalance(player).join(), "在线玩家余额走缓存");
    }

    /** 构造无在线缓存的服务实例（余额查询走读池）。 */
    private CoinService newService() {
        return new CoinService(coinRepository, txRunner, dbExecutor, new PlayerProfileCache());
    }

    /** 按 id 升序装载该玩家的 (delta, balance_after) 流水对。 */
    private List<long[]> loadLogRows(UUID player) {
        return withConnection(c -> {
            List<long[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT \"delta\", \"balance_after\" FROM " + tables.coinLog()
                            + " WHERE \"player_uuid\"=? ORDER BY \"id\"")) {
                ps.setBytes(1, net.baiyu.title.repository.codec.UuidCodec.toBytes(player));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
                    }
                }
            }
            return rows;
        });
    }
}
