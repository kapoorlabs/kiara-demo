# **Kiara DB and SQLite: generic-index (untuned) in-memory read-execution comparison**

This experiment compares embedded Kiara with SQLite through Xerial JDBC, using the same five
retail/catalog/schedule fixtures and independent result oracle. It is a warm, synchronous,
single-connection read experiment, not a universal database ranking or a throughput benchmark.

[Raw trials, SQL, bound parameters, plans, VM bytecode and index inventories](../benchmarks/sqlite-untuned-comparison.json) |
[Reproduction](../benchmarks/sqlite/README.md) |
[Harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/SqliteBenchmark.java)

Generated: 2026-10-29T00:37:11.761640800Z.

Versions: Kiara **2.0.16**, SQLite **3.53.4**, Xerial JDBC **3.53.4.0**; both workers use Java **21.0.12.1+1-LTS**.

Measured executable SHA-256: `3130fc86cb6ba0e19479cedf92fcd532e018437223b6b9ed22e9d5356915e24d`.

## Timing boundary and fairness

| Boundary | Kiara | SQLite |
| --- | --- | --- |
| Included | Search API and eager Java result-map creation | Prepared execution, all cursor steps, JDBC/JNI conversion, eager Java result-map creation, cursor closure |
| Prepared before timing | Conditions, projection, limit | SQL compilation and parameter binding |
| Excluded | Fixture/index construction, ID extraction, validation | Schema/loading/index setup, plan inspection, ID extraction, validation |
| Timed result shape | ID-only maps, or ID/CATEGORY/PRICE maps | Same map keys and Java container types |
| Storage | In-memory Kiara store | Private connection-local `:memory:` database; temporary work in memory |

Results are fully enumerated and consumed; there is no result cache, count-only shortcut,
or post-query filtering in Java. Both clocks use System.nanoTime in the same Java runtime.
A back-to-back timer pair is retained diagnostically and is not subtracted.
Equal map shapes do not mean identical allocation or conversion costs. SQLite necessarily pays
JNI/JDBC and cursor overhead; Kiara reconstructs values through its search API.
This is application-facing embedded execution with materialization, **not pure native SQL
execution, pure index traversal, CPU time, or a direct continuation of the VoltDB timing boundary**.

## SQLite physical design

SQLite uses typed scalar columns, indexed list-child rows, paired interval endpoints and
Kiara-compatible missing-value sentinels. This reuses the validated relational representation
and portable SQL compiler; it does not connect to VoltDB. Fixture numeric values lie within
exact binary64 integer range; interval endpoints are stored as 64-bit integers.

This is the explicitly defined **generic-index baseline**, not an index-free SQLite setup.
There are **26 indexes: 25 explicit plus one automatic primary-key index**:
17 generic scalar indexes, four scalar interval-bound indexes, and four list owner/text/number/
lower-first interval indexes. Scalar indexes retain IDs for covering scans, as in the tuned
experiment. None of the 11 workload-specific compound indexes or the extra upper-first
list interval index is created.

The original SQL compiler output is used for every query, with no alternative-candidate
generation, SQL performance selection, explicit ANALYZE, or PRAGMA optimize. A preflight
pass checks correctness and captures the one predetermined SQL/plan per query; its single
diagnostic sample is not warmup, tuning, or part of final measurements. The raw format's
`tuning` array contains these `prepare` records for schema compatibility, not tuning trials.
There is no tuning progress callback in this profile. SQL errors and correctness failures
abort. A separately disclosed wall-clock deadline is applied equally to both engines.

SQLite's normal planner and automatic_index remain enabled. The baseline retains the same
typed schema, prepared statements, temp_store=MEMORY and cache_size=-262144 (256-MiB
suggested page-cache target, not a total memory limit). Thus "untuned" means no manual
workload-specific tuning, **not SQLite defaults, unindexed storage, or a worst-case design**.
Prepared SQL, parameters, EXPLAIN QUERY PLAN, VM bytecode, schema, index inventory,
absence of ANALYZE statistics, and build versions are checked against preflight evidence.

