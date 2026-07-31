-- V13 — a grant can be made twice, and until now the second one landed.
--
-- role_assignment has carried no uniqueness since V1. That was harmless while the only writer was
-- provision-company, which guards itself with an existence check so re-running the CLI tops a
-- company up rather than stacking grants. POST /role-assignments makes it reachable: the contract
-- says an identical re-grant "returns the existing one with 200, not a duplicate and not a 409",
-- and an application-side lookup alone cannot promise that — two admins clicking Grant at the same
-- instant both read nothing and both insert.
--
-- The cost of the duplicate is not an untidy list. It is that the same person now holds two grants
-- to revoke, so revoking the one on the screen leaves them holding the role, and the admin who did
-- it has every reason to believe access was removed.
--
-- WHY TWO PARTIAL INDEXES AND NOT ONE CONSTRAINT
--
-- ck_role_assignment_subject makes user_id and team_id mutually exclusive, so every row has a NULL
-- in one of them. A single unique index over both columns would therefore be uniquely satisfiable
-- by anything: in a plain unique index NULLs are distinct from each other, so two identical team
-- grants (both with user_id NULL) would not collide. One partial index per subject removes the
-- NULL column from the picture entirely.
--
-- NULLS NOT DISTINCT is the other half, and the one that is easy to leave out. project_id IS NULL
-- is how COMPANY scope is encoded (role_assignment's own column comment), so without it the
-- company-wide grants — the Company Admin appointments, the most consequential rows in this table
-- — would be the only ones the index did not protect. Postgres 15+; this deployment is 17.
--
-- A UNIQUE CONSTRAINT cannot express either of these. Constraints take no WHERE clause and no
-- NULLS NOT DISTINCT before 15, so an index it is; the trade is that the error arrives as a
-- generic unique violation rather than a named constraint, which the application maps back on the
-- one path that can hit it (AdminController#grant).
--
-- Both indexes lead with company_id, per the tenancy rule that every composite index does — a
-- lookup is always inside one company, and leading with role_id would make every probe cross the
-- whole instance's grants before the policy narrowed them.

CREATE UNIQUE INDEX uq_role_assignment_user
    ON role_assignment (company_id, role_id, user_id, project_id)
    NULLS NOT DISTINCT
    WHERE user_id IS NOT NULL;

COMMENT ON INDEX uq_role_assignment_user IS
    'One grant of one role to one person at one scope. NULLS NOT DISTINCT because project_id IS '
    'NULL IS the COMPANY scope — without it the company-wide grants would be the only ones not '
    'deduplicated. Partial on user_id because ck_role_assignment_subject makes team rows carry a '
    'NULL here, and a NULL is distinct from every other NULL in a plain unique index.';

CREATE UNIQUE INDEX uq_role_assignment_team
    ON role_assignment (company_id, role_id, team_id, project_id)
    NULLS NOT DISTINCT
    WHERE team_id IS NOT NULL;

COMMENT ON INDEX uq_role_assignment_team IS
    'The same, for grants made to a team. See uq_role_assignment_user.';
