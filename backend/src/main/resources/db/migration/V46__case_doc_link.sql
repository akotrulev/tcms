-- =============================================================================
-- V46 — the documentation links of a case, mirrored into a table `repository`
--       owns, so the case detail's Traceability panel stops being empty for
--       every case in the product
--
-- Source: handoff §165, which named this gap and its twin and argued both at
--         the right obstacle for the first time; §166 closed the twin. See
--         DocumentationFacts.refreshCaseDocLinks for the statement that fills
--         this table.
--
-- `TestCase.relatedDocuments` has been declared in openapi.yaml since M0 and
-- served by nothing. `POST /projects/{id}/docs/{pageId}/blocks/{blockId}/links`
-- has created links since §93, the doc page shows them, and CaseDetailPage
-- reads `data.relatedDocuments ?? []` — so a link somebody made from a spec
-- paragraph to a case renders on the paragraph and NEVER ON THE CASE, under a
-- panel that says "Not linked to any specification. Nothing traces this case
-- back to a requirement."
--
-- That sentence is the same shape §166 removed one section ago: not an empty
-- panel, a confident falsehood, and one that reads as the traceability having
-- been lost rather than never having been asked for.
--
-- The obstacle is the module boundary and nothing else. `repository` may not
-- import `documentation` (plan §3, its own package-info), so the value arrives
-- the way suite_run_summary and now lastResult do — a read model fed by events
-- (§3 lesson 4) — and no request path names doc_link.
--
-- -----------------------------------------------------------------------------
-- 1. WHY THIS IS A TABLE AND lastResult WAS THREE COLUMNS ON case_health
-- -----------------------------------------------------------------------------
--
-- V45's argument was that a last result is ONE FACT about a case and belongs on
-- the row that already answers "has this case ever run" — two derivations of one
-- question rendered inches apart is two answers with nothing on the screen
-- saying which is right.
--
-- That argument does not transfer, and the reason is arithmetic rather than
-- taste: a case has ZERO OR MORE links, and a column holds one value. There is
-- no arrangement in which a list of links is a column on a one-row-per-case
-- table, so the alternative to this table is not a tidier table — it is
-- serialized JSON in a column, which is a list the database cannot index, join
-- or count, and which every later reader has to parse to answer "how many cases
-- link to this block".
--
-- What IS carried over from V45 is the part that mattered: the derived value and
-- the health signals computed from it are written in ONE transaction by ONE
-- service (CaseHealthRecomputeService), so the panel's per-link `stale` marker
-- and the case's OUTDATED_SPEC_CHANGED chip — rendered on the same screen, one
-- panel apart — cannot disagree. Phase 2 of §167 derives that signal FROM THIS
-- TABLE rather than from doc_link a second time, which is the same structural
-- agreement V45 bought by sharing a row.
--
-- -----------------------------------------------------------------------------
-- 2. THE PRIMARY KEY IS THE doc_link ID, AND THE MIRROR IS REPLACED WHOLE
-- -----------------------------------------------------------------------------
--
-- id is doc_link.id, not a new uuid. It is what the contract's `DocLink.id`
-- means, it makes a row in this table nameable in the table it mirrors, and a
-- reader comparing the two by hand is doing a join and not a search.
--
-- There is NO foreign key to doc_link, doc_page or doc_block, and that is the
-- module boundary again rather than an oversight: cross-module FKs are allowed
-- only toward tenancy / identity / project / storage (plan §3), and doc_link is
-- `documentation`. The FKs that ARE here — company and test_case — point at
-- exactly the two the rule permits. doc_link.source_id holds a case id with no
-- FK for the mirror-image reason, which is stated in DocLink's own header.
--
-- The refresh is DELETE-then-INSERT for the whole company, not an upsert. An
-- ON CONFLICT statement can add and update a row and has no way to REMOVE one,
-- and removal is the ordinary case here: unlinking is a hard delete
-- (BlockLinkService: "a withdrawn claim that is still in the table still reads
-- as one to every count over it"), removing a block cascades, and deleting a
-- page hides everything on it. A mirror that could only grow would keep
-- asserting claims somebody explicitly withdrew — which is the same defect the
-- source table refuses, arriving one table later.
--
-- -----------------------------------------------------------------------------
-- 3. THE PAGE'S TITLE AND THE BLOCK'S TEXT ARE COPIED, AND THAT IS THE POINT
-- -----------------------------------------------------------------------------
--
-- page_key, page_title and block_excerpt are `documentation`'s values living in
-- a `repository` table. They are copied because the request path may not read
-- the tables they come from — that is the whole reason this table exists — and
-- they go stale between refreshes exactly as V45's last_status_kind does. The
-- window is the refresh interval: immediately on the documentation.links_changed
-- event, hourly otherwise.
--
-- block_excerpt is truncated to 200 characters WITH AN ELLIPSIS when it is cut.
-- A silently truncated quotation is a claim about what the spec says, and a
-- reader who cannot see that it was cut may conclude the paragraph ends there.
-- NULL when the block has no text at all, so that the SPA's falsy check and the
-- wire agree about what "no excerpt" is.
--
-- -----------------------------------------------------------------------------
-- 4. stale IS STORED, AND IT IS A CONTENT-VERSION COMPARISON ON BOTH SIDES
-- -----------------------------------------------------------------------------
--
-- The contract: "True when the target changed after the case was last touched
-- (plan §6i)". Both halves of that are computed as WHEN CONTENT LAST CHANGED —
-- the newest doc_page_version.created_at against the newest
-- test_case_version.created_at — and not as updated_at on either side.
--
-- ApprovalFacts already refused the timestamp spelling for STALE_APPROVAL and
-- its reason applies here twice over: a save whose content hash is unchanged
-- writes no version and still moves updated_at, and on test_case that column is
-- ALSO moved by assigning an owner, moving a suite and changing a lifecycle
-- status. Under the timestamp spelling, assigning an owner would clear the
-- staleness of every link on the case — a spec change silently forgiven by an
-- act that read nothing.
--
-- It is stored rather than computed at read time so that this column and the
-- OUTDATED_SPEC_CHANGED signal derived from it in phase 2 move together. A
-- read-time comparison would be fresher and would disagree with the chip beside
-- it for up to an hour, which is the failure this whole arrangement is against.
-- The cost is stated plainly: editing a case does not clear its stale markers
-- until the next recompute. STALE_APPROVAL has behaved that way since §157.
--
-- WHAT IT DOES NOT DO: block-level precision. §6i says "when a doc block
-- changes", and this compares PAGE versions, so editing an unrelated paragraph
-- on the same page marks the link stale. That is the finest granularity the
-- schema supports — doc_block carries no timestamp and no content hash, and
-- doc_page_version snapshots a whole page — so closing it means a new column on
-- doc_block, a writer for it in `documentation`, and its own slice. It errs
-- toward telling a reader to re-read a spec they did not need to; the opposite
-- error is a case that silently keeps its coverage claim after the statement it
-- verifies was rewritten.
--
-- -----------------------------------------------------------------------------
-- 5. NO computed_at, AND WHAT AN EMPTY LIST THEREFORE MEANS
-- -----------------------------------------------------------------------------
--
-- A case with no rows here has no links — and, until the first refresh after
-- this migration, so does a case with fifty. §156 is the standing warning about
-- exactly that: a derived all-clear nobody earned reads as a check that ran and
-- passed.
--
-- It is accepted here and it was accepted for lastResult in V45, on the same
-- terms: the window is bounded by the next tick rather than permanent, which is
-- what made §156's five different — those had NO writer at all. The alternative
-- is a nullable list on the wire, and that hands the SPA two spellings of
-- nothing to tell apart, which is the defect §121 phase 4 shipped one field up
-- on this same response.
--
-- The window is NOT bounded for a deployment running with
-- tcms.case-health.enabled=false: there the panel says "not linked to any
-- specification" forever. That is the same consequence that switch already has
-- for every health chip and, since §166, for the Last result column — one
-- switch, one documented meaning, stated at CaseHealthProperties.enabled.
--
-- -----------------------------------------------------------------------------
-- 6. RLS, AND THE ONE INDEX
-- -----------------------------------------------------------------------------
--
-- A new company-owned table, so it gets company_id, FORCE ROW LEVEL SECURITY and
-- V4's nullif() predicate — the sixty-fourth policy, which SchemaConventionTest
-- pins by count so that a table added without one cannot pass by not being
-- looked at.
--
-- One index, (company_id, case_id), company_id leading per the tenancy rule.
-- Both readers are per case: the detail render fetches a case's links, and the
-- signal statement in phase 2 aggregates them by case. Nothing reads by page or
-- by block — the reverse direction is BlockLinks, over doc_link itself, which is
-- the table this one mirrors and not a substitute for it.
-- =============================================================================

CREATE TABLE case_doc_link (
    id            UUID PRIMARY KEY,
    company_id    UUID NOT NULL REFERENCES company(id),
    case_id       UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    target_kind   TEXT NOT NULL,
    relationship  TEXT NOT NULL,
    page_id       UUID NOT NULL,
    page_key      TEXT NOT NULL,
    page_title    TEXT NOT NULL,
    block_id      UUID NOT NULL,
    block_excerpt TEXT,
    stale         BOOLEAN NOT NULL
);

COMMENT ON TABLE case_doc_link IS
    'A read model in `repository`, mirroring doc_link from the case end — one row per link from a '
    'live case to a block of a live page. Written only by DocumentationFacts, replaced whole per '
    'company on every refresh. doc_link is the source of truth; this is what the case detail may '
    'read without naming another module''s tables on a request path (§3 lesson 4).';

COMMENT ON COLUMN case_doc_link.id IS
    'The doc_link row this mirrors, carried rather than minted: it is what the contract''s '
    'DocLink.id means, and it makes the two tables joinable by hand.';

COMMENT ON COLUMN case_doc_link.target_kind IS
    'Always DOC_BLOCK. doc_link''s CHECK allows EXTERNAL_GIT and EXTERNAL_URL and nothing writes '
    'either — DocLink leaves those columns unmapped for the same reason. Stored rather than assumed '
    'so the day an external target gets a writer this column is what tells the two apart.';

COMMENT ON COLUMN case_doc_link.page_key IS
    'DOC-1. Copied from doc_page along with the title and the block excerpt, because the request '
    'path may not read doc_page. Stale between refreshes by construction; the refresh runs on the '
    'documentation.links_changed event and hourly.';

COMMENT ON COLUMN case_doc_link.block_excerpt IS
    'The first 200 characters of the block''s text, with a trailing ellipsis when it was cut — a '
    'quotation trimmed without a mark is a claim about where the spec ends. NULL when the block has '
    'no text, so an absent excerpt has one spelling.';

COMMENT ON COLUMN case_doc_link.stale IS
    'The linked page''s content changed after this case''s content last changed — newest '
    'doc_page_version against newest test_case_version, never updated_at on either side (see this '
    'migration''s header, and ApprovalFacts for the same refusal). PAGE granularity, not block: '
    'doc_block carries no timestamp, so an edit elsewhere on the page raises it too.';

CREATE INDEX case_doc_link_case_idx ON case_doc_link (company_id, case_id);

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

ALTER TABLE case_doc_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_doc_link FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON case_doc_link USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
