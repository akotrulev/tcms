/** Defect links on an execution — the list, and the controls that add and remove one. */
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import LinkIcon from '@mui/icons-material/Link';
import {
  Alert,
  Box,
  Button,
  IconButton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { useLinkDefect, useUnlinkDefect } from '../../api/queries';
import type { DefectLink } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';

/** One sentence, reused by both controls' tooltips — the server's `RunLifecycle` message. */
const FROZEN_REASON = 'This run has ended. Reopen it to change its defect links.';

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
}: {
  projectId: string;
  runId: string;
  executionId: string;
  defects: DefectLink[];
  /** The run has ended, so both writes are refused by the server. Links stay VISIBLE. */
  frozen?: boolean;
}) {
  const [adding, setAdding] = useState(false);
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
          )}
        </PermissionGate>
      }
    >
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
