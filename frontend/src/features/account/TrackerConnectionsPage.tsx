import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import {
  useConnectTracker,
  useDisconnectTracker,
  useMyTrackerIdentities,
  useTestTrackerIdentity,
} from '../../api/queries';
import { ApiError } from '../../api/client';
import type { TrackerIdentity, TrackerIdentityInput, TrackerProvider } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { describeError } from '../../lib/describeError';
import { formatDate, formatDateTime } from '../../lib/format';
import { trackerProviderLabels } from '../../lib/labels';

/**
 * The caller's own connections to the issue trackers their projects are bound to (004 D2, Phase 1).
 *
 * ## Why this is an account screen and not a project settings tab
 *
 * A **binding** — which Jira site, which project key — belongs to the project and lives on its
 * settings page. A **credential** belongs to a person: it is theirs, it is used to act as them, and
 * every issue anybody previews is fetched with the *viewer's* own. Putting it on the project
 * settings page would teach people it is a project-level thing, which is the misreading D2's whole
 * inversion exists to prevent — and it would put a personal secret behind a permission somebody
 * else holds.
 *
 * Nothing here is gated on a permission. It is gated on **being the caller**, and there is no
 * request this API accepts for somebody else's identity.
 *
 * ## The expiry is on the first screen, not in a drawer
 *
 * Jira Cloud enforces a one-year maximum on an API token, so **every Jira Cloud row has a death
 * date by construction** — a connection that works today and stops on a date nobody has looked at.
 * That is why the column is here beside the host rather than behind a click.
 *
 * ## `lastError` is shown here and nowhere else
 *
 * It is what the tracker said about *this person's* credential, and the D19 members list on the
 * project settings page is forbidden from carrying it — a colleague may learn that somebody
 * `Needs attention` and not why. The sentence belongs on the affected person's own page, which is
 * this one.
 */
const TestId = 'TrackerConnections';

export function TrackerConnectionsPage() {
  const identities = useMyTrackerIdentities();
  const [connecting, setConnecting] = useState(false);
  const [disconnecting, setDisconnecting] = useState<TrackerIdentity | null>(null);

  return (
    <Box>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Tracker connections"
        documentTitle="Tracker connections · TCMS"
        subtitle="Your own credentials for the issue trackers your projects file into. Issues are read and filed as you, so what you can see here is what you can see there."
        actions={
          <Button variant="contained" onClick={() => setConnecting(true)} data-testid={`${TestId}.Connect`}>
            Connect a tracker
          </Button>
        }
      />

      <Panel title="Connected hosts" testId={`${TestId}.Panel`} dense>
        <DataRegion
          testId={`${TestId}.List`}
          state={{ ...identities, refetch: () => void identities.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No connections yet',
            body: 'Connect the tracker your project is bound to, and issue links start showing their title and status.',
          }}
          skeleton={<Skeleton variant="rounded" height={160} />}
        >
          {(rows) => (
            <Stack sx={{ gap: 1.5, p: 1.5 }}>
              {rows.map((identity) => (
                <IdentityRow
                  key={identity.id}
                  identity={identity}
                  onDisconnect={() => setDisconnecting(identity)}
                />
              ))}
            </Stack>
          )}
        </DataRegion>
      </Panel>

      {/* Conditionally mounted so each open starts on a fresh draft rather than the last one's. */}
      {connecting && <ConnectDialog onClose={() => setConnecting(false)} />}
      {disconnecting && (
        <DisconnectDialog identity={disconnecting} onClose={() => setDisconnecting(null)} />
      )}
    </Box>
  );
}

/**
 * One host.
 *
 * The **test** is a mutation rather than a read, and its answer is rendered from the mutation's own
 * result: pressing it is the point, and a cached "ok" from a minute ago is the one answer that
 * cannot tell somebody whether the token they just rotated works.
 */
function IdentityRow({
  identity,
  onDisconnect,
}: {
  identity: TrackerIdentity;
  onDisconnect: () => void;
}) {
  const test = useTestTrackerIdentity();
  const checked = test.data;

  return (
    <Box
      data-testid={`${TestId}.Row`}
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 2,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        px: 2,
        py: 1.5,
      }}
    >
      <Stack sx={{ flex: 1, gap: 0.25 }}>
        <Typography variant="body1" sx={{ fontWeight: 600 }}>
          {trackerProviderLabels[identity.provider]}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          <Identifier variant="muted">{identity.baseUrl}</Identifier>
          {identity.secretHint ? ` · token ending ${identity.secretHint}` : ''}
        </Typography>
        {identity.expiresAt && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid={`${TestId}.Expiry`}>
            Token expires {formatDate(identity.expiresAt)}
          </Typography>
        )}
        {identity.lastCheckedAt && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Last checked {formatDateTime(identity.lastCheckedAt)}
          </Typography>
        )}
        {/*
          The stored error, and then this session's own check. Both are shown because they answer
          different questions — "what went wrong the last time anything used this" and "what
          happens if I try right now" — and a page that overwrote the first with the second would
          lose the reason somebody came here.
        */}
        {identity.lastError && (
          <Alert severity="warning" sx={{ mt: 1 }} data-testid={`${TestId}.LastError`}>
            {identity.lastError}
          </Alert>
        )}
        {checked && (
          <Alert
            severity={checked.ok ? 'success' : 'error'}
            sx={{ mt: 1 }}
            data-testid={`${TestId}.CheckResult`}
          >
            {checked.ok ? 'The tracker answered. This connection works.' : checked.error}
          </Alert>
        )}
        {test.isError && (
          <Alert severity="error" sx={{ mt: 1 }} data-testid={`${TestId}.CheckFailed`}>
            {describeError(test.error).body}
          </Alert>
        )}
      </Stack>
      <Stack direction="row" sx={{ gap: 1 }}>
        <Button
          size="small"
          variant="outlined"
          disabled={test.isPending}
          onClick={() => test.mutate(identity.id)}
          data-testid={`${TestId}.Test`}
        >
          Test
        </Button>
        <Button
          size="small"
          variant="outlined"
          color="error"
          onClick={onDisconnect}
          data-testid={`${TestId}.Disconnect`}
        >
          Disconnect
        </Button>
      </Stack>
    </Box>
  );
}

