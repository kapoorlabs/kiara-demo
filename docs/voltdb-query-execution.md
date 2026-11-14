# Kiara DB and VoltDB CE: instrumented query-execution comparison

**Scope:** Kiara 2.0.16 versus AGPL VoltDB Community Edition 10.1.1 (2020).
This is not a comparison with current commercial VoltDB. It measures synchronous,
warm, single-partition read execution, not client-visible latency or transaction throughput.

[Raw trials, tuning alternatives, procedure sources, plans and index catalogs](../benchmarks/voltdb-query-execution.json) |
[Reproduction](../benchmarks/voltdb/README.md) |
[Execution harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/VoltExecutionBenchmark.java)

Generated: 2026-10-28T03:32:42.775306Z.

## Timing boundary

| Included / excluded | Kiara DB | VoltDB CE |
| --- | --- | --- |
| Measured region | `StoreSearch.query` | Server-side `voltQueueSQL` through `voltExecuteSQL(true)` |
| Query preparation | Conditions and projection prepared before timer | SQL precompiled at procedure creation; arguments prepared before timer |
| Included | Query evaluation and eager result-map creation | SQL execution and native/Java VoltTable result creation, including the Java/native handoff |
| Excluded | Adapter ID extraction and correctness checks | Dispatch/queue wait before the procedure, client/server transport, client decoding and correctness checks |
| Clock | `System.nanoTime` in worker JVM | `System.nanoTime` inside the server procedure |

Result creation is deliberately included, but its representation differs (Java maps versus VoltTables).
This is **not pure index traversal, CPU time, or equal-output-encoding cost**. Transaction work outside
the timed procedure region is not measured. No estimated network latency is subtracted.
Both clocks record an additional back-to-back timer pair outside the measured query; its raw cost is
retained, not subtracted. Returning the timing table also happens outside the measured region.
The timing procedure is benchmark-only instrumentation: its clock result is nondeterministic,
uses read-only SQL, and is run with k-safety zero. It is not a production/replicated procedure design.

## VoltDB setup and tuning

- Precompiled Java SQL procedures replace timed ad-hoc SQL. Procedures route to one partition using a
  separate partitioned routing table; benchmark data is replicated and locally readable at that site.
  This intentionally favorable configuration keeps the entire fixture local to one execution site.
  It does not demonstrate a scalable partitioning strategy for these global queries.
- Baseline primary-key, scalar, text-list, numeric-list and interval indexes remain present.
  Additional compound indexes cover brand/status/region, sale/category, tier/status, missing/region,
  brand/missing, category/brand, brand/date, brand/prefixed interval, and the combined
  category/brand/capacity/season query; an upper-endpoint-first list interval index is also tested
  alongside the lower-endpoint-first index.
- SQL alternatives include the existing indexed join, direct list queries that remove redundant record
  joins, scalar/list equality `IN` predicates, simplified scalar exclusions under the non-null
  sentinel representation, set difference for list exclusions, and explicit
  record-first/value-first join orders where applicable.
  All eligible alternatives must pass the independent result oracle, including paired interval endpoints.
- Each alternative receives separate warmup and 21 server-side timing samples during tuning.
  The lowest median candidate is frozen per dataset/scenario. Final trials do not choose the
  fastest individual sample or reselect the SQL. A tuning-only query timeout is recorded as a failed
  candidate with no usable timings; it cannot win selection. Other errors or incorrect results abort
  tuning, and any error during final measurement aborts that trial.
  Final execution plans must equal the frozen plan
  after removing generated object-name identifiers.
- Tuning uses the same fixture/query definitions as evaluation and therefore is workload-specific,
  not a held-out generalization experiment. Only the documented candidates were evaluated:
  **best among tested candidates is not a provably optimal VoltDB configuration**.
  Index inventories, native plans, procedure source and all candidate samples are in the raw data.
  A scan is not automatically a missing-index defect: broad predicates can be faster with a
  sequential scan than repeated index lookups. The tuning compares those predicate rewrites too.

## Experimental protocol

Both execution environments are Linux containers on the same Docker host, each limited to four CPUs
and 8 GiB, with a 4-GiB Java heap. Kiara uses JDK 21; this legacy VoltDB server uses JDK 11.
This runtime difference remains a confounder. Host scheduling, VM behavior and unrelated host load
are not controlled as on dedicated bare-metal hardware. Container images and runtime details are
recorded in the raw platform evidence.

