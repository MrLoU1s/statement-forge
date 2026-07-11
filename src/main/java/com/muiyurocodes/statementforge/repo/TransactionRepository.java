package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
