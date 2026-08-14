# §196 — The rule that lived in comments, and the field eight fixtures never got

**One phase, `frontend/` plus one contract *nullable* and both `schema.d.ts` copies. No migration,
nothing in `backend/src`, and no product behaviour changes — every file this touches is a test
fixture, the shared test harness, or a comment.**

It is one phase and not two on purpose: the check and the 57 repairs it demands are the same change,
and a commit holding only one half is a commit whose gates say nothing about the other.

§195's own closing item, taken as the **check** it asked for rather than the reading it contrasted
itself with: *"the `as TestCase` fixture cast is a population, not an instance — mechanically
findable, and unlike this slice's audit a check rather than a reading."*

## The rule was decided in §79 and enforced by whoever happened to read a comment

§79 added `comments` to `Review`. Every one of `ReviewPanel.test.tsx`'s eleven tests went red at
**runtime** (`comments.length` of `undefined`) with **zero type errors**, because the fixture ended
`} as Review`. The repair was to delete the cast, and it worked — §80 measured the next required
field arriving as *one build error in one place*. Seven files then wrote the rule down in prose:

> *"Declared as `Condition[]` and NOT cast — §79's rule again. §95 added two required fields to this
> shape, and the whole point is that the next one is a build error here."* — `CaseEditorPage.test.tsx`

> *"no `as WorkItem`, which is §79's repair applied here… §80 measured what removing it was worth
> one slice later."* — `BoardPage.test.tsx`

And there it stopped. **Nothing ever compared the rule against the tree.** At the start of this
slice the tree held **57 object literals asserted to a contract type across 31 files**, and **39 of
them were missing at least one required field** — 31 at the top level, plus 8 more nested inside a
cast, because an assertion at the top of an object turns the compiler off all the way down.

The three sharpest instances are not in files that had never heard of the rule:

- **`CaseEditorPage.test.tsx` states §79's rule three times in its first hundred lines**, and the
  fixture ten lines *above* the first statement of it was `{ … } as Suite`, missing six required
  fields. Three more fixtures in the same file closed with `as unknown as TestCase`, and so did
  five one-line stubs standing in for what a `POST` answers.
- **`PlanFreshness.test.tsx` and `PlanLifecycle.test.tsx` name this exact cast as the cause of a
  mount-time crash that cost three red runs in §97** — *"the fields §97 phase 2 made required went
  missing silently behind `as unknown as TestPlan`"* — and **left the cast in place both times**.
  The repair that presents itself when the failure is in front of you is to add the missing field.
- **`CaseDetailPage.test.tsx` argues for `required` inside the object the cast was disabling it
  for**: *"Required by the contract, so leaving it off is a build error rather than a panel that
  silently renders its 'not assessed' branch"* — written under a `} as TestCase`, which is exactly
  what stops it from being a build error.

That is the shape §108 paid the most for, at the scale of a convention: **every one of those
comments is a careful reading, and a catalogue-wide invariant needs a computed check.**

## What the cast was costing, measured

**`TestPlan.keySeq` arrived on the contract in §165 and eight plan fixtures never gained it** —
thirty-one sections of every plan test running against a body the server cannot send, with nothing
slower or redder. §165 was itself the check that found `keySeq` — `TestPlanView` had been sending it
since §74 and the contract declared it nowhere — so `ContractConventionTest`'s member pairing closed
the *server↔contract* gap in the same commit that created this one's clearest instance, because
nothing compares the contract to the **fixtures**. `DocPageSummary.slug` is the same story twice,
and `DocBlock.links` / `DocBlock.coverage` (§168) are it four more times, nested one level down
inside `DocPage` fixtures whose own comment explains that those two fields are required.

None of that had bitten yet. §195's `declinedPreconditions` is what it looks like when it does: a
field absent from a fixture is harmless until the day a component reads it, and then it is a
`TypeError` in a test file that was green the day before.

## The rule this check enforces is "no cast", not "no missing field"

A cast that is complete today is complete *today*. The two rules diverge on exactly the day this is
about — the day a required field arrives — and the missing-field rule passes every cast in the tree
right up to that commit and then reports the damage. The check refuses the mechanism instead, so the
failure lands on `npm run build`, in the file that has to change anyway.

