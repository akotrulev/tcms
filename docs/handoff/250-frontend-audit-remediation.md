# §250 — The round-1 frontend audit, remediated

Driven by **`docs/audits/001-frontend-audit-r1.md`** (baseline, feature lane, static evidence only).
Sixteen findings: 4 major, 7 minor, 3 nit, plus 2 logical conflicts. All were addressed; two were
addressed by *recording* rather than fixing, and this file says which and why.

**The audit report is the permanent register and was not edited.** Its finding ids never renumber,
and its own closing rule binds the next round: *"Resolved is claimed only after re-reading the cited
lines that round — a green gate, a linked commit or a 'we fixed it' is a lead, not evidence."*
Nothing here claims a finding is resolved. It records what changed.

## The rule to carry

> **A suppression for a rule the linter does not run is a guard that exists only in the comment —
> and it reads exactly like a guard that works.**

Ten source files carried `// eslint-disable-next-line react-hooks/exhaustive-deps`. `.oxlintrc.json`
enabled two rules and neither was that one. So nothing was suppressed, and — the half that matters —
**no dependency-array checking ran anywhere in this project**, in a codebase with debounce timers,
autosave buffers and a documented deliberate missing dependency array (§248).

The rule exists. oxlint spells it **`react/exhaustive-deps`** and *reports* it as
`react-hooks(exhaustive-deps)`, which is precisely why the wrong name in the suppressions looked
plausible for as long as it did. The tell was available to anyone who ran `oxlint` with a deliberate
violation planted in `src/` — which is how it was confirmed here, because a clean lint run proves
nothing until you have seen the linter fail.

## The three real defects, as opposed to drift

### 1. One request per keystroke, under the wrong budget (`C1`)

`CaseSearchPage` wrote `onChange` straight to the URL, and the URL **is** the query key. Typing
*"payment gateway"* therefore issued ~14 requests against `GET /cases/search` — the app's only
company-wide, multi-project read — thirteen of whose answers were discarded on arrival. It also
took `TIMEOUTS.interactive` (10 s) although `client.ts` defines `typeahead: 3_000` for exactly this
and says why: *"a typeahead that waits 10s is broken."*

**`useDebouncedValue` (`lib/`) is now the one debounce in this project.** Three screens had inlined
the same timer, and `ConditionsPanel.tsx:211`'s comment stated the reason: *"this project has no
shared debounce hook, and extracting one would move §94's code in a slice that is not about it."*
This was the slice that was about it. All four sites now share it; the delay stays the caller's,
because a popup under the caret reads as slow at 300 ms where a grid refilling below the fold does
not.

**The URL still updates on every keystroke.** Only the request is debounced. A search that cannot be
pasted to a colleague is half a feature, and this is the one screen whose entire output gets pasted.

### 2. A key nothing could invalidate (`C8`)

`keys.caseSearch` sits outside `['projects', …]` deliberately, and that stays: a search spanning
eleven projects must not be dropped by a write in one of them. But the consequence went unnoticed —
**no invalidation reached it at all**, so a case renamed, retired or deleted stayed in a held search
result indefinitely.

The answer to *"a project write must not reach this"* is a **targeted root**, not an unreachable
key. `keys.caseSearchRoot` plus `invalidateCaseLists(client, projectId)`, which does both halves in
one function — because the pair would otherwise be spelled out at **eleven** call sites across four
modules, which is how one of them ends up updated and the other does not.

### 3. `passRate` was two units under one name (`L1`)

`TestPlanSummary.passRate` was read as a **0–1 ratio** (`formatRatio`, which ×100);
`PlanStatistics.trend[].passRate` is a **0–100 percentage** — `PlanStatisticsService:325` computes
`round(passed*1000/executed)/10` — and was read as one. Both were bare `type: number` with no unit
in the description, and every other percentage in the SPA is 0–100.

Latent **only** because `TestPlanSummary.passRate` is null on every response today
(`TestPlanView.java:23`: *"null, always, in this slice"*). The day it gains a writer, the plan list
renders it **100× wrong**, and nothing on either side has to disagree for that to happen.

Resolved at the user's direction by **unifying on 0-100**: both fields (and `coverage`, which sits
beside `passRate` and had the same silence) now declare the unit in `openapi.yaml`; `PlanListPage`
moved to `formatPercent`. Both `schema.d.ts` copies regenerated and still byte-identical.

