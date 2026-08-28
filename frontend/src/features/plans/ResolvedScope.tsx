import { Alert, Box, Skeleton, Stack, Typography } from '@mui/material';
import { useReferencedCases, type CaseReference } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { formatCount, plural } from '../../lib/format';
import { resolveReferences, type ReferenceRead } from '../../lib/planScope';
import type { GroupId } from './planDraft';

/**
 * **What a plan group's references currently resolve to** — read-only, recomputed live (§103).
 *
 * ## Why this exists
 *
 * §97's D7′ made a group a *selector*: it holds suite ids, tags and case ids, and its membership is
 * a query the server re-runs on every read. The tab drew the selector and nothing else, so the one
 * question an author actually has — *which cases does this catch?* — was answerable only by leaving
 * the screen. This list answers it beside the chips.
 *
 * ## Why it does not break D7′
 *
 * Nothing here is written anywhere. The rows are derived from a read, on every render, and the
 * moment a reference comes off they are gone. **The model D7 reversed was an add-time snapshot**;
 * this is the opposite of a snapshot, and the difference is that no state on this screen or on the
 * server remembers a single one of these case ids.
 *
 * ## Why the suite arm is a request and not a filter
 *
 * A suite's membership is filed ∪ added ∪ tagged since §98. `cases.filter(c => c.suiteId === id)` is
 * the filed arm alone — the exact thing §96 shipped, which showed a tag-defined suite as a count of
 * 2 over a grid of 0 rows. Every arm here is `GET /cases`, which resolves the union server-side.
 *
 * ## What it deliberately does NOT show
 *
 *   - **Cases named directly.** They have no by-ids read on the contract, and the only way to
 *     resolve one today is the tab's 200-row catalogue, whose truncation is a recorded, accepted gap
 *     for a *chip that can still be removed* and would be a lie inside a list presented as complete.
 *     They are already drawn in full, key and title, by their own chip — so the caption says they
 *     are not repeated here rather than leaving the reader to wonder.
 *   - **A count.** The group header already carries the server's `caseCount`, over all three arms.
 *     A second number here would mean something different and read as a contradiction (§52).
 *   - **New case rows.** They do not exist yet; the rows below say they are created on Save.
 */
export function ResolvedScope({
  projectId,
  group,
  references,
  labelFor,
  namedCaseCount,
  dirty,
}: {
  projectId: string;
  group: GroupId;
  /** The group's collection references, in the order they are drawn as chips. */
  references: CaseReference[];
  /** How a reference reads on screen — `Suite Payments`. The suite names live one level up. */
  labelFor: (reference: CaseReference) => string;
  /** Cases named directly in this group, which this list does not repeat. */
  namedCaseCount: number;
  /** Whether the tab's draft has moved past the plan as loaded; see the caption. */
  dirty: boolean;
}) {
  const state = useReferencedCases(projectId, references);
  const testId = `PlanCases.Resolved.${group ?? 'unsectioned'}`;

  /*
    Not memoized on purpose. The parent re-renders on every keystroke in the draft, and `labelFor`
    is a fresh closure each time, so a `useMemo` keyed on it never hits and one keyed on `state.data`
    alone would go stale the moment a suite name arrived. The work is a merge over at most a few
    hundred rows, which is cheaper than the dependency array that would be wrong.
  */
  const reads: ReferenceRead[] = (state.data ?? []).map((read) => ({
    label: labelFor(read.reference),
    cases: read.cases,
    total: read.total,
  }));
  const resolved = resolveReferences(reads);

  // A group defined by nothing has nothing to resolve, and an empty region with a caption about
  // references would be an explanation of an absence the reader can already see.
  if (references.length === 0) return null;

  return (
    <Stack sx={{ gap: 0.5 }} data-testid={testId}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {dirty
          ? /*
              The honest sentence for a reference the author has just added. The rows ARE live
              server data — that is the point, and it is why the served count beside the heading is
              hidden here while this list is not — but they are what the group *would* catch, and
              the plan does not hold the reference until Save. Saying "resolves to" here would claim
              a state of the plan that does not exist yet.
            */
            'What these references would catch — including the ones not saved yet. Nothing is in the plan until you press Save.'
          : 'The cases these references catch right now, recomputed on every read.'}
        {namedCaseCount > 0
          && ` The ${plural(namedCaseCount, 'case')} named directly above are not repeated here.`}
      </Typography>

      <DataRegion
        testId={testId}
        state={state}
        isEmpty={() => resolved.cases.length === 0}
        empty={{
          // `by-design`, not `no-records`: nothing is broken and no filter is in the way. A suite
          // that is currently empty and a tag nobody has used yet are both ordinary states here.
          kind: 'by-design',
          title: 'These references catch no cases',
          body: 'The suites and tags above resolve to nothing at the moment. They stay on the plan, and this fills in when a matching case appears.',
        }}
        skeleton={
          <Stack sx={{ gap: 0.5, pl: 2 }}>
            {[0, 1, 2].map((row) => (
              <Skeleton key={row} variant="text" width="60%" />
            ))}
          </Stack>
        }
      >
        {() => (
          <Stack sx={{ gap: 0.25, pl: 2 }} data-testid={`${testId}.List`}>
            {resolved.truncated.map((cut) => (
              <Alert
                key={cut.label}
                severity="info"
                sx={{ py: 0, my: 0.5 }}
                data-testid={`${testId}.Truncated`}
              >
                {cut.label} resolves to {plural(cut.total, 'case')} — the first{' '}
                {formatCount(cut.shown)} are listed.
              </Alert>
            ))}
            {resolved.cases.map((row) => (
              <Stack
                key={row.id}
                direction="row"
                sx={{ gap: 1, alignItems: 'baseline', flexWrap: 'wrap' }}
                data-testid={`${testId}.Row`}
              >
                <Box aria-hidden sx={{ color: 'text.disabled', fontFamily: 'monospace' }}>
                  ├─
                </Box>
                <Identifier>{row.key}</Identifier>
                <Typography variant="body2">{row.title}</Typography>
                {/*
                  Which reference brought it in. Without it, taking a suite off and watching three
                  rows vanish is a surprise; with it, the row said so before the click.
                */}
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  ({row.via.join(', ')})
                </Typography>
              </Stack>
            ))}
          </Stack>
        )}
      </DataRegion>
    </Stack>
  );
}
