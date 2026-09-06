import type { BoardIssueCard } from '../../api/types';
import { cn } from '../../lib/cn';

/**
 * §2.6's readiness signals — **what is missing, in words**, on the grooming board's card face.
 *
 * `18-pm-board.html` draws them as a row of small chips under the title: `✓ AC · ✓ est 8h ·
 * ✓ epic · — release`. Each is a glyph **plus a word**, never a colour alone — the render standard's
 * rule, and the reason a `—` chip says `estimate` rather than going grey and silent. A person
 * grooming a backlog is asking "what stops this being ready", and a card that answers with an
 * absence of green answers nothing.
 *
 * ## Three signals, not four, and the fourth is a contract gap rather than an omission
 *
 * §2.6 names five (acceptance criteria, estimate, epic, target release, task count). The task count
 * is the row header's `n/m`, which every earned row already carries. **Target release is not on
 * `BoardIssueCard`** — 021 T1 added six card fields and a release was not among them, so there is
 * no honest way to draw `— release`: a `—` would be a claim that the issue has no release, which
 * this payload cannot know from a field it does not carry. Drawing three and saying so beats
 * drawing four where one is invented; the fourth arrives with the contract field.
 *
 * ## An epic's third signal counts stories
 *
 * `18-pm-board.html` gives `ACME-102` `✓ 8 stories` where a story has `✓ epic`, which is the same
 * question — *does this sit in a structure* — asked of the level below. An epic has no epic, so a
 * `— epic` on one would be a permanent, unfixable red mark on every epic on the board.
 *
 * The signals are read from fields the card already carries and nothing is derived twice: this
 * component decides only how a boolean is worded.
 */
export function ReadinessSignals({
  card,
  testId,
}: {
  card: BoardIssueCard;
  testId: string;
}) {
  const signals = signalsOf(card);

  return (
    <div
      data-testid={testId}
      className="mb-[7px] flex flex-wrap items-center gap-[7px] text-[10.5px] leading-[1.4]"
    >
      {signals.map((signal) => (
        <span
          key={signal.label}
          data-testid={`${testId}.${signal.name}`}
          className={cn(
            'inline-flex items-center gap-1 rounded-[4px] border px-[5px] py-[1px]',
            signal.held
              ? 'border-[color:var(--color-passed)] text-ink-2'
              : 'border-line text-ink-4',
          )}
        >
          <b aria-hidden className="font-semibold">{signal.held ? '✓' : '—'}</b>
          <span className="sr-only">{signal.held ? 'has' : 'missing'}</span>
          {signal.label}
        </span>
      ))}
    </div>
  );
}

/**
 * One signal's decided wording.
 *
 * Local, and `signalsOf` below is local with it: a module exporting a component **and** a function
 * trips oxlint's `react(only-export-components)`, and the lint gate is zero warnings — the same
 * constraint `boardCoverage.ts` and `cardMove.ts` are separate files for. The rules here are small
 * enough to pin through the render, which is where they are read anyway.
 */
interface ReadinessSignal {
  /** A stable testid segment, so a test names the signal rather than its current words. */
  name: 'AC' | 'Estimate' | 'Structure';
  label: string;
  held: boolean;
}

function signalsOf(card: BoardIssueCard): ReadinessSignal[] {
  return [
    { name: 'AC', label: 'AC', held: card.hasAcceptanceCriteria },
    {
      name: 'Estimate',
      // The number when there is one: "est 8h" is the signal AND the value, which is one glance
      // instead of two and is exactly what the render draws.
      label: card.estimateHours == null ? 'estimate' : `est ${card.estimateHours}h`,
      held: card.estimateHours != null,
    },
    card.typeLevel === 'EPIC'
      ? {
        name: 'Structure',
        label: card.subtaskTotal === 1 ? '1 story' : `${card.subtaskTotal} stories`,
        held: card.subtaskTotal > 0,
      }
      : { name: 'Structure', label: 'epic', held: card.epicKey != null },
  ];
}
