package com.kapoorlabs.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.kapoorlabs.kiara.domain.KeywordSearchResult;
import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.exception.IndexingException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class BenchmarkMain {
    private static volatile Object blackhole;

    record Validation(String status, int expectedMatches, int missing, int extra, List<String> examples) {}
    record Measurement(String scenario, String group, String detail, boolean comparable, Integer matches, Validation validation,
                       Double p50Us, Double p95Us, Double meanUs, List<Long> samplesNs) {}
    record Run(String dataset, String description, String dataSha256, String engine, int rows, int fork, String status,
               String failure, Double buildMs, double buildAttemptMs, Long heapBeforeBytes,
               Long heapAfterBytes, Long retainedHeapBytes, List<Measurement> measurements) {}
    record Results(String generatedAt, Map<String, Object> environment, Map<String, Object> configuration, List<Run> runs) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        if (options.containsKey("worker")) {
            worker(options);
            return;
        }
        int forks = positive(options, "forks", 3);
        int samples = positive(options, "samples", 61);
        int warmupMs = positive(options, "warmup-ms", 50);
        int budgetMs = positive(options, "budget-ms", 1000);
        if (options.containsKey("sizes") && options.containsKey("datasets")) {
            throw new IllegalArgumentException("Use --datasets or legacy --sizes, not both");
        }
        List<String> datasets = options.containsKey("sizes")
                ? Arrays.stream(options.get("sizes").split(",")).map(n -> "mixed-" + n).toList()
                : Arrays.asList(options.getOrDefault("datasets", Datasets.DEFAULTS).split(","));
        if (new HashSet<>(datasets).size() != datasets.size()) throw new IllegalArgumentException("Duplicate dataset names");
        for (String dataset : datasets) Datasets.load(dataset);
        Path raw = Path.of(options.getOrDefault("output", "benchmarks\\lucene-comparison.json")).toAbsolutePath();
        Path report = Path.of(options.getOrDefault("report", "docs\\lucene-comparison.md")).toAbsolutePath();
        String jar = Path.of(BenchmarkMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        if (!jar.endsWith(".jar")) throw new IllegalStateException("Run the packaged executable jar, not class files");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.isExecutable(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path temp = Files.createTempDirectory("kiara-lucene-");
        List<Run> runs = new ArrayList<>();
        boolean complete = false;
        try {
            for (String dataset : datasets) {
                for (int fork = 1; fork <= forks; fork++) {
                    for (String engine : fork % 2 == 1 ? List.of("kiara", "lucene") : List.of("lucene", "kiara")) {
                        Path output = temp.resolve(engine + "-" + dataset + "-" + fork + ".json");
                        Path error = temp.resolve(engine + "-" + dataset + "-" + fork + ".stderr.txt");
                        Process process = new ProcessBuilder(java, "-Xms256m", "-Xmx4g", "-XX:+UseG1GC", "-Duser.timezone=UTC",
                                "--add-modules=jdk.incubator.vector",
                                "-jar", jar, "--worker", engine, "--dataset", dataset, "--fork", Integer.toString(fork),
                                "--samples", Integer.toString(samples), "--warmup-ms", Integer.toString(warmupMs),
                                "--budget-ms", Integer.toString(budgetMs),
                                "--output", output.toString()).inheritIO().redirectError(error.toFile()).start();
                        int exit = process.waitFor();
                        String stderr = Files.readString(error);
                        System.err.print(stderr);
                        if (exit != 0) throw new IllegalStateException(engine + " worker failed with exit code " + exit);
                        Run run = Engines.JSON.readValue(output.toFile(), Run.class);
                        if (!run.status().equals("SUCCESS")) {
                            run = new Run(run.dataset(), run.description(), run.dataSha256(), run.engine(), run.rows(),
                                    run.fork(), run.status(), run.failure() + "\n" + stderr, null, run.buildAttemptMs(),
                                    run.heapBeforeBytes(), null, null, run.measurements());
                        }
                        runs.add(run);
                    }
                }
            }
            complete = true;
        } finally {
            if (complete) {
                // Only delete this invocation's named intermediate files, never a shared directory tree.
                try (var files = Files.list(temp)) {
                    for (Path file : files.toList()) Files.delete(file);
                }
                Files.delete(temp);
            } else {
                System.err.println("Incomplete benchmark; completed worker JSON and errors preserved at " + temp);
            }
        }
        validateRuns(runs, datasets, forks);
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java", System.getProperty("java.runtime.version"));
        environment.put("vendor", System.getProperty("java.vendor"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        environment.put("cpu", System.getenv().getOrDefault("BENCHMARK_CPU_NAME",
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "not reported")));
        Properties versions = new Properties();
        try (var input = BenchmarkMain.class.getResourceAsStream("/benchmark-versions.properties")) {
            if (input == null) throw new IllegalStateException("Missing dependency version metadata");
            versions.load(input);
        }
        environment.put("kiara", versions.getProperty("kiara"));
        environment.put("lucene", versions.getProperty("lucene"));
        environment.put("benchmarkJarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(jar)))));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("seed", Workloads.SEED);
        config.put("datasets", datasets);
        config.put("forksPerEngineAndDataset", forks);
        config.put("maximumSamplesPerScenario", samples);
        config.put("minimumSamplesPerScenario", Math.min(5, samples));
        config.put("measurementBudgetMsPerScenario", budgetMs);
        config.put("minimumWarmupMsPerScenario", warmupMs);
        config.put("minimumWarmupQueriesPerScenario", 3);
        config.put("retailCsvSha256", Datasets.RETAIL_SHA256);
        config.put("libraryBuildFailurePolicy", "Record Kiara IndexingException as BUILD_FAILED; continue other workers. No latency or heap estimate for failed builds.");
        config.put("workerJvmArgs", List.of("-Xms256m", "-Xmx4g", "-XX:+UseG1GC", "-Duser.timezone=UTC",
                "--add-modules=jdk.incubator.vector"));
        Results result = new Results(Instant.now().toString(), environment, config, runs);
        Files.createDirectories(raw.getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), result);
        Files.createDirectories(report.getParent());
        Files.writeString(report, Report.render(result));
        System.out.println("Wrote " + raw + " and " + report);
    }

    private static void worker(Map<String, String> options) throws Exception {
        String engineName = options.get("worker");
        Datasets.Dataset dataset = Datasets.load(options.getOrDefault("dataset", "mixed-" + positive(options, "size", 10000)));
        int size = dataset.rows().size();
        int fork = positive(options, "fork", 1);
        int samples = positive(options, "samples", 61);
        int warmupMs = positive(options, "warmup-ms", 50);
        int budgetMs = positive(options, "budget-ms", 1000);
        System.out.printf(Locale.ROOT, "%s: %s, fork %d%n", engineName, dataset.name(), fork);
        List<BenchmarkRecord> rows = dataset.rows();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Engines.JSON.writeValueAsBytes(rows)));
        try (Engines.Engine priming = Engines.build(engineName, rows.subList(0, 64))) {
            blackhole = priming.run(dataset.scenarios().getFirst());
        }
        blackhole = null;
        long before = retainedHeap();
        long start = System.nanoTime();
        Engines.Engine built;
        try {
            built = Engines.build(engineName, rows);
        } catch (IndexingException error) {
            if (!engineName.equals("kiara")) throw error;
            double elapsed = (System.nanoTime() - start) / 1e6;
            List<Measurement> blocked = dataset.scenarios().stream().map(s -> new Measurement(s.name(), s.group(), s.detail(),
                    false, null, new Validation("BUILD_FAILED", s.expectedIds(rows).size(), 0, 0, List.of()),
                    null, null, null, List.<Long>of())).sorted(Comparator.comparing(Measurement::scenario)).toList();
            Run failure = new Run(dataset.name(), dataset.description(), fingerprint, engineName, size, fork, "BUILD_FAILED",
                    error.getClass().getName() + ": " + error.getMessage(), null, elapsed, before, null, null, blocked);
            Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(options.get("output")).toFile(), failure);
            System.err.println("BUILD_FAILED: " + dataset.name() + " / " + engineName + "; no query timings recorded");
            return;
        }
        try (Engines.Engine engine = built) {
            double buildMs = (System.nanoTime() - start) / 1e6;
            long after = retainedHeap();
            List<Measurement> measurements = new ArrayList<>();
            List<Workloads.Scenario> order = new ArrayList<>(dataset.scenarios());
            Collections.shuffle(order, new Random(Workloads.SEED + fork));
            for (Workloads.Scenario scenario : order) {
                Engines.Result checked = engine.run(scenario);
                Validation validation = validate(engineName, scenario, rows, checked);
                if (!validation.status().equals("PASS")) {
                    System.out.println(engineName + " / " + scenario.name() + ": " + validation);
                }
                long deadline = System.nanoTime() + warmupMs * 1_000_000L;
                int warmups = 0;
                do {
                    blackhole = engine.run(scenario);
                    warmups++;
                } while (warmups < 3 || System.nanoTime() < deadline);
                List<Long> timings = new ArrayList<>(samples);
                deadline = System.nanoTime() + budgetMs * 1_000_000L;
                do {
                    start = System.nanoTime();
                    blackhole = engine.run(scenario);
                    timings.add(System.nanoTime() - start);
                } while (timings.size() < samples && (timings.size() < Math.min(5, samples) || System.nanoTime() < deadline));
                List<Long> sorted = timings.stream().sorted().toList();
                measurements.add(new Measurement(scenario.name(), scenario.group(), scenario.detail(),
                        scenario.comparable() && validation.status().equals("PASS"),
                        checked.ids().size(), validation, percentile(sorted, .5) / 1e3, percentile(sorted, .95) / 1e3,
                        timings.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e3, timings));
                System.out.printf(Locale.ROOT, "  %s / %s: %d results; %d samples%n",
                        dataset.name(), scenario.name(), checked.ids().size(), timings.size());
            }
            measurements.sort(Comparator.comparing(Measurement::scenario));
            Run run = new Run(dataset.name(), dataset.description(), fingerprint, engineName, size, fork,
                    "SUCCESS", null, buildMs, buildMs, before, after, after - before, measurements);
            Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(options.get("output")).toFile(), run);
        }
    }

    static Validation validate(String engine, Workloads.Scenario scenario, List<BenchmarkRecord> rows, Engines.Result result) {
        List<String> actual = result.ids().stream().sorted().toList();
        if (new HashSet<>(actual).size() != actual.size()) throw new AssertionError(scenario.name() + ": duplicate results");
        if (scenario.mode().equals("predict") || scenario.mode().equals("suggest")) {
            Set<String> dictionary = new TreeSet<>();
            rows.forEach(r -> Workloads.values(r, "name").stream().filter(v -> !v.equals(Workloads.NULL))
                    .map(v -> v.toLowerCase(Locale.ROOT)).forEach(dictionary::add));
            List<String> wanted = dictionary.stream().filter(word -> scenario.mode().equals("predict")
                    ? word.startsWith(scenario.text()) : editDistance(word, scenario.text()) <= 1).toList();
            int limit = scenario.mode().equals("suggest") && engine.equals("kiara") ? 1 : 10;
            if (actual.size() != Math.min(limit, wanted.size()) || !wanted.containsAll(actual)) {
                throw new AssertionError(scenario.name() + ": expected valid dictionary candidates " + wanted + ", got " + actual);
            }
            return new Validation("PASS", actual.size(), 0, 0, List.of());
        }
        List<String> expected = scenario.expectedIds(rows);
        Validation validation = new Validation("PASS", expected.size(), 0, 0, List.of());
        if (scenario.mode().equals("keyword")) {
            Set<String> validIds = new HashSet<>();
            rows.stream().filter(r -> scenario.conditions().get().stream().allMatch(c -> Workloads.matches(r, c)))
                    .map(BenchmarkRecord::getId).forEach(validIds::add);
            if (!validIds.containsAll(actual)) throw new AssertionError(scenario.name() + ": invalid ID or hard-filter violation");
        }
        if (scenario.name().equals("keyword-best-subset")) {
            if (result.payload() instanceof KeywordSearchResult<?> keywordResult) {
                Set<String> keywords = keywordResult.getKeywords();
                if (keywords.size() != 1) throw new AssertionError("Best subset should match exactly one keyword");
                String chosen = keywords.iterator().next();
                expected = rows.stream().filter(r -> chosen.equals("quartz") ? r.getName().equals("quartz")
                        : Workloads.canonical(chosen).equals("boot") && r.getKeywords().endsWith("boots"))
                        .map(BenchmarkRecord::getId).sorted().toList();
            }
        } else if (scenario.limit() > 0) {
            if (actual.size() != Math.min(scenario.limit(), expected.size()) || !expected.containsAll(actual)) {
                throw new AssertionError(scenario.name() + ": invalid limited subset");
            }
            return new Validation("PASS", actual.size(), 0, 0, List.of());
        }
        if (!actual.equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            if (engine.equals("kiara") && scenario.name().startsWith("decimal-")) {
                List<String> truncated = rows.stream().filter(r -> scenario.conditions().get().stream()
                        .allMatch(c -> c.getColumnName().equals("price") ? truncatedPrice(r, c) : Workloads.matches(r, c)))
                        .map(BenchmarkRecord::getId).sorted().toList();
                if (actual.equals(truncated)) {
                    return new Validation("KNOWN_DECIMAL_TRUNCATION", expected.size(), missing.size(), extra.size(),
                            java.util.stream.Stream.concat(missing.stream(), extra.stream()).limit(5).toList());
                }
            }
            if (scenario.mode().equals("keyword")) {
                validation = new Validation("TEXT_RESULT_DIFFERENCE", expected.size(), missing.size(), extra.size(),
                        java.util.stream.Stream.concat(missing.stream(), extra.stream()).limit(5).toList());
            } else {
                throw new AssertionError(scenario.name() + ": expected " + expected.size() + " results, got " + actual.size()
                        + "; missing=" + missing.stream().limit(5).toList() + "; extra=" + extra.stream().limit(5).toList());
            }
        }
        if (scenario.mode().equals("projection") || scenario.mode().equals("typed") || scenario.mode().equals("keyword")) {
            Object payload = result.payload() instanceof KeywordSearchResult<?> keywordResult
                    ? keywordResult.getResult() : result.payload();
            JsonNode array = Engines.JSON.valueToTree(payload);
            Map<String, BenchmarkRecord> byId = new HashMap<>();
            rows.forEach(r -> byId.put(r.getId(), r));
            for (JsonNode node : array) {
                if (scenario.mode().equals("projection")) {
                    if (node.size() != 3 || !node.has("ID") || !node.has("CATEGORY") || !node.has("PRICE")) {
                        throw new AssertionError("Projection must have exactly ID, CATEGORY and PRICE");
                    }
                    BenchmarkRecord source = byId.get(node.get("ID").asText());
                    if (!(node.get("CATEGORY").isNull() ? source.getCategory() == null : node.get("CATEGORY").asText().equals(source.getCategory()))
                            || Double.compare(Double.parseDouble(node.get("PRICE").asText()), source.getPrice()) != 0) {
                        throw new AssertionError("Incorrect projected values");
                    }
                } else {
                    BenchmarkRecord source = byId.get(node.get("id").asText());
                    JsonNode wanted = Engines.JSON.valueToTree(source);
                    // Kiara reconstructs case-insensitive/stemmed fields in indexed (normalized) form.
                    for (String field : FIELDS_TO_CHECK) {
                        if (!Objects.equals(node.get(field), wanted.get(field))) {
                            throw new AssertionError(scenario.name() + ": incorrect typed field " + field);
                        }
                    }
                    if (source.getBrand() != null && !node.get("brand").asText().equalsIgnoreCase(source.getBrand())) {
                        throw new AssertionError(scenario.name() + ": incorrect case-insensitive value");
                    }
                    for (String field : LIST_FIELDS_TO_CHECK) {
                        if (!collectionValues(field, node.get(field)).equals(collectionValues(field, wanted.get(field)))) {
                            throw new AssertionError(scenario.name() + ": incorrect collection field " + field);
                        }
                    }
                }
            }
        }
        return validation;
    }

    private static final List<String> FIELDS_TO_CHECK = List.of("id", "category", "quantity", "price", "serial",
            "active", "optional", "date", "timestamp", "band", "route", "window", "timeWindow", "description", "status", "region", "tier");
    private static final List<String> LIST_FIELDS_TO_CHECK = List.of("tags", "numbers", "dates", "timestamps",
            "bands", "windows", "timeWindows", "keywords", "name");

    private static boolean truncatedPrice(BenchmarkRecord row, Condition condition) {
        long price = row.getPrice().longValue();
        if (condition.getOperator() == com.kapoorlabs.kiara.domain.Operator.BETWEEN) {
            return price >= (long) Double.parseDouble(condition.getLowerValue())
                    && price <= (long) Double.parseDouble(condition.getUpperValue());
        }
        long threshold = (long) Double.parseDouble(condition.getValue().getFirst());
        return switch (condition.getOperator()) {
            case LESS_THAN -> price < threshold;
            case LESS_THAN_EQUAL -> price <= threshold;
            case GREATER_THAN -> price > threshold;
            case GREATER_THAN_EQUAL -> price >= threshold;
            case EQUAL -> price == threshold;
            default -> throw new AssertionError("Unexpected decimal operator " + condition.getOperator());
        };
    }

    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return previous[b.length()];
    }

    static void validateRuns(List<Run> runs, List<String> datasets, int forks) {
        validateRuns(runs, datasets, forks, List.of("kiara", "lucene"));
    }

    static void validateRuns(List<Run> runs, List<String> datasets, int forks, List<String> engines) {
        if (runs.size() != datasets.size() * forks * engines.size()) throw new IllegalStateException("Incomplete dataset/engine/fork matrix");
        for (String dataset : datasets) {
            List<Run> selected = runs.stream().filter(r -> r.dataset().equals(dataset)).toList();
            if (selected.isEmpty() || selected.stream().map(Run::dataSha256).distinct().count() != 1
                    || selected.stream().map(Run::rows).distinct().count() != 1) {
                throw new IllegalStateException("Input data differs across workers for " + dataset);
            }
            Set<String> names = new HashSet<>();
            selected.getFirst().measurements().forEach(m -> names.add(m.scenario()));
            for (String engine : engines) {
                List<Run> engineRuns = selected.stream().filter(r -> r.engine().equals(engine)).toList();
                if (engineRuns.size() != forks || engineRuns.stream().map(Run::fork).distinct().count() != forks) {
                    throw new IllegalStateException("Missing or repeated engine fork for " + dataset + " / " + engine);
                }
                for (Run run : engineRuns) {
                    Set<String> measured = new HashSet<>();
                    run.measurements().forEach(m -> measured.add(m.scenario()));
                    if (!measured.equals(names) || measured.size() != run.measurements().size()) {
                        throw new IllegalStateException("Query set differs across workers for " + dataset);
                    }
                }
            }
        }
    }

    private static Set<String> collectionValues(String field, JsonNode value) {
        if (value == null || value.isNull()) return Set.of(Workloads.NULL);
        Set<String> result = new TreeSet<>();
        for (String token : value.asText().split(",")) {
            result.add(field.equals("keywords") ? Workloads.canonical(token)
                    : field.equals("numbers") ? new java.math.BigDecimal(token).stripTrailingZeros().toPlainString() : token);
        }
        return result;
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty() || p <= 0 || p > 1) throw new IllegalArgumentException("Invalid percentile input");
        return sorted.get((int) Math.ceil(p * sorted.size()) - 1);
    }

    private static long retainedHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(60);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static int positive(Map<String, String> options, String key, int fallback) {
        int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback)));
        if (value <= 0) throw new IllegalArgumentException("--" + key + " must be positive");
        return value;
    }

    private static Map<String, String> options(String[] args) {
        Set<String> allowed = Set.of("worker", "size", "sizes", "dataset", "datasets", "fork", "forks", "samples",
                "warmup-ms", "budget-ms", "output", "report");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !args[i].startsWith("--") || !allowed.contains(args[i].substring(2))) {
                throw new IllegalArgumentException("Expected --option value; allowed options: " + allowed);
            }
            if (values.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option " + args[i]);
            }
        }
        return values;
    }
}
