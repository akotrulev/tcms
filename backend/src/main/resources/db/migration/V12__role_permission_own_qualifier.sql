-- =============================================================================
-- V12 — the OWN qualifier, which the roles spec has specified since M0 and
--        nothing has been able to express
--
-- Roles spec §A5 and the permission table: `case:delete` and `evidence:delete`
-- are OWN-qualified for Test Engineer — authorship confers full control of that
-- row, in any state, and a Test Engineer is never blocked from PRODUCING, only
-- from unmaking SOMEONE ELSE'S work. Three places record the qualifier as owed
-- rather than done: PermissionEvaluator's "not yet done here", ProjectAccess#canDo's
-- header, and section 3 of this file's own V3 predecessor.
--
-- The first caller arrives with DELETE /projects/{id}/cases/{caseId}. Without this
-- column that endpoint has exactly two honest implementations and both are wrong:
-- grant the delete to every Test Engineer (they unmake other people's cases — the
-- one thing §A3's principle names), or withhold it from them entirely (the spec
-- says ✓/OWN, not —, and a case's author cannot remove their own mistake).
--
-- -----------------------------------------------------------------------------
-- WHY THE COLUMN IS ON role_permission AND NOT ON permission
-- -----------------------------------------------------------------------------
--
-- The qualifier is a property of THIS ROLE'S GRANT of the permission, not of the
-- permission. `case:delete` is unqualified for a Test Lead and OWN for a Test
-- Engineer: the same key, two answers. A column on `permission` could hold only
-- one of them, and whichever it held would be wrong for the other role.
--
-- -----------------------------------------------------------------------------
-- WHY NOT A SEPARATE `case:delete:own` KEY
-- -----------------------------------------------------------------------------
--
-- V3's section 3 already refused it, and the reason stands: it doubles the
-- catalogue, it puts two keys where every screen and every check wants one, and it
-- still cannot express "in any state" — the qualifier restricts WHICH ROWS, not
-- which lifecycle states, and a key cannot say that either.
--
-- -----------------------------------------------------------------------------
-- WHY NOT "IS THE CALLER A TEST ENGINEER?"
-- -----------------------------------------------------------------------------
--
-- Because code checks permissions, never role names (plan §6b). A rule keyed on the
-- role name is correct until the first custom role is defined — it then answers
-- "unqualified" for a role its author intended as a restricted contributor, which
-- is handoff §6 lesson 10 (the '*' sentinel) wearing a different hat: a value that
-- reads correctly until the set it describes changes. Custom roles are a stated
-- direction (V1's role table comment), so this must be data.
--
-- -----------------------------------------------------------------------------
-- DEFAULT 'ALL', AND WHY THAT IS THE SAFE DEFAULT HERE
-- -----------------------------------------------------------------------------
--
-- Every existing row means "held without restriction" today, so 'ALL' is what they
-- already are — a backfill to anything else would silently narrow 200 grants. The
-- risk of a permissive default is that a future OWN-qualified grant is forgotten
-- and lands unrestricted; the risk of the other default is that every existing
-- grant is restricted by a migration that claimed to add a column. Only the second
-- is a change to behaviour, and it would apply to permissions no call site knows
-- are qualified.
--
-- Instance Admin's grant is DERIVED from the catalogue (V3), so it picks up 'ALL'
-- for every key without being enumerated here — including keys added later.
--
-- -----------------------------------------------------------------------------
-- DDL AND SEED DML IN ONE FILE — a deliberate deviation
-- -----------------------------------------------------------------------------
--
-- The migrations skill says schema and data belong in separate files. Its two
-- reasons are rolling deploys (a version reading the old shape while another writes
-- the new) and long backfills that must be batched and resumable. Neither is in
-- play: `role_permission` is instance-level REFERENCE data seeded by V3, this
-- deploys as a single jar, and the DML below touches two rows. Splitting it would
-- produce a V12 whose column means nothing and a V13 that gives it meaning, with a
-- window in between where the qualifier exists and lies.
-- =============================================================================

ALTER TABLE role_permission
    ADD COLUMN qualifier TEXT NOT NULL DEFAULT 'ALL'
        CONSTRAINT ck_role_permission_qualifier CHECK (qualifier IN ('ALL', 'OWN'));

COMMENT ON COLUMN role_permission.qualifier IS
    'WHICH ROWS this grant of the permission covers. ALL = every row in scope. OWN = only rows the '
    'caller authored (created_by), in any state — roles spec §A5. Resolved at check time against '
    'the row being acted on, because the evaluator cannot see that row: PermissionEvaluator '
    'answers the qualifier, the project-scoped write gate applies it to the author. '
    '  STRONGEST WINS: a caller holding the same permission through two grants (directly and via a '
    'team, or PROJECT and COMPANY scope) gets ALL if any grant is ALL. A Company Admin who is also '
    'a project''s Test Engineer must not be demoted by the weaker grant. '
    '  NOT a permission key: case:delete:own would double the catalogue and still could not '
    'express "in any state" (V3 §3). NOT keyed on the role name: code checks permissions, never '
    'role names (plan §6b), or the first custom role is mis-answered.';

-- Test Engineer's two OWN-qualified grants (roles spec §A5, permission table rows
-- `case:delete` and `evidence:delete`). Idempotent by construction — an UPDATE to a
-- value a row already holds is a no-op, so a re-run changes nothing.
--
-- evidence:delete is set now although nothing reads it: the `execution` module does
-- not exist, so no endpoint can consult it yet. The spec states both, the data is
-- one statement, and the alternative is a second migration whose only content is
-- the row this one deliberately left half-done.
UPDATE role_permission
   SET qualifier = 'OWN'
 WHERE role_id = '10000000-0000-4000-8000-000000000004'   -- Test Engineer
   AND permission_key IN ('case:delete', 'evidence:delete');
