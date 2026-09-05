/**
 * Fault injection for the API client — development only.
 *
 * The resilience gate is a list of claims: a 500 shows the failed state inline, a hung endpoint
 * times out instead of spinning, a failed *refetch* keeps its rows and marks them stale, a
 * mid-session 401 recovers without losing the form. Every one of those is only believable if it
 * has been *seen*, and none of them can be triggered by using the app normally — a healthy
 * backend always succeeds.
 *
 * So the faults are switchable from the console:
 *
 *     tcmsFaults.status(500)      // every request fails with a 500
 *     tcmsFaults.status(503, 30)  // …with a Retry-After: 30
 *     tcmsFaults.hang()           // requests never answer — proves the timeout budget
 *     tcmsFaults.expire()         // next request 401s — proves in-place session recovery
 *     tcmsFaults.drift()          // a 200 with the wrong shape — must read as FAILED, not empty
 *     tcmsFaults.only('cases')    // scope the fault to paths containing 'cases'
 *     tcmsFaults.tracker('403')   // the tracker refuses THIS viewer — see below
 *     tcmsFaults.issue('conflict')// the workflow refuses a move — see below
 *     tcmsFaults.rank('conflict') // the rank write loses a race — see below
 *     tcmsFaults.wip()            // every card move lands the column over its limit — see below
 *     tcmsFaults.release('empty') // the roadmap is empty — merge day, see below
 *     tcmsFaults.clients('none') // this company has no clients — every client surface vanishes
 *     tcmsFaults.importing('midway') // the source stopped answering during the run — see below
 *     tcmsFaults.time('rejected')  // your fortnight came back with a note — see below
 *     tcmsFaults.staffing('no-profile') // nobody's contracted hours are recorded — see below
 *     tcmsFaults.billing('no-rate-card') // nothing this company does can be priced — see below
 *     tcmsFaults.off()
 *
 * ## Why this lives here and not in a mock transport
 *
 * It used to be an MSW handler (`src/mocks/handlers/injection.ts`), which meant the faults could
 * only be driven against the mock API — the one place where the *real* failure modes could not
 * occur. Now it sits in front of `fetch` in `src/api/client.ts`, so the same switches fault a
 * session talking to the real Spring Boot backend. That is a strictly stronger test: the response
 * this fabricates is the only thing injected, and everything downstream of it — the error mapping,
 * the notification, the query cache's stale-data behaviour — is the production path.
 *
 * ## The tracker arm is a different kind of fault, and that is the point (004 Phase 0)
 *
 * Everything above fabricates a failure of **our** API. The tracker is somebody else's server
 * reached with somebody else's credential, and its failures are ones this product deliberately
 * does **not** propagate: a viewer with no identity, an expired token and an issue the viewer may
 * not see all answer `200` with an `"unavailable"` body rather than a `401`, because the viewer's
 * TCMS session is fine and a `401` would trip the SPA's session-recovery path for a condition that
 * has nothing to do with it. Injecting a raw status could not exercise that at all — it would test
 * the ordinary failure path, which is the one place this feature must not end up.
 *
 * So `tracker()` fabricates the **contract's own degraded bodies**, and it has five arms:
 *
 *   - `'slow'` — tracker reads never answer, proving the typeahead's 3s budget rather than the
 *     interactive one.
 *   - `'401'` — the credential is rejected.
 *   - `'403'` — the credential is fine and this viewer may not see this issue.
 *   - `'down'` — the tracker did not answer at all.
 *   - `'drift'` — a 200 whose body is not the contract's, which must read as FAILED and never as
 *     an empty list.
 *
 * **`'401'` and `'403'` are separate arms even though the chip renders them identically** (D16).
 * That identity is a decision — the tracker's reason for refusing is not ours to interpret at a
 * reader — and the one place the two do differ is the credential owner's own screen, where
 * `POST /me/tracker-identities/{id}/test` reports what the tracker said. Each arm therefore writes
 * a different sentence there and the same chip everywhere else, which is exactly the shape the
 * product has and the reason a single "make the tracker fail" switch could not prove it.
 *
 * ## The tracker's namesake, and why the issue arm is a third kind again (011 T6)
 *
 * `tracker()` above is 004's *external* Jira/ADO integration. This module's `issue()` is the
 * product's **own** issue tracker, and its two faults are specific to it because neither can be
 * produced by using the app:
 *
 *   - `'conflict'` is the one worth having. The server refuses a transition the workflow forbids
 *     with a **409 that names the moves that are allowed**, and the screen has to show that
 *     refusal beside the picker rather than as a toast that scrolls away. The only way to see it
 *     during development is to force it, **because the picker only ever offers legal moves** — it
 *     is built from `Issue.allowedTransitions`, so an ordinary session cannot ask for a refused
 *     one. The same 409 is what an Epic gets whatever the target (D31).
 *   - `'drift'` on `GET /issues/{key}` serves an issue whose `completion.basis` is absent. It must
 *     read as **FAILED**, not as a percentage with no caption: a number whose basis is missing is
 *     exactly the thing D8 says must never render, and the ordinary `drift()` above cannot prove
 *     it — a wholly unrecognisable body is easy to reject, and a body that is *almost* right is
 *     the one that slips through.
 *
 * ## The board's two switches, and why neither is reachable by using the app (012 T6)
 *
 * These land **before the screens that prove them**, which is CLAUDE.md's working agreement: a
 * resilience behaviour you cannot trigger is one you have not tested.
 *
 *   - `rank('conflict')` — `PUT /issues/{key}/rank` answers **409**. Unreachable in an ordinary
 *     session: it needs a second person to move the neighbour between this client's read and its
 *     write, inside one drag. It is also the only thing that proves the optimistic rank drag rolls
 *     back — the machinery M2 earned and M1 deliberately did not (012 D-c) — so without this
 *     switch the rollback ships untested and looks identical to a board that simply never fails.
 *   - `rank('rebalance')` — **200 with `rebalanced: true`, which is not a failure**, and is the
 *     easier of the two to get backwards. The placement succeeded; what is now stale is every
 *     *other* row's rank, so the client must **drop** its optimistic order and refetch rather than
 *     roll it back. A client that keeps its patch here shows an order the server does not have,
 *     and nothing on screen says the two disagree.
 *   - `wip()` — every card move answers with a `breach`. Proves that the move still **lands** and
 *     that the breach is reported rather than enforced (story B3), which a real WIP limit only
 *     reaches after somebody drags three cards into one column.
 *
 * ## The release module's two switches, and both are states a healthy instance grows out of (013 T5)
 *
 * These land **before the screens that prove them**, for the same working agreement.
 *
 *   - `release('untested')` — every readiness read answers `hasRun: false` with **null** counts. It
 *     is the state no seeded instance can reach once anybody has run anything, and the one the
 *     screen most easily gets wrong: a `?? 0` anywhere in the meter turns "nothing has run" into
 *     "nothing failed", which is the distinction `ReleaseVerdict` exists to hold. The list's Tests
 *     cell must read the literal words *no run yet* and the detail page must show its *"If nothing
 *     had run"* panel, and neither is reachable from a demo instance with a green run on it.
 *   - `release('empty')` — the roadmap answers `{ releases: [], unscheduled: [] }`. **This is an
 *     acceptance criterion of the module rather than an edge case**: D14 migrates nothing into
 *     `release`, so on the day M3 merges the roadmap and the release list are empty for every user
 *     in every instance, and the screen has to say why and offer to create the first one. After
 *     somebody does, the state is unreachable again without a fresh database.
 *
 * ## The import module's six switches, and not one of them is reachable by using the app (014 T7)
 *
 * These land **before the screens they prove**, for the working agreement above — and here the rule
 * is not a discipline so much as the only option available. Every failure this module has to word
 * correctly lives inside **somebody else's Jira**: a token that was revoked this morning, an account
 * that can list a project but not read its issues, a site that stops answering at minute four. A
 * developer cannot produce any of them from a healthy instance, and the ones that matter most are
 * the ones a demo instance is furthest from.
 *
 *   - `importing('unauthorized')` / `importing('forbidden')` — the source refuses the credential, or
 *     refuses the account this particular project. Two arms rather than one, for `tracker()`'s
 *     reason inverted: here the two sentences are **different advice** — a rejected token is
 *     replaced, an account without access is granted access by somebody else — and the contract
 *     serves the source's own message on both, so a single "the import failed" switch would prove
 *     the screen renders a sentence and nothing about which sentence.
 *   - `importing('down')` — the source did not answer at all. The `502` the two credential-carrying
 *     POSTs declare.
 *   - `importing('midway')` — **the one this module was built for**, and the only one of the six
 *     that spans two requests. The preview answered, the mapping was confirmed, the run was accepted
 *     with a `202` — and then the source stopped answering. Its correct rendering is a **FAILED
 *     report saying the project is unchanged**, never a spinner that never ends and never a partial
 *     success: the write is one all-or-nothing transaction after the read pass (D-b), so nothing was
 *     half-written and the screen has to say so. A run stuck `RUNNING` for ever is what the reaper
 *     exists to prevent server-side, and this is the client half of the same claim.
 *   - `importing('collision')` — the preview answers `200` with a `KEY_COLLISION` blocker. **Not a
 *     failure**, which is why it is an arm here: it is the state the mockup does not draw, it
 *     replaces Continue rather than reddening the page, and reaching it for real needs a Jira
 *     project whose keys already exist in this company.
 *   - `importing('slow')` — the source reads never answer, proving the long budget the preview is
 *     given rather than the interactive one. A real project takes minutes to page; a preview that
 *     gave up after ten seconds would be indistinguishable from one that failed.
 *
 * **The report reads are deliberately left alone by the first three arms.** `GET /imports` and
 * `GET /imports/{id}` are served from our own database and reach no source system — that is the
 * promise the report makes about outliving the system it describes — so a fault that broke them
 * would be asserting the opposite of what the module claims.
 *
 * Both path tests are **anchored** (`/^\/releases\/[^/]+\/readiness$/`), so `untested` cannot
 * catch the release detail read one segment above it and fabricate a `ReleaseReadiness` where a
 * `Release` was expected — the drifted-body failure, injected by accident.
 *
 * `wip()` fabricates a whole `CardMoveResult`, and its `card` is therefore **not the card that
 * moved** — the seam sits in front of `fetch` and never sees the request body, so there is no
 * `toColumnId` to answer with. Nothing reads it: the move's success invalidates the board and the
 * real cards arrive from the refetch, and `breach` is the only member a consumer takes. Said out
 * loud because a developer watching the network tab would otherwise read the placeholder card as a
 * defect in the endpoint.
 *
 * ## The time module's two switches, and neither can be reached alone (016 T4)
 *
 * These land **before the screens that prove them**, for the working agreement above.
 *
 *   - `time('rejected')` — `GET /timesheets/periods/current` answers a **REJECTED** period carrying
 *     a decision note. It is the state the timesheet screen most has to get right and the one a
 *     developer cannot produce: somebody else has to hold `time:approve`, find your fortnight in
 *     their queue and send it back. Its correct rendering is the note at the top, the Add form
 *     **editable again**, and Submit still offered — a rejection *unlocks* a period, which is the
 *     opposite of what a word like "rejected" suggests and the opposite of what SUBMITTED does.
 *   - `time('locked')` — the three entry writes answer the contract's **409**. Reachable in an
 *     ordinary session only through a race, because the screen removes the Add form and the edit
 *     buttons the moment a period stops being editable: producing it for real needs the period
 *     submitted in a second tab between this one rendering and the person pressing Add. It is the
 *     refusal the whole feature turns on, and it has to read as a sentence beside the form rather
 *     than as a toast that scrolls away.
 *
 * **The reads are left alone by `locked`**, for `issue('conflict')`'s reason: a fault that broke the
 * period read would prove the failed-region branch four other switches already prove, and would
 * never let the screen render the form the refusal has to appear beside.
 *
 * The rollup reads are inside `isTimePath` on purpose even though neither arm fabricates one. The
 * estimate-vs-actual panel is a *region* inside a screen whose other regions must keep rendering
 * when it fails, and `tcmsFaults.status(500)` narrowed with `only('/time/')` is the only way to
 * watch that one `DataRegion` fall over while the issue beside it stays on screen.
 *
 * Both path tests are **anchored**, per the rule above: `/timesheets/entries` and
 * `/timesheets/entries/{entryId}` are the same prefix, and `/timesheets/periods/current` is one
 * segment away from `/timesheets/periods/{periodId}` — a `rejected` body served for the latter
 * would answer a *colleague's* period with the caller's own, which is the drifted-body failure
 * injected by accident and libellous with it.
 *
 * ## The staffing arm, and why the interesting one is not an error at all (017 T4)
 *
 * These land **before the screens that prove them**, for the same working agreement.
 *
 *   - `staffing('no-profile')` — every staffing read answers with `profile: null` and every hours
 *     figure null. It is the branch every capacity surface in the product has and the one no
 *     fixture and no seeded instance produces: the demo data records a profile for everybody, and
 *     a company where nobody has one is the state on the *first* day of using the module. Its
 *     correct rendering is an em dash and the words *no profile recorded* — not `0h`, which says
 *     somebody recorded zero contracted hours, and not the region's failed state, which says the
 *     read did not answer. Those three are indistinguishable to a reader if the screen gets them
 *     wrong, which is why `PersonCapacity.profile` is served rather than inferred from a null and
 *     why this switch exists at all.
 *   - `staffing('over-allocated')` — everybody comes back at `peakPercent: 130`, `overAllocated:
 *     true`, and every person is repeated into the view's `overAllocated` list. Producing it for
 *     real needs three allocations on one person across three projects the developer can see, and
 *     what it proves — the danger tone, the row badge, the chip's count and the breakdown panel —
 *     is four surfaces that must agree about one boolean.
 *
 * The path test is a **prefix** rather than one anchored operation, because the arm rewrites bodies
 * of three different shapes (`PersonCapacity[]`, `StaffingCapacityView`, `SelfCapacity`) and each
 * rewrite is chosen by its own anchored predicate below. `/projects/{id}/staffing` is deliberately
 * **outside** it: that payload is a `ProjectStaffingView`, which is T5's screen and carries no
 * `profile` at all, so a body written for the company shape would be served where the other was
 * expected — this module's own drifted-body failure, injected by accident.
 *
 * ## Tree-shaking
 *
 * **This module must stay free of top-level side effects.** Both entry points are called behind a
 * *literal* `import.meta.env.DEV`, which Vite replaces with `false` in a production build, so
 * Rollup proves the calls dead and drops this module with them. Routing that check through an
 * imported constant instead is what once shipped the whole mock layer as a 485 kB production
 * chunk — the literal is the thing doing the work, not the intent behind it.
 */

