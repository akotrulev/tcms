import MoreVertIcon from '@mui/icons-material/MoreVert';
import { Box, Chip, IconButton, Menu, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useSetArtifactStatus } from '../../api/queries';
import { DeleteArtifactDialog, EditArtifactDialog } from './ArtifactDialogs';
import type { Artifact } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { FileDownloadLink } from '../../components/FileDownloadLink';
import { Identifier } from '../../components/Identifier';
import { formatAge, formatBytes, formatDateTime } from '../../lib/format';
import { artifactStatusLabel, humanize } from '../../lib/labels';
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

  const replacements = new Map(rows.map((artifact) => [artifact.id, artifact.title]));

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
            <TableCell sx={{ width: 48 }} />
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
                  ) : artifact.kind === 'FILE' && artifact.url ? (
                    /*
                      An uploaded document downloads, and it is a BUTTON rather than an anchor.

                      The session is a bearer token, so `<a href="/api/v1/…">` sends no credential
                      and answers 401 while rendering as a working link — `FileDownloadLink` owns
                      that whole problem, and this row is why it was extracted rather than copied
                      out of the attachment panel.

                      Keyed on `url` as well as `kind`, like the two branches above: the field is
                      nullable on the wire and a `FILE` row that somehow carries none must render as
                      a title, not as a control that cannot work.
                    */
                    <FileDownloadLink
                      url={artifact.url}
                      // The uploaded name when there is one — the server sends this artifact
                      // `Content-Disposition: attachment` with it, so the fallback is only reached
                      // if that header goes missing. The title is the honest second answer; a name
                      // invented from the media type would not be.
                      filename={artifact.filename ?? artifact.title}
                      testId="Artifacts.Row.Download"
                      sx={{ fontWeight: 500, fontSize: '0.8125rem' }}
                    >
                      {artifact.title}
                    </FileDownloadLink>
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
                  {/*
                    The status, and — for a superseded row — WHAT replaced it. A status the reader
                    cannot follow is a dead end: "Superseded" alone sends somebody scanning the
                    register for a title they have to guess. The replacement is at the same scope by
                    construction, so it is in `rows` and no second request is needed; the fallback
                    covers the one case it is not, a plan register showing an inherited row.
                  */}
                  {artifact.status !== 'ACTIVE' && (
                    <Tooltip title={statusReason(artifact, replacements)}>
                      <Chip
                        size="small"
                        variant="outlined"
                        label={artifactStatusLabel[artifact.status]}
                        data-testid="Artifacts.Row.Status"
                      />
                    </Tooltip>
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
              <TableCell align="right">
                <ArtifactRowActions artifact={artifact} projectId={projectId} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}

/** Why a row is not current, named rather than merely stated. */
function statusReason(artifact: Artifact, titles: Map<string, string>): string {
  if (artifact.status !== 'SUPERSEDED') {
    return 'Taken out of circulation. It stays in the register for the record.';
  }
  const replacement = artifact.supersededById ? titles.get(artifact.supersededById) : undefined;
  return replacement
    ? `Replaced by “${replacement}”.`
    : 'Replaced by a newer artifact in this project.';
}

/**
 * Correct, Archive, Restore and Remove for one row.
 *
 * ## Every item is always rendered, and each is enabled by the row's own state
 *
 * §31's disable-don't-hide, and `ui-rules` §4's other half — one control whose label flips between
 * Archive and Restore is the meaning-swapping toggle that rule names, so the two acts are two items
 * and the state decides which is live.
 *
 * ## ~~A superseded row disables the whole menu~~ → only the two status items (A6)
 *
 * It did, and it was right while the menu was Archive and Restore: `SUPERSEDED` is a fact about
 * *another* row, so neither act has anywhere to go and the server's own 409 says so. **Correcting a
 * title is not a status act**, and the server does not refuse it on a superseded row for exactly
 * that reason — so a kebab that stays shut would put the register's one edit path out of reach on
 * every row somebody has ever replaced. The button opens; the two status items are greyed by the
 * status test they already had, and *why* the row is superseded is the sentence already on its
 * status chip beside the title — which is where a reader looks, and where a tooltip actually
 * fires (MUI raises no hover events on a disabled `MenuItem`).
 *
 * ## The two permissions are asked differently, and the difference is the schema's
 *
 * `canOnScope('artifact:edit', rowIsPlanScoped)` is exactly what the server evaluates for the first
 * three items: `V19` made that key qualifiable, and although nothing carries `PLAN_SCOPE` since
 * `V52` the mechanism is retained deliberately (roles spec §A5), so asking the plain key would be a
 * client that fails **open** the day a migration qualifies it again. `artifact:delete` is
 * unqualified for everyone who holds it — `V19`'s own comment says so and
 * `ArtifactWriteService.delete` gates it with plain `writeGranted` — so Remove asks `can`, and
 * asking it by scope would invent a narrowing the server does not have.
 *
 * ## Archive does not confirm and Remove does
 *
 * Archiving destroys nothing, the row stays on screen with its new status, and the way back is the
 * item directly beneath the one just pressed — `ui-rules` §7 refuses a blocking modal where the
 * interruption is not the value. ~~**Removal** — which is destructive, and still has no control
 * anywhere in the SPA — is where that dialog belongs.~~ → **A6: it has one, and that is where the
 * dialog went.** See `DeleteArtifactDialog`, which names what a `FILE`'s removal costs and points
 * at Archive for the case somebody actually meant.
 */
function ArtifactRowActions({ artifact, projectId }: { artifact: Artifact; projectId: string }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const gate = useCan(projectId);
  const setStatus = useSetArtifactStatus(projectId);

  const planScoped = artifact.scope === 'PLAN';
  const allowed = gate.canOnScope('artifact:edit', planScoped);
  const mayRemove = gate.can('artifact:delete');
  const reason = gate.reasonOnScope('artifact:edit', planScoped) ?? undefined;

  const move = (status: 'ACTIVE' | 'ARCHIVED') => {
    setAnchor(null);
    setStatus.mutate({ artifactId: artifact.id, status });
  };

  const open = (act: (value: boolean) => void) => {
    setAnchor(null);
    act(true);
  };

  return (
    <>
      <Tooltip title={reason ?? 'Artifact actions'}>
        <Box component="span">
          <IconButton
            size="small"
            // No longer `|| superseded` — see the header. A caller who holds neither key still
            // gets a disabled button with the gate's reason on it.
            disabled={(!allowed && !mayRemove) || setStatus.isPending}
            onClick={(event) => setAnchor(event.currentTarget)}
            data-testid="Artifacts.Row.Actions"
            aria-label={`Actions for ${artifact.title}`}
          >
            <MoreVertIcon fontSize="small" />
          </IconButton>
        </Box>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
        <MenuItem
          disabled={!allowed}
          onClick={() => open(setEditing)}
          data-testid="Artifacts.Row.Edit"
        >
          Correct…
        </MenuItem>
        <MenuItem
          disabled={!allowed || artifact.status !== 'ACTIVE'}
          onClick={() => move('ARCHIVED')}
          data-testid="Artifacts.Row.Archive"
        >
          Archive
        </MenuItem>
        <MenuItem
          disabled={!allowed || artifact.status !== 'ARCHIVED'}
          onClick={() => move('ACTIVE')}
          data-testid="Artifacts.Row.Restore"
        >
          Restore
        </MenuItem>
        <MenuItem
          disabled={!mayRemove}
          onClick={() => open(setRemoving)}
          data-testid="Artifacts.Row.Delete"
        >
          Remove…
        </MenuItem>
      </Menu>
      <EditArtifactDialog
        open={editing}
        onClose={() => setEditing(false)}
        projectId={projectId}
        artifact={artifact}
      />
      <DeleteArtifactDialog
        open={removing}
        onClose={() => setRemoving(false)}
        projectId={projectId}
        artifact={artifact}
      />
    </>
  );
}
