-- V28 — deleting a saved report must not be refused by the reports it produced.
--
-- R5 gives report_definition its first writer, and with it the first DELETE. The slice plan said
-- R5 needs no migration; it does, and this is the reason, found by reading the two foreign keys
-- that point at this table rather than by running the delete:
--
--   report_job.definition_id       REFERENCES report_definition(id) ON DELETE SET NULL   (V27)
--   report_artifact.definition_id  REFERENCES report_definition(id)                      (V1)
--
-- V27 chose SET NULL and wrote down why — "deleting a saved report must not delete the history of
-- what it produced". V1 predates that reasoning and left the default, NO ACTION. ReportJobRunner
-- copies the job's definition_id onto every artifact it writes, so from the first regenerate of a
-- saved report onward, deleting that report raises a foreign-key violation from inside the delete
-- transaction — a 500 on a route whose contract says 204, and only for definitions that have
-- actually been used, which is to say only for the ones somebody cares about.
--
-- The application-side alternative — null the artifacts first, in the service — holds the same
-- invariant in one code path instead of in the database, and leaves every other path (a future
-- retention reaper, an operator's DELETE) still able to hit the violation. The rule belongs where
-- the sibling column already keeps it.
--
-- DROP + ADD rather than an added constraint, so there is exactly one FK on this column and the
-- next reader does not have to intersect two — V27's own argument about the format CHECK.
--
-- No new table, no new policy: SchemaConventionTest's count stays at 59.

ALTER TABLE report_artifact DROP CONSTRAINT report_artifact_definition_id_fkey;

ALTER TABLE report_artifact
    ADD CONSTRAINT report_artifact_definition_id_fkey
    FOREIGN KEY (definition_id) REFERENCES report_definition(id) ON DELETE SET NULL;

COMMENT ON COLUMN report_artifact.definition_id IS
    'The saved definition this artifact was generated from, or NULL for an ad-hoc report. '
    'ON DELETE SET NULL since V28, matching report_job.definition_id: deleting a saved report '
    'leaves the files it produced, which become indistinguishable from ad-hoc ones because that '
    'is what they now are.';
