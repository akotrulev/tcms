# 003 — journeys round (r3), captured evidence

Screenshots taken by `fe-react:design-auditor` on **2026-08-28** against the live
instance `https://tcms.203-0-113-10.sslip.io`, company **Reflex**, via
claude-in-chrome on the user's authenticated session. **Writes were authorized for
this round**; every record created is prefixed `[design-audit]` and listed in the
report's cleanup inventory.

Project id in every path: `11e23d67-b1bd-4caf-9842-bdf0a893a442`.
Widths are read from the JPEG/PNG header and are authoritative.

| File | What it shows | Viewport |
|---|---|---|
| 01-root-w1400.jpg | `/` — "1 cases" still present (r1 G2 unfixed) | 1400 |
| 02-suite-dialog-w1097.jpg | New suite dialog — teaches the three membership arms | 1097 |
| 03-suite-dialog-validation-w1097.jpg | **Empty submit → "Enter a name."** inline, client-side | 1097 |
| 04-suite-created-TS-3-w1097.jpg | TS-3 created, auto-selected, "0 cases filed, added or tagged here" | 1097 |
| 05-empty-suite-no-cases-match-w1097.jpg | **J1 — brand-new empty suite shows "No cases match / Clear filters"** | 1097 |
| 06-case-new-w1473.jpg | `/cases/new` — single-column layout, no right rail | 1473 |
| 07-case-new-validation-title-w1473.jpg | **Empty submit → "Enter a title.", focus moved into field** | 1473 |
| 08-case-new-autosave-backup-w1473.jpg | **"Backup saved 05:32 UTC. This is not the case itself…"** | 1473 |
| 09-case-created-TC-3-no-toast-w1473.jpg | **TC-3 created — no success confirmation of any kind** | 1473 |
| 10-suite-now-1-case-w1473.jpg | Suite now "1 case filed, added or tagged here" | 1473 |
| 11-runs-list-green-completed-w1473.jpg | r2 UX3 reconfirmed at full height: two identical green chips | 1473 |
| 12-run-new-dialog-w1473.jpg | New run dialog — four scope inputs, excellent helper copy | 1473 |
| 13-run-TR-3-detail-w1473.jpg | **Run detail — "not the final answer", legend with counts, snapshot note** | 1473 |
| 14-execution-screen-split-w1473.jpg | **The execution screen** — Focus/Split/Grid, `P F B S` | 1473 |
| 15-execution-step-passed-w1473.jpg | Step passed; header → "All steps marked" | 1473 |
| 16-execution-verdict-buttons-w1473.jpg | Verdict buttons + **"Record or clear this result first"** guard | 1473 |
| 17-keyboard-shortcuts-dialog-w1473.jpg | **Keyboard shortcuts — accelerators never the only route** | 1473 |
| 18-run-after-failed-0of1-steps-w1097.jpg | **"What failed" says "0 of 1 steps failed"** on a failed case | 1097 |
| 19-search-overlapping-labels-w1473.jpg | **`/search` — three selects render label over value** | 1473 |
| 20-search-overlapping-labels-ZOOM.png | Same defect at 1.5×, confirming it is not a capture artifact | zoom |
| 21-docs-page-lowercase-draft-w1473.jpg | Docs page — lowercase `draft` chip; action row wraps, Delete orphaned | 1473 |
| 22-case-detail-after-cancel-w1473.jpg | After Cancel — edit not applied, no warning shown | 1473 |
| 23-unsaved-changes-restore-banner-w1473.jpg | **"Your unsaved changes from 1m ago" — Discard / Restore** | 1473 |
| 24-run-TR-3-final-passed-w1473.jpg | Final state left behind: TR-3 In progress, 100% passed | 1473 |

## Tooling caveat recorded with the evidence

The `computer.type` action **did not deliver text into multiline `<textarea>`
fields** in this app (execution Notes, case Objective), while single-line inputs
(case Title, run Name) accepted it normally. In the execution screen the
undelivered keystrokes were consumed by the page's global single-key shortcut
handler instead, which changed the recorded verdict several times.

**No finding in the r3 report attributes that to the application**, because the
app's own shortcut dialog states the guard ("Nothing here fires while you are
typing in a box") and this evidence cannot distinguish a broken guard from the
extension dispatching keys outside the focused element. It is filed as an
open question for r4, to be settled with a real keyboard.
