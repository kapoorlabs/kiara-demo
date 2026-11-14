package com.kapoorlabs.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class WideReport {
    static String component() {
        return """
                # Wide-filter component evidence

                This is one component of the separate wide-filter experiment, not the historical
                retail/catalog/schedule benchmark. Its complete measurements and tuning evidence are
                in [comparison.json](comparison.json). The combined report is generated only after all
                four pairwise comparisons complete and pass independent verification.

                See [methodology and reproduction](../../wide/README.md). Historical report narratives
                and index counts must not be applied to this new workload.
                """;
    }

    static JsonNode query(JsonNode trial, String name) {
        for (JsonNode observation : trial.path("observations")) {
            if (observation.path("query").path("scenario").asText().equals(name)) return observation.path("query");
        }
        throw new IllegalStateException("Missing query " + name);
    }

    static List<JsonNode> trials(JsonNode comparison, String dataset, String engine) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode trial : comparison.path("trials")) {
            if (trial.path("dataset").asText().equals(dataset) && trial.path("engine").asText().equals(engine)) result.add(trial);
        }
        return result;
    }

    static boolean complete(List<JsonNode> trials, String name) {
        return !trials.isEmpty() && trials.stream().allMatch(t -> query(t, name).path("comparable").asBoolean());
    }

    static boolean deadlineWin(List<JsonNode> completed, List<JsonNode> timedOut, String name) {
        return completed.size() == timedOut.size() && complete(completed, name)
                && timedOut.stream().allMatch(t -> query(t, name).path("validation").path("status").asText().equals("QUERY_TIMEOUT"));
    }

    static double median(List<JsonNode> trials, String name) {
        return ExecutionStatistics.median(trials.stream().map(t -> query(t, name).path("p50Us").doubleValue()).toList());
    }

    static List<double[]> matrix(List<JsonNode> trials, List<String> names) {
        return trials.stream().map(t -> names.stream().mapToDouble(n -> query(t, n).path("p50Us").doubleValue()).toArray()).toList();
    }

    static String metric(List<JsonNode> trials, String name) {
        long timeouts = trials.stream().filter(t -> query(t, name).path("validation").path("status").asText().equals("QUERY_TIMEOUT")).count();
        return timeouts > 0 ? "TIMEOUT " + timeouts + "/" + trials.size()
                : String.format(Locale.ROOT, "%.3f", median(trials, name));
    }

    static String render(JsonNode results) {
        StringBuilder out = new StringBuilder("""
                # **Wide-filter comparison: Kiara, Lucene, VoltDB CE and SQLite**

                This separate experiment varies the number of **distinct filtered attributes**, not merely
                predicate count or the number of requested values on one field. Historical reports are unchanged.
                Results apply to these synthetic distributions, adapters and physical designs, not all databases.

                [Raw trials, conditions, oracle counts, plans and index inventories](../benchmarks/wide-filter-comparison.json) |
                [Methodology and reproduction](../benchmarks/wide/README.md)

                ## Workload

                - Widths: **2, 4, 8, 12 and 16 distinct fields**, combined with AND.
                - Scalar-only controls: 2, 4 and 8 fields. The existing schema has only nine applicable scalar
                  fields without using identifiers, decimals or low-cardinality enum/boolean controls; no
                  artificial new columns are added to manufacture a 16-field scalar-only case.
                - Mixed EITHER and mixed ALL families: all five widths. Each collection predicate requests
                  **two distinct values**. Wider cases mix strings, numbers, dates/timestamps and stored intervals.
                - Expected final selectivities: **0.1%, 1% and 10%** for every width/family. Actual counts below
                  come from the independent scan oracle; they are not forced to equal their expectations.
                - Other scalar predicates accept 95% of random rows; collection EITHER accepts 95%, ALL 90%.
                  An independently drawn quantity selector compensates for expected conjunction selectivity.
                  The same rows serve every query and all engines. One all-matching anchor prevents empty
                  results; it adds a 1/N bias, material in tiny smoke fixtures. Query width thus adds real,
                  mostly broad predicates instead of simply intersecting many rare values to obtain zero rows.
                - This deliberately controlled, low-cardinality, mostly independent fixture is a stress test,
                  not a claim about real attribute correlations. It does not isolate operator cost from data
                  type, number of collection joins, predicate order or plan changes. Compare widths within
                  the same family and selectivity; do not treat width alone as a causal performance model.

                ## Timing, tuning and correctness

                Both engines are rerun for each comparison. Fresh JVMs, alternating engine order and seeded
                scenario shuffles are used. Standard measurements use five trials, at least ten warmup calls
                and one second, and 15-101 samples with a three-second acquisition budget (the minimum can
                exceed that budget). Smoke runs have one trial and are not publication measurements.

                Kiara, SQLite and Lucene include execution and eager ID-only Java maps. Query/condition
                preparation, builds, scan validation and subsequent ID extraction are excluded. Lucene uses
                constant-score document-order retrieval, stored IDs and no query cache; search-time query
                rewriting remains included. This new Lucene boundary is not the historical end-to-end harness.
                SQLite includes JDBC/JNI, all cursor steps and closure. VoltDB uses server-side procedure
                execution and VoltTable creation, excluding transport and client decoding; that is a different
                boundary and representation. **Do not rank all four opponents against each other using these
                ratios**. Kiara results are independently measured within each comparison.

                Tuned SQLite uses ANALYZE, the existing tuned indexes plus one quantity-first covering index
                over the wide scalar fields, and frozen SQL candidate selection. Generic SQLite retains
                26 indexes, original SQL and no ANALYZE or SQL candidate selection. Tuned VoltDB adds the
                same wide covering index and tests indexed set intersections plus explicit record-first/
                value-first join orders. Joins are tried through four EITHER collection fields and two
                ALL collection fields; above those bounds only intersections are attempted. Wider joins
                exceeded a two-minute catalog-compilation timeout in smoke/probe testing before execution
                timing; five-table EITHER joins compiled and validated and remain candidates. The wide
                client now allows ten minutes per request for larger batched catalog setup, without
                changing the server's SQL execution-timeout setting. The search bounds remain a disclosed
                practical tuning restriction, not proof that excluded plans can never compile. This is not a
                measured query loss. Validated candidate medians choose the frozen plan before fresh
                measurements. Structurally identical SQL/join-order/type templates are compiled once and
                reused with different bound values, rather than recompiling for each selectivity target.
                Neither tuning search is exhaustive or held out.
                Index/load/build/write costs are not measured. Lucene and Kiara use their existing indexes.

                All matching IDs are validated against the scan oracle before and after timing. Partial or
                incorrect results are not wins. Generic SQLite retains the approved 60-second per-invocation
                wall deadline for both engines and durable fresh-worker recovery. Tuned SQLite/Lucene have
                no such measurement deadline; VoltDB retains its native configured query timeout and fails
                measurement on timeout. Therefore deadline policy differs across comparisons; compare
                within a pair, not across pairs. Timeouts never become fabricated latency samples.

                Ratios below are opponent latency / Kiara latency. Per-query latency is the median of trial
                p50s; summary ratios are equal-query geometric means over the three selectivity targets that
                pass in every trial of both engines. Exploratory intervals use 5,000 whole-trial bootstrap
                resamples. Deadline-completion wins require validated completion in every trial versus
                timeouts in every opposing trial; they are separate from measured-p50 wins and ratios.

                """);
        out.append("Generated: ").append(results.path("generatedAt").asText()).append("; trials per engine/dataset: ")
                .append(results.path("forks").asInt()).append(".\n\n");
        out.append("Pinned dependency versions: `").append(results.path("versions").toString()).append("`.\n\n");
        for (var entry : results.path("comparisons").properties()) {
            String comparison = entry.getKey();
            JsonNode data = entry.getValue();
            String opponent = comparison.startsWith("sqlite") ? "sqlite" : comparison;
            out.append("## Kiara vs ").append(comparison).append("\n\n");
            out.append("| Dataset | Family | Attributes | Fully measured queries | Opponent / Kiara | Exploratory 95% interval | Measured p50 wins K / opponent | Deadline wins K / opponent |\n");
            out.append("| --- | --- | ---: | ---: | ---: | --- | ---: | ---: |\n");
            for (var datasetEntry : results.path("datasets").properties()) {
                String dataset = datasetEntry.getKey();
                List<JsonNode> k = trials(data, dataset, "kiara"), other = trials(data, dataset, opponent);
                for (String family : List.of("scalar", "either", "all")) for (int width : WideWorkloads.WIDTHS) {
                    List<String> names = new ArrayList<>();
                    for (JsonNode scenario : datasetEntry.getValue().path("scenarios")) {
                        if (scenario.path("name").asText().startsWith(family + "-w" + width + "-")) names.add(scenario.path("name").asText());
                    }
                    if (names.isEmpty()) continue;
                    var eligible = names.stream().filter(n -> complete(k, n) && complete(other, n)).toList();
                    String ratio = "n/a", interval = "n/a";
                    if (!eligible.isEmpty()) {
                        ratio = String.format(Locale.ROOT, "%.3fx", ExecutionStatistics.ratio(matrix(k, eligible), matrix(other, eligible)));
                        double[] ci = ExecutionStatistics.bootstrap(matrix(k, eligible), matrix(other, eligible));
                        interval = String.format(Locale.ROOT, "%.3f-%.3fx", ci[0], ci[1]);
                    }
                    long kw = eligible.stream().filter(n -> median(k, n) < median(other, n)).count();
                    long ow = eligible.stream().filter(n -> median(other, n) < median(k, n)).count();
                    long kd = names.stream().filter(n -> deadlineWin(k, other, n)).count();
                    long od = names.stream().filter(n -> deadlineWin(other, k, n)).count();
                    out.append(String.format(Locale.ROOT, "| %s | %s | %d | %d/3 | %s | %s | %d / %d | %d / %d |%n",
                            dataset, family, width, eligible.size(), ratio, interval, kw, ow, kd, od));
                }
            }
            out.append("\n### Per-query results\n\n");
            out.append("| Dataset | Query | Attributes | Oracle matches | Actual selectivity | Kiara p50 us | Opponent p50 us | Opponent / Kiara | Outcome |\n");
            out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
            for (var datasetEntry : results.path("datasets").properties()) {
                String dataset = datasetEntry.getKey();
                var k = trials(data, dataset, "kiara");
                var other = trials(data, dataset, opponent);
                for (JsonNode scenario : datasetEntry.getValue().path("scenarios")) {
                    String name = scenario.path("name").asText(), ratio = "n/a", outcome = "Mixed timeout; no unanimous winner";
                    if (complete(k, name) && complete(other, name)) {
                        double value = median(other, name) / median(k, name);
                        ratio = String.format(Locale.ROOT, "%.4fx", value);
                        outcome = value > 1 ? "Kiara lower p50" : value < 1 ? "Opponent lower p50" : "Tie";
                    } else if (deadlineWin(k, other, name)) outcome = "Kiara deadline win";
                    else if (deadlineWin(other, k, name)) outcome = "Opponent deadline win";
                    out.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.4f%% | %s | %s | %s | %s |%n",
                            dataset, name, scenario.path("attributes").asInt(), scenario.path("expectedMatches").asInt(),
                            scenario.path("actualSelectivity").doubleValue() * 100, metric(k, name), metric(other, name), ratio, outcome));
                }
            }
            out.append("\n");
        }
        out.append("""
                ## Publication limits

                This is single-threaded, warm, in-memory read execution on a shared virtualized Docker host,
                not dedicated hardware. It does not establish write throughput, build efficiency, memory
                efficiency, disk-backed SQLite performance, transactional durability, concurrency or current
                commercial VoltDB performance. The server is legacy AGPL VoltDB CE 10.1.1.
                Versions, exact measured executables, platforms, candidate audits, plans, raw samples,
                actual result sizes and exclusions are retained in the raw evidence. Use independent
                replication and application-representative correlations before drawing broader conclusions.
                """);
        return out.toString();
    }

    private WideReport() {}
}
