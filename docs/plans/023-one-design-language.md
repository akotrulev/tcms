# 023 — One design language: a top bar instead of the rail, one page strip, the board — plan (2026-10-04)

**Goal:** every authenticated screen is drawn in one language. A 48px top bar replaces the rail.
Every page opens with one sticky page strip: trail, title, views, filter, actions. Surfaces climb one
ladder (canvas, panel, card, overlay) on Tailwind's own radius scale. The Work board becomes calm
panels of cards whose face leads with identity and ends with the card's test result. A project gets
a colour, which its key chip carries; it never fills a canvas. No route moves. The project colour is
the plan's one contract change (§3a).

**Status:** A1 built 2026-10-04 (contract, both generated copies, both fixture factories; nothing run, per
the plan's single gate). Next: A2. The server still serves no `color` and answers `404` on `PUT …/color`
until A2. The design was approved on 2026-10-04 on a canvas:
<https://claude.ai/artifact/5LuW51YtnVNKWHZ4mV6Xbj>. `Main` is the board screen with the new shell,
and `CardStates` is the card in six states, in both themes. The canvas is the user's private artifact,
so §2–§6 restate every value a task needs. All three open questions were answered on 2026-10-04
(`## Answered questions`).

**Sequential. One agent at a time.** Every gate runs on shanks through `~/bin/tcms-remote`; never
run a local `npm run build` / `lint` / `test:run`.

**This plan gates once, in C1, and that includes the backend.** Every task before C1 writes its code
and its tests, commits and stops. **The cost, accepted here once: intermediate commits are ungated.** The tree is not known to
build at any task boundary before C1, so the plan cannot be stopped halfway and trusted. For the same
reason **no task pushes**. `ci.yml` delivers every push to master to the lab, and an ungated commit
must not reach it. C1 ends by telling the user the branch is ready to push.

| # | Task | Stack | Agent | Deliverable | Depends on | Budget |
|---|---|---|---|---|---|---|
| A1 | The contract: a project's colour | `ts` | `tooling-engineer` | `ProjectColor`, `Project.color` (required), `ProjectInput.color` (optional) and `PUT /projects/{projectId}/color` are in `openapi.yaml`; both generated copies are regenerated; both fixture factories carry `color` | — | ~85k |
| A2 | The backend: the column, the default, the endpoint | `be` | `be-java` | `V15__project_color.sql` adds and backfills `project.color`; a create assigns one when none is sent; `PUT …/color` sets it under the client endpoint's permission; tests cover all three | A1 | ~115k |
| K1 | The surface ladder, the radius scale, the project palette | `fe` | `fe-react` | `bg-bar`/`bg-raised` exist; no `rounded-[…]` literal is left in `frontend/src`, and a test fails on a new one; menus, popovers, dialogs and selects are overlays on the ladder; `--color-project-1…8` exist with a computed contrast check | A1 (`ProjectColor`) | ~130k |
| K2 | The page strip, the segmented control, the filter popover | `fe` | `fe-react` | `PageStrip` and `Segmented` exist; `PageHeader` renders a `PageStrip`; `TabStrip` draws as a segmented control; `FilterStrip` keeps its pickers behind a Filter popover | K1 | ~122k |
| N1 | The shell: top bar, launcher, project tabs, Create, user menu | `fe` | `fe-react` | No rail, drawer or footer is left. One 48px top bar serves every authenticated route, its switcher chips carry each project's colour, and `AppShell.test.tsx` asserts it | K2 (`shellFrame`, the trail now in `PageStrip`), K1 (`projectColorFill`) | ~154k |
| B1 | The card face | `fe` | `fe-react` | `BoardCardFace` draws §6.1. The whole card drags by pointer and the grip drags by keyboard | K1 | ~105k |
| B2 | The Work strip | `fe` | `fe-react` | `/projects/:id/work` opens with one strip: board picker (door badges), five views, Filter and Group in the strip. No keyboard banner, no Configure button, no `+ Add` | K2, B1 | ~120k |
| B3 | The board's columns | `fe` | `fe-react` | Columns are panels. A header carries count, WIP, linked-case health and the door `+`. Done collapses board-wide from its header | B2 | ~127k |
| P1 | Document-mode pages onto the strip; the top-strip slot retired | `fe` | `fe-react` | Case detail, case editor, case history, execution, proposal, doc editor, docs, help and 404 open with `PageStrip`; `app/topStripSlot.tsx` is deleted | K2, N1 | ~132k |
| P2 | Index and analytics pages: views into the strip, toggles onto `Segmented` | `fe` | `fe-react` | Every `TabStrip` sits in its page's strip. The five bespoke toggles here and Proposals' Radix tabs are `Segmented` or restyled `ui/tabs`. Case search uses `FilterStrip` | K2, P1 | ~150k |
| P3 | Work, people and project pages | `fe` | `fe-react` | The six bespoke headers in this family use `PageHeader`. The Projects page shows coloured key chips. Panels sit on the ladder (§7.3) | K2, N1 (`ProjectKeyChip`) | ~126k |
| P4 | Settings, admin, account, imports, audit, clients, billing | `fe` | `fe-react` | `SettingsSubnav` is a segmented row in the strip; Account security uses `PageHeader`; the audit filter row is a `FilterStrip`; Settings → General sets the project colour; panels sit on the ladder (§7.4) | K2, A2 (`PUT …/color`), N1 (`ProjectKeyChip`) | ~124k |
| C1 | Help copy, the specs, the gate, the walk | `fe` | `fe-react` | Help and specs describe the new shell and the colour. The whole gate, backend included, is green on shanks. The walk is recorded or listed as unverified | every task above | ~162k |

## Close-out — every task

Before you stop, in this order, in this file:

1. **As built** — append a dated `**As built (YYYY-MM-DD):**` bullet to your task block: what differed
   from the plan, the ids and names you assigned, what is built but unverified, and the `file:line` or
   command output that proves the deliverable exists. Never edit an earlier note.
2. **Decisions** — where you built something differently from the plan, append a dated `D<n>` to
   `## Decisions` with both readings. A divergence is recorded, never absorbed.
3. **The blocks below yours** — rewrite, in place, every prompt block whose assumptions your work
   changed (a renamed file, a changed signature, a moved gate), and write any block left
   `Blocked on <your id>` from what you now know.
4. **Your own block** — delete it, and update the line above the blocks that says which have landed.
   Stopping mid-task: keep it, with a `Picking up from:` line. Blocked: keep it, naming the blocker and
   its owner.
5. **Status** — rewrite the header `**Status:**` line: what is built, what is next, what is built but
   unverified.
6. **Filing** — if your deletion left no blocks, rewrite `**Status:**` one final time (what the plan
   delivered, what is still unverified) and `git mv` this plan into `docs/plans/implemented/`, same
   name and number — a `solution-planner` pack moves its whole initiative folder.
7. **Commit** — the code, its tests and these plan edits together, in one commit.

## Prompts — one per task

Run them in order, one session each. Each block is complete on its own. Every gate runs on shanks;
only C1 runs one. No prompt pushes, and none starts the local stack. C1 may *use* a stack that is
already running and must not start one.

A1 has landed; every block below is live.

---

### A2 — the backend: the column, the default, the endpoint (be-java, be)

````
You are `be-java`. Task A2 of docs/plans/023-one-design-language.md: give `project` a colour column,
backfill it for every existing project, assign one on create when none is sent, and serve
`PUT /projects/{projectId}/color`.

Deliverable: `V15__project_color.sql` adds and backfills `project.color`; a create assigns one when
none is sent; `PUT …/color` sets it under the client endpoint's permission; tests cover all three.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `./gradlew build`,
`./gradlew test` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Tenancy", "Backend", "Working agreement" (a required Spring property has no colon);
   then docs/specs/tenancy-invariants.md, the two silent RLS traps.
2. The plan: §3a, D17, task A2, and "Close-out — every task".
3. A1's "As built" note, and the `ProjectColor` / `PUT /projects/{projectId}/color` text it landed in
   docs/specs/openapi.yaml.
4. `ls backend/src/main/resources/db/migration/`. The next version is V15 unless something has
   landed since; take the next free number and record it. Then V13__three_boards_and_blocking.sql:
   how it wrote rows for every existing company under FORCE ROW LEVEL SECURITY.
5. backend/src/main/java/com/tcms/project/: `Project.java`, `ProjectCreateService.java`,
   `ProjectController.java`, and the class serving `PUT /projects/{projectId}/client`
   (grep `"/client"`), with its service and its test — the pattern you copy.
6. docs/specs/2026-07-30-tcms-erd.md — the `project` table's section.
7. docs/specs/2026-07-30-tcms-roles-permissions-naming.md §A3 — the permission the client endpoint
   checks. Use the same key; never a role name.

Build (the bullets of task A2, in order).

Traps:
- Under FORCE ROW LEVEL SECURITY, a backfill `UPDATE project …` with no company set updates zero rows,
  fail-closed and silent, and the `SET NOT NULL` after it then fails on a non-empty database. Do it
  the way V13 did for existing companies, and test it against a database that already holds two
  companies' projects.
- `hibernate.ddl-auto: validate`: the entity's column name, type and nullability must match the
  migration exactly, or the context will not boot.
- An unknown colour is a `400` with a field error on `color`, raised before the database's CHECK
  constraint could answer `500`.
- A repository interface carries `@Transactional(readOnly = true)`, or its derived reads return zero
  rows.

Commit: `feat(project): a project has a colour (023 A2)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### K1 — the surface ladder and the radius scale (fe-react, fe)

````
You are `fe-react`. Task K1 of docs/plans/023-one-design-language.md: put the surface ladder and the
radius scale into the token system and the overlay primitives, so every later task draws with them.

Deliverable: `bg-bar` and `bg-raised` resolve in both schemes; `grep -rnE "rounded(-[trbl]{1,2})?-\[" frontend/src | grep -v '\.test\.'`
returns nothing, and `tokenSystem.test.ts` fails on a new literal; every `components/ui` overlay is
`rounded-xl bg-raised shadow-e3` and every item `rounded-md`. `--color-project-1…8` are declared in
both palettes, `lib/projectColors.ts` maps every `ProjectColor`, and a computed check holds white text
at ≥ 4.5:1 on each.

House rules for this plan: work on the current branch (`git rev-parse --abbrev-ref HEAD`), never
create one; no subagents; stage only the files you touched (`git add <paths>`, never `-A`); do not
push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Frontend" and "Test-harness rules".
2. The plan: §2 (A3, A4, A7), §3a, §5.1, §5.2, §5.3, D4, D5, D17, task K1, and "Close-out — every
   task". Then A1's "As built" note (the `ProjectColor` type it generated).
3. frontend/src/index.css — the `@theme inline` block, both palettes, and the deleted-scale note
   above `--leading-body` (it is why this plan adds no radius token).
4. frontend/node_modules/tailwindcss/theme.css — the `--radius-*` lines only. Confirm xs 2 · sm 4 ·
   md 6 · lg 8 · xl 12 px before you run the codemod; if they differ, stop and record it as a D<n>.
5. frontend/src/app/tokenSystem.test.ts — the "lines and washes are tokens, not literals" describe;
   its file walker is the pattern for the new check.
6. frontend/src/components/ui/{dropdown-menu,select,popover,dialog,command,tooltip,tabs}.tsx and
   frontend/src/components/kit/{Badge,Chip,Banner,FigurePanel}.tsx.
7. frontend/src/app/statusPalette.test.ts — how it computes contrast per scheme (the pattern for
   the project palette's check); frontend/src/lib/statusColors.ts (`labelFill`, the pattern for
   `projectColorFill`); frontend/src/lib/labels.ts (the `satisfies Record<Union, string>` idiom).

Build (the bullets of task K1, in order):
- index.css: `--color-surface-bar` in both palettes (light `#ffffff`, dark `#0c1119`); in
  `@theme inline` add `--color-bar: var(--color-surface-bar)` and
  `--color-raised: var(--color-surface-raised)`; `--color-project-1…8` per §3a's table, in both
  palettes.
- `lib/projectColors.ts` (`projectColorFill`) and `projectColorLabels` in `lib/labels.ts`, both
  `satisfies Record<ProjectColor, string>`; the white-on-each ≥ 4.5:1 check beside
  `statusPalette.test.ts`'s, in both schemes. If a value fails it, darken it and record the value
  you used.
- Every hand-written `Project` literal in frontend tests gains `color` (A1 changed the type):
  `grep -rln "myRole:" frontend/src --include='*.test.tsx' --include='*.test.ts'`.
- The codemod (§5.2's table) over `frontend/src/**/*.{ts,tsx}`, as one `perl -pi` pass, then
  `git diff --stat` and spot-read five files.
- The computed check in tokenSystem.test.ts: no `rounded(-side)?-[` literal in any non-test file
  under `src/`, with a message naming §5.2's role table.
- The overlay and control primitives per §5.3; Badge `rounded-sm`, Chip `rounded-full`, Banner
  `rounded-lg`, FigurePanel `rounded-xl`.

Traps:
- A token declared outside `@theme` under a Tailwind name shadows the utility. This task adds no
  radius token at all; it uses Tailwind's scale, and the check is what keeps it that way.
- The codemod is literal for literal. Do not hand-edit the 191 sites, and do not "improve" a role
  while you are there. Role fixes are P1–P4's.
- `bg-raised` is new. Nothing has used `surface-raised` as a utility before, so the bridge test will
  report a typo as an undeclared property. Read its message rather than guessing.
- `--rail-width` and `--header-height` are N1's, not yours.
- `tokenSystem.test.ts`'s "no component hardcodes a palette hue" check means the project hues live
  only in `index.css`. `projectColorFill` returns `var(--color-project-n)`, never a hex.

Commit: `feat(design): the surface ladder, the radius scale and the project palette (023 K1)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### K2 — the page strip, the segmented control, the filter popover (fe-react, fe)

````
You are `fe-react`. Task K2 of docs/plans/023-one-design-language.md: build the three shared pieces
every screen will open with. They are `PageStrip` (and `PageHeader` on top of it), the segmented
control, and `FilterStrip` with its pickers behind a popover.

Deliverable: `PageStrip` and `Segmented` exist with tests; `PageHeader` renders a `PageStrip` and
keeps every testid and duty; `TabStrip` draws on the segment helpers; `FilterStrip`'s pickers sit in a
popover opened by `${testid}.Open`; `openFilters` exists.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Frontend" and "Test-harness rules" (contract fixtures, `createTestQueryClient`, the
   cited-files rule).
2. The plan: §2 (A2, A5, A8, A9), §4, §5.4, D2, D3, D6, task K2, and "Close-out — every task".
3. K1's "As built" note in task K1 (the token names it landed).
4. frontend/src/components/PageHeader.tsx (whole file — `PageHeader` and `Panel`, and every testid
   they emit; keep each one).
5. frontend/src/components/TabStrip.tsx, frontend/src/components/kit/FilterStrip.tsx,
   frontend/src/components/kit/Chip.tsx, frontend/src/components/kit/index.ts,
   frontend/src/components/ui/popover.tsx.
6. frontend/src/app/routeMeta.ts — `Crumb`, `CrumbContext`, `crumbsFor()`, and three `ROUTE_META`
   entries (a section root, a record page, an outside-the-project page) to see what the first two
   crumbs link to. Then frontend/src/app/ShellBreadcrumbs.tsx, which your trail replaces.
7. frontend/src/components/kit/kitRules.test.tsx — the "filter strip" describe.

Build (the bullets of task K2, in order): `app/shellFrame.tsx`, `components/PageStrip.tsx`,
`PageHeader` onto it, `Panel` restyled, `components/kit/Segmented.tsx` with its exported class
helpers, `TabStrip` onto them, `FilterStrip`'s popover, `src/test/filters.ts`'s `openFilters`, and
the tests listed in the task.

Traps:
- `PageStrip` renders an `<h1>` only when it is given a `title`. A document-mode page whose
  `DocumentTitle` is already the h1 passes no title, and two h1s is the defect to avoid.
- The trail drops the crumb linking to `/` and the crumb linking to the project root, because the top
  bar states both. It also drops the last crumb, which is the page the title already names. Derive
  this from what `ROUTE_META` produces, not from string positions.
- Moving the pickers into a Radix `Popover` takes them out of the DOM until it opens. Every existing
  test that reaches a picker directly breaks. Fix only kitRules.test.tsx here; the consumers' tests
  are B2's, P2's and P3's (listed in "Not in K2").
- `TabStrip` loses its `mb-3`: from P2 on it sits inside the strip. Until then it draws flush, which
  is expected.

Commit: `feat(design): the page strip, the segmented control and the filter popover (023 K2)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### N1 — the shell (fe-react, fe)

````
You are `fe-react`. Task N1 of docs/plans/023-one-design-language.md: replace the rail, the drawer,
the 52px header and the footer with one 48px top bar. The bar holds the launcher, the project
switcher, the project tabs with More, search, Create, My work, theme and the user menu.

Deliverable: no rail, drawer, hamburger, collapse toggle or footer remains. Every authenticated
route has one 48px top bar per §3. The switcher's chips carry each project's colour.
`AppShell.test.tsx` asserts the launcher's gates, the tabs and More, Create's disabled-with-reason
items, the user menu with the version, and the switcher.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Frontend", "Test-harness rules".
2. The plan: §2 (A1, A6, A7), §3 (all of it), §3a, D1, D13, D14, D17, task N1, and "Close-out —
   every task".
3. K2's "As built" note (the `shellFrame` export and the `PageStrip` trail), and K1's
   (`lib/projectColors.ts`'s `projectColorFill`).
4. frontend/src/app/AppShell.tsx — whole file. Keep `useNavGates`, `visibleEntries` and
   `useActiveProject` as they are; they are the gate behaviour that must not drift.
5. frontend/src/app/nav.ts — the interfaces and `NAV`.
6. frontend/src/app/AppShell.test.tsx — what it asserts, so the rewrite keeps every gate assertion.
7. frontend/src/app/CommandPalette.tsx — the trigger only. Then the `TopBar.` and `Nav.` references
   in frontend/src/app/CommandPalette.test.tsx.
8. frontend/src/features/account/AccessPage.tsx — its copy that says "rail" or "sidebar".

Build (the bullets of task N1, in order).

Traps:
- `nav.ts`'s zones stay the data model. `places.ts` (the palette's Places) and `AccessPage` read them
  unchanged; you add `placement` and `hint` and change no existing field.
- A gated entry is ABSENT in the launcher exactly as it was absent in the rail, and an ungated one is
  listed for everybody. The nine recorded exceptions do not change.
- The project tabs, More and Create render only when a project is resolved — the same rule as the old
  project zone (`useActiveProject`).
- Use the app's breakpoint contract (`sm` 600, `md` 900, `lg` 1200), never an arbitrary one.
- Deleting `app/ShellBreadcrumbs.tsx` (and later files) reddens `citedFiles.test.ts` if any comment
  still names it: `grep -rn "ShellBreadcrumbs" frontend/src docs/specs/*.yaml backend/src`.
- `TopStripProvider` stays mounted and `TopStripTarget` goes. The four pages that use `TopStripSlot`
  then render their content in place until P1 moves them, which is expected.
- The key chip's text is white on `projectColorFill(project.color)`. K1's check holds that contrast,
  so do not tint the text by scheme.

Commit: `feat(shell): one top bar instead of the rail (023 N1)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### B1 — the card face (fe-react, fe)

````
You are `fe-react`. Task B1 of docs/plans/023-one-design-language.md: redraw the board card per
§6.1. Its face leads with key, type and assignee, carries labels as dot-and-name, and ends with its
linked-case result as a 3px edge bar. The whole card drags by pointer; the grip drags by keyboard.

Deliverable: `BoardCardFace` draws §6.1. `BoardCardFace.test.tsx` covers:
  - the three linked-case zeroes;
  - priority shown for CRITICAL/HIGH only;
  - the edge bar absent at `total === 0`;
  - the grip as a named, focusable keyboard activator;
  - a drag that began on the title not navigating.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules".
2. The plan: §2 (A10), §6.1, D7, D9, task B1, and "Close-out — every task".
3. frontend/src/features/board/BoardCardFace.tsx (whole) and BoardCardFace.test.tsx.
4. frontend/src/features/board/linkedCases.ts — the three zeroes; your copy must keep all three.
5. frontend/src/components/kit/ResultBar.tsx — its props and the untested-is-the-empty-track rule.
6. frontend/src/features/board/MoveToMenu.tsx (props only), ReadinessSignals.tsx (props only),
   frontend/src/features/tracker/AiEligibleMarker.tsx (props only), frontend/src/lib/labels.ts
   (`priorityLabels`), frontend/src/lib/statusColors.ts (`labelFill`).
7. frontend/src/features/board/ProjectBoardView.tsx — `useSensors` and `<DragOverlay>` only.
8. @dnd-kit/core's `useDraggable` — `setActivatorNodeRef` and the shape of `listeners`. Query
   context7 for the installed version rather than recalling it.

Build (the bullets of task B1, in order).

Traps:
- After a pointer drag that began on the title, the browser still fires `click` on the link and
  navigates away. Suppress the click that ends a drag, and test it.
- The grip stays in the tab order with an accessible name, even when it is only visible on hover or
  focus.
- A card with `linkedCases: null` draws no cases row and no edge bar (not served). `total === 0`
  draws "no cases linked" and no bar. A summary with nothing executed reads "0/n run", never failing.
- Priority shows for CRITICAL and HIGH only. A colour is never the only statement: the word is drawn.

Commit: `feat(board): the card face — identity first, the test result last (023 B1)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### B2 — the Work strip (fe-react, fe)

````
You are `fe-react`. Task B2 of docs/plans/023-one-design-language.md: give `/projects/:id/work` one
strip. It holds the board picker (with door badges), the five views, and each view's Filter (plus
Group on the board) in the strip's actions. The keyboard banner, the Configure button, the GroupBy
select and `+ Add` go.

Deliverable: `/projects/:id/work` opens with one strip per §6.2: the board picker with door
badges, five view links, and each view's Filter (plus Group on the board) in the strip's actions.
`.Add`, `.GroupBy`, `.Configure` and `.KeyboardNote` are gone, and the board's foot line carries the
keyboard sentence. WorkTablePage, ProjectBoardView and BacklogView tests assert it.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules"; filter state lives in the URL.
2. The plan: §2 (A8, A9), §4, §6.2, D6, D16, task B2, and "Close-out — every task".
3. K2's and B1's "As built" notes.
4. frontend/src/features/tracker/WorkTablePage.tsx — the header, the view switcher, the Table view's
   FilterStrip, `useBoardDoors`.
5. frontend/src/features/board/ProjectBoardView.tsx — from the top of the returned JSX down to the
   `{rejected && …}` banner, and the count line under the board.
6. The FilterStrip call sites in frontend/src/features/board/BacklogView.tsx and
   frontend/src/features/releases/CalendarView.tsx.
7. The tests that reach what you move: WorkTablePage.test.tsx (`.Add`, `.View.Board.*` and its
   badge, the filters), ProjectBoardView.test.tsx (`.GroupBy`, `.Configure`, `.KeyboardNote`,
   `.Filter.*`), BacklogView.test.tsx (`.Filter.*`). Use frontend/src/test/filters.ts's
   `openFilters` from K2.

Build (the bullets of task B2, in order).

Traps:
- A board is not a filter. Clearing filters keeps `?view` and `?board` (`useWorkFilter` owns this).
  Choosing a board in the picker keeps every other parameter.
- The door badge counts the first column's cards, never a total. A board with no answer yet shows no
  number rather than 0.
- The strip's actions slot is a local portal with an in-place fallback. A view rendered by a test
  without `WorkTablePage` must still draw its Filter.
- Grouping costs `board:configure` and is disabled with its reason. It is not hidden.

Commit: `feat(work): one strip for the Work page (023 B2)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### B3 — the board's columns (fe-react, fe)

````
You are `fe-react`. Task B3 of docs/plans/023-one-design-language.md: draw columns as panels. Each
header carries count, WIP, the column's linked-case health and the door `+`. Done collapses
board-wide from its own header.

Deliverable: columns are panels per §6.3. `ColumnHeader` draws count, WIP, health
(`sumLinkedCases`) and the door `+` on the first-status column. Done collapses board-wide from
`${testId}.DoneToggle`. ProjectBoardView tests assert the health row's three zeroes, the `+` placement
and the board-wide Done.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules".
2. The plan: §6.3, §6.4, D8, D10, D11, D12, task B3, and "Close-out — every task".
3. B1's and B2's "As built" notes.
4. frontend/src/features/board/ProjectBoardView.tsx — the board grid, `lanes`, `doneOpen`,
   `laneOrder`/`stepColumn`, `countIn`.
5. frontend/src/features/board/ColumnHeader.tsx (whole).
6. frontend/src/features/board/Swimlane.tsx — the cell grid, the band bar, the sub-bar, the Done
   strip.
7. frontend/src/features/board/linkedCases.ts and frontend/src/components/kit/ResultBar.tsx.
8. The workflow read in frontend/src/api/queries/ (the hook WorkTablePage uses for
   `workflow.data.statuses`) and `BoardConfig.columns[].statuses` in frontend/src/api/types.ts.
9. ProjectBoardView.test.tsx — the column-header, Done-strip and keyboard-walk describes.

Build (the bullets of task B3, in order).

Traps:
- The breach is the server's (`BoardContents.breaches`). It is counted project-wide and does not
  shrink under a filter. Keep the three WIP states and their titles exactly.
- The keyboard walk steps over bands using the first column that is not Done. A collapsed Done is one
  cell per lane; do not let it become a stop.
- The door `+` is drawn only on the column that claims the workflow's first status by position. A
  create enters that status (`IssueInput` has no status field). A board with no such column draws no
  `+`.
- The health sums card summaries and skips `null` ones. A column whose cards are all `null` draws no
  health row at all. The title says the count is per card link.

Commit: `feat(board): columns as panels, test health in the header, Done from its header (023 B3)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### P1 — document-mode pages onto the strip (fe-react, fe)

````
You are `fe-react`. Task P1 of docs/plans/023-one-design-language.md: move the nine document-mode
pages onto `PageStrip` and delete `app/topStripSlot.tsx`.

Deliverable: the nine pages in §7.1 open with `PageStrip`.
`grep -rn "topStripSlot\|TopStripSlot\|TopStripTarget\|TopStripProvider" frontend/src` returns
nothing, and `app/topStripSlot.tsx` is deleted.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules" (the cited-files rule bites here).
2. The plan: §2 (A2, A4), §4, §7.1, D2, task P1, and "Close-out — every task".
3. K2's and N1's "As built" notes.
4. frontend/src/components/PageStrip.tsx and frontend/src/app/HeaderActions.tsx.
5. For each page in §7.1's table, the region the table names (line numbers drift, so locate by
   content). Then each page's test, searching for `Nav.TopStrip`, `TopStripSlot` and heading
   queries.