**Deadline amendment:** after the original no-deadline preflight exposed extremely slow
SQLite exclusion plans, the user approved a 60-second per-invocation wall deadline for
both engines. It covers validation executions, warmup and measurement calls of timed
scenarios. A watchdog records QUERY_TIMEOUT, discards that scenario/trial's partial
timing samples, atomically checkpoints completed scenarios, and terminates that worker.
A fresh worker rebuilds the same data and resumes remaining scenarios; no query thread
is abandoned inside a reused database. Worker-incarnation counts are retained.
Timeout enforcement is subject to OS scheduling and is not an exact 60-second latency
measurement. Timeouts have no result count, usable latency, p50, or p95.
Compilation, fixture construction, oracle work and untimed typed-object checks are
outside this deadline. Watchdog scheduling overhead is present for successful calls too.
Preflight timeouts do not prevent five independent measured attempts for that query.
Ratios include only queries passing in every trial of both engines; the timeout table
below exposes excluded cases so that survivor-only ratios are not mistaken for full-workload
performance. Do not rank timeouts using a substituted 60-second sample.

The [tuned report](sqlite-comparison.md) is a separate historical experiment. Both engines
are rerun here. Different run times, host state and executables mean a difference between
the two reports is not an isolated causal measurement of index/tuning benefits.

## Trial protocol and correctness

Each trial starts a fresh Linux container/JVM and rebuilds its data outside timing. Engines run
sequentially, alternating order by trial, with reproducibly shuffled scenarios. Both use the
same pinned JDK image, four-CPU quota, 8-GiB container limit, and 1-4-GiB Java heap.
Native SQLite memory is outside the Java heap but inside the container limit. The shared,
virtualized host is not dedicated bare metal; scheduling and thermal state are uncontrolled.

Defaults: five trials per engine/dataset; at least ten warmup calls and 1,000 ms;
15-101 measured calls with a 3,000-ms wall acquisition budget. The minimum may exceed the budget
for expensive queries. Raw worker options retain any overrides. Both engines are rerun;
old Kiara measurements are not recycled after changing the executable or runtime configuration.

Only fully equivalent timed ID/projection queries enter the ratios. Known Kiara fractional-price
discrepancies must match the exact truncation model and are disclosed rather than called passes.
Limits may yield different valid subsets and are not aggregated. Typed Java objects are checked
but untimed. Keyword/prediction/spelling modes lack an equivalent adapter and are UNSUPPORTED for
both engines; this is not a statement that SQLite has no full-text capabilities.

## Summary

Ratios are **SQLite time / Kiara time**: above one favors Kiara, below one favors SQLite.
Per-query latency is the median of trial p50s; the dataset ratio is the unweighted geometric mean.
Intervals use 5,000 whole-trial percentile-bootstrap resamples with fixed seed 20261028.
They preserve within-trial query correlations, but are exploratory with only five trials.
They condition on this fixed workload, not other schemas, query mixes, hardware, or tuning choices.
Win counts and geometric ratios are neither total batch latency nor measured throughput.

**Post hoc win-count amendment:** on user request after measurement, total query wins now
include separately labeled deadline-completion wins. A query earns one such win only when
one engine has validated, comparable completed measurements in every trial and the other
times out in every trial. Mixed completion/timeout outcomes earn no deadline win.
These are completion-within-deadline outcomes, not measured p50 wins: a timeout means at
least one invocation exceeded the deadline during validation, warmup or sampling.
It does not establish that the losing engine's median exceeds 60 seconds, nor provide
a completed result to validate. No timeout latency is imputed; geometric ratios and their
intervals retain the original fully measured query set. Counts are per distinct query,
not per trial. This additional endpoint was not prespecified before the benchmark.

