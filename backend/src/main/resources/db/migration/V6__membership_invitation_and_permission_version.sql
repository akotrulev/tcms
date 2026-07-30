-- =============================================================================
-- V6 — the invitation/activation path, and the revocation epoch
--
-- provision-company leaves the first Company Admin INVITED (deliberately: an
-- operator who could activate a client's administrator on their behalf is an audit
-- finding, not a convenience). Nothing existed behind that state — no table, no
-- token, no way in — so the deployment stood up infrastructure with no usable
-- login. This migration is the schema half of closing that.
--
-- Two independent additions, in one migration because they are one slice:
--   1. membership_invitation — the redeemable token.
--   2. app_user.permission_version — the `pv` revocation epoch (plan §6b).
--
-- -----------------------------------------------------------------------------
-- WHY membership_invitation IS INSTANCE-LEVEL, WITH NO RLS
-- -----------------------------------------------------------------------------
--
-- This is an addition to the ERD §0.1 exception list, which requires a decision
-- rather than a quiet entry in a Set.of(). The decision and its cost:
--
-- The accept path runs BEFORE authentication. There is no session, so there is
-- nothing to resolve a company from — and this codebase's rule is absolute:
-- CompanyContext is populated from the authenticated session, NEVER from a request
-- parameter, header or path segment. A company-owned, RLS-protected invitation row
-- would therefore be unreadable in exactly the one place it must be read. That is
-- the same shape as V1's role_assignment.company_id IS NULL defect (handoff §11):
-- a row that exists under a policy no reachable context satisfies is a row that can
-- never be seen, and the tempting repairs are worse than the disease.
--
-- The rejected alternative was to encode the company id into the token itself
-- (<companyId>.<secret>), so the accept path could open a context before looking the
-- row up. It keeps this list closed, and it discloses nothing the invitee does not
-- already know. It is still wrong: it makes request input select the tenancy. Permit
-- that for one endpoint and the rule that makes 56 policies meaningful becomes a
-- convention instead of an invariant.
--
-- THE COST, STATED RATHER THAN GLOSSED: the fact "this user was invited to this
-- company" lives outside the isolation boundary. Three deliberate mitigations, all
-- visible in the column list below:
--   * the row carries NO email, NO name, NO display name — two foreign keys, one
--     hash, three timestamps. Every human-readable fact stays in app_user (already
--     instance-level) and company_membership (RLS-protected);
--   * token_hash is a SHA-256 digest, so the table is not a credential store — a
--     read of this table yields nothing redeemable;
--   * rows are consumed and then purgeable, so the fact is not retained.
--
-- -----------------------------------------------------------------------------
-- WHY THE TOKEN IS STORED AS A HASH
-- -----------------------------------------------------------------------------
--
-- An invitation token is a bearer credential: whoever holds it becomes the
-- administrator of a client company. Stored in plaintext, a database dump, a replica,
-- a backup or an over-broad SELECT hands out that capability directly. The digest is
-- unsalted and uniterated ON PURPOSE — unlike a password, the secret is 32 bytes from
-- a CSPRNG, so there is no dictionary to attack and a slow KDF would only add latency
-- to a lookup that has to be an indexed equality match.
-- =============================================================================

CREATE TABLE membership_invitation (
    id           UUID PRIMARY KEY,

    -- The company the invitation admits the user to. A TARGET, not a tenancy
    -- discriminator — the same relationship instance_audit_event.company_id has, and
    -- the reason neither table is a candidate for a policy.
    company_id   UUID        NOT NULL REFERENCES company(id),
    user_id      UUID        NOT NULL REFERENCES app_user(id),

    -- Hex SHA-256 of the base64url secret. UNIQUE so a collision is a constraint
    -- violation rather than an ambiguous lookup, and so the lookup is an index probe.
    token_hash   TEXT        NOT NULL,

    expires_at   TIMESTAMPTZ NOT NULL,

    -- Set on redemption. Single-use is enforced here, not by deleting the row: an
    -- invitation that was used is evidence, and a second attempt with the same token
    -- must be distinguishable in the audit trail from a token that never existed —
    -- even though the API response deliberately cannot tell them apart.
    consumed_at  TIMESTAMPTZ,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The operator or admin who issued it, where there was one. Nullable for the
    -- unattended CLI run, for the same reason instance_audit_event.actor_user_id is:
    -- a synthetic "system" actor is indistinguishable from a real one later.
    created_by   UUID        REFERENCES app_user(id),
    lock_version BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT uq_membership_invitation_token UNIQUE (token_hash)
);

COMMENT ON TABLE membership_invitation IS
    'INSTANCE-LEVEL by decision (ERD §0.1): the accept path runs before authentication, so no '
    'company context can exist when the token is looked up, and CompanyContext is never '
    'populated from request input. Carries no email or name — every human-readable fact stays '
    'in app_user and company_membership. See the V6 header for the full reasoning and its cost.';

COMMENT ON COLUMN membership_invitation.token_hash IS
    'Hex SHA-256 of the secret. The plaintext is returned to the issuer exactly once and never '
    'stored, so a read of this table yields nothing redeemable. Unsalted deliberately: the '
    'secret is 32 CSPRNG bytes, so there is no dictionary and a slow KDF would only add latency '
    'to an equality lookup.';

-- At most ONE outstanding invitation per (company, user). Issuing a new one supersedes
-- the old, which is what makes "re-run provision-company to re-send" safe: without this
-- a re-run would leave two live tokens and revoking one would not revoke the other.
-- Partial, so the history of consumed invitations is unconstrained.
CREATE UNIQUE INDEX uq_membership_invitation_outstanding
    ON membership_invitation (company_id, user_id)
    WHERE consumed_at IS NULL;

-- For the purge of expired, unconsumed rows — the mitigation the table comment claims.
CREATE INDEX membership_invitation_expiry_idx
    ON membership_invitation (expires_at)
    WHERE consumed_at IS NULL;

-- Deliberately NO ENABLE/FORCE ROW LEVEL SECURITY here. SchemaConventionTest derives the
-- company-owned set from the exception list and asserts it in BOTH directions, so this
-- table's absence from the policy list is checked rather than assumed — and adding it to
-- that list without adding it here would fail the build.

-- =============================================================================
-- app_user.permission_version — the `pv` revocation epoch (plan §6b)
-- =============================================================================
--
-- A per-user counter bumped on ANY change to a role assignment, team membership,
-- company membership, user status or company status. The session token carries the
-- value it was issued with; every authenticated request compares the two and a
-- mismatch forces re-authentication.
--
-- It ships now even though this release's token carries no grant claims, because it
-- is load-bearing whatever the token carries: it is the revocation channel. Adding it
-- later would mean invalidating every token in flight to introduce the claim, whereas
-- adding the sys/cmp/prj grant claims on top of an existing epoch is additive.
--
-- INT, not BIGINT: a counter bumped on grant changes for one human being. At one bump
-- per second for a century it does not reach 2^31.
ALTER TABLE app_user
    ADD COLUMN permission_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN app_user.permission_version IS
    'The `pv` revocation epoch (plan §6b). Bumped on any change to this user''s effective '
    'authorization — role assignment, team membership, company membership, account status. A '
    'session token carries the value it was issued with; a mismatch on any request means the '
    'token predates a revocation and re-authentication is required. Mandatory, not an '
    'optimization: without it a revoked grant survives until the token expires.';
