-- V10 — the index suite-level condition inheritance needs. Same omission V9 fixed, one table over.
--
-- `suite_condition` came out of V1 with no index at all beyond its primary key on `id`, while the
-- only two queries against it both seek on `suite_id`: the suite tree's attachment lookup (§24) and
-- now the case detail's ancestry walk, which reads the case's folder and every folder above it in
-- one `suite_id IN (...)`. Neither has had anything to seek on. Its sibling `case_condition` was
-- given `case_condition_case_idx (case_id, kind, position)` in V1 and this table was not — an
-- oversight rather than a decision, exactly as `condition` was in V9.
--
-- company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest: a trailing company_id costs
-- storage and buys nothing, because the planner cannot seek on it.
--
-- `kind` and `position` follow so the index also answers the ordering. The case detail reads both
-- kinds in one query and partitions them in memory, so the seek is on (company_id, suite_id) and
-- the remaining two columns come along free — the same shape case_condition_case_idx has, and
-- deliberately so: two tables serving one resolution order should not be indexed two ways.
--
-- NOT partial. `suite_condition` has no deleted_at — detaching a condition from a suite is a real
-- DELETE, because the attachment is not something history pins. Retirement is a soft delete on
-- `condition`, one level down, and V9's index is the one that is partial for it.
CREATE INDEX suite_condition_suite_idx
    ON suite_condition (company_id, suite_id, kind, position);

COMMENT ON INDEX suite_condition_suite_idx IS
    'Inheritance resolution: the conditions attached to a case''s folder and to every folder above '
    'it, in one seek. Mirrors case_condition_case_idx with company_id leading — the two tables '
    'feed one resolution order (plan §6f) and are read together.';
