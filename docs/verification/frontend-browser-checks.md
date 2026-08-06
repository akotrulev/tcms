# Deferred browser checks — the queue

**Policy §65 (2026-08-02).** A live browser pass is deferred and batched: a slice records what must
be driven, and this file is executed later as one session. Nothing here is optional — each row is a
defect nobody has looked for yet, and **a slice in an unrun lane is *built and unverified***.

**Rewritten 2026-08-05 into lanes.** It was one block per slice (~30 blocks, 128 numbered rows) with
the same permission, resilience and responsive checks restated in each. It is now **two passes of
ordered lanes**. Rows keep a `src` column so existing references (`§80 row 14`, `§100 row 112`) still
resolve; the per-slice originals are in git history at `4b582ec`.

| Pass | Lanes | Shape |
|---|---|---|
| ~~**The mock pass**~~ | ~~`M1 → M7`~~ | ~~One `npm run dev`, one persona, no sign-ins, no backend.~~ |
| **The live pass** | `L0 → L4` | One provisioned company, five personas, two scheduler waits. |

> ## ⚠ THE MOCK PASS HAS NO LANE TO RUN IN — §107, 2026-08-06
>
> **`src/mocks/` was deleted.** MSW, its handlers, its fixtures and `VITE_API_MODE` are gone now that
> the API is complete, so `npm run dev` requires `docker compose up` and a provisioned company. The
> `M1 → M7` lanes below were written against a fixture store that no longer exists: their seeded
> data, their "sign in with any 4+ char password", and every row whose expectation names a specific
> fixture row are **not executable as written**.
>
> They are struck through rather than deleted because most of them check a *screen*, not a *mock* —
> the observation is still worth making, it just has to be made against real data now. Whoever runs
> this queue must **fold the surviving M-rows into the live pass**, re-deriving each row's setup from
> `provision-company` output instead of the fixtures, and drop the ones that only ever asserted mock
> behaviour. That is a real re-write and it is not done here.
>
> **`M7` (the fault switches) survives intact and moves to the live pass**, where it is strictly
> better: `tcmsFaults` now injects in front of `fetch` against the real backend rather than inside a
> mock handler, so what those rows exercise is the production error path. Keep it last — it still
> poisons everything after it.

**Each pass is a single agent working its lanes in order, start to finish.** They are not a menu and
they do not interleave: within a pass the lanes share one accumulating state, later lanes read what
earlier ones wrote, and both passes end with a lane that would poison everything after it (`M7` arms
the fault switches; `L4` finishes writing the company). Each pass opens with its own **run-order
table** naming what every stage needs and what it leaves behind — read that before the first row.

The two passes are independent of each other and can be done on different days, in either order.
**Neither may be split across agents**, and neither may run concurrently with the other in the same
tree: `docs/plans/2026-07-31-parallel-execution-rework.md` §0 is what that costs here.

## Setup

```bash
docker compose up --build          # REBUILD — a running image predates the change (§63)
cd frontend && npm run dev         # mock lanes: default. live lanes: VITE_API_MODE=live in
                                   # .env.local, then rm -rf node_modules/.vite
```

Live mode is the one that counts — mock mode agrees with itself, and §52/§54/§64 each found a defect
that existed only because the mock and the SPA shared one wrong assumption. Where a lane is marked
**mock**, mock is sufficient *for those rows*; where a row is live-only it says so.

Per row: drive it, check the observation, and check `window.__tcmsErrors` is empty at the end of each
lane. **Findings go in the slice's handoff section, not here** — this is the queue.

**Personas.** Mock mode signs in as one persona holding every key, so every tier distinction is
structurally invisible there and lives in `L1`. Live mode needs five: Viewer · Test Engineer ·
Test Lead · Company Admin · a `report:view`-without-`report:export` grant (no built-in role has that
shape — §87 created it for the suite).

---

# The mock pass — M1 → M7, one agent, one sitting, in this order

**One `npm run dev`, one persona, no sign-ins.** The mock holds every key, so nothing here switches
account — which is why the whole pass is one continuous session and why every tier distinction lives
in L1 instead. Run the seven lanes back-to-back in the order below.

**The mock store resets on reload — this is the sequencing hazard of the whole pass.**
`src/mocks/db.ts` is a cloned fixture store in module state, so a browser reload returns it to
baseline and takes every write in this lane with it. Two consequences, and neither is optional:

- **Prefer in-app navigation to a browser reload.** Where a row says *"save and reload"* and expects
  the write to survive, it means navigate away and back. A row that genuinely needs a browser reload
  to prove something (M1-16's collapsed rail, M6-15's lost cards) says so, and those two are
  deliberately about state that *should* be lost or *should* outlive the store.
- **Order within a lane matters more than it looks.** A row that reads a write made three rows
  earlier is only meaningful if nothing reloaded in between. Work down each lane as written.

### Run order

| Stage | Lane | Before you start | What it leaves behind |
|---|---|---|---|
| 1 | **M1** | A fresh `npm run dev`. | Almost nothing — it is mostly navigation, so it is the cheapest lane to redo after an accidental reload. |
| 2 | **M2** | Baseline fixtures. M2-1…M2-11 build the Feature tree the later classification rows read. | Cases, tags, suite rules and vocabulary values that M3's plan counts depend on. |
| 3 | **M3** | M2-29/M2-33 done: **M3-5 reads the `smoke` tag M2 wrote**, and M3-6's section arithmetic only holds once the suite rules exist. | Plans moved down the ladder, a plan document, drafts in the tab spine. |
| 4 | **M4** | M3's plans. M4-23…M4-29 need a plan whose cases are drafts, which M2 left. | A completed run, a reopened run, attached evidence, two gates switched on. |
| 5 | **M5** | **M5-1 first, always** — §77 changed the default to "review nowhere", so nothing else in the lane exists until it runs. M4-28 switched the opt-ins off; M5-1 switches plans back on. | An open review, a verdict trail, comments, reviewer requests, board cards. |
| 6 | **M6** | Nothing from earlier lanes — the console rows stand alone. **Its last two rows edit source** (M6-13's `GenerateReportDialog`, M6-20's `mockModel`), which restarts HMR and resets the store, so they are last in the lane on purpose. **Undo both edits before Stage 7.** | Report jobs, saved definitions, and two source files you must have reverted. |
| 7 | **M7** | Everything else finished. **This lane arms `tcmsFaults`, which poisons every request until it is disarmed** — running it earlier makes every later row's failure ambiguous. | Faults. Call `tcmsFaults.off()` after each row and reload at the end. |

**Fault switches live in module state**, so a reload clears them — arm the fault *after* the page has
loaded when the row is about testing a failed refetch. Caught client errors are readable at
`window.__tcmsErrors`, and M7-8 checks it is empty at the end of the pass.

**If a row cannot be driven** — a control is missing, a fixture is not what the row assumes — record
it against that row and carry on down the lane. Unlike the live pass, restarting is cheap here: a
reload and a re-walk of the lane costs minutes, so a lane whose state got confused is worth redoing
rather than reasoning around.

---

# M1 — the frame: rails, boards, and the way back (mock)

Three boards share one component and one card shape; the rail is on every screen and now has two
widths. Most of the repaired defects were "the route's project and the card's project happened to be
the same value", which is invisible from a single project.

| # | Drive | Expect | src |
|---|---|---|---|
| M1-1 | Look at the rail before touching anything | **My work**, then **All projects**, then the **Project** switcher, with a divider above the switcher. A personal board listed inside the project frame says switching projects changes it. | §83-1, §91-35 |
| M1-2 | Click **My work**, then switch projects | The board **does not change**; the tab title stays "My work". | §83-2 |
| M1-3 | Read My Work's cards | Cards from **both OLB and MOB**, each stamped with its own project key. One project means the aggregate quietly became one project's board — and every unit test still passes. | §83-3 |
| M1-4 | Click the key on a **MOB** card, then on an OLB review card (case, then plan) | A MOB URL that resolves — **check the address bar**. Case → case detail with the review panel; plan → plan detail. `linkFor` took the board's project before §83 and has needed repair in three slices. | §83-4,5, §85-6 |
| M1-5 | Drag a **task** card on My Work, then open that project's board **and** a team board | The card is in the new column on **all three**. The move invalidates the shared `work-items` root; a per-board key leaves two screens showing a card in the column it just left, and neither looks wrong alone. | §83-6, §85-7 |
| M1-6 | Drag a **derived** card (execution or review) on any of the three boards | Refused in place, sentence naming what to update — *"TC-20 mirrors a review, so it can't be moved to Done."* Identical on all three: one component. Moving must not perform the approval. | §83-7, §85-8, §81-4 |
| M1-7 | On My Work and on a team board, look for the **Everyone / Assigned to me** chips and **New task** | **Neither is present.** The chips are a deliberate 400 ("Choose a board"); the button needs a project these screens do not have. Render-then-reject. | §83-8, §85-9 |
| M1-8 | URL → `/projects/<nonexistent-uuid>/board` | An error state, **not another project's board**. The mock used to fall back to the first project, so a stale bookmark rendered OLB's cards under a foreign id. Only reachable by typing a URL. | §83-13 |
| M1-9 | Sign in, go straight to `/my-work` before visiting any project | It renders. Must not depend on `useProjects` — a member with no grants has this board and nothing else. | §83-14 |
| M1-10 | Teams & roles → any team row | A **Board** link on every row, **not greyed** even when Edit/Members/Delete beside it are. It is the one affordance there with no permission behind it. | §85-1 |
| M1-11 | Board on *Payments Squad*, then on *Checkout Squad* | `/teams/<uuid>/board`, title "Payments Squad — board". Cards from **more than one person** and from **both projects**. Checkout Squad shows a **different set** — Priya's OLB task, not the signed-in user's. Two teams showing one board passes every test written from one team. | §85-3,4,5 |
| M1-12 | URL → `/teams/<not-a-team>/board`, then press **Back to teams** | **"No such team"** with a working *Back to teams* → `/admin/teams`. Not an empty board, not "Not found — Not Found": the server's 404 is empty-bodied so the sentence is the client's. | §85-10,11 |
| M1-13 | Create a team with no members, open its board | The **empty state**, visibly different from M1-12's error. The two answers this screen exists to keep apart. | §85-12 |
| M1-14 | Two or three screens deep, click **All projects**, then the **TCMS** wordmark | Both land on the picker. `All projects` is marked current **there and on no other screen**. | §91-34 |
| M1-15 | Collapse the rail; hover each icon, then tab through with the keyboard | 64px, content reflows. Tooltips carry the label **on keyboard focus, not only hover**. Group dividers stand in for the headings. The active item keeps its indicator bar. | §91-36 |
| M1-16 | Collapsed: click the project-key button; reload; expand and reload | The menu lists every project, current one selected, navigation works. **The collapsed state survives both reloads**, and the rail does **not** render expanded for a frame first (read in initial state, not an effect). | §91-37 |
| M1-17 | Collapsed, walk case list · case detail · plan statistics · board · docs · reports | Nothing reflows badly; no screen assumed a 232px rail. The DataGrid pages are where a width change shows. | §91-38 |
| M1-18 | Keyboard: tab from the top; then narrow below `md` and open the hamburger | Skip link → **My work** first → switcher. Active state marked by bar and weight, not colour alone. The drawer holds the same items in the same order and closes on tap; a row in one and not the other means the drawer took a different path. Team-board columns stack at `xs`. | §83-15,16, §85-18,19 |
| M1-19 | Grab a task card's **drag handle** and drop it in another column | The card follows the cursor (an overlay copy; the original stays and fades), the target column outlines, and on release it is in the new column **immediately** — no beat of latency, no snap-back-then-jump. §106 replaced the browser's native HTML5 drag with `@dnd-kit`, so this is the whole gesture re-implemented rather than a tweak. | §106-1 |
| M1-20 | The same drag **on a tablet or with touch emulation on** | It works. Native HTML5 drag has **no touch support at all**, so before §106 a board could not be reordered on the device it is most likely to be read on, and nothing on screen said so. | §106-2 |
| M1-21 | Tab to a card's drag handle, press **Space**, then **←/→**, then **Space** | Space lifts the card, each arrow moves it **a whole column** (not 25 pixels), Space drops it. Arrows at either end of the board do nothing rather than wrapping round. `@dnd-kit`'s stock getters cannot do this — `sortableKeyboardCoordinates` needs a `SortableContext` the board has none of. | §106-3 |
| M1-22 | With the same card lifted, press **Escape** | The drag cancels and the card stays where it was. No request goes out. | §106-4 |
| M1-23 | Drag a card and drop it **back in the column it started in**, and again **outside every column** | Nothing happens either time — and check devtools: **no `PATCH`**. §94's `move(i,i)` defect in its board spelling; here it would be a write, a version and an audit row for a gesture that changed nothing. | §106-5 |
| M1-24 | Try to drag a **derived** card (execution, review, maintenance) | It does not lift at all — no handle is rendered on it. `ui-rules`: not a gesture that lifts and is then refused. The refusal is still discoverable on the disabled "→ column" buttons' tooltips, which name that card's **own** source. | §106-6 |
| M1-25 | While dragging a task, look at the columns it may not enter | They dim and do not highlight, and they will not accept the drop. The legal ones tint and outline under the pointer. Not colour alone: the dimming and the buttons' words carry it too. | §106-7 |
| M1-26 | `tcmsFaults.status(409)` scoped to `/work-items`, then drag a card | The card lands in the new column **first**, then returns to the one it came from, and a **persistent** warning above the board carries the server's sentence. Not a toast alone: a toast about a card that has visibly snapped back is gone before the eye returns to it. | §106-8 |
| M1-27 | Same, with a screen reader running | The failure is **announced** — "…did not move. …". The snap-back is otherwise completely silent, which is the one state a sighted user gets for free and a screen-reader user does not. | §106-9 |
| M1-28 | `tcmsFaults.hang()` scoped to `/work-items`, drag a card, then drag it again immediately | The first drop moves it optimistically; the second is a move from the new column, not a repeat of the first. Watch for **two identical PATCHes** — a card that looks like it did not move is the thing users re-drag. | §106-10 |
| M1-29 | Mock mode: drag a task from **Done** back to **To do** if that is not in its `allowedTransitions` | Refused. §106 taught the mock handler the contract's second 409 (*"the requested column is not in the card's allowedTransitions"*), which it had answered **200** to since Phase 1 — unreachable while the only mover was a button the board had already disabled. | §106-11 |

---

# M2 — the repository: cases, steps, conditions, tags, suites (mock)

