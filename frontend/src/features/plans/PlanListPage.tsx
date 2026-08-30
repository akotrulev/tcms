import { Archive, ArchiveRestore } from 'lucide-react';
import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import type { PlanStatus, TestPlan } from '../../api/types';
import { usePlans, useProject, useSetPlanArchived } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type BadgeTone,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { CriteriaStrip } from '../../components/CriteriaStrip';
import { Button, Switch } from '../../components/ui';
import { EM_DASH, formatCount, formatPercent } from '../../lib/format';
import { planStatusLabels } from '../../lib/labels';
import { PlanCreateDialog } from './PlanCreateDialog';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';

/**
 * Test plans. Deliberately a compact table rather than a card grid: a lead comparing four
 * plans wants the pass rate and coverage columns to line up so the eye can scan down them,
 * which cards make impossible.
 *
 * Both of those columns are an em dash on every row today: the server sets `passRate` and
 * `coverage` to null unconditionally (`TestPlanView`), so the comparison this shape exists
 * for is not yet one the screen can offer. The columns stay because the table is built for
 * the day they are served, and `openapi.yaml` now declares both as a percentage 0-100 so
 * that day does not arrive with the unit in doubt.
 */
export function PlanListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  /*
    In the URL, not in a `useState` — this repo's rule for filter state, and it earns it here: a
    lead who has ticked "show archived" to find last spring's plan sends somebody the link, and a
    component-state toggle sends them a list the plan is not in.
  */
  const [params, setParams] = useSearchParams();
  const showArchived = params.get('archived') === 'true';
  const plans = usePlans(projectId, showArchived);
  useAnnouncedResults(
    plans,
    `${formatCount(plans.data?.length ?? 0)} ${(plans.data?.length ?? 0) === 1 ? 'plan' : 'plans'} shown`,
  );
  const [creating, setCreating] = useState(false);

  return (
    <div>
      <PageHeader
        testId="Plans.Header"
        title="Test plans"
        documentTitle={project.data ? `Test plans · ${project.data.name}` : 'Test plans'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test plans' },
        ]}
        subtitle="A plan fixes the scope, configurations and exit criteria for a release. Runs execute against it."
        actions={
          <div className="flex items-center gap-4">
            {/*
              Ungated: showing archived plans discloses nothing a plan list does not already, and
              somebody who cannot archive still has to be able to FIND the plan somebody else did.
            */}
            <label className="flex items-center gap-2 text-[13px] text-ink-3">
              <Switch
                checked={showArchived}
                onCheckedChange={(checked) => {
                  const next = new URLSearchParams(params);
                  if (checked) next.set('archived', 'true');
                  else next.delete('archived');
                  setParams(next, { replace: true });
                }}
                data-testid="Plans.ShowArchived"
              />
              Show archived
            </label>
            {/*
              Disabled with a reason rather than hidden (§31): a Viewer seeing a greyed "New plan"
              learns the capability exists and is worth asking for. `plan:create` is PROJECT scope —
              unlike "New project", which is the app's one company-scope affordance — so this asks
              the project question and the gate matches the server's.
            */}
            <PermissionGate projectId={projectId} permission="plan:create">
              {({ allowed }) => (
                <Button
                  variant="primary"
                  disabled={!allowed}
                  onClick={() => setCreating(true)}
                  data-testid="Plans.New"
                >
                  New plan
                </Button>
              )}
            </PermissionGate>
          </div>
        }
      />

      {/* Conditionally rendered, so the form mounts fresh each time — see the dialog's own note. */}
      {creating && (
        <PlanCreateDialog
          projectId={projectId}
          open
          onClose={() => setCreating(false)}
          /*
            Stays on the list. ~~Because `/plans/:planId` is the plan STATISTICS route and that
            endpoint is deliberately unimplemented server-side (§38), so navigating there on
            success showed a red "Something didn't load" toast one second after the plan was
            created — the create worked and the app said it had not.~~ → superseded: the endpoint
            is served (`PlanStatisticsService`) and `PlanStatisticsPage` renders it, which is
            where this row's own link goes.

            The destination did not move, for a second reason that outlived the first: a plan
            created a second ago has been executed zero times, so its statistics screen is
            every empty state at once. The list answers the question the creator actually has
            — did it save, and what scope did it pick up — with the new plan as the first row.
          */
          onCreated={() => setCreating(false)}
        />
      )}

      <section data-testid="Plans.Panel">
        <DataRegion
          testId="Plans.List"
          state={{ ...plans, refetch: () => void plans.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={
            /*
              §7: empty says what its filters hid and offers their removal in place. There is one
              filter here — archived — and with it off an empty list is ambiguous between "this
              project has no plans" and "every plan it has is archived". No count is offered
              because none is knowable without a second query for a list nobody asked to see; the
              offer is, which is the half that unsticks the reader.
            */
            showArchived
              ? {
                  kind: 'no-records',
                  title: 'No test plans yet',
                  body: 'Create a plan to fix the scope and exit criteria for a release.',
                }
              : {
                  kind: 'no-filter-match',
                  title: 'No test plans yet',
                  body:
                    'Archived plans are hidden. Create a plan to fix the scope and exit criteria '
                    + 'for a release, or show the archived ones.',
                  onClearFilters: () => setParams({ archived: 'true' }, { replace: true }),
                }
          }
        >
          {(rows) => <PlanTable rows={rows} projectId={projectId} />}
        </DataRegion>
      </section>
    </div>
  );
}

