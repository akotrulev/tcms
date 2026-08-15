# M6 Plan D — `/c/{slug}` and the Company Switcher

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Master plan:** `docs/plans/2026-08-15-m6-multi-company-activation.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans`. **Checkpoint after every
> task.** Load `/fe-react`, plus `fe-react-routing` for Tasks 1–3 and `fe-react-ui-rules` for Task 4.
> `ep-code-comments` governs every comment.

**Depends on:** Plan A (`CurrentUser.company`, `GET /me/companies`, `POST /auth/session/company`) and
Plan B (branding, so the switcher can show a company the way that company looks). Nothing here needs
Plan C.

**Goal:** Put the company in the URL and give the user a control to change it — the last piece of
"a second company can actually be used", and the piece §6s says makes a wrong-company link fail
loudly instead of silently resolving to whatever the session had active.

**Architecture:** Purely a frontend plan, on top of endpoints Plan A already shipped. The slug in the
URL is **validated against the session, never trusted as its source** — a mismatch either switches
(if the caller is a member) or renders not-found. No new backend endpoint, and deliberately so: the
moment the SPA can select a company by URL segment, the temptation is a server that reads it, and
that is the one thing ERD §0.1 point 2 forbids.

**Tech Stack:** React 19 · React Router 7 · TanStack Query · MUI v7 · Vitest + RTL

**Fixture UUID prefix for this plan:** `d3000000-…`.

---

## Orientation

1. **`router.tsx` already anticipates this.** Its header (`:25-30`) records that flattening
   `/projects/:projectId/…` was deferred *"so the whole URL shape — including the `/c/{companySlug}`
   segment that arrives with backend M6 — gets designed once rather than twice."* **This is that
   once.** Read the whole header before editing; it also explains why the project segment exists and
   why it stays.
2. **The current tree**, verified 2026-08-15: public `/login` (`:143`) and `/accept-invitation`
   (`:149`); behind `<RequireAuth>` inside `<AppShell>` — `/` (the project picker, `:154`),
   `/projects` → `/` (`:161`), `/my-work` (`:178`), `/teams/:teamId/board` (`:202`), the
   `/projects/:projectId/…` tree (`:211`-`:495`), `/admin/teams` (`:495`), `/admin/tokens` (`:510`),
   `*` → NotFound (`:518`).
3. **Heavy screens are code-split** and must stay so. A prefix segment must not accidentally move a
   `lazy()` boundary.
4. **Filter state lives in the URL** (`useSearchParams`) — so a redirect that rewrites a path must
   preserve the query string, or every saved link loses its filters.

---

## The decision this plan has to make first

`/c/{slug}` can be added three ways and they are not equivalent:

| Shape | What it costs |
|---|---|
| **A. Prefix everything** — `/c/:slug/projects/:projectId/cases/:caseId` | Every internal link, every `navigate()`, every breadcrumb and every test that asserts a path. Large, mechanical, and it is what §6s literally specifies (`/c/{companySlug}/projects/{projectKey}/cases/{ref}`). |
| **B. Prefix nothing; add `/c/:slug` as a *switch-and-redirect* entry point only** | Cheap. A shared link works. But the address bar never shows the company, so the "wrong-company link fails loudly" property is only true for links somebody deliberately built. |
| **C. Prefix everything, with a redirect that adds the segment when it is missing** | B's cheapness for existing links plus A's property, at the cost of one redirect layer. |

**Recommended: C**, and Task 1 builds the redirect first so that every subsequent task can move
routes under the prefix without breaking a single existing link. If the user prefers B, Tasks 2 and 3
collapse into one and Task 4 is unchanged — **say so before Task 2 starts**, because after Task 2 the
cost of changing the answer is the whole tree again.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `frontend/src/app/CompanyRoute.tsx` | Reads `:companySlug`, validates it against the session, switches or 404s. |
| `frontend/src/app/companyPath.ts` | `companyPath(slug, rest)` — the one place a company-prefixed path is composed. |
| `frontend/src/features/company/CompanySwitcher.tsx` | The control. |
| `frontend/src/features/company/SuspendedCompanyPage.tsx` | What a member of a suspended company sees. |
| `.test.tsx` beside each of the above. |

