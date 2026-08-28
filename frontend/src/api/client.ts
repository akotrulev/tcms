/**
 * Thin fetch wrapper. All requests go to /api/v1 — the real Spring Boot API, reached in
 * development through the Vite dev proxy and in production from the same origin that served the
 * SPA. Same origin either way, so nothing here changes between the two.
 *
 * Three things here are load-bearing rather than boilerplate:
 *
 *   1. Every call carries a **timeout budget**. A request nothing aborts hangs until the
 *      browser's own ~300s limit, which renders as a region spinning forever — a state no
 *      amount of loading/failed branching can express.
 *   2. `TimeoutError` and `AbortError` are kept apart. A timeout is a failure to surface;
 *      an abort (unmount, navigation, a superseded query) is **not a failure at all** and
 *      must never be rendered or notified.
 *   3. A **dev-only fault seam** sits in front of `fetch` (`maybeFault`). None of the resilience
 *      behaviour above can be triggered by using the app normally, so it is driven from the
 *      console instead — see `lib/devFaults.ts`. Every `import.meta.env.DEV` guarding it is a
 *      literal, so the whole seam is statically eliminated from a production build.
 */

import { maybeFault } from '../lib/devFaults';
import { safeStorage } from '../lib/safeStorage';

/**
 * The API's own prefix, relative so the dev proxy and the deployment both reach it.
 *
 * Exported because one call this app makes is **not** a `fetch`: the SSO authorize endpoint answers
 * a 302 to a third-party origin, so the browser has to be sent there rather than a response read
 * back (§222). That is the only legitimate caller — anything else that needs this prefix wants
 * `api.*`, which applies it already.
 */
export const BASE = '/api/v1';

/**
 * Budgets per endpoint class, not one global constant — a typeahead that waits 10s is
 * broken, and an export that gives up after 10s is equally broken.
 */
export const TIMEOUTS = {
  /** Type-ahead and search-as-you-type. */
  typeahead: 3_000,
  /** Ordinary interactive reads and writes. */
  interactive: 10_000,
  /** Reports, exports, bulk operations. */
  long: 60_000,
} as const;

export class ApiError extends Error {
  // Declared as fields rather than constructor parameter properties: the project sets
  // `erasableSyntaxOnly`, which forbids the parameter-property shorthand.
  readonly status: number;
  readonly detail: string;
  /**
   * RFC-7807 `title` — the problem's *kind*, as opposed to `detail`'s account of this instance.
   *
   * Carried because one status code can mean two things that want opposite advice: a 409 is
   * either "someone else saved first" (reload and reapply) or "this is not in a state that
   * allows it" (reloading changes nothing). `describeError` reads the title to tell them apart.
   * Null when the body carried none.
   */
  readonly title: string | null;
  /** RFC-7807 `errors` map, when the server rejected specific fields. */
  readonly fieldErrors: Record<string, string[]> | null;
  readonly correlationId: string | null;
  /**
   * The `Retry-After` header, verbatim. Kept as sent (it may be delta-seconds *or* an HTTP
   * date) and parsed at the point of use by `retryAfterMs`.
   */
  readonly retryAfter: string | null;
  /**
   * `Problem.line` — the 1-based line of a posted text the refusal is about (005 S1).
   *
   * Null on everything except the four gherkin refusals, which is what makes it worth carrying at
   * all: 003 C4 makes the parse refusal the case editor's whole product, and the sentence has to
   * land **on the line it names**. The alternative the contract's own description rules out is the
   * SPA regexing a number out of `detail`'s English — which is a parser for prose, in a client,
   * against a string the server is free to reword.
   */
  readonly line: number | null;
  /**
   * `Problem.permission` — the catalogue key the refusal is about (005 C1a).
   *
   * The Refused state's whole subject: a 403 that says "ask a project lead" and nothing else
   * leaves the reader with no request to make, while "you need `case:approve`" — labelled from the
   * same catalogue `GET /me/permissions` is labelled from — is a sentence they can take to an
   * administrator. Read as an unconstrained string rather than a `PermissionKey`: the server's
   * catalogue is the authority and a key this client cannot label must still render as its own
   * text, never be dropped.
   *
   * **Null on most refusals today**, and every consumer must degrade rather than crash: the
   * envelope carries the member, but the backend sites that fill it in land progressively.
   */
  readonly permission: string | null;
  /**
   * `Problem.scope` — where the permission would have to be held: `SYSTEM` · `COMPANY` · `PROJECT`.
   *
   * It changes who to ask, which is the actionable half. Null whenever `permission` is.
   */
  readonly scope: string | null;

