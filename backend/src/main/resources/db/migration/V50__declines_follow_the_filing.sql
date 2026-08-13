-- =============================================================================
-- V50 — a decline the case's folder no longer imposes stops existing
--
-- Source: handoff §192, which is §187's own recorded next slice: "the INERT
--         decline — one whose attachment no longer reaches the case — is state
--         no screen can show and no test can reach."
--
-- §187 gave case_condition_optout its first writer and, with it, its first way
-- of reaching a state nobody can observe. CaseConditions.reconcileOptouts
-- protected a decline whose suite_condition no longer reaches the case, on the
-- argument that the client was never shown the row and therefore cannot
-- resubmit it — the same protection reconcile() gives a retired entry's
-- attachment. The slice stated the cost in its own javadoc:
--
--     Move the case back under Billing and a rule the author turned off six
--     months ago is off again, with nothing on the screen having said so in
--     between.
--
-- §192 reverses the MOVE half of that and keeps the RETIRED half. The clause
-- the reversal turns on is §187's own: "deleting declines on a move loses the
-- decision on an action that said nothing about conditions". A move is not such
-- an action. Since §98 a case inherits from the folder it is FILED in and from
-- nothing else, so relocating a case is the one and only act that decides which
-- standing rules govern it — and the product had already answered this question
-- the other way one path over. CaseCloneService has said since §129, and
-- CaseConditions.declinedAtSuite has implemented since §187, that a clone
-- landing outside the source's folder carries NO declines. A move that kept
-- them made a relocated case decline a rule that a copy of it, dropped in the
-- same folder by the same author in the same minute, would not.
--
-- Retirement stays protected, and the asymmetry is the argument: retirement is
-- reversible and says nothing about this case, so restoring the entry brings
-- the attachment, the decline and the toggle back together. A move is a new
-- decision by a person about this case.
--
-- -----------------------------------------------------------------------------
-- 1. WHAT THIS DELETES
-- -----------------------------------------------------------------------------
--
-- Every case_condition_optout row whose suite_condition hangs off a suite the
-- case is not filed in. That is the whole of the invariant §192 establishes:
--
--     optout.suite_condition.suite_id = test_case.suite_id, for every row.
--
-- The writers hold it going forward — CaseUpdateService and CaseBulkService
-- both narrow to the destination folder now, and CaseCreateService never had
-- another folder to be wrong about. This statement is what makes it true of
-- rows already stored, which is the half a code change cannot reach.
--
-- Note which cause is NOT in the predicate. An attachment behind a RETIRED
-- library entry still hangs off the case's own folder, so it is retained here
-- exactly as it is at runtime; the two rules are the same rule.
--
-- A case with a NULL suite_id is unfiled (§96) and inherits nothing, so every
-- decline it carries is deleted. `s.suite_id = c.suite_id` is already false for
-- a NULL, so the predicate needs no arm for it — stated because a three-valued
-- comparison doing the right thing by accident and doing it by design read the
-- same in the diff.
--
-- A case whose suite was SOFT-DELETED keeps its declines: the attachment still
-- hangs off the folder the case is filed in. Deliberate. The case itself is
-- dropped from every list in the product while that is true, so sweeping it
-- here would be deleting the decisions of a case nobody can see, on an event
-- that may be undone.
--
-- -----------------------------------------------------------------------------
-- 2. RLS: THE OWNER IS NOT EXEMPT, SO THE CONTEXT IS SET PER COMPANY
-- -----------------------------------------------------------------------------
--
-- V15's, V18's and V44's pattern, fourth use. case_condition_optout, suite_condition
-- and test_case are all under FORCE ROW LEVEL SECURITY, which covers the table
-- owner this migration runs as (V1's RLS block), so a DELETE issued with no
-- app.current_company set would match ZERO ROWS AND REPORT SUCCESS. Fail-closed,
-- and silent — which is the whole reason a data migration in this schema is
-- written as a loop over `company` rather than as one statement.
--
-- It matters more here than it did in V44. That migration corrected a column
-- nothing reads; this one removes rows that CHANGE WHAT A TESTER IS ASKED TO
-- DO, and a no-op reporting success would leave exactly the invisible state the
-- slice is named for while the code around it assumed the invariant held.
--
-- set_config(..., is_local => true) scopes each setting to this migration's
-- transaction, and the final reset returns it to '' rather than leaving the last
-- company set — the difference between "no context" and "somebody else's
-- context" on a connection Flyway hands back to the pool. V4's nullif(…, '')
-- is what makes that empty string read as "no company" rather than raising.
--
-- -----------------------------------------------------------------------------
-- 3. NO DDL, AND NO CONSTRAINT EITHER
-- -----------------------------------------------------------------------------
--
-- The invariant spans three tables, so it is not a CHECK: expressing it in the
-- schema means a trigger on two tables or a denormalized suite_id on the optout
-- row, and both make an ordinary reorder of a folder's conditions a write to
-- this table. It is held by the writers and pinned by the tests instead —
-- ConditionWriteEndpointTest$Declining for the edit path, CaseBulkEndpointTest
-- for the bulk one.
-- =============================================================================


DO $$
DECLARE
    v_company RECORD;
    v_deleted integer;
    v_total   integer := 0;
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);

        DELETE FROM case_condition_optout o
              WHERE NOT EXISTS (
                    SELECT 1
                      FROM suite_condition s
                      JOIN test_case c ON c.id = o.case_id
                     WHERE s.id = o.suite_condition_id
                       AND s.suite_id = c.suite_id);

        GET DIAGNOSTICS v_deleted = ROW_COUNT;
        v_total := v_total + v_deleted;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);

    RAISE NOTICE 'V50: deleted % decline(s) against a folder the case is no longer filed in',
                 v_total;
END $$;


-- =============================================================================
-- 4. THE COMMENT THE TABLE NEVER HAD
-- =============================================================================
--
-- V1 gave this table no comment at all, so the one thing about it that is not
-- readable from its four columns — how long a row lives — was written down in
-- Java only. §190 built SchemaCommentTest and made a comment's identifiers
-- checkable; a table whose rule is a lifetime is exactly the kind that should
-- carry one.
-- =============================================================================

COMMENT ON TABLE case_condition_optout IS
    'ONE case declining ONE inherited attachment (plan §6f). Keyed on suite_condition_id rather '
    'than on the library entry, so declining a rule at one suite leaves an identical rule attached '
    'elsewhere in force. A row lives exactly as long as its attachment hangs off the folder the '
    'case is FILED in: suite_condition.suite_id = test_case.suite_id holds for every row (V50, '
    '§192), because a move is the only act that decides which standing rules govern a case and '
    'CaseCloneService has answered a clone the same way since §129. A retired library entry does '
    'NOT end a row - the attachment still belongs to the folder, retirement is reversible, and no '
    'list shows the row while it lasts. Deleting the attachment does, by ON DELETE CASCADE.';
