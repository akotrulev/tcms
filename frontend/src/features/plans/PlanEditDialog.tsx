import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  IconButton,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useSuites, useUpdatePlan } from '../../api/queries';
import type { CriterionInput } from '../../api/queries';
import type { TestPlan } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';
import { inputTestId } from '../../lib/muiTestIds';
import { criterionMetricLabels, criterionOperators } from '../../lib/labels';

/**
 * Editing a test plan — the screen behind `plan:edit`, and the only way the two criteria columns
 * are ever written.
 *
 * ## The dialog opens from the plan it was served, which is why the scope is on the response
 *
 * Every control here is seeded from the `TestPlan` the caller already loaded, including
 * `suiteIds` — a field the detail endpoint did not serve until the backend half of this slice.
 * `caseCount` is an expansion and could never have driven a picker: it says how many cases the
 * plan covers, not which folders were chosen. A dialog built against it would have had to send a
 * scope it invented.
 *
 * ## What is deliberately NOT here
 *
 * **The status.** It has its own controls on the page behind this dialog and its own two
 * permissions, and a status an ordinary save could set is an approval anybody grants themselves.
 *
 * **`caseIds`.** The API accepts individually-named cases and the create dialog already explains
 * why it offers folders only — a folder covers what is beneath it *when the question is asked*, so
 * a plan scoped to folders stays correct as cases are authored. This dialog therefore leaves
 * `caseIds` **out of the payload entirely** rather than sending `[]`, which is the difference
 * between "leave the individually-named cases alone" and "delete them": a plan whose scope was
 * built through the API would otherwise lose half of it the first time somebody renamed it here.
 */

/** Mirrors `PlanEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `PlanEdit.MAX_DESCRIPTION`. */
const MAX_DESCRIPTION = 2000;
/** Mirrors `PlanEdit.MAX_MILESTONE_NAME`. */
const MAX_MILESTONE_NAME = 200;
/** Mirrors `PlanEdit.MAX_CRITERIA`. */
const MAX_CRITERIA = 50;

const PlanSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter a plan name.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  description: z.string().max(MAX_DESCRIPTION).optional(),
  milestoneName: z.string().max(MAX_MILESTONE_NAME).optional(),
  milestoneDate: z.string().optional(),
});

type PlanFormInput = z.infer<typeof PlanSchema>;

const TestId = 'Plans.Edit';

type FormField = 'name' | 'description' | 'milestoneName' | 'milestoneDate';

const FIELDS: readonly FormField[] = ['name', 'description', 'milestoneName', 'milestoneDate'];

/**
 * A criterion as the form holds it — the threshold is a **string** while it is being typed.
 *
 * A `number` state bound to a text input cannot represent "the user has cleared the box", and
 * coercing an empty box to 0 is exactly the gate the server refuses: "pass_rate >= 0" is met by
 * every release that ever runs and reads on screen like something somebody meant.
 */
interface CriterionRow {
  metric: string;
  op: string;
  value: string;
  label: string;
}

function toRows(criteria: TestPlan['exitCriteria']): CriterionRow[] {
  return (criteria ?? []).map((criterion) => ({
    metric: criterion.metric,
    op: criterion.op,
    value: String(criterion.value),
    // The server DERIVES a label when none was authored ("pass_rate >= 95"), so a round trip would
    // otherwise turn every generated label into a stored one — and then editing the threshold
    // would leave the old number frozen in the text beside it.
    label: criterion.label === `${criterion.metric} ${criterion.op} ${criterion.value}` ? '' : (criterion.label ?? ''),
  }));
}

/** Drops rows the user started and left blank; `met`/`actual` are never sent — the server owns them. */
function toPayload(rows: CriterionRow[]): CriterionInput[] {
  return rows
    .filter((row) => row.metric !== '' && row.value.trim() !== '')
    .map((row) => ({
      metric: row.metric,
      op: row.op,
      value: Number(row.value),
      label: row.label.trim() || null,
    }));
}

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
        // `suiteIds`, `entryCriteria`, `exitCriteria` and `If-Match` have no TextField to land on,
        // so they go to the form-level alert with the server's own sentence — a refusal the user
        // cannot see is the failure this whole path exists to prevent.
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

