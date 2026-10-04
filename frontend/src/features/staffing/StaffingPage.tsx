import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useStaffingCapacity } from '../../api/queries';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Segmented } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';
import { AllocationTimeline } from './AllocationTimeline';
import { FreeCapacityPanel } from './FreeCapacityPanel';
import { OverAllocatedPanel } from './OverAllocatedPanel';
import { UtilisationPanel } from './UtilisationPanel';

/**
 * `staffing.html` — the company's whole allocation over a window.
 *
 * <h2>The gate is `staffing:directory`, and the mockup's own role badge is wrong</h2>
 *
 * `staffing.html` carries `data-role="Project Manager"`. The roles spec does not give a Project
 * Manager `staffing:directory` — their staffing keys are the PROJECT-scope pair (017 D-g, plan
 * correction 3) — so this screen refuses them, and the refusal **names the screen they can use**:
 * a project's own Staffing, which is T5's and answers their actual question, *who is on this
 * project*. It is the only refusal in this product that can point at a page the reader wants.
 *
 * The rail entry is shown to everybody regardless, per the Company group's standing rule (§31,
 * disable-don't-hide): a hidden entry tells a lie about the product, and the two audit entries are
 * the sole recorded exception.
 *
 * <h2>The window is the server's, not this screen's</h2>
 *
 * `from`/`to` are held in the URL and passed through **empty** when nobody has touched the picker,
 * so `GET /staffing/capacity` applies its own default — the first of this month, three months on —
 * and the subtitle, the scale and every bar are drawn from the `from`/`to` the *answer* carries.
 * Computing "next three months" here as well would give the product two spellings of one range and
 * let the heading disagree with the timeline beneath it.
 *
 * <h2>The over-allocated chip is read, never re-filtered</h2>
 *
 * `view.overAllocated` is served as its own subset for exactly this reason: the chip's count, the
 * row badges and the breakdown panel are three renderings of one list, and a client that filtered
 * `view.people` for the chip would have built a second rule that can disagree with the first.
 *
 * <h2>`+ Allocate` is disabled here, and that is the task boundary rather than an omission</h2>
 *
 * Writing an allocation is `staffing:allocate` on a **project**, and this screen names no project.
 * The button is therefore rendered disabled with the reason on it rather than hidden (§31) or wired
 * to a dialog that would have to ask which project first and then refuse most answers.
 */

const TEST_ID = 'Staffing';

