import BookmarkAddOutlinedIcon from '@mui/icons-material/BookmarkAddOutlined';
import { Alert, Box, Button, Stack, Tooltip } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { usePlans } from '../../api/queries';
import type { ReportJob } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { RegisterArtifactDialog } from '../artifacts/ArtifactDialogs';

/**
 * Saves the report on screen into the project's artifact register — audit C6.
 *
 * ## What was actually missing, which was neither feature
 *
 * `V1` declared `artifact.report_artifact_id` and `ArtifactKind.GENERATED_REPORT` in M0; the
 * reporting module has produced artifacts since §87; the register has rendered any kind of row
 * since §57. The two shipped features simply had no join, so the durable record of "these were the
 * numbers we signed the cycle off on" could be produced and could be registered and could not be
 * *both*. A report otherwise survives only as a job row and a download somebody kept.
 *
 * ## Why it reuses the register's own dialog rather than carrying a small form
 *
 * `ArtifactDialogs`' header refuses a second Upload dialog for a list of shared concerns — scope,
 * the plan requirement, the category list, the two length limits, the `403`-as-a-field-error
 * mapping — and every one of them applies here unchanged. Pinning a report differs from
 * registering a link in exactly one respect, *what the payload is*, and that is the one thing the
 * user never chooses: pressing this button is the choice. So the dialog takes `pinnedReport` and
 * hides the kind picker; nothing about the register is spelled twice.
 *
 * ## It keys on the download URL, never on the status
 *
 * `DownloadReportButton`'s rule one file over, and for its reason: `downloadUrl` is null for the
 * whole of PENDING and RUNNING and null for ever on a FAILED job. The server refuses a pin whose
 * job produced no artifact, so the two agree — and the one that cannot offer a control leading to
 * a refusal is the URL.
 *
 * ## The key is the register's, and the mismatch is the point
 *
 * `artifact:edit`, not `report:view`. A reader who may look at reports and may not write the
 * register sees this disabled with the gate's own sentence, rather than a 403 after filling the
 * form in — §31. Asked unqualified: the scope this would register at is chosen *inside* the
 * dialog, so the question here is whether the caller may register anything at all.
 */
export function PinReportButton({
  job,
  projectId,
  reportTitle,
  testId,
}: {
  job: ReportJob;
  projectId: string;
  /** The report's own title, which the dialog opens with — see its `pinnedReport` prop. */
  reportTitle: string;
  testId: string;
}) {
  const [open, setOpen] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const gate = useCan(projectId);
  /*
    For the dialog's plan picker. Fetched here rather than passed down from the page: this is the
    only thing on a report screen that needs plans, and threading them through `ReportViewPage`
    would make every render of a report fetch a list it does not use.

    `pages` is `[]` and that is not the same shortcut — the documentation picker belongs to
    `DOC_PAGE_REF`, a kind this dialog cannot be put into while `pinnedReport` is set, so there is
    no control to feed.
  */
  const plans = usePlans(projectId);

  if (!job.downloadUrl) return null;

  const allowed = gate.can('artifact:edit');
  const reason = gate.reason('artifact:edit');

  return (
    <Stack sx={{ gap: 1, alignItems: 'flex-end' }}>
      <Tooltip title={reason ?? 'Keep this report in the project register'}>
        <Box component="span">
          <Button
            size="small"
            variant="outlined"
            startIcon={<BookmarkAddOutlinedIcon />}
            disabled={!allowed}
            onClick={() => setOpen(true)}
            data-testid={testId}
          >
            Save to register
          </Button>
        </Box>
      </Tooltip>

      {/*
        The confirmation is in place and carries the way onward, rather than a notification.

        Two reasons, and the second is the one that decided it. The notification stack is for
        *errors* and prompts (`useNotify`) — there is no success channel and inventing one here
        would be a second notification system with one caller. And the useful thing to say is not
        "saved" but "here is where it went": the register is a different screen, so a message that
        vanishes leaves the reader with a fact and no way to act on it.
      */}
      {saved && (
        <Alert severity="success" data-testid={`${testId}.Saved`} sx={{ width: '100%' }}>
          “{saved}” is in the project register.{' '}
          <RouterLink to={`/projects/${projectId}/artifacts`}>Open the register</RouterLink>
        </Alert>
      )}

      {/*
        Conditionally rendered so it mounts fresh, which is what makes the report's title the
        starting value rather than a `reset` fighting a half-typed one — `ArtifactDialogs`' own
        no-reset rule, relied on from the outside here.
      */}
      {open && (
        <RegisterArtifactDialog
          open
          onClose={() => setOpen(false)}
          projectId={projectId}
          plans={plans.data ?? []}
          pages={[]}
          pinnedReport={{ jobId: job.jobId, defaultTitle: reportTitle }}
          onRegistered={(artifact) => setSaved(artifact.title)}
        />
      )}
    </Stack>
  );
}
