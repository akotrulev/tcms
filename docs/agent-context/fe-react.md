<!-- orientation-cache v1 -->

# fe-react — orientation cache (TCMS)

**Last verified:** 2026-08-05
**Baseline commit:** `7f3f8c3` (§95 phase 3) **+ §95 phase 4, the commit this line ships in** (its
own SHA cannot be written into itself; per `CLAUDE.md`, trust the body over the header). Two headers
ago this read `6980bfb` while HEAD was three slices further on — the drift the warning below
describes, and it has now been caught twice running.
**Fingerprint files:** `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`,
`frontend/vite.config.ts`

> Authoritative docs win over this file. `CLAUDE.md`, `docs/HANDOFF.md` (an index — sections live in
> `docs/handoff/NN-slug.md`), and the specs in `CLAUDE.md`'s precedence table. This is a map for
> re-orienting fast, not a source of truth.

## Version lane

- **React 19 + TypeScript strict**, **Vite 8** SPA. **No Next.js, no RSC** — so there is no
  `'use client'` boundary anywhere and the skills' RSC lane does not apply. `#confirmed`
- ~~**MUI v7**~~ → **MUI v9** (`@mui/material` 9.2.0, verified against `node_modules` in §94; both
  this file and `CLAUDE.md` still said v7). The slot rules below are unchanged by the bump, and one
  thing v9 makes concrete: `Autocomplete`'s `renderInput` params carry **`slotProps.htmlInput`**, so
  a testid on the inner input is `slotProps={{ ...params.slotProps, htmlInput: {
  ...params.slotProps.htmlInput, 'data-testid': … } }}` — spreading `params` alone lands nothing on
  the element `userEvent.type` drives. With X DataGrid / TreeView, **React Router 7**,
  **TanStack Query v5**.
- **`@dnd-kit` since §94** (`core` 6.3 · `sortable` 10.0 · `utilities` 3.2 · `modifiers`) — the
  first runtime dependency added since the scaffold. One consumer: the case editor's step list.
- Package manager **npm**. `erasableSyntaxOnly` is on, which forbids TS constructor parameter
  properties — see `api/client.ts:35` for the recorded workaround. `#confirmed`

## Build, run & test

- `npm run dev` — MSW mock mode by default, **no backend needed**; sign in with any 4+ char password.
- **`npm run build` is THE gate** (`tsc -b` + vite build). **Do not trust `tsc --noEmit`** — it has
  reported 0 errors while the build failed with nine (working agreement). `#confirmed`
- `npm run lint` — oxlint, must stay at **0 warnings**; it prints nothing when clean.
- `npm run test:run` — Vitest + RTL. **831/831** at §96 phase 5 (805 at §94; 770 at §91, 783 before this
  slice). **`testTimeout` is 30s since §67 — the second raise for one cause.** The suite has a load-dependent cliff: adding five tests anywhere pushed `DocPageEditorPage`'s two `userEvent.type` tests over the old 20s budget, in a file that slice never touched, always as *Test timed out* and never an assertion. The real fix (those tests type sentences character-by-character; `paste` asserts the same thing) is named in `vitest.config.ts` and not done. `#confirmed`
- `npm run gen:api` — regenerate `src/api/schema.d.ts` after **any** `openapi.yaml` change. Runs
  through `npx` on purpose (the scaffold pins TS 6, the tool peer-requires 5.x); do not "fix" it.
- **Live mode:** `VITE_API_MODE=live` in `.env.local` proxies `/api` to `localhost:8080`.
  **After changing `.env.local`, kill vite and `rm -rf node_modules/.vite`** — an in-place restart
  leaves a stale optimize-deps hash and the page renders **blank with an empty console**. Hit and
  confirmed in §61's live pass; the remedy works. `#confirmed`

## Layout

`src/{api,app,auth,components,features,lib,mocks,notifications,a11y,test}`. Feature folders under
`features/<area>/`; routes are project-scoped under `/projects/:projectId/*` (`app/router.tsx`, lazy).
**Four routes are NOT** — `/` (project list), `/admin/teams` (company), `/my-work` (§83, the
cross-project personal board) and `/teams/:teamId/board` (§85, the team board). A screen whose
subject is not a project does not live under one. **`/projects/:projectId/reports` (§89) lands on
the other side of that test**: a report is computed over one project's facts and its own header
says *"covers X in full and no other project"*, so switching projects changes what it is about.
**`/projects/:projectId/reports/jobs/:jobId` (§90) is the report itself** — a route rather than a
dialog because a report is read and referred back to, and because a job id in the address bar is the
only durable handle a report has while nothing lists jobs.

**`/` finally has an affordance (§91).** It is where sign-in lands and where an unreachable project
falls back to, and until §91 **nothing in the app linked to it** — the one screen every session
starts on and no screen pointed at. It now has a rail item (`ALL_PROJECTS`, in the ungrouped block
directly above the switcher) and the wordmark links there too.

> **⚠ This file went FIVE SLICES STALE (§72–§76) and was repaired in §77.** Only the backend half of
> the update-the-cache habit had stuck. If you are reading this and the baseline lags HEAD by more
> than a slice, the seam list below is a lead and not evidence — re-grep anything that decides your
> slice's shape. `docs/handoff/00-plugin-skills-are-mandatory.md` step 0 now states the protocol.

## Seams to reuse

