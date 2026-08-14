# §199 — The payloads nothing compared, and the Save that could never create a case

**One phase, `frontend/` only: `src/api/contractBodies.ts` (renamed), the test harness, its own test
file, one new `lib/` constant and two files that ship. No migration, nothing in `backend/src`, no
contract change and no `schema.d.ts` regeneration.**

One phase for §196's, §197's and §198's reason, restated because it applies unchanged: the check and
the repairs it demands are the same change, and a commit holding only one half is a commit whose
gates say nothing about the other.

§198's largest opened item, taken verbatim:

> **the request bodies are unchecked in the same way** (§197) — a double reports what it was *called*
> with and the tests assert on it, but nothing compares those bodies against the operation's
> `requestBody` schema. Same walker, other direction, and the population is every write test in the
> suite. **This is now the largest one**, and §198 makes it cheaper: the walker it needs is the one
> that exists, values included.

## The two directions are not the same kind of finding, and that is why this was worth doing

§197 and §198 compare a **response** a double serves. That is a claim the *suite* makes about the
server, so every violation they find is a fixture defect — 629 of them, and every repair was in a
test file.

A **request** body is the other thing entirely. It arrives at the double having been composed by
`api/queries/`, a `useForm` submit handler and whatever the component did to the form's values, so
what is being compared is **the bytes the SPA would have put on the wire**. The instrument is the
same and the population it audits is not: this half reads production code.

It is also the half with no other observer. `apiResponseTypes.test.ts` refuses an inline literal at
an `api.*` call site; `contractFixtures.test.ts` refuses a cast; `contractOperations.test.ts` checks
the method and the path. **Nothing looked inside a payload** — and `toCasePayload` returns
`Record<string, unknown>`, which is `as never` again by another spelling.

## What the check asks, and the one question that changes meaning

All six questions, unchanged. Five mean the same thing from either end. The **undeclared property**
gets sharper: a response carrying a field the contract does not declare is usually a double answering
the wrong endpoint, but a *request* carrying one is a value the SPA composed, serialized and sent to
a server that discards it without comment — §186's `SuiteInput`, which was a hand-written literal
typed as the endpoint's input and had stopped being it.

Three things the seam had to be taught, each a defect if it had not been:

| | |
|---|---|
| **`api.get(path, options)` puts `RequestOptions` where the writes put a body** | Reading argument 1 without asking which verb it was would compare a timeout budget against a schema, on the two most-used verbs in the suite. `SENDS_A_BODY` is `post`/`patch`/`put`/`upload`. |
| **`{}` and no argument at all are the same request** | `JSON.stringify(body ?? {})`. So an operation that declares no body is only being *sent* one when the object has something in it — otherwise the check would report `useAcceptProposal`'s `api.post(url, {})` and tell it to write a call that is byte-identical. |
| **A `multipart/form-data` body is refused, not checked** | §198's `uuid` grounds. What `api.upload` is handed is a `FormData` whose parts are strings and `File`s; comparing it is a second instrument, not this one with a wider argument. Pinned by `leaves a multipart upload alone`. |

**The check runs before the double does**, so the error path of every mutation in the suite is
covered too — which is where a payload defect is most likely to hide, because a test asserting a
failure asserts nothing about what was sent.

`contractResponses.ts` → **`contractBodies.ts`**. A module named for one half of what it does is
§152's shape, and it now answers both halves.

## The finding: the plan's Save has never been able to create a case

**Ten tests across three files.** Two of the three were `useAcceptProposal`'s empty `{}`, which is the
decision above and not a defect. The third is the slice.

`useCreatePlanCase` — the Test cases tab's create, §97 — sent this:

```ts
{ title: row.title, suiteId: null }
```

`TestCaseInput` requires **four** fields. So every Save on that tab with a row typed in it answers
**400** on `stepFormat`, `priority` and `automationStatus` — three fields the author was never shown
and could not have filled.

**§144 is the slice that decided the required set, and it audited the clients that send this shape.**
It found and fixed three; all three were in `packages/`. Its own text names the client it did not
check:

> The SPA satisfies this by construction — its editor submits every field it renders on every save.

True of the editor. Never true here: this is a second writer of the same shape, added at §97, whose
whole input is a title. §144's sentence *"That sentence was written while looking at one schema"*,
one layer over, written about itself.

