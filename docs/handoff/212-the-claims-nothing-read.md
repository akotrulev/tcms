# §212 — The claims nothing read, and the two acts they talked the surface out of

**Two phases**, one machine-checkable rule plus its whole population, then the two arms that
population had been standing in front of. `packages/` (three new test files, one refactor, two tool
definitions), `docs/specs` (the contract, the roles spec, the surface spec) and both `schema`
copies, plus comment-only repairs in `backend/src` and `frontend/src`. **No migration, no
permission moved, no grant changed, and not one line of shipping logic in `backend/` or
`frontend/`.**

§211's own "what is NOT done" opened it, in two entries that turn out to be the same entry:

> **The `identifying` defect was a class, and only one instance was looked for.**
>
> **Four things the credential holds that this surface still cannot do**: close or reopen a run
> (`run:close`), publish a doc page (`doc:publish`), anything at all with artifacts, and
> `library:manage` … `run:close` in particular is one `state` arm and the same call the CLI's
> `run status` already makes.

The connection is the point of this section. The reason the surface could not close a run was not
that closing a run is hard. It is that **for eight days after `V52` granted the key, thirteen live
sentences across four corpora said a contributor did not hold it** — and one of those sentences was
the tool description a model reads before deciding whether to call.

## The lesson has now been stated three times and swept by hand twice

§210 closed with it: *"copy that names a role is a claim about the catalogue, and nothing checks
it."* It found three such sentences and swept `backend/` and `frontend/`.

§211 found the same lesson was true of four more in `packages/`, **two of them tool descriptions**,
and wrote an eight-line rule over that one corpus — comparing `does not hold` sentences against
`AGENT_KEYS`.

This is the third pass, and the first one a machine makes over all four corpora. It found **thirteen
more**, in every corpus including the two §210 had already swept:

| Where | What it said | What is true |
|---|---|---|
| `openapi.yaml`, `PUT …/runs/{runId}/status` | *"Requires `run:close`, which is **Lead and up** — a Test Engineer … does *not* hold this one"* | `V52` granted it |
| `openapi.yaml`, `PUT …/doc-pages/{pageId}/status` | *"Requires `doc:publish`, which Test Engineer does **not** hold"* | `V52` granted it |
| `openapi.yaml`, `DELETE …/artifacts/{id}` | *"Lead and up, and deliberately **not** held by a Test Engineer"* | `V52` granted it |
| `openapi.yaml`, `DELETE …/plans/{id}` | *"The lead line now sits at shared structure … and at judgements about the work"* | written by `V51`; `V52` deleted the line **the next day** |
| `openapi.yaml` ×2 | the doc attachment endpoints, both resting on Test Engineer holding `doc:edit` and not `doc:publish` | both are a contributor's |
| `frontend/src/api/queries/runs.ts` | *"`run:close`, which a Test Engineer does not hold"* | granted |
| `frontend/src/api/queries/documentation.ts`, `features/docs/DocsPage.tsx` | the same about `doc:publish`, twice | granted |
| `backend/…/ExecutionService.java`, `TestExecution.java` | `result:amend` is *"Lead-only"* | granted |
| `backend/…/PlanLifecycle.java`, `PlanDeleteService.java` | *"`run:close`, `suite:delete` and `plan:delete` are all Lead-only"* | all three are a contributor's |
| `backend/…/SuiteWriteService.java` | *"`suite:delete` by leads only"* | granted |
| `backend/…/LifecycleLadder.java` | rests on §A4 principle 1 *"as `V51` rewrote it"* | `V52` deleted that principle |
| `backend/…/ExecutionEndpointTest.java` | the class's own list of what it catches | the persona it named cannot carry that assertion any more |

Seven more were repaired in the same pass and are **outside the rule's reach** — see below.

## What the rule is, and the four decisions inside it

`packages/client/src/test/tierClaims.test.ts`, over the four corpora `citedFiles.test.ts` already
reads. Its instrument is `roleMatrix.ts`, which **parses the roles spec's §A3 tables**.

