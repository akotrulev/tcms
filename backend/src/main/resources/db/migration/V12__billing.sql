-- =============================================================================
-- V12 — billing: rate cards, budgets, invoices and expenses (plan 009 M8, 019)
--
-- THE VERSION IS V12, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-05:
-- V1..V11, V11 being Mc's. Plan 009's column reserved V10, then V11, then V12
-- for this module, striking each through in turn -- SEVEN entries for one
-- module, and the reason the number is right this time is that it was read, not
-- derived. `out-of-order` is unset in application.yml, so a lower-numbered
-- migration arriving after a higher one is NEVER APPLIED, and Testcontainers
-- builds every test database from empty, so no suite here can show it.
--
-- SEVEN TABLES, NOT THE SIX PLAN 009 NAMED. invoice_line_entry is the seventh
-- and story M2 requires it: "the time entries show which invoice they are on".
-- This module MAY NOT write a column on time_entry, so the link is a join owned
-- here with NO FOREIGN KEY to that table -- the polymorphic crossing scope §3
-- prescribes, already paid for by attachment, doc_link, issue_link and
-- time_entry.subject_id itself (ERD §0.1, the fifth thing the rule does not
-- buy). It is also the only thing standing between this module and
-- double-billing.
--
-- A DRAFT STORES NO LINES (019 D-c). invoice_line and invoice_line_entry are
-- written EXACTLY ONCE, inside the issue transaction, and never updated. That is
-- why neither carries lock_version: optimistic locking guards a concurrent
-- UPDATE, and nothing updates these rows. A draft is a header plus a live
-- computation over the approved entries that exist now, which is what makes
-- invoice.html's "approve them and this draft recalculates" true with no
-- recalculate endpoint -- and what makes the lock structural rather than a check
-- somebody can forget on the sixth handler.
--
-- KEY_SEQUENCE IS NOT TOUCHED (019 D-k). invoice.seq is this module's own
-- counter with uq_invoice_seq as its arbiter. Widening key_sequence would cost a
-- CHECK change, a backfill, AND a CREATE OR REPLACE of tcms_seed_company_defaults
-- -- and CREATE OR REPLACE FUNCTION replaces the WHOLE body, which V11's header
-- names as exactly how this gets broken. Four migrations running have declined
-- to touch that function. So does this one.
--
-- NO MARGIN COLUMN, NO COST RATE, NO FIXED FEE, NO RETAINER, NO BALANCE, NO
-- PAYMENT. Time and materials only (scope D24) and every one of those absences
-- is a decision, not a gap -- see 019 D-q. An AI assistant's hours are a COST,
-- never a line: `expense` is where such a cost would be recorded, and no path in
-- this module turns one into an invoice line.
--
-- ONE NAMING QUIRK, DELIBERATE AND WORTH ONE PARAGRAPH SO IT IS NOT UNDONE. The
-- COMMENT ON strings below say "the invoice screen" where these -- block comments
-- say invoice.html, and the reason is SchemaCommentTest: it reads the catalog and
-- resolves every `x.y` token in a comment against table x's columns, so a mockup
-- whose stem collides with a table name reads as a claim about a column that does
-- not exist. `invoice` is the first table in this schema to collide with a mockup
-- -- V9's directory.html, V10's staffing.html and V11's client-new.html all have
-- stems no table shares -- and that rule has no exemption list on its qualified
-- arm, by design: it cannot tell a stale claim from a shorthand, and neither can
-- a reader who greps the identifier. So the catalog says the screen and this
-- header says the file, which is the half a reader of the FILE wants anyway.
-- =============================================================================

