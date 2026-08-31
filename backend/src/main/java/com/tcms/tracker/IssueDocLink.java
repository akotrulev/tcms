package com.tcms.tracker;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One documentation link of an issue, as this module is allowed to see it — a <strong>read
 * model</strong>, mirrored from {@code doc_link} and never authored here (D-e, scope §3).
 *
 * <p>{@code repository.CaseDocLink} field for field, and that is the whole design: the same table
 * seen from the issue end instead of the case end, so the two mirrors go stale in the same way, are
 * refreshed by the same event, and answer {@code stale} by the same rule. A second, cleverer shape
 * here would be a second thing to reason about on the day the two disagreed.
 *
 * <h2>There is no writer on this class, and there cannot be one</h2>
 *
 * <p>{@link IssueDocLinkFacts} replaces this table wholesale per company in native SQL. A mutator
 * here would be a second author of a mirrored value, and the value it mirrors lives in a module this
 * one may not import — so what it would actually author is a claim about somebody else's table that
 * nothing could reconcile. <strong>The write is not in this module at all</strong>: creating the
 * link is {@code documentation}'s own endpoint, because that module owns {@code doc_link}.
 *
 * <h2>The ids into {@code documentation} carry no foreign key, deliberately</h2>
 *
 * <p>{@link #pageId} and {@link #blockId} name rows in {@code doc_page} and {@code doc_block}, and a
 * cross-module FK is allowed only toward the five foundation modules (plan §3). {@code CaseDocLink}
 * makes the same omission for the same reason.
 *
 * <h2>{@link #relationship} and {@link #targetKind} are Strings, not enums</h2>
 *
 * <p>Both are {@code documentation}'s vocabulary. Re-declaring either as an enum here would put a
 * second copy of another module's value set in a module forbidden to import the first, and a value
 * added there would arrive as an {@code IllegalArgumentException} on a read rather than as a compile
 * error. A mirror copies what it was given.
 */
@Entity
@Table(name = "issue_doc_link")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IssueDocLink {

    /** The {@code doc_link} row this mirrors, carried rather than minted — see the class header. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private UUID issueId;

    @Column(name = "target_kind", nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "relationship", nullable = false, updatable = false)
    private String relationship;

    /** Null for an external target — see {@code ck_issue_doc_link_target}. */
    @Column(name = "page_id", updatable = false)
    private UUID pageId;

    @Column(name = "page_key", updatable = false)
    private String pageKey;

    @Column(name = "page_title", updatable = false)
    private String pageTitle;

    @Column(name = "block_id", updatable = false)
    private UUID blockId;

    /** Null when the block has no text — {@code case_doc_link}'s spelling of an absent excerpt. */
    @Column(name = "block_excerpt", updatable = false)
    private String blockExcerpt;

    /**
     * The linked page changed after this issue last did.
     *
     * <p>Always false for an external target, and the reason differs by kind: a git range is pinned
     * by its sha, so what could fall behind is the pin, and an external issue carries no derived
     * meaning for a freshness marker to be about. Stored rather than compared at read time, exactly
     * as {@code case_doc_link.stale} is, so a marker and anything derived beside it cannot disagree.
     */
    @Column(name = "stale", nullable = false, updatable = false)
    private boolean stale;

    @Column(name = "external_repo", updatable = false)
    private String externalRepo;

    @Column(name = "external_path", updatable = false)
    private String externalPath;

    @Column(name = "external_line_start", updatable = false)
    private Integer externalLineStart;

    @Column(name = "external_line_end", updatable = false)
    private Integer externalLineEnd;

    @Column(name = "external_commit_sha", updatable = false)
    private String externalCommitSha;

    /**
     * The three {@code EXTERNAL_ISSUE} coordinates, mirrored and interpreted by nothing here.
     *
     * <p><strong>Readerless by decision, like their counterparts on {@code case_doc_link}.</strong>
     * The contract's {@code IssueDocLink} renders a page, a block excerpt and an external URL; the
     * provider, host and tracker id are what {@code documentation}'s own {@code DocLink} renders a
     * degraded chip from, one table over. Dropping them here would make the mirror stop being a
     * mirror, which is the property that keeps the module boundary intact.
     */
    @Column(name = "external_provider", updatable = false)
    private String externalProvider;

    @Column(name = "external_issue_id", updatable = false)
    private String externalIssueId;

    @Column(name = "external_host", updatable = false)
    private String externalHost;

    @Column(name = "external_url", updatable = false)
    private String externalUrl;

    /** JPA only. Every value on this row is written by {@link IssueDocLinkFacts}, in SQL. */
    protected IssueDocLink() {
    }

    UUID getId() {
        return id;
    }

    // No getCompanyId(). The column is mapped because ddl-auto: validate and the Hibernate company
    // filter both need it, and nothing here has a question whose answer is a mirrored row's
    // company — CaseDocLink makes the same omission for the same reason.

    UUID getIssueId() {
        return issueId;
    }

    String getTargetKind() {
        return targetKind;
    }

    String getRelationship() {
        return relationship;
    }

    UUID getPageId() {
        return pageId;
    }

    String getPageKey() {
        return pageKey;
    }

    String getPageTitle() {
        return pageTitle;
    }

    UUID getBlockId() {
        return blockId;
    }

    String getBlockExcerpt() {
        return blockExcerpt;
    }

    boolean isStale() {
        return stale;
    }

    String getExternalUrl() {
        return externalUrl;
    }
}
