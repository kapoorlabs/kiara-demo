# **Kiara DB versus generic-index VoltDB CE: query-execution benchmark**

This separate baseline compares Kiara 2.0.16 with legacy AGPL VoltDB CE 10.1.1.
"Untuned" means **generic indexes retained**, not an index-free database.
Historical tuned and end-to-end evidence is preserved.

[Raw evidence](../benchmarks/voltdb-untuned-comparison.json) |
[Reproduction](../benchmarks/voltdb/README.md)

## Physical design and fixed SQL

- Retain the original primary-key, scalar, scalar-bound, list owner, text, number and
  lower-first interval indexes. Retain the required routing primary key.
- Remove all nine workload-specific record compound indexes, the upper-first list
  interval index, and the additional wide scalar covering index.
- Use exactly one predetermined SQL translation per scenario, without explicit join
  order or measured candidate selection. Historical queries use the original adapter
  compiler. Wide queries use the shared set-intersection translation, falling back to
  the original compiler when no intersection is applicable. This fixed translation
  is **not necessarily the same SQL as generic SQLite** and is not selected by timing.
- Preflight compiles procedures, checks results and records plans; it gathers no timing
  samples. Final plans must match preflight after generated-name normalization.
  Parameterized wide templates are reused with each scenario's actual arguments.
- Precompiled read-only single-partition procedures, replicated benchmark data and a
  partitioned routing table are retained. These deliberately favorable execution
  choices remain configured; "untuned" does not mean all server settings are defaults.

## Timing, protocol and exclusions

Kiara measures query execution plus eager Java result maps. VoltDB measures server-side
voltQueueSQL/voltExecuteSQL and VoltTable creation, excluding transport and client decoding.
Output representations and timing boundaries differ. Builds, SQL compilation, validation
and adapter preparation are excluded. No timer overhead or estimated transport is subtracted.
Both engines are freshly measured; no historical Kiara samples are reused.

Each worker has four CPU quota, 8 GiB memory and a 1-4 GiB Java heap. Workers use JDK 21;
legacy VoltDB uses JDK 11. Server restart precedes every VoltDB trial; engine order alternates,
with seeded scenario shuffles. Warmup is at least ten calls and one second; measurement
uses 15-101 calls and a three-second acquisition budget that the minimum may exceed.
Five trials per engine are the full protocol; one-trial smoke runs are not publication results.
Original retail/catalog/schedule fixtures and separate wide 2/4/8/12/16-field filters are
included according to the dataset inventory below. Wide EITHER/ALL collection predicates
request two distinct values, targeting 0.1%, 1% and 10% selectivity. Width is not isolated
from data type, join count or predicate mix; scalar-only controls stop at eight attributes.

The unchanged native VoltDB SQL timeout can interrupt correctness checks, warmup or sampling.
Such outcomes are QUERY_TIMEOUT with no matches or usable latency; partial samples are
discarded. Client/catalog timeouts and other errors abort, rather than becoming query losses.
Kiara does not have an equivalent native server timeout, so **no symmetric deadline-win
endpoint or timeout-derived speedup is claimed**. Mixed or unanimous query timeouts exclude
the scenario from aggregate latency ratios. Preflight timeouts are recorded but are not
measured trial outcomes. Decimal discrepancies, unsupported text modes, typed-only results
and limited non-equivalent subsets remain disclosed and excluded as in the tuned experiment.

## Results

V/K above one favors Kiara; below one favors VoltDB. Ratios use medians of trial p50s,
then an equal-query geometric mean over scenarios completed comparably in every trial.
The exploratory 95% interval resamples whole trials 5,000 times with the fixed Java seed;
it is not a generalization guarantee. Near-unity differences are not decisive evidence.

| Dataset | Trials per engine | Equivalent queries | V/K aggregate ratio | Exploratory 95% interval | Lower median K / V | VoltDB timeout entries |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| retail-3788 | 5 | 5 | 28.595x | 21.922-31.875x | 5 / 0 | 0 |
| catalog-10000 | 5 | 21 | 1.517x | 1.376-1.615x | 10 / 11 | 0 |
| catalog-100000 | 5 | 21 | 1.139x | 1.067-1.274x | 9 / 12 | 0 |
| schedule-10000 | 5 | 21 | 2.452x | 2.299-2.601x | 13 / 8 | 0 |
| schedule-100000 | 5 | 21 | 1.891x | 1.748-2.001x | 12 / 9 | 0 |
| wide-10000 | 5 | 39 | 15.426x | 15.061-16.787x | 33 / 6 | 0 |
| wide-100000 | 5 | 39 | 10.991x | 10.494-11.341x | 32 / 7 | 0 |

