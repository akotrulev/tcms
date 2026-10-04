-- =============================================================================
-- V15 - a project has a colour (plan 023 D17)
--
-- THE VERSION IS V15, READ FROM THE DIRECTORY (V1..V14 on 2026-10-04). out-of-order is unset,
-- so a lower number arriving after a higher one is silently never applied.
--
-- ONE COLUMN: project.color, a design-token name (project-1 .. project-8), never a hex, so the
-- palette can be re-tuned without a migration (the Label.color precedent). It colours a
-- project's key chip and nothing else.
--
-- THE BACK-FILL IS V13'S LOOP, AND THE TRAP IS THE REASON. project carries FORCE ROW LEVEL
-- SECURITY and Flyway connects as tcms_owner, which holds no BYPASSRLS, so one UPDATE with
-- app.current_company unset matches ZERO rows and reports success - and the SET NOT NULL after it
-- would then fail on exactly the database it matters on. `company` is on the ERD 0.1 exception
-- list and carries no policy, so it can be walked; setting the GUC per company makes that
-- company's projects visible through the ordinary policy. set_config's third argument is
-- transaction-local and Flyway runs a migration in one transaction, so the setting is gone when
-- this file ends.
--
-- ASSERTED PER COMPANY, UNDER THAT SAME CONTEXT: the GUC took, and no project of that company
-- is left without a colour. A global count taken before the loop would itself read zero under RLS.
--
-- hashtext(key)::bigint, cast BEFORE abs(): abs of the int minimum overflows. The colour need
-- only be deterministic, and need not agree with the Java default a create assigns.
-- =============================================================================

ALTER TABLE project ADD COLUMN color TEXT;

DO $$
DECLARE
    v_company UUID;
    v_missing INT;
BEGIN
    FOR v_company IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', v_company::text, true);
        IF current_setting('app.current_company', true) IS DISTINCT FROM v_company::text THEN
            RAISE EXCEPTION 'V15 back-fill: app.current_company did not take for company %',
                v_company;
        END IF;

        UPDATE project
           SET color = 'project-' || (mod(abs(hashtext(key)::bigint), 8) + 1)
         WHERE company_id = v_company AND color IS NULL;

        SELECT count(*) INTO v_missing
          FROM project WHERE company_id = v_company AND color IS NULL;
        IF v_missing <> 0 THEN
            RAISE EXCEPTION 'V15 back-fill left % project(s) of company % without a colour',
                v_missing, v_company;
        END IF;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END
$$;

ALTER TABLE project ALTER COLUMN color SET NOT NULL;
ALTER TABLE project ADD CONSTRAINT ck_project_color CHECK (color ~ '^project-[1-8]$');

COMMENT ON COLUMN project.color IS
    'The design-token name (project-1 .. project-8) of the colour this project''s key chip is '
    'drawn in. A token and not a hex so the palette can be re-tuned without a migration. Assigned '
    'by the server when a create sends none; set through PUT /projects/{id}/color (plan 023 D17).';
