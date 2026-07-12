package com.muiyurocodes.statementforge.service;

import com.muiyurocodes.statementforge.domain.Account;
import com.muiyurocodes.statementforge.domain.Statement;
import com.muiyurocodes.statementforge.domain.StatementRun;
import com.muiyurocodes.statementforge.domain.Transaction;
import com.muiyurocodes.statementforge.dto.StatementRunResponse;
import com.muiyurocodes.statementforge.repo.AccountRepository;
import com.muiyurocodes.statementforge.repo.StatementRepository;
import com.muiyurocodes.statementforge.repo.StatementRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
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
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatementGenerationService {

    // PLAN.md M4: id-range chunk size for the fetch-join strategy — small enough
    // to avoid one giant multi-row-per-account result set, large enough that
    // limitAccounts=1000 (the M3/M4 comparison point) is exactly one chunk.
    private static final int FETCH_JOIN_CHUNK_SIZE = 1000;

    // PLAN.md M7 Stage C: accumulate this many Statement entities before
    // saveAll()+flush()+clear() — independent of FETCH_JOIN_CHUNK_SIZE (that's
    // the read-side id-range width; this is the write-side batch boundary, a
    // running counter across chunks, not reset per chunk).
    private static final int BATCH_FLUSH_SIZE = 1000;

    private final StatementRunRepository statementRunRepository;
    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final EntityManagerFactory entityManagerFactory;

    // Field-injected (not constructor/Lombok-final): @PersistenceContext gives
    // a shared, transaction-scoped EntityManager proxy — needed for the
    // explicit flush()/clear() the BATCHED strategy uses to force real JDBC
    // batches and bound persistence-context growth (PLAN.md gotcha #11).
    @PersistenceContext
    private EntityManager entityManager;

    // Hibernate Statistics is one counter per SessionFactory (process-wide, not
    // per-transaction) — cleared at the start of each run per PLAN.md M3. Fine for
    // sequential curl-driven benchmarking; would race under concurrent runs.
    @Transactional
    public StatementRunResponse generate(YearMonth period, GenerationStrategy strategy, int limitAccounts) {
        if (strategy != GenerationStrategy.NAIVE && strategy != GenerationStrategy.FETCH_JOIN
                && strategy != GenerationStrategy.BATCHED && strategy != GenerationStrategy.NATIVE_SQL) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED, "Strategy " + strategy + " is not implemented yet");
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

        int accountsLoaded;
        int statementsWritten;
        if (strategy == GenerationStrategy.NAIVE) {
            List<Account> accounts = accountRepository.findAllByOrderByIdAsc(
                    PageRequest.of(0, limitAccounts));
            accountsLoaded = accounts.size();
            statementsWritten = generateNaive(run, accounts, periodStart, periodEnd);
        } else if (strategy == GenerationStrategy.FETCH_JOIN) {
            ChunkResult result = generateFetchJoin(run, limitAccounts, periodStart, periodEnd);
            accountsLoaded = result.accountsLoaded();
            statementsWritten = result.statementsWritten();
        } else if (strategy == GenerationStrategy.BATCHED) {
            ChunkResult result = generateBatched(run, limitAccounts, periodStart, periodEnd);
            accountsLoaded = result.accountsLoaded();
            statementsWritten = result.statementsWritten();
        } else { // NATIVE_SQL — the guard above already restricted strategy to one of the four known values
            int written = statementRepository.insertAggregatedStatements(
                    run.getId(), 1, limitAccounts, periodStart, periodEnd);
            accountsLoaded = written;
            statementsWritten = written;
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
                run.getId(), strategy.name(), accountsLoaded, statementsWritten,
                elapsedMs, statementsPerSec, jdbcQueryCount);
    }

    private int generateNaive(StatementRun run, List<Account> accounts,
                               LocalDate periodStart, LocalDate periodEnd) {
        int statementsWritten = 0;
        for (Account account : accounts) {
            // Lazy load: one SELECT per account pulling ALL of that account's
            // transactions — the N+1 access pattern PLAN.md M3/M4 is about.
            Statement statement = buildStatement(run, account, account.getTransactions(), periodStart, periodEnd);
            statementRepository.save(statement); // IDENTITY -> immediate INSERT, one row at a time
            statementsWritten++;
        }
        return statementsWritten;
    }

    // Chunked id-range fetch join (PLAN.md M4) — NOT Pageable, see
    // AccountRepository.findWithTransactionsInPeriod's javadoc for why.
    // Returns {accountsLoaded, statementsWritten}: `join fetch` is an INNER
    // join, so accounts with zero transactions in the period never come back
    // and get no statement — accountsLoaded can be < limitAccounts. See
    // PERFORMANCE.md SS2 for the measured gap against NAIVE's full coverage.
    private ChunkResult generateFetchJoin(StatementRun run, int limitAccounts,
                                           LocalDate periodStart, LocalDate periodEnd) {
        int accountsLoaded = 0;
        int statementsWritten = 0;
        for (long fromId = 1; fromId <= limitAccounts; fromId += FETCH_JOIN_CHUNK_SIZE) {
            long toId = Math.min(fromId + FETCH_JOIN_CHUNK_SIZE - 1, limitAccounts);
            List<Account> chunk = accountRepository.findWithTransactionsInPeriod(
                    fromId, toId, periodStart, periodEnd);
            for (Account account : chunk) {
                Statement statement = buildStatement(run, account, account.getTransactions(), periodStart, periodEnd);
                statementRepository.save(statement);
                statementsWritten++;
            }
            accountsLoaded += chunk.size();
        }
        return new ChunkResult(accountsLoaded, statementsWritten);
    }

    private record ChunkResult(int accountsLoaded, int statementsWritten) {
    }

    // Same read path as generateFetchJoin (chunked id-range fetch join) — the
    // one variable PLAN.md M7 changes is the write side: statements accumulate
    // in `pending` and are saveAll()+flush()+clear()'d in batches of
    // BATCH_FLUSH_SIZE instead of saved one row at a time. This is what lets
    // Hibernate's JDBC batching actually engage — Statement.id is a pooled
    // SEQUENCE (V4), not IDENTITY, so ids are assigned client-side before the
    // INSERT and no per-row generated-key round trip blocks batching (compare
    // Stage B's negative result, PERFORMANCE.md SS5).
    private ChunkResult generateBatched(StatementRun run, int limitAccounts,
                                         LocalDate periodStart, LocalDate periodEnd) {
        int accountsLoaded = 0;
        int statementsWritten = 0;
        List<Statement> pending = new ArrayList<>(BATCH_FLUSH_SIZE);
        for (long fromId = 1; fromId <= limitAccounts; fromId += FETCH_JOIN_CHUNK_SIZE) {
            long toId = Math.min(fromId + FETCH_JOIN_CHUNK_SIZE - 1, limitAccounts);
            List<Account> chunk = accountRepository.findWithTransactionsInPeriod(
                    fromId, toId, periodStart, periodEnd);
            for (Account account : chunk) {
                Statement statement = buildStatement(run, account, account.getTransactions(), periodStart, periodEnd);
                pending.add(statement);
                statementsWritten++;
                if (pending.size() >= BATCH_FLUSH_SIZE) {
                    flushBatch(pending);
                }
            }
            accountsLoaded += chunk.size();
        }
        flushBatch(pending); // remainder shorter than BATCH_FLUSH_SIZE
        return new ChunkResult(accountsLoaded, statementsWritten);
    }

    // flush(): executes the accumulated inserts now, as real JDBC batches
    // (hibernate.jdbc.batch_size=50, order_inserts=true, reWriteBatchedInserts
    // on the driver). clear(): detaches everything from the persistence
    // context so it can't grow unbounded across a 20k-account run (PLAN.md
    // gotcha #11) — this also detaches the read-side Account/Transaction graph
    // loaded earlier, which is fine: buildStatement() already consumed them.
    private void flushBatch(List<Statement> pending) {
        if (pending.isEmpty()) {
            return;
        }
        statementRepository.saveAll(pending);
        entityManager.flush();
        entityManager.clear();
        pending.clear();
    }

    private Statement buildStatement(StatementRun run, Account account, List<Transaction> transactions,
                                      LocalDate periodStart, LocalDate periodEnd) {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal closingBalance = BigDecimal.ZERO;

        for (Transaction txn : transactions) {
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
        return statement;
    }
}
