package com.tcms.execution;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One line of a plan's scope — a suite, a case, a tag, or nothing at all when the row exists only to
 * name an empty section ({@code ck_plan_scope_target}, widened in V32).
 *
 * <h2>A section is a column here and not a table, which is what makes the empty one awkward</h2>
 *
 * <p>§97's sub-feature sections have no key, no lifecycle and no existence outside their plan, so
 * they are carried on the member rows rather than in a table of their own — which keeps the RLS
 * policy count at 61 and keeps a section from looking like a suite. The cost is that a section with
 * <strong>no members</strong> has nowhere to live except a row that names it and targets nothing,
 * and the contract requires that state: an author names the sub-feature before filling it, and
 * dropping empty sections on save would delete the heading they had just typed.
 *
 * <p>So the constraint is <em>at most one target, and a row with no target must name a section</em>
 * — exactly-one for an ordinary line, a placeholder for the other case. {@link #ofSection} is the
 * only factory that may produce the second, and it says so in its name rather than being reachable
 * by passing three nulls to a general one.
 *
 * <h2>Two ids into {@code repository}, and this entity does not read either of them</h2>
 *
 * <p>{@code suite_id} and {@code case_id} are foreign keys into the module this one may not import.
 * That is the schema's deliberate shape (ERD §5) and the package comment's "a shared row is not a
 * shared class": this entity <strong>stores</strong> the ids and never resolves them. Every question
 * that needs the other side — is this suite live, which cases does this folder contain — goes
 * through {@link CaseFacts}, which stays the one crossing.
 *
 * <p>Which is why there is no {@code @ManyToOne} here. A JPA association would be the code edge
 * ArchUnit forbids, arrived at by autocomplete rather than by decision.
 *
 * <h2>A suite item is the folder AND its subtree, and that is not stored</h2>
 *
 * <p>Nothing here records the expansion. A plan whose scope names "Payments" covers whatever is in
 * Payments <em>when the question is asked</em> — which is what the person who added the folder
 * meant, and it is why {@link CaseFacts#caseCountsByPlan} walks the {@code ltree} path on every
 * read rather than materialising a membership list that would be wrong by the next case authored.
 *
 * <p>The run is where that stops being true: creating one resolves the expansion once and writes an
 * execution per case, so the run's checklist is fixed even though the plan's scope is not. Both are
 * correct and they are different questions — "what is in scope" versus "what did we agree to test
 * on this pass".
 */
@Entity
@Table(name = "plan_scope_item")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class PlanScopeItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    /** Null unless this item names a suite. At most one target is set. */
    @Column(name = "suite_id", updatable = false)
    private UUID suiteId;

    /** Null unless this item names a case. */
    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    /**
     * Null unless this item names a tag — §96's {@code case_tag}, matched exactly, the same arm
     * {@code suite_tag_rule} uses (§97 D8). Its members are a query, so "add by tag" on a plan
     * section and "add by tag" on a suite are the same act with the same semantics.
     */
    @Column(name = "tag", updatable = false)
    private String tag;

    /**
     * The sub-feature this line belongs to, or null for the <strong>unsectioned</strong> group —
     * which is what every plan written before §97 holds, and what a plan gets when its author never
     * subdivides it.
     */
    @Column(name = "section_name", updatable = false)
    private String sectionName;

    /**
     * Author order among the sections, assigned by the server from the order submitted.
     *
     * <p>Zero for every unsectioned row, which is not an ordering claim: the scope is a
     * <em>set</em> and {@link PlanScope} sorts what it hashes, because two folders listed in either
     * order are one scope and a hash over insertion order would move without the plan changing.
     * This column orders the <em>sections</em> and nothing inside them.
     */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    /** JPA only. */
    protected PlanScopeItem() {
    }

    /** A suite item — the folder and, at read time, everything beneath it. */
    static PlanScopeItem ofSuite(UUID planId, UUID suiteId, String sectionName, int ordinal) {
        return of(planId, suiteId, null, null, sectionName, ordinal);
    }

    /** A case item — exactly that case. */
    static PlanScopeItem ofCase(UUID planId, UUID caseId, String sectionName, int ordinal) {
        return of(planId, null, caseId, null, sectionName, ordinal);
    }

    /** A tag item — whichever live cases carry the tag when the question is asked. */
    static PlanScopeItem ofTag(UUID planId, String tag, String sectionName, int ordinal) {
        return of(planId, null, null, tag, sectionName, ordinal);
    }

    /**
     * The placeholder for a section nobody has filled yet — a row that names the section and targets
     * nothing.
     *
     * <p>Its own factory rather than a null-target call to the others, because it is the one row in
     * this table that expands to no cases, and a caller producing it by accident is exactly the
     * nonsense {@code ck_plan_scope_target} exists to catch. Written only when the section is
     * genuinely empty; a filled section's rows carry its name and this row is not among them.
     */
    static PlanScopeItem ofSection(UUID planId, String sectionName, int ordinal) {
        return of(planId, null, null, null, sectionName, ordinal);
    }

    /**
     * @throws IllegalArgumentException if more than one target is set, or if a targetless row names
     *         no section — the same condition {@code ck_plan_scope_target} enforces, checked here so
     *         the failure names the factory above rather than arriving as a constraint violation at
     *         flush with no indication of which caller built the row.
     */
    private static PlanScopeItem of(
            UUID planId, UUID suiteId, UUID caseId, String tag, String sectionName, int ordinal) {

        int targets = (suiteId == null ? 0 : 1) + (caseId == null ? 0 : 1) + (tag == null ? 0 : 1);
        if (targets > 1) {
            throw new IllegalArgumentException(
                    "A plan scope item names a suite, a case or a tag — never more than one");
        }
        if (targets == 0 && sectionName == null) {
            throw new IllegalArgumentException(
                    "A plan scope item with no target must name the section it holds open");
        }
        PlanScopeItem item = new PlanScopeItem();
        item.id = UUID.randomUUID();
        item.companyId = CompanyContext.require();
        item.planId = planId;
        item.suiteId = suiteId;
        item.caseId = caseId;
        item.tag = tag;
        item.sectionName = sectionName;
        item.ordinal = ordinal;
        return item;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getSuiteId() {
        return suiteId;
    }

    UUID getCaseId() {
        return caseId;
    }

    String getTag() {
        return tag;
    }

    String getSectionName() {
        return sectionName;
    }

    int getOrdinal() {
        return ordinal;
    }
}
