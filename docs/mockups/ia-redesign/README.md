# Navigation & IA redesign — static renders

Twenty static HTML renders of
[`../../specs/2026-09-06-navigation-ia-redesign-proposal.md`](../../specs/2026-09-06-navigation-ia-redesign-proposal.md).
They exist so the proposal's rail, boards, header, settings landing, palette, badges and button
convention can be **looked at** before anything is built. **Open [`index.html`](index.html).**

**Revised twice on 2026-09-06**, with the proposal, after two product-owner reviews.

**First revision:**

- **§1, the rail.** The project zone is **flat and ordered by how often a screen is opened** —
  Work · Test cases · Test plans · Test runs · Releases · Documents · Overview · Analytics ·
  Test reports · Team · Settings. The first draft's ~~`Repository ▸` / `Quality ▸`~~ subject folders
  are withdrawn; five rows they held are **tabs** on the screen they extend. One folder survives
  anywhere in the rail, `Administration ▸`, because all three of its children are gated.
- **§2, the boards.** ~~One board becomes two — Dev and QA, four columns each.~~ → superseded by the
  second revision below.

**Second revision** — three things changed:

- **§1 / §4 / R11: `Timesheets` left the rail.** It is in the **user menu**, with
  `Timesheet approvals` beneath it and still no count. The `You` zone is two rows — My work ·
  Projects — and the rail is **20 rows** for a caller holding every gate, **17** for a Test
  Engineer. The rule that moved it: the rail holds places scoped by the project switcher or by the
  company, and a fortnight of one person's hours is scoped by neither.
- **§2: two boards became three, over eleven statuses.** **PM · Dev · QA** over
  `New → Refining → Ready to develop → In progress → In review → Ready for test → In test →
  Ready to deploy → Done`, plus `Rework` and `Blocked` off the line. `Blocked` is a column on
  **every** board and each blocked card carries `⛔ from <status>` and an `Unblock` in its menu.
  `Done` is the PM board's alone and is collapsed to a count strip, because tested is not shipped
  (R12).
- **§2.5 / R10: an epic has a status of its own, so it is a card**, drawn in the lane's first band
  in the column of that status and draggable; on a board that does not claim it the lane bar shows a
  chip and an `epic on <board>` link instead. Its *completion* stays derived. **Cards stack; rows are
  earned:** the band holds the epic's card and every card-story, and a story earns a row only when
  its tasks are the cards here — any of them, even one. §2.5's layout rules then removed the left
  gutter entirely: **column headers render once per board**, and a story row's header is an
  **indented sub-bar spanning the row**.

No build step, no server, no framework, no CDN script: open the file. Nothing in the app source was
changed to make these, and nothing in `docs/mockups/` outside this directory was touched — the
existing mockup set still draws the IA it was written against.

## What this is, and what it is not

- It **renders the proposal**, it does not re-design it. Labels, order, zones, `[gate]` marks,
  column sets and badge placements are the proposal's, verbatim.
- It is **pictures, not the app**. No test ids, no routes, no data.
- The palette, the two font families and the `[data-color-scheme]` swap come from
  `assets/shell-v2.css`, which is `../assets/tcms-mock.css` **copied unchanged** with a v2 section
  appended. A value copied out of a render still lands in the real stylesheet unaltered.
- The board renders reuse the existing board mockup's own parts — `.card`, `.lane-head`,
  `.col-head`, the WIP marker — from [`../work-board.html`](../work-board.html), so a card face here
  and a card face there are the same object.

## The files

