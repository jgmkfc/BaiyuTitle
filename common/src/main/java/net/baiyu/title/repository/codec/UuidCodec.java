package net.baiyu.title.repository.codec;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/**
 * UUID 与 BINARY(16) 编解码器。
 * <p>编码为 {@link ByteBuffer} 两次 putLong（高 64 位在前），全库统一走本类，
 * 避免各处手写字节序不一致。
 */
public final class UuidCodec {

    /** BINARY(16) 字节长度。 */
    private static final int BYTES = 16;

    private UuidCodec() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * UUID 编码为 16 字节数组（非空版）。
     *
     * @param uuid UUID（不允许为空）
     * @return 16 字节数组
     */
    public static byte[] toBytes(UUID uuid) {
        Objects.requireNonNull(uuid, "UUID 不能为空");
        ByteBuffer buffer = ByteBuffer.allocate(BYTES);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /**
     * 16 字节数组解码为 UUID（非空版）。
     *
     * @param bytes 16 字节数组（不允许为空）
     * @return UUID
     * @throws IllegalArgumentException 字节长度不是 16 时
     */
    public static UUID fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "UUID 字节数组不能为空");
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException("UUID 字节数组长度必须为 16: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long mostSignificant = buffer.getLong();
        long leastSignificant = buffer.getLong();
        return new UUID(mostSignificant, leastSignificant);
    }

    /**
     * UUID 编码为 16 字节数组（null 安全版，用于可空列写入）。
     *
     * @param uuid UUID（可空）
     * @return 16 字节数组；入参为空时返回 null
     */
    public static byte[] toBytesOrNull(UUID uuid) {
        return uuid == null ? null : toBytes(uuid);
    }

    /**
     * 16 字节数组解码为 UUID（null 安全版，用于可空列读取）。
     *
     * @param bytes 16 字节数组（可空）
     * @return UUID；入参为空时返回 null
     */
    public static UUID fromBytesOrNull(byte[] bytes) {
        return bytes == null ? null : fromBytes(bytes);
    }
}
