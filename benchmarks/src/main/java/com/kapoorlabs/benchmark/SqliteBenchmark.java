package com.kapoorlabs.benchmark;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;
import static com.kapoorlabs.benchmark.QueryExecution.*;
import static com.kapoorlabs.benchmark.VoltExecutionBenchmark.sha;

public final class SqliteBenchmark {
    record Audit(SqliteEngine.Candidate candidate, List<Map<String, Object>> plan, List<Map<String, Object>> bytecode,
                 Validation validation, List<Long> samplesNs, List<Long> timerPairsNs, String failure) {}
    record Choice(String selected, List<Audit> alternatives) {}
    record Trial(String phase, String dataset, String description, String dataSha256, String engine, int fork,
                 Map<String, Object> environment, List<Observation> observations,
                 Map<String, Choice> choices, Map<String, Object> evidence) {}
    record Results(String generatedAt, Map<String, Object> configuration, List<Trial> tuning, List<Trial> trials) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        SqliteEngine.Profile profile = SqliteEngine.Profile.parse(options.getOrDefault("profile", "tuned"));
        if (options.containsKey("collect")) {
            collect(options);
            return;
        }
        String phase = options.getOrDefault("phase", "measure");
        String engineName = options.getOrDefault("engine", "");
        String preparation = profile == SqliteEngine.Profile.TUNED ? "tune" : "prepare";
        if (!Set.of(preparation, "measure").contains(phase) || !Set.of("kiara", "sqlite").contains(engineName)
                || (phase.equals(preparation) && !engineName.equals("sqlite"))) {
            throw new IllegalArgumentException("Use --phase " + preparation + "|measure --engine kiara|sqlite");
        }
        Datasets.Dataset data = Datasets.load(options.get("dataset"));
        int fork = positive(options, "fork", 1);
        Map<String, Object> environment = VoltExecutionBenchmark.environment(options);
        environment.put("sqliteProfile", profile.name().toLowerCase(Locale.ROOT));
        int timeoutMs = profile == SqliteEngine.Profile.GENERIC ? positive(options, "query-timeout-ms", 60000) : 0;
        if (profile == SqliteEngine.Profile.TUNED && options.containsKey("query-timeout-ms")) {
            throw new IllegalArgumentException("Deadline is supported only by the separately reported generic profile");
        }
        environment.put("queryTimeoutMs", timeoutMs);
        String hash = sha(Engines.JSON.writeValueAsBytes(data.rows()));
        System.out.println(phase + " / " + engineName + " / " + data.name() + " / " + fork);
        List<Observation> observations = new ArrayList<>();
        Map<String, Choice> choices = new TreeMap<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        Path output = Path.of(options.get("output"));
        Path checkpoint = Path.of(output + ".checkpoint.json");
        int incarnation = 1;
        if (Files.exists(checkpoint)) {
            Trial saved = read(checkpoint);
            if (!saved.phase().equals(phase) || !saved.dataset().equals(data.name()) || !saved.engine().equals(engineName)
                    || saved.fork() != fork || !saved.dataSha256().equals(hash)
                    || !Engines.JSON.valueToTree(saved.environment()).equals(Engines.JSON.valueToTree(environment))) {
                throw new IllegalStateException("Deadline checkpoint provenance mismatch");
            }
            observations.addAll(saved.observations());
            choices.putAll(saved.choices());
            incarnation = ((Number) saved.evidence().get("workerIncarnations")).intValue() + 1;
            System.out.println("Resuming after deadline; worker incarnation " + incarnation);
        }
        evidence.put("workerIncarnations", incarnation);
        List<Workloads.Scenario> order = new ArrayList<>(data.scenarios());
        Collections.shuffle(order, new Random(Workloads.SEED + 1000L * fork));
        Trial tuning = phase.equals("measure") ? read(Path.of(options.get("tuning"))) : null;
        if (tuning != null) checkProvenance(tuning, data.name(), hash, environment);
        long buildStart = System.nanoTime();
        try (QueryDeadline deadline = new QueryDeadline(timeoutMs)) {
            if (engineName.equals("kiara")) {
                try (var engine = new Engines.Kiara(data.rows())) {
                    evidence.put("untimedBuildMs", (System.nanoTime() - buildStart) / 1_000_000.0);
                    for (var scenario : order) {
                        if (observations.stream().anyMatch(o -> o.query().scenario().equals(scenario.name()))) continue;
                        if (!VoltDbEngine.supported(scenario)) { observations.add(unsupported(scenario, data)); continue; }
                        if (!VoltExecutionEngine.timed(scenario)) {
                            Validation validation = validate(engineName, scenario, data.rows(), engine.run(scenario));
                            observations.add(notTimed(scenario, validation, "Typed object reconstruction is outside the timed scope"));
                            continue;
                        }
                        Runnable expired = expiration(checkpoint, phase, data, hash, engineName, fork,
                                environment, observations, choices, evidence, scenario, null, timeoutMs);
                        Query query = () -> deadline.run(() -> kiara(engine, scenario), expired);
                        Validation validation = validate(engineName, scenario, data.rows(), query.run().result());
                        Samples samples = measure(query, options, false);
                        validate(engineName, scenario, data.rows(), query.run().result());
                        observations.add(observation(scenario, validation, samples));
                        progress(scenario, validation, samples);
                    }
                }
            } else {
                try (var engine = new SqliteEngine(data.rows(), profile, data.name().startsWith("wide-"))) {
                    evidence.putAll(engine.evidence());
                    evidence.put("untimedBuildMs", (System.nanoTime() - buildStart) / 1_000_000.0);
                    if (tuning != null) {
                        for (String field : List.of("sqliteVersion", "sqliteSourceId", "jdbcVersion", "schema",
                                "indexes", "statistics", "compileOptions")) {
                            if (!Engines.JSON.valueToTree(tuning.evidence().get(field)).equals(Engines.JSON.valueToTree(evidence.get(field)))) {
                                throw new IllegalStateException("SQLite configuration changed after tuning: " + field);
                            }
                        }
                    }
                    for (var scenario : order) {
                        if (observations.stream().anyMatch(o -> o.query().scenario().equals(scenario.name()))
                                || (phase.equals("prepare") && choices.containsKey(scenario.name()))) continue;
                        if (!VoltDbEngine.supported(scenario)) { observations.add(unsupported(scenario, data)); continue; }
                        if (!VoltExecutionEngine.timed(scenario)) {
                            observations.add(notTimed(scenario, validate(engineName, scenario, data.rows(), engine.typed(scenario)),
                                    "Typed object reconstruction is outside the timed scope"));
                            continue;
                        }
                        List<SqliteEngine.Candidate> variants = new ArrayList<>(SqliteEngine.candidates(scenario, profile));
                        if (tuning != null) {
                            Choice choice = tuning.choices().get(scenario.name());
                            if (choice == null) throw new IllegalStateException("No frozen candidate for " + scenario.name());
                            variants.removeIf(candidate -> !candidate.name().equals(choice.selected()));
                            if (variants.size() != 1) throw new IllegalStateException("Unrecognized frozen candidate");
                        } else {
                            Collections.shuffle(variants, new Random(Workloads.SEED + scenario.name().hashCode()));
                        }
                        List<Audit> audits = new ArrayList<>();
                        for (var candidate : variants) {
                            try (var prepared = engine.prepare(candidate)) {
                                if (tuning != null) {
                                    Audit frozen = reference(tuning.choices().get(scenario.name()), profile);
                                    if (!sameCandidate(frozen.candidate(), candidate)
                                            || !Engines.JSON.valueToTree(frozen.plan()).equals(Engines.JSON.valueToTree(prepared.plan()))
                                            || !Engines.JSON.valueToTree(frozen.bytecode()).equals(Engines.JSON.valueToTree(prepared.bytecode()))) {
                                        throw new IllegalStateException("Frozen SQL or compiled plan changed: " + scenario.name());
                                    }
                                }
                                Audit timeoutAudit = new Audit(candidate, prepared.plan(), prepared.bytecode(), null,
                                        List.of(), List.of(), "QUERY_TIMEOUT");
                                Runnable expired = expiration(checkpoint, phase, data, hash, engineName, fork,
                                        environment, observations, choices, evidence, scenario, timeoutAudit, timeoutMs);
                                Query query = () -> deadline.run(() -> engine.run(prepared, scenario, phase.equals("tune")), expired);
                                try {
                                    Validation validation = validate(engineName, scenario, data.rows(), query.run().result());
                                    Samples samples = phase.equals("prepare") ? sample(query, 1, 1, 1)
                                            : measure(query, options, phase.equals("tune"));
                                    validate(engineName, scenario, data.rows(), query.run().result());
                                    audits.add(new Audit(candidate, prepared.plan(), prepared.bytecode(), validation,
                                            samples.executionNs(), samples.timerPairsNs(), null));
                                    if (phase.equals("measure")) observations.add(observation(scenario, validation, samples));
                                    System.out.print(candidate.name() + " / ");
                                    progress(scenario, validation, samples);
                                } catch (SqliteEngine.TuningTimeout timeout) {
                                    if (!phase.equals("tune")) throw timeout;
                                    System.err.println(scenario.name() + " / " + candidate.name() + ": " + timeout);
                                    audits.add(new Audit(candidate, prepared.plan(), prepared.bytecode(), null,
                                            List.of(), List.of(), timeout.toString()));
                                }
                            }
                        }
                        Audit best = audits.stream().filter(audit -> audit.failure() == null)
                                .min(Comparator.comparingDouble(audit -> VoltExecutionBenchmark.median(audit.samplesNs())))
                                .orElseThrow(() -> new IllegalStateException("All candidates failed: " + scenario.name()));
                        choices.put(scenario.name(), new Choice(best.candidate().name(), List.copyOf(audits)));
                    }
                }
            }
        }
        observations.sort(Comparator.comparing(observation -> observation.query().scenario()));
        Trial trial = new Trial(phase, data.name(), data.description(), hash, engineName, fork,
                environment, observations, choices, evidence);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), trial);
        Files.deleteIfExists(checkpoint);
        System.out.println("Saved " + output);
    }

    private static Samples measure(Query query, Map<String, String> options, boolean tuning) throws Exception {
        warm(query, tuning ? 500 : positive(options, "warmup-ms", 1000), 10);
        return sample(query, tuning ? 21 : positive(options, "samples", 101),
                tuning ? 21 : positive(options, "minimum-samples", 15), positive(options, "budget-ms", 3000));
    }

    static Audit selected(Choice choice) {
        Audit audit = choice.alternatives().stream().filter(a -> a.candidate().name().equals(choice.selected()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Invalid selected candidate"));
        if (audit.failure() != null || audit.samplesNs().isEmpty() || !audit.validation().status().equals("PASS")) {
            throw new IllegalStateException("Selected candidate lacks successful evidence");
        }
        return audit;
    }

    static Audit reference(Choice choice, SqliteEngine.Profile profile) {
        if (profile != SqliteEngine.Profile.GENERIC) return selected(choice);
        if (choice == null || choice.alternatives().size() != 1 || !choice.selected().equals("generic-original-sql")) {
            throw new IllegalStateException("Generic baseline must contain exactly the original SQL");
        }
        Audit audit = choice.alternatives().getFirst();
        if (!audit.candidate().name().equals(choice.selected())
                || (audit.failure() != null && !audit.failure().equals("QUERY_TIMEOUT"))) {
            throw new IllegalStateException("Invalid generic preflight evidence");
        }
        return audit;
    }

    private static Runnable expiration(Path checkpoint, String phase, Datasets.Dataset data, String hash, String engine,
                                       int fork, Map<String, Object> environment, List<Observation> observations,
                                       Map<String, Choice> choices, Map<String, Object> evidence,
                                       Workloads.Scenario scenario, Audit audit, int timeoutMs) {
        if (timeoutMs == 0) return () -> { throw new IllegalStateException("Unexpected deadline"); };
        List<Observation> saved = new ArrayList<>(observations);
        Validation validation = new Validation("QUERY_TIMEOUT", scenario.expectedIds(data.rows()).size(), 0, 0,
                List.of("At least " + timeoutMs + " ms; incomplete result, no usable latency sample"));
        saved.add(new Observation(new Measurement(scenario.name(), scenario.group(), scenario.detail(), false,
                null, validation, null, null, null, List.of()), List.of(),
                "Per-invocation wall deadline " + timeoutMs + " ms; worker terminated and resumed"));
        Map<String, Choice> savedChoices = new TreeMap<>(choices);
        if (audit != null) savedChoices.put(scenario.name(), new Choice(audit.candidate().name(), List.of(audit)));
        Trial timeout = new Trial(phase, data.name(), data.description(), hash, engine, fork,
                Map.copyOf(environment), List.copyOf(saved), savedChoices, Map.copyOf(evidence));
        return () -> {
            try {
                Files.createDirectories(checkpoint.toAbsolutePath().getParent());
                Path temporary = Path.of(checkpoint + ".tmp");
                Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), timeout);
                Files.move(temporary, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("QUERY_TIMEOUT: " + engine + " / " + data.name() + " / " + scenario.name()
                        + " exceeded " + timeoutMs + " ms; checkpoint saved; terminating worker");
                Runtime.getRuntime().halt(124);
            } catch (Exception failure) {
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(125);
            }
        };
    }

    static boolean sameCandidate(SqliteEngine.Candidate left, SqliteEngine.Candidate right) throws Exception {
        // JSON reloads small Long parameters as Integer; their bound SQLite type remains INTEGER.
        return Engines.JSON.writeValueAsString(left).equals(Engines.JSON.writeValueAsString(right));
    }

    private static void checkProvenance(Trial tuning, String dataset, String hash, Map<String, Object> environment) {
        String profile = environment.getOrDefault("sqliteProfile", "tuned").toString();
        if (!tuning.phase().equals(profile.equals("generic") ? "prepare" : "tune")
                || !tuning.environment().getOrDefault("sqliteProfile", "tuned").equals(profile)
                || !tuning.engine().equals("sqlite") || !tuning.dataset().equals(dataset)
                || !tuning.dataSha256().equals(hash)) throw new IllegalStateException("Tuning fixture mismatch");
        if (!Objects.equals(tuning.environment().getOrDefault("queryTimeoutMs", 0), environment.getOrDefault("queryTimeoutMs", 0))) {
            throw new IllegalStateException("Deadline configuration mismatch");
        }
        for (String key : List.of("benchmarkJarSha256", "kiara", "sqliteJdbc", "java", "vm", "os",
                "architecture", "activeProcessors", "jvmArguments")) {
            if (!Objects.equals(tuning.environment().get(key), environment.get(key))) {
                throw new IllegalStateException("Tuning/measurement runtime mismatch: " + key);
            }
        }
    }

    private static void progress(Workloads.Scenario scenario, Validation validation, Samples samples) {
        System.out.printf(Locale.ROOT, "%s: %s, p50 %.3f us, %d samples%n", scenario.name(), validation.status(),
                VoltExecutionBenchmark.median(samples.executionNs()) / 1000.0, samples.executionNs().size());
    }

    private static Trial read(Path file) throws Exception { return Engines.JSON.readValue(file.toFile(), Trial.class); }

    private static int positive(Map<String, String> options, String name, int fallback) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected --option value pairs");
        Set<String> allowed = Set.of("phase", "engine", "dataset", "fork", "output", "tuning", "samples",
                "minimum-samples", "warmup-ms", "budget-ms", "collect", "report", "forks", "datasets", "profile", "query-timeout-ms");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))
                    || result.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate option: " + args[i]);
            }
        }
        return result;
    }

    static void verifyObservation(Observation observation) {
        Measurement m = observation.query();
        if (!Set.of("PASS", "UNSUPPORTED", "KNOWN_DECIMAL_TRUNCATION", "QUERY_TIMEOUT").contains(m.validation().status())
                || m.samplesNs().isEmpty() != (observation.notTimedReason() != null)
                || m.samplesNs().size() != observation.timerPairsNs().size()
                || m.samplesNs().stream().anyMatch(ns -> ns <= 0) || observation.timerPairsNs().stream().anyMatch(ns -> ns < 0)) {
            throw new IllegalStateException("Invalid result evidence: " + m.scenario());
        }
        if (!m.samplesNs().isEmpty()) {
            List<Long> sorted = m.samplesNs().stream().sorted().toList();
            if (m.p50Us() == null || m.p95Us() == null || m.meanUs() == null
                    || m.p50Us() != percentile(sorted, .5) / 1000.0
                    || m.p95Us() != percentile(sorted, .95) / 1000.0
                    || m.meanUs() != sorted.stream().mapToLong(Long::longValue).average().orElseThrow() / 1000.0) {
                throw new IllegalStateException("Incorrect percentile or mean: " + m.scenario());
            }
        } else if (m.comparable() || m.p50Us() != null || m.p95Us() != null || m.meanUs() != null) {
            throw new IllegalStateException("Untimed result has usable timing");
        }
        if (m.validation().status().equals("QUERY_TIMEOUT")
                && (m.matches() != null || !m.samplesNs().isEmpty() || m.comparable())) {
            throw new IllegalStateException("Timeout must not contain a result count or usable timing");
        }
    }

    private static void collect(Map<String, String> options) throws Exception {
        SqliteEngine.Profile profile = SqliteEngine.Profile.parse(options.getOrDefault("profile", "tuned"));
        Path directory = Path.of(options.get("collect"));
        List<String> datasets = Arrays.asList(options.getOrDefault("datasets", Datasets.DEFAULTS).split(","));
        int forks = positive(options, "forks", 5);
        List<Trial> tuning = new ArrayList<>();
        List<Trial> trials = new ArrayList<>();
        for (String dataset : datasets) {
            Trial tuned = read(directory.resolve((profile == SqliteEngine.Profile.TUNED ? "tune-" : "prepare-") + dataset + ".json"));
            if (!tuned.environment().getOrDefault("sqliteProfile", "tuned")
                    .equals(profile.name().toLowerCase(Locale.ROOT))) throw new IllegalStateException("Profile mismatch");
            if (profile == SqliteEngine.Profile.GENERIC && !Objects.equals(tuned.environment().get("queryTimeoutMs"),
                    positive(options, "query-timeout-ms", 60000))) throw new IllegalStateException("Collected deadline mismatch");
            tuning.add(tuned);
            Datasets.Dataset data = Datasets.load(dataset);
            String expectedHash = sha(Engines.JSON.writeValueAsBytes(data.rows()));
            Set<String> names = new TreeSet<>();
            data.scenarios().forEach(s -> names.add(s.name()));
            for (int fork = 1; fork <= forks; fork++) {
                for (String engine : List.of("kiara", "sqlite")) {
                    Trial trial = read(directory.resolve(engine + "-" + dataset + "-" + fork + ".json"));
                    checkProvenance(tuned, dataset, expectedHash, trial.environment());
                    if (!trial.phase().equals("measure") || !trial.dataset().equals(dataset) || !trial.engine().equals(engine)
                            || trial.fork() != fork || !trial.dataSha256().equals(expectedHash)) {
                        throw new IllegalStateException("Trial provenance mismatch");
                    }
                    Set<String> actual = new TreeSet<>();
                    for (Observation observation : trial.observations()) {
                        if (!actual.add(observation.query().scenario())) throw new IllegalStateException("Duplicate scenario");
                        verifyObservation(observation);
                        var scenario = data.scenarios().stream().filter(s -> s.name().equals(observation.query().scenario())).findFirst().orElseThrow();
                        boolean timed = VoltExecutionEngine.timed(scenario);
                        boolean timeout = observation.query().validation().status().equals("QUERY_TIMEOUT");
                        if ((timeout && (profile != SqliteEngine.Profile.GENERIC || !timed))
                                || (timed && !timeout) != !observation.query().samplesNs().isEmpty()
                                || observation.query().comparable() != (timed && scenario.comparable()
                                && observation.query().validation().status().equals("PASS"))) {
                            throw new IllegalStateException("Incorrect scenario eligibility");
                        }
                        if (timed && engine.equals("sqlite")) {
                            Audit original = reference(tuned.choices().get(scenario.name()), profile);
                            Audit measured = reference(trial.choices().get(scenario.name()), profile);
                            if (!original.candidate().equals(measured.candidate()) || !original.plan().equals(measured.plan())
                                    || !original.bytecode().equals(measured.bytecode())
                                    || !measured.samplesNs().equals(observation.query().samplesNs())) {
                                throw new IllegalStateException("Frozen query evidence mismatch");
                            }
                        }
                    }
                    if (!actual.equals(names)) throw new IllegalStateException("Incomplete scenario set");
                    trials.add(trial);
                }
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("datasets", datasets);
        config.put("forks", forks);
        config.put("profile", profile.name().toLowerCase(Locale.ROOT));
        config.put("queryTimeoutMs", profile == SqliteEngine.Profile.GENERIC ? positive(options, "query-timeout-ms", 60000) : 0);
        config.put("storage", profile == SqliteEngine.Profile.TUNED
                ? "Private connection-local in-memory SQLite; full ANALYZE; prepared statements"
                : "Private connection-local in-memory SQLite; no ANALYZE; generic indexes; prepared statements");
        config.put("timing", "Execution plus eager Java maps for both engines; SQLite JDBC/JNI and cursor closure included; ID extraction excluded");
        config.put("tuning", profile == SqliteEngine.Profile.TUNED
                ? "Lowest median of validated SQL alternatives; frozen before fresh measurement; tuning-only 10-second progress-handler deadline"
                : "None: original SQL only; untimed preflight validation and plan capture; no candidate selection");
        config.put("platform", Engines.JSON.readTree(directory.resolve("platform.json").toFile()));
        Results result = new Results(Instant.now().toString(), config, tuning, trials);
        Path raw = Path.of(options.get("output"));
        Path report = Path.of(options.get("report"));
        Files.createDirectories(raw.toAbsolutePath().getParent());
        Files.createDirectories(report.toAbsolutePath().getParent());
        Engines.JSON.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), result);
        Files.writeString(report, datasets.stream().allMatch(name -> name.startsWith("wide-"))
                ? WideReport.component() : SqliteReport.render(result));
        System.out.println("Wrote " + raw + " and " + report);
    }
}
