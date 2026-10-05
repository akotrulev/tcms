# 008 — Closing the renders-vs-implementation audit

**Status (filed 2026-10-05):** Delivered — all nineteen tasks landed on 2026-08-30 and every gate passed (Results, below); the audit's round 2 (`26095681`) records what closed. Unverified: no browser pass at either width or scheme, so the four layout findings (1.3, 2.1, 3.2, 4.1) are fixed in mechanism, not seen in pixels; T1's seed exit code has not been driven against a rebuilt image. Findings 1.4 and 1.12 are blocked on the contract and 6.4 was declined — each recorded in the Results. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-08-30 · **Written by:** `ep-writing-plans` · **Status:** proposed, nothing implemented

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all 34 findings in
[`docs/audits/2026-08-30-renders-vs-implementation.md`](../../audits/2026-08-30-renders-vs-implementation.md)
— by moving the build to the render where the render is right, and by amending the spec where the
render is wrong (plan 007 §4's rule).

**Architecture:** The audit's findings are not 34 independent bugs. They fall into three shapes, and
the task order follows that shape rather than the audit's section order:

1. **The mechanism already exists and the call site does not use it** — `StateBlock` already takes
   `grantee`, `hidden`/`filters` and `reference`/`timestamp`; `kit/ResultBar` already draws the
   proportion bar; `MultiPicker` already is the render's applied-chip idiom; `Figure`'s `legend`
   prop already exists. Nine findings (1.4, 6.2, 7.1, 9.2, 9.4, 1.1, 1.2, 1.13, 9.5) are wiring.
2. **A shared slot is the wrong shape, so several screens are wrong the same way** — the header
   action slot (2.1 and half of 3.2), the rail's value column (2.3), the missing table foot (1.7).
   These are fixed once in `AppShell`/`kit`, then consumed.
3. **A genuine behaviour gap** — the failure refusal (3.3), the eternal skeleton (9.3), the light
   status palette (6.1). These carry the real risk and get their own tasks with their own tests.

**Two of the audit's findings are re-diagnosed by this plan**, because reading the code changed what
the fix is. Both corrections are recorded in the tasks that own them:

- **3.3 "Recording a failure has no failure panel" — the panel exists.** `ExecutionPage.tsx:1122`
  renders it, `:672` computes `failureNeedsDescription`, and `:825` refuses on it. The gate is keyed
  on `failedStep !== null` — a *step* marked Failed — so pressing the case-level **Failed** with no
  step marked walks straight past it. This is a one-predicate defect, not an unimplemented render.
  See **T10**.
- **8.1 "Sign-in asks for the password before discovering the method" — discovery is implemented.**
  `LoginPage.tsx:111` fires `useSsoProviders()` on the email once it contains `@`. What is missing is
  that the password field is rendered from the start rather than after discovery answers. This is a
  presentation change, not a feature. See **T15**.

**Tech stack:** Unchanged — Vite 8 · React 19 · TS strict · Tailwind v4 · shadcn/ui on Radix ·
TanStack Query + Table · Vitest + RTL. T1 and T14 also touch Spring Boot 3.5.6 / Java 21 and
`docs/specs/openapi.yaml`.

**Gates run once, at the end — not per task.** This follows the standing user directive recorded in
plan 007's header and is the same trade: T2–T18 each write their code, run at most a single test
file, and commit; the single full gate pass is **T19**, and it runs on `shanks` per CLAUDE.md
(`~/bin/tcms-remote`). A task's own `grep`/single-file checks stay — they cost nothing and are not a
gate. The cost is stated rather than discovered: between T2 and T19 the tree is *expected* to build
but is not *proven* to, and any breakage T2–T18 introduces surfaces in T19, which is therefore a
gate pass **and** its fix-up.

**Work on the current branch.** Per CLAUDE.md: no new branch, one lane, no parallel agents.

**Tasks** — in the order they are done:

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — The seeder stops reporting success on an incomplete fixture | `seed-demo-data` exits `3` and prints a `Refusals` block when a documented fixture element was refused | none |
| 2 | T2 — The light status palette, re-stepped and computed | Light `--color-blocked`/`--color-when` clear §6's 15 ΔE floor against every adjacent fill; `statusPalette.test.ts` measures both schemes | none |
| 3 | T3 — Kit: `TableFoot`, `FilterStrip`, and a rail that does not clip | Three kit exports exist with tests: `TableFoot`, `FilterStrip`, and `RailRow` truncating its value with a title | none |
| 4 | T4 — The header action slot stops overflowing | At 1280px `CaseDetailPage`'s five actions render inside the 52px header, the last two behind an overflow menu | none |
| 5 | T5 — The case list filter strip | `CaseFilterBar` renders one row: search, per-facet pickers, applied chips, `Showing n of m`, `Clear filters` | T3 |
| 6 | T6 — The case grid's columns | Title column ≥300px at 1680px; Last result draws a `ResultBar`; Health is a `Badge`; Priority leaves the reserved hues; the grid has a foot | T2, T3 |
| 7 | T7a — Index surfaces: the small, independent ones | `ArtifactTable` emits `<th>`; project cards carry three bars, a `Current` badge and no dangling noun; proposal tabs carry counts | T3 |
| 8 | T7b — Index surfaces: Reports and run schedules | Reports draws the shared jobs table beside CI ingestion; schedules lead with a per-row switch and a humanised cadence and offer no Delete | T3 |
| 9 | T8 — The record screens | The run legend names `skipped`; the three rails stop clipping; Reliability/Attachments/Issues lose their cards | T3 |
| 10 | T9 — The report preview is a light surface | `ReportViewPage` renders its preview under `data-color-scheme="light"` and its scope prose at 70ch | none |
| 11 | T10 — A case cannot be recorded Failed without saying what happened | Pressing **Failed** with no failed step opens the failure panel and refuses until a description is typed | none |
| 12 | T11 — The runner holds together at 1280 | At 1280px the top strip does not wrap and the procedure column stays ≥60ch; the queue shows a per-case dot and duration; the run bar is in the queue head | T3, T4 |
| 13 | T12 — The board card at 1280 | At 1280px a one-line task card is ≤200px tall and no metadata line ends in a dangling `·` | none |
| 14 | T13 — Authoring: gutter not toolbar, three columns, chrome outside the column | The doc editor has no per-block toolbar; feature import is three columns ≥`lg`; documentation's actions sit in the strip above | none |
| 15 | T14 — A refusal names a person | `GET /permissions/{permission}/grantees` exists and `ApiTokensPage`'s refusal reads "Ask Dana Whitfield to grant it." | none |
| 16 | T15 — Sign-in discovers the method before asking for a password | The password field mounts only after discovery answers for the typed email | none |
| 17 | T16 — Analytics legends, and figures without cards | `By feature` and `By owner` carry legends; dashboard figures are not bordered panels | none |
| 18 | T17 — A failure looks like a failure, and says which one | A persistent 503 reaches FAILED in ~8s; every failure carries a six-character reference and a timestamp; a shape error takes the region, not the route | none |
| 19 | T18 — The spec is amended where the render was wrong | `2026-08-28-design-language.md` records four amendments, each struck-through-and-superseded | T2, T11 |
| 20 | T19 — Gates, browser re-verification, audit round 2 | All four gates green on `shanks`; `docs/audits/2026-08-30-renders-vs-implementation.md` carries a round-2 status against every finding | T1–T18 |

---

## 0.1 Size, and the ceiling every task is held to

**No task changes more than ~500 lines**, counting additions, deletions and its own tests. The
estimates below are from the current file sizes, and the one that broke the ceiling — the original
T7, at ~600 across five screens — was split rather than waved through. See T7a's superseded note.

| Task | Changed lines (est.) | What dominates it |
|---|---|---|
| T1 | ~120 | six call sites + the report + the exit code + a new test |
| T2 | ~130 | two CSS values; the rest is the ΔE test |
| T3 | ~330 | two new kit components (~150) with their docblocks + tests |
| T4 | ~200 | `HeaderActions` (~110) + its test + one call site |
| T5 | ~450 | `CaseFilterBar` is 337 lines and roughly two thirds of it goes |
| T6 | ~230 | four column definitions in a 507-line file |
| T7a | ~200 | three small edits in three files |
| T7b | ~400 | `cron.ts` + its test (~130), the schedules row, the Reports jobs panel |
| T8 | ~120 | two legend insertions + four uncarded blocks |
| T9 | ~90 | one attribute and one prose column |
| T10 | ~110 | one predicate, one guard, one payload branch, three tests |
| T11 | ~450 | the largest — a 1556-line file's grid, top strip and queue |
| T12 | ~200 | the board card, in an 805-line file |
| T13 | ~330 | ~95 deleted toolbar lines + a gutter menu + two small layouts |
| T14 | ~450 | contract + service + controller + backend test + hook + call site |
| T15 | ~60 | one conditional mount |
| T16 | ~120 | one legend constant, two call sites, seven unwrapped figures |
| T17 | ~300 | the retry budget + the reference + the boundary rewrite + tests |
| T18 | ~120 | documentation only |
| T19 | varies | whatever the gate finds, plus the audit's round-2 column |

Four sit in the 400–500 band — T5, T7b, T11 and T14 — and each is one file's structure plus its
tests rather than a sprawl; none can be halved without leaving the tree red at the boundary, which
is the property that actually matters. **T14's number is inflated by generated code**: the two
`schema.d.ts` / `schema.ts` diffs are ~80 of it and are produced by `npm run gen:api`, not written.

---

## 0. What this plan does not do

- **It does not touch the tenancy invariant, the migration chain, or the permission catalogue.**
  T14 adds one read endpoint; it reads `instance_role_assignment` and the company's grants through
  the existing services and adds no table and no migration.
- **It does not re-run the audit's own browser pass task by task.** T19 does that once, against a
  freshly seeded fixture that T1 makes trustworthy.
- **It does not fix what the audit could not check (§10).** The org chart at five projects, a
  fifty-step case, `AcceptInvitation`/`SsoCallback`, revoked/paused rows — all remain unchecked, and
  T19 restates them as still-unchecked rather than pretending otherwise. Extending the fixture to
  reach them is its own plan.
- **It does not add a second status token per surface.** T2 considered splitting `--color-blocked`
  into a fill and a text variant and rejected it; the reason is recorded in T2.

---

### T1 — The seeder stops reporting success on an incomplete fixture

**Deliverable:** `docker compose run --rm backend seed-demo-data` exits `3` and prints a `Refusals`
block naming every fixture element that was refused, instead of exiting `0` with the refusals only
in `WARN` lines above the report.
**Depends on:** none

Audit §0. This is first because every later task's verification depends on a fixture you can trust,
and the audit lost two attempts to exactly this.

**Files:**
- Modify: `backend/src/main/java/com/tcms/DemoSeedReport.java:34` (add `refusal`), `:36` (`render`)
- Modify: `backend/src/main/java/com/tcms/DemoExecutionSeeder.java:254`, `:258`, `:291`, `:344`
- Modify: `backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java` (the doc-page refusal arm)
- Modify: `backend/src/main/java/com/tcms/DemoDataSeeder.java:183`
- Modify: `backend/src/main/java/com/tcms/SeedDemoDataCommand.java:52-60` (the exit-code Javadoc), `:67`
- Test: `backend/src/test/java/com/tcms/DemoSeedReportTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.tcms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A seed run that refused a documented fixture element must say so in the report, not only in a
 * WARN line above it. The audit of 2026-08-30 lost two environment builds to a report that read
 * "Seeded demo data" over a fixture with no test plans and no doc pages.
 */
class DemoSeedReportTest {

    @Test
    void refusalsAppearInTheReportAndMarkItIncomplete() {
        DemoSeedReport report = new DemoSeedReport();
        report.line("Northwind", "WEB — 10 cases");
        report.refusal("Plan for 2026.8.0 was refused: entryCriteria ['gte' is not a comparison]");

        assertThat(report.isComplete()).isFalse();
        assertThat(report.render())
                .contains("Refusals")
                .contains("Plan for 2026.8.0 was refused");
    }

    @Test
    void aRunWithNoRefusalsIsComplete() {
        DemoSeedReport report = new DemoSeedReport();
        report.line("Northwind", "WEB — 10 cases");

        assertThat(report.isComplete()).isTrue();
        assertThat(report.render()).doesNotContain("Refusals");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.DemoSeedReportTest'`
Expected: FAIL — `cannot find symbol: method refusal(String)`.

- [ ] **Step 3: Add `refusal` and `isComplete` to the report**

In `DemoSeedReport.java`, beside the existing `notes` list:

```java
    /**
     * A documented fixture element the run asked for and did not get.
     *
     * <p>Separate from {@link #note} on purpose: a note is a skip the operator chose (a project
     * that already had content), a refusal is the run disagreeing with itself. Only the second one
     * makes the exit code non-zero, because only the second one leaves the next reader hunting a
     * frontend bug that is not there.
     */
    private final List<String> refusals = new ArrayList<>();

    void refusal(String refusal) {
        refusals.add(refusal);
    }

    boolean isComplete() {
        return refusals.isEmpty();
    }
```

and in `render()`, immediately after the `notes` block and before the credentials block:

```java
        if (!refusals.isEmpty()) {
            out.append("\nRefusals — this fixture is INCOMPLETE\n");
            refusals.forEach(refusal -> out.append("  ").append(refusal).append('\n'));
        }
```

- [ ] **Step 4: Route every existing refusal through it**

At each of the six `log.warn(... "was refused" ...)` call sites listed under **Files**, keep the log
line and add the report call next to it. `DemoExecutionSeeder.java:254` becomes:

```java
                log.warn("Plan for {} was refused: Invalid {}", releaseOf(spec), invalid.errors());
                report.refusal("Plan for %s was refused: Invalid %s".formatted(releaseOf(spec), invalid.errors()));
```

Apply the same shape at `:258`, `:291`, `:344`, at `DemoWorkspaceSeeder`'s doc-page arm, and at
`DemoDataSeeder.java:183`. `DemoExecutionSeeder.java:466` already calls `report.note(...)` for the
ingestion arm — change that one call to `report.refusal(...)`.

- [ ] **Step 5: Add the exit code**

In `SeedDemoDataCommand.java`, beside the existing constants:

```java
    private static final int EXIT_INCOMPLETE = 3;
```

and where the command currently finishes after printing `report.render()`, return
`report.isComplete() ? EXIT_OK : EXIT_INCOMPLETE`. Extend the class Javadoc's `<h2>Exit codes</h2>`
list with the fourth entry:

```java
 *   <li>{@code 3} — the run completed, but at least one documented fixture element was refused and
 *       the printed report says which. The instance holds everything that did succeed.</li>
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.tcms.DemoSeedReportTest'`
Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/DemoSeedReport.java \
        backend/src/main/java/com/tcms/DemoExecutionSeeder.java \
        backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java \
        backend/src/main/java/com/tcms/DemoDataSeeder.java \
        backend/src/main/java/com/tcms/SeedDemoDataCommand.java \
        backend/src/test/java/com/tcms/DemoSeedReportTest.java
git commit -m "fix(seed): a refused fixture element makes seed-demo-data exit 3, not 0"
```

---

### T2 — The light status palette, re-stepped and computed

**Deliverable:** Light `--color-blocked` (and its parity twin `--color-when`) clear design language
§6's 15 ΔE floor against every adjacent `ResultBar` fill, and `app/statusPalette.test.ts` measures
both schemes so a third scheme cannot repeat the omission.
**Depends on:** none

Audit 6.1 — the audit's most consequential finding, and the one CLAUDE.md's "a palette-wide
invariant needs a computed check, not a careful reading" rule names directly.

**The measurement, reproduced.** Euclidean distance in OKLab ×100, the metric §6 used, verified
against the dark palette first: `skipped↔untested` = 11.3 (§6 says 10.7), `failed↔blocked` = 17.3
(§6 says 17.3 exactly). `ResultBar`'s fill order is fixed — passed, failed, blocked, skipped — so:

| adjacent pair | dark | light now | light after |
|---|---|---|---|
| passed \| failed | 22.9 | 24.3 | 24.3 |
| failed \| blocked | 17.3 | **12.6** ✗ | **18.9** ✓ |
| blocked \| skipped | 19.8 | **13.0** ✗ | **15.9** ✓ |

**The chosen value is `#623c00`**, a deep ochre. It also keeps 18.2 ΔE from light `--color-passed`
and reads at **8.26:1** against `--color-surface-sunken` (`#e9edf4`) — which matters because these
tokens are used as *text* colour in the run legend (`RunDetailPage.tsx:406-410`), not only as bar
fills. That double duty is what makes the light palette hard: the obvious brighter amber `#b07d00`
clears 15 ΔE but falls to 3.33:1 as text, trading a colour-blindness failure for a contrast failure.

**Considered and rejected:** splitting each status into a fill token (3:1, graphic) and a text token
(4.5:1). It is the principled answer to the double duty and it costs six new tokens plus a rule
about which to use where, on a system whose whole premise (design language §1) is one token per
meaning. Revisit it only if a second status pair is forced below the floor.

**`--color-when` must move with it.** `app/tokenSystem.test.ts:115` asserts
`['--color-when', '--color-blocked']` parity — rule 5 makes `When` and `Blocked` one hue and that is
a claim about meaning, not a coincidence. Changing one and not the other reddens `tokenSystem`.

**Files:**
- Modify: `frontend/src/index.css:171` (`--color-blocked`), `:181` (`--color-when`)
- Test: `frontend/src/app/statusPalette.test.ts` (create)
- Modify: `docs/specs/2026-08-28-design-language.md` §6 — done in T17, not here

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/statusPalette.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * Design language §6 sets a floor of 15 ΔE (Euclidean distance in OKLab, ×100) between any two
 * fills that can sit next to each other in a `ResultBar`. §6 derived its numbers for the dark
 * palette ONLY, and the light one was never put through the same check — the 2026-08-30 audit
 * found it failing on two of three adjacent pairs, red beside brown reading as one warm band.
 *
 * This measures both schemes from the stylesheet text, so a third scheme cannot repeat it. The
 * check reads the CSS rather than the DOM because jsdom does not resolve `@media
 * (prefers-color-scheme)` and the defect lives in the declared value, not the rendered one — the
 * same reason `ConfigConventionTest` reads the YAML text.
 */

const CSS = readFileSync(new URL('../index.css', import.meta.url), 'utf8');

/** `ResultBar`'s fill order is fixed, so these three pairs are the only ones that touch. */
const ADJACENT: [string, string][] = [
  ['passed', 'failed'],
  ['failed', 'blocked'],
  ['blocked', 'skipped'],
];

const FLOOR = 15;

function srgbToLinear(channel: number): number {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function oklab(hex: string): [number, number, number] {
  const r = srgbToLinear(parseInt(hex.slice(1, 3), 16));
  const g = srgbToLinear(parseInt(hex.slice(3, 5), 16));
  const b = srgbToLinear(parseInt(hex.slice(5, 7), 16));
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function deltaE(a: string, b: string): number {
  const [l1, a1, b1] = oklab(a);
  const [l2, a2, b2] = oklab(b);
  return 100 * Math.hypot(l1 - l2, a1 - a2, b1 - b2);
}

/**
 * The two blocks that declare the status set. Splitting on the dark block's opening selector is
 * enough: `index.css` declares the light palette on bare `:root` and redefines it once under the
 * dark guard, and `tokenSystem.test.ts` already fails if a third declaration site appears.
 */
function schemes(): Record<'light' | 'dark', Record<string, string>> {
  const darkAt = CSS.indexOf(':root:not([data-theme="light"])');
  expect(darkAt, 'the dark palette block moved — update this test, do not delete it').toBeGreaterThan(0);
  const read = (block: string) =>
    Object.fromEntries(
      [...block.matchAll(/--color-(passed|failed|blocked|skipped|untested):\s*(#[0-9a-f]{6})/g)].map(
        (match) => [match[1], match[2]],
      ),
    );
  return { light: read(CSS.slice(0, darkAt)), dark: read(CSS.slice(darkAt)) };
}

describe('the status palette, per scheme', () => {
  const palettes = schemes();

  for (const scheme of ['light', 'dark'] as const) {
    it(`declares all five status fills in ${scheme}`, () => {
      expect(Object.keys(palettes[scheme]).sort()).toEqual([
        'blocked',
        'failed',
        'passed',
        'skipped',
        'untested',
      ]);
    });

    for (const [left, right] of ADJACENT) {
      it(`${scheme}: ${left} and ${right} are at least ${FLOOR} ΔE apart`, () => {
        const distance = deltaE(palettes[scheme][left], palettes[scheme][right]);
        expect(
          distance,
          `${scheme} ${left}(${palettes[scheme][left]}) | ${right}(${palettes[scheme][right]}) = ${distance.toFixed(1)}`,
        ).toBeGreaterThanOrEqual(FLOOR);
      });
    }
  }
});
```

- [ ] **Step 2: Run it and watch the two light pairs fail**

Run: `cd frontend && npx vitest run src/app/statusPalette.test.ts`
Expected: FAIL — `light: failed | blocked = 12.6` and `light: blocked | skipped = 13.0`; the four
dark assertions and both `declares all five` assertions PASS.

- [ ] **Step 3: Re-step the light token, and its parity twin**

`frontend/src/index.css:171` — replace the line and extend the comment block above the light status
set so the value carries its reason:

```css
  --color-passed: #2c7a58;
  --color-failed: #bd3a2b;
  /* Re-stepped from #8a5f0d on 2026-08-30 (audit 6.1). §6's 15 ΔE floor was derived for the dark
     palette only; at #8a5f0d this sat 12.6 from `failed` and 13.0 from `skipped`, and the light
     TR-1 bar drew red beside brown reading as one warm band. #623c00 clears the floor on both
     (18.9 / 15.9) and still carries 8.26:1 as TEXT on --color-surface-sunken, which these tokens
     also have to do — the run legend colours its words with them. A brighter amber clears ΔE and
     fails contrast; see plan 008 T2 for the search. `statusPalette.test.ts` is the check. */
  --color-blocked: #623c00;
  --color-skipped: #5c6674;
  --color-untested: #646d7a;
```

`frontend/src/index.css:181` — the same value, because `tokenSystem.test.ts:115` asserts the pair:

```css
  --color-when: #623c00;
```

- [ ] **Step 4: Run both the palette check and the token system**

Run: `cd frontend && npx vitest run src/app/statusPalette.test.ts src/app/tokenSystem.test.ts`
Expected: PASS — 12 palette assertions and `tokenSystem` unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/index.css frontend/src/app/statusPalette.test.ts
git commit -m "fix(theme): the light status palette clears §6's 15 ΔE floor, and a test measures both schemes"
```

---

### T3 — Kit: `TableFoot`, `FilterStrip`, and a rail that does not clip

**Deliverable:** Three things exist in `frontend/src/components/kit/` with tests — a `TableFoot`
that states what is on screen and what the keyboard does, a `FilterStrip` that draws the render's
one-row applied-filter idiom, and a `RailRow` whose value truncates with a `title` instead of
running off the viewport.
**Depends on:** none

Audit 1.7 (no table foot, on 41 of 42 screens), 1.1/1.2 (two filter idioms, neither the render's),
2.3 (three rails clip their own values). All three are shared-slot defects, so they are fixed once
here and consumed by T5–T8 and T11.

**Files:**
- Create: `frontend/src/components/kit/TableFoot.tsx`
- Create: `frontend/src/components/kit/FilterStrip.tsx`
- Modify: `frontend/src/components/kit/RailRow.tsx:56`, `:76` (the two value spans)
- Modify: `frontend/src/components/kit/index.ts` (three new exports)
- Test: `frontend/src/components/kit/kitRules.test.tsx` (extend)

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/components/kit/kitRules.test.tsx`:

```tsx
describe('TableFoot', () => {
  it('states what is on screen, the sort, and the keys', () => {
    render(
      <TableFoot
        summary="86 cases · 12 selected"
        sort="sorted by Updated, newest first"
        keys={['↑↓ move', 'Space select', 'Enter open']}
        data-testid="Cases.Foot"
      />,
    );
    const foot = screen.getByTestId('Cases.Foot');
    expect(foot).toHaveTextContent('86 cases · 12 selected');
    expect(foot).toHaveTextContent('sorted by Updated, newest first');
    expect(foot).toHaveTextContent('Space select');
  });
});

describe('FilterStrip', () => {
  it('renders one chip per APPLIED filter, each removable', async () => {
    const remove = vi.fn();
    render(
      <FilterStrip
        applied={[{ key: 'lifecycleStatus:APPROVED', label: 'Status · Approved', onRemove: remove }]}
        summary="Showing 86 of 1 284"
        onClear={vi.fn()}
        data-testid="Cases.Filters"
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Remove Status · Approved' }));
    expect(remove).toHaveBeenCalledOnce();
  });

  it('offers no clear affordance when nothing is applied', () => {
    render(<FilterStrip applied={[]} summary="86 cases" data-testid="Cases.Filters" />);
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });
});

describe('RailRow', () => {
  it('truncates a long value and keeps it reachable as a title', () => {
    render(<RailRow label="Test method" value="checkout.saved_card.happy_path_with_a_long_tail" />);
    const value = screen.getByTitle('checkout.saved_card.happy_path_with_a_long_tail');
    expect(value).toHaveClass('truncate');
  });
});
```

Add `TableFoot` and `FilterStrip` to the file's `import { … } from './index'` line, and `userEvent`
from `@testing-library/user-event` if it is not already imported.

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/components/kit/kitRules.test.tsx`
Expected: FAIL — `TableFoot` and `FilterStrip` are not exported; the `RailRow` case fails on
`Unable to find an element by title`.

- [ ] **Step 3: Write `TableFoot`**

```tsx
import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * Design language rule 2: the table's footer *"states what is on screen and what the keyboard can
 * do"*. The 2026-08-30 audit found this on exactly one screen out of forty-two — the execution
 * runner — with every other index leaving its count floating above the table and saying nothing
 * about the sort or any shortcut.
 *
 * It is a `<div role="status">` beside the table rather than a `<tfoot>` inside it, and that is
 * deliberate: the table lives in `ScrollX`, so a `<tfoot>` would scroll horizontally away from the
 * reader who most needs it, and a summary that changes as filters change is a live region.
 *
 * `keys` is optional because not every index has shortcuts. A screen that has them and does not
 * name them is the defect; a screen that has none and names none is correct.
 */
export interface TableFootProps {
  /** What is on screen — "86 cases · 12 selected". */
  summary: ReactNode;
  /** How it is ordered — "sorted by Updated, newest first". */
  sort?: ReactNode;
  /** One entry per binding, already written as the reader reads it — "J/K case". */
  keys?: string[];
  className?: string;
  'data-testid'?: string;
}

export function TableFoot({ summary, sort, keys, className, ...rest }: TableFootProps) {
  return (
    <div
      role="status"
      data-testid={rest['data-testid']}
      className={cn(
        'mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line pt-2',
        'text-[11.5px] text-ink-4',
        className,
      )}
    >
      <span className="tabular-nums">{summary}</span>
      {sort && <span>{sort}</span>}
      {keys && keys.length > 0 && (
        <span className="ml-auto flex flex-wrap gap-3 font-mono">
          {keys.map((key) => (
            <span key={key}>{key}</span>
          ))}
        </span>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Write `FilterStrip`**

```tsx
import type { ReactNode } from 'react';

import { Button } from '../ui';
import { cn } from '../../lib/cn';
import { Chip } from './Chip';

/**
 * Design language rule 2: *"filters in one row under the toolbar"*.
 *
 * The distinction this component exists to hold is §5's: **a chip here means "this filter is
 * applied; remove it", never "this value exists; apply it".** What shipped before it
 * (`CaseFilterBar`, audit 1.1) listed every available value of four vocabularies as a toggle chip —
 * eleven for Health alone — which is why that strip could not be one row: it scaled with the
 * vocabulary rather than with what the reader had chosen, and put ~480px of chrome above the first
 * data row.
 *
 * So: choosing happens in `pickers` (a `Combobox`/`MultiPicker` per facet, closed at rest), and the
 * strip shows only what is chosen. The consequence is that the strip's height is bounded by the
 * reader's own choices, which is the property rule 2 is actually asking for.
 */
export interface AppliedFilter {
  /** Stable across renders — the facet and the value, e.g. `lifecycleStatus:APPROVED`. */
  key: string;
  /** Written as the reader reads it: `Status · Approved`. */
  label: string;
  onRemove: () => void;
}

export interface FilterStripProps {
  /** The search box, when this family has one. */
  search?: ReactNode;
  /** One closed picker per facet. */
  pickers?: ReactNode;
  applied: AppliedFilter[];
  /** `Showing 86 of 1 284` — or the plain count when nothing is applied. */
  summary?: ReactNode;
  /** Omitted hides the control: there is nothing to clear. */
  onClear?: () => void;
  className?: string;
  'data-testid'?: string;
}

export function FilterStrip({
  search,
  pickers,
  applied,
  summary,
  onClear,
  className,
  ...rest
}: FilterStripProps) {
  const testId = rest['data-testid'] ?? 'Filters';
  return (
    <div
      data-testid={rest['data-testid']}
      className={cn('mb-3 flex flex-wrap items-center gap-2 text-[12.5px]', className)}
    >
      {search}
      {pickers}
      {applied.map((filter) => (
        <Chip
          key={filter.key}
          onRemove={filter.onRemove}
          removeLabel={`Remove ${filter.label}`}
          data-testid={`${testId}.Applied.${filter.key}`}
        >
          {filter.label}
        </Chip>
      ))}
      {summary && <span className="ml-auto tabular-nums text-ink-4">{summary}</span>}
      {onClear && applied.length > 0 && (
        <Button variant="ghost" size="sm" onClick={onClear} data-testid={`${testId}.Clear`}>
          Clear filters
        </Button>
      )}
    </div>
  );
}
```

Read `components/kit/Chip.tsx` before wiring this — if it spells the remove handler or its
accessible name differently, use `Chip`'s spelling here rather than widening `Chip`.

- [ ] **Step 5: Stop `RailRow` clipping**

`RailRow.tsx` — both value spans, at `:56` (the read-only arm) and `:76` (the button arm). Extend
the component's docblock:

```tsx
 * The value column truncates rather than overflowing. Audit 2.3 found three screens
 * (`CaseDetailPage`, `CaseEditorPage`, `ExecutionPage`) where the value was cut off at the viewport
 * edge with no scroll and no ellipsis — `Automation  Automated · checkout.saved_card.hap` — and
 * rule 6 says long mono identifiers are exactly what lives in these rows. `title` carries the full
 * value for anyone who needs it; the label stays `shrink-0` because a truncated label is worse than
 * a truncated value.
```

and replace both spans with:

```tsx
      <span
        className="flex min-w-0 items-center gap-1.5 truncate text-right font-normal text-ink"
        title={typeof value === 'string' ? value : undefined}
      >
```

- [ ] **Step 6: Export all three**

In `frontend/src/components/kit/index.ts`:

```ts
export { TableFoot, type TableFootProps } from './TableFoot';
export { FilterStrip, type FilterStripProps, type AppliedFilter } from './FilterStrip';
```

- [ ] **Step 7: Run the kit tests and verify they pass**

Run: `cd frontend && npx vitest run src/components/kit/kitRules.test.tsx`
Expected: PASS — the existing cases plus the four new ones.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/kit/TableFoot.tsx frontend/src/components/kit/FilterStrip.tsx \
        frontend/src/components/kit/RailRow.tsx frontend/src/components/kit/index.ts \
        frontend/src/components/kit/kitRules.test.tsx
git commit -m "feat(kit): TableFoot, FilterStrip, and a RailRow that truncates instead of clipping"
```

---

### T4 — The header action slot stops overflowing

**Deliverable:** At 1280px `CaseDetailPage`'s five actions render inside the shell's 52px header
with the breadcrumb still legible — the first three visible, the rest behind an overflow menu — and
no action's `bottom` exceeds the header's.
**Depends on:** none

Audit 2.1, and the mechanism half of 3.2. `AppShell.tsx:985` is `h-[52px]` and `:797` states the
reason (*"The header has a fixed height and does not collapse"*); `CaseDetailPage.tsx:210` portals a
`flex flex-wrap` group into it. Measured by the audit: header `bottom: 52`, Delete button
`bottom: 57.5` — a second row of buttons sitting on top of the page content.

**The decision: the slot collapses, the header does not grow.** Growing it would make the one
measurement the whole shell is laid out against variable, and the audit itself names the pattern to
match — the case editor's commit bar, which fits.

**Files:**
- Create: `frontend/src/app/HeaderActions.tsx`
- Modify: `frontend/src/features/cases/CaseDetailPage.tsx:210`
- Modify: `frontend/src/app/AppShell.tsx:985` (the portal target)
- Test: `frontend/src/app/HeaderActions.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { HeaderActions } from './HeaderActions';

describe('HeaderActions', () => {
  const five = [
    { key: 'run', label: 'Add to run', onSelect: () => {} },
    { key: 'edit', label: 'Edit', onSelect: () => {} },
    { key: 'clone', label: 'Duplicate', onSelect: () => {} },
    { key: 'archive', label: 'Archive', onSelect: () => {} },
    { key: 'delete', label: 'Delete', onSelect: () => {} },
  ];

  it('keeps the first `inline` actions visible and folds the rest away', () => {
    render(<HeaderActions actions={five} inline={3} data-testid="CaseDetail.Actions" />);
    expect(screen.getByRole('button', { name: 'Add to run' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More actions' })).toBeInTheDocument();
  });

  it('reaches a folded action through the menu', async () => {
    render(<HeaderActions actions={five} inline={3} data-testid="CaseDetail.Actions" />);
    await userEvent.click(screen.getByRole('button', { name: 'More actions' }));
    expect(await screen.findByRole('menuitem', { name: 'Delete' })).toBeInTheDocument();
  });

  it('shows no overflow control when everything fits', () => {
    render(<HeaderActions actions={five.slice(0, 2)} inline={3} data-testid="CaseDetail.Actions" />);
    expect(screen.queryByRole('button', { name: 'More actions' })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run src/app/HeaderActions.test.tsx`
Expected: FAIL — `Cannot find module './HeaderActions'`.

- [ ] **Step 3: Write `HeaderActions`**

```tsx
import { MoreHorizontal } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from '../components/ui';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/ui/dropdown-menu';

/**
 * The shell's header action slot, which collapses rather than wrapping.
 *
 * `AppShell.tsx` states that the header has a fixed 52px height and does not collapse. A
 * `flex-wrap` group portalled into it therefore does not wrap *inside* the header — it wraps
 * *through* it: the 2026-08-30 audit measured `CaseDetailPage`'s five actions putting a second row
 * of buttons 5.5px below the header's own bottom border, on top of the content, with the breadcrumb
 * truncated behind them.
 *
 * Growing the header instead was considered and rejected: the 52px is the one measurement the whole
 * shell is laid out against, and a variable one turns every sticky offset into a guess.
 *
 * `inline` is a count, not a media query, because what fits depends on the labels this screen uses
 * and not on the viewport alone — and a count is testable in jsdom, where a measurement is not.
 */
export interface HeaderAction {
  key: string;
  label: string;
  onSelect: () => void;
  disabled?: boolean;
  /** Rendered before the label on the inline arm only; menu items get text. */
  icon?: ReactNode;
  /** The destructive arm reads red in the menu, per rule 7. */
  destructive?: boolean;
}

export interface HeaderActionsProps {
  actions: HeaderAction[];
  /** How many stay visible. Order the array most-used first. */
  inline?: number;
  'data-testid'?: string;
}

export function HeaderActions({ actions, inline = 3, ...rest }: HeaderActionsProps) {
  const testId = rest['data-testid'] ?? 'Header';
  const visible = actions.slice(0, inline);
  const folded = actions.slice(inline);

  return (
    <div className="flex min-w-0 items-center gap-2" data-testid={rest['data-testid']}>
      {visible.map((action) => (
        <Button
          key={action.key}
          size="sm"
          variant="ghost"
          disabled={action.disabled}
          onClick={action.onSelect}
          data-testid={`${testId}.${action.key}`}
        >
          {action.icon}
          {action.label}
        </Button>
      ))}
      {folded.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" variant="ghost" aria-label="More actions" data-testid={`${testId}.More`}>
              <MoreHorizontal aria-hidden className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {folded.map((action) => (
              <DropdownMenuItem
                key={action.key}
                disabled={action.disabled}
                onSelect={action.onSelect}
                className={action.destructive ? 'text-[color:var(--color-failed)]' : undefined}
                data-testid={`${testId}.${action.key}`}
              >
                {action.label}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}
```

`components/ui/dropdown-menu.tsx` already exists (plan 007 T2 skinned it to the kit), so this adds no
primitive — T12 and T13 reuse the same one.

- [ ] **Step 4: Consume it on the case detail**

`CaseDetailPage.tsx:210` — replace the `flex flex-wrap items-center gap-2` wrapper and its five
children with one `<HeaderActions>`, ordering the array so the two least-used (Archive, Delete) fall
past `inline={3}` and marking Delete `destructive`. Keep every existing `data-testid` by using the
same suffix as the `key`, so no existing test has to change.

- [ ] **Step 5: Make the breadcrumb, not the actions, the thing that truncates**

`AppShell.tsx:985` — the `<header>` itself has no `flex-wrap`; inspect the portal target inside it,
remove `flex-wrap` if it carries one, and add `min-w-0 truncate` to the breadcrumb element so it
shortens before the action group does.

- [ ] **Step 6: Run the tests**

Run: `cd frontend && npx vitest run src/app/HeaderActions.test.tsx src/features/cases`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/HeaderActions.tsx frontend/src/app/HeaderActions.test.tsx \
        frontend/src/app/AppShell.tsx frontend/src/features/cases/CaseDetailPage.tsx
git commit -m "fix(shell): header actions collapse into a menu instead of wrapping through the header"
```

---

### T5 — The case list filter strip

**Deliverable:** `CaseFilterBar` renders as one row — search, a closed picker per facet, the applied
filters as removable chips, `Showing n of m`, `Clear filters` — and on an empty project it renders
no dead controls at all, only the empty state.
**Depends on:** T3 (`FilterStrip`)

Audit 1.1 (High), 1.2, 9.4, 9.5 (High). One task, because they are one component: the ~480px chrome
block, the second idiom, the missing hidden-count, and rule 4's "nothing configured, nothing shown"
are four consequences of `MULTI_FILTERS` (`CaseFilterBar.tsx:35-41`) being rendered as chip groups
over the whole vocabulary.

**The idiom already exists in the product twice** — `kit/MultiPicker` is the closed multi-select
with removable chips, and `CaseSearchPage` draws three quiet chips in a row (1.2). This task makes
`CaseFilterBar` the same shape and leaves `CaseSearchPage` alone except for its empty state.

**Files:**
- Modify: `frontend/src/features/cases/CaseFilterBar.tsx` (whole component; ~337 lines → ~180)
- Modify: `frontend/src/features/cases/CaseListPage.tsx` (the `total` / empty-state props it passes)
- Modify: `frontend/src/features/cases/CaseSearchPage.tsx` (empty state only — 9.4)
- Modify: `frontend/src/features/plans/PlanListPage.tsx` (empty state only — 9.4)
- Test: `frontend/src/features/cases/CaseFilterBar.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('shows no filter chips until a filter is applied', () => {
  renderBar({ filters: {} });
  expect(screen.queryByTestId(/Cases\.Filters\.Applied\./)).not.toBeInTheDocument();
  expect(screen.queryByText('Not linked to any spec')).not.toBeInTheDocument();
});

it('shows one removable chip per applied value, named facet and value', async () => {
  const setFilter = vi.fn();
  renderBar({ filters: { lifecycleStatus: ['APPROVED'] }, setFilter });
  await userEvent.click(screen.getByRole('button', { name: 'Remove Status · Approved' }));
  expect(setFilter).toHaveBeenCalledWith('lifecycleStatus', []);
});

it('renders nothing but the empty state on a project with no cases and no suites', () => {
  renderBar({ facets: [], dimensions: [], total: 0 });
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  expect(screen.getByTestId('Cases.Filters.Nothing')).toHaveTextContent(
    'Nothing to filter yet — this project has no cases.',
  );
});

it('counts what the filters hid', () => {
  renderBar({ filters: { lifecycleStatus: ['APPROVED'] }, total: 1284, shown: 0 });
  expect(screen.getByTestId('Cases.Empty')).toHaveTextContent('1 284 cases exist');
  expect(screen.getByTestId('Cases.Empty')).toHaveTextContent('1 filter is hiding all of them');
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/cases/CaseFilterBar.test.tsx`
Expected: FAIL on all four — the chip groups render every value, and there is no `.Nothing` node.

- [ ] **Step 3: Replace the chip groups with pickers plus applied chips**

Keep `MULTI_FILTERS` — it is the right catalogue — and change what it renders. Derive the applied
list from the filter state rather than from the vocabulary:

```tsx
  /**
   * The strip shows what the reader CHOSE, never what exists. Before this (audit 1.1) the four
   * vocabularies were rendered as toggle chip groups — Health alone is eleven chips over two rows,
   * wider than the table's own Health column — which is why rule 2's "filters in one row" could not
   * hold: the strip's height scaled with the catalogue instead of with the choice.
   */
  const applied: AppliedFilter[] = MULTI_FILTERS.flatMap(({ key, label, labels }) =>
    (filters[key] ?? []).map((value) => ({
      key: `${key}:${value}`,
      label: `${label} · ${labels[value] ?? value}`,
      onRemove: () => setFilter(key, (filters[key] ?? []).filter((v) => v !== value)),
    })),
  );
```

then render:

```tsx
  <FilterStrip
    data-testid={TestIdPrefix}
    search={/* the existing debounced search Input, unchanged */}
    pickers={
      <>
        {MULTI_FILTERS.map(({ key, label, labels }) => (
          <MultiPicker
            key={key}
            id={`filter-${key}`}
            options={Object.keys(labels)}
            selected={filters[key] ?? []}
            onChange={(next) => setFilter(key, next)}
            optionKey={(value) => value}
            optionLabel={(value) => labels[value]}
            placeholder={label}
            data-testid={`${TestIdPrefix}.${key}`}
          />
        ))}
        {/* Feature / Type / Level keep their existing SelectFilter, which is already a closed
            control — they were never the problem; the chip groups were. */}
      </>
    }
    applied={applied}
    summary={
      hasActiveFilters
        ? `Showing ${formatCount(shown)} of ${formatCount(total ?? 0)}`
        : plural(total ?? 0, 'case')
    }
    onClear={hasActiveFilters ? clearFilters : undefined}
  />
```

Delete the four `ChipGroup` blocks and the explanatory sentence beneath them. Move the tag input
into `pickers` as one `MultiPicker` with `onCreate` — that is the prop it exists for, and it removes
the bespoke Enter handling at `:205-220`.

- [ ] **Step 4: Apply rule 4 — nothing configured, nothing shown**

At the top of the component, before the strip:

```tsx
  /**
   * Rule 4, and the audit's finding 9.5. On PAY (0 cases, 0 suites) this screen rendered three
   * empty selects, four full chip groups, a tag input and a sentence describing the system's state
   * to a reader it left stuck — while the suites panel beside it got rule 4 exactly right. A filter
   * over an empty corpus is not a narrower view, it is furniture.
   */
  const nothingToFilter = total === 0 && (facets?.length ?? 0) === 0;
  if (nothingToFilter) {
    return (
      <p className="mb-3 text-[12.5px] text-ink-4" data-testid={`${TestIdPrefix}.Nothing`}>
        Nothing to filter yet — this project has no cases.
      </p>
    );
  }
```

- [ ] **Step 5: Count what the filters hid, on all three screens**

`StateBlock`'s `empty` arm already takes `hidden` and `filters` (`kit/StateBlock.tsx:36-38`) — it is
call sites that omit them. In `CaseListPage`, `CaseSearchPage` and `PlanListPage`, pass them to the
`DataRegion`'s `empty` prop:

```tsx
        empty={{
          kind: 'empty',
          title: 'No case matches',
          hidden: total ?? 0,
          filters: activeFilterCount,
          onClearFilters: clearFilters,
        }}
```

where `activeFilterCount` is the length of the `applied` array computed in Step 3 (export it from
`useCaseFilters` so all three screens read one definition rather than three).

- [ ] **Step 6: Run the tests**

Run: `cd frontend && npx vitest run src/features/cases src/features/plans/PlanListPage.test.tsx`
Expected: PASS. Existing tests that queried a chip group by its value label will fail here — fix
them to open the matching `MultiPicker`, and do not reintroduce the groups to keep them green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/cases frontend/src/features/plans/PlanListPage.tsx
git commit -m "fix(cases): the filter bar is a strip of applied filters, not the whole vocabulary"
```

---

### T6 — The case grid's columns

**Deliverable:** At 1680px the case grid's Title column measures ≥300px; Last result draws a
`kit/ResultBar` with `n/m` in mono; Health is a `Badge`; Priority uses neither `--color-failed` nor
`--color-blocked`; and the grid carries a `TableFoot` naming the count, the sort and its keys.
**Depends on:** T2 (the palette), T3 (`TableFoot`)

Audit 1.3 (High), 1.4, 1.5, 1.6, 1.7.

**The measurement, from the audit.** `CaseGrid.tsx:414` sets `min-w-[1240px]` while the declared
column widths sum to **1588px**. With `table-layout: auto`, the browser satisfies the floor and then
squeezes the one column allowed to wrap: Title measured **137px** against Suite 143px and Health
182px, wrapping titles to four and five lines. `meta.width` is a hint; a floor is what is needed.

**Files:**
- Modify: `frontend/src/features/cases/CaseGrid.tsx:130` (Title column def), `:189` (Last result),
  `:226` (Health), `:414` (`min-w`), and the return block for the foot
- Modify: `frontend/src/components/StatusChip.tsx:138` (`PriorityLabel`)
- Test: `frontend/src/features/cases/CaseGrid.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('gives the table a floor no narrower than its declared columns', () => {
  const { container } = renderGrid();
  const table = container.querySelector('table')!;
  const declared = [...table.querySelectorAll('thead th')].reduce(
    (sum, th) => sum + parseInt((th as HTMLElement).style.width || '0', 10),
    0,
  );
  const floor = parseInt(/min-w-\[(\d+)px\]/.exec(table.className)![1], 10);
  expect(floor).toBeGreaterThanOrEqual(declared);
});

it('draws the last result as a proportion bar, not a sentence', () => {
  renderGrid();
  expect(screen.getByTestId('Cases.Row.TC-1.LastResult')).toContainElement(
    screen.getByTestId('Cases.Row.TC-1.LastResult.Bar'),
  );
});

it('draws health as a badge, because the server owns it', () => {
  renderGrid();
  expect(screen.getByTestId('Cases.Row.TC-1.Health')).toHaveAttribute('data-kind', 'badge');
});

it('states the count, the sort and the keys under the table', () => {
  renderGrid();
  expect(screen.getByTestId('Cases.Foot')).toHaveTextContent('sorted by');
});
```

Add to `frontend/src/components/StatusChip.test.tsx`:

```tsx
it('spends no reserved result hue on priority', () => {
  const { container } = render(<PriorityLabel priority="CRITICAL" />);
  expect(container.innerHTML).not.toContain('--color-failed');
  expect(container.innerHTML).not.toContain('--color-blocked');
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/cases/CaseGrid.test.tsx src/components/StatusChip.test.tsx`
Expected: FAIL — floor 1240 < declared 1588; no `.Bar`; health renders a chip; no foot; CRITICAL
paints `--color-failed`.

- [ ] **Step 3: Raise the floor and give Title one**

`CaseGrid.tsx:414`:

```tsx
      {/*
        The floor is the SUM of the declared widths, not a round number under it. `meta.width` is a
        hint to `table-layout: auto`, and with 1588px of hints inside a 1240px floor the browser
        squeezed the one column allowed to wrap: audit 1.3 measured Title at 137px against Suite's
        143 and Health's 182, with titles wrapping to five lines. That is UX#113's essential-text
        rule failed by layout — the exact defect design language §9.5 predicted for this grid.
      */}
      <Table className="min-w-[1600px]">
```

`CaseGrid.tsx:130` — Title's column def gets a real floor in its cell, not only a `meta.width`:

```tsx
      cell: ({ row }) => (
        <div className="min-w-[300px] whitespace-normal">{/* existing title content */}</div>
      ),
```

- [ ] **Step 4: Use the conformant `ResultBar` in the Last result column**

`CaseGrid.tsx:189` — the product already owns the render's idiom; this column just did not call it:

```tsx
      cell: ({ row }) => {
        const last = row.original.lastResult;
        if (!last) return <span className="text-ink-4">{EM_DASH}</span>;
        return (
          <span
            className="flex items-center gap-2"
            data-testid={`Cases.Row.${row.original.key}.LastResult`}
          >
            <ResultBar
              className="h-1.5 w-14"
              passed={last.passed}
              failed={last.failed}
              blocked={last.blocked}
              skipped={last.skipped}
              untested={last.notRun}
              data-testid={`Cases.Row.${row.original.key}.LastResult.Bar`}
            />
            {/* §5: proportion before number. The sentence this replaces ("Passed 2m ago") put the
                number nowhere and the proportion nowhere. */}
            <Identifier variant="muted">
              {last.passed}/{last.passed + last.failed + last.blocked + last.skipped}
            </Identifier>
          </span>
        );
      },
```

If `lastResult` on the row type does not carry per-status counts, use what it does carry and keep
the bar — a two-segment bar is still the render's idiom; a status dot plus prose is not. Add the
missing counts to `openapi.yaml` in T19's follow-up list rather than inventing them here.

- [ ] **Step 5: Health is a badge; priority leaves the reserved hues**

`CaseGrid.tsx:226` — swap `Chip` for `Badge` and drop the click handler. §5: a badge is state the
system owns; a chip is a value the reader owns and is a button. Health is computed by the server.

`StatusChip.tsx:138` — `PriorityLabel` keeps its "no second saturated chip" argument, which is a
fair argument against a chip and not for reusing the result hues. Replace the two `var(--color-*)`
reads with weight alone, and record the reversal in the docblock:

```tsx
/**
 * Priority reads as text with a leading rule rather than as a coloured chip — in a grid where every
 * row also carries a result status, a second saturated chip per row turns the table into confetti.
 *
 * ~~CRITICAL takes `--color-failed` and HIGH `--color-blocked`.~~ → **superseded (audit 1.6).**
 * Rule 5 gives those two hues one meaning each, shared deliberately between Gherkin keywords and
 * results — and on this very row the Last result column is now drawing them. Two meanings for red
 * in one row is what rule 5 exists to prevent, and the render draws priority as plain text. Weight
 * carries the emphasis instead.
 */
```

- [ ] **Step 6: Add the foot**

In `CaseGrid`'s return, after `</Table>`:

```tsx
      <TableFoot
        data-testid="Cases.Foot"
        summary={`${plural(total, 'case')}${selectedCount > 0 ? ` · ${selectedCount} selected` : ''}`}
        sort={sortDescription}
        keys={['↑↓ row', 'Space select', 'Enter open']}
      />
```

`sortDescription` is derived from the table's own sorting state — `sorted by Updated, newest first`
— so the foot cannot drift from the order the rows are actually in. Delete the floating `10 cases`
line above the table that this replaces.

- [ ] **Step 7: Run the tests**

Run: `cd frontend && npx vitest run src/features/cases/CaseGrid.test.tsx src/components/StatusChip.test.tsx`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/cases/CaseGrid.tsx frontend/src/components/StatusChip.tsx \
        frontend/src/features/cases/CaseGrid.test.tsx frontend/src/components/StatusChip.test.tsx
git commit -m "fix(cases): title gets a floor, last result gets the bar, priority gives back the result hues"
```

---

### T7a — Index surfaces: the small, independent ones

**Deliverable:** `ArtifactTable`'s header row emits `<th>`; project cards carry three bars, a
`Current` badge and a `Show archived` switch, and no dangling noun; proposal tabs carry counts.
**Depends on:** T3 (`TableFoot`)

Audit 1.10 (High, accessibility), 1.12, 1.13.

**Split from T7b, 2026-08-30.** ~~Five index screens in one task: each is a small, independent edit
in its own file, none share a component, and splitting them would buy five context reloads for
nothing.~~ → **superseded.** The bundle came to roughly 600 changed lines, over the 500-line ceiling
this plan's tasks are held to. The three here total ~200 and share nothing but their family; T7b
carries the two that are real rewrites. Nothing else about either changes — same order, same
dependency, and they may still be executed back to back by one operator.

Steps 1–5 below are T7a. Steps 6–7 moved to T7b.

**Files:**
- Modify: `frontend/src/features/artifacts/ArtifactTable.tsx:59-66`
- Modify: `frontend/src/features/projects/ProjectsPage.tsx:136`, `:141`
- Modify: `frontend/src/features/proposals/ProposalsPage.tsx`
- Modify: `frontend/src/features/runs/RunSchedulePage.tsx:270`, `:308-325`
- Modify: `frontend/src/features/reports/ReportsPage.tsx:140-190`
- Create: `frontend/src/lib/cron.ts` + `frontend/src/lib/cron.test.ts`
- Test: each screen's existing `*.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
// ArtifactTable.test.tsx
it('gives its columns real headers', () => {
  const { container } = renderTable();
  expect(container.querySelectorAll('thead th')).toHaveLength(6);
  expect(container.querySelectorAll('thead td')).toHaveLength(0);
});

// ProjectsPage.test.tsx
it('names the count beside the noun', () => {
  renderProjects();
  expect(screen.getByTestId('Projects.Card.WEB.Meta')).toHaveTextContent('10 cases');
});
it('marks the project you are in', () => {
  renderProjects({ currentProjectId: 'web' });
  expect(screen.getByTestId('Projects.Card.WEB.Current')).toHaveTextContent('Current');
});

// ProposalsPage.test.tsx
it('carries a count on every tab, because it is a queue', () => {
  renderProposals({ counts: { waiting: 6, accepted: 124, rejected: 31 } });
  expect(screen.getByRole('tab', { name: /Waiting/ })).toHaveTextContent('6');
});

// cron.test.ts
it.each([
  ['0 30 2 * * *', 'Every day at 02:30'],
  ['0 0 3 * * MON-FRI', 'Every weekday at 03:00'],
  ['0 15 6 * * SUN', 'Every Sunday at 06:15'],
])('reads %s as %s', (expression, prose) => {
  expect(describeCron(expression, 'Europe/Sofia')).toBe(`${prose} · Europe/Sofia`);
});
it('falls back to the expression it cannot read, rather than lying about it', () => {
  expect(describeCron('0 0 0 L * *', 'UTC')).toBe('0 0 0 L * * · UTC');
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/artifacts src/features/projects src/features/proposals src/lib/cron.test.ts`
Expected: FAIL — six `td` where `th` is expected; `cases` with no number; no `Current`; bare tabs;
no `cron` module.

- [ ] **Step 3: `ArtifactTable` uses the header cell that exists**

`ArtifactTable.tsx:59-66` — replace all six `TableCell` inside `TableHead` with `TableHeaderCell`,
keeping the `style={{ width }}` values and dropping the `className="text-ink-2"` (the header cell
already styles itself, which is why this table rendered in `IBM Plex Sans` sentence case against
mono uppercase everywhere else). Screen-reader users currently get no column headers at all.

- [ ] **Step 4: `ProjectsPage` — the dangling noun and the two missing signals**

`ProjectsPage.tsx:141` — `pluralNoun` returns only the noun (`lib/format.ts:240`, which is correct
and documented); the line was written expecting the count. With `myRole` null for a Company Admin
all that survived was the word `cases`. Use `plural`, which is the same inflection with the number:

```tsx
                <span
                  className="mt-2 flex flex-wrap gap-4 text-[11.5px] text-ink-4"
                  data-testid={`Projects.Card.${project.key}.Meta`}
                >
                  <span>{plural(project.caseCount, 'case')}</span>
```

`ProjectsPage.tsx:136` — the render's picker gives each card three comparable bars. Add the two the
build omits, from the fields the projects endpoint already returns:

```tsx
                <HBar label="Cases" fraction={project.caseCount / largest} value={formatCount(project.caseCount)} />
                <HBar label="Automated" fraction={project.automatedFraction} value={formatPercent(project.automatedFraction)} />
                <HBar label="Last run" fraction={project.lastRunPassRate ?? 0} value={project.lastRunAt ? formatRelative(project.lastRunAt) : EM_DASH} />
```

If either field is absent from the contract, add it to `docs/specs/openapi.yaml`, regenerate **both**
copies (`cd frontend && npm run gen:api`; `cd packages/client && npm run gen:api`) and serve it from
the backend — that is a contract change and belongs in this commit, per the working agreement.

Add the `Current` badge beside the project name and a `Show archived` switch above the grid, both
drawn in the render.

- [ ] **Step 5: `ProposalsPage` — counts on the tabs**

Render each tab label as `{label} {count}` from the same query that fills the list, so a count and
its rows cannot disagree. The default tab is already correct; do not change it.

- [ ] **Step 6: Run T7a's tests and commit**

Run: `cd frontend && npx vitest run src/features/artifacts src/features/projects src/features/proposals`
Expected: PASS.

```bash
git add frontend/src/features/artifacts frontend/src/features/projects frontend/src/features/proposals
git commit -m "fix(indexes): real table headers, three project bars, tab counts"
```

---

### T7b — Index surfaces: Reports and run schedules

**Deliverable:** Reports draws the shared jobs table beside CI ingestion; run schedules lead with a
per-row switch and a humanised cadence and offer no Delete.
**Depends on:** T3 (`TableFoot`)

Audit 1.8, 1.9. Split from T7a on size — see the note there. Both are structural rewrites of a
panel, and both are ~200 changed lines on their own.

**Files:**
- Modify: `frontend/src/features/runs/RunSchedulePage.tsx:79`, `:270`, `:308-325`
- Modify: `frontend/src/features/reports/ReportsPage.tsx:140-190`
- Create: `frontend/src/lib/cron.ts` + `frontend/src/lib/cron.test.ts`
- Test: `frontend/src/features/runs/RunSchedulePage.test.tsx`, `frontend/src/features/reports/ReportsPage.test.tsx`

- [ ] **Step 1: Write the failing `cron` tests** — the `cron.test.ts` block given under T7a Step 1.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run src/lib/cron.test.ts`
Expected: FAIL — no `cron` module.

- [ ] **Step 3: `RunSchedulePage` — the switch is the first column, and Delete goes**

Create `frontend/src/lib/cron.ts`:

```ts
/**
 * A cron expression, read out loud.
 *
 * Render G writes `Every day at 01:00 · Europe/Sofia`; the build printed the raw `0 30 2 * * *`
 * (audit 1.9). A schedule's cadence is the one thing every reader of that table needs and the one
 * thing a six-field expression does not say.
 *
 * It reads the common shapes and **falls back to the expression itself** for anything else. A
 * humaniser that guesses at `L` or `#` would state a cadence the scheduler does not keep, which is
 * worse than the raw string it replaces.
 */
export function describeCron(expression: string, timezone: string): string {
  const parts = expression.trim().split(/\s+/);
  const suffix = ` · ${timezone}`;
  if (parts.length !== 6) return expression + suffix;
  const [second, minute, hour, dayOfMonth, month, dayOfWeek] = parts;
  const simple = /^\d+$/;
  if (second !== '0' || !simple.test(minute) || !simple.test(hour)) return expression + suffix;
  if (dayOfMonth !== '*' || month !== '*') return expression + suffix;
  const at = `${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`;
  const days: Record<string, string> = {
    '*': 'Every day',
    'MON-FRI': 'Every weekday',
    SUN: 'Every Sunday',
    MON: 'Every Monday',
    TUE: 'Every Tuesday',
    WED: 'Every Wednesday',
    THU: 'Every Thursday',
    FRI: 'Every Friday',
    SAT: 'Every Saturday',
  };
  const cadence = days[dayOfWeek];
  return cadence ? `${cadence} at ${at}${suffix}` : expression + suffix;
}
```

`RunSchedulePage.tsx:270` — replace `<Identifier variant="muted">{schedule.cronExpression}</Identifier>`
with `describeCron(schedule.cronExpression, schedule.timezone)`, keeping the raw expression as the
element's `title` so it stays recoverable.

Add an **Active** column as the row's first cell — a `Switch` calling the existing update mutation.
`RunSchedulePage.tsx:79` currently states the opposite decision (*"the row's enabled flag is an
Edit-dialog field, and promoting it is deliberately not done here"*). Rewrite that comment as a
struck-through reversal citing render G's caption — *"the only index whose rows have an on/off, so
the switch is the first column"* — rather than deleting it.

`RunSchedulePage.tsx:323` — remove the per-row **Delete** button and its `useDeleteRunSchedule`
call. Rule 7's worked example is a paused schedule keeping its row at reduced weight with the date
and the reason; a Delete on the row contradicts it. Deletion stays available inside the Edit dialog.

- [ ] **Step 4: `ReportsPage` — the deliberate pairing with CI ingestion**

`ReportsPage.tsx:140-190` — the jobs panel becomes the same `Table` shape CI ingestion already uses:
`Report · Kind · State · Size · Generated · Download`, with a `TableFoot`. `DefinitionTable`
(`:283`) already is a table and stays. Render F draws these as *"two tables about jobs, drawn the
same way on purpose"*; the pairing is the point, so match the column set exactly.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npx vitest run src/features/runs/RunSchedulePage.test.tsx src/features/reports src/lib/cron.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/runs/RunSchedulePage.tsx frontend/src/features/reports \
        frontend/src/lib/cron.ts frontend/src/lib/cron.test.ts
git commit -m "fix(indexes): a schedule switch with a cadence you can read, Reports as the shared table"
```

---

### T8 — The record screens

**Deliverable:** The run summary legend names all five statuses including `skipped`; the three rails
that clipped now truncate; and Reliability, Version history, Attachments and Issues lose the card
that rule 1 forbids.
**Depends on:** T3 (`RailRow`)

Audit 2.2 (High), 2.3 (the call sites; the component was fixed in T3), 2.4.

**2.2 is the "two contradictory numbers on one screen" class** CLAUDE.md's working agreement warns
about, and the data proves it is a defect rather than a decision: on TR-1 the bar's own `aria-label`
reads `6 passed, 2 failed, 1 blocked, 1 skipped of 10` and draws four segments, while the legend
under it prints nine of ten and ends `0 not run` — which invites the reader to conclude the list is
complete. `PlanStatisticsPage` gets it right on the same data.

**Files:**
- Modify: `frontend/src/features/runs/RunDetailPage.tsx:406-411`
- Modify: `frontend/src/features/runs/RunListPage.tsx:199` (same omission)
- Modify: `frontend/src/features/cases/CaseDetailPage.tsx` (Reliability, Version history,
  Attachments, Issues — drop the `Panel` wrapper)
- Test: `frontend/src/features/runs/RunDetailPage.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
it('names every segment the bar draws', () => {
  renderRunDetail({ counts: { passed: 6, failed: 2, blocked: 1, skipped: 1, notRun: 0 } });
  const legend = screen.getByTestId('RunDetail.ResultBar');
  const named = ['passed', 'failed', 'blocked', 'skipped', 'not run'].filter((word) =>
    legend.textContent!.includes(word),
  );
  expect(named).toHaveLength(5);
  // The legend and the bar's own accessible name must agree on the total.
  const sum = [...legend.textContent!.matchAll(/(\d+)\s+(?:passed|failed|blocked|skipped|not run)/g)]
    .reduce((total, match) => total + Number(match[1]), 0);
  expect(sum).toBe(10);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run src/features/runs/RunDetailPage.test.tsx`
Expected: FAIL — `expected 4 to be 5`, then `expected 9 to be 10`.

- [ ] **Step 3: Add the missing entry, on both screens**

`RunDetailPage.tsx:406-411` — insert between blocked and not run:

```tsx
            <span style={{ color: 'var(--color-skipped)' }}>
              {formatCount(data.counts.skipped)} skipped
            </span>
```

Apply the identical insertion at `RunListPage.tsx:199`. Add a one-line comment above the legend
naming the invariant, so the next person adding a status sees it:

```tsx
          {/* One entry per segment the bar can draw, in the bar's own fill order. A legend shorter
              than the bar is audit 2.2: four segments drawn, three named, and `0 not run` reading
              as "that is all of them". */}
```

- [ ] **Step 4: Uncard four blocks on the case detail**

`CaseDetailPage.tsx` — Reliability and Version history in the rail are bordered cards while
`SUMMARY`/`LAST RESULT`/`AUTOMATION`/`CLASSIFICATION` above them are quiet `kv` sections; in the
document column Attachments and Issues are carded while Preconditions, Steps, Postconditions and
Traceability are not. Rule 1's "no card wrapping" applied to four of six blocks is not a rule.
Remove the `Panel`/border wrapper from all four, giving each the same section heading treatment its
neighbours use.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npx vitest run src/features/runs src/features/cases/CaseDetailPage.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/runs/RunDetailPage.tsx frontend/src/features/runs/RunListPage.tsx \
        frontend/src/features/cases/CaseDetailPage.tsx frontend/src/features/runs/RunDetailPage.test.tsx
git commit -m "fix(runs): the legend names every segment the bar draws"
```

---

### T9 — The report preview is a light surface

**Deliverable:** `ReportViewPage`'s preview renders under `data-color-scheme="light"` regardless of
the app's scheme, and its scope prose reads at 70ch inside the region instead of forcing a 1639px
table into a 1366px column.
**Depends on:** none

Audit 2.5. Design language §9.1 opens with *"The report preview is the one light surface in a dark
product"* and gives the reason: a PDF preview drawn dark misrepresents the file being downloaded.
§9 lists it as an open problem rather than a rule, which is why the build's gap reads deliberate —
but the stated reason still holds, so this task implements it rather than amending it away.

**Files:**
- Modify: `frontend/src/features/reports/ReportViewPage.tsx`
- Test: `frontend/src/features/reports/ReportViewPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('draws the preview on the light surface whatever the app scheme is', () => {
  renderReportView({ scheme: 'dark' });
  expect(screen.getByTestId('ReportView.Preview')).toHaveAttribute('data-color-scheme', 'light');
});

it('sets the scope prose at a reading measure', () => {
  renderReportView();
  expect(screen.getByTestId('ReportView.Scope')).toHaveClass('max-w-[70ch]');
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/reports/ReportViewPage.test.tsx`
Expected: FAIL — no `data-color-scheme` on the preview; the scope node is a key/value table.

- [ ] **Step 3: Pin the preview to light**

`app/colorScheme.tsx` is the one thing that writes `data-color-scheme` on the root (CLAUDE.md), and
this does not change that — it stamps the attribute on **this subtree**, which is what `@theme
inline` makes work: the utilities emit `var(--…)`, so a nested scope override moves every one of
them.

```tsx
        {/*
          Design language §9.1: the report preview is the one light surface in a dark product,
          because it is a picture of a PDF and a PDF drawn dark misrepresents the file the reader is
          about to download. The attribute is stamped here rather than on the root — `colorScheme`
          still owns the app's choice; this is a local, deliberate exception and the only one.
        */}
        <div data-color-scheme="light" data-testid="ReportView.Preview" className="bg-paper text-ink">
```

- [ ] **Step 4: Set the prose at 70ch**

Replace the key/value table carrying the scope prose with a definition list whose value column is
`max-w-[70ch]`, and give the surrounding region `overflow-x-auto` so nothing else can force the page
body to scroll horizontally.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npx vitest run src/features/reports/ReportViewPage.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/reports/ReportViewPage.tsx frontend/src/features/reports/ReportViewPage.test.tsx
git commit -m "feat(reports): the preview is the one light surface, and its prose reads at 70ch"
```

---

### T10 — A case cannot be recorded Failed without saying what happened

**Deliverable:** Pressing **Failed** (or `2`) on the runner with no step marked Failed opens the
failure panel, marks the current step failed, and refuses to record until a description is typed —
so `0 of 3 steps failed · 3 not marked` with no reason and no defect is unreachable.
**Depends on:** none

Audit 3.3, High — and **the audit's diagnosis is corrected here.** The finding reads *"Recording a
failure has no failure panel"*. The panel exists: `ExecutionPage.tsx:1122` renders it, `:1015` gives
it the render's `Failed at step N` title, `:1127` shows the refusal inline, and `:825` blocks the
submit. What is wrong is one predicate — `failureNeedsDescription` (`:672`) is
`failedStep !== null && failureDescription.trim() === ''`, and `failedStep` (`:664-667`) is the first
step whose *step* verdict is Failed. With no step marked, `failedStep` is `null`,
`failureNeedsDescription` is `false`, the disabled guard at `:1084` passes, and the case-level
verdict records straight through with a toast and a navigation away.

So this is a small, precise fix in a screen that already knows how to refuse — which is a much
better position than the audit's write-up implies, and worth stating so nobody rebuilds render B.

**The rule:** a **case** verdict of FAILED requires a failure description, whether or not any step
carries one. Where no step is marked, the panel adopts the current step (`currentStepIndex`,
`:657`), because "which step" is information the record needs and the tester is standing on it.

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx:664-673`, `:823-833`, `:1015-1017`, `:1084`
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('refuses a case-level Failed with nothing said about it', async () => {
  const { submit } = renderRunner({ steps: 3, marked: 0 });
  await userEvent.click(screen.getByRole('button', { name: 'Failed' }));
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByTestId('Execution.Failure')).toHaveTextContent('Failed at step 1');
  expect(screen.getByTestId('Execution.Failure.Description')).toBeRequired();
});

it('records once the description is there', async () => {
  const { submit } = renderRunner({ steps: 3, marked: 0 });
  await userEvent.click(screen.getByRole('button', { name: 'Failed' }));
  await userEvent.type(
    screen.getByTestId('Execution.Failure.Description'),
    'The price filter reset when the sort changed.',
  );
  await userEvent.click(screen.getByRole('button', { name: 'Failed' }));
  expect(submit).toHaveBeenCalledOnce();
});

it('leaves the other three verdicts one keystroke away', async () => {
  const { submit } = renderRunner({ steps: 3, marked: 0 });
  await userEvent.click(screen.getByRole('button', { name: 'Passed' }));
  expect(submit).toHaveBeenCalledOnce();
});
```

- [ ] **Step 2: Run them and watch the first two fail**

Run: `cd frontend && npx vitest run src/features/runs/ExecutionPage.test.tsx`
Expected: FAIL — the first records immediately (`submit` called once, no panel); the second calls
`submit` twice. The third PASSES already and is here to pin that this task does not slow the
happy path — Blocked keeps its checklist, Passed and Skipped stay one keystroke.

- [ ] **Step 3: Make the gate the case verdict, not the step**

`ExecutionPage.tsx:664-673` — add the pending case verdict to the state and widen the predicate:

```tsx
  /*
    The failure panel's subject. Two ways to reach it, and both must:

      1. a STEP was marked Failed — the original path; `failedStep` is that step;
      2. the CASE is being recorded Failed with no step marked — audit 3.3. Before this, `failedStep`
         was null on that path, so `failureNeedsDescription` was false, the disabled guard passed,
         and one click produced "TC-4 · Failed · 0 of 3 steps failed · 3 not marked" with no reason
         and no defect. That is precisely the record the render's refusal was designed to prevent,
         and it was reachable in one click.

    In case 2 the panel adopts the step the tester is standing on, because "which step" is part of
    the record and `currentStepIndex` already knows it.
  */
  const [pendingFailure, setPendingFailure] = useState(false);

  const markedFailedIndex = steps.findIndex(
    (step) => kindOfStatusId(stepResults[step.stepId ?? ''] ?? null) === 'FAILED',
  );
  const failedStepIndex =
    markedFailedIndex >= 0 ? markedFailedIndex : pendingFailure ? Math.max(currentStepIndex, 0) : -1;
  const failedStep = failedStepIndex >= 0 ? steps[failedStepIndex] : null;
  const failureDescription = failedStep ? (stepComments[failedStep.stepId ?? ''] ?? '') : '';
  const failureNeedsDescription = failedStep !== null && failureDescription.trim() === '';
```

Move `currentStepIndex`'s declaration (`:657`) above this block if it is not already — the order
matters and TypeScript will say so.

- [ ] **Step 4: Open the panel instead of recording**

`ExecutionPage.tsx:823-833` — before the existing `failureNeedsDescription` guard:

```tsx
    if (action.verdict === 'FAILED' && !pendingFailure && markedFailedIndex < 0) {
      // First press opens the panel and adopts the current step; the second, once a description is
      // there, records. Same two-press shape Blocked has had since the checklist.
      setPendingFailure(true);
      announce('Failed. Say what actually happened.');
      return;
    }
    if (failureNeedsDescription) {
      announce(failureRefusal);
      return;
    }
```

Clear `pendingFailure` wherever the screen moves to another case (`:742`'s navigation helper) so the
panel does not follow the tester to the next case.

- [ ] **Step 5: Mark the adopted step as failed on submit**

In `record` (`:602`), when `pendingFailure` is set, include the adopted step in `stepResults` so the
saved record is internally consistent — a case marked Failed with zero failed steps is the artefact
this task exists to remove, and leaving `pendingFailure` out of the payload would keep producing it:

```tsx
        stepResults: Object.entries({
          ...stepResults,
          ...(pendingFailure && failedStep
            ? { [failedStep.stepId ?? '']: byKind('FAILED')?.id ?? null }
            : {}),
        })
```

- [ ] **Step 6: Mark the description required**

`ExecutionPage.tsx:1127` — the `error` prop is already wired; add `required` and a `data-testid` of
`Execution.Failure.Description` to the field, and `data-testid="Execution.Failure"` to the panel at
`:1122`, so the tests above have something to assert on.

- [ ] **Step 7: Run the tests**

Run: `cd frontend && npx vitest run src/features/runs/ExecutionPage.test.tsx`
Expected: PASS, including the third case unchanged.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "fix(runner): a case Failed with no failed step now opens the failure panel and is refused"
```

---

### T11 — The runner holds together at 1280

**Deliverable:** At 1280px the runner's top strip does not wrap and its procedure column stays at
least 60ch; the queue shows a per-case status dot and duration under a run-wide result bar; and the
top strip carries elapsed time and **Pause run**.
**Depends on:** T3 (`TableFoot` idiom, `RailRow`), T4 (`HeaderActions`)

Audit 3.2 (High, §9.5's third named risk confirmed) and 3.4.

**The measurement.** §3's 74ch measure *is* implemented (`max-w-[74ch]`, verified on the element)
and is exact at 1680px, where the procedure column is 666px = 74ch. At 1280px it is 360px = **40ch**,
because `max-width` is a cap and not a floor and the three-pane grid squeezes past it. Below roughly
1500px the runner stops being the 74ch document surface that §4 uses to justify collapsing the rail
there in the first place.

**The fix is a floor plus a fallback**, not a new breakpoint: give the procedure column a
`min-w-[60ch]` and let the three-pane grid drop to two panes when it cannot honour it. The app uses
`sm`/`md`/`lg`/`xl` and no `2xl`, so 1280 and 1680 share a breakpoint state — a media query would
not distinguish them and a container query is what actually describes the constraint.

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx` — the workspace grid, the top strip, the queue
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('gives the procedure a floor, not only a cap', () => {
  renderRunner({ layout: 'split' });
  expect(screen.getByTestId('Execution.Procedure')).toHaveClass('min-w-[60ch]');
});

it('says what has been recorded, per case, in the queue', () => {
  renderRunner({ queue: [{ key: 'TC-188', title: 'Refund a settled payment', verdict: 'PASSED', durationMs: 184_000 }] });
  const row = screen.getByTestId('Execution.Queue.TC-188');
  expect(row).toHaveTextContent('3m 04s');
  expect(row.querySelector('[data-status="PASSED"]')).toBeInTheDocument();
});

it('carries elapsed time and a way to stop', () => {
  renderRunner();
  expect(screen.getByTestId('Execution.Elapsed')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Pause run' })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/runs/ExecutionPage.test.tsx`
Expected: FAIL on all three.

- [ ] **Step 3: Floor the procedure column and let the grid fall back**

On the workspace grid, express the constraint as a container query so it responds to the *pane*
rather than to the viewport:

```tsx
      {/*
        §3's 74ch measure is a CAP. At 1680 the three-pane split lands on it exactly; at 1280 the
        grid squeezed the procedure to 40ch (audit 3.2), which is where the runner stops being the
        document surface §4 collapses the rail for. A floor plus a fallback: when three panes cannot
        give the procedure 60ch, it drops to two and the evidence pane moves under the rail.
      */}
      <div className="@container grid gap-4 @[1500px]:grid-cols-[minmax(0,1fr)_320px_280px] @[0px]:grid-cols-[minmax(0,1fr)_320px]">
        <article data-testid="Execution.Procedure" className="min-w-[60ch] max-w-[74ch]">
```

- [ ] **Step 4: Stop the top strip wrapping through the header**

The strip has the same mechanism as 2.1 — `flex-wrap` inside `h-[52px]`, which at 1280 clipped the
`Case 5 of 10 · TC-5 v1` indicator off the *top* of the viewport and spilled the Focus/Split/Grid
control below the header's border. Put the layout control and the run actions through
`HeaderActions` from T4 with `inline={2}`, drop `flex-wrap`, and give the case indicator `min-w-0
truncate` so it shortens rather than pushing.

- [ ] **Step 5: Give the queue what it is for**

The render's left rail carries a result bar for the whole run, and per case a status dot, the key,
the title and the duration (`● TC-188 Refund a settled… 3m 04s`). The build lists titles and keys
only, so the queue does not say what has been recorded — which is the one question a tester has
about it. Add a `ResultBar` at the head of the queue from the run's counts, and per row a dot
carrying `data-status`, plus `formatDuration(durationMs)` right-aligned.

- [ ] **Step 6: Elapsed time and Pause run**

Add an `Execution.Elapsed` counter to the top strip driven by the run's `startedAt`, and a
**Pause run** action through the existing run-status mutation. Leave the *duration* field as it is:
the render shows a live timer (*"Counting since you opened the case"*), the build shows an empty box
captioned *"Optional. Leave it empty if you did not time this run."*, and the build's version is the
honest one — a timer that starts when a tab opens measures the tab, not the test. Record that
divergence in T17's spec amendments rather than implementing the render.

- [ ] **Step 7: Add the preconditions banner**

`CaseDetailPage` has the inherited-precondition banner and the runner does not (audit 3.4). Reuse
the same component; a tester who cannot see the preconditions is being asked to judge a result
without them.

- [ ] **Step 8: Run the tests**

Run: `cd frontend && npx vitest run src/features/runs/ExecutionPage.test.tsx`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "fix(runner): a 60ch floor with a two-pane fallback, a queue that says what was recorded"
```

---

### T12 — The board card at 1280

**Deliverable:** At 1280px a one-line board task renders in a card no taller than 200px, its badges
do not wrap to their own row, its move controls sit behind one menu, and no metadata line ends in a
dangling `·`.
**Depends on:** none

Audit 4.1 — §9.5's second named risk, confirmed as degradation rather than breakage. Five columns
still fit with no horizontal scroll; what comes apart is the card. The four move-buttons stack three
deep and a one-line task becomes a ~290px card.

**The four move-buttons stay reachable, in a menu.** Audit 4.2 records the board as the product's
only conformant answer to WCAG 2.2's drag rule, and the explicit `→ To do` / `→ In progress` /
`→ Blocked` / `→ In review` / `→ Done` buttons are that answer. Folding them into one **Move to…**
menu keeps every destination keyboard-reachable — which is what 2.2 requires — while costing one
row instead of three. Do not remove them.

**Files:**
- Modify: `frontend/src/features/board/BoardPage.tsx` (the card)
- Modify: `frontend/src/lib/format.ts` (a `joinMeta` helper, if none exists)
- Test: `frontend/src/features/board/BoardPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('never ends a metadata line in a separator', () => {
  renderBoard({ tasks: [{ key: 'T-1', assignee: 'Marco Ferreira', dueDate: null }] });
  expect(screen.getByTestId('Board.Card.T-1.Meta').textContent).not.toMatch(/·\s*$/);
});

it('offers every destination through one control', async () => {
  renderBoard({ tasks: [{ key: 'T-1', state: 'TODO' }] });
  expect(screen.queryByRole('button', { name: '→ Done' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Move T-1' }));
  expect(await screen.findByRole('menuitem', { name: 'Done' })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/work/BoardPage.test.tsx`
Expected: FAIL — the metadata ends `Marco Ferreira ·`; four `→` buttons render.

- [ ] **Step 3: Build the metadata line from what exists**

The dangling `·` is a separator emitted between a present value and an absent one. Join instead of
concatenating:

```ts
/**
 * Joins the parts of a metadata line, dropping the empty ones — so a card with an assignee and no
 * due date reads `Aiko Tanaka` and not `Aiko Tanaka ·`. Audit 4.1 found both spellings of the bug
 * on the board at 1280px, where the line wraps after the separator and the orphan is on its own.
 */
export function joinMeta(...parts: (string | null | undefined)[]): string {
  return parts.filter((part) => part != null && part !== '').join(' · ');
}
```

- [ ] **Step 4: Fold the move controls into one menu**

Replace the five `→` buttons with a single `Move T-<key>` `DropdownMenu` (the primitive T4 added)
whose items are the four destinations other than the card's own column. Keep the drag handle. Keep
each destination reachable by keyboard — that is the WCAG 2.2 property, and a menu satisfies it.

- [ ] **Step 5: Keep the badges on the title's row**

Give the title `min-w-0 truncate` and put the badges in a `shrink-0` group beside it, so a long
title shortens instead of pushing the badges onto a row of their own.

- [ ] **Step 6: Run the tests**

Run: `cd frontend && npx vitest run src/features/board src/lib/format.test.ts`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/board frontend/src/lib/format.ts frontend/src/lib/format.test.ts
git commit -m "fix(board): one move menu, no dangling separator, badges stay on the title's row"
```

---

### T13 — Authoring: gutter not toolbar, three columns, chrome outside the column

**Deliverable:** The doc editor has a gutter and no per-block toolbar; feature import lays out as
three columns at `lg` and up; and documentation's Edit/Unpublish/Archive/Delete sit in the strip
above the title rather than between the title and the first block.
**Depends on:** none

Audit 5.1, 5.2, 5.3.

**5.1 is the render's decision by name.** Render B's caption: *"blocks get a **gutter, not a toolbar
each** — the case editor's move, applied to a block model."* The build has the gutter (drag handle,
`H`/`P` type letter, `DocPageEditorPage.tsx:384`) **and** a full toolbar under every block: a type
select (`:458`), a level select (`:488`), up (`:515`), down (`:525`), delete (`:541`). On a
five-block page that is five toolbars — the thing the render ruled out.

**Files:**
- Modify: `frontend/src/features/docs/DocPageEditorPage.tsx:380-545`
- Modify: `frontend/src/features/cases/FeatureImportPage.tsx`
- Modify: `frontend/src/features/docs/DocPageViewPage.tsx` (the chrome placement)
- Test: `frontend/src/features/docs/DocPageEditorPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('gives the page one toolbar, not one per block', () => {
  renderEditor({ blocks: 5 });
  expect(screen.queryAllByTestId(/DocEditor\.Block\.\d+\.Toolbar/)).toHaveLength(0);
});

it('keeps every block action reachable from the gutter', async () => {
  renderEditor({ blocks: 5 });
  await userEvent.click(screen.getByRole('button', { name: 'Block 2 actions' }));
  for (const action of ['Heading', 'Paragraph', 'Move up', 'Move down', 'Delete']) {
    expect(await screen.findByRole('menuitem', { name: action })).toBeInTheDocument();
  }
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/docs/DocPageEditorPage.test.tsx`
Expected: FAIL — five toolbars; no gutter menu.

- [ ] **Step 3: Move the five controls into the gutter**

Keep the drag handle and the type letter at `:384`. Add a `Block N actions` `DropdownMenu` beside
them holding the type choices, the level choices, Move up, Move down and Delete — every action the
toolbar offered, none removed. Delete the toolbar row at `:450-545`. Add the reason above the
gutter, in the file:

```tsx
                    {/*
                      Gutter, not a toolbar each — render B's caption states it as a decision, and
                      the reason is the same one the case editor's rail settled: a control that
                      repeats per block multiplies chrome by content. Five blocks meant five
                      toolbars (audit 5.1). Every action the toolbar carried is still here; it is
                      one press away rather than permanently on screen.
                    */}
```

- [ ] **Step 4: Feature import is three columns**

`FeatureImportPage.tsx` — render C: *"files left, what will happen centre, what it costs right."*
The build stacks them. The load-bearing principles are already right (`Nothing is written until you
import`, Preview before Import, the path stored as provenance) and none of them change: this is
`grid-cols-1 lg:grid-cols-[280px_minmax(0,1fr)_260px]` on the existing three sections.

- [ ] **Step 5: Documentation's chrome leaves the reading column**

`Edit` / `Unpublish` / `Archive` / `Delete` currently sit between the page title and the first block,
inside the document surface. Rule 2: chrome is a strip above and a strip below. Move them into the
header slot with `HeaderActions` (T4). Leave the tested/not-tested signal exactly where it is —
*"Not tested · no case links to this paragraph · Write one"* is the screen's best line and it
belongs beside the paragraph it is about.

- [ ] **Step 6: Run the tests**

Run: `cd frontend && npx vitest run src/features/docs src/features/cases/FeatureImportPage.test.tsx`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/docs frontend/src/features/cases/FeatureImportPage.tsx
git commit -m "fix(authoring): a gutter menu per block, three import columns, chrome out of the reading column"
```

---

### T14 — A refusal names a person

**Deliverable:** `GET /permissions/{permission}/grantees` exists in the contract, both generated
copies and the backend, and `ApiTokensPage`'s refusal reads *"Issuing API tokens needs `token:issue`,
which you do not hold. Ask Dana Whitfield to grant it."* for a Viewer in Northwind.
**Depends on:** none

Audit 7.1, High. Rule 7 and §7 state the requirement twice — *"the permission's real id **and the
name of somebody in this company who holds it**"*. The build meets four of five requirements
(neutral colour, no retry, the real permission id in mono, the action disabled rather than hidden)
and stops one clause short: *"A company administrator"* is a role, not a name.

**`kit/StateBlock` already takes `grantee`** (`StateBlock.tsx:74`, `:186-187`) and its docblock at
`:58-67` explains why it is optional — *"pass `grantee` where a name is genuinely known"*. Today no
name is knowable to a refused caller, and that is the actual gap: `/org-chart` is the only endpoint
that knows who holds what, and it needs an admin permission the refused user by definition lacks.

**So this task is mostly backend.** One narrow read endpoint, readable by any authenticated company
member, answering exactly one question: *who in my company can grant this permission?* It returns at
most three display names and nothing else — no emails, no ids, no roles — because a refusal needs a
name to ask and anything more turns a permission error into a directory of the company.

**Files:**
- Modify: `docs/specs/openapi.yaml` (new path + `GranteeList` schema, **with its `required` array**)
- Create: `backend/src/main/java/com/tcms/identity/GranteeLookupService.java`
- Create: `backend/src/main/java/com/tcms/api/GranteeController.java`
- Create: `backend/src/test/java/com/tcms/identity/GranteeLookupServiceTest.java`
- Modify: `frontend/src/api/queries/admin.ts` (a `useGrantees(permission)` hook), `keys.ts`
- Modify: `frontend/src/features/admin/ApiTokensPage.tsx`
- Regenerate: `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.ts`

- [ ] **Step 1: Write the contract**

In `docs/specs/openapi.yaml`:

```yaml
  /permissions/{permission}/grantees:
    get:
      operationId: listPermissionGrantees
      summary: Who in this company can grant this permission
      description: >
        Readable by any authenticated member of the company, deliberately: it is what a REFUSAL
        renders, and a refused caller by definition lacks the permission that would let them read
        the org chart. It answers one question and returns display names only — no email, no id, no
        role — so it cannot be walked into a directory of the company.
      parameters:
        - name: permission
          in: path
          required: true
          schema: { type: string, example: token:issue }
      responses:
        '200':
          description: At most three names, most-reachable first.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/GranteeList'
```

and under `components.schemas` — note the `required` array, per the working agreement:

```yaml
    GranteeList:
      type: object
      required: [permission, grantees]
      properties:
        permission: { type: string }
        grantees:
          type: array
          items:
            type: object
            required: [displayName]
            properties:
              displayName: { type: string }
```

- [ ] **Step 2: Write the failing backend test**

```java
package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal has to name somebody, and the caller reading it holds no admin permission — that is
 * what makes it a refusal. So this lookup is readable by any company member and answers only with
 * display names. The isolation property is the one that matters: it must never name a person from
 * another company, because a permission error would then leak the shape of the instance.
 */
class GranteeLookupServiceTest extends AbstractCompanyScopedTest {

    @Test
    void namesOnlyPeopleInTheCallersOwnCompany() {
        var northwind = seedCompanyWithAdmin("northwind", "Dana Whitfield");
        seedCompanyWithAdmin("contoso", "Someone Else");

        var names = inCompany(northwind, () -> service.grantees("token:issue"));

        assertThat(names).extracting("displayName").containsExactly("Dana Whitfield");
    }

    @Test
    void answersEmptyRatherThanGuessingWhenNobodyHoldsIt() {
        var company = seedCompanyWithAdmin("northwind", "Dana Whitfield");
        assertThat(inCompany(company, () -> service.grantees("permission:that:nobody:holds"))).isEmpty();
    }
}
```

Follow the existing tenancy test base in `backend/src/test/java/com/tcms/tenancy/` for
`AbstractCompanyScopedTest`'s real name and helpers.

- [ ] **Step 3: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.identity.GranteeLookupServiceTest'`
Expected: FAIL — `GranteeLookupService` does not exist.

- [ ] **Step 4: Implement the service and controller**

`GranteeLookupService` derives holders from the permission catalogue and the company's grants —
**never** from a `'*'` sentinel, per CLAUDE.md — caps the result at three, orders by company-scope
grant before project-scope, and returns display names only. The repository interface it uses carries
`@Transactional(readOnly = true)`, or every read returns zero rows under RLS, fail-closed and
silent. The controller requires authentication and company membership and no permission beyond that,
and its Javadoc says why in one paragraph.

- [ ] **Step 5: Regenerate both copies of the contract**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 6: Wire the frontend**

Add `useGrantees(permission)` to `api/queries/admin.ts` with its key in the **one** `keys` factory
(`keys.ts` — never add a second), then in `ApiTokensPage.tsx` pass the first name through to the
`StateBlock` the page already renders:

```tsx
      <StateBlock
        kind="refused"
        permission="token:issue"
        grantee={grantees.data?.grantees[0]?.displayName}
        data-testid="ApiTokens.Refused"
      />
```

`StateBlock` already falls back to the `action` copy when `grantee` is undefined
(`StateBlock.tsx:58-67`), so a slow or empty lookup degrades to today's sentence rather than to a
blank.

- [ ] **Step 7: Run both suites**

Run: `cd backend && ./gradlew test --tests 'com.tcms.identity.GranteeLookupServiceTest'`
Run: `cd frontend && npx vitest run src/features/admin/ApiTokensPage.test.tsx`
Expected: PASS.

- [ ] **Step 8: Commit — contract, both sides, one commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
        backend/src/main/java/com/tcms/identity/GranteeLookupService.java \
        backend/src/main/java/com/tcms/api/GranteeController.java \
        backend/src/test/java/com/tcms/identity/GranteeLookupServiceTest.java \
        frontend/src/api/queries/admin.ts frontend/src/api/queries/keys.ts \
        frontend/src/features/admin/ApiTokensPage.tsx
git commit -m "feat(identity): a refusal names a person who can grant the permission"
```

---

### T15 — Sign-in discovers the method before asking for a password

**Deliverable:** `LoginPage` renders the email field alone until discovery answers for the typed
address; the password field mounts only on the local-password path.
**Depends on:** none

Audit 8.1 — and **the audit's diagnosis is corrected here.** The finding reads as though SSO
discovery is missing. It is implemented: `LoginPage.tsx:90` holds `useSsoProviders()` and `:111-112`
fires it on the email once it contains `@`, and `:64-69` already documents the directory-mode reuse
of the same two inputs. What diverges from render A is only that the password field is on screen
from the first paint, so the email never visibly decides the method.

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.tsx`
- Test: `frontend/src/features/auth/LoginPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('asks for the address before it asks for anything else', () => {
  renderLogin();
  expect(screen.getByLabelText('Email')).toBeInTheDocument();
  expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
});

it('asks for a password once discovery says this address uses one', async () => {
  renderLogin({ discovery: { providers: [] } });
  await userEvent.type(screen.getByLabelText('Email'), 'viewer@northwind.test');
  await userEvent.tab();
  expect(await screen.findByLabelText('Password')).toBeInTheDocument();
});

it('offers the directory instead when the address belongs to one', async () => {
  renderLogin({ discovery: { providers: [{ id: 'okta', name: 'Northwind SSO' }] } });
  await userEvent.type(screen.getByLabelText('Email'), 'viewer@northwind.test');
  await userEvent.tab();
  expect(await screen.findByRole('button', { name: 'Continue with Northwind SSO' })).toBeInTheDocument();
  expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/auth/LoginPage.test.tsx`
Expected: FAIL on the first and third — the password field is present from the first paint.

- [ ] **Step 3: Gate the password field on discovery**

Render it only once `discovery` has settled and returned no directory for this address. Keep the
existing schema and submit path untouched — `password: z.string().min(1, …)` still applies on the
local path, and the 401-with-an-empty-body behaviour documented at `:43-53` is deliberate and stays.

```tsx
  {/*
    Render A: *"the SSO discovery that fires on the email, before a password is ever asked for"*.
    Discovery has fired on blur since this screen was built (:111); what shipped drew the password
    field alongside the email from the first paint, so the address never visibly decided the method
    (audit 8.1). Mounting it after discovery is the whole change — no new request, no new state.
  */}
  {discovery.isSuccess && providers.length === 0 && (
    <Field label="Password" htmlFor="password">{/* the existing input, unmoved */}</Field>
  )}
```

Keep the rest of the threshold as it is — audit §8.1 records no rail, no project, the identity panel
at more weight than anywhere inside, and *"An administrator has to invite you — this instance has no
sign-up."* as already correct.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/features/auth`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/auth
git commit -m "fix(auth): the email decides the method before a password is asked for"
```

---

### T16 — Analytics legends, and figures without cards

**Deliverable:** The dashboard's `By feature` and `By owner` breakdowns carry the same legend
`By last run` has, and no figure on the dashboard sits in a bordered panel.
**Depends on:** none

Audit 6.2, 6.4. The mechanism is already there and two call sites do not use it:
`DashboardPage.tsx:253` passes `legend={LAST_RUN_LEGEND}`, and the two four-colour lifecycle stacks
pass none — so the reader sees amber, green and periwinkle with no key. Each segment does carry an
`aria-label` and a `title`, but a hover-only key is not a legend and is not available to a touch
reader at all.

**Files:**
- Modify: `frontend/src/features/dashboard/DashboardPage.tsx:253` and the two sibling call sites
- Test: `frontend/src/features/dashboard/DashboardPage.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
it('gives every multi-colour breakdown a legend', () => {
  renderDashboard();
  for (const figure of ['ByLastRun', 'ByFeature', 'ByOwner']) {
    expect(screen.getByTestId(`Dashboard.${figure}.Legend`)).toBeInTheDocument();
  }
});

it('does not wrap a figure in a panel on a screen that is nothing but figures', () => {
  const { container } = renderDashboard();
  expect(container.querySelectorAll('[data-testid^="Dashboard."] .border')).toHaveLength(0);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run src/features/dashboard/DashboardPage.test.tsx`
Expected: FAIL — two legends missing; seven bordered panels.

- [ ] **Step 3: Add the lifecycle legend and pass it to both**

Beside `LAST_RUN_LEGEND`, and modelled on it:

```tsx
/**
 * The four lifecycle entries, for the same reason `LAST_RUN_LEGEND` exists: `By feature` and
 * `By owner` are four-colour stacks whose only key was a `title` attribute (audit 6.2). A hover-only
 * key is not a legend — it does not exist for a touch reader at all, and rule 8's "a count is a
 * link to its rows" assumes the reader knows which count they are following.
 */
const LIFECYCLE_LEGEND = [
  { label: 'Draft', color: 'var(--color-untested)' },
  { label: 'In review', color: 'var(--color-blocked)' },
  { label: 'Approved', color: 'var(--color-passed)' },
  { label: 'Deprecated', color: 'var(--color-skipped)' },
] as const;
```

Pass it to both call sites. Use the colours the segments already draw — read them from the existing
segment definitions rather than retyping them, so the legend cannot drift from the bars.

- [ ] **Step 4: Uncard the figures**

Rule 1: no panel around the subject of the page. On a screen that is nothing *but* figures, seven
borders is exactly what rule 1 describes. Replace `FigurePanel` on the dashboard with the bare
`Figure` heading + body, keeping the heading and the caption. `FigurePanel` stays in the kit for
screens where a figure genuinely is one region among several.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npx vitest run src/features/dashboard`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/dashboard
git commit -m "fix(dashboard): a legend on every multi-colour breakdown, and no panel around a figure"
```

---

### T17 — A failure looks like a failure, and says which one

**Deliverable:** A persistent 503 leaves the loading skeleton within ~8 seconds for `DataRegion`'s
FAILED state; every failure — boundary or region — carries a six-character reference and a
timestamp; and a shape error takes the region, not the whole route.
**Depends on:** none

Audit 9.3 (High) and 9.2. These are one task because they are the two halves of §7's central
requirement — *the six states must be unmistakable* — and they share `queryClient.ts` and
`ErrorBoundaries.tsx`.

**9.3's root cause, read from the code.** `app/queryClient.ts:146` retries while
`failureCount < MAX_RETRIES` (`:30`, `= 3`) and `isTransient(error)`; `:148-154` obeys the server's
`Retry-After` up to a 60-second cap. Under `tcmsFaults.status(503, 30)` that is three waits of
**thirty seconds each** — so the Board sat in its skeleton for the 56+ seconds the audit measured,
with no failed state, no message and zero notifications, and no way for a reader to tell it from a
slow request. Obeying `Retry-After` is right and the comment's reason stands (*"backing off less
than a 429 or 503 asked for is how a throttle becomes a ban"*). What is wrong is doing it silently.

**The fix is a retry budget, not a shorter backoff.** If the server asks for longer than the budget,
do not sleep on it — go to the failed state, which offers manual retry and can quote the server's
own number. That obeys `Retry-After` (nothing is re-sent early) and stops the skeleton lying.

**Files:**
- Modify: `frontend/src/app/queryClient.ts:30`, `:146-154`
- Modify: `frontend/src/components/DataRegion.tsx` (the reference/timestamp it passes on)
- Modify: `frontend/src/components/ErrorBoundaries.tsx`
- Create: `frontend/src/lib/errorReference.ts` + test
- Test: `frontend/src/app/queryClient.test.ts`, `frontend/src/components/ErrorBoundaries.test.tsx`

- [ ] **Step 1: Write the failing tests**

```ts
// queryClient.test.ts
describe('the retry budget', () => {
  it('does not auto-retry when the server asks for longer than the budget', () => {
    const error = apiError(503, { retryAfterSeconds: 30 });
    expect(shouldRetry(0, error)).toBe(false);
  });

  it('still retries a transient failure the server said nothing about', () => {
    expect(shouldRetry(0, apiError(503, {}))).toBe(true);
    expect(shouldRetry(3, apiError(503, {}))).toBe(false);
  });

  it('spends at most the budget across every attempt', () => {
    const total = [0, 1, 2].reduce((sum, attempt) => sum + retryDelay(attempt, apiError(503, {})), 0);
    expect(total).toBeLessThanOrEqual(RETRY_BUDGET_MS);
  });
});
```

```tsx
// ErrorBoundaries.test.tsx
it('carries a reference and a timestamp, because §7 says a reader must be able to quote one', () => {
  renderingIntoAnErrorBoundary('the route boundary is the subject of this test');
  renderThrowing();
  expect(screen.getByTestId('RouteError.Reference')).toHaveTextContent(/^[0-9A-Z]{6}$/);
  expect(screen.getByTestId('RouteError.Timestamp')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/app/queryClient.test.ts src/components/ErrorBoundaries.test.tsx`
Expected: FAIL — `shouldRetry` is not exported; the boundary renders neither node.

- [ ] **Step 3: Give the retry policy a budget, and export its predicates**

`queryClient.ts` — extract the two closures so they are testable, and add the budget:

```ts
/**
 * How long automatic retrying may take in total, before the region must say so.
 *
 * Audit 9.3: with `tcmsFaults.status(503, 30)` armed, three retries each obeying a 30-second
 * `Retry-After` kept the Board in its loading skeleton for 56+ seconds with no failed state, no
 * message and no notification — a loading state indistinguishable from a permanently broken one,
 * which is the exact thing §7's six states exist to prevent.
 *
 * `Retry-After` is still obeyed: nothing is re-sent earlier than the server asked. What changes is
 * that a server asking for longer than this budget is not waited on silently — the region goes to
 * FAILED, which offers manual retry and can quote the server's own number. Obeying a throttle and
 * lying to the reader are separable, and only the first is a requirement.
 */
export const RETRY_BUDGET_MS = 8_000;

export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_RETRIES || !isTransient(error)) return false;
  const serverAsked = retryAfterMs(error);
  return serverAsked === null || serverAsked <= RETRY_BUDGET_MS;
}

export function retryDelay(attemptIndex: number, error: unknown): number {
  const serverAsked = retryAfterMs(error);
  if (serverAsked !== null) return Math.min(serverAsked, RETRY_BUDGET_MS);
  return Math.min(1_000 * 2 ** attemptIndex, RETRY_BUDGET_MS / 2);
}
```

and point the query defaults at them: `retry: shouldRetry, retryDelay`.

- [ ] **Step 4: Give every failure a reference and a timestamp**

Create `frontend/src/lib/errorReference.ts`:

```ts
/**
 * Design language §7: *"Every failure carries a six-character reference and a timestamp"* — so that
 * a reader who reports one has something to quote and a log has something to match. Audit 9.2 found
 * the route boundary carrying neither.
 *
 * Six characters from a Crockford-style alphabet with the ambiguous glyphs removed: a reference is
 * read aloud and typed into a support message, and `0`/`O` and `1`/`I` are where that goes wrong.
 * It is not an id — nothing looks it up — it is a handle on one moment, so it is generated where
 * the failure is caught and never regenerated on re-render.
 */
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';

export function newErrorReference(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return [...bytes].map((byte) => ALPHABET[byte % ALPHABET.length]).join('');
}
```

`ErrorBoundaries.tsx` — each tier generates one reference in `getDerivedStateFromError` (not in
render, so it is stable), stamps `new Date().toISOString()` beside it, includes both in what it
reports, and renders both. `kit/StateBlock`'s `failed` arm already **requires** `reference` and
`timestamp` (`StateBlock.tsx:43-47`) — render the boundary through it rather than through bespoke
markup, and the requirement is then enforced by the type.

- [ ] **Step 5: Keep a shape error inside its region**

Under `tcmsFaults.drift()` the wrong shape throws in render
(`TypeError: suites.data?.find is not a function`) and the **route** boundary replaces the whole
page — so the filters and the page frame are lost with the data, when §7 asks for `DataRegion`'s
FAILED state inside the region. §9.1's load-bearing property already holds (it reads as FAILED, not
as empty) and must not regress.

Wrap each page's `DataRegion` in the **region** tier that `ErrorBoundaries.tsx` already provides, so
the chrome above it survives. Start with the three screens the audit reached — Board, case list,
suites — and add the rest in T19's follow-up list rather than sweeping 42 screens blind.

- [ ] **Step 6: Prove it with the fault switch, not by reasoning**

CLAUDE.md: *"Build the fault switch before the feature it proves."* Both switches already exist.
With `npm run dev` and the backend up, in the browser console:

```js
tcmsFaults.status(503, 30)   // the Board must leave the skeleton in ≤ ~8s and show FAILED + a reference
tcmsFaults.drift()           // the case list must keep its filter strip and fail INSIDE the region
tcmsFaults.off()
```

Record what was seen — including the measured time to FAILED — in T19's results section. If either
does not behave as stated, that is this task's bug, not T19's.

- [ ] **Step 7: Run the tests**

Run: `cd frontend && npx vitest run src/app/queryClient.test.ts src/components/ErrorBoundaries.test.tsx src/lib/errorReference.test.ts`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/queryClient.ts frontend/src/components/ErrorBoundaries.tsx \
        frontend/src/components/DataRegion.tsx frontend/src/lib/errorReference.ts \
        frontend/src/lib/errorReference.test.ts frontend/src/app/queryClient.test.ts \
        frontend/src/components/ErrorBoundaries.test.tsx
git commit -m "fix(states): a retry budget so a 503 stops looking like loading, and a reference on every failure"
```

---

### T18 — The spec is amended where the render was wrong

**Deliverable:** `docs/specs/2026-08-28-design-language.md` carries four amendments, each written as
the superseded text struck through with its reversal beside it, and the audit's §11 "spec should be
amended" list is fully answered.
**Depends on:** T2 (the palette numbers), T11 (the runner's layouts and its timer)

Plan 007 §4's rule: where the build disagrees with a render, the SPEC is amended. Four items qualify,
and CLAUDE.md's working agreement fixes the shape — *"write superseded decisions **and** their
reversal into the docs, struck through with '→ superseded', so the next reader doesn't think the old
reasoning was never considered."*

**Files:**
- Modify: `docs/specs/2026-08-28-design-language.md` §1, §6, §9.2, and the Artifacts and runner notes
- Modify: `docs/specs/assets/renders/` — no file changes; the renders stay as drawn and the spec
  states where they are superseded

- [ ] **Step 1: §1 and §6 apply per scheme**

Both sections are written entirely in dark-theme terms, which is how the light palette shipped
without ever being measured (audit 6.1). Add to §6, after its ΔE table:

> **These numbers are per scheme.** ~~The palette validates once.~~ → **superseded, 2026-08-30.**
> The figures above were derived for the dark palette only; the light one was never put through the
> same check and failed on two of three adjacent pairs — `failed | blocked` at 12.6 and
> `blocked | skipped` at 13.0 against a floor of 15, drawing red beside brown as one warm band.
> Every scheme is validated separately, and a light palette additionally has to clear 4.5:1 as text
> on the sunken surface, because these tokens colour the legend's words as well as the bar's fills.
> `frontend/src/app/statusPalette.test.ts` is the check; it reads the stylesheet and measures both.

- [ ] **Step 2: Artifacts is a registrable catalogue**

Render H draws Artifacts as an empty state whose copy says artifacts *"arrive attached to an
execution or an ingested report; there is nothing to upload here"*. The build has a Register button,
a scope select, and registered documents — **and the backend supports registration**, so the render
was drawn against a wrong mental model. The audit calls this the clearest case in it of the build
being right. Amend §8's family H entry and mark the render superseded in place.

- [ ] **Step 3: The runner has three layouts, and its duration is not a timer**

The render's note says *"Focus and Split are the two layouts"*; the build ships Focus, Split and
Grid, and Grid is used. Amend to three.

Add the timer reversal from T11 with its reason, since it is a decision and not an omission:

> ~~The duration field is a live counter — *"Counting since you opened the case"*.~~ →
> **superseded, 2026-08-30.** It is an optional field captioned *"Leave it empty if you did not time
> this run."* A counter that starts when a tab opens measures the tab, not the test, and a wrong
> duration recorded automatically is worse than an absent one recorded honestly.

- [ ] **Step 4: Record the rail measurement that was already amended**

§9.2's 64px is the one place the spec was amended in the build's favour, and the audit confirms the
build matches it on all three routes (`Nav.Rail` measured at 64px, against the 60px the renders
draw). Add a line saying it has been verified in the shipped build, so the next reader does not
re-open it.

- [ ] **Step 5: Commit**

```bash
git add docs/specs/2026-08-28-design-language.md
git commit -m "docs(spec): four amendments where the render was wrong, each struck through with its reversal"
```

---

### T19 — Gates, browser re-verification, audit round 2

**Deliverable:** All four gates green on `shanks`; a re-seeded fixture verified against the brief;
the audit document carrying a round-2 status against every one of its 34 findings; and this plan's
own results section written.
**Depends on:** T1–T18

**Files:**
- Modify: `docs/audits/2026-08-30-renders-vs-implementation.md` (a round-2 column, not a rewrite)
- Modify: `docs/plans/implemented/008-render-audit-remediation.md` (§ Results)

- [ ] **Step 1: Sync and run the four gates on `shanks`**

Read `docs/specs/shanks-runner-details.md` first — in particular that `BUILD FAILED` with
`Could not write XML test results` and **zero** assertion failures is an fd-limit problem, not a red
suite (`ulimit -n 65535; ./gradlew build`).

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
```

Expected: green. This is the first full build since T1, so failures here are this plan's, not the
runner's — fix them in this task and note each one in the results section.

- [ ] **Step 2: Fix whatever the gate finds, then re-run it**

The likely classes, named so they are recognised rather than debugged from scratch: `citedFiles`
failing on a path named in a comment that T5/T7/T13 deleted; `tierClaims` failing on a comment
rewritten in T6 or T14; `contractPairing` failing because T14 regenerated one copy of the schema and
not the other; `tokenSystem` failing because T2 moved `--color-blocked` without `--color-when`.

- [ ] **Step 3: Rebuild the image and re-seed, with T1's exit code doing its job**

```bash
docker compose build backend
docker compose down -v
docker compose up -d
docker compose run --rm backend seed-demo-data; echo "exit: $?"
```

Expected: `exit: 0` and the fixture matching the brief — WEB 10 cases / 4 runs / 1 plan / 3 doc
pages; MOB 4/1/1/2; EHR 3/1/1/2; PAY and LEG empty; LEG archived. A `3` here means T1 worked and the
fixture is short — read the `Refusals` block and fix the seeder before going on, rather than
auditing against an incomplete fixture, which is what cost the first audit two attempts.

- [ ] **Step 4: Re-verify in the browser, at both widths and both schemes**

Walk the eleven **High** findings first, at **1280px and 1680px**, in **dark and light**:

| Finding | What to confirm |
|---|---|
| 1.1 / 9.5 | The case-list strip is one row; PAY renders no dead controls |
| 1.3 | Title column measures ≥300px at 1680 |
| 1.10 | Every artifact header cell is a `TH` in the DOM |
| 2.1 | No action's `bottom` exceeds the header's, at 1280 |
| 2.2 | The legend sums to the bar's `aria-label` total |
| 3.2 | Procedure column ≥60ch at 1280; top strip does not wrap |
| 3.3 | **Failed** with no step marked opens the panel and refuses |
| 6.1 | The light TR-1 bar's red and amber read as two bands |
| 7.1 | The refusal names Dana Whitfield |
| 9.3 | `tcmsFaults.status(503, 30)` reaches FAILED in ≤ ~8s |

Then the Mediums and Lows. Measure rather than eyeball — the audit's own numbers came from
`getBoundingClientRect` and `getComputedStyle`, and that is what makes them re-checkable.

- [ ] **Step 5: Write round 2 into the audit document**

Add a **Round 2 — 2026-08-30+** column or per-finding line to
`docs/audits/2026-08-30-renders-vs-implementation.md`: `fixed` / `amended in spec` / `still open`,
each with the commit or the reason. Do not delete the round-1 text — the document's value is that it
is re-runnable, and a finding that was fixed reads differently from one that was never there.

Restate §10's unchecked list unchanged, and add anything this plan could not reach — the org chart
at five projects, a fifty-step case, `AcceptInvitation`/`SsoCallback`, revoked tokens and paused
schedules at reduced weight. Say plainly that they remain unchecked.

- [ ] **Step 6: Write this plan's results section**

Append a `## Results` section to this file: which gate commands ran and their outcome, the measured
numbers from Step 4, the fault-switch observations from T17 Step 6, and anything deferred with the
reason. Per CLAUDE.md: say **"built and unverified"** for anything that only typechecked. The gates
prove it compiles, not that it works.

- [ ] **Step 7: Commit and push**

```bash
git add docs/audits/2026-08-30-renders-vs-implementation.md docs/plans/implemented/008-render-audit-remediation.md
git commit -m "docs(audit): round 2 — what the render remediation fixed, and what is still open"
git push
```

Know what the push buys: `ci.yml` runs on push to `master` (backend **compile only**, frontend build
+ lint + smoke, packages full) and continuously delivers to the lab. The full backend suite runs
nightly in `tests.yml`, so Step 1's `./gradlew build` on `shanks` remains the only pre-merge run of
it.

---

## Appendix — every audit finding, and the task that answers it

Written as the self-review pass the plan skill requires: each of the audit's numbered findings, with
the task that resolves it. Nothing in the audit is unaccounted for.

| Finding | Severity | Task |
|---|---|---|
| §0 seeder reports success on an incomplete fixture | (method note) | T1 |
| 1.1 filter strip is not a strip | High | T5 |
| 1.2 two filter idioms in one family | Medium | T5 |
| 1.3 the table starves the Title column | High | T6 |
| 1.4 last result is text where the render draws a bar | Medium | T6 |
| 1.5 health is a chip where the render uses a badge | Low | T6 |
| 1.6 priority spends the reserved result palette | Medium | T6 |
| 1.7 no table foot | Medium | T3 (component) + T6 (first consumer) |
| 1.8 Reports is cards, not the shared table | Medium | T7b |
| 1.9 run schedules has no per-row switch | Medium | T7b |
| 1.10 the artifact table has no `<th>` | High (a11y) | T7a |
| 1.11 Artifacts: the render models a different product | Medium (spec) | **T18 — amend the spec** |
| 1.12 projects: two of three signals, and a dangling word | Medium | T7a |
| 1.13 proposal tabs carry no counts | Low | T7a |
| 2.1 the case-detail action group overflows the header | High | T4 |
| 2.2 the run summary legend omits skipped | High | T8 |
| 2.3 the right rail clips its own content | Medium | T3 (component) + T8 (call sites) |
| 2.4 two rail idioms on one rail | Low | T8 |
| 2.5 the report preview is dark, and its prose is 1639px | Medium | T9 |
| 2.6 what matched the render | conformant | — (must not regress; T19 Step 4) |
| 3.1 §4's rail collapse at 64px | conformant | T18 records the verification |
| 3.2 the runner's three-pane split breaks at 1280 | High | T11 |
| 3.3 recording a failure has no failure panel | High | **T10 — re-diagnosed: the panel exists, the predicate is wrong** |
| 3.4 smaller runner divergences | Low/Medium | T11 (queue, elapsed, pause, preconditions) + T18 (grid, timer) |
| 4.1 the board at five columns on 1280 | Medium | T12 |
| 4.2 WCAG 2.2's drag rule | conformant | T12 preserves it explicitly |
| 4.3 org chart | conformant | — (five projects unreachable in this fixture) |
| 5.1 the doc editor gives every block a toolbar | Medium | T13 |
| 5.2 feature import is one column, not three | Low | T13 |
| 5.3 documentation puts its chrome in the reading column | Low | T13 |
| 6.1 the light status palette was never validated | High | T2 (+ T18 for the per-scheme rule) |
| 6.2 two of three dashboard breakdowns have no legend | Medium | T16 |
| 6.3 §6's chart rules that hold | conformant | — |
| 6.4 figures are carded | Low | T16 |
| 6.5 health-signal copy | verified | — |
| 7.1 a refusal names the permission but not a person | High | T14 |
| 7.2 / 7.3 review settings, tokens, classification, access | conformant | — |
| 8.1 sign-in asks for the password before discovering the method | Medium | **T15 — re-diagnosed: discovery exists, the field mounts too early** |
| 9.1 `drift()` reads as FAILED, not empty | conformant | T17 Step 5 must not regress it |
| 9.2 it fails through the boundary with no reference or timestamp | Medium | T17 |
| 9.3 a persistent 503 never leaves the loading skeleton | High | T17 |
| 9.4 the empty state does not count what its filters hid | Medium | T5 |
| 9.5 an empty project shows every dead filter | High | T5 |
| 9.6 NotFound and Help | conformant | — |

**All eleven High findings are answered:** 1.1/9.5 → T5 · 1.3 → T6 · 1.10 → T7a · 2.1 → T4 ·
2.2 → T8 · 3.2 → T11 · 3.3 → T10 · 6.1 → T2 · 7.1 → T14 · 9.3 → T17.

**§10's unchecked list is not addressed by this plan** and stays unchecked: the org chart at five
projects, a fifty-step case, `AcceptInvitation` / `SsoCallback`, revoked tokens and paused schedules
at reduced weight, `Unmatched` as a link, the end-of-queue runner screen, and MyWork with anything
assigned. Every one of them is a *fixture* limit rather than a code question, and extending the
demo fixture to reach them is its own plan — one that T1 makes safe to write, because a seeder that
silently produces less than it promises is what made the current fixture untrustworthy in the first
place.

---

## Results — 2026-08-30

All nineteen tasks executed in one session, one commit each, on `master` per CLAUDE.md.

### The gates

Run locally rather than on `shanks`, because the user asked for them in-session. The backend suite
therefore ran against a local Testcontainers Postgres rather than the lab's; `shanks` remains the
place a pre-push run belongs.

| Gate | Result |
|---|---|
| `frontend` `npm run build` | **pass** (`tsc -b` + vite) |
| `frontend` `npm run lint` | **pass**, 0 warnings |
| `frontend` `npm run test:run` | **pass** — 160 files, **2170 tests** |
| `packages` `npm run build` | **pass** (client → cli → mcp, in order) |
| `packages` `npm run lint --workspaces` | **pass**, 0 warnings |
| `packages` `npm run test:run --workspaces` | **pass** — client 138, cli 12 files, mcp 153 |
| `backend` `./gradlew build` | **pass** on the second run — see below |

**The backend gate failed first, on three arms of `ContractConventionTest`**, and it was right to:
T14's `GranteeList` schema and its inline `grantees[]` item were paired with no record, so nothing
compared the contract to `GranteeListView` / `GranteeView` in either direction. Pairing them is the
fix; 3579 of 3582 passed on the first run and all 3582 on the second. This is the class of miss the
plan's §0 named — a rule that is green by cache or by omission until something new arrives.

### What the tasks turned up that the plan did not predict

Five things, each recorded in the code where the next reader stands:

1. **Two findings were re-diagnosed** (3.3 and 8.1), as the plan's header predicted — and a third
   turned out to have a worse cause than its write-up: the runner queue's status dot **existed**,
   with a `data-testid` and an `aria-label` and no size, no colour and no class, so it rendered
   nothing. A screen reader was told every row's status and a sighted reader none.
2. **Two of the audit's findings are blocked on the contract, not the screen** (1.4's result bar,
   1.12's two extra project bars). Both would have to claim a proportion or a figure the wire does
   not carry. Neither is silently dropped; the reason sits in the cell and in the file's docblock.
3. **One finding was considered and not applied** (6.4). `FigurePanel`'s own docblock already argued
   the other side and the argument holds: rule 1's subject is *the only thing on a screen*.
4. **Two fixes were walked back mid-task by the existing tests, correctly.** T7a's archived-project
   switch defaulted to hiding and broke the guarantee `ArchiveProject.test.tsx` pins — an archived
   project must stay listed, because omitting one makes it unreachable rather than marked. T4's
   first attempt folded Delete into a menu and fought `PermissionGate`'s stated principle that a
   Viewer seeing a greyed Delete learns the capability exists.
5. **Writing the tests found two defects of their own**: `FilterStrip` keyed its Clear control to
   the applied chips, so a suite filter with no chip left a reader stuck on an empty list; and
   `contractFixtures` caught a fixture cast through `unknown` that was hiding four required fields.

### What is not done

- **No browser pass.** Everything above is the automated gates and reading the code. The measured,
  two-width, two-scheme walk that produced the audit is outstanding, and it is the only thing that
  can confirm the four layout findings (1.3, 2.1, 3.2, 4.1) in pixels rather than in mechanism.
- **No re-seed.** T1's exit code is unit-tested; it has not been driven through
  `docker compose run --rm backend seed-demo-data` against a rebuilt image.
- **§10's unchecked list is unchanged**, and is restated as unchecked in the audit's round-2 section
  rather than quietly dropped.
- **Nothing is pushed.** `ci.yml` and the lab delivery have not run.
