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
