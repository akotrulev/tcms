package com.tcms.execution;

/**
 * How a case's body was authored — {@code StepFormat} in {@code docs/specs/openapi.yaml}.
 *
 * <p>A second declaration of {@code repository.StepFormat}, and deliberately not a shared one:
 * this module may not import that package (see {@code package-info}), and the value arrives here
 * as a <strong>string inside a pinned snapshot</strong> rather than as a Java value passed across a
 * call. What is shared is the JSON spelling, which is the contract's, not a type.
 *
 * <p>The duplication is safe in the direction that matters: a member added there and not here makes
 * {@link #of} fall back rather than throw, so a snapshot written by a newer {@code repository}
 * still renders its steps.
 */
public enum StepFormat {

    STRUCTURED,
    GHERKIN;

    /**
     * The value a snapshot spelled, or {@link #STRUCTURED} when it spelled something this module
     * does not know.
     *
     * <p>Falling back rather than failing is the right direction here: the alternative is that one
     * unrecognised enum member makes a past run's executions unreadable, and the steps — which are
     * what a tester actually needs — are present either way.
     */
    static StepFormat of(String value) {
        if (value == null) {
            return STRUCTURED;
        }
        for (StepFormat format : values()) {
            if (format.name().equals(value)) {
                return format;
            }
        }
        return STRUCTURED;
    }
}
