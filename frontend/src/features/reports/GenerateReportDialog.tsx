import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import {
  useClassification,
  useDocPages,
  useGenerateReport,
  usePlans,
  useRuns,
  useSaveReportDefinition,
} from '../../api/queries';
import type {
  AggregationPolicy,
  ReportDefinition,
  ReportFormat,
  ReportJob,
} from '../../api/types';
import { describeError } from '../../lib/describeError';
import {
  aggregationHelp,
  aggregationLabels,
  docPageStatusLabel,
  humanize,
  reportFormatHints,
  reportFormatLabels,
  runStatusLabels,
} from '../../lib/labels';

/**
 * Asking for a report (§6e, the request half of §87's pipeline).
 *
 * ## What this dialog is allowed to know, and what it must not claim
 *
 * Two of its three fields are open sets on the wire and closed here, and the reasons are opposite.
 *
 * **The report type is an open string in the contract** because §6e names ten and a deployment
 * grows them slice by slice; a client generated from a stale enum could not ask for a report that
 * exists. What this dialog offers is therefore a list of the ones it can *build a parameter form
 * for* — ~~one, today~~ ~~**two since R6**~~ **three since §115** — and not a claim about what the
 * server can compute. A server that has more is not misrepresented: it is simply not asked, and the
 * list and its parameter forms move together, which is the only way they can stay consistent.
 *
 * ## What a second report changed, and why it is more than a menu entry
 *
 * Every gate in this dialog used to be able to assume the run picker. A project with no runs
 * disabled **Generate** outright, which is right for `run_summary` — one run is its whole scope —
 * and wrong for `aggregate_execution`, whose selection is optional in every field and whose honest
 * answer for an empty project is a report that says so. So the emptiness alert and the disabled
 * submit are now scoped to the type that needs a run, and the run picker is not rendered for the
 * type that has no use for one. The natural implementation — leaving the gate where it was — is a
 * dialog whose second report is unreachable in exactly the projects that have not started running
 * things yet, which is §66's shape and the one this file's own tests had to be written to catch.
 *
 * ## And what a THIRD one changed, which is that the gate stopped being a negation
 *
 * R6 spelled the run gate `!isAggregate`, and with two types that was the same statement as "this
 * type needs a run". With three it is not: `coverage_matrix` is not the aggregate, so it would have
 * inherited the run gate and been **unreachable in exactly the projects that have documentation and
 * no runs** — the same defect R6 fixed, arriving again through the shape of the fix rather than
 * through the code. The gates now key on what a type *needs* (`needsRun`, `isCoverage`), so a
 * fourth report opts in rather than being enrolled by not being the others.
 *
 * `coverage_matrix` brings the second emptiness gate, and it is a real one rather than symmetry: a
 * coverage matrix's whole spine is a document, so a project with no pages has nothing to report on,
 * exactly as a project with no runs has nothing for a run summary.
 *
 * ## Two modes since §227: ad-hoc, and regenerating something saved
 *
 * Passing `definition` puts the dialog in the second. Everything the saved row already answers —
 * the type and the whole parameter selection — is **not rendered**, because the request does not
 * send it: with a `definitionId` the server takes the type from the definition and *merges* the
 * request's parameters over the saved ones, so a form that re-posted its own defaults would
 * silently overwrite the selection somebody saved. Only the format is asked for, which is right:
 * `report_definition` has no format column on purpose (it is chosen per download, and a saved
 * report is not the file it produced).
 *
 * A blank read of that mode is the trap it was written against — the natural implementation
 * pre-fills the form from `definition.parameters` and posts it back, which works until a parameter
 * the form cannot express (this file lists three) is dropped on every regeneration.
 *
 * ## Saving is a field on this form, not a screen of its own
 *
 * The `POST` and `DELETE` behind saved definitions have been implemented, gated and tested on the
 * server since §92, and the panel that lists them had no Save control — so the list was `[]` on
 * every deployment for a reason no reader could see. The control is an optional name on this form
 * rather than a second dialog because the thing being saved *is* what this form composes; asking
 * for it anywhere else would mean a second copy of every parameter control.
 *
 * The save runs **before** the generate and a failure stops both. The other order queues a report
 * and then loses the definition, which is the outcome nobody can tell from a save that worked.
 * Saving costs `report:view` where generating costs `report:export` — two keys on one form, which
 * is the contract's decision and is stated in `useSaveReportDefinition`.
 *
 * ## Why the aggregate form validates what the server would also refuse
 *
 * Normally a client that re-checks a server rule is building the copy that drifts. This form
 * checks two things anyway, because of *when* the server's refusal arrives: a report's own
 * parameters are validated inside the **worker** (`ReportJobService`'s own note — the alternative
 * is a second copy of each report's rules on the request path), so a bad policy or a backwards
 * date range is not a 400 on this form, it is a job that goes `FAILED` after the dialog has
 * closed. So the policy is a **picker** — the value cannot be wrong because it cannot be typed —
 * and the date order is checked here, since the one refusal a person is most likely to earn is
 * worth more as a message beside the field than as a dead job card.
 *
 * ## The three facets this form deliberately cannot express
 *
 * The contract's selection also takes `configurationIds`, `suitePaths` and `automationStatuses` /
 * `sources`. None is offered, and the reasons differ: **there is no configurations endpoint in
 * this client at all**; **`suitePaths` are `ltree` paths that the API stopped exposing when suites
 * flattened (§98)**, so a picker here would have to guess at the server's own path spelling; and
 * the last two are cheap but would go on a form that is already the longest in the product. A
 * caller who needs them can send them — the API takes them — and the day a screen needs them,
 * they land beside these. Named as not built rather than left to be discovered as missing.
 *
 * **The format is a closed enum and is offered in full**, including the one that is refused on
 * every deployment today. That looks like `ui-rules`' render-then-reject and is the contract's
 * explicit design: *"the enum is not narrowed to what is installed: what a deployment can render
 * is a property of that deployment, and the refusal names it."* A client-side list of installed
 * renderers would be a second copy of somebody else's configuration — silently wrong in both
 * directions, offering nothing where PDF has been installed and claiming to know where it has not.
 * The refusal is a **400 at request time carrying a field error**, engineered for exactly this, so
 * it lands on the format field naming what the server *can* produce. That is a better answer than
 * a picker that quietly lacks an option, and it is why the hint under PDF says what it needs
 * rather than that it is unavailable.
 *
 * ## The run picker is this report's parameters, not a general one
 *
 * `run_summary` takes a required `runId`, checked **inside the worker** — a bad one is a FAILED
 * job carrying a sentence, not a 400. So a dialog that let it be omitted would produce a job that
 * spins and then fails, for a field the form could have required. A generic parameter form for
 * whatever a future report declares is still not built, and is still named as not built: nothing
 * in the contract describes a report's parameters in a machine-readable way, so there is nothing
 * to generate one from — which is why each type's form is written here, beside its entry.
 */

