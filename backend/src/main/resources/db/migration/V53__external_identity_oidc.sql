-- =============================================================================
-- V53 — external identity: a company may authenticate its own people against
--        its own OIDC issuer, and a person may hold several identities.
--
-- Source: plan §6r, ERD "identity_provider / user_identity — M4, not V1".
--         Resolved questions #37 (Keycloak is the reference issuer), #38 (JIT
--         defaults to invitation-required), #40 (no SAML). Handoff §217.
--
-- THE ONE THING TO UNDERSTAND BEFORE EDITING THIS FILE
--
--   Every read on this path happens BEFORE company context exists. An OIDC
--   callback carries a `state` and nothing else -- no session, no company. So
--   `app.current_company` is unset, RLS yields zero rows, and sign-in fails
--   closed for everyone, permanently. That is V7's defect exactly, and it was
--   invisible to a suite that migrated as a superuser.
--
--   The answer is V6's and V36's, not a new one: the pre-authentication lookup
--   is an INSTANCE-LEVEL table holding the minimum that answers the question,
--   while the full row and its lifecycle stay RLS-protected and are checked
--   after the context is open. `sso_login_attempt` is that table; ERD §0.1
--   carries the decision.
--
-- Rejected: putting the PKCE verifier in a signed cookie to avoid the table.
--           It hands a confidential client's PKCE secret to the browser and
--           moves the state/nonce replay check client-side, where it is not a
--           check at all.
-- =============================================================================

CREATE TABLE identity_provider (
    id                        UUID PRIMARY KEY,
    company_id                UUID NOT NULL REFERENCES company (id),
    key                       TEXT NOT NULL,
    kind                      TEXT NOT NULL,
    display_name              TEXT NOT NULL,
    issuer_uri                TEXT NOT NULL,
    client_id                 TEXT NOT NULL,
    client_secret_encrypted   BYTEA,
    jit_provisioning          BOOLEAN NOT NULL DEFAULT FALSE,
    auto_link_verified_email  BOOLEAN NOT NULL DEFAULT FALSE,
    group_claim               TEXT,
    enabled                   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                UUID REFERENCES app_user (id),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                UUID REFERENCES app_user (id),
    lock_version              BIGINT NOT NULL DEFAULT 0,

    -- Not SAML: #40 resolved it out of scope, and a member nothing can create
    -- is a state the code must branch on for no reason.
    CONSTRAINT ck_identity_provider_kind CHECK (kind IN ('OIDC', 'LDAP')),

    CONSTRAINT uq_identity_provider_key UNIQUE (company_id, key)
);

COMMENT ON TABLE identity_provider IS
    'One external issuer a company authenticates its own people against (plan §6r). Company-owned '
    'and FORCE RLS-protected: issuer trust is COMPANY-LOCAL, so a different client''s IdP asserting '
    'a subject can never resolve to this company''s user. Provider RESOLUTION before a session '
    'exists goes through tcms_resolve_identity_provider, not through this table directly.';
COMMENT ON COLUMN identity_provider.jit_provisioning IS
    'Defaults FALSE (#38): an IdP authoritative for one organisation is not authoritative for who '
    'may enter a client''s company. First login with no invitation is refused unless this is on.';
COMMENT ON COLUMN identity_provider.client_secret_encrypted IS
    'ENCRYPTED, not hashed -- unlike every other credential in this schema. A token exchange needs '
    'the plaintext back, so InvitationTokens''/api_token_lookup''s SHA-256 digest is the wrong tool '
    'here and reusing it would make the secret unusable. Never in a response, log, audit event or '
    'error message (§6r). Nullable: a public client uses PKCE alone.';
COMMENT ON COLUMN identity_provider.enabled IS
    'Defaults FALSE so a half-configured provider is never a live sign-in route. Read AFTER the '
    'company context is open, so a disabled provider refuses on the same code path as every other '
    'refusal and is indistinguishable from them (§6r).';

-- company_id LEADS, per §0.1 point 3.
CREATE INDEX identity_provider_company_enabled_idx
    ON identity_provider (company_id, enabled);

CREATE TABLE user_identity (
    id             UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company (id),
    user_id        UUID NOT NULL REFERENCES app_user (id),
    provider_id    UUID NOT NULL REFERENCES identity_provider (id),
    subject        TEXT NOT NULL,
    email_at_link  TEXT,
    linked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,

    -- THE identity key. Per-company issuer trust (§6r): a DIFFERENT company's
    -- IdP asserting the same `sub` must never resolve to this user. provider_id
    -- is already company-scoped, so this is (company, issuer, subject) with the
    -- company folded into the provider -- and company_id leads the index below
    -- so the discriminator is not merely decorative.
    CONSTRAINT uq_user_identity_subject UNIQUE (provider_id, subject)
);

