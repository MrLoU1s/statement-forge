package com.muiyurocodes.statementforge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "statement_run")
@Getter
@Setter
@NoArgsConstructor
public class StatementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status;

    // Optimistic locking for the batch-run status transition — see
    // PLAN.md Investigation 6 (M9). The only @Version in this schema.
    @Version
    @Column(nullable = false)
    private long version;
}