  constructor(
    status: number,
    detail: string,
    fieldErrors: Record<string, string[]> | null = null,
    correlationId: string | null = null,
    retryAfter: string | null = null,
    title: string | null = null,
    line: number | null = null,
    permission: string | null = null,
    scope: string | null = null,
  ) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
    this.title = title;
    this.fieldErrors = fieldErrors;
    this.correlationId = correlationId;
    this.retryAfter = retryAfter;
    this.line = line;
    this.permission = permission;
    this.scope = scope;
  }

  /** True when the server rejected individual fields, so a form can land them per input. */
  get hasFieldErrors(): boolean {
    return this.fieldErrors !== null && Object.keys(this.fieldErrors).length > 0;
  }
}

/** A request that ran past its deadline. Distinct from a cancellation. */
export class TimeoutError extends Error {
  constructor(ms: number) {
    super(`The server did not respond within ${Math.round(ms / 1000)} seconds.`);
    this.name = 'TimeoutError';
  }
}

/**
 * True when the reason a request ended was cancellation — an unmount, a navigation, or a
 * newer query superseding this one. Callers must not render or notify these.
 */
export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * How long the server asked us to wait, in milliseconds, or `null` if it didn't say.
 *
 * `Retry-After` comes in two forms and only handling one of them is a common bug: a
 * delta-seconds count (`Retry-After: 30`) or an HTTP date (`Retry-After: Wed, 21 Oct 2026
 * 07:28:00 GMT`). A clock-skewed client can compute a negative delay from the date form, which
 * is why the result is floored at zero rather than trusted.
 */
export function retryAfterMs(error: unknown): number | null {
  if (!(error instanceof ApiError) || !error.retryAfter) return null;

  const seconds = Number(error.retryAfter);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);

  const at = Date.parse(error.retryAfter);
  if (Number.isNaN(at)) return null;
  return Math.max(0, at - Date.now());
}

/**
 * A key identifying one **user intent**, for `Idempotency-Key` on a write.
 *
 * The distinction that makes this work: the key belongs to the *intent* ("create this test
 * case"), not to the *attempt*. Every retry of that intent carries the same key, so a server
 * that already processed the first attempt returns the original result instead of creating a
 * second record. Generating a fresh key per attempt would make the header decorative.
 *
 * This is what makes a timed-out POST safe to retry at all — a request that ran past its
 * deadline may well have succeeded server-side, and without a key there is no way to ask again
 * without risking a duplicate.
 */
