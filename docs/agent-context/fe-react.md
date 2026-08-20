<!-- orientation-cache v1 -->

# fe-react — orientation cache (TCMS)

**Last verified:** 2026-08-21 (**§243** — **review-workflow notifications, and the frontend half is TWO LABEL MAPS.** The notification event vocabulary went from two members to six (`review.requested`, `review.endorsed`, `review.changes_requested`, `review.approved`) and **not one component changed**: the channel dialog's checkbox list is derived from `NOTIFICATION_EVENT_TYPES`, which is `Object.keys(notificationEventTypeLabels)`. **The `satisfies Record<NotificationEventType, string>` on `notificationEventTypeLabels` AND `notificationEventTypeHelp` is what turned a regenerated `schema.d.ts` into seven compile errors** — in `labels.ts`, `NotificationChannelDialog.tsx` and `NotificationSettingsPage.tsx` — instead of four blank labels and four checkboxes with no help text. That is the whole argument for the `satisfies` pattern, observed rather than asserted: run `npm run build` **before** adding the labels and read the failure. Frontend suite unchanged at **1602 / 120** — no test needed extending, because `NotificationSettingsPage.test.tsx` targets checkboxes by test id rather than counting them, which is the reason a vocabulary change was free here and would not have been under a count assertion. Gates: build clean, lint **0 warnings**, **1602 / 120**.)

**Superseded header below — §242's, kept because §243 verified none of it.** (**§242** — **the audit trail: one lazy `AuditLogPage` behind `/projects/:projectId/audit` AND `/admin/audit`.** **A LOCAL `tsc --noEmit` PASSED CLEAN WHILE THREE OF THIS SCREEN'S TESTS FAILED** — the working agreement's warning arriving on schedule, and worth carrying because it was tempting to treat the typecheck as coverage while `shanks` was down. **THE RULE TO CARRY: the rail's disable-don't-hide rule has exactly one exception now, and it is written at the type rather than left for the next reader to discover.** `AppShell.AuditReach` gates the two audit entries on `project:audit` / `audit:view` and **HIDES** them — the opposite of §31 and of the five separate comments in that file arguing that hiding lies about the *product*. Taken at the owner's direction: an audit screen's subject is who did what, so advertising it to somebody who may not read it invites the request the permission exists to refuse. It fails closed while pending. **One component for two routes**, branching on whether `useParams` yields a `projectId`; two components would be two renderings of the same twelve columns with nothing keeping them identical. **`Table`, NOT `DataGrid` — and §241 already measured why**: DataGrid does not render in jsdom, so the twelve component arms are only possible on a `Table`. **Action labels go through a curated map + computed fallback (`auditActionLabel`), NOT `satisfies Record<Union, string>`** — `AuditEvent.action` is a bare `string` in the contract because the verb set grows with every slice, so there is no union to be total against; `notificationEventLabel` is the same shape for the same reason. A refusal renders as a sentence **and makes no request at all**. Filters live in the URL (`useAuditFilters`, `useCaseFilters`'s arrangement). Gates: **1602 / 120**, build and lint clean.)

**Superseded header below — §241's, kept because §242 verified none of it.** (**§241** — **the org chart screen: `/org-chart`, `useOrgChart`, and the tree flattening that became shared.** **THE RULE TO CARRY: a notice that appears only when something is hidden IS the disclosure it was meant to avoid.** The matrix columns are clipped to the reader's own `project:read`, so somebody missing six of eight projects gets a grid **that looks complete** — and the standing line *"Only projects you can open are shown here."* therefore renders **unconditionally**, for a Company Admin who sees everything too. Its test drives exactly that reader, which is the case a conditional implementation would pass. **`/org-chart` sits BESIDE `/admin/teams`, not beneath it**: every affordance there answers to `company:teams` and this screen has no gate at all — no `PermissionGate`, no `enabled`, no `lib/permissions.ts`. **`flattenTeamTree` (`features/admin/teamTree.ts`) is the one tree flattening**, extracted from `TeamsPage` in its own commit and called by `OrgChartTeams`; orphans and cycle members surface at the ROOT. **`keys.orgChart` is invalidated by all nine staffing writes, and a tenth is caught by a rule rather than by the list** — the last arm of `orgChartInvalidation.test.tsx` reads `admin.ts` through `import.meta.glob` and fails any `onSuccess` touching `teams`/`users`/`roleAssignments`/`projects` without it. **`OrgChartMatrix` is a `Table` and NOT `DataGrid`, which is load-bearing: DataGrid does not render in jsdom** (its virtualization measures through a `ResizeObserver` the test shim never fires, so the scroller is 0×0 with no rows) — measured, six arms failed on the DataGrid version, and `CaseListPage` is the repo's only DataGrid screen and has no component test at all. Whole-screen bundle cost **+7,054 B of dist JS (+0.37 %)**. Nine new fixtures including the four org-chart factories. Gates: **1582 / 119**.)

## §242 — THE ONE GATED PAIR IN THE RAIL, AND THE LABELS THAT CANNOT BE TOTAL

**Read this before touching `features/audit/`, `app/AppShell.tsx`'s nav, or `lib/labels.ts`'s
audit maps.**

> **Gated.** `npm run test:run` **1602 / 120 files**, `npm run build` clean, `npm run lint` **0
> warnings**. A local `tsc --noEmit` had passed clean while three of these tests failed — see the
> header.

### `AppShell` now has a permission gate, and it hides

`AuditReach` is **the only permission-gated pair of items in the rail**, and it **hides** rather
than disabling — which contradicts §31 and the five comments in `AppShell.tsx` that argue rail
items are never gated. That was taken deliberately, at the owner's direction, and the argument is
written at the `AuditReach` type so the next reader does not find the contrary comments first and
"repair" it.

The general rule stands everywhere else and its reasoning is good: hiding an entry lies about the
*product* ("this instance has no CI credentials"), which is worse than telling the truth about the
reader's *access*. **The audit trail is where that trade runs the other way** — the screen's whole
subject is who did what, so an entry visible to somebody who may not read it is a standing hint
that the company is watched in a way they are not trusted to see.

Mechanics: `useCan(activeProjectId)` and `useCanInCompany()` in the rail, verdicts passed into
`projectNav(projectId, audit)`, items spread in conditionally. Both **fail closed while pending**,
which is `useCan`'s `can()` and the right default for a hidden item — an item that appeared and
then vanished would be worse than one that arrives a beat late. Both read `/me/permissions`, which
every screen behind the rail already fetches, so the keys are warm.

### One component, two routes

`AuditLogPage` reads `useParams()` and branches on `projectId`: present → project trail,
`project:audit`, `/projects/{id}/audit-events`; absent → company trail, `audit:view`,
`/audit-events`. **Both hooks are called unconditionally** — that is the rule about hooks — and
`enabled` is what makes exactly one of them fetch.

`DocsPage` is the precedent for one component under two paths. Two components here would have been
two renderings of the same twelve columns with nothing keeping them identical, which is the
shared-layer rule at page altitude.

### `Table`, not `DataGrid` — §241 measured this and it is not a preference

**DataGrid does not render in jsdom**: its virtualization measures through a `ResizeObserver` the
test shim never fires, so the scroller is 0×0 with no rows. Six arms of the org chart's suite failed
on the DataGrid version before it was rewritten. `CaseListPage` is the repo's only DataGrid screen
**and has no component test at all**, which is the same fact from the other side.

So `AuditLogPage` is `Table` + `ScrollX` + `TablePagination`, and the twelve arms in
`AuditLogPage.test.tsx` are only possible because of it. **The plan for this slice assumed DataGrid**
and lazy-loaded the route on that basis; the route stays lazy anyway.

### The labels cannot be total, and pretending otherwise would be worse

`lib/labels.ts`'s house idiom is `as const satisfies Record<Union, string>`, so a new enum member is
a compile error. **`AuditEvent.action` and `.targetType` are bare `string`s on the wire**, because
the verb set grows with every slice that records something and pinning it as an enum would make
adding an act a contract change. There is no union to be total against.

So `auditActionLabel` / `auditTargetTypeLabel` are a **curated map plus a computed fallback** —
`notificationEventLabel`'s shape, one screen over, for the same reason. The fallback matters during
a rollout: a server recording a verb this build has no label for must still render
*"Suite condition attached"*, because a blank cell is a rendering fault nobody can describe while a
humanised verb is a legible bug report. `humanize` handles the dots.

**Do not "fix" the writers to spell `targetType` consistently.** The column is append-only, so
historical rows keep whatever they were written with (`test_case` beside `case`, `ARTIFACT` in caps),
and a client that only understood a new spelling would blank every older row.

### Where things went, against what the plan said

| Plan said | Actually |
|---|---|
| `features/audit/auditLabels.ts` | `lib/labels.ts` — CLAUDE.md's shared-layer rule |
| `packages/client/src/schema.d.ts` | the client copy is **`schema.ts`** |
| `frontend/src/api/queries/` a read hook per endpoint | `queries/` is a **directory**; new file `audit.ts` + one line in `index.ts`, `keys` stays the single factory |
| nav gated with `useCan`, **disable** don't hide | **hide**, at the owner's direction — see above |
| DataGrid | `Table`, for the jsdom reason §241 measured |

### THREE THINGS THE GATES CAUGHT THAT A TYPECHECK CANNOT

- **`contractParameters.test.ts` reads the `qs({…})` argument STATICALLY.** `qs({ ...filters,
  projectId: undefined })` counts as *sending* `projectId`, and the project operation does not
  declare it — the test fails naming the operation. **Both hooks list their keys explicitly**; do
  not "tidy" them back into a spread. The same walker is why a query string must never be
  interpolated into the path.
- **Gating the rail broke `AppShell.test.tsx` — five arms.** Its double answers `[]` to everything
  but `/projects`, and the shell now reads `/me/permissions`, where an array is not the object the
  contract declares; `contractDoubles` catches it and the failure reads as a navigation defect.
  **Any future read added to the shell has that cost**, because that file's double is a fallback
  rather than a set of arms.
- **Two test-side races specific to this screen.** `findByTestId` on the refusal panel resolves
  immediately against the **pending** copy ("Checking your permissions…"), so assert with
  `findByText` on the settled sentence. And **`window.location.search` is empty under
  `TestProviders`** — it renders a `MemoryRouter` — so a URL round trip is proved by *rendering a
  pre-narrowed route* and asserting the control and the request come up narrowed, which is the
  stronger claim anyway: component state could not produce it.

### The refusal makes no request

A caller without the permission gets a sentence naming who to ask, and the `enabled` gate means
**no `audit-events` request is issued at all** — the screen knows the answer and does not ask the
server to refuse it. `meta: { handled: true }` on both hooks, because the screen renders its own
refusal and a global toast would report it twice.

The two empty states are different and must stay different: **"No acts match"** with a Clear-filters
control when filters are active, **"Nothing recorded yet"** otherwise. An empty trail and a refused
trail must never look alike.

---

## §241 — THE ORG CHART, AND THE STANDING LINE THAT IS NOT CONDITIONAL

**Read this before touching `features/org-chart/`, `features/admin/teamTree.ts`, or any staffing
mutation in `api/queries/admin.ts`.**

**`/org-chart` exists, BESIDE `/admin/teams` and not beneath it**, with a rail entry first under
**Company**. §84's reasoning: every affordance on `/admin/teams` answers to `company:teams` and this
screen **has no gate at all** — no `PermissionGate`, no `enabled` term, no `lib/permissions.ts`. A
screen every member may open, filed under a prefix that reads "administration", teaches the opposite
of what is true. Do not "tidy" it under `/admin`.

**`OrgChartPage`'s standing line — *"Only projects you can open are shown here."* — renders
UNCONDITIONALLY**, including for a Company Admin who sees everything. The columns are clipped to the
reader's own reach, so somebody missing six of eight projects gets a grid **that looks complete**.
Making the line conditional is the defect: its presence would then be the existence claim the design
rejected, the "+3 more" count wearing a different hat. Its test drives a reader served every project
the company has, which is exactly the case a conditional implementation would pass.

**`flattenTeamTree` in `features/admin/teamTree.ts` is the ONE tree flattening**, extracted from
`TeamsPage` and called by `OrgChartTeams` as well. Orphans and cycle members are surfaced **at the
root** rather than dropped. A copy is how the two screens come to disagree about which teams exist,
and the disagreement is invisible on exactly the data that is already broken.

**`keys.orgChart` is invalidated by all NINE staffing writes** — `useCreateTeam`, `useUpdateTeam`,
`useDeleteTeam`, `usePutTeamMember`, `useRemoveTeamMember`, `useInviteUser`, `useRemoveUser`,
`useGrantRole`, `useRevokeRole`. The chart is a derived view of `teams`, `users`, `role-assignments`
and `projects` at once. **A tenth staffing write is caught by a rule, not by this list**:
`orgChartInvalidation.test.tsx`'s last arm reads `admin.ts` through `import.meta.glob` and fails any
`onSuccess` that invalidates one of those four keys without invalidating the chart.

**`OrgChartMatrix` is a `Table`, NOT `DataGrid`, and this is load-bearing rather than taste.**
**DataGrid does not render in jsdom** — its virtualization measures through a `ResizeObserver` that
`src/test/setup.ts`'s shim never fires, so the scroller comes back 0×0 with no rows. Measured: the
DataGrid version was written first and six arms failed on an empty grid. `CaseListPage`, this repo's
only DataGrid screen, has **no component test at all** — the same fact from the other end. Every
DataGrid feature this screen would use is absent anyway (one response; no server-side paging, sorting
or filtering). Bundle cost of the whole screen: **+7,054 B of dist JS (+0.37 %)**, page chunk 5,347 B.

**`OrgChartGaps` is pure presentation over the one document — no second query, no second
authorization path.** It computes from `person.projects`, already clipped, so it can never name reach
the reader was not shown. Its third gap is worded *"no role on any project you can open"* for that
reason; *"no assignments"* would be a claim about the company this reader's document cannot support.
It excludes anyone holding a company role, or the panel reports the person with the most access in
the company as a vacancy.

**`assignmentViaLabels` is in `lib/labels.ts` with `satisfies Record<AssignmentVia, string>`**, so a
fourth contract member is a compile error until labelled. Never a switch in the grid.

**New fixtures in `src/test/fixtures.ts`**: `aUserRef`, `aUser`, `aRole`, `aRoleAssignment`,
`aUserInvitation`, `anOrgChart`, `anOrgChartPerson`, `anOrgChartProject`, `anOrgChartAssignment`.
`anOrgChartAssignment` defaults to `DIRECT` with a **null `viaTeam`**, which is the pairing the
contract requires — the team arm must set both.

---

## §240 — TWO BUFFERS BEHIND ONE BANNER, A FILTER THAT MUST NOT BE LOCAL, AND A QUERY STRING THE CONTRACT CHECK CANNOT READ

**Read this before touching `CaseEditorPage`'s draft region, `PlanListPage`, or
`lib/reportDocument.ts`.**

**The case editor now has two buffers and ONE restore flow.** Edit mode reads the server
(`useCaseDraft`, keyed `(case_id, user_id)` since `V64`); create mode reads `localStorage`
(`useNewCaseDraft`). They share `useCaseAutosave`, the banner, the status line and
`toCasePayload`/`fromCasePayload`. **Do not fork the restore flow for create mode** — that is
§128's argument about the mapping, one level up: a field wired into one buffer and forgotten in the
other comes back empty from a restore, with both requests individually valid and nobody to notice.
The page normalises the two into one `offeredDraft`; the only real difference is that a browser
draft is never `stale` (there is no saved case to be stale against) and needs no `draftSettled`
gate, because it is read synchronously on mount.

**`useNewCaseDraft` is keyed per PROJECT and reads once, on mount.** The key is
`tcms.newCaseDraft.<projectId>`: a single global key would offer another client's half-written case
the next time somebody opened New Case, which is the tenancy boundary breached in the one place no
server check can see it, because nothing was ever sent to a server. The read is a `useState`
initializer for `useCaseDraft`'s reason — an offer that re-read itself would propose restoring the
draft the author is currently writing. Storage failures reject rather than throw, so the shared
status line reports them and the next content change retries.

**The banner's copy changed with the schema.** `CaseDraft.updatedBy` is gone; the sentence is
*"You left work on this case without saving it."* What a colleague's save produces is the `stale`
sentence, which was already there and was always the one doing the work.

**A list filter the server owns must not be applied locally.** `?archived=true` **widens** the plan
list rather than narrowing it to the archive, and the SPA sends the parameter — a client that
filtered the working list itself would show an empty archive for ever, since the server never sent
one. The toggle lives in the URL (`useSearchParams`), so the view can be linked; a `useState` toggle
sends a colleague a list the plan they were told about is not in.

**`contractOperations.test.ts` reads your URL EXPRESSION, not your request.** Building
`` `${base(projectId)}/plans${includeArchived ? '?archived=true' : ''}` `` made the call's shape
unrecognisable to that check, which then compared it against nothing at all — the call is silently
*out of* the contract comparison rather than failing it. Use `qs({...})`, which the introspection
understands and which drops an undefined value.

**Two query keys, one prefix.** `keys.plans(p)` and `keys.plansWithArchived(p)` are different
answers to different questions and must not share a key; both sit under the `plans` prefix so every
existing `invalidateQueries(keys.plans(p))` refreshes both without being told about the second.

**`lib/reportDocument.ts` is the fourth renderer of the report header and the one furthest from the
server's.** It gained `dataFreshness` in the zod schema (required, like every other provenance line)
and one entry in `headerBlock`. Its header already says why the assembly is a single function rather
than JSX: the two are comparable only if the client's version is one list in one place. If a server
header field ever appears in an exported file and not on screen, this is the file.

**`useGenerateReport` still sends no `Idempotency-Key`, and that is now a choice.** The server reads
one since `V66`. A press of Generate *is* the intent, so a key minted per press deduplicates nothing
(TanStack does not retry a mutation), and a key minted per mount would make the second press
silently hand back the first report. The endpoint took the header for the callers that cannot see a
duplicate: CI, and the MCP transport.

## §230 — THE INTENT THAT TRAVELS IN THE URL, AND THE PICKER THAT ARRIVES FILLED

`features/cases/CaseDetailPage.tsx` (`DocLinksPanel`), `features/docs/DocsPage.tsx`,
`features/docs/LinkBlockDialog.tsx`. No hook added, no query key, no contract change.

- **The `linkCase` search param is the whole feature.** It is read once, by `DocsPage`, and drilled
  down (`PageView` → `BlockView` → `BlockLinks` → dialog). Do not move that read into a block: it
  would issue one `useCase` per paragraph.
- **Everything that navigates on this screen carries the query string.** `suffix` is threaded into
  `PageTree` for exactly this. A new link or redirect here that forgets it silently ends the
  journey, and nothing about the resulting screen looks wrong.
- **`intendedCase` seeds `pickedCase` AND `query`, and is merged into `options`.** Both halves are
  necessary and each is silent alone — see the last-verified note above.
- The reverse direction (block → case) was already the source chip on `Docs.Block.Links.Item`.

## §228 — THE CASE'S STATUS MENU, AND THE SUITE PANEL THAT DECLINES A COMPARISON

**Read this before touching `features/cases/CaseDetailPage.tsx` or `features/cases/SuitePanel.tsx`.**
Full account: `docs/handoff/228-the-status-a-case-could-not-be-given.md`.

- **`caseMoves` in `CaseDetailPage.tsx` is the third copy of `LifecycleLadder.permissionFor`**
  (`suiteMoves` in `SuiteList.tsx` and `planMoves` are the others) and it is a **disabling hint,
  never the gate**. Getting a row wrong shows a control that 403s — §31's failure, not a security
  one. Local rather than shared with `suiteMoves`: the two ladders are the same transitions under
  **different keys**, so a shared table parameterised by the key pair is one edit from giving a case
  a suite's permissions.
- **The menu offers only the edges the current status has**, so there is no Approve on a draft — a
  *greyed* Approve there would blame a permission for what is a missing review.
- **It deliberately does not predict §6g's review policy.** Author-cannot-approve-own-work and the
  minimum peer LGTM depend on the review record, the project's settings and who is asking. The
  refusal arrives as a 409 through the global handler and is read beside `ReviewPanel`.
- **`useSetCaseStatus` does NOT set `meta: { handled: true }`**, unlike most writes here: it has no
  field to land a message on, and the refusal is about the case on the screen.
  `setQueryData(keys.case(...))` replaces the whole case because the response **is** the case — not
  the `EXCLUDED.*` shape `useQuarantineCase` guards against, whose response merely *describes* a row.
- **`SuitePanel` is A7's whole answer** and it passes **no `currentVersionNo`** to `ReviewPanel`.
  `suite_version` has been written once and never again since §98, so the comparison would print
  "the content has changed since this review" *never*, on a folder whose membership changes daily.
  `ReviewPanel`'s prop is optional for exactly this — do not "complete" the call.
- **It renders no Review heading of its own.** `ReviewPanel` returns null when
  `reviewEnabledSuite` is off (the default), so a wrapper heading would show an empty section on
  most projects in the product.

## §227 — THE REPORTS SCREEN READS A SERVER LIST, AND THE CARD FETCHES NOTHING

**Read this before touching `features/reports/`.** Full account:
`docs/handoff/227-the-reports-that-stopped-disappearing.md`.

- **`useReportJobs(projectId)` is the screen's only poll.** It stops when nothing on the list is
  unfinished — the same terminal predicate `useReportJob` uses, read over the list. `ReportJobCard`
  is now **presentational**: it takes a `job` row. Restoring its own `useReportJob` would mean *n*
  requests every two seconds for rows the page already holds, plus two ages of one job on screen.
  The polling-pair test lives in `ReportsPage.test.tsx` for that reason.
- **`keys.reportJobs` is a PREFIX of `keys.reportJob`** on purpose: invalidating the list after a
  generate brings the card and the list current in one call. A sibling key would let them disagree.
- **`useReportModel` now costs `report:view`.** `modelUrl` points at
  `GET …/reports/jobs/{jobId}/model`; the download still costs `report:export`. The long "this is a
  finding, not a design" paragraph in that hook is struck through in place, and `ReportViewPage`'s
  403 copy names `report:view` now. Do not restore the old wording.
- **`GenerateReportDialog` has two modes.** With a `definition` prop it asks for the **format alone**
  and posts `{definitionId, format}` — **no type, no parameters.** The server merges the request's
  parameters over the saved ones, so pre-filling the form from `definition.parameters` and posting
  it back silently overwrites the saved selection with whatever the form can express (that file's
  header lists three things it cannot). That is the trap; the natural implementation falls in it.
- **The save runs before the generate and a failure stops both.** The other order queues a report
  and loses the definition, which looks identical to a save that worked.
- **`saveAs` is this form's name for the server's `name`** — `applyServerError` takes an alias map
  for exactly that one field, so "give it a name" lands on the box it is about.
- **Two keys on one panel, and it is correct**: Generate/regenerate costs `report:export`, Save and
  Delete cost `report:view`. A saved definition is the caller's own row, invisible to anyone else,
  so the key that lets somebody save one is the key that lets them throw it away.
- **`ReportJob` gained a required `requestedAt`.** Any mocked body without it fails the §197 harness.

## §223 — A PROVIDER CAN BE A DIRECTORY, AND ON SCREEN THAT IS A MODE SWITCH

**Read this before touching `LoginPage.tsx`, `ProviderFormDialog.tsx` or anything the word LDAP
appears near.** The decisions live in `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md`
(D1–D9); the backend seams in `be-java.md`'s §223 entry.

