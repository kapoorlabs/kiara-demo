# Kiara and SQLite in-memory execution benchmark

Use JDK 21 and Docker Desktop with Linux containers. No SQLite server or separate
SQLite installation is needed: the pinned Xerial JDBC dependency contains native
SQLite. The benchmark module is independent of the demo application's runtime.

From the repository root in PowerShell:

```powershell
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
.\benchmarks\sqlite\run.ps1
```

Set `JAVA_HOME` to the JDK 21 installation before running these commands. The runner
builds the pinned Linux worker image, tunes SQLite, then runs five fresh trials
per engine for each of the five retail/catalog/schedule fixtures. All workers
execute sequentially. Containers have no network, four CPU quota, 8 GiB memory,
and the same 1-4-GiB Java heap. Each connection-local in-memory database disappears
with its worker; no database files or persistent server are left behind.

Outputs:

- [Comparison report](../../docs/sqlite-comparison.md)
- [Raw evidence](../sqlite-comparison.json)
- Per-worker results and platform evidence under `../target/sqlite-execution/`.

Independently reproduce the statistics and audit raw evidence with Node.js (no packages):

```powershell
node .\benchmarks\sqlite\verify.cjs
```

The verifier checks fixture hashes against the historical experiment, the exact
measured executable, SQLite's profile-specific index inventory and key order, frozen plans and
parameters, sample bounds, correctness statuses, percentiles, geometric means,
Java-seeded whole-trial bootstrap intervals, and the generated summary table.

Separate stages:

```powershell
.\benchmarks\sqlite\run.ps1 -Stage Tune
.\benchmarks\sqlite\run.ps1 -Stage Measure
.\benchmarks\sqlite\run.ps1 -Stage Collect
```

Do not rebuild between tuning and measurement. The runner checks the worker-image
identity, and workers verify the fixture/executable/runtime hashes, SQL parameters,
native query plans, VM bytecode, schema, indexes, and ANALYZE statistics.
Missing or inconsistent trials abort collection. Candidate timeouts are explicitly
recorded; errors and incorrect outputs are not converted to wins.

A smaller pipeline check writes only to a separate target directory:

```powershell
.\benchmarks\sqlite\run.ps1 -Datasets catalog-1000,schedule-1000 -Forks 1 -ResultsName sqlite-smoke
```

The full matrix can take hours because Kiara has expensive build and query paths.
The sampling minimum is not waived for those paths. A failed run does not count as
a completed benchmark; fix the cause and rerun affected stages with consistent provenance.

## Scope

SQLite 3.53.4 / Xerial JDBC 3.53.4.0 and Kiara 2.0.16 run in the same pinned JDK 21
Linux image. Storage is `jdbc:sqlite::memory:`, not an empty temporary filename.
Temporary work stays in memory; a 256-MiB suggested page-cache target is not a total
memory limit. SQLite native memory is outside the Java heap but within the container.

The adapter reuses the existing relational encoding and portable SQL compiler:
typed scalar columns, missing sentinels, normalized case-insensitive fields,
paired interval endpoints, and indexed child rows for collections. It does not
send requests to VoltDB. Additional SQLite alternatives use EXISTS/NOT EXISTS,
INTERSECT and EXCEPT. Full ANALYZE occurs after indexes and data are prepared.

Each successful tuning candidate gets 21 samples after at least 10 calls/500 ms
warmup. A progress callback checks a ten-second SQL deadline every 10,000 VM
instructions during tuning only. Callback overhead can influence candidate choice;
it is absent during final measurement. Tuning chooses the lowest median and
freezes that candidate before evaluation, rather than selecting the fastest final trial.
This is workload-specific tuning, not a globally optimal or held-out configuration.
Kiara's existing schema/query formulations are held fixed, not exhaustively tuned.

Measurement defaults match the execution-only experiment: five fresh trials,
at least ten warmup calls and 1,000 ms, 15-101 measured calls with a 3,000-ms
wall acquisition budget. Slow queries can exceed that budget to reach the minimum.

Both engines create ID-only maps or ID/CATEGORY/PRICE maps inside the timer.
SQLite includes prepared execution, all cursor steps, JDBC/JNI conversions,
materialization, and cursor closure. ID extraction and correctness checking are
outside both timers. Kiara condition preparation and SQLite compilation/binding
are outside their timers. No timing-overhead subtraction or count-only shortcut
is used. This is not pure index/native execution or a direct comparison with the
different VoltDB instrumentation boundary.

