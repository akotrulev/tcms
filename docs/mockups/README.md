# Project-management expansion — click-through mockups

Static renders of every screen in
[`../specs/2026-08-30-project-management-expansion-user-stories.md`](../specs/2026-08-30-project-management-expansion-user-stories.md),
wired to each other so the whole thing can be walked through. **Open
[`index.html`](index.html)** — it is the screen map, grouped by the story sections, and every card
links into the flow it belongs to.

No build step, no server needed: open the file. (A local server also works —
`python3 -m http.server` in this directory — and is what the renders were checked in.)

## What is real and what is not

| | |
|---|---|
| **Links** | Navigate. Every screen reachable from the sidebar, the breadcrumbs, a table row or a card. |
| **The `as …` switcher** | Changes what the sidebar offers. **Billing** is absent for everyone but Finance and Company Admin (story M2) — absent, not greyed out. |
| **☾ / ☀** | Swaps light and dark. Both palettes are the app's own tokens. |
| **Toggles** | Flip, and say in a toast what would follow. |
| **Everything else** | Says "not wired in the render" rather than pretending. Nothing is saved. |

## Why it looks like this

The palette, the two font families and the `[data-color-scheme]` swap are **lifted from
`frontend/src/index.css`**, not invented — these renders exist to show what the expansion looks
like *inside this product*, so a second visual identity would be a lie. Token names in
`assets/tcms-mock.css` are the app's own (`--color-surface`, `--color-text-muted`, …), so a value
copied out of a render lands in the real stylesheet unchanged.

This is the **click-through** counterpart to `docs/specs/assets/renders/`, which renders the design
language as pattern *families* rather than as a navigable app. Same palette, different question.

Three corpus rules the renders hold to, because the stories depend on them:

- **Dragging is never the only way.** Every board card carries a `⋮ Move to…`, every rankable row
  a `↑ / ↓` pair (WCAG 2.2 *Dragging Movements*). Story B3's keyboard requirement is not a footnote.
- **An empty state carries the action that resolves it** — Clients with none, a project with no
  releases, a release with no run.
- **Chip rows wrap and tables scroll inside themselves.** A filter never hides a label it set, and
  no table breaks the page.

## The screens

`index.html` is the map. In flow order:

- **Every day** — `my-work`, `projects`, `project-overview`, `solo-overview` *(the same overview for a
  company with no clients and no reviewer)*
- **Work, five views on one filter** — `work-board`, `work-backlog`, `work-table`, `work-timeline`,
  `work-calendar`
- **Hierarchy** — `epic`, `story`, `task`, `issue-new`, `story-proposal`, `documents`
- **Releases & reporting** — `releases`, `release-new`, `release`, `reports`
- **Project settings** — `settings-general`, `settings-workflow`, `settings-boards`,
  `settings-repositories`, `settings-import`
- **Jira** — `import-connect` → `import-preview` → `import-mapping` → `import-report`
- **Clients** — `clients-empty`, `clients`, `client`, `client-new`, `project-new`
- **Time & money** — `timesheets`, `timesheet-approvals`, `billing`, `invoice`
- **People** — `staffing`, `directory`, `teams`, `member`
- **Assistants** — `profile-assistants`, `assistant-connect`, `help`
- **Existing screens** — `test-cases`, `test-suites`, `test-plans`, `test-runs`

## Editing them

`assets/tcms-mock.css` is the whole design system; `assets/shell.js` draws the sidebar, top bar and
breadcrumbs on every page from four `data-*` attributes on `<body>`:

```html
<body data-nav="projects"
      data-proj="ACME|Acme Storefront"
      data-projnav="work"
      data-crumbs="Projects>projects.html|Acme Storefront>project-overview.html|Board">
```

Add a page by copying an existing one's `<head>` and body attributes. A navigation change is made
once, in `shell.js`.
