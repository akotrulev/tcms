import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import { useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useFeatureImport, useProject, useSuites } from '../../api/queries';
import type {
  ConditionAction,
  FeatureImportPlan,
  FeatureImportRequest,
  ImportRow,
  OnExisting,
  SuiteAction,
} from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { formatBytes, formatCount, plural } from '../../lib/format';
import { conditionActionLabels, importActionLabels, suiteActionLabels } from '../../lib/labels';
import {
  capsRefusals,
  fromEntry,
  isFeature,
  pathOf,
  readOne,
  type PickedFile,
} from './featureFiles';

/**
 * **Import `.feature` files** (§255) — area D's screen, and capability 3's only surface.
 *
 * ## One request, two modes, one rendering
 *
 * `POST .../feature-imports` takes `{files, suiteId?, onExisting, mode}` and answers the identical
 * `FeatureImportPlan` in both: `PREVIEW` plans and writes nothing, `APPLY` recomputes the same plan
 * inside its transaction and executes it. On a clean run `applied` and the rows' `caseKey`s are the
 * only fields that move — so {@link ImportPlanView} renders both answers, and a preview cannot
 * describe an outcome the apply does not produce. Two components here would be a second decision
 * procedure on the client to go with the server's one, and the preview would eventually lie.
 *
 * There is **no `Idempotency-Key`**, deliberately: determinism replaces it (see `useFeatureImport`
 * in `src/api/queries/cases.ts`).
 *
 * ## `REFUSE` is a row, never a page
 *
 * A caller who holds only `case:read` gets **200** with every row refused and the key they need
 * named on each one. So this screen never renders a 403 for the request itself, and Apply's
 * enablement is derived from the plan — `create + update > 0` — rather than from a permission
 * check. That one expression is what makes both halves true at once: a read-only caller sees the
 * whole plan with Apply off, and a caller with forty importable scenarios beside two refusals still
 * applies the forty. A permission gate on the button would have had to guess which, and would have
 * been wrong in the direction that hides work from somebody who can do it.
 *
 * ## The caps refuse here, before the request
 *
 * The four bounds and the reading live in `featureFiles.ts` beside this file; they mirror
 * `FeatureImportService`'s, and the server is still the authority — it answers `errors.files` for
 * each, and those sentences render beside the drop zone when they arrive. What the client adds is
 * that a 9 MB folder is refused **without being uploaded**: waiting out a multi-megabyte POST to be
 * told the request was too big is a worse answer than the same sentence a moment after the drop.
 * The two agree except on the scenario count, which no client can compute exactly — `countScenarios`
 * says what that costs.
 *
 * ## Not a `DataGrid`
 *
 * The delivery plan asked for one. §241 measured what that costs: **DataGrid does not render in
 * jsdom** — its virtualization measures through a `ResizeObserver` the test shim never fires, so
 * the scroller is 0×0 with no rows, which is why `CaseListPage` (the repo's only DataGrid screen)
 * has no component test at all. Every acceptance criterion here is about what a row says, so this
 * is `Table` + `ScrollX`, as `features/audit/AuditLogPage.tsx` is and for the same reason.
 */

