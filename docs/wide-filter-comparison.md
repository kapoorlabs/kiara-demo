# **Wide-filter comparison: Kiara, Lucene, VoltDB CE and SQLite**

This separate experiment varies the number of **distinct filtered attributes**, not merely
predicate count or the number of requested values on one field. Historical reports are unchanged.
Results apply to these synthetic distributions, adapters and physical designs, not all databases.

[Raw trials, conditions, oracle counts, plans and index inventories](../benchmarks/wide-filter-comparison.json) |
[Methodology and reproduction](../benchmarks/wide/README.md)

## Workload

- Widths: **2, 4, 8, 12 and 16 distinct fields**, combined with AND.
- Scalar-only controls: 2, 4 and 8 fields. The existing schema has only nine applicable scalar
  fields without using identifiers, decimals or low-cardinality enum/boolean controls; no
  artificial new columns are added to manufacture a 16-field scalar-only case.
- Mixed EITHER and mixed ALL families: all five widths. Each collection predicate requests
  **two distinct values**. Wider cases mix strings, numbers, dates/timestamps and stored intervals.
- Expected final selectivities: **0.1%, 1% and 10%** for every width/family. Actual counts below
  come from the independent scan oracle; they are not forced to equal their expectations.
- Other scalar predicates accept 95% of random rows; collection EITHER accepts 95%, ALL 90%.
  An independently drawn quantity selector compensates for expected conjunction selectivity.
  The same rows serve every query and all engines. One all-matching anchor prevents empty
  results; it adds a 1/N bias, material in tiny smoke fixtures. Query width thus adds real,
  mostly broad predicates instead of simply intersecting many rare values to obtain zero rows.
- This deliberately controlled, low-cardinality, mostly independent fixture is a stress test,
  not a claim about real attribute correlations. It does not isolate operator cost from data
  type, number of collection joins, predicate order or plan changes. Compare widths within
  the same family and selectivity; do not treat width alone as a causal performance model.

## Timing, tuning and correctness

Both engines are rerun for each comparison. Fresh JVMs, alternating engine order and seeded
scenario shuffles are used. Standard measurements use five trials, at least ten warmup calls
and one second, and 15-101 samples with a three-second acquisition budget (the minimum can
exceed that budget). Smoke runs have one trial and are not publication measurements.

Kiara, SQLite and Lucene include execution and eager ID-only Java maps. Query/condition
preparation, builds, scan validation and subsequent ID extraction are excluded. Lucene uses
constant-score document-order retrieval, stored IDs and no query cache; search-time query
rewriting remains included. This new Lucene boundary is not the historical end-to-end harness.
SQLite includes JDBC/JNI, all cursor steps and closure. VoltDB uses server-side procedure
execution and VoltTable creation, excluding transport and client decoding; that is a different
boundary and representation. **Do not rank all four opponents against each other using these
ratios**. Kiara results are independently measured within each comparison.

Tuned SQLite uses ANALYZE, the existing tuned indexes plus one quantity-first covering index
over the wide scalar fields, and frozen SQL candidate selection. Generic SQLite retains
26 indexes, original SQL and no ANALYZE or SQL candidate selection. Tuned VoltDB adds the
same wide covering index and tests indexed set intersections plus explicit record-first/
value-first join orders. Joins are tried through four EITHER collection fields and two
ALL collection fields; above those bounds only intersections are attempted. Wider joins
exceeded a two-minute catalog-compilation timeout in smoke/probe testing before execution
timing; five-table EITHER joins compiled and validated and remain candidates. The wide
client now allows ten minutes per request for larger batched catalog setup, without
changing the server's SQL execution-timeout setting. The search bounds remain a disclosed
practical tuning restriction, not proof that excluded plans can never compile. This is not a
measured query loss. Validated candidate medians choose the frozen plan before fresh
measurements. Structurally identical SQL/join-order/type templates are compiled once and
reused with different bound values, rather than recompiling for each selectivity target.
Neither tuning search is exhaustive or held out.
Index/load/build/write costs are not measured. Lucene and Kiara use their existing indexes.

All matching IDs are validated against the scan oracle before and after timing. Partial or
incorrect results are not wins. Generic SQLite retains the approved 60-second per-invocation
wall deadline for both engines and durable fresh-worker recovery. Tuned SQLite/Lucene have
no such measurement deadline; VoltDB retains its native configured query timeout and fails
measurement on timeout. Therefore deadline policy differs across comparisons; compare
within a pair, not across pairs. Timeouts never become fabricated latency samples.

Ratios below are opponent latency / Kiara latency. Per-query latency is the median of trial
p50s; summary ratios are equal-query geometric means over the three selectivity targets that
pass in every trial of both engines. Exploratory intervals use 5,000 whole-trial bootstrap
resamples. Deadline-completion wins require validated completion in every trial versus
timeouts in every opposing trial; they are separate from measured-p50 wins and ratios.

Generated: 2026-10-30T19:27:14.125978900Z; trials per engine/dataset: 5.

Pinned dependency versions: `{"kiara":"2.0.16","lucene":"10.5.2","sqliteJdbc":"3.53.4.0","voltdbCE":"10.1.1"}`.

## Kiara vs voltdb

| Dataset | Family | Attributes | Fully measured queries | Opponent / Kiara | Exploratory 95% interval | Measured p50 wins K / opponent | Deadline wins K / opponent |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: |
| wide-10000 | scalar | 2 | 3/3 | 22.728x | 20.612-30.971x | 3 / 0 | 0 / 0 |
| wide-10000 | scalar | 4 | 3/3 | 0.471x | 0.420-0.507x | 0 / 3 | 0 / 0 |
| wide-10000 | scalar | 8 | 3/3 | 0.173x | 0.163-0.185x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 2 | 3/3 | 8.040x | 7.504-8.788x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 4 | 3/3 | 22.581x | 21.166-25.423x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 8 | 3/3 | 2.101x | 1.708-2.263x | 2 / 1 | 0 / 0 |
| wide-10000 | either | 12 | 3/3 | 14.701x | 12.945-15.932x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 16 | 3/3 | 6.481x | 5.300-7.235x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 2 | 3/3 | 1.796x | 1.727-2.210x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 4 | 3/3 | 4.807x | 4.625-5.129x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 8 | 3/3 | 18.940x | 16.017-21.213x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 12 | 3/3 | 14.680x | 13.265-16.561x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 16 | 3/3 | 5.974x | 4.988-6.717x | 3 / 0 | 0 / 0 |
| wide-100000 | scalar | 2 | 3/3 | 21.249x | 17.199-22.297x | 3 / 0 | 0 / 0 |
| wide-100000 | scalar | 4 | 3/3 | 0.296x | 0.277-0.306x | 0 / 3 | 0 / 0 |
| wide-100000 | scalar | 8 | 3/3 | 0.089x | 0.087-0.092x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 2 | 3/3 | 4.246x | 3.967-4.461x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 4 | 3/3 | 13.905x | 13.251-14.383x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 8 | 3/3 | 1.257x | 1.187-1.306x | 2 / 1 | 0 / 0 |
| wide-100000 | either | 12 | 3/3 | 9.535x | 9.026-10.050x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 16 | 3/3 | 4.151x | 4.019-4.314x | 2 / 1 | 0 / 0 |
| wide-100000 | all | 2 | 3/3 | 1.366x | 1.267-1.468x | 2 / 1 | 0 / 0 |
| wide-100000 | all | 4 | 3/3 | 3.275x | 3.079-3.696x | 3 / 0 | 0 / 0 |
| wide-100000 | all | 8 | 3/3 | 12.489x | 12.242-13.007x | 3 / 0 | 0 / 0 |
| wide-100000 | all | 12 | 3/3 | 10.773x | 10.114-11.062x | 3 / 0 | 0 / 0 |
| wide-100000 | all | 16 | 3/3 | 4.386x | 4.247-4.452x | 3 / 0 | 0 / 0 |

### Per-query results