Each measured trial starts a fresh worker; VoltDB is also restarted before each of its measured
trials. Engine order alternates by trial. Each trial rebuilds identical seeded data and indexes
outside query timing. Scenario order is shuffled reproducibly. Warmup is at least ten calls and
1,000 ms by default. Sampling is bounded at 101 calls / 3,000 ms with at least 15 calls; the budget
controls wall-clock acquisition, not the stored execution duration. Slow queries can exceed the budget.
Inputs, index preparation, procedure compilation, plan inspection, transport and validation are untimed.
All results are actually produced and consumed; there is no count-only shortcut or result cache.
OS page caches may remain warm even when JVMs restart.

Five fixture entries cover retail (3,788 rows), catalogs (10,000/100,000), and schedules
(10,000/100,000). Integer fields, date/time values, nulls, any/all/exclusion and interval predicates
use the same independent scan oracle. Fractional-price discrepancies are disclosed and excluded.
Java-object reconstruction is correctness-checked but not timed. Text search/suggestion modes have
no equivalent adapter and are explicitly untimed. Limited subsets are validated but excluded from
equivalent-result ratios. ID and projection queries are measured separately by their declared shapes.

## Summary

Ratios are **VoltDB execution time / Kiara execution time**: above 1 favors Kiara, below 1 favors VoltDB.
Per-query p50 is the median of trial p50s; the dataset ratio is the unweighted geometric mean of
equivalent-query ratios. Workload frequency is not modeled. Five trials are the default, not a claim
that every query or production workload behaves alike.

The 95% interval is an exploratory percentile bootstrap (5,000 resamples, fixed seed), resampling
whole trials independently per engine and preserving cross-query correlation within each trial.
It describes trial variability for these fixed queries, not uncertainty over other datasets, hardware,
software versions or tuning candidates. Few trials limit its reliability; it is not proof of superiority.

| Dataset | Trials per engine | Equivalent queries | V/K aggregate ratio | Exploratory 95% interval | Lower median K / V |
| --- | ---: | ---: | ---: | --- | --- |
| retail-3788 | 5 | 5 | 10.949x | 8.977-12.457x | 5 / 0 |
| catalog-10000 | 5 | 21 | 0.988x | 0.885-1.127x | 9 / 12 |
| catalog-100000 | 5 | 21 | 0.801x | 0.705-0.859x | 7 / 14 |
| schedule-10000 | 5 | 21 | 1.324x | 1.232-1.468x | 13 / 8 |
| schedule-100000 | 5 | 21 | 0.923x | 0.850-0.987x | 12 / 9 |

### Tuning failures

The following candidates exceeded the server's 10-second query limit. They were excluded from selection, not treated as timed samples.

| Dataset | Query | Candidate | Outcome |
| --- | --- | --- | --- |
| schedule-100000 | range-list-point | record-first | Query timeout |

## Per-query results

Times are microseconds. p50 and p95 are medians of per-trial nearest-rank percentiles,
not pooled percentiles. Ranges show the minimum/maximum samples across trials.
p95 from short samples is descriptive and should not be treated as a production tail-latency estimate.
`n/a` is not zero; ratios are withheld for unsupported, untimed, fractional-mismatch and limited queries.

### retail-3788

Fixture SHA-256: `04e397cbe48dcf487b9dfbdb8e5e47e587ae6160fa1bd0115af29582525c6234`.

| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **decimal-between-japan**: Japan AND historical unitPrice between GBP 1 and 2 | KNOWN_DECIMAL_TRUNCATION / PASS | 86 / 56 | 30.903 / 322.935 | 54.906 / 419.846 | 101 / 101 | n/a | indexed-default |
| **decimal-lt**: price LESS_THAN [0.20] | KNOWN_DECIMAL_TRUNCATION / PASS | 0 / 121 | 0.300 / 38.204 | 0.300 / 55.506 | 101 / 101 | n/a | indexed-default |
| **keyword-stem**: lanterns | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: candle holdr | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **markets-all**: tags CONTAINS_ALL [Japan, France] | PASS / PASS | 172 / 172 | 53.606 / 1782.593 | 88.510 / 2577.779 | 101 / 101 | 33.254x | direct-in |
| **markets-either**: tags CONTAINS_EITHER [Japan, Australia] | PASS / PASS | 706 / 706 | 163.617 / 596.664 | 211.322 / 724.080 | 101 / 101 | 3.647x | direct-in |
| **markets-not**: tags NOT_CONTAINS [United Kingdom] | PASS / PASS | 6 / 6 | 222.524 / 5606.905 | 278.131 / 7092.737 | 101 / 101 | 25.197x | set-difference |
| **one-edit-suggestion**: lanern | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: lant | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: tags CONTAINS_EITHER [Japan] | PASS / PASS | 215 / 215 | 56.306 / 452.948 | 82.409 / 562.861 | 101 / 101 | 8.044x | indexed-default |
| **units-gte**: quantity GREATER_THAN_EQUAL [20000] | PASS / PASS | 18 / 18 | 3.000 / 19.202 | 3.301 / 32.701 | 101 / 101 | 6.401x | indexed-default |

Diagnostic median timer-pair cost: Kiara 0 ns; VoltDB 0 ns (not subtracted).

### catalog-10000

Fixture SHA-256: `383a988f19108a3700f55a21303fa6fe4b1c88f1945e175c409b8a0134c27a13`.

| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 29 / 29 | 375.343 / 24.102 | 449.651 / 41.805 | 101 / 101 | 0.064x | indexed-default |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 129 / 129 | 125.013 / 51.705 | 179.919 / 82.509 | 101 / 101 | 0.414x | indexed-default |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 189 / 189 | 27.303 / 58.906 | 34.904 / 75.308 | 101 / 101 | 2.157x | indexed-default |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 25 / 0 | 4.101 / 9.201 | 4.401 / 14.701 | 101 / 101 | n/a | indexed-default |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 101 / 99 | 15.402 / 28.103 | 24.903 / 47.506 | 101 / 101 | n/a | indexed-default |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 294 / 282 | 57.906 / 71.907 | 87.509 / 98.111 | 101 / 101 | n/a | indexed-default |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 2263 / 2263 | 1157.725 / 523.750 | 1277.038 / 1175.214 | 101 / 101 | 0.452x | indexed-default |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 1282.939 / 17.202 | 1984.926 / 26.602 | 101 / 101 | n/a | indexed-default |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 23 / 23 | 4.600 / 14.301 | 5.501 / 25.403 | 101 / 101 | 3.109x | indexed-default |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | 53.406 / 65.705 | 84.109 / 103.011 | 101 / 101 | 1.230x | indexed-default |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 4009 / 4009 | 1022.911 / 1257.235 | 1213.721 / 1638.076 | 101 / 101 | 1.229x | indexed-default |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 29 / 29 | 141.715 / 1995.505 | 187.920 / 2805.697 | 101 / 101 | 14.081x | indexed-default |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 486 / 486 | 541.158 / 5855.929 | 726.779 / 7406.595 | 101 / 101 | 10.821x | value-first |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 4054 / 4054 | 1347.241 / 621.166 | 2005.114 / 924.699 | 101 / 101 | 0.461x | indexed-default |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 2009 / 2009 | 556.458 / 236.225 | 612.865 / 292.231 | 101 / 101 | 0.425x | indexed-default |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 3935 / 3935 | 1223.730 / 435.336 | 2024.615 / 524.043 | 101 / 101 | 0.356x | indexed-default |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 2011 / 2011 | 550.060 / 384.340 | 930.199 / 479.351 | 101 / 101 | 0.699x | indexed-default |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 4042 / 4042 | 1243.033 / 791.782 | 2008.517 / 1590.569 | 101 / 101 | 0.637x | indexed-default |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 8000 / 8000 | 2289.248 / 1132.519 | 2911.116 / 1429.449 | 101 / 101 | 0.495x | scalar-rewrite |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 175 / 175 | 277.929 / 74.508 | 338.935 / 109.411 | 101 / 101 | 0.268x | indexed-default |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 195 / 195 | 173.019 / 69.307 | 234.123 / 96.610 | 101 / 101 | 0.401x | indexed-default |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 101 / 101 | 53.406 / 2388.149 | 83.409 / 2818.565 | 101 / 101 | 44.717x | direct-in |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 2196 / 2196 | 739.882 / 1896.596 | 952.101 / 2210.981 | 101 / 101 | 2.563x | direct-in |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 43 / 43 | 34.804 / 643.069 | 59.107 / 753.281 | 101 / 101 | 18.477x | indexed-default |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 6142 / 6142 | 624715.777 / 7452.698 | 639500.895 / 9622.830 | 15 / 101 | 0.012x | set-difference |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 189 / 189 | n/a | n/a | 0 / 0 | n/a | not timed |

