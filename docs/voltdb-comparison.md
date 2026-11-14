# **Kiara DB and VoltDB CE: benchmark findings and interpretation**

**Conclusion:** Neither database is uniformly faster. Query structure, data distribution,
result size, and physical design matter more than a single ranking. Kiara performs strongly
on many positive collection-membership queries; tuned VoltDB performs strongly on several
scalar conjunctions, bounded timestamp searches, and exclusion queries.

This analysis covers **Kiara 2.0.16 and AGPL VoltDB Community Edition 10.1.1 (2020)**.
It must not be attributed to current commercial VoltDB.

- [Full execution-only report and scenario tables](voltdb-query-execution.md)
- [Raw execution trials, tuning candidates, plans, sources, and index inventories](../benchmarks/voltdb-query-execution.json)
- [Setup and reproduction](../benchmarks/voltdb/README.md)
- [Historical end-to-end report](voltdb-end-to-end.md)
- [Historical end-to-end raw results](../benchmarks/voltdb-comparison.json)

## What was measured

| Boundary | Kiara | VoltDB |
| --- | --- | --- |
| Timed operation | Search API | Server-side SQL queue/execute inside a precompiled procedure |
| Included | Query evaluation and eager result-map creation | SQL execution and native/Java VoltTable creation |
| Excluded | Adapter preparation, subsequent ID extraction, correctness checks | Transport, dispatch before the procedure, client decoding, correctness checks |

Index creation and data loading are outside the timed region. All matching results are
produced; this is not a count-only shortcut. Result representations differ, so this measures
execution with materialization, **not pure index traversal, CPU time, equal encoding cost,
client-visible latency, or transaction throughput**.

Both execution environments use Linux containers on the same Docker host. Kiara uses
Java 21; the legacy VoltDB server uses Java 11. Each container has a four-CPU quota and
an 8-GiB limit, with a 4-GiB maximum Java heap. Each measured trial uses a fresh worker,
and VoltDB is restarted for each VoltDB trial.

## Aggregate results

For each query, take the median of the five trial p50s separately for each engine, then
calculate VoltDB time divided by Kiara time. The dataset ratio is the unweighted geometric
mean of the eligible per-query ratios. Above one favors Kiara; below one favors VoltDB.

| Dataset | Equivalent queries | VoltDB / Kiara | Exploratory 95% interval | Lower per-query medians: Kiara / VoltDB |
| --- | ---: | ---: | --- | ---: |
| Retail, 3,788 rows | 5 | 10.949 | 8.977-12.457 | 5 / 0 |
| Catalog, 10,000 rows | 21 | 0.988 | 0.885-1.127 | 9 / 12 |
| Catalog, 100,000 rows | 21 | 0.801 | 0.705-0.859 | 7 / 14 |
| Schedule, 10,000 rows | 21 | 1.324 | 1.232-1.468 | 13 / 8 |
| Schedule, 100,000 rows | 21 | 0.923 | 0.850-0.987 | 12 / 9 |

The small catalog is effectively inconclusive on this aggregate. The large catalog favors
VoltDB by approximately 19.9% lower geometrically aggregated time. The small schedule favors
Kiara by approximately 24.5% lower time, not 32.4% lower: the latter confuses the denominators.
The large schedule gives VoltDB a modest 7.7% advantage on the aggregate.

These are not arithmetic average latencies, total batch times, or throughput estimates.
The 89 eligible dataset/query combinations have 46 lower Kiara medians and 43 lower VoltDB
medians; that count is not a universal ranking either.

## Retail: substantial advantages over a narrow query set

All five equivalent retail queries favor Kiara.

| Query | Matches | Kiara p50, us | VoltDB p50, us | VoltDB / Kiara |
| --- | ---: | ---: | ---: | ---: |
| Markets contain all requested values | 172 | 53.606 | 1782.593 | 33.254 |
| Markets contain either requested value | 706 | 163.617 | 596.664 | 3.647 |
| Markets exclude requested value | 6 | 222.524 | 5606.905 | 25.197 |
| Projection | 215 | 56.306 | 452.948 | 8.044 |
| Units sold >= threshold | 18 | 3.000 | 19.202 | 6.401 |