Generated: 2026-10-31T00:50:29.301445200Z.

## Per-query evidence

Times are microseconds, medians of trial p50s/p95s, not pooled percentiles. Any timeout makes the paired latency display n/a; see the raw per-trial outcomes.

### retail-3788

Fixture SHA-256: `04e397cbe48dcf487b9dfbdb8e5e47e587ae6160fa1bd0115af29582525c6234`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| decimal-between-japan | KNOWN_DECIMAL_TRUNCATION / PASS | 33.702 / 314.224 | 61.405 / 420.933 | n/a |
| decimal-lt | KNOWN_DECIMAL_TRUNCATION / PASS | 0.300 / 37.402 | 0.300 / 58.204 | n/a |
| keyword-stem | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-typo | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| markets-all | PASS / PASS | 55.504 / 6917.140 | 95.107 / 8556.650 | 124.624x |
| markets-either | PASS / PASS | 171.013 / 4289.928 | 225.616 / 6138.069 | 25.085x |
| markets-not | PASS / PASS | 249.019 / 26071.961 | 308.323 / 28257.634 | 104.699x |
| one-edit-suggestion | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| prefix-predictions | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| projection | PASS / PASS | 57.204 / 448.334 | 103.507 / 584.044 | 7.837x |
| units-gte | PASS / PASS | 3.100 / 23.102 | 3.801 / 29.203 | 7.452x |
### catalog-10000

Fixture SHA-256: `383a988f19108a3700f55a21303fa6fe4b1c88f1945e175c409b8a0134c27a13`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| and-three | PASS / PASS | 362.827 / 48.603 | 414.931 / 69.304 | 0.134x |
| boolean-sale | PASS / PASS | 115.608 / 357.024 | 151.011 / 440.330 | 3.088x |
| brand-equal | PASS / PASS | 25.001 / 51.904 | 32.302 / 84.006 | 2.076x |
| decimal-equal | KNOWN_DECIMAL_TRUNCATION / PASS | 4.200 / 8.901 | 4.501 / 13.101 | n/a |
| decimal-gte | KNOWN_DECIMAL_TRUNCATION / PASS | 15.001 / 21.902 | 20.202 / 38.103 | n/a |
| decimal-lte | KNOWN_DECIMAL_TRUNCATION / PASS | 56.704 / 65.105 | 77.605 / 100.206 | n/a |
| enum-tier | PASS / PASS | 1056.787 / 862.364 | 1115.780 / 1061.880 | 0.816x |
| keyword-discovery | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-filtered | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-punctuation | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-stem | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-typo | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| limit-20 | PASS / PASS | 1142.886 / 13.901 | 1228.491 / 22.301 | n/a |
| long-views | PASS / PASS | 4.200 / 14.901 | 4.501 / 22.602 | 3.548x |
| one-edit-suggestion | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| prefix-predictions | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| projection | PASS / PASS | 53.904 / 53.804 | 79.806 / 79.205 | 0.998x |
| region-in | PASS / PASS | 970.372 / 1173.687 | 1031.876 / 1313.387 | 1.210x |
| sizes-all | PASS / PASS | 138.211 / 1911.343 | 171.913 / 2066.955 | 13.829x |
| sizes-either | PASS / PASS | 465.436 / 3564.164 | 557.742 / 4428.928 | 7.658x |
| stars-between | PASS / PASS | 1086.785 / 585.639 | 1194.092 / 744.750 | 0.539x |
| stars-gt | PASS / PASS | 514.939 / 242.818 | 609.350 / 323.724 | 0.472x |
| stars-gte | PASS / PASS | 1113.782 / 419.532 | 1243.293 / 515.340 | 0.377x |
| stars-lt | PASS / PASS | 506.937 / 315.124 | 588.344 / 391.830 | 0.622x |
| stars-lte | PASS / PASS | 1086.581 / 611.045 | 1309.199 / 708.753 | 0.562x |
| status-not-equal | PASS / PASS | 1645.834 / 1376.403 | 1715.229 / 1476.210 | 0.836x |
| supplier-known | PASS / PASS | 240.519 / 72.705 | 325.025 / 111.408 | 0.302x |
| supplier-null | PASS / PASS | 168.212 / 179.014 | 214.116 / 234.818 | 1.064x |
| tags-all | PASS / PASS | 54.204 / 11332.746 | 75.606 / 14851.809 | 209.076x |
| tags-either | PASS / PASS | 680.849 / 7264.847 | 747.557 / 11759.886 | 10.670x |
| tags-either-null | PASS / PASS | 33.703 / 624.847 | 56.004 / 722.454 | 18.540x |
| tags-exclude | PASS / PASS | 600482.914 / 32196.328 | 627753.887 / 39442.275 | 0.054x |
| typed-results | PASS / PASS | n/a | n/a | n/a |
### catalog-100000

