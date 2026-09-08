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

package com.github.housepower.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic soak of the compressed write and read paths of the fork.
 *
 * <p>Part one sweeps the size of the last compressed frame of a Data packet:
 * one String row of every length in a window around 1 MB and 2 MB, with and
 * without high-bit bytes at the end, so every remainder 0..~40 bytes is sent.
 * Part two inserts random batches of mixed column types with random sizes and
 * reads everything back row by row; the generator is seeded by row id, so the
 * verification regenerates the expected values instead of keeping them.
 */
public class SyntheticInsertSweepITest extends AbstractITest {

    @Test
    public void everyTailFrameSizeIsAccepted() throws Exception {
        withNewConnection(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS tail_sweep");
                s.execute("CREATE TABLE tail_sweep (s String) ENGINE = Memory");
            }
            long expectedRows = 0;
            long expectedBytes = 0;
            for (int base : new int[]{1024 * 1024, 2 * 1024 * 1024}) {
                for (int len = base - 40; len <= base + 40; len++) {
                    for (boolean highBit : new boolean[]{true, false}) {
                        byte[] bytes = new byte[len];
                        Arrays.fill(bytes, (byte) 'a');
                        if (highBit) {
                            for (int i = len - 16; i < len; i += 2) {
                                bytes[i] = (byte) 0xC3;
                                bytes[i + 1] = (byte) 0xA9;
                            }
                        }
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO tail_sweep (s) VALUES (?)")) {
                            ps.setString(1, value);
                            ps.addBatch();
                            ps.executeBatch();
                        }
                        expectedRows++;
                        expectedBytes += len;
                    }
                }
            }
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(), sum(length(s)) FROM tail_sweep")) {
                assertTrue(rs.next());
                assertEquals(expectedRows, rs.getLong(1));
                assertEquals(expectedBytes, rs.getLong(2));
                s.execute("DROP TABLE tail_sweep");
            }
        });
    }

    private static final long SEED = 20260909L;
    private static final int BATCHES = 40;
    private static final String CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ";

    @Test
    public void randomMixedBatchesRoundTrip() throws Exception {
        withNewConnection(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS synthetic");
                s.execute("CREATE TABLE synthetic (id UInt64, s String, u8 UInt8, u32 UInt32, i64 Int64,"
                        + " f32 Float32, d Date, dt DateTime, arr Array(String))"
                        + " ENGINE = MergeTree ORDER BY id");
            }
            Random sizes = new Random(SEED);
            long nextId = 0;
            long clientBytes = 0;
            for (int b = 0; b < BATCHES; b++) {
                int rows = 1 + sizes.nextInt(6000);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO synthetic (id, s, u8, u32, i64, f32, d, dt, arr) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (int r = 0; r < rows; r++) {
                        Row row = Row.of(nextId++);
                        ps.setLong(1, row.id);
                        ps.setString(2, row.s);
                        ps.setShort(3, row.u8);
                        ps.setLong(4, row.u32);
                        ps.setLong(5, row.i64);
                        ps.setFloat(6, row.f32);
                        ps.setDate(7, Date.valueOf(row.d));
                        ps.setTimestamp(8, new Timestamp(row.dtSeconds * 1000L));
                        ps.setArray(9, connection.createArrayOf("String", row.arr));
                        ps.addBatch();
                        clientBytes += row.s.getBytes(StandardCharsets.UTF_8).length;
                    }
                    ps.executeBatch();
                }
            }

            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(), sum(length(s)), max(id) FROM synthetic")) {
                assertTrue(rs.next());
                assertEquals(nextId, rs.getLong(1), "row count");
                assertEquals(clientBytes, rs.getLong(2), "total string bytes");
                assertEquals(nextId - 1, rs.getLong(3));
            }

            // read path: every row back through the compressed reader, compared field by field
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, s, u8, u32, i64, f32, d, dt, arr FROM synthetic ORDER BY id")) {
                long expectedId = 0;
                while (rs.next()) {
                    Row row = Row.of(expectedId);
                    assertEquals(row.id, rs.getLong(1));
                    assertEquals(row.s, rs.getString(2), "s at id " + expectedId);
                    assertEquals(row.u8, rs.getShort(3));
                    assertEquals(row.u32, rs.getLong(4));
                    assertEquals(row.i64, rs.getLong(5));
                    assertEquals(Float.floatToIntBits(row.f32), Float.floatToIntBits(rs.getFloat(6)));
                    assertEquals(row.d, rs.getDate(7).toLocalDate());
                    assertEquals(row.dtSeconds, rs.getTimestamp(8).getTime() / 1000L, "dt at id " + expectedId);
                    Array arr = rs.getArray(9);
                    assertArrayEquals(row.arr, (Object[]) arr.getArray(), "arr at id " + expectedId);
                    expectedId++;
                }
                assertEquals(nextId, expectedId, "rows read back");
                assertFalse(rs.next());
                s.execute("DROP TABLE synthetic");
            }
        });
    }

    /** One synthetic row, fully determined by its id. */
    static final class Row {
        final long id;
        final String s;
        final short u8;
        final long u32;
        final long i64;
        final float f32;
        final LocalDate d;
        final long dtSeconds;
        final String[] arr;

        private Row(long id, String s, short u8, long u32, long i64, float f32, LocalDate d, long dtSeconds, String[] arr) {
            this.id = id;
            this.s = s;
            this.u8 = u8;
            this.u32 = u32;
            this.i64 = i64;
            this.f32 = f32;
            this.d = d;
            this.dtSeconds = dtSeconds;
            this.arr = arr;
        }

        static Row of(long id) {
            Random rnd = new Random(SEED * 31 + id);
            int len = rnd.nextInt(600);
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                int k = rnd.nextInt(10);
                if (k < 3) {
                    sb.append(CYRILLIC.charAt(rnd.nextInt(CYRILLIC.length())));
                } else if (k == 3) {
                    sb.append((char) ('0' + rnd.nextInt(10)));
                } else {
                    sb.append((char) ('a' + rnd.nextInt(26)));
                }
            }
            String[] arr = new String[rnd.nextInt(4)];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = Long.toHexString(rnd.nextLong());
            }
            return new Row(id,
                    sb.toString(),
                    (short) rnd.nextInt(256),
                    rnd.nextInt() & 0xFFFFFFFFL,
                    rnd.nextLong(),
                    rnd.nextFloat() * 1000f,
                    LocalDate.of(1970 + rnd.nextInt(60), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28)),
                    1_000_000_000L + rnd.nextInt(700_000_000),
                    arr);
        }
    }
}
