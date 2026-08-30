# The renders vs. the implementation — the styling pass

**Date:** 2026-08-30 · **Kind:** read-only audit · **No implementation code was changed.**

Companion to [`2026-08-30-renders-vs-implementation.md`](./2026-08-30-renders-vs-implementation.md),
which audited **structure, ordering and semantics** — what is on the screen, in what order, saying
what. That audit measured size and colour only where something visibly broke. **This one audits the
styling system itself**: type scale, weight, tracking, leading, spacing rhythm, radius, border,
elevation, icon scale and palette literals, element by element, against
[`docs/specs/assets/renders/tcms.css`](../specs/assets/renders/tcms.css) — the render kit, which is
the one file that states every one of those values as a number.

Classification here is three-way, not two:

| | |
|---|---|
| **B** | the build diverges from the render on a value the render states |
| **I** | the build disagrees with **itself** — two or more spellings of one thing, which the render cannot arbitrate because it draws one |
| **A** | the divergence is already permitted by the design language, and is recorded only so it is not re-found |

---

## 0. Method, and what it does and does not cover

`tcms.css` declares every typographic and spatial value literally. Tailwind utilities also map 1:1
onto declarations — `text-[11.5px]` *is* `font-size: 11.5px`, `px-3.5` *is* `padding-inline: 14px`.
So for the properties audited here the class list on a component **is** its computed style, and the
comparison is deterministic and re-runnable from the repo alone. Radius names were resolved against
`node_modules/tailwindcss/theme.css` at **v4.3.3** rather than assumed: `rounded-sm` 4px,
`rounded-md` 6px, `rounded-lg` 8px, `rounded-xl` 12px.

**What this cannot see, and what is therefore still unchecked:** anything that depends on layout or
inheritance — the *rendered* size of an element whose font-size is inherited, the actual measure a
`74ch` cap resolves to at a given width, hover/focus/active states as painted, and whether a
divergence found here is visible or academic on screen. That half needs a live browser diff of the
render page against the app screen, and **the Chrome extension was not connected for this session**,
so it was not run. A local static server for the renders is the only setup it needs
(`python3 -m http.server` in `docs/specs/assets/renders/`, which was verified working); the app was
already up.

**A note on what has already moved.** The build has changed since the structural audit: `index.css`
now carries the light-palette re-step for finding 6.1 (with `app/statusPalette.test.ts` as the
computed check) and the light-island selector for 2.5. Everything below is measured against the
working tree as of this date, not against the state the first audit saw.

---

## 1. The headline: the kit conforms, and the pages go around it

This is the finding that organises all the others, so it goes first.

**The nine-component kit is a near-exact port of the render kit.** Checked value by value, the
divergences inside `components/kit/` are between 0 and 2px:

| kit component | render rule | agreement |
|---|---|---|
| `DocumentSurface.tsx:19,23-24` | `.doc` `26px 32px`; `.proc` 15px/1.75/74ch; `.prose` 14.5px/1.7/70ch | **exact, all six values** |
| `DocumentSurface.tsx:43,47-48,54` | `.dtitle` 24px/620/1.25/−.02em/36ch; `.dwhy` 14.5px/1.6/62ch; `.rule` 18px | **exact** |
| `ResultBar.tsx:55,61` | `.rbar` h6px/gap2/min110/r3, ends r3 | **exact** |
| `StateBlock.tsx:104-108` | `.state` 52/34px, 56ch, icon 34px, h3 16px/600, p 13.5px/1.6 | exact but `gap-2.5` (10px) for `.acts` where the render says 9px |
| `Table.tsx:26,94-95,153` | `.tbl` 680px/13px; `th` mono 11px/500/.07em/upper/14px sides; `td` 14×10px/align-top | `th` vertical padding 10px vs 9px; `.tbl .t` weight **400 vs 450** |
| `Badge.tsx:77-83` | `.badge` 11.5px/500/2×8px/pill/dot 6px/gap 5px | **exact** |
| `Banner.tsx:61-71` | `.banner` 8px radius/gap 11px/13px/1.55/icon 16px | vertical padding 10px vs 11px |
| `RailRow.tsx:55-56` | `.kv` 13px/min-h 28px/gap 10px/r5 | vertical padding 4px vs 5px |
| `Chip.tsx:52-53` | `.chip` 12px/3×9px/r5/gap 6px | horizontal padding 10px vs 9px |

Nothing in that table is worth a ticket. **The divergences are in the pages and the shell**, and
almost all of them take the same shape: a screen hand-rolls an element the kit already exports, and
lands on a different number. Sections 2–7 are that story told nine ways.

