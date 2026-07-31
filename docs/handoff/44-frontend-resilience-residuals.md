# §44. Frontend resilience residuals — the tab that lies, and the refetch that was switched off (2026-08-01)

Run through the **`fe-react`** skill in Mode B (extend an existing codebase), re-entering **Phase 3**
(quality layers) with `fe-react-data-fetching` and `fe-react-error-handling` (+ both `references/`).
Executed as **lane C** of a third three-lane parallel run, in its own worktree. Frontend only — no
backend, no Docker, no contract, and `npm run gen:api` not run.

**Versions:** React 19.2 · Vite 8.1.5 · MUI v7 · **no Next.js, no RSC**, so the skills' `'use client'`
and `error.tsx` lanes are not applicable and nothing was deferred for that reason.

Three items, all deferred by §40 into files other lanes held, plus §5's two oldest Phase 3 residuals.

## 1. `refetchOnWindowFocus: false` — the reason is that there wasn't one

§41 recorded this as *"the one decision in that file with no written reason"*. It is now **removed**,
leaving TanStack's default of `true`, with the decision written at the line.

The argument is not "the default is the default". It is that this is a **multi-user TCMS on one
instance**: another person's recorded result, another person's grant, a colleague's edit and a run
someone else closed all change *under* a tab that is doing nothing at all. Turning focus
revalidation off trades a real correctness property for a cosmetic one — quieter devtools — which is
exactly the trade `fe-react-data-fetching` names and refuses. **The failure mode it prevents is
silent and expensive**: a lead reads a pass rate off a tab opened an hour ago and nothing on screen
looks wrong.

**`staleTime: 30_000` is what makes the default affordable, and the two are now documented as one
decision.** A focus refetch fires only for queries older than 30s, so alt-tabbing to Slack costs
nothing while a tab left through a meeting revalidates on return. Raising `staleTime` weakens focus
revalidation; deleting it turns every focus into a full refetch. They are a pair and the file now
says so.

The interaction §40 was worried about — a focus-triggered storm that 503s producing N notifications —
was **driven rather than assumed**, and holds: 12 requests across 3 endpoints produced **one**
notification card reading *"Occurred 3 times"*. The existing dedupe (`correlationId ?? code|region`)
and roll-up-by-cause were already correct; focus refetching is simply a new way to reach them.

## 2. `placeholderData` on `useCases` — the finding was already half-wrong

§40's finding 10 said `useCases` had no `placeholderData`. It has had
`placeholderData: (previous) => previous` since the query modules were split (`b08d559`), which is
semantically identical to `keepPreviousData`. **A review finding written from reading rather than
running was stale by the time it was filed**, and this is the second time this session that reading
and running disagreed.

What was actually wrong is narrower and worth the change anyway: TanStack memoizes the placeholder by
comparing the **identity** of the option against the previous render's (`queryObserver.ts` ~line 497),
and an inline arrow is a new identity every render, so the memoized branch is never taken. Identical
result today; it stops being identical the moment a `select` is added, because `select` re-runs on
every recompute. Now the exported `keepPreviousData`, with that written down.

**Driven in the browser**, sampling the DOM every 90 ms while typing `refund` into the case search at
130 ms/keystroke: **28 frames, 0 skeleton frames, 0 zero-row frames, 3 refreshing-hairline frames**,
25 rows → 14 rows with nothing empty in between. The grid narrows instead of blinking.

**The same argument applies elsewhere and is deliberately NOT widened here** — those files belong to
other lanes this run. Listed for whoever owns them next, in descending order of how visible the flash
is:

| Hook | File | Why it flashes |
|---|---|---|
| `useConditions`, `useSharedSteps` | `api/queries/library.ts` | keyed on `q`, **search-as-you-type**, so this is the worst case of the three and the strongest candidate |
| `useCaseHistory` | `api/queries/cases.ts` (mine, left alone) | `scope` toggle CURRENT/ALL — a two-value toggle over one conceptual list |
| `useArtifacts` | `api/queries/documentation.ts` | `scope`/`planId` filter |
| `usePlanStats` | `api/queries/plans.ts` | `aggregation` toggle — **lane A's file** |
| `useWorkItems` | `api/queries/workItems.ts` | `mine` toggle |

`useCaseHistory` is in a file I own and was still left alone: it is a panel toggle rather than a typed
filter, the flash is one skeleton on a deliberate click, and widening a resilience slice into every
query in the module on that evidence is how a scoped change becomes an unreviewable one.

**`DataRegion`'s stale matrix was checked against its first real filtering consumer** and needs no
change. The seam has two halves pulling opposite ways and both are now pinned by tests: mid-flight
the previous key's rows survive with the hairline, and a *failed* first load of a new key renders the
**failed body, not the stale marker** — because TanStack only substitutes placeholder data while the
query is `pending`. That line is right: rows for the filter you replaced, labelled "showing older
data", would answer a question nobody asked.

## 3. Multi-tab session — and the defect that only a second renderer process can show