export function newIntentKey(): string {
  // `randomUUID` needs a secure context; a dev server on plain http over a LAN IP is not one.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Composes the caller's cancel signal with a deadline, rather than choosing between them:
 * TanStack Query's `signal` still cancels on unmount, and the timeout still fires.
 */
export function budget(ms: number, external?: AbortSignal): AbortSignal {
  const deadline = AbortSignal.timeout(ms);
  return external ? AbortSignal.any([external, deadline]) : deadline;
}

function authHeader(): Record<string, string> {
  // Read through `safeStorage`, not `localStorage` directly. `AuthProvider` *writes* the token
  // through the guarded wrapper, which falls back to an in-memory map when localStorage throws
  // (private mode, quota, a locked-down profile). Reading raw would then find nothing and send
  // every request unauthenticated — a session that appears to work until the first API call.
  const token = safeStorage.get('tcms.token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

interface RequestOptions {
  signal?: AbortSignal;
  /** Which budget class this endpoint belongs to. Defaults to `interactive`. */
  timeoutMs?: number;
  headers?: Record<string, string>;
  /**
   * Idempotency key for a write, from `newIntentKey()`. One key per user intent, reused across
   * every retry of that intent, so the server can dedupe rather than create a second record.
   */
  idempotencyKey?: string;
  /**
   * How the success body is read. `json` unless the operation's success body is a **document**.
   *
   * Set by the verb, not by the caller: `getText` is the only thing that passes `'text'`, because
   * the media type is a property of the endpoint rather than of the call. Reading a `text/plain`
   * body with `res.json()` throws a `SyntaxError` at the transport layer, which surfaces three
   * layers up as a region that failed to load for no stated reason.
   */
  responseType?: 'json' | 'text';
}

async function request<T>(path: string, init: RequestInit = {}, options: RequestOptions = {}): Promise<T> {
  const timeoutMs = options.timeoutMs ?? TIMEOUTS.interactive;

  const signal = budget(timeoutMs, options.signal);

  let res: Response;
  try {
    // The dev fault seam. `import.meta.env.DEV` is a literal so Rollup drops the branch — and
    // `lib/devFaults` with it — from a production build; see that module's header.
    // The method travels with the path because the tracker arm needs it: `POST …/tracker/issues`
    // (filing) and `GET …/tracker/issues` (the typeahead) are one path with two contracts, and the
    // fault has to answer the right one — a 502 problem for the first, a degraded 200 for the second.
    const faulted = import.meta.env.DEV
      ? maybeFault(path, signal, (init.method as string | undefined) ?? 'GET')
      : null;
    res = await (
      faulted ??
      fetch(`${BASE}${path}`, {
        ...init,
        signal,
        headers: {
          // A FormData body must NOT carry a hand-set Content-Type. The browser generates
          // `multipart/form-data; boundary=…`, and the boundary is the only thing that tells the
          // server where one part ends — set the header here and it is lost, after which the
          // server cannot parse a single part and answers 400 for a request that looks correct
          // in devtools.
          ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
          ...authHeader(),
          ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
          ...options.headers,
          ...init.headers,
        },
      })
    );
  } catch (error) {
    // AbortSignal.timeout() rejects with a TimeoutError DOMException; a caller-driven
    // cancellation is an AbortError. Only the first is a failure worth surfacing.
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new TimeoutError(timeoutMs);
    }
    throw error;
  }

  if (res.status === 204) return undefined as T;

  if (!res.ok) {
    // `statusText` is empty over HTTP/2 and on any bodiless response the origin didn't label —
    // the real API's 401, for one. Without a floor here `ApiError.detail` becomes '' and every
    // consumer that falls back to it renders a blank message.
    let detail = res.statusText || `The request failed (HTTP ${res.status}).`;
    let fieldErrors: Record<string, string[]> | null = null;
    let title: string | null = null;
    let line: number | null = null;
    let permission: string | null = null;
    let scope: string | null = null;
    try {
      const body = await res.json();
      detail = body.detail ?? body.title ?? detail;
      title = typeof body.title === 'string' ? body.title : null;
      // Read, never derived. A body that omits it — every refusal that is not about a text, and
      // any server older than 005 S1 — leaves this null, and the caller anchors nothing.
      line = typeof body.line === 'number' && Number.isFinite(body.line) ? body.line : null;
      // Same rule as `line`, and the same reason it is a typeof check rather than a cast: the two
      // members are optional in the contract, so a refusal that omits them must leave nulls here
      // and not the string "undefined" inside a sentence.
      permission = typeof body.permission === 'string' ? body.permission : null;
      scope = typeof body.scope === 'string' ? body.scope : null;
      if (body.errors && typeof body.errors === 'object') {
        fieldErrors = body.errors as Record<string, string[]>;
      }
    } catch {
      /* non-JSON error body — the status text is all we have, and it's not raw HTML */
    }
    throw new ApiError(
      res.status,
      detail,
      fieldErrors,
      res.headers.get('x-correlation-id'),
      res.headers.get('retry-after'),
      title,
      line,
      permission,
      scope,
    );
  }

  return (options.responseType === 'text' ? res.text() : res.json()) as Promise<T>;
}

/** Serializes filter objects to a query string, dropping empties and expanding arrays. */
export function qs(params: Record<string, unknown> = {}): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      if (v.length === 0) continue;
      v.forEach((item) => sp.append(k, String(item)));
    } else {
      sp.set(k, String(v));
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>(path, {}, options),
  /** Pass `idempotencyKey` from `newIntentKey()` on any create the user could retry. */
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}) }, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body ?? {}) }, options),
  /**
   * A whole-value replacement, for a sub-resource whose body IS the new state — today only a doc
   * page's publication status. Distinct from `patch` because the contract distinguishes them and
   * `contractOperations.test.ts` checks the method, not just the path: a `PATCH` sent to a `PUT`
   * operation is an endpoint the server does not have.
   */
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body ?? {}) }, options),
  del: <T>(path: string, options?: RequestOptions) => request<T>(path, { method: 'DELETE' }, options),
  /**
   * A POST whose **request** is a document and whose response is ordinary JSON — today only
   * `POST .../cases/parse-gherkin`, which takes a pasted scenario as `text/plain`.
   *
   * Separate from `post` rather than a body-type branch inside it, for `upload`'s reason: the two
   * differ in what they serialize. `post` sends `JSON.stringify(body ?? {})`, which would put a
   * quoted string with escaped newlines on the wire and answer 400 against a handler bound to a
   * raw body. The `Content-Type` is set on the `RequestInit` rather than through `options.headers`
   * so a caller cannot forget it — the endpoint refuses any other type with a 415.
   */
  postText: <T>(path: string, text: string, options?: RequestOptions) =>
    request<T>(
      path,
      { method: 'POST', body: text, headers: { 'Content-Type': 'text/plain; charset=utf-8' } },
      options,
    ),
  /**
   * A POST whose **request is JSON and whose success body is a document** — today only
   * `POST .../cases/render-gherkin` (003 C1/D2).
   *
   * The mirror image of `postText` above, and a third verb rather than an option on either,
   * because the two ends are independent: this one serializes the body the way `post` does and
   * reads the response the way `getText` does. Spelling it as `post` would hand `res.json()` a
   * `.feature` file and fail with a `SyntaxError` under the editor — the exact failure
   * `useCaseFeature`'s header records for the read side.
   */
  postForText: (path: string, body: unknown, options?: RequestOptions) =>
    request<string>(path, { method: 'POST', body: JSON.stringify(body ?? {}) }, {
      ...options,
      responseType: 'text',
    }),
  /**
   * A GET whose **success body is a document** — today only `GET .../cases/{caseId}/feature`.
   *
   * The body is the artifact a tester pastes into a Cucumber suite, so it is served as
   * `text/plain` and there is no JSON schema to type it against; the return is `string` and that
   * is the whole of what the contract promises. It still goes through `request`, so the timeout
   * budget, the bearer token and the dev fault seam all apply — a hand-rolled `fetch` at the call
   * site would have none of the three, which is the shape `fetchArtifact` below exists to avoid
   * for the same reason.
   */
  getText: (path: string, options?: RequestOptions) =>
    request<string>(path, {}, { ...options, responseType: 'text' }),
  /**
   * A `multipart/form-data` POST — today only evidence upload.
   *
   * <p>Separate from `post` rather than a body-type branch inside it, because the two differ in
   * more than serialization: the caller must not set `Content-Type` (see `request`), and an upload
   * carries a file over a link the product does not choose, so it defaults to the **long** budget.
   * A 10 MB attachment on hotel wifi outruns the 10s interactive one, and the failure looks like a
   * broken server rather than a slow connection.
   */
  upload: <T>(path: string, form: FormData, options?: RequestOptions) =>
    request<T>(path, { method: 'POST', body: form }, { timeoutMs: TIMEOUTS.long, ...options }),
};