Fixture SHA-256: `cb4a74653fef3a19dabbff894b67aeb6986e7ad9b271489c68563e3ff6e8c5d4`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| and-three | PASS / PASS | 4168.410 / 482.537 | 6198.460 / 579.246 | 0.116x |
| boolean-sale | PASS / PASS | 1863.123 / 5753.925 | 2103.440 / 10679.683 | 3.088x |
| brand-equal | PASS / PASS | 526.639 / 550.441 | 586.043 / 627.546 | 1.045x |
| decimal-equal | KNOWN_DECIMAL_TRUNCATION / PASS | 38.002 / 10.500 | 58.204 / 17.901 | n/a |
| decimal-gte | KNOWN_DECIMAL_TRUNCATION / PASS | 296.224 / 218.516 | 344.626 / 288.622 | n/a |
| decimal-lte | KNOWN_DECIMAL_TRUNCATION / PASS | 934.074 / 729.657 | 1026.581 / 848.166 | n/a |
| enum-tier | PASS / PASS | 21657.712 / 21103.276 | 28663.679 / 24570.078 | 0.974x |
| keyword-discovery | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-filtered | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-punctuation | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-stem | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| keyword-typo | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| limit-20 | PASS / PASS | 31575.576 / 17.801 | 36045.671 / 27.202 | n/a |
| long-views | PASS / PASS | 47.903 / 57.305 | 71.306 / 85.107 | 1.196x |
| one-edit-suggestion | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| prefix-predictions | UNSUPPORTED / UNSUPPORTED | n/a | n/a | n/a |
| projection | PASS / PASS | 806.262 / 628.648 | 964.169 / 732.058 | 0.780x |
| region-in | PASS / PASS | 19883.666 / 23161.813 | 24072.653 / 27597.503 | 1.165x |
| sizes-all | PASS / PASS | 1982.943 / 31196.416 | 2244.562 / 38082.432 | 15.732x |
| sizes-either | PASS / PASS | 7401.549 / 70341.296 | 10364.780 / 74356.593 | 9.504x |
| stars-between | PASS / PASS | 26989.685 / 15668.348 | 34310.258 / 20412.295 | 0.581x |
| stars-gt | PASS / PASS | 11324.641 / 2827.116 | 13682.321 / 4056.403 | 0.250x |
| stars-gte | PASS / PASS | 27149.150 / 9508.738 | 34775.570 / 19227.486 | 0.350x |
| stars-lt | PASS / PASS | 11780.165 / 4202.037 | 15647.116 / 8192.591 | 0.357x |
| stars-lte | PASS / PASS | 27613.143 / 15648.857 | 33215.567 / 21458.769 | 0.567x |
| status-not-equal | PASS / PASS | 40303.804 / 21661.544 | 48813.570 / 23433.786 | 0.537x |
| supplier-known | PASS / PASS | 3764.255 / 692.149 | 4129.780 / 773.356 | 0.184x |
| supplier-null | PASS / PASS | 2535.375 / 1820.032 | 2719.202 / 2495.801 | 0.718x |
| tags-all | PASS / PASS | 728.154 / 149860.044 | 787.161 / 155712.673 | 205.808x |
| tags-either | PASS / PASS | 19542.871 / 142479.044 | 22883.089 / 146577.414 | 7.291x |
| tags-either-null | PASS / PASS | 880.464 / 7683.987 | 1007.066 / 10365.001 | 8.727x |
| tags-exclude | PASS / PASS | 14692332.365 / 402365.933 | 14878804.497 / 423320.366 | 0.027x |
| typed-results | PASS / PASS | n/a | n/a | n/a |
### schedule-10000

