import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  useCreateTrackerBinding,
  useDeleteTrackerBinding,
  useProject,
  useTrackerBindings,
  useTrackerConnectedMembers,
  useUpdateTrackerBinding,
} from '../../api/queries';
import { ApiError } from '../../api/client';
import type {
  TrackerBinding,
  TrackerBindingInput,
  TrackerConnectionState,
  TrackerProvider,
} from '../../api/types';
import { useCan } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { SettingsSubnav } from './SettingsSubnav';
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
  type BadgeTone,
} from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Switch,
  Textarea,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { trackerConnectionStateLabels, trackerProviderLabels } from '../../lib/labels';

/**
 * Where this project files defects (004 Phase 1) — its **bindings**, and who on the team has
 * connected to the hosts they name.
 *
 * ## Two lists about two different things, deliberately on one screen
 *
 * A binding is the project's: non-secret, `integration:manage` to read or write, an ordinary form.
 * A connection is a person's, and lives on their own account page. The reason they share a screen
 * anyway is the one operational question a lead actually arrives with — *why can nobody on this
 * team file defects?* — whose answer is either "there is no binding" or "nobody has connected", and
 * splitting the two across two screens makes that a question you have to already know how to ask.
 *
 * ## What the members list may show, and why the restriction is the decision rather than the shape
 *
 * Each row is **a person and a state per bound host, and nothing else**. Not the credential, not
 * its `secretHint`, not the person's tracker email or account handle, not the scopes, not the
 * expiry, and not the text of `last_error`. `Needs attention` is a state; the sentence explaining
 * it belongs on the affected person's own account page, where the credential is theirs to read
 * about.
 *
 * It is also **scoped to this project's bound hosts**, so a lead learns nothing about a colleague's
 * connections to a tracker this project is not bound to. That scoping is the mitigation the privacy
 * counter-argument bought; it is not a side effect of the query being convenient.
 *
 * ## The reads are gated, so this is `NotificationSettingsPage`'s shape
 *
 * `integration:manage` gates the reads as well as the writes, so a Test Engineer opening this gets
 * no read-only view — they get no data. The rail entry is un-listed for them (005 C2), the page
 * still renders if they arrive by a pasted link, and it says who can configure it rather than
 * showing an empty table that would claim this project has no bindings.
 */
const TestId = 'TrackerSettings';

/**
 * The three connection states, as badge tones.
 *
 * `lib/labels.ts`' `trackerConnectionStateColors` beside the words is the MUI palette's spelling of
 * this ('success' / 'warning' / 'default') and has exactly one consumer — this line. It is left
 * where it is rather than rewritten in place: the labels file is shared with every other lane of
 * 007, and a tone map there would have to import a type from `components/`, which is the wrong
 * direction. Collapse the two when MUI goes.
 */
const STATE_TONE = {
  CONNECTED: 'passed',
  NEEDS_ATTENTION: 'blocked',
  NOT_CONNECTED: 'plain',
} as const satisfies Record<TrackerConnectionState, BadgeTone>;

