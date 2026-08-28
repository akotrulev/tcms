package com.tcms.project;

import java.util.Optional;
import java.util.UUID;

/**
 * <strong>What tracker does this binding name</strong> — asked by two modules that may not see the
 * one that answers.
 *
 * <h2>The two callers, and why neither can import {@code integration}</h2>
 *
 * <ul>
 *   <li>{@code execution.DefectLinkService}. {@code DefectLinkInput} gained an optional
 *       {@code bindingId} in 004, and the whole point of the field is that the server states the
 *       provider it <em>actually called</em> rather than accepting a caller's claim. Resolving it
 *       needs {@code tracker_binding}, which lives in {@code integration}.</li>
 *   <li>{@code documentation.IssueLinkService}, which records a link's provider and host from the
 *       binding the caller named, so the row survives that binding's deletion (004 D18).</li>
 * </ul>
 *
 * <p>A cross-module dependency may point only at the five foundation modules, and {@code integration}
 * is emphatically not one — its own package comment says so in capitals. So the declaration lives
 * here, in a module both callers already depend on, and {@code integration} supplies it: the same
 * arrangement as {@link PeopleDirectory} and {@link ProjectAccess}, and the remedy this package's
 * comment prescribes.
 *
 * <h2>It resolves and does not authorize</h2>
 *
 * <p>The caller has already gated the project. This answers a factual question about a row in it.
 */
public interface TrackerBindingDirectory {

    /**
     * The provider and host a binding names, or empty when it names none in this project.
     *
     * <p>Empty is what a caller gets for a binding id that belongs to a sibling project, one that
     * has been deleted, and one that never existed — one answer, for {@code GateResult}'s reason:
     * telling them apart makes the endpoint an oracle for rows in projects the caller cannot see.
     *
     * @param projectId scopes the lookup, so an id from elsewhere cannot be resolved through a path
     *                  naming this project
     */
    Optional<TrackerTarget> resolve(UUID projectId, UUID bindingId);

    /**
     * The binding this project currently has for a {@code (provider, host)} pair, or empty.
     *
     * <p>The <strong>read-time</strong> half of 004 D18, and the reason {@code doc_link} carries a
     * host instead of a binding id. A link records what its binding said when it was made; the
     * reader asks this whether any binding still matches. Empty means the binding was deleted, and
     * the chip keeps its href and stops previewing — rather than the row having been cascaded away
     * with somebody's traceability record inside it.
     *
     * <p>Several bindings can name one host (a project bound to two Jira projects on one site). Any
     * of them proves the host is still reachable and any of them can serve a preview, so this
     * answers one — the default first, then the oldest — rather than a list the caller would have to
     * choose from with no basis for choosing.
     */
    Optional<TrackerTarget> forHost(UUID projectId, String provider, String host);

    /**
     * @param provider the tracker's name as {@code tracker_binding.provider} spells it —
     *                 {@code JIRA_CLOUD}, {@code JIRA_DC}, {@code AZURE_DEVOPS}. A {@code String}
     *                 rather than the enum because that enum lives in {@code integration}, and
     *                 declaring a copy of it here would put a second spelling of the value in a
     *                 foundation module. Both callers store it as free text (004 D9), so nothing
     *                 downstream branches on it.
     * @param host     {@code base_url}. What {@code doc_link.external_host} records, and what a
     *                 reader matches back against the project's current bindings — the column that
     *                 exists so no foreign key has to.
     */
    record TrackerTarget(UUID bindingId, String provider, String host) {
    }
}