/**
 * The three wire strings, named once each.
 *
 * They are the server's durable identifiers (a saved definition stores this text), so they are
 * spelled here and nowhere else — the hard-coded `'run_summary'` in four places is what this file's
 * own note said R6 would otherwise have to go and find, and §115 added the third without having to.
 */
const RUN_SUMMARY = 'run_summary';
const AGGREGATE = 'aggregate_execution';
const COVERAGE = 'coverage_matrix';

/**
 * Ordered as the plan page orders them, not as the contract declares them — the two screens that
 * offer a policy must offer it in the same order or the same click means two things.
 *
 * `satisfies` against the generated union, so a policy added to the contract is a **compile error
 * here** rather than a report this form cannot ask for. That is the same argument `lib/labels.ts`
 * makes for every enum map in the product — and it works in the removing direction too: `BEST` was
 * dropped from the contract on 2026-08-07 (plan §8 #47) and this line stopped compiling, which is
 * the only reason a menu item offering a policy the server now refuses could not survive the change.
 */
const POLICIES = ['LATEST', 'LATEST_PER_CONFIG', 'WORST', 'ALL'] as const satisfies
  readonly AggregationPolicy[];

const ReportSchema = z
  .object({
    type: z.string().min(1),
    runId: z.string(),
    docPageId: z.string(),
    format: z.enum(['MD', 'HTML', 'PDF']),
    // Empty means "do not save". Not a checkbox plus a name: a checkbox ticked with an empty name
    // is a state the form would have to refuse, and the name alone cannot be in it.
    saveAs: z.string(),
    policy: z.enum(POLICIES),
    planIds: z.array(z.string()),
    featureIds: z.array(z.string()),
    // `yyyy-mm-dd` as typed, or empty. Converted to instants at submit — see `aggregateParameters`.
    from: z.string(),
    to: z.string(),
  })
  .superRefine((values, ctx) => {
    if (values.type === RUN_SUMMARY && values.runId === '') {
      ctx.addIssue({
        code: 'custom',
        path: ['runId'],
        message: 'Choose the run this report covers.',
      });
    }
    // Same shape as the run, and for the same reason: `docPageId` is checked in the WORKER, so
    // omitting it here buys a job that spins and then fails for a field this form could require.
    if (values.type === COVERAGE && values.docPageId === '') {
      ctx.addIssue({
        code: 'custom',
        path: ['docPageId'],
        message: 'Choose the document this report covers.',
      });
    }
    // ISO dates compare correctly as strings, so this needs no Date. The server refuses the same
    // thing — and refuses it in the worker, minutes later, on a job nobody is watching any more.
    if (values.type === AGGREGATE && values.from !== '' && values.to !== '' && values.to < values.from) {
      ctx.addIssue({
        code: 'custom',
        path: ['to'],
        message: 'The last day is before the first, so the range covers nothing.',
      });
    }
  });

