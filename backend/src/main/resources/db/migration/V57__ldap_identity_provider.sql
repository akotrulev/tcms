-- =============================================================================
-- V57 -- LDAP / Active Directory as a second kind of identity provider.
--
-- Source: docs/plans/2026-08-15-m4-external-identity-ldap-ad.md. V53 shipped
--         `kind IN ('OIDC','LDAP')` and an implementation of the first of them.
--
-- THE ONE THING TO UNDERSTAND BEFORE EDITING THIS FILE
--
--   client_id had to lose NOT NULL, because an LDAP provider has no relying
--   party identity to put in it. A dropped NOT NULL is dropped for EVERY row,
--   including the OIDC ones that were shipped with it -- and an OIDC provider
--   with a null client id does not fail here. It fails at the token exchange,
--   in a deployment, days later, as a sign-in that refuses everybody.
--
--   ck_identity_provider_shape is what replaces it: the same guarantee,
--   conditional on kind, plus the three LDAP columns that must be present for
--   the search to be constructible at all. It is strictly stronger than what it
--   replaced. LdapSchemaTest drives both arms.
--
-- Rejected: a separate ldap_identity_provider table. The company scoping, the
--           RLS policy, the CRUD, the audit events, the discovery function and
--           user_identity's (provider, subject) key are identical for both
--           kinds -- a second table would duplicate all of it to avoid eight
--           nullable columns, and would make `which providers does this domain
--           reach` a union.
-- =============================================================================

ALTER TABLE identity_provider
    ALTER COLUMN client_id DROP NOT NULL,
    ADD COLUMN bind_dn                 TEXT,
    ADD COLUMN bind_password_encrypted BYTEA,
    ADD COLUMN start_tls               BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN user_search_base        TEXT,
    ADD COLUMN user_search_filter      TEXT,
    ADD COLUMN user_id_attribute       TEXT,
    ADD COLUMN email_attribute         TEXT,
    ADD COLUMN display_name_attribute  TEXT;

ALTER TABLE identity_provider
    ADD CONSTRAINT ck_identity_provider_shape CHECK (
        (kind = 'OIDC'
            AND client_id IS NOT NULL
            AND user_search_base IS NULL
            AND user_search_filter IS NULL
            AND user_id_attribute IS NULL)
        OR
        (kind = 'LDAP'
            AND client_id IS NULL
            AND user_search_base IS NOT NULL
            AND user_search_filter IS NOT NULL
            AND user_id_attribute IS NOT NULL)
    );

COMMENT ON COLUMN identity_provider.issuer_uri IS
    'Where the authority lives, and what it means follows `kind`. OIDC: the issuer, always https, '
    'because the discovery document -- and therefore the signing-key URL every assertion is checked '
    'against -- is read from it. LDAP: the directory '
    'URL, ldaps:// or ldap:// with start_tls -- a plain bind puts the PERSON''S OWN directory '
    'password on the wire, which is a larger exposure than the https gate covers, and there is '
    'deliberately no flag that permits it.';
COMMENT ON COLUMN identity_provider.bind_password_encrypted IS
    'ENCRYPTED, not hashed, for client_secret_encrypted''s reason: a bind PRESENTS it. Never in a '
    'response, log, audit event or error message. Null means an anonymous search, which some '
    'directories permit and Active Directory does not.';
COMMENT ON COLUMN identity_provider.user_search_filter IS
    'Must contain {0}, which is where the typed address is substituted WITH RFC 4515 ESCAPING '
    'APPLIED. Concatenating it instead is LDAP filter injection on an unauthenticated surface, '
    'whose best case is matching the wrong person. IdentityProviderService refuses a filter '
    'without the placeholder.';
COMMENT ON COLUMN identity_provider.user_id_attribute IS
    'The directory''s immutable identifier -- objectGUID on AD, entryUUID on OpenLDAP -- and it is '
    'what lands in user_identity.subject. NEVER a DN: a DN encodes tree position, so moving '
    'somebody between OUs would orphan their account, their memberships and their authorship.';

-- -----------------------------------------------------------------------------
-- Discovery must now tell the sign-in screen WHICH CONTROL to render: a button
-- that navigates to an issuer, or a password field on the screen itself.
--
-- DROP-then-CREATE, not CREATE OR REPLACE: Postgres refuses to replace a
-- function whose RETURNS TABLE shape changed (42P13). The drop takes the grants
-- with it, so the REVOKE and the GRANT below are not decoration -- without them
-- a MIGRATED deployment answers `permission denied for function` on its login
-- screen while every fresh-container test stays green.
--
-- Everything else is V55's function unchanged, including the reason it is
-- SECURITY INVOKER (V8: FORCE ROW LEVEL SECURITY subjects the owner to its own
-- policies, so a definer function would return zero rows).
-- -----------------------------------------------------------------------------
DROP FUNCTION tcms_resolve_providers_for_domain(text);

CREATE FUNCTION tcms_resolve_providers_for_domain(p_domain TEXT)
    RETURNS TABLE (provider_id UUID, display_name TEXT, kind TEXT)
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
            SELECT p.id, p.display_name, p.kind
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
    '(§6r). `kind` says which CONTROL to render and nothing about who operates the route.';

REVOKE ALL ON FUNCTION tcms_resolve_providers_for_domain(text) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION tcms_resolve_providers_for_domain(text) TO tcms_app';
    END IF;
END $$;
