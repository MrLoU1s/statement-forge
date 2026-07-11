package com.muiyurocodes.statementforge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

// id stays IDENTITY on purpose: Investigation 4 (M7) migrates it to a pooled
// SEQUENCE (allocationSize matching V4's INCREMENT BY) to demonstrate the
// IDENTITY-vs-SEQUENCE JDBC batching gotcha. Do not change this ahead of M7.
@Entity
@Table(name = "statement")
@Getter
@Setter
@NoArgsConstructor
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
