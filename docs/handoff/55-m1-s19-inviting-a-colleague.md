# §55. Inviting a colleague — the write nine sessions named, and the field the live pass added

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the tenth slice since §45.

`POST /users` exists. A company can now add a person to itself, which it could not do since the
product was first provisioned. **No migration** — the tables, the token machinery and the accept
flow have all been there since §18; what was missing was a way in.

## Why this one and not `documentation`

The orientation pass (§28's two questions, §46's third) over the whole SPA, mechanically rather
than from the last handoff's prose:

1. **One dead hook**, `useSharedSteps` — unchanged from §54.
2. **The MSW-only surfaces are `cases/bulk`, `shared-steps`, `doc-pages`, `artifacts`, `reports`
   and `reports/generate`**, derived by listing every `@*Mapping` against the contract's paths.
   The prose list in the handoff was right this time.
3. **The staffing loop had a hole in the middle and nothing else did.** §34 creates projects, §39
   grants roles, §54 lists who is on one — and the only way to get a *person* into the company was
   an operator with a psql prompt. §54's own live-pass fixture needed three `INSERT`s and said so.

`documentation` is the bigger surface and it needs a writer before its reads are honest (§54's
note, which still stands). This was the one the product was visibly missing.

## What was already there, and what that made the slice about

Almost all of it. `InvitationService.issueIfPending` already mints, already refuses to replace an
outstanding token, already sends after commit; `accept` already redeems and already grants the
`company:read` baseline. `InvitationService`'s own javadoc names *"a future invite-a-colleague
endpoint"* as a case it was written for.

So this slice is not plumbing either — the interesting parts are all decisions:

### The response cannot say whether the account already existed

`app_user` is instance-level with an instance-unique email, and its own header states the
consequence: **an invitation flow must never reveal whether an address already exists.** One person
legitimately belongs to several client companies (§6s), so an endpoint that answered differently
for a known address lets any Company Admin enumerate the instance one guess at a time — and learn
that a named competitor's staff are on it.

Both branches therefore converge before anything is returned: the account is found or created and
nothing downstream knows which; the membership carries **the display name the inviter typed**,
never `AppUser.canonicalName` — which is §6u's rule *and* the leak, because that name was chosen by
whichever company created the account. `Disclosure` drives both cases and compares the two
responses field by field.

The mutation that proves it is not the obvious one: seeding the membership from
`account.getCanonicalName()` still passes the fresh-account tests, because for a fresh account the
two strings are equal. It fails only against a **reused** account, which is exactly the case the
rule exists for.

### The dead link, which is what `REMOVED` costs

A `REMOVED` membership is neither absent nor active. `issueIfPending` mints for it happily — its
guard is "empty or active" — and `accept` then refuses, because redemption requires `INVITED`. The
result is **a link the product sent and the product rejects**, reported to the inviter as a
success, for the very ordinary case of a contractor coming back. `CompanyMembership.reinvite`
resets the row; `Rejoining` redeems the real token to prove it.

The consequence worth knowing: their old `role_assignment` rows are inert rather than deleted while
they are gone (that is what `remove()` promises), so **re-accepting restores their previous project
access**. That follows from the model rather than from this endpoint, and it is written down here
because it is the kind of thing that is obvious in the ERD and surprising at an admin screen.

### Two acts, two gates

The input carries **no `roleId`**. Inviting somebody and deciding what they may do have different
gates — `company:members` here, `project:members` at `POST /role-assignments` — and folding them
together would let one permission do the work of both. The dialog says so before you send:
*"They will be able to sign in and see nothing until you grant them a role."*

The gate is `company:members` at COMPANY scope, read off `AdminReach.companyWide()` — the same term
`RoleGrantService` uses — so the two writes cannot disagree about who administers this company. It
runs **before the payload is examined**, because the work below it reports whether an address is
already a member here.

### The baseline is deliberately not granted here

`InvitationService.accept` runs `tcms_grant_company_read_baseline` at the moment somebody actually
joins. A third call site (the migration and provisioning are the other two) would be a copy of "who
gets the baseline" to keep in step forever, for a row that conveys nothing until it is accepted.
`Accepting.acceptedInviteeSeesTheCompany` proves the decision by driving invite → accept → read
rather than by asserting the copy exists — and its sibling asserts they still see **no projects**.

### No `Idempotency-Key`, and that is not §54 forgotten

The identity of this request is the email address, so a retry finds the membership it already made.
A key per mount would add nothing and would need the mounting discipline §54 had to repair. `201`
means exactly *a fresh link was minted*; `200` means there was nothing to mint.

## The defect only the live pass found

The first version answered `{ user, emailed, expiresAt }` and the dialog read the "no relay
configured" case off `emailed: false`. That is right on an install with no relay and **wrong on
every second click of one that has one**: a re-invite also answers `emailed: false`, so the product
would tell an administrator with working mail that their mail was broken — while hiding the thing
that actually mattered, which is that the invitee's first link is still good.