| Dataset | Query | Attributes | Oracle matches | Actual selectivity | Kiara p50 us | Opponent p50 us | Opponent / Kiara | Outcome |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| wide-10000 | scalar-w2-s1 | 2 | 8 | 0.0800% | 2.400 | 747.781 | 311.5754x | Kiara lower p50 |
| wide-10000 | scalar-w2-s10 | 2 | 97 | 0.9700% | 28.204 | 752.596 | 26.6840x | Kiara lower p50 |
| wide-10000 | scalar-w2-s100 | 2 | 998 | 9.9800% | 622.678 | 879.307 | 1.4121x | Kiara lower p50 |
| wide-10000 | scalar-w4-s1 | 4 | 8 | 0.0800% | 2796.251 | 1405.184 | 0.5025x | Opponent lower p50 |
| wide-10000 | scalar-w4-s10 | 4 | 97 | 0.9700% | 2785.452 | 1390.982 | 0.4994x | Opponent lower p50 |
| wide-10000 | scalar-w4-s100 | 4 | 995 | 9.9500% | 3802.299 | 1582.709 | 0.4163x | Opponent lower p50 |
| wide-10000 | scalar-w8-s1 | 8 | 9 | 0.0900% | 4887.930 | 1839.337 | 0.3763x | Opponent lower p50 |
| wide-10000 | scalar-w8-s10 | 8 | 101 | 1.0100% | 7957.473 | 1898.405 | 0.2386x | Opponent lower p50 |
| wide-10000 | scalar-w8-s100 | 8 | 1014 | 10.1400% | 37143.042 | 2130.372 | 0.0574x | Opponent lower p50 |
| wide-10000 | either-w2-s1 | 2 | 8 | 0.0800% | 3.401 | 54.408 | 15.9976x | Kiara lower p50 |
| wide-10000 | either-w2-s10 | 2 | 94 | 0.9400% | 50.507 | 419.631 | 8.3084x | Kiara lower p50 |
| wide-10000 | either-w2-s100 | 2 | 995 | 9.9500% | 1101.239 | 4306.368 | 3.9105x | Kiara lower p50 |
| wide-10000 | either-w4-s1 | 4 | 8 | 0.0800% | 6.001 | 891.315 | 148.5277x | Kiara lower p50 |
| wide-10000 | either-w4-s10 | 4 | 98 | 0.9800% | 131.015 | 2006.361 | 15.3140x | Kiara lower p50 |
| wide-10000 | either-w4-s100 | 4 | 1015 | 10.1500% | 2362.161 | 11956.922 | 5.0619x | Kiara lower p50 |
| wide-10000 | either-w8-s1 | 8 | 9 | 0.0900% | 3081.998 | 1825.435 | 0.5923x | Opponent lower p50 |
| wide-10000 | either-w8-s10 | 8 | 97 | 0.9700% | 3323.631 | 6482.996 | 1.9506x | Kiara lower p50 |
| wide-10000 | either-w8-s100 | 8 | 1013 | 10.1300% | 7668.979 | 61557.814 | 8.0269x | Kiara lower p50 |
| wide-10000 | either-w12-s1 | 12 | 8 | 0.0800% | 4412.381 | 98441.401 | 22.3103x | Kiara lower p50 |
| wide-10000 | either-w12-s10 | 12 | 109 | 1.0900% | 5143.350 | 97396.333 | 18.9364x | Kiara lower p50 |
| wide-10000 | either-w12-s100 | 12 | 1018 | 10.1800% | 14408.215 | 108350.459 | 7.5200x | Kiara lower p50 |
| wide-10000 | either-w16-s1 | 16 | 7 | 0.0700% | 4953.312 | 113197.903 | 22.8530x | Kiara lower p50 |
| wide-10000 | either-w16-s10 | 16 | 104 | 1.0400% | 13418.678 | 109678.042 | 8.1735x | Kiara lower p50 |
| wide-10000 | either-w16-s100 | 16 | 1013 | 10.1300% | 82259.934 | 119900.266 | 1.4576x | Kiara lower p50 |
| wide-10000 | all-w2-s1 | 2 | 8 | 0.0800% | 146.119 | 60.908 | 0.4168x | Opponent lower p50 |
| wide-10000 | all-w2-s10 | 2 | 95 | 0.9500% | 198.727 | 504.665 | 2.5395x | Kiara lower p50 |
| wide-10000 | all-w2-s100 | 2 | 1008 | 10.0800% | 982.028 | 5374.673 | 5.4730x | Kiara lower p50 |
| wide-10000 | all-w4-s1 | 4 | 8 | 0.0800% | 301.739 | 871.925 | 2.8897x | Kiara lower p50 |
| wide-10000 | all-w4-s10 | 4 | 96 | 0.9600% | 411.251 | 2170.367 | 5.2775x | Kiara lower p50 |
| wide-10000 | all-w4-s100 | 4 | 1026 | 10.2600% | 2146.880 | 15636.236 | 7.2832x | Kiara lower p50 |
| wide-10000 | all-w8-s1 | 8 | 7 | 0.0700% | 3418.150 | 86527.607 | 25.3142x | Kiara lower p50 |
| wide-10000 | all-w8-s10 | 8 | 98 | 0.9800% | 3845.504 | 83224.984 | 21.6422x | Kiara lower p50 |
| wide-10000 | all-w8-s100 | 8 | 1013 | 10.1300% | 7399.379 | 91770.186 | 12.4024x | Kiara lower p50 |
| wide-10000 | all-w12-s1 | 12 | 8 | 0.0800% | 7153.381 | 131822.672 | 18.4280x | Kiara lower p50 |
| wide-10000 | all-w12-s10 | 12 | 104 | 1.0400% | 7142.698 | 142189.877 | 19.9070x | Kiara lower p50 |
| wide-10000 | all-w12-s100 | 12 | 1004 | 10.0400% | 16132.636 | 139116.271 | 8.6233x | Kiara lower p50 |
| wide-10000 | all-w16-s1 | 16 | 8 | 0.0800% | 9129.456 | 146115.972 | 16.0049x | Kiara lower p50 |
| wide-10000 | all-w16-s10 | 16 | 112 | 1.1200% | 18406.173 | 160638.073 | 8.7274x | Kiara lower p50 |
| wide-10000 | all-w16-s100 | 16 | 1030 | 10.3000% | 106339.432 | 162321.413 | 1.5264x | Kiara lower p50 |
| wide-100000 | scalar-w2-s1 | 2 | 96 | 0.0960% | 34.703 | 14397.463 | 414.8766x | Kiara lower p50 |
| wide-100000 | scalar-w2-s10 | 2 | 970 | 0.9700% | 808.270 | 13398.674 | 16.5770x | Kiara lower p50 |
| wide-100000 | scalar-w2-s100 | 2 | 10002 | 10.0020% | 12374.460 | 17263.361 | 1.3951x | Kiara lower p50 |
| wide-100000 | scalar-w4-s1 | 4 | 104 | 0.1040% | 57256.673 | 17745.750 | 0.3099x | Opponent lower p50 |
| wide-100000 | scalar-w4-s10 | 4 | 980 | 0.9800% | 58166.186 | 18283.263 | 0.3143x | Opponent lower p50 |
| wide-100000 | scalar-w4-s100 | 4 | 9990 | 9.9900% | 76027.221 | 20153.726 | 0.2651x | Opponent lower p50 |
| wide-100000 | scalar-w8-s1 | 8 | 95 | 0.0950% | 106046.189 | 19430.682 | 0.1832x | Opponent lower p50 |
| wide-100000 | scalar-w8-s10 | 8 | 989 | 0.9890% | 162367.393 | 19958.387 | 0.1229x | Opponent lower p50 |
| wide-100000 | scalar-w8-s100 | 8 | 9958 | 9.9580% | 767886.724 | 23671.129 | 0.0308x | Opponent lower p50 |
| wide-100000 | either-w2-s1 | 2 | 94 | 0.0940% | 77.808 | 528.159 | 6.7880x | Kiara lower p50 |
| wide-100000 | either-w2-s10 | 2 | 963 | 0.9630% | 1633.058 | 5743.929 | 3.5173x | Kiara lower p50 |
| wide-100000 | either-w2-s100 | 2 | 10010 | 10.0100% | 25718.956 | 82472.761 | 3.2067x | Kiara lower p50 |
| wide-100000 | either-w4-s1 | 4 | 98 | 0.0980% | 192.817 | 16280.143 | 84.4331x | Kiara lower p50 |
| wide-100000 | either-w4-s10 | 4 | 970 | 0.9700% | 3479.102 | 31877.552 | 9.1626x | Kiara lower p50 |
| wide-100000 | either-w4-s100 | 4 | 10062 | 10.0620% | 53376.419 | 185489.701 | 3.4751x | Kiara lower p50 |
| wide-100000 | either-w8-s1 | 8 | 94 | 0.0940% | 58068.047 | 23452.411 | 0.4039x | Opponent lower p50 |
| wide-100000 | either-w8-s10 | 8 | 974 | 0.9740% | 67489.065 | 79523.815 | 1.1783x | Kiara lower p50 |
| wide-100000 | either-w8-s100 | 8 | 9988 | 9.9880% | 155030.777 | 647270.692 | 4.1751x | Kiara lower p50 |
| wide-100000 | either-w12-s1 | 12 | 101 | 0.1010% | 94214.470 | 1307612.261 | 13.8791x | Kiara lower p50 |
| wide-100000 | either-w12-s10 | 12 | 1009 | 1.0090% | 109100.135 | 1300113.986 | 11.9167x | Kiara lower p50 |
| wide-100000 | either-w12-s100 | 12 | 9966 | 9.9660% | 261361.254 | 1370051.838 | 5.2420x | Kiara lower p50 |
| wide-100000 | either-w16-s1 | 16 | 103 | 0.1030% | 116261.347 | 1442679.458 | 12.4089x | Kiara lower p50 |
| wide-100000 | either-w16-s10 | 16 | 1016 | 1.0160% | 254243.339 | 1506061.408 | 5.9237x | Kiara lower p50 |
| wide-100000 | either-w16-s100 | 16 | 9959 | 9.9590% | 1643490.843 | 1598838.822 | 0.9728x | Opponent lower p50 |
| wide-100000 | all-w2-s1 | 2 | 94 | 0.0940% | 1781.578 | 613.358 | 0.3443x | Opponent lower p50 |
| wide-100000 | all-w2-s10 | 2 | 956 | 0.9560% | 3619.652 | 6117.673 | 1.6901x | Kiara lower p50 |
| wide-100000 | all-w2-s100 | 2 | 10019 | 10.0190% | 22447.088 | 98238.093 | 4.3764x | Kiara lower p50 |
| wide-100000 | all-w4-s1 | 4 | 91 | 0.0910% | 6736.186 | 16587.277 | 2.4624x | Kiara lower p50 |
| wide-100000 | all-w4-s10 | 4 | 961 | 0.9610% | 11072.076 | 35218.597 | 3.1808x | Kiara lower p50 |
| wide-100000 | all-w4-s100 | 4 | 10054 | 10.0540% | 48685.863 | 218396.416 | 4.4858x | Kiara lower p50 |
| wide-100000 | all-w8-s1 | 8 | 87 | 0.0870% | 76056.209 | 1174383.762 | 15.4410x | Kiara lower p50 |
| wide-100000 | all-w8-s10 | 8 | 953 | 0.9530% | 81193.842 | 1191551.548 | 14.6754x | Kiara lower p50 |
| wide-100000 | all-w8-s100 | 8 | 10046 | 10.0460% | 148507.991 | 1276547.752 | 8.5958x | Kiara lower p50 |
| wide-100000 | all-w12-s1 | 12 | 98 | 0.0980% | 137911.880 | 1827396.676 | 13.2505x | Kiara lower p50 |
| wide-100000 | all-w12-s10 | 12 | 992 | 0.9920% | 147858.840 | 1833782.052 | 12.4022x | Kiara lower p50 |
| wide-100000 | all-w12-s100 | 12 | 9992 | 9.9920% | 262818.703 | 1999400.693 | 7.6075x | Kiara lower p50 |
| wide-100000 | all-w16-s1 | 16 | 100 | 0.1000% | 170858.769 | 2126840.211 | 12.4479x | Kiara lower p50 |
| wide-100000 | all-w16-s10 | 16 | 1006 | 1.0060% | 354137.948 | 2176058.597 | 6.1447x | Kiara lower p50 |
| wide-100000 | all-w16-s100 | 16 | 10005 | 10.0050% | 2179582.890 | 2403591.779 | 1.1028x | Kiara lower p50 |

