package com.kapoorlabs.benchmark;

import java.util.*;
import static com.kapoorlabs.benchmark.BenchmarkMain.*;

final class QueryExecution {
    record Sample(long executionNs, long timerPairNs, Engines.Result result) {}
    record Samples(List<Long> executionNs, List<Long> timerPairsNs) {}
    record Observation(Measurement query, List<Long> timerPairsNs, String notTimedReason) {}
    @FunctionalInterface interface Query { Sample run() throws Exception; }
    private static volatile Object blackhole;

    static Sample kiara(Engines.Kiara engine, Workloads.Scenario scenario) {
        var conditions = engine.conditions(scenario);
        Set<String> projection = new HashSet<>(scenario.mode().equals("projection") ? Engines.PROJECTION : Engines.ID);
        Integer limit = scenario.limit() == 0 ? null : scenario.limit();
        long clockStart = System.nanoTime();
        long timerPair = System.nanoTime() - clockStart;
        long start = System.nanoTime();
        List<Map<String, String>> rows = engine.search.query(engine.store, conditions, projection, limit);
        long elapsed = System.nanoTime() - start;
        if (elapsed <= 0 || timerPair < 0) throw new IllegalStateException("Invalid Kiara clock measurement");
        blackhole = rows;
        return new Sample(elapsed, timerPair, new Engines.Result(rows.stream().map(row -> row.get("ID")).toList(), rows));
    }

    static Query lucene(Engines.Lucene engine, Workloads.Scenario scenario) throws Exception {
        if (!scenario.mode().equals("ids") || scenario.limit() != 0) {
            throw new IllegalArgumentException("Wide Lucene execution requires full ID-map results");
        }
        var builder = new org.apache.lucene.search.BooleanQuery.Builder();
        scenario.conditions().get().forEach(condition -> builder.add(Engines.Lucene.condition(condition),
                org.apache.lucene.search.BooleanClause.Occur.FILTER));
        var prepared = new org.apache.lucene.search.ConstantScoreQuery(builder.build());
        var stored = engine.reader.storedFields();
        return () -> {
            long clock = System.nanoTime();
            long pair = System.nanoTime() - clock;
            long start = System.nanoTime();
            var hits = engine.searcher.search(prepared, engine.size, org.apache.lucene.search.Sort.INDEXORDER);
            List<Map<String, String>> rows = new ArrayList<>(hits.scoreDocs.length);
            for (var hit : hits.scoreDocs) {
                Map<String, String> row = new HashMap<>();
                row.put("ID", stored.document(hit.doc, Engines.ID).get("ID"));
                rows.add(row);
            }
            long elapsed = System.nanoTime() - start;
            blackhole = rows;
            return new Sample(elapsed, pair, new Engines.Result(rows.stream().map(row -> row.get("ID")).toList(), rows));
        };
    }

    static void warm(Query query, int milliseconds, int minimum) throws Exception {
        if (milliseconds < 1 || minimum < 1) throw new IllegalArgumentException("Invalid warmup");
        long until = System.nanoTime() + milliseconds * 1_000_000L;
        int count = 0;
        do { blackhole = query.run().result(); count++; }
        while (count < minimum || System.nanoTime() < until);
    }

    static Samples sample(Query query, int maximum, int minimum, int milliseconds) throws Exception {
        if (minimum < 1 || minimum > maximum || milliseconds < 1) throw new IllegalArgumentException("Invalid sample bounds");
        List<Long> durations = new ArrayList<>();
        List<Long> clocks = new ArrayList<>();
        long until = System.nanoTime() + milliseconds * 1_000_000L;
        do {
            Sample result = query.run();
            if (result.executionNs() <= 0 || result.timerPairNs() < 0) throw new IllegalStateException("Invalid clock sample");
            durations.add(result.executionNs());
            clocks.add(result.timerPairNs());
            blackhole = result.result();
        } while (durations.size() < maximum && (durations.size() < minimum || System.nanoTime() < until));
        return new Samples(durations, clocks);
    }

    static Measurement measurement(Workloads.Scenario scenario, Validation validation, Samples samples) {
        List<Long> sorted = samples.executionNs().stream().sorted().toList();
        int matches = validation.expectedMatches() - validation.missing() + validation.extra();
        return new Measurement(scenario.name(), scenario.group(), scenario.detail(),
                scenario.comparable() && validation.status().equals("PASS"), matches, validation,
                percentile(sorted, .5) / 1000.0, percentile(sorted, .95) / 1000.0,
                sorted.stream().mapToLong(Long::longValue).average().orElseThrow() / 1000.0, samples.executionNs());
    }

    static Observation observation(Workloads.Scenario scenario, Validation validation, Samples samples) {
        return new Observation(measurement(scenario, validation, samples), samples.timerPairsNs(), null);
    }

    static Observation unsupported(Workloads.Scenario scenario, Datasets.Dataset data) {
        return notTimed(scenario, new Validation("UNSUPPORTED", scenario.expectedIds(data.rows()).size(), 0, 0,
                List.of("No equivalent text adapter")), "No equivalent text adapter");
    }

    static Observation notTimed(Workloads.Scenario scenario, Validation validation, String reason) {
        return new Observation(new Measurement(scenario.name(), scenario.group(), scenario.detail(), false,
                validation.status().equals("UNSUPPORTED") ? null : validation.expectedMatches() - validation.missing() + validation.extra(),
                validation, null, null, null, List.of()), List.of(), reason);
    }
}
