package net.baiyu.title.repository.codec;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@link PreparedStatement} 公共绑参助手（{@link RowMappers} 的写入侧对偶，
 * 各 Jdbc 仓储实现共享 null 安全的 setNull 样板）。
 * <p>列格式约定与 {@link RowMappers} 一致：UUID = BINARY(16)；
 * 时刻 = BIGINT epoch 毫秒；窗口日期 = CHAR(10) ISO。
 */
public final class StatementBinders {

    private StatementBinders() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 绑定非空 UUID（BINARY(16)）。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param uuid  UUID（不允许为空）
     * @throws SQLException 绑定失败时
     */
    public static void setUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        ps.setBytes(index, UuidCodec.toBytes(uuid));
    }

    /**
     * 绑定可空 UUID（BINARY(16)）。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param uuid  UUID（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, UuidCodec.toBytes(uuid));
        }
    }

    /**
     * 绑定可空 BIGINT。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param value 包装 Long（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * 绑定可空 INT。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param value 包装 Integer（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * 绑定可空字符串。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param value 字符串（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /**
     * 绑定可空 DECIMAL(18,2)。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param value 金额（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableBigDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    /**
     * 绑定可空日期（CHAR(10) ISO 字符串）。
     *
     * @param ps    预编译语句
     * @param index 参数序号
     * @param date  日期（可空）
     * @throws SQLException 绑定失败时
     */
    public static void setNullableLocalDate(PreparedStatement ps, int index, LocalDate date) throws SQLException {
        if (date == null) {
            ps.setNull(index, Types.CHAR);
        } else {
            ps.setString(index, date.toString());
        }
    }

    /**
     * 绑定"TRUE 或 NULL"布尔标记列（active_marker 模式：唯一约束中
     * NULL 不参与比较，仅 TRUE 占位，设计文档 §2.3）。
     *
     * @param ps     预编译语句
     * @param index  参数序号
     * @param marker true 绑 TRUE；false 绑 NULL
     * @throws SQLException 绑定失败时
     */
    public static void setTrueOrNull(PreparedStatement ps, int index, boolean marker) throws SQLException {
        if (marker) {
            ps.setBoolean(index, true);
        } else {
            ps.setNull(index, Types.BOOLEAN);
        }
    }
}
