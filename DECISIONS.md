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
