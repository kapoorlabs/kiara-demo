package com.kapoorlabs.benchmark;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;

public final class VoltDbBenchmark {
    record Worker(Run run, String serverVersion, Map<String, VoltDbEngine.Sql> queries) {}
    record Results(String generatedAt, Map<String, Object> environment, Map<String, Object> configuration, List<Worker> workers) {}
    private static volatile Object blackhole;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("worker")) {
            worker(options);
            return;
        }
        List<String> datasets = Arrays.asList(options.getOrDefault("datasets", Datasets.DEFAULTS).split(","));
        if (new HashSet<>(datasets).size() != datasets.size()) throw new IllegalArgumentException("Duplicate datasets");
        for (String dataset : datasets) Datasets.load(dataset);
        int forks = positive(options, "forks", 3);
        Path jar = Path.of(VoltDbBenchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!jar.toString().endsWith(".jar")) throw new IllegalStateException("Run the packaged JAR");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isExecutable(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path directory = Files.createTempDirectory("kiara-voltdb-");
        System.out.println("Worker evidence: " + directory);
        List<Worker> workers = new ArrayList<>();
        for (String dataset : datasets) {
            for (int fork = 1; fork <= forks; fork++) {
                for (String engine : fork % 2 == 1 ? List.of("kiara", "voltdb") : List.of("voltdb", "kiara")) {
                    Path output = directory.resolve(engine + "-" + dataset + "-" + fork + ".json");
                    List<String> command = new ArrayList<>(List.of(java.toString(), "-Xms256m", "-Xmx4g",
                            "-XX:+UseG1GC", "-Duser.timezone=UTC", "-XX:ActiveProcessorCount=4",
                            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                            "-cp", jar.toString(), VoltDbBenchmark.class.getName(),
                            "--worker", engine, "--dataset", dataset, "--fork", Integer.toString(fork),
                            "--output", output.toString()));
                    for (String key : List.of("samples", "warmup-ms", "budget-ms")) {
                        command.add("--" + key);
                        command.add(Integer.toString(positive(options, key, key.equals("samples") ? 61 : key.equals("warmup-ms") ? 50 : 1000)));
                    }
                    int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
                    if (exit != 0) throw new IllegalStateException(engine + " worker exited " + exit + "; evidence retained at " + directory);
                    workers.add(Engines.JSON.readValue(output.toFile(), Worker.class));
                }
            }
        }
        validateWorkers(workers, datasets, forks);
        Properties versions = new Properties();
        try (var input = VoltDbBenchmark.class.getResourceAsStream("/benchmark-versions.properties")) {
            if (input == null) throw new IllegalStateException("Missing version metadata");
            versions.load(input);
        }
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("kiara", versions.getProperty("kiara"));
        environment.put("voltdb", workers.stream().filter(w -> w.run().engine().equals("voltdb"))
                .map(Worker::serverVersion).distinct().toList());
        environment.put("voltdbClient", "10.1.1");
        environment.put("edition", "AGPL Community Edition 10.1.1 (2020); not the current commercial release");
        environment.put("serverJarSha256", "bf3e82c1b3211006da00a0d59f46dc7300fd44bc534cd4af88f8f3948c53a8ff");
        environment.put("serverRuntime", "Java 11; archived CE runtime dependencies from DBOS-project/voltdb commit 663131f448f184b2be52a1b1a4478a63c476b4c6; reload4j 1.2.26");
        environment.put("java", System.getProperty("java.runtime.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("cpu", System.getenv().getOrDefault("BENCHMARK_CPU_NAME",
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "not reported")));
        environment.put("benchmarkJarSha256", sha(Files.readAllBytes(jar)));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("datasets", datasets);
        configuration.put("forksPerEngineAndDataset", forks);
        configuration.put("maximumSamplesPerScenario", positive(options, "samples", 61));
        configuration.put("minimumSamplesPerScenario", Math.min(5, positive(options, "samples", 61)));
        configuration.put("minimumWarmupMsPerScenario", positive(options, "warmup-ms", 50));
        configuration.put("measurementBudgetMsPerScenario", positive(options, "budget-ms", 1000));
        configuration.put("catalogSeed", Workloads.SEED);
        configuration.put("scheduleSeed", Workloads.SEED + 1);
        configuration.put("retailCsvSha256", Datasets.RETAIL_SHA256);
        configuration.put("workers", "Sequential; alternating engine order; fresh JVM per engine/dataset/fork; 4 active Java processors; 4 GiB maximum heap");
        configuration.put("server", "One long-lived single-node Docker server; 4 CPU quota, 4 sites, 8 GiB container limit, 4 GiB Java heap; new tables/indexes each fork");
        configuration.put("unsupported", "keyword, prefix prediction, spelling suggestion: no equivalent text adapter; skipped for BOTH engines");
        Results result = new Results(Instant.now().toString(), environment, configuration, workers);
        Path raw = Path.of(options.getOrDefault("output", "benchmarks\\voltdb-comparison.json")).toAbsolutePath();
        Path report = Path.of(options.getOrDefault("report", "docs\\voltdb-end-to-end.md")).toAbsolutePath();
        Files.createDirectories(raw.getParent());
        Files.createDirectories(report.getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), result);
        Files.writeString(report, VoltDbReport.render(result));
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(directory);
        System.out.println("Wrote " + raw + " and " + report);
    }

    private static void worker(Map<String, String> options) throws Exception {
        String engineName = options.get("worker");
        if (!Set.of("kiara", "voltdb").contains(engineName)) throw new IllegalArgumentException("Unknown engine " + engineName);
        Datasets.Dataset dataset = Datasets.load(options.get("dataset"));
        int fork = positive(options, "fork", 1);
        int samples = positive(options, "samples", 61);
        int warmup = positive(options, "warmup-ms", 50);
        int budget = positive(options, "budget-ms", 1000);
        System.out.println(engineName + ": " + dataset.name() + ", fork " + fork);
        try (Engines.Engine priming = build(engineName, dataset.rows().subList(0, 64))) {
            blackhole = priming.run(dataset.scenarios().stream().filter(VoltDbEngine::supported).findFirst().orElseThrow());
        }
        long start = System.nanoTime();
        Engines.Engine built = build(engineName, dataset.rows());
        double buildMs = (System.nanoTime() - start) / 1e6;
        try (Engines.Engine engine = built) {
            String version = engine instanceof VoltDbEngine voltdb ? voltdb.serverVersion() : null;
            List<Measurement> measurements = new ArrayList<>();
            Map<String, VoltDbEngine.Sql> queries = new TreeMap<>();
            List<Workloads.Scenario> order = new ArrayList<>(dataset.scenarios());
            Collections.shuffle(order, new Random(Workloads.SEED + fork));
            for (Workloads.Scenario scenario : order) {
                if (!VoltDbEngine.supported(scenario)) {
                    measurements.add(new Measurement(scenario.name(), scenario.group(), scenario.detail(), false, null,
                            new Validation("UNSUPPORTED", scenario.expectedIds(dataset.rows()).size(), 0, 0,
                                    List.of("No equivalent SQL text adapter; neither engine timed")), null, null, null, List.of()));
                    continue;
                }
                if (engineName.equals("voltdb")) queries.put(scenario.name(), VoltDbEngine.compile(scenario));
                Engines.Result checked = engine.run(scenario);
                Validation validation = BenchmarkMain.validate(engineName, scenario, dataset.rows(), checked);
                long deadline = System.nanoTime() + warmup * 1_000_000L;
                int count = 0;
                do {
                    blackhole = engine.run(scenario);
                    count++;
                } while (count < 3 || System.nanoTime() < deadline);
                List<Long> timings = new ArrayList<>();
                deadline = System.nanoTime() + budget * 1_000_000L;
                do {
                    start = System.nanoTime();
                    blackhole = engine.run(scenario);
                    timings.add(System.nanoTime() - start);
                } while (timings.size() < samples && (timings.size() < Math.min(5, samples) || System.nanoTime() < deadline));
                List<Long> sorted = timings.stream().sorted().toList();
                measurements.add(new Measurement(scenario.name(), scenario.group(), scenario.detail(),
                        scenario.comparable() && validation.status().equals("PASS"), checked.ids().size(), validation,
                        BenchmarkMain.percentile(sorted, .5) / 1e3, BenchmarkMain.percentile(sorted, .95) / 1e3,
                        timings.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e3, timings));
                System.out.printf(Locale.ROOT, "  %s / %s: %s, %d results; %d samples%n",
                        dataset.name(), scenario.name(), validation.status(), checked.ids().size(), timings.size());
            }
            measurements.sort(Comparator.comparing(Measurement::scenario));
            Run run = new Run(dataset.name(), dataset.description(), sha(Engines.JSON.writeValueAsBytes(dataset.rows())),
                    engineName, dataset.rows().size(), fork, "SUCCESS", null, buildMs, buildMs, null, null, null, measurements);
            Worker worker = new Worker(run, version, queries);
            Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(options.get("output")).toFile(), worker);
        }
    }

    private static Engines.Engine build(String name, List<BenchmarkRecord> rows) throws Exception {
        return name.equals("voltdb") ? new VoltDbEngine(rows) : Engines.build("kiara", rows);
    }

    static void validateWorkers(List<Worker> workers, List<String> datasets, int forks) {
        BenchmarkMain.validateRuns(workers.stream().map(Worker::run).toList(), datasets, forks, List.of("kiara", "voltdb"));
        if (workers.stream().anyMatch(w -> !w.run().status().equals("SUCCESS"))) {
            throw new IllegalStateException("A worker failed");
        }
        if (workers.stream().filter(w -> w.run().engine().equals("voltdb")).map(Worker::serverVersion).distinct().count() != 1) {
            throw new IllegalStateException("Server versions changed during run");
        }
    }

    private static Map<String, String> parse(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected --option value pairs");
        Set<String> allowed = Set.of("worker", "dataset", "datasets", "fork", "forks", "samples", "warmup-ms", "budget-ms", "output", "report");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))) {
                throw new IllegalArgumentException("Unknown option " + args[i]);
            }
            if (options.put(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option " + args[i]);
        }
        return options;
    }

    private static int positive(Map<String, String> options, String name, int fallback) {
        int result = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