export function PlanEditDialog({
  projectId,
  plan,
  open,
  onClose,
}: {
  projectId: string;
  plan: TestPlan;
  open: boolean;
  onClose: () => void;
}) {
  const update = useUpdatePlan(projectId, plan.id);
  const suites = useSuites(projectId);
  const [scope, setScope] = useState<string[]>(plan.suiteIds ?? []);
  const [entry, setEntry] = useState<CriterionRow[]>(() => toRows(plan.entryCriteria));
  const [exit, setExit] = useState<CriterionRow[]>(() => toRows(plan.exitCriteria));
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<PlanFormInput>({
    resolver: zodResolver(PlanSchema),
    defaultValues: {
      name: plan.name,
      description: plan.description ?? '',
      milestoneName: plan.milestoneName ?? '',
      milestoneDate: plan.milestoneDate ?? '',
    },
    mode: 'onBlur',
  });

  // Focus only, no `reset` — the dialog is conditionally rendered and mounts fresh each time it
  // opens, and resetting here is what wiped the suite dialog's server errors in §30.
  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      await update.mutateAsync({
        lockVersion: plan.lockVersion,
        name: values.name.trim(),
        description: values.description?.trim() || null,
        milestoneName: values.milestoneName?.trim() || null,
        // An empty date input submits '', which is not a date. Null is what "no milestone" means
        // in the column and in the contract.
        milestoneDate: values.milestoneDate?.trim() || null,
        suiteIds: scope,
        // No `caseIds` — see the header. Omitting it means "leave them alone"; sending [] would
        // delete a half of the scope this dialog cannot show.
        entryCriteria: toPayload(entry),
        exitCriteria: toPayload(exit),
      });
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={update.isPending}>
        <DialogTitle>Edit {plan.key}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            <TextField
              label="Name"
              required
              autoComplete="off"
              error={!!errors.name}
              helperText={errors.name?.message ?? 'The release or milestone this plan covers.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
              {...register('name')}
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="Milestone"
                autoComplete="off"
                fullWidth
                error={!!errors.milestoneName}
                helperText={errors.milestoneName?.message ?? 'Optional — GA, Sprint 42.'}
                slotProps={{
                  htmlInput: { 'data-testid': `${TestId}.Milestone`, maxLength: MAX_MILESTONE_NAME },
                }}
                {...register('milestoneName')}
              />
              <TextField
                label="Milestone date"
                type="date"
                fullWidth
                error={!!errors.milestoneDate}
                helperText={errors.milestoneDate?.message ?? 'Optional.'}
                slotProps={{
                  inputLabel: { shrink: true },
                  htmlInput: { 'data-testid': `${TestId}.MilestoneDate` },
                }}
                {...register('milestoneDate')}
              />
            </Stack>
            <TextField
              label="Description"
              multiline
              minRows={2}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Description` } }}
              {...register('description')}
            />

            <Stack spacing={0.5}>
              <Typography variant="subtitle2">Scope</Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                A folder covers everything beneath it, now and as cases are added.
              </Typography>
              <FormGroup data-testid={`${TestId}.Scope`} sx={{ maxHeight: 180, overflowY: 'auto' }}>
                {(suites.data ?? []).map((suite) => (
                  <FormControlLabel
                    key={suite.id}
                    control={
                      <Checkbox
                        size="small"
                        checked={scope.includes(suite.id)}
                        onChange={(event) =>
                          setScope((current) =>
                            event.target.checked
                              ? [...current, suite.id]
                              : current.filter((id) => id !== suite.id),
                          )
                        }
                        /*
                          On the INPUT slot, not the root. A bare `data-testid` on a MUI `Checkbox`
                          lands on the wrapper span, where the checked state is unreadable — so a
                          test finds the element, clicks it successfully, and asserts nothing about
                          selection. §62's option test passed that way and §68 hit it again; this
                          test file hit it a third time, on the first run.
                        */
                        slotProps={inputTestId(`${TestId}.Scope.${suite.key}`, suite.name)}
                      />
                    }
                    label={
                      <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                        <Identifier variant="muted">{suite.key}</Identifier>
                        <Typography variant="body2">{suite.name}</Typography>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          {formatCount(suite.caseCount)} cases
                        </Typography>
                      </Stack>
                    }
                  />
                ))}
                {suites.data?.length === 0 && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    This project has no suites yet.
                  </Typography>
                )}
              </FormGroup>
            </Stack>

            <CriteriaEditor
              testId={`${TestId}.Entry`}
              title="Entry criteria"
              hint="What must be true before execution starts."
              rows={entry}
              onChange={setEntry}
            />
            <CriteriaEditor
              testId={`${TestId}.Exit`}
              title="Exit criteria"
              hint="What must be true before the release is signed off. The plan page evaluates these against the run results."
              rows={exit}
              onChange={setExit}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={update.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {update.isPending ? 'Saving…' : 'Save plan'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/**
 * One criteria list — the metric is a **select**, not a text field.
 *
 * The server validates against a closed vocabulary and it has to, because a criterion whose metric
 * it does not recognise renders exactly like one the product cannot evaluate yet: both show a gate
 * with no verdict. Offering the list here means the difference never has to be explained in an
 * error message, and the two agree because `criterionMetricLabels` is `satisfies`-checked against
 * the same set.
 */
function CriteriaEditor({
  testId,
  title,
  hint,
  rows,
  onChange,
}: {
  testId: string;
  title: string;
  hint: string;
  rows: CriterionRow[];
  onChange: (rows: CriterionRow[]) => void;
}) {
  const update = (index: number, patch: Partial<CriterionRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  return (
    <Stack spacing={0.5} data-testid={testId}>
      <Typography variant="subtitle2">{title}</Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {hint}
      </Typography>
      <Stack spacing={1} sx={{ mt: 0.5 }}>
        {rows.map((row, index) => (
          <Stack key={index} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Select
              size="small"
              value={row.metric}
              onChange={(event) => update(index, { metric: event.target.value })}
              data-testid={`${testId}.Metric.${index}`}
              inputProps={{ 'aria-label': `Metric for criterion ${index + 1}` }}
              sx={{ minWidth: 170 }}
            >
              {Object.entries(criterionMetricLabels).map(([value, label]) => (
                <MenuItem key={value} value={value}>
                  {label}
                </MenuItem>
              ))}
            </Select>
            <Select
              size="small"
              value={row.op}
              onChange={(event) => update(index, { op: event.target.value })}
              data-testid={`${testId}.Op.${index}`}
              inputProps={{ 'aria-label': `Comparison for criterion ${index + 1}` }}
              sx={{ minWidth: 80 }}
            >
              {criterionOperators.map((op) => (
                <MenuItem key={op} value={op}>
                  {op}
                </MenuItem>
              ))}
            </Select>
            <TextField
              size="small"
              type="number"
              value={row.value}
              onChange={(event) => update(index, { value: event.target.value })}
              slotProps={{ htmlInput: { 'data-testid': `${testId}.Value.${index}`, 'aria-label': `Threshold for criterion ${index + 1}` } }}
              sx={{ width: 110 }}
            />
            <TextField
              size="small"
              fullWidth
              placeholder="Optional label"
              value={row.label}
              onChange={(event) => update(index, { label: event.target.value })}
              slotProps={{ htmlInput: { 'data-testid': `${testId}.Label.${index}`, 'aria-label': `Label for criterion ${index + 1}` } }}
            />
            <IconButton
              size="small"
              aria-label={`Remove criterion ${index + 1}`}
              data-testid={`${testId}.Remove.${index}`}
              onClick={() => onChange(rows.filter((_, i) => i !== index))}
            >
              ✕
            </IconButton>
          </Stack>
        ))}
        {/* Wrapped so the button sizes to its label rather than stretching to the row width. */}
        <Box>
          <Button
            size="small"
            disabled={rows.length >= MAX_CRITERIA}
            data-testid={`${testId}.Add`}
            onClick={() =>
              onChange([...rows, { metric: 'pass_rate', op: '>=', value: '', label: '' }])
            }
          >
            Add criterion
          </Button>
        </Box>
      </Stack>
    </Stack>
  );
}
