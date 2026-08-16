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
import { formatCount, formatRatio } from '../../lib/format';
import { planStatusColors, planStatusLabels } from '../../lib/labels';
import { PlanCreateDialog } from './PlanCreateDialog';
import { ScrollX } from '../../components/ScrollX';

/**
 * Test plans. Deliberately a compact table rather than a card grid: a lead comparing four
 * plans wants the pass rate and coverage columns to line up so the eye can scan down them,
 * which cards make impossible.
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
            Stays on the list, and that is the live browser pass talking rather than a preference.
            `/plans/:planId` is the plan STATISTICS route, and that endpoint is deliberately
            unimplemented server-side (§38: `bySuite` needs the live suite tree, `coveragePercent`
            a repository-wide denominator, `trend` a `reporting` module that does not exist). So
            navigating there on success showed the user a red "Something didn't load" toast and a
            "Not found" panel one second after their plan was created successfully — the create
            worked and the app said it had not.

            The list is the honest destination until statistics exists: the new plan is the first
            row, with the case count its scope expands to. Point this back at the plan the day
            that endpoint is served.
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
                <TableCell align="right">
                  <Identifier>{formatRatio(plan.passRate)}</Identifier>
                </TableCell>
                <TableCell align="right">
                  <Identifier>{formatRatio(plan.coverage)}</Identifier>
                </TableCell>
                <TableCell>
                  <CriteriaStrip criteria={plan.exitCriteria ?? []} testId="Plans.Row.ExitCriteria" />
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
