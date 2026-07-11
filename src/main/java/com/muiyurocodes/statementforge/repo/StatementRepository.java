package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Statement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    long countByStatementRunId(Long statementRunId);
}
