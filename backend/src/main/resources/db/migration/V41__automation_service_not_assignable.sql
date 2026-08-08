-- =============================================================================
-- V41 — Automation Service stops being grantable to a person, and the grant axis
--        parts from the revoke axis
--
-- Source: docs/plans/2026-08-08-automation-service-not-assignable.md, and
--         docs/plans/2026-08-08-mcp-server-wip-plan.md §2.4 (SETTLED — the role
--         stays; CI must not carry Agent). Handoff §134.
--
-- V3 has described Automation Service as "non-human, attached to API tokens,
-- never people" since M0 and NOTHING HAS EVER REFUSED SUCH A GRANT. Every screen
-- offered it, POST /role-assignments accepted it, and the evaluator honoured it.
-- A description asserting a property nothing enforces is the recurring defect
-- shape this project keeps finding (handoff §100's rule, eighth sighting) — and
-- V40 built the mechanism that fixes it, for a different role. This is the second
-- row to use it, which is the test of whether the mechanism generalised.
--
-- WHAT DOES NOT CHANGE. The role stays, with the same six keys. It stays issuable
-- to an API token — a leaked CI credential that cannot edit or delete a real case
-- is the entire value of the role — and it stays listed by GET /roles, because
-- the token-issue form is now, as for Agent, the only place it is ever chosen.
-- Assignability, issuability and listedness are three questions (V40 §2) and this
-- migration changes exactly one of them.
--
-- -----------------------------------------------------------------------------
-- 1. THE FLAG WAS ANSWERING A FOURTH QUESTION, AND THIS IS WHERE THAT SHOWS
-- -----------------------------------------------------------------------------
--
-- V40 §2 says `assignable` drives "the two RoleGrantService enforcement points",
-- grant and revoke, and that reads as one rule. It is two. They were fused only
-- because the one non-assignable role at the time wanted the same answer in both
-- directions:
--
--   Company Member      grant: a no-op on a row joining already wrote
--                       revoke: REMOVES A COLLEAGUE'S SIGHT OF THE COMPANY, and
--                               the only repair is re-running the operator CLI
--
--   Agent               grant: nobody is ever an Agent
--                       revoke: no reason of its own — such a grant cannot exist,
--                               so the arm is unreached and its answer untested
--
--   Automation Service  grant: it is a credential's capability set, not a
--                              person's
--                       revoke: THE OPPOSITE — a grant that already exists is
--                               precisely what an administrator must be able to
--                               clean up
--
-- So `assignable` answers "may POST /role-assignments CREATE this". The revoke
-- refusal is about a CONSEQUENCE OF REMOVAL and exactly one row has one, which
-- makes it a baseline-specific check like AdminDirectoryService's two filters and
-- ApiTokenService.validate — the fourth member of that family, refused for the
-- same reason each of those was: two questions that coincided while there was one
-- non-assignable role.
--
-- WITHOUT THE SPLIT THIS MIGRATION STRANDS DATA. AdminDirectoryService filters
-- only Company Member out of GET /role-assignments, so an existing person-held
-- Automation Service grant would stay ON THE SCREEN, carrying a Revoke button
-- that 403s forever — the product showing an administrator a mess it had just
-- made, with no way to clear it. That is the disable-don't-hide defect §31 exists
-- to prevent, arrived at from the server side.
--
-- -----------------------------------------------------------------------------
-- 2. EXISTING GRANTS ARE KEPT, DELIBERATELY
-- -----------------------------------------------------------------------------
--
-- This migration deletes nothing. A DELETE here would remove somebody's access on
-- a live instance with no audit row it could honestly write — role_assignment's
-- removals are audited by RoleGrantService with an actor, and a migration has no
-- actor. Existing grants keep conferring exactly what they conferred yesterday,
-- stay listed, and are now revocable by any administrator who decides they should
-- go. The decision to clean up is a person's; this migration only stops NEW ones.
--
-- -----------------------------------------------------------------------------
-- 3. NO TABLE, NO POLICY
-- -----------------------------------------------------------------------------
--
-- role is instance-level reference data on the ERD §0.1 exception list (V3 §1),
-- so the FORCE ROW LEVEL SECURITY count stays at 62. SchemaConventionTest pins
-- it.
-- =============================================================================


-- =============================================================================
-- 4. THE ROW
-- =============================================================================
--
-- Named by its seeded id rather than by name, like every corrective migration
-- since V15: the fixed ids exist so a later migration can address a role without
-- a lookup subquery that a renamed row would silently miss.

UPDATE role
   SET assignable = false
 WHERE id = '10000000-0000-4000-8000-000000000005';


-- =============================================================================
-- 5. THE COMMENT
-- =============================================================================
--
-- V40's version named two rows and gave one reason ("carried by API tokens
-- only") that now covers two of the three. Rewritten rather than appended to,
-- because a column comment that enumerates is a comment the next migration has to
-- edit — and this is the second migration in two days to edit this one.

COMMENT ON COLUMN role.assignable IS
    'Whether POST /role-assignments may grant this role to a person or a team. False for three '
    'rows: Company Member (V15 — a consequence of membership, never a decision about a person), '
    'Agent (V40) and Automation Service (V41), both of which are capability ceilings a credential '
    'carries rather than roles a person holds. '
    '  IT ANSWERS ONE QUESTION OF FOUR. It is not "hidden" — Agent and Automation Service are both '
    'assignable = false and both ARE returned by GET /roles, because the token-issue form is the '
    'only place either is chosen. It is not "not issuable" — both are issuable, which is what they '
    'exist for. And SINCE V41 IT IS NOT "not revocable": revoking is refused only for the '
    'membership baseline, whose removal takes away a colleague''s sight of the company, and an '
    'Automation Service grant made before V41 must stay revocable or it can never be cleaned up. '
    'See V41 §1 and V40 §2 before wiring this column into a fifth site.';
