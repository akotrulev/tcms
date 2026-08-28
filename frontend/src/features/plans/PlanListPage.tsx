import {
  Box,
  Button,
  Chip,
  FormControlLabel,
  IconButton,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import ArchiveOutlinedIcon from '@mui/icons-material/ArchiveOutlined';
import UnarchiveOutlinedIcon from '@mui/icons-material/UnarchiveOutlined';
import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import type { TestPlan } from '../../api/types';
import { usePlans, useProject, useSetPlanArchived } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { CriteriaStrip } from '../../components/CriteriaStrip';
import { formatCount, formatPercent } from '../../lib/format';
import { planStatusColors, planStatusLabels } from '../../lib/labels';
import { PlanCreateDialog } from './PlanCreateDialog';
import { ScrollX } from '../../components/ScrollX';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';

/**
 * Test plans. Deliberately a compact table rather than a card grid: a lead comparing four
 * plans wants the pass rate and coverage columns to line up so the eye can scan down them,
 * which cards make impossible.
 *
 * Both of those columns are an em dash on every row today: the server sets `passRate` and
 * `coverage` to null unconditionally (`TestPlanView`), so the comparison this shape exists
 * for is not yet one the screen can offer. The columns stay because the table is built for
 * the day they are served, and `openapi.yaml` now declares both as a percentage 0-100 so
 * that day does not arrive with the unit in doubt.
 */
export function PlanListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  /*
    In the URL, not in a `useState` — this repo's rule for filter state, and it earns it here: a
    lead who has ticked "show archived" to find last spring's plan sends somebody the link, and a
    component-state toggle sends them a list the plan is not in.
  */
  const [params, setParams] = useSearchParams();
  const showArchived = params.get('archived') === 'true';
  const plans = usePlans(projectId, showArchived);
  useAnnouncedResults(
    plans,
    `${formatCount(plans.data?.length ?? 0)} ${(plans.data?.length ?? 0) === 1 ? 'plan' : 'plans'} shown`,
  );
  const [creating, setCreating] = useState(false);

  return (
    <Box>
      <PageHeader
        testId="Plans.Header"
        title="Test plans"
        documentTitle={project.data ? `Test plans · ${project.data.name}` : 'Test plans'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test plans' },
        ]}
        subtitle="A plan fixes the scope, configurations and exit criteria for a release. Runs execute against it."
        actions={
          <Stack direction="row" sx={{ gap: 2, alignItems: 'center' }}>
            {/*
              Ungated: showing archived plans discloses nothing a plan list does not already, and
              somebody who cannot archive still has to be able to FIND the plan somebody else did.
            */}
            <FormControlLabel
              control={
                <Switch
                  size="small"
                  checked={showArchived}
                  onChange={(event) => {
                    const next = new URLSearchParams(params);
                    if (event.target.checked) next.set('archived', 'true');
                    else next.delete('archived');
                    setParams(next, { replace: true });
                  }}
                  data-testid="Plans.ShowArchived"
                />
              }
              label={
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Show archived
                </Typography>
              }
            />
            {/*
              Disabled with a reason rather than hidden (§31): a Viewer seeing a greyed "New plan"
              learns the capability exists and is worth asking for. `plan:create` is PROJECT scope —
              unlike "New project", which is the app's one company-scope affordance — so this asks
              the project question and the gate matches the server's.
            */}
            <PermissionGate projectId={projectId} permission="plan:create">
              {({ allowed }) => (
                <Button
                  variant="contained"
                  disabled={!allowed}
                  onClick={() => setCreating(true)}
                  data-testid="Plans.New"
                >
                  New plan
                </Button>
              )}
            </PermissionGate>
          </Stack>
        }
      />

      {/* Conditionally rendered, so the form mounts fresh each time — see the dialog's own note. */}
      {creating && (
        <PlanCreateDialog
          projectId={projectId}
          open
          onClose={() => setCreating(false)}
          /*
            Stays on the list. ~~Because `/plans/:planId` is the plan STATISTICS route and that
            endpoint is deliberately unimplemented server-side (§38), so navigating there on
            success showed a red "Something didn't load" toast one second after the plan was
            created — the create worked and the app said it had not.~~ → superseded: the endpoint
            is served (`PlanStatisticsService`) and `PlanStatisticsPage` renders it, which is
            where this row's own link goes.

            The destination did not move, for a second reason that outlived the first: a plan
            created a second ago has been executed zero times, so its statistics screen is
            every empty state at once. The list answers the question the creator actually has
            — did it save, and what scope did it pick up — with the new plan as the first row.
          */
          onCreated={() => setCreating(false)}
        />
      )}

      <Panel testId="Plans.Panel" dense>
        <DataRegion
          testId="Plans.List"
          state={{ ...plans, refetch: () => void plans.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No test plans yet',
            body: 'Create a plan to fix the scope and exit criteria for a release.',
          }}
        >
          {(rows) => <PlanTable rows={rows} projectId={projectId} />}
        </DataRegion>
      </Panel>
    </Box>
  );
}

