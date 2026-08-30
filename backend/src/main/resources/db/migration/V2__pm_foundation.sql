-- =============================================================================
-- V2 — the project-management expansion's foundation (plan 010, module M0 of 009)
--
-- Four schema changes and one catalogue. Nothing here is user-visible: no
-- endpoint, no screen and no new module. What it buys is that the eight modules
-- after it each add their own tables against a permission catalogue and a key
-- counter that already exist, instead of eight edits to three documents a test
-- pins byte-for-byte.
--
-- V1 ONLY EVER RUNS ON AN EMPTY DATABASE (its own header says so). This file does
-- not: it runs against a live instance, so every statement below is written to be
-- safe on one — the backfill is ON CONFLICT DO NOTHING, the CHECK is dropped and
-- recreated rather than altered, and the new column is nullable with no default.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. PER-PROJECT ISSUE KEYS — a NEW table, not a primary-key change
-- -----------------------------------------------------------------------------
--
-- D5 of the expansion scope says issue keys are per project — ACME-1, not TC-1 —
-- and the scope document proposed widening key_sequence's primary key to
-- (company_id, entity_type, project_id) with project_id nullable for the five
-- existing company-scoped types. THAT STATEMENT CANNOT RUN: a primary key cannot
-- contain a nullable column. Plan 009 review R1 reversed it and this is the
-- replacement.
--
-- key_sequence is NOT TOUCHED. Its five entity types, its allocator
-- (tenancy/DisplayKeys.java), its seed function and its seven readers
-- (CompanyProvisioningService, ExportCompanyService, SeedDemoDataCommand,
-- ProjectEdit, RunEdit, PlanCreateService) keep the company-scoped numbering that
-- §6t argued for, and which nothing about issues makes wrong.
--
-- NO PREFIX COLUMN, unlike key_sequence. The prefix IS project.key, which already
-- exists with uq_project_key and ck_project_key_format '^[A-Z0-9]{2,10}$' — so a
-- prefix column here would be a second copy of a string that is immutable by
-- contract, and the two copies would be edited months apart. That is the same
-- reasoning DisplayKeys' header gives for taking the prefix FROM THE ROW rather
-- than from a Java constant; here the row is one table over.
--
-- The allocation statement is key_sequence's, one row per project instead of one
-- per company — which also relieves the contention §6t accepted: issue creation
-- in one project no longer serialises against every other project in the company.
--
--     UPDATE project_key_sequence s SET next_value = s.next_value + :n
--       FROM project p
--      WHERE s.company_id = :c AND s.project_id = :p AND p.id = s.project_id
--     RETURNING p.key, s.next_value - :n;
--
-- NOT MAX(key_seq)+1, which races under concurrent creation and surfaces as a
-- unique-constraint violation under load and never in a test.
--
-- ON DELETE CASCADE on project_id, like every other project-owned table in V1 and
-- unlike key_sequence's company_id, which has none because a company is suspended
-- rather than deleted. A counter is meaningless without the project it counts for,
-- and without the cascade the row is a foreign key that REFUSES a project delete —
-- which is not a safety property, only a delete that fails at the last table.
CREATE TABLE project_key_sequence (
    company_id UUID   NOT NULL REFERENCES company(id),
    project_id UUID   NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    next_value BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (company_id, project_id)
);
COMMENT ON TABLE project_key_sequence IS
    'The per-project issue-key counter (expansion scope D5). One row per project, created by '
    'tcms_seed_project_defaults so every creation path gets it — POST /projects, provision-company '
    'and seed-demo-data all call that function. Allocate with the UPDATE ... RETURNING in this '
    'migration''s header, joined to project for the prefix; NOT MAX(n)+1, which races. Gap-free '
    'numbering is NOT guaranteed and is not required: the contract is uniqueness and immutability. '
    'Distinct from key_sequence, which stays company-scoped for the five test-asset types and is '
    'not touched by this migration — see the header for why the proposed primary-key change to '
    'that table could never have run.';
COMMENT ON COLUMN project_key_sequence.next_value IS
    'The next number to issue. Starts at 1 so the first allocation is ACME-1 and not ACME-0 — '
    'issue keys are user-facing and 1-based, and there is no zero-padding anywhere.';


