package com.tcms.execution;

/**
 * {@code Criterion} in {@code docs/specs/openapi.yaml} — one machine-evaluable entry or exit gate.
 *
 * <p>Structured rather than prose so the plan page can be a gate rather than a paragraph (plan
 * §6p): {@code {"metric":"pass_rate","op":">=","value":95}} can be checked, "we should be mostly
 * green" cannot.
 *
 * @param met    <strong>null means "not evaluated", which is not "unmet".</strong> Set by
 *               {@link PlanCriteria#evaluate} on the statistics endpoint, for the metrics that
 *               endpoint's own numbers support — {@code pass_rate} and {@code coverage}. The other
 *               three plans are written with need machinery the product does not have yet
 *               ({@code open_critical} the M4 issue tracker, {@code cases_approved} the review
 *               workflow, {@code build_available} nothing at all), and returning {@code false} for
 *               those would say the gate had been checked and failed.
 *
 *               <p>Always null on the plan <em>detail</em> endpoints, which serve a plan's criteria
 *               without computing anything: a plan list resolving every plan's results would be the
 *               statistics endpoint run once per row.
 * @param actual null and non-null exactly with {@link #met} — the two are set by one computation and
 *               neither is meaningful alone. Rounded to one decimal; the comparison behind
 *               {@link #met} is not, so a 94.96 against a {@code >= 95} gate reads "95.0" and
 *               "unmet", which is the correct pair.
 */
public record CriterionView(
        String metric, String op, double value, String label, Boolean met, Double actual) {
}
