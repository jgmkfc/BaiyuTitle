package net.baiyu.title.database;

import net.baiyu.title.testutil.BaseRepositoryTest;
import net.baiyu.title.testutil.TestDialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移冒烟测试：H2 + SQLite 双方言经 DatabaseHandler 真跑 Flyway V1~V5，
 * 关键表可 SELECT、schema_history 五条版本全部成功。
 */
class MigrationSmokeTest extends BaseRepositoryTest {

    /** 双方言迁移成功且关键表存在（对空表试 SELECT COUNT）。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void migrate_createsKeyTables(TestDialect dialect) {
        initDatabase(dialect);
        String[] keyTables = {
                tables.title(),
                tables.titleCondition(),
                tables.titleBondRequirement(),
                tables.titleOwnership(),
                tables.playerSlot(),
                tables.playerSlotPool(),
                tables.playerCoin(),
                tables.coinLog(),
                tables.purchaseOrder(),
                tables.trade(),
                tables.tradeItem(),
                tables.playerGroup(),
                tables.playerGroupMember(),
                tables.redeemCode(),
                tables.redeemCodeUse(),
                tables.redeemCard(),
                tables.auditLog(),
        };
        for (String table : keyTables) {
            assertEquals(0L, countRows(table), "新库关键表应存在且为空: " + table);
        }
    }

    /** schema_history 记录 V1~V5 五条已应用迁移且全部成功。 */
    @ParameterizedTest(name = "[{0}]")
    @EnumSource(TestDialect.class)
    void migrate_recordsFiveSuccessfulVersions(TestDialect dialect) {
        initDatabase(dialect);
        long applied = scalarLong(
                "SELECT COUNT(*) FROM \"" + TABLE_PREFIX + "schema_history\""
                        + " WHERE \"version\" IS NOT NULL AND \"success\" = "
                        + (dialect == TestDialect.SQLITE ? "1" : "TRUE"));
        assertTrue(applied >= 5, "应至少应用 V1~V5 五条版本化迁移，实际: " + applied);
    }
}
