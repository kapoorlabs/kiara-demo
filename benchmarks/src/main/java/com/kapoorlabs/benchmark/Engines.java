package com.kapoorlabs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kapoorlabs.kiara.domain.*;
import com.kapoorlabs.kiara.loader.StoreLoader;
import com.kapoorlabs.kiara.search.KeywordSearch;
import com.kapoorlabs.kiara.search.StoreSearch;
import com.kapoorlabs.kiara.util.SpellCheckUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.tartarus.snowball.ext.englishStemmer;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.kapoorlabs.kiara.domain.Operator.*;
import static org.apache.lucene.search.BooleanClause.Occur.*;

final class Engines {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ID = Set.of("ID");
    static final Set<String> PROJECTION = Set.of("ID", "CATEGORY", "PRICE");
    static final List<String> FIELDS = List.of("id", "category", "status", "region", "tier", "brand", "quantity", "price", "serial", "active",
            "optional", "tags", "numbers", "date", "timestamp", "dates", "timestamps", "band", "route", "bands",
            "window", "timeWindow", "windows", "timeWindows", "name", "keywords");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    record Result(List<String> ids, Object payload) {}

    interface Engine extends AutoCloseable {
        Result run(Workloads.Scenario scenario) throws Exception;
        @Override
        void close() throws Exception;
    }

    static Engine build(String engine, List<BenchmarkRecord> rows) throws Exception {
        return switch (engine) {
            case "kiara" -> new Kiara(rows);
            case "lucene" -> new Lucene(rows);
            default -> throw new IllegalArgumentException("Unknown engine " + engine);
        };
    }

    static final class Kiara implements Engine {
        final Store<BenchmarkRecord> store = new Store<>(BenchmarkRecord.class);
        final StoreSearch search = new StoreSearch();
        final KeywordSearch keywords = new KeywordSearch(10);

        Kiara(List<BenchmarkRecord> rows) throws Exception {
            StoreLoader<BenchmarkRecord> loader = new StoreLoader<>(store);
            for (BenchmarkRecord row : rows) loader.loadTable(row);
            loader.prepareForSearch();
        }

        @Override
        public Result run(Workloads.Scenario scenario) {
            Integer limit = scenario.limit() == 0 ? null : scenario.limit();
            if (scenario.mode().equals("predict")) {
                List<String> values = SpellCheckUtil.getTextPredictions(scenario.text(), store.getSpellCheckTrie(), 10);
                return new Result(values, values);
            }
            if (scenario.mode().equals("suggest")) {
                String value = SpellCheckUtil.getOneEditKeyword(scenario.text(), store.getSpellCheckTrie());
                List<String> values = value == null ? List.of() : List.of(value);
                return new Result(values, values);
            }
            if (scenario.mode().equals("keyword")) {
                KeywordSearchResult<BenchmarkRecord> result = keywords.getBestMatch(scenario.text(), store,
                        conditions(scenario), limit);
                return new Result(result.getResult().stream().map(BenchmarkRecord::getId).toList(), result);
            }
            List<Condition> conditions = conditions(scenario);
            if (scenario.mode().equals("typed")) {
                List<BenchmarkRecord> result = search.query(store, conditions, limit);
                return new Result(result.stream().map(BenchmarkRecord::getId).toList(), result);
            }
            Set<String> projection = scenario.mode().equals("projection") ? PROJECTION : ID;
            List<Map<String, String>> result = search.query(store, conditions, new HashSet<>(projection), limit);
            return new Result(result.stream().map(r -> r.get("ID")).toList(), result);
        }

        @Override
        public void close() {}

