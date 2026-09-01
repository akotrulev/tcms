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
      console.info('[tcms faults] cleared');
    },
    state: () => ({ ...faults }),
  };

  (window as unknown as Record<string, unknown>).tcmsFaults = api;
  console.info(
    '[tcms faults] available — try tcmsFaults.status(500), .hang(), .expire(), '
      + ".tracker('403'), .issue('conflict'), .rank('conflict'), .wip(), "
      + ".release('empty'), .off()",
  );
}
