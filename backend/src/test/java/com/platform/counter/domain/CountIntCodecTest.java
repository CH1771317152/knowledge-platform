package com.platform.counter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountIntCodecTest {
    @Test
    void roundTripVariousValues() {
        long[] values = {0L, 1L, 255L, 1234567890123L, Long.MAX_VALUE};
        for (long v : values) {
            assertThat(CountIntCodec.decodeLong(CountIntCodec.encodeLong(v), 0)).isEqualTo(v);
        }
    }
    @Test
    void decodeTreatsShortBufferAsZeroPadded() {
        // bytes [0x01, 0x02] decoded at offset 0 → little-endian 0x0201 = 513 (rest zero-padded)
        assertThat(CountIntCodec.decodeLong(new byte[]{0x01, 0x02}, 0)).isEqualTo(0x0201L);
    }
    @Test
    void decodeAtNonZeroOffset() {
        byte[] blob = new byte[16];
        System.arraycopy(CountIntCodec.encodeLong(42L), 0, blob, 8, 8); // value at offset 8
        assertThat(CountIntCodec.decodeLong(blob, 8)).isEqualTo(42L);
        assertThat(CountIntCodec.decodeLong(blob, 0)).isZero(); // offset 0 still zero
    }
}
