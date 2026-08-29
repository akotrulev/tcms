# 007 — Implementing the renders: Tailwind + shadcn/ui, all 42 screens

**Date:** 2026-08-29 · **Written by:** `ep-writing-plans` · **Status:** proposed, nothing implemented

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the product look like `docs/specs/assets/renders/`, by replacing MUI with Tailwind v4
+ shadcn/ui and porting all 42 screens onto the nine-component kit the design-language spec defines.

**Architecture:** The renders are not a mood board — they are executable HTML with a shared
stylesheet (`assets/renders/tcms.css`, 367 lines) and a written rulebook
([`2026-08-28-design-language.md`](../specs/2026-08-28-design-language.md)). That spec's §1 already
maps every kit token to an app token and concludes *"the port is a mapping, not a translation"* —
verified true below. So the work is not a re-theme; it is (a) a styling-authority swap from MUI's
`theme`+`sx` to Tailwind, (b) building the nine-component kit once, and (c) porting each screen onto
it, family by family, in the order the renders are grouped.

**Tech stack:** Tailwind v4 (`@tailwindcss/vite`) · shadcn/ui on Radix · TanStack Table (headless) ·
`sonner` for notifications · React 19 + Vite 8 + TS strict, all unchanged.

---

## 0. Why this plan exists, and what it is answering

A screenshot of `CaseEditorPage` in the *New test case* state was compared against the origin render
([`assets/case-authoring-redesign.html`](../specs/assets/case-authoring-redesign.html) frame B). The
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
| Files importing `@mui` | 156 (`@mui/material` 156 · `@mui/icons-material` 115) |
| Test files | 100 |
| Files coupled to MUI **internals** | **8** — `lib/muiTestIds.ts` and 7 consumers |
| `@mui/x-data-grid` | **1 file** — `features/cases/CaseGrid.tsx` |
| `@mui/x-tree-view` | **2 files** — `CaseGrid.tsx`, `features/cases/ClassificationPicker.tsx` |
| `@mui/x-date-pickers` | **0 files** — a declared dependency nothing imports |
| Routes | 9 `<Route>` elements; 44 `*Page.tsx` |
| The design language itself | `tcms.css`, 367 lines; the spec, 9 sections |

