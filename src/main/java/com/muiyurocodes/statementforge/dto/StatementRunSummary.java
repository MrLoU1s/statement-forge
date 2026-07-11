package com.muiyurocodes.statementforge.dto;

import java.time.LocalDate;

public record StatementRunSummary(
        Long runId,
        LocalDate runDate,
        String channel,
        String status,
        long version,
        long statementsWritten
) {
}
