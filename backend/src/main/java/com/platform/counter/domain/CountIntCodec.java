package com.platform.counter.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CountIntCodec {

    private CountIntCodec() {}

    public static byte[] encodeLong(long value) {
        byte[] b = new byte[8];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
        return b;
    }

    public static long decodeLong(byte[] bytes, int offset) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            int idx = offset + i;
            b[i] = idx < bytes.length ? bytes[idx] : 0;
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
