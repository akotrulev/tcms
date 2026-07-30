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
 *
 * <h2>It depends on nothing, and that is declared, not merely true</h2>
 *
 * <p>{@code allowedDependencies = {}} is Spring Modulith's spelling of "no module
 * dependencies at all" (the attribute's default is <em>unrestricted</em>, so leaving it
 * off would assert nothing). The property matters because everything else depends on
 * this module: an edge from {@code tenancy} to any other module makes the isolation
 * layer participate in a cycle, and a cycle is how "tenancy is established before
 * anything reads a company-owned row" stops being guaranteeable. Enforced by
 * {@code ModuleStructureTest} and, from the other side, {@code ArchitectureRulesTest}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
@org.hibernate.annotations.FilterDef(
        name = com.tcms.tenancy.CompanyFilter.NAME,
        parameters = @org.hibernate.annotations.ParamDef(
                name = com.tcms.tenancy.CompanyFilter.PARAMETER,
                type = java.util.UUID.class),
        defaultCondition = com.tcms.tenancy.CompanyFilter.CONDITION)
package com.tcms.tenancy;
