import { Box, Tooltip } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import type { TestExecutionSummary } from '../../api/types';
import { Identifier } from '../../components/Identifier';

/**
 * The two links on an execution row, and the one place that decides where each one goes.
 *
 * <h3>Why this is a module and not two copies</h3>
 *
 * Two grids in this app list executions — the run's own checklist (`RunDetailPage`) and Grid mode
 * on the execution screen (`ExecutionGrid`) — and they had **opposite** answers to the same
 * question. `RunDetailPage` sent the key to the case and the title to the execution;
 * `ExecutionGrid` sent the key to the execution and made the title inert, so from Grid mode there
 * was **no way to reach the test case at all**. That is `ui-rules` §8's renderer divergence in
 * miniature: one rule, implemented once in each direction. It lives here now, and a third grid
 * composes it rather than re-deciding it.
 *
 * The two grids keep their own column layout — `RunDetailPage` puts both links in one Case cell
 * beside the pinned version, Grid mode gives the key its own narrow column so keys align down the
 * page for scanning. The *layout* is legitimately different; the *destinations* are not.
 *
 * <h3>Which link goes where, and why that way round</h3>
 *
 * **The key opens the test case.** A key is what a tester quotes in a defect report and what they
 * search for; it names the case, not this one attempt at it.
 *
 * **The title opens the execution** — the screen where the case is passed or failed. It is the
 * bigger target, it is what a tester recognises when scanning, and it is the thing they are on
 * this screen to do.
 *
 * <h3>Two links in one row means two accessible names</h3>
 *
 * A screen-reader user pulling up the link list would otherwise hear the same row twice with no
 * way to tell the two destinations apart, and a bare `TC-41` does not say it is a link to
 * anywhere. So:
 *
 *  - the key carries a **tooltip and a matching `aria-label`** — *"Open test case TC-41"* — which
 *    is also the answer to the sighted reader's question, since the affordance of a monospace key
 *    is not obvious. The two strings are deliberately the same one: MUI's `Tooltip` sets its own
 *    `aria-label` from `title` when the child has none, so writing them separately would leave two
 *    sentences that could drift, one of them unread.
 *  - the title's accessible name is its visible text plus the destination, which satisfies
 *    WCAG 2.5.3 (the visible label is contained in the accessible name) while still saying where
 *    the link goes.
 *
 * The title's own `Tooltip` stays what it always was — **the untruncated title**, because that
 * cell ellipsises. It is a truncation affordance and not a destination one, which is why the
 * destination is on the `aria-label` instead of being crammed into it.
 */

/** `/cases/{id}` — the case itself, outside any run. */
function caseHref(projectId: string, caseId: string) {
  return `/projects/${projectId}/cases/${caseId}`;
}

/** `/runs/{id}/executions/{id}` — this attempt at the case, where a verdict is recorded. */
function executionHref(projectId: string, runId: string, executionId: string, suffix: string) {
  return `/projects/${projectId}/runs/${runId}/executions/${executionId}${suffix}`;
}

/**
 * The case key, linked to the test case.
 *
 * `testId` is the row's static locator (`test-ids` rule 6 — never interpolated with a row id);
 * this composes `.CaseLink` onto it, matching the name `RunDetailPage` has used since it grew the
 * link.
 */
export function CaseKeyLink({
  execution,
  projectId,
  testId,
}: {
  execution: Pick<TestExecutionSummary, 'caseId' | 'caseKey'>;
  projectId: string;
  testId: string;
}) {
  const label = `Open test case ${execution.caseKey}`;

  return (
    <Tooltip title={label}>
      <Box
        component={RouterLink}
        to={caseHref(projectId, execution.caseId)}
        aria-label={label}
        data-testid={`${testId}.CaseLink`}
      >
        <Identifier>{execution.caseKey}</Identifier>
      </Box>
    </Tooltip>
  );
}

/**
 * The case title, linked to the execution — the row→detail affordance, and the one a tester uses
 * to go and record a result.
 *
 * Truncates with the full title in a tooltip (`ui-rules` §2: cap the width, never dump free text
 * into a cell), so `maxWidth` is a prop rather than a constant — the two grids give this cell
 * different room.
 */
export function ExecuteCaseLink({
  execution,
  projectId,
  runId,
  /** The `?mode=` Grid mode carries onto its links, so opening a case keeps the mode. */
  linkSuffix = '',
  maxWidth,
  testId,
}: {
  execution: Pick<TestExecutionSummary, 'id' | 'caseKey' | 'caseTitle'>;
  projectId: string;
  runId: string;
  linkSuffix?: string;
  maxWidth?: number;
  testId: string;
}) {
  return (
    <Tooltip title={execution.caseTitle}>
      <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth }}>
        <Box
          component={RouterLink}
          to={executionHref(projectId, runId, execution.id, linkSuffix)}
          aria-label={`${execution.caseTitle} — record a result for ${execution.caseKey} in this run`}
          data-testid={`${testId}.Link`}
        >
          {execution.caseTitle}
        </Box>
      </Box>
    </Tooltip>
  );
}
