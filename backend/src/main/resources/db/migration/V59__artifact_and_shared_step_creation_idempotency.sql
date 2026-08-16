-- =============================================================================
-- V59 — a retried artifact or shared-step creation must not create a second one
--
-- B4 of docs/audits/2026-08-15-flow-review-and-improvements.md. The FIFTH
-- instance of a shape V11 (test_case) introduced and V14 (test_plan, test_run),
-- V17 (work_item) and V42 (suite, doc_page) repeated. Eight columns across eight
-- tables now.
--
-- THE GENERALISATION IS STILL NOT TAKEN, and V42's header carries the argument
-- unchanged: a general `idempotent_request` table would have to store the
-- RENDERED RESPONSE, because a general mechanism cannot re-render one it does
-- not understand, and a stored response is a second copy of client content that
-- goes stale the moment the row it describes is edited. The column form
-- re-renders from the live row through the same assembler the GET uses. That is
-- a function of what the alternative must store, not of how many tables carry
-- the column — so the count rising to eight changes nothing about it.
--
-- WHY THE ARTIFACT REGISTER IS THE WORST OF THE EIGHT
--
-- Not because a duplicate row is expensive — it is a register entry, and DELETE
-- has shipped since §126 (and a caller since §229). It is because ONE of the two
-- creation paths writes BYTES, and those bytes are not covered by the row's
-- delete in the way a reader would assume:
--
--   * POST …/artifacts registers a reference. A retry makes a twin, which is
--     visible in the register and removable.
--   * POST …/artifacts/files registers a FILE: an `attachment` row, a blob in
--     the store, and the artifact pointing at it. A retried upload therefore
--     duplicates all three — and the blob is the one nobody sees. The register
--     shows two rows; the disk holds two copies of a signed PDF.
--
-- The multipart path is also the one most likely to be retried: it is the
-- longest request in the product, so it is the one that hits a proxy timeout
-- while the server is still writing.
--
-- WHY SHARED STEPS ARE HERE TOO, AND WHY THE ARGUMENT FOR THEM IS WEAKER
--
-- The audit's own words: "shared steps too, or document why a visible twin is
-- acceptable". The twin IS visible — the library is a list, and a duplicate
-- entry sits next to its original with the same title. That is precisely
-- SuiteWriteService's §29 argument, which V42 superseded, and it fails here for
-- the same reason it failed there: it reasons entirely from a person looking at
-- a screen, and the caller this exists for has none. A shared step is also a
-- REFERENCE TARGET — `test_step.shared_step_id` — so two copies of one entry
-- split which cases point at which, and retiring the loser is §64's
-- retired-reference problem arriving for no reason at all.
--
-- What is weaker here than for the artifact is the COST, and it is worth saying
-- so rather than implying a symmetry: a duplicate library entry breaks nothing,
-- burns no display key (shared_step has none), and is deletable. It is here
-- because it is a create endpoint on a surface an agent drives, and because the
-- alternative was to write down why it is fine — which, having tried to, it is
-- not quite.
--
-- WHAT DOES NOT GET A KEY, WITH REASONS
--
--   * PATCH …/artifacts/{id} (§229) and every other update. An idempotency key
--     answers "did my create land twice"; a PATCH is already idempotent by
--     construction — sending it twice stores the same values — and `If-Match` is
--     what that path needs against a concurrent writer, which it has.
--   * POST …/artifacts/{id}/status. Same: setting a status twice is one state.
--   * The condition library (`condition`). It is the same shape as shared_step
--     and it is NOT included, deliberately: this migration covers the two
--     endpoints the audit named, and adding a third table on the grounds that it
--     rhymes is how a column count reaches eight without anybody deciding. When
--     a caller retries a condition create and makes a twin, that is the evidence
--     for the ninth.
--
-- SCOPE, PARTIALITY AND ORDERING — V11's, V14's, V17's and V42's, unchanged:
--
--   * (company_id, project_id, key): the lookup must name exactly the
--     constraint's columns, or a key reused across two projects passes the
--     lookup and then violates the index — a validated request that 500s.
--   * company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest.
--   * Partial on IS NOT NULL because the header is optional: every row created
--     before this migration carries NULL, NULLs are not comparable so an
--     unpartitioned unique index would permit them anyway, and it would index
--     every unkeyed row for nothing.
--
-- NOT CREATE INDEX CONCURRENTLY, for V17's reason: Flyway runs a migration in
-- one transaction and CONCURRENTLY cannot run inside one. Both tables are small
-- by construction — a project has tens of registered artifacts and tens of
-- library entries — so the brief ACCESS EXCLUSIVE is measured in milliseconds.
--
-- NO NEW TABLE AND NO NEW POLICY. Both tables are already company-owned and
-- already under FORCE ROW LEVEL SECURITY.
--
-- NOT a foreign key, not validated, never read as data. The application caps its
-- length so a hostile 10 MB header is refused at the edge rather than indexed.
-- =============================================================================

ALTER TABLE artifact    ADD COLUMN created_idempotency_key TEXT;
ALTER TABLE shared_step ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN artifact.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS artifact instead of '
    'registering a second one. It matters most on the multipart path: a retried upload duplicates '
    'the attachment row AND the blob, and the blob is the copy nobody sees. Opaque, '
    'client-supplied, write-once, never read as data. NULL for every artifact registered before '
    'V59 and for any caller that sends no key.';

COMMENT ON COLUMN shared_step.created_idempotency_key IS
    'As artifact.created_idempotency_key. A shared step is a reference target (test_step.'
    'shared_step_id), so two copies of one entry split which cases point at which — a visible twin '
    'in the library list is not the whole cost, and the caller this defends has no list in front '
    'of it. NULL for every entry created before V59 and for any caller that sends no key.';

CREATE UNIQUE INDEX artifact_created_idempotency_key_idx
    ON artifact (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX shared_step_created_idempotency_key_idx
    ON shared_step (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX artifact_created_idempotency_key_idx IS
    'Serves the two create endpoints'' replay lookup AND enforces it: the query is keyed on '
    'exactly these three columns, so a race between two retries of one intent fails on the '
    'constraint rather than writing a twin.';

COMMENT ON INDEX shared_step_created_idempotency_key_idx IS
    'As artifact_created_idempotency_key_idx, for the library create.';