`auth/sessionChannel.ts` is a `BroadcastChannel('tcms.session')` carrying two messages,
`signed-in { email }` and `signed-out`. `AuthProvider` publishes on login/logout and reacts;
`SessionRecoveryProvider` closes its dialog; `SessionReplayBridge` decides what happens to the query
cache. **No change to `Providers.tsx`** — each provider owns its own reaction, so the load-bearing
nesting order was not touched.

Three decisions, each with the failure it prevents:

- **`BroadcastChannel`, not the `storage` event.** The storage event is free but tied to the one
  thing this must not depend on: `safeStorage` degrades to an in-memory `Map` when `localStorage`
  throws, and in that state no storage event is ever emitted — the multi-tab behaviour would silently
  vanish exactly where sessions are already fragile. It also cannot express intent: "signed out" and
  "token rotated" arrive identically.
- **One channel object per tab.** A `BroadcastChannel` does not deliver to the object that posted,
  but it *does* deliver to another object with the same name **in the same tab**. Two providers each
  opening their own channel would hear their own tab's sign-out and recurse. The module keeps one
  object and fans out itself.
- **A remote sign-out clears hard; a mid-session 401 does not.** They look alike and are opposite:
  an expiry happens *to* the user, so the ladder keeps the route mounted over unsaved work; a sign-out
  is what they asked for, and the token is already gone from shared storage, so every control the
  sibling is still rendering is a control that produces a 401.

**A sign-in settles siblings too, not just a sign-out** — which was the half §5 would have left
undone. The dialog closes rather than asking for a password a second time for a session that is
already valid, and a tab sitting on `/login` lands back in the app.

### The browser found the one thing nothing else could

The first implementation adopted the new session by **reading `localStorage` on receipt**. Every unit
test passed. In Chrome it failed **5 runs out of 5**: tab B received `signed-in`, read storage,
got `null`, and sat on the login form holding a perfectly valid token.

**`localStorage` is shared between tabs but is not synchronously consistent between the renderer
*processes* they run in.** A `BroadcastChannel` message routes through the browser process and
arrives before the sibling's view of the write has caught up. An independent raw-channel spy in tab B
saw `token: true` at receipt while the app's own listener, microseconds later, read `null` — the two
observations disagree because they are different reads of a view that is still converging.

**One jsdom process has one storage, so no unit test in this suite could ever have seen it.** This is
§6 lesson 5 again in its sharpest form yet: not "the browser finds bugs the gates miss", but *the
browser is the only place a whole class of bug exists.*

The fix is not a retry or a delay. The sibling **re-confirms against `/me`**, which is the mechanism
`AuthContext`'s own header comment already argues is correct ("restoring a session is a server
question") and which is immune by construction: the request is at least a macrotask away, it carries
whatever token storage holds *at request time*, and the **server** decides who this is. A genuinely
absent token 401s and clears — the same fail-closed outcome as before. It reuses the existing restore
effect via a nonce rather than adding a second path to the same question.

### The sharpest correctness point: the cache is the leak, not the name

`SessionReplayBridge` clears the query cache when the **identity** changes (a sign-out, or a sign-in
as a different email) and merely refetches when it does not. Without that, tab B renders the previous
user's project list — and, worse, their cached `/me/permissions` answers, which `useCan` gates eleven
write affordances on. That is a data-exposure bug rather than a cosmetic one, and it is invisible in
mock mode because MSW signs everyone in as the same fixture user. It is covered by unit tests, not by
the browser pass, and that limitation is stated rather than papered over.

## What the browser pass showed that the gates did not

| Checked | Result |
|---|---|
| Focus inside `staleTime` (30 s) | **0** API requests — no storm |
| Focus after `staleTime` | **3** requests: `/suites`, `/cases`, `/conditions` |
| Focus storm under a 503 fault | 12 requests / 3 endpoints → **1** notification, *"Occurred 3 times"*, correlation id shown |
| …and the region under it | `Cases.List.Stale` present, `Cases.List.Error` absent, **25 rows before → 25 rows after**, search box still editable |
| Typing a filter (`keepPreviousData`) | 28 sampled frames, **0** skeleton, **0** zero-row, 3 hairline |
| Sign out in A | B moved `/…/cases` → `/login`, signed-in shell gone, login form present |
| Sign in on A | B left `/login` **5/5**; before the fix, **0/5** |
| Mid-session 401 (regression check) | dialog opens, still open 4 s later, route stays mounted, typed filter `refund` intact, re-auth in place restores 14 rows |
| Console / `window.__tcmsErrors` | empty in both tabs across every pass |

Driven with `playwright-core` against the installed Chrome, two pages in one browser context. Note
for the next person: **TanStack v5's `focusManager` listens for `visibilitychange` on `window`**, not
for `focus` — a synthetic `focus` event proves nothing, and the first pass reported "0 refetches" for
that reason before the trigger was corrected.

## Mutation evidence

