package com.muiyurocodes.statementforge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

// id migrated IDENTITY -> pooled SEQUENCE in M7 (V4__statement_id_sequence.sql)
// specifically to unblock Hibernate JDBC insert batching: IDENTITY forces an
// immediate per-row INSERT to read back the generated key, which silently
// disables batching (measured negative result, PERFORMANCE.md SS5 Stage B).
// allocationSize MUST match V4's `INCREMENT BY 50` — the pooled optimizer
// pre-allocates a block of ids from one nextval() call so inserts need no
// per-row round trip. Id gaps on restart/crash are an accepted trade (see
// DECISIONS.md D5).
@Entity
@Table(name = "statement")
@Getter
@Setter
@NoArgsConstructor
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "statement_seq")
    @SequenceGenerator(name = "statement_seq", sequenceName = "statement_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_run_id", nullable = false)
    private StatementRun statementRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "total_debits", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDebits;

    @Column(name = "total_credits", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalCredits;

    @Column(name = "closing_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal closingBalance;

    @Column(name = "document_ref", nullable = false)
    private String documentRef;
}