## Kiara vs sqlite-tuned

| Dataset | Family | Attributes | Fully measured queries | Opponent / Kiara | Exploratory 95% interval | Measured p50 wins K / opponent | Deadline wins K / opponent |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: |
| wide-10000 | scalar | 2 | 3/3 | 3.190x | 3.003-3.294x | 3 / 0 | 0 / 0 |
| wide-10000 | scalar | 4 | 3/3 | 0.046x | 0.038-0.053x | 0 / 3 | 0 / 0 |
| wide-10000 | scalar | 8 | 3/3 | 0.020x | 0.019-0.022x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 2 | 3/3 | 4.000x | 3.857-4.148x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 4 | 3/3 | 4.036x | 3.943-4.376x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 8 | 3/3 | 0.273x | 0.268-0.313x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 12 | 3/3 | 0.317x | 0.296-0.351x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 16 | 3/3 | 0.164x | 0.155-0.170x | 0 / 3 | 0 / 0 |
| wide-10000 | all | 2 | 3/3 | 1.249x | 1.185-1.375x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 4 | 3/3 | 1.559x | 1.456-1.618x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 8 | 3/3 | 0.528x | 0.475-0.565x | 1 / 2 | 0 / 0 |
| wide-10000 | all | 12 | 3/3 | 0.553x | 0.495-0.632x | 1 / 2 | 0 / 0 |
| wide-10000 | all | 16 | 3/3 | 0.251x | 0.236-0.283x | 0 / 3 | 0 / 0 |
| wide-100000 | scalar | 2 | 3/3 | 0.958x | 0.896-1.028x | 1 / 2 | 0 / 0 |
| wide-100000 | scalar | 4 | 3/3 | 0.013x | 0.013-0.013x | 0 / 3 | 0 / 0 |
| wide-100000 | scalar | 8 | 3/3 | 0.005x | 0.005-0.005x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 2 | 3/3 | 1.319x | 1.273-1.379x | 2 / 1 | 0 / 0 |
| wide-100000 | either | 4 | 3/3 | 1.192x | 0.943-1.218x | 1 / 2 | 0 / 0 |
| wide-100000 | either | 8 | 3/3 | 0.083x | 0.082-0.085x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 12 | 3/3 | 0.081x | 0.077-0.086x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 16 | 3/3 | 0.039x | 0.037-0.039x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 2 | 3/3 | 0.612x | 0.539-0.657x | 1 / 2 | 0 / 0 |
| wide-100000 | all | 4 | 3/3 | 0.399x | 0.353-0.466x | 1 / 2 | 0 / 0 |
| wide-100000 | all | 8 | 3/3 | 0.156x | 0.144-0.163x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 12 | 3/3 | 0.163x | 0.158-0.176x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 16 | 3/3 | 0.071x | 0.067-0.073x | 0 / 3 | 0 / 0 |

### Per-query results

