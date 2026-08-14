/**
 * A test whose render tripped an error boundary fails, unless it says it meant to.
 *
 * `contractDoubles.ts` checks what crosses the wire; this checks what the tree did with it. They are
 * the two halves of one gap and §198 found them one after the other: a fixture answered
 * `/cases/{id}/versions` with a case, `rows.map` threw, `DataRegion`'s own `RegionErrorBoundary`
 * caught it, and **24 tests across two files passed for as long as those files existed with the
 * version panel beside them showing "We couldn't display this content."** Every assertion in them was
 * about something else on the page. Nothing was wrong with any of those assertions; nothing was
 * looking at the panel.
 *
 * That is not a fixture problem with an instrument problem behind it. It is the general shape:
 *
 *   - the boundary tiers exist **so that** one broken region does not take the page down, so a
 *     tripped boundary is by design invisible to everything except an assertion aimed at it;
 *   - a test asserts on the subtree it is about, and there is no reading of a green run that
 *     distinguishes "the other six regions rendered" from "the other six regions caught a throw".
 *
 * So the check cannot be "assert the region rendered" written 1330 times. It has to be a property of
 * the whole render, collected where the app already collects it.
 *
 * ## Why the sink and not the DOM
 *
 * The obvious observation point is the fallback's own `data-testid` — `<testId>.RenderError`,
 * `Route.Error`, `Root.Error` — queried after the test. It is worse in two ways. A boundary that
 * tripped and was then reset by a `resetKeys` change leaves no trace in the DOM, and RTL's `cleanup`
 * runs in an `afterEach` of its own, so a DOM read is racing a hook order that is not this module's
 * to choose.
 *
 * `reportClientError` has neither problem. **Every tier reports through it** — that is the sink's
 * whole stated purpose — it is called at the moment of the throw rather than read back from what
 * survived, and it carries the tier and the region's `testId` in `context`, so the failure can name
 * the panel rather than the page.
 *
 * ## Two seams that had no caller
 *
 * Nothing in the suite called either of the two things this module is built on, and both were written
 * for it. `send()` keeps every report on `window.__tcmsErrors` under a comment saying why — *"a
 * console object that has to be expanded by hand is no use to an automated check"* — and
 * `resetReportedErrors` is labelled *"Test-only seam — a fresh test must not inherit another's
 * fingerprints"*, which is exactly the isolation an `afterEach` check needs and exactly what no
 * `beforeEach` was doing. A reader that arrives after the seam is this repo's recurring shape
 * (§116, §117, §121, §132, §172); here the seam was right and the reader is two years late.
 *
 * **`__tcmsErrors` is populated only on the `import.meta.env.DEV` branch of `send()`**, which is the
 * one way this module could go silently dark. `renderFailures.test.ts` drives a real throw through a
 * real boundary rather than asserting against a hand-made report, so a mode change that turned the
 * array off would redden there instead of nowhere.
 *
 * ## What this check does NOT see, measured rather than assumed
 *
 * **A region whose *fetch* failed.** `DataRegion` renders `<testId>.Error` for that, and it is the
 * same defect one door over: a secondary panel reading an endpoint the double has no arm for shows an
 * alert while the test's assertions, all aimed at the primary panel, pass. The app reports those
 * through this same sink (`app/queryClient.ts`, source `query`), so the shape looks reachable — and
 * it is not, because **`createTestQueryClient` deliberately installs no error handlers**, for a
 * reason that is right on its own terms (the app's handlers need the notification provider in every
 * test tree). So no `query` report is ever collected under test.
 *
 * Measured, not reasoned: breaking one arm of `ApiTokensPage.test.tsx`'s double so its own
 * `throw new Error('unmocked read: …')` fires produced **no report at all**. Thirteen test files wrote
 * that throw to be loud, and the query layer swallows every one of them. Recorded as the next rung
 * rather than taken here — closing it means deciding what a *deliberate* failed query looks like, and
 * hundreds of tests drive one.
 */
import { afterEach, beforeEach, expect } from 'vitest'
import { resetReportedErrors, type ClientErrorReport, type ErrorSource } from '../lib/reportClientError'

