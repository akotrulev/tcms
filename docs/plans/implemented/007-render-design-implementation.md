# 007 — Implementing the renders: Tailwind + shadcn/ui, all 42 screens

**Status (filed 2026-10-05):** Delivered — T1–T17 landed; T17 `91c4bd6a` ran all gates green on shanks and measured the bundle (§4.1). Unverified: the browser pass (T15 step 3, §4.1's last section) was never completed. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-08-29 · **Written by:** `ep-writing-plans` · **Status:** ~~proposed, nothing
implemented~~ → **T1–T6, T8 and T9 landed**
(`5e14f336`, `8c21107a`, `2dceb911`, `8b78a4f9`, `8127551a` + `5a4d7226`, T6, T9 `6de8c9a9`, T8),
the rest open. Next up: **T7**.

> **Grounded 2026-08-29** against the working tree after T4. Where a task below was written from an
> assumption the code disproved, the original text is struck through and the correction follows it
> (D4). The counts in §1 were re-measured; the ones that moved are marked.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

> **Gates run once, at the end — not per task (user directive, 2026-08-29).** ~~Every task ends with
> `npm run build && npm run lint && npm run test:run`.~~ → superseded. T6–T16 **do not run the build,
> the lint or the test suite**; each one writes its code and commits. The single gate pass is **T17**,
> after all of T6–T16 are on `master`, and it runs on `shanks` per CLAUDE.md. Two consequences the
> next reader should not rediscover the hard way: (a) a task's own "done when" `grep` checks stay —
> they cost nothing and are not a gate; (b) the layout damage Tailwind's preflight causes in T16 now
> surfaces in **T17** rather than in T16, so T17 is a gate pass *and* the fix-up for it. Anyone who
> wants a fast local check mid-task may run a single test file, but the full three-command gate is
> T17's alone.

**Goal:** Make the product look like `docs/specs/assets/renders/`, by replacing MUI with Tailwind v4
+ shadcn/ui and porting all 42 screens onto the nine-component kit the design-language spec defines.

**Architecture:** The renders are not a mood board — they are executable HTML with a shared
stylesheet (`assets/renders/tcms.css`, 367 lines) and a written rulebook
([`2026-08-28-design-language.md`](../../specs/2026-08-28-design-language.md)). That spec's §1 already
maps every kit token to an app token and concludes *"the port is a mapping, not a translation"* —
verified true below. So the work is not a re-theme; it is (a) a styling-authority swap from MUI's
`theme`+`sx` to Tailwind, (b) building the nine-component kit once, and (c) porting each screen onto
it, family by family, in the order the renders are grouped.

**Tech stack:** Tailwind v4 (`@tailwindcss/vite`) · shadcn/ui on Radix · TanStack Table (headless) ·
`sonner` for notifications · React 19 + Vite 8 + TS strict, all unchanged.

---

## 0. Why this plan exists, and what it is answering

A screenshot of `CaseEditorPage` in the *New test case* state was compared against the origin render
([`assets/case-authoring-redesign.html`](../../specs/assets/case-authoring-redesign.html) frame B). The
information architecture had been implemented; the design had not. Seven differences, each traceable
to a rule in a spec that was written before the code:

| # | The render / spec says | What ships |
|---|---|---|
| 1 | The document is a bare paper column; rule 1, *"no card wrapping a table, no panel around the only thing on screen"* | Every region is a bordered `Panel` — *Start*, *The case*, *This case* |
| 2 | Three start **cards**, each with an explanatory sentence and a keyboard hint (`start typing`, `⌘O`, `⌘V`) | Three plain buttons (`CaseEditorPage.tsx:345`–`:372`) |
| 3 | A `+ tag` row, a ghost title lead, the subtitle *"One sentence stating what is verified"*, a rule, then a numbered scaffold with a ghost hint per keyword | A textarea with `placeholder="Given …\nWhen …\nThen …"` (`CaseDocument.tsx:280`) |
| 4 | Rail rows are controls but **quiet at rest** — no filled input backgrounds, values right-aligned, borders on hover and focus (rail spec, *What this does to the visual balance*) | Full outlined `Select`s with permanent borders and a persistent helper paragraph each |
| 5 | The procedure is 15px sans at 1.75 with only the keyword in coloured mono | The whole document is mono at `0.8125rem` (`CaseDocument.tsx:70`) |
| 6 | *"Both fields go"* / *"Description dies"* (redesign render, notes) | The *Notes* panel still carries **Expected outcome** and **Description** (`CaseEditorPage.tsx:1086`, `:1095`) |
| 7 | Commit bar in the top strip beside the breadcrumb; no page `<h1>` | A `PageHeader` H1 plus a footer commit bar |

Items 1, 4 and 5 are not local to that screen — they are the kit, and they are wrong on all 42.
That is why this is a design-system plan and not a bug fix. **Item 6 is the one item deliberately
deferred** — see §4.

## 1. The measurement this plan rests on

Taken on `master` at `5933ca27`, before any change:

| | |
|---|---|
| Non-test components | 148 `.tsx`, ~86k LOC of `.ts`/`.tsx` |
| Files importing `@mui` | 156 (`@mui/material` 156 · `@mui/icons-material` ~~115~~ **41** — re-measured; the 115 was a grep over `@mui` lines, not files) |
| Test files | ~~100~~ **143** `*.test.ts(x)` (the 100 counted `.test.tsx` only) |
| Files coupled to MUI **internals** | ~~**8** — `lib/muiTestIds.ts` and 7 consumers~~ → **14**: the lib, **13 importers** of `inputTestId`/`selectTestId`, and **two path citations** in comments (`features/runs/BlockedDialog.tsx:105`, `components/ui/ui.test.tsx:20`) |
| `@mui/x-data-grid` | **1 file** — `features/cases/CaseGrid.tsx` |
| `@mui/x-tree-view` | ~~**2 files** — `CaseGrid.tsx`, `features/cases/ClassificationPicker.tsx`~~ → **1 file**, `ClassificationPicker.tsx`; `CaseGrid.tsx` does not import it |
| `@mui/x-date-pickers` | **0 files** — a declared dependency nothing imports (re-confirmed) |
| `notistack` | **14 files** — `app/Providers.tsx`, **6 pages** calling `useSnackbar` (`BoardPage`, `CaseListPage`, `CaseEditorPage`, `DocPageEditorPage`, `RunDetailPage`, `ExecutionPage`), **6 tests** that wrap `SnackbarProvider`, and one comment — *not* one provider line; see T8 |
| Routes | 9 `<Route>` elements; 44 `*Page.tsx` — **two of which the design language's family map (§8) does not list**: `settings/TrackerSettingsPage` and `account/TrackerConnectionsPage`. T15 takes both. |
| The design language itself | `tcms.css`, 367 lines; the spec, 9 sections |

Two of those numbers decide the shape of the plan. **Tests query by `data-testid` and role, not by
MUI class** — only 14 files reach into MUI internals — so a faithful primitive swap keeps the
existing suite meaningful instead of rewriting 143 test files. And **the MUI X packages are two
files**, so the expensive-looking DataGrid/TreeView replacements are two contained tasks, not a
theme running through the whole tree.

## 2. The token port is a mapping — verified, not assumed

The design-language spec §1 claims the render kit is *"the app's dark theme, tightened"*. Checked
against `frontend/src/index.css:114` (`[data-mui-color-scheme='dark']`):

| Kit | App dark | Verdict |
|---|---|---|
| `--steel` `#6ba8c4` | `--color-action-primary` `#6ba8c4` | **identical** |
| `--panel` `#141a23` | `--color-surface` `#151b24` | near-identical, kit wins |
| `--paper` `#11161e` | `--color-surface-sunken` `#0f141b` | near-identical, kit wins |
| `--ink` `#e6ecf3` | `--color-text` `#e8edf3` | near-identical, kit wins |
| `--ink-2` `#9aa8ba` | `--color-text-muted` `#9aa8ba` | **identical** |
| `--failed` `#e07568` | `--color-failed` `#e07568` | **identical** |
| `--ground` `#0a0e14`, `--field` `#0e141c` | *(absent)* | **new — two tokens to add** |
| `--ink-3` / `--ink-4` | `--color-text-faint` (one token) | **split one into two** |
| `--passed` `#7ec8a0` | `--color-passed` `#5aab84` | **changed** — kit hue, per rule 5 |
| `--blocked` `#e0b95f` | `--color-blocked` `#d9a441` | **changed** — kit hue, per rule 5 |
| `--retest` `#8fa9e0` | `--color-retest` `#a58ad6` | **changed** — purple → blue, per rule 5 |

Rule 5 (*"one palette serves keywords and results"*) is what moves `retest` off purple: `Then` and
`Retest` must be the same blue, or the editor and the runner teach two meanings for one colour. So
the token work is **two additions, one split, three hue changes** — everything else already exists
under a different name.

*As landed in T1:* the two additions are named **`--color-surface-ground`** and
**`--color-surface-field`** (following the `--color-surface-*` family already in `index.css`), not
the bare `--color-ground` / `--color-field` the steps below wrote; `@theme inline` maps them to the
kit's `ground` / `field`, so the utility names are unchanged. `theme.ts`'s `statusColors` moved
with them, including a light-mode `RETEST` hue (`#3f5aa6`) so light stays blue too — D1's
"derived" in practice.

## 3. Decisions this plan takes

**D1 — Dark stays the authored theme; light is kept and derived.** The renders are dark-only and
the spec's §9.1 lists the light-theme collision as *unsolved*. The app ships a working theme toggle
today. Deleting light mode is scope nobody asked for, so: the **dark** palette is authored from the
kit and is authoritative, light is derived from it and kept, and `features/reports/ReportViewPage`
keeps its deliberately-light preview surface (§9.1). If light drifts from the renders, that is
accepted and recorded — the renders make no claim about it.

**D2 — The intermediate state is mixed, bounded, and preflight-free.** The `fe-react` styling skill
forbids Tailwind alongside a CSS-in-JS library. That rule is about a *steady* state; a wholesale
migration cannot avoid a middle. The middle is made safe by **omitting Tailwind's Preflight** until
MUI is gone — importing the layers individually rather than `@import "tailwindcss"` — so Tailwind's
reset never fights Emotion's:

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/utilities.css" layer(utilities);
```

T1 omits the `preflight.css` line; **T16 adds it** once no MUI remains. The plan is not done until
T16 lands — a half-migrated tree is the failure this bounding exists to prevent.

**D3 — `lib/muiTestIds.ts` is deleted, not ported.** Its entire reason is that *"MUI types a slot's
props as that element's known DOM props, which excludes custom attributes"*. A Tailwind `<input>`
takes `data-testid` directly, so the workaround's premise disappears. ~~One comment cites it —
`features/runs/BlockedDialog.tsx:105`~~ **Two comments cite it by path** —
`features/runs/BlockedDialog.tsx:105` and, since T2, `components/ui/ui.test.tsx:20` (the test that
proves the premise is gone cites the file whose premise it is) — and both citations must die with
the file or `packages/client/src/test/citedFiles.test.ts` reddens the **packages** job. The 13
importers do not need a T16 step: each screen task removes its own `inputTestId`/`selectTestId`
import when it ports the control, and T16's grep is what proves none survived. Who owns which:

| Importer | Removed by |
|---|---|
| `cases/CaseEditorPage.tsx:67`, `cases/ConditionEditDialog.tsx:40` | T7 |
| ~~`runs/RunSchedulePage.tsx:39`~~ | T10 — **done** (`b2b1509e`) |
| `runs/RunDetailPage.tsx:30`, `review/ReviewPanel.tsx:43` | T11 |
| ~~`runs/BlockedDialog.tsx:16` (+ the `:105` citation)~~ | T12 — **done** |
| `cases/ClassificationPicker.tsx:19` | T13 |
| `settings/ReviewSettingsPage.tsx:32`, `settings/TrackerSettingsPage.tsx:46`, `settings/NotificationChannelDialog.tsx:40`, `admin/TeamDialog.tsx:20`, `admin/TeamMembersDialog.tsx:24`, `admin/ProviderFormDialog.tsx:21` | T15 |
| `components/ui/ui.test.tsx:20` (citation only) | T16 | (Checked: `corpus.ts`
scans `frontend/src` for `.ts`/`.tsx` only, so the `.css` citation at `index.css:9` and the ones in
`tcms.css` are out of scope. `lib/statusColors.ts` is cited three times and **survives** — it is
token names, not MUI.)

**D4 — The comment discipline follows the repo, not the plugin.** `ep-code-comments` wants minimal
comments; this repository's convention is long doc comments carrying the *reason* a decision was
taken, and several of them are load-bearing history. Ported files keep their headers. Where a header
describes MUI machinery that no longer exists, it is **struck through and superseded**, per
CLAUDE.md's rule, never silently deleted.

**D5 — Screen tasks port a render; they do not invent a design.** The skill's *no placeholders* rule
requires complete code in every step. For T1–T4 that is real code, written below. For the screen
tasks (T5–T15) the complete code **already exists on disk** as the render's HTML and `tcms.css`, and
the deliverable is a faithful port of a named file. Inventing React in this document instead of
pointing at the drawing would produce a second, drifting design source — which is exactly the defect
§9.3 of the spec warns about. This is a stated deviation from the skill, not an omission.

## 4. What this plan does not do

- **It does not delete `expectedOutcome` or `description`** (item 6 in §0). The renders kill both
  fields, but they are on the wire in `openapi.yaml`, they hold data in existing cases, and removing
  them is a contract change with a migration behind it. It belongs in its own plan next to the
  backend work. This plan moves them out of a titled *Notes* panel into the document's narrative
  column and leaves the fields alive.
- **It does not redraw the renders.** They are the historical input and stay as they are, per 006's
  precedent. Where the build disagrees with a render, the *spec* is amended, not the HTML.
- **It does not touch the backend, the contract, or `packages/`** — except the one comment in D3.
- **It does not add a categorical chart palette.** §6 computed that the product cannot have one.

---

**Tasks** — in the order they are done:

| # | Task | Status | Deliverable | Depends on |
|---|---|---|---|---|
| 1 | T1 — Tailwind v4, preflight off, kit tokens | **done** `5e14f336` | `npm run build` clean with Tailwind compiled; `index.css` carries the kit's token names; MUI untouched and still rendering | none |
| 2 | T2 — shadcn primitives, skinned | **done** `8c21107a` | `components/ui/` holds the Radix primitives, restyled to the kit; each renders in a test at kit colours | T1 |
| 3 | T3 — The nine-component kit | **done** `2dceb911` | `components/kit/` exports the nine components of design-language §5; each has a test asserting the rule it carries | T2 |
| 4 | T4 — ~~`DataRegion`'s sixth state~~ `StateBlock`'s refusal without a grantee | **done** `8b78a4f9` | ~~`DataRegion` renders … **refused**~~ `DataRegion` already did; `StateBlock`'s refused state now degrades to *Ask a Company Admin* when no grantee can be named | T3 |
| 5 | T5 — AppShell | **done** `8127551a` + `5a4d7226` | Rail at 232px, collapsing to the icon rail on the **three** route patterns below (§4's two screens, one of them at two URLs); top strip; footer strip. ~~Slot for a page's commit bar~~ → not built; T7 adds it | T3 |
| 6 | T6 — Family 8, Threshold | **done** | Login · AcceptInvitation · SsoCallback render from the kit with no MUI import | T5 |
| 8 | T8 — Family 9, States **(runs before T7)** | **done** | NotFound · Help; notistack → `sonner` across all 14 files; the refusal/notification matrix all 42 inherit | T4, T6 |
| 7 | T7 — Case editor, both frames | **done** | `CaseEditorPage` matches `case-authoring-redesign.html` frames A and B, incl. the seven items of §0 | T5, T8 |
| 9 | T9 — TanStack Table | **done** `6de8c9a9` | `CaseGrid` renders on TanStack Table with server-side paging/sort/filter; `@mui/x-data-grid` unimported | T3 |
| 10 | T10 — Family 1, Index & table | **done** `b2b1509e` | The 10 index screens render on the kit's Table; `@mui` unimported by all ten | T9 |
| 11 | T11 — Family 2, Record as document | **done** | The 7 document screens render at the 74ch measure | T7 |
| 12 | T12 — Family 3, Execution | **done** | `ExecutionPage` renders with the collapsed rail and number-key verdicts; the failure panel is a rail section, ~~*last time*~~ is a named follow-up | T5, T11 |
| 13 | T13 — Family 4, Board & people | **done** | Board · MyWork · TeamBoard · OrgChart; the MUI tree-view package unimported | T3, T8 |
| 14 | T14 — Family 5, Authoring | **done** | Docs · DocPageEditor · FeatureImport at the 70ch prose measure | T11 |
| 15 | T15 — Family 6 & 7, Analytics and Settings | **done** | Dashboard · PlanStatistics per §6; the **11** settings/account screens (§8's nine plus the two it does not list) | T10 |
| 16 | T16 — MUI removed, preflight on | **done** `98ffb79a` | No `@mui`/`@emotion`/`notistack` in `package.json` or any import; Preflight imported; `muiTestIds.ts` deleted with **both** citations | T6–T15 |
| 17 | T17 — Gates and bundle measurement | gates green, browser pass open | The four gates green on `shanks` (the **only** gate pass for T6–T16 — see the directive in the header); before/after bundle numbers recorded; preflight layout damage fixed | T16 |

Seventeen tasks. T6–T15 are the ten screen tasks and are ordered so the cheapest proof of the kit
(T6, three screens, no rail) lands before the hardest document surface (T7), and so every family
that consumes a replacement engine comes after the task that builds it.

**One ordering change from the original:** **T8 now runs before T7.** The notistack → `sonner`
swap was written as a one-line provider change in T5; it is not (§1: six pages call `useSnackbar`
and six tests mount `SnackbarProvider`). It belongs with the notification matrix in T8, and
`CaseEditorPage` is one of the six callers — so porting T7 first would touch that file twice.
Numbers are kept as they were so commit messages and this table keep agreeing.

---

### T1 — Tailwind v4, preflight off, kit tokens — **DONE** (`5e14f336`)

**Deliverable:** `npm run build` is clean with Tailwind compiling, `src/index.css` carries the render
kit's token names alongside the existing ones, and every current screen still renders through MUI
exactly as before.
**Depends on:** none

**What landed, where it differs from the steps below:** the two new tokens are
`--color-surface-ground` / `--color-surface-field` (§2 note); `theme.ts` was also touched, because
`themeTokenParity.test.ts` pins its `statusColors` to `index.css` and the three hue changes would
otherwise have failed that test; the rule-5 identity test exists as written. Steps 1–8 are ticked.

**Files:**
- Modify: `frontend/package.json` — add `tailwindcss`, `@tailwindcss/vite`
- Modify: `frontend/vite.config.ts` — register the plugin
- Modify: `frontend/src/index.css` — the layer imports and the kit tokens
- Test: `frontend/src/app/themeTokenParity.test.ts:1-92` — extend, do not replace

- [x] **Step 1: Install**

```bash
cd frontend && npm install tailwindcss @tailwindcss/vite
```

- [x] **Step 2: Register the Vite plugin**

In `frontend/vite.config.ts`, add the import and the plugin entry:

```ts
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
});
```

- [x] **Step 3: Import Tailwind's layers individually — WITHOUT preflight**

At the very top of `frontend/src/index.css`, above the existing `:root` block:

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/utilities.css" layer(utilities);
```

