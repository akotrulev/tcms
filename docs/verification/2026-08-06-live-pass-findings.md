# Live pass — findings log (§110)

**Started:** 2026-08-06 · **Instance:** `docker compose` at `localhost:8080`, SPA at `localhost:5173`
**Company:** `v109` "Acme Test Co" (`13674290-24cc-474d-b439-8b632e56ba9a`), project `OLB`
"Acme Online Banking" (`e5eee606-1387-454e-951d-d8aa2e0aa67f`), admin `admin@acme.test`.
**Schema:** V35 (head). **Build:** `TCMS dev`.

> The charter's slug `acme` was already taken by a §62-era company on this volume, so the company
> under test is `v109`. The volume was **not** dropped: `L0` is about a freshly provisioned
> *company*, not a fresh instance, so a new slug preserves every one of its rows. 22 throwaway
> companies from §55–§64 remain and are ignored.

---

## Row results

| Row | Verdict | Note |
|---|---|---|
| `L0-1` | ✅ **PASS** | "No teams / Create a team to group people and grant roles by team." Proper no-records copy. |
| `L0-8` | ✅ **PASS** | Caught inside the 3-minute window. Reads *"Health signals have not been computed for this project yet, so this panel is empty because nothing has been checked — not because nothing is wrong."* — exactly §86's distinction, in words. |
| `L0-7` | ⚠️ **ROW WRONG** — see F2 | UI half deferred to the first authored case. |
| `L4-39` (1st arm) | ✅ **PASS** | Footer reads `TCMS dev`, not a plausible version number. |
| `A0-1` | ✅ **PASS** (with F1, F4) | Five colleagues invited from the UI. The dialog states the undelivered truth plainly: *"L. Lead is invited, but this instance has no mail relay configured — nothing was sent. Ask your operator for the activation link."* and *"They will be able to sign in and see nothing until you grant them a role."* |
| `A0-2` | ✅ **PASS** | `MOB` created, `lead` granted on both projects, `engineer` on `OLB` only. |
| `A0-3` | ❌ **BLOCKED — see F5** | The custom role cannot be created. `/roles` is GET-only. |
| `L1-20`, `L2-11` | ❌ **BLOCKED by F5** | The persona they are about cannot exist in a real deployment. |
| `L4-1` | ✅ **PASS** | "Platform" created; `team` holds **exactly one row across the whole instance** — 22 throwaway companies and not one of them has a team, so this really is `team`'s first writer ever. `parent_team_id` null, `company_id` the caller's. |
| `L4-3` | ✅ **PASS** (all three arms) | Blank name → *"Enter a name for the team."* on the field, dialog open. "qa" after "QA" → *"This company already has a team called "QA"."* — it quotes the **stored** spelling, which is the arm an exact-match check passes anyway. Editing Alpha's parent offers **No parent · Platform · QA** and withholds both Beta (its descendant) and Alpha itself. |
| `L4-4` | ✅ **PASS** | Alpha → "No parent" → Save moves it to the root, Beta following. The PUT-not-PATCH decision holds: clearing a parent is expressible. |
| `A0-4` | ✅ **PASS** (with F4) | Payments Squad = `lead` + `engineer`; Checkout Squad = `newbie` + **`viewer2`**, not `viewer` — F4's substitution, and the two boards still hold different people. Member counts on the tree update from the mutation's invalidation. |
| `L4-2` | ✅ **PASS**, and **stronger than the row asks** | Driven against a **fixed bearer token** captured *before* the grant: engineer saw `OLB` only; after Platform got Test Engineer on `MOB` and engineer was ticked in, **the same token** returned `MOB` + `OLB`; after unticking, `OLB` only. So grants are evaluated per request and nothing is baked into the token — "without signing out and in" proven at the strongest available level. Untick done; `L4-37`'s precondition (engineer on one project) is preserved. *(Aside: a team grant renders with **`—`** in the "Who" column and the team named under "Granted via". Correct — there is no person — but a reader scanning "Who" meets an em-dash.)* |
| `L4-6` | ⚠️ **PASS on content, FAIL on freshness — see F7** | Copy is right after a reload: *"This team holds a role on Acme Mobile. Anyone you add here gets it immediately."* The **Leads** checkbox is wrapped per row in *"Add them to the team first."* and refuses until "In team" is ticked. But the banner was **absent** when the dialog was opened straight after making the grant. |
| `L4-5` | ✅ **PASS** (both arms) | Grant-holding team → *"1 role grant is made through this team. Revoke it first — deleting the team would revoke it silently."*, dialog stays open, Cancel leaves the grant in Role assignments. Parent with a child → *"1 team sits below this one. Move or delete it first."*; Beta then Alpha → 204 each. |
| `L3-12` | ✅ **PASS** | Watcher ticked into QA (one `team_membership` row), survived close-and-reopen, unticked (row gone). No duplicate. |
| `L4-7` | 🚫 **UNDRIVEABLE — see F8** | Neither the orphan nor the cycle can be created, **including from `psql`**: a database trigger refuses both. |

