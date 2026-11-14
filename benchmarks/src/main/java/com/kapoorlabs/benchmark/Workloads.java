package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.Operator;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import static com.kapoorlabs.kiara.domain.Operator.*;

final class Workloads {
    static final long SEED = 20261027L;
    static final String NULL = "null";
    static final Set<String> RANGE_FIELDS = Set.of("band", "route", "bands", "window", "timeWindow", "windows", "timeWindows");
    static final Set<String> NUMBER_FIELDS = Set.of("quantity", "price", "serial", "numbers");
    static final Set<String> DATE_FIELDS = Set.of("date", "timestamp", "dates", "timestamps");

    record Scenario(String name, String group, String detail, Supplier<List<Condition>> conditions,
                    Predicate<BenchmarkRecord> expected, String mode, String text, int limit,
                    boolean comparable) {
        List<String> expectedIds(List<BenchmarkRecord> rows) {
            return rows.stream().filter(expected).map(BenchmarkRecord::getId).sorted().toList();
        }
    }

    static List<BenchmarkRecord> data(int size) {
        Random random = new Random(SEED);
        List<BenchmarkRecord> rows = new ArrayList<>(size);
        String[] names = {"quartz", "sapphire", "emerald", "topaz", "diamond", "opal"};
        for (int i = 0; i < size; i++) {
            BenchmarkRecord row = new BenchmarkRecord();
            row.setId(String.format(Locale.ROOT, "%07d", i));
            row.setCategory("category" + random.nextInt(20));
            row.setBrand("Brand" + random.nextInt(50));
            row.setQuantity(random.nextInt(1000));
            row.setPrice(random.nextInt(100000) / 100.0);
            row.setSerial(3_000_000_000L + i);
            row.setActive(i % 2 == 0);
            row.setOptional(i % 7 == 0 ? null : "available");
            row.setTags(i % 7 == 0 ? null : i % 3 == 0 ? "red,blue" : "green,blue");
            row.setNumbers(i % 2 == 0 ? "10,20,30" : "20,40");
            LocalDate day = LocalDate.of(2024, 1, 1).plusDays(random.nextInt(365));
            row.setDate(day.toString());
            row.setTimestamp(day + " 12:30:00");
            row.setDates(day + "," + day.plusDays(2));
            row.setTimestamps(day + " 12:30:00," + day.plusDays(2) + " 18:00:00");
            int start = random.nextInt(1000);
            row.setBand(start + "-" + (start + 100));
            row.setRoute((i % 2 == 0 ? "AA" : "BB") + start + "-" + (start + 100));
            row.setBands(row.getBand() + "," + (start + 200) + "-" + (start + 250));
            row.setWindow(day + "-" + day.plusDays(10));
            row.setTimeWindow(day + " 00:00:00-" + day.plusDays(10) + " 23:59:59");
            row.setWindows(row.getWindow() + "," + day.plusDays(20) + "-" + day.plusDays(25));
            row.setTimeWindows(row.getTimeWindow() + "," + day.plusDays(20) + " 00:00:00-" + day.plusDays(25) + " 23:59:59");
            row.setName(names[i % names.length]);
            row.setKeywords(i % 2 == 0 ? "running,shoes" : "walking,boots");
            row.setDescription("Stored but not indexed payload " + i);
            rows.add(row);
        }
        return rows;
    }

    static Condition c(String field, Operator op, String... values) {
        return new Condition(field, op, Arrays.asList(values));
    }

    static Condition between(String field, String lower, String upper) {
        return new Condition(field, BETWEEN, lower, upper);
    }

    static Scenario exact(String name, String group, String detail, Supplier<List<Condition>> conditions,
                          Predicate<BenchmarkRecord> expected) {
        return new Scenario(name, group, detail, conditions, expected, "ids", null, 0, true);
    }

    static Scenario single(String name, String group, String field, Operator op, String... values) {
        Condition predicate = c(field, op, values);
        return exact(name, group, field + " " + op + " " + Arrays.toString(values),
                () -> List.of(c(field, op, values)), r -> matches(r, predicate));
    }

    static Scenario interval(String name, String group, String field, String lower, String upper) {
        Condition predicate = between(field, lower, upper);
        return exact(name, group, field + " BETWEEN " + lower + " and " + upper,
                () -> List.of(between(field, lower, upper)), r -> matches(r, predicate));
    }