Fixture SHA-256: `6e5b27872478957ea3152380c0eb705acb26179e3702441d3986a3e6d346575c`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| combined-four-fields | PASS / PASS | 9574.633 / 1412.125 | 10888.334 / 1538.919 | 0.147x |
| date-between | PASS / PASS | 808.462 / 151.612 | 934.373 / 234.319 | 0.188x |
| date-equal | PASS / PASS | 6.601 / 20.501 | 7.701 / 33.002 | 3.106x |
| date-gt | PASS / PASS | 8.101 / 22.102 | 9.901 / 30.503 | 2.728x |
| date-range | PASS / PASS | 158378.213 / 1449.116 | 168579.506 / 1570.021 | 0.009x |
| date-range-list | PASS / PASS | 28.302 / 34935.464 | 36.103 / 50804.498 | 1234.381x |
| date-range-null | PASS / PASS | 709.555 / 279.322 | 847.365 / 350.328 | 0.394x |
| dates-all | PASS / PASS | 9.000 / 6241.595 | 15.501 / 8768.695 | 693.511x |
| dates-either | PASS / PASS | 107.008 / 828.965 | 140.211 / 938.574 | 7.747x |
| datetime-range | PASS / PASS | 313.024 / 766.961 | 367.228 / 842.867 | 2.450x |
| datetime-range-list-all | PASS / PASS | 347.326 / 7982.600 | 405.631 / 10082.457 | 22.983x |
| limit-20 | PASS / PASS | 358.728 / 12.601 | 432.234 / 20.502 | n/a |
| prefix-range | PASS / PASS | 56083.431 / 1007.683 | 66051.326 / 1222.196 | 0.018x |
| range-all | PASS / PASS | 5414.031 / 2350.888 | 5781.160 / 2691.015 | 0.434x |
| range-equal | PASS / PASS | 10.601 / 20.201 | 12.001 / 35.003 | 1.906x |
| range-list-all | PASS / PASS | 764.761 / 30942.166 | 841.768 / 39344.626 | 40.460x |
| range-list-point | PASS / PASS | 793.563 / 11089.501 | 870.666 / 13512.276 | 13.974x |
| range-not | PASS / PASS | 4293.844 / 38842.922 | 6854.937 / 45826.870 | 9.046x |
| range-point | PASS / PASS | 7238.274 / 1447.208 | 7864.715 / 1581.726 | 0.200x |
| timestamp-window | PASS / PASS | 1505.222 / 14.901 | 1581.327 / 20.901 | 0.010x |
| timestamps-all | PASS / PASS | 17.001 / 6377.129 | 27.202 / 7890.126 | 375.103x |
| timestamps-either | PASS / PASS | 91.507 / 777.361 | 127.910 / 860.326 | 8.495x |
| typed-results | PASS / PASS | n/a | n/a | n/a |
### schedule-100000

