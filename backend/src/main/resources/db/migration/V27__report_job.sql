-- V27 — the JOB RECORD. The one migration in the M2.5 reporting plan.
--
-- §6e specifies generation as a job: "POST /reports/generate returns 202 with a job id; the client
-- polls." Everything downstream of that sentence exists in V1 already — report_definition holds
-- what to compute, report_artifact holds what came out — and the thing in the middle, the record of
-- a request in flight, has never had a table. Without it the 202 has nothing to name, ReportJob's
-- contract field `downloadUrl` has nothing to fill it, and there is no row for a poller to read.
--
-- -----------------------------------------------------------------------------
-- WHY A TABLE AND NOT AN IN-JVM DISPATCH — slice-plan decision B
-- -----------------------------------------------------------------------------
--
-- @Async plus @TransactionalEventListener(AFTER_COMMIT) is the shape CompanyScopedEvent's javadoc
-- illustrates, it is less code, and it has lower latency. It also loses every in-flight job on a
-- restart, with no symptom but a spinner that never stops — and the whole reason §6e specifies a
-- job id rather than a long request is that the work OUTLIVES the request. Making the work die with
-- the process gives back exactly what the 202 bought. So the request writes a PENDING row inside
-- its own transaction and returns; a @Scheduled worker claims it later, in a different thread, in a
-- different transaction, possibly in a different process.
--
-- -----------------------------------------------------------------------------
-- HOW THE WORKER SEES A ROW AT ALL — the part that is silent when wrong
-- -----------------------------------------------------------------------------
--
-- This table is company-owned and therefore RLS-protected, so a worker holding no company context
-- sees ZERO rows: `SELECT … WHERE status = 'PENDING'` with no context returns nothing, and the tick
-- logs success having done nothing. Nothing fails, nothing is logged as failing, and the reports
-- simply never appear.
--
-- The worker therefore enumerates companies from `company` — which is deliberately NOT under RLS,
-- precisely so it can be read before any context exists — and opens each context in turn, one
-- transaction per company. CaseHealthScheduler's loop, and ExecutionFactScheduler's (§86) after it.
-- The company loop is NOT an optimisation here; it is the only way this table is visible.
--
-- The claim index below is the shape that loop asks for: company first (ERD §0.1 point 3, and
-- SchemaConventionTest fails the build on an index that mentions company_id without leading with
-- it), then status, then arrival order.
--
-- -----------------------------------------------------------------------------
-- RETENTION: NOTHING IS EVER DELETED IN v1 — an accepted cost, decided rather than overlooked
-- -----------------------------------------------------------------------------
--
-- This is the slice that starts writing bytes to a disk nothing sweeps. There is no `expires_at`
-- here and none on report_artifact: v1 keeps every job row and every artifact for ever, and the
-- growth is unbounded in the number of reports a company generates. That is a decision on the
-- record (user, 2026-08-04), not an omission — §6n's evidence-bundle retention is the section that
-- will eventually govern it, and the reaper is one scheduled arm on the loop the worker already
-- runs. Named here so the day the disk fills, this file says who chose it and what to add.
--
-- -----------------------------------------------------------------------------
-- WHY THE STATUS SET IS FOUR VALUES AND NOT FIVE
-- -----------------------------------------------------------------------------
--
-- PENDING → RUNNING → COMPLETED | FAILED. No CANCELLED: nothing can cancel a job in v1 (the worker
-- is single-threaded and a job that has started finishes within one tick), and a state no code can
-- reach renders identically to one the product cannot produce yet. The mock currently answers
-- 'QUEUED', which is not one of these and never was — the contract's enum is what it violates, and
-- R3 phase 5 corrects it.

CREATE TABLE report_job (
    id                UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES company(id),
    project_id        UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    definition_id     UUID REFERENCES report_definition(id) ON DELETE SET NULL,
    type              TEXT NOT NULL,
    format            TEXT NOT NULL CHECK (format IN ('PDF','MD','HTML','CSV','XLSX','JSON')),
    parameters        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status            TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    artifact_id       UUID REFERENCES report_artifact(id),
    model_artifact_id UUID REFERENCES report_artifact(id),
    error             TEXT,
    attempts          INT NOT NULL DEFAULT 0,
    requested_by      UUID REFERENCES app_user(id),
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ
);

