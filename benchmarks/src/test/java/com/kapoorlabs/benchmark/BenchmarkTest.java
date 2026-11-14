package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.kapoorlabs.kiara.domain.Operator.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTest {
    @Test
    void datasetFamiliesHaveDifferentRowsAndQueriesAndMatchBothEngines() throws Exception {
        Set<String> fingerprints = new HashSet<>();
        for (String datasetName : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
            Datasets.Dataset dataset = Datasets.load(datasetName);
            assertTrue(fingerprints.add(Engines.JSON.writeValueAsString(dataset.rows())));
            assertEquals(dataset.scenarios().size(), dataset.scenarios().stream().map(Workloads.Scenario::name).distinct().count());
            for (String engineName : List.of("kiara", "lucene")) {
                try (Engines.Engine engine = Engines.build(engineName, dataset.rows())) {
                    assertAll(datasetName + " / " + engineName, dataset.scenarios().stream().map(scenario ->
                            () -> BenchmarkMain.validate(engineName, scenario, dataset.rows(), engine.run(scenario))));
                }
            }
        }
        var retail = Datasets.load("retail-3788");
        assertEquals(3788, retail.rows().size());
        assertEquals(706, retail.scenarios().stream().filter(s -> s.name().equals("markets-either"))
                .findFirst().orElseThrow().expectedIds(retail.rows()).size());
        assertEquals(172, retail.scenarios().stream().filter(s -> s.name().equals("markets-all"))
                .findFirst().orElseThrow().expectedIds(retail.rows()).size());
        assertTrue(retail.rows().stream().allMatch(r -> r.getCategory() == null && r.getDate() == null));
        var catalog = Datasets.load("catalog-1000");
        assertTrue(catalog.rows().stream().allMatch(r -> r.getDate() == null && r.getBand() == null));
        assertTrue(catalog.rows().stream().map(BenchmarkRecord::getName).distinct().count() > 500);
        var schedule = Datasets.load("schedule-1000");
        assertTrue(schedule.rows().stream().allMatch(r -> r.getName() == null && r.getPrice() == null));
        assertTrue(schedule.scenarios().stream().noneMatch(s -> s.mode().equals("keyword")));
        assertNotEquals(catalog.scenarios().size(), schedule.scenarios().size());
        assertEquals(catalog.rows(), Datasets.load("catalog-1000").rows());
        assertEquals(schedule.rows(), Datasets.load("schedule-1000").rows());
        assertThrows(IllegalArgumentException.class, () -> Datasets.load("unknown-1000"));
        assertThrows(IllegalArgumentException.class, () -> Datasets.load("catalog-10"));
    }

    @Test
    void allScenariosAreCheckedAgainstIndependentOracleOnBothEngines() throws Exception {
        List<BenchmarkRecord> rows = Workloads.data(1000);
        for (String name : List.of("kiara", "lucene")) {
            try (Engines.Engine engine = Engines.build(name, rows)) {
                assertAll(name, Workloads.scenarios().stream().map(scenario ->
                        () -> BenchmarkMain.validate(name, scenario, rows, engine.run(scenario))));
            }
        }
    }

    @Test
    void operatorsAndAnnotationsAreCovered() {
        Set<Operator> covered = EnumSet.noneOf(Operator.class);
        try {
            for (String name : List.of("retail-3788", "catalog-1000", "schedule-1000")) {
                Datasets.load(name).scenarios().forEach(s -> s.conditions().get().forEach(c -> covered.add(c.getOperator())));
            }
        } catch (Exception e) {
            throw new AssertionError("Could not load coverage datasets", e);
        }
        assertEquals(EnumSet.allOf(Operator.class), covered);
        Set<String> annotations = new TreeSet<>();
        Arrays.stream(BenchmarkRecord.class.getDeclaredFields()).forEach(f ->
                Arrays.stream(f.getAnnotations()).filter(a -> a.annotationType().getPackageName().endsWith(".annotations"))
                        .forEach(a -> annotations.add(a.annotationType().getSimpleName())));
        assertEquals(Set.of("CaseInsensitive", "CommaSeperatedStrings", "CommaSeperatedNumbers",
                "DateFormat", "DateTimeFormat", "CommaSeperatedDates", "CommaSeperatedDateTimes",
                "NumericRange", "CommaSeperatedNumericRanges", "DateRange", "DateTimeRange",
                "CommaSeperatedDateRanges", "CommaSeperatedDateTimeRanges", "OneEditAway",
                "Predictable", "StemmedIndex", "IgnoreIndex"), annotations);
    }

    @Test
    void boundariesNullsRangeCorrelationAndNoMatches() throws Exception {
        List<BenchmarkRecord> rows = Workloads.data(4);
        rows.get(0).setQuantity(49);
        rows.get(1).setQuantity(50);
        rows.get(2).setQuantity(51);
        rows.get(3).setQuantity(0);
        rows.get(0).setPrice(19.98);
        rows.get(1).setPrice(19.99);
        rows.get(2).setPrice(20.0);
        rows.get(0).setBand("100-200");
        rows.get(1).setBand("200-300");
        rows.get(2).setBand("201-400");
        rows.get(3).setBand("0-99");
        rows.get(0).setBands("0-10,20-30");
        List<Workloads.Scenario> queries = List.of(
                Workloads.single("lt", "test", "quantity", LESS_THAN, "50"),
                Workloads.single("lte", "test", "quantity", LESS_THAN_EQUAL, "50"),
                Workloads.single("gt", "test", "quantity", GREATER_THAN, "50"),
                Workloads.single("gte", "test", "quantity", GREATER_THAN_EQUAL, "50"),
                Workloads.interval("between", "test", "quantity", "50", "50"),
                Workloads.single("decimal-boundary", "test", "price", LESS_THAN_EQUAL, "19.99"),
                Workloads.single("range-boundary", "test", "band", CONTAINS_EITHER, "200"),
                Workloads.single("range-no-cross-pair", "test", "bands", CONTAINS_EITHER, "15"),
                Workloads.single("range-different-pairs", "test", "bands", CONTAINS_ALL, "5", "25"),
                Workloads.single("none", "test", "id", EQUAL, "not-present"),
                Workloads.single("null", "test", "optional", EQUAL, "null"),
                Workloads.single("not-null", "test", "optional", NOT_EQUAL, "null"));
        assertEquals(List.of("0000000", "0000003"), queries.get(0).expectedIds(rows));
        assertEquals(List.of("0000001", "0000002"), queries.get(3).expectedIds(rows));
        assertEquals(List.of("0000000", "0000001"), queries.get(6).expectedIds(rows));
        for (String name : List.of("kiara", "lucene")) {
            try (Engines.Engine engine = Engines.build(name, rows)) {
                for (Workloads.Scenario scenario : queries) BenchmarkMain.validate(name, scenario, rows, engine.run(scenario));
            }
        }
    }

    @Test
    void ignoredFieldAndMinimumMatchApi() throws Exception {
        try (Engines.Kiara engine = new Engines.Kiara(Workloads.data(24))) {
            var scenario = Workloads.scenarios().stream().filter(s -> s.name().equals("typed-results")).findFirst().orElseThrow();
            var records = engine.search.query(engine.store, new ArrayList<>(scenario.conditions().get()));
            records.forEach(r -> assertEquals("Stored but not indexed payload " + Integer.parseInt(r.getId()), r.getDescription()));
            assertFalse(Arrays.stream(engine.store.getSdqlColumns())
                    .filter(c -> c.getColumnName().equalsIgnoreCase("description")).findFirst().orElseThrow().isIndexed());
            assertTrue(engine.keywords.getMinimumMatch("quartz", engine.store).getResult().isEmpty());
            assertEquals(List.of(), com.kapoorlabs.kiara.util.SpellCheckUtil.getTextPredictions("zzzz", engine.store.getSpellCheckTrie(), 10));
        }
    }

    @Test
    void deterministicDataAndPercentiles() {
        assertEquals(Workloads.data(10), Workloads.data(10));
        assertEquals(3L, BenchmarkMain.percentile(List.of(1L, 2L, 3L, 4L, 5L), .5));
        assertEquals(5L, BenchmarkMain.percentile(List.of(1L, 2L, 3L, 4L, 5L), .95));
        assertEquals(2.5, Report.median(List.of(1.0, 4.0, 2.0, 3.0), Double::doubleValue));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMain.main(new String[]{"--samples", "0"}));
        assertEquals(1, BenchmarkMain.editDistance("lanern", "lantern"));
        assertEquals(2, BenchmarkMain.editDistance("abc", "cab"));
    }

    @Test
    void decimalPrecisionDifferencesAreReportedNotSilentlyAccepted() throws Exception {
        List<BenchmarkRecord> rows = Workloads.data(4);
        rows.get(0).setPrice(19.0);
        rows.get(1).setPrice(19.50);
        rows.get(2).setPrice(19.99);
        rows.get(3).setPrice(20.0);
        Workloads.Scenario query = Workloads.scenarios().stream().filter(s -> s.name().equals("decimal-lte")).findFirst().orElseThrow();
        assertFalse(query.comparable());
        assertEquals(2, query.expectedIds(rows).size());
        for (String name : List.of("kiara", "lucene")) {
            try (Engines.Engine engine = Engines.build(name, rows)) {
                BenchmarkMain.Validation validation = BenchmarkMain.validate(name, query, rows, engine.run(query));
                assertEquals(name.equals("kiara") ? "KNOWN_DECIMAL_TRUNCATION" : "PASS", validation.status());
                assertEquals(name.equals("kiara") ? 1 : 0, validation.extra());
            }
        }
        assertThrows(AssertionError.class, () -> BenchmarkMain.validate("kiara", query, rows,
                new Engines.Result(List.of("0000003"), List.of())));
    }

    @lombok.Data
    public static class MinimalRecord {
        private String name;
    }

    @Test
    void minimumKeywordMatchAlsoReturnsAnExactMinimalRecord() throws Exception {
        Store<MinimalRecord> store = new Store<>(MinimalRecord.class);
        var loader = new com.kapoorlabs.kiara.loader.StoreLoader<>(store);
        for (String name : List.of("quartz", "emerald")) {
            MinimalRecord record = new MinimalRecord();
            record.setName(name);
            loader.loadTable(record);
        }
        loader.prepareForSearch();
        var result = new com.kapoorlabs.kiara.search.KeywordSearch(10).getMinimumMatch("quartz", store);
        assertEquals(Set.of("quartz"), result.getKeywords());
        assertEquals(1, result.getResult().size());
        assertEquals("quartz", result.getResult().getFirst().getName());
    }

    @Test
    void textDifferencesRetainQualityEvidenceAndCannotBypassHardFilters() throws Exception {
        Datasets.Dataset data = Datasets.load("catalog-1000");
        var scenario = data.scenarios().stream().filter(s -> s.name().equals("keyword-typo")).findFirst().orElseThrow();
        try (Engines.Engine engine = Engines.build("kiara", data.rows())) {
            var validation = BenchmarkMain.validate("kiara", scenario, data.rows(), engine.run(scenario));
            assertEquals("TEXT_RESULT_DIFFERENCE", validation.status());
            assertEquals(4, validation.expectedMatches());
            assertEquals(4, validation.missing());
            assertEquals(8, validation.extra());
        }
        var filtered = data.scenarios().stream().filter(s -> s.name().equals("keyword-filtered")).findFirst().orElseThrow();
        String invalidId = data.rows().stream().filter(r -> !r.getStatus().equals("active") || !r.getRegion().equals("emea"))
                .findFirst().orElseThrow().getId();
        assertThrows(AssertionError.class, () -> BenchmarkMain.validate("kiara", filtered, data.rows(),
                new Engines.Result(List.of(invalidId), List.of())));
    }

    @Test
    void reportUsesDatasetSpecificQueriesAndRequiresEveryForkToBeComparable() {
        var pass = new BenchmarkMain.Validation("PASS", 1, 0, 0, List.of());
        var difference = new BenchmarkMain.Validation("TEXT_RESULT_DIFFERENCE", 1, 1, 1, List.of("example"));
        var exact = new BenchmarkMain.Measurement("date-equal", "Dates", "one date", true, 1, pass,
                1.0, 2.0, 1.5, List.of(1000L, 2000L));
        var keywordPass = new BenchmarkMain.Measurement("keyword-typo", "Text", "two words", true, 1, pass,
                1.0, 2.0, 1.5, List.of(1000L, 2000L));
        var keywordDifferent = new BenchmarkMain.Measurement("keyword-typo", "Text", "two words", false, 1, difference,
                1.0, 2.0, 1.5, List.of(1000L, 2000L));
        List<BenchmarkMain.Run> runs = new ArrayList<>();
        for (int fork = 1; fork <= 2; fork++) {
            for (String engine : List.of("kiara", "lucene")) {
                runs.add(new BenchmarkMain.Run("test-1000", "test fixture", "same", engine, 1000, fork,
                        "SUCCESS", null, 10.0, 10, 100L, 200L, 100L,
                        List.of(exact, engine.equals("kiara") && fork == 2 ? keywordDifferent : keywordPass)));
            }
        }
        BenchmarkMain.validateRuns(runs, List.of("test-1000"), 2);
        assertThrows(IllegalStateException.class, () -> BenchmarkMain.validateRuns(runs.subList(1, runs.size()), List.of("test-1000"), 2));
        var results = new BenchmarkMain.Results("now", Map.of("kiara", "2.0.15", "lucene", "10.5.2"), Map.of(), runs);
        String report = Report.render(results);
        assertTrue(report.contains("## test-1000"));
        assertTrue(report.contains("**date-equal**: one date"));
        assertFalse(report.contains("**brand-equal**"));
        String keywordRow = report.lines().filter(line -> line.startsWith("| **keyword-typo**")).findFirst().orElseThrow();
        assertTrue(keywordRow.endsWith("| n/a |"));
    }

    @Test
    void scheduleIndexingSucceedsWithPublishedComparatorFix() throws Exception {
        var output = java.nio.file.Files.createTempFile("kiara-schedule-", ".json");
        try {
            BenchmarkMain.main(new String[]{"--worker", "kiara", "--dataset", "schedule-10000", "--samples", "1",
                    "--warmup-ms", "1", "--budget-ms", "1", "--output", output.toString()});
            var run = Engines.JSON.readValue(output.toFile(), BenchmarkMain.Run.class);
            assertEquals("SUCCESS", run.status());
            assertNull(run.failure());
            assertTrue(run.buildMs() > 0);
            assertNotNull(run.heapAfterBytes());
            assertNotNull(run.retainedHeapBytes());
            assertEquals(23, run.measurements().size());
            run.measurements().forEach(m -> {
                assertNotNull(m.matches());
                assertNotNull(m.p50Us());
                assertNotNull(m.p95Us());
                assertEquals(1, m.samplesNs().size());
                assertEquals("PASS", m.validation().status());
            });
        } finally {
            java.nio.file.Files.delete(output);
        }
    }
}
