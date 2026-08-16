import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { RegisterArtifactDialog } from './ArtifactDialogs'
import { heldPermissions } from '../../test/permissions'
import type { PermissionKey } from '../../api/types'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Registering an artifact — the form whose fields decide what its other fields mean.
 *
 * Four things are locked here, and every one is invisible to the build and the linter:
 *
 *   1. **The scope-qualified 403 lands on the scope selector, not in a banner.** This is the one
 *      the whole slice turns on. A Test Engineer holds `artifact:edit` — just not at project scope
 *      — so `PermissionGate` cannot disable the button and §31's disable-don't-hide rule is
 *      structurally unavailable: the SPA's permission model is keys-only and has no qualifier to
 *      read. The server therefore answers that single refusal with a *body*, and if the dialog
 *      treats a 403 the way it treats every other 403 the user gets "forbidden" on a form they are
 *      one radio button away from submitting.
 *   2. **The payload matches the scope and kind chosen**, sending `null` rather than the leftover
 *      value of a control that is no longer displayed. The server refuses a `planId` at PROJECT
 *      scope rather than ignoring it, so a stale value is a 400 on a field the user cannot see.
 *   3. **The cross-field rules match the server's**, so the form cannot refuse what the API accepts
 *      or accept what it refuses. The `javascript:` case is a security control, not tidiness.
 *   4. **The plan being viewed is carried into the form.** Somebody on Release 1's register who
 *      presses Register means Release 1.
 */

const plans = [
  { id: 'plan-1', key: 'OLB-P1', name: 'Release 1', status: 'ACTIVE' },
  { id: 'plan-2', key: 'OLB-P2', name: 'Release 2', status: 'DRAFT' },
] as never

const pages = [
  { id: 'page-1', key: 'DOC-1', title: 'Test strategy', parentId: null, slug: 's', status: 'PUBLISHED', updatedAt: '2026-08-01T00:00:00Z' },
] as never

const registered = {
  id: 'artifact-1',
  scope: 'PLAN',
  category: 'SignOff',
  kind: 'EXTERNAL_URL',
  title: 'Sign-off',
  description: null,
  status: 'ACTIVE',
  inherited: false,
  url: 'https://sign.example.test/r1',
  docPageId: null,
  filename: null,
  sizeBytes: null,
  writeOnce: false,
  updatedBy: { id: 'u1', displayName: 'L. Lead', email: 'l@x.test', avatarUrl: null },
  updatedAt: '2026-08-02T00:00:00Z',
}

/**
 * The gate reads `artifact:edit`; nothing else in this dialog reads anything.
 *
 * Keys are `ALL` unless named in `qualified` — the shape `/me/permissions` has carried since §65,
 * and the reason this dialog can disable a control instead of waiting for the server to refuse it.
 */
function mockPermissions(
  permissions: PermissionKey[],
  qualified: Record<string, 'OWN' | 'PLAN_SCOPE'> = {},
  register: unknown[] = [],
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, qualified)
    // The Replaces picker's own read (§209), at the scope the FORM is on rather than the one the
    // register is filtered to. Answered even when the case under test has nothing to replace: a
    // read this dialog always issues has to be mocked, or every test in the file fails on a
    // failed fetch rather than on its own subject.
    if (path.startsWith('/projects/p1/artifacts')) return register
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderDialog(defaultPlanId?: string) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <RegisterArtifactDialog
        open
        onClose={onClose}
        projectId="p1"
        plans={plans}
        pages={pages}
        defaultPlanId={defaultPlanId}
        onRegistered={onRegistered}
      />
    </TestProviders>,
  )
}

let onClose: () => void
let onRegistered: () => void

/** MUI renders a select's options into a portal, so the listbox is found on the document. */
async function choose(testId: string, optionName: string | RegExp) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root')!
  await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