export interface FaultState {
  /** Status to fail with, or `null` to pass through. */
  status: number | null;
  /** `Retry-After` value to send with the failure, in seconds. */
  retryAfter: number | null;
  /** Never respond, so the client's own deadline is the only thing that ends the request. */
  hang: boolean;
  /** Fail exactly one request with a 401, then clear — a session that expired mid-work. */
  expireOnce: boolean;
  /** Return a 200 whose body is the wrong shape. */
  drift: boolean;
  /** Only apply to request paths containing this substring. */
  pathContains: string | null;
  /** How the tracker is misbehaving, or `null` for a tracker that works. */
  tracker: TrackerFault | null;
  /** How this product's own issue tracker is misbehaving, or `null` for one that works. */
  issue: IssueFault | null;
  /** How the rank write misbehaves, or `null` for one that lands. */
  rank: RankFault | null;
  /** Every card move reports the target column over its WIP limit. */
  wip: boolean;
  /** How the release module's reads misbehave, or `null` for an instance with releases in it. */
  release: ReleaseFault | null;
  /** How an import misbehaves, or `null` for a source system that answers. */
  import: ImportFault | null;
  /** How the time module misbehaves, or `null` for a fortnight nobody has acted on. */
  time: TimeFault | null;
  /** How the staffing reads misbehave, or `null` for a company whose profiles are recorded. */
  staffing: StaffingFault | null;
  /** How many clients this company has, or `null` for the instance as it actually is. */
  clients: ClientsFault | null;
  /** Which unpriceable state the billing reads are in, or `null` for the instance's own figures. */
  billing: BillingFault | null;
}

/** The five ways somebody else's tracker fails us. See this module's header. */
export type TrackerFault = 'slow' | '401' | '403' | 'down' | 'drift';

/**
 * The tracker's own fault modes, beyond the four every path family gets.
 *
 * See this module's header for why each exists and why neither is reachable by using the app.
 */
export type IssueFault = 'conflict' | 'drift';

/**
 * Which fabricated answer the rank endpoint gives.
 *
 * **`'rebalance'` is not a failure**, and that is why it is an arm here rather than something a
 * developer could approximate with `status()`. See this module's header.
 */
export type RankFault = 'conflict' | 'rebalance';

/**
 * The two release states a working instance grows out of. See this module's header.
 *
 * **Neither is a failure.** Both are 200s carrying a state the screen has to word correctly, which
 * is why they are arms here rather than something `status()` could approximate.
 */
export type ReleaseFault = 'untested' | 'empty';

/**
 * How an import misbehaves, or `null` for a source that answers.
 *
 * **None of these can be reached by using the app**, which is the whole reason they are here: the
 * failures this module exists to word correctly all live inside somebody else's Jira. `'midway'`
 * is the one the module was built for — a source that answered the preview and stopped answering
 * during the run — and its correct rendering is a FAILED report saying the project is unchanged,
 * never a spinner and never a partial success.
 */
export type ImportFault = 'unauthorized' | 'forbidden' | 'down' | 'midway' | 'collision' | 'slow';

/**
 * The two time states a single developer cannot reach.
 *
 * **`'rejected'` is not a failure** — it is a 200 carrying the state that unlocks a period, and the
 * one this module was added for. `'locked'` is the 409 the feature turns on. See this module's
 * header for why neither is reachable by using the app.
 */
export type TimeFault = 'rejected' | 'locked';