| Dataset | Query | Attributes | Oracle matches | Actual selectivity | Kiara p50 us | Opponent p50 us | Opponent / Kiara | Outcome |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| wide-10000 | scalar-w2-s1 | 2 | 8 | 0.0800% | 2.301 | 25.302 | 10.9961x | Kiara lower p50 |
| wide-10000 | scalar-w2-s10 | 2 | 97 | 0.9700% | 28.102 | 81.007 | 2.8826x | Kiara lower p50 |
| wide-10000 | scalar-w2-s100 | 2 | 998 | 9.9800% | 667.755 | 684.156 | 1.0246x | Kiara lower p50 |
| wide-10000 | scalar-w4-s1 | 4 | 8 | 0.0800% | 2706.821 | 31.403 | 0.0116x | Opponent lower p50 |
| wide-10000 | scalar-w4-s10 | 4 | 97 | 0.9700% | 2711.527 | 96.007 | 0.0354x | Opponent lower p50 |
| wide-10000 | scalar-w4-s100 | 4 | 995 | 9.9500% | 3370.779 | 814.868 | 0.2417x | Opponent lower p50 |
| wide-10000 | scalar-w8-s1 | 8 | 9 | 0.0900% | 4401.466 | 63.407 | 0.0144x | Opponent lower p50 |
| wide-10000 | scalar-w8-s10 | 8 | 101 | 1.0100% | 7887.856 | 149.713 | 0.0190x | Opponent lower p50 |
| wide-10000 | scalar-w8-s100 | 8 | 1014 | 10.1400% | 34498.363 | 1036.785 | 0.0301x | Opponent lower p50 |
| wide-10000 | either-w2-s1 | 2 | 8 | 0.0800% | 3.500 | 43.503 | 12.4294x | Kiara lower p50 |
| wide-10000 | either-w2-s10 | 2 | 94 | 0.9400% | 51.204 | 186.016 | 3.6328x | Kiara lower p50 |
| wide-10000 | either-w2-s100 | 2 | 995 | 9.9500% | 1105.792 | 1567.430 | 1.4175x | Kiara lower p50 |
| wide-10000 | either-w4-s1 | 4 | 8 | 0.0800% | 6.101 | 107.209 | 17.5724x | Kiara lower p50 |
| wide-10000 | either-w4-s10 | 4 | 98 | 0.9800% | 128.911 | 380.532 | 2.9519x | Kiara lower p50 |
| wide-10000 | either-w4-s100 | 4 | 1015 | 10.1500% | 2513.029 | 3186.160 | 1.2679x | Kiara lower p50 |
| wide-10000 | either-w8-s1 | 8 | 9 | 0.0900% | 2744.328 | 296.524 | 0.1080x | Opponent lower p50 |
| wide-10000 | either-w8-s10 | 8 | 97 | 0.9700% | 3194.461 | 812.973 | 0.2545x | Opponent lower p50 |
| wide-10000 | either-w8-s100 | 8 | 1013 | 10.1300% | 7667.619 | 5674.165 | 0.7400x | Opponent lower p50 |
| wide-10000 | either-w12-s1 | 12 | 8 | 0.0800% | 4221.253 | 739.663 | 0.1752x | Opponent lower p50 |
| wide-10000 | either-w12-s10 | 12 | 109 | 1.0900% | 5028.002 | 1515.024 | 0.3013x | Opponent lower p50 |
| wide-10000 | either-w12-s100 | 12 | 1018 | 10.1800% | 14384.693 | 8687.222 | 0.6039x | Opponent lower p50 |
| wide-10000 | either-w16-s1 | 16 | 7 | 0.0700% | 5435.845 | 1061.985 | 0.1954x | Opponent lower p50 |
| wide-10000 | either-w16-s10 | 16 | 104 | 1.0400% | 12101.605 | 1945.362 | 0.1608x | Opponent lower p50 |
| wide-10000 | either-w16-s100 | 16 | 1013 | 10.1300% | 76596.294 | 10682.452 | 0.1395x | Opponent lower p50 |
| wide-10000 | all-w2-s1 | 2 | 8 | 0.0800% | 118.309 | 60.605 | 0.5123x | Opponent lower p50 |
| wide-10000 | all-w2-s10 | 2 | 95 | 0.9500% | 168.114 | 265.322 | 1.5782x | Kiara lower p50 |
| wide-10000 | all-w2-s100 | 2 | 1008 | 10.0800% | 957.879 | 2305.988 | 2.4074x | Kiara lower p50 |
| wide-10000 | all-w4-s1 | 4 | 8 | 0.0800% | 237.123 | 231.420 | 0.9759x | Opponent lower p50 |
| wide-10000 | all-w4-s10 | 4 | 96 | 0.9600% | 353.629 | 639.253 | 1.8077x | Kiara lower p50 |
| wide-10000 | all-w4-s100 | 4 | 1026 | 10.2600% | 2048.387 | 4399.067 | 2.1476x | Kiara lower p50 |
| wide-10000 | all-w8-s1 | 8 | 7 | 0.0700% | 3396.991 | 804.067 | 0.2367x | Opponent lower p50 |
| wide-10000 | all-w8-s10 | 8 | 98 | 0.9800% | 3560.755 | 1646.334 | 0.4624x | Opponent lower p50 |
| wide-10000 | all-w8-s100 | 8 | 1013 | 10.1300% | 7261.222 | 9784.796 | 1.3475x | Kiara lower p50 |
| wide-10000 | all-w12-s1 | 12 | 8 | 0.0800% | 6041.404 | 1812.351 | 0.3000x | Opponent lower p50 |
| wide-10000 | all-w12-s10 | 12 | 104 | 1.0400% | 7085.388 | 3355.698 | 0.4736x | Opponent lower p50 |
| wide-10000 | all-w12-s100 | 12 | 1004 | 10.0400% | 14952.107 | 17791.232 | 1.1899x | Kiara lower p50 |
| wide-10000 | all-w16-s1 | 16 | 8 | 0.0800% | 9472.482 | 2459.006 | 0.2596x | Opponent lower p50 |
| wide-10000 | all-w16-s10 | 16 | 112 | 1.1200% | 16647.361 | 4367.663 | 0.2624x | Opponent lower p50 |
| wide-10000 | all-w16-s100 | 16 | 1030 | 10.3000% | 98557.110 | 22858.727 | 0.2319x | Opponent lower p50 |
| wide-100000 | scalar-w2-s1 | 2 | 96 | 0.0960% | 34.603 | 80.507 | 2.3266x | Kiara lower p50 |
| wide-100000 | scalar-w2-s10 | 2 | 970 | 0.9700% | 846.371 | 662.055 | 0.7822x | Opponent lower p50 |
| wide-100000 | scalar-w2-s100 | 2 | 10002 | 10.0020% | 13661.814 | 6598.846 | 0.4830x | Opponent lower p50 |
| wide-100000 | scalar-w4-s1 | 4 | 104 | 0.1040% | 58024.238 | 99.209 | 0.0017x | Opponent lower p50 |
| wide-100000 | scalar-w4-s10 | 4 | 980 | 0.9800% | 58648.277 | 765.063 | 0.0130x | Opponent lower p50 |
| wide-100000 | scalar-w4-s100 | 4 | 9990 | 9.9900% | 73002.982 | 7395.919 | 0.1013x | Opponent lower p50 |
| wide-100000 | scalar-w8-s1 | 8 | 95 | 0.0950% | 100926.295 | 141.812 | 0.0014x | Opponent lower p50 |
| wide-100000 | scalar-w8-s10 | 8 | 989 | 0.9890% | 167389.099 | 1007.485 | 0.0060x | Opponent lower p50 |
| wide-100000 | scalar-w8-s100 | 8 | 9958 | 9.9580% | 785564.424 | 9463.398 | 0.0120x | Opponent lower p50 |
| wide-100000 | either-w2-s1 | 2 | 94 | 0.0940% | 81.807 | 206.617 | 2.5257x | Kiara lower p50 |
| wide-100000 | either-w2-s10 | 2 | 963 | 0.9630% | 1592.032 | 1816.552 | 1.1410x | Kiara lower p50 |
| wide-100000 | either-w2-s100 | 2 | 10010 | 10.0100% | 24237.764 | 19290.211 | 0.7959x | Opponent lower p50 |
| wide-100000 | either-w4-s1 | 4 | 98 | 0.0980% | 196.917 | 420.636 | 2.1361x | Kiara lower p50 |
| wide-100000 | either-w4-s10 | 4 | 970 | 0.9700% | 3564.305 | 3447.794 | 0.9673x | Opponent lower p50 |
| wide-100000 | either-w4-s100 | 4 | 10062 | 10.0620% | 52815.704 | 43296.351 | 0.8198x | Opponent lower p50 |
| wide-100000 | either-w8-s1 | 8 | 94 | 0.0940% | 60111.223 | 843.268 | 0.0140x | Opponent lower p50 |
| wide-100000 | either-w8-s10 | 8 | 974 | 0.9740% | 67270.229 | 5899.985 | 0.0877x | Opponent lower p50 |
| wide-100000 | either-w8-s100 | 8 | 9988 | 9.9880% | 155251.526 | 72840.895 | 0.4692x | Opponent lower p50 |
| wide-100000 | either-w12-s1 | 12 | 101 | 0.1010% | 92668.454 | 1567.327 | 0.0169x | Opponent lower p50 |
| wide-100000 | either-w12-s10 | 12 | 1009 | 1.0090% | 112189.368 | 9022.155 | 0.0804x | Opponent lower p50 |
| wide-100000 | either-w12-s100 | 12 | 9966 | 9.9660% | 258647.759 | 101665.815 | 0.3931x | Opponent lower p50 |
| wide-100000 | either-w16-s1 | 16 | 103 | 0.1030% | 110374.543 | 2021.871 | 0.0183x | Opponent lower p50 |
| wide-100000 | either-w16-s10 | 16 | 1016 | 1.0160% | 255806.858 | 11242.258 | 0.0439x | Opponent lower p50 |
| wide-100000 | either-w16-s100 | 16 | 9959 | 9.9590% | 1654641.378 | 118058.526 | 0.0713x | Opponent lower p50 |
| wide-100000 | all-w2-s1 | 2 | 94 | 0.0940% | 1734.346 | 305.025 | 0.1759x | Opponent lower p50 |
| wide-100000 | all-w2-s10 | 2 | 956 | 0.9560% | 3122.561 | 2779.929 | 0.8903x | Opponent lower p50 |
| wide-100000 | all-w2-s100 | 2 | 10019 | 10.0190% | 21331.005 | 31217.229 | 1.4635x | Kiara lower p50 |
| wide-100000 | all-w4-s1 | 4 | 91 | 0.0910% | 6203.411 | 710.253 | 0.1145x | Opponent lower p50 |
| wide-100000 | all-w4-s10 | 4 | 961 | 0.9610% | 10956.202 | 5305.946 | 0.4843x | Opponent lower p50 |
| wide-100000 | all-w4-s100 | 4 | 10054 | 10.0540% | 49684.982 | 57106.669 | 1.1494x | Kiara lower p50 |
| wide-100000 | all-w8-s1 | 8 | 87 | 0.0870% | 78827.885 | 1864.050 | 0.0236x | Opponent lower p50 |
| wide-100000 | all-w8-s10 | 8 | 953 | 0.9530% | 79889.309 | 13230.230 | 0.1656x | Opponent lower p50 |
| wide-100000 | all-w8-s100 | 8 | 10046 | 10.0460% | 148908.537 | 145236.887 | 0.9753x | Opponent lower p50 |
| wide-100000 | all-w12-s1 | 12 | 98 | 0.0980% | 140044.588 | 3611.987 | 0.0258x | Opponent lower p50 |
| wide-100000 | all-w12-s10 | 12 | 992 | 0.9920% | 149374.195 | 25407.076 | 0.1701x | Opponent lower p50 |
| wide-100000 | all-w12-s100 | 12 | 9992 | 9.9920% | 264394.266 | 262663.749 | 0.9935x | Opponent lower p50 |
| wide-100000 | all-w16-s1 | 16 | 100 | 0.1000% | 167528.937 | 4707.897 | 0.0281x | Opponent lower p50 |
| wide-100000 | all-w16-s10 | 16 | 1006 | 1.0060% | 349396.907 | 31889.856 | 0.0913x | Opponent lower p50 |
| wide-100000 | all-w16-s100 | 16 | 10005 | 10.0050% | 2192344.211 | 301892.777 | 0.1377x | Opponent lower p50 |

