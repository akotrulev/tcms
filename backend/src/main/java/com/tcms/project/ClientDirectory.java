package com.tcms.project;

import com.tcms.api.ClientRef;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>What client is this, by name</strong> — asked by the module that stores the id and may not
 * see the module that owns the row.
 *
 * <h2>Why this declaration is here and not in {@code crm}</h2>
 *
 * <p>{@code project.client_id} has been a column since {@code V2} and gains its foreign key in
 * {@code V11}. {@link ProjectView} must serve the client's <em>name</em>: the projects list renders
 * it per card and the overview draws it as a chip, and both are read by anybody who can see the
 * project. The row lives in {@code crm}, which is not one of the five foundation modules, so
 * {@code project} may not import it.
 *
 * <p>So the declaration lives here, in a module the implementor already depends on, and {@code crm}
 * supplies it — the same arrangement as {@link PeopleDirectory} and {@link TeamDirectory} (supplied
 * by {@code identity}) and {@link TrackerBindingDirectory} (supplied by {@code integration}), whose
 * own comment states the argument in full. Fourth instance of the pattern in this package.
 *
 * <p><strong>Not a second JPA mapping.</strong> {@link Project} maps {@code client_id} as a plain
 * {@code UUID} rather than as a {@code @ManyToOne} to {@code crm.Client} — an association would be
 * the import this interface exists to avoid, and {@code ddl-auto: validate} would then check two
 * mappings over one table, which is {@code execution.CaseFacts}' recorded reason for refusing that
 * shape.
 *
 * <h2>It resolves and does not authorize</h2>
 *
 * <p>The caller has already gated the project. This answers a factual question about a column in it,
 * inside the caller's transaction and therefore under RLS — which is why {@link #namesOf} may return
 * a map smaller than the set it was given, and why that is not an error.
 *
 * <h2>A missing entry and a null column render the same, deliberately</h2>
 *
 * <p>Both mean <em>this project has no client to show</em>, and neither is a deficiency: a company
 * building its own products has no clients at all, and scope D1 rule 1 refuses the synthetic
 * "Internal" row that would make the column non-null. Nothing in this product marks a project as
 * missing a client, so a caller has no reason to tell the two apart.
 */
public interface ClientDirectory {

    /**
     * The name of each id that names a visible, in-company client.
     *
     * <p>An id with no entry is one this caller cannot see. Callers render no client for it, which
     * is what they render for a {@code null} column too — see the class comment.
     *
     * @param clientIds may be empty, and is on every projects list in a company with no clients
     */
    Map<UUID, String> namesOf(Collection<UUID> clientIds);

    /**
     * Whether this id names a client in the current company — the write-path check behind 018 D-l.
     *
     * <p><strong>The foreign key is not this check.</strong> {@code fk_project_client} is on
     * {@code id} alone, and referential integrity is not subject to RLS, so a client id belonging to
     * another company would satisfy the constraint and attach one company's project to another's
     * customer. This runs under RLS and answers false for it, which is what lets the caller return a
     * {@code 400} with a field error rather than a {@code 500} that would also be an oracle for what
     * exists in another client's tenancy (§6t).
     */
    boolean existsInCompany(UUID clientId);

    /**
     * Every ACTIVE client in the current company, by name — the third question a module that may not
     * see {@code crm} has had to ask.
     *
     * <p>Added by 019 T3 for {@code GET /rate-cards}, which answers <strong>one slot per active
     * client, card or {@code null}</strong>: {@code billing.html}'s table ends on a Kessler Health
     * row reading <em>no rate card yet</em> beside a {@code Set} button, and a table built from the
     * cards alone could not draw it. So the population is the clients, and the cards are what is
     * joined on — which means the caller needs the clients and {@code billing} may not read them.
     *
     * <p><strong>It grew here rather than in {@code billing}</strong>, and that is the decision
     * rather than the convenience. A {@code billing.ClientFacts} reading the {@code client} table
     * would have been the {@code *Facts} pattern pointed the wrong way: those five all read
     * downstream-to-upstream in native SQL, and {@code project} → {@code crm} is the reverse, held
     * by this interface precisely because ArchUnit cannot see a SQL crossing. A rule that holds only
     * while the checker is blind is one somebody breaks by reading the code.
     *
     * <p>ARCHIVED clients are left out, which is the same filter {@code ClientService.pickableFor}
     * applies and very nearly the same reason: archiving is how a client stops appearing in the
     * places you would start something new from, and a rate card is something new. The asymmetry
     * with the picker is deliberate — that one adds the project's own archived client back, because
     * dropping it would silently detach the project on the next save. Nothing is silently detached
     * here: an archived client's existing card is still readable by id, still prices its issued
     * work, and simply has no row offering to create one.
     *
     * <p>Empty is a complete answer, not an empty state — a company building its own products never
     * records a client (scope D1 rule 2), and {@code GET /rate-cards} answers {@code []} for it with
     * nothing marked as missing.
     */
    List<ClientRef> activeClients();

    /**
     * This client's contacts, in the order the client page renders them.
     *
     * <p>Added by 019 T4 for {@code InvoiceDetail.billTo} (019 D-u). {@code billing} may not import
     * {@code crm}, and the alternative was a {@code billing.ClientFacts} reading
     * {@code client_contact} — the {@code *Facts} pattern pointed the wrong way, which is exactly
     * what {@link #activeClients()} above declines for the {@code client} table one method up.
     *
     * <p><strong>The whole list, and not "the billing contact".</strong> {@code client_contact}
     * carries a free-text {@code role} and no designated-contact column, so a port method that
     * picked one would be deciding a CRM concept on a billing screen's behalf. Billing takes the
     * first for a new draft and then <em>stores that id</em>, so an invoice keeps addressing the
     * person it was raised for even after the client's contacts change — and resolves that stored id
     * from this same list on every read.
     *
     * <p>Ordered by name, which is what makes "the first" a stable answer rather than whichever row
     * the database returned. Empty is a state and not a blocker: issuing sends nothing, so there is
     * nothing an absent address prevents.
     *
     * <p>Runs inside the caller's transaction, so RLS applies and a client in another company
     * answers empty rather than answering.
     */
    List<ClientContactRef> contactsOf(UUID clientId);
}
