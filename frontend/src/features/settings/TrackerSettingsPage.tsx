import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
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
import type { TrackerBinding, TrackerBindingInput, TrackerProvider } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { describeError } from '../../lib/describeError';
import {
  trackerConnectionStateColors,
  trackerConnectionStateLabels,
  trackerProviderLabels,
} from '../../lib/labels';

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
    <Box>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Issue tracker"
        documentTitle={project.data ? `Issue tracker · ${project.data.name}` : 'Issue tracker'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Issue tracker' },
        ]}
        subtitle="Which tracker this project links and files into. Each person connects their own account — nothing here holds a credential."
        actions={
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
                    Add a binding
                  </Button>
                </Box>
              </Tooltip>
            )}
          </PermissionGate>
        }
      />

      {adding && <BindingDialog projectId={projectId} onClose={() => setAdding(false)} />}
      {deleting && (
        <DeleteBindingDialog
          projectId={projectId}
          binding={deleting}
          onClose={() => setDeleting(null)}
        />
      )}

      <Stack sx={{ gap: 3 }}>
        <Panel title="Bindings" testId={`${TestId}.BindingsPanel`} dense>
          {gate.pending ? null : !allowed ? (
            <Alert severity="info" data-testid={`${TestId}.Denied`}>
              You do not have permission to manage integrations in this project, so its tracker
              bindings are not shown. A test lead or a project administrator can configure them.
            </Alert>
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
                <ScrollX>
                  <Table size="small" data-testid={`${TestId}.BindingsTable`}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Tracker</TableCell>
                        <TableCell>Site</TableCell>
                        <TableCell>Project</TableCell>
                        <TableCell>Default</TableCell>
                        <TableCell>Enabled</TableCell>
                        <TableCell />
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
                </ScrollX>
              )}
            </DataRegion>
          )}
        </Panel>

        <Panel title="Who has connected" testId={`${TestId}.MembersPanel`} dense>
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
                <Stack sx={{ gap: 1, p: 1.5 }}>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    One row per project member, and one state per host this project’s bindings name.
                    Whether somebody has connected is all this can say — the credential, and why a
                    connection needs attention, are theirs.
                  </Typography>
                  <ScrollX>
                    <Table size="small" data-testid={`${TestId}.MembersTable`}>
                      <TableHead>
                        <TableRow>
                          <TableCell>Member</TableCell>
                          <TableCell>Host</TableCell>
                          <TableCell>State</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {rows.flatMap((member) =>
                          member.hosts.map((host) => (
                            <TableRow key={`${member.user.id}:${host.baseUrl}`} data-testid={`${TestId}.MemberRow`}>
                              <TableCell>{member.user.displayName}</TableCell>
                              <TableCell>
                                <Identifier variant="muted">{host.baseUrl}</Identifier>
                              </TableCell>
                              <TableCell>
                                <Chip
                                  size="small"
                                  color={trackerConnectionStateColors[host.state]}
                                  label={trackerConnectionStateLabels[host.state]}
                                />
                              </TableCell>
                            </TableRow>
                          )),
                        )}
                      </TableBody>
                    </Table>
                  </ScrollX>
                </Stack>
              )}
            </DataRegion>
          )}
        </Panel>
      </Stack>
    </Box>
  );
}

/**
 * One binding.
 *
 * The two switches write through the `PATCH` immediately rather than through a form, because each
 * is a single fact with an immediate consequence: `isDefault` moves where filing goes when no
 * binding is named, and `enabled` takes a binding out of search, preview and filing without
 * deleting anything anybody linked. `If-Match` carries `lockVersion` on both.
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
    <TableRow data-testid={`${TestId}.BindingRow`}>
      <TableCell>{trackerProviderLabels[binding.provider]}</TableCell>
      <TableCell>
        <Identifier variant="muted">{binding.baseUrl}</Identifier>
      </TableCell>
      <TableCell>
        <Identifier>{binding.externalProject}</Identifier>
      </TableCell>
      <TableCell>
        <Switch
          size="small"
          checked={binding.isDefault}
          disabled={update.isPending}
          inputProps={{ 'aria-label': 'Default binding' }}
          onChange={(_e, checked) =>
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
          size="small"
          checked={binding.enabled}
          disabled={update.isPending}
          inputProps={{ 'aria-label': 'Enabled' }}
          onChange={(_e, checked) =>
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
        <Button size="small" color="error" onClick={onDelete} data-testid={`${TestId}.Delete`}>
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
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid={`${TestId}.Dialog`}>
      <DialogTitle>Add a binding</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, mt: 1 }}>
          <TextField
            select
            label="Tracker"
            value={provider}
            onChange={(e) => setProvider(e.target.value as TrackerProvider)}
            data-testid={`${TestId}.DialogProvider`}
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
              ?? 'https only, and it must be reachable from this instance.'
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.DialogBaseUrl` } }}
          />
          <TextField
            label="Tracker project"
            placeholder="PAY, or contoso/Payments"
            value={externalProject}
            onChange={(e) => setExternalProject(e.target.value)}
            error={!!fieldErrors?.externalProject}
            helperText={
              fieldErrors?.externalProject?.join(' ')
              ?? 'The Jira project key, or Azure DevOps’ organisation/team project.'
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.DialogProject` } }}
          />
          <TextField
            label="Adapter settings"
            value={config}
            multiline
            minRows={2}
            onChange={(e) => setConfig(e.target.value)}
            error={!!configError || !!fieldErrors?.config}
            helperText={
              configError ?? fieldErrors?.config?.join(' ')
              ?? 'JSON the adapter reads — issue type, area path, the search scope. {} applies its defaults.'
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.DialogConfig` } }}
          />
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
            <Switch
              checked={isDefault}
              onChange={(_e, checked) => setIsDefault(checked)}
              inputProps={{ 'aria-label': 'Default binding' }}
              data-testid={`${TestId}.DialogDefault`}
            />
            <Typography variant="body2">
              File here when nothing names a binding. The first binding becomes the default
              regardless.
            </Typography>
          </Stack>
          {create.error != null && !fieldErrors && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.DialogError`}>
              {describeError(create.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.DialogCancel`}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={create.isPending || baseUrl.trim() === '' || externalProject.trim() === ''}
          onClick={submit}
          data-testid={`${TestId}.DialogSave`}
        >
          Add
        </Button>
      </DialogActions>
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
    <Dialog open onClose={onClose} fullWidth maxWidth="xs" data-testid={`${TestId}.DeleteDialog`}>
      <DialogTitle>Remove this binding?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2">
            Issues already linked to cases stay linked and keep working — they stop showing their
            title and status. Nobody’s connection is removed, and nothing changes in{' '}
            {trackerProviderLabels[binding.provider]}.
          </Typography>
          {remove.error != null && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.DeleteError`}>
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestId}.DeleteCancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          onClick={() => remove.mutate(binding.id, { onSuccess: onClose })}
          data-testid={`${TestId}.DeleteConfirm`}
        >
          Remove
        </Button>
      </DialogActions>
    </Dialog>
  );
}
