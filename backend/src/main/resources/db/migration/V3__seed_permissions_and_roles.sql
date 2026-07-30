-- =============================================================================
-- TCMS — Reference data: the permission catalogue and the six built-in roles
--
-- SOURCE OF TRUTH: docs/specs/2026-07-30-tcms-roles-permissions-naming.md Part A.
-- That document is authoritative for the grants; this file is its transcription,
-- and docs/specs/openapi.yaml PermissionKey is the same catalogue expressed for
-- the frontend. All three must agree — SeedDataTest asserts the parts a reader
-- cannot check, including the count, so a permission added to one side and not
-- the others fails the build.
--
-- DML only. Triggers, generated columns and the seed FUNCTIONS are V2: schema and
-- data migrations stay separate files, so correcting a grant does not re-run a
-- trigger definition.
--
-- Everything below is instance-level (permission, role, role_permission are all on
-- the ERD §0.1 exception list) and therefore carries no RLS. That is what makes it
-- seedable from a migration at all — the COMPANY-owned vocabularies cannot be, and
-- live in V2's tcms_seed_company_defaults instead.
--
-- Idempotent throughout (ON CONFLICT DO NOTHING), so a re-run repairs rather than
-- fails. Grants are additive by design; revoking one is a corrective migration,
-- because forward-only means never editing this file after it has shipped.
-- =============================================================================


-- =============================================================================
-- 1. THE PERMISSION CATALOGUE — 55 keys
-- =============================================================================
--
-- There is deliberately NO WILDCARD. Instance Admin's grant is derived below by
-- joining the whole catalogue, not by storing a '*' sentinel: a sentinel cannot
-- distinguish "holds every permission" from "holds a permission that happens to
-- be present", so adding a permission Instance Admin did not hold would still
-- render as full coverage. The frontend learned this the expensive way — see
-- frontend/src/lib/permissions.ts.
--
-- `area` matches lib/permissions.ts exactly, because both sides group a 55-item
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
    ('instance:audit',    'system',     'Read the instance-wide audit log')
ON CONFLICT (key) DO NOTHING;


-- =============================================================================
-- 2. THE SIX BUILT-IN ROLES
-- =============================================================================
--
-- These six are the complete role set in v1 — no custom roles, not even
-- clone-and-edit (spec A2). Enforcement is permission-based everywhere (nothing
-- checks a role NAME), so a role designer later is additive: built_in = false rows
-- plus a company_id column, no change to the evaluator.
--
-- Fixed ids rather than generated ones: role_permission references them, and a
-- corrective migration needs to name a role without a lookup subquery.

INSERT INTO role (id, scope, name, description, built_in) VALUES
    ('10000000-0000-4000-8000-000000000001', 'SYSTEM',  'Instance Admin',
     'The operator. Everything, in every company — provisions companies and appoints their '
     'administrators. Reach into a client''s content is audited in that client''s own trail.',
     true),
    ('10000000-0000-4000-8000-000000000002', 'COMPANY', 'Company Admin',
     'Full control of the companies they are assigned to, and nothing outside them. Sees every '
     'project in the company, present and future, with no per-project grant. Assigned by an '
     'Instance Admin.',
     true),
    ('10000000-0000-4000-8000-000000000003', 'PROJECT', 'Test Lead',
     'Full control of assigned projects — deletes others'' content, amends others'' results, closes '
     'runs, gives final approval, reads the project audit trail, manages project settings.',
     true),
    ('10000000-0000-4000-8000-000000000004', 'PROJECT', 'Test Engineer',
     'The working contributor role, granted explicitly — never a default. Creates and edits '
     'everything, executes, reviews, files defects, maintains the shared library. Deletes only '
     'their own work; cannot approve, close runs, delete a suite subtree, or read the audit trail.',
     true),
    ('10000000-0000-4000-8000-000000000005', 'PROJECT', 'Automation Service',
     'Non-human, attached to API tokens rather than people. Deliberately the smallest role in the '
     'product: a leaked CI token can post results and add quarantine stubs, and cannot edit or '
     'delete a real case, close a run, or read reports.',
     true),
    ('10000000-0000-4000-8000-000000000006', 'PROJECT', 'Viewer',
     'Read-only, plus commenting and report export. The default on joining a company or a project. '
     'Built for the stakeholder — often the client themselves — who reviews coverage without '
     'writing any of it.',
     true)
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 3. GRANTS
-- =============================================================================
--
-- Effective permissions are a union of allows with no explicit deny, gated on
-- company membership (spec A1). Membership is a PRECONDITION, never a source, so
-- nothing below needs a "member" pseudo-role.
--
-- The OWN qualifier is NOT a permission. case:edit, case:delete and
-- evidence:delete are OWN-qualified for Test Engineer, resolved against
-- created_by at check time (spec A5) — deliberately not modelled as separate
-- case:edit:own keys, which would double the catalogue and still not express
-- "in any state".

