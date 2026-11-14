# **Kiara DB and Lucene: Exploring the Tradeoff Between Query Speed and Index Preparation**

Kiara DB's prepared, in-memory, read-oriented design makes a deliberate tradeoff: optimize repeated searches over a prepared dataset, while accepting the cost of loading and indexing that data before it can be queried.

That tradeoff is visible in a Java benchmark comparing **Kiara 2.0.16** with **Apache Lucene 10.5.2**. Kiara recorded lower aggregate query latency across all five tested dataset entries, reaching **16.07x lower aggregate latency for equivalent structured-query scenarios on the 10,000-product catalog**. Lucene built the larger indexes substantially faster and used less additional retained Java heap.

The results support evaluating Kiara for workloads where a dataset is prepared and then searched repeatedly. They do not establish that Kiara is faster for every query, or that Lucene's broader search capabilities are interchangeable with Kiara's API.

## What does "up to 16x" mean?

The headline refers to a dataset-level aggregate, not a universal speedup.

For each eligible scenario, the benchmark takes the median p50 latency across three independent JVM runs. It then calculates the geometric mean of the Lucene-to-Kiara latency ratios across those scenarios.

The highest such aggregate was **16.07x**, across 22 equivalent structured-query scenarios in the 10,000-product catalog. Individual queries have different ratios, and a production workload may weight those queries very differently.

This distinction matters: "up to 16x lower aggregate query latency in these tests" is supported by the measurements. "Kiara is 16x faster than Lucene" without qualification is not.

## Different designs, overlapping features

Kiara Java is a prepared, in-memory, read-oriented data store. Annotated Java objects define field behavior, and search conditions express structured queries. Its features include scalar filtering, list membership, dates, stored intervals, and cross-field keyword discovery.

Lucene is a general-purpose indexing and search library. It supports structured filtering alongside relevance scoring, analyzers, phrase and proximity queries, persistent indexes, and index updates and deletes.

The benchmark compares overlapping operations through adapters that aim to return equivalent results.

| Area | Kiara Java | Lucene configuration used in the benchmark |
| --- | --- | --- |
| Equality and combined filters | Conditions over annotated fields | Exact terms and Boolean queries |
| Numeric comparisons | Native numeric indexing and conditions | Numeric points and exact terms |
| List membership | Annotated comma-separated values | Repeated indexed fields |
| Dates and timestamps | Annotation-driven parsing and indexing | Epoch-day and UTC epoch-second numeric points |
| Stored intervals | Numeric, date, and date-time range annotations | Native `LongRange` fields |
| Keyword retrieval | Cross-field discovery and best-match behavior | Explicit term and fuzzy-query adapter |
| Predictions and spelling | Prediction and single-correction APIs | Term enumeration and fuzzy matching |
| Results | Projected fields or reconstructed Java objects | Stored fields or JSON decoded into matching Java objects |

Lucene is not treated as a string-only search tool: its native numeric and interval indexes are used. Conversely, Kiara's best-match keyword behavior is not presented as equivalent to Lucene's relevance-ranking model.

## Five datasets and three workload families

The experiment uses five dataset entries:

| Dataset | Records | Representative tests |
| --- | ---: | --- |
| Retail | 3,788 | Country lists, fractional prices, sales volume, keywords, predictions, and projection |
| Catalog | 10,000 | Equality, numeric comparisons, lists, missing values, combined filters, and keywords |
| Catalog | 100,000 | The catalog query family at a larger scale |
| Schedule | 10,000 | Dates, timestamps, numeric and prefixed ranges, interval lists, and combined conditions |
| Schedule | 100,000 | The schedule query family at a larger scale |

The retail fixture is the same real retail projection used in the reference TypeScript benchmark. Catalog and schedule data are generated from fixed seeds. Both Java engines receive identical input objects.

The suite exercises all **11 Kiara query operators** and all **17 indexing annotations** through timed scenarios and supporting tests. Some APIs, such as minimum keyword matching, are tested separately rather than assigned an artificial head-to-head latency comparison.

## Methodology

The run used JDK 21 on Windows 11 with an AMD EPYC 7763 processor. Each engine ran in a separate JVM for each dataset and repetition. Three repetitions per engine produced **30 worker runs**, executed sequentially with alternating engine order.

Important controls included:

- **Correctness before timing:** Results were checked against an independent scan-based oracle.
- **Comparable output:** Most structured queries materialized every matching ID, rather than comparing a count with a complete result set.
- **Separate build and query measurements:** Loading and preparing an index were measured independently from searching it.
- **Explicit semantic differences:** Recognized result mismatches remained visible but were excluded from equivalent-query speed aggregates.
- **Retained evidence:** Raw samples, input fingerprints, dependency versions, and the executable hash were recorded.

Each scenario received warmup followed by bounded sampling, with between five and 61 latency samples per worker. The completed run recorded **738 scenario/fork measurements** and **37,110 raw latency samples**.

Both engines used in-memory storage. Lucene used `ByteBuffersDirectory`, and its query-result cache was disabled. Query construction, result materialization, and ID extraction were included in query timings.

This is a standalone harness, not JMH. The reported medians and percentiles are descriptive measurements, not confidence intervals.

## Query latency: Kiara's read-oriented tradeoff in practice

Kiara recorded lower aggregate latency across the equivalent structured-query scenarios in every dataset entry.

