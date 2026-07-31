package com.tcms.execution;

/**
 * {@code Criterion} in {@code docs/specs/openapi.yaml} — one machine-evaluable entry or exit gate.
 *
 * <p>Structured rather than prose so the plan page can be a gate rather than a paragraph (plan
 * §6p): {@code {"metric":"pass_rate","op":">=","value":95}} can be checked, "we should be mostly
 * green" cannot.
 *
 * @param met    <strong>null in this slice, and that is honest rather than pending.</strong>
 *               Evaluating a criterion needs the plan's aggregated results, which is the statistics
 *               endpoint this slice deliberately does not implement. The contract marks the field
 *               nullable and the SPA renders a null as "not evaluated"; returning {@code false}
 *               would say the gate had been checked and failed.
 * @param actual null for the same reason and always together with {@link #met} — the two are set
 *               by one computation and neither is meaningful alone.
 */
public record CriterionView(
        String metric, String op, double value, String label, Boolean met, Double actual) {
}