Diagnostic median timer-pair cost: Kiara 0 ns; VoltDB 0 ns (not subtracted).

### catalog-100000

Fixture SHA-256: `cb4a74653fef3a19dabbff894b67aeb6986e7ad9b271489c68563e3ff6e8c5d4`.

| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **and-three**: active status AND emea region AND Brand7 | PASS / PASS | 267 / 267 | 6543.911 / 131.614 | 9343.073 / 193.918 | 101 / 101 | 0.020x | indexed-default |
| **boolean-sale**: on sale AND category3 | PASS / PASS | 1327 / 1327 | 2446.042 / 525.650 | 3635.184 / 613.258 | 101 / 101 | 0.215x | indexed-default |
| **brand-equal**: brand EQUAL [BRAND7] | PASS / PASS | 1951 / 1951 | 548.551 / 552.657 | 678.265 / 831.279 | 101 / 101 | 1.007x | indexed-default |
| **decimal-equal**: price EQUAL [19.99] | KNOWN_DECIMAL_TRUNCATION / PASS | 209 / 3 | 37.604 / 13.901 | 57.405 / 29.403 | 101 / 101 | n/a | indexed-default |
| **decimal-gte**: price GREATER_THAN_EQUAL [495.25] | KNOWN_DECIMAL_TRUNCATION / PASS | 991 / 950 | 298.430 / 222.121 | 377.938 / 283.828 | 101 / 101 | n/a | indexed-default |
| **decimal-lte**: price LESS_THAN_EQUAL [19.50] | KNOWN_DECIMAL_TRUNCATION / PASS | 2975 / 2869 | 1045.110 / 780.474 | 1211.728 / 1098.104 | 101 / 101 | n/a | indexed-default |
| **enum-tier**: tier PRO AND status active | PASS / PASS | 23329 / 23329 | 29041.889 / 22636.755 | 32379.950 / 24726.842 | 77-101 / 101 | 0.779x | indexed-default |
| **keyword-discovery**: active emea | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-filtered**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-punctuation**: wireless, lamp! | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-stem**: chargers | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **keyword-typo**: wireles lamp | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **limit-20**: status EQUAL [active] | PASS / PASS | 20 / 20 | 38303.676 / 25.503 | 45005.002 / 38.403 | 63-94 / 101 | n/a | indexed-default |
| **long-views**: serial GREATER_THAN [3004990000] | PASS / PASS | 208 / 208 | 47.905 / 64.207 | 81.408 / 85.409 | 101 / 101 | 1.340x | indexed-default |
| **one-edit-suggestion**: keybord | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **prefix-predictions**: wirel | UNSUPPORTED / UNSUPPORTED | n/a / n/a | n/a | n/a | 0 / 0 | n/a | not timed |
| **projection**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | 829.889 / 792.681 | 912.082 / 1497.541 | 101 / 101 | 0.955x | indexed-default |
| **region-in**: region EQUAL [emea, apac] | PASS / PASS | 40009 / 40009 | 32372.702 / 29753.606 | 35739.226 / 32311.373 | 74-94 / 86-93 | 0.919x | indexed-default |
| **sizes-all**: sizes 9 AND 10 AND category3 | PASS / PASS | 260 / 260 | 2448.441 / 42147.639 | 3437.838 / 46016.128 | 101 / 67-72 | 17.214x | record-first |
| **sizes-either**: size 13 AND region emea | PASS / PASS | 4866 / 4866 | 12911.439 / 78332.451 | 16195.751 / 82923.759 | 101 / 37-39 | 6.067x | indexed-default |
| **stars-between**: quantity BETWEEN 2 and 3 | PASS / PASS | 39960 / 39960 | 35718.874 / 21807.293 | 39957.735 / 25233.822 | 63-80 / 101 | 0.611x | indexed-default |
| **stars-gt**: quantity GREATER_THAN [4] | PASS / PASS | 20122 / 20122 | 18993.721 / 10482.136 | 20726.975 / 11648.886 | 101 / 101 | 0.552x | indexed-default |
| **stars-gte**: quantity GREATER_THAN_EQUAL [4] | PASS / PASS | 39805 / 39805 | 37125.033 / 20318.843 | 41895.534 / 22806.794 | 66-81 / 101 | 0.547x | indexed-default |
| **stars-lt**: quantity LESS_THAN [2] | PASS / PASS | 20235 / 20235 | 17460.031 / 11313.831 | 19744.845 / 12567.292 | 101 / 101 | 0.648x | indexed-default |
| **stars-lte**: quantity LESS_THAN_EQUAL [2] | PASS / PASS | 40131 / 40131 | 35100.306 / 22660.465 | 40921.438 / 24848.844 | 70-82 / 101 | 0.646x | indexed-default |
| **status-not-equal**: status NOT_EQUAL [archived] | PASS / PASS | 80000 / 80000 | 48025.771 / 20680.059 | 61404.328 / 22456.127 | 44-54 / 101 | 0.431x | scalar-rewrite |
| **supplier-known**: supplier known AND Brand7 | PASS / PASS | 1774 / 1774 | 6270.463 / 782.668 | 8668.615 / 2114.384 | 101 / 101 | 0.125x | scalar-rewrite |
| **supplier-null**: supplier missing AND region emea | PASS / PASS | 1980 / 1980 | 3231.708 / 661.967 | 5510.513 / 980.394 | 101 / 101 | 0.205x | indexed-default |
| **tags-all**: tags CONTAINS_ALL [wireless, premium] | PASS / PASS | 1048 / 1048 | 763.580 / 48639.949 | 918.596 / 54859.503 | 101 / 57-63 | 63.700x | direct-in |
| **tags-either**: tags CONTAINS_EITHER [gift, premium] | PASS / PASS | 21951 / 21951 | 25867.745 / 40681.033 | 29905.892 / 45454.483 | 93-101 / 61-71 | 1.573x | direct-in |
| **tags-either-null**: Brand7 AND tags missing or contain eco | PASS / PASS | 391 / 391 | 955.794 / 17293.603 | 1053.495 / 19177.366 | 101 / 101 | 18.093x | record-first |
| **tags-exclude**: active status AND tags exclude clearance | PASS / PASS | 61996 / 61996 | 14241322.827 / 210456.497 | 14406278.851 / 235214.109 | 15 / 15 | 0.015x | set-difference |
| **typed-results**: brand EQUAL [Brand7] | PASS / PASS | 1951 / 1951 | n/a | n/a | 0 / 0 | n/a | not timed |

