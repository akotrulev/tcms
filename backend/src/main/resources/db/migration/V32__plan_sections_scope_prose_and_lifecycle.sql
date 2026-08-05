-- =============================================================================
-- V32 — a test plan gets prose, sub-feature sections and a shorter ladder
--
-- Three changes from docs/plans/2026-08-05-test-plan-detail-rework.md (§97) and
-- docs/plans/2026-08-05-organization-model-restatement.md (M7, M9). They ride in
-- one migration because §97 is one slice and two of them touch `test_plan`.
--
--   1. D6  — `scope_text` / `out_of_scope_text`: what the plan says it will and
--            will not test, as prose.
--   2. D7′ — `plan_scope_item` gains `section_name`, `ordinal` and a `tag` arm,
--            so the plan's cases are grouped into named sub-features whose
--            membership is a LIVE query.
--   3. M7  — the plan lifecycle drops ACTIVE and CLOSED. M9 adds WIP to the
--            seeded execution statuses.
--
-- No new table, so no new RLS policy: the count stays at 61.
--
-- -----------------------------------------------------------------------------
-- TWO MEANINGS OF THE WORD "SCOPE", AND BOTH ARE ON THIS TABLE
-- -----------------------------------------------------------------------------
--
-- `plan_scope_item` has meant "which cases the plan covers" since V1.
-- `scope_text` added here means "the prose a test plan calls its Scope section"
-- — what will be tested, in a sentence. They are unrelated and they sit on the
-- same aggregate, so both columns carry a comment naming the other meaning.
-- A reader who knows only one of them is the failure this guards against.
--
-- -----------------------------------------------------------------------------
-- A SECTION IS A LIVE SELECTOR AND NOT A SNAPSHOT (D7′)
-- -----------------------------------------------------------------------------
--
-- The rejected design (D7) expanded a suite or a tag ONCE, at add time, into
-- individual case rows. TestNG's <test> block is this exact level — a named
-- subdivision carrying its own <classes> list and its own <groups> filter — and
-- it re-resolves on every run. So a section stores REFERENCES and its membership
-- is a query, exactly as a suite's has been since V31.
--
-- The accepted cost, stated here because it is the one somebody will meet: a
-- plan's contents can change between approval and execution. Tag a case
-- `checkout` and it joins every section claiming that tag. §82's case-approval
-- gate still fires at run-create time over the RESOLVED set, so an unapproved
-- case arriving by tag is still refused at the moment it would run — what goes
-- stale is the PLAN's own approval, not the case gate.
--
-- -----------------------------------------------------------------------------
-- THE CONSTRAINT IS THE ONE THING HERE THAT CAN SILENTLY ADMIT A NONSENSE ROW
-- -----------------------------------------------------------------------------
--
-- `ck_plan_scope_target` was a two-way XOR: exactly one of suite_id / case_id.
-- Three arms make the same statement longer, and a widening that said "at most
-- one" would admit a row naming nothing — a scope line that expands to no cases
-- and reads on every screen as a plan somebody forgot to fill in.
--
-- IT IS NOT A THREE-WAY EXACTLY-ONE, WHICH IS A DEPARTURE FROM THE PLAN AND IS
-- FORCED BY THE CONTRACT. `PlanSectionInput` says an empty section is legal and
-- is kept — "an author names the sub-feature before filling it, and dropping
-- empty ones on save would delete the heading they just typed". A section has no
-- table of its own (that is what keeps the policy count at 61), so it exists
-- only as a column on its member rows, and a section with no members therefore
-- needs one row that names the section and no target. Hence:
--
--     at most one target, AND a row with no target must name a section.
--
-- which is exactly-one for an unsectioned row and a section placeholder for the
-- other case. `PlanScopeConstraintTest` asserts all of it directly rather than
-- trusting this comment, because every violation it prevents is a row that reads
-- as ordinary data.
--
-- -----------------------------------------------------------------------------
-- M7 IS LOSSY AND THE LOSS IS BOUNDED BY THE AUDIT LOG
-- -----------------------------------------------------------------------------
--
-- Every ACTIVE and CLOSED plan becomes APPROVED — a plan that was started or
-- finished was, by construction, past drafting — and `closed_at` is dropped with
-- the state that filled it. THE DOWN-MIGRATION CANNOT RESTORE WHICH PLANS WERE
-- CLOSED, and this product has a real deployment (§58), so that is a live
-- concern rather than a theoretical one.
--
-- What bounds it: `audit_event` already carries `plan.started`, `plan.reopened`
-- and `plan.closed`, so the HISTORY SURVIVES WHERE HISTORY BELONGS even though
-- the current-state column stops carrying it. The next reader will otherwise
-- assume the information was thrown away.
--
-- Why the states go at all (M7): `plan.started` / `plan.closed` describe a plan
-- being RUN, which is the execution's business, and `test_run` already has
-- NOT_STARTED → IN_PROGRESS → COMPLETED → ABORTED. Plan-ACTIVE duplicated
-- run-IN_PROGRESS one level up. IN_REVIEW stays: it is the transit between the
-- two states the user named, `review.subject_type` admits PLAN, and cutting it
-- would remove plan review from the product.
--
-- THE UPDATE RUNS INSIDE A COMPANY LOOP, and that is not caution. `test_plan` is
-- under FORCE ROW LEVEL SECURITY, which covers the owner this migration runs as.
-- With no context set the policy matches no row, so a bare UPDATE would touch
-- ZERO rows, report success, and leave every ACTIVE plan behind to fail the new
-- CHECK — or, worse, to pass it because there were none in the migrating
-- database and to fail in production. V7 paid for this shape once; V15 and V18
-- are the loop this copies, reset to '' at the end included.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The plan's prose (D6).
-- -----------------------------------------------------------------------------