        List<Condition> conditions(Workloads.Scenario scenario) {
            return scenario.conditions().get().stream().map(c -> c.getOperator() == BETWEEN
                    ? new Condition(c.getColumnName(), BETWEEN, c.getLowerValue(), c.getUpperValue())
                    : new Condition(c.getColumnName(), c.getOperator(), c.getValue()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }

    static final class Lucene implements Engine {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final DirectoryReader reader;
        final IndexSearcher searcher;
        final int size;

        Lucene(List<BenchmarkRecord> rows) throws Exception {
            size = rows.size();
            // Every field is explicitly typed; no text analyzer is used for exact/list values.
            try (Analyzer analyzer = new Analyzer() {
                @Override
                protected TokenStreamComponents createComponents(String field) {
                    Tokenizer unused = new Tokenizer() {
                        @Override
                        public boolean incrementToken() {
                            throw new UnsupportedOperationException("Only untokenized fields are indexed");
                        }
                    };
                    return new TokenStreamComponents(unused);
                }
            }; IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (BenchmarkRecord row : rows) writer.addDocument(document(row));
                writer.commit();
            }
            reader = DirectoryReader.open(directory);
            searcher = new IndexSearcher(reader);
            searcher.setQueryCache(null);
        }

        private Document document(BenchmarkRecord row) throws IOException {
            Document doc = new Document();
            doc.add(new StoredField("_payload", JSON.writeValueAsString(row)));
            doc.add(new StoredField("ID", row.getId()));
            if (row.getCategory() != null) doc.add(new StoredField("CATEGORY", row.getCategory()));
            if (row.getPrice() != null) doc.add(new StoredField("PRICE", row.getPrice().toString()));
            for (String field : FIELDS) {
                for (String value : Workloads.values(row, field)) {
                    doc.add(new StringField(field, normalize(field, value), Field.Store.NO));
                    doc.add(new StringField("_discovery", normalize(field, value), Field.Store.NO));
                    if (value.equals(Workloads.NULL)) continue;
                    if (Workloads.RANGE_FIELDS.contains(field)) {
                        String[] bounds = Workloads.rangeBounds(field, value);
                        String rangeField = rangeField(field, value);
                        doc.add(new LongRange(rangeField, new long[]{rangeValue(field, bounds[0])},
                                new long[]{rangeValue(field, bounds[1])}));
                    } else if (field.equals("price")) {
                        doc.add(new DoublePoint("_point." + field, Double.parseDouble(value)));
                    } else if (Workloads.NUMBER_FIELDS.contains(field) || Workloads.DATE_FIELDS.contains(field)) {
                        doc.add(new LongPoint("_point." + field, scalar(field, value)));
                    }
                }
            }
            return doc;
        }

        @Override
        public Result run(Workloads.Scenario scenario) throws Exception {
            if (scenario.mode().equals("predict")) {
                List<String> result = predictions(scenario.text());
                return new Result(result, result);
            }
            if (scenario.mode().equals("suggest")) {
                List<String> result = suggestions(scenario.text());
                return new Result(result, result);
            }
            Query query = and(scenario.conditions().get().stream().map(Lucene::condition).toList());
            if (scenario.mode().equals("keyword")) {
                List<Query> terms = Arrays.stream(scenario.text().replaceAll("[!,.?;]", " ").trim().split("\\s+"))
                        .map(this::keyword).toList();
                Query all = and(List.of(query, and(terms)));
                if (!scenario.comparable() && searcher.count(all) == 0) {
                    query = and(List.of(query, or(terms)));
                } else {
                    query = all;
                }
            }
            int limit = scenario.limit() == 0 ? size : scenario.limit();
            // Constant-score, document-order retrieval: no BM25/scoring or relevance ranking in the exact comparison.
            TopDocs hits = searcher.search(new ConstantScoreQuery(query), limit, Sort.INDEXORDER);
            StoredFields stored = reader.storedFields();
            List<String> ids = new ArrayList<>(hits.scoreDocs.length);
            if (scenario.mode().equals("typed") || scenario.mode().equals("keyword")) {
                List<BenchmarkRecord> result = new ArrayList<>(hits.scoreDocs.length);
                for (ScoreDoc hit : hits.scoreDocs) {
                    BenchmarkRecord row = JSON.readValue(stored.document(hit.doc, Set.of("_payload")).get("_payload"), BenchmarkRecord.class);
                    ids.add(row.getId());
                    result.add(row);
                }
                return new Result(ids, result);
            }
            Set<String> fields = scenario.mode().equals("projection") ? PROJECTION : ID;
            List<Map<String, String>> result = new ArrayList<>(hits.scoreDocs.length);
            for (ScoreDoc hit : hits.scoreDocs) {
                Document doc = stored.document(hit.doc, fields);
                Map<String, String> row = new HashMap<>();
                for (String field : fields) row.put(field, doc.get(field));
                ids.add(row.get("ID"));
                result.add(row);
            }
            return new Result(ids, result);
        }

        private Query keyword(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            Query exact = or(List.of(new TermQuery(new Term("_discovery", lower)),
                    new TermQuery(new Term("keywords", stem(lower)))));
            try {
                if (searcher.count(exact) > 0) return exact;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to resolve keyword " + value, e);
            }
            return new FuzzyQuery(new Term("name", lower), 1, 0, 50, false);
        }

        List<String> predictions(String prefix) throws IOException {
            Terms terms = MultiTerms.getTerms(reader, "name");
            TermsEnum iterator = terms.iterator();
            List<String> result = new ArrayList<>();
            if (iterator.seekCeil(new BytesRef(prefix)) == TermsEnum.SeekStatus.END) return result;
            do {
                String term = iterator.term().utf8ToString();
                if (!term.startsWith(prefix)) break;
                result.add(term);
            } while (result.size() < 10 && iterator.next() != null);
            return result;
        }

        List<String> suggestions(String text) throws IOException {
            Query query = searcher.rewrite(new FuzzyQuery(new Term("name", text), 1, 0, 50, false));
            Set<Term> terms = new TreeSet<>();
            query.visit(QueryVisitor.termCollector(terms));
            return terms.stream().map(Term::text).limit(10).toList();
        }

        static Query condition(Condition c) {
            String field = c.getColumnName();
            Operator op = c.getOperator();
            if (op == BETWEEN) return ordered(field, c.getLowerValue(), true, c.getUpperValue(), true);
            List<String> wanted = c.getValue();
            if (Set.of(LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL).contains(op)) {
                String value = wanted.getFirst();
                return switch (op) {
                    case LESS_THAN -> ordered(field, null, true, value, false);
                    case LESS_THAN_EQUAL -> ordered(field, null, true, value, true);
                    case GREATER_THAN -> ordered(field, value, false, null, true);
                    case GREATER_THAN_EQUAL -> ordered(field, value, true, null, true);
                    default -> throw new IllegalArgumentException("Not ordered: " + op);
                };
            }
            List<Query> queries = new ArrayList<>();
            for (String value : wanted) {
                if (Workloads.RANGE_FIELDS.contains(field) && op != EQUAL && op != NOT_EQUAL && !value.equals(Workloads.NULL)) {
                    String point = field.equals("route") ? value.substring(2) : value;
                    long number = rangeValue(field, point);
                    queries.add(LongRange.newContainsQuery(rangeField(field, value), new long[]{number}, new long[]{number}));
                } else {
                    queries.add(new TermQuery(new Term(field, normalize(field, value))));
                }
            }
            if (op == CONTAINS_EITHER_INCLUDING_NULL) queries.add(new TermQuery(new Term(field, Workloads.NULL)));
            Query positive = op == CONTAINS_ALL ? and(queries) : or(queries);
            return op == NOT_EQUAL || op == NOT_CONTAINS
                    ? new BooleanQuery.Builder().add(new MatchAllDocsQuery(), FILTER).add(positive, MUST_NOT).build()
                    : positive;
        }

        private static Query ordered(String field, String lower, boolean includeLower, String upper, boolean includeUpper) {
            if (field.equals("price")) {
                double lo = lower == null ? Double.NEGATIVE_INFINITY : Double.parseDouble(lower);
                double hi = upper == null ? Double.POSITIVE_INFINITY : Double.parseDouble(upper);
                return DoublePoint.newRangeQuery("_point." + field, includeLower ? lo : Math.nextUp(lo), includeUpper ? hi : Math.nextDown(hi));
            }
            long lo = lower == null ? Long.MIN_VALUE : scalar(field, lower);
            long hi = upper == null ? Long.MAX_VALUE : scalar(field, upper);
            return LongPoint.newRangeQuery("_point." + field, includeLower ? lo : Math.addExact(lo, 1),
                    includeUpper ? hi : Math.subtractExact(hi, 1));
        }

        private static String normalize(String field, String value) {
            if (field.equals("brand") || field.equals("name")) return value.toLowerCase(Locale.ROOT);
            return field.equals("keywords") && !value.equals(Workloads.NULL) ? stem(value.toLowerCase(Locale.ROOT)) : value;
        }

        private static String stem(String value) {
            englishStemmer stemmer = new englishStemmer();
            stemmer.setCurrent(value);
            stemmer.stem();
            return stemmer.getCurrent();
        }

        private static long scalar(String field, String value) {
            if (Workloads.DATE_FIELDS.contains(field)) {
                return value.length() == 10 ? LocalDate.parse(value).toEpochDay()
                        : LocalDateTime.parse(value, TIMESTAMP).toEpochSecond(ZoneOffset.UTC);
            }
            return Long.parseLong(value);
        }

        private static String rangeField(String field, String value) {
            return "_range." + field + (field.equals("route") ? "." + value.substring(0, 2) : "");
        }

        private static long rangeValue(String field, String value) {
            if (Set.of("band", "bands", "route").contains(field)) return Long.parseLong(value);
            return value.length() == 10 ? LocalDate.parse(value).toEpochDay()
                    : LocalDateTime.parse(value, TIMESTAMP).toEpochSecond(ZoneOffset.UTC);
        }

        private static Query and(List<Query> queries) {
            if (queries.isEmpty()) return new MatchAllDocsQuery();
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            queries.forEach(q -> builder.add(q, FILTER));
            return builder.build();
        }

        private static Query or(List<Query> queries) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
            queries.forEach(q -> builder.add(q, SHOULD));
            return builder.build();
        }

        @Override
        public void close() throws IOException {
            reader.close();
            directory.close();
        }
    }
}
