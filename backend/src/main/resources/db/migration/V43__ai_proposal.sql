-- =============================================================================
-- V43 — ai_proposal (plan §6w.6; MCP WIP plan §1.3 and phase 6)
--
-- The boundary that protects the repository. §6w.6 states it in one sentence —
-- "Nothing generated writes to test_case without a human accepting it" — and
-- until now there was no table for a generated case to land in, so the only
-- shapes available to a generation tool were "write a case" or "do nothing".
--
-- The MCP WIP plan §1.3 settled the split on 2026-08-08: AN EDIT TO EXISTING
-- CONTENT WRITES STRAIGHT THROUGH, A NEWLY GENERATED CASE LANDS HERE. That is
-- why the tool surface spec (§5) calls `tcms_write_case`'s create arm the one
-- blocked arm — it is blocked on this table.
--
-- -----------------------------------------------------------------------------
-- THIS IS NOT test_case_draft, AND THE DIFFERENCE IS NOT A NAMING ONE
-- -----------------------------------------------------------------------------
--
-- §6j's autosave buffer already stores an unsaved case as JSONB, and reusing it
-- was refused in §1.3 in writing. Three properties differ, and each of them is
-- load-bearing:
--
--   * A draft is keyed on the CASE (one row per existing case). A proposal has
--     no case — that is what makes it a proposal.
--   * A draft is CLEARED on save and never versioned. A proposal is DECIDED and
--     the decision is kept, because "this profile's proposals are rejected 80%
--     of the time" is a measurable fact and proposals that vanish when dismissed
--     make it unknowable (§6w.6).
--   * TestCaseDraft's own javadoc forbids a promote endpoint — "a second write
--     path into test_case that skipped CaseEdit's validation". That argument
--     applies here unchanged and is why ACCEPT GOES THROUGH CaseCreateService
--     rather than through an INSERT of its own: same validation, same TC-n
--     allocator, same v1 version row, same audit line.
--
-- -----------------------------------------------------------------------------
-- WHAT IS ABSENT FROM §6w.6's COLUMN LIST, AND WHY EACH ABSENCE IS DELIBERATE
-- -----------------------------------------------------------------------------
--
-- §6w.6 sketches `ai_proposal (company_id, project_id, job_id, …)` with a
-- criterion_id. Two of those are not here:
--
--   * job_id — `ai_generation_job` does not exist. It arrives with TCMS calling
--     a model itself, which the whole plan defers behind BYO-agent generation
--     (§6w slice 5: "before TCMS ever calls a model itself"). A nullable FK to a
--     table with no rows is a column no reader can use.
--   * criterion_id — THERE IS NO CRITERION TABLE AND THERE NEVER WILL BE. §135
--     re-read the documentation module and found ERD resolved question #18 had
--     already decided it: a requirement IS a doc_block. A FK from here to
--     doc_block would also be a cross-module FK from `repository` into
--     `documentation`, which ArchUnit refuses. What a proposal claims to verify
--     therefore travels in `grounding`, which is where the per-block findings
--     already live, and is not a column.
--
-- This is the sixth §6w reversal recorded (§135 found five, including
-- `via = MCP` written against a table with no `via` column).
--
-- -----------------------------------------------------------------------------
-- TWO ENUM VALUES ARE MISSING FROM THEIR CHECKS ON PURPOSE — V39's RULE
-- -----------------------------------------------------------------------------
--
-- V39 (#50) narrowed attachment.owner_type to the five values that have
-- writers, dropping STEP. The same rule is applied here from the start:
--
--   * verdict_source has NO 'TCMS_VERIFIED'. That value means TCMS ran the
--     isolated critic itself under a known model and prompt version, and TCMS
--     calls no model in this milestone. Seeding the value would make the
--     headline number inflatable by whichever caller first guessed the string —
--     which is precisely the failure §6w.1a spends its length preventing. It
--     arrives with the critic, in the migration that gives it a writer.
--   * status has NO 'SUPERSEDED'. It describes a proposal displaced by a later
--     one, and nothing in this slice displaces anything: proposals are created
--     one at a time and decided one at a time. A status no code can set is a
--     filter option that always returns nothing.
--
-- -----------------------------------------------------------------------------
-- verdict_source IS DERIVED BY THE SERVER AND MUST NEVER BE ACCEPTED ON THE WIRE
-- -----------------------------------------------------------------------------
--
-- §6w.1a: an agent's asserted verdict is recorded and shown, and NEVER counted
-- as verified, "because TCMS cannot see that agent's critic, its isolation, or
-- its prompt". So the request carries a verdict claim and this column is
-- computed from whether one was made — NONE when the caller asserted nothing,
-- EXTERNAL when it did. There is no request field for it, and the CHECK above
-- makes the third value unreachable besides. Two independent barriers, because
-- a single one here is the difference between a coverage number that means
-- something and one any MCP client can mint.
--
-- -----------------------------------------------------------------------------
-- RLS — a new company-owned table, so the policy count goes 62 -> 63
-- -----------------------------------------------------------------------------
--
-- The first new policed table since V38. FORCE, because the owner bypasses a
-- plain ENABLE; and nullif(..., '') because V4 established that a GUC reset on a
-- pooled connection reads as the empty string rather than NULL, and ''::uuid
-- raises instead of matching nothing.
-- =============================================================================

CREATE TABLE ai_proposal (
    id                      UUID PRIMARY KEY,
    company_id              UUID NOT NULL REFERENCES company(id),
    project_id              UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,

    -- Where the proposer SAYS the accepted case should be filed. A claim, and
    -- deliberately NOT a foreign key — see the column comment; this is the one
    -- place the "stored unvalidated" decision meets the schema.
    suite_id                UUID,

    -- The proposed case in the CaseUpdateRequest shape — the same payload the
    -- create endpoint takes, so accept can hand it to CaseCreateService without
    -- a translation layer that could disagree with the editor about a field.
    payload                 JSONB NOT NULL,

    -- Denormalised from payload for the review queue. It cannot drift: payload
    -- is written once and never updated (there is no PATCH on a proposal — a
    -- reviewer who wants changes rejects it, or accepts and then edits the case
    -- through the ordinary path, which is the path that versions and audits).
    title                   TEXT NOT NULL CHECK (length(btrim(title)) > 0),

    verdict                 TEXT NOT NULL
                            CHECK (verdict IN
                                ('GROUNDED', 'PARTIAL', 'HALLUCINATED', 'UNVERIFIED')),
    verdict_source          TEXT NOT NULL CHECK (verdict_source IN ('NONE', 'EXTERNAL')),
    grounding               JSONB,

    status                  TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID NOT NULL REFERENCES app_user(id),
    created_token_id        UUID REFERENCES api_token(id) ON DELETE SET NULL,
    created_idempotency_key TEXT,

    decided_at              TIMESTAMPTZ,
    decided_by              UUID REFERENCES app_user(id),
    decision_note           TEXT,
    accepted_case_id        UUID REFERENCES test_case(id) ON DELETE SET NULL,

    -- A decided proposal names its decider and when; a pending one names
    -- neither. Without this the accept path can record a case with nobody
    -- answerable for it, which is the one thing §6w.6 says a proposal must never
    -- produce ("authorship is a person, always").
    CONSTRAINT ai_proposal_decision_complete CHECK (
        (status = 'PENDING'
             AND decided_at IS NULL
             AND decided_by IS NULL
             AND accepted_case_id IS NULL)
        OR (status <> 'PENDING'
             AND decided_at IS NOT NULL
             AND decided_by IS NOT NULL)),

    -- And an ACCEPTED proposal names the case it became. This is the whole
    -- provenance link: there is no origin column on test_case_version, so
    -- "which cases came from a model" is answered from this side, by the row
    -- that knows. A REJECTED one must not name a case, or the queue would show
    -- a rejection that nonetheless created something.
    CONSTRAINT ai_proposal_accepted_names_its_case CHECK (
        (status = 'ACCEPTED' AND accepted_case_id IS NOT NULL)
        OR (status <> 'ACCEPTED' AND accepted_case_id IS NULL))
);

COMMENT ON TABLE ai_proposal IS
    'A generated test case awaiting a human decision (plan §6w.6). NOT test_case_draft: that is a '
    'per-case keystroke buffer that is cleared on save, this is a decision record that outlives its '
    'decision. Nothing here reaches test_case except through CaseCreateService — the accept path '
    'reuses the create path so an accepted proposal is indistinguishable from a case authored any '
    'other way (same validation, same TC-n allocation, same v1 version, same audit row), and the '
    'accepting person is its author. No lock_version: a proposal is authored once and decided once, '
    'so there is no form two screens can contend over.';

COMMENT ON COLUMN ai_proposal.suite_id IS
    'Where the proposer says the case should be filed — a copy of payload->>''suiteId'', so the '
    'queue can group without parsing a document per row. NOT A FOREIGN KEY, and that is the whole '
    'design of this endpoint meeting the schema: a proposal is stored UNVALIDATED, because whether '
    'it is a legal case is a question about the project''s suites at ACCEPT time, which may be a '
    'week later. A FK would answer that question here as well, in the database, as a 500 rather '
    'than a field error — so an agent inventing a suite id would lose its whole batch instead of '
    'landing a proposal a reviewer can look at and reject. Accept resolves it through CaseEdit like '
    'every other create, and a dangling id becomes a field error on the suite picker.';

COMMENT ON COLUMN ai_proposal.payload IS
    'The proposed case in the CaseUpdateRequest field shape (§6m) — the same body POST /cases '
    'takes. Write-once. Held as the request shape rather than as a rendered case so accept can '
    'hand it to the create service unchanged; a second shape here would be a second answer to '
    '"what is a case" and the one that diverges is whichever was edited second.';

COMMENT ON COLUMN ai_proposal.verdict IS
    'What the proposer claims about grounding: GROUNDED | PARTIAL | HALLUCINATED | UNVERIFIED. '
    'UNVERIFIED is a first-class verdict and the honest default, not an error state (§6w.6) — it '
    'is what every proposal carries on rungs 0 and 1, and the review UI renders it as "not '
    'independently checked".';

COMMENT ON COLUMN ai_proposal.verdict_source IS
    'WHO checked, and therefore what the verdict is worth (§6w.1a). NONE = nobody but the reviewer '
    'will have. EXTERNAL = a connecting agent asserted it — recorded, shown, attributed to the '
    'token that said so, and NEVER counted as verified, because TCMS cannot see that agent''s '
    'critic, isolation or prompt. DERIVED BY THE SERVER from whether a verdict was asserted; there '
    'is no request field for it. TCMS_VERIFIED is deliberately absent from the CHECK until the '
    'isolated critic exists to write it (V39''s rule, #50): a value with no writer is a claim any '
    'caller could mint.';

COMMENT ON COLUMN ai_proposal.grounding IS
    'Per-step findings, including rejections and unverified claims (§6w.6) — and where a claim '
    'about WHICH requirement this verifies travels, because there is no criterion table: ERD '
    'resolved question #18 makes a requirement a doc_block, and a FK from repository into '
    'documentation is refused by ArchUnit besides.';

COMMENT ON COLUMN ai_proposal.created_by IS
    'The person answerable for the proposal — the token''s OWNER on an agent-authenticated call, '
    'never the token. §6j''s attribution guarantee: "An agent did it" is never an acceptable line; '
    '"Ana''s agent, holding Ana''s grants, did it" is. created_token_id is the other half.';

COMMENT ON COLUMN ai_proposal.created_token_id IS
    'Which credential filed it, when one did — audit_event.actor_token_id''s shape (§132), and NULL '
    'for a proposal filed in a browser session. ON DELETE SET NULL: revoking a token must not '
    'delete the proposals it filed, or revocation would silently empty the review queue.';

COMMENT ON COLUMN ai_proposal.created_idempotency_key IS
    'As test_case.created_idempotency_key (V11) — the seventh column in that family, and the first '
    'whose caller has no screen at all. A generation agent that times out mid-batch retries the '
    'batch; without this each retry files a second copy of every proposal it had already filed, '
    'and the reviewer pays for it one duplicate at a time.';

COMMENT ON COLUMN ai_proposal.accepted_case_id IS
    'The case this became, set by the accept path inside the same transaction that creates it. '
    'ON DELETE SET NULL is a deliberate hole: deleting the case leaves an ACCEPTED proposal '
    'pointing at nothing, which is refused by ai_proposal_accepted_names_its_case on any '
    'subsequent write to the row and is why the accept path never rewrites a decided row.';

-- -----------------------------------------------------------------------------
-- Indexes — both lead with company_id, which SchemaConventionTest asserts
-- -----------------------------------------------------------------------------
--
-- The first is the review queue's whole predicate: the pending proposals in a
-- project, newest first. Partial, because a healthy queue is short and the
-- decided rows are kept for the rejection-rate signal rather than for browsing.
--
-- The second is V11's replay lookup and its enforcement in one object, with
-- V42's scoping argument verbatim: the query names exactly the constraint's
-- columns, or a key reused across two projects passes the lookup and then
-- violates the index — a validated request that 500s.

CREATE INDEX ai_proposal_pending_idx ON ai_proposal (company_id, project_id, created_at DESC)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX ai_proposal_created_idempotency_key_idx
    ON ai_proposal (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX ai_proposal_pending_idx IS
    'The review queue. Partial on PENDING because that is the only status anybody browses — a '
    'decided proposal is read by id from the case it became, or counted in aggregate.';

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

ALTER TABLE ai_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_proposal FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON ai_proposal USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
