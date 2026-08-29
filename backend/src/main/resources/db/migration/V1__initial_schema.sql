-- =============================================================================
-- TCMS — the schema, entire
-- Companion to docs/specs/2026-07-30-tcms-erd.md
--
-- THIS IS THE ONLY MIGRATION. The 73 files that followed the first cut of it
-- (V2..V75, July–August 2026) were absorbed back into it on 2026-08-29 and
-- deleted. Forward-only history is what git holds; a chain of 73 ALTERs is a
-- worse description of a schema than the schema is, and every reader of it was
-- reconstructing this file in their head. The collapse was not transcribed by
-- hand: a database was built from the old chain and another from this file, and
-- pg_dump of both compared — schema and seed data identical, byte for byte.
--
-- The consequence is the point. This file only ever runs on an EMPTY database.
-- An instance that ran the old chain cannot adopt it — the version history and
-- V1's checksum both differ — and must be rebuilt rather than repaired.
--
-- Conventions (see ERD §0):
--   * UUID v7 primary keys, generated application-side
--   * company_id on every COMPANY-OWNED table, leading every composite index and
--     every previously-global uniqueness constraint. The instance-level
--     exceptions are listed in ERD §0.1 and enforced by SchemaConventionTest.
--   * TEXT + CHECK instead of native ENUM (enum values cannot be removed)
--   * TIMESTAMPTZ always
--   * deleted_at for soft delete on user-authored content
--   * lock_version for optimistic locking on concurrently-edited entities
--
-- 87 tables, of which 74 are company-owned. A company is a SEPARATE CLIENT
-- (plan §1, §6s), and isolation is enforced in three independent layers: the
-- Hibernate filter (application), the 74 RLS policies near the end of this file
-- (database), and the two-company CI fixture (proof).
--
-- A `Vnn` IN A COMMENT NAMES AN ABSORBED MIGRATION, not a file on disk. Those
-- references are kept because they are how the decision is found: the file is in
-- git history, at any revision before this collapse. Do not add new ones.
--
-- WHAT IS WHERE. Tables first, grouped by module and ordered so a foreign key
-- never points forward. Then the functions and the triggers that call them, then
-- row-level security, then the reference data — the permission catalogue, the
-- eight built-in roles and their grants — which is seeded last because it is the
-- only DML here and reads as its own document.
-- =============================================================================


CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;


-- =============================================================================
-- TENANCY
-- =============================================================================

CREATE TABLE company (
    id                  UUID        PRIMARY KEY,
    slug                TEXT        NOT NULL,
    display_name        TEXT        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','SUSPENDED')),
    logo_storage_key    TEXT,
    brand_primary_color TEXT,
    brand_accent_color  TEXT,
    favicon_emoji       TEXT,
    storage_quota_bytes BIGINT,
    settings            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    lock_version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_company_slug UNIQUE (slug),
    CONSTRAINT ck_company_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);
COMMENT ON TABLE company IS
    'A separate CLIENT organisation — the top-level isolation boundary (plan §6s). Provisioned by the '
    'provision-company operator script, never through an admin UI. NOT under RLS: the Instance Admin '
    'reads it to resolve a slug before any company context exists.';
COMMENT ON COLUMN company.brand_primary_color IS
    'Two brand colours only (§6u). The MUI theme is DERIVED from them with contrast-safe foregrounds '
    'computed and validated at save time — WCAG AA on execution screens is a commitment a '
    'client-chosen accent would otherwise break silently. Status colours are NOT brandable.';
COMMENT ON COLUMN company.slug IS
    'Immutable after provisioning: it appears in every URL (/c/{slug}, /{slug}/login) and shared '
    'link.';
COMMENT ON COLUMN company.status IS
    'SUSPENDED must be HONORED IN CODE: refuse all non-Instance-Admin access, return 503 from '
    'ingestion with a reason, leave data untouched. Default lifecycle policy is suspend, never '
    'delete. There is deliberately no ARCHIVED state.';

-- =============================================================================
-- IDENTITY
-- =============================================================================

-- app_user is INSTANCE-LEVEL and deliberately has no company_id: one account may
-- belong to several companies with independent permissions in each (plan §6s).
-- Email is therefore unique instance-wide, which creates a disclosure channel —
-- the invite flow must never reveal whether an address already exists, and no
-- admin below Instance Admin may see a user's other company memberships.
--
-- User-facing names come from company_membership.display_name, NOT from here.
CREATE TABLE app_user (
    id                 UUID        PRIMARY KEY,
    email              CITEXT      NOT NULL,
    canonical_name     TEXT        NOT NULL,
    password_hash      TEXT,
    status             TEXT        NOT NULL DEFAULT 'INVITED'
                       CHECK (status IN ('ACTIVE','INVITED','DEACTIVATED')),
    locale             TEXT        NOT NULL DEFAULT 'en',
    timezone           TEXT        NOT NULL DEFAULT 'UTC',
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID,
    lock_version       BIGINT      NOT NULL DEFAULT 0,
    permission_version INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_user_email UNIQUE (email)
);
COMMENT ON COLUMN app_user.canonical_name IS
    'For the Instance Admin directory only. Every user-facing rendering site resolves the name '
    'through the ACTIVE company''s membership (§6u).';
COMMENT ON COLUMN app_user.password_hash IS
    'Nullable so SSO-only users are possible in M4 without a schema change. The break-glass Instance '
    'Admin must always have one (plan §6r).';
COMMENT ON COLUMN app_user.permission_version IS
    'The `pv` revocation epoch (plan §6b). Bumped on any change to this user''s effective '
    'authorization — role assignment, team membership, company membership, account status. A session '
    'token carries the value it was issued with; a mismatch on any request means the token predates a '
    'revocation and re-authentication is required. Mandatory, not an optimization: without it a '
    'revoked grant survives until the token expires.';
COMMENT ON COLUMN app_user.status IS
    'Users are never hard-deleted; DEACTIVATED preserves authorship in versions and audit. An '
    'IdP-removed user is deactivated, never deleted.';

CREATE TABLE company_membership (
    company_id   UUID        NOT NULL REFERENCES company(id),
    user_id      UUID        NOT NULL REFERENCES app_user(id),
    display_name TEXT        NOT NULL,
    avatar_url   TEXT,
    status       TEXT        NOT NULL DEFAULT 'INVITED'
                 CHECK (status IN ('INVITED','ACTIVE','REMOVED')),
    invited_by   UUID        REFERENCES app_user(id),
    invited_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at  TIMESTAMPTZ,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id, user_id)
);
COMMENT ON TABLE company_membership IS
    'Membership is a PRECONDITION for every permission, not a source of any (plan §6b). A non-member '
    'resolves to the empty permission set regardless of stale grants, so offboarding is one row '
    'update. The one place the permission model is not a pure union — deliberately.';
COMMENT ON COLUMN company_membership.display_name IS
    'PER-MEMBERSHIP, not global (§6u): a contractor may be "A. Kotrulev" in one client and "Aleks '
    '(Vendor)" in another, and neither client can see or overwrite the other''s.';
COMMENT ON COLUMN company_membership.status IS
    'Accepting an invitation grants Viewer with no project access — the same default as SSO JIT '
    'provisioning, so SSO cannot be a side door to more capability than the invite flow grants.';
CREATE INDEX company_membership_user_idx ON company_membership (user_id);

CREATE TABLE membership_invitation (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    user_id      UUID        NOT NULL REFERENCES app_user(id),
    token_hash   TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID        REFERENCES app_user(id),
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_membership_invitation_token UNIQUE (token_hash)
);
COMMENT ON TABLE membership_invitation IS
    'INSTANCE-LEVEL by decision (ERD §0.1): the accept path runs before authentication, so no company '
    'context can exist when the token is looked up, and CompanyContext is never populated from '
    'request input. Carries no email or name — every human-readable fact stays in app_user and '
    'company_membership. See the V6 header for the full reasoning and its cost.';
COMMENT ON COLUMN membership_invitation.token_hash IS
    'Hex SHA-256 of the secret. The plaintext is returned to the issuer exactly once and never '
    'stored, so a read of this table yields nothing redeemable. Unsalted deliberately: the secret is '
    '32 CSPRNG bytes, so there is no dictionary and a slow KDF would only add latency to an equality '
    'lookup.';
CREATE INDEX membership_invitation_expiry_idx ON membership_invitation (expires_at)
    WHERE consumed_at IS NULL;
CREATE UNIQUE INDEX uq_membership_invitation_outstanding ON membership_invitation (company_id, user_id)
    WHERE consumed_at IS NULL;

CREATE TABLE team (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    parent_team_id UUID        REFERENCES team(id),
    name           TEXT        NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     UUID,
    lock_version   BIGINT      NOT NULL DEFAULT 0
);
COMMENT ON COLUMN team.parent_team_id IS
    'Org-chart structure ONLY. Permissions never cascade through this edge (plan §6b/§6k). Cycle '
    'prevention is enforced in the service layer and by trigger.';
CREATE UNIQUE INDEX uq_team_name ON team (company_id, lower(name));
COMMENT ON INDEX uq_team_name IS
    'Team names are unique per company, compared case-insensitively (V21). Replaces V1''s exact-match '
    'UNIQUE (company_id, name), which was strictly weaker than the check TeamEdit makes — so a race '
    'could store two teams one picker cannot tell apart.';

CREATE TABLE team_membership (
    company_id UUID        NOT NULL REFERENCES company(id),
    team_id    UUID        NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    is_lead    BOOLEAN     NOT NULL DEFAULT false,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);
COMMENT ON COLUMN team_membership.is_lead IS
    'Team Lead (manages membership, default reviewer) — distinct from the Test Lead ROLE (plan §6b).';
CREATE INDEX team_membership_user_idx ON team_membership (company_id, user_id);
COMMENT ON INDEX team_membership_user_idx IS
    'The reverse of the primary key. team_membership_pkey is (team_id, user_id), which answers "who '
    'is in this team" and cannot answer "which teams is this person in" without scanning — and every '
    'arm of the permission model asks the second question: the evaluator''s three team_id IN (SELECT '
    '... WHERE tm.user_id = :userId) subqueries, the org chart''s three UNION ALL team arms, and '
    'TeamMembershipRepository.findByUserId. company_id leads because every lookup is inside one '
    'company and because the RLS policy''s predicate is on that column (ERD §0.1 point 3, '
    'SchemaConventionTest.companyIdLeadsCompositeIndexes). Measured in '
    'docs/plans/2026-08-15-org-chart.md §6.1: it turned a 60,028-row filtered seq scan on the '
    'authorization hot path into a 4-buffer index scan.';

CREATE TABLE permission (
    key         TEXT PRIMARY KEY,
    area        TEXT NOT NULL,
    description TEXT NOT NULL
);
COMMENT ON TABLE permission IS
    'Seeded reference data. Code checks permissions, never role names.';

-- role is INSTANCE-LEVEL and deliberately has no company_id: the eight built-ins
-- are the complete role set — no custom roles, not even clone-and-edit (plan §6b).
-- Enforcement is permission-based throughout (nothing checks a role NAME), so
-- adding a designer later is additive: built_in = false rows plus a company_id
-- column, with no change to the evaluator. When that lands, custom roles MUST be
-- company-owned — a client editing "Test Engineer" must never mutate a row another
-- client reads. The rows themselves, and the grants, are at the end of this file.
CREATE TABLE role (
    id          UUID        PRIMARY KEY,
    scope       TEXT        NOT NULL
                CHECK (scope IN ('SYSTEM','COMPANY','PROJECT')),
    name        TEXT        NOT NULL,
    description TEXT,
    built_in    BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    assignable  BOOLEAN     NOT NULL DEFAULT true,
    CONSTRAINT uq_role_name UNIQUE (name)
);
COMMENT ON TABLE role IS
    'Seeded with seven built-ins: Instance Admin (SYSTEM), Company Admin and Company Member '
    '(COMPANY), Test Lead / Test Engineer / Automation Service / Viewer (PROJECT). Company Member is '
    'the company:read baseline every member holds and is deliberately invisible on the wire — see V15 '
    'and handoff §43. See docs/specs/2026-07-30-tcms-roles-permissions-naming.md, authoritative for '
    'the grants.';
COMMENT ON COLUMN role.assignable IS
    'Whether POST /role-assignments may grant this role to a person or a team. False for three rows: '
    'Company Member (V15 — a consequence of membership, never a decision about a person), Agent (V40) '
    'and Automation Service (V41), both of which are capability ceilings a credential carries rather '
    'than roles a person holds. '
    '  IT ANSWERS ONE QUESTION OF FOUR. It is not "hidden" — Agent and Automation Service are both '
    'assignable = false and both ARE returned by GET /roles, because the token-issue form is the only '
    'place either is chosen. It is not "not issuable" — both are issuable, which is what they exist '
    'for. And SINCE V41 IT IS NOT "not revocable": revoking is refused only for the membership '
    'baseline, whose removal takes away a colleague''s sight of the company, and an Automation Service '
    'grant made before V41 must stay revocable or it can never be cleaned up. See V41 §1 and V40 §2 '
    'before wiring this column into a fifth site.';

CREATE TABLE role_permission (
    role_id        UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_key TEXT NOT NULL REFERENCES permission(key),
    qualifier      TEXT NOT NULL DEFAULT 'ALL',
    PRIMARY KEY (role_id, permission_key),
    CONSTRAINT ck_role_permission_qualifier CHECK (qualifier IN ('ALL','OWN','PLAN_SCOPE'))
);
COMMENT ON COLUMN role_permission.qualifier IS
    'WHICH ROWS this grant of the permission covers. ALL = every row in scope. PLAN_SCOPE = only the '
    'rows of the plan being acted on, never the project-scope row every plan inherits read-only '
    '(V19). OWN = only rows the caller authored (created_by), in any state (V12). '
    '  NEITHER PLAN_SCOPE NOR OWN IS CARRIED BY ANY GRANT AS OF V52, AND THAT IS A DECISION, NOT A '
    'GAP. OWN emptied at V51: CRUD on plans, cases and steps belongs to every non-Viewer contributor '
    'regardless of who created the row (§207). PLAN_SCOPE emptied here: a contributor creates, edits '
    'and deletes, unqualified, so artifact:edit lost the scope qualifier and it was the only key that '
    'carried one (§210). EVERY ROW IN THIS COLUMN IS NOW ''ALL''. '
    '  The values, the PermissionQualifier members, IdentityProjectAccess.canDoOnAuthored and the '
    'plan-scope resolution arm are all kept against the next permission that needs to narrow — '
    'because the qualifier is DATA, and deleting an arm is what makes a future qualified grant fail '
    'OPEN at whichever gate forgot it. Do not read the empty population as a seeding bug; '
    'SeedDataTest.everyQualifierIsAll pins it in both directions. '
    '  Resolved at check time against the row being acted on, because the evaluator cannot see that '
    'row: PermissionEvaluator answers the qualifier, the project-scoped write gate applies it. '
    '  STRONGEST WINS: a caller holding the same permission through two grants (directly and via a '
    'team, or PROJECT and COMPANY scope) gets ALL if any grant is ALL. A Company Admin who is also a '
    'project''s Test Engineer must not be demoted by the weaker grant. '
    '  NOT a permission key: case:delete:own would double the catalogue and still could not express '
    '"in any state" (V3 §3). NOT keyed on the role name: code checks permissions, never role names '
    '(plan §6b), or the first custom role is mis-answered.';

CREATE TABLE project (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    key          TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    description  TEXT,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_project_key UNIQUE (company_id, key),
    CONSTRAINT ck_project_key_format CHECK (key ~ '^[A-Z0-9]{2,10}$')
);
COMMENT ON TABLE project IS
    'A FULL CLIENT PROJECT — an entire product or engagement under test ("Globex Online Banking"), '
    'not a feature area. Feature-level structure lives in the Feature classification dimension and '
    'the suite tree. This granularity is why a project grant is significant and why company '
    'membership deliberately conveys no project visibility (plan §1, §6b).';
COMMENT ON COLUMN project.key IS
    '2-10 uppercase alphanumerics, immutable, unique per company. Suggested from the name at '
    'creation. Used in URLs and labels — NOT inside display keys, which are company-scoped (§6t).';

-- -----------------------------------------------------------------------------
-- Display-key allocation — PER COMPANY (plan §6t)
--
-- Keys are PREFIX-N, numbered from 1 per COMPANY and unique across ALL that
-- company's projects. No zero-padding: key_seq owns ordering, key is display only.
--
--   TC-  test case      TS-  suite        TP-  test plan
--   TR-  test run       DOC- documentation page
--
-- No key for executions, conditions, shared steps, defects (Jira owns those),
-- reviews, artifacts, milestones or configurations — each is reached from its
-- parent or picked from a typeahead. Every extra prefix is another counter row
-- and another ambiguous token in search. No automation prefix: automation_status
-- carries that and is designed to change, so a key encoding it would start lying
-- the day the case is automated.
--
-- WHY COMPANY SCOPE: TC-1 is unambiguous everywhere inside a client, which DELETES
-- design rather than adding it — one form everywhere (no qualified "PROJ / TC-1"
-- variant in reports, search, kanban, notifications or Jira comments), trivial
-- search (a key pattern short-circuits with no project context to resolve), and
-- moving a case between projects is a non-event since a collision is impossible.
--
-- THE ONE COST: a user granted one project can infer the client's total case volume
-- from key numbers, and could probe TC-N for a case in a project they cannot see.
-- Two rules contain it and both are load-bearing: an unreachable key returns NOT
-- FOUND (never FORBIDDEN, identically, so the response is not an oracle), and
-- display keys never appear in API paths — resolution goes through an explicit
-- lookup endpoint, so there is no route to enumerate.
--
-- One row per (company, entity_type) rather than counter columns on company:
-- adding a sixth entity type later is an INSERT, not a table rewrite.
-- -----------------------------------------------------------------------------
CREATE TABLE key_sequence (
    company_id  UUID   NOT NULL REFERENCES company(id),
    entity_type TEXT   NOT NULL
                CHECK (entity_type IN ('CASE','SUITE','PLAN','RUN','DOC_PAGE')),
    prefix      TEXT   NOT NULL,
    next_value  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (company_id, entity_type)
);
COMMENT ON TABLE key_sequence IS
    'Allocate with: UPDATE key_sequence SET next_value = next_value + :n WHERE company_id = :c AND '
    'entity_type = :t RETURNING next_value - :n; NOT MAX(n)+1, which races under concurrent creation '
    'and eventually issues duplicate keys. Because the counter is company-wide, ALL case creation in '
    'a company serialises on one row — microseconds per increment, but bulk import and CI quarantine '
    'auto-create MUST reserve a range in one statement. Importing 5,000 cases one round trip at a '
    'time would be pathological. Gap-free numbering is NOT guaranteed (a rolled-back txn burns its '
    'range) and is not required: the contract is uniqueness and immutability only (plan §6m, §6t).';

CREATE TABLE role_assignment (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    role_id    UUID        NOT NULL REFERENCES role(id),
    user_id    UUID        REFERENCES app_user(id),
    team_id    UUID        REFERENCES team(id) ON DELETE CASCADE,
    project_id UUID        REFERENCES project(id) ON DELETE CASCADE,
    granted_by UUID        REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_role_assignment_subject CHECK ((user_id IS NULL) <> (team_id IS NULL))
);
COMMENT ON TABLE role_assignment IS
    'COMPANY- and PROJECT-scope grants. SYSTEM-scope grants live in instance_role_assignment — see '
    'the comment there for why they are a separate table rather than a NULL company_id here. The two '
    'tables together are the ONLY source of any capability (plan §6b). Effective permissions: '
    ' 0 '
    ' unless the user has an ACTIVE company_membership for project.company_id '
    ' <- gate, not a term '
    ' U '
    ' roles granted directly to the user on the project '
    ' U '
    ' roles granted to any team the user belongs to, on the project '
    ' U '
    ' roles granted to the user or their teams on project.company_id '
    ' (COMPANY scope) '
    ' U '
    ' roles granted to the user at SYSTEM scope '
    '                   <- instance_role_assignmentCached per (company_id, user_id, project_id) — the '
    'company id MUST be in the cache key. Invalidated on any write to role_assignment, '
    'instance_role_assignment, team_membership, role_permission or company_membership, and on a '
    'company switch. No explicit deny in the model.';
COMMENT ON COLUMN role_assignment.company_id IS
    'NOT NULL, like every other company-owned table — no special case for the schema test. project_id '
    'NULL => COMPANY scope, which covers EVERY project in that company, present and future: that is '
    'what makes Company Admin work, and why nothing else should be granted at that scope casually.';
COMMENT ON COLUMN role_assignment.project_id IS
    'Non-null => PROJECT scope. A project grant always names its company via company_id. Company '
    'membership alone conveys NO project visibility: a regular user sees only the projects they hold '
    'a grant on — not the company''s project list, names, or counts.';
CREATE INDEX role_assignment_team_idx ON role_assignment (company_id, team_id, project_id);
CREATE INDEX role_assignment_user_idx ON role_assignment (company_id, user_id, project_id);
CREATE UNIQUE INDEX uq_role_assignment_team ON role_assignment (company_id, role_id, team_id, project_id) NULLS NOT DISTINCT
    WHERE team_id IS NOT NULL;
COMMENT ON INDEX uq_role_assignment_team IS
    'The same, for grants made to a team. See uq_role_assignment_user.';
CREATE UNIQUE INDEX uq_role_assignment_user ON role_assignment (company_id, role_id, user_id, project_id) NULLS NOT DISTINCT
    WHERE user_id IS NOT NULL;
COMMENT ON INDEX uq_role_assignment_user IS
    'One grant of one role to one person at one scope. NULLS NOT DISTINCT because project_id IS NULL '
    'IS the COMPANY scope — without it the company-wide grants would be the only ones not '
    'deduplicated. Partial on user_id because ck_role_assignment_subject makes team rows carry a NULL '
    'here, and a NULL is distinct from every other NULL in a plain unique index.';

CREATE TABLE instance_role_assignment (
    id         UUID        PRIMARY KEY,
    role_id    UUID        NOT NULL REFERENCES role(id),
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    granted_by UUID        REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_instance_role_assignment UNIQUE (user_id, role_id)
);
COMMENT ON TABLE instance_role_assignment IS
    'SYSTEM-scope grants (Instance Admin). A deliberate instance-level table (ERD §0.1 exception '
    'list): no company_id, no RLS. '
    ' WHY A SEPARATE TABLE, and not company_id IS NULL on role_assignment: the RLS policy is '
    '  company_id = current_setting(''app.current_company'', true)::uuid, and NULL = anything is '
    '  never true — so a SYSTEM grant modelled as a NULL company_id is invisible in EVERY company '
    '  context, permanently. Instance Admin then resolves to zero permissions, which reads as an '
    '  authorization bug rather than a schema one, and the tempting repair is BYPASSRLS on the app '
    '  role, destroying isolation for all 56 protected tables. Widening this one policy to allow '
    '  NULLs is not the fix either: a context-less query would then return rows, breaking the '
    '  "unset GUC yields zero rows" invariant (ERD §0.1). Measured, not reasoned — see '
    '  docs/audits/2026-07-30-v1-schema-first-execution.md. '
    ' The split also removes the last nullable company_id under RLS, so the schema test rule '
    '  "company_id NOT NULL on every non-exception table" holds with no special case.';
COMMENT ON COLUMN instance_role_assignment.user_id IS
    'Users only — deliberately no team_id. A team is company-owned, so granting instance-wide power '
    'to a company-scoped team would let a client-owned entity confer operator rights: an escalation '
    'path, and one whose membership the client controls. Instance Admin is granted per person by the '
    'operator (plan §6s).';
CREATE INDEX instance_role_assignment_user_idx ON instance_role_assignment (user_id);