`tailwindcss/preflight.css` is deliberately absent. Adding it here resets MUI's Emotion baseline and
every screen loses its typography at once. T16 adds it, after the last MUI import is gone.

- [x] **Step 4: Add the six token changes of §2 to the dark block**

In `frontend/src/index.css`, inside `[data-mui-color-scheme='dark']`:

```css
  --color-ground: #0a0e14;
  --color-field: #0e141c;
  --color-text-ghost: #4a5566;

  --color-passed: #7ec8a0;
  --color-blocked: #e0b95f;
  --color-retest: #8fa9e0;

  --color-given: #7ec8a0;
  --color-when: #e0b95f;
  --color-then: #8fa9e0;
```

`--color-text-ghost` is the `--ink-4` half of the split; `--color-text-faint` keeps `--ink-3`'s
value. The three changed status hues and the three keyword hues are the same three colours by rule
5 — declared twice because the two names mean different things to a reader, not because they may
diverge.

- [x] **Step 5: Expose the tokens to Tailwind**

Below the imports in `frontend/src/index.css`:

```css
@theme inline {
  --color-ground: var(--color-ground);
  --color-paper: var(--color-surface-sunken);
  --color-panel: var(--color-surface);
  --color-field: var(--color-field);
  --color-ink: var(--color-text);
  --color-ink-2: var(--color-text-muted);
  --color-ink-3: var(--color-text-faint);
  --color-ink-4: var(--color-text-ghost);
  --color-steel: var(--color-action-primary);
  --font-sans: 'IBM Plex Sans', system-ui, sans-serif;
  --font-mono: 'IBM Plex Mono', ui-monospace, monospace;
}
```

This is what makes `bg-panel`, `text-ink-3` and `font-mono` resolve to the tokens rather than to
Tailwind's defaults. The CSS custom properties stay the single source: `@theme inline` points at
them, it does not copy their values.

- [x] **Step 6: Extend the parity test rather than replacing it**

`themeTokenParity.test.ts` pins `index.css` against `app/theme.ts`. It must keep doing that while
MUI lives, and additionally assert the new names exist. Add to that file:

```ts
it('declares the render kit tokens the design language adds', () => {
  const dark = darkBlockOf(readIndexCss());
  for (const token of ['--color-ground', '--color-field', '--color-text-ghost',
                       '--color-given', '--color-when', '--color-then']) {
    expect(dark, `${token} is named by docs/specs/2026-08-28-design-language.md §1`)
      .toHaveProperty(token);
  }
});

it('keeps the keyword hue and its result hue identical (design language rule 5)', () => {
  const dark = darkBlockOf(readIndexCss());
  expect(dark['--color-given']).toBe(dark['--color-passed']);
  expect(dark['--color-when']).toBe(dark['--color-blocked']);
  expect(dark['--color-then']).toBe(dark['--color-retest']);
});
```

The second test is the one worth having: rule 5 is a claim two people editing two tokens can break
silently, and it is exactly the *"schema-wide invariant needs a computed check"* rule in CLAUDE.md.

- [x] **Step 7: Run the gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: build clean, lint 0 warnings, suite green. Every screen still looks as it did — Tailwind
is compiled but nothing uses a utility class yet.

- [x] **Step 8: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts \
        frontend/src/index.css frontend/src/app/themeTokenParity.test.ts
