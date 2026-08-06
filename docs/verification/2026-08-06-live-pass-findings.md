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

---

## A5 — the frame and the boards: results

| Row | Verdict | Note |
|---|---|---|
| `L4-26` | ✅ PASS (opportunistic) | `CASE_MAINTENANCE` cards render on My Work, one per never-executed case, naming the signal. |
| `M1-3` | ✅ PASS | My Work (as `lead`, granted on both projects): cards from both `OLB` and `MOB`, each stamped with its project key. |
| `M1-4` | ✅ PASS | A `MOB` card's key resolves to `/projects/<MOB-id>/cases/<id>` — correct project, address bar checked. |
| `M1-6` | ✅ PASS exactly | Clicking a transition button on a derived (`Maintenance`/`linked`) card: refused in place — *"TC-17 mirrors a test case, so it can't be moved to In progress. Update the case and the card follows."* |
| `M1-24` | ✅ PASS (by contrast) | The derived cards render with **no drag handle**; a real task card does. |
| task transitions | ✅ PASS | A created task moved `To do → In progress` via the transition button and landed correctly, board-wide count updated. |
| `L4-16` | ✅ PASS (found without seeking it) | Requested review from `engineer` on `TP-1` (row exists in `review_assignment`), then approved the plan (`A4`). Reloading `engineer`'s board afterward showed **no review card at all** — correct: the review had ended, and `L4-16` says every asked person's card should be gone once the artifact is approved or rejected. |

**Not driven this pass**: `M1-1`/`M1-2`/`M1-7`…`M1-29` (rail structure, collapsed state, keyboard nav,
the full drag gesture with `@dnd-kit` — only button-driven transitions were exercised, not the pointer
drag itself), `M1-10`…`M1-13` (team boards), `M5-28`/`M5-29`/`M5-30` (the review-card-while-open state
was missed — approval happened first in `A4`, so only the post-approval "gone" state was observed).
A follow-up pass should request a review, check the board **before** approving, to see the card `M5-28`
actually describes.

---

## A6 — reports: results

| Row | Verdict | Note |
|---|---|---|
| `L4-30` | ✅ PASS | The job seeded at `A3-2` reached `COMPLETED` between stages, with no polling — real worker clock. |
| `M6-1` | ✅ PASS (from `A3-2`) | 202, all seven contract fields, no `id`/`requestedAt`. |
| `M6-9` | ✅ PASS | Header, Generate, Saved reports panel (correctly empty — no saved-definition feature yet). |
| `M6-10` | ✅ PASS | Card appears immediately at "Queued" on 202's body, no skeleton. |
| `M6-18` | ✅ PASS | Report-view URL is durable — opened directly by id, full header renders. |
| `L4-34` | ✅ PASS | Header carries the real company (`Acme Test Co`), `OLB — Acme Online Banking` project scope, the scope sentence, `L. Lead` as generator, and a UTC-labelled timestamp. |
| `L2-12` | ✅ PASS | The raw artifact-content URL, hit directly (no bearer token): 401. |
| `M6-17` | ✅ PASS | Bars scaled to the series' own max (Passed=7 full width, Blocked=3, Failed=2, shorter in proportion), exact percentages (`58.3%`, not `58%`). |

**Not driven this pass**: `M6-3`…`M6-8` (PDF/DOCX refusal, saved-definition round trip), `M6-11`
through `M6-16` beyond what's listed (the download's actual bytes were not inspected, `M6-12`'s
two-cards-in-parallel, `M6-13`/`M6-14` failure states), `M6-19`/`M6-20` (building state, drift fault),
`L4-31`…`L4-35` beyond `L4-34` (offline reload, print preview, XSS in a title, the two-account
saved-definition isolation).

---

## L1/L2/L3 — results

