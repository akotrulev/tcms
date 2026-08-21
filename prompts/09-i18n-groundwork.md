# Slice 9 — Internationalisation groundwork

**Lane:** frontend only · **Migration:** none · **Depends on:** all of Slices 1–8

**Run this last.** It sweeps every user-facing string in the SPA; any earlier and Slices 1–8 each
add untranslated strings behind it.

---

## Prompt

```text
Implement **Slice 9 — Internationalisation groundwork** from
`docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-9-i18n-groundwork.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/fe-react.md`, then the Slice 9 section of the master plan. Use
**`/fe-react`** and load `fe-react-languages`.

**What this slice is:** there is no translation infrastructure of any kind. Route every
user-facing string through a translation layer so that adding a locale later is *data* rather than
surgery.

**Scope boundary — hold it.** Ship the **mechanism and the English catalogue only. Do not
translate anything.** A second locale is a separate slice and probably a paid one. Say so in the
handoff, explicitly, so the next reader does not think a translation pass was attempted and
abandoned.

**Shape:** an i18n provider in `frontend/src/app/Providers.tsx`, a catalogue module, and a lint
rule or test that fails on a bare string literal in JSX so the sweep cannot silently regress.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation cache in this slice's own commit.
```

---

## The four traps this slice turns on

**1. `Providers.tsx` nesting order is load-bearing.** It is documented in the file: the query client
is constructed **innermost** because its global error handlers consume the notification and
session-recovery providers. Adding a provider in the wrong place here has previously rendered
nothing but the error page — and it typechecked and linted clean.

**2. `lib/labels.ts` maps enums with `satisfies Record<Union, string>`, which makes a new enum member
a compile error until it is labelled. Preserve that property through the move.** Routing those maps
through a runtime catalogue that returns a key-not-found string trades a compile-time guarantee for a
runtime blank — that is a regression, however tidy it looks.

**3. Dates and numbers are `lib/format.ts`'s job.** An i18n library will offer its own formatters. Do
not let a second formatting path in; a hundred consistent pages become a hundred slightly different
ones exactly this way.

**4. Do not "fix" the case-key sort.** `frontend/src/lib/planScope.ts:78` deliberately uses
`compareCaseKeys` and **not** `localeCompare` — otherwise `TC-10` sorts ahead of `TC-9`, invisibly.
An i18n pass is precisely when someone helpfully switches it to a locale-aware comparator. There is a
pinned test asserting the wrong answer; read it before touching the sort.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote frontend npm run test:run`, `npm run build`,
  `npm run lint` (0 warnings, a gate).
- **Verify with `npm run build`, not `tsc --noEmit`** — the latter has reported 0 errors while the
  build failed with nine.
- **Acceptance gate:** frontend suite, build and lint green; the bare-literal check passing; and a
  written note in the handoff that **no locale was added**.
- This is a very wide diff. Commit per phase, stage only the files that phase touched, and expect to
  re-read `docs/agent-context/fe-react.md`'s seam list rather than trusting a grep.
