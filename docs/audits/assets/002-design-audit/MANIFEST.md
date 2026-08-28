# 002 design audit — captured evidence (round 1)

Screenshots taken by `fe-react:design-auditor` on 2026-08-27 against the live
instance `https://tcms.203-0-113-10.sslip.io`, company **Reflex**, via
claude-in-chrome on the user's authenticated session. Extracted from the agent
transcript after the fact; the transcript stored each shot twice, so 42 blocks
deduped to 21 unique images.

Project id in every `project-*` path: `11e23d67-b1bd-4caf-9842-bdf0a893a442`.

| File | Route | Viewport |
|---|---|---|
| 01-root-w1512.jpg | `/` | 1512 |
| 02-my-work-w1512.jpg | `/my-work` | 1512 |
| 03-project-1-cases-w1512.jpg | `/projects/1/cases` — **invalid id error state** | 1512 |
| 04-project-w1512.jpg | project overview | 1512 |
| 05-project-w1440.jpg | project overview | 1440 |
| 06-project-w1440.jpg | project overview (scrolled) | 1440 |
| 07-project-cases-w1440.jpg | case repository | 1440 |
| 08-project-cases-w1440.jpg | case repository (scrolled) | 1440 |
| 09-case-detail-w1440.jpg | case detail | 1440 |
| 10-case-detail-w1440.jpg | case detail (scrolled) | 1440 |
| 11-case-edit-w1440.jpg | case edit | 1440 |
| 12-project-runs-w1568.jpg | `/runs` — **TR-2 green chip + 0% pass** | 1568 |
| 13-project-reports-w1568.jpg | `/reports` | 1568 |
| 14-project-cases-w501.jpg | case repository | 501 |
| 15-project-cases-w501.jpg | case repository (scrolled) | 501 |
| 16-project-cases-w900.jpg | case repository — **rail+panel 55% of viewport** | 900 |
| 17..20-help-getting-started-w900.jpg | `/help/getting-started` | 900 |
| 21-project-board-w900.jpg | `/board` | 900 |

**Not captured, and therefore unassessable from this evidence:** the execution
screen, every write flow, the auth/invitation screens, and light mode.

## Correction to r1's evidence header

r1 states **"4 widths (1568 · 1440 · 900 · 501)"**. The captures show **five**:
**1512** (`/`, `/my-work`, `/projects/1/cases`, project overview) is absent from
that list. Widths here are verified from the JPEG headers, not from the
resize calls, so this file is the authority where the two disagree.
