import { HBar } from '../../components/kit/Figure';
import { FigurePanel } from '../../components/kit/FigurePanel';
import type { CycleTimeReport } from '../../api/types';
import { cycleTimeMeasuredFromLabels } from '../../lib/labels';
import { InsufficientBlock } from './InsufficientBlock';

/**
 * How long issues take to finish, one row per issue type.
 *
 * <h2>Four rows, not four coloured bars on one axis</h2>
 *
 * This is the facet the kit prescribes — `Figure.tsx`'s *"a three-series chart facets into three
 * single-series ones"* — and it is also what the data is: four types are four populations, not four
 * slices of one. Each row is a single series, so no categorical hue is needed and `HBar`'s own
 * single-series treatment is exactly right.
 *
 * <h2>The subtitle is the correction the mockup needs</h2>
 *
 * `reports.html` reads *"started → done"*. This product records no moment at which work started —
 * `issue_transition` is the workflow allow-list, not a history — so what is measured is
 * creation to done, and `measuredFrom` says so on every response (020 D-c). `FigurePanel`'s
 * subtitle is where every figure in this product states what its number is *of*; this one states
 * its bounds, because a label claiming a different number than the one underneath it is the failure
 * `CLAUDE.md` records as two contradictory percentages on one screen.
 *
 * <h2>Thin types are shown with their counts, not hidden</h2>
 *
 * A band of three issues is served (020 D-e) and its count is on the row. The reader decides
 * whether three is enough; what they are not handed is a median presented as if it settled the
 * question — which is what `sufficiency` says when the whole window is thin.
 */
/**
 * The panel's own sentence — the mockup's, plus the bound this product actually measures.
 *
 * **A constant and not bare JSX text**, which is a decision `noVelocity.test.ts` records at length:
 * that rule strips comments and quoted strings so the copy explaining the absence is not what fails
 * it, and JSX text is neither. Copy in a named constant is where copy belongs the moment a rule has
 * to tell it from code.
 */
const FOOT =
  'Measured from when an issue was created — nothing here records when work started. '
  + 'No velocity chart: there are no sprints to measure it over, and inventing one would report a '
  + 'number nobody planned against.';

export function CycleTimeFigure({ report }: { report: CycleTimeReport }) {
  const subtitle =
    `${cycleTimeMeasuredFromLabels[report.measuredFrom].toLowerCase()} → done, by type, `
    + `last ${report.windowDays} days`;

  const longest = Math.max(1, ...report.bands.map((band) => band.medianDays));

  return (
    <FigurePanel title="Cycle time" subtitle={subtitle}>
      {!report.sufficiency.sufficient && (
        <div className="mb-3">
          <InsufficientBlock reason={report.sufficiency.reason} />
        </div>
      )}

      {report.bands.length > 0 && (
        <div className="flex flex-col gap-2" data-testid="cycle-time-figure">
          {report.bands.map((band) => (
            <HBar
              key={band.typeName}
              label={band.typeName}
              fraction={band.medianDays / longest}
              // The count rides on the value, not in a tooltip: it is the denominator of the
              // median beside it, and a median whose population a reader has to hover for is one
              // two readers will read differently.
              value={`${band.medianDays.toFixed(1)} d · ${band.p90Days.toFixed(0)} d p90 · n=${band.count}`}
              data-testid={`cycle-time-${band.typeName}`}
            />
          ))}
        </div>
      )}

      <p className="mt-2.5 border-t border-line pt-2 text-[11.5px] leading-[1.5] text-ink-4">
        {FOOT}
      </p>
    </FigurePanel>
  );
}
