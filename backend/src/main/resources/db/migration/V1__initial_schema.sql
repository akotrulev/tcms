-- =============================================================================
-- TCMS — Initial schema
-- Companion to docs/specs/2026-07-30-tcms-erd.md
--
-- Conventions (see ERD §0):
--   * UUID v7 primary keys, generated application-side
--   * company_id on every COMPANY-OWNED table, leading every composite index and
--     every previously-global uniqueness constraint. Instance-level exceptions
--     are listed in ERD §0.1 and enforced by a schema test: company, app_user,
--     permission, role, instance_setting, instance_audit_event.
--   * TEXT + CHECK instead of native ENUM (enum values cannot be removed)
--   * TIMESTAMPTZ always
--   * deleted_at for soft delete on user-authored content
--   * lock_version for optimistic locking on concurrently-edited entities
--
-- A company is a SEPARATE CLIENT (plan §1, §6s). Isolation is enforced in three
-- independent layers: the Hibernate filter (application), the RLS policies at
-- the end of this file (database), and the two-company CI fixture (proof).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;

-- =============================================================================
-- TENANCY
-- =============================================================================

CREATE TABLE company (
    id                   UUID PRIMARY KEY,
    slug                 TEXT        NOT NULL,
    display_name         TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE','SUSPENDED')),
    logo_storage_key     TEXT,
    brand_primary_color  TEXT,
    brand_accent_color   TEXT,
    favicon_emoji        TEXT,
    storage_quota_bytes  BIGINT,
    settings             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID,
    lock_version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_company_slug UNIQUE (slug),
    CONSTRAINT ck_company_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);
COMMENT ON TABLE company IS
    'A separate CLIENT organisation — the top-level isolation boundary (plan §6s). Provisioned by '
    'the provision-company operator script, never through an admin UI.';
COMMENT ON COLUMN company.slug IS
    'Immutable after provisioning: it appears in every URL (/c/{slug}, /{slug}/login) and shared link.';
COMMENT ON COLUMN company.status IS
    'SUSPENDED must be HONORED IN CODE: refuse all non-Instance-Admin access, return 503 from '
    'ingestion with a reason, leave data untouched. Default lifecycle policy is suspend, never '
    'delete. There is deliberately no ARCHIVED state.';
COMMENT ON COLUMN company.brand_primary_color IS
    'Two brand colours only (§6u). The MUI theme is DERIVED from them with contrast-safe foregrounds '
    'computed and validated at save time — WCAG AA on execution screens is a commitment a '
    'client-chosen accent would otherwise break silently. Status colours are NOT brandable.';

INSERT INTO company (id, slug, display_name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'default', 'Default');

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
    id             UUID PRIMARY KEY,
    email          CITEXT      NOT NULL,
    canonical_name TEXT        NOT NULL,
    password_hash  TEXT,                          -- null when SSO-only (M4)
    status         TEXT        NOT NULL DEFAULT 'INVITED'
                   CHECK (status IN ('ACTIVE','INVITED','DEACTIVATED')),
    locale         TEXT        NOT NULL DEFAULT 'en',
    timezone       TEXT        NOT NULL DEFAULT 'UTC',
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     UUID,
    lock_version   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_user_email UNIQUE (email)
);
COMMENT ON COLUMN app_user.canonical_name IS
    'For the Instance Admin directory only. Every user-facing rendering site resolves the name '
    'through the ACTIVE company''s membership (§6u).';
COMMENT ON COLUMN app_user.password_hash IS
    'Nullable so SSO-only users are possible in M4 without a schema change. The break-glass '
    'Instance Admin must always have one (plan §6r).';
COMMENT ON COLUMN app_user.status IS
    'Users are never hard-deleted; DEACTIVATED preserves authorship in versions and audit. '
    'An IdP-removed user is deactivated, never deleted.';

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
    'Membership is a PRECONDITION for every permission, not a source of any (plan §6b). A '
    'non-member resolves to the empty permission set regardless of stale grants, so offboarding is '
    'one row update. The one place the permission model is not a pure union — deliberately.';
COMMENT ON COLUMN company_membership.display_name IS
    'PER-MEMBERSHIP, not global (§6u): a contractor may be "A. Kotrulev" in one client and '
    '"Aleks (Vendor)" in another, and neither client can see or overwrite the other''s.';
COMMENT ON COLUMN company_membership.status IS
    'Accepting an invitation grants Viewer with no project access — the same default as SSO JIT '
    'provisioning, so SSO cannot be a side door to more capability than the invite flow grants.';

CREATE INDEX company_membership_user_idx ON company_membership (user_id);

CREATE TABLE team (
    id             UUID PRIMARY KEY,
    company_id      UUID        NOT NULL REFERENCES company(id),
    parent_team_id UUID        REFERENCES team(id),
    name           TEXT        NOT NULL,
    description    TEXT,
    notification_channel JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     UUID,
    lock_version   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_team_name UNIQUE (company_id, name)
);
COMMENT ON COLUMN team.parent_team_id IS
    'Org-chart structure ONLY. Permissions never cascade through this edge (plan §6b/§6k). '
    'Cycle prevention is enforced in the service layer and by trigger.';

CREATE TABLE team_membership (
    company_id  UUID        NOT NULL REFERENCES company(id),
    team_id    UUID        NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    is_lead    BOOLEAN     NOT NULL DEFAULT false,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);
COMMENT ON COLUMN team_membership.is_lead IS
    'Team Lead (manages membership, default reviewer) — distinct from the Test Lead ROLE (plan §6b).';

CREATE TABLE permission (
    key         TEXT PRIMARY KEY,
    area        TEXT NOT NULL,
    description TEXT NOT NULL
);
COMMENT ON TABLE permission IS
    'Seeded reference data. Code checks permissions, never role names.';

-- role is INSTANCE-LEVEL and deliberately has no company_id: the six built-ins are
-- the complete role set in v1 — no custom roles, not even clone-and-edit (plan §6b).
-- Enforcement is permission-based throughout (nothing checks a role NAME), so adding
-- a designer later is additive: built_in = false rows plus a company_id column, with
-- no change to the evaluator. When that lands, custom roles MUST be company-owned —
-- a client editing "Test Engineer" must never mutate a row another client reads.
CREATE TABLE role (
    id         UUID PRIMARY KEY,
    scope      TEXT    NOT NULL CHECK (scope IN ('SYSTEM','COMPANY','PROJECT')),
    name       TEXT    NOT NULL,
    description TEXT,
    built_in   BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_name UNIQUE (name)
);
COMMENT ON TABLE role IS
    'Seeded with exactly six built-ins: Instance Admin (SYSTEM), Company Admin (COMPANY), '
    'Test Lead / Test Engineer / Automation Service / Viewer (PROJECT). See '
    'docs/specs/2026-07-30-tcms-roles-permissions-naming.md, authoritative for the grants.';

CREATE TABLE role_permission (
    role_id        UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_key TEXT NOT NULL REFERENCES permission(key),
    PRIMARY KEY (role_id, permission_key)
);