describe('RegisterArtifactDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
    onRegistered = vi.fn()
    mockPermissions(['artifact:edit'])
  })

  /*
    §65 — the qualifier reaches the control. These assert through `role="option"`, never through a
    testid, because §62 cost a live pass to learn that a MUI `Select` item its parent never
    processed carries no `aria-disabled` either: "not disabled" and "not an option at all" read
    identically to a query that looks the element up by testid and asks whether it is disabled.
  */
  describe('the PLAN_SCOPE qualifier disables the project scope before the submit', () => {
    /** Every option in the "Belongs to" select, by accessible name and disabled state. */
    async function scopeOptions() {
      const select = screen.getByTestId('Artifacts.Dialog.Scope').closest('.MuiInputBase-root')!
      await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
      const listbox = await screen.findByRole('listbox')
      return within(listbox)
        .getAllByRole('option')
        .map((option) => ({
          name: option.textContent ?? '',
          disabled: option.getAttribute('aria-disabled') === 'true',
        }))
    }

    it('offers a Test Engineer the plan and refuses them the project', async () => {
      mockPermissions(['artifact:edit'], { 'artifact:edit': 'PLAN_SCOPE' })
      renderDialog()

      // The caller HOLDS artifact:edit, so the dialog opens and its Register button is enabled.
      // Only the one choice their grant does not cover is closed off.
      const options = await scopeOptions()
      expect(options).toHaveLength(2)
      expect(options.find((o) => /project/i.test(o.name))?.disabled).toBe(true)
      expect(options.find((o) => /one plan/i.test(o.name))?.disabled).toBe(false)
    })

    it('leaves both open for a Test Lead, so the test above is about the qualifier', async () => {
      // Without this, the assertion above passes just as well against a dialog whose project
      // option is disabled for everybody — which would lock out the role the feature is for.
      mockPermissions(['artifact:edit'])
      renderDialog()

      const options = await scopeOptions()
      expect(options.every((o) => !o.disabled)).toBe(true)
    })

    it('moves the form off PROJECT scope rather than opening on a disabled choice', async () => {
      // The default is computed at mount, when the permission query is still in flight and the
      // gate is fail-closed. Landing on a value the user cannot submit — and cannot see why — is
      // worse than the 403 this replaces.
      mockPermissions(['artifact:edit'], { 'artifact:edit': 'PLAN_SCOPE' })
      renderDialog()

      await waitFor(() => {
        expect(screen.getByTestId('Artifacts.Dialog.Scope')).toHaveValue('PLAN')
      })
      // And the plan picker is showing, because that is what PLAN scope requires.
      expect(screen.getByTestId('Artifacts.Dialog.Plan')).toBeInTheDocument()
    })

    it('explains the restriction instead of claiming they lack the permission', async () => {
      mockPermissions(['artifact:edit'], { 'artifact:edit': 'PLAN_SCOPE' })
      renderDialog()

      await waitFor(() => {
        expect(screen.getByText(/covers a single plan, not the whole project/i)).toBeInTheDocument()
      })
      // The sentence for a permission nobody holds would be false here and would send them to ask
      // an administrator for a grant they already have.
      expect(screen.queryByText(/don't have permission/i)).not.toBeInTheDocument()
    })
  })

  it('puts the scope-qualified 403 on the scope selector rather than in a banner', async () => {
    // The refused write is the subject: this asserts which control the 403 lands on.
    drivingAFailedWrite()
    // THE test of this file. The server distinguishes "you hold nothing" (bodiless 403) from "you
    // hold it, not here" (403 with an errors map), and only the second is actionable.
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(403, 'Your role can add artifacts to a plan, but not to the project.', {
        scope: ['Your role can add artifacts to a plan, but not to the project as a whole.'],
      }),
    )
    renderDialog()

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Test strategy')
    await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://wiki.example.test/s')
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => {
      expect(screen.getByText(/can add artifacts to a plan, but not to the project/i))
        .toBeInTheDocument()
    })
    // Still open: the user changes the scope and submits again. Closing on a refusal would throw
    // away a form that is one choice from succeeding.
    expect(onClose).not.toHaveBeenCalled()
    expect(onRegistered).not.toHaveBeenCalled()
  })

  it('sends no planId at PROJECT scope, even after a plan was chosen and the scope changed back', async () => {
    // The stale-value case. The server REFUSES a planId at PROJECT scope rather than ignoring it
    // (§50), so a leftover value is a 400 on a control the user can no longer see.
    const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
    renderDialog('plan-1')

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Strategy')
    await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://wiki.example.test/s')
    await choose('Artifacts.Dialog.Scope', /every plan inherits it/i)
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({ scope: 'PROJECT', planId: null })
  })

  it('carries the plan being viewed into the form', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
    renderDialog('plan-2')

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Sign-off')
    await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://sign.example.test/r2')
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({ scope: 'PLAN', planId: 'plan-2' })
  })

  it('refuses a javascript: link without asking the server', async () => {
    // A security control: the register renders EXTERNAL_URL rows inside an anchor, so this value
    // stored would be XSS in the product's own markup. The server refuses it too — this is so the
    // user finds out before submitting, and so the rule exists in the layer that renders the link.
    const post = vi.spyOn(api, 'post')
    renderDialog()

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Innocent')
    await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'javascript:alert(1)')
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => {
      expect(screen.getByText(/must start with http:\/\/ or https:\/\//i)).toBeInTheDocument()
    })
    expect(post).not.toHaveBeenCalled()
  })

  /*
    C7 — the sign-off toggle, `artifact.write_once`'s first writer.

    The column has been enforced by the delete since §126 and by the edit since §229 against a
    value only psql could produce. What is asserted here is the half that is invisible to the
    build: the checkbox reaches the payload as a boolean, and its absence is `false` rather than
    `undefined` — the server reads an absent scalar as a clear (§68), so an undefined here would be
    the same request and a client that later learned to send `null` would not be.
  */
  describe('the sign-off toggle', () => {
    it('sends writeOnce when it is ticked', async () => {
      const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
      renderDialog()

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'March sign-off')
      await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://sign.example.test/m')
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.WriteOnce'))
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ writeOnce: true })
    })

    it('sends false when it is not, and says what the choice costs before it is made', async () => {
      const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
      renderDialog()

      // The flag cannot be set later and cannot be taken back, so a control that explained itself
      // afterwards would be explaining an act nobody can undo.
      expect(screen.getByText(/cannot be changed later/i)).toBeInTheDocument()

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Environment guide')
      await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://env.example.test/g')
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ writeOnce: false })
    })
  })

  /*
    C6 — the same dialog, pinning a report.

    Reuse rather than a second form is `ArtifactDialogs`' own rule (its header refuses a separate
    Upload dialog for the list of concerns both would share). What these lock is the part reuse can
    get wrong: the kind must be fixed rather than merely defaulted, and the job id must reach the
    payload from the PROP — a form field would be one more thing that can be edited into
    disagreeing with the report the user pressed Save on.
  */
  describe('pinning a report', () => {
    function renderPinning() {
      return render(
        <TestProviders client={createTestQueryClient()}>
          <RegisterArtifactDialog
            open
            onClose={onClose}
            projectId="p1"
            plans={plans}
            pages={pages}
            pinnedReport={{ jobId: 'job-9', defaultTitle: 'Release 1 — run summary' }}
            onRegistered={onRegistered}
          />
        </TestProviders>,
      )
    }

    it('sends GENERATED_REPORT and the job, with no payload of any other kind', async () => {
      const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
      renderPinning()

      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({
        kind: 'GENERATED_REPORT',
        reportJobId: 'job-9',
        // The server refuses these on this kind rather than ignoring them, so sending a leftover
        // is a 400 about a control the user never saw.
        docPageId: null,
        url: null,
      })
    })

    it('opens with the report’s own title, so nobody retypes what is on the screen behind it', async () => {
      renderPinning()

      expect(screen.getByTestId('Artifacts.Dialog.TitleField')).toHaveValue(
        'Release 1 — run summary',
      )
    })

    it('offers no kind picker — pressing Save on a report is the choice', async () => {
      renderPinning()

      // Every other option would invalidate the payload, so a picker here could only be used
      // wrongly. Queried by testid deliberately: the assertion is that the control is absent, not
      // that it is disabled, and §62's option-vs-testid caveat is about the opposite case.
      expect(screen.queryByTestId('Artifacts.Dialog.Kind')).not.toBeInTheDocument()
      expect(screen.getByTestId('Artifacts.Dialog.PinnedReport')).toBeInTheDocument()
    })
  })

  it('sends a docPageId and no url when the artifact is a documentation reference', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
    renderDialog()

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Test strategy')
    await choose('Artifacts.Dialog.Kind', /page in this project's documentation/i)
    await choose('Artifacts.Dialog.Page', /Test strategy/i)
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({
      kind: 'DOC_PAGE_REF',
      docPageId: 'page-1',
      url: null,
    })
  })

  /*
    #49 phase 3 — the third kind, which is a different ENDPOINT rather than a different payload.

    Everything here is invisible to the build: `api.upload` is generic over its response, the
    `FormData` it takes has no type at all, and a part sent with the wrong media type fails at the
    transport layer with a message about media types rather than about the artifact.
  */
  describe('an uploaded file goes to /artifacts/files, not to /artifacts', () => {
    /** A `File` of a stated size without allocating it — `size` is read-only on the real thing. */
    function sized(name: string, size: number): File {
      const file = new File(['x'], name, { type: 'application/pdf' })
      Object.defineProperty(file, 'size', { value: size })
      return file
    }

    /** `fireEvent`, not `userEvent.upload`: the input is `hidden` and pointer events cannot reach it. */
    function pick(file: File) {
      fireEvent.change(screen.getByTestId('Artifacts.Dialog.FileInput'), {
        target: { files: [file] },
      })
    }

    it('sends one multipart request whose artifact part is JSON and carries no kind', async () => {
      const upload = vi.spyOn(api, 'upload').mockResolvedValue(registered as never)
      const post = vi.spyOn(api, 'post')
      renderDialog('plan-1')

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Release sign-off')
      await choose('Artifacts.Dialog.Kind', /file to upload/i)
      pick(sized('sign-off.pdf', 12_000))
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(upload).toHaveBeenCalled())
      // The JSON create is not reached at all. A form that built an `ArtifactInput` with
      // `kind: 'FILE'` and posted it would be answered 400 by the server, minutes of confusion later.
      expect(post).not.toHaveBeenCalled()
      expect(upload.mock.calls[0][0]).toBe('/projects/p1/artifacts/files')

      const form = upload.mock.calls[0][1]
      const artifact = form.get('artifact') as Blob
      // The media type is the load-bearing part: `@RequestPart` picks its converter from the
      // part's own Content-Type, and a plain string part arrives as `text/plain` with no converter.
      expect(artifact.type).toBe('application/json')
      expect(JSON.parse(await artifact.text())).toEqual({
        scope: 'PLAN',
        planId: 'plan-1',
        // C7: the upload path takes the sign-off flag too — a countersigned PDF is bytes, not a
        // link, so this is the create it most often arrives on.
        writeOnce: false,
        category: 'Strategy',
        title: 'Release sign-off',
        description: null,
        // §209. `toEqual` rather than `toMatchObject` on purpose: the part is what the server
        // binds, so a field appearing in it that nobody meant to send is the thing to catch.
        supersedes: null,
      })
      expect((form.get('file') as File).name).toBe('sign-off.pdf')
    })

    it('refuses to submit with no file chosen, without asking the server', async () => {
      const upload = vi.spyOn(api, 'upload')
      renderDialog('plan-1')

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Release sign-off')
      await choose('Artifacts.Dialog.Kind', /file to upload/i)
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() =>
        expect(screen.getByText(/choose a file to register/i)).toBeInTheDocument(),
      )
      expect(upload).not.toHaveBeenCalled()
    })

    it('refuses a file over the instance limit on the picker, not in a banner', async () => {
      // A 413 is refused by the servlet container before any handler runs, so it carries no problem
      // document and there is nothing for `applyServerError` to land. This is the only place the
      // user can be told, and it has to be told before the bytes go up a slow link.
      const upload = vi.spyOn(api, 'upload')
      renderDialog('plan-1')

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Huge export')
      await choose('Artifacts.Dialog.Kind', /file to upload/i)
      pick(sized('dump.zip', 25 * 1024 * 1024))

      expect(screen.getByTestId('Artifacts.Dialog.FileHelp')).toHaveTextContent(/dump\.zip/)
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))
      expect(upload).not.toHaveBeenCalled()
    })

    it('keeps the scope qualifier for an upload — bytes change nothing about who may write a row', async () => {
      // The same `PLAN_SCOPE` restriction as the reference create, on the same control. A second
      // dialog for uploads is where this would have been forgotten.
      mockPermissions(['artifact:edit'], { 'artifact:edit': 'PLAN_SCOPE' })
      renderDialog()

      await choose('Artifacts.Dialog.Kind', /file to upload/i)
      await waitFor(() => expect(screen.getByTestId('Artifacts.Dialog.Scope')).toHaveValue('PLAN'))
    })

    it('offers no upload control for the two reference kinds', async () => {
      // The guard: without it every assertion above passes equally well against a dialog that
      // always shows a file picker, which would invite an upload the JSON endpoint cannot take.
      renderDialog('plan-1')

      expect(screen.queryByTestId('Artifacts.Dialog.FileInput')).toBeNull()
      await choose('Artifacts.Dialog.Kind', /page in this project's documentation/i)
      expect(screen.queryByTestId('Artifacts.Dialog.FileInput')).toBeNull()
    })
  })

  /*
    §209 — the picker for the act three refusals had been advising.

    Everything the field constrains is about ANOTHER row, so there is no Zod rule to assert: what
    is asserted is that the control cannot OFFER a row the server would refuse, which is the same
    rule stated where it can be enforced.
  */
  describe('Replaces', () => {
    const candidates = [
      { ...registered, id: 'active-1', title: 'Sign-off v1', status: 'ACTIVE', inherited: false },
      { ...registered, id: 'retired', title: 'Old guide', status: 'ARCHIVED', inherited: false },
      { ...registered, id: 'replaced', title: 'Sign-off v0', status: 'SUPERSEDED', inherited: false },
      { ...registered, id: 'from-project', title: 'Test strategy', status: 'ACTIVE', inherited: true },
    ]

    it('offers only what the server would accept — ACTIVE, and not an inherited row', async () => {
      mockPermissions(['artifact:edit'], {}, candidates)
      renderDialog('plan-1')

      const select = screen.getByTestId('Artifacts.Dialog.Supersedes').closest('.MuiInputBase-root')!
      await userEvent.click(within(select as HTMLElement).getByRole('combobox'))

      expect(await screen.findByRole('option', { name: /Sign-off v1/ })).toBeInTheDocument()
      // The three the server refuses with `errors.supersedes`: already replaced, archived, and —
      // the one that matters — a PROJECT-scope document a plan-scope registration may not retire.
      expect(screen.queryByRole('option', { name: /Old guide/ })).toBeNull()
      expect(screen.queryByRole('option', { name: /Sign-off v0/ })).toBeNull()
      expect(screen.queryByRole('option', { name: /Test strategy/ })).toBeNull()
    })

    it('sends the chosen artifact as supersedes, and null when nothing is chosen', async () => {
      mockPermissions(['artifact:edit'], {}, candidates)
      const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
      renderDialog('plan-1')

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Sign-off v2')
      await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://sign.example.test/r2')
      await choose('Artifacts.Dialog.Supersedes', /Sign-off v1/)
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      expect(post.mock.calls[0][1]).toMatchObject({ supersedes: 'active-1' })
    })

    it('sends null rather than an empty string for an ordinary registration', async () => {
      mockPermissions(['artifact:edit'], {}, candidates)
      const post = vi.spyOn(api, 'post').mockResolvedValue(registered as never)
      renderDialog('plan-1')

      await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Something new')
      await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://x.example.test/n')
      await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

      await waitFor(() => expect(post).toHaveBeenCalled())
      // `''` is not a uuid and not absent: the contract's field is nullable, and an empty string
      // would be a 400 about a field the user never touched.
      expect(post.mock.calls[0][1]).toMatchObject({ supersedes: null })
    })
  })

  it('closes and reports the artifact once the server has taken it', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(registered as never)
    renderDialog('plan-1')

    await userEvent.type(screen.getByTestId('Artifacts.Dialog.TitleField'), 'Sign-off')
    await userEvent.type(screen.getByTestId('Artifacts.Dialog.Url'), 'https://sign.example.test/r1')
    await userEvent.click(screen.getByTestId('Artifacts.Dialog.Submit'))

    await waitFor(() => expect(onRegistered).toHaveBeenCalledWith(registered))
    expect(onClose).toHaveBeenCalled()
  })
})
