-- V35 — the two rollups learn when they were written, and when their last result landed.
--
-- suite_run_summary has carried `updated_at` since V1 and plan_snapshot does not, which is invisible
-- until something reads either one. §101 phase 4 makes both readable, and the contract's `computedAt`
-- is the answer to "how old is this arithmetic" — a different question from `stale`, which is how old
-- the EXECUTIONS are. §6p: "a plan page showing 94% from three weeks ago, presented as current, is
-- the most dangerous screen in the product", and the two ages are separately dangerous.
--
-- NOT NULL with a default rather than nullable: a null here would be a third state on a field whose
-- whole job is to have two — a timestamp meaning "as of then", and null on the wire meaning "computed
-- live, as of now". A row that exists was written by the refresh, so it has a time; letting the
-- column be null would let the SERVER produce the client's live sentinel from a rollup row, which is
-- the one confusion this field exists to prevent.
--
-- The default is now() rather than an epoch, because existing rows were in fact written by a refresh
-- that ran before this column existed, and dating them to 1970 would render as a plan whose figures
-- are fifty-six years stale — the exact false alarm §6p's dangerous-screen sentence is about, with
-- the sign reversed.

ALTER TABLE plan_snapshot ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- And suite_run_summary learns WHEN the run's last result landed, which is not the same thing as
-- when the rollup was written and V1 shipped neither.
--
-- §6p's suite history row is "87% — Nightly Regression #412 — 2026-07-28 — 41/47 passed", and that
-- date is the last result in the run rather than the run's own start: a run left open for a week
-- belongs on the timeline where the work finished. The page SORTS by it and the contract marks
-- `executedAt` required, so a rollup that could not answer it could not serve this screen at all —
-- it would have to fall back to the run's start, silently moving every row of a long-running run.
--
-- NULLABLE, unlike its sibling column above, and the difference is real: a run that has opened and
-- recorded nothing has no last result. The reader falls back to started_at and then to created_at,
-- which is what SuiteHistoryService has always done and what makes "this suite is in flight" a row
-- rather than an absence.
--
-- It is NOT frozen with the membership. An amended verdict moves it, because it describes the
-- results rather than the composition — the same split V1's own columns already make on this table.

ALTER TABLE suite_run_summary ADD COLUMN last_executed_at TIMESTAMPTZ;

COMMENT ON COLUMN suite_run_summary.last_executed_at IS
    'The most recent result recorded against this suite in this run, or NULL when the run has '
    'opened and recorded nothing. Moves with the counts and is NOT frozen with membership_snapshot: '
    'it describes the results, not the composition.';

COMMENT ON COLUMN plan_snapshot.updated_at IS
    'When the rollup refresh last wrote this row. Served as PlanStatistics.computedAt, which is a '
    'claim about the ARITHMETIC''s age — distinct from `stale`, which is a claim about the '
    'EXECUTIONS'' age. A plan executed an hour ago and rolled up ten minutes ago is fresh by one '
    'measure and behind by the other, and rendering them as one signal reintroduces the ambiguity '
    'plan §6p added the second number to remove.';