/**
 * Which of the sink's eight sources mean *a render threw*.
 *
 * **Written as an exhaustive `Record` rather than a `Set` of the three, on purpose.** A set names
 * what it covers and says nothing about what it does not, so a fourth boundary tier added to
 * `ErrorSource` would be absent from it and the check would go quiet on that tier while staying
 * green — a hand-maintained list, which is the failure this repo has paid for in §196's two tsconfig
 * lists, §162's exemptions and §53's undeclared operations. Annotated `Record<ErrorSource, boolean>`,
 * a new member is a **compile error here** and whoever adds it has to answer the question.
 *
 * The five `false` entries are answers rather than omissions. `query` and `mutation` are a fetch that
 * failed, which is a state this app renders on purpose and hundreds of tests drive deliberately —
 * they are `false` by classification rather than by necessity, since the test query client reports
 * neither today (see the header), and they must stay `false` for the day it does. `window-error`,
 * `unhandled-rejection` and `recoverable-render` are wired in `main.tsx` and `globalErrorHandlers.ts`,
 * neither of which a component test mounts.
 */
const IS_A_RENDER_THROW: Record<ErrorSource, boolean> = {
  'root-boundary': true,
  'route-boundary': true,
  'region-boundary': true,
  'recoverable-render': false,
  'window-error': false,
  'unhandled-rejection': false,
  query: false,
  mutation: false,
}

let suspended = false

/**
 * Suspends the check for the remainder of the current test.
 *
 * For a test whose subject *is* a tripped boundary — that the region contains a throw, that the page
 * survives one, that the fallback offers a retry. Name the reason at the call site.
 *
 * A function rather than a list, for `servingADriftedBody()`'s reason: the exception is written where
 * it is taken, and it cannot widen to a file, a directory or a pattern without somebody typing it
 * again.
 */
export function renderingIntoAnErrorBoundary(): void {
  suspended = true
}

function collected(): ClientErrorReport[] {
  return (window as unknown as { __tcmsErrors?: ClientErrorReport[] }).__tcmsErrors ?? []
}

/**
 * Every boundary trip since the last drain, cleared.
 *
 * `afterEach` below drains to decide whether to fail; `renderFailures.test.ts` drains to assert
 * *what* was collected, which is the only way a green suite can observe an instrument whose working
 * state is silence.
 */
export function drainBoundaryTrips(): ClientErrorReport[] {
  const trips = collected().filter((report) => IS_A_RENDER_THROW[report.source])
  clearCollected()
  return trips
}

function clearCollected(): void {
  ;(window as unknown as { __tcmsErrors?: ClientErrorReport[] }).__tcmsErrors = []
}

/**
 * The sink dedupes on `source:name:message` and never forgets, so the second test in a file to hit
 * the same broken panel would report nothing at all — the check would fail whichever test happened to
 * run first and call the other twenty-three clean.
 */
beforeEach(() => {
  resetReportedErrors()
  clearCollected()
})

function describeTrip(report: ClientErrorReport): string {
  const region = report.context?.region
  const where = region
    ? `the region \`${region}\``
    : report.source === 'route-boundary'
      ? `the route \`${report.context?.path ?? report.path}\``
      : 'the whole application'
  return `  ${where} caught a throw and rendered its failure state\n    ${report.message}`
}

afterEach(() => {
  const trips = drainBoundaryTrips()
  const meantIt = suspended
  suspended = false

  if (meantIt || trips.length === 0) return

  expect.fail(
    `This test rendered ${trips.length === 1 ? 'a region' : `${trips.length} regions`} that had `
      + `thrown:\n\n${trips.map(describeTrip).join('\n')}\n\n`
      + 'An error boundary exists so that one broken region does not take the page down, which means '
      + 'a tripped one is invisible to every assertion not aimed at it — 24 tests passed for years '
      + 'beside a panel showing "We couldn\'t display this content" (§198). The assertions in this '
      + 'test are probably all fine; something else on the screen is not.\n\n'
      + 'The usual cause is a body the region could not read: a double answering a sub-resource with '
      + 'the parent resource, a list endpoint answered with an object, a required field a fixture '
      + 'left out. `contractDoubles` reports those directly — check whether it did. If it did not, '
      + 'the throw is in the component.\n\n'
      + 'If the tripped boundary IS the subject of this test, call `renderingIntoAnErrorBoundary()` '
      + 'and say why.',
  )
})
