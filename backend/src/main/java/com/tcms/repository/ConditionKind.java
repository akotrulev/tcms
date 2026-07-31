package com.tcms.repository;

/**
 * What role one <em>attachment</em> plays — {@code kind} on {@code case_condition} and on
 * {@code suite_condition} in {@code V1}, and the field that splits a case's conditions into the
 * contract's two lists.
 *
 * <p>Two members where {@link ConditionType} has three, and the missing one is the point:
 * {@code BOTH} says what a library entry <em>may</em> be used for, {@code kind} says what it
 * <em>was</em> used as. A BOTH entry attached before the steps is a {@code PRE} attachment and
 * attached after them is a {@code POST} one — the same row, twice, in two different positions.
 * A third member here would mean an attachment that is simultaneously setup and cleanup, which is
 * not a thing a tester can execute.
 *
 * <p>The column has existed since {@code V1} and was deliberately left unmapped through §24: the
 * suite endpoint's {@code CaseConditionRef} has no field to carry it, so mapping it there would
 * have produced a value with nowhere to go. The case detail is the reader it was waiting for —
 * not because the shape gained a field, but because the response gained a second <em>list</em>,
 * and this is what decides which one a row lands in.
 */
public enum ConditionKind {

    /** Setup that must hold before step 1. Rendered as a checklist above the steps (plan §6f). */
    PRE,

    /** Cleanup that must hold after the last step. Does not fail the case by default (plan §6f). */
    POST
}