COMMENT ON TABLE user_identity IS
    'One external identity bound to one TCMS user. The key is (provider, subject) and NEVER the '
    'email: emails change and get reassigned, and the IdP `sub` is the only stable handle (§6r). '
    'An already-linked subject resolves without email being consulted at all -- otherwise a user '
    'whose corporate address changed loses their account and their authorship.';
COMMENT ON COLUMN user_identity.email_at_link IS
    'What the IdP said at link time, kept as a record. NEVER read back as a key.';

CREATE INDEX user_identity_company_user_idx ON user_identity (company_id, user_id);

CREATE TABLE company_email_domain (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES company (id),
    -- CITEXT, not TEXT, for app_user.email's reason one label over: domains are
    -- case-insensitive, so on TEXT `acme.test` and `ACME.test` are two rows that
    -- satisfy the unique constraint and answer provider discovery differently
    -- depending on which one a lookup happened to normalise to.
    domain      CITEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NOT unique instance-wide: one domain may map to several companies, which
    -- §6r requires -- the user then authenticates FIRST and chooses among their
    -- own memberships afterwards. A global unique here would make that
    -- impossible and would leak, by rejection, that some other company already
    -- claimed the domain.
    CONSTRAINT uq_company_email_domain UNIQUE (company_id, domain)
);

COMMENT ON TABLE company_email_domain IS
    'Which companies claim an email domain, for provider discovery (§6r). Deliberately NOT unique '
    'instance-wide -- several companies may claim one domain. Discovery must answer IDENTICALLY '
    'whether the domain maps to one company, several, or none, or login becomes an oracle for '
    'which companies exist on the instance.';

CREATE INDEX company_email_domain_domain_idx ON company_email_domain (domain);

ALTER TABLE identity_provider    ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_provider    FORCE  ROW LEVEL SECURITY;
ALTER TABLE user_identity        ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_identity        FORCE  ROW LEVEL SECURITY;
ALTER TABLE company_email_domain ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_email_domain FORCE  ROW LEVEL SECURITY;

-- nullif(..., '') per V4: once set_config has run on a session its reset value
-- is '' rather than NULL for the rest of that session, so every POOLED
-- connection is in the second state and a bare cast raises
-- `invalid input syntax for type uuid` instead of returning nothing.
CREATE POLICY company_isolation ON identity_provider USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
CREATE POLICY company_isolation ON user_identity USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
CREATE POLICY company_isolation ON company_email_domain USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- The instance-level half. ERD §0.1 carries the decision; see the header.
-- -----------------------------------------------------------------------------
CREATE TABLE sso_login_attempt (
    id             UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company (id),
    provider_id    UUID NOT NULL REFERENCES identity_provider (id),
    state_hash     TEXT NOT NULL,
    code_verifier  TEXT NOT NULL,
    nonce          TEXT NOT NULL,
    redirect_uri   TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,

    CONSTRAINT uq_sso_login_attempt_state UNIQUE (state_hash)
);

COMMENT ON TABLE sso_login_attempt IS
    'Instance-level, NO RLS (ERD §0.1 exception list, decided here). Answers exactly one question '
    '-- which in-flight authorization request is this callback -- because it is asked before any '
    'company context can exist. company_id is a TARGET, the company the attempt is against, not a '
    'tenancy discriminator. Holds NOTHING identifying: no email, no subject, no user id, because '
    'at insert time none of them are known yet. Rows are single-use and short-lived, so the table '
    'is empty in steady state.';
COMMENT ON COLUMN sso_login_attempt.code_verifier IS
    'The PKCE verifier, held server-side. The rejected alternative was a signed cookie, which puts '
    'a confidential client''s PKCE secret in the browser.';
COMMENT ON COLUMN sso_login_attempt.consumed_at IS
    'Single-use. Stamped BEFORE the token exchange, not after: stamping it after leaves a window '
    'in which two concurrent callbacks carrying the same `state` both pass the check.';

CREATE INDEX sso_login_attempt_expires_idx ON sso_login_attempt (expires_at);