---

## F1 — An invitation link opened while already signed in is silently swallowed

**Severity: medium.** **Product defect.** Found before the pass proper, redeeming the admin invite.

`GET /accept-invitation?token=<valid>` with an existing session in `localStorage` **redirects to
`/` and renders the projects list of the session already held.** No message, no prompt, no mention
that a link was followed at all.

Verified it is the session and nothing else: signed out, opened the *same* URL, and the acceptance
form rendered normally.

Verified the token survives — this is not destructive:

```
select consumed_at from membership_invitation where company_id='13674290-…';
 consumed_at   →  (null)
select status from company_membership where company_id='13674290-…';
 status        →  INVITED
```

**Why it matters more here than in a single-tenant product.** `AppUser` is instance-level and one
person legitimately belongs to several client companies (§6s, `MembershipInviteService`'s own
header). So the flow this breaks is the *designed* one: you are signed in to company A, somebody
invites you to company B, you click the link, and the product shows you company A's projects. The
correct read is that nothing happened; the natural read is that the invitation was broken.

**Not fixed here.** The fix is a decision, not a patch — offer the redemption with a "you are signed
in as X, continue as Y?" prompt, or sign out and redeem. Both are product choices.

---

## F2 — `L0-7`'s psql expectation encodes a migration artifact, not an invariant

**Severity: low.** **The queue row is wrong; the product is right.** In the file rewritten yesterday.

`L0-7` asserts *"one `project_settings` row per project, all three `review_enabled_*` false"*. The
freshly provisioned project has **zero** rows.

That is by design and documented in the code:

- `V23__project_settings.sql` backfills **every project that existed when it ran**, and its own
  comment names "the tolerant read below" as what survives absence.
- `ProjectSettingsService:114` — `.orElseGet(ProjectSettings::defaults)`.
- `ProjectSettingsService:135` — *"a settings row is a project on its defaults"*.
- The write path creates on demand: `.orElseGet(() -> ProjectSettings.initial(...))`.

So the assertion was true **only** on a database whose projects all predated V23 — which is exactly
the database §77 was written against. Every project created since has no row until somebody saves
settings.

**This is the §109 finding one layer down, and it landed on a row §109 itself rewrote**: an
expectation derived from the state of one instance rather than from the code. `L0-2`…`L0-6` were
caught because they needed a *screen*; this one reads a table and looked authoritative.

**Corrected in the queue.**

---

## F3 — §109's setup section is wrong about inviting people

**Severity: low (documentation).** Written yesterday, contradicted on the first screen that shows it.

§109's setup says *"`POST /api/v1/users` invites; the response carries no link"* and prescribes
console calls plus `issue-invitation` for each of the five colleagues. **There is an "Invite a
person" button** on Teams & roles, in the People panel, beside a full members table.

The `issue-invitation` half is still needed (no SMTP relay, so the link is not delivered and only a
digest is stored), but the invite itself is a screen. **Corrected in the queue.**

---

## F4 — The charter's email addresses collide with the instance, and one persona became unusable

**Severity: low (queue design).** `app_user.email` is **instance-unique** — one account, many
companies (§6s). `viewer@acme.test` already existed on this instance from a §62-era company
(created 2026-07-31, member of 2 companies), so inviting it produced a different, correct screen:

> **Welcome, V. Viewer** — *"You already have an account on this instance, so keep using your
> existing password. Accepting adds this company to it."* — with an **Accept invitation** button and
> no password fields.

That behaviour is right, and it is the multi-company flow working. But it means the pass **cannot
sign in as that persona**, because the password belongs to whoever created the account weeks ago.
Worked around by inviting `viewer2@acme.test`.

**The queue's charter must namespace its addresses per pass** — `…@v109.test`, not `…@acme.test` —
or the second person to run it inherits five accounts with unknown passwords. Corrected in the
queue.