/**
 * The two staffing states a healthy seeded instance will not show you.
 *
 * **Neither is a failure.** Both are 200s carrying a shape the screens have a second branch for,
 * and the branch is the thing that cannot be reached any other way:
 *
 *   - `'no-profile'` — every person comes back with `profile: null` and **every hours figure
 *     null**, which is the five-null body the contract sends together (017 correction 4). It is
 *     the state no fixture produces by accident and the one every capacity surface must read as
 *     an em dash and *no profile recorded* — never `0h`, which claims somebody recorded 0 hours,
 *     and never the region's failed state, which claims the read did not answer. Reaching it for
 *     real means an instance where nobody has run `PUT /staffing/people/{id}/profile`, which the
 *     demo seed is specifically built not to be.
 *   - `'over-allocated'` — everybody comes back at `peakPercent: 130` with the flag set, so the
 *     danger tone, the row badge and the Over-100% panel can be watched without arranging three
 *     real allocations across three projects on one person.
 */
export type StaffingFault = 'no-profile' | 'over-allocated';

/**
 * The clients arm, and it is **the only arm in this file that fabricates a population rather than a
 * failure**.
 *
 * Every other arm here answers the question *what does this screen do when something breaks*. Mc's
 * acceptance criterion is not a failure at all: it is an **absence** — with no clients, six
 * surfaces render nothing, and *"nothing"* is a state a reader can only check by seeing it. On a
 * `seed-demo-data` instance that state is unreachable without deleting every client, and on a
 * fresh one the populated state is unreachable without creating them. So neither half of the
 * criterion could be looked at before this arm existed, which is exactly the case
 * *build the fault switch before the feature it proves* is written for.
 *
 * `'none'` answers `[]` to the client list and to the project picker; `'some'` answers a fabricated
 * pair. Both are `200`s carrying a state rather than failures, so both are `body` verdicts — the
 * shape the tracker arm established for a degraded answer.
 *
 * The four generic faults still reach these paths through `only('/clients')`. This arm is for the
 * two states that are not faults.
 */
export type ClientsFault = 'none' | 'some';

/**
 * The billing arm (019 T5), and like the clients arm it fabricates a **state** rather than a
 * failure.
 *
 * Both are `200`s carrying a shape the screens have a second branch for, and the branch is the
 * thing that cannot be reached any other way — a `seed-demo-data` instance has rate cards, budgets
 * and two invoices on purpose, so the two states this module was hardest to word for are exactly
 * the two nobody can walk to.
 *
 *   - `'no-rate-card'` — every rate-card read answers `card: null`, and every draft comes back with
 *     all of its hours in the `NO_RATE` bucket at a total of zero. This is `billing.html`'s
 *     *Kessler Health* row and the **second** exclusion note on an invoice — the bucket a naive
 *     implementation loses, because from inside a join it looks exactly like a priced hour
 *     (019 D-g). Reaching it for real means deleting every card on the instance.
 *   - `'nothing-priceable'` — every read answers empty or zero, so the four tiles, the budget
 *     table, the invoice list and any draft render their empty states at once. This is a company
 *     that has just started, and it is the state most likely to be mistaken for a broken screen.
 *
 * The four generic faults still reach these paths through `only('/billing')`, `only('/invoices')`
 * and so on. This arm is for the two states that are not faults.
 */
export type BillingFault = 'no-rate-card' | 'nothing-priceable';

const faults: FaultState = {
  status: null,
  retryAfter: null,
  hang: false,
  expireOnce: false,
  drift: false,
  pathContains: null,
  tracker: null,
  issue: null,
  rank: null,
  wip: false,
  release: null,
  import: null,
  time: null,
  staffing: null,
  clients: null,
  billing: null,
};

/**
 * Which requests the tracker arm applies to.
 *
 * The project's tracker sub-resource and the caller's own identities — the two path families whose
 * answers come from somebody else's server. Deliberately **not** `…/cases/{id}/issue-links`: those
 * rows are ours, they are served from our database, and a link list that failed when the tracker
 * did would hide 004 D18's whole claim (delete the binding and the links still render).
 */
function isTrackerPath(path: string): boolean {
  return path.includes('/tracker/') || path.startsWith('/me/tracker-identities');
}

/**
 * Which requests the issue arm applies to — the three path families M1 added.
 *
 * `/board` is deliberately included: a board card's move is the same transition, refused by the
 * same rule, and a fault that could not reach the board would leave the one screen where a
 * refusal is easiest to mis-handle (a card that visibly lands and then snaps back) unprovable.
 */
function isIssuePath(path: string): boolean {
  return path.startsWith('/issues') || path.startsWith('/board') || path.includes('/workflow');
}

/**
 * `PUT /issues/{key}/rank`, and only that.
 *
 * Anchored rather than a `startsWith('/issues')`: `/issues/{key}` and `/issues/{key}/comments` are
 * the same prefix, and a rank fault that answered a detail read would fabricate a `RankResult`
 * where an `Issue` was expected — the drifted-body failure, injected by accident, three arms away
 * from the one being tested.
 */
function isRankWrite(path: string, method: string): boolean {
  return method === 'PUT' && /^\/issues\/[^/]+\/rank$/.test(path);
}

/** `POST /boards/{boardId}/cards/{key}/move`, anchored for {@link isRankWrite}'s reason. */
function isCardMove(path: string, method: string): boolean {
  return method === 'POST' && /^\/boards\/[^/]+\/cards\/[^/]+\/move$/.test(path);
}

/**
 * `GET /releases/{releaseId}/readiness`, and only that.
 *
 * Anchored for {@link isRankWrite}'s reason, one segment tighter: `/releases/{id}` is the same
 * prefix, and a readiness fault answering it would serve a `ReleaseReadiness` where a `Release` was
 * expected.
 */
function isReadinessRead(path: string, method: string): boolean {
  return method === 'GET' && /^\/releases\/[^/]+\/readiness$/.test(path);
}

/** `GET /projects/{projectId}/roadmap`, anchored for {@link isReadinessRead}'s reason. */
function isRoadmapRead(path: string, method: string): boolean {
  return method === 'GET' && /^\/projects\/[^/]+\/roadmap$/.test(path);
}

/** A path with its query string removed — `/imports?projectId=…` is still `/imports`. */
function withoutQuery(path: string): string {
  return path.split('?')[0] ?? path;
}

/**
 * The two operations that **reach the source system carrying a credential**, and only those.
 *
 * `POST /imports` is not among them even though it starts a run against the source: it answers
 * `202` and dispatches, so the failure it can produce is not a failure of this request. That one is
 * {@link isRunRead}'s, one poll later, which is what `'midway'` exists to drive.
 */
function isSourceCall(path: string, method: string): boolean {
  const bare = withoutQuery(path);
  return method === 'POST' && (bare === '/imports/source-projects' || bare === '/imports/preview');
}

/**
 * `POST /imports` — the confirmation, anchored so it cannot catch its two POST siblings.
 *
 * The trailing check is on the whole path rather than a prefix, because `/imports/preview` and
 * `/imports/source-projects` are the same prefix and a fault that answered one of them with an
 * `ImportRun` would be the drifted-body failure, injected by accident.
 */
function isStartImport(path: string, method: string): boolean {
  return method === 'POST' && withoutQuery(path) === '/imports';
}

/**
 * `GET /imports/{importRunId}` — the poll target.
 *
 * **The method is what tells this apart from the two POST sub-paths**, not the shape of the
 * segment: `/^\/imports\/[^/]+$/` matches `/imports/preview` and `/imports/source-projects` exactly
 * as well as it matches a run id, and a run fault answering the preview would serve an `ImportRun`
 * where an `ImportPreview` was expected. Neither of those two is ever a `GET`, so the pair of
 * conditions is unambiguous where either alone is not.
 */
function isRunRead(path: string, method: string): boolean {
  return method === 'GET' && /^\/imports\/[^/]+$/.test(withoutQuery(path));
}

/**
 * Which requests the time arm applies to — the two path families 016 added.
 *
 * The rollup reads are included on purpose, even though neither arm fabricates one: the
 * estimate-vs-actual panel is a *region* inside a screen whose other regions must keep rendering
 * when it fails, and a failure it cannot be given is a `DataRegion` nobody has watched fall over.
 * `only('/time/')` narrows to exactly those two reads; `only('/timesheets')` to the eight above.
 */
function isTimePath(path: string): boolean {
  return path.startsWith('/timesheets') || path.startsWith('/time/');
}

/**
 * `GET /timesheets/periods/current`, and only that.
 *
 * Anchored for {@link isRankWrite}'s reason, and the collision here is one segment rather than a
 * prefix: `/timesheets/periods/{periodId}` reads **somebody else's** fortnight, so a `rejected` body
 * served there would put the caller's own note and hours under a colleague's name.
 */
function isCurrentPeriodRead(path: string, method: string): boolean {
  return method === 'GET' && withoutQuery(path) === '/timesheets/periods/current';
}

/**
 * The three entry writes — the create, the amend and the delete.
 *
 * The create is matched on the whole path rather than a prefix, for {@link isStartImport}'s reason:
 * `/timesheets/entries` and `/timesheets/entries/{entryId}` are the same prefix, and only the method
 * tells the create from the amend.
 */
function isEntryWrite(path: string, method: string): boolean {
  const bare = withoutQuery(path);
  if (method === 'POST') return bare === '/timesheets/entries';
  if (method === 'PATCH' || method === 'DELETE') {
    return /^\/timesheets\/entries\/[^/]+$/.test(bare);
  }
  return false;
}

