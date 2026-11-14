package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.Condition;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import static com.kapoorlabs.benchmark.Workloads.*;
import static com.kapoorlabs.kiara.domain.Operator.*;

final class Datasets {
    static final String DEFAULTS = "retail-3788,catalog-10000,catalog-100000,schedule-10000,schedule-100000";
    static final String RETAIL_SHA256 = "08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253";
    record Dataset(String name, String description, List<BenchmarkRecord> rows, List<Scenario> scenarios) {}

    static Dataset load(String name) throws Exception {
        if (name.equals("retail-3788")) return retail();
        if (!name.matches("(catalog|schedule|mixed|wide)-[1-9][0-9]*")) {
            throw new IllegalArgumentException("Unknown dataset: " + name);
        }
        int size = Integer.parseInt(name.substring(name.indexOf('-') + 1));
        if (size < 1000) throw new IllegalArgumentException("Synthetic datasets must contain at least 1000 rows");
        if (name.startsWith("wide-")) return WideWorkloads.load(size);
        if (name.startsWith("mixed-")) {
            return new Dataset(name, "Original all-feature mixed fixture (optional legacy workload)", data(size), scenarios());
        }
        return name.startsWith("catalog-") ? catalog(size) : schedule(size);
    }

    private static BenchmarkRecord row(int i) {
        BenchmarkRecord r = new BenchmarkRecord();
        r.setId(String.format(Locale.ROOT, "%07d", i));
        return r;
    }

