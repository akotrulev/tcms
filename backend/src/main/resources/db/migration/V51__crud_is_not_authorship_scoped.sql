-- =============================================================================
-- V51 — CRUD on plans, cases and steps belongs to every non-Viewer contributor,
--        whoever first created the row
--
-- Source: a product decision taken 2026-08-14, stated as a requirement rather
--         than derived from a defect: "CRUD on test plan, cases and steps can be
--         done by any user that is not a viewer, no matter who first created it."
--         Handoff §207. The roles spec is updated in the same commit and remains
--         authoritative; this file is the data.
--
-- Three grants move, and they are two different kinds of change:
--
--   case:delete       OWN -> ALL for Test Engineer. An AUTHORSHIP gate, removed.
--   evidence:delete   OWN -> ALL for Test Engineer. The same, and it moves for
--                     consistency rather than because the requirement names
--                     evidence: leaving one OWN row behind would keep the
--                     qualifier populated by an exception nobody decided.
--   plan:delete       granted to Test Engineer. A TIER gate, removed. Not a
--                     qualifier change — Test Engineer never held this key at
--                     all, so there was no row to requalify.
--
-- -----------------------------------------------------------------------------
-- WHAT THIS REVERSES, AND WHY THE OLD REASONING IS NOT DELETED
-- -----------------------------------------------------------------------------
--
-- V12 introduced the qualifier column FOR these two rows and argued the case at
-- length; roles spec §A3.5 called it "creative vs destructive" — a Test Engineer
-- is never blocked from producing, only from unmaking SOMEONE ELSE'S work.
--
-- That principle is not being called wrong. It is being overruled for this
-- surface, on the same ground §A5 already used to de-qualify `case:edit` on
-- 2026-07-31: this product's subject is a SHARED test repository, and a
-- repository whose rows answer to their author is a set of private notebooks in
-- one table. The 2026-07-31 reversal stopped at editing because editing is
-- producing and deleting is unmaking. The decision here is that the distinction
-- does not survive contact with the actual workflow — the case a colleague wrote
-- last quarter and left duplicated is exactly the row someone else must remove —
-- and that soft delete, which V1 gives every one of these tables, is what makes
-- the risk recoverable rather than the qualifier.
--
-- V12's and V40's comments still argue the old position. THEY ARE NOT EDITED:
-- an applied migration is immutable here (Flyway checksums a re-run against a
-- migrated database), so those files are history in the same way V1's
-- `suite_condition` comment is history after V49. When they disagree with the
-- roles spec, the spec wins and the migration is the record of what was once
-- believed. This header is where a reader arriving at V12 by grep is sent.
--
-- -----------------------------------------------------------------------------
-- THE `OWN` QUALIFIER SURVIVES WITH ZERO ROWS, AND THAT IS DELIBERATE
-- -----------------------------------------------------------------------------
--
-- After this migration NO grant in the catalogue carries `OWN`. The value stays
-- in the CHECK constraint, `PermissionQualifier.OWN` stays in the enum, and
-- `IdentityProjectAccess.canDoOnAuthored` stays wired — all of it unreachable
-- from seeded data.
--
-- Deleting the mechanism was considered and refused. `PLAN_SCOPE` (V19) is the
-- same shape and is populated, so the column and the resolution seam are earning
-- their keep regardless; what would be removed is one enum member and one switch
-- arm, and what it would cost is the ability to express "only rows you authored"
-- the next time a permission needs it, plus a migration that drops a CHECK value
-- to no measurable end. An unpopulated value with a comment saying so is
-- cheaper than a capability that has to be rebuilt.
--
-- The comment below is the thing that makes it legible: without it, the next
-- reader finds `OWN` in the constraint, greps for a grant that carries it, finds
-- none, and cannot tell an empty population from a seeding bug.
--
-- -----------------------------------------------------------------------------
-- WHAT DOES *NOT* MOVE, EACH FOR A STATED REASON
-- -----------------------------------------------------------------------------
--
--   Agent (V40) and Automation Service (V41) — the two token-only ceilings.
--   Neither gains anything here. Both ENUMERATE their grants rather than
--   deriving them from Test Engineer, so Test Engineer's new `plan:delete` does
--   not reach them through the data, and the decision is that agent access
--   creates, reads and edits but does not delete.
--
--   Their ARGUMENTS do change, and that is a spec edit, not a data one. V40
--   justified dropping case:delete and evidence:delete by the OWN qualifier
--   colliding with api_token.owner_id — an argument this migration deletes the
--   premise of. The new reason is the plainer one that was always underneath it.
--   And V40 listed plan:delete under "never held, INHERITED FROM TEST ENGINEER'S
--   OWN BOUNDARY": that inheritance stops excluding it the moment Test Engineer
--   holds it, so it becomes an explicit drop. Agent is Test Engineer minus FIVE
--   from here on. SeedDataTest computes that delta rather than transcribing it,
--   which is why this paragraph is a note and not a bug.
--
--   suite:delete and artifact:delete stay Lead-only. The requirement named
--   plans, cases and steps. A suite is a flat named COLLECTION since §98 —
--   deleting one destroys the grouping, not the cases in it — and a
--   project-scope artifact is inherited read-only by every plan (plan §6n), so
--   both are decisions about shared structure rather than about content a
--   contributor authored. §A3.5 is rewritten around that narrower claim.
--
--   run:close and result:amend stay Lead-only. They are judgements about the
--   WORK, not CRUD on an artifact, and no part of the requirement reaches them.
--
--   Author-cannot-approve-own-work stays on. It IS an authorship rule, and it
--   is the one that survives: approving is not CRUD, and the whole point of a
--   second signature is that it is somebody else's.
--
-- -----------------------------------------------------------------------------
-- STEPS HAVE NO KEY OF THEIR OWN
-- -----------------------------------------------------------------------------
--
-- The requirement names steps. There is nothing to grant: a step is part of its
-- case, written through CaseUpdateService and gated by `case:edit`, which Test
-- Engineer has held unqualified since V3. Recorded here so the next reader
-- looking for a `step:*` key learns it never existed rather than assuming it was
-- missed.
--
-- DATA ONLY — no DDL, no structural change. Every statement is idempotent by
-- construction: an UPDATE to a value a row already holds is a no-op, the INSERT
-- takes ON CONFLICT DO NOTHING, and COMMENT ON is replace-only.
-- =============================================================================