---

## 2. Type

### 2.1 The form label has three idioms, and the kit's is not the render's — **B** — High
`components/kit/Field.tsx:53`

The render states the form-label idiom once and unambiguously:

```css
label.f{display:block;font-size:11px;font-family:var(--mono);letter-spacing:.06em;
        text-transform:uppercase;color:var(--ink-4);margin-bottom:5px}
```

`kit/Field.tsx` — the component every form in the product goes through — draws
`mb-1 block text-[13px] font-medium text-ink-2`: **sans, 13px, medium, sentence case, no tracking,
two ink weights lighter, 4px below.** Not one of the six declared properties survives.

This is the largest single typographic divergence in the product and it is invisible to a
structural audit, because the label is present, correct and correctly associated — it is only
drawn as a different kind of thing. The render's label recedes into the chrome tier (mono, ghost
grey, 11px); the build's competes with the value it labels.

Two other idioms exist alongside it:

| where | spelling |
|---|---|
| the render's, in sans not mono | `block text-[11px] font-medium tracking-[.06em] text-ink-4 uppercase` |
| `CaseEditorPage.tsx:1893` | `text-[11px] font-medium tracking-[.04em] text-ink-3 uppercase` — a fourth tracking value and a different ink |

*Resolve:* the render. One idiom, in `Field`, and the other two call sites deleted in favour of it.

### 2.2 The big number is drawn at four sizes and three weights — **I / B** — High

`kit/Figure.tsx:38` is `.tile .v` exactly — `font-mono text-[26px] leading-[1.1] font-medium
tracking-[-.02em] tabular-nums`. Five other places draw the same kind of thing and none of them
agree:

| site | size | weight | tracking | leading |
|---|---|---|---|---|
| `components/kit/Figure.tsx:38` | 26px | 500 | −.02em | 1.1 | ✓ the render |
| `features/dashboard/DashboardPage.tsx:422` | **22px** | 500 | −.02em | 1.1 |
| `features/dashboard/DashboardPage.tsx:561` | **30px** | 500 | **−.03em** | none |
| `features/plans/PlanStatisticsPage.tsx:1207` | **30px** | 500 | **−.03em** | none |
| `features/runs/RunDetailPage.tsx:389` | 26px | **600** | **none** | none |
| `components/ResultBar.tsx:55,61` | **32px** (`text-[2rem]`) | **600** | none | none |

The render has exactly one figure size. The build has five, two of which (30px, 32px) are larger
than anything the render draws anywhere including its page `<h1>` (29px), and one of which is
authored in `rem` while every other size in the product is in `px`.

*Resolve:* every one of these is `Figure`. −.03em is off the render's tracking scale as well
(§2.4).

### 2.3 The document title is drawn at four sizes — **I**, and **A** on the size itself

`kit/DocumentSurface.tsx:43` is `.dtitle` exactly. Four screens re-declare it:

| site | size | measure cap |
|---|---|---|
| `components/kit/DocumentSurface.tsx:43` | `text-2xl` 24px | `max-w-[36ch]` ✓ |
| `features/docs/DocsPage.tsx:675` | 24px | **none** |
| `features/docs/DocPageEditorPage.tsx:356` | 24px | — |
| `features/help/HelpPage.tsx:136` | **25px** | 36ch |
| `features/cases/CaseDocument.tsx:162` | **25px** | **34ch** |

The **size** is not a finding: design language §3 says *"Titles at 24–25px / 620 weight"*, so both
are in spec — mark **A**. What is a finding is that five call sites re-implement one exported
component, one of them drops the measure cap entirely, and one caps at 34ch where the other four
say 36ch. Rule: a title that the kit owns should not be spelled anywhere else.

### 2.4 Four sizes and four tracking values sit off the render's scale — **B** — Medium

The render's type scale is 18 sizes: 10, 10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 16, 18,
19, 22, 24, 26, 29px. The build adds:

| off-scale | uses | where |
|---|---|---|
| **9px** | 2 | `DocPageEditorPage.tsx:409`, `ExecutionPage.tsx:1652` — below the render's 10px floor |
| **17px** | 2 | `help/prose.tsx:35`, `DocPageEditorPage.tsx:517` — the render's body heading is `.prose h2` at 18px |
| **30px** | 2 | §2.2 |
| **32px** | 2 | §2.2, as `2rem` |

