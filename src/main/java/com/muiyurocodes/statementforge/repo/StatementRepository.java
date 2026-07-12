package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Statement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    long countByStatementRunId(Long statementRunId);

    // PLAN.md M8: the entire monthly aggregation as one set-based statement —
    // no account/transaction entities ever materialize in Java, no chunking
    // loop. Same account-id-range windowing as FETCH_JOIN/BATCHED
    // (:fromId/:toId) so limitAccounts stays an apples-to-apples scale knob
    // across all four strategies; a plain `JOIN` (inner) means an account
    // with zero transactions in the period contributes no row and gets no
    // statement — the same coverage behavior as M4's fetch join (DECISIONS.md
    // D11), now happening entirely inside Postgres via GROUP BY instead of
    // Java iterating a loaded collection. Returns the row count actually
    // inserted, which doubles as both accountsLoaded and statementsWritten
    // for this strategy (see StatementGenerationService).
    //
    // Native SQL here is a deliberate choice, not a forced one: HQL bulk
    // insert-select with CASE WHEN in place of FILTER was empirically tried
    // against this exact schema and it worked (Hibernate 7.4.1 compiles it
    // into a CTE that also handles the Statement.id pooled-SEQUENCE batch
    // allocation) — see DECISIONS.md D8. Kept as plain SQL anyway: FILTER
    // reads as intent ("sum these rows meeting this condition") where CASE
    // WHEN reads as a value substitution; the generated SQL here is exactly
    // what's written, not translated through Hibernate's HQL-to-SQL layer.
    //
    // nextval('statement_id_seq') is explicit and required: M7's V4 migration
    // dropped IDENTITY for a plain SEQUENCE with no column DEFAULT (Hibernate's
    // own JPA path calls nextval() itself via its @SequenceGenerator, never
    // touching the column's DDL) — a native INSERT that skips `id` bypasses
    // that generator entirely and would insert NULL into a NOT NULL column
    // (hit exactly this as a real 500 while self-verifying this milestone).
    // Calling nextval() once per row here forgoes M7's pooled-allocation
    // optimization (50 ids per round trip) since this is a single set-based
    // statement, not a loop of row-by-row saves — there is no per-row round
    // trip to amortize in the first place.
    @Modifying
    @Query(value = """
            INSERT INTO statement (id, statement_run_id, account_id, period_start, period_end,
                                    total_debits, total_credits, closing_balance, document_ref)
            SELECT nextval('statement_id_seq'), :runId, a.id, :periodStart, :periodEnd,
                   COALESCE(SUM(t.amount) FILTER (WHERE t.amount < 0
                             AND t.txn_date BETWEEN :periodStart AND :periodEnd), 0),
                   COALESCE(SUM(t.amount) FILTER (WHERE t.amount > 0
                             AND t.txn_date BETWEEN :periodStart AND :periodEnd), 0),
                   COALESCE(SUM(t.amount) FILTER (WHERE t.txn_date <= :periodEnd), 0),
                   'stmt-' || :runId || '-' || a.id
            FROM account a
            JOIN transaction t ON t.account_id = a.id
            WHERE a.id BETWEEN :fromId AND :toId
            GROUP BY a.id
            """, nativeQuery = true)
    int insertAggregatedStatements(
            @Param("runId") long runId,
            @Param("fromId") long fromId,
            @Param("toId") long toId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);
}
