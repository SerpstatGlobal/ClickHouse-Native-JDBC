/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.housepower.buffer;

import com.github.housepower.misc.ClickHouseCityHash;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Arrays;
import java.util.Random;

import static com.github.housepower.settings.ClickHouseDefines.CHECKSUM_LENGTH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompressedBuffedWriterTest {

    /** Collects everything the compressed writer hands to the socket layer. */
    static class CapturingWriter implements BuffedWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void writeBinary(byte byt) {
            out.write(byt);
        }

        @Override
        public void writeBinary(byte[] bytes, int offset, int length) {
            out.write(bytes, offset, length);
        }

        @Override
        public void flushToTarget(boolean force) {
        }
    }

    /**
     * The forced flush of a 7..9-byte remainder produces a 17..19-byte compressed
     * frame; the checksum of such a frame is what the sign-extension defect broke.
     * Expected values come from the C++ reference (cityhash128-vectors.txt).
     */
    @Test
    public void smallTailFrameChecksumMatchesReference() throws IOException {
        String[][] expected = {
            // n, frame hex, low64, high64
            {"7", "82110000000700000070c3a9c3a9c3a9c3", "1af4b189b2ca8dc5", "c827b4a178976ea8"},
            {"8", "82120000000800000080c3a9c3a9c3a9c3a9", "828a61abd2bd941e", "c16219d5f6230190"},
            {"9", "82130000000900000090c3a9c3a9c3a9c3a9c3", "582efcb6b166c17a", "17e0d03a51b9ad3a"},
        };
        for (String[] e : expected) {
            int n = Integer.parseInt(e[0]);
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i % 2 == 0 ? 0xC3 : 0xA9);
            }
            CapturingWriter target = new CapturingWriter();
            CompressedBuffedWriter writer = new CompressedBuffedWriter(1024 * 1024, target);
            writer.writeBinary(data, 0, n);
            writer.flushToTarget(true);

            byte[] sent = target.out.toByteArray();
            byte[] frame = Arrays.copyOfRange(sent, CHECKSUM_LENGTH, sent.length);
            assertEquals(e[1], hex(frame), "frame for n=" + n);
            assertEquals(e[2], String.format(Locale.ROOT, "%016x", readLongLE(sent, 0)), "checksum low64 for n=" + n);
            assertEquals(e[3], String.format(Locale.ROOT, "%016x", readLongLE(sent, 8)), "checksum high64 for n=" + n);
        }
    }

    /**
     * A full 1 MB buffer of incompressible data: the LZ4 output is as large as it
     * gets. With the old, overstated maxOutputLength aircompressor >= 0.27 throws
     * IllegalArgumentException here.
     */
    @Test
    public void fullIncompressibleBufferRoundTrips() throws IOException {
        int capacity = 1024 * 1024;
        byte[] data = new byte[capacity];
        new Random(52).nextBytes(data);

        CapturingWriter target = new CapturingWriter();
        CompressedBuffedWriter writer = new CompressedBuffedWriter(capacity, target);
        writer.writeBinary(data, 0, capacity);
        writer.flushToTarget(true);

        byte[] sent = target.out.toByteArray();
        int compressedSize = readIntLE(sent, CHECKSUM_LENGTH + 1);
        assertEquals(sent.length, CHECKSUM_LENGTH + compressedSize, "frame length field");
        assertEquals(capacity, readIntLE(sent, CHECKSUM_LENGTH + 5), "decompressed length field");

        long[] checksum = ClickHouseCityHash.cityHash128(sent, CHECKSUM_LENGTH, compressedSize);
        assertEquals(checksum[0], readLongLE(sent, 0));
        assertEquals(checksum[1], readLongLE(sent, 8));

        CompressedBuffedReader reader = new CompressedBuffedReader(new ByteArrayReader(sent));
        byte[] back = new byte[capacity];
        reader.readBinary(back);
        assertArrayEquals(data, back);
    }

    static class ByteArrayReader implements BuffedReader {
        private final byte[] bytes;
        private int pos;

        ByteArrayReader(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int readBinary() {
            return bytes[pos++];
        }

        @Override
        public int readBinary(byte[] target) {
            System.arraycopy(bytes, pos, target, 0, target.length);
            pos += target.length;
            return target.length;
        }
    }

    private static long readLongLE(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
