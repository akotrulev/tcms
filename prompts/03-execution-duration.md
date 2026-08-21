# Slice 3 — Execution duration

**Lane:** backend + contract + frontend · **Migration: `V68`** · **Depends on:** nothing

Half a session. Pair it with Slice 4 if you have room — they touch the same write path.

---

## Prompt

```text
Implement **Slice 3 — Execution duration** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-3-execution-duration.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/be-java.md`, then the Slice 3 section of the master plan. Use
**`/be-java`** plus the `be-java-database-code` skill for the JPA change and
`data-postgresql` / `data-migrations` for the migration.

**What this slice is:** there is no elapsed or duration field anywhere on an execution, so "how
long did this cycle take last time" — the question a test lead plans the next cycle from — cannot
be answered. Add one column and surface it.

**This slice owns `V68`.** The head is `V67`
(`V67__team_membership_answers_which_teams_a_person_is_in.sql`). Add
`duration_seconds INTEGER` to `test_execution` (created at
`backend/src/main/resources/db/migration/V1__initial_schema.sql:831`), **nullable** — a result
recorded without a stopwatch is ordinary, not an error. Write the migration header in this repo's
house style: say what the column is *for*, not just what it is.

Then: the execution entity, input and view; `openapi.yaml`'s result-recording request body and
execution response; **both** `schema.d.ts` copies; the result form control; and the run rollup so
a run can total it.

**Handoff:** next free § from `docs/HANDOFF.md` — new file plus one index row. Orientation caches
in this slice's own commit.
```

---

## The trap this slice turns on

**Two other clients write this endpoint, and neither is the SPA.** The MCP's `tcms_record_result`
and the CLI's `result record` both post results. **Keep the field optional** — making it required
breaks both surfaces, and the failure on the MCP side has no error a user sees, because a model
reads a tool's schema before deciding whether to call it.

`packages/*/src/test/writeConformance.test.ts` drives every write arm and asserts both halves of the
header contract; run the packages gate, not just the backend one.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote backend ./gradlew build`,
  `~/bin/tcms-remote packages npm run build`, `~/bin/tcms-remote frontend npm run test:run`.
- **`npm run build` in `packages/`, never `--workspaces`** — the workspace order matters and
  alphabetical typechecks `cli` against an unemitted `client`.
- **Regenerate both `schema.d.ts` copies** and confirm they are byte-identical.
- **Give the new schema field a `required` entry** if it is required, and mark it `nullable: true`
  if it is not. Do not leave it merely absent.
- **`out-of-order` is unset.** If this slice is skipped, leave `V68` unused rather than renumbering
  a later migration into the gap.
- Verification rows into both queues; run nothing (§65).
