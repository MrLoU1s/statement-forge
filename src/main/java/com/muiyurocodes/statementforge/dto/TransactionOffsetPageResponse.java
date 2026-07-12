package com.muiyurocodes.statementforge.dto;

import java.util.List;

// GET /api/transactions?page=&size= — PLAN.md M6.
public record TransactionOffsetPageResponse(
        List<TransactionDto> content,
        int page,
        int size,
        boolean hasNext
) {
}
