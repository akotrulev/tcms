import type { IssueTypeRef } from '../../api/types';
import { cn } from '../../lib/cn';

/**
 * The 15px lettered square beside every issue — the mockups' `.type-ic`.
 *
 * ## Why it is derived from the contract rather than from the type's name
 *
 * The mockups give five types five colours: a violet Epic, a green Story, a blue Task, a **red
 * Bug** and an amber Spike. Four of those five are structural and the fifth is not.
 *
 * An issue type is a **row**, not an enum (scope §4): a project may rename `Story`, and a fourth
 * story-level type is a row rather than a migration. So a map keyed on the English word `Bug`
 * would be a hardcoded name that a project renaming its type breaks silently — the icon quietly
 * turning green on every defect, with nothing failing. The contract carries no field that says
 * "this type is a defect", and inventing one here would be this component asserting a fact the
 * server does not hold.
 *
 * What the contract *does* carry is two fields that answer four of the five:
 *
 *   - `level` separates Epic, story-level and sub-task-level;
 *   - `countsTowardCompletion` is **false for Spike and for every epic-level type** (D8) — a spike
 *     produces knowledge, not shippable work — which is exactly the Spike/Story split, stated by
 *     the server rather than guessed from a word.
 *
 * **So a Bug renders in the story-level green, not the mockup's red.** Recorded as a deliberate
 * deviation rather than left for a reader to find: the alternative is a name check, and the price
 * of that is a colour that lies on any project that does not spell its types in English.
 *
 * The glyph is the type's own initial, so a project's fourth story-level type gets a legible icon
 * with nobody adding a case here — except a spike, which is the mockups' `?` and reads as the open
 * question a spike is.
 */

/** Epic is the app's `retest` indigo. It is the tone nothing else on this screen uses, and it is
 *  the nearest declared token to the mockups' violet — a new hue would be a palette addition for
 *  one glyph. */
const LEVEL_TONE = {
  EPIC: 'var(--color-retest)',
  STORY: 'var(--color-passed)',
  TASK: 'var(--color-info)',
} as const;

export function TypeIcon({
  type,
  className,
  ...rest
}: {
  type: IssueTypeRef;
  className?: string;
  'data-testid'?: string;
}) {
  const spike = type.level === 'STORY' && !type.countsTowardCompletion;
  const background = spike ? 'var(--color-warning)' : LEVEL_TONE[type.level];

  return (
    <span
      // The type's full name, so a reader who cannot tell `S` from `S` on two story-level types
      // gets the answer without leaving the row. `aria-hidden` on the glyph itself: the title is
      // the accessible text, and reading "S" out loud is worse than reading nothing.
      title={type.name}
      data-testid={rest['data-testid']}
      className={cn(
        'inline-grid size-[15px] flex-none place-items-center rounded-[3px]',
        'font-mono text-[9px] font-bold text-rail',
        className,
      )}
      style={{ background }}
    >
      <span aria-hidden>{spike ? '?' : type.name.slice(0, 1).toUpperCase()}</span>
      <span className="sr-only">{type.name}</span>
    </span>
  );
}
