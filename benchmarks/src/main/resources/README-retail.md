# Bundled historical retail projection

Source: Chen, D. (2015), **Online Retail**, UCI Machine Learning Repository,
[DOI: 10.24432/C5BW33](https://doi.org/10.24432/C5BW33).
Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
This is an attributed transformed projection, not the original transaction data.

The CSV is identical to the projection in
[kiara-ts-examples](https://github.com/kapoor-labs/kiara-ts-examples/blob/main/public/data/retail-products.csv).
SHA-256: `08ee2f3bd9316c8f158c0dc78b00e171ee0302b6c3797d0aa1ea259d2f9d7253`.
The Java loader verifies this checksum and the 3,788-product count.

The projection aggregates 525,462 qualifying transactions out of 541,909 source
rows dated December 1, 2010 through December 9, 2011. It retains genuine stock
codes, first included descriptions, minimum/maximum positive historical GBP
prices, transaction-row counts, positive units sold and observed countries.
Cancelled invoices, nonmerchandise codes, returns, adjustments, missing
descriptions, zero prices and nonpositive quantities were excluded.
Customer and invoice identifiers are not included.

For the Java benchmark only, stock code maps to `id`, minimum price to `price`,
units sold to `quantity`, sales-row count to `serial`, observed markets to
`tags`, and lowercase unique title tokens to both `name` (raw dictionary)
and `keywords` (stemmed index). The original title is stored in the unindexed
`description`. Other fields in the shared Java schema are null; no categories,
inventory quantities or live prices are invented. The maximum source price is
not indexed by this benchmark. The file itself is unmodified.
