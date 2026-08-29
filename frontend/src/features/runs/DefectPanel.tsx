/** Defect links on an execution — the list, and the controls that add and remove one. */
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import LinkIcon from '@mui/icons-material/Link';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  useCreateTrackerIssue,
  useLinkDefect,
  useMyTrackerIdentities,
  useUnlinkDefect,
} from '../../api/queries';
import type { DefectLink } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';

/** One sentence, reused by both controls' tooltips — the server's `RunLifecycle` message. */
const FROZEN_REASON = 'This run has ended. Reopen it to change its defect links.';

/**
 * Why Filing is disabled for somebody who has connected nothing (004 Phase 3).
 *
 * **Disabled with a reason, not hidden and not shown-and-failing.** Filing is done *as the caller*,
 * with their own credential, so a viewer who has connected nothing cannot file however many
 * permissions they hold — and a button that opens a dialog which then reports a 409 teaches them
 * the product is broken rather than that they have a connection to make.
 */
const UNCONNECTED_REASON =
  'Issues are filed as you, so this needs your own tracker connection. Connect one on your account.';

/**
 * What goes into a filed defect's body — the execution, in the words the tester is looking at.
 *
 * **Composed in the client, deliberately** (the contract's own decision): what a defect report says
 * is a product decision this SPA makes visibly, not a template buried inside an adapter where
 * nobody reviewing the product would ever read it.
 */
export interface DefectFilingContext {
  caseKey: string;
  caseTitle: string;
  runName: string;
  /** The environment this run is against, when it names one. */
  environment: string | null;
  releaseVersion: string | null;
  /** The verdict just recorded, as a word — what the reader is filing *about*. */
  verdict: string | null;
  /** The first step that did not pass, if the tester recorded one. */
  failedStep: string | null;
}

/**
 * The defects linked to one execution.
 *
 * Read-only for anyone without `defect:create`, who still sees every link: which defect a failure
 * belongs to is part of the record, and hiding it would make the screen less useful without
 * protecting anything — the server serves it to anyone who may read the execution.
 *
 * There is no `provider` control and no status field. The server writes `EXTERNAL` and refuses a
 * caller-supplied tracker name (`V62`), and `statusCache` is a tracker's answer that nothing has
 * asked for — so both are absent from the form rather than present and inert.
 */
export function DefectPanel({
  projectId,
  runId,
  executionId,
  defects,
  frozen = false,
  filing,
}: {
  projectId: string;
  runId: string;
  executionId: string;
  defects: DefectLink[];
  /** The run has ended, so both writes are refused by the server. Links stay VISIBLE. */
  frozen?: boolean;
  /**
   * Enough of the execution to write a defect report from (004 Phase 3). Absent means the screen
   * cannot describe what it would be filing, so the file-a-defect control is not offered — linking
   * an existing defect by hand still is.
   */
  filing?: DefectFilingContext;
}) {
  const [adding, setAdding] = useState(false);
  const [filingOpen, setFilingOpen] = useState(false);
  const [externalKey, setExternalKey] = useState('');
  const [url, setUrl] = useState('');
  const link = useLinkDefect(projectId, runId, executionId);
  const unlink = useUnlinkDefect(projectId, runId, executionId);

  function submit() {
    link.mutate(
      { externalKey: externalKey.trim(), url: url.trim() },
      {
        onSuccess: () => {
          setExternalKey('');
          setUrl('');
          setAdding(false);
        },
      },
    );
  }

  const incomplete = externalKey.trim() === '' || url.trim() === '';

  return (
    <Panel
      title="Defects"
      testId="Execution.Defects"
      actions={
        <PermissionGate projectId={projectId} permission="defect:create">
          {({ allowed, reason }) => (
            <Stack direction="row" sx={{ gap: 0.5, alignItems: 'center' }}>
              {filing && (
                <FileDefectButton
                  projectId={projectId}
                  allowed={allowed}
                  permissionReason={reason}
                  frozen={frozen}
                  onOpen={() => setFilingOpen(true)}
                />
              )}
              <Tooltip title={frozen ? FROZEN_REASON : allowed ? '' : reason}>
                <span>
                  <Button
                    size="small"
                    startIcon={<LinkIcon />}
                    disabled={!allowed || frozen}
                    onClick={() => setAdding((open) => !open)}
                    data-testid="Execution.Defects.Add"
                  >
                    Link a defect
                  </Button>
                </span>
              </Tooltip>
            </Stack>
          )}
        </PermissionGate>
      }
    >
      {filingOpen && filing && (
        <FileDefectDialog
          projectId={projectId}
          runId={runId}
          executionId={executionId}
          context={filing}
          onClose={() => setFilingOpen(false)}
        />
      )}
      {adding && (
        <Stack sx={{ gap: 1.5, mb: 2 }} data-testid="Execution.Defects.Form">
          <TextField
            label="Defect id"
            size="small"
            value={externalKey}
            onChange={(event) => setExternalKey(event.target.value)}
            helperText="Whatever your tracker calls it — PAY-991, #4172."
            slotProps={{ htmlInput: { 'data-testid': 'Execution.Defects.Key' } }}
          />
          <TextField
            label="Link"
            size="small"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            helperText="The address of the defect, starting with http:// or https://."
            slotProps={{ htmlInput: { 'data-testid': 'Execution.Defects.Url' } }}
          />
          <Stack direction="row" sx={{ gap: 1 }}>
            <Button
              size="small"
              variant="contained"
              disabled={incomplete || link.isPending}
              onClick={submit}
              data-testid="Execution.Defects.Save"
            >
              {link.isPending ? 'Linking…' : 'Link'}
            </Button>
            <Button
              size="small"
              onClick={() => setAdding(false)}
              data-testid="Execution.Defects.Cancel"
            >
              Cancel
            </Button>
          </Stack>
        </Stack>
      )}

      {link.isError && (
        <Alert severity="error" sx={{ mb: 2 }} data-testid="Execution.Defects.Error">
          {describeError(link.error).body}
        </Alert>
      )}

      {defects.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          No defect linked to this result.
        </Typography>
      ) : (
        <Stack sx={{ gap: 1 }} data-testid="Execution.Defects.List">
          {defects.map((defect) => (
            <DefectRow
              key={defect.id}
              projectId={projectId}
              defect={defect}
              onUnlink={() => unlink.mutate(defect.id)}
              unlinking={unlink.isPending}
              frozen={frozen}
            />
          ))}
        </Stack>
      )}
    </Panel>
  );
}