Known Kiara decimal discrepancies must match the integer-truncation oracle and
are excluded. Limited subsets are checked but not aggregated. Typed objects are
checked but untimed. Text/prediction/spelling modes are explicitly unsupported by
this adapter, not a claim about SQLite FTS capabilities.

Results cannot establish file-backed performance, durability, write throughput,
concurrent behavior, memory-efficiency rankings, or universal superiority.
The report uses equal-query geometric means and exploratory whole-trial bootstrap
intervals; application-frequency-weighted latency may rank engines differently.

## Separate generic-index (untuned) experiment

The tuned report and raw data are not overwritten. Run the approved generic-index
baseline with:

```powershell
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
.\benchmarks\sqlite\run.ps1 -Profile generic
node .\benchmarks\sqlite\verify.cjs --generic
```

Outputs are [the untuned report](../../docs/sqlite-untuned-comparison.md) and
[its raw evidence](../sqlite-untuned-comparison.json), with workers under
`../target/sqlite-generic-execution/`. Do not rebuild after preflight until
measurement and collection finish.

This profile retains 26 indexes (25 explicit plus the automatic primary-key index):
17 generic scalar/covering indexes, four scalar interval-bound indexes, and four
list owner/text/numeric/lower-first interval indexes. It removes all 11 workload-specific
compound indexes and the upper-first list interval index. It uses exactly the original
SQL compiler output, no SQL candidate selection, no explicit ANALYZE, and no PRAGMA optimize.
The normal SQLite planner and automatic indexing remain enabled. The typed schema,
memory/cache settings, prepared statements, eager map shapes, JVM/container limits,
correctness exclusions, and measurement protocol remain the same.

`-Stage Tune` in this profile is a **preflight**, using worker phase `prepare`:
it validates the one predetermined statement and retains its plan/bytecode plus one
diagnostic sample, never selecting among timings. The raw format's `tuning` array
holds those preparation records for compatibility; they are not tuning trials.
There is no SQLite VM progress callback in the generic profile. After the initial
no-deadline preflight exposed extremely slow exclusion plans, the user approved a
**60-second per-invocation wall deadline for both engines**. It covers executions
of timed scenarios during correctness validation, warmup and sampling, not builds,
SQL preparation, the scan oracle or untimed typed-object checks. The watchdog
encloses the adapter call, a slightly wider boundary than the stored execution timer.

A deadline records `QUERY_TIMEOUT`, discards partial samples for that scenario/trial,
atomically checkpoints completed scenarios and exits the isolated worker with code
124. The runner verifies checkpoint progress and starts a fresh worker, rebuilding
the same fixture before resuming the remaining scenarios. It never abandons a query
thread inside a reused database. Incarnation counts and deadline outcomes are retained.
Errors other than the explicitly recognized deadline still abort.

Successful scenarios retain the original warmup and sample minimum; no 60-second
value is substituted for an incomplete latency or result count. A query enters
aggregate ratios only if equivalent usable results exist in **every trial of both
engines**. The report lists timeouts separately; survivor-only aggregates do not
describe all supported queries and must not hide lost coverage. This amendment
does not alter the historical tuned experiment.

On user request after measurement, generic-report total query wins also include
separately labeled **deadline-completion wins**: validated, comparable completed
measurements in every trial for one engine versus timeouts in every trial for the
other. Mixed outcomes earn no deadline win. This post hoc endpoint counts distinct
queries, not trials, and does not change latency ratios or impute timeout latencies.
A timeout during validation/warmup/sampling does not establish a median latency
above the deadline or validate the incomplete result.

For a separate smoke check:

```powershell
.\benchmarks\sqlite\run.ps1 -Profile generic -Datasets catalog-1000,schedule-1000 -Forks 1 -ResultsName sqlite-generic-smoke
```

`-QueryTimeoutMs` allows an explicitly recorded smoke-only deadline override to
exercise checkpoint/restart behavior. The full report uses the default 60000 ms.

Both Kiara and SQLite are rerun in fresh workers. Comparing the generic experiment
with the earlier tuned report is descriptive, not an isolated causal measurement
of index benefits: host state, measurement time, and executable version also differ.
This baseline is **not index-free, SQLite-default, or deliberately scan-only**.

The [wide-filter experiment](../wide/README.md) separately exercises 2-16 distinct
filtered attributes with controlled expected selectivity. Its tuned profile adds
one quantity-first covering index for the new workload; the generic inventory
remains 26 indexes. Use its own verifier and reports, not the historical fixture
counts expected by this directory's verifier.
