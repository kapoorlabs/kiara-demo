package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.Operator;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.voltdb.VoltTable;
import org.voltdb.client.*;
import org.voltdb.client.VoltBulkLoader.BulkLoaderFailureCallBack;
import org.voltdb.client.VoltBulkLoader.VoltBulkLoader;
import static com.kapoorlabs.kiara.domain.Operator.*;

final class VoltDbEngine implements Engines.Engine {
    record Sql(String text, List<Object> parameters) {}
    record Clause(String field, String predicate, List<Object> parameters, boolean exclusion, List<Sql> all) {}
    static final Set<String> LIST_FIELDS = Set.of("tags", "numbers", "dates", "timestamps", "bands",
            "windows", "timeWindows", "name", "keywords");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private final Client client;
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String recordsTable = "kiara_records_" + suffix;
    private final String valuesTable = "kiara_vals_" + suffix;
    private final Map<String, Sql> statements = new HashMap<>();
    private boolean recordsCreated;
    private boolean valuesCreated;

    VoltDbEngine(List<BenchmarkRecord> rows) throws Exception {
        this(rows, "127.0.0.1");
    }

    VoltDbEngine(List<BenchmarkRecord> rows, String host) throws Exception {
        this(rows, host, 120000);
    }

    VoltDbEngine(List<BenchmarkRecord> rows, String host, int procedureTimeoutMs) throws Exception {
        ClientConfig config = new ClientConfig();
        config.setProcedureCallTimeout(procedureTimeoutMs);
        client = ClientFactory.createClient(config);
        try {
            if (!Set.of("127.0.0.1", "voltdb").contains(host)) {
                throw new IllegalArgumentException("Only the dedicated local benchmark server is allowed");
            }
            client.createConnection(host, 21212);
            execute("CREATE TABLE " + recordsTable + " (id VARCHAR(64) NOT NULL PRIMARY KEY, payload VARCHAR(65000), "
                    + String.join(", ", scalarColumns()) + ");");
            recordsCreated = true;
            for (String field : Engines.FIELDS) {
                if (LIST_FIELDS.contains(field)) continue;
                execute("CREATE INDEX kiara_scalar_" + field + "_" + suffix + " ON " + recordsTable
                        + " (" + column(field) + (numeric(field) ? "_num" : "") + ");");
                if (Workloads.RANGE_FIELDS.contains(field)) {
                    execute("CREATE INDEX kiara_bounds_" + field + "_" + suffix + " ON " + recordsTable
                            + " (" + column(field) + "_prefix, " + column(field) + "_lower, " + column(field) + "_upper);");
                }
            }
            execute("CREATE TABLE " + valuesTable + " (id VARCHAR(64) NOT NULL, field VARCHAR(32) NOT NULL, text_value VARCHAR(1024), "
                    + "number_value FLOAT, prefix VARCHAR(16), lower_bound BIGINT, upper_bound BIGINT);");
            valuesCreated = true;
            execute("CREATE INDEX kiara_owner_" + suffix + " ON " + valuesTable + " (id, field);");
            execute("CREATE INDEX kiara_text_" + suffix + " ON " + valuesTable + " (field, text_value, id);");
            execute("CREATE INDEX kiara_number_" + suffix + " ON " + valuesTable + " (field, number_value, id);");
            execute("CREATE INDEX kiara_range_" + suffix + " ON " + valuesTable + " (field, prefix, lower_bound, upper_bound, id);");
            load(rows);
        } catch (Exception error) {
            try {
                close();
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private void execute(String sql) throws Exception {
        check(client.callProcedure("@AdHoc", sql));
    }

    ClientResponse call(String procedure, Object... arguments) throws Exception {
        return check(client.callProcedure(procedure, arguments));
    }

    String bindTables(String sql) {
        return sql.replace("FROM records r", "FROM " + recordsTable + " r")
                .replace("JOIN vals ", "JOIN " + valuesTable + " ")
                .replace("FROM vals ", "FROM " + valuesTable + " ");
    }

    String recordsTable() { return recordsTable; }
    String valuesTable() { return valuesTable; }

    private void load(List<BenchmarkRecord> rows) throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        BulkLoaderFailureCallBack callback = (handle, fields, response) ->
                failure.compareAndSet(null, response.getStatusString());
        VoltBulkLoader records = client.getNewBulkLoader(recordsTable, 1000, callback);
        VoltBulkLoader values = client.getNewBulkLoader(valuesTable, 1000, callback);
        try {
            for (BenchmarkRecord row : rows) {
                records.insertRow(row.getId(), recordValues(row).toArray());
                for (List<Object> value : listValues(row)) values.insertRow(row.getId(), value.toArray());
                if (failure.get() != null) throw new IllegalStateException("VoltDB loading failed: " + failure.get());
            }
            records.drain();
            values.drain();
            if (failure.get() != null) throw new IllegalStateException("VoltDB loading failed: " + failure.get());
        } finally {
            try {
                records.close();
            } finally {
                values.close();
            }
        }
    }

    static List<Object> recordValues(BenchmarkRecord row) throws Exception {
        List<Object> record = new ArrayList<>();
        record.add(row.getId());
        record.add(Engines.JSON.writeValueAsString(row));
        for (String field : Engines.FIELDS) {
            if (LIST_FIELDS.contains(field)) continue;
            String value = Workloads.values(row, field).getFirst();
            record.add(normalize(field, value));
            if (numeric(field)) record.add(value.equals(Workloads.NULL) ? null : number(field, value));
            if (Workloads.RANGE_FIELDS.contains(field)) {
                boolean missing = value.equals(Workloads.NULL);
                String[] bounds = missing ? null : Workloads.rangeBounds(field, value);
                record.add(field.equals("route") && !missing ? value.substring(0, 2) : "");
                record.add(missing ? null : rangeNumber(field, bounds[0]));
                record.add(missing ? null : rangeNumber(field, bounds[1]));
            }
        }
        return record;
    }

    static List<List<Object>> listValues(BenchmarkRecord row) {
        List<List<Object>> result = new ArrayList<>();
        for (String field : Engines.FIELDS) {
            if (!LIST_FIELDS.contains(field)) continue;
            for (String value : Workloads.values(row, field)) {
                Double number = null;
                String prefix = "";
                Long lower = null;
                Long upper = null;
                if (!value.equals(Workloads.NULL)) {
                    if (Workloads.RANGE_FIELDS.contains(field)) {
                        String[] bounds = Workloads.rangeBounds(field, value);
                        prefix = field.equals("route") ? value.substring(0, 2) : "";
                        lower = rangeNumber(field, bounds[0]);
                        upper = rangeNumber(field, bounds[1]);
                    } else if (numeric(field)) {
                        number = number(field, value);
                    }
                }
                result.add(Arrays.asList(row.getId(), field, normalize(field, value), number, prefix, lower, upper));
            }
        }
        return result;
    }

    static boolean supported(Workloads.Scenario scenario) {
        return Set.of("ids", "typed", "projection").contains(scenario.mode());
    }

    static Sql compile(Workloads.Scenario scenario) {
        return compile(scenario, false);
    }

    static Sql compile(Workloads.Scenario scenario, boolean optimized) {
        if (!supported(scenario)) throw new IllegalArgumentException("No equivalent SQL adapter for " + scenario.mode());
        List<Object> parameters = new ArrayList<>();
        List<Clause> clauses = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        for (Condition condition : scenario.conditions().get()) {
            if (LIST_FIELDS.contains(condition.getColumnName())) {
                clauses.addAll(condition(condition, optimized));
            } else {
                filters.add(scalarCondition(condition, parameters, optimized));
            }
        }
        String selection = switch (scenario.mode()) {
            case "typed" -> "r.id, r.payload";
            case "projection" -> "r.id, r.f_category, r.f_price_num";
            default -> "r.id";
        };
        List<String> joins = new ArrayList<>();
        List<String> having = new ArrayList<>();
        List<Object> havingParameters = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            Clause clause = clauses.get(i);
            String alias = "v" + i;
            joins.add(" JOIN vals " + alias + " ON " + alias + ".id = r.id AND " + alias + ".field = ?"
                    + (clause.exclusion() ? "" : " AND (" + clause.predicate().replace("v.", alias + ".") + ")"));
            if (clause.exclusion()) {
                having.add("MAX(CASE WHEN (" + clause.predicate().replace("v.", alias + ".") + ") THEN 1 ELSE 0 END) = 0");
                havingParameters.addAll(clause.parameters());
            }
            for (int j = 0; j < clause.all().size(); j++) {
                Sql required = clause.all().get(j);
                int marker = j + 1;
                // Distinct markers keep parameterized aggregates distinct in the legacy planner.
                having.add("MAX(CASE WHEN (" + required.text().replace("v.", alias + ".") + ") THEN "
                        + marker + " ELSE 0 END) = " + marker);
                havingParameters.addAll(required.parameters());
            }
        }
        List<Object> ordered = new ArrayList<>();
        for (Clause clause : clauses) {
            ordered.add(clause.field());
            if (!clause.exclusion()) ordered.addAll(clause.parameters());
        }
        ordered.addAll(parameters);
        ordered.addAll(havingParameters);
        if (optimized && filters.isEmpty() && clauses.size() == 1 && scenario.mode().equals("ids")) {
            Clause clause = clauses.getFirst();
            String sql = "SELECT " + (having.isEmpty() ? "DISTINCT " : "") + "v0.id FROM vals v0 WHERE v0.field = ?"
                    + (clause.exclusion() ? "" : " AND (" + clause.predicate().replace("v.", "v0.") + ")")
                    + (having.isEmpty() ? "" : " GROUP BY v0.id HAVING " + String.join(" AND ", having))
                    + (scenario.limit() > 0 ? " LIMIT " + scenario.limit() : "");
            return new Sql(sql, List.copyOf(ordered));
        }
        String sql = "SELECT " + (clauses.isEmpty() || !having.isEmpty() ? "" : "DISTINCT ") + selection + " FROM records r" + String.join("", joins)
                + (filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters))
                + (having.isEmpty() ? "" : " GROUP BY " + selection + " HAVING " + String.join(" AND ", having))
                + (scenario.limit() > 0 ? " LIMIT " + scenario.limit() : "");
        return new Sql(sql, List.copyOf(ordered));
    }

