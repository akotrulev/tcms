-- =============================================================================
-- V60 — a rejected report leaves a row, and that row does not consume the key
--
-- B6 of docs/audits/2026-08-15-flow-review-and-improvements.md.
--
-- WHAT THE PRODUCT DID, AND WHY IT WAS A REASONABLE THING TO DO
--
-- A report this server cannot parse left NO ROW AT ALL. That was deliberate and
-- the reason is in `IngestionService`: `ingestion_job.idempotency_key` is unique
-- per company, CI generates the key once per build, and a FAILED row carrying it
-- would mean the corrected retry — same build, same key — replayed the failure
-- instead of ingesting the fixed report. Refusing to write was the only way to
-- keep the retry usable.
--
-- WHAT IT COST, WHICH IS THE HALF NOBODY WROTE DOWN
--
-- The 400 body is the entire record of a rejected report. It goes to a CI job's
-- stdout, which is rotated; nobody in the product can see that a build tried to
-- post results and was refused. `GET …/ingestions` — the history a QA lead reads
-- to answer "did last night's run report" — shows the successes and is silent
-- about every rejection, so a pipeline posting malformed XML for a week looks
-- exactly like a pipeline nobody wired up.
--
-- THE FIX IS THE CONSTRAINT, NOT THE ROW
--
-- Both halves are available at once as soon as the uniqueness is narrowed to the
-- rows it is actually about: a key is consumed by a COMPLETED ingestion and by
-- nothing else. So a failure is recorded, carries its key, and the retry under
-- that same key finds no completed job and proceeds.
--
--   * The old constraint was a table constraint (UNIQUE (company_id,
--     idempotency_key)), which cannot be partial. It is dropped and replaced by
--     a partial unique INDEX — same columns, same guarantee for the rows that
--     matter, and Postgres serves the replay lookup from it either way.
--   * (company_id, idempotency_key): company-scoped, exactly as before. It is
--     deliberately NOT project-scoped, and `IngestionJobRepository`'s header says
--     why: the lookup must be the constraint read back, or the same key posted to
--     a second project finds nothing, is treated as new, and fails on the index
--     as a 500 in the middle of a build.
--   * company_id LEADS, per ERD §0.1 point 3.
--
-- WHAT THIS DOES NOT DO
--
-- It does not make two FAILED rows with one key impossible, and that is
-- intentional: a build that posts a malformed report three times has failed three
-- times, and collapsing those into one row would hide the repetition that is the
-- most useful thing about them. What stays impossible is two COMPLETED
-- ingestions of one key, which is the guarantee CI depends on.
--
-- It also does not touch PENDING or PROCESSING. Those belong to an asynchronous
-- lane that does not exist, and after this migration FAILED is the third of the
-- four that is reachable — so the enum's own contract description stops
-- describing three unreachable values and describes two.
-- =============================================================================

ALTER TABLE ingestion_job DROP CONSTRAINT uq_ingestion_idempotency;

CREATE UNIQUE INDEX ingestion_job_completed_idempotency_key_idx
    ON ingestion_job (company_id, idempotency_key)
    WHERE status = 'COMPLETED';

COMMENT ON INDEX ingestion_job_completed_idempotency_key_idx IS
    'CI retries must not double-post results (plan §5) — and a REJECTED report must not stop the '
    'corrected retry, which is why this is partial on COMPLETED rather than the table constraint '
    'V1 declared. A failed ingestion is recorded, carries its key, and consumes nothing.';
