-- =============================================================================
-- V15 — company:read is granted to "every member" and no member below Company
--        Admin could answer for it
--
-- THE DEFECT (handoff §39, found by four tests going red on their first run)
--
-- Roles spec §A3 grants `company:read` to *every member*, and V3 seeds it onto
-- five of the six built-in roles saying exactly that in its own comment. Four of
-- those five roles are PROJECT-scope. PermissionEvaluator.evaluateCompanyWide
-- answers a company-level question from the SYSTEM and COMPANY terms only — by
-- design since handoff §34, because "permissions on every project" would make a
-- Test Lead's project:settings read as a company-wide capability.
--
-- So a permission the spec says every member holds was invisible to the only
-- question that asks about the company, for everyone below Company Admin.
-- AdminDirectoryService.reachOf worked around it locally by asking "held
-- company-wide OR on any project", which is honest and does not scale: the next
-- surface that gates on a company-scope permission writes the same workaround, or
-- forgets to and fails closed.
--
-- -----------------------------------------------------------------------------
-- THE REPAIR, AND THE ONE THAT WAS REJECTED
-- -----------------------------------------------------------------------------
--
-- CHOSEN: every company member receives an explicit COMPANY-scope grant of a
-- minimal built-in role holding exactly `company:read`, seeded when they join.
-- The evaluator's scope model does not change.
--
-- REJECTED: letting evaluateCompanyWide answer "held anywhere in this company".
-- It repairs this permission and breaks every other one: `case:delete` held on a
-- single project would read true for the company, and every company-scope gate in
-- the product would then be answering a question about one project. The scope
-- model is the thing that makes a Test Lead a Test Lead of ONE project.
--
-- -----------------------------------------------------------------------------
-- WHY A SEVENTH ROLE AND NOT A REUSE OF ONE OF THE SIX
-- -----------------------------------------------------------------------------
--
-- No existing role can carry this grant:
--
--   * the four PROJECT-scope roles cannot be granted at COMPANY scope at all
--     (RoleGrantService.scopeError refuses it, and Viewer granted company-wide
--     would confer project:read and case:read over EVERY project present and
--     future — which is precisely the boundary roles spec §A1 draws when it says
--     company membership grants authentication and ZERO project visibility);
--   * Company Admin is COMPANY-scope and confers everything.
--
-- Handoff §39 already anticipated a seventh role, for a different reason (a
-- company-wide read-only *stakeholder*, who reads every project). This is not
-- that role and must not become it: `Company Member` grants nothing about any
-- project, which is what makes it safe to hold universally.
--
-- IT IS NOT ON THE WIRE AND NOT GRANTABLE. `GET /roles` still returns exactly the
-- six the contract enumerates (openapi.yaml Role.name is a closed enum), POST
-- /role-assignments refuses this role id, and its grants are filtered out of GET
-- /role-assignments so no screen offers a Revoke for them. A revocable baseline
-- would let an administrator remove a colleague's sight of the company with a
-- button whose only repair is re-running the operator CLI.
--
-- -----------------------------------------------------------------------------
-- WHY THE BACKFILL IS A FUNCTION AND NOT AN INSERT IN THIS FILE
-- -----------------------------------------------------------------------------
--
-- Same argument V2 §4 makes for the seeded vocabularies, and V5 restates: these
-- are COMPANY-OWNED rows under FORCE ROW LEVEL SECURITY, so an insert needs
-- app.current_company set — including for the migration role, which FORCE covers
-- too. Beyond that, three callers need exactly this statement: this backfill,
-- provision-company (whose re-run is the supported way to top a company up after
-- a release — see V5), and invitation acceptance. One function is one
-- implementation; three copies would drift, and the copy that drifts silently is
-- the one that decides whether a client can see their own company.
--
-- SECURITY INVOKER (the default), deliberately: the policy must apply to whoever
-- calls it, exactly as V8 established when it took definer rights away from
-- tcms_companies_for_user.
--
-- NOT BATCHED. The migrations skill wants a batched, resumable backfill; the
-- ceiling here is the one ProjectService states — a self-hosted instance holds a
-- handful of client companies with tens of people each, so this is tens of rows
-- per company inside the pre-deploy migration step, not millions.
-- =============================================================================


-- =============================================================================
-- 1. THE SEVENTH BUILT-IN ROLE
-- =============================================================================
--
-- Fixed id, like V3's six, so a corrective migration can name it without a
-- lookup subquery. built_in = true: it is product-defined reference data, and
-- SeedDataTest refuses any non-built-in role while custom roles do not exist.

