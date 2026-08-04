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
 * <strong>The one place in this module that names a {@code repository} table.</strong>
 *
 * <p>{@link PlanFacts}' sibling, and the second file here to hold an id into another module and read
 * it through native SQL. The split is by what is read rather than by who reads it: that one names
 * {@code test_plan} ({@code execution}), this one names {@code test_case} and {@code suite}
 * ({@code repository}). Merging them into one {@code Facts} class would make the header sentence
 * "the one place this module names a foreign table" true of a file whose name promises nothing, and
 * the point of the pattern is that a reader can tell what crosses where by looking at the file list.
 *
 * <h2>What it answers, and what it must never be asked</h2>
 *
 * <p>Existence-within-a-project, and the display key and title. Not visibility, not permissions, not
 * whether a case may be linked — those are {@code project}'s questions and the caller has already
 * answered them before reaching here.
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

        // Two statements rather than one union: the columns differ (a suite's display name is
        // `name`, a case's is `title`) and the tables are unrelated. A union would have to alias
        // them into agreement, which reads as though the two were one thing.
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
     * The three columns of a linkable artifact this module is allowed to know about.
     *
     * <p>Not a {@code TestCaseView}: that is {@code repository}'s vocabulary, and holding a foreign
     * module's shape to read three fields off it is how a boundary drawn in {@code package-info}
     * stops being real. {@link PlanFacts.PlanRef} is the same record for the same reason.
     */
    record CatalogueRef(UUID id, String key, String title) {
    }
}