| # | Drive | Expect | src |
|---|---|---|---|
| M2-1 | Open a case editor → Classification panel | **Four** controls — Test Type, Test Level, Feature, Execution Phase — with current values selected. Empty vocabulary reads *"Nothing to choose from yet"*, not a spinner. | §68-1,10 |
| M2-2 | Change Test Type, save; then save again untouched | One new version, then **no new version**. Reorder-churn is the risk: the ids go over the wire as an array. | §68-2,3 |
| M2-3 | Set Test Type to **None**, save | Detail shows none; `psql` shows the `case_classification` row **gone**. | §68-4 |
| M2-4 | Tick a **parent** Feature, save; tick a child too; untick one | Parent accepted (two milestones of comments claimed it could not be); both stored and both named on the grid; unticking takes only that one. | §68-5,6,7 |
| M2-5 | Create a new case with a Test Type and two Features | Both land; the v1 snapshot carries them. Network: `classificationValueIds` present on **every** save, including empty. | §68-11,13 |
| M2-6 | Vocabulary editor (`/settings/classification`) as the mock persona | Four panels with a scope chip (*This project* / *Company-wide*). Feature: **Add value** and every rename/retire enabled. Test Type: **Add value disabled with a tooltip** — not hidden (§31). That contrast is the screen. | §69-1,2,3 |
| M2-7 | Add root "Lending", then "Mortgages" under it via the row's **+** | Renders indented. `psql`: the child's `path` is the parent's plus a `v…` label **not derived from the name**. | §69-4,5 |
| M2-8 | Add a Feature named **支付** | Accepted and rendered. A name-derived path fails this insert outright. | §69-6 |
| M2-9 | Add a second "Mortgages" under Lending; then under a different parent | Refused **on the name field** (*"Another value here already has that name"*), then accepted — two leaves under different parents may share a name. | §69-7,8 |
| M2-10 | Retire a parent that has children; then the children, then it | **409**, dialog **stays open**, *"2 values sit under this one"* — not a toast over a closed dialog. Then 204 each. Reload: gone and staying gone. | §69-10,11,12 |
| M2-11 | Add a value with `#b7791f`, then one with `red` | Colour dot; then refused on the **colour** field. | §69-17 |
| M2-12 | Open a case for editing, then **New test case** | Both end in a **blank row**; the new case opens on exactly one. **No "Add step" button** anywhere. Before this slice a new case opened on zero rows. | §94-51 |
| M2-13 | Type one word in the last row's Action, keep typing, then type in the new last row. Repeat with a **paste** | A blank row appears after the **first keystroke** and **exactly one** — not one per character, and not one per paste. The caret **stays put**: a jump means `shouldFocus` is back. | §94-52 |
| M2-14 | Type a fragment of an existing step (mock: "Refund") | A suggestion popup with action, a **"n cases"** count, and data → expected beneath. It fires **after you stop typing** — watch the network panel: one request per word, not per letter. | §94-53 |
| M2-15 | Pick a suggestion with data+expected; then one with **neither** into a row where you had typed data; then edit the action | First fills all three. Second **clears** the data rather than leaving it attached to a step it was never written for. Editing changes nothing else and no "Shared" chip appears — a pick is a copy. | §94-54 |
| M2-16 | New case: title and suite only, no step, press **Create case** | Refused with **"Add at least one step" rendered in the step panel**. Two things at once: the permanent blank row no longer satisfies the rule, and the message is *visible* — it rendered nothing before, so the button simply did not respond. | §94-55 |
| M2-17 | One step with leading/trailing spaces in all three fields, trailing row blank, save and reopen. Then type into the trailing row and clear it back to spaces, save | One trimmed step, no empty numbered step — check the **detail** page too. Whitespace is not a step: `"  "` is accepted by the API and renders for ever as an empty line. | §94-56,57 |
| M2-18 | Remove the trailing blank row with its bin button | A blank row is still there. With no Add button, removing the last row is otherwise a dead end. | §94-58 |
| M2-19 | Drag a step two places down; drop one **outside** the list; drop one back where it started; then keyboard (Tab to handle, Space, arrows, Space) | Moves and **renumbers**, text stays with its row. The two null drops leave the form **un-dirty** — on an edit form Save stays disabled ("Nothing has changed yet"); a `move(i,i)` would offer a new version of an unchanged case. ▲▼ still work. | §94-60 |
| M2-20 | Attach two preconditions, save, reopen | Both, **in attachment order** — position is content. | §95-62 |
| M2-21 | Attach one, reopen the picker; then attach one condition as **both** pre- and postcondition | The attached one is **not offered**. Both lists accept the same condition and both survive a reopen — "reset the payment data" is the canonical case. | §95-63,64 |
| M2-22 | A case whose **folder** carries conditions: open the editor, save untouched, reopen | Inherited rows render **named with their folder**, with no remove/reorder/drag, and are **still inherited** afterwards. **The slice's most dangerous defect**: an editor that seeded them into the form attaches the folder's conditions to every case anybody edits, permanently and invisibly. | §95-65 |
| M2-23 | Detach the **last** condition and save | Gone after reload. Absent ≠ empty: a client omitting the empty list reports success and changes nothing. | §95-66 |
| M2-24 | Reorder conditions with ▲▼, then drag, then keyboard | All three paths produce the same saved order. | §95-67 |
| M2-25 | Create a condition from the **+** beside the picker | Created, **attached to the list it was created from**, title rendered immediately — not "Unknown condition". Library lists are cached per search term. | §95-70 |
| M2-26 | Save a condition with a blank title, then an all-blank body | Both refused on the field. An empty condition is a picker row that attaches nothing and cannot be told from one that failed to load. | §95-71 |
| M2-27 | Save a case and leave the Suite select on **Not filed**; find it in the grid | Saves, Suite column reads **Not filed** (muted italics), and it is **in the unfiltered grid**. `suite_id IN (…)` is never true of NULL, so a grid without an explicit arm drops every unfiled case with no error and a total that still counts it. | §96-74 |
| M2-28 | Click the **Not filed** chip; then apply a suite filter and look at it again | Only unfiled cases, `unfiled=true` in a shareable URL, cleared by **Clear filters**. With a suite filter it is **disabled with the reason on hover** — not hidden, not enabled-but-inert. | §96-75,76 |
| M2-29 | Tag a case `smoke` and `regression`, save, reopen; type `smoke` again | Both survive; **no second chip** — a tag list is a set. | §96-77 |
| M2-30 | Filter bar: add tag `smoke`, then `regression` | Each Enter adds a removable chip and **narrows** — AND, not OR, deliberately the opposite of a suite's tag rules. Deleting widens back. | §96-78 |
| M2-31 | Editor on a filed case → suite **Not filed** → save, reload | Unfiled, and the detail breadcrumb reads **Not filed** linking to the grid's unfiled filter — not `?suiteId=null`, which renders every case. | §96-80 |
| M2-32 | Type a 61-character tag, save | Refused on the tags field, **never truncated** — a shortened tag stops matching the suite rule it was written for, with no symptom. | §96-81 |
| M2-33 | Create a suite with tag rule `smoke`; open it in the grid | It reports cases carrying `smoke` **wherever they are filed** — members are a query, not a filing decision. Only observable on the fixture's `Smoke` suite (TS-10). | §96-82 |
| M2-34 | Rename that suite without touching the rules, reopen; then remove the last rule and save | Rules survive the rename; removal drops the count. The form always sends `tagRules` seeded from the suite: omitting it could never clear a rule, sending `[]` by default would empty the suite on every rename. | §96-83,84 |
| M2-35 | Type a tag rule and watch the field | The chip **appears on Enter**. This phase's own defect: replacing MUI's `slotProps` to add a testid drops the adornment the chips live in, so every rule was held, sent and invisible — both gates green. | §96-85 |
| M2-36 | Hover a suite's case count; then add a tag to a case that suite claims and reload the tree | The tooltip says the count includes cases filed here, added here, **or carrying its tags — and that a case can belong to several suites, so counts do not sum to the project total.** The count then goes up **with no write to the suite**. | §96-86,87 |
| M2-37 | Look at the suite rail | A **flat list**: no expand chevrons, no indentation, every suite visible without expanding anything. Ten rows for the fixture's ten suites, sorted by name — `Card capture`, `Cart`, `Checkout`, `Legacy card flows`, `Partial refunds`, `Payments`, `Refunds`, `Settlement`, `Smoke`, `Unmatched automation`. A row missing means the payload lost it; the old tree could drop one silently by orphaning it. | §98-1 |
| M2-38 | **Click `Smoke` (TS-10) and count the grid's rows against the number in the rail** | They **agree**, and both are 2. **THIS IS THE ROW THE SLICE EXISTS FOR.** Before §98 the rail said 2 and the grid said **none**: `caseCount` was the resolved union while `?suiteId=` matched the filed arm alone. Neither number looked wrong on its own — a suite with no cases in it is ordinary — so nothing but reading them together finds it. | §98-2 |
| M2-39 | Click `Payments` (a container suite until §98, now carrying tag rule `payments`) | Cases carrying `payments`, **not** the old subtree of Card capture + Refunds + Partial refunds. It reported 26 in a 26-case project as a rollup; it now reports what it actually claims. A number identical to the old rollup means the rollup is still happening somewhere. | §98-3 |
| M2-40 | Open a suite's kebab menu | **No "New suite inside"** — gone, not greyed. Execution history, Rename, the ladder, Delete. A disabled row would advertise a capability the product no longer has. | §98-4 |
| M2-41 | Press **New**, with a suite selected in the rail | The dialog is titled **"New suite"** — not "New suite in *Payments*" — and has **no parent field**. The created suite appears in the flat list and is selected. | §98-5 |
| M2-42 | Rename any suite | Name and description only; **no parent control anywhere**, and the save does not 400. | §98-6 |
| M2-43 | Delete `Smoke`, which claims two cases by tag | **It deletes.** Then check both cases are still in the grid. A claim is not a holding: deleting the collection takes the claim and never the case. If this 409s, the delete gate widened from the filed arm to the union. | §98-7 |
| M2-44 | Delete a suite with cases **filed** in it (`Cart`, 9 cases) | **409**, and the sentence names **only a case count** — *"This suite still holds 9 cases."* No "and N suites": there are no child suites left to name, and a leftover clause would be counting something that cannot exist. | §98-8 |
| M2-45 | Open the case editor's **Suite** picker | A flat list of names, **no indentation**, sorted the same way the rail sorts. The old picker padded each option by `path` depth; a picker that still indents is reading a field the server no longer sends. **Not filed** is still the first option. | §98-9 |
| M2-46 | Open a suite's **Execution history** and compare the header's "N cases now" with the runs below | The header count is the suite's `caseCount` **verbatim** and the grid reports on the same set. §52's defect was these two disagreeing (3 cases over a table covering 47); the rollup helper that fixed it is deleted, so this is the check that it stayed fixed by the flattening rather than broken by it. | §98-10 |
| M2-47 | Scope a plan on a suite (M3 lane fixtures) and read its case count | The suite's own resolved membership. Naming a suite that used to have children reaches **only what that suite resolves** — the change the plan states out loud. | §98-11 |
| M2-48 | **Live only.** Open a report over a run recorded **before** the migration, on a suite-grouped section | Rows still print the **dotted** suite paths (`s1.s3`) those runs were recorded under, beside single labels (`s7`) for anything run after. Both are correct: `execution_fact.suite_path` is frozen history and says where a case was filed **when it ran**. Anyone "fixing" this is destroying the history. Needs a database that migrated in place — Testcontainers cannot show it. | §98-12 |
| M2-49 | Case editor → **Tags**, type `sm` | A dropdown of the project's tags that match, **most-used first** (the endpoint's order, which the control must not re-sort — alphabetical puts the project's one stray tag above the one on half its cases). Fixture: `smoke` before `smoke-extended`. An empty dropdown here means the read failed or the join lost its project scope. | §102-1 |
| M2-50 | Pick `smoke` from the list, then open the dropdown again with `sm` | `smoke` is **no longer offered**; `smoke-extended` still is. Re-picking a tag the case already carries is a no-op the de-duplicating `Set` swallows in silence, which reads as a dead menu row. | §102-2 |
| M2-51 | Type a tag **no case carries** (`brand-new-tag`) and press Enter | Accepted as a chip and saved. **THE ROW THIS SLICE MUST NOT BREAK**: `case_tag` is free text and this endpoint is a picker's list, never a validator's — a control that refused an unlisted tag makes the first use of any new tag impossible and closes the vocabulary. | §102-3 |
| M2-52 | Same three checks on **suite rename → Tag rules** and on **Bulk actions → Add tags** | Same list, same order, same freedom to type an unlisted one. A suite rule especially: it may legitimately name a tag nothing carries yet, which is how a collection gets written before its cases. **Bulk is the one to drive hardest** — its field's testid was on the wrapper until this slice, so it had never been typed into by anything. | §102-4 |
| M2-53 | Open the case editor on a project with **no tags at all** (or with the tags read failing — `tcmsFaults.only('/tags')` then `tcmsFaults.status(503)`) | The field still works as free text: type a tag, press Enter, chip appears, save succeeds. **No spinner, no error, no "no options" popup** — the suggestion list is an optional call and its absence must cost nothing. | §102-5 |

---

# M3 — plans: the ladder, the editor, the document, the seven tabs (mock)

*Stage 3 of the mock pass. **M3-5 reads the `smoke` tag M2-29 wrote** and M3-6's arithmetic needs
M2-33's suite rules, so a reload between the lanes costs both rows.*

