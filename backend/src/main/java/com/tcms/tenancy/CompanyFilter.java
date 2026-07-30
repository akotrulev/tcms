package com.tcms.tenancy;

/**
 * Names for the Hibernate filter that scopes every company-owned entity to the active
 * company. The {@code @FilterDef} itself lives on
 * {@code com.tcms.tenancy.package-info}; each company-owned entity carries the
 * matching {@code @Filter}.
 *
 * <p>The filter is the <em>application</em> layer of isolation. It is not the
 * guarantee — RLS is (ERD §0.1). A Hibernate filter does not apply to native queries,
 * to {@code find()} by primary key, or to anything reached outside a Session, which is
 * exactly why the database enforces the same predicate independently.
 */
public final class CompanyFilter {

    /** Filter name, referenced by {@code @Filter(name = …)} on every company-owned entity. */
    public static final String NAME = "companyFilter";

    /** Bind parameter carrying the active company id. */
    public static final String PARAMETER = "companyId";

    /**
     * The predicate. {@code company_id} is a physical column name, so this string is
     * valid for every company-owned table by the ERD §0.1 naming rule — which is what
     * lets one {@code @FilterDef} serve all of them.
     */
    public static final String CONDITION = "company_id = :companyId";

    private CompanyFilter() {
    }
}