Tracking, same shape. The render's set is .06, .07, .08, .09, .1em and −.01, −.015, −.018, −.02,
−.022em. The build adds **.04em** (4 uses), **.05em** (1), **.14em** (1), **−.03em** (2) — and
**.09em, the value the render gives the rail label and the page eyebrow, appears nowhere in the
app at all.**

Line-height adds one: **1.45** (3 uses) against the render's 1.1 / 1.15 / 1.25 / 1.5 / 1.55 / 1.6 /
1.7 / 1.75.

### 2.5 `DocBlockBody` is authored in a different unit system — **B** — Medium
`components/DocBlockBody.tsx:137,165,177,348`

Four sizes in one file, all in `rem`, none of which lands on the render's scale:

```
0.85rem = 13.6px   (code block)     0.9rem  = 14.4px  (table cell, caption)
0.95rem = 15.2px   (paragraph)
```

The render's corresponding rules are `.prose code` **12.5px**, `.tbl td` **13px**, `.prose`
**14.5px**. So documentation body text renders ~0.7px larger than the document surface it sits on,
from a file that is the only place in `src/` measuring type in `rem` rather than `px`. Three more
`rem` sizes appear at `ArtifactTable.tsx:102,106,136` (`0.8125rem` = 13px), `SuiteList.tsx:203`
(`0.625rem` = 10px) and `ExecutionQueue.tsx:91` (`0.6875rem` = 11px) — those *do* land on the
scale, they are just spelled in the other unit.

### 2.6 The identifier tracks the wrong way — **B** — Medium
`frontend/src/index.css:263,395`

`.identifier` — the product's self-declared *"signature element … every identifier: case key,
version, release, SHA, percentage"* — sets `letter-spacing: var(--tracking-wide)`, and
`--tracking-wide` is **`0.02em`**.

The render's is `.id{letter-spacing:-.01em}`.

The build tracks its monospace **out** by 0.02em where the render tracks it **in** by 0.01em: a
0.03em swing, in the opposite direction, on the one element the design language says the design
spends its boldness on. At 13px that is ~0.4px per character — over `TC-1042` it is a visible
loosening, and it works against the tabular-figure alignment the same comment is arguing for.

*Resolve:* `--tracking-wide: -0.01em` for this use, or a dedicated `--tracking-identifier`. The
name `wide` is itself the tell: the token was defined for a different job and reused here.

### 2.7 Two spellings for one value, four times over — **I** — Low

| value | spelled | and also |
|---|---|---|
| 12px | `text-xs` ×126 | `text-[12px]` ×96 |
| 14px | `text-sm` ×77 | `text-[14px]` ×1 |
| 6px radius | `rounded-md` ×32 | `rounded-[6px]` ×18 |
| 4px radius | `rounded` ×35 | `rounded-[4px]` ×17 |
| 1.25 leading | `leading-tight` ×3 | `leading-[1.25]` ×6 |
| .08em | `tracking-[.08em]` ×27 | `tracking-[0.08em]` ×1 |

Costless to render, expensive to audit: a grep for one spelling finds half the sites, which is how
a value drifts in the first place. **Weights are the counter-example and are clean** — 400 / 450 /
500 / 550 / 600 / 620 and nothing else across the whole app, no `font-bold` anywhere.

---

## 3. Spacing

### 3.1 The 22px chrome gutter does not exist in the build — **B** — High
`frontend/src/app/AppShell.tsx:561` and `:985`

The render gives every chrome strip on the stage the **same 22px horizontal gutter**, five times
over: `.top` `12px 22px`, `.filters` `10px 22px`, `.title-row` `16px 22px 0`, `.bulk` `8px 22px`,
`.foot` `10px 22px`. It is the one spatial constant of the shell.

`px-[22px]` appears **zero times** in `frontend/src/`. Instead:

| | render | build |
|---|---|---|
| content gutter | 22px | `main` is `px-2 py-2 md:px-4 md:py-3` — **16px** at ≥900px, **8px** below |
| header gutter | 22px | `<header>` is `px-2 md:px-4` — same |

Every one of the forty-two screens is drawn 6px tighter to the frame than the render on each side
at desktop, and 14px tighter on a phone. This is a one-line change and it is the highest-leverage
item in this document, because it moves every screen at once and nothing else depends on it.

### 3.2 The rail row is a full-bleed bar with an indicator the render does not draw — **B** — Medium
`frontend/src/app/AppShell.tsx:932-940`