**`formatRatio` survives and is now documented as flakiness-only** — it is genuinely right for
`FlakePanel`, whose scores are 0–1 (`bandOf`'s thresholds confirm the scale). Its doc comment had
named pass rate and coverage as its callers, which is what made the wrong call site look correct.
`CaseHistoryPage`'s inline `formatPercent(x * 100, 0)` — the duplication `format.ts` explicitly
forbids, *"mixing 0.89 and 89 into one helper is how a report ends up showing 0.9%"* — now calls it.

## The rest, briefly

- **`C2` — 29 hand-built key prefixes → six `*Root` helpers.** `casesRoot`, `conditionsRoot`,
  `artifactsRoot`, `plansRoot`, `runsRoot`, `aiProposalsRoot`. `keys.workItemRoot` had already made
  the argument for `['work-items']`. A renamed segment used to leave every hand-built copy
  typechecking, running, and silently invalidating a subtree nothing is stored under — a failure
  with no error and no empty screen, just data that stops refreshing.
- **`C3` — "Saved reports" → "My saved reports", Owner column removed.** `openapi.yaml:5568` is
  explicit: *"Yours, not the project's … otherwise indistinguishable from one that means
  everyone's."* The Owner column could only ever print the reader's own name, so it answered a
  question it appeared to ask. (The `report:view` gate on Delete was checked and **is** correct.)
- **`C4` — the empty state is three states, not two.** `projects.data?.length === 0` is `false`
  while that query is loading **and** while it has failed, so the disambiguation silently chose
  *"No case matches"* — the exact confident falsehood §249 built the screen to prevent — whenever
  the read it depends on did not succeed. `readerHasNoProjects` is now `boolean | undefined`.
- **`C5`/`D3` — two stale comments on `PlanListPage`.** Its header promised a pass-rate/coverage
  comparison that is structurally an em dash on every row; its `onCreated` comment asserted the
  plan-statistics endpoint was unimplemented five months after it shipped, and left an instruction
  that was due. Both rewritten, the second struck through with the repo's mandatory
  "→ superseded" marker. The destination did **not** move, for a second reason that outlived the
  first: a plan created a second ago has been executed zero times.
- **`C7` — filter parity.** The screen rendered lifecycle, automation and priority as columns with
  no way to filter on them (`ui-rules` §2). All three are now multi-selects in the URL. **Owner and
  tag stay unoffered and are now unoffered for a stated reason**: both need a picker over a
  population that is itself cross-project, the same argument that keeps the owner column off. So
  `isSearchable`'s `ownerId`/`tag` arms remain unreachable from any control here — recorded rather
  than half-built.
- **`C9` — results are announced now.** `useAnnouncedResults` on the case list, cross-project
  search, plan list, run list and audit trail. **Keyed on the fetch settling, not on the count
  changing**: two different filters can both match twenty rows, and a count-keyed announcement stays
  silent on exactly the change the reader asked for. The first settle is skipped — arriving on the
  page is the route announcement's to report, and saying both is how a live region becomes noise
  people switch off.
- **`C10` — the result palette means a test outcome again.** `--color-passed`/`-failed`/`-blocked`
  were doing duty for account status, overdue, priority, staleness, token liveness, automation share
  and requirement coverage. All moved to `--color-success`/`-warning`/`-danger`, which `index.css`
  already defined and labelled *"semantic feedback (distinct from status-of-a-test)"*.
  `apiTokens.ts` was the sharpest instance: its comment says *"the colour budget in `index.css` is
  spent on test outcomes, and a token's state is not one"* directly above `LIVE:
  'var(--color-passed)'`. `DocsPage`'s two genuine result colours were deliberately left.
- **`D1`/`D2`/`D4` — three documentation drifts.** `CLAUDE.md` said **MUI v7**; the installed and
  declared version is **9.2.0**, and this cache had already recorded the correction in §94 — only
  the cache got fixed, while the file every contributor and agent is told to read first stayed
  wrong. Also 1679 → 1692/125 tests, and a dead `VITE_API_MODE` in `.env.local` (deleted with the
  mock layer in §107).

## Where the audit itself was wrong

Worth carrying, because it is the shape of an audit's own drift and round 2 will re-trace from the
same report:

1. **The suppression count was ten, not eight.** `ReportsPage` and `UnlinkDialog` were missed.
2. **The colour misuse was eleven sites, not four.** The cited list is a sample, and the fe-react
   rule applies — *sweep the class, not the instance*.

## What is deliberately still open

**Twelve components have no test** (`C11`). `ConditionsPanel` — the largest, and the one carrying
the server-decided resolution order its own header warns against re-deriving — is now covered:
`ConditionsPanel.test.tsx`, 8 arms, **red-green verified** (injecting the re-sort fails four of the
eight). The remaining eleven are recorded, not written, at the user's scoping.

**`ConditionsPanel`'s arms are the ones its comments call load-bearing**, because each is invisible
to `tsc` and survives a browser pass that happens not to have the right data: the inherited group's
order, a declined row staying put rather than being removed, inherited rows carrying no
remove/reorder/drag, `leavingFolder` making every toggle inert (a decline switched on there would be
accepted by validation and then dropped by the same request, since a suite is flat since §98), and a
suite's panel having no toggle at all.

## Found while sweeping, NOT fixed — `--color-on-status` is undefined

`ReviewPanel.tsx:188` sets `color: 'var(--color-on-status)'` on a filled status chip, and **that
token is defined nowhere** — not in `:root`, not in the dark block. The declaration resolves to
nothing, so the label inherits its colour and sits on a saturated status background at whatever
contrast the inherited value happens to give. Present since §76.

Found by a computed check rather than by reading — every `var(--color-*)` reference in `src/`
compared against the definitions in `index.css` — which is the schema-wide-invariant rule in
`CLAUDE.md` applied to tokens. It is the only such reference in the app with no definition.

**Not fixed here, deliberately.** It is outside the audit, it is not caused by this slice, and the
fix is a colour decision rather than a mechanical one: the parallel `--color-on-action` flips
`#ffffff` → `#0f141b` between themes, and whether status chips want that same inversion is a
question for whoever owns the palette. Two lines in `index.css` once that is answered.

## The one genuine suppression

`LinkBlockDialog` keeps a suppression — under the **correct** rule name, with its reason recorded
per `ep-code-comments`. `intendedCase?.id` is deliberately narrower than `intendedCase`: a parent
that rebuilds the object each render would re-run the effect and discard the picker selection the
reader just made, and the id is what actually decides whether a different case is being carried in.

The other nine took the real fix. `useMutation`'s returned object is a fresh literal every render
(`{...result, mutate, reset}`), so the old comments were **right** that depending on it would reset
on every render — but `MutationObserver.bindMethods()` binds `reset` **once in the constructor**, so
`const { reset } = remove` is stable and the dependency is free. That distinction is only visible if
you read the library source, which is the argument for turning the rule on rather than trusting ten
comments that all said the same reassuring thing.

## Gates

**Green on `shanks`, all three stacks.** Frontend **1705 / 1705** across 127 files measured (from 1692/125 — `useDebouncedValue`'s 5
arms and `ConditionsPanel`'s 8). `useAnnouncedResults`' 5 arms landed after that run and are
locally green; the re-measure is pending, `npm run build` clean,
`npm run lint` **0 warnings across 354 files with 104 rules**. That rule count is the evidence worth
quoting: it was 103, and the extra one is `react/exhaustive-deps`. Packages **341** (130 client /
82 cli / 129 mcp) and build clean, because `openapi.yaml` changed and both `schema.d.ts` copies were
regenerated — still byte-identical, verified by md5.

`ConditionsPanel.test.tsx` was **red-green verified**, not merely observed to pass: injecting the
re-sort its own header warns against fails four of the eight arms.

## Verification

`docs/verification/frontend-browser-checks.md` gained **nine rows, `§250 AR-1` … `AR-9`**, under
§65's deferred-and-batched policy. They are the population the automated gates structurally cannot
see: **a request that was never made, a live region nobody hears, and a colour whose meaning
changed.** `AR-3` is the only row needing a fault armed after load.

No backend mutation rows: this slice mutates nothing server-side. The `openapi.yaml` change is a
description-only edit — no field added, removed, or retyped.
