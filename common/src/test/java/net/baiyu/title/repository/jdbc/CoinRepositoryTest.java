package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.coin.CoinChangeReason;
import net.baiyu.title.domain.coin.CoinLogEntry;
import net.baiyu.title.repository.codec.UuidCodec;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcCoinRepository} 双方言单测（表 V3：player_coin / coin_log）。
 */
class CoinRepositoryTest extends BaseRepositoryTest {

    /** tryAdd 正负增减：入账/扣款均成功且余额准确。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryAdd_positiveAndNegative(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        assertEquals(0L, balance(player), "无记录时余额为 0");
        assertTrue(tryAdd(player, 100));
        assertEquals(100L, balance(player));

        assertTrue(tryAdd(player, -40));
        assertEquals(60L, balance(player));

        // 扣到 0 合法（balance >= -delta 含等于）
        assertTrue(tryAdd(player, -60));
        assertEquals(0L, balance(player));

        // delta = 0 恒成功
        assertTrue(tryAdd(player, 0));
    }

    /** 透支扣款返回 false 且余额不变（条件 UPDATE 禁透支，不变量 §十 9）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryAdd_overdraftRejected(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        assertTrue(tryAdd(player, 50));

        assertFalse(tryAdd(player, -51), "余额不足必须拒绝");
        assertEquals(50L, balance(player), "拒绝后余额不变");

        // 全新玩家直接扣款：种子行余额 0，扣款失败
        UUID fresh = UUID.randomUUID();
        assertFalse(tryAdd(fresh, -1));
        assertEquals(0L, balance(fresh));
    }

    /** set 直接设置余额并刷新玩家名（插入与更新两条路径）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void set_insertsAndUpdates(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        runInTx(c -> {
            coinRepository.set(c, player, "BaiYu", 200);
            return null;
        });
        assertEquals(200L, balance(player));
        assertEquals("BaiYu", playerName(player));

        runInTx(c -> {
            coinRepository.set(c, player, "BaiYuRenamed", 5);
            return null;
        });
        assertEquals(5L, balance(player));
        assertEquals("BaiYuRenamed", playerName(player));
        assertEquals(1L, countRows(tables.playerCoin()), "set 两次仍只有一行");
    }

    /** log 落表：coin_log 行数与全列内容一致。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void log_persistsAllColumns(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();
        CoinLogEntry entry = new CoinLogEntry(0, player, -30, 70,
                CoinChangeReason.PURCHASE, "SYSTEM", 42L, 8_888L);

        runInTx(c -> {
            coinRepository.log(c, entry);
            return null;
        });

        assertEquals(1L, countRows(tables.coinLog()));
        withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + tables.coinLog() + " WHERE \"player_uuid\"=?")) {
                ps.setBytes(1, UuidCodec.toBytes(player));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "流水行应存在");
                    assertEquals(-30L, rs.getLong("delta"));
                    assertEquals(70L, rs.getLong("balance_after"));
                    assertEquals("PURCHASE", rs.getString("reason"));
                    assertEquals("SYSTEM", rs.getString("actor"));
                    assertEquals(42L, rs.getLong("ref_id"));
                    assertEquals(8_888L, rs.getLong("created_at"));
                }
            }
            return null;
        });
    }

    /** ensureRow：补种子行并刷新玩家名，不动余额。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void ensureRow_seedsAndRefreshesName(TestDialect dialect) {
        initDatabase(dialect);
        UUID player = UUID.randomUUID();

        runInTx(c -> {
            coinRepository.ensureRow(c, player, "FirstName", 1_000L);
            return null;
        });
        assertEquals(0L, balance(player));
        assertEquals("FirstName", playerName(player));

        assertTrue(tryAdd(player, 30));
        runInTx(c -> {
            coinRepository.ensureRow(c, player, "SecondName", 2_000L);
            return null;
        });
        assertEquals(30L, balance(player), "ensureRow 不应改动余额");
        assertEquals("SecondName", playerName(player));
    }

    /** 读连接查询当前余额。 */
    private long balance(UUID player) {
        return withConnection(c -> coinRepository.getBalance(c, player));
    }

    /** 写线程事务中执行一次余额增减。 */
    private boolean tryAdd(UUID player, long delta) {
        return runInTx(c -> coinRepository.tryAdd(c, player, delta));
    }

    /** 查询余额行的玩家名缓存列。 */
    private String playerName(UUID player) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT \"player_name\" FROM " + tables.playerCoin() + " WHERE \"player_uuid\"=?")) {
                ps.setBytes(1, UuidCodec.toBytes(player));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }
}