| | render `.rail .n` | build `RailLink` |
|---|---|---|
| padding | `6px 10px` | `py-[7px] px-4` — 7px / **16px** |
| radius | `var(--r-sm)` = 5px | **none** — square, full-bleed |
| gap | 9px | `gap-2.5` = 10px |
| active | `background:var(--steel-dim)`, ink, 500 | `bg-steel/12` + weight + **a 3px steel bar pinned to the left edge** |

The build keeps the render's tinted fill *and* adds a left rule the render does not have. The
render's active row is an inset rounded pill; the build's is a full-width band with a marker. This
is the most-looked-at component in the product and it is drawn as a different pattern.

### 3.3 The table foot is an inline strip, not a bar — **B** — Low
`components/kit/TableFoot.tsx:34-43`

`.foot` in the render is a bar: `padding:10px 22px`, `border-top`, its own translucent background
(`rgb(14 19 26/92%)`), pushed to the bottom by `margin-top:auto`, summary at 12px and key hints at
11px mono. The build is `mt-2 pt-2 border-t text-[11.5px]` with no background, no side gutter and
one size for both halves. The *content* of the foot was the first audit's finding 1.7; this is the
same element's form.

---

## 4. Radius, border, elevation

### 4.1 The button and the input round at 6px where the render's base radius is 8px — **B** — Medium
`components/ui/buttonVariants.ts:23,41-42`, `components/ui/input.tsx:9`

`--r: 8px` is the render's default corner and it is what `.btn`, `.fld`, `.card`, `.banner`,
`.tile`, `.lens` and `.pop` all use. `Banner` and the cards take it correctly (`rounded-lg`). The
two controls a user touches most do not:

| | render | build |
|---|---|---|
| button radius | 8px | **6px** (`rounded-[6px]`) |
| button padding | `6px 13px` | `py-1.5 px-3.5` — 6 / **14px** |
| button gap | 7px | `gap-2` = **8px** |
| button `sm` min-height | 26px | `min-h-7` = **28px** |
| input radius | 8px | **6px** |
| input padding | `5px 10px` | `py-1.5 px-3` — 6 / **12px** |
| input font-size | 12.5px | **13px** |
| input min-height | 30px | **none declared** |
| input ink | `--ink-2` | `text-ink` |

The focus treatment also differs — the render is a steel border plus a 3px steel glow, the build a
2px green outline at 2px offset — but that one is **A**: `buttonVariants.ts` states the reason in
its own header (a steel ring around a steel button disappears; rule 3 wants it visible), and it is
a defensible amendment rather than a slip.

### 4.2 The segmented control and a second tab idiom — **B / I** — Medium
`components/ui/tabs.tsx`, `features/plans/PlanStatisticsPage.tsx:201-202`

`ui/tabs.tsx` is `.lens`, one step small on both radii and one pixel large on type:

| | render `.lens` | build |
|---|---|---|
| track radius | 8px | `rounded-md` = **6px** |
| item radius | 6px | `rounded-[4px]` = **4px** |
| item font-size | 12px | **13px** |
| item padding | `4px 11px` | `py-1 px-3` — 4 / **12px** |
| active elevation | `var(--e1)` | Tailwind's default `shadow` — a different, much lighter shadow |

Separately, `PlanStatisticsPage` hand-rolls `role="tab"` as **underline tabs**
(`border-b-2 border-steel px-3 py-2`). The render draws no underline tab anywhere; the lens is the
only idiom. So the product has two tab patterns, and the one on the analytics screen is not in the
design language at all.

### 4.3 A 2px radius that is not in the system — **B** — Low

The render's radii are 1, 3, 4, 5, 6, 8, 12px and round. The build adds **2px**, 9 times —
`PlanStatisticsPage.tsx:1000-1001`, `DashboardPage.tsx:531-532,650`, `DocsPage.tsx:934`,
`ShortcutOverlay.tsx:57`, `CriteriaStrip.tsx:124`, `components/ResultBar.tsx:120`. Six of those are
legend swatches, where the render's own swatch is the `.rbar i` fill at **1px**.

### 4.4 Elevation is not a token, it is eighteen copies of one — **B** — High

The render declares three: `--e1`, `--e2`, `--e3`. **`index.css` declares none.** Instead:

| render token | how the build spells it | copies |
|---|---|---|
| `--e3` `0 16px 32px -8px rgb(0 0 0/60%)` | inlined verbatim as `shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]` | **14 files** |
| `--e1` `0 1px 2px rgb(0 0 0/40%)` | inlined verbatim | 4 |
| `--e2` | — | **0** |
| — | Tailwind's default `shadow` | 5 |

