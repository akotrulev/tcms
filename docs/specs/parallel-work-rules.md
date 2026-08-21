# Parallel work rules

**Work is sequential.** Parallel lanes were tried three times (handoff §37, §41, §45) and
re-authorised once more on 2026-08-02 with a 4-agent cap, then reversed the same day. See
`docs/plans/2026-07-31-parallel-execution-rework.md` §0 for the incident that ended it: two agents in
one tree produced **three silent losses of committed work** — a rebase, an unrelated commit reverting
a doc as collateral, and a stale re-application wiping a later change — with no conflict, no error
and a clean `git status` throughout.

**One subagent at a time — ask before a second.** No agent may have more than one subagent running
concurrently. If a task genuinely needs two or more in parallel, say why it is safe (disjoint files,
no shared build directory, no shared contract or migration numbering) and **get the user's explicit
permission first**. Absent that permission, run them one after another. This is the same failure
mode as the lanes above: concurrent writers in one tree lose committed work silently.

**If a second checkout is ever genuinely needed** — the concurrency-worktree exception to "do not
branch per task" — these still bind, and are still not sufficient:

- **One contract holder.** `openapi.yaml` and `npm run gen:api` belong to exactly one lane;
  `schema.d.ts` is generated and two regenerations do not merge. Resolve the *contract*, then
  regenerate — never hand-merge the output.
- **Pre-assign Flyway versions, renumber at integration in landing order.** `out-of-order` is unset,
  so a `V13` landing after a `V14` is **refused on any already-migrated database**. Testcontainers
  cannot see this — it migrates a fresh container every time. Two lanes each adding a `V20__*.sql`
  produce two *filenames* and no conflict at all.
- **Pre-assign the fixture UUID prefix space.** Hand-managed, no check behind it, collided five
  times (§19 … §62). Two lanes take the same prefix and merge clean; `project_pkey` fails at runtime.
- **One writer for `docs/agent-context/*.md`.** Two agents of the same type write the same file.

A second stack for a concurrent browser pass is `cp .env.lane-b.example .env.lane-b`,
`docker compose --env-file .env.lane-b up`, and `npm run dev:lane-b` (6432 / 9080 / 6173).
