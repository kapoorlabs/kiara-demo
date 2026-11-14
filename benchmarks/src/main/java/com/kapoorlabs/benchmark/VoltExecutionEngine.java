package com.kapoorlabs.benchmark;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;
import org.voltdb.VoltTable;
import com.kapoorlabs.benchmark.QueryExecution.Sample;

final class VoltExecutionEngine implements AutoCloseable {
    enum Profile { TUNED, GENERIC }
    record Candidate(String name, VoltDbEngine.Sql sql, String joinOrder) {}
    record Prepared(String procedure, Candidate candidate, String source, String plan, Object[] arguments) {}
    record Template(String sql, String joinOrder, List<String> parameterTypes) {}

    private final VoltDbEngine database;
    private final String namespace = "bench" + UUID.randomUUID().toString().replace("-", "");
    private final String routing = namespace + "_routing";
    private final List<String> procedures = new ArrayList<>();
    private final List<String> indexDdl = new ArrayList<>();
    private boolean routingCreated;
    private boolean classesLoaded;
    private final boolean wide;

    VoltExecutionEngine(List<BenchmarkRecord> rows) throws Exception {
        this(rows, false);
    }

    VoltExecutionEngine(List<BenchmarkRecord> rows, boolean wide) throws Exception {
        this(rows, wide, Profile.TUNED);
    }

