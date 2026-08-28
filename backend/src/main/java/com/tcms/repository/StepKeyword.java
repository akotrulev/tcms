package com.tcms.repository;

/**
 * The Gherkin keyword on one step — the {@code StepKeyword} schema in
 * {@code docs/specs/openapi.yaml}, and the {@code keyword} check constraint on {@code test_step}
 * in {@code V1}.
 *
 * <p>{@code StepKeyword} became a <strong>named</strong> schema in §255 (it was inlined on
 * {@code TestStep} and a bare {@code string} on {@code TestStepInput}), so this type is now paired
 * in {@code ContractEnumTest.OWNERS} and a member added on one side fails the build.
 *
 * <p><strong>Nullable, and the null is load-bearing.</strong> One table serves both lenses, and
 * {@code V1} says so out loud: "non-null identifies a Gherkin step". A structured
 * step has no keyword because there is nothing for it to mean — numbered actions are not
 * Given/When/Then — so the absence is the format, not a missing value someone forgot to set.
 * Defaulting it to {@code GIVEN} would turn every structured case into a malformed Gherkin one.
 *
 * <p>In the {@code GHERKIN} lens the absence is refused instead: {@link CaseEdit} answers
 * <em>"Step 3 needs a keyword."</em>, and a first step of {@code AND} or {@code BUT} — a
 * continuation with nothing to continue — is refused too.
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