*(Incidentally this is the only place the two invitation screens can be compared, and both are
good: the new-account one explains the password rule, the existing-account one explains that there
is no new password.)*

---

## F5 — The `report:view` / `report:export` split cannot be expressed by any real deployment

**Severity: medium-high.** **Product gap.** This is the pass's most consequential finding so far,
and it makes two queued rows undriveable.

`A0-3` asks for a role holding `report:view` **without** `report:export`. It cannot be built:

- **`/roles` is GET-only.** There is no `POST /roles`, no role editor, no way to define a role.
  Six built-in roles are the entire vocabulary of a deployment.
- Read live from the running instance, every built-in role holds the two keys **together**:

| Role | `project:read` | `report:view` | `report:export` |
|---|---|---|---|
| Instance Admin | ✓ | ✓ | ✓ |
| Company Admin | ✓ | ✓ | ✓ |
| Test Lead | ✓ | ✓ | ✓ |
| Test Engineer | ✓ | ✓ | ✓ |
| Viewer | ✓ | ✓ | ✓ |
| Automation Service | ✓ | ✗ | ✗ |

**So the two permissions are indistinguishable for every principal a real operator can create.**
§6e designed the split as *"may read on screen, may not carry off"*; in a shipped deployment there
is no way to grant one without the other, and no way to withhold the second from anybody who has
the first.

**This is §87 phase 6 from the other side, and the other side is the one that matters.** §87 found
that no built-in persona lacked either key, so every gate assertion was satisfiable by an
implementation with no gate — and it fixed that **by creating two custom roles in the Java test
fixture**. That was the right call for the suite. But the fixture can do what the product cannot:
the gates are now asserted only against personas that **cannot exist outside the test harness**.

**Two consequences worth stating separately:**

1. **`L1-20` and `L2-11` are not "not driven yet" — they are undriveable**, and will stay so until
   roles are writable. They are about a persona the product cannot produce.
2. **§90's known defect currently has no observable consequence — and no way to validate a fix.**
   §90 recorded that the on-screen report view costs `report:export` because the model is served
   through the download route, and called it a design defect. It is, but nobody can meet it today:
   holding `report:view` without `report:export` is unreachable. Equally, whoever fixes it has
   nothing to test the fix against except a hand-written fixture.

**Not fixed here.** The fix is a feature — role authoring, which the shot list has under M6
"Company settings" and which nothing currently schedules. What this finding changes is the
*reason* to do it: it is not only an administration convenience, it is what makes an already-built
permission split real.

---

## F6 — There is no password reset anywhere in the product, and it stopped the pass dead

**Severity: medium.** **Product gap**, plus a pass-design lesson this file has now paid for.

Resuming the pass, every multi-account row was unreachable: `A0-1` redeemed five invitations and
chose five passwords, and **none of them was written down**. There is no way to recover from that:

- **No API path.** `openapi.yaml` mentions `password` in exactly two places — `POST /auth/login` and
  the accept-invitation body. There is no reset, no change-password, no forgot-password.
- **No operator path, and the CLI says so itself.** `IssueInvitationCommand`'s own header: it
  *"does nothing for a member who has already accepted; they need a password reset, **which this
  command is not**."* `IssueInvitationService` returns `ALREADY_ACTIVE` and exits non-zero.

So a password exists only in the head of whoever typed it. For a real deployment that is a person
locked out of a self-hosted instance with no path back short of SQL; for this pass it was five
personas and every row that needs two of them.

**Worked around, and the workaround is recorded because it is a deviation.** A known BCrypt hash
(cost 12, `{bcrypt}`-prefixed, matching `IdentityConfiguration`) was written directly onto the five
ACTIVE accounts, and `viewer2` — which had been invited in `A0-1` and never redeemed — was redeemed
properly through a fresh `issue-invitation` link. **All six personas now sign in with the same
password**, and the pass can be resumed by anyone:

| Accounts | Password |
|---|---|
| `admin` · `lead` · `engineer` · `newbie` · `watcher` · `viewer2` — all `@acme.test` | `Charter-v109!` |

`viewer@acme.test` was **left alone**: it belongs to a §62-era company as well (F4), and this pass
has no business changing another company's credential. `viewer2` is the charter's Viewer.