## Kiara vs sqlite-generic

| Dataset | Family | Attributes | Fully measured queries | Opponent / Kiara | Exploratory 95% interval | Measured p50 wins K / opponent | Deadline wins K / opponent |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: |
| wide-10000 | scalar | 2 | 3/3 | 44.872x | 42.457-51.850x | 3 / 0 | 0 / 0 |
| wide-10000 | scalar | 4 | 3/3 | 0.592x | 0.512-0.678x | 0 / 3 | 0 / 0 |
| wide-10000 | scalar | 8 | 3/3 | 0.166x | 0.155-0.249x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 2 | 3/3 | 125.437x | 120.276-138.739x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 4 | 3/3 | 20.457x | 19.998-21.514x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 8 | 3/3 | 1.420x | 1.302-1.633x | 2 / 1 | 0 / 0 |
| wide-10000 | either | 12 | 3/3 | 404.138x | 351.604-427.575x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 16 | 3/3 | 223.221x | 208.261-229.979x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 2 | 3/3 | 25.956x | 25.312-27.789x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 4 | 3/3 | 4.977x | 4.733-5.715x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 8 | 3/3 | 1.598x | 1.530-1.734x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 12 | 3/3 | 391.306x | 359.133-402.826x | 3 / 0 | 0 / 0 |
| wide-10000 | all | 16 | 2/3 | 138.780x | 119.316-148.812x | 2 / 0 | 1 / 0 |
| wide-100000 | scalar | 2 | 3/3 | 33.881x | 33.015-37.077x | 3 / 0 | 0 / 0 |
| wide-100000 | scalar | 4 | 3/3 | 0.447x | 0.399-0.503x | 0 / 3 | 0 / 0 |
| wide-100000 | scalar | 8 | 3/3 | 0.127x | 0.120-0.130x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 2 | 3/3 | 80.115x | 78.031-82.779x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 4 | 3/3 | 13.329x | 12.507-13.768x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 8 | 3/3 | 0.990x | 0.944-1.014x | 1 / 2 | 0 / 0 |
| wide-100000 | either | 12 | 1/3 | 300.158x | 283.092-311.939x | 1 / 0 | 2 / 0 |
| wide-100000 | either | 16 | 1/3 | 407.571x | 387.557-419.947x | 1 / 0 | 2 / 0 |
| wide-100000 | all | 2 | 3/3 | 25.183x | 23.180-26.773x | 3 / 0 | 0 / 0 |
| wide-100000 | all | 4 | 3/3 | 4.058x | 2.990-4.442x | 3 / 0 | 0 / 0 |
| wide-100000 | all | 8 | 3/3 | 1.142x | 1.103-1.272x | 1 / 2 | 0 / 0 |
| wide-100000 | all | 12 | 1/3 | 298.414x | 276.382-314.571x | 1 / 0 | 2 / 0 |
| wide-100000 | all | 16 | 0/3 | n/a | n/a | 0 / 0 | 3 / 0 |

### Per-query results

