package com.tcms.repository;

/**
 * {@code StepFormat} in {@code docs/specs/openapi.yaml} — structured steps or Gherkin, per case
 * (plan §6a).
 *
 * <p>Per <em>case</em>, not per project: the plan's dual-format decision is that one repository
 * holds both, because a team that automates its API suite in Gherkin still writes its exploratory
 * regression cases as numbered steps. It is on the summary rather than only on the detail because
 * the grid has to show which editor a row opens.
 */
public enum StepFormat {
    STRUCTURED,
    GHERKIN
}
