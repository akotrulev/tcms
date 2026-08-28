-- =============================================================================
-- V73 — issue-tracker integration, part 1: the mapping, the credential and the
--        outbound-attempt ledger
--
-- Plan 004 (docs/plans/004-issue-tracker-integration.md), *Storage*. 006's
-- batching moved this migration from the plan's V72 to V73: V71 (doc review) and
-- V72 (last_result names its configuration) landed first, and V70 stays reserved
-- for plan 002.
--
-- -----------------------------------------------------------------------------
-- TWO TABLES WHERE REVISION 2 HAD ONE, AND THE SPLIT IS THE WHOLE POINT OF D2
-- -----------------------------------------------------------------------------
--
-- A BINDING says which tracker project this TCMS project maps to. It is
-- configured once, under integration:manage, by a Test Lead, and it holds NO
-- CREDENTIAL AT ALL — there is not a column here one could be put in.
--
-- An IDENTITY says who may talk to that tracker and as whom. It is configured by
-- each person for themselves, holds the ciphertext, and is keyed to the HOST
-- rather than to the binding so one connect serves every project in this company
-- bound to that site.
--
-- They have different owners, different permissions, different lifecycles and
-- different secrecy, which is why one table could not carry both: every column
-- revision 2 put on the binding (secret, secret_hint, account_id, expires_at,
-- last_error, last_checked_at, mode, user_id) was always about a CREDENTIAL
-- rather than about a MAPPING, and a shared credential under a per-user product
-- is a leak with a settings screen in front of it.
--
-- THE STATED COST OF company_id ON tracker_identity: a person who is a member of
-- two companies both bound to the same Jira site connects TWICE. That is the
-- isolation boundary doing its job — a credential row readable across companies
-- would be the exact thing company_id exists to prevent — and it is a real
-- papercut, recorded rather than pretended away.
--
-- -----------------------------------------------------------------------------
-- auth_method IS CHECKED TO 'PAT', AND THE OTHER FOUR COLUMNS ARE THERE ANYWAY
-- -----------------------------------------------------------------------------
--
-- D3: a PAT is the only mechanism on all three providers that is ENTIRELY
-- user-side — no app registration, no Entra tenant config, no tracker-admin
-- action, no public redirect URI on a self-hosted instance. OAuth needs one of
-- those on every provider, before the first person can connect.
--
-- So refresh_token, scopes, expires_at and auth_method ship now and OAuth is a
-- RELAXED CHECK rather than a table redesign. Under PAT refresh_token and scopes
-- are always NULL; expires_at is not optional metadata, because Jira Cloud
-- enforces a maximum one-year token life and every Jira Cloud row here therefore
-- has a death date by construction.
--
-- -----------------------------------------------------------------------------
-- provider IS CHECKED HERE AND IS NOT CHECKED ON defect_link
-- -----------------------------------------------------------------------------
--
-- The difference is real and D9/D20 depend on it. THIS column selects an adapter
-- that must exist, so a value with no adapter behind it is a row that can only
-- fail. defect_link.provider and doc_link.external_provider RECORD what some
-- adapter said, so they stay free text and a fourth tracker is an adapter rather
-- than a migration.
--
-- -----------------------------------------------------------------------------
-- RLS, AND THE HALF RLS DOES NOT DO
-- -----------------------------------------------------------------------------
--
-- All three tables are company-owned and none is on ERD §0.1's exception list,
-- so all three take company_id UUID NOT NULL leading every composite index,
-- FORCE ROW LEVEL SECURITY and V4's nullif() predicate verbatim.
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares the
-- text and its policy count moves from 71 to 74.
--
-- RLS GIVES COMPANY ISOLATION AND NOT PER-PERSON ISOLATION. tracker_identity is
-- additionally gated in the service layer to user_id = the caller, because a
-- Company Admin must not be able to read a colleague's credential row through
-- any endpoint. That is TrackerIdentityService's rule and it has its own test;
-- this comment exists so a reader of the schema does not conclude the policy
-- below is the whole of it.
--
-- NO CREATE INDEX CONCURRENTLY — V17, V42, V59, V62 and V68 all refuse it on
-- record: Flyway runs a migration in one transaction and these tables are
-- created empty in this one, so the lock the concurrent form buys its way out of
-- is held over zero rows.
-- =============================================================================

