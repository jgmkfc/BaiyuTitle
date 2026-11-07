package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcRedeemRepository} 双方言单测（表 V5：redeem_code / redeem_code_use / redeem_card）。
 */
class RedeemRepositoryTest extends BaseRepositoryTest {

    /** insertCode → findCodeByCode 全字段往返（可空列非空/全空两态）；不存在返回空。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insertCode_thenFindByCode_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        long id = runInTx(c -> redeemRepository.insertCode(c,
                new RedeemCode(0, "GIFT2026", 7, 100, 3, 1, 1_000L, 9_000L, true, "Admin", 500L)));
        assertTrue(id > 0, "主键应由数据库生成");
        assertEquals(new RedeemCode(id, "GIFT2026", 7, 100, 3, 1, 1_000L, 9_000L, true, "Admin", 500L),
                withConnection(c -> redeemRepository.findCodeByCode(c, "GIFT2026")).orElseThrow());

        // 可空列全空（永久有效、控制台创建）
        long bareId = runInTx(c -> redeemRepository.insertCode(c,
                new RedeemCode(0, "FOREVER", 8, 0, 0, 0, null, null, false, null, 600L)));
        assertEquals(new RedeemCode(bareId, "FOREVER", 8, 0, 0, 0, null, null, false, null, 600L),
                withConnection(c -> redeemRepository.findCodeByCode(c, "FOREVER")).orElseThrow());
        assertTrue(withConnection(c -> redeemRepository.findCodeByCode(c, "MISSING")).isEmpty());
    }

    /** tryConsumeUse：限量码逐次递增至用尽后拒绝；禁用码直接拒绝。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryConsumeUse_limitedCodeExhausts(TestDialect dialect) {
        initDatabase(dialect);
        long limited = insertCode("LIMIT2", 2, true);
        long disabled = insertCode("OFF", 5, false);

        boolean firstUse = runInTx(c -> redeemRepository.tryConsumeUse(c, limited));
        boolean secondUse = runInTx(c -> redeemRepository.tryConsumeUse(c, limited));
        boolean thirdUse = runInTx(c -> redeemRepository.tryConsumeUse(c, limited));
        assertTrue(firstUse);
        assertTrue(secondUse);
        assertFalse(thirdUse, "已用尽应拒绝");
        assertEquals(2, withConnection(c -> redeemRepository.findCodeByCode(c, "LIMIT2"))
                .orElseThrow().usedCount());

        boolean disabledUse = runInTx(c -> redeemRepository.tryConsumeUse(c, disabled));
        assertFalse(disabledUse, "禁用码应拒绝");
    }

    /** tryConsumeUse：max_uses=0 为无限，连续消耗恒成功。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryConsumeUse_unlimitedNeverExhausts(TestDialect dialect) {
        initDatabase(dialect);
        long unlimited = insertCode("FREE", 0, true);
        for (int i = 0; i < 3; i++) {
            boolean consumed = runInTx(c -> redeemRepository.tryConsumeUse(c, unlimited));
            assertTrue(consumed);
        }
        assertEquals(3, withConnection(c -> redeemRepository.findCodeByCode(c, "FREE"))
                .orElseThrow().usedCount());
    }

    /** recordUse + countUsesByPlayer：按 (code, player) 精确计数。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void countUsesByPlayer_scopedToCodeAndPlayer(TestDialect dialect) {
        initDatabase(dialect);
        long code = insertCode("GIFT", 0, true);
        long other = insertCode("OTHER", 0, true);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        runInTx(c -> {
            redeemRepository.recordUse(c, code, alice, 11L, 1_000L);
            redeemRepository.recordUse(c, code, alice, null, 2_000L); // ownership_id 可空
            redeemRepository.recordUse(c, code, bob, 22L, 3_000L);
            redeemRepository.recordUse(c, other, alice, 33L, 4_000L);
            return null;
        });

        assertEquals(2, (int) withConnection(c -> redeemRepository.countUsesByPlayer(c, code, alice)));
        assertEquals(1, (int) withConnection(c -> redeemRepository.countUsesByPlayer(c, code, bob)));
        assertEquals(0, (int) withConnection(c -> redeemRepository.countUsesByPlayer(c, code, UUID.randomUUID())));
    }

    /** insertCard → findCard 全字段往返（未核销全空/已核销全满两态）；不存在返回空。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void insertCard_thenFindCard_roundTrips(TestDialect dialect) {
        initDatabase(dialect);
        RedeemCard fresh = new RedeemCard(UUID.randomUUID(), 7, null, null, 1_000L, null, null, null);
        UUID redeemer = UUID.randomUUID();
        RedeemCard redeemed = new RedeemCard(UUID.randomUUID(), 8, 30, "Admin", 2_000L,
                redeemer, 5_000L, 99L);
        runInTx(c -> {
            redeemRepository.insertCard(c, fresh);
            redeemRepository.insertCard(c, redeemed);
            return null;
        });

        assertEquals(fresh, withConnection(c -> redeemRepository.findCard(c, fresh.cardUuid())).orElseThrow());
        assertEquals(redeemed, withConnection(c -> redeemRepository.findCard(c, redeemed.cardUuid())).orElseThrow());
        assertTrue(withConnection(c -> redeemRepository.findCard(c, UUID.randomUUID())).isEmpty());
    }

    /** tryRedeemCard：单次核销成功落核销者与时刻；二次核销拒绝；bindCardOwnership 回填。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void tryRedeemCard_singleRedemptionOnly(TestDialect dialect) {
        initDatabase(dialect);
        UUID cardUuid = UUID.randomUUID();
        UUID redeemer = UUID.randomUUID();
        runInTx(c -> {
            redeemRepository.insertCard(c, new RedeemCard(cardUuid, 7, null, "Admin", 1_000L, null, null, null));
            return null;
        });

        boolean redeemedOk = runInTx(c -> redeemRepository.tryRedeemCard(c, cardUuid, redeemer, 5_000L));
        assertTrue(redeemedOk);
        runInTx(c -> {
            redeemRepository.bindCardOwnership(c, cardUuid, 77L);
            return null;
        });
        assertEquals(new RedeemCard(cardUuid, 7, null, "Admin", 1_000L, redeemer, 5_000L, 77L),
                withConnection(c -> redeemRepository.findCard(c, cardUuid)).orElseThrow());

        // 已核销的卡再次核销：0 行命中返回 false，字段不被覆盖
        boolean redeemedTwice = runInTx(c -> redeemRepository.tryRedeemCard(c, cardUuid, UUID.randomUUID(), 9_000L));
        assertFalse(redeemedTwice, "单次核销防刷应拒绝二次核销");
        assertEquals(redeemer, withConnection(c -> redeemRepository.findCard(c, cardUuid))
                .orElseThrow().redeemedBy());
        // 不存在的卡核销返回 false
        boolean missing = runInTx(c -> redeemRepository.tryRedeemCard(c, UUID.randomUUID(), redeemer, 9_000L));
        assertFalse(missing);
    }

    /** 插入一个指定限量与启用态的兑换码并返回主键。 */
    private long insertCode(String code, int maxUses, boolean enabled) {
        return runInTx(c -> redeemRepository.insertCode(c,
                new RedeemCode(0, code, 7, maxUses, 0, 0, null, null, enabled, null, 1_000L)));
    }
}