    static List<Scenario> scenarios() {
        List<Scenario> s = new ArrayList<>();
        s.add(single("text-equal", "Equality", "category", EQUAL, "category3"));
        s.add(single("text-in", "Equality", "category", EQUAL, "category3", "category7"));
        s.add(single("text-not-equal", "Equality", "category", NOT_EQUAL, "category3"));
        s.add(single("case-insensitive", "Equality", "brand", EQUAL, "BRAND7"));
        s.add(single("boolean-equal", "Equality", "active", EQUAL, "true"));
        s.add(single("null-equal", "Missing values", "optional", EQUAL, NULL));
        s.add(single("null-not-equal", "Missing values", "optional", NOT_EQUAL, NULL));
        s.add(single("numeric-equal", "Numeric", "quantity", EQUAL, "500"));
        s.add(single("numeric-lt", "Numeric", "quantity", LESS_THAN, "50"));
        s.add(single("numeric-lte", "Numeric", "quantity", LESS_THAN_EQUAL, "50"));
        s.add(single("numeric-gt", "Numeric", "quantity", GREATER_THAN, "950"));
        s.add(single("numeric-gte", "Numeric", "quantity", GREATER_THAN_EQUAL, "950"));
        s.add(interval("numeric-between", "Numeric", "quantity", "100", "200"));
        for (Scenario decimal : List.of(single("decimal-lte", "Numeric", "price", LESS_THAN_EQUAL, "19.50"),
                single("decimal-gte", "Numeric", "price", GREATER_THAN_EQUAL, "950.25"),
                single("decimal-equal", "Numeric", "price", EQUAL, "19.99"))) {
            s.add(new Scenario(decimal.name, decimal.group, decimal.detail, decimal.conditions, decimal.expected,
                    decimal.mode, decimal.text, decimal.limit, false));
        }
        s.add(single("long-gt", "Numeric", "serial", GREATER_THAN, "3000009500"));
        s.add(single("strings-either", "Lists", "tags", CONTAINS_EITHER, "red", "green"));
        s.add(single("strings-all", "Lists", "tags", CONTAINS_ALL, "red", "blue"));
        s.add(single("strings-not", "Lists", "tags", NOT_CONTAINS, "red"));
        s.add(single("strings-either-null", "Lists", "tags", CONTAINS_EITHER_INCLUDING_NULL, "red"));
        s.add(single("numbers-either", "Lists", "numbers", CONTAINS_EITHER, "10", "40"));
        s.add(single("numbers-all", "Lists", "numbers", CONTAINS_ALL, "10", "30"));
        s.add(single("date-equal", "Dates and times", "date", EQUAL, "2024-06-15"));
        s.add(interval("date-between", "Dates and times", "date", "2024-06-01", "2024-06-30"));
        s.add(single("date-gt", "Dates and times", "date", GREATER_THAN, "2024-12-01"));
        s.add(single("timestamp-gte", "Dates and times", "timestamp", GREATER_THAN_EQUAL, "2024-12-01 12:30:00"));
        s.add(single("timestamp-lt", "Dates and times", "timestamp", LESS_THAN, "2024-01-15 12:30:00"));
        s.add(single("dates-either", "Date and time lists", "dates", CONTAINS_EITHER, "2024-06-15", "2024-06-17"));
        s.add(single("dates-all", "Date and time lists", "dates", CONTAINS_ALL, "2024-06-15", "2024-06-17"));
        s.add(single("timestamps-either", "Date and time lists", "timestamps", CONTAINS_EITHER, "2024-06-15 12:30:00"));
        s.add(single("timestamps-all", "Date and time lists", "timestamps", CONTAINS_ALL, "2024-06-15 12:30:00", "2024-06-17 18:00:00"));
        s.add(single("range-point", "Stored ranges", "band", CONTAINS_EITHER, "500"));
        s.add(single("range-all", "Stored ranges", "band", CONTAINS_ALL, "500", "550"));
        s.add(single("range-not", "Stored ranges", "bands", NOT_CONTAINS, "500"));
        s.add(single("range-equal", "Stored ranges", "band", EQUAL, "500-600"));
        s.add(single("prefix-range", "Stored ranges", "route", CONTAINS_EITHER, "AA500"));
        s.add(single("range-list-point", "Stored ranges", "bands", CONTAINS_EITHER, "500"));
        s.add(single("range-list-all", "Stored ranges", "bands", CONTAINS_ALL, "500", "700"));
        s.add(single("date-range", "Stored ranges", "window", CONTAINS_EITHER, "2024-06-15"));
        s.add(single("datetime-range", "Stored ranges", "timeWindow", CONTAINS_EITHER, "2024-06-15 12:00:00"));
        s.add(single("date-range-list", "Stored ranges", "windows", CONTAINS_EITHER, "2024-06-15"));
        s.add(single("datetime-range-list", "Stored ranges", "timeWindows", CONTAINS_EITHER, "2024-06-15 12:00:00"));
        s.add(single("stemming", "Text", "keywords", CONTAINS_EITHER, "runs"));
        Supplier<List<Condition>> combined = () -> List.of(c("category", EQUAL, "category3", "category7"),
                c("tags", CONTAINS_ALL, "red", "blue"), between("quantity", "100", "700"),
                between("date", "2024-03-01", "2024-09-30"), c("band", CONTAINS_EITHER, "500"));
        s.add(exact("combined-five-fields", "Combined", "AND: category IN, tags ALL, quantity/date BETWEEN, stored range contains point",
                combined, r -> combined.get().stream().allMatch(c -> matches(r, c))));
        Scenario base = single("base", "Results", "category", EQUAL, "category3");
        s.add(new Scenario("projection", "Results", "ID, category and price projection", base.conditions, base.expected,
                "projection", null, 0, true));
        s.add(new Scenario("typed-results", "Results", "Full typed POJO reconstruction", base.conditions, base.expected,
                "typed", null, 0, true));
        s.add(new Scenario("limit-10", "Results", "At most 10 records; engine-dependent subset/order", base.conditions,
                base.expected, "ids", null, 10, false));
        s.add(text("keyword-discovery", "quartz", "keyword", null, r -> r.getName().equals("quartz"), true));
        s.add(text("keyword-typo", "quarz", "keyword", null, r -> r.getName().equals("quartz"), true));
        s.add(text("keyword-stem", "runs", "keyword", null, r -> r.getKeywords().startsWith("running"), true));
        s.add(text("keyword-filtered", "quarz", "keyword", () -> List.of(c("category", EQUAL, "category3")),
                r -> r.getName().equals("quartz") && r.getCategory().equals("category3"), true));
        s.add(text("keyword-two-values", "quartz shoes", "keyword", null, r -> r.getName().equals("quartz"), true));
        s.add(text("keyword-punctuation", "quartz!", "keyword", null, r -> r.getName().equals("quartz"), true));
        s.add(text("keyword-best-subset", "quartz boots", "keyword", null,
                r -> r.getName().equals("quartz") || r.getKeywords().endsWith("boots"), false));
        s.add(text("prefix-predictions", "qu", "predict", null, r -> true, true));
        s.add(text("one-edit-suggestion", "quarz", "suggest", null, r -> true, false));
        return List.copyOf(s);
    }

