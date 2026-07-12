package com.muiyurocodes.statementforge.web;

import com.muiyurocodes.statementforge.dto.TransactionDto;
import com.muiyurocodes.statementforge.dto.TransactionKeysetPageResponse;
import com.muiyurocodes.statementforge.dto.TransactionOffsetPageResponse;
import com.muiyurocodes.statementforge.repo.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// PLAN.md M6 — OFFSET vs keyset pagination over the full 5M-row transaction
// table. Both live at /api/transactions, disambiguated by which query param
// is present (`page` selects the OFFSET variant, `afterId` the keyset one) —
// Spring MVC's `params` mapping condition routes on that directly.
@RestController
@RequiredArgsConstructor
public class TransactionPageController {

    private final TransactionRepository transactionRepository;

    // `!afterId`/`!page` make the two conditions mutually exclusive — plain
    // `params = "page"` only asserts presence, so a request carrying BOTH
    // query params would otherwise match both handlers and Spring would throw
    // an ambiguous-mapping exception (500) instead of picking one.
    @GetMapping(value = "/api/transactions", params = {"page", "!afterId"})
    public TransactionOffsetPageResponse listOffset(
            @RequestParam int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Slice<TransactionDto> slice = transactionRepository.findAllByOrderByIdOffset(pageable);
        return new TransactionOffsetPageResponse(slice.getContent(), page, size, slice.hasNext());
    }

    @GetMapping(value = "/api/transactions", params = {"afterId", "!page"})
    public TransactionKeysetPageResponse listKeyset(
            @RequestParam long afterId,
            @RequestParam(defaultValue = "50") int size) {
        List<TransactionDto> content = transactionRepository.findAfterId(afterId, PageRequest.ofSize(size));
        Long nextCursor = content.isEmpty() ? null : content.get(content.size() - 1).id();
        return new TransactionKeysetPageResponse(content, nextCursor, size);
    }
}