-- 1. RATE_CARD -- one per client, and the carrier of the currency.
--
-- uq_rate_card_client is (company_id, client_id), which is both §0.1 point 3's
-- ordering rule and the "one card per client" decision (019 D-d) in the same
-- constraint. There is no second card, no draft card and no card version: an
-- entry carries its own effective_from, so a repricing is a new ENTRY rather
-- than a new card, and every historical rate stays readable on the one row set a
-- Finance user edits.
--
-- THE CURRENCY IS HERE AND NOT ON THE INVOICE. That is what makes an invoice's
-- currency a property of its client rather than a field somebody picks on a
-- form, and it is what makes a cross-client invoice structurally impossible
-- rather than merely refused -- lines can only be priced from one card, and a
-- card has one currency. invoice.currency is a frozen COPY of this, and its own
-- comment says why.
--
-- ck_rate_card_currency is a regex and not a lookup table. There is no currency
-- reference data in this schema and inventing some would be a fourteenth
-- vocabulary to seed per company; ISO 4217 is three uppercase letters, and the
-- set of codes a client company actually uses is theirs rather than ours.
CREATE TABLE rate_card (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    client_id    UUID        NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    currency     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_rate_card_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_rate_card_client   UNIQUE (company_id, client_id)
);
COMMENT ON TABLE rate_card IS
    'ONE PER CLIENT (019 D-d). The currency lives HERE and not on the invoice, which is what makes '
    'an invoice''s currency a property of its client rather than a field on a form -- and what makes '
    'a cross-client invoice structurally impossible rather than merely refused. billing.html carries '
    'a EUR client and a CHF client two rows apart, which is why the company totals on '
    '/billing/summary are ARRAYS of {amount, currency} (019 D-l).';
COMMENT ON COLUMN rate_card.client_id IS
    'NOT NULL, so an internal project -- project.client_id is nullable forever and there is no '
    'synthetic "Internal" client (scope D1 rule 1) -- can never be priced. That is the intended '
    'shape: billing.html draws Internal Platform with no client, no budget and 126 approved hours, '
    'in the same table as three billed projects, with no warning tone (019 D-p). CASCADE from '
    'client: a card prices one organisation''s work and an organisation this company no longer '
    'records has no work to price, exactly as client_contact cascades one table over.';
CREATE INDEX ix_rate_card_client ON rate_card (company_id, client_id);

-- 2. RATE_CARD_ENTRY -- what an hour costs, from a date, at one of three scopes.
--
-- THE EFFECTIVE DATE IS ON THE ENTRY, NOT THE CARD. billing.html's Acme rows
-- read "from 1 Jan 2026" for two roles and "from 1 Jul 2026" for one person --
-- three rates live at once on one card, each authoritative for a different span
-- of days. A card-level effective date could not express that without a second
-- card, and a second card breaks uq_rate_card_client above.
--
-- ck_rate_card_entry_shape is the same device as ck_time_entry_subject_shape one
-- migration over, and it is here for the same reason: a shape the database holds
-- is one the application cannot drift from. PERSON needs person_id and no
-- role_id, ROLE needs role_id and no person_id, ALL_ROLES needs neither -- so a
-- PERSON row that also names a role, which would price the same hour two ways
-- depending on which column a query read, cannot be stored at all.
--
-- role_id points at `role`, which is INSTANCE-LEVEL and on ERD §0.1's exception
-- list: the seven built-ins are shared and carry no company_id. That is not a
-- tenancy hole here, because the ENTRY carries company_id and is policed by it;
-- what crosses is only the name of a role every company already sees.
--
-- NO hourly_rate ON THE CARD ITSELF and no default rate column: a card with no
-- ALL_ROLES entry prices nothing, and hours under it fall into the exclusion
-- bucket 019 D-g counts and names. A default hidden on the parent would price
-- them silently at a number nobody chose.
CREATE TABLE rate_card_entry (
    id             UUID          PRIMARY KEY,
    company_id     UUID          NOT NULL REFERENCES company(id),
    rate_card_id   UUID          NOT NULL REFERENCES rate_card(id) ON DELETE CASCADE,
    scope          TEXT          NOT NULL,
    person_id      UUID          REFERENCES app_user(id) ON DELETE CASCADE,
    role_id        UUID          REFERENCES role(id)     ON DELETE CASCADE,
    hourly_rate    NUMERIC(12,2) NOT NULL,
    effective_from DATE          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT ck_rate_card_entry_scope CHECK (scope IN ('PERSON','ROLE','ALL_ROLES')),
    CONSTRAINT ck_rate_card_entry_shape CHECK (
        (scope = 'PERSON'    AND person_id IS NOT NULL AND role_id IS     NULL) OR
        (scope = 'ROLE'      AND person_id IS     NULL AND role_id IS NOT NULL) OR
        (scope = 'ALL_ROLES' AND person_id IS     NULL AND role_id IS     NULL)),
    CONSTRAINT ck_rate_card_entry_rate CHECK (hourly_rate >= 0)
);
COMMENT ON TABLE rate_card_entry IS
    'THE EFFECTIVE DATE IS HERE AND NOT ON THE CARD: billing.html''s Acme rows read "from 1 Jan 2026" '
    'for two roles and "from 1 Jul 2026" for one person. The rate for an hour is the most specific '
    'entry effective on or before that hour''s spent_on -- PERSON, then ROLE, then ALL_ROLES -- and '
    'where two ROLE entries match, the HIGHER wins (019 D-d). ck_rate_card_entry_shape is the same '
    'device as ck_time_entry_subject_shape one migration over: a shape the database holds is one the '
    'application cannot drift from.';