Several predicates map naturally to Kiara's indexed collection operations. The indexed SQL
alternatives still require grouping, deduplication, or set combination. An index does not
eliminate those costs. Conversely, small final cardinality does not imply little input work:
the exclusion query returns only six rows.

The overall 10.949 ratio is meaningful for these five queries, but three are collection
operations. It does not establish general small-dataset superiority. Absolute time matters:
the units query saves approximately 16 microseconds, not milliseconds.

## Catalog: an aggregate near one hides opposing strengths

At 10,000 records, the 0.988 aggregate conceals approximately 44.7-fold lower Kiara execution
time on `tags-all` and approximately 83.8-fold lower VoltDB execution time on `tags-exclude`.
The three-field conjunction also favors VoltDB. A near-tie does not mean similar behavior
for each query.

Selected 100,000-record results:

| Query | Kiara p50, ms | VoltDB p50, ms | Lower execution time |
| --- | ---: | ---: | --- |
| Brand equality | 0.549 | 0.553 | Essentially tied |
| Status AND region AND brand | 6.544 | 0.132 | VoltDB, approximately 49.7-fold |
| Tags contain both values | 0.764 | 48.640 | Kiara, approximately 63.7-fold |
| Sizes contain both values AND category | 2.448 | 42.148 | Kiara, approximately 17.2-fold |
| Status excludes archived | 48.026 | 20.680 | VoltDB, approximately 2.3-fold |
| Active status AND tags exclude clearance | 14241.323 | 210.456 | VoltDB, approximately 67.7-fold |

The actual conjunction plan seeks through the compound index on
`brand, status, region, id`. Kiara obtains qualifying node collections and combines them,
which can require more intermediate work than the compound-index path.

The tag-exclusion result is not a typo: Kiara's trial p50s range from approximately
13.415 to 17.661 seconds, versus 192.287 to 235.580 milliseconds for VoltDB.
VoltDB uses indexed set difference: active record IDs minus IDs with the excluded tag.
Kiara builds exclusion keys, enumerates eligible collection groups, and combines the result
with the other condition. This is consistent with the measured cost, but no CPU profile
establishes the exact bottleneck.

The retail exclusion favors Kiara while this catalog exclusion favors VoltDB. Therefore
"exclusion queries always favor one engine" would be an unsupported generalization.

## Schedule: scalar windows are not collection membership

At 10,000 records, Kiara has the lower aggregate, driven in part by date/time-list membership.
At 100,000 records, the aggregate modestly favors VoltDB, despite 12 of 21 queries favoring Kiara.

| Query, 100,000 records | Kiara p50, ms | VoltDB p50, ms | Lower execution time |
| --- | ---: | ---: | --- |
| Date list contains both dates | 0.251 | 15.945 | Kiara, approximately 63.6-fold |
| Timestamp list contains both timestamps | 0.258 | 18.180 | Kiara, approximately 70.5-fold |
| Duration interval contains point AND city | 13.146 | 221.463 | Kiara, approximately 16.8-fold |
| Four-field conjunction | 391.368 | 3.101 | VoltDB, approximately 126.2-fold |
| Season contains date AND event type | 8842.599 | 45.275 | VoltDB, approximately 195.3-fold |
| Scalar timestamp within four-hour window | 30.256 | 0.040304 | VoltDB, approximately 750.7-fold |

For timestamp-list membership, VoltDB uses indexed child rows plus grouping and conditional
aggregation. Kiara uses indexed value matches and intersections.

For the scalar timestamp window, VoltDB combines the lower and upper bounds into one bounded
index scan. Kiara's tested formulation evaluates two separate same-column conditions and
intersects their candidate collections. Each condition can match many records although the
intersection contains only 77. Small output is not evidence of small intermediate work.

