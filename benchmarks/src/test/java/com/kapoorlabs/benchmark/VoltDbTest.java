package com.kapoorlabs.benchmark;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static com.kapoorlabs.benchmark.Workloads.*;
import static com.kapoorlabs.kiara.domain.Operator.*;

class VoltDbTest {
    @Test
    void parametersAreBoundRatherThanInterpolated() {
        var scenario = exact("injection", "Equality", "quoted string",
                () -> List.of(c("brand", EQUAL, "O'Reilly'); DROP TABLE records; --")), r -> false);
        var sql = VoltDbEngine.compile(scenario);
        assertFalse(sql.text().contains("O'Reilly"));
        assertEquals(List.of("o'reilly'); drop table records; --"), sql.parameters());
        assertEquals(sql.parameters().size(), sql.text().chars().filter(c -> c == '?').count());
    }

    @Test
    void rangeEndpointsAndPrefixStayInTheSameRowPredicate() {
        var sql = VoltDbEngine.compile(exact("range", "Ranges", "prefix",
                () -> List.of(c("route", CONTAINS_ALL, "AA21", "AA40")), r -> false));
        assertEquals(List.of("AA", 21L, 21L, "AA", 40L, 40L), sql.parameters());
        assertTrue(sql.text().contains("r.f_route_prefix = ? AND r.f_route_lower <= ? AND r.f_route_upper >= ?"));
        assertFalse(sql.text().contains("JOIN"));
    }

    @Test
    void allStructuredDatasetQueriesCompileAndTextIsExplicitlyUnsupported() throws Exception {
        Set<com.kapoorlabs.kiara.domain.Operator> operators = new HashSet<>();
        for (String name : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
            for (var scenario : Datasets.load(name).scenarios()) {
                if (VoltDbEngine.supported(scenario)) {
                    var sql = VoltDbEngine.compile(scenario);
                    assertEquals(sql.parameters().size(), sql.text().chars().filter(c -> c == '?').count());
                    scenario.conditions().get().forEach(c -> operators.add(c.getOperator()));
                } else {
                    assertThrows(IllegalArgumentException.class, () -> VoltDbEngine.compile(scenario));
                }
            }
        }
        assertEquals(EnumSet.allOf(com.kapoorlabs.kiara.domain.Operator.class), operators);
    }

    @Test
    void listAllUsesOneJoinAndChecksEveryPointWithoutBreakingIntervalPairs() {
        var sql = VoltDbEngine.compile(exact("all", "Ranges", "two points",
                () -> List.of(c("bands", CONTAINS_ALL, "20", "170")), r -> false));
        assertEquals(1, sql.text().split("JOIN vals", -1).length - 1);
        assertTrue(sql.text().contains("GROUP BY r.id HAVING MAX(CASE WHEN"));
        assertTrue(sql.text().contains("THEN 2 ELSE 0 END) = 2"));
        assertEquals(List.of("bands", "", 20L, 20L, "", 170L, 170L, "", 20L, 20L, "", 170L, 170L),
                sql.parameters());
    }

    @Test
    void nullInclusionAndExclusionHaveExplicitSemantics() {
        var sql = VoltDbEngine.compile(exact("missing", "Lists", "missing",
                () -> List.of(c("tags", CONTAINS_EITHER_INCLUDING_NULL, "red"), c("optional", NOT_EQUAL, NULL)), r -> false));
        assertEquals(List.of("tags", "red", NULL, NULL), sql.parameters());
        assertTrue(sql.text().contains("NOT (COALESCE((r.f_optional = ?), FALSE))"));
        assertFalse(sql.text().contains("NOT IN"));
    }

