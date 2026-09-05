-- =============================================================================
-- V11 — crm: clients and their contacts (plan 009 module Mc, plan 018)
--
-- THE VERSION IS V11, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-05:
-- V1..V10, V9 being M6's and V10 M7's. Every other number written down for this
-- module is wrong and each is somewhere a reader will find it: the execution
-- breakdown's q3 chose V3 (M1 took it), its Position table projected V8 (M4 took
-- V7 and M5 took V8), and 009's own column struck through V10, V11 and V12
-- before giving up on arithmetic and saying "whatever `ls` says on the day".
-- That is the SIXTH correction to that column in five weeks.
--
-- The failure it avoids is silent and no test in this repository can show it:
-- `out-of-order` is unset in application.yml, so a lower-numbered migration
-- arriving after a higher one is NEVER APPLIED, and Testcontainers builds every
-- test database from empty, so the conflict cannot appear in a suite.
--
-- TWO TABLES, AND NEITHER IS A PRINCIPAL. A client is a record describing an
-- organisation; a contact is a name, a role, an email and a phone. No app_user,
-- no company_membership, no invitation, no credential column, no authentication
-- path (scope D1, confirmed 2026-08-30). This is what makes a Client safe as a
-- scope-LESS design rather than merely a cheap one: the only reason it would
-- have needed to become a level of the hierarchy is to gate a login, and there
-- is no login to gate. If a client portal is ever wanted, D1 is REOPENED rather
-- than extended -- it is a second authentication path and a real confidentiality
-- boundary, and bolting one onto a lookup table is how a tenancy model gets
-- quietly broken.
--
-- A CLIENT CARRIES NO SCOPE. It is not a third gate. The two stay exactly as the
-- roles spec §A1 states them -- company filter, then project grant -- and a
-- client is visible iff you hold client:read at company scope or you can see a
-- project that references it. A scope here would have meant a new
-- RoleAssignment target, a fourth term in evaluateCompanyWide and a second place
-- authorization is decided, and the rollups this feature wants need none of it.
--
-- NO RATE, NO BUDGET, NO INVOICE, NO HOURS COLUMN. Those are M8's six tables,
-- and the money boundary is the sharpest in the expansion (scope §7): an Account
-- Manager knows the client and the hours and never a rate. A rate column here
-- would put one join between client:read and what an hour costs. Stated so the
-- absence is not read as a gap -- V9's and V10's headers say the same.
-- =============================================================================

