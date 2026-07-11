package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // Returns a List (not Page) so Spring Data skips the count(*) query —
    // one SELECT for the naive baseline's account load, per PLAN.md M3.
    List<Account> findAllByOrderByIdAsc(Pageable pageable);
}
