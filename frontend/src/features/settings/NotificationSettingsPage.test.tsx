import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { NotificationChannel, NotificationDelivery, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { heldPermissions } from '../../test/permissions'
import {
  aNotificationChannel,
  aNotificationDelivery,
  aNotificationSubscription,
  aProject,
} from '../../test/fixtures'
import { NotificationSettingsPage } from './NotificationSettingsPage'

/**
 * The notification-channel settings screen (§218 phase 3).
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **THE test — an edit that leaves the webhook field empty sends no `secret` property at
 *      all.** Absent means *unchanged*; `''` and `null` both mean *rotate to nothing*. The
 *      difference is one spread in `NotificationChannelDialog` and the failure it prevents has no
 *      error and no symptom: the save succeeds, the row goes on reading as configured, and delivery
 *      stops hours later at a time unconnected to the edit. Asserted on the **payload**, which is
 *      what §199 built the instrument for — an assertion on the outcome could not tell the two
 *      apart, because a blanked credential and a kept one both answer 200.
 *   2. **A caller without `integration:manage` gets a sentence and no request.** Every operation
 *      behind this screen is gated, reads included, so `ReviewSettingsPage`'s render-it-read-only
 *      pattern is unavailable — the data does not arrive. An ungated fetch would 403 and raise a
 *      global "Something didn't load" over a screen working as designed.
 *   3. **`kind` is disabled on an edit and still submitted.** The `PUT` ignores it and the input
 *      schema requires it, so dropping the control is not available either.
 *   4. **`subscriptions` is the complete set.** Unticking is how a channel unsubscribes, and a form
 *      that sent only the ticked *additions* would make removal impossible with no error anywhere.
 *   5. **`If-Match` carries the version the client read**, so two administrators editing one
 *      channel do not lose each other's words.
 */

const PROJECT = 'p1'

function channels(...rows: NotificationChannel[]): NotificationChannel[] {
  return rows
}

/*
  Every arm is ANCHORED. `…/notification-channels` is a prefix of `…/notification-channels/{id}` and
  of `…/{id}/deliveries`, so a `startsWith` here would answer the delivery log with the channel list
  — which the contract comparison would then report against the wrong schema.
*/
function mockReads(
  rows: NotificationChannel[],
  permissions: PermissionKey[] = ['integration:manage'],
  deliveries: NotificationDelivery[] = [],
) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, PROJECT)
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    if (/^\/projects\/[^/]+\/notification-channels$/.test(path)) return rows
    if (/^\/projects\/[^/]+\/notification-channels\/[^/]+\/deliveries$/.test(path)) return deliveries
    if (/^\/projects\/[^/]+\/notification-channels\/[^/]+$/.test(path)) return rows[0]
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/settings/notifications`}
    >
      <Routes>
        <Route
          path="/projects/:projectId/settings/notifications"
          element={<NotificationSettingsPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answer has landed — without it every "enabled" assertion below passes
 * for the wrong reason, because `useCan` is fail-closed while pending (§66).
 */
async function enabled(testId: string) {
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled())
}

/**
 * Picks an option by its visible name.
 *
 * ~~MUI renders a select's options into a portal, so the listbox is found on the document.~~ →
 * **superseded (007 T15).** The transport picker is a native `<select>` now — one of four fixed
 * words, so the platform control — and its options are children of the box rather than a portal.
 * This helper is the only line of this file the port touched; every assertion below is unchanged.
 */
async function chooseFrom(testId: string, optionName: string | RegExp) {
  const select = screen.getByTestId(testId)
  const option = within(select).getByRole('option', { name: optionName }) as HTMLOptionElement
  await userEvent.selectOptions(select, option)
}

/** Opens the edit dialog on the first row and waits for its Save to be usable. */
async function openEditor() {
  await enabled('Notifications.Row.Edit')
  await userEvent.click(screen.getByTestId('Notifications.Row.Edit'))
  expect(await screen.findByTestId('Notifications.Dialog')).toBeInTheDocument()
}

const A_SLACK_CHANNEL = aNotificationChannel({
  id: 'nc-slack',
  name: 'QA alerts',
  kind: 'SLACK',
  secretHint: 'AbC1',
  lockVersion: 7,
  subscriptions: [aNotificationSubscription({ id: 'sub-1', eventType: 'execution.run_completed' })],
})

describe('NotificationSettingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the project’s channels, and shows only the masked tail of the credential', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    renderPage()

    expect(await screen.findByTestId('Notifications.Grid')).toBeInTheDocument()
    expect(screen.getByTestId('Notifications.Row.Name')).toHaveTextContent('QA alerts')
    expect(screen.getByTestId('Notifications.Row.Kind')).toHaveTextContent('Slack')
    // The tail and nothing else. `NotificationChannel` has no property that could carry a secret, so
    // the strongest thing a screen test can say is that what IS rendered is the hint.
    expect(screen.getByTestId('Notifications.Row.SecretHint')).toHaveTextContent('AbC1')
    expect(screen.getByTestId('Notifications.Row.Subscriptions')).toHaveTextContent('Run completed')
  })

  /**
   * The read gate, which is this screen's whole departure from `ReviewSettingsPage`. Asserted in
   * both halves — what is shown, and that nothing was asked for — because a page that rendered the
   * sentence *and* fired the request would 403 and raise a global notification behind it.
   */
  it('refuses the screen without integration:manage, and asks the server for nothing', async () => {
    const get = mockReads(channels(A_SLACK_CHANNEL), ['project:read'])
    renderPage()

    expect(await screen.findByTestId('Notifications.Denied')).toBeInTheDocument()
    expect(screen.queryByTestId('Notifications.Grid')).toBeNull()
    // An empty table would say this project has no channels, which is a different fact and one this
    // caller is not entitled to.
    expect(screen.queryByTestId('Notifications.List.Empty')).toBeNull()
    expect(
      get.mock.calls.filter(([path]) => String(path).includes('/notification-channels')),
    ).toHaveLength(0)
  })

  /**
   * THE test. Asserted on the payload's own key set rather than on `secret`'s value, because
   * `expect(body.secret).toBeUndefined()` passes for `{ secret: undefined }` — which `JSON.stringify`
   * drops, so it happens to be right today and would stop being right the moment the client sent the
   * property explicitly.
   */
  it('sends NO secret property when the webhook field is left empty — absent means unchanged', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const put = vi.spyOn(api, 'put').mockResolvedValue(aNotificationChannel() as never)
    renderPage()
    await openEditor()

    // Change something else entirely — the ordinary edit, and the one that would blank a live
    // credential if an empty field were read as a clear.
    await userEvent.clear(screen.getByTestId('Notifications.Dialog.Name'))
    await userEvent.type(screen.getByTestId('Notifications.Dialog.Name'), 'Release alerts')
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    const body = put.mock.calls[0][1] as Record<string, unknown>
    expect(Object.keys(body)).not.toContain('secret')
    expect(body.name).toBe('Release alerts')
  })

  it('sends the webhook URL when one is typed — that is how a credential is rotated', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const put = vi.spyOn(api, 'put').mockResolvedValue(aNotificationChannel() as never)
    renderPage()
    await openEditor()

    await userEvent.type(
      screen.getByTestId('Notifications.Dialog.Secret'),
      'https://hooks.slack.test/services/NEW',
    )
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    expect((put.mock.calls[0][1] as Record<string, unknown>).secret).toBe(
      'https://hooks.slack.test/services/NEW',
    )
  })

  /**
   * Two properties at once, and they pull in opposite directions: the control must be unusable
   * because the server ignores it, and the value must still be on the wire because the input schema
   * requires it. A form that solved the first by dropping the field would send a body the server
   * refuses with a 400 nobody typed.
   */
  it('disables the transport on an edit and submits the channel’s own kind anyway', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const put = vi.spyOn(api, 'put').mockResolvedValue(aNotificationChannel() as never)
    renderPage()
    await openEditor()

    expect(screen.getByTestId('Notifications.Dialog.Kind')).toBeDisabled()

    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))
    await waitFor(() => expect(put).toHaveBeenCalled())
    expect((put.mock.calls[0][1] as Record<string, unknown>).kind).toBe('SLACK')
  })

  it('carries the lockVersion it read as If-Match, so one edit cannot silently overwrite another', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const put = vi.spyOn(api, 'put').mockResolvedValue(aNotificationChannel() as never)
    renderPage()
    await openEditor()

    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))
    await waitFor(() => expect(put).toHaveBeenCalled())
    expect(put.mock.calls[0][2]).toEqual({ headers: { 'If-Match': '7' } })
  })

  /**
   * The array is reconciled against what is stored, so an event left out is unsubscribed. A form
   * that submitted only what the user *ticked this session* would make removal impossible and would
   * report success doing it.
   */
  it('submits the complete subscription set, so unticking is how a channel unsubscribes', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const put = vi.spyOn(api, 'put').mockResolvedValue(aNotificationChannel() as never)
    renderPage()
    await openEditor()

    // Seeded ticked from the channel's own subscriptions, which is the half a create-shaped form
    // gets wrong: an editor that started every box empty would unsubscribe everything on save.
    expect(
      screen.getByTestId('Notifications.Dialog.Event.execution.run_completed'),
    ).toBeChecked()

    await userEvent.click(screen.getByTestId('Notifications.Dialog.Event.execution.run_completed'))
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Event.work.item_assigned'))
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    expect((put.mock.calls[0][1] as Record<string, unknown>).subscriptions).toEqual([
      { eventType: 'work.item_assigned', minFailures: 0 },
    ])
  })

  it('will not create a webhook channel without a URL, and sends it once there is one', async () => {
    mockReads(channels())
    const post = vi.spyOn(api, 'post').mockResolvedValue(aNotificationChannel() as never)
    renderPage()

    await enabled('Notifications.Add')
    await userEvent.click(screen.getByTestId('Notifications.Add'))
    await userEvent.type(screen.getByTestId('Notifications.Dialog.Name'), 'QA alerts')

    // Required on the create for a webhook kind — and only there. The same field left blank on an
    // edit is the ordinary case, which the test above covers.
    expect(screen.getByTestId('Notifications.Dialog.Submit')).toBeDisabled()

    await userEvent.type(
      screen.getByTestId('Notifications.Dialog.Secret'),
      'https://hooks.slack.test/services/X',
    )
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toEqual({
      kind: 'SLACK',
      name: 'QA alerts',
      enabled: true,
      // `[]` for a webhook kind, which the contract asks for rather than an omission.
      recipients: [],
      subscriptions: [],
      secret: 'https://hooks.slack.test/services/X',
    })
  })

  it('asks an email channel for addresses instead of a credential', async () => {
    mockReads(channels())
    const post = vi.spyOn(api, 'post').mockResolvedValue(aNotificationChannel() as never)
    renderPage()

    await enabled('Notifications.Add')
    await userEvent.click(screen.getByTestId('Notifications.Add'))
    await chooseFrom('Notifications.Dialog.Kind', 'Email')

    // Email authenticates as the deployment's mail relay, which is not a company-owned value, so
    // there is nothing for this channel to hold — the field is gone rather than optional.
    expect(screen.queryByTestId('Notifications.Dialog.Secret')).toBeNull()

    await userEvent.type(screen.getByTestId('Notifications.Dialog.Name'), 'Release leads')
    await userEvent.type(
      screen.getByTestId('Notifications.Dialog.Recipients'),
      'lead@acme.test, qa@acme.test',
    )
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect((post.mock.calls[0][1] as Record<string, unknown>).recipients).toEqual([
      'lead@acme.test',
      'qa@acme.test',
    ])
  })

  /**
   * The log hangs off the channel, so nothing is fetched until one is selected — and the row it
   * renders may name an event the live vocabulary no longer has, which is why that column reads
   * through a fallback rather than a `satisfies`-checked map.
   */
  it('fetches a channel’s delivery history only once it is selected, and renders a retired event name', async () => {
    const get = mockReads(channels(A_SLACK_CHANNEL), ['integration:manage'], [
      aNotificationDelivery({
        id: 'nd-1',
        channelId: 'nc-slack',
        status: 'FAILED',
        attempt: 3,
        error: 'HTTP 404 from the remote endpoint',
      }),
      aNotificationDelivery({
        id: 'nd-2',
        channelId: 'nc-slack',
        // A name this instance no longer sends. `NotificationDelivery.eventType` is a bare string
        // for exactly this, and the contract comparison permits it where the enum would not.
        eventType: 'work.defect_created',
      }),
    ])
    renderPage()

    await enabled('Notifications.Row.History')
    expect(get.mock.calls.filter(([path]) => String(path).endsWith('/deliveries'))).toHaveLength(0)

    await userEvent.click(screen.getByTestId('Notifications.Row.History'))

    expect(await screen.findByTestId('Notifications.History.Grid')).toBeInTheDocument()
    const rows = screen.getAllByTestId('Notifications.History.Row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('Notifications.History.Row.Status')).toHaveTextContent('Failed')
    expect(within(rows[0]).getByTestId('Notifications.History.Row.Error')).toHaveTextContent(
      'HTTP 404 from the remote endpoint',
    )
    // Humanised rather than blank — the row happened, whatever the vocabulary says now.
    expect(within(rows[1]).getByTestId('Notifications.History.Row.Event')).toHaveTextContent(
      'Defect created',
    )
  })

  /**
   * A 409 is a lost update and the copy for it belongs to `describeError`. The assertion is that the
   * dialog shows the server's own sentence and **keeps the user's input**, which is the half a
   * close-on-error would throw away.
   */
  it('keeps the form open with describeError’s sentence when another admin saved first', async () => {
    // The subject of this test IS a refused write, so the failed-write check is suspended for it.
    drivingAFailedWrite()
    mockReads(channels(A_SLACK_CHANNEL))
    vi.spyOn(api, 'put').mockRejectedValue(
      new ApiError(409, 'stale', null, null, null, 'Someone else saved first'),
    )
    renderPage()
    await openEditor()

    await userEvent.clear(screen.getByTestId('Notifications.Dialog.Name'))
    await userEvent.type(screen.getByTestId('Notifications.Dialog.Name'), 'Release alerts')
    await userEvent.click(screen.getByTestId('Notifications.Dialog.Submit'))

    expect(await screen.findByTestId('Notifications.Dialog.Error')).toHaveTextContent(/Reload/i)
    expect(screen.getByTestId('Notifications.Dialog.Name')).toHaveValue('Release alerts')
  })

  it('says what a delete takes with it, and clears the history panel it was showing', async () => {
    mockReads(channels(A_SLACK_CHANNEL))
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await enabled('Notifications.Row.History')
    await userEvent.click(screen.getByTestId('Notifications.Row.History'))
    expect(await screen.findByTestId('Notifications.History')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('Notifications.Row.Delete'))
    // The consequence, not "are you sure" — the history is hard-deleted with the channel and there
    // is nowhere else it survives.
    expect(await screen.findByTestId('Notifications.Delete')).toHaveTextContent(
      /delivery history is deleted/i,
    )

    await userEvent.click(screen.getByTestId('Notifications.Delete.Confirm'))

    await waitFor(() => expect(del).toHaveBeenCalledWith('/projects/p1/notification-channels/nc-slack'))
    // The panel was headed by a channel that no longer exists; leaving it up would refetch a
    // sub-resource whose parent is gone and render a failed region on a screen where nothing failed.
    await waitFor(() => expect(screen.queryByTestId('Notifications.History')).toBeNull())
  })
})
