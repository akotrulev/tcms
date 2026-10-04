import { FileText, Trash2, Upload } from 'lucide-react';
import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
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
import { Badge, Banner, type BadgeTone } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { cn } from '../../lib/cn';
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

/** `.sec h3` from `renders/tcms.css` — one spelling of a rail section heading on this screen. */
const SECTION_HEADING =
  'm-0 mb-2.5 flex items-baseline gap-2 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * **Import `.feature` files** (§255) — area D's screen, and capability 3's only surface, drawn as
 * `renders/05-authoring.html` frame C: *a review before commit — files left, what will happen
 * centre, what it costs right.*
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
 * ## A scenario is a card, not a table row
 *
 * ~~`Table` + `ScrollX`, as `features/audit/AuditLogPage.tsx` is — the delivery plan asked for a
 * `DataGrid` and §241 measured what that costs.~~ → **superseded by 007 T14.** Five columns made
 * the reader carry a scenario's verdict, its reason and its preconditions across a horizontal
 * scroll; the render's `.scn` card puts the three under each other, which is what "a review before
 * commit" needs. The DataGrid finding stands and is now doubly moot — 007 T9 replaced that engine
 * anyway.
 *
 * **What the render asks for and the contract cannot give:** a per-step diff, struck-through old
 * text beside new. `ImportRow` in `docs/specs/openapi.yaml` carries no steps and no diff — path,
 * line, feature, scenario, automationKey, action, reason, caseKey, preconditions, and nothing
 * else. So the "diff not count" rule is honoured as far as the wire allows: every scenario is
 * named with what will happen to it and why, the preconditions it would gain are listed by title,
 * and the counts are a summary beside the rows rather than a substitute for them. The step diff is
 * a contract change and belongs in the plan that makes it.
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
    ~~Raised by `PageHeader`.~~ → superseded by 007 T14, which replaced that header with the
    render's own top strip. Kept rather than dropped: a route change that announces nothing leaves
    a screen-reader user on a page they have no way to know has changed.
  */
  const announce = useAnnounce();
  useEffect(() => {
    announce('Import feature files page loaded');
  }, [announce]);

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

  const willWrite = plan ? plan.totals.create + plan.totals.update : 0;
  const canApply = plan !== null && !plan.applied && willWrite > 0;

  return (
    <div>
      <title>
        {project.data
          ? `Import feature files · ${project.data.name} · TCMS`
          : 'Import feature files · TCMS'}
      </title>

      <header className="mb-3 flex flex-wrap items-center gap-2 border-b border-line pb-3">
        <div className="min-w-0 flex-1">
          <h1 className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink">
            Import feature files
          </h1>
        </div>

        {/* The render's quiet promise beside the two buttons, so it is read at the moment of
            deciding rather than in a paragraph above the fold. */}
        <span className="text-[12px] text-ink-4">Nothing is written until you import</span>
        <Button asChild size="sm" data-testid="FeatureImport.BackToCases">
          <RouterLink to={`/projects/${projectId}/cases`}>Cancel</RouterLink>
        </Button>
        <Button
          size="sm"
          disabled={files.length === 0 || refusals.length > 0 || importer.isPending}
          onClick={() => void send('PREVIEW')}
          data-testid="FeatureImport.Preview"
        >
          Preview
        </Button>
        {/*
          **The button counts what it will do.** *"A verb with no quantity is the same button
          whether it writes nothing or rewrites a suite."* The quantity is the rows it would
          WRITE — creates plus updates — because that is the blast radius; the skipped rows are
          named in the caption beside the totals, where they belong.
        */}
        <Button
          variant="primary"
          size="sm"
          disabled={!canApply || importer.isPending}
          onClick={() => void send('APPLY')}
          data-testid="FeatureImport.Apply"
        >
          {plan ? `Import ${plural(willWrite, 'scenario')}` : 'Import'}
        </Button>
      </header>

      {plan && !plan.applied && !canApply && (
        <p className="mt-0 mb-3 text-[13px] text-ink-3" data-testid="FeatureImport.NothingToApply">
          Nothing in this plan would be created or updated.
        </p>
      )}

      {plan?.applied && (
        <div className="mb-3" data-testid="FeatureImport.Applied">
          <Banner tone="steel">
            <span>
              <b className="text-ink">Imported.</b> {formatCount(plan.totals.create)} created,{' '}
              {formatCount(plan.totals.update)} updated. Every row below carries the key of the case
              it names.
            </span>
          </Banner>
        </div>
      )}

      {/*
        Three columns from `lg`, not from `xl` — render C: *"files left, what will happen centre,
        what it costs right"*. At `xl` the three panes only appeared at 1280 and above, so the
        screen the audit walked read as one stacked column (renders audit 5.2) and the render's
        whole point — that the cost sits BESIDE the plan it is the cost of — was lost to scrolling.
        The two sides are 280 and 260 fixed, so from 1024 the centre still gets ~460px, which is
        enough for the row list it holds.
      */}
      <div className="flex flex-col items-start gap-4 lg:flex-row">
        {/* ── The render's left `.side`: what was picked, and how it will be filed ───────── */}
        <aside className="w-full shrink-0 rounded-lg border border-line bg-panel p-4 lg:w-[280px]">
          <section className="mb-5" data-testid="FeatureImport.FilesPanel">
            <h2 className={SECTION_HEADING}>
              Files
              <span className="font-sans text-[11px] font-normal tracking-normal text-ink-4 normal-case">
                {files.length}
              </span>
            </h2>

            {/*
              A drop target and two pickers, because a drop is the gesture and a picker is the way
              back for anybody who cannot make one — a keyboard user, and a browser with no entry
              API. The inputs are hidden rather than absent: an `<input type="file">` is the only
              thing that can open a file dialog, so hiding the button instead would leave the
              screen unusable without a mouse.
            */}
            <div
              data-testid="FeatureImport.DropZone"
              onDragOver={(event) => {
                event.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => void handleDrop(event)}
              className={cn(
                'rounded-lg border-2 border-dashed p-4 text-center',
                dragging ? 'border-steel bg-wash-2' : 'border-line-strong',
              )}
            >
              <Upload aria-hidden className="mx-auto size-[34px] text-ink-4" />
              <p className="m-0 mt-1.5 text-[12.5px] text-ink-2">
                Drop a folder of <Identifier>.feature</Identifier> files here.
              </p>
              <p className="m-0 mt-1 text-[11.5px] leading-[1.5] text-ink-4">
                Only <Identifier variant="muted">.feature</Identifier> files are read. The path you
                drop is stored on every case as provenance — nothing here fetches it back.
              </p>
              <div className="mt-2 flex flex-wrap justify-center gap-1.5">
                <Button
                  size="sm"
                  onClick={() => folderInput.current?.click()}
                  data-testid="FeatureImport.PickFolder"
                >
                  Choose a folder
                </Button>
                <Button
                  size="sm"
                  onClick={() => fileInput.current?.click()}
                  data-testid="FeatureImport.PickFiles"
                >
                  Choose files
                </Button>
              </div>
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
            </div>

            {files.length > 0 && (
              <div className="mt-3" data-testid="FeatureImport.FileList">
                <p className="m-0 mb-1.5 text-[12px] text-ink-4" data-testid="FeatureImport.FileSummary">
                  {plural(files.length, 'file')} · {formatBytes(totalBytes)} ·{' '}
                  {plural(totalScenarios, 'scenario')}
                </p>
                {/* The render's `.fileln` — path, then its scenario count in the mono gutter. */}
                {files.map((file) => (
                  <div
                    key={file.path}
                    data-testid="FeatureImport.File"
                    className="flex items-center gap-2 rounded-md px-1.5 py-1 text-[12.5px] text-ink-2 hover:bg-wash-1"
                  >
                    <FileText aria-hidden className="size-3.5 shrink-0 text-ink-4" />
                    <span className="min-w-0 flex-1 truncate" title={file.path}>
                      {file.path}
                    </span>
                    <span className="shrink-0 font-mono text-[10.5px] text-ink-4">
                      {file.scenarios}
                    </span>
                    <Button
                      size="icon"
                      variant="quiet"
                      onClick={() => removeFile(file.path)}
                      data-testid="FeatureImport.RemoveFile"
                      aria-label={`Remove ${file.path}`}
                    >
                      <Trash2 aria-hidden className="size-3.5" />
                    </Button>
                  </div>
                ))}
              </div>
            )}

            {refusals.length > 0 && (
              <div className="mt-3" data-testid="FeatureImport.CapRefusal">
                <Banner tone="bad">
                  <span>
                    <b className="text-ink">These files cannot be sent.</b>{' '}
                    {refusals.map((sentence) => (
                      <span key={sentence} className="block">
                        {sentence}
                      </span>
                    ))}
                  </span>
                </Banner>
              </div>
            )}

            {/* The server's own `errors.files` — it knows things the caps above cannot. */}
            {fileErrors && (
              <div className="mt-3" data-testid="FeatureImport.FileErrors">
                <Banner tone="bad">
                  <span>
                    {fileErrors.map((sentence) => (
                      <span key={sentence} className="block">
                        {sentence}
                      </span>
                    ))}
                  </span>
                </Banner>
              </div>
            )}
          </section>

          <section data-testid="FeatureImport.OptionsPanel">
            <h2 className={SECTION_HEADING}>Options</h2>

            {/*
              The default is "match a suite by feature name", which is what the endpoint does when
              no `suiteId` is sent — and naming one here is the interesting branch: EVERY scenario
              files into it, no suite is matched or created, and `suites[]` comes back empty. That
              belongs on the option rather than in a tooltip nobody opens.
            */}
            <Field label="Where the cases are filed" htmlFor="feature-import-suite">
              <NativeSelect
                id="feature-import-suite"
                value={suiteId}
                onChange={(event) => {
                  discardPlan();
                  setSuiteId(event.target.value);
                }}
                aria-label="Which suite the imported cases are filed in"
                data-testid="FeatureImport.Suite"
              >
                <option value="">Match a suite by feature name</option>
                {(suites.data ?? []).map((suite) => (
                  <option key={suite.id} value={suite.id}>
                    {suite.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            {/*
              Its own element rather than `Field`'s `error` slot, because the locator has to land
              on the sentence: `Field` renders the message it is given and has nowhere to put a
              `data-testid`, and a refusal the suite select caused is the one a caller re-reads.
            */}
            {suiteErrors && (
              <p
                role="alert"
                data-testid="FeatureImport.SuiteError"
                className="m-0 mt-1 text-[12px] text-[color:var(--color-failed)]"
              >
                {suiteErrors.join(' ')}
              </p>
            )}

            <p className="mt-3 mb-1 text-[12px] text-ink-4">
              A scenario whose case already exists
            </p>
            <div
              role="group"
              aria-label="What to do with a scenario that already has a case"
              className="inline-flex gap-0.5 rounded-lg bg-wash-1 p-0.5"
            >
              {(
                [
                  { value: 'SKIP', label: 'Leave it alone' },
                  { value: 'UPDATE', label: 'Rewrite it from the file' },
                ] as const
              ).map((option) => (
                <button
                  key={option.value}
                  type="button"
                  aria-pressed={onExisting === option.value}
                  onClick={() => {
                    discardPlan();
                    setOnExisting(option.value);
                  }}
                  data-testid={`FeatureImport.OnExisting.${option.value}`}
                  className={cn(
                    'cursor-pointer rounded-md border-0 px-[11px] py-1 text-[12px] font-medium',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                    onExisting === option.value
                      ? 'bg-panel text-ink shadow-e1'
                      : 'bg-transparent text-ink-3 hover:text-ink-2',
                  )}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </section>
        </aside>

        {/* ── The centre pane: what will happen, one card per scenario ───────────────────── */}
        <div className="min-w-0 flex-1" data-testid="FeatureImport.PlanPanel">
          {importer.isPending || plan !== null || unfielded ? (
            /*
              Mounted only once a request is in flight or an answer exists. `DataRegion` reads an
              undefined `data` as "still loading", which is right for a query that always runs and
              wrong for a mutation nobody has pressed yet — an unconditional region would show a
              skeleton over an empty screen from the moment the page opened.
            */
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
          ) : (
            <p className="m-0 rounded-lg border border-line bg-panel p-4 text-[13px] text-ink-3">
              Pick some files and press Preview. Every scenario in them gets a card here saying what
              would happen to it and why, before anything is written.
            </p>
          )}
        </div>

        {/* ── The render's right `.side`: what it costs ──────────────────────────────────── */}
        {plan && <ImportCost plan={plan} />}
      </div>
    </div>
  );
}

/**
 * The plan — **the same component for a preview and for a committed apply**.
 *
 * Nothing here branches on `mode`. `applied` changes one sentence above this component and the
 * `caseKey`s fill in; the rows, the suites, the conditions and the totals are read identically,
 * because the server computes them identically. A second rendering keyed on the mode is how a
 * preview starts describing something the apply does not do.
 */
function ImportPlanView({ plan }: { plan: FeatureImportPlan }) {
  return (
    <div className="flex flex-col gap-4">
      <div data-testid="FeatureImport.Rows" className="flex flex-col gap-2.5">
        {plan.scenarios.map((row, index) => (
          <ScenarioCard key={`${row.path}:${row.line ?? 'file'}:${index}`} row={row} />
        ))}
      </div>

      {plan.suites.length > 0 && (
        <section
          className="rounded-lg border border-line bg-panel p-4"
          data-testid="FeatureImport.Suites"
        >
          <h2 className={SECTION_HEADING}>Suites</h2>
          {plan.suites.map((suite) => (
            <SuiteLine key={suite.name} suite={suite} />
          ))}
        </section>
      )}

      {plan.conditions.length > 0 && (
        <section
          className="rounded-lg border border-line bg-panel p-4"
          data-testid="FeatureImport.Conditions"
        >
          <h2 className={SECTION_HEADING}>Preconditions from the Background</h2>
          {plan.conditions.map((condition) => (
            <ConditionLine key={condition.importedKey} condition={condition} />
          ))}
        </section>
      )}
    </div>
  );
}

/**
 * `CREATE`/`UPDATE` read as progress, `SKIP` as neutral, `REFUSE` as the one that wants attention.
 *
 * Steel for a create, because it is the instance telling the reader what it will make rather than
 * a result; amber for an update and for a refusal, which are the two the reader has to decide
 * about. ~~`success`/`info`/`default`/`warning`.~~ → 007 T14: the kit has no `info`, and a green
 * `Create` would have borrowed the hue the runner spends on *passed*.
 */
const actionTone = {
  CREATE: 'steel',
  UPDATE: 'blocked',
  SKIP: 'plain',
  REFUSE: 'blocked',
} as const satisfies Record<ImportRow['action'], BadgeTone>;

/**
 * One scenario, as the render's `.scn`: the verdict and the name in the header, the reason and the
 * provenance under it, the preconditions it would gain beneath that.
 *
 * A refused card is distinguished by more than its badge's colour: a tint on the card is the
 * second cue and the badge's own TEXT is the non-colour one WCAG 1.4.1 asks for. It never blocks
 * anything — Import is enabled off the totals, so forty importable scenarios import beside any
 * number of refusals. A skipped card is dimmed, per the render's `.scn.skip`: it is on screen
 * because *not touching something* is an outcome, and it is quiet because it is the outcome
 * nobody has to read.
 */
function ScenarioCard({ row }: { row: ImportRow }) {
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? row.preconditions : row.preconditions.slice(0, 2);
  const hidden = row.preconditions.length - shown.length;

  return (
    <article
      data-testid="FeatureImport.Row"
      data-action={row.action}
      className={cn(
        'overflow-hidden rounded-lg border border-line bg-panel',
        row.action === 'SKIP' && 'opacity-60',
      )}
      style={
        row.action === 'REFUSE'
          ? { backgroundColor: 'color-mix(in oklab, var(--color-blocked) 6%, transparent)' }
          : undefined
      }
    >
      <header className="flex flex-wrap items-center gap-2.5 border-b border-line px-3 py-2">
        <Badge tone={actionTone[row.action]} data-testid="FeatureImport.Row.Action">
          {importActionLabels[row.action]}
        </Badge>
        <span
          className="min-w-0 flex-1 text-[13px] text-ink"
          data-testid="FeatureImport.Row.Scenario"
        >
          {row.scenario ?? 'The whole file'}
        </span>
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
            className="shrink-0 no-underline"
          >
            <Identifier>{row.caseKey}</Identifier>
          </RouterLink>
        ) : null}
      </header>

      <div className="px-3 py-2.5">
        <p className="m-0 text-[12.5px] text-ink-4">
          {row.feature ? `${row.feature} · ` : ''}
          <Identifier variant="muted">
            {row.path}
            {row.line === null ? '' : `:${row.line}`}
          </Identifier>
          {row.automationKey && (
            <>
              {' · '}
              <Identifier variant="muted" testId="FeatureImport.Row.Key">
                {row.automationKey}
              </Identifier>
            </>
          )}
        </p>

        {/*
          VERBATIM. These sentences are the server's, fixed, listed on `ImportRow.reason` in
          `docs/specs/openapi.yaml`, and clients may match on them — they are the stability a `type`
          slug carries on an error, applied to an outcome that is not one. Re-wording "needs
          library:manage for the Background" through `describeError` would take the permission key
          out of the one place a caller can read it, and `describeError` is for failures: an import
          that refuses a row succeeded.
        */}
        {row.reason && (
          <p className="m-0 mt-1.5 text-[13px] text-ink-2" data-testid="FeatureImport.Row.Reason">
            {row.reason}
          </p>
        )}

        {row.preconditions.length > 0 && (
          <div className="mt-2 flex flex-wrap items-center gap-1.5">
            <span className="font-mono text-[10.5px] tracking-[.06em] text-ink-4 uppercase">
              Preconditions
            </span>
            {/*
              Rendered from the ROW, in the order it arrived — the import-owned titles in Background
              order, then the case's human-attached ones. Re-deriving this from the plan's
              `conditions[]` would attach every Background step in the request to every case in it,
              because that list is per REQUEST and this one is per row.
            */}
            {shown.map((title, index) => (
              <span
                key={`${title}:${index}`}
                data-testid="FeatureImport.Row.Precondition"
                className="rounded-md border border-line px-2 py-[2px] text-[11.5px] text-ink-2"
              >
                {title}
              </span>
            ))}
            {hidden > 0 && (
              <Button
                size="sm"
                variant="quiet"
                onClick={() => setExpanded(true)}
                data-testid="FeatureImport.Row.MorePreconditions"
              >
                +{hidden} more
              </Button>
            )}
          </div>
        )}
      </div>
    </article>
  );
}

function SuiteLine({ suite }: { suite: SuiteAction }) {
  return (
    <div
      className="flex flex-wrap items-center gap-2 border-b border-line py-2 last:border-b-0"
      data-testid="FeatureImport.SuiteRow"
    >
      <Badge
        tone={suite.action === 'REFUSE' ? 'blocked' : suite.action === 'CREATE' ? 'steel' : 'plain'}
        data-testid="FeatureImport.SuiteRow.Action"
      >
        {suiteActionLabels[suite.action]}
      </Badge>
      <span className="text-[13px] text-ink">{suite.name}</span>
      {suite.reason && <span className="text-[12.5px] text-ink-4">{suite.reason}</span>}
    </div>
  );
}

function ConditionLine({ condition }: { condition: ConditionAction }) {
  return (
    <div
      className="flex flex-wrap items-center gap-2 border-b border-line py-2 last:border-b-0"
      data-testid="FeatureImport.ConditionRow"
    >
      <Badge
        tone={
          condition.action === 'REFUSE'
            ? 'blocked'
            : condition.action === 'CREATE'
              ? 'steel'
              : 'plain'
        }
        data-testid="FeatureImport.ConditionRow.Action"
      >
        {conditionActionLabels[condition.action]}
      </Badge>
      <span className="min-w-0 flex-1 text-[13px] text-ink">{condition.title}</span>
      <Identifier variant="muted">{condition.importedKey}</Identifier>
      {condition.reason && <span className="text-[12.5px] text-ink-4">{condition.reason}</span>}
    </div>
  );
}

/**
 * What this import does, and what it deliberately leaves alone.
 *
 * **Import never deletes, and the render says so twice** — once as a panel naming the orphans, once
 * as a sentence under it (UX#35). *"A destructive default hidden in a toggle is the failure this
 * panel exists to prevent."*
 */
function ImportCost({ plan }: { plan: FeatureImportPlan }) {
  const { create, update, skip, refuse } = plan.totals;

  return (
    <aside className="w-full shrink-0 rounded-lg border border-line bg-panel p-4 lg:w-[260px]">
      <section className="mb-5" data-testid="FeatureImport.Totals" aria-live="polite">
        <h2 className={SECTION_HEADING}>What this import does</h2>
        <CostRow
          label="New cases"
          value={`${formatCount(create)} create`}
          tone="var(--color-steel)"
          testId="FeatureImport.Totals.Create"
        />
        <CostRow
          label="Cases updated"
          value={`${formatCount(update)} update`}
          tone="var(--color-blocked)"
          testId="FeatureImport.Totals.Update"
        />
        <CostRow
          label="Left alone"
          value={`${formatCount(skip)} skip`}
          testId="FeatureImport.Totals.Skip"
        />
        <CostRow
          label="Refused"
          value={`${formatCount(refuse)} refused`}
          tone="var(--color-blocked)"
          testId="FeatureImport.Totals.Refuse"
        />
        <p className="mt-2 mb-0 text-[12px] leading-[1.5] text-ink-4">
          Creates {formatCount(create)}, updates {formatCount(update)}, leaves{' '}
          {formatCount(skip)} alone.
        </p>
      </section>

      {plan.orphans.length > 0 && (
        <section data-testid="FeatureImport.Orphans">
          <h2 className={SECTION_HEADING}>
            Cases these files do not name
            <span className="font-sans text-[11px] font-normal tracking-normal text-ink-4 normal-case">
              {plan.orphans.length}
            </span>
          </h2>
          {/*
            Listed, never touched — and saying so is the point of the panel. Whether a missing
            scenario is a rename or a retirement is a question only a person can answer, and
            deleting the case would destroy the runs it appears in on the strength of a file
            somebody edited.
          */}
          <p className="m-0 mb-2 text-[12px] leading-[1.5] text-ink-4">
            These are cases in the target suites whose automation key is not in the files you
            picked — a renamed scenario looks exactly like a retired one.
          </p>
          {plan.orphans.map((orphan) => (
            <div
              key={orphan.caseKey}
              data-testid="FeatureImport.Orphan"
              className="flex items-baseline justify-between gap-2 py-1 text-[12.5px]"
            >
              <span className="min-w-0 truncate text-ink-3">
                <Identifier variant="muted">{orphan.caseKey}</Identifier> {orphan.automationKey}
              </span>
              <span className="shrink-0 text-ink-4">kept</span>
            </div>
          ))}
          <p className="mt-2 mb-0 text-[12px] leading-[1.5] text-ink-4">
            Import never deletes. Deprecate them yourself if the repository dropped them on purpose.
          </p>
        </section>
      )}
    </aside>
  );
}

/** The render's `.kv` — a claim on the left, its number on the right. */
function CostRow({
  label,
  value,
  tone,
  testId,
}: {
  label: string;
  value: string;
  tone?: string;
  testId: string;
}) {
  return (
    <div className="flex items-baseline justify-between gap-2 py-1 text-[12.5px]">
      <span className="text-ink-3">{label}</span>
      <span
        data-testid={testId}
        className="font-mono text-[12px] tabular-nums text-ink"
        style={tone ? { color: tone } : undefined}
      >
        {value}
      </span>
    </div>
  );
}
