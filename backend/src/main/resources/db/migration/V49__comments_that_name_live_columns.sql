-- =============================================================================
-- V49 — two table comments restated, so that every identifier a comment names
--       is one this schema still has
--
-- Source: handoff §190, taking §186's named-and-untaken item ("V1's comment on
--         `suite_condition` still describes the tree §98 deleted") and widening
--         it by the only means that could say whether it was the only one: a
--         computed check. `SchemaCommentTest` reads every comment out of
--         `pg_description` and resolves the identifiers in it against the live
--         catalog. Two comments failed. This migration is both of them.
--
-- NOTHING HERE CHANGES A SINGLE BYTE OF DATA OR ONE COLUMN OF STRUCTURE. Two
-- `COMMENT ON TABLE` statements, which are replace-only: the text below is what
-- `\d+` prints from now on, and V1's is history in the same way V33's rewrite
-- of `suite`'s comment left V1's behind.
--
-- -----------------------------------------------------------------------------
-- 1. `suite_condition` — the resolution order described a walk up a tree that
--    V33 dropped the columns for
-- -----------------------------------------------------------------------------
--
-- V1 said: "Inherited by every case beneath the suite. Resolution order:
-- inherited (walking suite.path upward) then the case own conditions, deduped
-- by condition_id (plan §6f)."
--
-- `suite.path` and `suite.parent_id` were dropped by V33 (§98) — a suite is a
-- flat named collection, not a folder in a tree. So the comment names a column
-- that has not existed for fifteen sections, and the mechanism it describes is
-- one nobody can perform.
--
-- Two things it got wrong beyond the column name, both of which matter more:
--
--   "every case beneath the suite" — a flat suite has nothing beneath it, and
--   membership is now three arms (filed, added, tagged). Inheritance reaches
--   exactly ONE of them, and that is a decision with an argument behind it:
--   `CaseConditions.ancestry` states it (filing says where a case lives and the
--   standing rules of that place apply to it; a tag rule says a collection
--   wants to see it, which is not a claim about how the case is run). A reader
--   who takes "every case in the suite" from this comment and implements it
--   would widen the model by way of a typo.
--
--   The decline was invisible. `case_condition_optout` had no writer at all
--   until §187 and is not mentioned here, which left the one table that can
--   take a row OUT of the resolved list absent from the comment that describes
--   the list.
--
-- -----------------------------------------------------------------------------
-- 2. `role_assignment` — `project.company` is the shorthand, `project.company_id`
--    is the column
-- -----------------------------------------------------------------------------
--
-- Twice, in the effective-permissions sketch. Nothing about the sketch is
-- wrong: a grant is answered against the company the project belongs to, which
-- is what the words say. It is the *identifier* that names nothing — and the
-- reason to spend a migration on a two-character difference is that the check
-- which found it cannot tell a shorthand from a stale claim, and neither can a
-- reader who greps for `project.company` to find out what reads it. An
-- exemption for prose would be an exemption the next stale identifier hides
-- behind (§162: an ignore list naming the wrong mechanism reads as coverage).
--
-- The rest of the comment is transferred verbatim, including its layout — a
-- COMMENT ON replaces the whole string, so restating it is the only way to
-- change two words of it.
-- =============================================================================

COMMENT ON TABLE suite_condition IS
    'Standing conditions attached to a suite. FLAT since V33 (§98): there is no walk and no path, '
    'because there is no parent. Inherited by the cases FILED in the suite (test_case.suite_id) '
    'and by those alone — not by the cases added (suite_case) nor by those a tag rule matches '
    '(suite_tag_rule); see CaseConditions.ancestry for why widening it is a decision rather than a '
    'consequence of flattening. Resolution order per kind (plan §6f): these attachments first, then '
    'the case own case_condition rows, deduped by condition_id with the first occurrence winning. '
    'A case_condition_optout row declines ONE attachment for ONE case, which is why the decline is '
    'keyed on suite_condition.id and not on condition_id.';

COMMENT ON TABLE role_assignment IS
    'COMPANY- and PROJECT-scope grants. SYSTEM-scope grants live in instance_role_assignment — see '
    'the comment there for why they are a separate table rather than a NULL company_id here. '
    'The two tables together are the ONLY source of any capability (plan §6b). Effective permissions:'
    '  0  unless the user has an ACTIVE company_membership for project.company_id  <- gate, not a term'
    '  U  roles granted directly to the user on the project'
    '  U  roles granted to any team the user belongs to, on the project'
    '  U  roles granted to the user or their teams on project.company_id  (COMPANY scope)'
    '  U  roles granted to the user at SYSTEM scope                    <- instance_role_assignment'
    'Cached per (company_id, user_id, project_id) — the company id MUST be in the cache key. '
    'Invalidated on any write to role_assignment, instance_role_assignment, team_membership, '
    'role_permission or company_membership, and on a company switch. No explicit deny in the model.';
