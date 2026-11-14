package com.kapoorlabs.benchmark;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.kapoorlabs.benchmark.Workloads.*;
import static com.kapoorlabs.kiara.domain.Operator.*;

class SqliteTest {
    @Test
    void deadlineWinsRequireComparableCompletionAgainstUnanimousTimeouts() {
        java.util.function.BiFunction<String, Boolean, SqliteBenchmark.Trial> trial = (status, comparable) -> {
            boolean timeout = status.equals("QUERY_TIMEOUT");
            var validation = new BenchmarkMain.Validation(status, 1, 0, 0, List.of());
            var measurement = new BenchmarkMain.Measurement("query", "Lists", "test", comparable,
                    timeout ? null : 1, validation, timeout ? null : 1.0, timeout ? null : 1.0,
                    timeout ? null : 1.0, timeout ? List.of() : List.of(1000L));
            var observation = new QueryExecution.Observation(measurement,
                    timeout ? List.of() : List.of(0L), timeout ? "Deadline" : null);
            return new SqliteBenchmark.Trial("measure", "test", "test", "hash", "test", 1,
                    Map.of(), List.of(observation), Map.of(), Map.of());
        };
        var pass = trial.apply("PASS", true);
        var timeout = trial.apply("QUERY_TIMEOUT", false);
        var excluded = trial.apply("PASS", false);
        assertTrue(SqliteReport.deadlineWin(List.of(pass, pass), List.of(timeout, timeout), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(pass, pass), List.of(timeout, pass), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(pass, timeout), List.of(timeout, timeout), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(pass, excluded), List.of(timeout, timeout), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(timeout), List.of(timeout), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(pass), List.of(pass), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(pass), List.of(timeout, timeout), "query"));
        assertFalse(SqliteReport.deadlineWin(List.of(), List.of(), "query"));
    }

    @Test
    void timeoutEvidenceCannotMasqueradeAsSuccessfulLatencyOrSelectedTuning() {
        var validation = new BenchmarkMain.Validation("QUERY_TIMEOUT", 7, 0, 0, List.of("Incomplete"));
        var measurement = new BenchmarkMain.Measurement("excluded", "Lists", "Exclusion", false,
                null, validation, null, null, null, List.of());
        var observation = new QueryExecution.Observation(measurement, List.of(), "Per-invocation wall deadline");
        assertDoesNotThrow(() -> SqliteBenchmark.verifyObservation(observation));
        var fabricated = new BenchmarkMain.Measurement("excluded", "Lists", "Exclusion", false,
                7, validation, null, null, null, List.of());
        assertThrows(IllegalStateException.class, () -> SqliteBenchmark.verifyObservation(
                new QueryExecution.Observation(fabricated, List.of(), "Per-invocation wall deadline")));
        var candidate = new SqliteEngine.Candidate("generic-original-sql",
                new VoltDbEngine.Sql("SELECT id FROM records", List.of()));
        var audit = new SqliteBenchmark.Audit(candidate, List.of(), List.of(), null, List.of(), List.of(), "QUERY_TIMEOUT");
        var choice = new SqliteBenchmark.Choice(candidate.name(), List.of(audit));
        assertSame(audit, SqliteBenchmark.reference(choice, SqliteEngine.Profile.GENERIC));
        assertThrows(IllegalStateException.class, () -> SqliteBenchmark.reference(choice, SqliteEngine.Profile.TUNED));
    }

