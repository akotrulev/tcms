# §91 — the way back to the picker, and the rail that collapses

**One phase, frontend only.** No migration (`V27` still head, `V28` still free), no contract change,
no `gen:api`, no new permission key, no backend file touched. Gate: **build clean, lint 0,
770/770 across 52 files** (765 → 770).

## What prompted it

Two observations, in order: *"we don't have a landing page where user can select from all available
projects and permitted users can create a new project"*, then — once that turned out to exist —
***"rather we don't have a way to navigate to it"***, and then *"also no way to collapse the
navigation menu"*.

**The first premise was wrong and the correction is the actual finding.** The picker has existed
since the beginning: `/` routes to `ProjectsPage`, which renders a responsive card grid of
`GET /projects` with key, name, description, case count and role, and a **New project** button
behind `<PermissionGate projectId="company" permission="project:create">` — the app's only
COMPANY-scope affordance, disabled-with-reason rather than hidden. `ProjectCreateDialog`,
`POST /projects`, and the contract's whole argument about client-supplied keys were all shipped.

What did not exist was **any way to reach it**. That is the part worth recording, because it is a
defect nothing in the project could have caught:

- It is where **sign-in lands** (`LoginPage` → `<Navigate to="/">`).
- It is the **fallback** when a URL names a project the caller cannot see — the page's own comment
  says *"better to land here with a way forward than on a bare 403"*.
- And once you were inside a project, **nothing in the app linked to it.** The rail had a project
  *switcher*, which moves you between projects but never out of them; the wordmark was plain text.
  `/` was reachable only by editing the address bar.

**A screen every session starts on, that no screen points at.** Every gate was green on it: it
builds, it lints, it is covered by tests, and each of those tests renders the *page* rather than the
shell around it — so the missing link was in the one component no test had ever mounted. This is
§65's "the gates prove it compiles, not that it works" with a new shape: not a broken screen, a
correct screen with no door.

## What landed

**`ALL_PROJECTS`, in the ungrouped block above the switcher.** It sits there for the reason
`MY_WORK` (§83) already states — everything *below* the switcher is scoped **by** the switcher, and
this screen is the one that lists what the switcher chooses between — and it is placed **last in
that block, directly above the switcher**, because those two are one thought: *"all of them"*, then
*"this one"*. `MY_WORK` above it is personal and belongs to neither.

**The wordmark links there too**, which is the convention every reader already arrives with. It can
be one *now* and could not before: a logo that is the only way home is a guess the user has to make,
while a logo that agrees with a visible, labelled, active-marked nav item is a shortcut.

**A recorded wrong comment.** The first version of `ALL_PROJECTS` claimed `end: true` was
load-bearing — `/` is a prefix of every route in the app, so a prefix match would light the item up
on all of them. That is the obvious assumption and it is **false**: `NavLink` already refuses the
case (it requires the next character of the location to be `/`, and for `to="/"` there is none), so
removing `end` changes nothing and the test stays green. Found by deliberately breaking it to check
the test was not vacuous — the §89 habit — and both the comment and the test's own doc now say so.
`end` is kept as an explicit statement of intent; **relying on a router special case to make a nav
item behave is a worse reason than saying what you meant**, and a comment claiming a guard that is
not there is worse than no comment.

## The collapse — icon rail, not a hidden rail

Chosen deliberately over the cheaper option (drop the rail, promote the existing hamburger to all
widths). `ui-rules` §6: *don't hide navigation when there's room to show it* — the drawer is a
small-screen affordance, not a desktop default. The icon rail keeps every destination on screen.

**232px ↔ 64px, and the rail stays in the flex flow at both** — the content area reflows on its own,
so there is still no width-dependent JS deciding layout, which is the same rule the existing `md`
visibility swap follows.

**The drawer gets neither prop and is always expanded.** That is why `collapsed` and
`onToggleCollapse` are props on `RailContent` rather than a hook it reads: a temporary drawer is
already a deliberate reveal that costs a tap and covers the page, so collapsing it to icons would
save nothing while taking the labels away from the users with the least room to guess at icons. It
has no toggle either, rather than a disabled one.

Four things the collapsed state had to keep, each of which is invisible on screen when wrong:

1. **Names.** Collapsing removes the label text from the DOM, so `RailLink` must carry `aria-label`.
   An icon-only link without one is announced as its **href**. The `Tooltip` is not a substitute —
   it is a hover affordance and never reaches a reader that is not hovering. The tooltip exists only
   in the collapsed rail for the mirror reason: on the expanded one it would put a tooltip on text
   already saying the same word.
2. **Grouping.** The Repository / Execution / Project headings become `<Divider>`s rather than
   vanishing. Those headings are the only thing telling a reader that Reports is about execution and
   Classification is about the project; twelve icons in one undifferentiated column lose it.
3. **Which project.** The switcher becomes an `IconButton` showing the project's **key** — not a
   generic folder icon — opening a `Menu` of projects. *Which project am I in* is the question a
   64px rail is likeliest to drop, and the key is what the rest of the product identifies a project
   by, so it needs no legend. It renders with no project too, matching the expanded `Select` that
   renders empty: a member holding no grants should see an empty control, not conclude the app has
   no projects in it.
4. **The preference.** Written through `safeStorage` (`tcms.nav.railCollapsed`) and **read in the
   initial `useState`, not an effect** — an effect renders the expanded rail first and snaps it shut
   on the next frame, a visible jump on every page load for exactly the people who chose the
   collapsed rail. `=== 'true'`, so a corrupt entry reads as the default rather than as collapsed.

The toggle sits **below** the scrolling nav list rather than inside it: it is a control on the rail,
not a destination in it, and a toggle that scrolls away with the links is one the user cannot find
to undo. `aria-expanded` names the state, `aria-controls="nav-rail"` names what it is about, and the
accessible name states the *action* — "Collapse navigation" / "Expand navigation".

## `AppShell.test.tsx` — the shell's first test file

The most-shared component in the app had never had one, which is why a missing link on every screen
was invisible to 765 passing tests. Five tests. Two traps found while writing them, both worth
knowing before the next person mounts the shell:

- **It needs a `userId`** on `TestProviders` — `TopBar` calls `useAuth`, which throws without a
  provider (unlike `useCurrentUserId`, which is deliberately provider-free).
- **Wait on the project group (`Nav.Link.Testcases`), never on `Nav.ProjectSwitcher`.** The switcher
  and the two ungrouped items render *before* `/projects` answers, so a wait on one of those returns
  while `groups` is still `[]` — and every project-scoped assertion then fails on a rail that was
  merely early rather than wrong. Two of the five failed this way on their first run.

## Built and unverified

Browser rows **34–39** are appended to `docs/verification/frontend-browser-checks.md` and **were not
run** (§65 standing policy, including the cheap ones). Row 38 is the one that justifies the slice's
size: the rail is on every screen, so a second width is a second appearance for **all** of them, and
nothing has looked at any of it. Row 39 is live-only — a member with no project grants at all, which
mock mode cannot show, since its fixture user holds everything.

**This slice is built and unverified, and should be described that way.**