/** The issue key a path names, decoded — the fabricated bodies have to answer about the right one. */
function keyIn(path: string, position: number): string {
  return decodeURIComponent(path.split('/')[position] ?? 'ACME-1');
}

/** The tracker's own message, per arm — shown only to the person whose credential it is. */
const TRACKER_CHECK_ERRORS: Record<Exclude<TrackerFault, 'slow' | 'drift'>, string> = {
  '401': 'The tracker rejected the credential. It may have expired or been revoked.',
  '403': 'The credential is valid, but this account may not read that project.',
  down: 'The tracker did not answer.',
};

function applies(path: string): boolean {
  return faults.pathContains === null || path.includes(faults.pathContains);
}

/** What, if anything, this request should do instead of reaching the network. */
export type FaultVerdict =
  | { kind: 'none' }
  | { kind: 'hang' }
  | { kind: 'drift' }
  | { kind: 'status'; status: number; retryAfter: number | null }
  /**
   * A body this fault composes itself, at a status of its own — the tracker's degraded answers,
   * which are `200`s carrying a state rather than failures.
   */
  | { kind: 'body'; status: number; body: unknown };

export function verdictFor(path: string, method = 'GET'): FaultVerdict {
  /*
    The tracker arm is checked first and is scoped by path rather than by `only()`: it fabricates
    the shapes of two specific path families, so applying it to `/cases` would serve a case list a
    body from a different contract. `only()` still narrows it further, which is how a developer
    watches one chip rather than every chip on a page.
  */
  if (faults.tracker !== null && isTrackerPath(path) && applies(path)) {
    return trackerVerdict(faults.tracker, path, method);
  }

  /*
    The issue arm, second, and scoped by path for the tracker arm's reason: it fabricates the
    shapes of specific operations, so applying it to `/cases` would serve a case list a body from
    a different contract. `only()` still narrows it further.
  */
  if (faults.issue !== null && isIssuePath(path) && applies(path)) {
    const verdict = issueVerdict(faults.issue, path, method);
    if (verdict.kind !== 'none') return verdict;
  }

  /*
    The rank and WIP arms, third and fourth, and scoped by path for the two arms above's reason.
    Both fabricate the shape of one specific operation, so a `only()`-style substring match would
    serve a `RankResult` where a board was expected.
  */
  if (faults.rank !== null && isRankWrite(path, method) && applies(path)) {
    return rankVerdict(faults.rank, path);
  }

  if (faults.wip && isCardMove(path, method) && applies(path)) {
    return breachedMoveVerdict(path);
  }

  /*
    The release arm, fifth, and scoped by path for every arm above's reason. Each of its two states
    fabricates the shape of one specific operation, so a substring match would serve a roadmap where
    a release list was expected.
  */
  if (faults.release !== null && applies(path)) {
    const verdict = releaseVerdict(faults.release, path, method);
    if (verdict.kind !== 'none') return verdict;
  }

  /*
    The import arm, sixth, and scoped by path for every arm above's reason. Four of its six states
    fabricate the shape of one specific operation, so a substring match would serve an `ImportRun`
    where an `ImportPreview` was expected.
  */
  if (faults.import !== null && applies(path)) {
    const verdict = importVerdict(faults.import, path, method);
    if (verdict.kind !== 'none') return verdict;
  }

  /*
    The time arm, seventh, and scoped by path for every arm above's reason. One of its two states
    fabricates a whole `TimesheetPeriod`, so a substring match would serve one where a queue — or a
    rollup — was expected.
  */
  if (faults.time !== null && isTimePath(path) && applies(path)) {
    const verdict = timeVerdict(faults.time, path, method);
    if (verdict.kind !== 'none') return verdict;
  }

  /*
    The staffing arm, eighth, and scoped by path for every arm above's reason. Both of its states
    fabricate a whole `PersonCapacity` — three operations answer three different arrangements of
    one — so the shape is picked per operation rather than per fault.
  */
  if (faults.staffing !== null && isStaffingPath(path) && applies(path)) {
    const verdict = staffingVerdict(faults.staffing, path, method);
    if (verdict.kind !== 'none') return verdict;
  }

  /*
    The clients arm, ninth, and the only one here that fabricates a POPULATION rather than a
    failure — see `ClientsFault`. Scoped by path for every arm above's reason, and its matcher is
    anchored because `/clients` is a prefix of `/clients/{clientId}`.
  */
  if (faults.clients !== null && isClientPopulationRead(path, method) && applies(path)) {
    return clientsVerdict(faults.clients, path);
  }

  /*
    The billing arm, tenth, and the second here that fabricates a STATE rather than a failure — see
    `BillingFault`. Scoped by path for every arm above's reason, and its matcher answers WHICH read
    matched rather than only whether one did: five operations answer five different shapes, so the
    body is picked per operation exactly as the staffing arm's is.
  */
  if (faults.billing !== null && applies(path)) {
    const which = isBillingRead(path, method);
    if (which !== null) return billingVerdict(faults.billing, which);
  }

  if (!applies(path)) return { kind: 'none' };

  // Checked first and consumed immediately: the point is to expire the session *once*, so the
  // recovery can then succeed. A permanent 401 would only prove the dialog appears, not that
  // signing back in actually restores the page.
  if (faults.expireOnce) {
    faults.expireOnce = false;
    return { kind: 'status', status: 401, retryAfter: null };
  }
  if (faults.hang) return { kind: 'hang' };
  if (faults.drift) return { kind: 'drift' };
  if (faults.status !== null) {
    return { kind: 'status', status: faults.status, retryAfter: faults.retryAfter };
  }
  return { kind: 'none' };
}

/**
 * What the tracker does instead of working, in the contract's own vocabulary.
 *
 * Four shapes, and each is the *documented* degraded answer rather than an error:
 *
 *   - `POST …/tracker-identities/{id}/test` → `TrackerConnectionCheck` with `ok: false` and the
 *     tracker's message. The one place the arms differ, per D16.
 *   - `POST …/tracker/issues` (filing) → the contract's `502` `tracker-unavailable` problem. Filing
 *     is the one operation that cannot degrade: there is no issue to show, so the refusal is real.
 *   - `GET …/tracker/issues?q=` → `TrackerIssueSearch` with `state: 'UNAVAILABLE'`.
 *   - `GET …/tracker/issues/{id}` → `TrackerIssueLookup` with `state: 'UNAVAILABLE'` and a null
 *     issue, which is what makes the chip fall back to `<provider> issue <id>`.
 *
 * Everything else on those paths — the bindings, the connected-members list, the identity rows —
 * is **ours** and is left alone: it is served from our database and does not stop working because
 * somebody else's server did.
 */
function trackerVerdict(fault: TrackerFault, path: string, method: string): FaultVerdict {
  if (fault === 'slow') return { kind: 'hang' };
  if (fault === 'drift') return { kind: 'drift' };

  if (path.includes('/tracker-identities/') && path.endsWith('/test')) {
    return {
      kind: 'body',
      status: 200,
      body: { ok: false, checkedAt: new Date().toISOString(), error: TRACKER_CHECK_ERRORS[fault] },
    };
  }

  if (method === 'POST' && path.endsWith('/tracker/issues')) {
    return {
      kind: 'body',
      status: 502,
      body: {
        type: 'tracker-unavailable',
        title: 'The tracker did not accept it',
        status: 502,
        detail: TRACKER_CHECK_ERRORS[fault],
        permission: null,
        scope: null,
        line: null,
      },
    };
  }

  if (path.includes('/tracker/issues')) {
    // The two reads differ in one member and share the state, so one branch decides both: a search
    // carries a (possibly empty) array, a lookup carries a nullable issue.
    const isSearch = path.includes('?') || path.endsWith('/tracker/issues');
    return {
      kind: 'body',
      status: 200,
      body: isSearch ? { state: 'UNAVAILABLE', issues: [] } : { state: 'UNAVAILABLE', issue: null },
    };
  }

  return { kind: 'none' };
}

/**
 * What this product's own tracker does instead of working.
 *
 * **`conflict` answers the transition POST and nothing else.** The reads have to keep succeeding
 * or the screen under test never renders the picker the refusal has to appear beside — a fault
 * that broke the detail read would prove the failed-region branch, which four other switches
 * already prove, and not this.
 *
 * The 409 body is the contract's `Problem`, and its `detail` **names the legal moves**, which is
 * the half a plain `status(409)` cannot fabricate. That sentence is the whole requirement: a
 * refusal that says only "conflict" leaves a configured workflow looking like a broken one.
 *
 * `drift` is deliberately *narrow*. It serves a real `Issue` shape with `completion` present and
 * `basis` missing — the almost-right body — because a wholly unrecognisable one is easy to
 * reject and this is the one D8 forbids rendering.
 */
