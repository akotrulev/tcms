import { Banner } from './kit';
import { Button } from './ui';
import { ErrorBoundary, type FallbackProps } from 'react-error-boundary';
import { useEffect, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { handlePossibleSkew, isChunkLoadError, reloadOntoCurrentBuild } from '../app/versionSkew';
import { reportClientError, type ErrorSource } from '../lib/reportClientError';

/**
 * Error boundaries — three tiers.
 *
 * An unhandled exception thrown during render unmounts the tree **up to the nearest
 * boundary**. Before this file there was no boundary anywhere in the app, which meant any
 * render throw — a `.map` on an undefined field, a bad date, a chunk that failed to
 * download — blanked the entire SPA to a white screen with no way back but a manual reload.
 * That is the single highest-severity gap of the resilience gate.
 *
 * This is the Vite lane: there is no `error.tsx` / `global-error.tsx` file convention, so the
 * tiers are composed explicitly with `react-error-boundary`.
 *
 *   `RootErrorBoundary`    — the last line of defence, wrapping the providers.
 *   `RouteErrorBoundary`   — per route, inside the shell, so the rail and top bar survive and
 *                            the user can navigate *away* from a broken page.
 *   `RegionErrorBoundary`  — around an independently-failing widget, so one dashboard tile
 *                            crashing does not take the dashboard with it.
 *
 * Shared rules, enforced here rather than trusted to each fallback:
 *   - **Never render `error.message` or a stack.** Response bodies carry PII and JSON; a stack
 *     names our internals. The fallback shows friendly copy; the detail goes to the report.
 *   - **Always report**, with the source and the route, through the one sink.
 *   - **Always offer a real recovery action**, not just an apology.
 *   - **A failed chunk load is not a terminal error** — its recovery is a reload onto the
 *     current build, guarded so it can happen at most once.
 */

// ------------------------------------------------------------------- shared ----

/**
 * A chunk-load failure means this tab outlived its build, not that the app is broken. The
 * recovery is a reload, and a fallback that merely *displays* the error is a dead end the user
 * cannot escape.
 *
 * The reload runs from an effect rather than during render because rendering must stay pure —
 * and because it gives the guard in `handlePossibleSkew` a chance to say "already tried once",
 * which is what keeps a genuinely broken build from becoming an infinite reload loop.
 */
function useSkewRecovery(error: unknown): 'recovering' | 'manual' | 'not-skew' {
  const outcome = isChunkLoadError(error)
    ? // Boundary-level recovery has no view of unsaved work, so it never force-reloads: it
      // defers to the guard and, failing that, offers the reload as a button.
      handlePossibleSkew(error, false)
    : ({ kind: 'not-skew' } as const);

  useEffect(() => {
    if (outcome.kind === 'reloading') window.location.reload();
  }, [outcome.kind]);

  if (outcome.kind === 'not-skew') return 'not-skew';
  return outcome.kind === 'reloading' ? 'recovering' : 'manual';
}

/** The stale-build fallback, shared by every tier. */
function SkewFallback({ mode, testId }: { mode: 'recovering' | 'manual'; testId: string }) {
  return (
    <div className="my-4" data-testid={`${testId}.Skew`}>
      <Banner tone="steel">
        <div className="min-w-0">
          <p className="m-0 text-sm font-semibold text-ink">A newer version is available</p>
          <p className={mode === 'manual' ? 'mt-1 mb-3' : 'mt-1 mb-0'}>
            {mode === 'recovering'
              ? 'Reloading to pick up the latest version…'
              : 'This tab has been open since an earlier release. Reload to continue.'}
          </p>
          {mode === 'manual' && (
            <Button size="sm" onClick={reloadOntoCurrentBuild} data-testid={`${testId}.Skew.Reload`}>
              Reload
            </Button>
          )}
        </div>
      </Banner>
    </div>
  );
}

function report(source: ErrorSource, context?: Record<string, string>) {
  return (error: unknown) => reportClientError(error, source, context ?? null);
}

// --------------------------------------------------------------------- root ----

function RootFallback({ error, resetErrorBoundary }: FallbackProps) {
  const skew = useSkewRecovery(error);

  return (
    <div className="grid min-h-screen place-items-center bg-ground p-6">
      {/*
        ~~Deliberately not themed: this boundary catches failures in the providers themselves, so
        the theme may be exactly what is broken. Plain system styling always renders.~~
        → superseded in T16. The caution was about MUI's `ThemeProvider`, a React provider that
        this boundary sits *above* — if it threw, anything styled through it rendered unstyled.
        Tailwind's utilities are a static stylesheet the document has already loaded by the time
        any of this runs, so they cannot be broken by a provider throwing. The property the
        original comment wanted is now structural rather than a restraint to remember.
      */}
      <div className="max-w-[52ch]" data-testid="Root.Error">
        {skew !== 'not-skew' ? (
          <SkewFallback mode={skew} testId="Root" />
        ) : (
          <>
            <h1 className="m-0 mb-2 text-[19px] font-semibold tracking-[-.018em] text-ink">
              TCMS ran into a problem
            </h1>
            <p className="mt-0 mb-4 text-sm text-ink-2">
              Something went wrong loading the application. The problem has been reported. Trying
              again is usually enough; if it keeps happening, reload the page.
            </p>
            <div className="flex gap-2">
              <Button variant="primary" onClick={resetErrorBoundary} data-testid="Root.Error.Retry">
                Try again
              </Button>
              <Button onClick={() => window.location.reload()} data-testid="Root.Error.Reload">
                Reload
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * Wraps everything, including the providers. Nothing above it can catch, so this one has to be
 * self-sufficient — it must not depend on the theme, the query client, or the session.
 */
export function RootErrorBoundary({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary FallbackComponent={RootFallback} onError={report('root-boundary')}>
      {children}
    </ErrorBoundary>
  );
}

// -------------------------------------------------------------------- route ----

function RouteFallback({ error, resetErrorBoundary }: FallbackProps) {
  const skew = useSkewRecovery(error);

  if (skew !== 'not-skew') return <SkewFallback mode={skew} testId="Route" />;

  return (
    <div className="my-4" data-testid="Route.Error">
      <Banner tone="bad">
        <div className="min-w-0">
          <p className="m-0 text-sm font-semibold text-ink">This page didn't load</p>
          <p className="mt-1 mb-3">
            Something went wrong rendering this page, and the problem has been reported. The rest
            of the application still works — you can try again, or use the navigation to go
            elsewhere.
          </p>
          <Button
            size="sm"
            variant="danger"
            onClick={resetErrorBoundary}
            data-testid="Route.Error.Retry"
          >
            Try again
          </Button>
        </div>
      </Banner>
    </div>
  );
}

/**
 * Per-route boundary, mounted *inside* the shell. That placement is the point: the rail, the
 * project switcher, and the top bar are chrome the user can still act on, so a broken page must
 * degrade to this alert in the content area rather than replacing the whole window.
 *
 * `resetKeys` on the pathname means navigating away from a broken route clears the boundary by
 * itself — otherwise the fallback would persist over the next page the user opened, and the app
 * would look permanently broken after one bad render.
 */
export function RouteErrorBoundary({ children }: { children: ReactNode }) {
  const location = useLocation();
  return (
    <ErrorBoundary
      FallbackComponent={RouteFallback}
      resetKeys={[location.pathname, location.search]}
      onError={report('route-boundary', { path: location.pathname })}
    >
      {children}
    </ErrorBoundary>
  );
}

// ------------------------------------------------------------------- region ----

/**
 * For an independently-failing region — a dashboard tile, a chart, a panel. Scoped so the
 * surrounding page keeps rendering, which is the same rule `DataRegion` applies to failed
 * *fetches*, extended to render-time throws.
 */
export function RegionErrorBoundary({
  testId,
  label,
  children,
}: {
  testId: string;
  /** What failed, in the user's words — "the coverage chart", not "CoverageChart". */
  label: string;
  children: ReactNode;
}) {
  return (
    <ErrorBoundary
      onError={report('region-boundary', { region: testId })}
      fallbackRender={({ resetErrorBoundary }) => (
        <div className="my-2" data-testid={`${testId}.RenderError`}>
          <Banner tone="bad">
            <div className="min-w-0">
              <p className="m-0">We couldn't display {label}.</p>
              <Button
                size="sm"
                variant="danger"
                onClick={resetErrorBoundary}
                data-testid={`${testId}.RenderError.Retry`}
                className="mt-1"
              >
                Try again
              </Button>
            </div>
          </Banner>
        </div>
      )}
    >
      {children}
    </ErrorBoundary>
  );
}