export function StaffingPage() {
  const [params, setParams] = useSearchParams();
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';

  const gate = useCanInCompany();
  const allowed = gate.can('staffing:directory');

  const view = useStaffingCapacity(from, to);

  const setRange = (next: Range) => {
    setParams((current) => {
      const updated = new URLSearchParams(current);
      if (next === 'default') {
        updated.delete('from');
        updated.delete('to');
      } else {
        const { from: start, to: end } = boundsFor(next);
        updated.set('from', start);
        updated.set('to', end);
      }
      return updated;
    });
  };

  const window = view.data ? { from: view.data.from, to: view.data.to } : null;

  return (
    <div>
      {/*
        ~~`Staffing`~~ → **`Capacity`** (021 §8.1), the other half of the duplicate-label repair.
        The project screen is `Team` — who is on it — and this is the company question: who is
        free next month, across every project a person is on. The route stays `/staffing`.
      */}
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Capacity"
        documentTitle="Company capacity"
        subtitle={
          window ?
            `Company view · ${formatDate(window.from)} – ${formatDate(window.to)}`
          : 'Who is allocated where, who is over-booked, and who has room.'
        }
        views={
          allowed ?
            <Segmented<Range>
              label="Range"
              testId={`${TEST_ID}.Range`}
              value={rangeOf(from, to)}
              onChange={setRange}
              options={[
                { value: 'default', label: 'Next 3 months' },
                { value: 'month', label: 'This month' },
                { value: '6m', label: 'Next 6 months' },
                { value: '12m', label: 'Next 12 months' },
              ]}
            />
          : null
        }
        actions={
          allowed ?
            <>
              {/*
                Disabled rather than absent, and the title says which screen has the write. See the
                file header: `staffing:allocate` is PROJECT scope and this page names no project.
              */}
              <Button
                size="sm"
                variant="primary"
                disabled
                title="Allocate from the project you are staffing"
                data-testid={`${TEST_ID}.Allocate`}
              >
                + Allocate
              </Button>
            </>
          : null
        }
      />

      {!allowed ?
        <Panel testId={`${TEST_ID}.Denied`}>
          <p
            className="m-0 max-w-[74ch] text-[13px] leading-[1.6] text-ink-3"
            data-testid={`${TEST_ID}.DeniedText`}
          >
            {gate.pending ?
              'Checking your permissions…'
            : <>
                Staffing across the company is for Account Managers and Finance. To see who is on a
                project you run, open that project’s{' '}
                <RouterLink className="underline" to="/projects" data-testid={`${TEST_ID}.ToProjects`}>
                  Staffing
                </RouterLink>
                .
              </>
            }
          </p>
        </Panel>
      : <>
          {/*
            The chip strip sits ABOVE the region, so a failed load does not unmount the range
            picker and the count the reader would use to decide what to do next.
          */}
          <div className="mb-3 flex flex-wrap items-center gap-2 text-[12.5px]">
            <span className="text-ink-4">
              {view.data ? plural(view.data.people.length, 'person') : 'Everybody in the company'}
            </span>
            {view.data && (
              <Badge
                tone={view.data.overAllocated.length > 0 ? 'failed' : 'plain'}
                data-testid={`${TEST_ID}.OverChip`}
              >
                {view.data.overAllocated.length === 0 ?
                  'none over-allocated'
                : `${view.data.overAllocated.length} over-allocated`}
              </Badge>
            )}
          </div>

          <Panel
            testId={`${TEST_ID}.Allocation`}
            title="Allocation"
            dense
            actions={
              <span className="text-[11.5px] text-ink-4">percentage of capacity, per person</span>
            }
          >
            <DataRegion
              testId={`${TEST_ID}.Region`}
              state={{ ...view, refetch: () => void view.refetch() }}
              isEmpty={(data) => data.people.length === 0}
              empty={{
                kind: 'no-records',
                title: 'Nobody is allocated in this range',
                body:
                  window ?
                    `No allocation covers ${formatDate(window.from)} – ${formatDate(window.to)}. Allocate somebody from a project’s own Staffing screen, or widen the range.`
                  : 'No allocation covers this range.',
              }}
              skeleton={<GridSkeleton rows={5} />}
            >
              {(loaded) => (
                <AllocationTimeline
                  window={{ from: loaded.from, to: loaded.to }}
                  people={loaded.people}
                  testId={`${TEST_ID}.Timeline`}
                />
              )}
            </DataRegion>

            <div className="border-t border-line px-3 py-2 text-[11.5px] text-ink-3">
              Absence sets capacity to <strong className="font-semibold">zero</strong> for those
              days — everywhere, not only here, so a plan cannot quietly assume someone who is away.
            </div>
          </Panel>

          {view.data && (
            <div className="mt-4 grid gap-4 lg:grid-cols-3">
              <OverAllocatedPanel
                people={view.data.overAllocated}
                testId={`${TEST_ID}.OverAllocated`}
              />
              <FreeCapacityPanel
                people={view.data.people}
                windowFrom={view.data.from}
                testId={`${TEST_ID}.Free`}
              />
              <UtilisationPanel people={view.data.people} testId={`${TEST_ID}.Utilisation`} />
            </div>
          )}
        </>
      }
    </div>
  );
}

/**
 * The four ranges the picker offers, as a URL-derived value rather than component state.
 *
 * `'default'` is the absence of both parameters, which is what makes the server's own default the
 * screen's default — see the file header. The other three write explicit bounds, so a link somebody
 * shares shows the range they were looking at rather than the range the recipient's clock produces.
 */
type Range = 'default' | 'month' | '6m' | '12m';

function rangeOf(from: string, to: string): Range {
  if (from === '' || to === '') return 'default';
  for (const candidate of ['month', '6m', '12m'] as const) {
    const bounds = boundsFor(candidate);
    if (bounds.from === from && bounds.to === to) return candidate;
  }
  /*
    A window nobody's option produces — an old link, or a range a future picker offered. It reads as
    `default` in the select and changes nothing: the URL still carries the bounds, the request still
    sends them, and the heading still names what came back. Forcing the select onto a value that
    would rewrite the URL is how a shared link silently becomes a different question.
  */
  return 'default';
}

function boundsFor(range: Exclude<Range, 'default'>): { from: string; to: string } {
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const months = range === 'month' ? 1 : range === '6m' ? 6 : 12;
  // The last day of the final month, not the first of the one after: the window's `to` is
  // inclusive everywhere in this module, and an off-by-one here is a whole extra month of scale.
  const end = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + months, 0));
  return { from: iso(start), to: iso(end) };
}

const iso = (at: Date) => at.toISOString().slice(0, 10);
