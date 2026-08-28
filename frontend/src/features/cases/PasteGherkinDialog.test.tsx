import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { Condition, GherkinParseResult, PermissionKey } from '../../api/types'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { PasteGherkinDialog } from './PasteGherkinDialog'

/**
 * The paste dialog (§255) — `POST .../cases/parse-gherkin` and the Background offer.
 *
 * What each arm is about, and why none of them can be reached by reading the file:
 *
 *   1. **The body is the raw text**, sent through `api.postText`. `api.post` would put a
 *      JSON-quoted string on the wire against a handler bound to `text/plain` and answer 415, and
 *      the request the code composes is exactly what the contract seam compares.
 *   2. **The refusal sentence is the SERVER's, on the textarea.** The four `gherkin-*` types are
 *      the first non-`about:blank` types this API sends and their sentence arrives on
 *      `errors.text`; composing words around it (§149) is how an author is told two things about
 *      one typo.
 *   3. **A Background entry offers a precondition, disabled with the reason when
 *      `library:manage` is not held** — disable, don't hide, because a hidden offer reads as a
 *      Background the paste dropped.
 *   4. **Append and Replace are two different answers** and the dialog hands the whole result up:
 *      what happens to the title, the objective and the tags is the editor's decision, not this
 *      dialog's.
 */

const PROJECT = 'p1'

function parsed(over: Partial<GherkinParseResult> = {}): GherkinParseResult {
  return {
    title: 'A settled payment cannot be refunded twice',
    objective: null,
    tags: [],
    background: [],
    steps: [
      { id: null, keyword: 'GIVEN', action: 'a settled payment', testData: null, expectedResult: null, docString: null, dataTable: null },
      { id: null, keyword: 'WHEN', action: 'I refund it twice', testData: null, expectedResult: null, docString: null, dataTable: null },
    ],
    ...over,
  }
}

function aCondition(over: Partial<Condition> = {}): Condition {
  return {
    id: 'lib-9',
    title: 'Given a settled payment',
    type: 'PRE',
    body: ['Given a settled payment'],
    usageCount: 0,
    suiteUsageCount: 0,
    lockVersion: 0,
    ...over,
  }
}

function renderDialog(hasSteps = false) {
  const onApply = vi.fn()
  const onConditionCreated = vi.fn()
  const onClose = vi.fn()
  render(
    <TestProviders client={createTestQueryClient()}>
      <PasteGherkinDialog
        projectId={PROJECT}
        open
        onClose={onClose}
        onApply={onApply}
        onConditionCreated={onConditionCreated}
        hasSteps={hasSteps}
      />
    </TestProviders>,
  )
  return { onApply, onConditionCreated, onClose }
}

/** The only read this dialog makes is the permission gate's. */
function mockPermissions(permissions: PermissionKey[] = ['case:edit', 'library:manage']) {
  vi.spyOn(api, 'get').mockImplementation((async () =>
    heldPermissions(permissions, {}, PROJECT)) as never)
}

/**
 * Pastes and presses Read.
 *
 * `paste`, not `type`: the box is a controlled textarea and typing a sentence into one is what put
 * this suite past its per-test budget twice (`vitest.config.ts`). A paste is also what an author
 * actually does with a scenario.
 */
async function pasteAndRead(text = 'Given a settled payment') {
  const user = userEvent.setup()
  await user.click(screen.getByTestId('PasteGherkin.Text'))
  await user.paste(text)
  await user.click(screen.getByTestId('PasteGherkin.Parse'))
  return user
}

beforeEach(() => {
  mockPermissions()
})

