import { Box, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import type { TestExecutionSummary } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { QuarantineChip } from '../../components/StatusChip';
import { plural } from '../../lib/format';
import { statusLabels } from '../../lib/labels';
import { orderedQueue } from './queueOrder';

/**
 * The run's remaining work, beside the case being executed — Split mode's left rail.
 *
 * <h3>Rows are real links</h3>
 *
 * Each row is a `<RouterLink>` to that execution's own route, not an `onClick` that swaps a
 * component's state. The execution screen is already URL-addressed; keeping the queue on the same
 * mechanism means ⌘-click opens a case in a second tab (a genuinely useful thing mid-run — check
 * one case against another), the back button walks the tester back through what they marked, and
 * the address bar always names the case on screen. A click handler would have none of that and
 * would additionally need its own copy of the mode parameter to preserve it.
 *
 * <h3>No query of its own</h3>
 *
 * The rows come from `useRun`'s `executions`, which the page already holds — the execution screen
 * has read the run since §72 for the one bit of whether it is closed. So Split costs no request
 * that Focus does not already make, and the rail cannot show a queue that disagrees with the run
 * header above it.
 */

const RowTestId = 'Execution.Queue.Row';

export function ExecutionQueue({
  executions,
  currentExecutionId,
  projectId,
  runId,
  modeParam,
}: {
  executions: TestExecutionSummary[];
  currentExecutionId: string;
  projectId: string;
  runId: string;
  /** The `?mode=` to carry onto each row's link, or null when the mode is the default. */
  modeParam: string | null;
}) {
  const ordered = orderedQueue(executions);
  const position = ordered.findIndex((execution) => execution.id === currentExecutionId);
  const suffix = modeParam ? `?mode=${modeParam}` : '';

  return (
    <Box
      component="nav"
      aria-label="Cases in this run"
      data-testid="Execution.Queue"
      sx={{
        width: 300,
        flexShrink: 0,
        borderRight: '1px solid',
        borderColor: 'divider',
        bgcolor: 'background.paper',
        // The rail scrolls independently of the case detail, and sticks: on a 400-case run the
        // tester is deep in one case's steps and still wants the next one reachable.
        position: 'sticky',
        top: 68,
        maxHeight: 'calc(100vh - 84px)',
        overflowY: 'auto',
        // Below lg there is no room for two columns, so the rail is dropped and the detail takes
        // the width — Split degrades to Focus rather than to a squeezed version of itself. A CSS
        // swap rather than `useMediaQuery`, so nothing depends on a hook that measures the window.
        display: { xs: 'none', lg: 'block' },
      }}
    >
      <Box sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="Execution.Queue.Position">
          {position >= 0
            ? `Case ${position + 1} of ${ordered.length}`
            : `${plural(ordered.length, 'case')} in this run`}
        </Typography>
      </Box>

      <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {ordered.map((execution) => {
          const current = execution.id === currentExecutionId;
          const kind = execution.status?.kind;
          return (
            <Box component="li" key={execution.id}>
              <Box
                component={RouterLink}
                to={`/projects/${projectId}/runs/${runId}/executions/${execution.id}${suffix}`}
                data-testid={RowTestId}
                // The current row is the app's standard "you are here": an inset accent bar plus
                // weight, never colour alone (ui-rules' navigation rule), and `aria-current` so a
                // screen reader is told the same thing the bar says.
                aria-current={current ? 'page' : undefined}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.25,
                  px: 2,
                  py: 1,
                  textDecoration: 'none',
                  color: 'text.primary',
                  borderBottom: '1px solid',
                  borderColor: 'divider',
                  bgcolor: current ? 'action.selected' : 'transparent',
                  boxShadow: current ? 'inset 3px 0 0 0 var(--color-action-primary)' : 'none',
                  '&:hover': { bgcolor: 'action.hover', textDecoration: 'none' },
                }}
              >
                {/*
                  The dot carries the result and the row carries no other status text — the rail
                  is 300px and a chip per row would leave no room for the title. Colour is not
                  alone: the tooltip names the status, and `Untested` is the readable default
                  rather than an absent mark, so "not run yet" is a state rather than a blank.
                */}
                <Tooltip title={kind ? statusLabels[kind] : statusLabels.UNTESTED}>
                  <Box
                    component="span"
                    data-testid={`${RowTestId}.Status`}
                    aria-label={kind ? statusLabels[kind] : statusLabels.UNTESTED}
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      flexShrink: 0,
                      bgcolor: kind ? `var(--color-${kind.toLowerCase()})` : 'var(--color-untested)',
                    }}
                  />
                </Tooltip>
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  <Typography
                    variant="body2"
                    data-testid={`${RowTestId}.Title`}
                    sx={{
                      fontWeight: current ? 600 : 400,
                      color: current ? 'text.primary' : 'text.secondary',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {execution.caseTitle}
                  </Typography>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                    <Identifier variant="muted" sx={{ fontSize: '0.6875rem' }}>
                      {execution.caseKey}
                    </Identifier>
                    {/* The rail is 260px, so this is the one surface where the chip has to earn
                        its width. It does: the queue is where a tester CHOOSES the next case, and
                        a badge that waited for the detail screen would arrive after the choice. */}
                    {execution.quarantined && (
                      <QuarantineChip testId={`${RowTestId}.Quarantined`} />
                    )}
                  </Box>
                </Box>
              </Box>
            </Box>
          );
        })}
      </Stack>
    </Box>
  );
}
