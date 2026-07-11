package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // Returns a List (not Page) so Spring Data skips the count(*) query —
    // one SELECT for the naive baseline's account load, per PLAN.md M3.
    List<Account> findAllByOrderByIdAsc(Pageable pageable);

    // Chunked by id range, NOT Pageable: fetch-join + LIMIT/OFFSET makes
    // Hibernate paginate the whole match set in memory (HHH90003004) instead
    // of at the database — PLAN.md M4. No `distinct`: as of Hibernate 6,
    // duplicate root entities from a join fetch are deduplicated in-memory
    // automatically, so `distinct` (PLAN.md's snippet has it) only adds an
    // unneeded SQL-level DISTINCT — confirmed against current Hibernate docs.
    // `join fetch` (inner) means an account with zero transactions in the
    // period is dropped from the result entirely — a deliberate deviation
    // from NAIVE's "one statement per requested account" coverage; see
    // PERFORMANCE.md SS2 and DECISIONS.md D11.
    @Query("select a from Account a join fetch a.transactions t "
            + "where a.id between :fromId and :toId and t.txnDate between :from and :to")
    List<Account> findWithTransactionsInPeriod(
            @Param("fromId") long fromId,
            @Param("toId") long toId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