export function TrackerSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);

  const gate = useCan(projectId);
  // Fail-closed while pending is right for a control and wrong for a fetch, so the verdict is read
  // twice: this drives the reads, `gate.pending` below keeps the refusal off screen until it is real.
  const allowed = !gate.pending && gate.can('integration:manage');

  const bindings = useTrackerBindings(projectId, allowed);
  const members = useTrackerConnectedMembers(projectId, allowed);

  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<TrackerBinding | null>(null);

  return (
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Issue tracker"
        documentTitle={project.data ? `Issue tracker · ${project.data.name}` : 'Issue tracker'}
        subtitle="Which tracker this project links and files into. Each person connects their own account — nothing here holds a credential."
        actions={
          <PermissionGate projectId={projectId} permission="integration:manage">
            {({ allowed: mayManage, reason }) => (
              <Button
                variant="primary"
                disabled={!mayManage}
                title={mayManage ? undefined : reason}
                onClick={() => setAdding(true)}
                data-testid={`${TestId}.Add`}
              >
                Add a binding
              </Button>
            )}
          </PermissionGate>
        }
      />

      <SettingsSubnav projectId={projectId} />

      {adding && <BindingDialog projectId={projectId} onClose={() => setAdding(false)} />}
      {deleting && (
        <DeleteBindingDialog
          projectId={projectId}
          binding={deleting}
          onClose={() => setDeleting(null)}
        />
      )}

      <div className="flex flex-col gap-7">
        <section data-testid={`${TestId}.BindingsPanel`}>
          <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
            Bindings
          </h2>
          {gate.pending ? null : !allowed ? (
            <Banner tone="neutral" data-testid={`${TestId}.Denied`}>
              {/* The permission and the role that holds it — a person cannot be named on this
                  codebase (005 C1b dropped the endpoint that would answer it). */}
              <span>
                Binding a tracker needs <Identifier variant="muted">integration:manage</Identifier>,
                which you do not hold, so this project’s tracker bindings are not shown. A test lead
                or a project administrator can configure them.
              </span>
            </Banner>
          ) : (
            <DataRegion
              testId={`${TestId}.Bindings`}
              state={{ ...bindings, refetch: () => void bindings.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'no-records',
                title: 'No tracker bound',
                body: 'Bind a Jira or Azure DevOps project, and this project can link and file issues into it.',
              }}
            >
              {(rows) => (
                <Table data-testid={`${TestId}.BindingsTable`}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Tracker</TableHeaderCell>
                      <TableHeaderCell>Site</TableHeaderCell>
                      <TableHeaderCell>Project</TableHeaderCell>
                      <TableHeaderCell className="w-[90px]">Default</TableHeaderCell>
                      <TableHeaderCell className="w-[90px]">Enabled</TableHeaderCell>
                      <TableHeaderCell className="w-[110px]" align="right" />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((binding) => (
                      <BindingRow
                        key={binding.id}
                        projectId={projectId}
                        binding={binding}
                        onDelete={() => setDeleting(binding)}
                      />
                    ))}
                  </TableBody>
                </Table>
              )}
            </DataRegion>
          )}
        </section>

        <section data-testid={`${TestId}.MembersPanel`}>
          <h2 className="mb-1.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
            Who has connected
          </h2>
          {gate.pending || !allowed ? null : (
            <DataRegion
              testId={`${TestId}.Members`}
              state={{ ...members, refetch: () => void members.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'by-design',
                title: 'Nobody to list',
                body: 'This list appears once the project has a binding and members who could use it.',
              }}
            >
              {(rows) => (
                <>
                  <p className="mb-2.5 max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
                    One row per project member, and one state per host this project’s bindings name.
                    Whether somebody has connected is all this can say — the credential, and why a
                    connection needs attention, are theirs.
                  </p>
                  <Table data-testid={`${TestId}.MembersTable`}>
                    <TableHead>
                      <TableRow>
                        <TableHeaderCell>Member</TableHeaderCell>
                        <TableHeaderCell>Host</TableHeaderCell>
                        <TableHeaderCell className="w-[150px]">State</TableHeaderCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.flatMap((member) =>
                        member.hosts.map((host) => (
                          <TableRow
                            key={`${member.user.id}:${host.baseUrl}`}
                            data-testid={`${TestId}.MemberRow`}
                          >
                            <TableCell variant="title">{member.user.displayName}</TableCell>
                            <TableCell>
                              <Identifier variant="muted">{host.baseUrl}</Identifier>
                            </TableCell>
                            <TableCell>
                              <Badge tone={STATE_TONE[host.state]}>
                                {trackerConnectionStateLabels[host.state]}
                              </Badge>
                            </TableCell>
                          </TableRow>
                        )),
                      )}
                    </TableBody>
                  </Table>
                </>
              )}
            </DataRegion>
          )}
        </section>
      </div>
    </div>
  );
}

/**
 * One binding.
 *
 * The two switches write through the `PATCH` immediately rather than through a form, because each
 * is a single fact with an immediate consequence: `isDefault` moves where filing goes when no
 * binding is named, and `enabled` takes a binding out of search, preview and filing without
 * deleting anything anybody linked. `If-Match` carries `lockVersion` on both.
 *
 * ~~The testid belongs on the INPUT slot: on a Switch's root it lands on the wrapper span, where
 * checked is not readable.~~ → **superseded (007 T15).** There is no wrapper any more. The switch
 * is one element with `role="switch"` and `aria-checked`, so the id sits on the control itself and
 * `toBeChecked` reads the state off it.
 */
function BindingRow({
  projectId,
  binding,
  onDelete,
}: {
  projectId: string;
  binding: TrackerBinding;
  onDelete: () => void;
}) {
  const update = useUpdateTrackerBinding(projectId);

  return (
    <TableRow data-testid={`${TestId}.BindingRow`} className={binding.enabled ? undefined : 'opacity-60'}>
      <TableCell variant="title">{trackerProviderLabels[binding.provider]}</TableCell>
      <TableCell>
        <Identifier variant="muted">{binding.baseUrl}</Identifier>
      </TableCell>
      <TableCell>
        <Identifier>{binding.externalProject}</Identifier>
      </TableCell>
      <TableCell>
        <Switch
          checked={binding.isDefault}
          disabled={update.isPending}
          aria-label="Default binding"
          onCheckedChange={(checked) =>
            update.mutate({
              bindingId: binding.id,
              lockVersion: binding.lockVersion,
              patch: { isDefault: checked },
            })
          }
          data-testid={`${TestId}.Default`}
        />
      </TableCell>
      <TableCell>
        <Switch
          checked={binding.enabled}
          disabled={update.isPending}
          aria-label="Enabled"
          onCheckedChange={(checked) =>
            update.mutate({
              bindingId: binding.id,
              lockVersion: binding.lockVersion,
              patch: { enabled: checked },
            })
          }
          data-testid={`${TestId}.Enabled`}
        />
      </TableCell>
      <TableCell align="right">
        <Button size="sm" variant="danger" onClick={onDelete} data-testid={`${TestId}.Delete`}>
          Remove
        </Button>
      </TableCell>
    </TableRow>
  );
}