    private static Scenario text(String name, String text, String mode, Supplier<List<Condition>> conditions,
                                 Predicate<BenchmarkRecord> expected, boolean comparable) {
        return new Scenario(name, "Text", text, conditions == null ? List::of : conditions, expected,
                mode, text, 0, comparable);
    }

    static List<String> values(BenchmarkRecord r, String field) {
        String value = switch (field) {
            case "id" -> r.getId();
            case "category" -> r.getCategory();
            case "status" -> r.getStatus();
            case "region" -> r.getRegion();
            case "tier" -> Objects.toString(r.getTier(), null);
            case "brand" -> r.getBrand() == null ? null : r.getBrand().toLowerCase(Locale.ROOT);
            case "quantity" -> Objects.toString(r.getQuantity(), null);
            case "price" -> Objects.toString(r.getPrice(), null);
            case "serial" -> Objects.toString(r.getSerial(), null);
            case "active" -> Objects.toString(r.getActive(), null);
            case "optional" -> r.getOptional();
            case "tags" -> r.getTags();
            case "numbers" -> r.getNumbers();
            case "date" -> r.getDate();
            case "timestamp" -> r.getTimestamp();
            case "dates" -> r.getDates();
            case "timestamps" -> r.getTimestamps();
            case "band" -> r.getBand();
            case "route" -> r.getRoute();
            case "bands" -> r.getBands();
            case "window" -> r.getWindow();
            case "timeWindow" -> r.getTimeWindow();
            case "windows" -> r.getWindows();
            case "timeWindows" -> r.getTimeWindows();
            case "name" -> r.getName();
            case "keywords" -> r.getKeywords();
            default -> throw new IllegalArgumentException("Unknown indexed field " + field);
        };
        return value == null ? List.of(NULL) : Arrays.asList(value.split(","));
    }