/**
 * Fetches a **server-supplied URL** whose body is bytes rather than JSON — today a report artifact.
 *
 * ## Why this is not `api.get`, and not an `<a href>` either
 *
 * Two properties of this app make the obvious spellings wrong, and both are silent when wrong.
 *
 * *An `<a href>` cannot authenticate.* The session travels in an `Authorization` header the client
 * sets explicitly — `SecurityConfiguration` is `STATELESS` with no cookie anywhere — so a plain
 * anchor pointed at `/api/v1/…` sends no credential and the download answers **401**. It looks
 * like a link; it is a link to a sign-in failure. (`EvidencePanel` renders `Evidence.url` as an
 * anchor and has the same hole; noted in the handoff rather than swept here, because evidence is
 * served `inline` and its fix is a different rendering, not this helper.)
 *
 * *`api.get` cannot take this URL.* `request` prepends `BASE`, and `ReportJob.downloadUrl` already
 * carries `/api/v1` because the **server** composes it — deliberately, so the client holds no copy
 * of the server's routing (`openapi.yaml` on `downloadUrl`, §67 on `Evidence.url`). Passing it to
 * `api.get` produces `/api/v1/api/v1/…` and a 404 that reads as a missing report. `request` also
 * ends in `res.json()`, which would parse a Markdown report as JSON and fail at the wrong layer.
 *
 * ## The same-origin guard is not defensive programming
 *
 * The URL is data from the server and this function attaches the caller's bearer token to it. An
 * absolute URL — a misconfigured `tcms.public-url`, a compromised or simply wrong deployment
 * setting — would send that token to another host. So the URL must be **root-relative and inside
 * `BASE`**, checked rather than trusted, and anything else throws before a request is made. It
 * lives in `artifactResponse` below, which both this and `fetchArtifactJson` go through: one guard
 * for one hazard, because the copy that drifts is whichever reader was added second.
 */