Two of those numbers decide the shape of the plan. **Tests query by `data-testid` and role, not by
MUI class** — only 8 files reach into MUI internals — so a faithful primitive swap keeps the
existing suite meaningful instead of rewriting 100 test files. And **the MUI X packages are three
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
takes `data-testid` directly, so the workaround's premise disappears. One comment cites it —
`features/runs/BlockedDialog.tsx:105` — and that citation must die with the file or
`packages/client/src/test/citedFiles.test.ts` reddens the **packages** job. (Checked: `corpus.ts`
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

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — Tailwind v4, preflight off, kit tokens | `npm run build` clean with Tailwind compiled; `index.css` carries the kit's token names; MUI untouched and still rendering | none |
| 2 | T2 — shadcn primitives, skinned | `components/ui/` holds the Radix primitives, restyled to the kit; each renders in a test at kit colours | T1 |
| 3 | T3 — The nine-component kit | `components/kit/` exports the nine components of design-language §5; each has a test asserting the rule it carries | T2 |
| 4 | T4 — `DataRegion`'s sixth state | `DataRegion` renders loading/loaded/empty/failed/stale/**refused**; refused is neutral, names the permission and a grantee, and offers no retry | T3 |
| 5 | T5 — AppShell | Rail at 232px, collapsing to the 60px icon rail on exactly the two routes of §4; top strip; footer strip | T3 |
| 6 | T6 — Family 8, Threshold | Login · AcceptInvitation · SsoCallback render from the kit with no MUI import | T5 |
| 7 | T7 — Case editor, both frames | `CaseEditorPage` matches `case-authoring-redesign.html` frames A and B, incl. the seven items of §0 | T5 |
| 8 | T8 — Family 9, States | NotFound · Help, and the refusal/notification matrix all 42 inherit | T4, T6 |
| 9 | T9 — TanStack Table | `CaseGrid` renders on TanStack Table with server-side paging/sort/filter; `@mui/x-data-grid` unimported | T3 |
| 10 | T10 — Family 1, Index & table | The 10 index screens render on the kit's Table | T9 |
| 11 | T11 — Family 2, Record as document | The 7 document screens render at the 74ch measure | T7 |
| 12 | T12 — Family 3, Execution | `ExecutionPage` renders with the collapsed rail and number-key verdicts | T5, T11 |
| 13 | T13 — Family 4, Board & people | Board · MyWork · TeamBoard · OrgChart; `@mui/x-tree-view` unimported | T3 |
| 14 | T14 — Family 5, Authoring | Docs · DocPageEditor · FeatureImport at the 70ch prose measure | T11 |
| 15 | T15 — Family 6 & 7, Analytics and Settings | Dashboard · PlanStatistics per §6; the 9 settings screens | T10 |
| 16 | T16 — MUI removed, preflight on | No `@mui`/`@emotion`/`notistack` in `package.json` or any import; Preflight imported; `muiTestIds.ts` deleted with its citation | T6–T15 |
| 17 | T17 — Gates and bundle measurement | The four gates green on `shanks`; before/after bundle numbers recorded | T16 |

Seventeen tasks. T6–T15 are the ten screen tasks and are ordered so the cheapest proof of the kit
(T6, three screens, no rail) lands before the hardest document surface (T7), and so every family
that consumes a replacement engine comes after the task that builds it.

---

### T1 — Tailwind v4, preflight off, kit tokens

**Deliverable:** `npm run build` is clean with Tailwind compiling, `src/index.css` carries the render
kit's token names alongside the existing ones, and every current screen still renders through MUI
exactly as before.
**Depends on:** none

**Files:**
- Modify: `frontend/package.json` — add `tailwindcss`, `@tailwindcss/vite`
- Modify: `frontend/vite.config.ts` — register the plugin
- Modify: `frontend/src/index.css` — the layer imports and the kit tokens
- Test: `frontend/src/app/themeTokenParity.test.ts:1-92` — extend, do not replace

- [ ] **Step 1: Install**

```bash
cd frontend && npm install tailwindcss @tailwindcss/vite
```

- [ ] **Step 2: Register the Vite plugin**

In `frontend/vite.config.ts`, add the import and the plugin entry:

```ts
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
});
```

- [ ] **Step 3: Import Tailwind's layers individually — WITHOUT preflight**

At the very top of `frontend/src/index.css`, above the existing `:root` block:

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/utilities.css" layer(utilities);
```

`tailwindcss/preflight.css` is deliberately absent. Adding it here resets MUI's Emotion baseline and
every screen loses its typography at once. T16 adds it, after the last MUI import is gone.

- [ ] **Step 4: Add the six token changes of §2 to the dark block**

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

- [ ] **Step 5: Expose the tokens to Tailwind**

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

- [ ] **Step 6: Extend the parity test rather than replacing it**

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

- [ ] **Step 7: Run the gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: build clean, lint 0 warnings, suite green. Every screen still looks as it did — Tailwind
is compiled but nothing uses a utility class yet.

- [ ] **Step 8: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts \
        frontend/src/index.css frontend/src/app/themeTokenParity.test.ts
git commit -m "feat(frontend): T1 — Tailwind v4 with preflight off, render-kit tokens"
```

---

### T2 — shadcn primitives, skinned to the kit

**Deliverable:** `frontend/src/components/ui/` holds the Radix-based primitives the 42 screens need,
restyled to the kit tokens, each rendering at kit colours in a test. Nothing outside that folder
imports them yet.
**Depends on:** T1 (tokens and utilities must resolve)

**Files:**
- Create: `frontend/src/components/ui/*.tsx` — the primitive set
- Create: `frontend/src/lib/cn.ts`
- Test: `frontend/src/components/ui/ui.test.tsx`

- [ ] **Step 1: The class-merge helper**

```bash
cd frontend && npm install clsx tailwind-merge
```

```ts
// frontend/src/lib/cn.ts
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export const cn = (...inputs: ClassValue[]) => twMerge(clsx(inputs));
```

- [ ] **Step 2: Add the primitives**

```bash
cd frontend && npx shadcn@latest init
npx shadcn@latest add button dialog select popover command switch checkbox \
  tooltip tabs dropdown-menu input textarea separator skeleton sonner
```

`init` will offer to overwrite `index.css`. **Decline** — T1's layer imports and `@theme inline`
block are deliberate and `init` writes the preflight-on form. Point `components.json` at
`src/components/ui` and at the existing `@/` alias.

- [ ] **Step 3: Skin the button to the kit**

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

- [ ] **Step 4: Write the primitive test**

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

- [ ] **Step 5: Run and commit**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/components/ui frontend/src/lib/cn.ts frontend/components.json \
        frontend/package.json frontend/package-lock.json
git commit -m "feat(frontend): T2 — shadcn primitives skinned to the render kit"
```

---

### T3 — The nine-component kit

**Deliverable:** `frontend/src/components/kit/` exports the nine components of design-language §5 —
`Table`, `ResultBar`, `Badge`, `Chip`, `RailRow`, `DocumentSurface`, `Timeline`, `Banner`,
`StateBlock`, `Figure` — each with a test asserting the rule it carries.
**Depends on:** T2

**Files:**
- Create: `frontend/src/components/kit/{Table,ResultBar,Badge,Chip,RailRow,DocumentSurface,Timeline,Banner,StateBlock,Figure}.tsx`
- Create: `frontend/src/components/kit/index.ts`
- Test: one `*.test.tsx` beside each

The source for every one is `docs/specs/assets/renders/tcms.css` plus the render that uses it. Port
the CSS rule; do not re-derive the look.

- [ ] **Step 1: `Badge` vs `Chip` — the distinction the spec makes load-bearing**

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

- [ ] **Step 2: `RailRow` — rule 3, the row the current editor gets wrong**

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

- [ ] **Step 3: `StateBlock` — six states, and failed must not read as empty**

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

- [ ] **Step 4: The remaining six**

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

- [ ] **Step 5: Run and commit**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/components/kit
git commit -m "feat(frontend): T3 — the nine-component kit of design-language §5"
```

---

### T4 — `DataRegion`'s sixth state

**Deliverable:** `DataRegion` renders loading / loaded / empty / failed / stale / **refused**, with
refused neutral, naming the permission and a person who holds it, and offering no retry.
**Depends on:** T3 (`StateBlock`)

**Files:**
- Modify: `frontend/src/components/DataRegion.tsx`
- Modify: `frontend/src/components/DataRegion.test.tsx`

- [ ] **Step 1: Read the existing five states before changing any**

`DataRegion` is inherited by all 42 screens and its current contract is relied on everywhere. Add
the sixth; do not restructure the five.

- [ ] **Step 2: Write the failing test first**

```tsx
it('renders refused as its own state — not an error', () => {
  render(
    <DataRegion state="refused" permission="case:edit" grantee="M. Kalcheva">
      <div>never</div>
    </DataRegion>,
  );
  expect(screen.queryByText('never')).toBeNull();
  expect(screen.getByText(/case:edit/)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).toBeNull();
});
```

Run `npm run test:run -- DataRegion`. Expected: FAIL, `refused` is not assignable.

- [ ] **Step 3: Add the state, delegating to `StateBlock`. Step 4: re-run to green. Step 5: commit.**

```bash
git add frontend/src/components/DataRegion.tsx frontend/src/components/DataRegion.test.tsx
git commit -m "feat(frontend): T4 — DataRegion's refused state"
```

---

### T5 — AppShell

**Deliverable:** The shell renders the 232px rail with the headings and order `AppShell.tsx`'s
`projectNav` already has, collapsing to the 60px icon rail on **exactly two routes** — the case
document editor and the execution runner — plus the top strip and the footer strip of rule 2.
**Depends on:** T3

**Files:**
- Modify: `frontend/src/app/AppShell.tsx`, `frontend/src/app/AppShell.test.tsx`
- Modify: `frontend/src/app/Providers.tsx` — `sonner`'s `<Toaster />` replaces notistack's provider

**`Providers.tsx` nesting order is load-bearing and documented in the file** (CLAUDE.md): the query
client is constructed innermost because its global error handlers consume the notification and
session-recovery providers. Swapping notistack for `sonner` must preserve that order — the toaster
mounts where the `SnackbarProvider` was, not at the root.

- [ ] **Step 1: Write the failing test for the collapse rule**

```tsx
it.each([
  ['/projects/p1/cases/c1/edit', 'collapsed'],
  ['/projects/p1/runs/r1/execute', 'collapsed'],
  ['/projects/p1/cases', 'expanded'],
  ['/projects/p1/dashboard', 'expanded'],
])('the rail is %s on %s (design language §4)', (path, expected) => {
  renderShellAt(path);
  expect(screen.getByTestId('AppShell.Rail')).toHaveAttribute('data-width', expected);
});
```

Expected: FAIL — the shell does not read the route today, which §9.2 names as an open problem.

- [ ] **Step 2: Implement the route read.** Derive from `useMatch` against the two patterns, held in
one exported constant so a third route is a deliberate edit:

```ts
export const COLLAPSED_RAIL_ROUTES = [
  '/projects/:projectId/cases/:caseId/edit',
  '/projects/:projectId/runs/:runId/execute',
] as const;
```

- [ ] **Step 3: Port the rail, top strip and footer strip** from `renders/tcms.css` (`.rail`, `.top`,
`.foot`) and `renders/01-index-tables.html`.

- [ ] **Step 4: Run and commit**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/app
git commit -m "feat(frontend): T5 — AppShell on the kit, with the two-width rail"
```

---

### T6 — Family 8, Threshold

**Deliverable:** `LoginPage`, `AcceptInvitationPage` and `SsoCallbackPage` render entirely from the
kit with no `@mui` import, matching `renders/08-threshold.html`.
**Depends on:** T5

Three screens, no rail, no project, no session — the cheapest complete proof that the kit can carry
a screen. The render's rule: *more identity than anywhere inside, fewer controls than anywhere
inside, and the email decides the sign-in method before a password is asked for.*

**Files:** `frontend/src/features/auth/*.tsx` and their tests.

- [ ] **Step 1:** Port each screen against `renders/08-threshold.html`, replacing MUI imports with
`components/ui` and `components/kit`. Keep every `data-testid` byte-identical — the existing tests
are the regression net and must not be edited to accommodate the port.
- [ ] **Step 2:** `grep -rn "@mui" frontend/src/features/auth` → expected: no matches.
- [ ] **Step 3:** `npm run build && npm run lint && npm run test:run`
- [ ] **Step 4:** Commit.

```bash
git add frontend/src/features/auth
git commit -m "feat(frontend): T6 — family 8, the threshold, ported to the kit"
```

---

### T7 — The case editor, both frames

**Deliverable:** `CaseEditorPage` matches `assets/case-authoring-redesign.html` frames A and B, with
all seven differences of §0 closed except item 6, which §4 defers.
**Depends on:** T5

This is the screen the plan was opened for. Its two specs
([`2026-08-28-case-authoring-redesign.md`](../specs/2026-08-28-case-authoring-redesign.md),
[`2026-08-28-case-authoring-rail.md`](../specs/2026-08-28-case-authoring-rail.md)) are the authority
where they and the render disagree — the rail spec's *"Does the render need redrawing?"* section
lists four places the render is already known to be wrong.

**Files:** `frontend/src/features/cases/CaseEditorPage.tsx`, `CaseDocument.tsx`, and their tests.

- [ ] **Step 1: Remove the panel chrome** (§0 item 1). The document column is a bare `paper` surface;
`Start`, `The case` and `Notes` lose their `Panel` wrappers. The rail keeps its section headings —
those are `tcms.css:.sec h3`, not panels.

- [ ] **Step 2: The three starts become cards** (§0 item 2), each with its sentence and its keyboard
hint, `Write it` highlighted — `case-authoring-redesign.html:.starts`.

- [ ] **Step 3: The blank state** (§0 item 3) — a `+ tag` row, the ghost title lead, the subtitle
*"One sentence stating what is verified"*, the rule, then the numbered scaffold with a ghost hint
per keyword. This replaces the textarea placeholder at `CaseDocument.tsx:280`; the placeholder is
what a person sees when there is nothing to see, and the scaffold is what tells them what to write.

- [ ] **Step 4: The rail goes quiet** (§0 item 4). Every row becomes `kit/RailRow`: no outlined
`Select`, no permanent border, no persistent helper paragraph. The helper text moves into the
opened control, where the rail spec puts it. **`notAutomatableReason` must stay reachable and its
refusal visible** — the rail spec's open question 2 says the automation popover must not close on a
server refusal, or the error has no visible field.

- [ ] **Step 5: The document reads as a document** (§0 item 5): 15px sans at 1.75 for step text,
mono only for the keyword, the gutter number, and step data. `MONO` at `CaseDocument.tsx:70` stops
applying to the whole mirror — but the textarea and its colouring mirror must still agree on font,
size, line height, padding, border and wrapping, or the caret drifts from the completion popup. That
constraint is why the file has one mirror and not two; changing the font makes it *more* important,
not less. Its `SHARED` block is the place to change, and both halves read from it already.

- [ ] **Step 6: The commit bar moves to the top strip** (§0 item 7) and the `PageHeader` H1 goes —
rule 1, *"no page heading repeating the rail's active item"*.

- [ ] **Step 7: `Notes` loses its panel but keeps its fields** (§4). Both `TextField`s become kit
inputs in the document's narrative column, under the procedure.

- [ ] **Step 8: Tests.** The existing `CaseEditorPage.test.tsx` (524 lines) must pass unedited except
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

- [ ] **Step 9:** `npm run build && npm run lint && npm run test:run`, then commit.

```bash
git add frontend/src/features/cases/CaseEditorPage.tsx frontend/src/features/cases/CaseDocument.tsx \
        frontend/src/features/cases/CaseEditorPage.test.tsx frontend/src/features/cases/CaseDocument.test.tsx
git commit -m "feat(frontend): T7 — the case editor as the render draws it"
```

---

### T8 — Family 9, States

**Deliverable:** `NotFoundPage` and `HelpPage` render from the kit, and the refusal + notification
matrix all 42 screens inherit is in place: a failure never auto-dismisses, an error names the field
and links to it, every failure carries a six-character reference and a timestamp.
**Depends on:** T4 (`StateBlock`), T6

**Files:** `frontend/src/features/NotFoundPage.tsx`, `frontend/src/features/help/*`,
`frontend/src/notifications/*`, `frontend/src/app/queryClient.ts`

Help is deliberately the fourth document surface (§3) — *written in the product, on the product's own
document surface, using the product's own chart components*.

- [ ] **Step 1:** Port both screens. **Step 2:** Move the notification surface onto `sonner`,
preserving `app/queryClient.ts` as the one place a failure raises a notification (CLAUDE.md).
**Step 3:** Assert the never-auto-dismiss rule:

```tsx
it('a failure notification does not auto-dismiss', () => {
  raiseFailure();
  vi.advanceTimersByTime(30_000);
  expect(screen.getByRole('alert')).toBeInTheDocument();
});
```

- [ ] **Step 4:** Run the gate and commit.

---

### T9 — TanStack Table

**Deliverable:** `CaseGrid` renders on TanStack Table with `manualPagination` / `manualSorting` /
`manualFiltering` driving the existing query hooks; `@mui/x-data-grid` is imported by no file.
**Depends on:** T3 (`kit/Table`)

**Files:** `frontend/src/features/cases/CaseGrid.tsx` (336 lines), `frontend/src/components/kit/Table.tsx`

- [ ] **Step 1:** `npm install @tanstack/react-table`
- [ ] **Step 2:** Port the column definitions one-for-one. **Server-side paging/sort/filter is not
optional** — it is how the grid behaves today and a client-side load of the full table is the named
anti-pattern. Deterministic default sort with a unique tiebreaker is a `ui-rules` requirement and
the existing behaviour; keep it.
- [ ] **Step 3:** The kit's Table rule — *a quiet second line in the cell rather than a twelfth
column* (UX#113 / UX#111) — is the one visual change. Everything else is a mechanical port.
- [ ] **Step 4:** `grep -rn "x-data-grid" frontend/src` → no matches. Gate, commit.

---

### T10 — Family 1, Index & table

**Deliverable:** CaseList · CaseSearch · RunList · PlanList · Proposals · Reports · Ingestions ·
RunSchedule · Artifacts · Projects all render on `kit/Table`, against `renders/01-index-tables.html`.
**Depends on:** T9

Ten screens, one skeleton. The render's rules: filters as removable chips in one row under the
toolbar; **proportion before number**; an em dash meaning *no value* rather than zero; and rule 8 —
*a count is a link to its rows, and the URL carries the filter*. Filter state lives in the URL
(`useSearchParams`) and that does not change.

- [ ] **Step 1–10:** One screen per step, each ending with `npm run test:run` on that screen's test.
- [ ] **Step 11:** Gate and commit.

---

### T11 — Family 2, Record as document

**Deliverable:** CaseDetail · RunDetail · IngestionJob · Proposal · CaseHistory · SuiteHistory ·
ReportView render at the 74ch measure on `kit/DocumentSurface` and `kit/Timeline`, against
`renders/02-record-as-document.html`.
**Depends on:** T7

`ReportViewPage` keeps its light surface (D1, §9.1) — a PDF preview drawn dark misrepresents the
file being downloaded. It is the one screen in the product that is deliberately not dark, and the
port must not "fix" it.

- [ ] **Steps 1–7:** One screen per step. **Step 8:** Gate and commit.

---

### T12 — Family 3, Execution

**Deliverable:** `ExecutionPage` renders against `renders/03-execution.html` with the collapsed rail
from T5, number keys for verdicts, the current step at full ink, the failure panel in place of a
dialog, and *last time* on screen before it is needed.
**Depends on:** T5, T11

- [ ] **Step 1:** Port. **Step 2:** Assert the keyboard contract:

```tsx
it('records a verdict from the number key for that verdict', async () => {
  renderExecution();
  await userEvent.keyboard('1');
  expect(await screen.findByTestId('Execution.Step.1.Verdict')).toHaveTextContent('Passed');
});
```

- [ ] **Step 3:** Gate and commit.

---

### T13 — Family 4, Board & people

**Deliverable:** Board · MyWork · TeamBoard · OrgChart render against `renders/04-board-and-people.html`;
`@mui/x-tree-view` is imported by no file.
**Depends on:** T3

The board is the only drag surface, *so the only one where WCAG 2.2's drag rule bites* — every drag
needs a single-pointer alternative. `@dnd-kit` stays; it is not MUI.

`ClassificationPicker.tsx` is the second `x-tree-view` consumer and is replaced with a flat
`components/ui/command` combobox — classification values are a flat vocabulary per dimension, not a
tree, which is why the TreeView was carrying one node depth.

- [ ] **Step 1–4:** One screen per step. **Step 5:** `grep -rn "x-tree-view" frontend/src` → no
matches. Gate, commit.

---

### T14 — Family 5, Authoring

**Deliverable:** Docs · DocPageEditor · FeatureImport render against `renders/05-authoring.html` at
the 70ch prose measure.
**Depends on:** T11

The three rules the render carries: coverage is **per paragraph, not per page**; draft pages stay in
the tree, dimmed; and *Write one* sits on the untested clause, opening the case editor pre-seeded
with that paragraph as the objective. The import shows the **diff, not the count**, never deletes,
and its button counts what it will do.

- [ ] **Steps 1–3:** One screen per step. **Step 4:** Gate and commit.

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

Settings' rules: every setting states its blast radius against live data; every refusal names a
person who can grant it (T4's refused state); revoked and expired rows **stay on screen, dimmed**,
with the date and reason — rule 7, *disable, don't hide*.

- [ ] **Steps 1–11:** One screen per step. **Step 12:** Gate and commit.

---

### T16 — MUI removed, preflight on

**Deliverable:** `@mui/*`, `@emotion/*` and `notistack` appear in no import and in no
`package.json`; Tailwind's Preflight is imported; `lib/muiTestIds.ts` is deleted along with the one
comment that cites it.
**Depends on:** T6–T15

**Files:**
- Delete: `frontend/src/lib/muiTestIds.ts`, `frontend/src/app/theme.ts`
- Modify: `frontend/src/features/runs/BlockedDialog.tsx:105` — the citation
- Modify: `frontend/src/index.css`, `frontend/package.json`
- Modify: `frontend/src/app/themeTokenParity.test.ts` — its subject no longer exists

- [ ] **Step 1: Prove nothing imports MUI**

```bash
cd frontend && grep -rn "@mui\|@emotion\|notistack" src/ ; echo "exit=$?"
```

Expected: no matches (`exit=1`). A single hit here means a screen task is unfinished; finish it
before proceeding — this is the task that makes the D2 intermediate state end.

- [ ] **Step 2: Delete the two files and fix the citation**

`BlockedDialog.tsx:105` reads `// reason \`lib/muiTestIds.ts\` exists.` Delete the comment with the
file. **This is not optional**: `packages/client/src/test/citedFiles.test.ts` scans `frontend/src`
for `.ts`/`.tsx` and fails on a path that is not on disk, and it reddens the **packages** job, not
the frontend one — the wrong place to go looking.

- [ ] **Step 3: Rewrite `themeTokenParity.test.ts`**

Its job was pinning `index.css` against `app/theme.ts`, and `theme.ts` is gone. What survives is the
half that still has a subject: the rule-5 identity test from T1 Step 6, plus a check that every
token named in `@theme inline` resolves to a declared custom property. Rename the file to
`tokenSystem.test.ts` and say in its header what it stopped checking and why — CLAUDE.md's rule
about writing superseded decisions and their reversal into the record.

- [ ] **Step 4: Turn Preflight on**

```css
@layer theme, base, components, utilities;
@import "tailwindcss/theme.css" layer(theme);
@import "tailwindcss/preflight.css" layer(base);
@import "tailwindcss/utilities.css" layer(utilities);
```

- [ ] **Step 5: Drop the dependencies**

```bash
cd frontend && npm uninstall @mui/material @mui/icons-material @mui/x-data-grid \
  @mui/x-tree-view @mui/x-date-pickers @emotion/react @emotion/styled notistack
```

`@mui/x-date-pickers` is removed here having never been imported by anything — see §1.

- [ ] **Step 6: Run the full gate and expect layout damage**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Preflight resets margins, list styles and heading sizes that MUI's baseline was providing. Expect
findings here and fix them in this task — that is what Step 4 is for, and postponing it to T17 makes
the gate task a second design task.

- [ ] **Step 7: Commit**

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

**Gates run on `shanks`, not locally** — the standing user directive in CLAUDE.md. Read
[`shanks-runner-details.md`](../specs/shanks-runner-details.md) first: `BUILD FAILED` with
`Could not write XML test results` and **zero** assertion failures is an fd-limit problem, not a red
suite.

- [ ] **Step 1: Sync and run all four**

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

- [ ] **Step 2: Measure the bundle, before believing anything about it**

```bash
cd frontend && npm run build -- --mode production
```

CLAUDE.md: *"Measure bundle changes before believing them. Splitting DataGrid into its own chunk
looked obviously right and made total JS ~40% worse by defeating tree-shaking."* Record total JS,
the largest chunk, and CSS, against the `5933ca27` baseline. Removing Emotion's runtime and MUI
should be a large win; **"should be" is not a measurement**, and a regression here is a finding, not
a rounding error.

- [ ] **Step 3: Drive the six states in a browser**

The gates prove it compiles, not that it works — CLAUDE.md's own list of what typechecked and was
still wrong includes a no-op theme toggle and a provider-ordering bug that rendered nothing but the
error page. Run the fault switches against a real backend:

```js
tcmsFaults.status(503, 30); tcmsFaults.hang(); tcmsFaults.expire(); tcmsFaults.drift();
```

`drift()` is the one that matters most here: it must read as **FAILED**, not empty, and T3 Step 3
is the test that claims so. Confirm it on screen.

- [ ] **Step 4: Write the results into this file** under a new *Results* section — gate output, the
bundle table, and anything the browser pass found that the gates did not.

- [ ] **Step 5: Commit**

```bash
git add docs/plans/007-render-design-implementation.md
git commit -m "fix: T17 — gates green on shanks, bundle measured"
```

---

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

Nothing here is built. The task sizes are a judgement, not a measurement: T10 and T15 are ten and
eleven screens and are the two most likely to overrun a single agent's useful context — split them
at the screen boundary named in their steps and keep the order. The claim that the kit's nine
components carry all forty-two screens is the design-language spec's claim, made from drawings, and
the first task that finds a tenth component is evidence against it, not a reason to improvise one
quietly. Everything is *built and unverified* until T17 says otherwise.
