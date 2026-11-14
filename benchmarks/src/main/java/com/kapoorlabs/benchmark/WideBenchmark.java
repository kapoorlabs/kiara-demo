package com.kapoorlabs.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;
import static com.kapoorlabs.benchmark.QueryExecution.*;

public final class WideBenchmark {
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("collect", "prefix", "datasets", "forks", "output", "report", "engine", "dataset", "fork");
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected option/value pairs");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))
                    || options.put(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException("Invalid option");
        }
        if (options.containsKey("collect")) collect(options);
        else worker(options);
    }

    private static void worker(Map<String, String> options) throws Exception {
        String engineName = options.get("engine");
        if (!Set.of("kiara", "lucene").contains(engineName)) throw new IllegalArgumentException("Unknown engine");
        var data = Datasets.load(options.get("dataset"));
        if (!data.name().startsWith("wide-")) throw new IllegalArgumentException("Expected wide fixture");
        int fork = Integer.parseInt(options.get("fork"));
        if (fork < 1) throw new IllegalArgumentException("Invalid fork");
        List<Observation> observations = new ArrayList<>();
        var order = new ArrayList<>(data.scenarios());
        Collections.shuffle(order, new Random(Workloads.SEED + 1000L * fork));
        long start = System.nanoTime();
        double buildMs;
        try (var engine = Engines.build(engineName, data.rows())) {
            buildMs = (System.nanoTime() - start) / 1_000_000.0;
            for (var scenario : order) {
                Query query;
                if (engine instanceof Engines.Kiara kiara) query = () -> kiara(kiara, scenario);
                else if (engine instanceof Engines.Lucene lucene) query = lucene(lucene, scenario);
                else throw new IllegalStateException("Unexpected engine");
                var validation = validate(engineName, scenario, data.rows(), query.run().result());
                if (!validation.status().equals("PASS")) throw new IllegalStateException("Wide fixture must pass: " + scenario.name());
                warm(query, 1000, 10);
                var samples = sample(query, 101, 15, 3000);
                validate(engineName, scenario, data.rows(), query.run().result());
                observations.add(observation(scenario, validation, samples));
                System.out.printf(Locale.ROOT, "%s / %s / %s: %.3f us%n", data.name(), engineName,
                        scenario.name(), VoltExecutionBenchmark.median(samples.executionNs()) / 1000.0);
            }
        }
        observations.sort(Comparator.comparing(o -> o.query().scenario()));
        var evidence = Map.<String, Object>of("untimedBuildMs", buildMs, "queryCache", "disabled",
                "materialization", "ID-only HashMap per matching record; ID extraction outside timer");
        var trial = new SqliteBenchmark.Trial("measure", data.name(), data.description(),
                VoltExecutionBenchmark.sha(Engines.JSON.writeValueAsBytes(data.rows())), engineName, fork,
                VoltExecutionBenchmark.environment(options), observations, Map.of(), evidence);
        Path output = Path.of(options.get("output"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), trial);
    }

    private static void collect(Map<String, String> options) throws Exception {
        Path directory = Path.of(options.get("collect"));
        String prefix = options.get("prefix");
        if (prefix == null || !prefix.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Invalid prefix");
        List<String> datasets = Arrays.asList(options.get("datasets").split(","));
        int forks = Integer.parseInt(options.get("forks"));
        if (forks < 1 || new HashSet<>(datasets).size() != datasets.size()) throw new IllegalArgumentException("Invalid matrix");
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Datasets.Dataset> fixtures = new HashMap<>();
        Map<String, Map<String, Integer>> expectedCounts = new HashMap<>();
        Map<String, String> fixtureHashes = new HashMap<>();
        for (String name : datasets) {
            var data = Datasets.load(name);
            if (!name.startsWith("wide-")) throw new IllegalArgumentException("Expected wide fixture");
            fixtures.put(name, data);
            fixtureHashes.put(name, VoltExecutionBenchmark.sha(Engines.JSON.writeValueAsBytes(data.rows())));
            Map<String, Integer> counts = new HashMap<>();
            List<Map<String, Object>> scenarios = new ArrayList<>();
            for (var scenario : data.scenarios()) {
                var conditions = scenario.conditions().get();
                if (conditions.size() != conditions.stream().map(c -> c.getColumnName()).distinct().count()) {
                    throw new IllegalStateException("Repeated field is not a wider filter");
                }
                int count = scenario.expectedIds(data.rows()).size();
                if (count < 1) throw new IllegalStateException("Empty wide-filter result");
                counts.put(scenario.name(), count);
                scenarios.add(Map.of("name", scenario.name(), "group", scenario.group(), "detail", scenario.detail(),
                        "attributes", conditions.size(), "conditions", conditions, "expectedMatches", count,
                        "actualSelectivity", count / (double) data.rows().size()));
            }
            expectedCounts.put(name, counts);
            metadata.put(name, Map.of("rows", data.rows().size(), "description", data.description(),
                    "dataSha256", fixtureHashes.get(name), "scenarios", scenarios));
        }
        Map<String, JsonNode> comparisons = new LinkedHashMap<>();
        String executable = null;
        for (String comparison : List.of("voltdb", "sqlite-tuned", "sqlite-generic", "lucene")) {
            Path folder = directory.resolve(prefix + "-" + comparison);
            JsonNode comparisonData;
            if (comparison.equals("lucene")) {
                var node = Engines.JSON.createObjectNode();
                node.set("configuration", Engines.JSON.readTree(folder.resolve("platform.json").toFile()));
                var trials = node.putArray("trials");
                for (String dataset : datasets) for (int fork = 1; fork <= forks; fork++) {
                    for (String engine : List.of("kiara", "lucene")) {
                        trials.add(Engines.JSON.readTree(folder.resolve(engine + "-" + dataset + "-" + fork + ".json").toFile()));
                    }
                }
                comparisonData = node;
            } else comparisonData = Engines.JSON.readTree(folder.resolve("comparison.json").toFile());
            if (comparisonData.path("trials").size() != datasets.size() * forks * 2) throw new IllegalStateException("Incomplete trials");
            Set<String> unique = new HashSet<>();
            String opponent = comparison.startsWith("sqlite") ? "sqlite" : comparison;
            for (JsonNode trial : comparisonData.path("trials")) {
                String dataset = trial.path("dataset").asText(), engine = trial.path("engine").asText();
                var data = fixtures.get(dataset);
                if (data == null) throw new IllegalStateException("Unknown dataset");
                String hash = trial.path("environment").path("benchmarkJarSha256").asText();
                if (executable == null) executable = hash;
                if (!hash.matches("[a-f0-9]{64}") || !hash.equals(executable)) throw new IllegalStateException("Mixed executables");
                if (!datasets.contains(dataset) || !Set.of("kiara", opponent).contains(engine)
                        || trial.path("fork").asInt() < 1 || trial.path("fork").asInt() > forks
                        || !unique.add(dataset + "/" + engine + "/" + trial.path("fork").asInt())
                        || !trial.path("dataSha256").asText().equals(fixtureHashes.get(dataset))) {
                    throw new IllegalStateException("Wide trial provenance mismatch");
                }
                Set<String> found = new HashSet<>();
                for (JsonNode entry : trial.path("observations")) {
                    Observation observation = Engines.JSON.treeToValue(entry, Observation.class);
                    SqliteBenchmark.verifyObservation(observation);
                    var scenario = data.scenarios().stream().filter(s -> s.name().equals(observation.query().scenario())).findFirst().orElseThrow();
                    if (!found.add(scenario.name()) || observation.query().validation().expectedMatches() != expectedCounts.get(dataset).get(scenario.name())) {
                        throw new IllegalStateException("Incorrect oracle evidence");
                    }
                    boolean timeout = observation.query().validation().status().equals("QUERY_TIMEOUT");
                    if (timeout && !comparison.equals("sqlite-generic") || !timeout && !observation.query().comparable()) {
                        throw new IllegalStateException("Wide scenario did not pass");
                    }
                }
                if (found.size() != data.scenarios().size()) throw new IllegalStateException("Incomplete scenarios");
            }
            comparisons.put(comparison, comparisonData);
        }
        Properties versions = new Properties();
        try (var input = WideBenchmark.class.getResourceAsStream("/benchmark-versions.properties")) {
            if (input == null) throw new IllegalStateException("Missing dependency metadata");
            versions.load(input);
        }
        Map<String, String> dependencies = new LinkedHashMap<>();
        for (String key : List.of("kiara", "lucene", "sqliteJdbc")) {
            String value = versions.getProperty(key);
            if (value == null) throw new IllegalStateException("Missing dependency version " + key);
            dependencies.put(key, value);
        }
        dependencies.put("voltdbCE", "10.1.1");
        var results = Map.of("generatedAt", Instant.now().toString(), "datasets", metadata,
                "forks", forks, "comparisons", comparisons, "widths", WideWorkloads.WIDTHS, "versions", dependencies);
        JsonNode tree = Engines.JSON.valueToTree(results);
        Path raw = Path.of(options.get("output")), report = Path.of(options.get("report"));
        Files.createDirectories(raw.toAbsolutePath().getParent());
        Files.createDirectories(report.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), results);
        Files.writeString(report, WideReport.render(tree));
    }
}
