package net.baiyu.title.repository.codec;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UuidCodec} 编解码单测：随机往返一致性、字节序约定与 null 安全。
 */
class UuidCodecTest {

    /** 随机 100 个 UUID 编解码往返一致。 */
    @Test
    void roundTrip_100_random_uuids() {
        for (int i = 0; i < 100; i++) {
            UUID original = UUID.randomUUID();
            byte[] bytes = UuidCodec.toBytes(original);
            assertEquals(16, bytes.length, "BINARY(16) 编码长度必须为 16");
            assertEquals(original, UuidCodec.fromBytes(bytes), "第 " + i + " 个 UUID 往返不一致");
        }
    }

    /** 字节序约定：高 64 位在前（ByteBuffer 两次 putLong）。 */
    @Test
    void byteOrder_mostSignificantBitsFirst() {
        UUID uuid = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        byte[] expected = {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
        };
        assertArrayEquals(expected, UuidCodec.toBytes(uuid));
    }

    /** null 安全版：null 入参原样返回 null。 */
    @Test
    void nullSafeVariants_passThroughNull() {
        assertNull(UuidCodec.toBytesOrNull(null));
        assertNull(UuidCodec.fromBytesOrNull(null));
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, UuidCodec.fromBytesOrNull(UuidCodec.toBytesOrNull(uuid)));
    }

    /** 非空版：null 入参与非法长度快速失败。 */
    @Test
    void strictVariants_rejectNullAndBadLength() {
        assertThrows(NullPointerException.class, () -> UuidCodec.toBytes(null));
        assertThrows(NullPointerException.class, () -> UuidCodec.fromBytes(null));
        assertThrows(IllegalArgumentException.class, () -> UuidCodec.fromBytes(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> UuidCodec.fromBytes(new byte[17]));
    }
}