    static Optional<Sql> exclusionAlternative(Workloads.Scenario scenario) {
        if (!scenario.mode().equals("ids") || scenario.limit() != 0) return Optional.empty();
        List<Condition> input = scenario.conditions().get();
        List<Condition> lists = input.stream().filter(c -> LIST_FIELDS.contains(c.getColumnName())).toList();
        if (lists.size() != 1 || !Set.of(NOT_EQUAL, NOT_CONTAINS).contains(lists.getFirst().getOperator())) {
            return Optional.empty();
        }
        List<Object> parameters = new ArrayList<>();
        List<String> filters = input.stream().filter(c -> !LIST_FIELDS.contains(c.getColumnName()))
                .map(c -> scalarCondition(c, parameters)).toList();
        Clause excluded = condition(lists.getFirst(), true).getFirst();
        parameters.add(excluded.field());
        parameters.addAll(excluded.parameters());
        return Optional.of(new Sql("SELECT r.id FROM records r"
                + (filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters))
                + " EXCEPT SELECT v.id FROM vals v WHERE v.field = ? AND (" + excluded.predicate() + ")",
                List.copyOf(parameters)));
    }

    static Optional<Sql> intersection(Workloads.Scenario scenario) {
        if (!scenario.mode().equals("ids") || scenario.limit() != 0) return Optional.empty();
        List<String> filters = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        List<Clause> lists = new ArrayList<>();
        for (var condition : scenario.conditions().get()) {
            if (LIST_FIELDS.contains(condition.getColumnName())) lists.addAll(condition(condition, true));
            else filters.add(scalarCondition(condition, parameters, true));
        }
        if (lists.isEmpty() || lists.stream().anyMatch(Clause::exclusion)) return Optional.empty();
        List<String> sets = new ArrayList<>();
        if (!filters.isEmpty()) sets.add("SELECT r.id FROM records r WHERE " + String.join(" AND ", filters));
        for (Clause clause : lists) {
            List<Sql> tests = clause.all().isEmpty() ? List.of(new Sql(clause.predicate(), clause.parameters())) : clause.all();
            for (Sql test : tests) {
                sets.add("SELECT DISTINCT v.id FROM vals v WHERE v.field = ? AND (" + test.text() + ")");
                parameters.add(clause.field());
                parameters.addAll(test.parameters());
            }
        }
        return Optional.of(new Sql(String.join(" INTERSECT ", sets), List.copyOf(parameters)));
    }

    static List<String> scalarColumns() {
        List<String> columns = new ArrayList<>();
        for (String field : Engines.FIELDS) {
            if (LIST_FIELDS.contains(field)) continue;
            columns.add(column(field) + " VARCHAR(1024)");
            if (numeric(field)) columns.add(column(field) + "_num FLOAT");
            if (Workloads.RANGE_FIELDS.contains(field)) {
                columns.add(column(field) + "_prefix VARCHAR(16)");
                columns.add(column(field) + "_lower BIGINT");
                columns.add(column(field) + "_upper BIGINT");
            }
        }
        return columns;
    }

    static String column(String field) {
        if (!Engines.FIELDS.contains(field)) throw new IllegalArgumentException("Unknown field " + field);
        return "f_" + field;
    }

    private static String scalarCondition(Condition condition, List<Object> parameters) {
        return scalarCondition(condition, parameters, false);
    }

    static String scalarCondition(Condition condition, List<Object> parameters, boolean optimized) {
        String field = condition.getColumnName();
        String base = "r." + column(field);
        String valueColumn = base + (numeric(field) ? "_num" : "");
        Operator op = condition.getOperator();
        if (op == BETWEEN) {
            parameters.add(queryValue(field, condition.getLowerValue()));
            parameters.add(queryValue(field, condition.getUpperValue()));
            return "(" + valueColumn + " >= ? AND " + valueColumn + " <= ?)";
        }
        if (Set.of(LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL).contains(op)) {
            String operator = switch (op) {
                case LESS_THAN -> "<";
                case LESS_THAN_EQUAL -> "<=";
                case GREATER_THAN -> ">";
                default -> ">=";
            };
            parameters.add(queryValue(field, condition.getValue().getFirst()));
            return valueColumn + " " + operator + " ?";
        }
        List<String> matches = new ArrayList<>();
        boolean negate = op == NOT_EQUAL || op == NOT_CONTAINS;
        for (String value : condition.getValue()) {
            String predicate;
            if (Workloads.RANGE_FIELDS.contains(field) && op != EQUAL && op != NOT_EQUAL && !value.equals(Workloads.NULL)) {
                long point = rangeNumber(field, field.equals("route") ? value.substring(2) : value);
                parameters.add(field.equals("route") ? value.substring(0, 2) : "");
                parameters.add(point);
                parameters.add(point);
                predicate = base + "_prefix = ? AND " + base + "_lower <= ? AND " + base + "_upper >= ?";
            } else {
                parameters.add(queryValue(field, value));
                predicate = (value.equals(Workloads.NULL) ? base : valueColumn) + " = ?";
            }
            matches.add(negate ? "COALESCE((" + predicate + "), FALSE)" : "(" + predicate + ")");
        }
        if (op == CONTAINS_EITHER_INCLUDING_NULL) {
            matches.add(base + " = ?");
            parameters.add(Workloads.NULL);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("Empty condition for " + field);
        if (optimized && op == EQUAL && condition.getValue().size() > 1
                && (!numeric(field) || !condition.getValue().contains(Workloads.NULL))) {
            return valueColumn + " IN (" + String.join(", ", Collections.nCopies(condition.getValue().size(), "?")) + ")";
        }
        if (optimized && op == NOT_EQUAL && !numeric(field) && condition.getValue().size() == 1) {
            // Loaded raw scalar strings always use a non-null sentinel for missing values.
            return base + " <> ?";
        }
        return (negate ? "NOT " : "")
                + "(" + String.join(op == CONTAINS_ALL ? " AND " : " OR ", matches) + ")";
    }

    static List<Clause> condition(Condition condition, boolean optimized) {
        String field = condition.getColumnName();
        if (!Engines.FIELDS.contains(field)) throw new IllegalArgumentException("Unknown field " + field);
        Operator op = condition.getOperator();
        if (op == BETWEEN) {
            String column = numeric(field) ? "v.number_value" : "v.text_value";
            List<Object> bounds = List.of(queryValue(field, condition.getLowerValue()), queryValue(field, condition.getUpperValue()));
            return List.of(new Clause(field, column + " >= ? AND " + column + " <= ?", bounds, false, List.of()));
        }
        if (Set.of(LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL).contains(op)) {
            String operator = switch (op) {
                case LESS_THAN -> "<";
                case LESS_THAN_EQUAL -> "<=";
                case GREATER_THAN -> ">";
                default -> ">=";
            };
            return List.of(new Clause(field, "v.number_value " + operator + " ?",
                    List.of(number(field, condition.getValue().getFirst())), false, List.of()));
        }
        List<String> matches = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        List<Sql> all = new ArrayList<>();
        for (String value : condition.getValue()) {
            String predicate;
            List<Object> arguments;
            if (Workloads.RANGE_FIELDS.contains(field) && op != EQUAL && op != NOT_EQUAL && !value.equals(Workloads.NULL)) {
                String prefix = field.equals("route") ? value.substring(0, 2) : "";
                long point = rangeNumber(field, field.equals("route") ? value.substring(2) : value);
                predicate = "v.prefix = ? AND v.lower_bound <= ? AND v.upper_bound >= ?";
                arguments = List.of(prefix, point, point);
            } else {
                String column = numeric(field) && !value.equals(Workloads.NULL) ? "v.number_value" : "v.text_value";
                predicate = column + " = ?";
                arguments = List.of(queryValue(field, value));
            }
            matches.add("(" + predicate + ")");
            parameters.addAll(arguments);
            all.add(new Sql(predicate, arguments));
        }
        if (op == CONTAINS_EITHER_INCLUDING_NULL) {
            matches.add("(v.text_value = ?)");
            parameters.add(Workloads.NULL);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("Empty condition for " + field);
        if (optimized && !Workloads.RANGE_FIELDS.contains(field) && matches.size() > 1
                && (!numeric(field) || !condition.getValue().contains(Workloads.NULL))
                && op != CONTAINS_EITHER_INCLUDING_NULL) {
            String valueColumn = numeric(field) ? "v.number_value" : "v.text_value";
            matches = List.of(valueColumn + " IN (" + String.join(", ", Collections.nCopies(parameters.size(), "?")) + ")");
        }
        return List.of(new Clause(field, String.join(" OR ", matches), List.copyOf(parameters),
                op == NOT_EQUAL || op == NOT_CONTAINS, op == CONTAINS_ALL ? List.copyOf(all) : List.of()));
    }

    private static String normalize(String field, String value) {
        return Set.of("brand", "name", "keywords").contains(field) ? value.toLowerCase(Locale.ROOT) : value;
    }

    static boolean numeric(String field) {
        return Workloads.NUMBER_FIELDS.contains(field) || Workloads.DATE_FIELDS.contains(field);
    }

    private static Object queryValue(String field, String value) {
        return numeric(field) && !value.equals(Workloads.NULL) ? number(field, value) : normalize(field, value);
    }

    private static double number(String field, String value) {
        if (Set.of("date", "dates").contains(field)) return LocalDate.parse(value).toEpochDay();
        if (Set.of("timestamp", "timestamps").contains(field)) {
            return LocalDateTime.parse(value, TIMESTAMP).toEpochSecond(ZoneOffset.UTC);
        }
        return Double.parseDouble(value);
    }

    private static long rangeNumber(String field, String value) {
        if (Set.of("window", "windows").contains(field)) return LocalDate.parse(value).toEpochDay();
        if (Set.of("timeWindow", "timeWindows").contains(field)) {
            return LocalDateTime.parse(value, TIMESTAMP).toEpochSecond(ZoneOffset.UTC);
        }
        return Long.parseLong(value);
    }

    String serverVersion() throws Exception {
        VoltTable information = check(client.callProcedure("@SystemInformation", "OVERVIEW")).getResults()[0];
        while (information.advanceRow()) {
            if (information.getString("KEY").equals("VERSION")) return information.getString("VALUE");
        }
        throw new IllegalStateException("Server did not report its version");
    }

    @Override
    public Engines.Result run(Workloads.Scenario scenario) throws Exception {
        Sql statement = statements.computeIfAbsent(scenario.name(), ignored -> compile(scenario));
        Object[] arguments = new Object[statement.parameters().size() + 1];
        arguments[0] = bindTables(statement.text());
        for (int i = 0; i < statement.parameters().size(); i++) arguments[i + 1] = statement.parameters().get(i);
        VoltTable results;
        try {
            results = check(client.callProcedure("@AdHoc", arguments)).getResults()[0];
        } catch (ProcCallException error) {
            throw new IllegalStateException("VoltDB query failed for " + scenario.name() + ": " + statement.text(), error);
        }
        return decode(scenario, results);
    }

    static Engines.Result decode(Workloads.Scenario scenario, VoltTable results) throws Exception {
        List<String> ids = new ArrayList<>();
        List<Object> payload = new ArrayList<>();
            while (results.advanceRow()) {
                String id = results.getString(0);
                ids.add(id);
                if (scenario.mode().equals("typed")) {
                    payload.add(Engines.JSON.readValue(results.getString(1), BenchmarkRecord.class));
                } else if (scenario.mode().equals("projection")) {
                    Map<String, String> row = new HashMap<>();
                    row.put("ID", id);
                    String category = results.getString(1);
                    row.put("CATEGORY", Workloads.NULL.equals(category) ? null : category);
                    double price = results.getDouble(2);
                    row.put("PRICE", results.wasNull() ? null : Double.toString(price));
                    payload.add(row);
                }
            }
        return new Engines.Result(ids, payload);
    }

    private static ClientResponse check(ClientResponse response) {
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new IllegalStateException("VoltDB procedure failed: " + response.getStatusString());
        }
        return response;
    }

    @Override
    public void close() throws Exception {
        try {
            client.drain();
            if (valuesCreated) execute("DROP TABLE " + valuesTable + ";");
            if (recordsCreated) execute("DROP TABLE " + recordsTable + ";");
        } finally {
            client.close();
        }
    }
}
