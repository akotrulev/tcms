-- =============================================================================
-- V37 — the other half of automation drift: a case receiving CI results while
--       its own automation_status says no automation exists
--
-- case_health has carried `stale_automation` since V1 with no writer, because the
-- signal's predicate is "no INGESTED result in N days" and until §117 nothing in the
-- product could produce an ingested result — test_run.source was 'MANUAL' on every
-- row ever written. That blocker is gone, and M3 slice 5 gives the column its writer.
--
-- §6c defines the failure mode in two directions and the schema only has one of them:
--
--     "Flag any AUTOMATED case with no ingested result in N days as stale automation.
--      Equally, flag cases receiving results while marked MANUAL — that's an unlinked
--      automation the team doesn't know it has."
--
-- The second has no column. It is NOT `unlinked`, which means something else entirely
-- (no requirement link — HealthSignal's INFO tier, "something is missing but nothing
-- is broken"); reusing it would put two unrelated meanings behind one boolean and one
-- dashboard tile, and the tile's label can only ever be right about one of them.
--
-- -----------------------------------------------------------------------------
-- WHY THE COLUMN IS NAMED FOR THE MEANING AND NOT FOR §6c's WORDING
-- -----------------------------------------------------------------------------
--
-- §6c says "marked MANUAL". The predicate this column is written by is
-- `automation_status <> 'AUTOMATED'`, which is wider by two states, and both of them
-- are louder than the one §6c named:
--
--   TO_BE_AUTOMATED  — the automation backlog is counting work that is already done,
--                      and that backlog is what §6c says justifies headcount.
--   NOT_AUTOMATABLE  — the case was declared impossible to automate and something is
--                      automating it. Worse than wrong: automation coverage is
--                      denominated AUTOMATED / (total − NOT_AUTOMATABLE), so this one
--                      moves the DENOMINATOR of the number the whole signal family
--                      exists to keep honest.
--
-- Hence `undeclared_automation` — automation exists and the case does not say so —
-- rather than a name that would have to be re-read the first time it fired on a
-- NOT_AUTOMATABLE case.
--
-- -----------------------------------------------------------------------------
-- NO NEW TABLE, SO NO NEW POLICY
-- -----------------------------------------------------------------------------
--
-- case_health has been in V1's company_owned array since M0 and already carries FORCE
-- ROW LEVEL SECURITY. The policy count stays 61. A column added to a policed table
-- inherits the policy; there is nothing to add and nothing to remember.
-- =============================================================================

ALTER TABLE case_health
    ADD COLUMN undeclared_automation BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN case_health.undeclared_automation IS
    'A case NOT marked AUTOMATED that has taken a result from an ingested run inside the '
    'stale-automation window (plan §6c). The mirror of stale_automation: that one is a result '
    'the team believes it is getting and is not, this one is a result arriving that no coverage '
    'number counts. Both are derived from one fact — the newest ingested result — on opposite '
    'sides of one cutoff, so a case can never raise both.';

COMMENT ON COLUMN case_health.stale_automation IS
    'A case marked AUTOMATED whose newest INGESTED result is older than the window (plan §6c). '
    'Requires a previous ingested result: "stopped reporting" presupposes started, and §6c makes '
    'automationKey optional precisely so a case can be marked AUTOMATED before its automation '
    'exists — so a predicate of "no result in N days" would fire on every case in a project the '
    'day somebody began declaring intent. A case with no results at all is NEVER_EXECUTED.';

-- -----------------------------------------------------------------------------
-- The indexes, and the one V1 forgot
-- -----------------------------------------------------------------------------
--
-- V1 indexed four of the nine signals (outdated, stale_approval, never_executed,
-- unlinked) and left five bare. Two of those five now have writers, and both are read
-- by the dashboard's facet tally, which is one aggregate per signal over every live
-- case in a project. Adding the pair here rather than "when it gets slow" is the same
-- argument V36 made about api_token.name's missing unique index: SchemaConventionTest
-- asserts that company_id leads every index THAT EXISTS, which is silent about one
-- that does not — check for the missing index, not only the wrong one.
--
-- (company_id, project_id) leading, partial on the flag, exactly as V1's four are.

CREATE INDEX case_health_stale_autom_idx  ON case_health (company_id, project_id) WHERE stale_automation;
CREATE INDEX case_health_undecl_autom_idx ON case_health (company_id, project_id) WHERE undeclared_automation;
