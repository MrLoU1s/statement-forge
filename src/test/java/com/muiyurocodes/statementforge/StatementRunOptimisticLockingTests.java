package com.muiyurocodes.statementforge;

import com.muiyurocodes.statementforge.domain.StatementRun;
import com.muiyurocodes.statementforge.repo.StatementRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// PLAN.md M9: proves the @Version (StatementRun, M1) lost-update guard fires.
// Two TransactionTemplate blocks (PLAN.md's own suggested demonstration,
// chosen over racing concurrent HTTP requests because it's deterministic —
// no artificial delay needed to force the interleaving) each load the SAME
// row at the SAME version, both mutate it, and only the first save can win.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StatementRunOptimisticLockingTests {

    @Autowired
    private StatementRunRepository statementRunRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void secondConcurrentSaveLosesToStaleVersion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long id = tx.execute(status -> {
            StatementRun run = new StatementRun();
            run.setRunDate(LocalDate.now());
            run.setChannel("BATCH");
            run.setStatus("RUNNING");
            return statementRunRepository.saveAndFlush(run).getId();
        });

        // Both loads happen before either write — same starting version, each
        // becoming a detached copy once its own transaction commits.
        StatementRun a = tx.execute(status -> statementRunRepository.findById(id).orElseThrow());
        StatementRun b = tx.execute(status -> statementRunRepository.findById(id).orElseThrow());
        assertEquals(a.getVersion(), b.getVersion());

        a.setStatus("APPROVED");
        tx.execute(status -> statementRunRepository.saveAndFlush(a));
        // First writer wins: DB version is now incremented under it.

        b.setStatus("REJECTED");
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                tx.execute(status -> statementRunRepository.saveAndFlush(b)));
        // b is detached, so saveAndFlush merges it: Hibernate reloads the
        // current row (now version 1), compares it against b's captured
        // version 0, and raises the stale-state exception right there — no
        // second UPDATE is ever prepared (confirmed via the SQL debug log,
        // docs/evidence/m9-optimistic-locking-test.log). A live HTTP race
        // through the actual PATCH endpoint hits a different Hibernate path
        // instead — an UPDATE that executes and matches zero rows — because
        // there both entities stay managed in their own transaction rather
        // than being detached and re-merged; see PERFORMANCE.md SS7.
    }
}
