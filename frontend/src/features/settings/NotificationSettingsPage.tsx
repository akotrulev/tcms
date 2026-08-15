import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  useDeleteNotificationChannel,
  useNotificationChannels,
  useNotificationDeliveries,
  useProject,
} from '../../api/queries';
import type { NotificationChannel } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
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
 *   - **The page renders, and says who can.** The panel is replaced by a sentence naming the
 *     missing capability and who holds it, rather than by an empty table or an error region. A
 *     table with no rows would say this project has no channels, which is a different fact and one
 *     this caller is not entitled to.
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
 * ## The history hangs off a channel, so the panel is about the selected one
 *
 * There is no project-level delivery log and the endpoint shape says so. A delivery is only ever
 * asked about as one — *is this webhook working* — and deleting a channel takes its history with
 * it, so a project-wide list would outlive the channels it names. Selecting a row is therefore the
 * only way into the log, and nothing is fetched until one is selected.
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
    <Box>
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
            is still rendered for a caller who may not press it, beside the sentence below that says
            why.
          */
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed: mayManage, reason }) => (
              <Tooltip title={mayManage ? '' : reason}>
                {/* A disabled button fires no events, so the tooltip needs a wrapper that does. */}
                <Box component="span">
                  <Button
                    variant="contained"
                    disabled={!mayManage}
                    onClick={() => setAdding(true)}
                    data-testid={`${TestId}.Add`}
                  >
                    Add a channel
                  </Button>
                </Box>
              </Tooltip>
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

      <Stack sx={{ gap: 3 }}>
        <Panel title="Channels" testId={`${TestId}.Panel`} dense>
          {gate.pending ? null : !allowed ? (
            <Alert severity="info" data-testid={`${TestId}.Denied`}>
              {/*
                A sentence, not an empty table and not an error. The list is refused rather than
                narrowed — a channel carries the masked tail of a live credential and a delivery
                carries whatever a remote endpoint said — so "no channels" is a fact this caller is
                not entitled to, and rendering it as one would be a confident falsehood.
              */}
              You do not have permission to manage integrations in this project, so this project’s
              notification channels are not shown. A test lead or a project administrator can
              configure them.
            </Alert>
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
                <ScrollX>
                  <Table size="small" data-testid={`${TestId}.Grid`}>
                    <TableHead>
                      <TableRow>
                        <TableCell sx={{ color: 'text.secondary' }}>Name</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Transport</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 190 }}>Where it goes</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>Told about</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }}>Delivery</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 170 }}>Changed</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 210 }} align="right">
                          Actions
                        </TableCell>
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
                </ScrollX>
              )}
            </DataRegion>
          )}
        </Panel>

        {allowed && selected && (
          <DeliveryHistoryPanel projectId={projectId} channel={selected} />
        )}
      </Stack>
    </Box>
  );
}

