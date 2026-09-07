import { useMemo } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useSkills, useStaffingPeople } from '../../api/queries';
import type { DirectoryFilters as Filters } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Button } from '../../components/ui';
import { formatCount, plural } from '../../lib/format';
import { DirectoryFilters } from './DirectoryFilters';
import { PersonCard } from './PersonCard';
import { todayIso, windowOver } from './staffingWindow';

/**
 * `directory.html` — who has which skills, and when they are free.
 *
 * <h2>The whole screen is one question, and the URL is the question</h2>
 *
 * *"Who is free next month with skill X"* is answered by `?skill=…&freeFrom=…`, and holding those
 * in `useSearchParams` rather than in component state is what makes the answer **shareable** — the
 * acceptance criterion is not "the filters work", it is that the reader can send the result to
 * whoever asked. The same choice keys the query by the filters, so a slow answer for one skill set
 * cannot land under a heading that now says another.
 *
 * <h2>The denominator is a second read, and this is a deviation from what T4 was told</h2>
 *
 * The plan predicted the match count *"3 of 11 people match"* would be served. It is not:
 * `GET /staffing/people` answers a bare `PersonCapacity[]` with no total on it, so the 11 has to
 * come from somewhere. It comes from the **same endpoint with the skill and date filters dropped**,
 * which is one extra key — `{from,to}` alone — fetched once and reused across every filter
 * combination the reader tries, and identical to the main read (so *one* request) when nothing is
 * applied. Counting the filtered array instead would have given "3 of 3", which is not a
 * denominator; inventing a company size from a members list would have been a second answer to a
 * question this endpoint already answers.
 *
 * <h2>The gate is `staffing:directory`, and the refusal names the project screen</h2>
 *
 * Same gate and same sentence as `/staffing`, for 017 D-g's reason: a Project Manager holds the
 * PROJECT-scope pair and no company key, and the screen that answers *their* question is a
 * project's own Staffing. The rail entry is shown to them anyway, per the Company group's standing
 * rule.
 */

const TEST_ID = 'Directory';

export function DirectoryPage() {
  const [params, setParams] = useSearchParams();
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const freeFrom = params.get('freeFrom') ?? '';
  const skill = useMemo(() => params.getAll('skill'), [params]);

  const gate = useCanInCompany();
  const allowed = gate.can('staffing:directory');

  const filters: Filters = useMemo(
    () => ({
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
      ...(skill.length > 0 ? { skill } : {}),
      ...(freeFrom ? { freeFrom } : {}),
    }),
    [from, to, skill, freeFrom],
  );

  /*
    The unfiltered read is the denominator, and it is deliberately the SAME hook: when nothing is
    applied the two keys hash identically and TanStack makes one request. See the file header.
  */
  const unfiltered: Filters = useMemo(
    () => ({ ...(from ? { from } : {}), ...(to ? { to } : {}) }),
    [from, to],
  );

  const people = useStaffingPeople(filters);
  const everybody = useStaffingPeople(unfiltered);
  const skills = useSkills();

  const setParam = (mutate: (next: URLSearchParams) => void) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      mutate(next);
      return next;
    });
  };

  const toggleSkill = (skillId: string) =>
    setParam((next) => {
      const held = next.getAll('skill');
      next.delete('skill');
      const updated = held.includes(skillId) ?
        held.filter((id) => id !== skillId)
      : [...held, skillId];
      updated.forEach((id) => next.append('skill', id));
    });

  const setFreeFrom = (date: string) =>
    setParam((next) => {
      if (date) next.set('freeFrom', date);
      else next.delete('freeFrom');
    });

  const clearFilters = () =>
    setParam((next) => {
      next.delete('skill');
      next.delete('freeFrom');
    });

  const filtered = skill.length > 0 || freeFrom !== '';
  const summary =
    people.data === undefined ? undefined
    : filtered && everybody.data !== undefined ?
      `${formatCount(people.data.length)} of ${plural(everybody.data.length, 'person')} match`
    : plural(people.data.length, 'person');

  /*
    Widest span in the answer, falling back to the URL's range and then to today — the reasoning is
    `staffingWindow.ts`'s, and it lives there because `/directory/:userId` draws the same bars from
    the same shape and two spellings would let one screen clip a bar the other shows in full.
  */
  const window = windowOver(people.data ?? [], {
    from: from || todayIso(),
    to: to || todayIso(),
  });

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Directory"
        documentTitle="Skills directory"
        subtitle="Who has which skills, and when they are free."
        actions={
          <Button size="sm" asChild>
            <RouterLink to="/staffing" data-testid={`${TEST_ID}.Staffing`}>
              Staffing view
            </RouterLink>
          </Button>
        }
      />

      {!allowed ?
        <Panel testId={`${TEST_ID}.Denied`}>
          <p
            className="m-0 max-w-[74ch] text-[13px] leading-[1.6] text-ink-3"
            data-testid={`${TEST_ID}.DeniedText`}
          >
            {gate.pending ?
              'Checking your permissions…'
            : <>
                The company directory is for Account Managers and Finance. To see who is on a
                project you run, open that project’s{' '}
                <RouterLink className="underline" to="/projects" data-testid={`${TEST_ID}.ToProjects`}>
                  Staffing
                </RouterLink>
                .
              </>
            }
          </p>
        </Panel>
      : <>
          {/*
            Above the region, so a failed read leaves the chips and the pickers on screen — the
            reader can narrow, widen or clear without a reload. DataRegion's own header states it.
          */}
          <DirectoryFilters
            skills={skills.data ?? []}
            selected={skill}
            freeFrom={freeFrom}
            onToggleSkill={toggleSkill}
            onFreeFrom={setFreeFrom}
            onClear={clearFilters}
            summary={summary}
            testId={`${TEST_ID}.Filters`}
          />

          <DataRegion
            testId={`${TEST_ID}.Region`}
            state={{ ...people, refetch: () => void people.refetch() }}
            isEmpty={(data) => data.length === 0}
            empty={{
              kind: filtered ? 'no-filter-match' : 'no-records',
              title: filtered ? 'Nobody matches' : 'Nobody is in this company yet',
              body:
                filtered ?
                  'No one holds every skill you picked and has room from that date. Take a skill off, or move the date out.'
                : 'People appear here as soon as they accept an invitation.',
              hidden: everybody.data?.length,
              filters: skill.length + (freeFrom ? 1 : 0),
              noun: 'person',
              onClearFilters: filtered ? clearFilters : undefined,
            }}
            skeleton={<GridSkeleton rows={3} />}
          >
            {(loaded) => (
              <div className="flex flex-col gap-4">
                {loaded.map((person) => (
                  <PersonCard
                    key={person.person.id}
                    person={person}
                    window={window}
                    profileTo={`/directory/${person.person.id}`}
                    highlighted={skill}
                    onToggleSkill={toggleSkill}
                    testId={`${TEST_ID}.Person.${person.person.id}`}
                  />
                ))}
              </div>
            )}
          </DataRegion>

          <p className="mt-4 text-[12.5px] leading-[1.6] text-ink-3">
            Enough to answer a client’s staffing question — who knows this, and from when — without
            opening a spreadsheet or asking three people.
          </p>
        </>
      }
    </div>
  );
}