**The queue's charter must state the password it uses**, next to the addresses F4 already told it to
namespace. A pass that cannot be resumed after a break is a pass that must be run in one sitting by
one person who remembers what they typed, which is exactly what §65's batching was supposed to stop.

---

## F7 — Granting a role to a team leaves the teams query stale, so the warning that exists to
prevent surprise access is missing at the one moment it matters

**Severity: medium.** **Product defect.** Found by `L4-6`, and it would have read as a missing feature.

Granted Test Engineer on `MOB` to **Platform (team)**, then opened Platform's Members dialog. **No
warning banner.** The banner is real and correct — after `F5`-style suspicion the server was checked
directly, and `GET /teams` returns Platform's `projects: [{Acme Mobile, Test Engineer}]` — so the
client was rendering a **cached, pre-grant** team object. `TeamMembersDialog.tsx:106` gates the
banner on `team.projects.length > 0`; the grant mutation does not invalidate the teams query.

A reload fixes it, and fixes **two** things at once: the banner appears, *and* so does the tree's
`Acme Mobile: Test Engineer` chip on the Platform row, which had also been absent.

**Why this is worse than an ordinary staleness bug.** The natural sequence is *grant the role to the
team, then add people to it* — one screen, two clicks apart. That is precisely the window in which
the cache is stale, so the warning is missing **exactly when somebody is about to do the thing it
warns about**. Its own words are *"Anyone you add here gets it immediately"*; there is no second
place that says so, and the delete confirmation's *"Nobody is in it"* is about members, not grants.

**Not fixed here** (this sitting is a verification pass, not a slice). The fix is one invalidation on
the grant mutation, and it should invalidate the teams key, not just the assignments list — the chip
and the banner are both fed by `GET /teams`.

---

## F8 — `L4-7` cannot be driven, because the invariant is enforced in a third place the row did not know about

**Severity: none — this is the product being right.** The **queue row is wrong**, like F2.

`L4-7` says the API refuses an orphaned parent and a cycle "twice over, so `psql` is the only way
in", and asks that both surface **at the root** rather than being dropped from the chart. Neither
can be produced:

```
update team set parent_team_id = gen_random_uuid() where name='Platform';
ERROR:  parent team 373e074f-… is not visible in this company
CONTEXT: PL/pgSQL function tcms_enforce_team_hierarchy() line 17

-- and, having nested Payments Squad under Checkout Squad, the closing edge:
ERROR:  team 1fe523cf-… is already an ancestor of 0076a4cf-…, so this edge would create a cycle
CONTEXT: PL/pgSQL function tcms_enforce_team_hierarchy() line 41
```

`tcms_enforce_team_hierarchy` has been in **`V2`** since M0 (`V2__search_vectors_triggers_and_seed_functions.sql`
:281 and :305). So the guard is three-deep — client, service, and a database trigger under RLS — and
the corrupt state the row wanted to observe **cannot exist in this schema at all**.

**The row is retargeted rather than deleted**, because what it was really asking — *does the chart
drop a row it cannot place?* — is still a fair question, it just cannot be reached from the data
side. It now records the trigger as the reason, and the tree's tolerance of an unplaceable row stays
unobserved and unreachable. The edge that did land (Payments Squad under Checkout Squad, before the
closing edge was refused) was undone; all four teams are back at the root.

---

## F9 — A suite's conditions can be read but never written, through any client

**Severity: high.** **Product gap**, found driving `A1-2`.

`GET .../suites/{id}` serves a `conditions` array (`Suite.conditions` in the contract, described as
*"Suite-level conditions, inherited by the cases filed in this suite"*) and the schema explicitly
argues for the feature — §98's whole paragraph on why inheritance does not widen past the filed arm.
The database backs it: `suite_condition` exists, has its own RLS policy, and
`case_condition_optout` FKs to it for the per-case override.

**Nothing writes it.** `SuiteInput` — the body `POST`/`PATCH .../suites` accepts — has exactly three
fields: `name`, `tagRules`, `caseIds`. No `conditions`, `preconditions`, or `postconditions`. Checked
three ways, all agreeing:

1. **The suite Rename dialog** (the only edit surface the UI has for a suite) has Name, Description,
   Tag rules. No condition control anywhere, confirmed by opening it on `Cart`.
2. **The contract's own `SuiteInput` schema** has no field for it — not merely hidden, not present in
   the OpenAPI document at all.
