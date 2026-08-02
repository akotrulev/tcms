-- V22 — the review record gets a writer, and two things V1 could not know become constraints.
--
-- `review`, `review_participation` and `review_comment` have been in the schema since V1 with no
-- entity, no query and no writer of any kind: §6g's reviewer apparatus, the last unbuilt half of the
-- review workflow now that every item-side ladder exists (a case's §62, a plan's §73, a suite's
-- §75). This migration lands with the first writer, and changes exactly two things V1 got wrong or
-- left open — not because they were careless, but because neither is decidable until something
-- actually writes the table.
--
-- 1. A REVIEW THAT ENDS WITHOUT A VERDICT IS NOT A REJECTION.
--
-- V1's check allows IN_REVIEW, APPROVED and REJECTED. §6g's diagram justifies all three: a reviewer
-- may reject, and a rejection sends the item back to DRAFT. But the ladders shipped in §62/§73/§75
-- make the SAME move — IN_REVIEW back to DRAFT — available to the person who submitted it, at the
-- edit key, because withdrawing your own submission needs no approval rights and §6g says so.
-- Both acts leave IN_REVIEW downward, and closing both as REJECTED would write a rejection nobody
-- performed into the one record a team keeps precisely to show an auditor who said what. Hence a
-- fourth value. The distinction is derived, never sent: the submitter taking their own submission
-- back is a withdrawal, anyone else sending it back is a rejection (`ReviewService`).
--
-- 2. ONE OPEN REVIEW PER SUBJECT, AND THE INDEX IS THE BACKSTOP RATHER THAN THE CHECK.
--
-- The service opens a review when an item enters IN_REVIEW and closes it when it leaves, so at most
-- one review per subject can be open — an invariant with nothing behind it until now. Two leads
-- submitting the same case at the same instant both read "no open review", both insert, and the
-- subject acquires two open reviews that no later close can reconcile: the closing code updates one
-- of them and the other stays IN_REVIEW for ever, on an item that is not in review. V20's and V21's
-- reason, in the same words — an application check with no constraint behind it is a check with no
-- backstop.
--
-- PARTIAL, because the closed rows are the history: a case submitted, approved, edited and
-- resubmitted has several reviews and every one of them must survive. Only IN_REVIEW is exclusive.
--
-- `company_id` leads both indexes below, per ERD §0.1 — `SchemaConventionTest` asserts it over every
-- index that mentions the column, and a uniqueness constraint that did not lead with it would be
-- enforced across companies.

ALTER TABLE review DROP CONSTRAINT review_state_check;

ALTER TABLE review ADD CONSTRAINT review_state_check
    CHECK (state IN ('IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'));

COMMENT ON COLUMN review.state IS
    'IN_REVIEW while open. Three ways to end (V22): APPROVED by someone holding *:approve, '
    'REJECTED when another person sends it back, WITHDRAWN when the submitter takes it back. '
    'The last is not a rejection and must not read as one in an audit.';

CREATE UNIQUE INDEX uq_review_open_per_subject
    ON review (company_id, subject_type, subject_id)
    WHERE state = 'IN_REVIEW';

COMMENT ON INDEX uq_review_open_per_subject IS
    'At most one OPEN review per subject (V22). Partial, because the closed rows are the subject''s '
    'review history and there may be any number of them.';

CREATE INDEX ix_review_subject
    ON review (company_id, subject_type, subject_id, created_at DESC);

CREATE INDEX ix_review_participation_review
    ON review_participation (company_id, review_id, created_at);
