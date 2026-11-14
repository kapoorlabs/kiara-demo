package com.kapoorlabs.benchmark;

import java.util.*;
import java.util.function.ToDoubleFunction;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;

final class VoltDbReport {
    static String render(VoltDbBenchmark.Results results) {
        StringBuilder out = new StringBuilder("""
                # Kiara Java and VoltDB Community Edition read-query comparison

                **Version scope:** This uses the AGPL-licensed VoltDB Community Edition **10.1.1 (2020)**,
                obtained from Maven Central, not the current commercial/developer release. The current developer
                image requires a license key. The user explicitly requested Community Edition. No license checks
                were bypassed and the server was not patched. These results must not be attributed to modern
                commercial VoltDB. Both databases use in-memory indexes, but their APIs and architectures differ.

                [Executable harness](../benchmarks/src/main/java/com/kapoorlabs/benchmark/VoltDbBenchmark.java) |
                [Raw per-fork results, SQL parameters and nanosecond samples](../benchmarks/voltdb-comparison.json) |
                [Setup and reproduction](../benchmarks/voltdb/README.md)

                """);
        out.append("Generated: ").append(results.generatedAt()).append(".\n\n");
        out.append("Environment: ").append(results.environment()).append(".\n\n");
        out.append("Configuration: ").append(results.configuration()).append(".\n\n");
        out.append("""
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
                """);
        for (String dataset : results.workers().stream().map(w -> w.run().dataset()).distinct().toList()) {
            List<Run> kiara = runs(results, dataset, "kiara");
            List<Run> voltdb = runs(results, dataset, "voltdb");
            List<Measurement> eligible = kiara.getFirst().measurements().stream()
                    .filter(m -> comparable(kiara, voltdb, m.scenario())).toList();
            double ratio = eligible.isEmpty() ? Double.NaN : Math.exp(eligible.stream()
                    .mapToDouble(m -> Math.log(metric(voltdb, m.scenario(), Measurement::p50Us)
                            / metric(kiara, m.scenario(), Measurement::p50Us))).average().orElseThrow());
            long timed = kiara.getFirst().measurements().stream().filter(m -> !m.samplesNs().isEmpty()).count();
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %.1f / %.1f |%n",
                    dataset, timed, eligible.size(), Double.isNaN(ratio) ? "n/a" : String.format(Locale.ROOT, "%.2fx", ratio),
                    median(kiara.stream().map(Run::buildMs).toList()), median(voltdb.stream().map(Run::buildMs).toList())));
        }
        out.append("""

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
                """);
        for (String dataset : results.workers().stream().map(w -> w.run().dataset()).distinct().toList()) {
            List<Run> kiara = runs(results, dataset, "kiara");
            List<Run> voltdb = runs(results, dataset, "voltdb");
            out.append("\n## ").append(dataset).append("\n\n").append(kiara.getFirst().description()).append("\n\n");
            out.append("| Scenario | Status K / V | Matches K / V | p50 us K / V | p95 us K / V | Samples per fork K / V | V/K ratio |\n");
            out.append("| --- | --- | ---: | ---: | ---: | --- | ---: |\n");
            for (Measurement measurement : kiara.getFirst().measurements()) {
                String name = measurement.scenario();
                if (measurement.validation().status().equals("UNSUPPORTED")) {
                    out.append("| **").append(name).append("**: ").append(measurement.detail())
                            .append(" | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |\n");
                    continue;
                }
                double kp = metric(kiara, name, Measurement::p50Us);
                double vp = metric(voltdb, name, Measurement::p50Us);
                out.append(String.format(Locale.ROOT,
                        "| **%s**: %s | %s / %s | %.0f / %.0f | %.2f / %.2f | %.2f / %.2f | %s / %s | %s |%n",
                        name, measurement.detail(), statuses(kiara, name), statuses(voltdb, name),
                        metric(kiara, name, m -> m.matches()), metric(voltdb, name, m -> m.matches()), kp, vp,
                        metric(kiara, name, Measurement::p95Us), metric(voltdb, name, Measurement::p95Us),
                        samples(kiara, name), samples(voltdb, name),
                        comparable(kiara, voltdb, name) ? String.format(Locale.ROOT, "%.2fx", vp / kp) : "n/a"));
            }
        }
        out.append("""

                ## Reproduction and sources

                Follow the [local setup instructions](../benchmarks/voltdb/README.md). Raw results include SQL
                templates and bound parameters for each supported scenario. Do not combine worker files from
                different executable hashes or input fingerprints.

                - [AGPL CE server artifact and license declaration](https://repo.maven.apache.org/maven2/org/voltdb/voltdb/10.1.1/voltdb-10.1.1.pom)
                - [Archived upstream runtime dependency snapshot](https://github.com/DBOS-project/voltdb/tree/663131f448f184b2be52a1b1a4478a63c476b4c6)
                - [Shared datasets](../benchmarks/src/main/java/com/kapoorlabs/benchmark/Datasets.java)
                - [Independent oracle](../benchmarks/src/main/java/com/kapoorlabs/benchmark/Workloads.java)
                - [Retail license and attribution](../benchmarks/src/main/resources/README-retail.md)
                """);
        return out.toString();
    }

    private static List<Run> runs(VoltDbBenchmark.Results results, String dataset, String engine) {
        return results.workers().stream().map(VoltDbBenchmark.Worker::run)
                .filter(r -> r.dataset().equals(dataset) && r.engine().equals(engine)).toList();
    }

    private static Measurement measurement(Run run, String name) {
        return run.measurements().stream().filter(m -> m.scenario().equals(name)).findFirst().orElseThrow();
    }

    private static boolean comparable(List<Run> kiara, List<Run> voltdb, String name) {
        return kiara.stream().allMatch(r -> measurement(r, name).comparable())
                && voltdb.stream().allMatch(r -> measurement(r, name).comparable());
    }

    private static String statuses(List<Run> runs, String name) {
        return String.join(", ", runs.stream().map(r -> measurement(r, name).validation().status()).distinct().sorted().toList());
    }

    private static String samples(List<Run> runs, String name) {
        IntSummaryStatistics sizes = runs.stream().mapToInt(r -> measurement(r, name).samplesNs().size()).summaryStatistics();
        return sizes.getMin() == sizes.getMax() ? Integer.toString(sizes.getMin()) : sizes.getMin() + "-" + sizes.getMax();
    }

    private static double metric(List<Run> runs, String name, ToDoubleFunction<Measurement> value) {
        return median(runs.stream().map(r -> value.applyAsDouble(measurement(r, name))).toList());
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(index - 1) + sorted.get(index)) / 2 : sorted.get(index);
    }
}