Diagnostic median timer-pair cost: Kiara 0 ns; VoltDB 100 ns (not subtracted).

### schedule-10000

Fixture SHA-256: `6e5b27872478957ea3152380c0eb705acb26179e3702441d3986a3e6d346575c`.

| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 18 / 18 | 10000.788 / 189.017 | 10881.166 / 252.622 | 101 / 101 | 0.019x | indexed-default |
| **date-between**: city3 AND dates in March | PASS / PASS | 70 / 70 | 812.972 / 32.503 | 920.081 / 51.905 | 101 / 101 | 0.040x | indexed-default |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 33 / 33 | 5.901 / 19.802 | 7.601 / 36.803 | 101 / 101 | 3.356x | indexed-default |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 46 / 46 | 8.201 / 24.102 | 9.000 / 34.903 | 101 / 101 | 2.939x | indexed-default |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 374 / 374 | 156997.258 / 1739.262 | 169878.471 / 2457.930 | 18-22 / 101 | 0.011x | indexed-default |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 93 / 93 | 26.703 / 100.409 | 37.803 / 144.413 | 101 / 101 | 3.760x | direct-in |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 2000 / 2000 | 721.568 / 295.924 | 823.577 / 398.333 | 101 / 101 | 0.410x | indexed-default |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 17 / 17 | 8.901 / 1036.292 | 13.401 / 1151.302 | 101 / 101 | 116.424x | direct-in |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 110 / 110 | 107.609 / 853.076 | 144.413 / 986.488 | 101 / 101 | 7.928x | value-first |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 871 / 871 | 313.627 / 763.268 | 361.132 / 847.676 | 101 / 101 | 2.434x | indexed-default |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 871 / 871 | 354.232 / 13822.888 | 426.039 / 16554.322 | 101 / 101 | 39.022x | direct-in |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 355.932 / 15.702 | 408.936 / 25.703 | 101 / 101 | n/a | indexed-default |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 280 / 280 | 54693.854 / 757.065 | 61407.950 / 939.881 | 44-58 / 101 | 0.014x | indexed-default |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 445 / 445 | 5692.637 / 2584.549 | 6094.374 / 4294.913 | 101 / 101 | 0.454x | indexed-default |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 48 / 48 | 10.501 / 27.402 | 12.201 / 40.704 | 101 / 101 | 2.609x | indexed-default |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 1232 / 1232 | 846.375 / 27691.413 | 931.583 / 31991.204 | 101 / 88-101 | 32.718x | direct-in |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 284 / 284 | 734.465 / 16473.724 | 800.270 / 19618.157 | 101 / 101 | 22.430x | value-first |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 6793 / 6793 | 7068.863 / 18120.603 | 8417.789 / 22717.310 | 101 / 101 | 2.563x | set-difference |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 948 / 948 | 7987.906 / 1492.330 | 8536.462 / 3025.568 | 101 / 101 | 0.187x | indexed-default |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 7 / 7 | 1570.739 / 17.501 | 1875.767 / 25.202 | 101 / 101 | 0.011x | indexed-default |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 23 / 23 | 17.801 / 1019.591 | 26.602 / 1153.099 | 101 / 101 | 57.277x | direct-in |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 53 / 53 | 90.408 / 798.071 | 134.612 / 912.484 | 101 / 101 | 8.827x | indexed-default |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 239 / 239 | n/a | n/a | 0 / 0 | n/a | not timed |