| Dataset | Equivalent queries | SQLite / Kiara | Exploratory 95% interval | Lower p50: Kiara / SQLite | Deadline wins: Kiara / SQLite | Total query wins: Kiara / SQLite |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| retail-3788 | 5 | 11.572x | 8.516-12.649x | 5 / 0 | 0 / 0 | 5 / 0 |
| catalog-10000 | 21 | 3.334x | 3.147-3.518x | 19 / 2 | 0 / 0 | 19 / 2 |
| catalog-100000 | 20 | 1.758x | 1.591-1.832x | 13 / 7 | 1 / 0 | 14 / 7 |
| schedule-10000 | 21 | 2.023x | 1.939-2.090x | 14 / 7 | 0 / 0 | 14 / 7 |
| schedule-100000 | 20 | 0.935x | 0.848-0.973x | 12 / 8 | 1 / 0 | 13 / 8 |

Evidence: 50 measured trials, 5 preflight passes (not tuning), 101 predetermined statements (2 timed out), 1000 timed scenario/trial entries, and 94473 raw execution samples.

KNOWN_DECIMAL_TRUNCATION: 40 scenario/trial entries; excluded from ratios.

UNSUPPORTED: 180 scenario/trial entries; excluded from ratios.

QUERY_TIMEOUT: 10 scenario/trial entries; excluded from ratios.

### Deadline outcomes (not latency measurements)

Configured deadline: 60000 ms per invocation. The 60-second protocol above describes the full run; worker options/configuration govern smoke overrides.

| Dataset | Query | Kiara timed-out trials | SQLite timed-out trials | Completion outcome | Latency-ratio eligibility |
| --- | --- | ---: | ---: | --- | --- |
| catalog-100000 | tags-exclude | 0/5 | 5/5 | Kiara deadline win | Excluded from latency ratio |
| schedule-100000 | range-list-point | 0/5 | 5/5 | Kiara deadline win | Excluded from latency ratio |

## Detailed results

Times below are microseconds. The displayed p95 is the median of trial p95s, not a pooled
percentile. With 15 calls, nearest-rank p95 is the maximum and is weak tail-latency evidence.
Use the unrounded raw values when inverting small ratios.

### retail-3788

3,788 genuine stock codes from the attributed UCI Online Retail projection used by the TypeScript report (CC BY 4.0); historical GBP prices, units sold and observed markets. SHA-256 08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253

Fixture SHA-256: `04e397cbe48dcf487b9dfbdb8e5e47e587ae6160fa1bd0115af29582525c6234`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **decimal-between-japan**: Japan AND historical unitPrice between GBP 1 and 2 | KNOWN_DECIMAL_TRUNCATION / PASS | 86 / 56 | 42.203 / 177.613 | 56.705 / 224.717 | 101 / 101 | n/a | generic-original-sql |
| **decimal-lt**: price LESS_THAN [0.20] | KNOWN_DECIMAL_TRUNCATION / PASS | 0 / 121 | 0.300 / 82.007 | 0.600 / 113.409 | 101 / 101 | n/a | generic-original-sql |
| **keyword-stem**: lanterns | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: candle holdr | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **markets-all**: tags CONTAINS_ALL [Japan, France] | PASS / PASS | 172 / 172 | 64.405 / 1687.133 | 87.007 / 1810.143 | 101 / 101 | 26.1957x | generic-original-sql |
| **markets-either**: tags CONTAINS_EITHER [Japan, Australia] | PASS / PASS | 706 / 706 | 180.114 / 850.565 | 213.905 / 917.070 | 101 / 101 | 4.7224x | generic-original-sql |
| **markets-not**: tags NOT_CONTAINS [United Kingdom] | PASS / PASS | 6 / 6 | 274.421 / 14465.441 | 324.325 / 15015.968 | 101 / 101 | 52.7126x | generic-original-sql |
| **one-edit-suggestion**: lanern | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: lant | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **projection**: tags CONTAINS_EITHER [Japan] | PASS / PASS | 215 / 215 | 59.805 / 458.034 | 86.607 / 560.944 | 101 / 101 | 7.6588x | generic-original-sql |
| **units-gte**: quantity GREATER_THAN_EQUAL [20000] | PASS / PASS | 18 / 18 | 3.201 / 13.301 | 5.100 / 18.101 | 101 / 101 | 4.1553x | generic-original-sql |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `units-gte`: Kiara 0.003201 ms; SQLite 0.013301 ms; SQLite/Kiara 4.155264.
  - Plan: `SEARCH r USING COVERING INDEX scalar_quantity (f_quantity_num>?)`.