`contractFixtures.test.ts` encodes that difference as a **pin**: a *complete* cast to `TagUsage` is
one of the three violations the pins require. If a later slice weakens this into a missing-field
check, that pin is what reddens.

## The check, and the corpus it is designed to find empty

`src/api/contractFixtures.test.ts` builds a TypeScript program over the whole of `src/` — its own,
not `contractIntrospection.ts`'s, whose root set is `api/queries/` because its subject is the call
sites — and reports every object literal asserted to a type that resolves into `api/types.ts` or
`api/schema.d.ts`.

Two things about it are load-bearing:

**Resolution goes through the symbol, not the type.** `TestCase` is an alias for an indexed access
into `schema.d.ts`; the resolved type carries neither an alias symbol nor a declaration naming a
file, so a resolver written the obvious way answers *"not a contract type"* for every single one of
them and the check reports nothing. **The first draft of this file had exactly that bug** and looked
green — it found 23 casts instead of 57, and the 34 it missed included every plain `as TestCase`.

**The corpus is empty by design once the repairs land**, which makes "found nothing" both the expected
result and what a broken check looks like. Three guards separate them: the file walk must find ≥250
sources, the AST walk must see ≥100 type assertions of *any* kind, and ≥100 type references in the
tree must resolve to a contract type. The last one is the guard the first draft would have failed.
The pins do the rest — they run through the same analyzer over a **virtual** source file that is
compiled into the program but never written to disk, because a fixture full of deliberate violations
living in `src/` would be reported by the check itself, and exempting it by path is how an ignore
list starts (§162).

**It was mutation-tested rather than assumed**: a `{ id: 'l9' } as DocLink` added to `lib/links.test.ts`
reddened it with *"lib/links.test.ts:17 asserts an object literal to `DocLink`. It is currently
hiding 3 missing required fields: targetKind, relationship, stale."*

## What removing 57 casts said out loud

Deleting an assertion hands the question back to `tsc`, and three of its answers were not about
missing fields at all.

**1. `WorkItem.assignee` — the contract could not spell the state most cards are in.** `cardMove`'s
fixture wrote `assignee: null`; the schema declared a bare `$ref` with no `nullable`, which
generates as `assignee?: UserRef`. The server has always sent null — `BoardService` filters on
`card.assignee() != null` and `BoardPeople`'s javadoc says *"an unresolvable id yields no entry and
the caller renders a null assignee"* — and `BoardPage` renders `{item.assignee && …}`. So both sides
were arranged around a contract that was wrong, which is §148 exactly. **Fixed in the contract**
(`allOf` + `nullable: true`, the spelling this spec uses in 24 other places), both `schema.d.ts`
copies regenerated. The spec has exactly **two** bare optional `$ref`s to an object schema — computed
over the parsed document, not eyeballed — and the other one is not this defect: `BulkOperation.payload`
is a **request** body, where absent genuinely means *the caller supplied none* and the server binds it
as `BulkPayloadInput payload`. A response field is where absent and null are two claims about the same
row, and only one of them is what the server sends.

**2. `heldPermissions` was the one file where the shape it centralises did not bind.** Its own header
says *"the shape lives here once. A future change to the response is a compile error in this file and
nowhere else"* — under a `} as EffectivePermissions` that made it not one. Removing the cast surfaced
the second half: `keys: PermissionKey[] | string[]` widened every key to `string`, so **a misspelled
permission key was a silent no-grant** whose symptom is a disabled button three assertions later —
the same failure the header describes §65 causing. `keys` is `PermissionKey[]` now, which cascaded
through **28 call sites** whose own parameters were typed `string[]`.

**3. `configuration.attributes: []` where the contract says a string map.** `RunLifecycle.test.tsx`
described a run configuration the server cannot produce. Harmless today because nothing iterates it,
and invisible for as long as the cast stood.

**And a fourth, from the check's own file.** `tsconfig.app.json` **excludes** the contract checks by
name and `tsconfig.node.json` **includes** them by name, because they need `types: ["node"]` and the
SPA must not have it. Two hand-maintained lists that have to agree, with nothing enforcing the
pairing: a new file added to neither compiles under the app project, where every `node:` import is a
`TS2591`. **`npm run build` on shanks is what said so, after a local `tsc -b --noEmit` had reported
nothing** — the incremental build was up to date on a project that did not contain the file. Both
lists now name it, and both comments now say they are a pair. It is the same class as the slice, two
directories up, and the honest note is that it is still a reading.