/**
 * Put this plan away, or bring it back (`V65`, audit D2).
 *
 * **No confirmation, deliberately.** The other destructive-looking act on a plan — Delete — has a
 * dialog because it cannot be undone; this is undone by clicking the same control again, and a
 * confirmation on a reversible act teaches people to dismiss confirmations.
 *
 * Its own component so the mutation hook is per row rather than one hook the table threads a plan
 * id through — the hook keys its cache updates on the plan it was built for.
 */
function ArchiveToggle({ projectId, plan }: { projectId: string; plan: TestPlan }) {
  const setArchived = useSetPlanArchived(projectId, plan.id);
  const archived = plan.archived;

  return (
    <PermissionGate projectId={projectId} permission="plan:edit">
      {({ allowed }) => (
        <Tooltip
          title={
            allowed
              ? archived
                ? 'Put this plan back in the working list'
                : 'Archive: take this plan out of the list. Nothing is deleted, and it can still be run.'
              : 'You do not have permission to edit test plans in this project'
          }
        >
          {/* A span, because MUI drops the tooltip on a disabled button. */}
          <span>
            <IconButton
              size="small"
              disabled={!allowed || setArchived.isPending}
              onClick={() => setArchived.mutate(!archived)}
              aria-label={archived ? `Restore ${plan.key}` : `Archive ${plan.key}`}
              data-testid="Plans.Row.ArchiveToggle"
            >
              {archived ? (
                <UnarchiveOutlinedIcon fontSize="small" />
              ) : (
                <ArchiveOutlinedIcon fontSize="small" />
              )}
            </IconButton>
          </span>
        </Tooltip>
      )}
    </PermissionGate>
  );
}