| File | Shows |
|---|---|
| `index.html` | The map. Every render with one line, plus what is live and what is static. |
| `00-before-after.html` | The built rail (brief §4.1, verbatim — 36 rows, five mixed group headings, two duplicate labels) beside the proposed rail as it rests **and** with its one folder open, same scale. The full renames table. |
| `01-rail-expanded-pm.html` | Project overview as a Project Manager, and the flat project zone in usage order — Work first, Overview seventh. 17 rows. |
| `02-rail-expanded-te.html` | Test cases as a Test Engineer: every project screen one click away, none in a folder. 17 rows, one badge, and no Clients, Billing or Administration. |
| `03-rail-collapsed-flyout.html` | The 64px rail on Test runs as a Company Admin — 20 icons, the same list in the same order as expanded, with `Administration ▸`'s flyout open and labelled. |
| `04-settings-landing.html` | `/projects/:id/settings` as a real page — per-section live-fact cards + subnav — as a Test Engineer, so four gated cards are absent. |
| `05-settings-subpage.html` | `/settings/workflow` with the subnav row, as a Project Manager, so all eleven sections show. Now drawing the **eleven-status seed** — the nine-status line plus `Rework` and `Blocked` off it, their kinds, and the legal moves grouped into the three sets §2.3 describes. |
| `06-palette.html` | The ⌘K palette open, Phase 1 scope (cases · places · projects · key jump) with the honest placeholder. Places now index the tabs too. |
| `07-my-work-home.html` | My work as the personal home: §8.4's *Awaiting you* strip and the rail badge; approvals deliberately countless. |
| `08-your-access.html` | `/account/access` — all nine hidden entries named, with who holds each key. User menu drawn open. |
| `09-case-detail-actions.html` | The button convention: one primary + `⋯` overflow, one disabled-with-reason tooltip open, the verb vocabulary. |
| `10-mobile-drawer.html` | The `<md` drawer as zone accordions, 390px in a phone frame. |
| `11-role-comparison.html` | Four rails side by side — Test Engineer, Project Manager, Finance, Company Admin. |
| **`18-pm-board.html`** | **The PM board — read the boards starting here.** `New · Refining · Ready to develop · Ready to deploy · Done · Blocked`. **One dense band per epic and no story rows**, because no story has tasks yet: `ACME-102` sits in `Refining` as a card, four card-stories stack across the other columns, two blocked stories sit in `Blocked`, `Done` is one strip carrying `41`, and `Ship it → Done` is open in `ACME-147`'s menu. `ACME-101` is in `In progress`, which this board does not claim, so its lane bar carries a chip and a link to Dev. |
| **`12-dev-board.html`** | **The Dev board.** Five columns (`Ready to develop · In progress (+Rework) · In review · Ready for test · Blocked`). The band holds `ACME-101`'s **epic card** in `In progress`, two card-stories and the two blocked cards; then one row each for `ACME-118` (4 tasks) and `ACME-130` (2 of 3 shown, `1 on QA board`). `ACME-102` is in `Refining`, so its lane bar shows the chip and an `epic on PM board` link. A `Rework` chip, a WIP breach on the column header, and `⛔ from In progress` / `⛔ from In test`. |
| **`13-qa-board.html`** | **The QA board.** `Ready for test · In test · Rework · Ready to deploy · Blocked` — ending at the tester's last word, which the PM board shares. A band, then rows for `ACME-118`, `ACME-130` and `ACME-136`. Both epics link out. The linked-cases line is on every card face and is Phase 2 — said once, in the caption. |
| **`14-board-handoff.html`** | All three boards small and stacked, **three** cards highlighted twice each: `ACME-150` in `Ready to develop` (PM→Dev), `ACME-120` in `Ready for test` (Dev→QA), `ACME-147` in `Ready to deploy` (QA→PM). `Rework` and `Blocked` marked shared but *not* hand-offs. **No second action.** |
| **`15-board-coverage.html`** | Settings → Boards: the workflow line drawn above the matrix, then **11 statuses × 3 boards**, the five shared ones marked, one card per board, and the same table again in the state where a status falls through. |
| **`16-test-cases-tabs.html`** | `Cases · Suites · Proposals` — all three tabs drawn, the Proposals banner, and the table of every row that left the rail with its route unchanged. |
| **`17-test-runs-tabs.html`** | `Runs · Schedules · CI results` — the `/run-schedules` orphan rescued as a tab rather than a row, and the unmatched-tests badge on the tab that owns the list. |
| `assets/shell-v2.css` | `tcms-mock.css` verbatim + the v2 components (zones, the folder, the flyout, palette, drawer, comparison frames) + the board section (the one-scroller board, the once-rendered header row, the lane grid, the story sub-bar, shared-column marking, the matrix). |
| `assets/shell-v2.js` | The nav model as data — **the rail, the tabs and the user menu in one file**, so a row that moved from one to another cannot end up in two of them or in none — the rail/header/menu builders, and the real permission matrix. |

