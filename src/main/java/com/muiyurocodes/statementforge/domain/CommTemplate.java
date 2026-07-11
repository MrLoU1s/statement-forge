package com.muiyurocodes.statementforge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "comm_template")
@Getter
@Setter
@NoArgsConstructor
public class CommTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String channel;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    // Template content version (v1, v2, ... of the body copy) — not optimistic
    // locking. @Version lives on StatementRun only.
    @Column(nullable = false)
    private Integer version;
}
