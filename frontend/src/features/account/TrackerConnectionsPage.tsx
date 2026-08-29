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
import { PageHeader } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Skeleton,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
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
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Tracker connections"
        documentTitle="Tracker connections · TCMS"
        subtitle="Your own credentials for the issue trackers your projects file into. Issues are read and filed as you, so what you can see here is what you can see there."
        actions={
          <Button variant="primary" onClick={() => setConnecting(true)} data-testid={`${TestId}.Connect`}>
            Connect a tracker
          </Button>
        }
      />

      <section data-testid={`${TestId}.Panel`}>
        <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
          Connected hosts
        </h2>
        <DataRegion
          testId={`${TestId}.List`}
          state={{ ...identities, refetch: () => void identities.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No connections yet',
            body: 'Connect the tracker your project is bound to, and issue links start showing their title and status.',
          }}
          skeleton={<Skeleton className="h-40 w-full" />}
        >
          {(rows) => (
            <div className="flex max-w-[820px] flex-col">
              {rows.map((identity) => (
                <IdentityRow
                  key={identity.id}
                  identity={identity}
                  onDisconnect={() => setDisconnecting(identity)}
                />
              ))}
            </div>
          )}
        </DataRegion>
      </section>

      {/* Conditionally mounted so each open starts on a fresh draft rather than the last one's. */}
      {connecting && <ConnectDialog onClose={() => setConnecting(false)} />}
      {disconnecting && (
        <DisconnectDialog identity={disconnecting} onClose={() => setDisconnecting(null)} />
      )}
    </div>
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
    <div
      data-testid={`${TestId}.Row`}
      className="flex items-start gap-4 border-b border-line py-4 last:border-b-0"
    >
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[13.5px] font-medium text-ink">
          {trackerProviderLabels[identity.provider]}
        </span>
        <span className="text-[12px] text-ink-4">
          <Identifier variant="muted">{identity.baseUrl}</Identifier>
          {identity.secretHint ? ` · token ending ${identity.secretHint}` : ''}
        </span>
        {identity.expiresAt && (
          <span className="text-[12px] text-ink-4" data-testid={`${TestId}.Expiry`}>
            Token expires {formatDate(identity.expiresAt)}
          </span>
        )}
        {identity.lastCheckedAt && (
          <span className="text-[12px] text-ink-4">
            Last checked {formatDateTime(identity.lastCheckedAt)}
          </span>
        )}
        {/*
          The stored error, and then this session's own check. Both are shown because they answer
          different questions — "what went wrong the last time anything used this" and "what
          happens if I try right now" — and a page that overwrote the first with the second would
          lose the reason somebody came here.
        */}
        {identity.lastError && (
          <Banner tone="warn" className="mt-2" data-testid={`${TestId}.LastError`}>
            <span>{identity.lastError}</span>
          </Banner>
        )}
        {checked && (
          <Banner
            tone={checked.ok ? 'steel' : 'bad'}
            className="mt-2"
            data-testid={`${TestId}.CheckResult`}
          >
            <span>{checked.ok ? 'The tracker answered. This connection works.' : checked.error}</span>
          </Banner>
        )}
        {test.isError && (
          <Banner tone="bad" className="mt-2" data-testid={`${TestId}.CheckFailed`}>
            <span role="alert">{describeError(test.error).body}</span>
          </Banner>
        )}
      </div>
      <div className="flex shrink-0 gap-2">
        <Button
          size="sm"
          disabled={test.isPending}
          onClick={() => test.mutate(identity.id)}
          data-testid={`${TestId}.Test`}
        >
          Test
        </Button>
        <Button size="sm" variant="danger" onClick={onDisconnect} data-testid={`${TestId}.Disconnect`}>
          Disconnect
        </Button>
      </div>
    </div>
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
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={`${TestId}.ConnectDialog`}>
        <DialogHeader>
          <DialogTitle>Connect a tracker</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          <Field label="Tracker" htmlFor="tracker-identity-provider">
            <NativeSelect
              id="tracker-identity-provider"
              value={provider}
              onChange={(e) => setProvider(e.target.value as TrackerProvider)}
              data-testid={`${TestId}.Provider`}
            >
              {(Object.keys(trackerProviderLabels) as TrackerProvider[]).map((key) => (
                <option key={key} value={key}>
                  {trackerProviderLabels[key]}
                </option>
              ))}
            </NativeSelect>
          </Field>

          <Field
            label="Site"
            htmlFor="tracker-identity-base-url"
            error={fieldErrors?.baseUrl?.join(' ')}
            help="The same host your project’s binding names. https only."
          >
            <Input
              id="tracker-identity-base-url"
              placeholder="https://acme.atlassian.net"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              data-testid={`${TestId}.BaseUrl`}
            />
          </Field>

          {NEEDS_ACCOUNT_ID[provider] && (
            <Field
              label="Your tracker email"
              htmlFor="tracker-identity-account"
              error={fieldErrors?.accountId?.join(' ')}
              help="Jira Cloud pairs the email with the token when it authenticates."
            >
              <Input
                id="tracker-identity-account"
                value={accountId}
                onChange={(e) => setAccountId(e.target.value)}
                data-testid={`${TestId}.AccountId`}
              />
            </Field>
          )}

          <Field
            label="API token"
            htmlFor="tracker-identity-secret"
            error={fieldErrors?.secret?.join(' ')}
            help="Stored encrypted and returned by nothing. Rotating is connecting again."
          >
            <Input
              id="tracker-identity-secret"
              type="password"
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              data-testid={`${TestId}.Secret`}
            />
          </Field>

          <Field
            label="Token expires"
            htmlFor="tracker-identity-expires"
            help="Optional — but Jira Cloud always shows one, and this page warns you before it lands."
          >
            <Input
              id="tracker-identity-expires"
              type="date"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
              data-testid={`${TestId}.ExpiresAt`}
            />
          </Field>

          {failure != null && !fieldErrors && (
            <Banner tone="bad" data-testid={`${TestId}.ConnectError`}>
              <span role="alert">{describeError(failure).body}</span>
            </Banner>
          )}
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.ConnectCancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={connect.isPending || baseUrl.trim() === '' || secret === ''}
            onClick={submit}
            data-testid={`${TestId}.ConnectConfirm`}
          >
            Connect
          </Button>
        </DialogFooter>
      </DialogContent>
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
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid={`${TestId}.DisconnectDialog`}>
        <DialogHeader>
          <DialogTitle>Disconnect {trackerProviderLabels[identity.provider]}?</DialogTitle>
          <DialogDescription>
            Issue links stay where they are and keep working — they stop showing their title and
            status until you connect again. Nothing in the tracker changes.
          </DialogDescription>
        </DialogHeader>

        {disconnect.error != null && (
          <Banner tone="bad" className="mt-3" data-testid={`${TestId}.DisconnectError`}>
            <span role="alert">{describeError(disconnect.error).body}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.DisconnectCancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={disconnect.isPending}
            onClick={() => disconnect.mutate(identity.id, { onSuccess: onClose })}
            data-testid={`${TestId}.DisconnectConfirm`}
          >
            Disconnect
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