Diagnostic median timer-pair cost: Kiara 0 ns; VoltDB 0 ns (not subtracted).

### schedule-100000

Fixture SHA-256: `c5665efceaaa727ba5404e8fa9482139c56ea1712b6aa59ce3bb4bcd30bf8d03`.

| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| **combined-four-fields**: concert AND city3 AND season July 15 AND capacity 250 | PASS / PASS | 173 / 173 | 391367.601 / 3100.868 | 410398.361 / 5251.264 | 15 / 101 | 0.008x | indexed-default |
| **date-between**: city3 AND dates in March | PASS / PASS | 735 / 735 | 14228.986 / 277.223 | 16164.096 / 349.929 | 101 / 101 | 0.019x | indexed-default |
| **date-equal**: date EQUAL [2026-03-15] | PASS / PASS | 279 / 279 | 57.905 / 88.408 | 86.507 / 134.411 | 101 / 101 | 1.527x | indexed-default |
| **date-gt**: date GREATER_THAN [2026-12-29] | PASS / PASS | 525 / 525 | 147.613 / 138.412 | 194.118 / 193.717 | 101 / 101 | 0.938x | indexed-default |
| **date-range**: season includes July 15 AND tour | PASS / PASS | 4064 / 4064 | 8842598.741 / 45274.854 | 8913340.479 / 49284.213 | 15 / 54-66 | 0.005x | indexed-default |
| **date-range-list**: windows CONTAINS_EITHER [2026-12-25] | PASS / PASS | 1022 / 1022 | 435.140 / 1020.090 | 503.446 / 1148.999 | 101 / 101 | 2.344x | direct-in |
| **date-range-null**: window EQUAL [null] | PASS / PASS | 20000 / 20000 | 23184.347 / 10926.107 | 26758.021 / 12181.988 | 101 / 101 | 0.471x | indexed-default |
| **dates-all**: dates CONTAINS_ALL [2026-04-11, 2026-07-20] | PASS / PASS | 187 / 187 | 250.723 / 15944.815 | 311.428 / 21753.131 | 101 / 101 | 63.595x | direct-in |
| **dates-either**: open date pool[10] AND concert | PASS / PASS | 1083 / 1083 | 2368.209 / 20153.841 | 3408.685 / 23175.658 | 101 / 101 | 8.510x | indexed-default |
| **datetime-range**: timeWindow CONTAINS_EITHER [2026-04-01 09:30:00] | PASS / PASS | 8262 / 8262 | 9152.312 / 22010.062 | 11222.541 / 25666.914 | 101 / 101 | 2.405x | indexed-default |
| **datetime-range-list-all**: timeWindows CONTAINS_ALL [2026-04-01 10:00:00, 2026-04-01 14:00:00] | PASS / PASS | 8262 / 8262 | 7911.097 / 184584.669 | 9644.555 / 215053.820 | 101 / 15-19 | 23.332x | direct-in |
| **limit-20**: category EQUAL [concert] | PASS / PASS | 20 / 20 | 9232.969 / 23.502 | 11937.941 / 34.603 | 101 / 101 | n/a | indexed-default |
| **prefix-range**: age band includes AA21 AND city3 | PASS / PASS | 2836 / 2836 | 1164441.447 / 25186.863 | 1182880.396 / 28749.889 | 15 / 101 | 0.022x | indexed-default |
| **range-all**: band CONTAINS_ALL [100, 900] | PASS / PASS | 4481 / 4481 | 87711.772 / 72776.373 | 105774.405 / 76567.575 | 32-37 / 39-44 | 0.830x | indexed-default |
| **range-equal**: band EQUAL [100-200] | PASS / PASS | 495 / 495 | 156.613 / 167.114 | 195.317 / 251.823 | 101 / 101 | 1.067x | indexed-default |
| **range-list-all**: bands CONTAINS_ALL [20, 170] | PASS / PASS | 12594 / 12594 | 31057.590 / 335998.669 | 35911.667 / 364427.086 | 89-101 / 15 | 10.819x | direct-in |
| **range-list-point**: duration includes 125 AND city3 | PASS / PASS | 2547 / 2547 | 13146.247 / 221462.725 | 15363.262 / 246338.259 | 101 / 15 | 16.846x | indexed-default |
| **range-not**: bands NOT_CONTAINS [125] | PASS / PASS | 68803 / 68803 | 99155.417 / 329452.773 | 112748.714 / 383760.972 | 28-30 / 15 | 3.323x | set-difference |
| **range-point**: capacity includes 250 AND expo | PASS / PASS | 9294 / 9294 | 126636.538 / 42878.105 | 129774.999 / 45675.854 | 23-25 / 56-70 | 0.339x | indexed-default |
| **timestamp-window**: start time June 1, 08:00 inclusive to 12:00 exclusive | PASS / PASS | 77 / 77 | 30256.019 / 40.304 | 37291.375 / 67.706 | 73-101 / 101 | 0.001x | indexed-default |
| **timestamps-all**: timestamps CONTAINS_ALL [2026-02-20 10:00:00, 2026-04-11 10:00:00] | PASS / PASS | 214 / 214 | 257.823 / 18179.713 | 321.427 / 24855.220 | 101 / 101 | 70.512x | direct-in |
| **timestamps-either**: session pool[5] AND city3 | PASS / PASS | 461 / 461 | 1413.423 / 18976.989 | 1830.259 / 23098.459 | 101 / 101 | 13.426x | indexed-default |
| **typed-results**: region EQUAL [venue3] | PASS / PASS | 2577 / 2577 | n/a | n/a | 0 / 0 | n/a | not timed |

Diagnostic median timer-pair cost: Kiara 0 ns; VoltDB 100 ns (not subtracted).

## Publication limits and sources

This experiment supports statements about the specified versions, instrumented execution boundaries,
fixtures and best-tested SQL variants only. It does not establish a universally faster database.
It does not measure transactional write throughput, concurrency, durability, HA, distributed execution,
cold reads, current commercial VoltDB, or client-visible response latency.
Near-unity ratios and intervals overlapping one should not be presented as decisive wins.
This is a standalone harness, not JMH. Repetition on a dedicated host and independent review of the
schema, plans and instrumentation are recommended before making broader claims in a paper.

- [VoltDB procedure design guidance](https://docs.voltdb.com/UsingVoltDB/DesignProc.php)
- [Pinned legacy source: procedure execution-plan inspection](https://github.com/DBOS-project/voltdb/blob/663131f448f184b2be52a1b1a4478a63c476b4c6/src/frontend/org/voltdb/sysprocs/ExplainProc.java)
- [Exact AGPL server artifact and license declaration](https://repo.maven.apache.org/maven2/org/voltdb/voltdb/10.1.1/voltdb-10.1.1.pom)
- [Retail data attribution and preparation](../benchmarks/src/main/resources/README-retail.md)