Fixture SHA-256: `c5665efceaaa727ba5404e8fa9482139c56ea1712b6aa59ce3bb4bcd30bf8d03`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| combined-four-fields | PASS / PASS | 379190.936 / 38823.783 | 392418.982 / 44434.446 | 0.102x |
| date-between | PASS / PASS | 11289.576 / 1466.807 | 13323.718 / 1832.033 | 0.130x |
| date-equal | PASS / PASS | 58.805 / 90.907 | 82.104 / 126.409 | 1.546x |
| date-gt | PASS / PASS | 147.210 / 137.010 | 192.415 / 177.913 | 0.931x |
| date-range | PASS / PASS | 8561052.185 / 37274.068 | 8684047.953 / 44853.751 | 0.004x |
| date-range-list | PASS / PASS | 403.826 / 586700.889 | 461.132 / 624709.918 | 1452.856x |
| date-range-null | PASS / PASS | 18524.226 / 4272.141 | 20783.300 / 7773.720 | 0.231x |
| dates-all | PASS / PASS | 233.413 / 145847.855 | 301.717 / 159145.444 | 624.849x |
| dates-either | PASS / PASS | 1695.613 / 11277.416 | 1792.543 / 17106.127 | 6.651x |
| datetime-range | PASS / PASS | 5550.259 / 10986.721 | 7199.411 / 17512.947 | 1.979x |
| datetime-range-list-all | PASS / PASS | 5846.721 / 129810.540 | 7380.584 / 136261.635 | 22.202x |
| limit-20 | PASS / PASS | 4465.647 / 15.201 | 6001.566 / 25.201 | n/a |
| prefix-range | PASS / PASS | 1169948.601 / 25551.436 | 1193805.918 / 29454.022 | 0.022x |
| range-all | PASS / PASS | 77274.017 / 63587.068 | 91373.966 / 70311.158 | 0.823x |
| range-equal | PASS / PASS | 159.513 / 167.213 | 225.117 / 226.918 | 1.048x |
| range-list-all | PASS / PASS | 17189.607 / 415066.493 | 24689.118 / 440272.101 | 24.146x |
| range-list-point | PASS / PASS | 9024.239 / 173937.766 | 11919.273 / 213167.414 | 19.275x |
| range-not | PASS / PASS | 79901.469 / 497174.438 | 88726.992 / 521636.202 | 6.222x |
| range-point | PASS / PASS | 116940.301 / 34420.212 | 127441.812 / 41712.848 | 0.294x |
| timestamp-window | PASS / PASS | 25762.817 / 37.203 | 28591.833 / 57.804 | 0.001x |
| timestamps-all | PASS / PASS | 218.716 / 143940.923 | 286.320 / 151502.594 | 658.118x |
| timestamps-either | PASS / PASS | 1405.988 / 11666.253 | 1613.726 / 20193.236 | 8.298x |
| typed-results | PASS / PASS | n/a | n/a | n/a |
### wide-10000

Fixture SHA-256: `52e1ad2c56a24e4978cc48a97f8b7af4bc64afec82ed3315f1d57139d2439f93`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| all-w12-s1 | PASS / PASS | 6226.122 / 197145.569 | 6759.348 / 247579.090 | 31.664x |
| all-w12-s10 | PASS / PASS | 7218.713 / 188539.549 | 9079.121 / 231006.488 | 26.118x |
| all-w12-s100 | PASS / PASS | 14622.491 / 200091.020 | 17438.367 / 258426.918 | 13.684x |
| all-w16-s1 | PASS / PASS | 8216.331 / 260586.661 | 10383.085 / 351127.385 | 31.716x |
| all-w16-s10 | PASS / PASS | 18443.358 / 264135.720 | 21061.587 / 333224.713 | 14.321x |
| all-w16-s100 | PASS / PASS | 108254.100 / 278172.088 | 113082.938 / 356660.803 | 2.570x |
| all-w2-s1 | PASS / PASS | 152.210 / 19577.631 | 183.712 / 24391.109 | 128.623x |
| all-w2-s10 | PASS / PASS | 205.913 / 20775.672 | 251.722 / 25689.185 | 100.895x |
| all-w2-s100 | PASS / PASS | 980.577 / 20859.843 | 1072.584 / 29350.051 | 21.273x |
| all-w4-s1 | PASS / PASS | 304.020 / 40084.739 | 350.323 / 47298.115 | 131.849x |
| all-w4-s10 | PASS / PASS | 413.527 / 40609.662 | 479.332 / 47644.158 | 98.203x |
| all-w4-s100 | PASS / PASS | 2130.842 / 44235.032 | 2357.758 / 57908.611 | 20.759x |
| all-w8-s1 | PASS / PASS | 3503.636 / 79797.559 | 3772.553 / 96202.499 | 22.776x |
| all-w8-s10 | PASS / PASS | 3816.249 / 83261.686 | 4897.934 / 99752.641 | 21.818x |
| all-w8-s100 | PASS / PASS | 8073.152 / 86059.624 | 9177.113 / 102501.884 | 10.660x |
| either-w12-s1 | PASS / PASS | 4364.698 / 91243.912 | 4796.715 / 113538.955 | 20.905x |
| either-w12-s10 | PASS / PASS | 5071.039 / 94025.113 | 5479.867 / 104575.444 | 18.542x |
| either-w12-s100 | PASS / PASS | 14827.246 / 99786.029 | 18270.861 / 115940.608 | 6.730x |
| either-w16-s1 | PASS / PASS | 5558.580 / 102603.042 | 9115.923 / 122486.047 | 18.458x |
| either-w16-s10 | PASS / PASS | 14012.647 / 102460.241 | 16386.752 / 119938.045 | 7.312x |
| either-w16-s100 | PASS / PASS | 83709.339 / 107849.229 | 87566.759 / 131100.987 | 1.288x |
| either-w2-s1 | PASS / PASS | 3.500 / 15920.049 | 4.900 / 19724.819 | 4548.585x |
| either-w2-s10 | PASS / PASS | 50.904 / 16719.720 | 80.306 / 24422.091 | 328.456x |
| either-w2-s100 | PASS / PASS | 1140.372 / 19103.355 | 1240.579 / 26284.328 | 16.752x |
| either-w4-s1 | PASS / PASS | 6.100 / 32902.440 | 7.101 / 42908.491 | 5393.843x |
| either-w4-s10 | PASS / PASS | 132.509 / 32602.761 | 190.513 / 38725.829 | 246.042x |
| either-w4-s100 | PASS / PASS | 2373.058 / 37151.014 | 2672.269 / 47776.622 | 15.655x |
| either-w8-s1 | PASS / PASS | 2809.188 / 70786.912 | 2953.594 / 81607.912 | 25.198x |
| either-w8-s10 | PASS / PASS | 3189.950 / 69067.653 | 3359.264 / 83857.929 | 21.652x |
| either-w8-s100 | PASS / PASS | 7479.406 / 69955.883 | 9231.023 / 80542.977 | 9.353x |
| scalar-w2-s1 | PASS / PASS | 2.400 / 735.751 | 2.600 / 835.259 | 306.563x |
| scalar-w2-s10 | PASS / PASS | 28.502 / 750.648 | 41.302 / 851.952 | 26.337x |
| scalar-w2-s100 | PASS / PASS | 642.444 / 846.557 | 724.247 / 980.463 | 1.318x |
| scalar-w4-s1 | PASS / PASS | 2752.781 / 1357.094 | 2882.499 / 1493.403 | 0.493x |
| scalar-w4-s10 | PASS / PASS | 2803.161 / 1473.495 | 2955.297 / 1698.109 | 0.526x |
| scalar-w4-s100 | PASS / PASS | 3562.336 / 1506.395 | 3876.776 / 1700.305 | 0.423x |
| scalar-w8-s1 | PASS / PASS | 4611.281 / 1481.987 | 5007.728 / 1672.997 | 0.321x |
| scalar-w8-s10 | PASS / PASS | 7634.700 / 1513.099 | 8474.266 / 1650.008 | 0.198x |
| scalar-w8-s100 | PASS / PASS | 37788.187 / 1790.917 | 41478.931 / 1893.724 | 0.047x |
### wide-100000

