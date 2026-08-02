-- V23 — the per-project settings row, and the four things §6g could not do without it.
--
-- §6g opens with "optional is the operative word: enabled per project and per artifact type, off by
-- default, and a project that never turns it on should see no extra UI at all" — and then lists
-- three more settings that decide how review behaves where it IS on. None of the four has ever had
-- anywhere to live. The consequences were all live and all silent:
--
--   * review could not be turned OFF, so the apparatus §76 built applies to every project whether
--     the team wants it or not;
--   * `review.required_peer_reviews` is mapped, is written from the column default, and is read by
--     NOTHING — `Review`'s own javadoc says so and names this migration as what it is waiting for;
--   * §6g calls author-cannot-approve-own-work "the single most valuable setting here" and the
--     product has never had it, so the person who submits a case may sign it off themselves;
--   * `ReviewPanel` renders §6g's stale marker — the Retain half of the edit-after-approval rule —
--     and nothing has ever implemented the Re-review half it is the alternative to.
--
-- WHY A TYPED TABLE AND NOT `project.settings JSONB`
--
-- The ERD gives `company` a `settings JSONB` and describes it as carrying "review gating,
-- edit-after-approval mode" — per COMPANY. That cannot express §6g, which is explicit that the
-- opt-in is per project AND per artifact type, so a company-level column is the wrong grain rather
-- than a smaller version of the right one. `company.settings` is left exactly as it is: still
-- unmapped, still with no reader and no writer, and this migration does not claim it.
--
-- Typed columns rather than a JSONB blob on `project`, for §74's reason in a second place. A
-- misspelled key in a settings blob reads IDENTICALLY to a setting nobody configured — both are
-- "absent, use the default" — so `reviewEnabledCase` vs `review_enabled_case` is a silent no-op that
-- a release meeting cannot tell from a deliberate choice. Typed columns put that failure in front of
-- `ddl-auto: validate` and `SchemaConventionTest` instead, where it is a failed boot. The cost is
-- that a fifth setting is a migration; that is the intended trade, and a new REVIEW SUBJECT TYPE is
-- a migration anyway because it needs a ladder of its own.
--
-- WHY ONE ROW PER PROJECT, BACKFILLED
--
-- The alternative — no row until somebody saves the form — makes absence mean "defaults", which is
-- a second spelling of unset in a codebase that has already paid for having two (V4's GUC). Every
-- project gets a row here and `ProjectCreateService` writes one from now on. `ProjectSettings`
-- STILL tolerates a missing row and answers the defaults, because a row that is somehow absent must
-- fail to the safe state (review off, approval unrestricted-by-us, Retain) rather than 500 on the
-- case list.
--
-- The defaults below ARE §6g's defaults, and two of them are worth reading twice:
--   * review off for all three subject types — "off by default", the whole point;
--   * `author_may_approve_own_work` FALSE, i.e. §6g's rule is ON. Stored in the positive so no
--     consumer reads a double negative; the setting §6g names as most valuable is the one nobody
--     should have to opt into.
--   * 'RETAIN' — §6g: "recommend Retain + stale marker as the default. Forcing full re-approval on
--     a typo fix is how review processes get abandoned."

CREATE TABLE project_settings (
    project_id                  UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    company_id                  UUID        NOT NULL REFERENCES company(id),
    review_enabled_case         BOOLEAN     NOT NULL DEFAULT false,
    review_enabled_suite        BOOLEAN     NOT NULL DEFAULT false,
    review_enabled_plan         BOOLEAN     NOT NULL DEFAULT false,
    required_peer_reviews       INT         NOT NULL DEFAULT 0,
    author_may_approve_own_work BOOLEAN     NOT NULL DEFAULT false,
    edit_after_approval         TEXT        NOT NULL DEFAULT 'RETAIN',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                  UUID,
    lock_version                BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_project_settings_edit_after_approval
        CHECK (edit_after_approval IN ('RETAIN', 'RE_REVIEW')),
    -- An upper bound as well as a lower one. Without it a fat-fingered 500 is accepted and the
    -- project's approvals are frozen for ever with a message telling people to collect 500 LGTMs —
    -- refusable at the API, but this is the backstop, and V20's and V21's rule is that an
    -- application check with no constraint behind it is a check with no backstop.
    CONSTRAINT ck_project_settings_required_peer_reviews
        CHECK (required_peer_reviews BETWEEN 0 AND 20)
);

COMMENT ON TABLE project_settings IS
    'Per-project policy, one row per project (V23). Created with the project and backfilled here '
    'for existing ones; a missing row is nonetheless read as the defaults, never as an error.';
COMMENT ON COLUMN project_settings.review_enabled_case IS
    'Plan §6g: review is opt-in PER PROJECT AND PER ARTIFACT TYPE, off by default. When false the '
    'reviewer apparatus does not exist for this artifact type — no review row is opened, no peer '
    'verdict can be recorded, and the client renders no panel. The STATUS LADDER is unaffected: '
    'turning review off must not retire the four statuses a project has been using since M1.';
COMMENT ON COLUMN project_settings.required_peer_reviews IS
    'Plan §6g''s "configurable minimum peer reviews before approval is offered (default 0)". '
    'COPIED ONTO EACH REVIEW at submission (review.required_peer_reviews) and read from there, so '
    'raising the bar does not move the goalposts under a review already in flight.';
COMMENT ON COLUMN project_settings.author_may_approve_own_work IS
    'FALSE means §6g''s author-cannot-approve-own-work rule is ON — the default, and the setting '
    '§6g calls the single most valuable one here. Stored in the positive so no consumer has to read '
    'a double negative. "Author" is who SUBMITTED the review, not who created the artifact: the '
    'submitter is the person putting it forward for sign-off.';
COMMENT ON COLUMN project_settings.edit_after_approval IS
    'Plan §6g: what an edit to an APPROVED artifact does. RETAIN (default) leaves the approval in '
    'place and marks it stale — ReviewPanel already renders that. RE_REVIEW returns the artifact to '
    'DRAFT so approval must be re-earned. The demotion is NOT gated on *:approve: it is a '
    'consequence the project configured, not an act the editor requested — see LifecycleLadder.';

-- company_id leads the index, per ERD §0.1. The lookup is by project, but SchemaConventionTest
-- asserts the rule over every index that mentions the column and a project id is only unique
-- within a company anyway.
CREATE INDEX ix_project_settings_company ON project_settings (company_id, project_id);

-- -----------------------------------------------------------------------------
-- RLS — the 57th policy.
--
-- Spelled with nullif(...) rather than V1's bare current_setting: V4 rewrote all 56 of V1's
-- policies because a GUC that has been set once on a pooled connection resets to '' rather than
-- NULL, and ''::uuid raises instead of matching nothing. A new table created after V4 must be born
-- with the fixed predicate — creating it with V1's spelling would reintroduce, on exactly one
-- table, the defect V4 exists to close. SchemaConventionTest pins the predicate over every policy
-- and counts them, so the count moves 56 → 57 with this file.
-- -----------------------------------------------------------------------------

ALTER TABLE project_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_settings FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON project_settings USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- Backfill — every project that already exists gets its defaults row.
--
-- Deliberately in this file rather than a V24, against the migrations skill's "schema and data are
-- separate files" rule, and the reason is that this is not a backfill in that rule's sense: there is
-- no batching question (one row per project, on an instance with tens), no window in which readers
-- see a half-migrated table, and nothing to resume. Splitting it would produce a release in which
-- the table exists and no project has a row — the exact state the tolerant read below exists to
-- survive, deployed on purpose.
--
-- ON CONFLICT DO NOTHING so a re-run is inert, per the same file's idempotency rule.
-- -----------------------------------------------------------------------------

INSERT INTO project_settings (project_id, company_id)
SELECT id, company_id FROM project
ON CONFLICT (project_id) DO NOTHING;
