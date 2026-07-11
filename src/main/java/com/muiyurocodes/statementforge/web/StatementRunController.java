package com.muiyurocodes.statementforge.web;

import com.muiyurocodes.statementforge.domain.StatementRun;
import com.muiyurocodes.statementforge.dto.StatementRunResponse;
import com.muiyurocodes.statementforge.dto.StatementRunSummary;
import com.muiyurocodes.statementforge.repo.StatementRepository;
import com.muiyurocodes.statementforge.repo.StatementRunRepository;
import com.muiyurocodes.statementforge.service.GenerationStrategy;
import com.muiyurocodes.statementforge.service.StatementGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/statement-runs")
@RequiredArgsConstructor
public class StatementRunController {

    private final StatementGenerationService statementGenerationService;
    private final StatementRunRepository statementRunRepository;
    private final StatementRepository statementRepository;

    @PostMapping
    public StatementRunResponse create(
            @RequestParam String period,
            @RequestParam GenerationStrategy strategy,
            @RequestParam(defaultValue = "1000") int limitAccounts) {
        return statementGenerationService.generate(YearMonth.parse(period), strategy, limitAccounts);
    }

    @GetMapping("/{id}")
    public StatementRunSummary get(@PathVariable Long id) {
        StatementRun run = statementRunRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No statement_run " + id));
        long statementsWritten = statementRepository.countByStatementRunId(id);
        return new StatementRunSummary(
                run.getId(), run.getRunDate(), run.getChannel(), run.getStatus(),
                run.getVersion(), statementsWritten);
    }
}
