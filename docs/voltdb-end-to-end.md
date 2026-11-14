# Kiara Java and VoltDB Community Edition read-query comparison

> **Historical end-to-end baseline:** these timings include client/server communication.
> For the tuned, instrumented query-execution experiment, use the
> [query-execution report](voltdb-query-execution.md) and
> [detailed comparison analysis](voltdb-comparison.md) instead.

**Version scope:** This uses the AGPL-licensed VoltDB Community Edition **10.1.1 (2020)**,
obtained from Maven Central, not the current commercial/developer release. The current developer
image requires a license key. The user explicitly requested Community Edition. No license checks
were bypassed and the server was not patched. These results must not be attributed to modern
commercial VoltDB. Both databases use in-memory indexes, but their APIs and architectures differ.

[Executable harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/VoltDbBenchmark.java) |
[Raw per-fork results, SQL parameters and nanosecond samples](../benchmarks/voltdb-comparison.json) |
[Setup and reproduction](../benchmarks/voltdb/README.md)

Generated: 2026-10-27T23:07:08.545163200Z.

Environment: {kiara=2.0.16, voltdb=[10.1.1], voltdbClient=10.1.1, edition=AGPL Community Edition 10.1.1 (2020); not the current commercial release, serverJarSha256=bf3e82c1b3211006da00a0d59f46dc7300fd44bc534cd4af88f8f3948c53a8ff, serverRuntime=Java 11; archived CE runtime dependencies from DBOS-project/voltdb commit 663131f448f184b2be52a1b1a4478a63c476b4c6; reload4j 1.2.26, java=21.0.13.0.2+1-LTS, os=Windows 11 10.0, cpu=AMD EPYC 7763 64-Core Processor, benchmarkJarSha256=aad76ccc33f854b207ee3b27f62806e76f623251b2eb02ea81359156c9a5ee78}.

Configuration: {datasets=[retail-3788, catalog-10000, catalog-100000, schedule-10000, schedule-100000], forksPerEngineAndDataset=3, maximumSamplesPerScenario=61, minimumSamplesPerScenario=5, minimumWarmupMsPerScenario=50, measurementBudgetMsPerScenario=1000, catalogSeed=20261027, scheduleSeed=20261028, retailCsvSha256=08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253, workers=Sequential; alternating engine order; fresh JVM per engine/dataset/fork; 4 active Java processors; 4 GiB maximum heap, server=One long-lived single-node Docker server; 4 CPU quota, 4 sites, 8 GiB container limit, 4 GiB Java heap; new tables/indexes each fork, unsupported=keyword, prefix prediction, spelling suggestion: no equivalent text adapter; skipped for BOTH engines}.

## Scope and fairness

This compares **embedded Kiara execution** with **end-to-end native-client execution against a local Docker
VoltDB server** over loopback. Timing includes client/server transport, SQL execution, fetching
all matching rows and materialization. It is not a pure index-algorithm comparison.
The server uses a 4-CPU quota and an 8-GiB limit; worker JVMs use 4 active Java processors and
at most 4 GiB heap. These are different resource boundaries, not equal memory configurations.

The same five retail/catalog/schedule fixture entries as the Lucene suite are reused without
changing their seeds, predicates or oracle. Text retrieval, prefix predictions and spelling
suggestions have no equivalent adapter here: they are explicitly **UNSUPPORTED for both engines**,
not failed queries or zero-latency wins. This says nothing about VoltDB's broader text capabilities.
All eleven structured operators are compiled into SQL; any/all/exclusion, missing values, typed
dates and inclusive interval containment are represented without scanning original records in Java.
Each stored interval retains its paired endpoints and optional prefix.

SQL uses typed scalar columns and interval endpoints in the record table, indexed child rows for lists,
and explicit owner, text, numeric and interval indexes. List queries use joins and deduplicate IDs;
all/exclusion checks use conditional aggregation over paired list rows, including missing-value sentinels.
This is a reproducible baseline, **not an expert-tuned
VoltDB physical design**. Domain-specific wide tables, partitioning and stored procedures could change results.
Tables are replicated in a single-node, four-site configuration; no distributed scale claims are made.
This synchronous read workload does not exercise VoltDB's high-throughput asynchronous transaction model.

## Summary

Ratios are VoltDB latency / Kiara latency. Above 1 means lower Kiara latency.
For each scenario take the median p50 across JVM forks, then the unweighted geometric mean of
eligible ratios. Only scenarios passing full result equivalence in every engine/fork qualify.
Limits can return different valid subsets and are excluded; fractional numeric differences
are checked explicitly and excluded rather than treated as equivalent operations.

