import { Box, Button, Chip, MenuItem, Select, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { Artifact } from '../../api/types';
import { useArtifacts, useDocPages, usePlans, useProject } from '../../api/queries';
import { PermissionGate } from '../../components/PermissionGate';
import { RegisterArtifactDialog } from './ArtifactDialogs';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatAge, formatBytes, formatDateTime } from '../../lib/format';
import { humanize } from '../../lib/labels';
import { ScrollX } from '../../components/ScrollX';

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
              Disabled-with-a-reason rather than hidden (§31), and the gate is honest only about
              the half it can see: `artifact:edit` is keys-only on the wire, so a Test Engineer —
              who holds it for plan scope alone — gets an ENABLED button here and a refusal on the
              scope field if they choose the project. The dialog is written for that, and the
              server answers that one 403 with a body so the message lands on the control.
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

function ArtifactTable({
  rows,
  showInheritance,
  projectId,
}: {
  rows: Artifact[];
  showInheritance: boolean;
  /** For the documentation link — the register is the only place a doc page is linked from. */
  projectId: string;
}) {
  // Grouped by category with a deterministic order inside each group. Category is how people
  // look for these ("where's the environment guide?"), not the upload date.
  const sorted = [...rows].sort(
    (a, b) =>
      a.category.localeCompare(b.category) ||
      Number(a.inherited) - Number(b.inherited) ||
      a.title.localeCompare(b.title),
  );

  return (
    <ScrollX>
      <Table size="small" data-testid="Artifacts.Grid">
        <TableHead>
          <TableRow>
            <TableCell sx={{ color: 'text.secondary', width: 130 }}>Category</TableCell>
            <TableCell sx={{ color: 'text.secondary' }}>Artifact</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 120 }}>Kind</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 100 }} align="right">
              Size
            </TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 170 }}>Updated</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((artifact) => (
            <TableRow key={artifact.id} data-testid="Artifacts.Row" hover>
              <TableCell>
                <Chip size="small" variant="outlined" label={humanize(artifact.category)} />
              </TableCell>
              <TableCell>
                <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                  {/*
                    A documentation reference links to the page. Without this the kind plan §6n
                    steers people toward is the one kind the reader cannot open, while the external
                    link beside it is clickable — an asymmetry that pushes people back to pasting
                    URLs, which is the failure the register exists to prevent.
                  */}
                  {artifact.kind === 'DOC_PAGE_REF' && artifact.docPageId ? (
                    <Box
                      component={RouterLink}
                      to={`/projects/${projectId}/docs/${artifact.docPageId}`}
                      data-testid="Artifacts.Row.PageLink"
                      sx={{ fontWeight: 500, fontSize: '0.8125rem' }}
                    >
                      {artifact.title}
                    </Box>
                  ) : artifact.kind === 'EXTERNAL_URL' && artifact.url ? (
                    <Box
                      component="a"
                      href={artifact.url}
                      target="_blank"
                      rel="noopener"
                      data-testid="Artifacts.Row.Link"
                      sx={{ fontWeight: 500, fontSize: '0.8125rem' }}
                    >
                      {artifact.title}
                      <Box component="span" aria-hidden="true" sx={{ ml: 0.5 }}>
                        ↗
                      </Box>
                      <Box component="span" className="sr-only">
                        (opens in a new tab)
                      </Box>
                    </Box>
                  ) : (
                    <Typography variant="body2" sx={{ fontWeight: 500 }} data-testid="Artifacts.Row.Title">
                      {artifact.title}
                    </Typography>
                  )}
  
                  {/*
                    The inheritance marker. Without it, someone looking at a plan's register
                    cannot tell which documents belong to their release and which are the
                    project's — and so they duplicate them.
                  */}
                  {showInheritance && artifact.inherited && (
                    <Tooltip title="Inherited from the project. Editing it affects every plan that inherits it.">
                      <Chip
                        size="small"
                        label="from project"
                        data-testid="Artifacts.Row.InheritedMarker"
                        sx={{
                          bgcolor: 'transparent',
                          color: 'var(--color-info)',
                          border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
                        }}
                      />
                    </Tooltip>
                  )}
                  {artifact.status !== 'ACTIVE' && (
                    <Chip size="small" variant="outlined" label={artifact.status.toLowerCase()} />
                  )}
                </Stack>
  
                {artifact.description && (
                  <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                    {artifact.description}
                  </Typography>
                )}
                {artifact.filename && (
                  <Identifier variant="muted">{artifact.filename}</Identifier>
                )}
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                {artifact.kind === 'DOC_PAGE_REF'
                  ? 'Documentation page'
                  : artifact.kind === 'EXTERNAL_URL'
                    ? 'External link'
                    : 'File'}
              </TableCell>
              <TableCell align="right">
                <Identifier variant="muted">{formatBytes(artifact.sizeBytes)}</Identifier>
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                <Tooltip title={formatDateTime(artifact.updatedAt)}>
                  <Box component="span">{formatAge(artifact.updatedAt)}</Box>
                </Tooltip>
                <Box>by {artifact.updatedBy.displayName}</Box>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}
