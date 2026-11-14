# VoltDB Community Edition comparison

## Execution-only experiment

The [query-execution report](../../docs/voltdb-query-execution.md) and
[raw execution evidence](../voltdb-query-execution.json) are a **separate
experiment**, not replacements for the end-to-end timings below. They compare
Kiara's search API with timers inside precompiled, read-only, single-partition
VoltDB procedures. Eager ID/projection result creation is included; transport,
client decoding, adapter preparation and index creation are excluded. Java map
results and VoltTable results still have different representation costs.

The execution experiment runs both workers under pinned Linux JDK 21 containers;
the actual legacy VoltDB server uses its pinned Java 11 runtime. Each container
has a four-CPU quota and an 8-GiB limit; each JVM has a 4-GiB maximum heap.
The server is restarted before each measured VoltDB trial. Benchmark data is
replicated; a partitioned routing table routes the read-only procedure to one
site with a local copy of the full fixture. This is a favorable local-query
configuration, not evidence of distributed scalability.

When inspecting this legacy server's `@SystemCatalog INDEXINFO`, do not infer
composite key order from `ORDINAL_POSITION`: these are catalog-relative
positions, observed in alphabetical column order. Use the executed additional
index DDL in the raw evidence, the baseline
[adapter DDL](../src/main/java/com/kapoorlabs/benchmark/VoltDbEngine.java), and
native execution plans for key order. The inventory still verifies index names
and column membership.

Tuning evaluates composite indexes and several SQL/join-order alternatives.
Every eligible candidate must match the oracle. A tuning-only query timeout
is recorded explicitly as a failed candidate with no usable timings and is
excluded from selection; all other errors and correctness mismatches abort.
Final measurement errors always abort. The harness saves procedure source, native
`@ExplainProc` plans, server catalog/index inventories, timing samples and the
chosen variant. Catalog checks require every procedure to be read-only and
single-partition. The full measurement phase uses the frozen winners on fresh
processes and rejects changed plans. This is **best among tested alternatives**,
not proof of an optimal physical design.

From the repository root, with JDK 21 in `JAVA_HOME`:

```powershell
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
.\benchmarks\voltdb\run-execution.ps1
```

The default is five trials per engine/dataset (50 measurement workers), plus
five tuning workers. Warmup is at least 10 calls / 1 second; sampling is at
least 15 calls, at most 101, with a three-second acquisition budget. Slow
queries can run beyond the budget to meet the minimum. **Allow substantial
time**, particularly for Kiara's large index builds and exclusion queries.
The script removes only this project's containers/network in a `finally` block,
including after failures. It does not stop Docker Desktop.

Stages can be run separately:

```powershell
.\benchmarks\voltdb\run-execution.ps1 -Stage Tune
# Inspect benchmarks\target\voltdb-execution\tune-*.json before proceeding.
.\benchmarks\voltdb\run-execution.ps1 -Stage Measure
.\benchmarks\voltdb\run-execution.ps1 -Stage Collect
```

Do not rebuild or change the executable between tuning and measurement: the
harness checks its SHA-256. Trial files and platform metadata live under
`benchmarks\target\voltdb-execution`; retain them until collection succeeds.
The final raw JSON embeds all trials and tuning evidence. The historical
end-to-end report/raw data are not overwritten.

`-ResultsName` selects a separate target directory and keeps its collected report/raw
inside that directory. `-ProjectName` selects an isolated Compose project; port 21212
must still be free. These options support the [wide-filter experiment](../wide/README.md)
without replacing the historical execution-only report.

For a pipeline smoke check, use `-Datasets retail-3788,catalog-1000,schedule-1000
-Forks 1`. These smaller outputs are not the published full matrix.

The server timer returns nondeterministic clock data from read-only benchmark
procedures with k-safety zero. It does not change the server implementation or
license checks and must not be used as a production replicated procedure.
Back-to-back clock measurements are retained separately, never subtracted.
Do not substitute the client's millisecond cluster-roundtrip estimate for
this nanosecond execution timer.

## Generic-index execution baseline

The separate untuned baseline retains the adapter's generic indexes and required
routing primary key, but removes workload-specific compound, upper-first interval
and wide covering indexes. The verified native catalog has 27 indexes: 17 scalar,
four scalar-bound and four collection indexes, plus the records and routing
primary-key indexes. Catalog identifier comparisons are case-insensitive.
One fixed SQL translation is used per query: the
original compiler for historical fixtures and shared set intersections for wide
filters (original compiler when intersections are inapplicable). It does not
measure/select SQL alternatives or impose join orders. Preflight validates results
and freezes plans without timing samples. Single-partition procedures, replicated
data, CPU/memory limits and the execution-only timing boundary remain configured.

After completing and verifying any ongoing experiment, rebuild once, then run:

```powershell
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
.\benchmarks\voltdb\run-execution.ps1 -Profile generic `
  -Datasets retail-3788,catalog-10000,catalog-100000,schedule-10000,schedule-100000,wide-10000,wide-100000