CREATE TABLE tracker_binding (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    provider         TEXT NOT NULL
                     CHECK (provider IN ('JIRA_CLOUD', 'JIRA_DC', 'AZURE_DEVOPS')),
    base_url         TEXT NOT NULL CHECK (base_url LIKE 'https://%'),
    external_project TEXT NOT NULL,
    config           JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_default       BOOLEAN NOT NULL DEFAULT FALSE,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    lock_version     BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE tracker_binding IS
    'One TCMS project''s mapping to one tracker project (004 D1) — a Jira project key, or an ADO '
    'organization + team project. MANY per project, exactly one default. HOLDS NO CREDENTIAL and '
    'has no column one could be put in (D2): the credential is each person''s own tracker_identity '
    'row, keyed to this row''s host.';

COMMENT ON COLUMN tracker_binding.provider IS
    'Which adapter reads this binding. CHECKED here and deliberately NOT on defect_link.provider or '
    'doc_link.external_provider (D9/D20): this value SELECTS an adapter that must exist, those '
    'RECORD what some adapter said. Not updatable by the API — identities are keyed to the host, so '
    'a binding that changed provider or host would silently start using different people''s '
    'credentials.';

COMMENT ON COLUMN tracker_binding.base_url IS
    'The tracker site. https:// only, enforced BOTH at the write (OutboundUrlGuard) and by the CHECK '
    'here — the guard also refuses private, loopback, link-local and unique-local destinations AFTER '
    'DNS resolution, which no CHECK can see. Not updatable: see provider.';

COMMENT ON COLUMN tracker_binding.external_project IS
    'The Jira project key (PAY), or ADO''s org/teamProject. Free text — this product knows no '
    'tracker''s naming rules and refusing a shape it guessed at would reject a real project.';

COMMENT ON COLUMN tracker_binding.config IS
    'Per-provider settings the adapter reads — the issue type to file, a default area path, the '
    'JQL/WIQL scope clause the search is narrowed by. JSONB because the keys are the ADAPTER''s; '
    'each adapter validates its own on the write and refuses with 400 on errors.config.';

COMMENT ON COLUMN tracker_binding.is_default IS
    'Where POST .../tracker/issues files when the request names no binding. Exactly one per project, '
    'enforced by tracker_binding_default_idx: the first binding becomes the default whether or not '
    'it said so, and deleting the default promotes the oldest remaining one — so there is never '
    'zero and never two.';

COMMENT ON COLUMN tracker_binding.enabled IS
    'A disabled binding is skipped by search, preview and filing, and links made through it render '
    'degraded. Kept rather than deleted so the links keep resolving a provider and a host.';

CREATE INDEX tracker_binding_project_idx
    ON tracker_binding (company_id, project_id);

CREATE UNIQUE INDEX tracker_binding_target_idx
    ON tracker_binding (company_id, project_id, provider, base_url, external_project);

COMMENT ON INDEX tracker_binding_target_idx IS
    'One project may not be bound twice to the same tracker project on the same host — the 409 the '
    'create answers. company_id LEADS, per ERD §0.1 point 3.';

CREATE UNIQUE INDEX tracker_binding_default_idx
    ON tracker_binding (company_id, project_id)
    WHERE is_default;

COMMENT ON INDEX tracker_binding_default_idx IS
    'At most one default per project, enforced rather than intended: two defaults would make "where '
    'does a defect get filed" answerable two ways, and the answer would depend on row order.';

ALTER TABLE tracker_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE tracker_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON tracker_binding USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- tracker_identity — one row per person per tracker host
-- -----------------------------------------------------------------------------

CREATE TABLE tracker_identity (
    id              UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES company(id),
    user_id         UUID NOT NULL,
    provider        TEXT NOT NULL
                    CHECK (provider IN ('JIRA_CLOUD', 'JIRA_DC', 'AZURE_DEVOPS')),
    base_url        TEXT NOT NULL CHECK (base_url LIKE 'https://%'),
    auth_method     TEXT NOT NULL DEFAULT 'PAT' CHECK (auth_method = 'PAT'),
    secret          BYTEA NOT NULL,
    secret_hint     TEXT,
    account_id      TEXT,
    refresh_token   BYTEA,
    scopes          TEXT,
    expires_at      TIMESTAMPTZ,
    last_error      TEXT,
    last_checked_at TIMESTAMPTZ,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version    BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE tracker_identity IS
    'ONE PERSON''S connection to one tracker host (004 D2) — theirs, served to nobody else. Keyed '
    'to the HOST rather than to a binding, so one connect serves every project in this company '
    'bound to that site. RLS gives COMPANY isolation; the per-person half is TrackerIdentityService '
    'filtering on user_id = the caller, and no endpoint anywhere returns another person''s row. '
    'STATED COST: a person in two companies bound to the same Jira site connects twice.';

COMMENT ON COLUMN tracker_identity.user_id IS
    'The person whose credential this is. No foreign key, for storage.blob''s reason: app_user is '
    'instance-level (ERD §0.1''s exception list) and `integration` never resolves this id to a '
    'person — it is an opaque UUID here, exactly as owner_id is in `storage`.';

COMMENT ON COLUMN tracker_identity.auth_method IS
    'PAT, and the CHECK says so (004 D3). A PAT is the only mechanism on all three providers that '
    'is entirely user-side — no app registration, no tenant config, no redirect URI — so it is what '
    'ships first. Relaxing this CHECK is the OAuth slice''s first line; refresh_token, scopes and '
    'expires_at are already here so that slice is not a table redesign.';

COMMENT ON COLUMN tracker_identity.secret IS
    'The PAT / API token, AES-GCM via SecretCipher (nonce prepended). Recoverable rather than '
    'digested because the server has to PRESENT it — the argument SecretCipher''s header makes for '
    'notification_channel.secret, now once per person rather than once per project.';

COMMENT ON COLUMN tracker_identity.secret_hint IS
    'Four end-anchored characters. Recognition, never a credential — SecretHint''s rule. Served to '
    'the person whose row it is and to NOBODY else: /tracker/connected-members must not carry it '
    '(D19), and a test closes that response''s key set rather than naming the leaks it thought of.';

COMMENT ON COLUMN tracker_identity.account_id IS
    'The person''s own tracker login. Jira Cloud''s Basic auth needs the email beside the token; '
    'NULL for the providers that do not. Personal information, so it shares secret_hint''s '
    'audience: theirs alone.';

COMMENT ON COLUMN tracker_identity.expires_at IS
    'When the token dies. NOT OPTIONAL METADATA: Jira Cloud has enforced a maximum one-year token '
    'life since Dec 2024, so every Jira Cloud row here has a death date by construction, and the '
    'connections page shows the date on the first screen rather than in a drawer.';

COMMENT ON COLUMN tracker_identity.last_error IS
    'What the tracker said the last time a call with this credential failed; NULL after a success. '
    'The ERD''s sentence, now per person: "an expired Jira token that silently stops creating '
    'defects — while runs keep passing and failing — is the realistic failure and is invisible '
    'otherwise." The TEXT is the person''s own to read; connected-members shows a STATE.';

COMMENT ON COLUMN tracker_identity.refresh_token IS
    'Always NULL under PAT. Present from V73 so OAuth is a second auth_method rather than a second '
    'table — D3''s trick, applied deliberately this time.';

CREATE UNIQUE INDEX tracker_identity_host_idx
    ON tracker_identity (company_id, user_id, provider, base_url);

COMMENT ON INDEX tracker_identity_host_idx IS
    'One connection per person per host — what makes POST /me/tracker-identities an UPSERT: a '
    'second POST for a host already connected ROTATES the credential and answers 200, and there is '
    'no way to end up with two rows a reader would have to choose between.';

CREATE INDEX tracker_identity_user_idx
    ON tracker_identity (company_id, user_id);

ALTER TABLE tracker_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE tracker_identity FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON tracker_identity USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- tracker_issue_creation — the outbound-attempt ledger
-- -----------------------------------------------------------------------------

CREATE TABLE tracker_issue_creation (
    id              UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES company(id),
    project_id      UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    binding_id      UUID REFERENCES tracker_binding(id) ON DELETE SET NULL,
    user_id         UUID NOT NULL,
    execution_id    UUID,
    status          TEXT NOT NULL CHECK (status IN ('PENDING', 'CREATED', 'FAILED')),
    provider        TEXT NOT NULL,
    external_id     TEXT,
    external_key    TEXT,
    url             TEXT,
    error           TEXT,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

COMMENT ON TABLE tracker_issue_creation IS
    'Every attempt to create an issue in somebody else''s tracker — written PENDING before the call '
    'and completed after, mirroring notification_delivery. DIAGNOSTIC, NOT RECOVERY: recovery is '
    'the outbox handler''s job (004, The creation seam). It is how you find the orphan an event '
    'could not name — an issue that exists in Jira for an execution the payload got wrong.';

COMMENT ON COLUMN tracker_issue_creation.user_id IS
    'WHO FILED IT. Recorded because under D2 that is a real question with a real answer — the issue '
    'was created with this person''s credential, so the tracker''s own reporter is them — and the '
    'tracker''s answer is only visible to people who can read that tracker.';

COMMENT ON COLUMN tracker_issue_creation.binding_id IS
    'The binding used. ON DELETE SET NULL rather than CASCADE: unbinding a project must not destroy '
    'the record that an issue was filed through it (D18''s reasoning, applied to the ledger).';

COMMENT ON COLUMN tracker_issue_creation.error IS
    'What the tracker said, redacted through SecretRedaction before it is stored. The natural '
    'failure message quotes the URL that was posted to, and for a credential-bearing call that is '
    'the one string this column must never hold.';

COMMENT ON COLUMN tracker_issue_creation.idempotency_key IS
    'The request''s Idempotency-Key, or NULL when none was sent. Opaque, write-once, never read as '
    'data — as on test_case, doc_page, artifact and report_job. A replayed key answers 200 with the '
    'issue THIS row records rather than filing a second one, which is the failure people actually '
    'hit: a double-clicked "File defect".';

CREATE INDEX tracker_issue_creation_project_idx
    ON tracker_issue_creation (company_id, project_id, created_at DESC);

CREATE UNIQUE INDEX tracker_issue_creation_idempotency_idx
    ON tracker_issue_creation (company_id, project_id, user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON INDEX tracker_issue_creation_idempotency_idx IS
    'Enforces the replay guarantee, and its four columns ARE the lookup''s four predicates — a '
    'lookup narrower than the constraint finds nothing and then violates the index on insert, which '
    'is a validated request answering 500 (V14''s account). user_id is IN the key because the issue '
    'is filed as the caller: two people replaying the same key from the same client are two issues '
    'with two reporters, not one.';

ALTER TABLE tracker_issue_creation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tracker_issue_creation FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON tracker_issue_creation USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
