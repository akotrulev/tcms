-- V26 — §6g's GATING RULES, the last unbuilt half of the review workflow.
--
-- §6g's mechanics list ends with one line that has had no implementation since the plan was
-- written: "Gating (opt-in, off by default): a project may require APPROVED status before a case
-- can be added to a run, or before a plan can be started. Powerful and genuinely obstructive —
-- must be an explicit choice, with a documented lead override for the 2am release."
--
-- Everything it needs now exists and each part arrived in a different slice: the case and suite
-- ladders (§75), the plan ladder (§73), the review record the approvals are made through (§76),
-- the per-project settings row to be opt-in IN (§77), and a board (§81) that shows a person what
-- is blocking them. This migration is the two columns.
--
-- WHY THESE ARE NOT TIED TO review_enabled_*
--
-- The tempting reading is that gating is part of the reviewer apparatus and should therefore be
-- inert wherever review is switched off — the shape ReviewService#reReviewOnEdit takes, and §77
-- chose it there deliberately. It is the wrong reading here, and §77's own rule is what settles
-- it: "off is NARROW — it suppresses the reviewer apparatus and NOT the status ladder." A project
-- with review off still has DRAFT / IN_REVIEW / APPROVED on every case and plan, still moves
-- artifacts through them, and can perfectly well want "nothing unapproved goes into a release
-- run" without ever running a peer review. Gating reads the STATUS, which the opt-in does not
-- touch; so it is its own opt-in, off by its own default, and a project may hold either without
-- the other.
--
-- The consequence, stated rather than discovered: turning review OFF does not turn gating off.
-- A project that enables gating and later abandons peer review keeps the gate, because the gate
-- was never about peer review — it is about the status somebody put the artifact in.
--
-- WHAT "APPROVED" MEANS ON EACH SIDE, AND WHY THE PLAN COLUMN IS THE NARROWER ONE
--
--   * require_approved_case_for_run — every case materialised into a new run must be
--     test_case.lifecycle_status = 'APPROVED'. It binds on the RESOLVED set, whichever way the
--     run named it: an explicit caseIds list, or the expansion of a plan's scope. Binding only on
--     the explicit list would leave "create the run from the plan instead" as a bypass that needs
--     no permission and leaves no trace.
--
--   * require_approved_plan_to_start — a plan may only enter ACTIVE from APPROVED. This is one
--     edge, not one target: PlanLifecycle reaches ACTIVE from DRAFT (starting a plan on a project
--     that never turned review on), from APPROVED (starting a signed-off one) and from CLOSED
--     (REOPENING one). Applying the rule to the target would make a CLOSED plan permanently
--     un-reopenable — its status is CLOSED, not APPROVED, and no legal move takes it back to
--     APPROVED — which is a dead end reachable by ordinary use, the bound §68 and §69 each drew
--     from the other side. PlanStatusService's audit vocabulary already draws exactly the line
--     the gate needs: it spells CLOSED → ACTIVE as "plan.reopened" and everything else as
--     "plan.started", so "started" was already a defined act before there was a rule about it.
--
-- THE OVERRIDE NEEDS NO COLUMN AND NO NEW PERMISSION KEY
--
-- §6g asks for "a documented lead override". It is NOT stored here, because it is a property of
-- one request rather than of the project: the request carries an explicit flag and the server
-- asks for the artifact's own approve key (case:approve / plan:approve).
--
-- No new key is invented, for the argument §67 made about evidence:read, §79 about suite:comment
-- and §80 about the assignment gate: a key V3 seeds to nobody is a 403 no administrator can grant
-- their way out of. And the key is DERIVED rather than chosen — a holder of case:approve could
-- reach the identical end state today by approving the cases and then creating the run, so the
-- override widens nobody's reach. What it changes is which record is left behind: the override
-- leaves the cases honestly unapproved with the override on the run's audit row, where the
-- workaround it replaces launders a sign-off nobody performed into the approval history §6g
-- exists to keep honest.
--
-- No new table, so the policy count stays at 58 (§80's V25 moved it 57 → 58).

ALTER TABLE project_settings
    ADD COLUMN require_approved_case_for_run  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN require_approved_plan_to_start BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN project_settings.require_approved_case_for_run IS
    'Plan §6g''s gating rule for cases, opt-in and OFF by default. When true, creating a run whose '
    'resolved case set contains any case that is not lifecycle_status = ''APPROVED'' is refused '
    'with 409 naming the offending keys — whether the set came from an explicit caseIds list or '
    'from the expansion of a plan''s scope, because otherwise the plan is the bypass. Deliberately '
    'INDEPENDENT of review_enabled_case: §6g''s opt-in suppresses the reviewer apparatus, not the '
    'status ladder, and a project can want approved-only runs without peer review. Overridable per '
    'request by a holder of case:approve, who could reach the same state by approving first.';
COMMENT ON COLUMN project_settings.require_approved_plan_to_start IS
    'Plan §6g''s gating rule for plans, opt-in and OFF by default. When true, DRAFT → ACTIVE is '
    'refused: a plan is started from APPROVED. It binds on that ONE EDGE and not on the target — '
    'CLOSED → ACTIVE is a REOPEN (PlanStatusService already audits it as plan.reopened), and '
    'refusing it would make a closed plan permanently un-reopenable, since no legal move takes '
    'CLOSED back to APPROVED. Overridable per request by a holder of plan:approve.';
