package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Transaction;
import com.muiyurocodes.statementforge.dto.TransactionDto;
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
}
