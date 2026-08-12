# Mission Control and TCMS — a development tool, not a feature

**Written:** 2026-08-12 · **Status: planned, nothing implemented.** No code, no config, no registry
entry has been written for this. It is a decision record plus the setup that would follow from it.

Mission Control (`git@github.com:akotrulev/mission-control.git`) is plan-driven agent orchestration
over the SkillsLibrary: prose in, task graph out, a phased Kanban board, per-run git worktrees, three
gates before a task reads done, a budget ceiling, SQLite persistence, an `/api/v1` contract and an
MCP server. The question put to this document was whether it can be *integrated into* TCMS or
*ported into* it.

**The answer is neither.** It is adopted as a tool that is pointed at this repository. That is a
smaller claim than "integration" and it is the only one the two codebases support.

---

## 0. Why porting it in is refused

Recorded here so the next reader does not re-derive it. Three independent reasons, any one of which
is sufficient.

### 0.1 It is not this product

TCMS is a test case management system: it **ingests** results, it is **not** a test runner, and it is
**not** a defect tracker — three exclusions the high-level plan states deliberately. A Mission
Control run is neither a test execution nor a defect; there is no row in the ERD it becomes, and no
existing screen it belongs on. Adding it means adding a fourth product to a product that has spent
its whole life saying no to the second and third.

### 0.2 It cannot satisfy the tenancy invariant

Every company-owned table carries `company_id UUID NOT NULL`, leading every composite index, under a
`FORCE ROW LEVEL SECURITY` policy, with `SchemaConventionTest` failing the build for any new table
not on the ERD §0.1 exception list. Mission Control's state is `.data/mission-control.db` — SQLite,
single-user, no tenancy concept anywhere in its model: runs, phases, tasks, logs, projects. Making it
comply is not a migration, it is a rewrite of its data layer against a database it does not use.

And the model underneath is wrong for us in a way the schema would only paper over: a Mission Control
project is *an absolute filesystem path on the machine running the server*. There is no honest
mapping from that to a TCMS Project inside a Company.

### 0.3 It does not fit the module graph or the stack count

The backend is a Spring Modulith of nine modules plus `storage`, cross-module FKs allowed only toward
`tenancy`/`identity`/`project`/`storage`, everything else crossing by domain events under ArchUnit
enforcement. Agent orchestration is none of those and is not a foundation module either — §125 set
the bar for the fourth one, and "a fifth foundation module is a decision, not an import."

Ported *as* a Next.js app instead, it is a **fourth stack**. There are three (`backend/`,
`frontend/`, `packages/`) and nothing builds from the repo root; the fourth would arrive carrying
SQLite, `next start`, its own launchd plist, its own container image and its own auth story — into a
product whose entire security posture is one isolation boundary enforced in three layers.

A rewrite in Java is the same refusal wearing a different hat: it means reimplementing the Claude
Agent SDK dispatch loop, SSE streaming, worktree management and a Kanban board, to own a concern that
is not ours.

### 0.4 The one integration that is real

Register both MCP servers with Claude Code side by side — Mission Control's `mission-control` (six
tools, a client of its own `/api/v1` and nothing else) and this repo's `packages/mcp` (§140). Two
servers, different jobs, no coupling: one queues and steers development runs, the other exposes the
TCMS API to an agent under a capped capability set. This is additive client configuration. It is not
a change to either product and it is the whole of the "integration" that survives scrutiny.

---

## 1. What adoption actually buys

Stated honestly, because the frictions in §2 are priced against it.

**Worktree isolation is a direct answer to a failure this repo has already paid for three times.**
`docs/plans/2026-07-31-parallel-execution-rework.md` §0 records the incident that ended parallel
lanes: two agents in one tree, **three silent losses of committed work** — a rebase, an unrelated
commit reverting a doc as collateral, a stale re-application wiping a later change — with no
conflict, no error and a clean `git status` throughout. Every one of those is a *shared working tree*
failure. Mission Control cuts `.data/worktrees/<runId>` on branch `mc/<runId>` per run, based on the
commit the current branch points at, and the developer's checkout — uncommitted changes included — is
untouched start to finish.