    VoltExecutionEngine(List<BenchmarkRecord> rows, boolean wide, Profile profile) throws Exception {
        this.wide = wide;
        database = new VoltDbEngine(rows, System.getenv().getOrDefault("VOLTDB_BENCHMARK_HOST", "127.0.0.1"),
                wide ? 600000 : 120000);
        try {
            ddl("CREATE TABLE " + routing + " (bucket INTEGER NOT NULL PRIMARY KEY);");
            routingCreated = true;
            ddl("PARTITION TABLE " + routing + " ON COLUMN bucket;");
            ddl("INSERT INTO " + routing + " VALUES (0);");
            if (profile == Profile.TUNED) {
                for (String columns : List.of("f_brand, f_status, f_region, id", "f_active, f_category, id",
                        "f_tier, f_status, id", "f_optional, f_region, id", "f_brand, f_optional, id",
                        "f_category, f_brand, id", "f_brand, f_date_num, id",
                        "f_brand, f_route_prefix, f_route_lower, f_route_upper, id",
                        "f_category, f_brand, f_band_prefix, f_band_lower, f_band_upper, f_window_lower, f_window_upper, id")) {
                    String sql = "CREATE INDEX " + namespace + "_composite_" + indexDdl.size()
                            + " ON " + database.recordsTable() + " (" + columns + ");";
                    ddl(sql);
                    indexDdl.add(sql);
                }
                String upper = "CREATE INDEX " + namespace + "_upper ON " + database.valuesTable()
                        + " (field, prefix, upper_bound, lower_bound, id);";
                ddl(upper);
                indexDdl.add(upper);
                if (wide) {
                    String sql = "CREATE INDEX " + namespace + "_wide ON " + database.recordsTable()
                            + " (" + WideWorkloads.COVERING_COLUMNS + ");";
                    ddl(sql);
                    indexDdl.add(sql);
                }
            }
        } catch (Exception error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static boolean timed(Workloads.Scenario scenario) {
        return Set.of("ids", "projection").contains(scenario.mode());
    }

    static List<Candidate> candidates(Workloads.Scenario scenario) {
        List<Candidate> result = new ArrayList<>();
        VoltDbEngine.Sql baseline = VoltDbEngine.compile(scenario);
        VoltDbEngine.Sql optimized = VoltDbEngine.compile(scenario, true);
        if (scenario.group().startsWith("Wide ")) {
            VoltDbEngine.intersection(scenario).ifPresent(sql -> result.add(new Candidate("wide-intersections", sql, "")));
            int joinLimit = scenario.conditions().get().stream().anyMatch(c -> c.getOperator() == com.kapoorlabs.kiara.domain.Operator.CONTAINS_ALL) ? 2 : 4;
            if (scenario.conditions().get().stream().filter(c -> VoltDbEngine.LIST_FIELDS.contains(c.getColumnName())).count() > joinLimit) {
                return List.copyOf(result);
            }
            for (var entry : new LinkedHashSet<>(List.of(baseline, optimized))) {
                List<String> aliases = new ArrayList<>(List.of("r"));
                var matcher = java.util.regex.Pattern.compile("JOIN vals (v[0-9]+)").matcher(entry.text());
                while (matcher.find()) aliases.add(matcher.group(1));
                result.add(new Candidate("wide-record-first-" + result.size(), entry,
                        aliases.size() == 1 ? "" : String.join(",", aliases)));
                if (aliases.size() > 1) {
                    String drivingValue = aliases.remove(1);
                    aliases.addFirst(drivingValue);
                    result.add(new Candidate("wide-value-first-" + result.size(), entry, String.join(",", aliases)));
                }
            }
            return List.copyOf(result);
        }
        result.add(new Candidate("indexed-default", baseline, ""));
        if (!optimized.equals(baseline)) {
            result.add(new Candidate(baseline.text().contains("JOIN vals") ? "direct-in" : "scalar-rewrite", optimized, ""));
        }
        if (baseline.text().contains("JOIN vals v0") && !baseline.text().contains("JOIN vals v1")) {
            if (optimized.text().contains("JOIN")) result.add(new Candidate("record-first", optimized, "r,v0"));
            result.add(new Candidate("value-first", baseline, "v0,r"));
        }
        VoltDbEngine.exclusionAlternative(scenario).ifPresent(sql -> result.add(new Candidate("set-difference", sql, "")));
        return result;
    }

    static List<Candidate> candidates(Workloads.Scenario scenario, Profile profile) {
        if (profile == Profile.TUNED) return candidates(scenario);
        VoltDbEngine.Sql sql = scenario.group().startsWith("Wide ")
                ? VoltDbEngine.intersection(scenario).orElseGet(() -> VoltDbEngine.compile(scenario))
                : VoltDbEngine.compile(scenario);
        return List.of(new Candidate("generic-fixed", sql, ""));
    }

    List<Prepared> prepare(List<Candidate> candidates) throws Exception {
        if (!wide) return prepareDistinct(candidates);
        Map<Template, Integer> offsets = new LinkedHashMap<>();
        List<Candidate> unique = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Template key = template(candidate);
            if (!offsets.containsKey(key)) {
                offsets.put(key, unique.size());
                unique.add(candidate);
            }
        }
        System.out.println("Reusing " + unique.size() + " parameterized templates for " + candidates.size() + " candidate bindings");
        List<Prepared> compiled = prepareDistinct(unique);
        List<Prepared> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Prepared prepared = compiled.get(offsets.get(template(candidate)));
            result.add(new Prepared(prepared.procedure(), candidate, prepared.source(), prepared.plan(), arguments(candidate)));
        }
        return List.copyOf(result);
    }

    static Template template(Candidate candidate) {
        return new Template(candidate.sql().text(), candidate.joinOrder(),
                candidate.sql().parameters().stream().map(value -> value.getClass().getName()).toList());
    }

    static Object[] arguments(Candidate candidate) {
        Object[] arguments = new Object[candidate.sql().parameters().size() + 1];
        arguments[0] = 0;
        for (int i = 1; i < arguments.length; i++) arguments[i] = candidate.sql().parameters().get(i - 1);
        return arguments;
    }

