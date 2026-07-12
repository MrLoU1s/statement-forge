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

## D6 — Composite index column order: (account_id, txn_date)
`idx_transaction_account_date ON transaction (account_id, txn_date)` — equality column
(`account_id`) leads, range column (`txn_date`) trails. A btree sorts by the leading
column first; within a fixed leading value the rows are then already sorted by the
second column, so the range predicate becomes a short contiguous walk instead of a
filter over unsorted rows. Measured: seq scan (287.9ms, ~44.8k buffers) → index scan
(0.204ms, 10 buffers) — see PERFORMANCE.md §3. The reverse order, `(txn_date,
account_id)`, would only let Postgres narrow by date first, then still have to filter
every date-matching row (across all 200k accounts) for the right account — a much
bigger, less-sorted scan; not built, reasoned through instead. Trade-off: every index
adds write-time maintenance cost and storage — this one has to be updated on every
`transaction` write, directly relevant to M7's bulk-insert investigation. Plain
`CREATE INDEX`, not `CONCURRENTLY`, because Flyway migrations run inside a transaction
and `CONCURRENTLY` cannot; a live production table would build it outside Flyway with
`CONCURRENTLY` to avoid holding a write lock for the build duration.

## D7 — Keyset pagination for deep listing
`GET /api/transactions` ships both an OFFSET endpoint (`page`/`size`) and a keyset one
(`afterId`/`size`), same path, dispatched by which query param is present (Spring MVC's
`params` mapping condition) — kept both, not just the fixed version, because PLAN.md's
whole point is a reproducible before/after, and a real API would likely keep an
OFFSET-style "jump to page N" option too for UI page-number links even after adding
keyset for deep/infinite-scroll consumption. Measured: at depth 10,000 (offset 500,000,
`size=50`), OFFSET costs 115.1ms DB-side / ~118ms API vs keyset's 0.218ms DB-side /
~13ms API — ~528× DB, ~9× end-to-end (same "DB gets fast, HTTP/JSON becomes the floor"
shape as D6). Both plans use the *same* index (`transaction_pkey`, no new index added)
— the point isn't a missing index, it's that `OFFSET n` is a positional skip a btree
can't seek to directly, while `WHERE id > :afterId` is a value condition it can. See
PERFORMANCE.md §4. **Trade-off, stated plainly:** keyset gives up random page-number
access — there is no "jump straight to page 7,000," only "give me the next batch after
this cursor." That's the accepted cost in every cursor-token API (GitHub, Stripe,
Slack) because their consumers walk forward, not to an arbitrary page; a UI that
genuinely needs numbered page links would still need OFFSET (or a maintained separate
position index) for that specific feature. Also chose `Slice<T>` over `Page<T>` for the
OFFSET endpoint specifically to avoid Spring Data's automatic `count(*)` query — measured
that query alone at 396ms warm (full parallel seq scan; PostgreSQL can't satisfy
`count(*)` from an index under MVCC), which would have been added to *every* request to
either endpoint had `Page<T>` been used instead.

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
