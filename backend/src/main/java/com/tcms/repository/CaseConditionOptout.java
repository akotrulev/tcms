package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One case declining one inherited condition — the opt-out toggle plan §6f puts beside the
 * "inherited from <em>Billing</em>" label.
 *
 * <h2>Why inheritance needs an exception table at all</h2>
 *
 * <p>Suite-level attachment exists so a standing rule ("everything under Billing requires login")
 * keeps applying to cases added later, which point-in-time bulk assignment cannot do. But a
 * standing rule always has the one case it is wrong for — the case that tests the login screen
 * itself, which cannot require being logged in. Without an opt-out the author's only ways out are
 * to move the case out of the folder it belongs in, or to delete the rule for all two hundred
 * siblings. Both are worse than a row saying "not this one".
 *
 * <p>Absence of a row is therefore the default and the common state: a case inherits everything
 * above it unless something here says otherwise. The table is empty for almost every case, and
 * that is the shape it is designed for.
 *
 * <h2>Keyed on the attachment, not on the library condition</h2>
 *
 * <p>{@code suite_condition_id} rather than {@code condition_id}, and the difference is the whole
 * behaviour. "Log in" attached at <em>Billing</em> and again at <em>Billing › Refunds</em> is two
 * attachments; opting out of the first must not silently cancel the second, which a case in the
 * deeper folder may well need. Keying on the library entry would make one toggle mean "never
 * inherit this condition from anywhere", which is a different and much blunter feature.
 *
 * <p>{@code ON DELETE CASCADE} toward {@code suite_condition} in {@code V1} closes the other half:
 * detach the condition from the suite and the opt-outs against it go with it, rather than
 * lingering to suppress a future re-attachment nobody connected to them.
 */
@Entity
@Table(name = "case_condition_optout")
@IdClass(CaseConditionOptoutId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseConditionOptout {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Id
    @Column(name = "suite_condition_id", nullable = false, updatable = false)
    private UUID suiteConditionId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** JPA only. */
    protected CaseConditionOptout() {
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getSuiteConditionId() {
        return suiteConditionId;
    }
}
