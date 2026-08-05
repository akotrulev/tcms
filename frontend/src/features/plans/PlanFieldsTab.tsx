import { Alert, Box, Button, IconButton, MenuItem, Select, Stack, TextField, Typography } from '@mui/material';
import { Panel } from '../../components/PageHeader';
import { criterionMetricLabels, criterionOperators } from '../../lib/labels';
import type { CriterionRow, PlanDraft } from './planDraft';

/**
 * The **Plan** tab — name, description, milestone and the two criteria lists.
 *
 * ## This replaces `PlanEditDialog`, which is deleted rather than left compiling
 *
 * Every control here was in that dialog and is now on the page, bound to the draft above the tabs
 * (§97 D3: one form, one Save). A dialog nobody opens that still compiles is the second
 * implementation `ui-rules` §8 is about — and this one would have been the *divergent* second, since
 * the dialog wrote its own `PATCH` on its own lock version while the form writes an ordered pair.
 *
 * **What did NOT come across is the suite picker.** The dialog carried a checkbox per suite, which
 * was the plan's scope: under §97 that is the Test cases tab's subject — sections, suites, cases and
 * tags together — and putting a folder list here would be the second place to write one column.
 * The tab that owns it lands in phase 6, so between this phase and that one a plan's suite scope
 * has no on-screen editor. Deliberate, recorded in the slice plan's traps and in a browser row,
 * rather than shipped as a control that half-writes the scope and then has to be removed again.
 *
 * ## Nothing here validates on blur
 *
 * `PlanEditDialog` was React Hook Form + Zod because it owned a submit. This tab owns none: the
 * draft is the state, Save is the banner's, and the one locally-knowable refusal — an empty name —
 * is {@link planProblems}, which disables Save *with its reason* instead of failing at the server.
 * Everything else is the server's answer, landed on the control it names through `fieldErrors`.
 */

/** Mirrors `PlanEdit.MAX_NAME`. */
const MAX_NAME = 200;
/** Mirrors `PlanEdit.MAX_DESCRIPTION`. */
const MAX_DESCRIPTION = 2000;
/** Mirrors `PlanEdit.MAX_MILESTONE_NAME`. */
const MAX_MILESTONE_NAME = 200;
/** Mirrors `PlanEdit.MAX_CRITERIA`. */
const MAX_CRITERIA = 50;

const TestId = 'PlanStats.Plan';

export function PlanFieldsTab({
  draft,
  fieldErrors,
  onChange,
}: {
  draft: PlanDraft;
  /** The last save's refusals, keyed as the server keys them. */
  fieldErrors: Record<string, string[]>;
  onChange: (patch: Partial<PlanDraft>) => void;
}) {
  const errorOf = (field: string) => fieldErrors[field]?.join(' ');
  const nameEmpty = draft.name.trim() === '';

  return (
    <Stack sx={{ gap: 3 }}>
      <Panel title="Plan" testId="PlanStats.PlanFields">
        <Stack sx={{ gap: 2 }}>
          <TextField
            label="Name"
            required
            autoComplete="off"
            value={draft.name}
            onChange={(event) => onChange({ name: event.target.value })}
            error={nameEmpty || !!errorOf('name')}
            helperText={
              errorOf('name')
              // Said here as well as on the disabled Save, because the reason belongs beside the
              // box that is empty and the button is on a banner that may be scrolled away.
              ?? (nameEmpty ? 'Enter a plan name.' : 'The release or milestone this plan covers.')
            }
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 2 }}>
            <TextField
              label="Milestone"
              autoComplete="off"
              fullWidth
              value={draft.milestoneName}
              onChange={(event) => onChange({ milestoneName: event.target.value })}
              error={!!errorOf('milestoneName')}
              helperText={errorOf('milestoneName') ?? 'Optional — GA, Sprint 42.'}
              slotProps={{
                htmlInput: { 'data-testid': `${TestId}.Milestone`, maxLength: MAX_MILESTONE_NAME },
              }}
            />
            <TextField
              label="Milestone date"
              type="date"
              fullWidth
              value={draft.milestoneDate}
              onChange={(event) => onChange({ milestoneDate: event.target.value })}
              error={!!errorOf('milestoneDate')}
              helperText={errorOf('milestoneDate') ?? 'Optional.'}
              slotProps={{
                inputLabel: { shrink: true },
                htmlInput: { 'data-testid': `${TestId}.MilestoneDate` },
              }}
            />
          </Stack>
          <TextField
            label="Description"
            multiline
            minRows={2}
            value={draft.description}
            onChange={(event) => onChange({ description: event.target.value })}
            error={!!errorOf('description')}
            helperText={errorOf('description') ?? 'Optional.'}
            slotProps={{
              htmlInput: { 'data-testid': `${TestId}.Description`, maxLength: MAX_DESCRIPTION },
            }}
          />
        </Stack>
      </Panel>

      <CriteriaEditor
        testId={`${TestId}.Entry`}
        title="Entry criteria"
        hint="What must be true before execution starts."
        rows={draft.entryCriteria}
        error={errorOf('entryCriteria')}
        onChange={(entryCriteria) => onChange({ entryCriteria })}
      />
      <CriteriaEditor
        testId={`${TestId}.Exit`}
        title="Exit criteria"
        hint="What must be true before the release is signed off. The Progress tab evaluates these against the run results."
        rows={draft.exitCriteria}
        error={errorOf('exitCriteria')}
        onChange={(exitCriteria) => onChange({ exitCriteria })}
      />
    </Stack>
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
 *
 * **The verdicts are not here and that is deliberate.** The Progress tab evaluates these against
 * real results; a met/unmet mark beside the editor would be a second answer to one question,
 * computed from a different response, changing under a threshold being typed.
 */
function CriteriaEditor({
  testId,
  title,
  hint,
  rows,
  error,
  onChange,
}: {
  testId: string;
  title: string;
  hint: string;
  rows: CriterionRow[];
  /** A refusal about the list as a whole — an unknown metric has no control to land on. */
  error?: string;
  onChange: (rows: CriterionRow[]) => void;
}) {
  const update = (index: number, patch: Partial<CriterionRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  return (
    <Panel title={title} testId={testId}>
      <Stack sx={{ gap: 1 }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {hint}
        </Typography>
        {error && (
          <Alert severity="error" data-testid={`${testId}.Error`}>
            {error}
          </Alert>
        )}
        {rows.map((row, index) => (
          <Stack
            key={index}
            direction={{ xs: 'column', md: 'row' }}
            sx={{ gap: 1, alignItems: { md: 'center' } }}
          >
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
              slotProps={{
                htmlInput: {
                  'data-testid': `${testId}.Value.${index}`,
                  'aria-label': `Threshold for criterion ${index + 1}`,
                },
              }}
              sx={{ width: 110 }}
            />
            <TextField
              size="small"
              fullWidth
              placeholder="Optional label"
              value={row.label}
              onChange={(event) => update(index, { label: event.target.value })}
              slotProps={{
                htmlInput: {
                  'data-testid': `${testId}.Label.${index}`,
                  'aria-label': `Label for criterion ${index + 1}`,
                },
              }}
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
        {rows.length === 0 && (
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            None set.
          </Typography>
        )}
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
    </Panel>
  );
}