-- 1. CLIENT -- the company's own customer.
--
-- status is ACTIVE|ARCHIVED and is not a soft-delete flag. DELETE /clients/{id}
-- exists and answers 409 while any project references the client (018 D-k):
-- detaching those projects on the way past would silently leave live work with
-- no customer, and this product already decided the other way for projects --
-- "archiving hides the project from lists and freezes its issues. Nothing is
-- deleted, and its keys stay resolvable."
--
-- account_manager_id is NULLABLE and null is the default rather than a gap:
-- client-new.html's picker opens on "-- nobody yet --", and a client with no
-- account manager is not incomplete. ON DELETE SET NULL, not RESTRICT: a person
-- leaving the company must not pin a client record open.
--
-- uq_client_name is (company_id, name) and not (name). That ordering is the
-- rule this schema is built on -- company_id leads every composite index and
-- every previously-global uniqueness constraint -- and here it is also what
-- makes the constraint the safety net the CLI and MCP create arms rely on in
-- place of an Idempotency-Key, which is project-scoped and therefore unavailable
-- to a company-scoped create (018 T1, CREATE_WITHOUT_A_KEY in both packages).
CREATE TABLE client (
    id                 UUID        PRIMARY KEY,
    company_id         UUID        NOT NULL REFERENCES company(id),
    name               TEXT        NOT NULL,
    location           TEXT,
    industry           TEXT,
    notes              TEXT,
    status             TEXT        NOT NULL DEFAULT 'ACTIVE',
    account_manager_id UUID        REFERENCES app_user(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID,
    lock_version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_client_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_client_name   CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_client_name   UNIQUE (company_id, name)
);
COMMENT ON TABLE client IS
    'A company''s own customer -- an ATTRIBUTE of a project, never a level of the hierarchy '
    '(scope D1). It carries no isolation semantics and no permission scope: the two gates stay '
    'company filter then project grant, and a client is visible iff you hold client:read at '
    'company scope or you can see a project that references it. NO rate, cost or invoice column '
    'here or on any table this module owns -- that is M8.';
COMMENT ON COLUMN client.status IS
    'ARCHIVED hides the client from the default list and from the project picker -- except on a '
    'project already attached to it, which keeps naming it. This is what DELETE points at when a '
    'project references the client (018 D-k).';
COMMENT ON COLUMN client.account_manager_id IS
    'Nullable, and null is the default rather than a gap -- client-new.html opens on '
    '"-- nobody yet --". SET NULL on the person''s deletion, so a leaver cannot pin the record.';
CREATE INDEX ix_client_status  ON client (company_id, status, name);
CREATE INDEX ix_client_manager ON client (company_id, account_manager_id);

-- 2. CLIENT_CONTACT -- a person to talk to, and NOT a person who can log in.
--
-- Read the table comment above for why that is a rule rather than an omission.
-- There is no user_id, no password, no invitation token and no email
-- verification column -- and ClientContactInput in openapi.yaml has no
-- credential field either, so there is nothing to send even if a column
-- appeared. Both halves are asserted by CrmCreatesNoLoginTest, which counts rows
-- in app_user, company_membership and invitation across a contact write: "no
-- login" is a claim about what is ABSENT, and a service test cannot see an
-- insert it does not make.
--
-- email is NOT unique and NOT citext: two contacts at one client may share an
-- inbox (invoices@, which is exactly what client.html's third contact is), and a
-- uniqueness constraint here would be the first step toward treating this row as
-- an identity. The cost is that a retried contact-add leaves a duplicate; it is
-- one row on a list the client page renders in full, with a delete beside it,
-- and it is recorded in CREATE_WITHOUT_A_KEY rather than left to be discovered.
CREATE TABLE client_contact (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    client_id    UUID        NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    role         TEXT,
    email        TEXT,
    phone        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_client_contact_name CHECK (length(btrim(name)) > 0)
);
COMMENT ON TABLE client_contact IS
    'Contact information, not an identity (scope D1). NO credential, NO invitation, NO app_user '
    'link -- and adding one reopens D1 rather than extending it. CASCADE from client: these rows '
    'describe who to talk to at an organisation, and an organisation this company no longer '
    'records has nobody to talk to.';
CREATE INDEX ix_client_contact_client ON client_contact (company_id, client_id, name);

-- 3. THE CONSTRAINT V2 SHIPPED THIS COLUMN WITHOUT.
--
-- V2 added project.client_id as UUID NULL with NO REFERENCES and NO entity
-- mapping, deliberately and in one breath: ArchitectureRulesTest sweeps @Entity
-- fields for readerless and writerless columns in BOTH directions, so a mapping
-- added by M0 would have been an entry Mc then had to remove. The constraint
-- lands here and the mapping in 018 T3, together, as V2's own comment promised.
--
-- STILL NULLABLE, FOREVER, and there is no synthetic "Internal" client row to
-- make it NOT NULL (scope D1 rule 1). That row would put a fake customer on
-- every internal project, pollute every client rollup with a company billing
-- itself, and make "which projects are contracted work?" -- the question this
-- column exists to answer -- unanswerable. A project with no client is not
-- incomplete and nothing in the product renders it as missing one.
--
-- ON DELETE RESTRICT, not CASCADE and not SET NULL. Deleting a client that
-- projects point at is refused, and ClientService answers 409 naming archiving
-- before the database ever sees the statement; this FK is the backstop for a
-- path the service does not own.
--
-- NOTE WHAT THIS FK DOES NOT CHECK. It is on id alone, so it does NOT check that
-- the client is in the same company -- referential integrity is not subject to
-- RLS, and a cross-company id would pass it. There is no composite-FK precedent
-- in this schema (V10 uses plain REFERENCES throughout), so that rule is
-- enforced where every other cross-table rule here is: ClientService reads the
-- client back through RLS before writing and answers 400 with a field error on
-- clientId (018 D-l). A 500 from a constraint would be both the wrong status and
-- an oracle for what exists in another client's tenancy, which §6t forbids.
ALTER TABLE project
    ADD CONSTRAINT fk_project_client
    FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE RESTRICT;
CREATE INDEX ix_project_client ON project (company_id, client_id);

-- 4. ROW LEVEL SECURITY -- two more forced policies, predicate byte-identical to
--    V1's (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares the TEXT and fails equally on an explicit WITH CHECK -- a
--    USING-only policy reuses USING as the insert check, so the read boundary IS
--    the write boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to the table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows (docs/specs/tenancy-invariants.md).
--
-- client_contact is in this list for person_skill's reason one module over: a
-- child table whose parent is isolated looks correct in every test that uses one
-- company, and the missing predicate is invisible by reading. Its rows carry a
-- named person's email and phone number at a client organisation, which is
-- exactly the kind of row a missing policy hands to a competitor on the same
-- instance.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['client','client_contact'];
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

    IF array_length(protected, 1) <> 2 THEN
        RAISE EXCEPTION 'expected 2 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 5. NOTHING ELSE IS TOUCHED, AND EACH ABSENCE IS A DECISION.
--
-- tcms_seed_project_defaults and tcms_seed_company_defaults are NOT restated.
-- Nothing here is a per-project or per-company default row: a new company starts
-- with NO clients, and that is the correct empty state rather than an unfilled
-- one -- a company building its own products never creates one, and
-- clients-empty.html is the only trace it ever sees. The functions are not
-- reproduced "to be safe" either: CREATE OR REPLACE FUNCTION replaces the whole
-- body (V3 §5, V8 §5, V9 §6, V10 §7), so reproducing one is the only way this
-- file could break it, and a block omitted from the reproduction silently stops
-- seeding.
--
-- ExportCompanyService and ExportTables need NO change: ExportTables derives the
-- walked set from the tables carrying a company_isolation policy, so a
-- company-owned table with the standard policy is exported BY CONSTRUCTION.
-- project_key_sequence arrived that way in V2. If ExportTablesTest fails after
-- this file, the policy above is wrong -- not the exporter.
--
-- The app role needs no explicit GRANT: ALTER DEFAULT PRIVILEGES FOR ROLE
-- tcms_owner covers every new table in schema public
-- (deploy/postgres/initdb/10-tcms-roles.sql).
--
-- NO permission keys are seeded here. client:read, client:create, client:edit
-- and client:delete have existed since V2 lines 363-366, with Finance holding
-- client:read alone, and they are already in all five documents SeedDataTest and
-- matrixCopies.test.ts compare. Adding or re-granting one would be five
-- coordinated document edits and a byte-pinned test, for nothing this module
-- needs.