| Dataset | Query | Attributes | Oracle matches | Actual selectivity | Kiara p50 us | Opponent p50 us | Opponent / Kiara | Outcome |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| wide-10000 | scalar-w2-s1 | 2 | 8 | 0.0800% | 2.401 | 1488.504 | 619.9517x | Kiara lower p50 |
| wide-10000 | scalar-w2-s10 | 2 | 97 | 0.9700% | 38.503 | 1555.868 | 40.4090x | Kiara lower p50 |
| wide-10000 | scalar-w2-s100 | 2 | 998 | 9.9800% | 654.157 | 2359.198 | 3.6065x | Kiara lower p50 |
| wide-10000 | scalar-w4-s1 | 4 | 8 | 0.0800% | 2855.054 | 1483.355 | 0.5196x | Opponent lower p50 |
| wide-10000 | scalar-w4-s10 | 4 | 97 | 0.9700% | 2864.853 | 1574.834 | 0.5497x | Opponent lower p50 |
| wide-10000 | scalar-w4-s100 | 4 | 995 | 9.9500% | 3638.499 | 2643.492 | 0.7265x | Opponent lower p50 |
| wide-10000 | scalar-w8-s1 | 8 | 9 | 0.0900% | 4801.725 | 1494.161 | 0.3112x | Opponent lower p50 |
| wide-10000 | scalar-w8-s10 | 8 | 101 | 1.0100% | 8251.834 | 1653.344 | 0.2004x | Opponent lower p50 |
| wide-10000 | scalar-w8-s100 | 8 | 1014 | 10.1400% | 36695.054 | 2672.732 | 0.0728x | Opponent lower p50 |
| wide-10000 | either-w2-s1 | 2 | 8 | 0.0800% | 3.701 | 7743.977 | 2092.4012x | Kiara lower p50 |
| wide-10000 | either-w2-s10 | 2 | 94 | 0.9400% | 65.206 | 7785.357 | 119.3963x | Kiara lower p50 |
| wide-10000 | either-w2-s100 | 2 | 995 | 9.9500% | 1150.202 | 9086.851 | 7.9002x | Kiara lower p50 |
| wide-10000 | either-w4-s1 | 4 | 8 | 0.0800% | 8.501 | 1557.435 | 183.2061x | Kiara lower p50 |
| wide-10000 | either-w4-s10 | 4 | 98 | 0.9800% | 141.112 | 2143.672 | 15.1913x | Kiara lower p50 |
| wide-10000 | either-w4-s100 | 4 | 1015 | 10.1500% | 2475.315 | 7614.059 | 3.0760x | Kiara lower p50 |
| wide-10000 | either-w8-s1 | 8 | 9 | 0.0900% | 2830.349 | 1787.436 | 0.6315x | Opponent lower p50 |
| wide-10000 | either-w8-s10 | 8 | 97 | 0.9700% | 3264.586 | 4244.618 | 1.3002x | Kiara lower p50 |
| wide-10000 | either-w8-s100 | 8 | 1013 | 10.1300% | 8276.735 | 28872.757 | 3.4884x | Kiara lower p50 |
| wide-10000 | either-w12-s1 | 12 | 8 | 0.0800% | 4513.701 | 240923.483 | 53.3760x | Kiara lower p50 |
| wide-10000 | either-w12-s10 | 12 | 109 | 1.0900% | 5228.838 | 3102139.903 | 593.2752x | Kiara lower p50 |
| wide-10000 | either-w12-s100 | 12 | 1018 | 10.1800% | 14396.580 | 30008532.638 | 2084.4209x | Kiara lower p50 |
| wide-10000 | either-w16-s1 | 16 | 7 | 0.0700% | 5535.778 | 309640.512 | 55.9344x | Kiara lower p50 |
| wide-10000 | either-w16-s10 | 16 | 104 | 1.0400% | 12667.828 | 4467170.169 | 352.6390x | Kiara lower p50 |
| wide-10000 | either-w16-s100 | 16 | 1013 | 10.1300% | 81694.516 | 46066554.569 | 563.8880x | Kiara lower p50 |
| wide-10000 | all-w2-s1 | 2 | 8 | 0.0800% | 162.414 | 7782.135 | 47.9154x | Kiara lower p50 |
| wide-10000 | all-w2-s10 | 2 | 95 | 0.9500% | 217.019 | 7936.389 | 36.5700x | Kiara lower p50 |
| wide-10000 | all-w2-s100 | 2 | 1008 | 10.0800% | 1008.225 | 10061.617 | 9.9795x | Kiara lower p50 |
| wide-10000 | all-w4-s1 | 4 | 8 | 0.0800% | 328.328 | 1585.740 | 4.8297x | Kiara lower p50 |
| wide-10000 | all-w4-s10 | 4 | 96 | 0.9600% | 439.439 | 2395.458 | 5.4512x | Kiara lower p50 |
| wide-10000 | all-w4-s100 | 4 | 1026 | 10.2600% | 2260.489 | 10582.399 | 4.6815x | Kiara lower p50 |
| wide-10000 | all-w8-s1 | 8 | 7 | 0.0700% | 3702.058 | 1884.128 | 0.5089x | Opponent lower p50 |
| wide-10000 | all-w8-s10 | 8 | 98 | 0.9800% | 4186.820 | 5853.684 | 1.3981x | Kiara lower p50 |
| wide-10000 | all-w8-s100 | 8 | 1013 | 10.1300% | 7826.379 | 44897.723 | 5.7367x | Kiara lower p50 |
| wide-10000 | all-w12-s1 | 12 | 8 | 0.0800% | 7305.784 | 343926.347 | 47.0759x | Kiara lower p50 |
| wide-10000 | all-w12-s10 | 12 | 104 | 1.0400% | 8609.008 | 4161175.199 | 483.3513x | Kiara lower p50 |
| wide-10000 | all-w12-s100 | 12 | 1004 | 10.0400% | 15790.899 | 41581152.424 | 2633.2353x | Kiara lower p50 |
| wide-10000 | all-w16-s1 | 16 | 8 | 0.0800% | 9128.842 | 496234.880 | 54.3590x | Kiara lower p50 |
| wide-10000 | all-w16-s10 | 16 | 112 | 1.1200% | 18818.272 | 6667440.037 | 354.3067x | Kiara lower p50 |
| wide-10000 | all-w16-s100 | 16 | 1030 | 10.3000% | 107390.518 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | scalar-w2-s1 | 2 | 96 | 0.0960% | 47.905 | 23600.085 | 492.6435x | Kiara lower p50 |
| wide-100000 | scalar-w2-s10 | 2 | 970 | 0.9700% | 844.462 | 25243.999 | 29.8936x | Kiara lower p50 |
| wide-100000 | scalar-w2-s100 | 2 | 10002 | 10.0020% | 12631.145 | 33356.912 | 2.6408x | Kiara lower p50 |
| wide-100000 | scalar-w4-s1 | 4 | 104 | 0.1040% | 56781.966 | 26392.286 | 0.4648x | Opponent lower p50 |
| wide-100000 | scalar-w4-s10 | 4 | 980 | 0.9800% | 59156.727 | 24518.799 | 0.4145x | Opponent lower p50 |
| wide-100000 | scalar-w4-s100 | 4 | 9990 | 9.9900% | 76410.401 | 35340.458 | 0.4625x | Opponent lower p50 |
| wide-100000 | scalar-w8-s1 | 8 | 95 | 0.0950% | 101938.142 | 26829.999 | 0.2632x | Opponent lower p50 |
| wide-100000 | scalar-w8-s10 | 8 | 989 | 0.9890% | 167615.929 | 27689.207 | 0.1652x | Opponent lower p50 |
| wide-100000 | scalar-w8-s100 | 8 | 9958 | 9.9580% | 763990.405 | 35835.011 | 0.0469x | Opponent lower p50 |
| wide-100000 | either-w2-s1 | 2 | 94 | 0.0940% | 85.407 | 111540.943 | 1305.9930x | Kiara lower p50 |
| wide-100000 | either-w2-s10 | 2 | 963 | 0.9630% | 1564.519 | 115604.818 | 73.8916x | Kiara lower p50 |
| wide-100000 | either-w2-s100 | 2 | 10010 | 10.0100% | 24010.649 | 127941.785 | 5.3285x | Kiara lower p50 |
| wide-100000 | either-w4-s1 | 4 | 98 | 0.0980% | 218.216 | 26229.192 | 120.1983x | Kiara lower p50 |
| wide-100000 | either-w4-s10 | 4 | 970 | 0.9700% | 3539.457 | 36356.853 | 10.2719x | Kiara lower p50 |
| wide-100000 | either-w4-s100 | 4 | 10062 | 10.0620% | 52367.615 | 100433.640 | 1.9179x | Kiara lower p50 |
| wide-100000 | either-w8-s1 | 8 | 94 | 0.0940% | 58842.132 | 29537.336 | 0.5020x | Opponent lower p50 |
| wide-100000 | either-w8-s10 | 8 | 974 | 0.9740% | 68628.731 | 62077.708 | 0.9045x | Opponent lower p50 |
| wide-100000 | either-w8-s100 | 8 | 9988 | 9.9880% | 154325.936 | 330218.913 | 2.1397x | Kiara lower p50 |
| wide-100000 | either-w12-s1 | 12 | 101 | 0.1010% | 92212.014 | 27678148.508 | 300.1577x | Kiara lower p50 |
| wide-100000 | either-w12-s10 | 12 | 1009 | 1.0090% | 109269.309 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | either-w12-s100 | 12 | 9966 | 9.9660% | 259700.392 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | either-w16-s1 | 16 | 103 | 0.1030% | 111365.336 | 45389254.780 | 407.5708x | Kiara lower p50 |
| wide-100000 | either-w16-s10 | 16 | 1016 | 1.0160% | 258722.630 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | either-w16-s100 | 16 | 9959 | 9.9590% | 1620181.221 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | all-w2-s1 | 2 | 94 | 0.0940% | 1749.279 | 111344.827 | 63.6518x | Kiara lower p50 |
| wide-100000 | all-w2-s10 | 2 | 956 | 0.9560% | 2940.909 | 114222.297 | 38.8391x | Kiara lower p50 |
| wide-100000 | all-w2-s100 | 2 | 10019 | 10.0190% | 20544.748 | 132714.697 | 6.4598x | Kiara lower p50 |
| wide-100000 | all-w4-s1 | 4 | 91 | 0.0910% | 4418.323 | 27105.521 | 6.1348x | Kiara lower p50 |
| wide-100000 | all-w4-s10 | 4 | 961 | 0.9610% | 9018.342 | 39998.280 | 4.4352x | Kiara lower p50 |
| wide-100000 | all-w4-s100 | 4 | 10054 | 10.0540% | 49245.501 | 120951.934 | 2.4561x | Kiara lower p50 |
| wide-100000 | all-w8-s1 | 8 | 87 | 0.0870% | 73739.622 | 33161.555 | 0.4497x | Opponent lower p50 |
| wide-100000 | all-w8-s10 | 8 | 953 | 0.9530% | 80847.400 | 80006.557 | 0.9896x | Opponent lower p50 |
| wide-100000 | all-w8-s100 | 8 | 10046 | 10.0460% | 147971.954 | 495050.242 | 3.3456x | Kiara lower p50 |
| wide-100000 | all-w12-s1 | 12 | 98 | 0.0980% | 132028.284 | 39399045.905 | 298.4137x | Kiara lower p50 |
| wide-100000 | all-w12-s10 | 12 | 992 | 0.9920% | 145296.777 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | all-w12-s100 | 12 | 9992 | 9.9920% | 267854.794 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | all-w16-s1 | 16 | 100 | 0.1000% | 170105.604 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | all-w16-s10 | 16 | 1006 | 1.0060% | 353923.812 | TIMEOUT 5/5 | n/a | Kiara deadline win |
| wide-100000 | all-w16-s100 | 16 | 10005 | 10.0050% | 2156891.394 | TIMEOUT 5/5 | n/a | Kiara deadline win |