- **`SsoProviderChoice.kind` decides what a provider button *is*.** `OIDC` → a navigation to
  `/auth/sso/{id}/authorize` (§222's control). `LDAP` → a **mode switch on the same screen**: there
  is no third party to send the browser to, so the password is collected here. One pair of inputs,
  not two — the email is what discovery routed on. Directory mode adds the heading note ("Signing
  in with your *{displayName}* password."), relabels the field **Directory password**, and offers
  "Use my TCMS password instead". Editing the address drops the mode for the same reason it drops
  the provider buttons: a directory chosen for one address must not collect a different address's
  password.
- **A wrong directory password is the identical sentence a wrong local password is.** Anything
  naming the directory, the provider or a bind is an oracle for which accounts are federated. The
  401 body is empty by design; `useDirectoryLogin` is `meta: { handled: true }` because the form
  owns every outcome.
- **The token still has one writer.** `useDirectoryLogin` answers `AuthResult` (the same body as
  local sign-in) and the screen hands the token to `adoptSession` — never to storage.
- **`ProviderFormDialog` is one form for two kinds.** Kind is live on a registration and read-only
  on an edit (§31 disable-don't-hide — a row's kind changing is a different provider). The two
  transport refusals and the `{0}` check are duplicated client-side **on purpose**: the server
  refuses for reasons a 400 field error cannot carry, so the client message says them. `clientId`
  is `null` on a directory (`ck_identity_provider_shape` requires it); `bindPassword` follows
  `clientSecret`'s discipline — never prefilled, `null` is a keep, `''` is never sent.
- **Built and unverified**: `§223 LD-1`…`LD-4` in the browser queue, and they need an LDAP server
  the compose stack does not have — the options are recorded at the rows.

## §222 — THE EXTERNAL SIGN-IN LANE, AND THE SECOND WRITER OF THE SESSION TOKEN

The SPA can now complete a federated sign-in. Before this, `/sso/callback#token=…` rendered the
not-found page over a valid session.

### `adoptSession` is the second and last writer of `tcms.token`

It sits beside `login` on `AuthValue` and does the same four things: write the token, write the user
cache, set `status`, publish `signed-in` on the cross-tab channel. **Do not add a third writer, and
do not export `TOKEN_KEY`.** A callback page that called `safeStorage.set` directly would leave
`user` null, `status` stale and sibling tabs uncorrected — a signed-in app rendering a signed-out
shell.

It **round-trips `/me`**, because the fragment carries a token and nothing else, and `login`'s user
comes from the login response. A rejected `/me` **clears the token** — on *any* failure, unlike the
mount-time confirmation, which clears only on 401 so a backend restart does not sign everybody out.
The asymmetry is deliberate and written in the file: there the session is one the user already had,
here there is nothing to preserve.

`TestProviders` now supplies an `adoptSession` stub. A test whose subject *is* the adoption supplies
its own.

### The fragment is captured during the first render, and that is not a style choice

`SsoCallbackPage` reads `window.location.hash` in a **`useRef` initialiser**, wipes it with
`history.replaceState` in the effect, and guards adoption with a second ref. React runs effects twice
under StrictMode, so an effect that read *and* wiped sees an empty hash the second time and
**reports a failure for a sign-in that worked**. Same class as `AuthContext`'s `ignore` flag.

`expiresAt` is parsed and **ignored**. Nothing in this SPA has ever stored a session expiry; the
server enforces it (8h/30d, plus the `pv` epoch), and a client copy drifts on clock skew and is acted
on by nothing.

Both exits are `<Navigate replace>`, so the callback is not left in the history stack — Back from the
signed-in app must not re-run a callback whose `state` the server has already consumed.

### `/sso/callback` is the third public un-prefixed route

Beside `/login` and `/accept-invitation`, and un-prefixed for the same reason: the person arriving
holds a token and no company, so the segment M6d adds cannot be known until `/me` has answered. **The
path is `tcms.oidc.spa-callback-path` on the server side and the server builds the URL** — renaming
it here silently sends every federated sign-in to the not-found page. Not lazy, deliberately: a
code-split chunk between the issuer and the session is one more thing that can fail mid-sign-in.

### Discovery is silent about its own emptiness, and the silence is the requirement

`useSsoProviders` is a **mutation for a read** — the operation is a `POST` because an email address
in a query string reaches access logs, browser history and `Referer` headers, and this one is typed
by somebody who has not signed in. It fires on **blur**, not per keystroke.

An empty list, a failed request and a question never asked must all render as **nothing at all** — no
message, no empty state, no spinner resolving into a shrug. `POST /auth/providers` answers 200 with
`[]` for an unclaimed domain, a disabled provider and a malformed address alike, because a login
surface must not be an oracle for which companies exist here (§6r). Four of the six `LoginPage` tests
assert an absence for exactly this, and the failed-discovery one needs **`drivingAFailedWrite()`**:
the failure *is* the subject.

The SSO failure is **one sentence** in the existing `Login.Error` region, arriving as
`location.state.ssoFailed`. A form-submit failure wins when both are present.

### `authorize` is a navigation, never a fetch

`window.location.assign()` against `${BASE}/auth/sso/{id}/authorize`. It answers **302 to a third-party
origin**; a `fetch` follows it into a CORS failure at the issuer, and the point is to put the browser
at the issuer's login page. `BASE` is now exported from `api/client.ts` **for that one caller** —
anything else needing the prefix wants `api.*`, which applies it already.

### `IdentityProviderInput.clientSecret` is REQUIRED and nullable — the keep is a present `null`

This is the **opposite spelling** from `NotificationChannelInput.secret`, where the property is
optional and the keep is an *absent* key. Both mean "leave the stored credential alone"; copying
either arrangement into the other is a body the server refuses, or a live credential blanked with no
error and no symptom. Each dialog's header says which and why. Five of that schema's ten properties
are required-and-nullable, so `ProviderFormDialog` always sends all ten.

The dialog also refuses a non-`https://` issuer **before the server has to** — duplication on
purpose, because the server's 400 cannot carry the reason (the issuer URL is where the signing keys
are fetched from). It follows `IssueTokenDialog` / `NotificationChannelDialog`'s plain-`useState`
shape, not React Hook Form.

### `keys.emailDomains` is not a key under `keys.identityProviders`

It is `['identity-provider-email-domains']`. TanStack matches invalidations **by prefix**, so the
URL-mirroring spelling `['identity-providers', 'email-domains']` would be swept away by every
`invalidateQueries(keys.identityProviders)` — and the two resources are edited apart.

### `SsoProvidersPage` is the third screen whose READS are gated

`ApiTokensPage`'s and `NotificationSettingsPage`'s pattern, unchanged: `GET /identity-providers` 403s
without `company:idp` rather than answering `[]`, so the gate's verdict drives `enabled`, nothing is
fetched for a caller who may not read it, and the panel is replaced by a sentence naming who can.
Deferred on `gate.pending`, never rendered as a denial. **The rail entry is NOT gated** — the page
renders its own refusal, which is `NotificationSettingsPage`'s precedent and what makes a pasted link
behave the same for everybody.

**There is no Delete on this screen and there is none in the contract**: `user_identity` rows
reference a provider, so removing one would orphan or cascade away every federated account.
`enabled: false` is the off switch. **Disabled providers stay in the list** — a list that hid them
could not be used to enable one, and registration always produces a disabled provider.

### What is queued and unrun (§65) — five rows

`§222 SS-1`…`SS-5`, in the same browser lane as `§217 SO-*` because they need the same state, and
four §217 rows (`SO-1`, `SO-3`, `SO-6`, `SO-7`) re-pointed **in place** — ids are quoted from handoff
sections and are never renumbered. `SS-1` inherits §217's unresolved setup problem: the compose
Keycloak is plain HTTP and §219 decided the https gate **stays**, so registering *that* issuer
through the product is what the form correctly refuses.

## §218 — THE FIRST SCREEN WHOSE **READS** ARE GATED, AND THE FIELD WHERE ABSENT MEANS UNCHANGED

**Read this before editing `features/settings/NotificationSettingsPage.tsx`,
`features/settings/NotificationChannelDialog.tsx` or `api/queries/integration.ts` — and before
"tidying" any form in this tree that sends a credential.**

### An empty field is not an empty value, and this is the one place in the API where that is true

`NotificationChannelInput.secret` is **write-only**: the wire carries it in, nothing carries it back,
and the read model has `secretHint` — the masked last four characters — instead. So an edit form has
nothing to pre-fill the field with, and the contract inverts this API's usual `PUT` convention to
match: **absent means unchanged**, everywhere else on a full replacement an omitted field is a clear.

`NotificationChannelDialog` therefore builds the payload with a **spread**, not an assignment:

```ts
...(trimmedSecret === '' ? {} : { secret: trimmedSecret })
```

`secret: value || null` and `secret: value ?? ''` are both the defect, and the defect has **no error
and no symptom**: the save returns 200, the row goes on reading as configured, and delivery stops
hours later at a time unconnected to the edit that caused it. The assertion that catches it is on the
**payload's key set** — `expect(Object.keys(body)).not.toContain('secret')` — because
`expect(body.secret).toBeUndefined()` passes for `{ secret: undefined }`, which happens to serialise
correctly today and would stop doing so the moment the property were set explicitly.

### §31 has to be answered differently when the DATA is what is withheld

Every operation under `/projects/{id}/notification-channels` costs `integration:manage`, **reads
included** — a channel row carries the tail of a live credential and people’s addresses, a delivery
row carries whatever a remote endpoint said. So `ReviewSettingsPage`’s pattern (render it read-only
for everybody, disable the controls) is **unavailable here**: there is no read-only view, because the
list 403s.

What this screen does instead is `ApiTokensPage`’s answer, and the four parts are one decision:

| | |
|---|---|
| The rail item | **Not hidden.** Hiding it would teach a contributor the product cannot send notifications — a lie about the product rather than about their access. |
| The page | Renders, with a sentence naming who can configure channels. **Never an empty table**: "no channels" is a fact this caller is not entitled to. |
| The reads | `enabled` is driven by the gate’s verdict. An ungated fetch 403s and raises a global "Something didn’t load" over a screen working as designed. |
| While pending | `gate.pending` renders **nothing**. `Gate.can` is fail-closed while loading, which is right for a button and would flash a denial at a Test Lead on every visit. |

The transferable half is the backend’s own rule pointing this way: *read the gate off what the
response carries, not off the screen the endpoint belongs to.*

### `kind` is disabled on an edit and still submitted, and both halves are forced

The `PUT` **ignores** `kind` — a transport is not editable — while `NotificationChannelInput` lists it
as **required**. Dropping the control is therefore not available: the form would have to invent a
value or send a body the server refuses. Disabling it and submitting the channel’s own kind is the
only arrangement that is both legal and honest, and the helper text names delete-and-create as the
way to move a transport.

### `subscriptions` is the COMPLETE set, so the editor must seed from the channel

The array is reconciled server-side — an event type left out is unsubscribed. That makes two mistakes
silent rather than loud: an editor that started every box unticked would unsubscribe everything on
save, and a form that submitted only this session’s *additions* would make removal impossible. The
checkbox list is derived from `NOTIFICATION_EVENT_TYPES`, which is `Object.keys` of the label map —
so a member added to the contract’s enum is a compile error in `lib/labels.ts` and the list inherits
it. **Do not hand-write a second array of event types.**

### `NotificationDelivery.eventType` is a bare string, and the history column has a fallback

Everything else in `lib/labels.ts` is `satisfies Record<Union, string>` and looked up directly. The
delivery log is the exception: it is **history**, and may name an event this instance no longer
sends, so the contract types it as a string. `notificationEventLabel()` is the fallback-carrying
lookup for that one column — a `satisfies`-checked map used there would render a blank cell for a row
that really happened. Serving the live enum where the contract says string is fine; the reverse is
what the harness fails.

### Three query keys, and the delete REMOVES two of them

`keys.notificationChannels` / `notificationChannel` / `notificationDeliveries`. The delete hook calls
`removeQueries` on the last two rather than invalidating them: the server hard-deletes the history
with the channel, so an invalidation would schedule a refetch of a sub-resource whose parent is gone
— a 404 rendered as a failed region on a screen where nothing failed. The list is invalidated
normally.

`useUpdateNotificationChannel` always sends `If-Match` from the `lockVersion` the client read. A 409
is a lost update and **`describeError` owns the sentence** — do not compose one at the page.

**Anchor the mock arms.** `…/notification-channels` is a prefix of both `…/{id}` and
`…/{id}/deliveries`, so a `startsWith` arm answers the delivery log with the channel list and the
contract comparison then reports it against the wrong schema.

**No `packages/` surface.** Neither the CLI nor the MCP server offers notification channels, and
that is a decision rather than an omission: an agent that could create a channel could point one at a
URL it chose.

### What is queued and unrun (§65) — eight rows, and one of them is the second event type

`§218 NT-1`…`NT-8` in `docs/verification/frontend-browser-checks.md`. Three are worth knowing about
before touching this screen, because each is the *only* observer of a decision above:

- **`NT-3`** is the `secret`-absent-means-unchanged round trip, read from both ends — the hint's tail
  unchanged *and* the `PUT` payload with no `secret` key. Nothing else in either stack watches the
  live behaviour of that spread.
- **`NT-5`** is the read gate: the rail item present, the sentence rendered, and **no request fired**.
  Check the network tab, not just the copy — a fetch here 403s and raises a global "Something didn't
  load" over a screen working exactly as designed.
- **`NT-8`** was added by phase 4 and drives **`work.item_assigned`**, which until then nothing in
  either queue touched: the backend's end-to-end test drives `run_completed` only, so the second
  event type had a producer pin, a consumer pin, and nothing that crossed between them. It also
  proves the failure threshold is **inert** for an event with no counts — the claim
  `notificationEventTypeHelp` makes in the dialog.

`NT-6`/`NT-8` need a webhook endpoint the pass supplies; the block recommends a local HTTP sink, and
the charter now says so where a reader preparing the stack will meet it.

---

## §209 — THE ARTIFACT REGISTER'S TWO NEW ACTS, AND THE QUALIFIER READ AGAINST THE ROW

**Read this before editing `features/artifacts/{ArtifactTable,ArtifactDialogs}.tsx`.**

Both acts existed only as **advice** until §209 — the delete's 409 said *"Supersede it with a
correction instead"* and this dialog's own upload helper text said *"replacing the document means
registering a new artifact that supersedes this one"*. Neither named anything the product could do.

**`ArtifactTable` now runs a query.** `ArtifactRowActions` calls `useCan(projectId)` and
`useSetArtifactStatus`, so **every test that renders this table must mock `/me/permissions`** — the two
pre-existing tests in `ArtifactTable.test.tsx` did not, and since §202 a failed read fails the test.

**The gate is `canOnScope('artifact:edit', row.scope === 'PLAN')`, not `can('artifact:edit')`.** The
register is the one table in the product holding rows at **two scopes side by side**, and the grant
was qualified by exactly that: ~~a Test Engineer's `artifact:edit` is `PLAN_SCOPE`, so the ⋯ button is
disabled on a project-scope row and live on a plan-scope one~~ → **`V52` (§210) de-qualified it**, so
that caller now gets a live button on both, which is the intended widening and not a regression.

**Keep `canOnScope` here.** It reads the qualifier the server sent, so it is correct for whatever the
catalogue says on the day it runs — an unqualified grant makes it a no-op, and swapping it for the
plain key would be a second implementation of the permission model that goes wrong the moment a
qualified grant returns. Asking the plain key would have turned each project-scope click into a 403
(§31's renders-then-rejects), and that is a live failure again the day the qualifier comes back.
`schemaFreshness`-style safety does not apply here; nothing fails if this is collapsed, which is
exactly why it is written down.

**The Replaces picker issues its own `useArtifacts` read**, keyed on the scope and plan the **form** is
on rather than the page's filter. The two differ the moment somebody viewing a plan registers at
project scope. It filters to `status === 'ACTIVE' && !inherited`, which is the server's same-scope rule
stated where it can be enforced: `inherited` rows inside a plan query are the *project's* documents, and
a plan-scope artifact may not replace one. The selection is cleared when scope or plan changes.

**Send `null`, never `''`.** `supersedes` is a nullable uuid; an empty string is a 400 about a field the
user never touched.

Two deliberate deviations from `ui-rules`, both argued in the files:

- **Archive and Restore are two menu items**, always rendered, each enabled by the row's state — §4
  refuses one control that swaps its own meaning. A `SUPERSEDED` row disables both with the server's
  own 409 sentence.
- **No confirmation dialog on Archive.** It destroys nothing, the row stays on screen with its new
  status, and the way back is the item directly beneath. §7 refuses a blocking modal where the
  interruption is not the value. **Removal is the destructive act — and `DELETE …/artifacts/{id}` still
  has NO consumer in `src/`.** The ⋯ menu is now where that item belongs; §118's dialog is the pattern.

`artifactStatusLabel` is in `lib/labels.ts` (`satisfies Record<ArtifactStatus, string>`); the chip's
tooltip names the *replacement's title*, resolved from the rows already on screen — the same-scope rule
is what guarantees it is among them.

---

## §208 — `suiteMoves` IS A SECOND COPY OF THE SERVER'S GATE, AND IT MOVED

**Read this before editing `SuiteList.tsx` or `BulkActionDialog.tsx`.**

`SuiteList.tsx`'s `suiteMoves` mirrors `LifecycleLadder.permissionFor` per status, as a **disabling
hint and never the gate**. The server's rule is now one sentence — *the approve key buys a sign-off,
and it buys touching an artifact that has one* — so the table reads:

| From | `suite:manage` | `suite:approve` |
|---|---|---|
| `DRAFT` | `IN_REVIEW`, **`DEPRECATED`** | — |
| `IN_REVIEW` | `DRAFT`, **`DEPRECATED`** | `APPROVED` |
| `APPROVED` | — | `DRAFT`, `DEPRECATED` |
| `DEPRECATED` | **`DRAFT`** | — |

The three bolded cells are §208's change. Everything under `APPROVED` is §75's finding and is
**untouched** — if you are editing this table, that row is the one whose regression is silent.

**`BulkActionDialog` gates `APPROVED` and nothing else, and that is correct rather than incomplete.**
The other approve-keyed edge is *moving a case that is currently approved*, which depends on each
selected row and not on the form — those rows come back in `skipped` with a reason, which is this
dialog's standing shape for a partial failure. Do **not** add a `DEPRECATED` gate here; until §208
the absence of one was a real §31 gap (the server refused what the select offered), and §208 closed it
by making the server agree with the dialog.

**Testing either:** every item in the suite menu is disabled while the permission query is in flight,
so an assertion that an item is *enabled* passes vacuously against a component that never resolves.
Use `waitFor`, as the two §208 tests do — this is the §201/§202 shape one control over.

Struck in `BulkActionDialog`'s header, false since `V51`: *"`case:delete` is the only `OWN`-qualified
key in the catalogue"*. **No** key carries `OWN`, so the paragraph's conclusion holds for a stronger
reason.

## §207 — NO GRANT IS `OWN`-QUALIFIED, AND THE `authorId` PLUMBING STAYS ANYWAY

**Nothing in this tree changed behaviour.** Four prose sites, two test headers, and a regenerated
`schema.d.ts`. Read this before touching a permission gate.

**The rule:** CRUD on plans, cases and steps belongs to **every user who is not a Viewer, no matter
who created the row** (`V51`, roles spec §A3/§A5). `case:delete` and `evidence:delete` lost the
`OWN` qualifier; Test Engineer gained `plan:delete`. After it, **no grant in the catalogue carries
`OWN`.**

What that means here:

| | now |
|---|---|
| `useCan.canOnAuthored(k, author)` | answers exactly what `can(k)` answers, for every key and row |
| `PermissionGate` with `authorId` | the authorship term is a no-op; the button enables for any holder |
| `PlanStatisticsPage`'s Delete | **lit up with no edit** — it already gated on `plan:delete` alone |
| `canOnScope` / `PLAN_SCOPE` | **unchanged and still populated** (`artifact:edit`). Do not confuse the two axes. |

### Do NOT delete the `authorId` plumbing

`useCan.canOnAuthored`, `reasonOnAuthored`, and the `authorId` prop passed at `CaseDetailPage`'s
Delete and `EvidencePanel`'s Remove are all retained **on purpose**. The qualifier is *server-side
data*, not a client constant — the server keeps `PermissionQualifier.OWN` and
`IdentityProjectAccess.canDoOnAuthored` wired for exactly the same reason. A client that hard-codes
"authorship never matters" re-creates §66's defect **inverted**: an enabled Delete that 403s.

`?? null` vs `?? undefined` still matters and still means two different things (§66) — `undefined`
tells `PermissionGate` the affordance is *not* author-qualified; `null` is a *supplied* author
matching nobody. Unchanged by §207.

### No frontend test failed, and that is worth knowing rather than lucky

`useCan.test.tsx`, `PermissionGate.test.tsx`, `CaseDetailPage.test.tsx` and `EvidencePanel.test.tsx`
all **mock** the qualifier (`answer(['case:delete'], { 'case:delete': 'OWN' })`) instead of reading
the seeded catalogue. So they kept passing, and they are now the **only** coverage of a mechanism no
shipped role reaches. Their headers say so. If you are tempted to "update them to reality" by
dropping the `OWN` mocks, you would be deleting that coverage. #confirmed

`createdBy` **stays on the wire** — no longer load-bearing for authorization, kept as a display
field (author ≠ owner, they diverge on reassignment; `CaseVersionPanel` renders it).

---

## §205 — A FILE NAMED IN A COMMENT HERE MUST EXIST, AND THE CHECK LIVES IN THE OTHER STACK

**Nothing under `frontend/src` ships differently. Two citations were stale and are fixed. What is
new for a frontend agent is the check, and where it runs.**

`packages/client/src/test/citedFiles.test.ts` scans **every `.ts`/`.tsx` under `frontend/src`** (and
every `.ts` under `packages/`) for a backtick-quoted path with a known extension, and asserts the
file exists. 350 files, 140 distinct citations.

- **It is one check, not a pair, and it runs in the `packages` job.** Unlike `contractBodies.ts` —
  which is copied into both stacks because a Vite `?raw` import and two npm roots make an import
  impossible in *production* code — this is a test reading files off disk, and both CI jobs check the
  repository out whole. So a comment you write here that names a file which does not exist **fails a
  job in the other stack**. That is stated in the check's own header as its cost.
- **The escape hatch is this repo's own notation.** A struck-through name (`~~x~~ → y`) is already a
  statement that the file does not exist, so those spans are excused. If the *absence* is the point
  of a present-tense sentence — `ErrorBoundaries.tsx` naming `error.tsx` to say a Vite SPA has no
  framework tier — it goes in `CITED_BUT_ABSENT_BY_DECISION` with the reason, pinned both ways.
- **`schema.d.ts` is out of the corpus**, because it carries `openapi.yaml`'s prose. ~~which cites
  files freely and is a different rule's business~~ → **§206: the reason was wrong and the finding
  §205 recorded here proves it.** Not scanning a *generated copy* is right; not scanning the
  *source* was not, and `docs/specs/*.yaml` is a corpus now. The consequence this bullet recorded
  rather than fixed — the contract's `WorkItemStatus` naming a ~~boardColumns.test.ts~~ that does
  not exist — is fixed: the check it described is real in two places, `workItemColumns` in
  `lib/labels.ts` (the keys of a map declared `satisfies Record<WorkItemStatus, string>`) and
  `BoardPage.tsx`'s `_everyStatusHasAColumn`, and both are compile errors rather than tests.

What it found here, neither of them this slice's own work:

| citation | citer | truth |
|---|---|---|
| `contractResponses.ts` | `src/test/contractDoubles.ts` | renamed to `contractBodies.ts` in §199; that file's header has the rename struck through and this one was missed |
| `renderFailures.test.ts` ×2 | `src/test/renderFailures.ts` | the file is `.tsx` |

**One finding about `frontend/src/api/client.ts` that is NOT fixed here.** `Problem` declares five
properties and requires four; this client reads `detail`, `title`, `errors` and the response status,
and **drops `type`**. `@tcms/client`'s `TcmsApiError` carries it. Neither stack *reads* the value
anywhere, so adding a field to `ApiError` would be a readerless one (§154, §165) — the question is
whether `describeError.ts` can do something with a problem *kind* that it cannot do with a status,
and the answer arrives with a consumer or not at all. `packages/client/src/problemShape.test.ts`
carries the measurement.

## §203 — `contractBodies.ts` EXISTS TWICE AND THE TWO COPIES ARE COMPARED

**Read this before editing `src/api/contractBodies.ts` at all.** Between its two `SHARED REGION`
sentinels the text is **byte-identical** to `packages/client/src/test/contractBodies.ts`, and
`packages/client/src/test/contractPairing.test.ts` compares the two slices line by line.

- **Editing anything inside the region means editing both files.** The pairing test names the first
  line that differs, but only the `packages/` suite runs it — so an edit made here alone is green in
  `frontend/` and red one stack over, which is the one way this can bite.
- **It is a copy rather than an import because neither stack can reach the other**: two npm roots,
  a Vite-only `?raw` import on this side, and two CI jobs each installing a tree the other never
  sees. A copy nothing pairs is §196's two tsconfig lists, so the copy is paid for by a test.
- **The region must compile under `packages/`'s stricter flags** — `noUncheckedIndexedAccess` and
  `exactOptionalPropertyTypes`, neither of which `tsconfig.app.json` sets. That is why `Constraints`
  spells `| undefined` on its three optional members and why `contractPathOf` binds
  `url.split('?')[0] ?? url` rather than indexing inline. Writing looser code here compiles fine and
  breaks the other stack's build.
- **What is outside the region is the difference between the stacks**, and since §204 there are
  three: how each loads the document (`?raw` here, `node:fs` there), and — there only — the `/api/v1`
  prefix `@tcms/client` sends, and `successStatusesOf`. That last one is the shape of the rule: a
  question **only one stack can ask** goes outside the region rather than into it. The SPA's double
  replaces a method on the `api` object, which answers `{ data }`, so a copy of it here would be an
  export with no possible caller.

`packages/` got its own half of §197–§199 in the same slice and it found **118 red of 241**. Nothing
about it changes how a test is written *here* — but if you are working in that stack: the seam is
`packages/client/src/test/contractExchange.ts`, called from all **three** `fetch` doubles, the
fixtures are `packages/client/src/test/fixtures.ts`, only a **2xx** answer is measured against the
success schema, and the single hatch is `drivingTheTransport()`. **Since §204 the status is compared
too** — a 2xx a double answers with must be one the operation declares — and the population is
**zero**: 17 of the 133 operations declare two success statuses and sixteen of those are creates
declaring 200 *and* 201, because a replayed `Idempotency-Key` answers 200. `CLAUDE.md`'s `packages/` block carries the rest.

## §201 — A TEST WHOSE RENDER TRIPPED A BOUNDARY FAILS, UNLESS IT SAYS IT MEANT TO

**Read this before writing any test that renders a page with more than one panel on it, and read it
with §197–§199 below — same family, other half. That one checks what crosses the wire; this checks
what the tree did with it.**

- **`src/test/renderFailures.ts` is installed globally from `src/test/setup.ts`**, like
  `contractDoubles`. It reads `window.__tcmsErrors` after every test, keeps the reports whose
  `ErrorSource` is one of the three boundary tiers, and **fails the test in `afterEach`** naming the
  region's `testId`. Nothing opts in; you cannot opt out by writing a new file.
- **The escape hatch is `renderingIntoAnErrorBoundary()`**, called inside the test with a reason —
  `servingADriftedBody()`'s design one layer up. **Two tests in the suite call it**, both in
  `DataRegion.test.tsx`, where a contained throw *is* the subject.
- **Why the check exists at all:** a boundary is there so that one broken region does not take the
  page down, which means a tripped one is invisible to every assertion not aimed at it. §198's 24
  tests passed for years beside a version panel showing *"We couldn't display this content."* A green
  run cannot distinguish that from the panel having rendered.
- **It reads the SINK, not the DOM.** A boundary reset by `resetKeys` leaves no DOM trace, and RTL's
  `cleanup` owns an `afterEach` this module does not — so a `queryByTestId('…RenderError')` sweep
  would race a hook order and report on survivors. `reportClientError` fires at the throw and carries
  the region's `testId` in `context`.
- **`KIND_OF` is a `Record<ErrorSource, FailureKind | null>` and must stay one.** A `Set` would go
  quiet on a fourth boundary tier while staying green; the `Record` makes a new `ErrorSource` member a
  compile error there. (It was `IS_A_RENDER_THROW`, a `Record<…, boolean>`, until §202.)
- ~~**A region whose *fetch* failed is NOT seen by this**, because `createTestQueryClient` installs no
  error handlers.~~ → **superseded 2026-08-14 by §202: it is seen. Read the entry below.**
- **`resetReportedErrors()` runs in a `beforeEach` now** — first caller it has ever had. The sink
  dedupes on `source:name:message` and never forgets, so without it the second test in a file to hit
  the same broken panel collects nothing.

## §202 — A TEST WHOSE *REQUEST* FAILED FAILS TOO, AND THERE ARE THREE HATCHES

**Read this with §201 above — one module, one `afterEach`, two halves. If you are writing a test that
drives a failure on purpose, the hatch you need is here.**

- **`src/test/queryClient.ts` now installs a `QueryCache` and a `MutationCache`** whose `onError`
  calls `reportClientError` and stops. §201 read that file's *"no global error handlers"* as a design
  constraint; it was one sentence doing two jobs — **raising a notification** needs the provider,
  **reporting** needs nothing — and the app's own `onError` does them in that order.
- **The three hatches are not interchangeable, and this is the design rather than a nicety.**
  `renderingIntoAnErrorBoundary()` · `drivingAFailedRead()` · `drivingAFailedWrite()`, each excusing
  its own kind for one test, each called with a reason at the site. **44 of the 61 opt-outs are a
  refused write**, and such a test says nothing about whether the panels beside the dialog were
  allowed to fail their reads — which is where §198's defect lived — so one shared hatch would have
  disarmed the check in the place it was written for.
- **58 call sites across 33 test files** (58 not 60: one is an `it.each` of three). Every one of the
  61 reds was deliberate; the population of *unintended* failures is **zero**, as it was for §201's
  half.
- **What it buys, measured:** deleting the `/users` arm of `ApiTokensPage.test.tsx`'s double reddens
  **1 test of 9** in §201's state and **9 of 9** now — eight were passing beside a panel that could
  not load its owner names. §201's *"the seven tests failed on their assertions"* was a reading, not a
  measurement; it is one.
- **Do not construct a `QueryClient` in a test.** `createTestQueryClient` is the only one with the
  caches, so a hand-built client is invisible to this check — silently. `renderFailures.test.tsx`
  sweeps every test source for `new QueryClient(` and requires the list to be **empty**, and requires
  ≥50 of the 100 files to reach the helper (59 do today).
- **`unexcused(reports, meant)` is exported for its own test**, because *"the check decided not to
  fail"* cannot be observed from a green run any other way. Everything else in that file drives a real
  throw or a real query — the vacuity rule §201 wrote, and §202's own request cases were the thing it
  was warning about: they *were* two hand-made `reportClientError` calls, hand-made because the path
  they classified did not exist.

## §200 — THE PLAN TAB'S SAVE WORKS, AND `caseSchema.ts` IS DELIBERATELY STRICTER THAN THE SERVER

§199 got `useCreatePlanCase`'s payload past the *contract* and left it one server rule short. The
server moved: `CaseEdit.validate`'s procedure rule is `REQUIRED` on `PATCH` and `DEFERRED` on `POST`.

- **What that tab creates is a `DRAFT` stub** — a title, `NEW_CASE_DEFAULTS`, and nobody has said how
  to run it. The case editor is where it gets finished, and `caseHealth` calls a missing procedure a
  fault only once such a case is **approved**.
- **`caseSchema.ts`'s two format refinements are now stricter than the server, and must stay.** Both
  used to be justified as mirroring it. The editor *renders* a step field, so an author here has been
  asked the question; matching the server would make the one surface built for writing a case the
  surface that saves an empty one. The comment above the refinements says this — do not "fix" the
  divergence.
- **Nothing in the SPA renders a stub differently**, and that is an open product question rather than
  a gap this slice left: the plan shows a case, the case list shows a `DRAFT`, and nothing says
  *"three of these have no steps yet"*.
- The `schema.d.ts` change is **descriptions only** — both operations gained prose about the
  asymmetry, and `openapi-typescript` emits it as JSDoc. No type moved, so nothing under `src/`
  needed adapting.

## §199 — AND IT MAY NOT SEND A BODY THE SERVER COULD NOT ACCEPT

**The third entry on one seam. Read it with the two below it — same wrapper, same six questions, the
other direction.**

- **`src/api/contractResponses.ts` IS `src/api/contractBodies.ts` NOW**, because it answers both
  directions and a module named for one half of what it does is §152's shape.
- **What is new:** the value a double is **called** with — `api.post/patch/put`'s second argument —
  is compared against the operation's `requestBody` schema, and a disagreement fails the test in the
  same `afterEach`. The report says **`sent to`** rather than `served for`.
- **A `sent` violation is usually NOT a fixture.** The payload arrives at the double having been
  composed by `api/queries/` and the form above it, so the repair is normally in the code under test.
  This is the only instrument in the SPA that looks inside an outgoing body: `apiResponseTypes`
  refuses an inline literal at a call site, `contractFixtures` refuses a cast, `contractOperations`
  checks the method and the path, and `toCasePayload` returns `Record<string, unknown>`.
- **`api.get(path, options)` and `api.del(path, options)` carry `RequestOptions` in the slot the
  writes carry a body.** `SENDS_A_BODY` is the list; do not widen it without reading the client.
- **`{}` and no second argument are the same request** (`JSON.stringify(body ?? {})`), so an
  operation declaring no body is only reported when the object has something in it. `api.upload`'s
  `FormData` is refused outright, as `uuid` is.
- **The check runs BEFORE the double resolves**, so a test whose mutation rejects still has its
  payload compared. That is where a payload defect hides — a test asserting a failure asserts nothing
  about what was sent.
- **`NEW_CASE_DEFAULTS` (`src/lib/caseDefaults.ts`) is the one answer to "what does a case say before
  the author does"** — `STRUCTURED` / `MEDIUM` / `MANUAL`, used by `CaseEditorPage`'s `defaultValues`
  and by `useCreatePlanCase`. `useCreatePlanCase` sent `{title, suiteId}` from §97 to §199, and
  `TestCaseInput` requires **four** fields, so the plan tab's Save answered 400 the whole time.
  **`STRUCTURED` is not a preference** — `GHERKIN` is refused without a scenario body.
- **The plan tab's create still 400s, on `steps`, and that is recorded rather than hidden.** §144 put
  the conditional requirements (`steps` for `STRUCTURED`, `gherkinSource` for `GHERKIN`, …) off the
  contract deliberately, so this check cannot see them. `§199 PC-2` in the browser queue is written
  as a row **expected to fail**; closing it is a product decision, not a patch.

## §198 — AND IT MAY NOT SERVE A VALUE THE SERVER COULD NOT SEND EITHER

**The other half of the §197 entry below. Same seam, same failure in `afterEach`, four more
questions** — so read them together and treat the two section numbers as one rule.

- **What is checked, beyond §197's two structural questions:** an **`enum` member the contract does
  not list**; a value of the wrong **`type`** (`'4'` for an `integer`, an object for an `array`); a
  **`null` in a property carrying no `nullable: true`**; a **`format: date-time`** string that is not
  an RFC 3339 instant.
- **The null one is not the required one.** `'name' in body` is satisfied by `name: null`, so a
  fixture that nulls a field the server always fills passes every structural check there is. If you
  are reaching for `null` to mean "I don't care about this field", check the contract first — the
  factory's default is what you want.
- **`format: uuid` and `pattern` are deliberately NOT checked**, and the refusal is pinned by
  `says nothing about an id that is not a UUID`. Ids here are `'p1'` and `'c1'` on purpose; the SPA
  treats an id as opaque. **Do not "tighten" this** — you would rewrite 208 fixtures and catch
  nothing.
- **The escape hatch is still `servingADriftedBody()`**, and it now suspends all six questions.
- **A `type: array` operation answered with an object is a violation now, and used to be silence.**
  That is what this slice found: eighteen doubles ended
  `if (path.startsWith('/projects/')) return aProject(…)`, which answers `/statuses`, `/reviews` and
  `/cases/{id}/versions` with the project. **Write the arm anchored** —
  `/^\/projects\/[^/]+$/.test(path)` for the project, `/\/cases\/[^/]+$/.test(path)` for the case —
  so a sub-resource falls through to the fallback rather than being caught by a prefix.
- **A region whose children throw renders its error branch and nothing fails.** `DataRegion`'s
  `isEmpty` asked a non-array for `.length`, got `undefined`, and `rows.map` threw into the region's
  `ErrorBoundary` — **24 tests passed for years with the version panel beside them broken.** When a
  test asserts on one panel, nothing tells you about the others.

## §197 — A DOUBLE MAY NOT SERVE A BODY THE SERVER COULD NOT SEND

**Read this before writing any `vi.spyOn(api, …)` mock.** It is the §196 rule's other half, and the
one you will meet first, because it fails at *run* time rather than at `npm run build`.

- **`src/test/contractDoubles.ts` is installed globally from `src/test/setup.ts`.** It wraps
  `vi.spyOn` once; any spy for a member of the `api` object gets `mockImplementation` /
  `mockResolvedValue` / `mockReturnValue` (and the `…Once` forms) wrapped, so every body it hands
  back is compared against `openapi.yaml` and a disagreement **fails the test in `afterEach`**,
  naming the endpoint and the field. Nothing in a test file opts in; you cannot opt out by writing a
  new file.
- **Use `src/test/fixtures.ts`.** Seventeen factories (`aProject`, `projectSettings`, `aCase`,
  `aCaseSummary`, `aSuite`, `aPlan`, `aPlanSummary`, `aRun`, `aConfiguration`, `noCounts`,
  `planStatistics`, `aWorkItem`, `aTeam`, `aDocPageSummary`, `anExternalLink`, `aCurrentUser`,
  `anAuthResult`), each typed and never cast, each defaulting to the **least interesting** body the
  contract admits. Override what your test is about; inherit the rest. Do not write a fresh literal
  for a shape that has a factory — that is how the 629 violations happened.
- **A path-keyed `mockImplementation` needs an arm per endpoint, and order matters.** The idiom
  `if (path.startsWith('/projects/')) return aProject(…)` written last catches
  `/projects/{id}/settings`, `/projects/{id}/runs` and everything else without an earlier arm. Nine
  files were answering the settings read with the project body, so every review-gating flag read
  `undefined` — falsy, and indistinguishable from review being off.
- ~~**It compares structure, not values.** An enum member the contract does not list, a `number` where
  it says string, a malformed `date-time` — all invisible. `status: 'ACTIVE'` (not a `PlanStatus`)
  survived four slices and was found by reading. Named as the next rung.~~ → **superseded by §198,
  which climbed the rung**; see the entry above. The `'ACTIVE'` instance was repaired by hand in §197
  itself, so the value check found nothing wrong with any enum, type, null or timestamp in the tree —
  it is a guard, and what it actually found was §197's silent array branch.
- **`servingADriftedBody()`** suspends the comparison for one test, for a test whose *subject* is a
  drifted body. Call it in the test and say why. Nothing calls it today.
- **`src/api/contractBodies.ts` (~~`contractResponses.ts`~~, renamed in §199) reads the spec through
  Vite's `?raw` import, not `node:fs`**, and
  must keep doing so: it is reached from the harness, so it is in the app program no matter what
  `tsconfig.app.json` excludes — `exclude` drops a file from the root set, not from the graph of what
  the roots import. `vitest.config.ts` carries the `server.fs.allow` line that permits it; that line
  belongs in the test config, never in `vite.config.ts`.

## §196 — A FIXTURE FOR A CONTRACT TYPE IS DECLARED, NEVER CAST

**Read this before writing any test fixture that stands in for a shape from `api/types.ts`.**

- **`src/api/contractFixtures.test.ts` fails the build on `{ … } as TestCase`** — on any object
  literal asserted to a type that resolves into `api/types.ts` or `api/schema.d.ts`, single cast or
  `as unknown as`, **complete or not**. Annotate instead: a `const`, a parameter, or the factory's
  return type. `function caseBody(over: Partial<TestCase> = {}): TestCase { return { …, ...over } }`.
- **It is a "no cast" rule, not a "no missing field" rule, and that is deliberate.** A complete cast
  is complete *today*; the whole point is the day the contract gains a field. One of the check's
  three pins is a **complete** cast, so weakening it into a missing-field check reddens.
- **Why it exists:** §79 decided this and seven files wrote it down in prose. Nothing compared it to
  the tree, so at §196 there were **57 casts across 31 files, 39 hiding a missing required field** —
  including three in `CaseEditorPage.test.tsx`, which states the rule three times in its first
  hundred lines, and two in plan files whose comments name this exact cast as the cause of §97's
  three red runs and left it in place. `TestPlan.keySeq` (§165) was missing from **eight** plan
  fixtures for thirty sections.
- **`heldPermissions` takes `PermissionKey[]` now**, not `string[]`: a misspelled key used to be a
  silent no-grant whose only symptom is a disabled control. If you add a `mockReads(permissions:
  string[])`, `tsc` will send you here.
- **What it cannot see: `as never`.** Every mocked read in this repo satisfies the generic client
  with `(async (path) => …) as never`, and `never` has no shape, so the *body* a test serves is
  unconstrained by the contract. Named as an open slice, not an oversight.
- **A deliberately-malformed fixture is still allowed** — assert the malformed *member*, not the
  whole object, the way `DocBlockBody.test.tsx` does with `'diagram' as DocBlock['blockType']` to
  reach the unknown-block-type branch.

## §195 — A SNAPSHOT MAY IDENTIFY THE ENTITY; IT MAY NOT PREDICT THE SAVE

**Read this before adding anything to a form that is derived from the entity the form loaded.**

- **The rule, from §195's audit of all seventeen `useForm` files:** a value read off the loaded
  entity and rendered beside the form is fine when it **identifies** the row (`Edit ${team.name}`,
  `Rename ${suite.name}`, `Edit ${existing.key}`, `ConditionEditDialog`'s *"Saving changes “…”
  everywhere it is used"*) — a reader needs to know *which* thing they are editing, and the stored
  name is the answer. It is a **defect** when it **predicts what the save will do** and a control on
  the same form is an input to that prediction. §194's inherited condition rows were the second
  kind; so was `CaseCloneDialog`'s closing caption.
- **Seven of the seventeen initialise from a loaded entity** and are the only ones where this can
  occur: `CaseEditorPage`, `CaseCloneDialog`, `RenameSuiteDialog`, `TeamDialog`,
  `DocPageEditorPage`, `PlanCloneDialog`, `RunCloneDialog`. The other ten are creates or the two
  auth screens. **A clone form is clean by construction** — it edits the *copy*, and every snapshot
  on it is of the *source*, which no control can reach. That is why `CaseCloneDialog`'s caption was
  the exception: it described the **copy**.
- **`CaseCloneDialog.notCarried` is EXACT, not a bound** — unlike `CaseEditorPage.movingFolder`,
  which infers "a move ends every decline" from §98's flatness. This one quotes
  `CaseCloneService`'s single `Objects.equals(targetSuiteId, source.getSuiteId())`, so there is
  nothing for a schema change to falsify. **That is why there is no measured snackbar here** and
  why §194 needed one: the alert cannot drift from the writer, and a clone destroys nothing — the
  source keeps every exception, so the dialog is only the last moment to decide not to (§193).
- **`''` on this dialog's Folder picker means "same as the source", NOT unfiled** — the opposite of
  the editor's `suiteId`, where `''` is UNFILED (§96). The difference is load-bearing in
  `landingElsewhere`, and it means **a clone cannot file the copy nowhere** at all: absent is the
  only spelling the endpoint has for the source's suite. Recorded as an open contract-side item.
- **Do not add `?? []` to `declinedPreconditions` / `declinedPostconditions`.** They are required on
  the wire. The `as TestCase` cast in the test fixtures defeats `required`, which is what made
  spreading them throw in a green test file — the fixture was wrong, not the component. 27 such
  casts across 15 files; none of the other 26 has been checked.

## §194 — THE CONDITIONS PANEL IS THE FOLDER THE CASE IS FILED IN *NOW*

**Read this before touching `CaseEditorPage`'s `movingFolder`, its Conditions `Panel`, or
`ConditionsPanel`'s `leavingFolder`. Read it with §187 two entries down, whose group it guards.**

- **`inheritedPre` / `inheritedPost` are derived from `existing`, and the Suite picker does not move
  them.** The rows keep naming the folder the case is filed in until the save lands. That is correct
  — and it means the decline switches beside them stop meaning anything the moment the picker moves.
- **A move ends EVERY stored decline**, so `leavingFolder` disables the switch. This is not a
  courtesy: `CaseEdit.normalizeDeclines` intersects the submitted set with what the **destination**
  imposes, and a flat suite (§98) shares no `suite_condition` id with any other, so a flip there is
  validated and then discarded by the same request. **Only the switch greys** — the case's own
  attachments are unaffected by a move and stay editable.
- **The warning is ONE alert above BOTH panels**, inside the `declinedConditionIds` `Controller`,
  for that field's own reason: one array carries both kinds, so a per-panel copy states one event
  twice and names half of it each time. Inline and `warning`-coloured, never a dialog — §193 named
  the click-through hazard in the arm it did not take.
- **Do NOT render the destination folder's conditions here**, however cheap it looks:
  `Suite.preconditions` carries the same ids on the list this screen already holds, and assembling
  them into "what this case will inherit" is the case endpoint's resolution rewritten in the client
  (the §187 entry below refuses exactly that for a smaller list) — built, on top of it, from the
  **declinable** bound rather than the retention one, because the suite list omits retired entries.
- **The alert PREDICTS and the snackbar MEASURES, deliberately.** The alert reads §98's flatness;
  the snackbar subtracts the saved case's `declinedPreconditions`/`declinedPostconditions` from what
  the form submitted, so it is the writer's answer. If inheritance ever widens, the snackbar stays
  true and the alert is the thing to fix. The subtraction is gated on the folder having changed;
  both its terms are what a *list* shows, so a retired-entry decline is counted by neither.
- `plural` lives in **`lib/format.ts`** since this slice, not in `lib/flake.ts`.

## §193 — THE BULK PREVIEW IS THE ONLY PLACE A LOST DECLINE CAN BE REFUSED

**Read this before touching `BulkActionDialog`'s `BulkPreview` or `CaseListPage`'s `onCommitted`.**

- **`BulkResult.declinesEnded` is a SUBSET of `changed`, never a fourth bucket.** Counting it into
  the `will change · already match · will be skipped` line would double-count; it renders as its own
  named group, like `skipped`, because *"3 will lose an exception"* leaves nobody able to find out
  which three.
- **It is populated on the PREVIEW**, and that is why the group exists — a `MOVE_TO_SUITE` deletes
  those declines server-side, so this dialog is the last moment a person can decide not to. The
  alert severity is **warning** when it is non-empty, on the same footing as a skip.
- **The commit snackbar repeats it and is a warning**, because it is the only record of the event
  that survives the dialog closing. No other screen in the product mentions that a decline ended.
- Nothing here computes the rule. Whether a case loses a decline is the server's answer
  (`CaseBulkService.endsADecline`); the SPA renders keys.

## §187 — AN INHERITED CONDITION HAS A TOGGLE, AND A DECLINED ROW STAYS IN THE PANEL

**Read this before touching `ConditionsPanel`'s inherited group, `CaseEditorPage`'s
`inheritedPre`/`inheritedPost`, or `caseSchema`'s `declinedConditionIds`.**

- **A declined row is greyed and struck through IN PLACE, never removed from the group.** The toggle
  is the only control that restores the rule, so removing the row makes the action one-way from the
  one screen that offers it. That is what the server's shape was before §187, one layer down.
- **The union of live + declined is made ONCE in `CaseEditorPage`, not in the panel.** Recomputing it
  as the toggle is used reorders the list under the author's cursor: the row they just switched off
  jumps to the end and reads as the control having deleted it. Declined rows sit **last** because
  their §6f position is resolved over the suite chain server-side, and interleaving them here is the
  second implementation of that order `CaseDetailPage` refuses to write for the same list.
- **ONE `Controller` over `declinedConditionIds`, wrapping BOTH panels.** The field is a single array
  carrying both kinds; two Controllers over it are two independent copies, and the last panel to fire
  overwrites the other's toggles with a value read before they happened.
- **The form sends attachment ids (`ref.id`), never `ref.conditionId`.** The server refuses a library
  id with a field error, so getting it wrong is a 400 on a save the author reads as broken.
- **`declined` / `onToggleDecline` are OPTIONAL on `ConditionsPanel`** because `SuiteDialogs` renders
  the same panel for a suite's own conditions, which inherit from nothing.
- **The switch has no `data-testid` on its input** — MUI v7's Switch input slot rejects an arbitrary
  data attribute. The handle is the accessible name: `getByRole('switch', { name: /applies to this
  case/i })`. The testid is on the `FormControlLabel`.
- **`CaseDetailPage` renders declined rows BELOW the checklist, not inside it**, and names the folder
  they still apply to. A struck-through line inside a checklist is a step a tester has to decide
  about.

---

## §186 — A SUITE'S CONDITIONS ARE EDITED IN THE SUITE DIALOG, AND `SuiteInput` IS GENERATED NOW

**Read this before touching `SuiteDialogs.tsx`, `lib/conditions.ts`, or adding a field to a suite
write.**

- **`ConditionsPanel` is reused, not reimplemented.** The suite create/rename dialog mounts two of
  them with `inherited={[]}` — by construction, because a suite's conditions *are* the inheritance
  and §98 removed anything above a suite to inherit from — plus the same `ConditionEditDialog` and
  the same `createdConditions` state the case editor keeps for the same reason (library reads are
  cached per search term, so a freshly created entry renders "Unknown condition" without it).
- **The dialog is `maxWidth="md"`.** At `sm` the panels' reorder controls wrap.
- **The form holds IDS; the payload is `{conditionId}` objects.** `Suite.preconditions` serves
  `CaseConditionRef` and `SuiteInput.preconditions` takes `CaseConditionInput` — the read shape is
  not the write shape, which is exactly why `suiteInputFrom` in `packages/client` drops both lists.
- **`SERVER_FIELDS` maps the payload's names to the form's** (`preconditions` →
  `preconditionIds`). Without it a rejected condition lands in the form-level alert instead of on
  the panel that owns it. Measured: deleting one entry fails exactly one test.
- **Always send both lists, including `[]`.** Absent means "leave them alone" server-side, so an
  omitted field makes detaching the last condition a save that reports success and does nothing.
  `tagRules`' rule, and it matters more here.
- **`SuiteInput` IS `S['SuiteInput']` NOW.** It was a hand-written literal in
  `api/queries/suites.ts` from Phase 2 — structurally right until §186 added two fields, then
  silently typing a payload the endpoint does not accept as one it does. §52's lesson on the write
  side, where `apiResponseTypes.test.ts` does not look (it refuses inline literals at `api.*` call
  sites; this was a named type used as an input). **`caseIds` is still on the schema and no screen
  authors it.**
- **`impactSummary`'s suite branch had NEVER rendered until this slice.** `suiteUsageCount` counts
  `suite_condition` rows and nothing could write one, so it was 0 everywhere; the string said
  *"every case beneath"* for eighty-eight slices after §98 removed the tree. It reads *"every case
  filed there"* now — only the **filing** arm inherits, not "added" and not tag-matched.

---

## §184 — NEITHER SCREEN RANKS A HEALTH SEVERITY ANY MORE

**Read this before adding a sort to `DashboardPage.tsx` or `CaseGrid.tsx`, and before writing an
ordering assertion in `HealthPanel.test.tsx`.**

- **`severityRank` IS DELETED.** `HealthPanel` renders `data.health` **verbatim**;
  `DashboardService.health` sorts by severity and then by **count descending**, and it is now the
  only ordering of tiles anywhere. §179 took this decision at the case grid (which carries the same
  do-not-sort comment); §184 applied it to the other screen showing a health severity.
- **`HealthPanel.test.tsx`'s order test now asserts the OPPOSITE of what it used to.** Its fixture
  passes a WARNING ahead of an ERROR — an order the server cannot produce — and expects the panel to
  render it unchanged. It reads like a bug and is not: it is the only input a sorting panel and a
  transparent one disagree about. Do not "fix" it.
- **The old sort was a no-op by coincidence, not by design.** `Array.prototype.sort` is stable and
  its key was the server comparator's *first* key, so the server's count tiebreak survived a sort
  that had never heard of it. It was also a **non-exhaustive ternary chain** — a fifth `Severity`
  member would have ranked bottom silently, where `severityVar` thirty lines away is a
  `satisfies Record<…>` and fails the build. That contrast is the general rule: a `Record` over a
  contract union is a compile error on a new member; a ternary chain is a default.
- **`HealthTileOrderTest` (backend) is where the order is asserted now.** A frontend assertion on
  which tile is worst would be the copy this slice deleted.

---

## §183 — ONE SEVERITY→COLOUR TABLE, AND INFO IS NOT AMBER

**Read this before touching `lib/statusColors.ts`, `components/StatusChip.tsx`'s health half, or
`features/dashboard/DashboardPage.tsx`'s tiles. Read §180's entry below with it — this is the same
finding in the same two files, one layer down.**

- **`severityVar` in `lib/statusColors.ts` is the ONLY severity→colour map in the SPA.** There were
  two: `severityColors` (private to `StatusChip.tsx`) and `severityColor` (private to
  `DashboardPage.tsx`). Four keys each, three values equal, **INFO differing** — amber on the case
  chip, blue on the dashboard tile — so one severity had two colours and the same signal read as
  work in the grid and as context on the dashboard, one navigation apart. Third instance of the
  shape after §179's `SEVERE_SIGNALS` and §180's `DashboardBreakdown.color`.
- **INFO is `var(--color-info)`.** This is §179's deferred reading-plan decision, taken: amber had
  been carrying both "wrong now" and "worth knowing", and once §178/§181/§182 gave `UNLINKED`,
  `ORPHANED` and `INCOMPLETE` writers, three of the ten signals were INFO and most rows of a young
  project carried an amber chip. Blue is a *different claim*, not a fainter one — `--color-text-muted`
  is NEUTRAL's, and it means "not a signal".
- **Nothing had ever asserted a colour on a tile.** That is why the disagreement survived two slices
  that read both files. The tile test now asserts against `severityVar`, never a literal — a literal
  is the fourth copy.
- **`DashboardTile.severity` is REQUIRED on the wire**; the tile's `?? 'NEUTRAL'` fallback went with
  the private map.

---

## §182 — THE HEALTH SIGNAL WHOSE WORDS LEFT OUT THE GATE THAT GIVES IT ITS MEANING

**Two strings and one test file. Read it before editing `healthLabels` or `healthHelp` — and read
§181's entry below it, because this is the same finding a fourth time.**

- **`INCOMPLETE` has a writer on the server as of §182** — it was a **contract-only** enum member from
  2026-07-30 until then, so this catalogue has labelled it for a year and nothing could ever raise it.
- **`healthLabels.INCOMPLETE` is `'Approved but unfinished'`** (~~`'Missing required fields'`~~) and
  the tooltip names the three terms plus the action. Both old strings were wrong twice over: **nothing
  is required** — all three of the server's terms are optional columns and the case saved fine — and
  they **left out the gate**, `lifecycle_status = 'APPROVED'`, which is the entire meaning. A DRAFT
  missing all three raises nothing, so the old label described a state no chip is ever raised for.
- **The tooltip says "open it to see which" on purpose.** The signal does not say which of the three
  is missing, and a sentence that guessed would be wrong on two cases out of three.
- **The word "required" is pinned as an ABSENCE** in `HealthChip.test.tsx`, for §181's reason: it is
  what the enum's own name suggests and exactly what a later tidy-up reaches for, and re-adding it
  would satisfy a `stringContaining` assertion.
- **FOURTH IN A ROW.** §158 (`CONDITION_DRIFT`), §178 (`OUTDATED_SPEC_CHANGED`), §181 (`ORPHANED`),
  §182 — every tooltip in `healthHelp` written before its signal could appear has needed rewriting by
  the slice that gave the signal a derivation. If you are writing copy for a signal nothing raises,
  that is the precedent.
- **`INCOMPLETE` is the third INFO signal now**, after `UNLINKED` and `ORPHANED`. §179's recorded
  "quietening INFO on a chip" decision is still not taken and is one signal louder again.

---

## §181 — THE HEALTH SIGNAL WHOSE WORDS NAMED THE ONE STATE NOTHING CAN PRODUCE

**Two strings and one test file. Read it before editing `healthLabels` or `healthHelp`.**

- **`ORPHANED` has a writer on the server as of §181** — the last of the ten — and the words here
  were measured against the derivation for the first time. Its predicate has three terms (no
  membership, membership `REMOVED`, account `DEACTIVATED`) and both strings named **only the third**,
  which is the one no deployment can currently reach: `AppUser.deactivate` has four callers and all
  four are tests. The state a deployment *does* reach — an offboarded colleague — had no words at
  all.
- **`'Owner deactivated'` → `'Owner has left'`**, and the tooltip now says *no longer works here*
  plus the action (reassign). Named by the answer all three terms share rather than by any one
  mechanism, which is also what keeps it true when the account screen ships.
- **This is the THIRD tooltip in `healthHelp` corrected by the slice that gave its signal a
  derivation** — after §158's `CONDITION_DRIFT` and §178's `OUTDATED_SPEC_CHANGED`. The pattern is
  now three for three: **copy for a signal nothing can raise is copy nobody checks**, and it reads as
  a perfectly sensible sentence until the day it starts appearing. If you add a signal's words before
  its derivation, assume they are wrong.
- **`HealthChip.test.tsx` pins the tooltip as an ABSENCE as well as a presence** — it asserts
  "deactivated" is *not* in the sentence, because that word is exactly what a later tidy-up reaches
  for (it is what the enum name suggests) and re-adding it would satisfy a `stringContaining` check.

---

## §180 — NOTHING ON THE DASHBOARD IS LABELLED OR COLOURED BY THE SERVER ANY MORE

**Read this before touching `features/dashboard/DashboardPage.tsx`, `lib/labels.ts` or
`components/StatusChip.tsx`.**

- **`DashboardTile` HAS NO `label`, AND A SEGMENT HAS NEITHER `label` NOR `color`.** `TileRow` takes a
  `labels` catalogue prop — `lifecycleLabels` for "Authoring status", `healthLabels` for "Needs
  attention" — and the bars read `lifecycleLabels`/`lifecycleVar` off `segment.key`. The server used
  to send all three, and **six of the ten health labels disagreed with ours**, so the tile "Outdated —
  spec changed" drilled into a grid saying "Linked spec changed". One navigation, two vocabularies.
- **`labelForKey(catalogue, key)` in `lib/labels.ts` is ONLY for the two `string`-typed dashboard
  keys.** Every other caller indexes its map directly and must keep doing so — routing a typed
  `HealthSignal` through it trades a compile error for a silent fallback, which is the one thing
  those maps exist to prevent.
- **`statusVar` and `lifecycleVar` LIVE IN `lib/statusColors.ts` NOW**, not in `StatusChip.tsx`.
  Exporting them from a component file trips oxlint's `only-export-components` and the gate is zero
  warnings. `lifecycleVar` was a silent second copy of a mapping the server composed by string
  concatenation, with **no test on either side**.
- **`DashboardBreakdown.label` STAYS and is not the same kind of thing** — a classification path or a
  person's display name in this company. Data, not a rendering of a closed set. Do not "finish the
  job" by removing it; `§180 TL-M3` is the mutation that says so.
- **An unknown tile key renders as the key and still drills**, deliberately (`TileLabels.test.tsx`).
  A blank tile means `labelForKey` fell off that path.

---

## §179 — THE CHIP NO LONGER DECIDES HOW LOUD IT IS

**Read this before touching `components/StatusChip.tsx`'s health half, `CaseGrid`'s Health cell, or
anything that renders a `HealthSignal`.**

- **`HealthChip` takes `{signal, severity}` and both come off ONE `RaisedSignal`.** The wire carries
  the pair (`TestCaseSummary.healthSignals` is `RaisedSignal[]`), and `severityColors` maps the four
  severities to colours — `satisfies Record<HealthSignalSeverity, string>`, so a new severity is a
  compile error where a new *signal* used to be silently absent from a list.
- **`SEVERE_SIGNALS` IS DELETED. Do not reintroduce a table keyed on `HealthSignal`.** It was a hand
  copy of `HealthSignal.Severity` that had drifted about two of the ten members (it carried
  `FAILING_REPEATEDLY`, lacked `CONDITION_DRIFT`), so one case read red on a dashboard tile and amber
  on its own chip. Anything that needs to know how bad a signal is reads the wire value.
- **THE SERVER SENDS THEM WORST FIRST. DO NOT SORT.** `CaseGrid` renders `healthSignals[0]` and hides
  the rest behind `+n`; a client-side sort would be the second copy of the ranking this slice deleted.
  Before §179 the order was the enum's declaration order, so a case that was both unlinked and failing
  showed the missing link and hid the failure — the defect §178 created by giving `UNLINKED` a writer.
- **The `+n` tooltip shows LABELS.** It printed raw enum codes until §179, invisible unless a case
  raises two signals. `lib/labels.ts` owns every word a reader sees.
- **`healthLabels` STAYS on this side** and is deliberately not on the wire: the filter bar needs a
  label for every member of the enum, including `INCOMPLETE`, which the server can never send.
  ~~But **the dashboard's tiles render the SERVER's labels (`tile.label`) and six of the ten disagree
  with `healthLabels`**~~ → **closed in §180 — see the entry above this one.**
- **INFO renders in the same amber as WARNING**, which since §178 means most rows carry an amber chip.
  Quietening it is a reading-plan decision, pinned by a test so it has to be taken on purpose.

---

## §178 — TWO HEALTH CHIPS START APPEARING, AND `SEVERE_SIGNALS` DISAGREES WITH THE SERVER

**Read this before editing `components/StatusChip.tsx` — both halves of it.**

- **`OUTDATED_SPEC_CHANGED` and `UNLINKED` render for the first time** (the server derives them as of
  §178). No component changed: the chip, the facet and the dashboard tile were already generic over
  the enum, which is the third time that prediction has paid.
- **`healthHelp.OUTDATED_SPEC_CHANGED` was rewritten and is pinned by a test.** It said *"the
  documentation block this case **verifies** has changed since the case was last **edited**"* and every
  clause was wrong: any relationship counts, the comparison is the **page's** newest version (`V46`
  §4), and the case side is its newest **version**, not `updated_at`. The regression is a tidy rewrite
  toward the wording the enum's name suggests — §158's finding on `CONDITION_DRIFT`, one signal later,
  which is why this one is asserted in `HealthChip.test.tsx`.
- **`UNLINKED`'s sentence is deliberately unchanged** and must keep matching `CaseDetailPage`'s empty
  Traceability copy: the chip and the panel are two renderings of **one row set** (`case_doc_link`), so
  a case whose only link points into a deleted page says "not linked" in both places. Changing one and
  not the other is the finding.
- ~~**`SEVERE_SIGNALS` IS A HAND-KEPT COPY OF THE SERVER'S SEVERITY AND TWO MEMBERS DISAGREE**~~ →
  **§179 deleted the list; severity is on the wire.** See the §179 entry above.
- **Expect the Unlinked tile to be large** — most of a young project. That is the reading plan §6o
  designed (`Unlinked 156` beside `Approved 891`), and the severity is INFO so the panel stays
  readable.

---

## §177 — THE RUN'S GIT COORDINATE, AND WHY ITS FORMATTER LIVES WITH THE LINK'S

**`describeRunCode` is in `lib/links.ts`, not in `RunDetailPage` and not in `format.ts`.** Read this
before adding anything that renders a commit sha.

- **`at <sha>` is `describeLinkTarget`'s wording and the two must not fork.** A case's Traceability
  panel names a pinned file and the run that tested that case names a pinned build — the same kind of
  fact on adjacent screens. That shared convention is why the function is filed with the link's rather
  than with the numeric formatters.
- **It returns `null` when the run records neither**, so the caller renders *nothing*. Most runs are
  manual passes in projects that record no build, and a labelled empty field is a screen teaching that
  something is missing (§157). Either half alone is a legitimate state: `main`, `at 4f9c2b1`.
- **`RunDetailPage`'s line is a SIBLING of the release line, not nested inside it.** `buildNumber` is
  nested under `releaseVersion &&` — an existing shape worth not copying, since a run with a build and
  no release renders neither. A CI run knows its commit whether or not anybody set a release.
- **`RunCreateDialog` checks LENGTH ONLY, never the sha's shape.** The 7-to-40-hex rule is the
  server's alone, deliberately: a client copy would be a third declaration of it (the backend already
  pins its two against each other). So the server's field error is the only way the caller learns the
  bound — which is why `applyServerError` must keep `commitSha` and `branch` in `FIELDS`, or the
  message lands in the root alert with no control under it.
- **The run LIST renders neither**, though the payload carries them (`§177 GT-5`). Not an oversight.
- Four run fixtures across `*.test.tsx` gained `commitSha` / `branch`; the contract marks both
  **required-and-nullable**, so a new `TestRun` fixture that omits them is a compile error.

## §175 — `TeamRef` IS ONE GENERATED TYPE NOW (no frontend source changed)

**Nothing in `src/` moved and both `schema.d.ts` copies did.** The backend collapsed three
declarations of the team shape into one; the frontend consequence is entirely in the generated
types, and it typechecks because the payloads are unchanged.

- `RoleAssignment.team` was an inline `{id, name}` object and is now
  `components["schemas"]["TeamRef"] | null` — **same keys, same values, same nullability**. The
  admin role-assignment list is the one screen reading it (`§175 TR-1` in the browser queue).
- `CurrentUser.teams` is `TeamRef[]` and **`isLead` is gone from it**. Safe because nothing read it:
  `src/`'s only `isLead` is a local in `TeamMembersDialog` derived from a team's own `leads`.
- **`CurrentUser.teams` is read by nothing at all**, which this slice found and did not close. The
  session stores the whole `CurrentUser` in `localStorage` (`AuthContext`) and no component touches
  `.teams`; `TeamBoardPage` and the admin screens go to `GET /teams` instead — which a non-admin
  cannot call. If a "my teams" affordance is ever wanted, the data is already on the session.

## §174 — THE CLAIM'S CONTROL, AND WHY IT IS A BUTTON

**A link's `relationship` is corrected in place, on both panels, through one
`components/LinkRelationshipControl.tsx`.** Read this before using a MUI `Chip` as a gated control
anywhere.

- **A MUI `Chip` CANNOT CARRY A PERMISSION GATE.** It renders a `div`; `disabled` becomes a class
  plus `pointer-events: none`. It looks disabled, exposes no disabled state to a screen reader, and
  is indistinguishable from an enabled control to anything that asks the DOM — `toBeDisabled()`
  passes against an enabled one. This control was a Chip first and the gate on it was decorative.
  It is a `Button` now. **`component="button"` is not enough** — it fixes the semantics and keeps
  the pointer-events quirk, which is how this surfaced: the *enabled* control could not be clicked.
- **The no-op is sent nowhere.** Choosing the value a link already has fires no request: the server
  answers 200 and writes nothing, which is right of the server and pointless from here, because the
  round trip still invalidates a page.
- **On the case panel a `DOC_BLOCK` link's control is disabled, not absent** — that claim is
  corrected on the documentation page, the same split that gives a block link no Remove there.
- **The hooks inherit §172/§173's opposite cache rules unchanged**: `useRelabelBlockLink`
  invalidates, `useRelabelCaseExternalLink` patches and does not.
- **A test that captures a node and re-asserts on the reference will hang on a gated control.** It
  renders immediately (disabled, `useCan` is fail-closed while pending) and React replaces the node
  when the answer lands, so the captured element is detached and stays disabled for ever. Re-query
  inside the `waitFor`.

## §173 — ONE CONFIRMATION, RAISED BY BOTH LINK SCREENS

**Withdrawing a `doc_link` confirms, at both ends, through one component**
(`components/UnlinkDialog.tsx`). Read this before adding a third link surface or touching either
Remove.

- **`UnlinkDialog` is shared on purpose and the sharing is the fix.** A link is withdrawn from
  `CaseDetailPage`'s Traceability panel and from `DocsPage`'s block row, under two permission keys
  through two hooks — one act, and `ui-rules` §6 forbids it having two behaviours depending on which
  screen reached it. Until §173 neither end confirmed; §172 matched §169 deliberately rather than
  spot-fixing one side. A third surface now inherits the behaviour by construction.
- **Exactly two things are the caller's**: the **named object** (the case end passes
  `describeLinkTarget`, the block end passes `sourceKey — sourceTitle`) and the **consequence
  sentence**, which differs because the block end recounts a coverage marker on the screen the reader
  is looking at and the case end changes nothing but the record. Title, button label, danger colour,
  Cancel, reset-on-open and error-stays-on-the-dialog are shared and must stay shared.
