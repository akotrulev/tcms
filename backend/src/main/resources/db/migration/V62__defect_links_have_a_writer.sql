-- =============================================================================
-- V62 — defect_link gets its first writer, and stops claiming a tracker
--
-- C5 of docs/audits/2026-08-15-flow-review-and-improvements.md. The table has
-- been in V1 since M0, is read natively by TestExecutionRepository
-- .findDefectLinks, renders on three screens, and nothing has ever written a
-- row into it. `defect:create` was seeded by V3 in the same milestone and has
-- had no consumer for as long.
--
-- NO PERMISSION IS SEEDED OR MOVED. `defect:create` already exists and is a
-- Test Engineer key and up; the catalogue has taken no new key since V3, and one
-- granted to nobody is a 403 no administrator can grant their way out of.
--
-- THE DEFAULT IS THE POINT OF THIS MIGRATION
--
-- `provider TEXT NOT NULL DEFAULT 'JIRA'` was written for the M4 IssueTracker
-- port, where an adapter states which tracker it spoke to. M4 does not exist,
-- and the writer that arrives with this migration is a person pasting a key and
-- a URL into a form. A row it wrote would have said JIRA — naming a product this
-- instance does not talk to, has no credential for, and may never be pointed at.
--
-- That is a CLAIM, not a value, and it is the kind that survives: the column is
-- served as DefectLink.provider, so the first thing M4 would have to do is work
-- out which of its rows really came from Jira and which came from this default.
-- Dropping the default makes the application state the provider or fail to
-- insert, which is the behaviour the NOT NULL was always asking for.
--
-- The application's answer pre-M4 is DefectLinkProvider.EXTERNAL — "a link a
-- person made", the honest description of every row this writer can produce.
-- Chosen there rather than as a new DEFAULT here, deliberately: a default is
-- what the database supplies when the application declines to say, and the whole
-- defect of the old one was that it said something on the application's behalf.
--
-- WHY url BECOMES NOT NULL, AND WHY THAT IS SAFE ON AN EXISTING TABLE
--
-- openapi.yaml has marked DefectLink.url REQUIRED since M0 while the column has
-- been nullable the whole time. Nothing has violated the contract yet for the
-- only reason that matters — there is no writer, so there are no rows — and this
-- migration is the moment that stops being true. Left nullable, the first row
-- with a key and no URL is served as a required property that is absent, which
-- every generated client reads as a type error rather than as a missing link.
--
-- The usual objection to SET NOT NULL on an existing table is the table scan and
-- the rows that fail it. Neither applies, and not because the table is "probably
-- small": it is EMPTY BY CONSTRUCTION, provable from the code rather than
-- measured — no INSERT against defect_link exists anywhere in the product, and
-- this migration is the commit that adds the first one. If an operator has hand-
-- inserted a row with no URL, this fails loudly at deploy, which is the correct
-- outcome: a defect link nobody can follow is not a link.
--
-- status_cache AND synced_at ARE LEFT WRITERLESS ON PURPOSE
--
-- They are the tracker's status and the time it was last asked, and there is
-- nothing to ask. §234's C7 found the mirror of this — a column ENFORCED for
-- three slices and settable by nobody — so the question is asked here rather
-- than left implicit: is a column written by nobody and READ as though it meant
-- something the same defect pointing the other way?
--
-- For these two it is not, and the difference is what NULL says. status_cache is
-- declared nullable and DefectLinkView documents it as "the tracker's status as
-- of the last sync"; a row that has never been synced has no such status, so
-- NULL is a TRUE answer rather than a missing one. Two of the three readers
-- render it conditionally and simply show nothing. That is the opposite of
-- provider's DEFAULT 'JIRA', which asserted a fact nobody established.
--
-- So they keep their columns and gain no writer. What is forbidden — and it is
-- forbidden in DefectLinkInput rather than here, because it is a rule about
-- requests — is letting the CALLER set them: a client-supplied "status" would be
-- one person's guess recorded as a tracker's answer, which is the claim this
-- migration exists to stop making.
--
-- case_id KEEPS ITS NULL
--
-- Both foreign keys are nullable and this writer populates only execution_id.
-- All three readers are execution-scoped — findDefectLinks selects WHERE
-- d.execution_id IN (:executionIds) and nothing reads case_id at all — so
-- filling it would be writing a column no query names, on the reasoning that a
-- later one might. It is derivable through test_execution whenever that day
-- comes, and a backfill from execution_id is a one-line UPDATE.
--
-- THE INDEX IS THE FIRST ONE THIS TABLE HAS EVER HAD
--
-- V1 gave defect_link no index beyond its primary key, which cost nothing while
-- the table was empty and costs a sequential scan per run-detail render once it
-- is not. One partial unique index serves both needs:
--
--   * company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest. The read
--     now spells company_id out beside RLS (§235's rule) precisely so the
--     leading column is usable rather than supplied only by the policy.
--   * (company_id, execution_id) is its prefix, which is the lookup
--     findDefectLinks performs — so no second index is needed.
--   * It ENFORCES the replay the create endpoint answers 200 to. The service
--     looks up exactly these four columns, so two concurrent retries of one
--     intent collide on the constraint instead of writing a twin, and the
--     endpoint's idempotency is a property of the schema rather than of a
--     lookup somebody could forget to keep in step.
--   * Partial on execution_id IS NOT NULL because the column is nullable: NULLs
--     are not comparable, so an unpartitioned unique index would permit
--     duplicates among case-scoped rows anyway while indexing them for nothing.
--
-- NOT CREATE INDEX CONCURRENTLY, for V17's and V59's reason: Flyway runs a
-- migration in one transaction and CONCURRENTLY cannot run inside one. The table
-- is empty, so the ACCESS EXCLUSIVE lock is instantaneous.
--
-- NO NEW TABLE AND NO NEW POLICY. defect_link is already company-owned and
-- already under FORCE ROW LEVEL SECURITY (V1's list, V4's nullif predicate).
-- =============================================================================

ALTER TABLE defect_link ALTER COLUMN provider DROP DEFAULT;

ALTER TABLE defect_link ALTER COLUMN url SET NOT NULL;

COMMENT ON COLUMN defect_link.provider IS
    'Which tracker the key belongs to, stated by whatever created the row. NO DEFAULT since V62: '
    'it was ''JIRA'', which named a product the instance does not talk to and would have made M4 '
    'unable to tell a real Jira row from a defaulted one. The pre-M4 writer states EXTERNAL — a '
    'link a person made.';

COMMENT ON COLUMN defect_link.url IS
    'Where the defect lives. NOT NULL since V62, closing a gap openapi.yaml never had: DefectLink.'
    'url has been a required property since M0 while the column was nullable, which nothing had '
    'violated only because the table had no writer.';

COMMENT ON COLUMN defect_link.status_cache IS
    'The tracker''s status as of the last sync, or NULL for a row that has never been synced — '
    'which is every row until M4, and is a TRUE answer rather than a missing one. Deliberately '
    'writerless: no caller may set it, because one person''s guess recorded as a tracker''s answer '
    'is the claim V62 removed from provider.';

COMMENT ON COLUMN defect_link.synced_at IS
    'When the tracker was last asked about status_cache. Writerless until M4, for that column''s '
    'reason.';

COMMENT ON COLUMN defect_link.case_id IS
    'Unused. Every reader is execution-scoped, so the pre-M4 writer fills execution_id alone rather '
    'than populating a column no query names; it is derivable through test_execution if a '
    'case-scoped read is ever owed.';

CREATE UNIQUE INDEX defect_link_execution_key_idx
    ON defect_link (company_id, execution_id, provider, external_key)
    WHERE execution_id IS NOT NULL;

COMMENT ON INDEX defect_link_execution_key_idx IS
    'Serves the execution-scoped read AND enforces the create''s replay: (company_id, execution_id) '
    'is the prefix findDefectLinks looks up, and the full four columns are what the service checks '
    'before inserting — so a race between two retries of one link fails on the constraint rather '
    'than writing a twin.';
