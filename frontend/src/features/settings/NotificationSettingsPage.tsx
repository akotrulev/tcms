import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  useDeleteNotificationChannel,
  useNotificationChannels,
  useNotificationDeliveries,
  useProject,
} from '../../api/queries';
import type { NotificationChannel, NotificationDelivery } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import {
  notificationChannelKindLabels,
  notificationDeliveryStatusLabels,
  notificationEventLabel,
  notificationEventTypeLabels,
} from '../../lib/labels';
import { NotificationChannelDialog } from './NotificationChannelDialog';

/**
 * Where this project sends its notices (§218) — the third `/settings/` screen, and the first one
 * whose **reads** are gated.
 *
 * ## `ReviewSettingsPage`'s pattern is not available here, and that is the decision on this screen
 *
 * That page renders its current state for anybody who can reach the project and disables the
 * controls for anybody who cannot change them — §31's disable-don't-hide, made possible by a `GET`
 * gated on visibility alone. **Every operation behind this screen costs `integration:manage`,
 * reads included**, so a Test Engineer opening it does not get a read-only view: they get a 403 and
 * no data at all.
 *
 * §31 has to be answered differently when the answer is an empty response rather than a disabled
 * button, and the answer taken here is `ApiTokensPage`'s:
 *
 *   - **The rail item is NOT hidden.** Every entry in that rail is ungated, and hiding this one
 *     would teach a contributor that this instance cannot send notifications at all — which is the
 *     same lie an empty list would tell, and worse, because there is nothing to ask about.
 *   - **The page renders, and says who can.** The table is replaced by a sentence naming the
 *     missing capability and the role that holds it, rather than by an empty table or an error
 *     region. A table with no rows would say this project has no channels, which is a different
 *     fact and one this caller is not entitled to.
 *   - **Nothing is fetched.** The gate's verdict drives `enabled` on all three reads. An ungated
 *     fetch would raise a global "Something didn't load" over a screen working exactly as designed.
 *   - **Deferred while the verdict is pending, never rendered as a denial.** `Gate.can` is
 *     fail-closed while loading, which is right for disabling a button and would flash "you do not
 *     have permission" at a Test Lead on every visit. `Gate.pending` exists for this.
 *
 * The reason the reads are gated at all is worth carrying, because it generalises: *read the gate
 * off what the response carries, not off the screen the endpoint belongs to.* A channel row carries
 * `secretHint` — the tail of a live credential — and `recipients`, which are people's addresses,
 * and a delivery row's `error` is whatever a remote endpoint said back.
 *
 * ## The history hangs off a channel, so the log is about the selected one
 *
 * There is no project-level delivery log and the endpoint shape says so. A delivery is only ever
 * asked about as one — *is this webhook working* — and deleting a channel takes its history with
 * it, so a project-wide list would outlive the channels it names. Selecting a row is therefore the
 * only way into the log, and nothing is fetched until one is selected.
 *
 * ## A failing channel is escalated, not merely logged (007 T15)
 *
 * The render's finding for this family: *"Three identical rejections in a delivery table is data.
 * The banner that says nobody has been told about a plan approval since Tuesday is the finding."*
 * So the log carries a banner when the run of most recent attempts is all failures — the table
 * already held the rows, and nobody reads a table to discover that something stopped working.
 */

const TestId = 'Notifications';
const RowTestId = `${TestId}.Row`;

