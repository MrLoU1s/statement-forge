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

<!-- TODO(M2): row counts, pg_total_relation_size('transaction'), seed determinism note -->

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