type ReportFormInput = z.infer<typeof ReportSchema>;

const TestId = 'Reports.Generate';

/**
 * The types this dialog can ask for, each with the parameter form it needs.
 *
 * A list rather than a single constant because the second entry is what R6 adds, and a hard-coded
 * `'run_summary'` in four places is what that slice would have to find.
 */
const TYPES = [
  {
    value: RUN_SUMMARY,
    label: 'Run summary',
    hint: 'One run: status breakdown, progress per tester, and results by suite.',
  },
  {
    value: AGGREGATE,
    label: 'Aggregate execution report',
    hint: 'Many runs at once, narrowed by plan, feature and date, under one aggregation policy.',
  },
  {
    value: COVERAGE,
    label: 'Coverage matrix',
    hint: 'A document, its statements, and the cases that verify them — with each case’s latest result.',
  },
] as const;

/** Ordered by what people ask for, not by the contract's declaration order. */
const FORMATS: readonly ReportFormat[] = ['HTML', 'MD', 'PDF'];

type FormField = keyof ReportFormInput;

/**
 * The fields the **server** may name in a 400.
 *
 * Still three. The aggregate report's controls are not on this list and must not be added to it:
 * they compose the `parameters` object, which the server names as one field — so their sentences
 * belong in the banner, where `applyServerError` already puts anything it does not recognise.
 */
const FIELDS: readonly FormField[] = ['type', 'runId', 'docPageId', 'format'];

/**
 * The fields the **save** may name, which are not the same three.
 *
 * `POST /reports` refuses a blank `name` and an uncomputable `type`; it never sees a run or a
 * format. Keeping the two lists apart is what stops a save's `type` error landing on a control the
 * regenerate mode does not render.
 */
const SAVE_FIELDS: readonly FormField[] = ['type', 'saveAs'];

/**
 * `yyyy-mm-dd` from a date input to the instant the contract wants.
 *
 * **The range on the wire is half-open and the one on this form is not**, which is the whole
 * reason this function exists. A person picking 1–31 August means the whole of the 31st; the API's
 * `to` is exclusive, so it is sent the **next** midnight. Sending the typed date verbatim silently
 * drops the last day of every range — a smaller number that looks entirely reasonable, on a report
 * whose readers are comparing months.
 */
function startOfDay(date: string): string {
  return `${date}T00:00:00Z`;
}

function dayAfter(date: string): string {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return `${next.toISOString().slice(0, 10)}T00:00:00Z`;
}

/**
 * The selection, with **only what the caller narrowed on** in it.
 *
 * An empty array would be read by the server as absent anyway, so this is not about the numbers:
 * `parameters` is frozen onto the artifact and printed as the report's selection block (§88), and
 * a `"planIds": []` in there is a line telling the reader a filter was applied that was not.
 *
 * `policy` is always sent, even at its default. It is the one parameter whose omission changes what
 * the numbers *mean* rather than which rows they came from — "94% pass" under `WORST` and under
 * `BEST` are different claims — so the request states it and the header prints it.
 */
