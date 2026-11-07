package net.baiyu.title.repository.codec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@link ResultSet} 公共取值助手（各 Jdbc 仓储实现共享，消除重复的
 * wasNull 判空样板与列格式约定分叉）。
 * <p>列格式约定：UUID = BINARY(16)；
 * 时刻 = BIGINT epoch 毫秒；窗口日期 = CHAR(10) ISO。
 */
public final class RowMappers {

    private RowMappers() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 读取非空 UUID 列（BINARY(16)）。
     *
     * @param rs     结果集
     * @param column 列名
     * @return UUID
     * @throws SQLException 取值失败或列值为 NULL 时
     */
    public static java.util.UUID getUuid(ResultSet rs, String column) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) {
            throw new SQLException("非空 UUID 列出现 NULL: " + column);
        }
        return UuidCodec.fromBytes(bytes);
    }

    /**
     * 读取可空 UUID 列（BINARY(16)）。
     *
     * @param rs     结果集
     * @param column 列名
     * @return UUID；列值为 NULL 时返回 null
     * @throws SQLException 取值失败时
     */
    public static java.util.UUID getNullableUuid(ResultSet rs, String column) throws SQLException {
        return UuidCodec.fromBytesOrNull(rs.getBytes(column));
    }

    /**
     * 读取可空 BIGINT 列。
     *
     * @param rs     结果集
     * @param column 列名
     * @return 包装 Long；列值为 NULL 时返回 null
     * @throws SQLException 取值失败时
     */
    public static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 读取可空 INT 列。
     *
     * @param rs     结果集
     * @param column 列名
     * @return 包装 Integer；列值为 NULL 时返回 null
     * @throws SQLException 取值失败时
     */
    public static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 读取可空日期列（CHAR(10) ISO 格式，如 {@code 2026-07-26}）。
     *
     * @param rs     结果集
     * @param column 列名
     * @return LocalDate；列值为 NULL 或空白时返回 null
     * @throws SQLException 取值失败或日期格式非法时
     */
    public static LocalDate getLocalDate(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new SQLException("日期列格式非法（应为 ISO CHAR(10)）: " + column + "=" + raw, e);
        }
    }

    /**
     * 读取非空时刻列（BIGINT epoch 毫秒）为 {@link Instant}。
     *
     * @param rs     结果集
     * @param column 列名
     * @return Instant
     * @throws SQLException 取值失败时
     */
    public static Instant getInstantMillis(ResultSet rs, String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    /**
     * 读取可空时刻列（BIGINT epoch 毫秒）为 {@link Instant}。
     *
     * @param rs     结果集
     * @param column 列名
     * @return Instant；列值为 NULL 时返回 null
     * @throws SQLException 取值失败时
     */
    public static Instant getNullableInstantMillis(ResultSet rs, String column) throws SQLException {
        Long millis = getNullableLong(rs, column);
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }
}