/**
 * Put this plan away, or bring it back (`V65`, audit D2).
 *
 * **No confirmation, deliberately.** The other destructive-looking act on a plan — Delete — has a
 * dialog because it cannot be undone; this is undone by clicking the same control again, and a
 * confirmation on a reversible act teaches people to dismiss confirmations.
 *
 * Its own component so the mutation hook is per row rather than one hook the table threads a plan
 * id through — the hook keys its cache updates on the plan it was built for.
 *
 * ~~A MUI `Tooltip` around a `<span>`, because MUI drops the tooltip on a disabled button.~~ →
 * superseded by the T9 precedent: the reason is the native `title` attribute, which a disabled
 * control still shows and a screen reader still reaches, with no wrapper element in between.
 */
function ArchiveToggle({ projectId, plan }: { projectId: string; plan: TestPlan }) {
  const setArchived = useSetPlanArchived(projectId, plan.id);
  const archived = plan.archived;

  return (
    <PermissionGate projectId={projectId} permission="plan:edit">
      {({ allowed }) => (
        <Button
          variant="quiet"
          size="icon"
          disabled={!allowed || setArchived.isPending}
          onClick={() => setArchived.mutate(!archived)}
          aria-label={archived ? `Restore ${plan.key}` : `Archive ${plan.key}`}
          title={
            allowed
              ? archived
                ? 'Put this plan back in the working list'
                : 'Archive: take this plan out of the list. Nothing is deleted, and it can still be run.'
              : 'You do not have permission to edit test plans in this project'
          }
          data-testid="Plans.Row.ArchiveToggle"
        >
          {archived ? (
            <ArchiveRestore aria-hidden className="size-4" />
          ) : (
            <Archive aria-hidden className="size-4" />
          )}
        </Button>
      )}
    </PermissionGate>
  );
}

/** The ladder, in one palette with the results — design language rule 5. */
const PLAN_TONE = {
  DRAFT: 'plain',
  IN_REVIEW: 'retest',
  APPROVED: 'passed',
} as const satisfies Record<PlanStatus, BadgeTone>;

