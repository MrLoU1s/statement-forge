# PERFORMANCE.md — the investigation report

> Every number in this file was measured on my machine against the seeded dataset
> (see *Dataset* below) and is regenerable: `docker compose down -v && docker compose up`
> rebuilds the exact same database (deterministic seed). Raw captures live in
> [`docs/evidence/`](docs/evidence/). Format per investigation:
> **Symptom → Hypothesis → Measurement → Fix → Result → What I learned.**

## Summary

| # | Investigation | Before | After | Evidence |
|---|---|---|---|---|
| 1 | N+1 in statement generation | 2,003 queries / 207.5s (1k accts) | `[M]` queries / `[Y]s` | [§2](#2-n1) |
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

**Symptom.** `StatementGenerationService` (`strategy=NAIVE`) loads the first `limitAccounts`
accounts ordered by id, then for **each account** calls `account.getTransactions()` — a
`LAZY` `@OneToMany` — filters the returned collection to the requested period in Java,
sums debits/credits/closing balance in Java, and writes one `statement` row at a time via
`statementRepository.save(...)`. The whole run is one `@Transactional` method (fits the
plan's "wrap in one transaction"). `open-in-view: false` means this lazy access has to
happen *inside* that transaction — if it were left to the (absent) view layer it would
throw `LazyInitializationException` instead of quietly firing a query, which is exactly
why that setting is on: it forces every lazy touch to be visible in the service layer
where the SQL log is being watched, not hidden behind a web-tier session.

**Hypothesis.** `transaction(account_id, …)` has no index yet (V1 is PK-only) — every
`getTransactions()` call is a full sequential scan of a 5,000,000-row table, once per
account.

**Measurement.** `POST /api/statement-runs?period=2025-03&strategy=NAIVE&limitAccounts=1000`,
run twice (cold, then warm) against the seeded dataset:

| Run | elapsedMs | statements/sec | jdbcQueryCount |
|---|---|---|---|
| 1 (cold) | 244,465 ms (**244.5 s**) | 4.09 | 2,003 |
| 2 (warm) | 207,538 ms (**207.5 s**) | 4.82 | 2,003 |

Warm-run response:
```json
{"runId":2,"strategy":"NAIVE","accounts":1000,"statementsWritten":1000,"elapsedMs":207538,"statementsPerSec":4.818394703620542,"jdbcQueryCount":2003}
```

`jdbcQueryCount` (Hibernate `Statistics.getPrepareStatementCount()`, cleared per run) is
**exactly** 2,003 = 1 accounts `SELECT` + 1,000 lazy `transaction` `SELECT`s (one per
account) + 1,000 `statement` `INSERT`s (row-by-row, `IDENTITY`) + 1 `statement_run`
`INSERT` + 1 `statement_run` `UPDATE` (RUNNING → COMPLETED) — matches the 1+N prediction
precisely, on both runs. `GET /api/statement-runs/2` confirms the completed run:
```json
{"runId":2,"runDate":"2026-07-11","channel":"BATCH","status":"COMPLETED","version":1,"statementsWritten":1000}
```

SQL debug log excerpt (`org.hibernate.SQL` at DEBUG) — one account's select/insert cycle,
identical shape repeated 1,000 times per run (full capture:
[`docs/evidence/m3-naive-baseline.log`](docs/evidence/m3-naive-baseline.log)):
```
select
    t1_0.account_id, t1_0.id, t1_0.amount, t1_0.description, t1_0.txn_date
from
    transaction t1_0
where
    t1_0.account_id=?
insert into statement (account_id, closing_balance, document_ref, period_end,
    period_start, statement_run_id, total_credits, total_debits) values (?, ?, ?, ?, ?, ?, ?, ?)
select
    t1_0.account_id, t1_0.id, t1_0.amount, t1_0.description, t1_0.txn_date
from
    transaction t1_0
where
    t1_0.account_id=?
... (repeats)
```

**Extrapolation to 200,000 accounts (linear, from the warm run — stated as an estimate,
not measured):** 207.5 s / 1,000 accounts × 200,000 accounts ≈ **41,508 s ≈ 11.5 hours**.
A full naive run at production scale is not merely slow — it is operationally
impossible; that impossibility is itself the finding this milestone set out to produce.
No full-scale run was attempted.

**Fix / Result.** Not yet — this section is the "before." Investigation 1 (§2, M4)
replaces the lazy per-account load with a chunked fetch join.

**What I learned.** The query count collapsing exactly to `1 + N + N + 2` is the cleanest
possible confirmation that `getTransactions()` is the trigger: each element of the `N`
lazy-loads is a *separate* round trip because Hibernate has no way to know in advance
which accounts will be touched — it defers the `SELECT` until the collection is actually
iterated (`account.getTransactions()` in the `for` loop), one query per proxy. On this
laptop that single unindexed lookup against 5M rows costs on the order of 200ms per
account — the real cost driver, more than the query *count* itself; §3 (Investigation 2)
revisits this same access pattern once the composite index exists.

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
