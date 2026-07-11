package com.muiyurocodes.statementforge.service;

import com.muiyurocodes.statementforge.domain.Account;
import com.muiyurocodes.statementforge.domain.Statement;
import com.muiyurocodes.statementforge.domain.StatementRun;
import com.muiyurocodes.statementforge.domain.Transaction;
import com.muiyurocodes.statementforge.dto.StatementRunResponse;
import com.muiyurocodes.statementforge.repo.AccountRepository;
import com.muiyurocodes.statementforge.repo.StatementRepository;
import com.muiyurocodes.statementforge.repo.StatementRunRepository;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatementGenerationService {

    private final StatementRunRepository statementRunRepository;
    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final EntityManagerFactory entityManagerFactory;

    // Hibernate Statistics is one counter per SessionFactory (process-wide, not
    // per-transaction) — cleared at the start of each run per PLAN.md M3. Fine for
    // sequential curl-driven benchmarking; would race under concurrent runs.
    @Transactional
    public StatementRunResponse generate(YearMonth period, GenerationStrategy strategy, int limitAccounts) {
        if (strategy != GenerationStrategy.NAIVE) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "Strategy " + strategy + " is not implemented yet");
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        long startNanos = System.nanoTime();

        StatementRun run = new StatementRun();
        run.setRunDate(LocalDate.now());
        run.setChannel("BATCH");
        run.setStatus("RUNNING");
        run = statementRunRepository.save(run); // IDENTITY -> immediate INSERT

        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.atEndOfMonth();

        List<Account> accounts = accountRepository.findAllByOrderByIdAsc(
                PageRequest.of(0, limitAccounts));

        int statementsWritten = 0;
        for (Account account : accounts) {
            BigDecimal totalDebits = BigDecimal.ZERO;
            BigDecimal totalCredits = BigDecimal.ZERO;
            BigDecimal closingBalance = BigDecimal.ZERO;

            // Lazy load: one SELECT per account pulling ALL of that account's
            // transactions — the N+1 access pattern PLAN.md M3/M4 is about.
            for (Transaction txn : account.getTransactions()) {
                LocalDate txnDate = txn.getTxnDate();
                BigDecimal amount = txn.getAmount();

                if (!txnDate.isBefore(periodStart) && !txnDate.isAfter(periodEnd)) {
                    if (amount.signum() < 0) {
                        totalDebits = totalDebits.add(amount);
                    } else {
                        totalCredits = totalCredits.add(amount);
                    }
                }
                if (!txnDate.isAfter(periodEnd)) {
                    closingBalance = closingBalance.add(amount);
                }
            }

            Statement statement = new Statement();
            statement.setStatementRun(run);
            statement.setAccount(account);
            statement.setPeriodStart(periodStart);
            statement.setPeriodEnd(periodEnd);
            statement.setTotalDebits(totalDebits);
            statement.setTotalCredits(totalCredits);
            statement.setClosingBalance(closingBalance);
            statement.setDocumentRef("stmt-" + run.getId() + "-" + account.getId());
            statementRepository.save(statement); // IDENTITY -> immediate INSERT, one row at a time

            statementsWritten++;
        }

        run.setStatus("COMPLETED");
        // saveAndFlush: force the UPDATE to execute now, inside the method body,
        // so both elapsedMs and the statistics snapshot below include it — the
        // @Transactional proxy would otherwise defer it to commit, after we return.
        statementRunRepository.saveAndFlush(run);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        long jdbcQueryCount = statistics.getPrepareStatementCount();
        double statementsPerSec = elapsedMs > 0
                ? statementsWritten / (elapsedMs / 1000.0)
                : statementsWritten;

        return new StatementRunResponse(
                run.getId(), strategy.name(), accounts.size(), statementsWritten,
                elapsedMs, statementsPerSec, jdbcQueryCount);
    }
}
