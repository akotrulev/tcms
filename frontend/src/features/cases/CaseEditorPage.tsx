import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Collapse,
  Divider,
  Link,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useSnackbar } from 'notistack';
import { useEffect, useRef, useState } from 'react';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { useCan } from '../../auth/useCan';
import { Controller, useForm, type FieldErrors, type FieldPath, type UseFormSetFocus } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import {
  CaseSchema,
  fromCasePayload,
  liveSteps,
  nextKeyword,
  prefilledKeywords,
  toCasePayload,
  type CaseInput,
} from './caseSchema';
import { PasteGherkinDialog } from './PasteGherkinDialog';
import { CaseDocument, type DocumentRefusal } from './CaseDocument';
import { sectionLine, splitFeatureLine } from './gherkinText';
import { caseTextKey, clearCaseText, readCaseText, writeCaseText } from './caseTextBuffer';
import { CaseDraftStatus } from './CaseDraftStatus';
import { useNewCaseDraft } from './useNewCaseDraft';
import type { CaseDraft } from '../../api/types';
import { ClassificationPicker } from './ClassificationPicker';
import { ConditionEditDialog } from './ConditionEditDialog';
import { ConditionsPanel } from './ConditionsPanel';
import {
  useCase,
  useCaseDraft,
  useCaseFeature,
  useCases,
  useClassification,
  useCreateCase,
  useCreateCondition,
  useDiscardCaseDraft,
  useParseGherkin,
  useRenderGherkin,
  useSaveCaseDraft,
  useStepCorpus,
  useSuites,
  useUpdateCase,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { NEW_CASE_DEFAULTS } from '../../lib/caseDefaults';
import { formatAge, plural } from '../../lib/format';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { automationLabels, priorityLabels } from '../../lib/labels';
import { selectTestId } from '../../lib/muiTestIds';
import type {
  CaseConditionRef,
  Condition,
  GherkinParseResult,
  StepSuggestion,
  TestCase,
} from '../../api/types';

/**
 * Case authoring — **one surface, and it is text** (003 D1).
 *
 * ## What this file stopped being
 *
 * ~~A two-column form whose main column was a list of step rows: a keyword select, an action field
 * with its own completion, a data cell with three modalities and a drag handle, per row.~~ →
 * **retired in §003 Phase 2.** `SortableStep`, `StepActionField`, `StepKeywordCell`,
 * `StepDataCell.tsx`, `stepReorder.ts` and the drag-and-drop sensors are deleted, and with them
 * roughly half of this file. `@dnd-kit` stays a dependency — the board uses it — and the three
 * step-data modalities survive **as text**: a doc string is `"""`, a table is `|…|`, and
 * `testData` is the `# data:` comment the renderer already emits.
 *
 * What is honestly lost is written down rather than left to be discovered: an author who does not
 * write Gherkin loses their editor in one release, with no fallback if the document surface
 * disappoints at real content length. 003 records that as the trade D1 accepts.
 *
 * ## What it kept, and what the text is authoritative over
 *
 * The header, the commit bar, the draft machinery and the rail. The document carries **five** of
 * the sixteen case fields — title, objective, tags, steps and the case's own preconditions — so a
 * commit from text is a **merge over those five and never a form replace** (003 C3). The other
 * eleven live in the rail and in the two prose fields below the document, and no parse may touch
 * them. `Feature:` renders the *suite's* name and is therefore chrome: parsing it back into
 * `suiteId` would let a rename move the case out of its folder.
 *
 * ## The one parser is still the server's
 *
 * Committing means posting the text to `POST …/cases/parse-gherkin` and using what the server
 * read; seeding means posting the payload to `POST …/cases/render-gherkin` and showing what the
 * server wrote. `gherkinText.ts` colours the text and numbers its lines and commits nothing — see
 * its header, and the amendment it made to `featureFiles.ts`'s standing rule.
 */

const TestIdPrefix = 'CaseEditor';

/**
 * The name every field answers to when something other than the field itself has to name it.
 *
 * One map rather than a label written at the control and re-typed in the summary: the two would
 * drift the first time a field was renamed, and a summary that names a field the form does not
 * have is worse than no summary — it sends the author looking for a control that is not there.
 */
const FIELD_LABELS: Partial<Record<keyof CaseInput, string>> = {
  title: 'Title',
  objective: 'Objective',
  description: 'Description',
  expectedOutcome: 'Expected outcome',
  suiteId: 'Suite',
  tags: 'Tags',
  priority: 'Priority',
  estimatedDurationSec: 'Estimated duration',
  automationStatus: 'Automation status',
  automationKey: 'Automated test',
  notAutomatableReason: "Why it can't be automated",
  changeComment: 'Change note',
};

/** The fields the document carries (003 C3) — named in a summary, never linked from one. */
const TEXT_CARRIED = new Set<keyof CaseInput>(['title', 'objective', 'tags']);

type SummaryRow = { field: FieldPath<CaseInput> | null; label: string; message: string };

/**
 * Every refusal on the form, flattened, in the order the fields appear on screen.
 *
 * ~~The step list contributes twice — a rule about the LIST and a rule about one row.~~ → under D1
 * there are no rows on screen, so every step rule collapses into **one** line about the document.
 * A per-row message would name a control the author cannot see: the row it is about is a sentence
 * in a textarea, and `setFocus('steps.3.action')` has nothing to focus. Its `field` is therefore
 * null and the line is a statement rather than a link — the document is one control and it is
 * already on screen.
 */
function summarizeErrors(errors: FieldErrors<CaseInput>): SummaryRow[] {
  const rows: SummaryRow[] = [];

  for (const [name, label] of Object.entries(FIELD_LABELS)) {
    const message = errors[name as keyof CaseInput]?.message;
    if (typeof message === 'string') {
      /*
        A refusal about a field the TEXT carries has no control to move focus to — `title`,
        `objective` and `tags` are lines in the document now. It is still named, because the author
        has to know what is wrong, but it is not a link: a link that focuses nothing reads as the
        summary being broken rather than as the field being somewhere else.
      */
      const focusable = !TEXT_CARRIED.has(name as keyof CaseInput);
      rows.push({ field: focusable ? (name as FieldPath<CaseInput>) : null, label, message });
    }
  }

  const steps = errors.steps;
  const listLevel = steps?.root?.message ?? steps?.message;
  if (typeof listLevel === 'string') {
    rows.push({ field: null, label: 'Steps', message: listLevel });
  }
  if (Array.isArray(steps)) {
    // Every row-level message, de-duplicated: the schema's per-row rules quote the server's
    // wording, and four rows failing the same rule is one thing to fix, not four lines.
    const seen = new Set<string>();
    steps.forEach((row) => {
      if (!row) return;
      for (const part of ['keyword', 'action', 'testData', 'expectedResult'] as const) {
        const message = row[part]?.message;
        if (typeof message === 'string' && !seen.has(message)) {
          seen.add(message);
          rows.push({ field: null, label: 'Steps', message });
        }
      }
    });
  }

  return rows;
}

/**
 * What a refused submit says, and where it says it.
 *
 * Inline errors are necessary and this does not replace them. But the field a refused submit is
 * about is routinely off screen — so what the author actually experienced was a Save button that
 * did nothing. WCAG 2.2's answer, and the one used here: one focusable region at the top of the
 * form, one line per refusal, each line moving focus to the control it names when there is one.
 *
 * The caller keys this on `submitCount`, so a second refused submit is a second mount and focus
 * lands here again.
 */
function ErrorSummary({
  rows,
  setFocus,
  testId,
}: {
  rows: SummaryRow[];
  setFocus: UseFormSetFocus<CaseInput>;
  testId: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.focus();
  }, []);

  return (
    <Alert
      severity="error"
      role="alert"
      ref={ref}
      tabIndex={-1}
      sx={{ mb: 2, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' } }}
      data-testid={testId}
    >
      <AlertTitle>
        {rows.length === 1
          ? 'One thing needs fixing before this can be saved'
          : `${rows.length} things need fixing before this can be saved`}
      </AlertTitle>
      <Box component="ul" sx={{ pl: 2.5, my: 0 }}>
        {rows.map((row) => (
          <Typography component="li" variant="body2" key={`${row.label}:${row.message}`}>
            {row.field ? (
              <Link
                component="button"
                type="button"
                underline="always"
                color="inherit"
                onClick={() => setFocus(row.field as FieldPath<CaseInput>, { shouldSelect: true })}
                data-testid={`${testId}.Item`}
                sx={{ font: 'inherit', textAlign: 'left' }}
              >
                {row.label}
              </Link>
            ) : (
              <Box component="span" sx={{ fontWeight: 600 }} data-testid={`${testId}.Item`}>
                {row.label}
              </Box>
            )}
            {' — '}
            {row.message}
          </Typography>
        ))}
      </Box>
    </Alert>
  );
}

export function CaseEditorPage({ mode }: { mode: 'create' | 'edit' }) {
  const { projectId = '', caseId } = useParams();
  const existing = useCase(projectId, mode === 'edit' ? caseId : undefined);

  if (mode === 'edit') {
    return (
      <DataRegion testId={`${TestIdPrefix}.Load`} state={{ ...existing, refetch: () => void existing.refetch() }}>
        {(data) => <CaseEditorForm mode="edit" projectId={projectId} existing={data} />}
      </DataRegion>
    );
  }

  return <CaseEditorForm mode="create" projectId={projectId} existing={undefined} />;
}

/**
 * The case's OWN attached condition ids, in order — what the editor binds.
 *
 * Inherited rows are the folder's and are dropped: submitting one would attach the folder's
 * condition directly to the case. Inline rows carry no `conditionId` and are dropped too, on the
 * server's terms — nothing can render one, so nothing can round-trip one either.
 */
function ownConditionIds(refs: readonly CaseConditionRef[] | undefined): string[] {
  return (refs ?? [])
    .filter((ref) => !ref.inherited && !!ref.conditionId)
    .map((ref) => ref.conditionId as string);
}


/**
 * The three ways to start a case that does not exist yet (003 Phase 1).
 *
 * ## Its own component, because of the read it makes
 *
 * *Start from a case* needs the project's most recently edited case, which is a list read. Held in
 * the editor it would fire on the **edit** route too, where the offer is not made and the answer
 * is not used — a request per case anybody opens, for nothing. A child rendered only on the blank
 * state asks the question only where it is asked.
 *
 * ## It copies TEXT, not fields
 *
 * `GET …/cases/{id}/feature` is the canonical rendering of a saved case, from the one renderer. So
 * the start hands the editor a document, and the commit on the first blur is what turns it into
 * this case's own title, tags and steps. Copying the fields instead would need this component to
 * know what a case is, and would leave the document showing something the form disagreed with.
 *
 * Nothing is linked: the corpus rule, one level up. A copy carries no reference, so editing this
 * case later cannot change the one it was started from.
 */
function CaseStarts({
  projectId,
  onWrite,
  onPaste,
  onText,
}: {
  projectId: string;
  onWrite: () => void;
  onPaste: () => void;
  onText: (text: string) => void;
}) {
  const [fromId, setFromId] = useState<string | null>(null);
  // One row, most recently updated — the smallest answer that can name a case.
  const recent = useCases(projectId, { size: 1 }, 'updatedAt,desc');
  const recentCase = recent.data?.items[0];
  const copied = useCaseFeature(projectId, fromId ?? undefined);

  /*
    Delivered once. `onText` is a new function on every render of the parent, so an effect that
    only watched the data would hand the same text over again on the next render — overwriting
    whatever the author had typed since, with no event that could explain it.
  */
  const delivered = useRef(false);
  const text = copied.data;
  useEffect(() => {
    if (text === undefined || delivered.current) return;
    delivered.current = true;
    onText(text);
  }, [text, onText]);

  return (
    <Panel title="Start" testId={`${TestIdPrefix}.Starts`}>
      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5 }}>
        <Button variant="contained" onClick={onWrite} data-testid={`${TestIdPrefix}.Start.Write`}>
          Write it
        </Button>
        <Tooltip
          title={
            recentCase
              ? `Copies the text of ${recentCase.key} — nothing is linked.`
              : 'This project has no case to copy yet.'
          }
        >
          <Box component="span">
            <Button
              variant="outlined"
              disabled={!recentCase}
              onClick={() => recentCase && setFromId(recentCase.id)}
              data-testid={`${TestIdPrefix}.Start.FromCase`}
            >
              Start from a case
            </Button>
          </Box>
        </Tooltip>
        <Button variant="outlined" onClick={onPaste} data-testid={`${TestIdPrefix}.Start.Paste`}>
          Paste a .feature
        </Button>
      </Stack>
    </Panel>
  );
}