    @Test
    void listExclusionChecksForZeroMatchesWithoutAnOuterJoin() {
        var sql = VoltDbEngine.compile(exact("exclude", "Lists", "no red",
                () -> List.of(c("tags", NOT_CONTAINS, "red")), r -> false));
        assertEquals(List.of("tags", "red"), sql.parameters());
        assertTrue(sql.text().contains("GROUP BY r.id HAVING MAX(CASE WHEN"));
        assertTrue(sql.text().contains("THEN 1 ELSE 0 END) = 0"));
        assertFalse(sql.text().contains("LEFT JOIN"));
    }

    @Test
    void reportExcludesDifferencesAndDoesNotInventUnsupportedTimings() {
        var pass = new BenchmarkMain.Validation("PASS", 1, 0, 0, List.of());
        var unsupported = new BenchmarkMain.Measurement("keyword", "Text", "not adapted", false, null,
                new BenchmarkMain.Validation("UNSUPPORTED", 1, 0, 0, List.of()), null, null, null, List.of());
        List<VoltDbBenchmark.Worker> workers = new ArrayList<>();
        for (String engine : List.of("kiara", "voltdb")) {
            double latency = engine.equals("kiara") ? 1 : 2;
            var exact = new BenchmarkMain.Measurement("equal", "Equality", "one value", true, 1, pass,
                    latency, latency, latency, List.of((long) (latency * 1000)));
            var difference = new BenchmarkMain.Measurement("decimal", "Numeric", "fractional", false, 1, pass,
                    latency, latency, latency, List.of((long) (latency * 1000)));
            var run = new BenchmarkMain.Run("test-1000", "test fixture", "same", engine, 1000, 1, "SUCCESS",
                    null, 10.0, 10, null, null, null, List.of(exact, difference, unsupported));
            workers.add(new VoltDbBenchmark.Worker(run, engine.equals("voltdb") ? "10.1.1" : null, Map.of()));
        }
        VoltDbBenchmark.validateWorkers(workers, List.of("test-1000"), 1);
        assertThrows(IllegalStateException.class, () -> VoltDbBenchmark.validateWorkers(workers.subList(1, 2), List.of("test-1000"), 1));
        String report = VoltDbReport.render(new VoltDbBenchmark.Results("now", Map.of(), Map.of(), workers));
        assertTrue(report.contains("| test-1000 | 2 | 1 | 2.00x |"));
        assertTrue(report.lines().filter(line -> line.startsWith("| **decimal**")).findFirst().orElseThrow().endsWith("| n/a |"));
        assertTrue(report.contains("| UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a | 0 / 0 | n/a |"));
        assertFalse(report.contains("JDBC"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VOLTDB_INTEGRATION", matches = "true")
    void liveDatabaseMatchesOracleAcrossAllStructuredFamilies() throws Exception {
        for (String name : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
            var data = Datasets.load(name);
            try (var engine = new VoltDbEngine(data.rows())) {
                assertFalse(engine.serverVersion().isBlank());
                for (var scenario : data.scenarios()) {
                    if (!VoltDbEngine.supported(scenario)) continue;
                    assertEquals("PASS", BenchmarkMain.validate("voltdb", scenario, data.rows(), engine.run(scenario)).status(),
                            name + " / " + scenario.name());
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VOLTDB_INTEGRATION", matches = "true")
    void intervalCorrelationAndInclusiveBoundariesArePreserved() throws Exception {
        BenchmarkRecord row = new BenchmarkRecord();
        row.setId("split");
        row.setBands("10-20,30-40");
        row.setWindow("2026-01-01-2026-01-10");
        List<BenchmarkRecord> rows = List.of(row);
        try (var engine = new VoltDbEngine(rows)) {
            for (int point : new int[] {9, 10, 20, 25, 30, 40, 41}) {
                var scenario = exact("point-" + point, "Ranges", "paired endpoints",
                        () -> List.of(c("bands", CONTAINS_EITHER, Integer.toString(point))),
                        r -> point >= 10 && point <= 20 || point >= 30 && point <= 40);
                assertEquals("PASS", BenchmarkMain.validate("voltdb", scenario, rows, engine.run(scenario)).status());
            }
        }
    }
}
