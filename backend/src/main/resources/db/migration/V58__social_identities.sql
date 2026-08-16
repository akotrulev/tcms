-- V58: social identities — GitHub, GitLab, Google as instance-level sign-in authorities.
--
-- Source: the 2026-08-16 social-login plan (docs/plans/), building on §6r's split: protocol in
-- com.tcms.auth, policy in identity. These authorities are for PEOPLE, not companies — a
-- gmail.com domain row would be claimable by every company at once — so unlike V53's
-- identity_provider there is no provider table at all (operator configuration lives in Spring
-- properties) and both tables here are INSTANCE-LEVEL: ERD §0.1 exceptions, no company_id, no
-- RLS. user_social_identity because a person's social identity is theirs across every company
-- they belong to; social_login_attempt because, like V53's sso_login_attempt (exception #13),
-- it is read before any session or company context can exist.
--
-- Identities are made by LINKING only (a signed-in act on the security page). No row here is
-- ever created during account creation — the plan's Appendix A records the invitation-time
-- variant that was considered and deferred.

CREATE TABLE user_social_identity (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES app_user (id),
    provider      TEXT NOT NULL,
    subject       TEXT NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    CONSTRAINT ck_user_social_identity_provider
        CHECK (provider IN ('GITHUB', 'GITLAB', 'GOOGLE')),
    -- One TCMS account per external identity: the login key, and the linking race's backstop.
    CONSTRAINT uq_user_social_identity_subject UNIQUE (provider, subject),
    -- One identity per provider per account: "link all three" is the ceiling.
    CONSTRAINT uq_user_social_identity_user UNIQUE (provider, user_id)
);

COMMENT ON TABLE user_social_identity IS
    'Instance-level (ERD §0.1): a person''s social identity spans their companies. The key is '
    '(provider, subject), NEVER email — §6r''s rule, same reason as user_identity.';
COMMENT ON COLUMN user_social_identity.email_at_link IS
    'What the provider asserted when the link was made. Provenance only; never read as a key.';

CREATE INDEX user_social_identity_user_idx ON user_social_identity (user_id);

CREATE TABLE social_login_attempt (
    id            UUID PRIMARY KEY,
    provider      TEXT NOT NULL,
    intent        TEXT NOT NULL,
    user_id       UUID REFERENCES app_user (id),
    state_hash    TEXT NOT NULL,
    code_verifier TEXT NOT NULL,
    nonce         TEXT NOT NULL,
    redirect_uri  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT ck_social_login_attempt_provider
        CHECK (provider IN ('GITHUB', 'GITLAB', 'GOOGLE')),
    CONSTRAINT ck_social_login_attempt_intent
        CHECK (intent IN ('LOGIN', 'LINK')),
    -- The intent decides the binding, exactly: a LINK belongs to a user, a LOGIN to nobody
    -- yet. ck_identity_provider_shape's pattern.
    CONSTRAINT ck_social_login_attempt_intent_shape
        CHECK ((intent = 'LINK') = (user_id IS NOT NULL)),
    CONSTRAINT uq_social_login_attempt_state UNIQUE (state_hash)
);

COMMENT ON TABLE social_login_attempt IS
    'Instance-level (ERD §0.1): consulted before any session exists, like sso_login_attempt. '
    'state_hash is the single-use binding; consumed_at is the anti-replay claim.';
