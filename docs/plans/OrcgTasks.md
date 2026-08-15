Written and committed: docs/plans/2026-08-15-org-chart-tasks.md (5149e58).

  How it's structured

  There's a shared preamble at the top — branch, read-CLAUDE.md-first, stage-only-your-files, commit-at-phase-end, don't-run-the-deferred-checks. Paste it above whichever task
  prompt you're running, so each prompt stays short without losing the binding rules.

  Then eleven prompts, each self-contained: the plan sections to read, the exact files it may touch, the gate, and when to commit.

  The order

  ┌─────┬──────────────────────────────────────────┬───────────────────────┬────────┐
  │  #  │                   Task                   │         Agent         │  Deps  │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T0  │ Decision gate — Q1–Q3                    │ you                   │ —      │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T1  │ Contract + both schema.d.ts              │ be-java               │ T0     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T2  │ Repository generalisation + §A1.1 helper │ be-java               │ T1     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T3  │ OrgChartService + controller             │ be-java               │ T2     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T4  │ Backend tests                            │ qa-api-java           │ T3     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T5  │ Query-plan review                        │ data-architect        │ T2     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T6  │ Extract teamTree.ts                      │ fe-react              │ —      │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T7  │ useOrgChart + nine invalidations         │ fe-react              │ T1, T6 │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T8  │ The screen                               │ fe-react              │ T7     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T9  │ Disclosure-boundary pass                 │ qa-security           │ T4     │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T10 │ Manual test cases                        │ qa-test-case-designer │ T4, T8 │
  ├─────┼──────────────────────────────────────────┼───────────────────────┼────────┤
  │ T11 │ Handoff, caches, queue rows              │ last lane             │ T8     │
  └─────┴──────────────────────────────────────────┴───────────────────────┴────────┘
  
  Run T6 first — it's independent, and it's the only task touching a shipped screen, so a regression in TeamsPage can't be confused with the new feature's fault.

  What I put in the prompts beyond the work

  Each one names the specific trap it exists to avoid, because these are the failures that pass a gate:

  - T2 — if an existing myRole assertion fails, stop and report; do not edit the test to make it pass. That's a caller-facing field on a shipped screen.
  - T4 — break the agreement test deliberately and watch it go red before counting it as coverage. Also: pre-assign a fixture UUID prefix and say which one — that has collided
  five times here and merges clean before failing at runtime.
  - T7 — all nine invalidations, nine test arms. Missing one leaves the chart stale in the exact moment someone restaffed a project, which is the moment they opened it.
  - T8 — the "Only projects you can open are shown here" line renders unconditionally. Showing it only when something is hidden makes its presence the existence claim the plan
  rejected.
  - T9 — assert on the response body, not the UI. A field the server sends and the client declines to render is precisely the defect this task exists to catch.

  Two things to know before you start

  T0 is genuinely blocking. Every prompt has a line reading Q1-Q3 answered as: RECOMMENDED — edit it if you decide differently. Detailing an unapproved disclosure boundary is
  the expensive mistake here.

  T2, T3 and T4 need Docker. ~/bin/tcms-remote backend ./gradlew build runs them on shanks instead, which is also the differently-shaped Linux observer. And ci.yml is still -x
  test, so your local gate is the only one that sees a backend test before the nightly.