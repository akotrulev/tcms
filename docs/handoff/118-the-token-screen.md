# §118. The token screen — the one dialog that must not close on success, and the read that is refused rather than narrowed

**Date:** 2026-08-07 · **Milestone:** M3, slice 3 · **Frontend only** · No migration, no contract
change, no server change.

**Gates:** `npm run build` clean · `npm run lint` 0 warnings · `npm run test:run`
**992/992 across 73 files** (up from 968/70 — 24 new across three new files; the handoff's carried
figure said 962, which had drifted again). `./gradlew build` not re-run: nothing under `backend/`
was touched.

## Why this slice

§116 and §117 each closed by naming it. §116 gave `api_token` its first writer and §117 gave CI
ingestion its caller, and between them the only way to obtain a credential was `curl` — so
everything M3 does started with a step the product could not perform. `GET`/`POST`/`DELETE
/api-tokens` have been on the contract since §116 with **no consumer in `src/`**, which is the shape
§116's own dead reads had and the one this slice closes.

Nothing was added to `openapi.yaml`. Every shape this screen needs was already there, `required`
arrays and all, which is what made it a one-lane slice.

## The reveal is a second step of the dialog, and Esc must do nothing

`POST /api-tokens` answers with the plaintext and **nothing can ever answer with it again**. The
three obvious places to put it are each wrong in a different direction:

| Where | Why not |
|---|---|
| A toast | It dismisses itself. For an unrecoverable value that means the product destroys what it just made while the user is still reading the form. |
| A row in the list | The secret would enter the **query cache** — a store the whole app reads, kept alive by every other consumer of that key. |
| Close on success, deliver later | The invitation flow's shape, and there is no second delivery here. |

So the form is **replaced in place** by the secret, and `IssueTokenDialog`'s reveal branch passes
**no `onClose` to `Dialog`** — the only dialog in the app that refuses Esc and a backdrop click. Its
one exit says what it costs (*"I've stored it — close"*).

**The dangerous version of this file is the tidy one.** Every other create dialog in `features/admin`
calls `onClose()` on success; a refactor that made this one consistent with its four neighbours would
throw away a credential and show a success message. The test that fails is
`shows the secret and stays open`, and a mutation adding `onClose()` after the `mutateAsync`
reddened exactly it and nothing else.

The lifetime is the mount: the dialog is conditionally rendered by its opener, so the plaintext lives
in the mutation's `data` and dies when the component unmounts. `useIssueApiToken` writes it nowhere —
no `setQueryData`, no optimistic insert. `never writes the secret into the query cache` asserts it
over **every** cache entry rather than the token key, because the point is the absence, not the shape.

## The list is REFUSED, not narrowed — so the screen asks before it fetches

`GET /api-tokens` 403s for a caller without `token:issue` rather than answering `[]`, deliberately:
an empty array would tell somebody who may simply not ask that this company has no credentials.

That correct server decision makes an ungated client read **wrong**. A Test Lead opening the page
would fire a request that fails and raise a global *"Something didn't load"* over a screen behaving
exactly as designed. So the gate's verdict drives `enabled`, and the refusal renders as a sentence
naming who to ask.

**Nothing on screen distinguishes the two implementations** — a page that fires the request and
swallows the 403 looks identical. `L1-21`'s assertion is therefore an *absent* network request, and
the component test asserts the same thing by counting calls.

**Deferred on `Gate.pending`, never rendered as a denial.** `useCan` is fail-closed while loading,
which is right for disabling a button and wrong here: it would flash *"you do not have permission"*
at a Company Admin on every visit. `Gate.pending` exists for exactly this and its header says so.

## `lastUsedAt` is on the wire and is NOT a column

The contract carries it and states it is **always null today** — the column exists and writing it on
every authenticated request is a write-amplification decision nobody has made. A "Last used" column
would be empty on every row of every deployment for ever: `ui-rules`' no-column-the-write-path-never-fills
rule in its purest form, and the field stays on the type so the client is built against the shape.

## Expired is not revoked, and the Revoke button is what proves it