-- Instance Admin — DERIVED from the catalogue, never enumerated. If a later
-- migration adds a permission and forgets Instance Admin, SeedDataTest fails on
-- the derived count rather than the screen quietly claiming full coverage.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000001', key FROM permission
ON CONFLICT DO NOTHING;

-- Company Admin — everything a Test Lead has, over every project in the company,
-- plus company configuration and project creation. Holds NO SYSTEM-scope
-- permission: the client boundary is what Company Admin must not cross.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000002', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),('case:delete'),
    ('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),
    ('case:approve'),('suite:approve'),('plan:approve'),
    ('plan:create'),('plan:edit'),('plan:delete'),
    ('run:create'),('run:execute'),('run:close'),('result:submit'),('result:amend'),
    ('evidence:attach'),('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read'),('company:settings'),('company:members'),('company:teams'),
    ('company:vocabulary'),('company:idp'),('project:create'),('token:issue'),('audit:view')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- Test Lead — Company Admin scoped to one project. Keeps company:read (they are a
-- member and every check is a real permission check, spec A3) but nothing else at
-- company scope.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000003', key
  FROM (VALUES
    ('suite:manage'),('suite:delete'),('case:read'),('case:create'),('case:edit'),('case:delete'),
    ('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),
    ('case:approve'),('suite:approve'),('plan:approve'),
    ('plan:create'),('plan:edit'),('plan:delete'),
    ('run:create'),('run:execute'),('run:close'),('result:submit'),('result:amend'),
    ('evidence:attach'),('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),('artifact:edit'),('artifact:delete'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- Test Engineer — three lines separate this from Test Lead (spec A4), and each is
-- visible as an absence here rather than as a rule somewhere else:
--   1. creative vs destructive — no suite:delete, plan:delete, run:close,
--      result:amend; case:delete and evidence:delete are OWN-qualified;
--   2. review vs approve — holds *:review, none of *:approve;
--   3. content vs configuration — no project:settings, project:members,
--      project:audit, integration:manage.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key
  FROM (VALUES
    ('suite:manage'),('case:read'),('case:create'),('case:edit'),('case:delete'),
    ('library:manage'),
    ('case:comment'),('case:review'),('suite:review'),('plan:review'),
    ('plan:create'),('plan:edit'),
    ('run:create'),('run:execute'),('result:submit'),('evidence:attach'),('evidence:delete'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),
    ('artifact:read'),('artifact:edit'),
    ('project:read'),('company:read')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- Automation Service — five permissions, and the smallest grant in the product
-- because a leaked CI token is the realistic breach (spec A6). case:create is
-- restricted to the quarantine folder, which is a service-layer rule: a permission
-- cannot carry a folder. Deliberately no report:view — a token that can read every
-- report is a data-exfiltration path with no operational benefit.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000005', key
  FROM (VALUES
    ('case:read'),('case:create'),('run:create'),('result:submit'),('evidence:attach')
  ) AS granted(key)
ON CONFLICT DO NOTHING;

-- Viewer — the default at EVERY entry point: company invitation, SSO JIT
-- provisioning, and project assignment (spec A7). Holds case:comment and
-- report:export on purpose: it is built for the client reviewing their own
-- coverage, and a reviewer who cannot comment reviews by email instead.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000006', key
  FROM (VALUES
    ('case:read'),('case:comment'),('project:read'),('company:read'),
    ('report:view'),('report:export'),('doc:read'),('artifact:read')
  ) AS granted(key)
ON CONFLICT DO NOTHING;