Two problems, and the second is the serious one.

**It is the literal-vs-token defect that `index.css` already documents.** Its own `@theme inline`
comment describes exactly this failure for the white-alpha family — *"correct on a dark ground and
invisible on a light one, which is what made the light palette unusable rather than merely
divergent"* — and the fix was to author them as tokens. The elevation family never got that
treatment, so eighteen hardcoded values sit outside the palette swap.

**And they are pure black at 40–60%.** Those alphas were chosen against `--ground: #0a0e14`. The
light palette's reading surface is `#f7f9fc`. A 60%-black 32px drop shadow under a popover on an
off-white ground is a smudge, not an elevation cue — and because it is a literal, the
`[data-color-scheme]` swap cannot reach it. This is the same class of defect as the first audit's
6.1: **a value derived for the dark scheme only, with nothing checking it per scheme.** 6.1 now has
`app/statusPalette.test.ts`; elevation has nothing.

*Resolve:* declare `--shadow-1/2/3` per palette in `index.css`, bridge them through `@theme
inline`, and replace all eighteen. `tokenSystem.test.ts` is the natural place for the check.

### 4.5 The primary button's glow hardcodes the dark steel — **B** — Medium
`components/ui/buttonVariants.ts:33`

```
shadow-[0_0_0_1px_rgb(107_168_196/30%),0_4px_14px_-4px_rgb(107_168_196/35%)]
```

`rgb(107 168 196)` is `#6ba8c4` — the **dark** palette's `--color-action-primary`. In light the
token is `#2b6883`, so the primary button gets a pale-blue ring and glow around a dark-teal fill:
two different blues on one control, in the scheme where the glow is least able to hide. The render
uses `var(--steel-line)` and `rgb(107 168 196/35%)` — the first of which is a token.

The inner ring is also **30%** where `--steel-line` is **22%**.

This is the **only** hardcoded palette literal left in the app — a search for every dark-palette
hex and rgb across `frontend/src/**/*.{ts,tsx}` returns this one line. The discipline is otherwise
complete, which is what makes it worth fixing rather than a symptom of a wider problem.

---

## 5. Icons and measure

### 5.1 The icon scale runs one size long — **B** — Low

The render's icon sizes are 11, 12, 13, 14, 15, 16px in chrome, and 34px once for the empty-state
mark. The build's distribution is healthy — `size-4` (16px) ×76, `size-3.5` (14px) ×56, `size-3`
×15 — with two additions above the ceiling: **`size-5` (20px)** ×4 (`SessionGate.tsx:43`,
`FeatureImportPage.tsx:339`, `AuditLogPage.tsx:340`, `ExecutionPage.tsx:1702`) and **`size-6`
(24px)** ×1 (`CaseFilterBar.tsx:250`). Also `size-[15px]` — the render's rail-nav icon — is
absent; the rail uses 16px.

### 5.2 Seven one-off measures, and prose's 70ch reaches two places — **B** — Medium

The procedure measure is in excellent shape: `max-w-[74ch]` ×28, and `DocumentSurface` threads both
correctly. But:

- **`max-w-[80ch]`** at `components/PageHeader.tsx:144` — the page-subtitle cap, on every screen
  with a subtitle, **above the design language's stated maximum of 74ch** and drawn at
  `text-sm` (14px) where `.title-row p` is 13px/74ch.
- Seven one-off caps with no counterpart in the render: 60, 52, 46, 44, 42, 34, 22ch.
- `max-w-[70ch]` — the prose measure — appears **twice**, once of which is a report table cell.
  §3 names three prose surfaces; they get it by going through `DocumentSurface`, which is right,
  but it means the number itself is stated once and is easy to lose.

---

## 6. What is conformant, and worth saying so

- **The nine-component kit**, value for value (§1). `DocumentSurface`, `ResultBar`, `Badge` and
  `DocumentTitle` are exact ports; nothing in the kit is more than 2px off.
- **The body baseline.** `index.css:380-384` — IBM Plex Sans, 0.875rem, 1.5, antialiased — is
  `body{}` from the render exactly.
- **Weight discipline.** Six weights across the whole app and not one outside the render's set. No
  `font-bold`, no `font-black`.
- **Palette discipline.** One hardcoded literal in the entire app (§4.5). The white/black-alpha
  family is fully tokenised; the two remaining `bg-white/3` and `bg-black/60` hits are a test
  comment and a dialog scrim.