COMMENT ON TABLE report_job IS
    'A report generation request in flight (V27, plan §6e). The row the 202 names and the poller '
    'reads. It survives a restart, which is the whole reason it is a table and not an @Async '
    'dispatch. Nothing deletes rows here in v1 — see this migration''s retention note.';
COMMENT ON COLUMN report_job.definition_id IS
    'The saved definition this run came from, or NULL for an ad-hoc request. ON DELETE SET NULL, '
    'not CASCADE: deleting a saved report must not delete the history of what it produced.';
COMMENT ON COLUMN report_job.format IS
    'The format ASKED FOR. The set is report_artifact''s, deliberately — this constraint is about '
    'what the schema may legitimately hold, not about what the server currently renders. PDF is '
    'refused with a 400 at REQUEST time (there is no PDF renderer installed), in the service, where '
    'the message can say so; a client cannot tell an unsupported format from a broken server by '
    'watching a job go FAILED an hour later.';
COMMENT ON COLUMN report_job.parameters IS
    'Includes the AGGREGATION POLICY, for report_definition.parameters'' reason (plan §6e): two '
    'teams reading "94% pass" otherwise mean entirely different things.';
COMMENT ON COLUMN report_job.artifact_id IS
    'The artifact in the REQUESTED format. Set last, after the bytes are in the store — see '
    'ReportJobRunner for why this ordering is the inverse of EvidenceService''s.';
COMMENT ON COLUMN report_job.model_artifact_id IS
    'The same report as a JSON artifact, written for EVERY job (decision F). §6e: "the on-screen '
    'report view consumes the same model via JSON, so what you see is what exports." A synchronous '
    'preview endpoint would be a second code path producing a second answer.';
COMMENT ON COLUMN report_job.attempts IS
    'Incremented on claim. There is no retry policy in v1 — a FAILED job is re-requested by the '
    'user, which is honest and costs one click. The column is here so a retry loop, when it '
    'arrives, does not need a migration to be observable.';
COMMENT ON COLUMN report_job.requested_by IS
    'Who asked. Nullable like every other actor column since §66: an offboarded account does not '
    'un-request a report.';

-- The claim query, and the only reason this index exists: "the oldest PENDING job for the company
-- whose context is currently open". company_id leads (ERD §0.1 point 3).
CREATE INDEX ix_report_job_claim
    ON report_job (company_id, status, requested_at);

-- -----------------------------------------------------------------------------
-- RLS — the 59th policy.
--
-- nullif(...) rather than V1's bare current_setting, for V4's reason: a GUC set once on a pooled
-- connection resets to '' rather than NULL for the rest of that session, and ''::uuid RAISES
-- instead of matching nothing. A table created after V4 must be born with the fixed predicate, or
-- it reintroduces the reset-GUC 500 on exactly this table and no other.
--
-- SchemaConventionTest counts the policies, so 58 → 59 with this file. Moving the count is the
-- point: it is what keeps the predicate check non-vacuous, and a table added without its policy
-- would otherwise pass by never being looked at.
-- -----------------------------------------------------------------------------

ALTER TABLE report_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_job FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON report_job USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- report_artifact.format GAINS 'JSON' — slice-plan decision F
-- -----------------------------------------------------------------------------
--
-- V1 wrote ('PDF','MD','HTML','CSV','XLSX'): the five things a person downloads. The JSON model is
-- not one of those — it is what the on-screen view reads, so that the screen and the export are
-- provably one model rendered twice rather than two code paths that drift. Storing it also makes
-- §6e's determinism requirement free.
--
-- DROP + ADD rather than an added constraint, so there is exactly one CHECK on this column and the
-- next reader does not have to intersect two. The table has zero rows today, on every instance,
-- which is what makes this trivial — and this is the last moment it is.

ALTER TABLE report_artifact DROP CONSTRAINT report_artifact_format_check;

ALTER TABLE report_artifact ADD CONSTRAINT report_artifact_format_check
    CHECK (format IN ('PDF','MD','HTML','CSV','XLSX','JSON'));

COMMENT ON COLUMN report_artifact.format IS
    'What the bytes in storage_key are. JSON (V27) is the serialized ReportModel and is written for '
    'every job alongside the requested format — it is what the on-screen view reads, not a debug '
    'convenience.';
