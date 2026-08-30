import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input, Textarea } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { Panel } from '../../components/PageHeader';
import {
  criterionMetricEvaluated,
  criterionMetricLabels,
  criterionOperators,
} from '../../lib/labels';
import { criterionRowId } from './planDraft';
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
    <div className="flex flex-col gap-6">
      <Panel title="Plan" testId="PlanStats.PlanFields">
        <div className="flex flex-col gap-4">
          {/*
            The empty-name hint is said here as well as on the disabled Save, because the reason
            belongs beside the box that is empty and the button is on a banner that may be
            scrolled away.
          */}
          <Field
            label="Name"
            htmlFor="name"
            error={errorOf('name')}
            help={nameEmpty ? 'Enter a plan name.' : 'The release or milestone this plan covers.'}
          >
          <Input
            id="name"
            value={draft.name}
            onChange={(event) => onChange({ name: event.target.value })}
            autoComplete="off"
            required
            aria-invalid={nameEmpty || !!errorOf('name')}
            maxLength={MAX_NAME}
            data-testid={`${TestId}.Name`}
          />
          </Field>
          <div className="flex flex-col gap-4">
            <Field
              label="Milestone"
              htmlFor="milestone"
              error={errorOf('milestoneName')}
              help="Optional — GA, Sprint 42."
            >
            <Input
              id="milestone"
              value={draft.milestoneName}
              onChange={(event) => onChange({ milestoneName: event.target.value })}
              autoComplete="off"
              aria-invalid={!!errorOf('milestoneName')}
              maxLength={MAX_MILESTONE_NAME}
              data-testid={`${TestId}.Milestone`}
            />
            </Field>
            <Field
              label="Milestone date"
              htmlFor="milestone-date"
              error={errorOf('milestoneDate')}
              help="Optional."
            >
            <Input
              id="milestone-date"
              value={draft.milestoneDate}
              onChange={(event) => onChange({ milestoneDate: event.target.value })}
              type="date"
              aria-invalid={!!errorOf('milestoneDate')}
              data-testid={`${TestId}.MilestoneDate`}
            />
            </Field>
          </div>
          <Field
            label="Description"
            htmlFor="description"
            error={errorOf('description')}
            help="Optional."
          >
          <Textarea
            id="description"
            value={draft.description}
            onChange={(event) => onChange({ description: event.target.value })}
            rows={2}
            aria-invalid={!!errorOf('description')}
            maxLength={MAX_DESCRIPTION}
            data-testid={`${TestId}.Description`}
          />
          </Field>
        </div>
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
        hint="What must be true before the release is signed off. The Progress tab evaluates pass rate and coverage against the run results; the other metrics are recorded and shown as unevaluated."
        rows={draft.exitCriteria}
        error={errorOf('exitCriteria')}
        onChange={(exitCriteria) => onChange({ exitCriteria })}
      />
    </div>
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
      <div className="flex flex-col gap-2">
        <span className="m-0 text-[12px] text-ink-2">
          {hint}
        </span>
        {error && (
          <div data-testid={`${testId}.Error`}>
<Banner tone="bad">
            {error}
          </Banner>
</div>
        )}
        {rows.map((row, index) => (
          <div className="flex flex-col" key={row.rowId}>
            <NativeSelect
              value={row.metric}
              onChange={(event) => update(index, { metric: event.target.value })}
              data-testid={`${testId}.Metric.${index}`}
              aria-label={`Metric for criterion ${index + 1}`}
              className="min-w-[170px]"
            >
              {Object.entries(criterionMetricLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {/*
                    B11 — the three metrics this product cannot decide yet say so HERE, where the
                    choice is made. All five stay on offer: a gate somebody has agreed to is worth
                    recording before it can be checked automatically (`criterionMetricEvaluated`
                    carries that argument), and the plan page shows such a criterion as unevaluated
                    rather than as passing. What was missing was any way to know that in advance.
                  */}
                  {label}
                  {!criterionMetricEvaluated[value as keyof typeof criterionMetricEvaluated] &&
                    ' — not evaluated yet'}
                </option>
              ))}
            </NativeSelect>
            <NativeSelect
              value={row.op}
              onChange={(event) => update(index, { op: event.target.value })}
              data-testid={`${testId}.Op.${index}`}
              aria-label={`Comparison for criterion ${index + 1}`}
              className="min-w-[80px]"
            >
              {criterionOperators.map((op) => (
                <option key={op} value={op}>
                  {op}
                </option>
              ))}
            </NativeSelect>
            <Input
              type="number"
              value={row.value}
              onChange={(event) => update(index, { value: event.target.value })}
              data-testid={`${testId}.Value.${index}`}
              aria-label={`Threshold for criterion ${index + 1}`}
              className="w-[110px]"
            />
            <Input
              placeholder="Optional label"
              value={row.label}
              onChange={(event) => update(index, { label: event.target.value })}
              data-testid={`${testId}.Label.${index}`}
              aria-label={`Label for criterion ${index + 1}`}
            />
            <Button variant="quiet" size="icon"
              aria-label={`Remove criterion ${index + 1}`}
              data-testid={`${testId}.Remove.${index}`}
              onClick={() => onChange(rows.filter((_, i) => i !== index))}
            >
              ✕
            </Button>
          </div>
        ))}
        {rows.length === 0 && (
          <p className="m-0 text-[13px] text-ink-2">
            None set.
          </p>
        )}
        {/* Wrapped so the button sizes to its label rather than stretching to the row width. */}
        <div>
          <Button
            size="sm"
            disabled={rows.length >= MAX_CRITERIA}
            data-testid={`${testId}.Add`}
            onClick={() =>
              onChange([...rows, { rowId: criterionRowId(), metric: 'pass_rate', op: '>=', value: '', label: '' }])
            }
          >
            Add criterion
          </Button>
        </div>
      </div>
    </Panel>
  );
}
