-- =============================================================================
-- V75 — EXTERNAL_ISSUE: the fourth doc_link target kind, its mirror, and the
--        predicate A1 requires
--
-- Plan 004, *Storage* → V74 in the plan's numbering, V75 here (006's batching;
-- see V73's header). FIVE OBJECTS ACROSS THREE CODE SITES, and only the first two
-- are DDL — DocumentationFacts' refresh and SpecLinkage's predicate are Java and
-- land in the same commit (D11).
--
-- -----------------------------------------------------------------------------
-- 1. doc_link — three columns, an extended kind CHECK, a fourth target arm
-- -----------------------------------------------------------------------------
--
-- external_issue_id is THE IDENTITY (D17). Jira's numeric issue id, ADO's
-- work-item id. Immutable across a project move on both, where the KEY is exactly
-- what moves.
--
-- external_host is the tracker site that id belongs to. AN ID IS ONLY UNIQUE
-- WITHIN A HOST, so identity is (provider, host, id) and never id alone — two
-- bindings to different sites holding the same numeric id are two different
-- issues.
--
-- external_provider names which adapter reads the row. Free text, matching
-- defect_link.provider's reasoning (D9): a fourth tracker must not be a
-- migration.
--
-- THREE COLUMNS THAT ARE NOT HERE, AND THEY ARE ABSENT FOR TWO DIFFERENT REASONS.
-- A later reader adding any of them will think they are adding a cache:
--
--   * external_issue_key — ABSENT BECAUSE IT MOVES. A key is the tracker's
--     display name for an issue and a project move changes it. The consequence
--     that follows, and it is a rule rather than an observation: NOTHING DERIVES
--     A DISPLAY LABEL FROM external_url. Parsing PAY-991 out of a stored URL
--     yields a string that looks current, is unverifiable, and goes silently
--     wrong after exactly the event id-only was chosen to survive — and it needs
--     a per-provider regex that fails on any URL shape nobody anticipated.
--
--   * a title column and a status column — ABSENT BECAUSE THEY WERE FETCHED WITH
--     ONE PERSON'S CREDENTIAL (D5/D15). Storing either on a shared row serves
--     user A's view of an issue to user B, who may not be able to see it at all.
--     This is the same refusal as defect_link.status_cache (V74) and it is a
--     security property rather than a tidiness preference.
--
-- AND THERE IS NO binding_id, which is D18. A foreign key from `documentation`'s
-- table into `integration`'s would be a cross-module edge the architecture
-- forbids, AND it would make a binding's deletion cascade into somebody's
-- traceability record. external_host is what replaces it: the reader resolves a
-- binding by matching (provider, host) against the project's current bindings,
-- finds none after a deletion, and renders the degraded chip. THE HOST IS STORED,
-- NEVER PARSED OUT OF external_url — for the same reason the label is not.
--
-- -----------------------------------------------------------------------------
-- 2. case_doc_link — the mirror, and its CHECK stays closed
-- -----------------------------------------------------------------------------
--
-- V47's constraint is closed over the kinds it admits, and its comment says why:
-- "a fourth value arriving in this table is a defect in a statement that copies
-- what it does not interpret, never a new capability." HERE IT IS A NEW
-- CAPABILITY, so the comment is amended in place rather than left to read as a
-- contradiction the next reader has to resolve.
--
-- DocumentationFacts' second SELECT is `WHERE l.target_kind <> 'DOC_BLOCK'`, so
-- it copies EXTERNAL_ISSUE rows the moment they exist with no code change — and
-- would then violate the mirror's CHECK until this migration lands. That is why
-- the two halves are one migration and not two.
--
-- -----------------------------------------------------------------------------
-- 3. stale IS false FOR EXTERNAL_ISSUE, AND NOT FOR V47 §3's REASON
-- -----------------------------------------------------------------------------
--
-- V47 argues false is honest for EXTERNAL_GIT because the link is PINNED TO A
-- COMMIT SHA, so the lines cannot change under it. An issue has no pin. Here
-- false is honest for a different reason: under D13 the row carries no derived
-- meaning at all, so there is nothing for a freshness marker to be about.
--
-- The column comment says WHICH reason applies to WHICH kind, because one
-- sentence covering both would be wrong about one of them.
--
-- -----------------------------------------------------------------------------
-- 4. WHY THIS STAYS IN doc_link, RESTATED UNDER A1
-- -----------------------------------------------------------------------------
--
-- An EXTERNAL_ISSUE row is the only doc_link kind inert to every derived fact: it
-- does not take a case out of UNLINKED (SpecLinkage's predicate, this migration's
-- Java half) and it cannot go stale. It is an ANNOTATION, not a traceability
-- edge, which weakens revision 1's "one traceability edge" argument considerably.
--
-- It still wins, and the reason has changed rather than survived: the case
-- detail's Links panel reads the MIRROR — `repository` may not import
-- `documentation`, which is why V46 built the mirror at all — so a separate table
-- would need its own mirror, its own refresh, its own RLS policy, its own
-- delete-cascade and its own arm in the detail render, to gain one WHERE clause.
-- The decision is unchanged and its reason is not, and the next reader should
-- know that.
--
-- NO NEW TABLE, NO NEW POLICY: both tables are already company-owned and already
-- under FORCE ROW LEVEL SECURITY. The policy count does not move here.
-- =============================================================================