-- 1. The two authorship gates, removed. This is the exact inverse of V12's only
--    UPDATE, down to the role id and the key list.
UPDATE role_permission
   SET qualifier = 'ALL'
 WHERE role_id = '10000000-0000-4000-8000-000000000004'   -- Test Engineer
   AND permission_key IN ('case:delete', 'evidence:delete');

-- 2. The tier gate, removed. Takes the DEFAULT 'ALL' from V12 — a plan is
--    deleted by whoever needs it gone, which is the whole content of the
--    decision, so a qualifier here would reintroduce what statement 1 removes.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key      -- Test Engineer
  FROM (VALUES ('plan:delete')) AS granted(key)
ON CONFLICT DO NOTHING;

-- 3. The column comment, restated. V19 wrote the text this replaces and it is
--    still correct about what OWN MEANS; what it cannot say is that nothing
--    carries it any more. Restated rather than edited in place for V49's reason:
--    an applied migration is immutable, and `\d+` prints the newest COMMENT ON.
COMMENT ON COLUMN role_permission.qualifier IS
    'WHICH ROWS this grant of the permission covers. ALL = every row in scope. '
    'PLAN_SCOPE = only the rows of the plan being acted on, never the project-scope row '
    'every plan inherits read-only (roles spec Artifacts table, V19). '
    'OWN = only rows the caller authored (created_by), in any state. '
    '  OWN IS CARRIED BY NO GRANT AS OF V51, AND THAT IS A DECISION, NOT A GAP: CRUD on plans, '
    'cases and steps belongs to every non-Viewer contributor regardless of who created the row '
    '(handoff §207), which removed the only two rows that ever held it. The value, the enum member '
    'and IdentityProjectAccess.canDoOnAuthored are kept against the next permission that needs '
    '"only what you wrote" — do not read the empty population as a seeding bug. '
    '  Resolved at check time against the row being acted on, because the evaluator cannot see '
    'that row: PermissionEvaluator answers the qualifier, the project-scoped write gate applies it. '
    '  STRONGEST WINS: a caller holding the same permission through two grants (directly and via a '
    'team, or PROJECT and COMPANY scope) gets ALL if any grant is ALL. A Company Admin who is also '
    'a project''s Test Engineer must not be demoted by the weaker grant. '
    '  NOT a permission key: case:delete:own would double the catalogue and still could not '
    'express "in any state" (V3 §3). NOT keyed on the role name: code checks permissions, never '
    'role names (plan §6b), or the first custom role is mis-answered.';
