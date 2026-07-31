package com.tcms.repository;

/**
 * The Gherkin keyword on one step — {@code TestStep.keyword} in {@code docs/specs/openapi.yaml},
 * and the {@code keyword} check constraint on {@code test_step} in {@code V1}.
 *
 * <p><strong>Nullable, and the null is load-bearing.</strong> One table serves both step formats
 * (plan §6a), and {@code V1} says so out loud: "non-null identifies a Gherkin step". A structured
 * step has no keyword because there is nothing for it to mean — numbered actions are not
 * Given/When/Then — so the absence is the format, not a missing value someone forgot to set.
 * Defaulting it to {@code GIVEN} would turn every structured case into a malformed Gherkin one.
 *
 * <p>{@code AND} and {@code BUT} are members rather than being folded into the preceding keyword.
 * They are what an author wrote and what a {@code .feature} export has to reproduce; collapsing
 * them at read time would make the round trip lossy for a purely cosmetic tidy-up.
 */
public enum StepKeyword {
    GIVEN,
    WHEN,
    THEN,
    AND,
    BUT
}