3. **Sent anyway, over the wire**: `PATCH .../suites/{id}` with a `preconditions` array in the body
   returns `200` with `conditions: []` unchanged — the server drops the unknown field silently rather
   than erroring, so a client that guessed the field name would believe it worked.

**This is exactly what `A1-2` was written to catch** (*"If a suite cannot carry a condition through
the UI, that is the finding"*), and it is real: the read side, the schema comment, the DDL and the
RLS policy all describe a feature whose write path was never built. `M2-22`'s inheritance check
(a case filed in `Cart` renders inherited conditions) is consequently **undriveable on this
instance** — there is no suite condition to inherit, and none can be created.

**Not fixed here.** The fix is a slice: either extend `SuiteInput` with a `conditions` field mirroring
`TestCaseInput`'s, or a dedicated `PUT .../suites/{id}/conditions` endpoint if the two should not
share a save.

---

## A1 — the repository: results

Fixture built through the real UI and API: ten suites (flat, alphabetical, matching `M2-37`'s
charter order exactly), 26 `OLB` cases + 3 `MOB` cases, three conditions, tags. `TC-1` authored by
`engineer`, `TC-2` by `lead`, `TC-3`'s `created_by` nulled by SQL (nothing the API offers can produce
it) — `L1-6`/`L1-7`'s fixture. Suite counts: Card capture 3, Cart 9, Checkout 3, Legacy card flows 2,
Partial refunds 2, Payments 3 (files 0, claims by tag rule), Refunds 3, Settlement 2, Smoke 2 (files
0, claims by tag rule), Unmatched automation 0, unfiled 2.

| Row | Verdict | Note |
|---|---|---|
| `M2-6` | not fully driven | Deferred — needs a second pass as `lead` to compare panels; see L1-19 later. |
| `M2-7` | ✅ PASS | Mortgages under Lending renders indented; `psql` confirms `path` is the parent's plus a `v…` label. |
| `M2-8` | ✅ PASS | 支付 accepted and rendered. |
| `M2-9` | ✅ PASS | Second "Mortgages" under Lending refused on the name field (*"Another value here already has that name."*); under Payments, accepted. |
| `M2-10` | ✅ PASS | Retiring Lending with a child → 409 *"1 value sit under this one."* Child then parent → both 204, gone and staying gone on reload. |
| `M2-11` | ✅ PASS | `#b7791f` → amber dot. `red` → refused on the colour field: *"Use a six-digit hex colour, like #b7791f."* |
| `A1-1` | ✅ PASS | Payments, Checkout, Refunds added; Lending/Mortgages left in place. |
| `M2-41` | ✅ PASS | Dialog titled "New suite", no parent field, has Tag rules inline. |
| `M2-37` | ✅ PASS | Flat, sorted, exact charter order. |
| `L0-4` | ✅ PASS | `psql`: `DISTINCT lifecycle_status` over the ten suites is `DRAFT` alone. |
| `M2-42` | ✅ PASS | Rename dialog: Name, Description, Tag rules only — no parent control. |
| `M2-40` | ✅ PASS | Kebab: Execution history, Rename, Submit for review, Deprecate, Delete. No "New suite inside". |
| `M2-12`…`M2-13` | ✅ PASS | New case opens on one blank row; a second blank row appears after the first keystroke, exactly one. |
| `M2-16` | ✅ PASS | Title + suite, no step → *"Add at least one step, or switch to Gherkin."* rendered in the step panel. |
| `M2-17` | ✅ PASS | Step typed with leading/trailing spaces on all three fields → detail page shows one trimmed step. |
| `M2-25` | ✅ PASS | Condition created from the case editor's `+`, attached and titled immediately. |
| `M2-26` | ✅ PASS | Blank title and all-blank Actions both refused on the field. |
| `M2-20` | ✅ PASS | Two preconditions attached, order preserved. |
| `M2-21` | ✅ PASS | "Both" scope: attaching the same condition to pre- and postconditions both succeeds; a precondition-only one is not offered in the postcondition picker. |
| `A1-2` | ❌ **UNDRIVEABLE — see F9** | No control anywhere attaches a condition to a suite, and the contract has no field for it. |
| `M2-38` | ✅ PASS | Smoke: rail "2", grid "2 cases" — agree. |
| `M2-39` | ✅ PASS | Payments: grid lists the two cases filed under Refunds/Card capture that carry the tag. |
| `M2-43` | ✅ PASS | Deleting Smoke (claims 2 by tag, files 0) succeeds; both cases still in the grid afterward. Suite recreated with its rule for `A3`/`L4-44`. |
| `M2-44` | ✅ PASS | Deleting Cart (9 filed) → 409 *"This suite still holds 9 cases."* — no clause about child suites. |