/**
 * One channel.
 *
 * The row locator is a hardcoded const and carries no id — `test-ids` rule 6: a test addresses rows
 * by index over the shared selector, and a locator built from a uuid is unaddressable.
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
    <TableRow data-testid={RowTestId} hover selected={selected} sx={{ opacity: channel.enabled ? 1 : 0.6 }}>
      <TableCell sx={{ fontWeight: 500 }} data-testid={`${RowTestId}.Name`}>
        {channel.name}
      </TableCell>
      <TableCell sx={{ fontSize: '0.8125rem' }} data-testid={`${RowTestId}.Kind`}>
        {notificationChannelKindLabels[channel.kind]}
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem' }} data-testid={`${RowTestId}.Target`}>
        {channel.kind === 'EMAIL' ? (
          /*
            The addresses themselves, because for an email channel they ARE the destination and an
            administrator deciding whether this is the right channel is reading them. A count would
            answer a question nobody asked.
          */
          channel.recipients.join(', ') || (
            <Typography component="span" variant="caption" sx={{ color: 'warning.main' }}>
              Nobody — this channel delivers to no one
            </Typography>
          )
        ) : channel.secretHint ? (
          /*
            The masked tail and nothing else, in the identifier font so tails align down the page.
            This is the value an administrator visually matches against the webhook they created,
            and it is the only part of a credential this product ever discloses.
          */
          <Tooltip title="The last characters of the configured webhook URL. The rest never leaves the server.">
            <Box component="span">
              <Identifier variant="muted" testId={`${RowTestId}.SecretHint`}>
                …{channel.secretHint}
              </Identifier>
            </Box>
          </Tooltip>
        ) : (
          /*
            A positive statement rather than a blank. A webhook channel with no credential cannot
            deliver anything, which is the more consequential of the two answers and reads as a
            field somebody left empty if it is rendered as one.
          */
          <Typography component="span" variant="caption" sx={{ color: 'warning.main' }}>
            No webhook set
          </Typography>
        )}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Subscriptions`}>
        {channel.subscriptions.length === 0 ? (
          <Typography component="span" variant="caption" sx={{ color: 'text.secondary' }}>
            Nothing — this channel never fires
          </Typography>
        ) : (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
            {channel.subscriptions.map((subscription) => (
              <Chip
                key={subscription.id}
                size="small"
                variant="outlined"
                label={
                  subscription.minFailures > 0
                    ? `${notificationEventTypeLabels[subscription.eventType]} · ${subscription.minFailures}+ failed`
                    : notificationEventTypeLabels[subscription.eventType]
                }
              />
            ))}
          </Stack>
        )}
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Enabled`}>
        <Typography component="span" variant="caption">
          {channel.enabled ? 'On' : 'Paused'}
        </Typography>
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }} data-testid={`${RowTestId}.Updated`}>
        {formatDateTime(channel.updatedAt)}
      </TableCell>
      <TableCell align="right">
        <Stack direction="row" sx={{ gap: 1, justifyContent: 'flex-end' }}>
          <Button size="small" onClick={onInspect} data-testid={`${RowTestId}.History`}>
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
              <Tooltip title={allowed ? '' : reason}>
                <Box component="span">
                  <Button
                    size="small"
                    disabled={!allowed}
                    onClick={onEdit}
                    data-testid={`${RowTestId}.Edit`}
                  >
                    Edit
                  </Button>
                </Box>
              </Tooltip>
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed, reason }) => (
              <Tooltip title={allowed ? '' : reason}>
                <Box component="span">
                  <Button
                    size="small"
                    color="error"
                    disabled={!allowed}
                    onClick={onDelete}
                    data-testid={`${RowTestId}.Delete`}
                  >
                    Delete
                  </Button>
                </Box>
              </Tooltip>
            )}
          </PermissionGate>
        </Stack>
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
    <Panel title={`Delivery history — ${channel.name}`} testId={`${TestId}.History`} dense>
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
          <ScrollX>
            <Table size="small" data-testid={`${TestId}.History.Grid`}>
              <TableHead>
                <TableRow>
                  <TableCell sx={{ color: 'text.secondary', width: 170 }}>Attempted</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 200 }}>Event</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 100 }}>Result</TableCell>
                  <TableCell sx={{ color: 'text.secondary', width: 80 }}>Tries</TableCell>
                  <TableCell sx={{ color: 'text.secondary' }}>What came back</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((delivery) => (
                  <TableRow key={delivery.id} data-testid={`${TestId}.History.Row`}>
                    <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                      {formatDateTime(delivery.updatedAt)}
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.8125rem' }} data-testid={`${TestId}.History.Row.Event`}>
                      {/*
                        Through the fallback-carrying helper, not the subscription map. This column
                        is HISTORY: it may name an event this instance no longer sends, and a map
                        lookup that missed would render a blank cell for a row that really happened.
                      */}
                      {notificationEventLabel(delivery.eventType)}
                    </TableCell>
                    <TableCell data-testid={`${TestId}.History.Row.Status`}>
                      <Chip
                        size="small"
                        variant="outlined"
                        color={delivery.status === 'SENT' ? 'success' : 'error'}
                        label={notificationDeliveryStatusLabels[delivery.status]}
                      />
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.75rem' }} data-testid={`${TestId}.History.Row.Attempt`}>
                      {/*
                        Attempts against THIS channel for this occurrence — not the outbox row's
                        count, which covers the whole event across every subscribed channel. The
                        header word is "Tries" rather than "Attempt" because the number is a total.
                      */}
                      {delivery.attempt}
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }} data-testid={`${TestId}.History.Row.Error`}>
                      {delivery.error ?? (
                        <Typography component="span" variant="caption">
                          —
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ScrollX>
        )}
      </DataRegion>
    </Panel>
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
    <Dialog open onClose={onClose} fullWidth maxWidth="xs" data-testid={`${TestId}.Delete`}>
      <DialogTitle>Delete {channel.name}?</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2 }}>
          <DialogContentText>
            This project stops sending anything to this channel, and its delivery history is deleted
            with it. Nothing else changes — the runs and tasks it reported on are untouched.
          </DialogContentText>
          {remove.isError && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.Delete.Error`}>
              {/* `describeError` is the one place an error becomes user-facing copy. */}
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.Delete.Cancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          onClick={() => {
            remove.mutate(channel.id, { onSuccess: () => onDeleted(channel.id) });
          }}
          data-testid={`${TestId}.Delete.Confirm`}
        >
          {remove.isPending ? 'Deleting…' : 'Delete channel'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