## Driving them

Every render carries the shell's own controls, and they work:

| Control | Does |
|---|---|
| `as …` in the header | Rebuilds the rail, the user menu and the settings landing for that role, from the real matrix. Entries are **absent, not greyed**. |
| `⇤` in the wordmark row | Collapses to 64px. Collapsed, the `T` mark expands again. |
| `Administration ▸` | Opens and closes, in the rail and in the drawer. It is the only folder left. |
| `⌘K` / the search control | Opens the palette (render 06) or goes to it. `Esc` closes. |
| `☾ / ☀` | Light and dark, both from the app's tokens. |

Role and rail state can also be set per URL — `01-rail-expanded-pm.html?role=Finance&rail=collapsed`.

## Choices made while rendering

Where the proposal was silent or ambiguous, the simplest reading was taken and recorded here.

1. **The render standard: nothing inside the product frame is commentary.** These renders are
   the implementation guide, so they must show only what gets built. Every explanation lives in
   the caption strip at the top and nowhere else — never a banner inside the page, a chip inside a
   card, a note in a column header, or meta text in a filter bar. A chip survives only if it is
   **real data**: a label, `Rework`, `⛔ from <status>`, `also on <board>`, a readiness signal, the
   linked-cases line on a QA card. Empty columns are blank **drop zones**, not a `—`. Mock tooling
   that is not product — the `as …` role switcher, the `MOCKUP` ribbon, the caption strip — stays
   visually distinct, and nothing else may look like product without being product. Four renders
   are figures rather than screens (00, 11, 14, 19): their *product frames* obey the standard, and
   the text around those frames is the figure's subject.
2. **Cards stack; rows are earned** (§2.5). A lane opens with **one band** in which the epic's own
   card and every card-story sit in their status columns like a plain kanban. A story earns a row
   of its own **only when its tasks are the cards on this board**, and a lane never has a row whose
   only content is one card in one cell. So the PM board is one dense band per epic and no story
   rows — no story has tasks yet — while Dev and QA are a band plus one row per story in flight.
   §2.5's layout rules finish the shape: **column headers render once**, sticky under the filter
   bar, carrying the board-wide count, WIP limit and breach — lanes below carry none of their own;
   **there is no left gutter**, so the columns start at the lane's left edge on every board and
   every lane, which is also what lets render 14's three mini boards line up by column alone; and a
   story row's header is an **indented sub-bar spanning the row** — key, title, `done/total`, status
   chip, elsewhere link, `⋯` — in the same idiom as the epic bar, one indent in.
3. **Gates are the real matrix**, read from `docs/specs/permission-matrix-summary.md`, not
   illustrated: `issue:import` · `integration:manage` · `project:audit` are Project Manager, Test
   Lead and Company Admin (a Test Engineer holds none); `client:read` is Account Manager upward;
   `billing:read` is Finance upward; `token:issue` · `company:idp` · `audit:view` are Company Admin.
4. **Finance gets no Project zone.** Finance and Account Manager hold no `project:read` — at company
   scope that key would open every project present and future — so the whole zone is absent rather
   than empty. The proposal does not say what a person with no project grant sees; its §8.4
   ("anyone with a project grant") implies such people exist.
5. **`Teams & roles` is rendered ungated**, as the proposal's §1 diagram has it, although the build
   gates `/admin/teams` on `company:teams`. The proposal is the source of truth here.
6. **The "before" rail is the built one**, from brief §4.1 verbatim — that is what "36 rows max"
   refers to. `docs/mockups/assets/shell.js` draws a *third*, simpler IA (~20 rows) that is neither
   the build nor the proposal; rendering it as "before" would have compared the proposal against
   another proposal.
7. **Row counts are recomputed, not quoted** — and after the second revision they **agree with §1
   exactly**: **20** for a caller holding every gate, **17** for a Test Engineer or a Project
   Manager. Render 11 counts the rows out of the built DOM rather than printing a literal, so the
   figure cannot drift from the rail beside it.