function issueVerdict(fault: IssueFault, path: string, method: string): FaultVerdict {
  if (fault === 'conflict') {
    /*
      The board card move is the **same refusal at a second door** (012 M2): the endpoint resolves
      the target column's default status and delegates to the same transition, so it answers the
      same 409. `isIssuePath`'s comment above already promised the board this arm, and until M2
      there was no board endpoint of its own for it to reach.
    */
    const refusable = (method === 'POST' && path.endsWith('/transitions')) || isCardMove(path, method);
    if (!refusable) return { kind: 'none' };
    return {
      kind: 'body',
      status: 409,
      body: {
        type: 'workflow-move-refused',
        title: 'The workflow forbids this move',
        status: 409,
        detail:
          'This issue can only move to Blocked or In Review from where it stands. '
          + 'The project\u2019s workflow decides which moves exist, not the person making them.',
        permission: null,
        scope: null,
        line: null,
      },
    };
  }

  // `drift`: a body that is almost an Issue. Only the detail read — a list of these would be
  // rejected for a different reason and would say nothing about the basis.
  if (method !== 'GET' || !/^\/issues\/[^/]+$/.test(path)) return { kind: 'none' };
  return {
    kind: 'body',
    status: 200,
    body: {
      key: 'ACME-101',
      seq: 101,
      title: 'An epic whose completion lost its basis',
      type: { id: 'drift-type', name: 'Epic', level: 'EPIC', countsTowardCompletion: false },
      status: { id: 'drift-status', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 },
      projectId: 'drift-project',
      projectKey: 'ACME',
      watchers: [],
      children: [],
      allowedTransitions: [],
      createdAt: new Date().toISOString(),
      completion: { percent: 70, done: 12, total: 17, excludedSpikes: 1 },
    },
  };
}

/**
 * What the rank write does instead of landing.
 *
 * `'conflict'` is the 409 the contract declares for *"a neighbour moved between the read and the
 * write"* — and the title matters as much as the status: `describeError` reads
 * *"Someone else saved first"* as the lost-update case and any other title as a refusal that keeps
 * its own `detail`. This is a lost update, so it carries that title and the sentence names what
 * happened rather than the status code.
 *
 * `'rebalance'` is a **success**, and the one arm here whose point is that it is not a failure. The
 * rank it answers with is the respaced one, so a client that kept its optimistic order would be
 * showing an order derived from ranks the server has just rewritten.
 */
function rankVerdict(fault: RankFault, path: string): FaultVerdict {
  const key = keyIn(path, 2);

  if (fault === 'conflict') {
    return {
      kind: 'body',
      status: 409,
      body: {
        type: 'stale-write',
        title: 'Someone else saved first',
        status: 409,
        detail:
          'The issue you dropped this next to was moved by somebody else while you were dragging. '
          + 'Nothing was changed \u2014 the list has been refreshed, so try again.',
        permission: null,
        scope: null,
        line: null,
      },
    };
  }

  return { kind: 'body', status: 200, body: { key, rank: '2048', rebalanced: true } };
}

/**
 * The two release states a healthy instance grows out of, in the contract's own vocabulary.
 *
 * `'untested'` answers **readiness only**, and it answers with `hasRun: false` and every count under
 * it `null` — which is the contract's own rule and the whole point of the arm. A fabricated `0`
 * would arm nothing: the screen the fault exists to check is the one that must not draw a zero as a
 * clean release, so serving one would make the fault agree with the defect.
 *
 * `'empty'` answers **the roadmap only**. The release *list* is left alone deliberately: a fresh
 * instance has an empty list too, and that state is already reachable by looking at a project nobody
 * has typed a release into — while an empty roadmap over a project that *has* releases is the
 * merge-day shape and cannot be reached any other way once one exists.
 */
function releaseVerdict(fault: ReleaseFault, path: string, method: string): FaultVerdict {
  if (fault === 'untested') {
    if (!isReadinessRead(path, method)) return { kind: 'none' };
    return {
      kind: 'body',
      status: 200,
      body: {
        releaseId: keyIn(path, 2),
        version: '2.5',
        status: 'PLANNED',
        verdict: 'NOT_TESTED',
        stories: { done: 0, total: 6, inProgress: 0, notStarted: 6, excludedSpikes: 0 },
        tests: {
          hasRun: false,
          linkedCases: 4,
          withResult: null,
          passed: null,
          failed: null,
          blocked: null,
          untested: null,
          latestExecutedAt: null,
          latestRunId: null,
          latestRunName: null,
          executedBy: null,
        },
        blockers: [],
        epics: [],
        scopeAddedAfterCreation: 0,
      },
    };
  }

  if (!isRoadmapRead(path, method)) return { kind: 'none' };
  return { kind: 'body', status: 200, body: { releases: [], unscheduled: [] } };
}

/** The source system's own message, per arm — shown only to the person whose credential it is. */
const IMPORT_SOURCE_ERRORS: Record<'unauthorized' | 'forbidden' | 'down', string> = {
  unauthorized:
    'The source system rejected the credential. An API token can be revoked from the account '
    + 'that created it, and one that has been is indistinguishable from one typed wrongly.',
  forbidden:
    'The credential is valid, but this account may not read that project. Someone with '
    + 'administrator rights in the source system has to grant it browse access first.',
  down: 'The source system did not answer.',
};

/**
 * A run that never started, fabricated for `'midway'`.
 *
 * Its `projectId` is a placeholder for `breachedMoveVerdict`'s reason: the seam sits in front of
 * `fetch` and never sees the request body, so there is no project to answer about. Nothing reads
 * it — the wizard navigates by the id in the response and the report page reads the run — and it is
 * said out loud here because a developer watching the network tab would otherwise read the
 * placeholder as a defect in the endpoint.
 *
 * **Every count is zero and `failureReason` says the project is unchanged**, which is the whole
 * claim: the write is one all-or-nothing transaction after the read pass (D-b), so a source that
 * stopped answering leaves nothing behind. A fabricated partial count here would make the fault
 * agree with the defect it exists to catch.
 */
function strandedRun(status: 'RUNNING' | 'FAILED'): unknown {
  return {
    id: '00000000-0000-4000-8000-0000000000f1',
    projectId: '00000000-0000-4000-8000-0000000000f0',
    provider: 'JIRA_CLOUD',
    siteUrl: 'https://northwind.atlassian.net',
    accountEmail: 'ops@northwind.test',
    sourceProjectKey: 'PROJ',
    status,
    startedAt: new Date().toISOString(),
    finishedAt: status === 'FAILED' ? new Date().toISOString() : null,
    startedBy: null,
    issuesImported: 0,
    releasesCreated: 0,
    commentsImported: 0,
    labelsCreated: 0,
    droppedValueCount: 0,
    labelPerSprint: false,
    backlogOrderPreserved: false,
    failureReason:
      status === 'FAILED'
        ? 'The source system stopped answering while its issues were being read. '
          + 'Nothing was written \u2014 this project is exactly as it was before the import started.'
        : null,
    droppedFields: [],
    statusMapping: [],
    people: [],
  };
}

/**
 * What an import does instead of working.
 *
 * The six arms are three different kinds of thing, and keeping them apart is the point:
 *
 *   - **Three refusals by the source** (`unauthorized`, `forbidden`, `down`) answer the two
 *     credential-carrying POSTs with the contract's own `502`, each carrying the source's own
 *     sentence. They deliberately leave `GET /imports` and `GET /imports/{id}` alone: those are
 *     served from our database and reach no source system, which is the promise the report makes
 *     about outliving what it describes.
 *   - **`collision` is not a failure.** It is a `200` preview carrying a `KEY_COLLISION` blocker —
 *     the state `import-preview.html` does not draw, which replaces Continue rather than reddening
 *     the page.
 *   - **`midway` spans two requests**, and is the only arm here that does. `POST /imports` is
 *     accepted with its `202` exactly as it would be, and the poll that follows answers `FAILED`.
 *     A fault that refused the `202` would prove the confirm button's error path, which
 *     `unauthorized` already proves, and nothing whatever about the report the module was built to
 *     word.
 */
function importVerdict(fault: ImportFault, path: string, method: string): FaultVerdict {
  const touchesImports = withoutQuery(path).startsWith('/imports');
  if (!touchesImports) return { kind: 'none' };

  if (fault === 'slow') {
    // The source calls only. A hung poll would be a spinner that never ends, which is the defect
    // this module has to avoid rather than a budget it has to prove.
    return isSourceCall(path, method) ? { kind: 'hang' } : { kind: 'none' };
  }

  if (fault === 'collision') {
    if (withoutQuery(path) !== '/imports/preview' || method !== 'POST') return { kind: 'none' };
    return {
      kind: 'body',
      status: 200,
      body: {
        projectId: '00000000-0000-4000-8000-0000000000f0',
        sourceProjectKey: 'PROJ',
        sourceKeyPrefix: 'PROJ',
        projectKeyPrefix: 'ACME',
        totals: {
          issues: 1482,
          labels: 214,
          comments: 4892,
          historyEntries: 11306,
          fixVersions: 38,
          sprintValues: 1204,
        },
        types: [],
        /*
          Empty statuses, and `suggestedStatusId` would be null in any case: the seam cannot know
          this project's real status ids, and a fabricated one would be a uuid the mapping step
          would send to a server that has never seen it. The mapping step is unreachable behind a
          blocker anyway, which is the state under test.
        */
        statuses: [],
        droppedFields: [],
        blockers: [
          {
            kind: 'KEY_COLLISION',
            detail:
              'Three keys in PROJ already exist in this company. Importing them would create a '
              + 'second issue with the same key, so the import cannot proceed.',
            keys: ['PROJ-14', 'PROJ-802', 'PROJ-4812'],
          },
        ],
      },
    };
  }

  if (fault === 'midway') {
    if (isStartImport(path, method)) return { kind: 'body', status: 202, body: strandedRun('RUNNING') };
    if (isRunRead(path, method)) return { kind: 'body', status: 200, body: strandedRun('FAILED') };
    return { kind: 'none' };
  }

  if (!isSourceCall(path, method)) return { kind: 'none' };
  return {
    kind: 'body',
    status: 502,
    body: {
      type: 'import-source-unavailable',
      title: 'The source system did not answer',
      status: 502,
      detail: IMPORT_SOURCE_ERRORS[fault],
      permission: null,
      scope: null,
      line: null,
    },
  };
}

