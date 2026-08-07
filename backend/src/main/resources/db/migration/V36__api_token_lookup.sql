-- =============================================================================
-- V36 — the credential M3 runs on: an instance-level lookup for the one question
--       that must be answered BEFORE a company context can exist
--
-- api_token has been in the schema since V1 with no entity, no repository and no
-- endpoint. M3's ingestion has no caller without it: a CI job is not a browser
-- session, and test_execution.executed_by_token and audit_event.actor_token_id are
-- columns nothing can populate.
--
-- -----------------------------------------------------------------------------
-- THE PROBLEM THIS MIGRATION EXISTS FOR
-- -----------------------------------------------------------------------------
--
-- api_token is in V1's company_owned array, so it carries FORCE ROW LEVEL SECURITY.
-- A request arriving with `Authorization: Bearer tcms_…` must resolve that string to
-- a token BEFORE app.current_company is set — there is nothing to set it from. Under
-- the policy that lookup returns ZERO rows, so the instance answers 401 to a valid
-- token, for ever, with no error anywhere to explain it.
--
-- That is V7's defect exactly (handoff §18/§19): a lookup that had to run outside any
-- company context, against a table whose owner does not escape FORCE. It was invisible
-- to a suite that migrated as a superuser, and it took `docker compose up` to find.
-- V8's closing paragraph names the answer in advance:
--
--     "if that ever stops being true, the answer is an instance-level index of the
--      membership fact, not a bypass."
--
-- So: an instance-level index of exactly one fact.
--
-- -----------------------------------------------------------------------------
-- WHY api_token_lookup IS INSTANCE-LEVEL, WITH NO RLS
-- -----------------------------------------------------------------------------
--
-- An addition to the ERD §0.1 exception list, which requires a decision rather than a
-- quiet entry in a Set.of(). This is membership_invitation's decision (V6) clause for
-- clause, and it is repeated here rather than cross-referenced because the cost is
-- being accepted a second time:
--
--   * presented BEFORE authentication, so no company context can exist at lookup;
--   * company_id is a TARGET — the company the digest belongs to — and not a tenancy
--     discriminator, exactly like instance_audit_event.company_id;
--   * the row holds a DIGEST and nothing else. No name, no owner, no role, no project.
--     A read of this table yields nothing usable and nothing about a client's people or
--     work; every authorization fact still comes from the POLICED api_token row, read
--     after the context is open.
--
-- THE REJECTED ALTERNATIVE is the one V6 also rejected: encoding the company id into
-- the token string (<companyId>.<secret>) so the filter could open a context from it.
-- It keeps this list closed and it discloses nothing the token holder does not already
-- know. It is still wrong, for V6's reason: it makes REQUEST INPUT SELECT THE TENANCY.
-- Permit that for one endpoint and the rule behind 61 policies is a convention.
--
-- THE COST, STATED: the fact "this digest belongs to this company" sits outside the
-- isolation boundary, and it is reachable only by someone who already has the database.
--
-- -----------------------------------------------------------------------------
-- THE HASH MOVES, RATHER THAN BEING COPIED
-- -----------------------------------------------------------------------------
--
-- V1 put token_hash on api_token. Leaving it there and adding a second copy here is a
-- divergence waiting to happen — revoke updates one, the filter reads the other, and a
-- revoked token keeps working with no row that looks wrong. A credential digest gets
-- ONE home.
--
-- What stays on the policed row is the LIFECYCLE: expires_at and revoked_at are read
-- after the company is open, so revocation is enforced inside the isolation boundary
-- where an operator can see it, and this table answers only "which company".
--
-- The DROP is safe because api_token has never had a writer: no code path in any
-- release could insert a row. It is written as a plain NOT NULL ADD (no default,
-- no backfill) for the same reason — if this migration fails here, rows were created
-- outside the application and that is something to look at, not to default over.
-- =============================================================================

ALTER TABLE api_token DROP COLUMN token_hash;

-- The first 12 characters of the secret, so a list can say WHICH token without holding
-- one. The plaintext is returned once, at issue, and is unrecoverable afterwards
-- (InvitationTokens' argument, one credential over), which leaves a list with nothing
-- to identify a row by except a name somebody typed.
ALTER TABLE api_token ADD COLUMN token_prefix TEXT NOT NULL;

COMMENT ON COLUMN api_token.token_prefix IS
    'The leading characters of the secret, for recognition in a list. NOT a credential: it is a '
    'deliberate partial disclosure of a 256-bit secret, and the digest lives in api_token_lookup.';

CREATE TABLE api_token_lookup (
    -- Hex SHA-256 of the presented secret, and the PRIMARY KEY: the lookup is an indexed
    -- equality probe on a value with 256 bits of entropy behind it. Unsalted and not a slow
    -- KDF, for InvitationTokens' recorded reason — a salted hash cannot be queried by, so
    -- every request would scan every outstanding token and verify each one.
    token_hash TEXT PRIMARY KEY,

    -- ON DELETE CASCADE so the two rows cannot outlive each other. A lookup row pointing at
    -- a deleted token is a credential the filter resolves to a company and then cannot place.
    token_id   UUID NOT NULL REFERENCES api_token(id) ON DELETE CASCADE,

    -- A TARGET, not a discriminator — see the header. Not NULL: a lookup row that cannot name
    -- its company is a row the filter must refuse, which is a state with no legitimate cause.
    company_id UUID NOT NULL REFERENCES company(id)
);

COMMENT ON TABLE api_token_lookup IS
    'Instance-level, NO RLS (ERD §0.1 exception list, decided here). Answers exactly one '
    'question — which company does this digest belong to — because it is asked before any '
    'company context can exist. Holds no name, owner, role or project: everything else is read '
    'from the RLS-protected api_token row once the context is open.';

COMMENT ON COLUMN api_token_lookup.token_id IS
    'The policed row carrying the lifecycle. expires_at and revoked_at are deliberately NOT '
    'duplicated here: revocation is enforced inside the isolation boundary, on the row an '
    'operator can see.';

-- One token id has one digest. Without this a re-issue could leave two live digests for one
-- token — two credentials the operator sees as one row, and revoking it would revoke neither
-- in a way anybody could verify.
CREATE UNIQUE INDEX api_token_lookup_token_idx ON api_token_lookup (token_id);

-- =============================================================================
-- THE UNIQUENESS ERD §0.1 POINT 3 NAMES AND V1 NEVER CREATED
-- =============================================================================
--
-- §0.1 point 3 lists the previously-global uniqueness constraints that must lead with
-- company_id, by name: "project.key, team.name, api_token.name, test_case.automation_key,
-- key_sequence, and company-scoped classification values." Every one of those exists in V1
-- except this one. SchemaConventionTest asserts that company_id LEADS every index that
-- includes it — which is silent about an index that was never created at all, and that is
-- how a named invariant went eight months unimplemented behind a green suite.
--
-- PARTIAL on revoked_at IS NULL, for §93's reason on doc_page: a revoked token holding its
-- name for ever surfaces as a duplicate-key 500 naming a dead row. Rotation is issue-then-
-- revoke or revoke-then-issue, and both must be able to reuse the name that is written into
-- somebody's CI configuration.

CREATE UNIQUE INDEX api_token_name_idx
    ON api_token (company_id, name) WHERE revoked_at IS NULL;

-- The list endpoint's index: a company's tokens, newest first.
CREATE INDEX api_token_company_idx ON api_token (company_id, created_at DESC);