8. **The zone header is not counted as a row**, matching the proposal's own 20.
9. **Case detail's action group is drawn in the shell's top-strip slot**, per §3's list of
   document-mode screens, with its `⋯` contents also drawn open in the page body so the render can
   show them.
10. **The palette's key-jump affordance is a dimmed hint row**, not a fabricated result: the rendered
   query is `checkout`, which is not a key, so showing a jump *result* would have been a lie about
   what Phase 1 returns. For the same reason `Timesheets` is **not** added to that query's *Places*
   results — it cannot match `checkout`. That the user menu is indexed as places is said in the
   render's caption instead.
11. **The rail carries exactly one badge, on `My work`.** The proposal's other badges (§8.3) belong
   to *Proposals*, *CI results* and the three *board segments*, all of which are tabs, so they are
   drawn on the tabs that own those lists and nowhere else. The first draft's rule — a closed folder
   rolls up its children's badges — is kept in `shell-v2.js` but has nothing to act on: the one
   surviving folder holds no badged child. It is left in so a folder added later behaves.
12. **Badge numbers are invented but shaped correctly** — each is an *awaiting you* count, never a
    total: `My work 8`, `PM board 3` (`Ready to deploy`), `Dev board 6` (`Ready to develop`),
    `QA board 5` (`Ready for test`), `Proposals 3`, `CI results 4`. §8.3's rule that the **Blocked
    count belongs on the column header, not the segment** is followed: no segment carries it, and
    all three boards' `Blocked` column heads read `2`.
13. **The Google Fonts link is kept**, because the existing mockup kit has it and dropping it would
    change the type. Both families fall back to a system stack, so every render is complete offline.
14. **No skip link**, matching the existing kit — the shell prepends the rail to `<body>`, so a skip
    link could not be first in the DOM without restructuring pictures that have no keyboard journey
    to skip.
15. **Tabs are drawn from the same model as the rail, and so is the user menu.** `TABS` and
    `buildUserMenu()` sit beside `NAV` in `shell-v2.js`, so the file states in one place that a tab
    is not a rail row and a menu row is not either — and `Timesheets` cannot end up in two of the
    three or in none. Every render's rail, menu and tab strip is rebuilt from that one model, so the
    second revision reached all twenty files at once.
16. **A story row is a CSS grid per lane, not the existing flex `.board`.** The columns have to line
    up across every story row in the lane, which a per-row set of flex columns cannot guarantee. The
    lane scrolls sideways on its own (`.lanescroll`), so the page body never scrolls horizontally.
    Each board sets its own track list, because each board now claims its own number of columns —
    that is reversal R7 expressed as a grid.
17. **An item drawn as a card has no row header of its own; the header exists only for an item
    whose children are the cards.** Without it an epic in a status its board claims was drawn
    *three times in one band* — the lane bar, a header, and the card — and a story with no tasks
    twice. The lane bar keeps the identity and the roll-up; the card keeps the status, the menu and
    the drag. `ACME-118`, `ACME-130` and `ACME-136` keep sub-bars: their tasks are the cards, which
    is the case a header exists for. The one thing a header carried that a card must not lose is the
    *elsewhere* link, so a card-item carries `also on <board>` on its face instead.
18. **One horizontal scroller per board**, holding the single header row and every lane, because
    headers rendered once above lanes that each scrolled independently would desync the moment a
    board got wider than the window. The header row is `position: sticky; top: 0`; a static render
    cannot show stickiness, so what these screenshots actually prove is the checkable half — the
    headers render **once**, above the lanes, on the lane grid's own track list. That shared track
    declaration (`.egrid.c5, .colheads.c5 { … }`) is the whole of what keeps them aligned.
19. **A shared column is *marked*, not re-coloured.** Every shared status carries a tinted border, a
    `shared with …` label in the column head, and a row in the coverage matrix. `Blocked` is marked
    separately again, in the danger tint, because it is the one status all three boards claim.
    Colour alone would have carried the meaning, which the corpus forbids — so every mark also
    carries a word, and every blocked card also carries the `⛔ from <status>` chip in text.