This does **not** reopen parallel lanes. It removes one of the mechanisms behind the ban; §2.1 and
§2.2 are the ones it does not touch. CLAUDE.md's "work is sequential" stands until a document
supersedes it explicitly, and this is not that document.

Three smaller alignments:

- The implementer contract — implement only, unit tests required, **no live/E2E tests, no starting
  servers, no manual verification** — is the same policy as §65's deferred and batched browser
  passes, arrived at independently.
- Per-project lessons written back into every later implementer prompt is a mechanised version of
  what `docs/agent-context/be-java.md` and `fe-react.md` do by hand.
- The budget ceiling ($25 default, everything counting toward it including retries and the memory
  pass) makes the cost of an unattended run bounded and visible, which it currently is not.

---

## 2. The frictions, in the order they would bite

Each is a real property of this repository, not a hypothetical. None is a blocker on its own; all of
them constrain the setup in §3.

### 2.1 One `backend/build/` and one Gradle daemon

Mission Control's second line of defence against a parallel-safe phase going wrong is **declared file
overlap**: two tasks naming the same target files never run concurrently. That check sees source
files. Two backend tasks in one worktree both run `./gradlew`, declare no overlapping sources, and
collide on build output and the daemon anyway. This is the same constraint that makes a second
checkout necessary for a second backend lane in CLAUDE.md's concurrency-worktree exception.

**Consequence:** any phase whose tasks touch `backend/` is sequential-only, regardless of what the
planner concludes. This must be stated in the plan prose, because the planner cannot derive it.

### 2.2 Flyway numbering and fixture UUID prefixes are invisible to the overlap check

`out-of-order` is unset, so a `V13` landing after a `V14` is refused on any already-migrated
database — and Testcontainers cannot see it, because it migrates a fresh container every time. Two
tasks each adding a `V20__*.sql` produce **two different filenames**: no declared overlap, no
conflict, no error, and a green suite. The hand-managed fixture UUID prefix space is the same shape
and has collided five times (§19 … §62).

**Consequence:** Flyway versions and UUID prefixes are pre-assigned *in the plan text handed to the
planner*, per task, before Start. There is no mechanism in Mission Control that will catch this and
none is coming.

### 2.3 The verify gate is expensive and there are three of them

Mission Control models one verify command per project. TCMS has three stacks and no root build:

| Stack | Gate | Cost |
|---|---|---|
| `backend/` | `./gradlew build` | Docker + a real Postgres 17 per test via Testcontainers, ~327 s |
| `frontend/` | `npm run build` + `npm run lint` + `npm run test:run` | 1203 tests |
| `packages/` | `npm run build` (client → cli → mcp, **in that order**) + lint + `test:run` | ~1 s of tests |

The backend number is why `ci.yml` runs `-x test` at all. Paid per task and again per retry — and a
rejected task is re-dispatched up to twice — it is the dominant cost of a run. Mission Control's
remote-build option (plan 008, phases 1–2 done) is directly relevant: `shanks` is already a
registered Linux runner for `tests.yml` and is the obvious host, which would also make the verify
step a *Linux* observer rather than merely a cheaper one.

### 2.4 Mutation testing is required by the contract and is not configured here

The implementer contract expects Stryker/PIT and instructs the agent to say so explicitly rather than
skip silently when it cannot. Neither exists in this repo, so every card will carry that admission.
That is the tool behaving correctly; it is still noise on every task until either PIT is configured
for `backend/` (a decision nobody has made) or the expectation is relaxed in the plan prose.

### 2.5 The HANDOFF export writes the shape CLAUDE.md forbids

Mission Control exports a `HANDOFF.md` at the end of a run. This repo's rule is the opposite:
`docs/HANDOFF.md` is an **index**, each section lives in `docs/handoff/NN-slug.md` numbered by its §
number, and *finishing a slice means adding a file and one index row — never appending to
`HANDOFF.md`*. Left alone, the export corrupts the index and every `§N` reference that resolves by
filename prefix.

