# PERFORMANCE.md — the investigation report

> Every number in this file was measured on my machine against the seeded dataset
> (see *Dataset* below) and is regenerable: `docker compose down -v && docker compose up`
> rebuilds the exact same database (deterministic seed). Raw captures live in
> [`docs/evidence/`](docs/evidence/). Format per investigation:
> **Symptom → Hypothesis → Measurement → Fix → Result → What I learned.**

## Summary

| # | Investigation | Before | After | Evidence |
|---|---|---|---|---|
| 1 | N+1 in statement generation | 2,003 queries / 207.5s (1k accts) | 670 queries / 2.49s (667 accts, see §2) | [§2](#2-n1) |
| 2 | Missing composite index | Parallel Seq Scan, 287.9ms, ~44.8k buffers | Index Scan, 0.204ms, 10 buffers | [§3](#3-indexing) |
| 3 | OFFSET deep pagination | 115.1ms DB / 123.2ms API @ depth 10k (offset 500k) | 0.218ms DB / 13.7ms API, flat at any depth | [§4](#4-pagination) |
| 4 | Row-by-row inserts (IDENTITY) | 306.16 rows/s (batch_size=50 alone: 303.72, no change) | 1,164.27 rows/s (SEQUENCE + batch + rewrite) | [§5](#5-batch-inserts) |
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

**Symptom.** §1's baseline: 1,000 lazy `SELECT`s, one per account, because
`getTransactions()` is only ever touched after the account is already loaded — Hibernate
has no way to know in advance which accounts will need their transactions.

**Hypothesis.** Telling Hibernate up front — in the *same* query that loads the accounts
— which associated rows to bring back should collapse the N per-account round trips into
one query per batch. JPA's tool for this is a **fetch join**.

**Fix.** `AccountRepository.findWithTransactionsInPeriod` (chunked id-range, not
`Pageable` — see below):
```java
@Query("select a from Account a join fetch a.transactions t "
        + "where a.id between :fromId and :toId and t.txnDate between :from and :to")
List<Account> findWithTransactionsInPeriod(long fromId, long toId, LocalDate from, LocalDate to);
```
`StatementGenerationService.generateFetchJoin` walks `[1, limitAccounts]` in chunks of
1,000 ids, calling this once per chunk — at `limitAccounts=1000` that's exactly **one**
chunk, one query. Two deliberate deviations from PLAN.md's literal snippet, both
version-checked against current Hibernate docs before writing the code:
1. **No `distinct`.** PLAN.md's snippet has `select distinct a ...`. As of Hibernate 6,
   duplicate root entities from a `join fetch` are deduplicated **in memory** after
   materialization — `distinct` is no longer needed for that purpose and only adds an
   unnecessary SQL-level `DISTINCT` (extra sort/hash work Postgres doesn't need to do).
   Source: Hibernate ORM docs, `querylanguage/Relational.adoc` — *"As of Hibernate 6,
   duplicate results from join fetch are automatically removed in memory... distinct
   should not be used for this purpose."*
2. **Not `Pageable`.** Confirmed against the same docs: *"Fetch joins should typically
   be avoided in limited or paged queries, including those using `setFirstResult()` and
   `setMaxResults()`."* Combining a fetch join with `Pageable`'s `LIMIT`/`OFFSET` makes
   Hibernate fetch the **entire** match set and paginate it **in memory** — the
   `HHH90003004` warning — which defeats the point. The id-range `BETWEEN` chunk is the
   database-level equivalent of pagination that stays safe to combine with a fetch join.

**Measurement.** Same benchmark, `strategy=FETCH_JOIN, limitAccounts=1000`, cold then warm:

| Strategy | jdbcQueryCount | elapsedMs | accounts | statementsWritten | statements/sec |
|---|---|---|---|---|---|
| NAIVE (warm, §1) | 2,003 | 207,538 ms | 1,000 | 1,000 | 4.82 |
| FETCH_JOIN (cold) | 670 | 4,817 ms | 667 | 667 | 138.5 |
| FETCH_JOIN (warm) | 670 | 2,486 ms | 667 | 667 | 268.3 |

670 = 1 chunk query + 667 `statement` `INSERT`s + 1 run `INSERT` + 1 run `UPDATE` —
confirmed by grepping the SQL log (full capture:
[`docs/evidence/m4-fetch-join.log`](docs/evidence/m4-fetch-join.log)). Query count
collapsed **1,000× on the read side** (1,000 lazy `SELECT`s → 1 join query); wall-clock
dropped **~83×** warm-to-warm (207.5s → 2.49s).

```
select
    a1_0.id, a1_0.customer_id, a1_0.opened_at, a1_0.product_type, a1_0.status,
    t1_0.account_id, t1_0.id, t1_0.amount, t1_0.description, t1_0.txn_date
from
    account a1_0
join
    transaction t1_0
        on a1_0.id=t1_0.account_id
where
    a1_0.id between ? and ?
    and t1_0.txn_date between ? and ?
```

**A genuine, measured side-effect — not a bug.** `accounts` dropped from 1,000 to
**667**. `join fetch` on an unqualified `join` is an **inner** join: an account with
zero transactions in March 2025 produces no joined row at all and is silently absent
from the result, so it gets no statement. I did not assume this — I cross-checked it
independently in `psql`:
```sql
SELECT count(DISTINCT account_id) FROM transaction
WHERE account_id BETWEEN 1 AND 1000 AND txn_date BETWEEN '2025-03-01' AND '2025-03-31';
-- 667
```
Exact match. With ~25 transactions per account spread over 24 months (~1.04/month on
average), it's entirely expected that roughly a third of accounts land on zero
transactions in any single specific month. **This is a real coverage gap between the two
strategies, not an artifact of the fix** — see DECISIONS.md D11 for the `left join
fetch` alternative that would close it, and the trade-off of doing so.

**What I learned.** Two separate things happened here and it matters to keep them
apart: (1) the **query count** collapsed because one query with a join replaces N lazy
loads — that's the N+1 fix, and it would hold even if the insert path were still the
bottleneck; (2) the **wall-clock time** also collapsed by ~83×, but that's dominated by
no longer re-scanning the unindexed 5M-row `transaction` table 1,000 times (§1's real
cost driver) — §3 (M5, composite index) attacks that same cost from the other side and
will matter even more once §5 (M8) runs the aggregation over all 200k accounts. Neither
investigation alone would have gotten this baseline to a workable place; they compound.
Fetch join is the right tool here specifically because the read pattern is "load an
account together with its transactions for one bounded query" — for a case that needs a
*flat, decoupled* projection instead (no entity graph, just columns), a DTO projection
would be the stricter/leaner alternative (mentioned, not built, per PLAN.md M4).

## 3. Indexing

**Symptom.** A single-account, date-range lookup — `GET
/api/accounts/{accountId}/transactions?from=&to=`, the read pattern both an on-demand
statement and each of §1/§2's per-account access ultimately boil down to — is slow
against the 5M-row `transaction` table.

**Hypothesis.** V1 put no secondary index on `transaction`; the only index is the
primary key on `id`. A query filtering by `account_id` and `txn_date` has no index to
use and must scan the whole table.

**Measurement — before** (`account_id = 123456`, period `2025-01-01..2025-03-31`,
warm run kept, full plans in
[`docs/evidence/m5-index.log`](docs/evidence/m5-index.log)):
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM transaction
WHERE account_id = 123456 AND txn_date BETWEEN '2025-01-01' AND '2025-03-31';
```
```
 Gather  (cost=1000.00..82287.73 rows=3 width=40) (actual time=69.672..287.755 rows=4 loops=1)
   Workers Planned: 2
   Workers Launched: 2
   Buffers: shared hit=15984 read=28845
   ->  Parallel Seq Scan on transaction  (cost=0.00..81287.43 rows=1 width=40) (actual time=160.101..266.796 rows=1 loops=3)
         Filter: ((txn_date >= '2025-01-01'::date) AND (txn_date <= '2025-03-31'::date) AND (account_id = 123456))
         Rows Removed by Filter: 1666665
         Buffers: shared hit=15984 read=28845
 Planning:
   Buffers: shared hit=80
 Planning Time: 0.656 ms
 Execution Time: 287.881 ms
```
Parallel Seq Scan across 3 workers, ~5,000,000 rows read (`Rows Removed by Filter` ≈
1.67M × 3), ~44,800 buffers touched, 287.9 ms even warm. `curl -w time_total`, warm:
**244.7 ms** end-to-end through the app.

**Fix.** `V3__transaction_account_date_index.sql`:
```sql
CREATE INDEX idx_transaction_account_date ON transaction (account_id, txn_date);
```
`account_id` (equality predicate) leads, `txn_date` (range predicate) trails. A btree is
sorted by the leading column first; within one `account_id` value the rows are then
already ordered by `txn_date`. Equality-first lets Postgres seek directly to one
account's transactions and walk a short, already-date-sorted run for the range check.
The reverse order, `(txn_date, account_id)`, would only let the date range narrow the
leading column — Postgres would still have to filter every date-matching row across all
200,000 accounts for the right `account_id`, i.e. a much larger, unsorted-per-account
scan. Plain `CREATE INDEX`, not `CONCURRENTLY`: Flyway runs each migration inside a
transaction, and `CONCURRENTLY` cannot run inside one — a real production run against a
live table would build it outside Flyway with `CONCURRENTLY` to avoid holding a write
lock on `transaction` for the build's duration (here: 5.111s, empty table locking
concern in dev but not negligible at production write volume). Migration applied
cleanly: `Successfully applied 1 migration ... (execution time 00:05.111s)`.

**Measurement — after** (same query, warm run):
```
 Index Scan using idx_transaction_account_date on transaction  (cost=0.43..16.49 rows=3 width=40) (actual time=0.057..0.118 rows=4 loops=1)
   Index Cond: ((account_id = 123456) AND (txn_date >= '2025-01-01'::date) AND (txn_date <= '2025-03-31'::date))
   Buffers: shared hit=10
 Planning:
   Buffers: shared hit=105
 Planning Time: 0.682 ms
 Execution Time: 0.204 ms
```

| | Before | After | Δ |
|---|---|---|---|
| Plan | Parallel Seq Scan (3 workers) | Index Scan | seq → index |
| Buffers | ~44,829 (15,984 hit + 28,845 read) | 10 (all hits, 0 reads) | ~4,483× fewer |
| DB execution time (warm) | 287.881 ms | 0.204 ms | ~1,411× |
| API latency, `curl` (warm) | 244.7 ms | 17.9 ms | ~13.7× |

The DB-side improvement (~1,411×) is much larger than the end-to-end API improvement
(~13.7×) because once the query itself drops to sub-millisecond, the HTTP round trip,
connection acquisition, and JSON serialization — previously negligible next to a
287.9 ms query — become the dominant cost. That gap is itself a small lesson: past a
certain point, the database stops being the bottleneck and the rest of the stack takes
over.

**Bonus — investigation interaction.** Re-ran §1 (NAIVE) and §2 (FETCH_JOIN) at
`limitAccounts=1000` after V3 landed, no code changes:

| Strategy | Before V3 | After V3 | Query count (unchanged) |
|---|---|---|---|
| NAIVE | 207,538 ms | **6,853 ms** (~30×) | 2,003 |
| FETCH_JOIN | 2,486 ms | **2,073 ms** (~1.2×) | 670 |

NAIVE's *query count* didn't change — it's still 1,000 separate lazy-load round trips,
§4/M4's variable, not this one — but each of those 1,000 queries now hits the index
instead of re-scanning 5M rows, so the *cost per query* collapsed and the whole run got
~30× faster from this fix alone. FETCH_JOIN barely moved: its single chunked query
(`WHERE a.id BETWEEN ... AND t.txn_date BETWEEN ...`) was already a fairly bounded scan
before the index existed, so there was less seq-scan cost to remove. This is the clean
illustration that §1/§2 (query *count*) and §3 (query *cost*) are independent levers —
neither alone would get the naive baseline to a workable place, and they compound.

**What I learned.** The planner didn't choose a seq scan out of a bad decision — before
V3, a seq scan genuinely was the only available access path; there was no index to use.
Adding one didn't just make the *existing* plan faster, it made a **different, cheaper
plan possible at all** — that's the real mechanism behind "add an index," not "make
queries faster" in the abstract. The `Buffers: shared hit=10` in the after-plan (zero
`read`) also shows the whole answer now lives in a handful of index pages, small enough
to stay resident — a stark contrast to the before-plan's ~28,845 buffers read from disk
per query. Cost of this fix, for balance: every index is also write amplification and
storage — this index has to be maintained on every `INSERT`/`UPDATE`/`DELETE` touching
`transaction`, which matters directly for §5/M7 (bulk inserts) and is why V1 shipped
with no indexes at all rather than "just in case" ones. An index-only scan (avoiding the
heap fetch entirely) would need `amount`/`description` in an `INCLUDE (...)` clause if
those columns were also read-hot without being part of the search predicate.

## 4. Pagination

**Symptom.** `GET /api/transactions?page=&size=` — a plain "give me page N of the
transaction table" listing — needs to stay usable at any depth into a 5,000,000-row
table, not just near page 1.

**Hypothesis.** `ORDER BY id LIMIT :size OFFSET :page*size` should get slower as `page`
grows, because `OFFSET` is a purely *positional* skip: to return "row 500,001 through
500,050" the executor has to walk past the first 500,000 matching rows first — it has
no way to seek directly to a position, only to a value. A keyset query —
`WHERE id > :afterId ORDER BY id LIMIT :size` — reframes the same request as "give me
everything after value X," which a btree condition *can* seek to directly, so its cost
should stay flat regardless of how deep `afterId` is.

**Fix.** Two endpoints, both on `TransactionRepository`, dispatched from the same
`/api/transactions` path via Spring MVC's `params` mapping condition
(`params = "page"` vs `params = "afterId"` on `@GetMapping`) — matching the literal URLs
PLAN.md specifies without an ambiguous route:
```java
// OFFSET — Slice<T>, not Page<T>: fetches size+1 rows to derive hasNext,
// no separate count(*) query (see mini-finding below).
@Query("select new ...TransactionDto(t.id, t.txnDate, t.amount, t.description) "
        + "from Transaction t order by t.id asc")
Slice<TransactionDto> findAllByOrderByIdOffset(Pageable pageable);

// Keyset — id is both the total order and the cursor: one unique,
// monotonically increasing column needs no tie-breaker.
@Query("select new ...TransactionDto(t.id, t.txnDate, t.amount, t.description) "
        + "from Transaction t where t.id > :afterId order by t.id asc")
List<TransactionDto> findAfterId(@Param("afterId") long afterId, Pageable pageable);
```
`TransactionPageController` builds `PageRequest.of(page, size)` for the OFFSET path
(Spring Data computes `offset = page * size`) and `PageRequest.ofSize(size)` for the
keyset path; the keyset response's `nextCursor` is simply the last row's `id`, or `null`
once a page comes back short.

**Measurement — DB-level, both at the same logical position** (row 500,001 onward,
`size=50`; full plans in
[`docs/evidence/m6-pagination.log`](docs/evidence/m6-pagination.log)):
```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM transaction ORDER BY id LIMIT 50 OFFSET 500000;
```
```
 Limit  (cost=17468.43..17470.18 rows=50 width=40) (actual time=115.013..115.042 rows=50 loops=1)
   Buffers: shared hit=7163
   ->  Index Scan using transaction_pkey on transaction  (cost=0.43..174680.43 rows=5000000 width=40) (actual time=0.047..86.648 rows=500050 loops=1)
         Buffers: shared hit=7163
 Planning Time: 0.582 ms
 Execution Time: 115.148 ms
```
```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM transaction WHERE id > 500000 ORDER BY id LIMIT 50;
```
```
 Limit  (cost=0.43..2.30 rows=50 width=40) (actual time=0.084..0.099 rows=50 loops=1)
   Buffers: shared hit=8
   ->  Index Scan using transaction_pkey on transaction  (cost=0.43..168005.78 rows=4487677 width=40) (actual time=0.083..0.092 rows=50 loops=1)
         Index Cond: (id > 500000)
         Buffers: shared hit=8
 Planning Time: 0.903 ms
 Execution Time: 0.218 ms
```

**The interesting detail: both plans use the same index (`transaction_pkey`), no new
index was added.** `ORDER BY id` already matches the primary key's natural order, so the
planner picks `Index Scan using transaction_pkey` for *both* queries — this is already
the best access path available for either. The OFFSET plan's `rows=500050` (`actual
... rows=500050 loops=1`) is the tell: even walking a perfectly-ordered index, Postgres
still visits and discards 500,000 rows before it can return the 50 it was asked for.
The keyset plan's `Index Cond: (id > 500000)` is a direct seek — no rows visited before
the first one returned. Same index, same table, same row count returned; ~528× the
execution time and ~895× the buffers for OFFSET, purely from *where in the table* the
answer needs to come from.

**Measurement — API latency vs. depth** (`scripts/pagination-bench.ps1`, `size=50`,
warm runs, two independent passes for reproducibility; keyset depth is approximated by
jumping straight to `afterId = depth × size` rather than walking the cursor
`depth` times — PLAN.md's sanctioned shortcut, since `WHERE id > :afterId` costs the
same regardless of how that `id` value was obtained; full CSV in
[`docs/evidence/m6-pagination-bench.csv`](docs/evidence/m6-pagination-bench.csv)):

| depth (pages) | OFFSET ms (run 1) | OFFSET ms (run 2) | keyset ms (run 1) | keyset ms (run 2) |
|---|---|---|---|---|
| 1 | 19.11 | 12.77 | 19.00 | 13.68 |
| 10 | 14.35 | 14.25 | 14.18 | 12.29 |
| 100 | 18.20 | 11.77 | 13.34 | 12.38 |
| 1,000 | 23.53 | 21.58 | 13.70 | 13.77 |
| 10,000 | **123.16** | **112.32** | **13.74** | **13.06** |

OFFSET grows with depth (~19ms → ~118ms across two orders of magnitude, both runs);
keyset stays flat (~13–14ms regardless of depth) — the DB-level gap (528×) shrinks to
~9× end-to-end because, same as §3, once the query itself is sub-millisecond, HTTP
round-trip and JSON serialization become the floor. At `size=50` and 5M rows the
absolute OFFSET numbers are still small (double-digit-to-triple-digit ms, not seconds) —
the shape of the curve, not the absolute value, is the finding; a bigger table or a
smaller page size would make the same O(offset) growth land at seconds instead of
milliseconds.

**Mini-finding: the `count(*)` tax a `Page<T>` return type would have added.** PLAN.md
flags this explicitly — I used `Slice<T>` specifically to avoid it, but measured what
avoiding it actually saved rather than asserting it:
```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM transaction;
```
```
 Finalize Aggregate  (actual time=388.647..395.983 rows=1 loops=1)
   Buffers: shared hit=14314 read=30515
   ->  Gather  (actual time=388.426..395.968 rows=3 loops=1)
         ->  Partial Aggregate (actual time=380.446..380.448 rows=1 loops=3)
               ->  Parallel Seq Scan on transaction (actual time=0.031..244.781 rows=1666667 loops=3)
 Execution Time: 396.292 ms
```
`count(*)` can't be answered from an index alone under MVCC (visibility has to be
checked row-by-row), so it's a full parallel sequential scan every time — **396ms warm**,
independent of `page`/`size`/`afterId`. Had I used `Page<TransactionDto>` instead of
`Slice<TransactionDto>`, this ~396ms would be added to the top of *every single request*
to either endpoint, dwarfing even the worst OFFSET number measured above (123ms) and
completely swamping the keyset endpoint's ~13ms. `Slice` avoids it entirely by fetching
`size + 1` rows and deriving `hasNext` from whether the extra row came back — no
aggregate query at all.

**What I learned.** OFFSET's cost is about *position*, not content, and — the part I
didn't expect going in — a perfect index doesn't fix it. `(account_id, txn_date)` in §3
made a *cheaper plan possible*; here, the *same* index already backs both queries, and
the plan for OFFSET is still the best one available — it's just that "the 500,001st row
in id order" isn't a value a btree condition can express, only a position the executor
has to walk to by counting. Keyset works by reframing the request from a position
(`OFFSET n`) into a value (`WHERE id > x`), which *is* something a btree can seek to.
The trade-off is real, not free: keyset can't jump to an arbitrary page number — only
"give me the next batch after this cursor" — which is why cursor-token APIs (GitHub,
Stripe, Slack) universally give up random page-jump access in exchange for flat cost at
any depth. For a UI that genuinely needs "jump to page 7,000" there's no way around
paying the OFFSET cost (or maintaining a separate position index, out of scope here);
for infinite-scroll/next-batch consumption — which is what most deep-pagination API
consumers actually do — keyset is strictly better and this measurement is why.

## 5. Batch inserts

**Symptom.** Every strategy so far (`NAIVE`, `FETCH_JOIN`) writes `statement` rows one at a time —
`statementRepository.save(...)` inside a loop, one JDBC round trip per row. At §1/§2's scale
(`limitAccounts=1000`) that cost was hidden behind the N+1 read-side problem; §2's own measurement
already flagged it once the read side was fixed ("insert time still dominates — say so honestly").
This investigation isolates that one remaining variable: the write path.

**Hypothesis.** JDBC supports batching multiple statements into fewer round trips
(`PreparedStatement.addBatch()`/`executeBatch()`), and Hibernate exposes this via
`hibernate.jdbc.batch_size`. Turning it on should collapse thousands of individual `INSERT` round
trips into a much smaller number of batch executions.

Three measured stages, same benchmark (`POST
/api/statement-runs?period=2025-03&strategy=...&limitAccounts=20000` — 10× §1/§2's scale so
insert-side cost dominates the measurement), same warm-run discipline, full capture in
[`docs/evidence/m7-batch-inserts.log`](docs/evidence/m7-batch-inserts.log). At this scale, 13,303 of
the 20,000 requested accounts have a March-2025 transaction and get a statement — 66.5% coverage,
matching §2's 667/1,000 = 66.7% at 10× the account count, the same inner-join account-drop behavior
(D11) holding steady with scale.

### Stage A — before: row-by-row `save()`, `IDENTITY`, no batch config

| Run | statements | elapsedMs | statements/sec | jdbcQueryCount |
|---|---|---|---|---|
| 1 | 13,303 | 47,108 ms | 282.39 | 13,325 |
| 2 (warm) | 13,303 | 43,451 ms | 306.16 | 13,325 |

`jdbcQueryCount` = 13,325 = 20 chunked fetch-join `SELECT`s (id-range chunks of 1,000, unchanged from
§2) + 13,303 single-row `statement` `INSERT`s + 1 run `INSERT` + 1 run `UPDATE` — every insert is
still its own round trip.

### Stage B — the gotcha, measured (negative result)

**Fix attempted.** Set *only* `spring.jpa.properties.hibernate.jdbc.batch_size: 50` and
`hibernate.order_inserts: true` in `application.yaml`. Nothing else changed — `Statement.id` is still
`GenerationType.IDENTITY`. Restarted the app; the boot log confirms the setting loaded:
```
org.hibernate.orm.jdbc.batch : HHH100501: Automatic JDBC statement batching enabled (maximum batch size 50)
```

**Expected result — read from Hibernate's own docs before writing any code, not assumed:**
> Hibernate disables insert batching at the JDBC level transparently if you use an identity
> identifier generator.
— hibernate-orm user guide, *Batching* chapter (`documentation/.../chapters/batch/Batching.adoc`).

**Measured** — same benchmark, same scale:

| Run | statements | elapsedMs | statements/sec | jdbcQueryCount |
|---|---|---|---|---|
| 1 | 13,303 | 45,658 ms | 291.36 | 13,325 |
| 2 (warm) | 13,303 | 43,800 ms | 303.72 | 13,325 |

Warm-to-warm against Stage A: 306.16 → 303.72 stmt/s, a **-0.8% change** — noise, not a regression
(Stage A's own cold→warm swing was +8.4%, several times larger than this entire delta). `jdbcQueryCount`
is **identical**, 13,325, in both stages: the config change didn't even alter query *count*, only
*(attempted)* execution grouping — and that grouping never engaged. This is the negative result the
investigation exists to produce: `hibernate.jdbc.batch_size` does nothing here, silently, with no
warning or exception. The boot log's "batching enabled" is true in general and irrelevant for this
specific entity's inserts.

### Stage C — the fix

`Statement.id` moves from `IDENTITY` to a pooled `SEQUENCE` — `V4__statement_id_sequence.sql`:
```sql
ALTER TABLE statement ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE statement_id_seq INCREMENT BY 50 OWNED BY statement.id;
SELECT setval('statement_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM statement));
```
```java
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "statement_seq")
@SequenceGenerator(name = "statement_seq", sequenceName = "statement_id_seq", allocationSize = 50)
private Long id;
```
`allocationSize = 50` matches `INCREMENT BY 50` exactly: Hibernate's pooled sequence optimizer calls
`nextval()` **once** to claim a block of 50 ids, hands them out client-side for the next 50 entities,
and only calls `nextval()` again once that block is exhausted — a run of inserts needs no per-row
round trip to learn its id, unlike `IDENTITY`, which can only ever learn its id from the database
*after* that specific row is inserted (the mechanical reason batching and `IDENTITY` are incompatible).
Migration applied cleanly against a table that already had 58,213 `IDENTITY`-generated rows from every
prior run (M3/M4/Stage A/B): `setval` seeded the sequence past the existing max, confirmed by the
migration log (`docs/evidence/m7-batch-inserts.log`) — no id collisions.

Two more pieces:
- `application.yaml` datasource URL gains `?reWriteBatchedInserts=true` — a **pgjdbc driver-level**
  optimization, independent of Hibernate: it rewrites a JDBC batch of same-shaped
  `INSERT ... VALUES (?, ...)` calls into one `INSERT ... VALUES (?, ...), (?, ...), ...` statement,
  cutting network round trips further within a single `executeBatch()`.
- `StatementGenerationService.generateBatched`: the same chunked fetch-join **read** path as §2's
  `generateFetchJoin` (the read side is not this investigation's variable, deliberately unchanged); the
  **write** side accumulates `Statement` entities into a list and calls
  `statementRepository.saveAll(...)` + `entityManager.flush()` + `entityManager.clear()` every 1,000
  entities — `flush()` forces the accumulated inserts to execute as real batches now, `clear()`
  detaches everything from the persistence context so it can't grow unbounded across a
  20,000-account run (PLAN.md gotcha #11).

**Verified batching is real**, not just "enabled" — `logging.level.org.hibernate.orm.jdbc.batch: TRACE`:
```
Created JDBC batch (50) - [com.muiyurocodes.statementforge.domain.Statement#INSERT]
Adding to JDBC batch (1 / 50) - [com.muiyurocodes.statementforge.domain.Statement#INSERT]
... (48 more) ...
Executing JDBC batch (50 / 50) - [com.muiyurocodes.statementforge.domain.Statement#INSERT]
```
repeated 266 times per run, closing with one partial batch for the 13,303rd row and the final run-status
update:
```
Executing JDBC batch (3 / 50) - [com.muiyurocodes.statementforge.domain.Statement#INSERT]
Executing JDBC batch (1 / 50) - [com.muiyurocodes.statementforge.domain.StatementRun#UPDATE]
```
**Correction to PLAN.md, recorded honestly:** the plan's expected log text was `"Executing batch size:
50"`; Hibernate 7.4.1.Final's real message is `"Executing JDBC batch (50 / 50) - [Entity#OPERATION]"`
— close in spirit, different in exact wording. Worth stating precisely rather than assuming the plan's
paraphrase was verbatim, same discipline as D11's `distinct` check. Small bonus finding embedded in
that same log: the final `StatementRun` status `UPDATE` goes through the identical batch machinery as a
batch-of-one, which is why the per-run "Executing JDBC batch" line count is 268, not the 267 the
`Statement` inserts alone would produce (13 full 1,000-entity flushes × 20 batches of 50 = 260, + one
closing flush of 303 = 6 batches of 50 + 1 of 3 = 267, + the run update = 268); grepped count across
both runs: 536 = 268 × 2, exact.

**Measured:**

| Run | statements | elapsedMs | statements/sec | jdbcQueryCount |
|---|---|---|---|---|
| 1 | 13,303 | 12,302 ms | 1,081.37 | 304 |
| 2 (warm) | 13,303 | 11,426 ms | 1,164.27 | 303 |

### Summary — four configurations (PLAN.md's requested table; NAIVE row is §1's own 1,000-account
measurement for scale, not re-run at 20,000 — see note below)

| Strategy | Scale | jdbcQueryCount | elapsedMs (warm) | statements/sec (warm) |
|---|---|---|---|---|
| NAIVE — row-by-row + N+1 (§1, for scale) | 1,000 accts | 2,003 | 207,538 | 4.82 |
| FETCH_JOIN — row-by-row, `IDENTITY` | 20,000 accts | 13,325 | 43,451 | 306.16 |
| FETCH_JOIN + `batch_size=50` — `IDENTITY` unchanged | 20,000 accts | 13,325 | 43,800 | 303.72 (negative result) |
| BATCHED — `SEQUENCE` + `batch_size` + `reWriteBatchedInserts` | 20,000 accts | 303 | 11,426 | **1,164.27** |

NAIVE was not re-run at 20,000 accounts: §1 already established that a naive run at any real scale is
operationally impossible (~11.5h extrapolated for 200k accounts), and NAIVE conflates two variables —
N+1 *and* row-by-row inserts — while this investigation is isolating the insert variable alone against
the already-N+1-fixed `FETCH_JOIN` baseline (the same "one variable per investigation" discipline M4
stated when it left the insert path untouched). `statements/sec` is scale-independent, so the row is
still a fair comparison point.

**This investigation's own effect** (FETCH_JOIN → BATCHED, insert path only): **~3.80× throughput**
(306.16 → 1,164.27 stmt/s), **~3.80× wall-clock** (43,451ms → 11,426ms), **~44× fewer JDBC statements**
(13,325 → 303). The query-count collapse is the larger number because it counts *distinct prepared
statement executions*, which drops from "one per row" to "one per batch of up to 50, plus one per
chunk read"; wall-clock improves by a smaller factor because each batch still costs real time to
execute — just far less of it — and the read side (fetch-join chunk queries) is unchanged and still
contributes its own cost. **The full compounding arc across every investigation so far** (NAIVE →
BATCHED, N+1 fix + index + batching together, at their respective scales): 4.82 → 1,164.27 stmt/s,
~241× — a different, larger claim than the 3.80× above, and worth keeping the two distinct: one is
this section's isolated finding, the other is what four sections' worth of fixes add up to.

**What I learned.** The negative-result numbers (Stage A → Stage B, -0.8%) are the more important
measurement in this section, not the headline 3.80×: they prove `hibernate.jdbc.batch_size` is not a
switch that unconditionally "turns batching on" — it is silently gated by id-generation strategy, with
no exception and no failure-point warning (only the *boot-time* "enabled" message, which is true in
general but not for this specific entity's inserts). The gate makes sense once you know why:
`IDENTITY` can only produce an id as a side effect of actually inserting that exact row, so Hibernate
*must* execute row N before it can even finish constructing row N+1 — and more fundamentally, a JDBC
batch is a promise to send several statements without waiting for individual results, a promise
`IDENTITY` structurally cannot keep. A pooled `SEQUENCE` sidesteps this by moving id assignment
*before* the `INSERT` and *off* the per-row critical path — the database still hands out ids from one
source of truth, just 50 at a time instead of one at a time. The accepted cost, stated plainly: ids can
have gaps (a crashed run, or an app restart, abandons whatever's left of its claimed block of 50) —
harmless here, since `id` is a surrogate key with no business meaning and sequences were never
transactional to begin with (a rolled-back transaction doesn't return its claimed ids either).
`reWriteBatchedInserts` and `hibernate.jdbc.batch_size` are easy to conflate but sit at different
layers — one groups statements into JDBC `executeBatch()` calls (Hibernate/JDBC-API level), the other
rewrites what such a batch looks like on the wire (pgjdbc driver level); turning on either alone would
likely still help somewhat, but this investigation measured them only together, matching PLAN.md's
four-row ask — isolating each one's individual share is a natural extension, not attempted here.

## 6. ORM vs native SQL

<!-- TODO(M8) -->

## 7. Optimistic locking

<!-- TODO(M9): conflict demo output + isolation note pointer -->
