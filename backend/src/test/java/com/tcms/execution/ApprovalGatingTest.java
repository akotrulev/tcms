package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §6g's verdict — pure logic, no context.
 *
 * <p>{@code ApprovalGating} exists to hold two invariants that are otherwise prose in its javadoc,
 * and prose does not fail a build. Both are silent when broken: a refusal with nothing to say leaves
 * somebody unable to start work with no idea what to do next, and an {@code overridden} flag that
 * means "the caller sent the flag" rather than "the gate fired and was overridden" writes an audit
 * line accusing somebody of bypassing a policy they in fact complied with.
 *
 * <p>Three lines of production code, and the check is here rather than in
 * {@code RunCreateEndpointTest} for the reason the class itself is separate from its one caller:
 * §99's tag-scoped runs add a second gating rule, and it is the second caller that gets these wrong.
 */
@DisplayName("ApprovalGating.Verdict — what proceeding and overriding mean")
class ApprovalGatingTest {

    @Test
    @DisplayName("PROCEED refuses nothing and overrides nothing")
    void proceedIsNeitherARefusalNorAnOverride() {
        // The gate that does not bind and the gate that binds and is satisfied are one value —
        // there is nothing for an audit record to say about either.
        assertThat(ApprovalGating.Verdict.PROCEED.refusal()).isNull();
        assertThat(ApprovalGating.Verdict.PROCEED.overridden()).isFalse();
    }

    @Test
    @DisplayName("a fired gate the caller may not override refuses, and names what is wrong")
    void firedWithoutPermissionRefuses() {
        var verdict = ApprovalGating.Verdict.fired(false, "2 cases are not approved: PAY-1, PAY-4");

        assertThat(verdict.refusal()).isNotBlank();
        // Not overridden: nothing was bypassed, so the audit field stays false.
        assertThat(verdict.overridden()).isFalse();
    }

    @Test
    @DisplayName("a fired gate the caller may override proceeds AND records the override")
    void firedWithPermissionOverrides() {
        var verdict = ApprovalGating.Verdict.fired(true, "2 cases are not approved: PAY-1, PAY-4");

        // Both halves. Proceeding without recording it loses §6g's word "documented"; recording it
        // without proceeding would be a refusal that claims to have been overridden.
        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.overridden()).isTrue();
    }

    @Test
    @DisplayName("overridden and refusal are never both set — refusal == null IS the proceed signal")
    void theTwoFieldsAreNeverBothMeaningful() {
        // Callers read refusal == null to decide whether to continue, and overridden only after
        // that. A verdict with both set would be read as "proceed" by the first check and as an
        // override by the second, which is the shape the record's single factory prevents.
        assertThat(ApprovalGating.Verdict.fired(true, "x"))
                .satisfies(v -> assertThat(v.refusal() == null || !v.overridden()).isTrue());
        assertThat(ApprovalGating.Verdict.fired(false, "x"))
                .satisfies(v -> assertThat(v.refusal() == null || !v.overridden()).isTrue());
    }
}
