# **Kiara DB and SQLite: in-memory read-execution comparison**

This experiment compares embedded Kiara with SQLite through Xerial JDBC, using the same five
retail/catalog/schedule fixtures and independent result oracle. It is a warm, synchronous,
single-connection read experiment, not a universal database ranking or a throughput benchmark.

[Raw trials, SQL alternatives, bound parameters, plans, VM bytecode and index inventories](../benchmarks/sqlite-comparison.json) |
[Reproduction](../benchmarks/sqlite/README.md) |
[Harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/SqliteBenchmark.java)

Generated: 2026-10-28T14:51:02.142070400Z.

Versions: Kiara **2.0.16**, SQLite **3.53.4**, Xerial JDBC **3.53.4.0**; both workers use Java **21.0.12.1+1-LTS**.

Measured executable SHA-256: `580a3396b9bb455947c0cf48a886b671fa587cf318982a8f1fbc107828cc6a27`.

## Timing boundary and fairness

| Boundary | Kiara | SQLite |
| --- | --- | --- |
| Included | Search API and eager Java result-map creation | Prepared execution, all cursor steps, JDBC/JNI conversion, eager Java result-map creation, cursor closure |
| Prepared before timing | Conditions, projection, limit | SQL compilation and parameter binding |
| Excluded | Fixture/index construction, ID extraction, validation | Schema/loading/indexes/ANALYZE, plan inspection, ID extraction, validation |
| Timed result shape | ID-only maps, or ID/CATEGORY/PRICE maps | Same map keys and Java container types |
| Storage | In-memory Kiara store | Private connection-local `:memory:` database; temporary work in memory |

Results are fully enumerated and consumed; there is no result cache, count-only shortcut,
or post-query filtering in Java. Both clocks use System.nanoTime in the same Java runtime.
A back-to-back timer pair is retained diagnostically and is not subtracted.
Equal map shapes do not mean identical allocation or conversion costs. SQLite necessarily pays
JNI/JDBC and cursor overhead; Kiara reconstructs values through its search API.
This is application-facing embedded execution with materialization, **not pure native SQL
execution, pure index traversal, CPU time, or a direct continuation of the VoltDB timing boundary**.

## SQLite physical design and tuning

SQLite uses typed scalar columns, indexed list-child rows, paired interval endpoints and
Kiara-compatible missing-value sentinels. This reuses the validated relational representation
and portable SQL compiler; it does not connect to VoltDB. Fixture numeric values lie within
exact binary64 integer range; interval endpoints are stored as 64-bit integers.

Scalar indexes include result IDs for covering scans. Compound indexes cover tested conjunctions;
interval indexes include lower/upper endpoints, and child indexes cover text, number, owner,
and interval lookups. The exact DDL and key order are retained, not inferred from index names.
A full ANALYZE runs after loading and index construction. Automatic indexing remains enabled.
temp_store=MEMORY and cache_size=-262144 (a 256-MiB suggested page-cache target) are recorded;
this is not a 256-MiB total memory limit. Other journal/synchronous settings are recorded,
not presented as evidence of persistent-database durability.

Alternatives include indexed joins, direct list/IN queries, correlated EXISTS/NOT EXISTS,
set difference, and indexed set intersections where applicable. All interval predicates refer
to paired endpoints of the same child row. Every successful candidate must pass the full oracle.
Each candidate gets at least ten calls/500 ms warmup and 21 timing samples. Candidate order is
reproducibly shuffled. The lowest median is frozen per dataset/query before measurement.
A tuning-only progress callback checks a ten-second SQL deadline every 10,000 VM instructions;
explicit deadline interruptions are retained as failed candidates and cannot win. Its overhead
can affect tuning choice; the callback is removed for final measurement.
Other SQL errors and incorrect results abort. The selected SQL, bound parameters,
EXPLAIN QUERY PLAN, VM bytecode, schema, index inventory, statistics and build versions must
match the frozen evidence in fresh SQLite trials.

