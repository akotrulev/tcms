/** Evidence on ONE step of a run — the strip under a step row, and the control that adds to it. */
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import { Alert, Box, Button, IconButton, Link, Stack, Tooltip, Typography } from '@mui/material';
import { useRef, useState } from 'react';
import { MAX_EVIDENCE_BYTES, useAttachEvidence, useDeleteEvidence } from '../../api/queries';
import type { Evidence } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatBytes } from '../../lib/format';

const FROZEN_REASON = 'This run has ended. Reopen it to change its evidence.';

/**
 * The server's own refusal, said before it can happen rather than after.
 *
 * `POST …/evidence` resolves `stepId` against `step_outcome` and answers a 400 on that field when
 * the step has no recorded result — a step nobody has marked has no row to attach to, and inventing
 * one would record a verdict nobody gave. The marks on this screen are local until Submit, so an
 * unconditional Attach here would 400 for every step the tester has not yet saved.
 */
const UNMARKED_REASON = 'Record a result for this step before attaching evidence to it.';

/**
 * Evidence attached to one step, rather than to the execution as a whole.
 *
 * The distinction is the server's and it is already on the wire: `StepResult.evidence` carries the
 * files whose `step_result_id` is this step's, and `TestExecution.evidence` carries only those with
 * none. So a screenshot attached here documents *where it went wrong*, and one attached to the
 * execution documents *the verdict* — which is the difference `EvidencePanel` alone could not show,
 * because nothing in the SPA had ever sent a `stepId` (audit C9).
 */
export function StepEvidence({
  projectId,
  runId,
  executionId,
  stepId,
  evidence,
  recorded,
  frozen,
}: {
  projectId: string;
  runId: string;
  executionId: string;
  stepId: string;
  evidence: Evidence[];
  /** The SERVER has a result for this step. Not the local mark — see `UNMARKED_REASON`. */
  recorded: boolean;
  frozen: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [tooLarge, setTooLarge] = useState<string | null>(null);
  const attach = useAttachEvidence(projectId, runId, executionId);
  const remove = useDeleteEvidence(projectId, runId, executionId);

  function choose(file: File | undefined) {
    if (!file) return;
    if (file.size > MAX_EVIDENCE_BYTES) {
      setTooLarge(
        `${file.name} is ${formatBytes(file.size)}. Evidence files are limited to ` +
          `${formatBytes(MAX_EVIDENCE_BYTES)}.`,
      );
      return;
    }
    setTooLarge(null);
    attach.mutate({ file, stepId });
  }

  function blockedReason(allowed: boolean, denial: string | undefined): string {
    if (frozen) return FROZEN_REASON;
    if (!allowed) return denial ?? '';
    if (!recorded) return UNMARKED_REASON;
    return '';
  }

  return (
    <Box sx={{ mt: 1, pl: 3 }} data-testid="Execution.Steps.Row.Evidence">
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <PermissionGate projectId={projectId} permission="evidence:attach">
          {({ allowed, reason }) => (
            <Tooltip title={blockedReason(allowed, reason)}>
              <span>
                <Button
                  size="small"
                  startIcon={<UploadFileIcon />}
                  disabled={!allowed || frozen || !recorded || attach.isPending}
                  onClick={() => input.current?.click()}
                  data-testid="Execution.Steps.Row.Evidence.Attach"
                >
                  {attach.isPending ? 'Uploading…' : 'Attach to this step'}
                </Button>
              </span>
            </Tooltip>
          )}
        </PermissionGate>

        {evidence.map((item) => (
          <Stack
            key={item.id}
            direction="row"
            sx={{ alignItems: 'center', gap: 0.5 }}
            data-testid="Execution.Steps.Row.Evidence.Row"
          >
            <Link
              href={item.url}
              target="_blank"
              rel="noreferrer"
              variant="caption"
              sx={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
              data-testid="Execution.Steps.Row.Evidence.Link"
            >
              {item.filename}
            </Link>
            <PermissionGate projectId={projectId} permission="evidence:delete">
              {({ allowed, reason }) => (
                <Tooltip title={frozen ? FROZEN_REASON : allowed ? 'Remove' : reason}>
                  <span>
                    <IconButton
                      size="small"
                      aria-label={`Remove ${item.filename}`}
                      disabled={!allowed || frozen || remove.isPending}
                      onClick={() => remove.mutate(item.id)}
                      data-testid="Execution.Steps.Row.Evidence.Remove"
                    >
                      <DeleteOutlineIcon fontSize="inherit" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
            </PermissionGate>
          </Stack>
        ))}
      </Stack>

      <input
        ref={input}
        type="file"
        hidden
        data-testid="Execution.Steps.Row.Evidence.Input"
        onChange={(event) => {
          choose(event.target.files?.[0]);
          // Cleared so choosing the SAME file twice fires change again — without this a failed
          // upload cannot be retried by picking the identical file, which is the first thing
          // anyone tries.
          event.target.value = '';
        }}
      />

      {tooLarge && (
        <Alert severity="warning" sx={{ mt: 1 }} data-testid="Execution.Steps.Row.Evidence.TooLarge">
          {tooLarge}
        </Alert>
      )}
      {attach.isError && (
        <Alert severity="error" sx={{ mt: 1 }} data-testid="Execution.Steps.Row.Evidence.Error">
          {describeError(attach.error).body}
        </Alert>
      )}
      {!recorded && evidence.length === 0 && (
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.5 }}>
          {UNMARKED_REASON}
        </Typography>
      )}
    </Box>
  );
}
