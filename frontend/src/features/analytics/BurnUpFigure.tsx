import { FigurePanel } from '../../components/kit/FigurePanel';
import type { BurnUpReport } from '../../api/types';
import { InsufficientBlock } from './InsufficientBlock';
import { INNER_HEIGHT, PLOT, areaPath, axisDate, linePath, scale, ticks } from './plot';
import { VIZ_GRID, VIZ_SOFT, VIZ_STRONG } from './vizTokens';

/**
 * Done against total scope over time, and the step where scope arrived after the release existed.
 *
 * <h2>An area inside a line, not two lines of two hues</h2>
 *
 * `reports.html` draws two hue-coded polylines sharing an axis. This product has no categorical
 * palette to draw them with — see `vizTokens.ts`, which records the measurement — so the encoding
 * is **containment**: done is a filled area, total scope is the line that bounds it, and the gap
 * between them is the work left. A reader who cannot separate the two hues still reads the gap,
 * because one shape is inside the other.
 *
 * That is not a compromise. The two series are not categories, they are a part and its whole, and
 * containment says so where two lines only imply it.
 *
 * <h2>The step is the point</h2>
 *
 * A burn-up whose denominator grows silently reports a team going backwards for no visible reason.
 * The bounding line steps on the week the scope arrived, and the third chip below says how much —
 * the one number in this figure that cannot be reconstructed after the fact (009 review R12).
 */
export function BurnUpFigure({ report }: { report: BurnUpReport }) {
  if (!report.sufficiency.sufficient) {
    return (
      <FigurePanel title="Release burn-up" subtitle={`${report.releaseVersion} · done vs total scope`}>
        <InsufficientBlock reason={report.sufficiency.reason} />
        <Foot />
      </FigurePanel>
    );
  }

  const done = report.points.map((point) => point.done);
  const total = report.points.map((point) => point.totalScope);
  const s = scale(total, report.points.length);
  const shown = ticks(report.points.length);

  return (
    <FigurePanel
      title="Release burn-up"
      subtitle={`${report.releaseVersion} · done vs total scope, weekly since it was created`}
    >
      <svg
        viewBox={`0 0 ${PLOT.width} ${PLOT.height}`}
        width="100%"
        height={PLOT.height}
        role="img"
        aria-label={describe(report)}
        data-testid="burn-up-figure"
      >
        {/* Recessive grid, and a zero baseline the area sits on rather than floats above. */}
        {[0, 0.5, 1].map((at) => (
          <line
            key={at}
            x1={PLOT.left}
            x2={PLOT.width - PLOT.right}
            y1={PLOT.top + INNER_HEIGHT * at}
            y2={PLOT.top + INNER_HEIGHT * at}
            stroke={VIZ_GRID}
            strokeWidth={1}
          />
        ))}
        <g fontSize={10} fontFamily="var(--font-mono)" fill="var(--color-ink-4)">
          <text x={4} y={PLOT.top + 4}>{s.max}</text>
          <text x={4} y={PLOT.top + INNER_HEIGHT + 4}>0</text>
          {shown.map((index) => (
            <text
              key={index}
              x={s.x(index)}
              y={PLOT.height - 6}
              textAnchor={index === 0 ? 'start' : index === report.points.length - 1 ? 'end' : 'middle'}
            >
              {axisDate(report.points[index]?.date ?? '')}
            </text>
          ))}
        </g>

        <path d={areaPath(done, s)} fill={VIZ_STRONG} opacity={0.3} />
        <path d={linePath(done, s)} fill="none" stroke={VIZ_STRONG} strokeWidth={2.5} />
        {/*
          Dashed, which is secondary encoding rather than decoration: the two marks are one hue at
          two steps, so the dash is what tells them apart in a forced-colours or greyscale render.
        */}
        <path
          d={linePath(total, s)}
          fill="none"
          stroke={VIZ_SOFT}
          strokeWidth={2}
          strokeDasharray="4 3"
        />
      </svg>

      {/*
        The legend, and it carries the numbers — two series always get one, and four or fewer are
        direct-labelled too, so identity is never colour alone.
      */}
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[11.5px] text-ink-3">
        <Key swatch={<span className="block h-[2.5px] w-3 rounded-full" style={{ background: VIZ_STRONG }} />}>
          Done · <span className="font-mono tabular-nums text-ink-2">{report.doneTotal}</span>
        </Key>
        <Key
          swatch={
            <span
              className="block h-[2px] w-3"
              style={{ backgroundImage: `repeating-linear-gradient(90deg, ${VIZ_SOFT} 0 4px, transparent 4px 7px)` }}
            />
          }
        >
          Total scope · <span className="font-mono tabular-nums text-ink-2">{report.countedTotal}</span>
        </Key>
        {report.scopeAddedAfterCreation > 0 && (
          <span data-testid="burn-up-scope-added">
            +<span className="font-mono tabular-nums text-ink-2">{report.scopeAddedAfterCreation}</span>{' '}
            added after the release was created
          </span>
        )}
      </div>
      <Foot />
    </FigurePanel>
  );
}

function Key({ swatch, children }: { swatch: React.ReactNode; children: React.ReactNode }) {
  return (
    <span className="flex items-center gap-1.5">
      {swatch}
      {children}
    </span>
  );
}

/**
 * The panel's own sentence, and it is the mockup's.
 *
 * Rendered on the insufficient branch too: the reason the step matters does not stop being true
 * because this particular release has no step to show.
 */
function Foot() {
  return (
    <p className="mt-2.5 border-t border-line pt-2 text-[11.5px] leading-[1.5] text-ink-4">
      Scope added after the release was created is the step in the dashed line — visible, not
      absorbed into the denominator silently.
    </p>
  );
}

/**
 * The alt text, built from the report so it cannot go stale.
 *
 * A sentence and not a list of numbers, which is what the mockup's own `aria-label` is: a screen
 * reader gets the shape of the trend, and the exact figures are in the legend underneath as text.
 */
function describe(report: BurnUpReport): string {
  const first = report.points[0];
  const last = report.points[report.points.length - 1];
  const added =
    report.scopeAddedAfterCreation > 0
      ? `, ${report.scopeAddedAfterCreation} of it assigned after the release was created`
      : '';
  return (
    `Burn-up for ${report.releaseVersion}: done rising from ${first?.done ?? 0} to `
    + `${last?.done ?? 0} of a total scope that went from ${first?.totalScope ?? 0} to `
    + `${last?.totalScope ?? 0} issues${added}.`
  );
}
