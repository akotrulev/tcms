# §110 — the live pass runs, and what a queue cannot tell you until somebody drives it

**Written 2026-08-07, from the commits and the evidence file, as decision round 3's #60.**
Two sittings, `9cccb2a` and `ccf9eea`, both 2026-08-06. **Part-driven and deliberately unfinished.**
Evidence: [`docs/verification/2026-08-06-live-pass-findings.md`](../verification/2026-08-06-live-pass-findings.md),
which is the primary record — this section is the reading of it, not a replacement for it.

> **Why this file was missing for a day, and why that mattered.** §110 is the only slice in the log
> with no section, which is why the numbering jumps 109 → 111. The pass itself is committed and its
> findings file is complete; what was absent was the *reading* — which findings are product defects,
> which are queue defects, and what the whole exercise says about §65. That reading has the shortest
> half-life of anything this repository produces (`docs/audits/2026-08-05-remaining-scope-assessment.md`
> §5.3), and it decays in the head of whoever drove the pass, not in the file. Written on the fifth
> day after, from the artifacts, it is thinner than it would have been on the first.

## What this was

§109 turned the browser queue into **one live pass of thirteen ordered lanes** against a real
backend — no mock lane, no mode to choose — and authored a fixture charter the pass builds *through
the product*. §110 is the first sitting of that pass, and the first time any of the ~360 queued rows
had been driven against the real server.

- **Sitting 1 (`9cccb2a`)** — all thirteen lanes touched, eleven driven to some depth. A fixture
  built from scratch through the real UI and API: one company (`v109`), two projects, ten flat
  suites, 29 cases, three conditions, five plans, four runs, one report, one full review cycle,
  three teams. **Roughly 60 rows confirmed against their exact worded expectations**; several dozen
  more recorded as *not driven* rather than silently skipped.
- **Sitting 2 (`ccf9eea`)** — the `F` lane alone, which sitting 1 had closed by declaring
  undriveable-as-attempted. **Six of nine rows driven, five to full confirmation, and no product
  defect in any of them.** That is the lane's whole point: the resilience claims that had been
  asserted everywhere and *seen* nowhere.

**Fourteen findings, F1–F14.** They do not all mean the same thing, and the split is the finding.

## The three kinds of finding, and why the split is the point

| Kind | Findings | What it says |
|---|---|---|
| **Product defect or gap** | F1, F5, F6, F7, F9, F11 | The product is wrong or has a hole. Six. |
| **The queue row is wrong; the product is right** | F2, F8 | The expectation encoded a database's history, not an invariant. |
| **Harness / environment / charter** | F3, F4, F10, F12, F13, F14 | The pass could not see what it came to see. Six. |

**Six of fourteen findings were about the pass, not the product.** That number is the honest cost of
a queue written months before it was driven, and it is why §109's re-derivation was worth doing and
still not sufficient — F2 landed **on a row §109 itself had just rewritten**.

### The six product findings

- **F1 — an invitation link opened while already signed in is silently swallowed.** `GET
  /accept-invitation?token=…` with a session in `localStorage` redirects to `/` and renders the
  projects of the session already held. No message, no prompt, no mention that a link was followed.
  The token survives (`consumed_at` null, membership still `INVITED`), so it is not destructive —
  it is *unreadable*. **This breaks the designed multi-company flow**, not an edge case: `app_user`
  is instance-level, one person legitimately belongs to several client companies, so "signed in to
  company A, invited to company B, click the link, see company A" is the ordinary path. The correct
  read is that nothing happened; the natural read is that the invitation is broken.
- **F5 — the `report:view` / `report:export` split cannot be expressed by any real deployment.**
  `/roles` is GET-only: six built-in roles are the entire vocabulary of a deployment, and every one
  that holds `report:view` holds `report:export` too. §6e designed the split as *"may read on screen,
  may not carry off"* and **no operator can grant one without the other**. This is §87 phase 6 from
  the other side, and the other side is the one that matters: §87 fixed its unsatisfiable gate
  assertions by minting two custom roles **in the Java test fixture**, which was right for the suite
  and means the gates are now asserted only against personas that cannot exist outside it. Two
  consequences: `L1-20` and `L2-11` are **undriveable**, not undriven; and §90's known defect (the
  on-screen report view costs `report:export`) currently has **no observable consequence and no way
  to validate a fix**.
