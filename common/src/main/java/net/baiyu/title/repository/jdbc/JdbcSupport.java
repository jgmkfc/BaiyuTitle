package net.baiyu.title.repository.jdbc;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Jdbc 实现共享的小工具（包内可见）。
 */
final class JdbcSupport {

    /** 完整性约束违反类 SQLState 前缀（SQL 标准 23xxx）。 */
    private static final String INTEGRITY_SQLSTATE_PREFIX = "23";

    /** SQLite 主错误码 SQLITE_CONSTRAINT（扩展码低 8 位同值，如 UNIQUE=2067、PRIMARYKEY=1555）。 */
    private static final int SQLITE_CONSTRAINT = 19;

    private JdbcSupport() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 判断是否唯一/完整性约束冲突（异常类型命中或 SQLState 23xxx——四方言通吃）。
     * <p>sqlite-jdbc 既不抛 {@link SQLIntegrityConstraintViolationException} 也不设置
     * SQLState，只能按厂商错误码识别：SQLITE_CONSTRAINT 家族的扩展码低 8 位恒为 19。
     *
     * @param e SQL 异常
     * @return 是约束冲突返回 true
     */
    static boolean isUniqueViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith(INTEGRITY_SQLSTATE_PREFIX)) {
            return true;
        }
        return e.getClass().getName().startsWith("org.sqlite")
                && (e.getErrorCode() & 0xFF) == SQLITE_CONSTRAINT;
    }
}