/**
 * What the time module does instead of behaving.
 *
 * **`rejected` is a `200`, not a failure**, and it is the arm this switch exists for. A fortnight
 * that came back with a note is the state the timesheet screen most has to word correctly and the
 * one nobody can produce alone — it takes a second person holding `time:approve`. Served on
 * `GET /timesheets/periods/current` only, so the history list beside it keeps answering and the
 * screen renders with the previous-period panel intact.
 *
 * The body is a **complete** `TimesheetPeriod` with `editable: true` and `entries: []`. Both of
 * those are load-bearing rather than convenient: `editable` is what tells the screen a rejection
 * *unlocks* a period, and an empty entries list is what a person sees when the rejection arrives on
 * a fortnight they have not started re-working — the row the note is about lives in the period the
 * approver sent back, which is the one this list links to.
 *
 * **`locked` answers the three entry writes and nothing else**, for `issueVerdict`'s reason: the
 * reads have to keep succeeding or the screen under test never renders the form the refusal has to
 * appear beside. The `detail` names the state, because a person whose fortnight was *approved* and
 * one whose fortnight is *waiting* need different next steps — which is exactly the half a plain
 * `status(409)` cannot fabricate.
 */
function timeVerdict(fault: TimeFault, path: string, method: string): FaultVerdict {
  if (fault === 'locked') {
    if (!isEntryWrite(path, method)) return { kind: 'none' };
    return {
      kind: 'body',
      status: 409,
      body: {
        type: 'timesheet-period-closed',
        title: 'This period is closed',
        status: 409,
        detail:
          'The fortnight this day falls in is SUBMITTED, so nothing in it can be changed. '
          + 'A rejected period is editable again; an approved one is final.',
        permission: null,
        scope: null,
        line: null,
      },
    };
  }

  if (!isCurrentPeriodRead(path, method)) return { kind: 'none' };
  return {
    kind: 'body',
    status: 200,
    body: {
      id: '00000000-0000-4000-8000-0000000000e1',
      personId: '00000000-0000-4000-8000-0000000000e2',
      personName: 'You',
      startsOn: '2026-09-01',
      endsOn: '2026-09-15',
      state: 'REJECTED',
      loggedHours: 21,
      // Zero unless the state is APPROVED, which this one is not — a rejected fortnight has had
      // nothing posted anywhere, and a non-zero here would be the contradiction D-d forbids.
      approvedHours: 0,
      submittedAt: '2026-09-16T08:12:00Z',
      decidedAt: '2026-09-16T09:40:00Z',
      decidedByName: 'Rae Lindqvist',
      decisionNote:
        'the 8h on 24 Aug is against ACME-140 but that epic was on hold that week \u2014 '
        + 're-point it or drop it.',
      editable: true,
      entries: [],
    },
  };
}

/**
 * Which requests the staffing arm applies to — the company-scope reads 017 added.
 *
 * `/projects/{id}/staffing` is **not** here, and the omission is the same one `isCurrentPeriodRead`
 * makes one module over: that operation answers a `ProjectStaffingView`, which has no `profile` and
 * no hours figure on it, so a body written for `PersonCapacity` served there would be a drifted
 * response rather than a fault. `only('/staffing/capacity')` narrows to the two capacity reads.
 */
function isStaffingPath(path: string): boolean {
  return path.startsWith('/staffing');
}

/** `GET /staffing/capacity`, and only that — `capacity/me` is one segment on and a different shape. */
function isCapacityRead(path: string, method: string): boolean {
  return method === 'GET' && withoutQuery(path) === '/staffing/capacity';
}

/** `GET /staffing/capacity/me`. Anchored for {@link isCapacityRead}'s reason, from the other end. */
function isMyCapacityRead(path: string, method: string): boolean {
  return method === 'GET' && withoutQuery(path) === '/staffing/capacity/me';
}

/**
 * `GET /staffing/people` and `GET /staffing/people/{userId}` — the list and the one person.
 *
 * The two answer an **array** and a **single object** of the same schema, so they cannot share one
 * fabricated body and the caller below picks by which of the two matched. `…/profile`, `…/skills`
 * and `…/absences` sit under the same prefix and are excluded by the anchor: a `PersonCapacity`
 * served where a `Skill[]` was expected is the collision this module's header warns about.
 */
function isPeopleRead(path: string, method: string): 'list' | 'one' | null {
  if (method !== 'GET') return null;
  const bare = withoutQuery(path);
  if (bare === '/staffing/people') return 'list';
  return /^\/staffing\/people\/[^/]+$/.test(bare) ? 'one' : null;
}

const FAULT_FROM = '2026-09-01';
const FAULT_TO = '2026-11-30';

/**
 * The staffing bodies, and the shape of each is decided by which read matched.
 *
 * Two people rather than one, because every panel this proves is a *list*: an Over-100% panel with
 * a single row cannot show that the chip's count, the row badges and the breakdown are read off one
 * served subset rather than three separate filters.
 */
/**
 * The two reads whose EMPTINESS decides whether anything client-shaped renders.
 *
 * Both are anchored, and the first one has to be. `startsWith('/clients')` would also catch
 * `/clients/{clientId}`, and answering an array there would serve a `ClientDetail` read a list —
 * the drifted-body failure, injected by accident. That is `isRankWrite`'s recorded reason and this
 * is the fourth arm to pay it.
 *
 * The detail read is deliberately NOT faulted: a client page is only reachable from a list that
 * has one, so `'none'` makes it unreachable rather than empty, and `'some'` would need a whole
 * fabricated `ClientDetail` to answer a question no acceptance criterion asks.
 */
function isClientPopulationRead(path: string, method: string): boolean {
  if (method !== 'GET') return false;
  const bare = withoutQuery(path);
  return bare === '/clients' || /^\/projects\/[^/]+\/settings\/client-options$/.test(bare);
}

/**
 * A fabricated pair, or none — see {@link ClientsFault}.
 *
 * `'some'` serves the two shapes the two paths declare: `GET /clients` answers `Client[]` and the
 * picker answers `ClientRef[]`, which is two fields of the same row. Serving the fuller shape to
 * both would put a `projectCount` on a picker option, which is a body the contract does not
 * declare and `contractBodies` would reject in a test.
 */
function clientsVerdict(fault: ClientsFault, path: string): FaultVerdict {
  if (fault === 'none') return { kind: 'body', status: 200, body: [] };

  const pair = [
    { id: '00000000-0000-4000-8000-0000000000c1', name: 'Acme Retail' },
    { id: '00000000-0000-4000-8000-0000000000c2', name: 'Bergstrom AG' },
  ];

  if (withoutQuery(path) !== '/clients') return { kind: 'body', status: 200, body: pair };

  return {
    kind: 'body',
    status: 200,
    body: pair.map((client, index) => ({
      ...client,
      location: index === 0 ? 'Stockholm' : 'Zürich',
      industry: index === 0 ? 'retail' : 'logistics',
      notes: null,
      status: 'ACTIVE',
      accountManager: null,
      projectCount: index === 0 ? 2 : 1,
      contactCount: index === 0 ? 3 : 2,
      createdAt: '2026-03-04T09:00:00Z',
    })),
  };
}

/**
 * The five reads and one write whose ANSWERS decide whether anything on the billing screens can be
 * priced.
 *
 * Every list path is anchored, for `isRankWrite`'s recorded reason and the fifth arm to pay it:
 * `/invoices` is a prefix of `/invoices/{invoiceId}`, and answering an array there would serve an
 * `InvoiceDetail` read a list — the drifted-body failure, injected by accident. `/rate-cards` is a
 * prefix of `/rate-cards/{rateCardId}` in exactly the same way.
 *
 * `POST /invoices` is included because it answers an `InvoiceDetail` of its own: a draft made while
 * the arm is on must render the same all-excluded state the read does, or the one gesture that
 * reaches this state would land on an unfaulted body.
 *
 * `/projects/{id}/budget-burn` is deliberately NOT faulted. It is the one endpoint in the module a
 * Test Engineer may call and it carries no money at all (019 D-h), so there is no money-shaped
 * state here for it to be in — and answering it from this arm would put a billing fault on a
 * project screen.
 */
