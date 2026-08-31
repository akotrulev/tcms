-- =============================================================================
-- V4 — work_item retires (plan 011, module M1 of 009, task T4)
--
-- Two statements and no data movement. V3's section 6 already COPIED every
-- work_item into `issue`, carrying the id forward so derived_item_annotation's
-- (source_type, source_id) rows still point at the same thing; this is the
-- contract half of that expand-contract pair.
--
-- WHY IT IS NOT IN V3, since a reader will ask. com.tcms.work.WorkItem was still
-- an @Entity mapped to work_item and hibernate.ddl-auto is `validate`, so a
-- database without the table refuses to boot the Spring context and every
-- integration test in the suite fails before its first assertion. T2 could not
-- delete the entity either, because BoardService read WorkItemRepository until
-- BoardFacts.assignedIssues existed. So the drop can only land in the commit that
-- deletes the entity, and this is that commit.
--
-- NO ROLLBACK PATH, and that is the same statement V3 already makes: the rows are
-- in `issue` and the tables being dropped are empty of anything `issue` does not
-- already hold. An instance that ran V3 and stopped there has both copies; one
-- that runs this has one. There is nothing here to undo that V3 did not do first.
--
-- work_item_link FIRST — it has no FK to work_item (its target_id is the
-- polymorphic half and carries none), but its work_item_id does, and dropping the
-- parent first would be refused. V3 copied it into issue_link in the same loop.
--
-- SchemaConventionTest's forced-RLS policy count moves 84 -> 82 in this commit.
-- The comment beside that number has said these two were waiting for this
-- migration since V3.
--
-- FOUR COMMENTS ARE RE-ISSUED at the foot of this file. See the note there: a
-- COMMENT naming a dropped table is a false statement in the data dictionary,
-- and SchemaCommentTest reads it.
-- =============================================================================

DROP TABLE work_item_link;
DROP TABLE work_item;


-- -----------------------------------------------------------------------------
-- The four comments that named the dropped tables
-- -----------------------------------------------------------------------------
--
-- SchemaCommentTest reads every COMMENT in the database and asks whether each
-- snake_case token it names is an object this schema still declares. Four were
-- true when they were written and are false the moment the DROPs above run — and
-- the migrations holding them cannot be edited, because Flyway checksums an
-- applied file and V1 and V3 are applied. So the comments are RE-ISSUED here,
-- which is the one edit a migration can make to another migration's prose.
--
-- Each keeps its reasoning and says the predecessor is retired rather than
-- deleting the sentence, per the working agreement: the next reader meets why the
-- column exists and learns where it came from, instead of a shorter sentence that
-- never mentions it. The name is spelled `work-item` rather than in snake_case,
-- deliberately — the rule reads a snake_case token as a CLAIM that the schema
-- declares that object, and this text is making the opposite claim.

COMMENT ON COLUMN issue.id IS
    'Carried from the retired work-item row for a migrated issue, so '
    'derived_item_annotation''s (source_type, source_id) still resolves. That table survives M1 '
    '(D11) and its rows are somebody''s private notes — a fresh id here would have orphaned every '
    'one of them silently. The source table was dropped in V4, one task after the copy; carrying '
    'the id is why that drop cost nothing.';

COMMENT ON COLUMN issue.deleted_at IS
    'Soft delete, inherited from the retired work-item table and REQUIRED rather than stylistic. '
    'Scope §3: a downstream time entry or invoice line will hold an id into this table WITH NO '
    'FOREIGN KEY, because the module rule forbids one — and those tables arrive with M6 and M8, '
    'which is why neither is named here as an identifier. A hard delete orphans them silently; '
    'this is the mitigation the module rule is paid for with.';

COMMENT ON TABLE issue_link IS
    'The retired work-item link table''s successor, and the same shape for the same reason: a '
    'polymorphic reference across a module boundary, WITH NO FOREIGN KEY on target_id. Scope §3 — '
    'nearly everything in a PM system points at an issue, and tracker is not a foundation module, '
    'so the pointer is an id and the price is integrity enforced in application code. TEST_CASE is '
    'the target type story H7 needs and it costs one enum member; the rows arrive with M3. The '
    'predecessor was dropped in V4 after V3 copied its rows here.';

COMMENT ON COLUMN report_job.created_idempotency_key IS
    'The Idempotency-Key of the request that queued this job, or NULL when none was sent (V66, '
    'audit E2). Opaque, write-once, never read as data — as on test_case, test_plan, test_run, '
    'suite, doc_page, issue, ai_proposal and artifact. The work-item table was on that list until '
    'V4 retired it, and `issue` is where its column went. A replayed key answers 200 with THIS job '
    'rather than 202 with a second one, which matters more here than on a row: a report job '
    'refreshes execution_fact, computes, renders and writes two artifacts, so a duplicate is a '
    'second full pass rather than a second row.';
