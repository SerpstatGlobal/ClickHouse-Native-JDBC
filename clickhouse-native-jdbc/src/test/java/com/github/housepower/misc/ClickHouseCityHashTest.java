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

package com.github.housepower.misc;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Java port against CityHash_v1_0_2 as shipped in ClickHouse
 * (contrib/cityhash102), which the server uses to validate every compressed block.
 * The vectors were produced by the C++ reference: lengths 0..300, three buffers
 * each (random, high-bit only, ASCII only), plus the three 17..19-byte LZ4 frames
 * that trigger the sign-extension defect fixed in the Serpstat fork.
 */
public class ClickHouseCityHashTest {

    @Test
    public void matchesClickHouseReferenceVectors() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/cityhash128-vectors.txt"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        assertTrue(lines.size() > 900, "vector file looks truncated: " + lines.size());

        int checked = 0;
        for (String line : lines) {
            String[] parts = line.split(" ");
            byte[] input = "-".equals(parts[0]) ? new byte[0] : hexToBytes(parts[0]);
            long[] actual = ClickHouseCityHash.cityHash128(input, 0, input.length);
            assertEquals(parts[1], String.format(Locale.ROOT, "%016x", actual[0]), "low64 for input " + parts[0]);
            assertEquals(parts[2], String.format(Locale.ROOT, "%016x", actual[1]), "high64 for input " + parts[0]);
            checked++;
        }
        assertEquals(lines.size(), checked);
    }

    @Test
    public void tailBytesWithHighBitAreUnsigned() {
        // 18-byte frame: 16 seed bytes + 2 tail bytes with the high bit set
        // (the shape of the production incident, see SERPSTAT-FORK.md)
        byte[] frame = hexToBytes("82120000000800000080c3a9c3a9c3a9c3a9");
        long[] h = ClickHouseCityHash.cityHash128(frame, 0, frame.length);
        assertEquals("828a61abd2bd941e", String.format(Locale.ROOT, "%016x", h[0]));
        assertEquals("c16219d5f6230190", String.format(Locale.ROOT, "%016x", h[1]));
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
