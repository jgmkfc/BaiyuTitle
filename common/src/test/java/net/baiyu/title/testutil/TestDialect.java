package net.baiyu.title.testutil;

import net.baiyu.title.database.handler.DatabaseType;

/**
 * 测试双方言枚举+ SQLite
 * 临时文件双方言参数化跑真迁移；MySQL/MariaDB 走 Testcontainers 冒烟，后续里程碑接入）。
 */
public enum TestDialect {

    /** H2（生产 HikariConfigFactory 统一构造 file URL，测试落在 @TempDir，仍以 MODE=MySQL 运行）。 */
    H2(DatabaseType.H2),

    /** SQLite（@TempDir 临时文件）。 */
    SQLITE(DatabaseType.SQLITE);

    /** 对应的数据库类型。 */
    private final DatabaseType databaseType;

    TestDialect(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    /**
     * 对应的数据库类型。
     *
     * @return 数据库类型
     */
    public DatabaseType databaseType() {
        return databaseType;
    }
}
