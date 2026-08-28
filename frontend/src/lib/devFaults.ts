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
}

/** The five ways somebody else's tracker fails us. See this module's header. */
export type TrackerFault = 'slow' | '401' | '403' | 'down' | 'drift';

const faults: FaultState = {
  status: null,
  retryAfter: null,
  hang: false,
  expireOnce: false,
  drift: false,
  pathContains: null,
  tracker: null,
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
      console.info('[tcms faults] cleared');
    },
    state: () => ({ ...faults }),
  };

  (window as unknown as Record<string, unknown>).tcmsFaults = api;
  console.info(
    "[tcms faults] available — try tcmsFaults.status(500), .hang(), .expire(), .tracker('403'), .off()",
  );
}
