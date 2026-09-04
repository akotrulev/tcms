import { Link as RouterLink } from 'react-router-dom';
import type { PersonCapacity } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Badge } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';

/**
 * `staffing.html`'s Over-100% panel — **the breakdown, and it exists only on this screen**.
 *
 * 017 D-f: `GET /projects/{projectId}/staffing` returns each person's total percentage and the flag
 * and **names no other project**, because a Project Manager holds a project-scope grant and no
 * company key — listing *"Acme Loyalty 50% + Bergstrom Portal 30%"* there would disclose, through
 * that grant, the existence and staffing of projects they cannot see. This screen is
 * `staffing:directory`, which is the company-wide key, so here the breakdown is the answer.
 *
 * The mockup's sentence is kept **verbatim**: *"Flagged, not blocked. The product does not know
 * which of the three matters most — you do."* It is the product saying out loud what it will not
 * do, and 017 D-e is why — over-allocation is a peak-day fact, and which allocation should give way
 * is a judgement about work nobody has told this system about.
 *
 * The `Adjust` button the mockup draws is a **link to the person**, not a dialog: an allocation is
 * written on a project (`staffing:allocate`, PROJECT scope) and this screen names no project, so a
 * dialog here would have to ask which of the three the reader meant and then refuse them on two of
 * the answers.
 *
 * <h2>The tone is `failed`, not `blocked`, and the panel's own copy is the argument</h2>
 *
 * The mockup paints this `b-blocked`. The kit's `blocked` means *something outside stopped this* —
 * and the sentence three lines down says **"Flagged, not blocked"**, so that tone would be the one
 * word on the screen contradicting the one beside it. `failed` is the kit's red and is what
 * `--color-danger` resolves to, which is the hue the mockup actually draws.
 */
export interface OverAllocatedPanelProps {
  people: readonly PersonCapacity[];
  testId: string;
}

export function OverAllocatedPanel({ people, testId }: OverAllocatedPanelProps) {
  return (
    <Panel
      testId={testId}
      title="Over 100%"
      actions={
        <Badge tone={people.length > 0 ? 'failed' : 'plain'} data-testid={`${testId}.Count`}>
          {people.length === 0 ? 'nobody' : plural(people.length, 'person')}
        </Badge>
      }
    >
      {people.length === 0 ?
        <p className="m-0 text-[12.5px] leading-[1.6] text-ink-3" data-testid={`${testId}.None`}>
          Nobody in this range is booked past their capacity on any single day.
        </p>
      : <div className="flex flex-col gap-2.5">
          {people.map((person) => (
            <div
              key={person.person.id}
              className="flex flex-col gap-0.5"
              data-testid={`${testId}.Person.${person.person.id}`}
            >
              <p className="m-0 text-[12.5px] text-ink">
                {`${person.person.displayName} — `}
                <strong style={{ color: 'var(--color-danger)' }}>{`${person.peakPercent}%`}</strong>
                {/*
                  The peak DAY, named. A month is what the mockup's copy says and a day is what the
                  number means (017 D-e): the reader is being pointed at one date to go and fix, and
                  "in October" would send them looking through thirty-one of them.
                */}
                {person.peakOn ? ` on ${formatDate(person.peakOn)}` : ''}
              </p>
              <p className="m-0 text-[11px] text-ink-4" data-testid={`${testId}.Breakdown.${person.person.id}`}>
                {person.allocations.length === 0 ?
                  'No allocation in this range accounts for it — the peak is outside the window you are looking at.'
                : person.allocations
                    .map((span) => `${span.projectName} ${span.percent}%`)
                    .join(' + ')
                }
              </p>
            </div>
          ))}

          <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
            Flagged, not blocked. The product does not know which of the three matters most — you
            do.
          </p>

          <Button size="sm" variant="quiet" asChild className="self-start">
            <RouterLink to="/directory" data-testid={`${testId}.Adjust`}>
              Adjust in the directory
            </RouterLink>
          </Button>
        </div>
      }
    </Panel>
  );
}
