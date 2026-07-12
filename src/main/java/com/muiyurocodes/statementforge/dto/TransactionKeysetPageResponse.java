package com.muiyurocodes.statementforge.dto;

import java.util.List;

// GET /api/transactions?afterId=&size= — PLAN.md M6.
public record TransactionKeysetPageResponse(
        List<TransactionDto> content,
        Long nextCursor,
        int size
) {
}
