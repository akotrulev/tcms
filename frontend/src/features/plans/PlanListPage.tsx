import {
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { TestPlan } from '../../api/types';
import { usePlans, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { formatCount, formatRatio } from '../../lib/format';
import { PlanCreateDialog } from './PlanCreateDialog';

/**
 * Test plans. Deliberately a compact table rather than a card grid: a lead comparing four
 * plans wants the pass rate and coverage columns to line up so the eye can scan down them,
 * which cards make impossible.
 */
export function PlanListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const plans = usePlans(projectId);
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
          /*
            Disabled with a reason rather than hidden (§31): a Viewer seeing a greyed "New plan"
            learns the capability exists and is worth asking for. `plan:create` is PROJECT scope —
            unlike "New project", which is the app's one company-scope affordance — so this asks
            the project question and the gate matches the server's.
          */
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

const statusColor: Record<string, string> = {
  ACTIVE: 'var(--color-passed)',
  DRAFT: 'var(--color-draft)',
  CLOSED: 'var(--color-skipped)',
  ARCHIVED: 'var(--color-deprecated)',
};

function PlanTable({ rows, projectId }: { rows: TestPlan[]; projectId: string }) {
  // Deterministic order with a unique tiebreaker: active plans first, then by milestone
  // date, then by key — so the list never reshuffles between loads.
  const sorted = [...rows].sort((a, b) => {
    const rank = (plan: TestPlan) => (plan.status === 'ACTIVE' ? 0 : plan.status === 'DRAFT' ? 1 : 2);
    return (
      rank(a) - rank(b) ||
      (b.milestoneDate ?? '').localeCompare(a.milestoneDate ?? '') ||
      a.key.localeCompare(b.key)
    );
  });

  return (
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
          <TableCell sx={{ color: 'text.secondary', width: 110 }} align="right">
            Exit criteria
          </TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {sorted.map((plan) => {
          const unmet = (plan.exitCriteria ?? []).filter((c) => !c.met).length;
          const criteriaCount = (plan.exitCriteria ?? []).length;

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
                </Stack>
              </TableCell>
              <TableCell>
                <Chip
                  size="small"
                  label={plan.status.toLowerCase()}
                  data-testid="Plans.Row.Status"
                  sx={{
                    bgcolor: 'transparent',
                    color: statusColor[plan.status] ?? 'text.secondary',
                    border: `1px solid color-mix(in srgb, ${statusColor[plan.status] ?? 'var(--color-border)'} 45%, transparent)`,
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
              <TableCell align="right">
                {criteriaCount === 0 ? (
                  <Box sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>None set</Box>
                ) : (
                  <Chip
                    size="small"
                    label={unmet === 0 ? 'All met' : `${unmet} unmet`}
                    data-testid="Plans.Row.ExitCriteria"
                    sx={{
                      bgcolor: 'transparent',
                      color: unmet === 0 ? 'var(--color-passed)' : 'var(--color-failed)',
                      border: `1px solid color-mix(in srgb, ${
                        unmet === 0 ? 'var(--color-passed)' : 'var(--color-failed)'
                      } 45%, transparent)`,
                    }}
                  />
                )}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
