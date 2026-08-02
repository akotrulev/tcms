-- V25 — who was ASKED. The last unbuilt half of §6g's reviewer apparatus.
--
-- `review_participation` records who *spoke*; nothing has ever recorded who was *asked*. §6g's
-- Mechanics list is explicit that the two are different facts — "Reviewers — assign a user *or* a
-- team (§6b); any member of the team can action it" — and the consequence of having only the first
-- is that the workflow §76–§79 built has no inbox: a review opens, and the people who should look
-- at it find out through some other product. §6l's board names the same gap from the other end
-- ("Review requested (§6g) → card for each reviewer"), and it cannot draw a card for a request that
-- was never stored.
--
-- -----------------------------------------------------------------------------
-- A WORKLIST ENTRY, NOT AN AUDIT RECORD — and every other decision here follows from it
-- -----------------------------------------------------------------------------
--
-- §6g says a team wants review for "proving to an auditor that someone competent signed off". That
-- record is `review` plus `review_participation`: who submitted, who spoke, who signed. This table
-- is the *other* thing — a routing hint, alive only while the review is open, saying whose queue
-- the item should appear in. Three consequences, and they are the file:
--
--   * UNASSIGNING IS A HARD DELETE. No `removed_at`, no tombstone. Withdrawing a request is not an
--     act an auditor follows up; asking the wrong person and correcting it should leave no trace,
--     because a trail of retracted requests reads as a history of suspicion.
--   * A DELETED TEAM CASCADES. §71 made deleting a team holding `role_assignment` rows a 409 with
--     the count, because that cascade would silently revoke authority. This is the case that rule
--     does NOT cover: what cascades away here is a request, not a grant, and the review stays
--     visible, reviewable and approvable by exactly the same people afterwards. Refusing instead
--     would make a team undeletable until every review it was asked to look at had closed — and a
--     review whose only assignee is a dissolved team is precisely the one that will never close on
--     its own. `TeamWriteService`'s refusal is deliberately NOT extended to this table.
--   * RE-ASSIGNING SOMEBODY ALREADY ASSIGNED WRITES NOTHING. The unique indexes below are the
--     backstop; `ReviewService` answers 201 with the review and leaves the row alone. Re-stamping
--     `assigned_at`/`assigned_by` would hand the request to whoever clicked last — §79's resolution
--     defect, in the one place it would recur.
--
-- -----------------------------------------------------------------------------
-- WHY ONE TABLE WITH TWO NULLABLE COLUMNS RATHER THAN TWO TABLES
-- -----------------------------------------------------------------------------
--
-- §6b's "a user *or* a team" is one concept with two spellings, and every consumer asks the same
-- question of both: is this review on somebody's list. Two tables would mean two queries and a
-- UNION at every read, and the panel would render two lists that mean one thing.
-- `role_assignment` already carries exactly this shape (`user_id` / `team_id`, one of them), so
-- this is the schema's existing answer rather than a new one. The CHECK is what keeps it honest:
-- `num_nonnulls(...) = 1` refuses both the row that names nobody and the row that names two.
--
-- -----------------------------------------------------------------------------
-- WHY THE UNIQUENESS IS TWO PARTIAL INDEXES AND NOT ONE PLAIN ONE
-- -----------------------------------------------------------------------------
--
-- A plain `UNIQUE (company_id, review_id, assignee_user_id, assignee_team_id)` enforces nothing:
-- every team row has a NULL user, NULL is never equal to NULL, so the same team could be asked a
-- hundred times and the constraint would admit all of them. V20's `COALESCE(parent_id, nil)` is the
-- same trap solved the other way; here the two sides are genuinely independent, so the honest
-- spelling is one partial index per side.
--
-- `company_id` leads both, per ERD §0.1 point 3 — `SchemaConventionTest` fails the build on an
-- index that mentions the column without leading with it.

CREATE TABLE review_assignment (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    review_id        UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    assignee_user_id UUID REFERENCES app_user(id),
    assignee_team_id UUID REFERENCES team(id) ON DELETE CASCADE,
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by      UUID,
    CONSTRAINT ck_review_assignment_one_assignee
        CHECK (num_nonnulls(assignee_user_id, assignee_team_id) = 1)
);

COMMENT ON TABLE review_assignment IS
    'Who was ASKED to review (V25, plan §6g). A worklist entry, not part of the audit record — '
    'that is review_participation. Unassigning deletes the row, and a deleted team takes its '
    'requests with it.';
COMMENT ON COLUMN review_assignment.assignee_user_id IS
    'One named person. Mutually exclusive with assignee_team_id — see ck_review_assignment_one_assignee. '
    'References instance-level app_user, so the FK alone does NOT prove the person belongs to this '
    'company: ReviewAssignmentService checks project visibility, which is the §71 defect one table over.';
COMMENT ON COLUMN review_assignment.assignee_team_id IS
    'A team, any member of which may action it (§6g). ON DELETE CASCADE, unlike role_assignment''s '
    'identically-shaped column, because what is lost is a request rather than a grant — see this '
    'migration''s header for why §71''s 409 is deliberately not extended here.';
COMMENT ON COLUMN review_assignment.assigned_by IS
    'Who asked. Nullable like every other actor column in this schema (§66): an offboarded account '
    'does not un-ask the question.';

-- The read path, and the only one: every assignment of the reviews being rendered, oldest first,
-- one query for the whole response — `findForReviews`' shape on participations (V22) and comments
-- (V24), a third time.
CREATE INDEX ix_review_assignment_review
    ON review_assignment (company_id, review_id, assigned_at);

CREATE UNIQUE INDEX uq_review_assignment_user
    ON review_assignment (company_id, review_id, assignee_user_id)
    WHERE assignee_user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_review_assignment_team
    ON review_assignment (company_id, review_id, assignee_team_id)
    WHERE assignee_team_id IS NOT NULL;

-- The second read path, and the reason this index is here before the query is: §6l's board and the
-- pending-review queue `ReviewController` refuses to serve today both ask "what is on THIS person's
-- list", which is a scan by assignee rather than by review. Adding it now costs one index on a
-- table with tens of rows per project; adding it later costs a migration on the slice that can
-- least afford one, since the queue is the first thing that will be built on this table.
CREATE INDEX ix_review_assignment_assignee_user
    ON review_assignment (company_id, assignee_user_id)
    WHERE assignee_user_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- RLS — the 58th policy.
--
-- nullif(...) rather than V1's bare current_setting, for V4's reason: a GUC set once on a pooled
-- connection resets to '' rather than NULL, and ''::uuid raises instead of matching nothing. A
-- table created after V4 must be born with the fixed predicate. SchemaConventionTest counts the
-- policies, so 57 → 58 with this file.
-- -----------------------------------------------------------------------------

ALTER TABLE review_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON review_assignment USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