ALTER TABLE doc_link
    ADD COLUMN external_provider TEXT,
    ADD COLUMN external_issue_id TEXT,
    ADD COLUMN external_host     TEXT;

ALTER TABLE doc_link DROP CONSTRAINT doc_link_target_kind_check;

ALTER TABLE doc_link ADD CONSTRAINT doc_link_target_kind_check
    CHECK (target_kind IN ('DOC_BLOCK', 'EXTERNAL_GIT', 'EXTERNAL_URL', 'EXTERNAL_ISSUE'));

ALTER TABLE doc_link DROP CONSTRAINT ck_doc_link_target;

ALTER TABLE doc_link ADD CONSTRAINT ck_doc_link_target CHECK (
    (target_kind = 'DOC_BLOCK'      AND target_block_id IS NOT NULL)
 OR (target_kind = 'EXTERNAL_GIT'   AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
 OR (target_kind = 'EXTERNAL_URL'   AND external_url IS NOT NULL)
 OR (target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL
                                    AND external_host IS NOT NULL
                                    AND external_url IS NOT NULL)
);

COMMENT ON CONSTRAINT ck_doc_link_target ON doc_link IS
    'EXTERNAL_GIT requires a commit SHA — a line number without one is meaningless six months later '
    '(plan §6m). EXTERNAL_ISSUE requires an id, a host AND a url (V75, 004 D17): the id is the '
    'identity, the host is what makes the id unique, and the url is the href — which is required '
    'because a Jira browse URL is key-addressable and cannot be built from an id alone.';

COMMENT ON COLUMN doc_link.external_issue_id IS
    'The tracker''s OWN STABLE ID — Jira''s numeric issue id, ADO''s work-item id (004 D17). '
    'Immutable across a project move on both providers, which is the whole reason it and not the '
    'key is what is stored. THERE IS NO external_issue_key COLUMN and there must not be: a key '
    'moves. The consequence is a rule — NO DISPLAY LABEL IS EVER DERIVED FROM external_url. Parsing '
    'PAY-991 out of a stored URL gives a string that looks current, cannot be known to be stale, '
    'and lies after exactly the event id-only was chosen to survive.';

COMMENT ON COLUMN doc_link.external_host IS
    'The tracker site this id belongs to. STORED, NEVER PARSED OUT OF external_url — the same rule '
    'as the label. An id is only unique within a host, so identity is (provider, host, id): two '
    'bindings to different sites holding the same numeric id are two different issues. This column '
    'is also what replaces a binding_id (D18): a foreign key from this table into `integration`''s '
    'would be a forbidden cross-module edge AND would cascade a binding''s deletion into somebody''s '
    'traceability record. The reader matches (provider, host) against the project''s current '
    'bindings, finds none after a deletion, and renders a degraded chip.';

COMMENT ON COLUMN doc_link.external_provider IS
    'Which adapter reads this row. Free text with no CHECK, matching defect_link.provider (D9/D20): '
    'a fourth tracker is an adapter and not a migration. Compare tracker_binding.provider, which IS '
    'checked because it SELECTS an adapter that must exist.';

COMMENT ON COLUMN doc_link.target_kind IS
    'DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL or EXTERNAL_ISSUE (V75). NO TITLE COLUMN AND NO STATUS '
    'COLUMN accompanies the issue kind, and the absence is different in kind from the missing key: '
    'the key is absent because it MOVES, the title and status because they were FETCHED WITH ONE '
    'PERSON''S CREDENTIAL (004 D5/D15) and a shared row would serve them to somebody who cannot see '
    'the issue. Both are fetched live, per viewer, and never stored.';

-- -----------------------------------------------------------------------------
-- The mirror
-- -----------------------------------------------------------------------------

ALTER TABLE case_doc_link
    ADD COLUMN external_provider TEXT,
    ADD COLUMN external_issue_id TEXT,
    ADD COLUMN external_host     TEXT;

ALTER TABLE case_doc_link DROP CONSTRAINT ck_case_doc_link_target;

ALTER TABLE case_doc_link ADD CONSTRAINT ck_case_doc_link_target CHECK (
    (target_kind = 'DOC_BLOCK'
         AND page_id IS NOT NULL AND page_key IS NOT NULL
         AND page_title IS NOT NULL AND block_id IS NOT NULL)
 OR (target_kind = 'EXTERNAL_GIT'
         AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
 OR (target_kind = 'EXTERNAL_URL'
         AND external_url IS NOT NULL)
 OR (target_kind = 'EXTERNAL_ISSUE'
         AND external_issue_id IS NOT NULL AND external_host IS NOT NULL
         AND external_url IS NOT NULL)
);

COMMENT ON CONSTRAINT ck_case_doc_link_target ON case_doc_link IS
    'ck_doc_link_target seen in the mirror — one column list per target kind, so a refresh that '
    'wrote half a block link and half an external one fails here rather than rendering a bullet '
    'with a page title and no page. STILL CLOSED over the kinds it admits, and V47''s reason for '
    'that is AMENDED rather than left standing: V47 said "a fourth value arriving in this table is '
    'a defect in a statement that copies what it does not interpret, never a new capability", which '
    'was true of a fourth value nobody had designed. EXTERNAL_ISSUE (V75) IS a new capability, so '
    'the arm was added here deliberately and in the same migration as the source constraint. The '
    'rule the sentence was protecting survives: a FIFTH value arriving without an arm is still a '
    'defect in the refresh, not a capability.';

COMMENT ON COLUMN case_doc_link.target_kind IS
    'DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL or EXTERNAL_ISSUE. ~~Always DOC_BLOCK~~ — superseded by '
    'V47 (the two external kinds got their first writer) and extended by V75 (the issue kind). The '
    'value is copied from doc_link and interpreted by the case detail''s Links panel, which is the '
    'only reader.';

COMMENT ON COLUMN case_doc_link.external_issue_id IS
    'Copied from doc_link.external_issue_id — the tracker''s stable id, which is the ONLY thing '
    'this row can render for a viewer who cannot preview the issue. The chip says "<Provider> issue '
    '<id>" and never a key parsed from external_url (004 D17).';

COMMENT ON COLUMN case_doc_link.external_host IS
    'Copied from doc_link.external_host. What the Links panel matches against the project''s '
    'bindings to decide whether a preview is even possible — there is no binding id on either side '
    '(D18), so a deleted binding degrades the chip and cascades nothing.';

COMMENT ON COLUMN case_doc_link.stale IS
    'The linked page''s content changed after this case''s content last changed — newest '
    'doc_page_version against newest test_case_version, never updated_at on either side (see V46''s '
    'header, and ApprovalFacts for the same refusal). PAGE granularity, not block: doc_block '
    'carries no timestamp, so an edit elsewhere on the page raises it too. ALWAYS FALSE FOR AN '
    'EXTERNAL TARGET — and the reason DIFFERS BY KIND, which is why this sentence is two and not '
    'one. For EXTERNAL_GIT (V47 §3) the sha PINS the lines, so what could go out of date is the '
    'pin, and that is a different question this column must not be read as answering. For '
    'EXTERNAL_ISSUE (V75, 004 D13) there is no pin at all: the row carries NO derived meaning, so '
    'there is nothing for a freshness marker to be about. One sentence covering both would be wrong '
    'about one of them.';
