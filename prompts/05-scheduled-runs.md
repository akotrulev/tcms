# Slice 5 — Scheduled runs

**Lane:** backend + contract + frontend · **Migration: `V69`** · **Depends on:** Slice 4

---

## Prompt

```text
Implement **Slice 5 — Scheduled runs** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-5-scheduled-runs.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/be-java.md`, then the Slice 5 section of the master plan.
**`/be-java`**, plus `data-postgresql` / `data-migrations` for the table.

**What this slice is:** internal jobs are scheduled — the outbox, report generation, case health,
fact refresh — but a regression cycle cannot be. Add a saved schedule that creates a run.

**This slice owns `V69`.** A `run_schedule` table: `company_id` **first in every index** per the
tenancy rule, a project reference, the scope request (reuse the shape `V61__test_run_scope_request.sql`
already stores on `test_run` — do not invent a second scope shape), a cron expression, an enabled
flag, and last-fired.

Then a `RunScheduleService` and controller in `execution`, a `@Scheduled` poller mirroring
`OutboxScheduler`'s shape, and a settings screen under the project.

**Depends on Slice 4:** a scheduled run with nobody assigned to it is a dead run. Land bulk
assignment first.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation caches in this slice's own commit.
```

---

## The three traps this slice turns on

**1. The poller runs outside a web request, so context is your job.**
`CompanyContext` is **deliberately not** `InheritableThreadLocal`, and the task decorator clears it
on every task so a handler that forgets fails closed. Open context per schedule row from that row's
own `company_id`, inside the scope guard (`try (var ignored = CompanyContext.open(id))`) — never a
setter. `AsyncCompanyContextTest` covers this family; add to it.

**2. Cron parsing belongs in exactly one place.** Two parsers disagreeing about what `0 0 * * 1`
means is a class of bug that only shows up on the week it matters.

**3. A schedule whose project was archived must not fire.** Projects gained archiving in §240.
Decide the behaviour — skip, or disable the schedule — and **record the decision with its reason**,
the way this repo's plans do. Silently firing into an archived project is the failure mode.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote backend ./gradlew build`.
- **Acceptance gate: a test proving a fire in company A cannot see company B's schedules.** This is
  a scheduled path, so RLS is the backstop and the context guard is the actual gate — test the guard.
- **`SchemaConventionTest` will fail the build** if the new table misses `company_id` or its RLS
  policy. That is the check working; add the policy, do not add the table to the ERD §0.1 exception
  list. Adding to that list is a decision, not a fix.
- **`out-of-order` is unset** — if this slice is skipped, leave `V69` unused.
- Verification rows into both queues; run nothing (§65).