This is best among tested SQL alternatives under this workload-specific schema, not a proof of
global optimality. SQLite receives SQL/index tuning while the Kiara schema and API formulations
are held fixed. No claim is made that both engines are equally or exhaustively optimized.
The tuning and evaluation fixtures are the same, not a held-out generalization experiment.

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

| Dataset | Equivalent queries | SQLite / Kiara | Exploratory 95% interval | Lower p50: Kiara / SQLite |
| --- | ---: | ---: | --- | ---: |
| retail-3788 | 5 | 5.300x | 4.831-6.471x | 5 / 0 |
| catalog-10000 | 21 | 1.675x | 1.431-1.744x | 16 / 5 |
| catalog-100000 | 21 | 0.613x | 0.538-0.683x | 7 / 14 |
| schedule-10000 | 21 | 0.687x | 0.653-0.730x | 14 / 7 |
| schedule-100000 | 21 | 0.299x | 0.286-0.309x | 12 / 9 |

Evidence: 50 measured trials, 5 tuning trials, 197 candidates (0 timed out), 1010 timed scenario/trial entries, and 96240 raw execution samples.

KNOWN_DECIMAL_TRUNCATION: 40 scenario/trial entries; excluded from ratios.

UNSUPPORTED: 180 scenario/trial entries; excluded from ratios.

## Detailed results

Times below are microseconds. The displayed p95 is the median of trial p95s, not a pooled
percentile. With 15 calls, nearest-rank p95 is the maximum and is weak tail-latency evidence.
Use the unrounded raw values when inverting small ratios.

### retail-3788

3,788 genuine stock codes from the attributed UCI Online Retail projection used by the TypeScript report (CC BY 4.0); historical GBP prices, units sold and observed markets. SHA-256 08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253