| # | Drive | Expect | src |
|---|---|---|---|
| M3-1 | Open a plan as a Test Lead and read the header | **Three states only.** Draft → *Submit for review*. In review → *Approve plan* + *Return to draft*. Approved → *Return to draft*. **No Start, Close or Reopen anywhere.** | §97p3a-92 |
| M3-2 | ~~On an approved plan press **Edit**~~ → **obsoleted by §97 phase 5**: there is no Edit button. Open an approved plan's **Plan** tab instead | The fields are editable and there is no "Reopen the plan…" tooltip anywhere. There is no closed plan; the plan is editable. | §97p3a-93, §97p5-129 |
| M3-3 | Project settings → *Approval required before work starts* | **One** switch (cases). The plan switch is **gone, not disabled**. Toggling it leaves every other setting alone. | §97p3a-95 |
| M3-4 | Plan list ordering | Drafts, then in-review, then approved. A list that reshuffles between loads is what this ordering prevents. | §97p3a-96 |
| M3-5 | Plan list: note **TP-1**'s count, tag a case `smoke` in another tab, reload | The count **goes up with nothing written to the plan** — a section is a live selector (D7′), and the case side is the only place to see it. Until §97p3b the mock stored `caseCount` at save time. | §97p2-90, §97p3b-99 |
| M3-6 | TP-1: compare the total against its sections | The total is the **deduped union** of every arm; the sections' counts **exceed** it, because a case reached two ways is one case. A total equal to the sum is the mock resolving each arm separately. | §97p2-89, §97p3b-100 |
| M3-7 | Edit a plan's **name only**, save, check the version; then move a case into a section and save | Unchanged, then moved. Prose and name are content; the version pins what will be run. Two saves, because each passes the other's check. | §97p2-91, §74-6 |
| M3-8 | **Plan** tab → add exit criteria *pass_rate ≥ 95*; then add *open_critical = 0* | The first two get verdicts; `open_critical` renders **not evaluated** — no tick, no cross. Null means not evaluated, not unmet: the product has no issue tracker, and a red cross would tell a release meeting a gate had failed. | §74-2 |
| M3-9 | **Plan** tab → set a threshold, clear the number box entirely, save | The row is **dropped, not sent as `0`**. A `pass_rate >= 0` gate is met by every release and reads exactly like one somebody meant. | §74-3 |
| M3-10 | On a plan with folders in scope, change **only** the name on the Plan tab and save | `plan_scope_item` unchanged, `caseCount` the same, and the `PATCH` carries **neither `suiteIds` nor `caseIds`**. ~~Scope checkboxes were pre-ticked on open~~ → the picker left with the dialog in §97 phase 5 and the Test cases tab's editor is phase 6, so **a plan's suite scope has no on-screen editor in between** — a deliberate window, not a regression. An editor that sent `[]` for what it did not render would empty the plan silently, which is what this row still guards. | §74-4, §97p5-130 |
| M3-11 | Open a plan in two browser tabs, save in the second, then save the first | **409 "Someone else saved first"** in the page's own save report with *"Reload the plan…"* — the draft is compared against the seed, never the live query, so the stale `lockVersion` is the one that travels. `lock_version` had no `@Version` before §74, so it was `0` for ever and this could not fire. | §74-7 |
| M3-12 | **Plan** tab → open the metric dropdown | **Five options with readable labels** ("Pass rate (%)", "Open critical defects"), not raw enum spellings. | §74-11 |
| M3-13 | Reopen a closed plan (where the ladder still allows it) | Chip back to **Active**, `closed_at` null, audit action `plan.reopened` — not a second `plan.started`. | §73-3 |
| M3-14 | Draft plan (`TP-4`) → Delete → confirm | 204, leaves the list, the following read is a **404 page** not an empty panel. | §73-6 |
| M3-15 | A plan with runs (`TP-1`) → Delete → confirm; then open the run list | Dialog **stays open**: *"3 test runs have been executed against this plan… Close the plan instead."* Not a corner toast — nothing on that screen predicted the refusal. Each run still shows its `TP-n` chip and the link still opens the plan. | §73-7,8 |
| M3-16 | Open a plan → **Business requirements** (mock: any plan but TP-1) | `?tab=requirements` in the URL; the empty state reads *"No page is linked to this plan"* and carries a **page picker**, *Link this page* and *Open documentation* — no Start-the-document button, the template `POST` is retired. ~~A plan cannot be given a document at all in between~~ → **closed by §97 phase 5**, which is this row's picker. Not a "Something didn't load" toast — the 404-to-null in `usePlanDocument` is all that stands between the first state and an error. | §93-45, §97p2-88, §97p4-128, §97p5-131 |
| M3-17 | ~~On TP-1's document, edit a heading in place~~ → **obsoleted by §97 phase 5, which made the tab read-only.** Press **Open in documentation**, edit the heading there, come back | The tab shows the edited text and holds **no input of any kind**; `/docs` shows the same page in the tree. One page, one writer, two routes. | §93-47, §97p5-132 |
| M3-18 | ~~Type a title, pick a suite, **Add case**~~ → **deleted in §97 phase 5** with `useCreateDraftCase`: the bar required a suite (D2 says none) and created the case before Save (§5 says nothing is created until Save). The idempotency question it existed for moves to the **Test cases tab's per-row keys** (phase 6), and has no row until that tab can be driven | — | §93-48 |
| M3-19 | On a linked page's bullet, click a case chip | It **navigates** to the case and there is no × on it: unlinking a case from a block is the documentation editor's affordance now, on the screen that owns the page. Two destructive-looking controls on one read-only tab is what phase 5 removed. | §93-49, §97p5-133 |
| M3-20 | ~~Rename the **Test cases** heading, then add a case~~ → **obsoleted by §97 phase 5**: nothing matches a heading by text any more (D1's failure mode removed by removing the mechanism), so there is no stranded control to look for. | — | §93-50 |
| M3-21 | Open a plan | **Seven tabs** — Progress · Plan · Business requirements · Test artefacts · Scope · Out of scope · Test cases — with Progress still the default: every existing link to this route means the numbers. | §97p4-114 |
| M3-22 | URL → `?tab=nonsense` | Falls back to Progress. An unrecognised id reaching the body renders **nothing at all** — a blank page from a query string. | §97p4-115 |
| M3-23 | Open **Scope**, type a sentence, click **Progress**, then go back to Scope | The banner reads *"Unsaved changes in Scope"* **while Progress is on screen**, numbers underneath; the sentence is still in the box on return. §93's unmounting decision reversed: the tab is gone, the draft is not. | §97p4-116,117 |
| M3-24 | With the banner up, navigate away or reload | The unsaved-work prompt fires — `useUnsavedWork` covers the deploy-skew reload and the mid-session 401. | §97p4-118 |
| M3-25 | Press **Discard** | Back to what the server served, banner gone. **Not "empty"** — discarding to blank loses the work the control protects. | §97p4-119 |
| M3-26 | Edit Scope **and** Out of scope, read the banner | It names **both**, in tab order. One label for two edits makes the second invisible. | §97p4-120 |
| M3-27 | Press **Save**, watch the network panel | **One `PATCH`** carrying `scopeText`/`outOfScopeText` and **no `suiteIds`, `caseIds`, `sections`**. The server reads an empty list as *clear it*, so a full-draft payload empties the plan's scope on a prose edit — and succeeds either way. | §97p4-121 |
| M3-28 | After the save | Banner gone (reseeded from the response), green *Plan saved*. A banner surviving its own successful save is one nobody trusts. | §97p4-122 |
| M3-29 | `tcmsFaults.status(503, 30)`, edit Scope, Save | *"The plan was not saved"* **with the typed text still in the box and the banner still up.** | §97p4-123 |
| M3-30 | **Test artefacts** on a plan whose project has artifacts | The plan's register with project rows marked *from project* — the same `ArtifactTable` as `/artifacts`, so a broken inheritance marker breaks in one place. | §97p4-126 |
| M3-31 | **Test cases** on a plan with sections | Each section shows **what defines it** beside its count ("2 suites · 3 cases added directly · tags smoke"), and the header total carries the do-not-sum tooltip. | §97p4-127 |
| M3-32 | **Plan** tab: change the name, then switch to **Scope** and back | The banner reads *"Unsaved changes in Plan"*, and the typed name survives the round trip — the Plan tab is in the draft like the prose tabs, not a form of its own. | §97p5-134 |
| M3-33 | **Plan** tab: clear the name entirely | **Save is disabled with the reason on it** and the banner says which tab is stopping it. Typing a name re-enables it. A blank name is the one refusal the client is certain about; everything else is the server's. | §97p5-135 |
| M3-34 | **Plan** tab: type three spaces as the name | Still refused. §94's trim defect — whitespace is not a name, and the server would otherwise answer 400 for something the client could see. | §97p5-136 |
| M3-35 | Save a plan whose exit criteria name a metric the server rejects (edit the payload in devtools, or use an old client) | The refusal renders **in the criteria panel it is about**, not as a corner toast and not swallowed. `exitCriteria` has no text field to land on, and a refusal the user cannot see is the failure the whole error path exists to prevent. | §97p5-137 |
| M3-36 | **Business requirements** → pick a page that is already another plan's document | It is **offered and disabled**, with the reason in the row. Hidden, it reads as deleted and the reader goes hunting in the tree. | §97p5-138 |
| M3-37 | Link a page, then press **Unlink (keeps the page)**; then open `/docs` | The tab returns to the picker and **the page is still in the tree with its blocks and history**. The label is the check: delete is one screen away and does something else. | §97p5-139 |
| M3-38 | **Test artefacts** → **Register**, with the plan pre-selected | The scope is `PLAN` and the plan is *this* one, the kind offers only `DOC_PAGE_REF` and `EXTERNAL_URL` (no upload control — the storage port does not exist), and the registered row appears in the tab **without the plan's Save banner ever appearing**: the register writes itself. | §97p5-140 |
| M3-39 | As a Test Engineer (`artifact:edit` = `PLAN_SCOPE`), open the same dialog from this tab | Register is **enabled** and the scope selector is pinned to `PLAN` — this tab can only ever register at plan scope, which is the one scope every holder of the key has. Live-only: mock mode grants everything. | §97p5-141 |
| M3-40 | As a reader with `project:read` only, open every tab | Progress renders its numbers; the Plan tab's fields render **filled in and editable-looking with Save disabled and a reason**, never blank; Business requirements renders the page with Unlink disabled. A reader who came for the report must never be met by nothing. | §97p5-142 |
| M3-41 | **Test cases** tab: type a title in the blank row | The next blank row appears **while you are still typing**, and the caret does not move. Then check the network panel: **no `POST`** — §93's bar created the case on the click, and the model changed to make that wrong. | §97p6-143 |
| M3-42 | Reload the plan without touching anything | **No unsaved-changes banner**, and navigating away does not prompt. The trailing blank row is permanent, so anything counting rows would flag every plan page in the product from the moment it rendered (§94's refine defect). | §97p6-144 |
| M3-43 | Add a section, type one title under it and one under *Not in a section*, Save; then check the payload and the case list | **Two `POST /cases` and one `PATCH`**, in that order. The `PATCH` carries the new ids in the group each was authored under — the sectioned one under `sections[].caseIds`, the other under the top-level `caseIds`. Both cases are in the repository **filed in no suite** (D2). | §97p6-145 |
| M3-44 | Add a suite, a tag and an existing case to one section, Save, reload | Three chips, and the `PATCH` sent `suiteIds`/`tags`/`caseIds` — **references, never the cases they resolve to.** | §97p6-146 |
| M3-45 | With a section referencing a suite, add a case to that suite in another tab and reload the plan | The section's count **goes up with nothing written to the plan**. This is the live-selector claim (D7′) seen from the plan's own screen; M3-5 is the same fact from the case side. | §97p6-147 |
| M3-46 | Note a section's count, then type anything on the tab | The counts **disappear** and an info alert says they are recomputed on save. A number that stays put beside a draft that has moved is wrong in the direction of the work just done. | §97p6-148 |
| M3-47 | Rename a section that has an **unsaved** case row under it, then Save | The row moves with the heading and its case lands **in that section**, not in *Not in a section*. `NewCaseRow.section` is the section's name, so the migration is the whole of what stops a case from silently leaving the group the author put it in. | §97p6-149 |
| M3-48 | Delete a section holding a suite and a tag → **Keep them in the plan**; then repeat with **Remove them from the plan** | First: the references appear under *Not in a section* and the plan's total is unchanged. Second: they are gone and the total drops. Two answers, both defensible, which is why the dialog asks. | §97p6-150 |
| M3-49 | Add a second section with the name of an existing one | **Save is disabled** and the banner says two sections share a name. Sections have no ids — the server keys them by name — so two of one name come back as one section holding the union of both. | §97p6-151 |
| M3-50 | Reorder two sections with ▲▼, Save, reload | The order persists. `position` is sent as the array order, and the end buttons are disabled rather than being no-ops nobody can see. | §97p6-152 |
| M3-51 | As a Test Engineer **without `case:create`** (live only), open the tab | The new-case rows are **disabled with the reason**; the suite, tag and existing-case pickers still work and Save is enabled. §31 disables the section, not the Save. | §97p6-153 |
| M3-52 | Open TP-1 → **Test cases**. Watch the network panel while the tab renders | Under each group's chips, a **list of the cases it currently catches** — key, title, and the reference(s) it arrived through. **One `GET /cases` per reference**: `?suiteId=…` for a suite chip, `?tag=…` for a tag chip, never one request carrying both. The references union; `?tag=` ANDs, so a single blended request would answer the intersection and read as *catches nothing*. | §103-154 |
| M3-53 | In *Navigation* (a suite **and** the `smoke` tag), find a case that is in both | It appears **once**, with **both** references named beside it — `(Suite …, Tag smoke)`. Two arms, one row: a concat would list it twice, and naming only the first would make removing the other reference look like it should have dropped the row. | §103-155 |
| M3-54 | Live only. Point a section at a suite whose membership comes from a **tag rule** and not from filing (a suite claiming cases it does not file) | The listed cases are the suite's **resolved** membership — filed ∪ added ∪ tagged. This is the §96 defect's exact shape one screen over: a client-side `case.suiteId === id` filter lists the filed arm alone, under a count that is the union. If the list is shorter than the count beside it, that is the bug. | §103-156 |
| M3-55 | Take a tag chip off a group that also holds a suite chip | The rows that arrived **only** through that tag disappear immediately, the ones the suite also catches stay, and nothing is saved. Nothing on this screen remembers a case id — that is what keeps this a live resolution and not D7's add-time snapshot. | §103-157 |
| M3-56 | Add a suite to a group **without saving**, then read the caption above the list | The cases appear, and the caption reads *"What these references **would** catch … Nothing is in the plan until you press Save."* The served **count** beside the heading is hidden while dirty and the list is not — deliberately opposite, because the count is the server's arithmetic over the plan it holds and the list is computed from the draft. | §103-158 |
| M3-57 | A group whose only references are **individually named cases** | **No list at all** — no empty region, no caption. The case chips already draw those cases in full, and there is no by-ids read on the contract to resolve them with. Where a group has *both*, the caption says the named cases are not repeated below. | §103-159 |
| M3-58 | Point a section at a suite whose membership is currently **empty** (or a tag nothing carries) | An empty state saying *"These references catch no cases"* — a by-design emptiness, not a failure and not a filter that found nothing. A blank space here reads as a list that failed to load. | §103-160 |
| M3-59 | Arm `tcmsFaults.status(503, 30)` **after** the tab has loaded, then take a chip off to force a re-resolve | The rows stay on screen with the **stale** marker and a *Try again*, not a blank list. Then reload with the fault armed: the region shows its own error **inside the group card**, and the chips, pickers and Save stay usable. | §103-161 |
| M3-60 | `tcmsFaults.hang()` and reload | The list shows its skeleton, then the timeout budget ends it with an error — **no region spins for ever**. The reads go through `useCases`' `interactive` budget. | §103-162 |
| M3-61 | Live only. Point a section at a suite holding **more than 200 cases** | An info line above the rows: *"Suite X resolves to N cases — the first 200 are listed."* There is no all-pages read; a list that silently stopped at 200 is indistinguishable from a suite that holds 200. | §103-163 |
| M3-62 | Live only, with more than nine cases in one reference | The rows are ordered **TC-2, TC-9, TC-10** — numerically. `localeCompare` puts `TC-10` first and is invisible on every fixture under ten cases. | §103-164 |

---

# M4 — execution: runs, results, evidence, and the approval gates (mock)

| # | Drive | Expect | src |
|---|---|---|---|
| M4-1 | A run with no results → **Complete run** | **Disabled**, tooltip *"Nothing has been recorded in this run yet. Abort it instead."* Abort beside it is enabled. | §72-4 |
| M4-2 | Record one result, **Complete run** | 200, chip → Completed, and the Complete/Abort pair is **replaced by Reopen**, not joined by it. | §72-5 |
| M4-3 | On the completed run, open an execution | Four verdict buttons **disabled**, *"This run has ended, so its results can no longer change."* Attach and every Remove disabled with the same reason — **and the attachments still listed and still opening**. | §72-6 |
| M4-4 | **Reopen run** → record a result | Chip back to In progress, `completed_at` null, `started_at` **unchanged**, the result saves. | §72-8 |
| M4-5 | Abort a run that was never started, then Reopen it | Back to **"Not started"**, not In progress — the client asked for `IN_PROGRESS` and the server normalized. `started_at` still null. | §72-9 |
| M4-6 | Complete a run, then Abort from a stale tab | 409 *"Reopen it before recording a different outcome."* | §72-11 |
| M4-7 | Open a failed execution, **Attach** a PNG; then click the filename | 201 with a **rendered thumbnail** (not a broken image), correct size and your name. The filename opens the image **inline** from `/evidence/{id}/content` — live-only for the inline half; the mock serves a `blob:` URI. | §67-1,2 |
| M4-8 | Delete the row **you** attached | Enabled; removing takes the row away. | §67-3 |
| M4-9 | Attach a file **over 10 MB** | Refused **client-side**, sentence naming the limit, before any upload. | §67-8 |
| M4-10 | Record a result on a step, then attach with that step selected | The file lands **on that step**; the execution-level list stays empty. | §67-10 |
| M4-11 | Network tab on the attach | `multipart/form-data` **with a boundary**, no hand-set `Content-Type`. A lost boundary is a 400 that looks correct in devtools. | §67-11 |
| M4-12 | Attach the **same file twice in a row** | The second works — the input is cleared on change so re-picking an identical file re-fires. The first thing anyone tries after a failed upload. | §67-13 |
| M4-13 | Open an execution | Three-segment mode switch top right; **Split** default, queue rail beside the detail reading *Case N of 42*. | §100-103 ✅ |
| M4-14 | Mark steps 1–3 Passed, step 4 Failed, leave step 5 unmarked | *Failed* gains a heavier border and a **suggested** label, caption *"A step failed…"*, **every other verdict still enabled**. Fires on a half-marked checklist — failure outranks completeness. | §100-104 ✅ |
| M4-15 | Look at a step's verdict keys | **P F B S**, fixed 30×24 squares. `statusLabels[kind].slice(0,4)` rendered "Bloc" four times per row. | §100-105 ✅ |
| M4-16 | **Grid** mode: mark an untested row Failed | The row's chip becomes Failed **and the page header's chip changes with it** — the write landed in the query key the detail screen reads, not a second copy. | §100-106 ✅ |
| M4-17 | Press **B** on a Grid row, tick a precondition, record it, then open that execution in **Focus** → Notes | A dialog (never a one-click write) listing **that case's own preconditions**; the reason prefills *"Blocked — these preconditions did not hold: …"*; the comment is **there** in Focus. `PATCH` sets `comment` to whatever it is sent and an omitted comment **clears** it. | §100-107,108,109 ✅ |
| M4-18 | URL → `?mode=kanban` | Falls back to Split. An unrecognised value renders neither rail nor detail. | §100-110 ✅ |
| M4-19 | Open one execution in two tabs, record in the first, mark the same row in Grid in the second | *"Someone else recorded a result first"* **in place, on that row** — not a corner toast. Bulk marking is where overwriting a second tester is easiest. | §100-113 |
| M4-20 | Settings → Review: toggle **Only approved test cases can go into a run**, Save, reload | Still on. The **whole-body save** is the risk: this form `PUT`s all eight settings and the server reads an absent boolean as `false`. | §82-1 |
| M4-21 | With gating on, change **only** the peer-review number and Save, reload | Both gating switches **still on**. The silent-discard defect in the form it would take: an admin editing an unrelated field quietly reopens the gate, and nothing says so. | §82-2 |
| M4-22 | Turn a gate on, then both off | The amber warning appears while either is on and disappears when both are off — §6g requires the choice to be explicit and this is where "explicit" lives. | §82-3 |
| M4-23 | With cases gating on, start a run from a plan whose cases are drafts | **409 in the dialog's banner**, naming the count and the first few keys — not a toast, not a silent no-op. Read the sentence as somebody who did not configure this: does it say what to do next? | §82-4 |
| M4-24 | Same, as a caller **holding `case:approve`**; then edit the run name and press it | **Create it anyway** under the refusal, enabled — §6g's documented override, and why a 409 here is not a dead end. The retry re-reads the form, so the run gets the **edited** name; a replay would create a run the user is not looking at. | §82-5,7 |
| M4-25 | With plan gating on, press **Start** on a draft plan; then press **Submit for review** and let it be refused | 409 beside the buttons with **Start it anyway** — sentence naming review as the way forward. On the submit refusal, **no override button**: the gate can only refuse a start. | §82-8,9 |
| M4-26 | Approve a plan properly, then Start it; then create a run with **no** unapproved cases | Starts normally, no refusal, no override. Created normally with **no mention of the gate anywhere** — a gate that announces itself when satisfied is the noise that gets it switched off. | §82-10,15 |
| M4-27 | Start a plan, Close it, **Reopen** it with plan gating **on** | **It reopens.** The row the slice turns on: a target-keyed reading makes every closed plan permanently un-reopenable, showing a Reopen button that 409s for ever about approving a plan that cannot be approved. | §82-11 |
| M4-28 | Switch **all three review opt-ins off**, leave a gate on, repeat M4-23 and M4-25 | The gates **still fire** and no review panel renders. Gating reads the status ladder; review's opt-in suppresses the apparatus. A build where they are coupled looks identical until this row. | §82-12 |
| M4-29 | Gate on for project A, repeat M4-23 in project B | B unaffected. The read is `useProjectSettings(projectId)`; a cache keyed too broadly leaks A's policy into B. | §82-13 |
| M4-30 | New run → open the **Plan** select | A **"No plan — scope it below"** row above the plans, not a blank first option. A run against no plan is supported since §99, and a blank row reads as a field somebody forgot. | §99-1 |
| M4-31 | New run in a project with **no plans at all** | The info alert says *"choose the suites, tags or cases this run covers instead"* and **Start run is enabled**. Before §99 it was disabled — the row exists because `toBeDisabled()` goes on passing against a client that never grew the arms. | §99-2 |
| M4-32 | Name it, choose **Suites → Payments** only, Start | 201, and the checklist is the suite's **whole membership** — including cases filed elsewhere that its tag rules claim. A suite defined only by a tag rule must not produce an empty run. | §99-3 |
| M4-33 | Choose **Tags → smoke** only | The option list is the project's own tags **with a case count beside each**, and the run's length matches the count. Free text still accepted for a tag nobody has used. | §99-4 |
| M4-34 | Choose a plan, then add a tag | The plan select's helper text **changes** to *"Its scope is not used — the choices below are"*, and the created run is the tag's cases with `planKey` still shown on the run. The substitution rule is invisible in a form where four controls look equally live. | §99-5 |
| M4-35 | Choose a suite **and** a tag that overlap | One row per case on the checklist, no duplicates, and the total is the union rather than the sum. | §99-6 |
| M4-36 | Type a tag nobody uses, alone, and Start | 400 landing **on the Tags control**, not in the banner and not on Cases: *"This run would have nothing to execute…"*. Then add a suite that also matches nothing → the message appears on **both** controls. | §99-7 |
| M4-37 | Press Start with the name filled and nothing else chosen | Refused **client-side** under the Cases control — no request goes out. | §99-8 |
| M4-38 | With case gating on, scope a run by a **tag** that catches draft cases | 409, the same refusal as M4-23. §82's bypass with a new spelling: the gate binds on the resolved union. | §99-9 |
| M4-39 | Live only — a project with **more than 200 cases**: open the Cases picker | The catalogue is the first page, so a case past it cannot be picked. Named as a known limit (`PlanCasesTab`'s, same cause); the row exists so the absence is not read as a bug. | §99-10 |
| M4-40 | Mock only — scope a run by a tag carried by a **DEPRECATED** case | The mock's `resolveArms` **excludes DEPRECATED and the server does not**, so the two disagree by one row here. A pre-existing divergence §99 inherited by reusing the shared resolver; confirm which is right before changing either. | §99-11 |
| M4-41 | Open an execution, switch to **Grid**, click a row's **key** (`TC-…`) | The **test case** opens (`/cases/{caseId}`), not the execution. Before §104 the key went to the execution and the title was inert, so Grid mode could not reach a test case at all — and the two uuids on that row are the same shape, so a swap is a 404 nothing but a browser can see. | §104-1 |
| M4-42 | Back to Grid, hover the key | Tooltip *"Open test case TC-41"*. The affordance of a bare monospace key is not obvious; the tooltip is the sighted half of the accessible name. | §104-2 |
| M4-43 | Grid mode, click a row's **title** | That row's **execution** opens, on the pass/fail screen, and the address bar still carries **`?mode=grid`** — so Back returns to Grid rather than to Split. | §104-3 |
| M4-44 | Grid mode, key-only: Tab through one row | Two links before the verdict buttons, both reachable, focus ring visible on each. A title cell that ellipsises must not swallow the focus outline. | §104-4 |
| M4-45 | Grid mode with VoiceOver (or the accessibility inspector), read the row's links | Two **different** names: *"Open test case TC-41"* and *"<title> — record a result for TC-41 in this run"*. Two links to two places announced identically is the failure this pair exists to avoid, and it is invisible on screen. | §104-5 |
| M4-46 | Run detail (`/runs/{id}`), click the key, then the title | Unchanged from before §104 — key → case, title → execution, **no `?mode=`** (this grid is not a mode of the execution screen). The row proves the shared module did not move the grid that was already right. | §104-6 |
| M4-47 | Run detail, a case whose title is longer than the cell | Still ellipsised with the **full title** on hover. The tooltip there is a truncation affordance, not a destination one — the destination moved to the accessible name so the two do not compete. | §104-7 |
| M4-48 | Open a run with failures — read the **What failed** panel's headline | *"N cases failed · M blocked"*, with N and M matching the Result bar above. **Blocked must not be added into the failed number**, and the blocked list carries its own sentence (*"nobody could get far enough… Not counted as failures"*). The chip above merges them on purpose; this panel must not. | §105-1 |
| M4-49 | Same panel, a failed case whose tester **stopped at the failure** | *"1 of N steps failed · K not marked"* — three numbers, never two. Alternating fixtures now seed both shapes (§105 changed `runExecutions`), so one failed case has trailing unmarked steps and the next has a fully marked checklist with no *not marked* clause at all. A build that counted unmarked as passed shows the second shape correctly and the first wrongly. | §105-2 |
| M4-50 | Read the failed step rows | The step's **1-based** position, its keyword + action in the failed colour, and the step comment beneath where one exists. `position` is 0-based on the wire, so a row reading *"Step 0"* is the off-by-one. | §105-3 |
| M4-51 | Click the key on a row in the panel, then the title | §104's pair, third call site: key → the test case, title → the execution. If these behave differently from the Executions grid below, the shared module has been bypassed. | §105-4 |
| M4-52 | Open a run where **nothing failed and nothing is blocked** | *"Every case in this run produced a result and none of them failed"* — good news in words, **not** an empty table and not a spinner. The panel still renders (it is not hidden on a clean run), because a missing panel cannot be told apart from one that failed to render. | §105-5 |
| M4-53 | Same, on a run that still has **Not run** cases | The wording changes to *"Nothing has failed so far — but the run is not finished, so this is not the final answer."* Declaring a half-executed run clean is the claim this branch exists to refuse. | §105-6 |
| M4-54 | `tcmsFaults.only('/executions')` then `tcmsFaults.status(503, 30)` and reload a run with failures | The **case list still renders** (it needs no request) and the step layer shows `RunDetail.Failures.Steps.Error`. The forbidden outcome is a panel that silently reports no failing steps because the reads 500'd — an emptiness where there was a failure. | §105-7 |
| M4-55 | `tcmsFaults.hang()` scoped to `/executions`, reload | *"Reading where each case failed…"* above a **fully rendered list of failed cases** — the region wraps the step layer only. If the failed cases disappear while the steps load, the region has been wrapped around the wrong subtree. | §105-8 |
| M4-56 | Live only — a run with **more than 25 failed cases** | Every failed case is listed; the caption says step detail was read for the first 25 and names how many were not. No fixture run is this large, so the cap is unreachable in mock mode and the row is live-only. | §105-9 |
| M4-57 | Open a failed case from the panel, mark a step differently, come back | The panel's step detail **moves** — it reads `keys.execution(...)`, the same key the execution screen and Grid mode use, so no second copy of an execution exists in the cache. | §105-10 |
| M4-58 | A failed case with **no structured steps** (an exploratory case) | *"No structured steps — the verdict is on the case as a whole."* Never *"0 of 0 steps failed"*, which reads as a case whose steps all passed while the case failed. May need a `PLAIN`-format case in scope; if no fixture run has one, record that rather than skipping. | §105-11 |

---

# M5 — the review apparatus: settings, panel, comments, requests, inbox (mock)

*Stage 5 of the mock pass.* **Run M5-1 first.** §77 changed the default to "review nowhere", so
nothing else in this lane exists until review is switched on — and M4-28 has just switched all three
opt-ins off, so this is not a no-op even if an earlier row turned one on. (`L0-7` is the only chance
to see the off state on a real box.)

| # | Drive | Expect | src |
|---|---|---|---|
| M5-1 | Project → **Review** in the rail. Turn on **Test plans only**, Save | 200, "Last changed … by you". A **plan** now has the panel; a **case** still has nothing. The opt-in is per artifact type — this is the row proving the map is not read as any-of. | §77-2 |
| M5-2 | Set **Peer reviews required** to `21`, then `-1` | Red with *"Between 0 and 20."* **before any request**, Save disabled. `V23`'s CHECK is the backstop behind both. | §77-5 |
| M5-3 | Raise the minimum from 1 to 5 **while a review is open**, then approve it; then submit a new plan | The open one **still approves** (the count is pinned at submission, so goalposts do not move under reviewers already asked); the new one needs 5. | §77-10 |
| M5-4 | With **Keep the approval** (RETAIN): approve a plan, rename it. Then switch to **Return it to draft** (RE_REVIEW) and repeat | RETAIN: status stays **Approved** with §76's staleness warning if the edit wrote a version. RE_REVIEW: status becomes **Draft**, and the warning Alert on the settings screen must have been visible before saving. | §77-11,12 |
| M5-5 | Turn review **off** for plans while a plan sits In review | The panel disappears; the status chip **still reads In review**; `psql` shows the `review` row **still `IN_REVIEW`**. Switching the policy off does not withdraw submissions on anyone's behalf. | §77-14 |
| M5-6 | Turn review off entirely, then move a case Draft → In review → Approved from the bulk dialog | Every move **succeeds**. The ladder is not the apparatus — the alternative reading silently removes approval from every existing project on upgrade day. | §77-15 |
| M5-7 | Change one switch, press **Discard changes**; then change one, navigate away and back | Back to the server's values, Save re-disabled; the draft is gone on return. Nothing here is autosaved. | §77-17 |
| M5-8 | Submit a plan for review, open the panel | Chip **In review**, "Submitted by *you*", version pinned (`v1`), empty trail, and **no Approve button on the panel**. `psql`: one `review` row, `subject_version_no` = the plan's `current_version_no`. | §76-2 |
| M5-9 | Type a comment, press **Looks good**; then **Request changes** with a different comment | 201, trail gains *Looks good · your name*, the comment renders under it, **the box clears**, plan **still In review** — a peer verdict moves nothing. Then **both rows are there, oldest first**: participations are append-only, and a panel that replaced the first would hide somebody changing their mind. | §76-3,4 |
| M5-10 | As a lead, approve the plan from its own screen, re-read the panel | State **Approved**, "Signed off by *you*" with a timestamp, an `APPROVAL / Approved` row ending the trail, and **the verdict form gone** — not merely disabled. | §76-6 |
| M5-11 | With a review open, edit the artifact so it writes a version, re-read the panel | *"…has changed since it was submitted — the review covers v2, and you are looking at v3."* The version on the record must **not** move. | §76-9 |
| M5-12 | Withdraw and re-submit the same artifact twice | Newest review on top, an **"Earlier reviews"** list beneath, each with its own state and version. `psql`: exactly one row `state = 'IN_REVIEW'` per subject. | §76-10 |
| M5-13 | Have the review approved in another tab, then press **Looks good** in the stale one | **409 in place, inside the panel** — *"This review has already ended. Submit the plan for review again to reopen it."* Not a toast; the trail refreshes rather than the page. | §76-11 |
| M5-14 | Bulk-submit ~20 cases from the case list, open two | **Each has its own open review**, pinned to that case's own version. The bulk path is the common case and the one place a per-row write could silently do nothing. | §76-13 |
| M5-15 | Open the demo project's plan in review → **Comments** | Two threads: one with a reply **indented** beneath it, one greyed reading **"Resolved by …"** with **Re-open**. The reply has **no Reply button** — threads are one level deep and the server refuses a reply to a reply. | §79-1 |
| M5-16 | Write a comment, press Comment; then **Reply** on that thread | Appears at the bottom attributed to you, box cleared, `parent_id` NULL. The reply lands **under** the root and the reply form closes; `parent_id` = the root's id. | §79-3,4 |
| M5-17 | **Resolve** a thread, then **Re-open**, then Resolve twice in a row (or from a second tab) | Greys out reading "Resolved by *you*" with `resolved_at` **and** `resolved_by` set; Re-open clears **both together**; the second Resolve leaves timestamp and resolver **unchanged**. The unconditional write shipped in the first draft and hands the record to whoever clicked last. | §79-5,6,7 |
| M5-18 | Approve or reject the plan, re-open it | The conversation is **still readable** and there is **no comment box, no Reply, no Resolve**. A closed review takes no writes; the affordance's only outcome would be a 409. | §79-8 |
| M5-19 | A review with **no** comments, open then closed | Open: *"No comments yet."* with the box. **Closed: the whole section is absent**, not an empty heading. | §79-9 |
| M5-20 | Paste 5001 characters; then clear the box | Refused **in place beside the box** (*"Keep the comment under 5000 characters."*); the button disables on empty rather than letting the server refuse a blank. | §79-10 |
| M5-21 | A comment containing a blank line and a list | The paragraph break survives (`pre-wrap`). What a reviewer typed is part of what they said. | §79-11 |
| M5-22 | The same plan → **Asked to review**, above the trail | Three chips — a person, a **group** icon on "QA Chapter", and a third person appearing in **no** verdict row. The trail below still lists only the one who spoke. **Compare the two lists deliberately**: a name asked and not in the trail is the row a lead opens this panel to find, and if the lists have been merged the slice's premise is gone. | §80-1,2 |
| M5-23 | Open the picker | Project members and teams (suffixed "(team)"). **The submitter is absent**, and so is anybody already asked — the first is a server field error, the second a write that does nothing. | §80-5 |
| M5-24 | Pick a person → **Ask**; then a team → **Ask** | A chip and a cleared select; `review_assignment` with `assignee_user_id` set, `assignee_team_id` NULL, `assigned_by` you. The team chip carries the **group** icon and the team's name, **no member names** — expanding would freeze membership as it was at page load. | §80-6,7 |
| M5-25 | Press a chip's **✕**; then ask the same person twice (second tab) | Gone immediately, no confirmation, and the row is **deleted not tombstoned** — an assignment is a worklist entry. The duplicate ask changes no count and the original **"asked by"** survives; re-stamping is §79's resolution defect recurring. | §80-8,9 |
| M5-26 | Approve or reject, then re-open | Chips **still readable**, **no picker and no ✕**. | §80-10 |
| M5-27 | A plan in review nobody was asked about, open then closed | *"Nobody has been asked yet. Anyone with review rights can still give a verdict."* — and on a **closed** review with no requests the section is **absent**. | §80-11 |
| M5-28 | The demo project's **Board** | Two **Review** cards in **To do** — `TP-5` and `TC-20` — marked `linked`, reading *"Review requested by …"*. **Not** in In-review: To do is where work nobody has started belongs. The old generator drew a card per `IN_REVIEW` case, in the In-review column, assigned to the case's **owner** — a reviewer inbox shown to the person under review. If any row reads like that, it is the regression. | §81-1 |
| M5-29 | Click the key on `TP-5`, then on `TC-20` | Plan detail with the review panel, then case detail with its panel. One card shape, three subject types, three destinations. Before §81 every card routed to the case list, so a plan review searched the cases for `TP-5` and rendered "no cases match". | §81-2,3 |
| M5-30 | Switch the board to **Assigned to me** | The review cards stay. This is the inbox as people will use it. | §81-5 |

---

# M6 — reports: three console slices and two screens (mock)

*Stage 6 of the mock pass. Independent of every earlier lane — but **M6-13 and M6-20 edit source**,
which restarts HMR and resets the store, so they are last here on purpose and **both edits must be
reverted** before M7.*

Rows M6-1…M6-8 have no UI and are driven from the console on any signed-in page; `P` is a project id
from the URL, and the auth header is
`{'Content-Type':'application/json', Authorization:'Bearer '+JSON.parse(localStorage.getItem('tcms.session')).token}`.

| # | Drive | Expect | src |
|---|---|---|---|
| M6-1 | `POST /api/v1/projects/${P}/reports/generate` with `{type:'run_summary', format:'MD'}` | **202** with **all seven** contract fields: `jobId` (**not `id`**), `type`, `status: 'PENDING'`, `format`, `downloadUrl: null`, `modelUrl: null`, `error: null`. **No `requestedAt`.** `Location` → `…/reports/jobs/{jobId}`. The handler answered `{id, status:'QUEUED', …}` until §87 — `QUEUED` was never one of `ReportJob.status`'s four values. | §87-1 |
| M6-2 | Poll `…/reports/jobs/{jobId}` **three times** | `PENDING` → `RUNNING` → `COMPLETED`, one state per poll. The intermediate states are the point: a mock that completes on the first poll leaves the two states a client spends its loading life in unreachable in dev. | §87-2 |
| M6-3 | On the COMPLETED job follow `downloadUrl` and `modelUrl` | Both resolve — Markdown and JSON. A null `modelUrl` is a screen that cannot be built without a second computation. | §87-3 |
| M6-4 | Ask for `format:'PDF'`, `'DOCX'`, `type:'quarterly_vanity_metrics'` | **400 each**, error under `format`/`format`/`type`, read from the mock's own `TYPES`/`FORMATS` registries. The same request must not be a 400 here and a 202 live. | §87-4 |
| M6-5 | Generate `format: 'HTML'`, poll to COMPLETED, open `downloadUrl` in a tab | **202** (a 400 before R4 — the registries moved with the server's) and a page that renders as a **document**: `<title>`, `<h1>`, no broken frame. A fragment would make the download look like it worked while producing something no browser opens. | §88-9 |
| M6-6 | On the same job, `await (await fetch(modelUrl)).json()` | `{ header, report }` — **both keys**. `header`: `reportType`, `description`, `company`, `projectScope`, `scopeNote`, `generatedBy`, `generatedAt`, `selection[]`. `report`: `title`, `metadata`, `sections`. A bare `{title, sections}` is the shape F2 would be built against and the server does not serve it. | §88-10 |
| M6-7 | `POST /reports` (save a definition), `GET /reports`, then `POST /generate` with **only** `{definitionId, format:'MD'}` | 201 with `owner` filled; the list contains it; the generate is **202 with `type: 'run_summary'` without the request naming a type** — the whole of "one click". Poll to COMPLETED and download: the report must be **of the saved run**. | §92-40 |
| M6-8 | Re-generate with `{definitionId, format:'MD', parameters:{note:'this run'}}`, then read the model header. Then `DELETE` the definition and re-poll the first job; then generate from the deleted id | Selection lists **both** `runId` (definition) and `note` (request) — only `note` means the shallow merge became a replacement, which renders a perfect report over the wrong scope. The delete is 204, the definition leaves the list, and **the earlier report still downloads** (V28: deleting a saved report does not delete its history). Generating from the deleted id is a **400 with a field error on `definitionId`** — not 404, not 500. | §92-41,42 |
| M6-9 | Open a project → **Reports** in the rail | Header, **Generate**, and a "Saved reports" panel showing the **empty state** — not a spinner, not a table with no rows. The rail item is under **Execution**, `aria-current`, and present for **every** persona: it is ungated on purpose. | §89-14 |
| M6-10 | **Generate** → run `TR-1`, format HTML, submit. Watch the card | A card appears **immediately** reading `Queued` (the 202's body is seeded into the cache, so no skeleton), then `Building`, then `Ready`, ~2s apart. **This proves the poller runs at all** — a card jumping straight to Ready means the seeding or the interval is wrong. | §89-15 |
| M6-11 | On the Ready card click **Download HTML**, with the Network tab open | A file saves as `run_summary-{artifactId}.html`; opening it gives a whole document whose `<h1>` names **`TR-1`** — the run actually chosen, i.e. the mock read the stored `parameters`. The request carries an **`Authorization: Bearer …` header**, which is the whole reason it is a button and not a link (see L2-12). | §89-16,17 |
| M6-12 | Generate a second report while the first is `Building` | **Two cards**, newest first, each advancing on its own. The query key carries the job id; a key naming only the project makes the second answer overwrite the first's `downloadUrl`. | §89-18 |
| M6-13 | Force a failure: temporarily make `GenerateReportDialog` send `parameters: {}` | The card reaches **`Failed`**, red, carrying the worker's sentence, with **no download button and no spinner** — distinguishable at a glance from a Ready report that contains nothing. §89 added this path to the mock; `FAILED` was unreachable without a backend before. | §89-19 |
| M6-14 | Choose format **PDF** and submit | **400 under the Format field** — *"PDF rendering is not installed on this server. It can produce: MD, HTML."* — not in the top banner. PDF is offered on purpose: the enum is the contract's, not this deployment's. | §89-20 |
| M6-15 | Reload the page while a card is showing | The cards are **gone**, and that is the design: nothing lists jobs, so the screen shows what this tab asked for. Confirm the running card said so before the reload. **If this reads as broken, record it as the argument for R5 — do not patch it client-side.** | §89-21 |
| M6-16 | On a Ready card click **View report**, then **Download HTML**, and open the file beside the tab | Provenance block, section and table on both. **Compare line by line** — company, project scope, the scope sentence, who generated it, when, `Selection — runId`, then the numbers. Any difference is the drift decision F exists to prevent, and no gate can see it: the two renderers share a model and nothing checks they render it the same. | §90-26 |
| M6-17 | Read the report view's section body | Bars **proportional to the largest value in the series** with the number printed beside each, and cells reading exactly as the file's — `50.0%`, not `50%`, not `0.5`. The number is not decoration: it is what survives a reader who cannot judge length against a scale they cannot see. | §90-27, §88-12 |
| M6-18 | Copy the report view's URL, reload, then open it in a **new tab** | Renders both times — the durable handle the reports screen does not have. The breadcrumb reads *Project → Reports → the report's title*, and **Reports** links back to a screen with no cards, which is the honest state. | §90-28 |
| M6-19 | Open a report view URL for a job **still building** | *"Building — this page updates itself when the report is ready"* with a spinner — **not** a skeleton and not an error — then it **becomes the report with no reload**. Network tab: no artifact request until it completes. | §90-29 |
| M6-20 | Break the stored model: make `mockModel` return `{ report: { title: 'Half a model' } }` | A **failed region with a retry** saying the stored model is not in a shape this version can display — not an empty report, not a blank panel. This is the only rendering separating "the report contains nothing" from "this client cannot read the report"; a cast instead of a parse makes them one screen. **Undo the edit.** | §90-30 |

---

# M7 — resilience and responsive, swept once (mock)

*Stage 7, the last, and it is last because it arms `tcmsFaults` — every request is poisoned until the
switch is off, so a failure in an earlier lane run after this one would be unattributable. Disarm
between rows with `tcmsFaults.off()`.*

Every slice queued its own copy of these. They are one lane: the fault switches behave the same
everywhere, and what differs is which screen is worst to lose.

| # | Drive | Expect | src |
|---|---|---|---|
| M7-1 | `tcmsFaults.hang()`, reload **My Work**, a **team board**, and a **plan** | Loading state, then **failure on the timeout budget** — never an endless spinner. The page chrome stays on screen throughout: the header is not inside the region. | §83-9, §85-13 |
| M7-2 | `tcmsFaults.status(503, 30)`, reload, then `tcmsFaults.off()` and press **Try again**, on each of the three | Retry offered and working. On the team board it must **not** read "No such team" — a 503 wearing the 404's sentence sends the reader after a team that is still there. | §83-10, §85-14 |
| M7-3 | Load each screen, then `tcmsFaults.status(500)` and let it refetch | The board **keeps its cards, marked stale** rather than blanking. On My Work a blanked refetch reads as "your queue is clear", which is the worst possible wrong answer on that page. | §83-11, §85-15 |
| M7-4 | `tcmsFaults.expire()`, reload My Work and a team board | The session recovers **in place**. These are bookmarks opened cold in the morning, which is exactly when a token has expired. | §83-12, §85-16 |
| M7-5 | `tcmsFaults.only('/teams')` + `tcmsFaults.status(500)`, open a team board | The **board still renders** under the fallback title "Team board". The name is chrome from a second read; gating on it blanks a board that loaded fine. | §85-17 |
| M7-6 | `tcmsFaults.only('/conditions')` + `tcmsFaults.status(503, 30)`, open a case editor | The conditions panel shows its own warning and **the rest of the case still saves**. A failed library read must not take the editor down with it. | §95-73 |
| M7-7 | At **390 px**: the review panel with a two-line comment and a replied thread; the reviewer chips; the settings screen; the board with two review cards; the suite rail with ~10 folders at three depths, one badged | Nothing scrolls the page sideways. The version history is not pushed off screen; comments wrap and the reply's indent does not force horizontal scroll; chips wrap and the picker/Ask stack; the three switches, number field and mode select stack with the RE_REVIEW warning wrapping and Save/Discard reachable; cards wrap with the `linked` chip not pushing the title into overflow; the folder **name** ellipsizes rather than the badge wrapping (which is why drafts are unbadged at all). | §76-12, §79-12, §80-12, §77-16, §81-11, §75-11 |
| M7-8 | Console across the whole sweep | 0 client errors; `window.__tcmsErrors` empty. | §69-18 |

---

# The live pass — L0 → L4, one agent, one sitting, in this order

**The five L lanes are a single run, not five errands.** They share one deployment, one database and
one accumulating state, and three of them depend on what an earlier one leaves behind. Run them
back-to-back in the order below; do not interleave them with the mock lanes, and do not split them
across agents — a second agent in this tree is the incident `CLAUDE.md` records, and here it would
also mean two people writing the same company's fixtures.

**Two things must be started early and left to cook**, or the pass stalls twice for a quarter of an
hour each:

| Start it at | What | Why |
|---|---|---|
| Stage 2, immediately after the fixtures | **L4-23's seed** — one case, three `Failed` results across three runs | The health scheduler is `PT3M` then `PT15M`. Seed it now and the tick has happened by Stage 6; seed it when you reach L4 and you wait. |
| Stage 2, same moment | **One report generate** as a Test Lead (L4-30) | The report worker is on the same clock. The card must sit at Queued/Building for *minutes* — that is the row — so start the clock and read the card later. |

### Run order

| Stage | Lane | Before you start | What it leaves behind |
|---|---|---|---|
| 1 | **L0** | A **freshly provisioned company**, migrated, nothing written. Review **off** (the default) — L0-7 is the only chance to see it. | Nothing. It is read-only, and it is the only lane that is. |
| 2 | **Prologue** (below) | L0 finished and screenshotted. | Every fixture the rest of the pass needs, plus the two clocks running. |
| 3 | **L1** | The prologue's author split, the second account, and review switched **on** for plans. | Cases deleted (L1-6), evidence deleted (L1-8), a suite approved (L1-9), a plan moved down the ladder. |
| 4 | **L2** | L1's personas still to hand — L2 reuses four of the five, and its rows are the server half of controls L1 just saw disabled. | Refused writes only. Nothing lands. |
| 5 | **L3** | L1 and L2 done: L3-1, L3-2, L3-5 and L3-11 read rows *those lanes wrote*. | Nothing new; it is a `psql` read of what stages 3–4 produced. |
| 6 | **L4** | Both clocks from stage 2 elapsed. Teams still absent (L4-1 creates the first). | The company is now fully written and cannot serve L0 again. |

**Sign-in schedule.** Signing in is the expensive part, so the pass is ordered to do it five times
rather than twenty. L1 is already grouped by persona; L2, L3 and L4 name theirs per row. Where a
stage-4 or stage-6 row wants a persona you are not currently holding, batch it with the others that
want the same one rather than switching per row.

**If a stage cannot be completed** — a persona cannot be provisioned, a clock did not fire, an
endpoint 500s — finish every other row in that lane, record what was skipped and why in the slice's
handoff section, and carry on to the next stage. Do not restart the pass; L0 cannot be re-run and a
restart costs the whole company.

# L0 — the fresh instance (live, once, before anything else)

Eight facts that are true of every real installation today and false for ever after the first write.
Screenshot as you go; none of these can be reproduced. **Read-only: write nothing in this lane**, not
even to check something — the prologue is where writing starts.

| # | Drive | Expect | src |
|---|---|---|---|
| L0-1 | Company Admin → Teams & roles | "Team structure" **empty**, no-records copy. `team` has never had a writer. | §71-1 |
| L0-2 | Any run list, then open a run | Every run **"Not started"**, `started_at` null. The chip is coloured, not the grey fall-through — `NOT_STARTED` was missing from the colour map and `PLANNED` (not a run status) was in it. | §72-1,2 |
| L0-3 | Plan list | Every plan **"Draft"** in the draft colour. The old map was `Record<string,string>` missing `IN_REVIEW` and `APPROVED`. | §73-1 |
| L0-4 | Any project's suite rail | **Every folder unbadged.** `psql`: `SELECT DISTINCT lifecycle_status FROM suite` returns one row, `DRAFT`. | §75-1 |
| L0-5 | Any plan's exit-criteria panel | **Empty.** `psql`: `entry_criteria`/`exit_criteria` are NULL on every row — never written by anything, while the mock has carried hand-written criteria since Phase 1. Mock mode has been showing a feature the product did not have. | §74-1 |
| L0-6 | A case or plan already **In review** | The review panel reads **"Not in review"**. `psql`: `SELECT count(*) FROM review` is 0. The record starts now; this is not a defect. | §76-1 |
| L0-7 | Any case, suite and plan | **No Review panel anywhere** — not an empty panel, not a placeholder. `psql`: one `project_settings` row per project, all three `review_enabled_*` false. | §77-1 |
| L0-8 | A project dashboard within 3 min of a fresh deploy | "Needs attention" reads **"not computed yet"**, not "nothing needs attention". `healthComputedAt` is the only thing separating them, and §86 shrank the window to the first three minutes. | §86-5 |

---

### Stage 2 — the prologue: the fixtures, once, after L0 and before L1

**Nothing above this line has written anything. Everything below it does.**

```sql
-- as tcms_owner, with app.current_company set to the company under test

-- L1-6/L1-7: the author split. The seeded data has none.
UPDATE test_case SET created_by = '<engineer-uuid>' WHERE key = 'TC-1';
UPDATE test_case SET created_by = '<lead-uuid>'     WHERE key = 'TC-2';
UPDATE test_case SET created_by = NULL              WHERE key = 'TC-3';

-- L1-8: the unauthored evidence row. Nothing the API offers can produce it.
UPDATE evidence SET created_by = NULL WHERE id = '<third row on the failed execution>';

-- L3-3: a classification value that is assigned and THEN retired. Neither the API
-- nor the UI can produce this state, which is why it is the arm most likely to be wrong.
--   1. assign 'Cheques' to a case through the editor first, then:
UPDATE classification_value SET deleted_at = now() WHERE name = 'Cheques';

-- L3-4 / M2-7 need a Feature tree; tcms_seed_project_defaults creates the dimension and no values.
INSERT INTO classification_value (id, company_id, dimension_id, parent_id, path, name, position)
SELECT gen_random_uuid(), :company, d.id, NULL, 'payments'::ltree, 'Payments', 0
  FROM classification_dimension d
 WHERE d.project_id = :project AND d.key = 'feature';
```

Then, through the UI:

1. **A second account** in the same company, granted on the project — L1-17, L4-2, L4-9…L4-16 and
   L4-35 all need two people, and there is no way to fake one.
2. **A third grant**: `report:view` **without** `report:export`. No built-in role has that shape;
   §87 created it for the suite, and L1-20, L2-11 and L4-35 all read it.
3. **Review on for plans** (`/projects/:id/settings/review`) — L1-4, L1-13, L2-7 and every L4 review
   row presuppose it, and nothing in the review apparatus exists until it is switched on.
4. **Start the two clocks** from the table above.

---

# L1 — the permission matrix (live, five personas, one sitting)

*Stage 3 of the live pass. Needs the prologue's fixtures. Leaves deleted cases and evidence, an
approved suite and a moved plan, which L3 then reads.*

**Mock mode signs in as one persona holding every key**, so every row here is structurally
unobservable there. Walk the app once per persona rather than once per slice. The standing rule is
§31 **disable, don't hide**, and the standing trap is that a control disabled *for everybody* passes
every "is it disabled?" row — so each block ends with the tier above it confirming the control is
live.

### Viewer

| # | Drive | Expect | src |
|---|---|---|---|
| L1-1 | Artifact register; any case; evidence on a failed execution | **New** disabled (no `artifact:edit`); Delete disabled with the *ordinary* sentence (*"You don't have permission to delete test cases in this project."*) — the authorship path must not have replaced the plain gate; all three evidence rows **visible** with Attach and every Delete disabled. Evidence is part of the record of what happened. | §65-4, §66-5, §67-7 |
| L1-2 | A plan; the classification settings screen; Teams & roles | Edit **disabled**; the settings page **renders with every control disabled and the current values visible** rather than 403ing or hiding the rail item — a Viewer must be able to find out why no artifact shows a review panel; the **Review** rail item is present for the same reason. | §74-10, §77-3,4 |
| L1-3 | A plan's Scope tab: type into the box; then the Progress tab | Save **disabled with the reason**, never hidden. The box still accepts typing — the accepted cost of one draft above seven tabs; the refusal is on the write. On Progress the numbers render in full with **no disabled editor anywhere near them** — the row that catches a form spine that swallowed the read-only screen. | §97p4-124,125 |
| L1-4 | A case/plan with an open review (holds `case:comment`, not `*:review`) | Comment box and **Reply enabled**; **Resolve**, both verdict buttons, the **Ask** button and the picker **disabled**, chips carrying **no ✕**; the whole trail readable. One comment key for all three artifact types, and the reviewing tier above it. | §79-2, §80-3 |

### Test Engineer

| # | Drive | Expect | src |
|---|---|---|---|
| L1-5 | Artifact register → New | The **PROJECT** "Belongs to" option **disabled with a reason naming the restriction** — not enabled-then-refused, which is what §61 shipped. Choosing PLAN scope and submitting is a **201** landing in the plan's register. | §65-1,2 |
| L1-6 | Cases with three different authors (fixture below) | TC-1 (theirs): Delete **enabled**, and deleting works — 204. TC-2 (the lead's): **disabled**, *"You can only delete test cases you created."* TC-3 (`created_by IS NULL`): **disabled** — the arm that exists only in code and governs an installation's oldest cases. Then **create** a case and open it: Delete **enabled**. | §66-1,2,3,6 |
| L1-7 | Case list and detail, network tab | `createdBy` on every row, `null` where the column is, and **not the same person as `owner`** on the seeded rows. If they always agree the fixture did not take and L1-6 proves nothing. | §66-7 |
| L1-8 | Evidence on a failed execution | Their own row: Delete **enabled**. The colleague's: **disabled**, *"You can only remove evidence you attached."* The null-author row: **disabled**. | §67-4,5,6 |
| L1-9 | A run; an approved suite's kebab; a draft suite's kebab | Complete and Abort **disabled with a tooltip** while recording a result still works. On the approved folder **"Return to draft" and "Deprecate…" greyed** with "Execution history" live beside them — before §75 the same caller could take that approval back through the bulk case dialog with nothing on screen saying otherwise. On a draft folder **"Submit for review" is live** and "Deprecate…" is greyed. | §72-3, §75-3,5 |
| L1-10 | Case list: select one `DRAFT` and one `APPROVED` case → bulk **Set status → Draft** | The draft **changed**, the approved one **skipped** with *"Only a test lead can change a case that is already approved."*, and `changed + unchanged + skipped == total`. Before §75 both moved. | §75-4 |
| L1-11 | A plan in review, then an **approved** plan (holds `plan:edit`, not `plan:approve`) | In review: **Approve disabled**, "Return to draft" **enabled**. Approved: the same "Return to draft" button now **disabled**. The same control, the same target, gated by where the plan is. | §73-4, §97p3a-94 |
| L1-12 | An approved plan under **RE_REVIEW**: edit it | **The edit succeeds and the plan drops to Draft.** This looks wrong and is the design (`EditAfterApproval.RE_REVIEW`) — charging the approve key makes an approved plan uneditable by the people expected to edit it. | §77-13 |
| L1-13 | A plan in review: the reviewer picker (holds `plan:review`, not `plan:approve`) | Picker and **Ask enabled**. The gate decision made visible: the request must not be confined to the people whose sign-off it prepares. | §80-4 |
| L1-14 | A run's Grid mode (holds `result:submit`, not `result:amend`) | A row that already has a result: four keys **disabled with the amend tooltip**; an untested row's are live. The permission is **per row**, because half a run may be recorded and half not. | §100-112 |
| L1-15 | Start a run from a plan of draft cases with cases gating on (no `case:approve`) | **Create it anyway** is **present and disabled** with the gate's why-tooltip. Hidden instead of disabled is the defect: the person who has to go and ask would not know the way out exists. | §82-6 |
| L1-16 | Teams & roles (no `company:teams`) | The three admin buttons disabled with a reason, **Board live**. A Board button written as a fourth child of that gate looks identical until this sign-in. | §85-2 |

### Test Lead / Company Admin — the control-is-live half

| # | Drive | Expect | src |
|---|---|---|---|
| L1-17 | As **Test Lead**, repeat L1-5, L1-6, L1-8 | PROJECT scope **enabled and selectable**, submitting lands a project-scope row; Delete **enabled on TC-2 and TC-3**; Delete **enabled on all three** evidence rows. Without these, every "disabled" row above passes equally well against a page broken for everyone — and `useCan` is **fail-closed while pending**, which is exactly how §66's own component test passed against a page with the wiring deleted. | §65-3, §66-4, §67-6 |
| L1-18 | As **Test Lead**, a suite kebab: approve a folder, then reopen it; then deprecate one and reopen it | After approval **"Submit for review" is not offered at all** — the moves swap rather than accumulate, and offering it would be a control the server 409s. After deprecation **"Restore to draft" is the only move**, with a muted rather than coloured badge. | §75-6,7 |
| L1-19 | As **Company Admin**, the classification settings screen; add a Test Type value; check a **second project** | Every panel's controls enabled, **including Test Type's**. The new value is offered in the sibling project too — that is what "company-wide" means and why the permission is separate. A page asking `project:settings` for every dimension renders *identically* for this persona and hands a Test Lead a button the server 403s. | §69-14,15, §77-3 |
| L1-20 | As the `report:view`-without-`report:export` persona: the Reports screen; then a Ready card's Download; then a completed job's **report view URL**; then save/list/delete a definition and try to generate from it | **Generate disabled with a why-tooltip**, rail item still there, polls and definition reads working. Download → **403 handed back beside the button in the card**, not a corner toast, card and status still on screen (the route's 403 is empty-bodied, so this also confirms `describeError` produces a sentence from the status alone). The report view → job renders, report **refused with the worded 403** *"You can watch this report being made, but not open it"* — **this is a finding, not the expected product behaviour**: §6e's split is "may read on screen, may not carry off", and today the screen costs the export key because the model is served through the download route. The three definition operations **succeed** and the generate is **403**: saving a selection is not exporting a report. | §89-24,25, §90-31, §92-44 |

**Fixtures for this lane are the prologue's**, run at stage 2 — the author split (L1-6/L1-7), the
unauthored evidence row (L1-8), the second account (L1-17), the `report:view`-only grant (L1-20) and
review switched on for plans (L1-4, L1-13). If any of them is missing, go back and run the prologue
rather than improvising one here: L1-7 exists precisely to catch a fixture that did not take.

---

# L2 — the server's refusals (live, curl / devtools)

*Stage 4 of the live pass. Reuses L1's personas — keep them to hand rather than re-provisioning.
Lands nothing: every row is a refused write.*

The disabled control is a courtesy; these rows are the rule. Several are unreachable from any UI.

| # | Drive | Expect | src |
|---|---|---|---|
| L2-1 | `GET /me/permissions` on any authenticated screen; then walk the case grid, the board, the dashboard | `permissions: [{key, qualifier}, …]`, **sorted by key**, no entry whose qualifier is `NONE`. **No regression across the eleven gated affordances** — the wire shape changed under all of them at once and `useCan` falls **open** on error, so a consumer left on the old shape looks *normal*, not broken. The highest-risk row in the §65 block. | §65-5,6 |
| L2-2 | As a Test Lead, `POST /classification/values` with the **sibling project's** dimension id | **404, not 403** — the caller must not learn which permission would have applied. | §69-16 |
| L2-3 | Write to a **completed** run (devtools, or a tab opened before the close) | **409** *"This run has ended…"* — not 403, not 500. | §72-7 |
| L2-4 | `PUT …/status` with `IN_REVIEW` on an **APPROVED** suite | **409** *"Only a draft suite can be submitted for review. Return this suite to draft first."* — and the same sentence, prefixed with the folder's name, in the rail's own alert if reached through the UI. | §75-8 |
| L2-5 | **Close** a draft plan (no such control exists) | **409** *"Only a plan that is being executed can be closed…"*. The absence of the button is the point; the server refusing anyway proves it is not a UI convention. | §73-5 |
| L2-6 | Force a save on a **closed** plan | **409** *"This plan is closed… Reopen the plan to change them."* | §74-9 |
| L2-7 | With peer minimum 1 and *the submitter cannot approve* **on**: submit a plan yourself and approve it | **409 in place** — *"You submitted this plan for review, and this project asks somebody else to approve it."* Plan stays In review. **Not a 403**: the permission is held and the policy is what refuses. | §77-6 |
| L2-8 | Attach a file with `max-file-size: 1MB` temporarily set, 2 MB file | **413** with readable copy (*"Evidence files are limited to…"*), never a bare 500. The container refuses before the handler runs, so this is a completely different path from the client-side M4-9. | §67-9 |
| L2-9 | `GET /api/v1/work-items` with **no parameters** | **400, "Choose a board"**, naming both requests that do work. The SPA never sends it; this confirms the refusal its shape is built around exists. | §83-19 |
| L2-10 | `GET …/work-items?teamId=<uuid>&mine=true`; then `?teamId=<team in another company>`; then `?teamId=<valid>&projectId=<valid>` | **400 "Choose one board"** (unobservable in the body for a caller on the team, so the status is the only evidence). **404 with an empty body, identical to an unknown UUID** — any difference makes the operation a company-wide team-existence oracle (§6t). **200** with the team's work inside that project: legal, no screen sends it, and the mock answers it — this row stops the mock's answer being the only definition. | §85-23,24,25 |
| L2-11 | As the `report:view`-only persona: `POST …/reports/generate`, then poll an existing job, then follow a completed job's `downloadUrl` | **403 with a problem body**; poll **200**; download **403 with an empty body** — deliberately bodiless because the response is typed to a binary resource. Confirm the last does not render as a corrupted download. | §87-6,7 |
| L2-12 | As a Test Lead, paste a completed job's `downloadUrl` into the **address bar**, then download the same report **through the screen's button** | Address bar **401**; button gives the file. The session is a bearer token in a header and `SecurityConfiguration` is `STATELESS` with no cookie, so **no `<a href>` into `/api/v1` can ever authenticate**. This supersedes §87's row 8, which assumed a navigation would authenticate. | §89-22 |
| L2-13 | As a Test Lead, follow `downloadUrl` from the app | The file **downloads** — `Content-Disposition: attachment`, named `run_summary-{artifactId}.md`. Evidence is served `inline` (a screenshot belongs beside the step it failed on); a report is a file somebody keeps. | §87-8 |

---

# L3 — the invariants only `psql` can see (live)

*Stage 5 of the live pass. Reads what stages 3–4 wrote — L3-1, L3-2, L3-5 and L3-11 have nothing to
look at if L1 and L2 have not run. No browser needed except where a row says to drive something.*

Every row here looks identical on screen whether it holds or not. That is why they are written down.

| # | Drive | Expect | src |
|---|---|---|---|
| L3-1 | Delete a case you authored (L1-6) | `SELECT deleted_at FROM test_case WHERE key='TC-1'` is **set** — the delete is soft, as `CaseDeleteDialog`'s copy claims. | §66-8 |
| L3-2 | Delete evidence you attached | The `evidence` **and** `attachment` rows are gone **and the file is gone from `TCMS_EVIDENCE_ROOT`** — a hard delete. The one place a partial write is rolled back by nothing: a failure between the two leaves a row whose blob is gone, or worse in the other order. | §67-12 |
| L3-3 | Assign a classification value, **then retire it**, then open the editor and save any other change; reload and save again | The retired value has **no checkbox** and its `case_classification` row is **still there** afterwards — both times. **The only silent failure in §68**: if the bound is wrong the save succeeds, the response looks correct, and a classification is gone with nothing having mentioned it. Neither the API nor the UI can produce this state, which is why it is the arm most likely to be wrong. | §68-8,9, §69-13 |
| L3-4 | Rename a classification value | `path` is **byte-identical**. Nothing in the UI shows a path; if a rename rewrote it the only symptom appears later in §6q's descendant matching, which is not built — so it is checked here or not at all. | §69-9 |
| L3-5 | Complete a run, then Complete it again | 200, `completed_at` **unchanged**, **one** `run.closed` audit row. | §72-10 |
| L3-6 | Plan: Submit → Approve → Start → Close | `status` moves, `closed_at` set **only** on the close, `updated_by` is the actor, and `audit_event` holds **four** rows — `plan.submitted`, `plan.approved`, `plan.started`, `plan.closed` — each with `from` and `to`. | §73-2 |
| L3-7 | Edit a plan's **scope** (tick a folder), then edit **only** its name | Scope: `test_plan_version` gains a row whose `snapshot` names both suites, `current_version_no` = 2. Name: **no new version row**, `current_version_no` unchanged. A plan version is its scope (§6j); a name is content — and an implementation that bumps on every save looks identical on screen. | §74-5,6 |
| L3-8 | Press a suite move twice quickly (Approve → Approve); then check `suite_version` | Second is **200 with no second audit row**: exactly one `suite.approved`, `updated_at` unchanged. And **no new `suite_version` row**, `current_version_no` unchanged — a suite version is its composition, and approving does not change what is in the folder. | §75-9,10 |
| L3-9 | Retag a case (add a tag, save), open its version timeline | **No new version.** Tags are kept off `CaseEdit` and out of the content hash — a version whose diff says nothing about the test having changed is the empty-diff noise §6j added the hash to keep out. | §96-79 |
| L3-10 | Under RE_REVIEW, edit an approved plan | An audit row `plan.re_review_required` naming you, `from APPROVED to DRAFT`. | §77-12 |
| L3-11 | Create a run with the gate **overridden**, then one with the gate off | `approvalGateOverridden` on the first row and **absent** on the second. **No audit screen exists** (`audit:view` has no consumer), so this is a read against `audit_event.changes`. | §82-14 |
| L3-12 | Members dialog: tick a person, close, reopen, untick | 204 both times, no duplicate row: `team_membership` gained one row and lost it. | §71-13 |
| L3-13 | Deprecate a folder that holds cases, open the case list scoped to it | The cases are **still there and still editable**. Deprecating is a label on the folder, not a soft delete of its contents — nothing in this slice cascades. | §75-12 |

---

# L4 — what only a real instance produces (live)

*Stage 6, the last. Both stage-2 clocks must have elapsed — L4-23…L4-29 read a scheduler tick and
L4-30 reads a report worker tick, and neither can be hurried from the app. Teams must still be
absent: L4-1 creates the first one this company has ever had.*

### Teams (the whole screen was unreachable before §71)

| # | Drive | Expect | src |
|---|---|---|---|
| L4-1 | New team → "Platform" → Create | Appears in the tree; `SELECT id, name, parent_team_id FROM team` shows **exactly one row**, `company_id` the caller's. | §71-2 |
| L4-2 | Grant a role → subject **Platform (team)** → Test Engineer on a project → Members → tick a colleague | The colleague's own session (second window) lists that project on `/projects` **without signing out and in**; unticking removes it from their rail on the next navigation. **This is §71**: the team-grant path has existed since M0 and could never be exercised, because no team could be created for a grant to name. | §71-3 |
| L4-3 | Blank name → Create; then create "QA" and then "qa"; then A, B-under-A, and edit A's "Sits under" | Field error, dialog open. Second refused naming **the stored spelling** (`already has a team called "QA"`) — case-folding is the point and an exact-match check accepts it. B is **not offered** as A's parent; a legal parent saves. | §71-5,6,7 |
| L4-4 | Edit a nested team → "Sits under" → "No parent" → Save | It moves to the root. **The PUT-not-PATCH decision is this row**: under PATCH the operation is unreachable. | §71-8 |
| L4-5 | Delete a team that holds a grant; then one with children | **409** listing *"1 role grant is made through this team…"* — cancel and the grant is still in the Role assignments table. Then 409 naming the child count; delete the child first, then the parent → 204. | §71-9,10 |
| L4-6 | Members dialog on a team that holds a grant | The **warning banner** names the projects and says anyone added gets them immediately. Ticking a person survives close-and-reopen (the list refetches from the mutation's invalidation, not a snapshot). Ticking "Leads" for a non-member is **disabled with a tooltip** until "In team" is ticked. | §71-11,12 |
| L4-7 | `UPDATE team SET parent_team_id = gen_random_uuid() WHERE name = 'Platform';` — and a cycle | Both surface **at the root** rather than being dropped. The tree is the **only** place a team can be edited or deleted, so a row it drops is a row nobody can fix. The API refuses both twice over, so `psql` is the only way in. | §71 note |
| L4-8 | Delete a team that has an outstanding review request | The chip is gone on the next load and **the person asked individually is untouched**. The decision §71's 409 is deliberately *not* extended to — what cascades is a request, not authority — and the DDL is the only thing enforcing it. | §80-13 |

### Reviews across two accounts

| # | Drive | Expect | src |
|---|---|---|---|
| L4-9 | Submit as A, **send it back as B**; then submit as A and **withdraw as A** | **"Sent back"** with a trail row `APPROVAL / Sent back` naming **B**; then **"Withdrawn"** with **no verdict row added**. The same ladder edge, and they must read differently — this is the pair `V22` added a state for. | §76-7,8 |
| L4-10 | Comment as one account, resolve as another | The thread names **two different people** — author on the remark, resolver on the resolution line. The pair is why `V24` added the actor column. | §79-13 |
| L4-11 | Peer minimum 1: have B say **Looks good**, then approve as A. Then repeat with B saying Looks good → **Request changes** → approve | First succeeds. Second **refused** — *"…needs 1 peer review … and has 0."* The latest verdict is what counts and the trail shows both. **The single most valuable row here**: the cheap implementation passes the first perfectly and fails only this. | §77-7,8 |
| L4-12 | Author rule **off**: press Looks good as the submitter, then approve | Still refused on the peer minimum — *"has 0"*. The submitter's own LGTM is not a second opinion, and the two settings are independent. | §77-9 |
| L4-13 | Ask a **colleague** to review, sign in as them, open the board | A Review card for that artifact on **their** board naming the lead as the asker. **The row §81 exists for**; §80's row 14 recorded its absence. | §81-6 |
| L4-14 | Ask a **team**, sign in as any member; then **add somebody to that team** and reload their board | A card each for every member, none of them named on an assignment row; the new member's card is waiting. Expansion happens on the **read** — write-time expansion would have frozen the list at the moment somebody clicked. | §81-7 |
| L4-15 | As the person asked, leave a verdict (LGTM **or** Request changes), reload the board | **The card is gone** — on "changes requested" too, deliberately: they have done what was asked and the ball is with the author. A card that stays until they approve reads as work the reviewer still owes. | §81-8 |
| L4-16 | Approve or reject the artifact, reload every asked person's board; then soft-delete a case with an open review somebody was asked for | All cards gone though the `review_assignment` rows are untouched — the board reads `review.state`, and unassigning is the only thing that deletes a request. The deleted case's card disappears; nothing in the schema removes the review, because the subject triple has no FK. | §81-9,10 |

### Content, corpus and health

| # | Drive | Expect | src |
|---|---|---|---|
| L4-17 | On a real project, type a step fragment used by several cases; then soft-delete a case using the top suggestion and repeat; then repeat on a **second project** | Counts are **distinct cases** (a case repeating a step twice adds one). The deleted case's usage **drops out**. The second project offers **its own** steps and none of the first's. All three are unobservable in mock: the fixtures give every structured case the same four steps and `DELETE` removes the row. | §94-59 |
| L4-18 | Drag a step in a case that has evidence or results against it | Steps reorder and the **evidence stays with its step**, not the position (`step_result` FKs to `test_step.id`). If this were ever false it would be found by a tester reading someone else's evidence under the wrong step. | §94-61 |
| L4-19 | Edit a condition attached to several cases: read the impact line, confirm, open a **different** case that attaches it | The line gives **two separate figures** — cases attaching directly, suites attaching — never one blended number. The other case shows the **new** text: the attachment is a reference and the edit propagates with no prompt. | §95-68 |
| L4-20 | With the condition edit dialog open, have somebody else change that condition, then save | **409 in the dialog** saying to reload and reapply — not a toast, not a silent overwrite. `condition.lock_version` was mapped for the first time in §95 **with** `@Version`; §74 found three entities where the plain-column spelling makes the precondition unable to fail. | §95-69 |
| L4-21 | Case editor as a user **without `library:manage`** | Attach, detach and reorder still work (attaching is a case edit); **create and edit refused**, legibly rather than as a bare 403. | §95-72 |
| L4-22 | Re-run `provision-company` against an existing company, open a project's status vocabulary; then record a **WIP** result and open the plan's Progress tab | A seventh status **WIP** — blue, not final, not counting as a pass. Existing projects do **not** get it from the migration (M9's decision), so this is the check that the documented top-up path works. A WIP result counts as **not run**, not as a verdict, and coverage does not move. | §97p3a-97,98 |
| L4-23 | One case, **three `Failed`** results across three runs (the unique constraint forces one run each). Wait for the tick (`PT3M` then `PT15M`), open the dashboard | **"Failing repeatedly"** tile, count **1**, severity WARNING (amber not red). Before §86 it could only ever read 0. | §86-1 |
| L4-24 | Same case, record a fourth result **`Passed`**, wait, reload | The tile drops to **0** and the chip disappears. **The self-healing property §6o rests on** — "no 'mark as not-stale' button exists to lie with" is only true if the computation takes it back. A latching signal is indistinguishable on screen from a correct one. | §86-2 |
| L4-25 | Three **`Blocked`** results on a different case, wait, reload | **Not listed.** The predicate is `status_kind = 'FAILED'`, not "did not pass" — a blocked case is an environment problem, and flagging it sends the reader to fix the wrong thing. | §86-3 |
| L4-26 | With L4-23's case flagged, open the board | A **`CASE_MAINTENANCE`** card naming the signal, in the right column, whose `linkFor` **resolves**. Structurally empty since §53: two of nine signals had a writer and the card was built around neither. | §86-4 |
| L4-27 | Soft-delete a flagged case, wait a tick, reload | Tile count drops, case gone — **and the fact log keeps the deleted case's results**. Two opposite calls in one slice, and the screen is where their consistency is legible: no orphan chip, no report row lost. | §86-7 |
| L4-28 | Two companies, both with a failing-repeatedly case; sign in as a member of one | Only that company's case is counted. The projection carries **no company predicate** — `FORCE ROW LEVEL SECURITY` is the entire mechanism on both the `SELECT` and the `INSERT`. | §86-6 |
| L4-29 | A project of a few hundred cases: dashboard response time before and after a tick populates `failing_repeatedly` | No visible regression. **`V1` ships four partial indexes on `case_health` and none on `failing_repeatedly`**, so this tile is the first that has to scan. Found while reading the DDL and deliberately not fixed — if this shows a real cost, the index belongs in R3's `V27`. | §86-8 |

### Reports, documents and the deployed build

| # | Drive | Expect | src |
|---|---|---|---|
| L4-30 | As a Test Lead, generate a report and poll | **202**, then a job reaching `COMPLETED` **without polling advancing it** — the real worker is `PT15M` after a `PT3M` delay, so this waits for a tick or follows a restart. The mock's poll-driven clock must not be what a client depends on. The card sits at Queued/Building for **minutes**, and the copy has to hold up over that duration — a client that only ever saw the mock has copy written for a two-second wait. | §87-5, §89-23 |
| L4-31 | Generate `format: 'HTML'` for a real run and open `downloadUrl` | The provenance block at the top: company, project scope, the §390 scope sentence, who generated it, when, `Selection — runId`. Then **turn the network off and reload the saved file** — identical. "A self-contained file that needs a CDN to draw its diagrams is not self-contained"; the unit test asserts the absence of URLs, only a browser proves it *looks* like a report without them. | §88-11 |
| L4-32 | Same document: read the bar chart, then **Print preview** | Bars in CSS with the number beside each, and **no table row split across a page boundary** (`break-inside: avoid`). This page is what the PDF slice hands to a headless browser, so print is the deliverable's dry run — and the number beside the bar is what survives a mail client that strips the stylesheet. | §88-12 |
| L4-33 | Name a project (or run) `<img src=x onerror=alert(1)>`, generate HTML, open the file | The text appears **as text** — no alert, no broken layout. Every value in a report is user-controlled; the escaping is asserted by a counted unit test and this is the only place it meets a real parser. | §88-13 |
| L4-34 | As a Test Lead, open a real report's **on-screen view**; then print it and narrow to phone width | Header carries the **real** company, `KEY — Name` project scope, the scope sentence, the signed-in display name and a **UTC-labelled** timestamp (the mock's are literals) — compare `Generated at` against the run's own timestamps for a sensible timezone. Tables scroll **inside their own container** (`ScrollX`) rather than the page, and the print view is legible. | §90-32,33 |
| L4-35 | Two people on one project each save a report definition, then each list; then B `DELETE`s A's id | **Each sees only their own**, and the delete is **404** with the row surviving. Structurally unobservable in mock, which signs in as one persona — "mine" and "the project's" are the same set there, and dropping the owner filter passes every mock check. | §92-43 |
| L4-36 | A member with **no project grants at all**: My Work, then any team board, then the rail | **`200 []`** rendered as the empty state on both — never a 404, never an error. The personal board names no project, so nothing can fail to resolve; this is the first screen a new member sees and it must read as an empty queue, not a broken product. The rail shows **My work** and **All projects** with an **empty** switcher (a folder icon, not a key, when collapsed), and the picker says "You don't have access to any project yet". | §83-18, §85-21, §91-39 |
| L4-37 | A user with grants on **two** projects: My Work; then user A and user B (granted on one of the two) opening **the same team board** | A sees cards from both. **B sees strictly fewer cards** — the ones from the project they hold no grant on are absent, with no error. §84's headline decision, and unreachable in mock: the fixture has no project the caller is barred from. | §83-17, §85-20 |
| L4-38 | A member **not on the team** opens its board | It renders. Membership is not a requirement (§6k) — a team board a lead's manager cannot open is a board for nobody. | §85-22 |
| L4-39 | Footer: `npm run dev`; then `VITE_APP_VERSION=9.9.9 npm run build && npm run preview`; then after a real `gh workflow run deploy.yml -f version=<v>`, hard-reload the deployed site; then deploy a second version | **`TCMS dev`** (a plausible number here means a hardcoded literal is back — the exact defect this replaced), then **`TCMS 9.9.9`** proving the `define` reaches the emitted bundle, then **`TCMS <v>`** matching the dispatched input and the GHCR tag, then a **changing** number. One correct reading is consistent with a literal that happens to match; two are not. The `web` job greps `dist/assets/` for the dispatched version, so the automated half is covered — the deployed reads are the part a human must see. | footer 1–4 |

### The M2 rollups — §101 (live only, and structurally so)

*Every row here is live-only, and not by preference. **The mock computes from fixtures on every
request, so it truthfully answers `computedAt: null` on both endpoints** — which means mock mode can
render the "Computed now" branch and nothing else. The rollup branch, the two ages side by side, and
the stale-arithmetic wording cannot be produced there at all. That is §67's and §68's live-only
argument arriving through a **field** rather than through a fixture gap.*

*These rows need the **scheduler tick** — `PT3M` initial delay, then `PT15M` — so they belong beside
L4-30's report-worker wait and cannot be hurried from the app.*

| # | Drive | Expect | src |
|---|---|---|---|
| L4-40 | Record a result against a plan's run, then open **Plan → Progress** immediately, before any tick | Counts include the result just recorded, and the caption reads **"Computed now"**. **This is §101's third finding as a screen**: the first cut read the empty rollup and reported every case as not-run, which renders exactly like a plan nobody has started. The absence of "updated every 15 minutes" is the assertion — a cadence sentence beside a live figure is a false claim. | §101-13c |
| L4-41 | Wait for one tick (or restart the app to take the `PT3M` delay), reload the same page | Same numbers, and the caption now reads **"Figures as of HH:MM (Nm ago)" — updated every 15 minutes**. Two loads of one page answering the same numbers from two sources is the whole point; a difference here is a rollup that disagrees with the live path and `RollupAgreementTest` failed to see it. | §101-1c |
| L4-42 | On the same page, switch the aggregation dropdown to **Worst**, then back to **Latest** | Worst answers **"Computed now"**; Latest answers a timestamp. The same screen legitimately has two staleness behaviours depending on a dropdown, and this row is the check that the reader can *tell* — §65's two-contradictory-percentages defect is this with the difference hidden. | §101-4 |
| L4-43 | A plan whose executions are **older than 14 days** but whose rollup ran minutes ago | **Two lines, both true, neither styled as the other.** "nothing has been executed against this plan in over two weeks" in the blocked colour, and "Figures as of …" in secondary text below it. One line would have to pick which age it meant, and whichever it picked would be wrong half the time. | §101-5 |
| L4-44 | Open a **suite history** for a tag-defined suite after a tick | Rows for every run its cases ran in, with the caption's timestamp. **A tag-defined suite is the discriminator** — the one-column arm would show an empty history here and a populated one for a folder, and the two look equally plausible. Compare the pass rates against the same suite's grid count. | §101-13d |
| L4-45 | Record a result in a **new run** of that suite, then reload the history **before** the next tick | The new run **appears**, at the top. Not a stale number — a row that is not there is what the rollup alone would give, and the person who has just finished a run is the first to meet it. | §101-13j |
| L4-46 | On a suite whose runs are **all** newer than the last tick | Every row present, caption **"Computed now"**. The all-tail case: `computedAt` is the oldest rollup instant *used*, so with none used the honest answer is null. | §101-2 |
| L4-47 | Tag a case that ran in an **old** run into a tag-defined suite, wait a tick, reopen that suite's history | **The old run's totals do NOT move.** §101's first finding, and the only place it is visible: `membership_snapshot` freezes what the suite held when the run happened (§6p open question #32). A total that moves from 43 to 44 is a past pass rate rewritten, and both readings look equally correct. | §101-13a |
| L4-48 | Remove a case from a plan's scope, wait a tick, reload Plan → Progress | The case is gone from the totals and from **Not run**. The prune its sibling must never grow — without it the plan's totals stay too high for ever with no row that looks wrong. Cross-check `SELECT count(*) FROM plan_snapshot WHERE plan_id = …` in `psql` before and after. | §101-13b |

---

# Not reproducible in a browser — recorded so the absence does not read as an oversight

- **Simultaneous writes with no `If-Match`.** Two testers closing a run as the last result lands; two
  leads moving one suite; a lead approving while another returns a plan to draft; two admins saving
  the review settings form. The target state is absolute in each, so both orders are legal — except
  the settings form, where `project_settings.lock_version` **is** maintained but **nothing sends the
  header**, so the second write wins silently. Adding it is a later slice; the column is annotated
  now precisely so that slice does not first have to discover it was reading `0` for ever (§74).
- **The true optimistic-lock race on a plan**: two editors saving *after both passing* `If-Match`.
  `@Version` catches it at flush and `PlanController#onWriteRace` maps it to the same 409, so the
  handler is reachable in principle and unverified in practice.
- **Two people submitting one artifact for review in the same instant.** `V22`'s partial unique index
  decides it; the loser gets a constraint violation, not a second open review. Queued as a backend
  check.
- **A sibling company's account offered as a team member, or asked to review.** No picker in either
  mode lists them, so the refusal is unreachable from the UI — asserted at the server
  (`TeamEndpointTest`: *"a SIBLING COMPANY'S user is refused — 404, and no row is written"*).
- **Comment anchors.** §6g wants comments anchored to a step or field; `anchor_type`/`anchor_id` are
  NULL on every row and accepting one needs a `PeopleDirectory`-shaped port into `repository` first.
- **A cross-project review card on a personal board.** §6l's "My Work" board exists but the *board*
  is per project, so the reviewer inbox is per project too.
- **§70 had nothing to drive at all.** It changed `contractIntrospection.ts` and two test files;
  `grep -rln contractIntrospection src/` returns exactly those two, so it is in no bundle and the
  build output was byte-for-byte §69's app. What stands in for it is one command:
  ```bash
  cd frontend && grep -rn "api\.delete" src/     # must return NOTHING — the client's verb is `del`
  npx vitest run src/api/contractOperations.test.ts src/api/apiResponseTypes.test.ts
                                                 # 8 DELETE call sites, 1 upload among them
  ```

# Still owed, found and not fixed

- **The plan's case chips resolve through the case list's first 200 rows** (§97 phase 6). There is no
  by-ids read on the contract, so a plan naming an individual case outside that page shows *"a case
  outside the loaded list"* — removable, but unnamed. Not reproducible without a project of that
  size; the fix is a server-side read.
- ~~**Three tag controls still offer an empty option list** — the case editor's, `SuiteDialogs`'
  `tagRules` and `BulkActionDialog`'s. `GET /projects/{id}/tags` exists since §97 phase 3b and has a
  consumer since phase 6; wiring the other three is a three-line change §97 deliberately did not make
  in a slice that does not own those screens.~~
  → **superseded by §102**: all three now read `useProjectTags`. It was not quite the three-line
  change this entry predicted — `BulkActionDialog`'s locator turned out to sit on the `TextField`
  root rather than the input, so the field could never be typed into and no suggestion could have
  been driven; §96's slot trap in the form that hides longest. See M2-49…M2-52.

- **The artifact register's plan filter is `useState`, not the URL**, so a plan's register cannot be
  linked to while every other filtered screen can. An older gap than §65; tracked here.
- **§100's rows 101–110 were run in *mock* mode** at the user's explicit direction (2026-08-05,
  overriding §65 for that slice only) — marked ✅ in M4 and M1. `CLAUDE.md` says live is the one that
  counts, so **re-run M4-13…M4-18 in live mode** when credentials are at hand.
- **§100 row 111 could not be driven**: `resize_window` had no effect on the rendered viewport, so
  the slice's one responsive claim — Split degrading to Focus below `lg`, dropping the rail rather
  than squeezing it — is unverified. Retry it by resizing the window by hand.
