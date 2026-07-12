package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Transaction;
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
}