CREATE TABLE project (
    id           UUID PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
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
    'Allocate with: UPDATE key_sequence SET next_value = next_value + :n '
    'WHERE company_id = :c AND entity_type = :t RETURNING next_value - :n; '
    'NOT MAX(n)+1, which races under concurrent creation and eventually issues duplicate keys. '
    'Because the counter is company-wide, ALL case creation in a company serialises on one row — '
    'microseconds per increment, but bulk import and CI quarantine auto-create MUST reserve a range '
    'in one statement. Importing 5,000 cases one round trip at a time would be pathological. '
    'Gap-free numbering is NOT guaranteed (a rolled-back txn burns its range) and is not required: '
    'the contract is uniqueness and immutability only (plan §6m, §6t).';

CREATE TABLE role_assignment (
    id         UUID PRIMARY KEY,
    company_id UUID        REFERENCES company(id),   -- NULL only for SYSTEM-scope grants
    role_id    UUID        NOT NULL REFERENCES role(id),
    user_id    UUID        REFERENCES app_user(id),
    team_id    UUID        REFERENCES team(id) ON DELETE CASCADE,
    project_id UUID        REFERENCES project(id) ON DELETE CASCADE,
    granted_by UUID        REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_role_assignment_subject CHECK ((user_id IS NULL) <> (team_id IS NULL)),
    CONSTRAINT ck_role_assignment_project_has_company
        CHECK (project_id IS NULL OR company_id IS NOT NULL)
);
COMMENT ON TABLE role_assignment IS
    'The ONLY source of any capability (plan §6b). Effective permissions:'
    '  0  unless the user has an ACTIVE company_membership for project.company  <- gate, not a term'
    '  U  roles granted directly to the user on the project'
    '  U  roles granted to any team the user belongs to, on the project'
    '  U  roles granted to the user or their teams on project.company  (COMPANY scope)'
    '  U  roles granted to the user or their teams at SYSTEM scope'
    'Cached per (company_id, user_id, project_id) — the company id MUST be in the cache key. '
    'Invalidated on any write to role_assignment, team_membership, role_permission or '
    'company_membership, and on a company switch. No explicit deny anywhere in the model.';
COMMENT ON COLUMN role_assignment.company_id IS
    'NULL => SYSTEM scope (Instance Admin). Non-null with project_id NULL => COMPANY scope, which '
    'covers EVERY project in that company, present and future — that is what makes Company Admin '
    'work, and why nothing else should be granted at that scope casually.';
COMMENT ON COLUMN role_assignment.project_id IS
    'Non-null => PROJECT scope. A project grant always names its company (see the check). '
    'Company membership alone conveys NO project visibility: a regular user sees only the projects '
    'they hold a grant on — not the company''s project list, names, or counts.';

CREATE INDEX role_assignment_user_idx    ON role_assignment (company_id, user_id, project_id);
CREATE INDEX role_assignment_team_idx    ON role_assignment (company_id, team_id, project_id);

CREATE TABLE api_token (
    id           UUID PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    name         TEXT        NOT NULL,
    token_hash   TEXT        NOT NULL UNIQUE,
    owner_id     UUID        NOT NULL REFERENCES app_user(id),
    role_id      UUID        NOT NULL REFERENCES role(id),
    project_id   UUID        REFERENCES project(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN api_token.role_id IS
    'A token carries a ROLE, never the full rights of the issuing user (plan §6b). A CI token holds '
    'Automation Service and nothing more: case:read, run:create, result:submit, evidence:attach, '
    'and case:create restricted to the quarantine folder. Deliberately the smallest role in the '
    'product — a leaked token can post results and add quarantine stubs, and cannot edit or delete '
    'a real case, close a run, read reports, or reach another company.';
COMMENT ON COLUMN api_token.company_id IS
    'Bound at issue time. The request path must match this company or the call is rejected.';

-- =============================================================================
-- PROJECT CONFIGURATION & CLASSIFICATION
-- =============================================================================

CREATE TABLE classification_dimension (
    id           UUID PRIMARY KEY,
    company_id    UUID    NOT NULL REFERENCES company(id),
    project_id   UUID    REFERENCES project(id) ON DELETE CASCADE,
    key          TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    cardinality  TEXT    NOT NULL CHECK (cardinality IN ('SINGLE','MULTI')),
    controlled   BOOLEAN NOT NULL DEFAULT true,
    hierarchical BOOLEAN NOT NULL DEFAULT false,
    locked       BOOLEAN NOT NULL DEFAULT false,
    position     INT     NOT NULL DEFAULT 0
);
COMMENT ON COLUMN classification_dimension.project_id IS
    'NULL = COMPANY-level dimension, shared across that company''s projects. Test Type / Level / '
    'Phase are seeded company-level and locked so reporting is comparable across a client''s '
    'projects; Feature is per-project (plan §6d). Company-level rather than instance-level is the '
    'multi-company correction: each client gets its own COPY of the seeded vocabulary, because one '
    'client renaming "Compatibility" must not rename it for another. Cross-company reporting is a '
    'non-goal, so nothing is lost.';

CREATE UNIQUE INDEX classification_dimension_key_idx
    ON classification_dimension (company_id, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'), key);

CREATE TABLE classification_value (
    id           UUID PRIMARY KEY,
    company_id    UUID  NOT NULL REFERENCES company(id),
    dimension_id UUID  NOT NULL REFERENCES classification_dimension(id) ON DELETE CASCADE,
    parent_id    UUID  REFERENCES classification_value(id),
    path         LTREE NOT NULL,
    name         TEXT  NOT NULL,
    color        TEXT,
    position     INT   NOT NULL DEFAULT 0,
    deleted_at   TIMESTAMPTZ
);
CREATE INDEX classification_value_path_idx ON classification_value USING GIST (path);
COMMENT ON COLUMN classification_value.path IS
    'Materialized path. Selecting a feature must include descendants by default (plan §6q).';

CREATE TABLE case_status (
    id             UUID PRIMARY KEY,
    company_id      UUID    NOT NULL REFERENCES company(id),
    project_id     UUID    REFERENCES project(id) ON DELETE CASCADE,
    name           TEXT    NOT NULL,
    kind           TEXT    NOT NULL
                   CHECK (kind IN ('PASSED','FAILED','BLOCKED','SKIPPED','UNTESTED','RETEST')),
    color          TEXT,
    is_final       BOOLEAN NOT NULL DEFAULT true,
    counts_as_pass BOOLEAN NOT NULL DEFAULT false,
    position       INT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE case_status IS
    'PROJECT-scoped execution statuses, seeded with the six defaults per project. Statuses and the '
    'condition library are project-scoped while Test Type / Level / Phase are company-scoped '
    '(plan §6d, §6f): two client products legitimately differ in login flow and status vocabulary, '
    'while "what counts as a Performance test" should not.';
COMMENT ON COLUMN case_status.kind IS
    'Lets reporting aggregate project-custom statuses without knowing their names.';

-- =============================================================================
-- TEST REPOSITORY
-- =============================================================================

CREATE TABLE suite (
    id           UUID PRIMARY KEY,
    company_id    UUID  NOT NULL REFERENCES company(id),
    project_id   UUID  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id    UUID  REFERENCES suite(id),
    key          TEXT  NOT NULL,
    key_seq      BIGINT NOT NULL,
    path         LTREE NOT NULL,
    name         TEXT  NOT NULL,
    description  TEXT,
    position     INT   NOT NULL DEFAULT 0,
    lifecycle_status TEXT NOT NULL DEFAULT 'DRAFT'
                   CHECK (lifecycle_status IN ('DRAFT','IN_REVIEW','APPROVED','DEPRECATED')),
    current_version_no INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    deleted_at   TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE suite ADD CONSTRAINT uq_suite_key UNIQUE (company_id, key);
CREATE INDEX suite_path_idx    ON suite USING GIST (path);
CREATE INDEX suite_project_idx ON suite (company_id, project_id) WHERE deleted_at IS NULL;
CREATE INDEX suite_key_seq_idx ON suite (company_id, key_seq);

CREATE TABLE shared_step (
    id            UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company(id),
    project_id    UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    body          JSONB NOT NULL,
    usage_count   INT  NOT NULL DEFAULT 0,
    search_vector TSVECTOR,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID,
    deleted_at    TIMESTAMPTZ,
    lock_version  BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE condition (
    id            UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company(id),
    project_id    UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    type          TEXT NOT NULL CHECK (type IN ('PRE','POST','BOTH')),
    body          JSONB NOT NULL,
    parameters    JSONB,
    usage_count   INT  NOT NULL DEFAULT 0,
    search_vector TSVECTOR,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID,
    deleted_at    TIMESTAMPTZ,
    lock_version  BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN condition.usage_count IS
    'Denormalized. Drives typeahead ranking (plan §6h) and the pre-edit impact warning (§6f).';

CREATE INDEX condition_search_idx ON condition USING GIN (search_vector);
CREATE INDEX condition_title_trgm_idx ON condition USING GIN (title gin_trgm_ops);
CREATE INDEX shared_step_search_idx ON shared_step USING GIN (search_vector);
CREATE INDEX shared_step_title_trgm_idx ON shared_step USING GIN (title gin_trgm_ops);

CREATE TABLE test_case (
    id                     UUID PRIMARY KEY,
    company_id              UUID NOT NULL REFERENCES company(id),
    project_id             UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    suite_id               UUID NOT NULL REFERENCES suite(id),
    key                    TEXT NOT NULL,
    key_seq                BIGINT NOT NULL,
    title                  TEXT NOT NULL,
    description            TEXT,
    objective              TEXT,
    step_format            TEXT NOT NULL DEFAULT 'STRUCTURED'
                           CHECK (step_format IN ('STRUCTURED','GHERKIN')),
    gherkin_source         TEXT,
    expected_outcome       TEXT,
    priority               TEXT NOT NULL DEFAULT 'MEDIUM'
                           CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    severity               TEXT CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    estimated_duration_sec INT,
    owner_id               UUID REFERENCES app_user(id),
    lifecycle_status       TEXT NOT NULL DEFAULT 'DRAFT'
                           CHECK (lifecycle_status IN ('DRAFT','IN_REVIEW','APPROVED','DEPRECATED')),
    automation_status      TEXT NOT NULL DEFAULT 'MANUAL'
                           CHECK (automation_status IN
                                 ('MANUAL','TO_BE_AUTOMATED','AUTOMATED','NOT_AUTOMATABLE')),
    automation_key         TEXT,
    not_automatable_reason TEXT,
    current_version_no     INT  NOT NULL DEFAULT 1,
    search_vector          TSVECTOR,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             UUID,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID,
    deleted_at             TIMESTAMPTZ,
    lock_version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_test_case_key UNIQUE (company_id, key),
    CONSTRAINT ck_not_automatable_reason
        CHECK (automation_status <> 'NOT_AUTOMATABLE' OR not_automatable_reason IS NOT NULL)
);
COMMENT ON COLUMN test_case.key IS
    'TC-1 — sequential per COMPANY (unique across all its projects), immutable, never reused, never '
    'renumbered, and immutable across a project move too (a collision is impossible since the '
    'namespace is the company). It ends up in commit messages and Jira tickets, so it must outlive '
    'everything (plan §6m, §6t). Display only: never an API path identifier.';
COMMENT ON COLUMN test_case.key_seq IS
    'The numeric part, stored separately BECAUSE KEYS DO NOT SORT LEXICOGRAPHICALLY: "TC-10000" '
    'sorts before "TC-9999" as text. Order by key_seq, display key. Same class of bug as '
    'test_run.release_sort_key.';
COMMENT ON COLUMN test_case.automation_key IS
    'Single-valued match target for CI ingestion, unique per COMPANY (plan §6c) — so a CI job posts '
    'results without knowing which project owns the case, and a case moved between projects does '
    'not change where its key resolves. Selection tags live in case_automation_tag.';
COMMENT ON CONSTRAINT ck_not_automatable_reason ON test_case IS
    'NOT_AUTOMATABLE requires a reason — it is what makes automation coverage honestly denominated.';

CREATE UNIQUE INDEX test_case_automation_key_idx
    ON test_case (company_id, automation_key) WHERE automation_key IS NOT NULL;
CREATE INDEX test_case_search_idx     ON test_case USING GIN (search_vector);
CREATE INDEX test_case_title_trgm_idx ON test_case USING GIN (title gin_trgm_ops);
CREATE INDEX test_case_suite_idx      ON test_case (company_id, suite_id) WHERE deleted_at IS NULL;
CREATE INDEX test_case_lifecycle_idx  ON test_case (company_id, project_id, lifecycle_status)
    WHERE deleted_at IS NULL;
CREATE INDEX test_case_key_seq_idx    ON test_case (company_id, key_seq);
CREATE INDEX test_case_key_trgm_idx   ON test_case USING GIN (key gin_trgm_ops);

CREATE TABLE case_automation_tag (
    company_id UUID NOT NULL REFERENCES company(id),
    case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (case_id, tag)
);
CREATE INDEX case_automation_tag_tag_idx ON case_automation_tag (tag);

CREATE TABLE test_case_draft (
    case_id    UUID PRIMARY KEY REFERENCES test_case(id) ON DELETE CASCADE,
    company_id  UUID  NOT NULL REFERENCES company(id),
    content    JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID
);
COMMENT ON TABLE test_case_draft IS
    'Autosave buffer. NEVER versioned. An explicit save promotes this into test_case + a new '
    'test_case_version and clears the row — this is what keeps history free of keystroke noise (plan §6j).';

CREATE TABLE test_case_version (
    id             UUID PRIMARY KEY,
    company_id      UUID  NOT NULL REFERENCES company(id),
    case_id        UUID  NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    version_no     INT   NOT NULL,
    snapshot       JSONB NOT NULL,
    content_hash   TEXT  NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_case_version UNIQUE (case_id, version_no)
);
COMMENT ON COLUMN test_case_version.snapshot IS
    'FULL snapshot including RESOLVED steps, conditions and classification. Not a delta: '
    'reconstruction is O(1) and corruption stays local (plan §6j).';
COMMENT ON COLUMN test_case_version.content_hash IS
    'A save whose hash is unchanged writes no version.';
COMMENT ON COLUMN test_case_version.pinned IS
    'Set when referenced by a run, an approval, or a generated report. Pinned versions are '
    'permanently exempt from any retention policy.';

CREATE TABLE test_step (
    id              UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    case_id         UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    position        INT  NOT NULL,
    keyword         TEXT CHECK (keyword IN ('GIVEN','WHEN','THEN','AND','BUT')),
    action          TEXT NOT NULL,
    test_data       TEXT,
    expected_result TEXT,
    docstring       TEXT,
    datatable       JSONB,
    shared_step_id  UUID REFERENCES shared_step(id)
);
COMMENT ON COLUMN test_step.id IS
    'STABLE. step_result FKs here, so inserting or reordering steps never reassigns evidence (plan §6m).';
COMMENT ON COLUMN test_step.keyword IS
    'Non-null identifies a Gherkin step. One table serves both formats — splitting would double '
    'every query that does not care about format.';

CREATE INDEX test_step_case_idx ON test_step (case_id, position);

CREATE TABLE case_condition (
    id                 UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES company(id),
    case_id            UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    kind               TEXT NOT NULL CHECK (kind IN ('PRE','POST')),
    position           INT  NOT NULL,
    condition_id       UUID REFERENCES condition(id),
    inline_body        JSONB,
    parameter_bindings JSONB,
    CONSTRAINT ck_case_condition_source CHECK ((condition_id IS NULL) <> (inline_body IS NULL))
);
COMMENT ON TABLE case_condition IS
    'Holds a live REFERENCE while authoring (so library edits propagate), and is resolved and '
    'snapshotted into test_case_version.snapshot at version-pin time (plan §6f).';

CREATE INDEX case_condition_case_idx      ON case_condition (case_id, kind, position);
CREATE INDEX case_condition_condition_idx ON case_condition (condition_id);

CREATE TABLE suite_condition (
    id                 UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES company(id),
    suite_id           UUID NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    kind               TEXT NOT NULL CHECK (kind IN ('PRE','POST')),
    position           INT  NOT NULL,
    condition_id       UUID REFERENCES condition(id),
    inline_body        JSONB,
    parameter_bindings JSONB,
    CONSTRAINT ck_suite_condition_source CHECK ((condition_id IS NULL) <> (inline_body IS NULL))
);
COMMENT ON TABLE suite_condition IS
    'Inherited by every case beneath the suite. Resolution order: inherited (walking suite.path '
    'upward) then the case own conditions, deduped by condition_id (plan §6f).';

CREATE TABLE case_condition_optout (
    company_id         UUID NOT NULL REFERENCES company(id),
    case_id            UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    suite_condition_id UUID NOT NULL REFERENCES suite_condition(id) ON DELETE CASCADE,
    PRIMARY KEY (case_id, suite_condition_id)
);

CREATE TABLE case_classification (
    company_id   UUID NOT NULL REFERENCES company(id),
    case_id      UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    value_id     UUID NOT NULL REFERENCES classification_value(id),
    dimension_id UUID NOT NULL REFERENCES classification_dimension(id),
    PRIMARY KEY (case_id, value_id)
);
COMMENT ON COLUMN case_classification.dimension_id IS
    'Denormalized so SINGLE-cardinality dimensions can be enforced by trigger (the predicate '
    'depends on classification_dimension, so a plain unique index will not do).';

CREATE INDEX case_classification_value_idx ON case_classification (value_id);
CREATE INDEX case_classification_dim_idx   ON case_classification (case_id, dimension_id);

CREATE TABLE case_tag (
    company_id UUID NOT NULL REFERENCES company(id),
    case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (case_id, tag)
);

CREATE TABLE attachment (
    id           UUID PRIMARY KEY,
    company_id    UUID NOT NULL REFERENCES company(id),
    owner_type   TEXT NOT NULL
                 CHECK (owner_type IN ('CASE','STEP','EXECUTION','STEP_RESULT','DOC_PAGE','ARTIFACT')),
    owner_id     UUID NOT NULL,
    storage_key  TEXT NOT NULL,
    filename     TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes   BIGINT NOT NULL,
    checksum     TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
COMMENT ON TABLE attachment IS
    'Polymorphic parent — the ONE place this schema knowingly gives up referential integrity. '
    'Six near-identical attachment tables is the worse trade.';

CREATE INDEX attachment_owner_idx ON attachment (owner_type, owner_id);

-- =============================================================================
-- EXECUTION
-- =============================================================================

CREATE TABLE test_plan (
    id                 UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES company(id),
    project_id         UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key                TEXT NOT NULL,
    key_seq            BIGINT NOT NULL,
    name               TEXT NOT NULL,
    description        TEXT,
    status             TEXT NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','CLOSED')),
    milestone_name     TEXT,
    milestone_date     DATE,
    entry_criteria     JSONB,
    exit_criteria      JSONB,
    current_version_no INT  NOT NULL DEFAULT 1,
    closed_at          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID,
    deleted_at         TIMESTAMPTZ,
    lock_version       BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE test_plan ADD CONSTRAINT uq_test_plan_key UNIQUE (company_id, key);
CREATE INDEX test_plan_key_seq_idx ON test_plan (company_id, key_seq);

COMMENT ON COLUMN test_plan.key IS 'TP-1 — sequential per COMPANY, immutable (§6t).';
COMMENT ON COLUMN test_plan.exit_criteria IS
    'Structured predicates, e.g. {"metric":"pass_rate","op":">=","value":95}, so the plan page '
    'can evaluate them automatically rather than storing prose nobody can check (plan §6p).';

CREATE TABLE plan_scope_item (
    id        UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    plan_id   UUID NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    suite_id  UUID REFERENCES suite(id),
    case_id   UUID REFERENCES test_case(id),
    CONSTRAINT ck_plan_scope_target CHECK ((suite_id IS NULL) <> (case_id IS NULL))
);

CREATE TABLE test_plan_version (
    id             UUID PRIMARY KEY,
    company_id      UUID  NOT NULL REFERENCES company(id),
    plan_id        UUID  NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    version_no     INT   NOT NULL,
    snapshot       JSONB NOT NULL,
    content_hash   TEXT  NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_plan_version UNIQUE (plan_id, version_no)
);
COMMENT ON COLUMN test_plan_version.snapshot IS
    'A plan version captures its SCOPE. Gives the as-executed membership needed by history (plan §6p).';

CREATE TABLE suite_version (
    id             UUID PRIMARY KEY,
    company_id      UUID  NOT NULL REFERENCES company(id),
    suite_id       UUID  NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    version_no     INT   NOT NULL,
    snapshot       JSONB NOT NULL,
    content_hash   TEXT  NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_suite_version UNIQUE (suite_id, version_no)
);
COMMENT ON COLUMN suite_version.snapshot IS
    'A suite version captures COMPOSITION (member cases, order, nesting) — explicitly NOT the '
    'content of its cases, which version independently. Otherwise every case edit bumps every '
    'ancestor suite (plan §6j).';

CREATE TABLE configuration (
    id         UUID PRIMARY KEY,
    company_id  UUID  NOT NULL REFERENCES company(id),
    project_id UUID  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT  NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT false
);
COMMENT ON COLUMN configuration.is_default IS
    'Every project is seeded with a "Default" configuration. Runs without an explicit environment '
    'point at it rather than carrying NULL, which keeps configuration_id NOT NULL throughout the '
    'rollup read models — suite_run_summary and plan_snapshot key on it, and a nullable PK column '
    'is not expressible. Removes null-handling from every aggregate query.';

CREATE UNIQUE INDEX configuration_default_idx
    ON configuration (project_id) WHERE is_default;

CREATE TABLE test_run (
    id               UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    plan_id          UUID REFERENCES test_plan(id),
    configuration_id UUID NOT NULL REFERENCES configuration(id),
    key              TEXT NOT NULL,
    key_seq          BIGINT NOT NULL,
    name             TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'NOT_STARTED'
                     CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED','ABORTED')),
    release_version  TEXT,
    release_sort_key TEXT,
    build_number     TEXT,
    commit_sha       TEXT,
    branch           TEXT,
    source           TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','INGESTED')),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    deleted_at       TIMESTAMPTZ,
    lock_version     BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN test_run.release_sort_key IS
    'Computed on write from release_version — semver parsed to %05d.%05d.%05d, non-parsing values '
    'falling back to raw collation. Without this, "1.10" sorts before "1.9" and every latest-release '
    'query breaks SILENTLY (plan §6q).';
COMMENT ON COLUMN test_run.build_number IS
    'Distinct from release_version. Conflating them costs the ability to ask whether 4.3.0 improved '
    'between build 4470 and 4471 — the question during a release stabilization.';

ALTER TABLE test_run ADD CONSTRAINT uq_test_run_key UNIQUE (company_id, key);
CREATE INDEX test_run_release_idx ON test_run (company_id, project_id, release_sort_key);
CREATE INDEX test_run_plan_idx    ON test_run (company_id, plan_id) WHERE deleted_at IS NULL;
CREATE INDEX test_run_key_seq_idx ON test_run (company_id, key_seq);

COMMENT ON COLUMN test_run.key IS 'TR-1 — sequential per COMPANY, immutable (§6t).';

CREATE TABLE test_execution (
    id               UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id           UUID NOT NULL REFERENCES test_run(id) ON DELETE CASCADE,
    case_id          UUID NOT NULL REFERENCES test_case(id),
    case_version_id  UUID NOT NULL REFERENCES test_case_version(id),
    status_id        UUID REFERENCES case_status(id),
    release_version  TEXT,
    release_sort_key TEXT,
    assignee_id      UUID REFERENCES app_user(id),
    executed_by      UUID REFERENCES app_user(id),
    executed_by_token UUID REFERENCES api_token(id),
    executed_at      TIMESTAMPTZ,
    duration_ms      BIGINT,
    comment          TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_execution_run_case UNIQUE (run_id, case_id)
);
COMMENT ON TABLE test_execution IS
    'The case x run instance. Deliberately has NO display key (plan §6t): an execution is always '
    'reached through its run or its case, nobody says "TE-4038" out loud, and a per-execution '
    'counter would be the highest-volume contention point in the schema for no benefit. It is '
    'identified by (run_id, case_id) and by its UUID.';
COMMENT ON COLUMN test_execution.project_id IS
    'Denormalized from run -> plan as a permission-scoping shortcut, avoiding a two-hop join on '
    'every access check.';
COMMENT ON COLUMN test_execution.case_version_id IS
    'THE PIN. Every result knows exactly which version of the case it executed — without this, '
    'editing a case silently rewrites history in every past run report (plan §2/§6j).';
COMMENT ON COLUMN test_execution.release_version IS
    'Denormalized from test_run. Nearly every interesting query filters executions by release; '
    'joining back to the run on each would be the join in every slow-query report (plan §6q).';
COMMENT ON COLUMN test_execution.lock_version IS
    'Optimistic locking — multiple testers may work the same run concurrently.';

CREATE INDEX test_execution_case_history_idx ON test_execution (case_id, executed_at DESC);
CREATE INDEX test_execution_release_idx      ON test_execution (company_id, release_sort_key, status_id);
CREATE INDEX test_execution_assignee_idx     ON test_execution (company_id, assignee_id)
    WHERE executed_at IS NULL;

CREATE TABLE step_result (
    id           UUID PRIMARY KEY,
    company_id    UUID NOT NULL REFERENCES company(id),
    execution_id UUID NOT NULL REFERENCES test_execution(id) ON DELETE CASCADE,
    step_id      UUID NOT NULL REFERENCES test_step(id),
    status_id    UUID REFERENCES case_status(id),
    comment      TEXT,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_step_result UNIQUE (execution_id, step_id)
);

CREATE TABLE evidence (
    id             UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES company(id),
    execution_id   UUID NOT NULL REFERENCES test_execution(id) ON DELETE CASCADE,
    step_result_id UUID REFERENCES step_result(id) ON DELETE CASCADE,
    attachment_id  UUID NOT NULL REFERENCES attachment(id),
    caption        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID
);
COMMENT ON COLUMN evidence.step_result_id IS
    'Nullable — evidence attaches at either execution or step level. Immutable once the run closes.';

CREATE TABLE defect_link (
    id           UUID PRIMARY KEY,
    company_id    UUID NOT NULL REFERENCES company(id),
    execution_id UUID REFERENCES test_execution(id) ON DELETE CASCADE,
    case_id      UUID REFERENCES test_case(id),
    provider     TEXT NOT NULL DEFAULT 'JIRA',
    external_key TEXT NOT NULL,
    url          TEXT,
    status_cache TEXT,
    synced_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
COMMENT ON TABLE defect_link IS
    'Behind the IssueTracker port — no Jira-specific columns, so a GitHub/ADO adapter is additive.';

-- =============================================================================
-- DOCUMENTATION & ARTIFACTS
-- =============================================================================

CREATE TABLE doc_space (
    id         UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES company(id),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT NOT NULL
);

CREATE TABLE doc_page (
    id                 UUID PRIMARY KEY,
    company_id          UUID  NOT NULL REFERENCES company(id),
    space_id           UUID  NOT NULL REFERENCES doc_space(id) ON DELETE CASCADE,
    project_id         UUID  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id          UUID  REFERENCES doc_page(id),
    key                TEXT  NOT NULL,
    key_seq            BIGINT NOT NULL,
    path               LTREE NOT NULL,
    slug               TEXT  NOT NULL,
    title              TEXT  NOT NULL,
    status             TEXT  NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    current_version_no INT   NOT NULL DEFAULT 1,
    search_vector      TSVECTOR,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID,
    deleted_at         TIMESTAMPTZ,
    lock_version       BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE doc_page ADD CONSTRAINT uq_doc_page_key UNIQUE (company_id, key);
CREATE INDEX doc_page_path_idx    ON doc_page USING GIST (path);
CREATE INDEX doc_page_search_idx  ON doc_page USING GIN (search_vector);
CREATE INDEX doc_page_key_seq_idx ON doc_page (company_id, key_seq);

COMMENT ON COLUMN doc_page.key IS 'DOC-1 — sequential per COMPANY, immutable (§6t).';

CREATE TABLE doc_block (
    id          UUID PRIMARY KEY,
    company_id   UUID  NOT NULL REFERENCES company(id),
    page_id     UUID  NOT NULL REFERENCES doc_page(id) ON DELETE CASCADE,
    position    INT   NOT NULL,
    block_type  TEXT  NOT NULL,
    content     JSONB NOT NULL,
    anchor_slug TEXT
);
COMMENT ON COLUMN doc_block.id IS
    'IMMUTABLE, generated at creation, NEVER derived from content. Links target (page_id, block_id), '
    'so renaming a heading or reordering sections never silently breaks traceability. Heading-text '
    'anchors break quietly, which is worse than breaking loudly (plan §6i/§6m).';
COMMENT ON COLUMN doc_block.anchor_slug IS
    'Human-readable URL convenience ONLY. Never the stored link target.';

CREATE INDEX doc_block_page_idx ON doc_block (page_id, position);

CREATE TABLE doc_page_version (
    id             UUID PRIMARY KEY,
    company_id      UUID  NOT NULL REFERENCES company(id),
    page_id        UUID  NOT NULL REFERENCES doc_page(id) ON DELETE CASCADE,
    version_no     INT   NOT NULL,
    snapshot       JSONB NOT NULL,
    content_hash   TEXT  NOT NULL,
    change_comment TEXT,
    pinned         BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT uq_doc_page_version UNIQUE (page_id, version_no)
);

CREATE TABLE doc_link (
    id                  UUID PRIMARY KEY,
    company_id           UUID NOT NULL REFERENCES company(id),
    source_type         TEXT NOT NULL
                        CHECK (source_type IN ('TEST_CASE','SUITE','DEFECT','DOC_PAGE')),
    source_id           UUID NOT NULL,
    target_kind         TEXT NOT NULL
                        CHECK (target_kind IN ('DOC_BLOCK','EXTERNAL_GIT','EXTERNAL_URL')),
    target_block_id     UUID REFERENCES doc_block(id) ON DELETE CASCADE,
    external_repo       TEXT,
    external_path       TEXT,
    external_line_start INT,
    external_line_end   INT,
    external_commit_sha TEXT,
    external_url        TEXT,
    relationship        TEXT NOT NULL DEFAULT 'VERIFIES'
                        CHECK (relationship IN ('VERIFIES','DERIVED_FROM','REFERENCES')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    CONSTRAINT ck_doc_link_target CHECK (
        (target_kind = 'DOC_BLOCK'    AND target_block_id IS NOT NULL)
     OR (target_kind = 'EXTERNAL_GIT' AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
     OR (target_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL)
    )
);
COMMENT ON TABLE doc_link IS
    'Requirements ARE doc blocks — there is no requirement table. The coverage matrix is a query '
    'over this edge where source_type = TEST_CASE, joined to latest results (ERD resolved #18).';
COMMENT ON CONSTRAINT ck_doc_link_target ON doc_link IS
    'EXTERNAL_GIT requires a commit SHA — a line number without one is meaningless six months '
    'later, and pretending otherwise produces links that point confidently at the wrong code (plan §6m).';

CREATE INDEX doc_link_source_idx ON doc_link (source_type, source_id);
CREATE INDEX doc_link_target_idx ON doc_link (target_block_id);

CREATE TABLE artifact (
    id                UUID PRIMARY KEY,
    company_id         UUID NOT NULL REFERENCES company(id),
    scope             TEXT NOT NULL CHECK (scope IN ('PROJECT','PLAN')),
    project_id        UUID REFERENCES project(id) ON DELETE CASCADE,
    plan_id           UUID REFERENCES test_plan(id) ON DELETE CASCADE,
    category          TEXT NOT NULL,
    kind              TEXT NOT NULL
                      CHECK (kind IN ('FILE','DOC_PAGE_REF','EXTERNAL_URL','GENERATED_REPORT')),
    title             TEXT NOT NULL,
    description       TEXT,
    status            TEXT NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
    superseded_by_id  UUID REFERENCES artifact(id),
    attachment_id     UUID REFERENCES attachment(id),
    doc_page_id       UUID REFERENCES doc_page(id),
    external_url      TEXT,
    report_artifact_id UUID,
    write_once        BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        UUID,
    lock_version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_artifact_scope CHECK (
        (scope = 'PROJECT' AND project_id IS NOT NULL AND plan_id IS NULL)
     OR (scope = 'PLAN'    AND plan_id    IS NOT NULL)
    )
);
COMMENT ON TABLE artifact IS
    'Four kinds, one register. Supporting REFERENCES (doc pages, URLs, reports) rather than only '
    'uploads is what keeps this from becoming a graveyard of outdated copies (plan §6n).';
COMMENT ON COLUMN artifact.write_once IS 'Sign-off records: corrections supersede, never edit.';

-- =============================================================================
-- REVIEW & WORK
-- =============================================================================

CREATE TABLE review (
    id                    UUID PRIMARY KEY,
    company_id             UUID NOT NULL REFERENCES company(id),
    project_id            UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    subject_type          TEXT NOT NULL CHECK (subject_type IN ('CASE','SUITE','PLAN')),
    subject_id            UUID NOT NULL,
    subject_version_no    INT  NOT NULL,
    state                 TEXT NOT NULL DEFAULT 'IN_REVIEW'
                          CHECK (state IN ('IN_REVIEW','APPROVED','REJECTED')),
    required_peer_reviews INT  NOT NULL DEFAULT 0,
    approved_by           UUID REFERENCES app_user(id),
    approved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    lock_version          BIGINT NOT NULL DEFAULT 0
);
COMMENT ON COLUMN review.subject_version_no IS
    'An approval applies to a SPECIFIC version, including its snapshotted conditions (plan §6g).';

CREATE TABLE review_participation (
    id         UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES company(id),
    review_id  UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user(id),
    kind       TEXT NOT NULL CHECK (kind IN ('PEER_REVIEW','APPROVAL')),
    verdict    TEXT NOT NULL
               CHECK (verdict IN ('LGTM','CHANGES_REQUESTED','REJECTED','APPROVED')),
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN review_participation.kind IS
    'Separates a Test Engineer advisory review from a Test Lead binding approval. Only APPROVAL '
    'rows may transition review.state, and creating one requires the *:approve permission (plan §6b).';

CREATE TABLE review_comment (
    id          UUID PRIMARY KEY,
    company_id   UUID NOT NULL REFERENCES company(id),
    review_id   UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    parent_id   UUID REFERENCES review_comment(id),
    anchor_type TEXT,
    anchor_id   UUID,
    body        TEXT NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);

CREATE TABLE work_item (
    id          UUID PRIMARY KEY,
    company_id   UUID NOT NULL REFERENCES company(id),
    project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    description TEXT,
    kind        TEXT NOT NULL DEFAULT 'TASK',
    status      TEXT NOT NULL DEFAULT 'TODO',
    priority    TEXT NOT NULL DEFAULT 'MEDIUM'
                CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    due_date    DATE,
    assignee_id UUID REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID,
    deleted_at  TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE work_item IS
    'ONLY real tasks are rows. Derived items — assigned executions, pending reviews, stale cases — '
    'are NEVER materialized; they resolve at query time from their source. There is deliberately no '
    'column here that could let a card status diverge from its execution (plan §6l).';
COMMENT ON COLUMN work_item.priority IS
    'Distinct from test_case.priority. A P1 case may be a low-priority thing to UPDATE.';

CREATE TABLE work_item_link (
    company_id   UUID NOT NULL REFERENCES company(id),
    work_item_id UUID NOT NULL REFERENCES work_item(id) ON DELETE CASCADE,
    target_type  TEXT NOT NULL,
    target_id    UUID NOT NULL,
    PRIMARY KEY (work_item_id, target_type, target_id)
);

CREATE TABLE derived_item_annotation (
    company_id       UUID NOT NULL REFERENCES company(id),
    user_id         UUID NOT NULL REFERENCES app_user(id),
    source_type     TEXT NOT NULL,
    source_id       UUID NOT NULL,
    note            TEXT,
    personal_due_date DATE,
    flagged         BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, source_type, source_id)
);
COMMENT ON TABLE derived_item_annotation IS
    'Personal metadata on a DERIVED card without owning its status — satisfies "let me note something '
    'on that execution card" without breaking the invariant (plan §6l).';

-- =============================================================================
-- REPORTING READ MODELS  (event-updated tables, never joined back to source)
-- =============================================================================

CREATE TABLE execution_fact (
    execution_id     UUID PRIMARY KEY,
    company_id        UUID NOT NULL,
    project_id       UUID NOT NULL,
    case_id          UUID NOT NULL,
    case_version_id  UUID NOT NULL,
    suite_id         UUID NOT NULL,
    suite_path       LTREE NOT NULL,
    plan_id          UUID,
    run_id           UUID NOT NULL,
    configuration_id UUID NOT NULL,
    release_version  TEXT,
    release_sort_key TEXT,
    status_kind      TEXT NOT NULL,
    counts_as_pass   BOOLEAN NOT NULL,
    automation_status TEXT NOT NULL,
    source           TEXT NOT NULL,
    executed_by      UUID,
    executed_at      TIMESTAMPTZ,
    duration_ms      BIGINT,
    test_type_id     UUID,
    test_level_id    UUID,
    feature_ids      UUID[]
);
COMMENT ON TABLE execution_fact IS
    'Append-only fact log. Deliberately duplicates dimensions to avoid joins. Do NOT defer this — '
    'reporting built on live joins cannot be converted to a read model incrementally (plan §6e).';

CREATE INDEX execution_fact_case_idx     ON execution_fact (company_id, case_id, executed_at DESC);
CREATE INDEX execution_fact_plan_idx     ON execution_fact (company_id, plan_id, executed_at DESC);
CREATE INDEX execution_fact_release_idx  ON execution_fact (company_id, release_sort_key);
CREATE INDEX execution_fact_suite_idx    ON execution_fact USING GIST (suite_path);
CREATE INDEX execution_fact_feature_idx  ON execution_fact USING GIN (feature_ids);

CREATE TABLE suite_run_summary (
    company_id           UUID NOT NULL,
    run_id              UUID NOT NULL,
    suite_id            UUID NOT NULL,
    configuration_id    UUID NOT NULL,
    suite_version_no    INT,
    passed              INT NOT NULL DEFAULT 0,
    failed              INT NOT NULL DEFAULT 0,
    blocked             INT NOT NULL DEFAULT 0,
    skipped             INT NOT NULL DEFAULT 0,
    not_run             INT NOT NULL DEFAULT 0,
    total               INT NOT NULL DEFAULT 0,
    membership_snapshot JSONB,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, suite_id, configuration_id)
);
COMMENT ON TABLE suite_run_summary IS
    'COUNTS are stored; percentages are derived on read. Storing percentages would make the '
    '"of executed" vs "of total" toggle impossible, and nested rollups must SUM LEAF COUNTS — '
    'averaging child percentages over unequal case counts is simply wrong arithmetic (plan §6p).';

CREATE TABLE plan_snapshot (
    company_id        UUID NOT NULL,
    plan_id          UUID NOT NULL,
    case_id          UUID NOT NULL,
    configuration_id UUID NOT NULL,
    execution_id     UUID,
    status_kind      TEXT,
    counts_as_pass   BOOLEAN,
    executed_at      TIMESTAMPTZ,
    release_version  TEXT,
    PRIMARY KEY (plan_id, case_id, configuration_id)
);
COMMENT ON TABLE plan_snapshot IS
    'The CURRENT latest result per case per configuration. Upserted as results land, including on '
    'late-arriving CI ingestion. Frozen into the evidence bundle at plan close (plan §6p/§6n).';

CREATE TABLE case_health (
    case_id                UUID PRIMARY KEY,
    company_id              UUID NOT NULL,
    project_id             UUID NOT NULL,
    outdated_spec_changed  BOOLEAN NOT NULL DEFAULT false,
    stale_approval         BOOLEAN NOT NULL DEFAULT false,
    condition_drift        BOOLEAN NOT NULL DEFAULT false,
    stale_automation       BOOLEAN NOT NULL DEFAULT false,
    never_executed         BOOLEAN NOT NULL DEFAULT false,
    long_unexecuted        BOOLEAN NOT NULL DEFAULT false,
    unlinked               BOOLEAN NOT NULL DEFAULT false,
    failing_repeatedly     BOOLEAN NOT NULL DEFAULT false,
    orphaned               BOOLEAN NOT NULL DEFAULT false,
    incomplete             BOOLEAN NOT NULL DEFAULT false,
    computed_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE case_health IS
    'Signals are DERIVED, never authored — no UI writes here, so they self-heal when the underlying '
    'condition is fixed. No "mark as not-stale" button exists to lie with (plan §6o).';

CREATE INDEX case_health_outdated_idx  ON case_health (company_id, project_id) WHERE outdated_spec_changed;
CREATE INDEX case_health_stale_appr_idx ON case_health (company_id, project_id) WHERE stale_approval;
CREATE INDEX case_health_never_exec_idx ON case_health (company_id, project_id) WHERE never_executed;
CREATE INDEX case_health_unlinked_idx   ON case_health (company_id, project_id) WHERE unlinked;

CREATE TABLE report_definition (
    id               UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    type             TEXT NOT NULL,
    name             TEXT NOT NULL,
    parameters       JSONB NOT NULL DEFAULT '{}'::jsonb,
    schedule_cron    TEXT,
    owner_id         UUID REFERENCES app_user(id),
    shared_with_team_id UUID REFERENCES team(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN report_definition.parameters IS
    'Includes the AGGREGATION POLICY (latest / latest-per-config / worst / best / all). Two teams '
    'reading "94% pass" otherwise mean entirely different things (plan §6e).';

CREATE TABLE report_artifact (
    id              UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    definition_id   UUID REFERENCES report_definition(id),
    format          TEXT NOT NULL CHECK (format IN ('PDF','MD','HTML','CSV','XLSX')),
    storage_key     TEXT NOT NULL,
    filter_snapshot JSONB NOT NULL,
    data_watermark  TIMESTAMPTZ,
    generated_by    UUID REFERENCES app_user(id),
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN report_artifact.filter_snapshot IS
    'Determinism: a report you cannot reproduce is a report nobody trusts (plan §6e).';

-- =============================================================================
-- SHARED
-- =============================================================================

CREATE TABLE audit_event (
    id               UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES company(id),
    project_id       UUID,
    actor_user_id    UUID REFERENCES app_user(id),
    actor_token_id   UUID REFERENCES api_token(id),
    action           TEXT NOT NULL,
    target_type      TEXT NOT NULL,
    target_ids       UUID[] NOT NULL,
    changes          JSONB,
    bulk_operation_id UUID,
    ip               INET,
    user_agent       TEXT,
    request_id       TEXT,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_event IS
    'Append-only, NEVER updated. Emitted from domain events so ingestion and scheduled jobs are '
    'audited identically to UI actions (plan §6j).';
COMMENT ON COLUMN audit_event.target_ids IS
    'An ARRAY so one event covers a bulk operation over 143 cases — the audit log reads '
    '"Ana attached 2 conditions to 143 cases", not 143 lines (plan §6f).';

CREATE INDEX audit_event_target_idx ON audit_event (company_id, target_type, occurred_at DESC);
CREATE INDEX audit_event_actor_idx  ON audit_event (company_id, actor_user_id, occurred_at DESC);
CREATE INDEX audit_event_ids_idx    ON audit_event USING GIN (target_ids);

CREATE TABLE outbox (
    id              UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','SENT','FAILED','DEAD')),
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE outbox IS
    'Transactional outbox. A failing Slack webhook must never roll back a test result (plan §5).';

CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE ingestion_job (
    id              UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    project_id      UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id          UUID REFERENCES test_run(id),
    format          TEXT NOT NULL CHECK (format IN ('JUNIT','TESTNG','ALLURE','CUCUMBER')),
    idempotency_key TEXT NOT NULL,
    raw_payload     JSONB,
    raw_storage_key TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    matched_count   INT NOT NULL DEFAULT 0,
    unmatched_count INT NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ingestion_idempotency UNIQUE (company_id, idempotency_key)
);
COMMENT ON CONSTRAINT uq_ingestion_idempotency ON ingestion_job IS
    'CI retries must not double-post results (plan §5).';

CREATE TABLE automation_key_mapping (
    id                   UUID PRIMARY KEY,
    company_id            UUID NOT NULL REFERENCES company(id),
    project_id           UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    normalized_test_name TEXT NOT NULL,
    case_id              UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    confirmed_by         UUID REFERENCES app_user(id),
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
    key        TEXT PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID        REFERENCES app_user(id)
);

CREATE TABLE instance_audit_event (
    id             UUID PRIMARY KEY,
    actor_user_id  UUID        REFERENCES app_user(id),
    action         TEXT        NOT NULL,
    company_id     UUID        REFERENCES company(id),
    target_type    TEXT,
    target_ids     UUID[],
    changes        JSONB,
    ip             INET,
    user_agent     TEXT,
    request_id     TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE instance_audit_event IS
    'Operator actions that span or target companies: provisioning, suspension, admin appointment, '
    'cross-client user-directory access. Deliberately separate from the per-company audit_event.';
COMMENT ON COLUMN instance_audit_event.company_id IS
    'The company acted upon, where applicable. Operator access INTO a company is recorded in BOTH '
    'logs — here and in that company''s own audit_event, flagged as instance-operator access. '
    'Because companies are separate clients, "has anyone outside our team looked at this?" must '
    'have a real answer (plan §6b, §6s).';

CREATE INDEX instance_audit_event_company_idx ON instance_audit_event (company_id, occurred_at DESC);
CREATE INDEX instance_audit_event_actor_idx   ON instance_audit_event (actor_user_id, occurred_at DESC);

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
-- Two things to verify under Testcontainers before trusting this:
--   1. The app role must NOT be BYPASSRLS and must not own these tables
--      (owners bypass RLS unless FORCE ROW LEVEL SECURITY is set).
--   2. current_setting(..., true) returns NULL rather than erroring when the
--      GUC is unset — so an unset context yields ZERO rows, not all rows.
--      A test must assert exactly that, because the failure mode is silent.
-- =============================================================================

DO $$
DECLARE
    t TEXT;
    company_owned TEXT[] := ARRAY[
        'company_membership','team','team_membership','role_assignment','api_token',
        'project','key_sequence','classification_dimension','classification_value','case_status',
        'suite','suite_condition','test_case','test_case_draft','test_case_version','test_step',
        'shared_step','condition','case_condition','case_classification','case_automation_tag',
        'case_tag','case_condition_optout',
        'attachment','suite_version','test_plan_version','doc_page_version',
        'test_plan','plan_scope_item','configuration','test_run','test_execution','step_result',
        'evidence','defect_link',
        'doc_space','doc_page','doc_block','doc_link','artifact',
        'review','review_participation','review_comment','work_item','work_item_link',
        'derived_item_annotation',
        'execution_fact','suite_run_summary','plan_snapshot','case_health',
        'report_definition','report_artifact',
        'audit_event','outbox','ingestion_job','automation_key_mapping'
    ];
BEGIN
    FOREACH t IN ARRAY company_owned LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name = t) THEN
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                'CREATE POLICY company_isolation ON %I USING '
                '(company_id = current_setting(''app.current_company'', true)::uuid)', t);
        ELSE
            RAISE EXCEPTION 'RLS list names a table that does not exist: %', t;
        END IF;
    END LOOP;
END $$;

COMMENT ON TABLE company IS
    'A separate CLIENT organisation — the top-level isolation boundary (plan §6s). Provisioned by '
    'the provision-company operator script, never through an admin UI. NOT under RLS: the Instance '
    'Admin reads it to resolve a slug before any company context exists.';