ALTER TABLE test_plan ADD COLUMN scope_text        TEXT;
ALTER TABLE test_plan ADD COLUMN out_of_scope_text TEXT;

COMMENT ON COLUMN test_plan.scope_text IS
    'Free prose: what this plan says will be tested. NOT the plan''s scope ITEMS — plan_scope_item '
    'is which cases it covers, and the two meanings of "scope" live on one aggregate (§97 D6). '
    'Served as `scopeText`; NULL is "nobody has written one", which is not the same as an empty '
    'string somebody cleared.';
COMMENT ON COLUMN test_plan.out_of_scope_text IS
    'Free prose: what is deliberately not tested here. Its whole value is being explicit — an '
    'absent out-of-scope section reads as an oversight to the person signing the release off.';


-- -----------------------------------------------------------------------------
-- 2. Sub-feature sections and the tag arm (D7′, D8).
-- -----------------------------------------------------------------------------

ALTER TABLE plan_scope_item ADD COLUMN section_name TEXT;
ALTER TABLE plan_scope_item ADD COLUMN ordinal      INT NOT NULL DEFAULT 0;
ALTER TABLE plan_scope_item ADD COLUMN tag          TEXT;

COMMENT ON COLUMN plan_scope_item.section_name IS
    'The sub-feature this line belongs to — "Create an object", "Edit", "Navigation". NULL is the '
    'UNSECTIONED group, which is what every plan written before §97 holds. Plan-local: a section '
    'has no key, no lifecycle, no versions and no existence outside this plan, which is what makes '
    'it not a suite. Carried on the member rows rather than in a table of its own, so a section '
    'with no members is one row naming it with no target (see ck_plan_scope_target).';
COMMENT ON COLUMN plan_scope_item.ordinal IS
    'Author order WITHIN the plan — the order the sections were arranged in, assigned by the '
    'server from the order submitted rather than trusted from the client. The scope itself is '
    'still a SET: PlanScope sorts the ids it hashes, because two folders listed in either order '
    'are one scope and a hash over insertion order moves without the plan changing.';
COMMENT ON COLUMN plan_scope_item.tag IS
    'Matched against case_tag.tag exactly, the same arm suite_tag_rule uses (§97 D8) — so "add by '
    'tag" on a plan section and "add by tag" on a suite are the same act. UNIONED with the other '
    'arms, never intersected (V31 settles that), and there is no exclude arm anywhere in this '
    'product yet (M6, a recorded gap).';

