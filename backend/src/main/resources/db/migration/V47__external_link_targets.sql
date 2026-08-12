-- =============================================================================
-- V47 — the mirror learns the other two target kinds, because they finally
--       have a writer
--
-- Source: open question #26, resolved 2026-08-02 — "build it: repo + path +
--         lines + commitSha, and the commit sha is mandatory" (plan §6m) — and
--         handoff §167/§168, which left the six DocLink.external* keys as the
--         only entries in ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION
--         and said in the map itself what it would take to remove them: "an
--         external target needs a writer, a second arm in the mirror statement
--         and a targetKind this SPA branch can reach, and on that day all six
--         entries have to be deleted for the build to pass."
--
-- This migration is the second of those three. `doc_link` has designed
-- EXTERNAL_GIT and EXTERNAL_URL since V1 — ck_doc_link_target enumerates their
-- required columns — and only DOC_BLOCK ever had a writer, so both kinds were
-- a CHECK constraint enforcing a rule about rows that could not exist.
--
-- NOTHING IS ADDED TO doc_link. All six external_* columns and the CHECK that
-- governs them have been there since M0; what changes there is Java (DocLink
-- maps the columns it had deliberately left unmapped). What changes HERE is
-- case_doc_link, the read model V46 built one section ago, which was written
-- for the one kind that existed and says so at three NOT NULLs.
--
-- -----------------------------------------------------------------------------
-- 1. FOUR COLUMNS STOP BEING NOT NULL, AND A CHECK REPLACES THEM
-- -----------------------------------------------------------------------------
--
-- page_id, page_key, page_title and block_id are the block target's coordinates
-- and an external link has none of them: it points at a file in a repository or
-- at a URL, and there is no page it lives on. Dropping the NOT NULLs alone would
-- leave a row free to be half a block link and half an external one, so the
-- constraint moves rather than disappearing — ck_case_doc_link_target is
-- ck_doc_link_target seen in the mirror, one column list per kind, and the
-- mirror is the place a defect in the refresh statement would land.
--
-- The two constraints are deliberately not identical and the difference is the
-- direction each fails in. doc_link's admits a row whose target_kind is a value
-- outside the three (its column CHECK bounds that separately); this one closes
-- the set, because a target_kind arriving here that the refresh did not write is
-- not a new capability, it is a bug in a statement that copies a value it does
-- not interpret.
--
-- -----------------------------------------------------------------------------
-- 2. THE SIX external_* COLUMNS ARE COPIED, NOT DERIVED
-- -----------------------------------------------------------------------------
--
-- V46 §3 argued that page_key, page_title and block_excerpt are `documentation`
-- values living in a `repository` table because the request path may not read
-- where they come from. These six are the same arrangement with one difference
-- worth stating: they cannot go stale. A block excerpt is a copy of text that
-- somebody else may edit at any moment, while an external target is a set of
-- coordinates that is immutable by construction — doc_link has no update path
-- (every column on DocLink is `updatable = false`, and its header says why:
-- "changing what a link points at is two acts"). So the staleness window V46
-- accepts for the block columns does not exist for these; a row is right or the
-- link was deleted.
--
-- -----------------------------------------------------------------------------
-- 3. stale IS FALSE FOR AN EXTERNAL LINK, AND THAT IS AN ANSWER RATHER THAN A
--    DEFAULT
-- -----------------------------------------------------------------------------
--
-- The column means "the linked text changed after this case's content last
-- changed" — a doc_page_version compared against a test_case_version. Neither
-- half exists for an external target: TCMS does not read the repository and has
-- no version of the file to compare against.
--
-- False is therefore the honest value, and it is honest precisely because of
-- #26's decision. An EXTERNAL_GIT link is PINNED TO A COMMIT SHA, so the lines
-- it names cannot change under it — that is the whole reason the sha is
-- mandatory rather than optional metadata. What CAN happen is the pin falling
-- behind the branch, and this column does not mean that: a marker reading "the
-- spec changed, re-read it" on a link whose target is immutable would train
-- readers to ignore the marker on the links where it IS computed.
--
-- WHAT THIS COSTS, STATED: a git link pinned to a commit from a year ago looks
-- exactly as fresh as one pinned this morning. Closing that needs the product to
-- talk to a git host, which is an integration and not a column (§5's IssueTracker
-- is the shape it would take), and it would answer a different question —
-- "is the pin current" — under a different name than this one.
--
-- -----------------------------------------------------------------------------
-- 4. NO NEW INDEX
-- -----------------------------------------------------------------------------
--
-- case_doc_link_case_idx (company_id, case_id) already serves both readers and
-- both are per case: the detail render fetches a case's links and the signal
-- statement aggregates them by case. Nothing reads by repository, by path or by
-- URL, and an index on a column no query names is a write cost with no reader —
-- which is §154's finding one artefact over.
-- =============================================================================

