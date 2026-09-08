# Serpstat fork of ClickHouse Native JDBC

This repository is a minimal, maintained fork of
[housepower/ClickHouse-Native-JDBC](https://github.com/housepower/ClickHouse-Native-JDBC)
at release **2.7.1** (commit `248282b`). It exists for one reason: the upstream
driver computes a wrong checksum for some compressed blocks, the server rejects
the INSERT, and the maintainer has not responded to issues since mid-2025.

Artifacts are published under the `com.serpstat` group id so they cannot be
confused with the upstream release:

```xml
<dependency>
    <groupId>com.serpstat</groupId>
    <artifactId>clickhouse-native-jdbc-shaded</artifactId>
    <version>2.7.1-serpstat.1</version>
</dependency>
```

Java package names (`com.github.housepower.*`), the JDBC URL scheme
(`jdbc:clickhouse://`) and the driver class are unchanged: the fork is a
drop-in replacement.

## Why

Production symptom (Serpstat crawler, ClickHouse 21.1, September 2026):

```
DB::Exception: Checksum doesn't match: corrupted data.
Reference: ad8c0b16bd6eefc04207d1e44223e3fd. Actual: 2a3ed20aa63b475f638293e914e94b64.
Size of compressed block: 18.
```

The driver compresses a `Data` packet in 1 MB LZ4 frames and force-flushes the
remainder as a final, small frame. Each frame is prefixed with a CityHash128
(CityHash v1.0.2, the variant ClickHouse uses) over the frame. The Java port of
CityHash128 (`ClickHouseCityHash.hashLen0to16`) handled the 1..3-byte tail
branch with signed `byte` arithmetic where the C++ reference uses `uint8`/
`uint32`. The first 16 bytes of the hashed data are consumed as the seed, so
the branch is reached exactly when the compressed frame is **17, 18 or 19 bytes
long**, i.e. when the remainder of the packet is 7..9 bytes. If any of the
three tail bytes has the high bit set, the checksum differs from the one the
server computes and the whole INSERT fails with the error above.

The probability per INSERT larger than 1 MB is about 3 / 1 048 576 times the
share of tails with a high-bit byte. That is rare enough to look like random
corruption and frequent enough to hit a busy writer several times a week.

## What changed

| File | Change |
|------|--------|
| `misc/ClickHouseCityHash.java` | `hashLen0to16()` treats the 1..3-byte tail as unsigned (`& 0xFF`) and computes `y`, `z` as unsigned 32-bit values, matching cityhash102. |
| `buffer/CompressedBuffedWriter.java` | `flushToTarget()` passes the real remaining output length (`compressedBuffer.length - offset`) to the LZ4 compressor. The previous value overstated it by 25 bytes; harmless with aircompressor 0.21 (the compressor never exceeds `maxCompressedLength`), rejected with `IllegalArgumentException` by aircompressor ≥ 0.27. This is upstream issue #469. |
| `pom.xml` | `io.airlift:aircompressor` 0.21 → **0.27** (fixes CVE-2024-36114, out-of-bounds read/write in the pure-Java decompressors). Group id `com.serpstat`, version `2.7.1-serpstat.1`. `distributionManagement` points at GitHub Packages. |
| `pom.xml` (test scope only) | `testcontainers` 1.19.0 → 1.21.4: 1.19.0 speaks Docker API 1.32, which Docker ≥ 26 refuses (`client version 1.32 is too old`), so no test that needs a container could run. |
| `jdbc/AbstractITest.java`, `jdbc/FailoverClickhouseConnectionITest.java` (tests) | Containers get explicit `withUsername`/`withPassword` (testcontainers ≥ 1.20 defaults to `test`/`test`, the mounted `users.xml` knows `default`), the image name may be any `clickhouse-server` build (`asCompatibleSubstituteFor`), and the failover test uses the current `org.testcontainers.clickhouse.ClickHouseContainer` like the base class does. |
| `.github/workflows/` | Upstream's Java 8/11 × Scala × Spark matrix replaced by one job: Java 17, the two driver modules, integration tests against `yandex/clickhouse-server:21.1.9.41`; `publish.yml` deploys to GitHub Packages on a `v*-serpstat.*` tag. |

No production code beyond the two files above was touched. The read path (`CompressedBuffedReader`) does not
validate checksums at all, so it was never affected; the defect was visible
only on INSERT.

## Proof

* `ClickHouseCityHashTest` pins the Java port against 906 vectors produced by
  the C++ reference implementation from the ClickHouse repository
  (`contrib/cityhash102`): every length 0..300 with random, high-bit-only and
  ASCII-only buffers, plus the three 17..19-byte frames of the production
  incident. On the unpatched code the test fails exactly for lengths 1, 2, 3,
  17, 18, 19 and never for ASCII input.
* `CompressedBuffedWriterTest` checks the frame bytes and the checksum the
  writer produces for 7/8/9-byte remainders against the reference values, and
  round-trips a full 1 MB incompressible buffer (the case that trips
  aircompressor 0.27 with the old argument).
* `CompressedTailFrameITest` (testcontainers) inserts one String row of every
  length in a window around 1 MB, with and without high-bit tail bytes; before
  the fix lengths `1 MB - 15/-14/-13` fail on the server with
  `Size of compressed block: 17/18/19`, after it all rows are accepted.

## Building

Java 17 with `maven.compiler.source/target` 1.8 as upstream:

```bash
mvn -pl clickhouse-native-jdbc,clickhouse-native-jdbc-shaded -am install -DskipITs
```

Integration tests need Docker (testcontainers). To run them against the
ClickHouse version we run in production:

```bash
mvn -pl clickhouse-native-jdbc -am verify -DCLICKHOUSE_IMAGE=yandex/clickhouse-server:21.1.9.41
```

The Spark integration module and the examples are kept in the tree untouched
but are not part of the release build.

Known failures when running the upstream integration suite against 21.1.9.41
that are the server's age, not the driver's: `QuerySimpleTypeITest`
(`toDate32` does not exist before 21.9) and
`PreparedStatementITest.successfullyDateIndependentWithTz`. Both pass against
upstream's default image `clickhouse/clickhouse-server:21.9`.

## Publishing

Releases are published to the GitHub Packages Maven registry of this repository
(`distributionManagement` in the root `pom.xml`, property `github.repository`).
Tagging `v2.7.1-serpstat.<n>` on the `serpstat/2.7.1` branch runs
`.github/workflows/publish.yml`. Consumers need the registry in their
`settings.xml` with a token that has `read:packages`:

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/SerpstatGlobal/ClickHouse-Native-JDBC</url>
</repository>
```

Version scheme: `<upstream version>-serpstat.<n>`. Maven orders an unknown
qualifier after the plain release, so `2.7.1-serpstat.1 > 2.7.1`.

## License

Apache License 2.0, unchanged. The original `LICENSE` is retained, `NOTICE`
carries the attribution, and every modified file states the modification in
its header (Section 4(b) of the License).

## Upstream

Reported to upstream as a pull request against `master`; if it is merged and a
release ships, this fork becomes unnecessary and the `com.github.housepower`
artifact can be used again.