function isBillingRead(path: string, method: string): 'summary' | 'rate-cards' | 'budgets' | 'invoices' | 'invoice' | null {
  const bare = withoutQuery(path);
  if (method === 'POST') return bare === '/invoices' ? 'invoice' : null;
  if (method !== 'GET') return null;
  if (bare === '/billing/summary') return 'summary';
  if (bare === '/rate-cards') return 'rate-cards';
  if (bare === '/budgets') return 'budgets';
  if (bare === '/invoices') return 'invoices';
  if (/^\/invoices\/[^/]+$/.test(bare)) return 'invoice';
  return null;
}

/** The two clients the billing arm invents, and the ids every fabricated body below agrees on. */
const FAULT_CLIENTS = [
  { id: '00000000-0000-4000-8000-0000000000b1', name: 'Kessler Health' },
  { id: '00000000-0000-4000-8000-0000000000b2', name: 'Bergstrom AG' },
];

const FAULT_INVOICE_ID = '00000000-0000-4000-8000-0000000000b9';
const FAULT_PROJECT_ID = '00000000-0000-4000-8000-0000000000b7';

/**
 * The billing bodies, and **neither arm is a failure** — see {@link BillingFault}.
 *
 * `'nothing-priceable'` answers the empty shape of whichever read matched, which is what makes the
 * four tiles, the two tables and the invoice list show their empty states together. `'no-rate-card'`
 * answers a *populated* company that nothing can price: clients with no card, a money budget whose
 * burn is `null` rather than zero, and a draft whose every hour is in the `NO_RATE` bucket with its
 * reason on it.
 *
 * The zero-total draft is the body worth having. It is the one state where a screen can silently
 * lose an hour — from inside a join an unpriceable hour looks exactly like a priced one — and
 * `€0.00` with an empty Lines table and no note is precisely the render 019 D-g forbids.
 */
function billingVerdict(
  fault: BillingFault,
  which: 'summary' | 'rate-cards' | 'budgets' | 'invoices' | 'invoice',
): FaultVerdict {
  const empty = fault === 'nothing-priceable';

  if (which === 'summary') {
    return {
      kind: 'body',
      status: 200,
      body: {
        invoicedYearToDate: [],
        draftableNow: [],
        unapprovedHours: empty ? 0 : 38,
        clientsBilled: 0,
      },
    };
  }

  if (which === 'rate-cards') {
    return {
      kind: 'body',
      status: 200,
      // Every client, and every card null — `billing.html`'s Kessler Health row, for all of them.
      body: empty ? [] : FAULT_CLIENTS.map((client) => ({ client, card: null })),
    };
  }

  if (which === 'budgets') {
    return {
      kind: 'body',
      status: 200,
      body:
        empty ? []
        : [
            {
              projectId: FAULT_PROJECT_ID,
              projectKey: 'KESS',
              projectName: 'Kessler Portal',
              client: FAULT_CLIENTS[0],
              basis: 'MONEY',
              amountHours: null,
              amountMoney: { amount: 40000, currency: 'EUR' },
              approvedHours: 126,
              // Null, not zero — 126 hours nothing can price is *unknown* spend, not none.
              pricedToDate: null,
              burnPercent: null,
            },
          ],
    };
  }

  const invoice = {
    id: FAULT_INVOICE_ID,
    number: null,
    client: FAULT_CLIENTS[0],
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    status: 'DRAFT',
    currency: 'EUR',
    total: { amount: 0, currency: 'EUR' },
    lineCount: 0,
    approvedHours: 0,
    issuedAt: null,
    issuedBy: null,
  };

  if (which === 'invoices') {
    return { kind: 'body', status: 200, body: empty ? [] : [invoice] };
  }

  return {
    kind: 'body',
    status: 200,
    body: {
      invoice,
      billTo: null,
      termsDays: 30,
      lines: [],
      entries: [],
      excluded:
        empty ? []
        : [
            {
              reason: 'NO_RATE',
              hours: 68,
              detail: 'Mira Sund 32h · Dana Petrov 24h · Tomas Ek 12h',
            },
          ],
      budgetEffect: [],
    },
  };
}

function staffingVerdict(fault: StaffingFault, path: string, method: string): FaultVerdict {
  const people = [
    faultPerson(fault, '00000000-0000-4000-8000-0000000000f1', 'Mira Sund'),
    faultPerson(fault, '00000000-0000-4000-8000-0000000000f2', 'Tomas Ek'),
  ];

  const peopleRead = isPeopleRead(path, method);
  if (peopleRead === 'list') return { kind: 'body', status: 200, body: people };
  if (peopleRead === 'one') return { kind: 'body', status: 200, body: people[0] };

  if (isCapacityRead(path, method)) {
    return {
      kind: 'body',
      status: 200,
      body: {
        from: FAULT_FROM,
        to: FAULT_TO,
        people,
        // Served as its own subset rather than filtered by the panel, for the contract's own
        // reason: the chip and the row badge read one list and so cannot disagree.
        overAllocated: fault === 'over-allocated' ? people : [],
      },
    };
  }

  if (isMyCapacityRead(path, method)) {
    return {
      kind: 'body',
      status: 200,
      body: {
        from: FAULT_FROM,
        to: FAULT_TO,
        capacityHours: fault === 'no-profile' ? null : 80,
        absenceHours: fault === 'no-profile' ? null : 0,
        allocatedPercent: fault === 'over-allocated' ? 130 : 0,
        // The whole point of the field: a screen can say *no profile recorded* instead of
        // *failed to load*, which a null capacity on its own cannot tell apart.
        hasProfile: fault !== 'no-profile',
      },
    };
  }

  return { kind: 'none' };
}

/**
 * One person in the fabricated window.
 *
 * **The five nulls arrive together**, which is what makes `'no-profile'` worth having: the server
 * sends `profile`, `capacityHours`, `absenceHours`, `allocatedCapacityHours` and `approvedHours` as
 * one state, and a body carrying only the first would describe an answer no endpoint gives.
 * `utilisationPercent` is null in both arms and for two different reasons — no profile in one, no
 * allocated capacity to divide by in the other (017 D-i) — which is itself the thing the copy has
 * to word rather than print as `0%`.
 */
function faultPerson(fault: StaffingFault, id: string, displayName: string): unknown {
  const over = fault === 'over-allocated';
  return {
    person: {
      id,
      displayName,
      email: `${displayName.toLowerCase().replace(' ', '.')}@example.test`,
      avatarUrl: null,
    },
    profile:
      over ?
        {
          personId: id,
          weeklyHours: 40,
          jobTitle: 'Test Engineer',
          location: 'Malmö',
          experienceSince: '2020-09-01',
          experienceYears: 6,
        }
      : null,
    skills: [],
    capacityHours: over ? 480 : null,
    absenceHours: over ? 0 : null,
    allocatedPercent: over ? 130 : 0,
    allocatedCapacityHours: over ? 624 : null,
    freePercent: 0,
    peakPercent: over ? 130 : 0,
    peakOn: over ? '2026-10-12' : null,
    overAllocated: over,
    approvedHours: over ? 0 : null,
    utilisationPercent: null,
    freeFrom: over ? null : FAULT_FROM,
    allocations:
      over ?
        [
          {
            projectId: '00000000-0000-4000-8000-0000000000fb',
            projectKey: 'ACME',
            projectName: 'Acme Storefront',
            percent: 50,
            startsOn: FAULT_FROM,
            endsOn: FAULT_TO,
          },
          {
            projectId: '00000000-0000-4000-8000-0000000000fc',
            projectKey: 'LOYAL',
            projectName: 'Acme Loyalty',
            percent: 50,
            startsOn: FAULT_FROM,
            endsOn: FAULT_TO,
          },
          {
            projectId: '00000000-0000-4000-8000-0000000000fd',
            projectKey: 'BERG',
            projectName: 'Bergstrom Portal',
            percent: 30,
            startsOn: FAULT_FROM,
            endsOn: FAULT_TO,
          },
        ]
      : [],
    absences: [],
  };
}

/**
 * A card move that landed **and** put its column over the limit — story B3's 200 carrying a fact.
 *
 * The `card` is a placeholder for the reason this module's header gives: the seam never sees the
 * request body, so there is no target column to answer about. `breach` is the member a consumer
 * reads, and its `count` is deliberately one past its `limit` — a column *at* its limit is not in
 * breach, and a fault that fabricated an equal pair would arm the off-by-one rather than the state.
 */
function breachedMoveVerdict(path: string): FaultVerdict {
  const key = keyIn(path, 4);
  const columnId = '00000000-0000-4000-8000-0000000000fa';

  return {
    kind: 'body',
    status: 200,
    body: {
      card: {
        key,
        title: 'The card the fault could not read',
        typeName: 'Story',
        typeLevel: 'STORY',
        iconCode: 'story',
        countsTowardCompletion: true,
        columnId,
        status: { id: 'fault-status', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 },
        rank: '1024',
        labels: [],
        allowedTransitions: [],
        subtaskTotal: 0,
        subtaskDone: 0,
      },
      breach: { columnId, limit: 3, count: 4 },
    },
  };
}

