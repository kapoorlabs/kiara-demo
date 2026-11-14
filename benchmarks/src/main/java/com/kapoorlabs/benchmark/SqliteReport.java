package com.kapoorlabs.benchmark;

import java.util.*;
import com.kapoorlabs.benchmark.QueryExecution.Observation;
import static com.kapoorlabs.benchmark.SqliteBenchmark.*;

final class SqliteReport {
    static List<Trial> trials(Results results, String dataset, String engine) {
        return results.trials().stream().filter(t -> t.dataset().equals(dataset) && t.engine().equals(engine)).toList();
    }

    static Observation observation(Trial trial, String scenario) {
        return trial.observations().stream().filter(o -> o.query().scenario().equals(scenario)).findFirst().orElseThrow();
    }

    static List<String> eligible(List<Trial> kiara, List<Trial> sqlite) {
        return kiara.getFirst().observations().stream().map(o -> o.query().scenario())
                .filter(name -> java.util.stream.Stream.concat(kiara.stream(), sqlite.stream())
                        .allMatch(t -> observation(t, name).query().comparable())).toList();
    }

    static boolean deadlineWin(List<Trial> completed, List<Trial> timedOut, String scenario) {
        return !completed.isEmpty() && completed.size() == timedOut.size()
                && completed.stream().allMatch(t -> observation(t, scenario).query().comparable())
                && timedOut.stream().allMatch(t -> observation(t, scenario).query().validation().status().equals("QUERY_TIMEOUT"));
    }

    static double metric(List<Trial> trials, String scenario, boolean tail) {
        return ExecutionStatistics.median(trials.stream().map(t -> tail
                ? observation(t, scenario).query().p95Us() : observation(t, scenario).query().p50Us()).toList());
    }

    static List<double[]> matrix(List<Trial> trials, List<String> names) {
        return trials.stream().map(t -> names.stream().mapToDouble(name -> observation(t, name).query().p50Us()).toArray()).toList();
    }