| Dataset | Equivalent structured scenarios | Aggregate Lucene/Kiara latency ratio |
| --- | ---: | ---: |
| Retail: 3,788 products | 5 | **7.98x** |
| Catalog: 10,000 products | 22 | **16.07x** |
| Catalog: 100,000 products | 22 | **9.43x** |
| Schedule: 10,000 events | 22 | **5.42x** |
| Schedule: 100,000 events | 22 | **3.46x** |

A ratio above 1 means Kiara had lower latency. These aggregates give each included scenario equal weight; they do not mean Kiara won every individual query.

For keyword scenarios that returned equivalent results in every repetition, the aggregate ratios were **2.15x** for retail, **6.05x** for the 10,000-product catalog, and **4.63x** for the 100,000-product catalog. Schedule datasets contained no keyword workloads.

These results are consistent with Kiara's read-oriented design. They do not, by themselves, prove that slower index construction causes faster queries, or that the same balance will hold for other schemas and distributions.

## Index preparation and memory: the other side of the tradeoff

The larger datasets show why query latency should not be evaluated alone.

| Dataset | Kiara build time | Lucene build time | Kiara additional retained heap | Lucene additional retained heap |
| --- | ---: | ---: | ---: | ---: |
| Retail: 3,788 | 0.486 s | 0.670 s | 14.1 MiB | 0.7 MiB |
| Catalog: 10,000 | 1.151 s | 1.123 s | 33.5 MiB | 2.1 MiB |
| Catalog: 100,000 | 182.969 s | 4.764 s | 327.4 MiB | 20.0 MiB |
| Schedule: 10,000 | 1.302 s | 1.462 s | 33.4 MiB | 2.9 MiB |
| Schedule: 100,000 | 157.595 s | 6.002 s | 294.4 MiB | 27.0 MiB |

Small-dataset build times were relatively close. At 100,000 records, Lucene built the catalog index in about **4.8 seconds**, compared with **183 seconds** for Kiara. The schedule index took about **6 seconds** in Lucene and **158 seconds** in Kiara.

Lucene also used less additional retained Java heap across all five dataset entries.

The memory figures estimate post-build Java heap growth after requested garbage collections. They are **not peak memory, total process memory, allocation volume, or a full accounting of native memory**. Schema and storage choices also matter: the adapters use different representations for reconstructing objects and retrieving fields.

For a dataset searched repeatedly between infrequent reloads, a longer preparation phase may be acceptable. For frequent rebuilds or constrained memory budgets, Lucene's measured advantages may be more important.

Crucially, this experiment measures **bulk loading and index preparation**, not transactional writes or incremental update throughput. It would be inaccurate to describe these results as a measured comparison of write performance.

## Correctness remains part of the comparison

Performance ratios only have meaning when the operations return equivalent results.

### Fractional numeric comparisons

The tested Kiara version truncates fractional numeric index and query values into integer buckets. Lucene's configured representation preserves the tested fractional distinctions.

Some price filters therefore return different matches. The report retains the missing and additional records, but excludes these scenarios from aggregate speed ratios. Applications that depend on fractional thresholds should validate this behavior directly.

### Keywords and stemming

Kiara's keyword selection and the Lucene cross-field adapter do not always return the same records.

In the retail stemming scenario, Kiara returned 18 records while Lucene returned 20; the independent oracle expected both singular and plural forms. Other catalog keyword scenarios also differed from the intended-token oracle.

Those cases remain visible in the report. Only keyword scenarios with equivalent results across every repetition contribute to the keyword latency aggregates.

### Limits and spelling suggestions

Limited queries can return different valid subsets when there is no shared ordering contract. Likewise, selecting one spelling correction is not identical to enumerating multiple candidates. Such operations are reported without treating them as automatically interchangeable performance tests.

## What changed in Kiara 2.0.16?

An earlier run with Kiara 2.0.15 failed while preparing the larger schedule indexes. A range comparator used boxed-value identity when comparing lower bounds, violating the sorting contract for some repeated values.

Kiara 2.0.16 fixes that comparison.

In this rerun, **all 30 workers completed successfully**, including all six Kiara schedule builds. No queries were blocked by index preparation. This validates the fix against these fixtures; it does not imply that unrelated numeric and text-result differences were resolved.

## Choosing based on the workload

The benchmark supports a practical framing:

> **Kiara's prepared, read-oriented design trades a potentially expensive loading and indexing phase for low query latency. In this benchmark, it delivered up to 16.07x lower aggregate latency across equivalent structured-query scenarios. Lucene built the larger indexes faster and used less additional retained Java heap.**

Kiara is worth evaluating when a dataset can be prepared ahead of time, queried repeatedly, and reloaded infrequently. Its annotation-driven model may also suit applications expressing structured conditions directly over Java objects.

Lucene is worth evaluating when indexing throughput, memory efficiency, persistent indexes, relevance ranking, or index mutation are central requirements.

Neither conclusion replaces application-specific testing. This experiment does not measure concurrent throughput, disk-backed performance, incremental writes, ranking quality, or cold-start behavior. Some retrieval timings also include adapter costs, such as object reconstruction and JSON decoding.

The useful question is not simply which library is faster. It is **which combination of query latency, preparation cost, memory use, result semantics, and operational capabilities fits the application**.

## Supporting material

- [Detailed benchmark report](./lucene-comparison.md)
- [Raw per-fork measurements and latency samples](../benchmarks/lucene-comparison.json)
- [Benchmark build configuration](../benchmarks/pom.xml)
- [Dataset generators and query scenarios](../benchmarks/src/main/java/com/kapoorlabs/benchmark/Datasets.java)
- [Retail attribution and data mapping](../benchmarks/src/main/resources/README-retail.md)
