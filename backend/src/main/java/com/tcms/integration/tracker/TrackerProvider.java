package com.tcms.integration.tracker;

/**
 * {@code TrackerProvider} in {@code docs/specs/openapi.yaml} — the adapters that exist.
 *
 * <p>An enum <strong>here</strong> and a {@code CHECK} on {@code tracker_binding.provider}, because
 * this value <em>selects an adapter that must exist</em>: a binding naming a provider with nothing
 * behind it is a row that can only fail, at call time, on somebody else's screen.
 *
 * <p>Deliberately <strong>not</strong> constrained on {@code defect_link.provider} or
 * {@code doc_link.external_provider}, which record what some adapter <em>said</em> (004 D9/D20).
 * That asymmetry is what makes GitHub and GitLab one adapter and one enum member each, with no
 * migration and no contract change.
 */
public enum TrackerProvider {

    /** Atlassian's hosted Jira. Basic auth: the user's own email, then their API token. */
    JIRA_CLOUD,

    /** Self-hosted Jira. Bearer: the user's own personal access token. */
    JIRA_DC,

    /** Azure DevOps. Bearer over an <strong>organization-scoped</strong> PAT — see 004's PAT section. */
    AZURE_DEVOPS
}