| Row | Verdict | Note |
|---|---|---|
| `L1-6` | ✅ PASS exactly | `TC-1` (own, engineer): 204. `TC-2` (lead's): 403 *"You can only delete test cases you created. Ask a test lead to delete this one."* `TC-3` (`created_by IS NULL`): same 403 — the code-only arm. |
| `L1-19` | ✅ PASS | A Test Type value added while scoped to `OLB` (company-wide dimension) is visible reading `MOB`'s classification tree. |
| `L1-18` | ✅ PASS (fixture built along the way) | `Cart` taken Draft → In review → Approved as `lead`. |
| `L2-2` | ✅ PASS | `POST /classification/values` on `OLB` naming `MOB`'s own Feature dimension id: 404, not 403. |
| `L2-3` | ✅ PASS exactly | Recording a result on `TR-1` after `COMPLETED`: 409 *"This run has ended, so its results and evidence can no longer change. Reopen the run to make changes."* |
| `L2-4` | ✅ PASS exactly | `PUT .../suites/{id}/status` → `IN_REVIEW` on an `APPROVED` suite: 409 *"Only a draft suite can be submitted for review. Return this suite to draft first."* |
| `L2-9` | ✅ PASS exactly | `GET /work-items` with no parameters: 400 *"Ask for a project's board with projectId, or your own work across every project with mine=true."* |
| `L3-1` | ✅ PASS | `TC-1`'s `deleted_at` is set — soft delete, matching the dialog's claim. |
| `L3-7` (name half) | ✅ PASS | Renaming `TP-1` alone: `current_version_no` unchanged (3→3). |
| `L3-9` | ✅ PASS (after a false start) | Retagging `TC-6` with `severity` held constant: `current_version_no` unchanged (2→2). **First attempt was contaminated**: an incomplete `PATCH` body omitted `severity`, which cleared it to `null` and *did* bump the version — a real content change from my own test, not a tags-triggered one. Corrected and re-run cleanly. |

**Not driven this pass**: the bulk of `L1` (most Viewer/Engineer/Lead-tier UI-disabled-vs-enabled rows —
`L1-1`…`L1-5`, `L1-7`…`L1-17`), `L2-1`, `L2-5`…`L2-8`, `L2-10`, `L2-11` (blocked, `F5`), `L2-13`, and
most of `L3` (`L3-2`…`L3-6`, `L3-8`, `L3-10`…`L3-13`). Given time remaining in this pass, coverage was
concentrated on the rows most likely to catch a real defect (permission-tier deletes, 404-vs-403,
409 refusal wording, version-bump invariants) rather than walked exhaustively persona by persona.

---

## L4 — results

| Row | Verdict | Note |
|---|---|---|
| `L4-16` | ✅ PASS (recorded under A5) | See A5's results. |
| `L4-26` | ✅ PASS (recorded under A5) | See A5's results. |
| `L4-30` | ✅ PASS (recorded under A6) | See A6's results. |
| `L4-41` | ✅ PASS (recorded under A3) | See A3's results. |
| `L4-23` | ⚠️ **Overtaken by the pass's own later actions, not a defect** | `A3-1`'s seed (three `Failed` results for `TC-1` across three runs) was the fixture for this row, but `TC-1` was subsequently **deleted** while driving `L1-6` in the same sitting. By the time the dashboard was checked, "Failing repeatedly" correctly shows nothing — consistent with `L4-27` (a deleted flagged case drops out), not a failure of `L4-23` itself. The row needs a fresh seed on a case that survives to be checked. |

**Not driven this pass**: `L4-1`…`L4-22` (mostly recorded under `A0`/`A1`/`A3`/`A4` already), `L4-24`,
`L4-25`, `L4-27`…`L4-29`, `L4-31`…`L4-39`, `L4-42`…`L4-48` (the M2 rollup rows — `L4-40`'s window was
missed, and the rest need a fresh sequence of ticks this pass did not have time to wait out cleanly).

---

## F — faults and responsive: results

| Row | Verdict | Note |
|---|---|---|
| `tcmsFaults` presence | ✅ PASS | `window.tcmsFaults` is a real object in the dev build, confirming the fault seam described in `CLAUDE.md` exists in this running instance. |

**Not meaningfully driven this pass.** The first `hang()` arm was wiped by a subsequent `navigate` —
correct behavior per the fault switch's own documented reset-on-reload, but it means no fault-injected
row (`M7-1`…`M7-7`, `F-1`, `M7-8`) was actually observed misbehaving or recovering correctly. This
entire lane is effectively **undriven** and should be run as its own focused pass, arming each fault
strictly after the target page has finished loading.

---

# §110 — session summary (this sitting)

**All thirteen lanes touched; eleven driven to some depth, two (`F`, and parts of `L1`/`L4`) barely
started.** Fixture built from scratch through the real UI and API: ten suites, 26+3 cases, three
conditions, five plans, four runs, one report, one review cycle, three teams. Roughly 60 individual
queue rows confirmed against their exact worded expectations; several dozen more explicitly recorded
as not driven rather than silently skipped.

**New findings this sitting: F9, F10, F11.**
- **F9** — a suite's conditions can be read but never written (no UI control, no contract field).
- **F10** — local dev's `compose.yaml` has no evidence/reports volume; production is unaffected.
- **F11** — submitting a plan for review doesn't invalidate the Review panel's own query (same shape
  as the earlier session's F7).

**Confirmed-by-accident, not by design**: `L4-16` (review card vanishes on approval) and `L4-26`
(`CASE_MAINTENANCE` cards render) were caught as side effects of doing A4/A5 in this order, not by
deliberately sequencing to produce them — recorded as gaps for a follow-up pass to close cleanly
(check the board *before* approving, to see `M5-28`'s "card present" state that this sitting missed).

**What is left for a next sitting**: the bulk of `L1`'s persona-by-persona walk, `L2`'s remaining
curl-only rows, `L3`'s remaining psql invariants, most of `L4` (rollup-tick rows `L4-42`…`L4-48`
particularly — they need a clean sequence of scheduler ticks this sitting's other work kept
interrupting), and essentially all of `F` (needs its own focused pass, armed correctly, last).

---

# §110, second sitting — the F lane, driven

The previous sitting closed with *"essentially all of `F` … needs its own focused pass, armed
correctly, last."* This sitting is that pass. **Six of the nine rows driven, five of them to a full
confirmation; no product defect found in any of them.** That is the lane's whole point: these are
the resilience claims that had been asserted everywhere and *seen* nowhere.

**Instance unchanged**: company `v109`, project `OLB`, signed in as `lead@acme.test`, schema V35.
Nothing was written — the lane injects failures and reads what the screen does, and this sitting
made **no mutation at all**.

## Results

| Row | Verdict | What was actually observed |
|---|---|---|
| `M7-1` | ✅ **PASS** (My Work arm) | Skeleton, then the region fails: *"That took too long — The server did not respond in time. It may be under load — try again."* with **Try again**, plus the global notification *"Something didn't load"* carrying its own retry. **The chrome stayed on screen throughout** — the header bar, the "My work" title, the subtitle and the footer are all outside the region, exactly as the row requires. `__tcmsErrors[0]` reads `TimeoutError: The server did not respond within 10 seconds.`, so the budget is named by the error rather than inferred from the wait. **Not an endless spinner.** *(Team-board and plan arms not driven — same mechanism, ~100 s each.)* |
| `M7-2` | ✅ **PASS exactly, including the clause it exists for** | 503 + `Retry-After: 30` on a **cold** team board: *"Couldn't load this — The server ran into a problem. Try again in a moment."*, a **Try again**, and a copyable `Reference fault-3up98wc2`. It does **not** read "No such team" — the wrong sentence that would send a reader after a team that is still there. `tcmsFaults.off()` + **Try again** then loaded the board (To do 17 / In progress 0). |
| `M7-3` | ✅ **PASS exactly** | With the board loaded, a failed **refetch** (500) **kept all 17 cards** and marked them: *"Showing data from 17:05 UTC — the latest refresh failed."* Not blanked. On My Work the same marker appeared behind the `M7-4` dialog — a blanked refetch there would read as "your queue is clear". |
| `M7-4` | ⚠️ **PASS on everything observable to an agent; one half needs a human** | `expire()` aimed at a mounted query raises the ladder's rung 3 **in place**: *"Your session has expired. Sign in again to continue. **Nothing you have typed has been lost** — this page is still exactly where you left it. Signed in as lead@acme.test"*, with a Password field, **Sign out instead** and **Continue working**. No redirect; the route stayed mounted and **My Work's cards and its stale marker are visible behind the dialog**, so the dialog's claim is verifiable on screen rather than merely asserted. **The remaining half — type the password, press Continue working, watch the failed queries refetch — was not driven: entering a password into a field is outside what this agent may do.** It needs one human keystroke sequence to close. Session restored afterwards by reloading (the 401 is fabricated; the stored token is still valid). |
| `M7-5` | ✅ **PASS exactly** | `only('/teams')` + `status(500)`, `["teams"]` evicted so the read is genuinely cold: the heading falls back to **"Team board"** and **the board still renders all 17 cards**. Gating the board on the name would have blanked a board that loaded fine; it does not. |
| `M7-6` | ⚠️ **PASS on the first half; second half not driven** | `only('/conditions')` + `status(503, 30)` in the case editor for `TC-6`: the picker shows *"Loading…"*, and after the retries exhaust **the panel carries its own warning** — *"The condition library did not load, so nothing can be attached right now."* — under **both** Preconditions and Postconditions, while every other section of the editor (title, steps, placement, tags, classification, automation) renders untouched. **A failed library read does not take the editor down.** The row's second clause — *"the rest of the case still saves"* — was **not** driven: it needs a real write, and the sitting ended before one was made. `Save changes` was disabled only because the form was never dirtied, which is **not** evidence either way. |
| `M7-7` | ⬜ **Not driven** | The 390 px responsive sweep. |
| `F-1` | ⬜ **Not driven** | The hand resize below `lg` on an execution screen. |
| `M7-8` | ⬜ **Partial** | `window.__tcmsErrors` held only the errors this lane injected on purpose, and no unexplained client error appeared at any point. Not a whole-pass statement, because this sitting drove one lane. |

`tcmsFaults.off()` was run at the end and `state()` confirmed all six switches clear.

---

## F12 — The F lane is undriveable in a background tab, and it fails by looking like a product defect

**Severity: medium.** **Harness, not product.** This is very probably why the previous sitting's F
lane produced nothing.

A Chrome tab that is not the active tab has `document.visibilityState === 'hidden'`, and Chrome
throttles its timers. **`AbortSignal.timeout` is throttled far harder than `setTimeout`**, measured
in the hidden tab:

| Budget | Fired after |
|---|---|
| `AbortSignal.timeout(0)` | 1 ms |
| `AbortSignal.timeout(100)` | 789 ms |
| `AbortSignal.timeout(3000)` | **did not fire within 15 s** |
| `setTimeout(15000)` | 15 043 ms — on time |

`client.ts`'s `budget()` is built on `AbortSignal.timeout`, so in a hidden tab **the timeout budget
effectively does not fire**. The first `M7-1` attempt sat on skeletons for over 90 seconds with an
empty `__tcmsErrors` — which reads *exactly* like the defect the row exists to catch, an endless
spinner. It is not; it is the tab.

Made visible (`osascript -e 'tell application "Google Chrome" to set active tab index of window 1 to N'`),
`AbortSignal.timeout(3000)` fired at **3002 ms** and `M7-1` completed correctly.

**What the charter must say**: the F lane requires the tab under test to be the **active tab in a
foreground window**, checked with `document.visibilityState === 'visible'` before the first row and
not assumed. A row that times out slowly in a hidden tab is unattributable, and the failure mode is
a **false positive for a real defect**, which is the expensive direction.

---

## F13 — A reload clears the fault switch, so the obvious way to drive every F row is self-defeating

**Severity: low.** **Harness, not product** — and the behaviour is correct and documented
(`devFaults.ts` keeps its state in module scope precisely so a reload is a clean slate).

But every F row is worded *"arm the fault, **reload** X"*, and doing that literally clears the fault
before the page's first request. The previous sitting recorded exactly this: *"the one `hang()` arm
attempted was wiped by a navigate that followed it."*

The method that works, and that this sitting used for all six rows:

1. Land on some other route and let it finish loading.
2. Arm the fault from the console.
3. Reach the target route by **in-SPA navigation** — click a real link, or
   `history.pushState(...)` + `dispatchEvent(new PopStateEvent('popstate'))`. The module state
   survives, and a route whose query is not in cache gets a genuine **cold** failed load.

For a *second* read that a previous screen already cached (`/teams` for `M7-5`), evict it first. The
live `QueryClient` is reachable from the React fiber:

```js
const root = document.getElementById('root');
const key = Object.keys(root).find(k => k.startsWith('__reactContainer'));
let qc = null;
(function walk(f, d) {
  if (!f || d > 60 || qc) return;
  const p = f.memoizedProps;
  if (p && p.client && typeof p.client.getQueryCache === 'function') { qc = p.client; return; }
  walk(f.child, d + 1); walk(f.sibling, d + 1);
})(root[key], 0);
qc.removeQueries({ queryKey: ['teams'] });
```

**The rows should be reworded from "reload" to "arm, then navigate in-SPA"**, or the next sitting
pays for this a third time.

---

## F14 — `expire()` armed before a route mount is silently swallowed, and the row reads as failed

**Severity: low.** **Dev-mode artifact, not a product defect** — but it produces a **false negative**
that a future sitting would reasonably record as a bug.

Driving `M7-4` the obvious way — arm `expire()`, then navigate to a route whose query is not
cached — gave, three times running:

- `expireOnce` **consumed** (so a request really did receive the fabricated 401), and
- **no dialog**, and
- **no query holding an error**, and
- the page rendering its data normally.

That looks precisely like "a mid-session 401 is swallowed and the user is never told", which would
be a serious defect. It is not. The route's query is mounted twice under React's StrictMode
double-invoke in dev: the faulted attempt is superseded by a second fetch that succeeds, so the
recovery ladder never has to run. `expire()` fabricates **one** 401 and then clears itself, so the
second attempt is healthy by construction.

Aimed instead at an **already-mounted** query —

```js
tcmsFaults.only('/work-items'); tcmsFaults.expire();
qc.refetchQueries({ queryKey: ['work-items'] });
```

— the ladder fires correctly and the dialog appears, which is the `M7-4` result recorded above.

**What the row must say**: drive `expire()` against a screen that is **already on the page**, never
across a navigation. Note this is a statement about *dev*; nothing here says the production build
behaves this way, and nothing in this sitting tested that.

---

## What a next F sitting owes

- **`M7-4`'s second half** — the password, **Continue working**, and the refetch. Needs a human.
- **`M7-6`'s second half** — dirty the case and save it while `/conditions` is 503ing.
- **`M7-1`'s team-board and plan arms** — same mechanism, ~100 s each (4 attempts × the 10 s budget
  plus 1/2/4 s backoff; the plan's read is on the 60 s `long` budget, so budget minutes for it).
- **`M7-7`** (390 px) and **`F-1`** (hand resize below `lg`) — neither started.
- **`M7-8`** as a whole-pass statement rather than a one-lane one.