export function FeatureImportPage() {
  const { projectId = '' } = useParams();

  const project = useProject(projectId);
  const suites = useSuites(projectId);
  const importer = useFeatureImport(projectId);

  const [files, setFiles] = useState<PickedFile[]>([]);
  const [suiteId, setSuiteId] = useState('');
  const [onExisting, setOnExisting] = useState<OnExisting>('SKIP');
  const [plan, setPlan] = useState<FeatureImportPlan | null>(null);
  const [dragging, setDragging] = useState(false);
  const lastMode = useRef<FeatureImportRequest['mode']>('PREVIEW');

  const folderInput = useRef<HTMLInputElement | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  /*
    Any change to what would be SENT discards the plan on screen, and resets the mutation with it.

    A preview describes one request. Leaving it up while the caller swaps the suite or flips
    `onExisting` would show a plan for a request nobody is going to make — and Apply, whose whole
    contract is "commit the thing you just read", would then send the other one. Resetting the
    mutation is the second half: a 409 from a lost race must not outlive the request that raised it.
  */
  const discardPlan = () => {
    setPlan(null);
    importer.reset();
  };

  const add = (picked: PickedFile[]) => {
    if (picked.length === 0) return;
    discardPlan();
    // Later wins on a repeated path: re-picking a folder after editing one file is a correction,
    // not a duplicate. The duplicate-path refusal is for two DIFFERENT files that collide, which a
    // merge by path cannot produce and which the server refuses on `errors.files`.
    setFiles((current) => {
      const merged = new Map(current.map((file) => [file.path, file]));
      for (const file of picked) merged.set(file.path, file);
      return [...merged.values()];
    });
  };

  const handleDrop = async (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);

    // Typed `| undefined` on purpose: these are non-nullable in the DOM types and absent in a
    // synthetic drop, and `Array.from` over a union of array types is what leaves `.map` below
    // unresolvable to the compiler.
    const itemList: DataTransferItemList | undefined = event.dataTransfer.items;
    const items: DataTransferItem[] = itemList ? Array.from(itemList) : [];
    const entries = items
      .map((item) => (typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null))
      .filter((entry): entry is FileSystemEntry => entry !== null);

    if (entries.length > 0) {
      const found = await Promise.all(entries.map(fromEntry));
      add(found.flat());
      return;
    }

    // No entry API: the flat file list is all there is, and a folder in it simply is not there.
    // Reading it anyway is what keeps a single-file drop working where the entry API is absent.
    const fileList: FileList | undefined = event.dataTransfer.files;
    const dropped = (fileList ? Array.from(fileList) : []).filter((file) => isFeature(pathOf(file)));
    add(await Promise.all(dropped.map((file) => readOne(file, pathOf(file)))));
  };

  const handlePick = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.target;
    const picked = (input.files ? Array.from(input.files) : []).filter((file) =>
      isFeature(pathOf(file)),
    );
    add(await Promise.all(picked.map((file) => readOne(file, pathOf(file)))));
    // Cleared so re-picking the same folder fires `change` again — without it the second attempt
    // after a mistake is silent. `AttachmentList` carries the same line for the same reason.
    input.value = '';
  };

  const removeFile = (path: string) => {
    discardPlan();
    setFiles((current) => current.filter((file) => file.path !== path));
  };

  const refusals = capsRefusals(files);
  const totalBytes = files.reduce((sum, file) => sum + file.bytes, 0);
  const totalScenarios = files.reduce((sum, file) => sum + file.scenarios, 0);

  const send = async (mode: FeatureImportRequest['mode']) => {
    lastMode.current = mode;
    setPlan(null);
    const request: FeatureImportRequest = {
      files: files.map((file) => ({ path: file.path, content: file.content })),
      ...(suiteId ? { suiteId } : {}),
      onExisting,
      mode,
    };
    try {
      setPlan(await importer.mutateAsync(request));
    } catch {
      // Rendered below from `importer.error`: a refused request is an answer this screen shows in
      // place, and an unhandled rejection beside its own message is noise in the console.
    }
  };

  const failure = importer.error;
  const fieldErrors = failure instanceof ApiError ? failure.fieldErrors : null;
  const fileErrors = fieldErrors?.files;
  const suiteErrors = fieldErrors?.suiteId;
  /*
    A failure the fields already carry must not also raise the region's alert, or the caller reads
    one refusal twice in two voices. Everything else — the 409 a lost race writes, a timeout on
    8 MiB, a 500 — has no field to land on, so it goes to `DataRegion`, which is the one place a
    failure becomes copy (through `lib/describeError.ts`) and offers a retry only where one is
    honest. `duplicate-automation-key` and `duplicate-imported-key` both arrive with a title and a
    detail, so `describeError`'s 409 branch renders the server's own sentence rather than the
    lost-update advice, which would tell the caller to reload a page that has nothing stale on it.
  */
  const unfielded = importer.isError && !fileErrors && !suiteErrors;

  const canApply = plan !== null && !plan.applied && plan.totals.create + plan.totals.update > 0;

  return (
    <Box>
      <PageHeader
        testId="FeatureImport.Header"
        title="Import feature files"
        documentTitle={
          project.data ? `Import feature files · ${project.data.name}` : 'Import feature files'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          { label: 'Import' },
        ]}
        subtitle="Drop a folder of .feature files, read what would happen, then apply it. Nothing is written until you press Apply."
        actions={
          <Button
            component={RouterLink}
            to={`/projects/${projectId}/cases`}
            data-testid="FeatureImport.BackToCases"
          >
            Back to test cases
          </Button>
        }
      />

      <Stack sx={{ gap: 2 }}>
        <Panel title="Files" testId="FeatureImport.FilesPanel">
          <Box sx={{ p: 2 }}>
            {/*
              A drop target and two pickers, because a drop is the gesture and a picker is the way
              back for anybody who cannot make one — a keyboard user, and a browser with no entry
              API. The inputs are hidden rather than absent: an `<input type="file">` is the only
              thing that can open a file dialog, so hiding the button instead would leave the
              screen unusable without a mouse.
            */}
            <Box
              data-testid="FeatureImport.DropZone"
              onDragOver={(event) => {
                event.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => void handleDrop(event)}
              sx={{
                border: '2px dashed',
                borderColor: dragging ? 'primary.main' : 'divider',
                borderRadius: 1,
                bgcolor: dragging ? 'action.hover' : 'transparent',
                p: 3,
                textAlign: 'center',
              }}
            >
              <UploadFileIcon sx={{ color: 'text.secondary' }} />
              <Typography variant="body2" sx={{ mt: 1 }}>
                Drop a folder of <Identifier>.feature</Identifier> files here.
              </Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                Only <Identifier variant="muted">.feature</Identifier> files are read. The path you
                drop is stored on every case as provenance — nothing here fetches it back.
              </Typography>
              <Stack direction="row" sx={{ gap: 1, justifyContent: 'center', mt: 1.5 }}>
                <Button
                  size="small"
                  onClick={() => folderInput.current?.click()}
                  data-testid="FeatureImport.PickFolder"
                >
                  Choose a folder
                </Button>
                <Button
                  size="small"
                  onClick={() => fileInput.current?.click()}
                  data-testid="FeatureImport.PickFiles"
                >
                  Choose files
                </Button>
              </Stack>
              <input
                type="file"
                multiple
                hidden
                ref={(node) => {
                  folderInput.current = node;
                  // `webkitdirectory` has no React prop and no property on `HTMLInputElement`, so
                  // it is set as an attribute — and it is what makes the dialog pick a DIRECTORY
                  // and put `webkitRelativePath` on every file, which is where the provenance path
                  // comes from. A `{...{webkitdirectory: ''}}` spread would not typecheck.
                  node?.setAttribute('webkitdirectory', '');
                }}
                onChange={(event) => void handlePick(event)}
                data-testid="FeatureImport.FolderInput"
                aria-label="Choose a folder of feature files"
              />
              <input
                type="file"
                multiple
                hidden
                accept=".feature"
                ref={fileInput}
                onChange={(event) => void handlePick(event)}
                data-testid="FeatureImport.FileInput"
                aria-label="Choose feature files"
              />
            </Box>

            {files.length > 0 && (
              <Box sx={{ mt: 2 }} data-testid="FeatureImport.FileList">
                <Typography variant="body2" sx={{ mb: 1 }} data-testid="FeatureImport.FileSummary">
                  {plural(files.length, 'file')} · {formatBytes(totalBytes)} ·{' '}
                  {plural(totalScenarios, 'scenario')}
                </Typography>
                <ScrollX>
                  <Table size="small">
                    <TableBody>
                      {files.map((file) => (
                        <TableRow key={file.path} data-testid="FeatureImport.File">
                          <TableCell>
                            <Identifier>{file.path}</Identifier>
                          </TableCell>
                          <TableCell sx={{ width: 120, color: 'text.secondary' }}>
                            {formatBytes(file.bytes)}
                          </TableCell>
                          <TableCell sx={{ width: 80 }}>
                            <Button
                              size="small"
                              startIcon={<DeleteOutlineIcon fontSize="small" />}
                              onClick={() => removeFile(file.path)}
                              data-testid="FeatureImport.RemoveFile"
                              aria-label={`Remove ${file.path}`}
                            >
                              Remove
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </ScrollX>
              </Box>
            )}

            {refusals.length > 0 && (
              <Alert severity="error" sx={{ mt: 2 }} data-testid="FeatureImport.CapRefusal">
                <AlertTitle sx={{ fontSize: '0.875rem', fontWeight: 600 }}>
                  These files cannot be sent
                </AlertTitle>
                {refusals.map((sentence) => (
                  <Typography variant="body2" key={sentence}>
                    {sentence}
                  </Typography>
                ))}
              </Alert>
            )}

            {/* The server's own `errors.files` — it knows things the caps above cannot. */}
            {fileErrors && (
              <Alert severity="error" sx={{ mt: 2 }} data-testid="FeatureImport.FileErrors">
                {fileErrors.map((sentence) => (
                  <Typography variant="body2" key={sentence}>
                    {sentence}
                  </Typography>
                ))}
              </Alert>
            )}
          </Box>
        </Panel>

        <Panel title="Options" testId="FeatureImport.OptionsPanel">
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            sx={{ gap: 3, p: 2, alignItems: 'flex-start' }}
          >
            <Box sx={{ minWidth: 280 }}>
              <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                Where the cases are filed
              </Typography>
              {/*
                The default is "match a suite by feature name", which is what the endpoint does when
                no `suiteId` is sent — and naming one here is the interesting branch: EVERY scenario
                files into it, no suite is matched or created, and `suites[]` comes back empty. That
                belongs on the option rather than in a tooltip nobody opens.
              */}
              <Select
                size="small"
                fullWidth
                displayEmpty
                value={suiteId}
                onChange={(event) => {
                  discardPlan();
                  setSuiteId(event.target.value);
                }}
                error={!!suiteErrors}
                data-testid="FeatureImport.Suite"
                inputProps={{ 'aria-label': 'Which suite the imported cases are filed in' }}
              >
                <MenuItem value="">Match a suite by feature name</MenuItem>
                {(suites.data ?? []).map((suite) => (
                  <MenuItem key={suite.id} value={suite.id}>
                    {suite.name}
                  </MenuItem>
                ))}
              </Select>
              {suiteErrors && (
                <Typography
                  variant="caption"
                  sx={{ color: 'error.main', display: 'block', mt: 0.5 }}
                  data-testid="FeatureImport.SuiteError"
                >
                  {suiteErrors.join(' ')}
                </Typography>
              )}
            </Box>

            <Box>
              <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                A scenario whose case already exists
              </Typography>
              <ToggleButtonGroup
                exclusive
                size="small"
                value={onExisting}
                onChange={(_, next: OnExisting | null) => {
                  if (!next) return;
                  discardPlan();
                  setOnExisting(next);
                }}
                aria-label="What to do with a scenario that already has a case"
              >
                <ToggleButton value="SKIP" data-testid="FeatureImport.OnExisting.SKIP">
                  Leave it alone
                </ToggleButton>
                <ToggleButton value="UPDATE" data-testid="FeatureImport.OnExisting.UPDATE">
                  Rewrite it from the file
                </ToggleButton>
              </ToggleButtonGroup>
            </Box>
          </Stack>
        </Panel>

        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <Button
            variant="contained"
            disabled={files.length === 0 || refusals.length > 0 || importer.isPending}
            onClick={() => void send('PREVIEW')}
            data-testid="FeatureImport.Preview"
          >
            Preview
          </Button>
          <Button
            variant="contained"
            color="secondary"
            disabled={!canApply || importer.isPending}
            onClick={() => void send('APPLY')}
            data-testid="FeatureImport.Apply"
          >
            Apply
          </Button>
          {plan && !plan.applied && !canApply && (
            <Typography
              variant="body2"
              sx={{ color: 'text.secondary' }}
              data-testid="FeatureImport.NothingToApply"
            >
              Nothing in this plan would be created or updated.
            </Typography>
          )}
        </Stack>

        {plan?.applied && (
          <Alert severity="success" data-testid="FeatureImport.Applied">
            <AlertTitle sx={{ fontSize: '0.875rem', fontWeight: 600 }}>Imported</AlertTitle>
            <Typography variant="body2">
              {formatCount(plan.totals.create)} created, {formatCount(plan.totals.update)} updated.
              Every row below carries the key of the case it names.
            </Typography>
          </Alert>
        )}

        {(importer.isPending || plan !== null || unfielded) && (
          <Panel
            title={plan?.applied ? 'What was imported' : 'What would be imported'}
            testId="FeatureImport.PlanPanel"
            dense
          >
            <Box sx={{ p: 1.5 }}>
              {/*
                Mounted only once a request is in flight or an answer exists. `DataRegion` reads an
                undefined `data` as "still loading", which is right for a query that always runs and
                wrong for a mutation nobody has pressed yet — an unconditional region would show a
                skeleton over an empty screen from the moment the page opened.
              */}
              <DataRegion
                testId="FeatureImport.Plan"
                state={{
                  data: plan ?? undefined,
                  isPending: importer.isPending,
                  isError: unfielded,
                  error: importer.error,
                  refetch: () => void send(lastMode.current),
                }}
                isEmpty={(current) => current.scenarios.length === 0}
                skeleton={<GridSkeleton />}
                empty={{
                  kind: 'no-records',
                  title: 'No scenarios',
                  body: 'These files parsed, and none of them holds a scenario to import.',
                }}
              >
                {(current) => <ImportPlanView plan={current} />}
              </DataRegion>
            </Box>
          </Panel>
        )}
      </Stack>
    </Box>
  );
}

/**
 * The plan — **the same component for a preview and for a committed apply**.
 *
 * Nothing here branches on `mode`. `applied` changes one sentence above this component and the
 * `caseKey`s fill in; the rows, the suites, the conditions, the orphans and the totals are read
 * identically, because the server computes them identically. A second rendering keyed on the mode
 * is how a preview starts describing something the apply does not do.
 */
function ImportPlanView({ plan }: { plan: FeatureImportPlan }) {
  return (
    <Stack sx={{ gap: 2 }}>
      <Stack
        direction="row"
        sx={{ gap: 1, flexWrap: 'wrap', alignItems: 'center' }}
        data-testid="FeatureImport.Totals"
        aria-live="polite"
      >
        <Chip
          size="small"
          color="success"
          label={`${formatCount(plan.totals.create)} create`}
          data-testid="FeatureImport.Totals.Create"
        />
        <Chip
          size="small"
          color="info"
          label={`${formatCount(plan.totals.update)} update`}
          data-testid="FeatureImport.Totals.Update"
        />
        <Chip
          size="small"
          label={`${formatCount(plan.totals.skip)} skip`}
          data-testid="FeatureImport.Totals.Skip"
        />
        <Chip
          size="small"
          color="warning"
          label={`${formatCount(plan.totals.refuse)} refused`}
          data-testid="FeatureImport.Totals.Refuse"
        />
      </Stack>

      <ScrollX>
        <Table size="small" data-testid="FeatureImport.Rows">
          <TableHead>
            <TableRow>
              <TableCell sx={{ color: 'text.secondary' }}>Scenario</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 110 }}>Action</TableCell>
              <TableCell sx={{ color: 'text.secondary' }}>Why</TableCell>
              <TableCell sx={{ color: 'text.secondary' }}>Preconditions</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 110 }}>Case</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {plan.scenarios.map((row, index) => (
              <ScenarioRow key={`${row.path}:${row.line ?? 'file'}:${index}`} row={row} />
            ))}
          </TableBody>
        </Table>
      </ScrollX>

      {plan.suites.length > 0 && (
        <Panel title="Suites" testId="FeatureImport.Suites" dense>
          <ScrollX>
            <Table size="small">
              <TableBody>
                {plan.suites.map((suite) => (
                  <SuiteRow key={suite.name} suite={suite} />
                ))}
              </TableBody>
            </Table>
          </ScrollX>
        </Panel>
      )}

      {plan.conditions.length > 0 && (
        <Panel title="Preconditions from the Background" testId="FeatureImport.Conditions" dense>
          <ScrollX>
            <Table size="small">
              <TableBody>
                {plan.conditions.map((condition) => (
                  <ConditionRow key={condition.importedKey} condition={condition} />
                ))}
              </TableBody>
            </Table>
          </ScrollX>
        </Panel>
      )}

      {plan.orphans.length > 0 && (
        <Panel title="Cases these files do not name" testId="FeatureImport.Orphans" dense>
          <Box sx={{ p: 1.5 }}>
            {/*
              Listed, never touched — and saying so is the point of the panel. Whether a missing
              scenario is a rename or a retirement is a question only a person can answer, and
              deleting the case would destroy the runs it appears in on the strength of a file
              somebody edited.
            */}
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
              Nothing is done to these. They are cases in the target suites whose automation key is
              not in the files you picked — a renamed scenario looks exactly like a retired one.
            </Typography>
            <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
              {plan.orphans.map((orphan) => (
                <Chip
                  key={orphan.caseKey}
                  size="small"
                  variant="outlined"
                  data-testid="FeatureImport.Orphan"
                  label={
                    <>
                      <Identifier>{orphan.caseKey}</Identifier> · {orphan.automationKey}
                    </>
                  }
                />
              ))}
            </Stack>
          </Box>
        </Panel>
      )}
    </Stack>
  );
}