    static boolean matches(BenchmarkRecord row, Condition condition) {
        String field = condition.getColumnName();
        List<String> actual = values(row, field);
        Operator op = condition.getOperator();
        if (op == BETWEEN) {
            return actual.stream().anyMatch(a -> !a.equals(NULL) && compare(field, a, condition.getLowerValue()) >= 0
                    && compare(field, a, condition.getUpperValue()) <= 0);
        }
        List<String> wanted = condition.getValue();
        Predicate<String> has = v -> actual.stream().anyMatch(a -> equal(field, a, v, op != EQUAL && op != NOT_EQUAL));
        return switch (op) {
            case EQUAL, CONTAINS_EITHER -> wanted.stream().anyMatch(has);
            case CONTAINS_EITHER_INCLUDING_NULL -> actual.contains(NULL) || wanted.stream().anyMatch(has);
            case CONTAINS_ALL -> wanted.stream().allMatch(has);
            case NOT_EQUAL, NOT_CONTAINS -> wanted.stream().noneMatch(has);
            case LESS_THAN -> actual.stream().anyMatch(a -> !a.equals(NULL) && compare(field, a, wanted.getFirst()) < 0);
            case LESS_THAN_EQUAL -> actual.stream().anyMatch(a -> !a.equals(NULL) && compare(field, a, wanted.getFirst()) <= 0);
            case GREATER_THAN -> actual.stream().anyMatch(a -> !a.equals(NULL) && compare(field, a, wanted.getFirst()) > 0);
            case GREATER_THAN_EQUAL -> actual.stream().anyMatch(a -> !a.equals(NULL) && compare(field, a, wanted.getFirst()) >= 0);
            default -> throw new IllegalArgumentException("Unsupported oracle operator " + op);
        };
    }

    static boolean equal(String field, String actual, String wanted, boolean point) {
        if (actual.equals(NULL) || wanted.equals(NULL)) return actual.equals(wanted);
        if (RANGE_FIELDS.contains(field) && point) {
            String prefix = field.equals("route") ? wanted.substring(0, 2) : "";
            if (!actual.startsWith(prefix)) return false;
            String[] bounds = rangeBounds(field, actual);
            String value = field.equals("route") ? wanted.substring(2) : wanted;
            return compareRange(field, bounds[0], value) <= 0 && compareRange(field, bounds[1], value) >= 0;
        }
        if (field.equals("brand")) return actual.equalsIgnoreCase(wanted);
        // The fixture uses two known English inflection families; the oracle does not call either engine's stemmer.
        if (field.equals("keywords")) return canonical(actual).equals(canonical(wanted));
        return compare(field, actual, wanted) == 0;
    }

    static String canonical(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "running", "runs", "run" -> "run";
            case "walking", "walks", "walk" -> "walk";
            case "shoes", "shoe" -> "shoe";
            case "boots", "boot" -> "boot";
            default -> text.toLowerCase(Locale.ROOT);
        };
    }

    static String[] rangeBounds(String field, String value) {
        if (field.equals("route")) return value.substring(2).split("-");
        if (field.equals("band") || field.equals("bands")) return value.split("-");
        int width = field.equals("window") || field.equals("windows") ? 10 : 19;
        return new String[]{value.substring(0, width), value.substring(width + 1)};
    }

    private static int compareRange(String field, String a, String b) {
        return Set.of("band", "bands", "route").contains(field)
                ? Long.compare(Long.parseLong(a), Long.parseLong(b)) : a.compareTo(b);
    }

    private static int compare(String field, String a, String b) {
        return NUMBER_FIELDS.contains(field) ? new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b))
                : a.compareTo(b);
    }
}
