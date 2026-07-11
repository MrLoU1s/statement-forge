# DECISIONS.md — design log

Short entries, newest last. Format: **Context → Decision → Why → Trade-offs.**

## D1 — Flyway owns the schema; Hibernate only validates
`ddl-auto: validate`, migrations under `db/migration`. Schema history is the point of a
long-lived application; `update` hides drift and can't be code-reviewed.

## D2 — `open-in-view: false`
Surfaces lazy-loading problems (N+1) as exceptions/queries where they happen instead of
silently extending the session into the web layer.

## D3 — Seed data lives in a Flyway migration (V2), deterministic
Anyone who clones gets byte-identical data (`setseed`), so every number in
PERFORMANCE.md is reproducible. Trade-off: first boot takes 1–3 minutes.

## D4 — No secondary indexes in V1
Indexes are added by later migrations, each justified by a measured plan change. This
mirrors how a long-lived schema should evolve: evidence first.

## D5 — Statement id: IDENTITY → SEQUENCE (V4)
<!-- TODO(M7): the batching gotcha, allocationSize=50 = INCREMENT BY 50, id gaps accepted -->

## D6 — Composite index column order
<!-- TODO(M5): (account_id, txn_date) — equality first, range second -->

## D7 — Keyset pagination for deep listing
<!-- TODO(M6): trade-off — no random page jumps; cursor API style -->

## D8 — When ORM, when native SQL
<!-- TODO(M8): incl. the JPQL dead end for window functions -->

## D9 — Optimistic locking + isolation level for the batch
<!-- TODO(M9): why READ COMMITTED suffices; when REPEATABLE READ would matter -->

## D10 — Spring Boot 4.1 (not 3.x)
The Initializr scaffold came out on Boot 4.1.0 GA. Everything demonstrated here (JPA,
Flyway, native queries, `@Version`, JDBC batching) is version-agnostic; staying on the
scaffold's version avoided rework that proves nothing.

## D11 — `join fetch` (inner), not `left join fetch`, for the N+1 fix (M4)
`AccountRepository.findWithTransactionsInPeriod` uses a plain `join fetch`, which is an
**inner** join: an account with zero transactions in the requested period produces no
row and is silently dropped from the result (measured: 1,000 requested → 667 returned
for period 2025-03, cross-checked directly against the database — see PERFORMANCE.md
§2). Kept as-is rather than switched to `left join fetch` because it matches this
statement-generator's actual business rule as I've implemented it here: no activity in
the period, no statement. **Trade-off if that rule were wrong for a real CCM system**
(e.g. "every account gets a statement, even a zero-activity one"): `left join fetch`
would preserve full coverage — the account still comes back, its `transactions`
collection just initializes empty — at the cost of the caller needing to explicitly
decide what a zero-activity statement means (all-zero totals? skip the insert but log
it? something else). I did not build that variant; flagging the decision point is the
point. Separately: PLAN.md's suggested snippet uses `select distinct a ...` — dropped
here, since Hibernate 6+ deduplicates `join fetch` root entities in memory automatically
and `distinct` now only adds an unneeded SQL-level `DISTINCT` (confirmed against current
Hibernate ORM docs, not assumed from training data — the Boot-4.1/Hibernate-7 gap this
project's CLAUDE.md flags as a real risk).
