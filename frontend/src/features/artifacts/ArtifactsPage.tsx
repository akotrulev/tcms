import { Box, Button, MenuItem, Select, Stack } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useArtifacts, useDocPages, usePlans, useProject } from '../../api/queries';
import { PermissionGate } from '../../components/PermissionGate';
import { RegisterArtifactDialog } from './ArtifactDialogs';
import { ArtifactTable } from './ArtifactTable';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';

/**
 * The artifact register (plan §6n).
 *
 * Artifacts exist at two scopes — the project's (a test strategy, an environment guide) and a
 * plan's (a release sign-off, an environment snapshot). Plan scope **inherits** project
 * scope, and the inheritance has to be marked, because the alternative is that every release
 * re-uploads its own copy of the same environment guide and they immediately diverge.
 */
export function ArtifactsPage() {
  const { projectId = '' } = useParams();
  const [planId, setPlanId] = useState<string>('');
  const [registering, setRegistering] = useState(false);

  const project = useProject(projectId);
  const plans = usePlans(projectId);
  const artifacts = useArtifacts(projectId, planId ? 'PLAN' : 'PROJECT', planId || undefined);
  // For the register dialog's documentation picker. Cheap and already cached whenever the reader
  // has visited Documentation; the register is where a doc page gets promoted to a project asset.
  const pages = useDocPages(projectId);

  return (
    <Box>
      <PageHeader
        testId="Artifacts.Header"
        title="Artifacts"
        documentTitle={project.data ? `Artifacts · ${project.data.name}` : 'Artifacts'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Artifacts' },
        ]}
        subtitle="Strategy, environment, test data and sign-off documents. Plan artifacts sit alongside the project's, which they inherit."
        actions={
          <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
            <Box sx={{ minWidth: 260 }}>
            <Select
              size="small"
              fullWidth
              displayEmpty
              value={planId}
              onChange={(event) => setPlanId(event.target.value)}
              data-testid="Artifacts.ScopeSelect"
              inputProps={{ 'aria-label': 'Which scope to show' }}
            >
              <MenuItem value="">Project scope only</MenuItem>
              {(plans.data ?? []).map((plan) => (
                <MenuItem key={plan.id} value={plan.id}>
                  {plan.key} — {plan.name}
                </MenuItem>
              ))}
            </Select>
            </Box>
            {/*
              Disabled-with-a-reason rather than hidden (§31). ~~The gate is honest only about
              the half it can see: `artifact:edit` is keys-only on the wire, so a Test Engineer —
              who holds it for plan scope alone — gets an ENABLED button here and a refusal on the
              scope field if they choose the project.~~ → **`V52` (§210) de-qualified the key**, so
              the key IS the whole answer now and the button is honest on its own. The dialog still
              renders the scope-shaped 403 — see its header for why a form that can no longer
              produce a refusal must still handle one.
            */}
            <PermissionGate projectId={projectId} permission="artifact:edit">
              {({ allowed }) => (
                <Button
                  variant="contained"
                  startIcon={<AddIcon />}
                  disabled={!allowed}
                  onClick={() => setRegistering(true)}
                  data-testid="Artifacts.Register"
                >
                  Register
                </Button>
              )}
            </PermissionGate>
          </Stack>
        }
      />

      {registering && (
        <RegisterArtifactDialog
          open
          onClose={() => setRegistering(false)}
          projectId={projectId}
          plans={plans.data ?? []}
          pages={pages.data ?? []}
          // Continues what the reader was doing: viewing Release 1's register and pressing
          // Register means Release 1, not "start again and tell us which cycle".
          defaultPlanId={planId || undefined}
          onRegistered={() => undefined}
        />
      )}

      <Panel testId="Artifacts.Panel" dense>
        <DataRegion
          testId="Artifacts.List"
          state={{ ...artifacts, refetch: () => void artifacts.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No artifacts',
            body: planId
              ? 'This plan has no artifacts of its own, and the project has none to inherit.'
              : 'Attach the test strategy, environment guide and test data this project depends on.',
          }}
        >
          {(rows) => (
            <ArtifactTable rows={rows} showInheritance={!!planId} projectId={projectId} />
          )}
        </DataRegion>
      </Panel>
    </Box>
  );
}
