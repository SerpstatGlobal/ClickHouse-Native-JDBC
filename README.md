ClickHouse Native JDBC — Serpstat fork
===

[![build](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/build.yml/badge.svg?branch=serpstat%2F2.7.1)](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/build.yml)
[![publish](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/publish.yml/badge.svg)](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/publish.yml)
[![License](https://img.shields.io/github/license/SerpstatGlobal/ClickHouse-Native-JDBC)](LICENSE)

English | [Українська](README_uk.md)

A maintained fork of [housepower/ClickHouse-Native-JDBC](https://github.com/housepower/ClickHouse-Native-JDBC)
2.7.1 — a JDBC driver for [ClickHouse](https://clickhouse.com/) that speaks the native TCP
protocol and compresses data by columns.

## Why this fork exists

Upstream 2.7.1 computes a wrong checksum for some compressed blocks. ClickHouse then rejects
the whole INSERT:

```
DB::Exception: Checksum doesn't match: corrupted data. ... Size of compressed block: 18.
```

The Java port of CityHash128 in the driver sign-extends bytes in its 1..3-byte tail branch,
which is reached exactly when a compressed frame is 17..19 bytes long — the forced flush of a
7..9-byte remainder of a `Data` packet. It happens on roughly 3 of every 1 048 576 INSERTs
larger than 1 MB, looks like random corruption, and has been unanswered upstream since 2025.
This fork fixes it, upgrades `aircompressor` to 0.27 (CVE-2024-36114), and changes nothing
else. The full analysis, proofs and test recipe are in [SERPSTAT-FORK.md](SERPSTAT-FORK.md).

If you run housepower 2.7.x against ClickHouse and have ever seen that error, this is why.
The full investigation, including why the bug stayed invisible for years and why it depends on
the calendar, is told in [docs/STORY.md](docs/STORY.md).

## Requirements

- Java 8 or later at runtime (bytecode target 1.8); the fork is built and tested with Java 17.
- ClickHouse server 21.1 or later; the suite runs against 21.1.9.41 in CI.

## Import

Artifacts are published to GitHub Packages; publication to Maven Central is being set up
(`com.serpstat` namespace), after which no repository configuration will be needed. Until then
add the repository to your `pom.xml` or `settings.xml` (GitHub requires a token with
`read:packages` even for public packages):

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/SerpstatGlobal/ClickHouse-Native-JDBC</url>
    </repository>
</repositories>
```

Maven:

```xml
<!-- (recommended) shaded version: dependencies relocated, nothing leaks into your classpath -->
<dependency>
    <groupId>com.serpstat</groupId>
    <artifactId>clickhouse-native-jdbc-shaded</artifactId>
    <version>2.7.1-serpstat.1</version>
</dependency>

<!-- plain version -->
<dependency>
    <groupId>com.serpstat</groupId>
    <artifactId>clickhouse-native-jdbc</artifactId>
    <version>2.7.1-serpstat.1</version>
</dependency>
```

Gradle:

```groovy
implementation "com.serpstat:clickhouse-native-jdbc-shaded:2.7.1-serpstat.1"
```

The fork is a drop-in replacement: Java packages (`com.github.housepower.*`), the driver class
(`com.github.housepower.jdbc.ClickHouseDriver`) and the URL scheme (`jdbc:clickhouse://host:9000`)
are unchanged. Only the Maven coordinates differ, so the two builds cannot be confused.

## Differences from the official [clickhouse-java](https://github.com/ClickHouse/clickhouse-java)

* Native TCP protocol instead of HTTP, data organized and compressed by columns
  ([benchmark report](docs/dev/benchmark.md)).
* Works with old servers (21.x) that the official driver no longer supports.

## Limitations (inherited from upstream)

* No expressions in inserted values (`INSERT INTO t VALUES (toDate(123456))`); queries are fine.
* No non-VALUES insert formats such as `TSV`.
* No compression methods besides LZ4 (`ZSTD` is not supported).

## Spark integration

The `clickhouse-integration-spark` module is kept in the tree untouched but is **not** built or
published by this fork. For Spark use upstream's artifacts or
[Spark ClickHouse Connector](https://github.com/housepower/spark-clickhouse-connector).

## Building

```bash
mvn -pl clickhouse-native-jdbc,clickhouse-native-jdbc-shaded -am install -DskipITs
# integration tests (Docker) against the ClickHouse version we run in production
mvn -pl clickhouse-native-jdbc verify -DCLICKHOUSE_IMAGE=yandex/clickhouse-server:21.1.9.41
```

## Contributing

Issues and pull requests are welcome, especially reports from other users hit by the checksum
defect. Keep changes minimal and covered by tests; the CityHash port is pinned against the C++
reference implementation from the ClickHouse repository (see `ClickHouseCityHashTest`).

## License

Apache License, Version 2.0, unchanged from upstream. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Modified files carry a `Modified by Serpstat` notice in their header.
