package net.baiyu.title.testutil;

import net.baiyu.title.database.config.DatabaseConfig;
import net.baiyu.title.database.handler.DatabaseHandler;
import net.baiyu.title.repository.SqlFunction;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.jdbc.JdbcAuditRepository;
import net.baiyu.title.repository.jdbc.JdbcCoinRepository;
import net.baiyu.title.repository.jdbc.JdbcGroupRepository;
import net.baiyu.title.repository.jdbc.JdbcOrderRepository;
import net.baiyu.title.repository.jdbc.JdbcOwnershipRepository;
import net.baiyu.title.repository.jdbc.JdbcRedeemRepository;
import net.baiyu.title.repository.jdbc.JdbcSlotRepository;
import net.baiyu.title.repository.jdbc.JdbcTitleRepository;
import net.baiyu.title.repository.jdbc.JdbcTradeRepository;
import net.baiyu.title.service.executor.DbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * 仓储/服务层测试基类：H2 + SQLite 双方言参数化，各自经 {@link DatabaseHandler}
 * 真跑 Flyway V1~V5 迁移（表前缀 {@code byt_}，与生产建议一致）。
 * <p>用法：测试方法标注 {@code @ParameterizedTest + @EnumSource(TestDialect.class)}，
 * 方法体第一行调用 {@link #initDatabase(TestDialect)}；只跑单方言的测试
 * （如 TitleCatalogService）直接传入 {@link TestDialect#H2}。
 * <p>事务纪律：{@link DbExecutor#assertWriterThread()} 在测试中保持有效——
 * {@link #runInTx(SqlFunction)} 把事务体包装为 submitWrite + inTransaction 并
 * 同步等待，保证所有事务真的跑在写线程上（不变量 §十 11）。
 */
public abstract class BaseRepositoryTest {

    /** 测试统一表前缀（与迁移占位符 ${tablePrefix} 同值）。 */
    protected static final String TABLE_PREFIX = "byt_";

    /** 每个测试方法独享的临时目录（H2 / SQLite 数据文件都落在这里）。 */
    @TempDir
    protected Path tempDir;

    /** 数据库服务（HikariCP 池 + Flyway 迁移）。 */
    protected DatabaseHandler databaseHandler;

    /** 数据库执行器（写单线程 + 读小池）。 */
    protected DbExecutor dbExecutor;

    /** 事务执行器。 */
    protected TransactionRunner txRunner;

    /** 表名拼接器。 */
    protected TableNames tables;

    /** 称号仓储。 */
    protected JdbcTitleRepository titleRepository;

    /** 所有权仓储。 */
    protected JdbcOwnershipRepository ownershipRepository;

    /** 装扮栏位仓储。 */
    protected JdbcSlotRepository slotRepository;

    /** 称号币仓储。 */
    protected JdbcCoinRepository coinRepository;

    /** 审计日志仓储。 */
    protected JdbcAuditRepository auditRepository;

    /** 交易仓储。 */
    protected JdbcTradeRepository tradeRepository;

    /** 购买订单仓储。 */
    protected JdbcOrderRepository orderRepository;

    /** 组织仓储。 */
    protected JdbcGroupRepository groupRepository;

    /** 兑换码/称号卡仓储。 */
    protected JdbcRedeemRepository redeemRepository;

    /**
     * 按方言初始化数据库：建池 → 真跑 Flyway V1~V5 迁移 → 装配执行器与全部仓储。
     *
     * @param dialect 目标方言
     */
    protected void initDatabase(TestDialect dialect) {
        Logger logger = LoggerFactory.getLogger("BaiyuTitleTest-" + dialect.name());
        DatabaseConfig config = DatabaseConfig.builder()
                .withDatabaseType(dialect.databaseType())
                .withTablePrefix(TABLE_PREFIX)
                .withPath(tempDir.resolve(dialect.name().toLowerCase(Locale.ROOT)))
                .withMaxPoolSize(4)
                .withMinIdle(1)
                .build();
        databaseHandler = DatabaseHandler.builder()
                .withConfig(config)
                .withLogger(logger)
                .withMigrate(true)
                .build();
        databaseHandler.doStartup();

        dbExecutor = new DbExecutor(2);
        txRunner = new TransactionRunner(databaseHandler::getConnection);
        tables = new TableNames(TABLE_PREFIX);
        titleRepository = new JdbcTitleRepository(tables);
        ownershipRepository = new JdbcOwnershipRepository(tables);
        slotRepository = new JdbcSlotRepository(tables);
        coinRepository = new JdbcCoinRepository(tables);
        auditRepository = new JdbcAuditRepository(tables);
        tradeRepository = new JdbcTradeRepository(tables);
        orderRepository = new JdbcOrderRepository(tables);
        groupRepository = new JdbcGroupRepository(tables);
        redeemRepository = new JdbcRedeemRepository(tables);
    }

    /**
     * 关执行器、关连接池（先停写线程保证在途事务收尾，再释放数据库文件锁）。
     */
    @AfterEach
    protected void tearDownDatabase() {
        if (dbExecutor != null) {
            dbExecutor.shutdown(Duration.ofSeconds(10));
            dbExecutor = null;
        }
        if (databaseHandler != null) {
            databaseHandler.doShutdown();
            databaseHandler = null;
        }
    }

    /**
     * 在写线程上执行一个事务并同步等待结果（保持 assertWriterThread 断言有效）。
     * <p>事务体抛出的运行时异常（如 DuplicateActiveException / IllegalArgumentException）
     * 原样解包上抛，便于 assertThrows 精确断言。
     *
     * @param work 事务体
     * @param <T>  返回类型
     * @return 事务体返回值
     */
    protected <T> T runInTx(SqlFunction<Connection, T> work) {
        try {
            return dbExecutor.submitWrite(() -> txRunner.inTransaction(work)).join();
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    /**
     * 在读线程上借连接执行只读操作并同步等待（保持 assertDbThread 断言有效，
     * 与生产 submitRead + withConnection 路径一致）。
     *
     * @param work 只读操作体
     * @param <T>  返回类型
     * @return 操作体返回值
     */
    protected <T> T withConnection(SqlFunction<Connection, T> work) {
        try {
            return dbExecutor.submitRead(() -> txRunner.withConnection(work)).join();
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    /**
     * 统计整表行数（表名请从 {@link #tables} 取，自带前缀与引号）。
     *
     * @param quotedTable 带引号的完整表名
     * @return 行数
     */
    protected long countRows(String quotedTable) {
        return scalarLong("SELECT COUNT(*) FROM " + quotedTable);
    }

    /**
     * 执行返回单个 BIGINT 标量的查询。
     *
     * @param sql 查询语句
     * @return 标量值
     */
    protected long scalarLong(String sql) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("标量查询无结果: " + sql);
                }
                return rs.getLong(1);
            }
        });
    }

    /**
     * 从 CompletionException 中解包出原始异常（运行时异常/错误原样上抛）。
     *
     * @param e 组合异常
     * @return 永不正常返回（占位返回类型便于 throw 调用）
     */
    protected static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return e;
    }
}