- **`lib/links.ts`'s `describeLinkTarget` is the TEXT form of `DocLinkTarget`'s markup**, and it
  exists because a link that navigates away does not belong inside a confirmation. It is a **second
  implementation of one rule** — switch on `targetKind`, never on which field is populated (§169's
  seventy-section defect) — so `links.test.ts` feeds an `EXTERNAL_URL` row **with git fields
  populated**, which is the only assertion that can tell the two implementations apart.
- **The state is the link, never a boolean.** Both lists render one Remove per row; a boolean would
  confirm "a link" and withdraw whichever was clicked last.
- Test-id shape: `<Panel>.Unlink`, `.Unlink.Target`, `.Unlink.Confirm`, `.Unlink.Cancel` — the
  caller passes the prefix, the dialog composes the suffixes.

## §172 — THE BLOCK-LINK SCREEN, AND WHY THIS HALF *DOES* INVALIDATE

**A case or a suite is linked to one documentation block from the documentation page**
(`DocsPage` → `BlockLinks` → `LinkBlockDialog`). Read this before touching that page, the coverage
margin, or anything that reasons about which end of a `doc_link` is being written.

- **`useLinkBlock` and `useUnlinkBlock` had NO CALLER anywhere in `src/`** until this slice — since
  §97. The endpoint, the read model, `CoverageMarker` and the case-end panel were all built, and
  the one screen that could create a link from the documentation side was never written, so every
  block on every page read *"Not tested"* with no affordance to say otherwise. §169 recorded it;
  this closed it. Grep for hooks with no caller before assuming a capability is reachable.
- **This half invalidates and §169's half must not**, and the difference is the read model, not
  taste: `DocBlock.links` is assembled off `doc_link` inside the same GET (`DocPageService` →
  `BlockLinks.forPage`), so invalidating the page returns the row that was just written.
  `TestCase.relatedDocuments` is served from `case_doc_link`, a projection an outbox event refreshes
  up to a minute later, so invalidating there **erases** the link the server just created. Two hooks
  over one table, opposite cache rules. `invalidatePageEverywhere` also hits the plan key by prefix,
  because a page reached as a plan's document must not go stale behind a link written from the other.
- **The links list is below the block body; the coverage marker stays in the 132px margin.** One is
  *who claims this* (present, possibly empty), the other is *how those claims are doing* (null when
  nobody claims it) — the same split the two fields make on the wire.
- **The Link button is offered on a heading too**, though a heading gets no coverage marker. The
  server accepts a link to any block; withholding the control on the client's own authority is the
  client holding a second, staler copy of the rule, and it would strand an existing heading link
  with no way to remove it.
- **`sourceType` comes from the toggle, never from which picker was touched.** Pick a case, change
  your mind, pick a suite: an inferring form would send `TEST_CASE` with a suite's id — a real row
  of the wrong table, so the server's project check passes and the link points at nothing anyone
  chose. `BlockLinks.test.tsx` pins exactly that sequence.
- **The case picker is `BindTestDialog`'s server-side type-ahead** (open on the first twenty,
  `filterOptions={(all) => all}` so the answer is never re-filtered client-side); the suite picker
  is a plain select, because a project has thousands of the first and a handful of the second.
- **Two test-id prefixes one character apart were renamed away**: the list is `Docs.Block.Links.*`
  and the dialog is `Docs.Block.LinkDialog.*`, not `Docs.Block.Link.*`.
- **`linkSourceTypeLabels`** (`lib/labels.ts`) is the shared map for `BlockLink.sourceType`, keyed
  off the contract's two-value enum rather than the column's four.

## §169 — THE TRACEABILITY PANEL'S OTHER TWO BRANCHES, AND THE HOOK THAT MUST NOT INVALIDATE

**A case can be linked to code or to a URL from the SPA** (`CaseDetailPage` → `DocLinksPanel` →
`ExternalLinkDialog`). Read this before touching that panel, `lib/labels.ts`'s link vocabulary, or
any write whose read model is a projection.

- **`useLinkCaseExternally` and `useUnlinkCaseExternal` PATCH THE CACHE AND DO NOT INVALIDATE**, and
  that is the opposite of every other write in `api/queries/`. `relatedDocuments` is served from
  `case_doc_link`, refreshed on an outbox event up to a minute later, so `invalidateQueries` refetches
  a projection that has not moved and **erases the link the server just created** — a 201 that reads
  as a failure. What is inserted is the create's own answer re-spelled into `DocLink` (`asDocLink`),
  not an optimistic guess: the mirror's external arm copies those columns verbatim and stores
  `stale: false`. **Found by a test, not by reasoning** — `DocLinksPanel.test.tsx`'s
  *"shows the new link straight away"* went red with both the patch and the invalidate in place.
- **`DocLinkTarget` switches on `targetKind` and never on which field is populated.** A row is one
  kind and the other kind's properties are null. The old code branched on `link.externalRepo` and
  rendered a URL link as a blank line and a git link with its commit demoted to a caption — two dead
  branches that typechecked for seventy sections, §168's `CoverageMarker` one panel over.
