# §198 — The values nothing compared, and the panel twenty-four tests never saw

**One phase, `frontend/` only: `src/api/contractResponses.ts`, the test harness, its own test file
and sixteen test files. No migration, nothing in `backend/src`, no contract change, no `schema.d.ts`
regeneration, and not one file under `src/` that ships.**

One phase for §196's and §197's reason, restated because it applies unchanged: the check and the
repairs it demands are the same change, and a commit holding only one half is a commit whose gates
say nothing about the other.

§197's own largest opened item, taken verbatim: *"**A value is still whatever a fixture says it is.**
`status: 'ACTIVE'` sat in `RunCreateDialog` for four slices and this instrument cannot see it… It is
the obvious next rung and it is a slice, not a patch."*

## The four questions the structural pass could not ask

`contractResponses.ts` asked two: is a **required** property absent, and is a property **declared
nowhere**. Both are about the *set of keys*. Everything under a key was unexamined, and the module's
own header said so and said why it was deferred — a value check "would drag in format-level questions
(`date-time`, `uuid`) whose false positives would arrive as noise in 76 files at once."

That is the right worry and it is answerable rather than fatal. Four of the value questions have no
false-positive problem at all, and the two that do are named and refused:

| Question | Kept | Because |
|---|:--:|---|
| an `enum` member the contract does not list | ✅ | the rung's namesake — `status: 'ACTIVE'` is not a `RunStatus`, and `lib/labels.ts` is `satisfies Record<Union, string>`, so a member the union does not have renders as `undefined` |
| a value of the wrong `type` | ✅ | `'4'` where the contract says `integer` is a component doing arithmetic on a string |
| a `null` where no `nullable: true` is declared | ✅ | see below — this is **not** the required check one field over |
| a `format: date-time` that is not one | ✅ | `lib/format.ts` renders `Invalid Date` rather than throwing, so a malformed instant is a screen defect no assertion about *other* text can see |
| `format: uuid` | ❌ | 208 sites, and not one id in this suite is a UUID |
| `pattern` | ❌ | 3 sites, two of them display keys |

**The null question is the one worth stating separately.** `required` is answered with `name in body`,
and `name: null` satisfies it. So a body that nulls a field the server always fills passes every
structural check there is, and the component downstream reads straight through it — which is exactly
the §195 shape ("a component one commit away from a TypeError") arriving through the one door §197's
check left open. The contract spells nullability `allOf: [{ $ref: … }]` + `nullable: true` at 36
sites, which is the only legal spelling in OpenAPI 3.0 because a `$ref` may carry no siblings, and it
is why the new `constraintsOf` reads `enum`, `type`, `format` and `nullable` **through** `allOf`
rather than off the node: a reader that looked only at the node would find neither.

### Why `uuid` is refused, and why the refusal is pinned by a test

The contract declares `format: uuid` 208 times. Not one id in this suite is a UUID — `'p1'`, `'c1'`,
`'TS-1'`, `'cfg1'` — because an id here is a thing a reader has to recognise across four files, and
`src/test/fixtures.ts` says in as many words that its defaults exist to be recognised rather than to
be realistic. Enforcing the format would rewrite every one of them as a 36-character string and buy
nothing: **the SPA treats an id as opaque.** It goes into a URL and comes back; there is no code
downstream that can tell `'p1'` from a UUID, where there very much is code that can tell `'ACTIVE'`
from a `RunStatus` and `'2026-01-01'` from an instant.

That is a decision, so it is written where it is taken *and* pinned:
`says nothing about an id that is not a UUID` asserts `violationsOf(project, aProject())` is empty.
Adding a `uuid` rule later reddens **there**, next to the argument against it, rather than in 208
fixtures at once.

## The finding: §197's walker had a silent branch, over the endpoints most likely to be wrong

The array branch read:

```ts
if (schema.type === 'array') {
  const items = …
  if (!items || !Array.isArray(value)) return   // ← here
```

So an operation declaring a **list**, answered with an **object**, was not a violation. It was
nothing at all. And that is precisely the population §197's own cache entry warns about one paragraph
earlier:

> **A path-keyed `mockImplementation` needs an arm per endpoint, and order matters.**

Eighteen doubles across sixteen files end:

```ts
if (path.startsWith('/projects/')) return aProject({ id: PROJECT, name: 'Banking' })
return []
```

which catches `/projects/{id}/statuses`, `/projects/{id}/reviews`, `/projects/{id}/cases/{id}/versions`
and every other sub-resource the fallback was written for. §197 found nine files answering
`/projects/{id}/settings` with the project body and fixed those, because `ProjectSettings` is an
object and the structural check could see it. The list endpoints beside them were the same defect,
invisible for a reason internal to the instrument. **Ten files, 86 tests.**

## And under two of them, a component had been rendering its error branch

`/projects/{id}/cases/{id}/versions` was answered by a *different* catch-all arm —
`if (path.includes('/cases/')) return caseBody(…)` — so `CaseVersionPanel` was handed a case where it
expects an array of `EntityVersion`. What happens next is worth following, because nothing about it
is loud:

