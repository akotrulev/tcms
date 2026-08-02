-- =============================================================================
-- V19 — the PLAN_SCOPE qualifier, which the roles spec has specified since M0
--        and V12's two-value CHECK cannot express
--
-- Roles spec, the Artifacts permission table: `artifact:edit` is
--
--     | `artifact:edit` | ✓ | ✓ | ✓ | plan scope only | — | — |
--                          IA  CA  TL       TE           AS   V
--
-- and the sentence under it — "Project-scope artifact edit is Lead-only" —
-- is the whole of the rule. A Test Engineer may attach the sign-off and the
-- environment snapshot that belong to THIS cycle; they may not rewrite the test
-- strategy that governs every cycle, because a project-scope artifact is
-- inherited read-only by every plan (plan §6n) and editing one edits all of them.
--
-- The first caller arrives with POST /projects/{id}/artifacts. Without this value
-- that endpoint has exactly two honest implementations and both are wrong — the
-- same bind V12 was written for, one axis over:
--
--   * grant it unqualified, and a Test Engineer rewrites the strategy document
--     every cycle inherits; or
--   * withhold it, and the spec's ✓-with-a-qualifier becomes a —, so a Test
--     Engineer cannot attach their own cycle's sign-off.
--
-- Test Engineer's grant is `ALL` today (V12's backfill default, which was correct
-- for every row that existed then). So this is not a gap that fails closed: an
-- artifact:edit gate written against the current data would let a Test Engineer
-- through on a project-scope artifact. The data must change before the gate can
-- be honest, which is why this migration leads the slice rather than following it.
--
-- -----------------------------------------------------------------------------
-- WHY A SECOND QUALIFIER VALUE AND NOT A SECOND PERMISSION KEY
-- -----------------------------------------------------------------------------
--
-- `artifact:edit:plan` beside `artifact:edit` is the same proposal V3 §3 and V12
-- each refused, and the reasons are unchanged: it doubles the catalogue, it puts
-- two keys where every screen and every check wants one, and the SPA's permission
-- catalogue would have to learn that holding either means the button is enabled
-- while only one of them means it is enabled HERE. The qualifier restricts WHICH
-- ROWS, and "which rows" is exactly what is being said.
--
-- -----------------------------------------------------------------------------
-- WHY NOT REUSE `OWN`
-- -----------------------------------------------------------------------------
--
-- Because it is a different restriction and would answer a different question.
-- OWN covers rows the caller AUTHORED, in any state. The rule here covers rows at
-- PLAN scope, whoever wrote them: a Test Engineer must be able to supersede a
-- colleague's environment snapshot inside their own cycle, and must not be able
-- to touch a project-scope document they authored themselves before being moved
-- off the lead role. Both directions are wrong under OWN.
--
-- This is the point on which the two values are NOT interchangeable and NOT
-- comparable: they restrict along different axes. See PermissionQualifier, which
-- records what that costs — the enum's ordinal order is a "strongest wins" merge
-- rule, and OWN vs PLAN_SCOPE has no strongest. The union "rows you wrote OR rows
-- at plan scope" is not expressible in one value, so the invariant that keeps the
-- merge total is a property of THIS DATA: no permission key is ever granted with
-- two different restrictive qualifiers. `SeedDataTest` computes that over the
-- catalogue rather than trusting this comment, for §48's reason.
--
-- -----------------------------------------------------------------------------
-- WHY THE CHECK IS REPLACED RATHER THAN DROPPED
-- -----------------------------------------------------------------------------
--
-- The constraint is what makes PermissionQualifier#ofStored's throw-on-unknown a
-- guarantee rather than a hope: it is the reason an unrecognised qualifier means
-- "someone dropped the constraint or wrote a value no code implements" instead of
-- "ordinary data". Dropping it to add a value would leave a window in which the
-- column accepts anything, and the value that lands in that window is an
-- authorization decision nobody made.
--
-- ADD CONSTRAINT takes an ACCESS EXCLUSIVE lock and validates every row. That is
-- the right trade here and would not be on a hot table: `role_permission` is
-- instance-level REFERENCE data seeded by V3 — low hundreds of rows, written only
-- by migrations. NOT VALID + VALIDATE CONSTRAINT exists for the other case and
-- would add a second state this table never needs to be in.
--
-- -----------------------------------------------------------------------------
-- DDL AND SEED DML IN ONE FILE — the same deliberate deviation V12 recorded
-- -----------------------------------------------------------------------------
--
-- The migrations skill separates schema from data, for rolling deploys and for
-- long resumable backfills. Neither is in play, for the reasons V12 sets out in
-- full: this deploys as a single jar, `role_permission` is reference data, and the
-- DML below touches one row. Splitting it would produce a V19 whose CHECK admits a
-- value no grant uses and a V20 that gives it meaning, with a window in between
-- where the qualifier exists and lies — which is worse than the rule being bent.
-- =============================================================================

ALTER TABLE role_permission
    DROP CONSTRAINT ck_role_permission_qualifier;

ALTER TABLE role_permission
    ADD CONSTRAINT ck_role_permission_qualifier
        CHECK (qualifier IN ('ALL', 'OWN', 'PLAN_SCOPE'));

COMMENT ON COLUMN role_permission.qualifier IS
    'WHICH ROWS this grant of the permission covers. ALL = every row in scope. OWN = only rows the '
    'caller authored (created_by), in any state — roles spec §A5. PLAN_SCOPE = only rows at PLAN '
    'scope, whoever authored them — roles spec, Artifacts table: a Test Engineer curates their own '
    'cycle and does not rewrite the project-wide document every cycle inherits (plan §6n). '
    'Resolved at check time against the row being acted on, because the evaluator cannot see that '
    'row: PermissionEvaluator answers the qualifier, the project-scoped write gate applies it. '
    '  STRONGEST WINS, and it is only total because of an invariant on this table: a caller holding '
    'the same permission through two grants gets ALL if any grant is ALL. OWN and PLAN_SCOPE '
    'restrict along DIFFERENT axes and have no strongest — their union ("rows you wrote OR rows at '
    'plan scope") is not expressible in one value — so no permission key may be granted with two '
    'different restrictive qualifiers. SeedDataTest computes that; PermissionQualifier#strongest '
    'refuses the pair rather than picking one silently. '
    '  NOT a permission key: artifact:edit:plan would double the catalogue for the same reason '
    'case:delete:own was refused (V3 §3, V12). NOT keyed on the role name: code checks permissions, '
    'never role names (plan §6b), or the first custom role is mis-answered.';

-- Test Engineer's plan-scope-only grant of `artifact:edit` (roles spec, Artifacts
-- table). Idempotent by construction — an UPDATE to a value a row already holds is
-- a no-op, so a re-run changes nothing.
--
-- Exactly one row. `artifact:edit` is the only permission the spec qualifies this
-- way; artifact:read is unqualified for everyone who holds it and artifact:delete
-- is simply withheld from Test Engineer (a —, not a qualifier). Setting only what
-- the spec states is the point: a qualifier applied defensively to a neighbouring
-- key would restrict a grant no rule restricts.
UPDATE role_permission
   SET qualifier = 'PLAN_SCOPE'
 WHERE role_id = '10000000-0000-4000-8000-000000000004'   -- Test Engineer
   AND permission_key = 'artifact:edit';