function DefectRow({
  projectId,
  defect,
  onUnlink,
  unlinking,
  frozen,
}: {
  projectId: string;
  defect: DefectLink;
  onUnlink: () => void;
  unlinking: boolean;
  /** The run has ended. Separate from `unlinking` so the tooltip can say WHY — §31. */
  frozen: boolean;
}) {
  return (
    <Stack
      direction="row"
      sx={{ alignItems: 'center', gap: 1 }}
      data-testid="Execution.Defects.Row"
    >
      <Box
        component="a"
        href={defect.url}
        target="_blank"
        rel="noopener"
        sx={{ flex: 1, minWidth: 0 }}
        data-testid="Execution.Defects.Link"
      >
        <Identifier>{defect.externalKey}</Identifier>
        <Box component="span" aria-hidden="true" sx={{ ml: 0.5, color: 'text.secondary' }}>
          ↗
        </Box>
        <Box component="span" className="sr-only">
          (opens in a new tab)
        </Box>
      </Box>
      {defect.statusCache && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {defect.statusCache}
        </Typography>
      )}
      <PermissionGate projectId={projectId} permission="defect:create">
        {({ allowed, reason }) => (
          <Tooltip title={frozen ? FROZEN_REASON : allowed ? 'Unlink' : reason}>
            <span>
              <IconButton
                size="small"
                aria-label={`Unlink ${defect.externalKey}`}
                disabled={!allowed || unlinking || frozen}
                onClick={onUnlink}
                data-testid="Execution.Defects.Unlink"
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
        )}
      </PermissionGate>
    </Stack>
  );
}

/**
 * The file-a-defect control, and the three reasons it can be unavailable.
 *
 * `defect:create` · the run has ended · **the caller has connected no tracker at all**. The third
 * is the one 004 adds and the one a permission model cannot express: filing happens with the
 * caller's own credential, so somebody holding every key in the catalogue still cannot file until
 * they connect. The tooltip therefore carries a way out rather than a refusal, and the reason is
 * shown *before* the click instead of as a 409 afterwards.
 *
 * **Zero connections is the only thing this can know for certain**, and it is honest about that:
 * whether the caller has connected *the host this project is bound to* needs the binding list,
 * which costs `integration:manage` — a key a Test Engineer filing a defect has no reason to hold.
 * So a caller with some connection and not the right one still gets the dialog, and the server's
 * `tracker-not-connected` refusal inside it. That refusal is rare; a disabled button for everybody
 * who cannot read the bindings would not be.
 */
function FileDefectButton({
  allowed,
  permissionReason,
  frozen,
  onOpen,
}: {
  projectId: string;
  allowed: boolean;
  permissionReason: string | undefined;
  frozen: boolean;
  onOpen: () => void;
}) {
  const identities = useMyTrackerIdentities();
  /*
    Pending is treated as connected, which is `useCan`'s fail-open rule and for its reason: a wrong
    enable costs one refusal inside a dialog the reader opened on purpose, and a wrong disable tells
    somebody they cannot do their job with no error anywhere to explain it.
  */
  const connected = identities.data === undefined || identities.data.length > 0;

  const reason = frozen
    ? FROZEN_REASON
    : !allowed
      ? permissionReason
      : !connected
        ? UNCONNECTED_REASON
        : '';

  return (
    <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
      <Tooltip title={reason ?? ''}>
        <span>
          <Button
            size="small"
            variant="outlined"
            disabled={!allowed || frozen || !connected}
            onClick={onOpen}
            data-testid="Execution.Defects.File"
          >
            File a defect
          </Button>
        </span>
      </Tooltip>
      {/* The way out, beside the reason rather than only inside a tooltip nobody can click. */}
      {!connected && !frozen && allowed && (
        <Button
          size="small"
          component={RouterLink}
          to="/account/trackers"
          data-testid="Execution.Defects.Connect"
        >
          Connect
        </Button>
      )}
    </Stack>
  );
}