    private static Dataset catalog(int size) {
        Random random = new Random(SEED);
        String[] adjectives = {"wireless", "wired", "smart", "compact", "portable", "vintage", "modern", "classic",
                "rustic", "ceramic", "wooden", "metal", "glass", "leather", "cotton", "solar", "rechargeable",
                "waterproof", "foldable", "ergonomic"};
        String[] colors = {"black", "white", "silver", "blue", "green", "red", "oak", "grey"};
        String[] nouns = {"lamp", "speaker", "chair", "desk", "mug", "kettle", "lantern", "clock", "charger",
                "keyboard", "mouse", "backpack", "bottle", "blanket", "pillow", "headphones", "camera", "router", "monitor", "shelf"};
        List<String> tags = List.of("wireless", "eco", "gift", "bestseller", "new", "clearance", "premium",
                "bundle", "outdoor", "kids", "office", "travel", "handmade", "imported", "limited");
        List<BenchmarkRecord> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BenchmarkRecord r = row(i);
            r.setStatus(i % 10 < 7 ? "active" : i % 10 < 9 ? "archived" : "draft");
            r.setRegion(pick(random, new String[]{"emea", "amer", "apac", "latam", "mena"}));
            r.setTier(BenchmarkRecord.Tier.values()[random.nextInt(3)]);
            r.setActive(random.nextInt(4) == 0);
            r.setCategory("category" + random.nextInt(20));
            r.setBrand("Brand" + random.nextInt(50));
            r.setOptional(random.nextInt(10) == 0 ? null : "supplier" + random.nextInt(10));
            r.setQuantity(1 + random.nextInt(5));
            r.setPrice((500 + random.nextInt(49501)) / 100.0);
            r.setSerial(3_000_000_000L + random.nextInt(5_000_001));
            r.setTags(random.nextInt(10) == 0 ? null : some(random, tags, 1 + random.nextInt(3)));
            r.setNumbers(some(random, List.of("5", "6", "7", "8", "9", "10", "11", "12", "13"), 1 + random.nextInt(4)));
            String title = pick(random, adjectives) + " " + (random.nextInt(10) < 3 ? pick(random, adjectives) + " " : "")
                    + pick(random, colors) + " " + pick(random, nouns);
            r.setName(tokens(title));
            r.setKeywords(tokens(title));
            r.setDescription(title);
            rows.add(r);
        }
        List<Scenario> q = new ArrayList<>();
        q.add(single("brand-equal", "Equality", "brand", EQUAL, "BRAND7"));
        q.add(single("region-in", "Equality", "region", EQUAL, "emea", "apac"));
        q.add(where("and-three", "Equality", "active status AND emea region AND Brand7",
                c("status", EQUAL, "active"), c("region", EQUAL, "emea"), c("brand", EQUAL, "Brand7")));
        q.add(single("status-not-equal", "Equality", "status", NOT_EQUAL, "archived"));
        q.add(where("enum-tier", "Typed scalars", "tier PRO AND status active",
                c("tier", EQUAL, "PRO"), c("status", EQUAL, "active")));
        q.add(where("boolean-sale", "Typed scalars", "on sale AND category3",
                c("active", EQUAL, "true"), c("category", EQUAL, "category3")));
        q.add(where("supplier-null", "Missing values", "supplier missing AND region emea",
                c("optional", EQUAL, NULL), c("region", EQUAL, "emea")));
        q.add(where("supplier-known", "Missing values", "supplier known AND Brand7",
                c("optional", NOT_EQUAL, NULL), c("brand", EQUAL, "Brand7")));
        q.add(single("stars-lt", "Numeric", "quantity", LESS_THAN, "2"));
        q.add(single("stars-lte", "Numeric", "quantity", LESS_THAN_EQUAL, "2"));
        q.add(single("stars-gt", "Numeric", "quantity", GREATER_THAN, "4"));
        q.add(single("stars-gte", "Numeric", "quantity", GREATER_THAN_EQUAL, "4"));
        q.add(interval("stars-between", "Numeric", "quantity", "2", "3"));
        q.add(single("long-views", "Numeric", "serial", GREATER_THAN, "3004990000"));
        q.add(difference(single("decimal-lte", "Numeric", "price", LESS_THAN_EQUAL, "19.50")));
        q.add(difference(single("decimal-gte", "Numeric", "price", GREATER_THAN_EQUAL, "495.25")));
        q.add(difference(single("decimal-equal", "Numeric", "price", EQUAL, "19.99")));
        q.add(single("tags-either", "Lists", "tags", CONTAINS_EITHER, "gift", "premium"));
        q.add(single("tags-all", "Lists", "tags", CONTAINS_ALL, "wireless", "premium"));
        q.add(where("tags-exclude", "Lists", "active status AND tags exclude clearance",
                c("status", EQUAL, "active"), c("tags", NOT_CONTAINS, "clearance")));
        q.add(where("tags-either-null", "Lists", "Brand7 AND tags missing or contain eco",
                c("brand", EQUAL, "Brand7"), c("tags", CONTAINS_EITHER_INCLUDING_NULL, "eco")));
        q.add(where("sizes-either", "Lists", "size 13 AND region emea",
                c("numbers", CONTAINS_EITHER, "13"), c("region", EQUAL, "emea")));
        q.add(where("sizes-all", "Lists", "sizes 9 AND 10 AND category3",
                c("numbers", CONTAINS_ALL, "9", "10"), c("category", EQUAL, "category3")));
        q.add(mode(single("base", "Results", "brand", EQUAL, "Brand7"), "projection", "projection", 0, true));
        q.add(mode(single("base", "Results", "brand", EQUAL, "Brand7"), "typed-results", "typed", 0, true));
        q.add(mode(single("base", "Results", "status", EQUAL, "active"), "limit-20", "ids", 20, false));
        q.add(word("keyword-discovery", "active emea", List::of,
                r -> r.getStatus().equals("active") && r.getRegion().equals("emea")));
        q.add(word("keyword-typo", "wireles lamp", List::of, r -> hasWord(r, "wireless") && hasWord(r, "lamp")));
        q.add(word("keyword-filtered", "wireles lamp", () -> List.of(c("status", EQUAL, "active"), c("region", EQUAL, "emea")),
                r -> hasWord(r, "wireless") && hasWord(r, "lamp") && r.getStatus().equals("active") && r.getRegion().equals("emea")));
        q.add(word("keyword-stem", "chargers", List::of, r -> hasWord(r, "charger")));
        q.add(word("keyword-punctuation", "wireless, lamp!", List::of, r -> hasWord(r, "wireless") && hasWord(r, "lamp")));
        q.add(dictionary("prefix-predictions", "wirel", "predict", true));
        q.add(dictionary("one-edit-suggestion", "keybord", "suggest", false));
        return new Dataset("catalog-" + size, size + " seeded synthetic product records: skewed status, regions, enum tiers, "
                + "sale flags, nullable suppliers, stars, fractional prices, long view counters, varied tags/sizes and multi-word titles. "
                + "Java Random seed " + SEED + "; not the TypeScript generator's byte-identical rows.", rows, List.copyOf(q));
    }

    private static Dataset schedule(int size) {
        Random random = new Random(SEED + 1);
        List<String> dates = new ArrayList<>();
        List<String> times = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            dates.add(day(i * 10));
            times.add(day(i * 10) + " 10:00:00");
        }
        List<BenchmarkRecord> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BenchmarkRecord r = row(i);
            r.setCategory(pick(random, new String[]{"concert", "expo", "tour", "workshop", "sport"}));
            r.setBrand("city" + random.nextInt(12));
            r.setRegion("venue" + random.nextInt(40));
            int date = random.nextInt(365);
            r.setDate(day(date));
            r.setTimestamp(day(date) + String.format(Locale.ROOT, " %02d:%02d:00", 8 + random.nextInt(15), 15 * random.nextInt(4)));
            r.setDates(some(random, dates, 1 + random.nextInt(3)));
            r.setTimestamps(some(random, times, 1 + random.nextInt(3)));
            int low = 50 * (1 + random.nextInt(10));
            r.setBand(low + "-" + (low + 50 * (1 + random.nextInt(20))));
            int age = random.nextInt(31);
            r.setRoute((i % 2 == 0 ? "AA" : "TT") + age + "-" + (age + 10 + random.nextInt(61)));
            int length = 15 * (1 + random.nextInt(8));
            r.setBands(length + "-" + (length + 15 * (1 + random.nextInt(4))) + ",165-195");
            int season = random.nextInt(301);
            r.setWindow(i % 5 == 0 ? null : day(season) + "-" + day(season + 30 + random.nextInt(91)));
            int slot = 30 * random.nextInt(12);
            r.setTimeWindow(day(slot) + " 08:00:00-" + day(slot) + " 18:00:00");
            int closure = random.nextInt(360);
            r.setWindows(day(closure) + "-" + day(closure + random.nextInt(7)));
            r.setTimeWindows(day(slot) + " 09:00:00-" + day(slot) + " 12:00:00,"
                    + day(slot) + " 13:00:00-" + day(slot) + " 17:00:00");
            rows.add(r);
        }
        List<Scenario> q = new ArrayList<>();
        q.add(single("date-equal", "Dates and times", "date", EQUAL, "2026-03-15"));
        q.add(where("date-between", "Dates and times", "city3 AND dates in March",
                c("brand", EQUAL, "city3"), between("date", "2026-03-01", "2026-03-31")));
        q.add(single("date-gt", "Dates and times", "date", GREATER_THAN, "2026-12-29"));
        q.add(where("timestamp-window", "Dates and times", "start time June 1, 08:00 inclusive to 12:00 exclusive",
                c("timestamp", GREATER_THAN_EQUAL, "2026-06-01 08:00:00"), c("timestamp", LESS_THAN, "2026-06-01 12:00:00")));
        q.add(where("dates-either", "Date and time lists", "open date pool[10] AND concert",
                c("dates", CONTAINS_EITHER, dates.get(10)), c("category", EQUAL, "concert")));
        q.add(single("dates-all", "Date and time lists", "dates", CONTAINS_ALL, dates.get(10), dates.get(20)));
        q.add(where("timestamps-either", "Date and time lists", "session pool[5] AND city3",
                c("timestamps", CONTAINS_EITHER, times.get(5)), c("brand", EQUAL, "city3")));
        q.add(single("timestamps-all", "Date and time lists", "timestamps", CONTAINS_ALL, times.get(5), times.get(10)));
        q.add(where("range-point", "Stored ranges", "capacity includes 250 AND expo",
                c("band", CONTAINS_EITHER, "250"), c("category", EQUAL, "expo")));
        q.add(single("range-all", "Stored ranges", "band", CONTAINS_ALL, "100", "900"));
        q.add(single("range-equal", "Stored ranges", "band", EQUAL, "100-200"));
        q.add(where("prefix-range", "Stored ranges", "age band includes AA21 AND city3",
                c("route", CONTAINS_EITHER, "AA21"), c("brand", EQUAL, "city3")));
        q.add(where("range-list-point", "Stored ranges", "duration includes 125 AND city3",
                c("bands", CONTAINS_EITHER, "125"), c("brand", EQUAL, "city3")));
        q.add(single("range-list-all", "Stored ranges", "bands", CONTAINS_ALL, "20", "170"));
        q.add(single("range-not", "Stored ranges", "bands", NOT_CONTAINS, "125"));
        q.add(where("date-range", "Stored ranges", "season includes July 15 AND tour",
                c("window", CONTAINS_EITHER, "2026-07-15"), c("category", EQUAL, "tour")));
        q.add(single("date-range-null", "Missing values", "window", EQUAL, NULL));
        q.add(single("datetime-range", "Stored ranges", "timeWindow", CONTAINS_EITHER, day(90) + " 09:30:00"));
        q.add(single("date-range-list", "Stored ranges", "windows", CONTAINS_EITHER, "2026-12-25"));
        q.add(single("datetime-range-list-all", "Stored ranges", "timeWindows", CONTAINS_ALL,
                day(90) + " 10:00:00", day(90) + " 14:00:00"));
        q.add(where("combined-four-fields", "Combined", "concert AND city3 AND season July 15 AND capacity 250",
                c("category", EQUAL, "concert"), c("brand", EQUAL, "city3"),
                c("window", CONTAINS_EITHER, "2026-07-15"), c("band", CONTAINS_EITHER, "250")));
        q.add(mode(single("base", "Results", "category", EQUAL, "concert"), "limit-20", "ids", 20, false));
        q.add(mode(single("base", "Results", "region", EQUAL, "venue3"), "typed-results", "typed", 0, true));
        return new Dataset("schedule-" + size, size + " seeded synthetic events: five event types, 12 cities, 40 venues, "
                + "varied timestamps and date lists, capacities, age-band prefixes, duration lists, 20% missing seasons, "
                + "closures and split booking intervals. Java Random seed " + (SEED + 1) + ".", rows, List.copyOf(q));
    }

    private static Dataset retail() throws Exception {
        byte[] bytes;
        try (InputStream input = Datasets.class.getResourceAsStream("/retail-products.csv")) {
            if (input == null) throw new IllegalStateException("Missing bundled retail CSV");
            bytes = input.readAllBytes();
        }
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(RETAIL_SHA256)) {
            throw new IllegalStateException("Retail CSV checksum does not match the attributed projection");
        }
        List<BenchmarkRecord> rows = new ArrayList<>();
        try (CSVParser csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            for (var item : csv) {
                BenchmarkRecord r = new BenchmarkRecord();
                r.setId(item.get("sku"));
                r.setPrice(Double.valueOf(item.get("unitPrice")));
                r.setQuantity(Integer.valueOf(item.get("unitsSold")));
                r.setSerial(Long.valueOf(item.get("salesRows")));
                r.setTags(item.get("markets"));
                r.setName(tokens(item.get("title")));
                r.setKeywords(tokens(item.get("title")));
                r.setDescription(item.get("title"));
                rows.add(r);
            }
        }
        if (rows.size() != 3788) throw new IllegalStateException("Expected 3788 retail products");
        List<Scenario> q = new ArrayList<>();
        q.add(single("markets-either", "Lists", "tags", CONTAINS_EITHER, "Japan", "Australia"));
        q.add(single("markets-all", "Lists", "tags", CONTAINS_ALL, "Japan", "France"));
        q.add(single("markets-not", "Lists", "tags", NOT_CONTAINS, "United Kingdom"));
        q.add(difference(single("decimal-lt", "Numeric", "price", LESS_THAN, "0.20")));
        q.add(single("units-gte", "Numeric", "quantity", GREATER_THAN_EQUAL, "20000"));
        q.add(difference(where("decimal-between-japan", "Numeric", "Japan AND historical unitPrice between GBP 1 and 2",
                c("tags", CONTAINS_EITHER, "Japan"), between("price", "1", "2"))));
        q.add(word("keyword-typo", "candle holdr", List::of, r -> hasWord(r, "candle") && hasWord(r, "holder")));
        q.add(difference(word("keyword-stem", "lanterns", List::of, r -> hasWord(r, "lantern") || hasWord(r, "lanterns"))));
        q.add(dictionary("prefix-predictions", "lant", "predict", true));
        q.add(dictionary("one-edit-suggestion", "lanern", "suggest", false));
        q.add(mode(single("base", "Results", "tags", CONTAINS_EITHER, "Japan"), "projection", "projection", 0, true));
        return new Dataset("retail-3788", "3,788 genuine stock codes from the attributed UCI Online Retail projection used "
                + "by the TypeScript report (CC BY 4.0); historical GBP prices, units sold and observed markets. SHA-256 " + RETAIL_SHA256,
                rows, List.copyOf(q));
    }

    private static Scenario where(String name, String group, String detail, Condition... conditions) {
        return exact(name, group, detail, () -> Arrays.asList(conditions),
                r -> Arrays.stream(conditions).allMatch(c -> matches(r, c)));
    }

    private static Scenario difference(Scenario s) {
        return mode(s, s.name(), s.mode(), s.limit(), false);
    }

    private static Scenario mode(Scenario s, String name, String mode, int limit, boolean comparable) {
        return new Scenario(name, s.group(), s.detail(), s.conditions(), s.expected(), mode, s.text(), limit, comparable);
    }

    private static Scenario word(String name, String text, Supplier<List<Condition>> conditions,
                                 java.util.function.Predicate<BenchmarkRecord> expected) {
        return new Scenario(name, "Text", text, conditions, expected, "keyword", text, 0, true);
    }

    private static Scenario dictionary(String name, String text, String mode, boolean comparable) {
        return new Scenario(name, "Dictionary", text, List::of, r -> true, mode, text, 0, comparable);
    }

    private static boolean hasWord(BenchmarkRecord r, String word) {
        return Arrays.asList(r.getName().split(",")).contains(word);
    }

    static String tokens(String title) {
        return String.join(",", new LinkedHashSet<>(Arrays.asList(title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))));
    }

    private static String day(int offset) {
        return LocalDate.of(2026, 1, 1).plusDays(offset).toString();
    }

    private static String pick(Random random, String[] choices) {
        return choices[random.nextInt(choices.length)];
    }

    private static String some(Random random, List<String> choices, int count) {
        Set<String> selected = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) selected.add(choices.get(random.nextInt(choices.size())));
        return String.join(",", selected);
    }
}