- `markets-not`: Kiara 0.274421 ms; SQLite 14.465441 ms; SQLite/Kiara 52.712588.
  - Plan: `SEARCH v0 USING COVERING INDEX text_values (field=?)`.
  - Plan: `SEARCH r USING COVERING INDEX sqlite_autoindex_records_1 (id=?)`.
  - Plan: `USE TEMP B-TREE FOR GROUP BY`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 9.822x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### catalog-10000

10000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

Fixture SHA-256: `383a988f19108a3700f55a21303fa6fe4b1c88f1945e175c409b8a0134c27a13`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 29 / 29 | 349.827 / 86.406 | 421.832 / 115.809 | 101 / 101 | 0.2470x | generic-original-sql |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 129 / 129 | 119.409 / 795.260 | 161.213 / 849.665 | 101 / 101 | 6.6600x | generic-original-sql |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 189 / 189 | 33.403 / 115.109 | 39.203 / 144.013 | 101 / 101 | 3.4461x | generic-original-sql |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 25 / 0 | 4.001 / 2.201 | 6.000 / 4.001 | 101 / 101 | n/a | generic-original-sql |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 101 / 99 | 19.602 / 57.104 | 26.302 / 85.107 | 101 / 101 | n/a | generic-original-sql |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 294 / 282 | 58.404 / 165.612 | 81.906 / 214.916 | 101 / 101 | n/a | generic-original-sql |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 2263 / 2263 | 1078.782 / 2279.941 | 1165.388 / 2376.381 | 101 / 101 | 2.1134x | generic-original-sql |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 1215.291 / 15.501 | 1368.606 / 19.502 | 101 / 101 | n/a | generic-original-sql |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 23 / 23 | 4.401 / 16.102 | 6.200 / 20.402 | 101 / 101 | 3.6587x | generic-original-sql |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | 57.405 / 378.729 | 79.606 / 417.832 | 101 / 101 | 6.5975x | generic-original-sql |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 4009 / 4009 | 927.272 / 2424.187 | 1038.181 / 2488.792 | 101 / 101 | 2.6143x | generic-original-sql |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 29 / 29 | 136.111 / 759.155 | 179.614 / 815.759 | 101 / 101 | 5.5775x | generic-original-sql |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 486 / 486 | 512.138 / 1565.819 | 586.944 / 1632.124 | 101 / 101 | 3.0574x | generic-original-sql |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 4054 / 4054 | 1081.884 / 2383.483 | 1162.687 / 2505.997 | 101 / 101 | 2.2031x | generic-original-sql |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 2009 / 2009 | 504.740 / 1112.686 | 575.840 / 1183.391 | 101 / 101 | 2.2045x | generic-original-sql |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 3935 / 3935 | 1051.179 / 2229.369 | 1124.189 / 2366.780 | 101 / 101 | 2.1208x | generic-original-sql |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 2011 / 2011 | 508.339 / 1192.591 | 552.543 / 1281.797 | 101 / 101 | 2.3461x | generic-original-sql |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 4042 / 4042 | 1090.182 / 2369.583 | 1224.592 / 2468.891 | 101 / 101 | 2.1736x | generic-original-sql |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 8000 / 8000 | 1705.620 / 5053.030 | 2007.754 / 5327.911 | 101 / 101 | 2.9626x | generic-original-sql |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 175 / 175 | 257.220 / 177.913 | 316.023 / 218.017 | 101 / 101 | 0.6917x | generic-original-sql |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 195 / 195 | 173.813 / 460.835 | 224.617 / 504.439 | 101 / 101 | 2.6513x | generic-original-sql |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 101 / 101 | 52.704 / 2233.058 | 78.906 / 2344.066 | 101 / 101 | 42.3698x | generic-original-sql |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 2196 / 2196 | 676.051 / 2897.406 | 745.956 / 3126.941 | 101 / 101 | 4.2858x | generic-original-sql |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 43 / 43 | 47.703 / 305.222 | 71.905 / 358.327 | 101 / 101 | 6.3984x | generic-original-sql |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 6142 / 6142 | 520945.126 / 11736296.804 | 544930.853 / 11871388.470 | 15 / 15 | 22.5289x | generic-original-sql |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `and-three`: Kiara 0.349827 ms; SQLite 0.086406 ms; SQLite/Kiara 0.246996.
  - Plan: `SEARCH r USING INDEX scalar_brand (f_brand=?)`.