## Kiara vs lucene

| Dataset | Family | Attributes | Fully measured queries | Opponent / Kiara | Exploratory 95% interval | Measured p50 wins K / opponent | Deadline wins K / opponent |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: |
| wide-10000 | scalar | 2 | 3/3 | 24.653x | 18.880-25.862x | 3 / 0 | 0 / 0 |
| wide-10000 | scalar | 4 | 3/3 | 0.298x | 0.255-0.317x | 1 / 2 | 0 / 0 |
| wide-10000 | scalar | 8 | 3/3 | 0.093x | 0.084-0.097x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 2 | 3/3 | 14.918x | 11.321-15.937x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 4 | 3/3 | 7.683x | 7.130-8.195x | 3 / 0 | 0 / 0 |
| wide-10000 | either | 8 | 3/3 | 0.261x | 0.237-0.277x | 1 / 2 | 0 / 0 |
| wide-10000 | either | 12 | 3/3 | 0.196x | 0.184-0.214x | 0 / 3 | 0 / 0 |
| wide-10000 | either | 16 | 3/3 | 0.080x | 0.074-0.091x | 0 / 3 | 0 / 0 |
| wide-10000 | all | 2 | 3/3 | 2.755x | 2.548-3.039x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 4 | 3/3 | 1.408x | 1.308-1.502x | 2 / 1 | 0 / 0 |
| wide-10000 | all | 8 | 3/3 | 0.218x | 0.198-0.233x | 1 / 2 | 0 / 0 |
| wide-10000 | all | 12 | 3/3 | 0.149x | 0.127-0.198x | 0 / 3 | 0 / 0 |
| wide-10000 | all | 16 | 3/3 | 0.057x | 0.053-0.060x | 0 / 3 | 0 / 0 |
| wide-100000 | scalar | 2 | 3/3 | 12.362x | 11.160-12.882x | 3 / 0 | 0 / 0 |
| wide-100000 | scalar | 4 | 3/3 | 0.150x | 0.137-0.156x | 1 / 2 | 0 / 0 |
| wide-100000 | scalar | 8 | 3/3 | 0.041x | 0.037-0.043x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 2 | 3/3 | 6.257x | 5.655-6.376x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 4 | 3/3 | 2.898x | 2.616-2.987x | 3 / 0 | 0 / 0 |
| wide-100000 | either | 8 | 3/3 | 0.118x | 0.109-0.121x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 12 | 3/3 | 0.084x | 0.077-0.088x | 0 / 3 | 0 / 0 |
| wide-100000 | either | 16 | 3/3 | 0.033x | 0.031-0.036x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 2 | 3/3 | 1.763x | 1.462-1.883x | 2 / 1 | 0 / 0 |
| wide-100000 | all | 4 | 3/3 | 0.650x | 0.538-0.716x | 2 / 1 | 0 / 0 |
| wide-100000 | all | 8 | 3/3 | 0.100x | 0.092-0.103x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 12 | 3/3 | 0.067x | 0.063-0.068x | 0 / 3 | 0 / 0 |
| wide-100000 | all | 16 | 3/3 | 0.023x | 0.021-0.025x | 0 / 3 | 0 / 0 |

### Per-query results