/** Two ordered lists of Background lines, compared as the author would compare them. */
const sameLines = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && a.every((line, index) => line === b[index]);

function CaseEditorForm({
  mode,
  projectId,
  existing,
}: {
  mode: 'create' | 'edit';
  projectId: string;
  existing: TestCase | undefined;
}) {
  const navigate = useNavigate();
  const { enqueueSnackbar } = useSnackbar();
  const [searchParams] = useSearchParams();
  const suites = useSuites(projectId);
  const dimensions = useClassification(projectId);
  const gate = useCan(projectId);

  /*
    The suite the reader was standing in when they pressed New case — UX#106, carried as a query
    parameter by `CaseListPage`. A DEFAULT for the picker and not a decision: it is prefilled and
    changeable, which is what makes carrying it safe. Read once, into the form's defaults, so
    editing the picker and then re-rendering does not put it back.
  */
  const carriedSuiteId = mode === 'create' ? (searchParams.get('suiteId') ?? '') : '';

  const [conditionDialog, setConditionDialog] = useState<{
    kind: 'PRE' | 'POST';
    condition: Condition | null;
  } | null>(null);

  /*
    Entries created in this editor session, so a row can render the moment it is attached — and, in
    this slice, so a `Background:` line committed a moment ago can be compared against the text on
    the next commit without waiting for the library's cached lists to refetch.
  */
  const [createdConditions, setCreatedConditions] = useState<Condition[]>([]);

  /** Whether the paste dialog is open. Rendered only while it is, so it seeds fresh every time. */
  const [pasting, setPasting] = useState(false);

  /** Whether a submit has been refused yet — the summary is shown only after one has. */
  const [showSummary, setShowSummary] = useState(false);

  /*
    The folder's conditions, rendered read-only beside the case's own, with the DECLINED ROWS
    CONCATENATED ON (§187). Computed here rather than in the panel so the panel takes exactly what
    it renders, and computed once per response rather than per toggle — recomputing would reorder
    the list under the author's cursor.
  */
  const inheritedPre = [
    ...(existing?.preconditions ?? []).filter((ref) => ref.inherited),
    ...(existing?.declinedPreconditions ?? []),
  ];
  const inheritedPost = [
    ...(existing?.postconditions ?? []).filter((ref) => ref.inherited),
    ...(existing?.declinedPostconditions ?? []),
  ];

  const createCase = useCreateCase(projectId);
  const updateCase = useUpdateCase(projectId, existing?.id ?? '');
  const createCondition = useCreateCondition(projectId);
  const parse = useParseGherkin(projectId);
  const render = useRenderGherkin(projectId);

  const {
    control,
    register,
    handleSubmit,
    getValues,
    reset,
    watch,
    setValue,
    setError,
    setFocus,
    formState: { errors, isSubmitting, isDirty, submitCount },
  } = useForm<CaseInput>({
    resolver: zodResolver(CaseSchema),
    mode: 'onBlur',
    defaultValues: {
      title: existing?.title ?? '',
      // '' is UNFILED, not "missing" (§96) — see caseSchema. The submit maps '' back to null.
      suiteId: existing?.suiteId ?? carriedSuiteId,
      objective: existing?.objective ?? '',
      description: existing?.description ?? '',
      expectedOutcome: existing?.expectedOutcome ?? '',
      priority: existing?.priority ?? NEW_CASE_DEFAULTS.priority,
      automationStatus: existing?.automationStatus ?? NEW_CASE_DEFAULTS.automationStatus,
      automationKey: existing?.automationKey ?? '',
      notAutomatableReason: existing?.notAutomatableReason ?? '',
      estimatedDurationSec:
        existing?.estimatedDurationSec === null || existing?.estimatedDurationSec === undefined
          ? ''
          : String(existing.estimatedDurationSec),
      stepFormat: NEW_CASE_DEFAULTS.stepFormat,
      /*
        The rows the case was served with. They are no longer edited as rows — the document is —
        but they are still what a save sends and what `render-gherkin` is seeded from, and a case
        opened and saved with the text untouched must send back exactly what it arrived with.
      */
      steps: prefilledKeywords(
        (existing?.steps ?? []).map((step) => ({
          id: step.id,
          keyword: step.keyword ?? '',
          action: step.action,
          testData: step.testData ?? '',
          expectedResult: step.expectedResult ?? '',
          docString: step.docString,
          dataTable: step.dataTable,
        })),
      ),
      classificationValueIds: existing?.classificationValueIds ?? [],
      preconditionIds: ownConditionIds(existing?.preconditions),
      postconditionIds: ownConditionIds(existing?.postconditions),
      declinedConditionIds: [
        ...(existing?.declinedPreconditions ?? []).map((ref) => ref.id),
        ...(existing?.declinedPostconditions ?? []).map((ref) => ref.id),
      ],
      tags: existing?.tags ?? [],
      changeComment: '',
    },
  });

  /*
    ======================== THE WRITE SURFACE'S STATE (003 Phase 2) ========================

    `documentText` is the raw text, exactly as typed, and it is the source of truth for the five
    fields C3 names. It is NEVER re-rendered from parsed state per keystroke — that is what jumps
    the caret, and it is also what makes a half-typed line disappear as it is being written.

    `committedText` is the last text the server successfully parsed. It is what makes "commit on
    blur" idempotent: blurring a document nobody changed must not post the parser a request, and
    Save must know whether what is on screen has been read yet.
  */
  const [documentText, setDocumentText] = useState('');
  const [committedText, setCommittedText] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<DocumentRefusal | null>(null);
  const [seeded, setSeeded] = useState(false);
  /** Which start the author chose on a new case, or null while the three are still offered. */
  const [started, setStarted] = useState<'WRITE' | 'CASE' | 'PASTE' | null>(null);

  /**
   * A case with **zero step rows and a stored `gherkinSource`** — the population §255 left behind
   * and `002-` is named as the deleter of.
   *
   * Under the Fields lens those cases were editable as rows. Under D1 the only editor is one that
   * parses, so the honest answer is a refusal: the surface renders the stored blob **read-only**
   * and Save is refused until an operator round-trips it. It is never silently reparsed into rows
   * on somebody's first edit — a reparse is a rewrite of content nobody asked to change, under a
   * success toast.
   *
   * D5 clears lab and prod, so no case on our two boxes reaches this arm. It stays because this is
   * a self-hosted product and an operator's instance is not reachable by a `DELETE` run against
   * ours — which is why it is proved by a unit test and nothing else.
   */
  const migrationBlocked =
    mode === 'edit' && !!existing?.gherkinSource && (existing.steps ?? []).length === 0;

  const canManageLibrary = gate.can('library:manage');

  /** Where the raw text is buffered — one key per case, `new` included. See `caseTextBuffer`. */
  const textKey = caseTextKey(projectId, existing?.id ?? null);
  const [bufferedText] = useState(() => readCaseText(textKey));

  /*
    The buffer is written from a DEBOUNCED copy of the text rather than on every keystroke: the
    value is small, but `localStorage.setItem` is synchronous and this is the one control in the
    app somebody types a paragraph into. One second is well inside the window a crash is measured
    in and well outside the window a keystroke is.
  */
  const settledText = useDebouncedValue(documentText, 1000);
  useEffect(() => {
    if (!seeded) return;
    writeCaseText(textKey, settledText);
  }, [seeded, settledText, textKey]);

  /*
    SEEDING THE DOCUMENT — once, and only in edit mode.

    A new case starts empty, on the `Given / When / Then` ghost text: there is nothing to render,
    and posting an empty payload to `render-gherkin` to be told so would be a round trip for a
    blank box. An existing case is rendered by the server, through the one renderer, so what is on
    screen is byte-for-byte what a save produces (003 C1/D2).

    The `Feature:` line is lifted out into chrome — see `splitFeatureLine` and 003 C3.
  */
  const renderText = render.mutateAsync;
  useEffect(() => {
    if (seeded || mode !== 'edit' || !existing) return;
    setSeeded(true);
    if (migrationBlocked) {
      setDocumentText(existing.gherkinSource ?? '');
      return;
    }
    void renderText(toCasePayload(getValues()))
      .then((text) => {
        setDocumentText(splitFeatureLine(text).body);
        setCommittedText(splitFeatureLine(text).body);
      })
      .catch(() => {
        /*
          A failed render leaves the document EMPTY and says so through the mutation's own error
          below, rather than seeding it with something invented. Seeding a guess would make the
          first blur commit that guess over the case's real steps.
        */
      });
  }, [seeded, mode, existing, migrationBlocked, renderText, getValues]);

  useEffect(() => {
    if (mode !== 'create' || seeded) return;
    setSeeded(true);
  }, [mode, seeded]);

  /** The standing corpus — one mount per page, its own key, its own hook. See `useStepCorpus`. */
  const corpus = useStepCorpus(projectId);

  useUnsavedWork(isDirty && !isSubmitting, mode === 'edit' ? 'an edited test case' : 'a new test case');

  /**
   * Attaches a library condition to the case and makes it renderable at once.
   *
   * Shared by the conditions dialog and the commit's Background handling, because both create an
   * entry the picker's cached lists cannot resolve yet.
   */
  const attachCondition = (created: Condition, kind: 'PRE' | 'POST') => {
    setCreatedConditions((current) => [...current, created]);
    const field = kind === 'PRE' ? 'preconditionIds' : 'postconditionIds';
    setValue(field, [...getValues(field), created.id], { shouldDirty: true });
  };

  /** Every condition title this editor can resolve — the case's own, plus what it just created. */
  const titleById = new Map<string, string>();
  for (const ref of existing?.preconditions ?? []) {
    if (ref.conditionId) titleById.set(ref.conditionId, ref.title);
  }
  for (const condition of createdConditions) titleById.set(condition.id, condition.title);

  const automationStatus = watch('automationStatus');
  const suiteId = watch('suiteId');
  const movingFolder = mode === 'edit' && !!existing && (existing.suiteId ?? '') !== suiteId;

  /** The `Feature:` chrome — the suite's name, live off the picker, or the key of a filed-nowhere case. */
  const featureName =
    (suites.data ?? []).find((suite) => suite.id === suiteId)?.name ?? existing?.key ?? null;

  /**
   * The text, read by the server, merged into the form — **five fields, and never a sixth.**
   *
   * ## The merge, field by field (003 C3)
   *
   * | Field | Rule |
   * |---|---|
   * | `steps` | always replaced — they are what the text *is* |
   * | `tags` | always replaced, so deleting an `@tag` line deletes the tag |
   * | `title`, `objective` | replaced **only when the text declares a `Scenario:` block**, which is what `result.title` being non-null means. A bare step list — what a paste of loose steps produces — carries neither, and blanking somebody's title because they pasted four lines is a loss nothing on screen would report |
   * | `preconditionIds` | replaced from the `Background:` block, through the library |
   *
   * Nothing else is touched, ever. `suiteId`, `priority`, `severity`, `ownerId`,
   * `lifecycleStatus`, `automationStatus`, `automationKey`, `notAutomatableReason`,
   * `estimatedDurationSec`, `classificationValueIds`, `postconditionIds`, `description` and
   * `expectedOutcome` are the rail's and the two prose fields', and a parse that reached them
   * would be §255's rule broken — *a toggle between two representations of one thing may not
   * delete the thing* — with no second representation to recover from.
   *
   * ## Refusals never touch the text
   *
   * Every failure path here returns `false` and sets `refusal`. Not one of them writes
   * `documentText`. That is 003 C4's first clause and it is the reason this function reads the
   * text as an argument rather than from state.
   */
  const commit = async (text: string): Promise<boolean> => {
    if (migrationBlocked) {
      setRefusal({
        message:
          "This case's scenario is still stored as text rather than as steps, so it cannot be "
          + 'edited here. Ask your operator to run migrate-gherkin-source; nothing is changed '
          + 'until they do.',
        line: null,
      });
      return false;
    }
    if (text === committedText && !refusal) return true;
    if (text.trim().length === 0) {
      /*
        AN EMPTY DOCUMENT ON AN EDIT THAT HAS NOT BEEN SEEDED IS NOT AN EMPTY CASE — it is a
        document that has not arrived yet. `committedText` is null exactly then, and treating the
        blank textarea as authoritative would replace the case's steps with nothing on a save that
        raced the server's render. The refusal for a render that actually failed is the alert above
        the document; this is the arm for the seconds before it lands.
      */
      if (mode === 'edit' && committedText === null) return true;
      // Otherwise an empty document is not a refusal either — it is a case nobody has written yet,
      // and the schema's "Add at least one step" is the sentence that belongs to it, on Save.
      setRefusal(null);
      setCommittedText(text);
      setValue('steps', [], { shouldDirty: true });
      return true;
    }

    let result: GherkinParseResult;
    try {
      result = await parse.mutateAsync(text);
    } catch (error) {
      /*
        The server's own sentence, quoted, on the line `Problem.line` names. `errors.text` is where
        the four refusals put the sentence; `detail` is the fallback for anything else that got
        this far. The line is READ, never regexed out of the English — see `ApiError.line`.
      */
      setRefusal(
        error instanceof ApiError
          ? { message: error.fieldErrors?.text?.join(' ') ?? error.detail, line: error.line }
          : { message: 'The scenario could not be read. Check your connection and try again.', line: null },
      );
      return false;
    }

    /*
      THE `Background:` BLOCK IS THIS CASE'S OWN PRECONDITIONS (003 D6, decision (a)).

      A Background step is a row in the project's condition library, created through
      `library:manage` — so under D6 an author who may write cases but not manage the library
      cannot type a `Background:` line, in a surface where typing is the only interaction. The
      refusal therefore lands AT THE LINE and names the permission, rather than at Save; and for a
      caller without the grant the block is read-only chrome rather than an invitation.
    */
    const wanted = result.background.map((entry) => entry.title);
    const current = getValues('preconditionIds').map((id) => titleById.get(id) ?? '');
    if (!sameLines(wanted, current)) {
      if (!canManageLibrary) {
        setRefusal({
          message:
            'A Background: step becomes a precondition in this project’s library, which needs the '
            + 'library:manage permission. Ask a project admin for it, or put the line back as it '
            + 'was — nothing else in this case is blocked by it.',
          line: sectionLine(text, 'Background'),
          permission: 'library:manage',
        });
        return false;
      }

      const ids: string[] = [];
      try {
        for (const entry of result.background) {
          // An entry whose title this editor already resolves is REUSED rather than created a
          // second time: re-committing a document nobody changed must not add a duplicate library
          // row per blur.
          const known = [...titleById.entries()].find(([, title]) => title === entry.title)?.[0];
          if (known) {
            ids.push(known);
            continue;
          }
          const created = await createCondition.mutateAsync({
            title: entry.title,
            type: 'PRE',
            body: entry.body,
          });
          setCreatedConditions((all) => [...all, created]);
          titleById.set(created.id, created.title);
          ids.push(created.id);
        }
      } catch (error) {
        setRefusal({
          message:
            error instanceof ApiError
              ? error.detail
              : 'The Background steps could not be saved to the library. Nothing was changed.',
          line: sectionLine(text, 'Background'),
        });
        return false;
      }
      setValue('preconditionIds', ids, { shouldDirty: true });
    }

    const rows = result.steps.map((step) => ({
      id: null,
      keyword: step.keyword ?? '',
      action: step.action,
      testData: step.testData ?? '',
      expectedResult: step.expectedResult ?? '',
      docString: step.docString ?? null,
      dataTable: step.dataTable ?? null,
    }));
    setValue('steps', prefilledKeywords(rows), { shouldDirty: true });
    setValue('tags', result.tags, { shouldDirty: true });
    if (result.title !== null) {
      setValue('title', result.title, { shouldDirty: true });
      setValue('objective', result.objective, { shouldDirty: true });
    }

    setCommittedText(text);
    setRefusal(null);
    return true;
  };

  /*
    ============================ The autosave buffer (§128, D1) ============================

    TWO BUFFERS, one per mode, behind one banner and one status line — the server's for an edit,
    the browser's for a create. Both hold `toCasePayload`'s output; the RAW TEXT is buffered
    beside them by `caseTextBuffer`, because the interesting document is the one that does not
    parse and there is no payload for such a document (003 C4).
  */
  const draftQuery = useCaseDraft(projectId, mode === 'edit' ? existing?.id : undefined);
  const saveDraft = useSaveCaseDraft(projectId, existing?.id ?? '');
  const discardDraft = useDiscardCaseDraft(projectId, existing?.id ?? '');
  const newCaseDraft = useNewCaseDraft(projectId, mode === 'create');

  const [restoreDecided, setRestoreDecided] = useState(false);
  const offeredDraft:
    | { updatedAt: string; stale: boolean; content: CaseDraft['content'] }
    | null =
    mode === 'create'
      ? newCaseDraft.offered
        ? {
            updatedAt: newCaseDraft.offered.updatedAt,
            stale: false,
            content: newCaseDraft.offered.content as CaseDraft['content'],
          }
        : null
      : (draftQuery.data ?? null);
  /*
    The offer is made when EITHER buffer has something, and the text buffer alone is enough.

    That arm is the one C4 is about: an author typing a scenario that does not parse yet has not
    dirtied the form — nothing has been committed — so no payload draft was ever written. Their
    work exists only as text, and offering nothing would lose exactly the work that is hardest to
    retype.
  */
  const promptOpen = !restoreDecided && (offeredDraft !== null || bufferedText !== null);
  const draftSettled = mode === 'create' || draftQuery.isSuccess || draftQuery.isError;

  const autosaveEnabled =
    (mode === 'create' || !!existing) && draftSettled && !promptOpen && isDirty && !isSubmitting;

  const saveDraftContent = (content: Record<string, unknown>) =>
    mode === 'create'
      ? newCaseDraft.save(content)
      : saveDraft.mutateAsync({ lockVersion: existing?.lockVersion ?? 0, content });

  /**
   * Puts the buffered work back — the payload into the form, and the raw text into the document.
   *
   * `keepDefaultValues`, and it is NOT `keepDirty`: the offer is answered on mount, before the
   * author has typed, so `keepDirty` would preserve a dirty flag that currently stands at false —
   * Save disabled with "Nothing has changed yet" on a form full of unsaved work.
   *
   * The text is restored **uncommitted** (`committedText` is left alone), which is the point: it
   * may not parse, and the next blur or Save is what finds that out and says which line.
   */
  const restoreDraft = () => {
    if (offeredDraft) reset(fromCasePayload(offeredDraft.content), { keepDefaultValues: true });
    if (bufferedText) setDocumentText(bufferedText.text);
    if (mode === 'create') setStarted('WRITE');
    setRestoreDecided(true);
  };

  const dismissDraft = () => {
    if (mode === 'create') newCaseDraft.discard();
    else if (offeredDraft) discardDraft.mutate();
    clearCaseText(textKey);
    setRestoreDecided(true);
  };

  const onValid = handleSubmit(async (data) => {
    const payload = toCasePayload(data);

    try {
      if (mode === 'edit' && existing) {
        const saved = await updateCase.mutateAsync({ ...payload, lockVersion: existing.lockVersion });

        /*
          §194. The declines this save asked to keep that the saved case does not carry — MEASURED
          off the response, not predicted from the move.
        */
        const kept = new Set(
          [...(saved.declinedPreconditions ?? []), ...(saved.declinedPostconditions ?? [])]
            .map((ref) => ref.id),
        );
        const ended =
          (existing.suiteId ?? '') !== data.suiteId
            ? [...new Set(data.declinedConditionIds)].filter((id) => !kept.has(id))
            : [];

        clearCaseText(textKey);
        enqueueSnackbar(
          ended.length
            ? `Saved ${saved.key} as v${saved.currentVersionNo}`
              + ` · the move ended ${plural(ended.length, 'condition exception')}`
            : `Saved ${saved.key} as v${saved.currentVersionNo}`,
          { variant: ended.length ? 'warning' : 'success' },
        );
        navigate(`/projects/${projectId}/cases/${saved.id}`);
      } else {
        const created = await createCase.mutateAsync(payload);
        newCaseDraft.discard();
        clearCaseText(textKey);
        enqueueSnackbar(`Created ${created.key}`, { variant: 'success' });
        navigate(`/projects/${projectId}/cases/${created.id}`);
      }
    } catch (error) {
      if (error instanceof ApiError && error.hasFieldErrors) {
        const entries = Object.entries(error.fieldErrors ?? {});
        for (const [field, messages] of entries) {
          setError(field as keyof CaseInput, { type: 'server', message: messages.join(' ') });
        }
        const first = entries[0]?.[0] as keyof CaseInput | undefined;
        if (first) setFocus(first);
        return;
      }
      setError('root', {
        type: 'server',
        message:
          error instanceof ApiError
            ? error.detail
            : "Couldn't save the case. Check your connection and try again.",
      });
    }
  }, () => setShowSummary(true));

  /**
   * Save commits the text first, and **is refused while it does not parse** (003 C4).
   *
   * It does not save a partial parse and it does not silently keep the last good `steps`: a
   * refusal returns before `handleSubmit` runs at all, so the payload the server would have been
   * sent is never built. The sentence names the line, beside the text, where the author is
   * looking.
   */
  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!(await commit(documentText))) {
      setShowSummary(true);
      return;
    }
    await onValid();
  };

  const summaryRows = summarizeErrors(errors);
  const inheritedPreCount = inheritedPre.length;

  /** Where a corpus pick lands: one more step line at the end of the document. */
  const insertFromCorpus = (suggestion: StepSuggestion) => {
    const keyword = nextKeyword(liveSteps(getValues('steps'))) === 'GIVEN' ? 'Given' : 'And';
    const line = `  ${keyword} ${suggestion.action}`;
    setDocumentText((current) => (current.trim().length === 0 ? line : `${current.replace(/\n+$/, '')}\n${line}`));
  };

  return (
    <Box component="form" onSubmit={onSubmit} noValidate aria-busy={isSubmitting} data-testid={`${TestIdPrefix}.Form`}>
      <PageHeader
        testId={`${TestIdPrefix}.Header`}
        title={mode === 'edit' ? `Edit ${existing?.key}` : 'New test case'}
        documentTitle={mode === 'edit' ? `Edit ${existing?.key}` : 'New test case'}
        crumbs={[
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          ...(existing ? [{ label: existing.key, to: `/projects/${projectId}/cases/${existing.id}` }] : []),
          { label: mode === 'edit' ? 'Edit' : 'New' },
        ]}
        meta={
          existing && (
            <Tooltip title="Saving writes a new version. Past runs keep the version they executed.">
              <Box>
                <Identifier variant="muted">
                  v{existing.currentVersionNo} → v{(existing.currentVersionNo ?? 1) + 1}
                </Identifier>
              </Box>
            </Tooltip>
          )
        }
      />

      {promptOpen && (
        <Alert
          severity="info"
          sx={{ mb: 2 }}
          data-testid={`${TestIdPrefix}.DraftPrompt`}
          action={
            <Stack direction="row" sx={{ gap: 1 }}>
              <Button size="small" onClick={dismissDraft} data-testid={`${TestIdPrefix}.DraftDiscard`}>
                Discard
              </Button>
              <Button
                size="small"
                variant="contained"
                onClick={restoreDraft}
                data-testid={`${TestIdPrefix}.DraftRestore`}
              >
                Restore
              </Button>
            </Stack>
          }
        >
          <AlertTitle>
            Your unsaved changes from {formatAge((offeredDraft ?? bufferedText)?.updatedAt ?? '')}
          </AlertTitle>
          You left work on this case without saving it.
          {bufferedText && !offeredDraft && (
            <>
              {' '}
              {/* The text-only arm: work that never parsed, so no payload was ever buffered. */}
              <strong>It had not been read as a scenario yet, so it comes back exactly as typed.</strong>
            </>
          )}
          {offeredDraft?.stale && (
            <>
              {' '}
              <strong>
                The case has been saved since this draft was written, so restoring it will discard
                that change.
              </strong>
            </>
          )}
        </Alert>
      )}

      {errors.root && (
        <Alert severity="error" role="alert" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Error`}>
          {errors.root.message}
        </Alert>
      )}

      {showSummary && summaryRows.length > 0 && (
        <ErrorSummary
          key={submitCount}
          rows={summaryRows}
          setFocus={setFocus}
          testId={`${TestIdPrefix}.ErrorSummary`}
        />
      )}

      <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 2.5, alignItems: 'flex-start' }}>
        <Stack sx={{ gap: 2.5, flex: 1, minWidth: 0, width: '100%' }}>
          {/*
            THE THREE STARTS (003 Phase 1) — on create, and not on edit.

            An empty editor is not a blank page problem the author should have to solve alone, and
            the render draws three answers to it. On an EDIT there is already a case on screen, so
            offering to start from something else would be offering to overwrite it.
          */}
          {mode === 'create' && started === null && !promptOpen && (
            <CaseStarts
              projectId={projectId}
              onWrite={() => setStarted('WRITE')}
              onPaste={() => {
                setStarted('PASTE');
                setPasting(true);
              }}
              onText={(text) => {
                setStarted('CASE');
                setDocumentText(splitFeatureLine(text).body);
              }}
            />
          )}

          <Panel
            title="The case"
            testId={`${TestIdPrefix}.Document.Panel`}
            actions={
              <Button
                size="small"
                onClick={() => setPasting(true)}
                data-testid={`${TestIdPrefix}.Steps.Paste`}
              >
                Paste Gherkin
              </Button>
            }
          >
            <Box>
              {migrationBlocked && (
                <Alert severity="warning" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.NotMigrated`}>
                  <AlertTitle>Not yet migrated</AlertTitle>
                  This case&rsquo;s scenario is still stored as text, so it cannot be edited here and
                  cannot be saved. Ask your operator to run{' '}
                  <Identifier>migrate-gherkin-source</Identifier>. Nothing below has been changed.
                </Alert>
              )}
              {render.isError && (
                <Alert severity="error" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.RenderFailed`}>
                  The case could not be rendered as text. Reload before editing — saving now would
                  write an empty scenario over it.
                </Alert>
              )}
              <CaseDocument
                projectId={projectId}
                text={documentText}
                onTextChange={setDocumentText}
                onCommit={() => void commit(documentText)}
                refusal={refusal}
                featureName={featureName}
                inheritedPreconditionCount={inheritedPreCount}
                readOnly={migrationBlocked}
                testIdPrefix={TestIdPrefix}
              />
            </Box>
          </Panel>

          {/*
            THE TWO FIELDS THE TEXT DOES NOT CARRY AND THE RAIL DOES NOT PLACE.

            003's *Fields that must find a home* table settles eleven fields; `description` and
            `expectedOutcome` are not among them, and they are not in the five the text represents
            either. Dropping them would make two persisted fields uneditable in the only case
            editor there is — a silent narrowing of the product, not a simplification. They are
            prose, so they sit in the narrative column under the document rather than in a 320px
            rail that holds only short enumerable values.
          */}
          <Panel title="Notes" testId={`${TestIdPrefix}.Notes`}>
            <Stack sx={{ gap: 2, maxWidth: 920 }}>
              <TextField
                label="Expected outcome"
                fullWidth
                multiline
                minRows={2}
                helperText="The case-level outcome, alongside the per-step expected results."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ExpectedOutcome` } }}
                {...register('expectedOutcome')}
              />
              <TextField
                label="Description"
                fullWidth
                multiline
                minRows={2}
                helperText="Optional background — anything a reader needs that the steps assume."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Description` } }}
                {...register('description')}
              />
            </Stack>
          </Panel>

          {mode === 'edit' && (
            <Panel title="Change note" testId={`${TestIdPrefix}.ChangeComment`}>
              <Box>
                <TextField
                  label="What changed, and why"
                  fullWidth
                  multiline
                  minRows={2}
                  helperText="Optional. It appears on the version timeline, so make it worth reading."
                  slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ChangeComment` } }}
                  {...register('changeComment')}
                />
              </Box>
            </Panel>
          )}
        </Stack>

        {/*
          ===================================== THE RAIL =====================================

          Seven rows — Suite, Priority, Automation, Duration, Preconditions, Postconditions,
          Classification — and **every one of them is a real focusable control with a native
          role.** The render called it "a read-only summary you click to change" and drew the rows
          as `div.kv` spans; that is UX#117 *Compact Control Semantics* (Critical, *"don't use a
          clickable div"*) with UX#62 and UX#114 beside it, and the failure it produces is the
          `NOT_AUTOMATABLE` refusal landing on a field inside something the author reads as a
          summary. `ui-reasoning.csv` row 16 makes keyboard operability a must_have for a
          productivity tool, and a rail nobody can Tab into fails that outright.

          Row count is not the constraint; VALUE WIDTH is. Only short enumerable values sit on the
          face — the automation key, the condition lists and the classification chips render as a
          count plus a disclosure.

          It is present on the BLANK STATE too, with defaults and the Suite prefilled from context
          (frame B draws no rail; a new case still needs a suite). And it collapses **on this route
          only** — `position: sticky` here, not a global chrome change.
        */}
        <Box
          sx={{
            width: { xs: '100%', lg: 320 },
            flexShrink: 0,
            position: { lg: 'sticky' },
            top: { lg: 'calc(var(--header-height) + 16px)' },
          }}
        >
          <Stack sx={{ gap: 2.5 }}>
            <Panel title="This case" testId={`${TestIdPrefix}.Rail`}>
              <Stack sx={{ gap: 2 }}>
                <Controller
                  control={control}
                  name="suiteId"
                  render={({ field, fieldState }) => (
                    <TextField
                      select
                      label="Suite"
                      fullWidth
                      value={field.value}
                      onChange={field.onChange}
                      onBlur={field.onBlur}
                      error={!!fieldState.error}
                      helperText={
                        fieldState.error?.message ??
                        (field.value
                          ? undefined
                          : 'Unfiled. It still appears in the grid, and any suite whose tags it carries.')
                      }
                      slotProps={{
                        select: { native: false, SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.Suite`) },
                        formHelperText: { role: fieldState.error ? 'alert' : undefined },
                      }}
                    >
                      {/* An explicit choice rather than an empty first row (§96): "nowhere" is a
                          real answer, and a blank option reads as a field somebody forgot. */}
                      <MenuItem value="">
                        <Box component="span" sx={{ color: 'text.secondary' }}>
                          Not filed
                        </Box>
                      </MenuItem>
                      {[...(suites.data ?? [])]
                        .sort((a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key))
                        .map((suite) => (
                          <MenuItem key={suite.id} value={suite.id}>
                            {suite.name}
                          </MenuItem>
                        ))}
                    </TextField>
                  )}
                />

                <Controller
                  control={control}
                  name="priority"
                  render={({ field }) => (
                    <TextField
                      select
                      label="Priority"
                      fullWidth
                      value={field.value}
                      onChange={field.onChange}
                      // `severity` keeps its `severity: data.priority` mirror in the payload —
                      // dropping it would leave the stored value stale, because PATCH is a merge.
                      helperText="Also recorded as the case's severity."
                      slotProps={{
                        select: { SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.Priority`) },
                      }}
                    >
                      {Object.entries(priorityLabels).map(([value, label]) => (
                        <MenuItem key={value} value={value}>
                          {label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />

                {/* AUTOMATION IS A CONTROL, NOT A BADGE: it is required on every save and it gates
                    two child fields, which is exactly what a badge cannot express. Its two
                    children sit in its disclosure rather than in rows of their own — an unbounded
                    identifier cannot render whole on a 280px face. */}
                <Controller
                  control={control}
                  name="automationStatus"
                  render={({ field }) => (
                    <TextField
                      select
                      label="Automation"
                      fullWidth
                      value={field.value}
                      onChange={field.onChange}
                      slotProps={{
                        select: { SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.AutomationStatus`) },
                      }}
                    >
                      {Object.entries(automationLabels).map(([value, label]) => (
                        <MenuItem key={value} value={value}>
                          {label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <Collapse in={automationStatus === 'AUTOMATED' || automationStatus === 'NOT_AUTOMATABLE'}>
                  <Stack sx={{ gap: 2 }}>
                    {automationStatus === 'AUTOMATED' && (
                      <TextField
                        label="Automated test"
                        fullWidth
                        error={!!errors.automationKey}
                        helperText={
                          errors.automationKey?.message ??
                          'Fully-qualified test method. Ingested results match on this.'
                        }
                        slotProps={{
                          htmlInput: {
                            'data-testid': `${TestIdPrefix}.Field.AutomationKey`,
                            style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem' },
                          },
                          formHelperText: { role: errors.automationKey ? 'alert' : undefined },
                        }}
                        {...register('automationKey')}
                      />
                    )}
                    {automationStatus === 'NOT_AUTOMATABLE' && (
                      <TextField
                        label="Why it can't be automated"
                        fullWidth
                        multiline
                        minRows={2}
                        error={!!errors.notAutomatableReason}
                        helperText={
                          errors.notAutomatableReason?.message ??
                          'Recorded so the automation-coverage figure stays honest.'
                        }
                        slotProps={{
                          htmlInput: { 'data-testid': `${TestIdPrefix}.Field.NotAutomatableReason` },
                          formHelperText: { role: errors.notAutomatableReason ? 'alert' : undefined },
                        }}
                        {...register('notAutomatableReason')}
                      />
                    )}
                  </Stack>
                </Collapse>

                <TextField
                  label="Duration (seconds)"
                  type="number"
                  fullWidth
                  error={!!errors.estimatedDurationSec}
                  helperText={errors.estimatedDurationSec?.message}
                  slotProps={{
                    htmlInput: { 'data-testid': `${TestIdPrefix}.Field.EstimatedDuration`, min: 0 },
                    formHelperText: { role: errors.estimatedDurationSec ? 'alert' : undefined },
                  }}
                  {...register('estimatedDurationSec')}
                />

                <Divider />

                {/*
                  PRECONDITIONS — a read-only inherited count, because the case's OWN preconditions
                  are the `Background:` block in the document now (003 D6, decision (a)).

                  What stays here is the half the text cannot say: the folder's conditions, which
                  reach this case by inheritance and are rendered identically to its own by
                  `bodies()`, so putting them in the block would make deleting a line ambiguous
                  between detaching an own condition and DECLINING an inherited one. The count
                  links to what is missing from the rendered `.feature`.

                  §187's opt-out toggle survives inside the disclosure. 003 retires this row as an
                  *editor of attachments*; it says nothing about declines, and dropping them would
                  delete a shipped feature on the way past.
                */}
                <Controller
                  control={control}
                  name="declinedConditionIds"
                  render={({ field: declines }) => {
                    const toggle = (attachmentId: string, next: boolean) =>
                      declines.onChange(
                        next
                          ? [...declines.value, attachmentId]
                          : declines.value.filter((id: string) => id !== attachmentId),
                      );

                    /*
                      The rows the pending move will end (§194) — resolved from what is on screen,
                      which is why they can be NAMED. Here rather than in either group: one array
                      carries both kinds, so a per-group alert would state the same event twice and
                      each copy would name half of it.
                    */
                    const ending = movingFolder
                      ? [...inheritedPre, ...inheritedPost].filter((ref) => declines.value.includes(ref.id))
                      : [];

                    return (
                      <Stack sx={{ gap: 2 }}>
                        {ending.length > 0 && (
                          <Alert severity="warning" data-testid={`${TestIdPrefix}.Conditions.EndingDeclines`}>
                            <AlertTitle>
                              Moving this case ends {plural(ending.length, 'condition exception')}
                            </AlertTitle>
                            <Typography variant="body2">
                              A folder&rsquo;s rule can only be declined by a case filed in that
                              folder, so {ending.length === 1 ? 'this exception' : 'these exceptions'}
                              {' '}end with the move. Moving it back will not bring
                              {ending.length === 1 ? ' it' : ' them'} back.
                            </Typography>
                            <Box component="ul" sx={{ pl: 3, my: 1 }}>
                              {ending.map((ref) => (
                                <Typography component="li" variant="body2" key={ref.id}>
                                  {ref.title}
                                  {ref.inheritedFrom ? ` — from ${ref.inheritedFrom}` : null}
                                </Typography>
                              ))}
                            </Box>
                          </Alert>
                        )}

                        <Box data-testid={`${TestIdPrefix}.Preconditions`}>
                          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                            Preconditions
                          </Typography>
                          <Typography variant="body2">
                            {inheritedPreCount === 0
                              ? 'None inherited. Write this case’s own setup as a Background: block.'
                              : `${plural(inheritedPreCount, 'inherited precondition')} — not shown in the text above.`}
                          </Typography>
                          {inheritedPre.map((ref) => (
                            <Stack
                              key={ref.id}
                              direction="row"
                              sx={{ alignItems: 'center', gap: 1, mt: 0.5 }}
                              data-testid={`${TestIdPrefix}.Preconditions.Row`}
                            >
                              <Switch
                                size="small"
                                checked={!declines.value.includes(ref.id)}
                                disabled={movingFolder}
                                onChange={(event) => toggle(ref.id, !event.target.checked)}
                                // The panel's own wording, verbatim: the two switches mean the
                                // same thing and a reader meets both on this screen.
                                inputProps={{ 'aria-label': `Applies to this case: ${ref.title}` }}
                              />
                              <Typography variant="body2" sx={{ minWidth: 0 }}>
                                {ref.title}
                                {ref.inheritedFrom ? (
                                  <Identifier variant="muted"> from {ref.inheritedFrom}</Identifier>
                                ) : null}
                              </Typography>
                            </Stack>
                          ))}
                          {existing && (
                            <Link
                              component={RouterLink}
                              to={`/projects/${projectId}/cases/${existing.id}`}
                              variant="caption"
                              data-testid={`${TestIdPrefix}.Preconditions.Link`}
                            >
                              See the whole procedure, inherited setup included
                            </Link>
                          )}
                        </Box>

                        <Divider />

                        {/* POSTCONDITIONS — new, and the render draws no such row. Gherkin emits
                            them as comments the parser reads back as nothing by design, so the
                            text can never author them; this is the only place they can live. */}
                        <Controller
                          control={control}
                          name="postconditionIds"
                          render={({ field }) => (
                            <ConditionsPanel
                              kind="POST"
                              projectId={projectId}
                              testId={`${TestIdPrefix}.Postconditions`}
                              value={field.value}
                              inherited={inheritedPost}
                              declined={declines.value}
                              leavingFolder={movingFolder}
                              onToggleDecline={toggle}
                              onChange={field.onChange}
                              known={createdConditions}
                              onCreate={() => setConditionDialog({ kind: 'POST', condition: null })}
                              onEdit={(condition) => setConditionDialog({ kind: 'POST', condition })}
                            />
                          )}
                        />
                      </Stack>
                    );
                  }}
                />

                {/*
                  CLASSIFICATION — **absent entirely when every vocabulary is empty** (finding 3).

                  Not a labelled control with nothing in it, and not four of them: a freshly
                  provisioned project has four dimensions and no values, and the row is worth its
                  space only when there is something to choose. The one sentence and the one link
                  are the picker's `emptyNotice`, owned here because this is the side that knows
                  where Classification lives.
                */}
                <Controller
                  control={control}
                  name="classificationValueIds"
                  render={({ field }) => (
                    <DataRegion state={dimensions} testId={`${TestIdPrefix}.ClassificationRegion`}>
                      {(loaded) =>
                        loaded.length === 0 || loaded.every((dimension) => dimension.values.length === 0) ? (
                          <Box data-testid={`${TestIdPrefix}.Classification.Absent`}>
                            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                              Nothing configured, nothing shown.{' '}
                              <Link component={RouterLink} to={`/projects/${projectId}/settings/classification`}>
                                Set up Classification
                              </Link>{' '}
                              to classify cases here.
                            </Typography>
                          </Box>
                        ) : (
                          <>
                            <Divider sx={{ mb: 2 }} />
                            <ClassificationPicker
                              dimensions={loaded}
                              selected={field.value}
                              onChange={field.onChange}
                              testIdPrefix={TestIdPrefix}
                              emptyNotice={(empty) => (
                                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                                  {empty.map((dimension) => dimension.name).join(', ')}{' '}
                                  {empty.length === 1 ? 'has' : 'have'} no values yet.{' '}
                                  <Link
                                    component={RouterLink}
                                    to={`/projects/${projectId}/settings/classification`}
                                  >
                                    Set up Classification
                                  </Link>
                                </Typography>
                              )}
                            />
                          </>
                        )
                      }
                    </DataRegion>
                  )}
                />
              </Stack>
            </Panel>

            {/*
              THE STANDING CORPUS (003 Phase 1) — *steps this project uses*, ranked by use count,
              insert on click.

              ONE MOUNT PER PAGE, on its own key and its own hook: `useStepSuggestions`'s
              `enabled: q.trim().length > 0` gate is untouched, because the read this panel makes
              is precisely the one that gate refuses and the reason it refuses it — eight mounted
              rows firing eight reads of a thousand-row list — does not apply to a panel there is
              one of.
            */}
            <Panel title="Steps this project uses" testId={`${TestIdPrefix}.Corpus`}>
              <Box sx={{ maxHeight: 320, overflowY: 'auto' }}>
                <DataRegion
                  state={corpus}
                  testId={`${TestIdPrefix}.Corpus.Region`}
                  isEmpty={(rows) => rows.length === 0}
                  empty={{
                    title: 'No steps yet',
                    body: 'This list fills itself from the steps your cases already contain — nobody has to populate it.',
                  }}
                >
                  {(rows) => (
                    <Stack sx={{ gap: 0.5 }}>
                      {rows.map((row) => (
                        <Button
                          key={row.action}
                          size="small"
                          onClick={() => insertFromCorpus(row)}
                          data-testid={`${TestIdPrefix}.Corpus.Step`}
                          sx={{ justifyContent: 'space-between', textAlign: 'left', textTransform: 'none' }}
                        >
                          <Box component="span" sx={{ minWidth: 0 }}>
                            {row.action}
                          </Box>
                          {/* The ranking signal, shown rather than only sorted by. */}
                          <Identifier variant="muted">
                            {row.useCount} case{row.useCount === 1 ? '' : 's'}
                          </Identifier>
                        </Button>
                      ))}
                    </Stack>
                  )}
                </DataRegion>
              </Box>
            </Panel>

            {mode === 'edit' && (
              <Alert severity="info" data-testid={`${TestIdPrefix}.StatusNotice`}>
                <Typography variant="body2">
                  Review status is changed through the review actions, not here — approving a case
                  records who approved it and when.
                </Typography>
              </Alert>
            )}
          </Stack>
        </Box>
      </Stack>

      {/*
        THE COMMIT BAR — the form's one Save, pinned to the bottom of the viewport, carrying the
        buffer's status line beside the button that sentence names.
      */}
      <Box
        data-testid={`${TestIdPrefix}.CommitBar`}
        sx={{
          position: 'sticky',
          bottom: 0,
          zIndex: 2,
          mt: 2.5,
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 2,
          px: 2,
          py: 1.5,
          borderTop: '1px solid',
          borderColor: 'divider',
          bgcolor: 'background.paper',
        }}
      >
        <Box sx={{ flex: 1, minWidth: 200 }}>
          <CaseDraftStatus
            control={control}
            getValues={getValues}
            enabled={autosaveEnabled}
            save={saveDraftContent}
            commitLabel={mode === 'edit' ? 'Save changes' : 'Create case'}
            testId={`${TestIdPrefix}.DraftStatus`}
            inline
          />
        </Box>
        <Button
          onClick={() =>
            navigate(existing ? `/projects/${projectId}/cases/${existing.id}` : `/projects/${projectId}/cases`)
          }
          data-testid={`${TestIdPrefix}.Cancel`}
        >
          Cancel
        </Button>
        <PermissionGate projectId={projectId} permission={mode === 'edit' ? 'case:edit' : 'case:create'}>
          {({ allowed }) => (
            <Button
              type="submit"
              variant="contained"
              disabled={!allowed || isSubmitting || migrationBlocked || (mode === 'edit' && !isDirty)}
              title={
                migrationBlocked
                  ? 'This case has not been migrated yet — see the note above the text.'
                  : allowed && mode === 'edit' && !isDirty
                    ? 'Nothing has changed yet'
                    : undefined
              }
              data-testid={`${TestIdPrefix}.Submit`}
            >
              {isSubmitting ? 'Saving…' : mode === 'edit' ? 'Save changes' : 'Create case'}
            </Button>
          )}
        </PermissionGate>
      </Box>

      {conditionDialog && (
        <ConditionEditDialog
          key={conditionDialog.condition?.id ?? 'new'}
          projectId={projectId}
          open
          condition={conditionDialog.condition}
          defaultType={conditionDialog.kind}
          onClose={() => setConditionDialog(null)}
          onCreated={(created) => attachCondition(created, conditionDialog.kind)}
        />
      )}

      {/*
        The paste dialog, PROMOTED OUT OF ITS BUTTON (003 Phase 1): it is one of the three starts
        as well as an action on the panel, so it is mounted by whichever opened it and seeds fresh
        every time.

        Under D1 the paste lands as TEXT rather than as rows — the text is the source of truth, so
        putting the parsed rows into the form and leaving the document showing something else
        would be two representations of one case disagreeing on screen.
      */}
      {pasting && (
        <PasteGherkinDialog
          projectId={projectId}
          open
          onClose={() => {
            setPasting(false);
            if (started === 'PASTE' && documentText.trim().length === 0) setStarted(null);
          }}
          onApply={(_result, applyMode, text) => {
            const body = splitFeatureLine(text).body;
            setDocumentText((current) =>
              applyMode === 'REPLACE' || current.trim().length === 0
                ? body
                : `${current.replace(/\n+$/, '')}\n${body}`,
            );
            setStarted('WRITE');
          }}
          onConditionCreated={(created) => attachCondition(created, 'PRE')}
          hasSteps={documentText.trim().length > 0}
        />
      )}
    </Box>
  );
}
