import { MoreVertical } from 'lucide-react';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '../../components/ui';
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
      <Table data-testid="Artifacts.Grid">
        <TableHead>
          {/*
            `TableHeaderCell`, not `TableCell`. Built from TableCell this emitted `<td>` inside
            `<thead>`: a screen reader got no column headers at all, and the table was visibly the
            odd one out — sentence case in IBM Plex Sans against mono uppercase on every other
            table in the product, because the header cell is what carries that treatment. Renders
            audit 1.10, and the only accessibility defect it found.
          */}
          <TableRow>
            <TableHeaderCell style={{ width: 130 }}>Category</TableHeaderCell>
            <TableHeaderCell>Artifact</TableHeaderCell>
            <TableHeaderCell style={{ width: 120 }}>Kind</TableHeaderCell>
            <TableHeaderCell style={{ width: 100 }}>Size</TableHeaderCell>
            <TableHeaderCell style={{ width: 170 }}>Updated</TableHeaderCell>
            {/* The action column's header is empty, but it is still a header: an empty `<td>` in
                a header row is what makes the row ambiguous to a screen reader. */}
            <TableHeaderCell style={{ width: 48 }}>
              <span className="sr-only">Actions</span>
            </TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((artifact) => (
            <TableRow key={artifact.id} data-testid="Artifacts.Row">
              <TableCell>
                <Badge tone="plain" >
{humanize(artifact.category)}</Badge>
              </TableCell>
              <TableCell>
                <div className="flex items-center gap-2 flex-wrap">
                  {/*
                    A documentation reference links to the page. Without this the kind plan §6n
                    steers people toward is the one kind the reader cannot open, while the external
                    link beside it is clickable — an asymmetry that pushes people back to pasting
                    URLs, which is the failure the register exists to prevent.
                  */}
                  {artifact.kind === 'DOC_PAGE_REF' && artifact.docPageId ? (
                    <RouterLink className="font-medium text-[13px]" to={`/projects/${projectId}/docs/${artifact.docPageId}`} data-testid="Artifacts.Row.PageLink">
                      {artifact.title}
                    </RouterLink>
                  ) : artifact.kind === 'EXTERNAL_URL' && artifact.url ? (
                    <a className="font-medium text-[13px]" href={artifact.url} target="_blank" rel="noopener noreferrer" data-testid="Artifacts.Row.Link">
                      {artifact.title}
                      <span className="ml-1" aria-hidden="true">
                        ↗
                      </span>
                      <span className="sr-only">
                        (opens in a new tab)
                      </span>
                    </a>
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
                      className="font-medium text-[13px]"
                    >
                      {artifact.title}
                    </FileDownloadLink>
                  ) : (
                    <p className="m-0 text-[13px] font-medium" data-testid="Artifacts.Row.Title">
                      {artifact.title}
                    </p>
                  )}
  
                  {/*
                    The inheritance marker. Without it, someone looking at a plan's register
                    cannot tell which documents belong to their release and which are the
                    project's — and so they duplicate them.
                  */}
                  {showInheritance && artifact.inherited && (
                    <Tooltip>
<TooltipTrigger asChild>
                      {/*
                        `style` rather than a `BadgeTone`: `--color-info` is not one of the kit's
                        eight tones, and mapping it onto the nearest would be this file quietly
                        choosing a different colour. `Badge` spreads the caller's props after its
                        own, so this wins.
                      */}
                      <Badge
                        tone="plain"
                        data-testid="Artifacts.Row.InheritedMarker"
                        style={{
                          backgroundColor: 'transparent',
                          color: 'var(--color-info)',
                          borderColor: 'color-mix(in srgb, var(--color-info) 45%, transparent)',
                        }}
                      >
                        from project
                      </Badge>
                    </TooltipTrigger>
<TooltipContent>Inherited from the project. Editing it affects every plan that inherits it.</TooltipContent>
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
                    <Tooltip>
<TooltipTrigger asChild>
                      <Badge tone="plain"
                        data-testid="Artifacts.Row.Status"
                      >
{artifactStatusLabel[artifact.status]}</Badge>
                    </TooltipTrigger>
<TooltipContent>{statusReason(artifact, replacements)}</TooltipContent>
</Tooltip>
                  )}
                </div>
  
                {artifact.description && (
                  <span className="m-0 text-[12px] text-ink-2 block">
                    {artifact.description}
                  </span>
                )}
                {artifact.filename && (
                  <Identifier variant="muted">{artifact.filename}</Identifier>
                )}
              </TableCell>
              <TableCell className="text-ink-2" style={{ fontSize: '0.75rem' }}>
                {artifact.kind === 'DOC_PAGE_REF'
                  ? 'Documentation page'
                  : artifact.kind === 'EXTERNAL_URL'
                    ? 'External link'
                    : 'File'}
              </TableCell>
              <TableCell>
                <Identifier variant="muted">{formatBytes(artifact.sizeBytes)}</Identifier>
              </TableCell>
              <TableCell className="text-ink-2" style={{ fontSize: '0.75rem' }}>
                <Tooltip>
<TooltipTrigger asChild>
                  <span>{formatAge(artifact.updatedAt)}</span>
                </TooltipTrigger>
<TooltipContent>{formatDateTime(artifact.updatedAt)}</TooltipContent>
</Tooltip>
                <div>by {artifact.updatedBy.displayName}</div>
              </TableCell>
              <TableCell>
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
 * fires (MUI raises no events on a disabled `MenuItem`).
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
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const gate = useCan(projectId);
  const setStatus = useSetArtifactStatus(projectId);

  const planScoped = artifact.scope === 'PLAN';
  const allowed = gate.canOnScope('artifact:edit', planScoped);
  const mayRemove = gate.can('artifact:delete');
  const reason = gate.reasonOnScope('artifact:edit', planScoped) ?? undefined;

  /*
    A sign-off record refuses both content acts, and since C7 the client can say so BEFORE the
    press.

    This is not a permission and must not be mixed into one: the caller holds the key and would
    hold it for any other row. What refuses them is the row's own promise, so it disables the two
    acts and leaves Archive and Restore alone — circulation is not content, and a signed record
    that is out of use is exactly what ARCHIVED says.

    Until `writeOnce` reached the wire (C7) both refusals were `409`s nothing could predict, so the
    register enabled Correct… on a frozen row and answered after the correction had been written —
    §31's disable-don't-hide, unfollowable for want of one boolean.
  */
  const frozen = artifact.writeOnce;
  const frozenReason = frozen
    ? 'This is a sign-off record, so the register keeps it as it was signed. Register a replacement to correct it.'
    : undefined;

  // `setAnchor(null)` used to close the menu by hand in both of these; Radix closes on select, so
  // what is left is the act itself.
  const move = (status: 'ACTIVE' | 'ARCHIVED') => {
    setStatus.mutate({ artifactId: artifact.id, status });
  };

  const open = (act: (value: boolean) => void) => {
    act(true);
  };

  return (
    <>
      {/*
        ~~`anchorEl` + `open` + `onClose`~~ → `DropdownMenu`, T16. Radix anchors the panel to its
        own trigger, so the `anchor` state MUI needed is gone and the button IS the trigger rather
        than a thing that records a coordinate.
      */}
      <DropdownMenu>
        <Tooltip>
          <TooltipTrigger asChild>
            <span>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="quiet"
                  size="icon"
                  // No longer `|| superseded` — see the header. A caller who holds neither key
                  // still gets a disabled button with the gate's reason on it.
                  disabled={(!allowed && !mayRemove) || setStatus.isPending}
                  data-testid="Artifacts.Row.Actions"
                  aria-label={`Actions for ${artifact.title}`}
                >
                  <MoreVertical aria-hidden className="size-4" />
                </Button>
              </DropdownMenuTrigger>
            </span>
          </TooltipTrigger>
          <TooltipContent>{reason ?? 'Artifact actions'}</TooltipContent>
        </Tooltip>
        <DropdownMenuContent align="end">
        <DropdownMenuItem
          disabled={!allowed || frozen}
          onClick={() => open(setEditing)}
          data-testid="Artifacts.Row.Edit"
        >
          Correct…
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={!allowed || artifact.status !== 'ACTIVE'}
          onClick={() => move('ARCHIVED')}
          data-testid="Artifacts.Row.Archive"
        >
          Archive
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={!allowed || artifact.status !== 'ARCHIVED'}
          onClick={() => move('ACTIVE')}
          data-testid="Artifacts.Row.Restore"
        >
          Restore
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={!mayRemove || frozen}
          onClick={() => open(setRemoving)}
          data-testid="Artifacts.Row.Delete"
        >
          Remove…
        </DropdownMenuItem>
        {/*
          Why the row's promise is stated here and not as a tooltip on the two disabled items: a
          disabled menu item raises no events, so a tooltip there is a reason nobody
          can read — the finding this file already records for the superseded case. A line inside
          the menu is read by the person who just found both acts greyed.
        */}
        {frozen && (
          <span className="m-0 text-[12px] block px-4 py-2 text-ink-2" style={{ maxWidth: 280 }} data-testid="Artifacts.Row.WriteOnce">
            {frozenReason}
          </span>
        )}
        </DropdownMenuContent>
      </DropdownMenu>
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