function PlanTable({ rows, projectId }: { rows: TestPlan[]; projectId: string }) {
  // Deterministic order with a unique tiebreaker: the plans still being written first, then by
  // milestone date, then by key — so the list never reshuffles between loads. §97 (M7) removed
  // ACTIVE, which used to lead: with three states, what a reader is looking for on this screen is
  // the draft they were editing, and an approved plan is finished work.
  const sorted = [...rows].sort((a, b) => {
    const rank = (plan: TestPlan) => (plan.status === 'DRAFT' ? 0 : plan.status === 'IN_REVIEW' ? 1 : 2);
    return (
      rank(a) - rank(b) ||
      (b.milestoneDate ?? '').localeCompare(a.milestoneDate ?? '') ||
      a.key.localeCompare(b.key)
    );
  });

  /*
    The render (`renders/01-index-tables.html` frame D) draws this banner, and the condition is what
    keeps it from becoming a permanent decoration: it appears only while EVERY row is null in both
    columns, which is the state the server is in today and the state the sentence describes. The
    day one plan carries a real pass rate, the banner is wrong and disappears on its own.
  */
  const nothingComputed = sorted.every((plan) => plan.passRate === null && plan.coverage === null);

  return (
    <>
      <Table data-testid="Plans.Grid">
        <TableHead>
          <TableRow>
            <TableHeaderCell>Plan</TableHeaderCell>
            <TableHeaderCell className="w-[110px]">Status</TableHeaderCell>
            <TableHeaderCell className="w-[170px]">Milestone</TableHeaderCell>
            <TableHeaderCell align="right" className="w-[80px]">
              Cases
            </TableHeaderCell>
            <TableHeaderCell align="right" className="w-[100px]">
              Pass rate
            </TableHeaderCell>
            <TableHeaderCell align="right" className="w-[100px]">
              Coverage
            </TableHeaderCell>
            {/*
              Left-aligned and wider since §100: the cell is a strip of marks read left-to-right
              followed by its count, not a number. Right-aligning it would put the marks against
              the table's edge and the count in the middle, which reads as two columns.
            */}
            <TableHeaderCell className="w-[210px]">Exit criteria</TableHeaderCell>
            <TableHeaderCell align="right" className="w-[56px]" />
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((plan) => (
            <TableRow key={plan.id} data-testid="Plans.Row">
              <TableCell
                variant="title"
                subtitle={
                  <>
                    <RouterLink
                      to={`/projects/${projectId}/plans/${plan.id}`}
                      className="text-inherit hover:text-ink"
                      data-testid="Plans.Row.Link"
                    >
                      <Identifier variant="muted">{plan.key}</Identifier>
                    </RouterLink>
                    {/*
                      Beside the name rather than in the status column: archiving is orthogonal to
                      the ladder (`V65`), and putting it in that cell would read as a fourth status
                      — the exact conflation the column was kept out of the ladder to avoid.
                    */}
                    {plan.archived && (
                      <>
                        {' · '}
                        <span data-testid="Plans.Row.Archived">Archived</span>
                      </>
                    )}
                  </>
                }
              >
                <RouterLink
                  to={`/projects/${projectId}/plans/${plan.id}`}
                  className="font-medium text-inherit hover:text-steel"
                >
                  <span data-testid="Plans.Row.Name">{plan.name}</span>
                </RouterLink>
              </TableCell>
              <TableCell>
                <Badge tone={PLAN_TONE[plan.status]} data-testid="Plans.Row.Status">
                  {planStatusLabels[plan.status]}
                </Badge>
              </TableCell>
              <TableCell className="text-[12px]">
                {plan.milestoneName ? (
                  <>
                    {plan.milestoneName}
                    {plan.milestoneDate && (
                      <span className="block text-ink-4">
                        <Identifier variant="muted">{plan.milestoneDate}</Identifier>
                      </span>
                    )}
                  </>
                ) : (
                  <span className="text-ink-4">{EM_DASH}</span>
                )}
              </TableCell>
              <TableCell align="right">
                <Identifier>{formatCount(plan.caseCount)}</Identifier>
              </TableCell>
              {/*
                Both are nullable and the two nulls are different claims (005 S5), which is why
                each carries its own title rather than sharing one em dash with no explanation:

                  - `passRate === null` — nothing in scope has a result. A plan nobody has run has
                    no pass rate, and `0` would say every case failed.
                  - `coverage === null` — the scope is empty, so the denominator is zero.

                `formatPercent` renders both as an em dash, which is right for the cell; the
                title is what stops the em dash reading as "we could not load this".
              */}
              <TableCell align="right">
                <span title={plan.passRate === null ? 'Nothing in this plan has been run yet.' : undefined}>
                  <Identifier>{formatPercent(plan.passRate)}</Identifier>
                </span>
              </TableCell>
              <TableCell align="right">
                <span title={plan.coverage === null ? 'This plan’s scope is empty.' : undefined}>
                  <Identifier>{formatPercent(plan.coverage)}</Identifier>
                </span>
              </TableCell>
              <TableCell>
                {/*
                  The counts come from the summary (005 S5) and the marks from the criteria: the
                  server evaluates the gates in one batched pass over the page, but does not say
                  per criterion which one passed — so the sentence is authoritative and the marks
                  stay honestly grey.
                */}
                <CriteriaStrip
                  criteria={plan.exitCriteria ?? []}
                  summary={{ met: plan.exitCriteriaMet, total: plan.exitCriteriaTotal }}
                  testId="Plans.Row.ExitCriteria"
                />
              </TableCell>
              <TableCell align="right">
                <ArchiveToggle projectId={projectId} plan={plan} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      {nothingComputed && (
        <Banner tone="warn" className="mt-3.5" data-testid="Plans.NotComputed">
          <span>
            <b>Pass rate and Coverage are an em dash on every row.</b> The server does not compute
            them for a plan yet — the columns stay because the table is built for the scan they
            enable, and the dash says <em>not computed</em> rather than <em>zero</em>.
          </span>
        </Banner>
      )}
    </>
  );
}
