import {
  Alert,
  Autocomplete,
  Box,
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
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import {
  useCases,
  useConfigurations,
  useCreateRun,
  usePlans,
  useProjectMembers,
  useProjectTags,
  useSuites,
} from '../../api/queries';
import type { TestRun } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';

/**
 * Starting a run — **the affordance that materialises a checklist**.
 *
 * Creating a run is not creating a row. The server resolves the scope, resolves each case's
 * current version, and writes one execution per case pinned to it. That is the point of the whole
 * slice: until it existed, the execution screen — the product's most important one — could not be
 * reached from the UI at all, because nothing could produce a thing to record against.
 *
 * ## ~~The plan is required by this form, and optional in the contract~~
 *
 * ~~`test_run.plan_id` is nullable and the API accepts an ad-hoc run scoped by `caseIds`. That path
 * belongs to a "run selected cases" affordance on the case grid, where a selection already exists.
 * Offering it here would mean building a second case picker whose only use is to reconstruct, by
 * hand, the set a plan already names — and a run created that way is one nobody can later ask
 * "against which plan?" of.~~
 *
 * → **superseded by §99.** The argument was about *reconstructing* a plan's scope by hand, and it
 * held while a case list was the only other way to say what a run covers. It stops holding once
 * suites and tags are arms: "everything tagged `smoke`" is not a plan written out longhand, it is
 * a scope no plan in the project need express, and the whole reason the arms were added is that
 * people ask for exactly that run.
 *
 * So the plan is now **one of four ways** to scope a run and no longer required — and a project
 * with no plans is no longer a dead end, which is the change a new project feels first. Its case
 * count is still shown beside it, because "how long will this run be" is the question somebody is
 * actually asking at this moment.
 *
 * ## The arms replace the plan's scope, and the form has to say so
 *
 * The server unions the three arms and, when any is present, ignores the plan's scope — the plan
 * stays recorded as the association. Nothing about a form with four filled-in controls suggests
 * that, so the helper text under the scope block states it, and the plan select's own text changes
 * when an arm is in play. A rule the client cannot see is one it teaches by producing surprises.
 *
 * ## Configuration and assignee — added by A4, and both were *contract fields with no control*
 *
 * `TestRunInput` has carried `configurationId` and `assigneeId` since M2 and this form sent `null`
 * for both, in two lines with a comment each. So every run in the product ran in the project's
 * default environment and every execution in it was permanently unassigned — not because anybody
 * chose that, but because the form had no way to say anything else.
 *
 * - **Configuration** waited on a read: the list lived only on `TestPlanDetail`, so a run scoped by
 *   suites or tags rather than by a plan (§99) had nowhere to get it from. `GET /configurations`
 *   is that read, and "the project's default" stays the first option rather than becoming an
 *   unlabelled blank — a project is seeded with exactly one, so most runs mean it.
 * - **Assignee** assigns the *whole* checklist, which is a real thing to want at 9am on a
 *   regression day and the wrong thing to apply quietly. It defaults to nobody, which is what an
 *   unassigned checklist a team picks up from means, and per-row reassignment afterwards is the
 *   runner's own control (`ExecutionPage`).
 */

/** Mirrors `RunEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `RunEdit.MAX_RELEASE`. */
const MAX_RELEASE = 100;
/** Mirrors `RunEdit.MAX_TAG`, itself a copy of `CaseEdit`'s cap. */
const MAX_TAG = 60;
/**
 * Mirrors `RunEdit.MAX_BRANCH`, and deliberately not `MAX_RELEASE`: a ref name is a path a tool
 * generates (`dependabot/gradle/org.springframework.boot-3.5.6`), not a short identifier a human
 * types.
 */
const MAX_BRANCH = 255;

const RunSchema = z
  .object({
    name: z
      .string()
      .min(1, 'Enter a run name.')
      .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
    // No longer `.min(1)` — §99. Empty means "not against a plan", which is a run this product
    // now supports and the reason the arms exist.
    planId: z.string(),
    caseIds: z.array(z.string()),
    suiteIds: z.array(z.string()),
    tags: z.array(z.string().max(MAX_TAG, `Keep each tag under ${MAX_TAG} characters.`)),
    releaseVersion: z.string().max(MAX_RELEASE).optional(),
    buildNumber: z.string().max(MAX_RELEASE).optional(),
    /*
      §177. The SHAPE is not checked here and that is the decision, not an omission: the server
      refuses anything that is not 7–40 hex characters, and a client-side copy of that rule is a
      second answer to "what is a commit id" which would then have to be kept equal to the one the
      backend already keeps in two places. What the client owns is the length, because a maxLength
      the input enforces is a paste the caller sees truncated rather than a 400.
    */
    commitSha: z.string().optional(),
    branch: z.string().max(MAX_BRANCH).optional(),
    // '' is a value on both, and means the same thing the wire's null means: "the project's
    // default" and "nobody". Neither is validated here — the server owns "is that a configuration
    // of this project" and "is that an active member", and a client-side copy of either would be a
    // second answer built from a list this form fetched at a different instant.
    configurationId: z.string(),
    assigneeId: z.string(),
  })
  /*
    A run needs somewhere to get its cases from, and this is the one rule the client can check
    without guessing: it is about what the form *says*, not about what anything resolves to. The
    server still owns "and does that resolve to something", which the client cannot know — a tag
    nobody has used and a tag everybody uses look identical here.

    On `caseIds` because it is the first scope control on the form, and a message with no control
    under it is one nobody reads.
  */
  .superRefine((values, ctx) => {
    const empty =
      values.planId === '' &&
      values.caseIds.length === 0 &&
      values.suiteIds.length === 0 &&
      values.tags.length === 0;
    if (empty) {
      ctx.addIssue({
        code: 'custom',
        path: ['caseIds'],
        message: 'Choose a plan, or the cases, suites or tags this run covers.',
      });
    }
  });

type RunFormInput = z.infer<typeof RunSchema>;

const TestId = 'Runs.Create';

type FormField =
  | 'name'
  | 'planId'
  | 'caseIds'
  | 'suiteIds'
  | 'tags'
  | 'releaseVersion'
  | 'buildNumber'
  | 'commitSha'
  | 'branch'
  | 'configurationId'
  | 'assigneeId';

const FIELDS: readonly FormField[] = [
  'name',
  'planId',
  'caseIds',
  'suiteIds',
  'tags',
  'releaseVersion',
  'buildNumber',
  'commitSha',
  'branch',
  'configurationId',
  'assigneeId',
];

function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        // A sentence about a field this form has no control for goes to the banner rather than
        // nowhere — a dropped field error is a submit the user watches do nothing. §99 moved
        // `caseIds`, `suiteIds` and `tags` out of this branch by giving each of them a control,
        // and A4 moved `configurationId` and `assigneeId` out the same way, so `FIELDS` now covers
        // every field the server can name. **The branch stays** and stays tested: it is the
        // fallback for the next field the contract grows, and the failure it prevents is silent.
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

export function RunCreateDialog({
  projectId,
  open,
  onClose,
  onCreated,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (run: TestRun) => void;
}) {
  const create = useCreateRun(projectId);
  const plans = usePlans(projectId);
  const suites = useSuites(projectId);
  const tags = useProjectTags(projectId);
  /*
    The case picker's catalogue, and its limit is `PlanCasesTab`'s, stated for the same reason:
    there is no by-ids read on the contract, so this is the first page and a project past it has
    cases this control cannot offer. That is the acceptable half of the trade — a run of two
    hundred hand-picked cases is what the suite and tag arms exist to replace.
  */
  const catalogue = useCases(projectId, { size: 200 }, 'key,asc');
  // Both gated on `open`, like the board's member picker: a run list nobody is starting a run from
  // should not be asking who could be assigned one or what environments exist.
  const configurations = useConfigurations(projectId, open);
  const members = useProjectMembers(projectId, open);

  const {
    register,
    control,
    watch,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<RunFormInput>({
    resolver: zodResolver(RunSchema),
    defaultValues: {
      name: '',
      planId: '',
      caseIds: [],
      suiteIds: [],
      tags: [],
      releaseVersion: '',
      buildNumber: '',
      commitSha: '',
      branch: '',
      configurationId: '',
      assigneeId: '',
    },
    mode: 'onBlur',
  });

  /*
    Whether the plan's scope is in play. Watched rather than computed at submit, because it decides
    what the plan control SAYS — the server's substitution rule is invisible in a form where all
    four controls look equally live, and the first time somebody meets it should not be a run half
    the length they expected.
  */
  const armed =
    watch('caseIds').length > 0 || watch('suiteIds').length > 0 || watch('tags').length > 0;

  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  /*
    §82. §6g's gating rule refuses a run whose cases are not approved, and it is the ONLY 409 this
    endpoint answers — every other refusal here is a field error. So the status alone identifies it,
    and the client never has to hold a copy of the rule or match the server's sentence.

    The refusal itself lands in the root alert through `applyServerError` (a 409 carries no `errors`
    map), which is where the server's list of unapproved keys is read. This flag only decides
    whether the follow-up affordance appears beside it — `ui-rules`: an error naming an action the
    UI does not offer is a design bug.
  */
  const [gateRefused, setGateRefused] = useState(false);

  const createRun = async (values: RunFormInput, overrideApprovalGate: boolean) => {
    setGateRefused(false);
    try {
      const created = await create.mutateAsync({
        name: values.name.trim(),
        // Null, not '' — `planId` is a nullable uuid on the wire and an empty string is a 400 on
        // format rather than the "no plan" this control means.
        planId: values.planId || null,
        // Null still means "the project's default configuration", which every project is seeded
        // with — it is now what the first option of a real select sends rather than a hard-coded
        // line with no way to say anything else.
        configurationId: values.configurationId || null,
        releaseVersion: values.releaseVersion?.trim() || null,
        buildNumber: values.buildNumber?.trim() || null,
        commitSha: values.commitSha?.trim() || null,
        branch: values.branch?.trim() || null,
        caseIds: values.caseIds,
        suiteIds: values.suiteIds,
        tags: values.tags,
        // Assigns EVERY execution this create materialises. Null is the ordinary answer — a
        // checklist the team picks up from — and it is now a choice rather than the only option.
        assigneeId: values.assigneeId || null,
        // Omitted rather than sent false, so an ordinary create is byte-identical to what every
        // caller sent before this slice.
        ...(overrideApprovalGate ? { overrideApprovalGate: true } : {}),
      });
      onCreated(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
      // Not re-offered after an override that was itself refused: the caller lacks `case:approve`,
      // and a button that produces the same refusal twice is an affordance the product does not
      // have. The disabled control below is what tells them so the first time.
      setGateRefused(!overrideApprovalGate && error instanceof ApiError && error.status === 409);
    }
  };

  const submit = handleSubmit((values) => createRun(values, false));

  /*
    Re-validates before retrying rather than reusing the values the first attempt sent — the form is
    still open and editable, so the run created by "create it anyway" must be the one on screen now.
  */
  const submitWithOverride = handleSubmit((values) => createRun(values, true));

  const available = plans.data ?? [];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={create.isPending}>
        <DialogTitle>New test run</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
                {/*
                  §6g's lead override, offered only where the gate actually refused. Disabled rather
                  than hidden for a caller without `case:approve` (§31), so the way out is
                  discoverable by the person who has to go and ask for it — and the gate's tooltip
                  is what names who to ask. Inside the alert because it is the answer to this
                  sentence, not a second action on the dialog.
                */}
                {gateRefused && (
                  <PermissionGate projectId={projectId} permission="case:approve">
                    {({ allowed }) => (
                      <Button
                        size="small"
                        color="warning"
                        sx={{ mt: 1, display: 'block' }}
                        disabled={!allowed || create.isPending}
                        data-testid={`${TestId}.OverrideGate`}
                        onClick={() => void submitWithOverride()}
                      >
                        Create it anyway
                      </Button>
                    )}
                  </PermissionGate>
                )}
              </Alert>
            )}
            {plans.isSuccess && available.length === 0 && (
              <Alert severity="info" data-testid={`${TestId}.NoPlans`}>
                This project has no test plans yet — choose the suites, tags or cases this run
                covers instead.
              </Alert>
            )}
            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={errors.name?.message ?? 'What this pass is — “Regression 4.3.0”.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />
            <TextField
              label="Plan"
              select
              defaultValue=""
              error={!!errors.planId}
              helperText={
                errors.planId?.message ??
                (armed
                  ? 'Recorded as this run’s plan. Its scope is not used — the choices below are.'
                  : 'Every case in the plan’s scope becomes a row to record against.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Plan` } }}
              {...register('planId')}
            >
              {/*
                An explicit "no plan" row rather than a blank one, for `CaseEditorPage`'s reason:
                since §99 a run against no plan is a supported thing to want, and a blank first
                option reads as a field somebody forgot to fill in.
              */}
              <MenuItem value="" data-testid={`${TestId}.Plan.None`}>
                <Box component="span" sx={{ color: 'text.secondary' }}>
                  No plan — scope it below
                </Box>
              </MenuItem>
              {available.map((plan) => (
                <MenuItem key={plan.id} value={plan.id} data-testid={`${TestId}.Plan.${plan.key}`}>
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                    <Typography variant="body2">
                      {plan.key} · {plan.name}
                    </Typography>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {formatCount(plan.caseCount)} cases
                    </Typography>
                  </Stack>
                </MenuItem>
              ))}
            </TextField>

            {/*
              §99's three arms. Unioned by the server and deduped, so a case reached by two of them
              is one row on the checklist — which is why nothing here tries to add up a total: the
              honest number is the server's, after resolution, and a client-side sum of three
              overlapping sets is the "two contradictory percentages" defect in a new place.
            */}
            <Controller
              control={control}
              name="suiteIds"
              render={({ field, fieldState }) => (
                <Autocomplete
                  multiple
                  options={[...(suites.data ?? [])].sort(
                    (a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key),
                  )}
                  getOptionLabel={(suite) => suite.name}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  value={(suites.data ?? []).filter((suite) => field.value.includes(suite.id))}
                  onChange={(_event, value) => field.onChange(value.map((suite) => suite.id))}
                  onBlur={field.onBlur}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Suites"
                      error={!!fieldState.error}
                      helperText={
                        fieldState.error?.message ??
                        'A suite’s whole membership — filed, added, and claimed by its tags.'
                      }
                      slotProps={{
                        // `...params.slotProps` FIRST — §96's defect, which cost a whole slice:
                        // replacing the object drops `input.startAdornment`, so every chip is
                        // held, sent and invisible, with both gates green.
                        ...params.slotProps,
                        htmlInput: {
                          ...params.slotProps?.htmlInput,
                          'data-testid': `${TestId}.Suites`,
                        },
                        formHelperText: { role: fieldState.error ? 'alert' : undefined },
                      }}
                    />
                  )}
                />
              )}
            />
            <Controller
              control={control}
              name="tags"
              render={({ field, fieldState }) => (
                <Autocomplete
                  multiple
                  freeSolo
                  /*
                    Suggested from the project's own vocabulary and still free text — the tag may
                    be one nobody has used yet, and `GET /tags` lists only tags with a live case.
                    The count beside each is what makes this picker answer the question the person
                    is actually asking, which is how long the run will be.
                  */
                  options={(tags.data ?? []).map((usage) => usage.tag)}
                  value={field.value}
                  onChange={(_event, value) =>
                    field.onChange([
                      ...new Set(value.map((tag) => tag.trim()).filter((tag) => tag !== '')),
                    ])
                  }
                  onBlur={field.onBlur}
                  renderOption={(props, option) => {
                    const { key, ...rest } = props;
                    const usage = (tags.data ?? []).find((row) => row.tag === option);
                    return (
                      <li key={key} {...rest}>
                        <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                          <Typography variant="body2">{option}</Typography>
                          {usage && (
                            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                              {formatCount(usage.caseCount)} cases
                            </Typography>
                          )}
                        </Stack>
                      </li>
                    );
                  }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Tags"
                      error={!!fieldState.error}
                      helperText={
                        fieldState.error?.message ??
                        // The one place the union/intersection split is visible to a user, and it
                        // points the opposite way from the case grid's filter on purpose.
                        'Any of these — a case carrying one of them is in the run.'
                      }
                      slotProps={{
                        ...params.slotProps,
                        htmlInput: {
                          ...params.slotProps?.htmlInput,
                          'data-testid': `${TestId}.Tags`,
                        },
                        formHelperText: { role: fieldState.error ? 'alert' : undefined },
                      }}
                    />
                  )}
                />
              )}
            />
            <Controller
              control={control}
              name="caseIds"
              render={({ field, fieldState }) => (
                <Autocomplete
                  multiple
                  options={catalogue.data?.items ?? []}
                  getOptionLabel={(row) => `${row.key} · ${row.title}`}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  value={(catalogue.data?.items ?? []).filter((row) =>
                    field.value.includes(row.id),
                  )}
                  onChange={(_event, value) => field.onChange(value.map((row) => row.id))}
                  onBlur={field.onBlur}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Cases"
                      error={!!fieldState.error}
                      helperText={fieldState.error?.message ?? 'Named individually. Optional.'}
                      slotProps={{
                        ...params.slotProps,
                        htmlInput: {
                          ...params.slotProps?.htmlInput,
                          'data-testid': `${TestId}.Cases`,
                        },
                        formHelperText: { role: fieldState.error ? 'alert' : undefined },
                      }}
                    />
                  )}
                />
              )}
            />
            {/*
              A4's two. Both are contract fields this form sent a hard-coded null for, so what is
              new is not the payload but the ability to mean something else by it. Neither is
              required and neither has a client-side rule — see the schema.
            */}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Configuration"
                select
                fullWidth
                defaultValue=""
                error={!!errors.configurationId}
                helperText={
                  errors.configurationId?.message ?? 'The environment this pass runs in.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Configuration` } }}
                {...register('configurationId')}
              >
                {/*
                  Named rather than blank, for the plan select's reason: "the project's default" is
                  a decision the server makes, and a blank first row reads as a field somebody
                  forgot to fill in. Every project has exactly one Default seeded, so this is the
                  honest label for what an untouched form sends.
                */}
                <MenuItem value="" data-testid={`${TestId}.Configuration.Default`}>
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    The project’s default
                  </Box>
                </MenuItem>
                {(configurations.data ?? []).map((configuration) => (
                  <MenuItem
                    key={configuration.id}
                    value={configuration.id}
                    data-testid={`${TestId}.Configuration.${configuration.id}`}
                  >
                    {configuration.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Assign to"
                select
                fullWidth
                defaultValue=""
                error={!!errors.assigneeId}
                helperText={
                  errors.assigneeId?.message ??
                  // States the blast radius, because it is the one thing about this control that
                  // surprises: it is not "who runs this pass", it is every row of the checklist.
                  'Optional — gives every case in the run to one person.'
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestId}.Assignee` } }}
                {...register('assigneeId')}
              >
                <MenuItem value="" data-testid={`${TestId}.Assignee.None`}>
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    Nobody — the team picks it up
                  </Box>
                </MenuItem>
                {(members.data ?? []).map((member) => (
                  <MenuItem
                    key={member.id}
                    value={member.id}
                    data-testid={`${TestId}.Assignee.${member.id}`}
                  >
                    {member.displayName}
                  </MenuItem>
                ))}
              </TextField>
            </Stack>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Release"
                autoComplete="off"
                fullWidth
                error={!!errors.releaseVersion}
                helperText={errors.releaseVersion?.message ?? 'Optional — 4.3.0.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Release`, maxLength: MAX_RELEASE },
                }}
                {...register('releaseVersion')}
              />
              <TextField
                label="Build"
                autoComplete="off"
                fullWidth
                error={!!errors.buildNumber}
                // Distinct from the release on purpose: conflating them costs the ability to ask
                // whether 4.3.0 improved between build 4470 and 4471 (V1's own column comment).
                helperText={errors.buildNumber?.message ?? 'Optional — 4471.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Build`, maxLength: MAX_RELEASE },
                }}
                {...register('buildNumber')}
              />
            </Stack>
            {/*
              §177's Git traceability. Optional on a manual run and usually empty — a person running
              a regression pass by hand often knows the branch and rarely the sha, which is why both
              are accepted independently rather than as a pair. The row is below the release rather
              than beside it because it answers the narrower question: release, then build, then the
              exact code.
            */}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Branch"
                autoComplete="off"
                fullWidth
                error={!!errors.branch}
                helperText={errors.branch?.message ?? 'Optional — release/4.3.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Branch`, maxLength: MAX_BRANCH },
                }}
                {...register('branch')}
              />
              <TextField
                label="Commit"
                autoComplete="off"
                fullWidth
                error={!!errors.commitSha}
                // The server's rule, stated rather than enforced here — see the schema. Naming the
                // bound is what makes its refusal predictable instead of a surprise on submit.
                helperText={errors.commitSha?.message ?? 'Optional — 7 to 40 hex characters.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Commit`, maxLength: 40 },
                }}
                {...register('commitSha')}
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            /*
              §99 dropped `|| available.length === 0`. It was right while a plan was the only
              scope: a submit that can only ever be refused should not be offered. It is now the
              opposite — a project with no plans is exactly the one whose first run is scoped by a
              suite or a tag, and disabling the button there would hide the feature from the
              project that needs it most. The empty-scope rule moved into the schema, where it
              answers on a control instead of on the button.
            */
            disabled={create.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {create.isPending ? 'Starting…' : 'Start run'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