Fixture SHA-256: `04e397cbe48dcf487b9dfbdb8e5e47e587ae6160fa1bd0115af29582525c6234`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **decimal-between-japan**: Japan AND historical unitPrice between GBP 1 and 2 | KNOWN_DECIMAL_TRUNCATION / PASS | 86 / 56 | 32.303 / 231.021 | 54.305 / 292.127 | 101 / 101 | n/a | indexed-default |
| **decimal-lt**: price LESS_THAN [0.20] | KNOWN_DECIMAL_TRUNCATION / PASS | 0 / 121 | 0.300 / 103.309 | 0.700 / 145.913 | 101 / 101 | n/a | indexed-default |
| **keyword-stem**: lanterns | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: candle holdr | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **markets-all**: tags CONTAINS_ALL [Japan, France] | PASS / PASS | 172 / 172 | 54.205 / 391.035 | 80.007 / 440.638 | 101 / 101 | 7.2140x | intersections |
| **markets-either**: tags CONTAINS_EITHER [Japan, Australia] | PASS / PASS | 706 / 706 | 171.216 / 642.457 | 213.719 / 732.064 | 101 / 101 | 3.7523x | intersections |
| **markets-not**: tags NOT_CONTAINS [United Kingdom] | PASS / PASS | 6 / 6 | 248.322 / 654.660 | 320.751 / 710.965 | 101 / 101 | 2.6363x | set-difference |
| **one-edit-suggestion**: lanern | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: lant | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: tags CONTAINS_EITHER [Japan] | PASS / PASS | 215 / 215 | 56.206 / 549.056 | 87.908 / 709.168 | 101 / 101 | 9.7686x | indexed-default |
| **units-gte**: quantity GREATER_THAN_EQUAL [20000] | PASS / PASS | 18 / 18 | 3.200 / 19.202 | 3.500 / 26.402 | 101 / 101 | 6.0006x | indexed-default |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `markets-not`: Kiara 0.248322 ms; SQLite 0.654660 ms; SQLite/Kiara 2.636335.
  - Plan: `MERGE (EXCEPT)`.
  - Plan: `LEFT`.
  - Plan: `SCAN r USING COVERING INDEX sqlite_autoindex_records_1`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX text_values (field=? AND text_value=?)`.
- `projection`: Kiara 0.056206 ms; SQLite 0.549056 ms; SQLite/Kiara 9.768637.
  - Plan: `SEARCH v0 USING COVERING INDEX text_values (field=? AND text_value=?)`.
  - Plan: `SEARCH r USING INDEX sqlite_autoindex_records_1 (id=?)`.
  - Plan: `USE TEMP B-TREE FOR DISTINCT`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 5.456x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### catalog-10000

10000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

Fixture SHA-256: `383a988f19108a3700f55a21303fa6fe4b1c88f1945e175c409b8a0134c27a13`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 29 / 29 | 337.031 / 41.304 | 406.935 / 70.107 | 101 / 101 | 0.1226x | indexed-default |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 129 / 129 | 113.811 / 89.508 | 161.516 / 128.713 | 101 / 101 | 0.7865x | indexed-default |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 189 / 189 | 24.303 / 122.612 | 31.003 / 159.116 | 101 / 101 | 5.0451x | indexed-default |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 25 / 0 | 3.600 / 9.501 | 4.300 / 10.101 | 101 / 101 | n/a | indexed-default |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 101 / 99 | 14.801 / 61.906 | 19.502 / 88.910 | 101 / 101 | n/a | indexed-default |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 294 / 282 | 54.505 / 172.115 | 81.808 / 221.724 | 101 / 101 | n/a | indexed-default |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 2263 / 2263 | 1066.305 / 1437.140 | 1175.916 / 1542.954 | 101 / 101 | 1.3478x | indexed-default |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 1314.227 / 26.303 | 1521.850 / 35.103 | 101 / 101 | n/a | indexed-default |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 23 / 23 | 3.900 / 21.803 | 4.300 / 26.602 | 101 / 101 | 5.5905x | indexed-default |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | 51.606 / 337.934 | 80.808 / 388.638 | 101 / 101 | 6.5483x | indexed-default |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 4009 / 4009 | 891.000 / 2465.940 | 1086.207 / 2543.818 | 101 / 101 | 2.7676x | indexed-default |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 29 / 29 | 126.812 / 460.648 | 172.017 / 525.451 | 101 / 101 | 3.6325x | correlated-exists |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 486 / 486 | 477.376 / 916.589 | 556.555 / 994.796 | 101 / 101 | 1.9201x | intersections |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 4054 / 4054 | 1243.940 / 2425.242 | 2088.106 / 2512.351 | 101 / 101 | 1.9496x | indexed-default |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 2009 / 2009 | 492.048 / 1127.111 | 569.356 / 1212.019 | 101 / 101 | 2.2907x | indexed-default |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 3935 / 3935 | 1094.808 / 2227.091 | 1629.761 / 2336.129 | 101 / 101 | 2.0342x | indexed-default |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 2011 / 2011 | 488.149 / 1182.415 | 565.957 / 1247.422 | 101 / 101 | 2.4222x | indexed-default |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 4042 / 4042 | 1133.810 / 2406.734 | 1660.361 / 2494.346 | 101 / 101 | 2.1227x | indexed-default |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 8000 / 8000 | 1724.971 / 4882.119 | 2602.156 / 5013.730 | 101 / 101 | 2.8303x | direct-in |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 175 / 175 | 236.723 / 122.512 | 288.328 / 165.017 | 101 / 101 | 0.5175x | direct-in |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 195 / 195 | 169.527 / 127.213 | 220.621 / 173.618 | 101 / 101 | 0.7504x | indexed-default |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 101 / 101 | 50.205 / 449.144 | 73.407 / 530.952 | 101 / 101 | 8.9462x | intersections |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 2196 / 2196 | 702.459 / 2225.838 | 819.869 / 2360.230 | 101 / 101 | 3.1686x | intersections |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 43 / 43 | 32.105 / 310.430 | 57.405 / 371.437 | 101 / 101 | 9.6692x | correlated-exists |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 6142 / 6142 | 609243.203 / 4722.761 | 619365.561 / 4929.520 | 15 / 101 | 0.0078x | set-difference |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | n/a | n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `tags-exclude`: Kiara 609.243203 ms; SQLite 4.722761 ms; SQLite/Kiara 0.007752.
  - Plan: `MERGE (EXCEPT)`.
  - Plan: `LEFT`.
  - Plan: `SEARCH r USING COVERING INDEX scalar_status (f_status=?)`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX text_values (field=? AND text_value=?)`.
