package com.muiyurocodes.statementforge.dto;

// The benchmark record returned by POST /api/statement-runs — PLAN.md M3.
public record StatementRunResponse(
        Long runId,
        String strategy,
        int accounts,
        int statementsWritten,
        long elapsedMs,
        double statementsPerSec,
        long jdbcQueryCount
) {
}
