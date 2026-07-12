package com.muiyurocodes.statementforge.web;

import com.muiyurocodes.statementforge.domain.StatementRun;
import com.muiyurocodes.statementforge.dto.StatementRunResponse;
import com.muiyurocodes.statementforge.dto.StatementRunStatusUpdateRequest;
import com.muiyurocodes.statementforge.dto.StatementRunSummary;
import com.muiyurocodes.statementforge.repo.StatementRepository;
import com.muiyurocodes.statementforge.repo.StatementRunRepository;
import com.muiyurocodes.statementforge.service.GenerationStrategy;
import com.muiyurocodes.statementforge.service.StatementGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // PLAN.md M9: status transition guarded by StatementRun.version (@Version,
    // M1). @Transactional makes the read-modify-write's transaction boundary
    // explicit here — saveAndFlush's version check (WHERE id=? AND version=?)
    // runs at this method's flush, under Postgres's default READ COMMITTED; a
    // concurrent PATCH that read the same version loses this race and gets
    // ObjectOptimisticLockingFailureException, mapped to 409 by
    // GlobalExceptionHandler. See DECISIONS.md D9 for the isolation-level note.
    @PatchMapping("/{id}")
    @Transactional
    public StatementRunSummary updateStatus(@PathVariable Long id, @Valid @RequestBody StatementRunStatusUpdateRequest request) {
        StatementRun run = statementRunRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No statement_run " + id));
        run.setStatus(request.status());
        statementRunRepository.saveAndFlush(run);
        long statementsWritten = statementRepository.countByStatementRunId(id);
        return new StatementRunSummary(
                run.getId(), run.getRunDate(), run.getChannel(), run.getStatus(),
                run.getVersion(), statementsWritten);
    }
}
