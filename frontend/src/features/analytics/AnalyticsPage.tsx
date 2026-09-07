import { useMemo } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import {
  CYCLE_TIME_WINDOWS,
  DEFAULT_CYCLE_TIME_DAYS,
  DEFAULT_FLOW_WEEKS,
  FLOW_WINDOWS,
  useBurnUp,
  useCycleTime,
  useFlow,
  useReleases,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { FigurePanel } from '../../components/kit/FigurePanel';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu';
import { Button } from '../../components/ui/button';
import { BurnUpFigure } from './BurnUpFigure';
import { CycleTimeFigure } from './CycleTimeFigure';
import { EstimateAccuracyPanel } from './EstimateAccuracyPanel';
import { FlowFigure } from './FlowFigure';
import { burnUpCsv, cycleTimeCsv, download, flowCsv } from './exportCsv';

const TEST_ID = 'AnalyticsPage';

/**
 * Delivery analytics — *"are we converging on 2.4?"* — `reports.html` (020, module M9).
 *
 * <h2>Four panels, three of them served by `reporting` and one composed here</h2>
 *
 * The burn-up, cycle time and the flow are three synchronous reads over `issue_fact`. Estimate
 * accuracy is `time`'s own epic rollup, assembled on this screen because both halves of it already
 * belong to that module (020 D-i).
 *
 * <h2>Every selection lives in the URL</h2>
 *
 * The release, the cycle-time window and the flow window are all `useSearchParams`, per the app's
 * rule that filter state is a URL concern — so a burn-up somebody is looking at is a link they can
 * paste, and a reload returns the same figures rather than the defaults.
 *
 * <h2>What is deliberately not on this screen</h2>
 *
 * **Utilisation.** It landed with M7 as `approved hours ÷ allocated capacity` (017 D-i), is served
 * on `PersonCapacity.utilisationPercent`, and is rendered on `/staffing` with that denominator
 * printed beside it. A second utilisation here — even one computed identically — is the
 * two-contradictory-percentages failure waiting for its first divergent edit.
 *
 * **Velocity.** There are no sprints in this product (scope D6), so there is nothing to measure it
 * over. `noVelocity.test.ts` asserts the absence across the SPA and the shared packages;
 * `NoVelocityAnywhereTest` does the same for the backend and the contract.
 */
export function AnalyticsPage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  const releases = useReleases(projectId);

  const days = windowFrom(params.get('days'), CYCLE_TIME_WINDOWS, DEFAULT_CYCLE_TIME_DAYS);
  const weeks = windowFrom(params.get('weeks'), FLOW_WINDOWS, DEFAULT_FLOW_WEEKS);

  /*
    The release from the URL, or the newest one the project has — and "newest" is `releases`' own
    ordering, which is the roadmap's semver ordering rather than anything computed here. A default
    is safe where a silent default would not be, because the picker beside it always names which
    release is on screen.
  */
  const chosen = params.get('release');
  const releaseId = useMemo(() => {
    const list = releases.data ?? [];
    if (chosen !== null && list.some((release) => release.id === chosen)) return chosen;
    return list[0]?.id ?? null;
  }, [chosen, releases.data]);

  const burnUp = useBurnUp(projectId, releaseId);
  const cycleTime = useCycleTime(projectId, days);
  const flow = useFlow(projectId, weeks);

  const setParam = (name: string, value: string) => {
    const next = new URLSearchParams(params);
    next.set(name, value);
    setParams(next, { replace: true });
  };

  return (
    <>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Delivery analytics"
        documentTitle="Delivery analytics"
        subtitle="Whether this project is converging on its next release."
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" data-testid={`${TEST_ID}.Export`}>
                Export
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                disabled={burnUp.data === undefined}
                onSelect={() =>
                  burnUp.data && download('burn-up.csv', burnUpCsv(burnUp.data))
                }
              >
                Burn-up (CSV)
              </DropdownMenuItem>
              <DropdownMenuItem
                disabled={cycleTime.data === undefined}
                onSelect={() =>
                  cycleTime.data && download('cycle-time.csv', cycleTimeCsv(cycleTime.data))
                }
              >
                Cycle time (CSV)
              </DropdownMenuItem>
              <DropdownMenuItem
                disabled={flow.data === undefined}
                onSelect={() => flow.data && download('flow.csv', flowCsv(flow.data))}
              >
                Flow (CSV)
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />

      <div className="grid gap-3.5 lg:grid-cols-2">
        {releaseId === null ?
          <FigurePanel title="Release burn-up" subtitle="no release to chart yet">
            <p className="py-3 text-[12.5px] text-ink-4">
              This project has no releases. Create one and target issues at it, and the burn-up
              becomes the answer to “are we converging?”.
            </p>
          </FigurePanel>
        : <div className="min-w-0">
            <ReleasePicker
              releases={releases.data ?? []}
              value={releaseId}
              onChange={(id) => setParam('release', id)}
            />
            <DataRegion testId={`${TEST_ID}.BurnUp`} state={burnUp}>
              {(report) => <BurnUpFigure report={report} />}
            </DataRegion>
          </div>
        }

        <div className="min-w-0">
          <WindowPicker
            label="Window"
            options={CYCLE_TIME_WINDOWS}
            unit="days"
            value={days}
            onChange={(value) => setParam('days', String(value))}
          />
          <DataRegion testId={`${TEST_ID}.CycleTime`} state={cycleTime}>
            {(report) => <CycleTimeFigure report={report} />}
          </DataRegion>
        </div>

        <div className="min-w-0">
          <WindowPicker
            label="Window"
            options={FLOW_WINDOWS}
            unit="weeks"
            value={weeks}
            onChange={(value) => setParam('weeks', String(value))}
          />
          <DataRegion testId={`${TEST_ID}.Flow`} state={flow}>
            {(report) => <FlowFigure report={report} />}
          </DataRegion>
        </div>

        <EstimateAccuracyPanel projectId={projectId} />
      </div>
    </>
  );
}