export async function fetchArtifact(
  url: string,
  options: { signal?: AbortSignal; timeoutMs?: number } = {},
): Promise<{ blob: Blob; filename: string | null }> {
  const res = await artifactResponse(url, options);
  return { blob: await res.blob(), filename: filenameFrom(res.headers.get('content-disposition')) };
}

/**
 * The same artifact, read as JSON — what the on-screen report view renders (F2).
 *
 * **It is a sibling of `fetchArtifact` rather than a `blob.text()` at the call site**, so the guard
 * above, the budget and the error mapping are written once. The stored `JSON` model is the *same
 * artifact route* as a Markdown download; only the parsing differs, and a screen that reached it
 * through `blob → text → JSON.parse` would be re-deriving three of the four things this file owns
 * in order to change the fourth.
 *
 * **The return type is `unknown`, deliberately, and the caller must parse it.** Every other read in
 * this app is typed from `openapi.yaml`; this one cannot be, because the route is declared as
 * binary — it serves five formats' bytes and a schema there would be a lie about four of them. What
 * comes back is a **file**, possibly written months ago by a deployment this client has never seen
 * (`JsonReportRenderer`'s own argument for not sharing the web mapper), so the shape is checked at
 * the boundary by `lib/reportDocument.ts` and a drifted document fails loudly there rather than
 * rendering as a report with sections missing.
 */
export async function fetchArtifactJson(
  url: string,
  options: { signal?: AbortSignal; timeoutMs?: number } = {},
): Promise<unknown> {
  const res = await artifactResponse(url, options);
  return res.json();
}

/**
 * The guarded, budgeted, credential-carrying request both readers above share.
 *
 * The guard is the reason this is one function: it is the only thing standing between a
 * server-supplied string and this user's bearer token, and a second copy of it is a second thing to
 * remember to keep correct.
 */
async function artifactResponse(
  url: string,
  options: { signal?: AbortSignal; timeoutMs?: number },
): Promise<Response> {
  if (!url.startsWith(`${BASE}/`)) {
    throw new Error(
      `Refusing to fetch a report artifact from '${url}': the credential goes with the request, `
        + `so the URL must be a root-relative path under ${BASE}.`,
    );
  }

  const timeoutMs = options.timeoutMs ?? TIMEOUTS.long;
  const signal = budget(timeoutMs, options.signal);
  let res: Response;
  try {
    // Faulted too, so `tcmsFaults` covers downloads exactly as the MSW wildcard did — the
    // artifact routes are where a 60s budget and a mid-download 401 are hardest to reach by hand.
    const faulted = import.meta.env.DEV ? maybeFault(url, signal) : null;
    res = await (faulted ?? fetch(url, { signal, headers: authHeader() }));
  } catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError') throw new TimeoutError(timeoutMs);
    throw error;
  }

  if (!res.ok) {
    // Deliberately bodiless on this route's 403 — the response is typed to a binary resource, so
    // there is no problem document to read and `statusText` is the whole of what was said.
    throw new ApiError(
      res.status,
      res.statusText || `The download failed (HTTP ${res.status}).`,
      null,
      res.headers.get('x-correlation-id'),
      res.headers.get('retry-after'),
      null,
    );
  }

  return res;
}

/**
 * The `filename` out of a `Content-Disposition`, or null when the header did not name one.
 *
 * Null rather than a guess: the caller has a better fallback than this parser does (it knows the
 * report type and the format), and a filename invented here would be the same invention in every
 * caller with no way to tell it from a real one.
 */
function filenameFrom(header: string | null): string | null {
  if (!header) return null;
  const quoted = /filename\s*=\s*"([^"]*)"/i.exec(header);
  if (quoted?.[1]) return quoted[1];
  const bare = /filename\s*=\s*([^;]+)/i.exec(header);
  return bare?.[1]?.trim() || null;
}
