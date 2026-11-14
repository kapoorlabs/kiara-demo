# Wide-filter comparison

This is a separate, opt-in experiment across Kiara vs tuned VoltDB CE, tuned
SQLite, generic-index SQLite and Lucene. Existing reports, fixtures and default
commands are unchanged. JDK 21, Node.js and Linux Docker Desktop are required.
Pinned versions: Kiara 2.0.16, Lucene 10.5.2, SQLite 3.53.4 / Xerial 3.53.4.0,
and AGPL VoltDB CE 10.1.1.

```powershell
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
.\benchmarks\wide\run.ps1 -Smoke
.\benchmarks\wide\run.ps1
```

Set `JAVA_HOME` first. Do not rebuild during the experiment. The same executable
is used throughout; an archive is retained under `benchmarks\target\wide`.
The runner executes comparisons sequentially, not in parallel. The VoltDB stage
uses its own Compose project and loopback port 21212, which must be free.
It never stops another project's database.

Full runs use `wide-10000` and `wide-100000`, five fresh trials per engine and
comparison, and 39 queries per fixture. Each worker has four CPU quota, 8 GiB
container memory and a 1-4 GiB Java heap. Smoke uses `wide-1000` and one trial,
writing only under target. Smoke measurements are not publication evidence.

Stages can be resumed with `-Stage VoltDb`, `SqliteTuned`, `SqliteGeneric`,
`Lucene` or `Collect`. These are comparison-level stages: they rerun that entire
comparison. Do not combine old stages after rebuilding. Incomplete runs must not
be presented as completed comparisons.

## Filters and selectivity

- Distinct attributes per query: **2, 4, 8, 12, 16**.
- Scalar controls: 2, 4, 8; the existing applicable scalar fields do not support
  12/16 without changing schema or adding identifier/decimal/enum/boolean probes.
- Mixed `CONTAINS_EITHER` and `CONTAINS_ALL`: all five widths, two distinct
  requested values per collection. Predicates on different attributes use AND.
- Expected selectivity: **0.1%, 1%, 10%**. Actual scan-oracle counts are reported.
- Seed: `20261029`. Other scalars accept 95%; collections have four states:
  both requested values (90%), only first (2.5%), only second (2.5%), neither (5%).
  Intervals have corresponding point-containment states.
- The quantity selector adjusts its cutoff for the expected intersection of
  independent attribute draws. One matching anchor avoids empty-result artifacts.
  At 1,000-row smoke size the anchor's 1/N bias is material.

This is controlled low-cardinality stress data, not a representative real-world
correlation model. Selectivity is approximately controlled, not forced exactly.
Wider queries add genuine broad predicates and list joins; they do not prove
that filter width alone causes any observed difference.

## Tuning and timing

Tuned SQLite adds a quantity-first wide scalar covering index, performs ANALYZE,
and freezes the fastest validated SQL candidate before measurement. Generic
SQLite retains exactly 26 indexes, original SQL and no ANALYZE/selection.
Tuned VoltDB adds the same covering index and tests indexed set intersections
and explicit record-first/value-first join ordering. Joins remain candidates
through four EITHER collection fields or two ALL collection fields; above those
bounds only intersections are attempted. The widest join and a five-table ALL
probe exceeded the two-minute compilation timeout before query timing. Five-table
EITHER joins compiled and validated and remain in the search. The wide client now
allows ten minutes per request for larger batched catalog setup; the server's SQL
execution-timeout setting is unchanged. The retained search bounds are a practical
tuning restriction, not proof that excluded plans can never compile or a query loss. Procedure
declarations are batched to avoid repeatedly recompiling the growing catalog.
Identical SQL/join-order/parameter-type templates are compiled once and reused
with each query's actual bound values; the three selectivity targets do not
require three copies of the same procedure.
Neither tuning search proves globally optimal plans.

Kiara/Lucene/SQLite time execution plus eager Java ID maps; preparation,
subsequent ID extraction, oracle validation, and all builds are outside timing.
Lucene uses compiled Boolean filters, constant score, document-order retrieval,
stored IDs and no query cache; search-time rewriting is included. This differs
from the historical Lucene end-to-end harness. SQLite includes JDBC/JNI and
cursor closure. VoltDB retains server-side execution/VoltTable timing, excluding
transport; its representation and boundary differ. Do not rank opponents across
the four separate pairwise comparisons.

Warmup: at least ten calls and one second. Sampling: 15-101 calls with a
three-second wall acquisition budget; the minimum may exceed the budget.
Generic SQLite retains the approved 60-second symmetric per-invocation deadline,
checkpoint/restart handling and separate deadline wins. Other comparisons retain
their existing deadline/error policies, disclosed in the generated report.
Correctness or unexpected runtime failures abort; incomplete results are never
successful timing samples.

Outputs:

- [Full report](../../docs/wide-filter-comparison.md), only after completion.
- [Raw evidence](../wide-filter-comparison.json), including exact conditions,
  actual counts, executable hashes, platform evidence, tuning plans and samples.
- Component worker results under `benchmarks\target\wide-*`.

```powershell
node .\benchmarks\wide\verify.cjs
```

The independent verifier checks actual widths, requested collection values,
sample bounds/percentiles, all-trial eligibility and deadline wins, geometric
ratios, seeded bootstrap intervals, report rows, executable identity and
profile-specific tuning/index evidence. This shared-host experiment does not
measure disk-backed reads, writes, build efficiency, memory rankings, durability,
concurrency or current commercial VoltDB.
