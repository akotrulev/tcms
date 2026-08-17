# Next slice — T9, the org chart's disclosure boundary, over real HTTP

**Debt from `docs/plans/2026-08-15-org-chart.md`.** Eight code lanes, the record, and T5's
query-plan review have all landed; T9 and T10 are what is left. This file is the prompt for T9,
which §241 and plan §15 both name as the one that matters most.

**Why it is worth running rather than closing.** The whole feature is one predicate — `reach.maySee`
on the project axis — and the mutation queue's `§241 OC-1` says what breaking it looks like: the
response gets *bigger*, every screen still renders, and **nothing looks wrong**. Every gate this
repository has is blind to that. The backend suite drives the service, not the wire; the SPA tests
drive components that decline to render fields they were sent. A field the server sends and the
client merely does not display is invisible to both, and it is the exact defect this task exists to
catch.

---

## Already done — do not redo any of it

| | State |
|---|---|
| Org chart, all eight code lanes | ☑ `3f100b1` … `bd420eb`, **on `master`** — not on `worktree-org-chart-plan`, still at `5149e58` |
| Handoff §241, index row, both caches, both queues | ☑ T11 `64f9814` |
| T5 query-plan review | ☑ `0b2191e` — the finding is plan §6.1 |
| T5's index | ☑ `V67` `5da8a3b` — plain `CREATE INDEX`, §6.1's `CONCURRENTLY` half struck through |
| **T9 disclosure-boundary pass** | ☐ **this slice** |
| T10 manual test-case suite | ☐ not run — the natural follow-on, and document-only |

**No code is expected to change.** T9 reports; it does not fix. If it finds something real, the fix
is its own slice with its own gate.

---

## The prompt

```
Repo: ~/RiderProjects/tcms
Branch: whatever is checked out — check with `git rev-parse --abbrev-ref HEAD` and do NOT
create a new one.

Read CLAUDE.md FIRST — it is binding. Then docs/agent-context/be-java.md (trust its BODY
over its `Baseline commit:` header, and read the git delta since that header) — §241 and
§241-A are the org chart's entries. Then docs/plans/2026-08-15-org-chart.md **§4.4**, which
is the redaction table and IS the specification for this task, row by row, plus §15.

Use the `qa-security` agent. This is authorized security testing of this project's own
feature, against a locally provisioned instance you bring up yourself.

## What is already done

The org chart is COMPLETE: eight code lanes plus the record landed as 3f100b1..64f9814,
T5's review as 0b2191e, and T5's index as V67 (5da8a3b). Do not re-implement any of it.
T10 is NOT part of this slice.

## The task — T9

Prove the disclosure boundary IS the boundary, over real HTTP, asserting on the RESPONSE
BODY.

### The instance, and be honest that this is most of the work

There is no seeded multi-project multi-member fixture anywhere in this repo, so you build
one. `docker compose up --build` at the repo root (needs `.env` — `cp .env.example .env`),
then `docker compose run --rm backend provision-company …` per CLAUDE.md. Keep compose
LOCAL rather than on shanks: the wrapper's own note says so, because a build-tree compose
derives the project name `tcms` and collides with the stack already running there.

`provision-company` gives you a company and ONE admin, via an invitation link it prints.
Everything else is yours to create over the API as that admin — the endpoints exist:
POST /projects, POST /users (invite), POST /role-assignments, GET /org-chart. The member
redeems their own invitation and signs in through POST /auth/login; **do not try to probe
as the member using an API token the admin issued.** A token caps ITS OWNER's permissions
(CLAUDE.md's permission section), so an admin-issued token is an admin's reach narrowed,
not the member's, and probing with one measures the wrong principal entirely.

Minimum shape: one company, **at least four projects**, and a member who holds
`project:read` on exactly ONE of them. Four rather than two so a missing filter is
visible as a count and not as a coin flip. Also stand up a SECOND company with its own
projects and people, because one of the assertions below is cross-tenant and cannot be
made against a single-company instance.

State in the report exactly what you provisioned, as commands that can be re-run. A
finding nobody can reproduce is an anecdote.

### The assertions

As the member holding `project:read` on one project of four, against GET /api/v1/org-chart:

1. **No other project's `id`, `name`, `key` — or COUNT — reaches the wire.** The count is
   not an afterthought: §4.3 Option D rejected it explicitly because a count is a name-free
   existence claim about the company's portfolio. Grep the raw response text for the other
   three projects' ids and keys, not just the parsed columns.
2. **No cell for a project the reader cannot open**, including no placeholder and no null
   entry keyed by an invisible project id.
3. **`lastLoginAt` is null** for every person (the member holds no `company:members`).
4. **No grant ids anywhere.** Revocability belongs to GET /role-assignments and §4.4 says
   it stays there.
5. **No SYSTEM-scope / Instance Admin fact** — §4.4's last row, and `instance_role_assignment`
   has no company_id at all.
6. **No cross-company leakage of any kind** — the second company's people, teams and
   projects, against both companies' members.
7. **BOLA/IDOR-shaped probing of the path itself**: parameter and header tampering, any
   company/project/user id you can inject, and whatever the endpoint accepts that it should
   not.

Take every §4.4 row as its own named check, including the rows whose answer is "unredacted"
— a table read only for its redactions is a table half-read, and a field wrongly withheld is
also a defect.

### The trap this task exists inside

ASSERT ON THE JSON, NEVER ON THE UI. §241's own most valuable-looking test — the §8.3
agreement arm — was measured to stay GREEN with the disclosure filter removed, because a
leaked cell still names somebody who legitimately holds that role. A test that asks about
who is NAMED passes straight through a rule about who is ASKING. Do not rebuild that
mistake at the HTTP layer: the question is always "what did the server send to THIS
caller", never "is what it sent internally consistent".

## What NOT to do

- **Fix nothing.** Report. If you find something real, say plainly how severe it is and
  what the fix would touch; the fix is its own slice with its own gate.
- Do NOT run the §65 queues (browser passes, backend mutation checks) — deferred and
  batched, and "deferred means ALL of them". `§241 OC-1` in particular is the mutation form
  of this very boundary and is NOT yours to run.
- Do NOT weaken an assertion because the fixture was awkward to build. If you could not
  provision something, the row's outcome is "not driven, and here is what is missing" —
  that is a legitimate outcome in this repo and a silent skip is not.
- Do NOT touch T10.

## The record

T11 already ran, and handoff index rows are NEVER renumbered — so:

- **If T9 finds anything**, it gets its own handoff section at the current high-water mark
  (§241 was the top on 2026-08-17 — RE-MEASURE, `ls docs/handoff | sort -n | tail`), plus
  its one index row in docs/HANDOFF.md, and the security findings themselves saved under
  `docs/audits/` per the agent's own convention.
- **If it finds nothing**, that is a result and not a non-event: amend §241's sentence about
  T9 in place, struck through with "→ superseded", the way the T5 amendment on 2026-08-17
  did it. Do not write a section to say nothing happened.

Either way, record what was provisioned and what was probed, so the pass is re-runnable.

## Gates and commit

There is no build gate here — this slice changes no code. The gate is that the report's
claims are reproducible from the provisioning commands it states.

Stage ONLY the files this slice touches (`git add <paths>`, NEVER `git add -A`) — two other
worktrees are live, and a whole-file add in a shared plan file has already swept another
lane's in-flight edit into the wrong commit once in this project. Check `git status`
immediately before staging, not merely before editing.

Commit at the end, and push (CLAUDE.md's push-after-each-lane rule).

If you find a real problem with this task as specified, say so in a sentence or two, then
deliver it under a stated assumption. Do not silently rescope.
```

