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

/** What the glyph says when the payload carries no type name — the level's own word, and nothing more. */
const LEVEL_WORD = {
  EPIC: 'Epic',
  STORY: 'Story',
  TASK: 'Task',
} as const;

export function TypeIcon({
  type,
  className,
  ...rest
}: {
  /**
   * The fields the glyph and the tone are derived from — **not the whole `IssueTypeRef`**.
   *
   * Widened in 012 T7, when the project board's card arrived: `BoardIssueCard` carries `typeName`,
   * `typeLevel` and `countsTowardCompletion` and **no type id**, so a caller there had two choices —
   * synthesise an id this component has never read, or say in the type which fields it actually
   * needs. The second is the one a reader can check. Every existing caller passes a full
   * `IssueTypeRef` and is unaffected, because this is a widening.
   *
   * Widened again in 012 T8: **`countsTowardCompletion` is optional, and absent is not false.**
   * `BacklogRow` does not carry the field at all, so a backlog row cannot say whether its type is a
   * Spike — and defaulting the absence to `false` would draw every one of them amber with a `?`.
   * Absent means the payload does not answer, so the glyph falls back to the level's own tone,
   * which is the honest reading and the reason the test below is `=== false` rather than `!`.
   *
   * Widened a third time in 016 T4, and this one takes the **name** away: `TimeEntry.subject`
   * carries `typeLevel` and no type name at all, because a timesheet row denormalises the issue
   * rather than embedding its type. So the letter and the accessible text fall back to the level's
   * own word — `Epic` / `Story` / `Task` — and the tooltip is simply absent, which is the one
   * honest rendering available: the level is a fact this payload states, and the type's name is not
   * something to invent from it. The header above rules out deriving a colour from an English word;
   * this is the same rule applied to a word that is not on the wire.
   */
  type: Pick<IssueTypeRef, 'level'> & { name?: string; countsTowardCompletion?: boolean };
  className?: string;
  'data-testid'?: string;
}) {
  const spike = type.level === 'STORY' && type.countsTowardCompletion === false;
  const background = spike ? 'var(--color-warning)' : LEVEL_TONE[type.level];
  const named = type.name ?? LEVEL_WORD[type.level];

  return (
    <span
      // The type's full name, so a reader who cannot tell `S` from `S` on two story-level types
      // gets the answer without leaving the row. `aria-hidden` on the glyph itself: the title is
      // the accessible text, and reading "S" out loud is worse than reading nothing.
      title={type.name}
      data-testid={rest['data-testid']}
      className={cn(
        'inline-grid size-[15px] flex-none place-items-center rounded-sm',
        'font-mono text-[9px] font-bold text-rail',
        className,
      )}
      style={{ background }}
    >
      <span aria-hidden>{spike ? '?' : named.slice(0, 1).toUpperCase()}</span>
      <span className="sr-only">{named}</span>
    </span>
  );
}