export function NotificationSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);

  const gate = useCan(projectId);
  /*
    Fail-closed while pending is right for a control and wrong for a fetch, so the verdict is read
    twice: `allowed` drives the reads and never fires one on a maybe, while `gate.pending` below
    keeps the refusal off the screen until the answer is real.
  */
  const allowed = !gate.pending && gate.can('integration:manage');

  const channels = useNotificationChannels(projectId, allowed);

  const [editing, setEditing] = useState<NotificationChannel | undefined>(undefined);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<NotificationChannel | null>(null);
  /*
    The id, not the row: the channel it names is refetched by the list beside it, and holding the
    object would leave the history panel headed by a stale name after an edit. It is also the shape
    the read hook wants.
  */
  const [inspecting, setInspecting] = useState<string | null>(null);

  const selected = (channels.data ?? []).find((channel) => channel.id === inspecting);

  return (
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Notifications"
        documentTitle={project.data ? `Notifications · ${project.data.name}` : 'Notifications'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Notifications' },
        ]}
        subtitle="Where this project sends word that a run finished or a task landed on somebody. Each channel posts to one place and chooses what it is told about."
        actions={
          /*
            GATED in §31's ordinary form — the permission is known before anything renders, and
            nothing inside the dialog can change which permission the server will check. The button
            is still rendered for a caller who may not press it, with the reason as its `title`:
            a disabled control receives no pointer events, so a hover card on one is a reason
            nobody can read.
          */
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed: mayManage, reason }) => (
              <Button
                variant="primary"
                disabled={!mayManage}
                title={mayManage ? undefined : reason}
                onClick={() => setAdding(true)}
                data-testid={`${TestId}.Add`}
              >
                Add a channel
              </Button>
            )}
          </PermissionGate>
        }
      />

      {/* Conditionally mounted, like every other write dialog in this area, so each one opens with a
          fresh draft rather than the last row's (§54). */}
      {adding && (
        <NotificationChannelDialog open projectId={projectId} onClose={() => setAdding(false)} />
      )}
      {editing && (
        <NotificationChannelDialog
          open
          projectId={projectId}
          channel={editing}
          onClose={() => setEditing(undefined)}
        />
      )}
      {deleting && (
        <DeleteChannelDialog
          projectId={projectId}
          channel={deleting}
          onClose={() => setDeleting(null)}
          onDeleted={(id) => {
            if (inspecting === id) setInspecting(null);
            setDeleting(null);
          }}
        />
      )}

      <div className="flex flex-col gap-7">
        <section data-testid={`${TestId}.Panel`}>
          <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
            Channels
          </h2>
          {gate.pending ? null : !allowed ? (
            <Banner tone="neutral" data-testid={`${TestId}.Denied`}>
              {/*
                A sentence, not an empty table and not an error. The list is refused rather than
                narrowed — a channel carries the masked tail of a live credential and a delivery
                carries whatever a remote endpoint said — so "no channels" is a fact this caller is
                not entitled to, and rendering it as one would be a confident falsehood.

                It names the permission and the role that holds it. A person cannot be named on
                this codebase — 005 C1b dropped the endpoint that would answer "who holds this
                here" — so the role is the clause a reader can repeat to somebody.
              */}
              <span>
                Configuring notifications needs <Identifier variant="muted">integration:manage</Identifier>
                , which you do not hold, so this project’s notification channels are not shown. A
                test lead or a project administrator can configure them.
              </span>
            </Banner>
          ) : (
            <DataRegion
              testId={`${TestId}.List`}
              state={{ ...channels, refetch: () => void channels.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'no-records',
                title: 'No channels yet',
                body: 'Add one so this project is told when a run finishes or work is assigned.',
              }}
            >
              {(rows) => (
                <Table data-testid={`${TestId}.Grid`}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Name</TableHeaderCell>
                      <TableHeaderCell className="w-[130px]">Transport</TableHeaderCell>
                      <TableHeaderCell className="w-[190px]">Where it goes</TableHeaderCell>
                      <TableHeaderCell>Told about</TableHeaderCell>
                      <TableHeaderCell className="w-[100px]">Delivery</TableHeaderCell>
                      <TableHeaderCell className="w-[160px]">Changed</TableHeaderCell>
                      <TableHeaderCell className="w-[220px]" align="right">
                        Actions
                      </TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((channel) => (
                      <ChannelRow
                        key={channel.id}
                        projectId={projectId}
                        channel={channel}
                        selected={channel.id === inspecting}
                        onEdit={() => setEditing(channel)}
                        onDelete={() => setDeleting(channel)}
                        onInspect={() =>
                          setInspecting((current) => (current === channel.id ? null : channel.id))
                        }
                      />
                    ))}
                  </TableBody>
                </Table>
              )}
            </DataRegion>
          )}
        </section>

        {allowed && selected && (
          <DeliveryHistoryPanel projectId={projectId} channel={selected} />
        )}
      </div>
    </div>
  );
}

/**
 * One channel.
 *
 * The row locator is a hardcoded const and carries no id — `test-ids` rule 6: a test addresses rows
 * by index over the shared selector, and a locator built from a uuid is unaddressable.
 *
 * **A paused channel stays on screen, dimmed** — rule 7, *disable, don't hide*. It is the same
 * treatment a revoked token gets on the tokens screen, and for the same reason: a row that
 * disappears when it stops working makes the question "why did we stop being told" unanswerable.
 */