- `tags-all`: Kiara 0.052704 ms; SQLite 2.233058 ms; SQLite/Kiara 42.369801.
  - Plan: `SEARCH v0 USING COVERING INDEX text_values (field=? AND text_value=?)`.
  - Plan: `SEARCH r USING COVERING INDEX sqlite_autoindex_records_1 (id=?)`.
  - Plan: `USE TEMP B-TREE FOR GROUP BY`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 3.345x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### catalog-100000

100000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

Fixture SHA-256: `cb4a74653fef3a19dabbff894b67aeb6986e7ad9b271489c68563e3ff6e8c5d4`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 267 / 267 | 4143.767 / 1037.094 | 5889.663 / 1114.099 | 101 / 101 | 0.2503x | generic-original-sql |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 1327 / 1327 | 1753.562 / 11975.236 | 1841.070 / 16764.698 | 101 / 101 | 6.8291x | generic-original-sql |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 1951 / 1951 | 536.742 / 1184.895 | 603.351 / 1248.299 | 101 / 101 | 2.2076x | generic-original-sql |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 209 / 3 | 43.104 / 3.800 | 66.706 / 6.201 | 101 / 101 | n/a | generic-original-sql |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 991 / 950 | 293.427 / 529.246 | 360.128 / 599.860 | 101 / 101 | n/a | generic-original-sql |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 2975 / 2869 | 951.486 / 1721.552 | 1126.803 / 1843.169 | 101 / 101 | n/a | generic-original-sql |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 23329 / 23329 | 22322.358 / 31611.465 | 30702.779 / 34218.319 | 101 / 84-97 | 1.4161x | generic-original-sql |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 30660.252 / 16.002 | 34886.003 / 20.402 | 92-99 / 101 | n/a | generic-original-sql |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 208 / 208 | 55.905 / 116.209 | 83.106 / 143.612 | 101 / 101 | 2.0787x | generic-original-sql |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | 777.069 / 3475.663 | 857.569 / 3696.829 | 101 / 101 | 4.4728x | generic-original-sql |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 40009 / 40009 | 21071.487 / 24023.261 | 25798.893 / 25052.889 | 101 / 101 | 1.1401x | generic-original-sql |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 260 / 260 | 2044.060 / 8493.064 | 2734.809 / 9101.919 | 101 / 101 | 4.1550x | generic-original-sql |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 4866 / 4866 | 7533.750 / 17384.951 | 15142.206 / 18204.724 | 101 / 101 | 2.3076x | generic-original-sql |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 39960 / 39960 | 27663.608 / 23708.115 | 33492.804 / 24422.343 | 87-101 / 101 | 0.8570x | generic-original-sql |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 20122 / 20122 | 12599.608 / 11226.592 | 15473.838 / 11382.111 | 101 / 101 | 0.8910x | generic-original-sql |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 39805 / 39805 | 28297.586 / 22384.297 | 33234.319 / 23184.318 | 89-101 / 101 | 0.7910x | generic-original-sql |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 20235 / 20235 | 13673.711 / 12096.596 | 17203.686 / 12443.439 | 101 / 101 | 0.8847x | generic-original-sql |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 40131 / 40131 | 26740.266 / 23590.396 | 31504.754 / 24230.359 | 75-101 / 101 | 0.8822x | generic-original-sql |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 80000 / 80000 | 39604.528 / 51036.492 | 46199.865 / 54179.691 | 61-68 / 56-57 | 1.2887x | generic-original-sql |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 1774 / 1774 | 3542.377 / 2005.878 | 4110.022 / 2108.520 | 101 / 101 | 0.5663x | generic-original-sql |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 1980 / 1980 | 2468.120 / 4853.143 | 2747.745 / 5734.416 | 101 / 101 | 1.9663x | generic-original-sql |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 1048 / 1048 | 749.757 / 24814.146 | 826.371 / 26057.003 | 101 / 101 | 33.0963x | generic-original-sql |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 21951 / 21951 | 20029.199 / 32558.375 | 22782.146 / 34614.680 | 101 / 85-92 | 1.6255x | generic-original-sql |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 391 / 391 | 878.771 / 3527.077 | 928.175 / 3606.383 | 101 / 101 | 4.0136x | generic-original-sql |
| **tags-exclude**: active status AND tags exclude clearance | PASS / QUERY_TIMEOUT | 61996 / n/a | 14959489.070 / TIMEOUT 5/5 | 15103553.894 / TIMEOUT 5/5 | 15 / 0 | n/a | generic-original-sql |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `and-three`: Kiara 4.143767 ms; SQLite 1.037094 ms; SQLite/Kiara 0.250278.
  - Plan: `SEARCH r USING INDEX scalar_brand (f_brand=?)`.