- `tags-either-null`: Kiara 0.032105 ms; SQLite 0.310430 ms; SQLite/Kiara 9.669210.
  - Plan: `SEARCH r USING COVERING INDEX composite_7 (f_brand=?)`.
  - Plan: `SEARCH v EXISTS USING COVERING INDEX text_values (field=? AND text_value=? AND id=?)`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 2.027x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### catalog-100000

100000 seeded synthetic product records: skewed status, regions, enum tiers, sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. Java Random seed 20261027; not the TypeScript generator's byte-identical rows.

Fixture SHA-256: `cb4a74653fef3a19dabbff894b67aeb6986e7ad9b271489c68563e3ff6e8c5d4`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 267 / 267 | 7714.287 / 188.517 | 9570.171 / 234.023 | 101 / 101 | 0.0244x | indexed-default |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 1327 / 1327 | 2339.418 / 836.973 | 4186.825 / 932.883 | 101 / 101 | 0.3578x | indexed-default |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 1951 / 1951 | 541.752 / 1204.418 | 619.255 / 1279.426 | 101 / 101 | 2.2232x | indexed-default |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 209 / 3 | 36.603 / 11.501 | 59.806 / 15.002 | 101 / 101 | n/a | indexed-default |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 991 / 950 | 306.335 / 531.847 | 371.142 / 609.158 | 101 / 101 | n/a | indexed-default |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 2975 / 2869 | 1015.097 / 1713.867 | 1274.098 / 1835.671 | 101 / 101 | n/a | indexed-default |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 23329 / 23329 | 31308.902 / 14406.195 | 35519.972 / 15065.909 | 82-93 / 101 | 0.4601x | indexed-default |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 39750.095 / 26.803 | 44808.595 / 42.905 | 72-85 / 101 | n/a | indexed-default |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 208 / 208 | 48.704 / 120.112 | 72.608 / 157.015 | 101 / 101 | 2.4662x | indexed-default |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | 805.569 / 3895.272 | 926.187 / 4770.656 | 101 / 101 | 4.8354x | indexed-default |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 40009 / 40009 | 28797.651 / 24033.888 | 33090.285 / 24642.402 | 82-101 / 101 | 0.8346x | direct-in |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 260 / 260 | 3593.134 / 4530.521 | 5532.202 / 4846.551 | 101 / 101 | 1.2609x | correlated-exists |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 4866 / 4866 | 12517.127 / 8797.815 | 15039.394 / 9191.051 | 101 / 101 | 0.7029x | intersections |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 39960 / 39960 | 37867.716 / 23535.807 | 43643.499 / 24584.503 | 66-90 / 101 | 0.6215x | indexed-default |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 20122 / 20122 | 16243.095 / 11241.994 | 17859.444 / 11598.028 | 101 / 101 | 0.6921x | indexed-default |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 39805 / 39805 | 37106.631 / 22009.482 | 42338.829 / 22611.907 | 70-79 / 101 | 0.5931x | indexed-default |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 20235 / 20235 | 17718.429 / 11901.241 | 20917.324 / 12711.539 | 101 / 101 | 0.6717x | indexed-default |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 40131 / 40131 | 36181.924 / 23847.113 | 42184.998 / 24360.196 | 68-85 / 101 | 0.6591x | indexed-default |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 80000 / 80000 | 46983.517 / 48661.621 | 60637.142 / 52203.329 | 47-58 / 57-60 | 1.0357x | direct-in |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 1774 / 1774 | 7644.047 / 1177.601 | 9498.556 / 1241.206 | 101 / 101 | 0.1541x | direct-in |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 1980 / 1980 | 3027.101 / 1235.621 | 5734.113 / 1317.229 | 101 / 101 | 0.4082x | indexed-default |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 1048 / 1048 | 788.773 / 4347.409 | 911.387 / 4636.453 | 101 / 101 | 5.5116x | intersections |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 21951 / 21951 | 26258.478 / 23840.432 | 29230.058 / 24648.411 | 98-101 / 101 | 0.9079x | intersections |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 391 / 391 | 885.385 / 3224.689 | 1003.309 / 3335.299 | 101 / 101 | 3.6421x | correlated-exists |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 61996 / 61996 | 14663549.601 / 47469.675 | 14810246.127 / 49352.203 | 15 / 60-61 | 0.0032x | set-difference |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | n/a | n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `tags-exclude`: Kiara 14663.549601 ms; SQLite 47.469675 ms; SQLite/Kiara 0.003237.
  - Plan: `MERGE (EXCEPT)`.
  - Plan: `LEFT`.
  - Plan: `SEARCH r USING COVERING INDEX scalar_status (f_status=?)`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX text_values (field=? AND text_value=?)`.
- `tags-all`: Kiara 0.788773 ms; SQLite 4.347409 ms; SQLite/Kiara 5.511610.
  - Plan: `MERGE (INTERSECT)`.
  - Plan: `LEFT`.
  - Plan: `SEARCH v USING COVERING INDEX text_values (field=? AND text_value=?)`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX text_values (field=? AND text_value=?)`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 0.719x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### schedule-10000