---

## Notes

- **The whole org chart is "built and unverified" until this runs**, in the working agreement's own
  words, and so is every row §11 put in the two §65 queues. T9 does not discharge those rows — it is
  a different observer asking a different question — but it is the one that looks at the wire.
- **T10 is the natural follow-on** and is document-only: viewer tiers × `via` arms, the §9.4 standing
  line, the `OrgChartGaps` regions. Its most valuable case is the negative one — two colleagues
  opening the same chart and correctly seeing DIFFERENT matrices is the design, and the suite has to
  say so explicitly or somebody files it as a bug.
- **The `tierClaims.test.ts` "gated on `<key>`" rule is NOT ready to be a task, and this was
  measured rather than assumed (2026-08-17).** §241 names it as the instrument gap that would have
  caught T1's false `CurrentUser.teams` prose. The population is real — **21 claims of the exact
  `gated on \`key\`` shape** (14 in `frontend/src`, 7 in `docs/specs`, 0 in `packages` and
  `backend/src`), and ~219 sentences in the looser family. What is missing is the other side of the
  comparison:
  - **There is no machine-readable statement anywhere of which key gates which endpoint.** No
    `x-permission` or equivalent in `openapi.yaml`; no uniform annotation in the backend. Gates are
    service logic — `readsCompany()`, `reach.mayStaff`, and 137 `require(` call sites with no shared
    shape.
  - **A claims-must-agree consistency rule would not have caught the T1 defect either.** The false
    sentence lived in `CurrentUser.teams`; the `GET /teams` operation it was about carries **no
    description at all**, so there was no second claim to disagree with.
  - So the slice that closes this class is *"declare the gate, pin the declaration against the real
    one, then check the prose against the declaration"* — three parts, the middle one load-bearing
    and unspecified. **That is a design question and wants brainstorming before it wants a task
    list.** Checking only that the named key exists in the catalogue is cheap and would not have
    caught T1's defect, which named a real key that was simply the wrong one.
- **Q4's two deferred follow-ups** from plan §14 remain deferred with owners: retire `roleAccess.ts`
  in favour of the server's effective-role answer, and §6k's live-workload figures.
