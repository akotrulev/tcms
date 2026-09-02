package com.tcms.tracker.imports;

/**
 * The source system — {@code ImportProvider} on the wire, {@code import_run.provider} at rest.
 *
 * <p>Deliberately <strong>not</strong> {@code integration.tracker.TrackerProvider}, which spells
 * Data Center {@code JIRA_DC} and is in a module this one may not import (D-a). The two sets answer
 * different questions as well: that one selects an adapter for reading one issue on a case page,
 * this one selects a whole-project reader with paging, and a provider can plausibly appear in
 * either without the other.
 */
public enum ImportProvider {

    /**
     * Atlassian-hosted. Authenticates with {@code Basic base64(accountEmail:apiToken)}, which is
     * the only reason {@code accountEmail} exists on {@link ImportConnection}.
     */
    JIRA_CLOUD,

    /** Self-hosted Jira. A Bearer PAT, and {@code accountEmail} is ignored. */
    JIRA_DATA_CENTER,

    /** T5's, through this same port. A Bearer PAT over an org-scoped base URL. */
    AZURE_DEVOPS
}
