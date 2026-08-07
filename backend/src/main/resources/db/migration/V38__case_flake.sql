-- =============================================================================
-- V38 — flake detection and quarantine (plan §6c, §6l; M3 slice 6)
--
-- The plan's milestone table says what M3 is: "Result parsers, automation-key
-- matching, API tokens, QUARANTINE FLOW". Three of the four shipped in §116–§119.
-- This is the fourth, and it is the one with no schema at all.
--
-- §6c names the three parts in one line — "Flaky test detection: result history per
-- automation key, FLAKE SCORE, QUARANTINE FLAG" — and §6l names the consumer that has
-- been waiting for it: the board's fourth derived-item generator, "quarantined flaky
-- test", which is the one of §6l's four that BoardFacts does not implement.
--
-- Open question #24 recorded the blocker in as many words on 2026-08-02:
--
--     "The quarantined-flaky generator has NO DATA until M3 ingestion exists.
--      On-by-default here means on WHEN IT SHIPS, not a card source that is
--      silently empty and mistaken for working."
--
-- §117 removed that blocker. This is the fourth time a plan's own "blocked" note has
-- outlived its blocker (§115's coverage matrix, §120's drift pair) — re-read the note
-- against the code before treating it as current.
--
-- -----------------------------------------------------------------------------
-- ONE TABLE, TWO COLUMN GROUPS, AND THE SEPARATION IS THE ON CONFLICT RULE
-- -----------------------------------------------------------------------------
--
-- The score is DERIVED. The quarantine is AUTHORED. They cannot both live in
-- case_health, whose own table comment is categorical about this:
--
--     "Signals are DERIVED, never authored — no UI writes here, so they self-heal
--      when the underlying condition is fixed. No 'mark as not-stale' button exists
--      to lie with (plan §6o)."
--
-- So they live here, in one row, and the boundary between them is held by the rule
-- this codebase already runs on — repository.ExecutionFacts states it:
--
--     "A signal's owner writes its own column and no others; the row is shared, the
--      columns are not."
--
-- §120 was the first slice where that rule bit between two live writers. This is the
-- first where it bites INSIDE ONE TABLE. The recompute's ON CONFLICT names the seven
-- derived columns and never the four quarantine ones, so a case quarantined by a human
-- in March is still quarantined after the next tick — and the quarantine write names
-- the four and never the seven, so recording a decision cannot rewrite the evidence
-- the decision was made on.
--
-- Two tables would enforce the same thing structurally and are worse: one row per case
-- in two places, two upserts, and a join on every read of a screen that wants both.
--
-- WHY QUARANTINE IS AUTHORED AND NOT A THRESHOLD. A score crossing a line is a fact,
-- and deriving a flag from it would be free. Quarantining is a DECISION — "stop gating
-- on this test" — and §6o's no-button-to-lie-with rule forbids authoring a derived
-- CLAIM, not recording a decision. It is also the only version in which the board card
-- means anything: a derived card would vanish the moment the test stopped flaking, and
-- the debt would vanish with it while the test was still excluded from gating.
-- =============================================================================

CREATE TABLE case_flake (
    case_id             UUID PRIMARY KEY REFERENCES test_case(id) ON DELETE CASCADE,
    company_id          UUID NOT NULL REFERENCES company(id),
    project_id          UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,

    -- ---- the derived half. Owned by reporting.ExecutionSource; see the ON CONFLICT rule above.
    executions          INT NOT NULL DEFAULT 0,
    transitions         INT NOT NULL DEFAULT 0,
    alternations        INT NOT NULL DEFAULT 0,
    cells               INT NOT NULL DEFAULT 0,
    flake_score         NUMERIC(5,4)
                        CHECK (flake_score IS NULL OR (flake_score >= 0 AND flake_score <= 1)),
    last_alternation_at TIMESTAMPTZ,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ---- the authored half. Owned by the quarantine write. Never in the recompute's set-list.
    quarantined         BOOLEAN NOT NULL DEFAULT false,
    quarantined_at      TIMESTAMPTZ,
    quarantined_by      UUID REFERENCES app_user(id),
    quarantine_reason   TEXT,

    -- A quarantine with no reason is debt nobody can clear: the card says "this test is
    -- excluded" and cannot say why or by whom, so the only way to evaluate it is to read
    -- the test. The constraint is what stops a future caller from adding a one-argument
    -- toggle, which is the shape this would otherwise drift into.
    CONSTRAINT case_flake_quarantine_complete CHECK (
        (NOT quarantined
             AND quarantined_at IS NULL
             AND quarantined_by IS NULL
             AND quarantine_reason IS NULL)
        OR (quarantined
             AND quarantined_at IS NOT NULL
             AND quarantined_by IS NOT NULL
             AND quarantine_reason IS NOT NULL))
);

