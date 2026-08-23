# Deferred browser checks — the queue

**Policy §65 (2026-08-02).** A live browser pass is deferred and batched: a slice records what must
be driven, and this file is executed later as one session. Nothing here is optional — each row is a
defect nobody has looked for yet, and **a slice in an unrun lane is *built and unverified***.

## What has actually been driven — read this before starting a sitting

**Two sittings, both partial, both with their findings in their own file.** Neither marked rows in
place, so **do not read an unmarked row as unrun** — read these instead:

| Sitting | Lanes reached | Findings |
|---|---|---|
| 2026-08-06 (§110) | ~60 rows, ordered | [`2026-08-06-live-pass-findings.md`](2026-08-06-live-pass-findings.md) — six product findings, **none reachable by any gate** |
| 2026-08-12 | `L0`, `A0` complete, `A1` partial — stopped after `A1` at the user's direction | [`2026-08-12-live-pass-findings.md`](2026-08-12-live-pass-findings.md) — two 🔴, one stale expectation in this file |

**The next sitting resumes at `A2 — plans`.** The 2026-08-12 company (`v179`) and its fixture — 10
suites, 29 cases, tags, two conditions — were left in place for exactly that, along with a live
`Fixture seeding (temp)` API token; **revoke it before that instance is shown to anyone.** What that
fixture does *not* cover is recorded in its own file and is worth reading first: the 29 cases were
bulk-authored through one token, so `L1-6`'s author-based `OWN` check still needs a fixture of its
own, and the case-editor behaviours were driven on `TC-30` alone.

**Both sittings died on the same defect, six days apart** — an `AccessDeniedException` on the first
blob write, because nothing created `/var/lib/tcms`. §110 fixed `deploy/compose.prod.yaml`, wrote
*"production is unaffected"*, and left the local stack this queue is driven against. **§185 fixed
the class**: the image creates the roots, `compose.yaml` mounts a named volume, three
`ConfigConventionTest` rules derive the roots from `application.yml` and ask each artifact, and the
application now refuses to start rather than 500ing at the first upload. A sitting from
2026-08-13 onward should not meet it; **if it does, that is a finding about the fix and not a
workaround to apply.**

~~**The other 🔴 is still open and is a product gap, not a UI one**: nothing in the backend can write
a suite-level condition — no `conditions` on `SuiteInput`, no route on `SuiteController`, no
construction of `SuiteCondition` anywhere — while the read path resolves them in detail. `A1-2` and
`M2-22` are **undriveable rather than undriven**, and `L4-19`/`L4-20`'s suite sides go with them.~~
→ **CLOSED BY §186, and every row named here is driveable now.** `SuiteInput` carries
`preconditions` / `postconditions`, the suite create/rename dialog carries two condition panels,
and `Suite` serves the two lists split by kind where it served one undifferentiated `conditions`.

**Three of those rows change what they expect, and the changes are in the rows themselves**:
`A1-2` now drives a real control instead of asking whether one exists, `M2-22`'s fixture is
produced by the pass rather than by hand-written SQL, and `L4-19`'s two-figure impact line has a
**suite** figure that can be non-zero for the first time — the branch that renders it had never
executed in any deployment, which is why §186 found its wording eighty-eight slices stale.

## What this file is now — §109, 2026-08-06

**There is one pass, and it is live.** §107 deleted `src/mocks/` — MSW, its handlers, its fixtures
and `VITE_API_MODE` — so `npm run dev` requires `docker compose up` and a provisioned company. The
`M1 → M7` mock lanes were written against a fixture store that no longer exists, and §107 left the
fold-in undone and said so. This is that fold.

**Row ids are unchanged and every one still resolves.** `M2-38`, `L4-30`, `§98's row M2-48` and the
rest are referenced from handoff sections §98 and §102–§106; renumbering would break them for a
cosmetic gain. So **`M` is now a historical row name, not a pass** — the prefix records where a row
was written, not where it runs. Where a row went is the mapping at the end of this file, and the
short form is: `M1→A5 · M2→A1 · M3→A2 · M4→A3 · M5→A4 · M6→A6 · M7→F`, `L0`–`L4` keep their lanes
except for the eleven moves listed there.

**Three things changed beyond re-homing, and each is a finding rather than an edit:**

1. **`L0` was written against an instance somebody else had filled.** Five of its eight rows — every
   run "Not started", every plan "Draft", every suite unbadged, criteria empty, review "Not in
   review" — presuppose runs, plans and suites, and **`provision-company` creates none of them**
   (`SuiteWriteService`'s own header: a provisioned project has no folders). Against a real fresh
   instance those rows had nothing to look at. They now sit in the lane that *creates* the row and
   are checked at the moment of creation, which is strictly stronger: *"the plan you just made is
   Draft"* beats *"every plan is Draft"* over a set somebody else authored.
2. **The boards moved from first to sixth.** `M1` ran first in the mock pass because the fixture
   store already held cards. Live, a board card is **derived** — from a review request, an execution,
   a health signal — so there is nothing on any board until the review and execution lanes have run.
   A board lane placed first would have checked five card behaviours against three empty columns and
   passed.
3. **The only ✅ rows in this file are void.** `M4-13…M4-18` and `M1-13` were marked run at §100 —
   **in mock mode, at the user's explicit direction**, overriding §65 for that slice. `CLAUDE.md`
   says live is the one that counts and the mock they ran against is deleted, so the marks are
   removed rather than carried. They are ordinary unrun rows again.

**Rows dropped, with the reason, because a silent deletion reads as an oversight:**

| Row | Why it is gone |
|---|---|
| `M6-2` | *"Poll three times, one state per poll."* That was the mock's clock. The real worker is `PT15M` after a `PT3M` delay and does not advance because somebody asked. Its live successor is `L4-30`, which is about exactly that difference. |
| `M4-40` | Was *"the mock's `resolveArms` excludes `DEPRECATED` and the server does not"*. Half the comparison is deleted — but the **question is still open**, so it survives retargeted (`A3`): drive it live and *record which behaviour the server has*, because §99 inherited the divergence and never resolved it. |
| `M6-13`, `M6-20` | Both told you to edit source — `GenerateReportDialog` and `mockModel` — to force a state the mock could not otherwise reach. Both retarget to something better and neither touches a file: a `FAILED` job comes from the **worker's own** refusal of an unresolvable `runId`, and a drifted model comes from `tcmsFaults.drift()`, which now injects in front of `fetch` and covers the artifact route too (`client.ts`'s second fault seam, line 371). |
| `M1-29` | Was the mock handler's second 409. It is a **server** refusal now and moves to `L2`. |

Everything else survives with its wording repaired where it named a fixture, a mock handler, or
"mock mode signs in as one persona".

---

## Setup

```bash
cp .env.example .env
docker compose up --build          # REBUILD — a running image predates the change (§63)

docker compose run --rm backend provision-company \
  --slug=acme --name="Acme Test Co" \
  --admin-email=admin@acme.test --admin-name="A. Admin" \
  --project-key=OLB --project-name="Acme Online Banking"
# Redeem the /accept-invitation link it prints. There is no "any password of 4+ chars".

cd frontend && npm run dev         # http://localhost:5173, proxying /api to localhost:8080
```

**After changing `.env.local`, kill vite and `rm -rf node_modules/.vite`** — an in-place restart
leaves a stale optimize-deps hash and the page renders blank with an empty console.

**There is no `VITE_API_MODE` and no mode to choose.** Every row in this file is live. Where a row
still says *"live only"* it is saying something narrower and still true: it needs a **scheduler
tick**, a **second account**, or **a database that migrated in place** — things a browser session
alone does not produce.

### Can this be driven on `shanks`? — scoped 2026-08-12, not run

The homelab box is Linux with a display, so the answer is yes, with three things settled in advance
rather than discovered at stage 1:

- **The browser is scripted, not interactive.** No Playwright MCP server is connected in the session
  that scoped this, so the shape is `npx playwright` driven from a shell script with screenshots
  written to files and read back. `npx playwright` resolves; **no browsers are downloaded**
  (`~/.cache/ms-playwright` is absent) — `npx playwright install chromium` is ~150 MB and
  `--with-deps` wants `sudo`. Headless throughout; the display is irrelevant. What this shape does
  **not** cover is the judgment rows — *"does this read as broken?"*, *"compare line by line"*,
  *"does the copy hold up over minutes"* — which need a person on the screenshots either way.
- **Run it against a FRESH SCRATCH STACK from the repo, on the lane-B ports** (`6432` / `9080` /
  `6173`), decided 2026-08-12. Not the lab: `/srv/tcms` is a live deployment on this same box —
  backend container plus **host Postgres on 5432** — and driving the queue there would write six
  accounts, ten suites, 26 cases and a full plan/run/review trail into a deployment, on whatever
  image it last shipped rather than on master.
- **`L0` is spent the moment a company is provisioned.** It is the one read-only, un-re-runnable
  lane, so which instance the pass lives on is a decision taken *before* `provision-company`, not
  after — which is the whole reason this note exists.

### The six accounts

**Invite them from the screen, not the console** — Teams & roles → People → **Invite a person**
(§110 F3: the console recipe here was wrong, there has been a screen all along). The response
carries **no link** and must not (see `MembershipInviteService`), so with no SMTP relay the link is
printed by:

```bash
docker compose run --rm backend issue-invitation --slug=<slug> --email=lead@<slug>.test
```

**Namespace the addresses per pass, and write the password down.** `app_user.email` is
**instance-unique** — one account, many companies (§6s) — so a second pass reusing `…@acme.test`
inherits accounts whose passwords belong to whoever ran the first one (§110 F4, which cost a
persona). Use the company slug: `lead@v109.test`. And **fix one password for every persona and
record it here**, because there is **no password reset anywhere in this product** — not in the API,
not in the CLI, which says so itself (§110 F6). A pass whose passwords are lost cannot be resumed.

| Account | Grant | Rows that need it |
|---|---|---|
| `admin@…` | Company Admin (from `provision-company`) | L1-19, the settings lanes, every grant |
| `lead@…` | Test Lead on **OLB and MOB** | most of the authoring pass; the "control is live" half of L1 |
| `engineer@…` | Test Engineer on **OLB only** | L1-5…L1-16, the second account in every two-person row, L4-37's narrower view |
| `viewer@…` | Viewer on OLB | L1-1…L1-4 |
| `watcher@…` | a **custom role**: `project:read` + `report:view`, **no `report:export`** | L1-20, L2-11, A6's split. No built-in role has this shape; §87 built it for the suite. **§110 F5 found it cannot be built here either** — `/roles` is GET-only — so this account and its three rows are blocked until role authoring exists. |
| `newbie@…` | company member, **no project grant at all** | L4-36 |

~~**The custom role is made through the product**: Admin → Roles → new role, `project:read` +
`report:view`, then grant it to `watcher` on OLB.~~ → **superseded by §110 F5**: there is no role
editor and no `POST /roles`; all six built-ins that hold `report:view` also hold `report:export`.
The split §6e designed cannot be expressed by any real deployment, so `A0-3`, `L1-20` and `L2-11`
are **undriveable**, not merely undriven. Recorded as the finding the old wording asked for.

**The run in progress (2026-08-06) used company `v109`, addresses `…@acme.test`, and the password
`Charter-v109!` for all six** — see `2026-08-06-live-pass-findings.md`.

### The fixture charter — what this pass authors, and under which keys

Display keys are sequential per project, so **authoring in this order makes every key below
deterministic**. Anything a row names by key is created here.

| Object | Detail |
|---|---|
| Projects | **`OLB`** "Acme Online Banking" (provisioned), **`MOB`** "Acme Mobile" (created in `A0`) |
| Suites, OLB — ten, flat | Created in this order: **Payments** (tag rule `payments`), **Cart**, **Checkout**, **Refunds**, **Partial refunds**, **Card capture**, **Settlement**, **Legacy card flows**, **Smoke** (tag rule `smoke`), **Unmatched automation**. The rail sorts by name, so it reads `Card capture · Cart · Checkout · Legacy card flows · Partial refunds · Payments · Refunds · Settlement · Smoke · Unmatched automation` — which is `M2-37` unchanged. |
| Cases, OLB — 26 | `TC-1` authored **as `engineer`**, `TC-2` **as `lead`**, `TC-3` then given `created_by = NULL` by SQL (nothing the API offers can produce it). **Cart holds 9 filed cases** (`M2-44`). **Smoke files none and claims 2 by tag** (`M2-38`) — the non-vacuity of the whole suite-resolution argument, so build it deliberately. Two cases carry `payments` while filed under Refunds/Card capture, so **Payments claims cases it does not file**. |
| Cases, MOB — 3 | Enough for a second corpus (`L4-17`) and a second board project (`M1-3`). |
| Tags | `smoke` on 2 cases, `smoke-extended` on 1 (so `M2-49`'s most-used-first ordering has something to order), `payments`, `regression` |
| Conditions | Three in the library; one attached to the **suite `Cart`** so `M2-22`'s inherited rows exist — **`A1-2` authors it through the product now (§186)**, where before §186 the pass had no way to make one and two sittings filed it as a defect; one attached to several cases **and to `Cart`** for `L4-19`, whose suite figure is otherwise always 0; and, since §187, **the same entry attached at a SECOND suite** so `§187 M2-22e` can show that declining one attachment leaves the other in force — the property the whole table is keyed for, and the one a single attachment cannot demonstrate |
| Plans | `TP-1` (sections referencing suites, tags and named cases — `M3-52`…`M3-58` all read it), `TP-2`…`TP-5` down the ladder |
| Runs | `TR-1` against `TP-1`; further runs as `A3` needs them |
| Teams | **Platform** (created first and alone, for `L4-1`), then **Payments Squad** (`lead`, `engineer`) and **Checkout Squad** (`viewer`, `newbie`) |
| Doc pages, OLB | **`DOC-1` "Payments"**, authored in `/docs` before `M3-16` needs a page to link. §115 needs it to have a **specific shape**, so build it deliberately: one paragraph of intro, a **heading "Refunds"**, then **four list bullets** under it, then a **heading "Disputes"** with nothing under it. Link cases to the bullets from the documentation editor (`M3-19`'s affordance): bullet 1 → a case that **passed**, bullet 2 → **two** cases, one that passed and one that **failed**, bullet 3 → a case **never executed**, bullet 4 → **nothing at all**. Then link a case to the intro **paragraph**. Also author **`DOC-2` "Charter"** as prose alone — one paragraph, no bullets, no links. |

**Two things §218 needs are NOT fixtures and are not authored here — read `§218`'s own block before
the stack is built.** `TCMS_INTEGRATION_SECRET_KEY` is a required Spring property with no default, so
an instance without it does not start at all; `.env.lane-b.example` carries a dev value, which is the
file this pass copies, so the ordinary path is already covered and the note is a **diagnostic** — a
scratch stack that refuses to boot with a `secret-key is required` message is this and the fix is
`openssl rand -base64 32`, not a rollback. And `§218 NT-6`/`NT-8` need a **webhook endpoint**: they
deliberately use an unreachable one, because a delivery row is written as soon as an attempt is
*made*. Reading a **successful** delivery is the one thing here that needs more, and the
recommendation is a **local HTTP sink** rather than a real Slack workspace — it is the only option
that also proves the *body* the adapter posts. Stand it up before stage 5, not at stage 13.

**If a fixture cannot be authored through the product, that is a finding and not a setup problem.**
The old pass could paper over a missing control with a fixture; this one cannot, and the two places
SQL is still required — `TC-3`'s null author and `L3-3`'s retired-after-assignment value — are
called out where they are needed rather than assumed.

---

## Run order

**One agent, one sitting, in this order.** The lanes share one deployment, one database and one
accumulating state; later lanes read what earlier ones wrote. Do not interleave, do not split across
agents — `docs/plans/2026-07-31-parallel-execution-rework.md` §0 is what a second agent in this tree
costs, and here it would also mean two people writing one company's fixtures.

| Stage | Lane | Before you start | What it leaves behind |
|---|---|---|---|
| 1 | **L0** — the empty instance | A freshly provisioned company, nothing written. **Read-only: write nothing, not even to check something.** | Nothing. It is the only read-only lane and it cannot be re-run. |
| 2 | **A0** — accounts, projects, teams | L0 finished and screenshotted. | Six accounts, `MOB`, three teams, the custom role. |
| 3 | **A1** — the repository | A0's accounts. | The ten suites, 26 cases, tags, conditions, the classification tree. |
| 4 | **A2** — plans | A1's suites, cases and tags — `M3-5` reads the `smoke` tag and `M3-6`'s arithmetic needs the tag rules. | `TP-1`…`TP-5`, a plan document, section drafts. |
| 5 | **A3** — execution | A2's plans. | `TR-1`, results, evidence, two gates switched on — **and both clocks started (see below).** |
| 6 | **A4** — review | **`M5-1` first, always**: review is off by default and `A3`'s `M4-28` switches all three opt-ins off. | An open review, a verdict trail, comments, reviewer requests — and therefore the first board cards. |
| 7 | **A5** — the frame and the boards | A3 and A4: every card on every board is derived from what they wrote. | Cards moved between columns. |
| 8 | **A6** — reports | The report generated at the end of A3 has had its tick. | Report jobs, saved definitions, downloaded files. |
| 9 | **L1** — the permission matrix | All six accounts; review on for plans. | Cases deleted, evidence deleted, a suite approved, a plan moved. |
| 10 | **L2** — the server's refusals | L1's personas still to hand. | Refused writes only. Nothing lands. |
| 11 | **L3** — the `psql` invariants | L1 and L2 done — four rows read what those lanes wrote. | Nothing; it is a read. |
| 12 | **L4** — what only a real instance produces | Both clocks elapsed. | The company is now fully written. |
| 13 | **F** — faults and responsive | Everything else finished. **This lane arms `tcmsFaults`, which poisons every request until it is off** — run it earlier and every later failure is unattributable. | Faults. `tcmsFaults.off()` after each row, reload at the end. |

### The two clocks — start them at the end of stage 5 or the pass stalls twice

| Start it at | What | Why |
|---|---|---|
| End of **A3** | **`L4-23`'s seed** — one case, three `Failed` results across three runs (the unique constraint forces one run each) | The health scheduler is `PT3M` then `PT15M`. Seeded here, the tick has happened by stage 12. Seeded at L4, you wait. |
| End of **A3** | **One report generate** as `lead` | The report worker is on the same clock, and `L4-30` is *about* the card sitting at Queued/Building for minutes. Start it and read it later. |
| End of **A3** | **A result recorded against `TP-1`'s run** | The rollup scheduler (`L4-40`…`L4-48`) shares the clock. `L4-40` must be read **before** the first tick — so read it immediately, then let the tick happen. |

**Fault switches live in module state**, so a reload clears them — arm the fault *after* the page has
loaded when the row is about a failed refetch. Caught client errors are at `window.__tcmsErrors`;
check it is empty at the end of every lane, and `M7-8` checks it at the end of the pass.

**If a row cannot be driven** — a control is missing, a fixture did not take — record it against that
row and carry on down the lane. **Do not restart the pass**: `L0` cannot be re-run and a restart
costs the whole company. If a whole stage is impossible, finish every other row in it, record what
was skipped and why in the slice's handoff section, and go on to the next stage.

**Findings go in the slice's handoff section, not here** — this is the queue.

---

# L0 — the empty instance (read-only, once, before anything else)

Three facts that are true of every real installation on its first day and false for ever after the
first write. Screenshot as you go; none can be reproduced.

**This lane is three rows and used to be eight.** The other five presupposed runs, plans and suites
that `provision-company` does not create — they are checked at creation instead, in `A1`, `A2` and
`A3`, and they keep their ids there.

| # | Drive | Expect | src |
|---|---|---|---|
| L0-1 | Company Admin → Teams & roles | "Team structure" **empty**, no-records copy. `team` has never had a writer on this instance. | §71-1 |
| L0-7 | The one project, before anything is authored in it | **No Review panel anywhere** — not an empty panel, not a placeholder. This is the only chance to see the off state; `A4` switches it on and nothing switches it back. ~~`psql`: one `project_settings` row for the project, all three `review_enabled_*` false.~~ → **superseded by §110 F2**: expect **zero** rows. `V23` backfilled the projects that existed when it ran; every project created since has no row until settings are saved, and `ProjectSettingsService` says so in three places (`.orElseGet(ProjectSettings::defaults)`). The old assertion encoded a migration artifact of §77's database. | §77-1 |
| L0-8 | The project dashboard **within 3 minutes of the first boot** | "Needs attention" reads **"not computed yet"**, not "nothing needs attention". `healthComputedAt` is the only thing separating them and §86 shrank the window to the first three minutes — so this row expires faster than any other in the file. | §86-5 |

---

# A0 — accounts, the second project, and the first teams

*Stage 2. Everything below this line writes.* The teams block was `L4-1`…`L4-7`; it moves here
because `A5`'s board rows need teams to exist, and `L4-1` needs **no** team to exist yet — the two
constraints only fit in this order.

| # | Drive | Expect | src |
|---|---|---|---|
| A0-1 | Invite the five colleagues (`POST /api/v1/users` from the console, then `issue-invitation` for each link) and redeem each in a private window | Each lands on `/accept-invitation`, chooses a password, and signs in. The response body of the invite **never carries the link** — if it does, that is a disclosure defect and the row for it. | §109 |
| A0-5 | **Calibrate the browser FIRST — see the note under this table; a negative result is meaningless without it.** Then, **as part of A0-1 and not after it** (tokens are single-use, so there is no second chance): redeem **one** of the five invites in a **normal** Chrome window signed into a Google profile rather than the private window A0-1 otherwise calls for, since a private window suppresses the offer. Complete the accept form and land on `/login` | Chrome offers **"Save password?"** filed under the invitee's **email address**, not under no account. Then the `/login` form autofills both fields from that entry. ~~The offer comes from `navigator.credentials.store`, so an absent prompt means the API path failed, not the markup.~~ → **superseded 2026-08-07**, and it had the mechanism backwards. The offer is expected to come from the **route change**: `navigate()` is a `replaceState` that unmounts the form, which is what Google documents as how a manager learns a fetch-driven sign-in succeeded. `store()` is a belt-and-braces addition that was observed doing nothing. So an absent prompt indicts the **markup and the unmount**, not the API. **This is still §63's only observer** — the slice shipped both mechanisms and neither has ever been seen to fire. | §63 |
| A0-6 | **Calibrate first, as A0-5.** Then the same two forms in **Safari**, signed into an Apple ID (iCloud Keychain) | Safari implements no Credential Management API for passwords, so this row exercises the **markup alone**: the strong-password suggestion appears on the accept form's `new-password` field, Keychain offers to save under the email after submit, and `/login` then autofills. A failure here is a **markup** finding — `method`/`action`, the read-only username field, `readOnly` vs `disabled` — and not a `credentials.ts` one. The two rows fail independently and for opposite reasons; running only one proves nothing about the other. | §63 |
| A0-2 | Create project **`MOB`** as the Company Admin; grant `lead` on both projects and `engineer` on `OLB` only | Both projects in the switcher for `lead`, one for `engineer`. `L4-37` and `M1-3` both rest on this asymmetry. | §109 |
| A0-3 | Build the custom role (`project:read` + `report:view`, no `report:export`) and grant it to `watcher` on `OLB` | The role saves and the grant takes. **If the role editor cannot express a role without `report:export`, stop and record it** — §87 had to build this role in Java because no built-in has the shape, and this is the first time a person has to build it through the product. | §87 |
| L4-1 | New team → "Platform" → Create | Appears in the tree; `SELECT id, name, parent_team_id FROM team` shows **exactly one row**, `company_id` the caller's. Run this before the other two teams exist or the assertion is not the one written. | §71-2 |
| L4-3 | Blank name → Create; then create "QA" and then "qa"; then A, B-under-A, and edit A's "Sits under" | Field error, dialog open. The second refused naming **the stored spelling** (`already has a team called "QA"`) — case-folding is the point and an exact-match check accepts it. B is **not offered** as A's parent; a legal parent saves. | §71-5,6,7 |
| L4-4 | Edit a nested team → "Sits under" → "No parent" → Save | It moves to the root. **The PUT-not-PATCH decision is this row**: under PATCH the operation is unreachable. | §71-8 |
| A0-4 | Create **Payments Squad** (`lead`, `engineer`) and **Checkout Squad** (`viewer`, `newbie`) | Two more teams. `M1-11` needs the two boards to hold **different** people's cards, so the membership split is the fixture — put them in different teams and keep them there. | §85 |
| L4-2 | Grant a role → subject **Platform (team)** → Test Engineer on `MOB` → Members → tick `engineer` | `engineer`'s own session (second window) lists `MOB` on `/projects` **without signing out and in**; unticking removes it from their rail on the next navigation. **This is §71**: the team-grant path has existed since M0 and could never be exercised, because no team could be created for a grant to name. Untick afterwards — `L4-37` needs `engineer` on one project. | §71-3 |
| L4-6 | Members dialog on a team that holds a grant — **open it immediately after making the grant, and then again after a reload** | The **warning banner** names the projects and says anyone added gets them immediately. **§110 F7: it is absent on the first opening** — the grant mutation does not invalidate the teams query, so the dialog renders a pre-grant `team.projects` (and the tree's grant chip is missing too). The two openings are the row now, because the copy is right and the freshness is not. Ticking a person survives close-and-reopen (the list refetches from the mutation's invalidation, not a snapshot). Ticking "Leads" for a non-member is **disabled with a tooltip** until "In team" is ticked. | §71-11,12 |
| L4-5 | Delete a team that holds a grant; then one with children | **409** listing *"1 role grant is made through this team…"* — cancel and the grant is still in the Role assignments table. Then 409 naming the child count; delete the child first, then the parent → 204. | §71-9,10 |
| L4-7 | ~~`UPDATE team SET parent_team_id = gen_random_uuid() WHERE name = 'Platform';` — and then a cycle~~ | 🚫 **UNDRIVEABLE — §110 F8, and the product is the reason.** The row assumed *"the API refuses both twice over, so `psql` is the only way in"*. There is a **third** guard: `tcms_enforce_team_hierarchy`, a trigger in **`V2` since M0**, refuses the orphan (*"parent team … is not visible in this company"*) and the cycle (*"… is already an ancestor of …, so this edge would create a cycle"*) at the database. The state this row wanted to observe **cannot exist in this schema**. Kept, not deleted: the question it asks — does the chart drop a row it cannot place? — is fair and simply unreachable from the data side. | §71 note |
| L3-12 | Members dialog: tick a person, close, reopen, untick | 204 both times, no duplicate row: `team_membership` gained one row and lost it. Moved out of `L3` because it is a write, and this is where the writes to `team` happen. | §71-13 |

### API tokens (§118)

*Still A0: a token is company-level, issued by the Company Admin, and the rows below need `MOB` to
exist so the project picker has something in it. Drive them **after** the projects are created and
**before** leaving this lane.*

**`A0-11` is the only row in this file that puts a live credential on a clipboard.** Paste it into
the `curl` in `A0-12`, then discard it — it is not needed again, and `A0-13` revokes it.

| # | Drive | Expect | src |
|---|---|---|---|
| A0-8 | The rail's **Company** group → **API tokens** | The item is **present and enabled**, sits beside Teams & roles, and marks itself current (`aria-current`, not colour alone). The screen renders the empty state *"No tokens yet — issue one so a CI job can post its results"*, **not** a failed region. The rail item is deliberately ungated: a Test Lead must be able to find out this capability exists. | §118-1 |
| A0-9 | **Issue a token** → open the Role select | **Instance Admin is absent**; Company Admin, Test Lead, Test Engineer, Automation Service and Viewer are all offered. Then pick **Acme Online Banking** in *Where it works* and re-open Role: **the list does not change** — a token's project is a ceiling on where it works, not a statement about the role's scope, and narrowing here would be `GrantRoleDialog`'s filter copied into a dialog that must not have it. | §118-2 |
| A0-10 | Same dialog: leave **Expires** empty; read the two hints | *Where it works* reads *"Every project you can reach, including ones created later"* until a project is picked, then *"A hard limit: the token is inert on every other project."* Expires reads *"Optional. Leave it empty and the token lasts until it is revoked."* — never a blank field with no explanation. The blue hint says the token **never exceeds your own access**. | §118-3 |
| A0-11 | Name it `Nightly regression`, role **Automation Service**, no project, no expiry → **Issue token**. Then press **Esc**, then click the **backdrop**. | The form is **replaced** by the secret, in full, in the mono font, with the warning *"This is the only time this token is shown."* **Esc does nothing. The backdrop does nothing.** This is the one dialog in the app that refuses both, and it is the row that matters most: every other create dialog here closes on success, and a copy of that shape destroys a value nothing can produce again. Press **Copy** — either *"Copied."* appears, or, on a plain-HTTP origin, *"Your browser blocked the clipboard — select the text above and copy it"*, **never a button that silently does nothing**. Record which, and paste the secret somewhere for the next row. | §118-4 |
| A0-12 | With that secret, `curl -X POST …/api/v1/projects/{OLB}/ingestions` per §117's shape (`Authorization: Bearer <secret>`, an `Idempotency-Key`, a small JUnit XML) | **The credential works** — a 201 and an ingestion job. This is the only row in the pass that proves the screen produced a usable token rather than a plausible-looking string, and it closes M3's loop end to end: §116 mints, §118 hands over, §117 ingests. A 401 here is a finding about the *issue* path, not about ingestion. | §118-5 |
| A0-13 | Close the dialog and read the row | Name, then the **prefix as its own column** in the mono font — check it is the **first 12 characters of what you pasted**. Role `Automation Service`, *Where it works* the **wide sentence** and not a blank cell, Owner your **display name** and not a uuid, Expires *"Does not expire"*, Status **Live**. ~~**There is no "Last used" column** — the contract says the field is always null today, and shipping the column would leave it empty on every row of every deployment for ever.~~ → **CORRECTED 2026-08-13 (§185). There IS one, and it is right.** §118-6's argument was sound and its premise expired: **§132 gave `api_token.last_used_at` a writer**, and `ApiTokensPage.tsx` added the column in the same slice — both files carry the reversal struck through in their own headers, and only this row went on asserting the absence. So the expectation is: the column exists, reads *"Never used"* on a token nothing has authenticated with, and shows the real timestamp after `A0-12`'s `curl` — which is what the 2026-08-12 pass observed and correctly reported as a divergence. **A stale expectation in this file is worse than a missing row**: it asks the next reader to file the product's correct behaviour as a defect. | §118-6, §132, §185 |
| A0-14 | Issue a second token called `Nightly regression`; then a third with an expiry **in the past**; then a fourth pinned to `MOB` with an expiry **tomorrow at 14:00 local** | First two are **400s landing on the field that caused them** — the name error quotes the existing token and tells you to revoke it first; the expiry error says the token would be unusable the moment it was issued. Neither reaches the reveal, and what was typed is still there. The fourth succeeds: its row names **MOB** and its Expires cell is a **UTC-labelled** time that is tomorrow 14:00 **converted from your zone** — a screen showing `14:00 UTC` for a `+03:00` browser means the local wall clock was posted verbatim and the token dies three hours early. | §118-7 |
| A0-15 | **Revoke** `Nightly regression`, reading the dialog before confirming | It names the token **and** its prefix — two tokens can share a name a year apart, and the prefix is what matches a CI configuration. It says the credential stops working immediately, that it cannot be undone, and that **the row stays**. Confirm: the list is the **same length**, the row is muted and reads **Revoked**, and its Revoke is now **disabled** with *"This token was already revoked."* Then re-run `A0-12` with the same secret → **401**. | §118-8 |
| A0-16 | The row whose expiry has passed (`A0-14`'s third, if it was accepted — otherwise wait out the fourth, or `UPDATE api_token SET expires_at = now() - interval '1 day'` on it) | Status **Expired**, in the muted lifecycle colour and **not red** — a credential reaching its stated end date is the system working. **Revoke is enabled on it**, unlike the revoked row: closing a lapsed credential deliberately is a real act, and a screen that collapsed the two dead states into one would take it away. | §118-9 |
| A0-17 | Fresh company, **two projects**, one person in **no team**, one team with **no lead**, one person with no grant anywhere. Open `/org-chart` and read the **Gaps** panel | It names all three — the person with no team, the team with no lead, and the person with no role on any project you can open. §6k: *"these gaps are the reason someone opens an org chart in the first place"*. A panel that renders empty on a company that has all three is the failure, **and it looks exactly like a healthy company** — so read the healthy case too, which must say so in words rather than showing nothing. | §241-5 |
| A0-18 | The chart on a company whose `team.parent_team_id` chain is broken — hand-written SQL, a team pointing at a deleted parent | The orphan appears **at the root**, not dropped — and `/admin/teams` shows the identical set. If the two disagree, `teamTree.ts` was copied rather than shared. **The API cannot produce this state**, which is the point: what gets here came from somewhere else, and dropping it is how it stays. | §241-6 |

### Calibrate the browser before A0-5 and A0-6, or their result means nothing

**A password manager that is switched off looks exactly like an application that fails to ask it.**
On 2026-08-07 a Chrome profile produced no save prompt on the TCMS forms *and* none on a plain,
JS-free HTML login form that did a real POST and a real navigation — the most manager-friendly login
form that can be written. The app was not the variable. Without the second observation the first
reads as a §63 defect, and it is not one.

So **before driving either row, prove the browser prompts at all**:

1. Serve a throwaway page with a `<form method="post">`, an `autocomplete="username"` input and an
   `autocomplete="current-password"` input, posting to a path that answers 303 to a second page.
2. Submit it. **A "Save password?" bubble must appear.** If it does not, stop — nothing about A0-5
   or A0-6 is observable yet, and the cause is in the browser: an Incognito or Guest window (which
   never prompts), `chrome://settings/passwords` → "Offer to save passwords" off, the origin sitting
   in `chrome://password-manager/settings` → **"Never saved"** (one stray "Never" click during dev
   blocks it permanently, and `localhost` is a dev-heavy origin), or a managed-device
   `PasswordManagerEnabled` policy at `chrome://policy`.
3. Only once that bubble appears do A0-5 and A0-6 carry a verdict.

**Record the calibration result alongside the row**, whichever way it went. "Not driven, the browser
could not prompt" is a legitimate outcome; "no prompt, therefore broken" is the wrong finding and
the expensive one.

---

# A1 — the repository: suites, cases, steps, conditions, tags

*Stage 3, and the lane that authors most of the charter.* Work down it in order: the suites exist
before the cases that file into them, the tags exist before the suite rules that resolve them, and
`A2`'s plan arithmetic reads what this lane leaves behind.

**Two rows moved here from `L0`** — `L0-4` and `L0-5` were "every suite is unbadged" and "every
plan's criteria are empty" over a repository somebody else had authored. `L0-4` is now checked on
the ten suites as they are created; `L0-5` moved to `A2` for the same reason.

### The classification vocabulary — build it first, the case editor reads it

| # | Drive | Expect | src |
|---|---|---|---|
| M2-6 | Vocabulary editor (`/settings/classification`) as the **Company Admin** | Four panels with a scope chip (*This project* / *Company-wide*). Feature: **Add value** and every rename/retire enabled. Test Type: **Add value disabled with a tooltip** — not hidden (§31). That contrast is the screen. *(As `lead` instead, the company-wide panels are the disabled ones — that half is `L1-19`.)* | §69-1,2,3 |
| M2-7 | Add root "Lending", then "Mortgages" under it via the row's **+** | Renders indented. `psql`: the child's `path` is the parent's plus a `v…` label **not derived from the name**. | §69-4,5 |
| M2-8 | Add a Feature named **支付** | Accepted and rendered. A name-derived path fails this insert outright. | §69-6 |
| M2-9 | Add a second "Mortgages" under Lending; then under a different parent | Refused **on the name field** (*"Another value here already has that name"*), then accepted — two leaves under different parents may share a name. | §69-7,8 |
| M2-10 | Retire a parent that has children; then the children, then it | **409**, dialog **stays open**, *"2 values sit under this one"* — not a toast over a closed dialog. Then 204 each. Reload: gone and staying gone. | §69-10,11,12 |
| M2-11 | Add a value with `#b7791f`, then one with `red` | Colour dot; then refused on the **colour** field. | §69-17 |
| A1-1 | Add the Feature values the charter needs — **Payments**, **Checkout**, **Refunds** — and leave "Lending/Mortgages" in place | They are what `M2-4` ticks and what `L1-19`'s sibling-project check reads. `tcms_seed_project_defaults` creates the *dimension* and **no values**, so an empty Feature panel here is expected, not a defect. | §68 |

### The suites — ten, flat, in charter order

| # | Drive | Expect | src |
|---|---|---|---|
| M2-41 | Press **New** with a suite selected in the rail (or with none) | The dialog is titled **"New suite"** — not "New suite in *Payments*" — and has **no parent field**. The created suite appears in the flat list and is selected. Create all ten this way. | §98-5 |
| M2-37 | Look at the suite rail once all ten exist | A **flat list**: no expand chevrons, no indentation, every suite visible without expanding anything. Ten rows, sorted by name — `Card capture`, `Cart`, `Checkout`, `Legacy card flows`, `Partial refunds`, `Payments`, `Refunds`, `Settlement`, `Smoke`, `Unmatched automation`. A row missing means the payload lost it; the old tree could drop one silently by orphaning it. | §98-1 |
| L0-4 | The rail, before any suite has been submitted for review | **Every suite unbadged.** `psql`: `SELECT DISTINCT lifecycle_status FROM suite` returns one row, `DRAFT`. Checked here rather than in `L0` because a provisioned project has no suites to look at. | §75-1 |
| M2-42 | Rename any suite | Name and description only; **no parent control anywhere**, and the save does not 400. | §98-6 |
| M2-40 | Open a suite's kebab menu | **No "New suite inside"** — gone, not greyed. Execution history, Rename, the ladder, Delete. A disabled row would advertise a capability the product no longer has. | §98-4 |

### The cases

Author `TC-1` **signed in as `engineer`** and `TC-2` **as `lead`** — the author split `L1-6` needs is
produced by authoring, not by an `UPDATE`, which is one fewer hand-written fixture than the old pass
had. `TC-3`'s `created_by = NULL` still needs SQL; nothing the API offers can produce it.

| # | Drive | Expect | src |
|---|---|---|---|
| M2-12 | Open a case for editing, then **New test case** | Both end in a **blank row**; the new case opens on exactly one. **No "Add step" button** anywhere. Before §94 a new case opened on zero rows. | §94-51 |
| M2-13 | Type one word in the last row's Action, keep typing, then type in the new last row. Repeat with a **paste** | A blank row appears after the **first keystroke** and **exactly one** — not one per character, and not one per paste. The caret **stays put**: a jump means `shouldFocus` is back. | §94-52 |
| M2-16 | New case: title and suite only, no step, press **Create case** | Refused with **"Add at least one step" rendered in the step panel**. Two things at once: the permanent blank row no longer satisfies the rule, and the message is *visible* — it rendered nothing before, so the button simply did not respond. | §94-55 |
| M2-17 | One step with leading/trailing spaces in all three fields, trailing row blank, save and reopen. Then type into the trailing row and clear it back to spaces, save | One trimmed step, no empty numbered step — check the **detail** page too. Whitespace is not a step: `"  "` is accepted by the API and renders for ever as an empty line. | §94-56,57 |
| M2-18 | Remove the trailing blank row with its bin button | A blank row is still there. With no Add button, removing the last row is otherwise a dead end. | §94-58 |
| M2-19 | Drag a step two places down; drop one **outside** the list; drop one back where it started; then keyboard (Tab to handle, Space, arrows, Space) | Moves and **renumbers**, text stays with its row. The two null drops leave the form **un-dirty** — on an edit form Save stays disabled ("Nothing has changed yet"); a `move(i,i)` would offer a new version of an unchanged case. ▲▼ still work. | §94-60 |
| M2-14 | Once several cases share a step, type a fragment of it | A suggestion popup with the action, a **"n cases"** count, and data → expected beneath. It fires **after you stop typing** — watch the network panel: one request per word, not per letter. **This needs a corpus**, so it is the first row that only works once ~10 cases exist; do it late in the authoring, not on `TC-1`. | §94-53 |
| M2-15 | Pick a suggestion with data+expected; then one with **neither** into a row where you had typed data; then edit the action | First fills all three. Second **clears** the data rather than leaving it attached to a step it was never written for. Editing changes nothing else and no "Shared" chip appears — a pick is a copy. | §94-54 |
| L4-17 | Type a step fragment used by several cases; then soft-delete a case using the top suggestion and repeat; then repeat in **`MOB`** | Counts are **distinct cases** (a case repeating a step twice adds one). The deleted case's usage **drops out**. `MOB` offers **its own** steps and none of `OLB`'s. All three were unobservable in mock, where every structured case had the same four steps. **Moved here from `L4`**: it needs a corpus and a second project, both of which this lane builds. | §94-59 |
| M2-1 | A case editor → Classification panel | **Four** controls — Test Type, Test Level, Feature, Execution Phase — with current values selected. An empty vocabulary reads *"Nothing to choose from yet"*, not a spinner. | §68-1,10 |
| M2-2 | Change Test Type, save; then save again untouched | One new version, then **no new version**. Reorder-churn is the risk: the ids go over the wire as an array. | §68-2,3 |
| M2-3 | Set Test Type to **None**, save | Detail shows none; `psql` shows the `case_classification` row **gone**. | §68-4 |
| M2-4 | Tick a **parent** Feature (Lending), save; tick a child (Mortgages) too; untick one | Parent accepted (two milestones of comments claimed it could not be); both stored and both named on the grid; unticking takes only that one. | §68-5,6,7 |
| M2-5 | Create a new case with a Test Type and two Features | Both land; the v1 snapshot carries them. Network: `classificationValueIds` present on **every** save, including empty. | §68-11,13 |

### Conditions

| # | Drive | Expect | src |
|---|---|---|---|
| M2-25 | Create a condition from the **+** beside the picker | Created, **attached to the list it was created from**, title rendered immediately — not "Unknown condition". Library lists are cached per search term. Create the charter's three this way. | §95-70 |
| M2-26 | Save a condition with a blank title, then an all-blank body | Both refused on the field. An empty condition is a picker row that attaches nothing and cannot be told from one that failed to load. | §95-71 |
| M2-20 | Attach two preconditions, save, reopen | Both, **in attachment order** — position is content. | §95-62 |
| M2-21 | Attach one, reopen the picker; then attach one condition as **both** pre- and postcondition | The attached one is **not offered**. Both lists accept the same condition and both survive a reopen — "reset the payment data" is the canonical case. | §95-63,64 |
| A1-2 | Attach a condition to the **suite** `Cart` — kebab → Rename, attach one precondition and one postcondition, save, reopen | ~~If a suite cannot carry a condition through the UI, that is the finding.~~ → **it could not, twice, and §186 built it.** Both land in **their own list**, survive the reopen, and a rename that never touches them leaves them alone. Still the fixture `M2-22` reads and still the only thing that produces it. | §95 · §186 |
| §186 A1-2a | On that suite: reorder the two preconditions, save, then **detach** one and save | The order sticks. `psql`: `SELECT id, position FROM suite_condition` — **the ids are unchanged across the reorder**, which is what keeps `case_condition_optout` pointing at the attachment it declines. See `§186 SR-3`, its mutation twin. | §186 |
| §186 A1-2b | Attach the **same** library entry as both a pre- and a postcondition on one suite | Both stored, both rendered, one in each list. This is the arrangement the old single `conditions` array could not describe at all, and it is what a `BOTH` entry exists for. | §186 |
| M2-22 | A case filed in `Cart` (**filed**, not merely added or tag-matched — only filing inherits): open the editor, save untouched, reopen | Inherited rows render **named with their folder**, with no remove/reorder/drag, and are **still inherited** afterwards. **The slice's most dangerous defect**: an editor that seeded them into the form attaches the folder's conditions to every case anybody edits, permanently and invisibly. | §95-65 |
| §187 M2-22a | On that same case: switch the inherited row's toggle to **Not applicable**, save, reopen | The row is **still in the panel**, greyed and struck through, with the switch off — not gone. `psql`: one `case_condition_optout` row, keyed on `suite_condition.id` and **not** on `condition.id`. **The first row of this table any deployment has ever held**: it shipped in `V1` with a reader, a filter and four files of prose, and until §187 nothing but a test fixture could create one. | §187 |
| §187 M2-22b | Switch it back to **Applies**, save, reopen | The rule is back in the procedure and the opt-out row is gone. This is the half a decline that vanished from the panel would make unreachable — the toggle is the only control that restores it, so if `M2-22a` leaves an empty group the feature is one-way from the one screen that offers it. | §187 |
| §187 M2-22c | With the row declined, open the **case detail** page (not the editor) | A "Turned off for this case" group below the checklist, struck through, naming the folder — *"still applies to every other case in Cart"*. The checklist itself does **not** carry the row. A reader who cannot see this cannot tell "somebody decided this case does not need it" from "nobody has thought about it", and the two lead to opposite actions. | §187 |
| §187 M2-22d | With the row declined, **start a run** containing that case and open the execution | The declined precondition is **absent from the tester's checklist**, and nothing is struck through there. This is the row that justifies two lists instead of a flag: `preconditions` is what `test_case_version.snapshot` records and what `CaseFacts` pins, so a flag would have put the row into every checklist of every run started after the decline. | §187 |
| §187 M2-22e | Attach the **same library entry** at a second suite, file a different case there, and decline only one of the two attachments | Each case answers independently — the declining one loses it, the other keeps it. The whole reason the row is keyed on the attachment: keying it on the library entry would make one toggle mean "never inherit this from anywhere". Needs `§186 A1-2`'s fixture on a second suite. | §187 |
| §187 M2-22f | Decline a row, then **move the case to another suite** (bulk action or the editor's suite picker), then move it back | ~~After the move the rule is gone (a different folder's rules apply) and **after moving back the decline is still in force** … the cost, stated in `CaseConditions.reconcileOptouts`, is exactly this: invisible state that reappears.~~ → **REWRITTEN BY §192, and the row is the reason the slice happened**: after moving back the rule **applies again**, visibly, and the "Turned off for this case" group is empty. A decline lives exactly as long as its attachment hangs off the folder the case is *filed* in, so the move ends it — the same answer `M2-22g`'s clone has given since §129. If the decline **does** come back, the retention bound is not being applied and the six-months-later reappearance is live. Drive it **both ways** (editor suite picker *and* bulk `MOVE_TO_SUITE`): they are two writers of one rule. | §187 · §192 |
| §192 M2-22h | Decline a row, then in **one** save both change the suite **and** leave the toggle off (the editor sends `declinedConditionIds` on every save, so this is what the picker produces) | **200, not a 400** — the ids are checked against the folder the client was looking at — and the decline is gone afterwards. A 400 here means the two bounds have been collapsed into one, and the screen would be refusing a value it had itself just served. | §192 |
| §192 M2-22i | Decline a row, then have an admin **retire that library entry**, then save the case again from the editor | The row is on **no** list (nothing renders a withdrawn procedure) and the decline **survives** — restore the entry and the greyed, struck-through row is back with its switch off. This is the half §192 deliberately did *not* reverse, and the only remaining state where a stored decline is invisible; if restoring the entry shows the rule as **applying**, an omitted-because-unrenderable row has been read as an instruction. | §192 |
| §193 M2-22j | Decline a row, then select that case **plus one undeclined case** in the case list and open the bulk dialog · `MOVE_TO_SUITE` to another folder · **Preview, and stop there** | A **warning**-coloured preview with a *"Losing a condition exception"* group naming **only the declined case**, and the sentence *"moving them back will not bring it back"*. Then close the dialog and reopen the case: the decline is **still there** — a preview that reported its own damage would be the one shape of this field that is worse than the silence it replaced. Until §193 the preview said `2 will change` and nothing else, which is the whole finding: this is the last moment the person can decide not to. | §193 |
| §193 M2-22k | Confirm that same operation | The snackbar reads `2 updated · 1 lost a condition exception` and is a **warning**, not a success — an operation that ended somebody's decision is not a plain success whatever the other counts say. The case detail's "Turned off for this case" group is empty afterwards (that is `M2-22f`). Note that nothing on this screen names the *rule* that came back; the count is the disclosure, and whether that is enough is the open question §193 ends on. | §193 |
| §194 M2-22l | Decline a row, then in the **editor** move the Suite picker to another folder and **stop before Save** | A **warning** panel above the two condition lists: *"Moving this case ends 1 condition exception"*, **naming the rule and its folder**, and *"moving it back will not bring it back"*. Then put the picker back where it was: the panel goes away. Until §194 the whole event happened in silence on this path — §193 gave the bulk action a preview and this screen, which has exactly the same preview moment, had none. | §194 |
| §194 M2-22m | With the picker moved (the state `M2-22l` leaves), try to work the inherited row's **Applies / Not applicable** switch | **It is greyed and does nothing**, and its tooltip says the case is moving out of that folder. This is the row that matters most: before §194 the switch was live, and a flip was accepted by validation and then discarded by the very same request — `normalizeDeclines` bounds the submitted set by what the *destination* imposes, and a flat suite (§98) shares no attachment with the one being left. Check the case's **own** attached conditions in the same panel are still fully editable: a move invalidates the inherited group, not the case's own rows. | §194 |
| §194 M2-22n | Save that move | The snackbar reads `Saved TC-… as v… · the move ended 1 condition exception` and is a **warning**. Then move it back and save again: the snackbar is a **plain success** — nothing ended, because nothing was declined at the folder it just left. The number is **measured off the response**, not predicted from the move (`declinedPreconditions` / `declinedPostconditions` on the saved case), so if it disagrees with `M2-22l`'s preview the preview is the wrong one and §98's flatness is the assumption to re-check. | §194 |
| §195 M2-22o | Decline a row, open **Clone this test case**, and move the Folder picker to another suite — **stop before Create** | A **warning** alert appears directly under the Folder field: *"The copy will not carry 1 condition exception"*, **naming the rule and the folder it comes from**, and *"TC-… keeps its exception"*. Then set the picker back to *Same as the source* — the alert goes away; then pick the **source's own folder by name** from the list — it stays away, because choosing the same id is not a move. Until §195 this dialog said nothing at all and its closing caption said the conditions come across; `§187 M2-22g` below has driven the server side of the rule since §129. | §195 |
| §195 M2-22p | Create that copy, then read the copy's and the source's case detail pages side by side | The **copy** has an empty "Turned off for this case" group and follows its new folder's rules in full; the **source** still carries its decline. Nothing was destroyed — which is why this path has an alert and no snackbar, unlike `§194 M2-22n`. Also read the dialog's closing caption on the way through: it says the case's **own** conditions come across and that the inherited rules are the new folder's, which is true of an ordinary same-folder clone too. | §195 |
| §187 M2-22g | Decline a row, then **clone the case** into the same suite; then clone it into a *different* suite | The same-suite clone carries the decline; the other-suite clone carries none. `CaseCloneService`'s header prescribed this in §129 with no writer to obey it, and §187 is where the paragraph became code. | §187 |
| M2-23 | Detach the **last** condition and save | Gone after reload. Absent ≠ empty: a client omitting the empty list reports success and changes nothing. | §95-66 |
| M2-24 | Reorder conditions with ▲▼, then drag, then keyboard | All three paths produce the same saved order. | §95-67 |
| L4-19 | Edit a condition attached to several cases **and to `Cart`**: read the impact line, confirm, open a **different** case that attaches it | The line gives **two separate figures** — cases attaching directly, suites attaching — never one blended number. **The suite figure has never once rendered** (nothing could write a `suite_condition` row until §186, so the count was 0 everywhere); its sentence is "every case **filed there** inherits it", corrected in §186 from "every case beneath", which §98 made meaningless. The other case shows the **new** text: the attachment is a reference and the edit propagates with no prompt. **Moved here from `L4`**; it needs the multi-attachment fixture this lane builds. | §95-68 |
| L4-20 | With the condition edit dialog open as `lead`, have `engineer` change that condition, then save | **409 in the dialog** saying to reload and reapply — not a toast, not a silent overwrite. `condition.lock_version` was mapped for the first time in §95 **with** `@Version`; §74 found three entities where the plain-column spelling makes the precondition unable to fail. | §95-69 |
| L4-21 | The case editor as `viewer` (no `library:manage`) | Attach, detach and reorder still work (attaching is a case edit); **create and edit refused**, legibly rather than as a bare 403. | §95-72 |

### Tags, and the suites that resolve from them

| # | Drive | Expect | src |
|---|---|---|---|
| M2-29 | Tag a case `smoke` and `regression`, save, reopen; type `smoke` again | Both survive; **no second chip** — a tag list is a set. | §96-77 |
| M2-49 | Case editor → **Tags**, type `sm` | A dropdown of the project's tags that match, **most-used first** (the endpoint's order, which the control must not re-sort — alphabetical puts the project's one stray tag above the one on half its cases). With the charter's fixture: `smoke` before `smoke-extended`. An empty dropdown means the read failed or the join lost its project scope. | §102-1 |
| M2-50 | Pick `smoke` from the list, then open the dropdown again with `sm` | `smoke` is **no longer offered**; `smoke-extended` still is. Re-picking a tag the case already carries is a no-op the de-duplicating `Set` swallows in silence, which reads as a dead menu row. | §102-2 |
| M2-51 | Type a tag **no case carries** (`brand-new-tag`) and press Enter | Accepted as a chip and saved. **THE ROW THIS SLICE MUST NOT BREAK**: `case_tag` is free text and this endpoint is a picker's list, never a validator's — a control that refused an unlisted tag makes the first use of any new tag impossible and closes the vocabulary. | §102-3 |
| M2-32 | Type a 61-character tag, save | Refused on the tags field, **never truncated** — a shortened tag stops matching the suite rule it was written for, with no symptom. | §96-81 |
| M2-53 | Open the case editor in **`MOB`** before any tag exists there (or with the read failing — `tcmsFaults.only('/tags')` then `tcmsFaults.status(503)`) | The field still works as free text: type a tag, press Enter, chip appears, save succeeds. **No spinner, no error, no "no options" popup** — the suggestion list is an optional call and its absence must cost nothing. Disarm the fault before the next row. | §102-5 |
| M2-35 | Suite rename → **Tag rules**: type a rule and watch the field | The chip **appears on Enter**. This phase's own defect: replacing MUI's `slotProps` to add a testid drops the adornment the chips live in, so every rule was held, sent and invisible — both gates green. | §96-85 |
| M2-52 | The same three checks (`M2-49`…`M2-51`) on **suite rename → Tag rules** and on **Bulk actions → Add tags** | Same list, same order, same freedom to type an unlisted one. A suite rule especially: it may legitimately name a tag nothing carries yet, which is how a collection gets written before its cases. **Bulk is the one to drive hardest** — its field's testid was on the wrapper until §102, so it had never been typed into by anything. | §102-4 |
| M2-33 | Give `Smoke` the tag rule `smoke`; open it in the grid | It reports the cases carrying `smoke` **wherever they are filed** — members are a query, not a filing decision. The charter files **nothing** into `Smoke` on purpose: with a suite that also holds its cases, this row and the defective implementation agree. | §96-82 |
| M2-38 | **Click `Smoke` and count the grid's rows against the number in the rail** | They **agree**, and both are 2. **THIS IS THE ROW THE LANE EXISTS FOR.** Before §98 the rail said 2 and the grid said **none**: `caseCount` was the resolved union while `?suiteId=` matched the filed arm alone. Neither number looks wrong on its own — a suite with no cases in it is ordinary — so nothing but reading them together finds it. | §98-2 |
| M2-39 | Click `Payments` (tag rule `payments`, files nothing of its own) | Cases carrying `payments` — including the two filed under `Refunds` and `Card capture`. A count equal to the number of cases *filed* there means the filed arm is answering. | §98-3 |
| M2-34 | Rename `Smoke` without touching the rules, reopen; then remove the last rule and save | Rules survive the rename; removal drops the count. The form always sends `tagRules` seeded from the suite: omitting it could never clear a rule, sending `[]` by default would empty the suite on every rename. Put the rule back afterwards — `M2-38` and `A3` both read it. | §96-83,84 |
| M2-36 | Hover a suite's case count; then add `smoke` to another case and reload the tree | The tooltip says the count includes cases filed here, added here, **or carrying its tags — and that a case can belong to several suites, so counts do not sum to the project total.** The count then goes up **with no write to the suite**. | §96-86,87 |
| M2-30 | Filter bar: add tag `smoke`, then `regression` | Each Enter adds a removable chip and **narrows** — AND, not OR, deliberately the opposite of a suite's tag rules. Deleting widens back. | §96-78 |

### The flake panel and the quarantine action — §6l on the case page (§121 phase 4)

*The screen `M1-29`…`M1-34` had to author their fixture around, because until this phase nothing in
the product could quarantine a test. **These rows replace that console snippet with the product**,
and `M1-34`'s release should be driven from here rather than from `fetch` once this lane has run.*

*Fixture: the charter's case list has no flake rows at all on a fresh instance — the recompute is a
scheduled job and `A1` runs before anything has been ingested. **`M2-54` is therefore the only row
in this block that a fresh lane can drive**, and it is the most important one. The rest need
`ExecutionSource.recomputeFlakeScores` to have run over ingested results, which the `L4` lane
creates; drive `M2-55`…`M2-58` **after** `L4`, and say so if the lane is run in isolation.*

| # | Drive | Expect | src |
|---|---|---|---|
| M2-54 | Open any case detail on a fresh instance and read the **Reliability** panel | *"Not assessed yet."* plus the sentence about what flakiness is measured from — and **no percentage, no "0%", no em dash, and no "Computed now" line**. This is the never-scored state, which is neither of the other two. A panel showing `0%` here says a test nobody has run is evidently stable. | §121-20 |
| M2-55 | Find a case with **fewer than the evidence floor** of repeat runs and read the panel | *"Not enough evidence to score"* **with its counts** — "3 results, 2 repeat runs under identical conditions — too few to score." **THE ROW THIS PHASE EXISTS FOR:** this and `M2-56` must not read alike. One says nobody can tell, the other says we looked hard and it is fine, and `score ?? 0` renders them identically while type-checking. | §121-21 |
| M2-56 | Find a case scored **0** and put it beside `M2-55` on two tabs | A **Stable** chip and `0.0%`, with "0 switches between pass and fail across N repeat runs". Different words, different shape, obviously a measurement rather than the absence of one. **Check the chip is not green** — this measures reliability, not outcome, and a green badge on a test that fails every time reads as a pass. | §121-22 |
| M2-57 | Read the evidence sentence on a scored case and check its **denominator** against the case's execution history | It says *"across N repeat runs"* and **not** *"across N results"*. §121's own finding: a case with six results in six cells has six executions and **zero** transitions, and a sentence quoting the result count invites the reader to treat a score computed from nothing as a measurement over six. | §121-23 |
| M2-58 | As a **Viewer** (no `case:edit`), open a case detail | The **Quarantine test** button is present and **disabled**, with the reason on hover — never absent (§31). Then as **Automation Service** if a session can be had: also refused. **The gate is the slice** — a panel gated on `result:submit` looks identical on every other row here. | §121-24 |
| M2-59 | Press **Quarantine test**, confirm with the reason box **empty** | Refused **without a request leaving the browser** (watch the network tab) and the field says why. Then type a reason and confirm: the dialog closes, and the panel behind it shows the Quarantined banner **with the reason and your own name** — not an id, and not a blank line. | §121-25 |
| M2-60 | With that case quarantined, open the project **Board**, then come back and press **Return to the gate** | The card is there (`M1-29`'s card, created by the product this time); after the release the panel's banner is gone, the button reads *Quarantine test* again, and **the board card is gone on the next visit without a manual reload** — the write invalidates every board key, which is a different screen with a different key and the one thing a `setQueryData` on the case alone would miss. | §121-26 |
| M2-61 | Quarantine a case, then read the reason **character by character**, commas included | Verbatim. Same reason `M1-30` exists one lane over, on the surface that *authors* the sentence rather than the one that renders it: if either end ever splits it, the two rows fail together and the pair says which. | §121-27 |

### Filing, and the cases filed nowhere

| # | Drive | Expect | src |
|---|---|---|---|
| M2-27 | Save a case leaving the Suite select on **Not filed**; find it in the grid | Saves, Suite column reads **Not filed** (muted italics), and it is **in the unfiltered grid**. `suite_id IN (…)` is never true of NULL, so a grid without an explicit arm drops every unfiled case with no error and a total that still counts it. | §96-74 |
| M2-28 | Click the **Not filed** chip; then apply a suite filter and look at it again | Only unfiled cases, `unfiled=true` in a shareable URL, cleared by **Clear filters**. With a suite filter it is **disabled with the reason on hover** — not hidden, not enabled-but-inert. | §96-75,76 |
| M2-31 | Editor on a filed case → suite **Not filed** → save, reload | Unfiled, and the detail breadcrumb reads **Not filed** linking to the grid's unfiled filter — not `?suiteId=null`, which renders every case. | §96-80 |
| M2-45 | The case editor's **Suite** picker | A flat list of names, **no indentation**, sorted the way the rail sorts. The old picker padded each option by `path` depth; a picker that still indents is reading a field the server no longer sends. **Not filed** is still the first option. | §98-9 |
| M2-43 | Delete `Smoke`, which claims two cases by tag | **It deletes.** Then check both cases are still in the grid. A claim is not a holding: deleting the collection takes the claim and never the case. If this 409s, the delete gate widened from the filed arm to the union. **Re-create `Smoke` with its rule afterwards** — `A3` and `L4-44` read it. | §98-7 |
| M2-44 | Delete `Cart`, which holds 9 **filed** cases | **409**, and the sentence names **only a case count** — *"This suite still holds 9 cases."* No "and N suites": there are no child suites left to name, and a leftover clause would be counting something that cannot exist. | §98-8 |
| M2-46 | `Cart` → **Execution history**, and compare the header's "N cases now" with the runs below | The header count is the suite's `caseCount` **verbatim** and the grid reports on the same set. §52's defect was these two disagreeing (3 cases over a table covering 47); the rollup helper that fixed it is deleted, so this is the check that flattening kept it fixed. **Empty until `A3` has run** — come back to it, or read it as part of `L4-44`. | §98-10 |
| M2-48 | **After the pass**, on a report over a run recorded **before** `V33`: a suite-grouped section | Rows print the **dotted** suite paths (`s1.s3`) those runs were recorded under, beside single labels (`s7`) for anything run after. Both are correct: `execution_fact.suite_path` is frozen history and says where a case was filed **when it ran**. Anyone "fixing" this is destroying history. **Needs a database that migrated in place** — this company was created after `V33`, so this row is **not executable on the pass's own instance** and must be driven against the Hetzner box or recorded as unreachable. | §98-12 |

---

# A2 — plans: the ladder, the tabs, the document, the sections

*Stage 4. Reads what `A1` wrote — `M3-5` needs the `smoke` tag and `M3-6`'s arithmetic needs the tag
rules — and leaves `TP-1`…`TP-5` for `A3` to run against.*

> **⚠ Four rows in this file still assume a plan can be Started and Closed, and §97 phase 3a says it
> cannot.** `M3-1` asserts **three** states with no Start, Close or Reopen anywhere; `M3-13`, `L2-5`,
> `L2-6`, `L3-6` and `M4-27` were written against the five-state ladder and were never revisited.
> **Drive `M3-1` first and let it settle the question**, then record for each of the five whether the
> control, the endpoint and the audit action still exist. This is a documentation defect this fold
> found, not a product one — but which of the two it turns into is decided by driving it.

| # | Drive | Expect | src |
|---|---|---|---|
| M3-1 | Create `TP-1` as `lead` and read the header | **Three states only.** Draft → *Submit for review*. In review → *Approve plan* + *Return to draft*. Approved → *Return to draft*. **No Start, Close or Reopen anywhere.** | §97p3a-92 |
| L0-3 | The plan list once `TP-1`…`TP-5` exist | Every new plan **"Draft"** in the draft colour. The old map was `Record<string,string>` missing `IN_REVIEW` and `APPROVED`, so a plan moved down the ladder fell through to grey. Checked here rather than in `L0`: a provisioned project has no plans. | §73-1 |
| L0-5 | A new plan's exit-criteria panel | **Empty**, and `psql` agrees: `entry_criteria`/`exit_criteria` are NULL. Nothing writes them at creation, which is correct — `M3-8` is where they get their first value. Moved from `L0`, where it had no plan to look at. | §74-1 |
| M3-4 | Plan list ordering, once one plan is in review and one approved | Drafts, then in-review, then approved. A list that reshuffles between loads is what this ordering prevents. | §97p3a-96 |
| M3-3 | Project settings → *Approval required before work starts* | **One** switch (cases). The plan switch is **gone, not disabled**. Toggling it leaves every other setting alone. | §97p3a-95 |
| M3-2 | ~~On an approved plan press **Edit**~~ → **obsoleted by §97 phase 5**: there is no Edit button. Open an approved plan's **Plan** tab instead | The fields are editable and there is no "Reopen the plan…" tooltip anywhere. There is no closed plan; the plan is editable. | §97p3a-93, §97p5-129 |
| M3-14 | A draft plan (`TP-4`) → Delete → confirm | 204, leaves the list, and the following read is a **404 page** not an empty panel. | §73-6 |
| M3-13 | ⚠ Reopen a closed plan | **Probably unreachable — see the box above.** If there is no closed plan, record that and strike this row. If there is: chip back to **Active**, `closed_at` null, audit action `plan.reopened`, not a second `plan.started`. | §73-3 |

### The seven tabs and the one draft above them

| # | Drive | Expect | src |
|---|---|---|---|
| M3-21 | Open `TP-1` | **Seven tabs** — Progress · Plan · Business requirements · Test artefacts · Scope · Out of scope · Test cases — with Progress still the default: every existing link to this route means the numbers. | §97p4-114 |
| M3-22 | URL → `?tab=nonsense` | Falls back to Progress. An unrecognised id reaching the body renders **nothing at all** — a blank page from a query string. | §97p4-115 |
| M3-23 | Open **Scope**, type a sentence, click **Progress**, then go back to Scope | The banner reads *"Unsaved changes in Scope"* **while Progress is on screen**, numbers underneath; the sentence is still in the box on return. §93's unmounting decision reversed: the tab is gone, the draft is not. | §97p4-116,117 |
| M3-24 | With the banner up, navigate away or reload | The unsaved-work prompt fires — `useUnsavedWork` covers the deploy-skew reload and the mid-session 401. | §97p4-118 |
| M3-25 | Press **Discard** | Back to what the server served, banner gone. **Not "empty"** — discarding to blank loses the work the control protects. | §97p4-119 |
| M3-26 | Edit Scope **and** Out of scope, read the banner | It names **both**, in tab order. One label for two edits makes the second invisible. | §97p4-120 |
| M3-27 | Press **Save**, watch the network panel | **One `PATCH`** carrying `scopeText`/`outOfScopeText` and **no `suiteIds`, `caseIds`, `sections`**. The server reads an empty list as *clear it*, so a full-draft payload empties the plan's scope on a prose edit — and succeeds either way. | §97p4-121 |
| M3-28 | After the save | Banner gone (reseeded from the response), green *Plan saved*. A banner surviving its own successful save is one nobody trusts. | §97p4-122 |
| M3-32 | **Plan** tab: change the name, then switch to **Scope** and back | The banner reads *"Unsaved changes in Plan"*, and the typed name survives the round trip — the Plan tab is in the draft like the prose tabs, not a form of its own. | §97p5-134 |
| M3-33 | **Plan** tab: clear the name entirely | **Save is disabled with the reason on it** and the banner says which tab is stopping it. Typing a name re-enables it. A blank name is the one refusal the client is certain about; everything else is the server's. | §97p5-135 |
| M3-34 | **Plan** tab: type three spaces as the name | Still refused. §94's trim defect — whitespace is not a name, and the server would otherwise answer 400 for something the client could see. | §97p5-136 |

### Exit criteria and the optimistic lock

| # | Drive | Expect | src |
|---|---|---|---|
| M3-8 | **Plan** tab → add exit criteria *pass_rate ≥ 95*; then *open_critical = 0* | The first gets a verdict; `open_critical` renders **not evaluated** — no tick, no cross. Null means not evaluated, not unmet: the product has no issue tracker, and a red cross would tell a release meeting a gate had failed. | §74-2 |
| M3-9 | **Plan** tab → set a threshold, clear the number box entirely, save | The row is **dropped, not sent as `0`**. A `pass_rate >= 0` gate is met by every release and reads exactly like one somebody meant. | §74-3 |
| M3-12 | **Plan** tab → open the metric dropdown | **Five options with readable labels** ("Pass rate (%)", "Open critical defects"), not raw enum spellings. | §74-11 |
| M3-35 | Save a plan whose exit criteria name a metric the server rejects (edit the payload in devtools) | The refusal renders **in the criteria panel it is about**, not as a corner toast and not swallowed. `exitCriteria` has no text field to land on, and a refusal the user cannot see is the failure the whole error path exists to prevent. | §97p5-137 |
| M3-7 | Edit `TP-1`'s **name only**, save, check the version; then move a case into a section and save | Unchanged, then moved. Prose and name are content; the version pins what will be run. Two saves, because each passes the other's check. | §97p2-91, §74-6 |
| M3-10 | With suites in scope, change **only** the name on the Plan tab and save | `plan_scope_item` unchanged, `caseCount` the same, and the `PATCH` carries **neither `suiteIds` nor `caseIds`**. ~~Scope checkboxes were pre-ticked on open~~ → the picker left with the dialog in §97 phase 5. An editor that sent `[]` for what it did not render would empty the plan silently, which is what this row guards. | §74-4, §97p5-130 |
| M3-11 | Open `TP-1` in two browser tabs, save in the second, then save the first | **409 "Someone else saved first"** in the page's own save report with *"Reload the plan…"* — the draft is compared against the seed, never the live query, so the stale `lockVersion` is the one that travels. `lock_version` had no `@Version` before §74, so it was `0` for ever and this could not fire. | §74-7 |
| M3-29 | `tcmsFaults.status(503, 30)`, edit Scope, Save | *"The plan was not saved"* **with the typed text still in the box and the banner still up.** Disarm afterwards. | §97p4-123 |

### The plan document

| # | Drive | Expect | src |
|---|---|---|---|
| M3-16 | `TP-2` → **Business requirements** | `?tab=requirements` in the URL; the empty state reads *"No page is linked to this plan"* and carries a **page picker**, *Link this page* and *Open documentation* — no Start-the-document button, the template `POST` is retired. Not a "Something didn't load" toast — the 404-to-null in `usePlanDocument` is all that stands between the first state and an error. **The picker needs a doc page to offer**, so author one in `/docs` first. | §93-45, §97p2-88, §97p4-128, §97p5-131 |
| M3-36 | Pick a page that is already another plan's document | It is **offered and disabled**, with the reason in the row. Hidden, it reads as deleted and the reader goes hunting in the tree. | §97p5-138 |
| M3-17 | ~~Edit a heading in place~~ → **obsoleted by §97 phase 5, which made the tab read-only.** Press **Open in documentation**, edit the heading there, come back | The tab shows the edited text and holds **no input of any kind**; `/docs` shows the same page in the tree. One page, one writer, two routes. | §93-47, §97p5-132 |
| M3-19 | On a linked page's bullet, click a case chip | It **navigates** to the case and there is no × on it: unlinking a case from a block is the documentation editor's affordance now, on the screen that owns the page. Two destructive-looking controls on one read-only tab is what phase 5 removed. | §93-49, §97p5-133 |
| M3-37 | Link a page, then press **Unlink (keeps the page)**; then open `/docs` | The tab returns to the picker and **the page is still in the tree with its blocks and history**. The label is the check: delete is one screen away and does something else. | §97p5-139 |
| M3-18 | ~~Type a title, pick a suite, **Add case**~~ → **deleted in §97 phase 5** with `useCreateDraftCase`. The idempotency question it existed for moved to the Test cases tab's per-row keys (`M3-41`). | — | §93-48 |
| M3-20 | ~~Rename the **Test cases** heading, then add a case~~ → **obsoleted by §97 phase 5**: nothing matches a heading by text any more, so there is no stranded control to look for. | — | §93-50 |

### The artefact register

| # | Drive | Expect | src |
|---|---|---|---|
| M3-30 | **Test artefacts** on a plan whose project has artifacts | The plan's register with project rows marked *from project* — the same `ArtifactTable` as `/artifacts`, so a broken inheritance marker breaks in one place. Register one at project scope as `lead` first. | §97p4-126 |
| M3-38 | **Test artefacts** → **Register**, with the plan pre-selected | The scope is `PLAN` and the plan is *this* one, and the registered row appears in the tab **without the plan's Save banner ever appearing**: the register writes itself. ~~the kind offers only `DOC_PAGE_REF` and `EXTERNAL_URL` (no upload control — the storage port does not exist)~~ → **corrected by §126**: the kind now offers **three**, and *A file to upload and keep here* is the third and last. Driving this row against the old wording would report a defect that is the feature. | §97p5-140, §126 |
| M3-39 | As **`engineer`** (`artifact:edit` = `PLAN_SCOPE`), open the same dialog from this tab | Register is **enabled** and the scope selector is pinned to `PLAN` — this tab can only ever register at plan scope, which is the one scope every holder of the key has. | §97p5-141 |

### The Test cases tab, and the sections that resolve live

| # | Drive | Expect | src |
|---|---|---|---|
| M3-31 | **Test cases** on `TP-1` with sections | Each section shows **what defines it** beside its count ("2 suites · 3 cases added directly · tags smoke"), and the header total carries the do-not-sum tooltip. | §97p4-127 |
| M3-41 | Type a title in the blank row | The next blank row appears **while you are still typing**, and the caret does not move. Then check the network panel: **no `POST`** — §93's bar created the case on the click, and the model changed to make that wrong. | §97p6-143 |
| M3-42 | Reload the plan without touching anything | **No unsaved-changes banner**, and navigating away does not prompt. The trailing blank row is permanent, so anything counting rows would flag every plan page in the product from the moment it rendered (§94's refine defect). | §97p6-144 |
| M3-43 | Add a section, type one title under it and one under *Not in a section*, Save; then check the payload and the case list | **Two `POST /cases` and one `PATCH`**, in that order. The `PATCH` carries the new ids in the group each was authored under — the sectioned one under `sections[].caseIds`, the other under the top-level `caseIds`. Both cases are in the repository **filed in no suite** (D2). **Each `POST` body carries `stepFormat`/`priority`/`automationStatus` as well as the title since §199** — before it they carried the title and `suiteId: null`, and every one of them was a 400. | §97p6-145, §199 |
| §199 PC-1 | Read the `POST /cases` bodies `M3-43` produced | `{title, suiteId: null, stepFormat: 'STRUCTURED', priority: 'MEDIUM', automationStatus: 'MANUAL'}` — the four fields `TestCaseInput` requires plus the filing. The three defaults are `NEW_CASE_DEFAULTS`, the same constant the case editor opens on, so a case authored from a plan row and one authored from an empty editor say the same thing before anybody has said otherwise. | §199 |
| §199 PC-2 | The response to those `POST`s | ~~**EXPECTED TO FAIL TODAY**… a `400` naming **`steps`**~~ → **superseded by §200, which took the first of §199's three arms: the create path defers the procedure.** Expect **`201`** for each, and a case in the repository that is `DRAFT`, filed nowhere, with **no steps**. Record the field errors if any `400` still comes back — after §200 there is no field on this payload the server can refuse, so anything at all is a defect neither the contract harness nor the backend suite could see. | §199, §200 |
| §200 PS-1 | Open one of the cases `M3-43` created, in the case editor, and press Save without adding a step | A `400` on **`steps`** — refused by the editor's own Zod rule before it reaches the wire, so the message lands on the step list rather than in a banner. **This is the asymmetry and it is deliberate**: the plan tab defers the procedure because it has no step field, the editor requires it because it has one. If this saves clean, the SPA has stopped being the place a stub gets finished. | §200 |
| §200 PS-2 | Add a step to that case, save, then approve it — then re-open it and check the health chips; separately, approve one of the stubs *without* adding a step | The finished case carries no `INCOMPLETE` chip. The stub approved unfinished **does** — this is the state `case_health.incomplete` was written for in §182 and had no producer for until §200. Note that the chip appears only after the health recompute runs, so this row may need the scheduled job or a manual trigger; say which was used. | §200 |
| §200 PS-3 | Select one of the stubs in the case list and run a bulk **Move to suite** on it | It **moves**, and is reported under *changed* — not under *skipped* with *"Open this case and save it once — it has content that must be completed first."* That skip is the dead end §200 closed: the message asks for a save the editor would refuse for the same missing steps. Pinned by `CaseBulkEndpointTest.aStubIsMovableLikeAnyOtherCase`, and this row is the same claim against the real screen. | §200 |
| M3-44 | Add a suite, a tag and an existing case to one section, Save, reload | Three chips, and the `PATCH` sent `suiteIds`/`tags`/`caseIds` — **references, never the cases they resolve to.** | §97p6-146 |
| M3-45 | With a section referencing `Cart`, add a case to `Cart` in another tab and reload the plan | The section's count **goes up with nothing written to the plan**. This is the live-selector claim (D7′) seen from the plan's own screen; `M3-5` is the same fact from the case side. | §97p6-147 |
| M3-5 | Note `TP-1`'s count, tag a case `smoke` in another tab, reload | The count **goes up with nothing written to the plan** — a section is a live selector, and the case side is the only place to see it. | §97p2-90, §97p3b-99 |
| M3-6 | `TP-1`: compare the total against its sections | The total is the **deduped union** of every arm; the sections' counts **exceed** it, because a case reached two ways is one case. A total equal to the sum is each arm being resolved separately. **The charter's overlap makes this non-vacuous** — build a section that names both `Cart` and the `smoke` tag over a case that is in both. | §97p2-89, §97p3b-100 |
| M3-46 | Note a section's count, then type anything on the tab | The counts **disappear** and an info alert says they are recomputed on save. A number that stays put beside a draft that has moved is wrong in the direction of the work just done. | §97p6-148 |
| M3-47 | Rename a section that has an **unsaved** case row under it, then Save | The row moves with the heading and its case lands **in that section**, not in *Not in a section*. `NewCaseRow.section` is the section's name, so the migration is the whole of what stops a case from silently leaving the group the author put it in. | §97p6-149 |
| M3-48 | Delete a section holding a suite and a tag → **Keep them in the plan**; then repeat with **Remove them from the plan** | First: the references appear under *Not in a section* and the plan's total is unchanged. Second: they are gone and the total drops. Two answers, both defensible, which is why the dialog asks. | §97p6-150 |
| M3-49 | Add a second section with the name of an existing one | **Save is disabled** and the banner says two sections share a name. Sections have no ids — the server keys them by name — so two of one name come back as one section holding the union of both. | §97p6-151 |
| M3-50 | Reorder two sections with ▲▼, Save, reload | The order persists. `position` is sent as the array order, and the end buttons are disabled rather than being no-ops nobody can see. | §97p6-152 |
| M3-51 | As a persona **without `case:create`** (`viewer`), open the tab | The new-case rows are **disabled with the reason**; the suite, tag and existing-case pickers still work and Save is enabled. §31 disables the section, not the Save. | §97p6-153 |
| M2-47 | Scope a plan on a suite and read its case count | The suite's own resolved membership. Naming a suite that used to have children reaches **only what that suite resolves** — the change §98 states out loud. | §98-11 |

### What the references catch — the live resolution

| # | Drive | Expect | src |
|---|---|---|---|
| M3-52 | `TP-1` → **Test cases**. Watch the network panel while the tab renders | Under each group's chips, a **list of the cases it currently catches** — key, title, and the reference(s) it arrived through. **One `GET /cases` per reference**: `?suiteId=…` for a suite chip, `?tag=…` for a tag chip, never one request carrying both. The references union; `?tag=` ANDs, so a single blended request would answer the intersection and read as *catches nothing*. | §103-154 |
| M3-53 | In a section holding a suite **and** the `smoke` tag, find a case that is in both | It appears **once**, with **both** references named beside it — `(Suite …, Tag smoke)`. Two arms, one row: a concat would list it twice, and naming only the first would make removing the other reference look like it should have dropped the row. | §103-155 |
| M3-54 | Point a section at **`Payments`** — a suite whose membership comes from a tag rule and not from filing | The listed cases are the suite's **resolved** membership — filed ∪ added ∪ tagged. This is the §96 defect's exact shape one screen over: a client-side `case.suiteId === id` filter lists the filed arm alone, under a count that is the union. **If the list is shorter than the count beside it, that is the bug.** `Payments` files nothing, so a filed-arm implementation shows an empty list under a non-zero count. | §103-156 |
| M3-55 | Take a tag chip off a group that also holds a suite chip | The rows that arrived **only** through that tag disappear immediately, the ones the suite also catches stay, and nothing is saved. Nothing on this screen remembers a case id — that is what keeps this a live resolution and not D7's add-time snapshot. | §103-157 |
| M3-56 | Add a suite to a group **without saving**, then read the caption above the list | The cases appear, and the caption reads *"What these references **would** catch … Nothing is in the plan until you press Save."* The served **count** beside the heading is hidden while dirty and the list is not — deliberately opposite, because the count is the server's arithmetic over the plan it holds and the list is computed from the draft. | §103-158 |
| M3-57 | A group whose only references are **individually named cases** | **No list at all** — no empty region, no caption. The case chips already draw those cases in full, and there is no by-ids read on the contract to resolve them with. Where a group has *both*, the caption says the named cases are not repeated below. | §103-159 |
| M3-58 | Point a section at `Unmatched automation` (empty) or a tag nothing carries | An empty state saying *"These references catch no cases"* — a by-design emptiness, not a failure and not a filter that found nothing. A blank space here reads as a list that failed to load. | §103-160 |
| M3-59 | Arm `tcmsFaults.status(503, 30)` **after** the tab has loaded, then take a chip off to force a re-resolve | The rows stay on screen with the **stale** marker and a *Try again*, not a blank list. Then reload with the fault armed: the region shows its own error **inside the group card**, and the chips, pickers and Save stay usable. | §103-161 |
| M3-60 | `tcmsFaults.hang()` and reload | The list shows its skeleton, then the timeout budget ends it with an error — **no region spins for ever**. The reads go through `useCases`' `interactive` budget. Disarm afterwards. | §103-162 |
| M3-61 | Point a section at a suite holding **more than 200 cases** | An info line above the rows: *"Suite X resolves to N cases — the first 200 are listed."* There is no all-pages read; a list that silently stopped at 200 is indistinguishable from a suite that holds 200. **The charter's 26 cases cannot reach this** — either bulk-create 200 in `MOB` or record the row as not driven and why. | §103-163 |
| M3-62 | With more than nine cases in one reference | The rows are ordered **TC-2, TC-9, TC-10** — numerically. `localeCompare` puts `TC-10` first, and with fewer than ten cases in the reference the defect is invisible. `Cart`'s nine is deliberately one short — use a reference that catches ten or more. | §103-164 |
| M3-40 | As **`viewer`** (`project:read` only), open every tab | Progress renders its numbers; the Plan tab's fields render **filled in and editable-looking with Save disabled and a reason**, never blank; Business requirements renders the page with Unlink disabled. A reader who came for the report must never be met by nothing. | §97p5-142 |

---

# A3 — execution: runs, results, evidence, and the approval gates

*Stage 5. Needs `A2`'s plans. **Ends by starting all three clocks** — do not leave the lane without
them, or `L4` stalls for a quarter of an hour twice.*

**Author a `PLAIN`-format case before this lane ends** (`M4-58` needs one), and put at least one
`smoke`-tagged case in a state where its steps can be half-marked (`M4-49`).

### Starting and ending a run

| # | Drive | Expect | src |
|---|---|---|---|
| M4-30 | New run → open the **Plan** select | A **"No plan — scope it below"** row above the plans, not a blank first option. A run against no plan is supported since §99, and a blank row reads as a field somebody forgot. | §99-1 |
| M4-31 | New run in **`MOB`**, which has no plans at all | The info alert says *"choose the suites, tags or cases this run covers instead"* and **Start run is enabled**. Before §99 it was disabled — the row exists because `toBeDisabled()` goes on passing against a client that never grew the arms. | §99-2 |
| M4-32 | Name it, choose **Suites → Payments** only, Start | 201, and the checklist is the suite's **whole membership** — including the cases filed under `Refunds` and `Card capture` that its tag rule claims. **`Payments` files nothing**, so a suite-arm implementation produces an empty run here and that is the defect. | §99-3 |
| M4-33 | Choose **Tags → smoke** only | The option list is the project's own tags **with a case count beside each**, and the run's length matches the count. Free text still accepted for a tag nobody has used. | §99-4 |
| M4-34 | Choose a plan, then add a tag | The plan select's helper text **changes** to *"Its scope is not used — the choices below are"*, and the created run is the tag's cases with `planKey` still shown on the run. The substitution rule is invisible in a form where four controls look equally live. | §99-5 |
| M4-35 | Choose a suite **and** a tag that overlap | One row per case on the checklist, no duplicates, and the total is the union rather than the sum. | §99-6 |
| M4-36 | Type a tag nobody uses, alone, and Start | 400 landing **on the Tags control**, not in the banner and not on Cases: *"This run would have nothing to execute…"*. Then add a suite that also matches nothing (`Unmatched automation`) → the message appears on **both** controls. | §99-7 |
| M4-37 | Press Start with the name filled and nothing else chosen | Refused **client-side** under the Cases control — no request goes out. | §99-8 |
| M4-39 | A project with **more than 200 cases**: open the Cases picker | The catalogue is the first page, so a case past it cannot be picked. Named as a known limit (`PlanCasesTab`'s, same cause); the row exists so the absence is not read as a bug. **Unreachable at 26 cases** — pair it with `M3-61` and drive both or record both as not driven. | §99-10 |
| M4-40 | Scope a run by a tag carried by a **`DEPRECATED`** case | **Record what happens — this row is a question, not an assertion.** §99 inherited a divergence here (the deleted mock's `resolveArms` excluded `DEPRECATED`, the server did not) and never resolved which is right. Half the comparison is gone; the server's behaviour is now the only behaviour, and nothing has ever written down what it is. Deprecate a `smoke`-tagged case, scope a run by `smoke`, and say whether it is on the checklist. | §99-11 |
| L0-2 | The run list, and open the run you just created | Every run **"Not started"**, `started_at` null. The chip is **coloured**, not the grey fall-through — `NOT_STARTED` was missing from the colour map and `PLANNED` (not a run status) was in it. Moved from `L0`, which had no run to open. | §72-1,2 |
| M4-1 | A run with no results → **Complete run** | **Disabled**, tooltip *"Nothing has been recorded in this run yet. Abort it instead."* Abort beside it is enabled. | §72-4 |
| M4-2 | Record one result, **Complete run** | 200, chip → Completed, and the Complete/Abort pair is **replaced by Reopen**, not joined by it. | §72-5 |
| M4-3 | On the completed run, open an execution | Four verdict buttons **disabled**, *"This run has ended, so its results can no longer change."* Attach and every Remove disabled with the same reason — **and the attachments still listed and still opening**. | §72-6 |
| M4-4 | **Reopen run** → record a result | Chip back to In progress, `completed_at` null, `started_at` **unchanged**, the result saves. | §72-8 |
| M4-5 | Abort a run that was never started, then Reopen it | Back to **"Not started"**, not In progress — the client asked for `IN_PROGRESS` and the server normalized. `started_at` still null. | §72-9 |
| M4-6 | Complete a run, then Abort from a stale tab | 409 *"Reopen it before recording a different outcome."* | §72-11 |
| M3-15 | `TP-1`, which now has runs → Delete → confirm; then open the run list | Dialog **stays open**: *"N test runs have been executed against this plan… Close the plan instead."* Not a corner toast — nothing on that screen predicted the refusal. Each run still shows its `TP-1` chip and the link still opens the plan. **Held back from `A2` deliberately**: until this lane there was no run to refuse on, and the row would have passed by deleting the plan. | §73-7,8 |

### Recording results

| # | Drive | Expect | src |
|---|---|---|---|
| M4-13 | Open an execution | Three-segment mode switch top right; **Split** default, queue rail beside the detail reading *Case N of 42*. ~~✅~~ — the §100 mark was a **mock-mode** result and is void; this is unrun. | §100-103 |
| M4-14 | Mark steps 1–3 Passed, step 4 Failed, leave step 5 unmarked | *Failed* gains a heavier border and a **suggested** label, caption *"A step failed…"*, **every other verdict still enabled**. Fires on a half-marked checklist — failure outranks completeness. | §100-104 |
| M4-15 | Look at a step's verdict keys | **P F B S**, fixed 30×24 squares. `statusLabels[kind].slice(0,4)` rendered "Bloc" four times per row. | §100-105 |
| M4-16 | **Grid** mode: mark an untested row Failed | The row's chip becomes Failed **and the page header's chip changes with it** — the write landed in the query key the detail screen reads, not a second copy. | §100-106 |
| M4-17 | Press **B** on a Grid row, tick a precondition, record it, then open that execution in **Focus** → Notes | A dialog (never a one-click write) listing **that case's own preconditions**; the reason prefills *"Blocked — these preconditions did not hold: …"*; the comment is **there** in Focus. `PATCH` sets `comment` to whatever it is sent and an omitted comment **clears** it. **Needs a case carrying conditions** — `A1` attached them, so scope this run to include one. | §100-107,108,109 |
| M4-18 | URL → `?mode=kanban` | Falls back to Split. An unrecognised value renders neither rail nor detail. | §100-110 |
| M4-19 | Open one execution in two tabs, record in the first, mark the same row in Grid in the second | *"Someone else recorded a result first"* **in place, on that row** — not a corner toast. Bulk marking is where overwriting a second tester is easiest. | §100-113 |
| M4-41 | Grid mode, click a row's **key** (`TC-…`) | The **test case** opens (`/cases/{caseId}`), not the execution. Before §104 the key went to the execution and the title was inert, so Grid mode could not reach a test case at all — and the two uuids on that row are the same shape, so a swap is a 404 nothing but a browser can see. | §104-1 |
| M4-42 | Back to Grid, hover the key | Tooltip *"Open test case TC-41"*. The affordance of a bare monospace key is not obvious; the tooltip is the sighted half of the accessible name. | §104-2 |
| M4-43 | Grid mode, click a row's **title** | That row's **execution** opens, on the pass/fail screen, and the address bar still carries **`?mode=grid`** — so Back returns to Grid rather than to Split. | §104-3 |
| M4-44 | Grid mode, key-only: Tab through one row | Two links before the verdict buttons, both reachable, focus ring visible on each. A title cell that ellipsises must not swallow the focus outline. | §104-4 |
| M4-45 | Grid mode with VoiceOver (or the accessibility inspector), read the row's links | Two **different** names: *"Open test case TC-41"* and *"<title> — record a result for TC-41 in this run"*. Two links to two places announced identically is the failure this pair exists to avoid, and it is invisible on screen. | §104-5 |
| M4-46 | Run detail (`/runs/{id}`), click the key, then the title | Key → case, title → execution, **no `?mode=`** (this grid is not a mode of the execution screen). The row proves the shared module did not move the grid that was already right. | §104-6 |
| M4-47 | Run detail, a case whose title is longer than the cell | Still ellipsised with the **full title** on hover. The tooltip there is a truncation affordance, not a destination one — the destination moved to the accessible name so the two do not compete. | §104-7 |

### Evidence

| # | Drive | Expect | src |
|---|---|---|---|
| M4-7 | Open a failed execution, **Attach** a PNG; then click the filename | 201 with a **rendered thumbnail** (not a broken image), correct size and your name. The filename opens the image **inline** from `/evidence/{id}/content`. | §67-1,2 |
| M4-8 | Delete the row **you** attached | Enabled; removing takes the row away. Attach three rows as three different people (`lead`, `engineer`, and one whose `created_by` is nulled by SQL) — that trio is `L1-1`, `L1-8` and `L1-17`'s fixture and nothing else builds it. | §67-3 |
| M4-9 | Attach a file **over 10 MB** | Refused **client-side**, sentence naming the limit, before any upload. | §67-8 |
| M4-10 | Record a result on a step, then attach with that step selected | The file lands **on that step**; the execution-level list stays empty. | §67-10 |
| M4-11 | Network tab on the attach | `multipart/form-data` **with a boundary**, no hand-set `Content-Type`. A lost boundary is a 400 that looks correct in devtools. | §67-11 |
| M4-12 | Attach the **same file twice in a row** | The second works — the input is cleared on change so re-picking an identical file re-fires. The first thing anyone tries after a failed upload. | §67-13 |
| L4-18 | Drag a step in a case that now has evidence or results against it | Steps reorder and the **evidence stays with its step**, not the position (`step_result` FKs to `test_step.id`). If this were ever false it would be found by a tester reading someone else's evidence under the wrong step. Kept here rather than in `A1` because it needs this lane's evidence. | §94-61 |

### What failed

| # | Drive | Expect | src |
|---|---|---|---|
| M4-48 | A run with failures — the **What failed** panel's headline | *"N cases failed · M blocked"*, with N and M matching the Result bar above. **Blocked must not be added into the failed number**, and the blocked list carries its own sentence (*"nobody could get far enough… Not counted as failures"*). The chip above merges them on purpose; this panel must not. | §105-1 |
| M4-49 | A failed case whose tester **stopped at the failure**, and another whose checklist is fully marked | *"1 of N steps failed · K not marked"* — three numbers on the first, and **no *not marked* clause at all** on the second. A build that counted unmarked as passed shows the second correctly and the first wrongly, so **both shapes must exist in the run** — record them deliberately, the fixtures no longer do it for you. | §105-2 |
| M4-50 | Read the failed step rows | The step's **1-based** position, its keyword + action in the failed colour, and the step comment beneath where one exists. `position` is 0-based on the wire, so a row reading *"Step 0"* is the off-by-one. | §105-3 |
| M4-51 | Click the key on a row in the panel, then the title | §104's pair, third call site: key → the test case, title → the execution. If these behave differently from the Executions grid below, the shared module has been bypassed. | §105-4 |
| M4-52 | A run where **nothing failed and nothing is blocked** | *"Every case in this run produced a result and none of them failed"* — good news in words, **not** an empty table and not a spinner. The panel still renders, because a missing panel cannot be told apart from one that failed to render. | §105-5 |
| M4-53 | The same, on a run that still has **Not run** cases | The wording changes to *"Nothing has failed so far — but the run is not finished, so this is not the final answer."* Declaring a half-executed run clean is the claim this branch exists to refuse. | §105-6 |
| M4-58 | A failed case with **no structured steps** (the `PLAIN`-format case authored above) | *"No structured steps — the verdict is on the case as a whole."* Never *"0 of 0 steps failed"*, which reads as a case whose steps all passed while the case failed. | §105-11 |
| M4-56 | A run with **more than 25 failed cases** | Every failed case is listed; the caption says step detail was read for the first 25 and names how many were not. **26 cases in `OLB` cannot produce 25 failures** — either build the run in a bulk-created project or record it as not driven. | §105-9 |
| M4-57 | Open a failed case from the panel, mark a step differently, come back | The panel's step detail **moves** — it reads `keys.execution(...)`, the same key the execution screen and Grid mode use, so no second copy of an execution exists in the cache. | §105-10 |
| M4-54 | `tcmsFaults.only('/executions')` then `tcmsFaults.status(503, 30)` and reload a run with failures | The **case list still renders** (it needs no request) and the step layer shows `RunDetail.Failures.Steps.Error`. The forbidden outcome is a panel that silently reports no failing steps because the reads 500'd — an emptiness where there was a failure. | §105-7 |
| M4-55 | `tcmsFaults.hang()` scoped to `/executions`, reload | *"Reading where each case failed…"* above a **fully rendered list of failed cases** — the region wraps the step layer only. If the failed cases disappear while the steps load, the region has been wrapped around the wrong subtree. Disarm afterwards. | §105-8 |

### The approval gates

| # | Drive | Expect | src |
|---|---|---|---|
| M4-20 | Settings → Review: toggle **Only approved test cases can go into a run**, Save, reload | Still on. The **whole-body save** is the risk: this form `PUT`s all eight settings and the server reads an absent boolean as `false`. | §82-1 |
| M4-21 | With gating on, change **only** the peer-review number and Save, reload | Both gating switches **still on**. The silent-discard defect in the form it would take: an admin editing an unrelated field quietly reopens the gate, and nothing says so. | §82-2 |
| M4-22 | Turn a gate on, then both off | The amber warning appears while either is on and disappears when both are off — §6g requires the choice to be explicit and this is where "explicit" lives. | §82-3 |
| M4-23 | With cases gating on, start a run from a plan whose cases are drafts | **409 in the dialog's banner**, naming the count and the first few keys — not a toast, not a silent no-op. Read the sentence as somebody who did not configure this: does it say what to do next? | §82-4 |
| M4-24 | Same, as a caller **holding `case:approve`** (`lead`); then edit the run name and press it | **Create it anyway** under the refusal, enabled — §6g's documented override, and why a 409 here is not a dead end. The retry re-reads the form, so the run gets the **edited** name; a replay would create a run the user is not looking at. | §82-5,7 |
| M4-25 | With plan gating on, press **Start** on a draft plan; then press **Submit for review** and let it be refused | 409 beside the buttons with **Start it anyway** — sentence naming review as the way forward. On the submit refusal, **no override button**: the gate can only refuse a start. | §82-8,9 |
| M4-26 | Approve a plan properly, then Start it; then create a run with **no** unapproved cases | Starts normally, no refusal, no override. Created normally with **no mention of the gate anywhere** — a gate that announces itself when satisfied is the noise that gets it switched off. | §82-10,15 |
| M4-27 | ⚠ Start a plan, Close it, **Reopen** it with plan gating **on** | **Probably unreachable — see `A2`'s box.** If Close and Reopen exist: **it reopens**, because a target-keyed reading makes every closed plan permanently un-reopenable, showing a Reopen button that 409s for ever about approving a plan that cannot be approved. If they do not, record that and strike the row. | §82-11 |
| M4-38 | With case gating on, scope a run by a **tag** that catches draft cases | 409, the same refusal as `M4-23`. §82's bypass with a new spelling: the gate binds on the resolved union. | §99-9 |
| M4-28 | Switch **all three review opt-ins off**, leave a gate on, repeat `M4-23` and `M4-25` | The gates **still fire** and no review panel renders. Gating reads the status ladder; review's opt-in suppresses the apparatus. A build where they are coupled looks identical until this row. **`A4` starts by switching plans back on — this row is why `M5-1` is not a no-op.** | §82-12 |
| M4-29 | Gate on for `OLB`, repeat `M4-23` in `MOB` | `MOB` unaffected. The read is `useProjectSettings(projectId)`; a cache keyed too broadly leaks one project's policy into the other. | §82-13 |

### CI ingestion — the history and the binder (§119)

*Still A3, and deliberately at the end of it: these rows need real cases (`A1`) so a report can
match something, and they leave two ingestion jobs and one confirmed mapping behind.*

**They need a live credential and `A0-15` revoked the one `A0-11` issued.** Issue a second one
first — `Ingestion pass`, role **Automation Service**, no project, no expiry — from
`/admin/tokens`. That is a deliberate dependency and not a gap: it re-drives §118's issue path a
second time, which is the only thing in this file that would catch a screen that mints one working
credential and then stops.

| # | Drive | Expect | src |
|---|---|---|---|
| A3-4 | The rail's **Execution** group → **CI ingestion** on `OLB`, before posting anything new | The item sits **beside Test runs** and is enabled for every persona. The history shows **one row** — `A0-12`'s report, posted before any case existed. Its Run cell reads **"No run"** and *not* a dash: nothing matched, so no run was opened, and a run of zero cases would have reported 0 of 0, 100%, complete. Matched **0**, Unmatched **1**. | §119-1 |
| A3-5 | Open that row (the timestamp is the link) | The job screen, with the blue *"Nothing in this report matched a case, so no run was opened"* alert, and one unmatched row naming the test **as the report wrote it** — mixed case intact. **This is the screen that makes declining §6c's auto-created case stubs defensible**; before it, the only way to read this list was `curl`. | §119-2 |
| A3-6 | `curl` a JUnit report into `OLB` with two `<testcase>`s: one named `refunds in full [<TC-key of a real case>]`, one named `com.acme.GhostTest#nothing names this`, with a fresh `Idempotency-Key` | 201. The history gains a row: Matched **1**, Unmatched **1**, and a **`TR-n` link** — follow it and the run holds one execution against that case with the reported verdict, `Executed by` **blank** (the verdict was the machine's, not the token holder's). The first row still reads "No run". | §119-3 |
| A3-7 | Re-post the **same** report under the **same** `Idempotency-Key` | 200, not 201, and the history is **still two rows**. Read the response body: `unmatched` carries the **name**, not an empty array beside a count of 1. That was the shape before §119 read the payload back, and the retry is the caller that most needs it. | §119-4 |
| A3-8 | On the newest job, press **Bind** on the ghost test → pick a case by key → **Bind** | The dialog names the test **read-only** and posts it **raw**; it closes on success. **Check the request body in the Network tab**: `testName` must be exactly what the row shows, mixed case and all. A client that lower-cased it would look identical on screen and would break every mapping the day the two rules diverge — the Network tab is the only observer. | §119-5 |
| A3-9 | Back on the history screen, read **Test name mappings** | One row: the test in the mono font **normalized by the server** (lower-cased, whitespace collapsed) beside the case key it now means. The name in this table is deliberately not the one the previous row typed — that difference *is* the server's rule, visible. | §119-6 |
| A3-10 | `curl` the same report a third time with a **new** `Idempotency-Key` | 201, and now **Matched 2, Unmatched 0** — the mapping placed the test that had matched nothing. §6c's third ladder rung, end to end through the product. | §119-7 |
| A3-11 | As `engineer` (holds `case:read`, not `case:edit`), open the same two screens | Both **render in full** — the history is membership-gated, and hiding it would tell a Test Engineer this instance has no CI integration. **Bind** and **Remove** are **disabled with a reason on hover**, never absent (§31). | §119-8 |
| A3-12 | As `viewer`, open `/projects/{OLB}/ingestions` | The history renders. The mappings panel is the one to watch: `case:read` is what it costs, and whatever the server answers must render as data or as a refusal — **never as a global "Something didn't load" over a working screen**. Record which it does; if it is a refusal, that is a finding about the panel's gating and not about the endpoint. | §119-9 |
| A3-13 | Delete the case the mapping points at (as `lead`), then re-read Test name mappings | The row is **still there**, its Case cell reading **"Deleted case"** with the tooltip. It still consumes the test name, so every build still resolves that test to a case that is gone — filtering it out would hide the only thing that can be removed to fix it. **Remove** it, and confirm the dialog says results already recorded stay where they are. | §119-10 |
| A3-14 | `psql`: `SELECT count(*) FROM ingestion_job WHERE project_id = '<MOB>'`, then open `MOB` → CI ingestion | **0**, and the screen shows the empty state naming the endpoint and the API-token screen — the only place this product ever says how to start posting. A project's history must not show another project's jobs: the read is gated on `projectId` over RLS, and this is the browser-lane half of `IH-1`. | §119-11 |

#### Token attribution — whose name is on the verdict (§132)

*Two rows, same lane and same fixture: they read what `A3-6`'s post left behind. They are the only
place in the file where the product says a **credential** did something.*

| # | Drive | Expect | src |
|---|---|---|---|
| A3-15 | Open the case `A3-6` matched → **Execution history**, and read the caption under the ingested entry | **`ingested by Ingestion pass`** — the token's own name, the one typed into `/admin/tokens`, not `CI` and not a uuid. Before §132 the column had no writer and this read `ingested by CI` for every credential on the instance, so a reader could not tell which pipeline posted a result or whose credential to revoke. | §132-2 |
| A3-17 | After `A3-15`, open `/admin/tokens` as `admin` and read the new **Last used** column | The `Ingestion pass` row carries a **date-time**; every other row reads **"Never used"** as a sentence, not a blank. That column did not exist before §132 and could not have: the field was on the wire and always null, so it would have been empty on every row of every deployment. **Not a relative phrase** — the value is kept only to within an hour, and "3 minutes ago" would claim a precision it does not have. | §132-4 |
| A3-16 | With the same credential, `curl` a **single result** into an existing **manual** run (`POST …/runs/{id}/executions/{execId}/result` — Automation Service holds `result:submit`), then open that case's history | The new entry reads **`recorded by Ingestion pass`**, not `by <the token owner's display name>`. **This is the row that catches the defect §132 phase 2 found**: the caption used to branch on the *run's* source, which is a property of the run, while the actor is a property of the verdict — so a credential posting into a manual run printed a **person's name on a machine's claim**. No backend test can see it; both fields are correct and the branch is in the SPA. | §132-3 |

**One row this pass cannot drive.** A **capped** unmatched list (`Ingestion.Capped`, "Showing the
first 50 of N") needs a report with more than 50 unmatched tests. Generating one is a `curl` with a
scripted body rather than a browser action, and it is left out rather than faked: the alternative is
lowering the cap for the pass, which would verify a constant nobody ships. The unit test covers the
banner; what is unverified is the real 50-name response.

### Before leaving this lane — start the three clocks

| # | Drive | Expect | src |
|---|---|---|---|
| A3-1 | Pick one case and record **`Failed`** against it in **three separate runs** (the unique constraint forces one run each) | Nothing yet. This is `L4-23`'s seed and the health scheduler is `PT3M` then `PT15M` — seeded now, the tick has happened by `L4`. | §86 |
| A3-2 | As `lead`, generate one report (`A6`'s first) | A card at Queued. The worker is on the same clock; `L4-30` is about the card sitting there for **minutes**, so start it and read it in `A6`. | §87, §89 |
| A3-3 | Record a result against `TP-1`'s run, then **immediately** read `L4-40` in `A2`'s Progress tab before any tick | See `L4-40` — it is the only row in the file that must be read *before* a scheduler tick, and once the tick has run it cannot be produced again without a new plan. | §101 |

---

# A4 — the review apparatus: settings, panel, comments, requests, inbox

*Stage 6.* **Run `M5-1` first.** §77 changed the default to "review nowhere", and `M4-28` has just
switched all three opt-ins off — so nothing in this lane exists until review is switched back on.
**`L0-7` was the only chance to see the off state and it has passed.**

**The two-account review rows moved here from `L4`** (`L4-8`…`L4-16`). They need two people and
nothing else; the old placement put them behind two scheduler waits for no reason.

| # | Drive | Expect | src |
|---|---|---|---|
| M5-1 | Project → **Review** in the rail. Turn on **Test plans only**, Save | 200, "Last changed … by you". A **plan** now has the panel; a **case** still has nothing. The opt-in is per artifact type — this is the row proving the map is not read as any-of. | §77-2 |
| M5-2 | Set **Peer reviews required** to `21`, then `-1` | Red with *"Between 0 and 20."* **before any request**, Save disabled. `V23`'s CHECK is the backstop behind both. | §77-5 |
| M5-7 | Change one switch, press **Discard changes**; then change one, navigate away and back | Back to the server's values, Save re-disabled; the draft is gone on return. Nothing here is autosaved. | §77-17 |
| L0-6 | Submit a plan for review, then read the panel on a **different** plan | The panel reads **"Not in review"**. `psql`: `SELECT count(*) FROM review` is now 1, not 0 — the record starts here. Moved from `L0`, which had no artifact in review and no artifact not in review to contrast it with. | §76-1 |
| M5-8 | Submit `TP-2` for review, open the panel | Chip **In review**, "Submitted by *you*", version pinned (`v1`), empty trail, and **no Approve button on the panel**. `psql`: one `review` row, `subject_version_no` = the plan's `current_version_no`. | §76-2 |
| M5-9 | Type a comment, press **Looks good**; then **Request changes** with a different comment | 201, trail gains *Looks good · your name*, the comment renders under it, **the box clears**, plan **still In review** — a peer verdict moves nothing. Then **both rows are there, oldest first**: participations are append-only, and a panel that replaced the first would hide somebody changing their mind. | §76-3,4 |
| M5-10 | As `lead`, approve the plan from its own screen, re-read the panel | State **Approved**, "Signed off by *you*" with a timestamp, an `APPROVAL / Approved` row ending the trail, and **the verdict form gone** — not merely disabled. | §76-6 |
| M5-11 | With a review open, edit the artifact so it writes a version, re-read the panel | *"…has changed since it was submitted — the review covers v2, and you are looking at v3."* The version on the record must **not** move. | §76-9 |
| M5-12 | Withdraw and re-submit the same artifact twice | Newest review on top, an **"Earlier reviews"** list beneath, each with its own state and version. `psql`: exactly one row `state = 'IN_REVIEW'` per subject. | §76-10 |
| M5-13 | Have the review approved in another tab (as `lead`), then press **Looks good** in the stale one | **409 in place, inside the panel** — *"This review has already ended. Submit the plan for review again to reopen it."* Not a toast; the trail refreshes rather than the page. | §76-11 |
| M5-3 | Raise the minimum from 1 to 5 **while a review is open**, then approve it; then submit a new plan | The open one **still approves** (the count is pinned at submission, so goalposts do not move under reviewers already asked); the new one needs 5. Put it back to 1 afterwards — `L4-11` and `L4-12` read it. | §77-10 |
| M5-4 | With **Keep the approval** (RETAIN): approve a plan, rename it. Then switch to **Return it to draft** (RE_REVIEW) and repeat | RETAIN: status stays **Approved** with §76's staleness warning if the edit wrote a version. RE_REVIEW: status becomes **Draft**, and the warning Alert on the settings screen must have been visible before saving. | §77-11,12 |
| M5-5 | Turn review **off** for plans while a plan sits In review | The panel disappears; the status chip **still reads In review**; `psql` shows the `review` row **still `IN_REVIEW`**. Switching the policy off does not withdraw submissions on anyone's behalf. Turn it back on. | §77-14 |
| M5-6 | Turn review off entirely, then move a case Draft → In review → Approved from the bulk dialog | Every move **succeeds**. The ladder is not the apparatus — the alternative reading silently removes approval from every existing project on upgrade day. Turn plans back on. | §77-15 |
| M5-14 | Bulk-submit ~20 cases from the case list (turn case review on first), open two | **Each has its own open review**, pinned to that case's own version. The bulk path is the common case and the one place a per-row write could silently do nothing. | §76-13 |

### Comments

| # | Drive | Expect | src |
|---|---|---|---|
| M5-16 | On a plan in review, write a comment, press Comment; then **Reply** on that thread | Appears at the bottom attributed to you, box cleared, `parent_id` NULL. The reply lands **under** the root and the reply form closes; `parent_id` = the root's id. | §79-3,4 |
| M5-15 | Build the two shapes `M5-15` used to find in a fixture: one thread with a reply, one resolved | The replied thread is **indented** beneath its root; the resolved one is greyed reading **"Resolved by …"** with **Re-open**. The reply has **no Reply button** — threads are one level deep and the server refuses a reply to a reply. | §79-1 |
| M5-17 | **Resolve** a thread, then **Re-open**, then Resolve twice in a row (or from a second tab) | Greys out reading "Resolved by *you*" with `resolved_at` **and** `resolved_by` set; Re-open clears **both together**; the second Resolve leaves timestamp and resolver **unchanged**. The unconditional write shipped in the first draft and hands the record to whoever clicked last. | §79-5,6,7 |
| M5-20 | Paste 5001 characters; then clear the box | Refused **in place beside the box** (*"Keep the comment under 5000 characters."*); the button disables on empty rather than letting the server refuse a blank. | §79-10 |
| M5-21 | A comment containing a blank line and a list | The paragraph break survives (`pre-wrap`). What a reviewer typed is part of what they said. | §79-11 |
| M5-18 | Approve or reject the plan, re-open it | The conversation is **still readable** and there is **no comment box, no Reply, no Resolve**. A closed review takes no writes; the affordance's only outcome would be a 409. | §79-8 |
| M5-19 | A review with **no** comments, open then closed | Open: *"No comments yet."* with the box. **Closed: the whole section is absent**, not an empty heading. | §79-9 |
| L4-10 | Comment as `lead`, resolve as `engineer` | The thread names **two different people** — author on the remark, resolver on the resolution line. The pair is why `V24` added the actor column. | §79-13 |

### Who was asked

| # | Drive | Expect | src |
|---|---|---|---|
| M5-23 | On a plan in review, open the reviewer picker | Project members and teams (suffixed "(team)"). **The submitter is absent**, and so is anybody already asked — the first is a server field error, the second a write that does nothing. | §80-5 |
| M5-24 | Pick a person → **Ask**; then **Payments Squad** → **Ask** | A chip and a cleared select; `review_assignment` with `assignee_user_id` set, `assignee_team_id` NULL, `assigned_by` you. The team chip carries the **group** icon and the team's name, **no member names** — expanding would freeze membership as it was at page load. | §80-6,7 |
| M5-22 | Read **Asked to review** above the trail, having asked two people and a team, with only one of them having spoken | Three chips — two people and a **group** icon on the team — and at least one appearing in **no** verdict row. **Compare the two lists deliberately**: a name asked and not in the trail is the row a lead opens this panel to find, and if the lists have been merged the slice's premise is gone. | §80-1,2 |
| M5-25 | Press a chip's **✕**; then ask the same person twice (second tab) | Gone immediately, no confirmation, and the row is **deleted not tombstoned** — an assignment is a worklist entry. The duplicate ask changes no count and the original **"asked by"** survives; re-stamping is §79's resolution defect recurring. | §80-8,9 |
| M5-26 | Approve or reject, then re-open | Chips **still readable**, **no picker and no ✕**. | §80-10 |
| M5-27 | A plan in review nobody was asked about, open then closed | *"Nobody has been asked yet. Anyone with review rights can still give a verdict."* — and on a **closed** review with no requests the section is **absent**. | §80-11 |
| L4-8 | Delete a team that has an outstanding review request | The chip is gone on the next load and **the person asked individually is untouched**. The decision §71's 409 is deliberately *not* extended to — what cascades is a request, not authority — and the DDL is the only thing enforcing it. Use a throwaway team, not Payments Squad. | §80-13 |

### The verdicts two accounts make possible

| # | Drive | Expect | src |
|---|---|---|---|
| L4-9 | Submit as `lead`, **send it back as `engineer`**; then submit as `lead` and **withdraw as `lead`** | **"Sent back"** with a trail row `APPROVAL / Sent back` naming `engineer`; then **"Withdrawn"** with **no verdict row added**. The same ladder edge, and they must read differently — this is the pair `V22` added a state for. | §76-7,8 |
| L4-11 | Peer minimum 1: have `engineer` say **Looks good**, then approve as `lead`. Then repeat with `engineer` saying Looks good → **Request changes** → approve | First succeeds. Second **refused** — *"…needs 1 peer review … and has 0."* The latest verdict is what counts and the trail shows both. **The single most valuable row in the lane**: the cheap implementation passes the first perfectly and fails only this. | §77-7,8 |
| L4-12 | Author rule **off**: press Looks good as the submitter, then approve | Still refused on the peer minimum — *"has 0"*. The submitter's own LGTM is not a second opinion, and the two settings are independent. | §77-9 |
| L4-13 | Ask `engineer` to review, sign in as them, open the board | A Review card for that artifact on **their** board naming `lead` as the asker. **The row §81 exists for**; §80's row 14 recorded its absence. | §81-6 |
| L4-14 | Ask **Payments Squad**, sign in as any member; then **add `viewer` to that team** and reload their board | A card each for every member, none of them named on an assignment row; the new member's card is waiting. Expansion happens on the **read** — write-time expansion would have frozen the list at the moment somebody clicked. Remove `viewer` again afterwards. | §81-7 |
| L4-15 | As the person asked, leave a verdict (LGTM **or** Request changes), reload the board | **The card is gone** — on "changes requested" too, deliberately: they have done what was asked and the ball is with the author. A card that stays until they approve reads as work the reviewer still owes. | §81-8 |
| L4-16 | Approve or reject the artifact, reload every asked person's board; then soft-delete a case with an open review somebody was asked for | All cards gone though the `review_assignment` rows are untouched — the board reads `review.state`, and unassigning is the only thing that deletes a request. The deleted case's card disappears; nothing in the schema removes the review, because the subject triple has no FK. | §81-9,10 |

**Leave at least one review request outstanding when the lane ends** — `A5`'s board rows (`M5-28`,
`M5-29`, `M5-30`) are the cards it produces, and `L4-16` clears them if it is the last thing run.

---

# A5 — the frame: rails, boards, and the way back

*Stage 7, and it is seventh rather than first.* In the mock pass this lane ran before everything
because the fixture store already held cards. **Live, every card on every board is derived** — from
a review request (`A4`), an execution (`A3`) or a health signal (whose tick has not fired yet) — so
run against three empty columns this lane would have checked five card behaviours and passed.

Most of the repaired defects here were *"the route's project and the card's project happened to be
the same value"*, which is invisible from a single project — hence `MOB`.

| # | Drive | Expect | src |
|---|---|---|---|
| M1-1 | Look at the rail before touching anything | **My work**, then **All projects**, then the **Project** switcher, with a divider above the switcher. A personal board listed inside the project frame says switching projects changes it. | §83-1, §91-35 |
| M1-2 | Click **My work**, then switch projects | The board **does not change**; the tab title stays "My work". | §83-2 |
| M1-3 | Read My Work's cards as `lead` (granted on both projects) | Cards from **both `OLB` and `MOB`**, each stamped with its own project key. **Create a work item in `MOB` first if `A2`/`A3` left none** — one project means the aggregate quietly became one project's board, and every unit test still passes. | §83-3 |
| M1-4 | Click the key on a **`MOB`** card, then on an `OLB` review card (case, then plan) | A `MOB` URL that resolves — **check the address bar**. Case → case detail with the review panel; plan → plan detail. `linkFor` took the board's project before §83 and has needed repair in three slices. | §83-4,5, §85-6 |
| M1-5 | Drag a **task** card on My Work, then open that project's board **and** a team board | The card is in the new column on **all three**. The move invalidates the shared `work-items` root; a per-board key leaves two screens showing a card in the column it just left, and neither looks wrong alone. | §83-6, §85-7 |
| M1-6 | Drag a **derived** card (execution or review) on any of the three boards | Refused in place, sentence naming what to update — *"TC-20 mirrors a review, so it can't be moved to Done."* Identical on all three: one component. Moving must not perform the approval. | §83-7, §85-8, §81-4 |
| M1-7 | On My Work and on a team board, look for the **Everyone / Assigned to me** chips and **New task** | **Neither is present.** The chips are a deliberate 400 ("Choose a board"); the button needs a project these screens do not have. Render-then-reject. | §83-8, §85-9 |
| M1-8 | URL → `/projects/<nonexistent-uuid>/board` | An error state, **not another project's board**. A fallback to the first project renders a stale bookmark's cards under a foreign id. Only reachable by typing a URL. | §83-13 |
| M1-9 | Sign in as `newbie` and go straight to `/my-work` before visiting any project | It renders. Must not depend on `useProjects` — a member with no grants has this board and nothing else. (`L4-36` is the fuller version of this.) | §83-14 |
| M5-28 | The project **Board**, with `A4`'s outstanding requests | Review cards in **To do** — a plan and a case — marked `linked`, reading *"Review requested by …"*. **Not** in In-review: To do is where work nobody has started belongs. The old generator drew a card per `IN_REVIEW` case, in the In-review column, assigned to the case's **owner** — a reviewer inbox shown to the person under review. If any row reads like that, it is the regression. | §81-1 |
| M5-29 | Click the key on the plan card, then on the case card | Plan detail with the review panel, then case detail with its panel. One card shape, three subject types, three destinations. Before §81 every card routed to the case list, so a plan review searched the cases for its key and rendered "no cases match". | §81-2,3 |
| M5-30 | Switch the board to **Assigned to me** | The review cards stay. This is the inbox as people will use it. | §81-5 |
| M1-10 | Teams & roles → any team row | A **Board** link on every row, **not greyed** even when Edit/Members/Delete beside it are. It is the one affordance there with no permission behind it. | §85-1 |
| M1-11 | Board on **Payments Squad**, then on **Checkout Squad** | `/teams/<uuid>/board`, title "Payments Squad — board". Cards from **more than one person** and from **both projects**. Checkout Squad shows a **different set** — `viewer`'s and `newbie`'s work, not `lead`'s. **This needs cards assigned to people in each team**, which is why the two teams' membership is disjoint in the charter: two teams showing one board passes every test written from one team. | §85-3,4,5 |
| M1-12 | URL → `/teams/<not-a-team>/board`, then press **Back to teams** | **"No such team"** with a working *Back to teams* → `/admin/teams`. Not an empty board, not "Not found — Not Found": the server's 404 is empty-bodied so the sentence is the client's. | §85-10,11 |
| M1-13 | Create a team with no members, open its board | The **empty state**, visibly different from `M1-12`'s error. The two answers this screen exists to keep apart. | §85-12 |
| M1-14 | Two or three screens deep, click **All projects**, then the **TCMS** wordmark | Both land on the picker. `All projects` is marked current **there and on no other screen**. | §91-34 |
| M1-15 | Collapse the rail; hover each icon, then tab through with the keyboard | 64px, content reflows. Tooltips carry the label **on keyboard focus, not only hover**. Group dividers stand in for the headings. The active item keeps its indicator bar. | §91-36 |
| M1-16 | Collapsed: click the project-key button; reload; expand and reload | The menu lists every project, current one selected, navigation works. **The collapsed state survives both reloads**, and the rail does **not** render expanded for a frame first (read in initial state, not an effect). | §91-37 |
| M1-17 | Collapsed, walk case list · case detail · plan statistics · board · docs · reports | Nothing reflows badly; no screen assumed a 232px rail. The DataGrid pages are where a width change shows. | §91-38 |
| M1-18 | Keyboard: tab from the top; then narrow below `md` and open the hamburger | Skip link → **My work** first → switcher. Active state marked by bar and weight, not colour alone. The drawer holds the same items in the same order and closes on tap; a row in one and not the other means the drawer took a different path. Team-board columns stack at `xs`. | §83-15,16, §85-18,19 |

### The drag

| # | Drive | Expect | src |
|---|---|---|---|
| M1-19 | Grab a task card's **drag handle** and drop it in another column | The card follows the cursor (an overlay copy; the original stays and fades), the target column outlines, and on release it is in the new column **immediately** — no beat of latency, no snap-back-then-jump. §106 replaced the browser's native HTML5 drag with `@dnd-kit`, so this is the whole gesture re-implemented rather than a tweak. | §106-1 |
| M1-20 | The same drag **on a tablet or with touch emulation on** | It works. Native HTML5 drag has **no touch support at all**, so before §106 a board could not be reordered on the device it is most likely to be read on, and nothing on screen said so. | §106-2 |
| M1-21 | Tab to a card's drag handle, press **Space**, then **←/→**, then **Space** | Space lifts the card, each arrow moves it **a whole column** (not 25 pixels), Space drops it. Arrows at either end of the board do nothing rather than wrapping round. `@dnd-kit`'s stock getters cannot do this — `sortableKeyboardCoordinates` needs a `SortableContext` the board has none of. | §106-3 |
| M1-22 | With the same card lifted, press **Escape** | The drag cancels and the card stays where it was. No request goes out. | §106-4 |
| M1-23 | Drag a card and drop it **back in the column it started in**, and again **outside every column** | Nothing happens either time — and check devtools: **no `PATCH`**. §94's `move(i,i)` defect in its board spelling; here it would be a write, a version and an audit row for a gesture that changed nothing. | §106-5 |
| M1-24 | Try to drag a **derived** card (execution, review, maintenance) | It does not lift at all — no handle is rendered on it. `ui-rules`: not a gesture that lifts and is then refused. The refusal is still discoverable on the disabled "→ column" buttons' tooltips, which name that card's **own** source. | §106-6 |
| M1-25 | While dragging a task, look at the columns it may not enter | They dim and do not highlight, and they will not accept the drop. The legal ones tint and outline under the pointer. Not colour alone: the dimming and the buttons' words carry it too. | §106-7 |
| M1-26 | `tcmsFaults.status(409)` scoped to `/work-items`, then drag a card | The card lands in the new column **first**, then returns to the one it came from, and a **persistent** warning above the board carries the server's sentence. Not a toast alone: a toast about a card that has visibly snapped back is gone before the eye returns to it. | §106-8 |
| M1-27 | Same, with a screen reader running | The failure is **announced** — "…did not move. …". The snap-back is otherwise completely silent, which is the one state a sighted user gets for free and a screen-reader user does not. | §106-9 |
| M1-28 | `tcmsFaults.hang()` scoped to `/work-items`, drag a card, then drag it again immediately | The first drop moves it optimistically; the second is a move from the new column, not a repeat of the first. Watch for **two identical PATCHes** — a card that looks like it did not move is the thing users re-drag. Disarm afterwards. | §106-10 |

### The quarantine card — §6l's fourth generator (§121 phase 3)

*The generator has been on by default and silently empty since the board shipped; open question #24
said it would have no data until M3 ingestion existed, and §117 removed that. There is still **no
screen that quarantines**, so the fixture for this block is authored from the console — which is the
same shape `M6-1`…`M6-8` use, and it is what makes these rows drivable before phase 4 exists.*

```js
// `P` is the project id from the URL; `C` a case id from the case list. As `lead`.
const H = { 'Content-Type': 'application/json',
            Authorization: 'Bearer ' + localStorage.getItem('tcms.token') };
await fetch(`/api/v1/projects/${P}/cases/${C}/quarantine`, { method: 'PUT', headers: H,
  body: JSON.stringify({ quarantined: true, reason: 'Flaky on Firefox, races the login redirect' }) });
```

| # | Drive | Expect | src |
|---|---|---|---|
| M1-29 | Quarantine a case with the snippet above, then reload the project **Board** | A card in **To do** labelled **Quarantined**, `linked`, with the case's key, reading *"Quarantined by L. Lead — Flaky on Firefox, races the login redirect"*. **To do, not In review** — nobody has started clearing the debt. | §121-14 |
| M1-30 | Read that card's sentence **character by character**, commas included | It renders **exactly as written**. This is the whole reason `QUARANTINE` is its own `kind`: a `CASE_MAINTENANCE` description is a comma-separated list of raw `HealthSignal` codes that the board splits and labels code by code, so filed under that kind this sentence renders as *"Flaky on Firefox · races the login redirect"* — plausible, wrong, and nothing fails anywhere. | §121-15 |
| M1-31 | Quarantine a case that is **also** flagged in health (one with a Maintenance card already), reload, and watch the devtools console | **Two cards** for one case — one Maintenance, one Quarantined — and **no React duplicate-key warning**. Both cards are about the same case id, and a shared `case:` prefix would give them one id on the one screen whose whole job is to be a list. | §121-16 |
| M1-32 | Try to drag the quarantined card; then hover its disabled column buttons | No drag handle at all (`M1-24`'s rule), and the tooltip names **this card's own** source — *"… mirrors a quarantined test, so it can't be moved to Done. Update the quarantine and the card follows."* Not "an execution", not "a review". | §121-17 |
| M1-33 | Sign in as the case's **owner** and open **My work**; then as the person who ran the snippet | The card is on the **owner's** board and **not** on the quarantiner's. The decider is named in the sentence and is not the assignee — otherwise pulling somebody else's flaky test out of the gate moves their debt onto your own list. | §121-18 |
| M1-34 | Re-run the snippet with `{quarantined:false, reason:null}`, reload the board | The card is **gone**, and the case's other cards are not. This card leaves only when a person releases the test — deliberately unlike the Maintenance card beside it, which clears itself when the underlying condition is fixed. | §121-19 |

---

# A6 — reports: the pipeline, the screen, the view, the definitions

*Stage 8. `A3-2` generated a report at the end of the execution lane; by now its worker tick has
fired.* Everything here is against the **real** pipeline, which changes two rows fundamentally:
`M6-2`'s poll-driven state machine is gone (the worker is on a clock, see `L4-30`), and a `FAILED`
job now comes from the worker's own refusal rather than an edit to a dialog.

Rows `M6-1`…`M6-8` have no UI and are driven from the console on any signed-in page. `P` is a
project id from the URL:

```js
const H = { 'Content-Type': 'application/json',
            Authorization: 'Bearer ' + localStorage.getItem('tcms.token') };
```

> **The old snippet in this file read `JSON.parse(localStorage.getItem('tcms.session')).token` and
> would have thrown on its first line.** The key is `tcms.token` (`AuthContext.tsx:8`) and it holds
> the raw token, not JSON. Corrected here; it is the kind of error a queue accumulates while nobody
> executes it.

| # | Drive | Expect | src |
|---|---|---|---|
| M6-1 | `POST /api/v1/projects/${P}/reports/generate` with `{type:'run_summary', format:'MD'}` | **202** with **all seven** contract fields: `jobId` (**not `id`**), `type`, `status: 'PENDING'`, `format`, `downloadUrl: null`, `modelUrl: null`, `error: null`. **No `requestedAt`.** `Location` → `…/reports/jobs/{jobId}`. | §87-1 |
| M6-3 | On a `COMPLETED` job follow `downloadUrl` and `modelUrl` | Both resolve — Markdown and JSON. A null `modelUrl` is a screen that cannot be built without a second computation. | §87-3 |
| M6-4 | Ask for `format:'PDF'`, `'DOCX'`, `type:'quarterly_vanity_metrics'` | **400 each**, error under `format`/`format`/`type`. `PDF` is in the contract's enum and refused by *this deployment* — that is the design, not a bug (§89-20 renders it). | §87-4 |
| M6-5 | Generate `format:'HTML'`, wait for the tick, open `downloadUrl` in a tab | **202** at request time, and a page that renders as a **document**: `<title>`, `<h1>`, no broken frame. A fragment would make the download look like it worked while producing something no browser opens. | §88-9 |
| M6-6 | On the same job, `await (await fetch(modelUrl, {headers:H})).json()` | `{ header, report }` — **both keys**. `header`: `reportType`, `description`, `company`, `projectScope`, `scopeNote`, `generatedBy`, `generatedAt`, `selection[]`. `report`: `title`, `metadata`, `sections`. | §88-10 |
| M6-7 | `POST /reports` (save a definition), `GET /reports`, then `POST /generate` with **only** `{definitionId, format:'MD'}` | 201 with `owner` filled; the list contains it; the generate is **202 with `type: 'run_summary'` without the request naming a type** — the whole of "one click". Wait for the tick and download: the report must be **of the saved run**. | §92-40 |
| M6-8 | Re-generate with `{definitionId, format:'MD', parameters:{note:'this run'}}`, then read the model header. Then `DELETE` the definition and re-poll the first job; then generate from the deleted id | Selection lists **both** `runId` (definition) and `note` (request) — only `note` means the shallow merge became a replacement, which renders a perfect report over the wrong scope. The delete is 204, the definition leaves the list, and **the earlier report still downloads** (`V28`: deleting a saved report does not delete its history). Generating from the deleted id is a **400 with a field error on `definitionId`** — not 404, not 500. | §92-41,42 |

### The screen

| # | Drive | Expect | src |
|---|---|---|---|
| M6-9 | Open `OLB` → **Reports** in the rail | Header, **Generate**, and a "Saved reports" panel. The rail item is under **Execution**, `aria-current`, and present for **every** persona: it is ungated on purpose. | §89-14 |
| M6-10 | **Generate** → a real run, format HTML, submit. Watch the card | A card appears **immediately** reading `Queued` (the 202's body is seeded into the cache, so no skeleton). **It then sits there** — see `L4-30`, which is this row's other half and the reason the copy has to hold up for minutes rather than two seconds. | §89-15 |
| M6-11 | On a Ready card click **Download HTML**, with the Network tab open | A file saves as `run_summary-{artifactId}.html`; opening it gives a whole document whose `<h1>` names **the run actually chosen**. The request carries an **`Authorization: Bearer …` header**, which is the whole reason it is a button and not a link (see `L2-12`). | §89-16,17 |
| M6-12 | Generate a second report while the first is still building | **Two cards**, newest first, each advancing on its own. The query key carries the job id; a key naming only the project makes the second answer overwrite the first's `downloadUrl`. | §89-18 |
| M6-13 | Generate with `parameters:{runId:'<a uuid that is not a run>'}` from the console, then watch the card | The card reaches **`Failed`**, red, carrying **the worker's own sentence**, with no download button and no spinner — distinguishable at a glance from a Ready report that contains nothing. **Retargeted**: this used to say *"temporarily make `GenerateReportDialog` send `parameters: {}`"* and edit a source file. The server has always produced this state; the mock had to be taught to. | §89-19 |
| M6-14 | Choose format **PDF** in the dialog and submit | **400 under the Format field** — *"PDF rendering is not installed on this server. It can produce: MD, HTML."* — not in the top banner. PDF is offered on purpose: the enum is the contract's, not this deployment's. | §89-20 |
| M6-15 | Reload the page while a card is showing | The cards are **gone**, and that is the design: nothing lists jobs, so the screen shows what this tab asked for. Confirm the running card said so before the reload. **If this reads as broken, record it as the argument for a job-list endpoint — do not patch it client-side.** | §89-21 |
| M6-16 | On a Ready card click **View report**, then **Download HTML**, and open the file beside the tab | Provenance block, section and table on both. **Compare line by line** — company, project scope, the scope sentence, who generated it, when, `Selection — runId`, then the numbers. Any difference is the drift decision F2 exists to prevent, and no gate can see it: the two renderers share a model and nothing checks they render it the same. | §90-26 |
| M6-17 | Read the report view's section body | Bars **proportional to the largest value in the series** with the number printed beside each, and cells reading exactly as the file's — `50.0%`, not `50%`, not `0.5`. The number is not decoration: it is what survives a reader who cannot judge length against a scale they cannot see. | §90-27, §88-12 |
| M6-18 | Copy the report view's URL, reload, then open it in a **new tab** | Renders both times — the durable handle the reports screen does not have. The breadcrumb reads *Project → Reports → the report's title*, and **Reports** links back to a screen with no cards, which is the honest state. | §90-28 |
| M6-19 | Open a report view URL for a job **still building** (generate one and go straight there) | *"Building — this page updates itself when the report is ready"* with a spinner — **not** a skeleton and not an error. Network tab: no artifact request until it completes. **It will not complete while you watch** — the tick is `PT15M`; come back and confirm it **became the report with no reload**. | §90-29 |
| M6-20 | On a completed report view, `tcmsFaults.only('/artifacts')` then `tcmsFaults.drift()`, and reload | A **failed region with a retry** saying the stored model is not in a shape this version can display — not an empty report, not a blank panel. This is the only rendering separating "the report contains nothing" from "this client cannot read the report"; a cast instead of a parse makes them one screen. **Retargeted**: it used to require editing `mockModel`. `client.ts`'s artifact reader has its own fault seam, so `drift()` reaches it — and if the fault does **not** reach the artifact route, that is itself the finding. Disarm afterwards. | §90-30 |

| M6-21 | In **Generate**, switch **Report** to *Aggregate execution report* | The **Run** picker disappears and four controls take its place — Aggregation policy, Plans, Features, From/To. The help line under the policy is the plan page's own sentence for that value; change the policy and confirm it changes with it. | §111-1 |
| M6-22 | In a project with **no runs at all** (`provision-company` a second company, or use one before `A3`), open Generate → *Aggregate execution report* | **Generate is ENABLED** and the "no test runs yet" notice is **absent**. The run summary's notice must still appear when the type is switched back. This is the row the pre-R6 gate fails: it disabled the button for the whole dialog. | §111-2 |
| M6-23 | Aggregate report, policy **WORST**, From `2026-08-01`, To the **current day**, submit — Network tab open on the POST | `parameters` carries `policy`, `from` at `T00:00:00Z` and `to` at **the day AFTER** the one typed. Nothing else: no `planIds: []`, no `runId`. Then read the finished report's header — the **Selection** block must name the same range and the same policy. | §111-3 |
| M6-24 | Same dialog: From `2026-08-31`, To `2026-08-01` | A message under **To** — *"The last day is before the first…"* — and **no request at all** in the Network tab. The server refuses this too, in the worker, minutes later on a card nobody is watching; that is what this control is for. | §111-4 |
| M6-25 | Pick two **Plans** and two **Features**, submit, and read the resulting report | The picker shows `2 selected`; the header's Selection block says `2 selected` for each. Features **union**: a case classified under either appears. A project whose vocabulary has no features shows the Features control **disabled with its own sentence**, not an empty menu. | §111-5 |
| M6-26 | Generate an aggregate report at the **default** policy over a project where **one case was failed in one run and passed in a later one** (the `A3` lane's re-run, or record two results by hand), then **View report** | Six sections. **Inconsistent results** is directly under the headline and **lists that case by its key and title** — not a UUID, and not absent. Its last column reads *Within one configuration and version*. This is the whole slice: the default policy collapses that case to one row, so a section reading the collapsed set is empty here and looks like a project with nothing to report. | §112-1 |
| M6-27 | Same report, scroll to **First attempt and retries** and to **Manual and automated** | Three outcome rows summing to the number printed beside them, a bar per outcome, and the column heading **Case, configuration and version** — not "Cases". The split has **five columns** and shows the case's automation status *and* the run's source on each row: check the 5-column and 7-column tables **do not clip or scroll the page sideways** at 1280px and on a narrow window (§52's defect, and these are the two widest tables in any report). | §112-2 |
| M6-28 | Generate the same report over a project where **nothing has been retried** (a fresh company, or narrow `To` to before the second run) | **Inconsistent results** reads *"No case in this selection both passed and failed…"* as a sentence, and **First attempt and retries** still shows its three rows with *Passed only after a retry* at **0** — not a blank panel, not a missing section. The distinction being checked is "we looked and found none" against "this section failed to render". | §112-3 |
| M6-29 | Download the **HTML** of the M6-26 report and open it beside the tab | The three new sections render identically in both — same rows, same order, same *Disagreement* wording, and the retry chart drawn as CSS bars. Decision F's property, and nothing in either suite can see it: screen and export share a model and no gate checks they render it alike. | §112-4 |
| M6-30 | **Edit a case's title or a step between the two runs of the `A3` lane** — that edit is what this row needs and no lane makes it, so add it to the charter — then generate an aggregate report at the **default** policy and View report | A **Version divergence** section under the callout, listing that case **twice**: `v1` and `v2` as version labels (not UUIDs), with one row reading **Counted** and the other **Not counted**. The header block reads `Cases executed at more than one version — 1`. Under the default policy only one version reaches the totals, so a section computed over the collapsed set is empty here and reads as a project that never edits its tests. | §114-1 |
| M6-31 | Regenerate the same report with **Aggregation policy = All executions**, and read the section's closing sentence | Every listed row now reads **Counted**, and the last sentence of the narrative changes to *"those totals average results across versions"* — where the default policy's report said *"one version of each case reaches the totals"*. Nothing else in the table moves. This sentence is computed from which versions reached the totals, and the only way to see that it is not a hard-coded string keyed on the policy name is to change the policy and watch it follow. | §114-2 |
| M6-32 | Generate an aggregate report over a project where **no case has been edited between runs** (a fresh company, or narrow `To` to before the edit) | *"Every case in this selection was executed at one version of its own text…"* as a sentence, and the header block still carrying **`Cases executed at more than one version — 0`**. The zero is the row's point: a flag that appears only when it has something to say cannot be told from a build that does not compute it. | §114-3 |
| M6-33 | On the M6-30 report: check the **Version divergence** table at 1280px and on a narrow window, then download the **HTML** and open it beside the tab | Six columns that **do not clip and do not scroll the page sideways** (§52) — it sits beside R7's seven-column split, so this is the second-widest table in any report — and screen and export show the same rows in the same order with the same `Counted`/`Not counted` wording. Decision F's property again, and no gate can see it. | §114-4 |

#### The coverage matrix (§115)

*Needs `DOC-1` and `DOC-2` from the charter, and the mixed results the `A3` lane records. Every row
here is over the **real** worker — there is no other mode.*

| # | Drive | Expect | src |
|---|---|---|---|
| M6-34 | Reports → **Generate** → report type **Coverage matrix** | A **Document** picker appears, the **Run** picker disappears, and the aggregate report's policy/plan/feature/date controls do **not**. Each page is offered as `DOC-1 · Payments` with its **status** beside it — `Published` or `Draft`. **Submit is enabled.** The gate that would break this is a negation: R6 spelled the run gate `!isAggregate`, and a coverage matrix is not the aggregate, so an unrepaired gate leaves **Generate disabled and the Run picker rendered** in a project with documentation and no runs. | §115-1 |
| M6-35 | Same dialog, submit **without** choosing a document; then choose `DOC-1` and submit — Network tab open on the POST | First: *"Choose the document this report covers."* under the field and **no request at all**. Then `parameters` carries **`docPageId` and nothing else** — no `runId`, no `policy`. Sent at the top level instead of inside `parameters` it is dropped silently and the job FAILS minutes later, on a card that looks exactly like a correct one. | §115-2 |
| M6-36 | View the finished `DOC-1` report | **Coverage** first, then the document's own sections in the document's order: `Refunds`, `· Disputes`. `Statements` is **5** — four bullets plus the linked paragraph — and **not 8**: the intro paragraph and the two headings are not statements. The ladder reads Verified 2 · Failing 1 · Not run 1 · Not covered 1. The bullet with two cases is **Failing**, not Verified: one red case outranks a green one, and the opposite reading passes every other check on this page. | §115-3 |
| M6-37 | Same report: read the `· Disputes` section, then generate over **`DOC-2`** | `· Disputes` is **present** with the sentence *"No statements under this heading"* — a heading dropped from the output makes the report stop being the document. `DOC-2` renders with `Statements` **0**, `Coverage` an **em dash** and the sentence *"nothing to cover"* — **not `0.0%`**, which would tell a reader a page of prose is an untested specification. | §115-4 |
| M6-38 | Back on `DOC-1`: open the documentation editor, change bullet 4's link from **Verifies** to **References**, regenerate, and compare | ~~It was covered~~ → it now reads **Not covered**, and the Verified count drops by one. This will look like a bug to whoever recorded the link, and it is the whole reason the author was asked to choose a relationship: a `REFERENCES` link is *"context, not coverage"*, and counting it is the false claim the field exists to prevent. **If the editor cannot change a link's relationship**, say so — the row is then a finding about the editor, not about the report, and the fallback is to record it as a new link and delete the old. | §115-5 |
| M6-39 | The `DOC-1` report's **Last result** and **Last run** columns, at 1280px and on a narrow window | Four columns, `KEY — Title` in the Case column (never a UUID), a UTC timestamp per executed case and an **em dash** for the unexecuted one. Long statement text is **cut at one line with an ellipsis** rather than reflowing the table — the cut happens in the computation, so the screen and the HTML export must show the **same** truncation. No sideways scroll of the page (§52). | §115-6 |
| M6-40 | Download the **HTML** of the `DOC-1` report and open it beside the tab | Identical sections, identical order, identical ladder, same truncation. Decision F's property, and no gate can see it. Then check the header's **Selection** block names `docPageId`, and the metadata carries `Document`, `Document version` and `Document status` — a coverage report over a **draft** specification is a different claim from one over a published one, and only the document says which it was. | §115-7 |

### What only the real worker and a real document show

| # | Drive | Expect | src |
|---|---|---|---|
| L4-30 | The job started at `A3-2`, and the card watched in `M6-10` | **202**, then a job reaching `COMPLETED` **without polling advancing it** — the real worker is `PT15M` after a `PT3M` delay, so this waits for a tick or follows a restart. The card sits at Queued/Building for **minutes**, and the copy has to hold up over that duration. A client written against the mock had copy written for a two-second wait. | §87-5, §89-23 |
| L4-31 | Generate `format:'HTML'` for a real run and open `downloadUrl` | The provenance block at the top: company, project scope, the scope sentence, who generated it, when, `Selection — runId`. Then **turn the network off and reload the saved file** — identical. "A self-contained file that needs a CDN to draw its diagrams is not self-contained"; the unit test asserts the absence of URLs, only a browser proves it *looks* like a report without them. | §88-11 |
| L4-32 | Same document: read the bar chart, then **Print preview** | Bars in CSS with the number beside each, and **no table row split across a page boundary** (`break-inside: avoid`). This page is what the PDF slice hands to a headless browser, so print is the deliverable's dry run — and the number beside the bar is what survives a mail client that strips the stylesheet. | §88-12 |
| L4-33 | Name a project (or run) `<img src=x onerror=alert(1)>`, generate HTML, open the file | The text appears **as text** — no alert, no broken layout. Every value in a report is user-controlled; the escaping is asserted by a counted unit test and this is the only place it meets a real parser. | §88-13 |
| L4-34 | As `lead`, open a real report's **on-screen view**; then print it and narrow to phone width | Header carries the **real** company, `KEY — Name` project scope, the scope sentence, the signed-in display name and a **UTC-labelled** timestamp — compare `Generated at` against the run's own timestamps for a sensible timezone. Tables scroll **inside their own container** (`ScrollX`) rather than the page, and the print view is legible. | §90-32,33 |
| L4-35 | `lead` and `engineer` each save a report definition, then each list; then `engineer` `DELETE`s `lead`'s id | **Each sees only their own**, and the delete is **404** with the row surviving. Structurally unobservable with one persona — "mine" and "the project's" are the same set there, and dropping the owner filter passes every single-account check. | §92-43 |

---

# L1 — the permission matrix (five personas, one sitting)

*Stage 9. Needs all six accounts and everything the `A` lanes authored.*

Walk the app once per persona rather than once per slice. The standing rule is §31 **disable, don't
hide**, and the standing trap is that a control disabled *for everybody* passes every "is it
disabled?" row — so each block ends with the tier above it confirming the control is live.

**This lane used to open by saying mock mode makes it structurally unobservable. It no longer needs
to: there is no other mode, and this is the only lane where the tiers are visible at all.**

### Viewer

| # | Drive | Expect | src |
|---|---|---|---|
| L1-1 | Artifact register; any case; evidence on a failed execution | **New** disabled (no `artifact:edit`); Delete disabled with the *ordinary* sentence (*"You don't have permission to delete test cases in this project."*) — the authorship path must not have replaced the plain gate; all three evidence rows **visible** with Attach and every Delete disabled. Evidence is part of the record of what happened. | §65-4, §66-5, §67-7 |
| L1-2 | A plan; the classification settings screen; Teams & roles | Edit **disabled**; the settings page **renders with every control disabled and the current values visible** rather than 403ing or hiding the rail item — a Viewer must be able to find out why no artifact shows a review panel; the **Review** rail item is present for the same reason. | §74-10, §77-3,4 |
| L1-3 | A plan's Scope tab: type into the box; then the Progress tab | Save **disabled with the reason**, never hidden. The box still accepts typing — the accepted cost of one draft above seven tabs; the refusal is on the write. On Progress the numbers render in full with **no disabled editor anywhere near them** — the row that catches a form spine that swallowed the read-only screen. | §97p4-124,125 |
| L1-4 | A plan with an open review (`viewer` holds `case:comment`, not `*:review`) | Comment box and **Reply enabled**; **Resolve**, both verdict buttons, the **Ask** button and the picker **disabled**, chips carrying **no ✕**; the whole trail readable. One comment key for all three artifact types, and the reviewing tier above it. | §79-2, §80-3 |

### Test Engineer

| # | Drive | Expect | src |
|---|---|---|---|
| L1-5 | Artifact register → New | The **PROJECT** "Belongs to" option **disabled with a reason naming the restriction** — not enabled-then-refused, which is what §61 shipped. Choosing PLAN scope and submitting is a **201** landing in the plan's register. | §65-1,2 |
| L1-6 | `TC-1` (authored by `engineer` in `A1`), `TC-2` (by `lead`), `TC-3` (`created_by IS NULL`) | TC-1: Delete **enabled**, and deleting works — 204. TC-2: **disabled**, *"You can only delete test cases you created."* TC-3: **disabled** — the arm that exists only in code and governs an installation's oldest cases. Then **create** a case and open it: Delete **enabled**. | §66-1,2,3,6 |
| L1-7 | Case list and detail, network tab | `createdBy` on every row, `null` where the column is, and **not the same person as `owner`** on the seeded rows. If they always agree the fixture did not take and `L1-6` proves nothing. **The charter authors TC-1 and TC-2 as different people specifically so this row can fail.** | §66-7 |
| L1-8 | Evidence on a failed execution (`A3`'s three rows) | Their own row: Delete **enabled**. `lead`'s: **disabled**, *"You can only remove evidence you attached."* The null-author row: **disabled**. | §67-4,5,6 |
| L1-9 | A run; an approved suite's kebab; a draft suite's kebab | Complete and Abort **disabled with a tooltip** while recording a result still works. On the approved folder **"Return to draft" and "Deprecate…" greyed** with "Execution history" live beside them — before §75 the same caller could take that approval back through the bulk case dialog with nothing on screen saying otherwise. On a draft folder **"Submit for review" is live** and "Deprecate…" is greyed. | §72-3, §75-3,5 |
| L1-10 | Case list: select one `DRAFT` and one `APPROVED` case → bulk **Set status → Draft** | The draft **changed**, the approved one **skipped** with *"Only a test lead can change a case that is already approved."*, and `changed + unchanged + skipped == total`. Before §75 both moved. | §75-4 |
| L1-11 | A plan in review, then an **approved** plan (`engineer` holds `plan:edit`, not `plan:approve`) | In review: **Approve disabled**, "Return to draft" **enabled**. Approved: the same "Return to draft" button now **disabled**. The same control, the same target, gated by where the plan is. | §73-4, §97p3a-94 |
| L1-12 | An approved plan under **RE_REVIEW**: edit it | **The edit succeeds and the plan drops to Draft.** This looks wrong and is the design (`EditAfterApproval.RE_REVIEW`) — charging the approve key makes an approved plan uneditable by the people expected to edit it. | §77-13 |
| L1-13 | A plan in review: the reviewer picker (`plan:review`, not `plan:approve`) | Picker and **Ask enabled**. The gate decision made visible: the request must not be confined to the people whose sign-off it prepares. | §80-4 |
| L1-14 | A run's Grid mode (`result:submit`, not `result:amend`) | A row that already has a result: four keys **disabled with the amend tooltip**; an untested row's are live. The permission is **per row**, because half a run may be recorded and half not. | §100-112 |
| L1-15 | Start a run from a plan of draft cases with cases gating on (no `case:approve`) | **Create it anyway** is **present and disabled** with the gate's why-tooltip. Hidden instead of disabled is the defect: the person who has to go and ask would not know the way out exists. | §82-6 |
| L1-21 | **API tokens**, with the Network tab open (Test Engineer holds neither `token:issue` nor `company:members`) | The rail item is **there and works** — the screen renders, and says *"You do not have permission to issue API tokens in this company… A company administrator can issue one for you."* **Issue a token** is **disabled with its why-tooltip**, never hidden. And the row's real assertion: **no request to `/api-tokens` is made at all.** The server *refuses* that list rather than narrowing it, so an ungated read is a guaranteed 403 and a global "Something didn't load" over a screen that is behaving correctly. A page that fires it looks identical apart from that one line in the Network tab. | §118-10 |
| L1-22 | Same screen as **`viewer`**, then reload it twice watching the panel | Same refusal, same disabled button. On reload the panel must **never flash the refusal before settling** — the page defers on `Gate.pending`, because the permission verdict is fail-closed while it loads and a Company Admin would otherwise be told they lack access on every single visit. Then sign in as **Company Admin** and confirm the list is live: without that half, both rows above pass equally well against a screen that is broken for everybody. | §118-11 |
| L1-23 | As **`viewer`** (holds `project:read` on **one** project of several, no `company:members`): open `/org-chart` with the **Network tab open** | The page **renders in full** — it is gated on nothing. Every person in the company is listed with their teams. **Exactly one project column**, and the response body contains **no id, no name and no count** for any other project. `lastLoginAt` null on every person. The standing line *"Only projects you can open are shown here."* is present. **The row's real assertion is in the Network tab**: the disclosure boundary is the SERVER's, and a screen that filtered client-side would look identical here. | §241-1 |
| L1-24 | As **`engineer`**, whose role on project P is held **through a team**: find their own row, column P. Then as **`lead`**, who holds the same project **directly** | The cell reads the effective role with **"Through a team"** and names the team; the lead's reads **"Direct grant"**. Two arms of one enum that a matrix built from `role_assignment WHERE project_id = ?` renders identically — and a third (`INHERITED`) it omits entirely. | §241-2 |
| L1-25 | As **Company Admin**: find a person granted a company-wide **Test Lead**, and a person who is themselves a Company Admin | The company-wide Test Lead shows **"Company-wide default"** in every column **except** any project holding its own grant for them, where the project's role shows instead **and the inherited one is not also shown** (§A1.1 replace, not union). The Company Admin shows a **`Company Admin` badge on the person and NO cell in any column** — filling every column with it is the misreading §A1.1 property 2 exists to prevent. | §241-3 |
| L1-26 | As **`viewer`**: grant somebody a role on the one visible project from another browser, then return to the chart **without reloading** | The new cell appears. All nine staffing writes invalidate `keys.orgChart`; a missed invalidation leaves the chart stale in exactly the moment somebody just restaffed a project, which is the moment they opened it. | §241-4 |
| L1-16 | Teams & roles (no `company:teams`) | The three admin buttons disabled with a reason, **Board live**. A Board button written as a fourth child of that gate looks identical until this sign-in. | §85-2 |

### Test Lead / Company Admin — the control-is-live half

| # | Drive | Expect | src |
|---|---|---|---|
| L1-17 | As **`lead`**, repeat `L1-5`, `L1-6`, `L1-8` | PROJECT scope **enabled and selectable**, submitting lands a project-scope row; Delete **enabled on TC-2 and TC-3**; Delete **enabled on all three** evidence rows. Without these, every "disabled" row above passes equally well against a page broken for everyone — and `useCan` is **fail-closed while pending**, which is exactly how §66's own component test passed against a page with the wiring deleted. | §65-3, §66-4, §67-6 |
| L1-18 | As **`lead`**, a suite kebab: approve a folder, then reopen it; then deprecate one and reopen it | After approval **"Submit for review" is not offered at all** — the moves swap rather than accumulate, and offering it would be a control the server 409s. After deprecation **"Restore to draft" is the only move**, with a muted rather than coloured badge. | §75-6,7 |
| L1-19 | As **Company Admin**, the classification settings screen; add a Test Type value; check **`MOB`** | Every panel's controls enabled, **including Test Type's**. The new value is offered in `MOB` too — that is what "company-wide" means and why the permission is separate. A page asking `project:settings` for every dimension renders *identically* for this persona and hands a Test Lead a button the server 403s. | §69-14,15, §77-3 |
| L1-20 | As **`watcher`** (`report:view`, no `report:export`): the Reports screen; then a Ready card's Download; then a completed job's **report view URL**; then save/list/delete a definition and try to generate from it | **Generate disabled with a why-tooltip**, rail item still there, polls and definition reads working. Download → **403 handed back beside the button in the card**, not a corner toast, card and status still on screen (the route's 403 is empty-bodied, so this also confirms `describeError` produces a sentence from the status alone). The report view → job renders, report **refused with the worded 403** *"You can watch this report being made, but not open it"* — **this is a finding, not the expected product behaviour**: §6e's split is "may read on screen, may not carry off", and today the screen costs the export key because the model is served through the download route. The three definition operations **succeed** and the generate is **403**: saving a selection is not exporting a report. | §89-24,25, §90-31, §92-44 |

---

# L2 — the server's refusals (curl / devtools)

*Stage 10. Reuses `L1`'s personas — keep them to hand rather than re-provisioning. Lands nothing:
every row is a refused write.*

The disabled control is a courtesy; these rows are the rule. Several are unreachable from any UI.

| # | Drive | Expect | src |
|---|---|---|---|
| L2-1 | `GET /me/permissions` on any authenticated screen; then walk the case grid, the board, the dashboard | `permissions: [{key, qualifier}, …]`, **sorted by key**, no entry whose qualifier is `NONE`. **No regression across the eleven gated affordances** — the wire shape changed under all of them at once and `useCan` falls **open** on error, so a consumer left on the old shape looks *normal*, not broken. The highest-risk row in the §65 block. | §65-5,6 |
| L2-2 | As `lead`, `POST /classification/values` with **`MOB`**'s dimension id while scoped to `OLB` | **404, not 403** — the caller must not learn which permission would have applied. | §69-16 |
| L2-3 | Write to a **completed** run (devtools, or a tab opened before the close) | **409** *"This run has ended…"* — not 403, not 500. | §72-7 |
| L2-4 | `PUT …/status` with `IN_REVIEW` on an **APPROVED** suite | **409** *"Only a draft suite can be submitted for review. Return this suite to draft first."* — and the same sentence, prefixed with the folder's name, in the rail's own alert if reached through the UI. | §75-8 |
| L2-5 | ⚠ **Close** a draft plan (no such control exists) | **See `A2`'s box** — §97 phase 3a may have removed the state entirely. If the endpoint is still there: **409** *"Only a plan that is being executed can be closed…"*, and the absence of the button is the point. If it is gone, record that and strike the row. | §73-5 |
| L2-6 | ⚠ Force a save on a **closed** plan | Same caveat. If reachable: **409** *"This plan is closed… Reopen the plan to change them."* | §74-9 |
| L2-7 | With peer minimum 1 and *the submitter cannot approve* **on**: submit a plan as `lead` and approve it as `lead` | **409 in place** — *"You submitted this plan for review, and this project asks somebody else to approve it."* Plan stays In review. **Not a 403**: the permission is held and the policy is what refuses. | §77-6 |
| L2-8 | Attach a file with `max-file-size: 1MB` temporarily set, 2 MB file | **413** with readable copy (*"Evidence files are limited to…"*), never a bare 500. The container refuses before the handler runs, so this is a completely different path from the client-side `M4-9`. | §67-9 |
| L2-9 | `GET /api/v1/work-items` with **no parameters** | **400, "Choose a board"**, naming both requests that do work. The SPA never sends it; this confirms the refusal its shape is built around exists. | §83-19 |
| L2-10 | `GET …/work-items?teamId=<uuid>&mine=true`; then `?teamId=<a team in another company>`; then `?teamId=<valid>&projectId=<valid>` | **400 "Choose one board"** (unobservable in the body for a caller on the team, so the status is the only evidence). **404 with an empty body, identical to an unknown UUID** — any difference makes the operation a company-wide team-existence oracle (§6t). **200** with the team's work inside that project: legal, and no screen sends it. **The second arm needs a second company** — provision a throwaway one, or record the row as not driven. | §85-23,24,25 |
| M1-29 | `PATCH` a work item into a column that is **not in its `allowedTransitions`** | **409** — *"the requested column is not in the card's allowedTransitions"*. **Moved here from the board lane**: it was a check on the mock handler, which had answered `200` since Phase 1 and was taught the refusal in §106. The server is the only implementation now, and this is the row that says whether it refuses. It is unreachable by dragging, because the board disables the illegal columns — so devtools is the only way in. | §106-11 |
| L2-11 | As `watcher`: `POST …/reports/generate`, then poll an existing job, then follow a completed job's `downloadUrl` | **403 with a problem body**; poll **200**; download **403 with an empty body** — deliberately bodiless because the response is typed to a binary resource. Confirm the last does not render as a corrupted download. | §87-6,7 |
| L2-12 | As `lead`, paste a completed job's `downloadUrl` into the **address bar**, then download the same report **through the screen's button** | Address bar **401**; button gives the file. The session is a bearer token in a header and `SecurityConfiguration` is `STATELESS` with no cookie, so **no `<a href>` into `/api/v1` can ever authenticate**. This supersedes §87's row 8, which assumed a navigation would authenticate. | §89-22 |
| L2-13 | As `lead`, follow `downloadUrl` from the app | The file **downloads** — `Content-Disposition: attachment`, named `run_summary-{artifactId}.md`. Evidence is served `inline` (a screenshot belongs beside the step it failed on); a report is a file somebody keeps. | §87-8 |

---

# L3 — the invariants only `psql` can see

*Stage 11. Reads what the `A` lanes and `L1`/`L2` wrote. No browser except where a row says to drive
something.*

Every row here looks identical on screen whether it holds or not. That is why they are written down.

| # | Drive | Expect | src |
|---|---|---|---|
| L3-1 | The case `L1-6` deleted | `SELECT deleted_at FROM test_case WHERE key='TC-1'` is **set** — the delete is soft, as `CaseDeleteDialog`'s copy claims. | §66-8 |
| L3-2 | Delete evidence you attached | The `evidence` **and** `attachment` rows are gone **and the file is gone from `TCMS_EVIDENCE_ROOT`** — a hard delete. The one place a partial write is rolled back by nothing: a failure between the two leaves a row whose blob is gone, or worse in the other order. | §67-12 |
| L3-3 | Assign a classification value, **then retire it** (`UPDATE classification_value SET deleted_at = now() …`), then open the editor and save any other change; reload and save again | The retired value has **no checkbox** and its `case_classification` row is **still there** afterwards — both times. **The only silent failure in §68**: if the bound is wrong the save succeeds, the response looks correct, and a classification is gone with nothing having mentioned it. Neither the API nor the UI can produce this state, which is why it is the arm most likely to be wrong. | §68-8,9, §69-13 |
| L3-4 | Rename a classification value | `path` is **byte-identical**. Nothing in the UI shows a path; if a rename rewrote it the only symptom appears later in §6q's descendant matching, which is not built — so it is checked here or not at all. | §69-9 |
| L3-5 | Complete a run, then Complete it again | 200, `completed_at` **unchanged**, **one** `run.closed` audit row. | §72-10 |
| L3-6 | ⚠ Plan: Submit → Approve → Start → Close | **See `A2`'s box.** Under the three-state ladder the last two moves do not exist. Record which of the four audit actions — `plan.submitted`, `plan.approved`, `plan.started`, `plan.closed` — the instance can actually produce, each with `from` and `to`, and whether `closed_at` still has a writer. | §73-2 |
| L3-7 | Edit a plan's **scope** (add a suite), then edit **only** its name | Scope: `test_plan_version` gains a row whose `snapshot` names both suites, `current_version_no` = 2. Name: **no new version row**, `current_version_no` unchanged. A plan version is its scope (§6j); a name is content — and an implementation that bumps on every save looks identical on screen. | §74-5,6 |
| L3-8 | Press a suite move twice quickly (Approve → Approve); then check `suite_version` | Second is **200 with no second audit row**: exactly one `suite.approved`, `updated_at` unchanged. And **no new `suite_version` row**, `current_version_no` unchanged — a suite version is its composition, and approving does not change what is in the folder. | §75-9,10 |
| L3-9 | Retag a case (add a tag, save), open its version timeline | **No new version.** Tags are kept off `CaseEdit` and out of the content hash — a version whose diff says nothing about the test having changed is the empty-diff noise §6j added the hash to keep out. | §96-79 |
| L3-10 | Under RE_REVIEW, edit an approved plan | An audit row `plan.re_review_required` naming you, `from APPROVED to DRAFT`. | §77-12 |
| L3-11 | Create a run with the gate **overridden** (`L1-15`/`M4-24`), then one with the gate off | `approvalGateOverridden` on the first row and **absent** on the second. **No audit screen exists** (`audit:view` has no consumer), so this is a read against `audit_event.changes`. | §82-14 |
| L3-13 | Deprecate a folder that holds cases, open the case list scoped to it | The cases are **still there and still editable**. Deprecating is a label on the folder, not a soft delete of its contents — nothing in this slice cascades. | §75-12 |

---

# L4 — what only a real instance and a real clock produce

*Stage 12, and it is now the health signals, the rollups and the deployed build.* The teams block
moved to `A0`, the two-account reviews to `A4`, the reports to `A6`, and four content rows to `A1` —
what is left is what genuinely needs **a scheduler tick**, **a second company**, or **a deployment**.

**Both clocks from `A3` must have elapsed.**

### Health

| # | Drive | Expect | src |
|---|---|---|---|
| L4-23 | `A3-1`'s seed — one case, **three `Failed`** results across three runs — after the tick. Open the dashboard | **"Failing repeatedly"** tile, count **1**, severity WARNING (amber not red). Before §86 it could only ever read 0. | §86-1 |
| L4-24 | Record a fourth result **`Passed`** on that case, wait, reload | The tile drops to **0** and the chip disappears. **The self-healing property §6o rests on** — "no 'mark as not-stale' button exists to lie with" is only true if the computation takes it back. A latching signal is indistinguishable on screen from a correct one. | §86-2 |
| L4-25 | Three **`Blocked`** results on a different case, wait, reload | **Not listed.** The predicate is `status_kind = 'FAILED'`, not "did not pass" — a blocked case is an environment problem, and flagging it sends the reader to fix the wrong thing. | §86-3 |
| L4-26 | With `L4-23`'s case flagged, open the board | A **`CASE_MAINTENANCE`** card naming the signal, in the right column, whose `linkFor` **resolves**. Structurally empty since §53: two of nine signals had a writer and the card was built around neither. | §86-4 |
| L4-27 | Soft-delete a flagged case, wait a tick, reload | Tile count drops, case gone — **and the fact log keeps the deleted case's results**. Two opposite calls in one slice, and the screen is where their consistency is legible: no orphan chip, no report row lost. | §86-7 |
| L4-28 | **Two companies**, both with a failing-repeatedly case; sign in as a member of one | Only that company's case is counted. The projection carries **no company predicate** — `FORCE ROW LEVEL SECURITY` is the entire mechanism on both the `SELECT` and the `INSERT`. **Needs a second provisioned company**; pair it with `L2-10`'s second arm and provision one throwaway company for both. | §86-6 |
| L4-29 | A project of a few hundred cases: dashboard response time before and after a tick populates `failing_repeatedly` | No visible regression. **`V1` ships four partial indexes on `case_health` and none on `failing_repeatedly`**, so this tile is the first that has to scan. Found while reading the DDL and deliberately not fixed — if this shows a real cost, the index belongs in the next migration. **26 cases will show nothing**; either bulk-create or record it as not driven. **`V37` added the two `case_health` indexes the drift pair needs**, so this row is now about `failing_repeatedly` alone. | §86-8 |

### Automation drift — M3 slice 5, §6c

*These need `A3`'s ingestion rows to have run: without a `test_run` whose `source` is `INGESTED`,
both signals are correctly and permanently false, which is exactly the state the column sat in from
`V1` to M3. **`A3-6` and `A3-10` are the seed** — they leave `OLB` with two matched cases whose
results came through the ingestion door, one of them (`A3-8`'s ghost test, bound to a case somebody
picked) almost certainly bound to a case that is **not** marked `AUTOMATED`.*

**The clock is the problem in one direction and not the other.** `UNDECLARED_AUTOMATION` fires on a
**recent** ingested result and is drivable in a sitting. `STALE_AUTOMATION` needs one **older than 14
days**, which a company provisioned this morning cannot have. Two honest routes, and the row says to
pick one rather than leaving it: back-date `test_execution.executed_at` in `psql`, or restart the app
with `TCMS_STALE_AUTOMATION=PT1M` and wait. **Do not lower the constant in `application.yml`** —
that verifies a number nobody ships (`L4-29`'s objection to lowering the unmatched cap).

| # | Drive | Expect | src |
|---|---|---|---|
| L4-49 | After `A3-10`, set the case `A3-8` bound the ghost test to back to **Manual** in the case editor if it is not already, wait a tick, open the dashboard | An **"Automated, but not marked so"** tile, count **1**, severity **WARNING — amber, not red**. The colour is the row's real assertion: this signal and its mirror are matched in every other way, and the one thing that distinguishes them for a reader is that only one of them can be hiding a red test. | §120-1 |
| L4-50 | Open that case; read the chip and hover it | The chip in the **blocked/amber** colour, not the failed one, with the tooltip naming the consequence — *"…so automation coverage does not count it"* — rather than restating the label. Compare it side by side with a `STALE_AUTOMATION` chip if `L4-51` has produced one: **the two must not look the same**. | §120-2 |
| L4-51 | Mark that same case **Automated**, back-date its ingested execution past the window (or restart with `TCMS_STALE_AUTOMATION=PT1M` and wait), tick, reload | The amber tile is **gone** and a red **"Automation stopped reporting"** tile has replaced it. **One case must never show both** — they read one fact on two sides of one cutoff, and seeing both at once means a predicate lost its `automation_status` arm. | §120-3 |
| L4-52 | With that case stale, **record a result on it by hand** in a new manual run, tick, reload | **Still stale.** The signal does not clear, and this is the row that catches the likeliest wrong implementation: the statement directly above it in `ExecutionFacts` reads the newest result of *any* kind, and copying that lateral — the obvious edit — reports a case CI abandoned as healthy the moment a person runs it once. Mutation `DR-1`'s browser half. | §120-4 |
| L4-53 | Set the case to **Not automatable** (the editor requires a reason), post a fresh ingested report matching it, tick, reload | The **amber** tile again. §6c says "marked `MANUAL`" and the predicate is `<> 'AUTOMATED'`; this is the arm a literal reading drops, and it is the loudest one, because automation coverage is denominated `AUTOMATED / (total − NOT_AUTOMATABLE)` — a wrong `NOT_AUTOMATABLE` moves the **denominator**. | §120-5 |
| L4-54 | Filter the case grid by the new signal from the health facet | The facet lists **"Automated, but not marked so"** with a count, and selecting it narrows the grid to that case. **The facet count and the grid must agree** — they are the two halves `HealthSignal.attribute()` resolves, and `CaseHealthAttributesTest` now checks the name resolves but not that the two consumers pick the same rows. | §120-6 |
| L4-55 | Two companies, one drifting case each; sign in as a member of one | Only that company's case is counted, exactly as `L4-28` asserts for `failing_repeatedly`. The new statement carries **no company predicate** either — `FORCE ROW LEVEL SECURITY` on both the `SELECT` and the `INSERT` is the whole mechanism. Fold into `L4-28`'s throwaway company rather than provisioning a third. | §120-7 |

**One thing this pass cannot show.** A case marked `AUTOMATED` whose automation has **never** posted
raises nothing (decision D3, and deliberate — otherwise the signal fires on every case the day a team
starts declaring intent). That is an *absence*, so the row would be "open the dashboard and see no
tile", which is equally true of the signal being broken. It is asserted in
`automationThatNeverStartedHasNotStopped` and named here so the gap is on the record rather than
looking like an oversight.

### The M2 rollups — §101

*Every row here needs the **scheduler tick** (`PT3M`, then `PT15M`). `L4-40` must be read **before**
the first tick, which is why `A3-3` reads it in the execution lane and this row is its record.*

| # | Drive | Expect | src |
|---|---|---|---|
| L4-40 | *(read at `A3-3`, before any tick)* Record a result against `TP-1`'s run, then open **Plan → Progress** immediately | Counts include the result just recorded, and the caption reads **"Computed now"**. **This is §101's third finding as a screen**: the first cut read the empty rollup and reported every case as not-run, which renders exactly like a plan nobody has started. The absence of "updated every 15 minutes" is the assertion — a cadence sentence beside a live figure is a false claim. | §101-13c |
| L4-41 | Wait for one tick (or restart the app to take the `PT3M` delay), reload the same page | Same numbers, and the caption now reads **"Figures as of HH:MM (Nm ago)" — updated every 15 minutes**. Two loads of one page answering the same numbers from two sources is the whole point; a difference here is a rollup that disagrees with the live path and `RollupAgreementTest` failed to see it. | §101-1c |
| L4-42 | On the same page, switch the aggregation dropdown to **Worst**, then back to **Latest** | Worst answers **"Computed now"**; Latest answers a timestamp. The same screen legitimately has two staleness behaviours depending on a dropdown, and this row is the check that the reader can *tell* — §65's two-contradictory-percentages defect is this with the difference hidden. | §101-4 |
| L4-43 | A plan whose executions are **older than 14 days** but whose rollup ran minutes ago | **Two lines, both true, neither styled as the other.** "nothing has been executed against this plan in over two weeks" in the blocked colour, and "Figures as of …" in secondary text below it. One line would have to pick which age it meant, and whichever it picked would be wrong half the time. **A company provisioned today cannot have a 14-day-old execution** — either back-date `test_execution.executed_at` in `psql` or record the row as not driven and why. | §101-5 |
| L4-44 | Open **`Smoke`**'s execution history after a tick | Rows for every run its cases ran in, with the caption's timestamp. **A tag-defined suite is the discriminator** — the one-column arm would show an empty history here and a populated one for a folder, and the two look equally plausible. Compare the pass rates against the same suite's grid count. This also completes `M2-46`, which had no runs to read when `A1` ran. | §101-13d |
| L4-45 | Record a result in a **new run** of that suite, then reload the history **before** the next tick | The new run **appears**, at the top. Not a stale number — a row that is not there is what the rollup alone would give, and the person who has just finished a run is the first to meet it. | §101-13j |
| L4-46 | A suite whose runs are **all** newer than the last tick | Every row present, caption **"Computed now"**. The all-tail case: `computedAt` is the oldest rollup instant *used*, so with none used the honest answer is null. | §101-2 |
| L4-47 | Tag a case that ran in an **old** run into `Smoke`, wait a tick, reopen its history | **The old run's totals do NOT move.** §101's first finding, and the only place it is visible: `membership_snapshot` freezes what the suite held when the run happened (§6p open question #32). A total that moves from 43 to 44 is a past pass rate rewritten, and both readings look equally correct. | §101-13a |
| L4-48 | Remove a case from `TP-1`'s scope, wait a tick, reload Plan → Progress | The case is gone from the totals and from **Not run**. The prune its sibling must never grow — without it the plan's totals stay too high for ever with no row that looks wrong. Cross-check `SELECT count(*) FROM plan_snapshot WHERE plan_id = …` in `psql` before and after. | §101-13b |

### The instance, the empty member, and the deployed build

| # | Drive | Expect | src |
|---|---|---|---|
| L4-22 | Re-run `provision-company` against this company, open a project's status vocabulary; then record a **WIP** result and open the plan's Progress tab | A seventh status **WIP** — blue, not final, not counting as a pass. Existing projects do **not** get it from the migration (M9's decision), so this is the check that the documented top-up path works. A WIP result counts as **not run**, not as a verdict, and coverage does not move. | §97p3a-97,98 |
| L4-36 | Sign in as **`newbie`** (no project grants at all): My Work, then any team board, then the rail | **`200 []`** rendered as the empty state on both — never a 404, never an error. The personal board names no project, so nothing can fail to resolve; this is the first screen a new member sees and it must read as an empty queue, not a broken product. The rail shows **My work** and **All projects** with an **empty** switcher (a folder icon, not a key, when collapsed), and the picker says "You don't have access to any project yet". | §83-18, §85-21, §91-39 |
| L4-37 | `lead` (both projects) and `engineer` (`OLB` only) opening **the same team board** | `lead` sees cards from both. **`engineer` sees strictly fewer** — the `MOB` cards are absent, with no error. §84's headline decision, and unreachable with one project or one persona. **Put `lead` and `engineer` in one team and give each work in both projects**, or the row is vacuous. | §83-17, §85-20 |
| L4-38 | A member **not on the team** opens its board | It renders. Membership is not a requirement (§6k) — a team board a lead's manager cannot open is a board for nobody. | §85-22 |
| L4-39 | Footer: `npm run dev`; then `VITE_APP_VERSION=9.9.9 npm run build && npm run preview`; then after a real `gh workflow run deploy.yml -f version=<v>`, hard-reload the deployed site; then deploy a second version | **`TCMS dev`** (a plausible number here means a hardcoded literal is back — the exact defect this replaced), then **`TCMS 9.9.9`** proving the `define` reaches the emitted bundle, then **`TCMS <v>`** matching the dispatched input and the GHCR tag, then a **changing** number. One correct reading is consistent with a literal that happens to match; two are not. The `web` job greps `dist/assets/` for the dispatched version, so the automated half is covered — the deployed reads are the part a human must see. | footer 1–4 |

---

# F — faults and responsive, swept once, last

*Stage 13, and it is last because it arms `tcmsFaults` — every request is poisoned until the switch
is off, so a failure in an earlier lane run after this one would be unattributable. Disarm between
rows with `tcmsFaults.off()`.*

> **How to drive these — §110's second sitting paid for all three of these.** ~~"arm the fault, then
> reload X"~~ → **superseded**: a reload clears the switch before the page's first request (F13), so
> arm the fault and reach the target by **in-SPA navigation** (click a real link, or `pushState` +
> a `popstate` event); evict the query first if an earlier screen already cached it. The tab under
> test **must be the active tab in a foreground window** — a hidden tab throttles
> `AbortSignal.timeout` so hard that a 3 s budget does not fire in 15 s, and every timeout row then
> renders as the endless spinner it exists to catch (F12). And drive `expire()` against a screen
> **already on the page**, never across a navigation, or dev's StrictMode double-mount supersedes
> the fabricated 401 and the row falsely reads as "the 401 was swallowed" (F14).

**These are strictly stronger than they were.** The switches used to be an MSW handler, so they could
only fault the mock — the one place the failures they simulate could not occur. Since §107 the seam
is in front of `fetch` in `src/api/client.ts`, so everything downstream of the injected response is
the production path, against the real backend.

| # | Drive | Expect | src |
|---|---|---|---|
| M7-1 | `tcmsFaults.hang()`, reload **My Work**, a **team board**, and a **plan** | Loading state, then **failure on the timeout budget** — never an endless spinner. The page chrome stays on screen throughout: the header is not inside the region. | §83-9, §85-13 |
| M7-2 | `tcmsFaults.status(503, 30)`, reload, then `tcmsFaults.off()` and press **Try again**, on each of the three | Retry offered and working. On the team board it must **not** read "No such team" — a 503 wearing the 404's sentence sends the reader after a team that is still there. | §83-10, §85-14 |
| M7-3 | Load each screen, then `tcmsFaults.status(500)` and let it refetch | The board **keeps its cards, marked stale** rather than blanking. On My Work a blanked refetch reads as "your queue is clear", which is the worst possible wrong answer on that page. | §83-11, §85-15 |
| M7-4 | `tcmsFaults.expire()`, reload My Work and a team board | The session recovers **in place**. These are bookmarks opened cold in the morning, which is exactly when a token has expired. | §83-12, §85-16 |
| M7-5 | `tcmsFaults.only('/teams')` + `tcmsFaults.status(500)`, open a team board | The **board still renders** under the fallback title "Team board". The name is chrome from a second read; gating on it blanks a board that loaded fine. | §85-17 |
| M7-6 | `tcmsFaults.only('/conditions')` + `tcmsFaults.status(503, 30)`, open a case editor | The conditions panel shows its own warning and **the rest of the case still saves**. A failed library read must not take the editor down with it. | §95-73 |
| M7-7 | At **390 px**: the review panel with a two-line comment and a replied thread; the reviewer chips; the settings screen; the board with two review cards; the suite rail with the ten suites, one badged | Nothing scrolls the page sideways. The version history is not pushed off screen; comments wrap and the reply's indent does not force horizontal scroll; chips wrap and the picker/Ask stack; the three switches, number field and mode select stack with the RE_REVIEW warning wrapping and Save/Discard reachable; cards wrap with the `linked` chip not pushing the title into overflow; the suite **name** ellipsizes rather than the badge wrapping. ~~three depths~~ — suites are flat since §98, so the depth clause is gone and the badge/ellipsis check is what remains. | §76-12, §79-12, §80-12, §77-16, §81-11, §75-11 |
| F-1 | Resize the window **by hand** to below `lg` on an execution screen | Split degrades to Focus, **dropping the rail rather than squeezing it**. §100's one responsive claim, recorded as undriven because `resize_window` had no effect on the rendered viewport — a hand resize is the workaround, and the row exists so the gap is not inherited a third time. | §100-111 |
| M7-8 | Console across the whole pass | 0 client errors; `window.__tcmsErrors` empty. | §69-18 |

---

# Where a row went

Row ids are permanent. `M` no longer means "the mock pass" — it is the name a row was born with, and
handoff §98 and §102–§106 quote several of them.

| Old lane | Now in | Exceptions |
|---|---|---|
| `M1` (the frame) | **`A5`** | `M1-29` → `L2` (it is a server refusal, not a mock handler's) |
| `M2` (the repository) | **`A1`** | `M2-47` → `A2` (it is about a plan's scope); `M2-46` is begun in `A1` and finished at `L4-44`, which is the first tick with runs to read |
| `M3` (plans) | **`A2`** | `M3-15` → `A3`: the delete it expects to be refused needs a run to refuse on, and there is none until the execution lane |
| `M4` (execution) | **`A3`** | — |
| `M5` (review) | **`A4`** | `M5-28`, `M5-29`, `M5-30` → `A5`: they are board rows, and the board is `A5` |
| `M6` (reports) | **`A6`** | `M6-2` dropped, `M6-13` and `M6-20` retargeted — see the table at the top |
| `M7` (resilience) | **`F`** | — |
| `L0` | **`L0`**, three rows | `L0-2` → `A3`, `L0-3` and `L0-5` → `A2`, `L0-4` → `A1`, `L0-6` → `A4`. All five needed a row that `provision-company` does not create. |
| `L1`, `L2`, `L3` | unchanged | `L3-12` → `A0` (it writes `team_membership`, and that is where teams are made) |
| `L4` | **`L4`**, thinned to the clock-bound rows | `L4-1`…`L4-7` → `A0`; `L4-8`…`L4-16` → `A4`; `L4-17`, `L4-19`, `L4-20`, `L4-21` → `A1`; `L4-18` → `A3`; `L4-30`…`L4-35` → `A6`; `L4-40` is *read* at `A3-3` and recorded in `L4` |

New rows added by this fold: `A0-1`…`A0-4`, `A1-1`, `A1-2`, `A3-1`…`A3-3`, `F-1`. They are setup and
clock-starting steps that used to be prose in a prologue; a step with no expectation beside it is a
step somebody skips.

---

# Not reproducible in a browser — recorded so the absence does not read as an oversight

- **Simultaneous writes with no `If-Match`.** Two testers closing a run as the last result lands; two
  leads moving one suite; a lead approving while another returns a plan to draft; two admins saving
  the review settings form. The target state is absolute in each, so both orders are legal — except
  the settings form, where `project_settings.lock_version` **is** maintained but **nothing sends the
  header**, so the second write wins silently. Adding it is a later slice; the column is annotated
  now precisely so that slice does not first have to discover it was reading `0` for ever (§74).
- **The true optimistic-lock race on a plan**: two editors saving *after both passing* `If-Match`.
  `@Version` catches it at flush and `PlanController#onWriteRace` maps it to the same 409, so the
  handler is reachable in principle and unverified in practice.
- **Two people submitting one artifact for review in the same instant.** `V22`'s partial unique index
  decides it; the loser gets a constraint violation, not a second open review. Queued as a backend
  check.
- **A sibling company's account offered as a team member, or asked to review.** No picker lists them,
  so the refusal is unreachable from the UI — asserted at the server (`TeamEndpointTest`: *"a SIBLING
  COMPANY'S user is refused — 404, and no row is written"*).
- **Comment anchors.** §6g wants comments anchored to a step or field; `anchor_type`/`anchor_id` are
  NULL on every row and accepting one needs a `PeopleDirectory`-shaped port into `repository` first.
- **A cross-project review card on a personal board.** §6l's "My Work" board exists but the *board*
  is per project, so the reviewer inbox is per project too.
- **§70 had nothing to drive at all.** It changed `contractIntrospection.ts` and two test files;
  `grep -rln contractIntrospection src/` returns exactly those two, so it is in no bundle and the
  build output was byte-for-byte §69's app. What stands in for it is one command:
  ```bash
  cd frontend && grep -rn "api\.delete" src/     # must return NOTHING — the client's verb is `del`
  npx vitest run src/api/contractOperations.test.ts src/api/apiResponseTypes.test.ts
                                                 # 8 DELETE call sites, 1 upload among them
  ```

# Rows this pass cannot reach on its own instance

Named here rather than left to be discovered mid-lane. Each needs something a single freshly
provisioned company does not have, and each row says the same thing where it sits.

| Rows | What is missing | The options |
|---|---|---|
| `M3-61`, `M3-62`, `M4-39`, `M4-56`, `L4-29` | **Scale** — 200+ cases, 10+ in one reference, 25+ failures | Bulk-create in `MOB` through the API, or record as not driven |
| `L4-28`, `L2-10` (second arm) | **A second company** | `provision-company --slug=other` once, for both |
| `M2-48` | **A database that migrated in place across `V33`** | The Hetzner box, or record as unreachable |
| `L4-43` | **An execution older than 14 days** | Back-date `test_execution.executed_at` in `psql`, or record as not driven |
| `L4-39` | **A real deploy** | `gh workflow run deploy.yml` — and note §108's rotation must have happened first |

# Still owed, found and not fixed

- **The plan's case chips resolve through the case list's first 200 rows** (§97 phase 6). There is no
  by-ids read on the contract, so a plan naming an individual case outside that page shows *"a case
  outside the loaded list"* — removable, but unnamed. Not reproducible without a project of that
  size; the fix is a server-side read.
- ~~**Three tag controls still offer an empty option list**~~ → **superseded by §102**: all three now
  read `useProjectTags`. It was not the three-line change the entry predicted — `BulkActionDialog`'s
  locator sat on the `TextField` root rather than the input, so the field could never be typed into
  and no suggestion could have been driven. See `M2-49`…`M2-52`.
- **The artifact register's plan filter is `useState`, not the URL**, so a plan's register cannot be
  linked to while every other filtered screen can. An older gap than §65; tracked here.
- ~~**§100's rows 101–110 were run in *mock* mode**~~ → the marks are **removed** (see the top of this
  file). The mock is deleted and `CLAUDE.md` says live is the one that counts, so `M4-13`…`M4-18` and
  `M1-13` are ordinary unrun rows. This is not a regression in coverage; it is coverage that was
  never there being written down accurately.
- ~~**§100 row 111 could not be driven**~~ → carried forward as **`F-1`**, with the hand-resize
  workaround written into the row. It was a bullet nobody would execute; it is a row now.
- **Four rows still assume a plan can be Started and Closed** — `M3-13`, `L2-5`, `L2-6`, `L3-6`, and
  `M4-27` depends on them. §97 phase 3a reduced the plan to three states and these were never
  revisited. `A2`'s opening box makes settling it the first thing that lane does.








---

## §117 — CI result ingestion

**No rows, and the reason is that there is no frontend surface.** This slice is backend-only: three
endpoints (`POST /ingestions`, and `GET`/`POST`/`DELETE /automation-mappings`) whose caller is a CI
job, not a browser. `openapi.yaml` and `schema.d.ts` carry the shapes; nothing in `src/` calls them.

Two screens are named by the slice and are not built, so **their rows belong to the slices that
build them**, not here:

- **The ingestion history** — `ingestion_job` per project, with each job's unmatched list. The
  repository read exists (`IngestionJobRepository.findByProjectIdOrderByCreatedAtDesc`) and has no
  endpoint, deliberately: an endpoint with no consumer is the shape §116's dead reads had.
- **The unmatched-test binder** — the screen that turns an ingestion's `unmatched` array into
  `POST /automation-mappings` calls. This is the surface that makes declining §6c's auto-created
  case stubs a design rather than a gap, and **until it exists that decision is only defensible for
  a caller who can read JSON**. Worth stating plainly rather than leaving implied.

The token screen (§116) is still the next frontend slice; this one adds a second reason for it.
→ **built in §118**, and `A0-12` is where its credential meets this slice's endpoint.

---

## §118 — the token screen

**Twelve rows, and they are not in one block.** `A0-8`…`A0-16` sit in the **A0** lane because that
is where a Company Admin is signed in and where `MOB` gets created — the project picker needs it,
and the rows write a company-level fixture the way A0's other rows do. `L1-21` and `L1-22` are in
the **permission matrix**, because what they check is a tier and not a feature.

Three of them carry more than their own weight:

- **`A0-11`** is the pass's only irreversible screen. Esc and the backdrop must do nothing, and the
  reason is that every other create dialog in this area closes on success — the defect is a
  *refactor toward consistency*, and it destroys a credential with a success message on top.
- **`A0-12`** is the only row in the whole file that proves the screen produced a **working**
  credential rather than a plausible string, and it closes M3 end to end: §116 mints, §118 hands
  over, §117 ingests. It is also the only browser-lane row that reaches an endpoint with no
  browser surface.
- **`L1-21`**'s assertion is an *absent* request. Nothing on screen distinguishes a page that gated
  its read from one that fired it and swallowed the 403 — the Network tab is the whole check.

**No mutation rows.** This slice adds no server behaviour; §116's twelve cover the endpoints it
calls, and `A0-12`/`A0-15` exercise the credential's two ends from the browser lane instead.

---

## §119 — the ingestion history and the unmatched-test binder

**Eleven rows, all in the A3 lane** (`A3-4`…`A3-14`), because they need `A1`'s cases to match
against and `A0-12`'s already-posted report to read. They are the first rows in this file to drive
`/automation-mappings` from a browser — §117 shipped those three endpoints with no consumer in
`src/`, and §118's `A0-12` reached ingestion by `curl` alone.

Three carry more than their own weight:

- **`A3-8`** asserts a **request body**, not a screen. A client that normalized `testName` would
  look identical in every pixel and would break every mapping in the company the day its rule and
  the parser's diverge — silently, because a mapping that matches nothing looks exactly like a test
  nobody bound.
- **`A3-10`** closes §6c's ladder end to end through the product: a test that matched nothing,
  bound by a person, matching on the next build. Until this row exists the binder is a screen whose
  effect is only asserted in a unit test.
- **`A3-14`** is the browser half of `IH-1` — a project's history must not show another project's
  jobs, and the read layers a `projectId` predicate over RLS in one of the seven files §117's
  closing note names.

**No mutation rows here.** §119's eight (`IH-1`…`IH-8`) are in the backend queue; these are the
same properties observed from the other side where a browser can see them.

---

## §121 — flake detection and quarantine (M3 slice 6, phases 1–2)

**No rows, and this is a statement rather than an omission.** Phases 1 and 2 shipped the scoring
job, `V38`'s `case_flake`, and `PUT /projects/{projectId}/cases/{caseId}/quarantine` — **and no
screen**. Nothing in `src/` calls the endpoint, so there is no flow a browser could drive that
`curl` would not drive better.

This is §116's and §117's shape exactly: both were backend-only and both recorded "no browser rows,
and the screen that will carry them is the next slice." Here that was **phase 4** — ~~which is not
built~~ → **shipped 2026-08-07, and its eight rows are `M2-54`…`M2-61` in the `A1` repository lane**,
where the case detail already lives. They name:

- The flake panel on the case page, and the one thing it must not do — render a **null**
  `flakeScore` as `0`. The two mean opposite things (*not enough evidence* against *evidently
  stable*), the API ships `executions` and `transitions` precisely so the panel can say which, and
  a component that formats `score ?? 0` passes every type check. **There turned out to be a third
  state above both** — `flake === null`, no row at all — so `M2-54`…`M2-56` are three rows and not
  two.
- The quarantine action, its required reason, and the **release path clearing the reason on screen**
  rather than only in the row.
- **`M2-54` is the only one a fresh lane can drive**, because a flake row needs the recompute to
  have run over ingested results. The rest are marked to run after `L4`. A row that cannot be driven
  yet is recorded with its reason, which is this queue's own rule.
- ~~The board's quarantined-flaky card (**phase 3**, also not built)~~ → **phase 3 shipped, and its
  six rows are `M1-29`…`M1-34` in the A5 board lane**, where the state they need already lives. They
  author their own fixture from the console, because there is still no screen that quarantines —
  the same shape `M6-1`…`M6-8` use for the report pipeline.

**A row that cannot be driven yet is recorded as absent with its reason, never as a lane nobody
notices is empty** — which is the failure mode the generator above has been in for the whole life of
the board, and the one this slice exists to end.

---

## §124 — the BEST removal and the two unwired gates (M3 slice 8)

**Four rows, and two deliberate absences.** This slice is mostly server-side; what reaches a screen
is one dropdown losing a value in two places.

| Row | Lane | What to drive |
|---|---|---|
| `M3-63` | A2 (plans) | On a plan's **Progress** tab, open the aggregation dropdown. **Four options** — Latest result per case · Latest result per case, per configuration · Worst result per case · Every execution counted. **No "Best result per case".** Then edit the URL by hand to `?aggregation=BEST` and reload: the region must **fail with a message**, not fall back to Latest and render a number. A saved link answering a different question under the same name is the whole harm the removal exists to prevent, and the URL is the only place a stale `BEST` can still arrive from. |
| `M3-64` | A2 (plans) | Under the same dropdown, switch **Latest → Worst** on `TP-1` and watch the **blockers list**. It must not empty: the case that failed and later passed stays on it under every policy the product still offers. That was the one thing `BEST` changed on this screen, and it is what a release meeting reads. |
| `M6-41` | A6 (reports) | **Generate report** → `aggregate_execution`: the policy select offers the same four, in the **same order** as the plan page. Two screens offering one dropdown in two orders means the same click does two things. |
| `M6-42` | A6 (reports) | Generate an aggregate report under **Worst** and read the header: the policy is printed by name on the rendered report. This is the row that proves the removal reached the printed artefact and not only the picker. |

**Not driven, and stated rather than left to look like an oversight:**

- **`run:execute` has no browser row and cannot have one.** The only principal V3 withholds it from
  is Automation Service, which is a token attached to a CI job and never renders a screen. Every
  human role that can reach the execution grid holds it. The gate is asserted where it is
  observable — `RX-1` in the mutation queue, which was run. `ExecutionGrid`'s header records why the
  SPA's `PermissionGate` deliberately does not ask for it, and what would change that (§110's F5:
  authorable roles).
- **`artifact:delete` has an endpoint and NO SCREEN.** `DELETE /projects/{id}/artifacts/{id}` has no
  consumer anywhere in `src/`, so the register still has no way to remove a mis-registered link
  through the product. That is a *smaller* version of the shape this slice exists to close — a key
  with no consumer became an endpoint with no consumer — and it is recorded as unbuilt rather than
  claimed: the delete control, its confirmation, and the two 409 sentences it must render are a
  frontend slice, and §118's dialog is the pattern it should follow. **Until it is built there is no
  browser row to write**, because there is nothing on any screen to drive.

---

## §126 — attachment owners: the case, the page, and the file artifact (M3 slice 10, phase 4)

**Eleven rows in three lanes, and one deliberate absence.** `attachment.owner_type` had two writers
of six until this slice; it now has five, and three of them have a screen. The rows below split by
the lane whose **state** they need: a case exists in `A1`, a doc page and the artifact register in
`A2`, and the two rows about a credential and a byte limit belong with the other transport failures
in `F`.

**Every one of these needs a real file to hand.** Author them once, at the top of `A1`, and reuse:

| File | Make it | Used by |
|---|---|---|
| `wire-diagram.png` | any small PNG | `M2-62`, `M3-65` |
| `sample-data.csv` | three lines of text | `M2-63`, `M2-64` |
| `sign-off.pdf` | any small PDF | `M3-66`, `M3-67` |
| `empty.txt` | `: > empty.txt` — **zero bytes** | `F-9` |
| `dump.zip` | `mkfile 25m dump.zip` (or `dd`) — **over the 10MB limit** | `F-8` |

### A1 — a case's own files

| Row | Drive | Expect |
|---|---|---|
| `M2-62` | On `TC-1`'s detail, **Attachments** → *Attach a file* → `wire-diagram.png` | A row appears with the filename, `180 kB`-ish, **your name** and the time. The panel sits **below the steps and above Traceability**, in the content column and not the metadata sidebar. Then **reload**: it is still there — the list is `TestCase.attachments` on the detail read, so a row that vanishes on reload means the invalidation fired and the server did not write. |
| `M2-63` | Attach `sample-data.csv` as well, then click **each filename** | Both download to disk under their own names. **Neither opens in a tab and neither renders a thumbnail** — that is the current design, not a defect (see the absence note below). A **401** or a sign-in page here is the failure this row exists to catch. |
| `M2-64` | As **`engineer`** (holds `case:edit`, `case:delete` only `OWN`), open a case **`lead` created** and attached a file to | **Attach is enabled and Remove is enabled**, on a case whose own **Delete button is disabled**. Two opposite verdicts in one screen is the check: an authorship arm copied from evidence would disable Remove here, and it would look like security working. |
| `M2-65` | As **`viewer`**, open the same case | The files are **listed and downloadable**; Attach and Remove are **disabled with a reason on hover** (§31). Not hidden — a reader needs the sample data the steps depend on. |
| `M2-66` | Remove a row, then reload | Gone, and staying gone. Then check the disk if you can reach it: this is a **hard** delete and the bytes go with the row. `psql`: `SELECT count(*) FROM attachment WHERE owner_type='CASE'` drops by one. |

### A2 — a page's files, and the register's third kind

| Row | Drive | Expect |
|---|---|---|
| `M3-65` | On `DOC-1` in `/docs`, the **Attachments** panel below the page → attach `wire-diagram.png` | A row appears. The panel is its **own card beneath the prose**, not a block inside it. Then open `TP-2`'s **Business requirements** tab (which shows the same page if it is linked): the attachment is there too — one page, two keys, and `invalidatePageEverywhere` is what keeps them in step. |
| `M3-66` | `/artifacts` → **Register** → *What is it?* → **A file to upload and keep here** | The URL and page pickers disappear and a **Choose a file** button takes their place, with the helper text naming the 10MB limit **and** saying the bytes are fixed at registration. Press Register with **no file chosen**: refused on the picker (*"Choose a file to register."*), no request sent. |
| `M3-67` | Choose `sign-off.pdf`, category **SignOff**, scope **One plan** → `TP-1`, Register | **One** request in the network panel: `POST /projects/{id}/artifacts/files`, `multipart/form-data` **with a boundary**, two parts — `artifact` with `Content-Type: application/json` and `file`. A part sent as `text/plain` is the failure mode, and it surfaces as a 415 about media types rather than anything about artifacts. The row lands in the register with kind **File**, the filename under the title and the size in the Size column. |
| `M3-68` | Click the registered file's title | It **downloads** rather than opening — the one attachment response in the product served `Content-Disposition: attachment`. Beside it, an `EXTERNAL_URL` row still opens in a new tab. Two adjacent rows, two behaviours, and the check is that the file row is not an anchor. |
| `M3-69` | As **`engineer`** (`artifact:edit` = `PLAN_SCOPE`), open the dialog and pick the file kind | The scope selector is still pinned to **One plan** with the project option disabled and its reason on hover. **Bytes change nothing about who may write a row** — a second dialog for uploads is exactly where this would have been forgotten. |

### F — what only a fault or a bad file shows

| Row | Drive | Expect |
|---|---|---|
| `F-8` | On a case's Attachments, pick `dump.zip` (25MB) | Refused **client-side**, before any upload, naming the file and both numbers (*"26 MB … up to 10 MB"*). Nothing in the network panel. The server's own answer is a **413 refused by the servlet container before any handler runs**, so it carries no problem document and there is nothing the SPA could render beside a control — this refusal is the only place the user can be told. |
| `F-9` | Pick `empty.txt` (zero bytes) | Refused with a **different** sentence (*"…is empty"*), not the size one. `POST /artifacts/files` answers a zero-byte part with a 400; the case and page endpoints would happily store an attachment whose download is a zero-length file. |
| `F-10` | Attach a file, then `tcmsFaults.hang()` and click the filename | The download **gives up on the 60s budget** with a message beside the row, and the row stays on screen to press again. Not a corner toast, and not a spinner that never ends. `tcmsFaults.off()` after. |

**Not driven, and stated rather than left to look like an oversight:**

- **There is no inline preview and no thumbnail, on purpose.** The server serves a case's and a
  page's attachments `inline` with their stored content type, and the contract's own copy talks
  about a screenshot rendering beside the steps — but **an `<img src="/api/v1/…">` sends no
  credential in this app** (the session is a bearer token; `SecurityConfiguration` is `STATELESS`
  with no cookie), so it answers 401 and renders as a broken image on every row. Making it work
  means fetching each image into an object URL, i.e. an eager full-size download of every image on
  page load with no thumbnail endpoint to make it cheap. Shipped as download-only and recorded here;
  the real fix is a server-side thumbnail route or a credentialed navigation path, and both are
  slices. **`EvidencePanel` has the un-fixed version of exactly this** — an `<img src>` and an
  `<a href>` against `Evidence.url` — which is why `M4-7`'s *"rendered thumbnail (not a broken
  image)"* is expected to **fail** against a real deployment. That row was written in mock mode and
  has never been driven live.
- **`artifact:delete` still has no screen**, unchanged from §124's note. This slice made a `FILE`
  artifact the first kind whose deletion destroys bytes, which makes the missing control more
  costly rather than less — but building it is a separate frontend slice and there is still nothing
  on any screen to drive.
- **No row for re-uploading or detaching an artifact's file**, because neither exists:
  `attachment_id` is `updatable = false` and replacing the document is *superseding* it (plan §6n).
  The absence is the design; a row asserting a control is missing would be a row nobody could fail.

## §127 — the four unrendered block types, and the editor that could author three (M3 #48)

**Nine rows in one lane, plus one that belongs in `F`.** `DocBlockType` has had six values since M0
and only two were rendered; four of the six now have a renderer and three have an editor control.
Everything here needs a documentation page, so the rows sit in **`A2`**, after `M3-65` has attached
`wire-diagram.png` to `DOC-1` — that attachment is the fixture `M3-72` and `M3-73` depend on, and no
new file has to be authored for this slice.

> **The two rows most worth driving first are `M3-73` and `M3-75`.** `M3-73` is the one no gate can
> reach — an image that renders at all proves the credential travelled, and the failure mode it
> catches (a broken image on a specification) is exactly what §126 phase 4 predicted for
> `EvidencePanel`. `M3-75` is the round trip §93 left half-open for four months.

### A2 — the six block types, read and written

| Row | Drive | Expect |
|---|---|---|
| `M3-70` | Open `DOC-1` → **Edit**. Add a block, set its type to **Table**, press **Start the table** | Two cells appear labelled **Column 1** and **Column 2**, with a *Column name* placeholder. The first row's **Remove row** is **disabled** — there is no "has a header" switch anywhere, because the first row always is one. Fill in `Tier` / `Daily limit`, **Add row**, fill `Basic` / `1000`, put `Transfer limits` in **Caption**, Save. |
| `M3-71` | Read `DOC-1` back | The table renders as a **grid with a heading row**, under the caption. Not a caption above nothing, and not the word "Transfer limits" alone — which is what this page did before, for every table anyone stored. Then open the same page through a plan's **Business requirements** tab if one is linked: **identical rendering**. Two `BlockView`s used to disagree here. |
| `M3-72` | Back in the editor, press **Add column** | A third cell appears on **every** row at once, header included — never on the row you were in. The server refuses a ragged table by naming the short row's index, and this is what stops anyone ever seeing that message. Then **Remove last column**, Save, and confirm the reader shows two columns again. |
| `M3-73` | Add a block, type **Image**. Choose `wire-diagram.png` from **File**, put `The checkout screen wireframe` in **Alt text**, Save | **The picture renders on the page**, with the alt text as a visible caption beneath it. **This is the row that cannot be reached by any gate**: an `<img src="/api/v1/…">` sends no credential in this app and answers 401 while rendering as a broken image, so a broken-image icon here is the defect and not a missing file. Check the network panel: the request carries an `Authorization` header and the `<img>`'s `src` is a `blob:` URL. |
| `M3-74` | With that image block saved, go to the page's **Attachments** panel and **Remove** `wire-diagram.png` | Refused with a **409** naming the block: *"An image block on this page shows this file. Remove or repoint that block first."* The row stays, and the picture stays. Then delete the block in the editor, Save, and remove the file again: **204**, gone. There is no foreign key doing this — the reference lives inside a JSONB document. |
| `M3-75` | Add a **List** block. The control beside the type now reads **Indent**, not Depth | Set it to `3`, Save, and read the page back: the bullet is **indented three levels**. §93 gave a bullet an indent depth and the reader has drawn it ever since; **this control did not exist**, so the field could be read and never written. Drive it on both screens — the documentation page did not indent bullets at all before this slice. |
| `M3-76` | Add a **Code** block with three lines including a long unbroken URL | Renders **monospace, in a bordered panel, with the line breaks kept**. The long line **wraps or scrolls inside the block** — the page itself must not gain a horizontal scrollbar. Before this slice a code block was an ordinary paragraph with its newlines collapsed. |
| `M3-77` | In the editor, change the **Image** block's type to **Paragraph** and Save | Accepted, with no 400. Every type-specific field is dropped at submit based on the type, so the file reference does not travel with it — and switching type while writing is ordinary. Do the same from **Table** to **Paragraph**: the cells go and the caption stays as the text. |
| `M3-78` | On a page with **no** attachments, add an **Image** block | The File control is replaced by a message pointing at the page itself, not an empty dropdown. Then try to Save with the alt text blank on a page that *does* have a file: refused on the **Alt text** field (*"Describe what this image shows…"*), landing on that block's row rather than as a banner. An empty **paragraph** stays legal — that rule is one `else if` away and must not be made to agree. |

## §172 — the block-link screen, and the affordance this file's own charter already assumed

**Five rows, all in `A2`, and they should be driven BEFORE the charter's `DOC-1` fixture is finished
rather than after.** The fixture note at the top of this file (`Doc pages, OLB`) instructs the pass
to *"link cases to the bullets from the documentation editor (`M3-19`'s affordance)"* — and until
§172 **there was no such affordance anywhere in the SPA**: `useLinkBlock` and `useUnlinkBlock` had no
caller in `src/`, so `M3-19`'s chip, `§165 RC-2`'s pair, `M6-36`'s ladder and `M6-38`'s relationship
change all read a link the pass had no way to create through the product. That is the finding these
rows exist beside: **the charter described a screen the build did not have, and no gate could see
it** — the hooks typechecked, and nothing asserts that a hook is called.

`M6-38` is now executable by its own fallback (*"record it as a new link and delete the old"*) and
still not by its primary reading: **a link's relationship cannot be edited in place**, only created
and withdrawn. If that reads as a defect, it is one about this screen and not about the report.

| Row | Drive | Expect |
|---|---|---|
| `§172 XM-1` | On `DOC-1` in `/docs`, under the **Refunds** heading's first bullet, press **Link a case** | A dialog opens naming the block it was opened from — *"List — <the bullet's words>"*, cut to a line. **Two toggles**, Test case and Suite, with Test case selected. The case picker **opens on the first twenty cases before anything is typed**; a control that is empty until two characters are typed reads as broken to someone who does not know a key. Relationship defaults to **Verifies** with its own sentence beneath it. |
| `§172 XM-2` | Pick a case that **passed**, leave Verifies, press **Link** — Network tab open | One `POST .../doc-pages/{pageId}/blocks/{blockId}/links` carrying **exactly** `sourceType`, `sourceId`, `relationship`. **201.** The chip appears under that bullet immediately — `KEY — Title · Verifies` — and the margin's **Not tested** marker is replaced by the coverage readout on the next read. Then **press Link again and choose the same case**: **200**, no second chip, no error dialog. Re-asserting a recorded fact is not a conflict, and a duplicate row would double every count drawn off `doc_link`. |
| `§172 XM-3` | Open the dialog on bullet 2, pick a **case**, then switch the toggle to **Suite** and pick a suite. Submit with the Network tab open | The payload says `sourceType: 'SUITE'` with the **suite's** id — never `TEST_CASE` with it. This is the one failure the toggle exists to prevent and it is invisible from the response: a suite id is a real row of the wrong table, so the server's project check passes and the link points at something nobody chose. Then read the chip: it links to `/cases?suiteId=…`, and the case chip beside it links to the case's own detail route. |
| `§172 XM-4` | As **`viewer`** open `DOC-1`; then as **`engineer`** (holds `doc:edit`) | Viewer: every chip is **readable and clickable**, **Link a case** is **disabled with a reason on hover** (§31), and there is **no Remove** on any chip. Engineer: both live. The two verdicts must appear in the same page for the check to mean anything — a control disabled for everybody passes every "is it disabled?" row. |
| `§172 XM-5` | On the **Disputes heading** (a heading, which carries no coverage marker), press **Link a case** and link one; then **Remove** it | Both work. The heading has no coverage margin *and* has the link control, on purpose: the server accepts a link to any block, so withholding the control here would be the client holding a staler copy of the rule — and it would strand an existing heading link with no way to withdraw it. ~~**Remove does not confirm**, matching the case end's half of the same act~~ → **§173 gave both ends the same confirmation**; drive `§173 UL-1`…`UL-3` below instead. |

## §173 — one confirmation, two ends of one act

**Three rows, and they are only meaningful as a set** — the point of the slice is that the two
screens behave *identically*, so a row driven on one end alone proves nothing. `UL-1` is in `A2`
(needs `DOC-1`'s links from the charter), `UL-2` and `UL-3` are in `A1` (need a case with an
external link, which `§169`'s rows author).

| Row | Drive | Expect |
|---|---|---|
| `§173 UL-1` | On `DOC-1`, press **Remove** on a bullet's case chip. Read the dialog, then **Cancel**. Reload | Title *"Remove this link?"*, the case named as `TC-… — <title>` (**not** "this link", not a uuid), the consequence naming the **coverage recount**, a red **Remove link** button and a neutral Cancel. Cancel closes it, **no request in the Network tab**, and the chip is still there after the reload. Then confirm for real: the chip goes and the margin's marker recounts. |
| `§173 UL-2` | On the case at the other end of a **git** link, Traceability → **Remove**. Read the dialog | The same title, the same button, and the target named as **one line of text** — `acme/checkout-service · src/Refund.java:40–58 at 4f9c2b1`, pin included — and **not** as a clickable link. A confirmation you can navigate away from is a control the reader did not mean to use. The consequence sentence here says the **code and the case are untouched**, and does *not* mention coverage: an external link feeds no marker. |
| `§173 UL-3` | Both ends, side by side: compare the two dialogs | Same title, same button label, same danger colour, same Cancel — **differing only in the named object and the consequence sentence**. That is the whole slice: one act must not have two behaviours depending on which screen reached it. A difference in the title or the button is a regression, not a variation. |

## §174 — the claim corrected in place, at both ends

**Five rows.** `M6-38` in the reports lane has asked for this since §115 and named its own fallback
while waiting; **that row's primary reading is now executable** and it should be re-read against
these rather than against the fallback. `RL-1`…`RL-3` are `A2` (need `DOC-1`'s links), `RL-4` is
`A1` (needs a case with an external link), `RL-5` is `L1` (needs two personas).

| Row | Drive | Expect |
|---|---|---|
| `§174 RL-1` | On `DOC-1`, click the relationship control on a bullet's link | A menu of the three, each with **its own sentence beneath it** — *"Counted as coverage"* against *"context, not coverage"* — and the current one marked. The words alone do not carry the difference, which is why the menu shows the help and the create dialog does too. |
| `§174 RL-2` | Choose a different one, Network tab open | **One `PATCH`** on `.../doc-pages/{pageId}/links/{linkId}` carrying `{relationship}` alone — **not** a POST on the block, and **not** a DELETE followed by a POST. Then reload: the new claim survives, **and the link's identity did too** — check `psql` if you can reach it, `created_by` and `created_at` must be unchanged and `updated_by` must now be your user. That pair is the whole point of `V48`; the old workaround rewrote both. |
| `§174 RL-3` | Re-open the menu and choose **the value it already has** | **No request at all** in the Network tab. The server would answer 200 and write nothing, which is right of the server and pointless from the client — a round trip that cannot change anything still invalidates the page. |
| `§174 RL-4` | On a case with an **external** link, Traceability → the relationship control. Then look at a **block** link's control in the same panel | The external one is live and its `PATCH` goes to `.../external-links/{linkId}`. The block one is **disabled, not absent** (§31) — that claim is corrected on the documentation page, the same split that gives a block link no Remove here. Two verdicts in one panel is the check. |
| `§174 RL-5` | As **`viewer`** open both screens | The control is **visibly disabled on both**, and hovering still gives the current value's meaning. This is the row that would have failed silently before the control became a real `<button>`: as a MUI `Chip` it rendered a `div`, so `disabled` was a CSS class and the gate was decorative — it looked right on screen and exposed nothing to a screen reader or a test. |

### F — the transport lane

| Row | Drive | Expect |
|---|---|---|
| `F-11` | On a page with an image block, run `tcmsFaults.status(503, 30)` and reload | The image block shows a **failure beside itself with a working download link**, not a broken image and not only a corner toast. The bytes may be perfectly fine and unrenderable (a TIFF, a PDF attached as a diagram), so the download is the point. `tcmsFaults.off()` after, and confirm the picture returns. |

**Not driven, and stated rather than left to look like oversights:**

- **A create still cannot carry an image block, by design.** A page has no attachments until it
  exists, so the server refuses one on `POST` with a message naming the sequence (create, attach,
  reference). There is no create-time block editor on any screen, so there is nothing to drive —
  the refusal is reachable only from a hand-written request, and `DocPageEndpointTest` owns it.
- **`EvidencePanel` still has the un-fixed `<img src>` and `<a href>`.** `M4-7` remains expected to
  **fail**. This slice built the credentialed image path (`useAttachmentBlob` + `DocBlockBody`) and
  deliberately did not sweep evidence with it; that is a separate slice and one more surface.
- **No row asserts a seventh block type renders as prose.** The renderer's `default` arm exists for
  a server a version ahead of the bundle, and no deployment can produce one — a unit test drives it
  instead, which is the only observer that can.

---

## §128 — the case editor's autosave buffer (M3 #57, decision round 3 step 8)

Added 2026-08-08. `test_case_draft` got its first writer and the case editor got autosave, a restore
offer, and a discard. **9 rows, none driven.**

> **`M2-67` is the row that cannot be reached by any gate, and it is not close.** Every automated
> assertion here is about one browser: the *point* of a draft buffer is that the work survives
> something the suite cannot do — closing the tab. A test that never closes a tab proves the request
> was made, not that the work came back.
>
> **`M2-71` is second, and it is the one this slice is honest about being uncomfortable.** There is
> **one buffer per case**, so two people editing the same case share it. Driving it needs two
> accounts and two browsers, and what it demonstrates is a *designed* consequence rather than a
> defect: the second person's draft replaces the first's, and the offer names whose it is. If it
> reads as wrong when actually driven, that is a finding about the schema's 1:1 key and belongs in
> a decision, not a patch.

### A1 — losing the tab, and getting the work back

| Row | Drive | Expect |
|---|---|---|
| `M2-67` | Open `TC-1` → **Edit**. Change the title to `Checkout — reworked` and add a step. Wait ~3 seconds for the status line to read **"Backup saved …"**. Then **close the tab outright** — no Save, no Cancel. Reopen `TC-1` → **Edit** | A banner above the form: **"Unsaved changes from a minute ago"**, naming *you*, with **Restore** and **Discard**. Press **Restore**: the title and the step come back exactly as typed. **This is the whole slice.** Before it, closing the tab lost the work with no trace. |
| `M2-68` | Same, but press **Discard** instead | The banner goes, the form still shows **the saved case** — discarding a draft is not discarding the case. Reload the editor: **no banner**, because the row was deleted rather than merely dismissed. A banner returning here would be the product re-offering something you refused. |
| `M2-69` | Open `TC-2` → **Edit** and change nothing at all. Wait 5 seconds | **No status line, and no request.** The autosave is gated on the form being dirty, so merely opening a case must not buffer it — otherwise every case in the project acquires a phantom "unsaved changes" offer that is just a copy of itself. Confirm in the network panel: no `PUT …/draft`. |
| `M2-70` | Open `TC-1` → **Edit**, type continuously in the Description for ~10 seconds without pausing, then stop | **One** `PUT …/draft` about two seconds after you stop — not one per keystroke and not one per field blur. The status line goes **"Saving a backup…"** then **"Backup saved HH:MM"**. The debounce is on the *content*, so tabbing between fields without changing anything sends nothing either. |
| `M2-71` | **Two accounts, two browsers.** As the Test Engineer, open `TC-3` → **Edit**, type, wait for the backup. As the Test Lead in the other browser, open `TC-3` → **Edit** | The lead is offered a banner naming **the engineer**: *"E. Engineer left work on this case without saving it."* Not "you". There is one buffer per case, and this sentence is the only thing standing between the lead and publishing a colleague's half-finished text under their own name. |
| `M2-72` | Continuing `M2-71`: as the **lead**, press **Save changes** on `TC-3` (with or without restoring). Then as the **engineer**, reload the editor | The engineer's banner is **gone** — the lead's save cleared the shared buffer. This is a designed consequence of the 1:1 key, recorded here so that driving it is a decision point rather than a surprise. The engineer's own unsaved work in *their* open form is untouched; it is the *buffer* that went. |
| `M2-73` | Open `TC-4` → **Edit**, type, wait for the backup. In another tab, have someone else (or yourself as another account) **save** `TC-4`. Back in the first tab, reload the editor | The banner carries the extra sentence in bold: **"The case has been saved by someone else since this draft was written, so restoring it will discard their change."** The draft is **still offered in full** — advisory, never enforcing. This is the check `If-Match` structurally cannot make: the editor's own precondition comes from the fresh read and is correct. |
| `M2-74` | Restore a draft (`M2-67`), then press **Save changes** immediately without touching anything | The save succeeds and lands on the case detail. Reopen the editor: **no banner**. The server clears the buffer on every successful save, so a restored-and-saved draft must not be re-offered — being offered here would read as "your save did not land", about a save that did. Also confirm **Save changes is enabled** the moment the restore lands, rather than reading *"Nothing has changed yet"*. |
| `M2-75` | Open the **New case** form (`/cases/new`) and type | **No status line and no `…/draft` request of any kind.** Stated rather than left to be discovered: the buffer's primary key is a foreign key to `test_case`, so a case being created has nothing to key a draft on and the New Case form is exactly as lossy as it was before this slice. |

### F — what only a fault shows

| Row | Drive | Expect |
|---|---|---|
| `F-12` | In the editor with unsaved changes, run `tcmsFaults.only('/draft')` then `tcmsFaults.status(503, 30)`, and type | The status line reads **"Could not save a backup of your changes. Your work is still here — use Save changes to keep it."** and **no toast appears** — the caller is a timer, so a corner notification firing mid-sentence once per interval, about something you did not ask for and cannot act on, is worse than the silence it replaces. `tcmsFaults.off()`, type again: the backup succeeds on the **next change** without a reload. |

**Not driven, and stated rather than left to look like oversights:**

- **No row drives a draft larger than 256 KB.** The ceiling is a field error the server raises, and
  authoring a quarter of a megabyte of case by hand is not a browser pass — `CaseDraftEndpointTest`
  owns it.
- **No row drives the buffer surviving a browser *crash* rather than a deliberate close.** They are
  the same code path from the server's side; the difference is only whether an unload handler runs,
  and this design deliberately has none.

---

## §129 — clone case, plan and run (M3 #59, decision round 3 step 8)

Added 2026-08-08. Phases 1–2 shipped three endpoints whose only caller was `curl`; phase 3 gave each
one a screen. **10 rows in three lanes, none driven.** The rows sit in the lane whose *state* they
need — a case exists in `A1`, a plan in `A2`, a run in `A3` — and every one of them clones a fixture
those lanes already author, so nothing new has to be created first.

> **`M4-59` is the row no gate can reach, and it is the reason this section exists.** A cloned run
> re-evaluates §6g's approval gate from scratch and **never inherits the override**, so a run that
> was legal in March refuses in August unless somebody takes the override again, on the record. The
> backend test pins the refusal; what it cannot show is whether a person who meets that 409 can find
> their way forward from the sentence on screen.
>
> **`M2-79` is second.** The clone of an automated case is created as *To be automated* because the
> automation key cannot be copied — a silent status change with a dialog note in front of it. The
> note is asserted in a unit test; whether a reader connects it to the chip they then see on the new
> case is only answerable live.

### A1 — cloning a case

| Row | Drive | Expect |
|---|---|---|
| `M2-76` | Open `TC-1` → **Clone**. Leave everything alone and press **Clone case** | Lands on a **new** case detail with a **new key** — not `TC-1`. Title reads `Copy of <the source's>`, lifecycle is **Draft** even if `TC-1` is approved, version is **v1**, and the steps, tags, classification and conditions are all there. The folder crumb is the source's. |
| `M2-77` | Clone `TC-1` again, this time editing the title to `Full refund — EUR` and choosing a **different folder** in the dropdown | The copy lands in the chosen folder and the grid's per-suite counts move for **both** folders (the source's is unchanged, the target's is +1). Check the tree, not just the crumb: a clone that reports the right folder on the detail and the wrong count in the tree is the invalidation half being wrong. |
| `M2-78` | On the case that has attachments (`M2-62`'s `wire-diagram.png`), clone it, then **download the file from the copy**, then **delete the file from the source** and download from the copy again | The file downloads **both times**. This is the byte copy, and the second download is the whole assertion — a reference-shared blob would 404 here with nothing having failed, which is exactly why §6v's "by reference" was refused. |
| `M2-79` | Clone a case whose automation status is **Automated** (set one in the editor first, with a key) | The dialog shows the note about the key not being copied. On the copy: status **To be automated**, **no automation key**, and — after the health tick — no `Automation stopped reporting` signal chasing a key it never had. |
| `M2-80` | As the **Viewer**, open a case | **Clone is present and disabled**, not missing (§31). Same for the Automation Service persona if one is signed in — `case:create` is quarantine-only there. |

### A2 — cloning a plan

| Row | Drive | Expect |
|---|---|---|
| `M3-79` | Open `TP-1` (the one with sections, tags, prose and criteria from `M3-5`/`M3-6`) → **Clone**, name it `Regression 4.4` | The copy carries **every section in order**, the tag arm, both prose fields and **both criteria lists**. This is the row that catches the tempting implementation: routing a clone through `POST /plans` drops all six and still answers 201. Compare the two Plan tabs side by side. |
| `M3-80` | On the copy, open **Version history** | **One version, v1, describing the plan as it is** — not a v1 with no sections followed by a v2 a second later. A cloned plan born with a history saying somebody rewrote it is what create-then-edit would have shipped. |
| `M3-81` | Clone an **approved** plan | The copy is **Draft**, and its runs list is empty — the source keeps its runs and its approval. |

### A3 — cloning a run

| Row | Drive | Expect |
|---|---|---|
| `M4-59` | With `A3`'s approval gate **switched on** and at least one unapproved case in the run, clone `TR-1` | A **409** in the dialog banner naming the unapproved cases, with **Clone it anyway** beneath it — enabled for the Lead, **disabled** for the Test Engineer rather than hidden. Press it as the Lead: the run is created and the audit log carries the override against **this** request, not the source's. |
| `M4-60` | Clone a **completed** run, typing `4.3.1` in Release and leaving Build empty | The copy is **Not started**, every execution **Untested**, release `4.3.1`, build **the source's**. The empty field keeping the source's value is the behaviour the helper text promises, and the one a prefilled-then-cleared field would break. |
| `M4-61` | Add a case to one of the suites the source run was scoped by, **then** clone the run | The copy has **the same number of executions as the source** — the new case is not picked up. A run stores no scope; the clone carries the resolved set. Stated on the dialog, and this row is what proves the sentence true rather than reassuring. |

**Not driven, and stated rather than left to look like oversights:**

- **No row changes a cloned run's configuration or assignee.** Neither field is on the dialog, and
  that is recorded in `RunCloneDialog`'s header rather than left to be discovered: this SPA has no
  configuration picker anywhere (`RunCreateDialog` sends `null` and takes the project default), and
  a run has no assignee to copy because the column is on `test_execution`. Both are a slice with a
  picker in it. Until then the contract's own defaults — the source's configuration, an unassigned
  checklist — are what the product sends.
- **No row drives a cross-project clone**, because the path makes it structurally impossible: the
  destination is the source's project by construction. It needs classification revalidated against
  the target's vocabularies (§6d) and is its own slice.
- **No row retries a clone with the same `Idempotency-Key`.** Forcing it needs a fault injected
  between the server's write and the client's read; `CL-3` and `CL-21` in the mutation queue own it,
  where the key can be sent twice deliberately.

---

## §133 — the `Agent` role, and the axis a role picker cannot read off scope (M3, MCP plan §2)

**Lane:** the administration lane, with a Company Admin — the same state `§118`'s token rows need,
plus **Teams & roles** and a project's **Access** screen. No fixture is authored for this: `V40`
seeds the role into every company, so the state is any provisioned company.

The product change a person can see is small and entirely about **where `Agent` appears**. It is on
`GET /roles` like the six, and it must be offered in exactly one of the four places that read that
endpoint — which is the thing no gate can check, because all four render a list the server sent.

### A1 — where `Agent` is offered and where it must not be

| Row | Drive | Expect |
|---|---|---|
| `A3-18` | **Administration → Teams & roles**, read the "What each role can do" reference | **Six cards. No `Agent`.** It is a ceiling a credential carries, so a reference card beside a Grant button teaches that somebody can be given it. `Company Member` is absent too, and always has been — server-side. |
| `A3-19` | Open **Grant a role** from that screen, at company scope and again with a project chosen | `Agent` in **neither** list. It is PROJECT-scope, so the scope filter alone would pass it through at the project scope — this row is what separates the two axes. |
| `A3-20` | A project's **Access** screen → "What each role can do here" | Four project roles, **no `Agent`**, and the panel does not read as empty or short. |
| `A3-21` | **Administration → API tokens → Issue a token**, open the Role select | `Agent` **is** offered, between Automation Service and the rest, with its description. This is the only place in the product it can be chosen, and the three rows above are what make that true rather than accidental. |
| `A3-22` | Choose `Agent` and watch the **Expires** field | It fills itself with a date ~90 days out in **local** wall-clock, the label gains its required marker, and the helper text changes to say why. Type a different date first, then re-pick `Agent`: **the typed date survives.** |
| `A3-23` | Clear the Expires field with `Agent` chosen and submit | A **400** landing on the Expires field, not in the banner: *"An agent token has to expire…"*. The rule is the server's — the pre-fill is a nudge — and this row is what proves the two agree. |
| `A3-24` | Issue the `Agent` token, then read the token list | One row, role **Agent**, the expiry rendered, **Last used** reading `Never used` (§132's column). Revoke it. |

**Not driven, and why:**

- **No row exercises what an `Agent` token can and cannot do.** There is no client for it in this
  product — that is the CLI and MCP server, blocked on the MCP plan's §8 — and driving it means
  `curl`, not a browser. `AG-9` in the mutation queue owns the intersection end-to-end, and it is
  the highest-value unrun row in this slice.
- **No row grants `Agent` to a person.** The point of `A3-19` is that no screen offers it; forcing
  the request needs a hand-built `POST`, which is `AG-3`.

---

## §134 — Automation Service leaves the grant pickers (M3, `V41`)

**Lane:** the administration lane again, same state as §133's — a Company Admin, **Teams & roles**,
a project's **Access** screen, and **API tokens**. No fixture is authored: `V41` flips a seeded row,
so the state is any provisioned company.

**Why there are rows at all when no frontend file changed.** That is precisely the claim to check.
The pickers have filtered on `assignable` since §133, so the role is supposed to disappear from
three screens and stay on the fourth with nothing edited — and "nothing edited" is exactly the
condition under which a gate has nothing to say. Every row below is a list the server sent.

**One row is about a state the product will not create afresh** (`A3-27`) and therefore needs a
grant that already exists. Make it the way an instance made it before today: issue the `POST` by
hand, against a **pre-`V41` database** — or, equivalently and more cheaply, insert the
`role_assignment` row directly, which is what `RoleGrantEndpointTest` does. If neither is available
in the pass, say so and skip it; it is the only row here that cannot be reached through the UI.

| Row | Drive | Expect |
|---|---|---|
| `A3-25` | **Administration → Teams & roles** → "What each role can do", then **Grant a role** at company scope and again with a project chosen | **Five cards, no `Automation Service`**, and no `Agent` — one card fewer than `A3-18` saw. The role is absent from both grant lists too. The panel must not read as short or broken: it is the *only* screen where a card disappearing looks like a rendering fault rather than a rule. |
| `A3-26` | A project's **Access** screen → "What each role can do here" | **Three project roles** — Test Lead, Test Engineer, Viewer. `A3-20` expected four; this is the same panel one release later, and the difference is the whole slice. |
| `A3-27` | With a pre-existing person-held `Automation Service` grant (see above), open **Teams & roles → role assignments** | The grant **is listed**, with a working **Revoke**, and revoking it succeeds. The failure this row exists for is silent: if `revoke` ever reads `assignable` again, the row still renders and the button still looks live — it just 403s, and the banner says nothing about roles. |
| `A3-28` | **Administration → API tokens → Issue a token**, open the Role select | `Automation Service` **is** offered, with `Agent`. Issue one, confirm the secret is shown once, and read the list row. This is the row that fails if somebody unifies the four filters — and it fails by making **CI credentials unobtainable**, which is louder than `A3-21`'s failure and easier to attribute. |

**Not driven, and why:**

- **Nothing here checks that the refusal is enforced.** No screen sends the request, so the 400 is
  `AS-1`'s job in the mutation queue. This lane can only show that the affordance is gone, which is
  the §31 half of it.
- **No row covers an existing grant still *working*.** A pre-`V41` grant keeps conferring its
  permissions, and seeing that means signing in as its holder and posting a result — the ingestion
  path, not an administration screen. Recorded as a gap rather than driven here.

---

## §138 — the review queue for generated cases (MCP plan phase 6, `V43`)

**Lane:** the **authoring** lane, and it needs a fixture no other lane authors — **at least three
proposals**, because every row here is about telling two of them apart. The pass's charter creates
them the only way the product can: `POST /projects/{p}/ai-proposals` with an **`Agent` token**
(§133's `A3-24` issues one), three times.

```
# 1 — nobody checked it, and no evidence
{"testCase": {"title": "Refund a settled payment", "stepFormat": "STRUCTURED",
              "priority": "MEDIUM", "automationStatus": "MANUAL", "suiteId": "<a live suite>",
              "steps": [{"action": "Open the order", "expectedResult": "It opens"}]}}

# 2 — the agent asserts its own work is grounded, with evidence
{"testCase": {…as above, different title…}, "verdict": "GROUNDED",
 "grounding": {"steps": [{"n": 1, "rejected": "invented an API"}],
               "unverified_claims": ["the refund window is 30 days"]}}

# 3 — NAMES A SUITE ID THAT DOES NOT EXIST. This is the one that matters:
{"testCase": {"title": "Refund after chargeback", "stepFormat": "STRUCTURED",
              "priority": "MEDIUM", "automationStatus": "MANUAL",
              "steps": [{"action": "Open the order", "expectedResult": "It opens"}],
              "suiteId": "<a random uuid>"}}
```

> **§144 amended the third payload, and the amendment is the point.** It was
> `{"title": …, "suiteId": …}` — which, when `A4-4` finally ran, would have produced a 400 naming
> **four** fields, three of them nothing to do with the suite, and the row would have read as a
> pass. The contract said `required: [title]` for a year while the server required four (§143's
> F2), and this charter was written from the contract. It now carries the four, so `A4-4` observes
> the *one* refusal it was written for. **File it with `curl`, not `tcms case propose`**: the CLI
> and the MCP server refuse an unacceptable proposal client-side since §144, so the CLI cannot
> produce this fixture and should not be able to.

The third is filed happily and **cannot be accepted** — that asymmetry is the slice's central trade
and `A4-4` is the row that observes it in a browser.

| Row | Drive | Expect |
|---|---|---|
| `A4-1` | **Generated cases** in the rail, with the queue empty (before filing anything) | The empty state names the **MCP server / `tcms` CLI and the `Agent` role** rather than apologising. This is the only place the product explains that generation exists at all, and most projects will never leave this state. |
| `A4-2` | The queue with all three filed | Three rows. Every row shows the verdict **and, beside it in plain text, who claimed it** — *"Reported by the connecting agent"* / *"Nobody has checked this"*. **A row showing only a chip is the defect**; §6w.1a's whole argument is a claim rendered as a finding. Proposal 2 must not be green. |
| `A4-3` | The same rows, looking at **Filed by** | The robot marker on all three (an `Agent` token filed them) **and the person's name beside it** — the token's owner. A row naming only a token, or only a robot, breaks §6j's *"Ana's agent, holding Ana's grants, did it"*. |
| `A4-4` | Open proposal **3** and press **Accept** | A **400 rendered in full, in the panel** — **`suiteId` and nothing else** (see the amendment above; a 400 also naming `stepFormat`, `priority` or `automationStatus` means the fixture was filed from the pre-§144 charter, not that the screen is wrong) — with **Reject still enabled beside it**. Not a toast, and not a disabled screen: this is the designed outcome for a payload stored before the project moved, and the reviewer's next move is Reject. |
| `A4-5` | Reject proposal 3 with a note, then reload | Status `Rejected`, the note shown with who rejected it. The row is **still there** under the Rejected tab — §6w.6 keeps it so the rejection rate is answerable. Press Reject again from the URL: the **first note survives**. |
| `A4-6` | Open proposal **2** and read the grounding panel | The JSON shown **as preformatted text**, verbatim, with the sentence saying TCMS did not produce it and does not check it. Nothing from that payload is rendered as markup or as a product-styled component. |
| `A4-7` | Accept proposal 2 | Lands on the **new case**, which is `DRAFT`, has a real `TC-n` key, and is **owned by the accepting person** — not by the proposal's author and not by a robot. The case's history shows `case.created` like any other. |
| `A4-8` | Go back to **Generated cases**, Accepted tab | Proposal 2 is there, naming the case with a working link. The **Waiting** tab is down to one row. Press Accept again from the accepted proposal's URL: it is gone from the screen — no decision panel on a decided proposal. |
| `A4-9` | Sign in as a **Viewer** and open the queue and a proposal | Both readable. **Accept and Reject are disabled with a tooltip**, not hidden (§31). A Viewer who sees no buttons learns the product cannot do this. |
| `A4-10` | Paste `?status=NONSENSE` onto the queue URL | The **Waiting** list, no error. A typo in a pasted link must not become a 400 the reader cannot see or fix. |

**Not driven, and why:**

- **The credential refusal (`AP-9`) has no browser row and cannot have one.** The rule is that an
  API token may not accept or reject, and this SPA has no token-authenticated session — there is no
  caller in a browser that the refusal applies to. It is driven from `curl` with the `Agent` token
  instead, and that is a **mutation-queue** row, not a browser one.
- **`SUPERSEDED` and `TCMS_VERIFIED` have no rows** because neither value exists. See `V43`.

---

## §144 — the required set the contract had wrong, and the proposal that can now be accepted

**Lane: the authoring lane, immediately after §138's rows** — it reuses that charter's proposals
and needs nothing else. **Two rows, and one of them is the end of a path that has never once
completed.**

§143 drove `POST …/ai-proposals` → PENDING → Accept and got a 400 on three fields that are not on
the proposal and cannot be edited there. That was not drift; the payload had never been valid,
because `TestCaseInput` declared one required field and `CaseEdit.validate` enforced four. The
contract is fixed. What no gate can show is a person completing the round trip: the SPA's own
tests run in jsdom against a mocked query client, and the thing being verified is that a body a
*different process* filed is acceptable to *this* server.

| Row | Drive | Expect |
|---|---|---|
| `A4-11` | File a **complete** proposal with the `Agent` token (§138's payload 1 shape), open **Generated cases**, and press **Accept** | **200 and a real case** — `DRAFT`, a `TC-n` key, owned by the accepting person. The panel shows no field errors. This is §143's F2 path, ending the way it was designed to. `A4-7` accepts a proposal *this pass* authored from the contract; this row's value is that the payload came from outside the browser. |
| `A4-12` | On the accepted case, compare every field against what was filed — title, step format, priority, automation status, the steps, the suite | All present and correct. **The accept is a create, so a field the proposal carried and the create dropped is silent**: nothing is red, and the reviewer signed off on content the case does not have. |

**No row for the client-side proposal refusal, deliberately.** `core/proposal.ts` refuses an
unacceptable proposal in the CLI and the MCP server, neither of which is a browser. It is `LP-6` in
`2026-08-08-packages-live-pass.md`.

**No row for `case edit`, `suite edit` or `plan edit` either**, for the same reason: those are
`packages/` commands, and their live rows are `LP-1` … `LP-5`. The SPA's own editors were not
touched by §144 — they already submitted every field they render, which is exactly why the server
was never wrong about this and why it took a second client to find out.

## §148 — what a 404 says on a screen, now that it says nothing on the wire

**Lane: `L2`, the server's refusals** — every row here is a refusal, nothing lands, and `L2`
already has the personas to hand. Two rows.

The contract declared a `Problem` body on 111 of its 113 404s and the server has never written
one; §148 made the contract honest. The consequence that reaches a person is in `describeError`,
whose 404 branch rendered `error.detail` under a comment claiming *"a 404 detail names the missing
thing"*. There is no detail, so what rendered was `client.ts`'s floor — the status text. **The
heading is "Not found" and the body was the words "Not Found".** That is not visible to any gate:
the branch's own unit test supplied a detail no 404 has ever carried and then asserted it back.

`TeamBoardPage` had already found this and worked around it for one screen (its header names the
"Not found — Not Found" rendering in as many words). These rows are the other screens.

| Row | Drive | Expect |
|---|---|---|
| `EB-1` | As any account, open a case URL with a random UUID in place of the case id — `/projects/<real>/cases/00000000-0000-0000-0000-000000000001` | The error panel reads **Not found** over *"It may have been deleted, or it may be in a project you cannot open."* **Not** the word "Not Found" repeated, and not "The request failed (HTTP 404)." Both of those are what shipped before this slice. |
| `EB-2` | As the **Viewer**, open a URL for a project they hold no grant on (any project id `A0` created that this account was not granted) | The same panel and the same sentence — and *that* is the point of the copy: the server refuses to say whether the project exists, so the screen must not imply either, while still telling a reader who knows they should have access what to suspect. Confirm in DevTools that the response body is genuinely **empty** and the status is 404, not 403. |

**No row for the 401.** Its body is asserted live and on every endpoint by
`EndpointAccessTest.everyOtherEndpointRequiresAuthentication`, which sweeps the whole mapping
anonymously; a browser adds nothing to that. The SPA's 401 copy is fixed text and never read the
wire.

## §149 — the refusal a dialog used to write for itself

The retire-blocked warning now renders the server's sentence verbatim instead of composing one
around a count. Two things a gate cannot see: whether the sentence *reads* correctly in place (it
was written for a `<Alert severity="warning">` and includes its own em-dash clause), and whether the
em-dash and the count survive the round trip through `Problem.errors` at all.

Lane: **8 — company settings and vocabularies**, which already creates a classification dimension
with a parent value. No new fixture is needed beyond one child value under an existing parent.

| Row | Drive | Expect |
|---|---|---|
| `EV-5` | As **Test Lead**, in project settings → classification, add two child values under an existing value, then retire the parent | The dialog **stays open** with the warning *"2 values sit under this one. Retire those first — retiring a whole branch at once would unclassify every case beneath it with nothing on screen naming them."* One sentence, correct plural, em-dash intact. Before this slice the page composed the words around a bare `2`; the words are now the server's and the page prints them. |
| `EV-6` | Retire one child, then retire the parent again | The same warning with **"1 value sits under this one. Retire it first — …"** — the singular arm, which is the server's and not the page's. The page's own composition had the plural bug (`1 value sit under this one`) and could not have produced this. |
| `EV-7` | With the same two children, run `tcms` against the same value — there is no CLI arm for retiring a classification value, so drive `DELETE /projects/{id}/classification/values/{valueId}` with `curl` and an API token | The body's `errors.children[0]` is the **same sentence** the dialog shows. This is the row that would have caught the original: the response a non-SPA caller sees, read without a screen composing anything around it. |

## §156 — the health panel's all-clear, narrowed to what it actually knows

**Lane: 4 — the scheduler-tick lane**, immediately beside `L4-23`…`L4-29`. It needs the same state
those rows do (the hourly `CaseHealthScheduler` tick having run at least once, which is
`matchIfMissing = true` and therefore automatic within two minutes of the stack coming up) and it
needs **no fixture at all beyond a project with cases and no raised signal** — which, before any
result is recorded, is the state a freshly provisioned company is already in.

That is the point of the row. The branch it drives was described in the panel's own comment as
unreachable — *"`healthComputedAt` is null while `case_health` has no writer, which is every
deployment today"* — and it has been the branch every deployment lands in since §53 gave the table
its first writer. Five of the ten signals still have no computation behind them
(`ArchitectureRulesTest.everyMappedColumnHasAWriter`), so the panel was telling the reader that
nothing needed attention on the strength of five checks that had never run.

| Row | Drive | Expect |
|---|---|---|
| `HP-1` | Any account with `report:view`, on a project whose cases have **no** raised signal, after the tick. Open the project dashboard and read the "Needs attention" panel | **"No health signal was raised. Last checked …"** — a statement about the checks. **Not** "Nothing needs attention", which is a statement about the project and is the sentence that shipped from §47 to §156. The timestamp must render (it is non-null here; see below). |
| `HP-2` | On the same screen, confirm the *third* branch is not what you are looking at — `Dashboard.Health.NotComputed` must be absent | The "have not been computed for this project yet" sentence is now the **rare** branch, not the universal one: it needs a project the scheduler has not reached. Worth confirming which of the three you are in, because the two empty branches read similarly and only one of them is honest about *why* it is empty. |
| `HP-3` | Raise a real signal (`L4-23`'s three failures is the cheapest), wait a tick, reload | Tiles render and neither sentence shows. This is the row that says the copy change did not move the boundary between the branches — it narrowed one claim, and nothing else. |

**What no row here can check, stated rather than skipped:** that a case which *is* orphaned,
unlinked, condition-drifted, stale-approved or spec-outdated goes on reporting clean. There is no
way to drive it, because there is no computation to produce the signal — that is the finding, not a
gap in the pass. Closing it is five derivations, each owned by the module that can see its cause,
and the honest sentence is what this slice shipped in the meantime.

## §157 — the stale-approval signal, on the three screens that were already waiting for it

**Lane: 4 — the scheduler-tick lane**, immediately after `HP-1`…`HP-3`. Same tick, different
premise: §156's rows drive a project with **no** raised signal, these drive one where a signal that
had never been computable now fires.

**The fixture is this block's real cost and the pass must author it**, because nothing else in the
queue produces a review row and **three separate settings/permission facts have to line up**:

1. In project settings → review, switch **§6g review on for cases** — it is off by default, and
   `ReviewService.moved` opens no review at all for a project that has not turned it on.
2. Select the case in the grid and **submit it for review** (`SET_STATUS` → `IN_REVIEW`). This is
   what opens the review row and stamps `subject_version_no` at the case's current version. Going
   straight to `APPROVED` from `DRAFT` writes **no review row** — `moved` closes an open review and
   opens none — so a fixture that skips this step produces a case the signal cannot see.
3. **Approve as a different account from the one that submitted it**, because
   `authorMayApproveOwnWork` defaults to false and `refusesEach` will refuse the bulk approval
   otherwise. Either use a second Test Lead session or turn that setting on for the project, and
   say in the result which one was done — they are not the same fixture.

Then open the case and save an edit that changes content, so the version advances past the one the
review names. Leave `Edit after approval` on its default (`RETAIN`); the `RE_REVIEW` arm sends the
case back to `DRAFT` on that edit, which is the state `SA-B3` drives deliberately and at the end.
Then wait for a tick.

The three screens below need no change to have been made to them, which is why they are worth
driving: the facet, the chip and the board card were all written generic over `HealthSignal` and
have counted this signal as zero since M0.

| Row | Drive | Expect |
|---|---|---|
| `SA-B1` | Project dashboard, "Needs attention" panel, after the tick | A **"Stale approval"** tile with a count of 1, at WARNING severity, sorted below any ERROR tile. Clicking it opens the case grid filtered to `healthSignal=STALE_APPROVAL` and the edited case is in it. This is the first tile on that panel that can appear because somebody *edited* a case rather than ran one. |
| `SA-B2` | Open the case itself | The health chip reads **"Approved before its last edit"** (`lib/labels.ts`, not the server's label) with the hover copy from `StatusChip.tsx`, **and** the review panel's own stale banner is showing — *"This case has changed since it was submitted — the review covers v1, and you are looking at v2."* The two must agree: they are one claim drawn from one comparison, and a screen where the banner shows and the chip does not is the disagreement `ApprovalFacts` was written to avoid. |
| `SA-B3` | Set the case's status back to `DRAFT`, wait a tick, reload both screens | Tile gone, chip gone, banner still there. The banner is about the review record and does not care about the ladder; the signal is about a claim the case is no longer making. **If the tile survives, the status term has become a filter** — `SA-4`/`SA-3` in the mutation queue are the same defect seen from the server side. |

**What no row here can check:** that a project which has never turned review on reports an honest
zero rather than a missing feature. It renders identically to a project with nothing stale, and the
only way to tell them apart is to read `review` — which is `SA-5`'s territory, not a browser lane's.

## §158 — the condition-drift signal, and the tooltip that described the wrong comparison

**Lane: 4 — the scheduler-tick lane**, immediately after `SA-B1`…`SA-B3`. Third block on one tick,
and the **cheapest fixture of the three**, which is worth stating because the previous two set the
expectation that a health signal costs a settings change and a second account. This one does not:
no review settings, no approval, no second session. Everything it needs is one author with
`library:manage` and `case:edit`.

**The fixture:**

1. Project → **Conditions** (the §6f library), create an entry — title *"Log in as standard user"*,
   two steps.
2. Open a case, attach that entry as a **precondition**, and **save**. The save is the load-bearing
   step and the one to get wrong: attaching is authored content on `CaseEdit`, so the save is what
   writes the `test_case_version` whose snapshot this signal compares against. A case that was
   never saved after attaching has no snapshot naming the entry and can never drift.
3. Go back to the library and **edit the entry's body** — change one step. Do not touch the case.
4. Wait for a tick.

| Row | Drive | Expect |
|---|---|---|
| `CD-B1` | Project dashboard, "Needs attention" panel, after the tick | A **"Shared condition changed"** tile with a count of 1, at **ERROR** severity — so sorted *above* the WARNING tiles, unlike `SA-B1`'s. Clicking it opens the case grid filtered to `healthSignal=CONDITION_DRIFT` with the case in it. This is the first tile on that panel that appears because somebody edited **something that is not the case** — the drive for it never opens the case at all, which is the whole point of §6f's reference-while-live design and the reason the signal has to exist. |
| `CD-B2` | Open the case | The health chip reads **"Shared condition changed"**, and its tooltip — corrected in this slice — says *"…has changed since the case was last **saved**."* It said *"last reviewed"* until §158, which describes a comparison the server does not make; a project with review switched off raises this signal exactly as readily. **If the tooltip still says "reviewed", the built asset is stale**, not the copy. |
| `CD-B3` | Edit the library entry back to exactly what it said before, wait a tick, reload both screens | Tile gone, chip gone. **This is the row worth driving even if the other two are skipped.** The comparison is content, so reverting the cause reverts the signal; the spelling somebody will propose instead (`condition.updated_at > version.created_at`) leaves the flag standing for ever after this drive, with nothing on any screen able to clear it. `CD-6` in the mutation queue is the same question asked of the tests, and it records that the tests **cannot** currently answer it — this row is the only place the property is observable. |

**What no row here can check:** the *inherited* arm. A condition attached at suite level and then
edited drifts every case beneath the suite by the same mechanism, and the fixture above cannot tell
that apart from the own-attachment case on screen — both render one chip on one case. Distinguishing
them needs two cases under one suite and is a fixture the pass may add if `CD-B1` is cheap enough
when it gets there. The gap `ConditionDrift`'s header names — a suite-level **attach or detach**,
which writes no case version and is therefore *not* reported — is not drivable here at all, because
its expected outcome is that nothing happens.

## §159 — archiving a project, on the two screens that show the state

**Lane: 2 — `A0`, accounts, projects and teams**, at the end of it, after `MOB` exists. Not lane 4:
this needs no tick, no health signal and no review row. It needs one project and one account holding
`project:settings`, which `A0` has already created by the time these rows run — the cheapest fixture
of any block added since §156.

**Why the rows exist at all.** `Project.archived` was `required` in the contract from M0, `false` in
every deployment there has ever been, and read by no file in the SPA or `packages/` outside a test
fixture — because no request could set it (§156's finding, §159's slice). Everything below is a
first observation, not a regression check: there is no earlier pass any of it could have failed in.

**Drive `AR-3` even if the others are skipped.** It is the one row that observes a *decision* rather
than a rendering — the server marks an archived project and deliberately does not lock it — and it
is the row that would catch the opposite behaviour arriving by accident in a later slice.

| Row | Drive | Expect |
|---|---|---|
| `AR-1` | As **Test Lead** on `MOB`, open the project's own page and press **Archive project** | The button becomes **Restore project**, an **Archived** chip appears beside the key and case count, and no dialog is shown on the way (the action destroys nothing and is undone by the same button). No global error toast. |
| `AR-2` | Go to the project picker | `MOB`'s card is **still listed**, with an **Archived** chip. Not filtered out: the server returns retired projects on purpose, and a client-side filter here would make the project unreachable rather than marked — the picker is the only route back to the page that restores it. |
| `AR-3` | While `MOB` is archived, create a case in it (or edit any artifact) | **It succeeds.** Archiving is a mark, not a lock — `DocPageStatus.ARCHIVED` one level up. A refusal here means a later slice has taken the read-only decision without taking it deliberately; the enforcement point exists (`ProjectService.writeGranted` and its three siblings) and adding an arm to it touches 53 call sites, which is why it is a decision and not a tidy-up. |
| `AR-4` | Press **Restore project**, then press **Archive project** twice in a row, then read `audit_event` for the project in `psql` | Two rows only — `project.archived`, `project.unarchived`, `project.archived` in order, and **no fourth row** for the repeated press. A repeated click that writes an audit row buries the change that actually happened, and the second press must also leave `archived_at` **unchanged** rather than re-dating the retirement. |
| `AR-5` | As **Test Engineer** (no `project:settings`), open the same project's page | The button is **present, disabled, and explains itself on hover** — "You don't have permission to change this project's settings in this project." Disabled rather than hidden is §31's rule: a hidden control teaches that the product cannot do it. |
| `AR-6` | As that Test Engineer, drive `PUT /projects/{id}/archive` with `curl` and their token | **403 with a `Problem` body** whose `detail` names the action — "You do not have permission to archive this project." — and **never the permission key**. Belongs in lane **L2** if the pass prefers to keep server refusals together; it is written here so the block reads as one. |

**What no row here can check:** that an archived project reads any differently to a caller who has
never seen it live. It does not, deliberately — the flag is a label, and every list, board and report
in the product treats the project exactly as before.

## §160 — the verbatim CI upload, downloadable at last

**Lane: `A3`, the CI ingestion block**, immediately after `A3-13` and the token-attribution rows.
Not a lane of its own: every fixture these rows need is one `A3` already builds — a live
`Automation Service` credential, a report posted by `curl`, and a job screen reached from the
history. `RR-4` is the exception and says what it needs.

**Why the rows exist.** `ingestion_job.raw_storage_key` has been on the table since `V1` and was
never written; the file CI posted was not kept anywhere. These are first observations, not
regression checks — there is no earlier pass any of them could have failed in, and the fourth row
observes a state **no fixture in this file can create going forward**.

**Drive `RR-2` even if the others are skipped.** It is the row that separates this feature from the
one it is easily confused with: `raw_payload` has always been stored and has always been *the
server's reading* of the report. Only a byte comparison against what `curl` sent shows which of the
two is on screen, and a store that kept the wrong one would serve a valid, plausible file.

| Row | Drive | Expect |
|---|---|---|
| `RR-1` | After `A3-6`, open that job from the history | A **Download the posted report** button in the header, beside the status and format chips — present whether or not anything went unmatched, because it sits outside the unmatched panel. |
| `RR-2` | Press it, then `diff` the saved file against the exact XML `A3-6` sent | **Byte-identical.** Not the parsed payload, not re-serialised, not pretty-printed. This is the whole point of the column and the one thing a green suite cannot tell you about a real filesystem. The saved name ends `-junit.xml` and contains the job id. |
| `RR-3` | Open the job from `A3-10` (Matched 2, Unmatched 0) and look at the header | The button is **still there**. The unmatched panel is empty and renders its "Every test found a case" state; the download must not vanish with it — a report where everything matched is exactly when somebody checks what was actually posted. |
| `RR-4` | `psql`: `UPDATE ingestion_job SET raw_storage_key = NULL WHERE id = '<the A3-6 job>'`, reload the page | **No button at all** — not a disabled one, not a broken link. This is what every job in an upgraded instance looks like, and hand-SQL is the only way to produce it because the writer now always sets the key. Restore the value afterwards, or run this row last. |
| `RR-5` | With the row still nulled, `curl` `GET …/ingestions/{jobId}/report` with `lead`'s token | **404 with an empty body.** Same answer as an id that is nothing at all — the caller's next act is identical, so the two are deliberately not told apart. |
| `RR-6` | As `viewer`, open the same job screen and press the button | **It downloads.** The gate is membership, exactly as for the history: no `ingestion:read` key was invented, and a viewer who may read the verdicts may read what produced them. |
| `RR-7` | On the host, `ls -R` the raw-reports root (`/var/lib/tcms/raw-reports` in compose, or the dev override) | The company id is the first path segment and the job id the second, and **no file appears under the reports or evidence roots.** The three roots are separate on purpose; a store constructed with a sibling's property key is silent until a restore, which is the defect `CA-20` names on the same lines. |

**What no row here can check:** that the bytes survive a container restart. The mount is in
`compose.prod.yaml` and the runbook records both earlier roots defaulting into the container's
writable layer before anyone noticed — but this pass runs against a dev stack, and the row that
would catch it belongs to a deployment check rather than a browser one.

## §161 — nothing to drive, and the row that says so

**§161 changes no runtime behaviour on either stack.** No endpoint, no schema on the wire, no
query, no component, no copy a user reads. The backend diff is one new test class, three corrected
comments and a class header; the contract diff is one `description` block; both `schema.d.ts`
copies were regenerated and their diff is **comments only — no generated type changed**, which was
verified by `git diff` at the time.

So there is one row, and it is a row about the absence rather than a placeholder for a check:

| # | What | Expected |
|---|---|---|
| `CE-1` | With the stack up, open any case with a health signal (lane `A0`'s fixture is enough) and confirm the chip list, the grid facet and the dashboard tile render exactly as they did before §161 | **No visible difference at all.** The row exists because the slice touched `openapi.yaml` and both generated clients, and "only comments changed" is a claim about a generator's output that costs one screen to confirm and is embarrassing to be wrong about. If anything *has* moved, the regeneration was not comment-only and the finding is bigger than this row. |

**Deliberately not queued:** an `INCOMPLETE` health chip. The server has no constant for it and this
slice did not add one, so there is no fixture that can produce the value — which is the state the
whole slice is about, and inventing a row for it would be recording a check nobody can run.

## §162 — the media type changes on every refusal, and no client should notice

**§162 changes no body, no status, no field a screen renders.** What moves is the `Content-Type`
header on every 4xx and 5xx: `application/json` → `application/problem+json` on the refusals written
with `api.Problem`, the filter's 429 and the container's `/error` dispatch. The twenty-one
`ProblemDetail` controllers were already sending it.

The claim being tested is a **negative**, and it is the reason the defect survived: both TypeScript
consumers call `.json()` without consulting `Content-Type`, so nothing *should* change. A negative
asserted from reading the source is exactly what §161 is about.

One row, in lane **`A0`** — it needs no fixture beyond a signed-in session:

| # | What | Expected |
|---|---|---|
| `MT-1` | With the stack up, drive one refusal of each envelope and watch the network tab: a **400** from a case edit with an empty title (`api.Problem`, the record), a **403** from any gated action available to the signed-in role (`ProblemDetail`), and a **404** from a hand-typed bad id. For each, note the response `Content-Type` **and** whether the SPA renders its usual message. | The 400 and 403 both carry `application/problem+json`; the 404 carries no body and no content type at all (§148). **The rendered message is identical to before in every case** — `describeError` reads `detail` off a parsed body and never looks at the header. If a refusal renders as a bare status or a blank notification, the header *is* being consulted somewhere and the finding is larger than this row. |

**Deliberately not queued:** a 429. Driving the rate limit needs an API token and 4 requests in a
minute, which is a `packages/` lane rather than a browser one, and `RateLimitTest` already asserts
that header over real HTTP.

## §163 — the request echo goes away, and no client should notice that either

**§163 removes a property from every refusal written by a `ProblemDetail` controller** — `instance`,
the path the request was sent to, which Spring's return-value handler had been adding on all
twenty-one of them without anything in this repo asking for it. No status, no `detail`, no `errors`,
no media type moves.

The claim is a **negative** again, and it is even better founded than §162's: `instance` occurs in
no consumer in `frontend/src` or `packages/*/src` outside the generated schema, so nothing can be
reading it. That is exactly the reasoning §161 exists to distrust, which is why there is a row.

One row, lane **`A0`** — no fixture beyond a signed-in session, and it pairs with `§162 MT-1`:

| # | What | Expected |
|---|---|---|
| `§163 EC-1` | With the stack up, drive the same two bodied refusals `§162 MT-1` uses — a **400** from a case edit with an empty title (`api.Problem`, the record) and a **403** from any gated action available to the signed-in role (`ProblemDetail`) — and read the **response body** in the network tab, not just the header. | Both bodies are exactly `{type, title, status, detail}` (+ `errors` on the 400) and **neither carries `instance`**. The rendered message is identical to before in both cases — `describeError` reads `detail` and never looked at `instance`. A refusal still carrying `instance` means `ProblemEnvelope`'s advice is not running on that path, which is a bigger finding than this row: the advice is also what sets the media type, so check the `Content-Type` in the same breath. |

**Deliberately not queued:** the container's `/error` body and the filter's 429. Neither ever
carried `instance`, both are asserted over real HTTP in `ErrorDispatchStatusTest` and `RateLimitTest`,
and driving them from a browser needs a malformed request the SPA cannot compose and a rate limit
that needs an API token.

## §165 — two screens that are correct renderings of a key the server never sends

**Neither row is a regression and neither is fixed by §165.** Both are screens the live pass would
otherwise read as *working*, because each renders a defensible empty state for a field the server has
never once put on the wire — and each was argued in a javadoc that said the module supplying it
"does not exist". Both modules exist. The rows are here so the pass **looks for the value rather
than at the widget**, and records what it costs, which is what the corrected arguments now claim.

They are pinned in `ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION` in both directions, so
whichever slice closes one cannot land without deleting its entry, and these rows become live
assertions the moment it does.

| # | Lane | What | Expected |
|---|---|---|---|
| `§165 RC-1` | **A3** — after results exist (needs `TR-1` and at least one recorded result, which A3 leaves behind) | ~~Confirm `GET …/cases` and `GET …/cases/{caseId}` carry **no `lastResult` key at all** — not `null`, absent.~~ → **REVISED BY §166, id kept.** On the case list, find a case you have **just recorded a result for** and read its **Last result** column; then open that case's detail page and read the same chip. In the network tab, confirm both responses now carry `lastResult` as a **key that is always present** — an object for that case, `null` for one never run. | ~~Both read "Never run" for a case that was run minutes ago.~~ The original row added: *"if the column shows the real result, the read model has been built and `MEMBER_DIVERGENCE_BY_DECISION` is stale — say so, because the build should have failed first."* **It did fail first** — §166 deleted both entries as part of serving the field, which is what a both-ways pin is for. So the live expectation is the opposite one: the real status, the real age, and the release in the tooltip. **The row is not retired**, because what it actually observes is unchanged and still unrun — *does the screen agree with the result the tester just recorded* — and see `§166 LR-1` for the timing arm that makes a wrong answer here legitimate rather than a defect. |
| `§165 RC-2` | **A1** — after `DOC-1`'s links are authored (the fixture already links cases to bullets 1–3 and to the intro paragraph) | ~~Open one of the cases `DOC-1` links to … and read the **linked documents panel**~~ → **REVISED BY §167, id kept, same reasoning as `§165 RC-1`.** Open one of the cases `DOC-1` links to — bullet 1's passed case is the clearest — and read the traceability panel on its detail page. Then confirm from the documentation side: `DOC-1`'s bullet still shows the case chip (`M3-19`). | ~~The case's panel is empty while the doc block shows the case.~~ **It must not be, any more.** The panel now shows the linked block's excerpt, the page's key and title, and whether it reads stale — read both ends in the same sitting, because the row is only meaningful as the pair. If the panel is still empty, the mirror has not refreshed yet (`§167 DL-4`'s gap) or the recompute switch is off — check before concluding the field regressed. See `§167 DL-1`/`DL-4` below for the timing arm. |

**Deliberately not queued:** `TestPlan.keySeq`. It is now declared and every plan response already
carried it, so there is nothing on a screen to look at — no SPA code reads it, the plan list is
ordered `keySeq DESC` server-side, and the change is to the generated types alone. `§165 RC-1` in
the mutation queue is what covers it.

### §166 — the last result, now that it is served

**The one thing a pass must not do here is read a stale value as a bug.** `lastResult` is a read
model: it is derived on the `execution.results_changed` outbox event and on the hourly schedule, so
between recording a result and the dispatcher's next tick the grid legitimately still says "Never
run". `LR-1` is the row that establishes what the delay actually is, and it must be run **before**
`§165 RC-1`, or that row's answer is unreadable.

| # | Lane | What | Expected |
|---|---|---|---|
| `§166 LR-1` | **A3** — the moment a result is recorded (same fixture as `§165 RC-1`; run this one first) | Record a result on a case, then reload the case list **immediately** and note what the Last result column says. Reload every ~15s until it changes, and record **how long it took**. Then check `outbox` (or the dispatcher log) for the `execution.results_changed` row. | The column shows "Never run" for a short window and then the real status, without any further user action. **The number is the deliverable** — the design accepts a lag and nothing has ever measured it, so "it eventually appeared" is not the observation. If it never changes, the handler did not run: check the row's status for `DEAD`, and note that `HealthRecomputeOnResultsChanged` shares `tcms.case-health.enabled` with the scheduler. |
| `§166 LR-2` | **A3** — after `LR-1`, with at least one case run inside a release and one outside | Hover the Last result cell for a case executed **inside a release** and read the tooltip; then hover one executed **outside** any release. Open both cases' detail pages and compare the chip, the timestamp and the release line against the run each result came from. | The in-release case shows `· release X` in the tooltip and a Release line on the detail page; the outside-release case shows neither, with no empty label and no "null". The status **kind** must match the run's recorded status even if the project renamed it — the server sends the kind, not the name, so a project whose "Blocked" is called "Environment down" still renders a BLOCKED dot, and the *name* appearing anywhere on this cell would be a finding. |

### §167 — the traceability panel, now that it is served

**Same shape as `§166`: a read model with a refresh lag, not an instant join.** `case_doc_link` is
mirrored on the `documentation.links_changed` event and on the hourly schedule, so a link made or a
page edited seconds ago legitimately has not reached the panel yet. `DL-1` establishes the lag the
way `§166 LR-1` did for the last result, and should run before `§165 RC-2` above.

| # | Lane | What | Expected |
|---|---|---|---|
| `§167 DL-1` | **A1** — create a new link from a `DOC-1` block to a case that had none, then immediately open that case's detail page | Note whether the panel shows the link right away; if not, reload every ~15s until it does, and record how long it took. Then check `outbox` for the `documentation.links_changed` row. | The panel shows nothing for a short window and then the link, with no further user action. If it never appears, check the row's status for `DEAD` and confirm `tcms.case-health.enabled` — the same switch `§166`'s scheduler shares, and since §167 it now gates this panel too. |
| `§167 DL-2` | **A1** — after `DL-1`, edit the title or a bullet's text on the linked `DOC-1` page, then reload the case's detail page after the recompute | Read the traceability panel's per-link marker. | The link now shows **stale** (the page's content moved after the case's last content change) — and ~~once `OUTDATED_SPEC_CHANGED` has a derivation, the case's own health chip should agree with it on the same screen. Until that signal exists, this row only checks the panel's own marker~~ → **REVISED BY §178, id kept: that condition is met.** The chip is derived from this very row, in the statement after the one that wrote it, so **the marker and the chip must both be present or both absent** — this row now checks the pair, and `§178 SL-2` is the same assertion approached from the chip. |
| `§167 DL-3` | **A1** — delete the link (from the doc block, not the case) or soft-delete the linked block/page, then reload the case's detail page after the recompute | Read the traceability panel. | The link is gone — not shown as broken, not shown with an empty title, simply absent. A dangling row (title but no reachable destination) is the finding, not the withdrawal itself. |
| `§167 DL-4` | **A1** — open a case with **no** documentation links at all | Read the traceability panel. | "Not linked to any specification" (or the panel's equivalent empty state) — and this reading is now **true**, not merely the default rendering of an absent key, which is the whole finding this slice closes. |

**Deliberately not queued:** the excerpt truncation and ellipsis. `V46`'s 160-character bound is
server-side and has its own backend coverage (`a long statement is cut at the bound, marked, and put
on one line`); there is no SPA branch on the excerpt's length for a browser pass to exercise
independently.

### §168 — the coverage marker, on screen for the first time

**The SPA has rendered this component since M0 against a field the server never sent**, so every row
below is driving code that has never once run against real data — a different risk from §166/§167,
where the rendering was new and the data was mirrored. The marker reads `case_health`, so it carries
the same refresh lag those two rows measured: run `§166 LR-1` first if the lag has still not been
timed, because a marker that "shows the wrong number" and a marker that "has not caught up yet" are
indistinguishable without it.

| # | Lane | What | Expected |
|---|---|---|---|
| `§168 CV-1` | **A1** — open a `DOC-1` page whose blocks are **not** linked to anything | Read the margin beside each non-heading block, and beside a heading. | Every unlinked paragraph shows the **"Not tested"** chip — not an empty margin, which is what this rendered until §168 folded null and zero into one branch. Headings show nothing at all, which is deliberate: a heading is structure, not a requirement. **An empty margin on a paragraph is the finding**, and it means the server sent something other than `null`. |
| `§168 CV-2` | **A1** — link two cases to one block, one green and one red (the `§166 LR-1` fixture already has both), then reload the page after the recompute | Read the marker, then hover it. | The chip is in the **failed** colour — one red among two is not a verified statement — and the tooltip reads `1 passed · 1 failed · 0 not run` over both case keys. The keys must be the same two, in the same order, as the bullets rendered under the block. |
| `§168 CV-3` | **A1** — click the marker | Follow where it goes. | The case list, filtered to the **first** linked case key. Note whether landing on one case of several reads as intended: the component links to `caseKeys[0]` because a multi-key filter is not expressible in the list's `q` parameter, and this row exists to decide whether that is acceptable or a finding. |
| `§168 CV-4` | **A1** — link a case to a block with relationship **`REFERENCES`** rather than `VERIFIES` (the doc page's link dialog, if it offers the choice; otherwise note that it does not) | Read the block's bullets and its margin. | The bullet is listed and the margin says **"Not tested"** — a reference is not a coverage claim. **If the SPA offers no way to create anything but a `VERIFIES` link, say so in the result**: the server distinguishes three relationships and a UI that can only make one is a gap this row is the right place to discover. |
| `§168 CV-5` | **A1** — with a block linked to a case that has never been executed | Hover the marker. | `0 passed · 0 failed · 1 not run`, chip in the partial/blocked colour. The number that matters is `linkedCases` matching the bullet count: a never-executed case must be **counted and not dropped**, which is the one arm the server computes from an absent row rather than from a value. |

**Deliberately not queued:** the `Passed with comment` divergence recorded in the mutation queue.
Reaching it needs a project whose vocabulary carries a second `PASSED`-kind status with
`counts_as_pass = false`, and no fixture the browser charter authors has one — creating it is a
vocabulary edit plus a result submission plus a coverage-matrix export to compare against, which is
its own charter rather than a row here.

### §169 — linking a case to code, and to a URL

**Two of this panel's three branches had never run against data** — the same shape as §168's marker
one panel over, and the reason every row below is about what is *rendered* rather than about what is
stored. The panel is served from `case_doc_link`, which refreshes on an event: a link made **here**
appears immediately, from the create's own answer, while one made elsewhere waits for the tick.
`§169 XL-3` is the row that tells those two apart, and it is the one to run first.

| # | Lane | What | Expected |
|---|---|---|---|
| `§169 XL-1` | **A1** — open a case, use **Link code or a URL**, choose **Code**, and fill in a repository, a file, lines 40–58 and a **branch name** where the commit goes | Submit. | Refused in place, with the message under the **Commit** field saying a branch or tag moves — not a corner toast, and not a generic "check the highlighted fields" with nothing highlighted. Then correct it to a real short sha and submit again: the link appears in the panel **immediately**, rendered as one identifier — `repo · path:40–58 at sha` — with the relationship chip beside it. |
| `§169 XL-2` | **A1** — link a URL, then click it | Watch where it opens. | A new tab, and the original tab is still on the case. Then paste `javascript:alert(1)` into the same dialog and submit: refused under the field. The two halves are one gate — the scheme is refused at the write and the anchor carries `rel="noopener"` — and this row is the only place both are observable. |
| `§169 XL-3` | **A1** — with the case detail open in **two tabs**, link something in tab 1, then reload tab 2 immediately, then again after a minute | Compare the panels. | Tab 1 shows the link at once. **Tab 2 does not, until the outbox tick lands** — that is the projection, not a bug, and this row exists to measure the window on a real instance the way `§166 LR-1` measured it for the last-result column. Record the actual delay; if the link never arrives, the finding is the recompute, not the panel. |
| `§169 XL-4` | **A1** — as a **Viewer**, open a case that has an external link | Read the panel. | The **Link code or a URL** button is visible and disabled (§31), and **no Remove appears on any link**. Then as an engineer: Remove appears on external links only — a block link has no Remove, because no endpoint withdraws one from the case end. |
| `§169 XL-5` | **A1** — link the same file, same commit, same range twice, then the same file at a different range | Read the panel and the count. | The second identical submit adds **nothing** (the panel does not grow, and no duplicate bullet appears); the different range adds a second link. This is the idempotency rule and its bound, and the SPA is where a double-click actually happens. |

**A gap this slice found and did not close, worth stating where it will be met:** `useLinkBlock` and
`useUnlinkBlock` have **no caller anywhere in `src/`**. The documentation page renders a block's
links and offers no way to create or remove one — so `§168 CV-4`'s parenthetical ("the doc page's
link dialog, if it offers the choice") resolves to *there is no dialog at all*, and the only way to
make a `DOC_BLOCK` link in this product is the API. Every row above is about the **case** end, which
§169 gave a screen; the block end still has hooks and no UI.

## §175 — the team a screen renders beside something else

**Two rows, and the slice's honest position is that it should need none.** §175 collapsed three
declarations of one shape into one `TeamRef`, and the *wire is unchanged* at both surviving
positions: the same two keys, the same values, the same nullability. What changed is that a
generated client now names the type. So these rows exist to prove **nothing moved** — the failure
they would catch is a `$ref` that quietly changed a payload, which is exactly the failure a
contract-only edit is assumed not to have.

Both are `A0` (the accounts-and-teams lane, which is where a team grant is made in the first place).

| Row | Drive | Expect |
|---|---|---|
| `§175 TR-1` | **A0**, after the three teams exist: grant a role to a **team** rather than a person, then open the role-assignments list | The grant's row names the **team**, by name, in the same column a user grant names a person — not a blank cell, not an id. Then a **user** grant beside it: that row's team cell is empty. `RoleAssignment.team` became a `$ref` in this slice and this is the one screen that reads it; an empty cell here means the collapse changed the payload. |
| `§175 TR-2` | **A0**, signed in as a member who is **not** a Company Admin and who is in a team: open the Network tab and read `GET /me` | `teams` is an array of `{id, name}` and **carries no `isLead`** — the third position, whose extra flag §175 removed. The interesting half is the first: `teams` must still be *populated*, because `GET /teams` is gated on `company:teams` and this response is the only way this account can learn its own teams. Nothing in the SPA renders it, so the Network tab is the only observer there is. |

**Stated rather than hidden:** `§175 TR-2` reads a payload no screen shows. That is a legitimate
row — the value is on the wire, a client can name it, and the removal this slice made is only
observable there — but it is a *contract* check wearing a browser row's clothes, and if the pass is
short of time it is the one to drop. `TR-1` is not; it is a rendered column.

## §177 — the code a run tested

**Five rows, all `A3`** — the execution lane, because every one needs a run, and `GT-4` needs the CI
credential `A3-4`'s preamble issues. They slot in beside `M4-30`–`M4-37` (creating a run) and
`A3-6`–`A3-10` (ingestion) rather than forming a lane of their own.

`GT-5` is the row that records a **deliberate absence**, and it is here so that the absence is not
filed as a bug by the person driving the lane.

| # | Drive | Expect |
|---|---|---|
| `§177 GT-1` | **A3**, New run in `OLB`: fill the name, choose a plan, and fill the new **Branch** (`release/4.3`) and **Commit** (a real 7-character sha) fields, then Start | 201, and the run header carries a **third** metadata line: `release/4.3 at 4f9c2b1`, in the muted identifier font, **beside** the release rather than nested inside it. Then create a second run with a branch and **no** commit: the line reads `release/4.3` alone. Both halves are accepted independently and this is the only place that is visible. |
| `§177 GT-2` | **A3**, New run: type `release/4.3` into **Commit** and Start | Refused **in place, under the Commit field**: *"That is not a commit id — 7 to 40 hexadecimal characters. A branch or tag name moves; record it as the branch instead."* Not a corner toast and not a root banner. **The shape rule lives on the server alone** — the client checks length and nothing else — so this round trip is the only way the caller ever learns the bound, which makes the message landing on the right control the whole row. Then type six hex characters: refused. Seven: accepted. |
| `§177 GT-3` | **A3**, open `GT-1`'s run → **Clone**, name it, Clone | The clone's header carries the release and build **and no code line at all**. Not the source's branch, not an empty label. A fresh clone has run nothing, so `TestRunCloneInput` offers no override for either and the columns are left null — driving this is what distinguishes "deliberately absent" from "the dialog forgot to send it". Open the source run again: it still shows its own coordinate. |
| `§177 GT-4` | **A3**, with the ingestion credential: `curl` a JUnit report into `OLB` with `"commitSha"` and `"branch"` in the body and a fresh `Idempotency-Key`, then follow the `TR-n` link from the history | The run header shows the posted coordinate — the CI job is the caller that has it, and until §177 the API accepted these two keys and dropped them silently. Then post again with `"commitSha": "main"`: **400**, and the ingestion history gains **no row** — the report is refused rather than ingested with the field discarded, which is the trade recorded on `IngestionInput`. Read the response: the field error names `commitSha`. |
| `§177 GT-5` | **A3**, the run **list** for `OLB` after `GT-1` | The Release cell shows the release and the build and **not** the branch or the sha, on every row. Deliberate: a commit id is a coordinate somebody follows once they are looking at one run, and a column of shas is noise in the cell the release already occupies. The value **is** on the wire for the list (the detail view is built from the list view's record), so a reader with the Network tab open will see a field the table does not render — that is the design, not a missed binding. |

## §178 — the two chips the traceability panel had to agree with

**Six rows, all needing `A1`'s documentation links AND `L4`'s health tick**, which makes them the
first rows in this file whose *pair* of preconditions spans two lanes. Drive them at the end of
**`L4`**, after `§167 DL-1`…`DL-4` have established that the panel works and that the mirror has a
refresh lag — the chips are derived from the same rows the panel renders, so a panel that has not
caught up produces a chip that is correctly wrong.

**`SL-3` is the row worth the whole block.** Every other row could be satisfied by a derivation
written against `doc_link`; that one is only true of a derivation written against the mirror, and it
is the one a person would file as a bug.

`§167 DL-2`'s expectation is **revised in place above** (id kept): it said the case's own chip should
agree with the panel's stale marker *"once `OUTDATED_SPEC_CHANGED` has a derivation"*. It has one now,
so that clause is live and `SL-2` is its other half.

| # | Drive | Expect |
|---|---|---|
| `§178 SL-1` | **L4**, after `§167 DL-4`: open the dashboard for `OLB`, then a case with no documentation links at all | An **"Unlinked"** tile with a real count — and it will be **large**, most of the project, which is the expected reading rather than a defect (plan §6o's own mock-up shows `Unlinked 156`). The tile is **INFO/neutral, not amber and not red**: this is the one signal whose volume makes severity load-bearing, because a WARNING on most of the repository is a panel people stop reading. Then the case's own chip: "Not linked to any spec", in the quiet colour, and **the tooltip and the Traceability panel's empty sentence say the same thing** — they are two renderings of one row set and disagreeing is the finding. |
| `§178 SL-2` | **L4**, after `§167 DL-2` has left a link showing **stale**: reload that case and read the chip strip **and** the panel together | A red **"Linked spec changed"** chip beside a panel whose link carries the stale marker. **Both or neither** — one without the other is the disagreement §167's one-transaction design exists to prevent, and it is the reason the signal is derived from the mirror instead of from `doc_link`. Hover the chip: *"A specification page this case links to has changed since the case was last saved."* Not "the block", and not "verifies" — both were wrong in the copy this slice replaced, and the wording is what says which comparison the server actually makes. |
| `§178 SL-3` | **L4**: take a case with exactly one link, then **soft-delete the linked page** (or the block), wait for the recompute, reload the case | The panel shows **nothing** — and the chip says **"Not linked to any spec"**, on a case that still has a `doc_link` row in the database. **This looks like a bug and is the design**: the chip agrees with the panel, and the alternative is the product telling you a case is traced to a destination it will not show you. If instead the chip is absent (no "unlinked", no link listed), the derivation is reading `doc_link` and the two surfaces have already diverged. |
| `§178 SL-4` | **L4**: on a case with no links, add a link whose relationship is **References** rather than Verifies, wait for the recompute, reload | The **"Unlinked" chip disappears**, and the block's coverage marker on the *doc page* still says nothing covers the statement. Both at once, deliberately: `UNLINKED` counts any link because the chip sits inches from the list of links, while coverage counts `VERIFIES` only because it answers a question about the paragraph. **A reader who thinks one of these two screens is wrong should read the decision at `SpecLinkage` before filing it.** |
| `§178 SL-5` | **L4**: on a case whose only link is **external** (`A1`'s git or URL link from §169), reload after the tick | **No "Unlinked" chip** — a case pinned to a file at a commit is not invisible to traceability — and **no "Linked spec changed" chip either, ever**, however old the case's last save is. The second half is the one to check twice: `stale` is a literal `false` on that arm, so an external link that ever reports the ERROR means a staleness comparison has leaked onto a target that is not a row in this database. |
| `§178 SL-6` | **L4**: filter the case grid by **"Not linked to any spec"** from the health facet, then by **"Linked spec changed"** | Both facets list a count and selecting each narrows the grid to exactly those cases. **The facet count and the grid must agree** — `L4-54`'s assertion for the automation pair, repeated here because it is the pair of consumers `HealthSignal.attribute()` resolves and nothing checks that the two pick the same rows. Then open the **board**: a `CASE_MAINTENANCE` card naming each signal, with a `linkFor` that resolves. `BoardFacts` gained both of these with a **diff of zero**, so this row is the only thing that shows the generic read was actually generic. |

**Deliberately not queued:** the statement-order property (`SpecLinkage` after the mirror refresh in
one transaction). It is observable only as a one-tick lag, and the refresh lag `§167 DL-1` measures
already makes a short lag the expected reading — so a browser row could not tell the two apart. It is
pinned by a test instead (`aLinkMadeSinceTheLastTickCountsOnThisTick`) and by mutation `§178 SL-M1`,
which is where an unfalsifiable browser row belongs.

## §179 — the chip that stopped deciding how loud it is

**Four rows, all in `L4`**, immediately after §178's `SL-1`…`SL-6` — same preconditions (`A1`'s
documentation links plus `L4`'s health tick), because every row here needs a case that raises *at
least two* signals of *different* severities, which is exactly what §178's fixtures produce.

**`RS-2` is the row worth the block.** It is the only one that shows the defect this slice was named
for from the reader's side: before §179 the case grid's one visible chip was chosen by enum
declaration order, so a case whose automation had stopped reporting rendered "Not linked to any spec"
and hid the failure behind a `+1`. Nothing on either stack could see that — the wire was right, the
component was right, and the *order* was wrong.

`RS-1` and `RS-3` are the two halves of the drift §178 found and did not fix: the same case, on the
dashboard tile and on its own chip, must now agree about colour.

| # | Drive | Expect |
|---|---|---|
| `§179 RS-1` | **L4**, after `§178 SL-1`: open the dashboard for `OLB`, note the colour of the **"Condition drift"** tile, then open a case that raises it | Tile and chip are **both red**. Before §179 the tile was red (server severity) and the chip amber (the SPA's list lacked `CONDITION_DRIFT`), on two screens one tile links between. Check `FAILING_REPEATEDLY` the same way in the other direction: **both amber**, where the chip used to be the red one. This is the pair of members the two copies disagreed about, and the whole reason the copy is gone. |
| `§179 RS-2` | **L4**: in the case grid, find a case raising both an ERROR signal and `UNLINKED` (`§178 SL-3`'s moved-spec case will do once the tick has run) and read the **Health** cell | The chip is the **ERROR** one — "Linked spec changed" — and the `+1` covers `UNLINKED`, never the other way round. Then hover the `+1`: it must read the **labels** ("Not linked to any spec"), not `UNLINKED`. That tooltip printed raw enum codes until §179 and is invisible without two signals on one case; it is the only place in the SPA a reader could meet a constant. |
| `§179 RS-3` | **L4**: open the same case's detail page and compare its chip row against the grid row | Same chips, same colours, **same order** — worst first on both, because both render the server's list in the order it arrived. A detail page whose first chip differs from the grid's means one of the two is sorting, which is the second copy of the ranking this slice deleted. |
| `§179 RS-4` | **L4**: with the case list open, use the browser console to serve one row a signal whose severity is a string the SPA has never seen (`tcmsFaults.drift()` scoped to `/cases`, or edit the response) | The row **fails or renders no colour — it must not render a chip in a default colour as though nothing were wrong.** `severityVar` (`lib/statusColors.ts` since §183) is a closed map over four values and an unknown key is `undefined`, so this row is about which of the two failure modes the grid actually shows. It is the §148/§149 shape — a client compensating silently for a shape the server cannot produce — and the answer decides whether the map needs a fallback or the boundary needs to be louder. Not driveable without the fault switch, which is why it is here rather than as a unit test. |
| `§180 TL-1` | **L4**, after `§178 SL-1` and `§179 RS-1`: open the dashboard for `OLB`, read the label on a **"Needs attention"** tile, click it, and read the filter chip and the grid's Health chips it lands on | **All three read the same words.** The tile that used to say *"Outdated — spec changed"* now says *"Linked spec changed"*, which is what the chip on every case behind it says. This is the whole slice, and it is only observable by making the navigation: the tile and the grid are on two screens, and each was internally consistent. Do the same with **"Stale approval"** → *"Approved before its last edit"*, the largest of the six disagreements. |
| `§180 TL-2` | **L4**: on the same dashboard, read the four **Authoring status** tiles | *Draft · In review · Approved · Deprecated* — unchanged wording, now sourced from `lifecycleLabels` rather than from the server. These four agreed before the slice, so a difference here is a **regression** introduced by the repair rather than the defect it repaired; that asymmetry is why the row exists. |
| `§180 TL-3` | **L4**: hover a segment of a **By feature** bar, then compare it against the same status's chip on a case in that feature | Tooltip reads *"Draft: 9 — open these cases"*, the bar's own heading still reads the **feature path** (`Payments › Refunds`) or the person's name, and the segment's colour is the same grey the Draft chip uses. The label and colour now come from `lifecycleLabels`/`lifecycleVar`; the bar heading is data and still arrives from the server. **Colour is the half no test could ever see** — `toHaveStyle` on a CSS custom property does not resolve it — so this row is the only observer of `var(--color-draft)` actually painting the segment. Check in **dark mode too**: the server used to compose this string, and the reason it was allowed to was that it followed the theme. |
| `§180 TL-4` | **L4**: with the dashboard open, use the console to add a tile with an unknown key to the `health` array (`tcmsFaults` cannot do this — edit the response, or call the endpoint and re-render) | The tile renders **under its raw key** and its link still works. §179's `RS-4` asked the same question about an unknown *severity* and the answer was ugly-or-invisible; here the fallback is deliberate and the row is confirming it, because the "a new server signal just works" property is the one thing the deleted `label` was defended for. A blank tile means `labelForKey` is not on that path. |

## §181 — the owner who left, on the two screens that can now say so

**Four rows, none run** (§65). Ids are `§181 OS-*`; `OS` is unused elsewhere in this file.

This is the first slice whose fixture need is a **person leaving**, and that is worth reading before
the rows: every earlier health signal could be produced by editing a case, running one, or leaving one
alone. This one is produced from the **Members screen**, by an administrator who is not looking at test
cases at all — so `OS-1`'s fixture step is a real offboarding, and the lane's own charter has to
author it. `OS-4` is the row that cannot be driven at all today and says why, which §109 asks for
rather than a silent skip.

The recompute is hourly by default, so every row here waits for a tick or triggers one; the lane
already does this for `§157 SA-1` and `§178 SL-1`, and the same note applies.

| # | Drive | Expect |
|---|---|---|
| `§181 OS-1` | **L4**, needs a fixture the lane must author: in `OLB`, assign a case to a colleague, then **remove that colleague** from the company on the Members screen, wait for a recompute tick, and open the case list | The case's Health cell carries **"Owner has left"** in the same blue as any other INFO chip (**amber until §183**, which took the reading-plan decision; the row is corrected rather than renumbered), and its **Owner** column is empty or shows the person greyed — those two cells are read from different sources, so they are the pair worth looking at together. The chip must appear on **every** case that person owned, not just the one you assigned. |
| `§181 OS-2` | **L4**: hover the chip from `OS-1` | *"This case is owned by somebody who no longer works here, so nobody is maintaining it. Reassign it to give it an owner."* The word **"deactivated" must not appear** — that was the old sentence and it named the one of three states nothing in the product can currently produce. This is the row that would catch the sentence being "tidied" back toward the enum's name. |
| `§181 OS-3` | **L4**, straight after `OS-1`: **re-invite** the same person, wait for a tick, and reload the list | Every chip is **gone**, on every case, with nothing clicked on any case. The signal self-heals from a status update one screen away — which is the property `HealthSignal`'s "no button for lying" rule buys, and the one a reader has no reason to believe until they watch it happen. Note that they are `INVITED` at this point, not `ACTIVE`: an invitation outstanding is deliberately not an orphan, so the chip must clear **before** they accept. |
| `§181 OS-4` | **not driveable — recorded rather than skipped.** The third term of the predicate is `app_user.status = 'DEACTIVATED'`, and no screen or endpoint in the product writes it: `AppUser.deactivate` has four callers and all four are tests | The options, so the next author does not re-discover them: (a) drive it from `psql` against the live stack, which tests the derivation but not any path a user has; (b) leave it until the `user:manage` account screen exists, which is where the writer will be, and drive it there; (c) accept that `OS-1` covers the reachable half and that this half is covered by `CaseHealthRecomputeTest.aDeactivatedAccountIsAnOrphan` alone. **(c) is the recommendation** — the term is one `OR` in a statement a unit test pins directly, and (a) proves nothing about the product. Revisit when the account screen lands. |

## §182 — the case somebody approved before it was finished

**Four rows, none run** (§65). Ids are `§182 CC-*`; `CC` is unused elsewhere in this file.

The fixture this lane must author is the *opposite* of every other health fixture: nothing has to go
wrong and nothing has to age. **Approve a case that is missing something** — which the product will
let you do, and which is the whole point of the signal. `CC-3` is the row that proves the gate, and
it is the one worth doing first, because a lane that only ever looks at approved cases cannot tell
this signal from "most cases in this project".

The recompute is hourly by default, so every row here waits for a tick or triggers one; the same
note as `§157 SA-1`, `§178 SL-1` and `§181 OS-1`.

| # | Drive | Expect |
|---|---|---|
| `§182 CC-1` | **L4**, fixture the lane authors: in `OLB`, take a case with steps and an expected result, make sure it carries **no classification**, approve it, wait for a tick, and open the case list | The Health cell carries **"Approved but unfinished"** in the same blue as any other INFO chip — **amber when this row was written; §183 quietened INFO before the row was ever driven.** This is the one of the three terms a running deployment can actually reach — `CaseEdit` refuses to save a case with no steps and a Gherkin case with no scenario, so the classification term is what a real project raises. |
| `§182 CC-2` | **L4**: hover the chip from `CC-1` | *"This case was approved without its steps, without saying what should happen, or without a classification. Open it to see which."* The word **"required" must not appear** — that was the old sentence (*"Required fields are missing."*) and none of the three is a required field; the case saved perfectly well without them. This is the row that would catch the sentence being tidied back toward the enum's name, which is §181's `OS-2` at a different signal and the fourth time this map has needed the correction. |
| `§182 CC-3` | **L4**: take the same case, send it back to **Draft**, wait for a tick, reload | The chip is **gone**, with nothing about the case's content changed. The signal is a claim about a *sign-off*, not about a case, and this is the only way to see that from the outside. Then approve it again and watch it come back — which is also the self-heal, in the direction a person actually drives it. |
| `§182 CC-4` | **L4**: classify the case from `CC-1`, wait for a tick, and check both the case list **and** the dashboard's "Needs attention" panel | Chip gone on the grid **and** the tile's count down by one, or the tile absent if it was the last. Worth doing together because the tile and the chip are two screens over one column, which is the pair §180 found disagreeing — and because a tile that keeps its count after the chip clears is the read model being served from a cache nobody invalidated. |

---

## §183 — the colour INFO reads in, on the two screens that disagreed about it

**Four rows, none run** (§65). Ids are `§183 IC-*`; `IC` is unused elsewhere in this file.

**This block's fixtures already exist** — `§178 SL-1`, `§181 OS-1` and `§182 CC-1` each author a case
raising one of the three INFO signals, and any one of them serves every row here. What is new is not
a state; it is a **comparison across two screens**, which is the only place the defect was ever
visible.

**Note what this block found in its own file.** `§178 SL-1` has expected the `UNLINKED` tile to be
*"INFO/neutral, not amber and not red"* and its chip *"in the quiet colour"* since §178 — a row
written against a SPA that rendered that chip amber, sitting unrun for five slices while the code
disagreed with it. The check that would have caught this was already written; batching is what kept
it from saying so. `§181 OS-1` and `§182 CC-1` went the other way and described the amber as
correct, and both are corrected in place above rather than renumbered.

| # | Drive | Expect |
|---|---|---|
| `§183 IC-1` | **L4**, after any of `§178 SL-1` / `§181 OS-1` / `§182 CC-1`: open the dashboard for `OLB` and read the left border of an **INFO** tile ("Not linked to any spec", "Owner has left", "Approved but unfinished"), then click through to a case raising it and read the chip | **Both blue, and the same blue.** The tile was already blue and the chip was amber, so the navigation crossed a colour boundary for a severity that never changed — §179's `RS-1` at INFO, a year of screens later. Compare against a WARNING chip on the same grid: **amber must still be there and must still mean something.** A screen where everything is blue has traded one unreadable panel for another. |
| `§183 IC-2` | **L4**: with a grid row raising both a WARNING (or ERROR) signal and an INFO one, read the Health cell **without hovering anything** | The visible chip is the **severe** one in amber or red, the `+1` covers the INFO. This is `§179 RS-2` re-driven for the reason §183 exists: the ordering was already right, and until this slice the reader could not tell it *was* right, because the chip it promoted looked exactly like the one it demoted. |
| `§183 IC-3` | **L4**: put the app in **dark mode** and re-read `IC-1` | The tile border, the chip text and the chip border are all `--color-info`'s dark value and still legible against the sunken surface. Colour is the half no test can see (§180 `TL-3` says so about `--color-draft`), and this is a token that was, until this slice, used only for *decorative* borders — the case-detail inherited-condition outline, the board's chip. **This is the first time `--color-info` carries a triage meaning**, so it is the first time its contrast in dark mode is load-bearing. |
| `§183 IC-4` | **L4**: with the case list open, scan a whole page of rows in the young project and say out loud which rows need work | The rows carrying amber and red are the answer, and there should be **few of them**. This is the row that judges the decision rather than the code: the reading plan was the point, and its success condition is a person's answer to that question, not a hex value. If the INFO chips still dominate the scan, the next move is the chip's *weight* (a dot, an outline) and not its colour. |

---

## §184 — the order the tiles arrive in, now that only one side decides it

**Three rows, none run** (§65). Ids are `§184 TO-*`; `TO` is unused elsewhere in this file.

**No new fixture, and one of these rows cannot be driven without help.** The panel's order is only
observable when the panel holds **at least two tiles**, and its second key — count descending within
one severity — needs **two tiles of the same severity with different counts**. A young project
raises `UNLINKED` on most of its cases and little else, so `TO-2` names what it needs and says
plainly that it may not be reachable in the pass; "not driven, and here is why" is the outcome the
queue accepts, a silent skip is not.

**What changed is a deletion, so there is nothing new on screen.** That is exactly why these rows
exist: §184 removed the SPA's re-sort, and the failure mode is a panel that looks perfectly
plausible in the wrong order. The only observer is a reader who knows what order the server sent.

| # | Drive | Expect |
|---|---|---|
| `§184 TO-1` | **L4**, in a project raising signals of more than one severity (any of `§157 SA-1` / `§178 SL-1` / `§181 OS-1` / `§182 CC-1` will do, and the lane will have several by then): open the dashboard and read the "Needs attention" tiles **left to right** | Red first, then amber, then blue — **worst first**, whatever the counts say. A project whose 156 unlinked cases tile ahead of its two whose spec changed underneath them is the §179 regression at the case grid, one screen over and a whole panel wide. Read the tiles' left borders rather than their words: after §183 the border is the severity. |
| `§184 TO-2` | **L4**, and **may not be reachable — say so rather than passing it**: with **two tiles of the same severity and different counts** on the panel, check the bigger count is the left one | The comparator's second key, which no screen has ever shown. It needs two same-severity signals raised at once — e.g. `OUTDATED_SPEC_CHANGED` and `CONDITION_DRIFT`, both ERROR, from `§169`'s and `§158`'s fixtures. If the lane cannot produce them, record that: `HealthTileOrderTest` asserts this key on the server, so the open question is only whether the panel renders what it is given, which `TO-3` answers from the other end. |
| `§184 TO-3` | **L4**: with two or more tiles showing, open **devtools → Network**, re-request the dashboard and compare the `health` array's order to the tiles' order on screen | **Identical, element for element.** This is the row that actually tests §184: the panel is now transparent, and the assertion is that it re-orders nothing. Until this slice a client-side sort sat between the two, and it was a no-op only by the accident of a stable sort agreeing with the server's first key — so the response and the screen agreed for a reason nobody had written down. |

## §207 — the Delete a contributor could not press, on the two screens that now offer it

**Four rows, none run** (§65). Ids are `§207 AQ-*`; `AQ` is unused elsewhere in this file.

**No new fixture, but this lane needs something no other row has needed: TWO people.** Every check
below is about a case or plan authored by *somebody else*, so the lane must have a second account
that has written content — `provision-company` creates one admin, so the second contributor comes
from an invitation the pass redeems, and whichever of them authored the fixture must be recorded.
If the pass cannot produce two authors, say so rather than driving these as one person: a Delete
that works on your own case proves nothing here, because it worked before §207 too.

**What changed is an enabling, so the failure mode is invisible.** A Delete button that *should*
be enabled and is not looks exactly like a Delete button that is correctly gated — there is no
error, no console entry, and the tooltip explains itself plausibly. That is why `AQ-1` reads the
tooltip and not only the `disabled` attribute.

| # | Drive | Expect |
|---|---|---|
| `§207 AQ-1` | **L2**, signed in as a **Test Engineer**, on a case authored by a *different* account: open the case detail and look at Delete | **Enabled**, with no tooltip explaining a refusal. Before §207 this was disabled with "You can only delete test cases you created". This is the whole slice on one screen. Check the tooltip and not just the pixel — the button is also disabled while `useCan` is pending, which looks the same for a moment. |
| `§207 AQ-2` | **L2**, same caller, press it and confirm the dialog | **204, the case leaves the list**, and the dialog reports no error. The `CaseDeleteDialog` refusal path still exists for a revoked grant, so a 403 here would render *in the dialog* rather than as a toast — read the dialog, not the corner. |
| `§207 AQ-3` | **L4**, signed in as a **Test Engineer**, on a **plan** authored by a different account: open the plan and look at Delete | **Enabled.** This is the row with no server-side precedent — Test Engineer never held `plan:delete` at all, so the button has never once been live for this role and nothing in the SPA changed to make it so. It works only if the grant landed; a disabled button here means `V51`'s `INSERT` did not run on this database. |
| `§207 AQ-4` | **L2**, signed in as a **Viewer**, on any case: look at Delete | **Disabled**, with a tooltip that does **not** name `case:delete`. The boundary §207 leaves in place, and the only refusal this endpoint can still produce. If AQ-1 passes and this one does too, the line moved exactly as far as intended and no further. |

## §208 — the Deprecate a contributor could not press, and the way back from it

**Five rows, none run** (§65). Ids are `§208 DP-*`; `DP` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

Lane: **L2** for the case rows, **L1/L2** for the suite rows. `DP-1`, `DP-2` and `DP-5` need a
signed-in **Test Engineer**; `DP-3` needs a suite an account holding `suite:approve` has already
approved. Nothing here needs two accounts — unlike `§207 AQ-*`, the gate turns on the artifact's
status rather than on who authored it, so one contributor session can drive four of the five.

| # | Drive | Expect |
|---|---|---|
| `§208 DP-1` | **L2**, as a **Test Engineer**, on a **DRAFT** suite: open the folder's ⋯ menu and read `Deprecate…` | **Enabled.** Before §208 it was greyed with a `suite:approve` reason. Read the tooltip, not the pixel: every item in this menu is disabled while the permission query is in flight, which for a moment looks identical — the frontend test for this row uses `waitFor` for exactly that reason. |
| `§208 DP-2` | **L2**, same caller, press it and confirm | **200 and the folder shows as Deprecated.** The row that proves the *server* moved and not only the menu — the item enabling is a client-side table, so a 403 here means `LifecycleLadder` did not ship. |
| `§208 DP-3` | **L2**, as a **Test Engineer**, on an **APPROVED** suite: read both `Return to draft` and `Deprecate…` | **Both still disabled**, both naming `suite:approve`. The half §208 keeps, and the row that says the line moved exactly as far as intended. If `DP-1` passes and this one does too, the change is a gate placement rather than a widening. |
| `§208 DP-4` | **L2**, as a **Test Engineer**, on the suite `DP-2` just retired: open the menu and press `Restore to draft` | **Enabled, and a 200 landing on `DRAFT`.** The one-way door this slice exists not to build: if `DP-2` passes and this fails, a contributor can retire a folder and cannot undo it, which is worse than the state before the slice. |
| `§208 DP-5` | **L2**, as a **Test Engineer**: select two cases in the grid — one `DRAFT`, one `APPROVED` — open the bulk dialog, choose **Set status → Deprecated**, preview and commit | **200 with a split result**: the draft in `changed`, the approved one in `skipped` reading *"Only a test lead can change a case that is already approved."* Two things at once — that `DEPRECATED` is no longer gated for the whole selection, and that the per-row skip still names the state the row is **in** rather than the one it was asked to reach. Before §208 both rows were skipped and the sentence was *"Only a test lead can deprecate cases."* |

## §209 — the two acts the register's own help text had been advising

**Six rows, none run** (§65). Ids are `§209 AR-*`; `AR` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

Lane: **L2** for the project-scope rows, **L4** for the plan-scope ones. `AR-3` and `AR-6` need a
signed-in **Test Engineer**; the rest run as a Test Lead. **No second account is needed** — the gate
turns on the *row's scope*, not on who authored it, so one session per role covers everything.

The fixture is two project-scope artifacts registered by the lane's own charter (any two external
links will do) plus one plan-scope artifact on the lane's plan. `AR-1` creates the third.

| # | Drive | Expect |
|---|---|---|
| `§209 AR-1` | **L2**, as a **Test Lead**: press Register, fill the form, and open the new **Replaces** select | The register's other **ACTIVE** rows, listed as `Category — Title`, plus `Nothing — this is a new artifact` first. Before §209 the field did not exist. The row that proves the picker is fed by a read at the *form's* scope: switch **Belongs to** to `One plan` and the list must change to that plan's own rows, not merely filter the ones already loaded. |
| `§209 AR-2` | Same form: choose one of them and submit | **201, and the register shows both rows.** The replaced one carries a **Superseded** chip; hovering it names the artifact that replaced it *by title*. If the chip appears without a name, `supersededById` did not reach the wire or the replacement is not in the loaded rows — read which, because they are different defects. |
| `§209 AR-3` | **L2**, as a **Test Engineer**, on the same register: open a **project-scope** row's ⋯ menu button | **Disabled**, with a tooltip naming the scope rather than the key. This is the qualifier read against the *row*; if it is enabled, the click will 403 and the SPA is guessing where the server is deciding. Read the tooltip, not the pixel — the button is also disabled while the permission query is in flight. |
| `§209 AR-4` | **L4**, as a **Test Engineer**, on the plan's own artifact: open the ⋯ menu and press **Archive** | **200, and the row shows an Archived chip.** The mirror of `AR-3` and the row that says the line sits exactly where the roles spec puts it: a contributor curates their own cycle. `Restore` in the same menu must be greyed *before* the press and live after it. |
| `§209 AR-5` | On the row `AR-2` marked Superseded: open its ⋯ menu button | **Disabled**, tooltip *"A newer artifact replaced this one…"*. Both acts refused, and refused with the same sentence the server's 409 gives. If the menu opens, press Archive: a 409 must land as a notification rather than silently doing nothing. |
| `§209 AR-6` | **L2**, as a **Test Engineer** registering at **PLAN** scope while the register is filtered to the project: open **Replaces** | The project's documents must **not** be listed. This is the escalation the same-scope rule exists to stop — a plan-scope note retiring the project's test strategy — and the server refuses it with `errors.supersedes` regardless, so a row appearing here is an affordance for a 400 rather than a hole. |
| `§210 AR-7` | **L4**, as a **Test Engineer**, on a **project-scope** artifact: open the ⋯ menu | **Enabled now** — the mirror of `§209 AR-3`, which expected it disabled. `V52` de-qualified `artifact:edit`, so `canOnScope` answers yes at both scopes. **This row exists because `AR-3` is the one queued check §210 inverts**: running the queue without it would drive an expectation the product no longer has. | ⬜ NOT RUN |
| `§210 AR-8` | **L4**, as a **Test Engineer**: delete a suite, close a run, amend a recorded result, and publish a doc page | All four **succeed**. Five controls that were disabled-with-a-reason for this role until `V52`. Drives the whole tier change through the UI in one pass, and the failure it looks for is a control still disabled because a *frontend* gate hard-codes a role assumption the key no longer carries. | ⬜ NOT RUN |

## §218 — the notification channels, and the credential that must survive a rename

**~~Seven rows~~ eight rows, none run** (§65). Ids are `§218 NT-*`; `NT` is unused elsewhere in this
file — grep before minting a prefix (`§154`'s banner).

Lane: **A0** for `NT-1`…`NT-4` (the screen needs a project and a **Test Lead**, and nothing else —
`NT-1` wants a project nobody has configured, so drive it before `NT-2` writes the first channel);
**L1** for `NT-5`, which needs a signed-in **Test Engineer**; **A3** for `NT-6` and `NT-7`, because a
delivery row exists only once a run has actually completed; **A5** for `NT-8`, which needs a board
card created with an assignee.

**`NT-8` was added by phase 4, reading the two queues as a set.** This slice ships **two** event
types and, until it, everything in either queue drove exactly one of them: `run_completed` has an
end-to-end backend test *and* `NT-6`, and `work.item_assigned` had a producer pin and a consumer pin
and nothing that crossed between them. That is the one shape the whole slice's three-test argument
says a pair of pins cannot catch.

**Two things this pass has to supply that no earlier lane creates**, and neither is a fixture the
charter can author on its own:

- **`TCMS_INTEGRATION_SECRET_KEY` must be set on the deployment under test.** It is a required Spring
  property with **no default** (`${VAR}`, no colon — §108's lesson taken literally), so an instance
  without it does not start at all. If the stack refuses to boot on this pass, that is this and the
  fix is `openssl rand -base64 32`, not a rollback. **`.env.lane-b.example` carries a dev value and
  that is the file this pass copies**, so the ordinary path is covered and this note is a diagnostic
  rather than a step. `.env.example` carries one too. The charter section repeats it, because a
  reader preparing the stack meets the charter two thousand lines before this block.
- **A webhook URL the pass may point at.** `NT-6` deliberately uses an **unreachable** one
  (`https://hooks.slack.test/services/DEAD`) rather than a real Slack workspace: a delivery row is
  written as soon as an attempt is *made*, including a first attempt that failed, so a dead endpoint
  produces exactly the row this lane needs to read and needs no third-party account. Reading a
  **successful** delivery end to end is the one thing here that would need a real workspace, and the
  options are (a) a live Slack incoming webhook created for the pass, (b) a local HTTP sink pointed
  at by the URL, or (c) leave it undriven and rely on the backend's own delivery tests. **(b) is the
  recommendation** — it is the only one of the three that also proves the *body* the adapter posts.

| # | Drive | Expect |
|---|---|---|
| `§218 NT-1` | **A0**, as a **Test Lead**, on a project nobody has configured: open **Notifications** in the rail | The rail entry is there, the page renders, and the panel shows the *no-records* empty state — **"No channels yet"**, not a failed region and not a spinner. `[]` is a valid 200 and is the state of most projects; a screen that read it as a fault would fail on every project until somebody configured one. |
| `§218 NT-2` | Same screen: press **Add a channel**, choose **Slack**, name it `#qa-alerts`, paste a webhook URL, tick **Run completed**, save | **201 and the row appears.** Read the **Where it goes** cell: it must show `…` plus the **last four characters** of the URL and nothing else. If the whole URL is rendered, `secretHint` is not what the server sent or the cell is rendering the wrong field — either way a live credential is on screen and in the browser cache, which is the leak §218 §4 exists to prevent. |
| `§218 NT-3` | Press **Edit** on that row, change the name to `#release-alerts`, **leave the webhook field empty**, save. Then reopen the editor | **200, the name changed, and the tail in the hint is the SAME four characters.** ⚠️ **The row that matters, and the one whose failure is invisible.** Absent means *unchanged*; a form that sent `secret: ''` would save successfully, the row would still read as configured, and delivery would stop hours later at a time unconnected to this edit. If the tail changes or the hint reads "No webhook set", the client sent the field. Confirm from the other end too — devtools, the `PUT` payload, no `secret` key at all. |
| `§218 NT-4` | In the same editor, open the **Transport** select; then untick **Run completed** and save | The select is **disabled**, with a helper naming delete-and-create as the way to move a transport — the `PUT` ignores `kind` and the input schema requires it, so the value must still be in the payload. After the save the row's **Told about** cell reads *"Nothing — this channel never fires"*: `subscriptions` is the complete set, and unticking is the only way to unsubscribe. |
| `§218 NT-5` | **L1**, as a **Test Engineer**: find **Notifications** in the rail and open it | The entry is **present** and the page shows a sentence naming who can configure channels — **not** an empty table, **not** a 403 toast, **not** a missing rail item. Then check the network tab: **no request to `/notification-channels` was made at all.** This is the whole read-gate decision; a fetch fired here would 403 and raise a global "Something didn't load" over a screen working exactly as designed. |
| `§218 NT-6` | **A3**, as a **Test Lead**: add a second Slack channel pointed at `https://hooks.slack.test/services/DEAD`, subscribe it to **Run completed**, then complete a run. Wait for the outbox to drain, press **History** on that row | A delivery row with **Failed**, a **Tries** count that climbs across the backoff ladder, and an error in **What came back**. ⚠️ **Read that error character by character**: it must contain **no** `hooks.slack.test` path — `SecretRedaction` strips it on the way in, and this is the leak that is invisible in a green run, sitting in a table an operator browses and a backup copies. A URL here is a finding, not a cosmetic one. |
| `§218 NT-7` | On the channel from `NT-2`, which has never had an event fire: press **History** | The *no-records* empty state reading **"Nothing sent yet"** — and the body must distinguish it from a channel that does not work, because a row appears as soon as an attempt is *made*. Paired with `NT-6` deliberately: the same panel has to say two different things, and a single "no data" message for both is how a working channel and a dead one become indistinguishable. |
| `§218 NT-8` | **A5**, as a **Test Lead**: edit `NT-6`'s dead-endpoint channel and tick **Work assigned to somebody** as well, **and set its failure threshold to 3**. Then open the board and create a task with `engineer` on it. Wait for the outbox to drain, press **History** | A **second** delivery row, `eventType` **Work assigned to somebody**, alongside the `NT-6` run row. ⚠️ **The whole point is the threshold.** `work.item_assigned` carries no failure counts, so a threshold of 3 must be **inert** here — the row appears anyway. If it does not, the predicate is silencing an event that has no notion of the thing it filters on, which is the one failure mode with nothing to see: a channel that shows as subscribed, saves cleanly, and never fires. Read the dialog's help text for the event while you are there — it makes exactly this claim. **This is also the only place either queue drives the second event type at all**: `work.item_assigned` has a producer pin and a consumer pin and no test that crosses between them, which is the gap the slice's own three-test argument predicts. Create a **second** task with **nobody** on it and confirm **no third row appears** — an unassigned task is not an assignment. |

## §217 — signing in through somebody else's issuer, with no screen to do it on

**Seven rows, none run** (§65). Ids are `§217 SO-*`; `SO` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

Lane: **L4**, all seven, at the very end of it and before `F`. Three reasons, and none of them is
convenience. The lane needs a **second running service** (Keycloak), which is what `L4` is for —
"what only a real instance produces". It needs the pass to **sign out and hand the browser to
another origin**, which no earlier lane can afford. And `SO-5` may **create an account by JIT**, so
it has to land after every lane that counts members.

~~**⚠️ Read this before writing a row off as a UI bug: the SPA has no SSO surface of any kind.**~~
→ **superseded 2026-08-15 by §222**, which built the whole of it: the provider buttons on the
sign-in screen, `/sso/callback` as a third public route, and `/admin/sso`. The paragraph below is
kept because it is what these rows were *written against* — four of them (`SO-1`, `SO-3`, `SO-6`,
`SO-7`) now have something to observe and each says so in place, and the `§222 SS-*` block at the
end of this lane drives what §222 added. The rows are **not renumbered**; their ids are quoted from
§217's handoff.

**⚠️ ~~Read this before writing a row off as a UI bug: the SPA has no SSO surface of any kind.~~**
`grep -rl` over `frontend/src` finds `identity-provider` and `/auth/sso` in **`schema.d.ts` alone** —
the generated types and nothing that imports them. There is no sign-in button, no provider list on
the login screen, no `/sso/callback` route, and no admin screen for `POST /identity-providers`.
That is not an oversight this queue should file seven times: §217's plan says in its own self-review
that *"the frontend login-screen work is a `fe-react` lane, not this one"*, and no plan covers that
lane yet. **So these rows drive the flow through the address bar and the console, and what they are
actually measuring is where the missing screen has to attach.** `SO-3` is the one that names the
gap precisely enough to build against.

**Setup this lane needs and no other lane creates:**

- **Keycloak, and a provider row inserted by SQL.** `docker compose up` starts the issuer; the exact
  two `INSERT`s, the reason they cannot go through the product, and the reason they must run as
  `postgres` rather than `tcms_owner` are in [`deploy/keycloak/README.md`](../../deploy/keycloak/README.md).
  **The inability to configure this provider through the product is itself the finding** — the CRUD
  refuses a non-`https://` issuer (`§217 FD-14` pins that gate) and the reference issuer compose
  ships is plain HTTP.
- **An account at `kim@acme.test` that the pass controls**, for `SO-4` to link to. The charter
  namespaces addresses per pass (`…@v109.test`), so either add `acme.test` *and* the pass's own
  domain to `company_email_domain` and use a pass-local Keycloak user, or accept that this lane
  writes one `acme.test` account. Say which was done in the sitting's findings file — §110 F4 cost a
  persona to exactly this ambiguity.

| # | Drive | Expect |
|---|---|---|
| `§217 SO-1` | Sign out. On the sign-in screen, type `kim@acme.test` and look for a provider button; then, with devtools open, watch what the screen requests | **No button, and no request to `/auth/providers` at all.** The row exists to record the shape of the absence rather than to fail: `POST /auth/providers` is built, gated open and answers a list, and nothing calls it. What the sign-in screen must grow is a call on blur of the email field and a button per element of the answer — and it must render **identically** for a domain that maps to nothing, because that endpoint is deliberately not an oracle for which companies exist here (§6r). A screen that showed *"no provider for that domain"* would undo the property the server pays for. **§222 built the caller. The expectation is now: a button labelled from the provider's `displayName`, one `POST /auth/providers` on blur of the email field, and *nothing rendered at all* for a domain that reaches none. A message naming the emptiness is a regression against §6r.** |
| `§217 SO-2` | Paste `http://localhost:8080/api/v1/auth/sso/<providerId>/authorize` into the address bar, with the provider id from the row you inserted | The browser lands on **Keycloak's own login page**, at `keycloak.localtest.me:8081`. Before typing anything, read the URL: `response_type=code`, `code_challenge_method=S256`, a `state`, a `nonce`, and `redirect_uri` **exactly** `http://localhost:8080/api/v1/auth/sso/callback`. A mismatch here is refused by Keycloak with its own error page rather than by TCMS, and that page — *"Invalid parameter: redirect_uri"* — is the tell that `TCMS_API_BASE_URL` does not match what the realm registered. |
| `§217 SO-3` | Complete the Keycloak login as `kim` / `keycloak` and let the redirect run | ⚠️ **The row that matters most, and it is expected to look broken.** The browser ends at `http://localhost:5173/sso/callback#token=…&expiresAt=…` and the SPA **has no such route**, so it renders the not-found page while a valid session sits in the address bar unread. **The sign-in worked; nothing consumed it.** Copy the fragment: this is the exact contract the `fe-react` lane must implement — read `#token` and `#expiresAt`, put them where the session context expects them, `history.replaceState` the fragment away, and navigate. Record the rendered page and the full URL in the findings file; they are the specification for work no plan has written yet. **§222 built the route. The expectation is now: the fragment is gone from the address bar before the first paint completes, and the browser lands on the project list signed in. The fragment appearing in any request line is `§217 FD-13` failing and is a live-credential disclosure.** |
| `§217 SO-4` | Still on that landing URL, open devtools → Network, and look at the request for the SPA document itself. Then check the address bar once more | **The fragment is in the URL and in *no* request.** No `token=` in the document request, none in any subsequent XHR, none in a `Referer`. This is `§217 FD-13` from the only side that can actually observe it: that mutation row is *expected green* because neither stack asserts which separator is used, and a browser is the one place the difference between `#` and `?` is visible. If the token appears in a request line, it is in this instance's access log, in the proxy's, and in the first outbound link the SPA renders. |
| `§217 SO-5` | In `psql`, set the provider's `auto_link_verified_email = FALSE` and `jit_provisioning = TRUE`. Sign out of Keycloak (its admin console → Sessions, or a fresh private window), then repeat `SO-2`/`SO-3` as `sam` / `keycloak` — the **unverified** user | A session is issued and `sam@acme.test` exists as a **new** account: JIT does not consult `email_verified`, only linking does. Then in `psql`: exactly one `user_identity` row, **no `role_assignment` row at all**, and a membership row that is `ACTIVE`. ⚠️ **The absent grant is the assertion.** §217 corrected the plan here — `InvitationService.accept` creates no `RoleAssignment`, so a Viewer grant would make SSO a side door to more capability than an invitation. A row in `role_assignment` for this user is a regression, not a convenience. |
| `§217 SO-6` | Put `auto_link_verified_email` back to `TRUE`, `jit_provisioning` back to `FALSE`. In a fresh private window sign in as `sam` again — now with an account that exists but whose Keycloak email is unverified — after deleting sam's `user_identity` row | Refused: the browser lands on `http://localhost:5173/sso/callback#error=sso_failed` and, again, the SPA's not-found page. ⚠️ **Read the fragment character by character.** One marker, no reason, no provider name, no email — the same string this endpoint gives for a disabled provider, an expired attempt and a deactivated account. Anything finer here is the oracle §6r spends the whole refusal design closing. Whatever the `fe-react` lane renders for `#error=` must be one sentence for all of them. **§222: the landing is now the sign-in screen showing one sentence, "We couldn't sign you in with single sign-on." Any second sentence, and any wording that differs from `SO-7`'s, is the oracle §6r closes.** |
| `§217 SO-7` | Take the *whole* callback URL from a completed `SO-3` — `?code=…&state=…` — and paste it into the address bar a second time | The same generic `#error=sso_failed` landing. The `state` was consumed on the first pass, and the row is the browser half of `§217 FD-10`, which is expected green because nothing drives one callback twice. This is the cheapest observation of the single-use guarantee that exists, and it takes one paste — but it proves the *replay* leg only. Two **concurrent** callbacks racing one `state` are what the conditional `UPDATE` is really for, and a browser cannot drive that; it stays an integration-test gap. **§222: the landing is now the sign-in screen showing one sentence, "We couldn't sign you in with single sign-on." Any second sentence, and any wording that differs from `SO-6`'s, is the oracle §6r closes.** |

## §222 — the sign-in screen, the callback route and the provider admin

**Five rows, none run** (§65). Ids are `§222 SS-*`; `SS` is unused elsewhere in this file. They
belong to the **same lane as `§217 SO-*`** and need the same state — Keycloak up, and a provider row
that exists — so run them in one sitting, `SS-1` first, because it is the row that can create the
provider through the product instead of by `INSERT`.

**One setup caveat inherited from §217 and not fixed here.** The compose Keycloak serves plain HTTP
and `IdentityProviderService.validate` refuses a non-`https://` issuer — a gate §219's lane decided
to **keep** (`docs/plans/2026-08-15-m4-external-identity-oidc.md`, self-review). So `SS-1` needs a
**real https issuer** to be driven as written; against compose it can only be driven as far as the
form's own refusal, which is itself worth observing. Say in the sitting's findings file which of the
two was done — an unstated substitution here is the §110 F4 ambiguity again.

| # | Drive | Expect |
|---|---|---|
| `§222 SS-1` | As a **Company Admin**, open **Single sign-on** in the rail and press **Register a provider**. Fill it in against a real https issuer and save. Then read the row, the response in devtools, and every pixel of the screen | **201, and the row lands `Disabled`.** ⚠️ Registration cannot enable — `IdentityProvider.register` always produces a disabled provider, and a row that reads `Live` here means the server's rule moved. Then the credential half: the `POST` response body must carry **`clientSecretSet: true` and no secret**, the row must show no secret and offer no Reveal or Copy, and reopening the form must show the secret field **empty** with *"Leave blank to keep the stored secret."* beside it. A secret anywhere on this screen is a disclosure, not a cosmetic finding. |
| `§222 SS-2` | On the same screen, add `acme.test` under **Claimed email domains**. Sign out. On the sign-in screen type `kim@acme.test` and tab out; then type `kim@nowhere.test` and tab out. Watch devtools for both | The claimed domain shows a **Continue with …** button labelled from `displayName`. ⚠️ **The unclaimed one must show nothing at all** — no message, no empty state, no spinner resolving into a shrug — while devtools shows the request answered **`200 {"providers":[]}`**. That difference is the whole property: the server pays for an empty-not-refused answer on every call, and any rendered acknowledgement of the emptiness hands it straight back (§6r). Type `kim` with no `@` as well: **no request at all**. |
| `§222 SS-3` | Sign in as a **Test Lead** and open `/admin/sso` from the rail | The rail entry is **present**, the page renders, and the panel is replaced by a sentence naming who can configure providers — **not** a 404, **not** an empty table, **not** a global error toast. Then the network tab: **no request to `/identity-providers` or `/identity-providers/email-domains` was made at all.** This is `ApiTokensPage`'s read gate in a second place; a fetch fired here would 403 and raise "Something didn't load" over a screen working as designed. |
| `§222 SS-4` | Enable the provider from the edit form, sign out, and sign in through the button. Watch the address bar and the whole network log across the redirect | The app lands on the project list **signed in, with your name in the shell**. ⚠️ Then the two observations only a browser can make: the address bar shows `/sso/callback` with **no fragment** — it is wiped before the first paint completes — and `token=` appears in **no request line**, no `Referer` and no XHR. This is `§217 SO-4` re-driven against a route that now consumes the fragment rather than one that ignores it. |
| `§222 SS-5` | From the signed-in app, press the browser's **Back** button | You stay signed in and the callback route does **not** re-run. Both `Navigate`s out of that route are `replace`, so the callback is not in the history stack at all — and if it were, its `state` has already been consumed and the second pass would land on an identical failure, which reads as a loop. A sign-out here, or a "We couldn't sign you in" appearing after a successful sign-in, is that defect. |

## §224 — the deep link that survives the issuer, and the buttons that arrive without a blur

**Three rows, none run** (§65). Ids are `§224 SF-*`; `SF` is unused elsewhere in this file. Same
lane and same state as `§222 SS-*` — Keycloak up, a **Live** provider, a claimed domain — so run
them in the same sitting, after `SS-4` has proved the plain sign-in leg.

Note that `SS-4`'s expectation — *"lands on the project list"* — is now the **degenerate** case:
it holds only when the sign-in started from `/login` directly. `SF-1` is the general one.

| # | Drive | Expect |
|---|---|---|
| `§224 SF-1` | While signed out, paste a deep URL — a specific run or case under `/projects/…` — into the address bar. Get bounced to the sign-in screen, type the claimed-domain address, and go through the **provider button**. Watch `sessionStorage` before the redirect leaves | The app lands **back on the pasted URL**, signed in — not on the project list. Before the redirect, `sessionStorage` holds the path under `tcms.sso.return-path`; after the landing it is **gone** (consumed, so a later sign-in cannot replay it). ⚠️ The stash carries a **path**: put `https://evil.test/x` into that key by hand, complete a sign-in, and the app must land on `/` — a stored absolute URL followed here is an open redirect assembled out of a convenience. |
| `§224 SF-2` | Accept an invitation, arrive on the sign-in screen with the address **prefilled**, and touch nothing | The provider buttons appear **without any blur** — discovery fires once on mount for the prefilled address (one `POST /auth/providers` in devtools, not two). Until §224 the just-accepted invitee was the one person never shown their company's buttons, because a prefilled field never blurs. |
| `§224 SF-3` | Let a password manager autofill both fields and submit a wrong password. Then, separately: type a claimed-domain address, tab out so the buttons appear, and edit one character of the address | After the 401: the one-sentence failure **and** the provider buttons, arriving from a discovery fired by the rejected attempt — an SSO-only company's people otherwise dead-end here with a wrong sentence and no way in. After the edit: the buttons **disappear** until the next blur asks again — a button still offering the previous address's providers starts a sign-in nobody asked for. |

## §223 — the directory sign-in, and the button that switches instead of navigating

**Four rows, none run** (§65). Ids are `§223 LD-*`; `LD` is unused elsewhere in this file.

**This row set needs a fixture no lane creates today: a company with an enabled LDAP provider and
a reachable directory.** The compose stack has Keycloak and no LDAP server. The options are an
`osixia/openldap` service in `compose.yaml` beside Keycloak, or driving these four rows against a
customer directory in the lab — it is this pass's charter to choose, and "not driven, and here is
why" is a legitimate outcome where a silent skip is not. The suite already drives the whole flow
against an in-memory LDAPS server (`LdapLoginEndpointTest`), so what these rows add is the browser's
own half: the mode switch, the copy, and the absence of any navigation.

| # | Drive | Expect |
|---|---|---|
| `§223 LD-1` | Type an address at a claimed domain whose company has an enabled LDAP provider, tab out, and click the provider button | A button labelled from `displayName`; clicking it **switches the form** — the heading gains *"Signing in with your … password."*, the password label becomes **Directory password** — and the address bar does not move. A navigation here is the OIDC control on the wrong kind. |
| `§223 LD-2` | Submit a wrong password in directory mode | **One sentence, identical to a wrong TCMS password** — "That email and password don't match an account." Anything naming the directory, the provider or a bind is an oracle for which accounts are federated. |
| `§223 LD-3` | Click **Use my TCMS password instead**, then sign in locally | Local sign-in works from the same screen with no reload — the mode note and the Directory-password label are gone, and the same two inputs carry the local attempt. |
| `§223 LD-4` | A company with both an OIDC and an LDAP provider enabled: type the address, tab out | **Two buttons; one navigates, one switches.** The OIDC one leaves for the issuer as `§222 SS-4` proved; the LDAP one stays and switches the form. This is `SsoProviderChoice.kind` doing its one job on screen. |

## §227 phase 1 — the reports that stopped disappearing, and the report a reader may open

**Seven rows, none run** (§65). Ids are `§227 RP-*`; `RP` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

Lane: **A6 — reports**, which is where the boards and the reporting screens sit. The lane already
requires runs and results to exist, and `RP-1`–`RP-3` additionally require a report to have been
generated **and the tab reloaded**, which is the whole point of the slice.

**`RP-4` needs the screen-only persona and there is no seeded role for it.** `report:view` without
`report:export` is expressible and no built-in role holds that combination — the backend suite
invents a custom role (`ReportEndpointTest`'s `Report Reader (§87 test fixture)`) and the product
has no way to create one, because there are no custom roles. **So `RP-4` is undriveable through
the UI as the product stands**, and it is written here rather than dropped: the options are (a)
insert a custom `role` + `role_permission` row by hand against the lane's database and grant it,
which is the same shape §110 used for other unrepresentable states, or (b) accept that the split is
proved by `ReportEndpointTest.Model` alone and mark the row so. Choosing (b) silently is the thing
this paragraph exists to prevent.

| # | Drive | Expect |
|---|---|---|
| `§227 RP-1` | **A6**, generate a report, wait for it to complete, then **reload the page** | The report is **still listed**, under "Generated reports", with its View and Download controls. Before §227 the reload emptied the panel permanently and the artifact became unreachable. This one row is the slice. |
| `§227 RP-2` | **A6**, open the reports screen in a **second browser profile** signed in as a different member of the same project | The **same** rows. The list is the project's and not the caller's — a "mine" implementation answers an empty panel here and passes `RP-1`. |
| `§227 RP-3` | **A6**, on a project where nothing has been generated | The empty region *"No reports yet"*, not a hidden panel and not a spinner. |
| `§227 RP-4` | **A6**, as a caller holding `report:view` and **not** `report:export` (see the paragraph above): open a completed report's View | **The report renders.** Before §227 this was a 403 and the page said so. If this cannot be driven, say which of (a)/(b) was chosen. |
| `§227 RP-5` | **A6**, same caller if available: press Download on the same report | **Refused.** The pair — without it the model route could have been gated on `report:export` and `RP-4` would still pass for a caller holding both. |
| `§227 RP-6` | **A6**, generate a report and type a name into **"Save these settings as"** | The report queues **and** the row appears under "Saved reports". Then press the row's **Generate** (↻): a dialog offering the format alone, and a new job on the list using the saved selection. The saved definition supplies the parameters — if the new report covers a *different* run from the one that was saved, the regenerate is posting the form's own defaults over the saved ones, which is the trap this dialog's header names. |
| `§227 RP-7` | **A6**, press the row's Delete and confirm | The row goes; **the reports it produced are still in the list above and still download.** `ON DELETE SET NULL` on both columns is what makes that true, and a reader who assumes otherwise is who the dialog's second sentence is for. |

**Not driveable from a browser at all, and named so it is not mistaken for an omission:** the
reaper. Producing a job stranded in `RUNNING` means killing the process between the claim and the
completion, which no UI can do; `ReportJobPipelineTest.Reaping` writes the state in SQL and is the
only observer. What a pass *could* do — and is worth one line if the lane has database access — is
set a live job's `started_at` back an hour and watch the card turn Failed within thirty seconds.

## §229 phase 2 — the correction the register could not make (A6)

**Eight rows, none run** (§65). Ids are `§229 AR-*`; `AR` is unused elsewhere in this file — grepped
before minting (`§154`'s banner).

Lane: **A5 — artifacts** for every row. `AR-1`–`AR-6` need a **Test Lead**; `AR-7` needs a caller
holding `artifact:edit` and **not** `artifact:delete`, which no seeded role is — so it needs a
hand-built grant, and *"not driveable with the seeded six"* is a legitimate outcome to record.

| # | Drive | Expect |
|---|---|---|
| `§229 AR-1` | **A5**, open the row menu on any artifact | Four items: **Correct…**, Archive, Restore, **Remove…**. The first and last are new — before this slice the register could register and archive, and nothing else. |
| `§229 AR-2` | Press **Correct…**, change the title, Save | The row's title changes in place. The dialog carries an info banner saying scope and target cannot be changed here and pointing at superseding, which is the sentence somebody who opened it to fix a wrong URL needs to read. |
| `§229 AR-3` | Correct… on a row that has a description, empty the description box, Save | The caption line under the title **goes**. Sending `''` instead of `null` would store a present-and-blank description, which renders as an empty line — visible only by looking for it. |
| `§229 AR-4` | Correct… twice with no change the second time | Both save. Nothing in the UI distinguishes them, and that is correct — the second writes no audit row, which is only visible in `audit_event`. Worth one SQL check if the lane has database access. |
| `§229 AR-5` | Open the row menu on a **superseded** row | The kebab **opens** (it used to be disabled entirely), **Correct… is live**, Archive and Restore are greyed. The *why* is on the status chip beside the title, which is where a reader looks — a tooltip on a disabled `MenuItem` never fires. |
| `§229 AR-6` | Press **Remove…** on a **FILE** artifact | A confirmation naming that the uploaded file goes with it, and an info line pointing at **Archive** for the case somebody actually meant. Cancel, and nothing happens; confirm, and the row goes. Archiving deliberately has no such dialog — it destroys nothing. |
| `§229 AR-7` | As a caller holding `artifact:edit` and not `artifact:delete` | **Remove… greyed, Correct… live.** The two keys are separate on purpose — a company may grant one without the other — and `artifact:delete` is *unqualified*, so the menu asks it with `can` rather than `canOnScope`. Not driveable with the seeded six; say so rather than skipping. |
| `§229 AR-8` | Remove… a row that another artifact supersedes | A **red alert inside the confirmation dialog**, not a corner toast: deleting it would leave the newer row pointing at nothing. The client cannot predict either 409 (`write_once` is not on the wire at all), which is why they are rendered in place. |

**Not driveable from a browser and named so it is not read as an omission:** the `write_once` 409.
Nothing in the product sets that column — the backend test sets it in SQL — so a browser pass can
only reach it with database access. If the lane has it, `UPDATE artifact SET write_once = true` on
one row and then Correct… is one row's worth of evidence for a guard that otherwise has no live
observer at all.

## §229 phase 1 — the assignee that could not be given, and the environment nobody chose (A4)

**Thirteen rows, none run** (§65). Ids are `§229 AS-*`; `AS` is unused elsewhere in this file —
grepped before minting (`§154`'s banner).

Lanes: **A3 — runs** for every row. `AS-1`–`AS-4` need a signed-in **Test Engineer**;
`AS-5`/`AS-6` need a **Viewer** and a **CI token holder** respectively; `AS-11` needs a **Test
Lead** to close the run.

**A fixture this lane does not already have:** a second person with a grant on the project. The
assignee selects are built from `GET /members`, which answers *active company members holding a
grant on this project* — so on a one-person company both selects render "Nobody" and nothing else,
and `AS-1`, `AS-7` and `AS-8` cannot be driven at all. Invite a second account and grant it Viewer
on the project **before** starting this lane; a Viewer is enough, because the list is about who may
be *given* work rather than who may do it.

**What is easy to get wrong when driving these:** the assignee select is disabled while `useCan` is
still resolving, exactly like `§227 LC-*`'s menu. Read it after the page settles.

| # | Drive | Expect |
|---|---|---|
| `§229 AS-1` | **A3**, open an execution from a run, find the **Assignment** panel below Record the result, pick a person | The select shows them immediately and the panel does not flicker back. Then press **Back to the run**: that case's row now reads **assigned to <name>** instead of *unassigned*. The row that proves the whole item — before this slice nothing in the product could write the column. |
| `§229 AS-2` | Same execution, set the select back to **Nobody** | The row on the run reads *unassigned* again. Sending `''` rather than `null` is a 400 on a uuid's format, so a refusal here is that defect and not a permission. |
| `§229 AS-3` | Assign, then **record a verdict** on the same execution without reloading | The verdict saves — **no 409**. The assignment bumped `lockVersion` and the response was written into the cache; if this 409s, `useAssignExecution`'s `setQueryData` is not landing and every assignment costs the tester a reload. |
| `§229 AS-4` | Assign a case that already has a recorded result | The result, its author and its timestamp are **unchanged** on screen. A mutator that stamped `executedBy` would put the assigner's name on somebody else's verdict. |
| `§229 AS-5` | **A3** as a **Viewer**: open the same execution | The select is **present and disabled**, with the gate's tooltip naming who to ask (§31). Not hidden — a control that vanishes teaches nothing. |
| `§229 AS-6` | **A3** with a **CI token** (`Automation Service`): `PUT …/executions/{id}/assignee` over `curl` | **403.** Not driveable from the browser — the credential has no session — and it is the single most important observation in this lane, because it is the only one that distinguishes `run:execute` from the two keys that would have compiled. |
| `§229 AS-7` | **A3**, start a new run: the **New test run** dialog now has **Configuration** and **Assign to** | Configuration opens on *The project's default* and lists the project's environments; Assign to opens on *Nobody* and lists the members. Both are new — the form sent a hard-coded `null` for each until this slice. |
| `§229 AS-8` | Submit that dialog with a person chosen in **Assign to** | Every row of the new run reads **assigned to <name>** — the whole checklist, which is what the helper text says and the one thing about this control that surprises. |
| `§229 AS-9` | Submit the dialog touching **neither** new control | The run is created and its rows read *unassigned*; the run's configuration is the project's **Default**. The barrier: an untouched select must send `null`, not `''`. |
| `§229 AS-10` | **A3**, clone a run: the **Clone** dialog's Configuration select | First option reads **Keep <the source's configuration>**, and the source's own row is **not** offered a second time below it. Assign to opens on *Nobody* — the two defaults point in opposite directions on purpose. |
| `§229 AS-11` | **A3**, as a **Test Lead** close a run, then open one of its executions | The Assignment select is disabled and reads *"This run has ended, so its checklist can no longer be reorganised."* Reopen the run: it goes live again. |
| `§229 AS-12` | With the run closed in another tab, change the assignee in a tab opened before the close | A **red alert under the select** saying the checklist can no longer be reorganised — in place, not a corner toast. This is the one 409 a real user meets, and it is why the mutation is marked handled. |
| `§229 AS-13` | On a project whose configuration list has only the seeded `Default` | The Configuration select still opens and offers exactly one row besides *The project's default*. Worth one row because it is every freshly provisioned project, and a select with one option is where a "never empty" claim is cheapest to disprove. |

**Not driveable from a browser and named so it is not read as an omission:** the assignee who is a
company member **without** a project grant. `GET /members` will not offer them, so the select cannot
produce that request — the server accepts it (deliberately: it is the same predicate `POST /runs`
applies to the same column), and `ExecutionEndpointTest.Assignment` is the only observer. Driving it
needs `curl`.

## §228 — the status a case could not be given, and the folder nobody could see reviewed

**The row ids below say `§227` and stay that way.** They were minted before the section was numbered
§228, and an id in this file is quoted from handoff sections and never renumbered (`§109`'s rule).

**Six rows, none run** (§65). Ids are `§227 LC-*`; `LC` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

Lanes: **L2** for the case rows, **L1/L2** for the suite rows. `LC-1`–`LC-4` need a signed-in
**Test Engineer** and, for `LC-3`, a **Test Lead** as well. `LC-5` and `LC-6` need
`reviewEnabledSuite` switched **on** in project settings — it is off by default, which is the state
`LC-6` checks.

**What is easy to get wrong when driving these: a disabled menu item and a menu item that is still
resolving its permission look identical.** Every item in this menu is disabled while `useCan` is in
flight. Read the item after the page has settled, and prefer checking the *live* item beside it —
if Return to draft is live and Approve is grey, the answer has landed.

| # | Drive | Expect |
|---|---|---|
| `§227 LC-1` | **L2**, as a **Test Engineer**, on a **DRAFT** case: open the case, press **Status** | Two items — `Submit for review` and `Deprecate` — and **no Approve at all**. Not a greyed Approve: §6g reaches APPROVED only from IN_REVIEW, so a disabled one would blame a permission for what is actually a missing review. |
| `§227 LC-2` | Same caller, press **Submit for review** | **200, the chip in the header moves to In review**, and the Review panel below appears/updates. The row that proves the *server* endpoint shipped — the menu is a client-side table, so a 403 or 404 here means `CaseStatusService` did not. |
| `§227 LC-3` | **L2**, as a **Test Engineer**, on the case `LC-2` just submitted: open Status | `Approve` **greyed**, `Return to draft` and `Deprecate` live. Then sign in as a **Test Lead** and press Approve: **200**. The pair is the whole gate — the engineer half alone passes against a page with no gate in it. |
| `§227 LC-4` | **L2**, as a **Test Engineer**, on the now-**APPROVED** case: open Status | **Both** items greyed (`Return to draft`, `Deprecate`), because leaving an approval is an approval act whatever the target is called. If `LC-3` passes and this one does too, the line is exactly where the server puts it. |
| `§227 LC-5` | **L1/L2**, with `reviewEnabledSuite` **on**: select a suite in the rail | A panel above the grid naming the suite, its lifecycle chip, its membership count and an Execution history link — **and the Review thread below it**. Before §227 a suite's review record was written, gated by three permissions and rendered nowhere. |
| `§227 LC-6` | Same, with `reviewEnabledSuite` **off** (the default) | The header panel **stays**; the Review thread **does not appear**, and there is no empty "Review" section. The folder still has a lifecycle; the project has simply not asked anybody to sign off on folders. |

**Not driveable and named so it is not read as an omission:** the 409 from §6g's review policy —
author-cannot-approve-own-work and the minimum peer LGTM. It needs a project with review enabled,
an open review, and two accounts, which is `§207 AQ-*`'s two-author requirement plus a settings
change. The menu deliberately does not predict it (a third implementation of `ReviewService.refuses`
would be wrong exactly where it mattered), so the refusal arrives as a notification. If the pass has
two accounts available, driving it is worth one row.

## §230 — the trip from a case to the requirement it claims (A8)

**Six rows, none run** (§65). Ids are `§230 LK-*`; `LK` is unused elsewhere in this file — grepped
before minting (`§154`'s banner).

Lane: **A1 — the repository**, after `DOC-1`'s own links are authored (the fixture note at the top
of this file builds that page). Every row needs a signed-in **Test Engineer**, who holds `case:edit`
and `doc:edit` both; `LK-5` needs a **Viewer** instead, and is the only row that needs a second
account.

**Nothing about this slice changed the server.** No endpoint, no payload, no permission — the whole
of A8 is a URL carrying a case id between two screens that already existed, which is why there are
no matching rows in `backend-mutation-checks.md`. What can go wrong is therefore entirely
navigational, and that is what these rows drive: the intent surviving a redirect, a tree click and
a reload.

| # | Drive | Expect |
|---|---|---|
| `§230 LK-1` | **A1**, open any case → the **Traceability** panel | **Two** link buttons: *Link a specification* and *Link code or a URL*. Before this slice the panel named a target kind (a documentation block) that it offered no way to produce, including in its own empty state. |
| `§230 LK-2` | Press **Link a specification** | The documentation space, with `?linkCase=<the case id>` in the address bar **and a page selected** — the space redirects to its first page when none is named, and a redirect that rebuilt only the path would drop the intent before anything could read it. A banner names the case (key **and** title) and offers *Stop linking*; the case's key in it is a link back. |
| `§230 LK-3` | Click through to **another page** in the tree, then **reload** the browser | The banner is still there, still naming the same case, on both. The intent is in the URL for exactly this reason — the page somebody lands on is rarely the page their case verifies. |
| `§230 LK-4` | On a paragraph of that page, press the block's **Link `TC-…`** button | The dialog opens with the **case already chosen** — its key and title in the picker, Test case selected, Verifies as the relationship — and **Link is enabled without touching anything**. Press it: the block's chips gain the case and its coverage margin recounts. Then open that case: the link is on its Traceability panel (allow the mirror a moment — `§167 DL-1`'s timing arm). |
| `§230 LK-5` | As a **Viewer**, open a case → Traceability | *Link a specification* is **disabled**, not hidden (§31), and so is *Link code or a URL*. The two ask different keys — `doc:edit` and `case:edit` — and a Viewer holds neither, so this row proves only that it is gated. **The pair that proves it is gated on the right one is `DocLinksPanel.test.tsx`'s**, which needs a caller with `case:edit` and no `doc:edit`; no seeded role is that, so it is not driveable here. Named rather than skipped. |
| `§230 LK-6` | Back on the documentation space carrying a case, press **Stop linking** | The banner goes, the address bar loses `linkCase`, and each block's button returns to *Link* / *Link a case*. Open one: the picker is **empty** and Link is disabled. The intent has to leave the dialog too, not just the banner that announced it. |

## §231 — social login and account linking

**Seven rows, none run** (§65). Ids are `§231 GH-*`; `GH` is unused elsewhere in this file —
grep before minting a prefix (§154's banner). Most rows need the stack started with at least one
`TCMS_SOCIAL_*` pair set (see `.env.example`'s social block); `GH-1`'s second half and `GH-2`'s
fallback are the exceptions.

| # | Drive | Expect |
|---|---|---|
| `§231 GH-1` | **L1**, stack up with all three providers configured: open `/login`. Then restart with none configured and open it again | Three "Continue with GitHub/GitLab/Google" buttons below the company-SSO section; with none, **no social section at all** — no divider, no heading — and a fresh acceptance (GH-1b's flow) lands on the app's default page, not the security page. |
| `§231 GH-1b` | **L1**, providers configured: provision a company, redeem the invitation link, choose a password, submit | Signed in **immediately** — no manual login — landing on `/account/security?welcome=1` with the intro and a working one-click Skip. Then: an existing user accepting a second-company invitation gets neither the auto-login nor the welcome page (no password was typed). |
| `§231 GH-1c` | **L1**: arm `tcmsFaults.expire()` (or equivalent one-shot 401 on `/auth/login`) after the accept form loads, then submit | Acceptance still succeeds; the page falls back to the sign-in form with the address prefilled and, after manual sign-in, lands on `/account/security?welcome=1` — the destination survived the fallback. No copy suggesting the acceptance failed. |
| `§231 GH-2` | **L2**, a real GitHub OAuth app pointing at the stack's callback: link from `/account/security`, sign out, sign back in with the GitHub button | The full round-trip: link lands back on the security page with the inline "GitHub is now linked" acknowledgement; the later sign-in lands on the remembered return path. **Needs an operator-created OAuth app; not creatable by any lane's fixtures** — if absent, record "not driven: no OAuth app" and drive `GH-5` instead. |
| `§231 GH-3` | **L2**, same stack: link a second provider from `/account/security`; then unlink it | The second row round-trips and flips to Unlink (dated, with the provenance email); unlinking confirms first, then flips back to Link. |
| `§231 GH-4` | **L2**: a passwordless (company-IdP JIT) account with exactly one linked identity presses Unlink and confirms | Refused — the 409's explanatory message ("only way to sign in… set a password or link another provider first") stays **on the dialog**, not a corner toast. |
| `§231 GH-5` | **L1**: paste `/api/v1/auth/social/callback?state=garbage&code=x` into the address bar | Lands on the sign-in page with the single SSO failure sentence — the same one a company-SSO failure renders, no cause named. |

## §232 — the asymmetries (audit section B)

**Ten rows, none run** (§65). The automated gates behind them are green (frontend **1602 / 120**). Ids are `§232 SC-*`; `SC` is unused elsewhere in this file — grepped
before minting (`§154`'s banner).

Lanes: **A1** for the case and suite rows, **A2** for the plan ones, **A3** for the run rows,
**A5** for the artifact upload, **A6** for the report format. `SC-1`…`SC-4` need a signed-in
**Test Engineer**; the rest need a **Test Lead**.

**What is hardest to drive here is B1, and it is worth reading before starting.** Its whole subject
is a field the client *does not send*, and the SPA sends everything — so no click in this product
exercises the change. `SC-1` therefore drives the API directly (curl or the CLI), and it is the one
row in this file that does. Driving it through the editor proves nothing either way.

| # | Drive | Expect |
|---|---|---|
| `§232 SC-1` | **A1**, with a token: `GET` a case, then `PATCH` it with `{"title":"…","stepFormat":"STRUCTURED","priority":"HIGH","automationStatus":"MANUAL"}` and nothing else | **200**, and re-reading the case shows its objective, description, expected outcome, estimate and **steps unchanged**. Before B1 that body emptied four columns and deleted every step, answering 200 either way — which is why this row is an API drive and not a click. Then send `"objective": null` in the same shape: the objective clears and the description does not. |
| `§232 SC-2` | **A1**, `PATCH` a case created from a title alone (a stub: no steps) with its own body, unchanged | **200.** Before B2 this was a 400 about steps nobody had written. Then send `"steps": []` to a case that *has* steps: still **400** on `steps` — the only body that was ever really a mistake. |
| `§232 SC-3` | **A1**, `PATCH` a case echoing the `ownerId` the `GET` served | **200.** Then send a *different* `ownerId`: **400** naming the field. Both fields behave this way (`automationTags` too, in any order) — echoing what was read is not a write, and changing it is still refused because no path writes those columns. |
| `§232 SC-4` | **A1**, the case grid: type a tag into **Tag**, press Enter, add a second | The helper text under the field reads **"All of these — a case must carry every one."** and the list gets shorter, not longer. Compare with the suite's Tag rules and the run's Tags, which say "any" and mean it — the three surfaces now state their own semantics. |
| `§232 SC-5` | **A1**, select a suite whose conditions some case has declined, open **Rename/edit**, remove a condition chip | A **warning above Save** naming how many cases lose an opt-out, before the save. Cancel and nothing happens. The number comes from `declineCount`, which reached the wire with this slice — if it reads 0 where you know a case has declined, the count is being read from the wrong attachment id. |
| `§232 SC-6` | **A1**, delete a suite that has added and tag-claimed members but nothing filed in it | The confirmation names what the suite resolves to and says the cases are not deleted, then the delete succeeds. Then try one with a **filed** case: refused, with the server's own sentence in place. The dialog deliberately does not print a "filed" count — the arms overlap, so it cannot be computed from what the read serves. |
| `§232 SC-7` | **A2**, open a plan whose Test cases tab names an individual case, with more than 200 cases in the project | Every case chip shows its key and title, including one whose key sorts past the first 200. Before B8 that chip read "A case outside the loaded list". A chip that now reads "A case this project no longer lists" means the case was deleted, which is a different and true statement. |
| `§232 SC-8` | **A2**, plan → Fields tab → **Exit criteria** → open the metric picker | Five metrics, three of them marked **"— not evaluated yet"**. All five stay selectable: a gate somebody has agreed to is worth recording. Save one of the marked ones and read the Progress tab: it shows as unevaluated rather than as passing. |
| `§232 SC-9` | **A3**, open a run created from a suite or a tag | Under the pass-rate summary, a line naming **what it was created from** — arm counts, not case counts. A run created before this slice, or by an ingested report, shows **no such line at all** (not "no scope"). Then open **Clone**: a checkbox offering to *re-resolve the original scope*, unchecked. |
| `§232 SC-10` | **A3**, tag one more case into that run's scope, then clone it twice — once plain, once with the checkbox | The plain copy has the **same** number of cases as the source; the re-scoped one has one more. Clone a run with no recorded scope with the checkbox forced (API): **400** on `rescope`, never a silent fall back to the ordinary clone. |

**Not driveable from a browser and named so it is not read as an omission:** B4's whole subject.
An `Idempotency-Key` replay needs the same key sent twice, which the SPA does by holding one key per
mounted dialog — so reproducing it means killing the response mid-flight, not clicking twice. The
backend queue's `§232 PB-*` rows are where that lives.

## §234 — the artifact register: pinned reports and sign-off records (C6 + C7)

Lane **A5** (the register's own lane, where the upload rows already live) for `AR-2`…`AR-4`;
**A6** (the reports lane) for `AR-1`, which starts on a generated report. All four need a
**Test Lead** — `artifact:edit` at PROJECT scope plus `report:view`.

**The fixture `AR-1` needs is one this queue's charter does not author: a COMPLETED report job.**
The A6 lane generates one already, so the row is written to start from that lane's own output
rather than asking for new seed data — run it in the same pass, not before.

| # | Drive | Expect |
|---|---|---|
| `§234 AR-1` | **A6**: on a finished report, press **Save to register**, keep the offered title, choose a category, Register | The dialog opens titled *"Save this report to the register"*, offers **no** kind picker, and starts with the report's own title. After Register: a confirmation naming the title with a link to the register, and the row is there with kind `GENERATED_REPORT`. Following its link downloads the same bytes the report's own Download gives. |
| `§234 AR-2` | **A5**: register an external link with **This is a sign-off record** ticked, then open its row menu | Correct… and Remove… are **greyed**; Archive is **not**; a line in the menu says it is a sign-off record and to register a replacement. This is the §31 half of C7 — before it both were enabled and answered 409 after the correction was typed. |
| `§234 AR-3` | **A5**: on that sign-off row, register a replacement naming it under **Replaces** | Two rows: the original `SUPERSEDED` and pointing at the replacement, the new one `ACTIVE`. **A write-once row can still be superseded** — that is the correction path the flag forces people onto, and refusing it would make the record uncorrectable. |
| `§234 AR-4` | **A5**: pin a report at **PLAN** scope, then open a *different* plan's register | The pinned row appears in its own plan's register and **not** in the other plan's, and is absent from the project register. Plan-scope artifacts are not inherited sideways or upward. |

## §235 — the search a documentation space could not do (C3)

**Six rows, none run** (§65). Ids are `§235 DS-*`; `DS` is unused elsewhere in this file — grepped
before minting (`§154`'s banner).

Lane: **A1 — the repository**, after `DOC-1` and its blocks are authored (the fixture charter at the
top of this file builds that page, and `§230 LK-*` already depends on it). `DS-5` needs a **second**
page, so author one there: title *Chargebacks*, one paragraph saying *"A chargeback is not a
refund — the customer never asks us."*. Every row is driveable as any account that can see the
project: **the gate is the project read gate, not `doc:read`**, which is `DocPageService`'s own
argument and is why there is no persona row here. `DS-6` is the only row needing a second account,
and only to prove the 404.

**Read before driving `DS-2`:** matching is by lexeme. `refund` finds "refunds" and `refundable`
finds them both, but `refun` finds **nothing** — that is correct behaviour and the copy in the empty
state says so. A tester who reads it as a bug will file the product's one deliberate limitation.

| # | Drive | Expect |
|---|---|---|
| `§235 DS-1` | **A1** → Documentation. Type `refund` in the rail's search box and press **Enter** | The rail's heading changes **Pages → Results** and lists the pages that mention it, each with its key and a **fragment of the page's text containing the word** — not the first line of the page. The address bar gains `?q=refund`. The page open in the main pane does not change. |
| `§235 DS-2` | Search `refun`, then `refunds`, then `refundable` | `refun` → *No pages match*, with copy saying whole words only. The other two → the same hits as `refund`. This is the whole difference between this box and the case list's, and the one thing about it a user must be told rather than discover. |
| `§235 DS-3` | With results showing, **reload** the browser, then press **Back** | The reload comes back to the same results with the box still filled — `q` is in the URL, not in component state. Back returns to the previous search (or to the tree), and the box follows it rather than keeping the query that is no longer being run. |
| `§235 DS-4` | Click a result | Its page opens in the main pane **and the results stay in the rail**, with the clicked row marked current. The search survives being used, which is the point of carrying it on the link. |
| `§235 DS-5` | Search a word that is in one page's **title** and another page's **body** (`refund`, with *Chargebacks* authored as above) | The title match is **first**. `V2` weights the title `A` and the body `B` and `ts_rank_cd` reads those weights; a list in creation or alphabetical order means the ranking is not reaching the screen. The *Chargebacks* row's fragment shows the sentence containing the word. |
| `§235 DS-6` | Press the **✕** in the box | `q` leaves the address bar, the heading returns to **Pages**, and the full tree is back. A rail left on an empty result list is a documentation space with no navigation in it. |

Two rows that would be worth having and **cannot be driven from this SPA**, named rather than
silently skipped:

- **A page with no blocks matched on its title alone**, to see the null snippet render as nothing.
  Driveable only by creating a page and not writing in it, which the charter's flow does not leave
  behind; `DocsSearch.test.tsx` covers it and this pass would only be re-asserting it.
- **The company bound.** It is the most important property of the endpoint and this pass has one
  company. `DocPageEndpointTest$Searching.theCompanyBoundIsEnforced` is where it is proved, with two
  companies holding identically-titled pages.

## §236 — the defect link and the step's evidence (C5 + C9)

**Seven rows, none run** (§65). Ids are `§236 DK-*` and `§236 SE-*`; both prefixes were grepped
against this file and the mutation queue before minting. **Every row is lane `A3` — runs** and needs
that lane's state: a started run with at least one failed execution carrying structured steps. No
new fixture is authored; `A3`'s own charter already produces both.

**One existing row changes meaning rather than being replaced.** `M4-10` — *"Record a result on a
step, then attach with that step selected"* — was written in §67 against an affordance the SPA did
not have and has never had; `EvidencePanel` contained the string `stepId` zero times. It is
driveable for the first time as of this slice. Drive it, and read `§236 SE-1`–`SE-3` as the detail
of what it now means; do not treat a pass on `M4-10` as covering them.

**`§236 DK-7` needs a second account.** Everything else is one session as `engineer`.

| # | Drive | Expect |
|---|---|---|
| `§236 DK-1` | Open a **failed** execution. In the **Defects** panel press **Link a defect**, enter `PAY-991` and `https://tracker.acme.test/browse/PAY-991`, press **Link** | The row appears carrying the key as an identifier with the external-link arrow. **The form offers no provider field and no status field** — that absence is the decision (`V62`), and a form that grew either is the regression this row exists for. Clicking the key opens the address in a new tab. |
| `§236 DK-2` | Go back to the **run detail** grid and find that execution's row | The same key is in its **Defects** column. This is the invalidation crossing two screens: the write is on the execution and the read here is the run's, so a panel that updated and a grid that did not means the mutation invalidated the wrong key. |
| `§236 DK-3` | Open the case's **history** page, and the plan's **statistics** page for a blocker | The link is on both, unchanged. Three readers, one write — and the two history screens are the ones nobody looks at after making a link. |
| `§236 DK-4` | Press **Link a defect** again with the **same key and the same URL** | No second row, no error banner, no duplicate in the grid. The server answers 200 rather than 201 and the panel simply shows what is already there. Confirm in the network tab that the status **is 200** — a 201 with one row on screen means the twin exists and the list is deduplicating it. |
| `§236 DK-5` | Enter a key with a URL of `javascript:alert(1)` and press **Link** | Refused with the message under the **Link** field, and **no row is added**. Then confirm nothing was stored by reloading. This is the one row here with a security consequence: the value is rendered as an `href` on three screens. |
| `§236 DK-6` | Press the bin on a linked defect | The row goes, and it is gone from the run grid and the case history too. **Nothing in the tracker is touched** — if the fixture tracker is real, confirm the defect still exists there. |
| `§236 DK-7` | As **`viewer`**, open the same execution | The **Defects** panel renders with every link **visible**, and both **Link a defect** and the bin are disabled with a tooltip naming the missing permission. Not hidden — a Viewer must be able to read which defect a failure belongs to. |
| `§236 SE-1` | On an execution where **no step has been submitted yet**, look under any step row | **Attach to this step** is present and **disabled**, with the sentence *"Record a result for this step before attaching evidence to it."* This is the row that matters: the server answers a 400 naming `stepId` here, so an enabled button would be a guaranteed failure. Mark a step and **do not press Submit** — it must stay disabled, because the mark is local until saved. |
| `§236 SE-2` | Submit a result marking step 2 failed. Reload. Under step 2, press **Attach to this step** and choose a PNG | It is enabled now. The file appears **under step 2** and **not** in the execution-level Evidence panel below. That split is the whole of C9 — before it every attachment landed in one flat list. |
| `§236 SE-3` | Attach a second file to the execution as a whole via the **Evidence** panel, then re-read both places | The execution-level file is in the Evidence panel only; step 2's is under step 2 only. Neither appears twice. `TestExecution.evidence` carries `step_result_id IS NULL` rows alone and `StepResult.evidence` carries the rest — a file in both means the server's partition has been bypassed by a client-side merge. |
| `§236 SE-4` | Network tab on the step attach | The `multipart/form-data` body carries a **`stepId` part**. `M4-11`'s boundary check applies here too. If `stepId` is absent the file still uploads and still succeeds — landing on the execution — which is why this is a network-tab row and not a screen row. |
| `§236 SE-5` | Close the run, then reopen the execution | Both **Attach to this step** and the execution-level **Attach** are disabled with *"This run has ended…"*, and **every attached file stays visible and still opens**. The defect panel's controls are disabled with the matching sentence. A closed run's evidence is the part of the record most likely to be read. |

## §237 — the badge on the checklist, and the two pass rates (C2)

**Nine rows, none run** (§65). Ids are `§237 QB-*` (the runner badge) and `§237 QR-*` (the two
reports); both prefixes were grepped against this file and the mutation queue before minting.

**Lanes.** `QB-*` are lane `A3` — runs, and need that lane's state plus **one quarantined case that
is in the run**. `QR-*` are lane `A3` as well because a report is generated over a run, and they
need the same fixture. **No new fixture is authored**: the quarantine is set through the case's own
**Quarantine…** dialog, which `M3` already drives, so the only addition to `A3`'s charter is to
quarantine one of its cases *before* generating the reports.

**Order matters here in a way it usually does not.** `QR-1` must be read **before** anything is
quarantined and `QR-2` after, because the finding is the *difference between two readings of one
report* — a single reading cannot tell a working exclusion from a rate that was always going to say
that. Note the headline figure from `QR-1` and carry it.

**One row needs a case with no result.** `QR-5` needs a quarantined case that is in the project and
has **no execution in the selection**, which `A3` does not produce by itself — quarantine a case
that has never been run. If the lane's fixture cannot supply one, record `QR-5` as **not driven,
and why**; that is a legitimate outcome and a silent skip is not.

| # | Drive | Expect |
|---|---|---|
| `§237 QB-1` | Open a run whose checklist includes a **quarantined** case, in **Grid** mode | A **Quarantined** chip beside that case's *title*, and on **no other row**. It is an outlined amber chip, not a filled one — it must not read as a verdict, and it must not be in the **Result** column. |
| `§237 QB-2` | Read the chip's tooltip | It says the results are not trusted **and that the case is still to be run** — *"Run it and record a verdict as normal."* A tooltip that stops at "quarantined" is the regression: the word alone reads as "don't bother", which is the one thing it must not mean. |
| `§237 QB-3` | Switch to **Focus** mode and read the queue rail | The same chip on the same case, in the 260px rail. **This is the row the decision rests on** — the queue is where a tester *chooses* the next case, and a badge that waited for the detail screen would arrive after the choice was made. |
| `§237 QB-4` | Open that case's execution | The chip is in the header beside the status chip. `TestExecution` is an `allOf` over `TestExecutionSummary`, so a chip on the checklist and none here means a renderer stopped passing the flag through. |
| `§237 QB-5` | Record a verdict on the quarantined case, in Grid mode and again in Focus | It is accepted, exactly as any other row. **Nothing is disabled, nothing is hidden, no confirmation is asked.** Quarantine changes what the *reports* say and nothing about what the runner may do; a row that refused would hide work from the person doing it. |
| `§237 QB-6` | **Release** the case from quarantine (the case's own screen), then return to the run | Every chip is gone, on all three surfaces, without a hard reload. The flag is read live per request, so a chip that survives is a cache key the release did not invalidate. |
| `§237 QR-1` | **Before quarantining anything**, generate an **Aggregate execution report** over the project | Both **Pass rate (of contributing executions)** and **Pass rate (excluding quarantined cases)** are printed and are **the same number**. **Quarantined cases in this selection** reads `0 (0 executions…)`, and the **Quarantined cases** section is a sentence saying nobody is quarantined — never a missing section. Write the headline figure down. |
| `§237 QR-2` | Quarantine a case that has **failed** in the selection, then regenerate the same report | The **headline** figure is **unchanged from `QR-1`** — that is the assertion, and the one a "simplification" to a single rate would break. The **excluding** figure has moved. The metadata line names the case count and the execution count. |
| `§237 QR-3` | Read the **Quarantined cases** section of that report | Three columns — case, **reason**, quarantined at — with the sentence the person typed in the Quarantine dialog. The narrative says the exclusion is *"read as it stands now rather than as it stood when each result was recorded"*, and reconciles the table's row count against the metadata's contributing count. |
| `§237 QR-4` | Check every **other** section of the same report — status breakdown, failures by suite, by feature, the automation split, retries | The quarantined case's results are **still counted in all of them**, and the status breakdown still sums to the headline. The exclusion is one figure wide; a section that shrank means it leaked. |
| `§237 QR-5` | Quarantine a case with **no result in the selection** and regenerate | It **is** listed in the section and the metadata counts stay where they were. The narrative reads *"…of those contributed 0 of…"*. A quarantined test nobody is running is the finding, not a row to drop. |
| `§237 QR-6` | Generate a **Run summary** report for the run containing the quarantined case | The same pair of rates and the same section, with the scope word **"this run"** rather than "this selection". The per-tester and per-suite tables still count the quarantined rows. |
| `§237 QR-7` | Quarantine **every** case that has a result in the run, then regenerate the run summary | **Pass rate (excluding quarantined cases)** is an em dash `—`, never `0.0%`. Zero over zero is not zero per cent, and a report claiming total failure for a run whose every result was merely discounted is the one direction a defect must not take. |

## §238 — the marker rail that could finally have two marks (C1)

**Five rows, none run** (§65). Ids are `§238 SV-*`; the prefix was grepped against this file and the
mutation queue before minting, and against `DK`/`SE` which §236–§237 took.

**Lane.** All five are lane `A3` — runs — because the thing to look at is the **suite history**
screen, whose entries are runs and whose version-marker rail is the frontend half of this slice.
**No new fixture is authored and none is needed**: the rail has been wired since §101 and has never
rendered a mark, so what these rows check is a client that was written blind. The only addition to
`A3`'s charter is to **change a suite's membership between two of its runs** — file one more case
into the suite after the first run and before the last — which is an ordinary act on a screen the
lane already visits.

**The standing risk this lane carries.** Every row below renders a code path that has been
unreachable in every deployment since it was written. A component that throws on its second marker,
sorts them backwards, or draws the boundary on the wrong side of an entry would have looked correct
for the whole of the product's life, and `npm run test:run` is green either way because the SPA's
tests mock the endpoint. **A finding here is expected rather than surprising.**

| # | Drive | Expect |
|---|---|---|
| `§238 SV-1` | Open a suite's **history** for a suite whose membership changed between runs | The version-marker rail draws **more than one mark**, oldest first. This is the first time this element has had anything to draw; the row is worth running even if every other one is skipped. |
| `§238 SV-2` | Read the entries either side of the mark | The **"the membership changed during this period"** notice fires, and fires **between** the right two entries — the run before the change keeps the old number, the run after it reads the new one. A notice on the wrong side is the same claim inverted, and it is the reading that makes a pass-rate drop look like a regression when it is new coverage. |
| `§238 SV-3` | Read each mark's author and timestamp | Each names the person who made the composition change and when. **No mark carries a change comment**, and the rail must render that absence cleanly rather than as an empty quote or the word `null` — a composition version has no author's note by decision, so this is the normal state and not an edge case. |
| `§238 SV-4` | Open the history of a suite created **before** this slice, or one nothing has changed | **One mark, or none, and no notice** — and the page reads as "nothing changed here", never as an error or a gap. Suites predating the writer carry no marks at all and none was invented for them; the screen cannot tell that apart from a stable folder and must not pretend to. |
| `§238 SV-5` | Compare the pass rates either side of the mark, on a suite that **gained** cases | The suite's rate moves and the screen does **not** invite the comparison across the boundary. §6p calls this the most dangerous screen in the product for exactly this reason, and the mark exists so a reader refuses the comparison rather than making it silently. |

## §239 — the library that is gone, and the cases that referenced it (C4)

**Six rows, none run** (§65). Ids are `§239 RT-*`; `RT` was grepped against this file and the
mutation queue before minting, as were `SS` (taken by §222) and `RL`/`XL` (both in use).

**This is a RETIREMENT, so what these rows check is an absence and a non-event.** There is no new
affordance to drive. The question every row asks is the one a deletion cannot answer from a green
suite: *does a case that used the thing still work now that the thing is gone?*

**Lane.** All six are lane `A1` — authoring — because the screens are the case editor and case
detail, which that lane already visits.

**⚠️ The fixture problem, stated rather than hidden.** Five of these six rows want **a case whose
steps carry a `shared_step_id`**, and **no lane creates one, and none can any more**: the picker is
deleted, the endpoint is gone, and the field left `TestStepInput`, so there is no longer any path
through the product that writes such a row. The only way to author the state is a **direct
`INSERT`/`UPDATE` against `test_step.shared_step_id`** on a provisioned company, naming a live
`shared_step` row (the table is still there and still populated — that is `V63`'s whole point). The
lane's charter must say so, and the row that sets it up is `RT-0` below. If that setup is not done,
`RT-2`…`RT-5` are **not driven, and the reason is that the state can no longer be reached from the
UI** — which is a legitimate outcome and must be recorded as one rather than skipped silently.

| # | Drive | Expect |
|---|---|---|
| `§239 RT-0` | **Setup, not an assertion.** Against a provisioned company, `UPDATE test_step SET shared_step_id = (SELECT id FROM shared_step WHERE project_id = … LIMIT 1) WHERE id = …` for two steps of one case. If `shared_step` is empty, insert an entry first | A case now in the pre-§239 state. Everything below depends on it. Note in the charter that this is the **only** way to produce it. |
| `§239 RT-1` | Open the case **editor** on any case, and read the panel under the step list | **No "Insert shared step" button and no picker.** The panel ends at the step rows. `CaseEditor.Steps.InsertSharedButton` is gone as a locator too, so anything else driving this screen must have stopped looking for it. |
| `§239 RT-2` | Open **case detail** on the `RT-0` case | The two steps render their `action` text **exactly as before**, with **no "Shared" chip** and no tooltip. This is the row that proves the retirement cost no content: the text was always in `test_step.action`, and the chip is the only thing that leaves. |
| `§239 RT-3` | Open the **editor** on the `RT-0` case, change nothing, press **Save** | **200, and the case is unchanged on screen.** ⚠️ Then read the row: `test_step.shared_step_id` for those two steps is now **NULL**, because the field left the payload and a save rewrites the step list. This is the column draining on its own, and it is the intended behaviour rather than data loss — the reference pointed at nothing. Worth watching the response body for a `sharedStepId` key, which must be **absent**, not null. |
| `§239 RT-4` | On the `RT-0` case (before running `RT-3`), open the **version history** panel and read the newest entry | The stored snapshot still renders, and still carries the step text. Old snapshots keep a `sharedStepId` property the shape no longer declares; nothing reads a snapshot today, so what this row really checks is that the **history panel itself** is undisturbed. |
| `§239 RT-5` | **Clone** the `RT-0` case | 201, and the clone's steps carry the same `action` text and **no** reference. `CaseCloneService` stopped copying the field; the clone is the first row written entirely without it. |

---

## §240 — whose draft that is, the plan that left the list, and what a number is OF (audit D + E)

**Fourteen rows, none run** (§65). Ids are `§240 DR-*` (D1), `§240 AR-*` (D2) and `§240 RF-*` (E1
and E2); `DR`, `AR` and `RF` were grepped against this file and the mutation queue before minting,
as were `DB`, `PA` and `FR` (all in use).

**Lanes.** `DR-*` are lane `A1` — authoring — because the screens are the case editor and the New
Case form, which that lane already visits, and because `M2-67`…`M2-75` are already there and **half
of them now assert the OLD shape**. `AR-*` are lane `A2` — plans. `RF-*` are lane `A3` — runs,
because a report is generated over a run.

> **`M2-71` and `M2-72` are now WRONG and must be re-read before they are driven.** They were
> written to demonstrate §128's shared buffer: `M2-71` expects the lead to be offered *the
> engineer's* draft with their name on it, and `M2-72` expects the lead's save to have *destroyed*
> the engineer's work. `V64` makes both false — the lead is offered nothing, and the engineer's
> draft survives marked stale. **They are left in place rather than edited**, because a browser row
> that has never been driven is a claim about a build nobody ran, and rewriting it would erase the
> record that the shape changed. `DR-3` and `DR-4` below are their replacements and say so.
>
> **`M2-75` is still correct** — the New Case form makes no `…/draft` request — but it is no longer
> the whole story, which is `DR-5`.

### A1 — whose unsaved work is on offer

| Row | Drive | Expect |
|---|---|---|
| `§240 DR-1` | Open `TC-1` → **Edit**, type, wait for **"Backup saved …"**, close the tab, reopen the editor | The banner reads **"Your unsaved changes from …"** and the sentence under it is **"You left work on this case without saving it."** No name anywhere. This is `M2-67` with the new copy, and the reason it is a row of its own is that the *name* was the whole of the old sentence. |
| `§240 DR-2` | Restore that draft and press **Save changes** | Unchanged from `M2-74`: the save lands, and reopening the editor offers nothing. Included because the clear-on-save now names the saver, and a per-person delete that deleted the wrong row would look identical here until somebody else opened the case. |
| `§240 DR-3` | **Two accounts, two browsers** — replaces `M2-71`. As the engineer, open `TC-3` → **Edit**, type, wait for the backup. As the lead, open `TC-3` → **Edit** | The lead is offered **nothing at all**: no banner, and the form holds the saved case. Confirm in the network tab that the lead's `GET …/draft` answered **204**, not 200 — the two are indistinguishable on screen and only one of them means what this row is about. |
| `§240 DR-4` | ⚠️ **The row no gate can reach, and the whole of D1** — replaces `M2-72`. Continuing `DR-3`: as the **lead**, press **Save changes** on `TC-3`. Then as the **engineer**, reload the editor | The engineer's draft is **still there**, and the banner now carries the stale sentence: *"The case has been saved since this draft was written, so restoring it will discard that change."* Under §128 their work was gone with nothing on screen to say so. **Two accounts and two browsers, and no suite can substitute for it.** |
| `§240 DR-5` | Open **New case** (`/cases/new`), type a title, an objective and a step, wait ~3 seconds, then **close the tab**. Reopen `/cases/new` | The restore banner appears with **"Your unsaved changes from …"**, and **Restore** puts every field back. `M2-75` still holds: there is **no `…/draft` request** in the network tab, because this buffer is `localStorage`. Both halves matter — the work comes back, and the server was never asked. |
| `§240 DR-6` | Continuing `DR-5`: **Restore**, then press **Create case** | The case is created, and reopening `/cases/new` offers **nothing**. Then check `localStorage` for `tcms.newCaseDraft.<projectId>` — it must be **absent**. A buffer that survived its own create would offer a copy of a case that now exists. |
| `§240 DR-7` | Type into **New case**, wait for the backup, then **without creating** navigate to a *different project* and open its **New case** form | **No banner.** The key is per project, and the failure this row exists for is a half-written case crossing the isolation boundary in the one place no server check can see it. Then go back to the first project's New Case form: the draft is **still offered**. |
| `§240 DR-8` | With unsaved work in **New case**, run `tcmsFaults.status(503, 30)` and keep typing | **Nothing changes**, and that is the expectation: this buffer never touches the network, so a faulted API cannot break it. The contrast with `F-12` (the edit-mode buffer's status line goes to "Could not save a backup…") is the point — one row, two buffers, two behaviours. |

### A2 — the plan that could leave the list

| Row | Drive | Expect |
|---|---|---|
| `§240 AR-1` | On the plan list, note an **APPROVED** plan, press its archive control (the tray icon at the end of the row) | The row **disappears from the list**, and a snackbar-free, dialog-free act — there is deliberately no confirmation. Turn on **Show archived**: the plan is back, with an **Archived** chip beside its NAME and its status chip still reading **Approved**. That last detail is the whole reason this is not a fourth status. |
| `§240 AR-2` | With **Show archived** on, copy the URL and open it in a new tab | The list still includes the archived plan — the toggle is in the query string (`?archived=true`), so the view can be sent to somebody. |
| `§240 AR-3` | Open the archived plan (click through from the archived list) | The plan page opens normally and every control works. Edit its name and save: **200**. This is the row that proves archiving is a visibility fact rather than a lock — the tempting implementation freezes the plan, and nothing on this screen would look wrong if it did until somebody tried. |
| `§240 AR-4` | ⚠️ **Against a plan that runs were executed against** (lane `A3`'s fixture): archive it, then try to **Delete** it | The delete is still refused with the **409 naming the runs**. Archiving is what to do *instead of* that delete, not a way through it, and a build that quietly allowed the delete after archiving would look like a feature. |
| `§240 AR-5` | As a **Viewer**, open the plan list | The archive control is **present and disabled**, with the tooltip naming the permission (§31: disable, don't hide). **Show archived** is **not** gated — somebody who cannot archive still has to be able to find the plan somebody else did. |
| `§240 AR-6` | Restore the archived plan (**Show archived** on, press the same control, now an out-tray icon) | It returns to the working list with its status unchanged, and the Archived chip goes. Then press archive twice in quick succession on another plan: the second press must not change anything — no re-stamp, no second audit entry (check the project's audit view if the lane visits it). |

### A3 — what a number is of, and the retry that queued nothing

| Row | Drive | Expect |
|---|---|---|
| `§240 RF-1` | Generate a **run summary** (MD) and open the on-screen report | The provenance table's last row before the selection reads **Data freshness: Live — computed directly from this project's records as at …**. Download the MD and the HTML: **the same sentence, verbatim, in both**. |
| `§240 RF-2` | ⚠️ **The whole of E1, and it needs two documents.** Generate an **aggregate execution** report over the same project and open it beside `RF-1`'s | This one reads **"As of … — computed from the execution fact projection, which was refreshed at that instant, immediately before this report was produced."** Read the two side by side: before this slice they were indistinguishable on the page, which is why a single report proves nothing here. |
| `§240 RF-3` | Record a result in a run, then **immediately** generate an aggregate report | The freshness instant is **within seconds of now**, not up to fifteen minutes old, and the new result is in the numbers. The refresh runs inside the job — this row is what tells a reader that the sentence is about *this* report rather than about the schedule. |
| `§240 RF-4` | ⚠️ **Requires `curl` or the MCP client — the SPA deliberately sends no key.** `POST …/reports/generate` twice with the same `Idempotency-Key` | The first answers **202** with a new job; the second answers **200** with **the same `jobId`** and the same `Location`. Then open the reports page: **one** row, not two. The status is the only thing that tells a caller their retry queued nothing, and a build that answered 202 twice looks identical until the list is read. |

## §242 — the audit trail, on the two screens that finally read it

**Ten rows, none run** (§65). The automated gates behind them are green (frontend **1602 / 120**). Ids are `§242 LG-*`; `LG` was grepped against this file and the
mutation queue before minting (`LC`, `LD`, `LK`, `LP`, `LR`, `LV` are all in use).

**Lane `A3` — administration.** Both screens are administrative and the personas this needs (a
Company Admin, a Test Lead, a Test Engineer) are the ones `A3` already has signed in. It also has
to run **after** `A1` and `A2`: an audit trail is a record of acts, so the rows these checks read
are the ones the authoring and plan lanes wrote earlier in the same pass. A trail driven first is
an empty trail, and `LG-9` is the only row that wants it that way.

> **The fixture this lane must NOT author.** Two rows need a **token-driven** act and one needs an
> **unattended** one, and neither can be produced by clicking. `LG-4` is reachable through `A3-24`'s
> `Agent` token plus a CLI or MCP write. ~~`LG-5` (`actorUserId` null) has **no producer in the
> product at all** today — every write path carries a caller — so it is recorded as **not
> drivable**~~ → **superseded 2026-08-22 by area C (§247)**: a `run_schedule` firing is the
> product's first actorless writer, so `LG-5` is now driven from the schedule this pass creates in
> `§247 SK-1`. That is why the row moved from "not drivable" to a **Before you start**. A silent
> skip is what §65 exists to prevent; "not driven, and here is why" is a legitimate outcome, and so
> is a row that stops needing it.

| Row | Drive | Expect |
|---|---|---|
| `§242 LG-1` | As a **Test Lead**, open a project whose cases `A1` edited → **Audit trail** in the Project group of the rail | The rail entry **is there** (this is the one gated pair in the rail — see `LG-7`), and the trail lists that lane's acts **newest first**. Every row names a person, an act in words (*"Case updated"*, not `case.updated`), what it was on, and an age with the exact instant on hover. |
| `§242 LG-2` | Type `case.updated` into **Action** and read the URL | The URL gains `?action=case.updated`, the grid narrows, **and the paging resets to page 1**. Copy the URL into a second tab: the same narrowed trail. This is the row that makes a filtered trail a thing somebody can paste into a ticket, which is the whole reason the filter is not component state. |
| `§242 LG-3` | Set **From** and **To** to the same day, one that `A1` worked on, then to a day nothing happened | First: rows, all within that day. Second: the **"No acts match"** empty state with a **Clear filters** control — *not* "Nothing recorded yet". The two empty states say different things and only one of them is about the filter. |
| `§242 LG-4` | ⚠️ **Needs a CLI or MCP write under the `Agent` token from `A3-24`.** Record a result through `tcms result record`, then open the run's project audit trail | The newest row names **the token's owner as the person AND carries the robot marker**, whose tooltip names the token. **Both halves, not either** — this is the one behaviour the plan for this slice got wrong, and a build that renders only the person says a human typed what a pipeline did. |
| `§242 LG-5` | An act with `actorUserId` null renders as **"Automatic"** with no person and no token. **Before you start:** the `Nightly smoke` schedule this lane creates in `SK-1` must have fired at least once — see `SK-3` for the two ways to make that happen inside a pass. Open the project audit trail, filter **Action** to `run.created`, and read the scheduled run's row **beside a hand-created run's row** | The scheduled row shows **"Automatic"**, with no person and no robot marker; the hand-created row beside it shows a name. ~~**NOT DRIVABLE.** No producer exists — every write path in the product carries a caller… it becomes drivable when scheduled runs land.~~ → **superseded 2026-08-22 by area C (§247).** `V68`'s poller is the product's **first actorless writer**: a fire creates a run with a null `created_by` and an audit row with both actor columns null, because at 03:00 nobody performed the act. The id is unchanged and is quoted from `docs/handoff/242`. Both rows in one screenshot is the whole check — "Automatic" alone would also be what a build that had simply stopped recording actors renders. |
| `§242 LG-6` | As a **Company Admin**, open **Administration → Audit trail** | The company trail lists acts from **every** project plus the ones belonging to none — a member invited, a role granted. Confirm at least one row has an **empty project context**: those are the rows a project trail structurally cannot show, and they are why this second screen exists. |
| `§242 LG-7` | ⚠️ **The gating pair, and it breaks this rail's own rule on purpose.** Sign in as a **Test Engineer** and read the rail, then as a **Test Lead**, then as a **Company Admin** | Engineer: **neither** audit entry. Lead: the **project** entry only. Admin: **both**. These are the only permission-gated items in the rail and they are **hidden, not disabled** — the opposite of §31 everywhere else, taken deliberately (see `AppShell.AuditReach`). If a later pass finds them disabled-with-a-tooltip instead, that is a regression toward the general rule, not a fix. |
| `§242 LG-8` | As that **Test Engineer**, paste `/projects/<id>/audit` into the address bar | The page loads and says, in a sentence, that reading the trail is part of administering the project and who to ask. **Not a 404, not a redirect, and not an empty grid.** Then check the network tab: **no `audit-events` request was made at all** — the screen knows the answer and does not ask the server to refuse it. |
| `§242 LG-9` | On a **freshly provisioned project nobody has touched**, open its audit trail | **"Nothing recorded yet"** with no Clear-filters control. The row that matters here is the contrast with `LG-8`: an empty trail and a refused trail must never look the same, because an empty audit trail reads as *"nothing happened"* and a reader has no way to tell that apart from *"you were not allowed to look"*. |
| `§242 LG-10` | With more than one page of acts, walk to page 2 and back, then change the page size | No row appears on both pages and none is missing between them, and the pager's total is the **count of the filter** rather than the page length. The sort key is `(occurredAt DESC, id DESC)` and the secondary half is what this row is about — acts written in one transaction share a timestamp to the microsecond, which `A1`'s bulk operations produce for free. |

---

## §243 amendment (2026-08-21) — review-workflow notifications

**None run** (§65). This slice's frontend surface is **two label maps and nothing else** — the
channel dialog's checkbox list is derived from `notificationEventTypeLabels`
(`NOTIFICATION_EVENT_TYPES`, `frontend/src/lib/labels.ts`), so four new members rendered four new
checkboxes with no component change. That is the thing worth confirming with eyes: a derived list
is only as good as the derivation.

> **The fixture this lane must author.** Every row below needs a project with §6g's **review
> workflow switched on** for at least one artifact type (Project settings → Review), which no
> existing lane creates. `RN-3` additionally needs a **reachable webhook** — an operator-run
> receiver on the lab host — and is marked as **not drivable with the current fixture** rather than
> skipped, because a Slack workspace is not something this pass can conjure.

| Row | Drive | Expect |
|---|---|---|
| `§243 RN-1` | Open **Project settings → Notifications**, add a channel, and read the event list | **Six** checkboxes, not two: Run completed, Work assigned to somebody, Review requested, Peer said it looks good, Peer asked for changes, Review approved. Each carries its own help text under it. The list is derived, so a missing entry here means a label map was edited and the contract was not — or the reverse. |
| `§243 RN-2` | Read the help text under **Review approved**, and under **Peer said it looks good** | The first warns that a bulk approval sends **one message per artifact, up to 500**. The second says explicitly that LGTM **is not the sign-off**. Both sentences are the product's only warning about behaviour a subscriber cannot discover before it happens to them; if either has gone, that is a regression in the copy and not a cosmetic one. |
| `§243 RN-3` | ⚠️ **NOT DRIVABLE with the current fixture — recorded rather than skipped.** Subscribe a channel to `review.requested`, ask a colleague for a review, and read the message that arrives | **Needs a reachable receiver.** Every automated test in this slice posts to `127.0.0.1:1`, which refuses instantly, so **no test has ever read a rendered review message end to end** — the renderings are asserted as objects and the deliveries as rows. What to look for when a receiver exists: the artifact named as `PLAN-3 — Release 2.4`, never a UUID, and the reviewer named in the title. |
| `§243 RN-4` | Subscribe a channel to `review.approved`, then set a **failure threshold** on it | The threshold control is **shown and inert**, as it is for `work.item_assigned`. Serving it per event type is deliberate (`NotificationSubscription.minFailures`) — hiding it would put a rule the server owns into the client. Confirm the help text says so rather than leaving a control that looks broken. |
| `§243 RN-5` | Save a channel subscribed to all four `review.*` events, reload the page, and reopen it | All four come back checked. The round-trip is what proves the generated union and the server's `DELIVERABLE_EVENTS` agree in the one place a user can reach — `NotificationEventTypeTest` asserts that against the documents on disk, which is not the same as against a running server. |
| `§243 RN-6` | Open the **delivery log** for a channel that has fired | Review deliveries render with their event in words. `notificationEventLabel` has a fallback, so a name the vocabulary later drops still reads — check that a `review.*` row does **not** fall back to the humanized wire name, which would mean the label map and the log disagree. |

---

## §244 — the stopwatch on the result form, and the two screens that must not clear it

**Six rows, none run** (§65). ⚠️ **And this slice is unverified in a second sense the others were
not: NO AUTOMATED GATE WAS RUN AGAINST IT EITHER.** Area A was built under a directive to run no
tests at all — no `./gradlew build`, no `npm run test:run`, no `npm run build`, no `npm run lint` —
so the usual "the suite behind these rows is green" sentence **cannot be written here**. Two
targeted backend classes were run before that directive arrived (`ExecutionDurationTest` and
`IngestionEndpointTest`, BUILD SUCCESSFUL); everything else is unrun. Read every row below as
resting on nothing but the code.

Ids are `§244 ED-*`; `ED` was grepped against this file and the mutation queue before minting.

**Lane `A3` — execution.** Every row is the result form or the run's checklist, which is what `A3`
already drives. No new fixture: `A3` records results already, and the control is on the screen it
records them from. `ED-5` wants a run that `A3` has recorded into **twice**, which its amend rows
already produce.

> **The one row that needs another surface.** `ED-6` drives the CLI or MCP, the same shape
> `§242 LG-4` established, and reuses `A3-24`'s `Agent` token rather than minting one.

| Row | Drive | Expect |
|---|---|---|
| `§244 ED-1` | Open a case in a run (**Focus** mode), scroll to **Notes**, type `4.5` into **How long it took (seconds)** and record **Passed** | The result saves. Reopen the execution: the box reads **4.5** again. This is the whole slice in one row — before it, the field a person could set did not exist and `duration_ms` had no writer at all. |
| `§244 ED-2` | Reopen that execution, **clear the box**, and record again | The duration is **gone**, not preserved. The body states the whole result, so an empty box is a clear — the same rule the comment has carried since Phase 1, and the reason the helper text says so out loud. A build that keeps the old number here has made the field un-clearable from the UI. |
| `§244 ED-3` | Type `abc` (or `-1`) into the box | The field goes **red** with *"Give a number of seconds, or leave it empty."* and **all four verdict buttons disable**. ⚠️ **Check the disabling specifically** — without it, recording with unreadable text would send `null` and *silently clear* a duration the tester never meant to touch, which is worse than the refusal. |
| `§244 ED-4` | Type `0` and record | Saves as **0**, and reopening shows `0` rather than an empty box. "It was instant" and "I did not time this" are different statements and every falsy check between the form and the column would collapse them. |
| `§244 ED-5` | ⚠️ **The regression this slice is most likely to cause.** Record a duration in **Focus** mode, then switch to **Grid** mode and mark that same row **Passed** from the row's own buttons. Reopen the execution | The duration is **still there**. Grid mode has no duration control, and the body is total state — so a row verdict that omitted the field would clear a number the tester recorded on another screen, from a screen that never showed it to them. Repeat with the grid's **Blocked** dialog, which is the second such site. |
| `§244 ED-6` | ⚠️ **Needs a CLI or MCP write**, under `A3-24`'s `Agent` token. `tcms result record TR-n TC-n --status Passed --duration-ms 4500`, then open that execution in the browser | The screen shows **4.5** seconds. Then run the same command with `--duration-ms ''` and confirm the screen shows an empty box. The unit boundary lives in one place (`frontend/src/lib/format.ts`) and the CLI names milliseconds in its own flag rather than converting — this row is what proves the two ends agree about which unit they are in. |

---

## §245 — bulk execution assignment (area B of the gaps plan)

⚠️ **These rows are unrun AND the code behind them is ungated.** Area B was built under the same
no-tests directive as area A: no `./gradlew build`, no `npm run test:run`, no `npm run build`, no
`npm run lint`, and no targeted class either. Read every row below as resting on nothing but the
code. The suite counts quoted anywhere near this area are §243's and are stale.

Ids are `§245 BA-*`; `BA` was grepped against this file and the mutation queue before minting, and
**the section number is `§245`, not the `§244` the stepped plan wrote** — area A took `§244` first.

**Lane `A3` — execution.** Every row is the run detail's checklist, which `A3` already builds:
`TR-1` with results recorded into it. Two rows need people the pass already has — `A0`'s six
accounts — and `BA-4` needs an administrator to offboard one of them, which `L1` already does to a
different account. **No new fixture is asked for**, with one exception named in its own row.

| Row | Drive | Expect |
|---|---|---|
| `§245 BA-1` | On the run detail, tick three rows and read the bar. Then click a different filter chip | The bar names the count, **Assign…** is enabled for a Test Lead, and the caption reads *"Selection covers the rows this filter shows."* — not "on this page", because this screen is not paged. Switching the chip **clears the selection**: a selection holding rows the chip has hidden is a bulk act over rows the person cannot see. |
| `§245 BA-2` | Select ~12 rows, choose **Spread them across several**, pick five people in order, **Preview changes**, then commit | The split is **3/3/2/2/2** and the **first two** people in the list hold the extra one. The chosen people render as a numbered list. The per-row list names each case's destination. The snackbar reports **per outcome** (`n assigned · n already assigned · n skipped`), never a blanket success. |
| `§245 BA-3` | ⚠️ **The row that stands in for an idempotency key.** Send the identical distribute a second time — same rows, same five people in the same order | Every row comes back as **already assigned**, `changed` is empty, the **Assign** button stays disabled over that preview, and the project audit trail gains **no second group**. If this row fails, the determinism argument in the contract is false and the endpoint needs a different answer — not a patched expectation. |
| `§245 BA-4` | Open the dialog and choose somebody, then have an administrator **offboard that person** in another tab, then commit | A **400 landing on the people control** — not in the corner and not a toast — and **nothing assigned**: not a partial act. One transaction is what makes that true. |
| `§245 BA-5` | Complete the run, then select rows on it. Then force the request from devtools | The **Assign…** button is **disabled with the freeze explained in its tooltip** — §31, said before the server has to. The forced request answers **409** and the dialog renders it in place. |
| `§245 BA-6` | Open the project audit trail after `BA-2` | **One** collapsible row reading *"Execution assigned — 12 of one operation shown here"* and *"12 items"*, not twelve lines. Expand it: twelve rows, each naming its own execution. This is the debt `§242` left for this area in as many words. |
| `§245 BA-7` | Page through a trail where one bulk act straddles a page boundary (assign 12 rows with the pager at 25 rows and enough other acts around it) | **Two groups, one per page**, each counting only what is on its page — the honest reading, since the page cannot see the rest. And **no row appears twice or goes missing**, which is §242's `(occurredAt DESC, id DESC)` still doing its job under the new rendering. |
| `§245 BA-8` | As `viewer` (an account holding no `run:execute`), select rows on a live run | The **Assign…** control is **present and disabled with a reason** — §31, and the opposite of the audit rail's deliberate exception. ⚠️ **Note the correction**: the stepped plan said "a Test Engineer", and a Test Engineer **holds** `run:execute` (roles spec §A3, `V3`) — so this row must use a Viewer or it proves nothing. |
| `§245 BA-9` | In one-person mode, choose **Nobody (unassign)** over rows currently assigned, preview and commit | Every row's destination reads **nobody**, and the audit rows say **Execution unassigned**. `null` is a value here and not an omission; a picker that could not say "nobody" would make "take these off everybody's plate" inexpressible. |

---

## §247 — scheduled runs (area C of the gaps plan)

⚠️ **Unrun, and the code behind them is completely ungated.** Area C was built under the batch-3
no-gates directive: no `npm run test:run`, no `npm run build`, no `npm run lint`, and no backend
gate either. The eleven `RunSchedulePage.test.tsx` arms exist and **have never executed** — eleven
counted off the file, not off memory. Read every row below as resting on nothing but the code.

Ids are `§247 SK-*`; `SK` was grepped against every row id in this file and in
`backend-mutation-checks.md` before minting. **This section also supersedes `§242 LG-5` in place**
— see that row and its lane preamble above; the id is quoted from `docs/handoff/242` and is
**never renumbered**.

**Lane `A3` — execution.** It already has a project, suites, cases, tags and the three personas
these rows need (a Test Lead, a Test Engineer, a Viewer). The new screen is at
**`/projects/<id>/run-schedules`**; nothing in the rail links to it yet, so reach it by URL.

> **The fixture this lane must author.** One schedule, named **`Nightly smoke`**, cadence
> `0 0 3 * * *`, zone `Europe/Sofia`, tags `smoke` — created by the **Test Lead** through the New
> schedule dialog in `SK-1`. `A3`'s cases must include at least one carrying the `smoke` tag, which
> the lane's own charter already authors.
>
> ⚠️ **And the thing this lane cannot wait for.** A real fire on a three-a.m. cron cannot be
> observed inside a browser pass, and the poller ticks once a minute after a three-minute grace. The
> row that needs an actual run is `SK-3`, and there are exactly two honest ways to get one:
>
> **(a) a second schedule on a one-minute cron** — create `Minute smoke` with `0 * * * * *`, wait
> for two ticks, then delete it. Costs nothing but time, uses only the product's own controls, and
> is the option to prefer.
>
> **(b) drive the slot from `psql`** — `UPDATE run_schedule SET next_fire_at = now() WHERE name =
> 'Nightly smoke';` and wait one tick. Faster, and it proves slightly less: the cadence itself is
> not exercised, only the fire.
>
> **State which one was used in the pass notes.** Neither is a skip; choosing without saying is.

| Row | Drive | Expect |
|---|---|---|
| `§247 SK-1` | As a **Test Lead**, open `/projects/<id>/run-schedules` and use **New schedule** to create `Nightly smoke` — `0 0 3 * * *`, `Europe/Sofia`, tags `smoke` | A row appears with the cadence and the zone shown as separate lines, a **Next run** in the future, and **Owner** naming the lead. ⚠️ **Read the Owner column heading**: it says *Owner*, not *Created by*, and the distinction is the whole area — this person is the **authority** each fire is gated as, not the author of the runs. |
| `§247 SK-2` | Type `every monday please` into **Cadence** and save. Then fix the cadence and type `Europe/Sofiaa`-shaped nonsense into the zone — or note that the zone is a **select** and say whether a free-text zone would have been better | The cron failure is a message **under the Cadence field**, not a corner toast, and **nothing is stored** (the list is unchanged behind the dialog). The zone control is a picker precisely because a mistyped zone is a 400 nobody can diagnose; confirm the picker offers `UTC` and the schedule's own current value. |
| `§247 SK-3` | ⚠️ **The row the fixture note above is about.** Make `Nightly smoke` fire, by (a) or (b), then reload the list | **Last run** shows a run key (`TR-n`), **Next run** has moved forward, and **no error banner** is on the row. Open that run: its source reads **"scheduled"** — not *"manual"* — and it has a checklist with one execution per `smoke` case. ⚠️ **If it reads "manual", stop and report it**: that is the exact defect `runSourceLabels` replaced a ternary to prevent, and it typechecks. |
| `§247 SK-4` | ⚠️ **The feature's honesty, and the most valuable row here.** Archive the project (**Project settings → Archive**), make the schedule fire again, then reload the list | The row carries a **warning banner** naming the archive, **Next run has still advanced**, and **no new run was made**. Then unarchive and fire again: the banner **disappears**. A schedule that stopped silently is the failure mode of scheduling anything, and this string is the only place a reader could ever find out. |
| `§247 SK-5` | Demote the schedule's owner to **Viewer** on this project (a second account is easier than demoting yourself), fire again, and read the row | The banner names the **grant** — "no longer has permission to create runs" — and no run was made. This is the row that proves a schedule is not a standing, unrevokable right to create runs in somebody's project. Restore the grant afterwards. |
| `§247 SK-6` | Edit `Nightly smoke` to a cadence with nothing left in it — `0 0 3 1 1 2020` — and save | **Next run** reads **"Will not fire again"**. ⚠️ **Not a blank cell**: null is a real answer here, and an empty cell reads as *"loading"* or *"soon"*, both false. Then restore the cadence. |
| `§247 SK-7` | Turn the schedule off with the **Active** switch, then use the **Only active schedules** toggle above the list, and copy the URL into a second tab | The row shows a **Paused** chip; the toggle puts `?enabled=true` in the **URL** and hides it; the pasted URL opens the same narrowed list. Filter state in the URL is what makes "here are the paused ones" a link somebody can send. |
| `§247 SK-8` | Sign in as a **Viewer** and open the same URL | The **whole list is visible** and **New schedule**, **Edit** and **Delete** are **present, disabled, and explained on hover**. §31's disable-don't-hide — reading a schedule costs project visibility alone, and writing one costs `run:create`. ⚠️ **If they are hidden instead, that is a regression toward §242's deliberate audit-rail exception**, which was argued for a screen whose subject is who did what. This one's subject is a cadence. |
| `§247 SK-9` | With the schedule holding a **Last run**, delete it, then open the run list | The schedule is gone and **the run it created is still there**. A schedule records no work — the runs it made are ordinary runs and outlive it — which is why there is no soft delete and no confirmation about losing history. |
| `§247 SK-10` | Open the project **audit trail** and filter **Action** to `run_schedule.created` / `run_schedule.deleted` | Both acts are there, named in words, with the lead as the actor. ⚠️ **The contrast with `§242 LG-5` is the point of doing these two rows together**: an act on the *schedule* names a person, and the run the *schedule* makes names nobody. Two rows in one trail, and the difference is the whole design. |
| `§247 SK-11` | ⚠️ **A judgement this plan could not settle — report an opinion, not just a pass/fail.** Look at the dialog's scope control: it offers **tags only**, and preserves any plan, case and suite ids the schedule already holds without showing them | Say whether that is acceptable. The server accepts all four arms and the `PUT` states the whole schedule, so the ids **are** sent back — but a schedule created through the run dialog's four arms and then edited here shows a scope that looks smaller than it is. The alternatives are a full scope picker (the run create dialog's, reused) or a read-only summary line beside the tags box. Neither is built. |

---

## §248 — the execution keyboard (area F of the gaps plan)

⚠️ **Unrun, and the code behind them is completely ungated.** Area F was built under the batch-3
no-gates directive: no `npm run test:run`, no `npm run build`, no `npm run lint`, not even a single
targeted file. The `executionKeys.test.ts` arms and the eighteen `ExecutionPage.test.tsx` arms
exist and **have never executed**. Read every row below as resting on nothing but the code.

Ids are `§248 KB-*`; `KB` was grepped against this file and the mutation queue before minting.

**Lane `A3` — execution.** Every row is the execution *detail* screen in Focus or Split mode,
which `A3` already drives: `TR-1` with cases to record into. **No new fixture is asked for** — the
run `A3` builds has more than two cases, which is all `KB-4` needs. `KB-10` needs a screen reader
(VoiceOver on the pass's Mac is enough) and is the one row that cannot be done by looking.

**Nothing is added to `docs/verification/backend-mutation-checks.md`.** Area F is frontend-only:
the only write it can cause is `PATCH …/executions/{id}`, which is the same request the verdict
buttons already send and which `§244 ED-*` and the M2 rows already cover. A mutation row here would
be a second copy of an existing one.

| Row | Drive | Expect |
|---|---|---|
| `§248 KB-1` | ⚠️ **The defect this feature ships broken everywhere, and the reason to do this row first.** Open a case in **Focus** mode, click into **What happened**, and type a real sentence containing every bound letter — *"problem: staging kept failing, skipped for now"* | The sentence lands in the box **in full** and **nothing is recorded**: no verdict, no navigation, no dialog. If a single character of it triggers anything, stop and report it — the guard is the feature. Repeat in the per-step **What actually happened** box and in **How long it took**, which are the two other typing surfaces on this screen. |
| `§248 KB-2` | With focus on the page background, press `p` | The result records as **Passed** and the screen returns to the run, exactly as the button does. Then press `f` on another case, and `s` on a third. |
| `§248 KB-3` | Press `b` | The **precondition checklist dialog** opens — it does **not** record. Blocked is never one keystroke in any mode, which is the rule `executionMode.ts`, `ExecutionGrid.tsx` and the execution screen all carry. Cancel the dialog and confirm nothing was written. |
| `§248 KB-4` | Press `j`, then `j` again, then `k` | The screen moves to the next case in the run **in the rail's order** (by case key, `queueOrder.ts`), then the next, then back. In **Split** mode the rail's highlight and its *"Case n of m"* follow along, and `?mode=split` survives every move. At the last case, `j` does nothing and says so, and **Next case** is disabled. |
| `§248 KB-5` | Press `n`, type a note, press `Escape`, then press `p` | `n` puts the caret in **What happened**; the typed note is untouched by the keys in it; `Escape` takes focus out; `p` then records **Passed** *with the note attached*. This is the loop the feature is for — describe, then verdict, without a pointer. |
| `§248 KB-6` | Press `?` | The **Keyboard shortcuts** overlay opens and lists **every** binding, including `Escape`, each with a sentence. Close it with `Escape`. Then open it again from the **Keyboard shortcuts (?)** button — an overlay reachable only by a shortcut is circular help. |
| `§248 KB-7` | On a **completed** run, press `p`. Then, as an account holding neither `result:submit` nor `result:amend` (a Viewer), press `p` on a live run | **Nothing is recorded** in either case, and in both the reason is *announced* — the freeze sentence, and the permission sentence. ⚠️ **This is the row that catches a keyboard used as a way past a gate the buttons enforce.** A shortcut that 403s is worse than a disabled button, because nothing on screen ever said no. |
| `§248 KB-8` | Type `abc` into **How long it took (seconds)**, click out of the box, then press `p` | **Nothing is recorded** and the refusal is announced. The verdict buttons already disable here (`§244 ED-3`) because the body states total state and would silently clear the duration — the keyboard must refuse for the same reason and not route around it. |
| `§248 KB-9` | ⚠️ **A judgement this plan could not settle — report an opinion, not just a pass/fail.** Mark two steps, then press `j` | The move is **refused** with *"Record or clear this result first…"*, and **Previous case** / **Next case** are disabled with that reason in their tooltips. The marks survive. **Then say whether the refusal is right**: the alternative is a confirm dialog, which this repo has no shared component for. One keystroke discarding a run's marks is the failure it prevents; being unable to skip a case without clearing it is the cost. |
| `§248 KB-10` | ⚠️ **Needs a screen reader** (VoiceOver, ⌘F5). With it on, press `p`, then `j`, then `p` on a completed run | Each act is **spoken**: the verdict being recorded, the case moved to and how far through the run it is, and the refusal. Nothing is spoken twice, and nothing interrupts — the region is `polite`. Without this row the a11y half of the feature is a claim rather than an observation. |

---

## §249 amendment — cross-project case search (`/search`)

**Lane `A1` — authoring.** The rows need cases in more than one project and a signed-in person
granted only some of them, which is `A1`'s own state: it is the lane that authors cases, and it is
the only lane that exists before any run or trail. It must run **after** `A1`'s cases are written —
a search driven first searches nothing.

> **The fixture this lane must author, and no existing lane creates it.** Three projects in one
> company; the signed-in person granted **exactly one** of them; and cases whose titles share one
> word across **all three**. Without the ungranted two the only row that matters (`CS-1`) cannot
> fail, because a leak needs somewhere to leak from — every other row on this screen would pass
> against a build with no narrowing at all.
>
> Two ways to get there, and the pass should say which it used:
>
> **(a) two accounts** — sign in as a Company Admin, create the three projects and their cases, then
> sign in as a Test Lead granted one project. Proves the narrowing against a real second session.
>
> **(b) one account, grant revoked** — create everything as the Admin, then remove two of the three
> project grants from the account you then use. Faster, and proves slightly less: a stale cached
> permission would be indistinguishable from a working narrowing until a reload.

| Row | Drive | Expect |
|---|---|---|
| `§249 CS-1` | ⚠️ **The one row on this screen that is a security check rather than a UI check — do it first.** As a person granted **one of three** projects, search a word that matches cases in all three | Hits from the granted project only — **and read the result count above the table.** It must be the number of *granted* matches, not the company's. This is the browser half of `theTotalIsNarrowedToo`: a build that filtered in Java shows the right rows beside a count that discloses how many cases exist in projects this person was never granted. If the count is higher than the rows can account for, stop and report it. |
| `§249 CS-2` | Same search, set the page size to 25 and walk to the last page | No row repeats, none is missing, and the last page is not empty. The tie-break is the company-allocated case key, so the order is total across projects rather than only within one. |
| `§249 CS-3` | As a member with **no project grants at all**, search anything | A sentence saying you can open no projects and to ask an administrator — **not** "no case matches". The two are the same HTTP response and must not be the same screen; getting this wrong makes a permissions problem look like an empty product. |
| `§249 CS-4` | Search `TC-<n>` for a case in a project you are **not** granted (take a real key from the Admin session) | Empty, with the ordinary "no case matches" copy. Not a 404, not an error, and **no hint that the key exists** — a key lookup is gated exactly like a search, which is the arm most likely to be forgotten because it looks like an id lookup. |
| `§249 CS-5` | Clear the search box | The list empties, the prompt returns, and **no request is made** — check the network panel. The server 400s an unbounded search and the screen must not ask it to. Then type a single character: still no request. |
| `§249 CS-6` | Copy the URL mid-search into a new tab | The same search, the same page, the same results. Filter state is in the URL; a search nobody can share is half a feature, and this is the one screen whose whole output gets pasted to a colleague. |
| `§249 CS-7` | Open a hit, then press Back | The result set and the page are still there, and the hit opened **in its own project** — check the breadcrumb names the project the row named, not the last project you were in. |

---

## §250 — the audit remediation (`001-frontend-audit-r1`)

**Lane `A1` — authoring**, for the case-search rows; **lane `A0`** for the admin ones. These are the
browser half of fixes whose automated half is already green: the build, the lint and the component
suites prove the code compiles and the units are right, and **none of them can see a request that
was never made, a live region nobody hears, or a colour whose meaning changed.** That is the whole
population below.

No row here needs a fixture the pass does not already author, with one exception noted on `AR-3`.

| Row | Drive | Expect |
|---|---|---|
| `§250 AR-1` | On `/search`, open the network panel and type `payment gateway` at an ordinary typing speed | **One** request, not one per keystroke — the box is debounced 300 ms while the URL still updates as you type. Before §250 this screen issued ~14 requests against the widest read in the app. Then type into the case grid's filter and the step-suggestion popup and confirm both still behave as they did: all three now share `useDebouncedValue`. |
| `§250 AR-2` | With the network panel throttled hard, start a search and watch the request | It gives up at **3 s**, not 10 — the typeahead budget. The failed state renders; it does not spin. |
| `§250 AR-3` | Sign in as a member with **no project grants**, then make `GET /projects` fail (dev tools → block the request, or `tcmsFaults.only('/projects')` then `tcmsFaults.status(503, 30)`) and search anything | **"Nothing came back for that search"** — the third emptiness. Not *"No case matches"*, which is what shipped before §250 whenever the disambiguating read failed, and which tells a grantless member the product is empty. This is the one row needing a fault armed after load. |
| `§250 AR-4` | On `/search`, narrow by Status, Automation and Priority, then copy the URL into a new tab | The three filters apply, the table's own columns agree with them, and the URL carries every selected value. Filter parity: before §250 the screen rendered three columns nothing could filter on. |
| `§250 AR-5` | With a screen reader running (VoiceOver ⌘F5), type into the filter on the case list, the cross-project search, the plan list, the run list and the audit trail | Each one **announces its result count** when the list settles — politely, without stealing focus, and **not** on first load. Before §250 every filtered list in the app refilled in silence; errors were announced and results were not. Confirm it announces even when the count is unchanged between two different filters. |
| `§250 AR-6` | Open the reports screen | The panel reads **"My saved reports"** with no **Owner** column, and the empty copy says saved reports are yours alone. The endpoint returns the caller's rows only; the old title made a colleague's invisible report look like data loss. |
| `§250 AR-7` | Compare, in **both** light and dark themes: a user's ACTIVE/inactive dot (Teams), an overdue card and a CRITICAL/HIGH priority stripe (Board), a stale-figures caption and a trend delta (Plan statistics), a live API token, the automation share bar (Dashboard), and requirement coverage (Docs) | All read as *semantic* colour — success / warning / danger — and none of them borrows the **test-result** palette (`--color-passed` / `--color-failed` / `--color-blocked`), which now means a test outcome and nothing else. Check the run screens still look exactly as they did: those legitimately keep the result palette and must not have moved. |
| `§250 AR-8` | Delete a plan, a case, a suite and a user; each time reopen the same dialog after a **refused** attempt | The previous refusal is gone every time. The ten dialogs' reset effects were rewritten in §250 to depend on the mutation's bound `reset` rather than suppress a lint rule that never ran; this row is what proves the rewrite kept the behaviour. |
| `§250 AR-9` | Rename a case, then open `/search` and search for its **old** title, then its new one | The stale hit is gone. `keys.caseSearch` sits outside `['projects', …]` on purpose and consequently had **no** invalidation at all before §250 — a renamed, retired or deleted case stayed in a held result indefinitely. |

---

## §251 — the downloads page and the two install paths

**Not a lane in the thirteen, and deliberately outside them.** Every lane above needs one of the six
accounts and a fixture the pass authors; these rows need **no session at all** — that is the point
of them, because npm cannot present a credential. They also cannot be driven until a release has
been dispatched: `/downloads` does not exist on any box until the next deploy from master publishes
it. Run them **first thing after that deploy**, before the lanes, because a failure here is a failure
for every user of the instance and takes a redeploy to fix.

Rows 4 and 5 are shell, not browser. They are here rather than in the mutation queue because they
verify the same artifact as rows 1–3 and splitting them across two files is how one half gets run.

| Row | Drive | Expect |
|---|---|---|
| `§251 D-1` | Open `https://<host>/downloads/` in both themes | The page renders, **filled in with the host you are actually on** — every `https://your-tcms-host` is replaced by `location.origin` at render. A page still showing the placeholder means the inline script did not run and every command on it is wrong. Read the version line: it is the release now deployed. |
| `§251 D-2` | `curl -sI https://<host>/downloads/tcms-cli-latest.tgz`, then `curl -sI https://<host>/downloads/nonexistent.tgz` | The first is **200 with `application/gzip`** and `Cache-Control: no-cache`. The second is a **404** — *not* a 200 with `text/html`, which is the SPA catch-all answering and is what makes npm report an invalid gzip header on a file that is really a web page. **Check the content-type before believing either.** |
| `§251 D-3` | `curl -s https://<host>/downloads/SHA256SUMS`, then `shasum -a 256` the tarball you downloaded | The digests match, and the file lists four tarballs: `-latest` and the version-pinned copy of each tool. |
| `§251 D-4` | On a machine with **no checkout of this repository**: `npm install -g https://<host>/downloads/tcms-cli-latest.tgz`, then `TCMS_URL=… TCMS_TOKEN=… tcms whoami` | The install completes, and `tcms whoami` names the token's user and what it can reach. **Silence with exit 0 is the §251 defect returning** — that is what the entry guard did before `isProcessEntryPoint`, and it looks exactly like a successful command that had nothing to say. Then repeat the install with `--offline`: it must still succeed, because the tarball vendors its whole closure. |
| `§251 D-5` | Install `tcms-mcp-latest.tgz` the same way and name `tcms-mcp` in a real MCP host's config with `TCMS_URL`/`TCMS_TOKEN`; separately, point a host at `https://<host>/mcp` with the token as the bearer | **Both shapes list tools, and the same ones** — the tool list is built per session from the caller's credential (§140), so a difference between the two is a session-construction defect and not a transport one. The hosted half needs `COMPOSE_PROFILES=mcp` on the box; if the container is not running, **record that rather than filing a failure** — `docs/specs/client-distribution.md` has the one-line fix and it is an operator decision, not a defect. |
