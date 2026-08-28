import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { FeatureImportRequest } from '../../api/types'
import { anImportPlan, anImportRow, aProject, aSuite } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { TestProviders } from '../../test/TestProviders'
import { capsRefusals, countScenarios, type PickedFile } from './featureFiles'
import { FeatureImportPage } from './FeatureImportPage'

/**
 * The `.feature` import screen (§255) — the SPA half of area D.
 *
 * What each group is about, and why none of it can be settled by reading the file:
 *
 *   1. **The exchange.** One endpoint, two modes, and the body is `{files, suiteId?, onExisting,
 *      mode}` with **no `Idempotency-Key`** — deliberately, because determinism replaces it. The
 *      contract seam (`src/test/contractDoubles.ts`) compares both halves of every call here, so
 *      these arms are asserting the *shape of the intent*: which mode was asked for, and that the
 *      file the caller picked is the file the request carries, path included.
 *   2. **Every action renders, with its `reason` verbatim.** The ten sentences are contract —
 *      listed on `ImportRow.reason` — and clients may match on them. An arm that asserted "a
 *      refusal is shown" would pass while the screen re-worded `"needs library:manage for the
 *      Background"` into something with no permission key in it.
 *   3. **`REFUSE` never blocks the rest, and never becomes a 403 page.** A `case:read`-only caller
 *      gets 200 with every row refused; a caller with one importable scenario beside three
 *      refusals applies the one. Both fall out of Apply's enablement being read off `totals`, and
 *      an arm each is what stops that becoming a permission check on the button.
 *   4. **The caps refuse before the request.** The server answers `errors.files` for each, but a
 *      drop zone that uploads 9 MB and then reports a field error is a worse experience than one
 *      that refuses first — so the assertion that matters is `api.post` was never called.
 *   5. **The 409 is described by `describeError`.** `duplicate-automation-key` is the only failure
 *      that rolls anything back, and `describeError` had never seen it before this slice: the 409
 *      branch reads the problem's TITLE to tell a lost update from a refusal, and these arrive
 *      titled `"That key was taken"`, so the server's own sentence is what must reach the screen
 *      rather than the reload-and-reapply advice.
 *
 * Fixture ids in this file are the **`fi-…`** namespace, fresh for T7 — the backend prefixes
 * (`2b…`/`mgs-…`, `e1…`/`gher-…`, `1d…`/`fimp-…`) are spent and global.
 */

const PROJECT = 'p1'

const ORDERS = `Feature: Orders

  Background:
    Given I am signed in as a customer

  Scenario: Cancel an order
    Given I have placed an order
    When I cancel it
    Then the order is cancelled
`

function renderPage(route = `/projects/${PROJECT}/cases/import`) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <Routes>
        <Route path="/projects/:projectId/cases/import" element={<FeatureImportPage />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * The two reads this screen makes: the project (for the heading) and the suites (for the picker).
 *
 * Path-keyed and anchored, so `/projects/p1/suites` is not caught by `/projects/p1`'s arm — the
 * prefix trap `CLAUDE.md` names, which here would answer the suite list with a project object and
 * fail the contract seam three assertions away from the cause.
 */
function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (url: string) => {
    if (/^\/projects\/[^/]+$/.test(url)) return aProject({ id: PROJECT, name: 'Acme Web' })
    if (/^\/projects\/[^/]+\/suites$/.test(url)) {
      return [aSuite({ id: 'fi-suite-1', key: 'TS-1', name: 'Orders' })]
    }
    throw new Error(`unmocked read: ${url}`)
  }) as never)
}

/**
 * Picks a file through the hidden `<input type="file">`.
 *
 * `fireEvent.change` rather than `userEvent.upload`: the input is `hidden` (an `<input type=file>`
 * is the only thing that can open a file dialog, so the *button* beside it is the visible control),
 * and `userEvent` refuses to interact with an element it considers unpointable. `dom-testing-library`
 * special-cases `target.files`, which is why this assignment reaches `event.target.files`.
 */
async function pick(files: File[]) {
  fireEvent.change(screen.getByTestId('FeatureImport.FileInput'), { target: { files } })
  await waitFor(() => expect(screen.getAllByTestId('FeatureImport.File')).toHaveLength(files.length))
}

