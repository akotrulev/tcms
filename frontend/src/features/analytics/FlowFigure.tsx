import { FigurePanel } from '../../components/kit/FigurePanel';
import type { CumulativeFlowPoint, CumulativeFlowReport } from '../../api/types';
import { flowBandLabels, type FlowBandKey } from '../../lib/labels';
import { InsufficientBlock } from './InsufficientBlock';
import { PLOT, areaPath, axisDate, scale, ticks } from './plot';
import { VIZ_ABSENT, VIZ_GRID, VIZ_STRONG } from './vizTokens';

/**
 * Issues per status band, weekly — as four small multiples rather than one stack.
 *
 * <h2>Why it is faceted, measured rather than asserted</h2>
 *
 * `reports.html` draws a three-hue stacked area. The three tokens that would need were run through
 * the `dataviz` validator against this app's own surfaces, and the result is recorded in
 * `vizTokens.ts`: three steps of one hue cannot be both separable (ΔE ≥ 15) and legible (≥ 3:1) on
 * either surface, and three *hues* fail CVD separation outright — which is the measurement
 * `Figure.tsx`'s header already records and the reason this product has no categorical palette.
 *
 * Faceting loses the stack's shared total and keeps the reading the mockup's own alt text describes:
 * *"backlog shrinking, done growing, in-progress steady around five"*. Those are four trajectories,
 * and four small multiples say them without asking anyone to tell two blues apart.
 *
 * <h2>The fourth facet is not a status</h2>
 *
 * `beforeCurrentStatus` is the issues that existed on a date whose status *on that date* is recorded
 * nowhere — there is no status history in this product (020 D-d). It is drawn as a **hatch** in the
 * rule colour rather than as a fourth fill, because a fourth colour would say "a fourth status"
 * where the truth is "no information". It is zero at the right-hand edge by construction and grows
 * leftward; that shape is correct and is not a rendering fault.
 */
export function FlowFigure({ report }: { report: CumulativeFlowReport }) {
  const bands: FlowBandKey[] = ['notStarted', 'inFlight', 'done', 'beforeCurrentStatus'];

  return (
    <FigurePanel
      title="Flow"
      subtitle={`issues per status band, weekly, last ${report.windowWeeks} weeks`}
    >
      {!report.sufficiency.sufficient && (
        <div className="mb-3">
          <InsufficientBlock reason={report.sufficiency.reason} />
        </div>
      )}

      {report.sufficiency.sufficient && report.points.length > 0 && (
        <div className="grid grid-cols-2 gap-x-4 gap-y-3" data-testid="flow-figure">
          {bands.map((band) => (
            <Facet key={band} band={band} points={report.points} />
          ))}
        </div>
      )}
    </FigurePanel>
  );
}

/**
 * One band's trajectory, on its own axis.
 *
 * Each facet is scaled to its own maximum and prints it, which is the trade a small-multiple set
 * makes: the shapes are comparable and the heights are not, so the maximum is on the facet rather
 * than left to be inferred. A shared scale would flatten every band but the largest into a line at
 * the bottom, which is the reading this figure exists to give.
 */
function Facet({ band, points }: { band: FlowBandKey; points: readonly CumulativeFlowPoint[] }) {
  const values = points.map((point) => point[band]);
  const s = scale(values, points.length);
  const latest = values[values.length - 1] ?? 0;
  const absent = band === 'beforeCurrentStatus';
  const hatchId = `flow-hatch-${band}`;

  const height = 84;
  const box = { ...PLOT, height, top: 6, bottom: 14 };
  const inner = box.height - box.top - box.bottom;
  const local = {
    max: s.max,
    x: s.x,
    y: (value: number) => box.top + inner - (value / s.max) * inner,
  };
  const shown = ticks(points.length, 2);

  return (
    <figure className="min-w-0">
      <figcaption className="mb-1 flex items-baseline justify-between gap-2 text-[11.5px]">
        <span className={absent ? 'text-ink-4' : 'text-ink-2'}>{flowBandLabels[band]}</span>
        {/* The direct label — the latest value, in ink and never in the series colour. */}
        <span className="font-mono tabular-nums text-ink-3">{latest}</span>
      </figcaption>
      <svg
        viewBox={`0 0 ${box.width} ${box.height}`}
        width="100%"
        height={height}
        role="img"
        aria-label={`${flowBandLabels[band]}: ${values[0] ?? 0} at the start of the window, ${latest} now.`}
        data-testid={`flow-facet-${band}`}
      >
        <defs>
          <pattern id={hatchId} width={5} height={5} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1={0} y1={0} x2={0} y2={5} stroke={VIZ_ABSENT} strokeWidth={1.5} />
          </pattern>
        </defs>
        <line
          x1={box.left}
          x2={box.width - box.right}
          y1={box.top + inner}
          y2={box.top + inner}
          stroke={VIZ_GRID}
          strokeWidth={1}
        />
        <path
          d={areaPath(values, local)}
          fill={absent ? `url(#${hatchId})` : VIZ_STRONG}
          opacity={absent ? 1 : 0.32}
        />
        <path
          d={values
            .map((value, index) => `${index === 0 ? 'M' : 'L'}${local.x(index)} ${local.y(value)}`)
            .join(' ')}
          fill="none"
          stroke={absent ? VIZ_ABSENT : VIZ_STRONG}
          strokeWidth={2}
        />
        <g fontSize={9} fontFamily="var(--font-mono)" fill="var(--color-ink-4)">
          <text x={4} y={box.top + 4}>{local.max}</text>
          {shown.map((index) => (
            <text
              key={index}
              x={local.x(index)}
              y={box.height - 3}
              textAnchor={index === 0 ? 'start' : 'end'}
            >
              {axisDate(points[index]?.date ?? '')}
            </text>
          ))}
        </g>
      </svg>
      {absent && (
        <p className="mt-1 text-[11px] leading-[1.45] text-ink-4">
          Issues that existed then whose status on that date is recorded nowhere — this product has
          no status history. Empty at the right edge, growing back.
        </p>
      )}
    </figure>
  );
}
