import type { PersonCapacity } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { formatHours } from '../../lib/format';
import { UTILISATION_BASIS, utilisationLabel } from './capacityCopy';

/**
 * Utilisation — **the one addition this screen makes to `staffing.html`**, recorded rather than
 * resolved silently.
 *
 * Utilisation is on no mockup. The scope's P6 says only that *"utilisation becomes answerable"*,
 * and what made it answerable is M6 landing: `approvedHours` is a `time` figure, and until
 * timesheets existed there was no numerator. So this panel is new, and it is here rather than
 * anywhere else because this is the screen with the denominator on it.
 *
 * <h2>The subtitle names the spelling, because every other time figure in this product does</h2>
 *
 * `approved hours ÷ allocated capacity` (017 D-i). Approved rather than pending: `approvedHours`
 * and `pendingHours` are separate on the wire and **nothing in this product sums them** (016 D-d),
 * and Finance holds `staffing:directory` for exactly this figure — hours nobody has accepted are
 * not yet a fact about the money.
 *
 * <h2>Null is *not allocated*, and it is not `0%`</h2>
 *
 * `utilisationPercent` is null when allocated capacity for the window is zero. `0%` would say the
 * person is idle; the true statement is that nobody has planned their time, and those are different
 * things to go and do something about. {@link utilisationLabel} owns that word so the timesheet
 * tile and this panel cannot end up with two.
 *
 * A person with **no profile** has no capacity to divide by either, and their row reads the same
 * *not allocated* through the same null — which is correct but under-informative, so the hours
 * column beside it carries the em dash that says which of the two it is.
 */
export interface UtilisationPanelProps {
  people: readonly PersonCapacity[];
  testId: string;
}

export function UtilisationPanel({ people, testId }: UtilisationPanelProps) {
  return (
    <Panel
      testId={testId}
      title="Utilisation"
      actions={
        <span className="text-[11.5px] text-ink-4" data-testid={`${testId}.Basis`}>
          {UTILISATION_BASIS}
        </span>
      }
    >
      {people.length === 0 ?
        <p className="m-0 text-[12.5px] leading-[1.6] text-ink-3" data-testid={`${testId}.None`}>
          Nobody to report on in this range.
        </p>
      : <div className="flex flex-col gap-2">
          {people.map((person) => (
            <div
              key={person.person.id}
              className="flex items-baseline gap-2"
              data-testid={`${testId}.Person.${person.person.id}`}
            >
              <span className="min-w-0 flex-1 truncate text-[12.5px] text-ink">
                {person.person.displayName}
              </span>
              {/*
                The two figures the percentage is made of, so a reader who distrusts it can check
                it. `formatHours` renders both nulls as an em dash, which is the whole no-profile
                state on this row — never `0h`, which claims somebody logged nothing.
              */}
              <span className="font-mono text-[11px] text-ink-4">
                {`${formatHours(person.approvedHours)} / ${formatHours(person.allocatedCapacityHours)}`}
              </span>
              <span
                className="w-[86px] text-right font-mono text-[12px] text-ink-2"
                data-testid={`${testId}.Percent.${person.person.id}`}
              >
                {utilisationLabel(person.utilisationPercent)}
              </span>
            </div>
          ))}
        </div>
      }
    </Panel>
  );
}
