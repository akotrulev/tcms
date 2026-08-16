# §240 — The buffer that was somebody else's, the plan that could not leave, and the numbers nobody could date

**Date:** 2026-08-16 · **Branch:** `master` · **Three phases, all shipped** ·
**Audit sections D (D1–D3) and E (E1–E2)** of
`docs/audits/2026-08-15-flow-review-and-improvements.md` — the two P2 groups left after the C run
closed at §239.

**Three migrations: `V64`, `V65`, `V66`.** No permission seeded and none moved; the catalogue stays
at 55. Two of the five items reverse a decision this repository shipped deliberately and pinned
with a test — which is the shape worth reading before the rest.

Two decisions were the user's, taken before any code was written: **D2 is an orthogonal flag rather
than a fourth status**, and **D1 is both halves** (the server re-key *and* the create-mode buffer)
rather than either alone.

---

## Phase 1 — D1: the buffer is yours, and the New Case form stops losing everything

### What §128 decided, and why it was wrong

§128 built the autosave buffer on `test_case_draft`, whose primary key `V1` made `case_id` alone.
That slice did not treat the 1:1 key as an accident. It stated the cost in four places — the
entity's header, the service's, the contract's operation description, and a test called
`anotherPersonsSaveClearsIt` whose comment reads:

> *Pinning it here means a later slice that makes the buffer per-person has to change a test that
> says what the old shape was, rather than silently changing behaviour.*

**This is that slice, and the test was right to exist.** What it pinned turns out to be a defect
rather than a shape: a colleague's *save* deleted your unsaved draft, and a colleague's *keystroke*
overwrote it. §128's answer was disclosure — serve `updatedBy` so a returning editor is told whose
words they are being offered before adopting them — and the argument for that answer is sound
*given the key*. `V64` changes the key.

The question §128 raised against the re-key — *"which of the four drafts do I open"* — **does not
arise**, and that is the finding. A reader opens the draft keyed to themselves. There is one
candidate and never four; the editor never had to ask.

### `updatedBy` leaves the contract with the hazard it disclosed

Under `(case_id, user_id)` the field can only ever answer *"you"*. Keeping it would be a field that
asks a returning editor to weigh something that cannot happen — the stale-prose shape this
repository has paid for repeatedly (§152's family, and C11's whole slice). So it is removed from
`CaseDraft`, from `CaseDraftView`, and from the banner, which now reads **"Your unsaved changes
from …"**.

What survives is `stale`, which was doing the work all along: a colleague's save no longer deletes
your draft, it **makes it stale**, and that was already computed server-side, already advisory and
already on screen.

### The migration deletes exactly one thing, and says so

`updated_by` is nullable in `V1` and becomes half the primary key, so a row naming nobody cannot be
carried across — and is unreachable by construction afterwards, since the read looks a draft up *by*
the caller's id. `V64` deletes those rows and argues it at length rather than leaving a `WHERE`
clause to be discovered. Its blast radius is bounded: no path in the product has ever written a null
there, `CaseDraftService` having always passed the authenticated subject.

The column is **renamed** `user_id` rather than left as `updated_by`, because it stopped being an
audit stamp: it is the identity the read is keyed on.

### The create-mode buffer is the browser's, and that is a decision

`case_id` is a foreign key to `test_case`, so a case being created has nothing to key a *server* row
on. `V64` changes nothing about that — **what create mode was missing is a case id, not an owner** —
so D1's second half is `frontend/src/features/cases/useNewCaseDraft.ts`, a `localStorage` buffer.
Three reasons it is the right shape rather than a stopgap:

- **Nobody else can ever be shown it.** The server holds the edit-mode buffer because a case is a
  shared object; a case that does not exist yet is shared with nobody, so a round trip would buy
  durability across devices and nothing else.
- **A server-side new-case draft is a different table with a different lifetime**, and its two
  questions — what expires an abandoned one, and who may read it — are a decision with a slice
  behind it. Neither has to be answered to stop the form losing a paragraph to a closed tab.
- **What it costs is stated:** per browser profile. Typing on a laptop and returning on a phone
  offers nothing. That is the ordinary bargain of an unsent form and is strictly better than the
  nothing that was there before.

