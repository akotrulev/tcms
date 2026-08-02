# §67. Evidence attach and delete — the port, the ordering, and the invariant not implemented

**Date:** 2026-08-02. **No migration; `V20` is still free.**

`evidence` and `attachment` have been in `V1` since the beginning with **no reader and no writer**.
`EvidenceView` existed and its header said so in as many words — *"always an empty list in this
slice"* — and `TestExecutionView.of` hard-coded `List.of()`. So the execution screen has rendered a
field the product could never populate, for the whole life of the `execution` module.

Both halves land here: `evidence:attach` gets its first consumer in Java, and `evidence:delete` gets
the `OWN` consumer **§66 named on its way out** — *"the machinery is now in place; only the call
site is missing"*.

## `Evidence.url` was a required field with nothing behind it

The orientation pass found this before any code, and it changed the shape of the slice.
`Evidence.url` is `required` in the contract and has been since M2. There is no route that serves
bytes. So a server implementing only attach would have had to **invent a string** for a field every
consumer is entitled to dereference.

That is why attach and download landed in one commit rather than two. Shipping the write alone is
what §54, §55, §56 and §57 each recorded as the mistake, and this is the same error one field down:
a value that is present, typed, and false.

The route is session-authenticated rather than a public link, because the filesystem adapter cannot
issue an expiring signed URL and a guessable permanent one is an unauthenticated read of a
customer's screenshots. It needs **no permission** beyond the project read gate — anyone who may see
the execution may see its evidence, and the catalogue has no `evidence:read`. Inventing one would
have been a permission no role is granted, which is a 403 nobody can fix.

## The port lives in `execution`, and that is a decision rather than a shortcut

Open question #28 chose *storage port, filesystem adapter now, S3 later* (§51). The port is real —
`EvidenceStore`, four methods over an opaque key — but it lives in `execution` and not in a
`storage` module of its own.

`ModuleStructureTest` hard-codes **both** the module list and the rule that cross-module FKs and
declared dependencies point only at `tenancy`/`identity`/`project` (plan §3). A `storage` module
owning `Attachment` would need plan §3 amended and two architecture tests changed, to serve exactly
**one** caller. It moves when there is a second — with two real callers to design against instead of
one imagined.

`attachment.owner_type` spans six values and only two are this module's, so the table is built for
that day. What keeps the eventual move honest is `Attachment.forExecution` / `forStepResult`: **no
call site ever spells an owner type**, so the writers that must move are the ones the file names.

## Rows first, bytes second — in both directions

The one genuinely new problem in this slice: **a filesystem does not roll back with the
transaction.** This is the first thing in the product whose state is not in Postgres.

The ordering is chosen so every failure leaves the *recoverable* half:

- **Attach** writes the rows, then the bytes. A failure after the rows leaves a row whose blob is
  missing — the download 404s, and an operator can find it by joining. Bytes first would leave a
  blob **no row names**: unreachable, uncollectable, invisible.
- **Delete** removes the rows, then the bytes. A failure leaves an unreferenced blob a sweep can
  find. Bytes first would leave a thumbnail that renders and never opens, which reads as a product
  defect rather than as a partial operation.

**Neither is compensated automatically, and no test asserts either.** That is in the mutation queue
as the row whose expected result is *nothing reddens* — both orders serve an identical response on
the happy path. The property is currently held by a comment, and the honest fix is a fault-injecting
`EvidenceStore`, which is its own slice.

## The invariant deliberately NOT implemented

`V1`'s comment on `evidence.step_result_id` says evidence is *"immutable once the run closes"*.
It is not enforced, and the reason is written into the entity rather than left to be rediscovered:

- `RunStatus` has no `CLOSED` value (`NOT_STARTED · IN_PROGRESS · COMPLETED · ABORTED`);
- `run:close` has no implementation at all;
- decisively, **`ExecutionService.record` does not refuse to amend a result on a `COMPLETED` run.**

Gating evidence alone would make the screenshot **stricter than the verdict it documents** — a
tester could change the result and not attach the proof of it. The rule belongs with `run:close`, as
one invariant over both writes. A rule enforced on one of two paths reads as a bug on the other.

## The contract defect the build found, one field deep

`Evidence.createdBy` was declared `required` and **non-nullable** — inherited, not introduced here.
The server returns `null` for a row written before authorship was recorded, and **that null is the
entire `OWN` arm**: a grant covering "your own work" must not stretch to work nobody can attribute.

