package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Transaction;
import com.muiyurocodes.statementforge.dto.RunningBalanceLineDto;
import com.muiyurocodes.statementforge.dto.TransactionDto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Flat DTO projection (PLAN.md M4's "stricter alternative", built here for
    // M5): selects only the 4 needed columns, no Transaction entity
    // materialization. `t.account.id` compiles to the account_id FK column
    // directly — no join, since it's a to-one association's own key.
    @Query("select new com.muiyurocodes.statementforge.dto.TransactionDto(t.id, t.txnDate, t.amount, t.description) "
            + "from Transaction t where t.account.id = :accountId and t.txnDate between :from and :to "
            + "order by t.txnDate asc")
    List<TransactionDto> findDtosByAccountAndPeriod(
            @Param("accountId") Long accountId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // OFFSET pagination (naive) — PLAN.md M6. `Slice`, not `Page`: Spring Data
    // fetches size+1 rows to derive `hasNext` and issues no separate
    // `count(*)` query — a `Page` return type would fire one over all 5M rows
    // on every single request, its own mini-finding (see PERFORMANCE.md §4).
    // `Pageable`'s offset = page * size becomes a literal SQL OFFSET.
    @Query("select new com.muiyurocodes.statementforge.dto.TransactionDto(t.id, t.txnDate, t.amount, t.description) "
            + "from Transaction t order by t.id asc")
    Slice<TransactionDto> findAllByOrderByIdOffset(Pageable pageable);

    // Keyset (cursor) pagination — PLAN.md M6. `id` is both the total order
    // and the cursor: a single unique, monotonically increasing column needs
    // no tie-breaker. `WHERE id > :afterId` seeks directly via the primary
    // key's btree instead of scanning-and-discarding the skipped rows.
    @Query("select new com.muiyurocodes.statementforge.dto.TransactionDto(t.id, t.txnDate, t.amount, t.description) "
            + "from Transaction t where t.id > :afterId order by t.id asc")
    List<TransactionDto> findAfterId(@Param("afterId") long afterId, Pageable pageable);

    // PLAN.md M8: per-transaction running balance via a window function.
    // Native, by deliberate choice, not because JPQL/HQL can't — empirically
    // verified against this exact Hibernate version that the equivalent HQL
    // (`select new ...(..., sum(t.amount) over (partition by t.account.id
    // order by t.txnDate, t.id)) from Transaction t where ...`) compiles and
    // runs correctly (see DECISIONS.md D8). Native SQL kept here because this
    // is a pure read-only reporting query over one table with no entity
    // graph or business logic attached — there is nothing for an ORM to
    // abstract, so writing (and reading the EXPLAIN plan for) the exact SQL
    // that runs is strictly simpler than going through HQL's translation
    // layer for no benefit. `RunningBalanceLineDto`'s constructor argument
    // order/types match this SELECT's column order exactly, which is what
    // lets Spring Data JPA bind a native query straight into the record with
    // no `@SqlResultSetMapping`.
    @Query(value = """
            SELECT t.id, t.txn_date, t.amount, t.description,
                   SUM(t.amount) OVER (PARTITION BY t.account_id ORDER BY t.txn_date, t.id) AS running_balance
            FROM transaction t
            WHERE t.account_id = :accountId AND t.txn_date <= :periodEnd
            ORDER BY t.txn_date, t.id
            """, nativeQuery = true)
    List<RunningBalanceLineDto> findRunningBalanceThroughPeriod(
            @Param("accountId") Long accountId, @Param("periodEnd") LocalDate periodEnd);
}