COMMENT ON TABLE case_flake IS
    'Per-case flake evidence (DERIVED, plan §6l) and quarantine decision (AUTHORED, plan §6c) in '
    'one row. The two halves are kept apart by the ON CONFLICT set-list rather than by two tables: '
    'the recompute names the seven derived columns and the quarantine write names the four '
    'authored ones, so neither can blank the other. No lock_version — the quarantine toggle is a '
    'single-writer transition from one action, not two screens racing over a form (report_job''s '
    'shape, not test_case''s).';

COMMENT ON COLUMN case_flake.cells IS
    'How many (configuration, case version) cells contributed. Flakiness is scoped to ONE cell '
    '(plan §6l): "alternation across versions is just change, and reporting it as flake is a false '
    'signal." Stored so a reader can tell a test that flaps on one browser from one that flaps '
    'on five.';

COMMENT ON COLUMN case_flake.transitions IS
    'Adjacent pairs of outcomes inside a cell — the DENOMINATOR of flake_score, and never the '
    'execution count. A case with six results spread over six cells has six executions and zero '
    'transitions, because nothing in it ever ran twice under the same conditions.';

COMMENT ON COLUMN case_flake.alternations IS
    'How many of those adjacent pairs changed between a pass and a FAILED. Non-outcomes — BLOCKED, '
    'SKIPPED, RETEST, UNTESTED — are removed from the sequence BEFORE the pairs are formed, which '
    'is not the same as ignoring them: a blocked run between two passes would otherwise read as '
    'two alternations (pass->blocked, blocked->pass) and score a perfectly stable test at 1.0. '
    'This is R7''s "a pass and a BLOCK is not an inconsistency" rule applied to a sequence.';

COMMENT ON COLUMN case_flake.flake_score IS
    'alternations / transitions, in [0,1] — or NULL below the evidence floor, which is a DIFFERENT '
    'claim from 0.0. Two results that went pass then fail score 1.0 on any ratio definition and '
    'that is noise, not a flake; 0.0 means "evidently stable" and NULL means "not enough evidence '
    'to say". Collapsing the two is the confident zero §95''s usage_count and §100''s exit criteria '
    'both record. The counts behind it are stored either way so a reader can see WHY there is no '
    'score. Bounded window (tcms.case-flake.window) for §120''s reason: an unbounded score can '
    'never go false, so a test that flaked last spring and has been solid since stays flagged for '
    'ever with nothing anybody can do about it.';

COMMENT ON COLUMN case_flake.quarantine_reason IS
    'Required whenever quarantined — see case_flake_quarantine_complete. Gated on case:edit, which '
    'Automation Service deliberately does NOT hold (roles spec §6b): a leaked CI token can post '
    'results and cannot quarantine a test to stop its own red results from gating. Same argument '
    '§117 made for automation_key_mapping, and the reason this is not gated on result:submit.';

-- -----------------------------------------------------------------------------
-- Indexes — both lead with company_id, which SchemaConventionTest asserts
-- -----------------------------------------------------------------------------
--
-- The first is §6l's fourth generator's whole predicate: the quarantined cases in the
-- projects on a board. Partial, because quarantine is rare by design — a board that
-- shows dozens has stopped being a worklist.
--
-- The second serves the flake worklist ("what should we look at"), and is partial on
-- flake_score IS NOT NULL for D5's reason: a case below the evidence floor has no
-- score, is not ranked, and should not be in the index that ranks.

CREATE INDEX case_flake_quarantined_idx ON case_flake (company_id, project_id)
    WHERE quarantined;

CREATE INDEX case_flake_score_idx ON case_flake (company_id, project_id, flake_score DESC)
    WHERE flake_score IS NOT NULL;

-- -----------------------------------------------------------------------------
-- RLS — a new company-owned table, so the policy count goes 61 -> 62
-- -----------------------------------------------------------------------------
--
-- Unlike V37, which added a column to an already-policed table and had nothing to
-- register. FORCE, because the owner bypasses a plain ENABLE; and nullif(..., '')
-- because V4 established that a GUC reset on a pooled connection reads as the empty
-- string rather than NULL, and '' ::uuid raises instead of matching nothing.

ALTER TABLE case_flake ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_flake FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON case_flake USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
