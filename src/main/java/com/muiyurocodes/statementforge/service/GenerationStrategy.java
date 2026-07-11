package com.muiyurocodes.statementforge.service;

// Added milestone by milestone (PLAN.md): NAIVE now (M3), FETCH_JOIN (M4),
// BATCHED (M7), NATIVE_SQL (M8). Every "before" strategy stays callable
// forever so all before/after numbers stay reproducible.
public enum GenerationStrategy {
    NAIVE,
    FETCH_JOIN,
    BATCHED,
    NATIVE_SQL
}
