package com.muiyurocodes.statementforge.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionDto(
        Long id,
        LocalDate txnDate,
        BigDecimal amount,
        String description
) {
}
