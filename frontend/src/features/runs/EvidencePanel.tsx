/** Evidence on an execution — the strip of attachments, and the control that adds one. */
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  Box,
  Button,
  IconButton,
  Link,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { useRef, useState } from 'react';
import { MAX_EVIDENCE_BYTES, useAttachEvidence, useDeleteEvidence } from '../../api/queries';
import type { Evidence } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatBytes, formatDateTime } from '../../lib/format';

/**
 * Why both controls are disabled on a run that has ended. One sentence, two anchors — the tooltip
 * on Attach and the tooltip on each Remove — matching the server's single `RunLifecycle` message.
 */
const FROZEN_REASON = 'This run has ended. Reopen it to change its evidence.';

/**
 * The evidence attached to one execution.
 *
 * <p>Read-only for a Viewer, who still sees every attachment: evidence is part of the record of
 * what happened, and hiding it would make the screen less useful without protecting anything —
 * the server serves it to anyone who may read the execution.
 */
export function EvidencePanel({
  projectId,
  runId,
  executionId,
  evidence,
  frozen = false,
}: {
  projectId: string;
  runId: string;
  executionId: string;
  evidence: Evidence[];
  /**
   * The run has ended, so both writes are refused by the server (§72). Attachments stay VISIBLE and
   * downloadable — a closed run's evidence is the part of the record most likely to be read — and
   * only the two controls that would 409 are disabled.
   */
  frozen?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [tooLarge, setTooLarge] = useState<string | null>(null);
  const attach = useAttachEvidence(projectId, runId, executionId);
  const remove = useDeleteEvidence(projectId, runId, executionId);

  function choose(file: File | undefined) {
    if (!file) return;
    if (file.size > MAX_EVIDENCE_BYTES) {
      // Copy, not security — the server refuses this with a 413 regardless. It exists so the user
      // finds out before uploading 10 MB over a slow link, which is the difference between a rule
      // and a punishment.
      setTooLarge(
        `${file.name} is ${formatBytes(file.size)}. Evidence files are limited to ` +
          `${formatBytes(MAX_EVIDENCE_BYTES)}.`,
      );
      return;
    }
    setTooLarge(null);
    attach.mutate({ file });
  }

  return (
    <Panel
      title="Evidence"
      testId="Execution.Evidence"
      actions={
        <PermissionGate projectId={projectId} permission="evidence:attach">
          {({ allowed, reason }) => (
            <Tooltip title={frozen ? FROZEN_REASON : allowed ? '' : reason}>
              <span>
                <Button
                  size="small"
                  startIcon={<UploadFileIcon />}
                  disabled={!allowed || attach.isPending || frozen}
                  onClick={() => input.current?.click()}
                  data-testid="Execution.Evidence.Attach"
                >
                  {attach.isPending ? 'Uploading…' : 'Attach'}
                </Button>
              </span>
            </Tooltip>
          )}
        </PermissionGate>
      }
    >
      <input
        ref={input}
        type="file"
        hidden
        data-testid="Execution.Evidence.Input"
        onChange={(event) => {
          choose(event.target.files?.[0]);
          // Cleared so choosing the SAME file twice fires change again — without this, a failed
          // upload cannot be retried by picking the identical file, which is the first thing
          // anyone tries.
          event.target.value = '';
        }}
      />

      {tooLarge && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="Execution.Evidence.TooLarge">
          {tooLarge}
        </Alert>
      )}
      {attach.isError && (
        <Alert severity="error" sx={{ mb: 2 }} data-testid="Execution.Evidence.Error">
          {describeError(attach.error).body}
        </Alert>
      )}

      {evidence.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          No screenshots or logs attached yet.
        </Typography>
      ) : (
        <Stack sx={{ gap: 1 }} data-testid="Execution.Evidence.List">
          {evidence.map((item) => (
            <EvidenceRow
              key={item.id}
              projectId={projectId}
              item={item}
              onDelete={() => remove.mutate(item.id)}
              deleting={remove.isPending}
              frozen={frozen}
            />
          ))}
        </Stack>
      )}
    </Panel>
  );
}

function EvidenceRow({
  projectId,
  item,
  onDelete,
  deleting,
  frozen,
}: {
  projectId: string;
  item: Evidence;
  onDelete: () => void;
  deleting: boolean;
  /** The run has ended. Separate from `deleting` so the tooltip can say WHY — §31. */
  frozen: boolean;
}) {
  const isImage = item.contentType.startsWith('image/');

  return (
    <Stack
      direction="row"
      sx={{ alignItems: 'center', gap: 1.5 }}
      data-testid="Execution.Evidence.Row"
    >
      {isImage && (
        <Box
          component="img"
          src={item.url}
          alt=""
          sx={{
            width: 48,
            height: 48,
            objectFit: 'cover',
            borderRadius: 1,
            border: '1px solid',
            borderColor: 'divider',
            flexShrink: 0,
          }}
        />
      )}
      <Stack sx={{ minWidth: 0, flex: 1 }}>
        <Link
          href={item.url}
          target="_blank"
          rel="noreferrer"
          variant="body2"
          sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          data-testid="Execution.Evidence.Link"
        >
          {item.filename}
        </Link>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {formatBytes(item.sizeBytes)}
          {item.createdBy ? ` · ${item.createdBy.displayName}` : ''}
          {` · ${formatDateTime(item.createdAt)}`}
        </Typography>
        {item.caption && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {item.caption}
          </Typography>
        )}
      </Stack>

      {/*
        ~~`authorId` is what makes this an OWN-qualified gate: `evidence:delete` is held outright by
        leads and above, and by a Test Engineer only over evidence they attached (roles spec §A5).~~
        → V51 (§207) removed that qualifier along with `case:delete`'s, so this resolves to the
        plain grant today. The argument passes anyway, for the reason CaseDetailPage's Delete gate
        gives: the qualifier is server-side data, not a client constant.

        `?? null` and NOT `?? undefined` — §66. PermissionGate switches on `authorId !== undefined`,
        so `undefined` means "this affordance is not author-qualified" and would hand every row the
        UNQUALIFIED verdict. A null author is a *supplied* author: the row nobody can attribute,
        which the arm must refuse. The two absences are different answers and must not collapse.
      */}
      <PermissionGate
        projectId={projectId}
        permission="evidence:delete"
        authorId={item.createdBy?.id ?? null}
      >
        {({ allowed, reason }) => (
          <Tooltip title={frozen ? FROZEN_REASON : allowed ? 'Remove' : reason}>
            <span>
              <IconButton
                size="small"
                disabled={!allowed || deleting || frozen}
                onClick={onDelete}
                aria-label={`Remove ${item.filename}`}
                data-testid="Execution.Evidence.Delete"
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