INSERT INTO role (id, scope, name, description, built_in) VALUES
    ('10000000-0000-4000-8000-000000000007', 'COMPANY', 'Company Member',
     'The baseline every company member holds: company:read and nothing else. Granted '
     'automatically on joining, never offered in the grant UI and never revocable there — it is a '
     'consequence of membership rather than a decision about a person. It exists because '
     'company:read is granted to every member by roles spec §A3 while the four member-facing roles '
     'are PROJECT-scope, so without it the company-wide question answers "no" for everyone below '
     'Company Admin. It confers nothing about any project, which is what makes it safe to hold '
     'universally.',
     true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000007', 'company:read'
ON CONFLICT DO NOTHING;

-- Exactly one permission, and the seed asserts it rather than describing it: this
-- role is held by every human in every company, so anything added here is granted
-- to everybody in one edit. `qualifier` takes its DEFAULT 'ALL' (V12) — company:read
-- is not row-scoped and OWN would mean nothing for it.


-- =============================================================================
-- 2. THE GRANT, AS THE ONE STATEMENT THREE CALLERS SHARE
-- =============================================================================

CREATE FUNCTION tcms_grant_company_read_baseline(p_company_id UUID) RETURNS integer
    LANGUAGE plpgsql AS $$
DECLARE
    v_created integer;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    -- Every member who does not already hold it, in one statement. REMOVED
    -- memberships are excluded: offboarding is a status update, and a grant that
    -- survived it would be a row an administrator has to know to go and delete.
    -- INVITED members ARE included — the evaluator gates the COMPANY term on an
    -- ACTIVE membership, so the grant confers nothing until they accept, and
    -- granting at membership creation is what makes the invariant "a membership
    -- row implies a baseline grant" hold without a second trigger point.
    --
    -- NOT EXISTS is the fast path; V13's uq_role_assignment_user (partial, NULLS
    -- NOT DISTINCT, so it covers project_id IS NULL — the COMPANY scope) is the
    -- guarantee, and ON CONFLICT DO NOTHING is what makes two concurrent callers
    -- — a provisioning re-run and an invitation acceptance — both succeed.
    INSERT INTO role_assignment
        (id, company_id, role_id, user_id, team_id, project_id, granted_by, granted_at)
    SELECT gen_random_uuid(),
           p_company_id,
           '10000000-0000-4000-8000-000000000007',
           m.user_id,
           NULL,
           NULL,
           -- granted_by is NULL on purpose: nobody decided this. Attributing it to
           -- the operator or the inviter would put a person's name on a row they
           -- did not choose to create, in the table an audit reader uses to answer
           -- "who gave them that".
           NULL,
           now()
      FROM company_membership m
     WHERE m.company_id = p_company_id
       AND m.status <> 'REMOVED'
       AND NOT EXISTS (
            SELECT 1
              FROM role_assignment ra
             WHERE ra.company_id = p_company_id
               AND ra.role_id = '10000000-0000-4000-8000-000000000007'
               AND ra.user_id = m.user_id
               AND ra.project_id IS NULL)
    ON CONFLICT DO NOTHING;

    GET DIAGNOSTICS v_created = ROW_COUNT;
    RETURN v_created;
END $$;

COMMENT ON FUNCTION tcms_grant_company_read_baseline(UUID) IS
    'Grants the Company Member baseline (company:read, COMPANY scope) to every non-REMOVED member '
    'of one company that does not already hold it, and returns how many rows it wrote. Idempotent, '
    'so provision-company can call it on every run and invitation acceptance can call it again. '
    'Refuses to run without the matching company context, because RLS would otherwise hide every '
    'row it wrote (V2 §4).';


-- =============================================================================
-- 3. THE BACKFILL — every member who already exists
-- =============================================================================
--
-- A company provisioned before this migration has members with no baseline grant,
-- and once reachOf stops asking the workaround question those members answer "no"
-- to company:read — which reads to them as an empty admin directory, not as a
-- missing grant. So the repair has to land with the code, not with the next
-- provisioning run.
--
-- The context is set per company because the policy is a bare
-- `company_id = current_setting(...)` equality (V1/V4) and FORCE ROW LEVEL
-- SECURITY covers the owner this migration runs as. `company` itself is
-- instance-level (ERD §0.1), so the loop can see every company to iterate.
--
-- set_config(..., is_local => true) scopes each setting to this migration's
-- transaction; the final reset returns it to '' rather than leaving the last
-- company set, which is the difference between "no context" and "somebody else's
-- context" on a connection Flyway hands back.

DO $$
DECLARE
    v_company RECORD;
    v_created integer;
    v_total   integer := 0;
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);
        v_created := tcms_grant_company_read_baseline(v_company.id);
        v_total := v_total + v_created;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);

    RAISE NOTICE 'V15: granted the Company Member baseline to % existing membership(s)', v_total;
END $$;