git commit -m "feat(frontend): T1 — Tailwind v4 with preflight off, render-kit tokens"
```

---

### T2 — shadcn primitives, skinned to the kit — **DONE** (`8c21107a`)

**Deliverable:** `frontend/src/components/ui/` holds the Radix-based primitives the 42 screens need,
restyled to the kit tokens, each rendering at kit colours in a test. Nothing outside that folder
imports them yet.
**Depends on:** T1 (tokens and utilities must resolve)

**What landed, where it differs from the steps below:** ~~Step 2's `npx shadcn init` / `add`~~ was
**not run** — the 17 primitives were written by hand, because the CLI imposes a `@/` alias this
codebase does not use (it imports relatively throughout) and would have rewritten T1's `index.css`.
So there is **no `components.json`**; `button.tsx` and `buttonVariants.ts` are two files (a module
exporting a component and a value loses Fast Refresh, which the lint gate reports); `lucide-react`
was added as the icon replacement for the 41 `@mui/icons-material` files; `sonner` is installed
and `ui/sonner.tsx` exports `Toaster`, **but nothing mounts it yet** — that is T8. The barrel is
`components/ui/index.ts`.

**Files:**
- Create: `frontend/src/components/ui/*.tsx` — the primitive set
- Create: `frontend/src/lib/cn.ts`
- Test: `frontend/src/components/ui/ui.test.tsx`

- [x] **Step 1: The class-merge helper**

```bash
cd frontend && npm install clsx tailwind-merge
```

```ts
// frontend/src/lib/cn.ts
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export const cn = (...inputs: ClassValue[]) => twMerge(clsx(inputs));
```

- [x] **Step 2: Add the primitives**

```bash
cd frontend && npx shadcn@latest init
npx shadcn@latest add button dialog select popover command switch checkbox \
  tooltip tabs dropdown-menu input textarea separator skeleton sonner
```

`init` will offer to overwrite `index.css`. **Decline** — T1's layer imports and `@theme inline`
block are deliberate and `init` writes the preflight-on form. Point `components.json` at
`src/components/ui` and at the existing `@/` alias.

- [x] **Step 3: Skin the button to the kit**

The generated `button.tsx` ships shadcn's default palette. Replace its variants with the kit's,
which `tcms.css:.btn` defines — a ghost button is a hairline border and muted ink; the primary is
steel with a dark label and the glow:

```tsx
const buttonVariants = cva(
  'inline-flex items-center gap-2 rounded-md text-[13px] font-medium min-h-8 px-3.5 py-1.5 ' +
    'transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 ' +
    'focus-visible:outline-given disabled:opacity-45 disabled:pointer-events-none',
  {
    variants: {
      variant: {
        ghost: 'border border-white/8 text-ink-2 hover:bg-white/5',
        primary:
          'bg-steel text-[#08121a] font-semibold shadow-[0_0_0_1px_rgb(107_168_196/30%),0_4px_14px_-4px_rgb(107_168_196/35%)]',
        quiet: 'text-ink-2 hover:bg-white/5',
        danger: 'border border-white/8 text-[color:var(--color-failed)] hover:bg-white/5',
      },
      size: { default: '', sm: 'text-xs px-2.5 py-1 min-h-7' },
    },
    defaultVariants: { variant: 'ghost', size: 'default' },
  },
);
```

The focus ring is `--color-given`, not steel: the rail spec's rule 3 wants a *visible* focus ring,
and a steel ring on a steel-adjacent surface is the one that disappears.

- [x] **Step 4: Write the primitive test**

```tsx
// frontend/src/components/ui/ui.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button } from './button';
import { Switch } from './switch';

it('a primary button carries the steel background token', () => {
  render(<Button variant="primary" data-testid="B">Create case</Button>);
  expect(screen.getByTestId('B')).toHaveClass('bg-steel');
});

it('a switch is a real control with a role, a name and keyboard operation', async () => {
  const onCheckedChange = vi.fn();
  render(<Switch aria-label="Match a suite" onCheckedChange={onCheckedChange} />);
  const sw = screen.getByRole('switch', { name: 'Match a suite' });
  sw.focus();
  await userEvent.keyboard(' ');
  expect(onCheckedChange).toHaveBeenCalledWith(true);
});
```

The second test is the kit's rule 3 (*"a quiet control is still a control"*) made checkable. It is
the rule the current rail breaks in the other direction — loud, but at least operable — and it is
the one a hand-rolled div silently fails.

- [x] **Step 5: Run and commit**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/components/ui frontend/src/lib/cn.ts frontend/components.json \
        frontend/package.json frontend/package-lock.json
git commit -m "feat(frontend): T2 — shadcn primitives skinned to the render kit"
```

---

### T3 — The nine-component kit — **DONE** (`2dceb911`)

**Deliverable:** `frontend/src/components/kit/` exports the nine components of design-language §5 —
`Table`, `ResultBar`, `Badge`, `Chip`, `RailRow`, `DocumentSurface`, `Timeline`, `Banner`,
`StateBlock`, `Figure` — each with a test asserting the rule it carries.
**Depends on:** T2

**What landed, where it differs from the steps below:** the tests live in two files, not one per
component — `kit/kitRules.test.tsx` (badge/chip, rail row, result bar) and `kit/StateBlock.test.tsx`.
The exported names the screen tasks will import (from `components/kit/index.ts`): `Table` with
`TableHead/TableBody/TableRow/TableCell/TableHeaderCell`; `DocumentSurface` with `DocumentTitle`
and `ProcedureLine` (+ `ProcedureKeyword`); `Timeline` with `TimelineEvent`; **`Figure` is three
components** — `Tile`, `HBar`, `Sparkline` — there is no `<Figure>`. `StateBlock`'s empty state
takes `hidden` / `filters` / `onClearFilters`. `Chip`'s removable form is two sibling buttons, not
a nested one.

**Files:**
- Create: `frontend/src/components/kit/{Table,ResultBar,Badge,Chip,RailRow,DocumentSurface,Timeline,Banner,StateBlock,Figure}.tsx`
- Create: `frontend/src/components/kit/index.ts`
- Test: one `*.test.tsx` beside each

The source for every one is `docs/specs/assets/renders/tcms.css` plus the render that uses it. Port
the CSS rule; do not re-derive the look.

- [x] **Step 1: `Badge` vs `Chip` — the distinction the spec makes load-bearing**

§5: *a **badge** is state the system owns and is static; a **chip** is a value the reader owns and
is a button.* Encode that in the types so the wrong one is a compile error, not a review comment:

```tsx
// Badge.tsx — renders a <span>. It has no onClick prop and cannot be given one.
export function Badge({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return <span className={cn('rounded px-1.5 py-0.5 text-[11.5px] font-mono', toneClass[tone])}>{children}</span>;
}

// Chip.tsx — renders a <button>. onClick is required.
export function Chip({ onClick, children, ...rest }: ChipProps) {
  return (
    <button type="button" onClick={onClick} {...rest}
      className="rounded-full border border-white/8 bg-white/6 px-2 py-0.5 text-[11.5px]
                 text-ink-2 hover:border-steel/35 focus-visible:outline-2 focus-visible:outline-given">
      {children}
    </button>
  );
}
```

```tsx
// Badge.test.tsx
it('a badge is not operable — state the system owns (UX#114)', () => {
  render(<Badge tone="PASSED">Passed</Badge>);
  expect(screen.queryByRole('button')).toBeNull();
});
// Chip.test.tsx
it('a chip is a real button with a name (UX#117)', () => {
  render(<Chip onClick={vi.fn()}>@smoke</Chip>);
  expect(screen.getByRole('button', { name: '@smoke' })).toBeInTheDocument();
});
```

- [x] **Step 2: `RailRow` — rule 3, the row the current editor gets wrong**

Quiet at rest, bordered on hover, focus-ringed, native role, value right-aligned. This is item 4 of
§0 and the single most-repeated component in the product.

```tsx
export function RailRow({ label, value, onOpen, readOnly }: RailRowProps) {
  const className =
    'flex w-full items-center justify-between gap-2.5 rounded-md px-1.5 py-1 text-[13px] text-left ' +
    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-given';
  if (readOnly) {
    return (
      <div className={cn(className, 'opacity-72')}>
        <span className="text-ink-3">{label}</span><span className="text-ink">{value}</span>
      </div>
    );
  }
  return (
    <button type="button" onClick={onOpen} className={cn(className, 'hover:bg-white/5')}>
      <span className="text-ink-3">{label}</span>
      <span className="flex items-center gap-1.5 text-ink">{value}<ChevronDown className="size-3 text-ink-4" /></span>
    </button>
  );
}
```

```tsx
it('is quiet at rest and operable — no filled background, real button, focus ring', () => {
  render(<RailRow label="Priority" value="Medium" onOpen={vi.fn()} />);
  const row = screen.getByRole('button', { name: /Priority/ });
  expect(row.className).not.toMatch(/\bbg-(field|panel|white)\b/);
  expect(row.className).toMatch(/focus-visible:outline/);
});
```

- [x] **Step 3: `StateBlock` — six states, and failed must not read as empty**

§7. The load-bearing assertion is that **failed and empty are unmistakable** — different icon,
colour, verb and offer — because a 200 with the wrong shape rendering as *no results* is the
quietest data-loss bug a client can have.

```tsx
export type StateKind = 'loading' | 'empty' | 'failed' | 'stale' | 'refused';
```

```tsx
it('failed and empty share no icon, verb or tone', () => {
  const failed = render(<StateBlock kind="failed" reference="A3F19C" />).container.textContent;
  const empty = render(<StateBlock kind="empty" hidden={1284} filters={3} />).container.textContent;
  expect(failed).toMatch(/A3F19C/);
  expect(empty).not.toMatch(/A3F19C/);
  expect(failed).not.toEqual(empty);
});

it('refused is neutral, names the permission, and offers no retry', () => {
  render(<StateBlock kind="refused" permission="case:edit" grantee="M. Kalcheva" />);
  expect(screen.getByText(/case:edit/)).toBeInTheDocument();
  expect(screen.getByText(/M\. Kalcheva/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /retry/i })).toBeNull();
});
```

- [x] **Step 4: The remaining six**

`Table` (sticky mono heads; a quiet second line in the cell, never a twelfth column) · `ResultBar`
(proportion before number; 2px gaps, 3px ends; **untested is the empty track, not a fill** — §6) ·
`DocumentSurface` (74ch procedure / 70ch prose, 26px mono gutter, one hairline rule) · `Timeline`
(one column, mono stamps in the gutter) · `Banner` (neutral / amber / red per §5) · `Figure` (one
series in steel or a status stack; no pie, donut or gauge; y from zero).

`ResultBar` carries the §6 defect fix and needs its own test:

```tsx
it('untested renders as the empty track, never as a fill (design language §6)', () => {
  const { container } = render(<ResultBar passed={2} failed={1} untested={7} />);
  expect(container.querySelectorAll('[data-fill]')).toHaveLength(2);
});
```

- [x] **Step 5: Run and commit**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/components/kit
git commit -m "feat(frontend): T3 — the nine-component kit of design-language §5"
```

---

### T4 — ~~`DataRegion`'s sixth state~~ `StateBlock`'s refusal without a grantee — **DONE** (`8b78a4f9`)

~~**Deliverable:** `DataRegion` renders loading / loaded / empty / failed / stale / **refused**, with
refused neutral, naming the permission and a person who holds it, and offering no retry.~~

**→ superseded. The premise was wrong.** Step 1 (*read the existing five states*) found six:
`describeError` already returns `isRefused`, and `DataRegion.tsx:69-98` already renders a refusal
as a `warning` (not an error), with no retry, the permission named, and its own `.Refused` testid.
The deliverable was met before the task started; `DataRegion.tsx` was **not modified** and the
failing test in the original Step 2 was never written, because it would have passed.

What was actually wrong was T3's `StateBlock`, which *required* a grantee name. Design language §7
asks a refusal to name *"somebody in this company who holds it"*, but 005 C1b deliberately dropped
the endpoint that would answer that, so on this codebase no name exists to give. `grantee` is now
optional with an `action` slot, and the copy degrades to *Ask a Company Admin* — the wording
`describeError` already uses, so the product does not carry two. The permission id is still named
in every branch; it is the clause a reader can repeat to someone, and the part that must not
degrade.

**Consequence for T15:** *"every refusal names a person who can grant it"* is not achievable as
written; the settings screens name the permission and the role, via `DataRegion`'s existing
refused rendering, and that is the bar.

**Files touched:** `kit/StateBlock.tsx`, `kit/StateBlock.test.tsx` only.

---

### T5 — AppShell — **DONE** (`8127551a` "T5a" Steps 1–2 · `5a4d7226` "T5b" Step 3)

**What landed, where it differs from the steps below:** `AppShell.tsx` went from ~40 `@mui`
imports to **one** — `useColorScheme`, kept because it writes `data-mui-color-scheme` and
`index.css` keys the dark palette off that attribute; **T16 replaces it** (already in T16's file
list). The collapsed rail is **64px**, not the render's 60 — `RAIL_WIDTH_COLLAPSED` was left as
it was; take the render's figure in T16's layout pass or leave it, but say which. Two defects the
port surfaced and fixed on the way: Radix `Tooltip` throws without a provider ancestor, so
`ui/tooltip.tsx` is now self-providing (a root-only provider would have been fatal in ~100
isolated-render tests); and Tailwind's default `md: 768px` silently contradicted this app's
`md: 900` contract in `breakpoints.ts`, so the scale is now declared in `@theme` from the
contract and `themeTokenParity.test.ts` reads `breakpoints.ts` rather than restating it. **Step 4
(the top-strip slot) was not built** and moves to T7, the task that needs it. 1988 tests, local.

**Deliverable:** The shell renders the 232px rail with the headings and order `AppShell.tsx`'s
`projectNav` already has, collapsing to the icon rail on the case document editor and the execution
runner (§4's two screens — **three route patterns**, below) plus the top strip and the footer strip
of rule 2, the top strip carrying a slot a page can put its commit bar in.
**Depends on:** T3

**Files:**
- Modify: `frontend/src/app/AppShell.tsx` (1171 lines, all MUI), `frontend/src/app/AppShell.test.tsx`
- ~~Modify: `frontend/src/app/Providers.tsx` — `sonner`'s `<Toaster />` replaces notistack's provider~~
  **→ moved to T8.** The swap is 14 files, not one (§1), and it has nothing to do with the shell.

- [x] **Step 1: Write the failing test for the collapse rule** — *landed, with three corrections to
the text above the line:*

  - The execution runner's route is **`/projects/:projectId/runs/:runId/executions/:executionId`**
    (`router.tsx:513`), not `/execute`.
  - The case editor is reachable at **two** URLs — `/cases/new` (frame B, the blank state) and
    `/cases/:caseId/edit` — and collapsing on one but not the other would put the rail at two widths
    either side of the first save. So the constant has **three patterns** for §4's two screens.
  - The rail already carries `data-testid="Nav.Rail"` and `data-collapsed="true|false"`
    (`AppShell.tsx:528`); the test reads those, not the invented `AppShell.Rail` / `data-width`.

  The test also pins the other half — four routes that must **stay expanded** — because a collapse
  rule that quietly widened to every project route would take 168px off forty screens; and it
  asserts `Nav.CollapseToggle` is absent on a forced route, since a toggle that cannot expand is a
  control that appears to do nothing.

- [x] **Step 2: Implement the route read** — *landed.* `COLLAPSED_RAIL_ROUTES` is a module constant
in `AppShell.tsx`; `useMatch` is called once per entry at the top level (hooks cannot loop), and
`collapsed = railCollapsed || documentRoute`. The route forces the collapse without overwriting the
stored `tcms.nav.railCollapsed` preference.

- [x] **Step 3: Port the rail, top strip and footer strip** from `renders/tcms.css` (`.rail`, `.top`,
`.foot`) and `renders/01-index-tables.html`. The pieces that exist today, all MUI: the rail `Box`
at `AppShell.tsx:528`, the 52px `<header>` at `:1006`, the `AppFooter` at `:1146`. Widths: the
render says 232 / 60; the code says `RAIL_WIDTH` / `RAIL_WIDTH_COLLAPSED = 64` — take the render's.
Every `Nav.*` testid stays byte-identical; the existing `AppShell.test.tsx` (216 lines before this
task) is the regression net.

- [ ] ~~**Step 4: A slot in the top strip for a page's commit bar.**~~ **→ moved to T7 Step 0**, not
built in T5b. Kept here so the reasoning is not lost: §0 item 7 puts the case
editor's commit bar *in the top strip beside the breadcrumb*, and T7 cannot do that unless the shell
offers a place — a `TopStripSlot` (context + portal target, or an `Outlet` context value). This is
the one thing T7 needs from T5 that the original text did not name. Add it here, with a test that a
child rendering into it appears inside the `<header>`.

- [x] **Step 5: Run and commit** — landed as two commits, `8127551a` and `5a4d7226`, the second
also touching `ui/tooltip.tsx`, `index.css` and `themeTokenParity.test.ts` for the reasons above.

---

### T6 — Family 8, Threshold — **DONE**

**What landed, and the three things the code disproved.**

**1. The render's headline rule is only two-thirds portable.** *"The email decides the sign-in
method before a password is asked for"* cannot be built on this screen without deleting behaviour
the suite pins. `LoginPage.test.tsx`'s *"asks after a rejected local sign-in"* types into
`Login.PasswordField` with **no blur ever firing** — that is a password manager filling both
fields and submitting, and it is the everyday case at a company whose accounts exist only through
its issuer. A password field that appears only after discovery answers would have nothing for that
manager to fill. So the password path stays unconditional, `Keep me signed in` stays with it (the
render's fourth note puts the switch on the password path only — satisfied vacuously, since the
password path is always on), and the discovery *banner* of frame A — *"acme.test signs in through
Acme Okta. You will not need a password."* — was **not** ported: its second sentence is false here,
and its first duplicates the button beneath it while costing a `watch()` on the email field.
Making this screen match frame A properly is a behaviour change with its own tests, not a port.

**2. Frame C's story column asks for four fields the contract does not return.**
`GET /auth/invitations/{token}` answers `email`, `displayName`, `passwordRequired`. The render's
left panel says *"Mira Kalcheva invited you to Acme. You will join as a **Test Engineer**, with
access to **Reflex Payments** and **Halo Onboarding**…"* — inviter, company, role, projects, and
the role's own can/cannot sentence, which the render's first note calls the one moment a person
will read a permission description. None of it is on the wire. The story column carries the
invitee's own name and nothing invented; **the rest is a contract change**, and it is the
strongest single argument in these three screens for one. Same for the expiry in the footnote —
*"This link expires in 6 days"* became *"This link can be used once"*, which is a fact this page
actually holds.

**3. Frame C's strength meter and second requirement tick are claims the product cannot make.**
The drawing shows a four-segment meter and two ticks, the second being *"Not a password seen in a
known breach"*. There is no breach check in this tree or in the contract, and no strength
estimator — adding `zxcvbn` for a drawing is a dependency for a decoration. One tick landed, wired
to the Zod rule that actually exists (12 bytes), turning `--color-passed` when met. The meter did
not.

Also landed, smaller: `Identifier` is **not** used on these screens — it is MUI, and the wordmark
and the invited address are the render's `.mk` and `.id` classes inlined instead; T16 owns whether
`Identifier` itself is ported or dissolved. The failed-callback frame (D, lower) is **not** built:
`SsoCallbackPage` redirects a failure to `/login` and that is pinned by four tests, and the
drawing's four facts — the returned address, the reason, *SSO never creates accounts*, a reference
to quote — are exactly what §6r's opaque marker withholds. The render and the refusal design
disagree; the refusal design wins, and §9 of the design language gains an entry.

**Deliverable:** `LoginPage`, `AcceptInvitationPage` and `SsoCallbackPage` render entirely from the
kit with no `@mui` import, matching `renders/08-threshold.html`.
**Depends on:** T5

Three screens, no rail, no project, no session — the cheapest complete proof that the kit can carry
a screen. The render's rule: *more identity than anywhere inside, fewer controls than anywhere
inside, and the email decides the sign-in method before a password is asked for.*

**Files:** `frontend/src/features/auth/*.tsx` and their tests. **One file was added** —
`features/auth/ThresholdLayout.tsx`, the `.thr` two-column shell plus the six one-liners the three
screens share (`label.f`, `.err`, `.help`, `.or`, `.quiet`, the form column's title and lead). It is
not in `components/kit`: §5's nine components are the ones that carry all forty-two screens, and
these carry three.

- [x] **Step 1:** Port each screen against `renders/08-threshold.html`, replacing MUI imports with
`components/ui` and `components/kit`. Keep every `data-testid` byte-identical — the existing tests
are the regression net and must not be edited to accommodate the port. *Every testid is byte-
identical (verified by the suite, which queries by them). The three `*.test.tsx` files were touched
in exactly one way: the now-dead `<ThemeProvider theme={theme}>` wrapper and its two imports were
removed, because Step 2's grep covers the whole directory. No assertion, mock, fixture or query was
changed.*
- [x] **Step 2:** `grep -rn "@mui" frontend/src/features/auth` → expected: no matches. *No matches.*
- [x] **Step 3:** `npm run build && npm run lint && npm run test:run` — *build clean, lint 0
warnings, `src/features/auth` **36/36**. The **full** suite is red on this tree and it is not T6:
run with the T6 change it failed 16 tests in 7 files; run again with `features/auth` stashed and
nothing else altered it failed **8 tests in 6 files — a different set** (`IssueChip`,
`PlanLifecycle`, `ClassificationSettingsPage`, `TrackerSettingsPage`, only three files overlapping),
and an earlier run of the same tree failed 4 in 2. Zero auth failures in any of the three. Every
failure is the same shape — `TypeError: Failed to parse URL from /api/v1/…`, i.e. a write that
escaped its double — and the set moves between runs, which is a parallel-worker pollution or load
cliff, not a defect in any one file. The tree also carries an **uncommitted T9 lane** at the time
of this run (`@tanstack/react-table` in `package.json`, a rewritten `CaseGrid.tsx`, a modified
`kit/Table.tsx` and a new `CaseGrid.test.tsx`, +12 tests → 2000), which is the load that changed.
**T17's shanks pass is what settles it**; if it reproduces there, capping vitest's worker count is
the lead the runner's own comment in `vitest.config.ts` already names.*
- [x] **Step 4:** Commit.

```bash
git add frontend/src/features/auth
git commit -m "feat(frontend): T6 — family 8, the threshold, ported to the kit"
```

---

### T7 — The case editor, both frames — **DONE**

**Deliverable:** `CaseEditorPage` matches `assets/case-authoring-redesign.html` frames A and B, with
all seven differences of §0 closed except item 6, which §4 defers.
**Depends on:** T5, **T8** (so `useSnackbar` at
`CaseEditorPage.tsx:17` and the `SnackbarProvider` wrap in its test are already gone when this
file is opened — see the ordering note under the task table).

*Line references re-checked 2026-08-29:* `Panel title="Start"` `:345`, `"The case"` `:1032`,
`"Notes"` `:1083` with the two fields at `:1086` / `:1095`, `"This case"` (the rail) `:1152`;
`CaseDocument.tsx` `MONO` `:70`, `SHARED` `:86`, the placeholder `:280`; the file is 1662 lines and
its test 524. `CaseEditorPage.tsx:67` imports `selectTestId` from `lib/muiTestIds` — Step 4 removes
it with the `Select`s it served.

This is the screen the plan was opened for. Its two specs
([`2026-08-28-case-authoring-redesign.md`](../../specs/2026-08-28-case-authoring-redesign.md),
[`2026-08-28-case-authoring-rail.md`](../../specs/2026-08-28-case-authoring-rail.md)) are the authority
where they and the render disagree — the rail spec's *"Does the render need redrawing?"* section
lists four places the render is already known to be wrong.

**Files:** `frontend/src/features/cases/CaseEditorPage.tsx`, `CaseDocument.tsx`, `ConditionEditDialog.tsx`
and their tests, plus `frontend/src/app/topStripSlot.tsx` (new), `app/AppShell.tsx` (+test) for Step 0
and `components/kit/RailRow.tsx` — see note 2 below.

**What landed, and the four things the code disproved.**

**1. The existing test could not pass unedited, and the reason is the change itself.** The plan
said the 524-line `CaseEditorPage.test.tsx` would need editing only where it asserts a `Panel`
title — it asserts none; every region is found by testid, and those survived. What it *did* assert
was **MUI internals**: `within(rail).getAllByRole('combobox')` counted the rail's outlined
`Select`s, and two tests reached for `.closest('.MuiInputBase-root')` to open one. Those are
assertions about the defect §0 item 4 names. Three tests were rewritten (the UX#117 row test now
proves each row is a native `<button>` with an accessible name and `aria-expanded`; the automation
and suite interactions drive the disclosure); the other twenty-one pass byte-identical, and
`CaseDocument.test.tsx` needed one — the placeholder assertion Step 3 deletes.

**2. `RailRow` could not carry `aria-expanded`.** Its props stopped at `data-testid`, so a row that
opens a disclosure had its state in the chevron and nowhere a screen reader could reach — UX#117's
*state* clause failed by the component written to satisfy it. `kit/RailRow.tsx` now extends
`ComponentProps<'button'>` and takes a `className`. **This is the one file outside T7's list that
was touched**, and it is the kit's first real consumer finding its first gap.

**3. The automation disclosure is inline, not a popover — which is the rail spec's open question 2,
answered.** A popover is dismissed by a click outside it, and Automation is the one control that
can put this form into a state only a second control resolves. An inline disclosure has nothing to
dismiss it, and `rowOpen('automation')` is derived from `errors.automationKey ||
errors.notAutomatableReason`, so it **cannot be closed while its own refusal stands**. The two
condition groups stayed inline for a second reason: `ConditionsPanel` carries a portalled MUI
`Autocomplete` and a DnD context, and clicking a portalled listbox is an outside-click to a
popover — an untestable dismissal bug in a lane with no browser.

**4. The mirror may not take the render's mono keyword.** Step 5 asked for 15px sans at 1.75 with
mono for the keyword; the textarea and its colouring mirror must agree on font, size and weight to
the pixel or the caret drifts from the completion popup, and a mono keyword shifts every character
after it on its line. So `SHARED` is 15px sans and **the keyword changes colour only** — rule 5's
hues, `Given`/`When`/`Then` in the three the runner paints results in. The mono half of the rule
went where nothing is typed: the render's 26px gutter number (absolutely positioned, so it costs
the shared flow nothing), the `Feature:` chrome, and the blank state's scaffold. The `.blk` step-data
box and the mauve `--tagc` tag colour are the two render details refused outright — a background
and a hue this app has no token for; both render as `ink-2`.

Also landed, differing from the steps above: the **top-strip slot renders its children in place**
when no shell is above it (`app/topStripSlot.tsx`), because every page test in this repository
mounts the page without `AppShell` and a Save button that exists only under a particular ancestor
is a Save button that disappears. The strip also carries the **breadcrumb** the deleted
`PageHeader` used to draw — dropping the `<h1>` per rule 1 must not take the only non-rail way back
to the list — and the `<title>` and arrival announcement are raised from the page, which is the
rest of what `PageHeader` was doing that nothing else does. `ConditionEditDialog` was ported off
MUI whole (it is untested, and it is the second `lib/muiTestIds` importer D3 assigns to T7).

**The rail is 300px**, the render's figure, and open question 1 of the rail spec is answered in that
document.


- [x] **Step 0: The top-strip slot** (moved from T5). `AppShell.tsx`'s `<header>` gets a
`TopStripSlot` — a context + portal target — so a page can render its commit bar beside the
breadcrumb (§0 item 7). Test: a child rendered into it appears inside the `<header>`, and nothing
appears on a route that renders none. This is the only `AppShell.tsx` change in T7.

- [x] **Step 1: Remove the panel chrome** (§0 item 1). The document column is a bare `paper` surface;
`Start`, `The case` and `Notes` lose their `Panel` wrappers. The rail keeps its section headings —
those are `tcms.css:.sec h3`, not panels.

- [x] **Step 2: The three starts become cards** (§0 item 2), each with its sentence and its keyboard
hint, `Write it` highlighted — `case-authoring-redesign.html:.starts`.

- [x] **Step 3: The blank state** (§0 item 3) — a `+ tag` row, the ghost title lead, the subtitle
*"One sentence stating what is verified"*, the rule, then the numbered scaffold with a ghost hint
per keyword. This replaces the textarea placeholder at `CaseDocument.tsx:280`; the placeholder is
what a person sees when there is nothing to see, and the scaffold is what tells them what to write.

- [x] **Step 4: The rail goes quiet** (§0 item 4). Every row becomes `kit/RailRow`: no outlined
`Select`, no permanent border, no persistent helper paragraph. The helper text moves into the
opened control, where the rail spec puts it. **`notAutomatableReason` must stay reachable and its
refusal visible** — the rail spec's open question 2 says the automation popover must not close on a
server refusal, or the error has no visible field.

- [x] **Step 5: The document reads as a document** (§0 item 5): 15px sans at 1.75 for step text,
mono only for the keyword, the gutter number, and step data. `MONO` at `CaseDocument.tsx:70` stops
applying to the whole mirror — but the textarea and its colouring mirror must still agree on font,
size, line height, padding, border and wrapping, or the caret drifts from the completion popup. That
constraint is why the file has one mirror and not two; changing the font makes it *more* important,
not less. Its `SHARED` block is the place to change, and both halves read from it already.

- [x] **Step 6: The commit bar moves to the top strip** (§0 item 7) and the `PageHeader` H1 goes —
rule 1, *"no page heading repeating the rail's active item"*.

- [x] **Step 7: `Notes` loses its panel but keeps its fields** (§4). Both `TextField`s become kit
inputs in the document's narrative column, under the procedure.

- [x] **Step 8: Tests.** The existing `CaseEditorPage.test.tsx` (524 lines) must pass unedited except
where it asserts a `Panel` title. Add:

```tsx
it('offers the three starts as described cards, not bare buttons', () => {
  renderNewCase();
  expect(screen.getByTestId('CaseEditor.Start.FromCase')).toHaveTextContent(/Copy the shape/);
  expect(screen.getByTestId('CaseEditor.Start.Paste')).toHaveTextContent(/⌘V/);
});

it('scaffolds the blank document with a hint per keyword', () => {
  renderNewCase();
  expect(screen.getByText(/the state the test starts from/)).toBeInTheDocument();
  expect(screen.getByText(/the action under test/)).toBeInTheDocument();
  expect(screen.getByText(/what must be true afterwards/)).toBeInTheDocument();
});

it('renders the rail rows quiet — no filled input surface at rest', () => {
  renderNewCase();
  const priority = screen.getByRole('button', { name: /Priority/ });
  expect(priority.className).not.toMatch(/\bbg-field\b/);
});
```

- [x] **Step 9:** Commit. No gate here — the build, lint and suite run once in T17.

```bash
git add frontend/src/features/cases/CaseEditorPage.tsx frontend/src/features/cases/CaseDocument.tsx \
        frontend/src/features/cases/CaseEditorPage.test.tsx frontend/src/features/cases/CaseDocument.test.tsx
git commit -m "feat(frontend): T7 — the case editor as the render draws it"
```

---

### T8 — Family 9, States

**Deliverable:** `NotFoundPage` and `HelpPage` render from the kit, and the refusal + notification
matrix all 42 screens inherit is in place: a failure never auto-dismisses, an error names the field
and links to it, every failure carries a six-character reference and a timestamp. **`notistack` is
imported by no file** when this task ends.
**Depends on:** T4 (`StateBlock`), T6. **Runs before T7** (see the task table).

**Files:** `frontend/src/features/NotFoundPage.tsx`; `frontend/src/features/help/{HelpPage,RoleMatrix,prose}.tsx`
(**`sections.tsx` needed no edit** — 898 lines of copy that imports only `./prose` and
`./RoleMatrix`, never MUI, so porting the primitives ported all eleven sections);
`frontend/src/notifications/{NotificationsProvider.tsx,notificationsContextValue.ts,useNotify.ts}`;
`frontend/src/app/Providers.tsx`; `frontend/src/app/queryClient.ts`; and the 13 other notistack
files of §1.

Help is deliberately the fourth document surface (§3) — *written in the product, on the product's own
document surface, using the product's own chart components*.

**The two notification surfaces today, and what each becomes.** `Providers.tsx` documents the
split: success/info toasts go through notistack's `SnackbarProvider` (bottom-right, 4s auto-hide,
`maxSnack={3}`); failures go through `NotificationsProvider` (top-right, never auto-dismisses) and
are raised only by `app/queryClient.ts`. That split is the point and survives the swap — *"one
provider cannot be both auto-hiding and persistent"*. `sonner` handles both with one `<Toaster>`
(`toast.success` with a duration; `toast.error` with `duration: Infinity`), so:

**What landed, where it differs from the steps below.**

**The two surfaces stayed two.** Step 2 below says to *"route `NotificationsProvider`'s failure
surface through `toast.error` with no auto-dismiss"* — that was **not done, and should not be**.
`sonner` renders a list it owns; it has no notion of dedupe-by-cause, of the ten-second roll-up
window, or of the *"+N more"* / *"Dismiss all"* controls, all four of which
`NotificationsProvider`'s own header calls out as *"a defect class if it's missing"*. Routing it
through sonner would have deleted three of the four to gain nothing: the property being ported is
*never auto-dismisses*, and the surface already had it. So the **split** moved to sonner, not the
surface — `<Toaster />` replaces `SnackbarProvider` in exactly its position and takes the
success/info/warning half; the persistent half kept its portal and its logic, and lost only its MUI
chrome (it now draws the render's `.toast`: `ground` ground, a 3px coloured left border, the mono
`ref` line).

**The pages call a module function, not a hook.** `useNotify()` throws outside
`NotificationsProvider`, and `test/TestProviders.tsx` deliberately does not mount one — so a
`useNotify`-shaped hook would have meant *adding* a provider to the six tests rather than dropping
one from them, which is the opposite of Step 3. `sonner`'s queue is itself a module singleton
(only `<Toaster />` needs mounting), so the transient surface is `notifications/toast.ts` —
`toast(message, { variant })`, imported directly. The six call sites changed by exactly one
identifier each.

**`variant: 'error'` is where the rule was actually being broken.** Under notistack the caller's
`variant` chose the copy and the *provider* chose the timer, so the three page-level
`variant: 'error'` calls (`BoardPage` on a rejected drag, `ExecutionPage` twice on a refused
recording) were failures on a four-second auto-hide — §7 broken by a call site, past every gate.
`toast.ts` is now the only thing that sets a duration and its `error` arm has none.

**The 404 is still generic, on purpose.** The render draws it *inside a project* — *"There is no
TC-9999 in Reflex Payments; the highest case key in this project is TC-1284"* — but this component
is the router's catch-all (`path="*"`, and `ProjectGuard` for an unresolvable project id), so it
has neither a key nor a project to name. The half of the copy that survives is the *cause*, and it
was kept: keys are per project. A contextual 404 belongs to the screen that knows the context.

**One acceptance check cannot hold as written.** Step 4's `grep -rn "notistack" frontend/src` → no
matches contradicts Step 3's *"strike it through there"*, and CLAUDE.md's rule that a superseded
decision is written down struck through rather than deleted. **No file imports or calls notistack**
— that is the check that was met. Three doc comments still name it, struck through, in the three
places a reader needs the history: `app/Providers.tsx` (why `<Toaster />` sits where it sits),
`notifications/toast.ts` (what it supersedes and which defect that fixed) and
`notifications/NotificationsProvider.tsx:22` (the one Step 3 named).

- [x] **Step 1:** Port `NotFoundPage` and the four help files.
- [x] **Step 2:** Mount `ui/sonner`'s `<Toaster />` **where `SnackbarProvider` sits** in
`Providers.tsx` — the nesting order is load-bearing and documented in the file: the query client is
constructed innermost because its global error handlers consume the notification and
session-recovery providers. Route `NotificationsProvider`'s failure surface through `toast.error`
with no auto-dismiss, keeping `queryClient.ts` as the one place a failure is raised.
- [x] **Step 3:** Give the six pages a `useNotify`-shaped success/info call instead of
`useSnackbar` — `BoardPage.tsx:15`, `CaseListPage.tsx:4`, `CaseEditorPage.tsx:17`,
`DocPageEditorPage.tsx:18`, `RunDetailPage.tsx:19`, `ExecutionPage.tsx:16` — and drop the
`SnackbarProvider` wrap from the six tests that mount it (`TeamBoardPage`, `BoardPage`, `MyWorkPage`,
`CaseEditorPage`, `CaseDocument`, `DocPageEditorPage`). The fourteenth file,
`NotificationsProvider.tsx:23`, names notistack only in a comment — strike it through there. This
is the only step of the plan that touches pages ahead of their own family task, and it touches
only the import and the call.
- [x] **Step 4:** `grep -rn "notistack" frontend/src` → no matches. **Step 5:** Assert the
never-auto-dismiss rule:

```tsx
it('a failure notification does not auto-dismiss', () => {
  raiseFailure();
  vi.advanceTimersByTime(30_000);
  expect(screen.getByRole('alert')).toBeInTheDocument();
});
```

- [x] **Step 6:** Commit. No gate here — the build, lint and suite run once in T17.

```bash
git add frontend/src/features/NotFoundPage.tsx frontend/src/features/help frontend/src/notifications \
        frontend/src/app/Providers.tsx frontend/src/app/queryClient.ts <the 12 notistack pages and tests>
git commit -m "feat(frontend): T8 — family 9, the states, and one notification surface on sonner"
```

---

### T9 — TanStack Table — **DONE** (`6de8c9a9`)

**Deliverable:** `CaseGrid` renders on TanStack Table with `manualPagination` / `manualSorting` /
`manualFiltering` driving the existing query hooks; `@mui/x-data-grid` is imported by no file.
**Depends on:** T3 (`kit/Table`)

**Files:** `frontend/src/features/cases/CaseGrid.tsx` (336 lines), `frontend/src/components/kit/Table.tsx`

**What landed, where it differs from the steps below:** TanStack Table **v9.2.4** — `useTable` and
`tableFeatures({ rowPaginationFeature, rowSelectionFeature, rowSortingFeature })`, not v8's
`useReactTable` + `getCoreRowModel()`. There is **no `manualFiltering: true`**: that option belongs
to `columnFilteringFeature`, which this grid does not register, so there is no client-side filter
path to switch off — filtering stays the URL's and the server's, which is the stronger guarantee of
the two. `kit/Table`'s `TableHeaderCell` grew a real sort affordance (`sortDirection` + `onSort` →
a `<button>` inside the `<th>` and `aria-sort` on it); its decorative `sortable` prop, which had no
consumer, is gone. The grid now renders its own pager (`Cases.Pager.*`) — `DataGrid` supplied one
and nothing else does. Tooltips are the native `title` attribute rather than a tooltip component
per truncated cell. New file: `CaseGrid.test.tsx`, twelve assertions, none of them a look.

- [x] **Step 1:** `npm install @tanstack/react-table`
- [x] **Step 2:** Port the column definitions one-for-one. **Server-side paging/sort/filter is not
optional** — it is how the grid behaves today and a client-side load of the full table is the named
anti-pattern. Deterministic default sort with a unique tiebreaker is a `ui-rules` requirement and
the existing behaviour; keep it.
- [x] **Step 3:** The kit's Table rule — *a quiet second line in the cell rather than a twelfth
column* (UX#113 / UX#111) — is the one visual change. Everything else is a mechanical port.
- [x] **Step 4:** `grep -rn "x-data-grid" frontend/src` → no matches. Gate, commit.

---

### T10 — Family 1, Index & table — **DONE** (`b2b1509e`)

**Deliverable:** CaseList · CaseSearch · RunList · PlanList · Proposals · Reports · Ingestions ·
RunSchedule · Artifacts · Projects all render on `kit/Table`, against `renders/01-index-tables.html`.
**Depends on:** T9

Ten screens, one skeleton. The render's rules: filters as removable chips in one row under the
toolbar; **proportion before number**; an em dash meaning *no value* rather than zero; and rule 8 —
*a count is a link to its rows, and the URL carries the filter*. Filter state lives in the URL
(`useSearchParams`) and that does not change.

**What landed, where it differs from the steps above:**

- **`PageHeader` stays on all ten**, and the render says it should not — *no page heading repeating
  the rail's active item*. It is a **shared** component (54 callers), it owns the `<title>` element
  and the route-change announcement, and nothing replaces either. Removing it from ten screens with
  no substitute is an a11y and tab-title regression, so it is **T16's** call, alongside the rest of
  the shared MUI tier. `Panel` **is** gone from every one of the ten — that one is rule 1's exact
  prohibition and cost nothing, and each panel's `data-testid` survives on the plain `<section>`
  that replaced it.
- **`CaseFilterBar` is untouched**, so *filters as removable chips* is delivered on `CaseSearchPage`
  (which owns its filter row) and **not** on `CaseListPage` (which delegates it). The bar is not one
  of the ten files this task owns.
- **`ReportJobCard` is untouched**, so *Generated reports* stays a card list rather than the render's
  table. Same reason.
- **`RunSchedulePage` did not move its switch to the first column**, which is the render's one
  structural note on frame G. The row's enabled flag is an Edit-dialog field; promoting it to a row
  control is a write per click with no confirmation, which is a behaviour change and not a port. The
  rest of frame G is applied — the paused row stays at reduced weight with its reason.
- **`ProposalsPage` carries `data-testid="SmartToyOutlinedIcon"` on a lucide `Bot`.** That is MUI's
  own auto-generated id, and `ProposalsPage.test.tsx` queries it by that exact name; no test may be
  edited here, so the id outlived the icon it was named after. Renaming it is a one-line change for
  whichever task may edit that test.
- **`RunSchedulePage` dropped the MUI slot-prop test-id helper** — a Radix switch takes a `data-*`
  directly. Two citations of that module remain elsewhere, as T16's D3 expects; this task added no
  third.
- **Three tone maps are page-local**, not in `lib/labels.ts`: a run's badge tone (which is its
  *result*, so a completed run with failures is red — the render draws exactly that), a plan's, and
  an ingestion's. Each has one caller; the shared layer is earned on the second.
- **`ProjectsPage` draws one `HBar` per card, not the render's three.** `Automated` and `Last run`
  are not on `ProjectSummary`, and a bar over a number the response does not carry is §9.3's defect.
  The one bar's denominator is the largest project in the list, which is the only proportion the
  data supports.
- **`PlanListPage` gained the render's amber banner** about Pass rate and Coverage, conditional on
  every row being null in both — so it disappears by itself the day the server computes one.
- **`CaseSearchPage` and `IngestionsPage` grew rule 8 where they had it missing**: the search pager
  is now the page's own (`TablePagination` went with MUI), and an ingestion's non-zero `Unmatched`
  count is a link to the job that lists them. Zero is not a link — a link to an empty list is a
  promise the row cannot keep.

- [x] **Step 1–10:** One screen per step.
- [x] **Step 11:** Commit. No gate here — the build, lint and suite run once in T17.

---

### T11 — Family 2, Record as document — **DONE**

**Deliverable:** CaseDetail · RunDetail · IngestionJob · Proposal · CaseHistory · SuiteHistory ·
ReportView render at the 74ch measure on `kit/DocumentSurface` and `kit/Timeline`, against
`renders/02-record-as-document.html`.
**Depends on:** T7

`ReportViewPage` keeps its light surface (D1, §9.1) — a PDF preview drawn dark misrepresents the
file being downloaded. It is the one screen in the product that is deliberately not dark, and the
port must not "fix" it.

**What landed, and the five things the code disproved.**

**1. Three of the seven drop `PageHeader`; four keep it.** The render draws A (case detail), D
(proposal) and E (case history) with **no page heading at all** — crumb and lens in the top strip,
and the document's own title as the subject — so those three follow T7: `<title>` and the arrival
`useAnnounce()` raised by hand, the breadcrumb rendered into `TopStripSlot`. B, C, F and G keep
`PageHeader`, because the render draws each of them with a real title row and a table under it, and
the component is T16's to port. **On `CaseDetailPage` the crumb slot sits OUTSIDE the `DataRegion`
and the action bar inside it**: the crumb is the only non-rail way back to the list, and a failed
read that takes it with it is a failure escaping the response-dependent subtree.

**2. `kit/ProcedureLine` could not carry the read view, in two places** — the second kit widening a
screen task has needed, after T7's `RailRow`, and both additive:
`ProcedureKeyword` gained **`but`** (`StepKeyword` has five members, and folding `BUT` into `And`
prints a word the author did not write), and `ProcedureLineProps` gained **`keywordTestId`** (the
component owns the *word* as well as the hue, so `CaseDetail.Steps.Row.Keyword` — which the existing
suite asserts on — has nowhere else to live). The four-column steps table is gone with them: read
and write are the same document or the thesis is empty, so `testData` and `expectedResult` are the
quiet lines under the action, which is `Table`'s own UX#113 rule applied where the cell became a
line.

**3. Neither hand-rolled widget could be a Radix primitive, and the reason is the harness.**
The lifecycle menu (`CaseDetail.StatusMenu`) and the reviewer picker (`Review.Assign.Choice`) are a
plain disclosure over `role="menu"` / `role="listbox"` with a scrim: `@radix-ui/react-select` calls
`target.hasPointerCapture`, jsdom implements neither it nor a polyfill, and `src/test/setup.ts` is
not a place to bend the app to the harness. Both are driven by existing tests. **The Radix
`Dialog` is proven** — `CaseDetail.Feature` opens, fetches and closes under jsdom, so `ui/dialog`
needs no such retreat.

**4. `Review.Assignee` is not a `kit/Chip`, and `PermissionGate` still owns the refusal tooltip.**
§5's chip is *a value the reader owns and is a button*; an assignee is a name with a withdrawal,
so borrowing the component would have meant inventing a click that does nothing — and its
`data-testid` lands on the inner button, where `within(chip).getByTestId('Review.Assignee.Remove')`
cannot reach it. MUI `Tooltip`s became native `title` attributes throughout (`RunListPage`'s
precedent), except on a gated control, where `PermissionGate` already wraps the denied branch.

**5. `components/ResultBar.tsx`, `StatusChip.tsx`, `PageHeader.tsx`, `Identifier` and `DataRegion`
stay MUI and stay imported.** They are shared with screens T12/T15 own and are T16's to port;
importing them costs no `@mui` in these eight files, which is what the task's grep asks. What did
get replaced in place is `PassRateSummary` / `ResultCountsCell` / `DenominatorToggle` on the two
screens that used them — inline numbers plus `kit/ResultBar`, keeping every test id including
`SuiteHistory.Denominator.EXECUTED` / `.TOTAL`. `lib/muiTestIds.ts` is now imported by
`RunDetailPage` and `ReviewPanel` no longer, as D3 assigns.

Data test ids are byte-identical apart from eight that named a wrapper this port deletes —
`CaseDetail.Header`, `CaseDetail.Ownership`, `Proposal.Header`, `Proposal.CasePanel`,
`Proposal.SummaryPanel`, `CaseHistory.Header`, `SuiteHistory.Runs`, `SuiteHistory.Denominator` —
each checked to be referenced by nothing in the repository. No test file was edited.

- [x] **Steps 1–7:** One screen per step. **Step 8:** Commit — no gate here; T17 runs it.

---

### T12 — Family 3, Execution — **DONE**

**Deliverable:** `ExecutionPage` renders against `renders/03-execution.html` with the collapsed rail
from T5, number keys for verdicts, the current step at full ink, the failure panel in place of a
dialog, and ~~*last time* on screen before it is needed~~ — **not built; see 5 below.**
**Depends on:** T5, T11

**Files:** `frontend/src/features/runs/ExecutionPage.tsx` and its test; the route is
`/projects/:projectId/runs/:runId/executions/:executionId` (`router.tsx:513`) and T5 already
collapses the rail on it. The failure panel replaces `features/runs/BlockedDialog.tsx`'s dialog
form — the file whose comment cites `muiTestIds` (D3); its `inputTestId` import goes here.

**What landed, and the five things the code disproved.**

**1. The rail's four verdict buttons record the CASE here, so the number keys had to bind the
step.** The render draws one verdict control, in the rail, and its caption says *"Records step 3
and moves to step 4"* — one result model, where this app has two: a per-step mark (`stepResults[]`)
and the case verdict the run counts (`statusId`). `p f b s` are the case verdict and the existing
suite pins them, so `1`–`4` bind **the step the tester is on** and advance, exactly as the plan's
own Step-2 assertion (`Execution.Step.1.Verdict`) says. They are therefore printed on that step's
own marks rather than on the rail's four buttons, where the digit would name a key that records
something else. The rail buttons print `P F B S`, read from `EXECUTION_SHORTCUTS`.

**2. The number keys are a second source of key truth, and `executionKeys.ts` was not this task's
to open.** That module's whole argument is that a shortcut written twice drifts, and the overlay
renders the map — so `1`–`4` are absent from the help. The render's own note argues for exactly
that (*"printed on the buttons, not hidden in a help page"*), and the existing overlay arm asserts
`rows === EXECUTION_SHORTCUTS.length`, so four more rows would have needed the test file edited too.
**Follow-up:** fold the four into `EXECUTION_SHORTCUTS` with a `markStep` action when that file is
next open, and delete the local map in `ExecutionPage.tsx`.

**3. The failure panel replaced a dialog, but not `BlockedDialog`'s — Blocked stays modal.** Frame
B is about verdict **2**: marking a step Failed turns the rail's *Record the result* section into
*Failed at step N*, with the description field, the inline refusal, and the step still on screen
(UX#55). `BlockedDialog` is the other shape and stays a dialog: **Grid mode is a second caller with
no rail at all**, the checklist is a gate rather than a description, and the existing suite pins
`b` → `findByRole('dialog')`. Two spellings of Blocked, one per mode, is the drift the component
was extracted to prevent. It is ported to Radix + the kit, and both the `inputTestId` import and the
comment citing it are gone, as D3 assigns.

**4. Recording Failed is now refused without a description, and only when there is a failed step to
describe.** The render's *"a failure without a description is refused, and says so"* is scoped to
the panel: with a step marked Failed and its box empty, the case's Failed button is disabled and
`f` announces the refusal. With no failed step there is nothing the panel is about, and `f` records
as before — widening it further would have made a documented shortcut lie.

**5. *Last time* could not be built without editing an unedited test, so it is not built.** It needs
the previous run's verdict for this case, which is `GET /projects/{id}/cases/{caseId}/history` —
a read this screen does not make. Adding it fails **every** arm of `ExecutionPage.test.tsx`: the
double ends in `throw new Error('unmocked read')`, and since §202 an unexcused failed read fails the
test that caused it. The arm belongs in that file's `mockReads`, which T12 was told not to touch.
Nothing was faked in its place. **Follow-up:** add the `/cases/[^/]+/history` arm and the rail
section together, in one change that may edit the test.

Data test ids are byte-identical apart from the six `PageHeader` minted, which this screen drops for
T11's reason (crumb and lens in the top strip, the case's own title as the document's subject):
`Execution.Header`, `.Header.Breadcrumbs`, `.Header.Breadcrumb`, `.Header.Title`, `.Header.Meta`,
`.Header.Actions` — each checked to be referenced by nothing in the repository. `Execution.CaseLink`
and `Execution.Quarantined` moved with the meta into the top strip and kept their names;
`Execution.Breadcrumbs` and `Execution.Step.<n>.Verdict` are new. No existing test was edited.
`ShortcutOverlay.tsx`, `ExecutionQueue.tsx`, `ExecutionGrid.tsx`, `EvidencePanel.tsx`,
`DefectPanel.tsx`, `ExecutionAssignee.tsx` and `StepEvidence.tsx` stay MUI and stay imported —
T16's, like T11's item 5.

- [x] **Step 1:** Port. **Step 2:** Assert the keyboard contract:

```tsx
it('records a verdict from the number key for that verdict', async () => {
  renderExecution();
  await userEvent.keyboard('1');
  expect(await screen.findByTestId('Execution.Step.1.Verdict')).toHaveTextContent('Passed');
});
```

  *Landed as written, plus three arms the port earned: that the second press marks the second step
  and not the first again, that a digit typed into the notes box is a digit, and that `2` opens the
  failure panel in the rail with no dialog and refuses until it is described.*

- [x] **Step 3:** Commit — no gate here; T17 runs it.

---

### T13 — Family 4, Board & people — **DONE**

**Deliverable:** Board · MyWork · TeamBoard · OrgChart render against `renders/04-board-and-people.html`;
`@mui/x-tree-view` is imported by no file.
**Depends on:** T3

The board is the only drag surface, *so the only one where WCAG 2.2's drag rule bites* — every drag
needs a single-pointer alternative. `@dnd-kit` stays; it is not MUI.

`features/cases/ClassificationPicker.tsx` is the ~~second~~ **only** `x-tree-view` consumer
(`CaseGrid.tsx` does not import it — §1) and is replaced with a flat `components/ui/command`
combobox — classification values are a flat vocabulary per dimension, not a tree, which is why the
TreeView was carrying one node depth. It is a case-authoring component, not a board one; it sits in
this task only because the tree-view removal does, and it drops its `inputTestId` import with the
port. **Depends on T8** as well, because `BoardPage.tsx:15` calls `useSnackbar` until then.

**Files:** `features/board/{BoardPage,MyWorkPage,TeamBoardPage}.tsx`,
`features/org-chart/OrgChartPage.tsx`, `features/cases/ClassificationPicker.tsx`, their tests.

- [x] **Step 1–4:** One screen per step. **Step 5:** the tree-view grep returns no matches, then
commit — no gate here; T17 runs it.

**What landed, where it differs from the steps above:**

**The picker is not a `command` combobox, and the reason is the test suite, not taste.** Three of
`ClassificationPicker`'s own tests — unedited, per the task — click a value and assert `toBeChecked()`
on the **initial render**, so the vocabulary cannot live behind a popover at all; and `cmdk` selects
its first item on mount and calls `scrollIntoView`, which jsdom does not implement and
`src/test/setup.ts` (not this task's file) does not polyfill. So a SINGLE dimension is a native
`<details>` disclosure over a `role="listbox"` of `role="option"` buttons — the render's quiet rail
row, with an expanded state the browser reports for free — and a MULTI dimension is a flat checkbox
list indented by depth. **`kit/RailRow` is deliberately not the trigger:** its props cannot carry
`aria-expanded`, and a `<button>` is not a native disclosure. **The checkbox is a native `<input>`,
not `components/ui/checkbox`:** the kit primitive is a Radix `<button>`, which a wrapping `<label>`
can only reach through label-activation forwarding — the one path whose double-fire behaviour
differs between environments, on the exact control three tests drive directly. The props contract
is byte-identical, so the `CaseEditorPage` port running in parallel (T7) needed no coordination.

**The org chart drops the render's legend.** Frame C decodes its three fills with one; our cells
carry the arm **in words** on a second line, so the legend would print those three phrases twice
— and `OrgChartPage.test.tsx` asserts `getByText('Direct grant')` in the singular. Colour is
therefore never the only carrier of the arm, which is the stronger reading of the render's own note.
`Teams` and `Gaps` move into the render's `.side` column beside a **bare** matrix pane (rule 1);
they are not put behind the render's lens tabs, because gaps are *"the point of the screen"* and a
tab is where you put what you are content for the reader not to see.

**Both headers are local, not `PageHeader`.** Four pages now render the render's `.top` strip
themselves — crumb, `<h1>`, subtitle, actions — and raise their own arrival announcement and
`<title>`, the way `HelpPage` has since T8. `PageHeader` and `Panel` are still MUI and still
imported by ~40 other screens; T16 owns them.

**`features/board/TaskCreateDialog.tsx` is deliberately untouched** and still imports
`@mui/material`. It is not in this task's file list, no render draws it, and its test drives an MUI
`Select` through `getByRole('combobox')` — a port has to decide what replaces that widget under
jsdom, which is a decision for the lane that owns the other dialogs. It is the only MUI import left
under `features/board/**` or `features/org-chart/**`.

---

### T14 — Family 5, Authoring — **DONE**

**Deliverable:** Docs · DocPageEditor · FeatureImport render against `renders/05-authoring.html` at
the 70ch prose measure.
**Depends on:** T11

The three rules the render carries: coverage is **per paragraph, not per page**; draft pages stay in
the tree, dimmed; and *Write one* sits on the untested clause, opening the case editor pre-seeded
with that paragraph as the objective. The import shows the ~~**diff, not the count**~~ — see item 2
below — never deletes, and its button counts what it will do.

- [x] **Steps 1–3:** One screen per step. **Step 4:** Commit — no gate here; T17 runs it.

**What landed, and the four things the code disproved.**

**1. *Write one* carries the objective and nothing reads it — the one half of a rule this task
could not finish.** The button is on the untested clause, it opens
`/projects/:projectId/cases/new?objective=<the paragraph>`, and `CaseEditorPage` reads **`suiteId`
from the URL and nothing else** (`CaseEditorPage.tsx`'s `carriedSuiteId`). Seeding the objective is
one more line in that file's `defaultValues` — T7's file, outside this task's ownership, so the link
is written carrying the parameter and the line is all that is left to add. Recorded rather than
reached across for, per the lane's file list.

**2. The render's per-step diff is not on the wire, and the contract is what would have to change.**
`ImportRow` in `openapi.yaml` is `path · line · feature · scenario · automationKey · action ·
reason · caseKey · preconditions` — **no steps and no diff**, so *"struck-through old text beside
the new, per changed step"* cannot be built from any answer the server gives. What the port does
instead is drop the five-column table for the render's `.scn` card: verdict, name and case key in
the header; the reason verbatim and the provenance under it; the preconditions the row would gain
listed by title. That is "not a count" as far as the wire allows — every scenario says what will
happen to it and why — and the counts move to the right rail where the render puts them. §4 says
the *spec* is amended where the build disagrees with a render; this is one for it, and the step diff
belongs in the plan that widens `ImportRow`.

**3. Three MUI-shaped widgets could not become Radix ones, for T11's reason and one more.** The
link dialog's case type-ahead and suite picker are a `role="combobox"` over a `role="listbox"` this
file owns — `@radix-ui/react-select` calls `hasPointerCapture`, jsdom implements neither it nor a
polyfill, and `BlockLinks.test.tsx` drives both pickers by role, unedited. The editor's block-type
and depth pickers are **native `<select>`s** (`ui/native-select`, T15's lane), which is also what
made the one test-helper change below possible. The **Radix `Dialog` is proven again** — all three
dialogs here are `ui/dialog`.

**4. One test helper changed, body only, and no assertion moved.**
`DocPageEditorPage.test.tsx`'s `choose()` reached through `.MuiInputBase-root` to a portalled
listbox; it now calls `userEvent.selectOptions`. Exactly the shape T15 took with
`NotificationSettingsPage.test`'s `chooseFrom`, and for the same reason: a helper whose body is the
MUI DOM cannot survive the MUI DOM going away. `DocsPage.test.tsx`, `DocsSearch.test.tsx`,
`BlockLinks.test.tsx` and `FeatureImportPage.test.tsx` are untouched.

**Also worth keeping.** The coverage margin is gone: the verdict is a line **under** the clause, and
the page aggregate survives only in the rail and only as a count, never a percentage — a ratio there
would put back exactly the averaging the render's note calls out. All four screens render their own
`.top` strip, raise their own `<title>` and arrival announcement, and drop `PageHeader`/`Panel`
(T13's precedent; T16 owns those two). Data test ids are byte-identical apart from four that named a
wrapper this port deletes — `Docs.Header`, `DocEditor.Header`, `DocEditor.Panel`,
`FeatureImport.Header` — each checked to be referenced by nothing in the repository; two families
were **added**, `Docs.Block.Coverage.WriteOne` and `DocEditor.AddBlock.<type>` for the render's
six-way insert row. `lib/muiTestIds.ts` is imported by no file under `features/docs/**`, which it
never was.

---

### T15 — Families 6 and 7, Analytics and Settings

**Deliverable:** Dashboard and PlanStatistics render per §6; the nine settings screens render against
`renders/07-settings-admin.html`.
**Depends on:** T10

§6 is computed, not judged, and two of its results are **defects in what ships today**:
`--color-untested` becomes the empty track rather than a fill (T3's `ResultBar` already carries
this), and *Activity, last 30 days* is drawn as three small multiples rather than one three-series
chart, because the product's accents measure 2.5 ΔE apart under deuteranopia. No pie, donut or gauge.
Y axes start at zero.

Settings' rules: every setting states its blast radius against live data; ~~every refusal names a
person who can grant it (T4's refused state)~~ every refusal names the permission and the role that
can grant it — a person cannot be named on this codebase, see T4; revoked and expired rows **stay
on screen, dimmed**, with the date and reason — rule 7, *disable, don't hide*.

**Thirteen screens, not eleven.** §8 lists nine settings/admin pages; the tree has two more that
the family map never placed — `features/settings/TrackerSettingsPage.tsx` (project-level tracker
config, alongside Review/Classification/Notifications) and `features/account/TrackerConnectionsPage.tsx`
(alongside `AccountSecurityPage`). Both are settings by job and take family 7's render. Together
with Dashboard and PlanStatistics that is 13 screens; this is the task §6 says to split — split it
**after step 2** (analytics) and **after step 8** (the project-scoped settings), and keep the
order. Six `lib/muiTestIds` importers are this task's (`ReviewSettingsPage`, `TrackerSettingsPage`,
`NotificationChannelDialog`, `TeamDialog`, `TeamMembersDialog`, `ProviderFormDialog`) — each port
drops its own; the full ownership map is under D3.

- [x] **Steps 1–2:** Dashboard, PlanStatistics. **Steps 3–8:** Review, Classification,
Notifications, Trackers, ProjectAccess, AuditLog. **Steps 9–13:** Teams, ApiTokens, SsoProviders,
AccountSecurity, TrackerConnections. **Step 14:** Commit — no gate here; T17 runs it.

**DONE** (`e330de34` steps 1–2 · `8390db78` steps 3–8 · `bc7d0e7a` steps 9–13). Where it differs
from the steps above:

- **Thirteen screens became twenty-eight files.** The task's own list names the pages; the
  `grep` in its "done when" is over `features/{dashboard,settings,admin,audit,account}/**`, which
  also holds ten dialogs nobody had assigned — Grant/Invite/Remove/IssueToken/RevokeToken/
  DeleteTeam/ClassificationValue, plus `RoleAssignmentTable` and `RoleReference`. They are ported
  here, and they had to be: `ProjectAccessPage` renders two of them.
- **Three new kit files**, none of them in §5's nine and all three earned by repetition across
  this task's screens: `kit/FigurePanel` (the render's `.fig` frame — `Figure.tsx` beside it is
  the *drawings*), `kit/SettingRow` + `SettingGroup` (family 7's two-column rule, with the
  blast-radius slot), `kit/Field` (label above, one column, refusal under the control —
  `ConditionEditDialog` has a private copy from T7 that should collapse onto it).
- **`ui/native-select.tsx` is new, and it is a decision, not a convenience.** Every picker on
  these screens chooses one word from a closed set of three or four. The platform control is
  keyboard- and screen-reader-operable with no code of ours, opens as the system picker on a
  phone, and — the finding T13 recorded — is the one `@radix-ui/react-select` cannot be under
  jsdom, whose `hasPointerCapture` it calls and `src/test/setup.ts` does not polyfill.
- **Five test files were touched and no expectation in any of them moved.** Four MUI-internal
  select helpers (`.closest('.MuiInputBase-root')` → a portalled listbox) in
  `NotificationSettingsPage.test`, `GrantRoleDialog.test` and `IssueTokenDialog.test`, and one
  `.closest('.MuiFormControl-root')`. They addressed markup this task deleted; leaving them would
  have handed T17 four red files with nothing to fix but the helpers.
- **`ReviewSettingsPage`'s blast-radius panel became the rows' own consequences**, keeping every
  `ReviewSettings.Impact.*` testid. Family 7's rule is that the consequence belongs *to the
  setting*, counted before the control moves.
- **`AuditLogPage` is the kit's `Timeline`**, per §5's three owners for it, and its
  `TablePagination` is a stated window (`25–50 of 1 942`) with Newer/Older.
- **Two test files still import `@mui/material/styles`** — `HealthPanel.test` and
  `AccountSecurityPage.test` wrap in `ThemeProvider theme={theme}`, as `src/test/TestProviders.tsx`
  and three other suites do. That is `app/theme.ts`'s deletion, which is **T16's**.
- Two second copies are left named rather than silently made: `PlanStatisticsPage`'s `PLAN_TONE`
  (T10 put a private one in `PlanListPage`, which this lane may not edit) and the two
  `*StateColors` maps in `lib/{apiTokens,labels}.ts`, whose one consumer each is now a local
  `satisfies Record<…, BadgeTone>`. Both collapse when MUI goes.

---

### T16 — MUI removed, preflight on — **DONE** (`98ffb79a`)

**Deliverable:** `@mui/*`, `@emotion/*` and `notistack` appear in no import and in no
`package.json`; Tailwind's Preflight is imported; `lib/muiTestIds.ts` is deleted along with the one
comment that cites it.
**Depends on:** T6–T15

**Files:**
- Delete: `frontend/src/lib/muiTestIds.ts`, `frontend/src/app/theme.ts`
- Modify: `frontend/src/components/ui/ui.test.tsx:20` — the second citation (the first,
  `BlockedDialog.tsx:105`, went with T12)
- Modify: `frontend/src/index.css`, `frontend/package.json`
- Modify: `frontend/src/app/themeTokenParity.test.ts` — its subject no longer exists
- Modify: `frontend/src/app/Providers.tsx` — `ThemeProvider` + `CssBaseline` go; the
  `defaultMode="system"` toggle they carried must be re-homed on `data-mui-color-scheme` (or its
  renamed successor) before they do, or the theme toggle becomes the no-op CLAUDE.md warns about
- Modify: `docs/specs/assets/renders/tcms.css:6-7` — the header names `theme.ts` (§5)

**What landed, and where it differs from the steps below.**

**Step 1 failed, and the diagnosis in the step is wrong.** The step says *"a single hit here means
a screen task is unfinished"*. **Zero `*Page.tsx` files imported MUI** — every screen task had done
its job — and 81 other files did:

| Group | Files |
|---|---|
| Shared `src/components/*` | 20 — `Identifier` (55 importers), `DataRegion` (47), `PermissionGate` (41), `PageHeader` (37), … |
| Feature dialogs, panels and tables | 48 — cases 16, runs 12, plans 7, reports 5, ingestion 3, artifacts 2, +4 |
| App / auth / a11y / test infra | 10 — `AppShell`, `Providers`, `router`, `theme.ts`, `SessionGate`, `SessionRecoveryProvider`, `AnnouncerProvider`, `TestProviders` |
| Test files | 8 — `ThemeProvider` wraps and four MUI-internal queries |

**This was deferred into T16 on purpose and then not written down here.** T11's note says of five
of the shared components: *"they are shared with screens T12/T15 own and are **T16's to port**"*,
and T14 says the same of `PageHeader`/`Panel`. T16's own **Files** list never enumerated any of it,
and **nothing in the plan ever owned the 48 feature components**. So the omission is this section's,
not the screen tasks'. All 81 are ported here, on the instruction to absorb rather than split.

**`app/colorScheme.tsx` is new, and it had to land before `ThemeProvider` could go.** Step 6's own
warning is the whole reason: `index.css` hangs the dark palette off one attribute on `<html>` and
MUI's provider was the only thing writing it, so deleting the provider first leaves a toggle that
typechecks, lints and does nothing. The attribute is renamed with the port —
`data-mui-color-scheme` → **`data-color-scheme`** — because the old name was MUI's contract rather
than a description; `index.css` and `tokenSystem.test.ts` are its only two readers, ever. The
stored key moves with it (`mui-mode` → `tcms-color-scheme`), which costs each user one re-pick and
saves shipping a reader for a vocabulary this app no longer has.

**Two kit components are new and neither is in §5's nine.** `kit/Combobox` and `kit/MultiPicker`
replace **nine** `<Autocomplete>`s across seven files. Neither is built on Radix's `Select`, for
the reason `ReviewPanel` and `LinkBlockDialog` already record: it calls `hasPointerCapture`, jsdom
implements neither it nor a polyfill, and `src/test/setup.ts` is not a place where the application
is bent to suit the harness. `features/docs/LinkBlockDialog.tsx` keeps the private copy T14 wrote —
the same relationship `kit/Field` has with `ConditionEditDialog` — and should collapse onto the
shared one next time it is open.

**`components/ResultBar`'s own `ResultBar` is deleted, not ported.** `kit/ResultBar` has been the
one on screen since T10 and this one had no importer left. Its three figure helpers survive
(`PassRateSummary`, `ResultCountsCell`, `DenominatorToggle`) because the kit has no equivalent;
`PlanStatisticsPage` is their one caller, exactly as T15 predicted.

**Three things moved out of `sx` into places that can hold them.** The `@keyframes` behind
`DataRegion`'s refresh hairline is in `index.css` now — Emotion was the thing turning an `sx`
keyframe into a real stylesheet rule, and a `<style>` per mounted region would emit one copy each.
`lib/labels.ts`'s `trackerStatusCategoryColors` becomes `BadgeTone`; its dead twin
`trackerConnectionStateColors` is deleted, both as T15 predicted.

**One assertion got weaker and it is recorded rather than quietly changed.**
`PermissionGate.test.tsx` read `getComputedStyle(anchor).display` to prove the `wrapper="block"`
fix. That worked because Emotion injected `sx` into jsdom as a real stylesheet; **jsdom loads no
stylesheet for Tailwind**, so the expression now returns `''` for every class the component could
carry and would pass or fail without reference to the code. It asserts the class instead. That
proves the component *chose* `block`, not that `block` laid out as a block.

**§5's fourth document needed a decision, not just an edit.** The collapsed rail is **64px** in the
build and 60px in the renders. 64 wins and the *spec* is amended (§4 and §9.2), per §4 of this
plan: it is a multiple of the 4px grid the shell sits on and leaves a 40px icon target 12px of
padding.

**No gate ran, per the header directive — but the port was not left unchecked.** Roughly 4,000
lines of this were machine-transformed, and handing T17 a pile of parse errors would defeat the
point of T17 finding *preflight layout damage*. So three non-gate checks ran instead: every one of
the 86 changed files **parses** (TypeScript's parser, syntax only — no typecheck, no lint, no
suite), every JSX identifier **resolves** to an import or a local, and **no import is unused**.
Those caught real breakage the transformers introduced — six files that did not parse, three whole
components (`GroupCard`, `DeleteSectionDialog`, `SuitePicker`) deleted by a bad splice, `{expr}`
labels flattened to text, interactive chips turned into non-interactive badges, and an attribute
stripper that ate the word `color` out of `var(--color-info)`. **Type errors and layout damage are
still T17's**, and this remains *built and unverified*.

- [x] **Step 1: Prove nothing imports MUI**

```bash
cd frontend && grep -rn "@mui\|@emotion\|notistack" src/ ; echo "exit=$?"
```

Expected: no matches (`exit=1`). A single hit here means a screen task is unfinished; finish it
before proceeding — this is the task that makes the D2 intermediate state end.

- [x] **Step 2: Delete the two files and fix the citations**

`components/ui/ui.test.tsx:20` reads `The whole reason \`lib/muiTestIds.ts\` exists is …`; it is the
test that proved the premise gone, and it now describes a file that is not there — strike the
path through and say the file was deleted here, per D4. **As landed the path is *removed*, not
struck through**, because the two halves of this step contradict each other: `citedFiles.test.ts`
excuses a citation inside a `~~…~~` span, but the step's own acceptance check is
`grep -rn "muiTestIds" frontend/src` → no matches, and a struck-through path is still the string.
The grep wins; the strike-through carries the reasoning, and the comment says explicitly why the
name is not written. (`BlockedDialog.tsx:105` was the other
citation and T12 owns it; if it survived, T12 is unfinished.) **This is not optional**:
`packages/client/src/test/citedFiles.test.ts` scans `frontend/src` for `.ts`/`.tsx` and fails on a
path that is not on disk, and it reddens the **packages** job, not the frontend one — the wrong
place to go looking. `grep -rn "muiTestIds" frontend/src` → no matches is the proof.

- [x] **Step 3: Rewrite `themeTokenParity.test.ts`**

Its job was pinning `index.css` against `app/theme.ts`, and `theme.ts` is gone. What survives is the
half that still has a subject: the rule-5 identity test from T1 Step 6, plus a check that every
token named in `@theme inline` resolves to a declared custom property. Rename the file to
`tokenSystem.test.ts` and say in its header what it stopped checking and why — CLAUDE.md's rule
about writing superseded decisions and their reversal into the record.

- [x] **Step 4: Turn Preflight on**

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/preflight.css" layer(base);
@import "tailwindcss/utilities.css" layer(utilities);
```

- [x] **Step 5: Drop the dependencies**

```bash
cd frontend && npm uninstall @mui/material @mui/icons-material @mui/x-data-grid \
  @mui/x-tree-view @mui/x-date-pickers @emotion/react @emotion/styled notistack
```

`@mui/x-date-pickers` is removed here having never been imported by anything — see §1.

- [x] **Step 6: ~~Run the full gate and expect layout damage~~ → superseded — no gate here**

~~`cd frontend && npm run build && npm run lint && npm run test:run`~~

Preflight resets margins, list styles and heading sizes that MUI's baseline was providing. ~~Expect
findings here and fix them in this task — that is what Step 4 is for, and postponing it to T17 makes
the gate task a second design task.~~ → superseded by the gates-run-once directive at the top of
this file: the damage now surfaces in **T17**, which is therefore a gate pass *and* the design
fix-up for whatever preflight moved. Fix it there, against T17's build output, and record it in
T17's Results section.

- [x] **Step 7: Commit**

```bash
git add frontend/src frontend/package.json frontend/package-lock.json
git rm frontend/src/lib/muiTestIds.ts frontend/src/app/theme.ts
git commit -m "feat(frontend): T16 — MUI removed, preflight on, one styling authority"
```

---

### T17 — Gates and bundle measurement

**Deliverable:** The four gates run green on `shanks`, and the before/after bundle numbers are
recorded in this plan's closing section.
**Depends on:** T16

**This is the FIRST time anything is built or tested since the last gated task (T9)** — the gates-run-once directive at
the top of this file moved every per-task gate here. So expect this task to be a fix-up task, not a
formality: T6–T16 have eleven tasks' worth of unrun `tsc`, lint and Vitest between them, plus the
layout damage Tailwind's preflight causes when T16 turns it on. Budget for that, fix what the gates
find here, and re-run until all four are green before writing the Results section. If the first run
buries you, fix in the order build → lint → tests, since a type error hides everything behind it.

**Gates run on `shanks`, not locally** — the standing user directive in CLAUDE.md. Read
[`shanks-runner-details.md`](../../specs/shanks-runner-details.md) first: `BUILD FAILED` with
`Could not write XML test results` and **zero** assertion failures is an fd-limit problem, not a red
suite.

- [x] **Step 1: Sync and run all four** — green; see §4.1.

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build     # citedFiles.test.ts lives here — D3's trap
~/bin/tcms-remote backend ./gradlew build    # ulimit -n 65535 first
```

The backend gate is in the list because `backend/build.gradle.kts` declares
`frontend/src/lib/permissions.ts` as a test input — a backend test asserts over that file's *text*.
This plan does not touch it, and the run is what proves that.

- [x] **Step 2: Measure the bundle, before believing anything about it** — see §4.1.

```bash
cd frontend && npm run build -- --mode production
```

CLAUDE.md: *"Measure bundle changes before believing them. Splitting DataGrid into its own chunk
looked obviously right and made total JS ~40% worse by defeating tree-shaking."* Record total JS,
the largest chunk, and CSS, against the `5933ca27` baseline. Removing Emotion's runtime and MUI
should be a large win; **"should be" is not a measurement**, and a regression here is a finding, not
a rounding error.

- [ ] **Step 3: Drive the six states in a browser** — **NOT DONE**; see §4.1.

The gates prove it compiles, not that it works — CLAUDE.md's own list of what typechecked and was
still wrong includes a no-op theme toggle and a provider-ordering bug that rendered nothing but the
error page. Run the fault switches against a real backend:

```js
tcmsFaults.status(503, 30); tcmsFaults.hang(); tcmsFaults.expire(); tcmsFaults.drift();
```

`drift()` is the one that matters most here: it must read as **FAILED**, not empty, and T3 Step 3
is the test that claims so. Confirm it on screen.

- [x] **Step 4: Write the results into this file** under a new *Results* section — gate output, the
bundle table, and anything the browser pass found that the gates did not. → **§4.1**.

- [x] **Step 5: Commit**

```bash
git add docs/plans/implemented/007-render-design-implementation.md
git commit -m "fix: T17 — gates green on shanks, bundle measured"
```

---

---

## 4.1 Results — T17

Run on `shanks` (`~/bin/tcms-remote`), 2026-08-29, against the tree at T16 plus the fix-ups this
task made. **All four gates are green.** The bundle was measured against `5933ca27`. The browser
pass is recorded at the end and is the one part that is *not* complete.

### The four gates

| Gate | Result |
|---|---|
| `frontend npm run build` | **PASS** — `tsc -b` + vite, 94 chunks, `✓ built in 626ms` |
| `frontend npm run lint` | **PASS** — oxlint, 0 warnings / 0 errors on 432 files |
| `frontend npm run test:run` | **PASS** — **148 files, 2051 tests**, 0 failed |
| `packages npm run build` + lint + `test:run --workspaces` | **PASS** — client 138, cli 102, mcp 153 tests; 0 lint warnings in all three |
| `backend ./gradlew build` | **PASS** — `BUILD SUCCESSFUL in 9m 52s`, **3577 PASSED / 0 FAILED**, and zero `Could not write XML test results` lines under `ulimit -n 65535` |

**The first run was red: 18 files, 84 tests.** That is what eleven tasks of unrun gates looks like,
and it is worth recording what the failures actually were, because only one class of them was the
preflight damage the task expected.

| What broke | Files | Why |
|---|---|---|
| **Rich markup inside `<option>`** | 8 source files, 12 sites | The MUI→native port carried `MenuItem` bodies — a `<div>`, a `<p>`, a `<span>` — straight into `<option>`, which may hold text only. The browser drops the elements and keeps the text, so the *screens* looked right and the accessible name went missing: `getByRole('option', {name})` found nothing. **A real defect, not a test artefact** — the second line of every plan/run/document row (the case count, the run status, the page status) was not being announced at all. |
| **Tests still driving MUI's portal listbox** | 10 test files | `click(combobox)` then `click(option)`. On a native `<select>` the click selects nothing, so the value never reached react-hook-form and the assertion read `expected "post" to be called at least once`. Replaced with `selectOptions`. |
| **Assertions on MUI internals** | 5 test files | `aria-disabled="true"` → `toBeDisabled()`, `.MuiInputBase-root` → the testid, `MuiAlert-colorWarning` → a new `data-tone`, `CancelIcon` → the kit's named *Remove* button, `calc(6 * var(--mui-spacing))` → `48px`. |
| **`git grep` in `tokenSystem.test.ts`** | 1 | Environment, not code: the sync excludes `.git/`, so `git grep` exits 128 on the build tree and the raw-white-alpha invariant reported itself broken rather than unchecked. Replaced with a filesystem walk of `src`. |
| **Copy pinned by its old wording** | 1 | T15 rewrote the token screen's refusal to name `token:issue`; the test still matched `/do not have permission/i`. Re-pinned on the permission's name, which is the stronger claim. |
| **A stale excuse in `citedFiles.test.ts`** | 1 | `docs/audits/` was excused as absent and 007's design audit put a report back in it. The rule catching its own excuse is the rule working. |

Four of those fixes changed **behaviour, not just tests**, and all four are improvements the port had
silently dropped:

- **`HealthChip` and `QuarantineChip` had lost their accessible description.** MUI's `Tooltip`
  put the help text on the trigger as an `aria-label`; Radix's puts it in content that exists only
  while hovered. `Pill` now spreads props and is the trigger itself, so the label and the colour are
  on one element again.
- **`TableCell` gained `rowHeader`** — `<th scope="row">`. The report provenance block is a
  label/value table and had been rendering as loose pairs of `<td>`s since the port.
- **`Banner` gained `data-tone`.** The tone was only in a `color-mix()` inline style, so nothing
  could read back that the bulk-preview warning is a warning.
- **`CaseFilterBar`'s hierarchical indent** was `paddingLeft` on a `<span>` inside an `<option>` —
  dropped by the browser. It is leading `\u00a0` now, which is what an `<option>` can carry.

### The bundle, measured

`npm run build` at `5933ca27` (in a detached worktree) against the same command on this tree.
Raw bytes of `dist/assets`, and gzip of the same files.

| | `5933ca27` (MUI) | T17 (Tailwind + shadcn) | Change |
|---|---|---|---|
| **Total JS** | 2 095.0 kB | **1 545.7 kB** | **−549.3 kB (−26.2%)** |
| Chunks | 116 | 94 | −22 |
| **Largest chunk** | `CaseListPage` 434.1 kB | `DataRegion` **409.1 kB** | −25.0 kB (−5.8%) |
| Second largest | `index` 342.8 kB | `index` 297.6 kB | −45.2 kB |
| **Total CSS** | 12.9 kB | **67.1 kB** | **+54.2 kB (5.2×)** |
| Total JS + CSS | 2 107.9 kB | **1 612.8 kB** | −495.1 kB (−23.5%) |
| **Gzip, JS** | 672.8 kB | **488.8 kB** | −184.0 kB (−27.3%) |
| Gzip, CSS | 2.3 kB | 12.8 kB | +10.5 kB |
| **Gzip, total** | 675.1 kB | **501.5 kB** | **−173.6 kB (−25.7%)** |

**The CSS growing 5× is the win, not a regression, and the two halves have to be read together.**
Under MUI the component styles were `sx` objects living *inside* the JS chunks and serialised by
Emotion at runtime; the 12.9 kB stylesheet was almost all of `index.css`. They are now static
utility classes in one stylesheet the browser caches and never re-parses per render. The honest
number is the pair: **−495 kB raw and −174 kB gzipped across both**, with the runtime style engine
gone from the critical path entirely.

CLAUDE.md's own warning — *"splitting DataGrid into its own chunk … made total JS ~40% worse by
defeating tree-shaking"* — is why this was measured rather than asserted. It reads the right way
here. The largest chunk barely moved, though: `DataRegion` at 409 kB is now the shape of the
problem, and it is **not** a design-system chunk (React, the router and TanStack Query land in it
through the shared barrel). That is a finding for a later task, not a regression from this one.

### The browser pass — INCOMPLETE

The stack was brought up (`docker compose up -d --build`, backend `UP`, vite on 5173), a company
provisioned, and the `/accept-invitation` screen rendered correctly. **The fault-switch pass was not
run**: completing the invitation means setting a password, which the agent may not do. The four
switches — `tcmsFaults.status(503, 30)`, `hang()`, `expire()`, `drift()` — are therefore still
unexercised on this tree, and **`drift()` reading as FAILED rather than empty remains claimed by
`T3 Step 3`'s test and unconfirmed on screen.**

Per CLAUDE.md: the gates prove it compiles and that 2051 + 3577 assertions hold. **The six
resilience states are built and unverified**, and T17 does not close that. Whoever signs in should
run the four switches on `/projects/:id/cases` and record what they see here.

## 5. Documents this plan changes when it lands

- **`CLAUDE.md`** — the Frontend section says *"MUI v9 (X DataGrid / TreeView / date-pickers)"*,
  *"MUI `theme` + `sx` as the single authority"* and *"No Tailwind"*. All three become their
  opposites. The bundle-measurement lesson and the `Providers.tsx` ordering rule are unchanged and
  must survive the edit.
- **`docs/specs/2026-08-28-design-language.md`** — §9.2 (*"two rail widths is a layout the shell
  does not have"*) is closed by T5 and should be struck through with *"→ superseded"*, per the
  repository's rule about writing reversals into the record rather than deleting the old reasoning.
- **`docs/specs/assets/renders/tcms.css:6-7`** — its header says the app's real tokens live in
  `index.css` **and `theme.ts`**. `theme.ts` is deleted in T16. The header is prose in a `.css`
  file, so no test catches it; fix it in T16 anyway.
- **`docs/specs/2026-08-28-case-authoring-rail.md`** — its open question 1 (*"the width arithmetic
  is arithmetic"*) gets a real answer from T7 at 1280px. Record it.

## 6. What this plan does not claim

~~Nothing here is built.~~ T1–T4 are built and their gates ran **locally** (build clean, lint 0,
suite green — 1973 tests at T3); none of the four has yet been run on `shanks`, so per CLAUDE.md
they are *built and unverified* until T17. T4 is also the first task whose premise the code
disproved (a sixth `DataRegion` state that already existed) — the reason every remaining task has
a *"read it first"* step, and the reason this grounding pass was made. The task sizes are a
judgement, not a measurement: T10 and T15 are ten and thirteen screens and are the two most likely
to overrun a single agent's useful context — split them at the screen boundary named in their steps
and keep the order. The claim that the kit's nine
components carry all forty-two screens is the design-language spec's claim, made from drawings, and
the first task that finds a tenth component is evidence against it, not a reason to improvise one
quietly. Everything is *built and unverified* until T17 says otherwise.
