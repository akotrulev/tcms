/**
 * Tenancy — the company boundary.
 *
 * <p>A company is a separate <strong>client organisation</strong>, not a business unit
 * (plan §6s). This module is depended on by every other module and depends on none.
 *
 * <p>It owns the three independent layers that keep clients apart:
 * <ol>
 *   <li>{@link com.tcms.tenancy.CompanyContext} → the Hibernate filter (application)</li>
 *   <li>{@link com.tcms.tenancy.CompanyAwareTransactionManager} → the
 *       {@code app.current_company} GUC that Postgres RLS reads (database)</li>
 *   <li>the two-company isolation harness in {@code src/test} (proof)</li>
 * </ol>
 *
 * <p>Three layers because the first two can both be forgotten and the third is what
 * notices.
 */
@org.hibernate.annotations.FilterDef(
        name = com.tcms.tenancy.CompanyFilter.NAME,
        parameters = @org.hibernate.annotations.ParamDef(
                name = com.tcms.tenancy.CompanyFilter.PARAMETER,
                type = java.util.UUID.class),
        defaultCondition = com.tcms.tenancy.CompanyFilter.CONDITION)
package com.tcms.tenancy;
