-- =============================================================================
-- V71 — documentation pages become reviewable: the opt-in column, the fourth
--        subject type, and the 56th catalogue key
--
-- Plan 005 S3 + S9b (docs/plans/005-renders-backend-support.md), built as one
-- migration per 006's batching: with one operator and the numbers fixed by batch
-- order, the "inert column first" split bought nothing, so the column, the CHECK
-- and the seed rows land together.
--
-- THREE THINGS, ONE DECISION
--
-- 1. project_settings.review_enabled_doc — the fourth §6g opt-in, off by default
--    like the other three. When true a DOC review can be opened and doc:review
--    verdicts recorded; when false the page renders no panel. Switching it off
--    does not close open DOC reviews (ReviewService's rule for the other three).
--
-- 2. review.subject_type admits 'DOC'. V1's inline CHECK named three values, so
--    the enum member alone would have been a constraint violation on the first
--    submit. Dropped and re-added by its auto-generated name, the way V22 did the
--    state check.
--
-- 3. doc:review — the 56th permission. 005 S9b said "no migration" and 006's
--    batching corrected it: V3 seeded the catalogue by INSERT and nothing adds a
--    key without one. Seeded to the same holders as plan:review — Company Admin,
--    Test Lead, Test Engineer — plus Instance Admin (V3 derived its rows from the
--    catalogue AT V3 TIME, so a later key needs an explicit row) and Agent (V52
--    derived it from Test Engineer the same way). Automation Service holds no
--    *:review key and does not follow. Viewer holds case:comment, which is one
--    key across every reviewable artifact, so commenting on a DOC review needs
--    nothing new (roles spec §A3, the ladder SeedDataTest.everyReviewerIsAlsoACommenter
--    derives).
--
-- Idempotent throughout, like V3: ON CONFLICT DO NOTHING on every row.
-- =============================================================================

ALTER TABLE project_settings
    ADD COLUMN review_enabled_doc BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN project_settings.review_enabled_doc IS
    'The §6g opt-in for documentation pages (V71, plan 005 S9b). Off by default; when true a DOC '
    'review can be opened and doc:review verdicts recorded. Turning it off does not close open DOC '
    'reviews — the same rule as the other three.';

ALTER TABLE review DROP CONSTRAINT review_subject_type_check;

ALTER TABLE review ADD CONSTRAINT review_subject_type_check
    CHECK (subject_type IN ('CASE', 'SUITE', 'PLAN', 'DOC'));

COMMENT ON COLUMN review.subject_type IS
    'Which artifact is under review. DOC since V71 (a documentation page); the other three since V1. '
    'Paired with subject_id, which has no foreign key because the four tables are in three modules.';

INSERT INTO permission (key, area, description) VALUES
    ('doc:review', 'review', 'Take part in a documentation page review — LGTM, request changes')
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_permission (role_id, permission_key)
SELECT id, 'doc:review'
  FROM role
 WHERE id IN ('10000000-0000-4000-8000-000000000001',   -- Instance Admin
              '10000000-0000-4000-8000-000000000002',   -- Company Admin
              '10000000-0000-4000-8000-000000000003',   -- Test Lead
              '10000000-0000-4000-8000-000000000004',   -- Test Engineer
              '10000000-0000-4000-8000-000000000008')   -- Agent (Test Engineer entire, V52)
ON CONFLICT DO NOTHING;