/**
 * The release the burn-up is about.
 *
 * Above the figure rather than in the page header, because it changes one panel and not the screen
 * — a control in the header would claim it changed all four.
 */
function ReleasePicker({
  releases,
  value,
  onChange,
}: {
  releases: readonly { id: string; version: string }[];
  value: string;
  onChange: (id: string) => void;
}) {
  return (
    <div className="mb-1.5 flex items-center gap-2 text-[11.5px] text-ink-4">
      <label htmlFor="analytics-release">Release</label>
      <select
        id="analytics-release"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        data-testid={`${TEST_ID}.ReleasePicker`}
        className="rounded-[5px] border border-line bg-field px-1.5 py-0.5 text-[11.5px] text-ink-2"
      >
        {releases.map((release) => (
          <option key={release.id} value={release.id}>
            {release.version}
          </option>
        ))}
      </select>
    </div>
  );
}

/** One window selector, for the two figures that have one. Radio-shaped, not a menu — three values. */
function WindowPicker({
  label,
  options,
  unit,
  value,
  onChange,
}: {
  label: string;
  options: readonly number[];
  unit: string;
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <div className="mb-1.5 flex items-center gap-2 text-[11.5px] text-ink-4">
      <span>{label}</span>
      <span className="flex items-center gap-1">
        {options.map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => onChange(option)}
            aria-pressed={option === value}
            className={
              option === value ?
                'rounded-[5px] border border-steel-line bg-field px-1.5 py-0.5 font-mono tabular-nums text-ink-2'
              : 'rounded-[5px] border border-transparent px-1.5 py-0.5 font-mono tabular-nums hover:text-ink-3'
            }
          >
            {option}
          </button>
        ))}
        <span>{unit}</span>
      </span>
    </div>
  );
}

/**
 * A window from the URL, or the default.
 *
 * A value the contract does not offer falls back rather than being sent — the server answers 400
 * for it, and a hand-edited query string should not put a red banner on a screen where the honest
 * response is to show the default window and let the picker say which one is on.
 */
function windowFrom(raw: string | null, offered: readonly number[], fallback: number): number {
  const parsed = Number(raw);
  return offered.includes(parsed) ? parsed : fallback;
}
