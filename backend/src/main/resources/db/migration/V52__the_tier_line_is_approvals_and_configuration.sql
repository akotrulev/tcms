-- =============================================================================
-- V52 — the Test Engineer / Test Lead line is redrawn to exactly two things:
--        APPROVALS and PROJECT CONFIGURATION. And `Agent` becomes Test Engineer
--        entire, with the delete gate moved out of the catalogue and into the
--        MCP tool definitions as a consent prompt.
--
-- Source: a product decision taken 2026-08-15, stated as a requirement rather
--         than derived from a defect:
--
--           "Viewer  — can see all cases, steps, plans, etc.
--            Test Engineer and up — same as Viewer, plus create, edit, delete.
--            Test Lead — same as the engineer, plus adding and managing
--                        permission on the project, and giving approvals.
--            Company Admin — anything within the company.
--            Instance Admin — anything within any company.
--            Automation Service — stays as it is now."
--
--         Handoff §210. The roles spec is updated in the same commit and remains
--         authoritative; this file is the data.
--
-- Three of those five rows required no change and that is worth stating, because
-- "already true" and "not done" are indistinguishable from a diff:
--
--   Company Admin   V3 enumerates every PROJECT key plus company configuration.
--   Instance Admin  V3 DERIVES it by joining the whole catalogue, so it has held
--                   everything since M0 and holds anything a later migration adds.
--   Automation Service   untouched, by name, in the requirement itself. It is the
--                   smallest grant in the product because a leaked CI token is the
--                   realistic breach (spec §A6), and nothing here widens it.
--
--   Viewer          also unchanged. It already reads every readable surface —
--                   case:read, project:read, doc:read, artifact:read, report:view —
--                   plus case:comment and report:export, which it keeps.
--
-- -----------------------------------------------------------------------------
-- WHAT ACTUALLY MOVES
-- -----------------------------------------------------------------------------
--
-- Test Engineer gains five keys and loses one qualifier:
--
--   suite:delete      Was Lead-only as SHARED STRUCTURE (V51's header argues it
--   artifact:delete   at length, and §A4 line 1 was rewritten around exactly that
--                     claim eight days ago). The requirement overrules it: a
--                     contributor creates, edits and deletes, and the structure/
--                     content distinction is not one the sentence draws.
--
--   run:close         Was Lead-only as a JUDGEMENT ABOUT THE WORK rather than
--   result:amend      CRUD on an artifact — V51 §"WHAT DOES NOT MOVE" is the most
--                     recent statement of it. Overruled on the same ground.
--
--   doc:publish       Was Lead-only as a readiness signal.
--
--   artifact:edit     PLAN_SCOPE -> ALL. "Edit" without a scope qualifier is what
--                     the requirement says, so the project-scope artifact every
--                     plan inherits read-only is now editable by a contributor.
--                     THIS IS THE ONE WITH A CONSEQUENCE ELSEWHERE — see below.
--
-- After it, Test Lead minus Test Engineer is exactly seven keys, and they are the
-- two things the requirement names:
--
--   case:approve · suite:approve · plan:approve          giving approvals
--   project:members · project:settings · project:audit   adding and managing
--   integration:manage                                    permission on the project
--
-- That is now a CLOSED list rather than a description, and SeedDataTest asserts it
-- as an exact set in both directions. The previous three-line boundary (spec §A4)
-- collapses to two: review-vs-approve and content-vs-configuration. Line 1 —
-- whatever it was called in a given month, "creative vs destructive" (V3, V12) or
-- "content vs shared structure and judgement" (V51) — is gone. It is not being
-- called wrong twice; it is being removed as a tier line, having survived two
-- attempts to state it in six weeks. What both attempts had in common is that the
-- line they drew was legible to whoever wrote it and not to the contributor
-- standing in front of a greyed-out button on a colleague's abandoned duplicate.
--
-- -----------------------------------------------------------------------------
-- BOTH QUALIFIERS ARE NOW CARRIED BY ZERO ROWS
-- -----------------------------------------------------------------------------
--
-- V51 emptied `OWN` and argued the mechanism should survive unpopulated, resting
-- reason 2 of three on this: "PLAN_SCOPE (V19) is the same shape and IS populated,
-- so the column and the resolution seam are earning their keep regardless."
--
-- That reason expires here. De-qualifying artifact:edit removes the last two
-- PLAN_SCOPE rows (Test Engineer's, and Agent's — which is the same row by another
-- name after statement 3), so `role_permission.qualifier` is now 'ALL' on every
-- row in the catalogue, and NEITHER non-trivial qualifier is reachable from seeded
-- data.
--
-- The mechanism is still kept, and the decision is now resting on V51's reasons 1
-- and 3 alone, which is a weaker footing and is recorded as such rather than
-- restated as if nothing changed:
--
--   1. The qualifier is DATA. Deleting `PermissionQualifier.OWN` / `PLAN_SCOPE`,
--      `IdentityProjectAccess.canDoOnAuthored` or the plan-scope resolution arm is
--      what would make a future qualified grant fail OPEN at whichever gate forgot
--      it. Keeping them means a grant means what the row says.
--   3. Rebuilding "only rows you authored" or "only rows of this plan" later costs
--      a migration, a gate change and a contract change; keeping it costs a comment.
--
-- What is NOT claimed any more: that the column is earning its keep through a live
-- population. It is not. `SeedDataTest.everyQualifierIsAll` pins the emptiness in
-- both directions, so the next reader can tell a decision from a seeding bug — the
-- same job `ownIsCarriedByNoGrant` did for one value, now widened to the column.
--
-- If a later slice decides an unpopulated mechanism is not worth two enum members
-- and two switch arms, THIS is the paragraph that says the argument for keeping it
-- was already down to two reasons, and neither is "it is in use".
--
-- -----------------------------------------------------------------------------
-- `Agent` BECOMES TEST ENGINEER, AND THE CEILING MOVES TO THE MCP LAYER
-- -----------------------------------------------------------------------------
--
-- Decided 2026-08-15 with the objection on the record. `Agent` (V40) was defined as
-- "Test Engineer minus the things an API caller must not do" — five keys by V51:
-- library:manage (gated by an impact modal an API caller never sees), case:delete,
-- evidence:delete, plan:delete (agent access creates, reads and edits and does not
-- delete) and company:read (a token is not a member).
--
-- The requirement is that an agent is the same as an engineer, with DELETE
-- OPERATIONS GATED BY USER CONSENT IN THE MCP TOOL DEFINITIONS. So the ceiling
-- stops being data and becomes an interaction: the MCP server asks before it
-- deletes, rather than the catalogue refusing after it tries.
--
-- THE COST, STATED HERE BECAUSE IT IS NOT VISIBLE FROM THE SQL. §A6a's argument for
-- making this a role at all was that "a ceiling enforced by omitting a tool controls
-- what an agent reaches FOR, not what it can DO — the `tcms_` bearer authenticates
-- against /api/* and can be used with curl". That argument is unchanged and now
-- cuts against the design: a consent prompt lives in the MCP server, and the token
-- does not have to be presented through the MCP server. An agent credential that
-- is leaked, or driven by any other client, can delete every case, plan, suite,
-- artifact and evidence row its owner can, with no consent step anywhere in the
-- path. The token intersection still holds — an agent never exceeds the person who
-- issued it, and a Viewer's agent token is still a Viewer — so what is lost is the
-- ceiling BELOW the owner, not the owner's own boundary.
--
-- The mitigations that remain, none of which is the withdrawn one: soft delete on
-- every one of these tables, `expiresAt` REQUIRED for an Agent token (§A7), the
-- audit trail, and Revoke.
--
-- `Automation Service` deliberately does NOT follow. It is named in the requirement
-- as staying put, and the property V51 stated still holds and is now the only one
-- of its kind: a leaked CI token cannot edit or delete a real case, and that is
-- independent of how results arrive. Agent no longer shares that property.
--
-- ONE key is still withheld, and it is not part of the ceiling: `company:read`,
-- because a PROJECT-scope grant of it is unreachable for a credential that has no
-- membership row. See statement 3 — it is a reachability rule that predates this
-- requirement and applies to `Automation Service` the same way.
--
-- Statement 3 DERIVES Agent from Test Engineer instead of enumerating 23 keys.
-- V40 enumerated, and SeedDataTest computed the delta so the enumeration could not
-- silently drift — which is exactly what caught V51 (§207). With the delta now
-- empty by decision, deriving is what keeps it empty: a permission granted to Test
-- Engineer by a future migration reaches Agent through the data rather than through
-- somebody remembering. The delta check survives, inverted — it now asserts the
-- difference is EMPTY in both directions, so a migration that widens one and not
-- the other still fails the build.
--
-- DATA ONLY — no DDL, no structural change. Every statement is idempotent by
-- construction: an UPDATE to a value a row already holds is a no-op, every INSERT
-- takes ON CONFLICT DO NOTHING, and COMMENT ON is replace-only.
-- =============================================================================

-- 1. Test Engineer gains the five keys the tier held back. Takes the DEFAULT 'ALL'
--    from V12 — a qualifier on any of them would reintroduce a narrower version of
--    the boundary this migration removes.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key      -- Test Engineer
  FROM (VALUES
    ('suite:delete'),('artifact:delete'),
    ('run:close'),('result:amend'),
    ('doc:publish')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- 2. The last PLAN_SCOPE rows, de-qualified. This is the exact inverse of V19's
--    only UPDATE, widened to cover Agent — which V40 wrote as a second row of the
--    same shape, and which statement 3 would otherwise leave behind as the one
--    qualified grant in the catalogue.
UPDATE role_permission
   SET qualifier = 'ALL'
 WHERE role_id IN ('10000000-0000-4000-8000-000000000004',   -- Test Engineer
                   '10000000-0000-4000-8000-000000000008')   -- Agent
   AND permission_key = 'artifact:edit';

-- 3. Agent = Test Engineer, DERIVED. Not a list: the whole point is that the two
--    cannot drift, so the grant is expressed as the relationship rather than as a
--    snapshot of it. Insert-only is sufficient because Agent has been a strict
--    subset of Test Engineer since V40 and SeedDataTest asserts the difference is
--    exactly the one exclusion below, in BOTH directions — an extra key on Agent
--    fails the build rather than surviving here.
--
--    `company:read` IS EXCLUDED, and it is not a ceiling. It is the same class of
--    fact as §A6's `project:read` finding, pointing the other way: a PROJECT-scope
--    role's `company:read` is unreachable from PermissionEvaluator.evaluateCompanyWide,
--    which reads the SYSTEM and COMPANY terms only. A human Test Engineer holds the
--    key twice — inertly here, and reachably through the `Company Member` baseline
--    V15 grants on joining (§43) — so the PROJECT-scope row is harmless for them.
--    A TOKEN has no membership row and therefore no baseline, so this row would be
--    its only source and no evaluation path could answer it: a company surface
--    advertised on the roles screen and permanently false at the gate.
--
--    This was NOT reasoned out in advance. SeedDataTest.companyReadIsAlwaysExplicit
--    reddened on the first run of this migration, which is the check working — the
--    rule predates the requirement, applies to `Automation Service` identically,
--    and is about REACHABILITY rather than about what an agent may do.
INSERT INTO role_permission (role_id, permission_key, qualifier)
SELECT '10000000-0000-4000-8000-000000000008', te.permission_key, te.qualifier
  FROM role_permission te
 WHERE te.role_id = '10000000-0000-4000-8000-000000000004'
   AND te.permission_key <> 'company:read'
ON CONFLICT DO NOTHING;

-- 4. The column comment, restated. V51 wrote the text this replaces and it is
--    still correct about what each value MEANS; what it cannot say is that
--    PLAN_SCOPE has joined OWN in carrying nothing. Restated rather than edited in
--    place for V49's reason: an applied migration is immutable, and `\d+` prints
--    the newest COMMENT ON.
COMMENT ON COLUMN role_permission.qualifier IS
    'WHICH ROWS this grant of the permission covers. ALL = every row in scope. '
    'PLAN_SCOPE = only the rows of the plan being acted on, never the project-scope row '
    'every plan inherits read-only (V19). '
    'OWN = only rows the caller authored (created_by), in any state (V12). '
    '  NEITHER PLAN_SCOPE NOR OWN IS CARRIED BY ANY GRANT AS OF V52, AND THAT IS A DECISION, NOT A '
    'GAP. OWN emptied at V51: CRUD on plans, cases and steps belongs to every non-Viewer '
    'contributor regardless of who created the row (§207). PLAN_SCOPE emptied here: a contributor '
    'creates, edits and deletes, unqualified, so artifact:edit lost the scope qualifier and it was '
    'the only key that carried one (§210). EVERY ROW IN THIS COLUMN IS NOW ''ALL''. '
    '  The values, the PermissionQualifier members, IdentityProjectAccess.canDoOnAuthored and the '
    'plan-scope resolution arm are all kept against the next permission that needs to narrow — '
    'because the qualifier is DATA, and deleting an arm is what makes a future qualified grant fail '
    'OPEN at whichever gate forgot it. Do not read the empty population as a seeding bug; '
    'SeedDataTest.everyQualifierIsAll pins it in both directions. '
    '  Resolved at check time against the row being acted on, because the evaluator cannot see '
    'that row: PermissionEvaluator answers the qualifier, the project-scoped write gate applies it. '
    '  STRONGEST WINS: a caller holding the same permission through two grants (directly and via a '
    'team, or PROJECT and COMPANY scope) gets ALL if any grant is ALL. A Company Admin who is also '
    'a project''s Test Engineer must not be demoted by the weaker grant. '
    '  NOT a permission key: case:delete:own would double the catalogue and still could not '
    'express "in any state" (V3 §3). NOT keyed on the role name: code checks permissions, never '
    'role names (plan §6b), or the first custom role is mis-answered.';