20. **Board figures are invented but reconcile across renders.** One key space, and every number on
    screen — column counts, WIP limits, `n/m` completion, the `n on … board` links, the `41` strip
    and the three segment badges — agrees between 18, 12, 13, 14 and 15. The worked set is:
    `New 6 · Refining 5 · Ready to develop 6 · In progress 4 · In review 3 · Ready for test 5 ·
    In test 3 · Rework 2 · Ready to deploy 3 · Done 41 · Blocked 2`. **The epic `ACME-101` is in
    `In progress`**, because its stories are, which is why Dev draws its card and PM and QA draw a
    chip and a link to Dev. **The two blocked items are stories, not tasks** — deliberately: a
    blocked *task* would put its story on the PM board too (`Blocked` is claimed by all three) and
    earn it a row whose only content is one card, which §2.5 forbids.
21. **WIP counts are per column across every lane and row, collapsed ones included**, which is why
    *In progress* reads `4 / 3 ⚠` while fewer cards are visible, and why the `41` strip is larger
    than anything drawn. The breach shows **only** as the column-header indicator; the paragraph
    that used to explain the arithmetic on the page is in the caption now.
22. **`0/6 run` is not `0/6 passing`.** A QA card whose linked cases have never been executed says
    so; reporting it as failing would be the dishonest reading of the same zero.
23. **Render 03 is a Company Admin**, not a Test Engineer. It exists to show a collapsed folder's
    labelled flyout, and the only folder left is one a Test Engineer cannot see at all.
24. **Render 07's *Awaiting you* strip is §8.4's five items exactly** — reviews assigned, cards at
    your door **on each board where you hold the moving key**, your blocked cards, proposals
    waiting, and a plain `Timesheet approvals` link. Dana is a Test Engineer, so **one** board tile
    is drawn rather than three, and the render says why on the page: she holds the moving key on the
    QA board and on no other. §8.4 does not define "the moving key"; read here as *may move a card
    into that board's door column*.
25. **The coverage warning is drawn as a second table on the same page.** §2.4's seeded three cover
    every status, so the warning row cannot be true at the same time as the matrix above it; drawing
    both was the only way to show the state and the warning in one file without faking the project's
    configuration. That this is a render device — one table in the product — is said in the caption,
    not on the page, and the *what happens when a status is added later* panel moved there whole.
26. **The user menu is drawn twice on render 19** — live in the header, and again at rest in the
    page body at full size. The header instance is the real one (built by `shell-v2.js`, and it
    changes with the `as …` role switcher); the body copy exists because a menu anchored to a 26px
    avatar in the top-right corner is not a thing a reviewer can read and annotate. Render 08 shows
    the same menu open in the header only, which is how §4 is actually reached.
27. **The user menu carries no annotation tags.** An earlier pass put a `from the rail` tag beside
    `Timesheets` and a `new` tag beside `Your access`; both were commentary living inside product
    chrome, and both are gone under the standard. The move and the new entry are stated in render
    19's page copy and in the captions that need them.
28. **`Done` is one narrow strip spanning the lane** — a single count and the `⊞` that opens it,
    stretched to the lane's height (`align-self: stretch`, because the lane grid is
    `align-items: start`). Not a cell per row: a per-row zero under a collapsed column is noise.
29. **Empty cells are blank at rest.** No dash, no zero, no label, and no outline: the cell's own
    column surface is all that renders. The drop-target outline is scoped to
    `.egrid.is-dragging .cell:empty::after`, a class nothing in a static render ever sets — it is
    kept so the drag state is specified rather than invented later.
30. **Readiness signals name what is missing, in words.** §2.6 lists five signals (acceptance
    criteria, estimate, epic, target release, task count) and says the card "says which are
    missing". Rendered as **four** chips per card — the four that are per-story facts — with the
    task count carried by the row header's `n/m`, which every row already has. Each chip is a glyph
    plus a word, never a colour alone.
31. **A card's `⋯` menu is drawn open exactly once**, on `ACME-147`, because §2.4's `Ready to
    deploy → Done` drag is the one place in the product where a move *is* a business decision. The
    menu names the consequence — `Ship it → Done` — rather than only the destination.
