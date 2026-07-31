package com.tcms.repository;

/**
 * {@code Priority} in {@code docs/specs/openapi.yaml} — how much it matters that this case runs.
 *
 * <p>Serves both {@code test_case.priority} and {@code test_case.severity}: the contract uses this
 * one enum for both, and the database's two {@code CHECK} constraints list the same four values.
 * They mean different things — priority is scheduling, severity is the blast radius of the bug the
 * case would catch — but they share a vocabulary, and giving each its own identical enum would be
 * two things to keep in step for no gain.
 */
public enum Priority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
