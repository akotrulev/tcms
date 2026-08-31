package com.tcms.documentation;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a {@code repository} or {@code tracker}
 * table.</strong>
 *
 * <p>~~The one place in this module that names a {@code repository} table.~~ <strong>→ widened by
 * 011 T4</strong>, which gave {@link LinkSourceType} its {@code ISSUE} member: a statement can now
 * be linked from an epic, and {@code issue} is a third foreign table this file names. The pattern is
 * unchanged and that is the point — the arm went where the other two are rather than into a second
 * class, so the count of files a reviewer opens to find this module's crossings stayed at two.
 *
 * <p>{@link PlanFacts}' sibling, and the second file here to hold an id into another module and read
 * it through native SQL. The split is by what is read rather than by who reads it: that one names
 * {@code test_plan} ({@code execution}), this one names {@code test_case} and {@code suite}
 * ({@code repository}) and {@code issue} ({@code tracker}). Merging them into one {@code Facts}
 * class would make the header sentence "the one place this module names a foreign table" true of a
 * file whose name promises nothing, and the point of the pattern is that a reader can tell what
 * crosses where by looking at the file list.
 *
 * <h2>What it answers, and what it must never be asked</h2>
 *
 * <p>Existence-within-a-project, the display key and title, and — since §168 — <strong>the newest
 * recorded result of a case this project's specifications link to</strong>. Not visibility, not
 * permissions, not whether a case may be linked — those are {@code project}'s questions and the
 * caller has already answered them before reaching here.
 *
 * <h2>The project predicate is the whole point</h2>
 *
 * <p>RLS scopes every statement to the company, which is the wrong bound for a link: a case of a
 * sibling project is this company's, so RLS returns it, and a link written to it would put another
 * project's case key on this project's page — visible to a reader holding no grant on it. The
 * predicate is layered on top and never instead, exactly as {@link PlanFacts}' header states.
 *
 * <p>Both reads exclude soft-deleted rows. On the write that is a refusal; on the display resolution
 * it is what makes a link to a deleted case <em>disappear</em> rather than render as a blank bullet
 * with no key and no title — see {@link BlockLinks}, which drops what it cannot name.
 */
@Component
class CatalogueFacts {

    /**
     * The {@code ISSUE} arm (011 M1), and the two things about it that are not the other two arms'.
     *
     * <p><strong>The display key it carries back is {@code issue_key}</strong> — {@code ACME-124},
     * which is what a person types and what the link dialog offers. It reaches the wire as
     * {@code BlockLink.sourceKey}, so the source is named the way it is named everywhere else in the
     * product, while {@code doc_link.source_id} stays a UUID like every other source rather than the
     * one row addressed by a string.
     *
     * <p><strong>{@code deleted_at IS NULL} matters here in a way it does not for a case.</strong>
     * An issue is soft-deleted (scope §3's mitigation for {@code tracker} not being a foundation
     * module), so without this predicate a link could be made to something that is gone from every
     * screen — and, on the read side, rendered as a bullet naming work nobody can open.
     */
    private static final String ISSUES = """
            SELECT i.id, i.issue_key, i.title
              FROM issue i
             WHERE i.project_id = :projectId
               AND i.id IN (:ids)
               AND i.deleted_at IS NULL
            """;

    private final EntityManager entities;

    CatalogueFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * The key and title of one linkable artifact of this project, or empty.
     *
     * @return empty when there is no such row, when it belongs to another project, or when it has
     *         been deleted — one answer, because the caller turns all three into the same 404 (§6t)
     */
    Optional<CatalogueRef> find(UUID projectId, LinkSourceType type, UUID id) {
        Map<UUID, CatalogueRef> found = resolve(projectId, type, List.of(id));
        return Optional.ofNullable(found.get(id));
    }

    /**
     * Keys and titles for many ids of one kind, in one statement.
     *
     * <p>Batched because a page renders every block's links at once, and the per-id version is how a
     * document with fifty linked scenarios becomes fifty queries. Ids that resolve to nothing are
     * <em>absent from the map</em> rather than present with nulls, which is what lets the caller
     * express "drop it" without a null check that could be forgotten.
     */
    Map<UUID, CatalogueRef> resolve(UUID projectId, LinkSourceType type, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            // `IN ()` is a syntax error in Postgres rather than a predicate matching nothing — the
            // same guard BoardService states at its own generators, and here it is the ordinary
            // path: most pages have no links at all.
            return Map.of();
        }

