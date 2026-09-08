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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression for "Checksum doesn't match: corrupted data" on INSERT.
 * A Data packet is compressed in 1 MB frames and the remainder is force-flushed
 * as a last small frame; a remainder of 7..9 bytes gives a 17..19-byte frame,
 * whose checksum the unpatched Java CityHash128 got wrong whenever the tail
 * bytes had the high bit set. One String row of the right length reproduces it
 * deterministically: 22 bytes of block framing + L = 1 MB + 7/8/9.
 */
public class CompressedTailFrameITest extends AbstractITest {

    @Test
    public void tailFramesOf17To19BytesAreAcceptedByServer() throws Exception {
        withNewConnection(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS tail_frame_checksum");
                s.execute("CREATE TABLE tail_frame_checksum (s String) ENGINE = Memory");
            }
            int center = 1024 * 1024;
            int inserted = 0;
            for (int len = center - 20; len <= center - 10; len++) {
                for (boolean highBit : new boolean[]{true, false}) {
                    byte[] bytes = new byte[len];
                    Arrays.fill(bytes, (byte) 'a');
                    if (highBit) {
                        // last 16 bytes: 8 x U+00E9 = C3 A9, every byte >= 0x80
                        for (int i = len - 16; i < len; i += 2) {
                            bytes[i] = (byte) 0xC3;
                            bytes[i + 1] = (byte) 0xA9;
                        }
                    }
                    String value = new String(bytes, StandardCharsets.UTF_8);
                    assertEquals(len, value.getBytes(StandardCharsets.UTF_8).length);
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO tail_frame_checksum (s) VALUES (?)")) {
                        ps.setString(1, value);
                        ps.addBatch();
                        ps.executeBatch();
                    }
                    inserted++;
                }
            }
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(), sum(length(s)) FROM tail_frame_checksum")) {
                assertTrue(rs.next());
                assertEquals(inserted, rs.getInt(1));
                s.execute("DROP TABLE tail_frame_checksum");
            }
        });
    }
}
