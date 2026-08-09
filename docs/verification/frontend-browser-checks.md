# Deferred browser checks — the queue

**Policy §65 (2026-08-02).** A live browser pass is deferred and batched: a slice records what must
be driven, and this file is executed later as one session. Nothing here is optional — each row is a
defect nobody has looked for yet, and **a slice in an unrun lane is *built and unverified***.

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
| Conditions | Three in the library; one attached to a **suite** so `M2-22`'s inherited rows exist; one attached to several cases for `L4-19` |
| Plans | `TP-1` (sections referencing suites, tags and named cases — `M3-52`…`M3-58` all read it), `TP-2`…`TP-5` down the ladder |
| Runs | `TR-1` against `TP-1`; further runs as `A3` needs them |
| Teams | **Platform** (created first and alone, for `L4-1`), then **Payments Squad** (`lead`, `engineer`) and **Checkout Squad** (`viewer`, `newbie`) |
| Doc pages, OLB | **`DOC-1` "Payments"**, authored in `/docs` before `M3-16` needs a page to link. §115 needs it to have a **specific shape**, so build it deliberately: one paragraph of intro, a **heading "Refunds"**, then **four list bullets** under it, then a **heading "Disputes"** with nothing under it. Link cases to the bullets from the documentation editor (`M3-19`'s affordance): bullet 1 → a case that **passed**, bullet 2 → **two** cases, one that passed and one that **failed**, bullet 3 → a case **never executed**, bullet 4 → **nothing at all**. Then link a case to the intro **paragraph**. Also author **`DOC-2` "Charter"** as prose alone — one paragraph, no bullets, no links. |

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
| A0-13 | Close the dialog and read the row | Name, then the **prefix as its own column** in the mono font — check it is the **first 12 characters of what you pasted**. Role `Automation Service`, *Where it works* the **wide sentence** and not a blank cell, Owner your **display name** and not a uuid, Expires *"Does not expire"*, Status **Live**. **There is no "Last used" column** — the contract says the field is always null today, and shipping the column would leave it empty on every row of every deployment for ever. | §118-6 |
| A0-14 | Issue a second token called `Nightly regression`; then a third with an expiry **in the past**; then a fourth pinned to `MOB` with an expiry **tomorrow at 14:00 local** | First two are **400s landing on the field that caused them** — the name error quotes the existing token and tells you to revoke it first; the expiry error says the token would be unusable the moment it was issued. Neither reaches the reveal, and what was typed is still there. The fourth succeeds: its row names **MOB** and its Expires cell is a **UTC-labelled** time that is tomorrow 14:00 **converted from your zone** — a screen showing `14:00 UTC` for a `+03:00` browser means the local wall clock was posted verbatim and the token dies three hours early. | §118-7 |
| A0-15 | **Revoke** `Nightly regression`, reading the dialog before confirming | It names the token **and** its prefix — two tokens can share a name a year apart, and the prefix is what matches a CI configuration. It says the credential stops working immediately, that it cannot be undone, and that **the row stays**. Confirm: the list is the **same length**, the row is muted and reads **Revoked**, and its Revoke is now **disabled** with *"This token was already revoked."* Then re-run `A0-12` with the same secret → **401**. | §118-8 |
| A0-16 | The row whose expiry has passed (`A0-14`'s third, if it was accepted — otherwise wait out the fourth, or `UPDATE api_token SET expires_at = now() - interval '1 day'` on it) | Status **Expired**, in the muted lifecycle colour and **not red** — a credential reaching its stated end date is the system working. **Revoke is enabled on it**, unlike the revoked row: closing a lapsed credential deliberately is a real act, and a screen that collapsed the two dead states into one would take it away. | §118-9 |

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
| A1-2 | Attach a condition to the **suite** `Cart` | This is the fixture `M2-22` reads and nothing else in the pass produces it. If a suite cannot carry a condition through the UI, that is the finding. | §95 |
| M2-22 | A case filed in `Cart`: open the editor, save untouched, reopen | Inherited rows render **named with their folder**, with no remove/reorder/drag, and are **still inherited** afterwards. **The slice's most dangerous defect**: an editor that seeded them into the form attaches the folder's conditions to every case anybody edits, permanently and invisibly. | §95-65 |
| M2-23 | Detach the **last** condition and save | Gone after reload. Absent ≠ empty: a client omitting the empty list reports success and changes nothing. | §95-66 |
| M2-24 | Reorder conditions with ▲▼, then drag, then keyboard | All three paths produce the same saved order. | §95-67 |
| L4-19 | Edit a condition attached to several cases: read the impact line, confirm, open a **different** case that attaches it | The line gives **two separate figures** — cases attaching directly, suites attaching — never one blended number. The other case shows the **new** text: the attachment is a reference and the edit propagates with no prompt. **Moved here from `L4`**; it needs the multi-attachment fixture this lane builds. | §95-68 |
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
| M3-43 | Add a section, type one title under it and one under *Not in a section*, Save; then check the payload and the case list | **Two `POST /cases` and one `PATCH`**, in that order. The `PATCH` carries the new ids in the group each was authored under — the sectioned one under `sections[].caseIds`, the other under the top-level `caseIds`. Both cases are in the repository **filed in no suite** (D2). | §97p6-145 |
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