- `tags-all`: Kiara 0.749757 ms; SQLite 24.814146 ms; SQLite/Kiara 33.096251.
  - Plan: `SEARCH v0 USING COVERING INDEX text_values (field=? AND text_value=?)`.
  - Plan: `SEARCH r USING COVERING INDEX sqlite_autoindex_records_1 (id=?)`.
  - Plan: `USE TEMP B-TREE FOR GROUP BY`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 1.664x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### schedule-10000

10000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

Fixture SHA-256: `6e5b27872478957ea3152380c0eb705acb26179e3702441d3986a3e6d346575c`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 18 / 18 | 9369.633 / 1245.813 | 10049.500 / 1314.820 | 101 / 101 | 0.1330x | generic-original-sql |
| **date-between**: city3 AND dates in March | PASS / PASS | 70 / 70 | 811.366 / 384.034 | 875.778 / 441.139 | 101 / 101 | 0.4733x | generic-original-sql |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 33 / 33 | 7.200 / 22.702 | 8.700 / 30.002 | 101 / 101 | 3.1531x | generic-original-sql |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 46 / 46 | 11.001 / 35.203 | 12.801 / 44.604 | 101 / 101 | 3.2000x | generic-original-sql |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 374 / 374 | 125482.402 / 1492.333 | 158137.610 / 1571.140 | 20-25 / 101 | 0.0119x | generic-original-sql |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 93 / 93 | 29.703 / 850.877 | 38.304 / 901.080 | 101 / 101 | 28.6462x | generic-original-sql |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 2000 / 2000 | 655.759 / 1200.807 | 771.668 / 1277.313 | 101 / 101 | 1.8312x | generic-original-sql |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 17 / 17 | 18.402 / 1058.293 | 23.502 / 1128.402 | 101 / 101 | 57.5097x | generic-original-sql |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 110 / 110 | 107.210 / 1252.313 | 135.912 / 1296.416 | 101 / 101 | 11.6809x | generic-original-sql |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 871 / 871 | 295.228 / 716.564 | 363.832 / 790.370 | 101 / 101 | 2.4272x | generic-original-sql |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 871 / 871 | 352.832 / 4662.231 | 396.336 / 4813.646 | 101 / 101 | 13.2137x | generic-original-sql |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 336.330 / 15.902 | 394.935 / 20.101 | 101 / 101 | n/a | generic-original-sql |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 280 / 280 | 56764.445 / 1775.256 | 59168.163 / 1861.365 | 52-64 / 101 | 0.0313x | generic-original-sql |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 445 / 445 | 4972.542 / 482.543 | 5530.891 / 530.749 | 101 / 101 | 0.0970x | generic-original-sql |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 48 / 48 | 11.901 / 31.903 | 16.101 / 41.203 | 101 / 101 | 2.6807x | generic-original-sql |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 1232 / 1232 | 732.566 / 14505.708 | 809.977 / 15090.995 | 101 / 101 | 19.8012x | generic-original-sql |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 284 / 284 | 739.160 / 970896.719 | 853.269 / 983897.547 | 101 / 15 | 1313.5136x | generic-original-sql |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 6793 / 6793 | 5088.438 / 21641.792 | 7221.659 / 22339.756 | 101 / 101 | 4.2531x | generic-original-sql |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 948 / 948 | 6978.837 / 2808.260 | 7320.355 / 2973.566 | 101 / 101 | 0.4024x | generic-original-sql |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 7 / 7 | 1541.640 / 7.401 | 1815.564 / 9.701 | 101 / 101 | 0.0048x | generic-original-sql |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 23 / 23 | 33.003 / 1047.094 | 41.103 / 1129.301 | 101 / 101 | 31.7272x | generic-original-sql |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 53 / 53 | 99.609 / 562.151 | 129.912 / 618.556 | 101 / 101 | 5.6436x | generic-original-sql |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 239 / 239 | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `timestamp-window`: Kiara 1.541640 ms; SQLite 0.007401 ms; SQLite/Kiara 0.004801.
  - Plan: `SEARCH r USING COVERING INDEX scalar_timestamp (f_timestamp_num>? AND f_timestamp_num<?)`.
