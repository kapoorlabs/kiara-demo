package com.kapoorlabs.benchmark;

import java.util.*;
import com.kapoorlabs.benchmark.QueryExecution.Observation;
import static com.kapoorlabs.benchmark.VoltExecutionBenchmark.*;

final class VoltExecutionReport {
    static String renderGeneric(Results results) {
        StringBuilder out = new StringBuilder("""
                # **Kiara DB versus generic-index VoltDB CE: query-execution benchmark**

                This separate baseline compares Kiara 2.0.16 with legacy AGPL VoltDB CE 10.1.1.
                "Untuned" means **generic indexes retained**, not an index-free database.
                Historical tuned and end-to-end evidence is preserved.

                [Raw evidence](../benchmarks/voltdb-untuned-comparison.json) |
                [Reproduction](../benchmarks/voltdb/README.md)

                ## Physical design and fixed SQL

                - Retain the original primary-key, scalar, scalar-bound, list owner, text, number and
                  lower-first interval indexes. Retain the required routing primary key.
                - Remove all nine workload-specific record compound indexes, the upper-first list
                  interval index, and the additional wide scalar covering index.
                - Use exactly one predetermined SQL translation per scenario, without explicit join
                  order or measured candidate selection. Historical queries use the original adapter
                  compiler. Wide queries use the shared set-intersection translation, falling back to
                  the original compiler when no intersection is applicable. This fixed translation
                  is **not necessarily the same SQL as generic SQLite** and is not selected by timing.
                - Preflight compiles procedures, checks results and records plans; it gathers no timing
                  samples. Final plans must match preflight after generated-name normalization.
                  Parameterized wide templates are reused with each scenario's actual arguments.
                - Precompiled read-only single-partition procedures, replicated benchmark data and a
                  partitioned routing table are retained. These deliberately favorable execution
                  choices remain configured; "untuned" does not mean all server settings are defaults.

                ## Timing, protocol and exclusions

                Kiara measures query execution plus eager Java result maps. VoltDB measures server-side
                voltQueueSQL/voltExecuteSQL and VoltTable creation, excluding transport and client decoding.
                Output representations and timing boundaries differ. Builds, SQL compilation, validation
                and adapter preparation are excluded. No timer overhead or estimated transport is subtracted.
                Both engines are freshly measured; no historical Kiara samples are reused.

                Each worker has four CPU quota, 8 GiB memory and a 1-4 GiB Java heap. Workers use JDK 21;
                legacy VoltDB uses JDK 11. Server restart precedes every VoltDB trial; engine order alternates,
                with seeded scenario shuffles. Warmup is at least ten calls and one second; measurement
                uses 15-101 calls and a three-second acquisition budget that the minimum may exceed.
                Five trials per engine are the full protocol; one-trial smoke runs are not publication results.
                Original retail/catalog/schedule fixtures and separate wide 2/4/8/12/16-field filters are
                included according to the dataset inventory below. Wide EITHER/ALL collection predicates
                request two distinct values, targeting 0.1%, 1% and 10% selectivity. Width is not isolated
                from data type, join count or predicate mix; scalar-only controls stop at eight attributes.

                The unchanged native VoltDB SQL timeout can interrupt correctness checks, warmup or sampling.
                Such outcomes are QUERY_TIMEOUT with no matches or usable latency; partial samples are
                discarded. Client/catalog timeouts and other errors abort, rather than becoming query losses.
                Kiara does not have an equivalent native server timeout, so **no symmetric deadline-win
                endpoint or timeout-derived speedup is claimed**. Mixed or unanimous query timeouts exclude
                the scenario from aggregate latency ratios. Preflight timeouts are recorded but are not
                measured trial outcomes. Decimal discrepancies, unsupported text modes, typed-only results
                and limited non-equivalent subsets remain disclosed and excluded as in the tuned experiment.

                ## Results

                V/K above one favors Kiara; below one favors VoltDB. Ratios use medians of trial p50s,
                then an equal-query geometric mean over scenarios completed comparably in every trial.
                The exploratory 95% interval resamples whole trials 5,000 times with the fixed Java seed;
                it is not a generalization guarantee. Near-unity differences are not decisive evidence.

                | Dataset | Trials per engine | Equivalent queries | V/K aggregate ratio | Exploratory 95% interval | Lower median K / V | VoltDB timeout entries |
                | --- | ---: | ---: | ---: | --- | ---: | ---: |
                """);
        for (String dataset : datasets(results)) {
            List<Trial> k = trials(results, dataset, "kiara"), v = trials(results, dataset, "voltdb");
            List<String> names = eligible(k, v);
            long timeouts = v.stream().flatMap(t -> t.observations().stream())
                    .filter(o -> o.query().validation().status().equals("QUERY_TIMEOUT")).count();
            long lowerK = names.stream().filter(n -> metric(k, n, false) < metric(v, n, false)).count();
            long lowerV = names.stream().filter(n -> metric(v, n, false) < metric(k, n, false)).count();
            double[] interval = names.isEmpty() ? null : bootstrap(k, v, names);
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %s | %d / %d | %d |%n",
                    dataset, k.size(), names.size(),
                    names.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.3fx", ratio(k, v, names)),
                    interval == null ? "n/a" : String.format(Locale.ROOT, "%.3f-%.3fx", interval[0], interval[1]),
                    lowerK, lowerV, timeouts));
        }
        out.append("\nGenerated: ").append(results.generatedAt()).append(".\n\n## Per-query evidence\n\n")
                .append("Times are microseconds, medians of trial p50s/p95s, not pooled percentiles. ")
                .append("Any timeout makes the paired latency display n/a; see the raw per-trial outcomes.\n\n");
        for (String dataset : datasets(results)) {
            List<Trial> k = trials(results, dataset, "kiara"), v = trials(results, dataset, "voltdb");
            Set<String> comparable = new HashSet<>(eligible(k, v));
            out.append("### ").append(dataset).append("\n\nFixture SHA-256: `")
                    .append(k.getFirst().dataSha256()).append("`.\n\n")
                    .append("| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |\n")
                    .append("| --- | --- | ---: | ---: | ---: |\n");
            for (Observation observation : k.getFirst().observations()) {
                String name = observation.query().scenario();
                String ks = String.join(", ", k.stream().map(t -> observation(t, name).query().validation().status()).distinct().toList());
                String vs = String.join(", ", v.stream().map(t -> observation(t, name).query().validation().status()).distinct().toList());
                out.append(String.format(Locale.ROOT, "| %s | %s / %s | %s | %s | %s |%n", name, ks, vs,
                        pair(k, v, name, false), pair(k, v, name, true),
                        comparable.contains(name) ? String.format(Locale.ROOT, "%.3fx", metric(v, name, false) / metric(k, name, false)) : "n/a"));
            }
        }
        return out.append("""

                ## Interpretation limits

                Results describe the specified fixtures, versions, generic physical design and adapters,
                not universal database superiority. The baseline measures warm single-reader queries, not
                writes, indexing efficiency, disk-backed reads, durability, concurrency, HA or distributed
                scalability. The shared host and legacy JDK difference are confounders. Repeat on dedicated
                hardware and review native plans before broader publication claims. Incomplete runs are not
                completed comparisons; smoke measurements must not be cited as full-size results.
                """).toString();
    }

    static String render(Results results) {
        StringBuilder out = new StringBuilder("""
                # Kiara DB and VoltDB CE: instrumented query-execution comparison

                **Scope:** Kiara 2.0.16 versus AGPL VoltDB Community Edition 10.1.1 (2020).
                This is not a comparison with current commercial VoltDB. It measures synchronous,
                warm, single-partition read execution, not client-visible latency or transaction throughput.

                [Raw trials, tuning alternatives, procedure sources, plans and index catalogs](../benchmarks/voltdb-query-execution.json) |
                [Reproduction](../benchmarks/voltdb/README.md) |
                [Execution harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/VoltExecutionBenchmark.java)

                """);
        out.append("Generated: ").append(results.generatedAt()).append(".\n\n");
        out.append("""
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
                """);
        for (String dataset : datasets(results)) {
            List<Trial> k = trials(results, dataset, "kiara");
            List<Trial> v = trials(results, dataset, "voltdb");
            List<String> names = eligible(k, v);
            double ratio = ratio(k, v, names);
            double[] interval = bootstrap(k, v, names);
            long lowerK = names.stream().filter(n -> metric(k, n, false) < metric(v, n, false)).count();
            long lowerV = names.stream().filter(n -> metric(v, n, false) < metric(k, n, false)).count();
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %.3fx | %.3f-%.3fx | %d / %d |%n",
                    dataset, k.size(), names.size(), ratio, interval[0], interval[1], lowerK, lowerV));
        }
        out.append("\n### Tuning failures\n\n");
        long failures = results.tuning().stream().flatMap(t -> t.choices().values().stream())
                .flatMap(c -> c.alternatives().stream()).filter(a -> a.failure() != null).count();
        if (failures == 0) {
            out.append("No candidate query timed out during this tuning run.\n");
        } else {
            out.append("The following candidates exceeded the server's 10-second query limit. They were excluded from selection, not treated as timed samples.\n\n");
            out.append("| Dataset | Query | Candidate | Outcome |\n| --- | --- | --- | --- |\n");
            for (Trial tuning : results.tuning()) {
                for (var entry : tuning.choices().entrySet()) {
                    for (Audit audit : entry.getValue().alternatives()) {
                        if (audit.failure() != null) out.append("| ").append(tuning.dataset()).append(" | ")
                                .append(entry.getKey()).append(" | ").append(audit.candidate().name()).append(" | Query timeout |\n");
                    }
                }
            }
        }
        out.append("""

                ## Per-query results

                Times are microseconds. p50 and p95 are medians of per-trial nearest-rank percentiles,
                not pooled percentiles. Ranges show the minimum/maximum samples across trials.
                p95 from short samples is descriptive and should not be treated as a production tail-latency estimate.
                `n/a` is not zero; ratios are withheld for unsupported, untimed, fractional-mismatch and limited queries.

                """);
        for (String dataset : datasets(results)) {
            List<Trial> k = trials(results, dataset, "kiara");
            List<Trial> v = trials(results, dataset, "voltdb");
            Set<String> eligible = new HashSet<>(eligible(k, v));
            out.append("### ").append(dataset).append("\n\n");
            out.append("Fixture SHA-256: `").append(k.getFirst().dataSha256()).append("`.\n\n");
            out.append("| Query | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples K / V | V/K | Frozen VoltDB variant |\n");
            out.append("| --- | --- | ---: | ---: | ---: | --- | ---: | --- |\n");
            for (Observation observation : k.getFirst().observations()) {
                var m = observation.query();
                var other = observation(v.getFirst(), m.scenario()).query();
                Choice choice = results.tuning().stream().filter(t -> t.dataset().equals(dataset))
                        .findFirst().orElseThrow().choices().get(m.scenario());
                out.append(String.format(Locale.ROOT, "| **%s**: %s | %s / %s | %s / %s | %s | %s | %s / %s | %s | %s |%n",
                        m.scenario(), m.detail().replace("|", "\\|"),
                        m.validation().status(), other.validation().status(), nullable(m.matches()), nullable(other.matches()),
                        pair(k, v, m.scenario(), false), pair(k, v, m.scenario(), true),
                        counts(k, m.scenario()), counts(v, m.scenario()),
                        eligible.contains(m.scenario()) ? String.format(Locale.ROOT, "%.3fx", metric(v, m.scenario(), false) / metric(k, m.scenario(), false)) : "n/a",
                        choice == null ? "not timed" : choice.selected()));
            }
            List<Long> kClocks = k.stream().flatMap(t -> t.observations().stream()).flatMap(o -> o.timerPairsNs().stream()).toList();
            List<Long> vClocks = v.stream().flatMap(t -> t.observations().stream()).flatMap(o -> o.timerPairsNs().stream()).toList();
            out.append(String.format(Locale.ROOT, "%nDiagnostic median timer-pair cost: Kiara %.0f ns; VoltDB %.0f ns (not subtracted).%n%n",
                    VoltExecutionBenchmark.median(kClocks), VoltExecutionBenchmark.median(vClocks)));
        }
        out.append("""
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
                """);
        return out.toString();
    }

    static List<String> datasets(Results results) {
        return results.trials().stream().map(Trial::dataset).distinct().toList();
    }

    static List<Trial> trials(Results results, String dataset, String engine) {
        return results.trials().stream().filter(t -> t.dataset().equals(dataset) && t.engine().equals(engine)).toList();
    }

    static Observation observation(Trial trial, String scenario) {
        return trial.observations().stream().filter(o -> o.query().scenario().equals(scenario)).findFirst().orElseThrow();
    }

    static List<String> eligible(List<Trial> k, List<Trial> v) {
        return k.getFirst().observations().stream().map(o -> o.query().scenario())
                .filter(n -> java.util.stream.Stream.concat(k.stream(), v.stream()).allMatch(t -> observation(t, n).query().comparable()))
                .toList();
    }

    private static double medianDouble(List<Double> values) {
        return ExecutionStatistics.median(values);
    }

    static double metric(List<Trial> trials, String scenario, boolean tail) {
        return medianDouble(trials.stream().map(t -> tail ? observation(t, scenario).query().p95Us() : observation(t, scenario).query().p50Us()).toList());
    }

    static double ratio(List<Trial> k, List<Trial> v, List<String> names) {
        return ExecutionStatistics.ratio(matrix(k, names), matrix(v, names));
    }

    static double[] bootstrap(List<Trial> k, List<Trial> v, List<String> names) {
        return ExecutionStatistics.bootstrap(matrix(k, names), matrix(v, names));
    }

    private static List<double[]> matrix(List<Trial> trials, List<String> names) {
        return trials.stream().map(t -> names.stream().mapToDouble(n -> observation(t, n).query().p50Us()).toArray()).toList();
    }

    private static String nullable(Object value) { return value == null ? "n/a" : value.toString(); }

    private static String pair(List<Trial> k, List<Trial> v, String name, boolean tail) {
        if (java.util.stream.Stream.concat(k.stream(), v.stream())
                .anyMatch(t -> observation(t, name).query().samplesNs().isEmpty())) return "n/a";
        return String.format(Locale.ROOT, "%.3f / %.3f", metric(k, name, tail), metric(v, name, tail));
    }

    private static String counts(List<Trial> trials, String name) {
        IntSummaryStatistics counts = trials.stream().mapToInt(t -> observation(t, name).query().samplesNs().size()).summaryStatistics();
        return counts.getMin() == counts.getMax() ? Integer.toString(counts.getMin()) : counts.getMin() + "-" + counts.getMax();
    }
}
