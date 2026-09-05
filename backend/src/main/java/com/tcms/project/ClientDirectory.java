package com.tcms.project;

import java.util.Collection;
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
}