1. `DataRegion`'s `isEmpty={(rows) => rows.length === 0}` asks an object for `.length`, gets
   `undefined`, and `undefined === 0` is **false** — so the region decides it is *not* empty;
2. the children run, `rows.map` throws;
3. the region's `ErrorBoundary` catches it and renders the failure branch.

The page renders. Every assertion in those tests is about something else on it. **24 tests across
`CaseDetailPage.test.tsx` and `DocLinksPanel.test.tsx` have passed for as long as those files have
existed with the version panel beside them showing an error** — including three named
`the attachment panel is gated on case:edit` and three about a declined condition, none of which had
any reason to look. This is §65's argument in miniature: a green suite is not a claim that the screen
is right, it is a claim about the assertions somebody wrote.

The value check did not find this by looking for it. It found the *fixture*, and the fixture was the
cause.

## The repair, and why it is one expression eighteen times

```ts
if (/^\/projects\/[^/]+$/.test(path)) return aProject(…)
if (/\/cases\/[^/]+$/.test(path)) return caseBody(…)
```

A sub-resource now falls through to the `return []` the fallback already had, which is a body the
server can send for all three endpoints. `base(projectId)` is `` `/projects/${projectId}` `` with no
query string, so the anchored form is exact.

**It is deliberately not extracted into a shared predicate.** The alternative was one
`isProjectRead(path)` in `src/test/`, and the argument against it is the one §197 made for putting
the seam where the double is created rather than where a test opts in: a helper is a thing a new file
can not-call. A regex that says `[^/]+$` is self-describing at the site, and the rule it encodes is
already written down in the orientation cache. Two of the eighteen (`ArchiveProject`, `TileLabels`)
end in `throw new Error('unmocked read')` rather than `return []`, and narrowing the arm there is
what makes that throw mean what it says.

All eighteen were changed, not the ten that reddened. The eight quiet ones are the same landmine
under a page that does not currently read a list sub-resource.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | **1320 across 99 files, 0 failures** (+6, no new file) |

**`backend` and `packages` were not run, and that is a decision rather than an omission** — §197's,
unchanged and for the same reason. Nothing outside `frontend/src` changed: no Java, no migration, no
`openapi.yaml`, neither `schema.d.ts`. §196 ran the backend because it moved a schema in the contract
and `ContractConventionTest` reads that file; there is no such edge here either.

The three measured runs, kept because the numbers are the finding: **86 failed / 10 files** with the
check installed and nothing repaired; **24 failed / 2 files** after the project arm; **0 / 99 files**
after the case arm. Not one of the 86 was an enum, a type, a null or a date-time — the tree is clean
on all four today, and the four checks are a guard rather than a repair. Every failure was the array
question, which is the branch that was silent.

## Verification

**No rows in either queue.** Every changed file is a test, the test harness, or the comparison the
harness calls; `contractResponses.ts` has no importer outside `src/test/` (checked — a production
import would also pull 550 kB of YAML into the bundle, and the built chunk sizes are unmoved). No
screen renders differently and nothing on the wire moved.

## The next slice

§197's list, minus what this took, plus what this opened:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, sixteen `M2` rows.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the snapshot has no reader** — §193's larger item, still untouched.
- **a clone cannot file the copy nowhere** — §195's contract-side item, small, still open.
- **the request bodies are unchecked in the same way** (§197) — a double reports what it was *called*
  with and the tests assert on it, but nothing compares those bodies against the operation's
  `requestBody` schema. Same walker, other direction, and the population is every write test in the
  suite. **This is now the largest one**, and §198 makes it cheaper: the walker it needs is the one
  that exists, values included.
- **`packages/` still mocks its client with no comparison at all** (§196, §197) and **nothing pairs
  the two tsconfig lists** (§196).
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177); §184's
  *what the SPA computes from a contract enum the server also computes*; §185's *a rule that fails
  when a test reads a path outside `src/`*; §188's `= null` writer-rule gap; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable
  form at all*.

**And two this slice opened.**

**A panel can render its error branch for a hundred slices and no gate says so.** The 24 tests above
are the measured instance; the *population* is every `DataRegion` in the tree whose children throw on
a body the fixture got wrong, and nothing enumerates it. A candidate shape: fail a test whose render
tripped a region-level `ErrorBoundary` unless it says it meant to — the same escape-hatch-as-a-function
design `servingADriftedBody()` uses, one layer up. That is a slice, and it is the one that would have
found this without a fixture defect to lead it there.

**The undeclared-property question is off for a whole class of schema.** `additionalProperties`
anywhere in an `allOf` chain sets `open` for the *entire* node, so a body carrying junk beside a
declared string map is unchecked; and the map's own values are never walked at all, so
`configuration.attributes` may hold numbers, arrays or nulls where the contract says string. Small,
measurable, and the second time a branch of this walker has turned out to be quietly permissive.
