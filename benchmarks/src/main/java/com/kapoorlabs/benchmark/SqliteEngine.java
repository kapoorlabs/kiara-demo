package com.kapoorlabs.benchmark;

import java.sql.*;
import java.util.*;
import org.sqlite.ProgressHandler;
import static com.kapoorlabs.benchmark.VoltDbEngine.*;

final class SqliteEngine implements AutoCloseable {
    enum Profile {
        TUNED, GENERIC;

        static Profile parse(String value) {
            return switch (value) {
                case "tuned" -> TUNED;
                case "generic" -> GENERIC;
                default -> throw new IllegalArgumentException("Unknown SQLite profile: " + value);
            };
        }
    }
    record Candidate(String name, Sql sql) {}
    record Prepared(Candidate candidate, PreparedStatement statement, List<Map<String, Object>> plan,
                    List<Map<String, Object>> bytecode) implements AutoCloseable {
        @Override public void close() throws SQLException { statement.close(); }
    }
    static final class TuningTimeout extends SQLException {
        TuningTimeout(SQLException cause) { super("SQLite tuning candidate exceeded the 10-second execution deadline", cause); }
    }
    private final Connection connection;

    SqliteEngine(List<BenchmarkRecord> rows) throws Exception {
        this(rows, Profile.TUNED);
    }

    SqliteEngine(List<BenchmarkRecord> rows, Profile profile) throws Exception {
        this(rows, profile, false);
    }