**Modify**

`frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`, and every site that composes a path
by hand (Task 3 finds them).

---

## Task 1: `companyPath` and the redirect that makes the segment optional

Build the redirect **before** moving any route, so that at every commit in this plan both the old and
the new URL work.

**Files:**
- Create: `frontend/src/app/companyPath.ts` + `companyPath.test.ts`
- Create: `frontend/src/app/CompanyRoute.tsx` + `CompanyRoute.test.tsx`

- [ ] **Step 1: Write the failing test for `companyPath`**

```ts
import { describe, expect, it } from 'vitest';
import { companyPath } from './companyPath';

describe('companyPath', () => {
  it('prefixes a path with the company segment', () => {
    expect(companyPath('acme', '/projects/p1/cases')).toBe('/c/acme/projects/p1/cases');
  });

  it('preserves the query string, because filter state lives in the URL', () => {
    expect(companyPath('acme', '/projects/p1/cases?status=FAILED'))
      .toBe('/c/acme/projects/p1/cases?status=FAILED');
  });

  it('is idempotent, so composing an already-prefixed path is not a bug', () => {
    expect(companyPath('acme', '/c/acme/projects/p1')).toBe('/c/acme/projects/p1');
  });

  it('returns the path unchanged when there is no company', () => {
    // An operator who belongs to no company. Their session has no company to name and the
    // /instance tree is not company-scoped.
    expect(companyPath(null, '/instance/companies')).toBe('/instance/companies');
  });
});
```

- [ ] **Step 2: Run it and watch it fail.**

```bash
cd frontend && npm run test:run -- companyPath
```

- [ ] **Step 3: Implement `companyPath`.**

- [ ] **Step 4: Write the failing test for `CompanyRoute`**

Four behaviours, and the third and fourth are the ones §6s cares about:

```tsx
it('renders its children when the slug matches the session company', async () => { … });

it('switches the session when the slug names another company the caller is in', async () => {
  // A shared link into a company the caller is a member of but not currently in. §6s: the
  // request "either switches (if the user is a member) or 404s". Switching is the whole
  // reason a shareable company-qualified link is worth having.
  renderAt('/c/globex/projects/p1', { session: { company: 'acme' }, memberOf: ['acme', 'globex'] });
  await waitFor(() => expect(switchCompany).toHaveBeenCalledWith(globexId));
});

it('renders not-found for a company the caller is not a member of', async () => {
  // 404, never 403 — a 403 confirms the company exists (§6s). The SPA must not render a
  // "you do not have access to Globex" message either, for the same reason: it is the same
  // oracle, phrased politely.
  renderAt('/c/globex/projects/p1', { session: { company: 'acme' }, memberOf: ['acme'] });
  expect(await screen.findByText(/not found/i)).toBeVisible();
  expect(screen.queryByText(/globex/i)).not.toBeInTheDocument();
});

it('renders not-found for a slug that does not exist, identically', async () => { … });
```

> Use `createTestQueryClient` from `src/test/` — **never build your own `QueryClient`**;
> `renderFailures.test.tsx` sweeps every test source to keep that true, and only that helper's client
> has the caches the render- and fetch-failure checks read.

- [ ] **Step 5: Implement `CompanyRoute`.** It reads `useParams().companySlug`, compares against
  `useAuth().company?.slug`, and on a mismatch looks the slug up in `useMyCompanies()` — switching
  through `useSwitchCompany()` if found, rendering `<NotFoundPage />` if not. **The two not-found
  branches must render the identical component with the identical copy**; assert that in the test
  rather than trusting it.

- [ ] **Step 6: Gates and commit.**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
git add frontend/src/app/companyPath.ts frontend/src/app/companyPath.test.ts \
        frontend/src/app/CompanyRoute.tsx frontend/src/app/CompanyRoute.test.tsx
git commit -m "feat(web): the company segment, and the route that validates it