describe('PasteGherkinDialog — the request', () => {
  it('sends the pasted text as the body, verbatim', async () => {
    const post = vi.spyOn(api, 'postText').mockResolvedValue(parsed() as never)
    renderDialog()

    await pasteAndRead('Given a settled payment')

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/cases/parse-gherkin`)
    // The raw string, not an object: the endpoint consumes `text/plain` and refuses anything else
    // with a 415 the container writes before any handler is chosen.
    expect(post.mock.calls[0][1]).toBe('Given a settled payment')
  })

  it('refuses to send an empty box', () => {
    renderDialog()
    expect(screen.getByTestId('PasteGherkin.Parse')).toBeDisabled()
  })

  it('shows the rows it read, with `*` for a step that has no keyword', async () => {
    vi.spyOn(api, 'postText').mockResolvedValue(
      parsed({
        steps: [
          { id: null, keyword: null, action: 'open the app', testData: null, expectedResult: null, docString: null, dataTable: null },
        ],
      }) as never,
    )
    renderDialog()

    await pasteAndRead()

    const rows = await screen.findAllByTestId('PasteGherkin.Step')
    expect(rows).toHaveLength(1)
    // `*` is Gherkin's own spelling for a step with no keyword — a blank would read as a value the
    // parser failed to produce.
    expect(rows[0]).toHaveTextContent('*')
  })
})

describe('PasteGherkinDialog — the four refusals', () => {
  it('puts the server’s sentence on the textarea, verbatim', async () => {
    // The refused write is the subject: the whole subject is an unparseable paste, which is the ordinary case here.
    drivingAFailedWrite()
    vi.spyOn(api, 'postText').mockRejectedValue(
      new ApiError(400, 'The pasted text could not be read as a scenario.', {
        text: ['Line 4, column 3: expected a step'],
      }),
    )
    renderDialog()

    await pasteAndRead('nonsense')

    expect(await screen.findByText('Line 4, column 3: expected a step')).toBeInTheDocument()
  })

  it('does not ALSO raise a banner for a refusal, so one typo is stated once', async () => {
    // The refused write is the subject: a refused paste — the sentence belongs on the field, not in a banner.
    drivingAFailedWrite()
    vi.spyOn(api, 'postText').mockRejectedValue(
      new ApiError(400, 'The pasted text could not be read as a scenario.', {
        text: ['This text holds 3 scenarios; paste one, or import the file'],
      }),
    )
    renderDialog()

    await pasteAndRead('two scenarios')

    await screen.findByText('This text holds 3 scenarios; paste one, or import the file')
    expect(screen.queryByTestId('PasteGherkin.Error')).not.toBeInTheDocument()
  })

  it('DOES raise a banner for a failure that carries no sentence — a 413, a timeout', async () => {
    // The refused write is the subject: an oversized paste is a 413 with no field errors on it.
    drivingAFailedWrite()
    vi.spyOn(api, 'postText').mockRejectedValue(
      new ApiError(413, 'This endpoint reads one scenario, up to 256 KiB.'),
    )
    renderDialog()

    await pasteAndRead('a very large paste')

    expect(await screen.findByTestId('PasteGherkin.Error')).toBeInTheDocument()
  })
})

describe('PasteGherkinDialog — applying', () => {
  it('hands the whole result up, and says which button was pressed', async () => {
    const result = parsed({ tags: ['smoke'] })
    vi.spyOn(api, 'postText').mockResolvedValue(result as never)
    const { onApply } = renderDialog()

    const user = await pasteAndRead()
    await user.click(await screen.findByTestId('PasteGherkin.Append'))

    expect(onApply).toHaveBeenCalledWith(result, 'APPEND')
  })

  it('reports Replace distinctly, because it is the one that discards step ids', async () => {
    const result = parsed()
    vi.spyOn(api, 'postText').mockResolvedValue(result as never)
    const { onApply } = renderDialog(true)

    const user = await pasteAndRead()
    await user.click(await screen.findByTestId('PasteGherkin.Replace'))

    expect(onApply).toHaveBeenCalledWith(result, 'REPLACE')
  })

  it('offers neither until something has been read', () => {
    renderDialog()
    expect(screen.getByTestId('PasteGherkin.Append')).toBeDisabled()
    expect(screen.getByTestId('PasteGherkin.Replace')).toBeDisabled()
  })
})

describe('PasteGherkinDialog — the Background', () => {
  const withBackground = () =>
    parsed({
      background: [
        { title: 'Given a signed-in merchant', body: ['Given a signed-in merchant'] },
        { title: 'And a funded account', body: ['And a funded account'] },
      ],
    })

  it('offers ONE precondition per Background step, not one for the block', async () => {
    // Amendment AM1. Collapsing them would make "sign in" and "fund the account" one library entry
    // no other case can reuse half of.
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    renderDialog()

    await pasteAndRead()

    expect(await screen.findAllByTestId('PasteGherkin.Background.Row')).toHaveLength(2)
  })

  it('creates the condition and hands it back for the editor to attach', async () => {
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    const created = aCondition()
    const post = vi.spyOn(api, 'post').mockResolvedValue(created as never)
    const { onConditionCreated } = renderDialog()

    const user = await pasteAndRead()
    await user.click((await screen.findAllByTestId('PasteGherkin.Background.Create'))[0])

    await waitFor(() => expect(onConditionCreated).toHaveBeenCalledWith(created))
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/conditions`)
    // The step line is both the title and the first line of the body — a body that omitted the
    // step itself would show a tester only its attachments (`ParsedBackground.body`).
    expect(post.mock.calls[0][1]).toEqual({
      title: 'Given a signed-in merchant',
      type: 'PRE',
      body: ['Given a signed-in merchant'],
    })
  })

  it('marks the entry attached, so the same one is not created twice', async () => {
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    vi.spyOn(api, 'post').mockResolvedValue(aCondition() as never)
    renderDialog()

    const user = await pasteAndRead()
    await user.click((await screen.findAllByTestId('PasteGherkin.Background.Create'))[0])

    expect(await screen.findByTestId('PasteGherkin.Background.Attached')).toBeInTheDocument()
    // The second entry is untouched: the tick is per entry, not per dialog.
    expect(screen.getAllByTestId('PasteGherkin.Background.Create')).toHaveLength(1)
  })

  it('disables the offer with the reason when `library:manage` is not held', async () => {
    // Disable, don't hide: a caller without the key still sees that the Background exists and that
    // the rows are still applicable — hiding it would read as a paste that silently dropped it.
    mockPermissions(['case:edit'])
    vi.spyOn(api, 'postText').mockResolvedValue(withBackground() as never)
    renderDialog()

    await pasteAndRead()

    const offers = await screen.findAllByTestId('PasteGherkin.Background.Create')
    await waitFor(() => expect(offers[0]).toBeDisabled())
  })
})
