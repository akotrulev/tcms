-- =============================================================================
-- V40 — the Agent role, and the assignable flag that five hardcoded checks were
--        standing in for
--
-- Source: docs/plans/2026-08-08-mcp-server-wip-plan.md §2 (SETTLED), and
--         docs/plans/2026-08-08-agent-role.md, this slice's own plan.
--
-- Agent access to the test repository — a CLI and an MCP server over the same
-- REST API — needs a capability ceiling for a credential a developer's agent
-- holds. The ceiling is DATA, not code, and that is the decision:
--
--     effective = evaluate(ownerId, projectId)
--                     ∩ permissionsOf(token.role_id)
--                     ∩ [the token's project]
--
-- The intersection already exists (plan §6p, Decision C) and is the only
-- narrowing point, so PermissionEvaluator does not change. A ceiling held in
-- validation code instead would appear in no permission matrix and could not be
-- derived by SeedDataTest, which is how a ceiling drifts from the spec that
-- states it.
--
-- The ceiling has to be a ROLE and not a tool list. The tcms_ bearer
-- authenticates against /api/* through ApiTokenAuthenticationFilter, so an agent
-- holding it can curl the REST API directly: omitting a tool controls what the
-- agent reaches FOR, not what it can DO. Same sentence as /me/permissions'
-- "hidden is not protected", one client over.
--
-- -----------------------------------------------------------------------------
-- 1. WHY A SEVENTH ASSIGNABLE-LOOKING ROLE IS NOT A SEVENTH TIER
-- -----------------------------------------------------------------------------
--
-- The pattern is established twice. Automation Service (V3) is "non-human,
-- attached to API tokens, never people" — a token-only capability set already
-- implemented as a role. Company Member (V15) is a role row deliberately outside
-- the six, filtered from GET /roles and refused by POST /role-assignments.
--
-- Agent is the first with BOTH properties, and roles spec §A2's "these six are
-- the complete set of *assignable* roles" therefore stays literally true — which
-- is the sentence V15 was careful to keep true and this migration is careful not
-- to break.
--
-- Automation Service is NOT subsumed and CI must not carry Agent. Its value is
-- that a leaked CI token cannot edit or delete a real case, and that is
-- independent of how results arrive. Two token-carried roles, different jobs.
--
-- -----------------------------------------------------------------------------
-- 2. WHY assignable IS A COLUMN AND NOT A SIXTH CONSTANT COMPARISON
-- -----------------------------------------------------------------------------
--
-- Every exclusion of Company Member today is a hardcoded UUID equality against
-- Role.COMPANY_MEMBER_ID, in five places. The check somebody forgets is the one
-- that lets an administrator grant Agent to a person, and hardcoded lists are how
-- it gets forgotten — so the two ENFORCEMENT sites (RoleGrantService's grant and
-- revoke) ask the row.
--
-- BUT THE FLAG CANNOT DRIVE THE OTHER THREE, and that is the load-bearing part of
-- this migration's design. The two non-assignable roles differ exactly where
-- those sites look:
--
--   Company Member — non-assignable AND non-issuable AND never listed;
--   Agent          — non-assignable BUT issuable, and MUST be listed, because
--                    the token-issue form is the only place it is ever chosen.
--
-- So AdminDirectoryService's two filters and ApiTokenService.validate stay
-- baseline-specific on purpose. "Cleaning them up" to read `assignable` is the
-- regression this comment exists to refuse: WHERE assignable on the directory
-- filter hides Agent from the token form, and assignable on the issue check makes
-- an Agent token unissuable — the one thing the role exists for.
--
-- -----------------------------------------------------------------------------
-- 3. NO RLS, NO company_id
-- -----------------------------------------------------------------------------
--
-- role, role_permission and permission are instance-level reference data and are
-- on the ERD §0.1 exception list (V3 §1 says so). This migration therefore adds
-- no policy and the FORCE ROW LEVEL SECURITY count stays at 62 —
-- SchemaConventionTest pins it, which is what makes a new policed table a
-- decision rather than a side effect of a migration that needed a table.
-- =============================================================================


-- =============================================================================
-- 4. THE FLAG
-- =============================================================================
--
-- DEFAULT true, so the six keep their meaning without being named: a column whose
-- backfill enumerates the roles that are unaffected is a column that has to be
-- edited by the next migration that adds one. The two false rows are the
-- exceptions and are set explicitly below.

ALTER TABLE role
    ADD COLUMN assignable BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN role.assignable IS
    'Whether POST /role-assignments may grant this role to a person or a team, and whether '
    'DELETE may revoke such a grant. False for Company Member (V15 — a consequence of membership, '
    'never a decision about a person) and for Agent (V40 — carried by API tokens only). '
    '  IT IS NOT "hidden": Agent is assignable = false and IS returned by GET /roles, because the '
    'token-issue form has to offer it. Assignability, issuability and listedness are three '
    'different questions and this column answers exactly one of them — see §2 above before '
    'wiring it into AdminDirectoryService or ApiTokenService.';

-- Company Member, retroactively. The behaviour does not change: RoleGrantService
-- already refuses this id in both directions. What changes is that the refusal
-- can now be derived from the row rather than restated as a constant, so the next
-- non-assignable role inherits it instead of needing two more edits.
UPDATE role
   SET assignable = false
 WHERE id = '10000000-0000-4000-8000-000000000007';


-- =============================================================================
-- 5. THE ROLE
-- =============================================================================
--
-- Fixed id, like V3's six and V15's seventh, so a corrective migration can name
-- it without a lookup subquery. built_in = true: it is product-defined reference
-- data, and SeedDataTest refuses any non-built-in role while custom roles do not
-- exist.

INSERT INTO role (id, scope, name, description, built_in, assignable) VALUES
    ('10000000-0000-4000-8000-000000000008', 'PROJECT', 'Agent',
     'Non-human, carried by API tokens only — the ceiling for an AI agent acting on behalf of the '
     'person who issued the token. Test Engineer minus the shared library, minus deleting cases '
     'and minus deleting evidence. It can read, create and edit cases, suites, plans, runs and '
     'documentation, execute and submit results, review and comment; it cannot approve anything, '
     'close a run, amend a result, publish a document, delete anything, or change project '
     'configuration. It never exceeds the person who issued the token: the effective set is the '
     'intersection of their permissions with this role, so a Viewer''s agent token reads only.',
     true, false)
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 6. THE GRANT — 23 keys, and each absence is the reason for the role
-- =============================================================================
--
-- Enumerated rather than derived from Test Engineer by subtraction, for the same
-- reason Automation Service is enumerated in V3: a derived grant makes the next
-- permission added to Test Engineer land silently in a credential's ceiling. A
-- role a machine carries must state what it holds.
--
-- DROPPED FROM TEST ENGINEER — three, each for its own reason:
--
--   library:manage    Roles spec §A3 gates this by an IMPACT MODAL, not by
--                     permission: editing a shared condition or step propagates
--                     to every referencing case, and the control is informed
--                     consent via a confirmation listing them. AN API CALLER
--                     NEVER SEES THAT MODAL. Removing the permission is the only
--                     fix that also holds for raw REST; rebuilding consent at the
--                     tool layer could not, per §2 above.
--
--   case:delete       OWN for Test Engineer, and OWN resolves against created_by
--   evidence:delete   while a token's owner is api_token.owner_id — so an
--                     OWN-qualified delete would let the agent delete the content
--                     THE HUMAN AUTHORED, not only what it created itself. There
--                     is no qualifier that expresses "only what this credential
--                     made", so the permission is withheld rather than narrowed.
--
-- NEVER HELD, inherited from Test Engineer's own boundary, and each is something
-- an agent in particular should not do:
--
--   case:approve / suite:approve / plan:approve   author-cannot-approve-own-work
--       already defaults on; an agent that generates cases must not approve them
--   run:close / result:amend    a judgement about the WORK, not the artifact
--   doc:publish                 the human's signal that a document is ready
--   project:settings / project:members / integration:manage
--                               configuration and access-granting
--   suite:delete / plan:delete / artifact:delete   destructive
--
-- NOT company:read, and it is the one line that is an addition to the plan's
-- table rather than a copy of it. The four member-facing PROJECT-scope roles hold
-- it because they are held by MEMBERS (V3 §3 says exactly that), and V15 exists
-- because a PROJECT-scope role's company:read is unreachable from
-- evaluateCompanyWide anyway. A token is not a member: it has no membership row
-- and no baseline grant. Automation Service — the other token-only role — does
-- not hold it either, and automationServiceIsTheSmallestRole stays true because
-- its five keys remain a strict subset of these twenty-three.
--
-- project:read IS held, and it is a precondition rather than a capability (roles
-- spec §A6, repaired for Automation Service by V16): every project-scoped
-- endpoint asks ProjectAccess.canView first, so a PROJECT-scope role without it
-- 404s on every project-scoped path and is inert over HTTP while looking correct
-- in the seed and on the roles screen.
-- SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition derives this,
-- so omitting it here fails the build rather than the first agent request.

INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000008', key
  FROM (VALUES
    ('suite:manage'),('case:read'),('case:create'),('case:edit'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),
    ('plan:create'),('plan:edit'),
    ('run:create'),('run:execute'),('result:submit'),('evidence:attach'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),
    ('artifact:read'),('artifact:edit'),
    ('project:read')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- The plan-scope qualifier, V19's value, and it must be Test Engineer's EXACT
-- spelling rather than a new one:
-- SeedDataTest.noPermissionCarriesTwoDifferentRestrictiveQualifiers computes over
-- the catalogue that one permission never carries two different restrictive
-- qualifiers, so a near-miss here fails the build — which is that test working.
--
-- The rule it encodes is unchanged for an agent: a project-scope artifact is
-- inherited read-only by every plan (plan §6n), so editing one edits all of them.
-- An agent may attach this cycle's sign-off; it may not rewrite the strategy
-- every cycle inherits.
--
-- Idempotent by construction — an UPDATE to a value a row already holds is a
-- no-op, so a re-run changes nothing.
UPDATE role_permission
   SET qualifier = 'PLAN_SCOPE'
 WHERE role_id = '10000000-0000-4000-8000-000000000008'   -- Agent
   AND permission_key = 'artifact:edit';

-- Nothing else is qualified. case:edit is deliberately ALL: editing anyone's case
-- is what agent access is FOR, and an OWN qualifier here would carry the
-- api_token.owner_id problem described above into the one permission the role
-- exists to grant.
