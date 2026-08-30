package com.tcms.tracker;

/**
 * An issue's priority — {@code issue.priority}, and the contract's {@code Priority} schema.
 *
 * <p>Its own enum rather than {@code work.Priority}: {@code tracker} may not import {@code work},
 * and {@code work} is retired in T4 anyway. The four members and their spellings are the contract's,
 * so the two are wire-identical for as long as both exist.
 */
public enum Priority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