    SqliteEngine(List<BenchmarkRecord> rows, Profile profile, boolean wide) throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            execute("PRAGMA temp_store=MEMORY");
            execute("PRAGMA cache_size=-262144");
            execute("PRAGMA foreign_keys=ON");
            execute("PRAGMA automatic_index=ON");
            execute("CREATE TABLE records (id VARCHAR(64) NOT NULL PRIMARY KEY, payload TEXT, "
                    + String.join(", ", scalarColumns()) + ")");
            execute("CREATE TABLE vals (id VARCHAR(64) NOT NULL REFERENCES records(id), field TEXT NOT NULL, "
                    + "text_value TEXT, number_value REAL, prefix TEXT, lower_bound INTEGER, upper_bound INTEGER)");
            connection.setAutoCommit(false);
            try (PreparedStatement record = connection.prepareStatement("INSERT INTO records VALUES ("
                    + String.join(",", Collections.nCopies(2 + scalarColumns().size(), "?")) + ")");
                 PreparedStatement value = connection.prepareStatement("INSERT INTO vals VALUES (?,?,?,?,?,?,?)")) {
                for (BenchmarkRecord row : rows) {
                    bind(record, recordValues(row));
                    record.executeUpdate();
                    for (List<Object> cells : listValues(row)) {
                        bind(value, cells);
                        value.executeUpdate();
                    }
                }
            }
            connection.commit();
            connection.setAutoCommit(true);
            for (String field : Engines.FIELDS) {
                if (LIST_FIELDS.contains(field)) continue;
                execute("CREATE INDEX scalar_" + field + " ON records (" + column(field)
                        + (numeric(field) ? "_num" : "") + ", id)");
                if (Workloads.RANGE_FIELDS.contains(field)) {
                    execute("CREATE INDEX bounds_" + field + " ON records (" + column(field) + "_prefix, "
                            + column(field) + "_lower, " + column(field) + "_upper, id)");
                }
            }
            execute("CREATE INDEX owner ON vals (id, field)");
            execute("CREATE INDEX text_values ON vals (field, text_value, id)");
            execute("CREATE INDEX numeric_values ON vals (field, number_value, id)");
            execute("CREATE INDEX range_values ON vals (field, prefix, lower_bound, upper_bound, id)");
            if (profile == Profile.TUNED) {
                execute("CREATE INDEX range_upper ON vals (field, prefix, upper_bound, lower_bound, id)");
                List<String> composite = List.of("f_brand, f_status, f_region, id", "f_active, f_category, id",
                        "f_tier, f_status, id", "f_optional, f_region, id", "f_brand, f_optional, id",
                        "f_category, f_brand, id", "f_brand, f_date_num, id",
                        "f_brand, f_route_prefix, f_route_lower, f_route_upper, id",
                        "f_category, f_brand, f_band_prefix, f_band_lower, f_band_upper, f_window_lower, f_window_upper, id",
                        "f_category, f_band_prefix, f_band_lower, f_band_upper, id",
                        "f_category, f_window_prefix, f_window_lower, f_window_upper, id");
                for (int i = 0; i < composite.size(); i++) {
                    execute("CREATE INDEX composite_" + i + " ON records (" + composite.get(i) + ")");
                }
                if (wide) execute("CREATE INDEX wide_covering ON records (" + WideWorkloads.COVERING_COLUMNS + ")");
                execute("PRAGMA analysis_limit=0");
                execute("ANALYZE");
            }
            if (!query("PRAGMA quick_check", List.of()).getFirst().containsValue("ok")) {
                throw new IllegalStateException("SQLite integrity check failed");
            }
            if (!query("PRAGMA foreign_key_check", List.of()).isEmpty()) throw new IllegalStateException("Broken list ownership");
        } catch (Exception error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static List<Candidate> candidates(Workloads.Scenario scenario) {
        LinkedHashMap<Sql, Candidate> variants = new LinkedHashMap<>();
        add(variants, "indexed-default", compile(scenario));
        add(variants, "direct-in", compile(scenario, true));
        add(variants, "correlated-exists", exists(scenario));
        exclusionAlternative(scenario).ifPresent(sql -> add(variants, "set-difference", sql));
        intersection(scenario).ifPresent(sql -> add(variants, "intersections", sql));
        return List.copyOf(variants.values());
    }

    static List<Candidate> candidates(Workloads.Scenario scenario, Profile profile) {
        return profile == Profile.TUNED ? candidates(scenario)
                : List.of(new Candidate("generic-original-sql", compile(scenario)));
    }

    private static void add(Map<Sql, Candidate> variants, String name, Sql sql) {
        variants.putIfAbsent(sql, new Candidate(name, sql));
    }

    static Sql exists(Workloads.Scenario scenario) {
        List<String> filters = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (var condition : scenario.conditions().get()) {
            if (!LIST_FIELDS.contains(condition.getColumnName())) {
                filters.add(scalarCondition(condition, parameters, true));
                continue;
            }
            for (Clause clause : condition(condition, true)) {
                List<Sql> tests = clause.all().isEmpty()
                        ? List.of(new Sql(clause.predicate(), clause.parameters())) : clause.all();
                for (Sql test : tests) {
                    filters.add((clause.exclusion() ? "NOT " : "") + "EXISTS (SELECT 1 FROM vals v "
                            + "WHERE v.id = r.id AND v.field = ? AND (" + test.text() + "))");
                    parameters.add(clause.field());
                    parameters.addAll(test.parameters());
                }
            }
        }
        String columns = switch (scenario.mode()) {
            case "typed" -> "r.id, r.payload";
            case "projection" -> "r.id, r.f_category, r.f_price_num";
            case "ids" -> "r.id";
            default -> throw new IllegalArgumentException("No structured adapter for " + scenario.mode());
        };
        return new Sql("SELECT " + columns + " FROM records r WHERE " + String.join(" AND ", filters)
                + (scenario.limit() > 0 ? " LIMIT " + scenario.limit() : ""), List.copyOf(parameters));
    }

    Prepared prepare(Candidate candidate) throws SQLException {
        List<Map<String, Object>> plan = query("EXPLAIN QUERY PLAN " + candidate.sql().text(), candidate.sql().parameters());
        List<Map<String, Object>> bytecode = query("EXPLAIN " + candidate.sql().text(), candidate.sql().parameters());
        if (plan.isEmpty() || bytecode.isEmpty()) throw new IllegalStateException("Missing compiled SQLite plan");
        PreparedStatement statement = connection.prepareStatement(candidate.sql().text());
        try {
            bind(statement, candidate.sql().parameters());
            return new Prepared(candidate, statement, plan, bytecode);
        } catch (SQLException error) {
            try { statement.close(); } catch (SQLException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    QueryExecution.Sample run(Prepared prepared, Workloads.Scenario scenario, boolean tuning) throws SQLException {
        if (!VoltExecutionEngine.timed(scenario)) throw new IllegalArgumentException("Only maps are timed");
        boolean[] expired = {false};
        if (tuning) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            ProgressHandler.setHandler(connection, 10000, new ProgressHandler() {
                @Override protected int progress() {
                    expired[0] = System.nanoTime() > deadline;
                    return expired[0] ? 1 : 0;
                }
            });
        }
        try {
            long clock = System.nanoTime();
            long pair = System.nanoTime() - clock;
            long start = System.nanoTime();
            List<Map<String, String>> rows = new ArrayList<>();
            try (ResultSet result = prepared.statement().executeQuery()) {
                while (result.next()) {
                    Map<String, String> row = new HashMap<>();
                    row.put("ID", result.getString(1));
                    if (scenario.mode().equals("projection")) {
                        String category = result.getString(2);
                        row.put("CATEGORY", Workloads.NULL.equals(category) ? null : category);
                        double price = result.getDouble(3);
                        row.put("PRICE", result.wasNull() ? null : Double.toString(price));
                    }
                    rows.add(row);
                }
            }
            long elapsed = System.nanoTime() - start;
            if (elapsed <= 0 || pair < 0) throw new IllegalStateException("Invalid SQLite clock measurement");
            return new QueryExecution.Sample(elapsed, pair,
                    new Engines.Result(rows.stream().map(row -> row.get("ID")).toList(), rows));
        } catch (SQLException error) {
            if (tuning && expired[0] && error.getErrorCode() == 9) throw new TuningTimeout(error);
            throw error;
        } finally {
            if (tuning) ProgressHandler.clearHandler(connection);
        }
    }

    Engines.Result typed(Workloads.Scenario scenario) throws Exception {
        try (Prepared query = prepare(new Candidate("untimed-typed", compile(scenario)));
             ResultSet result = query.statement().executeQuery()) {
            List<String> ids = new ArrayList<>();
            List<BenchmarkRecord> rows = new ArrayList<>();
            while (result.next()) {
                ids.add(result.getString(1));
                rows.add(Engines.JSON.readValue(result.getString(2), BenchmarkRecord.class));
            }
            return new Engines.Result(ids, rows);
        }
    }

    Map<String, Object> evidence() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sqliteVersion", query("SELECT sqlite_version() AS version", List.of()).getFirst().get("version"));
        result.put("sqliteSourceId", query("SELECT sqlite_source_id() AS source", List.of()).getFirst().get("source"));
        result.put("jdbcVersion", connection.getMetaData().getDriverVersion());
        result.put("compileOptions", query("PRAGMA compile_options", List.of()));
        result.put("databases", query("PRAGMA database_list", List.of()));
        result.put("schema", query("SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY name", List.of()));
        Map<String, Object> indexes = new TreeMap<>();
        for (var index : query("SELECT name FROM sqlite_schema WHERE type='index' ORDER BY name", List.of())) {
            String name = index.get("name").toString();
            if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unexpected index identifier");
            indexes.put(name, query("PRAGMA index_xinfo('" + name + "')", List.of()));
        }
        result.put("indexes", indexes);
        boolean analyzed = !query("SELECT name FROM sqlite_schema WHERE name='sqlite_stat1'", List.of()).isEmpty();
        result.put("analyzed", analyzed);
        result.put("statistics", analyzed ? query("SELECT tbl, idx, stat FROM sqlite_stat1 ORDER BY tbl, idx", List.of()) : List.of());
        result.put("rows", query("SELECT (SELECT COUNT(*) FROM records) AS records, (SELECT COUNT(*) FROM vals) AS vals", List.of()));
        for (String pragma : List.of("temp_store", "cache_size", "page_size", "page_count", "journal_mode",
                "synchronous", "foreign_keys", "automatic_index", "analysis_limit")) {
            result.put(pragma, query("PRAGMA " + pragma, List.of()));
        }
        return result;
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private List<Map<String, Object>> query(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) row.put(rows.getMetaData().getColumnLabel(i), rows.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) statement.setNull(i + 1, Types.NULL);
            else statement.setObject(i + 1, values.get(i));
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