**Consequence:** treat the export as raw material written outside the repo, and have the final task
of each run author the `docs/handoff/NN-slug.md` file and its index row by hand.

### 2.6 Two smaller ones

- **The contract holder rule.** `openapi.yaml` and `npm run gen:api` belong to exactly one lane, and
  `schema.d.ts` exists in two byte-identical copies that a test compares. Any run touching the
  contract has exactly one task that regenerates both; the planner will not know this.
- **`MISSION_CONTROL_DATA_DIR` defaults inside the Mission Control checkout**, where `git clean -xdf`
  deletes every run. Its own README says to move it. Worth honouring before the first run, not after.

---

## 3. The setup this implies

Not executed. Written so that executing it later is transcription rather than redesign.

**Phase A — stand it up, no TCMS involved.** Clone, `npm install`, point
`MISSION_CONTROL_LIBRARY_ROOT` at the SkillsLibrary checkout, set `MISSION_CONTROL_DATA_DIR` outside
the checkout (`~/.mission-control`), confirm the header shows a non-zero catalog count, and run
`npm run ci` green. Install the launchd agent so it binds loopback only — `npm run dev` and
`npm run start` bind `0.0.0.0` with no authentication in front, and this machine is on the same LAN
as `shanks`.

**Phase B — register TCMS as a project, execution switch OFF.** Confirm it plans, read the task
graph, and start nothing. A planner pass is read-only and cannot modify the repo, so this is free and
it is the cheapest way to find out whether the planner understands a three-stack repo with no root
build. Expect to iterate on the plan prose here rather than on any code.

**Phase C — the standing preamble.** A reusable block prepended to every TCMS plan handed to the
planner, encoding what §2 established and the planner cannot derive:

1. Read `CLAUDE.md`, then `docs/agent-context/be-java.md` or `fe-react.md`, before anything else.
2. Any phase touching `backend/` is sequential. Do not mark it parallel-safe.
3. Flyway versions and fixture UUID prefixes are pre-assigned below, per task. Do not choose your
   own.
4. Exactly one task may touch `openapi.yaml`; it regenerates **both** copies of `schema.d.ts`.
5. Verify commands, per stack, as §2.3.
6. No mutation tool is configured; say so once and move on.
7. Browser passes and backend mutation checks are deferred and batched (§65). A task appends its row
   to `docs/verification/*.md` and runs nothing.
8. The handoff is a new `docs/handoff/NN-slug.md` plus one index row. Never append to
   `docs/HANDOFF.md`.

**Phase D — one small real slice, backend excluded.** A frontend-only or `packages/`-only slice,
sequential, budget left at the default. Judge it on whether the diff on `mc/<runId>` is something you
would have committed, not on whether the board looked good.

**Phase E — only then, backend, and only then, remote verify.** Backend first with the local gate to
learn what it costs, then evaluate shipping the verify step to `shanks` over SSH. Note what that does
*not* buy: it is a build host, not the nightly lane, and `tests.yml` still observes the default
branch at 01:17 UTC. A green remote verify on `mc/<runId>` is a *second observer of a branch*, and
saying more than that is the shape §161 found.

**Phase F — the MCP registration of §0.4**, once there is a habit worth driving from a chat.

---

## 4. Open questions

1. **Does a Mission Control run count as a lane for the push rule?** CLAUDE.md says push after each
   lane. An `mc/<runId>` branch is committed work living in a worktree under `~/.mission-control`,
   which is exactly the "committed only in the local worktree" state §0's three losses came from —
   but it is also not a lane anybody is working. Leaning: push the branch, treat it as backed up and
   not observed.
2. **Who authors the handoff section — the last task, or a human afterwards?** §2.5 assumes the
   former. It may be the one part of a slice that should not be delegated.
3. **Is PIT worth configuring for `backend/` on its own merits**, independent of §2.4? The mutation
   requirement is a reason to ask the question, not an answer to it.
4. **Does the planner cope with three stacks and no root build**, or does it need the layout stated
   explicitly in the preamble? Phase B answers this and nothing else does.