`emailed: false` has **three** causes and the body could express one. `issued` is now on the wire:

| `issued` | `emailed` | `expiresAt` | What happened |
|---|---|---|---|
| ✓ | ✓ | set | A link is on its way. The dialog closes. |
| ✓ | — | set | **This install has no SMTP relay.** Carry the link across by hand. |
| — | — | set | Already invited; their existing link is untouched. |
| — | — | null | Already an active member. |

It restates `201` vs `200` on the body, which is the sort of duplication worth refusing by
default — and the reason it is right here is that **a response body has to be readable on its
own**. The status code is available to a client that keeps it; `api.post` does not, and a rule that
made it available everywhere to serve one screen is a worse change than one boolean.

Found by clicking Send twice, which no gate does. Both gates were green with the wrong copy in
them, and the three-state test now exists because the two-state one passed.

## What else the live pass proved

Driven in **LIVE mode** against `docker compose`, on §53's company, as its Company Admin:

- **A person was invited into a real company** and appears in the directory as `invited`,
  immediately — the write, the response and the invalidation.
- **`NADIA@BOARD53.TEST` matched `nadia@board53.test`** (`citext`), answered 200, and left the
  outstanding invitation alone: `membership_invitation` holds **exactly one unconsumed row** for
  her after two invitations.
- **The stored display name was not overwritten** by the second attempt's different name. Renaming
  a colleague is a different act; doing it as a side effect of a retried invite lets a mistyped
  name silently replace a correct one.
- **One `audit_event`, not two** — `company_membership.invited`, carrying the user id, the display
  name and `rejoining`, and carrying **neither the token nor anything about the underlying
  account**. The second click changed nothing and is not audited; a row per click would bury the
  one that mattered.
- The `UnconfiguredInvitationMailer` WARN fired from the real application, which is the named class
  this slice made of the old lambda.
- `Invite a person` renders enabled for the Company Admin; console clean; no page-level scroll.

**Not driven live:** the 403 branch (no second persona was signed in — the backend test covers it,
and the opener is gated by `PermissionGate`), and redemption of an emailed link on the live stack,
because the compose install has no relay and the link is deliberately absent from the response. The
suite drives that end to end by capturing the mail message.

## Two smaller things

**`InvitationMailer` stopped being a functional interface**, gaining `isConfigured()`. Deliberately
not a `default` method: a third implementation must state which of the two it is rather than
inherit the optimistic answer. The no-relay lambda in `MailConfiguration` became a named class.

**One assembler for a person.** `AdminDirectoryService.userViewOf` shares `assemble` with
`listUsers`, so the invitation response and the directory cannot disagree about the person they
both just named — the non-obvious part being the status rule (somebody `ACTIVE` elsewhere is
`INVITED` *here*). Same reasoning as §54's `peopleById`.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           1002 tests, 0 failures, 0 skipped      (976 + 26)
frontend:  npm run gen:api → UserInvitation / UserInvitationInput generated
           npm run build   → ✓ built
           npm run lint    → oxlint, exit 0
           npm run test:run→ 23 files, 401 tests passed   (391 + 10)
live:      docker compose up (no migration — still V17)
           LIVE mode browser pass — see above
```

**Mutations, each killed:**

- the gate removed — 3 tests.
- `reinvite` skipped for a `REMOVED` membership — 2, one of them the real redemption.
- the membership seeded from `AppUser.canonicalName` — 2, **both in `Disclosure`**; the
  fresh-account tests stay green, which is the point.
- `emailed` without `mailer.isConfigured()` — 1.
- the "nothing was issued" branch removed (always 201) — 2.
- the dialog closing unconditionally — 1.
- the two `emailed: false` messages collapsed into one — 1.

## Not done

- **`documentation` and `reporting` are still MSW-only** — `/doc-pages`, `/artifacts`, `/reports`,
  `cases/bulk`, `shared-steps`. `documentation` needs a **writer** to be honest (§54).
- **Removing a person is still not possible over HTTP.** `company:members` is defined as "invite,
  remove, set membership display names" and this slice built the first third. `CompanyMembership`
  has `remove()` and `rename()` with no caller — the same dead-code shape §28's orientation
  question exists to catch, now with one more instance. Offboarding is the half that matters for a
  departing contractor.
- **Teams still have no write endpoints** (§39, §45–§54). Narrower than before: a person can now be
  added to the *company*, so the remaining gap is the org chart itself.
- **The symmetric backend check — a declared operation no handler implements — is still not
  built** (§53, §54). It would list exactly the surfaces above.
- **No fixture-id registry** (§54). This slice picked `1c…` by running the one-liner over the test
  tree by hand, which worked and is still not a check.
- **This class is the suite's only mock**, and the reason is written in its header: the activation
  link exists only in the mail message. Worth re-reading before a second one appears — the habit,
  not the instance, is what would cost something.
- **A case that moves between suites still takes its whole execution history with it** (§52).