**Why nothing saw it for fifty-five sections.** Nothing compared a sent body to the contract until
this slice, and the browser lane that drives this screen — `A2 — plans`, whose `M3-43` is *"two
`POST /cases` and one `PATCH`, in that order"* — is the lane the queue has been stopped at since
§186. The tests that cover the hook are green and prove nothing about it: `usePlanSave.test.tsx`
asserts the **order** of the calls and the ids in the `PATCH`, which is the suite agreeing with the
payload rather than the server doing so.

### The repair, and the one field it does not reach

`NEW_CASE_DEFAULTS` in `src/lib/caseDefaults.ts` — `STRUCTURED` / `MEDIUM` / `MANUAL` — spread under
the caller's fields. It is **one constant used twice**: `CaseEditorPage`'s `defaultValues` carried
the same three literals as `?? 'MEDIUM'` fallbacks, and two screens authoring a case from nothing
must not answer *what does this case say before the author does* twice. That is §183's finding about
a colour and §179's about a severity, arriving on a payload instead of on a screen.

`STRUCTURED` is not a neutral choice made twice over; it is the only one available. `GHERKIN` is
refused without a scenario body, so a default of `GHERKIN` is a case that cannot be written at all.

**And that is exactly where the repair stops.** `CaseEdit.validate` refuses a `STRUCTURED` case with
no steps — *"Add at least one step, or switch to Gherkin"* — and §144 decided that the conditional
requirements stay **off** the contract rather than being spelled with a four-way `oneOf`:

> `steps` is required only for `STRUCTURED`, `gherkinSource` only for `GHERKIN`… They stay optional on
> the contract and refused on the server, which is the honest arrangement rather than a complete one.

So a payload of a title plus these three is **everything the contract can ask for and one server rule
short of a case**. The Save now sends a body the contract admits and still meets a 400 — on one field
instead of four, and on a rule this instrument is structurally unable to see. Closing it is a
decision about what a case authored from a plan row *is*, and it is named as this slice's largest
opened item rather than taken quietly inside a check-writing slice.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | **1330 across 99 files, 0 failures** (+10, no new file) |

**`backend` and `packages` were not run, and that is a decision rather than an omission** — §197's,
unchanged. Nothing outside `frontend/src` changed: no Java, no migration, no `openapi.yaml`, neither
`schema.d.ts`. The server was *read* (`CaseEdit.validate`) and not touched.

The two measured runs: **8 failed / 3 files** with the check installed and nothing repaired, then
**0 / 99**. Of the eight, six were the plan create's three missing fields ×2 rows and two were the
empty `{}`.

## Verification

**Two browser rows, both in lane `A2 — plans`, beside the row that would have caught this.** Unlike
§197 and §198, this slice changes a shipping writer, so a row is owed.

- **`§199 PC-1`** — the repaired create, driven through `M3-43`'s own steps.
- **`§199 PC-2`** — the residue, written as a row that is **expected to fail today**: the 400 on
  `steps`, which is the open decision below and the only way to observe it.

`M3-43` itself is amended to name the payload it should now see.

## The next slice

§198's list, minus what this took, plus what this opened.

**A case authored from a plan row cannot be created, and the fix is a product decision.** Three arms,
none free:

1. **The server accepts a stepless `STRUCTURED` case on create.** The product already contemplates
   one — `CaseCompleteness` raises `INCOMPLETE` for *"an approved case with no steps"*, which is a
   state the create rule says cannot exist. Cheapest, and it weakens a rule the case editor relies on
   the server for.
2. **The tab invents a step.** Rejected on sight: it is authoring content the author did not write.
3. **The tab stops creating cases** and sends the author to the editor — a reversal of §97 D2, whose
   whole argument is that a plan is written by listing what it covers.

The rest, carried forward: resume the browser queue at `A2 — plans`; a third `packages/` live pass;
the snapshot has no reader (§193); a clone cannot file the copy nowhere (§195); **`packages/` still
mocks its client with no comparison at all** (§196, §197, §198 — and this slice makes it cheaper
again, since the module now answers both directions); nothing pairs the two tsconfig lists (§196);
**a panel can render its error branch and no gate says so** (§198, still the largest instrument
item); the undeclared-property question is off under `additionalProperties` (§198); the read-only
archive decision §159 did not take; serving the relabel audit (§174); `CurrentUser.teams` has no
reader (§175); a run has no edit endpoint (§177); §184's, §185's, §188's, §189's and §190's items.
