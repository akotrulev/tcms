# Slice prompts

One file per slice of `docs/plans/2026-08-18-unplanned-gaps.md`, which closes the nine gaps recorded
in `docs/features.md` § *Gaps Not Currently Planned*.

**Each file is one Claude session.** Paste its `## Prompt` block into a fresh session; everything
below that block is context for the reader, not part of the paste.

Run them **in order**. The order is a dependency order, not a preference — the reasoning is in the
master plan's table and each prompt repeats the part that binds it.

| # | Prompt | Lane | Migration | Depends on |
|---|---|---|---|---|
| 1 | [`01-audit-log-reader.md`](01-audit-log-reader.md) | backend + frontend | — | — |
| 2 | [`02-review-notifications.md`](02-review-notifications.md) | backend | — | — |
| 3 | [`03-execution-duration.md`](03-execution-duration.md) | backend + frontend | `V68` | — |
| 4 | [`04-bulk-execution-assignment.md`](04-bulk-execution-assignment.md) | backend + frontend | — | 3, and 1 if landed |
| 5 | [`05-scheduled-runs.md`](05-scheduled-runs.md) | backend + frontend | `V69` | 4 |
| 6 | [`06-cross-project-case-search.md`](06-cross-project-case-search.md) | backend | — | — |
| 7 | [`07-company-data-export.md`](07-company-data-export.md) | backend / operator CLI | — | 3, 5 |
| 8 | [`08-keyboard-driven-execution.md`](08-keyboard-driven-execution.md) | frontend | — | 3, 4 |
| 9 | [`09-i18n-groundwork.md`](09-i18n-groundwork.md) | frontend | — | all of 1–8 |

**Flyway numbers are pre-assigned and `out-of-order` is unset.** If a slice is skipped, leave its
number unused — do not renumber a later slice into the gap.

**Only slice 1 is fully stepped in the master plan.** Every other prompt's first instruction is to
run `ep-writing-plans` on that slice alone before touching code. That is deliberate: the master plan
says why in its Scope Check.