A non-nullable declaration makes the SPA collapse *no author* into *some author*, which is precisely
the distinction the qualifier turns on. Now `allOf` + `nullable`, the same shape §66 gave
`TestCaseSummary.createdBy`.

**Found by `tsc`, not by reading it** — the mock fixture would not compile with a `null` author. The
type system caught a contract error that four gates and a full backend suite had no opinion about.

## Two client seams, and the one that would have failed silently

`api.upload` is separate from `api.post` rather than a body-type branch inside it, because the two
differ in more than serialization:

- **A `FormData` body must not carry a hand-set `Content-Type`.** The browser generates
  `multipart/form-data; boundary=…`, and the boundary is the only thing telling the server where one
  part ends. `request` set `application/json` unconditionally — so the first upload would have been
  a **400 on a request that looks perfectly correct in devtools**.
- An upload defaults to the **long** budget. 10 MB on hotel wifi outruns the 10 s interactive one,
  and that failure reads as a broken server rather than a slow connection.

The multipart limits are the other half of #28's consequence, stated in §51 when the option was
chosen: uploads proxy through the API, so `spring.servlet.multipart` is the only thing between one
caller and the disk the whole instance shares. It was **unset**, and Boot's 1 MB default would have
refused an ordinary screenshot. An unset limit is not "no limit" — it is the wrong limit.

## The test that would have passed while pending, again

§66 committed `CaseDetailPage.test.tsx` because nothing proved the *page* passed the author.
`EvidencePanel.test.tsx` is the same file one screen over, and it needed the same barrier:
`useCan` is fail-closed while pending, so `waitFor(() => expect(…).toBeDisabled())` is satisfied
immediately by the loading state.

Mutation-verified: deleting `authorId` from the JSX reddens **exactly the two authorship tests**.
The `?? null` is load-bearing and is commented at the call site — `?? undefined` would mean "this
affordance is not author-qualified" and hand every row the unqualified verdict.

## Mutations run

| Mutation | Result |
|---|---|
| Traversal guard deleted from `FilesystemEvidenceStore.resolve` | exactly 1 red — the traversal test |
| `authorship.mayActOn(...)` → `false` in `EvidenceService.remove` | **exactly 2** red; `deletesOwn` and `aLeadRemovesAnyones` stayed green |
| Partition puts every row in the execution-level list | exactly 1 red — `attachesToAStep` |
| `authorId` deleted from `EvidencePanel`'s JSX | exactly 2 red — the two authorship tests |

The second is the one worth reading. Had `deletesOwn` or `aLeadRemovesAnyones` *also* reddened, the
tests would have been measuring the gate rather than authorship — which is the failure §66 caught in
its own draft. The null-author row is set with raw SQL, because nothing the API offers can create
one: `created_by` is always the caller. That is exactly why it is the arm most likely to be wrong.

## The suite has a load-dependent cliff, and this slice found it

Adding **five** frontend tests pushed `DocPageEditorPage`'s two `userEvent.type` tests over the 20 s
budget — a file this slice never touched, always the same two, always *Test timed out* and never an
assertion. They pass alone in under 15 s for the whole file.

Raised to 30 s, following §54's recorded precedent, and the recurrence is recorded in
`vitest.config.ts` rather than the number being quietly changed: **this is the second raise for one
cause**, and it means any slice that adds tests anywhere can redden a file it never touched. The
real fix is that two tests type whole sentences character-by-character through React's scheduler
when `paste` would assert the same thing; that is out of this slice's scope and is now written down
where the next person raising the number will see it.

## Gates

- `backend` — `./gradlew build` clean, **1223 tests, 0 failures, 0 skipped** (+17).
- `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 490/490** (+5).
- **Not driven in a browser** — §65's standing policy. Rows are queued in both verification files,
  including the mock-vs-live divergence (`url` is a `blob:` URI in mock mode, so two rows are
  live-only).

## Left for later, deliberately

- **Per-project storage quota** — #28's other half, still open, and the filesystem adapter makes it
  **more** pressing rather than less: the bound is one disk shared by every company on the instance.
  Today the only limit is per-file.
- **The run-closed invariant**, with `run:close` — see above.
- **Thumbnails.** `thumbnailUrl` is null on every row; no image processing. Null rather than a
  guessed route, which would 404 on every image.
- **The backup story.** `TCMS_EVIDENCE_ROOT` is stateful and backup-critical, and the Hetzner
  runbook covers Postgres and not this directory. Losing it loses every screenshot behind every
  failed result and no migration restores them.
- **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant** (§64's carry-over).
- **The artifact register's plan filter is `useState`, not the URL** (§61's carry-over).