CREATE TABLE api_token (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    name         TEXT        NOT NULL,
    owner_id     UUID        NOT NULL REFERENCES app_user(id),
    role_id      UUID        NOT NULL REFERENCES role(id),
    project_id   UUID        REFERENCES project(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    token_prefix TEXT        NOT NULL
);
COMMENT ON COLUMN api_token.company_id IS
    'Bound at issue time. The request path must match this company or the call is rejected.';
COMMENT ON COLUMN api_token.role_id IS
    'A token carries a ROLE, never the full rights of the issuing user (plan §6b). A CI token holds '
    'Automation Service and nothing more: case:read, run:create, result:submit, evidence:attach, and '
    'case:create restricted to the quarantine folder. Deliberately the smallest role in the product — '
    'a leaked token can post results and add quarantine stubs, and cannot edit or delete a real case, '
    'close a run, read reports, or reach another company.';
COMMENT ON COLUMN api_token.token_prefix IS
    'The leading characters of the secret, for recognition in a list. NOT a credential: it is a '
    'deliberate partial disclosure of a 256-bit secret, and the digest lives in api_token_lookup.';
CREATE INDEX api_token_company_idx ON api_token (company_id, created_at DESC);
CREATE UNIQUE INDEX api_token_name_idx ON api_token (company_id, name) WHERE (revoked_at IS NULL);

CREATE TABLE api_token_lookup (
    token_hash TEXT PRIMARY KEY,
    token_id   UUID NOT NULL REFERENCES api_token(id) ON DELETE CASCADE,
    company_id UUID NOT NULL REFERENCES company(id)
);
COMMENT ON TABLE api_token_lookup IS
    'Instance-level, NO RLS (ERD §0.1 exception list, decided here). Answers exactly one question — '
    'which company does this digest belong to — because it is asked before any company context can '
    'exist. Holds no name, owner, role or project: everything else is read from the RLS-protected '
    'api_token row once the context is open.';
COMMENT ON COLUMN api_token_lookup.token_id IS
    'The policed row carrying the lifecycle. expires_at and revoked_at are deliberately NOT '
    'duplicated here: revocation is enforced inside the isolation boundary, on the row an operator '
    'can see.';
CREATE UNIQUE INDEX api_token_lookup_token_idx ON api_token_lookup (token_id);

CREATE TABLE identity_provider (
    id                       UUID        PRIMARY KEY,
    company_id               UUID        NOT NULL REFERENCES company(id),
    key                      TEXT        NOT NULL,
    kind                     TEXT        NOT NULL,
    display_name             TEXT        NOT NULL,
    issuer_uri               TEXT        NOT NULL,
    client_id                TEXT,
    client_secret_encrypted  BYTEA,
    jit_provisioning         BOOLEAN     NOT NULL DEFAULT false,
    auto_link_verified_email BOOLEAN     NOT NULL DEFAULT false,
    group_claim              TEXT,
    enabled                  BOOLEAN     NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               UUID        REFERENCES app_user(id),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by               UUID        REFERENCES app_user(id),
    lock_version             BIGINT      NOT NULL DEFAULT 0,
    bind_dn                  TEXT,
    bind_password_encrypted  BYTEA,
    start_tls                BOOLEAN     NOT NULL DEFAULT false,
    user_search_base         TEXT,
    user_search_filter       TEXT,
    user_id_attribute        TEXT,
    email_attribute          TEXT,
    display_name_attribute   TEXT,
    CONSTRAINT uq_identity_provider_key UNIQUE (company_id, key),
    CONSTRAINT ck_identity_provider_kind CHECK (kind IN ('OIDC','LDAP')),
    CONSTRAINT ck_identity_provider_shape CHECK ((kind = 'OIDC' AND client_id IS NOT NULL AND user_search_base IS NULL AND user_search_filter IS NULL AND user_id_attribute IS NULL)
        OR (kind = 'LDAP' AND client_id IS NULL AND user_search_base IS NOT NULL AND user_search_filter IS NOT NULL AND user_id_attribute IS NOT NULL))
);
COMMENT ON TABLE identity_provider IS
    'One external issuer a company authenticates its own people against (plan §6r). Company-owned and '
    'FORCE RLS-protected: issuer trust is COMPANY-LOCAL, so a different client''s IdP asserting a '
    'subject can never resolve to this company''s user. Provider RESOLUTION before a session exists '
    'goes through tcms_resolve_identity_provider, not through this table directly.';
COMMENT ON COLUMN identity_provider.bind_password_encrypted IS
    'ENCRYPTED, not hashed, for client_secret_encrypted''s reason: a bind PRESENTS it. Never in a '
    'response, log, audit event or error message. Null means an anonymous search, which some '
    'directories permit and Active Directory does not.';
COMMENT ON COLUMN identity_provider.client_secret_encrypted IS
    'ENCRYPTED, not hashed -- unlike every other credential in this schema. A token exchange needs '
    'the plaintext back, so InvitationTokens''/api_token_lookup''s SHA-256 digest is the wrong tool '
    'here and reusing it would make the secret unusable. Never in a response, log, audit event or '
    'error message (§6r). Nullable: a public client uses PKCE alone.';
COMMENT ON COLUMN identity_provider.enabled IS
    'Defaults FALSE so a half-configured provider is never a live sign-in route. Read AFTER the '
    'company context is open, so a disabled provider refuses on the same code path as every other '
    'refusal and is indistinguishable from them (§6r).';
COMMENT ON COLUMN identity_provider.issuer_uri IS
    'Where the authority lives, and what it means follows `kind`. OIDC: the issuer, always https, '
    'because the discovery document -- and therefore the signing-key URL every assertion is checked '
    'against -- is read from it. LDAP: the directory URL, ldaps:// or ldap:// with start_tls -- a '
    'plain bind puts the PERSON''S OWN directory password on the wire, which is a larger exposure than '
    'the https gate covers, and there is deliberately no flag that permits it.';
COMMENT ON COLUMN identity_provider.jit_provisioning IS
    'Defaults FALSE (#38): an IdP authoritative for one organisation is not authoritative for who may '
    'enter a client''s company. First login with no invitation is refused unless this is on.';
COMMENT ON COLUMN identity_provider.user_id_attribute IS
    'The directory''s immutable identifier -- objectGUID on AD, entryUUID on OpenLDAP -- and it is '
    'what lands in user_identity.subject. NEVER a DN: a DN encodes tree position, so moving somebody '
    'between OUs would orphan their account, their memberships and their authorship.';
COMMENT ON COLUMN identity_provider.user_search_filter IS
    'Must contain {0}, which is where the typed address is substituted WITH RFC 4515 ESCAPING '
    'APPLIED. Concatenating it instead is LDAP filter injection on an unauthenticated surface, whose '
    'best case is matching the wrong person. IdentityProviderService refuses a filter without the '
    'placeholder.';
CREATE INDEX identity_provider_company_enabled_idx ON identity_provider (company_id, enabled);

CREATE TABLE company_email_domain (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    domain     CITEXT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_email_domain UNIQUE (company_id, domain)
);
COMMENT ON TABLE company_email_domain IS
    'Which companies claim an email domain, for provider discovery (§6r). Deliberately NOT unique '
    'instance-wide -- several companies may claim one domain. Discovery must answer IDENTICALLY '
    'whether the domain maps to one company, several, or none, or login becomes an oracle for which '
    'companies exist on the instance.';
CREATE INDEX company_email_domain_domain_idx ON company_email_domain (domain);

CREATE TABLE user_identity (
    id            UUID        PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    user_id       UUID        NOT NULL REFERENCES app_user(id),
    provider_id   UUID        NOT NULL REFERENCES identity_provider(id),
    subject       TEXT        NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT uq_user_identity_subject UNIQUE (provider_id, subject)
);
COMMENT ON TABLE user_identity IS
    'One external identity bound to one TCMS user. The key is (provider, subject) and NEVER the '
    'email: emails change and get reassigned, and the IdP `sub` is the only stable handle (§6r). An '
    'already-linked subject resolves without email being consulted at all -- otherwise a user whose '
    'corporate address changed loses their account and their authorship.';
COMMENT ON COLUMN user_identity.email_at_link IS
    'What the IdP said at link time, kept as a record. NEVER read back as a key.';
CREATE INDEX user_identity_company_user_idx ON user_identity (company_id, user_id);

CREATE TABLE sso_login_attempt (
    id            UUID        PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    provider_id   UUID        NOT NULL REFERENCES identity_provider(id),
    state_hash    TEXT        NOT NULL,
    code_verifier TEXT        NOT NULL,
    nonce         TEXT        NOT NULL,
    redirect_uri  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT uq_sso_login_attempt_state UNIQUE (state_hash)
);
COMMENT ON TABLE sso_login_attempt IS
    'Instance-level, NO RLS (ERD §0.1 exception list, decided here). Answers exactly one question -- '
    'which in-flight authorization request is this callback -- because it is asked before any company '
    'context can exist. company_id is a TARGET, the company the attempt is against, not a tenancy '
    'discriminator. Holds NOTHING identifying: no email, no subject, no user id, because at insert '
    'time none of them are known yet. Rows are single-use and short-lived, so the table is empty in '
    'steady state.';
COMMENT ON COLUMN sso_login_attempt.code_verifier IS
    'The PKCE verifier, held server-side. The rejected alternative was a signed cookie, which puts a '
    'confidential client''s PKCE secret in the browser.';
COMMENT ON COLUMN sso_login_attempt.consumed_at IS
    'Single-use. Stamped BEFORE the token exchange, not after: stamping it after leaves a window in '
    'which two concurrent callbacks carrying the same `state` both pass the check.';
CREATE INDEX sso_login_attempt_expires_idx ON sso_login_attempt (expires_at);

CREATE TABLE user_social_identity (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES app_user(id),
    provider      TEXT        NOT NULL,
    subject       TEXT        NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    CONSTRAINT uq_user_social_identity_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_social_identity_user UNIQUE (provider, user_id),
    CONSTRAINT ck_user_social_identity_provider CHECK (provider IN ('GITHUB','GITLAB','GOOGLE'))
);
COMMENT ON TABLE user_social_identity IS
    'Instance-level (ERD §0.1): a person''s social identity spans their companies. The key is '
    '(provider, subject), NEVER email — §6r''s rule, same reason as user_identity.';
COMMENT ON COLUMN user_social_identity.email_at_link IS
    'What the provider asserted when the link was made. Provenance only; never read as a key.';
CREATE INDEX user_social_identity_user_idx ON user_social_identity (user_id);

CREATE TABLE social_login_attempt (
    id            UUID        PRIMARY KEY,
    provider      TEXT        NOT NULL,
    intent        TEXT        NOT NULL,
    user_id       UUID        REFERENCES app_user(id),
    state_hash    TEXT        NOT NULL,
    code_verifier TEXT        NOT NULL,
    nonce         TEXT        NOT NULL,
    redirect_uri  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT uq_social_login_attempt_state UNIQUE (state_hash),
    CONSTRAINT ck_social_login_attempt_intent CHECK (intent IN ('LOGIN','LINK')),
    CONSTRAINT ck_social_login_attempt_intent_shape CHECK ((intent = 'LINK') = (user_id IS NOT NULL)),
    CONSTRAINT ck_social_login_attempt_provider CHECK (provider IN ('GITHUB','GITLAB','GOOGLE'))
);
COMMENT ON TABLE social_login_attempt IS
    'Instance-level (ERD §0.1): consulted before any session exists, like sso_login_attempt. '
    'state_hash is the single-use binding; consumed_at is the anti-replay claim.';

-- =============================================================================
-- PROJECT CONFIGURATION & CLASSIFICATION
-- =============================================================================

CREATE TABLE project_settings (
    project_id                    UUID        PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    company_id                    UUID        NOT NULL REFERENCES company(id),
    review_enabled_case           BOOLEAN     NOT NULL DEFAULT false,
    review_enabled_suite          BOOLEAN     NOT NULL DEFAULT false,
    review_enabled_plan           BOOLEAN     NOT NULL DEFAULT false,
    required_peer_reviews         INTEGER     NOT NULL DEFAULT 0,
    author_may_approve_own_work   BOOLEAN     NOT NULL DEFAULT false,
    edit_after_approval           TEXT        NOT NULL DEFAULT 'RETAIN',
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                    UUID,
    lock_version                  BIGINT      NOT NULL DEFAULT 0,
    require_approved_case_for_run BOOLEAN     NOT NULL DEFAULT false,
    review_enabled_doc            BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT ck_project_settings_edit_after_approval CHECK (edit_after_approval IN ('RETAIN','RE_REVIEW')),
    CONSTRAINT ck_project_settings_required_peer_reviews CHECK (required_peer_reviews >= 0 AND required_peer_reviews <= 20)
);
COMMENT ON TABLE project_settings IS
    'Per-project policy, one row per project (V23). Created with the project and backfilled here for '
    'existing ones; a missing row is nonetheless read as the defaults, never as an error.';
COMMENT ON COLUMN project_settings.author_may_approve_own_work IS
    'FALSE means §6g''s author-cannot-approve-own-work rule is ON — the default, and the setting §6g '
    'calls the single most valuable one here. Stored in the positive so no consumer has to read a '
    'double negative. "Author" is who SUBMITTED the review, not who created the artifact: the '
    'submitter is the person putting it forward for sign-off.';
COMMENT ON COLUMN project_settings.edit_after_approval IS
    'Plan §6g: what an edit to an APPROVED artifact does. RETAIN (default) leaves the approval in '
    'place and marks it stale — ReviewPanel already renders that. RE_REVIEW returns the artifact to '
    'DRAFT so approval must be re-earned. The demotion is NOT gated on *:approve: it is a consequence '
    'the project configured, not an act the editor requested — see LifecycleLadder.';
COMMENT ON COLUMN project_settings.require_approved_case_for_run IS
    'Plan §6g''s gating rule for cases, opt-in and OFF by default. When true, creating a run whose '
    'resolved case set contains any case that is not lifecycle_status = ''APPROVED'' is refused with '
    '409 naming the offending keys — whether the set came from an explicit caseIds list or from the '
    'expansion of a plan''s scope, because otherwise the plan is the bypass. Deliberately INDEPENDENT '
    'of review_enabled_case: §6g''s opt-in suppresses the reviewer apparatus, not the status ladder, '
    'and a project can want approved-only runs without peer review. Overridable per request by a '
    'holder of case:approve, who could reach the same state by approving first.';
COMMENT ON COLUMN project_settings.required_peer_reviews IS
    'Plan §6g''s "configurable minimum peer reviews before approval is offered (default 0)". COPIED '
    'ONTO EACH REVIEW at submission (review.required_peer_reviews) and read from there, so raising '
    'the bar does not move the goalposts under a review already in flight.';
COMMENT ON COLUMN project_settings.review_enabled_case IS
    'Plan §6g: review is opt-in PER PROJECT AND PER ARTIFACT TYPE, off by default. When false the '
    'reviewer apparatus does not exist for this artifact type — no review row is opened, no peer '
    'verdict can be recorded, and the client renders no panel. The STATUS LADDER is unaffected: '
    'turning review off must not retire the four statuses a project has been using since M1.';
COMMENT ON COLUMN project_settings.review_enabled_doc IS
    'The §6g opt-in for documentation pages (V71, plan 005 S9b). Off by default; when true a DOC '
    'review can be opened and doc:review verdicts recorded. Turning it off does not close open DOC '
    'reviews — the same rule as the other three.';
CREATE INDEX ix_project_settings_company ON project_settings (company_id, project_id);

CREATE TABLE classification_dimension (
    id           UUID    PRIMARY KEY,
    company_id   UUID    NOT NULL REFERENCES company(id),
    project_id   UUID    REFERENCES project(id) ON DELETE CASCADE,
    key          TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    cardinality  TEXT    NOT NULL
                 CHECK (cardinality IN ('SINGLE','MULTI')),
    controlled   BOOLEAN NOT NULL DEFAULT true,
    hierarchical BOOLEAN NOT NULL DEFAULT false,
    locked       BOOLEAN NOT NULL DEFAULT false,
    position     INTEGER NOT NULL DEFAULT 0
);
COMMENT ON COLUMN classification_dimension.project_id IS
    'NULL = COMPANY-level dimension, shared across that company''s projects. Test Type / Level / Phase '
    'are seeded company-level and locked so reporting is comparable across a client''s projects; '
    'Feature is per-project (plan §6d). Company-level rather than instance-level is the multi-company '
    'correction: each client gets its own COPY of the seeded vocabulary, because one client renaming '
    '"Compatibility" must not rename it for another. Cross-company reporting is a non-goal, so '
    'nothing is lost.';
CREATE UNIQUE INDEX classification_dimension_key_idx ON classification_dimension (company_id, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid), key);

CREATE TABLE classification_value (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    dimension_id UUID        NOT NULL REFERENCES classification_dimension(id) ON DELETE CASCADE,
    parent_id    UUID        REFERENCES classification_value(id),
    path         LTREE       NOT NULL,
    name         TEXT        NOT NULL,
    color        TEXT,
    position     INTEGER     NOT NULL DEFAULT 0,
    deleted_at   TIMESTAMPTZ
);
COMMENT ON COLUMN classification_value.path IS
    'Materialized path. Selecting a feature must include descendants by default (plan §6q).';
CREATE INDEX classification_value_path_idx ON classification_value USING gist (path);
CREATE UNIQUE INDEX classification_value_sibling_name_idx ON classification_value (company_id, dimension_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name))
    WHERE deleted_at IS NULL;
COMMENT ON INDEX classification_value_sibling_name_idx IS
    'One live value per (dimension, parent, case-insensitive name). Partial because retiring is a '
    'soft delete and a retired name must not block a new value; per-parent because a hierarchical '
    'dimension legitimately repeats a leaf name under different parents (handoff §68).';

CREATE TABLE case_status (
    id             UUID    PRIMARY KEY,
    company_id     UUID    NOT NULL REFERENCES company(id),
    project_id     UUID    REFERENCES project(id) ON DELETE CASCADE,
    name           TEXT    NOT NULL,
    kind           TEXT    NOT NULL
                   CHECK (kind IN ('PASSED','FAILED','BLOCKED','SKIPPED','UNTESTED','RETEST','WIP')),
    color          TEXT,
    is_final       BOOLEAN NOT NULL DEFAULT true,
    counts_as_pass BOOLEAN NOT NULL DEFAULT false,
    position       INTEGER NOT NULL DEFAULT 0
);
COMMENT ON TABLE case_status IS
    'PROJECT-scoped execution statuses, seeded with the six defaults per project. Statuses and the '
    'condition library are project-scoped while Test Type / Level / Phase are company-scoped (plan '
    '§6d, §6f): two client products legitimately differ in login flow and status vocabulary, while '
    '"what counts as a Performance test" should not.';
COMMENT ON COLUMN case_status.kind IS
    'Lets reporting aggregate project-custom statuses without knowing their names.';

-- =============================================================================
-- TEST REPOSITORY
-- =============================================================================

CREATE TABLE suite (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key                     TEXT        NOT NULL,
    key_seq                 BIGINT      NOT NULL,
    name                    TEXT        NOT NULL,
    description             TEXT,
    position                INTEGER     NOT NULL DEFAULT 0,
    lifecycle_status        TEXT        NOT NULL DEFAULT 'DRAFT'
                            CHECK (lifecycle_status IN ('DRAFT','IN_REVIEW','APPROVED','DEPRECATED')),
    current_version_no      INTEGER     NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    CONSTRAINT uq_suite_key UNIQUE (company_id, key)
);
COMMENT ON TABLE suite IS
    'A named collection of test cases. FLAT since V33 (§98): membership is three arms — filed '
    '(test_case.suite_id), added (suite_case), tagged (suite_tag_rule) — unioned and deduped. There '
    'is no parent and no path; execution_fact.suite_path is frozen history and describes the tree '
    'that existed when a case ran.';
COMMENT ON COLUMN suite.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS suite instead of '
    'creating a second one. Opaque, client-supplied, write-once, and never read as data. NULL for '
    'every suite created before V42 and for any caller that sends no key.';