- **The rail's two widths** — `RAIL_WIDTH = 232`, `RAIL_WIDTH_COLLAPSED = 64` — are the design
  language's §4 numbers including its amendment.
- **Both document measures** and both their type settings, threaded through one component.
- **The `.identifier` rule exists at all**, and is one of the four global rules `index.css` is
  allowed. Its tracking is wrong (§2.6); its existence is right.

---

## 7. What this pass could not check

| Not checked | Why |
|---|---|
| **Rendered size of anything inherited** | Class lists give declared values. An element with no `text-*` takes its parent's, and only a live browser resolves that. |
| **Whether each divergence is visible** | 1px of padding is not 1px of perceived difference. Nothing here is ranked by how it looks, only by how far it is from the stated number. |
| **Hover, focus, active and disabled as painted** | Declared in the class lists and read, but not rendered. |
| **The `74ch` caps as resolved widths** | `ch` depends on the rendered font; the first audit measured this once (74ch = 666px at 1680px) and found it exact. Not re-measured. |
| **Light scheme as drawn** | §4.4 and §4.5 predict specific light-mode defects from the literals. Both are arguments from the source, not screenshots. **Verifying those two is the highest-value item for the live pass.** |
| **Motion** | The app declares `@keyframes tcmsIndeterminate` and a reduced-motion block; the render declares only the reduced-motion block. There is nothing to compare against. |
| **Everything in the structural audit's §10** | Unchanged — same fixture, same limits. |

---

## 8. Summary