Ten mutations, each expected to turn a specific new test red. §6 lesson 13's standard.

| # | Mutation | Result |
|---|---|---|
| 1 | iterate the live listener `Set` instead of a copy | ✅ 1 failed |
| 2 | drop the unknown-message guard | ✅ 1 failed |
| 3 | ignore a remote sign-out | ✅ 1 failed |
| 4 | ignore a remote sign-in | ✅ 2 failed |
| 5 | bridge never clears the cache | ✅ 2 failed |
| 6 | bridge always clears the cache | ✅ 1 failed |
| 7 | dialog ignores the channel | ✅ 2 failed |
| 8 | skeleton over placeholder rows | ✅ 2 failed |
| 9 | stale marker for a failure with no data | ✅ 4 failed |
| 10 | adopt from storage instead of `/me` (the real defect) | ✅ 1 failed |

**Mutation 1 survived on the first attempt and the test was rewritten, not kept.** It asserted that a
listener unsubscribing *itself* still worked — which `Set` iteration tolerates anyway, so no mutation
could kill it. It now pins the real hazard: a listener that removes a **later** subscriber
mid-delivery, which is the ordinary case here (`AuthProvider` clears the session, `RequireAuth`
unmounts the subtree holding `SessionReplayBridge`'s subscription, and the bridge has not been told
yet). With live iteration the bridge is skipped and the ended session's cache is never cleared — a
bug with no symptom until the *next* user signs in. **A test no mutation can kill is not evidence,
and deleting or fixing it is the only honest response.**

Both new test files also had a **flake** removed before it could be inherited: delivery was awaited
with a fixed `setTimeout(10)`, which passed idle and failed once under load. The behaviour tests now
await delivery by *observing* it through a sentinel subscriber.

## Gate evidence

```
npm run build     → tsc -b + vite build clean
                    entry chunk 249.72 kB → 250.56 kB  (78.88 → 79.13 kB gzip)  [+0.84 kB, +0.25 gzip]
                    CaseListPage 535.30 kB unchanged; no other chunk moved
npm run lint      → oxlint, exit 0, no output — 0 warnings
npm run test:run  → Test Files 14 passed (14) · Tests 248 passed (248)   [230 + 18]
find "* 2.*"      → nothing outside node_modules
```

The +0.84 kB is `sessionChannel.ts` plus the two listeners, and it is in the entry chunk on purpose:
the session providers mount before any route. **Measured, not assumed** (§8).

`npm run build` earned its keep again: `npm run test:run` was **green at 245 tests while the build
failed** on a test fixture missing two required fields of `CurrentUser`. That is the working
agreement's rule reproducing itself inside the slice that quotes it.

## Deliberately not done

- **`placeholderData` on the other five filtered lists** — table above. Four of the five files belong
  to other lanes this run; the fifth (`useCaseHistory`) is a click-driven toggle, not a typed filter.
- **`useUnsavedWork` on the execution screen** — lane A's this run, and lane A has been given it.
- **A cross-**identity** browser check.** Mock mode signs every email in as the same fixture user, so
  "Bob must not see Alice's cached permissions" is provable only by unit test until live mode has two
  accounts. Named here so it is not mistaken for verified.
- **`refetchOnReconnect`** left at its default, for the same reason as focus and stated in the file —
  but not separately driven, because taking the network down under MSW proves nothing.
- **A dedicated slow-connection soak.** Focus revalidation now fires more requests against the real
  API than before; the notification roll-up was driven under a 503, not under latency.

## Two defects found in files this lane correctly did not edit

1. **`data-testid="SessionExpired.Password"` lands on MUI's `TextField` root `<div>`, not on the
   `<input>`.** A Playwright `fill()` on that locator fails with *"Element is not an `<input>`…"*; it
   only works as `[data-testid="SessionExpired.Password"] input`. The file is mine, but the locator
   contract is not — a peer's page objects may already key on it, and §37's near-miss was one lane
   changing what another was asserting on. **Reported rather than changed**, for `test-ids` to settle:
   either the id moves to `slotProps.htmlInput` or the convention documents the descendant hop. Every
   `TextField` in the app is likely to have the same shape, so this is a class, not an instance.
2. Nothing new in `useCan.ts` / `PermissionGate.tsx`; neither was read into or modified. §40's
   `BoardPage` finding is unchanged and still open.

## One lesson for §6

16. **A bug can exist only in a second OS process, and no amount of jsdom will find it.** Adopting a
    cross-tab session by reading `localStorage` passed every unit test and failed 5/5 in Chrome,
    because storage is shared between tabs but not synchronously consistent between the renderer
    processes they run in — the broadcast overtakes the write. The general form: **when a mechanism's
    correctness depends on two agents' views converging, a single-process test asserts the premise
    away.** Same family as lesson 5, one level deeper: it is not that the browser finds more bugs,
    it is that the browser is the only place this kind of bug is *reachable*. The fix was to stop
    asking the browser and ask the server, which the file already argued for in a different context.