- **A git link is ONE identifier**: `repo · path:40–58 at 4f9c2b1`, in one element. The pin is what
  makes the range mean anything six months later (#26); a caption reads as a footnote. A single-line
  range renders as one number, and a whole-file link renders no range at all.
- **`rel="noopener"` on the URL anchor is not optional.** The `href` is a value somebody typed into a
  form, and without it the opened page can navigate this tab through `window.opener`. The server's
  `http`/`https`-only check is the other half of the same gate.
- **Remove appears on external links only.** A block link is withdrawn on the documentation page
  under `doc:edit`; no endpoint withdraws one from the case end, and a control that would fail is
  worse than none. The Link button is **disabled, not hidden** (§31) and gates on `case:edit`.
- **`linkRelationshipLabels` / `linkRelationshipHelp` are in `lib/labels.ts`** — the panel used to
  lower-case the wire value inline. The dialog **offers** the relationship rather than sending the
  server's default, because a form that always sent `VERIFIES` makes the other two unreachable.
- **`useLinkBlock` / `useUnlinkBlock` STILL HAVE NO CALLER ANYWHERE IN `src/`.** The documentation
  page renders a block's links and offers no way to create or remove one — the only way to make a
  `DOC_BLOCK` link is the API. Recorded in the browser queue; §169 gave the *case* end a screen and
  left the block end where it was.

---

## §168 — A COMPONENT THAT HAD RENDERED AGAINST NOTHING SINCE M0 CAME ALIVE

**`CoverageMarker` in `DocsPage.tsx` now receives data.** It was complete — chip, colours, tooltip,
a `RouterLink` into the filtered case list — and `DocBlock.coverage` was served by nothing for 168
sections, so **none of it had ever run against a real response**. §168 served the field from the
backend; one line changed here.

- **The one line: `if (!coverage)` and `if (linkedCases === 0)` are now ONE branch.** The server
  sends `coverage: null` for a block nothing claims to verify and **never a row of zeros**, so the
  zero arm — which carried the "Not tested" chip — was unreachable while the null arm drew an *empty
  margin*. The marker was therefore invisible in exactly the case plan §6i built it for: the
  statement nobody has linked. If you are tempted to restore the two-branch shape, the server's
  contract is what makes it dead code.
- **A heading gets no marker, and that is this file's decision rather than the server's.** The server
  will happily send a marker for a linked heading; `DocBlockRow` declines to render one because a
  heading is structure, not a requirement. Keep the two facts distinct — the fixture in
  `DocPageEditorPage.test.tsx` now carries `coverage: null` on its heading for exactly this reason.
- **`links` and `coverage` are `required` on `DocBlock` in both `schema.d.ts` copies** (required *and*
  nullable — the server always emits both keys). This turned a stale fixture into a `tsc` error, the
  §167 `caseBody()` mechanism a second time: `DocPageEditorPage.test.tsx`'s two `DocBlock` literals
  gained both keys.
- **The counts are the server's arithmetic and must not be recomputed here.**
  `passed + failed + notRun === linkedCases` always; `notRun` is the remainder over six status kinds,
  not "never executed". A tooltip or badge that derives a fourth number client-side would be a second
  implementation of a roll-up the backend deliberately owns.
- **Same refresh lag as §166/§167** — the marker reads `case_health`, so a result recorded seconds ago
  legitimately has not reached it. No optimistic patch, no refetch loop; `§168 CV-1`…`CV-5` are in
  the browser queue, lane A1.

## §166 — ONE OF §165'S TWO SCREENS IS FIXED, AND STILL NO FRONTEND SOURCE MOVED

**`CaseGrid`'s Last result column and `CaseDetailPage`'s result chip now show real values.** No
component, no fixture and no test changed here: `row.lastResult?.statusKind ? … : 'Never run'` was
right the whole time and the server started sending the key (§166, a backend read model on
`case_health` fed by the `execution.results_changed` outbox event). `schema.d.ts` changes by one
character in both copies — `lastResult?:` → `lastResult:`, i.e. **required and nullable**, because
the server always emits the key.

- **"Never run" is now a real state and also a TIMING state.** The value is a read model refreshed
  on an event and on an hourly floor, so for a short window after a tester records a result the
  column legitimately still says "Never run". **Do not treat that as a bug and do not add a
  client-side workaround** — no optimistic patch, no refetch loop. `§166 LR-1` in the browser queue
  exists to measure the window, and it must be run before `§165 RC-1`.
- **The status is a `StatusKind`, never the project's own status name.** A project may rename
  "Blocked" to "Environment down"; the wire carries `BLOCKED`. `StatusDot`/`StatusChip` + `labels.ts`
  already handle this correctly — the point is that a future change must not start reading a name
  off this object, because there is none.
- **`lastResult` is `sortable: false` in `CaseGrid` and the reason written down is now wrong.**
  `CaseSort`'s note grouped it with `owner` ("would need `app_user` inside this module's ORDER BY"),
  which was never this field's obstacle and is certainly not one now that it is a column on a table
  the module owns. Nobody has asked for the sort; the *argument* against it has simply expired.

## §165 — TWO SCREENS RENDER A CONFIDENT FALSEHOOD, AND NEITHER IS A FRONTEND BUG

No frontend source moved. `schema.d.ts` gains one property (`TestPlanSummary.keySeq`, which nothing
here reads). **The entry is what §165 measured about two screens this codebase owns**, because both
look correct and are not, and a browser pass that does not know will pass them.

- ~~**`CaseGrid`'s "Last result" column reads "Never run" for EVERY case in the product** — executed
  or not.~~ → **CLOSED BY §166**, and closed exactly as this bullet predicted: a backend read model
  fed by events, **not** a frontend change. Kept because "the fallback is right and the data is
  absent" is the diagnosis worth being able to reach again.
- **`CaseDetailPage`'s `DocLinksPanel` is empty for every case, including cases a doc block links
  to.** `data.relatedDocuments ?? []`, and the server omits the key for the same reason. Links have
  been creatable since `POST …/blocks/{blockId}/links`, so the link is real and visible **from the
  documentation side only**.
- **Do not "fix" either in the SPA.** There is nothing to fix here: no fallback, no default, no
  optional chain is wrong. Both fields are pinned in
  `ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION` in both directions, so the day a backend
  slice serves one, the build fails until its entry goes — and `§165 RC-1` / `§165 RC-2` in the
  browser queue become live assertions rather than descriptions.
- **The general lesson is the one worth keeping**: an empty state is indistinguishable, on screen,
  from a value the server never sends. When a browser row is about a field, read the **network tab**
  for the key, not the widget for the text.

## §161 — NO FRONTEND SOURCE MOVED, AND THAT IS THE ENTRY

`schema.d.ts` was regenerated and its diff is **comments only — no generated type changed**
(verified by `git diff` in the slice). The contract change was one `description` block on
`HealthSignal`. Recorded here anyway, because a regenerated `schema.d.ts` in a commit normally means
a type moved, and the next reader should not go looking.

- **`INCOMPLETE` was never a frontend gap.** `lib/labels.ts` has read *"Missing required fields"*
  and `StatusChip` has had its sentence since 2026-07-30. The server has no constant for it and
  still does not, so **no fixture can produce the chip** — do not write a test or a browser row
  that expects one. §161's whole finding is that a backend comment claimed the opposite.
- **`openapi.yaml`'s `HealthSignal` no longer lists which signals have writers.** The list was stale
  for two slices and said `STALE_APPROVAL` and `CONDITION_DRIFT` are never sent while they were
  being sent. A client's rule is unchanged and is the only thing to code against: **an absent signal
  means "nobody has looked", not "not raised"** — `DashboardSummary.healthComputedAt` is what tells
  the two apart.

---

## §160 — THE POSTED CI REPORT IS A DOWNLOAD, AND IT KEYS ON `reportUrl` ALONE

`features/ingestion/DownloadRawReportButton.tsx` is the new component, rendered in
`IngestionJobPage`'s **header** `meta` stack.

- **KEY THE CONTROL ON `reportUrl`, NEVER ON `status` OR `format`.** It is `required` and nullable,
  and null for every job posted before uploads were kept — which no migration can repair. A control
  keyed on `status: COMPLETED` renders a button that 404s on every historical row.
- **IN THE HEADER, NOT IN THE PANEL.** The panel is a `DataRegion` that renders nothing when
  `unmatched` is empty, and a report where *everything* matched is exactly when somebody wants to
  check what was posted — a test that never ran is absent from `unmatched` and from the run, and
  looks identical to one never written.
- **`fetchArtifact` + `saveBlob`, never `<a href>` and never `api.get`.** The session is a bearer
  token, so an anchor sends no credential and 401s while rendering as a working link; `reportUrl`
  already carries `/api/v1` because the server composed it, so `api.get` yields `/api/v1/api/v1/…`.
  `DownloadReportButton`'s two reasons exactly — `api/client.ts` has the account.
- **A sibling of `DownloadReportButton`, not a reuse.** That one names a format a person chose; this
  one a dialect CI chose, with a different fallback filename. What `ui-rules` §8 forbids duplicating
  is the *authenticated download*, and both share it.
- The server sends `Content-Disposition: inline` and a filename carrying the dialect
  (`ingestion-{id}-junit.xml`); the media type cannot carry it, because `application/xml` is true of
  both JUnit and TestNG.

## §159 — `Project.archived` IS READ AT LAST, AND A GATE VERDICT REPLACES THE CONTROL'S SUBTREE

The field was `required` in the contract from M0 and **no file here read it until §159**, because
nothing could set it. Two surfaces now do: `ProjectsPage`'s card chip and `DashboardPage`'s header
chip + `ArchiveButton`.

- **DO NOT FILTER ARCHIVED PROJECTS OUT OF THE PICKER.** The server returns them deliberately; the
  card is the only route to the page that can restore one.
- **`useSetProjectArchived` sends the target state**, never `!archived` re-derived at click time —
  the endpoint is idempotent so a stale render is a no-op, and a toggle throws that away.
- **It writes the detail cache from the mutation response.** Legal *here only*: the response comes
  from the server's own assembler, the same one `GET /projects/{id}` uses, and a backend test pins
  them together. Everywhere else in `queries/projects.ts` the rule is still invalidate-don't-assemble.
- **TESTING A `PermissionGate`D CONTROL: RE-QUERY THE NODE INSIDE THE WAIT.** The denied branch wraps
  the child in a tooltip anchor, so when the verdict lands the subtree is replaced and any element
  captured earlier is **detached and disabled for ever**. A test holding it across `waitFor` times
  out blaming the permission. `ArchiveProject.test.tsx` says so at the line.
- **One button, labelled for the move available from the current state** (Archive ↔ Restore), and
  **no confirmation dialog** — the action destroys nothing and is undone by the button that replaces
  it.

## §149 — AN `errors` VALUE IS THE SERVER'S SENTENCE; NEVER COMPOSE WORDS AROUND IT

`Problem.errors` is `{name: [messages]}` and **every value is a complete sentence, rendered as it
arrives.** Two dialogs read one directly rather than through a form's field mapping, and they must
agree: `DeleteTeamDialog` (`grants`, `children`) and `RetireValueDialog` in
`ClassificationSettingsPage` (`children`).

- **`RetireValueDialog` used to compose the sentence around a bare count** the server sent as
  `children: "3"`. It looked right on screen and was meaningless at `@tcms/cli` and `@tcms/mcp`,
  which print `errors` values verbatim. The server sends the sentence now; the page prints it.
- **Its test supplied `{ children: ['2'] }` and asserted the page showed `2`** — a fixture inventing
  the payload it checks, §148's `describeError` shape. When a test's fixture is the only statement
  of a wire shape, it cannot disagree with the server.
- The composition also carried a plural bug (`1 value sit under this one`) that only existed because
  the page was writing the sentence.

## §148 — `describeError` DOES NOT READ A DETAIL ON A 404, BECAUSE THERE IS NEVER ONE

**Every 404 the API writes is bodiless** (94 `notFound().build()` sites; `openapi.yaml` declared a
`Problem` here until §148 and was wrong). So `ApiError.detail` on a 404 is always `client.ts`'s
floor — `res.statusText`, i.e. the word "Not Found", or `The request failed (HTTP 404).`

- **The 404 branch returns fixed copy now**: *"It may have been deleted, or it may be in a project
  you cannot open."* It used to return `error.detail`, under a comment claiming a 404 detail "names
  the missing thing", so every 404 panel in the SPA read **"Not found — Not Found"**. Its unit test
  passed by supplying a detail no 404 has ever carried and asserting it back.
- **`TeamBoardPage`'s per-screen workaround stays** and its header is the accurate account of why
  it exists. It is not the general fix; this is.
- **The 401 branch never read the wire** and is unchanged — the API's 401 is bodiless too, and the
  copy is fixed text.
- **The `statusText` floor in `client.ts` is load-bearing, not a smell.** Without it `detail` is
  `''` and every consumer that falls back to it renders a blank line. `packages/client` has the
  identical floor, arrived at independently.
- Both `schema.d.ts` copies regenerated: 401 and 404 now generate `content?: never`.

## §144 — `TestCaseInput` REQUIRES FOUR FIELDS, AND `schema.d.ts` WAS REGENERATED

**No frontend code changed. One test fixture did, and it is worth knowing why.**

`docs/specs/openapi.yaml` said `TestCaseInput.required: [title]` from M0 while the server required
**`title`, `stepFormat`, `priority` and `automationStatus`** on create and edit alike. §144 fixed
the contract and re-ran **both** `gen:api` runs. The only thing in this stack that objected was
`src/features/proposals/ProposalPage.test.tsx` — its `PENDING` fixture carried `title` and
`objective` and nothing else, so **the SPA's own test asserted the shape of a proposal that could
only ever be Rejected**.

- **The editors were already correct and that is the point.** `CaseEditorPage` submits every field
  it renders on every save, which is why `PATCH …/cases/{id}` — whose body is the **whole editable
  set**, not a delta — has never been wrong for this client. It took a second client (the CLI, the
  MCP server) to find out, and both of those now do a read-modify-write via `@tcms/client`'s
  `core/rewrite.ts`.
- **If you write a `TestCaseInput` literal in a test, it needs the four.** `tsc` says so now.
- **`A4-11`/`A4-12`** in the browser queue are the file → PENDING → **Accept** path completing,
  which it never has: the accept 400ed on fields that are not on the proposal and cannot be edited
  there. §138's screen was right; what waited behind it could not be accepted.

## §138 — A VERDICT MAY NEVER BE RENDERED WITHOUT ITS SOURCE

`src/features/proposals/` — the review queue (`ProposalsPage`) and the decision screen
(`ProposalPage`), at **`/projects/:projectId/generated-cases`**. The route says
`generated-cases` where the API says `ai-proposals`: URLs are read by people, and `ai_proposal` is
the table's name.

- **`VerdictChip` takes BOTH props and there is no way to render one without the other.** That is
  the enforcement, not a convention: §6w.1a says an agent's claim is recorded, shown, attributed and
  **never counted as verified**, and a chip reading "Grounded" beside no provenance is a claim
  promoted to a finding on the one screen where people skim. The source is rendered as **text**, not
  only as a tooltip — a tooltip is invisible to a skimmer, who is exactly the reader at risk.
- **`proposalVerdictColors` HAS NO GREEN.** `--color-passed` is what this product uses for something
  it verified; a proposal's verdict is somebody else's critic's opinion. Adding green here makes the
  two look like the same kind of statement.
- **A 400 FROM ACCEPT IS THE DESIGNED OUTCOME, NOT AN ERROR.** The payload was stored unvalidated,
  so accept is where "is this a legal case" is finally asked, against the project as it is *now*.
  Both mutations carry `meta: { handled: true }` and `ProposalPage` renders the field errors **in
  full, in the panel, with Reject still enabled beside them** — the reviewer's next move. A toast
  here would say "something went wrong" over a message that says exactly what is wrong.
- **`testCase: null` is a real state**, not a failure: a payload stored before a field left the
  contract. The proposal still renders and is still rejectable, which is the only decision left.
- **The SPA cannot file a proposal, and that is the feature.** Filing is an agent's arm over MCP or
  the CLI. There is also **no client-side gate for the credential refusal** — this app has no
  token-authenticated session, so the caller the rule applies to cannot exist here.
- **Test trap, and it cost a red run:** `findByTestId` resolves the moment a gated control exists,
  and `PermissionGate` renders it **disabled** while `/me/permissions` is in flight — then swaps it
  for a **different node** when the answer lands. A captured reference stays disabled for ever.
  `ProposalPage.test.tsx`'s `enabled()` helper re-queries inside `waitFor`; copy it rather than
  re-deriving it.
- **An inline `?status=` in a query hook fails `contractOperations.test.ts`**, which reads the
  literal path out of the source. Use `qs({ … })`.

## §189 — TWO CONDITION IDS ON THE `packages/` SURFACE, AND THEY ARE NOT INTERCHANGEABLE

**Read this before touching `case edit --decline`, `suite edit --precondition`, `case show`'s or
`suite show`'s condition tables, or `tcms_write_case` / `tcms_write_suite`'s condition arguments.
Nothing in `frontend/src` changed.**

- **THE WHOLE SLICE IS KEEPING TWO UUIDS APART.** A **library** id (`condition.id`, served as
  `CaseConditionRef.conditionId`) is what a *suite* attaches; an **attachment** id
  (`suite_condition.id`, served as `CaseConditionRef.id`) is what a *case* declines. Both are
  UUIDs, both appear one column apart in `tcms suite show`'s table, and swapping them types
  everywhere. `--precondition` takes the first, `--decline` the second, and the only thing that
  refuses a mix-up is the server.
- **`caseInputFrom` and `suiteInputFrom` still drop the condition lists, and §189 is the slice that
  revisited that and kept it.** `core.test.ts`'s `excluded` list predicted a reversal for
  `declinedConditionIds` ("the exclusion to revisit first") and the answer is no: a **projection**
  says what an unchanged save sends, and for a list that is nothing; a **flag** is a delta and says
  what the caller asked for. Adding the echo makes every `--title` edit assert the complete set of
  an author's opt-outs.
- **Four complete-set flags, one spelling.** `--tag`, `--tag-rule`, `--decline`, `--precondition`
  and `--postcondition` all go through `completeSet` (renamed from `tagsFrom`, since two of its
  four callers are not tags): absent leaves the list alone, `--flag ''` empties it. On the MCP side
  `[]` is the clearing form, because JSON can type an empty array and a shell cannot.
- **The MCP suite arm takes BARE ids and builds the `CaseConditionInput` wrapper itself.** The
  object has exactly one fillable field — `inlineBody` exists only to be refused (§95) and position
  is the array's order — so asking a model for the shape buys a shape to get wrong.
- **`library list` and `suite show` are new read commands and they exist for the writes.**
  `library list` is `tcms_list_library`'s CLI twin (read-only: writing either library costs
  `library:manage`, which this surface deliberately does not hold), and `suite show` is the only
  place the two ids are printed side by side. **A write whose argument cannot be learned from the
  surface offering it is a write nobody can make** — before this, both ids were reachable only
  through `--json`.
- **`case show` prints declined conditions BESIDE the live ones, never merged.** The server serves
  four arrays for that reason: a declined condition is not part of what the case does.
- **Two fixtures were stale and one was wrong about the wire.** `mcp/src/writes.test.ts`'s
  `SUITE_ROW` said `conditions: []` — the single undifferentiated array §186 removed three sections
  earlier — and both case-detail fixtures omitted the declined arrays the contract requires. A stub
  answers 200 to whatever arrives, so a partial fixture agrees with a client that reads nothing.
- **What none of this can check is the id being the right *kind*** (`§189 CN-3`, a recorded hole):
  the stub accepts either, so declining a condition through a mis-wired field would *attach* it and
  every test stays green. That is a live-pass row, not a unit test.

## §137 — THERE IS A THIRD TYPESCRIPT STACK NOW, AND IT IS NOT THIS ONE

`packages/` holds **`@tcms/client`** (the typed API client + command core) and **`@tcms/cli`**, with
its own npm workspace root at `packages/package.json`. **`frontend/` did not change and must not
start importing it.** Read this section before "sharing" anything between the two.

- **The two clients are near-copies on purpose, and the copy is not drift.** `frontend/src/api/client.ts`
  is a *browser* client: `localStorage` through `safeStorage`, the `import.meta.env.DEV` fault seam,
  budgets chosen for a person watching a region spin (3s typeahead). None of that compiles or means
  anything in a CLI process, and `packages/client/tsconfig.json` omits the `DOM` lib deliberately so
  a browser global cannot slip in. What the two share is the **wire format**, which `openapi.yaml`
  owns and both read.
- **`schema.d.ts` now has a twin, and a test compares them byte-for-byte.**
  `packages/client/src/schema.ts` is generated from the same contract by the same
  `openapi-typescript@7`, so the two files are identical. `packages/client/src/schemaFreshness.test.ts`
  fails when they are not — which is the only thing anywhere that catches **one stack regenerating
  after an `openapi.yaml` change and the other not.** So `npm run gen:api` is now TWO commands, one
  per stack, and forgetting either reddens `packages`.
- **CI gained a third job** (`packages` in `ci.yml`): build + lint + test across both workspaces.
  Unlike the backend job it **does** run its tests — ~0.5s of pure Node, no container. It is not in
  the `lab` deploy's `needs`: these packages are published, not deployed.
- **`openapi-fetch` types the paths, so the SPA's two contract checks have no counterpart there.**
  `contractOperations.test.ts` and `contractParameters.test.ts` (~450 lines of AST walking) exist
  because a hand-built template literal can name a path the contract does not declare. In
  `packages/` the path and method are type parameters, so that class of defect is a compile error.
  **This is not an argument for rewriting the SPA's client** — it fetches through TanStack Query
  with per-endpoint budgets and a fault seam, and the checks cover call sites that already exist.

## §133 — FOUR SCREENS READ `GET /roles` AND ONLY THREE MAY FILTER ON `assignable`

`Role` gained **`assignable`** and `Role.name`'s enum gained **`Agent`** (V40 — the ceiling an API
token carries; nobody is ever an Agent). **Test count is 1177 across 90 files**, five added.

- **⚠ THE FILTER IS NOT UNIFORM AND MUST NOT BE MADE SO.** `GrantRoleDialog.grantableRoles`,
  `RoleReference` (both the Teams & roles and the Project access cards) and `ProjectAccessPage`'s
  `isEmpty` predicate all drop `assignable === false`. **`IssueTokenDialog.issuableRoles` does
  not**, and carries a comment refusing the copy: that form is the ONLY place in the product
  `Agent` can be chosen, so adding the term makes the role unobtainable and changes nothing else on
  any screen. `IssueTokenDialog.test.tsx` has the assertion that goes red.
- **`assignable` is a second axis, not a restatement of `scope`.** `Agent` is PROJECT-scope, so
  every existing scope filter passes it through. `RoleReference`'s own javadoc had already made the
  argument — a reference card beside a Grant button that will refuse it is mis-teaching — about
  *scope*, and this is the first role where the two disagree.
- **`agentSelected` is keyed on `role.name === 'Agent'`**, not on an id: seeded ids are not
  something the client holds, and this is affordance — `ApiTokenService` is what refuses a null
  expiry, so being wrong costs a 400.
- **The Expires pre-fill writes LOCAL wall clock and only into a BLANK field.** `datetime-local`
  wants the browser's own clock (`toInstant` converts back on submit, so an ISO string here shows
  a UTC time labelled as the user's), and overwriting a typed date on a re-pick replaces a decision
  with a default. Both have a test.
- **`optionNames` in the admin dialog tests LEAVES THE MUI MENU OPEN**, and an open menu makes the
  rest of the document inert — so a `chooseFrom` after it fails with *"no accessible roles"* on an
  element the dump plainly shows. `await userEvent.keyboard('{Escape}')` between them. This was the
  slice's only red run.

## §129 PHASE 3 — THE THREE CLONE DIALOGS, AND THE FIELD THAT MUST NOT BE PREFILLED

**ADDED SEVEN FILES** — `lib/clone.ts` + `lib/clone.test.ts`, `features/cases/CaseCloneDialog.tsx` +
its test, `features/plans/PlanCloneDialog.tsx` + its test, `features/runs/RunCloneDialog.tsx` + its
test — and touched `api/types.ts`, `api/queries/{cases,plans,runs,index}.ts`,
`features/cases/CaseDetailPage.tsx`, `features/plans/PlanStatisticsPage.tsx`,
`features/runs/RunDetailPage.tsx`. **No contract change** (`schema.d.ts` was regenerated in phases
1–2). **Test count is 1172 across 90 files** — measured on a quiet run; 22 added, so the real
pre-slice baseline was **1150** and the 1144 in the previous header was stale. **Eighth drift.**

- **`lib/clone.ts`'s `copyOf(name, max)` is THE suggestion helper**, and the reason it is shared is
  the arithmetic rather than the words: the ellipsis is a character, so truncating to exactly `max`
  before appending it lands one over the limit the field mirrors. Three limits (case title 300,
  plan and run 200), one function.
- **⚠ A RUN CLONE'S RELEASE AND BUILD MUST NOT BE PREFILLED, AND THE NAME MUST.** Absent means
  *keep the source's* and there is **no way to say "clear it"** — so a prefilled field a user
  empties is a control whose most obvious use is silently discarded (`ui-rules` §3's worst form
  defect), arrived at by copying the helpful behaviour from the field two rows above. They open
  empty with the source's value in the helper text: *"Leave empty to keep 4.3.0."* The inconsistency
  on one dialog is the correct answer.
- **The gate is the create key, never the edit key** — `case:create` · `plan:create` ·
  `run:create` — because a clone creates and changes nothing about the source. The plan's is
  `plan:create` alone though it writes criteria that normally cost `plan:edit`; a **backend** test
  derives that from the seeded catalogue, so do not re-state it here.
- **`RunCloneDialog` reuses `RunCreateDialog`'s §6g shape verbatim**: a 409 is the only thing the
  endpoint answers with that status, so the client holds no copy of the gating rule, and *Clone it
  anyway* is disabled-not-hidden without `case:approve`. The override is **never** inherited from
  the source run.
- **`RunLifecycleActions` is `RunActions` now** and takes the whole `TestRunDetail` rather than a
  status, because the dialog prefills from the source and counts its executions. Clone is offered on
  a **closed** run too, unlike the lifecycle buttons beside it that swap by status.
- **The automation note renders for an `AUTOMATED` source and no other.** Two tests hold the pair;
  an unconditional note passes the first and fails the second.
- **Each dialog navigates to the copy, and NOT with `replace`** — unlike `CaseDeleteDialog`'s, the
  source still exists and Back to it is reasonable.

**§128 ADDED THREE FILES** — `features/cases/useCaseAutosave.ts` + its test, and
`features/cases/casePayload.test.ts` — and changed `api/queries/{cases,keys,index}.ts`,
`api/types.ts`, `features/cases/caseSchema.ts`, `features/cases/CaseEditorPage.tsx` (+ its test).
**Test count is 1144 across 86 files** — measured on a solo run, +39/+2 over §127's measured 1105/84.

> **⚠ §127's RED RUN REPRODUCES, AND §129 PHASE 3 CAPTURED THE NAMES.** Three of five full runs went
> red (1, 11 and 13 failures) and **every failure was a 30–46s TIMEOUT, never an assertion**, always
> in the same five files: `CaseEditorPage`, `DocPageEditorPage`, `PlanCases`, `RunCreateDialog`,
> `ReviewPanel`. All are `userEvent.type` tests typing sentences character by character — the cause
> `vitest.config.ts` already names, whose fix (`paste`) is still not done. Each passes in isolation;
> the green run took 114s against the reds' 204s and 373s. **This is a load-dependent timeout cliff
> in a known set, not an unidentified flake** — and a third raise of `testTimeout` would be the
> third fix of a symptom.

> **⚠ §191 — THE OTHER LOAD-DEPENDENT FAILURE IN THAT FILE IS A DIFFERENT DEFECT, AND IT IS FIXED.**
> The cliff above is a **timeout, never an assertion**. `CaseEditorPage.test.tsx` also failed under
> load with a real **assertion** (`toHaveValue('Refund it twice')` receiving the typed fragment),
> which four sections filed as the same flake and described as *"a half-typed value"*. It was not:
> `StepActionField` debounces the corpus read **250 ms of real time**, so a loaded machine runs
> several debounce rounds per `user.type()`, each a new query key whose `data` is `undefined` until
> it resolves — the option list empties, MUI remounts the rows, and the `<li>` a `findAllByTestId`
> returned a moment earlier is **detached**. Clicking it fires nothing. **Capturing a node from a
> debounce-fed popup and clicking it on the next line is the defect**, wherever it appears: the
> query must live inside the retry (`pickSuggestion` in that file), and the row must be chosen by
> its text, not its index — different rounds hold different rows in different orders, so an index
> silently picks a *different* suggestion. Fixed 2026-08-13 after CI run 31731166477; the timeout
> cliff above is untouched by it and still open.

## §128 — THE AUTOSAVE BUFFER, AND THE GATE THAT HAS TO CLOSE FIRST

- **`useCaseAutosave` (`features/cases/useCaseAutosave.ts`) is the debounce**, 2s, keyed on the
  **serialized payload** rather than on a render or a change count. `save` is held in a ref so the
  effect does not depend on a callback whose identity changes every render — an effect that did
  would restart the timer on each keystroke's re-render and never reach its deadline.
- **`enabled` MUST include "the restore prompt has been answered", and the editor supplies that.**
  Without it the first keystroke buffers the saved case over the draft the editor is about to offer;
  the user is then shown a restore prompt for work that no longer exists, and accepting it hands
  back what they had already saved. Nothing errors, nothing is logged. The gate lives in
  `CaseEditorPage` rather than the hook because a hook that guessed would be a second opinion about
  a sequence only the screen can see. A **failed** draft read opens the gate too, deliberately.
- **`toCasePayload` / `fromCasePayload` live in `caseSchema.ts` and are THE mapping pair.**
  `toCasePayload` moved out of the submit handler when autosave became its second consumer; a second
  hand-written copy is how a field wired into Save and forgotten in the buffer **restores as empty**.
  `fromCasePayload` decodes **defensively** — the buffer is unvalidated by design and may have been
  written by an older build, so nothing in it throws and a wrong-typed field reads as absent. The
  round trip is asserted directly in `casePayload.test.ts`.
- **`reset(values, { keepDefaultValues: true })` on restore, NOT `keepDirty`.** `keepDirty` preserves
  the dirty flag *as it stands*, which on mount is `false` — so a restored draft lands in a form the
  editor considers pristine, with Save disabled reading "Nothing has changed yet" over a form full of
  unsaved work, and the autosave gated off with it.
- **`useCaseDraft` normalises the 204 to `null`.** `api.get` maps 204 to `undefined`, and TanStack
  Query throws *"Query data cannot be undefined"* — so the ordinary "no unsaved work" answer would
  surface as a failed query. `refetchOnWindowFocus` is **off** here and only here: the row's current
  value is on screen in the form, and a refetch landing mid-typing would offer to restore the draft
  the author is currently writing.
- **A failed autosave is a status line and no toast** (`meta.handled`) — the caller is a timer.
- **The create form has no autosave**, asserted: the buffer's key is an FK to `test_case`.

## §127 phase 3 — THE EDITOR COULD AUTHOR THREE OF SIX TYPES, AND ONE FIELD WAS READ-ONLY

- **`DocPageEditorPage` had no control for `table` or `image`**, so the only way to put either on a
  page was a hand-written request.
- **THE LIST DEPTH IS THE WORSE ONE AND IT IS A FIX, NOT A FEATURE.** §93 widened the contract so a
  bullet carries an indent, the reader has rendered that indent ever since, and the Depth control
  was `type === 'heading'` only — so `level` could be **read and never written**. Half a round trip,
  invisible from either end alone.
- **Every type-specific field is derived from the TYPE at submit**, never from what the control last
  held — one rule, three lines (`level`, `rows`, `attachmentId`). Switching a table to a paragraph
  with cells still in form state is a 400 the author cannot see the cause of, on an ordinary act.
- **`TableRowsEditor` is a grid, not a delimited textarea.** The textarea is a quarter of the code
  and is §93's mistake one field over: a cell containing the delimiter splits silently. Rows are
  added/removed whole and columns across **every** row at once, which makes ragged
  *unconstructable* rather than merely refused. The header row's Remove is disabled.
- **`ImageFilePicker` is a picker over the page's existing attachments and deliberately not an
  upload.** The upload lives on the page's own panel, which owns the size limit, the empty-file
  refusal and the 413; a second one here would copy all three and would have to happen *before*
  Save while everything else happens at Save. With no files it says where to go.
- **`TEXT_LABEL`/`TEXT_HINT` are `satisfies Record<blockType, …>`** — `text` means Heading, Bullet,
  Caption or Alt text depending on the type, and a control labelled "Text" gives nobody a reason to
  fill in the one the server refuses when blank.
- **`data-testid` ON A MUI SELECT SITS ON THE HIDDEN `<input>`** (`slotProps.htmlInput`), which
  carries `pointer-events: none` — `userEvent.click(getByTestId(…))` **throws**. The repo's idiom is
  `closest('.MuiInputBase-root')` → `getByRole('combobox')` → the option in the portal
  (`ArtifactDialogs.test.tsx`); `DocPageEditorPage.test.tsx` now has its own `choose()` helper.
  `#confirmed`

> **⚠ THERE WERE TWO `BlockView`s BRANCHING ON `blockType`, AND THEY DISAGREED.** `DocsPage`'s and
> `PlanDocumentPanel`'s: the plan panel indented list bullets and set a monospace face on `code`,
> the documentation page did neither — so **one stored outline read as an outline on one screen and
> as flat prose on the other**, with nothing failing. `components/DocBlockBody.tsx` is the single
> renderer now. The callers keep only the chrome that genuinely differs: `DocsPage` anchors each
> block by id and hangs the coverage marker in the margin, `PlanDocumentPanel` renders link chips.
> **Four of the six types had no branch at all** — `paragraph`, `code`, `table`, `image` all fell
> through to a paragraph, and every one of those *type-checked*, because `text` is a required string
> on every block. That is why it survived from M1.
> **THE `default` ARM IS NOT DEAD CODE.** `blockType` is a generated union so a new member is a
> compile error at every `case` — but the value arriving at runtime comes from a server that may be
> a version ahead of the bundle (`BlockContent` carries `ignoreUnknown` for the mirror reason). A
> test drives it.
> **AN IMAGE IS FETCHED, NEVER `<img src={url}>`** — §126 phase 4's finding, now with its first fix
> in place. `useAttachmentBlob` (`api/queries/documentation.ts`) reads through `fetchArtifact` and
> **caches the `Blob`, never an object URL**: a URL is a lifetime, not a value, and a revoked one
> sitting in the query cache is a broken image on the next mount. The component derives the URL in
> an effect and revokes it on unmount. `staleTime: Infinity` — an attachment is never rewritten,
> only hard-deleted. **`EvidencePanel` STILL HAS THE UNFIXED `<img>`** and is not swept here.
> **AN IMAGE BLOCK GETS BYTES WHERE AN ATTACHMENT ROW DOES NOT**, and that is not a reversal of
> §126 phase 4's no-thumbnail decision. That decision was about fetching *every* file of a page into
> memory on load, unbounded, to decorate a list. Here the picture **is** the content, the count is
> bounded by blocks somebody wrote, and rendering nothing is a missing paragraph rather than a
> plainer row.
> **A TABLE IS A REAL `<table>` WITH `scope="col"` AND A `<caption>`.** The test asserts
> `getByRole('columnheader')` and not the header's text: a `<td>` holding the same word renders
> identically and announces as an ordinary cell, so a text assertion passes against the broken
> version. The caption is `block.text`, which is also what the coverage matrix reads as the block's
> statement.
> **`toHaveStyle({paddingLeft: '48px'})` SILENTLY PASSES NOTHING under MUI v7 + jsdom** — the emitted
> value is `calc(6 * var(--mui-spacing))` and jsdom does not resolve the variable, so the matcher
> compares against an empty string. Assert the `calc()` string; the multiplier is the arithmetic.
> `#confirmed`


**§126 PHASE 4 ADDED FIVE FILES** — `lib/attachments.ts` + `lib/attachments.test.ts`,
`components/{AttachmentList,FileDownloadLink}.tsx` + `components/AttachmentList.test.tsx` — plus
three new test files (`features/docs/DocsPage.test.tsx`, `features/artifacts/ArtifactTable.test.tsx`,
and new blocks in `CaseDetailPage.test.tsx`/`ArtifactDialogs.test.tsx`). It touched
`api/types.ts`, `api/queries/{cases,documentation,index}.ts`,
`features/cases/CaseDetailPage.tsx`, `features/docs/DocsPage.tsx`,
`features/artifacts/{ArtifactDialogs,ArtifactTable}.tsx`. **No contract change** — `schema.d.ts` was
already regenerated by phases 1–3.
**Test count is 1088 across 83 files** — measured. **The 1045 in the previous header was already
wrong before this slice started**: a clean run at `e95ef1a` measured **1055/79**. **Sixth drift of
this figure — read it off a run, never off this file.**

> **⚠ AN `<a href>` AND AN `<img src>` POINTED AT `/api/v1/…` BOTH ANSWER 401, SILENTLY.** The
> session is a bearer token this client sets per request (`SecurityConfiguration` is `STATELESS`,
> no cookie anywhere), so *any* browser-initiated navigation to an API URL is unauthenticated.
> `components/FileDownloadLink.tsx` is the fix and the third call site is what extracted it
> (`DownloadReportButton` was the first, and stays separate — it composes a filename the response
> may not carry). **`EvidencePanel` still has the unfixed version** of both — the anchor *and* the
> thumbnail — which `api/client.ts` has recorded since §89 and this slice did not sweep.
> **THERE IS NO THUMBNAIL AND THAT IS A DECISION.** The server serves case and page attachments
> `inline` and the contract's copy talks about a screenshot rendering beside the steps — but the
> only way to honour it is fetching every image into an object URL on load, unbounded, with no
> thumbnail endpoint. A broken image on every row is worse than none. Recorded in the browser queue
> as an absence with its reason, which also predicts that `M4-7`'s *"rendered thumbnail"* will fail
> live.
> **THE DELETE GATE HAS NO `OWN` ARM AND `EvidencePanel` BESIDE IT DOES.** `case:edit`/`doc:edit`
> gate both writes; copying `evidence:delete`'s authorship arm is the tidy change and it is wrong —
> a case attachment is part of the case *definition* that `case:edit` already owns wholesale, so an
> authorship arm would make removing the screenshot **stricter than removing the step that
> references it**. Two tests exist only to hold that (a colleague's row and an unattributed row are
> both **enabled**), and `CaseDetailPage.test.tsx` proves the *call site* passes `case:edit` by
> asserting Attach enabled and Delete disabled in one render.
> **THE ARTIFACT IS A DIFFERENT SHAPE ON PURPOSE — ONE DIALOG, TWO ENDPOINTS.** A case and a page
> *have* files; a `FILE` artifact **is** one, so there is no list, no attach-afterwards, no `PUT`
> and no detach. `RegisterArtifactDialog`'s `kind` is **wider than `ArtifactInput.kind`** and the
> submit branches before either payload is built, so `ArtifactFileInput` (no `kind`, no `url`, no
> `docPageId`) makes the contradiction a compile error. A second Upload dialog would have been a
> second copy of the scope rules, the category list, the plan-scope qualifier and the 403-as-a-field
> mapping.
> **THE `artifact` PART MUST BE A JSON `Blob`.** `@RequestPart` picks its converter from *that
> part's* `Content-Type`; `form.append('artifact', JSON.stringify(x))` sends `text/plain`, no
> converter matches, and the failure is a 415 about media types nowhere near the field it is about.
> One test reads the part back and asserts `.type === 'application/json'`. `#confirmed`

