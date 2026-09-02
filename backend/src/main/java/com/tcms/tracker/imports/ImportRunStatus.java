package com.tcms.tracker.imports;

/**
 * Where a run got to — {@code import_run.status}.
 *
 * <p>Forwards only: {@code RUNNING} becomes {@code COMPLETED} or {@code FAILED} and never anything
 * else, and a re-request is a <em>new row</em>. {@code report_job}'s state machine and its argument
 * — one request per row is what keeps the record of what was asked for beside what it produced.
 *
 * <p>Declared in T3 with the rest of the module's wire vocabulary, and first written in T4:
 * {@code ContractEnumTest} asserts that every named enum in {@code openapi.yaml} names the server
 * type that serves it, so a contract enum whose Java type arrives a task later fails the build in
 * the gap.
 */
public enum ImportRunStatus {

    RUNNING,
    COMPLETED,
    FAILED
}
