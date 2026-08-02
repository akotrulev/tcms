-- V24 — `review_comment` gets its first writer, and the two things V1 could not decide.
--
-- The table has been in the schema since V1 with no entity, no query and no writer of any kind —
-- the last of the three review tables in that state, and the one §77 named on its way out. §6g is
-- explicit about why it matters: "Review without inline comments degrades into Slack conversations
-- that vanish." This migration lands with `ReviewComment`, and changes exactly two things.
--
-- 1. A RESOLUTION IS AN ACT, AND AN ACT WITH NO ACTOR IS NOT A RECORD.
--
-- V1 gave the table `resolved_at` and no `resolved_by`, which is enough to grey a thread out and not
-- enough for the one thing this whole apparatus exists for: §6g's "proving to an auditor that
-- someone competent signed off". Every other act in the record names its actor — `review.created_by`
-- and `approved_by`, `review_participation.user_id` — and a thread marked dealt-with by nobody is
-- the one entry an auditor cannot follow up. Nullable, like every other actor column here, because
-- an offboarded account still leaves the fact that the thread was resolved standing (§66).
--
-- The CHECK is one-directional on purpose. A resolver with no timestamp is a half-applied row and
-- is refused; a timestamp with no resolver is allowed, because that is what an unattributable
-- resolution looks like and `review.created_by` is nullable for the same reason. The service writes
-- and clears both together — the constraint is the backstop, in V20's and V21's sense, not the
-- check.
--
-- 2. THE READ PATH HAD NO INDEX.
--
-- Comments are read exactly one way — every comment of the reviews being rendered, oldest first,
-- one query for the whole response (`ReviewCommentRepository.findForReviews`, the shape
-- `findForReviews` on participations already has). V22 added that index for participations and
-- could not add this one, because nothing read the table. `company_id` leads it per ERD §0.1, which
-- `SchemaConventionTest` asserts over every index mentioning the column.
--
-- What this migration deliberately does NOT do is touch `anchor_type` / `anchor_id`. §6g's
-- "anchored to a specific step or field where possible" is real and unbuilt: validating a step id
-- needs `repository.test_step`, a table this module may not import, so the honest choices are a
-- port (a third `PeopleDirectory`-shaped inversion, for a display aid) or an unvalidated reference
-- — which is §64's `sharedStepId` defect written down in advance. The columns stay NULL on every
-- row until one of those is chosen; see `ReviewComment`'s header.

ALTER TABLE review_comment ADD COLUMN resolved_by UUID;

ALTER TABLE review_comment ADD CONSTRAINT review_comment_resolution_check
    CHECK (resolved_by IS NULL OR resolved_at IS NOT NULL);

COMMENT ON COLUMN review_comment.resolved_by IS
    'Who marked the thread dealt with (V24). Null while it is open, and null on a resolved thread '
    'whose account has since been offboarded — the resolution stands either way.';

COMMENT ON CONSTRAINT review_comment_resolution_check ON review_comment IS
    'resolved_at and resolved_by are one act. A resolver with no timestamp is a half-applied row '
    'and is refused; a timestamp with no resolver is an unattributable resolution and is allowed, '
    'for the reason review.created_by is nullable.';

COMMENT ON COLUMN review_comment.parent_id IS
    'The thread root this is a reply to, or NULL when this IS a root. Exactly one level deep — '
    '`ReviewCommentEdit` refuses a reply to a reply, because a thread with no bottom has no '
    'rendering and §6g asks for threads, not for a tree.';

CREATE INDEX ix_review_comment_review
    ON review_comment (company_id, review_id, created_at);
