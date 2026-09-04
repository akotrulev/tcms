import { Link as RouterLink } from 'react-router-dom';
import type { PersonCapacity } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Button } from '../../components/ui';
import { formatDate } from '../../lib/format';

/**
 * `staffing.html`'s Free-capacity panel — who has room, and from when.
 *
 * <h2>The list is derived here and the numbers are not</h2>
 *
 * `freePercent` is served, floored at zero, so this panel picks the people it shows by
 * `freePercent > 0` and prints what it was given. That is the one derivation this screen does, and
 * it is safe for the reason the Over-100% panel's is not: *has room* is a property of one served
 * number on one row, where *is over-allocated* is a fact about a peak day that no field on the row
 * would let a client recompute (017 D-e). The moment this panel needed to *rank* people by when
 * they free up rather than by how much, it would need the server's answer too.
 *
 * <h2>`freeFrom` is the date, and null means full for the whole window</h2>
 *
 * The contract's own reading: the first day in the window on which this person is under 100%
 * allocated. It is what `directory.html`'s *"free from 1 Oct"* renders, and a person with room from
 * the first day of the range needs no date at all — saying *"free from"* the day the reader is
 * already looking at is noise.
 */
export interface FreeCapacityPanelProps {
  people: readonly PersonCapacity[];
  /** The window's own start, so a `freeFrom` equal to it is read as "now" rather than printed. */
  windowFrom: string;
  testId: string;
}

export function FreeCapacityPanel({ people, windowFrom, testId }: FreeCapacityPanelProps) {
  const free = people
    .filter((person) => person.freePercent > 0)
    .slice()
    .sort((a, b) => b.freePercent - a.freePercent);

  return (
    <Panel
      testId={testId}
      title="Free capacity"
      actions={<span className="text-[11.5px] text-ink-4">in this range</span>}
    >
      {free.length === 0 ?
        <p className="m-0 text-[12.5px] leading-[1.6] text-ink-3" data-testid={`${testId}.None`}>
          Everybody is fully booked for this whole range. Widen the range to find room further out.
        </p>
      : <div className="flex flex-col gap-2">
          {free.map((person) => (
            <div
              key={person.person.id}
              className="flex items-baseline gap-2"
              data-testid={`${testId}.Person.${person.person.id}`}
            >
              <span className="min-w-0 flex-1 truncate text-[12.5px] text-ink">
                {person.person.displayName}
              </span>
              {person.freeFrom && person.freeFrom > windowFrom && (
                <span className="text-[11px] text-ink-4">
                  {`from ${formatDate(person.freeFrom)}`}
                </span>
              )}
              <span
                className="font-mono text-[12px]"
                style={{ color: 'var(--color-passed)' }}
                data-testid={`${testId}.Percent.${person.person.id}`}
              >
                {`${person.freePercent}%`}
              </span>
            </div>
          ))}
        </div>
      }

      <Button size="sm" variant="quiet" asChild className="mt-3 self-start">
        <RouterLink to="/directory" data-testid={`${testId}.Directory`}>
          Who has which skills →
        </RouterLink>
      </Button>
    </Panel>
  );
}