- `range-list-point`: Kiara 0.739160 ms; SQLite 970.896719 ms; SQLite/Kiara 1313.513609.
  - Plan: `SEARCH r USING COVERING INDEX scalar_brand (f_brand=?)`.
  - Plan: `SEARCH v0 USING COVERING INDEX range_values (field=? AND prefix=? AND lower_bound<?)`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 1.977x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### schedule-100000

100000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

Fixture SHA-256: `c5665efceaaa727ba5404e8fa9482139c56ea1712b6aa59ce3bb4bcd30bf8d03`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 173 / 173 | 420725.743 / 19462.644 | 440022.942 / 23336.511 | 15 / 101 | 0.0463x | generic-original-sql |
| **date-between**: city3 AND dates in March | PASS / PASS | 735 / 735 | 11829.048 / 3749.017 | 13517.726 / 4197.569 | 101 / 101 | 0.3169x | generic-original-sql |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 279 / 279 | 64.306 / 158.915 | 83.908 / 207.818 | 101 / 101 | 2.4712x | generic-original-sql |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 525 / 525 | 150.814 / 292.225 | 201.518 / 339.829 | 101 / 101 | 1.9377x | generic-original-sql |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 4064 / 4064 | 9552621.120 / 20577.526 | 9652919.200 / 26252.424 | 15 / 101 | 0.0022x | generic-original-sql |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 1022 / 1022 | 390.215 / 8930.590 | 444.637 / 9579.448 | 101 / 101 | 22.8863x | generic-original-sql |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 20000 / 20000 | 17815.077 / 12029.716 | 22452.362 / 12464.452 | 101 / 101 | 0.6753x | generic-original-sql |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 187 / 187 | 181.416 / 11431.639 | 227.520 / 12607.476 | 101 / 101 | 63.0134x | generic-original-sql |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 1083 / 1083 | 1616.733 / 13972.969 | 1750.956 / 14178.686 | 101 / 101 | 8.6427x | generic-original-sql |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 8262 / 8262 | 5472.874 / 6710.164 | 6696.180 / 7001.989 | 101 / 101 | 1.2261x | generic-original-sql |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 8262 / 8262 | 6028.440 / 47801.671 | 9131.189 / 51840.002 | 101 / 58-63 | 7.9294x | generic-original-sql |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 4667.217 / 15.701 | 8076.821 / 21.902 | 101 / 101 | n/a | generic-original-sql |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 2836 / 2836 | 1066778.850 / 31569.856 | 1093056.180 / 41532.353 | 15 / 88-95 | 0.0296x | generic-original-sql |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 4481 / 4481 | 72738.325 / 4773.014 | 80136.968 / 4903.726 | 39-42 / 101 | 0.0656x | generic-original-sql |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 495 / 495 | 159.413 / 288.425 | 204.917 / 349.729 | 101 / 101 | 1.8093x | generic-original-sql |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 12594 / 12594 | 16838.864 / 172387.533 | 24317.673 / 193769.922 | 101 / 17-18 | 10.2375x | generic-original-sql |
| **range-list-point**: duration includes 125 AND city3 | PASS / QUERY_TIMEOUT | 2547 / n/a | 8418.125 / TIMEOUT 5/5 | 11254.967 / TIMEOUT 5/5 | 101 / 0 | n/a | generic-original-sql |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 68803 / 68803 | 79199.096 / 293474.799 | 93496.214 / 342115.469 | 34-37 / 15 | 3.7055x | generic-original-sql |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 9294 / 9294 | 118453.972 / 42731.646 | 125894.492 / 48749.259 | 23-28 / 66-71 | 0.3607x | generic-original-sql |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 77 / 77 | 25734.077 / 47.004 | 30054.726 / 72.507 | 101 / 101 | 0.0018x | generic-original-sql |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 214 / 214 | 214.719 / 11427.622 | 276.923 / 11641.039 | 101 / 101 | 53.2213x | generic-original-sql |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 461 / 461 | 1292.512 / 5779.998 | 1377.419 / 5896.420 | 101 / 101 | 4.4719x | generic-original-sql |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 2577 / 2577 | n/a / n/a | n/a / n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `timestamp-window`: Kiara 25.734077 ms; SQLite 0.047004 ms; SQLite/Kiara 0.001827.
  - Plan: `SEARCH r USING COVERING INDEX scalar_timestamp (f_timestamp_num>? AND f_timestamp_num<?)`.