        // Three statements rather than one union: the columns differ (a suite's display name is
        // `name`, a case's is `title`, an issue's key is `issue_key`) and the tables are unrelated.
        // A union would have to alias them into agreement, which reads as though the three were one
        // thing.
        String sql = switch (type) {
            case TEST_CASE -> """
                    SELECT id, key, title FROM test_case
                     WHERE project_id = :projectId
                       AND id IN (:ids)
                       AND deleted_at IS NULL
                    """;
            case SUITE -> """
                    SELECT id, key, name FROM suite
                     WHERE project_id = :projectId
                       AND id IN (:ids)
                       AND deleted_at IS NULL
                    """;
            case ISSUE -> ISSUES;
        };

        List<?> rows = entities.createNativeQuery(sql)
                .setParameter("projectId", projectId)
                .setParameter("ids", ids)
                .getResultList();

        Map<UUID, CatalogueRef> resolved = new HashMap<>();
        for (Object each : rows) {
            Object[] row = (Object[]) each;
            UUID id = (UUID) row[0];
            resolved.put(id, new CatalogueRef(id, (String) row[1], (String) row[2]));
        }
        return resolved;
    }

    /**
     * The newest recorded result of each of these cases, by case id — the other half of
     * {@link BlockCoverageView}.
     *
     * <h2>Read from {@code case_health}, and that is what made the marker buildable</h2>
     *
     * <p>{@code case_health.last_status_kind} is {@code repository}'s mirror of a fact that lives in
     * {@code execution}, written by {@code ExecutionFacts} in the same statement that derives
     * {@code never_executed} (§166, {@code V45}). Reading it here is one foreign table in the one
     * module this file already names; the live join it replaces —
     * {@code test_execution JOIN case_status} — is two modules, one of which {@code documentation}
     * may not name at all, and was the marker's standing obstacle for five sections.
     *
     * <p><strong>The mirror is also the correct source rather than merely the reachable one.</strong>
     * {@code TestCase.lastResult} is served from this same column, so a statement's marker and the
     * case detail it links to cannot disagree about whether that case passed. A second derivation
     * with its own {@code ORDER BY} could, and the two are rendered one click apart.
     *
     * <p>The freshness of the answer is the recompute's, not this query's: the value is refreshed on
     * {@code execution.results_changed}, so it trails a just-submitted result by a projection rather
     * than by a poll. A case with no {@code case_health} row at all — a project whose first recompute
     * has not run — is <em>absent from the map</em>, which the caller counts as not run, exactly as it
     * counts a null kind. Both mean "no verdict recorded", and collapsing them here is what stops the
     * caller needing to know which of the two it met.
     *
     * @param ids the already-resolved live case ids of this project. The project and soft-delete
     *            predicates are restated rather than assumed: this method is {@code public} to the
     *            module and a later caller that has not been through {@link #resolve} would otherwise
     *            read a sibling project's result onto this page.
     * @return kinds by case id, as {@code case_status.kind} spells them — {@code project}'s
     *         {@link com.tcms.project.StatusKind} names and never a project's own renameable label,
     *         which is the same refusal {@code V45}'s column comment makes
     */
    Map<UUID, String> lastResultKinds(UUID projectId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            // `IN ()` is a syntax error rather than a predicate matching nothing — see #resolve,
            // where this guard is the ordinary path for the same reason: most pages link to nothing.
            return Map.of();
        }

        List<?> rows = entities.createNativeQuery("""
                SELECT c.id, h.last_status_kind
                  FROM test_case c
                  JOIN case_health h ON h.case_id = c.id
                 WHERE c.project_id = :projectId
                   AND c.id IN (:ids)
                   AND c.deleted_at IS NULL
                   AND h.last_status_kind IS NOT NULL
                """)
                .setParameter("projectId", projectId)
                .setParameter("ids", ids)
                .getResultList();

        Map<UUID, String> kinds = new HashMap<>();
        for (Object each : rows) {
            Object[] row = (Object[]) each;
            kinds.put((UUID) row[0], (String) row[1]);
        }
        return kinds;
    }

    /**
     * The three columns of a linkable artifact this module is allowed to know about.
     *
     * <p>Not a {@code TestCaseView}: that is {@code repository}'s vocabulary, and holding a foreign
     * module's shape to read three fields off it is how a boundary drawn in {@code package-info}
     * stops being real. {@link PlanFacts.PlanRef} is the same record for the same reason.
     */
    record CatalogueRef(UUID id, String key, String title) {
    }
}
