-- =============================================================================
-- V7 — the login path needs to know which companies an account belongs to,
--      and under RLS it cannot ask
--
-- THE PROBLEM
--
-- Authentication happens before a company is active. To issue a session token the
-- login path must choose the `co` claim, which means answering "which companies is
-- this account an ACTIVE member of" — and company_membership is company-owned with
-- FORCE ROW LEVEL SECURITY, so with no app.current_company set that query correctly
-- returns ZERO rows. The isolation layer working exactly as designed is what makes
-- signing in impossible.
--
-- Every repair that stays in application code is worse:
--
--   * open a context per company and probe each one — O(companies) transactions on
--     every login, and it still needs the company list;
--   * store a default_company_id on app_user — cheap, but a user whose default is
--     suspended or whose membership was removed can then never sign in anywhere,
--     and there is no path to discover the others;
--   * grant tcms_app BYPASSRLS — voids isolation for all 56 tables to answer one
--     question.
--
-- THE ANSWER, AND ITS COST
--
-- A SECURITY DEFINER function owned by tcms_owner: it runs with the owner's rights,
-- so it sees past the policy. That is a deliberate, named hole in the isolation
-- boundary and it is kept as small as one can be made:
--
--   * it takes a user id and returns COMPANY IDS ONLY — no names, no display names,
--     no membership metadata. Everything human-readable is still read afterwards
--     through RLS, with the company open;
--   * it returns only ACTIVE memberships of ACTIVE companies, so a suspended client
--     cannot be selected as a session's company;
--   * search_path is pinned. Without it, a SECURITY DEFINER function is the classic
--     Postgres privilege-escalation vector: a caller who can create objects in a
--     schema earlier on the path can shadow a table or operator the body resolves,
--     and have it executed as the owner;
--   * EXECUTE is revoked from PUBLIC and granted only to tcms_app.
--
-- What it does NOT do is expose the answer to anyone. It is called by the login path
-- with the id of the account that has just proved its password — never with an id
-- from a request. "Which other clients does this person work with" remains something
-- no API surface answers below Instance Admin (plan §6s).
-- =============================================================================

CREATE FUNCTION tcms_companies_for_user(p_user_id uuid)
    RETURNS TABLE (company_id uuid, accepted_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT m.company_id, m.accepted_at
      FROM company_membership m
      JOIN company c ON c.id = m.company_id
     WHERE m.user_id = p_user_id
       AND m.status = 'ACTIVE'
       AND c.status = 'ACTIVE'
     -- Deterministic, and the ordering IS the default-company rule: the company they
     -- joined first. Arbitrary but stable, so a session does not land somewhere
     -- different on each login. Company switching (M6) replaces this with a choice.
     ORDER BY m.accepted_at NULLS LAST, m.company_id;
$$;

COMMENT ON FUNCTION tcms_companies_for_user(uuid) IS
    'SECURITY DEFINER, and the ONLY function permitted to read membership across companies. '
    'It exists because authentication precedes company context: with no app.current_company '
    'set, an RLS-protected read of company_membership correctly returns nothing, so the login '
    'path could not choose a session company. Returns company IDS ONLY, for ACTIVE memberships '
    'of ACTIVE companies. Callers must pass the id of an already-authenticated account, never '
    'one taken from a request. See the V7 header.';

-- PUBLIC holds EXECUTE on a new function by default, which for a SECURITY DEFINER
-- function means every role on the instance. Revoked first, then granted narrowly.
REVOKE ALL ON FUNCTION tcms_companies_for_user(uuid) FROM PUBLIC;

-- Conditional because the two environments create the role at opposite ends of the
-- migration: the deployment's initdb script creates tcms_app BEFORE Flyway runs
-- (compose, and the Hetzner runbook), while the test harness creates it in an
-- afterMigrate callback — deliberately, since provisioning roles is a deployment
-- concern and not a production migration's job. An unconditional GRANT here would
-- therefore fail the whole suite on "role tcms_app does not exist".
--
-- The test callback and the initdb script each grant this explicitly as well, so the
-- role gets it whichever order it was created in.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION tcms_companies_for_user(uuid) TO tcms_app';
    END IF;
END $$;
