-- =============================================================================
-- V4 — the company-isolation policy must treat a RESET GUC as "no company"
--
-- DEFECT (found by the async-context harness, plan §0.2)
--
-- V1's policy read:
--     company_id = current_setting('app.current_company', true)::uuid
--
-- with the `true` there so a missing setting yields NULL and `company_id = NULL`
-- is never true — the trap-2 property: no context means ZERO rows.
--
-- That is correct only for a connection where the GUC was NEVER set. Postgres
-- does not return a custom GUC to "undefined" when a transaction ends: once the
-- setting has been assigned even once — including via
-- set_config(..., is_local => true), which is what CompanyAwareTransactionManager
-- issues per transaction — its reset value for the remainder of the SESSION is
-- the EMPTY STRING. So on any pooled connection that has already served a
-- company, current_setting('app.current_company', true) returns '' rather than
-- NULL, and ''::uuid raises:
--
--     ERROR:  invalid input syntax for type uuid: ""
--
-- Measured on Postgres 17.10, not inferred.
--
-- Severity: this fails CLOSED, so it was never a cross-company leak. What it was
-- is a 500 on every legitimately context-less read that touches a company-owned
-- table — the login path, the provisioning CLI, an instance-level operator query,
-- a report job that forgets its context — on any connection Hikari has already
-- warmed. Which is to say: never on the first request, always afterwards.
--
-- It also means V1's own trap-2 test was narrower than it read. It opened a fresh
-- connection, so it exercised only the never-set case. RlsIsolationTest now covers
-- the recycled case as its own assertion.
--
-- THE FIX: nullif(..., '') collapses both spellings of "absent" — never set, and
-- reset to empty — onto NULL, which is the value the trap-2 reasoning already
-- depends on. The security predicate is otherwise unchanged: still a bare equality,
-- still no widening, so trap 2 is preserved rather than traded away. (Widening the
-- policy was measured and rejected in §11 for exactly that reason.)
--
-- Rewritten from pg_policies rather than from a fourth hand-maintained copy of the
-- 56-table list: the live catalogue is the truth, a copied list drifts, and this
-- file would be the fourth place that list lives (handoff §6, lesson 9). The loop
-- refuses to run if it finds a policy whose predicate is not the one V1 created,
-- so a hand-edited policy is a failed migration rather than a silent overwrite.
-- =============================================================================

DO $$
DECLARE
    p         RECORD;
    rewritten INT := 0;
    expected  TEXT := '(company_id = (current_setting(''app.current_company''::text, true))::uuid)';
BEGIN
    FOR p IN
        SELECT tablename, qual
          FROM pg_policies
         WHERE schemaname = current_schema()
           AND policyname = 'company_isolation'
         ORDER BY tablename
    LOOP
        IF p.qual IS DISTINCT FROM expected THEN
            RAISE EXCEPTION
                'company_isolation on % has an unexpected predicate; refusing to overwrite it: %',
                p.tablename, p.qual;
        END IF;

        EXECUTE format('DROP POLICY company_isolation ON %I', p.tablename);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)',
            p.tablename);
        rewritten := rewritten + 1;
    END LOOP;

    -- The count is asserted, not logged. A loop that silently rewrote nothing —
    -- because the policy was renamed, or the schema search path moved — would leave
    -- the defect in place while the migration reported success.
    IF rewritten <> 56 THEN
        RAISE EXCEPTION 'expected to rewrite 56 company_isolation policies, rewrote %', rewritten;
    END IF;
END $$;
