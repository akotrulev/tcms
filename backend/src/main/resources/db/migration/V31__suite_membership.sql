-- V31 — a suite becomes a RESOLVABLE COLLECTION, and a case may belong nowhere.
--
-- Authoring-rework decision 4 (docs/plans/2026-08-04-test-case-authoring-rework.md): a suite's
-- members are the cases FILED in it, plus cases added to it INDIVIDUALLY, plus every live case
-- carrying ANY of its tags — unioned and deduped. A case may be created with no suite and no tags
-- at all.
--
-- What that fixes: `test_case.suite_id` has been `NOT NULL` since V1, so a case that is both a
-- Payments case and a smoke test had to pick one folder, and a case nobody had filed yet could not
-- be created. The suite tree is a filing cabinet; the product needs it to also be a saved query.
--
-- -----------------------------------------------------------------------------
-- WHY suite_id IS RELAXED AND NOT DROPPED
-- -----------------------------------------------------------------------------
--
-- Membership could have been modelled purely as `suite_case` + `suite_tag_rule`, with the column
-- gone. Keeping it nullable is what makes this change ADDITIVE: the suite tree's case counts, the
-- case grid's suite column, `plan_scope_item.suite_id` run scoping, `suite_run_summary` and every
-- suite-grouped report keep reading the column they already read, and none of them has to learn a
-- union query in the same slice that introduces one. The column now means "the folder this case is
-- FILED in", which is one of the three membership arms rather than the whole of it.
--
-- -----------------------------------------------------------------------------
-- THE FACT LOG IS THE PART THAT IS SILENT WHEN WRONG
-- -----------------------------------------------------------------------------
--
-- `execution_fact.suite_id` and `suite_path` are `NOT NULL` in V1 and are filled by
-- `ExecutionSource.projectRecordedExecutions()` through `JOIN suite s ON s.id = c.suite_id`. With a
-- nullable `suite_id` that INNER JOIN does not fail and does not warn — it DROPS THE FACT ROW
-- ENTIRELY for every unfiled case. The result is a short fact log: every report reads it happily,
-- every number is smaller than the truth, and the tick logs a successful run. That is the exact
-- class of defect ExecutionSource's own comments exist to prevent, so the columns are relaxed here
-- and the join becomes a LEFT JOIN in the same commit.
--
-- Facts already written are NOT touched. Their frozen `suite_id`/`suite_path` say where the case
-- was filed WHEN IT RAN, which is the freeze §86 argued for; a backfill would rewrite history.
-- Suite-grouped report sections now carry an explicit "not filed" bucket rather than omitting rows,
-- because a section that quietly drops results is the same short-log defect one layer up.
--
-- -----------------------------------------------------------------------------
-- WHY TWO TABLES AND NOT ONE
-- -----------------------------------------------------------------------------
--
-- `suite_case` holds a decision about ONE case ("this specific case is also part of the release
-- suite"). `suite_tag_rule` holds a decision about a QUERY ("everything tagged smoke belongs here"),
-- and its membership changes when a case is tagged, without anything writing to the suite. Collapsed
-- into one table with a nullable case_id / nullable tag they would share a constraint, a unique
-- index and a reader, and every query would have to say which arm it meant.
--
-- Run scoping needs no new mechanism: "run everything tagged smoke" is a suite whose tag rule is
-- `smoke`, scoped into a plan the way suites already are (`plan_scope_item.suite_id`). No tag option
-- on plan scope, and `ck_plan_scope_target` is untouched.

-- -----------------------------------------------------------------------------
-- 1. A case may be unfiled.
-- -----------------------------------------------------------------------------

ALTER TABLE test_case ALTER COLUMN suite_id DROP NOT NULL;

COMMENT ON COLUMN test_case.suite_id IS
    'The folder this case is FILED in, or NULL for an unfiled case (V31). One of the three arms of '
    'suite membership — the other two are suite_case (added individually) and suite_tag_rule '
    '(matched by tag), and a case reachable from three suites carries no row here it did not '
    'already have. Nullable since V31: a case may be created before anybody has decided where it '
    'lives. Readers that group by it must render an explicit "not filed" bucket rather than '
    'dropping the row.';

-- -----------------------------------------------------------------------------
-- 2. The fact log tolerates an unfiled case.
-- -----------------------------------------------------------------------------

ALTER TABLE execution_fact ALTER COLUMN suite_id   DROP NOT NULL;
ALTER TABLE execution_fact ALTER COLUMN suite_path DROP NOT NULL;

COMMENT ON COLUMN execution_fact.suite_id IS
    'Where the case was filed WHEN IT RAN — frozen at insert, never re-filed (§86). NULL means the '
    'case was unfiled at that moment (V31), which is a fact about the execution and not a gap: the '
    'alternative is the LEFT JOIN''s absence, which drops the execution from the log entirely.';

-- -----------------------------------------------------------------------------
-- 3. Cases added to a suite one at a time.
-- -----------------------------------------------------------------------------

CREATE TABLE suite_case (
    company_id UUID NOT NULL REFERENCES company(id),
    suite_id   UUID NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by   UUID REFERENCES app_user(id),
    PRIMARY KEY (company_id, suite_id, case_id)
);

COMMENT ON TABLE suite_case IS
    'A case added to a suite INDIVIDUALLY (V31) — the second arm of suite membership, beside '
    'test_case.suite_id (filed) and suite_tag_rule (matched). A set, not a list: there is no '
    'position column, because a resolved collection is ordered by the case''s own key and not by '
    'the order somebody added things to it.';
COMMENT ON COLUMN suite_case.added_by IS
    'Nullable like every other actor column since §66 — an offboarded account does not un-add a '
    'case.';

-- The resolution query asks both directions: "which cases are in this suite" (membership) and
-- "which suites hold this case" (the case detail's suite list). company_id leads both, per ERD §0.1
-- point 3 — SchemaConventionTest fails the build on an index that mentions it without leading with
-- it. The PK serves the first direction; this index serves the second.
CREATE INDEX suite_case_case_idx ON suite_case (company_id, case_id);

-- -----------------------------------------------------------------------------
-- 4. The suite's tag rules.
-- -----------------------------------------------------------------------------

CREATE TABLE suite_tag_rule (
    company_id UUID NOT NULL REFERENCES company(id),
    suite_id   UUID NOT NULL REFERENCES suite(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    PRIMARY KEY (company_id, suite_id, tag)
);

COMMENT ON TABLE suite_tag_rule IS
    'The tags a suite claims (V31) — the third arm of membership. UNIONED, never intersected: a '
    'suite with rules `smoke` and `payments` holds every case carrying EITHER. Intersection is the '
    'other defensible reading and it is not this one, because a two-tag suite that silently holds '
    'nothing is indistinguishable from an empty project. AND-ing tags is a saved filter on the case '
    'grid, which the URL already expresses.';
COMMENT ON COLUMN suite_tag_rule.tag IS
    'Matched against case_tag.tag exactly — same case, same spelling. NOT case_automation_tag: '
    'those are the selection tags a CI runner filters on (§6c), and merging the two would make '
    '"run the smoke suite" and "cases the release manager flagged" the same query.';

-- "Which suites claim this tag" — the direction the reverse resolution walks when a case is tagged.
CREATE INDEX suite_tag_rule_tag_idx ON suite_tag_rule (company_id, tag);

-- The membership union joins case_tag to suite_tag_rule by tag, per project. case_tag has carried
-- NO index since V1 beyond its (case_id, tag) primary key, so the tag arm of every resolution would
-- be a sequential scan of the company's tags for the sake of one suite.
CREATE INDEX case_tag_tag_idx ON case_tag (company_id, tag);

-- -----------------------------------------------------------------------------
-- 5. RLS — the 60th and 61st policies.
--
-- nullif(...) rather than V1's bare current_setting, for V4's reason: a GUC set once on a pooled
-- connection resets to '' rather than NULL for the rest of that session, and ''::uuid RAISES
-- instead of matching nothing. A table created after V4 must be born with the fixed predicate.
--
-- SchemaConventionTest counts the policies, so 59 → 61 with this file. Moving the count is the
-- point: a table added without its policy would otherwise pass by never being looked at.
-- -----------------------------------------------------------------------------

ALTER TABLE suite_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE suite_case FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON suite_case USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

ALTER TABLE suite_tag_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE suite_tag_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON suite_tag_rule USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
