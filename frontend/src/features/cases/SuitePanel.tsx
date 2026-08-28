import { Box, Button, Stack, Tooltip, Typography } from '@mui/material';
import HistoryIcon from '@mui/icons-material/History';
import { Link as RouterLink } from 'react-router-dom';
import type { Suite } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { LifecycleChip } from '../../components/StatusChip';
import { formatCount } from '../../lib/format';
import { ReviewPanel } from '../review/ReviewPanel';

/**
 * The selected suite's own standing — **the home a suite did not have** (§227, audit A7).
 *
 * ## What was missing, and it was not the writes
 *
 * `suite:review`, `suite:approve` and `reviewEnabledSuite` have all existed since §74/§81, and
 * `SuiteList`'s kebab has moved a folder along §6g's ladder since §74. What had no surface at all
 * was the **result**: a suite's review record — who was asked, who signed off, what they said —
 * was written by the server, gated by three permissions, switched on by a project setting, and
 * rendered nowhere. `ReviewPanel` mounted on cases and plans only, and the one route a suite has
 * (`/suites/:suiteId/history`) is about executions.
 *
 * ## Why a panel here rather than a suite detail page
 *
 * A suite is **flat since §98** — a named collection whose members are three arms — and every write
 * it has hangs off the case-list rail. A page of its own would be a screen whose only content is a
 * header and this panel, reached by a navigation away from the list the reader is using to decide
 * *which* suite they mean. So it lives where the selection is made, in the wide column, above the
 * grid that is already scoped to it.
 *
 * It is deliberately **not** in the rail: `ReviewPanel` carries assignees, verdicts and a comment
 * thread, and a 260-pixel column would render all three as a stack of ellipses.
 *
 * ## The lifecycle is repeated here on purpose
 *
 * `SuiteList` chips it too, at 12 pixels, in a list of twenty. This is the one place the reader is
 * looking *at* one suite, and the review standing directly below is unreadable without it — an
 * approval means nothing until you know whether the folder is approved.
 *
 * The panel renders nothing when review is off for suites in this project: `ReviewPanel` returns
 * null on its own settings check, so the header stays and the thread does not appear. That is the
 * honest rendering — the folder still has a lifecycle, and the project has simply not asked anybody
 * to sign off on folders.
 */
export function SuitePanel({ projectId, suite }: { projectId: string; suite: Suite }) {
  return (
    <Box data-testid="Cases.SuitePanel">
      <Panel testId="Cases.SuitePanel.Header" dense>
        <Stack
          direction="row"
          sx={{ alignItems: 'center', gap: 1.5, flexWrap: 'wrap', px: 1.5, py: 1 }}
        >
          <Identifier variant="strong">{suite.key}</Identifier>
          <Typography variant="body2" sx={{ fontWeight: 600 }} data-testid="Cases.SuitePanel.Name">
            {suite.name}
          </Typography>
          <LifecycleChip status={suite.lifecycleStatus} testId="Cases.SuitePanel.Lifecycle" />
          {/*
            The membership figure, worded rather than left as a bare number: the three arms overlap
            across suites, so "12 cases" beside a project total nobody can reconcile is the
            subtraction the contract's own description warns about.
          */}
          {/*
            And the three arms, separately (005 S1). `caseCount` is the size of the union — the
            arms overlap, so they do not add up to it and the sentence must not invite the
            subtraction — and until they were served, "filed, added or tagged" named three
            mechanisms while showing one number for all of them. A reader deciding whether a suite
            is a filing structure or a saved query cannot tell from the union alone.
          */}
          <Tooltip
            title={`${formatCount(suite.filedCount)} filed · ${formatCount(suite.addedCount)} added · ${formatCount(suite.taggedCount)} matched by tag. They overlap, so they do not add up to ${formatCount(suite.caseCount)}.`}
          >
            <Typography
              variant="caption"
              sx={{ color: 'text.secondary' }}
              data-testid="Cases.SuitePanel.CaseCount"
            >
              {suite.caseCount} {suite.caseCount === 1 ? 'case' : 'cases'} filed, added or tagged here
            </Typography>
          </Tooltip>
          <Box sx={{ flex: 1 }} />
          <Button
            component={RouterLink}
            to={`/projects/${projectId}/suites/${suite.id}/history`}
            size="small"
            startIcon={<HistoryIcon />}
            data-testid="Cases.SuitePanel.HistoryLink"
          >
            Execution history
          </Button>
        </Stack>
      </Panel>

      {/*
        No `currentVersionNo`. A suite's version is its composition and `suite_version` has been
        written once and never again since §98 — so passing it would make the panel compare the
        reviewed version against a number that cannot move, and print "the content has changed since
        this review" never, on a folder whose membership changes daily. The comparison is not
        offered rather than offered wrongly; `ReviewPanel`'s own prop is optional for this reason.
      */}
      <ReviewPanel projectId={projectId} subjectType="SUITE" subjectId={suite.id} />
    </Box>
  );
}