function ChannelRow({
  projectId,
  channel,
  selected,
  onEdit,
  onDelete,
  onInspect,
}: {
  projectId: string;
  channel: NotificationChannel;
  selected: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onInspect: () => void;
}) {
  return (
    <TableRow
      data-testid={RowTestId}
      selected={selected}
      className={channel.enabled ? undefined : 'opacity-60'}
    >
      <TableCell variant="title" data-testid={`${RowTestId}.Name`}>
        {channel.name}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Kind`}>
        {notificationChannelKindLabels[channel.kind]}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Target`}>
        {channel.kind === 'EMAIL' ? (
          /*
            The addresses themselves, because for an email channel they ARE the destination and an
            administrator deciding whether this is the right channel is reading them. A count would
            answer a question nobody asked.
          */
          channel.recipients.join(', ') || (
            <span className="text-[11.5px] text-[color:var(--color-blocked)]">
              Nobody — this channel delivers to no one
            </span>
          )
        ) : channel.secretHint ? (
          /*
            The masked tail and nothing else, in the identifier font so tails align down the page.
            This is the value an administrator visually matches against the webhook they created,
            and it is the only part of a credential this product ever discloses.
          */
          <span title="The last characters of the configured webhook URL. The rest never leaves the server.">
            <Identifier variant="muted" testId={`${RowTestId}.SecretHint`}>
              …{channel.secretHint}
            </Identifier>
          </span>
        ) : (
          /*
            A positive statement rather than a blank. A webhook channel with no credential cannot
            deliver anything, which is the more consequential of the two answers and reads as a
            field somebody left empty if it is rendered as one.
          */
          <span className="text-[11.5px] text-[color:var(--color-blocked)]">No webhook set</span>
        )}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Subscriptions`}>
        {channel.subscriptions.length === 0 ? (
          <span className="text-[11.5px] text-ink-4">Nothing — this channel never fires</span>
        ) : (
          <span className="flex flex-wrap gap-1">
            {channel.subscriptions.map((subscription) => (
              <Badge key={subscription.id} tone="plain">
                {subscription.minFailures > 0
                  ? `${notificationEventTypeLabels[subscription.eventType]} · ${subscription.minFailures}+ failed`
                  : notificationEventTypeLabels[subscription.eventType]}
              </Badge>
            ))}
          </span>
        )}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Enabled`}>
        <Badge tone={channel.enabled ? 'passed' : 'plain'}>{channel.enabled ? 'On' : 'Paused'}</Badge>
      </TableCell>
      <TableCell className="text-[11.5px] text-ink-4" data-testid={`${RowTestId}.Updated`}>
        {formatDateTime(channel.updatedAt)}
      </TableCell>
      <TableCell align="right">
        <span className="flex justify-end gap-1.5">
          <Button size="sm" onClick={onInspect} data-testid={`${RowTestId}.History`}>
            {selected ? 'Hide history' : 'History'}
          </Button>
          {/*
            Both write actions carry the same key as the read that got them here, so a caller who can
            see this table can press them. They are gated anyway rather than assumed: the gate is
            what the server checks, and a row's affordance that infers its permission from the fact
            the page rendered is a second copy of the rule.
          */}
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed, reason }) => (
              <Button
                size="sm"
                disabled={!allowed}
                title={allowed ? undefined : reason}
                onClick={onEdit}
                data-testid={`${RowTestId}.Edit`}
              >
                Edit
              </Button>
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed, reason }) => (
              <Button
                size="sm"
                variant="danger"
                disabled={!allowed}
                title={allowed ? undefined : reason}
                onClick={onDelete}
                data-testid={`${RowTestId}.Delete`}
              >
                Delete
              </Button>
            )}
          </PermissionGate>
        </span>
      </TableCell>
    </TableRow>
  );
}

/**
 * What one channel has been sent, and what came back.
 *
 * Newest first, most recent 50, and `[]` means nothing has been sent through this channel *yet* —
 * which is not the same as a channel that has never worked, because a row exists as soon as an
 * attempt is made, including a first attempt that failed. The empty copy says which.
 */
