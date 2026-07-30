/**
 * The single sink for client-side faults.
 *
 * A caught error that only reaches `console.error` is invisible: nobody has DevTools open on
 * the machine where the bug actually happens. Everything that catches — the error
 * boundaries, the global `error`/`unhandledrejection` listeners, React's own recoverable-error
 * hook — reports here, so there is one place to point at a real collector later.
 *
 * There is no backend yet (see `src/mocks/`), so the transport is deliberately a stub with
 * the shape already correct: a rate-limited POST to a collector endpoint. When the Spring
 * Boot service lands, only `send()` changes — no call site does.
 */

import { ApiError, TimeoutError, isAbort } from '../api/client';

/** Where the fault was caught. Lets a report say "the run detail region" rather than "somewhere". */
export type ErrorSource =
  | 'root-boundary'
  | 'route-boundary'
  | 'region-boundary'
  | 'window-error'
  | 'unhandled-rejection'
  | 'recoverable-render'
  | 'query'
  | 'mutation';

export interface ClientErrorReport {
  source: ErrorSource;
  /** Error name + message. Kept server-side only — never rendered (may carry PII or a body). */
  message: string;
  stack: string | null;
  /** The route the user was on, so a report is reproducible. */
  path: string;
  /** Ties the client report to the server-side log entry for the same failure. */
  correlationId: string | null;
  /** HTTP status when the fault came from a response rather than a render. */
  status: number | null;
  /** The build this tab is running, so version skew is visible in the logs. */
  buildId: string;
  at: string;
  /** Extra context the call site knows and the error does not (which region, which entity). */
  context: Record<string, string> | null;
}

/**
 * Stamped at build time by Vite (see `vite.config.ts`). Used both here and by the
 * version-skew check, so a report from a tab running last week's build says so.
 */
export const BUILD_ID: string =
  typeof __BUILD_ID__ === 'string' && __BUILD_ID__.length > 0 ? __BUILD_ID__ : 'dev';

/**
 * Rate limit. An error in a render loop can fire thousands of times a second; an unbounded
 * reporter then turns one bug into a self-inflicted denial of service against the collector.
 */
const MAX_REPORTS_PER_WINDOW = 20;
const WINDOW_MS = 60_000;

let windowStartedAt = 0;
let reportsInWindow = 0;
let suppressionLogged = false;

/** Errors already reported, so a re-render of a broken subtree doesn't re-report it. */
const seen = new Set<string>();

function fingerprint(error: unknown, source: ErrorSource): string {
  const name = error instanceof Error ? error.name : 'unknown';
  const message = error instanceof Error ? error.message : String(error);
  return `${source}:${name}:${message}`;
}

function allow(): boolean {
  const now = Date.now();
  if (now - windowStartedAt > WINDOW_MS) {
    windowStartedAt = now;
    reportsInWindow = 0;
    suppressionLogged = false;
  }
  reportsInWindow += 1;
  if (reportsInWindow <= MAX_REPORTS_PER_WINDOW) return true;
  if (!suppressionLogged) {
    suppressionLogged = true;
    console.warn('[tcms] client error reporting rate limit reached — suppressing until the window resets');
  }
  return false;
}

function send(report: ClientErrorReport): void {
  // Placeholder transport. `keepalive` matters: a fault during unload still needs to leave.
  if (import.meta.env.DEV) {
    console.error('[tcms] client error report', report);
    // Also kept on `window` so a report can be read back after the fact — a console object that
    // has to be expanded by hand is no use to an automated check or to a bug report.
    const w = window as unknown as { __tcmsErrors?: ClientErrorReport[] };
    (w.__tcmsErrors ??= []).push(report);
    return;
  }
  try {
    void fetch('/api/v1/client-errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(report),
      keepalive: true,
    }).catch(() => {
      /* the reporter must never be the thing that throws */
    });
  } catch {
    /* likewise */
  }
}

/**
 * Reports a fault. Safe to call from anywhere, including a catch block that must not throw.
 *
 * Cancellations are dropped on the floor: an `AbortError` fires on every navigation away from
 * a loading page, and reporting them would bury the real faults under routine noise.
 */
export function reportClientError(
  error: unknown,
  source: ErrorSource,
  context: Record<string, string> | null = null,
): void {
  if (isAbort(error)) return;

  const print = fingerprint(error, source);
  if (seen.has(print)) return;
  seen.add(print);
  if (!allow()) return;

  const status = error instanceof ApiError ? error.status : error instanceof TimeoutError ? 408 : null;

  send({
    source,
    message: error instanceof Error ? `${error.name}: ${error.message}` : String(error),
    stack: error instanceof Error ? (error.stack ?? null) : null,
    path: typeof window === 'undefined' ? '' : window.location.pathname + window.location.search,
    correlationId: error instanceof ApiError ? error.correlationId : null,
    status,
    buildId: BUILD_ID,
    at: new Date().toISOString(),
    context,
  });
}

/** Clears the dedupe memory. Test-only seam — a fresh test must not inherit another's fingerprints. */
export function resetReportedErrors(): void {
  seen.clear();
  reportsInWindow = 0;
  windowStartedAt = 0;
}
