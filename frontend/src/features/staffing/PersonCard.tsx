import type { PersonCapacity } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Badge } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, joinMeta } from '../../lib/format';
import { AllocationTimeline } from './AllocationTimeline';
import { SkillChips } from './DirectoryFilters';
import { NO_PROFILE, capacityLabel, freeCapacityLabel } from './capacityCopy';

/**
 * One person on `directory.html` — skills on the left, availability on the right.
 *
 * <h2>The badge answers the reader's question, which is not "how allocated are they"</h2>
 *
 * The mockup draws `130% allocated` on one card and `60% free` on the next, and the asymmetry is
 * right: somebody reading this screen is looking for room, so the headline is *how much room*, and
 * the over-allocated case is the one where the answer is "none, and here is why". So a flagged
 * person gets the peak percentage in the danger tone and everybody else gets their free capacity.
 *
 * <h2>The strapline is a date, rendered as years (017 D-b)</h2>
 *
 * `experienceSince` is what is stored and `experienceYears` is derived from it server-side, so
 * nothing here subtracts anything: an integer written down as "6" is correct on the day it is typed
 * and wrong every day after, silently, and nothing in the product would ever say so. A person with
 * no profile has neither, and their strapline is the {@link NO_PROFILE} sentence rather than an
 * empty line — a blank there reads as a person nobody has described, which is the same thing said
 * less usefully.
 *
 * <h2>`Profile` is disabled here, and that is the task boundary</h2>
 *
 * `/directory/:userId` is T5's route. A button linking to a route that does not exist yet is the
 * fake success plan 009's task-cutting rule forbids, so it renders disabled with the reason — never
 * hidden, per §31, because hiding it would say this product has no person page.
 */
export interface PersonCardProps {
  person: PersonCapacity;
  window: { from: string; to: string };
  /** The skill ids the reader filtered on, so the matching chips read as filled. */
  highlighted: readonly string[];
  onToggleSkill: (skillId: string) => void;
  testId: string;
}

export function PersonCard({
  person,
  window,
  highlighted,
  onToggleSkill,
  testId,
}: PersonCardProps) {
  const profile = person.profile;

  return (
    <Panel
      testId={testId}
      title={
        <span className="flex items-center gap-2">
          <span className="text-[15px] font-semibold text-ink">{person.person.displayName}</span>
          {person.overAllocated ?
            <Badge tone="failed" data-testid={`${testId}.Allocated`}>
              {`${person.peakPercent}% allocated`}
            </Badge>
          : <Badge tone="passed" data-testid={`${testId}.Free`}>
              {freeCapacityLabel(person.freePercent)}
            </Badge>
          }
        </span>
      }
      actions={
        <Button
          size="sm"
          disabled
          title="The person page arrives with the profile and absence editor"
          data-testid={`${testId}.Profile`}
        >
          Profile
        </Button>
      }
    >
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="flex flex-col gap-2">
          <SkillChips
            skills={person.skills}
            highlighted={highlighted}
            onToggle={onToggleSkill}
            testId={`${testId}.Skills`}
          />
          <p className="m-0 text-[12px] text-ink-3" data-testid={`${testId}.Profile.Meta`}>
            {profile ?
              joinMeta(
                profile.jobTitle,
                profile.location,
                profile.experienceYears !== null ?
                  `${profile.experienceYears} ${profile.experienceYears === 1 ? 'year' : 'years'}`
                : null,
                `${capacityLabel(person.capacityHours, true)} in this range`,
              )
            : NO_PROFILE}
          </p>
          {/*
            Said on the card rather than only in the badge, because "free from" is the half of the
            question the badge cannot carry: 60% free is useless if it starts in November. Null
            means full for the whole window, which reads as its own sentence rather than as a
            missing date.
          */}
          <p className="m-0 text-[11.5px] text-ink-4" data-testid={`${testId}.FreeFrom`}>
            {person.freeFrom === null ?
              `Fully booked through ${formatDate(window.to)}.`
            : person.freeFrom <= window.from ?
              'Has room from the start of this range.'
            : `Has room from ${formatDate(person.freeFrom)}.`}
          </p>
        </div>

        <AllocationTimeline
          window={window}
          people={[person]}
          testId={`${testId}.Timeline`}
        />
      </div>
    </Panel>
  );
}