1. **It reads the spec, not a copy.** §211's rule compares against `AGENT_KEYS`, ~~a hand-kept
   constant whose own comment says it is *"only as current as the last slice that moved a grant
   remembered to update it."*~~ → **§213 made that constant read this same parse**, so the second
   place is gone. A second such constant, in a second package, for a rule sweeping four
   corpora, is a third place for the catalogue to be wrong. The chain is
   `migration → SeedDataTest → spec → this rule`, and only the last link is new: `SeedDataTest`
   still pins the seeded rows, and this pins the prose against the spec.
2. **A claim is a clause, not a paragraph.** The first cut paired every role in a sentence with
   every key in it and reported fifteen findings on one true sentence. What fixed it was taking the
   **nearest key only**, and the role from the side the *voice* puts it — active `X does not hold`
   looks backwards for the subject, passive `withheld from X` looks forwards. Population went from
   74 candidates to 13 findings, all real.
3. **Past tense is not a claim, and `<del>` is strikethrough.** Every migration header that moved a
   key opens by saying what it *was*, truthfully. And javadoc renders `~~` as literal tildes, so
   `backend/` writes its corrections as `<del>…</del>` — a form `citedFiles.test.ts` had never
   excluded. Four spans read as live claims that had been struck through and corrected in the same
   sentence.
4. **Code is not prose.** `proseOf` blanks everything outside a comment. Measured first: a whole-file
   scan reported a Test Engineer as lacking `case:delete` on the strength of an `assertThat` chain,
   and read a `@DisplayName` — *"an engineer cannot take a lead's approval back"* — as a statement
   about the catalogue. Both belong to the test asserting the rule the sentence would have been
   wrong about. **A wordlist over identifiers finds the words it is made of.**

`corpus.ts` is the extraction that made a second rule possible: the four roots, the walker and the
strikethrough handling, previously private to `citedFiles.test.ts`. Two scanners agree until
somebody teaches one of them about a new extension.

## The reach it does not have, measured rather than asserted

**It catches a claim that names a key, not a claim about a tier.** Seven repairs in this slice were
found by hand and are unreachable by any wordlist over permission keys:

- `openapi.yaml`: *"with Test Engineer holding the first and not the second"* — exactly as false,
  names neither key.
- The roles spec's own §A3 legend still said *"the qualifier still in play is `PLAN_SCOPE`"*. `V52`
  emptied that one too; **both** qualifiers are now carried by zero rows.
- `ArtifactEndpointTest` and `ArtifactFileEndpointTest`: persona javadoc describing a Test Engineer
  who holds `artifact:edit` plan-scope-only, and a class header naming
  `{@link Qualifier#engineerCannotRegisterAtProjectScope}` — **a method §210 renamed**. Two
  `@DisplayName`s still described a 403 that no longer happens.
- `DocPageEndpointTest`: a bullet resting on the same split, naming
  `{@link Gates#engineerCannotPublish}`, also renamed.
- `tool.ts`: *"`case:delete` is `OWN` for a Test Engineer"* — emptied by `V51`.

Two of those are a **stale `{@link}` to a member**, which is worth naming as a gap: §206's rule
resolves `{@code SomethingTest}` against the filesystem precisely because `{@link}` on a *type* is
checked by the compiler — but `{@link Type#member}` is a javadoc warning at most, and this build
does not run javadoc. Nothing in this repository checks them.

## The two acts, and why they are arms rather than tools

Both merges are legal **only because of `V52`**, which is the shape §211 found for `suite:delete`
arriving twice more:

| Tool | New arm | The role that would have broken the merge |
|---|---|---|
| `tcms_write_run` | `status` — `run:close` | `Automation Service` holds `run:create` without `run:close`, and is **not** a reachable intersection: `V41` made it non-assignable, and an MCP token carries `Agent`. §134 is load-bearing here for the fourth separate time. |
| `tcms_write_doc_page` | `publish` — `doc:publish` | Nobody. Before `V52`, every contributor: `doc:publish` was Lead-only while `doc:edit` is held by everyone who writes, so the merge would have hidden the whole tool from them. |

Neither is a new tool; the surface stays at **21**. Both are `kind: 'state'` and send neither header
— the target state is absolute, so there is no delta for an `If-Match` to guard and nothing for an
`Idempotency-Key` to deduplicate.

**`writeConformance.test.ts` refused both arms before they worked**, which is the check doing the
job §205 built it for: an arm with no entry in `INVOCATIONS` fails by name rather than being skipped.