ALTER TABLE case_doc_link
    ALTER COLUMN page_id    DROP NOT NULL,
    ALTER COLUMN page_key   DROP NOT NULL,
    ALTER COLUMN page_title DROP NOT NULL,
    ALTER COLUMN block_id   DROP NOT NULL;

ALTER TABLE case_doc_link
    ADD COLUMN external_repo       TEXT,
    ADD COLUMN external_path       TEXT,
    ADD COLUMN external_line_start INT,
    ADD COLUMN external_line_end   INT,
    ADD COLUMN external_commit_sha TEXT,
    ADD COLUMN external_url        TEXT;

ALTER TABLE case_doc_link
    ADD CONSTRAINT ck_case_doc_link_target CHECK (
        (target_kind = 'DOC_BLOCK'
             AND page_id IS NOT NULL AND page_key IS NOT NULL
             AND page_title IS NOT NULL AND block_id IS NOT NULL)
     OR (target_kind = 'EXTERNAL_GIT'
             AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
     OR (target_kind = 'EXTERNAL_URL'
             AND external_url IS NOT NULL)
    );

COMMENT ON CONSTRAINT ck_case_doc_link_target ON case_doc_link IS
    'ck_doc_link_target seen in the mirror — one column list per target kind, so a refresh that '
    'wrote half a block link and half an external one fails here rather than rendering a bullet '
    'with a page title and no page. Closed over the three kinds, unlike the source constraint: a '
    'fourth value arriving in this table is a defect in a statement that copies what it does not '
    'interpret, never a new capability.';

COMMENT ON COLUMN case_doc_link.target_kind IS
    'DOC_BLOCK, EXTERNAL_GIT or EXTERNAL_URL. ~~Always DOC_BLOCK~~ — superseded by V47: the two '
    'external kinds got their first writer (open question #26, plan §6m), so this column now does '
    'the job V46 said it was stored for.';

COMMENT ON COLUMN case_doc_link.page_id IS
    'Null for an external target — see ck_case_doc_link_target. The four block coordinates were '
    'NOT NULL until V47, when the mirror stopped being a mirror of one kind.';

COMMENT ON COLUMN case_doc_link.external_commit_sha IS
    'Mandatory for EXTERNAL_GIT and the reason the kind is worth having: an unpinned line range '
    'silently points at different code after the next commit, which is a link that lies rather '
    'than a link that breaks (open question #26, plan §6m).';

COMMENT ON COLUMN case_doc_link.stale IS
    'The linked page''s content changed after this case''s content last changed — newest '
    'doc_page_version against newest test_case_version, never updated_at on either side (see V46''s '
    'header, and ApprovalFacts for the same refusal). PAGE granularity, not block: doc_block '
    'carries no timestamp, so an edit elsewhere on the page raises it too. ALWAYS FALSE FOR AN '
    'EXTERNAL TARGET, which V47 §3 argues is an answer and not a default: the sha pins the lines, '
    'so what could go out of date is the pin, and that is a different question this column must '
    'not be read as answering.';