`lib/apiTokens.ts` derives three states from two nullable timestamps. Both dead states mean the
credential cannot authenticate, so one "inactive" state is the tempting collapse — and it is wrong in
a way that reaches an affordance: **revoking an expired token is a legitimate act** (it closes the
credential deliberately rather than leaving it one date edit from working), while revoking a revoked
one is a no-op the row must not offer. Collapsing them disables a real action.

Revocation outranks expiry when a token is both, because it is the fact somebody *decided* — reporting
it as merely lapsed suggests extending the date would bring it back. A mutation collapsing `EXPIRED`
into `REVOKED` reddened four tests across the pure module and the page.

The state is derived rather than served for a second reason: expiry is a comparison against *now*, so
a served status goes stale in the cache while the row sits on screen.

## The role filter is `scope !== 'SYSTEM'`, and it is NOT the grant dialog's

`GrantRoleDialog` narrows to PROJECT-scope roles once a project is chosen, because a company-scope
grant pinned to one project is a contradiction. **A token is not a grant**: pinning it to a project is
a hard *ceiling* on where the credential works, and the server refuses exactly two things — a
SYSTEM-scope role and the membership baseline, which never reaches the wire at all (§43's seventh
role). Copying the neighbouring filter would hide roles the API accepts — §62's unselectable-option
defect arriving from the other side, where an administrator has no way to learn the product allows it.

## Decisions worth not re-litigating

- **`/admin/tokens`, beside `/admin/teams` and not under a project.** A token belongs to the company
  even when its `projectId` pins it to one, so a project-scoped route would make the rail's switcher
  appear to change a list it cannot change — `/admin/teams`' own recorded argument.
- **The rail item is ungated** while the Issue button is gated. Every other rail entry leads to a
  screen a member can read; this one leads to a screen that tells a non-administrator the list is not
  theirs. Hiding it would teach a Test Lead the product has no CI credentials — the same lie a
  filtered-to-empty list would have told, and §31 at rail altitude.
- **The rows are not re-sorted and revoked ones stay.** The server orders newest first and the list is
  complete. Sorting live above revoked is the tempting version and it moves the row an administrator
  is looking for — the one they just revoked — out from under the cursor.
- **`CopyField` is a shared component and the value is always readable text.** `navigator.clipboard`
  is **absent on an insecure origin**, and this product is self-hosted: the homelab box is plain HTTP
  today and a client's first install will be. A reveal that depended on the clipboard would hand over
  an unrecoverable credential and then refuse to give it to you. The text is selectable, the button is
  an accelerator, and its failure says what to do instead.
- **No owner field on the form.** The owner is the caller; a nominated owner would be a way to mint a
  credential more powerful than yourself by naming somebody more powerful — the exact escalation the
  intersection exists to prevent. The dialog states the intersection instead, because it is the one
  rule the two selects cannot show: choosing Company Admin does not produce a Company Admin token.
- **A blank expiry is `null`, and a filled one is converted through `Date`.** `datetime-local` yields
  a zoneless local string; posted verbatim it is read as UTC, so a token issued in a `+03:00` zone
  would die three hours early with nothing on either side saying why.
- **The revoke confirmation names the token *and* its prefix.** `ui-rules` requires the friendly
  label; here that is necessary and not sufficient, because two tokens can share a name a year apart
  and the prefix is the thing that matches a CI configuration.

## What is next

- **The ingestion history and the unmatched-test binder** — §117's two named screens, still unbuilt.
  The binder is what makes declining §6c's auto-created case stubs defensible for somebody who cannot
  read JSON, and the history has a repository read and deliberately no endpoint.
- **Drift detection** (§6c's stale-automation flag, and cases receiving results while marked MANUAL).
- **`lastUsedAt` needs a writer**, and it wants a batching answer rather than an `UPDATE` inside an
  authentication filter. The column is the one thing on this screen that cannot be shown.
- **The project-predicate-over-RLS gap is still seven files** (§117's closing note). Untouched here;
  still overdue.