function featureFile(name: string, content = ORDERS): File {
  return new File([content], name, { type: 'text/plain' })
}

/** A file that *reports* a size without holding one — see the cap arm for why it is faked. */
function ofSize(file: File, bytes: number): File {
  Object.defineProperty(file, 'size', { value: bytes, configurable: true })
  return file
}

/** The body of the most recent `api.post`, which is the request the screen actually composed. */
function sentBody(post: { mock: { calls: unknown[][] } }): FeatureImportRequest {
  return post.mock.calls[post.mock.calls.length - 1][1] as FeatureImportRequest
}

beforeEach(() => {
  mockReads()
})

describe('FeatureImportPage — the exchange', () => {
  it('previews the picked files at the declared path, with the path the caller picked', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(anImportPlan() as never)
    renderPage()

    const file = featureFile('orders.feature')
    // A folder pick carries the relative path; a plain pick does not. Defined here because jsdom's
    // `File` has no way to set it, and the provenance path is the whole reason this endpoint takes
    // text rather than a multipart upload.
    Object.defineProperty(file, 'webkitRelativePath', { value: 'features/orders.feature' })
    await pick([file])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/feature-imports`)
    const body = sentBody(post)
    expect(body.mode).toBe('PREVIEW')
    expect(body.onExisting).toBe('SKIP')
    expect(body.files).toEqual([{ path: 'features/orders.feature', content: ORDERS }])
  })

  it('sends no Idempotency-Key, because determinism replaces it', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(anImportPlan() as never)
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    // The third argument is `RequestOptions`. Every other create in `api/queries/cases.ts` puts an
    // `idempotencyKey` there; this endpoint declares no such parameter and the server would not
    // honour one, so a header here would look like protection and be inert.
    const options = post.mock.calls[0][2] as { idempotencyKey?: string } | undefined
    expect(options?.idempotencyKey).toBeUndefined()
  })

  it('applies with the same files and fills every row key in', async () => {
    const previewed = anImportPlan({
      scenarios: [anImportRow({ action: 'CREATE' })],
      totals: { create: 1, update: 0, skip: 0, refuse: 0 },
    })
    const applied = anImportPlan({
      mode: 'APPLY',
      applied: true,
      scenarios: [anImportRow({ action: 'CREATE', caseKey: 'TC-12' })],
      totals: { create: 1, update: 0, skip: 0, refuse: 0 },
    })
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValueOnce(previewed as never)
      .mockResolvedValueOnce(applied as never)
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))
    await waitFor(() => expect(screen.getByTestId('FeatureImport.Apply')).toBeEnabled())
    await userEvent.click(screen.getByTestId('FeatureImport.Apply'))

    await waitFor(() => expect(screen.getByTestId('FeatureImport.Applied')).toBeInTheDocument())
    expect(sentBody(post).mode).toBe('APPLY')
    // The same files as the preview: an apply that re-read the picker would be committing a
    // different request from the one the caller just read.
    expect(sentBody(post).files).toEqual([{ path: 'orders.feature', content: ORDERS }])
    const key = screen.getByTestId('FeatureImport.Row.CaseKey')
    expect(key).toHaveTextContent('TC-12')
    // `/search?q=TC-12`, because the plan carries a KEY and the case route takes an id — resolving
    // a key a person read is what the search endpoint is for (plan §6t).
    expect(key).toHaveAttribute('href', '/search?q=TC-12')
  })

  it('discards the plan when an option that changes the request changes', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(
      anImportPlan({
        scenarios: [anImportRow()],
        totals: { create: 1, update: 0, skip: 0, refuse: 0 },
      }) as never,
    )
    renderPage()
    await pick([featureFile('orders.feature')])
    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))
    await waitFor(() => expect(screen.getByTestId('FeatureImport.Row')).toBeInTheDocument())

    await userEvent.click(screen.getByTestId('FeatureImport.OnExisting.UPDATE'))

    // A plan describes ONE request. Left on screen it would invite an Apply that sends the other.
    expect(screen.queryByTestId('FeatureImport.Row')).not.toBeInTheDocument()
    expect(screen.getByTestId('FeatureImport.Apply')).toBeDisabled()
  })
})

describe('FeatureImportPage — the plan', () => {
  const everyAction = anImportPlan({
    scenarios: [
      anImportRow({ action: 'CREATE', scenario: 'Cancel an order', preconditions: ['Given I am signed in as a customer'] }),
      anImportRow({ action: 'UPDATE', scenario: 'Refund an order', caseKey: 'TC-8', line: 20 }),
      anImportRow({ action: 'SKIP', scenario: 'Pay by card', caseKey: 'TC-9', reason: 'exists (SKIP)', line: 6 }),
      anImportRow({
        action: 'REFUSE',
        scenario: 'A large order ships free',
        automationKey: null,
        reason: 'Scenario Outline is not supported yet',
        line: 15,
      }),
      anImportRow({
        action: 'REFUSE',
        path: 'features/broken.feature',
        line: null,
        feature: null,
        scenario: null,
        automationKey: null,
        reason: 'Line 3, column 5: expected: #TagLine, #ScenarioLine',
      }),
    ],
    suites: [
      { name: 'Orders', action: 'CREATE', suiteId: null, reason: null },
      { name: 'Payments', action: 'REUSE', suiteId: 'fi-suite-1', reason: null },
    ],
    conditions: [
      {
        importedKey: 'bg:0123456789abcdef',
        title: 'Given I am signed in as a customer',
        action: 'CREATE',
        conditionId: null,
        reason: null,
      },
      {
        importedKey: 'bg:fedcba9876543210',
        title: 'Given the catalogue has been loaded',
        action: 'UNCHANGED',
        conditionId: 'fi-cond-2',
        reason: null,
      },
    ],
    orphans: [{ caseKey: 'TC-40', automationKey: 'Orders#Split an order' }],
    totals: { create: 1, update: 1, skip: 1, refuse: 2 },
  })

  async function previewing(plan = everyAction) {
    vi.spyOn(api, 'post').mockResolvedValue(plan as never)
    renderPage()
    await pick([featureFile('orders.feature')])
    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))
    await waitFor(() => expect(screen.getAllByTestId('FeatureImport.Row').length).toBeGreaterThan(0))
  }

  it('renders one row per scenario, each labelled with its action', async () => {
    await previewing()

    const rows = screen.getAllByTestId('FeatureImport.Row')
    expect(rows).toHaveLength(5)
    expect(rows.map((row) => within(row).getByTestId('FeatureImport.Row.Action').textContent)).toEqual([
      'Create',
      'Update',
      'Skip',
      'Refused',
      'Refused',
    ])
  })

  it('renders every reason exactly as the server sent it', async () => {
    await previewing()

    // VERBATIM, because the sentences are contract: `openapi.yaml` lists them on `ImportRow.reason`
    // and clients may match on them. Re-wording the permission one through `describeError` would
    // take the key out of the one place a caller can read it.
    const reasons = screen
      .getAllByTestId('FeatureImport.Row.Reason')
      .map((cell) => cell.textContent)
    expect(reasons).toContain('exists (SKIP)')
    expect(reasons).toContain('Scenario Outline is not supported yet')
    expect(reasons).toContain('Line 3, column 5: expected: #TagLine, #ScenarioLine')
  })

  it('renders a whole-file refusal without a scenario or a line', async () => {
    await previewing()

    const wholeFile = screen
      .getAllByTestId('FeatureImport.Row')
      .find((row) => within(row).getByTestId('FeatureImport.Row.Scenario').textContent === 'The whole file')
    expect(wholeFile).toBeDefined()
    // No `:line` on a refusal that has no scenario to point at — `line` is null and the cell must
    // not render `features/broken.feature:null`.
    expect(wholeFile?.textContent).toContain('features/broken.feature')
    expect(wholeFile?.textContent).not.toContain('features/broken.feature:')
  })

  it('reads the totals off the server rather than re-tallying the rows', async () => {
    await previewing()

    expect(screen.getByTestId('FeatureImport.Totals.Create')).toHaveTextContent('1 create')
    expect(screen.getByTestId('FeatureImport.Totals.Update')).toHaveTextContent('1 update')
    expect(screen.getByTestId('FeatureImport.Totals.Skip')).toHaveTextContent('1 skip')
    expect(screen.getByTestId('FeatureImport.Totals.Refuse')).toHaveTextContent('2 refused')
  })

  it('renders the suites, the Background conditions and the orphans', async () => {
    await previewing()

    expect(screen.getAllByTestId('FeatureImport.SuiteRow')).toHaveLength(2)
    expect(screen.getAllByTestId('FeatureImport.SuiteRow.Action').map((chip) => chip.textContent)).toEqual([
      'Create',
      'Use existing',
    ])
    // One entry per DISTINCT Background step (amendment AM1), not one per block — and `UNCHANGED`
    // is the common answer on a re-import, which is the point of hashing the step text.
    expect(screen.getAllByTestId('FeatureImport.ConditionRow')).toHaveLength(2)
    expect(
      screen.getAllByTestId('FeatureImport.ConditionRow.Action').map((chip) => chip.textContent),
    ).toEqual(['Create', 'Already there'])
    expect(screen.getByTestId('FeatureImport.Orphan')).toHaveTextContent('TC-40')
  })

  it("lists a row's preconditions from the row, in the order they arrived", async () => {
    await previewing(
      anImportPlan({
        scenarios: [
          anImportRow({
            preconditions: [
              'Given I am signed in as a customer',
              'And the catalogue has been loaded',
              'And my basket is empty',
            ],
          }),
        ],
        // A different Background step altogether, so an implementation that read `conditions[]`
        // instead of the row would render this one and fail.
        conditions: [
          { importedKey: 'bg:aaaa', title: 'Given my address is in the EU', action: 'CREATE', conditionId: null, reason: null },
        ],
        totals: { create: 1, update: 0, skip: 0, refuse: 0 },
      }),
    )

    expect(
      screen.getAllByTestId('FeatureImport.Row.Precondition').map((chip) => chip.textContent),
    ).toEqual(['Given I am signed in as a customer', 'And the catalogue has been loaded'])

    await userEvent.click(screen.getByTestId('FeatureImport.Row.MorePreconditions'))

    expect(
      screen.getAllByTestId('FeatureImport.Row.Precondition').map((chip) => chip.textContent),
    ).toEqual([
      'Given I am signed in as a customer',
      'And the catalogue has been loaded',
      'And my basket is empty',
    ])
  })
})

describe('FeatureImportPage — REFUSE is a row, not a status', () => {
  it('keeps Apply enabled when refused rows sit beside one that would be created', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(
      anImportPlan({
        scenarios: [
          anImportRow({ action: 'CREATE' }),
          anImportRow({ action: 'REFUSE', reason: 'Scenario Outline is not supported yet' }),
          anImportRow({ action: 'REFUSE', reason: 'needs library:manage for the Background' }),
        ],
        totals: { create: 1, update: 0, skip: 0, refuse: 2 },
      }) as never,
    )
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    await waitFor(() => expect(screen.getByTestId('FeatureImport.Apply')).toBeEnabled())
  })

  it('shows a read-only caller the whole plan, refused row by row, with no error page', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(
      anImportPlan({
        scenarios: [
          anImportRow({ action: 'REFUSE', reason: 'needs case:create' }),
          anImportRow({ action: 'REFUSE', scenario: 'Refund an order', reason: 'needs case:create' }),
        ],
        totals: { create: 0, update: 0, skip: 0, refuse: 2 },
      }) as never,
    )
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    await waitFor(() => expect(screen.getAllByTestId('FeatureImport.Row')).toHaveLength(2))
    // 200 with every row refused — never a 403 page, and the row names the key the caller needs
    // FIRST: the row's own `case:create`, not the Background's `library:manage`.
    expect(screen.queryByTestId('FeatureImport.Plan.Error')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('FeatureImport.Row.Reason')[0]).toHaveTextContent('needs case:create')
    expect(screen.getByTestId('FeatureImport.Apply')).toBeDisabled()
    expect(screen.getByTestId('FeatureImport.NothingToApply')).toBeInTheDocument()
  })
})

describe('FeatureImportPage — the caps refuse before the request', () => {
  /*
    The four bounds as a pure function, because the UI arm below can only afford to drive one of
    them: proving the 8 MiB total through the DOM means holding 8 MiB of string in jsdom, and
    proving the 200-file bound means constructing 201 `File`s and reading each. The function is what
    the screen calls, so these are the real numbers and not a restatement of them.
  */
  const picked = (over: Partial<PickedFile> = {}): PickedFile => ({
    path: 'features/orders.feature',
    content: ORDERS,
    bytes: 100,
    scenarios: 1,
    ...over,
  })

  it('refuses more than 200 files', () => {
    const many = Array.from({ length: 201 }, (_, index) => picked({ path: `f${index}.feature` }))
    expect(capsRefusals(many)).toContain('Send at most 200 files in one request.')
  })

  it('refuses a file over 1 MiB, naming it', () => {
    expect(capsRefusals([picked({ bytes: 1024 * 1024 + 1 })])).toContain(
      "'features/orders.feature' is larger than 1 MiB.",
    )
  })

  it('refuses more than 8 MiB in total', () => {
    const nine = Array.from({ length: 9 }, (_, index) =>
      picked({ path: `f${index}.feature`, bytes: 1024 * 1024 }),
    )
    expect(capsRefusals(nine)).toContain('The files total more than 8 MiB.')
  })

  it('refuses more than 500 scenarios, without claiming to have parsed them', () => {
    const refusals = capsRefusals([picked({ scenarios: 501 })])
    expect(refusals).toHaveLength(1)
    // "look like they hold" — the client counts keyword lines and cannot see a `Scenario:` inside a
    // doc string, so the sentence must not assert what only the parser knows.
    expect(refusals[0]).toContain('look like they hold more than 500 scenarios')
  })

  it('de-duplicates identical refusals', () => {
    // FIVE files, not three: at 2 MiB each, three of them total 6 MiB and never reach the 8 MiB
    // cap this arm is about. Five oversized files produce five DIFFERENT sentences (each names its
    // file) plus ONE total — what must not happen is a copy of the total sentence per file.
    const big = Array.from({ length: 5 }, (_, index) =>
      picked({ path: `f${index}.feature`, bytes: 2 * 1024 * 1024 }),
    )
    const refusals = capsRefusals(big)
    expect(refusals.filter((line) => line === 'The files total more than 8 MiB.')).toHaveLength(1)
    expect(refusals.filter((line) => line.endsWith('is larger than 1 MiB.'))).toHaveLength(5)
  })

  it('counts the scenario keywords a file declares', () => {
    expect(countScenarios(ORDERS)).toBe(1)
    expect(countScenarios('Scenario: a\nScenario Outline: b\n  Example: c\n')).toBe(3)
  })

  it('sends nothing when a picked file is over the per-file cap', async () => {
    const post = vi.spyOn(api, 'post')
    renderPage()

    // The size is faked rather than held: a real 9 MiB `File` costs the suite 9 MB of heap and its
    // `text()` read, to prove a comparison against `file.size`. What is under test is that the
    // screen reads the size and refuses BEFORE the request — the bytes themselves prove nothing.
    await pick([ofSize(featureFile('huge.feature'), 9 * 1024 * 1024)])

    expect(screen.getByTestId('FeatureImport.CapRefusal')).toHaveTextContent(
      "'huge.feature' is larger than 1 MiB.",
    )
    expect(screen.getByTestId('FeatureImport.Preview')).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })

  it('reads only .feature files out of a pick', async () => {
    renderPage()

    fireEvent.change(screen.getByTestId('FeatureImport.FileInput'), {
      target: { files: [featureFile('orders.feature'), new File(['# notes'], 'README.md')] },
    })

    await waitFor(() => expect(screen.getAllByTestId('FeatureImport.File')).toHaveLength(1))
    expect(screen.getByTestId('FeatureImport.FileList')).toHaveTextContent('orders.feature')
  })
})

describe('FeatureImportPage — dropping a folder', () => {
  /**
   * A directory entry whose children are read in one batch, then an empty one.
   *
   * **A folder drop carries no `File`s at all** — `dataTransfer.files` is empty and the tree is
   * only reachable through `webkitGetAsEntry()`. So this fake is the shape the real API has,
   * `readEntries`' hundred-entry batching included, and it is what proves the page reads the
   * gesture the screen is built around rather than a file list a folder never populates.
   */
  function fileEntry(fullPath: string, content = ORDERS): FileSystemEntry {
    const file = new File([content], fullPath.split('/').pop() ?? '', { type: 'text/plain' })
    return {
      isFile: true,
      isDirectory: false,
      fullPath,
      file: (ok: (given: File) => void) => ok(file),
    } as unknown as FileSystemEntry
  }

  function dirEntry(fullPath: string, children: FileSystemEntry[]): FileSystemEntry {
    let drained = false
    return {
      isFile: false,
      isDirectory: true,
      fullPath,
      createReader: () => ({
        readEntries: (ok: (given: FileSystemEntry[]) => void) => {
          ok(drained ? [] : children)
          drained = true
        },
      }),
    } as unknown as FileSystemEntry
  }

  it('reads every .feature under the dropped folder, keeping its relative path', async () => {
    renderPage()

    const folder = dirEntry('/features', [
      fileEntry('/features/orders.feature'),
      fileEntry('/features/payments.feature'),
      dirEntry('/features/rules', [
        fileEntry('/features/rules/shipping.feature'),
        // Not a feature file, and a real automation repository is full of them.
        fileEntry('/features/rules/steps.java', 'class Steps {}'),
      ]),
    ])

    fireEvent.drop(screen.getByTestId('FeatureImport.DropZone'), {
      dataTransfer: { items: [{ webkitGetAsEntry: () => folder }], files: [] },
    })

    await waitFor(() => expect(screen.getAllByTestId('FeatureImport.File')).toHaveLength(3))
    const list = screen.getByTestId('FeatureImport.FileList')
    // The leading slash is stripped and the rest is kept: a multipart part would have carried
    // `shipping.feature`, and `features/rules/shipping.feature` is what somebody can find.
    expect(list).toHaveTextContent('features/orders.feature')
    expect(list).toHaveTextContent('features/rules/shipping.feature')
    expect(list).not.toHaveTextContent('steps.java')
  })
})

describe('FeatureImportPage — the failures', () => {
  it("puts the server's errors.files beside the drop zone", async () => {
    // The refused write is the subject: a request over a cap is the ordinary answer here, and the
    // sentence belongs beside the files it is about.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Validation failed', { files: ['more than 500 scenarios in one request'] }),
    )
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    await waitFor(() =>
      expect(screen.getByTestId('FeatureImport.FileErrors')).toHaveTextContent(
        'more than 500 scenarios in one request',
      ),
    )
    // The field carries it, so the region must not report the same refusal a second time in its
    // own words.
    expect(screen.queryByTestId('FeatureImport.Plan.Error')).not.toBeInTheDocument()
  })

  it('describes a lost race on an automation key with the server’s own sentence', async () => {
    // The refused write is the subject: the 409 is the only failure that rolls anything back, and
    // what it says is the whole of this arm.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(
        409,
        'Another writer took one of these automation keys while this import ran. Nothing was written; preview it again to see which.',
        null,
        null,
        null,
        'That key was taken',
      ),
    )
    renderPage()
    await pick([featureFile('orders.feature')])

    await userEvent.click(screen.getByTestId('FeatureImport.Preview'))

    // `describeError`'s 409 branch reads the TITLE: this is not a lost update, so the server's
    // detail is what reaches the screen rather than "reload to see their version", which would tell
    // the caller to reload a page with nothing stale on it. The 409 is also the only failure here
    // that rolls anything back, which is why the sentence saying so has to survive.
    await waitFor(() =>
      expect(screen.getByTestId('FeatureImport.Plan.Error')).toHaveTextContent('That key was taken'),
    )
    expect(screen.getByTestId('FeatureImport.Plan.Error')).toHaveTextContent('Nothing was written')
  })
})