- **F6 — there is no password reset anywhere in the product.** No API path (`openapi.yaml` mentions
  `password` twice: login, and the accept-invitation body), and no operator path — `IssueInvitationCommand`'s
  own header says it *"does nothing for a member who has already accepted; they need a password
  reset, which this command is not."* A password exists only in the head of whoever typed it. It
  **stopped the pass dead**: five personas redeemed in `A0-1`, five passwords not written down, and
  every two-account row unreachable. Worked around by writing a known BCrypt hash onto five accounts
  — recorded as a deviation, because it is one.
- **F7 — granting a role to a team leaves the teams query stale**, so the banner that exists to
  prevent surprise access is missing at the one moment it matters. The natural sequence is *grant to
  the team, then add people* — one screen, two clicks apart, which is exactly the stale window. The
  banner's own words are *"Anyone you add here gets it immediately"* and there is no second place
  that says so. One invalidation, on the teams key rather than the assignments list: the chip and
  the banner are both fed by `GET /teams`.
- **F9 — a suite's conditions can be read but never written, through any client.** `GET
  .../suites/{id}` serves a `conditions` array, the schema argues for the feature, `suite_condition`
  exists with its own RLS policy, and `case_condition_optout` FKs to it. **`SuiteInput` has three
  fields: `name`, `tagRules`, `caseIds`.** Sent anyway over the wire, `PATCH` returns 200 with
  `conditions: []` unchanged — the server drops the unknown field silently, so a client that guessed
  the field name would believe it worked. The read side, the comment, the DDL and the policy all
  describe a feature whose write path was never built. **This is the writerless-shape family again**
  (§116, §117, §120, §121, §122), one level up: not a table with no writer, but a *field*.
- **F11 — submitting a plan for review does not invalidate the review panel's query.** The status
  chip updates; the panel on the same page keeps reading *"This plan has never been submitted for
  review."* The `review` row is written at the same instant, so the write is right and the read is
  stale. **Same shape as F7**, on a different screen — which is what makes it a class rather than a
  bug: a mutation that changes what a *sibling panel* should show, without telling that panel's query.

### The two queue findings, and the shape they share

**F2** — `L0-7` asserted one `project_settings` row per project; a freshly provisioned project has
**zero**, by design, because `V23` backfilled the projects that existed when it ran and everything
downstream reads `.orElseGet(ProjectSettings::defaults)`. The assertion was true only on the database
§77 was written against.

**F8** — `L4-7` asked for an orphaned parent and a cycle, "twice over, so `psql` is the only way in".
Neither can be produced **including from `psql`**: `tcms_enforce_team_hierarchy` has been in `V2`
since M0, so the guard is three-deep and the corrupt state the row wanted to observe cannot exist in
this schema. The row was **retargeted rather than deleted**, because the question underneath it —
does the chart drop a row it cannot place? — is still fair; it just cannot be reached from the data.

Both are the same defect in the *observer*: **an expectation derived from the state of one instance
rather than from the code.** F2 is the worse of the two, because it reads a table and therefore
looks authoritative — `L0-2`…`L0-6` were caught by §109 precisely because they needed a *screen*.

### The four harness findings that made the `F` lane possible

Sitting 1's `F` lane produced nothing at all. Sitting 2 found out why, and the reasons are worth
more than the rows:

- **F12 — the `F` lane is undriveable in a background tab, and it fails by looking like a product
  defect.** `AbortSignal.timeout` is throttled far harder than `setTimeout` in a hidden tab —
  measured, `AbortSignal.timeout(3000)` **did not fire within 15 s** while `setTimeout(15000)` was
  on time to the millisecond. `client.ts`'s `budget()` is built on `AbortSignal.timeout`, so in a
  hidden tab the timeout budget effectively does not exist, and `M7-1` sat on skeletons for 90+
  seconds with an empty `__tcmsErrors` — **which reads exactly like the endless spinner the row
  exists to catch.** A false positive for a real defect is the expensive direction. The charter must
  assert `document.visibilityState === 'visible'` before the first row rather than assume it.
- **F13 — a reload clears the fault switch**, so the obvious way to drive every `F` row is
  self-defeating. Every row is worded *"arm the fault, reload X"*; `devFaults.ts` keeps state in
  module scope precisely so a reload is a clean slate. The method that works is arm-then-navigate
  **in-SPA**, evicting the target query first when a previous screen cached it.
- **F14 — `expire()` armed before a route mount is silently swallowed**, and the row reads as
  failed. StrictMode's dev double-invoke mounts the query twice: the fabricated 401 is consumed by
  the first attempt and superseded by a second that succeeds, so the recovery ladder never runs.
  That looks precisely like *"a mid-session 401 is swallowed and the user is never told"* — a
  serious defect, and a **false negative**. Aimed at an already-mounted query, the ladder fires and
  the dialog appears.
- **F10 — the local dev `compose.yaml` has no evidence/reports volume**, so `POST .../evidence`
  500s on `AccessDeniedException: /var/lib/tcms`. **Production is unaffected** — `compose.prod.yaml`
  bind-mounts both roots explicitly, with §107's reasoning in a comment. The root `compose.yaml` has
  no volume at all. Worked around inside the container; does not survive `docker compose down`.

F3 and F4 are charter corrections: there **is** an "Invite a person" button (§109's setup prescribed
console calls), and the charter's addresses must be namespaced per pass because `app_user.email` is
instance-unique and `viewer@acme.test` already belonged to a §62-era company.

## What the pass proves about §65

**The deferral is holding and it is expensive, and this sitting is the measurement.**

Six product findings in roughly 60 driven rows, from a queue of ~360, against slices whose gates were
all green. None of the six could have been caught by `./gradlew build`, `npm run build`, `npm run
lint` or 1045 frontend tests — F1 and F7 and F11 are cache and session behaviour across two screens,
F5 and F6 and F9 are features that do not exist and therefore have no failing test to write.

That is the §65 bargain stated in numbers: the standing policy buys shipping speed while requirements
move, and the bill is paid in findings that arrive late and all at once. Two of the six (F5, F6) are
**gaps rather than bugs** — the product cannot do a thing it was designed to do — and both were
invisible from inside the code, because nothing is broken until somebody tries to be a persona the
product cannot make.

**And the queue is now ~790 rows and essentially unrun.** §110 drove roughly 60 of them. Decision
round 3 (§123) chose to close small gaps before draining the queue, which grows this number rather
than shrinking it; that is the user's call and it is recorded as such, but the arithmetic belongs
here next to the evidence for what a driven row is worth.

## What is left, and what it is blocked on

**Undriveable, not undriven** — these need a *feature*, not a sitting:

| Rows | Blocked on |
|---|---|
| `L1-20`, `L2-11` | **F5** — role authoring. The persona cannot exist. |
| `M2-22` | **F9** — no suite condition can be created, so none can be inherited. |
| `M3-13`, `L2-5`, `L2-6`, `L3-6`, `M4-27` | Confirmed unreachable by `M3-1`: the five-state plan ladder is **gone**, not hidden. |
| `M2-48` | Needs a pre-`V33` database. |
| `M7-4`'s second half | **A human keystroke** — typing a password into a field is outside what an agent may do. |

**Not driven, and cheapest to close next**: the bulk of `L1`'s persona walk, `L2`'s remaining
curl-only rows, most of `L3`'s psql invariants, `L4-42`…`L4-48` (the rollup-tick rows, which need a
clean sequence of scheduler ticks this sitting kept interrupting), `M7-6`'s second half, `M7-1`'s
remaining arms, and `M7-7` / `F-1` — the two responsive rows, neither started.

**Two rows were confirmed by accident rather than by design** (`L4-16`, `L4-26`), which is recorded
as a gap: a follow-up must request a review and check the board **before** approving, to see the
card-present state `M5-28` describes and this sitting missed by doing A4 before A5.

## The lesson, in one line

**A verification queue is code, and it rots like code.** Six of fourteen findings were about the
observer; two of those landed on rows rewritten the day before. The queue's expectations must be
derived from the *code* — a `.orElseGet`, a `V2` trigger — and never from the state of the one
instance somebody happened to have running.