10000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

Fixture SHA-256: `6e5b27872478957ea3152380c0eb705acb26179e3702441d3986a3e6d346575c`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 18 / 18 | 9252.265 / 91.508 | 9905.205 / 134.911 | 101 / 101 | 0.0099x | indexed-default |
| **date-between**: city3 AND dates in March | PASS / PASS | 70 / 70 | 801.671 / 58.605 | 899.178 / 92.108 | 101 / 101 | 0.0731x | indexed-default |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 33 / 33 | 5.801 / 27.803 | 6.301 / 38.504 | 101 / 101 | 4.7928x | indexed-default |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 46 / 46 | 8.100 / 34.503 | 9.600 / 57.805 | 101 / 101 | 4.2596x | indexed-default |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 374 / 374 | 137977.646 / 321.328 | 147414.345 / 378.431 | 19-27 / 101 | 0.0023x | indexed-default |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 93 / 93 | 26.002 / 106.609 | 32.603 / 155.313 | 101 / 101 | 4.1000x | intersections |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 2000 / 2000 | 638.261 / 1207.902 | 703.468 / 1274.511 | 101 / 101 | 1.8925x | indexed-default |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 17 / 17 | 9.401 / 198.517 | 15.501 / 242.220 | 101 / 101 | 21.1166x | intersections |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 110 / 110 | 100.909 / 375.532 | 144.613 / 420.134 | 101 / 101 | 3.7215x | intersections |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 871 / 871 | 295.825 / 719.361 | 363.423 / 786.068 | 101 / 101 | 2.4317x | indexed-default |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 871 / 871 | 316.628 / 2348.704 | 409.436 / 2470.214 | 101 / 101 | 7.4179x | intersections |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 335.129 / 23.001 | 423.035 / 49.104 | 101 / 101 | n/a | indexed-default |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 280 / 280 | 52896.914 / 199.817 | 56126.783 / 252.322 | 50-64 / 101 | 0.0038x | indexed-default |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 445 / 445 | 4822.314 / 534.445 | 5020.831 / 601.651 | 101 / 101 | 0.1108x | indexed-default |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 48 / 48 | 9.401 / 36.503 | 13.401 / 51.604 | 101 / 101 | 3.8829x | indexed-default |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 1232 / 1232 | 723.660 / 9339.362 | 782.066 / 9906.346 | 101 / 101 | 12.9057x | intersections |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 284 / 284 | 715.361 / 1374.619 | 763.865 / 1452.325 | 101 / 101 | 1.9216x | indexed-default |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 6793 / 6793 | 6530.762 / 6642.957 | 10006.174 / 6909.090 | 101 / 101 | 1.0172x | set-difference |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 948 / 948 | 7652.857 / 663.358 | 8447.025 / 726.662 | 101 / 101 | 0.0867x | indexed-default |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 7 / 7 | 1530.831 / 14.301 | 1687.446 / 19.202 | 101 / 101 | 0.0093x | indexed-default |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 23 / 23 | 18.201 / 201.817 | 26.902 / 252.521 | 101 / 101 | 11.0882x | intersections |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 53 / 53 | 83.407 / 225.018 | 116.410 / 289.524 | 101 / 101 | 2.6978x | intersections |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 239 / 239 | n/a | n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `date-range`: Kiara 137.977646 ms; SQLite 0.321328 ms; SQLite/Kiara 0.002329.
  - Plan: `SEARCH r USING COVERING INDEX composite_10 (f_category=? AND f_window_prefix=? AND f_window_lower<?)`.