-- -----------------------------------------------------------------------------
-- Provider resolution with no company context open.
--
-- READ V7 AND V8 BEFORE CHANGING THE SECURITY MODEL ON THIS FUNCTION.
--
-- The obvious shape is SECURITY DEFINER -- run as the owner, see past the
-- policy, return the row. IT DOES NOT WORK, and it is precisely the defect V8
-- exists to record: identity_provider carries FORCE ROW LEVEL SECURITY, and
-- FORCE means the TABLE OWNER is subject to its own policies. A definer
-- function owned by tcms_owner would match the policy against an unset
-- app.current_company and return ZERO ROWS -- in production only, silently,
-- presenting as "that provider does not exist" rather than as a schema fault.
-- V7 shipped exactly that and 167 green tests said nothing, because the suite
-- migrated as a superuser at the time.
--
-- So this is V8's shape, unchanged: SECURITY INVOKER, running as the
-- unprivileged tcms_app, walking the ACTIVE companies and asking the ordinary,
-- fully-policed question for each. There is no privilege escalation to bound,
-- RLS is in force for every row read, and the suite exercises the same code
-- path production does.
--
-- It answers exactly one question -- "which company and issuer does this
-- provider id belong to" -- for a caller that has no context yet. It takes a
-- primary key, returns four scalar columns, and reads NOTHING about a user.
-- The provider's `enabled` flag is deliberately RETURNED rather than filtered,
-- so the refusal happens in Java on the same code path as every other refusal
-- and is therefore indistinguishable from them (§6r, trap 4).
--
-- The cost is V8's cost, one indexed primary-key probe per ACTIVE company per
-- authorize request. If a self-hosted instance ever serves enough companies for
-- that to matter, the answer is V8's answer -- an instance-level index of the
-- one fact -- and not a bypass.
-- -----------------------------------------------------------------------------
CREATE FUNCTION tcms_resolve_identity_provider(p_provider_id UUID)
    RETURNS TABLE (company_id UUID, issuer_uri TEXT, client_id TEXT, enabled BOOLEAN)
    LANGUAGE plpgsql
    -- VOLATILE, not STABLE: the body calls set_config. Declaring it STABLE would
    -- be a lie to the planner about a function that mutates session state.
    VOLATILE
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    -- nullif for V4's reason: once set_config has run on a session, a "reset" GUC
    -- reads as the empty string rather than NULL for the rest of that session,
    -- and every pooled connection is in that state.
    previous text := nullif(current_setting('app.current_company', true), '');
    candidate record;
BEGIN
    FOR candidate IN SELECT id FROM company WHERE status = 'ACTIVE' ORDER BY id LOOP
        PERFORM set_config('app.current_company', candidate.id::text, true);

        RETURN QUERY
            SELECT p.company_id, p.issuer_uri, p.client_id, p.enabled
              FROM identity_provider p
             WHERE p.id = p_provider_id;
    END LOOP;

    -- Restore, always. A function that silently repoints the tenancy
    -- discriminator of its caller's transaction is the exact shape of a
    -- cross-company read that looks completely ordinary afterwards. coalesce to
    -- '' rather than NULL because set_config takes text and NULL would reset it
    -- to the session default instead of to "no company".
    PERFORM set_config('app.current_company', coalesce(previous, ''), true);
END;
$$;

COMMENT ON FUNCTION tcms_resolve_identity_provider(uuid) IS
    'Which company, issuer and client an identity provider id belongs to, for the SSO authorize leg '
    'where no session and no company context exist yet. Runs as the CALLER with RLS fully in force '
    '(V8''s shape, for V8''s reason -- see the V53 header). Returns `enabled` rather than filtering '
    'on it, so a disabled provider refuses on the same code path as every other refusal. Reads '
    'nothing about any user.';

-- PUBLIC holds EXECUTE on a new function by default. Revoked first, then granted
-- narrowly: this one mutates the caller's transaction-local GUC, so it is kept to
-- the application role even though SECURITY INVOKER grants it no rights of its own.
REVOKE ALL ON FUNCTION tcms_resolve_identity_provider(uuid) FROM PUBLIC;

-- Conditional for V7's reason: the two environments create tcms_app at opposite
-- ends of the migration -- the deployment's initdb script before Flyway, the test
-- harness in its own pre-migration callback -- and an unconditional GRANT would
-- fail the whole suite on "role tcms_app does not exist" wherever it is created
-- later.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION tcms_resolve_identity_provider(uuid) TO tcms_app';
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- Provider DISCOVERY with no company context open, and the reason it needs its
-- own function.
--
-- ADDED IN §217 PHASE 4, AFTER THE REST OF THIS FILE WAS WRITTEN. The plan for
-- this slice specified `POST /auth/providers` -- email in, which sign-in buttons
-- to show out -- and provided no way to READ company_email_domain to answer it.
-- That table is company-owned and FORCE RLS-protected like the other two, and
-- the question is asked by an anonymous caller on a login form, so the answer
-- under RLS is zero rows: SSO would have been undiscoverable, permanently, and
-- the symptom would have been an empty button list rather than an error. That is
-- the header's own trap, one endpoint further along than the header noticed.
--
-- Same shape as tcms_resolve_identity_provider above and for the same V8 reason:
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
-- being deliberately non-unique (see company_email_domain above) is precisely
-- what makes several a legitimate answer.
--
-- The display name is the ONLY string that crosses, because a button needs a
-- label. It is chosen by the client's own administrator, so it is theirs to
-- disclose to somebody who already typed an address at their domain.
-- -----------------------------------------------------------------------------
CREATE FUNCTION tcms_resolve_providers_for_domain(p_domain TEXT)
    RETURNS TABLE (provider_id UUID, display_name TEXT)
    LANGUAGE plpgsql
    -- VOLATILE for the reason above: the body calls set_config.
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
