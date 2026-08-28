-- =============================================================================
-- V74 — defect_link.provider gets a truthful writer
--
-- Plan 004, *Storage* → V73 in the plan's numbering, V74 here (006's batching;
-- see V73's header).
--
-- COMMENT-ONLY, AND HALF THE SIZE REVISIONS 1–3 PLANNED. There is no CHECK on
-- provider and there will not be (D9): DefectLink.java:50-54 already states it as
-- a decision — "the column is free text because M4's adapters name their own
-- trackers, and constraining it to a Java enum now would make adding an adapter a
-- schema change." tracker_binding.provider IS checked, and V73's header says why
-- the two differ.
--
-- WHAT CHANGES: provider's comment stops saying "the pre-M4 writer states
-- EXTERNAL" as though that were the whole story. There are now two writers and
-- the column tells them apart:
--
--   * EXTERNAL — a link a person typed. DefectLinkInput with no bindingId, which
--     is every row written before this migration and every row a client writes
--     without naming a binding. Unverified by construction, and the honest thing
--     for it to say.
--
--   * A tracker's name (JIRA_CLOUD, JIRA_DC, AZURE_DEVOPS) — the row was written
--     with a bindingId, so the server RESOLVED that binding and states the
--     provider it actually called, through the caller's own credential. Not a
--     caller-supplied claim: DefectLinkInput carries a binding id and never a
--     provider string, so there is no way for a client to assert a tracker it did
--     not go through.
--
-- -----------------------------------------------------------------------------
-- status_cache AND synced_at KEEP THEIR V62 COMMENTS, AND THIS HEADER SAYS SO
-- -----------------------------------------------------------------------------
--
-- Revisions 1–3 of plan 004 all framed this migration as the one where those two
-- columns "start telling the truth". THEY DO NOT. D14, taken over two independent
-- arguments:
--
--   1. There is no poller. D8 deleted the sync-back phase outright — no
--      TrackerStatusScheduler, no company sweep, no rate-limit budget — so
--      nothing exists that could write either column.
--
--   2. Even if something did, a status fetched with ONE PERSON'S credential
--      written into a column three screens render to EVERYBODY is precisely the
--      leak D15 exists to prevent. Under per-user credentials a shared cache of
--      tracker content is a cross-user disclosure with a settings screen in front
--      of it.
--
-- V62's position is therefore not amended here; it is CONFIRMED by a second,
-- independent argument, and recording that is the point of this paragraph. A
-- later reader who finds those columns still writerless should read this before
-- concluding it was an oversight twice.
--
-- No DDL, no index, no policy. defect_link is already company-owned and already
-- under FORCE ROW LEVEL SECURITY.
-- =============================================================================

COMMENT ON COLUMN defect_link.provider IS
    'Which tracker the key belongs to, stated by whatever created the row. NO DEFAULT since V62: it '
    'was ''JIRA'', which named a product the instance does not talk to. TWO WRITERS since V74 and '
    'the column tells them apart — EXTERNAL means a link a person typed, unverified by '
    'construction; a tracker''s own name (JIRA_CLOUD, JIRA_DC, AZURE_DEVOPS) means the request '
    'named a tracker_binding and the server states the provider it ACTUALLY CALLED through the '
    'caller''s credential. Free text, with no CHECK, deliberately (004 D9): a fourth tracker is an '
    'adapter and not a migration. A client may name a binding and may never name a provider.';

COMMENT ON COLUMN defect_link.status_cache IS
    'The tracker''s status as of the last sync, or NULL for a row that has never been synced — '
    'which is EVERY row, still, and is a TRUE answer rather than a missing one. Deliberately '
    'writerless, now for TWO reasons (V62 + V74 / 004 D14): there is no poller, because plan 004 '
    'deleted status sync-back outright; and under per-user credentials a status fetched with one '
    'person''s token and written here would be served to people who cannot see the issue, which is '
    'the disclosure D15 exists to prevent. Confirmed by V74, not amended by it.';

COMMENT ON COLUMN defect_link.synced_at IS
    'When the tracker was last asked about status_cache. Writerless, for that column''s two '
    'reasons. ~~"until M4"~~ — V74 removes the deadline rather than meeting it: there is nothing '
    'planned that would write either column.';