- **A CASE MAY BE FILED NOWHERE, AND TAGS ARE AUTHORABLE (§96 phase 5).**
  `caseSchema.ts`: `suiteId` lost its `.min(1)` — **`''` IS THE UNFILED VALUE**, not a missing one
  (MUI's `Select` treats `null` as uncontrolled and warns), and the submit maps it to **`null`**
  because `''` is a 400 on a uuid field. New `tags: z.array(z.string().max(60))`.
  `CaseEditorPage`: a **Not filed** `MenuItem` with an explicit label rather than a blank first row
  (a blank reads as a field somebody forgot), and a `freeSolo multiple` `Autocomplete` for tags with
  **no options list** — there is no tag vocabulary anywhere, and offering a curated set is what left
  `shared_step` empty. Tags are trimmed and de-duplicated **in the control and again at submit**.
  `CaseGrid` renders `suiteName === null` as muted italic **"Not filed"**, never blank — a blank
  cell reads as a suite that failed to load.
  `useCaseFilters`/`CaseFilters`: **`tag` (repeats, ANDs)** and **`unfiled` (boolean, written only
  when true)**. `?unfiled=false` parses as *absent* — otherwise it counts as an active filter that
  narrows nothing and the empty state claims a filter that does not exist. The **Not filed** chip is
  **disabled beside a suite filter** with the reason on hover (§31), because the server ignores the
  pair and an active-looking chip that changed nothing is the worse half of render-then-reject.
  The tag filter box commits on **Enter and is not debounced** like the search box: a half-typed tag
  is not a narrower filter, it is an exact match against nothing.
  Mock: `tags` left the refused list in the same commit, `suiteId: null` is accepted, and a tag
  change deliberately **does not bump the version** — mirroring the server's decision.
- **THE CONDITION PANELS, AND THE INHERITED ROW THAT MUST NOT BE SUBMITTED (§95 phase 3).**
  `features/cases/ConditionsPanel.tsx` (one component, `kind="PRE"|"POST"` — two would be
  `ui-rules` §8's "hundred slightly different pages" in miniature) + `ConditionEditDialog.tsx` +
  `lib/conditions.ts` (`impactSummary`) + `useCreateCondition`/`useUpdateCondition` in
  `api/queries/library.ts`. `useConditions` gained an **`enabled`** parameter and finally has
  consumers — it had **zero call sites** from the day it was written.
  **An attachment is a REFERENCE, and that is the design and the hazard.** Picking stores the id;
  editing the entry later changes every case and suite attaching it, with **no prompt and no fork**
  — the deliberate opposite of §94's step corpus, where a pick copies text. Hence the dialog states
  the blast radius *before* the save, its button reads **"Change everywhere"** and is danger-styled
  (`ui-rules` §4), and no fork is offered (the fork is what turned `shared_step` into
  near-duplicates nobody could rank).
  **`impactSummary` is in `lib/` with its own test because THE TWO COUNTS MUST NEVER BE ADDED.**
  `usageCount` is cases attaching directly; `suiteUsageCount` is suites, each propagating to every
  case beneath. A suite's case reach is **not computable on this side** — the server declines to
  publish it (an `ltree` walk the library read cannot express) — so blending them would be a guess
  presented as a measurement, understating in the one direction an impact warning must not. The
  test uses 4 and 3 and asserts **7 never appears**.
  **THE most dangerous line is `ownConditionIds`.** `TestCaseDetail.preconditions` carries the
  FOLDER's conditions too, marked `inherited`, and they render almost identically to an own row.
  Seeding one into the form attaches the folder's condition **directly** to the case on the first
  save — permanently, invisibly, on every case anybody edits. Inherited rows are passed to the panel
  as a separate read-only prop with no remove, reorder or drag. **Not covered by a test yet**; a
  mutation row says so.
  **Both lists are always sent, including as `[]`** — absent means "leave them alone" to the server,
  so detaching the last condition would report success and change nothing. `classificationValueIds`'
  rule, one field over.
  Reordering reuses §94's `resolveStepMove` and keeps the **▲▼ buttons** beside the drag handle.
  **Two findings from running it:** a created entry rendered as *"Unknown condition"* until a
  library list refetched (the panel now takes `known` — the entries created this session — as a
  rendering fallback), and **the second library read per panel pushed `CaseEditorPage.test.tsx` over
  the suite's load cliff at 640s on one test**; the resolver read is now gated on
  `value.length > 0`, which is also the right product behaviour. Bundle **+8.9 kB raw / +2.1 kB gz
  (+0.54%)**, entirely in the lazy route chunk. `#confirmed`

- **THE STEP EDITOR, AND THE RULE A PERMANENT BLANK ROW BREAKS (§94).**
  `features/cases/CaseEditorPage.tsx` — the action field is an **`Autocomplete` (`freeSolo`)** over
  `useStepSuggestions` (`api/queries/library.ts`, typeahead budget, `enabled` on a non-blank
  fragment, debounced **250ms per row** in `StepActionField`, which exists as a component because a
  hook cannot be called inside `steps.fields.map`). A pick **copies** the whole triple into action,
  testData and expectedResult and creates **no link** — the endpoint serves no id, which is what
  keeps it that way, and it is the deliberate opposite of `SharedStepPicker` beside it.
  **Typing in the last row appends the next one, so `Steps.AddButton` is GONE** (a deleted locator,
  not just a control). Three things follow and each was a live defect:
  **(1)** `CaseSchema`'s "at least one step" counts **`liveSteps`**, never `steps.length` — the
  trailing blank row satisfies the old rule, so an empty case saved clean with a success toast;
  **(2)** `StepSchema.action` lost its `.min(1)`, which would have refused every save on that same
  row, and became a cross-field rule at `steps.N.action` (*a row carrying anything must carry an
  action*); **(3)** removing the trailing row was a **dead end** with no Add button —
  `ensureTrailingBlank` holds the invariant and the shared-step insert lands *before* the blank.
  **`liveSteps` has ONE definition and two consumers** (the refine, and the submit's trim-then-drop)
  because the two halves of their disagreement are individually invisible.
  **`errors.steps.root?.message`, not `errors.steps.message`** — a refinement whose `path` is a
  field array lands on `.root`, and reading only `.message` had rendered the step panel's error as
  nothing at all since Phase 4: the form did not respond to the button and said nothing.
  Drag-and-drop is `DndContext` + `SortableContext` + a `SortableStep` **render-prop wrapper** (the
  row's JSX stays put, so a reordering change does not read as a rewrite); **the ▲▼ buttons are
  KEPT** as the keyboard/SR/touch path and both ends call the same `steps.move`.
  **`stepReorder.ts` is its own module on purpose**: the three ways a drag ends in nothing (outside
  the list, back where it started, on a stale id) are the only part of DnD that can be *wrong*, and
  they are unreachable from a component test — `move(i, i)` marks the form dirty and offers a new
  version of an unchanged case. Bundle cost measured, **+2.9% total and +0.03 kB on the entry
  chunk**: all of it lands in the lazy route. `#confirmed`
- **THE PLAN DOCUMENT TAB (§93).** `features/plans/PlanDocumentPanel.tsx` +
  `usePlanDocument`/`useStartPlanDocument`/`useLinkBlock`/`useUnlinkBlock` +
  `useCreateDraftCase` + `keys.planDocument`; `PlanStatisticsPage` gained **Tabs** with the selection
  in the URL (`?tab=document`), the two halves **unmounted rather than hidden**.
  **`usePlanDocument` turns the server's 404 into `null`** — a plan with no document is the ordinary
  first state, and letting it reach the query client raises a global "Something didn't load" on a
  screen whose correct rendering is an invitation.
  **`blocksOf` is the ONE place an edit payload is built**, because the field that must never be
  dropped is `id`: a block sent without one is deleted and recreated and every link to it is
  destroyed silently.
  **`useCreateDraftCase` exists because `useCreateCase` holds ONE idempotency key per mount.** That
  is right for an editor (one intent, one case) and catastrophic for a bar somebody adds ten cases
  from — every case after the first would come back as the first, silently. A key per **submit**.
  **The "Test cases" section is found by HEADING TEXT** (`CASE_SECTION`), the screen's known weak
  point; renaming it moves new cases to the end of the document with an info alert rather than
  losing them.
  **Adding a case is THREE requests and the order is load-bearing**: case, bullet, link — the case
  first because it is the durable half, and the failure message says the case may exist anyway.
  **No component tests.** `#confirmed`

- **`ReviewPanel` carries TWO permission tiers and they are different keys (§79).** The comment box
  and Reply ask **`case:comment`** for *every* subject type — the catalogue has one comment key where
  it has three review keys, because commenting is the bottom tier §6b built for the client who
  reviews coverage without write access — while **Resolve** asks the subject's own
  `REVIEW_PERMISSION[subjectType]`, as the verdict buttons do. **Do not "simplify" the two into
  one:** a Viewer must be able to write and unable to resolve, and the panel's tests hold a
  permission set the seeded catalogue cannot produce, which is the only place that pair is
  observable (§71's finding from the good side). Both controls are **disabled, never hidden** (§31).
  A **reply carries no Reply button** — the server refuses a reply to a reply, so rendering one would
  be an affordance the product does not have — and a **closed review** renders the conversation with
  no write affordance at all. `#confirmed`
- **`ReviewPanel` renders ASKED and SPOKE as two lists and they must not be merged (§80).**
  `assignees` is who was *asked* (§6g's reviewer assignment); `participations` is who *spoke*.
  Merging them is wrong in both directions: somebody may give a verdict without being asked, and a
  request nobody has answered — the row a lead opens this panel to find — would render as "a person
  with no verdict", which is also what somebody never asked looks like. Both the chips and the picker
  ask the subject's `REVIEW_PERMISSION`, **not** `case:comment`: the bottom tier does not fill other
  people's queues. The picker is built from `useProjectMembers`, a **subset** of the server's rule
  (a Company Admin is assignable and not offered) — narrower never produces a 400, wider would be a
  form a user fills in correctly and cannot submit. `useAssignReviewer` takes
  `{userId} | {teamId}` as a **union**, so the server's neither/both field error is unrepresentable.
  `#confirmed`
- **A barrier on a MUI Select's test id is NOT a barrier (§80).** `Review.Assign.Choice` sits on the
  Select's **display `div`** via `selectTestId`, which is not a form element — so
  `expect(...).toBeEnabled()` passes immediately and the click lands while `useCan` is still pending
  and the control still disabled. Four tests failed this way on their first run (§66's finding, §71's
  hidden-input trap, one line). **Barrier on something derived from the resolved gate instead**:
  `useProjectMembers` is `enabled` on the gate's `allowed`, so the `/members` request firing at all
  is the proof. Same family: MUI's default Chip delete icon has **no identifier**, so pass
  `deleteIcon` explicitly when a test must click it. `#confirmed`
- **A fixture factory must NOT end `as <ContractType>` (§79, and it paid off in §80).** Adding
  `assignees` to `Review` was **one build error in one place**, where §79's cast turned eleven tests
  red at runtime with zero type errors. `ReviewPanel.test.tsx`'s did, so
  adding a required field to `Review` reddened all eleven of its tests **at runtime** with **zero
  type errors**. §65's finding (five files mocking `/me/permissions` through `as never`) in a second
  file. Declare the return type and let the object be checked, so the next contract change is one
  build failure rather than a page of confusing runtime ones. `#confirmed`
- **`useProjectSettings(projectId)` gates §6g's whole reviewer UI, and it is read by ARTIFACT screens
  rather than only by the settings page (§77).** §6g's opening requirement is that a project which
  never turns review on shows *"no extra UI at all"*, so `ReviewPanel` reads the settings and
  **returns `null`** when review is off for its subject type — the check lives **in the panel**, not
  at its three call sites, so a fourth screen that mounts it cannot forget. The endpoint is gated on
  **project visibility, not `project:settings`**, precisely so a Viewer's client can make that
  decision. `reviewEnabledKeys` is `satisfies Record<ReviewSubjectType, keyof ProjectSettings>`, so a
  fourth subject type is a compile error rather than a silently hidden panel.
  **While the settings are pending the panel renders nothing** — deliberately *not* §65's
  "not yet is not no" rule, which is about permissions: a panel that flashes in and vanishes is worse
  than one arriving a beat late, and a failed settings read is not evidence review is on. `#confirmed`
- **§6g's GATING rules are a THIRD panel on `ReviewSettingsPage`, and deliberately not two more
  switches under "Where review applies" (§82).** They bind on a project with review switched off,
  because §6g's opt-in suppresses the reviewer apparatus and not the status ladder — grouping them
  with the opt-ins would tell an admin the opposite in the one place they decide whether to turn
  them on. **The save is the risk, not the switches**: the endpoint is a `PUT` of all eight values
  onto a server that reads an absent boolean as `false`, so a field listed on `ProjectSettings` and
  omitted from the submit does not fail — it **switches that setting off on the next save of the
  form**, with a success message. `ReviewSettingsPage.test.tsx` (**new in §82; §77 shipped the
  screen with no test file at all**) asserts the body as an **exact object**, because
  `toMatchObject` passes with a field silently dropped. The mock's `saveProjectSettings` takes the
  whole value, which is how `npm run build` caught the handler forgetting both. `#confirmed`
- **A gating refusal is a 409 that must OFFER THE WAY OUT (§82).** `RunCreateDialog` and
  `PlanStatisticsPage` each render the server's sentence in place and put §6g's lead override beside
  it — `ui-rules`: an error naming an action the UI does not offer is a design bug. **Offered only
  AFTER the refusal, never on the happy path**, so a standing "ignore the approval rules" checkbox
  does not normalize the exception §6g wants to stay exceptional. **Disabled, not hidden** (§31) for
  a caller without `case:approve`/`plan:approve`, so the person who has to go and ask can see the
  way out exists. The run dialog keys on **`error.status === 409`** (the only 409 that endpoint
  answers — everything else there is a field error); the plan page keys on **the requested target**
  being `ACTIVE`, never on matching the server's sentence, which would be a second copy of the rule
  in a string. The retry **re-validates the form** rather than replaying the first attempt's values.
  `#confirmed`
- **The status ladders and the review apparatus are separate on the client too.** Turning review off
  hides the panel and changes **nothing** about the Draft/In review/Approved controls on the suite
  tree, plan header or bulk dialog. `#confirmed`

- **A FILE DOWNLOAD CANNOT BE AN `<a href>` IN THIS APP, AND THE FAILURE IS SILENT (§89).** The
  session is a bearer token in a header — `SecurityConfiguration` is `STATELESS` with **no cookie
  anywhere** — so an anchor pointed at `/api/v1/…` sends no credential and answers **401** while
  rendering as a working link. `api.get` cannot take a server-supplied URL either: `request`
  prepends `BASE` and `ReportJob.downloadUrl` already carries `/api/v1` (the **server** composes it,
  so the client holds no second copy of the routing — §67 on `Evidence.url`), giving
  `/api/v1/api/v1/…`; and `request` ends in `res.json()`, which parses a Markdown report as JSON.
  **`api/client.ts`'s `fetchArtifact`** is the seam — URL verbatim, blob + `Content-Disposition`
  filename, long budget — and **`lib/download.ts`** (`saveBlob`, `reportFilename`) is the DOM half,
  split so the transport is testable without a document. Its **same-origin guard is not defensive
  programming**: the URL is server data and the caller's token goes with it, so an absolute one
  hands the token to another host — **and it has no test** (a queued mutation row says so). **The
  sibling defect is live and unfixed**: `EvidencePanel` renders `Evidence.url` as a plain anchor,
  so every evidence download is a 401 in live mode; the fix is a different rendering (evidence is
  served `inline`, a report `attachment`), not this helper. `#confirmed`
- **The reports screen shows what THIS TAB asked for, because nothing lists jobs (§89).** The
  pipeline's whole surface is `POST /reports/generate` + `GET /reports/jobs/{jobId}`; job ids live
  in `ReportsPage` component state and a reload loses the links while the reports and artifacts
  still exist. **Do not "fix" this with job ids in the URL or in storage** — a list endpoint is the
  server's, and a client-side register is a second drifting copy of a table that already exists
  (§81). `useReportJob`'s `refetchInterval` returns **`false` on COMPLETED *or* FAILED**, through the
  exported `isJobFinished` the cards read too: polling on `!== 'COMPLETED'` never stops on a failure
  and renders as a report permanently about to be ready. `useGenerateReport` **seeds the 202 into
  the cache** (`setQueryData`), so the new card shows `Queued` rather than a skeleton for a row the
  client already holds. The download button keys on **`downloadUrl` being present, never on the
  status** — they agree today, and the URL is the one that cannot produce a broken link. `#confirmed`
- **The format picker offers PDF on purpose, and the type picker offers one report on purpose
  (§89) — opposite reasons, both worth keeping.** `format` is a closed contract enum offered **in
  full including the format every deployment refuses**, because *"the enum is not narrowed to what
  is installed: what a deployment can render is a property of that deployment, and the refusal names
  it"* — a client-side list of renderers is wrong in both directions, and the **400 field error was
  engineered to land on the picker** naming what the server can produce. `type` is an **open string**
  in the contract, so the dialog offers only the set it can **build a parameter form for**; the run
  picker IS `run_summary`'s `parameters`, and `runId` is validated **inside the worker**, so omitting
  it buys a job that spins and then fails. `parameters` is `additionalProperties: true`, which means
  **neither the generated types nor the contract checks can see a `runId` sent at the top level** —
  the dialog's test asserts the body with `toEqual`, not `toMatchObject`, for exactly that. Rail
  item **ungated** (a recorded departure from the plan): `V3` grants `report:view` to five of six
  built-ins and the sixth is API-token only, so the gate would refuse nobody. `#confirmed`
- **The mock can now produce a FAILED job, and that is deliberate infrastructure (§89).**
  `mocks/handlers/reporting.ts`'s `advance` resolves `parameters.runId` against `db.runs` and fails
  with the worker's sentence when it cannot — the server's own arrangement (report parameters are
  worker-validated), and the only way `FAILED` is reachable without a backend. Before it, the state
  the M2.5 plan names as this screen's characteristic defect could only be seen in production.
  *"Build the fault switch before the feature it proves"*, applied to a state machine. `#confirmed`
- **A poll test is vacuous unless the wait exceeds the interval, and the pair is what proves it
  (§89).** `ReportJobCard.test.tsx`'s first draft waited 250ms against a 2000ms interval and passed
  against a poller that never stopped. It is now **one test with two halves** — a RUNNING job whose
  request count must *increase* over 1.5 intervals, a FAILED one whose count must *not* — with the
  interval named and the wait derived from it. Real time, not fake timers: TanStack's interval,
  `userEvent`'s advance-on-act and `waitFor`'s scheduler all read the same clock. `#confirmed`

- **THE STORED REPORT MODEL IS THE ONE SHAPE IN THIS CLIENT WITH NO GENERATED TYPE (§90).** The
  artifact route serves five formats' bytes and is declared **binary** in `openapi.yaml`, so
  `schema.d.ts` cannot describe this body — the contract carries the shape in **prose** on
  `ReportJob.modelUrl` and the authority is `ReportDocument.java` + `ReportModel.java`. It is
  therefore **parsed with Zod in `lib/reportDocument.ts`, never cast**: a cast puts the drift three
  components deep, where a half-recognised document renders as a report with **no sections** — which
  is exactly what a selection that matched nothing looks like. The two directions are deliberate and
  opposite: **unknown fields are dropped** (an artifact outlives the deployment that wrote it, so a
  *newer* release's document must still open) and **missing or mistyped known fields throw**
  (`data-fetching`: a parse failure is an error, never an emptiness). A cell arriving as a **number**
  throws too — `ReportModel`'s "every cell is a STRING" held at the only place this client can hold
  it. `headerBlock()` reassembles the server's provenance→selection→metadata block **as one
  function**, because this screen is the fourth renderer of it and the furthest away; both tests over
  it assert an **exact ordered list**, since `toContain` per label reddens nothing when a line goes
  missing. `#confirmed`
- **THE ON-SCREEN REPORT VIEW COSTS `report:export`, AND THAT IS A FINDING (§90).** The model is an
  artifact, so it is served through the **download** route, which gates on `report:export` — while
  §6e's split (and the custom role §87 built a whole phase to express) is *may read on screen, may
  not carry off*. A `report:view`-only persona can watch a job reach `Ready` and be refused the
  report it watched being made. **Not papered over**: `ReportViewPage` branches on `status === 403`
  and says the on-screen view needs the download's key, because `describeError`'s generic 403 makes a
  design defect read as an ordinary permission error. The fix is the **server's** — serve the `JSON`
  artifact under `report:view`, or give the view its own route. Live-only (mock mode holds every
  key); browser row 31. `#confirmed`
- **`ReportView` computes NOTHING, a recorded departure from the plan's F2 line (§90).** It says to
  reuse `lib/results.ts`; there is no arithmetic, because every cell and timestamp is pre-formatted
  so that "what you see is what exports" is a property rather than a promise. **Do not add a
  percentage to this screen** — it would be §65's "two contradictory percentages" across two formats
  of one report, where nobody can see both at once to notice. The one number is the bar width
  (`barPercents`), carrying `HtmlReportRenderer`'s two defects: scaled to the largest value in its
  **own series** and never a total, and an **all-zero series draws no bar** rather than the
  full-width one `width: NaN%` renders as. `#confirmed`
- **`fetchArtifactJson` is the model's transport, beside `fetchArtifact`, through one
  `artifactResponse` (§90).** The same-origin guard is why they share: it is the only thing between a
  server-supplied string and the user's bearer token. **§89 shipped it untested and said so**;
  `api/artifacts.test.ts` now drives **both** readers over five refused URL shapes, and the pair is
  the point — a guard tested on one would leave the newer reader, the one that runs *without a
  click*, unprotected. `DownloadReportButton` is the extracted download (one authenticated download,
  two call sites — `ui-rules` §8); its failure testid is `<testId>.Error`. `#confirmed`

- **TanStack Query owns all server state**; no server data in any client store. `src/api/queries/` is
  the data module — one `keys` factory (`queries/keys.ts`), per-endpoint timeout budgets. `#confirmed`
- **Client state:** session in Context; **filter state belongs in the URL** (`useSearchParams`).
  *Known deviation:* `features/artifacts/ArtifactsPage.tsx` keeps its plan filter in `useState`, so a
  plan's register cannot be linked to — recorded in handoff §61, not fixed. `#confirmed`
- **`app/Providers.tsx` nesting order is load-bearing** and documented in the file: the query client
  is constructed *innermost* because its global error handlers consume the notification and
  session-recovery providers.
- **`components/PermissionGate.tsx`** is the one place a lacked permission becomes a **disabled
  control with a why-tooltip** (§31, disable-don't-hide). Render prop, not `cloneElement`; the denied
  branch wraps in a span because MUI kills pointer events on a disabled control. **It gates on a
  permission KEY only.** ~~`/me/permissions` carries no qualifiers, so a scope- or author-restricted
  grant reads as fully held (§61)~~ → **superseded in §65: the response is now `[{key, qualifier}]`.**
  `PermissionGate` still asks the key alone, which is right for it — a *qualified* affordance asks
  `useCan(projectId).canOnScope(key, rowIsPlanScoped)` in the component body, mirroring the server's
  `canDoOnScope` arm for arm rather than reinterpreting the qualifier. `Gate.pending` is exposed for
  the one thing the fail-closed verdicts cannot express: **"not yet" is not "no"**, and using a
  pending verdict to *act* (rather than to disable) demotes every caller during the load — which is
  exactly what shipped into §65's first draft and what two unrelated tests caught.
  **Mocking `/me/permissions` in a test: use `src/test/permissions.ts`'s `heldPermissions()`.**
  The spies cast through `as never`, so a response-shape change breaks them with **zero type
  errors**, surfacing as `pointer-events: none` three assertions later. `#confirmed`
- **It must NOT be used inside a MUI `Select`** — `Select`
  clones its *direct* children to inject `role="option"`, `data-value` and the selection handler, so
  a wrapped `MenuItem` renders as an inert `role="menuitem"` that cannot be chosen at all, and
  carries no `aria-disabled` either, so a testid-based test reads it as "enabled". Inside a `Select`,
  read `useCan(projectId)` in the component body and pass a plain `disabled` prop
  (`features/cases/BulkActionDialog.tsx` sets the pattern). Cost the whole approve feature in §62,
  with every gate green. `#confirmed`
- **`api.upload` is the multipart seam** (`api/client.ts`), separate from `api.post` on purpose:
  a `FormData` body must **not** carry a hand-set `Content-Type` — the browser generates the
  boundary, and setting the header loses it, after which the server cannot parse a single part and
  answers 400 for a request that looks correct in devtools. `request` now omits the JSON
  content-type when `init.body instanceof FormData`. Uploads default to the **long** timeout
  budget, not `interactive`. `#confirmed`
- **`EvidencePanel`** (`features/runs/`) is the second author-qualified call site after
  `CaseDetailPage`, and the pattern to copy: `authorId={item.createdBy?.id ?? null}` — **`?? null`,
  never `?? undefined`**, since `PermissionGate` switches on `!== undefined` and `undefined` means
  "not author-qualified", which hands every row the unqualified verdict. Its test uses a
  `permissionsResolved()` barrier for §66's reason: `useCan` is fail-closed while pending, so
  `waitFor(toBeDisabled)` is satisfied by the loading state and proves nothing. `#confirmed`
- **Forms:** react-hook-form + zod via `zodResolver`; each dialog owns an `applyServerError` that maps
  the RFC-7807 `errors` map onto its fields and falls back to a root alert via `describeError`.
  `mode: 'onBlur'`; **focus on open, never `reset(defaults)`** — a reset keyed on an object literal
  re-runs each render and wipes a failed submit's errors. `#confirmed`
- **The MUI slot spelling is `slotProps`, and getting it wrong has now cost FOUR slices** (§71
  extracted `lib/muiTestIds.ts` to end it, §74 recorded a third occurrence via `PlanCreateDialog`,
  §77 a fourth). `muiTestIds` owns the *shape* of the object; **the prop name is the part it cannot
  own**, which is the whole gap — a file written from an older sibling reintroduces `inputProps=` and
  MUI v7 rejects it. `Switch`/`Checkbox` → `slotProps={inputTestId(id)}`; `TextField` →
  `slotProps={{ htmlInput: { 'data-testid': … } }}`; `Select` →
  `SelectDisplayProps={selectTestId(id)}` or `slotProps={{ select: { SelectDisplayProps: … } }}`.
  **Caught by `npm run build`, never by the test runner.** `#confirmed`
- **`data-testid` goes on the INPUT, not the wrapper**: `slotProps={{ htmlInput: { 'data-testid': … } }}`.
  Putting it on the `TextField` lands it on the root div and `userEvent.type` silently does nothing —
  cost six tests failing for one reason in §61. Tests reach a MUI select's combobox via
  `.closest('.MuiInputBase-root')` then `getByRole('combobox')`. `#confirmed`
- **Errors are centralized:** `lib/describeError.ts` (copy + `isTransient()`), `app/queryClient.ts`
  (the single raise point; opt out per-call with `meta: { handled: true }`),
  `components/ErrorBoundaries.tsx` (root / route / region tiers). `DataRegion` owns the
  loading/loaded/failed/empty/stale matrix.
- **Shared display layer, not per-page:** `lib/labels.ts` (each `satisfies Record<Union,string>`, so a
  new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`,
  `lib/permissions.ts`, `Identifier` for monospace, `ScrollX` for wide content.
- **Mock API is stateful** — `mocks/db/` + `mocks/handlers/*.ts` read *and write* a cloned fixture
  store, so round-trips land in lists. Handlers are written from the **server's** rules, not from what
  one client sends (§52). Resets on reload.
  **And a mock generator can invent a whole feature — §81, the most expensive instance so far.**
  `mocks/db/workItems.ts` drew a board card for every case whose `lifecycleStatus` was `IN_REVIEW`,
  in the `IN_REVIEW` column, assigned to the case's **owner** — while the server produced no review
  cards at all and, once it did, refuses the artifact's own submitter as a reviewer. Mock mode
  therefore showed a reviewer inbox no deployment could produce, to the wrong person, from Phase 1 to
  §80, and it looked finished. Nothing could catch it: `kind: REVIEW` is legitimately in the contract
  (put there in §53 ahead of its generator), and a page test supplies its own props. Hence
  **`mocks/db/workItems.test.ts`, the first test asserting the MOCK's rules rather than a
  component's** — the pattern to copy whenever a mock module holds *logic* rather than storage.
  **It caught a second defect in the same module in §83.** `db.runs`, `db.cases` and `db.plans`
  carry **no `projectId`** — the contract puts the project in the *path* for all of those endpoints,
  so the wire shapes have no such field and neither do the fixtures. The execution and maintenance
  generators therefore drew the whole seeded repository onto **whichever project's board was asked
  for**, stamped with that project: already a 404-on-its-key on the second project's board, and on
  the cross-project board the same card twice with the **same React key**. `workItems()` now gates
  both on `projectId === db.projects[0].id` — the repository belongs to the project that seeds it,
  said once rather than inferred per generator. `personalBoard(userId)` mirrors
  `BoardService.personalBoard`: the visible set is `db.projects` (**the same list `GET /projects`
  answers**, one source so the two cannot disagree), and the personal filter runs **after** assembly
  for the server's reason. `GET /work-items` mirrors both refusals — **400** for projectless +
  `mine=false`, **404** for a project id nobody can see (it used to fall back to `projects[0]`, so a
  stale bookmark rendered another project's board under a foreign id). The fixture seeds **one task
  in the second project assigned to the current user**, without which My Work in mock mode is
  byte-identical to the first project's personal board (§72's rule).
  **And it caught the FIXTURE in §85, which is a third kind of finding from the same file.**
  `teamBoard(teamId, projectId?)` mirrors the server (team contributes only a set of assignees;
  membership expanded at read time, **direct members only**; filter after assembly; unknown team →
  `undefined` = 404, no members → `[]`; the empty-member shortcut **inside** the resolved team and
  **after** the handler's project gate, or "empty team + unreachable project" becomes a 200 that
  confirms the project exists). `personalBoard` and `teamBoard` share one **`assemble()`** — the one
  place the visible project set is named (§58's E1). The hole it exposed: every fixture card was
  assigned to `users[0]` or `users[1]`, **both in Payments Squad**, so Checkout Squad's board was
  empty and an implementation ignoring membership — or quietly answering the personal board — passed
  everything. The fixture now seeds **one OLB task to Priya Nair (`users[2]`, Checkout Squad, not the
  signed-in user)** and the assertions come as a **pair**, each half stopping the other being vacuous.
  ***What mock mode structurally cannot prove:*** `db.projects` **is** the caller's visible set, so
  the reader-bound — the whole of §84's decision — is structural here, not demonstrated. Live only.
  `#confirmed`
- **`BoardPage.linkFor` is where a card's key goes, and it keys on `sourceType` (§81) and on the
  CARD's project (§83).** Every card routed to a case search until the inbox drew the first cards
  about **suites and plans**; a review of `TP-5` sent there renders "no cases match", which reads as
  the plan having been deleted. A case goes to the case detail, a plan to the plan detail, a **suite
  to the case list filtered to it** — a suite has no page of its own and is the one §6g subject type
  with no screen anywhere. `EXECUTION` keeps the search fallback deliberately: its route needs a run
  id the card does not carry. **The project comes from `item.projectId`, never from the route** —
  §81 left it as the board's own, which is the same value on any project board and therefore a
  defect no test could see until §83's cross-project board existed. All four of §81's link tests
  still pass against the old signature; only `MyWorkPage.test.tsx` reddens. `#confirmed`
- **`Board` takes NO project and NO `mine` (§83) — it renders whatever cards it is handed.** That is
  what let §6l's cross-project **My Work** screen (`features/board/MyWorkPage.tsx`, route
  `/my-work`) reuse it rather than grow a second board, per `ui-rules` §8. `crossProject` is
  presentation only: it stamps each card with its `projectKey`, which is noise on a board that is
  already one project's. The screen deliberately has **no Everyone chip** (the server answers **400**
  to a projectless board without `mine=true` — "Choose a board", refused rather than narrowed) and
  **no New task** (a create needs a project it does not have). It never 404s; `[]` is the normal
  state of a member with no grants. Rail item sits **above the project switcher**, the only one that
  renders with no project resolved. `#confirmed`
- **§6l's THREE boards are three screens over ONE `Board` and ONE operation (§85 completes them).**
  `/projects/:projectId/board` (§53) · `/my-work` (§83) · **`/teams/:teamId/board` (§85)**.
  `projectId` narrows *where*; `teamId` and `mine` narrow *whose*; the response shape and sort are
  identical, which is why there is no second component and no second path. **The team board's route
  is deliberately NOT under `/admin/teams`**: every affordance on that screen is gated on
  `company:teams` and **the team board is gated on nothing at all** — §6k makes the org chart
  readable by any company member, and §84 declined to invent a key (a key seeded to nobody is a 403
  nobody can grant out of). Filing a screen every member may open under a prefix reading
  "administration" states the opposite of what is true. Same reason the **Board** link on
  `TeamsPage`'s rows sits **outside** the `PermissionGate` wrapping Members/Edit/Delete rather than
  inside it as a fourth button — that one line of JSX *is* the gating decision, and the mistake
  compiles and renders identically. **The cards are bounded by the READER's project grants, so two
  people see different boards** — therefore **no count, capacity figure or percentage may be
  rendered** on that screen; a number from a partial view reads as canonical and is wrong for
  everybody but its reader. `useTeamBoard` sends **only** `teamId`: `teamId`+`mine=true` is a 400
  (and is *unobservable in the body* for a caller on the team, so a chip would look like a filter
  that silently did nothing), and `teamId`+`projectId` is legal — §6l's **swimlanes by member**,
  a layout not a filter, answered by the mock and built by no screen. **The 404 is worded by the
  CLIENT**: the server's is empty-bodied on purpose (§6t), so `ApiError.detail` falls back to the
  status text and `describeError` renders "Not found — Not Found". `TeamBoardPage` keys on
  `status === 404` above the region — safe *there* because the hook sends no `projectId`, so the
  other 404 arm is unreachable — and keeps it visibly distinct from `200 []`, which is a reachable
  empty board. The team **name** comes from `GET /teams` (declares no 403) and is **not** in a
  `DataRegion`: a board titled "Team board" because the org chart failed beats one that will not
  render. `#confirmed`
- **Every board key shares the `work-items` root, and the move/create hooks invalidate the ROOT
  (§83).** The same card is on both boards, so keying an invalidation to the board you were looking
  at leaves the other holding a card in the column it just left. `useMoveWorkItem()` takes no
  arguments (`PATCH /work-items/{id}` names no project either); `useCreateWorkItem(projectId)` lost
  its `mine` — which had also meant a task created under the "Everyone" filter left the `mine=true`
  cache stale. `keys.myWork` is `['work-items','ALL',true]`; `'ALL'` cannot collide with a project id.
- **Contract-hygiene tests are generated per call site** — `api/contractOperations.test.ts`,
  `contractParameters.test.ts` (drives the TS compiler, not a regex), `apiResponseTypes.test.ts`
  (refuses an inline literal at any `api.*` call site). A new endpoint is covered automatically,
  **which means the suite's test count moves when you add one** — an unexplained change in the total
  is a report about these checks, and chasing one in §70 is how the next note was found. ~~A new
  endpoint is covered automatically~~ was true only of the verbs the checks knew: both hard-coded
  `['get','post','patch','put','delete']` *"as spelled on the client"*, and the client spells it
  **`del`**, with an `upload` that was absent entirely — so every delete in the app and the evidence
  upload, **eight call sites, were never checked** from §53 to §70. The verb set is now derived from
  `client.ts` by `api/contractIntrospection.ts` (`apiMethods`, mapping `del`→`delete` and
  `upload`→`post` because path items are keyed by the OpenAPI spelling), and `unresolvedApiVerbs`
  makes an unreadable verb **fail** rather than narrow the check. **Do not re-introduce a literal
  verb list**, and note that the vacuity guards in both files are by *count* — they cannot see a
  whole category going missing, which is exactly what happened. `#confirmed`
- **Fault injection** (dev only, browser console): `tcmsFaults.status/hang/expire/drift/only/off`.
  Faults live in module state, so arm them *after* load.
- **The rail has TWO widths since §91, and the drawer has only one.** `RailContent` takes
  `collapsed` + `onToggleCollapse`; the permanent rail (232px ↔ **64px**, `RAIL_WIDTH_COLLAPSED`)
  passes both, the small-screen `Drawer` passes **neither** and is always expanded — collapsing a
  temporary drawer saves nothing and takes the labels from the users with the least room to guess at
  icons. Collapsed, `RailLink` drops the label text and **must** carry `aria-label` (an icon-only
  link with no name is announced as its href; the `Tooltip` is a hover affordance and reaches no
  screen reader), group headings become `<Divider>`s, and the switcher becomes an `IconButton`
  showing the project's **key** plus a `Menu` — the key, not a folder icon, because *which project
  am I in* is what a 64px rail is likeliest to drop. The preference is `safeStorage`
  (`tcms.nav.railCollapsed`), read in the **initial `useState`** and not an effect, or the rail
  renders expanded for one frame and snaps shut on every page load. `Nav.Rail` carries
  `data-collapsed`. `#confirmed`
- **`AppShell.test.tsx` exists as of §91 — the shell's first test file.** Rendering the shell needs
  `TestProviders` with a **`userId`** (the `TopBar` calls `useAuth`, which throws without a
  provider), and — the trap — a `waitFor` on the **project group** (`Nav.Link.Testcases`), never on
  `Nav.ProjectSwitcher` or the ungrouped items: those three render *before* `/projects` answers, so
  waiting on one returns while `groups` is still `[]` and every project-scoped assertion fails on a
  rail that was merely early. `#confirmed`

## Conventions observed

- Comments carry the **why**, and often the failure mode a rule prevents — unusually dense and
  load-bearing. Match that register.
- Test files state, in a header, the specific failures they exist to catch, and are **mutation-checked
  rather than watched to pass**.
- MUI `theme` + `sx` as the single styling authority. **No Tailwind.** Tokens are CSS custom
  properties in `index.css` bridged into `createTheme`.

## Known unknowns

- E2E / page objects are the `qa-ui-typescript` peer's; none exist here.
- ~~**`/reports` is the only dead contract left on this side**~~ → **built in §89 (F1).** The
  screen, the generate dialog and the polling card exist; ~~**F2 (the on-screen report view) is the
  outstanding slice**~~ → **built in §90**, ahead of R6 rather than after it. **R5 has not built the
  definition writer**, so `GET /reports` answers `[]` on every deployment and the panel's empty
  state says so; there is deliberately **no Save control** (`ui-rules`: no Add button whose endpoint
  does not exist). **There is no operation that lists jobs**, which is the screen's largest
  limitation — see the seam below.
- Lighthouse / Core-Web-Vitals never measured (handoff §5); `CaseListPage` is over its route budget
  with the reasoning recorded in `vite.config.ts`.

## Session log

- 2026-08-05 · EXTEND · **Conditions get a writer** (§95, phases 1–4) — the library that could be
  read, ranked and attached and never created. Backend: `POST`/`PUT /projects/{id}/conditions`
  (`library:manage`, `If-Match`, 409) and the case's `preconditions`/`postconditions` finally
  accepted after refusing them with a 400 since M1. Frontend: `ConditionsPanel` ×2 +
  `ConditionEditDialog` + `lib/conditions.ts` + two write hooks + the mock's writers and its
  `applyConditions` reconcile. **One contract change, `gen:api` run.**
  The findings were **numbers**: `condition.usage_count` read its `DEFAULT 0` on every row in every
  deployment and was one slice from becoming an impact warning, so both counts are derived now — and
  deliberately **two numbers that are never added**, which answers a design question `/shared-steps`
  had posed and left open. Plus `lock_version` mapped **with `@Version`** from the first writer
  (§74's trap, prevented), and a bulk-hash trap that would have credited the next ordinary save with
  restoring conditions a `MOVE_TO_SUITE` stripped.
  Backend 1704 → **1715**; frontend 805 → **820/820 across 55 files**; build clean, lint 0.
  **Built and unverified** — 12 browser rows (62–73, two live-only) and 9 mutation rows queued,
  **zero run**. Three of the mutation rows are tests that do not exist yet, and the
  `ownConditionIds` one guards the slice's most dangerous defect.

- 2026-08-05 · EXTEND · **Writing steps** (§94 phases 2–3) — the case editor's step list reworked
  around the corpus §94 phase 1 built. New: `CaseEditorPage.test.tsx` (**the screen's first test
  file** after eight slices — 12 tests), `stepReorder.ts` + its 5, `useStepSuggestions` +
  `keys.stepSuggestions`, `StepSuggestion` in `types.ts`, the mock's `stepCorpus`, and `@dnd-kit`.
  **No contract change, no `gen:api`, no new permission key, no backend file touched.** The slice's
  own risk was the schema and it was named in advance: auto-append makes a blank row permanent, so
  "at least one step" had to stop counting `steps.length` in the *same commit* — see the seam.
  **Two pre-existing defects fell out of writing the first test**: the step panel's error alert had
  never rendered (`errors.steps.root`), and removing the trailing row became a dead end the moment
  the Add button went. Bundle measured before and after per the working agreement: **+46.2 kB raw /
  +14.8 kB gz (+2.9%), entirely in the lazy route chunk**, entry unchanged. Vitest 783 → **805**;
  build clean, lint 0. **Built and unverified** — browser rows 51–61 queued, **zero run**, two of
  them live-only and they are the ones that carry the corpus's real risk (mock fixtures give every
  structured case the same four steps).

- 2026-08-04 · EXTEND · §6e's **on-screen report view** (§90, F2) — the stored model rendered, so the
  screen and the exported file are one computation twice. New:
  `features/reports/{ReportViewPage,ReportView,DownloadReportButton}.tsx` + two test files, route
  `/projects/:projectId/reports/jobs/:jobId`, `lib/reportDocument.ts` (Zod parse + `headerBlock` +
  `barPercents`) + its test, `api/client.ts`'s `fetchArtifactJson`/`artifactResponse` +
  `api/artifacts.test.ts`, `useReportModel` + `keys.reportModel`, and a **View report** link on the
  job card. **No contract change, no `gen:api`, no new key, no backend file touched.** The finding is
  a **permission**: reading a report on screen costs `report:export`, contradicting the split §87
  built two custom roles for — worded on the page, fixed by nobody yet. See the seams above.
  723 → 749 → **765/765**; build clean, lint 0. **Built and unverified** — 8 browser rows (26–33) and
  6 frontend mutation rows queued, zero run inline; §89's untested-guard mutation row is struck
  through, closed here.

- 2026-08-04 · EXTEND · §6e's **Reports screen** (§89, F1) — the first client §87's pipeline has
  had. New: `features/reports/{ReportsPage,GenerateReportDialog,ReportJobCard}.tsx` + three test
  files, route `/projects/:projectId/reports`, an **ungated** rail item under Execution,
  `api/client.ts`'s `fetchArtifact`, `lib/download.ts`, three hooks + two keys in
  `api/queries/reporting.ts`, `ReportFormat`/`ReportJob*` in `types.ts`, four label maps, and the
  mock's FAILED path. **No contract change, no `gen:api`, no new key, no backend file touched.**
  The slice's finding is that **neither an `<a href>` nor `api.get` can download an artifact**, and
  both fail silently — see the first seam above. Two tests were repaired before they meant anything
  (a vacuous poll wait, a one-directional gate). 705 → 711 → **723/723**; build clean, lint 0.
  **Built and unverified** — 12 browser rows and 6 frontend mutation rows queued, zero run inline.

- 2026-08-04 · EXTEND · §6l's **team board** screen (§85) — §84's frontend half, and the slice that
  gives §6l's three boards three screens over one `Board` and one operation. New:
  `features/board/TeamBoardPage.tsx` + its test, route **`/teams/:teamId/board`** (**beside**
  `/admin/teams`, not beneath it — see the seam), the ungated **Board** link on every `TeamsPage`
  row, `useTeamBoard` + `keys.teamBoard`, and the mock's `teamBoard` + both of §84's refusals.
  `npm run gen:api` first — §84 moved `openapi.yaml` and left `schema.d.ts` stale.
  **No new permission key, no gate, no rail item, no contract change.** `linkFor` was **verified,
  not assumed** — the named risk of the slice, correct here precisely *because* §83 removed the route
  parameter rather than passing the right one down (with one, this screen would emit
  `/projects//cases/…`, having no project at all). The finding was the **fixture**: §84's own hole,
  one layer up — see the mock seam. And `npm run build` caught what the runner could not: a helper's
  `map(item => item.assignee?.id)` gave a `Set<string | undefined>` comparing happily against a
  `string[]`, twelve green tests and two type errors. 20 tests written (12 `TeamBoardPage`, 7
  `mocks/db/workItems`, 1 `TeamsPage`); 682 → **705**, the other 3 being §70's contract checks
  generating cases for the new `api.get` call site unattended. **0 mutations run inline** (policy);
  **25 browser rows** queued (six live-only — and the live-only ones are the ones that matter, since
  the reader-bound is unreachable in mock mode by construction), **6 frontend mutation rows**.

- 2026-08-04 · EXTEND · §6l's cross-project "My Work" board (§83) — the bound §81 named and left
  behind. New: `features/board/MyWorkPage.tsx` + its test, route `/my-work` (**outside the project
  segment** — see Layout), the rail item above the project switcher, `useMyWork` + `keys.myWork`,
  and the mock's `personalBoard` + both of `GET /work-items`'s refusals. `npm run gen:api` first —
  `projectId` is optional now. **Two repairs came out of it, both invisible before this screen
  existed**: `linkFor` took the *board's* project (identical to the card's on any project board, so
  all four of §81's link tests pass against the defect), and the mock's execution/maintenance
  generators drew the whole seeded repository onto every project's board (a 404-on-its-key on the
  second project, a duplicate React key on the aggregate). `Board` lost `projectId` and `mine`
  entirely and is now the one board both screens render — `ui-rules` §8, rather than a second
  implementation. Invalidation moved to the `work-items` root, which also fixed a create under the
  "Everyone" filter leaving the `mine=true` cache stale. 19 tests written (12 `MyWorkPage`, 7
  `mocks/db/workItems`); 662 → **682**, the extra one being §70's contract checks generating a case
  for the new `api.get` call site unattended. **0 mutations run inline** (policy); 19 browser rows
  queued (three live-only), 5 frontend mutation rows.

- 2026-08-04 · EXTEND · §6g's gating rules (§82) — the two approval gates get a settings panel and
  two override affordances. New: the `ReviewSettings.Gating` panel + its warning,
  `Runs.Create.OverrideGate`, `PlanStats.OverrideGate`, `SetPlanStatus` (the hook now takes
  `{status, overrideApprovalGate}`), `overrideApprovalGate` on `TestRunInput`, both rules in the
  mock (`handlers/runs.ts`, `handlers/plans.ts`) mirrored from the server including the
  resolved-set, refuse-the-whole-run, after-validation and not-ANDed-with-the-opt-in properties, and
  **`ReviewSettingsPage.test.tsx`, which did not exist** — §77 shipped that screen untested.
  **`npm run build` caught the mock's settings handler dropping both new fields**, which is the same
  silent-discard class the form itself carries. Gating stays **off in the mock's fixtures**: turning
  it on would make every run and plan start in mock mode fail until somebody approved the fixtures,
  which demos the refusal rather than the product. 13 tests written; 649 → **662**. **0 mutations
  run inline** (policy); 15 browser rows queued, three of them live-only.

- 2026-08-03 · EXTEND · The reviewer's inbox (§81) — §6l's review card lands on the board, so a
  request finally surfaces outside `ReviewPanel`. Frontend work was **three repairs, not a feature**:
  the mock's invented review generator replaced by the server's rules (see the seam above),
  `BoardPage.linkFor` keyed on `sourceType`, and the derived-card refusal sentence keyed on `kind`
  (it called every non-execution card "a review"). Plus §79's cast repair on
  `BoardPage.test.tsx`'s factory, and one fixture review on `TC-20` so mock mode shows more than one
  subject type. **No contract change and no new hook.** 9 tests written (5 board, 4 the new
  `mocks/db/workItems.test.ts`), **649/649**. **0 mutations run inline**; 2 frontend rows queued in
  the mutation file, 11 browser rows — and §80's browser row 14 is struck through, because it
  recorded this absence as correct.

- 2026-08-02 · EXTEND · Reviewer assignment (§80) — §6g's *"assign a user or a team"* lands in
  `ReviewPanel` as an **Asked to review** section above the verdict trail. New: `useAssignReviewer`,
  `useUnassignReviewer`, `Assignees`/`AssignForm`, `ReviewAssignee` + `TeamRef` types, the mock's
  `assignReviewer`/`unassignReviewer` and two handlers, and three fixture requests on the demo plan's
  open review (a person who also spoke, a **team**, and a person who has said nothing) beside a review
  with **none** — §72's rule, both shapes present. **Asked and spoke are two lists**; see the seam
  above. **The findings were both in the tests**: four failed because `toBeEnabled()` on a MUI Select
  display div is satisfied while the gate is pending, and a fifth because MUI's default Chip delete
  icon carries no test id. A doc comment also claimed the picker filtered the submitter when it did
  not — implemented rather than deleted, because a picker that can produce a 400 is the one thing it
  must not be. 9 tests written (20 → 29 in that file), **640/640** overall — the other 4 are §70's
  data-driven contract checks generating cases for the two new `api.*` call sites unattended.
  **0 mutations run inline**, 14 browser rows queued (**rows 3 and 4 are live-only and are the ones
  the slice turns on**).
- 2026-08-02 · EXTEND · Review comments (§79) — §6g's threads land in `ReviewPanel`. New:
  `useAddReviewComment`, `useSetReviewCommentResolution`, `Threads`/`Thread`/`CommentBody`/
  `CommentForm` in `ReviewPanel.tsx`, `ReviewComment` + `ReviewCommentInput` types, the mock's
  `addComment`/`threadRoot`/`commentExists`/`setResolution` and its two new handlers, and two
  fixture threads on the demo plan's open review (one with a reply, one resolved) beside a review
  with **none** — §72's rule, both shapes present. **The two affordances cost two different keys**;
  see the seam above. **The finding was a cast**: the test fixture's `as Review` hid a new required
  field from the compiler and turned eleven tests red at runtime. The panel's empty trail also stopped
  saying *"Nobody has commented yet"*, wording that named a feature two slices before it existed.
  7 tests written (13 → 20 in that file), 627/627 overall; **0 mutations run inline**, 13 browser rows
  queued.
- 2026-08-02 · EXTEND · Per-project review settings (§77) — §6g's four settings get a screen and the
  review panel gets an off switch. New: `features/settings/ReviewSettingsPage.tsx`,
  `useProjectSettings`/`useUpdateProjectSettings`, `keys.projectSettings`,
  `editAfterApprovalLabels`/`Descriptions`, the `/projects/:id/settings/review` route and its rail
  item, and the mock's `settingsForProject`/`saveProjectSettings` + both handlers.
  **`ReviewPanel` now returns `null` when review is off** — which reddened all 11 of its tests at
  once, because their `mockReads` fallthrough answered `[]` and `[].reviewEnabledPlan` is
  `undefined`. That is the feature working; the fixture moved to the enabled state and the
  default-off case got **its own test** rather than remaining a fallthrough's accident.
  The mock seeds the **demo project on and every other project off**, deliberately: both are states a
  real instance produces, and having only one is §72's fixture rule in one direction or the other.
  **The MUI slot trap fired a fourth time** (`inputProps` vs v7's `slotProps`), caught by
  `npm run build`. 2 tests written, 11 repaired; 616/616.
- 2026-08-02 · FIX · The verb the contract checks could not see (§70). Chasing §69's four
  unaccounted-for tests found that `contractOperations.test.ts` and `apiResponseTypes.test.ts` each
  hard-coded the client's verbs and spelled its `del` as `delete`, omitting `upload` — so **eight
  call sites had never been checked by either**. The set is derived from `client.ts` now
  (`contractIntrospection.apiMethods`); `apiResponseTypes` also stopped building a second
  `ts.createProgram` over the same sources. Both mutations run inline: unmapped spelling → exactly 8
  red, an unreadable verb → exactly 2 (both vacuity guards, which previously stayed silent).
  All 16 newly generated cases pass — the blind spot was empty this time. **No runtime code
  changed**, so no browser row is owed; the queue records that. 534/534. `#confirmed`
- 2026-08-02 · EXTEND · The classification vocabulary editor (§69) — new route
  `/projects/:projectId/settings/classification` (`features/settings/`), **Classification** in the
  rail's Project group, three new hooks in `api/queries/projects.ts`. **Every gated control reads
  the dimension's `locked` flag to choose WHICH permission to ask, at which scope** —
  `company:vocabulary` via `useCanInCompany` for the company's axes, `project:settings` via
  `useCan` for the project's Feature tree — so `PermissionGate`'s `projectId="company"` literal has
  its second consumer after the project picker. Reuses §68's `buildValueTree`, where the
  orphan-surfacing matters in mirror image: **a value nothing renders is one nobody can retire.**
  The mock's `dimensions` moved from the fixture into `db` and `handlers/cases.ts` moved with it,
  or a value added in the editor is refused by the case editor as "not in the vocabulary".
  **The test failed for §66's reason before it failed for a real one**: rendered bare instead of
  through a `<Route>`, `projectId` is `''`, `useCan` is disabled and permanently pending, and every
  "disabled" assertion passes with no gate in the code at all. Render page tests through a `Route`
  and hold a `permissionsResolved()` barrier. `#confirmed`
- 2026-08-02 · EXTEND · The classification picker (§68) — the last of §5's three scheduled Phase-2
  gaps, and the largest. New: `features/cases/ClassificationPicker.tsx` + its test,
  `lib/classification.ts` (`buildValueTree`) + its test. `caseSchema` gained
  `classificationValueIds`; `CaseEditorPage`'s Classification panel replaces read-only feature chips
  with a control per dimension, inside its own `DataRegion` so a failed vocabulary read leaves the
  rest of the editor saveable. **The control shape is derived from `cardinality` + `hierarchical`,
  never from the dimension key**, so a fifth dimension needs no code. **The read-only comment's
  justification was false**: no leaf-only rule exists in any spec, and a parent IS assignable.
  `buildValueTree` lives in `lib/` because exporting a function beside a component defeats Fast
  Refresh (the `caseSchema.ts` trade) and it **surfaces orphans AND cycle members at the root**
  where `SuiteTree` drops cycle members — in a picker, a value with no control on screen is silently
  deselected by the next save. Mock now **enforces** the server's three rules rather than storing
  what it is given, and `buildCaseDetail` DERIVES its ids from the summary's names so no fixture can
  carry ids and names that disagree. §62's lesson again in the test: a bare `data-testid` on a MUI
  `Checkbox` lands on the wrapper span where `toBeChecked` cannot see it — testids moved to the
  `input` slot. Two mutations run, 3 red each. **Browser pass DEFERRED**; rows 8–10 are live-only
  (the mock has no retired values). 506/506.
- 2026-08-02 · EXTEND · The evidence UI (§67). New: `features/runs/EvidencePanel.tsx` + its test,
  `useAttachEvidence`/`useDeleteEvidence`/`MAX_EVIDENCE_BYTES` in `api/queries/runs.ts`, and
  **`api.upload`** in `api/client.ts` (see the seam above — the Content-Type rule is the one that
  would have failed silently). Mock: `handlers/runs.ts` gained all three evidence routes and
  **models the `OWN` refusal**, reading `createdBy` exactly as the server does; `db/executions.ts`
  seeds three evidence rows per FAILED execution whose authors **disagree** with `executedBy`, one
  of them with a null author. **Contract defect found by `tsc`**: `Evidence.createdBy` was
  non-nullable while the null author is the whole `OWN` arm — now `allOf` + `nullable`.
  Mutation-verified: deleting `authorId` from the JSX reddens exactly the two authorship tests.
  `testTimeout` raised to 30s — see Build, run & test.
- 2026-08-02 · EXTEND · Consumed the §65 qualifier wire shape. `useCan` gains `canOnScope` /
  `reasonOnScope` / `pending`; `ArtifactDialogs` disables the PROJECT scope option for a
  `PLAN_SCOPE` grant (disabled on the `MenuItem`, never wrapped — §62's rule). New shared
  `src/test/permissions.ts`. Mock handlers gained `holdsOn` / `qualifierOn` and the fixture carries
  the *qualified* grants, so mock mode cannot show a control live mode disables. **Browser pass
  DEFERRED** under the new standing policy — queued in `docs/verification/frontend-browser-checks.md`;
  §65 is built and not browser-verified. 470/470.

- 2026-08-02 · EXTEND · Built `SharedStepPicker` (§64) — the first consumer `useSharedSteps` has ever
  had, reached from the case editor's step list, plus `useCreateSharedStep`. Inserting sets the
  step's `action` to the entry's TITLE and its `sharedStepId` to the id: a shared block is a
  reference resolved on read, so copying the body in yields a step that looks identical and stops
  tracking its source. The picker is **not** permission-gated (reading needs only the project
  grant); the inline create is, on `library:manage`. **Two mock defects**: the shared-step fixtures
  carried usageCount 48/33/91 while no fixture case referenced any entry — the handler now derives
  the count through `caseDetail`, since a summary row carries no steps — and `GET /shared-steps`
  alone did not `requireAuth`. A fixture case now references an entry, so the detail page's "Shared"
  chip is drawable in mock mode for the first time. **Harness trap**: `PermissionGate` swaps the DOM
  node between its denied (tooltip-wrapped) and allowed (bare) branches, so a held reference never
  becomes enabled — re-query inside `waitFor`; and since the gate is fail-closed while
  `/me/permissions` is in flight, a "disabled" assertion is only non-vacuous when paired with an
  enabling one. `#confirmed`
- 2026-08-02 · EXTEND · Extended `BulkActionDialog` to all eight bulk actions (§62), typed
  `BulkInput`/`payload` from the contract (both were `string`/`Record<string,unknown>`), and brought
  the MSW handler into agreement with the new server — it had **dropped unresolvable ids** so the
  three buckets did not add up to `total`, and answered "not supported yet" for six actions. Added
  `BulkActionDialog.test.tsx` (13 tests). **The live pass found the approve option was unselectable**
  because `PermissionGate` wrapped a `Select` child — see the seam note; tests now assert through
  `role="option"` and count options against `lifecycleLabels` rather than reading a testid.
- 2026-08-02 · EXTEND · Added the artifact register's write half (§61): `useCreateArtifact`,
  `features/artifacts/ArtifactDialogs.tsx` (scope × kind cross-field form) + its 6 tests, the MSW
  `POST /artifacts` handler mirroring the PLAN_SCOPE qualifier, and the `docPageId` link that makes a
  documentation artifact reachable. Cache created this run.
- 2026-08-04 · EXTEND · §91 — gave `/` a way in and the rail a way closed. `ALL_PROJECTS` rail item
  + linked wordmark (the picker had **no affordance at all** since the app began: sign-in lands
  there, an unreachable project falls back there, and nothing linked to it), and an icon-only
  collapsed rail persisted through `safeStorage`. Extracted `ProjectSwitcher` out of `RailContent`
  to carry both widths. Added `AppShell.test.tsx`, the shell's first tests (5). Gate: build clean,
  lint 0, **770/770 across 52 files**. **A comment was written wrong and corrected before landing**:
  it claimed `end: true` on a `to="/"` item is load-bearing against a prefix match on every route —
  `NavLink` already refuses that case, the test stays green without it, and the comment and the test
  doc now both say so rather than implying a guard that is not there. Browser rows 34–39 queued,
  **not run** (§65).