function DeliveryHistoryPanel({
  projectId,
  channel,
}: {
  projectId: string;
  channel: NotificationChannel;
}) {
  const deliveries = useNotificationDeliveries(projectId, channel.id, true);

  return (
    <section data-testid={`${TestId}.History`}>
      <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
        Recent deliveries — {channel.name}
      </h2>
      <DataRegion
        testId={`${TestId}.History.Region`}
        state={{ ...deliveries, refetch: () => void deliveries.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'Nothing sent yet',
          body: 'A row appears here as soon as an attempt is made — including one that failed, so an empty log means no event has fired for this channel rather than that it does not work.',
        }}
      >
        {(rows) => (
          <>
            <FailingChannelBanner channel={channel} rows={rows} />
            <Table data-testid={`${TestId}.History.Grid`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell className="w-[170px]">Attempted</TableHeaderCell>
                  <TableHeaderCell className="w-[200px]">Event</TableHeaderCell>
                  <TableHeaderCell className="w-[110px]">Result</TableHeaderCell>
                  <TableHeaderCell className="w-[80px]" align="right">
                    Tries
                  </TableHeaderCell>
                  <TableHeaderCell>What came back</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((delivery) => (
                  <TableRow key={delivery.id} data-testid={`${TestId}.History.Row`}>
                    <TableCell className="text-[11.5px] text-ink-4">
                      {formatDateTime(delivery.updatedAt)}
                    </TableCell>
                    <TableCell data-testid={`${TestId}.History.Row.Event`}>
                      {/*
                        Through the fallback-carrying helper, not the subscription map. This column
                        is HISTORY: it may name an event this instance no longer sends, and a map
                        lookup that missed would render a blank cell for a row that really happened.
                      */}
                      <Identifier variant="muted">{notificationEventLabel(delivery.eventType)}</Identifier>
                    </TableCell>
                    <TableCell data-testid={`${TestId}.History.Row.Status`}>
                      <Badge tone={delivery.status === 'SENT' ? 'passed' : 'failed'}>
                        {notificationDeliveryStatusLabels[delivery.status]}
                      </Badge>
                    </TableCell>
                    <TableCell align="right" data-testid={`${TestId}.History.Row.Attempt`}>
                      {/*
                        Attempts against THIS channel for this occurrence — not the outbox row's
                        count, which covers the whole event across every subscribed channel. The
                        header word is "Tries" rather than "Attempt" because the number is a total.
                      */}
                      <Identifier>{delivery.attempt}</Identifier>
                    </TableCell>
                    <TableCell className="text-[11.5px] text-ink-4" data-testid={`${TestId}.History.Row.Error`}>
                      {delivery.error ?? '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </DataRegion>
    </section>
  );
}

/**
 * The finding, rather than the data that implies it.
 *
 * Three identical rejections in a table are three rows a reader has to notice, compare and add up.
 * A channel that fails quietly is worse than no channel, so the run of most recent failures is
 * stated as a sentence with the last thing the far end said in it. The threshold is **the newest
 * consecutive failures**, not a count anywhere in the window: a channel that failed twice last
 * month and works now is not broken, and a banner that said so would be the boy who cried wolf on
 * a screen whose whole job is to be believed.
 */
function FailingChannelBanner({
  channel,
  rows,
}: {
  channel: NotificationChannel;
  rows: NotificationDelivery[];
}) {
  const failing: NotificationDelivery[] = [];
  for (const row of rows) {
    if (row.status === 'SENT') break;
    failing.push(row);
  }
  if (failing.length < 3) return null;

  const last = failing[0];

  return (
    <Banner tone="bad" className="mb-3" data-testid={`${TestId}.History.Failing`}>
      <span>
        <b className="font-semibold text-ink">
          {channel.name} has failed its last {failing.length} deliveries.
        </b>{' '}
        Nothing has reached it since {formatDateTime(failing[failing.length - 1].updatedAt)}, and the
        far end last said <Identifier variant="muted">{last.error ?? 'nothing at all'}</Identifier>.
        Whatever this channel was told about, nobody has been told.
      </span>
    </Banner>
  );
}

/**
 * Confirming a delete, and naming what goes with it.
 *
 * A hard delete that takes the delivery history with it, which is the part a confirmation exists to
 * say out loud: the alternative — keeping the log of a channel that no longer exists — is a history
 * nothing can be replayed into and a row naming a credential nobody can rotate. The dialog says so
 * rather than asking "are you sure", which is a question with no information in it.
 */
function DeleteChannelDialog({
  projectId,
  channel,
  onClose,
  onDeleted,
}: {
  projectId: string;
  channel: NotificationChannel;
  onClose: () => void;
  onDeleted: (channelId: string) => void;
}) {
  const remove = useDeleteNotificationChannel(projectId);

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid={`${TestId}.Delete`}>
        <DialogHeader>
          <DialogTitle>Delete {channel.name}?</DialogTitle>
          <DialogDescription>
            This project stops sending anything to this channel, and its delivery history is deleted
            with it. Nothing else changes — the runs and tasks it reported on are untouched.
          </DialogDescription>
        </DialogHeader>

        {remove.isError && (
          <Banner tone="bad" className="mt-3" data-testid={`${TestId}.Delete.Error`}>
            {/* `describeError` is the one place an error becomes user-facing copy. */}
            <span role="alert">{describeError(remove.error).body}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TestId}.Delete.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            onClick={() => {
              remove.mutate(channel.id, { onSuccess: () => onDeleted(channel.id) });
            }}
            data-testid={`${TestId}.Delete.Confirm`}
          >
            {remove.isPending ? 'Deleting…' : 'Delete channel'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
