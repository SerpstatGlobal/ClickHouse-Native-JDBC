ClickHouse Native JDBC — форк Serpstat
===

[![build](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/build.yml/badge.svg?branch=serpstat%2F2.7.1)](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/build.yml)
[![publish](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/publish.yml/badge.svg)](https://github.com/SerpstatGlobal/ClickHouse-Native-JDBC/actions/workflows/publish.yml)
[![License](https://img.shields.io/github/license/SerpstatGlobal/ClickHouse-Native-JDBC)](LICENSE)

[English](README.md) | Українська

Підтримуваний форк [housepower/ClickHouse-Native-JDBC](https://github.com/housepower/ClickHouse-Native-JDBC)
2.7.1 — JDBC-драйвера для [ClickHouse](https://clickhouse.com/), що працює через нативний
TCP-протокол і стискає дані поколонково.

## Навіщо цей форк

Upstream 2.7.1 обчислює хибну контрольну суму для деяких стиснених блоків. ClickHouse у
відповідь відхиляє весь INSERT:

```
DB::Exception: Checksum doesn't match: corrupted data. ... Size of compressed block: 18.
```

Java-порт CityHash128 у драйвері розширює байти зі знаком у гілці для «хвоста» з 1..3 байтів.
Ця гілка спрацьовує рівно тоді, коли стиснений фрейм має довжину 17..19 байтів — примусове
скидання залишку `Data`-пакета розміром 7..9 байтів. Це стається приблизно на 3 з кожних
1 048 576 INSERT-ів, більших за 1 МБ, виглядає як випадкове пошкодження даних і з 2025 року
залишається без відповіді в upstream. Цей форк виправляє дефект, оновлює `aircompressor` до
0.27 (CVE-2024-36114) і не змінює нічого іншого. Повний розбір, докази та рецепт відтворення —
у [SERPSTAT-FORK.md](SERPSTAT-FORK.md) (англійською).

Якщо ви використовуєте housepower 2.7.x із ClickHouse і хоч раз бачили цю помилку — ось її причина.

## Вимоги

- Java 8 або новіша під час виконання (байткод рівня 1.8); форк збирається і тестується на Java 17.
- Сервер ClickHouse 21.1 або новіший; у CI тести проходять проти 21.1.9.41.

## Підключення

Артефакти публікуються в GitHub Packages. Додайте репозиторій у `pom.xml` або `settings.xml`
(GitHub вимагає токен з правом `read:packages` навіть для публічних пакетів):

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
<!-- (рекомендовано) shaded-версія: залежності перенесені, нічого не потрапляє у ваш classpath -->
<dependency>
    <groupId>com.serpstat</groupId>
    <artifactId>clickhouse-native-jdbc-shaded</artifactId>
    <version>2.7.1-serpstat.1</version>
</dependency>

<!-- звичайна версія -->
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

Форк є повноцінною заміною без змін у коді: Java-пакети (`com.github.housepower.*`), клас
драйвера (`com.github.housepower.jdbc.ClickHouseDriver`) і схема URL
(`jdbc:clickhouse://host:9000`) залишилися тими самими. Відрізняються лише Maven-координати,
щоб дві збірки неможливо було сплутати.

## Відмінності від офіційного [clickhouse-java](https://github.com/ClickHouse/clickhouse-java)

* Нативний TCP-протокол замість HTTP, дані організовані та стиснені поколонково
  ([звіт бенчмарку](docs/dev/benchmark.md)).
* Працює зі старими серверами (21.x), які офіційний драйвер більше не підтримує.

## Обмеження (успадковані від upstream)

* Немає підтримки виразів у вставлених значеннях (`INSERT INTO t VALUES (toDate(123456))`); запити працюють.
* Немає підтримки форматів вставки, відмінних від VALUES, наприклад `TSV`.
* Немає інших методів стиснення, крім LZ4 (`ZSTD` не підтримується).

## Інтеграція зі Spark

Модуль `clickhouse-integration-spark` залишено в дереві без змін, але цей форк його **не**
збирає і не публікує. Для Spark використовуйте артефакти upstream або
[Spark ClickHouse Connector](https://github.com/housepower/spark-clickhouse-connector).

## Збірка

```bash
mvn -pl clickhouse-native-jdbc,clickhouse-native-jdbc-shaded -am install -DskipITs
# інтеграційні тести (Docker) проти версії ClickHouse, яку ми використовуємо в продакшені
mvn -pl clickhouse-native-jdbc verify -DCLICKHOUSE_IMAGE=yandex/clickhouse-server:21.1.9.41
```

## Участь у розробці

Issues та pull requests вітаються, особливо звіти від інших користувачів, які натрапили на
дефект контрольної суми. Зміни мають бути мінімальними та покритими тестами; порт CityHash
закріплено тестами проти еталонної C++-реалізації з репозиторію ClickHouse
(див. `ClickHouseCityHashTest`).

## Ліцензія

Apache License, Version 2.0, без змін відносно upstream. Див. [LICENSE](LICENSE) та
[NOTICE](NOTICE). Змінені файли містять позначку `Modified by Serpstat` у заголовку.