The slug is validated against the session and never trusted as its source. A
company the caller is not in and a company that does not exist render the same
not-found page with the same copy — a polite 'no access to Globex' is the same
oracle a 403 would be.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Move the tree under `/c/:companySlug`, with the old paths still working

**Files:** `frontend/src/app/router.tsx`

- [ ] **Step 1: Write the failing test**

A router-level test asserting both forms resolve, and that the un-prefixed form redirects rather than
rendering twice:

```tsx
it('serves /c/acme/projects/p1/cases', async () => { … });

it('redirects /projects/p1/cases to the company-qualified form, keeping the query', async () => {
  renderAt('/projects/p1/cases?status=FAILED', { session: { company: 'acme' } });
  await waitFor(() =>
    expect(currentPath()).toBe('/c/acme/projects/p1/cases?status=FAILED'));
});

it('leaves an operator with no company on the un-prefixed path', async () => {
  // There is no slug to redirect to, and /instance is not company-scoped.
  renderAt('/instance/companies', { session: { company: null, isSystemAdmin: true } });
  expect(currentPath()).toBe('/instance/companies');
});
```

- [ ] **Step 2: Run and watch fail.**

- [ ] **Step 3: Restructure the tree**

Wrap the company-scoped routes — `/`, `/projects/…`, `/my-work`, `/teams/:teamId/board`, `/admin/…` —
in a `<Route path="/c/:companySlug" element={<CompanyRoute />}>` layout route, and add a sibling
catch-all that redirects the un-prefixed forms through `companyPath`.

**Three things not to break:**

- **`lazy()` boundaries.** Every `const X = lazy(...)` at the top of the file stays exactly where it
  is. A prefix segment changes the `path`, not the element.
- **`/login` and `/accept-invitation` stay public and stay un-prefixed.** An invitation link is
  redeemed by somebody with no session, so there is no company to name.
- **The `*` → `NotFoundPage` route stays last.** It is what makes an unknown path inside a valid
  company still 404 rather than falling through to the redirect.

- [ ] **Step 4: Run the router tests, then the whole suite**

```bash
cd frontend && npm run test:run
```

**Expect broad fallout** — every test that renders at a hard-coded path. That is the cost of shape A/C
and it was priced in. Fix them by routing through the test helper rather than by hand-editing 200
string literals; if the helper does not exist, write it in `src/test/` first and say so in the commit.

- [ ] **Step 5: Gates and commit.**

---

## Task 3: Every path is composed in one place

**Files:** wherever `navigate('/projects/...)` or `<Link to="/projects/...">` appears.

- [ ] **Step 1: Find them**

```bash
cd frontend && grep -rn "navigate('/\|to=\"/\|to={\`/" src --include=*.tsx --include=*.ts | grep -v ".test." | wc -l
```

- [ ] **Step 2: Write the failing lint-shaped rule**

This repo's idiom for "there is one implementation" is a test that sweeps the sources — `citedFiles.test.ts`
and `renderFailures.test.tsx` both do it. Write the same shape:

```ts
it('no component composes a company-scoped path by hand', () => {
  // A hand-composed '/projects/…' is a link that drops the company segment, which after Task 2
  // means a redirect on every click — and, for a user in two companies, a redirect to whichever
  // company the session happens to hold rather than the one the link meant.
  const offenders = sourcesMatching(/(navigate\(|to=)["'`]\/(projects|my-work|teams|admin)\//);
  expect(offenders).toEqual([]);
});
```

- [ ] **Step 3: Route them all through `companyPath`.** Mechanical.

- [ ] **Step 4: Gates and commit.**

---

## Task 4: The switcher

**Files:**
- Create: `frontend/src/features/company/CompanySwitcher.tsx` + test
- Modify: `frontend/src/app/AppShell.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
it('is not rendered at all for a user in one company', () => {
  // A control that does nothing is worse than no control: it implies there is somewhere else
  // to go. `disable-don't-hide` is about actions the user might expect to be able to take —
  // this is not one of them.
  renderShell({ companies: [acme] });
  expect(screen.queryByRole('button', { name: /acme/i })).not.toBeInTheDocument();
});

