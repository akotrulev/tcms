# §128 — The draft buffer, and the check `If-Match` structurally cannot make

**Date:** 2026-08-08 · **Branch:** `master` · **Two phases, both shipped** · Decision round 3's
**step 8**, question **#57**. No migration.

`test_case_draft` has existed since `V1` with a comment describing exactly this flow and no code
behind it:

> *Autosave buffer. NEVER versioned. An explicit save promotes this into `test_case` + a new
> `test_case_version` and clears the row — this is what keeps history free of keystroke noise
> (plan §6j).*

The Resolved table settled §6j as one sentence — *"versions written on explicit save only; autosave
goes to a draft buffer"* — and **only the first clause shipped**, in §29. So for twenty-odd slices
the product has had the machinery that keeps history free of keystroke noise and no keystrokes to
keep out of it: the case editor had no autosave at all, and closing a tab lost the work outright.

This is #47's shape a second time and round 3 said so when it listed the question: a decision
recorded, half-applied, and never re-read. It is also the **sixth** blocker-or-promise note in this
repository to outlive whatever it was waiting on.

---

## Phase 1 — the endpoints, and the field the existing concurrency machinery cannot replace

Three verbs on `/projects/{projectId}/cases/{caseId}/draft`, all in `repository`:

| Verb | Answers | Notes |
|---|---|---|
| `PUT` | 200 with the stored draft | The autosave. Replaces wholesale, never merges. |
| `GET` | 200, or **204** when there is no draft | 204 is not the 404 beside it — see below. |
| `DELETE` | 204, idempotent | Discarding nothing succeeds. |

### There is no promote endpoint, and there must not be

`V1`'s comment says a save *"promotes this into `test_case`"*, which reads like an operation this
service should offer. It is not one. A promote would be a **second write path into `test_case`
whose input is by construction unvalidated** — that is what a buffer holds — so it would either
duplicate `CaseEdit`'s validation, giving the product two definitions of a legal case that are free
to disagree, or skip it, which is how a case with a blank title and a step referencing a retired
shared block reaches the database.

Promotion is therefore the **client's**: it restores the draft into the form and the user presses
Save, which is the ordinary `PATCH` — validated, versioned, audited, `If-Match`ed. The service's
whole contribution to that flow is the last clause of `V1`'s comment, *"and clears the row"*.

### `lockVersion` is the finding, and it is a defect the existing checks are blind to

The buffer stores the case's `lockVersion` as it stood when the editor started typing. Without it
there is a lost update that **`If-Match` cannot see**:

1. Ana opens `TC-9` at `lockVersion 3` and types. The draft is buffered.
2. Bob saves `TC-9`. It is now at `lockVersion 4`.
3. Ana returns. The editor loads the case **fresh** — `lockVersion 4` — and offers to restore her
   draft. She accepts and saves.

That save's `If-Match` is **4**, because it came from the fresh read, so `CaseUpdateService`'s
stale-editor check passes and **Bob's change is silently overwritten by text written before it
existed.** The check compares *what the client read* against *what is stored*, and the client did
read the current version. What nothing else asks is what the **draft** was written against.

`CaseDraft.stale` is computed server-side from that value — one answer, from the side that reads the
row, rather than two screens deriving it from two numbers they happen to hold — and it is
**advisory, never enforcing**. A stale draft is served in full: the words are the author's and are
often exactly what they want, particularly when the intervening save touched a different field.
Dropping it would be the product deciding that somebody else's edit invalidates your sentence.

### The buffer is per case, not per person — and the schema decided that, not this slice

`case_id` **is** the primary key. So there is one buffer per case, two people editing one case share
it, and a colleague's save clears your draft. Two consequences follow, and both are shipped as
design rather than discovered as bugs:

- **`updatedBy` is on the wire**, not an internal audit column. A returning editor must be told
  *whose* unsaved words they are being offered before adopting them — restoring silently would
  publish somebody's half-finished sentence under your name, with nothing in the audit trail showing
  it had ever been theirs.
- **Both consequences are pinned by tests that state what the shape is**
  (`anotherPersonsSaveClearsIt`), so a later per-person key has to change a test rather than change
  behaviour quietly.