```

This is 70 fresh measurement workers (seven datasets, five trials, two engines).
Outputs are `docs\voltdb-untuned-comparison.md` and
`benchmarks\voltdb-untuned-comparison.json`; tuned evidence is not overwritten.
The same stage options apply; `-Stage Tune -Profile generic` performs correctness/
plan preflight, not tuning. Native server SQL timeouts are explicit unmeasured
QUERY_TIMEOUT outcomes, with partial samples discarded. Other errors abort.
There is no symmetric Kiara native-server deadline, so no deadline-derived speedup
or symmetric deadline-win claim is made. Runtime errors never become successful
timings. Archived executable, native plans, inventories and samples are retained.

```powershell
node .\benchmarks\voltdb\verify-generic.cjs
```

## Historical end-to-end experiment

This harness compares Kiara 2.0.16 with **VoltDB Community Edition 10.1.1 (2020)**.
It reuses the Lucene suite's five dataset entries and independent correctness
oracle, but produces separate results. It does **not** benchmark the current
commercial VoltDB release.

## Why this version?

The current official developer image requires a license key. The user explicitly
requested Community Edition instead. The old community documentation URL
currently fails TLS hostname validation from this environment; the setup does
not disable certificate verification.

The server JAR is the AGPL-licensed artifact published to Maven Central:

- [Server artifact and license declaration](https://repo.maven.apache.org/maven2/org/voltdb/voltdb/10.1.1/voltdb-10.1.1.pom)
- [Matching client](https://repo.maven.apache.org/maven2/org/voltdb/voltdbclient/10.1.1/)
- [Archived 10.1-era upstream dependency snapshot](https://github.com/DBOS-project/voltdb/tree/663131f448f184b2be52a1b1a4478a63c476b4c6)

The Dockerfile pins the Java 11 base image and validates the server JAR's SHA-256.
It downloads bundled runtime libraries from the pinned source snapshot.
Reload4j 1.2.26 supplies the Log4j 1.x API instead of the archived Log4j JAR.
The server itself is unmodified. Keep the upstream license notices in downloaded
artifacts and observe their terms if redistributing the image.

**Local testing only:** this is legacy software with legacy dependencies, not a
production deployment recommendation. Only the client port is published, bound
to `127.0.0.1`. HTTP administration is disabled; the container has no host mounts
or production credentials. Do not expose it to other machines.

## Start the isolated database

Requirements: Docker Desktop with the Linux engine running, a free loopback
port 21212, and JDK 21 for the benchmark. The server uses Java 11 inside Docker.
The configured container has a four-CPU quota, an 8-GiB memory limit and four
execution sites; its Java heap is capped at 4 GiB.

From the repository root in PowerShell:

```powershell
docker compose -p kiara-voltdb-benchmark -f benchmarks\voltdb\compose.yaml up --build -d
docker compose -p kiara-voltdb-benchmark -f benchmarks\voltdb\compose.yaml logs --tail 30
```

Verify readiness with the integration tests, not just container status:

```powershell
$env:VOLTDB_INTEGRATION = 'true'
.\mvnw.cmd -B -ntp -f benchmarks\pom.xml verify
Remove-Item Env:\VOLTDB_INTEGRATION
```

The native 10.1.1 client needs access to Java's selector internals on JDK 21.
The test runner and benchmark worker launcher set
`--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Direct worker invocations must
include it too.

## Run the comparison

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar `
  com.kapoorlabs.benchmark.VoltDbBenchmark
```

Defaults match the Lucene sampling configuration: five datasets, three fresh
worker JVMs per engine/dataset, at most 61 samples per query, at least five
samples, a 50-ms minimum warmup and a 1,000-ms measurement budget.

Outputs:

- [Historical end-to-end report](../../docs/voltdb-end-to-end.md)
- [Execution-only findings and interpretation](../../docs/voltdb-comparison.md)
- [Raw results, SQL templates, parameters and samples](../voltdb-comparison.json)

For a smaller smoke run:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar `
  com.kapoorlabs.benchmark.VoltDbBenchmark `
  --datasets retail-3788,catalog-1000,schedule-1000 --forks 1 --samples 5 `
  --warmup-ms 10 --budget-ms 100 `
  --output benchmarks\target\voltdb-smoke.json --report benchmarks\target\voltdb-smoke.md
```

The harness connects only to its dedicated local database at port 21212.
Do not point a port-forward at a production instance: each worker creates and
drops its own generated tables and indexes. It never drops arbitrary existing
tables. If a worker is interrupted, its temporary tables may remain in this
disposable container. Restart with `down` followed by `up -d` to get clean state.
The harness prints and preserves partial worker files on failure.

The native bulk loader batches record and list-value inserts. SQL uses
parameterized `@AdHoc` calls and explicitly created owner/text/number/range
indexes. Endpoint pairs are retained within a single interval row.

Keyword retrieval, stemming, prefix predictions and spelling suggestions are
marked `UNSUPPORTED` for **both** engines in this comparison. They are not
silently simulated using Java record scans. Limited-result subsets and known
Kiara decimal mismatches are validated but excluded from equivalent-query
ratios. SQL decimal probes must pass the independent oracle.

## Interpretation

This measures single-client, end-to-end synchronous reads:

- Kiara executes inside the worker JVM.
- VoltDB executes through the native client, Docker networking and the server.
- Every matching row is transferred and materialized.

It is not a pure index-algorithm comparison or an expert-tuned VoltDB design.
The typed scalar/list schema, replicated tables, multi-predicate SQL, normal
plan caching, stored JSON and legacy server version all affect results.
Single-partition stored procedures, partitioned domain-specific schemas and
asynchronous transactional workloads could behave differently.

The same database process stays running between workers, with new tables each
time. Server/OS caches can remain warm. Build time includes the different loading
and preparation paths, not incremental write throughput or cold startup.

There is deliberately no heap comparison across the embedded/client-server
boundary. Raw heap fields are `null`, not zero-valued measurements.

## Cleanup

Once finished:

```powershell
docker compose -p kiara-voltdb-benchmark -f benchmarks\voltdb\compose.yaml down
```

This removes only this Compose project's disposable container and network.
No database volume or host data directory is created.
