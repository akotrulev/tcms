import type { AcceptanceCriterion } from '../../api/types';
import { Panel } from '../../components/PageHeader';

/**
 * `story.html`'s acceptance criteria — ordered, each with its own done flag.
 *
 * The header count is *"3 of 4"* and it is **read off the list**, which is the one arithmetic this
 * screen is allowed to do: the criteria are the whole population, they are all on screen, and the
 * server publishes no separate figure for them. That is the opposite of {@link CompletionMeter},
 * where the number describes a population the client does not hold — which is what makes one a
 * count and the other a claim (D8).
 *
 * **Read-only here.** Ticking a criterion is a `PATCH /issues/{key}` that replaces the whole array,
 * and the edit surface for it is the story's own edit form rather than a checkbox that writes on
 * click: a click that silently saves is a click somebody makes by accident, on a list whose whole
 * job is being the agreed definition of done.
 */
export function AcceptanceCriteria({
  criteria,
  testId = 'Issue.Criteria',
}: {
  criteria: readonly AcceptanceCriterion[];
  testId?: string;
}) {
  const done = criteria.filter((criterion) => criterion.done).length;

  return (
    <Panel
      title="Acceptance criteria"
      testId={testId}
      actions={
        <span className="text-[11.5px] text-ink-4" data-testid={`${testId}.Count`}>
          {`${done} of ${criteria.length}`}
        </span>
      }
    >
      <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
        {[...criteria]
          .sort((a, b) => a.position - b.position)
          .map((criterion) => (
            <li key={criterion.id} className="flex items-start gap-2.5" data-testid={`${testId}.Item`}>
              <span
                aria-hidden
                className={
                  criterion.done
                    ? 'mt-[3px] text-[12px] text-[color:var(--color-passed)]'
                    : 'mt-[3px] text-[12px] text-ink-4'
                }
              >
                {criterion.done ? '✓' : '○'}
              </span>
              <span
                className={
                  criterion.done
                    ? 'text-[12.5px] text-ink-2'
                    : 'text-[12.5px] text-ink'
                }
              >
                <span className="sr-only">{criterion.done ? 'Met: ' : 'Not met: '}</span>
                {criterion.text}
              </span>
            </li>
          ))}
      </ul>
    </Panel>
  );
}