Making it per-person is a migration and a decision, not a refactor: the key becomes
`(case_id, user_id)` and *"which of the four drafts do I open"* is a question the editor has no way
to ask.

### Four smaller decisions, each with the failure it prevents

- **Nothing in `content` is validated** — that is what a buffer means. The stated consequence is
  that **a draft which restores cleanly can still be refused when the user presses Save**, and that
  refusal is the ordinary 400 with its field errors. Validating here would make autosave fail while
  somebody is mid-sentence, so the buffer would protect nothing during exactly the interval it
  exists for.
- **No `@Version`.** An autosave that answers 409 is one the user cannot resolve — they never asked
  for it and there is no dialog to show them. Last-write-wins *is* the semantics of a keystroke
  buffer; the thing it protects (the committed case) is guarded by `test_case.lock_version` on the
  path that actually writes it.
- **`case:edit` on all three verbs, including the read.** The draft's content is the case's content,
  so serving it under `project:read` would disclose nothing a Viewer cannot already see — that is
  the argument that would be made for relaxing it, and it answers the wrong question. A draft is
  somebody's unfinished sentence, shown nowhere else in the product, and a Viewer is specifically
  the client's own stakeholder invited to read coverage rather than to watch it being written.
- **A 256 KB ceiling**, because nothing else bounds this: the content is unvalidated, the caller is a
  timer rather than a person, and no retention prunes the table. Measured in **bytes** on the
  serialized envelope, not characters — the column stores bytes and a character bound would be a
  different ceiling per language.

### 204 is not the 404 beside it

Almost every case has no draft almost all of the time — an ordinary state of a perfectly readable
case, not a missing resource. Collapsing the two would tell an editor their case was gone every time
they opened one they had not left work in, which is most of the installation. This is §35's argument
for `200 []` on a case with no versions, arriving at a different status code because there is no
collection to be empty.

### The red run was the fixture, twice, and neither half was the thing the convention names

All 26 tests died in `@BeforeEach` on a `DataIntegrityViolationException` inserting a company. The
UUID prefix was checked against every prefix in `src/test/java` before writing, as §19 requires —
and the collision was **`cd-acme`**, the company *slug* and email domain, already
`CaseDeleteEndpointTest`'s. Both are globally unique columns; neither is a UUID. **§19's family,
sixth instance, arriving through the fields the convention does not name.**

Then three survivors failed on a minimal `PATCH` body. A save must carry the **whole editable set**,
because `CaseUpdateRequest` cannot tell an absent field from one explicitly nulled — which is the
same asymmetry this slice is about, one level down: **the draft may be a fragment and the save may
not.**

---

## Phase 2 — the editor, and the gate that has to close first

### The ordering is the design, and getting it wrong is silent and total

Autosave must not run until the restore prompt has been **answered**:

1. The editor mounts and seeds the form from the saved case.
2. The draft query is still in flight, so nothing has offered to restore anything yet.
3. The author types one character. The form is dirty.
4. Autosave fires and **overwrites the buffered draft with the saved case's content**.
5. The draft query resolves and the editor offers to restore — the thing it just destroyed.

The user is then shown a restore prompt for their own unsaved work which, if they accept it, hands
back what they had already saved. **Nothing errors. Nothing is logged. The work is gone.**

So `enabled` includes *"the prompt has been answered"*, and the **editor spells that out rather than
the hook inferring it** — a hook that guessed would be a second opinion about a sequence only the
screen can see. `useCaseAutosave.test.ts`'s `WRITES NOTHING while disabled` is the assertion.

A **failed** draft read resolves the gate too, and that is a deliberate call: the alternative is an
editor whose autosave never starts because one `GET` failed — silently, since nothing on screen
could say so — which trades a small risk of overwriting a draft we could not read for a certainty of
buffering nothing at all for the rest of the session.

### One mapping, now with two consumers

`toCasePayload` moved out of the submit handler into `caseSchema`, because autosave is the second
caller. A second hand-written copy is the defect that shape produces: **a field wired into Save and
forgotten in the buffer restores as empty** — indistinguishable from the author never having typed
it, with both requests individually valid, both mappings type-checking, and the only observer
somebody looking at their recovered draft wondering where a paragraph went.

