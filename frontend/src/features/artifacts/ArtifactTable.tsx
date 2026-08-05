import { Box, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import type { Artifact } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { formatAge, formatBytes, formatDateTime } from '../../lib/format';
import { humanize } from '../../lib/labels';
import { ScrollX } from '../../components/ScrollX';

/**
 * The register, rendered.
 *
 * Exported since §97 phase 4 for the plan detail's **Test artefacts** tab: a plan's register is the
 * same rows read through a plan-scoped query, and a second table beside this one would be `ui-rules`
 * §8's renderer divergence — two places implementing the inheritance marker, the category grouping
 * and the doc-page link, drifting from the first change onward.
 */
export function ArtifactTable({
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