COMMENT ON COLUMN rate_card_entry.scope IS
    'PERSON beats ROLE beats ALL_ROLES, and a ROLE entry matches only a role the person actually '
    'holds ON THAT PROJECT (019 D-d). Where two ROLE entries both match, the HIGHER rate wins -- '
    'deterministic by construction and a decision rather than a coin toss: refusing the hour as '
    'ambiguous would turn a rate-card configuration a Finance user could not have anticipated into '
    'hours that silently fail to bill.';
COMMENT ON COLUMN rate_card_entry.effective_from IS
    'Effective ON OR BEFORE the hour''s spent_on -- as of the DAY WORKED, never the day invoiced. '
    'An entry added in July does not reprice June, and that is the same promise invoice_line.'
    'rate_applied keeps one step further downstream.';
COMMENT ON COLUMN rate_card_entry.role_id IS
    'Points at `role`, which is INSTANCE-LEVEL and on ERD §0.1''s exception list -- the seven '
    'built-ins carry no company_id. The isolation lives on THIS row, which carries company_id and '
    'its own FORCE policy; what is shared is only the name of a role every company already sees.';
CREATE INDEX ix_rate_card_entry_card
    ON rate_card_entry (company_id, rate_card_id, effective_from DESC);

-- 3. BUDGET -- a ceiling in hours or in money, one per project.
--
-- ck_budget_shape is why currency is nullable: NULL unless basis = 'MONEY', held
-- by the database for ck_time_entry_subject_shape's reason. A HOURS budget with
-- a currency would be a row two readers interpret differently -- one ignoring the
-- column, one rendering "1200 hours in EUR" -- and neither would be wrong about
-- what it read.
--
-- WHAT COMES OFF THIS TABLE AT PROJECT SCOPE IS A PERCENTAGE, NEVER AN AMOUNT
-- (019 D-h). GET /projects/{id}/budget-burn is gated on project:read and its
-- schema has NO amount and NO currency member at all: it serves basis,
-- approvedHours, budgetHours (null unless the basis is HOURS) and burnPercent.
-- That is the one endpoint in this module a Test Engineer may call, and what
-- makes it safe is that there is nothing on it to withhold. A MONEY-basis budget
-- therefore renders as a percentage ALONE on every project-scope surface, and
-- where the project's client has no rate card that percentage is UNKNOWN rather
-- than zero.
--
-- Rejected: a project-scope budget:view permission. The catalogue is closed and
-- every role''s grants are seeded, so a new key is a migration, four documents
-- and a matrixCopies change -- to express a distinction project:read already
-- draws correctly.
CREATE TABLE budget (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    project_id   UUID          NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    basis        TEXT          NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,
    currency     TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_budget_basis  CHECK (basis IN ('HOURS','MONEY')),
    CONSTRAINT ck_budget_amount CHECK (amount > 0),
    CONSTRAINT ck_budget_shape  CHECK (
        (basis = 'HOURS' AND currency IS     NULL) OR
        (basis = 'MONEY' AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
    CONSTRAINT uq_budget_project UNIQUE (company_id, project_id)
);
COMMENT ON TABLE budget IS
    'A ceiling in HOURS or in MONEY, one per project. What a Project Manager reads off it is a '
    'PERCENTAGE and, for an hours budget, the hours -- never an amount and never a currency (019 '
    'D-h): /projects/{id}/budget-burn is gated on project:read and its schema has no money member at '
    'all. project-overview.html''s panel hint reads "hours, no rates" and its copy reads "What an '
    'hour costs is Finance''s, and is not on this page."';
COMMENT ON COLUMN budget.currency IS
    'NULL unless basis = ''MONEY'', enforced by ck_budget_shape rather than by the service. Same '
    'device as ck_time_entry_subject_shape (V9) and ck_rate_card_entry_shape above: a shape the '
    'database holds is one the application cannot drift from. A HOURS budget carrying a currency '
    'would read two different ways to two correct readers.';
COMMENT ON COLUMN budget.amount IS
    'HOURS when basis = ''HOURS'', money when basis = ''MONEY'' -- one column and not two, because a '
    'budget has exactly one ceiling and a second nullable column would allow a row with both set '
    'and no way to say which one is the ceiling.';
CREATE INDEX ix_budget_project ON budget (company_id, project_id);

-- 4. EXPENSE -- recorded, and never invoiced.
--
-- Scope D24 admits exactly ONE line shape -- approved time at a rate-card rate --
-- and invoice.html draws no expense row. So nothing in this module joins this
-- table to invoice_line, and there is no expense_id on any row above.
--
-- WHY IT EXISTS AT ALL: billing:expense is IN THE CATALOGUE, and a permission
-- with no reachable endpoint is the unreachable-row defect §43 names. Its callers
-- are the CLI and MCP verbs. NO MOCKUP DRAWS AN EXPENSES PANEL, so no screen is
-- built, and that absence is a decision rather than an unfinished task (019 D-j).
--
-- ck_expense_amount is <> 0 rather than > 0: a credit note against a recorded
-- cost is a negative expense, and refusing it would push the correction into a
-- deletion that loses the record of what was corrected.
CREATE TABLE expense (
    id          UUID          PRIMARY KEY,
    company_id  UUID          NOT NULL REFERENCES company(id),
    project_id  UUID          NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    incurred_on DATE          NOT NULL,
    description TEXT          NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    currency    TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID,
    CONSTRAINT ck_expense_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_expense_amount   CHECK (amount <> 0),
    CONSTRAINT ck_expense_what     CHECK (length(btrim(description)) > 0)
);
COMMENT ON TABLE expense IS
    'RECORDED AND NEVER INVOICED (scope D24, 019 D-j). D24 admits exactly one line shape -- approved '
    'time at a rate-card rate -- and the invoice screen draws no expense row. This table exists '
    'because '
    'billing:expense is IN THE CATALOGUE, and a permission with no reachable endpoint is the '
    'unreachable-row defect §43 names. Its callers are the CLI and MCP verbs; NO MOCKUP DRAWS AN '
    'EXPENSES PANEL, so no screen is built, and that absence is a decision rather than an unfinished '
    'task. An AI assistant''s usage is a cost and would be recorded here -- never as a line.';
COMMENT ON COLUMN expense.amount IS
    'CHECK is <> 0, not > 0. A credit note against a recorded cost is a negative expense; refusing '
    'it would push every correction into a DELETE that loses what was corrected.';
COMMENT ON COLUMN expense.project_id IS
    'A cost belongs to a project, never to a client directly -- the client is reached through '
    'project.client_id, which is nullable, so an INTERNAL project can carry expenses it can never '
    'carry invoices for. That asymmetry is the point: cost is recorded for work nobody bills.';
CREATE INDEX ix_expense_project ON expense (company_id, project_id, incurred_on);

-- 5. INVOICE -- a header while DRAFT, a record once ISSUED.
--
-- ck_invoice_issued is 019 D-c written as a constraint. While DRAFT this row
-- carries the client, the period and nothing else -- no seq, no number, no total
-- and NOT ONE invoice_line. Its lines are computed on every read from the
-- approved entries that exist NOW, which is what makes invoice.html's "approve
-- them and this draft recalculates" true with no recalculate endpoint, no
-- staleness flag and no window in which a draft disagrees with the timesheets
-- behind it. The two states have different SHAPES and the database holds the
-- difference, so a half-issued row -- a number with no total, a total with no
-- issued_at -- cannot be stored by any code path, including a future one.
--
-- The cost of a computed draft, stated: two drafts open over an overlapping
-- period for one client both offer the same hours and whichever issues first
-- takes them. That is visible -- the second recomputes on its next read with the
-- taking invoice named -- and a reservation model was rejected because it makes
-- a draft a LOCK on the company's time data, which is a far worse failure than a
-- recomputation somebody sees.
--
-- client_id is REFERENCES client(id) with NO cascade and no SET NULL, unlike
-- rate_card above. An issued invoice is a financial record of what was sent, and
-- deleting the client must not take it or hollow it out; DELETE /clients/{id}
-- already answers 409 while any project references the client (018 D-k), and
-- this is the same refusal one table over.
CREATE TABLE invoice (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    client_id    UUID          NOT NULL REFERENCES client(id),
    seq          BIGINT,
    number       TEXT,
    period_start DATE          NOT NULL,
    period_end   DATE          NOT NULL,
    status       TEXT          NOT NULL DEFAULT 'DRAFT',
    currency     TEXT          NOT NULL,
    terms_days   INTEGER       NOT NULL DEFAULT 30,
    total_amount NUMERIC(14,2),
    bill_to_id   UUID          REFERENCES client_contact(id) ON DELETE SET NULL,
    issued_at    TIMESTAMPTZ,
    issued_by    UUID          REFERENCES app_user(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_invoice_status CHECK (status IN ('DRAFT','ISSUED')),
    CONSTRAINT ck_invoice_period CHECK (period_end >= period_start),
    CONSTRAINT ck_invoice_terms  CHECK (terms_days >= 0),
    CONSTRAINT ck_invoice_issued CHECK (
        (status = 'DRAFT'  AND seq IS     NULL AND number IS     NULL
                           AND issued_at IS NULL AND total_amount IS NULL) OR
        (status = 'ISSUED' AND seq IS NOT NULL AND number IS NOT NULL
                           AND issued_at IS NOT NULL AND total_amount IS NOT NULL)),
    CONSTRAINT ck_invoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_invoice_seq    UNIQUE (company_id, seq),
    CONSTRAINT uq_invoice_number UNIQUE (company_id, number)
);
COMMENT ON TABLE invoice IS
    'A DRAFT IS A HEADER; AN ISSUED INVOICE IS A RECORD (019 D-c). While DRAFT this row carries the '
    'client, the period and nothing else -- no seq, no number, no total, and NOT ONE invoice_line. '
    'Its lines are computed on every read from the approved entries that exist NOW, which is what '
    'makes the invoice screen''s "approve them and this draft recalculates" true with no recalculate '
    'endpoint. ck_invoice_issued is that decision written as a constraint: the two states have '
    'different shapes and the database holds the difference.';
COMMENT ON COLUMN invoice.seq IS
    'THIS MODULE''S OWN COUNTER (019 D-k), allocated as MAX(seq)+1 for the company inside the issue '
    'transaction with uq_invoice_seq as the arbiter and ONE retry. Gap-free numbering is not '
    'guaranteed and is not required -- key_sequence''s own table comment says the same for display '
    'keys, for the same reason: a rolled-back transaction burns its number. The contract is '
    'uniqueness and immutability. key_sequence itself is NOT widened; see the header.';
COMMENT ON COLUMN invoice.number IS
    'The rendered form, INV-<issue year>-<seq zero-padded to 3> (INV-2026-041), stored rather than '
    'derived on read because the YEAR is the year of ISSUE and re-deriving it would need the '
    'issued_at of a row somebody may later be reading beside a copy sent to the client. NULL while '
    'DRAFT -- uq_invoice_number tolerates that, since NULLs do not conflict in a Postgres unique.';
COMMENT ON COLUMN invoice.currency IS
    'Copied from the client''s rate card at draft time and frozen. An invoice whose client''s card '
    'changed currency mid-period would otherwise carry lines in two.';
COMMENT ON COLUMN invoice.total_amount IS
    'Written once at issue, as the sum of the lines then computed. NULL while DRAFT, and that is '
    'ck_invoice_issued''s doing rather than an unfilled field: a draft''s total is whatever the '
    'live computation says this second, and storing it would be the staleness D-c exists to remove.';
COMMENT ON COLUMN invoice.client_id IS
    'NO cascade and no SET NULL, unlike rate_card.client_id. An issued invoice is the record of what '
    'was sent, and deleting the client must neither take it nor hollow it out -- DELETE /clients/{id} '
    'already answers 409 while a project references the client (018 D-k), and this refuses the same '
    'way from one table over.';
CREATE INDEX ix_invoice_client ON invoice (company_id, client_id, period_start DESC);
CREATE INDEX ix_invoice_status ON invoice (company_id, status, period_start DESC);

-- 6. INVOICE_LINE -- written once, inside the issue transaction, never updated.
--
-- NO lock_version, DELIBERATELY. Optimistic locking guards a concurrent UPDATE
-- and nothing updates these rows. That is what "the lines lock on issue" means
-- here: there is no operation that can change one because none was built, so the
-- immutability is structural rather than a guard on the sixth handler somebody
-- forgets. Adding the column back would be the first sign somebody has built an
-- update path -- and the header, not the column, is where that argument belongs.
--
-- rate_applied IS A NUMBER, NOT A rate_card_entry_id. A card edited next month
-- must not reprice an invoice sent last month, and a reference resolved at read
-- time would do exactly that. applies_to is the RESOLVED LABEL for the same
-- reason -- "Mira Sund", "Test Lead" -- so the row still reads correctly after
-- the person leaves and the role is renamed. scope, person_id and role_id are
-- kept beside them for grouping and filtering, and are the copy that may go
-- stale; the money and the label are the copy that may not.
--
-- amount is stored rather than derived as hours * rate_applied, and the rounding
-- is why: an invoice's line total is what was SENT to the client, to the cent
-- somebody may have already paid, not the product of two other columns recomputed
-- by whichever reader happens to round differently.
CREATE TABLE invoice_line (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    invoice_id   UUID          NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    project_id   UUID          NOT NULL REFERENCES project(id),
    scope        TEXT          NOT NULL,
    person_id    UUID          REFERENCES app_user(id),
    role_id      UUID          REFERENCES role(id),
    applies_to   TEXT          NOT NULL,
    hours        NUMERIC(10,2) NOT NULL,
    rate_applied NUMERIC(12,2) NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_line_scope CHECK (scope IN ('PERSON','ROLE','ALL_ROLES')),
    CONSTRAINT ck_invoice_line_hours CHECK (hours > 0),
    CONSTRAINT ck_invoice_line_rate  CHECK (rate_applied >= 0),
    CONSTRAINT ck_invoice_line_label CHECK (length(btrim(applies_to)) > 0)
);
COMMENT ON TABLE invoice_line IS
    'WRITTEN ONCE, INSIDE THE ISSUE TRANSACTION, AND NEVER UPDATED. No lock_version, deliberately: '
    'optimistic locking guards a concurrent UPDATE and nothing updates these rows. That is what "the '
    'lines lock on issue" means here -- there is no operation that can change one, so the '
    'immutability is structural rather than a guard on the sixth handler somebody forgets.';
COMMENT ON COLUMN invoice_line.rate_applied IS
    'THE RATE AS APPLIED, AS A NUMBER -- not a reference into rate_card_entry. A card edited next '
    'month must not '
    'reprice an invoice sent last month, and a reference resolved at read time would do exactly '
    'that. applies_to is the RESOLVED label ("Mira Sund", "Test Lead") for the same reason: the row '
    'must still read correctly after the person leaves and the role is renamed.';
COMMENT ON COLUMN invoice_line.amount IS
    'STORED, not derived as hours * rate_applied. A line total is what was SENT to the client, to '
    'the cent they may already have paid -- not a product two readers can round two ways.';
COMMENT ON COLUMN invoice_line.person_id IS
    'Kept beside applies_to for grouping and filtering, NOT for rendering. It is the copy that may '
    'go stale -- a deleted person, a renamed role -- while applies_to is the copy that may not. '
    'Nullable per scope, exactly as rate_card_entry''s columns are, and NOT shape-constrained here: '
    'these rows are written by one transaction from an entry that already passed '
    'ck_rate_card_entry_shape, so a second copy of that constraint would guard nothing.';
CREATE INDEX ix_invoice_line_invoice ON invoice_line (company_id, invoice_id, project_id);

-- 7. INVOICE_LINE_ENTRY -- the seventh table, and the anti-double-billing one.
--
-- NO FOREIGN KEY ON time_entry_id, and that is the module rule rather than an
-- omission. billing may not write a column on time_entry and may not import
-- time, so the reference is polymorphic exactly as attachment.owner_id,
-- doc_link.source_id, issue_link.target_id and time_entry.subject_id are (scope
-- §3, ERD §0.1's closing paragraphs). The cost is the one that pattern always
-- pays and is named there too: referential integrity is enforced in application
-- code, and a deleted entry can orphan a row here.
--
-- WHAT BOUNDS THAT COST IS THAT THE ROW IS A SNAPSHOT. hours, spent_on, the
-- person's name, the subject and the approval are COPIED rather than joined, so
-- an orphan still renders correctly on an invoice sent last quarter -- which is
-- the only thing an issued invoice's supporting detail has to do.
--
-- uq_invoice_line_entry_time is why this table is not optional. "Which approved
-- hours are already on an issued invoice" is a question with no other place to
-- live, and rows here exist ONLY for issued invoices (a draft stores no lines,
-- 019 D-c), so "already billed" is a fact about issued work and never about
-- somebody else's open draft. Rejected: a uuid[] column on invoice_line -- it
-- cannot be indexed for the exclusion query every draft read runs, and it makes
-- "is this entry billed" a scan of every array in the company.
CREATE TABLE invoice_line_entry (
    id              UUID          PRIMARY KEY,
    company_id      UUID          NOT NULL REFERENCES company(id),
    invoice_line_id UUID          NOT NULL REFERENCES invoice_line(id) ON DELETE CASCADE,
    time_entry_id   UUID          NOT NULL,
    spent_on        DATE          NOT NULL,
    hours           NUMERIC(5,2)  NOT NULL,
    person_name     TEXT          NOT NULL,
    subject_key     TEXT,
    subject_title   TEXT,
    approved_at     TIMESTAMPTZ   NOT NULL,
    approved_by     TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_line_entry_hours CHECK (hours > 0 AND hours <= 24),
    CONSTRAINT uq_invoice_line_entry_time  UNIQUE (company_id, time_entry_id)
);
COMMENT ON TABLE invoice_line_entry IS
    'THE SEVENTH TABLE, WHICH PLAN 009 DID NOT NAME, and story M2 requires: "the time entries show '
    'which invoice they are on". NO FOREIGN KEY ON time_entry_id, and that is the module rule rather '
    'than an omission -- billing may not write a column on time_entry and may not import time, so '
    'the reference is polymorphic exactly as attachment.owner_id, doc_link.source_id, '
    'issue_link.target_id and time_entry.subject_id are (scope §3, ERD §0.1). The cost is the one '
    'that pattern always pays: referential integrity is enforced in application code, and a deleted '
    'entry can orphan a row here. It is bounded by the row being a SNAPSHOT -- hours, person, '
    'subject and approval are copied, not joined -- so an orphan still renders correctly on an '
    'invoice sent last quarter.';
COMMENT ON COLUMN invoice_line_entry.time_entry_id IS
    'NO FOREIGN KEY, deliberately -- see the table comment. RLS still holds on both sides '
    '(company_id is NOT NULL and policed here and on time_entry), so an orphan is a DANGLING row, '
    'never a cross-client one. That is the same guarantee ERD §0.1 states for the other five '
    'polymorphic columns, and the same mitigation applies: soft delete upstream, plus the '
    'consistency check.';
COMMENT ON CONSTRAINT uq_invoice_line_entry_time ON invoice_line_entry IS
    'THE ANTI-DOUBLE-BILLING CONSTRAINT, and the reason this table is not optional. An entry already '
    'on an ISSUED invoice is excluded from every later draft, and this is what makes that a fact the '
    'database holds rather than a query somebody remembered to write.';
COMMENT ON COLUMN invoice_line_entry.approved_by IS
    'WHO DECIDED THAT PERSON''S PERIOD -- not who approved this project''s hours, and the difference '
    'is 019 D-f. A period is per person per fortnight and SPANS PROJECTS (016 D-i) while time:approve '
    'is a PROJECT-scope grant, so approving one approves entries against projects the approver cannot '
    'see. "These hours were approved" is a claim the data supports. "Somebody with authority over '
    'this project approved them" is NOT, and no column here makes it. A NAME rather than a user id, '
    'because it is a snapshot: the invoice must read correctly after the approver leaves.';
COMMENT ON COLUMN invoice_line_entry.subject_key IS
    'The issue''s display key and title, copied. Nullable because only subject_type = ''ISSUE'' '
    'entries are priceable at all (019 D-e) and a future kind might carry neither -- V9''s '
    'ck_time_entry_subject_shape makes project_id NULL for MEETING, LEAVE, TRAINING and INTERNAL, '
    'so four of the five kinds have no project, therefore no client, therefore no rate card, and '
    'can never reach this table.';
CREATE INDEX ix_invoice_line_entry_line ON invoice_line_entry (company_id, invoice_line_id);

-- 8. ROW LEVEL SECURITY -- seven more forced policies, predicate byte-identical
--    to V1's (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares the TEXT and fails equally on an explicit WITH CHECK -- a
--    USING-only policy reuses USING as the insert check, so the read boundary IS
--    the write boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to the table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows (docs/specs/tenancy-invariants.md).
--
-- ALL SEVEN, AND THE THREE WORTH READING TWICE ARE THE CHILDREN.
-- rate_card_entry, invoice_line and invoice_line_entry all have an isolated
-- parent, which is exactly the configuration person_skill and client_contact
-- name one and two migrations back: rows look correct in every test that uses
-- one company, and the missing predicate is invisible by reading. What is in
-- these rows makes the omission worse than usual -- a client's negotiated hourly
-- rates, and a line-by-line record of who worked on what for whom.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['rate_card','rate_card_entry','budget','invoice',
                              'invoice_line','invoice_line_entry','expense'];
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

    IF array_length(protected, 1) <> 7 THEN
        RAISE EXCEPTION 'expected 7 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 9. NOTHING ELSE IS TOUCHED, AND EACH ABSENCE IS A DECISION.
--
-- tcms_seed_project_defaults and tcms_seed_company_defaults are NOT restated.
-- Nothing here is a per-project or per-company default row: a new company starts
-- with NO rate card, NO budget and NO invoice, and every one of those is the
-- correct empty state rather than an unfilled one. The functions are not
-- reproduced "to be safe" either -- CREATE OR REPLACE FUNCTION replaces the
-- WHOLE body (V3 §5, V8 §5, V9 §6, V10 §7, V11 §5), so reproducing one is the
-- only way this file could break it, and a block omitted from the reproduction
-- silently stops seeding.
--
-- key_sequence is NOT widened, NOT backfilled and its CHECK is NOT changed. See
-- the header and 019 D-k: invoice.seq is this module's own counter and
-- uq_invoice_seq is its arbiter.
--
-- time_entry gains NO COLUMN. No invoiced_at, no invoice_id, no billed flag --
-- that is the module rule this file's seventh table exists to obey, and a column
-- there would be billing writing into M6's tables.
--
-- ExportCompanyService and ExportTables need NO change: ExportTables derives the
-- walked set from the tables carrying a company_isolation policy, so a
-- company-owned table with the standard policy is exported BY CONSTRUCTION. If
-- ExportTablesTest fails after this file, the policies above are wrong -- not
-- the exporter.
--
-- The app role needs no explicit GRANT: ALTER DEFAULT PRIVILEGES FOR ROLE
-- tcms_owner covers every new table in schema public
-- (deploy/postgres/initdb/10-tcms-roles.sql).
--
-- NO permission keys are seeded here. All seven this module needs have existed
-- since V2 lines 373-379 -- ratecard:read, ratecard:manage, billing:read,
-- billing:budget, billing:expense, billing:invoice and billing:issue, every one
-- in the `money` group -- and are already in all five documents SeedDataTest and
-- matrixCopies.test.ts compare. Adding or re-granting one would be five
-- coordinated document edits and a byte-pinned test, for nothing this module
-- needs -- and 019 D-h records the one new key that WAS considered
-- (project-scope budget:view) and why it was rejected.