`BlockContent.of` in `documentation` is the same rule, arrived at the same way one slice earlier
(§127 phase 1).

`fromCasePayload` is the inverse, and it exists because the form's shape and the API's genuinely
differ, each difference for a reason already recorded on `CaseSchema`: an unfiled case is `''` in the
form and `null` on the wire (MUI's `Select` warns on `null`), a duration is the string the number
input produces, conditions are ids against `[{conditionId}]`. It **decodes defensively** — the
buffer is unvalidated by design and may have been written by an older build, so nothing in it can
throw and a wrong-typed field reads as **absent** rather than putting `[object Object]` in a title.
The round trip is asserted directly, which is the test that catches a dropped field.

### The fix that looked like the bug: `keepDirty` is not "make it dirty"

`reset(values, { keepDirty: true })` was the first draft and is wrong in the direction that reads as
right. `keepDirty` preserves the dirty flag **as it currently stands**, and at the moment of a
restore it stands at **false** — the offer is answered on mount, before the author has typed
anything. So the restored draft landed in a form the editor considered pristine: **Save disabled with
"Nothing has changed yet" over a form full of unsaved work**, and the autosave gated off with it.

`keepDefaultValues` keeps the *saved case* as the baseline and re-evaluates dirtiness against it,
which is the question actually being asked. Caught by
`restoring leaves the form dirty, so Save stays available` — a test written because the property
sounded obvious, which is the only reason it was asserted at all.

### Three smaller calls

- **A failed autosave is reported in a status line and nowhere else** (`meta.handled`). The caller is
  a timer, so a toast would appear unbidden, mid-sentence, once per interval, about something the
  author did not ask for and cannot act on. `role="status"` + `aria-live="polite"`, so a screen
  reader is told when the work became safe without being interrupted mid-sentence.
- **No retry and no queue.** The work is in the form, which is the safest place it can be, and a
  client retrying on a timer would hammer a server that is already refusing. But a failure **clears**
  the "what the server holds" ref, so the next content change tries again — without that, one failed
  write suppresses every subsequent one as already-buffered and the author's work stops being backed
  up for the rest of the session, silently.
- **The restore is a banner, not a modal.** A modal on mount blocks the author from reading the case
  underneath, and the decision it asks for only makes sense next to what is on screen —
  *"restore this instead of that"*. `severity="info"`, not `warning`: nothing is wrong. There is
  unsaved work, which is good news.

### What has no buffer at all, stated rather than left to be discovered

**A case being created has none.** The primary key is a foreign key to `test_case`, so there is
nothing to key a draft on until the case exists — the New Case form is exactly as lossy as it was
before this slice. Fixing that is a different table with a different lifetime (what expires an
abandoned new-case draft, and who sees it), not a column on this one. Asserted, so it reads as a
decision: `the CREATE form asks for no draft at all`.

---

## Gates

| Gate | Result |
|---|---|
| `cd backend && ./gradlew build` | **2130 tests across 453 result classes, 0 failures** (§127's baseline was 2104/447 → **+26/+6**) |
| `cd frontend && npm run build` | clean |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1144/1144 across 86 files**, measured on a full solo run (§127's measured 1105/84 → **+39/+2**) |

Flyway head is unchanged at **`V39`**; `V40` is still the next free number. The policed-table count
is unchanged at **62** — `test_case_draft` has been one of them since `V1`.

## Verification — built and unverified

- **9 browser rows**, `M2-67`…`M2-75` plus `F-12`. **`M2-67` cannot be reached by any gate and it is
  not close**: the point of a draft buffer is that work survives something the suite cannot do —
  closing the tab. A test that never closes a tab proves the request was made, not that the work came
  back. `M2-71` needs **two accounts and two browsers**, and what it demonstrates is the shared-row
  decision; if it reads as wrong when actually driven, that is a finding about the schema's 1:1 key
  and belongs in a decision, not a patch.
- **16 mutation rows**, `CD-1`…`CD-16`, **none run**. `CD-1` is the one worth running first: deleting
  the clear-on-save leaves every other test in the class green.