The check also asks a second question that reported **nothing**: no fixture names a property the
contract does not declare. That is the expected result for a population rule, and it is worth having
said (§190's bare-identifier rule, same posture).

## What shipped

**The repairs.** 31 files. Every fixture that stood in for a contract type is declared and
not cast; where a file had three partial fixtures of the same type they became one complete factory
with overrides (`CaseEditorPage.test.tsx`'s `loadedCase`, whose three predecessors were missing ten,
fourteen and thirty-four fields between them). `TestProviders`' session user gains the four fields it
never had — inert values, because the point was never a plausible name: `teams` is a required array
one component away from a crash, and `isSystemAdmin` absent is an **answer** to an authorisation
question nobody wrote down. Plus the contract's `WorkItem.assignee`, both `schema.d.ts` copies, and
the comments in the ten files that described the cast this slice removed — struck through rather
than deleted, because a reader who finds only *"declared, never cast"* cannot recognise what it cost.

**The check.** `src/api/contractFixtures.test.ts`: three tests (the vacuity guard, the pins, the
rule), plus its name in `tsconfig.app.json`'s exclude list and `tsconfig.node.json`'s include list.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings |
| `frontend`: `npm run test:run` | **1301 across 98 files, 0 failures** (+3, one new file) |
| `packages`: `npm run build` · `test:run --workspaces` | ✅ · **241 across 11 files** |
| `backend`: `./gradlew build` | ✅ **2646 · 531 classes · 0 failures** |

`backend` was run although no Java changed, because `ContractConventionTest` and the §165 pairing
read `openapi.yaml` itself and this slice moved a schema in it.

## Verification

**No rows in either queue, and that is not an omission.** No screen renders differently: every
`frontend/` change is a test fixture, the test harness, or a comment. Nothing on the wire moved
either — `WorkItem.assignee` already carried null on every response the board has ever served, and
the change is to what the contract *says* about it, so there is no mutation to record and nothing to
drive. The behaviour under the repaired fixtures is pinned by the 1301 tests that ran.

## The next slice

§195's list, minus what this took, plus what this opened:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, sixteen `M2` rows.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the snapshot has no reader** — §193's larger item, still untouched.
- **a clone cannot file the copy nowhere** — §195's contract-side item, small, still open.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175) — now with a fixture that finally supplies it;
  **a run has no edit endpoint at all** (§177); §184's *what the SPA computes from a contract enum
  the server also computes*; §185's *a rule that fails when a test reads a path outside `src/`*;
  §188's `= null` writer-rule gap; §189's *a proposal cannot carry a decline*; §190's *whether "a
  comment whose argument expired" has any computable form at all*.

**And three this slice opened.**

**`as never` is the population this instrument cannot reach.** `vi.spyOn(api, 'get')
.mockImplementation((async … ) as never)` is how every mocked read in this repo satisfies a generic
client, and `never` carries no shape — so the *response body* a test serves is unconstrained by the
contract and nothing can say otherwise. There are dozens. A check would have to reconstruct which
endpoint a path string matches and compare the returned literal against that operation's response
schema, which is `contractOperations.test.ts`'s URL-shape machinery pointed at a different question.
That is a real slice, and it is where the remaining fixture drift lives.

**`packages/` has no equivalent check.** The client, the CLI and the MCP server are a second
TypeScript stack over the same generated schema, and this check's program is rooted at
`frontend/src`. `packages/` happens to be clean today (one `as RequestInit`, no contract-typed
literal casts anywhere), so the gap is a *guard*, not a defect — and the honest options are to
generalise the walker into a shared script both stacks run, or to accept that a lane with no
instances needs no instrument until it has one.

**And a third, smaller: the two tsconfig lists have nothing enforcing their pairing.** A rule that
`tsconfig.app.json`'s exclude list and `tsconfig.node.json`'s include list name the same files is
four lines of a check and would have turned this slice's own `TS2591` into a red test rather than a
red build on the remote. It is the same argument as everything above, which is the reason to name it
rather than leave the note in a comment.