function PlanTable({ rows, projectId }: { rows: TestPlan[]; projectId: string }) {
  // Deterministic order with a unique tiebreaker: the plans still being written first, then by
  // milestone date, then by key — so the list never reshuffles between loads. §97 (M7) removed
  // ACTIVE, which used to lead: with three states, what a reader is looking for on this screen is
  // the draft they were editing, and an approved plan is finished work.
  const sorted = [...rows].sort((a, b) => {
    const rank = (plan: TestPlan) => (plan.status === 'DRAFT' ? 0 : plan.status === 'IN_REVIEW' ? 1 : 2);
    return (
      rank(a) - rank(b) ||
      (b.milestoneDate ?? '').localeCompare(a.milestoneDate ?? '') ||
      a.key.localeCompare(b.key)
    );
  });

  return (
    <ScrollX>
      <Table size="small" data-testid="Plans.Grid">
        <TableHead>
          <TableRow>
            <TableCell sx={{ color: 'text.secondary' }}>Plan</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 110 }}>Status</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 170 }}>Milestone</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 80 }} align="right">
              Cases
            </TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 100 }} align="right">
              Pass rate
            </TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 100 }} align="right">
              Coverage
            </TableCell>
            {/*
              Left-aligned and wider since §100: the cell is a strip of marks read left-to-right
              followed by its count, not a number. Right-aligning it would put the marks against
              the table's edge and the count in the middle, which reads as two columns.
            */}
            <TableCell sx={{ color: 'text.secondary', width: 210 }}>Exit criteria</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 56 }} align="right" />
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((plan) => {
            return (
              <TableRow key={plan.id} data-testid="Plans.Row" hover>
                <TableCell>
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, minWidth: 0 }}>
                    <RouterLink to={`/projects/${projectId}/plans/${plan.id}`} data-testid="Plans.Row.Link">
                      <Identifier>{plan.key}</Identifier>
                    </RouterLink>
                    <RouterLink
                      to={`/projects/${projectId}/plans/${plan.id}`}
                      style={{ color: 'inherit', textDecoration: 'none', minWidth: 0 }}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 500 }} data-testid="Plans.Row.Name">
                        {plan.name}
                      </Typography>
                    </RouterLink>
                    {/*
                      Beside the name rather than in the status column: archiving is orthogonal to
                      the ladder (`V65`), and putting it in that cell would read as a fourth status
                      — the exact conflation the column was kept out of the ladder to avoid.
                    */}
                    {plan.archived && (
                      <Chip
                        size="small"
                        label="Archived"
                        variant="outlined"
                        data-testid="Plans.Row.Archived"
                      />
                    )}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    label={planStatusLabels[plan.status]}
                    data-testid="Plans.Row.Status"
                    sx={{
                      bgcolor: 'transparent',
                      color: planStatusColors[plan.status],
                      border: `1px solid color-mix(in srgb, ${planStatusColors[plan.status]} 45%, transparent)`,
                    }}
                  />
                </TableCell>
                <TableCell sx={{ fontSize: '0.75rem' }}>
                  {plan.milestoneName ? (
                    <>
                      {plan.milestoneName}
                      {plan.milestoneDate && (
                        <Box sx={{ color: 'text.secondary' }}>
                          <Identifier variant="muted">{plan.milestoneDate}</Identifier>
                        </Box>
                      )}
                    </>
                  ) : (
                    <Box sx={{ color: 'text.secondary' }}>—</Box>
                  )}
                </TableCell>
                <TableCell align="right">
                  <Identifier>{formatCount(plan.caseCount)}</Identifier>
                </TableCell>
                {/*
                  Both are nullable and the two nulls are different claims (005 S5), which is why
                  each carries its own tooltip rather than sharing one em dash with no explanation:

                    - `passRate === null` — nothing in scope has a result. A plan nobody has run has
                      no pass rate, and `0` would say every case failed.
                    - `coverage === null` — the scope is empty, so the denominator is zero.

                  `formatPercent` renders both as an em dash, which is right for the cell; the
                  tooltip is what stops the em dash reading as "we could not load this".
                */}
                <TableCell align="right">
                  <Tooltip title={plan.passRate === null ? 'Nothing in this plan has been run yet.' : ''}>
                    <span>
                      <Identifier>{formatPercent(plan.passRate)}</Identifier>
                    </span>
                  </Tooltip>
                </TableCell>
                <TableCell align="right">
                  <Tooltip title={plan.coverage === null ? 'This plan’s scope is empty.' : ''}>
                    <span>
                      <Identifier>{formatPercent(plan.coverage)}</Identifier>
                    </span>
                  </Tooltip>
                </TableCell>
                <TableCell>
                  {/*
                    The counts come from the summary (005 S5) and the marks from the criteria: the
                    server evaluates the gates in one batched pass over the page, but does not say
                    per criterion which one passed — so the sentence is authoritative and the marks
                    stay honestly grey.
                  */}
                  <CriteriaStrip
                    criteria={plan.exitCriteria ?? []}
                    summary={{ met: plan.exitCriteriaMet, total: plan.exitCriteriaTotal }}
                    testId="Plans.Row.ExitCriteria"
                  />
                </TableCell>
                <TableCell align="right">
                  <ArchiveToggle projectId={projectId} plan={plan} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </ScrollX>
  );
}
