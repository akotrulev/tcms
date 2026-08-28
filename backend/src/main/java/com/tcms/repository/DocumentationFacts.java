package com.tcms.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a {@code documentation} table</strong> — the
 * third seam of its kind here, and the exact mirror of {@code documentation.CatalogueFacts}, which
 * is the one place that module names a {@code repository} table.
 *
 * <p>{@code ExecutionFacts}' shape and its argument. ArchUnit permits a cross-module dependency
 * only toward {@code api}, {@code tenancy}, {@code identity}, {@code project} and {@code storage}
 * (plan §3), so neither this module nor {@code documentation} may call the other; each reads rows it
 * holds ids into, through exactly one file, so a reviewer finds every crossing by opening two. A
 * second class here naming {@code doc_link} would end that property, and it would end it for the
 * most tempting reason there is — that the new query is short.
 *
 * <h2>Unlike its two siblings, what crosses here is a row and not a boolean</h2>
 *
 * <p>{@code ApprovalFacts} folds one comparison into a statement and {@code ExecutionFacts} carries
 * three values off one execution. This one copies a page's key, its title and a block's text into a
 * table this module owns, because the case detail has to <em>render</em> them and the request path
 * may not read where they live. That makes {@code case_doc_link} a denormalized copy rather than a
 * derivation, with the staleness window every copy has — see {@code V46} §3.
 *
 * <p>What has not changed is the property the seam exists for: <strong>no request ever reaches this
 * file</strong>, and no {@code documentation} row moves through the application on a request path.
 * The same forbidden edit applies here as there — a value-returning helper ("just fetch the links
 * for these forty case ids") would satisfy every ArchUnit rule in this repository and give away the
 * thing they protect, and on the detail endpoint it would be a single query, which is exactly why
 * somebody will propose it.
 *
 * <h2>Native SQL, and why there is no entity for {@code doc_link} here</h2>
 *
 * <p>{@code ExecutionFacts}' argument verbatim: a second JPA entity mapped over a table another
 * module owns is one table with two sets of column expectations for {@code ddl-auto: validate} to
 * check, and two places a mapping can drift. {@link CaseDocLink} is <em>not</em> that — it maps
 * {@code case_doc_link}, a table this module owns outright.
 *
 * <p>RLS covers every statement here, on both sides. This runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}: the {@code SELECT}
 * can only see the open company's links, the {@code INSERT} can only write that company's rows, and
 * — the one that matters most for a statement that <em>deletes</em> — the {@code DELETE} can only
 * reach that company's rows. {@code CaseHealthRecomputeTest} asserts that with two companies rather
 * than trusting this paragraph.
 */
@Component
class DocumentationFacts {

    /**
     * The excerpt bound, and it is <strong>a second spelling of a rule that already exists</strong>.
     *
     * <p>{@code reporting.CoverageMatrixReport.STATEMENT_LIMIT} is 160, collapses whitespace to
     * single spaces and appends an ellipsis when it cuts — the same block's text, excerpted for a
     * report cell. This statement does the same three things to the same column so that a reader
     * comparing the coverage matrix against a case detail meets one excerpt rather than two.
     *
     * <p>It is a copy because it cannot be anything else: {@code repository} may not import
     * {@code reporting}, and a shared constant would have to live in {@code api} — a module whose
     * charter is wire shapes and which a rendering rule would widen, which §125 makes a decision
     * rather than an import. <strong>Nothing checks that the two agree</strong>; that is recorded
     * rather than solved, and the day they disagree the symptom is cosmetic.
     *
     * <p>The one place they differ deliberately: a block with no words becomes {@code NULL} here and
     * {@code "(empty block at position 3)"} there. A blank cell in a table reads as a rendering
     * fault; an absent line in a panel reads as nothing at all, which is what it is.
     */
    private static final int EXCERPT_LIMIT = 160;

    private final EntityManager entities;

    DocumentationFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Replace this company's mirror of {@code doc_link} — one row per link from a live case to a
     * block of a live page, with the page's key and title, the block's text and whether the link is
     * stale.
     *
     * <h2>Delete-then-insert, because an upsert cannot express a withdrawal</h2>
     *
     * <p>{@code ON CONFLICT} adds and updates and has no way to <em>remove</em> a row, and removal
     * is the ordinary case here: unlinking is a hard delete, removing a block cascades, deleting a
     * page hides everything on it, and deleting a case takes its links out of scope. A mirror that
     * could only grow would go on asserting claims somebody explicitly withdrew — which is the
     * defect {@code BlockLinkService} refuses in the source table ("a withdrawn claim that is still
     * in the table still reads as one to every count over it"), arriving one table later.
     *
     * <p>Both statements run in the caller's single transaction, so no reader ever sees the gap
     * between them. Under MVCC a concurrent request reads the previous snapshot whole.
     *
     * <h2>The predicates, each of which drops something a reader must not see</h2>
     *
     * <ul>
     *   <li>{@code source_type = 'TEST_CASE'} — a suite's links are the same table and not this
     *       field. {@code target_kind = 'DOC_BLOCK'} for the same reason, and it is what makes the
     *       inner joins below total rather than merely usually satisfied.</li>
     *   <li>{@code c.deleted_at IS NULL} — a soft-deleted case is not served, so mirroring its links
     *       would keep rows nothing can read, and phase 2's signal statement would count them.</li>
     *   <li>{@code p.deleted_at IS NULL} — {@code BlockLinks} drops a link it cannot name for
     *       exactly this reason: a link into a deleted page rendered as a row with a title and no
     *       destination is worse than the link being gone.</li>
     *   <li>{@code p.project_id = c.project_id} — RLS bounds the statement to the company, which
     *       {@code CatalogueFacts}' header calls "the wrong bound for a link": a sibling project's
     *       page is this company's, so RLS returns it, and rendering it on this case would put a
     *       page title in front of a reader holding no grant on the project that owns it. The write
     *       path already refuses to create such a link, so this predicate can only ever drop a row a
     *       defect produced — which is the direction to fail in.</li>
     * </ul>
     *
     * <h2>Two arms, because an external target has no page to join to</h2>
     *
     * <p>Since §169 the second {@code SELECT} mirrors {@code EXTERNAL_GIT} and {@code EXTERNAL_URL}
     * rows — the kinds {@code V1} designed and {@code ExternalLinkService} finally writes. It joins
     * {@code test_case} and nothing else, so <strong>three of the four predicates above are absent
     * rather than relaxed</strong>: there is no page to be deleted, and no page project to compare
     * against, which means the sibling-project leak the fourth predicate guards cannot arise on this
     * arm — the target is not a row in this database at all. What it keeps is the case's soft-delete
     * check, which is the one predicate that is about the source.
     *
     * <p>{@code UNION ALL} and not {@code UNION}: the two arms are disjoint by their own
     * {@code target_kind} predicates, so deduplication would be a sort over every link in the
     * company to remove nothing. A row appearing twice here would be a defect in these predicates,
     * and {@code case_doc_link}'s primary key is what would report it — loudly, rather than by
     * quietly collapsing the duplicate.
     *
     * <p>{@code stale} is the literal {@code false} on that arm, which {@code V47} §3 argues is an
     * answer rather than a default: the commit sha pins the lines, so nothing about the target can
     * change, and what could fall behind — the pin itself — is a question this column does not ask.
     *
     * <h2>{@code stale} is a version comparison on both sides, never {@code updated_at}</h2>
     *
     * <p>The newest {@code doc_page_version} against the newest {@code test_case_version}, falling
     * back to each row's {@code created_at} when it has no version yet — which is real on both
     * sides: {@code CaseVersions}' header records that cases predating that write path have no v1,
     * and a page created before {@code PageVersions} is in the same position.
     *
     * <p>{@code ApprovalFacts} refused the timestamp spelling for {@code STALE_APPROVAL} because a
     * save whose content hash is unchanged writes no version and still moves {@code updated_at}. On
     * {@code test_case} that column is moved by three more acts that read nothing —
     * {@code assignOwner}, {@code moveToSuite}, {@code moveToStatus} — so under the timestamp
     * spelling, giving a case an owner would forgive every spec change it had ever accumulated.
     *
     * <p>The comparison is strictly {@code >}. A page version and a case version written in the same
     * microsecond are not evidence that anybody read the new text, and the alternative would raise
     * an ERROR-severity signal on the tick a case is created inside a page's own edit.
     *
     * @return how many links were mirrored. Returned for the caller's log line, and it is the one
     *         number that says the seam ran at all — a company whose documentation links silently
     *         stopped being copied has no other symptom until somebody opens a case.
     */
    int refreshCaseDocLinks() {
        // RLS scopes this to the open company. It is written without a WHERE for that reason and
        // not by omission: adding `WHERE company_id = ?` would put a second, weaker copy of the
        // isolation rule in the one statement in this file that could do real damage if the rule
        // were ever not in force — and a predicate that is merely redundant when RLS works reads as
        // the thing keeping companies apart when it does not.
        entities.createNativeQuery("DELETE FROM case_doc_link").executeUpdate();

        return entities.createNativeQuery("""
                INSERT INTO case_doc_link (
                        id, company_id, case_id, target_kind, relationship,
                        page_id, page_key, page_title, block_id, block_excerpt,
                        external_repo, external_path, external_line_start, external_line_end,
                        external_commit_sha, external_url,
                        external_provider, external_issue_id, external_host, stale)
                SELECT l.id, c.company_id, c.id, l.target_kind, l.relationship,
                       p.id, p.key, p.title, b.id,
                       CASE WHEN length(flat.text) > :limit
                            THEN btrim(left(flat.text, :cut)) || '…'
                            ELSE flat.text END,
                       NULL, NULL, NULL, NULL, NULL, NULL,
                       NULL, NULL, NULL,
                       coalesce(page_content.at, p.created_at)
                           > coalesce(case_content.at, c.created_at)
                  FROM doc_link l
                  JOIN doc_block b ON b.id = l.target_block_id
                  JOIN doc_page  p ON p.id = b.page_id
                  JOIN test_case c ON c.id = l.source_id
                  LEFT JOIN LATERAL (
                           SELECT nullif(
                                      regexp_replace(
                                          btrim(b.content ->> 'text'), '\\s+', ' ', 'g'),
                                      '') AS text
                       ) flat ON true
                  LEFT JOIN LATERAL (
                           SELECT max(v.created_at) AS at
                             FROM doc_page_version v
                            WHERE v.page_id = p.id
                       ) page_content ON true
                  LEFT JOIN LATERAL (
                           SELECT max(v.created_at) AS at
                             FROM test_case_version v
                            WHERE v.case_id = c.id
                       ) case_content ON true
                 WHERE l.source_type = 'TEST_CASE'
                   AND l.target_kind = 'DOC_BLOCK'
                   AND c.deleted_at IS NULL
                   AND p.deleted_at IS NULL
                   AND p.project_id = c.project_id

                UNION ALL

                SELECT l.id, c.company_id, c.id, l.target_kind, l.relationship,
                       NULL, NULL, NULL, NULL, NULL,
                       l.external_repo, l.external_path, l.external_line_start,
                       l.external_line_end, l.external_commit_sha, l.external_url,
                       -- V75: the three EXTERNAL_ISSUE columns. This arm's predicate is
                       -- `<> 'DOC_BLOCK'`, so it already copied issue rows the moment they
                       -- existed — and would have violated ck_case_doc_link_target until the
                       -- mirror gained the columns. That is why V75 moves both tables at once
                       -- and why this SELECT had to move with it: without these three the
                       -- mirror loses the provider, host and id a degraded chip renders from.
                       l.external_provider, l.external_issue_id, l.external_host,
                       -- `false` for every external kind, and the reason DIFFERS BY KIND: a git
                       -- link is pinned to a sha so the lines cannot move under it (V47 §3); an
                       -- issue link carries no derived meaning at all, so there is nothing for a
                       -- freshness marker to be about (004 D13). See the column comment.
                       false
                  FROM doc_link l
                  JOIN test_case c ON c.id = l.source_id
                 WHERE l.source_type = 'TEST_CASE'
                   AND l.target_kind <> 'DOC_BLOCK'
                   AND c.deleted_at IS NULL
                """)
                // Two parameters rather than `:limit - 1` in the statement: arithmetic on a bind
                // parameter leaves Postgres inferring the type of an operand it has not been given
                // one for, and the failure is a startup-time-looking error on a statement that is
                // correct SQL.
                .setParameter("limit", EXCERPT_LIMIT)
                .setParameter("cut", EXCERPT_LIMIT - 1)
                .executeUpdate();
    }
}
