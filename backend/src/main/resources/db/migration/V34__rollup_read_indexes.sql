-- V34 — the read indexes the two M2 rollups need, and V1 did not ship because the tables had no
-- reader to shape them for.
--
-- suite_run_summary and plan_snapshot have been in V1 since M0, discussed in javadoc, and written by
-- nothing. Round 3 of the remaining-scope assessment found them by filtering out tables that are only
-- *mentioned* in comments, which is why M2 went backwards 90% -> 85%: nothing was un-built, the
-- earlier figure was wrong. §101 gives both a writer, and a writer implies a reader.
--
-- WHY THE PRIMARY KEYS DO NOT SERVE THE READ.
-- Both PKs are the write path's, and correctly so: suite_run_summary is
-- (run_id, suite_id, configuration_id) because a row IS one suite's pass over one run, and
-- plan_snapshot is (plan_id, case_id, configuration_id) because a row IS one case's current verdict.
-- The reads arrive from the other direction. The suite history page has a suite and no run at all —
-- it wants every run of one suite — so the PK's leading run_id cannot be seeked on.
--
-- COMPANY_ID LEADS BOTH, and that is a rule with a test behind it: ERD §0.1 point 3 and
-- SchemaConventionTest#companyIdLeadsCompositeIndexes fail the build on an index where company_id is
-- present but not first. A trailing company_id costs storage and buys nothing at query time, because
-- the planner cannot seek on it.
--
-- NO POLICY IS CREATED HERE. Both tables are already in V1's `company_owned` array, so both have
-- carried FORCE ROW LEVEL SECURITY and the company_isolation policy since the beginning — they were
-- protected for eight months before there was a row to protect. The policy count stays at 61 and
-- SchemaConventionTest pins it.

CREATE INDEX suite_run_summary_suite_idx
    ON suite_run_summary (company_id, suite_id);

COMMENT ON INDEX suite_run_summary_suite_idx IS
    'The suite history page: every run of one suite, newest first. The PRIMARY KEY leads with '
    'run_id and therefore cannot serve a query that names a suite and no run.';

CREATE INDEX plan_snapshot_plan_idx
    ON plan_snapshot (company_id, plan_id);

COMMENT ON INDEX plan_snapshot_plan_idx IS
    'The plan statistics page: every case of one plan. The PRIMARY KEY already leads with plan_id, '
    'so this looks redundant and is not — it adds the company_id seek the PK cannot do at all, '
    'which is the predicate RLS puts on every single statement against this table.';
