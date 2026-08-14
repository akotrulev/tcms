/**
 * A test whose render tripped an error boundary — or whose read or write failed — fails, unless it
 * says it meant to.
 *
 * The file is still called `renderFailures` after §202 added the second half, because renaming it
 * would rewrite the import line of 35 files to say nothing new. What it is *about* is the failures a
 * test did not mean to have.
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
 * ## The second half: a region whose *fetch* failed (§202)
 *
 * `DataRegion` renders `<testId>.Error` for that, and it is the same defect one door over — a
 * secondary panel reading an endpoint the double has no arm for shows an alert while the test's
 * assertions, all aimed at the primary panel, pass. §201 shipped without it because the report never
 * arrived: the app reports a failed read through this same sink (`app/queryClient.ts`, source
 * `query`), but `createTestQueryClient` installed no caches at all, so under test **nothing was ever
 * reported**. Breaking one arm of `ApiTokensPage.test.tsx`'s double so its own
 * `throw new Error('unmocked read: …')` fired produced no report — and thirteen test files wrote that
 * throw to be loud.
 *
 * `src/test/queryClient.ts` now reports both caches into the sink, which is a smaller change than
 * §201 thought it was: what the notification provider is needed for is *raising a notification*, not
 * *reporting*, and those had been one sentence in that file's header.
 *
 * ## Why the deliberate ones are three hatches and not one
 *
 * Turning the two entries on reddened **61 tests, every one of them deliberate**: 44 driving a
 * refused write, 16 a failed read, and one of this module's own that had pinned the old
 * classification. So unlike the render half — whose population was zero — this one arrives with a
 * list to opt out, and the kinds are kept apart because those opt-outs are **not interchangeable**.
 * A test that arranges a rejected `POST` has said nothing whatever about whether a panel beside the
 * dialog was allowed to fail its read, which is precisely where §198's defect lived; one hatch
 * covering all three would let the largest group of exceptions in the suite disarm the check in the
 * place it was written for.
 *
 * ## What it buys, measured on §201's own instance
 *
 * §201 measured the door being shut by deleting the `/users` arm of `ApiTokensPage.test.tsx`'s
 * double, and reported that the file's own `throw new Error('unmocked read: …')` produced no report
 * at all. Repeating that with the two entries `null` — the state this file was in — reddens **one**
 * test of nine; with them on it reddens **nine of nine**, naming `the read ["users"] failed`. Eight
 * tests were passing beside a panel that could not load its owner names. (§201's prose said seven
 * of them failed on their assertions, which was a reading rather than a measurement: it is one.)
 */
import { afterEach, beforeEach, expect } from 'vitest'
import { resetReportedErrors, type ClientErrorReport, type ErrorSource } from '../lib/reportClientError'

/** What a report means for this check, and therefore which hatch can excuse it. */
export type FailureKind = 'render' | 'query' | 'mutation'

/**
 * Which of the sink's eight sources this check reads, and as what.
 *
 * **Written as an exhaustive `Record` rather than a `Set` of the five, on purpose.** A set names what
 * it covers and says nothing about what it does not, so a fourth boundary tier added to `ErrorSource`
 * would be absent from it and the check would go quiet on that tier while staying green — a
 * hand-maintained list, which is the failure this repo has paid for in §196's two tsconfig lists,
 * §162's exemptions and §53's undeclared operations. Annotated `Record<ErrorSource, …>`, a new member
 * is a **compile error here** and whoever adds it has to answer the question.
 *
 * The three `null` entries are answers rather than omissions: `window-error`, `unhandled-rejection`
 * and `recoverable-render` are wired in `main.tsx` and `globalErrorHandlers.ts`, neither of which a
 * component test mounts, so a report from one of them would be this module's own doing.
 *
 * `query` and `mutation` were `false` here until §202, correctly for §201's reason and wrongly for
 * the one written beside it: the entry said *"a state hundreds of tests drive deliberately"*, and the
 * measurement is 61 — which is a repair list, not a reason to look away.
 */
const KIND_OF: Record<ErrorSource, FailureKind | null> = {
  'root-boundary': 'render',
  'route-boundary': 'render',
  'region-boundary': 'render',
  query: 'query',
  mutation: 'mutation',
  'recoverable-render': null,
  'window-error': null,
  'unhandled-rejection': null,
}

const suspended: Record<FailureKind, boolean> = { render: false, query: false, mutation: false }

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
  suspended.render = true
}

/**
 * Suspends the failed-**read** half for the remainder of the current test.
 *
 * For a test whose subject *is* a read that failed — that the region shows its error branch, that a
 * 404 reads as "no such team" rather than as a fault, that a drifted body is a failed read rather
 * than an empty one. Name the reason at the call site, as above.
 */
export function drivingAFailedRead(): void {
  suspended.query = true
}

/**
 * Suspends the failed-**write** half for the remainder of the current test.
 *
 * For a test whose subject *is* a write that was refused — that the dialog keeps the user's input,
 * that a 409 says what to do next, that a gate's refusal offers the override.
 *
 * Separate from `drivingAFailedRead` for the header's reason, and it is the whole design rather than
 * a nicety: this is the largest group of exceptions in the suite, and a test that arranges a rejected
 * `POST` has said nothing about whether the panels beside the dialog were allowed to fail their
 * reads.
 */
