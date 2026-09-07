-- =============================================================================
-- V14 — a proposal names the requirements it verifies (plan 022, design §5)
--
-- THE VERSION IS V14, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-07:
-- V1..V13. CLAUDE.md's "there is exactly one migration" sentence describes the
-- state on 2026-08-29 and is stale by twelve files; the number is read from the
-- directory, never derived from a document (021 D-a). `out-of-order` is unset in
-- application.yml, so a lower-numbered migration arriving after a higher one is
-- NEVER APPLIED, and Testcontainers builds every test database from empty, so no
-- suite here can show it.
--
-- ONE COLUMN, AND NO POLICY. ai_proposal is already company-owned and already
-- carries its FORCE ROW LEVEL SECURITY policy from V1; a policy is per table and
-- not per column, so adding one here would be a second policy on a table that
-- has one, which SchemaConventionTest reports as a duplicate rather than as
-- belt and braces.
-- =============================================================================

ALTER TABLE ai_proposal ADD COLUMN verifies JSONB;

COMMENT ON COLUMN ai_proposal.verifies IS
    'The doc_block statements this proposed case claims to verify, as filed: an array of '
    '{pageId, blockId, relationship}. Its own column and NOT a key inside payload, for the reason '
    'payload''s own comment gives about itself — payload is the body POST /cases takes, "because '
    'accepting hands exactly this to the create path", and the create path accepts no such key, so '
    'a key smuggled in there would be dropped by the first validator that reads it strictly. NOT '
    'inside grounding either: grounding is displayed and never interpreted, and reading a link '
    'target out of a free-form object is a second interpretation nobody can validate. grounding '
    'sits beside payload as its own JSON column for that same reason and this is the third. '
    'STORED UNVALIDATED and NOT A FOREIGN KEY, exactly as suite_id is and for its argument: '
    'whether a block still exists is a question about the project at ACCEPT time, which may be a '
    'week later, and a FK would answer it here as a 500 rather than there as a field error. '
    'Accept resolves every target against doc_block joined to doc_page and answers 400 on '
    'verifies[n].blockId for a miss; the surviving targets then become ordinary doc_link rows, '
    'written by the documentation module on the repository.proposal_accepted outbox event, so a '
    'link an agent proposed is indistinguishable from one a person made — which is what the '
    'accepted test_case already is. A FK is refused twice over besides: ArchUnit forbids a '
    'reference from repository into documentation.';
