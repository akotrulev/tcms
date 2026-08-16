-- =============================================================================
-- V65 — a plan can be put away without being deleted
--
-- D2 of docs/audits/2026-08-15-flow-review-and-improvements.md: "a plan named by
-- any run is a permanent 409 and there is no terminal state since V32. If CLOSED
-- stays deleted, consider an ARCHIVED-style visibility flag so old plans leave
-- the working list."
--
-- CLOSED stays deleted. This is the flag.
--
-- -----------------------------------------------------------------------------
-- WHY A COLUMN AND NOT A FOURTH STATUS
-- -----------------------------------------------------------------------------
--
-- The obvious spelling is a fourth PlanStatus, and it is the wrong one twice
-- over.
--
-- §97 (M7) removed ACTIVE and CLOSED from that ladder for a stated reason: they
-- described a plan BEING RUN, which is the execution's business, and
-- test_run has carried NOT_STARTED → IN_PROGRESS → COMPLETED → ABORTED since V1.
-- Putting a terminal state back would re-open exactly that conflation, and this
-- one is not even about execution — a plan can be archived while a run against
-- it is still open, and a plan nobody ever ran can be archived on the day it is
-- written.
--
-- The second reason is the ladder itself. DRAFT → IN_REVIEW → APPROVED is a
-- statement about whether the outline has been AGREED, and PlanLifecycle gates
-- it (§6g: "the state machine has exactly one gate"). Archiving says nothing
-- about agreement: an approved plan is the one you most want to archive when the
-- release ships, and archiving it must not unsay the approval. Two facts, two
-- columns; the alternative is a status that destroys information every time it
-- is set.
--
-- So: orthogonal. Any status may be archived, archiving preserves the status,
-- and un-archiving restores nothing because nothing was lost.
--
-- -----------------------------------------------------------------------------
-- WHAT ARCHIVING DOES NOT DO, WHICH IS ALMOST EVERYTHING
-- -----------------------------------------------------------------------------
--
-- It is a VISIBILITY fact and not a lock. An archived plan can still be read,
-- edited, approved, run, reported on and deleted; every existing gate applies
-- unchanged. What changes is one thing: GET /projects/{id}/plans omits it unless
-- asked for.
--
-- That restraint is the design. A flag that also froze the plan would be CLOSED
-- under another name — a terminal state with the ladder's problems and none of
-- its clarity — and the audit row asks for old plans to leave the working list,
-- not for them to become unusable. A team that archives a plan and then needs
-- one more run against it should get the run, not a refusal citing a checkbox.
--
-- The 409 on DELETE is untouched for the same reason. Archiving is what to do
-- INSTEAD of deleting a plan runs were executed against; it is not a way to make
-- that delete succeed, and PlanDeleteService's refusal still names the runs.
--
-- -----------------------------------------------------------------------------
-- A TIMESTAMP IN THE DATABASE, A BOOLEAN ON THE WIRE, AND NO archived_by
-- -----------------------------------------------------------------------------
--
-- This is project.archived_at's shape, copied deliberately: PUT /projects/{id}/
-- archive has existed since M2 and does the same thing one level up, so a second
-- design for the same act would be two answers to one question. Following it
-- means:
--
--   * archived_at is a timestamp, because a flag that cannot say WHEN is a fact
--     somebody has to go to the audit trail for anyway, and the partial index
--     below needs the predicate either way;
--   * TestPlanView narrows it to a boolean, as ProjectView does, because "is
--     this in the working list" is the whole of what a client does with it;
--   * there is NO archived_by. The first draft of this migration had one, and
--     Project does not: the actor lives in audit_event as plan.archived /
--     plan.unarchived, which is where "who changed what, when" lives in this
--     product. A column stamped on every archive and read by nothing is a
--     write-only column, which ArchitectureRulesTest pins as a finding — and
--     rightly, since the audit row already answers it.
--
-- The partial index serves the default read, which is "the plans that are NOT
-- archived, newest key first" — the one query the plan list makes on every
-- project page load. company_id LEADS it, per ERD §0.1 point 3.
-- =============================================================================

ALTER TABLE test_plan ADD COLUMN archived_at TIMESTAMPTZ;

COMMENT ON COLUMN test_plan.archived_at IS
    'When this plan was put away, or NULL while it is in the working list (V65, audit D2). A '
    'VISIBILITY fact and orthogonal to status: any of DRAFT/IN_REVIEW/APPROVED may be archived, '
    'archiving preserves the status, and an archived plan can still be read, edited, approved, run '
    'and deleted. It exists because V32 removed CLOSED — a plan that runs were executed against can '
    'never be deleted (PlanDeleteService 409s) and had no way at all to leave the list. WHO '
    'archived it lives in audit_event (plan.archived / plan.unarchived), exactly as the actor '
    'behind project.archived_at does — this table deliberately grows no actor column beside this '
    'one, because a column written on every archive and read by nothing is a finding.';

CREATE INDEX test_plan_working_list_idx
    ON test_plan (company_id, project_id, key_seq DESC)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