it('lists the caller’s other companies and switches on selection', async () => { … });

it('shows the active company’s display name, not its slug', () => {
  // The slug is a URL identifier. The display name is what the client calls itself, and §6u
  // makes it freely editable precisely because clients rebrand.
});

it('does not list a company the caller is only INVITED to', () => {
  // Guaranteed by the endpoint (Plan A), asserted here because the switcher is where a
  // regression would be visible and harmless-looking.
});

it('clears the query cache on switch rather than invalidating it', async () => {
  // An invalidation refetches with the OLD token still in flight, and a partially-refetched
  // cache mixing two clients' rows is the exact failure the company boundary exists to make
  // impossible.
});
```

- [ ] **Step 2: Run, watch fail, implement.** Use the MUI `Menu` idiom the app bar already uses —
  **open `AppShell.tsx` and match it**; a second menu implementation in the same bar is how one
  consistent app becomes two.

- [ ] **Step 3: Place it in the app bar**, beside the existing account control, and make it show
  the company's logo when Plan B's branding provides one.

- [ ] **Step 4: Gates and commit.**

---

## Task 5: What a member of a suspended company sees

Plan B's filter answers 403 with a `detail` that says what to do. The SPA must render that as a
page, not as a toast on an empty grid.

**Files:**
- Create: `frontend/src/features/company/SuspendedCompanyPage.tsx` + test
- Modify: `frontend/src/app/queryClient.ts` or `src/lib/describeError.ts` — **whichever this repo
  says owns turning an error into copy.** `describeError.ts` is *"the one place an error becomes
  user-facing copy"*; do not add a second.

- [ ] **Step 1: Write the failing test** — a 403 whose body is the suspension problem renders the
  page; an ordinary 403 does not.

- [ ] **Step 2: Decide how the two are told apart, and write it down.** Matching on the `detail`
  string is fragile. `Problem.type` is the field designed for this — check what Plan B's filter sets
  and, if it is `about:blank`, **go back and give it a real type URI**; that is a Plan B correction,
  not a Plan D workaround.

- [ ] **Steps 3–5:** implement, gates, commit.

---

## Task 6: Write the slice down

Same shape as Plan A's Task 7 — handoff section, index row, both caches, both queues.

Worth recording: which of shapes A/B/C was taken and why; the count of hand-composed paths Task 3
found; and whether Task 5 Step 2 sent a correction back into Plan B.

Verification-queue rows (**write, run nothing** — §65). The browser queue is **one live pass of
thirteen ordered lanes** since §109; a new row goes in the lane whose *state* it needs, names the
fixture that lane's charter authors, and keeps a stable id. **Every row in this plan needs a user in
two companies, which no lane currently creates** — say that explicitly in the row rather than
skipping silently, and name the two options: extend an existing lane's charter, or add a lane.

---

## Self-review

**Spec coverage against the master plan's Plan D row:** the `/c/{slug}` segment → Tasks 1–3 · the
slug validated against the session, never trusted → Task 1 Step 5 · switch-or-404 with the two 404s
identical → Task 1 Step 4 · the switcher → Task 4 · the suspended-company surface → Task 5.

**Placeholder scan.** Task 5 Step 2 asks a question rather than answering it, deliberately: the answer
depends on what Plan B's filter actually sets in `Problem.type`, and the step says that if the answer
is `about:blank` the fix belongs in Plan B. Everything else names its files, its assertions and its
commands.

**Type consistency.** `companyPath(slug: string | null, path: string): string` is declared in Task 1
and called with that signature in Tasks 2 and 3. `CompanyRoute` is a layout route element in Task 1
and used as one in Task 2. `useMyCompanies()` and `useSwitchCompany()` are **Plan A's** and are
reused, not redeclared.

**The decision that gates everything.** Shape A vs B vs C, above. Task 2 is where it becomes
expensive to change, so the plan asks for it before Task 2 rather than after — and recommends C.