**High** — 2.1 the form label (three idioms, none the render's) · 2.2 the big number at five sizes ·
3.1 the 22px gutter that does not exist · 4.4 elevation as eighteen literals, dark-only

**Medium** — 2.4 off-scale sizes and tracking · 2.5 `DocBlockBody` in `rem` · 2.6 the identifier
tracking the wrong way · 3.2 the rail row · 4.1 button and input at 6px · 4.2 two tab idioms ·
4.5 the hardcoded steel glow · 5.2 the 80ch subtitle

**Low** — 2.3 the document title at four sizes · 2.7 two spellings per value · 3.3 the table foot ·
4.3 the 2px radius · 5.1 `size-5` / `size-6`

**Already permitted by the spec (A)** — titles at 24–25px (§3 states the range); the green focus
ring (`buttonVariants.ts` states the reason).

**The one-line version:** the render kit was ported faithfully into
`frontend/src/components/kit/`, and then a third of the screens went around it. Every High finding
is a case of a value the kit already holds being re-typed somewhere else and landing differently —
which means the fixes are substitutions, not redesigns.

---

## 9. Re-verified against HEAD — 2026-08-30, round 2

Eleven commits landed after the checks above were run (`f78d14ab..26095681`), all of them
remediation of the **structural** audit — the runner's failure panel, the board's move menu, the
doc editor's gutter, the retry budget, SSO discovery, the light report preview. Every finding in
this document was re-run against the resulting tree.

**All eighteen findings still stand. Not one was fixed, and no new styling drift was introduced.**
That is the expected result: the two audits touch disjoint surfaces, and none of the eleven commits
was aimed at a value in here.

Re-measured, unchanged:

| check | then | now |
|---|---|---|
| `px-[22px]` in `frontend/src` (§3.1) | 0 | **0** — `main` is still `px-2 py-2 md:px-4 md:py-3` |
| files inlining `--e3` (§4.4) | 14 | **14**; `index.css` still declares no shadow token |
| hardcoded palette literals (§4.5) | 1 | **1** — `buttonVariants.ts:33` |
| `--tracking-wide` (§2.6) | `0.02em` | **`0.02em`** |
| `kit/Field.tsx:53` label (§2.1) | 13px sans medium ink-2 | **unchanged** |
| figure sizes (§2.2) | 22 / 26 / 26 / 30 / 30 / 32px | **unchanged**, six sites |
| off-scale sizes (§2.4) | 9, 17, 30, 32px | **unchanged** |
| off-scale tracking (§2.4) | .04 ×4, .05, .14, −.03 ×2; **.09em absent** | **unchanged** |
| `rounded-[2px]` (§4.3) | 9 | **9** |
| `size-5` / `size-6` (§5.1) | 4 / 1 | **4 / 1** |
| measure caps (§5.2) | 74ch ×28, 70ch ×2, one 80ch, seven one-offs | **identical** |
| weights across the app (§6) | 400/450/500/550/600/620 only | **identical** — still no `font-bold` |
| rail widths (§6) | 232 / 64 | **232 / 64** |

Line numbers moved in six files and are corrected in place above: `DashboardPage` 403→422 and
542→561, `DocPageEditorPage` 346→356, 385→409 and 408→517, `DocsPage` 663→675, `ExecutionPage`
1542→1652, `buttonVariants.ts` 22→23 and 45-47→41-42, `AppShell` 931-943→932-940. §5.1's two icon
sites were also mis-attributed and are corrected: the `size-6` is `CaseFilterBar.tsx:250`, and
`ExecutionPage.tsx:1702` is a `size-5`.

The only substantive change is to `index.css`, which gained the `:root, [data-color-scheme='light']`
selector so a **light island can now exist inside the dark app** — the report preview
(`ReportViewPage.tsx:209`). That does not alter §4.4 or §4.5, because `ReportView` draws no
elevated surface and no primary button; but it does mean a popover or tooltip opened over that
subtree would paint a 60%-black shadow onto an off-white ground, which is the defect §4.4
describes, reachable without the user ever choosing the light scheme.

**Method note for the next round:** every check in this document is a grep. The tallies in the
table above are the whole test, and re-running them is the whole re-audit — which is the argument
for §4.4's and §2.4's real fix being a *computed check* in `tokenSystem.test.ts` rather than a
document, exactly as the first audit's 6.1 got `app/statusPalette.test.ts`.

---

## 10. Remediated — 2026-08-30, round 3

Every finding in this document is fixed. Gates run on `shanks`: frontend build ✔, oxlint 0/0 ✔,
**2184 frontend tests ✔**, packages build ✔, 153 package tests ✔.

### 10.1 What §0's method could not see, and what it cost this document

The deterministic pass read class lists and treated a Tailwind utility as its declared value. That
is true of arbitrary utilities and it was **false of the named ones**, because `index.css` declared
a second type scale — `--text-xs` … `--text-2xl`, `--leading-tight`, `--tracking-wide`, `--radius`,
`--radius-lg` — in a **plain, unlayered `:root`**, while Tailwind's own scale arrives in
`@layer theme`. Unlayered wins the cascade. Verified in the built stylesheet, not reasoned:

| utility | Tailwind's value | what it actually was | render kit |
|---|---|---|---|
| `text-sm` | 14px | **13px** | — |
| `text-base` | 16px | **14px** | `.state h3` 16px |
| `text-2xl` | 24px | **28px** | `.dtitle` 24px |
| `text-xl` | 20px | **22px** | — |
| `rounded-lg` | 8px | **6px** | `--r` 8px |
| `leading-tight` | 1.25 | **1.2** | — |

Nothing in the app read those tokens; everything read the utilities. `--text-2xl` looked like dead
code from T16 while it was setting the document title of five screens to 28px — outside the 24–25px
the design language §3 gives it — and `--radius-lg` meant **no element in the product drew the
render kit's 8px corner**, including the ones §1 credited with getting it right.

Three of this document's own findings were wrong because of it, and are corrected in place:

- **§2.3 was backwards.** `text-2xl` was 28px, so `DocumentSurface` was the outlier and
  `CaseDocument` / `HelpPage`'s hand-rolled `text-[25px]` were the two sites actually in spec.
- **§1 credited `Banner` and `StateBlock` with values they did not have** — an 8px corner that was
  6px, a 16px state heading that was 14px.
- **§2.7 was mis-stated.** `text-sm` / `text-[14px]` were never duplicates (13 vs 14), and
  `leading-tight` was a fourth leading rather than a second spelling of 1.25. The real duplicate
  pair was `text-sm` / `text-[13px]`.

**§5.1 was also wrong, for an unrelated reason.** Four of its five sites — `SessionGate.tsx:43`,
`AuditLogPage.tsx:340`, `CaseFilterBar.tsx:250`, `ExecutionPage.tsx:1702` — are `Button
size="icon"` boxes, a spinner and a numbered circle, not glyphs. Only `FeatureImportPage.tsx:339`
was a real icon off the scale.

The lesson is §0's own caveat, sharpened: **a class list is the computed value only if no one has
redefined the name.** The check for that is now a test.

### 10.2 The fixes

| § | fix |
|---|---|
| **2.1** | `kit/Field.tsx` draws `label.f` — mono, 11px, `.06em`, uppercase, `--ink-4`, 5px. The two screens that spelled their own (`CaseEditorPage`, `ReviewPanel`) fold into it. |
| **2.2** | Six figure sites → one declaration, the kit's 26px/500/−.02em/1.1. |
| **2.3** | One document title: 24px at 36ch everywhere; `DocsPage` gains the measure cap it was missing. |
| **2.4** | 9px→10px, 17px→18px, 30px/32px→26px; `.04em`/`.05em`→`.06em`, `.14em`→`.1em`, `−.03em`→`−.02em`; leading 1.45→1.5. |
| **2.5** | Every `rem` size → the px scale. `DocBlockBody`'s four land on `.prose code` 12.5px, `.tbl td` 13px, `.prose` 14.5px. |
| **2.6** | `--tracking-identifier: -0.01em`, replacing the borrowed `--tracking-wide` (`0.02em`). |
| **2.7** | One spelling per value: 259 named-scale utilities → explicit px, `rounded`/`rounded-[4px]`→`rounded-sm`, `rounded-[6px]`→`rounded-md`, `tracking-[0.0Xem]`→`tracking-[.0Xem]`. |
| **3.1** | `main` and `<header>` take the render kit's 22px gutter at `md` and up. |
| **3.2** | The rail row is an inset 5px pill at `6px 10px` with a 9px gap; the 3px left bar the kit never drew is gone, and the fill alone carries the active state. |
| **3.3** | The table foot is a bar — recessed ground, 22px gutter, 12px summary, 11px mono keys. |
| **4.1** | Button and input at the kit's 8px, `6px 13px` / `5px 10px`, 7px gap, `sm` at 26px, input 12.5px with a 30px floor; danger's border takes the failed tint. The whole `.fld` and `.pop` family moved with them — 24 more radii. |
| **4.2** | The lens is 8px/6px/12px/`4px 11px` on `--e1`; `PlanStatisticsPage`'s underline tabs became the lens, so there is one tab idiom again. |
| **4.3** | The nine 2px corners → 1px on fills and swatches, 4px on the kbd and the scroll target, 5px on the chip. |
| **4.4** | `--elevation-1/2/3` per palette, bridged to `shadow-e1/e2/e3`. All 20 shadow uses tokenised — the light values are ink-tinted at roughly a third of the dark opacity, not black at 40–60%. |
| **4.5** | `--color-action-line` / `--color-action-glow` per palette. The button's ring is back at the kit's 22%. |
| **5.1** | The one real glyph → 34px, the value the kit gives a state mark. |
| **5.2** | The page subtitle → 74ch, the design language §3 maximum. |
| **§1** | The kit's six sub-2px deltas closed: `th` 9px, `.banner` 11px, `.kv` 5px, `.chip` 9px, `.acts` 9px, and the title cell's weight back to 450. |

Two deliberate departures from the render kit, both recorded at the call site:

- **`Input` keeps `--ink`, not the kit's `--ink-2`.** `.fld` is a static mock of a control; typed
  input at the muted weight reads as placeholder text in a real one.
- **The mobile gutter stays below 22px.** The renders are desktop-only and a phone cannot spend
  44px on margins, so 22px applies from `md` up.

### 10.3 What now enforces this

Four checks in `app/tokenSystem.test.ts`, in the shape the file already used for the white-alpha
family:

- `--elevation-1/2/3` and `--color-action-line/glow` are declared in **both** palettes.
- No component spells a shadow **colour** — the geometry may be inline, the colour may not.
- No component hardcodes a palette hue.
- **`index.css` declares no name Tailwind's own `@theme` declares, outside `@theme`.** The Tailwind
  names are read from `node_modules/tailwindcss/theme.css` rather than listed, so the check cannot
  go stale on a minor. This one also caught a duplicate the audit had missed: `--font-mono` was
  declared twice with different fallback stacks, and the unlayered copy was winning — so
  `font-mono` in a class list and `var(--font-mono)` in a rule resolved through different
  declarations. Both families are now declared once.

### 10.4 Still unverified

**Nothing here was looked at.** The Chrome extension was not connected for any of the three rounds,
so this is a source-level remediation verified by gates and by reading the built stylesheet, not by
seeing the screens. The changes most worth a visual pass, in order:

1. **The 22px gutter** — it moves all forty-two screens at once (§3.1).
2. **The light scheme's elevation** — the whole point of §4.4, and the one thing a test cannot judge.
3. **`FeatureImportPage`'s 34px drop-target mark** — the scale says 34px for that role; whether it
   reads as heavy in a compact dashed box is a judgement I could not make (§5.1).
4. **The rail** without its left bar, and the form labels at 11px mono uppercase — both are large
   perceptual changes to surfaces a user sees on every screen.