-- V31's header refused this arm outright:
--
--   ~~"Run scoping needs no new mechanism: 'run everything tagged smoke' is a suite whose tag rule
--   is `smoke`, scoped into a plan the way suites already are. No tag option on plan scope, and
--   ck_plan_scope_target is untouched."~~
--
-- → superseded 2026-08-05 (§97 D8 / M3). A plan section is a named subdivision the author fills
-- four ways, and forcing one of the four through a whole new SUITE — reusable project content with
-- a key and a history — to express "the checkout cases in this section" is a worse trade than the
-- duplicated predicate. The cost that argument avoided is real and is accepted: there are now two
-- ways to say "everything tagged smoke". The old sentence is struck through rather than deleted so
-- the next reader finds the reasoning and its reversal, not a gap.

ALTER TABLE plan_scope_item DROP CONSTRAINT ck_plan_scope_target;
ALTER TABLE plan_scope_item ADD CONSTRAINT ck_plan_scope_target CHECK (
        (suite_id IS NOT NULL)::int + (case_id IS NOT NULL)::int + (tag IS NOT NULL)::int <= 1
    AND (section_name IS NOT NULL
         OR suite_id IS NOT NULL OR case_id IS NOT NULL OR tag IS NOT NULL));


-- -----------------------------------------------------------------------------
-- 3. M7 — the lifecycle reduction. Data first, constraint second.
-- -----------------------------------------------------------------------------

DO $$
DECLARE
    v_company RECORD;
    v_moved   integer := 0;
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);

        UPDATE test_plan
           SET status = 'APPROVED'
         WHERE status IN ('ACTIVE', 'CLOSED');

        GET DIAGNOSTICS v_moved = ROW_COUNT;
        RAISE NOTICE 'V32: company % — % plan(s) moved to APPROVED', v_company.id, v_moved;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;

-- Dropped by its generated name rather than with IF EXISTS: a wrong guess must fail this migration
-- loudly. Silently leaving the old constraint in place would let the narrower one below pass
-- (both are satisfied by the same rows) and leave a dead rule nobody can account for.
ALTER TABLE test_plan DROP CONSTRAINT test_plan_status_check;
ALTER TABLE test_plan ADD CONSTRAINT test_plan_status_check
    CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED'));

ALTER TABLE test_plan DROP COLUMN closed_at;

-- §82's gating rule went with the edge it bound on. The column is DROPPED rather than left mapped:
-- a policy toggle whose rule no longer exists is worse than an absent one, because the settings
-- screen keeps offering it, an administrator turns it on to stop unapproved releases, and nothing
-- happens — with a success message. §6g's OTHER gating rule, require_approved_case_for_run, is
-- untouched and still binds at run-create time over the resolved case set, which is where a plan
-- whose sections re-resolve (D7′) still meets an approval check.
--
-- The loss: a project that had this on has no record of it after this migration except its
-- audit_event rows for project.settings_updated, which carry the payload.
ALTER TABLE project_settings DROP COLUMN require_approved_plan_to_start;

COMMENT ON COLUMN test_plan.status IS
    'DRAFT → IN_REVIEW → APPROVED, and that is the whole ladder since V32 (M7). ACTIVE and CLOSED '
    'were removed because they described a plan being RUN, which is test_run''s job — a plan is an '
    'outline of what must be tested, and whether it is under way is answered by its runs. Their '
    'history lives in audit_event''s plan.started / plan.reopened / plan.closed rows.';


-- -----------------------------------------------------------------------------
-- 4. M9 — WIP joins the seeded execution statuses.
-- -----------------------------------------------------------------------------
--
-- The six seeded today have no value meaning "somebody is running this right
-- now", which is what a lead wants halfway through a long manual cycle. Zephyr
-- has it and this product's own vocabulary was the odd one out.
--
-- NOT FINAL and NOT a pass, like Retest: reporting groups both with UNTESTED as
-- "still owed a verdict", which is the honest reading — a case somebody has
-- picked up has no result yet, and counting it as anything else would move a
-- pass rate on the strength of somebody opening a screen.

ALTER TABLE case_status DROP CONSTRAINT case_status_kind_check;
ALTER TABLE case_status ADD CONSTRAINT case_status_kind_check
    CHECK (kind IN ('PASSED', 'FAILED', 'BLOCKED', 'SKIPPED', 'UNTESTED', 'RETEST', 'WIP'));

-- The seed function, reproduced IN FULL rather than patched, for V18's reason:
-- CREATE OR REPLACE replaces the whole body, so anything omitted here stops being
-- seeded, silently, for every project created from now on. Only the status list
-- differs from V18's — one row added at position 6.

CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    IF NOT EXISTS (SELECT 1 FROM project WHERE id = p_project_id) THEN
        RAISE EXCEPTION 'project % is not visible in company %', p_project_id, p_company_id
            USING ERRCODE = '23503';
    END IF;

    -- Feature is the one PROJECT-scoped dimension, and the only hierarchical and
    -- unlocked one: two client products have different feature trees, and the
    -- tree is meant to be edited (plan §6d). Seeded empty — inventing a client's
    -- feature taxonomy for them is how a controlled vocabulary gets worked around.
    INSERT INTO classification_dimension
        (id, company_id, project_id, key, name, cardinality, controlled, hierarchical, locked, position)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'feature', 'Feature',
           'MULTI', true, true, false, 3
     WHERE NOT EXISTS (
        SELECT 1 FROM classification_dimension
         WHERE company_id = p_company_id AND project_id = p_project_id AND key = 'feature');

    -- The seven default execution statuses. `kind` is what lets reporting aggregate
    -- a project's custom statuses without knowing their names, and counts_as_pass
    -- is separate from kind because a project may add a "Passed with comment".
    -- Colours match the frontend design tokens deliberately: status colour is
    -- load-bearing data in a TCMS and is NOT brandable (§6u).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Passed',   'PASSED',   '#2e7d5b', true,  true,  0),
            ('Failed',   'FAILED',   '#c0392b', true,  false, 1),
            ('Blocked',  'BLOCKED',  '#b7791f', true,  false, 2),
            ('Skipped',  'SKIPPED',  '#6b7280', true,  false, 3),
            ('Retest',   'RETEST',   '#7c5cbf', false, false, 4),
            ('Untested', 'UNTESTED', '#9ca3af', false, false, 5),
            ('WIP',      'WIP',      '#2563eb', false, false, 6)
        ) AS s(name, kind, color, is_final, counts_as_pass, position)
    LOOP
        INSERT INTO case_status
            (id, company_id, project_id, name, kind, color, is_final, counts_as_pass, position)
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind,
               v_spec.color, v_spec.is_final, v_spec.counts_as_pass, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM case_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- Every project gets a Default configuration so test_run.configuration_id can
    -- stay NOT NULL all the way through the rollup read models — suite_run_summary
    -- and plan_snapshot key on it, and a nullable PK column is not expressible.
    INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Default', '{}'::jsonb, true
     WHERE NOT EXISTS (
        SELECT 1 FROM configuration WHERE project_id = p_project_id AND is_default);

    -- V18. One documentation space, for the same structural reason as the
    -- configuration above: doc_page.space_id is NOT NULL, so without this row the
    -- project cannot hold a page at all.
    --
    -- It is NOT the "inventing the client's structure" mistake that keeps a
    -- Default *suite* from being seeded here. A suite is a decision about how
    -- somebody's tests are organised and appears in their tree; the space is a
    -- container the API does not expose, has no name anybody reads, and holds
    -- every page whatever its shape. The tree the client actually organises is
    -- doc_page.parent_id, and this seeds none of that.
    INSERT INTO doc_space (id, company_id, project_id, name)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Documentation'
     WHERE NOT EXISTS (
        SELECT 1 FROM doc_space WHERE project_id = p_project_id);
END $$;

COMMENT ON FUNCTION tcms_seed_project_defaults(UUID, UUID) IS
    'Called once per project, by project creation and by provision-company for the first project. '
    'Idempotent, and refuses a project it cannot see — which under RLS is also a project in '
    'another company. Seeds the Feature dimension, the seven case statuses (V32 added WIP), the '
    'Default configuration and (V18) the documentation space.';

-- THERE IS NO BACKFILL FOR WIP, and that is a decision rather than an omission.
-- V18 backfilled its doc_space because a NOT NULL foreign key had no row to point
-- at: the project was structurally broken without it. A status vocabulary is not
-- that. case_status has been PROJECT-configurable since V1, so a project that
-- deleted or renamed a seeded status did so deliberately, and a migration that
-- re-inserted rows into every project would overrule that silently and in bulk.
-- Existing companies top up by re-running `provision-company`, which is
-- idempotent by design and is the documented way to take on a release's new
-- vocabulary (M9).
