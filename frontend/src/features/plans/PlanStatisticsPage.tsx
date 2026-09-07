import { CircleAlert, CircleCheck, CircleHelp, ExternalLink } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import type { AggregationPolicy, Criterion, PlanStatistics, PlanStatus, TestPlan } from '../../api/types';
import {
  useArtifacts,
  useDocPages,
  usePlan,
  usePlans,
  usePlanStatistics,
  useSetPlanStatus,
} from '../../api/queries';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { ArtifactTable } from '../artifacts/ArtifactTable';
import { RegisterArtifactDialog } from '../artifacts/ArtifactDialogs';
import {
  PLAN_TABS,
  draftFromPlan,
  dirtyTabs,
  ensureTrailingBlank,
  planProblems,
  planTabLabels,
  type PlanDraft,
  type PlanTabId,
} from './planDraft';
import { PlanCasesTab } from './PlanCasesTab';
import { PlanFieldsTab } from './PlanFieldsTab';
import { usePlanSave, type SaveOutcome } from './usePlanSave';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PlanDocumentPanel } from './PlanDocumentPanel';
import { ReviewPanel } from '../review/ReviewPanel';
import { PermissionGate } from '../../components/PermissionGate';
import { DenominatorToggle, PassRateSummary, ResultCountsCell } from '../../components/ResultBar';
import {
  Badge,
  Banner,
  type BadgeTone,
  ResultBar,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { FigurePanel } from '../../components/kit/FigurePanel';
import { Button, Textarea } from '../../components/ui';
import { criterionColor, criterionState, type Denominator } from '../../lib/results';
import { StatusChip } from '../../components/StatusChip';
import { describeError } from '../../lib/describeError';
import { EM_DASH, formatComputedAt, formatCount, formatDateTime, formatPercent, plural } from '../../lib/format';
import { aggregationHelp, aggregationLabels, planStatusLabels } from '../../lib/labels';
import { PlanCloneDialog } from './PlanCloneDialog';
import { PlanDeleteDialog } from './PlanDeleteDialog';

/**
 * Plan statistics (plan §6p).
 *
 * Three decisions drive this screen:
 *
 *   1. **Pass rate and coverage sit side by side.** 89% pass over 97% coverage is a very
 *      different release position from 89% over 40%, and a screen that shows only the first
 *      number invites the wrong call.
 *   2. **The aggregation policy is displayed, always.** A plan whose cases ran across three
 *      configurations has several defensible pass rates; without naming the arithmetic, two
 *      teams reading "89%" mean different things and neither knows it.
 *   3. **Blockers and unexecuted cases are listed, not counted.** "21 failed" is a number;
 *      the 21 named cases with their defect links are a work list.
 *
 * The second of the two analytics screens (007 T15), so design language §6 binds the drawings:
 * the trend is **the one line chart in the product** and carries a crosshair by default, its y
 * axis starts at zero and says so, and every stacked bar here is the kit's — where *untested is
 * the empty track rather than a fill*, because as a fill it sat 10.7 ΔE from `skipped` and the two
 * were adjacent in every bar on this page.
 */
export function PlanStatisticsPage() {
  const { projectId = '', planId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  // The policy lives in the URL: a plan report sent to a release meeting must arrive with
  // the arithmetic it was read under, not reset to the default for the next reader.
  const aggregation = (params.get('aggregation') ?? 'LATEST') as AggregationPolicy;

  /*
    Which half of the plan is on screen, in the URL for the reason every other filter on this
    product is: a plan's document is what gets pasted into a release thread, and a link that opens
    on the progress numbers instead is a link that failed to say what it was about.

    `progress` is the default because this route was the statistics page before it had a document,
    and every link to it that already exists means the numbers.
  */
  const requested = params.get('tab') ?? '';
  const tab: PlanTabId = (PLAN_TABS as string[]).includes(requested)
    ? (requested as PlanTabId)
    : 'progress';
  const selectTab = (next: string) => {
    const updated = new URLSearchParams(params);
    if (next === 'progress') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };

  /*
    ~~`useProject`, for the project's name in this screen's own breadcrumb.~~ →
    **superseded (021 R2): the shell names the project**, from the read it already makes
    for the rail's switcher — so this page's copy of that read went with its trail.
  */
  const plan = usePlan(projectId, planId);
  const statistics = usePlanStatistics(projectId, planId, aggregation);

  return (
    <div>
      <PageHeader
        testId="PlanStats.Header"
        title={plan.data?.name ?? 'Test plan'}
        documentTitle={plan.data ? `${plan.data.key} ${plan.data.name}` : 'Test plan'}
        meta={
          plan.data && (
            <>
              <Identifier variant="strong">{plan.data.key}</Identifier>
              {/* A badge, not a chip: the plan's status is state the system owns and moving it is
                  the ladder of buttons below, never a click on the label (design language §5). */}
              <Badge tone={PLAN_TONE[plan.data.status]} data-testid="PlanStats.Status">
                {planStatusLabels[plan.data.status]}
              </Badge>
              {plan.data.milestoneName && (
                <span className="text-[13px] text-ink-3">
                  {plan.data.milestoneName}
                  {plan.data.milestoneDate && ` · due ${plan.data.milestoneDate}`}
                </span>
              )}
            </>
          )
        }
        subtitle={plan.data?.description}
        actions={
          <div className="flex flex-col items-end gap-2">
            {plan.data && (
              <PlanLifecycleActions projectId={projectId} plan={plan.data} />
            )}
            {/*
              A native `<select>`, not a listbox widget. It picks one value from four fixed
              arithmetic policies, it is on the report a reader is about to screenshot, and the
              platform control is the one that is already keyboard-operable, screen-reader-labelled
              and usable on a phone. Styled as the render's `.fld` so it reads as a field rather
              than as a button.
            */}
            <label
              className={tab === 'progress' ? 'flex min-w-[260px] flex-col gap-1' : 'hidden'}
            >
              <span className="sr-only">How results are aggregated</span>
              <select
                value={aggregation}
                onChange={(event) => {
                  const next = new URLSearchParams(params);
                  next.set('aggregation', event.target.value);
                  setParams(next, { replace: true });
                }}
                data-testid="PlanStats.AggregationSelect"
                className="w-full rounded-[8px] border border-line bg-field px-2.5 py-1.5 text-[12.5px] text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
              >
                {Object.entries(aggregationLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
        }
      />

      {/*
        A tablist of real buttons rather than a tab widget. The panels these tabs select are not
        siblings of the list — the form spine below renders one, the statistics region renders
        another — so a component that owns both halves would have to be given a hole to render
        through. `role="tab"` with `aria-selected` is what a screen reader needs, and it is the
        whole of what the widget was adding.
      */}
      <div
        role="tablist"
        aria-label="Plan"
        // `.lens` — the render kit's only tab idiom. This screen drew underline tabs, which the
        // kit does not have, so the product had two ways of saying "pick a view".
        className="mb-4 inline-flex max-w-full gap-0.5 overflow-x-auto rounded-[8px] bg-wash-1 p-0.5"
        data-testid="PlanStats.Tabs"
      >
        {PLAN_TABS.map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            onClick={() => selectTab(id)}
            data-testid={`PlanStats.Tab.${id}`}
            className={
              tab === id
                ? 'cursor-pointer whitespace-nowrap rounded-md bg-panel px-[11px] py-1 text-[12px] font-medium text-ink shadow-e1 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]'
                : 'cursor-pointer whitespace-nowrap rounded-md px-[11px] py-1 text-[12px] font-medium text-ink-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]'
            }
          >
            {planTabLabels[id]}
          </button>
        ))}
      </div>

      {/*
        Progress keeps its own lifecycle and is the one tab outside the form: it is a report, it
        holds no draft, and it must keep working for a reader with no write permission at all —
        this page is what gets pasted into a release thread, and turning it into a form must not put
        a disabled editor in front of somebody who came to read numbers.
      */}
      {/*
        Mounted for every tab, INCLUDING Progress, and that is the whole of §97's reversal of §93's
        unmounting: the draft lives here, so a form that unmounted while the reader glanced at the
        numbers would discard what they had typed. On Progress it renders the banner alone — an
        author who edited Scope and switched away still sees that Scope is unsaved, which is what
        makes an off-screen edit visible.

        Rendered on `plan.data` rather than through a `DataRegion`: the region below reports a
        failed load, the header already falls back to "Test plan", and a skeleton for the form above
        the numbers would be a second loading state for one page.
      */}
      {plan.data && (
        <PlanForm key={plan.data.id} projectId={projectId} planId={planId} plan={plan.data} tab={tab} />
      )}

      {tab === 'progress' && (
        <DataRegion testId="PlanStats.Content" state={{ ...statistics, refetch: () => void statistics.refetch() }}>
          {(data) => <StatisticsBody data={data} projectId={projectId} aggregation={aggregation} />}
        </DataRegion>
      )}

      {/*
        Below the report rather than above it. A plan's sign-off is the release-readiness signature
        (§6g) and therefore tempting to put first — but this page is the report, most plans are
        never submitted at all (review is optional and off by default), and an empty "Not in review"
        panel above the numbers would be a permanent header for a feature most teams do not use.
        `currentVersionNo` is passed so the panel can say the SCOPE has changed since the sign-off
        was asked for, which is exactly what a plan version is (§74).
      */}
      <div className="mt-6">
        <ReviewPanel
          projectId={projectId}
          subjectType="PLAN"
          subjectId={planId}
          currentVersionNo={plan.data?.currentVersionNo}
        />
      </div>
    </div>
  );
}

/**
 * The ladder, in one palette with the results — design language rule 5.
 *
 * **This is a second copy of `PlanListPage`'s `PLAN_TONE` and it is one on purpose, for now.** The
 * two screens were ported in different tasks of 007 and the list's map is private to its file, so
 * there was nothing to import; the pair belongs in `lib/labels.ts` beside `planStatusLabels`, where
 * `planStatusColors` already sits under the comment *"so a status cannot be worded in one place and
 * coloured in another"*. Collapsing them is a one-file change nobody should have to rediscover.
 */
const PLAN_TONE = {
  DRAFT: 'plain',
  IN_REVIEW: 'retest',
  APPROVED: 'passed',
} as const satisfies Record<PlanStatus, BadgeTone>;

/**
 * A region that is not a figure — a heading in the render's uppercase mono, and its rows directly
 * under it.
 *
 * Rule 1 of the design language: *no card wrapping a table, no panel around the only thing on
 * screen.* Everything below that used to be a bordered `Panel` and is a list or a table is this
 * instead; the figures keep their border because a grid of them needs one number-set separated
 * from the next.
 */
function Section({
  title,
  actions,
  children,
  testId,
}: {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section data-testid={testId}>
      <div className="mb-2.5 flex items-baseline justify-between gap-3">
        <h2 className="font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  );
}

/**
 * The form spine — **the draft, the dirty banner and the one Save**, above the tabs.
 *
 * ## The seed is a snapshot, not the live query
 *
 * `base` is the plan as it was when the draft was seeded, and every comparison and every payload is
 * against *it* rather than against the live `plan` prop. A background refetch that arrives while
 * somebody is typing would otherwise change what "dirty" means underneath them — a field they
 * edited could stop being dirty because another author saved the same value — and the `lockVersion`
 * they save against would silently become the newer one, which is optimistic concurrency turned off
 * at exactly the moment it was needed. Held this way, a concurrent edit is a **409 that says to
 * reload**, which is the answer.
 *
 * ## Two tabs write outside this Save, deliberately
 *
 * Business requirements adopts and releases a page (`doc:edit`, its own `PUT`/`DELETE`), and Test
 * artefacts registers rows (`artifact:edit`). Neither waits for Save and neither can make the
 * banner appear — see {@link dirtyTabs}.
 */
function PlanForm({
  projectId,
  planId,
  plan,
  tab,
}: {
  projectId: string;
  planId: string;
  plan: TestPlan;
  tab: PlanTabId;
}) {
  const [base, setBase] = useState<TestPlan>(plan);
  const [draft, setDraft] = useState<PlanDraft>(() => draftFromPlan(plan));
  const [outcome, setOutcome] = useState<SaveOutcome | null>(null);
  const { save, saving } = usePlanSave(projectId, planId);

  const dirty = dirtyTabs(draft, base);
  const problems = planProblems(draft);

  /*
    Every group on the Test cases tab ends in a blank row, which is what makes "add a case" a place
    to type rather than a button (§94's step list). Held as an effect rather than seeded into
    `draftFromPlan`, because the invariant has to be restored in three situations and only one of
    them is the first render: a save reseeds the draft from the response, Discard reseeds it from
    the base, and a section can be added at any time.

    `ensureTrailingBlank` returns the SAME object when nothing is missing, so this cannot loop — and
    the rows it adds are blank, which `liveCaseRows` drops and `dirtyTabs` ignores, so a reader who
    never opens the tab is unaffected by them.
  */
  useEffect(() => {
    const filled = ensureTrailingBlank(draft);
    if (filled !== draft) setDraft(filled);
  }, [draft]);

  /*
    The last save's refusals, keyed as the server keys them, so each one lands on the control it is
    about. Cleared on every press rather than accumulated: a stale error under a field the author
    has since corrected is a screen arguing with itself.

    Only `planFailed` can carry them — the case step's failures are named by title in the report
    above, because the row they belong to may not be on screen (§97's per-step reporting).
  */
  const fieldErrors =
    outcome?.kind === 'planFailed' && outcome.error instanceof ApiError
      ? (outcome.error.fieldErrors ?? {})
      : {};

  /*
    Tells the app this surface is holding work a navigation or a reload would destroy: the
    deploy-skew handler prompts instead of reloading, and a mid-session 401 shows the in-place
    re-auth dialog rather than redirecting off the draft.
  */
  useUnsavedWork(dirty.length > 0 && !saving, 'an edited test plan');

  const edit = (patch: Partial<PlanDraft>) => setDraft((current) => ({ ...current, ...patch }));

  const submit = async () => {
    const result = await save(draft, base);
    setOutcome(result);
    if (result.kind === 'saved') {
      // Reseeded from the server's own response, not from the draft: the plan comes back with a new
      // `lockVersion`, the ids of the cases just created, and every section's recomputed count.
      setBase(result.plan);
      setDraft(draftFromPlan(result.plan));
    }
  };

  // Two reasons a Save can be refused before it is pressed, and they are told apart: the
  // permission is about the caller, the problem is about the draft and names the tab holding it
  // (§31 — disabled, never hidden, always with a reason). The reason is the button's `title`
  // rather than a hover card, because a disabled control receives no pointer events in every
  // engine and a tooltip on one is a reason nobody can read.
  const saveReason = (allowed: boolean) =>
    !allowed
      ? 'You need plan:edit on this project.'
      : problems.length > 0
        ? problems.map((problem) => `${problem.message} (${planTabLabels[problem.tab]})`).join(' ')
        : undefined;

  return (
    <div className="flex flex-col gap-4">
      {dirty.length > 0 && (
        <Banner tone="neutral" data-testid="PlanStats.Dirty">
          <span className="flex flex-1 flex-wrap items-center justify-between gap-3">
            <span>
              Unsaved changes in {dirty.map((id) => planTabLabels[id]).join(', ')}.
              {problems.length > 0 && (
                <span data-testid="PlanStats.Blocked">
                  {' '}
                  {problems.map((problem) => problem.message).join(' ')}
                </span>
              )}
            </span>
            <span className="flex shrink-0 gap-2">
              <Button
                size="sm"
                data-testid="PlanStats.Discard"
                disabled={saving}
                onClick={() => {
                  setDraft(draftFromPlan(base));
                  setOutcome(null);
                }}
              >
                Discard
              </Button>
              {/* Disabled with a reason, never hidden (§31) — and the reason is the permission,
                  which is why the gate wraps the button rather than the banner. */}
              <PermissionGate projectId={projectId} permission="plan:edit">
                {({ allowed }) => (
                  <Button
                    size="sm"
                    variant="primary"
                    title={saveReason(allowed)}
                    data-testid="PlanStats.Save"
                    disabled={!allowed || saving || problems.length > 0}
                    onClick={() => void submit()}
                  >
                    {saving ? 'Saving…' : 'Save'}
                  </Button>
                )}
              </PermissionGate>
            </span>
          </span>
        </Banner>
      )}

      {outcome && <SaveReport outcome={outcome} onDismiss={() => setOutcome(null)} />}

      {tab === 'plan' && (
        <PlanFieldsTab draft={draft} fieldErrors={fieldErrors} onChange={edit} />
      )}
      {tab === 'requirements' && <PlanDocumentPanel projectId={projectId} planId={planId} />}
      {tab === 'artefacts' && <ArtefactsTab projectId={projectId} planId={planId} />}
      {tab === 'scope' && (
        <ScopeTextTab
          testId="PlanStats.ScopeText"
          title="Scope"
          hint="What this release undertakes to test. Prose — the cases themselves are the Test cases tab."
          value={draft.scopeText}
          onChange={(scopeText) => edit({ scopeText })}
        />
      )}
      {tab === 'outOfScope' && (
        <ScopeTextTab
          testId="PlanStats.OutOfScopeText"
          title="Out of scope"
          hint="What this release deliberately does not cover, so nobody has to infer it from an absence."
          value={draft.outOfScopeText}
          onChange={(outOfScopeText) => edit({ outOfScopeText })}
        />
      )}
      {tab === 'cases' && (
        <PlanCasesTab
          projectId={projectId}
          draft={draft}
          plan={base}
          dirty={dirty.includes('cases')}
          onChange={setDraft}
        />
      )}
    </div>
  );
}

/**
 * What the Save did, named per step.
 *
 * **The middle arm is the one this component exists for.** A plan step that fails after the case
 * step wrote three cases leaves those three loose in the project, and an author told only "could not
 * save" will press Save again — which is safe (the row keys make it a retry) but tells them nothing
 * about the three cases they cannot see. So they are named, by key.
 */
function SaveReport({ outcome, onDismiss }: { outcome: SaveOutcome; onDismiss: () => void }) {
  const created = outcome.created.map((row) => row.key).join(', ');

  if (outcome.kind === 'saved') {
    return (
      <Banner tone="neutral" data-testid="PlanStats.SaveReport">
        <span className="flex flex-1 items-start justify-between gap-3">
          <span>
            Plan saved
            {outcome.created.length > 0 && ` — ${plural(outcome.created.length, 'case')} created (${created})`}.
          </span>
          <DismissReport onDismiss={onDismiss} />
        </span>
      </Banner>
    );
  }

  return (
    <Banner tone="bad" data-testid="PlanStats.SaveReport">
      <span className="flex flex-1 items-start justify-between gap-3" role="alert">
        <span>
          <b className="block font-semibold text-ink">
            {outcome.kind === 'planFailed' ? 'The plan was not saved' : 'The cases were not all created'}
          </b>
          {outcome.kind === 'planFailed' ? (
            <>
              {describeError(outcome.error).body}
              {outcome.created.length > 0 && (
                <>
                  {' '}
                  {plural(outcome.created.length, 'case')} were created before this failed
                  ({created}) — they are in the project but not in this plan. Pressing Save again
                  will not create them a second time.
                </>
              )}
            </>
          ) : (
            <>
              {describeError(outcome.error).body} Stopped at “{outcome.failedTitle}”.
              {outcome.created.length > 0
                && ` ${plural(outcome.created.length, 'case')} before it were created (${created}).`}
            </>
          )}
        </span>
        <DismissReport onDismiss={onDismiss} />
      </span>
    </Banner>
  );
}

function DismissReport({ onDismiss }: { onDismiss: () => void }) {
  return (
    <Button size="sm" variant="quiet" onClick={onDismiss}>
      Dismiss
    </Button>
  );
}

/** One prose field, which is the whole of the Scope and Out of scope tabs (§97 D6). */
function ScopeTextTab({
  testId,
  title,
  hint,
  value,
  onChange,
}: {
  testId: string;
  title: string;
  hint: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Section title={title} testId={`${testId}.Panel`}>
      <label className="flex flex-col gap-1.5">
        <span className="sr-only">{title}</span>
        <Textarea
          rows={8}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          data-testid={testId}
        />
        <span className="text-[12px] text-ink-4">{hint}</span>
      </label>
    </Section>
  );
}

/**
 * The plan's own register, through the same table the Artifacts page renders (`ui-rules` §8).
 *
 * **Registering happens here too**, through the same dialog the Artifacts page opens, with this
 * plan pre-selected: somebody looking at Release 4.4's sign-off documents and pressing Register
 * means Release 4.4. A second dialog for one pre-filled field is how two registers come to accept
 * different things — the `FILE` kind in particular, which this product cannot store at all.
 *
 * It writes **outside the plan's Save**, which is why {@link dirtyTabs} can never name this tab:
 * an artifact is its own row under `artifact:edit`, and a banner asking the author to press Save
 * for something already written would be false.
 */
function ArtefactsTab({ projectId, planId }: { projectId: string; planId: string }) {
  const artifacts = useArtifacts(projectId, 'PLAN', planId);
  const plans = usePlans(projectId);
  // The dialog's documentation picker — the register is where a doc page becomes a plan asset.
  const pages = useDocPages(projectId);
  const [registering, setRegistering] = useState(false);

  return (
    <Section
      title="Test artefacts"
      testId="PlanStats.Artefacts"
      actions={
        /*
          Enabled on the key alone. ~~Which is honest here in a way it is not on the Artifacts
          page: `artifact:edit` is qualified by scope, and this tab can only ever register at PLAN
          scope — the one scope every holder of the key has.~~ → **`V52` (§210) de-qualified the
          key**, so both pages are now honest for the same reason and this one is no longer the
          special case. The dialog still renders a 403 if a grant is revoked between opening it and
          pressing Register.
        */
        <PermissionGate projectId={projectId} permission="artifact:edit">
          {({ allowed }) => (
            <Button
              size="sm"
              disabled={!allowed}
              onClick={() => setRegistering(true)}
              data-testid="PlanStats.Artefacts.Register"
            >
              Register
            </Button>
          )}
        </PermissionGate>
      }
    >
      {registering && (
        <RegisterArtifactDialog
          open
          onClose={() => setRegistering(false)}
          projectId={projectId}
          plans={plans.data ?? []}
          pages={pages.data ?? []}
          defaultPlanId={planId}
          onRegistered={() => setRegistering(false)}
        />
      )}
      <DataRegion
        testId="PlanStats.Artefacts.List"
        state={{ ...artifacts, refetch: () => void artifacts.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No artifacts',
          body: 'This plan has no artifacts of its own, and the project has none to inherit. Register them on the project’s Artifacts page.',
        }}
      >
        {(rows) => <ArtifactTable rows={rows} showInheritance projectId={projectId} />}
      </DataRegion>
    </Section>
  );
}

/**
 * The moves this screen offers from each status, and **the permission each one needs**.
 *
 * `satisfies Record<PlanStatus, …>` for `lib/labels`' reason: a sixth status is a compile error
 * here rather than a plan that silently offers nothing.
 *
 * ## This is a second copy of `PlanLifecycle.permissionFor`, deliberately, and bounded
 *
 * The server is the gate; this table exists so §31's disable-don't-hide can be followed at all — a
 * control cannot be disabled with a reason unless the client knows which permission it needs. What
 * keeps the copy honest is that it is *only* the affordance layer and never the verdict: every
 * button below is still refused server-side by the same rule, and the two disagreeing produces a
 * 403 with a sentence rather than an unauthorized write.
 *
 * The interesting row is the one that asks for `plan:approve` on a move *down* the ladder —
 * rescinding an approval. A table keyed only by the target would spell it `plan:edit` and put an
 * enabled "Return to draft" in front of every Test Engineer, who would then be refused by the
 * server. That is exactly the gap the server-side rule closes, mirrored.
 *
 * Three rows since §97 (M7): ~~ACTIVE and CLOSED~~, and with them "Start plan", "Close plan" and
 * "Reopen plan". A plan is an outline; whether its testing is under way is answered by its runs.
 */
const planMoves = {
  DRAFT: [{ target: 'IN_REVIEW', label: 'Submit for review', permission: 'plan:edit' }],
  IN_REVIEW: [
    { target: 'APPROVED', label: 'Approve plan', permission: 'plan:approve' },
    { target: 'DRAFT', label: 'Return to draft', permission: 'plan:edit' },
  ],
  APPROVED: [{ target: 'DRAFT', label: 'Return to draft', permission: 'plan:approve' }],
} as const satisfies Record<PlanStatus, readonly { target: PlanStatus; label: string; permission: string }[]>;

/**
 * The plan's ladder and its delete — the first affordances in the product for a column that had no
 * writer at all until §73, and for `plan:delete`.
 *
 * **The controls swap rather than accumulate**, as on the run detail: an approved plan offers
 * Return to draft and nothing else, because the server refuses everything else outright and
 * offering a move that cannot succeed is what §31 exists to stop.
 *
 * **The refusal renders here, not as a toast.** `useSetPlanStatus` marks its errors handled: "submit
 * it for review first" is an answer about the button that was just pressed.
 */
function PlanLifecycleActions({ projectId, plan }: { projectId: string; plan: TestPlan }) {
  const planId = plan.id;
  const status = plan.status;
  const setStatus = useSetPlanStatus(projectId, planId);
  const [error, setError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [cloning, setCloning] = useState(false);
  const navigate = useNavigate();

  /*
    §82's "Start it anyway" override lived here — a refused start was the one failure on this screen
    that named a follow-up the user could take. It went with the DRAFT → ACTIVE edge in §97 (M7):
    there is no gate on any remaining plan transition, so there is no refusal to offer a way out of,
    and `PlanStatusInput.overrideApprovalGate` is gone from the contract. §6g's override survives
    where its rule does — on run creation.
  */
  const move = (target: PlanStatus) => {
    setError(null);
    setStatus.mutate(
      { status: target },
      { onError: (failure) => setError(describeError(failure).body) },
    );
  };

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex flex-wrap justify-end gap-2">
        {/*
          ~~An Edit button opening `PlanEditDialog`.~~ → **removed in §97 phase 5.** Every field it
          held is on the Plan tab, saved by the page's own Save, so a button here would open a
          second editor for one row — and the dialog's `PATCH` ran on its own lock version, so the
          two could disagree about which write won. What still protects a sign-off is §6g's
          edit-after-approval rule, which the server applies: editing an approved plan on a
          review-enabled project demotes it to DRAFT rather than refusing the save.

          The last move in the list is the primary one — the render puts **Approve plan** in the
          top strip as the screen's single filled button, with everything else quiet beside it.
        */}
        {planMoves[status].map((option, index) => (
          <PermissionGate key={option.target} projectId={projectId} permission={option.permission}>
            {({ allowed }) => (
              <Button
                size="sm"
                variant={index === 0 ? 'primary' : 'ghost'}
                data-testid={`PlanStats.Move.${option.target}`}
                disabled={!allowed || setStatus.isPending}
                onClick={() => move(option.target)}
              >
                {option.label}
              </Button>
            )}
          </PermissionGate>
        ))}
        {/*
          §129. `plan:create`, which is what the endpoint charges even though the clone writes
          criteria and prose that normally cost `plan:edit` — every role holding one holds the
          other, and a backend test derives that from the seeded catalogue rather than restating it.
          Disabled rather than hidden (§31).
        */}
        <PermissionGate projectId={projectId} permission="plan:create">
          {({ allowed }) => (
            <Button
              size="sm"
              data-testid="PlanStats.CloneButton"
              disabled={!allowed}
              onClick={() => setCloning(true)}
            >
              Clone
            </Button>
          )}
        </PermissionGate>
        <PermissionGate projectId={projectId} permission="plan:delete">
          {({ allowed }) => (
            <Button
              size="sm"
              variant="danger"
              data-testid="PlanStats.Delete"
              disabled={!allowed}
              onClick={() => setConfirmingDelete(true)}
            >
              Delete
            </Button>
          )}
        </PermissionGate>
      </div>
      {error && (
        <p className="text-[11.5px] text-[color:var(--color-failed)]" data-testid="PlanStats.StatusError">
          {error}
        </p>
      )}
      {cloning && (
        <PlanCloneDialog
          open
          projectId={projectId}
          plan={plan}
          onClose={() => setCloning(false)}
          // Onto the copy, which is the plan the user is now going to edit. Not `replace`: the
          // source still exists and Back to it is reasonable, unlike after a delete.
          onCloned={(created) => navigate(`/projects/${projectId}/plans/${created.id}`)}
        />
      )}
      {/* Mounted only while confirming, so its reset-on-open effect starts from a clean state. */}
      {confirmingDelete && (
        <PlanDeleteDialog
          open
          projectId={projectId}
          planId={planId}
          onClose={() => setConfirmingDelete(false)}
          onDeleted={() => navigate(`/projects/${projectId}/plans`)}
        />
      )}
    </div>
  );
}

function StatisticsBody({
  data,
  projectId,
  aggregation,
}: {
  data: PlanStatistics;
  projectId: string;
  aggregation: AggregationPolicy;
}) {
  const [denominator, setDenominator] = useState<Denominator>('EXECUTED');
  const notExecuted = data.counts.total === 0 || data.counts.total === data.counts.notRun;

  return (
    <div className="flex flex-col gap-3.5">
      {/*
        The policy statement is part of the report, not a control's side effect. Anyone
        reading a screenshot of this page can tell which arithmetic produced the numbers.

        `steel` rather than amber: this is the instance telling the reader how it counted, not a
        state that is currently wrong — and design language §5 reserves amber for the second.
      */}
      <Banner tone="steel" data-testid="PlanStats.AggregationNotice">
        <span>
          <b className="font-semibold text-ink">{aggregationLabels[aggregation]}.</b>{' '}
          {aggregationHelp[aggregation]}
        </span>
      </Banner>

      {notExecuted ? (
        <section data-testid="PlanStats.NotExecuted">
          <h2 className="mb-1 text-[15px] font-semibold text-ink">Not executed yet</h2>
          <p className="max-w-[74ch] text-[13px] leading-[1.55] text-ink-3">
            This plan has {plural(data.counts.total, 'case')} in scope and no results. Start a run
            to begin recording them.
          </p>
        </section>
      ) : (
        <>
          <FigurePanel
            title="Progress"
            subtitle={`${formatCount(data.counts.total)} in scope`}
            testId="PlanStats.Summary"
          >
            <div className="flex flex-col gap-3">
              <PassRateSummary
                counts={data.counts}
                coveragePercent={data.coveragePercent}
                testId="PlanStats.PassRate"
              />
              <ResultBar
                passed={data.counts.passed}
                failed={data.counts.failed}
                blocked={data.counts.blocked}
                skipped={data.counts.skipped}
                untested={data.counts.notRun}
                className="h-2"
              />
              <ResultLegend counts={data.counts} />
              {data.latestExecutionAt && (
                /*
                  This caption used to read "the read model is catching up, so counts may lag by
                  a few seconds" — the opposite warning to the one `stale` carries. `stale` means
                  NOTHING HAS BEEN EXECUTED IN TWO WEEKS, and plan §6p calls a page showing old
                  numbers as current the most dangerous screen in the product. Reassuring the
                  reader was exactly the wrong response, and it went unnoticed because until the
                  endpoint went live the mock never set the flag.

                  ~~The figures are recomputed per request and never lag.~~ → SUPERSEDED by §101:
                  under LATEST and LATEST_PER_CONFIG they now come from the `plan_snapshot` rollup
                  and CAN lag, by up to one scheduler tick. That is not a reason to bring the old
                  sentence back — it was wrong then and would be wrong now, because it conflated
                  two ages. The lag has its own line below, its own field, and its own subject.
                */
                <p
                  style={data.stale ? { color: 'var(--color-blocked)' } : undefined}
                  className="text-[11.5px] leading-[1.5] text-ink-4"
                  data-testid="PlanStats.Freshness"
                >
                  Latest result {formatDateTime(data.latestExecutionAt)}
                  {data.stale
                    && ' — nothing has been executed against this plan in over two weeks. These'
                      + ' numbers describe the past, not the build you are about to ship.'}
                </p>
              )}
              {/*
                The OTHER age, and a separate line on purpose. `stale` above is about the
                executions; this is about the sums. Rendering them in one sentence — or styling
                this one like a warning — would put two different claims behind one signal, which
                is exactly the ambiguity the second field was added to remove. Always rendered,
                because "Computed now" is a positive statement rather than the absence of a
                warning: a screen that says nothing when it is current is a screen where the reader
                cannot tell current from unlabelled.
              */}
              <p className="text-[11.5px] text-ink-4" data-testid="PlanStats.ComputedAt">
                {formatComputedAt(data.computedAt)}
                {data.computedAt && ' — updated every 15 minutes'}
              </p>
            </div>
          </FigurePanel>

          <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
            <TrendChart trend={data.trend ?? []} />
            <ExitCriteria criteria={data.exitCriteria ?? []} />
          </div>

          <div className="grid gap-3.5 lg:grid-cols-2">
            <FigurePanel
              title="By configuration"
              subtitle="one row per configuration the plan ran on"
              testId="PlanStats.ByConfiguration"
              actions={
                <DenominatorToggle
                  value={denominator}
                  onChange={setDenominator}
                  testId="PlanStats.Denominator"
                />
              }
            >
              <BreakdownTable
                rows={(data.byConfiguration ?? []).map((row) => ({
                  id: row.configuration,
                  label: row.configuration,
                  counts: row.counts,
                }))}
                denominator={denominator}
                testId="PlanStats.ByConfiguration"
                emptyBody="No results recorded per configuration yet."
              />
            </FigurePanel>

            <FigurePanel title="By suite" subtitle="the same cases, grouped by where they are filed" testId="PlanStats.BySuite">
              <BreakdownTable
                rows={(data.bySuite ?? []).map((row) => ({
                  // All three suite fields are null together for the in-scope cases filed in no
                  // suite. The row is rendered rather than dropped — dropping it would make this
                  // panel stop summing to the counts above it, with nothing on screen to say why
                  // — and its link is the grid's unfiled filter, which is where those cases are.
                  id: row.suiteId ?? 'unfiled',
                  label: row.suiteName ?? 'Not filed',
                  identifier: row.suiteKey ?? undefined,
                  href:
                    row.suiteId === null
                      ? `/projects/${projectId}/cases?unfiled=true`
                      : `/projects/${projectId}/cases?suiteId=${row.suiteId}`,
                  counts: row.counts,
                }))}
                denominator={denominator}
                testId="PlanStats.BySuite"
                emptyBody="No suites in scope."
              />
            </FigurePanel>
          </div>

          <Blockers blockers={data.blockers ?? []} projectId={projectId} />

          <NotRun cases={data.notRun ?? []} projectId={projectId} />
        </>
      )}
    </div>
  );
}

/**
 * The words under the headline bar, and the one place `--color-untested` still speaks.
 *
 * Design language §6 took `untested` out of the fills, which leaves a reader looking at a track
 * that ends early with no way to know whether that is "not run" or "the bar stops here". The
 * legend answers it once, in words, with the never-run swatch drawn as the empty track it is.
 */
function ResultLegend({ counts }: { counts: PlanStatistics['counts'] }) {
  const entries = [
    { label: 'Passed', count: counts.passed, color: 'var(--color-passed)' },
    { label: 'Failed', count: counts.failed, color: 'var(--color-failed)' },
    { label: 'Blocked', count: counts.blocked, color: 'var(--color-blocked)' },
    { label: 'Skipped', count: counts.skipped, color: 'var(--color-skipped)' },
    { label: 'Never run', count: counts.notRun, color: null },
  ];

  return (
    <div className="flex flex-wrap gap-3.5 text-[11.5px] text-ink-3">
      {entries.map((entry) => (
        <span key={entry.label} className="flex items-center gap-1.5">
          <i
            aria-hidden
            style={entry.color ? { backgroundColor: entry.color } : undefined}
            className={
              entry.color
                ? 'block size-2.5 rounded-[1px]'
                : 'block size-2.5 rounded-[1px] border border-line-strong bg-field'
            }
          />
          {entry.label} <Identifier variant="muted">{formatCount(entry.count)}</Identifier>
        </span>
      ))}
    </div>
  );
}

/**
 * Exit criteria, each stated with its target *and* its actual. A criterion showing only a
 * red cross tells the reader they can't ship but not how far off they are — and "89.1%
 * against a 95% target" is what a release conversation actually needs.
 */
function ExitCriteria({ criteria }: { criteria: Criterion[] }) {
  if (criteria.length === 0) return null;
  // `met: null` is "not evaluated", which is neither met nor unmet. The server returns it for
  // metrics it cannot answer from a plan's own results — open_critical needs the issue tracker,
  // cases_approved the review workflow. Treating null as unmet (`!c.met`) put a red cross and a
  // release-blocking count against a gate nobody had checked; it only became reachable when the
  // statistics endpoint went live, because the mock had always supplied a boolean.
  const unmet = criteria.filter((c) => criterionState(c) === 'UNMET');
  const unevaluated = criteria.filter((c) => criterionState(c) === 'NOT_EVALUATED');

  return (
    <FigurePanel
      title="What's blocking this plan"
      subtitle={`${formatCount(criteria.length)} exit criteria · ${formatCount(criteria.length - unmet.length - unevaluated.length)} met`}
      testId="PlanStats.ExitCriteria"
      actions={
        <div className="flex items-center gap-1.5">
          <Badge tone={unmet.length === 0 ? 'passed' : 'failed'} data-testid="PlanStats.ExitCriteria.Summary">
            {unmet.length === 0 ? 'All met' : `${unmet.length} unmet`}
          </Badge>
          {unevaluated.length > 0 && (
            <Badge tone="plain" data-testid="PlanStats.ExitCriteria.Unevaluated">
              {unevaluated.length} not evaluated
            </Badge>
          )}
        </div>
      }
    >
      {/*
        Without this line the panel contradicts the headline: a pass-rate criterion is
        evaluated against every case in scope, so it reads 89.1% while the summary above
        shows 91.1% "of executed". Both are right; the difference is the denominator, and
        leaving the reader to work that out is precisely the ambiguity this screen exists
        to remove.
      */}
      {criteria.some((c) => c.metric === 'pass_rate') && (
        <p
          className="mb-2 max-w-[74ch] text-[11.5px] leading-[1.5] text-ink-4"
          data-testid="PlanStats.ExitCriteria.DenominatorNotice"
        >
          Criteria are evaluated against every case in scope, not only those executed — so a
          pass-rate figure here is lower than the "of executed" rate above until coverage
          reaches 100%.
        </p>
      )}
      {criteria.map((criterion, index) => {
        // Three states, not two. A criterion the server could not evaluate is grey and says so,
        // rather than borrowing the failed colour and reading as a blocker.
        //
        // The mapping moved to `lib/results.ts` in §100, when the plan LIST grew a second
        // reader of it (`CriteriaStrip`). It was correct here and a second inline copy there is
        // how the two would come to draw the same unevaluated gate in different colours.
        const state = criterionState(criterion);
        const color = criterionColor(state);
        const Mark = state === 'NOT_EVALUATED' ? CircleHelp : state === 'MET' ? CircleCheck : CircleAlert;

        return (
          <div
            // Metric is not unique — a plan may gate on pass_rate per configuration — so the
            // index is part of the key rather than the whole of it being the metric.
            key={`${criterion.metric}-${index}`}
            className="flex items-start gap-[11px] border-b border-line py-2.5 text-[13px] last:border-b-0"
            data-testid="PlanStats.ExitCriteria.Item"
          >
            <Mark aria-hidden style={{ color }} className="mt-px size-4 shrink-0" />
            <span className="min-w-0 flex-1 text-ink-2">
              {criterion.label}
              {state === 'NOT_EVALUATED' && (
                <span
                  className="block text-[12px] text-ink-4"
                  data-testid="PlanStats.ExitCriteria.Item.NotEvaluated"
                >
                  not evaluated: nothing in TCMS measures this yet
                </span>
              )}
              <span className="block text-[12px] text-ink-4">
                target <Identifier variant="muted">{criterion.op} {criterion.value}</Identifier>
              </span>
            </span>
            <span style={{ color }} className="shrink-0 font-mono text-[15px] font-medium tabular-nums">
              {criterion.actual ?? EM_DASH}
            </span>
          </div>
        );
      })}
    </FigurePanel>
  );
}

interface BreakdownRow {
  id: string;
  label: string;
  identifier?: string;
  href?: string;
  counts: PlanStatistics['counts'];
}

function BreakdownTable({
  rows,
  denominator,
  testId,
  emptyBody,
}: {
  rows: BreakdownRow[];
  denominator: Denominator;
  testId: string;
  emptyBody: string;
}) {
  if (rows.length === 0) {
    return <p className="text-[12.5px] text-ink-3">{emptyBody}</p>;
  }

  return (
    <div data-testid={`${testId}.Grid`}>
      {rows.map((row) => (
        <div
          key={row.id}
          className="grid grid-cols-[minmax(0,1fr)_minmax(70px,110px)_auto] items-center gap-[11px] border-b border-line py-2 text-[12.5px] last:border-b-0"
          data-testid={`${testId}.Row`}
        >
          <span className="flex min-w-0 items-center gap-2">
            {row.identifier && <Identifier variant="muted">{row.identifier}</Identifier>}
            {row.href ? (
              <RouterLink to={row.href} data-testid={`${testId}.Row.Link`} className="truncate text-ink-2 hover:text-ink">
                {row.label}
              </RouterLink>
            ) : (
              <span className="truncate text-ink-2">{row.label}</span>
            )}
          </span>
          <ResultBar
            passed={row.counts.passed}
            failed={row.counts.failed}
            blocked={row.counts.blocked}
            skipped={row.counts.skipped}
            untested={row.counts.notRun}
          />
          <ResultCountsCell
            counts={row.counts}
            denominator={denominator}
            testId={`${testId}.Row.PassRate`}
          />
        </div>
      ))}
    </div>
  );
}

/**
 * Pass-rate trend across the plan's runs — **the one line chart in the product**, and the only
 * place a chart library would ever have been considered.
 *
 * Three things here are design language §6 rather than taste:
 *
 * - **The y axis starts at zero and the figure says so.** A truncated axis turns a six-point
 *   recovery into a cliff, and a reader cannot tell one from the other without being told.
 * - **A crosshair and a readout by default, not on request.** An SVG chart in a browser *is*
 *   interactive; shipping one without a hover layer makes the reader estimate against gridlines.
 * - **One series, in steel.** There is no categorical palette in this product, so there is no
 *   second line to add here without faceting.
 *
 * Six points still do not justify a charting dependency, and the run keys underneath are what turn
 * "it dropped" into "it dropped at TR-415".
 */
function TrendChart({ trend }: { trend: NonNullable<PlanStatistics['trend']> }) {
  const [hovered, setHovered] = useState<number | null>(null);

  if (trend.length < 2) return null;

  const width = 520;
  const height = 130;
  const inset = 20;
  const span = width - inset * 2;
  const x = (index: number) => inset + (index / (trend.length - 1)) * span;
  // Zero-based, and the whole reason this expression is not `(rate - min) / (max - min)`.
  const y = (rate: number) => height - (rate / 100) * height;

  const line = trend.map((point, index) => `${x(index).toFixed(1)} ${y(point.passRate).toFixed(1)}`).join(' L ');
  const latest = trend[trend.length - 1];
  const previous = trend[trend.length - 2];
  const delta = latest.passRate - previous.passRate;
  const readout = hovered === null ? latest : trend[hovered];
  const readoutIndex = hovered ?? trend.length - 1;

  return (
    <FigurePanel
      title="Pass rate over the plan's runs"
      subtitle="one series · each point is a completed run"
      testId="PlanStats.Trend"
    >
      <div className="flex items-baseline gap-2.5">
        <span className="font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink">
          {formatPercent(latest.passRate)}
        </span>
        <span
          style={{ color: delta >= 0 ? 'var(--color-passed)' : 'var(--color-failed)' }}
          className="text-[12px]"
        >
          {delta >= 0 ? '+' : ''}
          {formatPercent(delta)} vs {previous.runKey}
        </span>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Pass rate across ${trend.length} runs, most recently ${formatPercent(latest.passRate)}. The vertical axis runs from 0 to 100 per cent.`}
        data-testid="PlanStats.Trend.Chart"
        className="mt-2.5 h-[130px] w-full overflow-visible"
        onMouseLeave={() => setHovered(null)}
      >
        <defs>
          <linearGradient id="planTrendFade" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--color-steel)" stopOpacity=".22" />
            <stop offset="100%" stopColor="var(--color-steel)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[0, 50, 100].map((rate) => (
          <line
            key={rate}
            x1={0}
            y1={y(rate)}
            x2={width}
            y2={y(rate)}
            stroke="var(--color-border)"
            strokeWidth={1}
          />
        ))}
        <path d={`M ${line} L ${x(trend.length - 1)} ${height} L ${x(0)} ${height} Z`} fill="url(#planTrendFade)" />
        <path
          d={`M ${line}`}
          fill="none"
          stroke="var(--color-steel)"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
        <line
          x1={x(readoutIndex)}
          y1={0}
          x2={x(readoutIndex)}
          y2={height}
          stroke="var(--color-border-strong)"
          strokeWidth={1}
          strokeDasharray="3 3"
          vectorEffect="non-scaling-stroke"
        />
        {trend.map((point, index) => (
          <circle
            key={point.runKey}
            cx={x(index)}
            cy={y(point.passRate)}
            r={index === readoutIndex ? 5 : 3.5}
            fill="var(--color-steel)"
            stroke="var(--color-panel)"
            strokeWidth={2}
            vectorEffect="non-scaling-stroke"
          />
        ))}
        {/*
          The hit areas are the last children so they sit above the drawing, and they are one
          column per point rather than a mousemove handler doing arithmetic: a column is a real
          target a pointer can rest in, and it needs no scale inversion to know which run it is.
        */}
        {trend.map((point, index) => (
          <rect
            key={`${point.runKey}-hit`}
            x={x(index) - span / (trend.length - 1) / 2}
            y={0}
            width={span / (trend.length - 1)}
            height={height}
            fill="transparent"
            onMouseEnter={() => setHovered(index)}
          />
        ))}
      </svg>

      <p className="mt-1.5 flex justify-between font-mono text-[10px] text-ink-4">
        <span>{trend[0].runKey}</span>
        <span>{latest.runKey}</span>
      </p>

      <p className="mt-2.5 text-[12px] text-ink-3" data-testid="PlanStats.Trend.Readout">
        <Identifier variant="muted">{readout.runKey}</Identifier> — pass rate{' '}
        <Identifier>{formatPercent(readout.passRate)}</Identifier>
      </p>
      <p className="mt-1 text-[11.5px] text-ink-4">
        The vertical axis starts at <b className="font-medium text-ink-3">0</b>, not at the lowest
        point. A truncated axis turns a six-point recovery into a cliff.
      </p>
    </FigurePanel>
  );
}

function Blockers({
  blockers,
  projectId,
}: {
  blockers: NonNullable<PlanStatistics['blockers']>;
  projectId: string;
}) {
  return (
    <Section
      title="What failed or was blocked"
      testId="PlanStats.Blockers"
      actions={
        <span className="text-[11.5px] text-ink-4">{formatCount(blockers.length)} failed or blocked</span>
      }
    >
      {blockers.length === 0 ? (
        <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
          Nothing failed or blocked. Anything unmet in the exit criteria above is about coverage,
          not defects.
        </p>
      ) : (
        <Table data-testid="PlanStats.Blockers.Grid">
          <TableHead>
            <TableRow>
              <TableHeaderCell>Case</TableHeaderCell>
              <TableHeaderCell className="w-[110px]">Result</TableHeaderCell>
              <TableHeaderCell className="w-[130px]">Defects</TableHeaderCell>
              <TableHeaderCell className="w-[150px]">Executed</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {blockers.map((blocker) => (
              <TableRow key={blocker.id} data-testid="PlanStats.Blockers.Row">
                {/*
                  The title is the cell's own line and the key sits above it — UX#113's quiet
                  second line rather than a twelfth column, and it is what lets the title stop
                  being truncated to an ellipsis nobody can read.
                */}
                <TableCell variant="title" subtitle={blocker.caseTitle}>
                  <span className="flex items-center gap-2">
                    <RouterLink
                      to={`/projects/${projectId}/cases/${blocker.caseId}`}
                      data-testid="PlanStats.Blockers.Row.CaseLink"
                    >
                      <Identifier>{blocker.caseKey}</Identifier>
                    </RouterLink>
                    {/* The version that failed, not the current one — a case edited since
                        would otherwise silently rewrite what this row means. */}
                    <Identifier variant="muted">
                      <span title="The case version this run executed">v{blocker.caseVersionNo}</span>
                    </Identifier>
                  </span>
                </TableCell>
                <TableCell>
                  {blocker.status?.kind && <StatusChip kind={blocker.status.kind} />}
                </TableCell>
                <TableCell>
                  {blocker.defects.length === 0 ? (
                    <span className="text-[11.5px] text-[color:var(--color-blocked)]">No defect linked</span>
                  ) : (
                    <span className="flex flex-col gap-0.5">
                      {blocker.defects.map((defect) => (
                        <a
                          key={defect.id}
                          href={defect.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          data-testid="PlanStats.Blockers.Row.DefectLink"
                          className="inline-flex items-center gap-1 text-[12px] text-steel hover:underline"
                        >
                          <Identifier>{defect.externalKey}</Identifier>
                          {defect.statusCache && <span className="text-ink-4">{defect.statusCache}</span>}
                          <ExternalLink aria-hidden className="size-3 text-ink-4" />
                          <span className="sr-only">(opens in a new tab)</span>
                        </a>
                      ))}
                    </span>
                  )}
                </TableCell>
                <TableCell className="text-[11.5px] text-ink-4">
                  {formatDateTime(blocker.executedAt)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Section>
  );
}

function NotRun({
  cases,
  projectId,
}: {
  cases: NonNullable<PlanStatistics['notRun']>;
  projectId: string;
}) {
  if (cases.length === 0) return null;

  return (
    <Section title="Still to execute" testId="PlanStats.NotRun">
      <div className="flex flex-col">
        {cases.map((testCase) => (
          <div
            key={testCase.id}
            className="flex min-w-0 items-center gap-2 border-b border-line py-1.5 text-[12.5px] last:border-b-0"
            data-testid="PlanStats.NotRun.Row"
          >
            <RouterLink to={`/projects/${projectId}/cases/${testCase.id}`} data-testid="PlanStats.NotRun.Row.CaseLink">
              <Identifier>{testCase.key}</Identifier>
            </RouterLink>
            <span className="truncate text-ink-2">{testCase.title}</span>
            <span className="ml-auto shrink-0 text-[11.5px] text-ink-4">{testCase.suiteName}</span>
          </div>
        ))}
      </div>
    </Section>
  );
}
