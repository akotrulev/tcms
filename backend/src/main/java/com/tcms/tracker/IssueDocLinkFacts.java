package com.tcms.tracker;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a {@code documentation} table.</strong>
 *
 * <p>{@code work.BoardFacts}' pattern and {@code repository.DocumentationFacts}' exact shape — the
 * mirror of {@code documentation.CatalogueFacts}, which is the one place that module names a
 * {@code tracker} table. Neither module may import the other (this package's comment, and
 * {@code documentation}'s), so each reads rows it holds ids into through exactly one file, and a
 * reviewer finds every crossing by opening two. <strong>A second class here naming
 * {@code doc_link} would end that property, and it would end it for the most tempting reason there
 * is — that the new query is short.</strong>
 *
 * <h2>What crosses here is a row, not a boolean, and no request ever reaches this file</h2>
 *
 * <p>The epic screen has to <em>render</em> a page key, a page title and a block excerpt, and the
 * request path may not read where they live. So this copies them into {@code issue_doc_link}, which
 * makes that table a denormalized copy with the staleness window every copy has. The forbidden edit
 * is the same one {@code DocumentationFacts} names: a value-returning helper — <em>"just fetch the
 * links for these forty issue ids"</em> — would satisfy every ArchUnit rule in this repository and
 * give away the thing they protect, and on the epic endpoint it would be a single query, which is
 * exactly why somebody will propose it.
 *
 * <h2>Native SQL, and why there is no entity for {@code doc_link} here</h2>
 *
 * <p>A second JPA entity mapped over a table another module owns is one table with two sets of
 * column expectations for {@code ddl-auto: validate} to check, and two places a mapping can drift.
 * {@link IssueDocLink} is <em>not</em> that: it maps {@code issue_doc_link}, a table this module owns
 * outright.
 *
 * <p>RLS covers every statement here, on both sides. This runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}: the {@code SELECT}
 * can only see the open company's links, the {@code INSERT} can only write that company's rows, and
 * — the one that matters most for a statement that <em>deletes</em> — the {@code DELETE} can only
 * reach that company's rows.
 */
@Component
class IssueDocLinkFacts {

    /**
     * The excerpt bound, and it is <strong>200 rather than {@code case_doc_link}'s 160</strong>.
     *
     * <p>Not a divergence to reconcile: {@code V3}'s column comment says 200 and this statement is
     * what makes that comment true. The number is a rendering decision per surface — an epic's
     * Linked-specification panel is a full-width card and a case's Links panel is a sidebar row —
     * and the rule that is genuinely shared is the one both copy: <em>a quotation trimmed without a
     * mark is a claim about where the spec ends</em>, so a cut excerpt always carries the ellipsis.
     */
    private static final int EXCERPT_LIMIT = 200;

    private final EntityManager entities;

    IssueDocLinkFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Replace this company's mirror of the links from its issues into documentation.
     *
     * <h2>Delete-then-insert, because an upsert cannot express a withdrawal</h2>
     *
     * <p>{@code ON CONFLICT} adds and updates and has no way to <em>remove</em> a row, and removal is
     * the ordinary case here: unlinking is a hard delete, removing a block cascades, deleting a page
     * hides everything on it, and soft-deleting an issue takes its links out of scope. A mirror that
     * could only grow would go on asserting claims somebody explicitly withdrew — <strong>which is
     * the whole reason this handler rebuilds rather than patches.</strong> Both statements run in the
     * caller's single transaction, so no reader ever sees the gap between them.
     *
     * <h2>The predicates, each of which drops something a reader must not see</h2>
     *
     * <ul>
     *   <li>{@code source_type = 'ISSUE'} — a case's and a suite's links are the same table and not
     *       this panel. {@code target_kind = 'DOC_BLOCK'} on the first arm for the same reason, and
     *       it is what makes the inner joins below total rather than merely usually satisfied.</li>
     *   <li>{@code i.deleted_at IS NULL} — a soft-deleted issue is not served, so mirroring its
     *       links would keep rows nothing can read.</li>
     *   <li>{@code p.deleted_at IS NULL} — a link into a deleted page rendered as a row with a title
     *       and no destination is worse than the link being gone.</li>
     *   <li>{@code p.project_id = i.project_id} — RLS bounds the statement to the company, which is
     *       the wrong bound for a link: a sibling project's page is this company's, so RLS returns
     *       it, and rendering it on this issue would put a page title in front of a reader holding no
     *       grant on the project that owns it. The write path already refuses to create such a link,
     *       so this predicate can only ever drop a row a defect produced — which is the direction to
     *       fail in.</li>
     * </ul>
     *
     * <h2>Two arms, because an external target has no page to join to</h2>
     *
     * <p>{@code UNION ALL} and not {@code UNION}: the two arms are disjoint by their own
     * {@code target_kind} predicates, so deduplication would be a sort over every link in the company
     * to remove nothing. A row appearing twice would be a defect in these predicates, and
     * {@code issue_doc_link}'s primary key is what would report it — loudly, rather than by quietly
     * collapsing the duplicate.
     *
     * <h2>{@code stale} is the page's newest version against the issue's own {@code updated_at}</h2>
     *
     * <p>{@code V3}'s column comment states it and this is where it is kept. It is deliberately
     * <em>not</em> {@code case_doc_link}'s version-against-version comparison: an issue has no
     * version table to compare against, so its own timestamp is the only "when did this last change"
     * this side has. The cost is the one {@code ApprovalFacts} refused for a case — a write that
     * changes nothing a reader cares about still moves {@code updated_at} and so forgives the flag —
     * and it is accepted here because the alternative is a version table nothing else asks for.
     *
     * <p>PAGE granularity, not block: {@code doc_block} carries no timestamp, so an edit elsewhere on
     * the page raises the flag too. {@code false} for every external kind, which {@code V3}'s comment
     * argues is an answer rather than a default.
     *
     * <p>The comparison is strictly {@code >}. A page version and an issue edit written in the same
     * microsecond are not evidence that nobody read the new text, and the alternative would flag
     * every link the instant it was made.
     *
     * @return how many links were mirrored — the one number that says the seam ran at all
     */
    int refreshIssueDocLinks() {
        // RLS scopes this to the open company. It is written without a WHERE for that reason and not
        // by omission: adding `WHERE company_id = ?` would put a second, weaker copy of the isolation
        // rule in the one statement in this file that could do real damage if the rule were ever not
        // in force.
        entities.createNativeQuery("DELETE FROM issue_doc_link").executeUpdate();

        return entities.createNativeQuery("""
                INSERT INTO issue_doc_link (
                        id, company_id, issue_id, target_kind, relationship,
                        page_id, page_key, page_title, block_id, block_excerpt,
                        external_repo, external_path, external_line_start, external_line_end,
                        external_commit_sha, external_url,
                        external_provider, external_issue_id, external_host, stale)
                SELECT l.id, i.company_id, i.id, l.target_kind, l.relationship,
                       p.id, p.key, p.title, b.id,
                       CASE WHEN length(flat.text) > :limit
                            THEN btrim(left(flat.text, :cut)) || '…'
                            ELSE flat.text END,
                       NULL, NULL, NULL, NULL, NULL, NULL,
                       NULL, NULL, NULL,
                       coalesce(page_content.at, p.created_at) > i.updated_at
                  FROM doc_link l
                  JOIN doc_block b ON b.id = l.target_block_id
                  JOIN doc_page  p ON p.id = b.page_id
                  JOIN issue     i ON i.id = l.source_id
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
                 WHERE l.source_type = 'ISSUE'
                   AND l.target_kind = 'DOC_BLOCK'
                   AND i.deleted_at IS NULL
                   AND p.deleted_at IS NULL
                   AND p.project_id = i.project_id

                UNION ALL

                SELECT l.id, i.company_id, i.id, l.target_kind, l.relationship,
                       NULL, NULL, NULL, NULL, NULL,
                       l.external_repo, l.external_path, l.external_line_start,
                       l.external_line_end, l.external_commit_sha, l.external_url,
                       l.external_provider, l.external_issue_id, l.external_host,
                       false
                  FROM doc_link l
                  JOIN issue i ON i.id = l.source_id
                 WHERE l.source_type = 'ISSUE'
                   AND l.target_kind <> 'DOC_BLOCK'
                   AND i.deleted_at IS NULL
                """)
                // Two parameters rather than `:limit - 1` in the statement: arithmetic on a bind
                // parameter leaves Postgres inferring the type of an operand it has not been given
                // one for, and the failure is a startup-time-looking error on correct SQL.
                .setParameter("limit", EXCERPT_LIMIT)
                .setParameter("cut", EXCERPT_LIMIT - 1)
                .executeUpdate();
    }
}
