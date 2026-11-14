package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.Operator;
import java.time.LocalDate;
import java.util.*;
import static com.kapoorlabs.benchmark.Workloads.*;
import static com.kapoorlabs.kiara.domain.Operator.*;

final class WideWorkloads {
    static final List<Integer> WIDTHS = List.of(2, 4, 8, 12, 16);
    static final List<String> SCALARS = List.of("quantity", "category", "status", "region", "brand",
            "optional", "serial", "date", "timestamp");
    static final List<String> MIXED = List.of("quantity", "tags", "category", "numbers", "status", "dates",
            "region", "timestamps", "brand", "bands", "optional", "windows", "serial", "timeWindows", "date", "timestamp");
    static final String COVERING_COLUMNS = "f_quantity_num, f_category, f_status, f_region, f_brand, "
            + "f_optional, f_serial_num, f_date_num, f_timestamp_num, id";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final Map<String, List<String>> POINTS = Map.of(
            "tags", List.of("hitA", "hitB"), "numbers", List.of("10", "20"),
            "dates", List.of("2026-03-01", "2026-03-02"),
            "timestamps", List.of("2026-03-01 10:00:00", "2026-03-01 11:00:00"),
            "bands", List.of("20", "30"), "windows", List.of("2026-03-01", "2026-03-02"),
            "timeWindows", List.of("2026-03-01 10:00:00", "2026-03-01 11:00:00"));

    static Datasets.Dataset load(int size) {
        Random random = new Random(SEED + 2);
        List<BenchmarkRecord> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BenchmarkRecord row = new BenchmarkRecord();
            row.setId(String.format(Locale.ROOT, "%07d", i));
            row.setQuantity(score(random, i, 1_000_000));
            row.setCategory(text(random, i));
            row.setStatus(text(random, i));
            row.setRegion(text(random, i));
            row.setBrand(text(random, i));
            row.setOptional(text(random, i));
            row.setSerial(3_000_000_000L + score(random, i, 100));
            row.setDate(START.plusDays(score(random, i, 100)).toString());
            row.setTimestamp(START.plusDays(score(random, i, 100)) + " 12:00:00");
            row.setTags(collection("tags", score(random, i, 200)));
            row.setNumbers(collection("numbers", score(random, i, 200)));
            row.setDates(collection("dates", score(random, i, 200)));
            row.setTimestamps(collection("timestamps", score(random, i, 200)));
            row.setBands(collection("bands", score(random, i, 200)));
            row.setWindows(collection("windows", score(random, i, 200)));
            row.setTimeWindows(collection("timeWindows", score(random, i, 200)));
            rows.add(row);
        }
        return new Datasets.Dataset("wide-" + size, size + " seeded wide-filter records; seed " + (SEED + 2)
                + "; independent attribute draws except one guaranteed matching anchor; 95% scalar acceptance, "
                + "95% EITHER / 90% ALL collection acceptance; quantity selector compensates expected intersection selectivity.",
                rows, scenarios());
    }

    private static int score(Random random, int row, int bound) {
        int value = random.nextInt(bound);
        return row == 0 ? 0 : value;
    }

    private static String text(Random random, int row) { return score(random, row, 100) < 95 ? "hit" : "miss"; }

    private static String collection(String field, int score) {
        int state = score < 180 ? 0 : score < 185 ? 1 : score < 190 ? 2 : 3;
        if (field.equals("bands")) return List.of("10-40", "10-25", "26-40", "50-60").get(state);
        if (field.equals("windows")) return List.of("2026-03-01-2026-03-02", "2026-03-01-2026-03-01",
                "2026-03-02-2026-03-02", "2026-04-01-2026-04-02").get(state);
        if (field.equals("timeWindows")) return List.of(
                "2026-03-01 09:00:00-2026-03-01 12:00:00", "2026-03-01 09:00:00-2026-03-01 10:30:00",
                "2026-03-01 10:30:00-2026-03-01 12:00:00", "2026-04-01 09:00:00-2026-04-01 12:00:00").get(state);
        List<String> values = POINTS.get(field);
        String other = switch (field) {
            case "tags" -> "miss";
            case "numbers" -> "99";
            case "dates" -> "2026-04-01";
            case "timestamps" -> "2026-04-01 10:00:00";
            default -> throw new IllegalArgumentException("Unknown collection " + field);
        };
        return switch (state) {
            case 0 -> String.join(",", values);
            case 1 -> values.getFirst() + "," + other;
            case 2 -> values.getLast() + "," + other;
            default -> other;
        };
    }

    static List<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        for (String family : List.of("scalar", "either", "all")) {
            for (int width : WIDTHS) {
                if (family.equals("scalar") && width > SCALARS.size()) continue;
                List<String> fields = (family.equals("scalar") ? SCALARS : MIXED).subList(0, width);
                for (int perMille : List.of(1, 10, 100)) {
                    Operator operator = family.equals("all") ? CONTAINS_ALL : CONTAINS_EITHER;
                    long lists = fields.stream().filter(VoltDbEngine.LIST_FIELDS::contains).count();
                    double acceptance = Math.pow(.95, width - 1 - lists)
                            * Math.pow(operator == CONTAINS_ALL ? .90 : .95, lists);
                    int cutoff = (int) Math.floor(perMille / 1000.0 / acceptance * 1_000_000);
                    if (cutoff < 1 || cutoff >= 1_000_000) throw new IllegalStateException("Unachievable target");
                    List<Condition> conditions = fields.stream().map(field -> predicate(field, operator, cutoff)).toList();
                    String name = family + "-w" + width + "-s" + perMille;
                    scenarios.add(exact(name, "Wide " + family, width + " distinct attributes; family " + family
                                    + "; expected selectivity " + perMille / 10.0 + "% (actual oracle count reported)",
                            () -> conditions, row -> conditions.stream().allMatch(condition -> matches(row, condition))));
                }
            }
        }
        return List.copyOf(scenarios);
    }

    private static Condition predicate(String field, Operator operator, int cutoff) {
        if (field.equals("quantity")) return c(field, LESS_THAN, Integer.toString(cutoff));
        if (POINTS.containsKey(field)) return c(field, operator, POINTS.get(field).toArray(String[]::new));
        return switch (field) {
            case "serial" -> c(field, LESS_THAN, "3000000095");
            case "date" -> between(field, START.toString(), START.plusDays(94).toString());
            case "timestamp" -> between(field, START + " 00:00:00", START.plusDays(94) + " 23:59:59");
            default -> c(field, EQUAL, field.equals("brand") ? "HIT" : "hit");
        };
    }

    private WideWorkloads() {}
}
