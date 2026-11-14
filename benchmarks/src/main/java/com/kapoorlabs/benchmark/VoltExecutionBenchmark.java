package com.kapoorlabs.benchmark;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;
import static com.kapoorlabs.benchmark.QueryExecution.*;

public final class VoltExecutionBenchmark {
    record Audit(VoltExecutionEngine.Candidate candidate, String plan, String source,
                 Validation validation, List<Long> samplesNs, List<Long> timerPairsNs, String failure) {}
    record Choice(String selected, List<Audit> alternatives) {}
    record Trial(String phase, String dataset, String description, String dataSha256, String engine, int fork,
                 Map<String, Object> environment, List<Observation> observations,
                 Map<String, Choice> choices, Map<String, Object> serverEvidence) {}
    record Results(String generatedAt, Map<String, Object> configuration, List<Trial> tuning, List<Trial> trials) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        if (options.containsKey("collect")) {
            collect(options);
            return;
        }
        if ("ready".equals(options.get("phase"))) {
            awaitServer();
            return;
        }
        String phase = options.getOrDefault("phase", "measure");
        VoltExecutionEngine.Profile profile = profile(options);
        String preparation = profile == VoltExecutionEngine.Profile.GENERIC ? "prepare" : "tune";
        String engineName = options.get("engine");
        if (!Set.of(preparation, "measure").contains(phase) || !Set.of("kiara", "voltdb").contains(engineName)) {
            throw new IllegalArgumentException("Specify --phase " + preparation + "|measure and --engine kiara|voltdb");
        }
        if (phase.equals(preparation) && !engineName.equals("voltdb")) throw new IllegalArgumentException("Only VoltDB has a preflight phase");
        Datasets.Dataset data = Datasets.load(options.get("dataset"));
        int fork = positive(options, "fork", 1);
        Map<String, Object> environment = environment(options);
        if (profile == VoltExecutionEngine.Profile.GENERIC) environment.put("voltdbProfile", "generic");
        List<Observation> observations = new ArrayList<>();
        Map<String, Choice> choices = new TreeMap<>();
        Map<String, Object> evidence = Map.of();
        System.out.println(phase + " / " + engineName + " / " + data.name() + " / " + fork);
        List<Workloads.Scenario> order = new ArrayList<>(data.scenarios());
        Collections.shuffle(order, new Random(Workloads.SEED + 1000L * fork));
        if (engineName.equals("kiara")) {
            try (var engine = new Engines.Kiara(data.rows())) {
                for (var scenario : order) {
                    if (!VoltDbEngine.supported(scenario)) {
                        observations.add(unsupported(scenario, data));
                        continue;
                    }
                    Validation validation = validate(engineName, scenario, data.rows(), engine.run(scenario));
                    if (!VoltExecutionEngine.timed(scenario)) {
                        observations.add(notTimed(scenario, validation, "Java-object reconstruction is outside query-only scope"));
                        continue;
                    }
                    Query query = () -> kiara(engine, scenario);
                    warm(query, positive(options, "warmup-ms", 1000), 10);
                    Samples samples = sample(query, positive(options, "samples", 101),
                            positive(options, "minimum-samples", 15), positive(options, "budget-ms", 3000));
                    validate(engineName, scenario, data.rows(), query.run().result());
                    observations.add(observation(scenario, validation, samples));
                    progress(scenario, validation, samples);
                }
            }
        } else {
            Trial tuning = phase.equals("measure")
                    ? Engines.JSON.readValue(Path.of(options.get("tuning")).toFile(), Trial.class) : null;
            String dataHash = sha(Engines.JSON.writeValueAsBytes(data.rows()));
            if (tuning != null && (!tuning.phase().equals(preparation) || !tuning.dataset().equals(data.name())
                    || !tuning.dataSha256().equals(dataHash)
                    || !tuning.environment().getOrDefault("voltdbProfile", "tuned").equals(profile.name().toLowerCase(Locale.ROOT))
                    || !tuning.environment().get("benchmarkJarSha256").equals(environment.get("benchmarkJarSha256")))) {
                throw new IllegalStateException("Tuning inputs or executable do not match measurement");
            }
            try (var engine = new VoltExecutionEngine(data.rows(), data.name().startsWith("wide-"), profile)) {
                List<VoltExecutionEngine.Candidate> candidates = new ArrayList<>();
                Map<String, List<Integer>> offsets = new HashMap<>();
                for (var scenario : order) {
                    if (!VoltExecutionEngine.timed(scenario)) continue;
                    List<VoltExecutionEngine.Candidate> variants = VoltExecutionEngine.candidates(scenario, profile);
                    if (tuning != null) {
                        Choice selected = tuning.choices().get(scenario.name());
                        if (selected == null) throw new IllegalStateException("Missing tuning choice: " + scenario.name());
                        variants = variants.stream().filter(v -> v.name().equals(selected.selected())).toList();
                        if (variants.size() != 1) throw new IllegalStateException("Unrecognized frozen tuning choice");
                    }
                    List<Integer> indexes = new ArrayList<>();
                    for (var variant : variants) {
                        indexes.add(candidates.size());
                        candidates.add(variant);
                    }
                    offsets.put(scenario.name(), indexes);
                }
                List<VoltExecutionEngine.Prepared> prepared = engine.prepare(candidates);
                for (var scenario : order) {
                    if (!VoltDbEngine.supported(scenario)) {
                        observations.add(unsupported(scenario, data));
                        continue;
                    }
                    if (!VoltExecutionEngine.timed(scenario)) {
                        observations.add(notTimed(scenario, validate(engineName, scenario, data.rows(), engine.validateUntimed(scenario)),
                                "Java-object reconstruction is outside query-only scope"));
                        continue;
                    }
                    List<Audit> audits = new ArrayList<>();
                    for (int offset : offsets.get(scenario.name())) {
                        var statement = prepared.get(offset);
                        if (tuning != null) {
                            Audit original = tuning.choices().get(scenario.name()).alternatives().stream()
                                    .filter(a -> a.candidate().name().equals(statement.candidate().name())).findFirst().orElseThrow();
                            if (!VoltExecutionEngine.normalizedPlan(original.plan())
                                    .equals(VoltExecutionEngine.normalizedPlan(statement.plan()))) {
                                throw new IllegalStateException("Execution plan changed after tuning: " + scenario.name());
                            }
                        }
                        Query query = () -> engine.run(statement, scenario);
                        try {
                            Validation validation = validate(engineName, scenario, data.rows(), query.run().result());
                            if (phase.equals("prepare")) {
                                audits.add(new Audit(statement.candidate(), statement.plan(), statement.source(), validation,
                                        List.of(), List.of(), null));
                                System.out.println("  generic-fixed / " + scenario.name() + ": " + validation.status());
                                continue;
                            }
                            warm(query, phase.equals("tune") ? 500 : positive(options, "warmup-ms", 1000), 10);
                            Samples samples = sample(query, phase.equals("tune") ? 21 : positive(options, "samples", 101),
                                    phase.equals("tune") ? 21 : positive(options, "minimum-samples", 15),
                                    positive(options, "budget-ms", 3000));
                            validate(engineName, scenario, data.rows(), query.run().result());
                            audits.add(new Audit(statement.candidate(), statement.plan(), statement.source(), validation,
                                    samples.executionNs(), samples.timerPairsNs(), null));
                            if (phase.equals("measure")) observations.add(observation(scenario, validation, samples));
                            System.out.print("  " + statement.candidate().name() + " ");
                            progress(scenario, validation, samples);
                        } catch (org.voltdb.client.ProcCallException error) {
                            if (!queryTimeout(error.getMessage())
                                    || (!phase.equals("tune") && profile != VoltExecutionEngine.Profile.GENERIC)) throw error;
                            audits.add(new Audit(statement.candidate(), statement.plan(), statement.source(), null,
                                    List.of(), List.of(), error.getMessage()));
                            if (phase.equals("measure")) observations.add(timeout(scenario, data, error.getMessage()));
                            System.err.println(phase.toUpperCase(Locale.ROOT) + " QUERY TIMEOUT: " + data.name() + " / " + scenario.name()
                                    + " / " + statement.candidate().name() + ": " + error.getMessage());
                        }
                    }
                    Audit best = profile == VoltExecutionEngine.Profile.GENERIC ? audits.getFirst()
                            : audits.stream().filter(a -> a.failure() == null)
                                    .min(Comparator.comparingDouble(a -> median(a.samplesNs()))).orElseThrow();
                    choices.put(scenario.name(), new Choice(best.candidate().name(), audits));
                }
                evidence = engine.evidence();
            }
        }
        observations.sort(Comparator.comparing(o -> o.query().scenario()));
        Trial result = new Trial(phase, data.name(), data.description(), sha(Engines.JSON.writeValueAsBytes(data.rows())),
                engineName, fork, environment, observations, choices, evidence);
        Path output = Path.of(options.get("output"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("Saved " + output);
    }

    private static void progress(Workloads.Scenario scenario, Validation validation, Samples samples) {
        System.out.printf(Locale.ROOT, "%s: %s, p50 %.3f us, %d samples%n",
                scenario.name(), validation.status(), median(samples.executionNs()) / 1000.0, samples.executionNs().size());
    }

    static boolean queryTimeout(String message) {
        return message != null && message.contains("A SQL query was terminated")
                && message.contains("exceeded the default query timeout period");
    }

    static Observation timeout(Workloads.Scenario scenario, Datasets.Dataset data, String message) {
        Validation validation = new Validation("QUERY_TIMEOUT", scenario.expectedIds(data.rows()).size(), 0, 0,
                List.of(message));
        return new Observation(new Measurement(scenario.name(), scenario.group(), scenario.detail(), false,
                null, validation, null, null, null, List.of()), List.of(),
                "Native VoltDB SQL execution limit; incomplete result and discarded partial samples");
    }

    private static VoltExecutionEngine.Profile profile(Map<String, String> options) {
        return VoltExecutionEngine.Profile.valueOf(options.getOrDefault("profile", "tuned").toUpperCase(Locale.ROOT));
    }

    static double median(List<Long> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("No timing samples");
        return percentile(values.stream().sorted().toList(), .5);
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Map<String, Object> environment(Map<String, String> options) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        Properties versions = new Properties();
        try (var input = VoltExecutionBenchmark.class.getResourceAsStream("/benchmark-versions.properties")) {
            if (input == null) throw new IllegalStateException("Missing version metadata");
            versions.load(input);
        }
        values.put("kiara", versions.getProperty("kiara"));
        values.put("sqliteJdbc", versions.getProperty("sqliteJdbc"));
        values.put("java", System.getProperty("java.runtime.version"));
        values.put("vm", System.getProperty("java.vm.name"));
        values.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        values.put("architecture", System.getProperty("os.arch"));
        values.put("activeProcessors", Runtime.getRuntime().availableProcessors());
        values.put("jvmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        Path jar = Path.of(VoltExecutionBenchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Run the packaged executable");
        values.put("benchmarkJarSha256", sha(Files.readAllBytes(jar)));
        values.put("retailCsvSha256", Datasets.RETAIL_SHA256);
        values.put("options", new TreeMap<>(options));
        return values;
    }

    private static int positive(Map<String, String> options, String name, int fallback) {
        int result = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (result < 1) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected --option value pairs");
        Set<String> allowed = Set.of("phase", "engine", "dataset", "fork", "output", "tuning", "samples",
                "minimum-samples", "warmup-ms", "budget-ms", "collect", "report", "forks", "datasets", "profile");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))
                    || result.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate option " + args[i]);
            }
        }
        return result;
    }

    private static void collect(Map<String, String> options) throws Exception {
        Path directory = Path.of(options.get("collect"));
        List<String> datasets = Arrays.asList(options.getOrDefault("datasets", Datasets.DEFAULTS).split(","));
        int forks = positive(options, "forks", 5);
        VoltExecutionEngine.Profile profile = profile(options);
        boolean generic = profile == VoltExecutionEngine.Profile.GENERIC;
        String preparation = generic ? "prepare" : "tune";
        List<Trial> tuning = new ArrayList<>();
        List<Trial> trials = new ArrayList<>();
        for (String dataset : datasets) {
            Trial tuned = Engines.JSON.readValue(directory.resolve(preparation + "-" + dataset + ".json").toFile(), Trial.class);
            if (!tuned.phase().equals(preparation)
                    || !tuned.environment().getOrDefault("voltdbProfile", "tuned").equals(profile.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Preflight profile mismatch");
            }
            tuning.add(tuned);
            Set<String> names = new TreeSet<>();
            for (int fork = 1; fork <= forks; fork++) {
                for (String engine : List.of("kiara", "voltdb")) {
                    Trial trial = Engines.JSON.readValue(directory.resolve(engine + "-" + dataset + "-" + fork + ".json").toFile(), Trial.class);
                    if (!trial.dataset().equals(dataset) || !trial.engine().equals(engine) || trial.fork() != fork
                            || !trial.phase().equals("measure") || !trial.dataSha256().equals(tuned.dataSha256())
                            || !trial.environment().getOrDefault("voltdbProfile", "tuned").equals(profile.name().toLowerCase(Locale.ROOT))
                            || !trial.environment().get("benchmarkJarSha256").equals(tuned.environment().get("benchmarkJarSha256"))) {
                        throw new IllegalStateException("Trial provenance mismatch");
                    }
                    Set<String> observed = new TreeSet<>();
                    for (Observation observation : trial.observations()) {
                        Measurement m = observation.query();
                        if (!observed.add(m.scenario())) throw new IllegalStateException("Duplicate scenario");
                        if (m.samplesNs().isEmpty() != (observation.notTimedReason() != null)
                                || m.samplesNs().size() != observation.timerPairsNs().size()) {
                            throw new IllegalStateException("Malformed timing evidence");
                        }
                        if (!Set.of("PASS", "UNSUPPORTED", "KNOWN_DECIMAL_TRUNCATION").contains(m.validation().status())
                                && !(generic && trial.engine().equals("voltdb") && m.validation().status().equals("QUERY_TIMEOUT"))) {
                            throw new IllegalStateException("Unexpected correctness difference");
                        }
                        if (!m.samplesNs().isEmpty()
                                && (m.p50Us() != median(m.samplesNs()) / 1000.0
                                || m.p95Us() != percentile(m.samplesNs().stream().sorted().toList(), .95) / 1000.0)) {
                            throw new IllegalStateException("Incorrect percentile");
                        }
                    }
                    if (names.isEmpty()) names.addAll(observed);
                    if (!names.equals(observed)) throw new IllegalStateException("Scenario sets differ");
                    trials.add(trial);
                    if (generic && engine.equals("voltdb")) {
                        if (!Engines.JSON.valueToTree(trial.serverEvidence().get("additionalIndexDdl")).isEmpty()) {
                            throw new IllegalStateException("Generic profile contains workload-specific indexes");
                        }
                        for (Choice choice : trial.choices().values()) {
                            if (!choice.selected().equals("generic-fixed") || choice.alternatives().size() != 1
                                    || !choice.alternatives().getFirst().candidate().joinOrder().isEmpty()) {
                                throw new IllegalStateException("Generic profile contains SQL candidate selection");
                            }
                        }
                    }
                }
            }
        }
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("datasets", datasets);
        configuration.put("forks", forks);
        configuration.put("timing", "Server-side voltQueueSQL/voltExecuteSQL; Kiara StoreSearch.query; eager result creation included");
        configuration.put("tuning", generic
                ? "Generic indexes only; one fixed SQL translation; correctness/plan preflight without timing selection"
                : "Best median among documented SQL/join-order variants with added composite indexes; frozen before measurement");
        if (generic) configuration.put("profile", "generic");
        configuration.put("isolation", "Linux containers, four CPU quota, 8 GiB limit; fresh worker and restarted VoltDB server per measured VoltDB trial");
        configuration.put("runtimeDifference", "Kiara JDK 21; legacy VoltDB server JDK 11");
        Path platform = directory.resolve("platform.json");
        if (!Files.isRegularFile(platform)) throw new IllegalStateException("Missing Docker/platform provenance");
        configuration.put("platform", Engines.JSON.readTree(Files.readString(platform)));
        Results results = new Results(Instant.now().toString(), configuration, tuning, trials);
        Path raw = Path.of(options.get("output"));
        Path report = Path.of(options.get("report"));
        Files.createDirectories(raw.toAbsolutePath().getParent());
        Files.createDirectories(report.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), results);
        Files.writeString(report, generic ? VoltExecutionReport.renderGeneric(results) : datasets.stream().allMatch(name -> name.startsWith("wide-"))
                ? WideReport.component() : VoltExecutionReport.render(results));
        System.out.println("Wrote " + raw + " and " + report);
    }

    private static void awaitServer() throws Exception {
        org.voltdb.client.ClientConfig config = new org.voltdb.client.ClientConfig();
        config.setProcedureCallTimeout(5000);
        String host = System.getenv().getOrDefault("VOLTDB_BENCHMARK_HOST", "127.0.0.1");
        if (!Set.of("voltdb", "127.0.0.1").contains(host)) throw new IllegalArgumentException("Nonlocal benchmark host");
        var client = org.voltdb.client.ClientFactory.createClient(config);
        try {
            long until = System.nanoTime() + 120_000_000_000L;
            while (true) {
                try {
                    client.createConnection(host, 21212);
                    break;
                } catch (java.io.IOException error) {
                    if (System.nanoTime() >= until) throw new IllegalStateException("Server did not become ready", error);
                    System.out.println("Waiting for dedicated VoltDB server: " + error.getMessage());
                    Thread.sleep(1000);
                }
            }
            var response = client.callProcedure("@SystemInformation", "OVERVIEW");
            if (response.getStatus() != org.voltdb.client.ClientResponse.SUCCESS) {
                throw new IllegalStateException("Readiness check failed: " + response.getStatusString());
            }
            System.out.println("VoltDB system procedure responded successfully");
        } finally {
            client.close();
        }
    }
}