    private List<Prepared> prepareDistinct(List<Candidate> candidates) throws Exception {
        Path directory = Files.createTempDirectory("volt-procedure-");
        try {
            Map<String, String> sources = new LinkedHashMap<>();
            List<Path> sourceFiles = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                String name = "Q" + (procedures.size() + i);
                Candidate candidate = candidates.get(i);
                String source = source(namespace, name, database.bindTables(candidate.sql().text()),
                        candidate.joinOrder(), candidate.sql().parameters());
                sources.put(name, source);
                Path file = directory.resolve(name + ".java");
                Files.writeString(file, source);
                sourceFiles.add(file);
            }
            compile(directory, sourceFiles);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes); var files = Files.walk(directory.resolve(namespace))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    JarEntry entry = new JarEntry(directory.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(0);
                    jar.putNextEntry(entry);
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
            database.call("@UpdateClasses", bytes.toByteArray(), null);
            classesLoaded = true;
            List<Prepared> result = new ArrayList<>();
            StringBuilder declarations = new StringBuilder();
            for (String procedure : sources.keySet()) {
                declarations.append("CREATE PROCEDURE PARTITION ON TABLE ").append(routing)
                        .append(" COLUMN bucket PARAMETER 0 FROM CLASS ").append(namespace).append(".").append(procedure).append(";\n");
            }
            if (wide) {
                System.out.println("Compiling " + sources.size() + " instrumented procedure plans in one catalog update");
                ddl(declarations.toString());
                procedures.addAll(sources.keySet());
            }
            int index = 0;
            for (var entry : sources.entrySet()) {
                String procedure = entry.getKey();
                Candidate candidate = candidates.get(index++);
                if (!wide) {
                    ddl("CREATE PROCEDURE PARTITION ON TABLE " + routing + " COLUMN bucket PARAMETER 0 FROM CLASS "
                            + namespace + "." + procedure + ";");
                    procedures.add(procedure);
                }
                VoltTable plan = database.call("@ExplainProc", procedure).getResults()[0];
                StringBuilder explanation = new StringBuilder();
                while (plan.advanceRow()) explanation.append(plan.getString("EXECUTION_PLAN")).append('\n');
                if (explanation.isEmpty()) throw new IllegalStateException("Empty execution plan for " + procedure);
                result.add(new Prepared(procedure, candidate, entry.getValue(), explanation.toString(), arguments(candidate)));
            }
            return result;
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static String source(String namespace, String name, String sql, String joinOrder, List<Object> parameters) {
        List<String> arguments = new ArrayList<>();
        List<String> signature = new ArrayList<>(List.of("int bucket"));
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            String type;
            if (value instanceof String) type = "String";
            else if (value instanceof Long) type = "long";
            else if (value instanceof Double) type = "double";
            else throw new IllegalArgumentException("Unsupported procedure parameter " + value);
            signature.add(type + " p" + i);
            arguments.add("p" + i);
        }
        return """
                package %s;
                import org.voltdb.*;
                public final class %s extends VoltProcedure {
                    public final SQLStmt query = new SQLStmt("%s"%s);
                    public VoltTable[] run(%s) {
                        long clockStart = System.nanoTime();
                        long timerPair = System.nanoTime() - clockStart;
                        Object[] arguments = new Object[]{%s};
                        long start = System.nanoTime();
                        voltQueueSQL(query, arguments);
                        VoltTable[] rows = voltExecuteSQL(true);
                        long elapsed = System.nanoTime() - start;
                        VoltTable timing = new VoltTable(new VoltTable.ColumnInfo("EXECUTION_NS", VoltType.BIGINT),
                                new VoltTable.ColumnInfo("TIMER_PAIR_NS", VoltType.BIGINT));
                        timing.addRow(elapsed, timerPair);
                        return new VoltTable[]{rows[0], timing};
                    }
                }
                """.formatted(namespace, name, escape(sql),
                        joinOrder.isEmpty() ? "" : ", \"" + escape(joinOrder) + "\"",
                        String.join(", ", signature), String.join(", ", arguments));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void compile(Path directory, List<Path> sources) throws Exception {
        Path api = Path.of(System.getenv().getOrDefault("VOLTDB_PROCEDURE_API",
                Path.of("target", "voltdb-procedure-api.jar").toString()));
        if (!Files.isRegularFile(api)) throw new FileNotFoundException("Missing pinned VoltDB procedure API: " + api);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(api)));
        if (!hash.equals("bf3e82c1b3211006da00a0d59f46dc7300fd44bc534cd4af88f8f3948c53a8ff")) {
            throw new IllegalStateException("Procedure API SHA-256 mismatch");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required to compile benchmark procedures");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            boolean success = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "11", "-classpath", api.toAbsolutePath().toString(), "-d", directory.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call();
            if (!success) throw new IllegalStateException("Procedure compilation failed: " + diagnostics.getDiagnostics());
        }
    }

    Sample run(Prepared query, Workloads.Scenario scenario) throws Exception {
        VoltTable[] response = database.call(query.procedure(), query.arguments()).getResults();
        if (response.length != 2 || response[1].getRowCount() != 1
                || !response[1].getColumnName(0).equals("EXECUTION_NS")) {
            throw new IllegalStateException("Malformed server timing response");
        }
        response[1].advanceRow();
        long duration = response[1].getLong("EXECUTION_NS");
        long timerPair = response[1].getLong("TIMER_PAIR_NS");
        if (duration <= 0) throw new IllegalStateException("Non-positive server execution duration");
        if (timerPair < 0) throw new IllegalStateException("Negative timer-pair duration");
        return new Sample(duration, timerPair, VoltDbEngine.decode(scenario, response[0]));
    }

    Engines.Result validateUntimed(Workloads.Scenario scenario) throws Exception { return database.run(scenario); }

    static String normalizedPlan(String plan) {
        return plan.replaceAll("(?i)[0-9a-f]{32}", "<instance>");
    }

    Map<String, Object> evidence() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", database.serverVersion());
        result.put("clientProcedureTimeoutMs", wide ? 600000 : 120000);
        result.put("additionalIndexDdl", List.copyOf(indexDdl));
        result.put("indexes", tables(database.call("@SystemCatalog", "INDEXINFO").getResults()));
        VoltTable catalog = database.call("@SystemCatalog", "PROCEDURES").getResults()[0];
        Set<String> verified = new HashSet<>();
        while (catalog.advanceRow()) {
            String name = catalog.getString("PROCEDURE_NAME");
            if (!procedures.contains(name)) continue;
            var properties = Engines.JSON.readTree(catalog.getString("REMARKS"));
            if (!properties.path("readOnly").asBoolean() || !properties.path("singlePartition").asBoolean()
                    || properties.path("partitionParameter").asInt(-1) != 0) {
                throw new IllegalStateException("Procedure is not read-only and single-partition: " + name + " " + properties);
            }
            verified.add(name);
        }
        if (verified.size() != procedures.size()) throw new IllegalStateException("Instrumented procedures absent from catalog");
        result.put("verifiedReadOnlySinglePartitionProcedures", verified.stream().sorted().toList());
        result.put("procedures", tables(new VoltTable[]{catalog}));
        result.put("deployment", tables(database.call("@SystemInformation", "DEPLOYMENT").getResults()));
        result.put("overview", tables(database.call("@SystemInformation", "OVERVIEW").getResults()));
        result.put("indexStatistics", tables(database.call("@Statistics", "INDEX", 0).getResults()));
        return result;
    }

    private static List<Object> tables(VoltTable[] tables) throws Exception {
        List<Object> result = new ArrayList<>();
        for (VoltTable table : tables) result.add(Engines.JSON.readTree(table.toJSONString()));
        return result;
    }

    private void ddl(String sql) throws Exception { database.call("@AdHoc", sql); }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (String procedure : procedures) {
            try { ddl("DROP PROCEDURE " + procedure + ";"); }
            catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        try {
            if (classesLoaded) database.call("@UpdateClasses", null, namespace + ".*");
            if (routingCreated) ddl("DROP TABLE " + routing + ";");
        } catch (Exception error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        } finally {
            try { database.close(); }
            catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
