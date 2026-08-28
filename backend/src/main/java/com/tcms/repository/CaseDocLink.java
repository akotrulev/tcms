package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One documentation link of a case, as this module is allowed to see it — a <strong>read
 * model</strong>, mirrored from {@code doc_link} and never authored here (plan §6i, §3 lesson 4).
 *
 * <p>{@link CaseHealth}'s sibling and the second read model {@code repository} owns. The difference
 * between them is why this is a table rather than three more columns on that one, and it is
 * arithmetic: a case has <em>zero or more</em> links and a column holds one value. {@code V46}'s
 * header carries the full argument, including what it kept from {@code V45} — the links and the
 * {@code OUTDATED_SPEC_CHANGED} signal derived from them are written by one service in one
 * transaction, so the panel's per-link marker and the case's chip cannot disagree on the screen
 * that renders both.
 *
 * <h2>There is no writer on this class, and there cannot be one</h2>
 *
 * <p>{@link DocumentationFacts} replaces this table wholesale per company in native SQL, exactly as
 * {@code ExecutionFacts} writes {@code case_health}. A mutator here would be a second author of a
 * mirrored value, and the value it mirrors lives in a module this one may not import — so what it
 * would actually author is a claim about somebody else's table that nothing could reconcile.
 *
 * <h2>The ids into {@code documentation} carry no foreign key, deliberately</h2>
 *
 * <p>{@link #pageId} and {@link #blockId} name rows in {@code doc_page} and {@code doc_block}, and
 * a cross-module FK is allowed only toward {@code tenancy}, {@code identity}, {@code project} and
 * {@code storage} (plan §3). {@code DocLink.sourceId} holds a {@code test_case} id with no FK from
 * the other side for the same reason and says so in its own header; this is that arrangement seen
 * in a mirror.
 *
 * <h2>{@link #relationship} and {@link #targetKind} are Strings, not enums</h2>
 *
 * <p>Both are {@code documentation}'s vocabulary — {@code LinkRelationship} and {@code doc_link}'s
 * {@code target_kind} CHECK. Re-declaring either as an enum here would put a second copy of another
 * module's value set in a module forbidden to import the first, and a value added there would
 * arrive as an {@code IllegalArgumentException} on a read rather than as a compile error. A mirror
 * copies what it was given.
 */
@Entity
@Table(name = "case_doc_link")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseDocLink {

    /** The {@code doc_link} row this mirrors — see the class header. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "target_kind", nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "relationship", nullable = false, updatable = false)
    private String relationship;

    /**
     * Null for an external target, and {@code NOT NULL} until {@code V47} — see that migration's
     * §1, and {@link #targetKind} for what tells the two apart.
     */
    @Column(name = "page_id", updatable = false)
    private UUID pageId;

    @Column(name = "page_key", updatable = false)
    private String pageKey;

    @Column(name = "page_title", updatable = false)
    private String pageTitle;

    @Column(name = "block_id", updatable = false)
    private UUID blockId;

    /** Null when the block has no text — see {@code V46}'s comment on the column. */
    @Column(name = "block_excerpt", updatable = false)
    private String blockExcerpt;

    /**
     * The six coordinates of an external target, null on a block link and mirrored exactly.
     *
     * <p>Unlike {@link #pageKey}, {@link #pageTitle} and {@link #blockExcerpt}, these are copies
     * that <em>cannot</em> go stale: {@code doc_link} has no update path at all, so what they mirror
     * is immutable for the life of the row ({@code V47} §2). A refresh rewrites them because it
     * rewrites the table, not because they could have changed.
     */
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
     * Which tracker an {@code EXTERNAL_ISSUE} row's issue lives in ({@code V75}) — copied, like the
     * six above, and interpreted by nothing in this module.
     */
    @Column(name = "external_provider", updatable = false)
    private String externalProvider;

    /**
     * The tracker's stable id. <strong>The only thing a degraded chip can render</strong>: there is
     * no key column here or in {@code doc_link}, because a key moves (004 D17), and no label is ever
     * derived from {@link #externalUrl}.
     */
    @Column(name = "external_issue_id", updatable = false)
    private String externalIssueId;

    /**
     * The tracker site the id belongs to. An id is only unique within a host, and this is also what
     * a reader matches against the project's bindings to decide whether a preview is possible at all
     * — there is no binding id on either side of the mirror (004 D18).
     */
    @Column(name = "external_host", updatable = false)
    private String externalHost;

    @Column(name = "external_url", updatable = false)
    private String externalUrl;

    /**
     * The linked page's content changed after this case's content last changed.
     *
     * <p>Always false for an external target — {@code V47} §3 argues that this is an answer and not
     * a default: the commit sha pins the lines, so what could fall behind is the pin, and this
     * column must not be read as saying anything about that.
     *
     * <p>Stored rather than compared at read time, which is the decision {@code V46} §4 argues: a
     * read-time comparison would be fresher and would disagree with the {@code OUTDATED_SPEC_CHANGED}
     * chip rendered one panel away for up to an hour. The cost is that editing a case does not clear
     * its markers until the next recompute — the behaviour {@code STALE_APPROVAL} has had since §157.
     */
    @Column(name = "stale", nullable = false, updatable = false)
    private boolean stale;

    /** JPA only. Every value on this row is written by {@link DocumentationFacts}, in SQL. */
    protected CaseDocLink() {
    }

    UUID getId() {
        return id;
    }

    // No getCompanyId(). The column is mapped because ddl-auto: validate and the Hibernate company
    // filter both need it, and nothing in this module has a question whose answer is a mirrored
    // row's company — a seam with no caller is what §152 and §153 made a build failure. CaseHealth
    // makes the same omission for the same reason.

    UUID getCaseId() {
        return caseId;
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

    String getExternalRepo() {
        return externalRepo;
    }

    String getExternalPath() {
        return externalPath;
    }

    Integer getExternalLineStart() {
        return externalLineStart;
    }

    Integer getExternalLineEnd() {
        return externalLineEnd;
    }

    String getExternalCommitSha() {
        return externalCommitSha;
    }

    String getExternalUrl() {
        return externalUrl;
    }
}
