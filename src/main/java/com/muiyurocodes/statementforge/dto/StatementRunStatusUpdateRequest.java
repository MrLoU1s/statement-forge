package com.muiyurocodes.statementforge.dto;

import jakarta.validation.constraints.NotBlank;

// PATCH /api/statement-runs/{id} request body — PLAN.md M9. status is
// NOT NULL at the DB (V1) with no column default; @NotBlank turns a missing
// value into a 400 here instead of a constraint-violation 500 at flush time.
public record StatementRunStatusUpdateRequest(@NotBlank String status) {
}
