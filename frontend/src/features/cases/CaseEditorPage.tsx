import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useSnackbar } from 'notistack';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCase, useClassification, useCreateCase, useSuites, useUpdateCase } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { automationLabels, priorityLabels, stepFormatLabels } from '../../lib/labels';
import type { TestCase } from '../../api/types';

/**
 * Case authoring form.
 *
 * The schema below is the single source of truth for what a case *is* on the client, and it
 * is deliberately narrower than the read model: it contains only fields the update path
 * actually persists. That is field gating by construction — TypeScript won't let the form
 * bind something the API ignores, so an edit can never show a success toast and quietly
 * discard the value, which is the highest-severity form defect there is.
 *
 * Notably absent: `lifecycleStatus`. Status moves through explicit review and approval
 * actions with their own side effects, so it is not an editable dropdown here — a `<select>`
 * would bypass the transition guards entirely.
 */

const StepSchema = z.object({
  id: z.string().nullable().optional(),
  keyword: z.string().nullable().optional(),
  action: z.string().min(1, 'Describe the action.'),
  testData: z.string().nullable().optional(),
  expectedResult: z.string().nullable().optional(),
  sharedStepId: z.string().nullable().optional(),
});

const CaseSchema = z
  .object({
    title: z.string().min(1, 'Enter a title.').max(300, 'Keep the title under 300 characters.'),
    suiteId: z.string().min(1, 'Choose a suite.'),
    objective: z.string().nullable(),
    description: z.string().nullable(),
    expectedOutcome: z.string().nullable(),
    priority: z.enum(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']),
    automationStatus: z.enum(['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE']),
    automationKey: z.string().nullable(),
    notAutomatableReason: z.string().nullable(),
    // Held as the string the number input actually produces and converted at submit, rather
    // than coerced in the schema: a coercion makes the schema's input and output types
    // diverge, which then fights the form's own typing for no benefit here.
    estimatedDurationSec: z
      .string()
      .nullable()
      .refine((value) => !value || /^\d+$/.test(value), 'Enter a whole number of seconds.'),
    stepFormat: z.enum(['STRUCTURED', 'GHERKIN']),
    gherkinSource: z.string().nullable(),
    steps: z.array(StepSchema),
    changeComment: z.string().nullable(),
  })
  // Cross-field rules the server also enforces. Both live in the schema rather than in the
  // submit handler, so the message lands on the field that caused it.
  .refine((data) => data.stepFormat !== 'GHERKIN' || !!data.gherkinSource?.trim(), {
    path: ['gherkinSource'],
    message: 'Write the scenario, or switch to structured steps.',
  })
  .refine((data) => data.stepFormat !== 'STRUCTURED' || data.steps.length > 0, {
    path: ['steps'],
    message: 'Add at least one step, or switch to Gherkin.',
  })
  .refine((data) => data.automationStatus !== 'NOT_AUTOMATABLE' || !!data.notAutomatableReason?.trim(), {
    path: ['notAutomatableReason'],
    message: 'Say why this case cannot be automated.',
  })
  .refine((data) => data.automationStatus !== 'AUTOMATED' || !!data.automationKey?.trim(), {
    path: ['automationKey'],
    message: 'Give the automated test that covers this case, so results can be matched to it.',
  });

type CaseInput = z.infer<typeof CaseSchema>;

const TestIdPrefix = 'CaseEditor';

/**
 * MUI types `SelectDisplayProps` as plain HTML attributes, which excludes `data-*`. The
 * attribute reaches the DOM correctly; only the type is too narrow, so it's asserted in one
 * helper rather than at every select.
 */
const selectTestId = (id: string) => ({ 'data-testid': id }) as React.HTMLAttributes<HTMLDivElement>;

const GHERKIN_TEMPLATE = `Feature:

  Scenario:
    Given
    When
    Then `;

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
  const suites = useSuites(projectId);
  const dimensions = useClassification(projectId);

  const createCase = useCreateCase(projectId);
  const updateCase = useUpdateCase(projectId, existing?.id ?? '');

  const {
    control,
    register,
    handleSubmit,
    watch,
    setValue,
    setError,
    setFocus,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<CaseInput>({
    resolver: zodResolver(CaseSchema),
    mode: 'onBlur',
    defaultValues: {
      title: existing?.title ?? '',
      suiteId: existing?.suiteId ?? '',
      objective: existing?.objective ?? '',
      description: existing?.description ?? '',
      expectedOutcome: existing?.expectedOutcome ?? '',
      priority: existing?.priority ?? 'MEDIUM',
      automationStatus: existing?.automationStatus ?? 'MANUAL',
      automationKey: existing?.automationKey ?? '',
      notAutomatableReason: existing?.notAutomatableReason ?? '',
      estimatedDurationSec:
        existing?.estimatedDurationSec === null || existing?.estimatedDurationSec === undefined
          ? ''
          : String(existing.estimatedDurationSec),
      stepFormat: existing?.stepFormat ?? 'STRUCTURED',
      gherkinSource: existing?.gherkinSource ?? '',
      steps: (existing?.steps ?? []).map((step) => ({
        id: step.id,
        keyword: step.keyword ?? null,
        action: step.action,
        testData: step.testData ?? '',
        expectedResult: step.expectedResult ?? '',
        sharedStepId: step.sharedStepId ?? null,
      })),
      changeComment: '',
    },
  });

  /*
    Tells the app this surface is holding work that a navigation or reload would destroy.

    Both automatic recoveries consult it: the deploy-skew handler prompts instead of reloading,
    and the session ladder shows the in-place re-auth dialog rather than falling through to a
    redirect. A dirty editor is precisely the case where a well-meaning automatic recovery does
    the most damage.
  */
  useUnsavedWork(isDirty && !isSubmitting, mode === 'edit' ? 'an edited test case' : 'a new test case');

  const steps = useFieldArray({ control, name: 'steps' });
  const stepFormat = watch('stepFormat');
  const automationStatus = watch('automationStatus');

  const onSubmit = handleSubmit(async (data) => {
    // Only the chosen format's content is sent. Posting both would leave the other one
    // stale in the record, and the next reader can't tell which is authoritative.
    const payload: Record<string, unknown> = {
      title: data.title.trim(),
      suiteId: data.suiteId,
      objective: data.objective || null,
      description: data.description || null,
      expectedOutcome: data.expectedOutcome || null,
      priority: data.priority,
      severity: data.priority,
      automationStatus: data.automationStatus,
      automationKey: data.automationStatus === 'AUTOMATED' ? data.automationKey || null : null,
      notAutomatableReason:
        data.automationStatus === 'NOT_AUTOMATABLE' ? data.notAutomatableReason || null : null,
      estimatedDurationSec: data.estimatedDurationSec ? Number(data.estimatedDurationSec) : null,
      stepFormat: data.stepFormat,
      gherkinSource: data.stepFormat === 'GHERKIN' ? data.gherkinSource : null,
      steps:
        data.stepFormat === 'STRUCTURED'
          ? data.steps.map((step) => ({
              id: step.id ?? null,
              keyword: null,
              action: step.action,
              testData: step.testData || null,
              expectedResult: step.expectedResult || null,
              sharedStepId: step.sharedStepId ?? null,
            }))
          : [],
      changeComment: data.changeComment || null,
    };

    try {
      if (mode === 'edit' && existing) {
        // The version we read is sent back, so a concurrent save is rejected rather than
        // silently overwritten.
        const saved = await updateCase.mutateAsync({ ...payload, lockVersion: existing.lockVersion });
        enqueueSnackbar(`Saved ${saved.key} as v${saved.currentVersionNo}`, { variant: 'success' });
        navigate(`/projects/${projectId}/cases/${saved.id}`);
      } else {
        const created = await createCase.mutateAsync(payload);
        enqueueSnackbar(`Created ${created.key}`, { variant: 'success' });
        navigate(`/projects/${projectId}/cases/${created.id}`);
      }
    } catch (error) {
      // Field-keyed server errors land on their fields; only genuinely non-field failures
      // become a banner. A "Something went wrong" message over a form the server told us
      // how to fix is a wasted round trip for the user.
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
            ? error.status === 409
              ? error.detail
              : error.detail
            : "Couldn't save the case. Check your connection and try again.",
      });
    }
  });

  const featureDimension = dimensions.data?.find((d) => d.key === 'feature');

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
        actions={
          <>
            <Button
              onClick={() =>
                navigate(existing ? `/projects/${projectId}/cases/${existing.id}` : `/projects/${projectId}/cases`)
              }
              data-testid={`${TestIdPrefix}.Cancel`}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="contained"
              disabled={isSubmitting || (mode === 'edit' && !isDirty)}
              title={mode === 'edit' && !isDirty ? 'Nothing has changed yet' : undefined}
              data-testid={`${TestIdPrefix}.Submit`}
            >
              {isSubmitting ? 'Saving…' : mode === 'edit' ? 'Save changes' : 'Create case'}
            </Button>
          </>
        }
      />

      {errors.root && (
        <Alert severity="error" role="alert" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Error`}>
          {errors.root.message}
        </Alert>
      )}

      <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'flex-start' }}>
        <Stack sx={{ gap: 3, flex: 1, minWidth: 0, width: '100%' }}>
          <Panel title="What this case proves" testId={`${TestIdPrefix}.Summary`}>
            <Stack sx={{ gap: 2 }}>
              <TextField
                label="Title"
                required
                fullWidth
                error={!!errors.title}
                helperText={errors.title?.message ?? 'One sentence stating what is verified.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Title` },
                  formHelperText: { role: errors.title ? 'alert' : undefined },
                }}
                {...register('title')}
              />
              <TextField
                label="Objective"
                fullWidth
                multiline
                minRows={2}
                error={!!errors.objective}
                helperText={errors.objective?.message}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Objective` } }}
                {...register('objective')}
              />
              <TextField
                label="Description"
                fullWidth
                multiline
                minRows={3}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.Description` } }}
                {...register('description')}
              />
              <TextField
                label="Expected outcome"
                fullWidth
                multiline
                minRows={2}
                helperText="The case-level outcome, alongside the per-step expected results."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ExpectedOutcome` } }}
                {...register('expectedOutcome')}
              />
            </Stack>
          </Panel>

          <Panel
            title="Steps"
            testId={`${TestIdPrefix}.Steps`}
            actions={
              <Controller
                control={control}
                name="stepFormat"
                render={({ field }) => (
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    value={field.value}
                    onChange={(_event, value) => {
                      if (!value) return;
                      field.onChange(value);
                      // Seed the editor the user is switching into, so they land on
                      // something writable rather than an empty box.
                      if (value === 'GHERKIN' && !watch('gherkinSource')) {
                        setValue('gherkinSource', GHERKIN_TEMPLATE, { shouldDirty: true });
                      }
                      if (value === 'STRUCTURED' && steps.fields.length === 0) {
                        steps.append({ action: '', testData: '', expectedResult: '' });
                      }
                    }}
                    aria-label="Step format"
                  >
                    {(['STRUCTURED', 'GHERKIN'] as const).map((format) => (
                      <ToggleButton
                        key={format}
                        value={format}
                        data-testid={`${TestIdPrefix}.StepFormat.${format}`}
                      >
                        {stepFormatLabels[format]}
                      </ToggleButton>
                    ))}
                  </ToggleButtonGroup>
                )}
              />
            }
          >
            {/*
              A case carries one format or the other, never both — the toggle switches which
              editor is mounted, and only the active one is submitted.
            */}
            {stepFormat === 'GHERKIN' ? (
              <TextField
                label="Scenario"
                fullWidth
                multiline
                minRows={12}
                error={!!errors.gherkinSource}
                helperText={errors.gherkinSource?.message ?? 'Gherkin. Keywords are recognized on the detail view.'}
                slotProps={{
                  htmlInput: {
                    'data-testid': `${TestIdPrefix}.Field.GherkinSource`,
                    style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem', lineHeight: 1.7 },
                  },
                  formHelperText: { role: errors.gherkinSource ? 'alert' : undefined },
                }}
                {...register('gherkinSource')}
              />
            ) : (
              <Stack sx={{ gap: 2 }}>
                {errors.steps?.message && (
                  <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Steps.Error`}>
                    {errors.steps.message}
                  </Alert>
                )}

                {steps.fields.map((field, index) => (
                  <Box
                    key={field.id}
                    data-testid={`${TestIdPrefix}.Steps.Row`}
                    sx={{ p: 1.5, border: '1px solid', borderColor: 'divider', borderRadius: 1 }}
                  >
                    <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 1 }}>
                      <Identifier variant="muted">Step {index + 1}</Identifier>
                      <Box sx={{ flex: 1 }} />
                      {/*
                        Reordering is safe because a step keeps its stable id: results and
                        evidence anchor to the id, not the position, so moving a step never
                        reassigns the evidence attached to it.
                      */}
                      <IconButton
                        size="small"
                        disabled={index === 0}
                        onClick={() => steps.move(index, index - 1)}
                        aria-label={`Move step ${index + 1} up`}
                        data-testid={`${TestIdPrefix}.Steps.MoveUp`}
                      >
                        <ArrowUpwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        disabled={index === steps.fields.length - 1}
                        onClick={() => steps.move(index, index + 1)}
                        aria-label={`Move step ${index + 1} down`}
                        data-testid={`${TestIdPrefix}.Steps.MoveDown`}
                      >
                        <ArrowDownwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        onClick={() => steps.remove(index)}
                        aria-label={`Remove step ${index + 1}`}
                        data-testid={`${TestIdPrefix}.Steps.Remove`}
                      >
                        <DeleteOutlineIcon fontSize="small" />
                      </IconButton>
                    </Stack>

                    <Stack sx={{ gap: 1.5 }}>
                      <TextField
                        label="Action"
                        required
                        fullWidth
                        multiline
                        error={!!errors.steps?.[index]?.action}
                        helperText={errors.steps?.[index]?.action?.message}
                        slotProps={{
                          htmlInput: { 'data-testid': `${TestIdPrefix}.Steps.Field.Action` },
                          formHelperText: { role: errors.steps?.[index]?.action ? 'alert' : undefined },
                        }}
                        {...register(`steps.${index}.action`)}
                      />
                      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5 }}>
                        <TextField
                          label="Test data"
                          fullWidth
                          multiline
                          slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Steps.Field.TestData` } }}
                          {...register(`steps.${index}.testData`)}
                        />
                        <TextField
                          label="Expected result"
                          fullWidth
                          multiline
                          slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Steps.Field.ExpectedResult` } }}
                          {...register(`steps.${index}.expectedResult`)}
                        />
                      </Stack>
                    </Stack>
                  </Box>
                ))}

                <Button
                  startIcon={<AddIcon />}
                  onClick={() => steps.append({ action: '', testData: '', expectedResult: '' })}
                  data-testid={`${TestIdPrefix}.Steps.AddButton`}
                  sx={{ alignSelf: 'flex-start' }}
                >
                  Add step
                </Button>
              </Stack>
            )}
          </Panel>

          {mode === 'edit' && (
            <Panel title="Change note" testId={`${TestIdPrefix}.ChangeComment`}>
              <TextField
                label="What changed, and why"
                fullWidth
                multiline
                minRows={2}
                // Deliberately optional: mandatory comments produce a thousand entries
                // reading "update", which is worse than none because they look informative.
                helperText="Optional. It appears on the version timeline, so make it worth reading."
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Field.ChangeComment` } }}
                {...register('changeComment')}
              />
            </Panel>
          )}
        </Stack>

        <Box sx={{ width: { xs: '100%', lg: 320 }, flexShrink: 0 }}>
          <Stack sx={{ gap: 3 }}>
            <Panel title="Placement" testId={`${TestIdPrefix}.Placement`}>
              <Controller
                control={control}
                name="suiteId"
                render={({ field, fieldState }) => (
                  <TextField
                    select
                    label="Suite"
                    required
                    fullWidth
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    error={!!fieldState.error}
                    helperText={fieldState.error?.message}
                    slotProps={{
                      select: { native: false, SelectDisplayProps: selectTestId(`${TestIdPrefix}.Field.Suite`) },
                      formHelperText: { role: fieldState.error ? 'alert' : undefined },
                    }}
                  >
                    {(suites.data ?? []).map((suite) => (
                      <MenuItem key={suite.id} value={suite.id}>
                        <Box component="span" sx={{ pl: (suite.path.split('.').length - 1) * 1.5 }}>
                          {suite.name}
                        </Box>
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            </Panel>

            <Panel title="Classification" testId={`${TestIdPrefix}.Classification`}>
              <Stack sx={{ gap: 2 }}>
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
                <TextField
                  label="Estimated duration (seconds)"
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
                {/*
                  Features are read-only here on purpose. The feature tree is a MULTI
                  hierarchical dimension whose assignment has its own picker; rendering it as
                  a flat select would let someone set a parent where a leaf is required.
                */}
                {existing && existing.features.length > 0 && (
                  <Box>
                    <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.5 }}>
                      Features
                    </Typography>
                    <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                      {existing.features.map((feature) => (
                        <Chip key={feature} size="small" variant="outlined" label={feature} />
                      ))}
                    </Stack>
                    <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.5 }}>
                      Assigned from the {featureDimension?.name ?? 'feature'} tree.
                    </Typography>
                  </Box>
                )}
              </Stack>
            </Panel>

            <Panel title="Automation" testId={`${TestIdPrefix}.Automation`}>
              <Stack sx={{ gap: 2 }}>
                <Controller
                  control={control}
                  name="automationStatus"
                  render={({ field }) => (
                    <TextField
                      select
                      label="Automation status"
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

                {/* These two fields appear only when their status makes them meaningful, and
                    each is required by the schema in exactly that case. */}
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
    </Box>
  );
}