/**
 * Adding a binding.
 *
 * `config` is free-form here because the keys belong to the adapter, and each adapter validates its
 * own on the write and refuses with a `400` keyed on `errors.config` — so this form takes JSON text
 * and lands the server's refusal on the field rather than inventing a per-provider form the client
 * would then own a second copy of.
 */
function BindingDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  const create = useCreateTrackerBinding(projectId);
  const [provider, setProvider] = useState<TrackerProvider>('JIRA_CLOUD');
  const [baseUrl, setBaseUrl] = useState('');
  const [externalProject, setExternalProject] = useState('');
  const [config, setConfig] = useState('{}');
  const [isDefault, setIsDefault] = useState(true);
  const [configError, setConfigError] = useState<string | null>(null);

  const fieldErrors = create.error instanceof ApiError ? create.error.fieldErrors : null;

  const submit = () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(config || '{}') as Record<string, unknown>;
    } catch {
      // Refused here rather than sent: a 400 for unparseable JSON would come back as a message
      // about the request body, which is a sentence about the API and not about this field.
      setConfigError('This is not valid JSON.');
      return;
    }
    setConfigError(null);
    const input: TrackerBindingInput = {
      provider,
      baseUrl: baseUrl.trim(),
      externalProject: externalProject.trim(),
      config: parsed,
      isDefault,
      // Required rather than defaulted, like every switch this API's writers take — a dropped
      // boolean must not silently disable anything.
      enabled: true,
    };
    create.mutate(input, { onSuccess: onClose });
  };

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={`${TestId}.Dialog`}>
        <DialogHeader>
          <DialogTitle>Add a binding</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          <Field label="Tracker" htmlFor="tracker-provider">
            <NativeSelect
              id="tracker-provider"
              value={provider}
              onChange={(e) => setProvider(e.target.value as TrackerProvider)}
              data-testid={`${TestId}.DialogProvider`}
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
            htmlFor="tracker-base-url"
            error={fieldErrors?.baseUrl?.join(' ')}
            help="https only, and it must be reachable from this instance."
          >
            <Input
              id="tracker-base-url"
              placeholder="https://acme.atlassian.net"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              data-testid={`${TestId}.DialogBaseUrl`}
            />
          </Field>

          <Field
            label="Tracker project"
            htmlFor="tracker-external-project"
            error={fieldErrors?.externalProject?.join(' ')}
            help="The Jira project key, or Azure DevOps’ organisation/team project."
          >
            <Input
              id="tracker-external-project"
              placeholder="PAY, or contoso/Payments"
              value={externalProject}
              onChange={(e) => setExternalProject(e.target.value)}
              data-testid={`${TestId}.DialogProject`}
            />
          </Field>

          <Field
            label="Adapter settings"
            htmlFor="tracker-config"
            error={configError ?? fieldErrors?.config?.join(' ')}
            help="JSON the adapter reads — issue type, area path, the search scope. {} applies its defaults."
          >
            <Textarea
              id="tracker-config"
              rows={2}
              className="font-mono"
              value={config}
              onChange={(e) => setConfig(e.target.value)}
              data-testid={`${TestId}.DialogConfig`}
            />
          </Field>

          <div className="flex items-start gap-2.5">
            <Switch
              checked={isDefault}
              onCheckedChange={setIsDefault}
              aria-label="Default binding"
              data-testid={`${TestId}.DialogDefault`}
            />
            <p className="text-[12.5px] leading-[1.5] text-ink-3">
              File here when nothing names a binding. The first binding becomes the default
              regardless.
            </p>
          </div>

          {create.error != null && !fieldErrors && (
            <div data-testid={`${TestId}.DialogError`}>
              <Banner tone="bad">
                <span role="alert">{describeError(create.error).body}</span>
              </Banner>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.DialogCancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={create.isPending || baseUrl.trim() === '' || externalProject.trim() === ''}
            onClick={submit}
            data-testid={`${TestId}.DialogSave`}
          >
            Add
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Removing a binding.
 *
 * The consequence is named because the obvious guess is wrong: **nothing cascades** (004 D18). The
 * case's links keep their href and stop previewing — no row is deleted here or in the tracker.
 */
function DeleteBindingDialog({
  projectId,
  binding,
  onClose,
}: {
  projectId: string;
  binding: TrackerBinding;
  onClose: () => void;
}) {
  const remove = useDeleteTrackerBinding(projectId);

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid={`${TestId}.DeleteDialog`}>
        <DialogHeader>
          <DialogTitle>Remove this binding?</DialogTitle>
        </DialogHeader>

        <p className="mt-3 text-[13px] leading-[1.55] text-ink-2">
          Issues already linked to cases stay linked and keep working — they stop showing their
          title and status. Nobody’s connection is removed, and nothing changes in{' '}
          {trackerProviderLabels[binding.provider]}.
        </p>

        {remove.error != null && (
          <Banner tone="bad" className="mt-3" data-testid={`${TestId}.DeleteError`}>
            <span role="alert">{describeError(remove.error).body}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.DeleteCancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            onClick={() => remove.mutate(binding.id, { onSuccess: onClose })}
            data-testid={`${TestId}.DeleteConfirm`}
          >
            Remove
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