**§124 ADDED NO FILE AND NO TEST** — 1045/1045 across 79 files, unchanged. It **removed** an enum
member, and the three `satisfies` guards are what found every consumer:

- `lib/labels.ts` — `aggregationLabels` and `aggregationHelp` lost their `BEST` entries. Both are
  `satisfies Record<AggregationPolicy, string>`, so they stopped compiling until edited. **That guard
  is always described in the adding direction and works exactly as well when removing.**
- `features/reports/GenerateReportDialog.tsx` — `POLICIES` is
  `satisfies readonly AggregationPolicy[]`; it is the reason a menu item offering a policy the server
  now refuses could not survive the change. Four values, ordered as the plan page orders them.
- `features/runs/ExecutionGrid.tsx` — **comment only.** The server now also demands `run:execute` on
  the result endpoint, and this `PermissionGate` deliberately does **not** ask for it: the only
  principal the key withholds from is a CI token, which never renders a screen. Revisit if roles
  become authorable (§110's F5).

**`DELETE /projects/{id}/artifacts/{artifactId}` exists and has NO CONSUMER in `src/`.** The artifact
register still cannot remove a mis-registered link through the product; the control, its confirmation
and its two 409 sentences are an unbuilt frontend slice, and §118's dialog is the pattern.

**§121 PHASE 4 ADDED FOUR FILES** — `lib/flake.ts` + `lib/flake.test.ts`,
`features/cases/{FlakePanel,QuarantineDialog}.tsx` + `FlakePanel.test.tsx` — and touched
`api/{types.ts,schema.d.ts,queries/{cases,keys,index,workItems}.ts}` plus
`features/cases/CaseDetailPage.{tsx,test.tsx}`.
**Test count is 1045 across 79 files** — measured. **The 1021 in the previous header was wrong**:
22 tests were added and the total moved 24, so the real baseline was 1023. **Fifth drift of this
figure — read it off a run.**

> **⚠ THREE STATES HAVE NO FLAKE NUMBER AND THEY ARE THREE DIFFERENT CLAIMS. `lib/flake.ts` is the
> only thing that should read `CaseFlake` directly.** `flake === null` is *no row — nobody has
> looked*; `flakeScore === null` is *a row, not enough evidence to say*; `flakeScore === 0` is
> *measured 38 times, evidently stable*. The middle and the last are **opposite** and `score ?? 0`
> collapses them while type-checking. `describeFlake` returns a discriminated union so the wrong
> branch is reachable from a unit test with no DOM. **`formatRatio` is deliberately not used for the
> null branch** — it answers an em dash, which is the unexplained blank the module exists to avoid.
> The band chip is **not green for `STABLE`**: this measures reliability, not outcome, and a test
> that fails every single time would wear it.
> **`TestCase.flake` IS THE READ PATH AND IT IS DETAIL-ONLY.** There is no `GET .../flake` — the
> contract served `CaseFlake` only as the `PUT` response for three phases, so the panel had nothing
> to render on load. `classificationValueIds`' rule decided where it went; `theListIsNotWidened`
> (backend) is what makes putting it on the grid a decision.
> **`quarantinedBy` IS A `UserRef` SINCE THIS PHASE** — it was the only bare-uuid actor field on the
> API against ~25 that are not. It can still be null when the id resolves to no account, and the
> panel says "somebody no longer in this company" rather than dropping the line (which would lose
> the date too).
> **THE GATE IS `case:edit` AND THAT IS THE SLICE**, not `result:submit`: Automation Service does not
> hold it, so a leaked CI token can post results and cannot quarantine the tests those results fail.
> A panel gated on `result:submit` passes every other test in `FlakePanel.test.tsx`.
> **THE DIALOG CLOSES ON SUCCESS, deliberately unlike §118's reveal** — nothing here is
> unrecoverable and the opposite direction reverses it. Do not generalise §118's stay-open rule.
> `quarantining` is a **prop decided by the caller**, never derived inside the dialog: a toggle
> computed from a possibly-stale row is how a quarantine releases itself.
> **`keys.workItemRoot` REPLACED `workItems.ts`'s PRIVATE `WORK_ITEM_ROOT`** — quarantining moves a
> card on a board the case screen knows nothing about, so the `['work-items']` literal was about to
> exist in two modules. `#confirmed`

**§121 PHASE 3 ADDED A SEVENTH `WorkItem['kind']` — `QUARANTINE` — AND THREE `Record<Union, …>`
MAPS ARE WHAT MADE IT SAFE.** `BoardPage.kindLabels`, `cardMove.derivedNouns`, and the contract's
own enum: a new member is a compile error in both maps until it is worded, which is the pattern the
board has used since §81. Three lines changed under `src/` plus the regenerated `schema.d.ts` and
one subtitle; **no new component, no new query, no test count change (still 1021/77 — measured)**.
**⚠ `BoardPage.describeCard` GATES ON `kind === 'CASE_MAINTENANCE'` AND THAT GATE IS LOAD-BEARING.**
It splits that kind's description on commas and looks each fragment up in `healthLabels`, because a
maintenance card's description is a list of raw `HealthSignal` codes the server refuses to word. A
quarantine reason is a sentence a person typed, so the whole reason `QUARANTINE` is a separate kind
rather than a second `CASE_MAINTENANCE` source is that filed there it would render as its own
fragments joined by a middot — with nothing failing on either side. Any future kind whose
description is prose gets the pass-through by default; do not widen the gate.

**⚠ WHEN PHASE 4 IS BUILT, `CaseFlake.flakeScore` IS NULLABLE AND NULL DOES NOT MEAN ZERO.** Null is
*not enough evidence to say*; `0` is *evidently stable*. They are opposite claims about a test, and
`score ?? 0` type-checks. The API ships `executions`, `transitions`, `alternations` and `cells`
precisely so a panel can render "3 results, not enough to score" instead — this is the same class of
defect as §95's `usage_count` and §100's exit criteria, in the place it costs most, because the
number it would invent is the one a human uses to decide whether to quarantine a test.

**§120 added one file** — `components/HealthChip.test.tsx` — and touched
`api/schema.d.ts`, `lib/labels.ts`, `components/StatusChip.tsx`. **Nothing else.** The dashboard's
"Needs attention" panel, the case grid's health facet and `HealthChip` are all generic over
`HealthSignal`, so a new signal arrives with no screen work at all — which is exactly what the
server's `HealthSignal` header predicted in M0 and is worth knowing before planning a signal slice
as though it needed a UI.
**Test count is 1021 across 77 files** — measured, over §119's measured 1018/76.
**§119 added six files** — `features/ingestion/{IngestionsPage,IngestionJobPage,BindTestDialog,UnbindMappingDialog}.tsx`
plus `api/queries/ingestion.ts` — and three test files; it touched
`api/{types.ts,queries/{keys,index}.ts}`, `lib/labels.ts`, `app/{router,AppShell}.tsx`.
**⚠ `ReportFormat` NAMES TWO UNRELATED THINGS IN THE CONTRACT** — the CI dialect
(JUNIT/TESTNG/ALLURE/CUCUMBER, schema-level) and a generated report's export format
(MD/HTML/PDF, read off `generateReport`'s body). `api/types.ts` already used the name for the
second, so the first is aliased **`IngestionFormat`**. The collision was a build error, which is the
good way to find it; do not "fix" the divergence by renaming either back.
**Test count is 1018 across 76 files** — measured. The **baseline before §119 was 1002, not the 992
the handoff carried**; this figure has now been stale in the same direction four times.
**§116 and §117 are backend-only** — they moved nothing under `frontend/src` except a regenerated
`api/schema.d.ts`. **§118 added five files** (`features/admin/{ApiTokensPage,IssueTokenDialog,RevokeTokenDialog}.tsx`,
`components/CopyField.tsx`, `lib/apiTokens.ts`) plus three test files, and touched
`api/{types.ts,queries/{keys,admin,index}.ts}`, `app/{router,AppShell}.tsx`.
**Test count is now 992 across 73 files.** The 962 in the previous header was **stale** — the
measured baseline before §118 was 968, so this figure has now drifted three times and is worth
reading off a run rather than trusting.
**§115 touched two frontend files** — `features/reports/GenerateReportDialog.{tsx,test.tsx}`.

> **§107 invalidated a lot of this file at once.** `src/mocks/` is gone — every "the mock does X"
> statement in the slice entries below is now history rather than current state, and is left in
> place because the *failure class* it records is the durable part. `VITE_API_MODE` is gone too:
> `npm run dev` requires a running backend. Test count is **954 across 70 files** (the two deleted
> files were `mocks/db/{suites,workItems}.test.ts`, which asserted the mock's own rules).

**Fingerprint files:** `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`,
`frontend/vite.config.ts`

> Authoritative docs win over this file. `CLAUDE.md`, `docs/HANDOFF.md` (an index — sections live in
> `docs/handoff/NN-slug.md`), and the specs in `CLAUDE.md`'s precedence table. This is a map for
> re-orienting fast, not a source of truth.

## Version lane

- **React 19 + TypeScript strict**, **Vite 8** SPA. **No Next.js, no RSC** — so there is no
  `'use client'` boundary anywhere and the skills' RSC lane does not apply. `#confirmed`
- ~~**MUI v7**~~ → **MUI v9** (`@mui/material` 9.2.0, verified against `node_modules` in §94; both
  this file and `CLAUDE.md` still said v7). The slot rules below are unchanged by the bump, and one
  thing v9 makes concrete: `Autocomplete`'s `renderInput` params carry **`slotProps.htmlInput`**, so
  a testid on the inner input is `slotProps={{ ...params.slotProps, htmlInput: {
  ...params.slotProps.htmlInput, 'data-testid': … } }}` — spreading `params` alone lands nothing on
  the element `userEvent.type` drives. With X DataGrid / TreeView, **React Router 7**,
  **TanStack Query v5**.
- **`@dnd-kit` since §94** (`core` 6.3 · `sortable` 10.0 · `utilities` 3.2 · `modifiers`) — the
  first runtime dependency added since the scaffold. **Two consumers, and they use different
  primitives on purpose**: the case editor's step list is `SortableContext` + `useSortable` (a
  reorder *within* one list), the kanban board is `useDraggable` + `useDroppable` since §106 (a move
  *between* containers, with no within-column order to preserve). **Do not add a second DnD
  library**, and do not reach for `SortableContext` on the board — see the seam.
- Package manager **npm**. `erasableSyntaxOnly` is on, which forbids TS constructor parameter
  properties — see `api/client.ts:35` for the recorded workaround. `#confirmed`

## Build, run & test

- `npm run dev` — **requires the backend.** `docker compose up` at the repo root plus a company from
  `provision-company`; sign in by redeeming the `/accept-invitation` link it prints. ~~MSW mock mode
  by default, no backend needed, any 4+ char password~~ → **the mock layer was deleted in §107.**
- **`npm run build` is THE gate** (`tsc -b` + vite build). **Do not trust `tsc --noEmit`** — it has
  reported 0 errors while the build failed with nine (working agreement). `#confirmed`
- `npm run lint` — oxlint, must stay at **0 warnings**; it prints nothing when clean.
- `npm run test:run` — Vitest + RTL. **1172/1172 across 90 files at §129 phase 3** (measured on a quiet run; the pre-slice baseline it grew from was **1150**, not the 1144 the handoff carried — eighth drift). Earlier: **1088/1088 across 83 files at §126 phase 4** (measured; the
  measured baseline it grew from was **1055/79**, not the 1045 the previous header carried — sixth
  drift). Earlier: **954/954 across 70 files at §107** (976/72 at §106, before the two mock-rule test files went with the mock) (958/70 at §105) (the §102 line said **925/67** and the measured baseline was **928/67** — this figure is carried forward by hand and has drifted at least twice; read it off a run before trusting it). Earlier: 913/65 at §99 phase 3, 909 at §98, 895/64 at §97 phase 5, 849/60 at §100. **`testTimeout` is 30s since §67 — the second raise for one cause.** The suite has a load-dependent cliff: adding five tests anywhere pushed `DocPageEditorPage`'s two `userEvent.type` tests over the old 20s budget, in a file that slice never touched, always as *Test timed out* and never an assertion. The real fix (those tests type sentences character-by-character; `paste` asserts the same thing) is named in `vitest.config.ts` and not done. `#confirmed`
- `npm run gen:api` — regenerate `src/api/schema.d.ts` after **any** `openapi.yaml` change. Runs
  through `npx` on purpose (the scaffold pins TS 6, the tool peer-requires 5.x); do not "fix" it.
  **Since §137 there is a SECOND copy to regenerate**: `cd packages/client && npm run gen:api`.
  The two outputs are byte-identical and `packages/client/src/schemaFreshness.test.ts` fails when
  they are not, so a half-done regeneration is caught — but only by the `packages` CI job, not by
  anything in `frontend/`.
- **There is no mode switch.** `VITE_API_MODE` is gone with the mock (§107); `/api` always proxies to
  `VITE_API_PROXY_TARGET` (default `localhost:8080`).
  **After changing `.env.local`, kill vite and `rm -rf node_modules/.vite`** — an in-place restart
  leaves a stale optimize-deps hash and the page renders **blank with an empty console**. Hit and
  confirmed in §61's live pass; the remedy works. `#confirmed`

## Layout

`src/{api,app,auth,components,features,lib,notifications,a11y,test}` — **no `mocks/` since §107**. Feature folders under
`features/<area>/`; routes are project-scoped under `/projects/:projectId/*` (`app/router.tsx`, lazy).
**Five routes are NOT** — `/` (project list), `/admin/teams` (company), `/admin/tokens` (§118, company:
a token belongs to the company even when its `projectId` pins it to one project), `/my-work` (§83, the
cross-project personal board) and `/teams/:teamId/board` (§85, the team board). A screen whose
subject is not a project does not live under one. **`/projects/:projectId/reports` (§89) lands on
the other side of that test**: a report is computed over one project's facts and its own header
says *"covers X in full and no other project"*, so switching projects changes what it is about.
**`/projects/:projectId/reports/jobs/:jobId` (§90) is the report itself** — a route rather than a
dialog because a report is read and referred back to, and because a job id in the address bar is the
only durable handle a report has while nothing lists jobs.

**`/` finally has an affordance (§91).** It is where sign-in lands and where an unreachable project
falls back to, and until §91 **nothing in the app linked to it** — the one screen every session
starts on and no screen pointed at. It now has a rail item (`ALL_PROJECTS`, in the ungrouped block
directly above the switcher) and the wordmark links there too.

> **⚠ This file went FIVE SLICES STALE (§72–§76) and was repaired in §77.** Only the backend half of
> the update-the-cache habit had stuck. If you are reading this and the baseline lags HEAD by more
> than a slice, the seam list below is a lead and not evidence — re-grep anything that decides your
> slice's shape. `docs/handoff/00-plugin-skills-are-mandatory.md` step 0 now states the protocol.

## Seams to reuse

- **A FILE IS DOWNLOADED THROUGH `FileDownloadLink`, AND THE OBVIOUS SPELLINGS ARE 401s (§126).**
  `components/FileDownloadLink.tsx` (the control) over `fetchArtifact` (`api/client.ts` — the
  same-origin guard, the bearer token, the 60s budget) + `saveBlob` (`lib/download.ts` — the
  object-URL dance and its revoke). Two consumers: `components/AttachmentList.tsx`'s row and
  `features/artifacts/ArtifactTable.tsx`'s `FILE` row. Testids `<root>.Download` and
  `<root>.Download.Error`.
  **`<a href>` and `<img src>` both send NO CREDENTIAL** — §89's finding, and the reason this is a
  `<button>` in a table full of anchors. `ArtifactTable.test.tsx` asserts the pair **in one render**
  (the `FILE` row has no `href`, the `EXTERNAL_URL` row keeps one), because a file rendered as an
  anchor and a link rendered as a button are each locally plausible and only one of them is right.
  The failure renders **beside the row**, and the component is a **fragment** so a table cell and a
  stacked list can lay it out differently.
  **`DownloadReportButton` is deliberately NOT merged into it**: it keys on a `ReportJob` and
  *composes* a filename from the job's type and format because a report response may name none. An
  attachment always has a `filename` column. `#confirmed`

- **THE ATTACHMENT PANEL IS ONE COMPONENT FOR TWO OWNERS, AND THE GATE IS THE DECISION (§126).**
  `components/AttachmentList.tsx`'s `AttachmentsPanel` + `lib/attachments.ts`
  (`MAX_UPLOAD_BYTES` · `uploadRefusal`), over `useAttachCaseFile`/`useDeleteCaseFile`
  (`api/queries/cases.ts`) and `useAttachDocPageFile`/`useDeleteDocPageFile`
  (`api/queries/documentation.ts`). Mounted as `CaseDetail.Attachments` (content column, below the
  steps, above Traceability) and `Docs.Attachments` (its own card **beneath** the prose). Testids
  `<root>.{Attach,Input,Refused,Error,List,Row,Download,Delete,Empty}`.
  **ONE `permission` PROP FOR BOTH WRITES, and no `authorId` anywhere.** See the warning at the top
  of this file: the `OWN` arm one component over is the change that must not be copied.
  **The list is `TestCase.attachments` / `DocPage.attachments` and there is NO `GET .../attachments`**
  — detail-only, exactly as §121 landed `TestCase.flake`. Neither summary carries it, so no grid key
  moves and the writes invalidate the detail alone. The doc-page writes go through
  `invalidatePageEverywhere`, because a page that documents a plan is served under **two** keys.
  **`MAX_UPLOAD_BYTES` IS ONE CONSTANT FOR FOUR OWNERS**, mirroring the single
  `spring.servlet.multipart.max-file-size`. It **replaced `MAX_EVIDENCE_BYTES`'s role** conceptually
  but that constant still exists in `runs.ts` and `EvidencePanel` still imports it — a second copy of
  one deployment setting, left standing because sweeping evidence was out of scope. **Fold it in
  when evidence is next touched.**
  **`uploadRefusal` RETURNS TWO DIFFERENT SENTENCES AND THE EMPTY ONE IS NOT COSMETIC**: a zero-byte
  part is a 400 naming the field at `/artifacts/files` and a **successfully stored zero-length file**
  at the case and page endpoints — a row that looks attached and opens to nothing. Both refusals are
  copy and not security: a file over the limit is a **413 refused by the servlet container before
  any handler runs**, so it carries no problem document and there is nothing to land on a field.
  `#confirmed`

- **A NEW HEALTH SIGNAL IS TWO RECORDS AND ONE DECISION — NEVER A SCREEN (§120).**
  `lib/labels.ts`'s `healthLabels` and `StatusChip.tsx`'s `healthHelp` are both
  `Record<HealthSignal, string>`, so a new member is a **compile error until labelled** and there is
  nothing else to wire: the dashboard tile, the case grid's facet and `HealthChip` all iterate the
  enum. Do not plan a signal slice as though it needs a page.
  **BUT THE TOOLTIP IS A CLAIM ABOUT THE SERVER'S COMPARISON AND NOTHING TYPECHECKS IT (§158).**
  `healthHelp[CONDITION_DRIFT]` said the condition had changed *"since the case was last
  **reviewed**"* from M0 until §158 gave the signal a derivation — which compares the case's newest
  **version snapshot** against the live library and never consults review at all. The record being
  exhaustive guarantees a *string* exists, not that it is true; a signal nothing computes has a
  tooltip nobody can falsify. **When a backend slice gives a signal its first writer, re-read that
  signal's `healthHelp` line in the same commit.** Two remain unverifiable on the same terms:
  `outdated_spec_changed` and `orphaned` have no writer, so their sentences describe comparisons no
  code makes.
  **THE DRIFT PAIR MUST NOT LOOK ALIKE, AND THAT IS THE WHOLE FRONTEND DECISION.**
  `STALE_AUTOMATION` is in `SEVERE_SIGNALS` and `UNDECLARED_AUTOMATION` is deliberately **not**.
  They are mirrors and read as a matched set, so matching their appearance is the *tidy* change —
  and it erases the only distinction a reader acts on: stale automation is a result the team
  believes it is getting and is not (a red test can stay invisible), while undeclared automation is
  a result arriving that no coverage number counts (the percentage is understated, wrong in the
  direction that costs nobody a missed defect). The server draws the same line in
  `HealthSignal.Severity` and **severity is not on the wire for a bare signal** — only a
  `DashboardTile` carries it — so `SEVERE_SIGNALS` is a second copy of a server decision with
  nothing holding the two together except `HealthChip.test.tsx`, which exists for §118's reason. A
  mutation confirms it reddens exactly one test. **If a signal ever renders with a severity beside
  it, delete that list rather than keeping it in step.** `#confirmed`

- **A SECRET IS SHOWN ONCE, AND THE DIALOG THAT SHOWS IT REFUSES `onClose` (§118).**
  `features/admin/IssueTokenDialog.tsx`'s reveal branch + `components/CopyField.tsx` +
  `lib/apiTokens.ts` + `useIssueApiToken`/`useApiTokens`/`useRevokeApiToken` (`api/queries/admin.ts`,
  key `keys.apiTokens`). Screen is `ApiTokensPage` at **`/admin/tokens`**, testids `Admin.Tokens.*`,
  `Admin.TokenIssue.*`, `Admin.TokenRevoke.*`.
  **THE REVEAL BRANCH PASSES NO `onClose` TO `Dialog` — the only one in the app that does not.**
  Esc and a backdrop click would destroy a value nothing can produce again. **The dangerous change
  here is the tidy one**: all four sibling dialogs in `features/admin` close on success, so a
  refactor toward consistency throws away a credential *with a success message on top*. One test
  exists only to hold that (`shows the secret and stays open`).
  **THE SECRET NEVER TOUCHES THE QUERY CACHE.** No `setQueryData`, no optimistic insert — the
  plaintext lives in the mutation's own `data` and dies with the conditionally-mounted dialog. The
  test asserts over **every** cache entry, not the token key, because it is an absence.
  **THE LIST READ IS GATED BY THE CALLER'S OWN VERDICT** (`useApiTokens(enabled)`): the server
  *refuses* rather than narrows, so an ungated read is a guaranteed 403 raising a global error over
  a working screen. **Nothing on screen distinguishes the two implementations** — the check is an
  absent request. Deferred on **`Gate.pending`**, never rendered as a denial, or a Company Admin is
  told they lack access on every visit.
  **`CopyField` ASSUMES NO CLIPBOARD.** `navigator.clipboard` is absent on an insecure origin and
  this product is self-hosted over plain HTTP today, so the value is always rendered as selectable
  text and the button is an accelerator whose failure says what to do instead.
  **`tokenState` KEEPS EXPIRED AND REVOKED APART** — revoking a lapsed credential is a real act and
  the collapse disables it; revocation outranks expiry when both. Derived, never served: expiry is a
  comparison against *now* and a served status goes stale in the cache.
  **`lastUsedAt` IS ON THE WIRE AND IS NOT A COLUMN** (always null today — `ui-rules`' no-column-the-
  write-path-never-fills rule). **The role filter is `scope !== 'SYSTEM'` and NOT `GrantRoleDialog`'s
  scope-dependent one** — a token's project is a ceiling on where it works, not a claim about the
  role's scope, and copying the neighbour hides roles the server accepts. `#confirmed`

- **A PER-TYPE GATE MUST KEY ON WHAT THE TYPE NEEDS, NEVER ON WHAT IT IS NOT (§115).**
  `GenerateReportDialog` offers three report types since §115 (`run_summary` ·
  `aggregate_execution` · `coverage_matrix`), and the run-emptiness gate was spelled
  **`!isAggregate`** by R6. With two types that was the same statement as *"this type needs a run"*;
  **at three it is not**, and `coverage_matrix` would have inherited the run gate and been
  **unreachable in exactly the projects with documentation and no runs** — §66's shape, and the very
  defect R6 wrote that line to fix, arriving again through the shape of the fix. It is `needsRun`
  and `isCoverage` now, so a fourth type **opts in**. Grep for `needsRun` before adding one.
  - Each type's parameter form lives beside its entry in `TYPES`, because **nothing in the contract
    describes a report's parameters machine-readably** — there is nothing to generate a form from,
    which is why the file says so rather than leaving it to be rediscovered.
  - The wire strings are named **once each** at the top (`RUN_SUMMARY`/`AGGREGATE`/`COVERAGE`);
    §115 added the third without hunting hard-coded literals, which is what R6 created them for.
  - The document picker is **flat**, though `DocPageSummary` carries `parentId`: the report's spine
    is **one page and never its subtree**, so a nested picker would suggest a choice the request
    cannot express.
  - **`docPageId` goes inside `parameters`**, like `runId`. At the top level it is dropped silently
    and the job FAILS minutes later on a card that looks exactly like a correct one — the reason
    both have an exact-body test.

- **THE BOARD IS DRAGGED WITH `@dnd-kit`, AND THE WRITE IS OPTIMISTIC (§106).**
  `features/board/{cardMove,boardKeyboard}.ts` + `BoardPage.tsx`'s `BoardColumn`/`WorkCard`, over
  `useMoveWorkItem` (`api/queries/workItems.ts`). Testids `Board.Card.DragHandle`,
  `Board.Column.<STATUS>` (also the **droppable id**), `Board.RejectedMove`.
  **DnD ALREADY EXISTED HERE AND WAS NATIVE HTML5** (`draggable` + `onDrop`, §53). It works with a
  desktop mouse and **has no touch support whatsoever** — a board could not be moved on a tablet,
  with nothing on screen saying so — no keyboard path, and it was a second DnD implementation
  beside §94's (`ui-rules` §8). Check what a screen already does before building the ask.
  **`useDraggable` + `useDroppable`, NOT `SortableContext`.** A step drag reorders within one list;
  a card drag moves between containers and the board has no within-column order to preserve (cards
  sort overdue → priority → id). Sortable would have to invent a rank the model does not have.
  Collision is **`pointerWithin`, not `closestCenter`** — a column is a big rectangle and a card is
  small, so closest-centre lands a release near a wide column's left edge in its neighbour.
  A **`DragOverlay`** copy follows the cursor and the original stays and fades (removing it
  collapses the column under the pointer). A **handle**, not a draggable card — §94's rule.
  **THE KEYBOARD IS TWO PATHS AND BOTH ARE LOAD-BEARING.** The "→ column" buttons are the simpler
  one (one per legal target, disabled-with-reason for the rest) and are unchanged. The handle is
  operable too, because §94's rule is that a focusable handle doing nothing on Space is worse than
  none — and that needed **new code**: `sortableKeyboardCoordinates` needs a `SortableContext` this
  screen has none of, and the default getter nudges **25px per keypress**. `boardKeyboardCoordinates`
  makes ←/→ **jump a whole column** and returns **null at either end** rather than wrapping. The
  arithmetic is `stepToAdjacentColumn`, pure and tested, resolving the current column by **nearest
  centre rather than containment** (mid-drag the card is routinely over a gutter).
  **`resolveCardMove` has FOUR outcomes and three are not moves** — outside every column, back where
  it started, an `over` that is not a column. §94's `move(i,i)` in board spelling, and worse here: it
  would be a `PATCH` writing an **unchanged status**. The fourth is a **legal refusal**, decided at
  drop time and never sent (`ui-rules`), and a derived card is **not draggable at all** rather than
  lifting and being refused on release.
  **THE REFUSAL SENTENCES LIVE IN `cardMove.ts` BECAUSE TWO AFFORDANCES MUST AGREE** — they had
  already drifted (the drop said *"mirrors a review… update the review"*, the tooltip said *"This
  card follows its source"*). One test exists only to hold `refusalFor` equal to the drop's.
  **THE WRITE IS OPTIMISTIC ACROSS EVERY BOARD KEY, and the DRAG is why.** A button press with
  300ms of latency reads as the button working; a *release* that springs back and re-appears
  elsewhere reads as the drop having missed, and users repeat it. `cancelQueries` first (a read in
  flight would otherwise resolve after the write and undo it); `onError` restores the **snapshot**
  rather than reversing the edit; `onSettled` re-reads on **both** paths. The failure **announces**
  as well as showing a persistent alert — the snap-back is silent to a screen reader.
  **The mock now answers the contract's SECOND 409** (`allowedTransitions`), which it had answered
  200 to from Phase 1: unreachable while the only mover was a button the board already disabled.
  **TWO TESTING TRAPS.** A `@dnd-kit` drag **cannot be driven in jsdom** — `fireEvent.dragStart`
  meant something to the old native implementation and means nothing now, so two tests were
  rewritten to read the sentence off the disabled button's tooltip (hover the **span**, not the
  button). And **a never-resolving query breaks RTL, not the product**: a permanently pending query
  leaves `act` waiting so **no state update after it is ever flushed**. The rollback is asserted at
  the hook level with **no `useWorkItems` mounted** — an invalidation with no observer fetches
  nothing, which is the only way that assertion is non-vacuous (`onSettled` refetches on failure
  too, and the mocked board answers with the card where it started). `#confirmed`

- **THE RUN SAYS WHAT BROKE, AND THE TWO HALVES HAVE DIFFERENT COSTS (§105).**
  `features/runs/FailureSummary.tsx` on `RunDetailPage`, over `runFailures` + `stepOutcome`
  (`lib/results.ts`) and `useExecutionSteps` + `STEP_DETAIL_LIMIT` (`api/queries/runs.ts`, **the
  second `useQueries` in this codebase**). Testids `RunDetail.Failures` / `.Headline` / `.None` /
  `.Truncated` / `.Blocked` / `.Case` / `.Step` / `.Step.Counts` / `.Step.Comment` / `.Step.None`
  plus `DataRegion`'s `RunDetail.Failures.Steps.*`.
  **THE CONTRACT HAS NO RUN-LEVEL STEP ROLLUP.** `TestRunDetail.executions` is
  `TestExecutionSummary[]` with **no steps**; `StepResult[]` exists only on `TestExecution`, one
  read per execution. So the **case** half (which failed, which were blocked) is free and complete
  at any run size, and the **step** half is one read per failed case, capped at
  `STEP_DETAIL_LIMIT = 25` with the cap **stated on screen**. The bound is on the READS, never on
  the answer — every failed case is always listed. Widening the contract is the right long-term fix
  and was deliberately not done here: it is a server slice, and it would move these numbers into
  §101's rollup lane where they inherit `formatComputedAt`.
  **A BLOCKED CASE IS NOT A FAILED CASE.** The "Needs attention" chip on the same screen merges
  FAILED+BLOCKED on purpose (both need a person); copying that merge here reports the product broken
  where nobody managed to test. Partitioned, counted separately, different sentences. SKIPPED and
  unexecuted are in neither list.
  **AN UNMARKED STEP IS NOT A PASSED STEP** — `stepOutcome` returns **three** numbers (failed,
  unmarked, total) because "1 of 5 failed" invites the reader to infer four passes. `criterionState`'s
  rule one field over, and the mirror of `suggestedVerdict`'s vacuous `[].every()`. A step whose
  `statusId` the vocabulary does not know counts as **neither** — that is not the tester's
  unfinished work.
  **NO PERCENTAGE AND NO STALENESS MARKER, both deliberate.** The pass rate is directly above
  (§65's two-percentages defect); and nothing here is a rollup, so a "computed at" would be a false
  claim in the other direction.
  **The `DataRegion` wraps the STEP LAYER ONLY and renders as a strip** (`{() => null}` loaded
  branch) — the first draft put the case list inside it and its own test caught 25 in-flight reads
  hiding every failure. Combine is pessimistic both ways, keys are `keys.execution(...)` so the
  execution screen and Grid mode share the cache.
  **The mock's failed executions now come in TWO shapes** (`runExecutions` alternates: one stops at
  the failure leaving trailing steps unmarked, the next marks the whole list). Before §105 every
  failed case failed on its LAST step with everything before it passed, so **"not marked" was
  unreachable in mock mode** — §100's exit-criteria finding and §64's usage counts, third sighting.
  `#confirmed`

- **AN EXECUTION ROW IS TWO LINKS TO TWO PLACES, AND `ExecutionRowLinks.tsx` DECIDES BOTH (§104).**
  `CaseKeyLink` (key → `/cases/{caseId}`) and `ExecuteCaseLink` (title → the execution, carrying
  `?mode=`), composed by **both** grids over executions: `ExecutionGrid` (Grid mode) and
  `RunDetailPage`. Testids `<Row>.CaseLink` and `<Row>.Link` onto each grid's static row locator.
  **The two grids had OPPOSITE answers and only one was right.** `RunDetailPage` sent the key to the
  case and the title to the execution; `ExecutionGrid` sent the key to the *execution* and left the
  title inert — so **Grid mode could not reach a test case at all**. No crash, no type error, no
  lint warning: `ui-rules` §8's renderer divergence, each half locally defensible.
  **Deliberately NOT one shared cell.** The two grids give this different room — run detail packs
  both links into one Case cell beside the `v{caseVersionNo}` chip, Grid mode gives the key its own
  narrow column so keys align for scanning. Sharing the *cell* would have made layout the shared
  thing and left the destinations decided twice, which is the part that was wrong.
  **TWO LINKS IN ONE ROW MEANS TWO ACCESSIBLE NAMES.** The key's is *"Open test case TC-41"*, set as
  an explicit `aria-label` **and** as the `Tooltip` title — **the same string in both**, because
  MUI's `Tooltip` sets its own `aria-label` from `title` when the child has none, so writing them
  separately leaves two sentences free to drift with one of them never read aloud. The title's name
  is its visible text **plus** the destination (WCAG 2.5.3: the visible label is contained in the
  accessible name, so voice control still works on the words on screen). **The title's tooltip stays
  the untruncated title** — that cell ellipsises, so its tooltip is a *truncation* affordance and not
  a destination one; one tooltip answering two questions answers the more useful one worse.
  **`caseId` was already on `TestExecutionSummary`** (required since M1, stamped by the mock), so
  there was no contract change and nothing faked client-side — check the schema before assuming a row
  needs widening.
  **`ExecutionQueue` is left alone on purpose**: Split's rail row is *one* link with the key and
  title inside it, and splitting it would nest a link in a link. The case is reachable there through
  `Execution.CaseLink` in the execution screen's header.
  **The test asserts over BOTH grids, never the module alone** — a unit test on `ExecutionRowLinks`
  would have been green throughout the state §100 shipped, because the defect was one caller not
  using the rule. `#confirmed`
- **A PLAN GROUP'S REFERENCES NOW LIST THE CASES THEY CATCH, AND THE SERVER DOES EVERY RESOLUTION
  (§103).** `useReferencedCases` (`api/queries/cases.ts`, **the first `useQueries` in this codebase**)
  + `resolveReferences` (`lib/planScope.ts`) + `features/plans/ResolvedScope.tsx`, mounted per
  `GroupCard` under the chips. Testids `PlanCases.Resolved.<group>` / `.List` / `.Row` / `.Truncated`
  plus `DataRegion`'s own `.Loading` / `.Error` / `.Empty` / `.Stale`.
  **NEVER `cases.filter((c) => c.suiteId === id)`.** A suite is filed ∪ added ∪ tagged since §98, so
  that filter is the *filed* arm alone — §96 shipped exactly it and produced a tag-defined suite
  showing a **count of 2 over a grid of 0 rows**. `?suiteId=` is the only thing that answers the
  union, and this list sits directly under the group's served count, so the same defect here would
  put the contradiction on one line of one card.
  **ONE READ PER REFERENCE, unioned client-side.** References *widen* a group; `?tag=` repeats and
  **ANDs**, and `GET /cases` takes one `suiteId`. A single request carrying every reference answers
  the **intersection** — usually empty, and empty in a way that reads as *catches nothing*. The key
  is `keys.cases`, shared with the grid, so the case-write invalidations already move this list.
  **The combine is pessimistic both ways** — pending while ANY arm is pending (a half-arrived union
  looks complete), failed if ANY arm failed (the arms that answered are a shorter list with nothing
  saying so) — and `dataUpdatedAt` is the **oldest** arm, since a union is only as current as its
  laggard. `ReferencedCasesState` is declared in `cases.ts`, not imported from `components/`.
  **This is NOT D7's add-time snapshot returning:** nothing here or on the server remembers a case
  id; the rows are derived on every render and vanish with the reference.
  **Three deliberate omissions.** *Individually named cases* — no by-ids read exists (`?q=` matches a
  **key**), and reusing the chips' 200-row catalogue would put its accepted truncation inside a list
  presented as complete; the caption says they are not repeated. *A count* — the heading already
  carries the server's `caseCount` over all three arms, and a second number would read as a
  contradiction (§52/§65). *New case rows* — they do not exist yet.
  **`dirty` moves the count and the list in OPPOSITE directions, and `GroupCard` takes both props for
  that reason.** The served count hides (it is the server's arithmetic over the plan *it* holds); the
  list stays and re-captions to *"what these references **would** catch … Nothing is in the plan until
  you press Save"*, because it is computed from the **draft**. Collapsing the two props is the obvious
  tidy-up and makes one of them wrong.
  **`ResolvedScope` does not memoize the merge on purpose** — the parent re-renders per keystroke and
  `labelFor` is a fresh closure, so a `useMemo` keyed on it never hits and one keyed on `state.data`
  goes stale when a suite name arrives. `#confirmed`
- **EVERY TAG CONTROL SUGGESTS FROM `useProjectTags` SINCE §102 — AND EVERY ONE STAYS `freeSolo`.**
  Four consumers now: `PlanCasesTab`'s `TagPicker` (§97 phase 6), plus the three §96 shipped with
  `options={[]}` — `CaseEditorPage`'s **Tags**, `SuiteDialogs`' **Tag rules** (`SuiteFormDialog`
  gained a `projectId` prop for it), `BulkActionDialog`'s **Add tags**. All pass
  `(projectTags.data ?? []).map((u) => u.tag)` + `filterSelectedOptions`.
  **THE LIST IS A PICKER'S AND NEVER A VALIDATOR'S** — `case_tag` is free text, so `freeSolo` is
  load-bearing: a control that refused an unlisted tag makes the first use of any new tag impossible
  and closes the vocabulary silently. One test per control pins it.
  **The read is deliberately ungated** (no `enabled`, no `isSuccess`, no spinner): a failed or slow
  `/tags` must leave the field working as plain free text. **The server's order is preserved** —
  most-used first; MUI's default `filterOptions` keeps it and nothing client-side re-sorts.
  **`BulkActionDialog`'s tags locator was on the `TextField` ROOT until this slice**, so the field
  could not be typed into at all — §96's `slotProps` trap in the form that hides longest, because
  **the two tests over that field only ever read the POST body**. A payload-only assertion passes
  against a control no caller can reach; it took a suggestion list to expose it. `#confirmed`
- **THE EXECUTION SCREEN HAS THREE MODES OVER ONE QUEUE (§100 phase C).** `?mode=focus|split|grid`
  on the execution route, parsed by `features/runs/executionMode.ts` (**falls back rather than
  trusting the value** — an unrecognised mode reaching the layout renders neither rail nor detail,
  a blank page produced by a query string; `executionModeParam` returns **null for the default** so
  two links to one screen are the same string). `ExecutionQueue.tsx` is Split's rail,
  `ExecutionGrid.tsx` is Grid, `Workspace` in `ExecutionPage.tsx` picks between them with **Focus as
  the fallthrough**, not a branch.
  **No new endpoint and no new request**: the queue is `useRun`'s `executions`, already read since
  §72 for the run-closed bit.
  **`queueOrder.ts` sorts by case key and NOT by what needs doing** — the deliberate divergence from
  `RunDetailPage`, which is triage and static while read; the rail is read *while marking*, and a
  needs-doing rank re-ranks the row the tester is standing on the instant they save. Named here
  because it looks like an inconsistency and is not. (The file is `queueOrder.ts` and not
  `executionQueue.ts` for a dull reason worth not rediscovering: `ExecutionQueue.tsx` beside it
  collides on a case-insensitive filesystem and `tsc` refuses the build with TS1149.)
  **Four per-row rules Grid re-asks that the single-case screen never had to**, all invisible when
  wrong: the permission is **per row** (`result:submit` vs `result:amend` — half a run may be each);
  every write carries **`If-Match`**, whose `lockVersion` is on `TestExecution` and *not* on the
  summary a row holds, so a mark **reads the execution via `executionQueryOptions` + `fetchQuery`
  before it writes**; the same read supplies `comment` back, because **`PATCH` sets `comment` to
  whatever it is sent and an omitted comment CLEARS it** (server and mock alike) while `stepResults`
  omitted means "leave them alone"; and **Blocked still routes through the dialog** —
  `BlockedDialog.tsx` is its own file now and takes `conditions` (Grid, renders its own checklist)
  or `unmet` (Focus/Split, the checklist is on screen). `#confirmed`
- **`suggestedVerdict.ts` — the step marks propose a verdict and never decide one (§100 phase B).**
  Advisory: a heavier border and a "suggested" label; nothing preselected, nothing disabled. Four
  branches whose interaction is the risk, each pinned in `suggestedVerdict.test.ts`: **Failed fires
  on a HALF-marked checklist** (failure outranks completeness) while everything else needs a
  complete one; a **skipped** step proposes nothing rather than falling through to Passed; and a
  case with **no steps** proposes nothing — `[].every()` is vacuously true, so the naive version
  proposed Passed from no evidence, on exactly the exploratory cases where judgement is the only
  input. Step buttons render `statusLabels[kind].charAt(0)`; they rendered `.slice(0, 4)` — **"Bloc"**
  — until this slice. `#confirmed`
- **`CriteriaStrip` + `criterionColor`/`criterionStateLabel` in `lib/results.ts` (§100 phase A).**
  One mark per exit criterion plus the count in text; the strip is `aria-hidden` and the summary
  carries the accessible name. **It distinguishes "5 not evaluated" from "0 of 5 met"**, which read
  identically and mean opposite things, and denominates on what was measured.
  **`GET /plans` evaluates nothing** — `PlanCriteria.parse` builds every criterion with `met`/`actual`
  null and only `/statistics` calls `evaluate` — so the strip is grey on every real deployment. The
  mock's `present()` **now nulls both fields**; it handed the fixtures' `met: false` straight back
  until this slice, so mock mode showed red and green gates no server produces. That is §73's own
  recorded trap (*"the mock always supplied a boolean, so the null branch was unreachable"*) still
  live one layer up. `#confirmed`
- **`formatComputedAt` (`lib/format.ts`) — how old the ARITHMETIC on a screen is (§101).** Rendered
  on `PlanStatisticsPage` and `SuiteHistoryPage`, whose counts now come from the `plan_snapshot` /
  `suite_run_summary` rollups and can lag by up to one scheduler tick. **Null renders as "Computed
  now" — a positive statement, not the absence of a warning**: it means the server computed from the
  executions themselves, so it is *more* current than a timestamp, and a screen that says nothing
  when it is current leaves the reader unable to tell current from unlabelled.
  **DELIBERATELY NOT MERGED WITH `PlanStatistics.stale`, and they render on separate lines.** `stale`
  is about the EXECUTIONS (nothing run in 14 days); this is about the SUMS. A plan executed an hour
  ago and rolled up ten minutes ago is fresh by one measure and behind by the other — one line would
  have to pick, and whichever it picked would be wrong half the time (§65's shape with the
  contradiction hidden). **Mock mode structurally cannot show the rollup branch**: the mock computes
  from fixtures per request, so it truthfully answers `computedAt: null` on both endpoints — which is
  why every one of §101's browser rows is live-only.
- **`compareCaseKeys` (`lib/format.ts`) — display keys sort numerically.** `caseKey.localeCompare`
  puts `TC-10` ahead of `TC-9`; `RunDetailPage`'s tiebreaker did, and `compareReleaseVersions` had
  *already named* the class ("the same class of bug as ordering by case key") without anyone
  checking. Invisible on fixtures under ten. Both call sites use it now. `#confirmed`
- **`GET /projects/{id}/plans` serves the full `TestPlan`, and the contract now says so (§100).**
  It declared `TestPlanSummary` — which has no `exitCriteria` — while `TestPlanView`, the mock and
  `usePlans` all used the wide shape. `TestPlanView`'s javadoc claimed the contract had been widened;
  it had not. **A comment asserting a fact about another file is not a check.** `#confirmed`
- **`slotProps` ON AN `Autocomplete`'s `renderInput` MUST SPREAD `...params.slotProps` FIRST.**
  MUI v9 puts the selected **chips** in `slotProps.input.startAdornment` and the field's ref/ARIA
  wiring in `slotProps.htmlInput`. An object that merely adds a `data-testid` **replaces** the lot:
  every value is held by the form, sent on save, and **invisible on screen** — the author types a
  tag, sees nothing, and types it again. `npm run build` and `oxlint` are both green, and a payload
  assertion passes. §96 phase 6 shipped this and fixed it; the step-action `Autocomplete`
  (`CaseEditorPage:281`) had it right since §94 and is the precedent to copy.
  **Assert a chip is VISIBLE, not just in the payload** — that is the only check that fails.
- **THE SUITE RAIL IS A FLAT LIST SINCE §98 — `SuiteTree.tsx` IS `SuiteList.tsx`.**
  `Suite.parentId`/`Suite.path` and `SuiteInput.parentId` left the contract, so there is no
  `SimpleTreeView`, no `TreeItem`, no expand state and no rollup: the count rendered is
  `suite.caseCount` verbatim. Testids are **`SuiteList.*`** (`SuiteList`, `.Item`, `.ItemMenu`,
  `.Item.Status`, `.AllCases`, `.NewSuite`, `.Empty.NewSuite`, `.StatusError`, `.Menu.*`), and
  `SuiteList.Menu.NewChild` is **removed rather than disabled** — disable-don't-hide is about an
  action the *state* forbids, and no state nests a suite. `lib/suites.ts` (`subtreeCaseCount`) is
  **deleted**; `SuiteHistoryPage` reads `suite.caseCount` directly, which closes §52's
  two-numbers-on-one-screen defect by there being one number. The case editor's suite picker no
  longer indents by `path` depth. **`lib/classification.ts` still walks a real tree** — §98 is about
  suites only, and doc pages and classification values still nest.
  **The mock's count and the grid's `?suiteId=` filter are ONE function** (`suiteMemberCaseIds`,
  returning ids) because they answered different questions from §96 to §98 — the count was the union
  and the filter was the filed arm, so a tag-defined suite showed a count of 2 over a grid of 0.
  `mocks/db/suites.test.ts` is a **new file** and its first assertion is the non-vacuity one: the
  fixture must hold a suite claiming a case it does not file, or everything below it is also true of
  the defective implementation.
- **THE SUITE DIALOG CLAIMS CASES BY TAG (§96 phase 6).** `SuiteDialogs.tsx` gains `tagRules` to
  its Zod schema and a chips `Autocomplete`; `SuiteInput` (`api/queries/suites.ts`) gains
  `tagRules` and `caseIds`. The rename dialog **seeds from the suite and always sends the field** —
  absent means "leave them alone", so an omitted field could never clear the last rule and a
  `[]` default would empty the suite on every rename. Both mistakes are opposites and
  `SuiteDialogs.test.tsx` (**new file**, this dialog had none) covers each.
  `caseIds` is **on the type and authored by no screen**: the endpoint validates it, the dialog
  offers tag rules only. Named rather than left to be found missing.
  `SuiteList`'s count carries a tooltip saying membership is filed ∪ added ∪ tagged **and that the
  counts do not sum to the project total** — a case can be in several suites, and a reader who
  subtracts otherwise finds a discrepancy in a correct number.
- **A CASE MAY BE FILED NOWHERE, AND TAGS ARE AUTHORABLE (§96 phase 5).**
  `caseSchema.ts`: `suiteId` lost its `.min(1)` — **`''` IS THE UNFILED VALUE**, not a missing one
  (MUI's `Select` treats `null` as uncontrolled and warns), and the submit maps it to **`null`**
  because `''` is a 400 on a uuid field. New `tags: z.array(z.string().max(60))`.
  `CaseEditorPage`: a **Not filed** `MenuItem` with an explicit label rather than a blank first row
  (a blank reads as a field somebody forgot), and a `freeSolo multiple` `Autocomplete` for tags with
  **no options list** — there is no tag vocabulary anywhere, and offering a curated set is what left
  `shared_step` empty. Tags are trimmed and de-duplicated **in the control and again at submit**.
  `CaseGrid` renders `suiteName === null` as muted italic **"Not filed"**, never blank — a blank
  cell reads as a suite that failed to load.
  `useCaseFilters`/`CaseFilters`: **`tag` (repeats, ANDs)** and **`unfiled` (boolean, written only
  when true)**. `?unfiled=false` parses as *absent* — otherwise it counts as an active filter that
  narrows nothing and the empty state claims a filter that does not exist. The **Not filed** chip is
  **disabled beside a suite filter** with the reason on hover (§31), because the server ignores the
  pair and an active-looking chip that changed nothing is the worse half of render-then-reject.
  The tag filter box commits on **Enter and is not debounced** like the search box: a half-typed tag
  is not a narrower filter, it is an exact match against nothing.
  Mock: `tags` left the refused list in the same commit, `suiteId: null` is accepted, and a tag
  change deliberately **does not bump the version** — mirroring the server's decision.
- **THE CONDITION PANELS, AND THE INHERITED ROW THAT MUST NOT BE SUBMITTED (§95 phase 3).**
  `features/cases/ConditionsPanel.tsx` (one component, `kind="PRE"|"POST"` — two would be
  `ui-rules` §8's "hundred slightly different pages" in miniature) + `ConditionEditDialog.tsx` +
  `lib/conditions.ts` (`impactSummary`) + `useCreateCondition`/`useUpdateCondition` in
  `api/queries/library.ts`. `useConditions` gained an **`enabled`** parameter and finally has
  consumers — it had **zero call sites** from the day it was written.
  **An attachment is a REFERENCE, and that is the design and the hazard.** Picking stores the id;
  editing the entry later changes every case and suite attaching it, with **no prompt and no fork**
  — the deliberate opposite of §94's step corpus, where a pick copies text. Hence the dialog states
  the blast radius *before* the save, its button reads **"Change everywhere"** and is danger-styled
  (`ui-rules` §4), and no fork is offered (the fork is what turned `shared_step` into
  near-duplicates nobody could rank).
  **`impactSummary` is in `lib/` with its own test because THE TWO COUNTS MUST NEVER BE ADDED.**
  `usageCount` is cases attaching directly; `suiteUsageCount` is suites, each propagating to every
  case beneath. A suite's case reach is **not computable on this side** — the server declines to
  publish it (an `ltree` walk the library read cannot express) — so blending them would be a guess
  presented as a measurement, understating in the one direction an impact warning must not. The
  test uses 4 and 3 and asserts **7 never appears**.
  **THE most dangerous line is `ownConditionIds`.** `TestCaseDetail.preconditions` carries the
  FOLDER's conditions too, marked `inherited`, and they render almost identically to an own row.
  Seeding one into the form attaches the folder's condition **directly** to the case on the first
  save — permanently, invisibly, on every case anybody edits. Inherited rows are passed to the panel
  as a separate read-only prop with no remove, reorder or drag. **Not covered by a test yet**; a
  mutation row says so.
  **Both lists are always sent, including as `[]`** — absent means "leave them alone" to the server,
  so detaching the last condition would report success and change nothing. `classificationValueIds`'
  rule, one field over.
  Reordering reuses §94's `resolveStepMove` and keeps the **▲▼ buttons** beside the drag handle.
  **Two findings from running it:** a created entry rendered as *"Unknown condition"* until a
  library list refetched (the panel now takes `known` — the entries created this session — as a
  rendering fallback), and **the second library read per panel pushed `CaseEditorPage.test.tsx` over
  the suite's load cliff at 640s on one test**; the resolver read is now gated on
  `value.length > 0`, which is also the right product behaviour. Bundle **+8.9 kB raw / +2.1 kB gz
  (+0.54%)**, entirely in the lazy route chunk. `#confirmed`

- **THE STEP EDITOR, AND THE RULE A PERMANENT BLANK ROW BREAKS (§94).**
  `features/cases/CaseEditorPage.tsx` — the action field is an **`Autocomplete` (`freeSolo`)** over
  `useStepSuggestions` (`api/queries/library.ts`, typeahead budget, `enabled` on a non-blank
  fragment, debounced **250ms per row** in `StepActionField`, which exists as a component because a
  hook cannot be called inside `steps.fields.map`). A pick **copies** the whole triple into action,
  testData and expectedResult and creates **no link** — the endpoint serves no id, which is what
  keeps it that way. ~~It is the deliberate opposite of `SharedStepPicker` beside it.~~ → **§239:
  there is nothing beside it.** The picker is deleted, and this corpus is the ONLY step-reuse
  affordance — the by-reference alternative it was contrasted with never resolved a reference.
  **Typing in the last row appends the next one, so `Steps.AddButton` is GONE** (a deleted locator,
  not just a control). Three things follow and each was a live defect:
  **(1)** `CaseSchema`'s "at least one step" counts **`liveSteps`**, never `steps.length` — the
  trailing blank row satisfies the old rule, so an empty case saved clean with a success toast;
  **(2)** `StepSchema.action` lost its `.min(1)`, which would have refused every save on that same
  row, and became a cross-field rule at `steps.N.action` (*a row carrying anything must carry an
  action*); **(3)** removing the trailing row was a **dead end** with no Add button —
  `ensureTrailingBlank` holds the invariant. (It also kept the shared-step insert *before* the
  blank, until §239 deleted the insert; the helper is unchanged and still has three other callers.)
  **`liveSteps` has ONE definition and two consumers** (the refine, and the submit's trim-then-drop)
  because the two halves of their disagreement are individually invisible.
  **`errors.steps.root?.message`, not `errors.steps.message`** — a refinement whose `path` is a
  field array lands on `.root`, and reading only `.message` had rendered the step panel's error as
  nothing at all since Phase 4: the form did not respond to the button and said nothing.
  Drag-and-drop is `DndContext` + `SortableContext` + a `SortableStep` **render-prop wrapper** (the
  row's JSX stays put, so a reordering change does not read as a rewrite); **the ▲▼ buttons are
  KEPT** as the keyboard/SR/touch path and both ends call the same `steps.move`.
  **`stepReorder.ts` is its own module on purpose**: the three ways a drag ends in nothing (outside
  the list, back where it started, on a stale id) are the only part of DnD that can be *wrong*, and
  they are unreachable from a component test — `move(i, i)` marks the form dirty and offers a new
  version of an unchanged case. Bundle cost measured, **+2.9% total and +0.03 kB on the entry
  chunk**: all of it lands in the lazy route. `#confirmed`
- **THE PLAN DETAIL IS ONE FORM WITH SEVEN TABS (§97 phase 4).** `features/plans/planDraft.ts`
  (pure: `draftFromPlan` · `dirtyTabs` · `planPayload` · `liveCaseRows`) + `usePlanSave.ts` +
  `useCreatePlanCase` + `PlanForm` inside `PlanStatisticsPage.tsx`. **The draft lives ABOVE the
  tabs and this reverses §93**: that screen unmounted the inactive tab, arguing a draft surviving a
  tab switch is one the author cannot see they made — under one Save, unmounting *discards* what
  was typed, so the state is hoisted and the **banner names which tabs are dirty** instead.
  `PlanForm` is mounted on **every** tab including Progress, which is what makes that true.
  **Comparisons are against the SEED (`base`), never the live query** — a background refetch would
  otherwise change what "dirty" means under a typing author and silently save against a newer
  `lockVersion`, which is optimistic concurrency switched off at the one moment it matters. A
  concurrent edit is a **409 that says reload**.
  **`planPayload` sends only the DIRTY tabs' fields.** Every list on `PATCH /plans` is three-state
  (absent = leave alone, `[]` = clear), so a whole-draft payload is a full replace dressed as a
  patch — `PlanEditDialog` avoided that by hand, one field at a time.
  **The save is two endpoints in a fixed order**: `POST /cases` ×N, then the `PATCH` carrying the
  ids just created. A case that exists outside a plan is findable; a scope entry naming a case that
  was never created cannot exist. Three outcomes, not two: `saved` · `casesFailed` (stopped
  partway, names the row) · `planFailed` (**the cases are loose in the project** and are named by
  key, or the author presses Save again and cannot account for them).
  **`useCreatePlanCase` is the THIRD idempotency shape in `cases.ts` and the key is the ROW's** —
  per-hook (`useCreateCase`) makes the server read rows 2…N as replays of row 1, per-call
  (`useCreateDraftCase`) gives up the retry protection a two-step save needs most. The key is
  minted with the row and survives a failed save, which is what makes pressing Save again safe.
  **The trailing blank row must never satisfy a non-empty check** (§94's refine defect): every such
  question goes through `liveCaseRows`, or the banner is up on every plan page ever rendered.
  **Three tabs can never be dirty** — Progress holds no draft, Business requirements is the linked
  page's own `PUT`/`DELETE`, Test artefacts writes itself.
  **Phase 5 put the editors on the tabs and DELETED `PlanEditDialog` with the header's Edit button.**
  `features/plans/PlanFieldsTab.tsx` is the Plan tab (fields + both `CriteriaEditor`s, bound to the
  draft, no RHF and no Zod — the form owns no submit). Two refusal paths, and they are different
  things: **`planProblems(draft)`** is what the *client* is certain about (only a blank or
  whitespace-only `name`, because it is the one required field on `TestPlanUpdateInput`) and
  disables Save **with its reason and the tab it is on**; everything else is the server's, arriving
  as `ApiError.fieldErrors` and landed on the control it names — `entryCriteria`/`exitCriteria` have
  no text field, so each list renders its own alert.
  **`PlanEdit.test.tsx` was rewritten, not deleted**, because every assertion in it is about the
  *payload* (`caseIds` absent not `[]`, no `met`/`actual`, the number coercion, the derived label),
  and the payload outlived the dialog.
  **Phase 6 finished the tabs with `PlanCasesTab.tsx`, and its model is REFERENCES.** A group (the
  unsectioned one, then the sections in author order) holds case ids, suite ids and tags — never
  cases — and its membership is a query the server re-runs on every read (D7′, validated against
  TestNG's `<test>` block). Ten pure helpers in `planDraft.ts` do the work: `ensureTrailingBlank`
  (returns the **same object** when satisfied, which is what lets `PlanForm` call it from an effect
  without looping), `typeInCaseRow` (appends the next blank only when the typed row was its group's
  **last** — §94's guard, so a paste appends exactly once), `add`/`rename`/`move`/`removeSection`,
  `add`/`removeReference`, `caseRowsIn`, `groupIds`.
  **A SECTION'S NAME IS ITS IDENTITY** — the contract has no section id and the whole list is
  replaced on every save — with two consequences worth knowing before touching this: `renameSection`
  must **move the section's unsaved case rows with it** (`NewCaseRow.section` is a name, so a rename
  that missed them files their cases in the unsectioned group), and a blank or duplicate name is a
  `planProblems` refusal rather than a server round trip.
  **A served `caseCount` is hidden while the tab is dirty**, never shown against a draft that has
  moved past it. **Deleting a section asks** what happens to its references (keep them in the plan ·
  remove them) instead of guessing — the plan's total moves in one case and not the other.
  `useProjectTags` (`GET /projects/{id}/tags`, shipped consumerless in phase 3b) is the tag picker's
  source; ~~the case editor's, `SuiteDialogs`' and `BulkActionDialog`'s tag controls **still pass
  `options={[]}`** and are the named follow-up.~~ → **all four read it since §102** (see the tag
  entry below).
  **A captured DOM node goes stale across a permission verdict** — `PermissionGate`'s denied branch
  wraps the control in a tooltip span, so when the answer lands React mints a **new** button and
  `waitFor(() => expect(captured).toBeEnabled())` waits for ever on a detached one. Re-query inside
  `waitFor`. Cost an hour in phase 5; it reads as a permanently-pending gate.
  **A test rendering this page now needs `SessionRecoveryContext`** (`useUnsavedWork` throws
  without it, by design) — `PlanEdit`/`PlanLifecycle`/`PlanForm` tests all stub it the way
  `CaseEditorPage.test.tsx` does. And **`as unknown as TestPlan` fixtures hid four required
  contract fields**: `tags`/`sections`/`scopeText`/`outOfScopeText` were missing from both existing
  plan fixtures and the page crashed on mount reading them — §65's cast class, third sighting.
  `#confirmed`

- **THE BUSINESS REQUIREMENTS TAB — A PAGE THE PLAN *ADOPTS*, READ-ONLY (§97 phase 5, D4′).**
  `features/plans/PlanDocumentPanel.tsx` is now three parts: the `DataRegion`, a **`LinkPagePanel`**
  (a picker over `useDocPages`, where a page another plan documents is **offered and disabled** —
  hidden, it reads as deleted) and a read-only **`DocumentBody`**. Writes are
  **`useLinkPlanDocument`/`useUnlinkPlanDocument`** (`PUT`/`DELETE .../plans/{id}/document`, costing
  **`doc:edit` and never `plan:edit`** — the write lands on the page's subject columns and nothing on
  the plan), both outside the form's Save. The unlink **keeps the page and says so in its label**;
  the mock's `DELETE` sets the subject to null rather than splicing the summary out, and its `PUT`
  answers **two different 409s** (this plan already has one · that page is another plan's), because a
  mock with one generic conflict lets a client ship one sentence for two situations.
  **Everything §93 put here is gone**: the in-place block editor (one page, one writer — it is
  `/docs/{pageId}/edit` under `doc:edit`), the Add-a-case bar, and `useCreateDraftCase` with it. The
  ~~`CASE_SECTION` heading match~~ is gone too, so D1's stranded-control failure mode is removed by
  removing the mechanism rather than by handling it.
  Historical (§93), still true of the cache and the 404: `features/plans/PlanDocumentPanel.tsx` +
  `usePlanDocument`/~~`useStartPlanDocument`~~/`useLinkBlock`/`useUnlinkBlock` +
  ~~`useCreateDraftCase`~~ + `keys.planDocument`; `PlanStatisticsPage` gained **Tabs** with the selection
  in the URL (~~`?tab=document`~~ → **`?tab=requirements`, and seven of them, since §97 phase 4**),
  the two halves ~~**unmounted rather than hidden**~~ → **the form is mounted on every tab now; see
  the seam above for why that reversed**.
  **`usePlanDocument` turns the server's 404 into `null`** — a plan with no document is the ordinary
  first state, and letting it reach the query client raises a global "Something didn't load" on a
  screen whose correct rendering is an invitation.
  **`blocksOf` is the ONE place an edit payload is built**, because the field that must never be
  dropped is `id`: a block sent without one is deleted and recreated and every link to it is
  destroyed silently.
  **`useCreateDraftCase` exists because `useCreateCase` holds ONE idempotency key per mount.** That
  is right for an editor (one intent, one case) and catastrophic for a bar somebody adds ten cases
  from — every case after the first would come back as the first, silently. A key per **submit**.
  **The "Test cases" section is found by HEADING TEXT** (`CASE_SECTION`), the screen's known weak
  point; renaming it moves new cases to the end of the document with an info alert rather than
  losing them.
  **Adding a case is THREE requests and the order is load-bearing**: case, bullet, link — the case
  first because it is the durable half, and the failure message says the case may exist anyway.
  **No component tests.** `#confirmed`

- **`ReviewPanel` carries TWO permission tiers and they are different keys (§79).** The comment box
  and Reply ask **`case:comment`** for *every* subject type — the catalogue has one comment key where
  it has three review keys, because commenting is the bottom tier §6b built for the client who
  reviews coverage without write access — while **Resolve** asks the subject's own
  `REVIEW_PERMISSION[subjectType]`, as the verdict buttons do. **Do not "simplify" the two into
  one:** a Viewer must be able to write and unable to resolve, and the panel's tests hold a
  permission set the seeded catalogue cannot produce, which is the only place that pair is
  observable (§71's finding from the good side). Both controls are **disabled, never hidden** (§31).
  A **reply carries no Reply button** — the server refuses a reply to a reply, so rendering one would
  be an affordance the product does not have — and a **closed review** renders the conversation with
  no write affordance at all. `#confirmed`
- **`ReviewPanel` renders ASKED and SPOKE as two lists and they must not be merged (§80).**
  `assignees` is who was *asked* (§6g's reviewer assignment); `participations` is who *spoke*.
  Merging them is wrong in both directions: somebody may give a verdict without being asked, and a
  request nobody has answered — the row a lead opens this panel to find — would render as "a person
  with no verdict", which is also what somebody never asked looks like. Both the chips and the picker
  ask the subject's `REVIEW_PERMISSION`, **not** `case:comment`: the bottom tier does not fill other
  people's queues. The picker is built from `useProjectMembers`, a **subset** of the server's rule
  (a Company Admin is assignable and not offered) — narrower never produces a 400, wider would be a
  form a user fills in correctly and cannot submit. `useAssignReviewer` takes
  `{userId} | {teamId}` as a **union**, so the server's neither/both field error is unrepresentable.
  `#confirmed`
- **A barrier on a MUI Select's test id is NOT a barrier (§80).** `Review.Assign.Choice` sits on the
  Select's **display `div`** via `selectTestId`, which is not a form element — so
  `expect(...).toBeEnabled()` passes immediately and the click lands while `useCan` is still pending
  and the control still disabled. Four tests failed this way on their first run (§66's finding, §71's
  hidden-input trap, one line). **Barrier on something derived from the resolved gate instead**:
  `useProjectMembers` is `enabled` on the gate's `allowed`, so the `/members` request firing at all
  is the proof. Same family: MUI's default Chip delete icon has **no identifier**, so pass
  `deleteIcon` explicitly when a test must click it. `#confirmed`
- **A fixture factory must NOT end `as <ContractType>` (§79, and it paid off in §80).** Adding
  `assignees` to `Review` was **one build error in one place**, where §79's cast turned eleven tests
  red at runtime with zero type errors. `ReviewPanel.test.tsx`'s did, so
  adding a required field to `Review` reddened all eleven of its tests **at runtime** with **zero
  type errors**. §65's finding (five files mocking `/me/permissions` through `as never`) in a second
  file. Declare the return type and let the object be checked, so the next contract change is one
  build failure rather than a page of confusing runtime ones. `#confirmed`
- **`useProjectSettings(projectId)` gates §6g's whole reviewer UI, and it is read by ARTIFACT screens
  rather than only by the settings page (§77).** §6g's opening requirement is that a project which
  never turns review on shows *"no extra UI at all"*, so `ReviewPanel` reads the settings and
  **returns `null`** when review is off for its subject type — the check lives **in the panel**, not
  at its three call sites, so a fourth screen that mounts it cannot forget. The endpoint is gated on
  **project visibility, not `project:settings`**, precisely so a Viewer's client can make that
  decision. `reviewEnabledKeys` is `satisfies Record<ReviewSubjectType, keyof ProjectSettings>`, so a
  fourth subject type is a compile error rather than a silently hidden panel.
  **While the settings are pending the panel renders nothing** — deliberately *not* §65's
  "not yet is not no" rule, which is about permissions: a panel that flashes in and vanishes is worse
  than one arriving a beat late, and a failed settings read is not evidence review is on. `#confirmed`
- **§6g's GATING rules are a THIRD panel on `ReviewSettingsPage`, and deliberately not two more
  switches under "Where review applies" (§82).** They bind on a project with review switched off,
  because §6g's opt-in suppresses the reviewer apparatus and not the status ladder — grouping them
  with the opt-ins would tell an admin the opposite in the one place they decide whether to turn
  them on. **The save is the risk, not the switches**: the endpoint is a `PUT` of all eight values
  onto a server that reads an absent boolean as `false`, so a field listed on `ProjectSettings` and
  omitted from the submit does not fail — it **switches that setting off on the next save of the
  form**, with a success message. `ReviewSettingsPage.test.tsx` (**new in §82; §77 shipped the
  screen with no test file at all**) asserts the body as an **exact object**, because
  `toMatchObject` passes with a field silently dropped. The mock's `saveProjectSettings` takes the
  whole value, which is how `npm run build` caught the handler forgetting both. `#confirmed`
- **THE RUN DIALOG SCOPES BY PLAN *OR* SUITES/TAGS/CASES SINCE §99, AND THE PLAN IS OPTIONAL.**
  `RunCreateDialog` has four scope controls; the three arms are sent as-is and **resolved by the
  server**, which is the whole point — a dialog that resolved locally would pin a checklist
  assembled at a different instant from the executions. The arms **substitute** for the plan's
  scope, so the plan select's helper text changes once any arm is filled (`watch`ed, not computed at
  submit). **No total is computed across the arms** — they overlap, and a client-side sum is wrong
  in the direction that makes a run look longer than it is.
  **`Runs.Create.Submit` is NO LONGER disabled in a plan-less project**, and the old
  `toBeDisabled()` assertion is why the row is here: it passes against a dialog that never grew the
  arms, so it would have stayed green while the feature was unreachable in exactly the project that
  needs it first. The live assertion is `toBeEnabled()`.
  **`caseIds`, `suiteIds` and `tags` now have controls**, so their field errors land **on** them and
  no longer in the banner — only `configurationId` and `assigneeId` are still strays.
  **`TestRunInput` is `S['TestRunInput']` since §99**, with one documented narrowing:
  `openapi-typescript` renders a `default:`-carrying property as **required**, which is true of a
  response and false of a request body, so `overrideApprovalGate` is made optional again in
  `api/queries/runs.ts`. Expect the same correction on any future defaulted request field.
  The mock resolves through **`runScopeCaseIds` = `resolveArms`**, the plan's own union — and that
  function **excludes `DEPRECATED` while the server does not**, a pre-existing divergence queued as
  browser row M4-40 rather than fixed.
- **A gating refusal is a 409 that must OFFER THE WAY OUT (§82).** `RunCreateDialog` and
  `PlanStatisticsPage` each render the server's sentence in place and put §6g's lead override beside
  it — `ui-rules`: an error naming an action the UI does not offer is a design bug. **Offered only
  AFTER the refusal, never on the happy path**, so a standing "ignore the approval rules" checkbox
  does not normalize the exception §6g wants to stay exceptional. **Disabled, not hidden** (§31) for
  a caller without `case:approve`/`plan:approve`, so the person who has to go and ask can see the
  way out exists. The run dialog keys on **`error.status === 409`** (the only 409 that endpoint
  answers — everything else there is a field error); the plan page keys on **the requested target**
  being `ACTIVE`, never on matching the server's sentence, which would be a second copy of the rule
  in a string. The retry **re-validates the form** rather than replaying the first attempt's values.
  `#confirmed`
- **The status ladders and the review apparatus are separate on the client too.** Turning review off
  hides the panel and changes **nothing** about the Draft/In review/Approved controls on the suite
  tree, plan header or bulk dialog. `#confirmed`

- **A FILE DOWNLOAD CANNOT BE AN `<a href>` IN THIS APP, AND THE FAILURE IS SILENT (§89).** The
  session is a bearer token in a header — `SecurityConfiguration` is `STATELESS` with **no cookie
  anywhere** — so an anchor pointed at `/api/v1/…` sends no credential and answers **401** while
  rendering as a working link. `api.get` cannot take a server-supplied URL either: `request`
  prepends `BASE` and `ReportJob.downloadUrl` already carries `/api/v1` (the **server** composes it,
  so the client holds no second copy of the routing — §67 on `Evidence.url`), giving
  `/api/v1/api/v1/…`; and `request` ends in `res.json()`, which parses a Markdown report as JSON.
  **`api/client.ts`'s `fetchArtifact`** is the seam — URL verbatim, blob + `Content-Disposition`
  filename, long budget — and **`lib/download.ts`** (`saveBlob`, `reportFilename`) is the DOM half,
  split so the transport is testable without a document. Its **same-origin guard is not defensive
  programming**: the URL is server data and the caller's token goes with it, so an absolute one
  hands the token to another host — **and it has no test** (a queued mutation row says so). **The
  sibling defect is live and unfixed**: `EvidencePanel` renders `Evidence.url` as a plain anchor,
  so every evidence download is a 401 in live mode; the fix is a different rendering (evidence is
  served `inline`, a report `attachment`), not this helper. `#confirmed`
- **The reports screen shows what THIS TAB asked for, because nothing lists jobs (§89).** The
  pipeline's whole surface is `POST /reports/generate` + `GET /reports/jobs/{jobId}`; job ids live
  in `ReportsPage` component state and a reload loses the links while the reports and artifacts
  still exist. **Do not "fix" this with job ids in the URL or in storage** — a list endpoint is the
  server's, and a client-side register is a second drifting copy of a table that already exists
  (§81). `useReportJob`'s `refetchInterval` returns **`false` on COMPLETED *or* FAILED**, through the
  exported `isJobFinished` the cards read too: polling on `!== 'COMPLETED'` never stops on a failure
  and renders as a report permanently about to be ready. `useGenerateReport` **seeds the 202 into
  the cache** (`setQueryData`), so the new card shows `Queued` rather than a skeleton for a row the
  client already holds. The download button keys on **`downloadUrl` being present, never on the
  status** — they agree today, and the URL is the one that cannot produce a broken link. `#confirmed`
- **The format picker offers PDF on purpose, and the type picker offers one report on purpose
  (§89) — opposite reasons, both worth keeping.** `format` is a closed contract enum offered **in
  full including the format every deployment refuses**, because *"the enum is not narrowed to what
  is installed: what a deployment can render is a property of that deployment, and the refusal names
  it"* — a client-side list of renderers is wrong in both directions, and the **400 field error was
  engineered to land on the picker** naming what the server can produce. `type` is an **open string**
  in the contract, so the dialog offers only the set it can **build a parameter form for**; the run
  picker IS `run_summary`'s `parameters`, and `runId` is validated **inside the worker**, so omitting
  it buys a job that spins and then fails. `parameters` is `additionalProperties: true`, which means
  **neither the generated types nor the contract checks can see a `runId` sent at the top level** —
  the dialog's test asserts the body with `toEqual`, not `toMatchObject`, for exactly that. Rail
  item **ungated** (a recorded departure from the plan): `V3` grants `report:view` to five of six
  built-ins and the sixth is API-token only, so the gate would refuse nobody. `#confirmed`
- **The mock can now produce a FAILED job, and that is deliberate infrastructure (§89).**
  `mocks/handlers/reporting.ts`'s `advance` resolves `parameters.runId` against `db.runs` and fails
  with the worker's sentence when it cannot — the server's own arrangement (report parameters are
  worker-validated), and the only way `FAILED` is reachable without a backend. Before it, the state
  the M2.5 plan names as this screen's characteristic defect could only be seen in production.
  *"Build the fault switch before the feature it proves"*, applied to a state machine. `#confirmed`
- **A poll test is vacuous unless the wait exceeds the interval, and the pair is what proves it
  (§89).** `ReportJobCard.test.tsx`'s first draft waited 250ms against a 2000ms interval and passed
  against a poller that never stopped. It is now **one test with two halves** — a RUNNING job whose
  request count must *increase* over 1.5 intervals, a FAILED one whose count must *not* — with the
  interval named and the wait derived from it. Real time, not fake timers: TanStack's interval,
  `userEvent`'s advance-on-act and `waitFor`'s scheduler all read the same clock. `#confirmed`

- **THE STORED REPORT MODEL IS THE ONE SHAPE IN THIS CLIENT WITH NO GENERATED TYPE (§90).** The
  artifact route serves five formats' bytes and is declared **binary** in `openapi.yaml`, so
  `schema.d.ts` cannot describe this body — the contract carries the shape in **prose** on
  `ReportJob.modelUrl` and the authority is `ReportDocument.java` + `ReportModel.java`. It is
  therefore **parsed with Zod in `lib/reportDocument.ts`, never cast**: a cast puts the drift three
  components deep, where a half-recognised document renders as a report with **no sections** — which
  is exactly what a selection that matched nothing looks like. The two directions are deliberate and
  opposite: **unknown fields are dropped** (an artifact outlives the deployment that wrote it, so a
  *newer* release's document must still open) and **missing or mistyped known fields throw**
  (`data-fetching`: a parse failure is an error, never an emptiness). A cell arriving as a **number**
  throws too — `ReportModel`'s "every cell is a STRING" held at the only place this client can hold
  it. `headerBlock()` reassembles the server's provenance→selection→metadata block **as one
  function**, because this screen is the fourth renderer of it and the furthest away; both tests over
  it assert an **exact ordered list**, since `toContain` per label reddens nothing when a line goes
  missing. `#confirmed`
- **THE ON-SCREEN REPORT VIEW COSTS `report:export`, AND THAT IS A FINDING (§90).** The model is an
  artifact, so it is served through the **download** route, which gates on `report:export` — while
  §6e's split (and the custom role §87 built a whole phase to express) is *may read on screen, may
  not carry off*. A `report:view`-only persona can watch a job reach `Ready` and be refused the
  report it watched being made. **Not papered over**: `ReportViewPage` branches on `status === 403`
  and says the on-screen view needs the download's key, because `describeError`'s generic 403 makes a
  design defect read as an ordinary permission error. The fix is the **server's** — serve the `JSON`
  artifact under `report:view`, or give the view its own route. Live-only (mock mode holds every
  key); browser row 31. `#confirmed`
- **`ReportView` computes NOTHING, a recorded departure from the plan's F2 line (§90).** It says to
  reuse `lib/results.ts`; there is no arithmetic, because every cell and timestamp is pre-formatted
  so that "what you see is what exports" is a property rather than a promise. **Do not add a
  percentage to this screen** — it would be §65's "two contradictory percentages" across two formats
  of one report, where nobody can see both at once to notice. The one number is the bar width
  (`barPercents`), carrying `HtmlReportRenderer`'s two defects: scaled to the largest value in its
  **own series** and never a total, and an **all-zero series draws no bar** rather than the
  full-width one `width: NaN%` renders as. `#confirmed`
- **`fetchArtifactJson` is the model's transport, beside `fetchArtifact`, through one
  `artifactResponse` (§90).** The same-origin guard is why they share: it is the only thing between a
  server-supplied string and the user's bearer token. **§89 shipped it untested and said so**;
  `api/artifacts.test.ts` now drives **both** readers over five refused URL shapes, and the pair is
  the point — a guard tested on one would leave the newer reader, the one that runs *without a
  click*, unprotected. `DownloadReportButton` is the extracted download (one authenticated download,
  two call sites — `ui-rules` §8); its failure testid is `<testId>.Error`. `#confirmed`

- **TanStack Query owns all server state**; no server data in any client store. `src/api/queries/` is
  the data module — one `keys` factory (`queries/keys.ts`), per-endpoint timeout budgets. `#confirmed`
- **Client state:** session in Context; **filter state belongs in the URL** (`useSearchParams`).
  *Known deviation:* `features/artifacts/ArtifactsPage.tsx` keeps its plan filter in `useState`, so a
  plan's register cannot be linked to — recorded in handoff §61, not fixed. `#confirmed`
- **`app/Providers.tsx` nesting order is load-bearing** and documented in the file: the query client
  is constructed *innermost* because its global error handlers consume the notification and
  session-recovery providers.
- **`components/PermissionGate.tsx`** is the one place a lacked permission becomes a **disabled
  control with a why-tooltip** (§31, disable-don't-hide). Render prop, not `cloneElement`; the denied
  branch wraps in a span because MUI kills pointer events on a disabled control. **It gates on a
  permission KEY only.** ~~`/me/permissions` carries no qualifiers, so a scope- or author-restricted
  grant reads as fully held (§61)~~ → **superseded in §65: the response is now `[{key, qualifier}]`.**
  `PermissionGate` still asks the key alone, which is right for it — a *qualified* affordance asks
  `useCan(projectId).canOnScope(key, rowIsPlanScoped)` in the component body, mirroring the server's
  `canDoOnScope` arm for arm rather than reinterpreting the qualifier. `Gate.pending` is exposed for
  the one thing the fail-closed verdicts cannot express: **"not yet" is not "no"**, and using a
  pending verdict to *act* (rather than to disable) demotes every caller during the load — which is
  exactly what shipped into §65's first draft and what two unrelated tests caught.
  **Mocking `/me/permissions` in a test: use `src/test/permissions.ts`'s `heldPermissions()`.**
  The spies cast through `as never`, so a response-shape change breaks them with **zero type
  errors**, surfacing as `pointer-events: none` three assertions later. `#confirmed`
- **It must NOT be used inside a MUI `Select`** — `Select`
  clones its *direct* children to inject `role="option"`, `data-value` and the selection handler, so
  a wrapped `MenuItem` renders as an inert `role="menuitem"` that cannot be chosen at all, and
  carries no `aria-disabled` either, so a testid-based test reads it as "enabled". Inside a `Select`,
  read `useCan(projectId)` in the component body and pass a plain `disabled` prop
  (`features/cases/BulkActionDialog.tsx` sets the pattern). Cost the whole approve feature in §62,
  with every gate green. `#confirmed`
- **`api.upload` is the multipart seam** (`api/client.ts`), separate from `api.post` on purpose:
  a `FormData` body must **not** carry a hand-set `Content-Type` — the browser generates the
  boundary, and setting the header loses it, after which the server cannot parse a single part and
  answers 400 for a request that looks correct in devtools. `request` now omits the JSON
  content-type when `init.body instanceof FormData`. Uploads default to the **long** timeout
  budget, not `interactive`. `#confirmed`
- **`EvidencePanel`** (`features/runs/`) is the second author-qualified call site after
  `CaseDetailPage`, and the pattern to copy: `authorId={item.createdBy?.id ?? null}` — **`?? null`,
  never `?? undefined`**, since `PermissionGate` switches on `!== undefined` and `undefined` means
  "not author-qualified", which hands every row the unqualified verdict. Its test uses a
  `permissionsResolved()` barrier for §66's reason: `useCan` is fail-closed while pending, so
  `waitFor(toBeDisabled)` is satisfied by the loading state and proves nothing. `#confirmed`
- **Forms:** react-hook-form + zod via `zodResolver`; each dialog owns an `applyServerError` that maps
  the RFC-7807 `errors` map onto its fields and falls back to a root alert via `describeError`.
  `mode: 'onBlur'`; **focus on open, never `reset(defaults)`** — a reset keyed on an object literal
  re-runs each render and wipes a failed submit's errors. `#confirmed`
- **The MUI slot spelling is `slotProps`, and getting it wrong has now cost FOUR slices** (§71
  extracted `lib/muiTestIds.ts` to end it, §74 recorded a third occurrence via `PlanCreateDialog`,
  §77 a fourth). `muiTestIds` owns the *shape* of the object; **the prop name is the part it cannot
  own**, which is the whole gap — a file written from an older sibling reintroduces `inputProps=` and
  MUI v7 rejects it. `Switch`/`Checkbox` → `slotProps={inputTestId(id)}`; `TextField` →
  `slotProps={{ htmlInput: { 'data-testid': … } }}`; `Select` →
  `SelectDisplayProps={selectTestId(id)}` or `slotProps={{ select: { SelectDisplayProps: … } }}`.
  **Caught by `npm run build`, never by the test runner.** `#confirmed`
- **`data-testid` goes on the INPUT, not the wrapper**: `slotProps={{ htmlInput: { 'data-testid': … } }}`.
  Putting it on the `TextField` lands it on the root div and `userEvent.type` silently does nothing —
  cost six tests failing for one reason in §61. Tests reach a MUI select's combobox via
  `.closest('.MuiInputBase-root')` then `getByRole('combobox')`. `#confirmed`
- **Errors are centralized:** `lib/describeError.ts` (copy + `isTransient()`), `app/queryClient.ts`
  (the single raise point; opt out per-call with `meta: { handled: true }`),
  `components/ErrorBoundaries.tsx` (root / route / region tiers). `DataRegion` owns the
  loading/loaded/failed/empty/stale matrix.
- **The dev fault seam is `lib/devFaults.ts`, called from `api/client.ts` (§107).** `window.tcmsFaults`
  — `.status(503,30)` `.hang()` `.expire()` `.drift()` `.only(path)` `.off()` — is how the resilience
  claims get *seen*, and it now injects in front of `fetch` against the **real** backend instead of
  inside an MSW handler against the mock. Both call sites guard on a **literal** `import.meta.env.DEV`;
  that literal is load-bearing, not stylistic — routing the check through an imported constant is what
  once shipped the mock layer as a 485 kB production chunk, and the build stays green either way.
  `deploy.yml`'s `web` job greps `dist/assets/` for `tcmsFaults` to catch the regression. The module
  must stay **free of top-level side effects** or tree-shaking cannot drop it. `#confirmed`
- **Shared display layer, not per-page:** `lib/labels.ts` (each `satisfies Record<Union,string>`, so a
  new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`,
  `lib/permissions.ts`, `Identifier` for monospace, `ScrollX` for wide content.
- ~~**Mock API is stateful** — `mocks/db/` + `mocks/handlers/*.ts` read *and write* a cloned fixture
  store, so round-trips land in lists. Handlers are written from the **server's** rules, not from what
  one client sends (§52). Resets on reload.~~
  → **DELETED IN §107. `src/mocks/` does not exist.** Every mock finding recorded in the slice
  entries above is history, not current state: there is no fixture store, no handler to keep honest,
  and no `?mock` lane to reproduce a bug in. Dev runs against the real backend.

  **Read the entries anyway, for the failure class rather than the code.** The recurring defect was
  never a bug in a handler — it was *a second implementation of the server's rules that agreed with
  the client instead of the server*, and it produced §52 (`entries`/`runs`), §81 (a reviewer inbox no
  deployment could produce), §98 (a suite count and a grid filter answering different questions) and
  several more, every one of them typechecking and testing green. That class is what deleting the
  layer removes. The same class returns the moment anything re-derives a server rule client-side.

  **The fault switch survived the deletion**, moved out of MSW into `lib/devFaults.ts` behind
  `api/client.ts` — see the seam below.
  **A mock generator could invent a whole feature — §81, the most expensive instance.**
  `mocks/db/workItems.ts` drew a board card for every case whose `lifecycleStatus` was `IN_REVIEW`,
  in the `IN_REVIEW` column, assigned to the case's **owner** — while the server produced no review
  cards at all and, once it did, refuses the artifact's own submitter as a reviewer. Mock mode
  therefore showed a reviewer inbox no deployment could produce, to the wrong person, from Phase 1 to
  §80, and it looked finished. Nothing could catch it: `kind: REVIEW` is legitimately in the contract
  (put there in §53 ahead of its generator), and a page test supplies its own props. Hence
  **`mocks/db/workItems.test.ts`, the first test asserting the MOCK's rules rather than a
  component's** — the pattern to copy whenever a mock module holds *logic* rather than storage.
  **It caught a second defect in the same module in §83.** `db.runs`, `db.cases` and `db.plans`
  carry **no `projectId`** — the contract puts the project in the *path* for all of those endpoints,
  so the wire shapes have no such field and neither do the fixtures. The execution and maintenance
  generators therefore drew the whole seeded repository onto **whichever project's board was asked
  for**, stamped with that project: already a 404-on-its-key on the second project's board, and on
  the cross-project board the same card twice with the **same React key**. `workItems()` now gates
  both on `projectId === db.projects[0].id` — the repository belongs to the project that seeds it,
  said once rather than inferred per generator. `personalBoard(userId)` mirrors
  `BoardService.personalBoard`: the visible set is `db.projects` (**the same list `GET /projects`
  answers**, one source so the two cannot disagree), and the personal filter runs **after** assembly
  for the server's reason. `GET /work-items` mirrors both refusals — **400** for projectless +
  `mine=false`, **404** for a project id nobody can see (it used to fall back to `projects[0]`, so a
  stale bookmark rendered another project's board under a foreign id). The fixture seeds **one task
  in the second project assigned to the current user**, without which My Work in mock mode is
  byte-identical to the first project's personal board (§72's rule).
  **And it caught the FIXTURE in §85, which is a third kind of finding from the same file.**
  `teamBoard(teamId, projectId?)` mirrors the server (team contributes only a set of assignees;
  membership expanded at read time, **direct members only**; filter after assembly; unknown team →
  `undefined` = 404, no members → `[]`; the empty-member shortcut **inside** the resolved team and
  **after** the handler's project gate, or "empty team + unreachable project" becomes a 200 that
  confirms the project exists). `personalBoard` and `teamBoard` share one **`assemble()`** — the one
  place the visible project set is named (§58's E1). The hole it exposed: every fixture card was
  assigned to `users[0]` or `users[1]`, **both in Payments Squad**, so Checkout Squad's board was
  empty and an implementation ignoring membership — or quietly answering the personal board — passed
  everything. The fixture now seeds **one OLB task to Priya Nair (`users[2]`, Checkout Squad, not the
  signed-in user)** and the assertions come as a **pair**, each half stopping the other being vacuous.
  ***What mock mode structurally cannot prove:*** `db.projects` **is** the caller's visible set, so
  the reader-bound — the whole of §84's decision — is structural here, not demonstrated. Live only.
  `#confirmed`
- **`BoardPage.linkFor` is where a card's key goes, and it keys on `sourceType` (§81) and on the
  CARD's project (§83).** Every card routed to a case search until the inbox drew the first cards
  about **suites and plans**; a review of `TP-5` sent there renders "no cases match", which reads as
  the plan having been deleted. A case goes to the case detail, a plan to the plan detail, a **suite
  to the case list filtered to it** — a suite has no page of its own and is the one §6g subject type
  with no screen anywhere. `EXECUTION` keeps the search fallback deliberately: its route needs a run
  id the card does not carry. **The project comes from `item.projectId`, never from the route** —
  §81 left it as the board's own, which is the same value on any project board and therefore a
  defect no test could see until §83's cross-project board existed. All four of §81's link tests
  still pass against the old signature; only `MyWorkPage.test.tsx` reddens. `#confirmed`
- **`Board` takes NO project and NO `mine` (§83) — it renders whatever cards it is handed.** That is
  what let §6l's cross-project **My Work** screen (`features/board/MyWorkPage.tsx`, route
  `/my-work`) reuse it rather than grow a second board, per `ui-rules` §8. `crossProject` is
  presentation only: it stamps each card with its `projectKey`, which is noise on a board that is
  already one project's. The screen deliberately has **no Everyone chip** (the server answers **400**
  to a projectless board without `mine=true` — "Choose a board", refused rather than narrowed) and
  **no New task** (a create needs a project it does not have). It never 404s; `[]` is the normal
  state of a member with no grants. Rail item sits **above the project switcher**, the only one that
  renders with no project resolved. `#confirmed`
- **§6l's THREE boards are three screens over ONE `Board` and ONE operation (§85 completes them).**
  `/projects/:projectId/board` (§53) · `/my-work` (§83) · **`/teams/:teamId/board` (§85)**.
  `projectId` narrows *where*; `teamId` and `mine` narrow *whose*; the response shape and sort are
  identical, which is why there is no second component and no second path. **The team board's route
  is deliberately NOT under `/admin/teams`**: every affordance on that screen is gated on
  `company:teams` and **the team board is gated on nothing at all** — §6k makes the org chart
  readable by any company member, and §84 declined to invent a key (a key seeded to nobody is a 403
  nobody can grant out of). Filing a screen every member may open under a prefix reading
  "administration" states the opposite of what is true. Same reason the **Board** link on
  `TeamsPage`'s rows sits **outside** the `PermissionGate` wrapping Members/Edit/Delete rather than
  inside it as a fourth button — that one line of JSX *is* the gating decision, and the mistake
  compiles and renders identically. **The cards are bounded by the READER's project grants, so two
  people see different boards** — therefore **no count, capacity figure or percentage may be
  rendered** on that screen; a number from a partial view reads as canonical and is wrong for
  everybody but its reader. `useTeamBoard` sends **only** `teamId`: `teamId`+`mine=true` is a 400
  (and is *unobservable in the body* for a caller on the team, so a chip would look like a filter
  that silently did nothing), and `teamId`+`projectId` is legal — §6l's **swimlanes by member**,
  a layout not a filter, answered by the mock and built by no screen. **The 404 is worded by the
  CLIENT**: the server's is empty-bodied on purpose (§6t), so `ApiError.detail` falls back to the
  status text and `describeError` renders "Not found — Not Found". `TeamBoardPage` keys on
  `status === 404` above the region — safe *there* because the hook sends no `projectId`, so the
  other 404 arm is unreachable — and keeps it visibly distinct from `200 []`, which is a reachable
  empty board. The team **name** comes from `GET /teams` (declares no 403) and is **not** in a
  `DataRegion`: a board titled "Team board" because the org chart failed beats one that will not
  render. `#confirmed`
- **Every board key shares the `work-items` root, and the move/create hooks invalidate the ROOT
  (§83).** The same card is on both boards, so keying an invalidation to the board you were looking
  at leaves the other holding a card in the column it just left. `useMoveWorkItem()` takes no
  arguments (`PATCH /work-items/{id}` names no project either); `useCreateWorkItem(projectId)` lost
  its `mine` — which had also meant a task created under the "Everyone" filter left the `mine=true`
  cache stale. `keys.myWork` is `['work-items','ALL',true]`; `'ALL'` cannot collide with a project id.
- **Contract-hygiene tests are generated per call site** — `api/contractOperations.test.ts`,
  `contractParameters.test.ts` (drives the TS compiler, not a regex), `apiResponseTypes.test.ts`
  (refuses an inline literal at any `api.*` call site). A new endpoint is covered automatically,
  **which means the suite's test count moves when you add one** — an unexplained change in the total
  is a report about these checks, and chasing one in §70 is how the next note was found. ~~A new
  endpoint is covered automatically~~ was true only of the verbs the checks knew: both hard-coded
  `['get','post','patch','put','delete']` *"as spelled on the client"*, and the client spells it
  **`del`**, with an `upload` that was absent entirely — so every delete in the app and the evidence
  upload, **eight call sites, were never checked** from §53 to §70. The verb set is now derived from
  `client.ts` by `api/contractIntrospection.ts` (`apiMethods`, mapping `del`→`delete` and
  `upload`→`post` because path items are keyed by the OpenAPI spelling), and `unresolvedApiVerbs`
  makes an unreadable verb **fail** rather than narrow the check. **Do not re-introduce a literal
  verb list**, and note that the vacuity guards in both files are by *count* — they cannot see a
  whole category going missing, which is exactly what happened. `#confirmed`
- **Fault injection** (dev only, browser console): `tcmsFaults.status/hang/expire/drift/only/off`.
  Faults live in module state, so arm them *after* load.
- **The rail has TWO widths since §91, and the drawer has only one.** `RailContent` takes
  `collapsed` + `onToggleCollapse`; the permanent rail (232px ↔ **64px**, `RAIL_WIDTH_COLLAPSED`)
  passes both, the small-screen `Drawer` passes **neither** and is always expanded — collapsing a
  temporary drawer saves nothing and takes the labels from the users with the least room to guess at
  icons. Collapsed, `RailLink` drops the label text and **must** carry `aria-label` (an icon-only
  link with no name is announced as its href; the `Tooltip` is a hover affordance and reaches no
  screen reader), group headings become `<Divider>`s, and the switcher becomes an `IconButton`
  showing the project's **key** plus a `Menu` — the key, not a folder icon, because *which project
  am I in* is what a 64px rail is likeliest to drop. The preference is `safeStorage`
  (`tcms.nav.railCollapsed`), read in the **initial `useState`** and not an effect, or the rail
  renders expanded for one frame and snaps shut on every page load. `Nav.Rail` carries
  `data-collapsed`. `#confirmed`
- **`AppShell.test.tsx` exists as of §91 — the shell's first test file.** Rendering the shell needs
  `TestProviders` with a **`userId`** (the `TopBar` calls `useAuth`, which throws without a
  provider), and — the trap — a `waitFor` on the **project group** (`Nav.Link.Testcases`), never on
  `Nav.ProjectSwitcher` or the ungrouped items: those three render *before* `/projects` answers, so
  waiting on one returns while `groups` is still `[]` and every project-scoped assertion fails on a
  rail that was merely early. `#confirmed`

## Conventions observed

- Comments carry the **why**, and often the failure mode a rule prevents — unusually dense and
  load-bearing. Match that register.
- Test files state, in a header, the specific failures they exist to catch, and are **mutation-checked
  rather than watched to pass**.
- MUI `theme` + `sx` as the single styling authority. **No Tailwind.** Tokens are CSS custom
  properties in `index.css` bridged into `createTheme`.

## Known unknowns

- E2E / page objects are the `qa-ui-typescript` peer's; none exist here.
- ~~**`/reports` is the only dead contract left on this side**~~ → **built in §89 (F1).** The
  screen, the generate dialog and the polling card exist; ~~**F2 (the on-screen report view) is the
  outstanding slice**~~ → **built in §90**, ahead of R6 rather than after it. **R5 has not built the
  definition writer**, so `GET /reports` answers `[]` on every deployment and the panel's empty
  state says so; there is deliberately **no Save control** (`ui-rules`: no Add button whose endpoint
  does not exist). **There is no operation that lists jobs**, which is the screen's largest
  limitation — see the seam below.
- Lighthouse / Core-Web-Vitals never measured (handoff §5); `CaseListPage` is over its route budget
  with the reasoning recorded in `vite.config.ts`.

## Session log

- 2026-08-14 · EXTEND · **The panels nothing looked at** (§201) — §198's largest opened instrument
  item: a test whose render tripped an error boundary fails unless it calls
  `renderingIntoAnErrorBoundary()`. **The population is zero**, so it was measured against §198's own
  instance instead — restoring the prefix arm in `CaseDetailPage.test.tsx` reddens 10 tests and names
  `CaseDetail.Versions.Region`. **The finding to carry: a region whose *fetch* failed is still
  invisible**, because `createTestQueryClient` has no error handlers (see the §201 entry above).
  **1335/1335 across 100 files** (+5, +1 file) on `shanks`, build and lint clean. No queue rows.
- 2026-08-12 · EXTEND · **The claim's control** (§174 phase 2) — one `LinkRelationshipControl` on
  both link panels over two new PATCH endpoints. **The finding to carry: a MUI `Chip` cannot carry
  a permission gate** (see the §174 entry above). **1251/1251 across 96 files** (+8), build and lint
  clean. Browser rows `§174 RL-1`…`RL-5` recorded and **not run**.
- 2026-08-12 · EXTEND · **One confirmation, two ends of one act** (§173) — §172's own recorded gap,
  taken as the **pair** rather than one side. New: `components/UnlinkDialog.tsx`, `lib/links.ts` +
  its pure test; both link screens route Remove through the dialog. **No contract change.** The
  decision worth carrying is why §172 left it: a confirmed Remove on one screen beside an
  unconfirmed one on the other is a *worse* state than two unconfirmed ones, so `ui-rules` §4 was
  satisfied at §8's altitude — one component — rather than at the site. `describeLinkTarget` is a
  second implementation of §169's switch-on-`targetKind` rule, and its test feeds an `EXTERNAL_URL`
  row with git fields populated because that is the only assertion that separates the two.
  **1243/1243 across 96 files** (+10, one new test file, from 1233/95), build and lint clean.
  Browser rows `§173 UL-1`…`UL-3` recorded and **not run**; `§172 XM-5` corrected, not appended to.
- 2026-08-12 · EXTEND · **The block-link screen** (§172) — §169's recorded gap: `useLinkBlock` and
  `useUnlinkBlock` had **no caller anywhere in `src/`** since §97, so the documentation side could
  not create a `doc_link` at all and every block read *"Not tested"* with no affordance behind it.
  New: `features/docs/LinkBlockDialog.tsx` + `features/docs/BlockLinks.test.tsx`; `DocsPage`'s
  `BlockView` gains a `BlockLinks` row under every block and a `pageId` prop; `lib/labels.ts` gains
  `linkSourceTypeLabels`. **No contract change** — the endpoints, `DocBlock.links` and the two hooks
  all predate this. The one thing to carry forward: **this half invalidates the page and §169's half
  must not**, because `DocBlock.links` is assembled inside the same GET while
  `TestCase.relatedDocuments` is a projection an event refreshes. **1233/1233 across 95 files**
  (+5, one new file, from 1228/94), build and lint clean. Browser rows `§172 XM-1`…`XM-5` recorded
  and **not run** (§65); no mutation rows — nothing in `backend/` moved.
- 2026-08-07 · EXTEND · **The three attachment surfaces** (§126 phase 4) — case files, page files,
  and `ArtifactKind.FILE` becoming registrable, over endpoints phases 1–3 had already shipped.
  **No contract change**: `schema.d.ts` was regenerated by the backend phases and every shape was
  already on the wire. Five new files (`lib/attachments.ts`, `components/AttachmentList.tsx`,
  `components/FileDownloadLink.tsx`, and two test files) plus three more test files. The two things
  worth knowing before touching it: **the download is a fetch and not an anchor** (an `<a href>` or
  an `<img src>` on an `/api/v1` URL sends no bearer token and answers 401 while looking correct —
  which is why there is no thumbnail either, recorded as an absence with its reason), and **the
  delete gate deliberately has no `OWN` arm** although `EvidencePanel` next door does. The artifact
  is a **different shape on purpose** — one dialog over two endpoints, with the form's `kind` wider
  than `ArtifactInput.kind` so the branch happens before either payload is built. `M3-38` in the
  browser queue was **corrected**, not appended to: it asserted the absence of the upload control
  this slice added. **1088/1088 across 83 files** (measured baseline **1055/79**, not the 1045 the
  header carried), build and lint clean.

- 2026-08-07 · EXTEND · **The ingestion history and the unmatched-test binder** (§119 phase 2) —
  `/projects/:projectId/ingestions` and `/…/ingestions/:jobId`, giving §117's `/automation-mappings`
  its first consumer in `src/`. Six new files under `features/ingestion/` plus
  `api/queries/ingestion.ts`; three new enum label maps. **Two routes rather than a dialog**, because
  the list and the detail serve *different shapes* — the list carries no unmatched names at all, so
  opening a job is a fetch and a dialog fed from the row would render an empty binder over a job with
  twelve unmatched tests. **`BindTestDialog` must never normalize `testName`**: the rule lives on the
  server beside the parser, and a client copy breaks every mapping in the company silently the day the
  two diverge — the assertion is on the **request body**, since nothing on screen can show it. The
  binder **closes on success**, deliberately the opposite of §118's reveal, because nothing here is
  unrecoverable. `runId: null` (matched nothing) and `runKey: null` (run deleted) render as different
  sentences; one dash for both was the obvious version and it misreports a deleted run.
  **1018/1018 across 76 files** (measured baseline 1002), build and lint clean.

- 2026-08-07 · EXTEND · **The token screen** (§118) — `/admin/tokens`, giving §116's `/api-tokens`
  endpoints their first consumer in `src/`. Three new feature files, `components/CopyField.tsx` (the
  app's first clipboard affordance, written to work **without** a clipboard), `lib/apiTokens.ts`
  (the derived LIVE/EXPIRED/REVOKED state). **No contract change and no `gen:api`** — every shape
  was already on the wire. The two decisions worth knowing before touching it: the reveal dialog
  passes **no `onClose`**, and the list read is gated by the caller's own permission verdict because
  the server refuses rather than narrows. Three mutations were run to prove the new tests can fail
  (`enabled`→`true`, EXPIRED→REVOKED, `onClose()` on success) and each reddened exactly its own
  rows. **992/992 across 73 files**, build and lint clean.
- 2026-08-06 · EXTEND · **The board learns to be dragged** (§106) — the ask was for drag-and-drop on
  the kanban board, **which already had it**: native HTML5, working on a desktop mouse and on
  nothing else. Replaced with `@dnd-kit` (`useDraggable`/`useDroppable`, not sortable), plus a
  column-jumping `KeyboardSensor` getter, a pure `resolveCardMove`, and an optimistic
  `useMoveWorkItem` with a snapshot rollback and an announcement. New
  `features/board/{cardMove,boardKeyboard}.ts` + their tests. **No contract change, no `gen:api`,
  no backend file touched** — `PATCH /work-items/{id}` and `allowedTransitions` were built in §53.
  Mock taught the contract's second 409, which it had answered 200 to since Phase 1. **976/976
  across 72 files, lint 0, build clean. Zero browser rows run; 11 M1 rows queued (M1-19…M1-29) and
  2 mutation rows.** An hour went on a rollback test whose hanging refetch broke RTL's `act`, not
  the product — recorded in the seam.

- 2026-08-06 · EXTEND · **What failed** (§105) — the run detail's failures and the steps inside
  them, on one panel. New `features/runs/FailureSummary.tsx` + 9 tests, `runFailures`/`stepOutcome`
  in `lib/results.ts` + 8 tests, `useExecutionSteps` + `STEP_DETAIL_LIMIT`. **No contract change,
  no `gen:api`, no backend file touched** — and the *reason* is the slice's main finding: the
  contract publishes **no run-level step rollup**, so the case half is free and the step half is a
  bounded read per failure. Mock changed: failed executions alternate two checklist shapes, because
  **"not marked" was unreachable in mock mode** before this. **958/958 across 70 files, lint 0,
  build clean. Zero browser rows run; 11 M4 rows queued (M4-48…M4-58).** The first draft put the
  failed-case list inside the step `DataRegion` and its own test caught it.

- 2026-08-06 · EXTEND · **Two links per execution row** (§104) — the key opens the test case (with
  the tooltip that says so), the title opens the verdict screen. New `features/runs/ExecutionRowLinks.tsx`
  + `ExecutionRowLinks.test.tsx` (5, over **both** grids); `ExecutionGrid` and `RunDetailPage` both
  compose it. **No contract change, no `gen:api`, no mock change, no backend file touched** —
  `TestExecutionSummary.caseId` has been required since M1. The finding was that the ask's "columns"
  already existed and the **destinations were the defect**: the two grids had the pair the opposite
  way round, leaving Grid mode with no route to a test case at all. **941/941 across 69 files, lint 0,
  build clean. Zero browser rows run; 7 M4 rows queued (M4-41…M4-47)**, of which the keyboard and
  screen-reader rows carry the risk — the accessible names are the half no gate here can see.
- 2026-08-06 · EXTEND · **The references show their cases** (§103) — a read-only, live-resolved case
  list under each plan group's chips. New `lib/planScope.ts` (`resolveReferences`) + its 5 tests,
  new `useReferencedCases` (**the first `useQueries` in this codebase**), new
  `features/plans/ResolvedScope.tsx`; `GroupCard` now takes `dirty` as well as `count`. **No contract
  change, no `gen:api`, no mock handler change** — `?suiteId=` (the §98 union) and `?tag=` were
  already served by both the server and the mock, and TP-1's fixture already exercises the union.
  **936/936 across 68 files, lint 0, build clean. Zero browser rows run; 11 M3 rows queued
  (M3-52…M3-62).** The baseline was **928**, not the **925** this file recorded at §102 — corrected.
- 2026-08-06 · EXTEND · **Tags become reusable** (§102) — the three tag controls §96 shipped with
  `options={[]}` now read `useProjectTags`; `SuiteFormDialog` gained `projectId`. No contract change,
  no migration, no `gen:api`, no new query key. **925/925 across 67 files, lint 0, build clean. Zero
  browser rows run; 5 M2 rows queued (M2-49…M2-53).** Found and fixed on the way: `BulkActionDialog`'s
  tags `data-testid` was on the `TextField` root, so the field had never been typeable by any test —
  exposed only because a suggestion list gave it something to filter. Also added two `PlanCasesTab`
  tests (add-then-see, remove-only-that) that **passed on arrival**: written to reproduce a reported
  defect that turned out to be a different ask (resolve references to visible cases — not built).
- 2026-08-05 · EXTEND · **The plan's scope becomes editable** (§97 phase 6, the last build phase) —
  `features/plans/PlanCasesTab.tsx` + ten pure helpers in `planDraft.ts` + `useProjectTags` +
  `keys.tags`. Sections created, renamed, reordered and deleted (with the reference question asked);
  four fill mechanisms, of which only the new-case rows write anything and they write it **on Save**.
  New test file `PlanCases.test.tsx` (9) and ten helper tests. No contract change, no migration, no
  `gen:api`. **915/915 across 65 files, lint 0, build clean. Zero browser rows run; 11 M3 rows
  queued (M3-41…M3-51).** Found and not fixed: the case-id chips resolve through a **client-side
  join over the case list's first 200**, because the contract has no by-ids read — an id outside it
  renders as "a case outside the loaded list" and can still be removed.
- 2026-08-05 · EXTEND · **The plan detail's tabs get their editors** (§97 phase 5) — the Plan tab
  (`features/plans/PlanFieldsTab.tsx`), the Business requirements tab rewritten read-only over an
  adopted page (`PlanDocumentPanel.tsx`, `useLinkPlanDocument`/`useUnlinkPlanDocument`, the mock's
  `PUT`/`DELETE`), and the artefacts tab's Register through the Artifacts page's own dialog.
  **Deleted:** `PlanEditDialog.tsx`, the header's Edit button, `useCreateDraftCase` and the
  Add-a-case bar — each with its argument struck through in place rather than removed. New test
  file `PlanDocument.test.tsx` (7); `PlanEdit.test.tsx` rewritten against the tab (10);
  `planProblems` unit-tested (3); two artefacts rows on `PlanForm.test.tsx`. No contract change, no
  migration, no `gen:api`. **895/895 across 64 files, lint 0, build clean. Zero browser rows run;
  12 M3 rows added or restated (M3-32…M3-40 new, M3-2/10/11/16/17/18/19/20 struck through or
  restated).** Known window, deliberate: the suite picker went with the dialog and the Test cases
  tab is phase 6, so a plan's suite scope has no on-screen editor in between.
- 2026-08-05 · EXTEND · **The plan detail's form spine** (§97 phase 4) — state above the tabs, one
  Save, the ordered pair, the banner. New: `features/plans/{planDraft,usePlanSave}.ts` + three test
  files · `useCreatePlanCase` · `features/artifacts/ArtifactTable.tsx` (extracted so the plan's
  artefacts tab and `/artifacts` share one renderer). No contract change, no `gen:api`.
  `TestPlanUpdateInput` widened by hand to the four fields phase 2 added, and
  `SetPlanStatus.overrideApprovalGate` **deleted** — M7 removed the edge it was meaningful on and
  the contract no longer declares it. **The build, not the runner, found the one defect** (a section
  fixture missing `position`: 29 green tests, one type error) — §85's lesson verbatim. Departure
  from the phase plan, recorded: the two prose tabs ship here rather than in phase 5, because a
  Save nothing can enable is a spine that cannot be driven. **880/880 across 63 files, lint 0,
  build clean. Zero browser rows run; 15 queued (114–128).**
- 2026-08-05 · EXTEND · **The design comp lands** (§100 phases A–C) — three proposals from
  `TCMS.dc.html`, imported through the design MCP. The comp is **mostly a portrait of what is already
  built** (its own first note is headed "Now matches the code"), so the deliverable was four
  "Proposed" notes; the user took 1–3 and dropped 4 because `DashboardPage` already totals health
  signals, and more honestly than the comp.
  New: `components/CriteriaStrip.tsx` + test · `features/runs/{executionMode,queueOrder,suggestedVerdict}.ts`
  + tests · `features/runs/{ExecutionQueue,ExecutionGrid,BlockedDialog}.tsx` ·
  `criterionColor`/`criterionStateLabel` and `compareCaseKeys` in `lib/`. **One contract change
  (`GET /plans` → `TestPlan`), `gen:api` run.**
  The findings were **three divergences no gate could see**: the contract declared a list shape no
  implementation served, with a javadoc asserting the opposite; the mock evaluated exit criteria the
  server leaves null, so mock mode showed gates no deployment shows; and `caseKey.localeCompare` put
  TC-10 before TC-9 in a spot whose defect class `format.ts` had already named in prose.
  Vitest 828 → **849/849 across 60 files**; build clean, lint 0.
  **Browser pass RUN inline by explicit direction, overriding §65 for this slice only** — rows
  101–110 `✅ RUN`, and two limits recorded rather than glossed: it was **mock mode** (no credentials
  for the :8080 backend `.env.local` points at), and `resize_window` did nothing, so the sub-`lg`
  rail collapse is the slice's one unverified claim. Rows 111–113 stay queued.

- 2026-08-05 · EXTEND · **Conditions get a writer** (§95, phases 1–4) — the library that could be
  read, ranked and attached and never created. Backend: `POST`/`PUT /projects/{id}/conditions`
  (`library:manage`, `If-Match`, 409) and the case's `preconditions`/`postconditions` finally
  accepted after refusing them with a 400 since M1. Frontend: `ConditionsPanel` ×2 +
  `ConditionEditDialog` + `lib/conditions.ts` + two write hooks + the mock's writers and its
  `applyConditions` reconcile. **One contract change, `gen:api` run.**
  The findings were **numbers**: `condition.usage_count` read its `DEFAULT 0` on every row in every
  deployment and was one slice from becoming an impact warning, so both counts are derived now — and
  deliberately **two numbers that are never added**, which answers a design question `/shared-steps`
  had posed and left open. Plus `lock_version` mapped **with `@Version`** from the first writer
  (§74's trap, prevented), and a bulk-hash trap that would have credited the next ordinary save with
  restoring conditions a `MOVE_TO_SUITE` stripped.
  Backend 1704 → **1715**; frontend 805 → **820/820 across 55 files**; build clean, lint 0.
  **Built and unverified** — 12 browser rows (62–73, two live-only) and 9 mutation rows queued,
  **zero run**. Three of the mutation rows are tests that do not exist yet, and the
  `ownConditionIds` one guards the slice's most dangerous defect.

- 2026-08-05 · EXTEND · **Writing steps** (§94 phases 2–3) — the case editor's step list reworked
  around the corpus §94 phase 1 built. New: `CaseEditorPage.test.tsx` (**the screen's first test
  file** after eight slices — 12 tests), `stepReorder.ts` + its 5, `useStepSuggestions` +
  `keys.stepSuggestions`, `StepSuggestion` in `types.ts`, the mock's `stepCorpus`, and `@dnd-kit`.
  **No contract change, no `gen:api`, no new permission key, no backend file touched.** The slice's
  own risk was the schema and it was named in advance: auto-append makes a blank row permanent, so
  "at least one step" had to stop counting `steps.length` in the *same commit* — see the seam.
  **Two pre-existing defects fell out of writing the first test**: the step panel's error alert had
  never rendered (`errors.steps.root`), and removing the trailing row became a dead end the moment
  the Add button went. Bundle measured before and after per the working agreement: **+46.2 kB raw /
  +14.8 kB gz (+2.9%), entirely in the lazy route chunk**, entry unchanged. Vitest 783 → **805**;
  build clean, lint 0. **Built and unverified** — browser rows 51–61 queued, **zero run**, two of
  them live-only and they are the ones that carry the corpus's real risk (mock fixtures give every
  structured case the same four steps).

- 2026-08-04 · EXTEND · §6e's **on-screen report view** (§90, F2) — the stored model rendered, so the
  screen and the exported file are one computation twice. New:
  `features/reports/{ReportViewPage,ReportView,DownloadReportButton}.tsx` + two test files, route
  `/projects/:projectId/reports/jobs/:jobId`, `lib/reportDocument.ts` (Zod parse + `headerBlock` +
  `barPercents`) + its test, `api/client.ts`'s `fetchArtifactJson`/`artifactResponse` +
  `api/artifacts.test.ts`, `useReportModel` + `keys.reportModel`, and a **View report** link on the
  job card. **No contract change, no `gen:api`, no new key, no backend file touched.** The finding is
  a **permission**: reading a report on screen costs `report:export`, contradicting the split §87
  built two custom roles for — worded on the page, fixed by nobody yet. See the seams above.
  723 → 749 → **765/765**; build clean, lint 0. **Built and unverified** — 8 browser rows (26–33) and
  6 frontend mutation rows queued, zero run inline; §89's untested-guard mutation row is struck
  through, closed here.

- 2026-08-04 · EXTEND · §6e's **Reports screen** (§89, F1) — the first client §87's pipeline has
  had. New: `features/reports/{ReportsPage,GenerateReportDialog,ReportJobCard}.tsx` + three test
  files, route `/projects/:projectId/reports`, an **ungated** rail item under Execution,
  `api/client.ts`'s `fetchArtifact`, `lib/download.ts`, three hooks + two keys in
  `api/queries/reporting.ts`, `ReportFormat`/`ReportJob*` in `types.ts`, four label maps, and the
  mock's FAILED path. **No contract change, no `gen:api`, no new key, no backend file touched.**
  The slice's finding is that **neither an `<a href>` nor `api.get` can download an artifact**, and
  both fail silently — see the first seam above. Two tests were repaired before they meant anything
  (a vacuous poll wait, a one-directional gate). 705 → 711 → **723/723**; build clean, lint 0.
  **Built and unverified** — 12 browser rows and 6 frontend mutation rows queued, zero run inline.

- 2026-08-04 · EXTEND · §6l's **team board** screen (§85) — §84's frontend half, and the slice that
  gives §6l's three boards three screens over one `Board` and one operation. New:
  `features/board/TeamBoardPage.tsx` + its test, route **`/teams/:teamId/board`** (**beside**
  `/admin/teams`, not beneath it — see the seam), the ungated **Board** link on every `TeamsPage`
  row, `useTeamBoard` + `keys.teamBoard`, and the mock's `teamBoard` + both of §84's refusals.
  `npm run gen:api` first — §84 moved `openapi.yaml` and left `schema.d.ts` stale.
  **No new permission key, no gate, no rail item, no contract change.** `linkFor` was **verified,
  not assumed** — the named risk of the slice, correct here precisely *because* §83 removed the route
  parameter rather than passing the right one down (with one, this screen would emit
  `/projects//cases/…`, having no project at all). The finding was the **fixture**: §84's own hole,
  one layer up — see the mock seam. And `npm run build` caught what the runner could not: a helper's
  `map(item => item.assignee?.id)` gave a `Set<string | undefined>` comparing happily against a
  `string[]`, twelve green tests and two type errors. 20 tests written (12 `TeamBoardPage`, 7
  `mocks/db/workItems`, 1 `TeamsPage`); 682 → **705**, the other 3 being §70's contract checks
  generating cases for the new `api.get` call site unattended. **0 mutations run inline** (policy);
  **25 browser rows** queued (six live-only — and the live-only ones are the ones that matter, since
  the reader-bound is unreachable in mock mode by construction), **6 frontend mutation rows**.

- 2026-08-04 · EXTEND · §6l's cross-project "My Work" board (§83) — the bound §81 named and left
  behind. New: `features/board/MyWorkPage.tsx` + its test, route `/my-work` (**outside the project
  segment** — see Layout), the rail item above the project switcher, `useMyWork` + `keys.myWork`,
  and the mock's `personalBoard` + both of `GET /work-items`'s refusals. `npm run gen:api` first —
  `projectId` is optional now. **Two repairs came out of it, both invisible before this screen
  existed**: `linkFor` took the *board's* project (identical to the card's on any project board, so
  all four of §81's link tests pass against the defect), and the mock's execution/maintenance
  generators drew the whole seeded repository onto every project's board (a 404-on-its-key on the
  second project, a duplicate React key on the aggregate). `Board` lost `projectId` and `mine`
  entirely and is now the one board both screens render — `ui-rules` §8, rather than a second
  implementation. Invalidation moved to the `work-items` root, which also fixed a create under the
  "Everyone" filter leaving the `mine=true` cache stale. 19 tests written (12 `MyWorkPage`, 7
  `mocks/db/workItems`); 662 → **682**, the extra one being §70's contract checks generating a case
  for the new `api.get` call site unattended. **0 mutations run inline** (policy); 19 browser rows
  queued (three live-only), 5 frontend mutation rows.

- 2026-08-05 · FIX · **§97 phase 3b — the mock STOPS STORING both case counts.** `PlanSection`
  gains `caseCount` on the contract, and the mock now resolves it — and the plan's own `caseCount` —
  in a single `present(plan)` applied to the list, the detail, the create, the edit, the status move
  and the idempotency replay. **The bug this fixes shipped in phase 2**: the counts were written on
  the last `PATCH` and stored on the row, so a plan's total never moved when a case was tagged,
  which made phase 2's own browser row 90 false in mock mode and would have had phase 6 built
  against a number the server does not produce (§81's finding, §87's). `db/executions.ts` gains
  `sectionCaseCount` and both it and `planScopeCaseIds` now go through one `resolveArms` union, so a
  plan and a section cannot disagree about what a reference catches — mirroring the server, where
  `caseCountsBySection` is `caseCountsByPlan` with `section_name` added to the grouping.
  **Fixture sections carry `caseCount: 0` deliberately**: seeding the "right" number would be
  seeding the one field that must never be stored. No screen consumes sections yet (the Test cases
  tab is phase 6), so the suite is unchanged at **828** — the tags endpoint has no client hook, so
  §70's contract-hygiene checks generated nothing.
- 2026-08-05 · REDUCE · **§97 phase 3a — the plan ladder is three states (M7), and a seventh status
  exists (M9).** `PlanStatus` is `DRAFT | IN_REVIEW | APPROVED`: `planStatusLabels` and
  `planStatusColors` lose two entries, **`isPlanClosed` is deleted** (its only consumer was the Edit
  button's disabled state), `planMoves` drops *Start plan* / *Close plan* / *Reopen plan*, and
  `PlanStats.OverrideGate` goes with the gate it retried — `useSetPlanStatus` takes `{status}`
  alone. The `ReviewSettings.Gating` panel keeps **one** switch. `StatusKind` gains `WIP`, so
  `statusLabels` and `StatusChip`'s `statusVar` gain an entry and `index.css` gains
  `--color-wip` in both schemes, **matching the colour `V32`'s seed writes into `case_status.color`
  rather than inventing a second one**. The mock's three ACTIVE/CLOSED plan fixtures become
  `APPROVED`, which is `V32`'s own mapping; TP-4 and TP-5 still carry DRAFT and IN_REVIEW, so all
  three states stay renderable. **`npm run build` found every site** — 30 type errors across 15
  files, none of them a runtime surprise, which is what `satisfies Record<Union, string>` and the
  un-cast `ProjectSettings` factory are for. Tests: two deleted with the affordances they covered
  (`PlanEdit`'s closed-plan disable, `PlanLifecycle`'s four gating tests), the rest rewritten;
  828 pass. **0 mutations run inline** (policy); 7 browser rows queued (89–95), two live-only.

- 2026-08-04 · EXTEND · §6g's gating rules (§82) — the two approval gates get a settings panel and
  two override affordances. ~~New: the `ReviewSettings.Gating` panel + its warning,
  `Runs.Create.OverrideGate`, `PlanStats.OverrideGate`, `SetPlanStatus` (the hook now takes
  `{status, overrideApprovalGate}`),~~ **the plan half of all of this was removed in §97 phase 3a
  (M7); the run half stands.** New: `overrideApprovalGate` on `TestRunInput`, both rules in the
  mock (`handlers/runs.ts`, `handlers/plans.ts`) mirrored from the server including the
  resolved-set, refuse-the-whole-run, after-validation and not-ANDed-with-the-opt-in properties, and
  **`ReviewSettingsPage.test.tsx`, which did not exist** — §77 shipped that screen untested.
  **`npm run build` caught the mock's settings handler dropping both new fields**, which is the same
  silent-discard class the form itself carries. Gating stays **off in the mock's fixtures**: turning
  it on would make every run and plan start in mock mode fail until somebody approved the fixtures,
  which demos the refusal rather than the product. 13 tests written; 649 → **662**. **0 mutations
  run inline** (policy); 15 browser rows queued, three of them live-only.

- 2026-08-03 · EXTEND · The reviewer's inbox (§81) — §6l's review card lands on the board, so a
  request finally surfaces outside `ReviewPanel`. Frontend work was **three repairs, not a feature**:
  the mock's invented review generator replaced by the server's rules (see the seam above),
  `BoardPage.linkFor` keyed on `sourceType`, and the derived-card refusal sentence keyed on `kind`
  (it called every non-execution card "a review"). Plus §79's cast repair on
  `BoardPage.test.tsx`'s factory, and one fixture review on `TC-20` so mock mode shows more than one
  subject type. **No contract change and no new hook.** 9 tests written (5 board, 4 the new
  `mocks/db/workItems.test.ts`), **649/649**. **0 mutations run inline**; 2 frontend rows queued in
  the mutation file, 11 browser rows — and §80's browser row 14 is struck through, because it
  recorded this absence as correct.

- 2026-08-02 · EXTEND · Reviewer assignment (§80) — §6g's *"assign a user or a team"* lands in
  `ReviewPanel` as an **Asked to review** section above the verdict trail. New: `useAssignReviewer`,
  `useUnassignReviewer`, `Assignees`/`AssignForm`, `ReviewAssignee` + `TeamRef` types, the mock's
  `assignReviewer`/`unassignReviewer` and two handlers, and three fixture requests on the demo plan's
  open review (a person who also spoke, a **team**, and a person who has said nothing) beside a review
  with **none** — §72's rule, both shapes present. **Asked and spoke are two lists**; see the seam
  above. **The findings were both in the tests**: four failed because `toBeEnabled()` on a MUI Select
  display div is satisfied while the gate is pending, and a fifth because MUI's default Chip delete
  icon carries no test id. A doc comment also claimed the picker filtered the submitter when it did
  not — implemented rather than deleted, because a picker that can produce a 400 is the one thing it
  must not be. 9 tests written (20 → 29 in that file), **640/640** overall — the other 4 are §70's
  data-driven contract checks generating cases for the two new `api.*` call sites unattended.
  **0 mutations run inline**, 14 browser rows queued (**rows 3 and 4 are live-only and are the ones
  the slice turns on**).
- 2026-08-02 · EXTEND · Review comments (§79) — §6g's threads land in `ReviewPanel`. New:
  `useAddReviewComment`, `useSetReviewCommentResolution`, `Threads`/`Thread`/`CommentBody`/
  `CommentForm` in `ReviewPanel.tsx`, `ReviewComment` + `ReviewCommentInput` types, the mock's
  `addComment`/`threadRoot`/`commentExists`/`setResolution` and its two new handlers, and two
  fixture threads on the demo plan's open review (one with a reply, one resolved) beside a review
  with **none** — §72's rule, both shapes present. **The two affordances cost two different keys**;
  see the seam above. **The finding was a cast**: the test fixture's `as Review` hid a new required
  field from the compiler and turned eleven tests red at runtime. The panel's empty trail also stopped
  saying *"Nobody has commented yet"*, wording that named a feature two slices before it existed.
  7 tests written (13 → 20 in that file), 627/627 overall; **0 mutations run inline**, 13 browser rows
  queued.
- 2026-08-02 · EXTEND · Per-project review settings (§77) — §6g's four settings get a screen and the
  review panel gets an off switch. New: `features/settings/ReviewSettingsPage.tsx`,
  `useProjectSettings`/`useUpdateProjectSettings`, `keys.projectSettings`,
  `editAfterApprovalLabels`/`Descriptions`, the `/projects/:id/settings/review` route and its rail
  item, and the mock's `settingsForProject`/`saveProjectSettings` + both handlers.
  **`ReviewPanel` now returns `null` when review is off** — which reddened all 11 of its tests at
  once, because their `mockReads` fallthrough answered `[]` and `[].reviewEnabledPlan` is
  `undefined`. That is the feature working; the fixture moved to the enabled state and the
  default-off case got **its own test** rather than remaining a fallthrough's accident.
  The mock seeds the **demo project on and every other project off**, deliberately: both are states a
  real instance produces, and having only one is §72's fixture rule in one direction or the other.
  **The MUI slot trap fired a fourth time** (`inputProps` vs v7's `slotProps`), caught by
  `npm run build`. 2 tests written, 11 repaired; 616/616.
- 2026-08-02 · FIX · The verb the contract checks could not see (§70). Chasing §69's four
  unaccounted-for tests found that `contractOperations.test.ts` and `apiResponseTypes.test.ts` each
  hard-coded the client's verbs and spelled its `del` as `delete`, omitting `upload` — so **eight
  call sites had never been checked by either**. The set is derived from `client.ts` now
  (`contractIntrospection.apiMethods`); `apiResponseTypes` also stopped building a second
  `ts.createProgram` over the same sources. Both mutations run inline: unmapped spelling → exactly 8
  red, an unreadable verb → exactly 2 (both vacuity guards, which previously stayed silent).
  All 16 newly generated cases pass — the blind spot was empty this time. **No runtime code
  changed**, so no browser row is owed; the queue records that. 534/534. `#confirmed`
- 2026-08-02 · EXTEND · The classification vocabulary editor (§69) — new route
  `/projects/:projectId/settings/classification` (`features/settings/`), **Classification** in the
  rail's Project group, three new hooks in `api/queries/projects.ts`. **Every gated control reads
  the dimension's `locked` flag to choose WHICH permission to ask, at which scope** —
  `company:vocabulary` via `useCanInCompany` for the company's axes, `project:settings` via
  `useCan` for the project's Feature tree — so `PermissionGate`'s `projectId="company"` literal has
  its second consumer after the project picker. Reuses §68's `buildValueTree`, where the
  orphan-surfacing matters in mirror image: **a value nothing renders is one nobody can retire.**
  The mock's `dimensions` moved from the fixture into `db` and `handlers/cases.ts` moved with it,
  or a value added in the editor is refused by the case editor as "not in the vocabulary".
  **The test failed for §66's reason before it failed for a real one**: rendered bare instead of
  through a `<Route>`, `projectId` is `''`, `useCan` is disabled and permanently pending, and every
  "disabled" assertion passes with no gate in the code at all. Render page tests through a `Route`
  and hold a `permissionsResolved()` barrier. `#confirmed`
- 2026-08-02 · EXTEND · The classification picker (§68) — the last of §5's three scheduled Phase-2
  gaps, and the largest. New: `features/cases/ClassificationPicker.tsx` + its test,
  `lib/classification.ts` (`buildValueTree`) + its test. `caseSchema` gained
  `classificationValueIds`; `CaseEditorPage`'s Classification panel replaces read-only feature chips
  with a control per dimension, inside its own `DataRegion` so a failed vocabulary read leaves the
  rest of the editor saveable. **The control shape is derived from `cardinality` + `hierarchical`,
  never from the dimension key**, so a fifth dimension needs no code. **The read-only comment's
  justification was false**: no leaf-only rule exists in any spec, and a parent IS assignable.
  `buildValueTree` lives in `lib/` because exporting a function beside a component defeats Fast
  Refresh (the `caseSchema.ts` trade) and it **surfaces orphans AND cycle members at the root**
  where the old `SuiteTree` dropped cycle members (§98 deleted that walk with the tree) — in a picker, a value with no control on screen is silently
  deselected by the next save. Mock now **enforces** the server's three rules rather than storing
  what it is given, and `buildCaseDetail` DERIVES its ids from the summary's names so no fixture can
  carry ids and names that disagree. §62's lesson again in the test: a bare `data-testid` on a MUI
  `Checkbox` lands on the wrapper span where `toBeChecked` cannot see it — testids moved to the
  `input` slot. Two mutations run, 3 red each. **Browser pass DEFERRED**; rows 8–10 are live-only
  (the mock has no retired values). 506/506.
- 2026-08-02 · EXTEND · The evidence UI (§67). New: `features/runs/EvidencePanel.tsx` + its test,
  `useAttachEvidence`/`useDeleteEvidence`/`MAX_EVIDENCE_BYTES` in `api/queries/runs.ts`, and
  **`api.upload`** in `api/client.ts` (see the seam above — the Content-Type rule is the one that
  would have failed silently). Mock: `handlers/runs.ts` gained all three evidence routes and
  **models the `OWN` refusal**, reading `createdBy` exactly as the server does; `db/executions.ts`
  seeds three evidence rows per FAILED execution whose authors **disagree** with `executedBy`, one
  of them with a null author. **Contract defect found by `tsc`**: `Evidence.createdBy` was
  non-nullable while the null author is the whole `OWN` arm — now `allOf` + `nullable`.
  Mutation-verified: deleting `authorId` from the JSX reddens exactly the two authorship tests.
  `testTimeout` raised to 30s — see Build, run & test.
- 2026-08-02 · EXTEND · Consumed the §65 qualifier wire shape. `useCan` gains `canOnScope` /
  `reasonOnScope` / `pending`; `ArtifactDialogs` disables the PROJECT scope option for a
  `PLAN_SCOPE` grant (disabled on the `MenuItem`, never wrapped — §62's rule). New shared
  `src/test/permissions.ts`. Mock handlers gained `holdsOn` / `qualifierOn` and the fixture carries
  the *qualified* grants, so mock mode cannot show a control live mode disables. **Browser pass
  DEFERRED** under the new standing policy — queued in `docs/verification/frontend-browser-checks.md`;
  §65 is built and not browser-verified. 470/470.

- ~~2026-08-02 · EXTEND · Built `SharedStepPicker` (§64).~~ → **DELETED by §239 (audit C4)**, with
  its test, both "Shared" chips, the detach control, the locator
  `CaseEditor.Steps.InsertSharedButton`, `sharedStepId` through all three `caseSchema` mappings,
  `useSharedSteps`, `useCreateSharedStep` and `keys.sharedSteps`. **The entry below describes the
  defect without naming it**: inserting set `action` to the entry's TITLE and `sharedStepId` to the
  id "because a shared block is a reference" — and **no backend read path ever resolved that
  reference**, so the chip's "editing the library entry changes every case using it" was false from
  the day it shipped. Step reuse is `useStepSuggestions` alone. Original entry: — the first consumer
  `useSharedSteps` has ever
  had, reached from the case editor's step list, plus `useCreateSharedStep`. Inserting sets the
  step's `action` to the entry's TITLE and its `sharedStepId` to the id: a shared block is a
  reference resolved on read, so copying the body in yields a step that looks identical and stops
  tracking its source. The picker is **not** permission-gated (reading needs only the project
  grant); the inline create is, on `library:manage`. **Two mock defects**: the shared-step fixtures
  carried usageCount 48/33/91 while no fixture case referenced any entry — the handler now derives
  the count through `caseDetail`, since a summary row carries no steps — and `GET /shared-steps`
  alone did not `requireAuth`. A fixture case now references an entry, so the detail page's "Shared"
  chip is drawable in mock mode for the first time. **Harness trap**: `PermissionGate` swaps the DOM
  node between its denied (tooltip-wrapped) and allowed (bare) branches, so a held reference never
  becomes enabled — re-query inside `waitFor`; and since the gate is fail-closed while
  `/me/permissions` is in flight, a "disabled" assertion is only non-vacuous when paired with an
  enabling one. `#confirmed`
- 2026-08-02 · EXTEND · Extended `BulkActionDialog` to all eight bulk actions (§62), typed
  `BulkInput`/`payload` from the contract (both were `string`/`Record<string,unknown>`), and brought
  the MSW handler into agreement with the new server — it had **dropped unresolvable ids** so the
  three buckets did not add up to `total`, and answered "not supported yet" for six actions. Added
  `BulkActionDialog.test.tsx` (13 tests). **The live pass found the approve option was unselectable**
  because `PermissionGate` wrapped a `Select` child — see the seam note; tests now assert through
  `role="option"` and count options against `lifecycleLabels` rather than reading a testid.
- 2026-08-02 · EXTEND · Added the artifact register's write half (§61): `useCreateArtifact`,
  `features/artifacts/ArtifactDialogs.tsx` (scope × kind cross-field form) + its 6 tests, the MSW
  `POST /artifacts` handler mirroring the PLAN_SCOPE qualifier, and the `docPageId` link that makes a
  documentation artifact reachable. Cache created this run.
- 2026-08-04 · EXTEND · §91 — gave `/` a way in and the rail a way closed. `ALL_PROJECTS` rail item
  + linked wordmark (the picker had **no affordance at all** since the app began: sign-in lands
  there, an unreachable project falls back there, and nothing linked to it), and an icon-only
  collapsed rail persisted through `safeStorage`. Extracted `ProjectSwitcher` out of `RailContent`
  to carry both widths. Added `AppShell.test.tsx`, the shell's first tests (5). Gate: build clean,
  lint 0, **770/770 across 52 files**. **A comment was written wrong and corrected before landing**:
  it claimed `end: true` on a `to="/"` item is load-bearing against a prefix match on every route —
  `NavLink` already refuses that case, the test stays green without it, and the comment and the test
  doc now both say so rather than implying a guard that is not there. Browser rows 34–39 queued,
  **not run** (§65).

- **2026-08-17 (§241)** — Org chart frontend, from `docs/plans/2026-08-15-org-chart-tasks.md`
  (T6, T7, T8). Extracted `flattenTeamTree` from `TeamsPage` first and alone, so a regression there
  could not be confused with the new feature. Added `useOrgChart`, `keys.orgChart` and the nine
  staffing-write invalidations plus a source-derived rule for a tenth; `features/org-chart/` with
  the page, matrix, teams and gaps panels; route and rail entry; `assignmentViaLabels`; nine
  fixtures. Wrote the matrix on `DataGrid` first, measured that it does not render in jsdom, and
  moved it to `Table`. 1582/119 on shanks, dist JS +0.37 %.