Build (the bullets of task P1, in order).

Traps:
- `DocumentTitle` is already the h1 on CaseDetail, Execution and Proposal. Those strips pass no
  `title`.
- The case editor's Save and draft status are its commit bar (design language rule 1). They move into
  the strip's actions. They never go into a footer.
- `grep -rn "topStripSlot\|TopStripSlot\|TopStripTarget\|TopStripProvider" frontend/src` must
  return nothing when you finish, comments included, or `citedFiles.test.ts` reddens the packages
  job.

Commit: `feat(design): document-mode pages open with the strip; the top-strip slot retired (023 P1)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### P2 — index and analytics pages (fe-react, fe)

````
You are `fe-react`. Task P2 of docs/plans/023-one-design-language.md: put every `TabStrip` into its
page's strip. Move the bespoke toggles onto `Segmented`, or onto `ui/tabs` where a toggle swaps
panels. Give case search a `FilterStrip`, and fix the panel roles in these screens.

Deliverable: every `TabStrip` sits in its page's `PageHeader views`. The toggles in §7.2's table
(FeatureImport, SuiteHistory, RunDetail, Analytics) and Proposals' Radix tabs are `Segmented`. Plan
statistics is `ui/tabs`. Case search's input is a `FilterStrip search`. CaseFilterBar's tests reach
pickers through `openFilters`.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules".
2. The plan: §2 (A3, A4, A8, A9), §5.5, §7.2, D6, D15, task P2, and "Close-out — every task".
3. K2's and P1's "As built" notes.
4. frontend/src/components/kit/Segmented.tsx, frontend/src/components/TabStrip.tsx,
   frontend/src/components/ui/tabs.tsx.
5. For each page in §7.2's table, the region it names (locate by content), and its test, searching
   for the toggle's testids and `getByRole('button', { pressed` queries.
6. frontend/src/features/cases/CaseFilterBar.tsx and its test. Use `openFilters` from
   frontend/src/test/filters.ts.

Build (the bullets of task P2, in order).

Traps:
- A toggle that FILTERS a list is `Segmented` (buttons, `aria-pressed`). A control that swaps PANELS
  with tab semantics is `ui/tabs`. A choice that is an ADDRESS is `TabStrip` (links). Keep each
  control's semantics; change only its look.
- `CaseFilterBar` puts facet pickers (their own popovers) inside the Filter popover. Check that
  Escape closes the inner one first and that focus returns to the trigger.
- The Proposals screen has two strips today: TabStrip `cases` plus Radix Waiting/Accepted/Rejected.
  The first goes in `views`; the second stays a page-level `Segmented` under the strip.

Commit: `feat(design): index and analytics pages — views in the strip, one segmented control (023 P2)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### P3 — work, people and project pages (fe-react, fe)

````
You are `fe-react`. Task P3 of docs/plans/023-one-design-language.md: put the six bespoke headers in
this family onto `PageHeader`, and the panels and tiles of the work, people and project screens onto
the ladder.

Deliverable: IssueNew, IssueDetail, MyWork, TeamBoard, OrgChart and the Staffing range use
`PageHeader`/`Segmented` per §7.3. MyWork has no local `Panel`. AwaitingYou's tiles and
ProjectOverview's sections sit on the ladder. The Projects page's tiles carry each project's coloured
key chip. DirectoryFilters' tests use `openFilters`.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules".
2. The plan: §2 (A3, A4), §3a, §5.5, §7.3, task P3, and "Close-out — every task".
3. K2's "As built" note; frontend/src/components/PageHeader.tsx (`PageHeader`, `Panel`); N1's
   `app/ProjectSwitcher.tsx`, for the key chip you reuse rather than redraw.
4. For each page in §7.3's table, the region it names (locate by content), and its test's heading
   and testid queries.
5. The FilterStrip consumers' tests in this family: frontend/src/features/staffing/ (DirectoryFilters)
   and frontend/src/features/releases/CalendarView.test.tsx if it reaches the strip. Use
   `openFilters`.

Build (the bullets of task P3, in order).

Traps:
- `PageHeader` emits `<title>` and announces the page on arrival. A bespoke header you replace must
  not keep its own `<title>` element too.
- MyWorkPage has a local `Panel` function. Replace it with the shared one; do not export a second.
- The panel-role grep finds candidates, not verdicts. A bordered box that is a FORM FIELD group or a
  TABLE wrapper is not a panel. Leave those.

Commit: `feat(design): work, people and project pages on the strip and the ladder (023 P3)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### P4 — settings, admin, account, imports, audit, clients, billing (fe-react, fe)

````
You are `fe-react`. Task P4 of docs/plans/023-one-design-language.md: make the settings subnav a
segmented row in the strip, move Account security onto `PageHeader`, make the audit filter row a
`FilterStrip`, and put the panels of the settings, admin and account screens onto the ladder.

Deliverable: `SettingsSubnav` renders in its 12 hosts' `PageHeader views` on the segment
helpers. AccountSecurity uses `PageHeader`. The audit filter row is a `FilterStrip` with its pickers
in the popover. GeneralSettings' sections are `Panel`s, one of them the project colour picker, which
saves through `PUT /projects/{projectId}/color`. The panel-role pass is clean over §7.4's folders.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push. **Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`,
`test:run`, `test:smoke`, `tsc` or any targeted test, locally or on shanks.

Read, in this order:
1. CLAUDE.md — "Test-harness rules".
2. The plan: §2 (A3, A4, A8, A9), §3a, §5.5, §7.4, D6, D17, task P4, and "Close-out — every task".
   Then A2's "As built" note (the permission the endpoint checks).
3. K2's "As built" note; frontend/src/components/kit/Segmented.tsx (`segmentTrackClass`,
   `segmentItemClass`); frontend/src/components/PageHeader.tsx.
4. frontend/src/features/settings/SettingsSubnav.tsx and frontend/src/features/settings/useSettingsSections.ts.
5. For each page in §7.4's table, the region it names (locate by content), and its test.
6. frontend/src/api/queries/ — the projects file and `keys.ts` (add the mutation to the existing
   factory, never a second one), and the client mutation `PUT …/client` uses, as the pattern.

Build (the bullets of task P4, in order).

Traps:
- The subnav's sections are filtered by permission (`useSettingsSections`). A section the caller
  cannot read is absent, as before.
- The audit page serves two scopes, and its subnav shows only at project scope. Keep that.
- A refused screen renders read-only with the permission's id and a holder's name (rule 7). The strip
  does not change that.
- The colour picker is a real radio group: eight visually hidden `<input type="radio">` with labelled
  swatches, keyboard-operable, disabled with its reason without the permission. The swatch's name
  comes from `projectColorLabels`; colour is never its only statement.
- A saved colour must repaint the switcher at once: invalidate the projects list key the switcher
  reads.

Commit: `feat(design): settings, admin and account on the strip and the ladder (023 P4)`.
Close out per the plan's "Close-out — every task" before you stop.
````

### C1 — help copy, the specs, the gate, the walk (fe-react, fe)

````
You are `fe-react`. Task C1 of docs/plans/023-one-design-language.md, the plan's last task: make the
user guide and the specs describe the shell that shipped, run the plan's only gate on shanks and fix
what it finds, walk the screens if a stack is already running, and close the plan.

Deliverable: the user guide's navigation section describes the top bar. The design-language spec
carries §10 (A1–A11) with §4 struck through. The IA proposal's rail decisions are marked superseded
by 023. The ERD and `docs/features.md` carry the project colour. §8's seven gate commands, backend
included, are green on shanks. The walk is recorded, or its screens are listed as unverified. This
plan is in `docs/plans/implemented/`.

House rules for this plan: current branch only, never create one; no subagents; stage only your
files; do not push — end by telling the user the branch is ready to push.

Read, in this order:
1. CLAUDE.md — "Run the gates on shanks", "What CI does", "Test-harness rules"; then
   docs/specs/shanks-runner-details.md, "Five things that bite".
2. The plan: §2, §3, §8, every `**As built**` note in §9, `## Decisions`, task C1, and
   "Close-out — every task".
3. frontend/src/features/help/sections.tsx — the navigation section (search "rail", "zone",
   "header") and the run-schedules sentence (search "no entry in the navigation"). Then
   frontend/src/features/help/TokenSteps.tsx (search "sidebar").
4. docs/specs/2026-08-28-design-language.md §2 and §4; docs/specs/2026-09-06-navigation-ia-redesign-proposal.md
   §1 and §3 (the headings, the R1 paragraph, and the rail-switcher paragraph);
   `grep -n "rail\|sidebar" docs/features.md`; and the `project` table in
   docs/specs/2026-07-30-tcms-erd.md (A2 added the column row; check it).

Build (the bullets of task C1, in order), then the gate, in this order:
  ~/bin/tcms-remote sync
  ~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
  ~/bin/tcms-remote packages 'npm run build'
  ~/bin/tcms-remote packages 'npm run test:run --workspaces'
  ~/bin/tcms-remote frontend 'npm run build'
  ~/bin/tcms-remote frontend 'npm run lint'
  ~/bin/tcms-remote frontend 'npm run test:run'
Fix what it finds. A failure may belong to any task in this plan, not only to yours. A backend
failure is A2's code: load `be-java` and `be-java-database-code` before you touch it. Re-run from the
first red command until all seven are green.

Traps:
- `npm run build` is the gate, not `tsc --noEmit`; `tsc -b` uses the stricter config.
- The packages run is not optional. `citedFiles.test.ts` scans frontend comments for deleted files
  (`ShellBreadcrumbs.tsx`, `topStripSlot.tsx`). `tierClaims.test.ts` fails a comment claiming somebody
  LACKS a permission §A3 grants. `guideCopies.test.ts` pins `sections.tsx`.
- A backend `BUILD FAILED` with `Could not write XML test results` and zero assertion failures is the
  fd limit, not a red suite. Count `grep -c ' FAILED$'` (excluding `> Task` lines) before believing
  it.
- The packages run also checks the contract: `schemaFreshness` fails if either generated copy is
  older than `openapi.yaml`, and `contractPairing` fails if the two copies' shared region drifted.
- The walk uses a stack that is ALREADY up (`curl -sf localhost:8080/actuator/health` and
  `curl -sf localhost:5173`). If either is down, do not start one: list the screens as unverified.

Commit: `docs(design): 023 close-out — help, specs, gate (023 C1)`.
Close out per the plan's "Close-out — every task" before you stop. Your deletion leaves no blocks, so
step 6 applies: final Status, then `git mv` this plan to docs/plans/implemented/.
````

---

## 1. What already exists, and what is missing

| # | Need | Today | Gap | Task |
|---|---|---|---|---|
| 1 | A top bar instead of the rail | `app/AppShell.tsx`: rail `<nav id="nav-rail">` at :236, drawer at :259, 52px header `TopBar` at :819–1035 | One 48px bar; rail, drawer, collapse and `COLLAPSED_RAIL_ROUTES` (:122) removed | N1 |
| 2 | Company and personal places in one menu | `NAV` zones `you`/`company` rendered as rail groups (`AppShell.tsx` :414–490) | Launcher menu over the same data, with a hint line per entry | N1 |
| 3 | Project places as tabs | `NAV` zone `project`, 11 rail rows | Six tabs plus More (`placement` on `NavItem`) | N1 |
| 4 | One Create | Work header's `+ Add` (`WorkTablePage.tsx` :279) | Create menu: Issue, Test case, Release | N1, B2 |
| 5 | Version and User guide without a footer | `AppFooter` (`AppShell.tsx` :1062) | In the user menu | N1 |
| 6 | One page strip on every screen | `PageHeader` on 53 pages (`mb-6`, h1 22px); 9 bespoke 19px headers; 4 `TopStripSlot` pages; 6 document-mode pages with only `<title>` | `PageStrip`; `PageHeader` on top of it; every page on one of the two | K2, P1–P4 |
| 7 | The trail in the strip | `ShellBreadcrumbs` in the header (`AppShell.tsx` :871) | Ancestors-only trail inside `PageStrip`; the header copy deleted | K2, N1 |
| 8 | One segmented control | `TabStrip.tsx` (links); 7 bespoke `aria-pressed`/tablist toggles; `SettingsSubnav` (12 pages); `ui/tabs` | One visual, three semantics (§5.4) | K2, P2, P4 |
| 9 | Filters behind one button | `FilterStrip` renders pickers inline (6 consumers); audit has a bespoke row; case search an ad-hoc input | Popover; search stays inline; chips stay inline | K2, B2, P2, P3, P4 |
| 10 | A surface ladder | `index.css` has `surface-raised` but no utility for it, and no bar surface | `bg-raised`, `bg-bar` | K1 |
| 11 | One radius scale | 191 `rounded-[…]` literals across 10 values; no check | Codemod onto Tailwind's scale plus a computed check; then role fixes per screen | K1, P1–P4 |
| 12 | Overlays on the ladder | `ui/*` content at `rounded-[8px] bg-panel`; items `rounded-sm` | `rounded-xl bg-raised shadow-e3`; items `rounded-md` | K1 |
| 13 | The card face | `BoardCardFace.tsx`: key row, title, badges; drag by handle only (:140) | §6.1 | B1 |
| 14 | The Work strip | `WorkTablePage.tsx` :257–363 header and switcher; `ProjectBoardView.tsx` :383–528 GroupBy, Configure, filters, keyboard banner | §6.2 | B2 |
| 15 | Columns as panels, header health, door `+`, Done | `ColumnHeader.tsx`, `Swimlane.tsx`, `ProjectBoardView.tsx` grid | §6.3, §6.4 | B3 |
| 16 | Help copy matches the shell | `help/sections.tsx` :85–126 describe the rail; :548 is stale; `TokenSteps.tsx` :66 says "sidebar" | Rewritten | C1 |
| 17 | The specs record the reversal | design-language §4 (rail collapse); IA proposal §1/§3/R1 | Amended, struck through with "→ superseded (023)" | C1 |
| 18 | One gate, one walk | — | C1 | C1 |
| 19 | A project has a colour | `Project` carries no colour (`openapi.yaml` `Project`, `ProjectInput`); no column on `project` | §3a: contract, column and backfill, endpoint, palette, the chip, the picker | A1, A2, K1, N1, P3, P4 |

## 2. The design language, amended

The eight rules of `docs/specs/2026-08-28-design-language.md` §2 all still hold. This plan adds
eleven, A1–A11, and C1 writes them into that spec as §10.

- **A1 — No rail.** One 48px top bar (§3) on every authenticated screen. Navigation is the bar's
  tabs and menus; the page below is the work.
- **A2 — One strip opens every page.** `PageStrip` (§4) sits directly under the top bar and is
  sticky beneath it. It holds the trail, the title, the views, the filter and the actions. Nothing
  else sits between the top bar and the content. This is rule 2's "a strip above", made literal.
- **A3 — The surface ladder.** The canvas is `bg-paper`. A panel (a column, a side panel, a section
  of a settings page) is `bg-panel border border-line rounded-xl`. A card on a panel (a board card, a
  tile) is `bg-raised border border-line rounded-lg shadow-e1`. An overlay (menu, popover, dialog,
  select content) is `bg-raised border border-line rounded-xl shadow-e3`. The top bar is `bg-bar`.
  `DocumentSurface` stays on `bg-paper` with no border: the document is the page.
- **A4 — Radius roles on Tailwind's own scale.** No `rounded-[…]` literal anywhere (§5.2):

  | Role | Utility | px |
  |---|---|---|
  | bar segment, meter | `rounded-xs` | 2 |
  | badge, tag, kbd | `rounded-sm` | 4 |
  | segment item, menu item, small icon button | `rounded-md` | 6 |
  | button, input, select, card, banner | `rounded-lg` | 8 |
  | panel, column, overlay | `rounded-xl` | 12 |
  | chip, avatar, count pill | `rounded-full` | — |

- **A5 — Type in the chrome.** Strip title 17px / 650 / `-0.015em`; trail 12px `text-ink-3`;
  subtitle 12.5px `text-ink-3`, `max-w-[74ch]`; panel heading 13px / 600. Document titles inside a
  `DocumentSurface` keep §3's 24–25px. Rule 6 (mono for identity and quantity) is unchanged.
- **A6 — Gutter.** 16px from `md`, 12px below it. ~~22px, the render kit's one spatial constant~~ →
  superseded by D14.
- **A7 — Menus.** Radix `DropdownMenu` or `Popover` only. A group heading is mono 10.5px uppercase
  `tracking-[.08em] text-ink-3`. An item is 13px `px-2.5 py-1.5 rounded-md`; the current item carries
  a check and `aria-current`/`aria-checked`. A footnote is 11.5px `text-ink-3` over a `border-t
  border-line`.
- **A8 — One segmented control.** Track `inline-flex gap-0.5 rounded-lg bg-wash-1 p-0.5`. Item
  `flex h-7 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium`. Active item
  `bg-raised text-ink font-semibold shadow-e1`; inactive `text-ink-3 hover:text-ink-2`. Three
  semantics share this one look (§5.4).
- **A9 — Filters behind one button.** A "Filter" button opens a popover holding every picker; its
  count is the number of applied filters. Search stays inline beside it. Applied filters stay visible
  as chips after the button. The OR-within / AND-across sentence is the popover's footnote.
- **A10 — The card.** One card shape on every board (§6.1). Identity first, test result last; labels
  are a 7px dot plus the name. No tilt, no coloured fill.
- **A11 — Colour.** The thesis is unchanged: steel only for actions and the current place; status hues
  only for data. No coloured canvases (D5). A label's colour appears only as its dot.

## 3. The shell

**What leaves:** the rail and its collapse toggle and preference (`tcms.nav.railCollapsed`), the
drawer and hamburger, `COLLAPSED_RAIL_ROUTES`, `AppFooter`, `CompanySlot` (it renders nothing; its
reservation note moves to the launcher's code comment), `ShellBreadcrumbs` (its job moves into
`PageStrip`), the `TopStripTarget` mount (the provider stays until P1), and the `--rail-width` token.
`--header-height` becomes `48px`.

**The bar**, `app/TopBar.tsx`:
`<header className="sticky top-0 z-20 flex h-12 shrink-0 items-center gap-1 border-b border-line bg-bar px-2.5">`.
From left to right:

| # | Element | File | Behaviour | testid |
|---|---|---|---|---|
| 1 | Launcher (`LayoutGrid`, quiet icon button, label "Your work and company") | `app/LauncherMenu.tsx` | `DropdownMenuContent align="start" className="grid w-[500px] grid-cols-2 gap-x-2.5 p-2"`. Left column: **You** (`NAV` zone `you`), then **Administration** (the folder's visible children). Right column: **Company** (the zone's visible entries). Each item is a two-line link: label 13px/500, then `hint` 11.5px `text-ink-3`. A section with no visible entries is not drawn. Footnote: "Only entries you can open are listed." plus a link "Your access" → `/account/access` | `Nav.Launcher`; items keep `Nav.Link.<Label>` |
| 2 | Wordmark `TCMS` → `/` | `TopBar.tsx` | mono 13px 600 `tracking-[.08em]`; hidden below `sm` | `Nav.Wordmark` |
| 3 | Divider | — | `h-5 w-px bg-line` | — |
| 4 | Project switcher | `app/ProjectSwitcher.tsx` | Button: key chip (`Identifier` strong, `h-[22px] rounded-sm px-1.5 text-[10.5px] text-white`, background `projectColorFill(project.color)`, §3a), name 14px/600 (hidden below `sm`), `ChevronDown`. Popover with a `Command` list: "Find a project" filter, rows of key chip + name, a check on the current one, then "All projects" → `/`. Choosing one navigates `/projects/:id`, as today. Resolution is `useActiveProject`, unchanged | `Nav.ProjectSwitcher` |
| 5 | Project tabs | `app/ProjectTabs.tsx` | `<nav aria-label="Project">` of `NavLink`s for the `NAV` project-zone entries with `placement: 'tab'`. Each tab is `flex h-8 items-center rounded-md px-2.5 text-[13px] font-medium whitespace-nowrap`; active is `bg-steel/12 text-ink font-semibold` plus `aria-current="page"`; inactive is `text-ink-3 hover:bg-wash-1 hover:text-ink-2`. Every tab except Work is `max-lg:hidden`. Then **More** (`DropdownMenu`): the `placement: 'more'` entries, plus the `placement: 'tab'` entries other than Work as `lg:hidden` items. More is labelled with the active entry's label when one of its entries is active, and is then drawn active | `Nav.ProjectTabs`, `Nav.Link.<Label>`, `Nav.More` |
| 6 | Spacer | — | `flex-1` | — |
| 7 | Search | `CommandPalette` trigger | `w-[280px] h-[34px] rounded-lg border border-line bg-field`, `Search` icon, placeholder "Search or jump to…", `⌘K` `kbd`. Below `md` it is an icon button with the same accessible name | unchanged |
| 8 | Create | `app/CreateMenu.tsx` | Primary `Button size="sm"` with `Plus` and "Create" (label hidden below `md`, `aria-label="Create"`). Items: **Issue** → `/projects/:p/work/new` (`issue:create`); **Test case** → `/projects/:p/cases/new`; **Release** → `/projects/:p/releases/new`. The last two use the same permission key their list pages' New buttons gate on, so read those call sites. Each item is disabled with its reason through `PermissionGate`, never hidden | `TopBar.Create`, `TopBar.Create.<Item>` |
| 9 | My work (`Inbox` icon link → `/my-work`, label "My work") | `TopBar.tsx` | No count (D13) | `TopBar.MyWork` |
| 10 | Theme toggle | `TopBar.tsx` | Unchanged behaviour | `TopBar.ThemeToggle` |
| 11 | Avatar → user menu | `app/UserMenu.tsx` | 32px round button showing initials (`bg-steel text-rail`, mono 11px 600; `aria-label="Account: <name>"`). The menu content is today's, unchanged, plus a last muted line `TCMS <Identifier>{__APP_VERSION__}</Identifier>` above Sign out | `TopBar.UserMenuButton` and every `TopBar.*Link` kept |

**The project tabs, More and Create** render only when a project is resolved, like the old project
zone.

**`nav.ts`.** `NavItem` gains `placement?: 'tab' | 'more'` (project zone: Work, Test cases, Test
plans, Test runs, Releases and Documents are `tab`; Overview, Analytics, Test reports, Team and
Settings are `more`) and `hint?: string` (the launcher entries). The hints:
- My work "Assigned to you, every project"
- Projects "Every project you can open"
- Org chart "Who reports to whom"
- Directory "Find a colleague"
- Capacity "Who is free, across projects"
- Teams & roles "Teams and what they hold"
- Clients "Companies you deliver for"
- Billing "Invoices and rates"
- API tokens "CI and agent credentials"
- Single sign-on "Identity providers"
- Company audit "Who changed what"

The zones, gates and `why` clauses do not change, so `app/places.ts` (the palette's Places) and
`features/account/AccessPage.tsx` keep working untouched. The header comment says that the zones are
now drawn as launcher sections and tabs, with the rail-specific arguments struck through
"→ superseded (023 D1)".

**The shell's frame.** `AppShell` provides `ShellFrameContext` (K2's `app/shellFrame.tsx`) with
`{ projectId, projectKey, projectName }` from `useActiveProject`, so `PageStrip` can draw a trail.

**Main.** `<main id="main-content" tabIndex={-1} className="flex-1 min-w-0 px-3 pb-6 md:px-4 outline-none">`.
It has no top padding: the strip brings its own. The root is `flex min-h-full flex-col bg-paper`.

**Responsive** (the app's contract only: `sm` 600, `md` 900, `lg` 1200):
- Below `lg`, the tabs fold into More.
- Below `md`, search and Create become icons.
- Below `sm`, the switcher shows the key only and the wordmark hides.
- At 390px the bar must not scroll sideways.

**Where this departs from the canvas:**
- No notifications bell, because no notification centre exists.
- No count on My work (D13).
- The project colour is on the key chip only, never behind the board (D5, D17).

## 3a. The project colour

Asked on 2026-10-04 and answered "add a colour field" (O2). It is the plan's one contract change, and
it follows 018 D-d's single-field precedent (`PUT /projects/{projectId}/client`).

**Contract (A1):**
- `ProjectColor` is a string enum: `project-1` … `project-8`. These are design-token names, as
  `Label.color` uses `label-1…10`, so a palette change never touches a stored value.
- `Project.color: ProjectColor`, **required**. Every project has one after A2's backfill.
- `ProjectInput.color?: ProjectColor`, optional. Absent means the server assigns one.
- `PUT /projects/{projectId}/color`, body `ProjectColorUpdate { color: ProjectColor }` with
  `required: [color]`, answers `200` with the `Project`. It answers `400` with a field error on `color`
  for a value outside the enum, `403` without the permission `PUT …/client` checks, and `404` for a
  project the caller cannot see. The description states the decision and its reason: a single field
  has no absence to misread, which is 018 D-d's argument.

**Backend (A2):**
- `V15__project_color.sql` adds `color text`. It backfills every existing project with
  `'project-' || (mod(abs(hashtext(key)::bigint), 8) + 1)`, across every company and despite FORCE
  RLS (A2's trap). Then it sets `NOT NULL` and adds `CHECK (color ~ '^project-[1-8]$')`.
- A create without a colour assigns `"project-" + (Math.floorMod(key.hashCode(), 8) + 1)`. The two
  formulas need not agree: each only has to be deterministic.
- The ERD's `project` row gains the column.

**Palette (K1).** Each value is declared in both palettes, as one deep fill carrying white text. The
same values serve both schemes, because the chip is white on colour either way:

| Token | Value | `projectColorLabels` |
|---|---|---|
| `--color-project-1` | `#2f6f8f` | Steel |
| `--color-project-2` | `#4b4f9e` | Indigo |
| `--color-project-3` | `#1f6f6a` | Teal |
| `--color-project-4` | `#2f6b45` | Green |
| `--color-project-5` | `#5f6a24` | Olive |
| `--color-project-6` | `#8a5a1b` | Amber |
| `--color-project-7` | `#9a3f2c` | Rust |
| `--color-project-8` | `#7a3f73` | Plum |

`lib/projectColors.ts`'s `projectColorFill` maps `ProjectColor → var(--color-project-n)` and
`satisfies Record<ProjectColor, string>`. A computed check holds `#ffffff` at ≥ 4.5:1 on every value;
K1 darkens any that fails and records it.

**Where it is drawn:**
- the switcher's chip and each row of the projects list (N1);
- the Projects page tiles (P3);
- Settings → General's picker (P4).

It is never a canvas, a column or a card fill (D5).

## 4. The page strip

`components/PageStrip.tsx` (K2):

```tsx
export interface PageStripProps {
  /** The page's name. Rendered as the page's <h1>; visually hidden when `lead` is given. */
  title?: ReactNode;
  /** Replaces the visible title — the Work page's board picker. */
  lead?: ReactNode;
  subtitle?: ReactNode;
  meta?: ReactNode;
  /** A TabStrip, a Segmented, or a ui/tabs list. */
  views?: ReactNode;
  /** Right side: HeaderActions-shaped. */
  actions?: ReactNode;
  /** Draw the ancestors trail. Default true. */
  trail?: boolean;
  testId: string;
}
```

**Markup.** The root is
`sticky top-12 z-10 -mx-3 mb-4 flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-paper px-3 py-2.5 md:-mx-4 md:px-4`.
Inside it, in order:
- a title block: the trail line, then `lead` or the h1 (`m-0 text-[17px] leading-tight font-[650] tracking-[-.015em] text-ink`), then subtitle and meta on one muted line;
- `views`;
- a `flex-1` spacer;
- `actions`.

There is no `<h1>` when `title` is absent.

testids: `${testId}` on the root, `${testId}.Title`, `${testId}.Trail`, `${testId}.Views`,
`${testId}.Actions`. `PageHeader` passes its own `testId`, so every existing `PageHeader` testid
survives.

**The trail.** It is `crumbsFor(pathname, frame)` (from `app/routeMeta.ts`, with K2's
`useShellFrame()` as the frame), minus:
- the crumb linking to `/`;
- the crumb linking to the project root `/projects/<id>`;
- the last crumb.

The top bar shows the first two, and the title is the last. It renders nothing when that leaves
nothing, and it is `hidden` below `md`. Its look is 12px `text-ink-3`; links `hover:text-steel`;
separators are `/` in `text-ink-4`.

**`PageHeader`** keeps its whole API (`title, documentTitle, subtitle, actions, meta, announcement,
testId`) and its duties (`<title>…· TCMS</title>`, the arrival announcement). It gains `views?` and
`lead?`, which it passes through, and renders a `PageStrip`. Its h1 drops from 22px to 17px.

**`Panel`** (same file) becomes `rounded-xl border border-line bg-panel`, with a title bar of
`px-4 py-2.5 text-[13px] font-semibold border-b border-line` and a body of `p-4` (`dense`: `p-3`).

**Document-mode pages** (P1) render `<PageStrip>` in place, at the top of their JSX. The four that
portalled into the header (`TopStripSlot`) put that content in `actions`. `app/topStripSlot.tsx` is
deleted (D2).

## 5. The kit

### 5.1 Tokens (K1)

| Where | Add |
|---|---|
| light palette | `--color-surface-bar: #ffffff;` |
| dark palette | `--color-surface-bar: #0c1119;` |
| `@theme inline` | `--color-bar: var(--color-surface-bar);` · `--color-raised: var(--color-surface-raised);` |
| both palettes | `--color-project-1` … `--color-project-8` (§3a's table) |

N1 does the `:root` layout change: `--header-height: 48px` and delete `--rail-width` (after
`grep -rn "rail-width" frontend/src`).

### 5.2 The radius codemod (K1)

The codemod is one pass, literal for literal, over `frontend/src/**/*.{ts,tsx}`:

| Literal (any side prefix `t-`, `l-`, `r-`, `b-` carried over) | → |
|---|---|
| `rounded-[1px]` | `rounded-xs` |
| `rounded-[3px]`, `rounded-[4px]` | `rounded-sm` |
| `rounded-[5px]`, `rounded-[6px]`, `rounded-[7px]` | `rounded-md` |
| `rounded-[8px]` | `rounded-lg` |
| `rounded-[10px]` | `rounded-xl` |

```bash
cd frontend && find src -name '*.ts*' -print0 | xargs -0 perl -pi -e '
  s/rounded(-[trbl]{1,2})?-\[1px\]/rounded$1-xs/g;
  s/rounded(-[trbl]{1,2})?-\[(?:3|4)px\]/rounded$1-sm/g;
  s/rounded(-[trbl]{1,2})?-\[(?:5|6|7)px\]/rounded$1-md/g;
  s/rounded(-[trbl]{1,2})?-\[8px\]/rounded$1-lg/g;
  s/rounded(-[trbl]{1,2})?-\[10px\]/rounded$1-xl/g;'
grep -rnE "rounded(-[trbl]{1,2})?-\[" src | grep -v '\.test\.' || echo clean
```

A value outside the table (`rounded-[2px]`, `rounded-[12px]`) is mapped by hand to the nearest role
and listed in K1's As built.

**The check** goes in `app/tokenSystem.test.ts`, in the "tokens, not literals" describe:
`it('no component spells an arbitrary radius')`. It walks `src/` non-test `.ts`/`.tsx` files and
fails on `/\brounded(?:-[trbl]{1,2})?-\[/`. Its message names this plan's §2 A4 table.

### 5.3 Overlays and controls (K1)

| File | Change |
|---|---|
| `ui/dropdown-menu.tsx` | Content: `rounded-xl border border-line bg-raised p-1.5 shadow-e3`. Item and checkbox/radio item: `rounded-md px-2.5 py-1.5 text-[13px]`. `DropdownMenuLabel`: A7's heading. Separator `my-1.5 bg-line` |
| `ui/select.tsx` | Content as the dropdown's; item `rounded-md` |
| `ui/popover.tsx` | `rounded-xl border border-line bg-raised shadow-e3` (padding stays the caller's `p-4` default) |
| `ui/dialog.tsx` | `rounded-xl … bg-raised shadow-e3` |
| `ui/command.tsx` | List items `rounded-md`; wrapper `rounded-xl` |
| `ui/tooltip.tsx` | `rounded-md` |
| `ui/tabs.tsx` | List and trigger take A8's track and item classes |
| `kit/Badge.tsx` · `kit/Chip.tsx` · `kit/Banner.tsx` · `kit/FigurePanel.tsx` | `rounded-sm` · `rounded-full` · `rounded-lg` · `rounded-xl` |

### 5.4 The segmented control and its three semantics (K2)

`components/kit/Segmented.tsx` exports:
- `segmentTrackClass` and `segmentItemClass(active: boolean)`, A8's strings;
- `Segmented<T extends string>({ label, options: { value: T; label: string; icon?: LucideIcon; testId?: string }[], value, onChange, testId })`.

`Segmented` renders `<div role="group" aria-label={label}>` around `<button type="button"
aria-pressed>` items. Export it from `components/kit/index.ts`.

| The choice is | Component | Semantics |
|---|---|---|
| an address (Cases · Suites · Proposals; Work's views) | `TabStrip` (or links using the class helpers) | links, `aria-current="page"` |
| a filter or a mode on this list (Waiting · Accepted · Rejected; scope toggles) | `Segmented` | buttons, `aria-pressed` |
| panels on this page with tab semantics (Plan statistics) | `ui/tabs` restyled | `tablist`/`tab`/`tabpanel` |

`TabStrip` keeps its props and `NavLink`s and draws with the helpers. It drops `mb-3`.

### 5.5 FilterStrip (K2), and the panel-role pass (P1–P4)

`FilterStrip` keeps its props (`search, pickers, applied, summary, onClear, className,
data-testid`). It renders one row, `flex flex-wrap items-center gap-2 text-[12.5px]`:
- `search`;
- when `pickers` is given, a `Popover` trigger: `Button variant="secondary" size="sm"` with
  `ListFilter`, "Filter" and, when `applied.length > 0`, a count pill
  (`rounded-full bg-steel px-1.5 font-mono text-[10.5px] leading-[15px] text-rail`). Its testid is
  `${testid}.Open`;
- one `Chip` per applied filter;
- "Clear filters" when `onClear` is given.

The `PopoverContent` is `align="end" className="w-[320px] p-3"`. It holds `pickers` in a
`flex flex-col gap-2.5` column whose selects are full width, and `summary` as A7's footnote.

`src/test/filters.ts` exports `openFilters(user, testId)`: it clicks `${testId}.Open` and awaits the
popover. Every test that reaches a picker opens the popover first.

**The panel-role pass** is each P-task's last bullet, run over its own folders:

```bash
grep -rnE "rounded-lg border[^\"']*border-line[^\"']*bg-panel|bg-panel[^\"']*rounded-lg border" frontend/src/features/<folder>
```

- A titled box becomes `Panel`.
- An untitled section becomes `rounded-xl`.
- An item on a panel (a tile, a card) becomes `bg-raised shadow-e1 rounded-lg`.
- A form-field group or a table wrapper is not a panel; leave it.

## 6. The board

### 6.1 The card face (B1)

The `<article>` is
`group relative overflow-hidden rounded-lg border border-line bg-raised px-[11px] pt-[9px] pb-2.5 shadow-e1 hover:border-line-strong cursor-grab`,
with `pb-3` when the edge bar draws. The epic keeps its `border-l-2` emphasis (its render's decision,
not this plan's).

1. **Row 1** (`flex min-h-[22px] items-center gap-1.5`):
   - the grip — the keyboard activator, `GripVertical`, `opacity-0 group-hover:opacity-100
     focus-visible:opacity-100`, still in the tab order;
   - `TypeIcon`;
   - the key (`Identifier`, 11.5px `text-ink-3`);
   - a `flex-1` spacer;
   - priority, CRITICAL and HIGH only (D9): `ChevronsUp` 12px plus `priorityLabels[p]` at 11px/600,
     CRITICAL in `text-[color:var(--color-failed)]`, HIGH in `text-[color:var(--color-warning)]`;
   - the assignee: a 22px round avatar with initials and its sr-only name, as today.
2. **Title**: the link, as today, at `mt-[5px] mb-2 block text-[13.5px] leading-[1.4] font-medium`.
3. **`ReadinessSignals`** on a grooming board, unchanged.
4. **Meta** (`flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11.5px] text-ink-3`):
   - each label as a 7px round dot (`labelFill(label.color)`) plus its name;
   - blocked origin: `Ban` 12px plus "from <status>" in `text-[color:var(--color-blocked)]`, 600;
   - "also on <board>";
   - `AiEligibleMarker`;
   - sub-tasks: `ListChecks` plus mono `d/t`, in `--color-passed` when complete;
   - due ("due Oct 8" / "overdue Oct 2" in `--color-failed`, 600);
   - estimate (mono);
   - "not in %";
   - the all-stories-done sentence.
5. **Cases row**, only when `describeLinkedCases(card.linkedCases)` is non-null
   (`mt-1.5 flex items-center gap-2 text-[11.5px]`):
   - `FlaskConical` 13px;
   - the headline, mono 11px in its tone (passed, failed, or `text-ink-3` for untested);
   - `n blocked` in `--color-blocked`;
   - the "Run linked cases" link, as today;
   - a `flex-1` spacer;
   - the `MoveToMenu` trigger at `opacity-0 group-hover:opacity-100 group-focus-within:opacity-100`.

   With no cases row, the trigger sits `absolute bottom-1.5 right-1.5` with the same opacity rule.
6. **Edge bar**, only when `total > 0`: `absolute inset-x-0 bottom-0 h-[3px]`, segments in
   proportion with a 1px gap:
   - passing → `--color-passed`;
   - failing → `--color-failed`;
   - blocked → `--color-blocked`;
   - not run → `bg-wash-3` (untested is the empty track, never a fill).

   Use the kit `ResultBar` if it can draw this edge form; otherwise add a `variant="edge"` to it, not
   a second bar.

**Drag (D7).** The `article` gets only the pointer listener (`onPointerDown`) and `setNodeRef`. The
grip gets `setActivatorNodeRef`, `attributes` and the keyboard listener (`onKeyDown`). The
`DragOverlay` copy is `shadow-e3 ring-2 ring-steel`, with no rotation. The origin stays at
`opacity-40`. A click that ends a drag is swallowed (capture-phase `click` handler on the article,
keyed on a ref set in `onDragStart`).

### 6.2 The Work strip (B2)

`WorkTablePage` renders `PageHeader` with `title="Work"` (the document title `Work · <project> · TCMS`
is unchanged):

- **`lead`** on the Board view is `features/tracker/BoardPicker.tsx`. It is a `DropdownMenu` whose
  trigger reads the active board name at 16px/650 plus `ChevronDown`. When any other board has cards
  waiting at its door, the trigger also shows a count pill with the sum and the title "n cards waiting
  on other boards". The items are the boards in position order, each with its door badge (from
  `useBoardDoors`, moved here) and a check on the current one; each links to `?view=board&board=<id>`
  with every other parameter kept. Under a separator comes "Configure boards" →
  `/projects/:id/settings/boards`. On the other views, `lead` is absent and the h1 "Work" shows.
- **`views`** are five links drawn with K2's segment helpers: Board · Backlog · Table · Timeline ·
  Calendar, each with its icon (`Kanban`, `ListOrdered`, `Table2`, `GanttChart`, `CalendarDays`).
  Board links `?view=board`, keeping `board`. The per-board segments are gone; the picker replaced
  them.
- **`actions`** is `<WorkStripActionsTarget/>` from `features/tracker/workStrip.tsx`, a local portal
  with an in-place fallback (`WorkStripActionsProvider`, `WorkStripActionsTarget`,
  `WorkStripActions`). Each view puts its own `FilterStrip` (no `search`) into `WorkStripActions`. The
  board view also puts there `features/board/GroupMenu.tsx`: a `DropdownMenu` with a
  `DropdownMenuRadioGroup` of `swimlaneByLabels` and the trigger text "Group: <label>". It sits behind
  `PermissionGate board:configure`, disabled with its reason. It carries the footnote "Changes this
  board for everyone." and writes the same `updateBoard` round-trip the select did.
- **Removed:**
  - the header's `+ Add` (Create and the door `+` replace it);
  - the board view's top row (the GroupBy select and the Configure button);
  - the `KeyboardNote` banner.
- **The board's foot line** becomes
  `${n} cards shown[ across k swimlanes] · Every card's ⋯ menu has Move to… — dragging is never the only way.`
  Rule 2's strip below states the keyboard path (D16).

### 6.3 Columns (B3)

- **`swimlaneBy = NONE`.** The header cell is `rounded-t-xl border border-b-0 border-line bg-panel`,
  and the single lane's cell is `rounded-b-xl border border-t-0 border-line bg-panel p-2
  min-h-[120px]`. Together they read as one panel per column. There is no row gap between them, and
  `gap-3` between columns.
- **With lanes.** The header row is slim panels (`rounded-xl border border-line bg-panel`). Each lane's
  band bar is `rounded-xl border border-line bg-panel px-3 py-2`, sub-bars are `rounded-lg bg-wash-1`,
  and each cell is its own `rounded-xl border border-line bg-panel p-2`.
- **No internal column scroll** (D11). The page scrolls; the board scrolls sideways inside itself, as
  today.

### 6.4 The column header (B3)

**Row 1:**
- the name (13px/600);
- the count (mono 11.5px `text-ink-3`);
- the WIP pill: `h-5 rounded-full px-1.5 font-mono text-[11px]`, `bg-wash-2` under the limit. In
  breach it is `bg-[color:var(--color-failed)]/15 text-[color:var(--color-failed)] font-semibold`
  plus `TriangleAlert` and the sr-only "over the limit". The three states and their titles are kept
  verbatim;
- a `flex-1` spacer;
- on the Done column, a Show/Hide text button (`aria-expanded`, `${testId}.DoneToggle`);
- the door `+` (D10): `Plus` icon link to `/projects/:id/work/new`, label "Create an issue in <name>",
  only on the column whose `statuses` include the workflow's first status by position, behind
  `PermissionGate issue:create` and disabled with its reason. Its testid is `${testId}.Create`.

**Row 2 (health, D8):** `sumLinkedCases(cards)` — new in `features/board/linkedCases.ts`. It returns
`null` when every card's summary is `null`, else the field-wise sum. `describeLinkedCases` is then
applied to the sum, so it keeps the same three-zero wording. The row is the mono headline in its tone,
`n blocked`, and a `flex-1` 4px `ResultBar`, with the title "Linked test cases across this column's
cards, counted once per card link". There is no row when the sum is `null` or `total === 0`. Its
testid is `${testId}.Health`.

**Done (D12).** One `doneOpen: boolean` for the board replaces the per-lane `Set`. It is collapsed by
default, as before. Collapsed, each lane's Done cell shows `n done` and no cards. The keyboard walk
keeps stepping over the first non-Done column.

## 7. The screen sweep

Line numbers are from the 2026-10-04 survey; locate by content. **Every P-task ends with §5.5's
panel-role pass over its folders.**

### 7.1 P1 — document mode

| Page | Today | Change |
|---|---|---|
| `features/cases/CaseDetailPage.tsx` | `TopStripSlot` :186 plus `HeaderActions` :221; `DocumentTitle` is the h1 | `PageStrip` (no title) with those actions |
| `features/cases/CaseEditorPage.tsx` | `TopStripSlot` :1037 (draft status plus Save); no h1 | `PageStrip` with `title` ("New case" / the case key, as `<title>` says today) and the draft status plus Save in `actions` |
| `features/history/CaseHistoryPage.tsx` | `TopStripSlot` :77 with the scope toggle | `PageStrip title="History"`, toggle → `Segmented` in `views` |
| `features/runs/ExecutionPage.tsx` | `TopStripSlot` :170, `HeaderActions` :181, layout toggle :461 | `PageStrip` (no title — `DocumentTitle`), the actions, and the Focus · Split · Grid toggle as `Segmented` in `views` |
| `features/proposals/ProposalPage.tsx` | `<title>` plus `DocumentTitle` | `PageStrip` (no title), the existing actions |
| `features/docs/DocPageEditorPage.tsx` | `<title>` only | `PageStrip` with the page title and its Save/Cancel |
| `features/docs/DocsPage.tsx` | bespoke header plus h1 19px :195, `TabStrip` :225 | `PageHeader` with `views={<TabStrip …docs/>}` |
| `features/help/HelpPage.tsx` | `<title>` plus section h2 | `PageStrip title="User guide"`; the section nav stays in the body |
| `NotFoundPage.tsx` | bare h1 | `PageHeader title="Page not found"` |

Then delete `app/topStripSlot.tsx` and `TopStripProvider` from `AppShell`, and fix every test that
queried `Nav.TopStrip`.

### 7.2 P2 — index and analytics

| Page | Change |
|---|---|
| `cases/CaseListPage.tsx` (:189), `cases/SuiteListPage.tsx`, `runs/RunListPage.tsx`, `runs/RunSchedulePage.tsx`, `ingestion/IngestionsPage.tsx`, `artifacts/ArtifactsPage.tsx` | `TabStrip` → `PageHeader views` |
| `proposals/ProposalsPage.tsx` | `TabStrip cases` → `views`; Radix `Tabs` Waiting/Accepted/Rejected (:91) → `Segmented` under the strip |
| `cases/FeatureImportPage.tsx` | bespoke header :234 → `PageHeader`; segment :495 → `Segmented` |
| `history/SuiteHistoryPage.tsx` | toggle :146 → `Segmented` in `views` |
| `plans/PlanStatisticsPage.tsx` | bespoke `role=tablist` :184 → `ui/tabs` (D15) |
| `runs/RunDetailPage.tsx` | filter :471 → `Segmented` |
| `analytics/AnalyticsPage.tsx` | toggle :242 → `Segmented` in `views` |
| `cases/CaseSearchPage.tsx` | ad-hoc `Input` → `FilterStrip search` |
| `cases/CaseFilterBar.tsx` | check the popover holds its 4 facets, 3 selects, tag input and Unfiled toggle; tests use `openFilters` |
| `plans/PlanListPage.tsx`, `reports/*`, `ingestion/IngestionJobPage.tsx`, the analytics figures | the panel-role pass only |

### 7.3 P3 — work, people, projects

| Page | Change |
|---|---|
| `tracker/IssueNewPage.tsx` (:87), `tracker/IssueDetailPage.tsx` (:107), `board/MyWorkPage.tsx` (:143), `board/TeamBoardPage.tsx` (:129), `org-chart/OrgChartPage.tsx` (:51) | bespoke header → `PageHeader` |
| `board/MyWorkPage.tsx` | local `Panel` (:653) → the shared `Panel` |
| `board/AwaitingYou.tsx` | the strip → `Panel`; tiles → cards (`bg-raised shadow-e1 rounded-lg`) |
| `projects/ProjectOverviewPage.tsx` | five `rounded-[10px]` sections (now `rounded-xl`) → `Panel` where titled |
| `projects/ProjectsPage.tsx` | `Tile` → card role; each tile leads with the project's coloured key chip (N1's chip, reused) |
| `releases/*` (ReleaseList, New, Detail, `ReadinessPanels`, `RoadmapView`, `CalendarView`) | panel-role pass; CalendarView's FilterStrip has no pickers, so it shows no Filter button |
| `staffing/StaffingPage.tsx` | the "Range" select → `Segmented` in `views` |
| `staffing/DirectoryPage.tsx`, `PersonPage.tsx`, `ProjectStaffingPage.tsx`; `time/TimesheetPage.tsx`, `ApprovalsPage.tsx` | panel-role pass; DirectoryFilters' tests use `openFilters` |

### 7.4 P4 — settings, admin, account, imports, audit, clients, billing

| Page | Change |
|---|---|
| `settings/SettingsSubnav.tsx` | one row of links on the segment helpers, `max-w-full overflow-x-auto`, rendered by each of its 12 hosts in `PageHeader views` |
| `account/AccountSecurityPage.tsx` (:95) | bespoke header → `PageHeader` |
| `audit/AuditLogPage.tsx` | filter row :143 (three inputs) → `FilterStrip` with its pickers in the popover; the page-size select (:281) moves to the table foot |
| `settings/GeneralSettingsPage.tsx` | four sections (`rounded-xl` after K1) → `Panel`; a new **Colour** panel: a radio group of eight 24px `rounded-md` swatches named by `projectColorLabels`, saving through `useSetProjectColor` (new, in `api/queries/`, invalidating the projects list), behind `PermissionGate` with the endpoint's key and disabled with its reason |
| `settings/*`, `admin/*`, `account/*`, `imports/*`, `clients/*`, `billing/*` | panel-role pass |

## 8. The gate (C1 only)

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"   # A2: V15, the endpoint, context boot
~/bin/tcms-remote packages 'npm run build'      # client → cli → mcp
~/bin/tcms-remote packages 'npm run test:run --workspaces'  # schemaFreshness, contractPairing, citedFiles, tierClaims, guideCopies
~/bin/tcms-remote frontend 'npm run build'      # tsc -b + vite build — THE gate
~/bin/tcms-remote frontend 'npm run lint'       # oxlint, 0 warnings
~/bin/tcms-remote frontend 'npm run test:run'   # full Vitest + RTL
```

The backend runs first: it is the slowest, and a red migration invalidates everything downstream of
it. Read `docs/specs/shanks-runner-details.md` before believing a backend `BUILD FAILED`.

**The walk** happens only on a stack that is already up. Use the Playwright MCP at 1280×800 and
390×844, in both schemes. The screens are:
- the Work board (NONE and Epic grouping);
- a case list with a filter applied;
- a case detail;
- the execution runner;
- Settings → General, setting a colour and seeing the switcher's chip change;
- the launcher open.

Record what was seen in C1's As built. A screen not walked is listed as built and unverified.

## Decisions

**D1 — The rail goes; one top bar carries navigation, and `nav.ts`'s zones stay the data model.**
The user's ask was that navigation stops competing with the work. The rail cost 232px on every screen
and its collapsed form still cost 64. Keeping the zones as data means the palette's Places, Your
access and every gate argument survive untouched. Cost: this reverses 021 R1 (the switcher in the
rail) and design language §4 (where the rail collapses), and both are struck through in C1. Six tabs
plus More is a second click for Overview, Analytics, Test reports, Team and Settings.

| Option | Verdict | Why |
|---|---|---|
| Keep the rail, collapsed by default | rejected | Still 64px of icons that need tooltips, and the frame is still split across two edges |
| Top bar plus a slide-over project menu | rejected | Hides the six daily places behind a click |
| **Top bar with tabs and More, launcher for company and personal places** | chosen | The daily places are one click and always visible; everything else is two |

**D2 — Every page renders `PageStrip` in place; the shell-owned top-strip slot is retired.** The slot
existed because page actions lived in the shell's header. With the strip at the top of every page,
the portal buys nothing. Cost: four pages change shape (P1), and their tests that queried
`Nav.TopStrip` are rewritten.

| Option | Verdict | Why |
|---|---|---|
| A shell-owned strip that pages portal title, views and actions into | rejected | Puts the h1 outside `<main>` unless the target sits inside it, and doubles the portal timing problem for 60 pages |
| **The strip in place, one component** | chosen | No portal, works in tests without the shell, sticky via CSS |

**D3 — The breadcrumb moves into the strip and shows ancestors only.** The top bar already states the
project and the section, and the title states the page. Only the levels in between are new
information. Cost: at a section root, no trail is drawn at all.

**D4 — Radii use Tailwind's own scale; the app declares no radius token.** `index.css` records why
app-named scale tokens were deleted (they shadowed Tailwind's utilities). Tailwind's xs–xl already
covers 2–12px. The codemod is mechanical, and the check makes it permanent. Cost: 5px and 6px both
land on 6px, 10px panels become 12px, and 1px bars become 2px — visible, and intended.

**D5 — The canvas is neutral; a project's colour lives only on its key chip.** The first canvas used a
Trello-style coloured background and the user called it a copy; the approved redraw is neutral. A
colour behind the board would also compete with status hues, which own the saturation budget (design
language §1). ~~No per-project colour at all, for want of a field~~ → superseded 2026-10-04 by O2 and
D17. The colour exists, and it is confined to a 22px chip where it identifies and never decorates.

**D6 — Pickers move behind a Filter popover everywhere; search stays inline.** One look for filtering
on every screen, and the strip stays one row. Cost: a picker is one more click, and every test that
reached a picker directly must open the popover first (`openFilters`).

**D7 — The whole card drags by pointer; the grip drags by keyboard.** The old handle-only drag
existed so a click on the title or the menu would not start a drag. dnd-kit's 5px activation already
separates a click from a drag. The keyboard drag needs a focusable activator that is not the article
(an article with `role=button` containing a link and a menu nests interactive content). Cost: the
click that ends a drag must be swallowed by hand.

**D8 — Column health is summed on the client from the cards' summaries, labelled per card link.** No
endpoint answers a distinct-case count per column. Summing what the cards already carry costs no
request. Cost: a case linked to two issues in one column is counted twice; the title says so.
Confirmed by the user on 2026-10-04 (O3).

**D9 — Priority returns to the card face, CRITICAL and HIGH only.** This reverses `BoardCardFace`'s
"priority is absent" decision at the user's request (the reference board showed it). Drawing MEDIUM
and LOW too would put a chip on most cards and cost the scan the old decision protected.

**D10 — The door `+` sits only on the column that claims the workflow's first status by position.**
`IssueInput` has no status: a create enters the project's first status by position. A `+` on any
other column would create a card that appears somewhere else. Cost: a board with no column for the
first status has no `+`; Create in the top bar still works.

**D11 — No internal column scroll; the page scrolls.** Lanes stack vertically, so columns are not
single scroll containers once a board is grouped. One rule for both shapes is simpler and keeps
`pointerWithin` drops honest. Cost: a long column pushes the page rather than scrolling in place.

**D12 — Done collapses board-wide from its header.** ~~One `Done` strip per lane (D-l)~~ → one toggle
in the column header controls every lane. It is still collapsed by default. A per-lane toggle had no
place in a column header, and opening Done is a question about the whole board.

**D13 — The bar carries no counts it would have to fetch, and no control without a destination.**
Confirmed for My work by the user on 2026-10-04 (O1). No My-work count (no cheap count read, and firing one on every page for a badge is the Timesheet
approvals argument again). No bell (no notification centre exists). Create offers only routes that
exist: Issue, Test case, Release.

**D14 — The gutter is 16px from `md`, 12px below.** ~~22px, the render kit's one spatial constant~~ →
superseded. With the rail gone the content is wider, and 22px plus a sticky strip read as padding
around the work rather than the work.

**D15 — Plan statistics keeps tab semantics on `ui/tabs`.** It swaps panels on one page, which is what
`tablist` means. `Segmented`'s `aria-pressed` would mis-describe it.

**D16 — The board's keyboard note moves from a banner to the foot line.** Design language rule 2
says the strip below states what the keyboard can do. WCAG needs the single-pointer alternative to
exist (the `⋯` menu's Move to…), not a banner that costs 50px above every board.

**D17 — A project's colour is a required token name, assigned by the server and changed through its
own endpoint.** It is required, so no reader handles a missing colour and no chip falls back to grey
for old projects. A2 backfills every row, and a create assigns one when none is sent. It is stored as
a token name (`project-n`), not a hex, so the palette can be re-tuned without a migration (the
`Label.color` precedent). It has its own `PUT …/color`, not a field on `ProjectSettingsUpdate`: that
document is a full replacement whose absent booleans mean "off", and a colour has no safe absent
reading (018 D-d's argument for the client). Cost: one contract change, one migration and one backend
session (A1, A2) for a 22px chip, plus a second write path beside General settings' existing save.

| Option | Verdict | Why |
|---|---|---|
| Derive a colour from the key on the client | rejected | The user asked for a field; a derived colour cannot be chosen, and two keys can collide on one colour forever |
| Nullable field, neutral chip until set | rejected | Every existing project would stay grey until somebody visits Settings, which is the state the field exists to end |
| **Required token name, server default, single-field endpoint** | chosen | Every project is coloured from day one, the palette stays retunable, and the write has no ambiguous absence |

## Answered questions (2026-10-04)

**O1 — Should My work carry a count in the top bar?** No. An icon link only (D13).

**O2 — Should a project carry a colour for its key chip?** Yes. A contract field set in Settings →
General (§3a, D17), adding A1 and A2.

**O3 — Should column health count distinct cases?** No. The client sums per card link, and the title
says so (D8).

## 9. Tasks

### A1 — the contract: a project's colour

**Stack:** `ts` · **Agent:** `tooling-engineer`
**Deliverable:** `ProjectColor`, `Project.color` (required), `ProjectInput.color` (optional) and
`PUT /projects/{projectId}/color` are in `openapi.yaml`; both generated copies are regenerated; both
fixture factories carry `color`.
**Depends on:** —
**Budget:** ~85k. Floor 25k; skill `tool-capability-contract` 8k; plan §3a, D5, D17 and the task 5k;
`openapi.yaml`'s `Project`, `ProjectInput`, `Label.color` and the `/client` operation ~8k; both
fixture factories ~4k; `shapesContract.test.ts` ~3k; `openapi.yaml` modified 1.5k; two regenerated
copies (diff stat only) 2k; fixtures and `UNOFFERED` modified 4.5k; packages test literals ~3k;
read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `test:run`, `lint` or `tsc`
in either tree. `npm run gen:api` is a generator and must run, in both places.

- `docs/specs/openapi.yaml`: `ProjectColor`; `Project.color` (added to `required`); `ProjectInput.color`;
  `ProjectColorUpdate` (`required: [color]`); the `PUT /projects/{projectId}/color` operation, worded
  after `/client`'s (§3a), with its decision and reason in the description.
- `cd frontend && npm run gen:api`; `cd packages/client && npm run gen:api`.
- `frontend/src/test/fixtures.ts` and `packages/client/src/test/fixtures.ts`: the `Project` factory
  defaults `color: 'project-1'`. Every `Project` literal in packages tests gains `color`.
- `packages/client/src/test/shapesContract.test.ts`: `ProjectInput.color` in `UNOFFERED`, with a
  comment that no CLI verb or MCP tool sets a project colour (023 D17). Record the operation the same
  way if a test enumerates operations.
- **Not in A1:** the column and the endpoint (A2); frontend test literals and the palette (K1).
  Until A2 lands, the server answers `404` on `PUT …/color` and serves no `color`. The contract runs
  ahead of the server here, as it did in 021 T1.
- **As built (2026-10-04):** `ProjectColor` (enum `project-1`…`project-8`), `ProjectColorUpdate`
  (`required: [color]`), `Project.color` (in `required`), `ProjectInput.color` (optional, `allOf` the
  enum) and `PUT /projects/{projectId}/color` (`operationId: setProjectColor`, tag `projects`,
  `project:settings`, 200/400/401/403/404) are in `docs/specs/openapi.yaml`. `npm run gen:api` ran in
  `frontend/` and `packages/client/`; each copy gained 90 lines (`git diff --stat`). Both `Project`
  factories default `color: 'project-1'` (`frontend/src/test/fixtures.ts` `aProject`,
  `packages/client/src/test/fixtures.ts` `project`). Differed from the plan: **no `UNOFFERED` line was
  added.** `shapesContract.test.ts` only walks the schemas its `ENTITIES` catalogue names, and no
  entity names `ProjectInput`, so a key there would be dead; no test enumerates operations either. No
  CLI verb or MCP tool sets or reads a project colour. No `Project` literal exists in `packages/`
  tests (`myRole:` appears only in the factory and `schema.ts`). Built but unverified: nothing was
  built, linted or run, per the gate.

### A2 — the backend: the column, the default, the endpoint

**Stack:** `be` · **Agent:** `be-java`
**Deliverable:** `V15__project_color.sql` adds and backfills `project.color`; a create assigns one when
none is sent; `PUT …/color` sets it under the client endpoint's permission; tests cover all three
(the backfill against a database already holding two companies' projects).
**Depends on:** A1.
**Budget:** ~115k. Floor 25k; skills `be-java-database-code` and `data-migrations` 16k; plan §3a, D17
and the task 5k; `tenancy-invariants.md` (the two traps) ~4k; `V13` (its existing-company writes)
~6k; `Project.java`, `ProjectCreateService.java`, `ProjectController.java` ~8k; the `/client`
endpoint's controller, service and test ~10k; ERD `project` section ~2k; migration, request record
and service created 6k; entity, create service, controller and ERD modified 6k; two test classes
created 4k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `./gradlew build`, `./gradlew test` or any
targeted test.

- `backend/src/main/resources/db/migration/V15__project_color.sql` (or the next free number,
  recorded) per §3a, written so the backfill reaches every company's rows under FORCE RLS, the way V13
  reached them.
- `Project.java`: the `color` column, matching the migration exactly.
- `ProjectCreateService`: use `ProjectInput.color` when given, else §3a's default.
- `PUT /projects/{projectId}/color`: request record `ProjectColorUpdate`, a service method checking
  the permission `PUT …/client` checks (by key, never by role), and a `400` field error on `color`
  for an unknown value before the database is asked.
- Tests: an integration test for the endpoint (200 / 400 / 403 / 404), one for the create default,
  and one that applies V15 over two companies' existing projects and finds every row coloured.
- `docs/specs/2026-07-30-tcms-erd.md`: the `project` table gains `color text NOT NULL CHECK …`, with
  one line of why (023 D17).
- **Not in A2:** the frontend (K1, N1, P3, P4).

### K1 — the surface ladder, the radius scale and the project palette

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** `bg-bar` and `bg-raised` resolve in both schemes; `grep -rnE "rounded(-[trbl]{1,2})?-\[" frontend/src | grep -v '\.test\.'`
returns nothing, and `tokenSystem.test.ts` fails on a new literal; every `components/ui` overlay is
`rounded-xl bg-raised shadow-e3` and every item `rounded-md`. `--color-project-1…8` are declared in
both palettes, `lib/projectColors.ts` maps every `ProjectColor`, and a computed check holds white text
at ≥ 4.5:1 on each.
**Depends on:** A1 (the generated `ProjectColor` type).
**Budget:** ~130k. Floor 25k; skills `fe-react-styling` and `fe-react-component-testing` 16k; plan
§2, §3a, §5.1–§5.3, D4, D5, D17 and the task 11k; `index.css` ~9k, `tokenSystem.test.ts` ~5k,
`statusPalette.test.ts` ~3k, Tailwind's radius lines 1k, `ui/*` (7 files) ~10k, four kit leaves ~4k;
`index.css` and two tests modified 4.5k; `projectColors.ts` and its check created 4k; 11 primitives
modified 16.5k; test `Project` literals ~3k; the codemod and its diff stat 4k; read-back 6k; churn
×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `frontend/src/index.css`: `--color-surface-bar` in both palettes (§5.1) and the two `@theme inline`
  bridges; `--color-project-1…8` in both palettes (§3a).
- `frontend/src/lib/projectColors.ts` (`projectColorFill`) and `projectColorLabels` in
  `frontend/src/lib/labels.ts`, both `satisfies Record<ProjectColor, string>`; a computed check that
  white on each value is ≥ 4.5:1, beside `statusPalette.test.ts`'s.
- Every hand-written `Project` literal in frontend tests gains `color` (A1 made it required).
- The codemod (§5.2) as one `perl -pi` pass; `git diff --stat`; spot-read five files; map any
  out-of-table value by hand and list it in As built.
- `frontend/src/app/tokenSystem.test.ts`: `it('no component spells an arbitrary radius')` per §5.2.
- `components/ui/` and the four kit leaves per §5.3.
- **Not in K1:** `--header-height` and `--rail-width` (N1); `PageStrip`, `Segmented` and
  `FilterStrip` (K2); any role fix on a screen (P1–P4); drawing the chip anywhere (N1, P3, P4).

### K2 — the page strip, the segmented control, the filter popover

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** `PageStrip` and `Segmented` exist with tests; `PageHeader` renders a `PageStrip` and
keeps every testid and duty; `TabStrip` draws on the segment helpers; `FilterStrip`'s pickers sit in a
popover opened by `${testid}.Open`; `openFilters` exists.
**Depends on:** K1 (`bg-raised`, the radius scale).
**Budget:** ~122k. Floor 25k; skills `fe-react-accessibility` and `fe-react-component-testing` 16k;
plan §2, §4, §5.4, §5.5, D2, D3, D6 and the task 13k; `PageHeader.tsx`, `TabStrip`, `FilterStrip`,
`Chip`, `popover`, `routeMeta` (part), `ShellBreadcrumbs`, `kitRules.test` and one `PageHeader` test
~20k; 3 sources, 2 tests and 1 helper created 14k; 5 files modified 7.5k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `frontend/src/app/shellFrame.tsx`: `ShellFrameContext` (`{ projectId?, projectKey?, projectName? }`,
  default `{}`), `ShellFrameProvider`, `useShellFrame()`.
- `frontend/src/components/PageStrip.tsx` per §4, including the trail rule; `PageStrip.test.tsx`
  covers:
  - no h1 without a title;
  - an sr-only h1 with a `lead`;
  - the trail dropping `/`, the project root and the last crumb;
  - an empty trail drawing nothing.
- `frontend/src/components/PageHeader.tsx`: `PageHeader` → `PageStrip` (adds `views`, `lead`; h1 to
  17px); `Panel` per §4.
- `frontend/src/components/kit/Segmented.tsx` per §5.4 (exported from the kit index) and
  `Segmented.test.tsx` (`aria-pressed`, the group's name, keyboard operation).
- `frontend/src/components/TabStrip.tsx` on the helpers, without `mb-3`.
- `frontend/src/components/kit/FilterStrip.tsx` per §5.5; `frontend/src/test/filters.ts`
  (`openFilters`); the "filter strip" describe in `kitRules.test.tsx` updated, plus one test that the
  count pill equals `applied.length`.
- **Not in K2:** the tests of `FilterStrip`'s consumers. Those are WorkTablePage, ProjectBoardView
  and BacklogView (B2); CaseFilterBar and CaseSearch (P2); DirectoryFilters and CalendarView (P3);
  AuditLog (P4). Also not here: moving any `TabStrip` into a strip (P1, P2), and the shell providing
  the frame (N1).

### N1 — the shell

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** no rail, drawer, hamburger, collapse toggle or footer remains. Every authenticated
route has one 48px top bar per §3. The switcher's chips carry each project's colour.
`AppShell.test.tsx` asserts the launcher's gates, the tabs and More, Create's disabled-with-reason
items, the user menu with the version, and the switcher.
**Depends on:** K2 (`shellFrame`; `PageStrip` now owns the trail), K1 (`projectColorFill`).
**Budget:** ~154k. Floor 25k; skills `fe-react-accessibility` and `fe-react-component-testing` 16k;
plan §2, §3, D1, D13, D14 and the task 9k; `AppShell.tsx` ~14k, `nav.ts` ~7k,
`AppShell.test.tsx` ~10k, the palette trigger and test refs ~5k, `places.ts`/`AccessPage` ~4k,
`useCan`/`PermissionGate` ~2k; 6 shell files created 18k; `AppShell` rewritten ~4k; `nav.ts`,
`CommandPalette`, `AccessPage`, `index.css` and `CommandPalette.test` modified 7.5k; `AppShell.test`
rewritten ~4k; read-back 6k; churn ×1.2. Over 150k: the natural split is the menus
(`CreateMenu`, `UserMenu`) into a session of their own, kept together because the bar's test asserts
them together.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `app/nav.ts`: `placement` and `hint` per §3; header comment updated (rail arguments struck through
  → 023 D1).
- New: `app/TopBar.tsx`, `app/LauncherMenu.tsx`, `app/ProjectSwitcher.tsx`, `app/ProjectTabs.tsx`,
  `app/CreateMenu.tsx`, `app/UserMenu.tsx`, per §3's table. `ProjectSwitcher.tsx` exports its
  `ProjectKeyChip` (key on `projectColorFill(project.color)`, white text) for P3 and P4 to reuse.
- `app/AppShell.tsx`:
  - root `flex min-h-full flex-col bg-paper`;
  - `ShellFrameProvider` from `useActiveProject`;
  - `TopBar`;
  - `main` per §3;
  - keeps `SkipToContentLink`, `useRouteFocus`, `RouteErrorBoundary`, `useNavGates`,
    `visibleEntries`, `useActiveProject` and `TopStripProvider`;
  - deletes the rail, the drawer, `RailContent`, `RailLink`, `RailFolder`, `COLLAPSED_RAIL_ROUTES`,
    `CompanySlot`, `AppFooter` and the `TopStripTarget` mount.
- Delete `app/ShellBreadcrumbs.tsx` (grep for citations first).
- `index.css`: `--header-height: 48px`; delete `--rail-width` once nothing reads it.
- `CommandPalette` trigger per §3 row 7; `CommandPalette.test.tsx` updated for removed `TopBar.`/`Nav.`
  ids.
- `features/account/AccessPage.tsx`: "rail"/"sidebar" → "the launcher (⊞) or the project tabs".
- `app/AppShell.test.tsx` rewritten against §3, keeping every gate assertion it had.
- **Not in N1:** the trail (K2 drew it); the help guide's copy (C1); moving the four `TopStripSlot`
  pages (P1).

### B1 — the card face

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** `BoardCardFace` draws §6.1. `BoardCardFace.test.tsx` covers:
- the three linked-case zeroes;
- priority shown for CRITICAL/HIGH only;
- the edge bar absent at `total === 0`;
- the grip as a named, focusable keyboard activator;
- a drag that began on the title not navigating.
**Depends on:** K1 (the ladder and the radius scale).
**Budget:** ~105k. Floor 25k; skills `fe-react-component-testing` and `fe-react-accessibility` 16k;
plan §2 A10, §6.1, D7, D9 and the task 8k; `BoardCardFace.tsx` ~5k, its test 1.5k, `linkedCases`
2k, `ResultBar` 1.5k, `MoveToMenu`/`ReadinessSignals`/`AiEligibleMarker`/`TypeIcon` props ~4k,
`labels`/`statusColors` ~2k, `ProjectBoardView` sensors and overlay 3k, the dnd-kit docs 3k; the face
rewritten and `ResultBar`'s edge variant ~7k; the test rewritten ~3k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `features/board/BoardCardFace.tsx` per §6.1, rows 1–6, keeping every existing testid (`.Key`,
  `.Title`, `.Type`, `.Label.<name>`, `.BlockedFrom`, `.AlsoOn.<board>`, `.Subtasks`, `.Due`,
  `.Estimate`, `.Assignee`, `.LinkedCases.*`, `.AllStoriesDone`, `.NotCounted`, `.Move`,
  `.DragHandle`). Adds `.Priority` and `.ResultEdge`.
- Drag split per §6.1 (D7); the swallowed click.
- `components/kit/ResultBar.tsx`: an `edge` variant if the bar cannot draw §6.1's form as it is.
- `ProjectBoardView.tsx`: the `DragOverlay` copy's classes only.
- **Not in B1:** the columns and the strip (B2, B3).

### B2 — the Work strip

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** `/projects/:id/work` opens with one strip per §6.2: the board picker with door
badges, five view links, and each view's Filter (plus Group on the board) in the strip's actions.
`.Add`, `.GroupBy`, `.Configure` and `.KeyboardNote` are gone, and the board's foot line carries the
keyboard sentence. WorkTablePage, ProjectBoardView and BacklogView tests assert it.
**Depends on:** K2 (`PageHeader lead/views`, segment helpers, `FilterStrip` popover, `openFilters`),
B1.
**Budget:** ~120k. Floor 25k; skills `fe-react-component-testing` and `fe-react-ui-rules` 16k; plan
§2, §4, §6.2, D6, D16 and the task 8k; `WorkTablePage.tsx` ~10k, its test 7k, `ProjectBoardView`
top and foot ~4k, `BacklogView`/`CalendarView` filter sites 3k, `ProjectBoardView.test` filter,
group and configure parts ~5k, `useBoards`/`useBoardDoors` 2k; 3 files created (`BoardPicker`,
`GroupMenu`, `workStrip`) 9k; 4 sources and 3 tests modified 10.5k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `features/tracker/workStrip.tsx` (provider, target, portal with in-place fallback).
- `features/tracker/BoardPicker.tsx` per §6.2 (moves `useBoardDoors` out of `WorkTablePage`). Testids:
  `WorkTable.BoardPicker`, `WorkTable.BoardPicker.<Board name>`, `.Badge` on each.
- `features/board/GroupMenu.tsx` per §6.2 (`ProjectBoard.GroupBy` reused as its trigger's testid).
- `WorkTablePage.tsx`: header → `PageHeader` (title, lead, views, actions); `+ Add` removed; the Table
  view's `FilterStrip` into the actions.
- `ProjectBoardView.tsx`: top row and `KeyboardNote` removed; `FilterStrip` and `GroupMenu` into
  `WorkStripActions`; the foot line per §6.2.
- `BacklogView.tsx`, `releases/CalendarView.tsx`: their `FilterStrip` into `WorkStripActions`.
- Tests: WorkTablePage.test, ProjectBoardView.test (filter/group/configure/keyboard-note describes),
  BacklogView.test, all reaching pickers through `openFilters`.
- **Not in B2:** the columns, headers and Done (B3).

### B3 — the board's columns

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** columns are panels per §6.3. `ColumnHeader` draws count, WIP, health
(`sumLinkedCases`) and the door `+` on the first-status column. Done collapses board-wide from
`${testId}.DoneToggle`. ProjectBoardView tests assert the health row's three zeroes, the `+` placement
and the board-wide Done.
**Depends on:** B2.
**Budget:** ~127k. Floor 25k; skills `fe-react-component-testing` and `fe-react-ui-rules` 16k; plan
§6.3, §6.4, D8, D10–D12 and the task 8k; `ProjectBoardView.tsx` ~11k, `ColumnHeader.tsx` 2k,
`Swimlane.tsx` (cells, band, sub-bar, Done strip) ~9k, `ProjectBoardView.test` (headers, Done, walk)
~10k, `linkedCases`/`ResultBar`/workflow hook/types ~5k; 4 sources and 2 tests modified 9k;
`sumLinkedCases` and its tests 4k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- `features/board/linkedCases.ts`: `sumLinkedCases` plus `linkedCases.test.ts` cases (all null → null;
  mixed; zero totals).
- `features/board/ColumnHeader.tsx` per §6.4 (props gain `health`, `createHref`/`canCreate` via
  `PermissionGate`, `done`, `doneOpen`, `onToggleDone`).
- `ProjectBoardView.tsx`:
  - door column from the workflow read plus `BoardConfig.columns[].statuses`;
  - health per column over the open lanes' cards;
  - `doneOpen: boolean`;
  - the NONE-joined panel classes (§6.3).
- `features/board/Swimlane.tsx`: cells, band bar and sub-bar per §6.3; Done takes the board-wide
  boolean and draws `n done` when collapsed.
- **Not in B3:** the card (B1) and the strip (B2).

### P1 — document-mode pages onto the strip

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** the nine pages in §7.1 open with `PageStrip`.
`grep -rn "topStripSlot\|TopStripSlot\|TopStripTarget\|TopStripProvider" frontend/src` returns
nothing, and `app/topStripSlot.tsx` is deleted.
**Depends on:** K2, N1.
**Budget:** ~132k. Floor 25k; skills `fe-react-component-testing` and `fe-react-accessibility` 16k;
plan §2, §4, §7.1, D2 and the task 6k; `PageStrip`/`HeaderActions` 3k; the strip regions of the four
big pages ~16k; ProposalPage, DocPageEditor, DocsPage, HelpPage and NotFound ~10k; tests'
`Nav.TopStrip`/heading queries ~10k; 9 pages and ~5 tests modified 21k; `AppShell` and the deletion
1.5k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- §7.1's table, row by row.
- Delete `app/topStripSlot.tsx`; remove `TopStripProvider` from `AppShell.tsx`; grep for citations.
- The panel-role pass (§5.5) over `features/cases`, `history`, `runs/ExecutionPage.tsx`,
  `proposals/ProposalPage.tsx`, `docs`, `help`.
- **Not in P1:** the index pages' `TabStrip`s (P2).

### P2 — index and analytics pages

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** every `TabStrip` sits in its page's `PageHeader views`. The toggles in §7.2's table
(FeatureImport, SuiteHistory, RunDetail, Analytics) and Proposals' Radix tabs are `Segmented`. Plan
statistics is `ui/tabs`. Case search's input is a `FilterStrip search`. CaseFilterBar's tests reach
pickers through `openFilters`.
**Depends on:** K2, P1.
**Budget:** ~150k. Floor 25k; skills `fe-react-component-testing` and `fe-react-accessibility` 16k;
plan §2, §5.4, §5.5, §7.2, D6, D15 and the task 8k; `Segmented`/`TabStrip`/`ui/tabs` 3k; 16 page
regions ~32k; their tests ~10k; `CaseFilterBar` and test ~4k; 17 sources and ~8 tests modified 37.5k;
read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- §7.2's table, row by row.
- The panel-role pass over `features/cases`, `plans`, `runs`, `ingestion`, `reports`, `artifacts`,
  `proposals`, `analytics`.
- **Not in P2:** the settings subnav (P4).

### P3 — work, people and project pages

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** IssueNew, IssueDetail, MyWork, TeamBoard, OrgChart and the Staffing range use
`PageHeader`/`Segmented` per §7.3. MyWork has no local `Panel`. AwaitingYou's tiles and
ProjectOverview's sections sit on the ladder. The Projects page's tiles carry each project's coloured
key chip. DirectoryFilters' tests use `openFilters`.
**Depends on:** K2; N1 (`ProjectKeyChip`).
**Budget:** ~126k. Floor 25k; skills `fe-react-component-testing` 8k; plan §2, §3a, §5.5, §7.3 and
the task 7k; 5 bespoke headers ~15k; MyWork panel and AwaitingYou ~6k; ProjectOverview sections ~5k;
releases/staffing/time regions ~8k; tests' heading queries ~8k; ~18 files modified 27k; read-back 6k;
churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- §7.3's table, row by row.
- The panel-role pass over `features/tracker` (except `WorkTablePage`), `board` (except B1–B3's
  files), `org-chart`, `projects`, `releases`, `staffing`, `time`.
- **Not in P3:** `WorkTablePage`, `ProjectBoardView`, `BoardCardFace`, `ColumnHeader`, `Swimlane`
  (B1–B3).

### P4 — settings, admin, account, imports, audit, clients, billing

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** `SettingsSubnav` renders in its 12 hosts' `PageHeader views` on the segment
helpers. AccountSecurity uses `PageHeader`. The audit filter row is a `FilterStrip` with its pickers
in the popover. GeneralSettings' sections are `Panel`s, and its Colour panel saves through
`PUT /projects/{projectId}/color` and repaints the switcher. The panel-role pass is clean over §7.4's
folders.
**Depends on:** K2; A2 (`PUT …/color`); N1 (`ProjectKeyChip`).
**Budget:** ~124k. Floor 25k; skills `fe-react-component-testing` and `fe-react-forms` 16k; plan §2,
§3a, §5.5, §7.4, D17 and the task 8k; `SettingsSubnav`/`useSettingsSections` 3k; AuditLog ~5k;
AccountSecurity header 2k; GeneralSettings sections ~4k; the projects query file, `keys.ts` and the
`/client` mutation ~4k; 12 hosts' subnav call sites ~6k; tests ~8k; ~20 files modified 30k; the
mutation hook and the picker with its test created 7k; read-back 6k; churn ×1.2.
**Gate: none** — this plan gates once, in C1. Do not run `npm run build`, `lint`, `test:run`,
`test:smoke`, `tsc` or any targeted test.

- §7.4's table, row by row.
- `api/queries/`: `useSetProjectColor(projectId)` (a `PUT /projects/{projectId}/color` with the
  write timeout class its neighbours use, invalidating the projects list key from `keys.ts`). The
  Colour panel in `GeneralSettingsPage.tsx` and its RTL test: the radio group, a save, the refused
  state, the failure state (`drivingAFailedWrite()`).
- The panel-role pass over `features/settings`, `admin`, `account`, `imports`, `audit`, `clients`,
  `billing`.
- **Not in P4:** help copy (C1).

### C1 — help copy, the specs, the gate, the walk

**Stack:** `fe` · **Agent:** `fe-react`
**Deliverable:** the user guide's navigation section describes the top bar. The design-language spec
carries §10 (A1–A11) with §4 struck through. The IA proposal's rail decisions are marked superseded
by 023. The ERD and `docs/features.md` carry the project colour. §8's seven gate commands, backend
included, are green on shanks. The walk is recorded, or its screens are listed as unverified. This
plan is in `docs/plans/implemented/`.
**Depends on:** every task above.
**Budget:** ~162k. Floor 25k; skill `fe-react-component-testing` 8k; plan §2, §3, §3a, §8, Decisions,
every As built and the task 15k; help and spec regions ~18k; 7 files modified (plus the new §10)
~13k; the gate's read-back (backend included) 16k; the fix loop ~25k; the walk ~12k; churn ×1.2.
Over 150k: the backend's read-back is the new line. There is no split that keeps the single gate,
and if the fix loop runs long it is the walk that gets listed as unverified.
**Gate (the plan's only one, in run order):** §8's seven commands. Fix what they find, whichever task
it belongs to; for a backend failure load `be-java` and `be-java-database-code` first.

- `features/help/sections.tsx`:
  - :85–87, :96, :101, :106, :111, :121 and :126 rewritten for the top bar (launcher, switcher,
    tabs and More, search, Create, user menu; the trail now in the page strip);
  - :548's stale run-schedules sentence → Schedules is a tab on Test runs.
- `features/help/TokenSteps.tsx` :66 "sidebar" → "the launcher (⊞), under Administration".
- `docs/specs/2026-08-28-design-language.md`:
  - new "## 10. Amended 2026-10-04 — one bar, one strip, one ladder (plan 023)" carrying A1–A11 and
    the A4 table;
  - §4 struck through → superseded (023 D1);
  - rule 1's "the rail's active item" → "the top bar's active tab".
- `docs/specs/2026-09-06-navigation-ia-redesign-proposal.md`: §1 (the rail), §3 (the header) and R1
  marked "→ superseded by plan 023 (D1, D3)", struck through, not deleted.
- `docs/features.md`: any navigation paragraph that says rail or sidebar; one sentence in the projects
  section saying a project has a colour, set in Settings → General and shown on its key chip.
- `docs/specs/2026-07-30-tcms-erd.md`: check A2's `project.color` row is there and says why (023 D17).
- The gate (§8), then the walk (§8), each recorded in As built.
- Close-out step 6: the final Status, then `git mv` this plan to `docs/plans/implemented/`. End by
  telling the user the branch is ready to push, and what is still unverified.