| Dataset | Timed scenarios per engine/fork | Equivalent scenarios | Exact latency ratio | Build ms: Kiara / VoltDB |
| --- | ---: | ---: | ---: | ---: |
| retail-3788 | 7 | 5 | 67.64x | 448.2 / 6916.7 |
| catalog-10000 | 26 | 22 | 8.16x | 1208.6 / 8982.6 |
| catalog-100000 | 26 | 22 | 2.86x | 177159.2 / 48699.3 |
| schedule-10000 | 23 | 22 | 10.88x | 1503.1 / 8415.9 |
| schedule-100000 | 23 | 22 | 4.66x | 169613.6 / 46499.0 |

## Measurement details and limitations

- Three forks per engine/dataset by default, fresh worker JVM each time; sequential workers with
  alternating engine order and identical seeded query order. Each server fork creates and drops
  only its own generated tables and indexes. The same server process stays running; OS and server caches may
  remain warm across workers. This is not a fresh database process or cold-cache test.
- Input creation, independent oracle checks, JVM startup and server startup are excluded.
  A 64-row engine build/query primes classes first. Build time includes table/index creation,
  scalar/list normalization, native bulk loading and acknowledgement drain for VoltDB, and loading plus
  prepareForSearch for Kiara. These are different preparation paths, not incremental write tests.
- VoltDB executes parameterized @AdHoc SQL using its normal plan cache, not cached query results.
  SQL templates are compiled by the adapter before timing; request encoding and server plan-cache
  lookup/execution remain timed. Kiara uses the existing condition adapter.
  Every result row is fetched, not merely counted. Typed results decode stored JSON; projection
  retrieves exactly ID, CATEGORY and PRICE. Adapter choices affect the measured cost.
- At least three warmup queries and the configured warmup duration precede measurement.
  Bounded sampling uses the configured cap and time budget, with at least min(5, cap) samples.
  p50/p95 are nearest-rank percentiles; displayed values are median per-fork percentiles,
  not pooled percentiles or confidence intervals. Sample-count ranges are shown per engine.
- Kiara fractional numeric truncation is recognized only when the exact expected integer-bucket
  model matches. SQL decimal probes must match the independent oracle. Unexpected structured
  mismatches abort the run. Known decimal differences are never included in speed aggregates.
- Memory is intentionally not compared: embedded JVM heap versus a separate native database
  server is not an equivalent accounting boundary. No fabricated zero server memory is reported.
- Single-node local read queries only: no write throughput, incremental mutation, HA, distributed
  scaling, concurrency, disk isolation, cold-start ranking or analytical throughput claims.
  Both engines use in-memory data. Durability, snapshotting and recovery costs are not measured.
- This is a standalone harness, not JMH. Repeated samples on one machine do not establish
  hardware-independent speedups. Do not equate any ratios here with the separate Lucene run.

## retail-3788

3,788 genuine stock codes from the attributed UCI Online Retail projection used by the TypeScript report (CC BY 4.0); historical GBP prices, units sold and observed markets. SHA-256 08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253

| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| **decimal-between-japan**: Japan AND historical unitPrice between GBP 1 and 2 | KNOWN_DECIMAL_TRUNCATION / PASS | 86 / 56 | 59.60 / 2317.20 | 98.70 / 2769.50 | 61 / 61 | n/a |
| **decimal-lt**: price LESS_THAN [0.20] | KNOWN_DECIMAL_TRUNCATION / PASS | 0 / 121 | 2.90 / 2073.10 | 3.40 / 2732.90 | 61 / 61 | n/a |
| **keyword-stem**: lanterns | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-typo**: candle holdr | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **markets-all**: tags CONTAINS_ALL [Japan, France] | PASS / PASS | 172 / 172 | 92.00 / 10855.50 | 152.60 / 12688.60 | 61 / 61 | 117.99x |
| **markets-either**: tags CONTAINS_EITHER [Japan, Australia] | PASS / PASS | 706 / 706 | 262.30 / 9775.50 | 329.30 / 11917.70 | 61 / 61 | 37.27x |
| **markets-not**: tags NOT_CONTAINS [United Kingdom] | PASS / PASS | 6 / 6 | 452.40 / 29427.50 | 536.50 / 31576.70 | 61 / 34-35 | 65.05x |
| **one-edit-suggestion**: lanern | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **prefix-predictions**: lant | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **projection**: tags CONTAINS_EITHER [Japan] | PASS / PASS | 215 / 215 | 128.80 / 2672.20 | 165.60 / 3214.20 | 61 / 61 | 20.75x |
| **units-gte**: quantity GREATER_THAN_EQUAL [20000] | PASS / PASS | 18 / 18 | 8.20 / 1956.70 | 12.10 / 2322.00 | 61 / 61 | 238.62x |