    @Test
    void wallDeadlineFiresOnceAndCancelledTimersCannotExpireLaterQueries() throws Exception {
        var expired = new java.util.concurrent.CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var deadline = new QueryDeadline(25)) {
            deadline.run(() -> {
                assertTrue(expired.await(2, java.util.concurrent.TimeUnit.SECONDS));
                return new QueryExecution.Sample(1, 0, new Engines.Result(List.of(), List.of()));
            }, () -> { count.incrementAndGet(); expired.countDown(); });
            assertEquals(1, count.get());
        }
        try (var deadline = new QueryDeadline(500)) {
            deadline.run(() -> new QueryExecution.Sample(1, 0, new Engines.Result(List.of(), List.of())), count::incrementAndGet);
        }
        assertEquals(1, count.get());
    }

    @Test
    void genericProfileHasExactlyBasicIndexesNoAnalyzeAndOnlyOriginalSql() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SqliteEngine.Profile.parse("unknown"));
        for (String name : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
            var data = Datasets.load(name);
            try (var engine = new SqliteEngine(data.rows(), SqliteEngine.Profile.GENERIC)) {
                var before = Engines.JSON.valueToTree(engine.evidence());
                assertEquals(26, before.get("indexes").size());
                assertFalse(before.get("analyzed").asBoolean());
                assertTrue(before.get("statistics").isEmpty());
                assertFalse(before.get("indexes").has("range_upper"));
                before.get("indexes").fieldNames().forEachRemaining(index -> assertFalse(index.startsWith("composite_")));
                for (var scenario : data.scenarios()) {
                    if (!VoltDbEngine.supported(scenario)) continue;
                    if (scenario.mode().equals("typed")) {
                        assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(), engine.typed(scenario)).status());
                        continue;
                    }
                    var candidates = SqliteEngine.candidates(scenario, SqliteEngine.Profile.GENERIC);
                    assertEquals(1, candidates.size());
                    assertEquals("generic-original-sql", candidates.getFirst().name());
                    assertEquals(VoltDbEngine.compile(scenario), candidates.getFirst().sql());
                    try (var prepared = engine.prepare(candidates.getFirst())) {
                        assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(),
                                engine.run(prepared, scenario, false).result()).status());
                    }
                }
                var after = Engines.JSON.valueToTree(engine.evidence());
                assertEquals(before.get("schema"), after.get("schema"));
                assertFalse(after.get("analyzed").asBoolean());
                assertTrue(after.get("statistics").isEmpty());
            }
        }
    }

    @Test
    void nativeSqliteCandidatesMatchAllStructuredFamiliesAndKeepPreparedResultsReusable() throws Exception {
        Set<com.kapoorlabs.kiara.domain.Operator> operators = new HashSet<>();
        for (String name : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
            var data = Datasets.load(name);
            try (var engine = new SqliteEngine(data.rows())) {
                for (var scenario : data.scenarios()) {
                    if (!VoltDbEngine.supported(scenario)) {
                        assertThrows(IllegalArgumentException.class, () -> SqliteEngine.candidates(scenario));
                        continue;
                    }
                    scenario.conditions().get().forEach(condition -> operators.add(condition.getOperator()));
                    if (scenario.mode().equals("typed")) {
                        assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(), engine.typed(scenario)).status());
                        continue;
                    }
                    for (var candidate : SqliteEngine.candidates(scenario)) {
                        assertEquals(candidate.sql().parameters().size(), candidate.sql().text().chars().filter(c -> c == '?').count());
                        try (var query = engine.prepare(candidate)) {
                            assertFalse(query.plan().isEmpty());
                            assertFalse(query.bytecode().isEmpty());
                            for (boolean tuning : List.of(true, false)) {
                                var result = engine.run(query, scenario, tuning);
                                assertTrue(result.executionNs() > 0);
                                assertTrue(result.timerPairNs() >= 0);
                                assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(), result.result()).status(),
                                        name + "/" + scenario.name() + "/" + candidate.name());
                                var maps = Engines.JSON.valueToTree(result.result().payload());
                                for (var row : maps) assertEquals(scenario.mode().equals("projection") ? 3 : 1, row.size());
                            }
                        }
                    }
                }
                var evidence = Engines.JSON.valueToTree(engine.evidence());
                assertEquals("3.53.4", evidence.get("sqliteVersion").asText());
                assertEquals("", evidence.get("databases").get(0).get("file").asText());
                assertTrue(evidence.get("indexes").size() >= 36);
                assertFalse(evidence.get("statistics").isEmpty());
                assertEquals(2, evidence.get("temp_store").get(0).get("temp_store").asInt());
            }
        }
        assertEquals(EnumSet.allOf(com.kapoorlabs.kiara.domain.Operator.class), operators);
    }

    @Test
    void intervalGapsNullsAllMembershipAndPrefixesRemainEquivalent() throws Exception {
        BenchmarkRecord row = new BenchmarkRecord();
        row.setId("split");
        row.setBands("10-20,30-40");
        row.setRoute("AA10-20");
        row.setTags("red,blue");
        row.setPrice(19.99);
        BenchmarkRecord missing = new BenchmarkRecord();
        missing.setId("missing");
        missing.setPrice(19.50);
        List<BenchmarkRecord> rows = List.of(row, missing);
        List<Scenario> scenarios = new ArrayList<>();
        for (int point : new int[]{9, 10, 20, 25, 30, 40, 41}) {
            scenarios.add(exact("point-" + point, "Ranges", "paired endpoints",
                    () -> List.of(c("bands", CONTAINS_EITHER, Integer.toString(point))),
                    r -> r == row && (point >= 10 && point <= 20 || point >= 30 && point <= 40)));
        }
        scenarios.add(exact("all", "Ranges", "different stored intervals may each satisfy a required point",
                () -> List.of(c("bands", CONTAINS_ALL, "10", "40")), r -> r == row));
        scenarios.add(exact("wrong-prefix", "Ranges", "prefix must match",
                () -> List.of(c("route", CONTAINS_EITHER, "BB15")), r -> false));
        scenarios.add(exact("missing", "Lists", "null inclusion",
                () -> List.of(c("tags", CONTAINS_EITHER_INCLUDING_NULL, "red")), r -> true));
        scenarios.add(exact("exclude", "Lists", "missing list has no excluded member",
                () -> List.of(c("tags", NOT_CONTAINS, "red")), r -> r == missing));
        scenarios.add(exact("fractional", "Numeric", "no integer truncation",
                () -> List.of(c("price", LESS_THAN_EQUAL, "19.50")), r -> r == missing));
        try (var engine = new SqliteEngine(rows)) {
            for (var scenario : scenarios) {
                for (var candidate : SqliteEngine.candidates(scenario)) {
                    try (var query = engine.prepare(candidate)) {
                        assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, rows, engine.run(query, scenario, false).result()).status());
                    }
                }
            }
        }
    }

    @Test
    void failedCandidatesCannotWinAndMalformedTimingsCannotBeCollected() {
        var candidate = new SqliteEngine.Candidate("timeout", new VoltDbEngine.Sql("SELECT 1", List.of()));
        var failed = new SqliteBenchmark.Audit(candidate, List.of(), List.of(), null, List.of(), List.of(), "deadline");
        assertThrows(IllegalStateException.class, () -> SqliteBenchmark.selected(new SqliteBenchmark.Choice("timeout", List.of(failed))));
        var pass = new BenchmarkMain.Validation("PASS", 1, 0, 0, List.of());
        var invalid = new BenchmarkMain.Measurement("query", "test", "test", true, 1, pass, 1.0, 1.0, 1.0, List.of(0L));
        assertThrows(IllegalStateException.class, () -> SqliteBenchmark.verifyObservation(
                new QueryExecution.Observation(invalid, List.of(0L), null)));
        assertThrows(IllegalArgumentException.class, () -> ExecutionStatistics.ratio(List.of(new double[]{1}), List.of(new double[]{0})));
    }

    @Test
    void frozenCandidatesPreserveSqlAndParametersAcrossJsonIntegerWidths() throws Exception {
        var candidate = new SqliteEngine.Candidate("range", new VoltDbEngine.Sql("SELECT ? AS id", List.of(250L)));
        var saved = Engines.JSON.readValue(Engines.JSON.writeValueAsBytes(candidate), SqliteEngine.Candidate.class);
        assertTrue(SqliteBenchmark.sameCandidate(candidate, saved));
        assertFalse(SqliteBenchmark.sameCandidate(candidate,
                new SqliteEngine.Candidate("range", new VoltDbEngine.Sql("SELECT ? AS id", List.of(251L)))));
        assertFalse(SqliteBenchmark.sameCandidate(candidate,
                new SqliteEngine.Candidate("range", new VoltDbEngine.Sql("SELECT ? AS id", List.of(250.0)))));
    }

    @Test
    void tuningDeadlineInterruptsNativeSqlAndIsClearedBeforeMeasurement() throws Exception {
        var data = Datasets.load("catalog-1000");
        var scenario = data.scenarios().stream().filter(s -> s.name().equals("brand-equal")).findFirst().orElseThrow();
        var expensive = new SqliteEngine.Candidate("deadline-test", new VoltDbEngine.Sql(
                "WITH RECURSIVE counter(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM counter WHERE x<1000000000) "
                        + "SELECT CAST(SUM(x) AS TEXT) AS id FROM counter", List.of()));
        try (var engine = new SqliteEngine(data.rows());
             var query = engine.prepare(expensive)) {
            assertThrows(SqliteEngine.TuningTimeout.class, () -> engine.run(query, scenario, true));
            try (var valid = engine.prepare(SqliteEngine.candidates(scenario).getFirst())) {
                assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(),
                        engine.run(valid, scenario, false).result()).status());
            }
        }
    }

    @Test
    void sharedExecutionStatisticsAndMinimumSamplesPreserveTheProtocol() throws Exception {
        List<double[]> kiara = List.of(new double[]{1, 10}, new double[]{1, 10}, new double[]{1, 10});
        List<double[]> sqlite = List.of(new double[]{10, 1}, new double[]{10, 1}, new double[]{10, 1});
        assertEquals(1.0, ExecutionStatistics.ratio(kiara, sqlite), 1e-12);
        assertArrayEquals(new double[]{1, 1}, ExecutionStatistics.bootstrap(kiara, sqlite), 1e-12);
        var samples = QueryExecution.sample(() -> new QueryExecution.Sample(100, 0, new Engines.Result(List.of(), List.of())), 3, 3, 1);
        assertEquals(3, samples.executionNs().size());
        assertThrows(IllegalArgumentException.class, () -> QueryExecution.sample(
                () -> new QueryExecution.Sample(1, 0, null), 2, 3, 1));
    }
}
