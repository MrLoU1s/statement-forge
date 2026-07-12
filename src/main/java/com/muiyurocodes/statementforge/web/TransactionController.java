package com.muiyurocodes.statementforge.web;

import com.muiyurocodes.statementforge.dto.RunningBalanceLineDto;
import com.muiyurocodes.statementforge.dto.TransactionDto;
import com.muiyurocodes.statementforge.repo.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;

    @GetMapping("/{accountId}/transactions")
    public List<TransactionDto> list(
            @PathVariable Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return transactionRepository.findDtosByAccountAndPeriod(accountId, from, to);
    }

    // PLAN.md M8: running balance through the end of `period` — no lower
    // bound on txn_date, matching StatementGenerationService.buildStatement's
    // own closingBalance semantics (cumulative since account inception, not
    // period-isolated), so this is the per-line breakdown of the same number
    // a statement's closing_balance already reports in aggregate.
    @GetMapping("/{accountId}/statements/{period}/lines")
    public List<RunningBalanceLineDto> statementLines(
            @PathVariable Long accountId,
            @PathVariable String period) {
        LocalDate periodEnd = YearMonth.parse(period).atEndOfMonth();
        return transactionRepository.findRunningBalanceThroughPeriod(accountId, periodEnd);
    }
}
