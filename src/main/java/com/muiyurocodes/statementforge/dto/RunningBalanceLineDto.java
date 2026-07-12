package com.muiyurocodes.statementforge.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// PLAN.md M8: one transaction line with its running balance, via a window
// function. Column order/types match this constructor exactly, which is what
// lets Spring Data JPA bind a native query straight into this record with no
// SqlResultSetMapping — see TransactionRepository.findRunningBalanceThroughPeriod.
public record RunningBalanceLineDto(
        Long id,
        LocalDate txnDate,
        BigDecimal amount,
        String description,
        BigDecimal runningBalance
) {
}
