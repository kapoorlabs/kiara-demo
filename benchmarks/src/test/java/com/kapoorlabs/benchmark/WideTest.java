package com.kapoorlabs.benchmark;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.kapoorlabs.kiara.domain.Operator.*;

class WideTest {
    @Test
    void parameterizedVoltTemplatesReusePlansButKeepActualBindingsAndTypes() {
        var first = new VoltExecutionEngine.Candidate("first",
                new VoltDbEngine.Sql("SELECT r.id FROM records r WHERE r.f_quantity_num < ?", List.of(10.0)), "");
        var second = new VoltExecutionEngine.Candidate("second",
                new VoltDbEngine.Sql(first.sql().text(), List.of(20.0)), "");
        assertEquals(VoltExecutionEngine.template(first), VoltExecutionEngine.template(second));
        assertArrayEquals(new Object[]{0, 10.0}, VoltExecutionEngine.arguments(first));
        assertArrayEquals(new Object[]{0, 20.0}, VoltExecutionEngine.arguments(second));
        var otherType = new VoltExecutionEngine.Candidate("long",
                new VoltDbEngine.Sql(first.sql().text(), List.of(20L)), "");
        assertNotEquals(VoltExecutionEngine.template(first), VoltExecutionEngine.template(otherType));
        var otherOrder = new VoltExecutionEngine.Candidate("order", first.sql(), "r,v0");
        assertNotEquals(VoltExecutionEngine.template(first), VoltExecutionEngine.template(otherOrder));
        var candidates = WideWorkloads.scenarios().stream().flatMap(s -> VoltExecutionEngine.candidates(s).stream()).toList();
        assertEquals(99, candidates.size());
        assertEquals(33, candidates.stream().map(VoltExecutionEngine::template).distinct().count());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "WIDE_VOLTDB_INTEGRATION", matches = "true")
    void nativeVoltFiveTableEitherJoinsCompileAndValidate() throws Exception {
        var data = Datasets.load("wide-1000");
        try (var engine = new VoltExecutionEngine(data.rows(), true)) {
            var scenario = data.scenarios().stream().filter(s -> s.name().equals("either-w8-s100")).findFirst().orElseThrow();
            var sql = VoltDbEngine.compile(scenario);
            var prepared = engine.prepare(List.of(
                    new VoltExecutionEngine.Candidate("probe-record-first", sql, "r,v0,v1,v2,v3"),
                    new VoltExecutionEngine.Candidate("probe-value-first", sql, "v0,r,v1,v2,v3")));
            for (var query : prepared) assertPass("voltdb", data, scenario, engine.run(query, scenario).result());
        }
    }

    @Test
    void widthsCountDistinctFieldsAndKeepResultsNonemptyAcrossSelectivityTargets() throws Exception {
        var data = Datasets.load("wide-10000");
        assertEquals(39, data.scenarios().size());
        assertEquals(10000, data.rows().size());
        Set<Integer> widths = new HashSet<>();
        for (var scenario : data.scenarios()) {
            int width = Integer.parseInt(scenario.name().split("-")[1].substring(1));
            int perMille = Integer.parseInt(scenario.name().split("-")[2].substring(1));
            var conditions = scenario.conditions().get();
            widths.add(width);
            assertEquals(width, conditions.size());
            assertEquals(width, conditions.stream().map(c -> c.getColumnName()).distinct().count());
            assertTrue(scenario.expected().test(data.rows().getFirst()));
            int actual = scenario.expectedIds(data.rows()).size();
            double expected = perMille / 1000.0 * data.rows().size();
            assertTrue(actual >= 1 && Math.abs(actual - expected) < Math.max(12, 6 * Math.sqrt(expected)),
                    scenario.name() + " unexpected count " + actual);
            long collections = conditions.stream().filter(c -> VoltDbEngine.LIST_FIELDS.contains(c.getColumnName())).count();
            if (scenario.group().equals("Wide scalar")) assertEquals(0, collections);
            else {
                assertTrue(collections > 0);
                var operator = scenario.group().equals("Wide all") ? CONTAINS_ALL : CONTAINS_EITHER;
                conditions.stream().filter(c -> VoltDbEngine.LIST_FIELDS.contains(c.getColumnName())).forEach(c -> {
                    assertEquals(operator, c.getOperator());
                    assertEquals(2, new HashSet<>(c.getValue()).size());
                });
            }
        }
        assertEquals(new HashSet<>(WideWorkloads.WIDTHS), widths);
        assertEquals(Engines.JSON.valueToTree(data.rows()), Engines.JSON.valueToTree(Datasets.load("wide-10000").rows()));
    }

    @Test
    void everyWideFilterMatchesKiaraLuceneAndBothSqliteProfiles() throws Exception {
        var data = Datasets.load("wide-1000");
        try (var kiara = new Engines.Kiara(data.rows());
             var lucene = new Engines.Lucene(data.rows());
             var tuned = new SqliteEngine(data.rows(), SqliteEngine.Profile.TUNED, true);
             var generic = new SqliteEngine(data.rows(), SqliteEngine.Profile.GENERIC, true)) {
            var tunedEvidence = Engines.JSON.valueToTree(tuned.evidence());
            var genericEvidence = Engines.JSON.valueToTree(generic.evidence());
            assertEquals(39, tunedEvidence.path("indexes").size());
            assertTrue(tunedEvidence.path("indexes").has("wide_covering"));
            assertEquals(26, genericEvidence.path("indexes").size());
            assertFalse(genericEvidence.path("analyzed").asBoolean());
            for (var scenario : data.scenarios()) {
                assertPass("kiara", data, scenario, QueryExecution.kiara(kiara, scenario).result());
                assertPass("lucene", data, scenario, QueryExecution.lucene(lucene, scenario).run().result());
                for (var candidate : SqliteEngine.candidates(scenario)) {
                    try (var prepared = tuned.prepare(candidate)) {
                        assertPass("sqlite", data, scenario, tuned.run(prepared, scenario, true).result());
                    }
                }
                try (var prepared = generic.prepare(SqliteEngine.candidates(scenario, SqliteEngine.Profile.GENERIC).getFirst())) {
                    assertPass("sqlite", data, scenario, generic.run(prepared, scenario, false).result());
                }
                var voltCandidates = VoltExecutionEngine.candidates(scenario);
                int joinLimit = scenario.group().equals("Wide all") ? 2 : 4;
                if (scenario.conditions().get().stream().filter(c -> VoltDbEngine.LIST_FIELDS.contains(c.getColumnName())).count() > joinLimit) {
                    assertEquals(1, voltCandidates.size());
                    assertEquals("wide-intersections", voltCandidates.getFirst().name());
                }
                for (var candidate : voltCandidates) {
                    var matcher = java.util.regex.Pattern.compile("JOIN vals (v[0-9]+)").matcher(candidate.sql().text());
                    Set<String> aliases = new HashSet<>(List.of("r"));
                    while (matcher.find()) aliases.add(matcher.group(1));
                    if (aliases.size() > 1) {
                        var order = List.of(candidate.joinOrder().split(","));
                        assertEquals(aliases, new HashSet<>(order));
                        assertTrue(order.getFirst().equals("r") || order.get(1).equals("r"),
                                "Value-first must join its owner before another collection, avoiding a Cartesian product");
                    }
                }
            }
        }
    }

    private static void assertPass(String engine, Datasets.Dataset data, Workloads.Scenario scenario, Engines.Result result) {
        assertEquals("PASS", BenchmarkMain.validate(engine, scenario, data.rows(), result).status(), scenario.name());
    }
}
