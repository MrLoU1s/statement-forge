package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