| Dataset | Query | Attributes | Oracle matches | Actual selectivity | Kiara p50 us | Opponent p50 us | Opponent / Kiara | Outcome |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| wide-10000 | scalar-w2-s1 | 2 | 8 | 0.0800% | 2.400 | 87.207 | 36.3363x | Kiara lower p50 |
| wide-10000 | scalar-w2-s10 | 2 | 97 | 0.9700% | 28.602 | 943.165 | 32.9755x | Kiara lower p50 |
| wide-10000 | scalar-w2-s100 | 2 | 998 | 9.9800% | 621.343 | 7770.038 | 12.5052x | Kiara lower p50 |
| wide-10000 | scalar-w4-s1 | 4 | 8 | 0.0800% | 2702.888 | 94.307 | 0.0349x | Opponent lower p50 |
| wide-10000 | scalar-w4-s10 | 4 | 97 | 0.9700% | 2833.394 | 953.368 | 0.3365x | Opponent lower p50 |
| wide-10000 | scalar-w4-s100 | 4 | 995 | 9.9500% | 3456.243 | 7762.635 | 2.2460x | Kiara lower p50 |
| wide-10000 | scalar-w8-s1 | 8 | 9 | 0.0900% | 4601.424 | 128.309 | 0.0279x | Opponent lower p50 |
| wide-10000 | scalar-w8-s10 | 8 | 101 | 1.0100% | 7655.720 | 1009.269 | 0.1318x | Opponent lower p50 |
| wide-10000 | scalar-w8-s100 | 8 | 1014 | 10.1400% | 36523.550 | 7893.145 | 0.2161x | Opponent lower p50 |
| wide-10000 | either-w2-s1 | 2 | 8 | 0.0800% | 3.500 | 90.507 | 25.8591x | Kiara lower p50 |
| wide-10000 | either-w2-s10 | 2 | 94 | 0.9400% | 49.904 | 914.063 | 18.3164x | Kiara lower p50 |
| wide-10000 | either-w2-s100 | 2 | 995 | 9.9500% | 1095.675 | 7679.646 | 7.0091x | Kiara lower p50 |
| wide-10000 | either-w4-s1 | 4 | 8 | 0.0800% | 6.101 | 107.208 | 17.5722x | Kiara lower p50 |
| wide-10000 | either-w4-s10 | 4 | 98 | 0.9800% | 125.508 | 961.267 | 7.6590x | Kiara lower p50 |
| wide-10000 | either-w4-s100 | 4 | 1015 | 10.1500% | 2334.563 | 7865.447 | 3.3691x | Kiara lower p50 |
| wide-10000 | either-w8-s1 | 8 | 9 | 0.0900% | 2814.792 | 151.511 | 0.0538x | Opponent lower p50 |
| wide-10000 | either-w8-s10 | 8 | 97 | 0.9700% | 3255.346 | 1018.881 | 0.3130x | Opponent lower p50 |
| wide-10000 | either-w8-s100 | 8 | 1013 | 10.1300% | 7416.657 | 7805.843 | 1.0525x | Kiara lower p50 |
| wide-10000 | either-w12-s1 | 12 | 8 | 0.0800% | 4392.406 | 222.816 | 0.0507x | Opponent lower p50 |
| wide-10000 | either-w12-s10 | 12 | 109 | 1.0900% | 5055.345 | 1217.398 | 0.2408x | Opponent lower p50 |
| wide-10000 | either-w12-s100 | 12 | 1018 | 10.1800% | 13226.046 | 8183.927 | 0.6188x | Opponent lower p50 |
| wide-10000 | either-w16-s1 | 16 | 7 | 0.0700% | 5126.410 | 272.419 | 0.0531x | Opponent lower p50 |
| wide-10000 | either-w16-s10 | 16 | 104 | 1.0400% | 12637.491 | 1233.186 | 0.0976x | Opponent lower p50 |
| wide-10000 | either-w16-s100 | 16 | 1013 | 10.1300% | 81982.214 | 8098.126 | 0.0988x | Opponent lower p50 |
| wide-10000 | all-w2-s1 | 2 | 8 | 0.0800% | 156.711 | 91.806 | 0.5858x | Opponent lower p50 |
| wide-10000 | all-w2-s10 | 2 | 95 | 0.9500% | 207.115 | 915.364 | 4.4196x | Kiara lower p50 |
| wide-10000 | all-w2-s100 | 2 | 1008 | 10.0800% | 967.266 | 7810.141 | 8.0745x | Kiara lower p50 |
| wide-10000 | all-w4-s1 | 4 | 8 | 0.0800% | 312.522 | 108.308 | 0.3466x | Opponent lower p50 |
| wide-10000 | all-w4-s10 | 4 | 96 | 0.9600% | 429.629 | 939.264 | 2.1862x | Kiara lower p50 |
| wide-10000 | all-w4-s100 | 4 | 1026 | 10.2600% | 2148.962 | 7909.951 | 3.6808x | Kiara lower p50 |
| wide-10000 | all-w8-s1 | 8 | 7 | 0.0700% | 3556.551 | 126.209 | 0.0355x | Opponent lower p50 |
| wide-10000 | all-w8-s10 | 8 | 98 | 0.9800% | 3810.966 | 1032.871 | 0.2710x | Opponent lower p50 |
| wide-10000 | all-w8-s100 | 8 | 1013 | 10.1300% | 7328.109 | 7902.044 | 1.0783x | Kiara lower p50 |
| wide-10000 | all-w12-s1 | 12 | 8 | 0.0800% | 6423.952 | 227.116 | 0.0354x | Opponent lower p50 |
| wide-10000 | all-w12-s10 | 12 | 104 | 1.0400% | 6989.492 | 1163.880 | 0.1665x | Opponent lower p50 |
| wide-10000 | all-w12-s100 | 12 | 1004 | 10.0400% | 14411.204 | 8067.219 | 0.5598x | Opponent lower p50 |
| wide-10000 | all-w16-s1 | 16 | 8 | 0.0800% | 8644.002 | 292.221 | 0.0338x | Opponent lower p50 |
| wide-10000 | all-w16-s10 | 16 | 112 | 1.1200% | 18618.570 | 1312.691 | 0.0705x | Opponent lower p50 |
| wide-10000 | all-w16-s100 | 16 | 1030 | 10.3000% | 106938.092 | 8305.472 | 0.0777x | Opponent lower p50 |
| wide-100000 | scalar-w2-s1 | 2 | 96 | 0.0960% | 34.502 | 1031.376 | 29.8932x | Kiara lower p50 |
| wide-100000 | scalar-w2-s10 | 2 | 970 | 0.9700% | 861.663 | 8968.547 | 10.4084x | Kiara lower p50 |
| wide-100000 | scalar-w2-s100 | 2 | 10002 | 10.0020% | 12863.522 | 78099.949 | 6.0714x | Kiara lower p50 |
| wide-100000 | scalar-w4-s1 | 4 | 104 | 0.1040% | 57329.444 | 1186.683 | 0.0207x | Opponent lower p50 |
| wide-100000 | scalar-w4-s10 | 4 | 980 | 0.9800% | 58775.565 | 9129.475 | 0.1553x | Opponent lower p50 |
| wide-100000 | scalar-w4-s100 | 4 | 9990 | 9.9900% | 74269.659 | 78610.558 | 1.0584x | Kiara lower p50 |
| wide-100000 | scalar-w8-s1 | 8 | 95 | 0.0950% | 102524.565 | 1243.288 | 0.0121x | Opponent lower p50 |
| wide-100000 | scalar-w8-s10 | 8 | 989 | 0.9890% | 171085.671 | 9417.783 | 0.0550x | Opponent lower p50 |
| wide-100000 | scalar-w8-s100 | 8 | 9958 | 9.9580% | 783569.470 | 78197.439 | 0.0998x | Opponent lower p50 |
| wide-100000 | either-w2-s1 | 2 | 94 | 0.0940% | 78.006 | 1040.778 | 13.3423x | Kiara lower p50 |
| wide-100000 | either-w2-s10 | 2 | 963 | 0.9630% | 1556.313 | 8878.256 | 5.7047x | Kiara lower p50 |
| wide-100000 | either-w2-s100 | 2 | 10010 | 10.0100% | 24216.133 | 77928.715 | 3.2180x | Kiara lower p50 |
| wide-100000 | either-w4-s1 | 4 | 98 | 0.0980% | 192.114 | 1187.789 | 6.1827x | Kiara lower p50 |
| wide-100000 | either-w4-s10 | 4 | 970 | 0.9700% | 3474.655 | 9176.550 | 2.6410x | Kiara lower p50 |
| wide-100000 | either-w4-s100 | 4 | 10062 | 10.0620% | 53159.954 | 79207.122 | 1.4900x | Kiara lower p50 |
| wide-100000 | either-w8-s1 | 8 | 94 | 0.0940% | 59448.709 | 1339.599 | 0.0225x | Opponent lower p50 |
| wide-100000 | either-w8-s10 | 8 | 974 | 0.9740% | 66719.490 | 9571.612 | 0.1435x | Opponent lower p50 |
| wide-100000 | either-w8-s100 | 8 | 9988 | 9.9880% | 152745.627 | 78499.462 | 0.5139x | Opponent lower p50 |
| wide-100000 | either-w12-s1 | 12 | 101 | 0.1010% | 94415.978 | 1897.243 | 0.0201x | Opponent lower p50 |
| wide-100000 | either-w12-s10 | 12 | 1009 | 1.0090% | 110913.493 | 10368.574 | 0.0935x | Opponent lower p50 |
| wide-100000 | either-w12-s100 | 12 | 9966 | 9.9660% | 250644.187 | 78120.092 | 0.3117x | Opponent lower p50 |
| wide-100000 | either-w16-s1 | 16 | 103 | 0.1030% | 115248.105 | 2244.459 | 0.0195x | Opponent lower p50 |
| wide-100000 | either-w16-s10 | 16 | 1016 | 1.0160% | 265582.573 | 10899.809 | 0.0410x | Opponent lower p50 |
| wide-100000 | either-w16-s100 | 16 | 9959 | 9.9590% | 1701463.980 | 78887.814 | 0.0464x | Opponent lower p50 |
| wide-100000 | all-w2-s1 | 2 | 94 | 0.0940% | 1932.645 | 1051.876 | 0.5443x | Opponent lower p50 |
| wide-100000 | all-w2-s10 | 2 | 956 | 0.9560% | 3186.337 | 8979.724 | 2.8182x | Kiara lower p50 |
| wide-100000 | all-w2-s100 | 2 | 10019 | 10.0190% | 21753.144 | 77670.662 | 3.5705x | Kiara lower p50 |
| wide-100000 | all-w4-s1 | 4 | 91 | 0.0910% | 6092.151 | 1103.080 | 0.1811x | Opponent lower p50 |
| wide-100000 | all-w4-s10 | 4 | 961 | 0.9610% | 9022.761 | 9111.148 | 1.0098x | Kiara lower p50 |
| wide-100000 | all-w4-s100 | 4 | 10054 | 10.0540% | 52559.810 | 78841.210 | 1.5000x | Kiara lower p50 |
| wide-100000 | all-w8-s1 | 8 | 87 | 0.0870% | 76926.696 | 1269.596 | 0.0165x | Opponent lower p50 |
| wide-100000 | all-w8-s10 | 8 | 953 | 0.9530% | 82351.793 | 9392.604 | 0.1141x | Opponent lower p50 |
| wide-100000 | all-w8-s100 | 8 | 10046 | 10.0460% | 147837.435 | 78476.597 | 0.5308x | Opponent lower p50 |
| wide-100000 | all-w12-s1 | 12 | 98 | 0.0980% | 135045.200 | 1873.336 | 0.0139x | Opponent lower p50 |
| wide-100000 | all-w12-s10 | 12 | 992 | 0.9920% | 146316.148 | 10427.417 | 0.0713x | Opponent lower p50 |
| wide-100000 | all-w12-s100 | 12 | 9992 | 9.9920% | 263909.804 | 78770.854 | 0.2985x | Opponent lower p50 |
| wide-100000 | all-w16-s1 | 16 | 100 | 0.1000% | 174306.534 | 2230.962 | 0.0128x | Opponent lower p50 |
| wide-100000 | all-w16-s10 | 16 | 1006 | 1.0060% | 390069.445 | 10852.570 | 0.0278x | Opponent lower p50 |
| wide-100000 | all-w16-s100 | 16 | 10005 | 10.0050% | 2257266.908 | 79121.581 | 0.0351x | Opponent lower p50 |

## Publication limits

This is single-threaded, warm, in-memory read execution on a shared virtualized Docker host,
not dedicated hardware. It does not establish write throughput, build efficiency, memory
efficiency, disk-backed SQLite performance, transactional durability, concurrency or current
commercial VoltDB performance. The server is legacy AGPL VoltDB CE 10.1.1.
Versions, exact measured executables, platforms, candidate audits, plans, raw samples,
actual result sizes and exclusions are retained in the raw evidence. Use independent
replication and application-representative correlations before drawing broader conclusions.