## What is left of §211's list of four

Two of the four are done. The other two are unchanged and both are decisions rather than gaps:

- **Artifacts** have no surface at all — no read, no write. A delete alone would be a tool whose id
  can only come from somewhere else, which is the reviewer-assignment shape. It waits for a slice
  that gives the register a surface.
- **`library:manage`** is off the surface on its own standing argument: the control is an impact
  modal and an API caller never sees a modal.

## Files

| Path | What |
|---|---|
| `packages/client/src/test/tierClaims.test.ts` | The rule. Four corpora, two claim forms, one escape hatch (empty, pinned both ways). |
| `packages/client/src/test/roleMatrix.ts` | §A3 parsed, with the Lead-minus-Engineer delta asserted as `V52`'s seven keys so a drifting parse fails loudly. |
| `packages/client/src/test/corpus.ts` | The four roots, the walker, `proseOf`, `liveSentences`. Extracted from `citedFiles.test.ts`, which now imports them. |
| `packages/mcp/src/writes.ts` | The two arms, their gates, and the merge argument beside each. |
| `packages/mcp/src/writeConformance.test.ts` | Two `INVOCATIONS` entries. |
| `packages/mcp/src/{session,tool}.ts` | Two stale qualifier claims. |
| `docs/specs/openapi.yaml` + both `schema` copies | Six descriptions. Nothing on the wire moved. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | The §A3 legend's `PLAN_SCOPE` sentence. |
| `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` | §2's first bullet and §3 rows 9 and 17. |
| `backend/src/**` (10 files), `frontend/src/**` (6 files) | Comments and two `@DisplayName`s. No behaviour. |

## Verification

`packages/` still has no persistent verification queue (§203's finding, unchanged), so these live
here and are carried in the next slice's list.

| Row | Drive | Expect |
|---|---|---|
| `§212 PL-7` | Against a **real backend**: `tcms_write_run` `status` COMPLETED on a run with results, then `tcms_record_result` against that run, then `status` IN_PROGRESS and the same record again. | The record between them must be the contract's **409** on a closed run — read whether `result.ts` renders the sentence a model can act on ("reopen it first") rather than a bare refusal. The reopen must then let the same call through. |
| `§212 PL-8` | `tcms_write_doc_page` `publish` → PUBLISHED, then DRAFT, then PUBLISHED again on the same page. | 200 every time, including the third, which is the idempotence the contract promises and nothing here has measured. |

No browser rows and no backend-mutation rows: no Java moved, no UI moved, and the contract diff is
six `description` strings.

## What is NOT done

- **The rule reads prose, and a defect can be a sentence with no key in it.** The seven hand-found
  repairs above are the measure. A wordlist over the *concept* was considered and rejected on
  §162's ground — an ignore list naming the wrong mechanism is worse than no rule — but the honest
  statement is that this catches the cheap half.
- **Nothing checks a `{@link Type#member}`.** Two of this slice's repairs were citations of methods
  §210 had renamed, and the build has nothing to say about either. `citedFiles.test.ts` resolves
  `{@code SomethingTest}` against the filesystem; the member form needs the compiler, and
  `:compileTestJava` does not run javadoc.
- **Neither new arm has met a real server**, the same standing gap as every `packages/` slice since
  §203. `§212 PL-7` is the one to read: the 409-on-a-closed-run it drives is quoted from the
  contract, not measured.
- **The roles spec is not in the corpus, deliberately** — it is the document the rule parses, and a
  rule that read its prose against its own tables would settle a question with the answer. Its §A3
  legend is exactly where a stale claim did survive this pass until it was found by hand.
- ~~**`AGENT_KEYS` is still a hand-kept copy.** §211's rule over tool descriptions reads it, and this
  one does not; the two now answer the same question from different sources, which is a
  disagreement waiting to happen and is left as one because the honest fix is for the MCP rule to
  read `roleMatrix.ts` too, across a package boundary that currently has no test dependency.~~ →
  **DONE 2026-08-15, [§213](213-the-copy-the-spec-could-have-answered.md), and the stated reason for
  leaving it was false**: `harness.ts` has imported across that boundary since §203, twelve lines
  above the constant. The two rules read one source now. Population zero — the hand-kept 32 keys and
  §A3's Test Engineer column agreed exactly.
