package com.tcms.tracker.release;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A story and a test case that proves it — story H7, and <strong>the rows {@code V3} created this
 * table for and left empty</strong>: <em>"TEST_CASE is the target type story H7 needs and it costs
 * one enum member; the rows arrive with M3."</em> They arrive here.
 *
 * <h2>The name, and it is not a preference</h2>
 *
 * <p>The table is {@code issue_link} and this class is <strong>not</strong> called {@code IssueLink},
 * because {@code com.tcms.documentation.IssueLinkView} / {@code IssueLinkInput} /
 * {@code IssueLinkService} / {@code IssueLinkController} already exist and are the <em>external</em>
 * tracker link — a Jira or Azure DevOps issue attached to a doc block. Two types called
 * {@code IssueLink} one import apart is how a wrong import survives review, and
 * {@code ArchitectureRulesTest} catches the collision only for Spring beans, not for records or
 * entities (plan 013 D-h).
 *
 * <h2>{@code targetType} is a String, and there is no enum over it</h2>
 *
 * <p>{@code V3} left the column with no {@code CHECK}, deliberately — <em>"nearly everything in a PM
 * system points at an issue"</em> — and three later modules will add target types to it. A Java enum
 * with one member would be a vocabulary claim this module has no right to make on a column it shares,
 * and every future member would have to be added here to be storable. The service writes the one
 * constant; the column stays open.
 *
 * <p><strong>There is no foreign key on {@code target_id}</strong> and there cannot be one: it is a
 * polymorphic reference across a module boundary, and scope §3 is explicit that this is what
 * {@code tracker} not being a foundation module costs. Integrity is
 * {@link VerificationService}'s — it validates every id against {@code test_case} before writing,
 * and a case deleted afterwards leaves a row this side renders as absent rather than as broken.
 */
@Entity
@Table(name = "issue_link")
@IdClass(VerificationLinkId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class VerificationLink {

    /** The one target type this module writes. Every other value belongs to a module not yet built. */
    static final String TEST_CASE = "TEST_CASE";

    @Id
    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Id
    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Id
    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** JPA only. */
    protected VerificationLink() {
    }

    static VerificationLink toCase(UUID companyId, UUID issueId, UUID caseId) {
        VerificationLink created = new VerificationLink();
        created.companyId = companyId;
        created.issueId = issueId;
        created.targetType = TEST_CASE;
        created.targetId = caseId;
        return created;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getTargetId() {
        return targetId;
    }
}