32. **Render 05 was rebuilt, not relabelled.** It drew a seven-status workflow
    (`Backlog · Ready · … · Won't do`) that predates §2.3 and contradicted the settings landing card
    two clicks away. Rewritten to §2.3's eleven, and its *Legal moves* panel now lists the moves the
    proposal actually names, grouped — 8 on the line, 4 for `Rework`, 10 for `Blocked` — with the
    arithmetic stated on the page rather than a bare count that does not add up to the card's 24.
33. **The "elsewhere" link is single-board in every lane drawn here.** §2.5 now defines the harder
    case — a not-shown set spanning two boards reads `3 on other boards` and opens a popover naming
    each — but no lane in this data reaches it, so it is **not drawn**. Inventing a lane to
    demonstrate it would have meant figures that no longer reconcile across renders 12–18. A card
    that is itself on two boards carries `also on <board>` on its face instead, which is a different
    statement and is drawn.

## Found in the proposal while rendering

Two findings from the previous pass are **now fixed in the document** and are recorded here only so
the next reader knows they were raised and settled.

- **§1's row counts reconcile with its own diagram.** With `Timesheets` moved out (R11), the diagram
  gives You 2 + Project 11 + Company 7 = **20** for a caller holding every gate and **17** for a Test
  Engineer. Both are §1's stated figures. The one open question §1 already flags stands: the figure
  becomes 16 if `Teams & roles` keeps the `company:teams` gate the build has and the diagram does not
  draw.
- ~~**"24 legal moves" is not derivable from §2.3, which describes 22.**~~ → **settled.** §2.4 now
  states the seeded moves and their arithmetic (8 + 4 + 10 = 22), §5's card reads
  `11 statuses · 3 kinds · 22 legal moves`, and both renders follow.
- ~~**§2.4 counts `Blocked` as a working column on Dev and QA but not on PM.**~~ → **settled.** §2.4
  now says "Working columns per board, **Blocked included**: PM 5 (+ Done collapsed), Dev 5, QA 5",
  and §5's card reads `PM (5+Done) · Dev (5) · QA (5)`. The renders draw six PM columns, five Dev,
  five QA, which is what those figures now describe.
- ~~**The "elsewhere" link needs a definition when the not-shown set spans two boards.**~~ →
  **settled**, and §2.5 answers it: the link reads `3 on other boards` and opens a popover naming
  each board with its count. **Not drawn** — no lane in this data has a not-shown set spanning two
  boards, and manufacturing one would have broken the figures that reconcile across 12–18. It is the
  one specified behaviour in §2.5 that these renders do not show.

Still open:

- **§2.2's auto-claim rule has no defined answer for `Rework` or `Blocked`.** The rule — "claimed by
  every board that claims the status it was inserted after" — assumes a line, and §2.3 draws both of
  those *off* the line. Render 15 states the rule and then says plainly that two statuses are not on
  a line, rather than inventing an order.
- ~~**§2.5's first-band rule and `Blocked`-on-every-board conflict for a lone blocked task.**~~ →
  **settled.** §2.5 now reads "a story gets a row of its own only when its tasks are the cards on
  this board — **any of them, even one**, because 'this story has one task here and it is blocked'
  is exactly what the row says". A lone blocked task is therefore a legitimate one-card row. These
  renders keep the two blocked items as *stories*, because the figures already reconcile that way
  and the rule no longer forces a change.
- **§2.5's older prose still says the epic's card sits "in the lane header row" with the roll-up
  "at the left", which the layout rules below it have superseded.** With no gutter column there is
  no "left" for a roll-up to sit in; the lane bar carries identity and roll-up, and the band carries
  the card, which is what the sketch draws. The two bullets should be reconciled in the document —
  the renders follow the layout rules and the sketch.
- **`Suites` has no icon distinct from `Classification` in the proposal**, which lists neither;
  glyphs throughout are this render set's, extending `shell.js`'s existing set. The same is true of
  the three board glyphs (`P` / `D` / `Q`) on render 14.