-- The backfill, and the reason it is a loop rather than one INSERT ... SELECT.
--
-- Flyway connects as tcms_owner, which is NOT a superuser and holds NO BYPASSRLS,
-- and `project` has carried FORCE ROW LEVEL SECURITY since V1 — FORCE meaning the
-- policy applies to the table's owner too. With app.current_company unset, a plain
--
--     INSERT INTO project_key_sequence (company_id, project_id)
--     SELECT company_id, id FROM project;
--
-- reads ZERO ROWS and inserts nothing, silently, and this migration reports
-- success. That is the first of the two silent RLS traps in
-- docs/specs/tenancy-invariants.md, met head-on. `company` is on the ERD §0.1
-- exception list and carries no policy, so it can be walked; setting the GUC per
-- company then makes that company's projects visible through the ordinary policy.
--
-- The alternative — ALTER TABLE project NO FORCE ROW LEVEL SECURITY around the
-- backfill — was rejected: it relaxes the isolation layer inside a transaction
-- that also seeds a permission catalogue, and a migration that can turn the policy
-- off is a migration that can be edited into leaving it off.
--
-- set_config's third argument is `true` — transaction-local. Flyway runs a
-- migration in one transaction, so the GUC is gone when this file ends.
DO $$
DECLARE
    c UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        INSERT INTO project_key_sequence (company_id, project_id, next_value)
        SELECT company_id, id, 1 FROM project
        ON CONFLICT (company_id, project_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;


-- Isolation, with the SAME predicate every other company-owned table carries.
-- SchemaConventionTest asserts all three properties — ENABLED, FORCED, and the
-- predicate byte-identical to the other 74 — so a widened policy here fails the
-- build rather than leaking a counter across clients. Its count moves 74 -> 75,
-- which is what makes the predicate check non-vacuous for this table.
--
-- Enabled AFTER the backfill above, deliberately: with the policy live, the loop
-- would also have to satisfy it on the INSERT side, and an INSERT policy that
-- refuses silently is the second of the two traps.
ALTER TABLE project_key_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_key_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY company_isolation ON project_key_sequence USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);


-- -----------------------------------------------------------------------------
-- 2. EVERY NEW PROJECT GETS ITS COUNTER
-- -----------------------------------------------------------------------------
--
-- Seeded here rather than in ProjectCreateService because this function is called
-- by BOTH creation paths — ProjectCreateService and CompanyProvisioningService
-- (twice) — and by seed-demo-data through them. One INSERT here covers every path
-- that exists and every path a later module adds; a Java-side insert covers the
-- one its author remembered.
--
-- Reproduced IN FULL rather than patched, for V18's reason, restated in V1's own
-- header above this function: CREATE OR REPLACE replaces the whole body. Only the
-- final block differs from V1's.
CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
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

    -- V2. The per-project issue-key counter. Structural, like the configuration
    -- and the doc space above: without this row ProjectKeys.allocate matches
    -- nothing and the first issue in the project cannot be numbered. Its absence
    -- is an EMPTY RESULT rather than an error — an UPDATE ... RETURNING that
    -- matches no row is not a failure — which is exactly why the row is created
    -- here, once, rather than repaired by an INSERT ... ON CONFLICT on the hot
    -- path of every issue creation forever.
    INSERT INTO project_key_sequence (company_id, project_id, next_value)
    VALUES (p_company_id, p_project_id, 1)
    ON CONFLICT (company_id, project_id) DO NOTHING;
END $$;


