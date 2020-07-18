package com.rchen102.ipc;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 基于 UUID 的 ClientId
 */
public class ClientId {
    /** UUID 的字节数组长度：16 */
    public static final int BYTE_LENGTH = 16;

    public static byte[] getClientId() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[BYTE_LENGTH]);
        byteBuffer.putLong(uuid.getMostSignificantBits());
        byteBuffer.putLong(uuid.getLeastSignificantBits());
        return byteBuffer.array();
    }
}
