-- =============================================================================
-- V55 — the pre-authentication provider-discovery function, moved out of V53
--       because V53 had already run on a live database
--
-- Source: docs/plans/2026-08-15-m4-external-identity-closing-plan-1.md, §217
--         phase 4. Nothing about the FUNCTION changed here: the body, the
--         comment and the grant below are the ones §217 phase 4 wrote. What
--         changed is which version they arrive under.
--
-- WHY THIS IS A SEPARATE VERSION AT ALL. §217 phase 4 appended this block to
-- V53__external_identity_oidc.sql, which phase 1 had already shipped — and the
-- lab had already deployed and applied. Flyway records a checksum per applied
-- version, so the edit did not add a function to the lab: it made the lab's
-- backend refuse to start, `Migration checksum mismatch for migration version
-- 53`, on the deploy of 87a2614 (Actions run 31897579899).
--
-- THE LOCAL GATE STRUCTURALLY CANNOT SEE THIS. Every backend test migrates a
-- fresh Testcontainers database, where a rewritten V53 and the original are
-- equally valid — there is no schema history to disagree with. The first
-- observer of an edited applied migration is a database that has already run
-- it, which in this project means the lab, at deploy time, after the gates are
-- green. Same family as the `out-of-order` rule in the parallel-execution plan,
-- and the same blind spot.
--
-- `flyway repair` was the other option and is the wrong one: it would rewrite
-- the recorded checksum and move on, leaving the lab permanently without
-- tcms_resolve_providers_for_domain while every test stayed green. The symptom
-- would then be a 500 on `POST /auth/providers` — or, worse, the empty button
-- list the function's own comment warns about — reachable only in the lab.
-- Restoring V53 to its applied bytes and re-landing the block here is the fix
-- that leaves both a migrated database and a fresh one holding the same schema.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Provider DISCOVERY with no company context open, and the reason it needs its
-- own function.
--
-- ADDED IN §217 PHASE 4, AFTER V53 WAS WRITTEN. The plan for that slice
-- specified `POST /auth/providers` -- email in, which sign-in buttons
-- to show out -- and provided no way to READ company_email_domain to answer it.
-- That table is company-owned and FORCE RLS-protected like the other two, and
-- the question is asked by an anonymous caller on a login form, so the answer
-- under RLS is zero rows: SSO would have been undiscoverable, permanently, and
-- the symptom would have been an empty button list rather than an error. That is
-- V53's own trap, one endpoint further along than its header noticed.
--
-- Same shape as tcms_resolve_identity_provider in V53 and for the same V8 reason:
-- SECURITY INVOKER, running as tcms_app, walking the ACTIVE companies and asking
-- the ordinary fully-policed question in each. SECURITY DEFINER would return
-- nothing, because FORCE ROW LEVEL SECURITY subjects the table's owner to its
-- own policies.
--
-- WHAT IT DELIBERATELY DOES NOT DO IS FILTER OR FAIL. It returns the union
-- across every company claiming the domain, in one flat list, with no company id
-- and no company name in the result -- so the caller cannot tell one company
-- from several, and an unclaimed domain answers with an empty set rather than a
-- refusal. §6r: a login surface that distinguished those cases would be an
-- oracle for which client organisations exist on this instance, and the domain
-- being deliberately non-unique (see company_email_domain in V53) is precisely
-- what makes several a legitimate answer.
--
-- The display name is the ONLY string that crosses, because a button needs a
-- label. It is chosen by the client's own administrator, so it is theirs to
-- disclose to somebody who already typed an address at their domain.
-- -----------------------------------------------------------------------------
CREATE FUNCTION tcms_resolve_providers_for_domain(p_domain TEXT)
    RETURNS TABLE (provider_id UUID, display_name TEXT)
    LANGUAGE plpgsql
    -- VOLATILE for V53's reason: the body calls set_config.
    VOLATILE
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    previous text := nullif(current_setting('app.current_company', true), '');
    candidate record;
BEGIN
    FOR candidate IN SELECT id FROM company WHERE status = 'ACTIVE' ORDER BY id LOOP
        PERFORM set_config('app.current_company', candidate.id::text, true);

        RETURN QUERY
            SELECT p.id, p.display_name
              FROM identity_provider p
              JOIN company_email_domain d ON d.company_id = p.company_id
             WHERE p.enabled
               AND d.domain = p_domain::citext
             ORDER BY p.display_name;
    END LOOP;

    PERFORM set_config('app.current_company', coalesce(previous, ''), true);
END;
$$;

COMMENT ON FUNCTION tcms_resolve_providers_for_domain(text) IS
    'Which live sign-in routes an email domain reaches, for the pre-authentication discovery call '
    'where no session and no company context exist yet. Runs as the CALLER with RLS fully in force '
    '(V8''s shape). Returns the UNION across every company claiming the domain, carrying no company '
    'id and no company name, so the answer cannot be read as which clients exist on this instance '
    '(§6r).';

REVOKE ALL ON FUNCTION tcms_resolve_providers_for_domain(text) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION tcms_resolve_providers_for_domain(text) TO tcms_app';
    END IF;
END $$;
