-- V9 — the index the condition library's only query needs.
--
-- `condition` came out of V1 with two GIN indexes (search_vector, title trigram) and no b-tree at
-- all, so `WHERE company_id = ? AND project_id = ? AND deleted_at IS NULL` — the shape of every
-- read GET /projects/{projectId}/conditions performs, and the shape RLS adds to any read that
-- forgets — had nothing to seek on. Every sibling table in V1 already carries this index under a
-- different name: suite_project_idx, test_case_suite_idx, test_case_lifecycle_idx. The omission is
-- an oversight in V1 rather than a decision, and this slice is the first query to notice.
--
-- company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest: a trailing company_id costs
-- storage and buys nothing, because the planner cannot seek on it.
--
-- Partial on `deleted_at IS NULL` for the reason suite_project_idx is: a retired library entry is
-- soft-deleted because attachments to it survive, so the dead rows accumulate permanently and no
-- read outside the retirement surface ever wants one.
CREATE INDEX condition_project_idx ON condition (company_id, project_id)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX condition_project_idx IS
    'The library read: one project''s live conditions. Mirrors suite_project_idx — same predicate, '
    'same partiality, same reason. The GIN indexes on this table serve the typeahead corpus, not '
    'the project scope, and a GIN index cannot answer an equality seek on company_id.';