CREATE UNIQUE INDEX suite_created_idempotency_key_idx ON suite (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX suite_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query is keyed on exactly these '
    'three columns, so a race between two retries of one intent fails on the constraint rather than '
    'inserting twice. Scoped to the project because the endpoint is.';
CREATE INDEX suite_key_seq_idx ON suite (company_id, key_seq);
CREATE INDEX suite_project_idx ON suite (company_id, project_id) WHERE (deleted_at IS NULL);

CREATE TABLE shared_step (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title                   TEXT        NOT NULL,
    body                    JSONB       NOT NULL,
    usage_count             INTEGER     NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    -- title A + the JSONB body B. The '["string"]' filter makes jsonb_to_tsvector
    -- index string leaves only — without it the JSON keys ("action", "expected")
    -- become search terms and match everything.
    search_vector           TSVECTOR GENERATED ALWAYS AS (
                                            setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
                                            || setweight(jsonb_to_tsvector('english'::regconfig, body, '["string"]'::jsonb), 'B')
                                        ) STORED,
    created_idempotency_key TEXT
);
COMMENT ON TABLE shared_step IS
    'RETIRED §239 (audit C4). No writer, no reader, and unreachable from Java — the entity, the '
    'repository, the service and the controller were deleted, and GET/POST /shared-steps left the '
    'contract. Kept rather than dropped because it holds authored content: the rows are the entries '
    'somebody wrote. The DROP is owed to a later slice, deliberately not appended here. Why the '
    'feature went: nothing ever resolved shared_step.body outside this table''s own list endpoint, so '
    'a case referencing an entry showed the entry TITLE that the picker had copied into '
    'test_step.action, and editing the entry propagated to nothing while the UI said it propagated to '
    'every case. Step reuse is GET /step-suggestions, derived from the project''s own cases.';
COMMENT ON COLUMN shared_step.created_idempotency_key IS
    'As artifact.created_idempotency_key. A shared step is a reference target '
    '(test_step.shared_step_id), so two copies of one entry split which cases point at which — a '
    'visible twin in the library list is not the whole cost, and the caller this defends has no list '
    'in front of it. NULL for every entry created before V59 and for any caller that sends no key.';
CREATE UNIQUE INDEX shared_step_created_idempotency_key_idx ON shared_step (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX shared_step_created_idempotency_key_idx IS
    'As artifact_created_idempotency_key_idx, for the library create.';
CREATE INDEX shared_step_search_idx ON shared_step USING gin (search_vector);
CREATE INDEX shared_step_title_trgm_idx ON shared_step USING gin (title gin_trgm_ops);

CREATE TABLE condition (
    id            UUID        PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    project_id    UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title         TEXT        NOT NULL,
    type          TEXT        NOT NULL
                  CHECK (type IN ('PRE','POST','BOTH')),
    body          JSONB       NOT NULL,
    parameters    JSONB,
    usage_count   INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID,
    deleted_at    TIMESTAMPTZ,
    lock_version  BIGINT      NOT NULL DEFAULT 0,
    -- title A + the JSONB body B. The '["string"]' filter makes jsonb_to_tsvector
    -- index string leaves only — without it the JSON keys ("action", "expected")
    -- become search terms and match everything.
    search_vector TSVECTOR GENERATED ALWAYS AS (
                                  setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
                                  || setweight(jsonb_to_tsvector('english'::regconfig, body, '["string"]'::jsonb), 'B')
                              ) STORED,
    imported_key  TEXT
);
COMMENT ON COLUMN condition.imported_key IS
    '"bg:" plus the first 16 hex of SHA-256 over one normalised Background step (keyword + text + '
    'block lines, whitespace collapsed, \n line endings). A TEXT HASH, NOT A POSITIONAL KEY, and that '
    'is the whole design: a reordered or partly rewritten Background re-mints nothing, and the same '
    'step text in two feature files is ONE library entry for the project. Non-null also means "the '
    'import owns this entry" — a re-import may replace the PRE attachments whose condition has one, '
    'and may never touch a human-attached PRE or any POST.';
COMMENT ON COLUMN condition.search_vector IS
    'title A + body B. The ''["string"]'' filter makes jsonb_to_tsvector index string leaves only — '
    'without it the JSON keys ("action", "expected") become search terms and match everything.';
COMMENT ON COLUMN condition.usage_count IS
    'Denormalized. Drives typeahead ranking (plan §6h) and the pre-edit impact warning (§6f).';
CREATE UNIQUE INDEX condition_imported_key_idx ON condition (company_id, project_id, imported_key)
    WHERE (imported_key IS NOT NULL) AND (deleted_at IS NULL);
COMMENT ON INDEX condition_imported_key_idx IS
    'One point lookup per distinct Background step per import request: "is this step already in the '
    'library". Also the uniqueness that makes a concurrent APPLY lose loudly (409 '
    'duplicate-imported-key, whole request rolled back) instead of quietly minting a second copy of '
    'one condition.';
CREATE INDEX condition_project_idx ON condition (company_id, project_id) WHERE (deleted_at IS NULL);
COMMENT ON INDEX condition_project_idx IS
    'The library read: one project''s live conditions. Mirrors suite_project_idx — same predicate, '
    'same partiality, same reason. The GIN indexes on this table serve the typeahead corpus, not the '
    'project scope, and a GIN index cannot answer an equality seek on company_id.';
CREATE INDEX condition_search_idx ON condition USING gin (search_vector);
CREATE INDEX condition_title_trgm_idx ON condition USING gin (title gin_trgm_ops);

CREATE TABLE test_case (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    suite_id                UUID        REFERENCES suite(id),
    key                     TEXT        NOT NULL,
    key_seq                 BIGINT      NOT NULL,
    title                   TEXT        NOT NULL,
    description             TEXT,
    objective               TEXT,
    step_format             TEXT        NOT NULL DEFAULT 'STRUCTURED'
                            CHECK (step_format IN ('STRUCTURED','GHERKIN')),
    gherkin_source          TEXT,
    expected_outcome        TEXT,
    priority                TEXT        NOT NULL DEFAULT 'MEDIUM'
                            CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    severity                TEXT
                            CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    estimated_duration_sec  INTEGER,
    owner_id                UUID        REFERENCES app_user(id),
    lifecycle_status        TEXT        NOT NULL DEFAULT 'DRAFT'
                            CHECK (lifecycle_status IN ('DRAFT','IN_REVIEW','APPROVED','DEPRECATED')),
    automation_status       TEXT        NOT NULL DEFAULT 'MANUAL'
                            CHECK (automation_status IN ('MANUAL','TO_BE_AUTOMATED','AUTOMATED','NOT_AUTOMATABLE')),
    automation_key          TEXT,
    not_automatable_reason  TEXT,
    current_version_no      INTEGER     NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    -- Generated rather than trigger-maintained, because every searchable field is
    -- same-row: there is no INSERT path that can skip it and no trigger to drop.
    -- Step text is deliberately absent — §6q searches the case's own name and
    -- description, and steps live in test_step, which a generation expression may
    -- not read. The display key is served by the trigram index, not by the vector.
    search_vector           TSVECTOR GENERATED ALWAYS AS (
                                            setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
                                            || setweight(to_tsvector('english'::regconfig,
                                                         coalesce(description, '') || ' ' || coalesce(objective, '') || ' '
                                                         || coalesce(expected_outcome, '') || ' ' || coalesce(gherkin_source, '')), 'B')
                                        ) STORED,
    created_idempotency_key TEXT,
    imported_source         TEXT,
    imported_path           TEXT,
    imported_line           INTEGER,
    CONSTRAINT uq_test_case_key UNIQUE (company_id, key),
    CONSTRAINT ck_not_automatable_reason CHECK (automation_status <> 'NOT_AUTOMATABLE'
        OR not_automatable_reason IS NOT NULL)
);
COMMENT ON COLUMN test_case.automation_key IS
    'Single-valued match target for CI ingestion, unique per COMPANY (plan §6c) — so a CI job posts '
    'results without knowing which project owns the case, and a case moved between projects does not '
    'change where its key resolves. Selection tags live in case_automation_tag.';
COMMENT ON COLUMN test_case.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS case instead of creating '
    'a second one. Opaque, client-supplied, write-once, and never read as data. NULL for every case '
    'created before V11 and for any caller that sends no key.';
COMMENT ON COLUMN test_case.imported_line IS
    '1-based line of the Scenario: keyword in imported_path, so a reader can answer "why does this '
    'case say that" by opening the file at the right place. Stale the moment the file is edited, '
    'which is why it is recorded beside imported_source rather than trusted on its own.';
COMMENT ON COLUMN test_case.imported_path IS
    'The path the caller supplied for the file this case came from. PROVENANCE, NOT A LOCATOR: the '
    'file lives in somebody''s automation repository and this instance never held it, so nothing '
    'resolves this to content. Bounded at 1024 chars in Java rather than here — a longer path is a '
    'caller mistake worth a field error, not a truncation nobody sees.';
COMMENT ON COLUMN test_case.imported_source IS
    'The scenario''s text at last import, verbatim from its Scenario: line to its last step. NOT '
    'provenance decoration — it is READ: a re-import compares the file''s scenario against it and '
    'answers SKIP "unchanged since import", so an unchanged folder re-imported mints no version and '
    'writes nothing. Without it every re-import rewrites every case and the version history fills '
    'with entries whose diff is empty.';
COMMENT ON COLUMN test_case.key IS
    'TC-1 — sequential per COMPANY (unique across all its projects), immutable, never reused, never '
    'renumbered, and immutable across a project move too (a collision is impossible since the '
    'namespace is the company). It ends up in commit messages and Jira tickets, so it must outlive '
    'everything (plan §6m, §6t). Display only: never an API path identifier.';
COMMENT ON COLUMN test_case.key_seq IS
    'The numeric part, stored separately BECAUSE KEYS DO NOT SORT LEXICOGRAPHICALLY: "TC-10000" sorts '
    'before "TC-9999" as text. Order by key_seq, display key. Same class of bug as '
    'test_run.release_sort_key.';
COMMENT ON COLUMN test_case.search_vector IS
    'Generated, not trigger-maintained: every searchable field is same-row, so there is no INSERT '
    'path that can skip it. title weighted A, description/objective/expected outcome/Gherkin B (plan '
    '§6q). Never compute to_tsvector() at query time — that defeats the GIN index entirely.';
COMMENT ON COLUMN test_case.suite_id IS
    'The folder this case is FILED in, or NULL for an unfiled case (V31). One of the three arms of '
    'suite membership — the other two are suite_case (added individually) and suite_tag_rule (matched '
    'by tag), and a case reachable from three suites carries no row here it did not already have. '
    'Nullable since V31: a case may be created before anybody has decided where it lives. Readers '
    'that group by it must render an explicit "not filed" bucket rather than dropping the row.';
COMMENT ON CONSTRAINT ck_not_automatable_reason ON test_case IS
    'NOT_AUTOMATABLE requires a reason — it is what makes automation coverage honestly denominated.';
CREATE UNIQUE INDEX test_case_automation_key_idx ON test_case (company_id, automation_key)
    WHERE automation_key IS NOT NULL;
CREATE UNIQUE INDEX test_case_created_idempotency_key_idx ON test_case (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX test_case_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query is keyed on exactly these '
    'three columns, so a race between two retries of one intent fails on the constraint rather than '
    'inserting twice. Scoped to the project because the endpoint is — a company-wide constraint with '
    'a project-scoped lookup is a validated request that 500s on insert.';
CREATE INDEX test_case_key_seq_idx ON test_case (company_id, key_seq);
CREATE INDEX test_case_key_trgm_idx ON test_case USING gin (key gin_trgm_ops);
CREATE INDEX test_case_lifecycle_idx ON test_case (company_id, project_id, lifecycle_status)
    WHERE deleted_at IS NULL;
CREATE INDEX test_case_search_idx ON test_case USING gin (search_vector);
CREATE INDEX test_case_suite_idx ON test_case (company_id, suite_id) WHERE (deleted_at IS NULL);
CREATE INDEX test_case_title_trgm_idx ON test_case USING gin (title gin_trgm_ops);

CREATE TABLE suite_case (
    company_id UUID        NOT NULL REFERENCES company(id),
    suite_id   UUID        NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    case_id    UUID        NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by   UUID        REFERENCES app_user(id),
    PRIMARY KEY (company_id, suite_id, case_id)
);
COMMENT ON TABLE suite_case IS
    'A case added to a suite INDIVIDUALLY (V31) — the second arm of suite membership, beside '
    'test_case.suite_id (filed) and suite_tag_rule (matched). A set, not a list: there is no position '
    'column, because a resolved collection is ordered by the case''s own key and not by the order '
    'somebody added things to it.';
COMMENT ON COLUMN suite_case.added_by IS
    'Nullable like every other actor column since §66 — an offboarded account does not un-add a case.';
CREATE INDEX suite_case_case_idx ON suite_case (company_id, case_id);

CREATE TABLE suite_tag_rule (
    company_id UUID NOT NULL REFERENCES company(id),
    suite_id   UUID NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (company_id, suite_id, tag)
);
COMMENT ON TABLE suite_tag_rule IS
    'The tags a suite claims (V31) — the third arm of membership. UNIONED, never intersected: a suite '
    'with rules `smoke` and `payments` holds every case carrying EITHER. Intersection is the other '
    'defensible reading and it is not this one, because a two-tag suite that silently holds nothing '
    'is indistinguishable from an empty project. AND-ing tags is a saved filter on the case grid, '
    'which the URL already expresses.';
COMMENT ON COLUMN suite_tag_rule.tag IS
    'Matched against case_tag.tag exactly — same case, same spelling. NOT case_automation_tag: those '
    'are the selection tags a CI runner filters on (§6c), and merging the two would make "run the '
    'smoke suite" and "cases the release manager flagged" the same query.';
CREATE INDEX suite_tag_rule_tag_idx ON suite_tag_rule (company_id, tag);

CREATE TABLE case_automation_tag (
    company_id UUID NOT NULL REFERENCES company(id),
    case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (case_id, tag)
);
CREATE INDEX case_automation_tag_tag_idx ON case_automation_tag (tag);

CREATE TABLE test_case_draft (
    case_id    UUID        NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    company_id UUID        NOT NULL REFERENCES company(id),
    content    JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id    UUID        NOT NULL,
    PRIMARY KEY (case_id, user_id)
);
COMMENT ON TABLE test_case_draft IS
    'Autosave buffer, ONE ROW PER PERSON PER CASE since V64 (audit D1). NEVER versioned. An explicit '
    'save promotes the saver''s buffer into test_case + a new test_case_version and clears THAT ROW — '
    'this is what keeps history free of keystroke noise (plan §6j). A colleague''s save no longer '
    'clears your draft; it makes it stale, which CaseDraft.stale reports and the editor shows without '
    'refusing to restore.';
COMMENT ON COLUMN test_case_draft.user_id IS
    'Whose buffer this is, and half the primary key — not an audit stamp. Was updated_by, nullable, '
    'when the row was keyed on the case alone (V1 → V63) and the wire had to disclose that the draft '
    'on offer might be somebody else''s.';

CREATE TABLE test_case_version (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    case_id        UUID        NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    version_no     INTEGER     NOT NULL,
    snapshot       JSONB       NOT NULL,
    content_hash   TEXT        NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_case_version UNIQUE (case_id, version_no)
);
COMMENT ON COLUMN test_case_version.content_hash IS
    'A save whose hash is unchanged writes no version.';
COMMENT ON COLUMN test_case_version.pinned IS
    'Set when referenced by a run, an approval, or a generated report. Pinned versions are '
    'permanently exempt from any retention policy.';
COMMENT ON COLUMN test_case_version.snapshot IS
    'FULL snapshot including RESOLVED steps, conditions and classification. Not a delta: '
    'reconstruction is O(1) and corruption stays local (plan §6j).';

CREATE TABLE test_step (
    id              UUID    PRIMARY KEY,
    company_id      UUID    NOT NULL REFERENCES company(id),
    case_id         UUID    NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    keyword         TEXT
                    CHECK (keyword IN ('GIVEN','WHEN','THEN','AND','BUT')),
    action          TEXT    NOT NULL,
    test_data       TEXT,
    expected_result TEXT,
    docstring       TEXT,
    datatable       JSONB,
    shared_step_id  UUID    REFERENCES shared_step(id),
    CONSTRAINT ck_test_step_one_data CHECK (num_nonnulls(test_data, docstring, datatable) <= 1)
);
COMMENT ON COLUMN test_step.datatable IS
    'Gherkin''s |-table argument as a JSON array of arrays of strings — '
    '[["name","qty"],["widget","2"]]. Rows MAY BE RAGGED: nothing here requires a rectangle, the '
    'parser does not produce a non-rectangular table, and a reader that indexes blindly across rows '
    'is the thing that breaks. Not normalised into rows and cells because it is read as a whole, '
    'written as a whole, and never joined against — the same argument V61 makes for '
    'test_run.scope_request.';
COMMENT ON COLUMN test_step.docstring IS
    'Gherkin''s """ block argument — a multi-line string hanging off ONE step. Declared in V1 and '
    'first written in §255, which is also when it first had a contract field (TestStep.docString) to '
    'be served on. Before that it was a column nothing could fill: a .feature file with a doc string '
    'could not round-trip through this product.';
COMMENT ON COLUMN test_step.id IS
    'STABLE. step_result FKs here, so inserting or reordering steps never reassigns evidence (plan '
    '§6m).';
COMMENT ON COLUMN test_step.keyword IS
    'Non-null identifies a Gherkin step. One table serves both formats — splitting would double every '
    'query that does not care about format.';
COMMENT ON COLUMN test_step.shared_step_id IS
    'RETIRED §239 (audit C4). No longer mapped by TestStep and no longer on the contract''s TestStep '
    'or TestStepInput. Left populated and NOT nulled: the reference resolved to nothing on every read '
    'path, so the step''s text is action and always was, and there is no content to migrate out of '
    'here. DROP COLUMN is owed to a later slice, per expand-contract. A case saved after §239 writes '
    'NULL here, because the field left the payload — so this column drains naturally rather than by '
    'an UPDATE.';
COMMENT ON CONSTRAINT ck_test_step_one_data ON test_step IS
    'One step carries structured test data, OR a Gherkin doc string, OR a Gherkin table — never two. '
    'A step holding two leaves the execution screen to choose which one the tester follows, and '
    'whichever it picks is wrong for somebody. THE BACKSTOP, NOT THE ANSWER: CaseEdit rejects the '
    'same payload with a field error on steps naming the step number, because a constraint violation '
    'reaching a client is a 500 where a form error belongs.';
CREATE INDEX test_step_action_trgm_idx ON test_step USING gin (lower(action) gin_trgm_ops);
COMMENT ON INDEX test_step_action_trgm_idx IS
    'Serves the step-suggestion typeahead''s LOWER(action) LIKE ''%…%'' fragment match. Built on the '
    'EXPRESSION lower(action), not on the column: a trigram index on `action` is never consulted by a '
    'predicate over LOWER(action), so the plain spelling indexes nothing this query asks for. See '
    'V30''s header.';
CREATE INDEX test_step_case_idx ON test_step (case_id, "position");

CREATE TABLE case_condition (
    id                 UUID    PRIMARY KEY,
    company_id         UUID    NOT NULL REFERENCES company(id),
    case_id            UUID    NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    kind               TEXT    NOT NULL
                       CHECK (kind IN ('PRE','POST')),
    position           INTEGER NOT NULL,
    condition_id       UUID    REFERENCES condition(id),
    inline_body        JSONB,
    parameter_bindings JSONB,
    CONSTRAINT ck_case_condition_source CHECK ((condition_id IS NULL) <> (inline_body IS NULL))
);
COMMENT ON TABLE case_condition IS
    'Holds a live REFERENCE while authoring (so library edits propagate), and is resolved and '
    'snapshotted into test_case_version.snapshot at version-pin time (plan §6f).';
CREATE INDEX case_condition_case_idx ON case_condition (case_id, kind, "position");
CREATE INDEX case_condition_condition_idx ON case_condition (condition_id);

CREATE TABLE suite_condition (
    id                 UUID    PRIMARY KEY,
    company_id         UUID    NOT NULL REFERENCES company(id),
    suite_id           UUID    NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    kind               TEXT    NOT NULL
                       CHECK (kind IN ('PRE','POST')),
    position           INTEGER NOT NULL,
    condition_id       UUID    REFERENCES condition(id),
    inline_body        JSONB,
    parameter_bindings JSONB,
    CONSTRAINT ck_suite_condition_source CHECK ((condition_id IS NULL) <> (inline_body IS NULL))
);
COMMENT ON TABLE suite_condition IS
    'Standing conditions attached to a suite. FLAT since V33 (§98): there is no walk and no path, '
    'because there is no parent. Inherited by the cases FILED in the suite (test_case.suite_id) and '
    'by those alone — not by the cases added (suite_case) nor by those a tag rule matches '
    '(suite_tag_rule); see CaseConditions.ancestry for why widening it is a decision rather than a '
    'consequence of flattening. Resolution order per kind (plan §6f): these attachments first, then '
    'the case own case_condition rows, deduped by condition_id with the first occurrence winning. A '
    'case_condition_optout row declines ONE attachment for ONE case, which is why the decline is '
    'keyed on suite_condition.id and not on condition_id.';
CREATE INDEX suite_condition_suite_idx ON suite_condition (company_id, suite_id, kind, "position");
COMMENT ON INDEX suite_condition_suite_idx IS
    'Inheritance resolution: the conditions attached to a case''s folder and to every folder above it, '
    'in one seek. Mirrors case_condition_case_idx with company_id leading — the two tables feed one '
    'resolution order (plan §6f) and are read together.';

CREATE TABLE case_condition_optout (
    company_id         UUID NOT NULL REFERENCES company(id),
    case_id            UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    suite_condition_id UUID NOT NULL REFERENCES suite_condition(id) ON DELETE CASCADE,
    PRIMARY KEY (case_id, suite_condition_id)
);
COMMENT ON TABLE case_condition_optout IS
    'ONE case declining ONE inherited attachment (plan §6f). Keyed on suite_condition_id rather than '
    'on the library entry, so declining a rule at one suite leaves an identical rule attached '
    'elsewhere in force. A row lives exactly as long as its attachment hangs off the folder the case '
    'is FILED in: suite_condition.suite_id = test_case.suite_id holds for every row (V50, §192), '
    'because a move is the only act that decides which standing rules govern a case and '
    'CaseCloneService has answered a clone the same way since §129. A retired library entry does NOT '
    'end a row - the attachment still belongs to the folder, retirement is reversible, and no list '
    'shows the row while it lasts. Deleting the attachment does, by ON DELETE CASCADE.';

CREATE TABLE case_classification (
    company_id   UUID NOT NULL REFERENCES company(id),
    case_id      UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    value_id     UUID NOT NULL REFERENCES classification_value(id),
    dimension_id UUID NOT NULL REFERENCES classification_dimension(id),
    PRIMARY KEY (case_id, value_id)
);
COMMENT ON COLUMN case_classification.dimension_id IS
    'Denormalized so SINGLE-cardinality dimensions can be enforced by trigger (the predicate depends '
    'on classification_dimension, so a plain unique index will not do).';
CREATE INDEX case_classification_dim_idx ON case_classification (case_id, dimension_id);
CREATE INDEX case_classification_value_idx ON case_classification (value_id);

CREATE TABLE case_tag (
    company_id UUID NOT NULL REFERENCES company(id),
    case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (case_id, tag)
);
CREATE INDEX case_tag_tag_idx ON case_tag (company_id, tag);

CREATE TABLE attachment (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    owner_type   TEXT        NOT NULL
                 CHECK (owner_type IN ('CASE','EXECUTION','STEP_RESULT','DOC_PAGE','ARTIFACT')),
    owner_id     UUID        NOT NULL,
    storage_key  TEXT        NOT NULL,
    filename     TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    size_bytes   BIGINT      NOT NULL,
    checksum     TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
COMMENT ON TABLE attachment IS
    'Polymorphic parent — the ONE place this schema knowingly gives up referential integrity. Six '
    'near-identical attachment tables is the worse trade.';
CREATE INDEX attachment_owner_idx ON attachment (owner_type, owner_id);

CREATE TABLE ai_proposal (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    suite_id                UUID,
    payload                 JSONB       NOT NULL,
    title                   TEXT        NOT NULL
                            CHECK (length(btrim(title)) > 0),
    verdict                 TEXT        NOT NULL
                            CHECK (verdict IN ('GROUNDED','PARTIAL','HALLUCINATED','UNVERIFIED')),
    verdict_source          TEXT        NOT NULL
                            CHECK (verdict_source IN ('NONE','EXTERNAL')),
    grounding               JSONB,
    status                  TEXT        NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID        NOT NULL REFERENCES app_user(id),
    created_token_id        UUID        REFERENCES api_token(id) ON DELETE SET NULL,
    created_idempotency_key TEXT,
    decided_at              TIMESTAMPTZ,
    decided_by              UUID        REFERENCES app_user(id),
    decision_note           TEXT,
    accepted_case_id        UUID        REFERENCES test_case(id) ON DELETE SET NULL,
    CONSTRAINT ai_proposal_accepted_names_its_case CHECK ((status = 'ACCEPTED' AND accepted_case_id IS NOT NULL)
        OR (status <> 'ACCEPTED' AND accepted_case_id IS NULL)),
    CONSTRAINT ai_proposal_decision_complete CHECK ((status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL AND accepted_case_id IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
COMMENT ON TABLE ai_proposal IS
    'A generated test case awaiting a human decision (plan §6w.6). NOT test_case_draft: that is a '
    'per-case keystroke buffer that is cleared on save, this is a decision record that outlives its '
    'decision. Nothing here reaches test_case except through CaseCreateService — the accept path '
    'reuses the create path so an accepted proposal is indistinguishable from a case authored any '
    'other way (same validation, same TC-n allocation, same v1 version, same audit row), and the '
    'accepting person is its author. No lock_version: a proposal is authored once and decided once, '
    'so there is no form two screens can contend over.';
COMMENT ON COLUMN ai_proposal.accepted_case_id IS
    'The case this became, set by the accept path inside the same transaction that creates it. ON '
    'DELETE SET NULL is a deliberate hole: deleting the case leaves an ACCEPTED proposal pointing at '
    'nothing, which is refused by ai_proposal_accepted_names_its_case on any subsequent write to the '
    'row and is why the accept path never rewrites a decided row.';
COMMENT ON COLUMN ai_proposal.created_by IS
    'The person answerable for the proposal — the token''s OWNER on an agent-authenticated call, never '
    'the token. §6j''s attribution guarantee: "An agent did it" is never an acceptable line; "Ana''s '
    'agent, holding Ana''s grants, did it" is. created_token_id is the other half.';
COMMENT ON COLUMN ai_proposal.created_idempotency_key IS
    'As test_case.created_idempotency_key (V11) — the seventh column in that family, and the first '
    'whose caller has no screen at all. A generation agent that times out mid-batch retries the '
    'batch; without this each retry files a second copy of every proposal it had already filed, and '
    'the reviewer pays for it one duplicate at a time.';
COMMENT ON COLUMN ai_proposal.created_token_id IS
    'Which credential filed it, when one did — audit_event.actor_token_id''s shape (§132), and NULL '
    'for a proposal filed in a browser session. ON DELETE SET NULL: revoking a token must not delete '
    'the proposals it filed, or revocation would silently empty the review queue.';
COMMENT ON COLUMN ai_proposal.grounding IS
    'Per-step findings, including rejections and unverified claims (§6w.6) — and where a claim about '
    'WHICH requirement this verifies travels, because there is no criterion table: ERD resolved '
    'question #18 makes a requirement a doc_block, and a FK from repository into documentation is '
    'refused by ArchUnit besides.';
COMMENT ON COLUMN ai_proposal.payload IS
    'The proposed case in the CaseUpdateRequest field shape (§6m) — the same body POST /cases takes. '
    'Write-once. Held as the request shape rather than as a rendered case so accept can hand it to '
    'the create service unchanged; a second shape here would be a second answer to "what is a case" '
    'and the one that diverges is whichever was edited second.';
COMMENT ON COLUMN ai_proposal.suite_id IS
    'Where the proposer says the case should be filed — a copy of payload->>''suiteId'', so the queue '
    'can group without parsing a document per row. NOT A FOREIGN KEY, and that is the whole design of '
    'this endpoint meeting the schema: a proposal is stored UNVALIDATED, because whether it is a '
    'legal case is a question about the project''s suites at ACCEPT time, which may be a week later. A '
    'FK would answer that question here as well, in the database, as a 500 rather than a field error '
    '— so an agent inventing a suite id would lose its whole batch instead of landing a proposal a '
    'reviewer can look at and reject. Accept resolves it through CaseEdit like every other create, '
    'and a dangling id becomes a field error on the suite picker.';
COMMENT ON COLUMN ai_proposal.verdict IS
    'What the proposer claims about grounding: GROUNDED | PARTIAL | HALLUCINATED | UNVERIFIED. '
    'UNVERIFIED is a first-class verdict and the honest default, not an error state (§6w.6) — it is '
    'what every proposal carries on rungs 0 and 1, and the review UI renders it as "not independently '
    'checked".';
COMMENT ON COLUMN ai_proposal.verdict_source IS
    'WHO checked, and therefore what the verdict is worth (§6w.1a). NONE = nobody but the reviewer '
    'will have. EXTERNAL = a connecting agent asserted it — recorded, shown, attributed to the token '
    'that said so, and NEVER counted as verified, because TCMS cannot see that agent''s critic, '
    'isolation or prompt. DERIVED BY THE SERVER from whether a verdict was asserted; there is no '
    'request field for it. TCMS_VERIFIED is deliberately absent from the CHECK until the isolated '
    'critic exists to write it (V39''s rule, #50): a value with no writer is a claim any caller could '
    'mint.';
CREATE UNIQUE INDEX ai_proposal_created_idempotency_key_idx ON ai_proposal (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
CREATE INDEX ai_proposal_pending_idx ON ai_proposal (company_id, project_id, created_at DESC)
    WHERE status = 'PENDING';
COMMENT ON INDEX ai_proposal_pending_idx IS
    'The review queue. Partial on PENDING because that is the only status anybody browses — a decided '
    'proposal is read by id from the case it became, or counted in aggregate.';

-- =============================================================================
-- EXECUTION
-- =============================================================================

CREATE TABLE test_plan (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key                     TEXT        NOT NULL,
    key_seq                 BIGINT      NOT NULL,
    name                    TEXT        NOT NULL,
    description             TEXT,
    status                  TEXT        NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED')),
    milestone_name          TEXT,
    milestone_date          DATE,
    entry_criteria          JSONB,
    exit_criteria           JSONB,
    current_version_no      INTEGER     NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    scope_text              TEXT,
    out_of_scope_text       TEXT,
    archived_at             TIMESTAMPTZ,
    CONSTRAINT uq_test_plan_key UNIQUE (company_id, key)
);
COMMENT ON COLUMN test_plan.archived_at IS
    'When this plan was put away, or NULL while it is in the working list (V65, audit D2). A '
    'VISIBILITY fact and orthogonal to status: any of DRAFT/IN_REVIEW/APPROVED may be archived, '
    'archiving preserves the status, and an archived plan can still be read, edited, approved, run '
    'and deleted. It exists because V32 removed CLOSED — a plan that runs were executed against can '
    'never be deleted (PlanDeleteService 409s) and had no way at all to leave the list. WHO archived '
    'it lives in audit_event (plan.archived / plan.unarchived), exactly as the actor behind '
    'project.archived_at does — this table deliberately grows no actor column beside this one, '
    'because a column written on every archive and read by nothing is a finding.';
COMMENT ON COLUMN test_plan.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS plan instead of creating '
    'a second one. Opaque, client-supplied, write-once, never read as data. NULL for every plan '
    'created before V14 and for any caller that sends no key.';
COMMENT ON COLUMN test_plan.exit_criteria IS
    'Structured predicates, e.g. {"metric":"pass_rate","op":">=","value":95}, so the plan page can '
    'evaluate them automatically rather than storing prose nobody can check (plan §6p).';
COMMENT ON COLUMN test_plan.key IS
    'TP-1 — sequential per COMPANY, immutable (§6t).';
COMMENT ON COLUMN test_plan.out_of_scope_text IS
    'Free prose: what is deliberately not tested here. Its whole value is being explicit — an absent '
    'out-of-scope section reads as an oversight to the person signing the release off.';
COMMENT ON COLUMN test_plan.scope_text IS
    'Free prose: what this plan says will be tested. NOT the plan''s scope ITEMS — plan_scope_item is '
    'which cases it covers, and the two meanings of "scope" live on one aggregate (§97 D6). Served as '
    '`scopeText`; NULL is "nobody has written one", which is not the same as an empty string somebody '
    'cleared.';
COMMENT ON COLUMN test_plan.status IS
    'DRAFT → IN_REVIEW → APPROVED, and that is the whole ladder since V32 (M7). ACTIVE and CLOSED '
    'were removed because they described a plan being RUN, which is test_run''s job — a plan is an '
    'outline of what must be tested, and whether it is under way is answered by its runs. Their '
    'history lives in audit_event''s plan.started / plan.reopened / plan.closed rows.';
CREATE UNIQUE INDEX test_plan_created_idempotency_key_idx ON test_plan (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX test_plan_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query names exactly these three '
    'columns, so a race between two retries of one intent fails on the constraint rather than '
    'inserting twice.';
CREATE INDEX test_plan_key_seq_idx ON test_plan (company_id, key_seq);
CREATE INDEX test_plan_working_list_idx ON test_plan (company_id, project_id, key_seq DESC)
    WHERE (deleted_at IS NULL) AND (archived_at IS NULL);

CREATE TABLE plan_scope_item (
    id           UUID    PRIMARY KEY,
    company_id   UUID    NOT NULL REFERENCES company(id),
    plan_id      UUID    NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    suite_id     UUID    REFERENCES suite(id),
    case_id      UUID    REFERENCES test_case(id),
    section_name TEXT,
    ordinal      INTEGER NOT NULL DEFAULT 0,
    tag          TEXT,
    CONSTRAINT ck_plan_scope_target CHECK (((((suite_id IS NOT NULL)::integer + (case_id IS NOT NULL)::integer) + (tag IS NOT NULL)::integer) <= 1) AND (section_name IS NOT NULL OR suite_id IS NOT NULL OR case_id IS NOT NULL OR tag IS NOT NULL))
);
COMMENT ON COLUMN plan_scope_item.ordinal IS
    'Author order WITHIN the plan — the order the sections were arranged in, assigned by the server '
    'from the order submitted rather than trusted from the client. The scope itself is still a SET: '
    'PlanScope sorts the ids it hashes, because two folders listed in either order are one scope and '
    'a hash over insertion order moves without the plan changing.';
COMMENT ON COLUMN plan_scope_item.section_name IS
    'The sub-feature this line belongs to — "Create an object", "Edit", "Navigation". NULL is the '
    'UNSECTIONED group, which is what every plan written before §97 holds. Plan-local: a section has '
    'no key, no lifecycle, no versions and no existence outside this plan, which is what makes it not '
    'a suite. Carried on the member rows rather than in a table of its own, so a section with no '
    'members is one row naming it with no target (see ck_plan_scope_target).';
COMMENT ON COLUMN plan_scope_item.tag IS
    'Matched against case_tag.tag exactly, the same arm suite_tag_rule uses (§97 D8) — so "add by '
    'tag" on a plan section and "add by tag" on a suite are the same act. UNIONED with the other '
    'arms, never intersected (V31 settles that), and there is no exclude arm anywhere in this product '
    'yet (M6, a recorded gap).';

CREATE TABLE test_plan_version (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    plan_id        UUID        NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    version_no     INTEGER     NOT NULL,
    snapshot       JSONB       NOT NULL,
    content_hash   TEXT        NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_plan_version UNIQUE (plan_id, version_no)
);
COMMENT ON COLUMN test_plan_version.snapshot IS
    'A plan version captures its SCOPE. Gives the as-executed membership needed by history (plan '
    '§6p).';

CREATE TABLE suite_version (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    suite_id       UUID        NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    version_no     INTEGER     NOT NULL,
    snapshot       JSONB       NOT NULL,
    content_hash   TEXT        NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_suite_version UNIQUE (suite_id, version_no)
);
COMMENT ON COLUMN suite_version.snapshot IS
    'A suite version captures COMPOSITION (member cases, order, nesting) — explicitly NOT the content '
    'of its cases, which version independently. Otherwise every case edit bumps every ancestor suite '
    '(plan §6j).';

CREATE TABLE configuration (
    id         UUID    PRIMARY KEY,
    company_id UUID    NOT NULL REFERENCES company(id),
    project_id UUID    NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    attributes JSONB   NOT NULL DEFAULT '{}'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT false
);
COMMENT ON COLUMN configuration.is_default IS
    'Every project is seeded with a "Default" configuration. Runs without an explicit environment '
    'point at it rather than carrying NULL, which keeps configuration_id NOT NULL throughout the '
    'rollup read models — suite_run_summary and plan_snapshot key on it, and a nullable PK column is '
    'not expressible. Removes null-handling from every aggregate query.';
CREATE UNIQUE INDEX configuration_default_idx ON configuration (project_id) WHERE is_default;

CREATE TABLE test_run (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    plan_id                 UUID        REFERENCES test_plan(id),
    configuration_id        UUID        NOT NULL REFERENCES configuration(id),
    key                     TEXT        NOT NULL,
    key_seq                 BIGINT      NOT NULL,
    name                    TEXT        NOT NULL,
    status                  TEXT        NOT NULL DEFAULT 'NOT_STARTED'
                            CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED','ABORTED')),
    release_version         TEXT,
    release_sort_key        TEXT,
    build_number            TEXT,
    commit_sha              TEXT,
    branch                  TEXT,
    source                  TEXT        NOT NULL DEFAULT 'MANUAL'
                            CHECK (source IN ('MANUAL','INGESTED','SCHEDULED')),
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    scope_request           JSONB,
    CONSTRAINT uq_test_run_key UNIQUE (company_id, key)
);
COMMENT ON COLUMN test_run.build_number IS
    'Distinct from release_version. Conflating them costs the ability to ask whether 4.3.0 improved '
    'between build 4470 and 4471 — the question during a release stabilization.';
COMMENT ON COLUMN test_run.created_idempotency_key IS
    'As test_plan.created_idempotency_key. It matters more here: a replayed run create would '
    'materialise a SECOND set of executions, splitting one tester''s results across two runs that each '
    'report a pass rate over a denominator nobody intended.';
COMMENT ON COLUMN test_run.key IS
    'TR-1 — sequential per COMPANY, immutable (§6t).';
COMMENT ON COLUMN test_run.release_sort_key IS
    'Computed on write from release_version — semver parsed to %05d.%05d.%05d, then a PRECEDENCE FLAG '
    '(0 pre-release, 1 release), then any suffix verbatim: 4.3.0-rc.1 is "00004.00003.00000.0-rc.1" '
    'and 4.3.0 is "00004.00003.00000.1". Non-parsing values fall back to raw collation. Two silent '
    'wrong answers, not one: without the padding "1.10" sorts before "1.9", and without the flag a '
    'release key is a PREFIX of its own release candidate''s and the candidate reads as newer (V44, '
    '§6q). '
    '  THE FLAG IS A DIGIT AND NOT A ''~'' SENTINEL because this column is ordered by Postgres under a '
    'libc collation that weights punctuation below letters, where the sentinel is a no-op — it works '
    'under C and ICU and fails under the collation in use. See V44 §1 and ReleaseSortKey. '
    '  Precedence AMONG pre-releases is still text order, so -rc.10 sorts before -rc.9: a known limit '
    'pinned by ReleaseSortKeyTest, not an oversight.';
COMMENT ON COLUMN test_run.scope_request IS
    'What this run was ASKED for — {planId, caseIds, suiteIds, tags}, normalised, as sent. Provenance '
    'only: the run''s contents are its test_execution rows and nothing re-resolves them. NULL for '
    'every run created before V61 and for every run opened by ingestion, where there was no request. '
    'A reader must show NULL as "not recorded", never as "no scope".';
COMMENT ON COLUMN test_run.source IS
    'The DOOR the request came through, never the caller''s role — MANUAL (a person, including an '
    'Automation Service token through the public endpoint), INGESTED (a posted report), SCHEDULED (a '
    'run_schedule fired, V68). A SCHEDULED run has a null created_by and a null actor on its audit '
    'row.';
CREATE UNIQUE INDEX test_run_created_idempotency_key_idx ON test_run (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX test_run_created_idempotency_key_idx IS
    'As test_plan_created_idempotency_key_idx.';
CREATE INDEX test_run_key_seq_idx ON test_run (company_id, key_seq);
CREATE INDEX test_run_plan_idx ON test_run (company_id, plan_id) WHERE (deleted_at IS NULL);
CREATE INDEX test_run_release_idx ON test_run (company_id, project_id, release_sort_key);

CREATE TABLE run_schedule (
    id               UUID        PRIMARY KEY,
    company_id       UUID        NOT NULL REFERENCES company(id),
    project_id       UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name             TEXT        NOT NULL,
    cron_expression  TEXT        NOT NULL,
    zone_id          TEXT        NOT NULL,
    scope_request    JSONB       NOT NULL,
    configuration_id UUID        REFERENCES configuration(id),
    assignee_id      UUID,
    enabled          BOOLEAN     NOT NULL DEFAULT true,
    next_fire_at     TIMESTAMPTZ,
    last_fired_at    TIMESTAMPTZ,
    last_run_id      UUID        REFERENCES test_run(id),
    last_fire_error  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID        NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    lock_version     BIGINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE run_schedule IS
    'A STANDING REQUEST for a run — what to ask for and when. The poller re-asks it and creates an '
    'ordinary test_run; nothing about execution changes. Deleting one destroys nothing that happened, '
    'because the runs it made outlive it.';
COMMENT ON COLUMN run_schedule.created_by IS
    'The AUTHORITY, re-evaluated on every fire: the poller runs the create through this person''s '
    'run:create grant, so a schedule outlives neither the grant nor the account. It is NOT the actor '
    'on what gets written — a fire''s run row and audit row both carry a null actor, because nobody '
    'performed the act.';
COMMENT ON COLUMN run_schedule.cron_expression IS
    'A six-field Spring cron expression, parsed by CronSchedule and by nothing else. Validated at '
    'write time, so an expression this server cannot read is a 400 rather than a stored schedule that '
    'silently never fires.';
COMMENT ON COLUMN run_schedule.last_fire_error IS
    'Why the last attempt produced no run — the owner lost the grant, the project was archived, the '
    'resolved scope was empty, the approval gate refused. NULL after a fire that made a run. Read by '
    'the settings screen: a schedule that silently stops is the failure mode of this whole feature.';
COMMENT ON COLUMN run_schedule.next_fire_at IS
    'When the poller will next ask. Recomputed FROM THE MOMENT OF FIRING, never from a missed slot — '
    'there is no catch-up, by decision. NULL means the expression yields nothing further, which '
    'CronExpression can legitimately answer, and the screen says "will not fire again".';
COMMENT ON COLUMN run_schedule.scope_request IS
    'V61''s document, unchanged — {planId, caseIds, suiteIds, tags}, normalised. NOT NULL here because '
    'a schedule that asked for nothing could only ever make empty runs. RE-RESOLVED on every fire: '
    '"everything tagged smoke" means today''s smoke cases. Materialisation-once is untouched — it is a '
    'property of each run.';
COMMENT ON COLUMN run_schedule.zone_id IS
    'An IANA zone, validated with ZoneId.of at write time. Stored rather than defaulted in code '
    'because "every weekday at 07:00" is not a time without one, and a DST shift with nothing on the '
    'screen to explain it is the failure this column prevents.';
CREATE INDEX run_schedule_due_idx ON run_schedule (company_id, next_fire_at) WHERE enabled;
CREATE INDEX run_schedule_project_idx ON run_schedule (company_id, project_id);

CREATE TABLE test_execution (
    id                UUID        PRIMARY KEY,
    company_id        UUID        NOT NULL REFERENCES company(id),
    project_id        UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id            UUID        NOT NULL REFERENCES test_run(id) ON DELETE CASCADE,
    case_id           UUID        NOT NULL REFERENCES test_case(id),
    case_version_id   UUID        NOT NULL REFERENCES test_case_version(id),
    status_id         UUID        REFERENCES case_status(id),
    release_version   TEXT,
    release_sort_key  TEXT,
    assignee_id       UUID        REFERENCES app_user(id),
    executed_by       UUID        REFERENCES app_user(id),
    executed_by_token UUID        REFERENCES api_token(id),
    executed_at       TIMESTAMPTZ,
    duration_ms       BIGINT,
    comment           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_execution_run_case UNIQUE (run_id, case_id)
);
COMMENT ON TABLE test_execution IS
    'The case x run instance. Deliberately has NO display key (plan §6t): an execution is always '
    'reached through its run or its case, nobody says "TE-4038" out loud, and a per-execution counter '
    'would be the highest-volume contention point in the schema for no benefit. It is identified by '
    '(run_id, case_id) and by its UUID.';
COMMENT ON COLUMN test_execution.case_version_id IS
    'THE PIN. Every result knows exactly which version of the case it executed — without this, '
    'editing a case silently rewrites history in every past run report (plan §2/§6j).';
COMMENT ON COLUMN test_execution.lock_version IS
    'Optimistic locking — multiple testers may work the same run concurrently.';
COMMENT ON COLUMN test_execution.project_id IS
    'Denormalized from run -> plan as a permission-scoping shortcut, avoiding a two-hop join on every '
    'access check.';
COMMENT ON COLUMN test_execution.release_version IS
    'Denormalized from test_run. Nearly every interesting query filters executions by release; '
    'joining back to the run on each would be the join in every slow-query report (plan §6q).';
CREATE INDEX test_execution_assignee_idx ON test_execution (company_id, assignee_id)
    WHERE executed_at IS NULL;
CREATE INDEX test_execution_case_history_idx ON test_execution (case_id, executed_at DESC);
CREATE INDEX test_execution_release_idx ON test_execution (company_id, release_sort_key, status_id);

CREATE TABLE step_result (
    id           UUID   PRIMARY KEY,
    company_id   UUID   NOT NULL REFERENCES company(id),
    execution_id UUID   NOT NULL REFERENCES test_execution(id) ON DELETE CASCADE,
    step_id      UUID   NOT NULL REFERENCES test_step(id),
    status_id    UUID   REFERENCES case_status(id),
    comment      TEXT,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_step_result UNIQUE (execution_id, step_id)
);

CREATE TABLE evidence (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    execution_id   UUID        NOT NULL REFERENCES test_execution(id) ON DELETE CASCADE,
    step_result_id UUID        REFERENCES step_result(id) ON DELETE CASCADE,
    attachment_id  UUID        NOT NULL REFERENCES attachment(id),
    caption        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID
);
COMMENT ON COLUMN evidence.step_result_id IS
    'Nullable — evidence attaches at either execution or step level. Immutable once the run closes.';

CREATE TABLE defect_link (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    execution_id UUID        REFERENCES test_execution(id) ON DELETE CASCADE,
    case_id      UUID        REFERENCES test_case(id),
    provider     TEXT        NOT NULL,
    external_key TEXT        NOT NULL,
    url          TEXT        NOT NULL,
    status_cache TEXT,
    synced_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
COMMENT ON TABLE defect_link IS
    'Behind the IssueTracker port — no Jira-specific columns, so a GitHub/ADO adapter is additive.';
COMMENT ON COLUMN defect_link.case_id IS
    'Unused. Every reader is execution-scoped, so the pre-M4 writer fills execution_id alone rather '
    'than populating a column no query names; it is derivable through test_execution if a case-scoped '
    'read is ever owed.';
COMMENT ON COLUMN defect_link.provider IS
    'Which tracker the key belongs to, stated by whatever created the row. NO DEFAULT since V62: it '
    'was ''JIRA'', which named a product the instance does not talk to. TWO WRITERS since V74 and the '
    'column tells them apart — EXTERNAL means a link a person typed, unverified by construction; a '
    'tracker''s own name (JIRA_CLOUD, JIRA_DC, AZURE_DEVOPS) means the request named a tracker_binding '
    'and the server states the provider it ACTUALLY CALLED through the caller''s credential. Free '
    'text, with no CHECK, deliberately (004 D9): a fourth tracker is an adapter and not a migration. '
    'A client may name a binding and may never name a provider.';
COMMENT ON COLUMN defect_link.status_cache IS
    'The tracker''s status as of the last sync, or NULL for a row that has never been synced — which '
    'is EVERY row, still, and is a TRUE answer rather than a missing one. Deliberately writerless, '
    'now for TWO reasons (V62 + V74 / 004 D14): there is no poller, because plan 004 deleted status '
    'sync-back outright; and under per-user credentials a status fetched with one person''s token and '
    'written here would be served to people who cannot see the issue, which is the disclosure D15 '
    'exists to prevent. Confirmed by V74, not amended by it.';
COMMENT ON COLUMN defect_link.synced_at IS
    'When the tracker was last asked about status_cache. Writerless, for that column''s two reasons. '
    '~~"until M4"~~ — V74 removes the deadline rather than meeting it: there is nothing planned that '
    'would write either column.';
COMMENT ON COLUMN defect_link.url IS
    'Where the defect lives. NOT NULL since V62, closing a gap openapi.yaml never had: DefectLink.url '
    'has been a required property since M0 while the column was nullable, which nothing had violated '
    'only because the table had no writer.';
CREATE UNIQUE INDEX defect_link_execution_key_idx ON defect_link (company_id, execution_id, provider, external_key)
    WHERE execution_id IS NOT NULL;
COMMENT ON INDEX defect_link_execution_key_idx IS
    'Serves the execution-scoped read AND enforces the create''s replay: (company_id, execution_id) is '
    'the prefix findDefectLinks looks up, and the full four columns are what the service checks '
    'before inserting — so a race between two retries of one link fails on the constraint rather than '
    'writing a twin.';

-- =============================================================================
-- DOCUMENTATION & ARTIFACTS
-- =============================================================================

CREATE TABLE doc_space (
    id         UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT NOT NULL
);

CREATE TABLE doc_page (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    space_id                UUID        NOT NULL REFERENCES doc_space(id) ON DELETE CASCADE,
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id               UUID        REFERENCES doc_page(id),
    key                     TEXT        NOT NULL,
    key_seq                 BIGINT      NOT NULL,
    path                    LTREE       NOT NULL,
    slug                    TEXT        NOT NULL,
    title                   TEXT        NOT NULL,
    status                  TEXT        NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    current_version_no      INTEGER     NOT NULL DEFAULT 1,
    search_vector           TSVECTOR,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    subject_type            TEXT,
    subject_id              UUID,
    created_idempotency_key TEXT,
    CONSTRAINT uq_doc_page_key UNIQUE (company_id, key),
    CONSTRAINT ck_doc_page_subject_kind CHECK (subject_type IS NULL OR subject_type = 'TEST_PLAN'),
    CONSTRAINT ck_doc_page_subject_paired CHECK ((subject_type IS NULL) = (subject_id IS NULL))
);
COMMENT ON COLUMN doc_page.created_idempotency_key IS
    'As suite.created_idempotency_key. It matters more here: a page is a link target, so two copies '
    'of one specification split traceability across two sets of block ids and deleting the loser '
    'destroys its links rather than merging them. NULL for every page created before V42, for any '
    'caller that sends no key, and for a page created on a plan''s behalf — that path is deduplicated '
    'by uq_doc_page_subject instead.';
COMMENT ON COLUMN doc_page.key IS
    'DOC-1 — sequential per COMPANY, immutable (§6t).';
COMMENT ON COLUMN doc_page.search_vector IS
    'Trigger-maintained, NOT generated, because the content lives in doc_block and a generation '
    'expression may only read its own row. Maintained from both sides: the page trigger on title '
    'change, the block trigger on any content change.';
COMMENT ON COLUMN doc_page.subject_id IS
    'The documented artifact''s id. Paired with subject_type by ck_doc_page_subject_paired.';
COMMENT ON COLUMN doc_page.subject_type IS
    'The kind of artifact this page documents, or NULL for a free-standing page. Opaque: no FK, and '
    'nothing joins on it — see V29''s header for why the reference cannot live on either side as a '
    'key.';
CREATE UNIQUE INDEX doc_page_created_idempotency_key_idx ON doc_page (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX doc_page_created_idempotency_key_idx IS
    'As suite_created_idempotency_key_idx. Sits alongside uq_doc_page_subject, which dedupes the '
    'plan-document path this index cannot see because that path carries no key.';
CREATE INDEX doc_page_key_seq_idx ON doc_page (company_id, key_seq);
CREATE INDEX doc_page_path_idx ON doc_page USING gist (path);
CREATE INDEX doc_page_search_idx ON doc_page USING gin (search_vector);
CREATE UNIQUE INDEX uq_doc_page_subject ON doc_page (company_id, subject_type, subject_id)
    WHERE (subject_id IS NOT NULL) AND (deleted_at IS NULL);

CREATE TABLE doc_block (
    id          UUID    PRIMARY KEY,
    company_id  UUID    NOT NULL REFERENCES company(id),
    page_id     UUID    NOT NULL REFERENCES doc_page(id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    block_type  TEXT    NOT NULL,
    content     JSONB   NOT NULL,
    anchor_slug TEXT
);
COMMENT ON COLUMN doc_block.anchor_slug IS
    'Human-readable URL convenience ONLY. Never the stored link target.';
COMMENT ON COLUMN doc_block.id IS
    'IMMUTABLE, generated at creation, NEVER derived from content. Links target (page_id, block_id), '
    'so renaming a heading or reordering sections never silently breaks traceability. Heading-text '
    'anchors break quietly, which is worse than breaking loudly (plan §6i/§6m).';
CREATE INDEX doc_block_page_idx ON doc_block (page_id, "position");

CREATE TABLE doc_page_version (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    page_id        UUID        NOT NULL REFERENCES doc_page(id) ON DELETE CASCADE,
    version_no     INTEGER     NOT NULL,
    snapshot       JSONB       NOT NULL,
    content_hash   TEXT        NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_doc_page_version UNIQUE (page_id, version_no)
);

CREATE TABLE doc_link (
    id                  UUID        PRIMARY KEY,
    company_id          UUID        NOT NULL REFERENCES company(id),
    source_type         TEXT        NOT NULL
                        CHECK (source_type IN ('TEST_CASE','SUITE','DEFECT','DOC_PAGE')),
    source_id           UUID        NOT NULL,
    target_kind         TEXT        NOT NULL
                        CHECK (target_kind IN ('DOC_BLOCK','EXTERNAL_GIT','EXTERNAL_URL','EXTERNAL_ISSUE')),
    target_block_id     UUID        REFERENCES doc_block(id) ON DELETE CASCADE,
    external_repo       TEXT,
    external_path       TEXT,
    external_line_start INTEGER,
    external_line_end   INTEGER,
    external_commit_sha TEXT,
    external_url        TEXT,
    relationship        TEXT        NOT NULL DEFAULT 'VERIFIES'
                        CHECK (relationship IN ('VERIFIES','DERIVED_FROM','REFERENCES')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ,
    updated_by          UUID,
    external_provider   TEXT,
    external_issue_id   TEXT,
    external_host       TEXT,
    CONSTRAINT ck_doc_link_target CHECK ((target_kind = 'DOC_BLOCK' AND target_block_id IS NOT NULL)
        OR (target_kind = 'EXTERNAL_GIT' AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
        OR (target_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL)
        OR (target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL AND external_host IS NOT NULL AND external_url IS NOT NULL))
);
COMMENT ON TABLE doc_link IS
    'Requirements ARE doc blocks — there is no requirement table. The coverage matrix is a query over '
    'this edge where source_type = TEST_CASE, joined to latest results (ERD resolved #18).';
COMMENT ON COLUMN doc_link.external_host IS
    'The tracker site this id belongs to. STORED, NEVER PARSED OUT OF external_url — the same rule as '
    'the label. An id is only unique within a host, so identity is (provider, host, id): two bindings '
    'to different sites holding the same numeric id are two different issues. This column is also '
    'what replaces a binding_id (D18): a foreign key from this table into `integration`''s would be a '
    'forbidden cross-module edge AND would cascade a binding''s deletion into somebody''s traceability '
    'record. The reader matches (provider, host) against the project''s current bindings, finds none '
    'after a deletion, and renders a degraded chip.';
COMMENT ON COLUMN doc_link.external_issue_id IS
    'The tracker''s OWN STABLE ID — Jira''s numeric issue id, ADO''s work-item id (004 D17). Immutable '
    'across a project move on both providers, which is the whole reason it and not the key is what is '
    'stored. THERE IS NO external_issue_key COLUMN and there must not be: a key moves. The '
    'consequence is a rule — NO DISPLAY LABEL IS EVER DERIVED FROM external_url. Parsing PAY-991 out '
    'of a stored URL gives a string that looks current, cannot be known to be stale, and lies after '
    'exactly the event id-only was chosen to survive.';
COMMENT ON COLUMN doc_link.external_provider IS
    'Which adapter reads this row. Free text with no CHECK, matching defect_link.provider (D9/D20): a '
    'fourth tracker is an adapter and not a migration. Compare tracker_binding.provider, which IS '
    'checked because it SELECTS an adapter that must exist.';
COMMENT ON COLUMN doc_link.relationship IS
    'What the source claims about the target: VERIFIES is counted as coverage, DERIVED_FROM and '
    'REFERENCES are not. The ONLY field on this row that may be updated after creation (V48) — the '
    'target coordinates may not, because a link that silently changes what it points at is worse than '
    'one that is withdrawn and remade.';
COMMENT ON COLUMN doc_link.target_kind IS
    'DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL or EXTERNAL_ISSUE (V75). NO TITLE COLUMN AND NO STATUS '
    'COLUMN accompanies the issue kind, and the absence is different in kind from the missing key: '
    'the key is absent because it MOVES, the title and status because they were FETCHED WITH ONE '
    'PERSON''S CREDENTIAL (004 D5/D15) and a shared row would serve them to somebody who cannot see '
    'the issue. Both are fetched live, per viewer, and never stored.';
COMMENT ON COLUMN doc_link.updated_at IS
    'When the relationship was last corrected. NULL means never — the row still says what it said '
    'when it was created, which is a different fact from "corrected at creation time" and is why this '
    'is not defaulted to created_at (V48).';
COMMENT ON COLUMN doc_link.updated_by IS
    'Who last corrected the relationship. Nullable for created_by''s reason: an actor may be a token '
    'or a job, and no FK to app_user, so removing a user does not remove the trace (V48).';
COMMENT ON CONSTRAINT ck_doc_link_target ON doc_link IS
    'EXTERNAL_GIT requires a commit SHA — a line number without one is meaningless six months later '
    '(plan §6m). EXTERNAL_ISSUE requires an id, a host AND a url (V75, 004 D17): the id is the '
    'identity, the host is what makes the id unique, and the url is the href — which is required '
    'because a Jira browse URL is key-addressable and cannot be built from an id alone.';
CREATE INDEX doc_link_source_idx ON doc_link (source_type, source_id);
CREATE INDEX doc_link_target_idx ON doc_link (target_block_id);

CREATE TABLE case_doc_link (
    id                  UUID    PRIMARY KEY,
    company_id          UUID    NOT NULL REFERENCES company(id),
    case_id             UUID    NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    target_kind         TEXT    NOT NULL,
    relationship        TEXT    NOT NULL,
    page_id             UUID,
    page_key            TEXT,
    page_title          TEXT,
    block_id            UUID,
    block_excerpt       TEXT,
    stale               BOOLEAN NOT NULL,
    external_repo       TEXT,
    external_path       TEXT,
    external_line_start INTEGER,
    external_line_end   INTEGER,
    external_commit_sha TEXT,
    external_url        TEXT,
    external_provider   TEXT,
    external_issue_id   TEXT,
    external_host       TEXT,
    CONSTRAINT ck_case_doc_link_target CHECK ((target_kind = 'DOC_BLOCK' AND page_id IS NOT NULL AND page_key IS NOT NULL AND page_title IS NOT NULL AND block_id IS NOT NULL)
        OR (target_kind = 'EXTERNAL_GIT' AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
        OR (target_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL)
        OR (target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL AND external_host IS NOT NULL AND external_url IS NOT NULL))
);
COMMENT ON TABLE case_doc_link IS
    'A read model in `repository`, mirroring doc_link from the case end — one row per link from a '
    'live case to a block of a live page. Written only by DocumentationFacts, replaced whole per '
    'company on every refresh. doc_link is the source of truth; this is what the case detail may read '
    'without naming another module''s tables on a request path (§3 lesson 4).';
COMMENT ON COLUMN case_doc_link.block_excerpt IS
    'The first 200 characters of the block''s text, with a trailing ellipsis when it was cut — a '
    'quotation trimmed without a mark is a claim about where the spec ends. NULL when the block has '
    'no text, so an absent excerpt has one spelling.';
COMMENT ON COLUMN case_doc_link.external_commit_sha IS
    'Mandatory for EXTERNAL_GIT and the reason the kind is worth having: an unpinned line range '
    'silently points at different code after the next commit, which is a link that lies rather than a '
    'link that breaks (open question #26, plan §6m).';
COMMENT ON COLUMN case_doc_link.external_host IS
    'Copied from doc_link.external_host. What the Links panel matches against the project''s bindings '
    'to decide whether a preview is even possible — there is no binding id on either side (D18), so a '
    'deleted binding degrades the chip and cascades nothing.';
COMMENT ON COLUMN case_doc_link.external_issue_id IS
    'Copied from doc_link.external_issue_id — the tracker''s stable id, which is the ONLY thing this '
    'row can render for a viewer who cannot preview the issue. The chip says "<Provider> issue <id>" '
    'and never a key parsed from external_url (004 D17).';
COMMENT ON COLUMN case_doc_link.id IS
    'The doc_link row this mirrors, carried rather than minted: it is what the contract''s DocLink.id '
    'means, and it makes the two tables joinable by hand.';
COMMENT ON COLUMN case_doc_link.page_id IS
    'Null for an external target — see ck_case_doc_link_target. The four block coordinates were NOT '
    'NULL until V47, when the mirror stopped being a mirror of one kind.';
COMMENT ON COLUMN case_doc_link.page_key IS
    'DOC-1. Copied from doc_page along with the title and the block excerpt, because the request path '
    'may not read doc_page. Stale between refreshes by construction; the refresh runs on the '
    'documentation.links_changed event and hourly.';
COMMENT ON COLUMN case_doc_link.stale IS
    'The linked page''s content changed after this case''s content last changed — newest '
    'doc_page_version against newest test_case_version, never updated_at on either side (see V46''s '
    'header, and ApprovalFacts for the same refusal). PAGE granularity, not block: doc_block carries '
    'no timestamp, so an edit elsewhere on the page raises it too. ALWAYS FALSE FOR AN EXTERNAL '
    'TARGET — and the reason DIFFERS BY KIND, which is why this sentence is two and not one. For '
    'EXTERNAL_GIT (V47 §3) the sha PINS the lines, so what could go out of date is the pin, and that '
    'is a different question this column must not be read as answering. For EXTERNAL_ISSUE (V75, 004 '
    'D13) there is no pin at all: the row carries NO derived meaning, so there is nothing for a '
    'freshness marker to be about. One sentence covering both would be wrong about one of them.';
COMMENT ON COLUMN case_doc_link.target_kind IS
    'DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL or EXTERNAL_ISSUE. ~~Always DOC_BLOCK~~ — superseded by '
    'V47 (the two external kinds got their first writer) and extended by V75 (the issue kind). The '
    'value is copied from doc_link and interpreted by the case detail''s Links panel, which is the '
    'only reader.';
COMMENT ON CONSTRAINT ck_case_doc_link_target ON case_doc_link IS
    'ck_doc_link_target seen in the mirror — one column list per target kind, so a refresh that wrote '
    'half a block link and half an external one fails here rather than rendering a bullet with a page '
    'title and no page. STILL CLOSED over the kinds it admits, and V47''s reason for that is AMENDED '
    'rather than left standing: V47 said "a fourth value arriving in this table is a defect in a '
    'statement that copies what it does not interpret, never a new capability", which was true of a '
    'fourth value nobody had designed. EXTERNAL_ISSUE (V75) IS a new capability, so the arm was added '
    'here deliberately and in the same migration as the source constraint. The rule the sentence was '
    'protecting survives: a FIFTH value arriving without an arm is still a defect in the refresh, not '
    'a capability.';
CREATE INDEX case_doc_link_case_idx ON case_doc_link (company_id, case_id);

CREATE TABLE artifact (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    scope                   TEXT        NOT NULL
                            CHECK (scope IN ('PROJECT','PLAN')),
    project_id              UUID        REFERENCES project(id) ON DELETE CASCADE,
    plan_id                 UUID        REFERENCES test_plan(id) ON DELETE CASCADE,
    category                TEXT        NOT NULL,
    kind                    TEXT        NOT NULL
                            CHECK (kind IN ('FILE','DOC_PAGE_REF','EXTERNAL_URL','GENERATED_REPORT')),
    title                   TEXT        NOT NULL,
    description             TEXT,
    status                  TEXT        NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
    superseded_by_id        UUID        REFERENCES artifact(id),
    attachment_id           UUID        REFERENCES attachment(id),
    doc_page_id             UUID        REFERENCES doc_page(id),
    external_url            TEXT,
    report_artifact_id      UUID,
    write_once              BOOLEAN     NOT NULL DEFAULT false,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    CONSTRAINT ck_artifact_scope CHECK ((scope = 'PROJECT' AND project_id IS NOT NULL AND plan_id IS NULL)
        OR (scope = 'PLAN' AND plan_id IS NOT NULL))
);
COMMENT ON TABLE artifact IS
    'Four kinds, one register. Supporting REFERENCES (doc pages, URLs, reports) rather than only '
    'uploads is what keeps this from becoming a graveyard of outdated copies (plan §6n).';
COMMENT ON COLUMN artifact.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS artifact instead of '
    'registering a second one. It matters most on the multipart path: a retried upload duplicates the '
    'attachment row AND the blob, and the blob is the copy nobody sees. Opaque, client-supplied, '
    'write-once, never read as data. NULL for every artifact registered before V59 and for any caller '
    'that sends no key.';
COMMENT ON COLUMN artifact.write_once IS
    'Sign-off records: corrections supersede, never edit.';
CREATE UNIQUE INDEX artifact_created_idempotency_key_idx ON artifact (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX artifact_created_idempotency_key_idx IS
    'Serves the two create endpoints'' replay lookup AND enforces it: the query is keyed on exactly '
    'these three columns, so a race between two retries of one intent fails on the constraint rather '
    'than writing a twin.';

-- =============================================================================
-- REVIEW & WORK
-- =============================================================================

CREATE TABLE review (
    id                    UUID        PRIMARY KEY,
    company_id            UUID        NOT NULL REFERENCES company(id),
    project_id            UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    subject_type          TEXT        NOT NULL
                          CHECK (subject_type IN ('CASE','SUITE','PLAN','DOC')),
    subject_id            UUID        NOT NULL,
    subject_version_no    INTEGER     NOT NULL,
    state                 TEXT        NOT NULL DEFAULT 'IN_REVIEW'
                          CHECK (state IN ('IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
    required_peer_reviews INTEGER     NOT NULL DEFAULT 0,
    approved_by           UUID        REFERENCES app_user(id),
    approved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    lock_version          BIGINT      NOT NULL DEFAULT 0
);
COMMENT ON COLUMN review.state IS
    'IN_REVIEW while open. Three ways to end (V22): APPROVED by someone holding *:approve, REJECTED '
    'when another person sends it back, WITHDRAWN when the submitter takes it back. The last is not a '
    'rejection and must not read as one in an audit.';
COMMENT ON COLUMN review.subject_type IS
    'Which artifact is under review. DOC since V71 (a documentation page); the other three since V1. '
    'Paired with subject_id, which has no foreign key because the four tables are in three modules.';
COMMENT ON COLUMN review.subject_version_no IS
    'An approval applies to a SPECIFIC version, including its snapshotted conditions (plan §6g).';
CREATE INDEX ix_review_subject ON review (company_id, subject_type, subject_id, created_at DESC);
CREATE UNIQUE INDEX uq_review_open_per_subject ON review (company_id, subject_type, subject_id)
    WHERE state = 'IN_REVIEW';
COMMENT ON INDEX uq_review_open_per_subject IS
    'At most one OPEN review per subject (V22). Partial, because the closed rows are the subject''s '
    'review history and there may be any number of them.';

CREATE TABLE review_assignment (
    id               UUID        PRIMARY KEY,
    company_id       UUID        NOT NULL REFERENCES company(id),
    review_id        UUID        NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    assignee_user_id UUID        REFERENCES app_user(id),
    assignee_team_id UUID        REFERENCES team(id) ON DELETE CASCADE,
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by      UUID,
    CONSTRAINT ck_review_assignment_one_assignee CHECK (num_nonnulls(assignee_user_id, assignee_team_id) = 1)
);
COMMENT ON TABLE review_assignment IS
    'Who was ASKED to review (V25, plan §6g). A worklist entry, not part of the audit record — that '
    'is review_participation. Unassigning deletes the row, and a deleted team takes its requests with '
    'it.';
COMMENT ON COLUMN review_assignment.assigned_by IS
    'Who asked. Nullable like every other actor column in this schema (§66): an offboarded account '
    'does not un-ask the question.';
COMMENT ON COLUMN review_assignment.assignee_team_id IS
    'A team, any member of which may action it (§6g). ON DELETE CASCADE, unlike role_assignment''s '
    'identically-shaped column, because what is lost is a request rather than a grant — see this '
    'migration''s header for why §71''s 409 is deliberately not extended here.';
COMMENT ON COLUMN review_assignment.assignee_user_id IS
    'One named person. Mutually exclusive with assignee_team_id — see '
    'ck_review_assignment_one_assignee. References instance-level app_user, so the FK alone does NOT '
    'prove the person belongs to this company: ReviewAssignmentService checks project visibility, '
    'which is the §71 defect one table over.';
CREATE INDEX ix_review_assignment_assignee_user ON review_assignment (company_id, assignee_user_id)
    WHERE assignee_user_id IS NOT NULL;
CREATE INDEX ix_review_assignment_review ON review_assignment (company_id, review_id, assigned_at);
CREATE UNIQUE INDEX uq_review_assignment_team ON review_assignment (company_id, review_id, assignee_team_id)
    WHERE assignee_team_id IS NOT NULL;
CREATE UNIQUE INDEX uq_review_assignment_user ON review_assignment (company_id, review_id, assignee_user_id)
    WHERE assignee_user_id IS NOT NULL;

CREATE TABLE review_participation (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    review_id  UUID        NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    kind       TEXT        NOT NULL
               CHECK (kind IN ('PEER_REVIEW','APPROVAL')),
    verdict    TEXT        NOT NULL
               CHECK (verdict IN ('LGTM','CHANGES_REQUESTED','REJECTED','APPROVED')),
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN review_participation.kind IS
    'Separates a Test Engineer advisory review from a Test Lead binding approval. Only APPROVAL rows '
    'may transition review.state, and creating one requires the *:approve permission (plan §6b).';
CREATE INDEX ix_review_participation_review ON review_participation (company_id, review_id, created_at);

CREATE TABLE review_comment (
    id          UUID        PRIMARY KEY,
    company_id  UUID        NOT NULL REFERENCES company(id),
    review_id   UUID        NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    parent_id   UUID        REFERENCES review_comment(id),
    anchor_type TEXT,
    anchor_id   UUID,
    body        TEXT        NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    resolved_by UUID,
    CONSTRAINT review_comment_resolution_check CHECK (resolved_by IS NULL
        OR resolved_at IS NOT NULL)
);
COMMENT ON COLUMN review_comment.parent_id IS
    'The thread root this is a reply to, or NULL when this IS a root. Exactly one level deep — '
    '`ReviewCommentEdit` refuses a reply to a reply, because a thread with no bottom has no rendering '
    'and §6g asks for threads, not for a tree.';
COMMENT ON COLUMN review_comment.resolved_by IS
    'Who marked the thread dealt with (V24). Null while it is open, and null on a resolved thread '
    'whose account has since been offboarded — the resolution stands either way.';
COMMENT ON CONSTRAINT review_comment_resolution_check ON review_comment IS
    'resolved_at and resolved_by are one act. A resolver with no timestamp is a half-applied row and '
    'is refused; a timestamp with no resolver is an unattributable resolution and is allowed, for the '
    'reason review.created_by is nullable.';
CREATE INDEX ix_review_comment_review ON review_comment (company_id, review_id, created_at);

CREATE TABLE work_item (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title                   TEXT        NOT NULL,
    description             TEXT,
    kind                    TEXT        NOT NULL DEFAULT 'TASK',
    status                  TEXT        NOT NULL DEFAULT 'TODO',
    priority                TEXT        NOT NULL DEFAULT 'MEDIUM'
                            CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    due_date                DATE,
    assignee_id             UUID        REFERENCES app_user(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT
);
COMMENT ON TABLE work_item IS
    'ONLY real tasks are rows. Derived items — assigned executions, pending reviews, stale cases — '
    'are NEVER materialized; they resolve at query time from their source. There is deliberately no '
    'column here that could let a card status diverge from its execution (plan §6l).';
COMMENT ON COLUMN work_item.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS task instead of creating '
    'a second card. Opaque, client-supplied, write-once, never read as data. NULL for every task '
    'created before V17 and for any caller that sends no key.';
COMMENT ON COLUMN work_item.priority IS
    'Distinct from test_case.priority. A P1 case may be a low-priority thing to UPDATE.';
CREATE UNIQUE INDEX work_item_created_idempotency_key_idx ON work_item (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX work_item_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query names exactly these three '
    'columns, so a race between two retries of one intent fails on the constraint rather than '
    'inserting twice.';

CREATE TABLE work_item_link (
    company_id   UUID NOT NULL REFERENCES company(id),
    work_item_id UUID NOT NULL REFERENCES work_item(id) ON DELETE CASCADE,
    target_type  TEXT NOT NULL,
    target_id    UUID NOT NULL,
    PRIMARY KEY (work_item_id, target_type, target_id)
);

CREATE TABLE derived_item_annotation (
    company_id        UUID    NOT NULL REFERENCES company(id),
    user_id           UUID    NOT NULL REFERENCES app_user(id),
    source_type       TEXT    NOT NULL,
    source_id         UUID    NOT NULL,
    note              TEXT,
    personal_due_date DATE,
    flagged           BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, source_type, source_id)
);
COMMENT ON TABLE derived_item_annotation IS
    'Personal metadata on a DERIVED card without owning its status — satisfies "let me note something '
    'on that execution card" without breaking the invariant (plan §6l).';

-- =============================================================================
-- REPORTING & ROLL-UPS
-- =============================================================================

CREATE TABLE execution_fact (
    execution_id      UUID        PRIMARY KEY,
    company_id        UUID        NOT NULL,
    project_id        UUID        NOT NULL,
    case_id           UUID        NOT NULL,
    case_version_id   UUID        NOT NULL,
    suite_id          UUID,
    suite_path        LTREE,
    plan_id           UUID,
    run_id            UUID        NOT NULL,
    configuration_id  UUID        NOT NULL,
    release_version   TEXT,
    release_sort_key  TEXT,
    status_kind       TEXT        NOT NULL,
    counts_as_pass    BOOLEAN     NOT NULL,
    automation_status TEXT        NOT NULL,
    source            TEXT        NOT NULL,
    executed_by       UUID,
    executed_at       TIMESTAMPTZ,
    duration_ms       BIGINT,
    test_type_id      UUID,
    test_level_id     UUID,
    feature_ids       uuid[]
);
COMMENT ON TABLE execution_fact IS
    'Append-only fact log. Deliberately duplicates dimensions to avoid joins. Do NOT defer this — '
    'reporting built on live joins cannot be converted to a read model incrementally (plan §6e).';
COMMENT ON COLUMN execution_fact.suite_id IS
    'Where the case was filed WHEN IT RAN — frozen at insert, never re-filed (§86). NULL means the '
    'case was unfiled at that moment (V31), which is a fact about the execution and not a gap: the '
    'alternative is the LEFT JOIN''s absence, which drops the execution from the log entirely.';
CREATE INDEX execution_fact_case_idx ON execution_fact (company_id, case_id, executed_at DESC);
CREATE INDEX execution_fact_feature_idx ON execution_fact USING gin (feature_ids);
CREATE INDEX execution_fact_plan_idx ON execution_fact (company_id, plan_id, executed_at DESC);
CREATE INDEX execution_fact_release_idx ON execution_fact (company_id, release_sort_key);
CREATE INDEX execution_fact_suite_idx ON execution_fact USING gist (suite_path);

CREATE TABLE suite_run_summary (
    company_id          UUID        NOT NULL,
    run_id              UUID        NOT NULL,
    suite_id            UUID        NOT NULL,
    configuration_id    UUID        NOT NULL,
    suite_version_no    INTEGER,
    passed              INTEGER     NOT NULL DEFAULT 0,
    failed              INTEGER     NOT NULL DEFAULT 0,
    blocked             INTEGER     NOT NULL DEFAULT 0,
    skipped             INTEGER     NOT NULL DEFAULT 0,
    not_run             INTEGER     NOT NULL DEFAULT 0,
    total               INTEGER     NOT NULL DEFAULT 0,
    membership_snapshot JSONB,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_executed_at    TIMESTAMPTZ,
    PRIMARY KEY (run_id, suite_id, configuration_id)
);
COMMENT ON TABLE suite_run_summary IS
    'COUNTS are stored; percentages are derived on read. Storing percentages would make the "of '
    'executed" vs "of total" toggle impossible, and nested rollups must SUM LEAF COUNTS — averaging '
    'child percentages over unequal case counts is simply wrong arithmetic (plan §6p).';
COMMENT ON COLUMN suite_run_summary.last_executed_at IS
    'The most recent result recorded against this suite in this run, or NULL when the run has opened '
    'and recorded nothing. Moves with the counts and is NOT frozen with membership_snapshot: it '
    'describes the results, not the composition.';
CREATE INDEX suite_run_summary_suite_idx ON suite_run_summary (company_id, suite_id);
COMMENT ON INDEX suite_run_summary_suite_idx IS
    'The suite history page: every run of one suite, newest first. The PRIMARY KEY leads with run_id '
    'and therefore cannot serve a query that names a suite and no run.';

CREATE TABLE plan_snapshot (
    company_id       UUID        NOT NULL,
    plan_id          UUID        NOT NULL,
    case_id          UUID        NOT NULL,
    configuration_id UUID        NOT NULL,
    execution_id     UUID,
    status_kind      TEXT,
    counts_as_pass   BOOLEAN,
    executed_at      TIMESTAMPTZ,
    release_version  TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id, case_id, configuration_id)
);
COMMENT ON TABLE plan_snapshot IS
    'The CURRENT latest result per case per configuration. Upserted as results land, including on '
    'late-arriving CI ingestion. Frozen into the evidence bundle at plan close (plan §6p/§6n).';
COMMENT ON COLUMN plan_snapshot.updated_at IS
    'When the rollup refresh last wrote this row. Served as PlanStatistics.computedAt, which is a '
    'claim about the ARITHMETIC''s age — distinct from `stale`, which is a claim about the EXECUTIONS'' '
    'age. A plan executed an hour ago and rolled up ten minutes ago is fresh by one measure and '
    'behind by the other, and rendering them as one signal reintroduces the ambiguity plan §6p added '
    'the second number to remove.';
CREATE INDEX plan_snapshot_plan_idx ON plan_snapshot (company_id, plan_id);
COMMENT ON INDEX plan_snapshot_plan_idx IS
    'The plan statistics page: every case of one plan. The PRIMARY KEY already leads with plan_id, so '
    'this looks redundant and is not — it adds the company_id seek the PK cannot do at all, which is '
    'the predicate RLS puts on every single statement against this table.';

CREATE TABLE case_health (
    case_id                 UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL,
    project_id              UUID        NOT NULL,
    outdated_spec_changed   BOOLEAN     NOT NULL DEFAULT false,
    stale_approval          BOOLEAN     NOT NULL DEFAULT false,
    condition_drift         BOOLEAN     NOT NULL DEFAULT false,
    stale_automation        BOOLEAN     NOT NULL DEFAULT false,
    never_executed          BOOLEAN     NOT NULL DEFAULT false,
    long_unexecuted         BOOLEAN     NOT NULL DEFAULT false,
    unlinked                BOOLEAN     NOT NULL DEFAULT false,
    failing_repeatedly      BOOLEAN     NOT NULL DEFAULT false,
    orphaned                BOOLEAN     NOT NULL DEFAULT false,
    incomplete              BOOLEAN     NOT NULL DEFAULT false,
    computed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    undeclared_automation   BOOLEAN     NOT NULL DEFAULT false,
    last_status_kind        TEXT,
    last_executed_at        TIMESTAMPTZ,
    last_release_version    TEXT,
    last_configuration_name TEXT
);
COMMENT ON TABLE case_health IS
    'Signals are DERIVED, never authored — no UI writes here, so they self-heal when the underlying '
    'condition is fixed. No "mark as not-stale" button exists to lie with (plan §6o).';
COMMENT ON COLUMN case_health.last_configuration_name IS
    'The name of the configuration the case''s newest RECORDED execution ran under — joined off '
    'test_run through the execution''s run by the same statement that writes last_status_kind, so the '
    'four columns of a last result cannot disagree (V45). The NAME and not the id, because the '
    'contract declares a string and a configuration is unique by name within a project; a rename '
    'leaves this stale until the next recompute, which is the ordinary property of a read model. NULL '
    'on a case that has never been executed, and on a row written before V72 until the next recompute '
    'reaches it.';
COMMENT ON COLUMN case_health.last_executed_at IS
    'When that execution was recorded. Recorded means status_id IS NOT NULL — creating a run writes '
    'one empty execution per case in scope (§42), so a case in a freshly planned run has an execution '
    'row and has never been executed. This is the same fact long_unexecuted is derived from, one '
    'cutoff later.';
COMMENT ON COLUMN case_health.last_release_version IS
    'The release that execution belonged to, copied off test_execution.release_version rather than '
    'joined back through test_run (V1: that join is the one in every slow-query report). NULL for a '
    'run outside a release, which is a real state and the only member of the contract''s lastResult '
    'object that may be null while the object is present.';
COMMENT ON COLUMN case_health.last_status_kind IS
    'The StatusKind of the case''s newest RECORDED execution — case_status.kind, not the project''s own '
    'name for it, because the name is renameable and the kind is the fixed aggregation class the '
    'contract declares. NULL exactly when the case has never been executed, which is the same fact '
    'never_executed asserts: both are derived from one row by one statement so they cannot disagree.';
COMMENT ON COLUMN case_health.stale_automation IS
    'A case marked AUTOMATED whose newest INGESTED result is older than the window (plan §6c). '
    'Requires a previous ingested result: "stopped reporting" presupposes started, and §6c makes '
    'automationKey optional precisely so a case can be marked AUTOMATED before its automation exists '
    '— so a predicate of "no result in N days" would fire on every case in a project the day somebody '
    'began declaring intent. A case with no results at all is NEVER_EXECUTED.';
COMMENT ON COLUMN case_health.undeclared_automation IS
    'A case NOT marked AUTOMATED that has taken a result from an ingested run inside the '
    'stale-automation window (plan §6c). The mirror of stale_automation: that one is a result the '
    'team believes it is getting and is not, this one is a result arriving that no coverage number '
    'counts. Both are derived from one fact — the newest ingested result — on opposite sides of one '
    'cutoff, so a case can never raise both.';
CREATE INDEX case_health_never_exec_idx ON case_health (company_id, project_id) WHERE never_executed;
CREATE INDEX case_health_outdated_idx ON case_health (company_id, project_id)
    WHERE outdated_spec_changed;
CREATE INDEX case_health_stale_appr_idx ON case_health (company_id, project_id) WHERE stale_approval;
CREATE INDEX case_health_stale_autom_idx ON case_health (company_id, project_id)
    WHERE stale_automation;
CREATE INDEX case_health_undecl_autom_idx ON case_health (company_id, project_id)
    WHERE undeclared_automation;
CREATE INDEX case_health_unlinked_idx ON case_health (company_id, project_id) WHERE unlinked;

CREATE TABLE case_flake (
    case_id             UUID         PRIMARY KEY REFERENCES test_case(id) ON DELETE CASCADE,
    company_id          UUID         NOT NULL REFERENCES company(id),
    project_id          UUID         NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    executions          INTEGER      NOT NULL DEFAULT 0,
    transitions         INTEGER      NOT NULL DEFAULT 0,
    alternations        INTEGER      NOT NULL DEFAULT 0,
    cells               INTEGER      NOT NULL DEFAULT 0,
    flake_score         NUMERIC(5,4)
                        CHECK (flake_score IS NULL
                              OR ((flake_score >= (0)::numeric) AND (flake_score <= (1)::numeric))),
    last_alternation_at TIMESTAMPTZ,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    quarantined         BOOLEAN      NOT NULL DEFAULT false,
    quarantined_at      TIMESTAMPTZ,
    quarantined_by      UUID         REFERENCES app_user(id),
    quarantine_reason   TEXT,
    CONSTRAINT case_flake_quarantine_complete CHECK (((NOT quarantined) AND quarantined_at IS NULL AND quarantined_by IS NULL AND quarantine_reason IS NULL)
        OR (quarantined AND quarantined_at IS NOT NULL AND quarantined_by IS NOT NULL AND quarantine_reason IS NOT NULL))
);
COMMENT ON TABLE case_flake IS
    'Per-case flake evidence (DERIVED, plan §6l) and quarantine decision (AUTHORED, plan §6c) in one '
    'row. The two halves are kept apart by the ON CONFLICT set-list rather than by two tables: the '
    'recompute names the seven derived columns and the quarantine write names the four authored ones, '
    'so neither can blank the other. No lock_version — the quarantine toggle is a single-writer '
    'transition from one action, not two screens racing over a form (report_job''s shape, not '
    'test_case''s).';
COMMENT ON COLUMN case_flake.alternations IS
    'How many of those adjacent pairs changed between a pass and a FAILED. Non-outcomes — BLOCKED, '
    'SKIPPED, RETEST, UNTESTED — are removed from the sequence BEFORE the pairs are formed, which is '
    'not the same as ignoring them: a blocked run between two passes would otherwise read as two '
    'alternations (pass->blocked, blocked->pass) and score a perfectly stable test at 1.0. This is '
    'R7''s "a pass and a BLOCK is not an inconsistency" rule applied to a sequence.';
COMMENT ON COLUMN case_flake.cells IS
    'How many (configuration, case version) cells contributed. Flakiness is scoped to ONE cell (plan '
    '§6l): "alternation across versions is just change, and reporting it as flake is a false signal." '
    'Stored so a reader can tell a test that flaps on one browser from one that flaps on five.';
COMMENT ON COLUMN case_flake.flake_score IS
    'alternations / transitions, in [0,1] — or NULL below the evidence floor, which is a DIFFERENT '
    'claim from 0.0. Two results that went pass then fail score 1.0 on any ratio definition and that '
    'is noise, not a flake; 0.0 means "evidently stable" and NULL means "not enough evidence to say". '
    'Collapsing the two is the confident zero §95''s usage_count and §100''s exit criteria both record. '
    'The counts behind it are stored either way so a reader can see WHY there is no score. Bounded '
    'window (tcms.case-flake.window) for §120''s reason: an unbounded score can never go false, so a '
    'test that flaked last spring and has been solid since stays flagged for ever with nothing '
    'anybody can do about it.';
COMMENT ON COLUMN case_flake.quarantine_reason IS
    'Required whenever quarantined — see case_flake_quarantine_complete. Gated on case:edit, which '
    'Automation Service deliberately does NOT hold (roles spec §6b): a leaked CI token can post '
    'results and cannot quarantine a test to stop its own red results from gating. Same argument §117 '
    'made for automation_key_mapping, and the reason this is not gated on result:submit.';
COMMENT ON COLUMN case_flake.transitions IS
    'Adjacent pairs of outcomes inside a cell — the DENOMINATOR of flake_score, and never the '
    'execution count. A case with six results spread over six cells has six executions and zero '
    'transitions, because nothing in it ever ran twice under the same conditions.';
CREATE INDEX case_flake_quarantined_idx ON case_flake (company_id, project_id) WHERE quarantined;
CREATE INDEX case_flake_score_idx ON case_flake (company_id, project_id, flake_score DESC)
    WHERE flake_score IS NOT NULL;

CREATE TABLE report_definition (
    id                  UUID        PRIMARY KEY,
    company_id          UUID        NOT NULL REFERENCES company(id),
    project_id          UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    type                TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    parameters          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    schedule_cron       TEXT,
    owner_id            UUID        REFERENCES app_user(id),
    shared_with_team_id UUID        REFERENCES team(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN report_definition.parameters IS
    'Includes the AGGREGATION POLICY (latest / latest-per-config / worst / best / all). Two teams '
    'reading "94% pass" otherwise mean entirely different things (plan §6e).';

CREATE TABLE report_artifact (
    id              UUID        PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    definition_id   UUID        REFERENCES report_definition(id) ON DELETE SET NULL,
    format          TEXT        NOT NULL
                    CHECK (format IN ('PDF','MD','HTML','CSV','XLSX','JSON')),
    storage_key     TEXT        NOT NULL,
    filter_snapshot JSONB       NOT NULL,
    data_watermark  TIMESTAMPTZ,
    generated_by    UUID        REFERENCES app_user(id),
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN report_artifact.definition_id IS
    'The saved definition this artifact was generated from, or NULL for an ad-hoc report. ON DELETE '
    'SET NULL since V28, matching report_job.definition_id: deleting a saved report leaves the files '
    'it produced, which become indistinguishable from ad-hoc ones because that is what they now are.';
COMMENT ON COLUMN report_artifact.filter_snapshot IS
    'Determinism: a report you cannot reproduce is a report nobody trusts (plan §6e).';
COMMENT ON COLUMN report_artifact.format IS
    'What the bytes in storage_key are. JSON (V27) is the serialized ReportModel and is written for '
    'every job alongside the requested format — it is what the on-screen view reads, not a debug '
    'convenience.';

CREATE TABLE report_job (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    definition_id           UUID        REFERENCES report_definition(id) ON DELETE SET NULL,
    type                    TEXT        NOT NULL,
    format                  TEXT        NOT NULL
                            CHECK (format IN ('PDF','MD','HTML','CSV','XLSX','JSON')),
    parameters              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status                  TEXT        NOT NULL
                            CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    artifact_id             UUID        REFERENCES report_artifact(id),
    model_artifact_id       UUID        REFERENCES report_artifact(id),
    error                   TEXT,
    attempts                INTEGER     NOT NULL DEFAULT 0,
    requested_by            UUID        REFERENCES app_user(id),
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    created_idempotency_key TEXT
);
COMMENT ON TABLE report_job IS
    'A report generation request in flight (V27, plan §6e). The row the 202 names and the poller '
    'reads. It survives a restart, which is the whole reason it is a table and not an @Async '
    'dispatch. Nothing deletes rows here in v1 — see this migration''s retention note.';
COMMENT ON COLUMN report_job.artifact_id IS
    'The artifact in the REQUESTED format. Set last, after the bytes are in the store — see '
    'ReportJobRunner for why this ordering is the inverse of EvidenceService''s.';
COMMENT ON COLUMN report_job.attempts IS
    'Incremented on claim. There is no retry policy in v1 — a FAILED job is re-requested by the user, '
    'which is honest and costs one click. The column is here so a retry loop, when it arrives, does '
    'not need a migration to be observable.';
COMMENT ON COLUMN report_job.created_idempotency_key IS
    'The Idempotency-Key of the request that queued this job, or NULL when none was sent (V66, audit '
    'E2). Opaque, write-once, never read as data — as on test_case, test_plan, test_run, suite, '
    'doc_page, work_item, ai_proposal and artifact. A replayed key answers 200 with THIS job rather '
    'than 202 with a second one, which matters more here than on a row: a report job refreshes '
    'execution_fact, computes, renders and writes two artifacts, so a duplicate is a second full pass '
    'rather than a second row.';
COMMENT ON COLUMN report_job.definition_id IS
    'The saved definition this run came from, or NULL for an ad-hoc request. ON DELETE SET NULL, not '
    'CASCADE: deleting a saved report must not delete the history of what it produced.';
COMMENT ON COLUMN report_job.format IS
    'The format ASKED FOR. The set is report_artifact''s, deliberately — this constraint is about what '
    'the schema may legitimately hold, not about what the server currently renders. PDF is refused '
    'with a 400 at REQUEST time (there is no PDF renderer installed), in the service, where the '
    'message can say so; a client cannot tell an unsupported format from a broken server by watching '
    'a job go FAILED an hour later.';
COMMENT ON COLUMN report_job.model_artifact_id IS
    'The same report as a JSON artifact, written for EVERY job (decision F). §6e: "the on-screen '
    'report view consumes the same model via JSON, so what you see is what exports." A synchronous '
    'preview endpoint would be a second code path producing a second answer.';
COMMENT ON COLUMN report_job.parameters IS
    'Includes the AGGREGATION POLICY, for report_definition.parameters'' reason (plan §6e): two teams '
    'reading "94% pass" otherwise mean entirely different things.';
COMMENT ON COLUMN report_job.requested_by IS
    'Who asked. Nullable like every other actor column since §66: an offboarded account does not '
    'un-request a report.';
CREATE INDEX ix_report_job_claim ON report_job (company_id, status, requested_at);
CREATE UNIQUE INDEX report_job_created_idempotency_key_idx ON report_job (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
COMMENT ON INDEX report_job_created_idempotency_key_idx IS
    'Enforces the replay guarantee, and its three columns ARE the lookup''s three predicates — a '
    'lookup narrower than the constraint finds nothing and then violates the index on insert, which '
    'is a validated request answering 500. See V14 for the full account.';

-- =============================================================================
-- INTEGRATION
-- =============================================================================

CREATE TABLE tracker_binding (
    id               UUID        PRIMARY KEY,
    company_id       UUID        NOT NULL REFERENCES company(id),
    project_id       UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    provider         TEXT        NOT NULL
                     CHECK (provider IN ('JIRA_CLOUD','JIRA_DC','AZURE_DEVOPS')),
    base_url         TEXT        NOT NULL
                     CHECK (base_url ~~ 'https://%'),
    external_project TEXT        NOT NULL,
    config           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    is_default       BOOLEAN     NOT NULL DEFAULT false,
    enabled          BOOLEAN     NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    lock_version     BIGINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE tracker_binding IS
    'One TCMS project''s mapping to one tracker project (004 D1) — a Jira project key, or an ADO '
    'organization + team project. MANY per project, exactly one default. HOLDS NO CREDENTIAL and has '
    'no column one could be put in (D2): the credential is each person''s own tracker_identity row, '
    'keyed to this row''s host.';
COMMENT ON COLUMN tracker_binding.base_url IS
    'The tracker site. https:// only, enforced BOTH at the write (OutboundUrlGuard) and by the CHECK '
    'here — the guard also refuses private, loopback, link-local and unique-local destinations AFTER '
    'DNS resolution, which no CHECK can see. Not updatable: see provider.';
COMMENT ON COLUMN tracker_binding.config IS
    'Per-provider settings the adapter reads — the issue type to file, a default area path, the '
    'JQL/WIQL scope clause the search is narrowed by. JSONB because the keys are the ADAPTER''s; each '
    'adapter validates its own on the write and refuses with 400 on errors.config.';
COMMENT ON COLUMN tracker_binding.enabled IS
    'A disabled binding is skipped by search, preview and filing, and links made through it render '
    'degraded. Kept rather than deleted so the links keep resolving a provider and a host.';
COMMENT ON COLUMN tracker_binding.external_project IS
    'The Jira project key (PAY), or ADO''s org/teamProject. Free text — this product knows no '
    'tracker''s naming rules and refusing a shape it guessed at would reject a real project.';
COMMENT ON COLUMN tracker_binding.is_default IS
    'Where POST .../tracker/issues files when the request names no binding. Exactly one per project, '
    'enforced by tracker_binding_default_idx: the first binding becomes the default whether or not it '
    'said so, and deleting the default promotes the oldest remaining one — so there is never zero and '
    'never two.';
COMMENT ON COLUMN tracker_binding.provider IS
    'Which adapter reads this binding. CHECKED here and deliberately NOT on defect_link.provider or '
    'doc_link.external_provider (D9/D20): this value SELECTS an adapter that must exist, those RECORD '
    'what some adapter said. Not updatable by the API — identities are keyed to the host, so a '
    'binding that changed provider or host would silently start using different people''s credentials.';
CREATE UNIQUE INDEX tracker_binding_default_idx ON tracker_binding (company_id, project_id)
    WHERE is_default;
COMMENT ON INDEX tracker_binding_default_idx IS
    'At most one default per project, enforced rather than intended: two defaults would make "where '
    'does a defect get filed" answerable two ways, and the answer would depend on row order.';
CREATE INDEX tracker_binding_project_idx ON tracker_binding (company_id, project_id);
CREATE UNIQUE INDEX tracker_binding_target_idx ON tracker_binding (company_id, project_id, provider, base_url, external_project);
COMMENT ON INDEX tracker_binding_target_idx IS
    'One project may not be bound twice to the same tracker project on the same host — the 409 the '
    'create answers. company_id LEADS, per ERD §0.1 point 3.';

CREATE TABLE tracker_identity (
    id              UUID        PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    user_id         UUID        NOT NULL,
    provider        TEXT        NOT NULL
                    CHECK (provider IN ('JIRA_CLOUD','JIRA_DC','AZURE_DEVOPS')),
    base_url        TEXT        NOT NULL
                    CHECK (base_url ~~ 'https://%'),
    auth_method     TEXT        NOT NULL DEFAULT 'PAT'
                    CHECK (auth_method = 'PAT'),
    secret          BYTEA       NOT NULL,
    secret_hint     TEXT,
    account_id      TEXT,
    refresh_token   BYTEA,
    scopes          TEXT,
    expires_at      TIMESTAMPTZ,
    last_error      TEXT,
    last_checked_at TIMESTAMPTZ,
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version    BIGINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE tracker_identity IS
    'ONE PERSON''S connection to one tracker host (004 D2) — theirs, served to nobody else. Keyed to '
    'the HOST rather than to a binding, so one connect serves every project in this company bound to '
    'that site. RLS gives COMPANY isolation; the per-person half is TrackerIdentityService filtering '
    'on user_id = the caller, and no endpoint anywhere returns another person''s row. STATED COST: a '
    'person in two companies bound to the same Jira site connects twice.';
COMMENT ON COLUMN tracker_identity.account_id IS
    'The person''s own tracker login. Jira Cloud''s Basic auth needs the email beside the token; NULL '
    'for the providers that do not. Personal information, so it shares secret_hint''s audience: theirs '
    'alone.';
COMMENT ON COLUMN tracker_identity.auth_method IS
    'PAT, and the CHECK says so (004 D3). A PAT is the only mechanism on all three providers that is '
    'entirely user-side — no app registration, no tenant config, no redirect URI — so it is what '
    'ships first. Relaxing this CHECK is the OAuth slice''s first line; refresh_token, scopes and '
    'expires_at are already here so that slice is not a table redesign.';
COMMENT ON COLUMN tracker_identity.expires_at IS
    'When the token dies. NOT OPTIONAL METADATA: Jira Cloud has enforced a maximum one-year token '
    'life since Dec 2024, so every Jira Cloud row here has a death date by construction, and the '
    'connections page shows the date on the first screen rather than in a drawer.';
COMMENT ON COLUMN tracker_identity.last_error IS
    'What the tracker said the last time a call with this credential failed; NULL after a success. '
    'The ERD''s sentence, now per person: "an expired Jira token that silently stops creating defects '
    '— while runs keep passing and failing — is the realistic failure and is invisible otherwise." '
    'The TEXT is the person''s own to read; connected-members shows a STATE.';
COMMENT ON COLUMN tracker_identity.refresh_token IS
    'Always NULL under PAT. Present from V73 so OAuth is a second auth_method rather than a second '
    'table — D3''s trick, applied deliberately this time.';
COMMENT ON COLUMN tracker_identity.secret IS
    'The PAT / API token, AES-GCM via SecretCipher (nonce prepended). Recoverable rather than '
    'digested because the server has to PRESENT it — the argument SecretCipher''s header makes for '
    'notification_channel.secret, now once per person rather than once per project.';
COMMENT ON COLUMN tracker_identity.secret_hint IS
    'Four end-anchored characters. Recognition, never a credential — SecretHint''s rule. Served to the '
    'person whose row it is and to NOBODY else: /tracker/connected-members must not carry it (D19), '
    'and a test closes that response''s key set rather than naming the leaks it thought of.';
COMMENT ON COLUMN tracker_identity.user_id IS
    'The person whose credential this is. No foreign key, for storage.blob''s reason: app_user is '
    'instance-level (ERD §0.1''s exception list) and `integration` never resolves this id to a person '
    '— it is an opaque UUID here, exactly as owner_id is in `storage`.';
CREATE UNIQUE INDEX tracker_identity_host_idx ON tracker_identity (company_id, user_id, provider, base_url);
COMMENT ON INDEX tracker_identity_host_idx IS
    'One connection per person per host — what makes POST /me/tracker-identities an UPSERT: a second '
    'POST for a host already connected ROTATES the credential and answers 200, and there is no way to '
    'end up with two rows a reader would have to choose between.';
CREATE INDEX tracker_identity_user_idx ON tracker_identity (company_id, user_id);

CREATE TABLE tracker_issue_creation (
    id              UUID        PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    project_id      UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    binding_id      UUID        REFERENCES tracker_binding(id) ON DELETE SET NULL,
    user_id         UUID        NOT NULL,
    execution_id    UUID,
    status          TEXT        NOT NULL
                    CHECK (status IN ('PENDING','CREATED','FAILED')),
    provider        TEXT        NOT NULL,
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
    'and completed after, mirroring notification_delivery. DIAGNOSTIC, NOT RECOVERY: recovery is the '
    'outbox handler''s job (004, The creation seam). It is how you find the orphan an event could not '
    'name — an issue that exists in Jira for an execution the payload got wrong.';
COMMENT ON COLUMN tracker_issue_creation.binding_id IS
    'The binding used. ON DELETE SET NULL rather than CASCADE: unbinding a project must not destroy '
    'the record that an issue was filed through it (D18''s reasoning, applied to the ledger).';
COMMENT ON COLUMN tracker_issue_creation.error IS
    'What the tracker said, redacted through SecretRedaction before it is stored. The natural failure '
    'message quotes the URL that was posted to, and for a credential-bearing call that is the one '
    'string this column must never hold.';
COMMENT ON COLUMN tracker_issue_creation.idempotency_key IS
    'The request''s Idempotency-Key, or NULL when none was sent. Opaque, write-once, never read as '
    'data — as on test_case, doc_page, artifact and report_job. A replayed key answers 200 with the '
    'issue THIS row records rather than filing a second one, which is the failure people actually '
    'hit: a double-clicked "File defect".';
COMMENT ON COLUMN tracker_issue_creation.user_id IS
    'WHO FILED IT. Recorded because under D2 that is a real question with a real answer — the issue '
    'was created with this person''s credential, so the tracker''s own reporter is them — and the '
    'tracker''s answer is only visible to people who can read that tracker.';
CREATE UNIQUE INDEX tracker_issue_creation_idempotency_idx ON tracker_issue_creation (company_id, project_id, user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
COMMENT ON INDEX tracker_issue_creation_idempotency_idx IS
    'Enforces the replay guarantee, and its four columns ARE the lookup''s four predicates — a lookup '
    'narrower than the constraint finds nothing and then violates the index on insert, which is a '
    'validated request answering 500 (V14''s account). user_id is IN the key because the issue is '
    'filed as the caller: two people replaying the same key from the same client are two issues with '
    'two reporters, not one.';
CREATE INDEX tracker_issue_creation_project_idx ON tracker_issue_creation (company_id, project_id, created_at DESC);

CREATE TABLE notification_channel (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    kind         TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    enabled      BOOLEAN     NOT NULL,
    config       JSONB       NOT NULL,
    secret       BYTEA,
    secret_hint  TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_channel_name UNIQUE (company_id, project_id, name)
);
COMMENT ON TABLE notification_channel IS
    'Where one project sends its notices — an incoming webhook or a mail recipient list. '
    'PROJECT-level and never person-level: a per-user digest is a different table with a different '
    'unsubscribe story, and mixing the two would make "who gets told" answerable two ways. Written '
    'only by `integration`; every other module reaches it through an outbox event.';
COMMENT ON COLUMN notification_channel.config IS
    'The kind-specific settings that are NOT credentials — an EMAIL channel''s recipients today. '
    'Anything an operator may read back goes here; anything that authenticates goes in `secret`, '
    'because this column is returned over the wire and that one never is.';
COMMENT ON COLUMN notification_channel.kind IS
    'SLACK, TEAMS or EMAIL — which adapter delivers, and therefore what `config` and `secret` mean. '
    'Three from the start on the plan''s own reasoning for the issue-tracker port: a port with a '
    'single implementation always leaks that implementation''s vocabulary.';
COMMENT ON COLUMN notification_channel.secret IS
    'The webhook URL, AES-GCM ciphertext, key from the environment (plan §6r). Encrypted rather than '
    'digested because unlike an API token this value has to be RECOVERABLE to be used, so the '
    'api_token_lookup trick is not available. NULL for EMAIL, which authenticates as the instance''s '
    'relay and therefore has no credential of its own on the row. Never returned, never in an outbox '
    'payload, never in `notification_delivery.error`.';
COMMENT ON COLUMN notification_channel.secret_hint IS
    'The last few characters of the secret, masked — enough for a person to recognise WHICH webhook '
    'is configured and not enough to post to it. The only part of the credential that ever leaves '
    'this table.';
CREATE INDEX notification_channel_project_idx ON notification_channel (company_id, project_id);

CREATE TABLE notification_subscription (
    id           UUID    PRIMARY KEY,
    company_id   UUID    NOT NULL REFERENCES company(id),
    channel_id   UUID    NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    event_type   TEXT    NOT NULL,
    min_failures INTEGER NOT NULL,
    CONSTRAINT uq_notification_subscription UNIQUE (company_id, channel_id, event_type)
);
COMMENT ON TABLE notification_subscription IS
    'One row per event type a channel wants. A channel with no rows here is configured and silent, '
    'which is a legitimate state and the one a channel is created in.';
COMMENT ON COLUMN notification_subscription.event_type IS
    'The outbox event type, matched by string exactly as OutboxHandler matches it — '
    'execution.run_completed today. TEXT and not an enum: the vocabulary lives in Java (OutboxEvents) '
    'and a CHECK here would be a second copy of it that a migration has to move.';
COMMENT ON COLUMN notification_subscription.min_failures IS
    'How many failures a run must have before this subscription wants to hear about it. 0 is every '
    'run, and is the default. A predicate on the event rather than an event of its own, so that '
    '`execution` never learns what any subscriber considers alarming — see this migration''s header. '
    'Meaningless for event types that carry no failure count, where it stays 0.';
CREATE INDEX notification_subscription_channel_idx ON notification_subscription (company_id, channel_id);
CREATE INDEX notification_subscription_event_idx ON notification_subscription (company_id, event_type);

CREATE TABLE notification_delivery (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    channel_id UUID        NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    event_id   UUID        NOT NULL,
    event_type TEXT        NOT NULL,
    status     TEXT        NOT NULL,
    attempt    INTEGER     NOT NULL,
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_delivery UNIQUE (company_id, channel_id, event_id)
);
COMMENT ON TABLE notification_delivery IS
    'What was attempted, to which channel, and what came back — the operator-facing half, and the '
    'reason the feature is debuggable at all. It is ALSO the idempotency record: outbox delivery is '
    'at least once, so a handler that already posted has to be able to tell, and (company_id, '
    'channel_id, event_id) is the key it tells by.';
COMMENT ON COLUMN notification_delivery.attempt IS
    'How many times THIS channel has been tried for THIS occurrence. The outbox''s own attempt count '
    'covers the whole event across every channel, and a fan-out of three where one webhook is dead '
    'makes those two numbers different — this is the one an operator is asking about.';
COMMENT ON COLUMN notification_delivery.error IS
    'The failure, REDACTED. A failed POST''s message routinely quotes the URL it was posted to, and '
    'that URL is the credential — so this column is exactly where a webhook secret leaks into '
    'something an operator browses and a backup copies. Redaction has its own test, because a leak '
    'here is invisible in every green run.';
COMMENT ON COLUMN notification_delivery.event_id IS
    'The occurrence, minted by the producer and carried in the outbox payload. Not the outbox row''s '
    'id: a handler is deliberately given no row identity, so that it cannot mutate the delivery state '
    'of the row being delivered from inside the delivery.';
COMMENT ON COLUMN notification_delivery.status IS
    'SENT or FAILED. A row exists as soon as an attempt is made, so a channel that has never been '
    'reachable is visible here rather than absent — an empty history and a broken webhook must not '
    'look the same.';
CREATE INDEX notification_delivery_channel_idx ON notification_delivery (company_id, channel_id, created_at DESC);

-- =============================================================================
-- SHARED
-- =============================================================================

CREATE TABLE audit_event (
    id                UUID        PRIMARY KEY,
    company_id        UUID        NOT NULL REFERENCES company(id),
    project_id        UUID,
    actor_user_id     UUID        REFERENCES app_user(id),
    actor_token_id    UUID        REFERENCES api_token(id),
    action            TEXT        NOT NULL,
    target_type       TEXT        NOT NULL,
    target_ids        uuid[]      NOT NULL,
    changes           JSONB,
    bulk_operation_id UUID,
    ip                INET,
    user_agent        TEXT,
    request_id        TEXT,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_event IS
    'Append-only, NEVER updated. Emitted from domain events so ingestion and scheduled jobs are '
    'audited identically to UI actions (plan §6j).';
COMMENT ON COLUMN audit_event.target_ids IS
    'An ARRAY so one event covers a bulk operation over 143 cases — the audit log reads "Ana attached '
    '2 conditions to 143 cases", not 143 lines (plan §6f).';
CREATE INDEX audit_event_actor_idx ON audit_event (company_id, actor_user_id, occurred_at DESC);
CREATE INDEX audit_event_ids_idx ON audit_event USING gin (target_ids);
CREATE INDEX audit_event_target_idx ON audit_event (company_id, target_type, occurred_at DESC);

CREATE TABLE outbox (
    id              UUID        PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    UUID,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','SENT','FAILED','DEAD')),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE outbox IS
    'Transactional outbox. A failing Slack webhook must never roll back a test result (plan §5).';
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE (status = 'PENDING');

CREATE TABLE ingestion_job (
    id              UUID        PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    project_id      UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id          UUID        REFERENCES test_run(id),
    format          TEXT        NOT NULL
                    CHECK (format IN ('JUNIT','TESTNG','ALLURE','CUCUMBER')),
    idempotency_key TEXT        NOT NULL,
    raw_payload     JSONB,
    raw_storage_key TEXT,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    matched_count   INTEGER     NOT NULL DEFAULT 0,
    unmatched_count INTEGER     NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ingestion_job_completed_idempotency_key_idx ON ingestion_job (company_id, idempotency_key)
    WHERE status = 'COMPLETED';
COMMENT ON INDEX ingestion_job_completed_idempotency_key_idx IS
    'CI retries must not double-post results (plan §5) — and a REJECTED report must not stop the '
    'corrected retry, which is why this is partial on COMPLETED rather than the table constraint V1 '
    'declared. A failed ingestion is recorded, carries its key, and consumes nothing.';

CREATE TABLE automation_key_mapping (
    id                   UUID        PRIMARY KEY,
    company_id           UUID        NOT NULL REFERENCES company(id),
    project_id           UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    normalized_test_name TEXT        NOT NULL,
    case_id              UUID        NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    confirmed_by         UUID        REFERENCES app_user(id),
    confirmed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_automation_mapping UNIQUE (company_id, normalized_test_name)
);
COMMENT ON TABLE automation_key_mapping IS
    'Learned mapping. Once a human confirms "this test name means this case", the mapping survives '
    'renames of everything except the test itself (plan §6c).';

-- =============================================================================
-- INSTANCE-LEVEL TABLES
--
-- No company_id, deliberately. These are on the schema test's exception list
-- (ERD §0.1) and nothing may be added to that list without a decision.
-- =============================================================================

CREATE TABLE instance_setting (
    key        TEXT        PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID        REFERENCES app_user(id)
);

CREATE TABLE instance_audit_event (
    id            UUID        PRIMARY KEY,
    actor_user_id UUID        REFERENCES app_user(id),
    action        TEXT        NOT NULL,
    company_id    UUID        REFERENCES company(id),
    target_type   TEXT,
    target_ids    uuid[],
    changes       JSONB,
    ip            INET,
    user_agent    TEXT,
    request_id    TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE instance_audit_event IS
    'Operator actions that span or target companies: provisioning, suspension, admin appointment, '
    'cross-client user-directory access. Deliberately separate from the per-company audit_event.';
COMMENT ON COLUMN instance_audit_event.company_id IS
    'The company acted upon, where applicable. Operator access INTO a company is recorded in BOTH '
    'logs — here and in that company''s own audit_event, flagged as instance-operator access. Because '
    'companies are separate clients, "has anyone outside our team looked at this?" must have a real '
    'answer (plan §6b, §6s).';
CREATE INDEX instance_audit_event_actor_idx ON instance_audit_event (actor_user_id, occurred_at DESC);
CREATE INDEX instance_audit_event_company_idx ON instance_audit_event (company_id, occurred_at DESC);


-- =============================================================================
-- FUNCTIONS & TRIGGERS
-- =============================================================================


-- ltree labels accept only alphanumerics and underscore, so "UI / E2E" cannot be
-- a path label as written. Slugged here rather than at each call site, so the
-- path a value gets is a property of the schema and not of whoever seeded it.
CREATE FUNCTION tcms_ltree_label(p_name TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT trim(both '_' from regexp_replace(lower(p_name), '[^a-z0-9]+', '_', 'g'));
$$;

-- =============================================================================
-- THE SEEDED VOCABULARIES, AS FUNCTIONS
-- =============================================================================
--
-- WHY FUNCTIONS AND NOT INSERTs. Classification dimensions, execution statuses
-- and the default configuration are COMPANY-OWNED data, and:
--
--   * there are no companies at migration time, so there is nothing to seed
--     against. Each client gets its own COPY of the vocabulary at provisioning
--     time, because one client renaming "Compatibility" must not rename it for
--     another (plan §6d, §6s);
--   * every one of these tables has FORCE ROW LEVEL SECURITY, so an INSERT
--     needs app.current_company set — including for the migration role, which
--     FORCE covers too. A migration that set the GUC to seed a placeholder
--     company would be encoding a fixture as schema.
--
-- So the vocabulary content lives here — versioned, reviewable, changed through a
-- migration like everything else — and provision-company calls these once per
-- company and once per project. Callers must already hold the company context;
-- both functions refuse to run without it rather than silently writing rows that
-- the policy will then hide.
CREATE FUNCTION tcms_assert_company_context(p_company_id UUID) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_active TEXT := current_setting('app.current_company', true);
BEGIN
    IF v_active IS NULL OR v_active = '' THEN
        RAISE EXCEPTION 'no company context is active — open one before seeding company-owned rows'
            USING ERRCODE = '42501';
    END IF;
    IF v_active::uuid <> p_company_id THEN
        RAISE EXCEPTION 'company context is % but the seed targets % — RLS would hide every row written',
            v_active, p_company_id
            USING ERRCODE = '42501';
    END IF;
END $$;

-- =============================================================================
-- Which companies an account may open a session in
--
-- SECURITY INVOKER, deliberately, and it is the one function whose security model
-- has been paid for once already. THE OBVIOUS SPELLING IS SECURITY DEFINER owned
-- by tcms_owner, on the reasoning that the owner's rights see past the RLS policy
-- on company_membership. THEY DO NOT. company_membership carries FORCE ROW LEVEL
-- SECURITY, and FORCE exists precisely so the TABLE OWNER is subject to its own
-- policies — the first of the two RLS traps this project has documented since M0.
-- A definer's-rights version matched the policy against an unset
-- app.current_company and returned ZERO rows: every login produced a token with no
-- `co` claim, every session ran context-less, and /me/permissions answered with an
-- empty set for a Company Admin.
--
-- Fail-closed, so never a leak. But the first person to sign in to a new instance
-- is its administrator, and they found an application in which they could do
-- nothing at all, with no error anywhere to explain it.
--
-- IT SHIPPED PAST 167 GREEN TESTS, because the suite migrated as the container's
-- default role, a SUPERUSER, and a superuser bypasses RLS unconditionally — FORCE
-- included — so under test the definer's rights really did see past the policy.
-- The harness now runs the deployment's own two-role split for exactly this
-- reason; see backend/src/test/resources/db/testinit/tcms-roles.sql.
--
-- So there are no definer's rights left to bound. The function runs as its CALLER,
-- the unprivileged tcms_app, and walks the ACTIVE companies — `company` is
-- instance-level and carries no policy — setting app.current_company for each and
-- asking the ordinary, fully-policed question. The cost is one indexed
-- EXISTS-shaped probe per ACTIVE company per login. A self-hosted instance serves
-- a handful of client companies; if that ever stops being true, the answer is an
-- instance-level index of the membership fact, not a bypass.
--
-- THE GUC IS RESTORED AT THE END, AND THAT MATTERS. set_config(..., is_local =>
-- true) is transaction-scoped and this runs inside the caller's transaction, so
-- without the restore it would leave app.current_company pointing at whichever
-- company the loop happened to finish on. The login path has no company open, but
-- a future caller might, and a function that silently repoints the tenancy
-- discriminator of its caller's transaction is the exact shape of a cross-company
-- read that looks completely ordinary afterwards.
-- =============================================================================
CREATE FUNCTION tcms_companies_for_user(p_user_id uuid)
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

-- doc_page — the one that CANNOT be generated. A page's text is in doc_block
-- rows, and a generation expression may only read its own row. Trigger-maintained
-- from two directions, which is exactly the fragility a generated column avoids
-- and the reason the other three do not use this path.
CREATE FUNCTION tcms_doc_page_search_vector(p_page_id UUID) RETURNS TSVECTOR
    LANGUAGE sql STABLE AS $$
    SELECT setweight(to_tsvector('english'::regconfig, coalesce(p.title, '')), 'A')
           || setweight(
                coalesce(
                    (SELECT jsonb_to_tsvector('english'::regconfig,
                                              jsonb_agg(b.content), '["string"]'::jsonb)
                       FROM doc_block b
                      WHERE b.page_id = p.id),
                    ''::tsvector),
                'B')
      FROM doc_page p
     WHERE p.id = p_page_id;
$$;

CREATE FUNCTION tcms_doc_page_search_vector_trigger() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- INSERT: no blocks exist yet, so this is title-only and the block trigger
    -- fills in the rest as content arrives. UPDATE OF title: blocks are re-read.
    NEW.search_vector :=
        setweight(to_tsvector('english'::regconfig, coalesce(NEW.title, '')), 'A')
        || setweight(
             coalesce((SELECT jsonb_to_tsvector('english'::regconfig,
                                               jsonb_agg(b.content), '["string"]'::jsonb)
                         FROM doc_block b WHERE b.page_id = NEW.id),
                      ''::tsvector),
             'B');
    RETURN NEW;
END $$;

CREATE FUNCTION tcms_doc_block_search_vector_trigger() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_page_id UUID := coalesce(NEW.page_id, OLD.page_id);
BEGIN
    -- Updates search_vector and NOTHING else, which is what keeps this from
    -- recursing: the doc_page trigger above fires only on INSERT or on an UPDATE
    -- naming the title column.
    UPDATE doc_page
       SET search_vector = tcms_doc_page_search_vector(v_page_id)
     WHERE id = v_page_id;
    RETURN NULL;
END $$;

-- =============================================================================
-- SINGLE-CARDINALITY CLASSIFICATION
-- =============================================================================
--
-- ERD §538: enforcing "one value per SINGLE dimension" needs
-- UNIQUE (case_id, dimension_id) WHERE <dimension is SINGLE>, and a partial-index
-- predicate cannot reach classification_dimension. Hence a trigger.
--
-- It also closes a hole the denormalization itself opens, which is the more
-- interesting half: case_classification.dimension_id is a COPY of
-- classification_value.dimension_id, so a caller that writes a MULTI dimension's
-- id alongside a SINGLE dimension's value bypasses the check entirely and leaves
-- the row lying about which axis it classifies. The copy is verified here rather
-- than trusted.
CREATE FUNCTION tcms_enforce_classification_integrity() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_value_dimension_id UUID;
    v_value_company_id   UUID;
    v_cardinality        TEXT;
    v_conflicting        INT;
BEGIN
    SELECT dimension_id, company_id
      INTO v_value_dimension_id, v_value_company_id
      FROM classification_value
     WHERE id = NEW.value_id;

    IF v_value_dimension_id IS NULL THEN
        -- Under RLS a foreign-company value is invisible rather than absent, so
        -- this covers both. The FK alone cannot: it is checked past the policy.
        RAISE EXCEPTION 'classification value % is not visible in this company', NEW.value_id
            USING ERRCODE = '23503';
    END IF;

    IF v_value_company_id <> NEW.company_id THEN
        RAISE EXCEPTION 'classification value % belongs to company %, not %',
            NEW.value_id, v_value_company_id, NEW.company_id
            USING ERRCODE = '23514';
    END IF;

    IF NEW.dimension_id <> v_value_dimension_id THEN
        RAISE EXCEPTION
            'case_classification.dimension_id (%) does not match the value''s dimension (%) — '
            'the denormalized copy must agree, or the SINGLE-cardinality check below is bypassable',
            NEW.dimension_id, v_value_dimension_id
            USING ERRCODE = '23514';
    END IF;

    SELECT cardinality INTO v_cardinality
      FROM classification_dimension WHERE id = v_value_dimension_id;

    IF v_cardinality <> 'SINGLE' THEN
        RETURN NEW;
    END IF;

    -- Serialise classification changes for this case. Two concurrent inserts
    -- would otherwise each see zero existing rows and both commit; there is no
    -- row to lock for a value that does not exist yet, so the parent is the lock.
    PERFORM 1 FROM test_case WHERE id = NEW.case_id FOR NO KEY UPDATE;

    SELECT count(*) INTO v_conflicting
      FROM case_classification
     WHERE case_id = NEW.case_id
       AND dimension_id = NEW.dimension_id
       AND value_id <> NEW.value_id;

    IF v_conflicting > 0 THEN
        RAISE EXCEPTION
            'case % already has a value for SINGLE-cardinality dimension % — replace it, do not add',
            NEW.case_id, NEW.dimension_id
            USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
END $$;

-- =============================================================================
-- TEAM HIERARCHY — CYCLES AND COMPANY CONSISTENCY
-- =============================================================================
--
-- ERD §217. A cycle breaks the org-chart render and hangs any recursive query,
-- and parent_team_id crossing a company boundary would let one client's team
-- appear inside another's org chart — an isolation leak that RLS cannot catch,
-- because both rows are legitimately readable by whoever wrote the edge.
CREATE FUNCTION tcms_enforce_team_hierarchy() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_parent_company_id UUID;
    v_cycle_depth       INT;
BEGIN
    IF NEW.parent_team_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.parent_team_id = NEW.id THEN
        RAISE EXCEPTION 'team % cannot be its own parent', NEW.id USING ERRCODE = '23514';
    END IF;

    SELECT company_id INTO v_parent_company_id FROM team WHERE id = NEW.parent_team_id;

    IF v_parent_company_id IS NULL THEN
        RAISE EXCEPTION 'parent team % is not visible in this company', NEW.parent_team_id
            USING ERRCODE = '23503';
    END IF;

    IF v_parent_company_id <> NEW.company_id THEN
        RAISE EXCEPTION 'team nesting may not cross companies: parent % is in company %, child in %',
            NEW.parent_team_id, v_parent_company_id, NEW.company_id
            USING ERRCODE = '23514';
    END IF;

    -- The depth guard is not paranoia about our own writes: if a cycle ever DOES
    -- get in (a direct psql edit, a future migration), an unbounded recursive CTE
    -- here would hang the transaction that tries to fix it.
    WITH RECURSIVE ancestors AS (
        SELECT t.id, t.parent_team_id, 1 AS depth
          FROM team t WHERE t.id = NEW.parent_team_id
        UNION ALL
        SELECT t.id, t.parent_team_id, a.depth + 1
          FROM team t JOIN ancestors a ON t.id = a.parent_team_id
         WHERE a.depth < 64
    )
    SELECT coalesce(max(depth), 0) INTO v_cycle_depth FROM ancestors WHERE id = NEW.id;

    IF v_cycle_depth > 0 THEN
        RAISE EXCEPTION 'team % is already an ancestor of %, so this edge would create a cycle',
            NEW.id, NEW.parent_team_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END $$;

-- =============================================================================
-- The grant tables refuse a row whose shape disagrees with its role's scope
--
-- Without these triggers the agreement between role.scope and the shape of a
-- grant is enforced in exactly one place: RoleGrantService.scopeError, an
-- application-layer refusal on the one HTTP path that writes role_assignment.
-- Every other writer — a seed function, a hand-run INSERT, a future migration —
-- could then produce the two rows the model has no meaning for, and the evaluator
-- would read both without noticing:
--
--   * a COMPANY-scope role paired with a project_id. Two silent consequences,
--     and the second is the expensive one: findProjectScopedHeldPermissions
--     confers the role's keys as though they were project permissions (it
--     deliberately never joins role), and hasProjectScopedAssignment counts the
--     row as "this project said otherwise" — REPLACING the caller's inherited
--     company-wide default with whatever the row grants. A row meant to widen
--     quietly removes.
--
--   * any non-SYSTEM role in instance_role_assignment. findSystemHeldPermissions
--     confers every key of whatever role the row names, scope unread, so a
--     PROJECT-scope role written there becomes an instance-wide grant of its
--     keys — reachable with no company gate, because the SYSTEM term is
--     deliberately in front of it (PermissionEvaluator's header says why).
--     The mirror case, a SYSTEM role in role_assignment, is the one
--     RoleGrantService already refuses with a 400: instance-wide powers scoped
--     to one company is a tier that does not exist in the model.
--
-- Neither row is writable over HTTP. These triggers are defence in depth of the
-- same kind the repository headers describe for the company_id predicates: the
-- application refusal is the intent, the trigger is the guarantee, and the two
-- agreeing is what makes a drift in either one loud instead of silent.
--
-- WHY TRIGGERS AND NOT CONSTRAINTS
--
-- The fact being checked (role.scope) lives in another table, which a CHECK
-- cannot reach. The declarative alternative — a denormalized role_scope column
-- with a composite FK onto role (id, scope) and a CHECK over it — would have to
-- be populated by every writer: the entity, both seed functions, provisioning.
-- That is per-writer remembering, which is the failure mode this file exists to
-- end. role.scope is immutable in practice (seeded reference data with no UPDATE
-- path in the product), so the cross-row race a trigger cannot see has no writer
-- to race with; V2's classification and team-hierarchy triggers stand on the
-- same argument.
--
-- The functions read only role — reference data, not under RLS — so they work
-- identically for tcms_app, the migration role, and a hand-run psql session.
-- =============================================================================
CREATE FUNCTION tcms_role_assignment_scope_shape() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    granted_scope TEXT;
BEGIN
    SELECT scope INTO granted_scope FROM role WHERE id = NEW.role_id;
    IF granted_scope = 'SYSTEM' THEN
        RAISE EXCEPTION
            'role % is SYSTEM-scope; its grants live in instance_role_assignment, never here',
            NEW.role_id
            USING ERRCODE = '23514';
    END IF;
    IF granted_scope = 'COMPANY' AND NEW.project_id IS NOT NULL THEN
        RAISE EXCEPTION
            'role % is COMPANY-scope, so its grant covers every project and may not name one',
            NEW.role_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION tcms_instance_role_assignment_scope_shape() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    granted_scope TEXT;
BEGIN
    SELECT scope INTO granted_scope FROM role WHERE id = NEW.role_id;
    IF granted_scope IS DISTINCT FROM 'SYSTEM' THEN
        RAISE EXCEPTION
            'role % is %-scope; instance_role_assignment holds SYSTEM grants only — company and '
            'project grants live in role_assignment, under RLS',
            NEW.role_id, granted_scope
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

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

-- =============================================================================
-- THE COMPANY-MEMBER BASELINE, AS THE ONE STATEMENT THREE CALLERS SHARE
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

-- =============================================================================
-- A provisioned company must be able to allocate its first display key
--
-- Five entity types carry a company-scoped display key — TC- TS- TP- TR- DOC-
-- (plan §6t, ERD "key_sequence — display-key allocation, per company"). The
-- allocator the ERD specifies is deliberately a single statement, so concurrent
-- creation cannot issue a duplicate:
--
--     UPDATE key_sequence SET next_value = next_value + :n
--      WHERE company_id = :c AND entity_type = :t
--     RETURNING next_value - :n;
--
-- Seeding those rows is this function's job, and it was once missing: a company
-- provisioned without them has no counter, so the statement above matches zero
-- rows and returns nothing.
--
-- It fails closed, and it fails in the shape that invites the wrong repair. An UPDATE ... RETURNING that returns no row is not an error — it is an
-- empty result — so the caller sees "could not allocate a key" and the tempting fix
-- is MAX(key_seq) + 1, which is exactly the racing allocator the ERD rejects. The
-- second tempting fix is an INSERT ... ON CONFLICT at allocation time, which puts a
-- write on the hot path of every case creation forever to cover a one-time setup
-- step. Seeding at provisioning is the cheap correct place.
--
-- WHY HERE AND NOT IN THE CLI: the same argument as the vocabularies above. These
-- are company-owned rows under FORCE ROW LEVEL SECURITY, they are product constants
-- rather than operator input, and they should be versioned in a migration and
-- reviewed like schema. Putting them in Java would also mean a second provisioning
-- path — a test fixture, a future restore tool — could create a company that looks
-- complete and cannot allocate a key.
--
-- The prefixes are NOT configurable per company. A client renaming TC- to TEST-
-- would break every key already printed in a report, linked in a ticket and pasted
-- into a chat, and display keys are immutable by contract (plan §6t).
--
-- Idempotent throughout, so an existing company is topped up by a re-run of
-- provision-company rather than needing a backfill.
-- =============================================================================
CREATE FUNCTION tcms_seed_company_defaults(p_company_id UUID) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_dimension_id UUID;
    v_value        TEXT;
    v_position     INT;
    v_spec         RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    -- Test Type and Test Level are SEPARATE axes on purpose (plan §6d): an API
    -- test is almost always functional, and a non-functional API test is a
    -- performance test at the API layer. One enum forces a false choice and
    -- destroys the type × level coverage matrix.
    --
    -- All three are locked = true: a client editing them is fine, but reporting
    -- comparability across that client's projects is the reason they are
    -- company-level rather than project-level, and unlocking them per project
    -- would give that away. Feature is per-project and unlocked
    -- (tcms_seed_project_defaults).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('test_type', 'Test Type', 'SINGLE', 0,
             ARRAY['Functional','Performance','Security','Usability',
                   'Accessibility','Compatibility','Localization','Recovery']),
            ('test_level', 'Test Level', 'SINGLE', 1,
             ARRAY['Unit','Component','API','Integration','UI / E2E','Database','Contract']),
            ('execution_phase', 'Execution Phase', 'MULTI', 2,
             ARRAY['Smoke','Sanity','Regression','Acceptance','Exploratory'])
        ) AS s(key, name, cardinality, position, values)
    LOOP
        SELECT id INTO v_dimension_id
          FROM classification_dimension
         WHERE company_id = p_company_id AND project_id IS NULL AND key = v_spec.key;

        IF v_dimension_id IS NULL THEN
            v_dimension_id := gen_random_uuid();
            INSERT INTO classification_dimension
                (id, company_id, project_id, key, name, cardinality,
                 controlled, hierarchical, locked, position)
            VALUES
                (v_dimension_id, p_company_id, NULL, v_spec.key, v_spec.name, v_spec.cardinality,
                 true, false, true, v_spec.position);
        END IF;

        v_position := 0;
        FOREACH v_value IN ARRAY v_spec.values LOOP
            INSERT INTO classification_value
                (id, company_id, dimension_id, parent_id, path, name, color, position)
            SELECT gen_random_uuid(), p_company_id, v_dimension_id, NULL,
                   tcms_ltree_label(v_value)::ltree, v_value, NULL, v_position
             WHERE NOT EXISTS (
                SELECT 1 FROM classification_value
                 WHERE dimension_id = v_dimension_id AND name = v_value);
            v_position := v_position + 1;
        END LOOP;
    END LOOP;

    -- The five display-key counters (V5). next_value starts at 1, so the first
    -- allocation issues TC-1 and not TC-0 — the keys are user-facing and 1-based
    -- (plan §6t). No zero-padding anywhere: key_seq owns ordering, key is display
    -- only, and TC-10000 sorting before TC-9999 as text is why those are separate
    -- columns at all.
    --
    -- There is deliberately NO row for executions, conditions, shared steps,
    -- defects, reviews, artifacts or configurations. Each is reached from its
    -- parent, and every extra prefix is another counter to contend on and another
    -- ambiguous token in search. The CHECK constraint on entity_type is the list.
    INSERT INTO key_sequence (company_id, entity_type, prefix, next_value)
    SELECT p_company_id, s.entity_type, s.prefix, 1
      FROM (VALUES
            ('CASE',     'TC-'),
            ('SUITE',    'TS-'),
            ('PLAN',     'TP-'),
            ('RUN',      'TR-'),
            ('DOC_PAGE', 'DOC-')
      ) AS s(entity_type, prefix)
    ON CONFLICT (company_id, entity_type) DO NOTHING;
END $$;

-- The seed function, reproduced IN FULL rather than patched, for V18's reason:
-- CREATE OR REPLACE replaces the whole body, so anything omitted here stops being
-- seeded, silently, for every project created from now on. Only the status list
-- differs from V18's — one row added at position 6.
CREATE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    IF NOT EXISTS (SELECT 1 FROM project WHERE id = p_project_id) THEN
        RAISE EXCEPTION 'project % is not visible in company %', p_project_id, p_company_id
            USING ERRCODE = '23503';
    END IF;

    -- Feature is the one PROJECT-scoped dimension, and the only hierarchical and
    -- unlocked one: two client products have different feature trees, and the
    -- tree is meant to be edited (plan §6d). Seeded empty — inventing a client's
    -- feature taxonomy for them is how a controlled vocabulary gets worked around.
    INSERT INTO classification_dimension
        (id, company_id, project_id, key, name, cardinality, controlled, hierarchical, locked, position)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'feature', 'Feature',
           'MULTI', true, true, false, 3
     WHERE NOT EXISTS (
        SELECT 1 FROM classification_dimension
         WHERE company_id = p_company_id AND project_id = p_project_id AND key = 'feature');

    -- The seven default execution statuses. `kind` is what lets reporting aggregate
    -- a project's custom statuses without knowing their names, and counts_as_pass
    -- is separate from kind because a project may add a "Passed with comment".
    -- Colours match the frontend design tokens deliberately: status colour is
    -- load-bearing data in a TCMS and is NOT brandable (§6u).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Passed',   'PASSED',   '#2e7d5b', true,  true,  0),
            ('Failed',   'FAILED',   '#c0392b', true,  false, 1),
            ('Blocked',  'BLOCKED',  '#b7791f', true,  false, 2),
            ('Skipped',  'SKIPPED',  '#6b7280', true,  false, 3),
            ('Retest',   'RETEST',   '#7c5cbf', false, false, 4),
            ('Untested', 'UNTESTED', '#9ca3af', false, false, 5),
            ('WIP',      'WIP',      '#2563eb', false, false, 6)
        ) AS s(name, kind, color, is_final, counts_as_pass, position)
    LOOP
        INSERT INTO case_status
            (id, company_id, project_id, name, kind, color, is_final, counts_as_pass, position)
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind,
               v_spec.color, v_spec.is_final, v_spec.counts_as_pass, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM case_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- Every project gets a Default configuration so test_run.configuration_id can
    -- stay NOT NULL all the way through the rollup read models — suite_run_summary
    -- and plan_snapshot key on it, and a nullable PK column is not expressible.
    INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Default', '{}'::jsonb, true
     WHERE NOT EXISTS (
        SELECT 1 FROM configuration WHERE project_id = p_project_id AND is_default);

    -- V18. One documentation space, for the same structural reason as the
    -- configuration above: doc_page.space_id is NOT NULL, so without this row the
    -- project cannot hold a page at all.
    --
    -- It is NOT the "inventing the client's structure" mistake that keeps a
    -- Default *suite* from being seeded here. A suite is a decision about how
    -- somebody's tests are organised and appears in their tree; the space is a
    -- container the API does not expose, has no name anybody reads, and holds
    -- every page whatever its shape. The tree the client actually organises is
    -- doc_page.parent_id, and this seeds none of that.
    INSERT INTO doc_space (id, company_id, project_id, name)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Documentation'
     WHERE NOT EXISTS (
        SELECT 1 FROM doc_space WHERE project_id = p_project_id);
END $$;


COMMENT ON FUNCTION tcms_companies_for_user(p_user_id uuid) IS
    'Which companies an account may open a session in — ids only, ACTIVE memberships of ACTIVE '
    'companies. Runs as the CALLER with RLS fully in force: it walks the active companies and asks '
    'the ordinary policed question for each, rather than bypassing the policy. V7 used SECURITY '
    'DEFINER and returned nothing in production, because FORCE ROW LEVEL SECURITY covers the table '
    'owner too; see the V8 header. Callers must pass an already-authenticated account''s id, never one '
    'taken from a request.';

COMMENT ON FUNCTION tcms_doc_page_search_vector(p_page_id uuid) IS
    'One recompute used by both triggers below, so the page-side and block-side paths can never '
    'produce different vectors for the same page.';

COMMENT ON FUNCTION tcms_enforce_team_hierarchy() IS
    'Cycle + company-consistency check on team.parent_team_id (ERD §217). Permissions never cascade '
    'through this edge — it is org-chart structure only — so this protects rendering and recursive '
    'queries, not authorization.';

COMMENT ON FUNCTION tcms_grant_company_read_baseline(p_company_id uuid) IS
    'Grants the Company Member baseline (company:read, COMPANY scope) to every non-REMOVED member of '
    'one company that does not already hold it, and returns how many rows it wrote. Idempotent, so '
    'provision-company can call it on every run and invitation acceptance can call it again. Refuses '
    'to run without the matching company context, because RLS would otherwise hide every row it wrote '
    '(V2 §4).';

COMMENT ON FUNCTION tcms_resolve_identity_provider(p_provider_id uuid) IS
    'Which company, issuer and client an identity provider id belongs to, for the SSO authorize leg '
    'where no session and no company context exist yet. Runs as the CALLER with RLS fully in force '
    '(V8''s shape, for V8''s reason -- see the V53 header). Returns `enabled` rather than filtering on '
    'it, so a disabled provider refuses on the same code path as every other refusal. Reads nothing '
    'about any user.';

COMMENT ON FUNCTION tcms_resolve_providers_for_domain(p_domain text) IS
    'Which live sign-in routes an email domain reaches, for the pre-authentication discovery call '
    'where no session and no company context exist yet. Runs as the CALLER with RLS fully in force '
    '(V8''s shape). Returns the UNION across every company claiming the domain, carrying no company id '
    'and no company name, so the answer cannot be read as which clients exist on this instance (§6r). '
    '`kind` says which CONTROL to render and nothing about who operates the route.';

COMMENT ON FUNCTION tcms_seed_company_defaults(p_company_id uuid) IS
    'Called once per company by provision-company, and by nothing else. Seeds the classification '
    'vocabularies and the five display-key counters. Idempotent: re-running adds only what is '
    'missing, so a company provisioned before a vocabulary or a counter was added can be topped up by '
    're-running the CLI rather than by a backfill migration.';

COMMENT ON FUNCTION tcms_seed_project_defaults(p_company_id uuid, p_project_id uuid) IS
    'Called once per project, by project creation and by provision-company for the first project. '
    'Idempotent, and refuses a project it cannot see — which under RLS is also a project in another '
    'company. Seeds the Feature dimension, the seven case statuses (V32 added WIP), the Default '
    'configuration and (V18) the documentation space.';


REVOKE ALL ON FUNCTION tcms_companies_for_user(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION tcms_resolve_identity_provider(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION tcms_resolve_providers_for_domain(text) FROM PUBLIC;

CREATE TRIGGER team_hierarchy_integrity
    BEFORE INSERT OR UPDATE OF parent_team_id, company_id ON team
    FOR EACH ROW EXECUTE FUNCTION tcms_enforce_team_hierarchy();

CREATE TRIGGER role_assignment_scope_shape
    BEFORE INSERT OR UPDATE OF role_id, project_id ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION tcms_role_assignment_scope_shape();

CREATE TRIGGER instance_role_assignment_scope_shape
    BEFORE INSERT OR UPDATE OF role_id ON instance_role_assignment
    FOR EACH ROW EXECUTE FUNCTION tcms_instance_role_assignment_scope_shape();

CREATE TRIGGER case_classification_integrity
    BEFORE INSERT OR UPDATE ON case_classification
    FOR EACH ROW EXECUTE FUNCTION tcms_enforce_classification_integrity();

CREATE TRIGGER doc_page_search_vector
    BEFORE INSERT OR UPDATE OF title ON doc_page
    FOR EACH ROW EXECUTE FUNCTION tcms_doc_page_search_vector_trigger();

CREATE TRIGGER doc_block_search_vector
    AFTER INSERT OR DELETE OR UPDATE ON doc_block
    FOR EACH ROW EXECUTE FUNCTION tcms_doc_block_search_vector_trigger();


-- =============================================================================
-- ROW-LEVEL SECURITY
--
-- Defence in depth, ON BY DEFAULT because companies are separate clients
-- (plan §1, §6s). The Hibernate filter is the primary mechanism; this is the
-- guarantee that the database will not serve another client's rows even if the
-- application forgets. Disable per deployment if the planner cost is measured
-- and unacceptable — but the shipped default is on.
--
-- The application sets the GUC once per transaction:
--     SET LOCAL app.current_company = '<uuid>';
--
-- Two properties this rests on, both asserted by RlsIsolationTest rather than
-- reasoned about, because both failure modes are silent:
--   1. The app role must NOT be BYPASSRLS and must not own these tables. FORCE
--      ROW LEVEL SECURITY covers the owner, and the owner is a non-superuser —
--      see deploy/postgres/initdb/10-tcms-roles.sql for why the split exists.
--   2. An absent company context must yield ZERO rows, not all rows.
--
-- THERE ARE TWO SPELLINGS OF "ABSENT", and the policy has to collapse both. On a
-- connection where the GUC was never set, current_setting(..., true) returns
-- NULL and `company_id = NULL` is never true, which is the property wanted. But
-- Postgres does not return a custom GUC to undefined when a transaction ends:
-- once it has been assigned even once — including by the set_config(..., true)
-- that CompanyAwareTransactionManager issues per transaction — its reset value
-- for the rest of the SESSION is the EMPTY STRING, and ''::uuid raises
--
--     ERROR:  invalid input syntax for type uuid: ""
--
-- Measured on Postgres 17.10, not inferred. It fails CLOSED, so it was never a
-- leak; what it was is a 500 on every legitimately context-less read on any
-- pooled connection that had already served a company — the login path, the
-- provisioning CLI, an operator query, a report job that forgets its context.
-- Never on the first request, always afterwards. nullif(..., '') collapses both
-- spellings onto NULL. The predicate stays a bare equality: widening it to admit
-- NULLs was considered and rejected, because a context-less query would then
-- return rows and property 2 would be traded away to fix property 2.
--
-- The list is explicit rather than derived from "has a company_id", and the loop
-- refuses to run if it names a table that does not exist. Four tables carry a
-- company_id and are deliberately NOT on it. Three are read BEFORE any company
-- context can exist — membership_invitation is redeemed before authentication,
-- api_token_lookup answers "which company issued this digest" for a credential
-- presented before a session, and sso_login_attempt resolves an OIDC callback
-- that carries only a `state` — so under RLS each would be permanently
-- unreachable — the defect that made the schema owner a non-superuser. The fourth, instance_audit_event, records
-- operator actions ACROSS companies and is instance-level by intent.
-- SchemaConventionTest holds that exception list with the reasoning; ERD §0.1
-- records the decision. Nothing may be added to it without one.
-- =============================================================================


DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY[
        'company_membership', 'team', 'team_membership', 'project', 'key_sequence',
        'role_assignment', 'api_token', 'identity_provider', 'company_email_domain',
        'user_identity', 'project_settings', 'classification_dimension',
        'classification_value', 'case_status', 'suite', 'shared_step', 'condition',
        'test_case', 'suite_case', 'suite_tag_rule', 'case_automation_tag',
        'test_case_draft', 'test_case_version', 'test_step', 'case_condition',
        'suite_condition', 'case_condition_optout', 'case_classification', 'case_tag',
        'attachment', 'ai_proposal', 'test_plan', 'plan_scope_item',
        'test_plan_version', 'suite_version', 'configuration', 'test_run',
        'run_schedule', 'test_execution', 'step_result', 'evidence', 'defect_link',
        'doc_space', 'doc_page', 'doc_block', 'doc_page_version', 'doc_link',
        'case_doc_link', 'artifact', 'review', 'review_assignment',
        'review_participation', 'review_comment', 'work_item', 'work_item_link',
        'derived_item_annotation', 'execution_fact', 'suite_run_summary',
        'plan_snapshot', 'case_health', 'case_flake', 'report_definition',
        'report_artifact', 'report_job', 'tracker_binding', 'tracker_identity',
        'tracker_issue_creation', 'notification_channel', 'notification_subscription',
        'notification_delivery', 'audit_event', 'outbox', 'ingestion_job',
        'automation_key_mapping'
    ];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    -- Asserted, not logged. A loop that silently covered fewer tables than the
    -- schema has would leave the gap open while reporting success.
    IF array_length(protected, 1) <> 74 THEN
        RAISE EXCEPTION 'expected 74 company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;


-- =============================================================================
-- REFERENCE DATA — the permission catalogue and the eight built-in roles
--
-- SOURCE OF TRUTH: docs/specs/2026-07-30-tcms-roles-permissions-naming.md Part A.
-- That document is authoritative for the grants; this is its transcription, and
-- docs/specs/openapi.yaml's PermissionKey is the same catalogue expressed for the
-- frontend. All three must agree — SeedDataTest asserts the parts a reader cannot
-- check, including the counts and the role-to-role differences as exact sets, so a
-- permission added on one side and not the others fails the build.
--
-- Everything here is instance-level: permission, role and role_permission are all
-- on the ERD §0.1 exception list and carry no RLS, which is what makes them
-- seedable from a migration at all. The COMPANY-owned vocabularies cannot be, and
-- live in tcms_seed_company_defaults above.
-- =============================================================================


INSERT INTO company (id, slug, display_name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'default', 'Default');


-- -----------------------------------------------------------------------------
-- 1. THE PERMISSION CATALOGUE — 56 keys
-- -----------------------------------------------------------------------------
--
-- There is deliberately NO WILDCARD. Instance Admin's grant is derived at the end
-- of this file by joining the whole catalogue, not by storing a '*' sentinel: a
-- sentinel cannot distinguish "holds every permission" from "holds a permission
-- that happens to be present", so adding a permission Instance Admin did not hold
-- would still render as full coverage. The frontend learned this the expensive
-- way — see frontend/src/lib/permissions.ts.
--
-- `area` matches lib/permissions.ts exactly, because both sides group the same
-- list for humans and two different groupings would be two different products.


INSERT INTO permission (key, area, description) VALUES
    -- repository
    ('suite:manage',      'repository', 'Create, rename, move and reorder suites'),
    ('suite:delete',      'repository', 'Delete a suite folder and its whole subtree'),
    ('case:read',         'repository', 'Read test cases'),
    ('case:create',       'repository', 'Create test cases'),
    ('case:edit',         'repository', 'Edit test cases'),
    ('case:delete',       'repository', 'Delete test cases'),
    ('library:manage',    'repository', 'Maintain the shared condition and step library'),
    -- review — three tiers: comment < review < approve
    ('case:comment',      'review',     'Comment on a test case without any write access to it'),
    ('case:review',       'review',     'Take part in a case review — LGTM, request changes'),
    ('suite:review',      'review',     'Take part in a suite review'),
    ('plan:review',       'review',     'Take part in a test plan review'),
    ('doc:review',       'review',     'Take part in a documentation page review — LGTM, request changes'),
    ('case:approve',      'review',     'Move a test case to APPROVED'),
    ('suite:approve',     'review',     'Move a suite to APPROVED'),
    ('plan:approve',      'review',     'Move a test plan to APPROVED'),
    -- execution
    ('plan:create',       'execution',  'Create test plans'),
    ('plan:edit',         'execution',  'Edit test plans'),
    ('plan:delete',       'execution',  'Delete test plans'),
    ('run:create',        'execution',  'Create test runs'),
    ('run:execute',       'execution',  'Execute test cases in a run'),
    ('run:close',         'execution',  'Close or abort a test run'),
    ('result:submit',     'execution',  'Record an execution result'),
    ('result:amend',      'execution',  'Amend a result recorded by someone else'),
    ('evidence:attach',   'execution',  'Attach evidence to a result'),
    ('evidence:delete',   'execution',  'Delete attached evidence'),
    -- defects & work
    ('defect:create',     'work',       'File a defect from a failed step or result'),
    ('workitem:manage',   'work',       'Create and move work items on the kanban board'),
    -- reporting
    ('report:view',       'reporting',  'View reports and dashboards'),
    ('report:export',     'reporting',  'Export a report to PDF, Markdown or HTML'),
    -- documentation
    ('doc:read',          'documentation', 'Read the documentation space'),
    ('doc:edit',          'documentation', 'Edit documentation pages and blocks'),
    ('doc:publish',       'documentation', 'Publish or archive a documentation page'),
    -- artifacts
    ('artifact:read',     'artifacts',  'Read project and plan artifacts'),
    ('artifact:edit',     'artifacts',  'Create and edit artifacts'),
    ('artifact:delete',   'artifacts',  'Delete artifacts'),
    -- project
    ('project:read',      'project',    'See a project and its contents'),
    ('project:audit',     'project',    'Read the project audit trail'),
    ('project:settings',  'project',    'Change project settings'),
    ('project:members',   'project',    'Grant and revoke project roles'),
    ('integration:manage','project',    'Configure outbound integrations for a project'),
    -- company (COMPANY scope)
    ('company:read',      'company',    'See the company profile, branding and settings view'),
    ('company:settings',  'company',    'Change company branding, policies and defaults'),
    ('company:members',   'company',    'Invite and remove members, set membership display names'),
    ('company:teams',     'company',    'Manage teams, nesting, leads and org-chart structure'),
    ('company:vocabulary','company',    'Edit the company Test Type, Test Level and Phase values'),
    ('company:idp',       'company',    'Configure identity providers and claim-to-role mappings'),
    ('project:create',    'company',    'Create projects in the company'),
    ('token:issue',       'company',    'Issue and revoke API and CI tokens'),
    ('audit:view',        'company',    'Read the company-wide audit log'),
    -- system (SYSTEM scope)
    ('company:create',    'system',     'Provision a company'),
    ('company:delete',    'system',     'Purge a company — policy is suspend, never delete'),
    ('company:suspend',   'system',     'Suspend or resume a company'),
    ('user:manage',       'system',     'Manage the global user directory across all companies'),
    ('role:manage',       'system',     'Manage the role catalogue'),
    ('system:settings',   'system',     'Change instance settings'),
    ('instance:audit',    'system',     'Read the instance-wide audit log');


-- -----------------------------------------------------------------------------
-- 2. THE EIGHT BUILT-IN ROLES
-- -----------------------------------------------------------------------------
--
-- These eight are the complete role set — no custom roles, not even
-- clone-and-edit (spec A2). Enforcement is permission-based everywhere (nothing
-- checks a role NAME), so a role designer later is additive: built_in = false rows
-- plus a company_id column, no change to the evaluator.
--
-- Six are the roles a person can be given. The other two are not roles a person
-- holds at all, and `assignable = false` is how the grant endpoint tells them
-- apart: Company Member is a CONSEQUENCE of membership, granted automatically and
-- never offered as a choice, and Automation Service and Agent are CEILINGS that a
-- credential carries — an Agent token never exceeds the person who issued it,
-- because the effective set is the intersection of their permissions with the
-- role's. Read role.assignable's own comment before wiring that flag into
-- anything: it answers one question of four, and the other three have different
-- answers.
--
-- Fixed ids rather than generated ones: role_permission references them, and
-- anything that has to name a role can do so without a lookup subquery.
INSERT INTO role (id, scope, name, description, built_in, assignable) VALUES
    ('10000000-0000-4000-8000-000000000001', 'SYSTEM',  'Instance Admin',
     'The operator. Everything, in every company — provisions companies and appoints their '
     'administrators. Reach into a client''s content is audited in that client''s own trail.',
     true, true),
    ('10000000-0000-4000-8000-000000000002', 'COMPANY', 'Company Admin',
     'Full control of the companies they are assigned to, and nothing outside them. Sees every '
     'project in the company, present and future, with no per-project grant. Assigned by an '
     'Instance Admin.',
     true, true),
    ('10000000-0000-4000-8000-000000000003', 'PROJECT', 'Test Lead',
     'Full control of assigned projects — deletes others'' content, amends others'' results, closes '
     'runs, gives final approval, reads the project audit trail, manages project settings.',
     true, true),
    ('10000000-0000-4000-8000-000000000004', 'PROJECT', 'Test Engineer',
     'The working contributor role, granted explicitly — never a default. Creates and edits '
     'everything, executes, reviews, files defects, maintains the shared library. Deletes only '
     'their own work; cannot approve, close runs, delete a suite subtree, or read the audit trail.',
     true, true),
    ('10000000-0000-4000-8000-000000000005', 'PROJECT', 'Automation Service',
     'Non-human, attached to API tokens rather than people. Deliberately the smallest role in the '
     'product: a leaked CI token can post results and add quarantine stubs, and cannot edit or '
     'delete a real case, close a run, or read reports.',
     true, false),
    ('10000000-0000-4000-8000-000000000006', 'PROJECT', 'Viewer',
     'Read-only, plus commenting and report export. The default on joining a company or a project. '
     'Built for the stakeholder — often the client themselves — who reviews coverage without '
     'writing any of it.',
     true, true),
    ('10000000-0000-4000-8000-000000000007', 'COMPANY', 'Company Member',
     'The baseline every company member holds: company:read and nothing else. Granted automatically on '
     'joining, never offered in the grant UI and never revocable there — it is a consequence of '
     'membership rather than a decision about a person. It exists because company:read is granted to '
     'every member by roles spec §A3 while the four member-facing roles are PROJECT-scope, so without '
     'it the company-wide question answers "no" for everyone below Company Admin. It confers nothing '
     'about any project, which is what makes it safe to hold universally.',
     true, false),
    ('10000000-0000-4000-8000-000000000008', 'PROJECT', 'Agent',
     'Non-human, carried by API tokens only — the ceiling for an AI agent acting on behalf of the '
     'person who issued the token. Test Engineer minus the shared library, minus deleting cases and '
     'minus deleting evidence. It can read, create and edit cases, suites, plans, runs and '
     'documentation, execute and submit results, review and comment; it cannot approve anything, close '
     'a run, amend a result, publish a document, delete anything, or change project configuration. It '
     'never exceeds the person who issued the token: the effective set is the intersection of their '
     'permissions with this role, so a Viewer''s agent token reads only.',
     true, false);


-- -----------------------------------------------------------------------------
-- 3. GRANTS
-- -----------------------------------------------------------------------------
--
-- Effective permissions are a union of allows with no explicit deny, gated on
-- company membership (spec A1). Membership is a PRECONDITION, never a source, so
-- nothing below needs a "member" pseudo-role.
--
-- THE TIER LINE IS APPROVALS AND PROJECT CONFIGURATION, and nothing else. Test
-- Lead minus Test Engineer is exactly seven keys, and they are those two things:
--
--     case:approve · suite:approve · plan:approve            giving approvals
--     project:members · project:settings · project:audit     managing the project
--     integration:manage                                     and its integrations
--
-- That is a closed list, asserted by SeedDataTest in both directions. Two earlier
-- attempts to draw the line elsewhere — "creative vs destructive", then "content
-- vs shared structure and judgement" — were each stated, shipped and withdrawn
-- inside six weeks. What they had in common is that the boundary was legible to
-- whoever wrote it and not to the contributor standing in front of a greyed-out
-- button on a colleague's abandoned duplicate. They are recorded here so the third
-- attempt is recognised as one.
--
-- role_permission.qualifier is 'ALL' on every row, and that is a decision rather
-- than a gap — see the column's own comment, and SeedDataTest.everyQualifierIsAll,
-- which pins the emptiness in both directions.


-- Company Admin — every project key a Test Lead holds, over every project in the
-- company present and future, plus company configuration and project creation.
-- Holds NO SYSTEM-scope permission: the client boundary is what Company Admin must
-- not cross.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000002', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),
    ('case:delete'),('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),('doc:review'),
    ('case:approve'),('suite:approve'),('plan:approve'),
    ('plan:create'),('plan:edit'),('plan:delete'),('run:create'),('run:execute'),
    ('run:close'),('result:submit'),('result:amend'),('evidence:attach'),
    ('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read'),('company:settings'),('company:members'),('company:teams'),
    ('company:vocabulary'),('company:idp'),('project:create'),('token:issue'),
    ('audit:view')
  ) AS granted(key);

-- Test Lead — Company Admin scoped to one project, and the only role below it that
-- approves or configures. Keeps company:read because they are a member and every
-- check is a real permission check (spec A3), but nothing else at company scope.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000003', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),
    ('case:delete'),('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),('doc:review'),
    ('case:approve'),('suite:approve'),('plan:approve'),
    ('plan:create'),('plan:edit'),('plan:delete'),('run:create'),('run:execute'),
    ('run:close'),('result:submit'),('result:amend'),('evidence:attach'),
    ('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read')
  ) AS granted(key);

-- Test Engineer — the working contributor, granted explicitly and never a default.
-- Creates, edits, deletes, executes, reviews, files defects and maintains the
-- shared library, on anyone's work rather than only their own. The seven keys that
-- separate it from Test Lead are listed above.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),
    ('case:delete'),('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),('doc:review'),
    ('plan:create'),('plan:edit'),('plan:delete'),('run:create'),('run:execute'),
    ('run:close'),('result:submit'),('result:amend'),('evidence:attach'),
    ('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read'),
    ('company:read')
  ) AS granted(key);

-- Automation Service — six permissions, the smallest grant in the product, because
-- a leaked CI token is the realistic breach (spec A6). case:create is confined to
-- the quarantine folder, which is a service-layer rule: a permission cannot carry a
-- folder. Deliberately no report:view — a credential that can read every report is
-- an exfiltration path with no operational benefit.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000005', key
  FROM (VALUES
    ('case:read'),('case:create'),
    ('run:create'),('result:submit'),('evidence:attach'),
    ('project:read')
  ) AS granted(key);

-- Viewer — the default at EVERY entry point: company invitation, SSO JIT
-- provisioning, and project assignment (spec A7). Holds case:comment and
-- report:export on purpose: it is built for the client reviewing their own
-- coverage, and a reviewer who cannot comment reviews by email instead.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000006', key
  FROM (VALUES
    ('case:read'),
    ('case:comment'),
    ('report:view'),('report:export'),
    ('doc:read'),
    ('artifact:read'),
    ('project:read'),
    ('company:read')
  ) AS granted(key);

-- Company Member — the membership baseline, and exactly one key. It exists because
-- company:read is granted to every member by spec A3 while the four member-facing
-- roles are PROJECT-scope, so without it the company-wide question answers "no" for
-- everyone below Company Admin. It confers nothing about any project, which is what
-- makes it safe to hold universally, and it is granted by
-- tcms_grant_company_read_baseline rather than by anybody's decision.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000007', key
  FROM (VALUES
    ('company:read')
  ) AS granted(key);

-- Agent — the ceiling for an AI agent acting for the person who issued the token,
-- and Test Engineer's grant less company:read. It is never MORE than the issuer:
-- the effective set is the intersection of their permissions with this role, so a
-- Viewer's agent token reads only. The consent gate on destructive tools lives in
-- the MCP tool definitions, not in this catalogue — a permission cannot ask.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000008', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),
    ('case:delete'),('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),('doc:review'),
    ('plan:create'),('plan:edit'),('plan:delete'),('run:create'),('run:execute'),
    ('run:close'),('result:submit'),('result:amend'),('evidence:attach'),
    ('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read')
  ) AS granted(key);


-- Instance Admin — DERIVED from the catalogue, never enumerated, and seeded last
-- so it picks up every key above. If a permission is added and Instance Admin is
-- forgotten, SeedDataTest fails on the derived count rather than a screen quietly
-- claiming full coverage.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000001', key FROM permission;