/** `CREATE`/`UPDATE` read as progress, `SKIP` as neutral, `REFUSE` as the one that wants attention. */
const actionColor = {
  CREATE: 'success',
  UPDATE: 'info',
  SKIP: 'default',
  REFUSE: 'warning',
} as const;

function ScenarioRow({ row }: { row: ImportRow }) {
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? row.preconditions : row.preconditions.slice(0, 2);
  const hidden = row.preconditions.length - shown.length;

  return (
    <TableRow
      hover
      data-testid="FeatureImport.Row"
      data-action={row.action}
      /*
        A refused row is distinguished by more than its chip's colour: a tint on the row is the
        second cue, and the chip's own TEXT is the non-colour one WCAG 1.4.1 asks for. It never
        blocks anything — Apply is enabled off the totals, so forty importable scenarios import
        beside any number of refusals.
      */
      sx={row.action === 'REFUSE' ? { bgcolor: 'action.hover' } : undefined}
    >
      <TableCell>
        <Typography
          variant="body2"
          sx={{ fontWeight: 500 }}
          data-testid="FeatureImport.Row.Scenario"
        >
          {row.scenario ?? 'The whole file'}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
          {row.feature ? `${row.feature} · ` : ''}
          <Identifier variant="muted">
            {row.path}
            {row.line === null ? '' : `:${row.line}`}
          </Identifier>
        </Typography>
        {row.automationKey && (
          <Identifier variant="muted" testId="FeatureImport.Row.Key">
            {row.automationKey}
          </Identifier>
        )}
      </TableCell>
      <TableCell>
        <Chip
          size="small"
          color={actionColor[row.action]}
          label={importActionLabels[row.action]}
          data-testid="FeatureImport.Row.Action"
        />
      </TableCell>
      <TableCell>
        {/*
          VERBATIM. These sentences are the server's, fixed, listed on `ImportRow.reason` in
          `docs/specs/openapi.yaml`, and clients may match on them — they are the stability a `type`
          slug carries on an error, applied to an outcome that is not one. Re-wording "needs
          library:manage for the Background" through `describeError` would take the permission key
          out of the one place a caller can read it, and `describeError` is for failures: an import
          that refuses a row succeeded.
        */}
        <Typography variant="body2" data-testid="FeatureImport.Row.Reason">
          {row.reason}
        </Typography>
      </TableCell>
      <TableCell>
        {row.preconditions.length === 0 ? (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            None
          </Typography>
        ) : (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap', alignItems: 'center' }}>
            {/*
              Rendered from the ROW, in the order it arrived — the import-owned titles in Background
              order, then the case's human-attached ones. Re-deriving this from the plan's
              `conditions[]` would attach every Background step in the request to every case in it,
              because that list is per REQUEST and this one is per row.
            */}
            {shown.map((title, index) => (
              <Chip
                key={`${title}:${index}`}
                size="small"
                variant="outlined"
                label={title}
                data-testid="FeatureImport.Row.Precondition"
              />
            ))}
            {hidden > 0 && (
              <Button
                size="small"
                onClick={() => setExpanded(true)}
                data-testid="FeatureImport.Row.MorePreconditions"
              >
                +{hidden} more
              </Button>
            )}
          </Stack>
        )}
      </TableCell>
      <TableCell>
        {row.caseKey ? (
          /*
            `/search?q=TC-12`, not `/projects/:projectId/cases/:caseId`, because the plan carries the
            KEY and not the id — and that is deliberate on the contract's side: a key a person reads
            is resolved through the search endpoint, which answers not-found rather than forbidden
            for a case in a project they cannot open (plan §6t). Offered in BOTH modes rather than
            only after an apply: a preview naming an existing case should let a reader go and look
            at the case it is about to skip or rewrite.
          */
          <RouterLink
            to={`/search?q=${encodeURIComponent(row.caseKey)}`}
            data-testid="FeatureImport.Row.CaseKey"
          >
            <Identifier>{row.caseKey}</Identifier>
          </RouterLink>
        ) : (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            —
          </Typography>
        )}
      </TableCell>
    </TableRow>
  );
}

