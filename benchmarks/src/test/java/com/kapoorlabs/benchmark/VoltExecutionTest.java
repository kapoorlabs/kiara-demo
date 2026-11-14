package com.kapoorlabs.benchmark;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class VoltExecutionTest {
    @Test
    void genericProfileUsesOneFixedTranslationAndMatchesWideOracle() throws Exception {
        var data = Datasets.load("wide-1000");
        try (var engine = new SqliteEngine(data.rows(), SqliteEngine.Profile.GENERIC, true)) {
            for (var scenario : data.scenarios()) {
                var candidates = VoltExecutionEngine.candidates(scenario, VoltExecutionEngine.Profile.GENERIC);
                assertEquals(1, candidates.size());
                var candidate = candidates.getFirst();
                assertEquals("generic-fixed", candidate.name());
                assertEquals("", candidate.joinOrder());
                assertEquals(candidate.sql().parameters().size(), candidate.sql().text().chars().filter(c -> c == '?').count());
                try (var prepared = engine.prepare(new SqliteEngine.Candidate(candidate.name(), candidate.sql()))) {
                    assertEquals("PASS", BenchmarkMain.validate("sqlite", scenario, data.rows(),
                            engine.run(prepared, scenario, false).result()).status());
                }
            }
        }
        for (var scenario : Datasets.load("catalog-1000").scenarios()) {
            if (!VoltExecutionEngine.timed(scenario)) continue;
            assertEquals(VoltDbEngine.compile(scenario),
                    VoltExecutionEngine.candidates(scenario, VoltExecutionEngine.Profile.GENERIC).getFirst().sql());
        }
    }

    @Test
    void genericReportExcludesTimeoutsWithoutInventingLatency() throws Exception {
        var data = Datasets.load("wide-1000");
        var scenario = data.scenarios().getFirst();
        var timeout = VoltExecutionBenchmark.timeout(scenario, data, "Native SQL query timeout");
        assertNull(timeout.query().matches());
        assertNull(timeout.query().p50Us());
        assertFalse(timeout.query().comparable());
        assertTrue(timeout.query().samplesNs().isEmpty());
        var validation = new BenchmarkMain.Validation("PASS", scenario.expectedIds(data.rows()).size(), 0, 0, List.of());
        var pass = QueryExecution.observation(scenario, validation,
                new QueryExecution.Samples(List.of(1000L), List.of(10L)));
        var k = new VoltExecutionBenchmark.Trial("measure", data.name(), data.description(), "hash", "kiara", 1,
                Map.of(), List.of(pass), Map.of(), Map.of());
        var v = new VoltExecutionBenchmark.Trial("measure", data.name(), data.description(), "hash", "voltdb", 1,
                Map.of(), List.of(timeout), Map.of(), Map.of());
        var results = new VoltExecutionBenchmark.Results("now", Map.of("profile", "generic"), List.of(), List.of(k, v));
        String report = VoltExecutionReport.renderGeneric(results);
        assertTrue(report.contains("| wide-1000 | 1 | 0 | n/a | n/a | 0 / 0 | 1 |"));
        assertTrue(report.contains("| PASS / QUERY_TIMEOUT | n/a | n/a | n/a |"));
        assertFalse(report.contains("NaN"));
    }

    @Test
    void onlyExplicitQueryTimeoutsCanDisqualifyTuningCandidates() {
        assertTrue(VoltExecutionBenchmark.queryTimeout("A SQL query was terminated after 10.001 seconds because it exceeded the default query timeout period."));
        assertFalse(VoltExecutionBenchmark.queryTimeout("Client procedure timed out"));
        assertFalse(VoltExecutionBenchmark.queryTimeout("Constraint violation"));
        assertFalse(VoltExecutionBenchmark.queryTimeout(null));
    }

    @Test
    void serverClockExcludesRequestAndResponseWork() {
        String source = VoltExecutionEngine.source("benchmark", "Query",
                "SELECT id FROM records WHERE value = ?", "", List.of("quoted\"value"));
        assertTrue(source.contains("public VoltTable[] run(int bucket, String p0)"));
        assertTrue(source.indexOf("Object[] arguments") < source.indexOf("long start"));
        assertTrue(source.indexOf("long start") < source.indexOf("voltQueueSQL"));
        assertTrue(source.indexOf("voltExecuteSQL(true)") < source.indexOf("long elapsed"));
        assertTrue(source.indexOf("long elapsed") < source.indexOf("VoltTable timing"));
        assertFalse(source.contains("quoted"));
        assertFalse(source.contains("getClusterRoundtrip"));
    }

    @Test
    void directListQueryPreservesGroupingAndAllRequirements() throws Exception {
        var data = Datasets.load("catalog-1000");
        var all = data.scenarios().stream().filter(s -> s.name().equals("tags-all")).findFirst().orElseThrow();
        var sql = VoltDbEngine.compile(all, true);
        assertTrue(sql.text().contains("FROM vals v0 WHERE v0.field = ?"));
        assertFalse(sql.text().contains("JOIN"));
        assertTrue(sql.text().contains("v0.text_value IN (?, ?)"));
        assertTrue(sql.text().contains("THEN 2 ELSE 0 END) = 2"));
        assertEquals(sql.parameters().size(), sql.text().chars().filter(c -> c == '?').count());
    }

    @Test
    void reportAggregatesTrialsAndBootstrapsWholeTrials() {
        var pass = new BenchmarkMain.Validation("PASS", 1, 0, 0, List.of());
        List<VoltExecutionBenchmark.Trial> k = new ArrayList<>();
        List<VoltExecutionBenchmark.Trial> v = new ArrayList<>();
        for (int fork = 1; fork <= 5; fork++) {
            for (String engine : List.of("kiara", "voltdb")) {
                long ns = engine.equals("kiara") ? 1000 : 2000;
                var measured = new BenchmarkMain.Measurement("query", "Equality", "test", true, 1, pass,
                        ns / 1000.0, ns / 1000.0, ns / 1000.0, List.of(ns));
                var excluded = new BenchmarkMain.Measurement("limited", "Equality", "test", false, 1, pass,
                        ns / 1000.0, ns / 1000.0, ns / 1000.0, List.of(ns));
                var trial = new VoltExecutionBenchmark.Trial("measure", "test", "test", "hash", engine, fork, Map.of(),
                        List.of(new QueryExecution.Observation(measured, List.of(20L), null),
                                new QueryExecution.Observation(excluded, List.of(20L), null)), Map.of(), Map.of());
                (engine.equals("kiara") ? k : v).add(trial);
            }
        }
        var eligible = VoltExecutionReport.eligible(k, v);
        assertEquals(List.of("query"), eligible);
        assertEquals(2, VoltExecutionReport.ratio(k, v, eligible));
        assertArrayEquals(new double[]{2, 2}, VoltExecutionReport.bootstrap(k, v, eligible));
        assertEquals(2, VoltExecutionBenchmark.median(List.of(3L, 1L, 2L)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VOLTDB_INTEGRATION", matches = "true")
    void instrumentedSinglePartitionProceduresReturnCorrectResultsAndPlans() throws Exception {
        var data = Datasets.load("catalog-1000");
        try (var engine = new VoltExecutionEngine(data.rows())) {
            for (String name : List.of("brand-equal", "tags-all", "sizes-either", "tags-exclude",
                    "region-in", "status-not-equal", "supplier-known")) {
                var scenario = data.scenarios().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
                for (var query : engine.prepare(VoltExecutionEngine.candidates(scenario))) {
                    var result = engine.run(query, scenario);
                    assertTrue(result.executionNs() > 0);
                    assertEquals("PASS", BenchmarkMain.validate("voltdb", scenario, data.rows(), result.result()).status());
                    assertFalse(query.plan().isBlank());
                    System.out.println(name + " / " + query.candidate().name() + "\n" + query.plan());
                }
            }
            assertEquals("10.1.1", engine.evidence().get("version"));
        }
    }
}