## catalog-10000

10000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 29 / 29 | 350.60 / 1896.80 | 389.10 / 2357.30 | 61 / 61 | 5.41x |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 129 / 129 | 119.10 / 2412.00 | 177.50 / 4167.00 | 61 / 61 | 20.25x |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 189 / 189 | 37.00 / 1844.00 | 44.10 / 2120.20 | 61 / 61 | 49.84x |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 25 / 0 | 5.90 / 1703.70 | 6.90 / 1930.70 | 61 / 61 | n/a |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 101 / 99 | 20.70 / 1753.10 | 42.00 / 1950.30 | 61 / 61 | n/a |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 294 / 282 | 69.00 / 1906.30 | 105.00 / 2255.40 | 61 / 61 | n/a |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 2263 / 2263 | 1288.00 / 5120.80 | 1525.40 / 7061.90 | 61 / 61 | 3.98x |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 1368.60 / 1738.60 | 2655.60 / 2051.10 | 61 / 61 | n/a |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 23 / 23 | 6.80 / 1833.60 | 8.10 / 2477.50 | 61 / 61 | 269.65x |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | 117.50 / 2013.70 | 151.40 / 2217.20 | 61 / 61 | 17.14x |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 4009 / 4009 | 1988.00 / 4159.80 | 2097.90 / 5486.70 | 61 / 61 | 2.09x |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 29 / 29 | 322.90 / 4355.50 | 497.80 / 5437.80 | 61 / 61 | 13.49x |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 486 / 486 | 496.30 / 6094.50 | 522.10 / 7951.20 | 61 / 61 | 12.28x |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 4054 / 4054 | 1150.50 / 3315.80 | 1593.80 / 4898.70 | 61 / 61 | 2.88x |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 2009 / 2009 | 585.30 / 2681.40 | 727.40 / 4016.00 | 61 / 61 | 4.58x |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 3935 / 3935 | 1163.50 / 3749.40 | 1305.70 / 4748.70 | 61 / 61 | 3.22x |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 2011 / 2011 | 557.20 / 2417.50 | 675.10 / 2780.30 | 61 / 61 | 4.34x |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 4042 / 4042 | 1283.30 / 3497.30 | 1604.40 / 5300.50 | 61 / 61 | 2.73x |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 8000 / 8000 | 1845.40 / 4923.10 | 4067.90 / 6204.40 | 61 / 61 | 2.67x |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 175 / 175 | 284.60 / 1938.40 | 318.50 / 2232.80 | 61 / 61 | 6.81x |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 195 / 195 | 249.80 / 2207.50 | 297.40 / 2649.00 | 61 / 61 | 8.84x |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 101 / 101 | 52.40 / 17936.40 | 73.60 / 20721.20 | 61 / 53-61 | 342.30x |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 2196 / 2196 | 797.80 / 17515.30 | 1129.80 / 20272.90 | 61 / 58-61 | 21.95x |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 43 / 43 | 38.90 / 2592.10 | 47.40 / 3157.10 | 61 / 61 | 66.63x |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 6142 / 6142 | 416057.90 / 40895.20 | 427955.00 / 45960.20 | 5 / 24-25 | 0.10x |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | 5120.50 / 4257.60 | 7506.10 / 6733.50 | 61 / 61 | 0.83x |

## catalog-100000