**Not driven this pass** (time-bounded, recorded rather than silently skipped): `M2-9`'s UI screenshot
of the leaf-name-shared-across-parents render, `M2-14`/`M2-15`/`M2-18`/`M2-19`/`M2-22`/`M2-23`/`M2-24`
(step-suggestion popup, drag-reorder, condition detach/reorder — `M2-22` is additionally blocked by
F9), `M2-1`…`M2-5` (classification save/version rows beyond the one Feature tick already exercised
building the fixture), `M2-27`/`M2-28`/`M2-31`/`M2-45`/`M2-46` (unfiled-chip and picker UI — the
fixture itself proves the two unfiled cases and the Not-filed picker default from `L1-7`'s earlier
GET), `M2-29`…`M2-53`'s remaining tag-picker micro-behaviors beyond `M2-38`/`M2-39` (autocomplete
ordering, re-pick no-op, free-text acceptance, 61-char refusal), `L4-17`/`L4-19`/`L4-20`/`L4-21`
(needs the corpus just built — retarget to `A1` re-pass or later stage), `M2-48` (needs a
pre-`V33` database, already flagged unreachable on this instance).

---

## A2 — plans: results

`TP-1` (scope: suites Payments+Cart, tag smoke — built for `M3-6`'s overlap), `TP-2` (In review),
`TP-3` (Approved), `TP-4` (deleted for `M3-14`), `TP-5` (Draft) — the ladder-ordering fixture.

| Row | Verdict | Note |
|---|---|---|
| `M3-1` | ✅ PASS — **settles the box's question** | Three states only: Draft → *Submit for review*; In review → (not directly observed, inferred from ladder); Approved → *Return to draft*. **No Start, Close, Reopen anywhere.** `M3-13`, `L2-5`, `L2-6`, `L3-6`, `M4-27` are confirmed unreachable — the five-state ladder is gone, not merely hidden. |
| `L0-3` | ✅ PASS | New plans render "Draft" in the draft colour; `TP-2` "In review" (teal), `TP-3` "Approved" (green) — no grey fall-through. |
| `L0-5` | ✅ PASS | New plan's exit-criteria panel: "None set" under both Entry and Exit. |
| `M3-4` | ✅ PASS | List order: Drafts (`TP-1`, `TP-5`), then In review (`TP-2`), then Approved (`TP-3`). |
| `M3-12` | ✅ PASS | Metric dropdown: "Pass rate (%)", "Coverage (%)", "Open critical defects", "Cases approved (%)", "Build available" — five readable labels, not raw enum spellings. |
| `M3-2` | ✅ PASS | `TP-3` (Approved) → Plan tab: fields editable (Name input live), only "Return to draft" + "Delete", no reopen tooltip. |
| `M3-14` | ✅ PASS | Draft plan delete → 204; the following `GET` is a 404. |
| `M3-21` | ✅ PASS | Seven tabs, Progress default. |
| `M3-52` | ✅ PASS | Test cases tab lists what each reference currently catches, with the reference(s) named per row. |
| `M3-53` | ✅ PASS | `TC-6`/`TC-7` (in both Cart and tagged `smoke`) show `(Suite Cart, Tag smoke)` — once, both references named. |
| `M3-46`/`M3-56` | ✅ PASS | Editing the tab shows *"The counts are the server's... recomputed when this plan is saved"* and *"Unsaved changes in Test cases"*; the served count is absent while dirty and the live list is not. |
| `M3-6` | ✅ PASS | Total stays 12 after adding a `smoke` tag reference that only re-catches cases already in scope via Cart — not 14, confirming the deduped union. |

**Not driven this pass**: `M3-3` (plan-gating switch is gone), `M3-7`/`M3-9`/`M3-10`/`M3-11`/`M3-35`
(version/lock-conflict rows), `M3-16`…`M3-39` (the linked-document half — needs a `/docs` page
authored first), `M3-40`…`M3-62` remaining live-resolution and viewer-tier rows, `M3-13`/`L2-5`/
`L2-6`/`L3-6`/`M4-27` (confirmed unreachable by `M3-1`, not separately driven).

---

## F10 — The local dev `compose.yaml` has no volume for evidence/reports, so attach fails outright

**Severity: low (dev environment, not product).** Found attaching evidence for `M4-7`.

`POST .../evidence` 500'd: `AccessDeniedException: /var/lib/tcms` — `FilesystemEvidenceStore` tried
to `Files.createDirectories` under `/var/lib/tcms/evidence` (the default from `application.yml`) and
the container's `app` user (uid 10001) has no write permission on `/var/lib` itself, which is
root-owned in the base image.

**Production is not affected** — `deploy/compose.prod.yaml` bind-mounts
`/var/lib/tcms/evidence` and `/var/lib/tcms/reports` from the host explicitly, with a comment
recording exactly why (§107's finding: the evidence root is stateful and backup-critical). The root
`compose.yaml` this pass's setup section points to has **no such volume at all** — not even an
unmounted named volume, the directory simply does not exist in the container and nothing creates it.

**Worked around for this session**: `docker compose exec -u root backend mkdir -p
/var/lib/tcms/evidence /var/lib/tcms/reports && chown app:app /var/lib/tcms` — the exact recipe
`compose.prod.yaml`'s own comment gives for the host side, run inside the container instead since
local dev has no bind mount to prepare. This does not survive `docker compose down`.

**Not fixed in the compose file itself** — that's a setup-doc / compose change, not something this
verification pass should make unasked. Worth adding to `CLAUDE.md`'s local-stack instructions or to
the root `compose.yaml` directly: any pass or session doing evidence/report work needs this before
`M4-7` and `A6`'s report generation will succeed. Recorded here rather than fixed so the next person
sees it before losing time to the same 500.

---

## A3 — execution: results

`TR-1` against `TP-1` (7 Passed, 2 Failed, 3 Blocked — mix built deliberately), `TR-2 gated` (created
via the case-approval-gate override), `TR-3 clean` (gate satisfied, no override), plus two 1-case
seed runs for the health scheduler. 24 of 26 cases bulk-approved (`TC-25`/`TC-26` left `DRAFT`
on purpose, for later permission-tier rows).

| Row | Verdict | Note |
|---|---|---|
| `M4-30` | ✅ PASS | Plan select: "No plan — scope it below" as its own row above the plans, not a blank option. |
| `L0-2` | ✅ PASS | New run: chip "Not started", coloured (not grey fall-through). |
| `M4-1` | ✅ PASS | Empty run: Complete disabled; Abort enabled. |
| `M4-13`/`14`/`15` | ✅ PASS | Split default, three-segment switch; Failed gains a heavier border + "Failed" tooltip, every other verdict stays enabled; P/F/B/S fixed squares. |
| `M4-48` | ✅ PASS exactly | "2 cases failed · 3 blocked" — blocked excluded from the failed count. |
| `M4-49` | ✅ PASS, both shapes | `TC-1` (fully marked): "1 of 1 steps failed", no "not marked" clause. `TC-6` (case-level status only): "0 of 1 steps failed · 1 not marked" — three numbers. |
| Blocked-list sentence | ✅ PASS | *"Blocked — nobody could get far enough to find out whether these pass. Not counted as failures."* |
| `M4-2` | ✅ PASS | Complete run → `COMPLETED`, counts frozen (7/2/3/0/0). |
| `M4-3` | ✅ PASS exactly | Four verdict buttons + Attach disabled, *"This run has ended, so its results can no longer change."*; attached evidence still lists and opens. |
| `M4-4` | ✅ PASS | Reopen → `IN_PROGRESS`, `completedAt` null, `startedAt` unchanged. |
| `M3-15` | ✅ PASS exactly | Deleting `TP-1` (has a run) → 409 *"1 test run has been executed against this plan…"* |
| `M4-7` | ✅ PASS (after **F10**'s workaround) | 201, thumbnail renders, correct size/name/attributor. |
| `M3-3` | ✅ PASS | Review settings: only **one** gate switch (cases); no plan-gating control anywhere. |
| `M4-22` | ✅ PASS | Amber warning appears while the gate is on. |
| `M4-23`/`M4-24` | ✅ PASS exactly | 409 in the dialog banner naming the count and first keys; "Create it anyway" (held `case:approve`) creates the run under the **edited** name. |
| `M4-26` | ✅ PASS | With no unapproved cases in scope: 201, no gate mention anywhere. |
| `L4-41` | ✅ PASS (caught opportunistically) | After the scheduler's first tick: *"Figures as of HH:MM (7m ago) — updated every 15 minutes."* |

**`L4-40` missed** — its window is *before the first scheduler tick*, and by the time Progress was
re-checked the tick had already run (this session had been going long enough). Recorded honestly
rather than claimed; `L4-41`'s confirmation is the next best evidence the two paths agree.

**Not driven this pass**: `M4-5`/`M4-6` (abort/stale-tab 409), `M4-8`…`M4-12`, `L4-18` (evidence
delete/dup-upload/multipart — F10 unblocks these for a future pass), `M4-16`…`M4-19` (Grid mode),
`M4-41`…`M4-47` (grid link pairs), `M4-50`…`M4-58` remaining "What failed" rows, `M4-20`/`21`/`25`/
`27`/`28`/`29`, `M4-38` (remaining gate rows — `M4-27` is confirmed unreachable per `M3-1`).

---

## F11 — Submitting a plan for review does not invalidate the review panel's query

**Severity: medium.** **Product defect**, found driving `M5-8`.

Clicked "Submit for review" on `TP-1` (`plan:edit`, status ladder DRAFT → IN_REVIEW). The status chip
at the top of the page updated correctly to "In review". The **Review panel**, on the same page,
continued reading *"Not in review — This plan has never been submitted for review."*

Checked it wasn't a delay: `psql` showed the `review` row had already been written
(`state=IN_REVIEW`, `subject_version_no=3`, `created_by` the caller) at the same instant the mutation
returned. A hard reload of the same URL immediately showed the correct panel — submitted by, trail,
reviewer picker, verdict buttons all present.

**So the write is correct and the read is stale**: the `Submit for review` mutation invalidates
whatever query feeds the status chip but not the one the Review panel reads (a `review`/`review-panel`
query keyed on subject id, most likely). This is the same shape as §110's F7 from the teams-and-roles
screen — a mutation that changes what a sibling panel should show, on the same page, without telling
that panel's query to refetch.

**Consequence**: a lead who submits a plan and immediately wants to ask a reviewer or leave a comment
sees "this has never been submitted" on the very screen where they just submitted it, and would
reasonably conclude the submit failed — even though it visibly succeeded one section up.

**Not fixed here.** The fix is one invalidation added to the submit-for-review mutation (or a broader
query key covering both the status chip and the review panel).

---

## A4 — review: results

Review turned on for plans (`M5-1`), `TP-1` submitted and approved. One comment thread, two reviewer
requests (a person + a team), one LGTM verdict, one genuine 409 found along the way.

| Row | Verdict | Note |
|---|---|---|
| `M5-1` | ✅ PASS | Saved; only Test plans review turns on artifact panels — Test cases/Suites stay off. |
| `M5-8` | ⚠️ PASS on content, **fails on freshness — see F11** | After a hard reload: chip In review, "Submitted by L. Lead", version pinned, empty trail, no Approve button on the panel. **Before the reload**: panel wrongly read "Not in review" although the DB row existed at the same instant. |
| `M5-9` | ✅ PASS | Comment posted, box cleared; "Looks good" entry added, plan stays In review. |
| `M5-23` | ✅ PASS | Reviewer picker excludes the submitter (`L. Lead` absent); already-asked people drop out of the list on the next open. |
| `M5-24` | ✅ PASS | Person and team both askable; team chip carries the group icon and no member names. |
| `M5-22` | ✅ PASS | Two "asked" chips (person + team), neither has spoken; the submitter's own LGTM sits in the trail despite never being asked — the two lists are visibly independent. |
| `L2-7` | ✅ PASS (found without going looking for it) | Lead tried to approve the plan **they submitted**: 409 in place, *"You submitted this plan for review, and this project asks somebody else to approve it."* Not a 403 — permission held, policy refused. |
| `M5-10` | ✅ PASS | Approved as `admin` (did not submit it): chip → Approved, "Return to draft" + Delete only. |

**Not driven this pass**: `M5-2`…`M5-7`, `M5-11`…`M5-21`, `M5-25`…`M5-30`, `L4-8`…`L4-16` (the two-account
review rows — comment-as-one/resolve-as-other, team-membership-expansion-on-read, board cards). A4's
core mechanics (submit, comment, ask, approve, the submitter-can't-approve gate) are confirmed; the
staleness half of the review panel (`M5-11`) and the reviewer-inbox/board rows are left for a later
pass or `A5`.
