package com.tcms.reporting;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The second place in this module that names a table another module owns</strong>, and the
 * first to read {@code documentation}: {@code doc_page}, {@code doc_block} and {@code doc_link}.
 *
 * <h2>Why a second seam rather than three more methods on {@link ExecutionSource}</h2>
 *
 * <p>The rule this codebase settled on is <strong>one seam per foreign module</strong>, not one per
 * module — {@code execution.CaseFacts} reads {@code repository}, {@code repository.ExecutionFacts}
 * reads {@code execution}, {@code work.BoardFacts} reads both and says so in its name,
 * {@code documentation.PlanFacts} and {@code documentation.CatalogueFacts} are two files in one
 * module for exactly this reason. {@link ExecutionSource} already carries seven tables across
 * {@code execution}, {@code repository} and {@code project}; folding a fourth module's three tables
 * into it would make the one file whose whole purpose is to make the coupling <em>reviewable</em>
 * the file nobody can review. The day these facts arrive as events, this is one file to delete and
 * that one is another.
 *
 * <p>The case labels this report also needs are <strong>not</strong> here. They are {@code test_case}
 * — {@code repository}'s — and {@link ExecutionSource#caseLabels} has served them since R7. A second
 * copy in this file would be the same table read two ways in one module, which is the drift the
 * single-seam rule exists to prevent.
 *
 * <h2>Native SQL, and no entity</h2>
 *
 * <p>{@code DocPage}, {@code DocBlock} and {@code DocLink} are all mapped entities — in
 * {@code documentation}, where they belong. Mapping them again here would give {@code ddl-auto:
 * validate} two sets of column expectations for one table and two places a mapping can drift, which
 * is the argument every sibling seam makes and the reason none of them has an entity either.
 *
 * <h2>The project bound is {@link #findPage}'s alone, and that is deliberate</h2>
 *
 * <p>RLS scopes every statement here to the open company and no further, so a project predicate has
 * to be layered on top — the constraint {@code PlanFacts} and {@code CatalogueFacts} both record.
 * {@code doc_block} and {@code doc_link} carry no {@code project_id} column at all, so the only
 * route to one is the page, and re-deriving it per query would mean joining {@code doc_page} three
 * times to re-answer a question already answered. Instead {@link #findPage} answers it <em>once</em>
 * and refuses; the two methods below take the id it returned. <strong>Calling them with a page id
 * that did not come from {@link #findPage} would read a sibling project's document</strong> — which
 * is why they are package-private, take a page id rather than a project id, and are called from one
 * place.
 */
@Component
class DocumentationSource {

    private final EntityManager entities;

    DocumentationSource(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * The page a coverage report is computed over, or empty when this project has no such page.
     *
     * <p><strong>An unknown page is refused rather than reported as zero coverage</strong>, which is
     * {@code RunSummaryReport}'s check and the same failure mode: a mistyped id and a page whose
     * statements nobody has linked both yield no rows, and left alone the first renders a clean
     * document declaring a specification wholly untested. That is the shape this repository has paid
     * for four times, and it is worse here than anywhere — the output is the artifact somebody hands
     * an auditor.
     *
     * <p>{@code deleted_at IS NULL}: a page's delete is soft, and a report whose spine is a document
     * somebody removed is a report about nothing. Unlike {@code PlanFacts.findAnyPlanKeyInProject},
     * which deliberately <em>does</em> see soft-deleted rows because its job is to name the plan
     * holding a slot, nothing here has to name what is gone.
     */
    Optional<PageHeader> findPage(UUID projectId, UUID pageId) {
        List<?> rows = entities.createNativeQuery("""
                SELECT p.key, p.title, p.status, p.current_version_no
                  FROM doc_page p
                 WHERE p.project_id = :projectId
                   AND p.id = :pageId
                   AND p.deleted_at IS NULL
                """)
                .setParameter("projectId", projectId)
                .setParameter("pageId", pageId)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new PageHeader(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                ((Number) row[3]).intValue()));
    }

    /**
     * Every block of the page, in the order it is written.
     *
     * <p>{@code ORDER BY position} is the document's own order and the whole point of the report:
     * §6i's spine is <em>"walk the document's own headings in order"</em>, so a reader can follow the
     * output against the page they already know. {@code id} breaks the tie, and the tie is
     * <strong>reachable</strong>: {@code doc_block_page_idx} is a plain index on
     * {@code (page_id, position)} and there is no unique constraint behind it, so two blocks may
     * share a position. Without the second key those two would come out in whatever order the plan
     * happened to produce, and one model would render as two different documents — §6e's determinism
     * bullet, which is the property that makes a regenerated report comparable to the one filed last
     * quarter.
     *
     * <p>{@code content} is JSONB and is read with {@code ->>} rather than deserialized into
     * {@code BlockContent}: that record is {@code documentation}'s, this module may not import it,
     * and the two fields wanted here are the two it has. A block whose {@code text} key is missing
     * yields null and is carried as an empty statement rather than dropped — {@code DocBlockView}'s
     * own rule, and for its reason: a block that vanishes from a coverage report is a requirement
     * nobody was told about.
     */
    List<BlockRow> blocks(UUID pageId) {
        List<BlockRow> blocks = new ArrayList<>();
        for (Object result : entities.createNativeQuery("""
                SELECT b.id, b.position, b.block_type,
                       b.content ->> 'text'  AS text,
                       b.content ->> 'level' AS level
                  FROM doc_block b
                 WHERE b.page_id = :pageId
                 ORDER BY b.position, b.id
                """)
                .setParameter("pageId", pageId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            blocks.add(new BlockRow(
                    UUID.fromString(row[0].toString()),
                    ((Number) row[1]).intValue(),
                    (String) row[2],
                    row[3] == null ? "" : (String) row[3],
                    level(row[4])));
        }
        return List.copyOf(blocks);
    }

    /**
     * The cases that claim to <em>verify</em> each block of the page, keyed by block.
     *
     * <h2>{@code VERIFIES} only, and the other two relationships are not a rounding error</h2>
     *
     * <p>{@code LinkRelationship} says it in as many words: a link recorded as {@code VERIFIES} when
     * the case merely {@code REFERENCES} the statement <em>"is what turns a coverage number into a
     * false claim"</em>. This query is where that sentence either holds or does not. A block whose
     * only links are {@code REFERENCES} or {@code DERIVED_FROM} is <strong>uncovered</strong> and the
     * report says so — which will look wrong to whoever created those links, and is the whole reason
     * the author was asked to choose a relationship in the first place.
     *
     * <p>{@code source_type = 'TEST_CASE'} for the same reason one step out. The column's CHECK
     * permits {@code SUITE}, {@code DEFECT} and {@code DOC_PAGE}; {@code LinkSourceType} models two
     * of the four and only {@code TEST_CASE} has a writer today, but a suite link is a claim about a
     * collection and not evidence of a test, and counting one as coverage would inflate the number
     * silently the day suite links land.
     *
     * <p>Deduped with {@code DISTINCT}: two people may record the same coverage from two ends, and
     * {@code BlockLinkService} deliberately answers the second one {@code 200} rather than
     * {@code 409} — <em>"two people recording the same coverage is agreement, not conflict"</em>. It
     * also writes no second row, so this is belt and braces on a table with no unique index behind
     * that promise.
     *
     * <p><strong>No {@code deleted_at} predicate on the case</strong>, and it cannot have one:
     * {@code doc_link.source_id} is opaque — no foreign key, by design, because
     * {@code documentation} may not hold a key into {@code repository} — so there is no
     * {@code test_case} to join without naming a fourth module's table from a file that exists to
     * name three. A link to a case that no longer resolves is handled where the labels are, by
     * printing what is known rather than dropping the row.
     */
    Map<UUID, List<UUID>> verifyingCasesByBlock(UUID pageId) {
        Map<UUID, List<UUID>> byBlock = new LinkedHashMap<>();
        for (Object result : entities.createNativeQuery("""
                SELECT DISTINCT l.target_block_id, l.source_id
                  FROM doc_link l
                  JOIN doc_block b ON b.id = l.target_block_id
                 WHERE b.page_id = :pageId
                   AND l.target_kind = 'DOC_BLOCK'
                   AND l.source_type = 'TEST_CASE'
                   AND l.relationship = 'VERIFIES'
                 ORDER BY l.target_block_id, l.source_id
                """)
                .setParameter("pageId", pageId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            byBlock.computeIfAbsent(UUID.fromString(row[0].toString()), key -> new ArrayList<>())
                    .add(UUID.fromString(row[1].toString()));
        }
        return Map.copyOf(byBlock);
    }

    /** {@code content ->> 'level'} is text, and absent on every block type but two. */
    private Integer level(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            // The column is schemaless. A level that is not a number is a block written by something
            // this build does not know, and the block still has text worth reporting — BlockContent's
            // `ignoreUnknown` argument, one layer down.
            return null;
        }
    }

    /**
     * What the report prints about the document it was computed over.
     *
     * @param key the page's display key — the identifier a reader can look up
     * @param status {@code DRAFT} / {@code PUBLISHED} / {@code ARCHIVED}. Carried because a coverage
     *     report over a draft specification is a different claim from one over a published one, and
     *     nothing else on the page says which it was.
     * @param versionNo {@code current_version_no} at computation time — §6i's determinism note:
     *     <em>"a report generated against a doc records which version it used"</em>
     */
    record PageHeader(String key, String title, String status, int versionNo) {}

    /**
     * One block of the page.
     *
     * @param level heading depth on a {@code heading}, indent depth on a {@code list} (§93), null
     *     everywhere else
     */
    record BlockRow(UUID id, int position, String blockType, String text, Integer level) {}
}