export function drivingAFailedWrite(): void {
  suspended.mutation = true
}

function collected(): ClientErrorReport[] {
  return (window as unknown as { __tcmsErrors?: ClientErrorReport[] }).__tcmsErrors ?? []
}

/**
 * Every boundary trip and failed request since the last drain, cleared.
 *
 * `afterEach` below drains to decide whether to fail; `renderFailures.test.ts` drains to assert
 * *what* was collected, which is the only way a green suite can observe an instrument whose working
 * state is silence.
 */
export function drainCollectedFailures(): ClientErrorReport[] {
  const trips = collected().filter((report) => KIND_OF[report.source] !== null)
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

/**
 * A failed request names its key, because the query key is the only thing on hand that says *which*
 * panel. A mutation usually has none — `useMutation` takes `mutationKey` optionally and almost
 * nothing here passes one — so the write half falls back to naming the verb and leans on the
 * error message, which for a refused write is the server's own sentence.
 */
function describeFailedRequest(report: ClientErrorReport): string {
  const what = report.context?.queryKey
    ? `the read \`${report.context.queryKey}\``
    : report.context?.mutationKey
      ? `the write \`${report.context.mutationKey}\``
      : report.source === 'query'
        ? 'a read'
        : 'a write'
  return `  ${what} failed\n    ${report.message}`
}

const RENDER_ADVICE =
  'An error boundary exists so that one broken region does not take the page down, which means '
  + 'a tripped one is invisible to every assertion not aimed at it — 24 tests passed for years '
  + 'beside a panel showing "We couldn\'t display this content" (§198). The assertions in this '
  + 'test are probably all fine; something else on the screen is not.\n\n'
  + 'The usual cause is a body the region could not read: a double answering a sub-resource with '
  + 'the parent resource, a list endpoint answered with an object, a required field a fixture '
  + 'left out. `contractDoubles` reports those directly — check whether it did. If it did not, '
  + 'the throw is in the component.\n\n'
  + 'If the tripped boundary IS the subject of this test, call `renderingIntoAnErrorBoundary()` '
  + 'and say why.'

const REQUEST_ADVICE =
  'A failed request is invisible in the same way and for the same reason: `DataRegion` renders its '
  + 'error branch, the rest of the page is unaffected, and whether anybody notices depends on '
  + 'whether an assertion happens to be pointed at that region. Thirteen test files end their '
  + "double with `throw new Error('unmocked read: …')` to be loud about exactly this, and until "
  + '§202 the query layer swallowed every one of them.\n\n'
  + 'The usual cause is a double with no arm for an endpoint some panel on the page reads. Add the '
  + 'arm — `src/test/fixtures.ts` has a factory for most shapes — and anchor it so a sub-resource '
  + 'is not caught by a prefix.\n\n'
  + 'If the failed request IS the subject of this test, call `drivingAFailedRead()` or '
  + '`drivingAFailedWrite()` — whichever half it is — and say why.'

function suspend(kind: FailureKind): boolean {
  const meant = suspended[kind]
  suspended[kind] = false
  return meant
}

/**
 * The reports a test has not excused, given which hatches it called.
 *
 * Exported for its own test rather than inlined into `afterEach`, because the claim it carries — a
 * hatch excuses **its own kind and nothing else** — is the whole reason there are three of them, and
 * a check cannot observe its own `afterEach` deciding not to fail.
 */
export function unexcused(
  reports: ClientErrorReport[],
  meant: Record<FailureKind, boolean>,
): ClientErrorReport[] {
  return reports.filter((report) => {
    const kind = KIND_OF[report.source]
    return kind !== null && !meant[kind]
  })
}

afterEach(() => {
  const reports = drainCollectedFailures()
  // Read and cleared for all three, always: a hatch called by a test that then did not need it must
  // not survive into the next test, which is the same isolation `resetReportedErrors` buys one line up.
  const meant: Record<FailureKind, boolean> = {
    render: suspend('render'),
    query: suspend('query'),
    mutation: suspend('mutation'),
  }

  const kept = unexcused(reports, meant)
  if (kept.length === 0) return

  const trips = kept.filter((report) => KIND_OF[report.source] === 'render')
  const failed = kept.filter((report) => KIND_OF[report.source] !== 'render')

  const parts: string[] = []
  if (trips.length > 0) {
    parts.push(
      `This test rendered ${trips.length === 1 ? 'a region' : `${trips.length} regions`} that had `
        + `thrown:\n\n${trips.map(describeTrip).join('\n')}\n\n${RENDER_ADVICE}`,
    )
  }
  if (failed.length > 0) {
    parts.push(
      `This test made ${failed.length === 1 ? 'a request' : `${failed.length} requests`} that `
        + `failed:\n\n${failed.map(describeFailedRequest).join('\n')}\n\n${REQUEST_ADVICE}`,
    )
  }

  expect.fail(parts.join('\n\n'))
})