- `dates-all`: Kiara 0.009401 ms; SQLite 0.198517 ms; SQLite/Kiara 21.116583.
  - Plan: `MERGE (INTERSECT)`.
  - Plan: `LEFT`.
  - Plan: `SEARCH v USING COVERING INDEX numeric_values (field=? AND number_value=?)`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX numeric_values (field=? AND number_value=?)`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 0.774x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

### schedule-100000

100000 seeded synthetic events: five event types, 12 cities, 40 venues, varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, closures and split booking intervals. Java Random seed 20261028.

Fixture SHA-256: `c5665efceaaa727ba5404e8fa9482139c56ea1712b6aa59ce3bb4bcd30bf8d03`.

| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 173 / 173 | 441433.915 / 369.629 | 459060.069 / 431.934 | 15 / 101 | 0.0008x | indexed-default |
| **date-between**: city3 AND dates in March | PASS / PASS | 735 / 735 | 12981.361 / 479.341 | 15542.267 / 518.743 | 101 / 101 | 0.0369x | indexed-default |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 279 / 279 | 56.905 / 170.413 | 85.307 / 204.722 | 101 / 101 | 2.9947x | indexed-default |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 525 / 525 | 140.212 / 294.729 | 192.018 / 350.829 | 101 / 101 | 2.1020x | indexed-default |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 4064 / 4064 | 9638759.384 / 3147.366 | 9713888.890 / 3258.556 | 15 / 101 | 0.0003x | indexed-default |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 1022 / 1022 | 408.233 / 1138.689 | 491.842 / 1212.696 | 101 / 101 | 2.7893x | intersections |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 20000 / 20000 | 19688.197 / 11949.614 | 23465.323 / 12397.800 | 101 / 101 | 0.6069x | indexed-default |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 187 / 187 | 237.720 / 1819.248 | 300.825 / 1900.149 | 101 / 101 | 7.6529x | intersections |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 1083 / 1083 | 1693.853 / 3307.820 | 2282.095 / 3396.429 | 101 / 101 | 1.9528x | intersections |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 8262 / 8262 | 6217.404 / 6730.648 | 8851.518 / 6941.992 | 101 / 101 | 1.0825x | indexed-default |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 8262 / 8262 | 6680.972 / 23167.865 | 8647.340 / 24383.469 | 101 / 101 | 3.4677x | intersections |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 4093.036 / 23.402 | 5217.529 / 29.202 | 101 / 101 | n/a | indexed-default |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 2836 / 2836 | 1241023.330 / 1876.247 | 1315717.771 / 1950.366 | 15 / 101 | 0.0015x | indexed-default |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 4481 / 4481 | 74582.229 / 4866.300 | 84918.592 / 4986.410 | 36-42 / 101 | 0.0652x | indexed-default |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 495 / 495 | 147.413 / 299.826 | 207.417 / 353.430 | 101 / 101 | 2.0339x | indexed-default |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 12594 / 12594 | 20901.897 / 116236.811 | 26947.812 / 121136.329 | 101 / 26 | 5.5611x | intersections |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 2547 / 2547 | 10895.463 / 17880.932 | 14261.491 / 21985.861 | 101 / 101 | 1.6411x | indexed-default |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 68803 / 68803 | 83463.254 / 68101.927 | 90827.007 / 69979.379 | 31-34 / 43-44 | 0.8160x | set-difference |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 9294 / 9294 | 118125.520 / 6154.498 | 124792.220 / 6288.309 | 22-27 / 101 | 0.0521x | indexed-default |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 77 / 77 | 29157.781 / 54.904 | 34220.474 / 91.908 | 94-101 / 101 | 0.0019x | indexed-default |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 214 / 214 | 244.219 / 1842.747 | 300.224 / 1904.951 | 101 / 101 | 7.5455x | intersections |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 461 / 461 | 1316.097 / 1890.761 | 1421.704 / 1967.760 | 101 / 101 | 1.4366x | intersections |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 2577 / 2577 | n/a | n/a | 0 / 0 | n/a | not timed |

Lowest and highest per-query ratios (descriptive, not universal speedups):

- `date-range`: Kiara 9638.759384 ms; SQLite 3.147366 ms; SQLite/Kiara 0.000327.
  - Plan: `SEARCH r USING COVERING INDEX composite_10 (f_category=? AND f_window_prefix=? AND f_window_lower<?)`.
- `dates-all`: Kiara 0.237720 ms; SQLite 1.819248 ms; SQLite/Kiara 7.652903.
  - Plan: `MERGE (INTERSECT)`.
  - Plan: `LEFT`.
  - Plan: `SEARCH v USING COVERING INDEX numeric_values (field=? AND number_value=?)`.
  - Plan: `RIGHT`.
  - Plan: `SEARCH v USING COVERING INDEX numeric_values (field=? AND number_value=?)`.

Post hoc sensitivity: removing the minimum and maximum ratios yields 0.361x. **Do not discard those queries or replace the primary aggregate with this value.** This changes workload composition, unlike the fixed-workload bootstrap.

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

## Tuning disclosure: what was done and what it cost

This is a tuned SQLite experiment, not an out-of-the-box configuration.
The measured databases each contained **38 indexes**:

| Index category | Count |
| --- | ---: |
| Generic scalar indexes, with IDs for covering scans | 17 |
| Scalar interval-bound indexes | 4 |
| List owner/value/interval indexes | 5 |
| Workload-specific compound indexes | 11 |
| Automatic primary-key index | 1 |
| **Total** | **38** |

We explicitly created 37 indexes; SQLite created one for the primary key.
This is the inventory we created, **not proof that all 38 were required** or
used by every dataset/query. Identifying a minimal useful subset needs an
index-removal experiment with plan/performance verification.

Compound indexes were designed for brand/status/region, active/category,
tier/status, optional/region, brand/optional, category/brand, brand/date,
brand/prefixed interval, category/brand/capacity/season, category/capacity,
and category/season. List interval indexes included both lower-first and
upper-first endpoint order. Full ANALYZE supplied planner statistics.
Prepared statements avoided timed SQL compilation and binding.
Temporary work was kept in memory, with a 256-MiB suggested page-cache target.

Across the five datasets, 197 SQL candidates were validated and timed.
Alternatives included indexed joins, direct list/IN queries, EXISTS/NOT EXISTS,
EXCEPT and INTERSECT. Each received warmup and 21 samples; its lowest-median
candidate was frozen before fresh measurement. No candidate timed out in the
completed tuned experiment. Native plans, VM bytecode and index inventories
were retained and checked, rather than assuming index creation implied use.

Kiara used its existing schema/internal indexes and query formulations without
manually designed query-specific compound indexes. It was not unindexed, and
its alternative query formulations were not exhaustively optimized.
SQLite therefore received additional workload-specific physical-design effort.

Additional indexes entail construction, storage and write-maintenance costs.
These were outside the read timer; this experiment does not quantify their
total cost or establish memory, write, build-time or overall database superiority.
A separate [generic-index baseline](sqlite-untuned-comparison.md) omits the
11 workload-specific compound indexes, extra upper-first list interval index,
SQL candidate selection and explicit ANALYZE. It retains generic indexing and
SQLite's normal planner, so it is not an index-free or deliberately scan-only test.