**One key per project.** A single `tcms.newCaseDraft` would offer another client's half-written case
the next time somebody opened New Case — the isolation boundary this product is built around,
breached in the one place no server check can see it, because nothing was ever sent to a server. A
test asserts it.

The two buffers share `useCaseAutosave`, the restore banner, and `toCasePayload`/`fromCasePayload`.
That is §128's own argument about the mapping, one level up: a second restore flow for create mode
is how a field wired into one buffer and forgotten in the other comes back empty.

---

## Phase 2 — D2: a plan can be put away, and that is not a fourth status

### The hole `V32` left, restated precisely

Two correct decisions produced it. `V32` (§97, M7) removed `CLOSED` from the plan ladder because it
described a plan **being run**, which is `test_run`'s business. `PlanDeleteService` refuses to delete
a plan that runs were executed against, because those runs would be left naming a plan the API 404s
for. Together: the plan for last spring's release stays `APPROVED` at the top of the working list
for ever, and the only way out is a delete the server is right to refuse.

### Why a column and not a fourth `PlanStatus`

The obvious spelling is wrong twice. It re-opens §97's conflation — and this act is not even about
execution, since a plan can be archived while a run against it is open and a plan nobody ran can be
archived the day it is written. And it **destroys information every time it is set**: an approved
plan is the one you most want to archive when the release ships, and archiving must not unsay the
approval.

### It is a VISIBILITY act, and the restraint is the design

An archived plan still reads, edits, approves, clones, **runs** and reports; every gate that applied
before applies unchanged. The `DELETE`'s 409 is untouched — archiving is what to do *instead of*
deleting such a plan, not a way to get that delete through, and a test asserts exactly that. A flag
that also froze the plan would be `CLOSED` under another name.

`plan:edit`, not `plan:delete`: it destroys nothing and the same call reverses it. Charging the
delete key would put a reversible tidying act behind the product's most destructive plan permission.

### The shape is `PUT /projects/{projectId}/archive`'s, copied deliberately

That endpoint has done this one level up since M2, so a second design for one act would be two
answers to one question. Following it gave: one endpoint naming the **target state** (idempotent by
construction), a **timestamp** in the column, a **boolean** on the wire, and **no `archived_by`** —
the actor lives in `audit_event` as `plan.archived` / `plan.unarchived`.

**The first draft had that column, and three convention rules caught it between them**, which is
worth recording because no single one of them would have:

| Rule | What it said |
|---|---|
| `ArchitectureRulesTest.everyMappedColumnHasAReader` | `TestPlan.archivedBy` is written and read by nothing |
| `ContractConventionTest` | `PlanArchivedInput` is a schema paired with no record |
| `SchemaCommentTest` | a column comment named `archived_by`, an identifier the database does not declare |

The second is what sent me to `ProjectArchiveRequest`, and the precedent answered the first.

### `?archived=true` widens the list; it does not filter to the archive

Somebody ticking "show archived" is asking which plans exist, and answering with the archive alone
would make them read two screens. The SPA keeps the toggle **in the URL** — a lead who found last
spring's plan can send somebody the link — marks the row **beside the name** rather than in the
Status column (putting it there would read as a fourth status, the conflation the column exists to
avoid), and offers Archive/Restore with **no confirmation**, because a confirmation on a reversible
act teaches people to dismiss confirmations.

---

## Phase 3 — E1, E2 and D3

### E1 — three surfaces answer "pass rate" and only one of them is a projection

The audit's finding, in its own words: *"run summary (live), plan statistics (live, policy echoed),
`aggregate_execution` (≤15 min stale, no 'not run' rows) … the fact-based report should carry its
projection watermark the way plan statistics already echoes its aggregation policy."*

Every report header now carries a **Data freshness** line, last of the provenance lines because it
is the one that qualifies every number under it:

- *Live — computed directly from this project's records as at 2026-08-16 21:04:11 UTC.*
- *As of 2026-08-16 21:04:09 UTC — computed from the execution fact projection, which was refreshed
  at that instant, immediately before this report was produced.*

**Neither half of that sentence can be written by one side**, which is the design. *Where* the
numbers come from is the report type's own property — `ReportComputation.freshness()`, defaulting to
`LIVE`, so a future fact-based report cannot inherit a claim that its numbers are current — and the
*instant* is the job's watermark, which only the job holds. `ReportHeaders` composes them.
`ReportHeader`'s own rule about the aggregation policy is the precedent: an item that only the report
can answer stays with the report.