function aggregateParameters(values: ReportFormInput): Record<string, unknown> {
  const parameters: Record<string, unknown> = { policy: values.policy };
  if (values.planIds.length > 0) parameters.planIds = values.planIds;
  if (values.featureIds.length > 0) parameters.featureIds = values.featureIds;
  if (values.from !== '') parameters.from = startOfDay(values.from);
  if (values.to !== '') parameters.to = dayAfter(values.to);
  return parameters;
}

/**
 * Server field errors onto the fields that carry them.
 *
 * `parameters` is the one name the server may send that has no control here — it is the object
 * this form composes, not a field somebody filled in — so its sentence goes to the banner rather
 * than nowhere. `RunCreateDialog`'s rule, one form over.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
  fields: readonly FormField[] = FIELDS,
  /**
   * What the server calls a field, where this form calls it something else.
   *
   * One entry, and it exists because the save's `name` is this form's `saveAs` — a control that
   * also decides *whether* to save, which is why it is not simply called `name`. Without the
   * rename the server's "give it a name" lands in the banner, three inches from the empty box it
   * is about.
   */
  aliases: Readonly<Record<string, FormField>> = {},
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [name, messages] of Object.entries(error.fieldErrors)) {
      const field = aliases[name] ?? name;
      if ((fields as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        stray.push(messages.join(' '));
      }
    }
    if (stray.length > 0) {
      setError('root', stray.join(' '));
      return;
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function GenerateReportDialog({
  projectId,
  open,
  onClose,
  onQueued,
  definition,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  /** The 202's job, handed to the screen that will watch it. */
  onQueued: (job: ReportJob) => void;
  /**
   * A saved report to regenerate, or absent for an ad-hoc request.
   *
   * When present the dialog asks for the format and nothing else — see the header. It is the whole
   * row rather than an id because the title and the fixed type are read off it, and a dialog that
   * took an id would have to fetch a list the screen behind it is already holding.
   */
  definition?: ReportDefinition;
}) {
  const generate = useGenerateReport(projectId);
  const save = useSaveReportDefinition(projectId);
  const runs = useRuns(projectId);
  const pages = useDocPages(projectId);
  const plans = usePlans(projectId);
  const dimensions = useClassification(projectId);
  const {
    control,
    register,
    handleSubmit,
    setError,
    setFocus,
    watch,
    formState: { errors },
  } = useForm<ReportFormInput>({
    resolver: zodResolver(ReportSchema),
    defaultValues: {
      type: TYPES[0].value,
      runId: '',
      docPageId: '',
      format: 'HTML',
      saveAs: '',
      policy: 'LATEST',
      planIds: [],
      featureIds: [],
      from: '',
      to: '',
    },
    mode: 'onBlur',
  });

  /**
   * The format is the only control in regenerate mode, so it is what the focus goes to there.
   *
   * `setFocus('runId')` unconditionally would put the caret in a field that is not rendered — a
   * no-op that reads as the dialog opening unfocused, which is the a11y failure this effect exists
   * to prevent.
   */
  useEffect(() => {
    if (open) setFocus(definition ? 'format' : 'runId');
  }, [open, setFocus, definition]);

  const format = watch('format');
  const type = watch('type');
  const policy = watch('policy');
  const saveAs = watch('saveAs');
  /**
   * Regenerating a saved report. Every parameter control is gated off it, including the two
   * emptiness alerts: a saved run summary already names its run, so "this project has no runs" is
   * both irrelevant and, on a project whose runs were archived, false about the report.
   */
  const regenerating = !!definition;
  const isAggregate = !regenerating && type === AGGREGATE;
  const isCoverage = !regenerating && type === COVERAGE;
  /**
   * The run picker's type, named rather than derived as "not the other two".
   *
   * With two types a negation was the same statement; with three it stops being one, and the
   * fourth report would silently inherit the run gate by being neither of the types that opt out.
   * This file's own header records that shape as §66's, which is why the gates below key on what a
   * type NEEDS rather than on what it is not.
   */
  const needsRun = !regenerating && type === RUN_SUMMARY;

  const submit = handleSubmit(async (values) => {
    // Regenerating: the definition supplies the type and the base parameters, and the request
    // deliberately sends neither. See the header.
    if (definition) {
      try {
        const job = await generate.mutateAsync({
          definitionId: definition.id,
          format: values.format,
        });
        onQueued(job);
        onClose();
      } catch (error) {
        applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
      }
      return;
    }

    /*
      Saved first, and a failure stops the generate. The other order queues a report and then loses
      the definition — an outcome indistinguishable, from the screen, from a save that worked.
    */
    if (values.saveAs.trim() !== '') {
      try {
        await save.mutateAsync({
          type: values.type,
          name: values.saveAs.trim(),
          parameters:
            values.type === AGGREGATE
              ? aggregateParameters(values)
              : values.type === COVERAGE
                ? { docPageId: values.docPageId }
                : { runId: values.runId },
        });
      } catch (error) {
        applyServerError(
          error,
          (field, message) => setError(field, { type: 'server', message }),
          SAVE_FIELDS,
          { name: 'saveAs' },
        );
        return;
      }
    }

    try {
      const job = await generate.mutateAsync({
        type: values.type,
        format: values.format,
        // Frozen onto the artifact verbatim (`V1`: "a report you cannot reproduce is a report
        // nobody trusts"), so only what the report actually takes is sent — an extra key here
        // would be printed in the header's selection block as a filter the reader believes
        // narrowed the numbers. Which is also why this switches on the type rather than sending
        // the union of both forms: a `runId` riding along on an aggregate request would be
        // printed as part of a selection that ignored it.
        parameters:
          values.type === AGGREGATE
            ? aggregateParameters(values)
            : values.type === COVERAGE
              ? { docPageId: values.docPageId }
              : { runId: values.runId },
      });
      onQueued(job);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  const available = runs.data ?? [];
  /**
   * Every page in the project's documentation, flat.
   *
   * The list is a tree on the wire (`parentId`), and it is offered flat on purpose: the report's
   * spine is ONE page and never its subtree, so a nested picker would suggest a choice — "this
   * branch" — that the request cannot express and the server would not honour.
   */
  const documents = pages.data ?? [];
  /**
   * The project's own Feature tree, by its contract key.
   *
   * `execution_fact.feature_ids` holds classification values of this dimension and no other, so
   * offering the company's Test Type or Test Level here would be a picker whose every choice
   * matches nothing. Absent while the read is in flight, and absent for a project whose vocabulary
   * has no features — the control says so instead of rendering an empty menu.
   */
  const features = (dimensions.data ?? []).find((dimension) => dimension.key === 'feature');

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={generate.isPending || save.isPending}>
        <DialogTitle>
          {definition ? `Generate “${definition.name}”` : 'Generate a report'}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            {definition && (
              /*
                What the saved row decides, said rather than rendered as disabled controls. A greyed
                form full of somebody's saved selection invites the reading that it can be adjusted
                here — which is the one thing the request cannot express without overwriting the
                saved parameters it merges over.
              */
              <Alert severity="info" data-testid={`${TestId}.FromDefinition`}>
                This uses the settings saved with <strong>{definition.name}</strong> —{' '}
                {humanize(definition.type)}. Choose the format to produce it in.
              </Alert>
            )}
            {/*
              A run is the RUN SUMMARY's whole scope, so a project with none cannot produce one —
              said before the click rather than after it, as the run dialog says it about plans.
              Scoped to that type since R6: the aggregate report needs no run, and a project with
              none is exactly where somebody would be reaching for it first.
            */}
            {needsRun && runs.isSuccess && available.length === 0 && (
              <Alert severity="info" data-testid={`${TestId}.NoRuns`}>
                This project has no test runs yet. A run summary reports on one run, so there is
                nothing to report on until a run has been started.
              </Alert>
            )}
            {!regenerating && (
            <TextField
              label="Report"
              required
              select
              defaultValue={TYPES[0].value}
              error={!!errors.type}
              helperText={
                errors.type?.message ??
                (TYPES.find((entry) => entry.value === type) ?? TYPES[0]).hint
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Type` } }}
              {...register('type')}
            >
              {TYPES.map((entry) => (
                <MenuItem
                  key={entry.value}
                  value={entry.value}
                  data-testid={`${TestId}.Type.${entry.value}`}
                >
                  {entry.label}
                </MenuItem>
              ))}
            </TextField>
            )}
            {isCoverage && pages.isSuccess && documents.length === 0 && (
              <Alert severity="info" data-testid={`${TestId}.NoDocuments`}>
                This project has no documentation pages yet. A coverage matrix reports on the
                statements a document makes, so there is nothing to report on until one is written.
              </Alert>
            )}
            {needsRun && (
              <TextField
                label="Run"
                required
                select
                defaultValue=""
                error={!!errors.runId}
                helperText={
                  errors.runId?.message ??
                  'Which run the numbers come from. Frozen onto the report so it can be reproduced.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Run` } }}
                {...register('runId')}
              >
                {available.map((run) => (
                  <MenuItem key={run.id} value={run.id} data-testid={`${TestId}.Run.${run.key}`}>
                    <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                      <Typography variant="body2">
                        {run.key} · {run.name}
                      </Typography>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {runStatusLabels[run.status]}
                      </Typography>
                    </Stack>
                  </MenuItem>
                ))}
              </TextField>
            )}
            {isCoverage && (
              <TextField
                label="Document"
                required
                select
                defaultValue=""
                error={!!errors.docPageId}
                helperText={
                  errors.docPageId?.message ??
                  'The page whose structure the report follows. Its statements are the rows.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Document` } }}
                {...register('docPageId')}
              >
                {documents.map((page) => (
                  <MenuItem
                    key={page.id}
                    value={page.id}
                    data-testid={`${TestId}.Document.${page.key}`}
                  >
                    <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                      <Typography variant="body2">
                        {page.key} · {page.title}
                      </Typography>
                      {/*
                        The status, because a coverage report over a DRAFT specification is a
                        different claim from one over a PUBLISHED one — the report prints it in its
                        own metadata, and the picker says it before the choice rather than after.
                      */}
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {docPageStatusLabel[page.status]}
                      </Typography>
                    </Stack>
                  </MenuItem>
                ))}
              </TextField>
            )}
            {isAggregate && (
              <>
                {/*
                  The policy is a picker and never a free field, and it is REQUIRED with a default
                  rather than optional: this report spans many runs, so a case with four results
                  has to be resolved to one somehow, and the arithmetic that does it is the
                  difference between two teams reading "94% pass" and meaning different things
                  (plan §3, detail 9). The help text under it is the same sentence the plan page
                  shows for the same value — one map, two screens.
                */}
                <TextField
                  label="Aggregation policy"
                  required
                  select
                  defaultValue="LATEST"
                  error={!!errors.policy}
                  helperText={errors.policy?.message ?? aggregationHelp[policy]}
                  slotProps={{ htmlInput: { 'data-testid': `${TestId}.Policy` } }}
                  {...register('policy')}
                >
                  {POLICIES.map((value) => (
                    <MenuItem
                      key={value}
                      value={value}
                      data-testid={`${TestId}.Policy.${value}`}
                    >
                      {aggregationLabels[value]}
                    </MenuItem>
                  ))}
                </TextField>
                <Controller
                  control={control}
                  name="planIds"
                  render={({ field }) => (
                    <TextField
                      label="Plans"
                      select
                      /*
                        The testid is on the ROOT and not on the input: a `multiple` select writes
                        through a hidden input MUI owns, and MUI's own props type refuses a
                        `data-*` on the display element. Tests reach the control by its role and
                        label, which is what a person does too.
                      */
                      data-testid={`${TestId}.Plans`}
                      value={field.value}
                      onChange={field.onChange}
                      onBlur={field.onBlur}
                      inputRef={field.ref}
                      helperText="Every plan when none is chosen. Results recorded outside any plan are excluded once one is."
                      slotProps={{
                        select: {
                          multiple: true,
                          renderValue: (selected) =>
                            `${(selected as string[]).length} selected`,
                        },
                      }}
                    >
                      {(plans.data ?? []).map((plan) => (
                        <MenuItem
                          key={plan.id}
                          value={plan.id}
                          data-testid={`${TestId}.Plans.${plan.key}`}
                        >
                          {plan.key} · {plan.name}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <Controller
                  control={control}
                  name="featureIds"
                  render={({ field }) => (
                    <TextField
                      label="Features"
                      select
                      data-testid={`${TestId}.Features`}
                      disabled={!features || features.values.length === 0}
                      value={field.value}
                      onChange={field.onChange}
                      onBlur={field.onBlur}
                      inputRef={field.ref}
                      // Union, not intersection — the contract's reading, said here because the
                      // opposite one is what a person assumes when they tick two boxes.
                      helperText={
                        features && features.values.length > 0
                          ? 'Cases classified under any of these. Every feature when none is chosen.'
                          : 'This project has no features in its vocabulary yet.'
                      }
                      slotProps={{
                        select: {
                          multiple: true,
                          renderValue: (selected) =>
                            `${(selected as string[]).length} selected`,
                        },
                      }}
                    >
                      {(features?.values ?? []).map((value) => (
                        <MenuItem
                          key={value.id}
                          value={value.id}
                          data-testid={`${TestId}.Features.${value.id}`}
                        >
                          {/* The path, so two features of the same name under different parents
                              are distinguishable — the picker is a filter and picking the wrong
                              one produces a plausible report of the wrong thing. */}
                          {value.path.split('.').join(' / ')}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <Stack direction="row" sx={{ gap: 2 }}>
                  <TextField
                    label="From"
                    type="date"
                    fullWidth
                    error={!!errors.from}
                    helperText={errors.from?.message ?? 'Inclusive. The beginning when empty.'}
                    slotProps={{
                      inputLabel: { shrink: true },
                      htmlInput: { 'data-testid': `${TestId}.From` },
                    }}
                    {...register('from')}
                  />
                  <TextField
                    label="To"
                    type="date"
                    fullWidth
                    error={!!errors.to}
                    // "Inclusive" is a claim this form has to make good on: the API's `to` is
                    // exclusive, so `dayAfter` is what keeps the last day in the report.
                    helperText={errors.to?.message ?? 'Inclusive — the whole of that day. Now when empty.'}
                    slotProps={{
                      inputLabel: { shrink: true },
                      htmlInput: { 'data-testid': `${TestId}.To` },
                    }}
                    {...register('to')}
                  />
                </Stack>
              </>
            )}
            <TextField
              label="Format"
              required
              select
              defaultValue="HTML"
              error={!!errors.format}
              // The hint is the selected format's own, so the picker teaches what each one is for
              // rather than leaving three acronyms to be guessed at.
              helperText={errors.format?.message ?? reportFormatHints[format]}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Format` } }}
              {...register('format')}
            >
              {FORMATS.map((value) => (
                <MenuItem key={value} value={value} data-testid={`${TestId}.Format.${value}`}>
                  {reportFormatLabels[value]}
                </MenuItem>
              ))}
            </TextField>
            {/*
              Optional, and the helper text says what "saved" does and does not cover: the format is
              chosen per download and is NOT part of a definition, which is a surprise worth
              spending a clause on rather than discovering by regenerating one.

              Not rendered while regenerating — saving a definition that came from a definition is
              a second copy of the same question, and the parameters this form would save in that
              mode are the empty ones it did not render.
            */}
            {!regenerating && (
              <TextField
                label="Save these settings as"
                error={!!errors.saveAs}
                helperText={
                  errors.saveAs?.message ??
                  'Optional. A saved report keeps its selection so the same one can be produced next release — the format is chosen each time.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.SaveAs` } }}
                {...register('saveAs')}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            // `!isAggregate &&` is the whole of R6's frontend gate change, and it is the line the
            // test for it had to be written to fail without: a project with no runs may still ask
            // for an aggregate report, whose honest answer is a report saying nothing has been
            // recorded yet.
            disabled={
              generate.isPending ||
              save.isPending ||
              (needsRun && available.length === 0) ||
              (isCoverage && documents.length === 0)
            }
            data-testid={`${TestId}.Submit`}
          >
            {save.isPending
              ? 'Saving…'
              : generate.isPending
                ? 'Requesting…'
                : saveAs.trim() !== ''
                  ? 'Save and generate'
                  : 'Generate'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