- `dates-all`: Kiara 0.181416 ms; SQLite 11.431639 ms; SQLite/Kiara 63.013400.
  - Plan: `SEARCH v0 USING COVERING INDEX numeric_values (field=? AND number_value=?)`.
  - Plan: `SEARCH r USING COVERING INDEX sqlite_autoindex_records_1 (id=?)`.
  - Plan: `USE TEMP B-TREE FOR GROUP BY`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 1.046x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

## Interpretation and publication limits

The measured comparison is query-specific. Native SQL plans and candidate measurements can
explain plausible mechanisms, but no CPU profiles prove particular bottlenecks. A SCAN in
a plan can be appropriate for broad predicates; index existence does not prove effective use.
Conversely, small output can hide large intermediate candidate sets or aggregation.
Large individual ratios should identify paths to investigate, not become a database-wide headline.

Ratios near one, especially intervals overlapping one, are not decisive wins. Workload-weighted
application latency may rank engines differently from an equal-query geometric mean.
The fixed Kiara timestamp-window formulation uses two predicates rather than a separately tuned
BETWEEN rewrite; conclusions apply to the tested forms.

This experiment does not measure file-backed SQLite, disk I/O, cold-cache behavior, persistent
durability, concurrent readers/writers, transactional write throughput, database build performance,
memory-efficiency rankings, or distributed operation. Untimed build durations in raw evidence are
diagnostic setup costs, not an independently controlled build benchmark.
A standalone harness and shared virtualized host are not substitutes for independent replication
on dedicated hardware before making broad academic claims.

## Sources

- [SQLite in-memory databases](https://www.sqlite.org/inmemorydb.html)
- [SQLite query planner](https://www.sqlite.org/queryplanner.html)
- [EXPLAIN QUERY PLAN](https://www.sqlite.org/eqp.html)
- [ANALYZE](https://www.sqlite.org/lang_analyze.html)
- [Xerial JDBC pinned release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0)
- [Retail fixture attribution](../benchmarks/src/main/resources/README-retail.md)
