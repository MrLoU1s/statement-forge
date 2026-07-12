-- M7 Stage C: statement.id migrates from IDENTITY to a pooled SEQUENCE.
--
-- Why: Hibernate disables JDBC insert batching transparently when a column
-- uses IDENTITY generation, because it must read the generated key back after
-- every single-row INSERT before it can proceed. Measured as a negative
-- result in Stage B (PERFORMANCE.md SS5): setting hibernate.jdbc.batch_size
-- alone, with id still IDENTITY, produced no throughput change at all. A
-- SEQUENCE lets Hibernate pre-allocate a block of ids client-side via one
-- nextval() call, so a run of inserts needs no per-row round trip and can be
-- handed to the JDBC driver as a real batch.
--
-- INCREMENT BY 50 must match the entity's @SequenceGenerator(allocationSize).

ALTER TABLE statement ALTER COLUMN id DROP IDENTITY;

CREATE SEQUENCE statement_id_seq INCREMENT BY 50 OWNED BY statement.id;

-- Seed the sequence past every id already written by IDENTITY (M3/M4 runs and
-- M7 Stages A/B all inserted rows before this migration) so the first
-- SEQUENCE-generated id cannot collide with an existing row.
SELECT setval('statement_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM statement));