/**
 * Filing a defect from a failed step, and linking it back — the creation seam, from this end.
 *
 * ## Two writes, and the second one is why `bindingId` travels with it
 *
 * `POST …/tracker/issues` files, and a follow-up `POST …/defects` records the link against this
 * execution. The link carries the **binding**, so the server can write which provider was actually
 * called into `defect_link.provider` — `V62`'s unique index has that column in its key, so a
 * follow-up that omitted it would write an `EXTERNAL` row, the outbox backstop would write a
 * `JIRA_CLOUD` row for the same issue, the index would not collide, and the run screen would show
 * one defect twice with both rows looking correct.
 *
 * ## The follow-up is best-effort, and the server does not depend on it
 *
 * If this browser closes between the two calls, an outbox handler writes the link from the event
 * the create raised. So a failed second call is reported and the dialog closes rather than trapping
 * the reader in a retry loop over a record that is already converging — and the create itself
 * carries an `Idempotency-Key`, so nothing here can file twice.
 */
function FileDefectDialog({
  projectId,
  runId,
  executionId,
  context,
  onClose,
}: {
  projectId: string;
  runId: string;
  executionId: string;
  context: DefectFilingContext;
  onClose: () => void;
}) {
  const create = useCreateTrackerIssue(projectId);
  const link = useLinkDefect(projectId, runId, executionId);

  const [title, setTitle] = useState(`${context.caseKey} — ${context.caseTitle}`);
  const [description, setDescription] = useState(() => composeDescription(context, projectId, runId, executionId));

  const submit = () =>
    create.mutate(
      {
        // Null files through the project's default binding, which is what a tester wants: which
        // Jira project this goes to is a decision the project made once, not one to re-take here.
        bindingId: null,
        title: title.trim(),
        description,
        executionId,
      },
      {
        onSuccess: (created) => {
          link.mutate(
            {
              bindingId: created.bindingId,
              // The key **at creation**, which is the one moment this product is entitled to it:
              // it came back from the call that made the issue rather than being parsed out of a
              // URL, and the row it lands on is a defect link rather than an issue link.
              externalKey: created.key,
              url: created.url,
            },
            { onSettled: onClose },
          );
        },
      },
    );

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid="Execution.Defects.FileDialog">
      <DialogTitle>File a defect</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, mt: 1 }}>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Filed in your name, with your own tracker account.
          </Typography>
          <TextField
            label="Summary"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            slotProps={{ htmlInput: { 'data-testid': 'Execution.Defects.FileTitle' } }}
          />
          <TextField
            label="Description"
            value={description}
            multiline
            minRows={6}
            onChange={(e) => setDescription(e.target.value)}
            helperText="Pre-filled from this execution. Edit it — it is what the tracker will show."
            slotProps={{ htmlInput: { 'data-testid': 'Execution.Defects.FileDescription' } }}
          />
          {create.error != null && (
            <Alert severity="error" role="alert" data-testid="Execution.Defects.FileError">
              {describeError(create.error).body}
            </Alert>
          )}
          {link.error != null && (
            <Alert severity="warning" data-testid="Execution.Defects.FileLinkError">
              The defect was filed, but linking it to this result did not go through. It will be
              linked shortly on its own.
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="Execution.Defects.FileCancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={create.isPending || link.isPending || title.trim() === ''}
          onClick={submit}
          data-testid="Execution.Defects.FileConfirm"
        >
          File it
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * The pre-filled body: what happened, where, and a way back here.
 *
 * The permalink is built from `window.location.origin` and the route rather than from
 * `location.href`, so it names the execution and not whatever query the reader happens to have on
 * the URL — a defect that links back to a filtered grid is a defect nobody can follow.
 */
function composeDescription(
  context: DefectFilingContext,
  projectId: string,
  runId: string,
  executionId: string,
): string {
  const permalink = `${window.location.origin}/projects/${projectId}/runs/${runId}/executions/${executionId}`;
  const lines = [
    `Test case: ${context.caseKey} — ${context.caseTitle}`,
    `Run: ${context.runName}`,
    context.environment ? `Environment: ${context.environment}` : null,
    context.releaseVersion ? `Release: ${context.releaseVersion}` : null,
    context.verdict ? `Result: ${context.verdict}` : null,
    context.failedStep ? `Failed at: ${context.failedStep}` : null,
    '',
    `Recorded in TCMS: ${permalink}`,
  ];
  return lines.filter((line) => line !== null).join('\n');
}