/**
 * The synthetic response for an armed fault, or `null` to let the request go to the network.
 *
 * Returning a real `Response` rather than throwing is what keeps the injection honest: the caller
 * runs its ordinary `res.ok` / `res.json()` / `ApiError` path over it, so what is under test is the
 * client's own handling and not a second error path that only faults take.
 *
 * `hang` is the exception, and it is modelled rather than faked. It returns a promise that never
 * resolves and rejects only when the caller's `signal` aborts, **with `signal.reason`** — which for
 * `AbortSignal.timeout` is a `TimeoutError` DOMException, exactly what a real dead socket produces.
 * So `request`'s existing catch converts it to `TimeoutError` with no special case, and a
 * caller-driven cancellation during a hang still reads as an abort rather than a failure.
 */
export function maybeFault(
  path: string,
  signal?: AbortSignal,
  method = 'GET',
): Promise<Response> | null {
  const verdict = verdictFor(path, method);

  switch (verdict.kind) {
    case 'none':
      return null;

    case 'body':
      return Promise.resolve(
        new Response(JSON.stringify(verdict.body), {
          status: verdict.status,
          headers: {
            'Content-Type':
              verdict.status >= 400 ? 'application/problem+json' : 'application/json',
            ...(verdict.status >= 400
              ? { 'x-correlation-id': `fault-${Math.random().toString(36).slice(2, 10)}` }
              : {}),
          },
        }),
      );

    case 'hang':
      return new Promise<Response>((_resolve, reject) => {
        if (!signal) return; // no deadline to wait on: hang for the lifetime of the page
        if (signal.aborted) {
          reject(signal.reason);
          return;
        }
        signal.addEventListener('abort', () => reject(signal.reason), { once: true });
      });

    case 'drift':
      // A 200 whose body is the wrong shape. This must surface as a FAILED region, never as an
      // empty one: "the service is broken" and "there is nothing here" are different facts, and a
      // `catch` that turns the first into the second is the defect being guarded against.
      return Promise.resolve(
        new Response(JSON.stringify({ unexpected: 'shape' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    case 'status':
      return Promise.resolve(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Injected fault',
            status: verdict.status,
            detail: 'This failure was injected from the console for resilience testing.',
          }),
          {
            status: verdict.status,
            headers: {
              'Content-Type': 'application/json',
              // A correlation id on every failure, so the notification's copyable reference and
              // the dedupe-by-id behaviour can both actually be exercised.
              'x-correlation-id': `fault-${Math.random().toString(36).slice(2, 10)}`,
              ...(verdict.retryAfter !== null ? { 'retry-after': String(verdict.retryAfter) } : {}),
            },
          },
        ),
      );
  }
}

/** Exposes the switches on `window`. Called once from `main.tsx`, behind a literal DEV check. */
export function installFaultConsole(): void {
  const api = {
    status(status: number, retryAfterSeconds?: number) {
      faults.status = status;
      faults.retryAfter = retryAfterSeconds ?? null;
      console.info(
        `[tcms faults] every request → ${status}`,
        retryAfterSeconds ? `(Retry-After: ${retryAfterSeconds}s)` : '',
      );
    },
    hang() {
      faults.hang = true;
      console.info('[tcms faults] requests will never answer — expect the timeout budget to fire');
    },
    expire() {
      faults.expireOnce = true;
      console.info('[tcms faults] the next request will 401 once');
    },
    drift() {
      faults.drift = true;
      console.info('[tcms faults] responses will be 200 with a drifted shape');
    },
    /**
     * How the tracker misbehaves. `tcmsFaults.tracker(null)` restores it without clearing anything
     * else — a session usually wants the tracker broken and the rest of the API healthy.
     */
    tracker(fault: TrackerFault | null) {
      faults.tracker = fault;
      console.info(
        fault === null
          ? '[tcms faults] the tracker works again'
          : `[tcms faults] the tracker is now: ${fault}`,
      );
    },
    /**
     * How this product's own tracker misbehaves. `tcmsFaults.issue(null)` restores it without
     * clearing anything else, for `tracker()`'s reason — a session usually wants one thing broken.
     */
    issue(fault: IssueFault | null) {
      faults.issue = fault;
      console.info(
        fault === null
          ? '[tcms faults] issues behave again'
          : `[tcms faults] issues are now: ${fault}`,
      );
    },
    /**
     * How the rank write misbehaves. `tcmsFaults.rank(null)` restores it without clearing anything
     * else, for `tracker()`'s reason.
     */
    rank(fault: RankFault | null) {
      faults.rank = fault;
      console.info(
        fault === null
          ? '[tcms faults] the rank write lands again'
          : `[tcms faults] the rank write is now: ${fault}`,
      );
    },
    /** Every card move reports its column over the limit. `tcmsFaults.wip(false)` restores it. */
    wip(on = true) {
      faults.wip = on;
      console.info(
        on
          ? '[tcms faults] every card move will land its column over its WIP limit'
          : '[tcms faults] WIP limits are respected again',
      );
    },
    /**
     * How the release module misbehaves. `tcmsFaults.release(null)` restores it without clearing
     * anything else, for `tracker()`'s reason.
     */
    release(fault: ReleaseFault | null) {
      faults.release = fault;
      console.info(
        fault === null
          ? '[tcms faults] releases behave again'
          : `[tcms faults] releases are now: ${fault}`,
      );
    },
    /**
     * How an import misbehaves. `tcmsFaults.importing(null)` restores it without clearing anything
     * else, for `tracker()`'s reason.
     *
     * ~~`import`~~ — a reserved word cannot be a shorthand method name that reads as a verb here,
     * and `importing` is what the call site says anyway: `tcmsFaults.importing('midway')`.
     */
    importing(fault: ImportFault | null) {
      faults.import = fault;
      console.info(
        fault === null
          ? '[tcms faults] imports behave again'
          : `[tcms faults] imports are now: ${fault}`,
      );
    },
    /**
     * How the time module misbehaves. `tcmsFaults.time(null)` restores it without clearing anything
     * else, for `tracker()`'s reason.
     */
    time(fault: TimeFault | null) {
      faults.time = fault;
      console.info(
        fault === null
          ? '[tcms faults] timesheets behave again'
          : `[tcms faults] timesheets are now: ${fault}`,
      );
    },
    /**
     * How many clients this company has — `none` to walk the absence, `some` to walk the presence.
     *
     * `tcmsFaults.clients(null)` gives the instance back its real answer. Neither state is a
     * failure: this is the one arm here that exists because a *correct* state is unreachable by
     * using the app, not because a broken one is.
     */
    clients(fault: ClientsFault | null) {
      faults.clients = fault;
      console.info(
        fault === null
          ? '[tcms faults] clients are the instance’s own again'
          : fault === 'none'
            ? '[tcms faults] this company now has NO clients — every client surface must vanish'
            : '[tcms faults] this company now has two clients',
      );
    },
    /**
     * Which unpriceable state the billing screens are in — `no-rate-card` to walk the second
     * exclusion bucket, `nothing-priceable` to walk every empty state at once.
     *
     * `tcmsFaults.billing(null)` gives the instance back its own figures. Neither state is a
     * failure: this is the second arm here that exists because a *correct* state is unreachable on
     * a seeded instance, not because a broken one is.
     */
    billing(fault: BillingFault | null) {
      faults.billing = fault;
      console.info(
        fault === null
          ? '[tcms faults] billing is the instance’s own again'
          : fault === 'no-rate-card'
            ? '[tcms faults] no client has a rate card — every approved hour must appear in the NO_RATE bucket, named'
            : '[tcms faults] nothing is priceable — every billing region must render its empty state',
      );
    },
    /**
     * How the staffing reads misbehave. `tcmsFaults.staffing(null)` restores them without clearing
     * anything else, for `tracker()`'s reason.
     */
    staffing(fault: StaffingFault | null) {
      faults.staffing = fault;
      console.info(
        fault === null
          ? '[tcms faults] staffing behaves again'
          : `[tcms faults] staffing is now: ${fault}`,
      );
    },
    only(pathContains: string | null) {
      faults.pathContains = pathContains;
      console.info(`[tcms faults] scoped to paths containing ${JSON.stringify(pathContains)}`);
    },
    off() {
      faults.status = null;
      faults.retryAfter = null;
      faults.hang = false;
      faults.expireOnce = false;
      faults.drift = false;
      faults.pathContains = null;
      faults.tracker = null;
      faults.issue = null;
      faults.rank = null;
      faults.wip = false;
      faults.release = null;
      faults.import = null;
      faults.time = null;
      faults.staffing = null;
      /*
        `clients` was missing from this list until 019 T5 added `billing` beside it, so
        `tcmsFaults.off()` left a fabricated population in place while announcing everything was
        cleared — the one failure mode of this whole module, since a developer then reads a real
        screen against invented data. Both are here now.
      */
      faults.clients = null;
      faults.billing = null;
      console.info('[tcms faults] cleared');
    },
    state: () => ({ ...faults }),
  };

  (window as unknown as Record<string, unknown>).tcmsFaults = api;
  console.info(
    '[tcms faults] available — try tcmsFaults.status(500), .hang(), .expire(), '
      + ".tracker('403'), .issue('conflict'), .rank('conflict'), .wip(), "
      + ".release('empty'), .importing('midway'), .time('rejected'), .clients('none'), "
      + ".staffing('no-profile'), .billing('no-rate-card'), .off()",
  );
}