100000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 267 / 267 | 4103.40 / 2440.00 | 7055.70 / 3232.00 | 61 / 61 | 0.59x |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 1327 / 1327 | 1703.50 / 16268.30 | 1811.80 / 19680.10 | 61 / 61 | 9.55x |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 1951 / 1951 | 567.40 / 2817.00 | 613.80 / 3406.30 | 61 / 61 | 4.96x |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 209 / 3 | 47.10 / 1678.70 | 72.30 / 1946.60 | 61 / 61 | n/a |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 991 / 950 | 301.80 / 2156.40 | 356.80 / 2970.40 | 61 / 61 | n/a |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 2975 / 2869 | 1015.40 / 3441.40 | 1378.10 / 4505.40 | 61 / 61 | n/a |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 23329 / 23329 | 24531.80 / 34768.90 | 28952.90 / 37867.80 | 37-44 / 28-29 | 1.42x |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 29232.70 / 1676.30 | 32602.30 / 1917.90 | 33-36 / 61 | n/a |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 208 / 208 | 72.10 / 1773.10 | 97.40 / 2204.90 | 61 / 61 | 24.59x |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | 1075.10 / 3895.10 | 1255.70 / 6353.50 | 61 / 61 | 3.62x |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 40009 / 40009 | 24657.70 / 40205.00 | 27071.00 / 43382.90 | 40-41 / 25-26 | 1.63x |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 260 / 260 | 2167.90 / 43645.00 | 2691.50 / 46909.80 | 61 / 22-24 | 20.13x |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 4866 / 4866 | 10487.80 / 82996.30 | 13737.80 / 86461.50 | 61 / 13 | 7.91x |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 39960 / 39960 | 30433.90 / 32443.90 | 34512.00 / 35921.60 | 33 / 29-32 | 1.07x |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 20122 / 20122 | 13529.40 / 16051.00 | 16339.00 / 18483.40 | 61 / 58-61 | 1.19x |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 39805 / 39805 | 30005.50 / 30212.30 | 36728.50 / 37200.70 | 32-35 / 33-34 | 1.01x |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 20235 / 20235 | 13612.60 / 16711.70 | 16101.50 / 18352.60 | 61 / 56-61 | 1.23x |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 40131 / 40131 | 30004.60 / 32201.90 | 36777.30 / 36924.90 | 33 / 30-32 | 1.07x |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 80000 / 80000 | 45819.50 / 40237.10 | 49189.10 / 44332.80 | 22-24 / 24-25 | 0.88x |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 1774 / 1774 | 3909.60 / 3054.10 | 7133.00 / 4369.90 | 61 / 61 | 0.78x |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 1980 / 1980 | 3507.20 / 6060.50 | 4960.70 / 9037.20 | 61 / 61 | 1.73x |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 1048 / 1048 | 690.10 / 171836.90 | 814.30 / 176254.50 | 61 / 6 | 249.00x |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 21951 / 21951 | 21112.10 / 163595.90 | 24623.00 / 167004.20 | 44-48 / 7 | 7.75x |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 391 / 391 | 782.90 / 16559.50 | 824.30 / 20614.80 | 61 / 56-61 | 21.15x |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 61996 / 61996 | 12275738.30 / 454980.50 | 12903248.60 / 473270.70 | 5 / 5 | 0.04x |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | 5225.30 / 19852.20 | 15419.70 / 25767.20 | 61 / 47-50 | 3.80x |

## schedule-10000

10000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 18 / 18 | 5416.10 / 3903.40 | 6392.90 / 5997.20 | 61 / 61 | 0.72x |
| **date-between**: city3 AND dates in March | PASS / PASS | 70 / 70 | 732.70 / 1912.40 | 856.70 / 2143.80 | 61 / 61 | 2.61x |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 33 / 33 | 8.30 / 1655.50 | 17.30 / 1923.10 | 61 / 61 | 199.46x |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 46 / 46 | 13.60 / 1701.90 | 25.40 / 1913.20 | 61 / 61 | 125.14x |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 374 / 374 | 72713.50 / 3730.30 | 79882.10 / 4579.70 | 13-15 / 61 | 0.05x |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 93 / 93 | 40.50 / 53478.20 | 48.90 / 64162.50 | 61 / 19-20 | 1320.45x |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 2000 / 2000 | 746.20 / 2375.00 | 849.40 / 3188.50 | 61 / 61 | 3.18x |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 17 / 17 | 11.50 / 11085.90 | 13.10 / 15647.80 | 61 / 61 | 963.99x |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 110 / 110 | 100.40 / 2808.30 | 151.10 / 3061.70 | 61 / 61 | 27.97x |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 871 / 871 | 361.80 / 2871.70 | 522.60 / 3874.50 | 61 / 61 | 7.94x |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 871 / 871 | 388.40 / 14350.10 | 441.70 / 17391.10 | 61 / 61 | 36.95x |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 314.10 / 1694.20 | 416.40 / 1952.50 | 61 / 61 | n/a |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 280 / 280 | 26159.90 / 3028.10 | 34752.10 / 3289.20 | 30-38 / 61 | 0.12x |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 445 / 445 | 4135.20 / 5922.30 | 7370.10 / 8446.90 | 61 / 61 | 1.43x |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 48 / 48 | 13.50 / 1694.60 | 15.80 / 1897.60 | 61 / 61 | 125.53x |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 1232 / 1232 | 1129.40 / 40356.30 | 1280.70 / 46719.10 | 61 / 23-26 | 35.73x |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 284 / 284 | 858.50 / 16351.40 | 1374.60 / 21233.50 | 61 / 58-61 | 19.05x |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 6793 / 6793 | 5948.20 / 47716.20 | 6902.40 / 51187.00 | 61 / 21-22 | 8.02x |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 948 / 948 | 6403.30 / 4275.10 | 7007.30 / 5693.50 | 61 / 61 | 0.67x |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 7 / 7 | 2107.20 / 1708.80 | 4627.40 / 1948.30 | 61 / 61 | 0.81x |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 23 / 23 | 59.40 / 10536.40 | 90.40 / 15129.00 | 61 / 51-61 | 177.38x |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 53 / 53 | 115.80 / 3114.00 | 153.50 / 5079.50 | 61 / 61 | 26.89x |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 239 / 239 | 1602.20 / 4370.40 | 6230.80 / 6630.30 | 61 / 61 | 2.73x |