The second clause of the projected sentence is the part a reader cannot work out for themselves.
`ReportJobRunner` refreshes `execution_fact` **inside the computing transaction, immediately before
computing**, so a *generated* report is exact while the same table read outside a job is up to a
scheduler tick behind — and two documents printing the same percentage are otherwise
indistinguishable.

The SPA is the fourth renderer of that block and carries the line too (`lib/reportDocument.ts`,
whose header already says it is the copy that could most easily drift).
`ReportRenderingTest.nothingInTheHeaderIsSilentlyDropped` covered the new field the moment it
existed, which is what that computed check is for — with **one snag worth knowing**: its premise is
that every header value survives verbatim in all three formats, so its fixture value carries no
apostrophe. HTML escapes one.

### E2 — the last of the eleven creates to take `Idempotency-Key`

`V66`. What makes this one different from the other ten is that **the client half shipped first and
did nothing**: §205's conformance sweep found `packages/mcp`'s `tcms_report.generate` declaring and
sending a key `ReportController` never read, so every MCP retry queued a second job. The honest fix
then was to stop sending it, with the gap recorded in `writeConformance.test.ts` as a decision. That
entry is gone now and the key is back on.

B4 (§232) deliberately left this one and was right to — *"a duplicated report is two files"* — but a
report job is not a row: it refreshes `execution_fact` for the **whole company**, computes, renders
and writes **two** artifacts. A duplicate is a second full pass, and the caller most likely to retry
(CI, or an MCP client) is the one with no job list on screen to notice.

A replay answers **200 with the job it already queued**, because `202` here means *work was started*.
The `Location` is the same either way — what the caller does next is identical.

**The SPA still sends no key, and now says why that is a choice.** A press of Generate *is* the
intent, so a key minted per press would deduplicate nothing (TanStack does not retry a mutation)
while a key minted per mount would make the second press silently hand back the first report.
`IdempotencyKeyCapTest` goes 9 → 10, and its header records what is new at the tenth: every other
entry prevents a second **row**; this one prevents a second **pass**.

### D3 — the deliverable is the note

The row asks for the pairing to be recorded *now* so the two cannot land separately. `TestRun`'s
no-`@Version` argument (§155) rests entirely on one premise — **there is no `PATCH /runs/{runId}`** —
so no field is written from a value a client read. An edit endpoint ends that premise, and the
failure between the two slices is silent: two people renaming a run from forms opened minutes apart,
the later write winning, with a `lock_version` column reading `0` for ever that looks like a guard.

So `TestRun`'s header and the contract's `GET /runs/{runId}` both now say: a slice that adds run edit
**maps the version, serves `lockVersion` and takes an `If-Match` in that same slice** — and keeps the
two result writers off the counter, which is what `startIfNotStarted`'s predicate and
`@DynamicUpdate` already do and why they must survive the change rather than be replaced by the
annotation.

---

## Gates

Every gate on `shanks`, per `CLAUDE.md`.

| Phase | Backend | Frontend | Packages |
|---|---|---|---|
| 1 (D1) | **3025 / 0 / 0** | **1544 / 115** | 336 |
| 2 (D2) | **3035 / 0 / 0** | **1553 / 116** | 336 |
| 3 (E1 + E2 + D3) | **3038 / 0 / 0** | **1553 / 116** | 336 |

Arrival baseline was §239's **3024 / 1540 / 115**. Both `schema` copies regenerated on every
contract change and byte-identical. Flyway head is **`V66`**; `V67` is the next free number.

## Verification — built and unverified

Rows in both queues, none run (§65): `§240 DR-*` (the per-person buffer and the New Case draft),
`§240 AR-*` (plan archiving) and `§240 RF-*` (report freshness and the generate replay).

**Two rows are worth doing first if only two are ever done.** `§240 DR-3` is the one no automated
gate can reach and it is not close: the point of a per-person buffer is that a *colleague's save*
leaves your work alone, which needs two accounts and two browsers. And `§240 RF-2` is the whole of
E1 — two reports open side by side, printing two different freshness sentences — because a
single report reads plausibly either way.
