package com.tcms.work;

/**
 * How much a board card matters — {@code Priority} in {@code docs/specs/openapi.yaml}.
 *
 * <h2>A second enum with the same four values, on purpose</h2>
 *
 * <p>{@code repository.Priority} exists and holds exactly these members, so this looks like the
 * duplication the codebase otherwise refuses. It is not, and §6l says why in one sentence:
 * <em>"a P1 test case may be a low-priority thing to update."</em> {@code test_case.priority} is how
 * urgent it is that a case <strong>runs</strong>; {@code work_item.priority} is how urgent it is
 * that somebody <strong>does something about it</strong>. The values coincide; the meanings do not,
 * and {@code V1} keeps them as two columns with two separate {@code CHECK} constraints for that
 * reason — its comment on the column says so outright.
 *
 * <p>The module boundary agrees independently: {@code work} may not depend on {@code repository}
 * (plan §3), so sharing the type would require moving it to a foundation module — which would put a
 * vocabulary that belongs to two features into a module that owns neither. Two rules pointing the
 * same way is the reason this is comfortable rather than tolerated.
 *
 * <p>They share one spelling on the wire because the contract has one {@code Priority} schema, and
 * that is correct: a client rendering a four-level scale does not care which table it came from.
 */
public enum Priority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