/** Which providers pair an account handle with the token. Jira Cloud's Basic auth needs the email. */
const NEEDS_ACCOUNT_ID: Record<TrackerProvider, boolean> = {
  JIRA_CLOUD: true,
  JIRA_DC: false,
  AZURE_DEVOPS: false,
};

/**
 * Connecting, or rotating a credential for a host already connected.
 *
 * **Every field is sent, including the secret, and that is the difference from
 * `NotificationChannelDialog`.** There the secret is absent-means-unchanged, because that form
 * edits a channel somebody may only be renaming; here a `POST` always sets one and rotation *is* a
 * second `POST` for the same host. The two shapes look alike and mean opposite things, which is
 * why this says so rather than leaving the next reader to infer it from the contract.
 */
function ConnectDialog({ onClose }: { onClose: () => void }) {
  const connect = useConnectTracker();
  const [provider, setProvider] = useState<TrackerProvider>('JIRA_CLOUD');
  const [baseUrl, setBaseUrl] = useState('');
  const [accountId, setAccountId] = useState('');
  const [secret, setSecret] = useState('');
  const [expiresAt, setExpiresAt] = useState('');

  const failure = connect.error;
  // Read off `ApiError` rather than duck-typed off `unknown`: the server keys its refusals on
  // `errors.baseUrl` / `errors.secret` / `errors.accountId`, and a field error rendered as a
  // dialog-wide sentence is a message the reader has to map back onto an input themselves.
  const fieldErrors = failure instanceof ApiError ? failure.fieldErrors : null;

  const submit = () => {
    const input: TrackerIdentityInput = {
      provider,
      baseUrl: baseUrl.trim(),
      authMethod: 'PAT',
      secret,
      // Null rather than an empty string for a provider that does not pair one: the contract's
      // nullable means "this provider has none", and `''` would be an account handle of no
      // characters, which is a different claim and one the adapter would try to authenticate with.
      accountId: NEEDS_ACCOUNT_ID[provider] ? accountId.trim() : null,
      expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
    };
    connect.mutate(input, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid={`${TestId}.ConnectDialog`}>
      <DialogTitle>Connect a tracker</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, mt: 1 }}>
          <TextField
            select
            label="Tracker"
            value={provider}
            onChange={(e) => setProvider(e.target.value as TrackerProvider)}
            data-testid={`${TestId}.Provider`}
          >
            {(Object.keys(trackerProviderLabels) as TrackerProvider[]).map((key) => (
              <MenuItem key={key} value={key}>
                {trackerProviderLabels[key]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Site"
            placeholder="https://acme.atlassian.net"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            error={!!fieldErrors?.baseUrl}
            helperText={
              fieldErrors?.baseUrl?.join(' ')
              ?? 'The same host your project’s binding names. https only.'
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.BaseUrl` } }}
          />
          {NEEDS_ACCOUNT_ID[provider] && (
            <TextField
              label="Your tracker email"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
              error={!!fieldErrors?.accountId}
              helperText={
                fieldErrors?.accountId?.join(' ')
                ?? 'Jira Cloud pairs the email with the token when it authenticates.'
              }
              data-testid={`${TestId}.AccountId`}
            />
          )}
          <TextField
            label="API token"
            type="password"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            error={!!fieldErrors?.secret}
            helperText={
              fieldErrors?.secret?.join(' ')
              ?? 'Stored encrypted and returned by nothing. Rotating is connecting again.'
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.Secret` } }}
          />
          <TextField
            label="Token expires"
            type="date"
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
            helperText="Optional — but Jira Cloud always shows one, and this page warns you before it lands."
            slotProps={{
              // A date input always shows its placeholder, so the label has to stay shrunk or it
              // sits on top of `dd/mm/yyyy`.
              inputLabel: { shrink: true },
              htmlInput: { 'data-testid': `${TestId}.ExpiresAt` },
            }}
          />
          {failure != null && !fieldErrors && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.ConnectError`}>
              {describeError(failure).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.ConnectCancel`}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={connect.isPending || baseUrl.trim() === '' || secret === ''}
          onClick={submit}
          data-testid={`${TestId}.ConnectConfirm`}
        >
          Connect
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Disconnecting.
 *
 * The consequence is named because it is not obvious and it is not destructive: **no link is
 * deleted and no issue is touched.** What stops is previewing — every chip this credential was
 * resolving falls back to `<provider> issue <id>`, keeping its href.
 */
function DisconnectDialog({
  identity,
  onClose,
}: {
  identity: TrackerIdentity;
  onClose: () => void;
}) {
  const disconnect = useDisconnectTracker();
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs" data-testid={`${TestId}.DisconnectDialog`}>
      <DialogTitle>Disconnect {trackerProviderLabels[identity.provider]}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2">
            Issue links stay where they are and keep working — they stop showing their title and
            status until you connect again. Nothing in the tracker changes.
          </Typography>
          {disconnect.error != null && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.DisconnectError`}>
              {describeError(disconnect.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.DisconnectCancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={disconnect.isPending}
          onClick={() => disconnect.mutate(identity.id, { onSuccess: onClose })}
          data-testid={`${TestId}.DisconnectConfirm`}
        >
          Disconnect
        </Button>
      </DialogActions>
    </Dialog>
  );
}