Fixture SHA-256: `bd9be3676ca89f216ea0a03a52605659554972a6291f4e3cdc00e5f83a701387`.

| Query | Status K / V across trials | p50 us K / V | p95 us K / V | V/K |
| --- | --- | ---: | ---: | ---: |
| all-w12-s1 | PASS / PASS | 133420.288 / 2783722.047 | 150720.589 / 3160098.384 | 20.864x |
| all-w12-s10 | PASS / PASS | 145314.688 / 2797087.018 | 170246.614 / 2963839.682 | 19.248x |
| all-w12-s100 | PASS / PASS | 259233.678 / 2944927.270 | 268288.545 / 3202711.867 | 11.360x |
| all-w16-s1 | PASS / PASS | 163694.175 / 3841631.137 | 188003.722 / 4080540.727 | 23.468x |
| all-w16-s10 | PASS / PASS | 352217.555 / 3895127.054 | 422909.452 / 4119142.832 | 11.059x |
| all-w16-s100 | PASS / PASS | 2177741.605 / 4084071.809 | 2286821.695 / 4360986.870 | 1.875x |
| all-w2-s1 | PASS / PASS | 1812.416 / 326609.685 | 3071.314 / 392382.354 | 180.207x |
| all-w2-s10 | PASS / PASS | 4327.421 / 320828.833 | 5902.279 / 399535.619 | 74.139x |
| all-w2-s100 | PASS / PASS | 21680.455 / 353060.453 | 26275.763 / 407632.310 | 16.285x |
| all-w4-s1 | PASS / PASS | 4347.431 / 607263.990 | 6224.128 / 726650.467 | 139.683x |
| all-w4-s10 | PASS / PASS | 8626.296 / 619550.743 | 11203.373 / 749258.779 | 71.821x |
| all-w4-s100 | PASS / PASS | 49987.672 / 660336.882 | 57675.123 / 790469.770 | 13.210x |
| all-w8-s1 | PASS / PASS | 74028.729 / 1170581.294 | 83622.867 / 1349185.902 | 15.813x |
| all-w8-s10 | PASS / PASS | 79556.639 / 1181010.748 | 84878.468 / 1405225.640 | 14.845x |
| all-w8-s100 | PASS / PASS | 150256.624 / 1249991.622 | 165531.109 / 1392750.700 | 8.319x |
| either-w12-s1 | PASS / PASS | 92814.540 / 1295225.103 | 103826.728 / 1426862.426 | 13.955x |
| either-w12-s10 | PASS / PASS | 108246.487 / 1282337.610 | 119046.352 / 1474728.734 | 11.846x |
| either-w12-s100 | PASS / PASS | 264515.979 / 1376613.964 | 292114.858 / 1631542.013 | 5.204x |
| either-w16-s1 | PASS / PASS | 110191.816 / 1432920.814 | 123550.210 / 1541614.222 | 13.004x |
| either-w16-s10 | PASS / PASS | 257953.029 / 1484290.228 | 291716.165 / 1667289.446 | 5.754x |
| either-w16-s100 | PASS / PASS | 1655692.685 / 1546007.605 | 1862952.021 / 1813978.177 | 0.934x |
| either-w2-s1 | PASS / PASS | 82.405 / 278053.210 | 129.008 / 307246.374 | 3374.227x |
| either-w2-s10 | PASS / PASS | 1574.293 / 289568.668 | 1666.212 / 322851.258 | 183.936x |
| either-w2-s100 | PASS / PASS | 23948.015 / 302837.524 | 28951.368 / 340744.194 | 12.646x |
| either-w4-s1 | PASS / PASS | 205.814 / 512993.082 | 258.919 / 600691.890 | 2492.508x |
| either-w4-s10 | PASS / PASS | 3496.233 / 523044.163 | 3886.882 / 603752.859 | 149.602x |
| either-w4-s100 | PASS / PASS | 53441.892 / 542466.478 | 61537.189 / 645299.265 | 10.151x |
| either-w8-s1 | PASS / PASS | 58797.234 / 956995.732 | 67915.900 / 1066625.383 | 16.276x |
| either-w8-s10 | PASS / PASS | 67510.952 / 962194.281 | 73628.360 / 1090470.886 | 14.252x |
| either-w8-s100 | PASS / PASS | 151940.312 / 994211.664 | 161889.784 / 1098647.523 | 6.543x |
| scalar-w2-s1 | PASS / PASS | 34.802 / 12637.066 | 56.404 / 14258.891 | 363.113x |
| scalar-w2-s10 | PASS / PASS | 803.954 / 13939.694 | 872.664 / 16312.617 | 17.339x |
| scalar-w2-s100 | PASS / PASS | 12630.347 / 15905.355 | 15399.070 / 18095.437 | 1.259x |
| scalar-w4-s1 | PASS / PASS | 57827.339 / 17784.530 | 64619.608 / 19360.359 | 0.308x |
| scalar-w4-s10 | PASS / PASS | 60137.403 / 18768.701 | 65911.096 / 20038.890 | 0.312x |
| scalar-w4-s100 | PASS / PASS | 72634.590 / 20595.000 | 81488.182 / 22401.422 | 0.284x |
| scalar-w8-s1 | PASS / PASS | 99233.805 / 17712.302 | 112465.231 / 18819.464 | 0.178x |
| scalar-w8-s10 | PASS / PASS | 163383.071 / 18318.616 | 175446.611 / 19022.031 | 0.112x |
| scalar-w8-s100 | PASS / PASS | 765937.884 / 20854.191 | 862547.605 / 22381.337 | 0.027x |

## Interpretation limits

Results describe the specified fixtures, versions, generic physical design and adapters,
not universal database superiority. The baseline measures warm single-reader queries, not
writes, indexing efficiency, disk-backed reads, durability, concurrency, HA or distributed
scalability. The shared host and legacy JDK difference are confounders. Repeat on dedicated
hardware and review native plans before broader publication claims. Incomplete runs are not
completed comparisons; smoke measurements must not be cited as full-size results.