## schedule-100000

100000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 173 / 173 | 291497.80 / 48351.20 | 308766.80 / 52198.80 | 5 / 20-21 | 0.17x |
| **date-between**: city3 AND dates in March | PASS / PASS | 735 / 735 | 10743.40 / 7363.80 | 11948.20 / 9664.20 | 61 / 61 | 0.69x |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 279 / 279 | 73.80 / 1884.70 | 95.90 / 2267.00 | 61 / 61 | 25.54x |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 525 / 525 | 167.60 / 2010.40 | 206.20 / 2308.10 | 61 / 61 | 12.00x |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 4064 / 4064 | 6342883.20 / 50430.50 | 6369903.10 / 56406.80 | 5 / 19-21 | 0.01x |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 1022 / 1022 | 452.60 / 641210.10 | 498.80 / 696545.40 | 61 / 5 | 1416.73x |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 20000 / 20000 | 20462.00 / 16348.30 | 22959.90 / 19006.80 | 45-51 / 57-61 | 0.80x |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 187 / 187 | 204.20 / 170542.00 | 260.60 / 180118.70 | 61 / 6-7 | 835.17x |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 1083 / 1083 | 1503.90 / 22282.90 | 1631.20 / 26315.00 | 61 / 42-51 | 14.82x |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 8262 / 8262 | 6940.10 / 25680.30 | 8167.10 / 29883.50 | 61 / 36-43 | 3.70x |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 8262 / 8262 | 7301.20 / 145009.90 | 10131.50 / 152230.60 | 61 / 7-8 | 19.86x |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 4451.60 / 1751.70 | 8087.90 / 2080.10 | 61 / 61 | n/a |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 2836 / 2836 | 789767.40 / 35024.40 | 799280.20 / 39075.10 | 5 / 27-30 | 0.04x |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 4481 / 4481 | 63951.00 / 74037.50 | 68212.70 / 83892.40 | 16-17 / 12-14 | 1.16x |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 495 / 495 | 166.20 / 2033.90 | 203.90 / 2427.50 | 61 / 61 | 12.24x |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 12594 / 12594 | 21235.20 / 455349.30 | 25451.40 / 463740.30 | 45-56 / 5 | 21.44x |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 2547 / 2547 | 9884.10 / 215472.40 | 11622.60 / 223069.40 | 61 / 5 | 21.80x |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 68803 / 68803 | 83321.10 / 555687.40 | 91355.50 / 568250.10 | 12 / 5 | 6.67x |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 9294 / 9294 | 101523.60 / 47927.20 | 117271.20 / 53561.10 | 10 / 21 | 0.47x |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 77 / 77 | 26172.70 / 1759.40 | 33091.30 / 1977.40 | 33-38 / 61 | 0.07x |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 214 / 214 | 292.80 / 167301.70 | 357.80 / 174638.50 | 61 / 6-7 | 571.39x |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 461 / 461 | 1490.00 / 19515.20 | 1644.20 / 23996.60 | 61 / 44-52 | 13.10x |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 2577 / 2577 | 8021.80 / 30049.20 | 9711.70 / 38456.40 | 61 / 33-34 | 3.75x |

## Reproduction and sources

Follow the [local setup instructions](../benchmarks/voltdb/README.md). Raw results include SQL
templates and bound parameters for each supported scenario. Do not combine worker files from
different executable hashes or input fingerprints.

- [AGPL CE server artifact and license declaration](https://repo.maven.apache.org/maven2/org/voltdb/voltdb/10.1.1/voltdb-10.1.1.pom)
- [Archived upstream runtime dependency snapshot](https://github.com/DBOS-project/voltdb/tree/663131f448f184b2be52a1b1a4478a63c476b4c6)
- [Shared datasets](../benchmarks/src/main/java/com/kapoorlabs/benchmark/Datasets.java)
- [Independent oracle](../benchmarks/src/main/java/com/kapoorlabs/benchmark/Workloads.java)
- [Retail license and attribution](../benchmarks/src/main/resources/README-retail.md)
