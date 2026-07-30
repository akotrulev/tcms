-- =============================================================================
-- V8 — V7's SECURITY DEFINER function returned NOTHING in production, and the
--      whole test suite was structurally unable to notice
--
-- THE DEFECT
--
-- V7 answered "which companies may this account open a session in" with a
-- SECURITY DEFINER function owned by tcms_owner, on the reasoning that running with
-- the owner's rights would see past the RLS policy on company_membership.
--
-- It does not. company_membership carries FORCE ROW LEVEL SECURITY, and FORCE exists
-- precisely so that the TABLE OWNER is subject to its own policies — it is the first
-- of the two RLS traps this project has documented since M0. So the function ran as
-- tcms_owner, matched the policy against an unset app.current_company, and returned
-- zero rows. Every login produced a token with no `co` claim; every session ran
-- context-less; /me/permissions answered with an empty set for a Company Admin.
--
-- Fail-closed, so not a leak. But the first person to sign in to a new instance is
-- its administrator, and they would have found an application in which they could do
-- nothing at all, with no error anywhere to explain it.
--
-- WHY 167 GREEN TESTS SAID NOTHING
--
-- IntegrationTestBase migrates as the container's default role, which is a SUPERUSER.
-- A superuser bypasses RLS unconditionally — FORCE included — so under test the
-- definer's rights really did see past the policy. The suite exercised an arrangement
-- production does not have. IntegrationTestBase's own comment has warned since M0 that
-- the owner being a superuser is weaker than the deployment; this is the first time
-- that gap produced a defect, and it took `docker compose up` to find it.
--
-- THE FIX, AND WHY IT IS BETTER THAN WHAT IT REPLACES
--
-- Drop the definer's rights entirely. The function now runs as its CALLER — the
-- unprivileged tcms_app — and walks the ACTIVE companies (the `company` table is
-- instance-level and carries no policy), setting app.current_company for each and
-- asking the ordinary, fully-policed question.
--
-- That removes the named hole in the isolation boundary that V7 introduced, rather
-- than making it work: there is no privilege escalation left to bound, RLS is in force
-- for every row read, and the suite now exercises the same code path production does,
-- because SECURITY INVOKER means the caller's rights are the ones that matter.
--
-- The cost is one indexed EXISTS-shaped probe per ACTIVE company per login. A
-- self-hosted instance serves a handful of client companies; if that ever stops being
-- true, the answer is an instance-level index of the membership fact, not a bypass.
--
-- THE GUC IS RESTORED, AND THAT MATTERS
--
-- set_config(..., is_local => true) is transaction-scoped, and this function runs
-- inside the caller's transaction — so without the restore at the end it would leave
-- app.current_company pointing at whichever company the loop happened to finish on.
-- The login path has no company open, but a future caller might, and a function that
-- silently repoints the tenancy discriminator of its caller's transaction is the exact
-- shape of a cross-company read that looks completely ordinary afterwards.
-- =============================================================================

CREATE OR REPLACE FUNCTION tcms_companies_for_user(p_user_id uuid)
    RETURNS TABLE (company_id uuid, accepted_at timestamptz)
    LANGUAGE plpgsql
    -- VOLATILE, not STABLE: the body calls set_config. Declaring it STABLE would be a
    -- lie to the planner about a function that mutates session state.
    VOLATILE
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    -- nullif for V4's reason: once set_config has run on a session, a "reset" GUC reads
    -- as the empty string rather than NULL for the rest of that session, and every
    -- pooled connection is in that state.
    previous text := nullif(current_setting('app.current_company', true), '');
    candidate record;
BEGIN
    FOR candidate IN SELECT id FROM company WHERE status = 'ACTIVE' ORDER BY id LOOP
        PERFORM set_config('app.current_company', candidate.id::text, true);

        RETURN QUERY
            SELECT m.company_id, m.accepted_at
              FROM company_membership m
             WHERE m.user_id = p_user_id
               AND m.status = 'ACTIVE';
    END LOOP;

    -- Restore, always. coalesce to '' rather than NULL because set_config takes text
    -- and NULL would reset it to the session default instead of to "no company".
    PERFORM set_config('app.current_company', coalesce(previous, ''), true);
END;
$$;

COMMENT ON FUNCTION tcms_companies_for_user(uuid) IS
    'Which companies an account may open a session in — ids only, ACTIVE memberships of '
    'ACTIVE companies. Runs as the CALLER with RLS fully in force: it walks the active '
    'companies and asks the ordinary policed question for each, rather than bypassing the '
    'policy. V7 used SECURITY DEFINER and returned nothing in production, because FORCE ROW '
    'LEVEL SECURITY covers the table owner too; see the V8 header. Callers must pass an '
    'already-authenticated account''s id, never one taken from a request.';