function SuiteRow({ suite }: { suite: SuiteAction }) {
  return (
    <TableRow data-testid="FeatureImport.SuiteRow">
      <TableCell>{suite.name}</TableCell>
      <TableCell sx={{ width: 140 }}>
        <Chip
          size="small"
          color={
            suite.action === 'REFUSE' ? 'warning' : suite.action === 'CREATE' ? 'success' : 'default'
          }
          label={suiteActionLabels[suite.action]}
          data-testid="FeatureImport.SuiteRow.Action"
        />
      </TableCell>
      <TableCell sx={{ color: 'text.secondary' }}>{suite.reason}</TableCell>
    </TableRow>
  );
}

function ConditionRow({ condition }: { condition: ConditionAction }) {
  return (
    <TableRow data-testid="FeatureImport.ConditionRow">
      <TableCell>
        <Typography variant="body2">{condition.title}</Typography>
        <Identifier variant="muted">{condition.importedKey}</Identifier>
      </TableCell>
      <TableCell sx={{ width: 140 }}>
        <Chip
          size="small"
          color={
            condition.action === 'REFUSE'
              ? 'warning'
              : condition.action === 'CREATE'
                ? 'success'
                : 'default'
          }
          label={conditionActionLabels[condition.action]}
          data-testid="FeatureImport.ConditionRow.Action"
        />
      </TableCell>
      <TableCell sx={{ color: 'text.secondary' }}>{condition.reason}</TableCell>
    </TableRow>
  );
}