Kiara also has a `BETWEEN` execution path. A correctness-preserving reformulation might
improve this case, but that alternative was not measured. The approximately 750.7 ratio is
specific to the tested formulation, not proof of an inherent disadvantage for all timestamp
queries. Similarly, the date-range result repeats across trials: Kiara p50s span approximately
8.193-11.224 seconds, versus 44.203-46.356 milliseconds for VoltDB.

## Index verification and tuning

The rerun verifies 37 indexes, actual native plans, read-only single-partition procedures,
and frozen-plan consistency. Of 184 tuning candidates, 183 completed and one explicit SQL
timeout was retained as a failed, ineligible candidate. Other errors and correctness failures
abort rather than producing successful-looking samples.

An index is not automatically faster than a scan. A broad region predicate returns about
40% of the catalog; an indexed `IN` rewrite was slower in the diagnostic comparison than the
scan formulation. The status exclusion returns 80% of records. These are not evidence that
index creation was forgotten.

The configuration is **best among the documented candidates**, not provably globally optimal.
The physical design and tuning are workload-specific, not a held-out generalization study.

## Statistics, workload sensitivity, and confidence

The aggregate considers the magnitude of each ratio, not merely win counts. It also gives
each eligible query equal weight, regardless of application frequency. An expensive query
can dominate real application latency even when a geometric mean looks attractive.

A post hoc sensitivity check removes the minimum and maximum per-query ratios and
recomputes the aggregate:

| Dataset | Primary ratio | Without lowest/highest ratios |
| --- | ---: | ---: |
| Retail, 3,788 | 10.949 | 10.907 |
| Catalog, 10,000 | 0.988 | 1.020 |
| Catalog, 100,000 | 0.801 | 0.785 |
| Schedule, 10,000 | 1.324 | 1.345 |
| Schedule, 100,000 | 0.923 | 1.037 |

**This is not a corrected result or a reason to discard those queries.** The large schedule's
ranking reversal illustrates dependence on workload composition. Bootstrap intervals hold
the workload fixed and measure trial variability; they do not describe uncertainty over
other query mixes. Thus the reversal does not contradict the primary interval.

The complete run has 50 measured workers, 1,010 timed scenario/trial entries, and 93,407
execution samples. Every scored query passes the independent oracle. Forty recognized
Kiara fractional-number discrepancies are excluded, as are differing valid limited subsets.
Text modes are unsupported by this adapter; typed-object reconstruction is correctness-checked
but untimed.

Five trials per engine/dataset remain only five trials, not tens of thousands of independent
experiments. The intervals use 5,000 whole-trial bootstrap resamples with a fixed seed and
are exploratory. Expensive queries have as few as 15 calls per trial; their nearest-rank p95
is the maximum sample, not strong evidence about production tails. Zero observed timer-pair
durations do not establish zero clock overhead; no overhead subtraction was applied.

## Why the historical results differ

The historical comparison includes client/server communication. The rerun changes the timing
boundary, SQL/procedure preparation, indexes and SQL alternatives, runtime placement, and
which output representations are timed. The entire difference cannot be attributed to network
removal or one index. A causal estimate would require changing only one factor at a time.
The historical report and raw data remain available separately and have not been replaced
with execution-only numbers.

## Publication scope

These results concern the specified versions, fixed fixtures, query formulations, tested
indexes, and timing boundaries. Java-version differences, unequal result representations,
virtualization, shared-host scheduling, small trial count, and workload-specific tuning remain
limitations. No CPU profile proves the proposed internal bottlenecks.

This does not measure writes, concurrency, durability, availability, distributed scaling,
cold reads, current commercial VoltDB, or production client-visible latency.

Suggested paper wording:

> Under the evaluated warm, single-partition execution configuration, neither Kiara DB nor
> VoltDB CE consistently achieved lower execution time across all workloads. Kiara performed
> strongly on several positive collection-membership queries, while tuned VoltDB performed
> strongly on several scalar conjunctions, bounded timestamp queries, and exclusion workloads.
> Aggregate rankings depended on dataset size and query composition and should not be
> interpreted as universal database-performance rankings.
