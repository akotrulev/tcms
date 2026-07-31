package com.tcms.project;

/**
 * {@code ProjectInput} in {@code docs/specs/openapi.yaml} — the body of {@code POST /projects}.
 *
 * <p>Three fields, and what is <strong>absent</strong> is the reason this is a separate type from
 * {@link Project}: no {@code id}, no {@code archived}, no {@code caseCount}, no {@code myRole} and
 * no {@code companyId}. Each of those is server-owned, computed per caller, or the tenancy
 * discriminator itself — and a field on an input schema is a field somebody eventually binds. A
 * create that accepted {@code archived} would let a project be born retired; one that accepted
 * {@code companyId} would be the whole attack (ERD §0.1 point 2).
 *
 * @param key         client-supplied, unlike every display key in the product. See
 *                    {@link ProjectEdit} for the difference and why it is not allocated.
 * @param description nullable, per the contract
 */
public record ProjectWriteRequest(String key, String name, String description) {
}