-- -----------------------------------------------------------------------------
-- 3. AN ISSUE IS A LINK SOURCE
-- -----------------------------------------------------------------------------
--
-- doc_link is the link authority and its source_type CHECK is the enumeration of
-- what may assert a link (expansion scope §3 — "the authority table enumerates its
-- source kinds; the source module is not imported"). An issue verifying a
-- statement in the documentation is the epic-to-spec edge M1 needs, and adding the
-- value here is what lets `tracker` write one without `documentation` learning
-- that `tracker` exists.
--
-- Dropped and recreated rather than altered: a CHECK constraint has no ALTER form
-- that widens it. Postgres validates the new constraint against existing rows on
-- ADD, which is correct and cheap — the widening cannot fail.
--
-- DROPPED BY LOOKUP, NOT BY NAME. V1 writes the constraint inline on the column
-- with no CONSTRAINT clause, so Postgres generated its name; hard-coding a guess
-- at it here would be a migration that works on the instance somebody checked and
-- fails on the next. The new one IS named, so the next migration to touch it can
-- say so.
DO $$
DECLARE
    v_name TEXT;
BEGIN
    SELECT conname INTO v_name
      FROM pg_constraint
     WHERE conrelid = 'doc_link'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%source_type%';

    IF v_name IS NULL THEN
        RAISE EXCEPTION 'doc_link has no CHECK constraint mentioning source_type — V1''s column '
                        'definition changed, and adding ISSUE blind would widen nothing';
    END IF;

    EXECUTE format('ALTER TABLE doc_link DROP CONSTRAINT %I', v_name);
END $$;

ALTER TABLE doc_link ADD CONSTRAINT ck_doc_link_source_type
    CHECK (source_type IN ('TEST_CASE','SUITE','DEFECT','DOC_PAGE','ISSUE'));
COMMENT ON CONSTRAINT ck_doc_link_source_type ON doc_link IS
    'What may assert a link (expansion scope §3 — the authority table enumerates its source kinds '
    'and does not import the source module). Wider than com.tcms.documentation.LinkSourceType on '
    'purpose: DEFECT, DOC_PAGE and, since V2, ISSUE are admitted by the column and offered by no '
    'writer, and narrowing the column to match would have to be undone by the slice that builds '
    'one. Named here, unlike V1''s generated name, so the next migration can drop it by name.';

-- com.tcms.documentation.LinkSourceType is NOT widened, and that is the design
-- rather than an omission — see the constraint comment above and the enum's own
-- header. M1 adds the member in the slice that first writes such a row.


-- -----------------------------------------------------------------------------
-- 4. A PROJECT MAY BELONG TO A CLIENT
-- -----------------------------------------------------------------------------
--
-- D1: a Client is an ATTRIBUTE of a Project, not a level of the hierarchy. This is
-- that attribute, and it is the whole of it — one nullable column on one existing
-- table.
--
-- THE COLUMN ONLY. No REFERENCES, because the `client` table belongs to module Mc
-- and does not exist yet; Mc adds the foreign key when it does. An earlier draft of
-- plan 009 wrote `REFERENCES client(id)` in the same sentence as "the FK is added
-- by Mc" (review R3).
--
-- Nullable and unread until Mc. NOT mapped on com.tcms.project.Project either:
-- ArchitectureRulesTest sweeps @Entity fields for columns with no reader and for
-- columns with no writer, in both directions, so a mapping added now would need an
-- entry in one of those maps that Mc would then have to remove. An unmapped column
-- is invisible to both rules and satisfies hibernate.ddl-auto: validate, which
-- checks that mapped entities match the schema and not the reverse.
--
-- "None (internal)" is a real answer and not a gap (story C2): a company running
-- its own product has no client, and NULL says so without a sentinel row.
ALTER TABLE project ADD COLUMN client_id UUID;
COMMENT ON COLUMN project.client_id IS
    'The client this project is for, or NULL for internal work — D1, where a Client is an '
    'attribute of a Project rather than a level of the hierarchy. Ships in V2 with NO foreign key '
    'and NO reader: the client table belongs to module Mc, which adds the constraint and the entity '
    'mapping together. A project with no client is not incomplete; nothing renders it as missing.';


-- -----------------------------------------------------------------------------
-- 5. THE CATALOGUE — 56 KEYS BECOME 89
-- -----------------------------------------------------------------------------
--
-- SOURCE OF TRUTH: docs/specs/2026-07-30-tcms-roles-permissions-naming.md Part A,
-- as amended by D3 and D28 of the project-management expansion scope. This is its
-- transcription; openapi.yaml's PermissionKey is the same catalogue expressed for
-- the frontend, and lib/permissions.ts groups it for humans. All four agree or
-- SeedDataTest fails.
--
-- STILL NO WILDCARD. Instance Admin's grant is derived at the end of this file by
-- joining the catalogue, exactly as V1 did — a '*' sentinel cannot distinguish
-- "holds every permission" from "holds a permission that happens to be present".
--
-- Thirty-three, not the "~35" §7 estimated. The difference is not rounding: each
-- key below separates two roles from each other, and the ones that did not were
-- dropped rather than seeded. issue:comment is the sharpest case — it is here
-- BECAUSE Viewer holds it, which is the same argument §A3 used to refuse
-- suite:comment and plan:comment, two keys that would have been granted to nobody.
INSERT INTO permission (key, area, description) VALUES
    -- tracker (PROJECT) — M1
    ('issue:read',         'tracker',   'Read epics, stories and tasks'),
    ('issue:create',       'tracker',   'Create an issue at any level'),
    ('issue:edit',         'tracker',   'Edit an issue, including its status and assignee'),
    ('issue:delete',       'tracker',   'Delete an issue'),
    ('issue:comment',      'tracker',   'Comment on an issue without any write access to it'),
    ('issue:workflow',     'tracker',   'Configure the project''s issue types, statuses and transitions'),
    ('issue:import',       'tracker',   'Import a project''s issues from an external tracker'),
    -- board & backlog (PROJECT) — M2
    ('board:read',         'board',     'See the project''s boards and backlog'),
    ('board:rank',         'board',     'Order the backlog'),
    ('board:configure',    'board',     'Create boards and define their columns, swimlanes and WIP limits'),
    -- releases (PROJECT) — M3
    ('release:read',       'releases',  'See releases and the roadmap'),
    ('release:manage',     'releases',  'Create, edit and delete releases and their scope'),
    ('release:publish',    'releases',  'Mark a release released'),
    -- time (PROJECT) — M6
    ('time:log',           'time',      'Log your own hours'),
    ('time:read',          'time',      'See other people''s time entries'),
    ('time:edit',          'time',      'Amend a time entry somebody else recorded'),
    ('time:approve',       'time',      'Approve or reject a submitted timesheet period'),
    -- staffing — M7. Two scopes, deliberately: read and allocate are asked about
    -- ONE PROJECT and are held by a PROJECT-scope role, while the directory, the
    -- profiles and the absences are company-wide views held by COMPANY-scope
    -- roles. A key asked only company-wide but granted to a PROJECT-scope role is
    -- an INERT ROW — the defect §43 repaired for company:read, still visible as
    -- four entries in SeedDataTest.MATRIX_DIVERGENCE_BY_DECISION.
    ('staffing:read',      'staffing',  'See this project''s allocations and capacity'),
    ('staffing:allocate',  'staffing',  'Allocate a person to this project for a period'),
    ('staffing:directory', 'staffing',  'See the company-wide capacity view and the skills directory'),
    ('staffing:profile',   'staffing',  'Maintain people''s skills and staffing profiles'),
    ('staffing:absence',   'staffing',  'Record absence on somebody else''s behalf'),
    -- clients (COMPANY) — Mc
    ('client:read',        'clients',   'See clients, their contacts and their projects'),
    ('client:create',      'clients',   'Record a new client organisation'),
    ('client:edit',        'clients',   'Edit a client and its contacts'),
    ('client:delete',      'clients',   'Delete a client'),
    -- money (COMPANY) — M8. THE SHARPEST NEW BOUNDARY IN THE PRODUCT: these seven
    -- keys are held by Finance, Company Admin and Instance Admin and by nobody
    -- else. A Test Engineer with time:log logs hours and cannot see what they cost
    -- (story T1), and an Account Manager has no Billing entry in their sidebar at
    -- all (story F2, story M2). That needs a test asserting the NEGATIVE, which is
    -- SeedDataTest.moneyIsFinanceAndAdminsOnly.
    ('ratecard:read',      'money',     'See rate cards and what an hour is charged at'),
    ('ratecard:manage',    'money',     'Create and edit rate cards'),
    ('billing:read',       'money',     'See budgets, invoices and margin'),
    ('billing:budget',     'money',     'Set and edit a project''s budget'),
    ('billing:expense',    'money',     'Record an expense against a project'),
    ('billing:invoice',    'money',     'Draft and edit an invoice'),
    ('billing:issue',      'money',     'Issue an invoice, locking its lines');


-- -----------------------------------------------------------------------------
-- 6. THREE MORE BUILT-IN ROLES — nine assignable, eleven rows
-- -----------------------------------------------------------------------------
--
-- D3 amends "the six are the complete role set" rather than reversing it: still
-- seeded, still immutable, still NO CUSTOM ROLES, not even clone-and-edit. This
-- is the third time that sentence has been superseded (V15's Company Member,
-- V40's Agent, and now these three), which is why it is written as an amendment
-- in the roles spec instead of being reworded again.
--
-- role has no company_id — it is on the ERD §0.1 exception list — and uq_role_name
-- is global, so these are three plain rows.
--
-- Fixed ids, continuing V1's series, for V1's reason: role_permission references
-- them and anything naming a role can do so without a lookup subquery.
INSERT INTO role (id, scope, name, description, built_in, assignable) VALUES
    ('10000000-0000-4000-8000-000000000009', 'PROJECT', 'Project Manager',
     'Runs the delivery of one project: epics, stories and tasks, the board and the backlog, '
     'releases and the roadmap, staffing this project and approving its timesheets. Reads the test '
     'repository and comments on it but does not author or approve test cases — that is the Test '
     'Lead''s line, and the two roles are granted together on a project that needs both.',
     true, true),
    ('10000000-0000-4000-8000-000000000010', 'COMPANY', 'Account Manager',
     'Owns the client relationship: client organisations, their contacts, and who is free to staff '
     'them. Sees no money at all — no rate card, no budget, no invoice, no margin — and Billing is '
     'absent from their sidebar rather than refused inside it.',
     true, true),
    ('10000000-0000-4000-8000-000000000011', 'COMPANY', 'Finance',
     'Owns the money: rate cards, budgets, expenses, invoices and margin. Reads clients so an '
     'invoice can name one, and writes nothing about them. Holds no project grant, so a '
     'COMPANY-scope role does not become a way to read every project''s test content.',
     true, true);


-- Project Manager — PROJECT scope. The delivery half of a project, whole:
-- issue:*, board:*, release:*, time:* and this project's staffing, plus the four
-- project-configuration keys and the project audit trail.
--
-- What it deliberately does NOT hold: any *:approve, and any test-authoring or
-- execution key. D3's line is "no test-case approval", and the rest follows from
-- it — a role that could create and edit cases but not approve them would be Test
-- Engineer with a different name, and D3's own note says there is no developer
-- role for exactly that reason. case:read and case:comment are held, because a PM
-- who cannot read the coverage they are managing is managing a number.
--
-- company:read is granted for V1's reason (they are a member, and every check is a
-- real permission check) and is the INERT ROW §43 documented: a PROJECT-scope
-- grant of a COMPANY-scope key is unreachable from evaluateCompanyWide, and what
-- actually carries the permission company-wide is V15's Company Member baseline.
-- SeedDataTest.MATRIX_DIVERGENCE_BY_DECISION pins it as deliberate, alongside the
-- identical rows for Test Lead, Test Engineer and Viewer.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000009', key
  FROM (VALUES
    ('case:read'),
    ('case:comment'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read'),
    ('issue:read'),('issue:create'),('issue:edit'),('issue:delete'),('issue:comment'),
    ('issue:workflow'),('issue:import'),
    ('board:read'),('board:rank'),('board:configure'),
    ('release:read'),('release:manage'),('release:publish'),
    ('time:log'),('time:read'),('time:edit'),('time:approve'),
    ('staffing:read'),('staffing:allocate')
  ) AS granted(key);

-- Account Manager — COMPANY scope, and the narrowest company role in the product.
-- Clients and their contacts, the skills directory and absences, company:read.
--
-- NO project:read and NO report:view, and both omissions are load-bearing: this
-- role is COMPANY-scope, so either key would confer it over EVERY project in the
-- company, present and future, which is the boundary §A1 draws and the reason
-- Company Member exists at all. The client page lists its projects from the client
-- aggregate under client:read, which is Mc's job to build.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000010', key
  FROM (VALUES
    ('company:read'),
    ('client:read'),('client:create'),('client:edit'),('client:delete'),
    ('staffing:directory'),('staffing:profile'),('staffing:absence')
  ) AS granted(key);

-- Finance — COMPANY scope. Reads clients, writes money.
--
-- Holds client:read and NOT client:edit: an invoice has to name a client, and a
-- Finance user editing the client record is the Account Manager's job under a
-- different login. staffing:directory because utilisation is a money question as
-- much as a staffing one. Same two omissions as Account Manager, same reason.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000011', key
  FROM (VALUES
    ('company:read'),
    ('client:read'),
    ('staffing:directory'),
    ('ratecard:read'),('ratecard:manage'),
    ('billing:read'),('billing:budget'),('billing:expense'),('billing:invoice'),
    ('billing:issue')
  ) AS granted(key);


-- Company Admin — holds everything below SYSTEM scope, which is what makes it
-- Company Admin. The client boundary is what it must not cross, so it gains all 33
-- new keys and no SYSTEM key.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000002', key FROM permission
 WHERE key IN (
    'issue:read','issue:create','issue:edit','issue:delete','issue:comment',
    'issue:workflow','issue:import',
    'board:read','board:rank','board:configure',
    'release:read','release:manage','release:publish',
    'time:log','time:read','time:edit','time:approve',
    'staffing:read','staffing:allocate','staffing:directory','staffing:profile',
    'staffing:absence',
    'client:read','client:create','client:edit','client:delete',
    'ratecard:read','ratecard:manage',
    'billing:read','billing:budget','billing:expense','billing:invoice','billing:issue');

-- Test Lead — "unchanged + issue:*" (D3), taken literally, which is a decision the
-- owner took on 2026-08-30 and not an oversight of this migration.
--
-- THE CONSEQUENCE, STATED HERE BECAUSE IT MOVES A NUMBER THIS FILE USED TO ASSERT:
-- Test Lead minus Test Engineer WAS exactly seven keys — "giving approvals, and
-- adding and managing permission on the project" — and is now TEN. The three that
-- joined are issue:delete, issue:workflow and issue:import, and they belong to the
-- second half of that sentence rather than a third category: deleting an issue and
-- configuring the project's workflow are both "managing the project". §A4, this
-- file's own grant comment above Company Admin, roleMatrix.ts's LEAD_ONLY and
-- SeedDataTest.testEngineerToTestLeadBoundary all carried the seven and all move
-- together with this INSERT.
--
-- THE SECOND CONSEQUENCE, which no test can catch: a project staffed with a Test
-- Lead and no Project Manager cannot configure a board, cut a release or approve a
-- timesheet — only Company Admin can. That is what D3 says. It is flagged for M2,
-- M3 and M6 in plan 009's prompts rather than repaired here by widening a grant
-- nobody decided to widen.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000003', key
  FROM (VALUES
    ('issue:read'),('issue:create'),('issue:edit'),('issue:delete'),('issue:comment'),
    ('issue:workflow'),('issue:import'),
    ('board:read'),
    ('release:read'),
    ('time:log')
  ) AS granted(key);

-- Test Engineer — issue:create and issue:edit per D28 (D3 gave edit only; a
-- developer who cannot create an issue cannot file a bug or break a story down,
-- and D3 says a developer IS a Test Engineer), plus time:log, plus the reads and
-- the comment key the tier below it holds.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key
  FROM (VALUES
    ('issue:read'),('issue:create'),('issue:edit'),('issue:comment'),
    ('board:read'),
    ('release:read'),
    ('time:log')
  ) AS granted(key);

-- Automation Service — issue:read and board:read, and nothing else (D3). Still the
-- smallest grant in the product, because a leaked CI token is the realistic breach.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000005', key
  FROM (VALUES
    ('issue:read'),('board:read')
  ) AS granted(key);

-- Viewer — issue:read and board:read per D3, plus release:read and issue:comment.
--
-- issue:comment is an EXTENSION of D3's list and the reason is §A3's own: the
-- comment tier exists because a Viewer holds it — "built for the case where the
-- client reviews test coverage themselves without gaining any write access" — and
-- a comment key that no role holds without also holding issue:edit would buy
-- nothing, which is the argument §A3 used to refuse suite:comment and
-- plan:comment. Taken as a decision on 2026-08-30, recorded in plan 010 D-M0-6.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000006', key
  FROM (VALUES
    ('issue:read'),('issue:comment'),('board:read'),('release:read')
  ) AS granted(key);

-- Agent — §A6a: Test Engineer entire, minus company:read. Derived here the same
-- way SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse derives the delta,
-- rather than restated, so the two cannot disagree.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000008', rp.permission_key
  FROM role_permission rp
 WHERE rp.role_id = '10000000-0000-4000-8000-000000000004'
   AND rp.permission_key <> 'company:read'
   AND NOT EXISTS (
       SELECT 1 FROM role_permission held
        WHERE held.role_id = '10000000-0000-4000-8000-000000000008'
          AND held.permission_key = rp.permission_key);

-- Instance Admin — DERIVED from the catalogue, never enumerated, exactly as V1
-- does it and for the same reason: if a key is added and Instance Admin is
-- forgotten, SeedDataTest fails on the derived count rather than a screen quietly
-- claiming full coverage. Last, so it picks up all 33 keys above.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000001', p.key
  FROM permission p
 WHERE NOT EXISTS (
       SELECT 1 FROM role_permission held
        WHERE held.role_id = '10000000-0000-4000-8000-000000000001'
          AND held.permission_key = p.key);