    static String render(Results results) {
        boolean generic = "generic".equals(results.configuration().get("profile"));
        StringBuilder out = new StringBuilder("""
                # **Kiara DB and SQLite: %s in-memory read-execution comparison**

                This experiment compares embedded Kiara with SQLite through Xerial JDBC, using the same five
                retail/catalog/schedule fixtures and independent result oracle. It is a warm, synchronous,
                single-connection read experiment, not a universal database ranking or a throughput benchmark.

                [Raw trials, SQL, bound parameters, plans, VM bytecode and index inventories](../benchmarks/%s) |
                [Reproduction](../benchmarks/sqlite/README.md) |
                [Harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/SqliteBenchmark.java)

                """.formatted(generic ? "generic-index (untuned)" : "tuned",
                generic ? "sqlite-untuned-comparison.json" : "sqlite-comparison.json"));
        Trial first = results.trials().stream().filter(t -> t.engine().equals("sqlite")).findFirst().orElseThrow();
        out.append("Generated: ").append(results.generatedAt()).append(".\n\n");
        out.append("Versions: Kiara **").append(first.environment().get("kiara")).append("**, SQLite **")
                .append(first.evidence().get("sqliteVersion")).append("**, Xerial JDBC **")
                .append(first.environment().get("sqliteJdbc")).append("**; both workers use Java **")
                .append(first.environment().get("java")).append("**.\n\n");
        out.append("Measured executable SHA-256: `").append(first.environment().get("benchmarkJarSha256")).append("`.\n\n");
        out.append("""
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

                """);
        if (generic) {
            out.append("""
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

                    """);
        } else {
            out.append("""
                ## Workload-specific tuning

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

                """);
        }
        out.append("""
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

                """);
        if (generic) {
            out.append("""
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

                    """);
        }
        out.append("| Dataset | Equivalent queries | SQLite / Kiara | Exploratory 95% interval | Lower p50: Kiara / SQLite |");
        if (generic) out.append(" Deadline wins: Kiara / SQLite | Total query wins: Kiara / SQLite |");
        out.append("\n| --- | ---: | ---: | --- | ---: |");
        if (generic) out.append(" ---: | ---: |");
        out.append("\n");
        List<String> datasets = results.trials().stream().map(Trial::dataset).distinct().toList();
        for (String dataset : datasets) {
            List<Trial> k = trials(results, dataset, "kiara"), s = trials(results, dataset, "sqlite");
            List<String> names = eligible(k, s);
            double ratio = ExecutionStatistics.ratio(matrix(k, names), matrix(s, names));
            double[] interval = ExecutionStatistics.bootstrap(matrix(k, names), matrix(s, names));
            long kWins = names.stream().filter(n -> metric(k, n, false) < metric(s, n, false)).count();
            long sWins = names.stream().filter(n -> metric(s, n, false) < metric(k, n, false)).count();
            out.append(String.format(Locale.ROOT, "| %s | %d | %.3fx | %.3f-%.3fx | %d / %d |",
                    dataset, names.size(), ratio, interval[0], interval[1], kWins, sWins));
            if (generic) {
                long kDeadline = k.getFirst().observations().stream()
                        .filter(o -> deadlineWin(k, s, o.query().scenario())).count();
                long sDeadline = s.getFirst().observations().stream()
                        .filter(o -> deadlineWin(s, k, o.query().scenario())).count();
                out.append(String.format(Locale.ROOT, " %d / %d | %d / %d |",
                        kDeadline, sDeadline, kWins + kDeadline, sWins + sDeadline));
            }
            out.append("\n");
        }
        long timed = results.trials().stream().flatMap(t -> t.observations().stream()).filter(o -> !o.query().samplesNs().isEmpty()).count();
        long samples = results.trials().stream().flatMap(t -> t.observations().stream()).mapToLong(o -> o.query().samplesNs().size()).sum();
        long candidates = results.tuning().stream().flatMap(t -> t.choices().values().stream()).mapToLong(c -> c.alternatives().size()).sum();
        long failed = results.tuning().stream().flatMap(t -> t.choices().values().stream())
                .flatMap(c -> c.alternatives().stream()).filter(a -> a.failure() != null).count();
        out.append(String.format(Locale.ROOT, "%nEvidence: %d measured trials, %d %s, %d %s (%d timed out), "
                + "%d timed scenario/trial entries, and %d raw execution samples.%n%n",
                results.trials().size(), results.tuning().size(), generic ? "preflight passes (not tuning)" : "tuning trials",
                candidates, generic ? "predetermined statements" : "candidates", failed, timed, samples));
        for (String status : List.of("KNOWN_DECIMAL_TRUNCATION", "UNSUPPORTED", "QUERY_TIMEOUT")) {
            long count = results.trials().stream().flatMap(t -> t.observations().stream())
                    .filter(o -> o.query().validation().status().equals(status)).count();
            out.append(status).append(": ").append(count).append(" scenario/trial entries; excluded from ratios.\n\n");
        }
        if (generic) {
            out.append("### Deadline outcomes (not latency measurements)\n\n");
            out.append("Configured deadline: ").append(results.configuration().get("queryTimeoutMs"))
                    .append(" ms per invocation. The 60-second protocol above describes the full run; worker options/configuration govern smoke overrides.\n\n");
            out.append("| Dataset | Query | Kiara timed-out trials | SQLite timed-out trials | Completion outcome | Latency-ratio eligibility |\n");
            out.append("| --- | --- | ---: | ---: | --- | --- |\n");
            boolean any = false;
            for (String dataset : datasets) {
                List<Trial> k = trials(results, dataset, "kiara"), s = trials(results, dataset, "sqlite");
                for (Observation o : k.getFirst().observations()) {
                    String name = o.query().scenario();
                    long kt = k.stream().filter(t -> observation(t, name).query().validation().status().equals("QUERY_TIMEOUT")).count();
                    long st = s.stream().filter(t -> observation(t, name).query().validation().status().equals("QUERY_TIMEOUT")).count();
                    if (kt + st == 0) continue;
                    any = true;
                    String winner = deadlineWin(k, s, name) ? "Kiara deadline win"
                            : deadlineWin(s, k, name) ? "SQLite deadline win" : "No unanimous deadline winner";
                    out.append(String.format(Locale.ROOT, "| %s | %s | %d/%d | %d/%d | %s | Excluded from latency ratio |%n",
                            dataset, name, kt, k.size(), st, s.size(), winner));
                }
            }
            if (!any) out.append("| All | No measured query exceeded the deadline | 0 | 0 | No deadline wins | No deadline exclusions |\n");
            out.append("\n");
        }
        out.append("""
                ## Detailed results

                Times below are microseconds. The displayed p95 is the median of trial p95s, not a pooled
                percentile. With 15 calls, nearest-rank p95 is the maximum and is weak tail-latency evidence.
                Use the unrounded raw values when inverting small ratios.

                """);
        for (String dataset : datasets) {
            List<Trial> k = trials(results, dataset, "kiara"), s = trials(results, dataset, "sqlite");
            Set<String> names = new HashSet<>(eligible(k, s));
            Trial tuned = results.tuning().stream().filter(t -> t.dataset().equals(dataset)).findFirst().orElseThrow();
            out.append("### ").append(dataset).append("\n\n").append(k.getFirst().description()).append("\n\n");
            out.append("Fixture SHA-256: `").append(k.getFirst().dataSha256()).append("`.\n\n");
            out.append("| Query | Status K / S | Matches K / S | p50 us K / S | p95 us K / S | Samples K / S | S/K | Frozen SQLite variant |\n");
            out.append("| --- | --- | ---: | ---: | ---: | --- | ---: | --- |\n");
            for (Observation observation : k.getFirst().observations()) {
                var m = observation.query();
                var other = observation(s.getFirst(), m.scenario()).query();
                Choice choice = tuned.choices().get(m.scenario());
                out.append(String.format(Locale.ROOT, "| **%s**: %s | %s / %s | %s / %s | %s | %s | %s / %s | %s | %s |%n",
                        m.scenario(), m.detail().replace("|", "\\|"), m.validation().status(), other.validation().status(),
                        nullable(m.matches()), nullable(other.matches()), pair(k, s, m.scenario(), false),
                        pair(k, s, m.scenario(), true), counts(k, m.scenario()), counts(s, m.scenario()),
                        names.contains(m.scenario()) ? String.format(Locale.ROOT, "%.4fx", metric(s, m.scenario(), false) / metric(k, m.scenario(), false)) : "n/a",
                        choice == null ? "not timed" : choice.selected()));
            }
            out.append("\nLowest and highest per-query ratios (descriptive, not universal speedups):\n\n");
            List<String> ordered = names.stream().sorted(Comparator.comparingDouble(n -> metric(s, n, false) / metric(k, n, false))).toList();
            for (String name : new LinkedHashSet<>(List.of(ordered.getFirst(), ordered.getLast()))) {
                out.append(String.format(Locale.ROOT, "- `%s`: Kiara %.6f ms; SQLite %.6f ms; SQLite/Kiara %.6f.%n",
                        name, metric(k, name, false) / 1000, metric(s, name, false) / 1000,
                        metric(s, name, false) / metric(k, name, false)));
                for (var step : reference(tuned.choices().get(name), generic ? SqliteEngine.Profile.GENERIC : SqliteEngine.Profile.TUNED).plan()) {
                    out.append("  - Plan: `").append(step.get("detail")).append("`.\n");
                }
            }
            if (ordered.size() > 2) {
                List<String> trimmed = ordered.subList(1, ordered.size() - 1);
                out.append(String.format(Locale.ROOT, "%nPost hoc sensitivity: removing the minimum and maximum ratios yields %.3fx. "
                        + "**Do not discard those queries or replace the primary aggregate with this value.** "
                        + "This changes workload composition, unlike the fixed-workload bootstrap.%n%n",
                        ExecutionStatistics.ratio(matrix(k, trimmed), matrix(s, trimmed))));
            }
        }
        out.append("""
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
                """);
        if (!generic) out.append(tuningDisclosure());
        return out.toString();
    }

    static String tuningDisclosure() {
        return """

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
                """;
    }

    private static String nullable(Object value) { return value == null ? "n/a" : value.toString(); }
    private static String pair(List<Trial> k, List<Trial> s, String name, boolean tail) {
        return displayMetric(k, name, tail) + " / " + displayMetric(s, name, tail);
    }
    private static String displayMetric(List<Trial> trials, String name, boolean tail) {
        long timeouts = trials.stream().filter(t -> observation(t, name).query().validation().status().equals("QUERY_TIMEOUT")).count();
        if (timeouts > 0) return "TIMEOUT " + timeouts + "/" + trials.size();
        if (observation(trials.getFirst(), name).query().samplesNs().isEmpty()) return "n/a";
        return String.format(Locale.ROOT, "%.3f", metric(trials, name, tail));
    }
    private static String counts(List<Trial> trials, String name) {
        IntSummaryStatistics count = trials.stream().mapToInt(t -> observation(t, name).query().samplesNs().size()).summaryStatistics();
        return count.getMin() == count.getMax() ? Integer.toString(count.getMin()) : count.getMin() + "-" + count.getMax();
    }
}
