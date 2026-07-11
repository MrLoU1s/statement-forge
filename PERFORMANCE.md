# PERFORMANCE.md — the investigation report

> Every number in this file was measured on my machine against the seeded dataset
> (see *Dataset* below) and is regenerable: `docker compose down -v && docker compose up`
> rebuilds the exact same database (deterministic seed). Raw captures live in
> [`docs/evidence/`](docs/evidence/). Format per investigation:
> **Symptom → Hypothesis → Measurement → Fix → Result → What I learned.**

## Summary

| # | Investigation | Before | After | Evidence |
|---|---|---|---|---|
| 1 | N+1 in statement generation | `[N]` queries / `[X]s` | `[M]` queries / `[Y]s` | [§2](#2-n1) |
| 2 | Missing composite index | seq scan `[X]ms` | index scan `[Y]ms` | [§3](#3-indexing) |
| 3 | OFFSET deep pagination | `[X]ms` @ page 10k | `[Y]ms` keyset | [§4](#4-pagination) |
| 4 | Row-by-row inserts (IDENTITY) | `[X]` rows/s | `[Y]` rows/s | [§5](#5-batch-inserts) |
| 5 | ORM loop vs set-based native SQL | `[X]s` @ 20k accts | `[Y]s` (full 200k: `[Z]s`) | [§6](#6-orm-vs-native-sql) |
| 6 | Lost update on run status | — | version conflict → 409 | [§7](#7-optimistic-locking) |

## Dataset

Seeded via [`V2__seed_data.sql`](src/main/resources/db/migration/V2__seed_data.sql) —
set-based `generate_series` inserts, deterministic (`SELECT setseed(0.42);` run first),
so `docker compose down -v` followed by a reboot regenerates an identical dataset every
time. Migration applied in **1:43.65s** (5.3M rows total); full app boot **123.9s**.

| Table | Row count |
|---|---|
| `customer` | 100,000 |
| `account` | 200,000 |
| `transaction` | 5,000,000 |
| `comm_template` | 3 |

```sql
SELECT pg_size_pretty(pg_total_relation_size('transaction')) AS transaction_total_size,
       pg_size_pretty(pg_relation_size('transaction'))       AS heap_only,
       pg_size_pretty(pg_database_size('statementforge'))    AS whole_db;
```
```
 transaction_total_size | heap_only | whole_db
------------------------+-----------+----------
 457 MB                 | 350 MB    | 491 MB
```
(`transaction` has no secondary indexes yet — V1 is PK-only — so the ~107 MB gap
between heap-only and total is the primary key's btree index. That gap is the baseline
Investigation 2 (§3) will grow when the composite index lands.)

Distribution sanity (both `0`, confirming the seed's modulo arithmetic is exact, not
approximate):
- every customer has exactly 2 accounts (`account.customer_id`)
- every account has exactly 25 transactions (`transaction.account_id`)
- `txn_date` spans the full `2024-01-01`..`2025-12-31` window (min and max both hit)
- `amount` is close to an even split: 2,501,058 negative / 2,498,940 positive / 2 exactly zero

All of the above was captured **after** the migration's closing `ANALYZE;` — the planner
has fresh, non-stale statistics for every `EXPLAIN` captured in the milestones that follow.

## 1. Baseline: the naive engine

<!-- TODO(M3): response JSON, query count, SQL log excerpt, extrapolation to 200k accounts -->

## 2. N+1

<!-- TODO(M4) -->

## 3. Indexing

<!-- TODO(M5): both EXPLAIN (ANALYZE, BUFFERS) plans verbatim -->

## 4. Pagination

<!-- TODO(M6): latency-vs-depth table + both plans -->

## 5. Batch inserts

<!-- TODO(M7): four-row throughput table, incl. the negative result (batch_size under IDENTITY) -->

## 6. ORM vs native SQL

<!-- TODO(M8) -->

## 7. Optimistic locking

<!-- TODO(M9): conflict demo output + isolation note pointer -->
