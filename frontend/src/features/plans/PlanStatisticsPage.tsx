import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  MenuItem,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined';
import HelpOutlineIcon from '@mui/icons-material/HelpOutlineOutlined';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import type { AggregationPolicy, Criterion, PlanStatistics, PlanStatus, TestPlan } from '../../api/types';
import {
  useArtifacts,
  useDocPages,
  usePlan,
  usePlans,
  usePlanStatistics,
  useProject,
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
import { PageHeader, Panel } from '../../components/PageHeader';
import { PlanDocumentPanel } from './PlanDocumentPanel';
import { ReviewPanel } from '../review/ReviewPanel';
import { PermissionGate } from '../../components/PermissionGate';
import { DenominatorToggle, PassRateSummary, ResultBar, ResultCountsCell } from '../../components/ResultBar';
import { criterionColor, criterionState, type Denominator } from '../../lib/results';
import { StatusChip } from '../../components/StatusChip';
import { describeError } from '../../lib/describeError';
import { formatComputedAt, formatCount, formatDateTime, formatPercent } from '../../lib/format';
import {
  aggregationHelp,
  aggregationLabels,
  planStatusColors,
  planStatusLabels,
} from '../../lib/labels';
import { PlanCloneDialog } from './PlanCloneDialog';
import { PlanDeleteDialog } from './PlanDeleteDialog';
import { ScrollX } from '../../components/ScrollX';

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

  const project = useProject(projectId);
  const plan = usePlan(projectId, planId);
  const statistics = usePlanStatistics(projectId, planId, aggregation);

  return (
    <Box>
      <PageHeader
        testId="PlanStats.Header"
        title={plan.data?.name ?? 'Test plan'}
        documentTitle={plan.data ? `${plan.data.key} ${plan.data.name}` : 'Test plan'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test plans', to: `/projects/${projectId}/plans` },
          { label: plan.data?.key ?? 'Plan' },
        ]}
        meta={
          plan.data && (
            <>
              <Identifier variant="strong">{plan.data.key}</Identifier>
              <Chip
                size="small"
                variant="outlined"
                data-testid="PlanStats.Status"
                label={planStatusLabels[plan.data.status]}
                sx={{ color: planStatusColors[plan.data.status] }}
              />
              {plan.data.milestoneName && (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  {plan.data.milestoneName}
                  {plan.data.milestoneDate && ` · due ${plan.data.milestoneDate}`}
                </Typography>
              )}
            </>
          )
        }
        subtitle={plan.data?.description}
        actions={
          <Stack sx={{ gap: 1, alignItems: 'flex-end' }}>
            {plan.data && (
              <PlanLifecycleActions projectId={projectId} plan={plan.data} />
            )}
            <Box sx={{ minWidth: 260, display: tab === 'progress' ? 'block' : 'none' }}>
              <Select
                size="small"
                fullWidth
                value={aggregation}
                onChange={(event) => {
                  const next = new URLSearchParams(params);
                  next.set('aggregation', event.target.value);
                  setParams(next, { replace: true });
                }}
                data-testid="PlanStats.AggregationSelect"
                inputProps={{ 'aria-label': 'How results are aggregated' }}
              >
                {Object.entries(aggregationLabels).map(([value, label]) => (
                  <MenuItem key={value} value={value}>
                    {label}
                  </MenuItem>
                ))}
              </Select>
            </Box>
          </Stack>
        }
      />

      <Tabs
        value={tab}
        onChange={(_event, next: string) => selectTab(next)}
        variant="scrollable"
        scrollButtons="auto"
        sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}
        data-testid="PlanStats.Tabs"
      >
        {PLAN_TABS.map((id) => (
          <Tab key={id} value={id} label={planTabLabels[id]} data-testid={`PlanStats.Tab.${id}`} />
        ))}
      </Tabs>

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
      <Box sx={{ mt: 3 }}>
        <ReviewPanel
          projectId={projectId}
          subjectType="PLAN"
          subjectId={planId}
          currentVersionNo={plan.data?.currentVersionNo}
        />
      </Box>
    </Box>
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

  return (
    <Stack sx={{ gap: 2 }}>
      {dirty.length > 0 && (
        <Alert
          severity="info"
          data-testid="PlanStats.Dirty"
          action={
            <Stack direction="row" sx={{ gap: 1 }}>
              <Button
                size="small"
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
                {({ allowed }) => {
                  // Two reasons a Save can be refused before it is pressed, and they are told
                  // apart: the permission is about the caller, the problem is about the draft and
                  // names the tab holding it (§31 — disabled, never hidden, always with a reason).
                  const reason = !allowed
                    ? 'You need plan:edit on this project.'
                    : problems.length > 0
                      ? problems
                          .map((problem) => `${problem.message} (${planTabLabels[problem.tab]})`)
                          .join(' ')
                      : '';
                  return (
                    <Tooltip title={reason}>
                      <Box>
                        <Button
                          size="small"
                          variant="contained"
                          data-testid="PlanStats.Save"
                          disabled={!allowed || saving || problems.length > 0}
                          onClick={() => void submit()}
                        >
                          {saving ? 'Saving…' : 'Save'}
                        </Button>
                      </Box>
                    </Tooltip>
                  );
                }}
              </PermissionGate>
            </Stack>
          }
        >
          Unsaved changes in {dirty.map((id) => planTabLabels[id]).join(', ')}.
          {problems.length > 0 && (
            <Box component="span" data-testid="PlanStats.Blocked">
              {' '}
              {problems.map((problem) => problem.message).join(' ')}
            </Box>
          )}
        </Alert>
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
    </Stack>
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
      <Alert severity="success" onClose={onDismiss} data-testid="PlanStats.SaveReport">
        Plan saved
        {outcome.created.length > 0 && ` — ${formatCount(outcome.created.length)} cases created (${created})`}.
      </Alert>
    );
  }

  return (
    <Alert severity="error" role="alert" onClose={onDismiss} data-testid="PlanStats.SaveReport">
      <AlertTitle>
        {outcome.kind === 'planFailed' ? 'The plan was not saved' : 'The cases were not all created'}
      </AlertTitle>
      {outcome.kind === 'planFailed' ? (
        <>
          {describeError(outcome.error).body}
          {outcome.created.length > 0 && (
            <>
              {' '}
              {formatCount(outcome.created.length)} cases were created before this failed
              ({created}) — they are in the project but not in this plan. Pressing Save again
              will not create them a second time.
            </>
          )}
        </>
      ) : (
        <>
          {describeError(outcome.error).body} Stopped at “{outcome.failedTitle}”.
          {outcome.created.length > 0
            && ` ${formatCount(outcome.created.length)} cases before it were created (${created}).`}
        </>
      )}
    </Alert>
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
    <Panel title={title} testId={`${testId}.Panel`}>
      <TextField
        multiline
        minRows={8}
        fullWidth
        label={title}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        helperText={hint}
        slotProps={{ htmlInput: { 'data-testid': testId } }}
      />
    </Panel>
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
    <Panel
      testId="PlanStats.Artefacts"
      dense
      actions={
        /*
          Enabled on the key alone, which is honest here in a way it is not on the Artifacts page:
          `artifact:edit` is qualified by scope, and this tab can only ever register at PLAN scope
          — the one scope every holder of the key has. The dialog still renders the scope-qualified
          403 if a grant is revoked between opening it and pressing Register.
        */
        <PermissionGate projectId={projectId} permission="artifact:edit">
          {({ allowed }) => (
            <Button
              size="small"
              variant="outlined"
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
    </Panel>
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
    <Stack sx={{ gap: 0.5, alignItems: 'flex-end' }}>
      <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {/*
          ~~An Edit button opening `PlanEditDialog`.~~ → **removed in §97 phase 5.** Every field it
          held is on the Plan tab, saved by the page's own Save, so a button here would open a
          second editor for one row — and the dialog's `PATCH` ran on its own lock version, so the
          two could disagree about which write won. What still protects a sign-off is §6g's
          edit-after-approval rule, which the server applies: editing an approved plan on a
          review-enabled project demotes it to DRAFT rather than refusing the save.
        */}
        {planMoves[status].map((option) => (
          <PermissionGate key={option.target} projectId={projectId} permission={option.permission}>
            {({ allowed }) => (
              <Button
                size="small"
                variant="outlined"
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
              size="small"
              variant="outlined"
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
              size="small"
              variant="outlined"
              color="error"
              data-testid="PlanStats.Delete"
              disabled={!allowed}
              onClick={() => setConfirmingDelete(true)}
            >
              Delete
            </Button>
          )}
        </PermissionGate>
      </Stack>
      {error && (
        <Typography variant="caption" sx={{ color: 'var(--color-failed)' }} data-testid="PlanStats.StatusError">
          {error}
        </Typography>
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
    </Stack>
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
    <Stack sx={{ gap: 3 }}>
      {/*
        The policy statement is part of the report, not a control's side effect. Anyone
        reading a screenshot of this page can tell which arithmetic produced the numbers.
      */}
      <Alert severity="info" icon={false} data-testid="PlanStats.AggregationNotice">
        <Typography variant="body2">
          <strong>{aggregationLabels[aggregation]}.</strong> {aggregationHelp[aggregation]}
        </Typography>
      </Alert>

      {notExecuted ? (
        <Panel testId="PlanStats.NotExecuted">
          <Typography variant="h4" sx={{ mb: 0.5 }}>
            Not executed yet
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            This plan has {formatCount(data.counts.total)} cases in scope and no results. Start a run
            to begin recording them.
          </Typography>
        </Panel>
      ) : (
        <>
          <Panel testId="PlanStats.Summary">
            <Stack sx={{ gap: 2 }}>
              <PassRateSummary
                counts={data.counts}
                coveragePercent={data.coveragePercent}
                testId="PlanStats.PassRate"
              />
              <ResultBar counts={data.counts} height={14} showLegend testId="PlanStats.ResultBar" />
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
                <Typography
                  variant="caption"
                  sx={{ color: data.stale ? 'var(--color-blocked)' : 'text.secondary' }}
                  data-testid="PlanStats.Freshness"
                >
                  Latest result {formatDateTime(data.latestExecutionAt)}
                  {data.stale
                    && ' — nothing has been executed against this plan in over two weeks. These'
                      + ' numbers describe the past, not the build you are about to ship.'}
                </Typography>
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
              <Typography
                variant="caption"
                sx={{ color: 'text.secondary' }}
                data-testid="PlanStats.ComputedAt"
              >
                {formatComputedAt(data.computedAt)}
                {data.computedAt && ' — updated every 15 minutes'}
              </Typography>
            </Stack>
          </Panel>

          <ExitCriteria criteria={data.exitCriteria ?? []} />

          <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'stretch' }}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Panel
                title="By configuration"
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
              </Panel>
            </Box>

            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Panel title="By suite" testId="PlanStats.BySuite">
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
              </Panel>
            </Box>
          </Stack>

          <TrendChart trend={data.trend ?? []} />

          <Blockers blockers={data.blockers ?? []} projectId={projectId} />

          <NotRun cases={data.notRun ?? []} projectId={projectId} />
        </>
      )}
    </Stack>
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
    <Panel
      title="Exit criteria"
      testId="PlanStats.ExitCriteria"
      actions={
        <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center' }}>
          <Chip
            size="small"
            label={unmet.length === 0 ? 'All met' : `${unmet.length} unmet`}
            data-testid="PlanStats.ExitCriteria.Summary"
            sx={{
              bgcolor: 'transparent',
              color: unmet.length === 0 ? 'var(--color-passed)' : 'var(--color-failed)',
              border: `1px solid color-mix(in srgb, ${
                unmet.length === 0 ? 'var(--color-passed)' : 'var(--color-failed)'
              } 45%, transparent)`,
            }}
          />
          {unevaluated.length > 0 && (
            <Chip
              size="small"
              label={`${unevaluated.length} not evaluated`}
              data-testid="PlanStats.ExitCriteria.Unevaluated"
              sx={{
                bgcolor: 'transparent',
                color: 'text.secondary',
                border: '1px solid color-mix(in srgb, currentColor 30%, transparent)',
              }}
            />
          )}
        </Stack>
      }
    >
      <Stack sx={{ gap: 1 }}>
        {/*
          Without this line the panel contradicts the headline: a pass-rate criterion is
          evaluated against every case in scope, so it reads 89.1% while the summary above
          shows 91.1% "of executed". Both are right; the difference is the denominator, and
          leaving the reader to work that out is precisely the ambiguity this screen exists
          to remove.
        */}
        {criteria.some((c) => c.metric === 'pass_rate') && (
          <Typography
            variant="caption"
            sx={{ color: 'text.secondary', mb: 0.5 }}
            data-testid="PlanStats.ExitCriteria.DenominatorNotice"
          >
            Criteria are evaluated against every case in scope, not only those executed — so a
            pass-rate figure here is lower than the "of executed" rate above until coverage
            reaches 100%.
          </Typography>
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

          return (
            <Stack
              // Metric is not unique — a plan may gate on pass_rate per configuration — so the
              // index is part of the key rather than the whole of it being the metric.
              key={`${criterion.metric}-${index}`}
              direction="row"
              sx={{ alignItems: 'center', gap: 1.5 }}
              data-testid="PlanStats.ExitCriteria.Item"
            >
              <Box sx={{ display: 'flex', color }}>
                {state === 'NOT_EVALUATED' ? (
                  <HelpOutlineIcon fontSize="small" />
                ) : state === 'MET' ? (
                  <CheckCircleOutlineIcon fontSize="small" />
                ) : (
                  <ErrorOutlineIcon fontSize="small" />
                )}
              </Box>
              <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
                {criterion.label}
                {state === 'NOT_EVALUATED' && (
                  <Typography
                    component="span"
                    variant="caption"
                    sx={{ color: 'text.secondary', ml: 1 }}
                    data-testid="PlanStats.ExitCriteria.Item.NotEvaluated"
                  >
                    — not evaluated: nothing in TCMS measures this yet
                  </Typography>
                )}
              </Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                target <Identifier variant="muted">{criterion.op} {criterion.value}</Identifier>
              </Typography>
              <Identifier sx={{ minWidth: 64, textAlign: 'right', color, fontWeight: 600 }}>
                {criterion.actual ?? '—'}
              </Identifier>
            </Stack>
          );
        })}
      </Stack>
    </Panel>
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
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        {emptyBody}
      </Typography>
    );
  }

  return (
    <ScrollX>
      <Table size="small" data-testid={`${testId}.Grid`}>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id} data-testid={`${testId}.Row`}>
              <TableCell sx={{ border: 0, pl: 0, width: '40%' }}>
                <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                  {row.identifier && <Identifier variant="muted">{row.identifier}</Identifier>}
                  {row.href ? (
                    <RouterLink to={row.href} data-testid={`${testId}.Row.Link`}>
                      <Typography variant="body2" component="span">
                        {row.label}
                      </Typography>
                    </RouterLink>
                  ) : (
                    <Typography variant="body2">{row.label}</Typography>
                  )}
                </Stack>
              </TableCell>
              <TableCell sx={{ border: 0, width: '35%' }}>
                <ResultBar counts={row.counts} />
              </TableCell>
              <TableCell sx={{ border: 0, pr: 0 }} align="right">
                <ResultCountsCell
                  counts={row.counts}
                  denominator={denominator}
                  testId={`${testId}.Row.PassRate`}
                />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}

/**
 * Pass-rate trend across the plan's runs. A sparkline, drawn as an inline SVG polyline —
 * six points do not justify a charting dependency, and the run keys underneath make each
 * point identifiable, which is what turns "it dropped" into "it dropped at TR-415".
 */
function TrendChart({ trend }: { trend: NonNullable<PlanStatistics['trend']> }) {
  if (trend.length < 2) return null;

  const width = 100;
  const height = 32;
  const points = trend
    .map((point, i) => {
      const x = (i / (trend.length - 1)) * width;
      const y = height - (point.passRate / 100) * height;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');

  const latest = trend[trend.length - 1];
  const previous = trend[trend.length - 2];
  const delta = latest.passRate - previous.passRate;

  return (
    <Panel title="Pass rate over the plan's runs" testId="PlanStats.Trend">
      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 3, alignItems: { sm: 'center' } }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Box
            component="svg"
            viewBox={`0 0 ${width} ${height}`}
            preserveAspectRatio="none"
            role="img"
            aria-label={`Pass rate across ${trend.length} runs, most recently ${formatPercent(latest.passRate)}`}
            data-testid="PlanStats.Trend.Chart"
            sx={{ width: '100%', height: 64, overflow: 'visible' }}
          >
            <polyline
              points={points}
              fill="none"
              stroke="var(--color-action-primary)"
              strokeWidth={1.5}
              vectorEffect="non-scaling-stroke"
            />
            {trend.map((point, i) => (
              <circle
                key={point.runKey}
                cx={(i / (trend.length - 1)) * width}
                cy={height - (point.passRate / 100) * height}
                r={2}
                fill="var(--color-action-primary)"
                vectorEffect="non-scaling-stroke"
              />
            ))}
          </Box>
          <Stack direction="row" sx={{ justifyContent: 'space-between', mt: 0.5 }}>
            <Identifier variant="muted">{trend[0].runKey}</Identifier>
            <Identifier variant="muted">{latest.runKey}</Identifier>
          </Stack>
        </Box>

        <Box sx={{ flexShrink: 0 }}>
          <Typography className="identifier" sx={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: 1 }}>
            {formatPercent(latest.passRate)}
          </Typography>
          <Typography
            variant="caption"
            sx={{ color: delta >= 0 ? 'var(--color-passed)' : 'var(--color-failed)' }}
          >
            {delta >= 0 ? '+' : ''}
            {formatPercent(delta)} vs {previous.runKey}
          </Typography>
        </Box>
      </Stack>
    </Panel>
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
    <Panel
      title="What's blocking this plan"
      testId="PlanStats.Blockers"
      actions={
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {formatCount(blockers.length)} failed or blocked
        </Typography>
      }
    >
      {blockers.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Nothing failed or blocked. Anything unmet in the exit criteria above is about coverage,
          not defects.
        </Typography>
      ) : (
        <ScrollX>
          <Table size="small" data-testid="PlanStats.Blockers.Grid">
            <TableHead>
              <TableRow>
                <TableCell sx={{ color: 'text.secondary' }}>Case</TableCell>
                <TableCell sx={{ color: 'text.secondary', width: 110 }}>Result</TableCell>
                <TableCell sx={{ color: 'text.secondary', width: 130 }}>Defects</TableCell>
                <TableCell sx={{ color: 'text.secondary', width: 150 }}>Executed</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {blockers.map((blocker) => (
                <TableRow key={blocker.id} data-testid="PlanStats.Blockers.Row">
                  <TableCell>
                    <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                      <RouterLink
                        to={`/projects/${projectId}/cases/${blocker.caseId}`}
                        data-testid="PlanStats.Blockers.Row.CaseLink"
                      >
                        <Identifier>{blocker.caseKey}</Identifier>
                      </RouterLink>
                      <Tooltip title={blocker.caseTitle}>
                        <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {blocker.caseTitle}
                        </Box>
                      </Tooltip>
                      {/* The version that failed, not the current one — a case edited since
                          would otherwise silently rewrite what this row means. */}
                      <Tooltip title="The case version this run executed">
                        <Box>
                          <Identifier variant="muted">v{blocker.caseVersionNo}</Identifier>
                        </Box>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                  <TableCell>
                    {blocker.status?.kind && <StatusChip kind={blocker.status.kind} />}
                  </TableCell>
                  <TableCell>
                    {blocker.defects.length === 0 ? (
                      <Typography variant="caption" sx={{ color: 'var(--color-blocked)' }}>
                        No defect linked
                      </Typography>
                    ) : (
                      <Stack sx={{ gap: 0.25 }}>
                        {blocker.defects.map((defect) => (
                          <Box
                            key={defect.id}
                            component="a"
                            href={defect.url}
                            target="_blank"
                            rel="noopener"
                            data-testid="PlanStats.Blockers.Row.DefectLink"
                            sx={{ fontSize: '0.75rem' }}
                          >
                            <Identifier>{defect.externalKey}</Identifier>
                            {defect.statusCache && (
                              <Box component="span" sx={{ color: 'text.secondary', ml: 0.5 }}>
                                {defect.statusCache}
                              </Box>
                            )}
                            <Box component="span" sx={{ ml: 0.5, color: 'text.secondary' }} aria-hidden="true">
                              ↗
                            </Box>
                            <Box component="span" className="sr-only">
                              (opens in a new tab)
                            </Box>
                          </Box>
                        ))}
                      </Stack>
                    )}
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>
                    {formatDateTime(blocker.executedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      )}
    </Panel>
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
    <Panel title="Still to execute" testId="PlanStats.NotRun">
      <Stack sx={{ gap: 0.75 }}>
        {cases.map((testCase) => (
          <Stack
            key={testCase.id}
            direction="row"
            sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}
            data-testid="PlanStats.NotRun.Row"
          >
            <RouterLink to={`/projects/${projectId}/cases/${testCase.id}`} data-testid="PlanStats.NotRun.Row.CaseLink">
              <Identifier>{testCase.key}</Identifier>
            </RouterLink>
            <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '0.8125rem' }}>
              {testCase.title}
            </Box>
            <Typography variant="caption" sx={{ color: 'text.secondary', ml: 'auto', flexShrink: 0 }}>
              {testCase.suiteName}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Panel>
  );
}
