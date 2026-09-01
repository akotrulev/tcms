import { Link2 } from 'lucide-react';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  useCases,
  useIssueTestCases,
  usePutIssueTestCases,
} from '../../api/queries';
import type { Issue, IssueTestCase } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { StatusDot } from '../../components/StatusChip';
import { MultiPicker } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatAge, formatDateTime, plural } from '../../lib/format';

const TEST_ID = 'Story.LinkedCases';

/**
 * `story.html`'s **Linked test cases** panel — story H7, and **the only writer of the QA link**.
 *
 * <h2>Without this panel, every release in every instance reads `NOT_TESTED` forever</h2>
 *
 * `PUT /issues/{key}/test-cases` is the one operation on the contract that creates a `TEST_CASE`
 * `issue_link` row, and readiness is computed over exactly those rows (013 D-p). T3 built the read;
 * this is the write, which is why the two shipped in the same module and not one after the other.
 * The reverse direction of the same fact is `CaseGrid`'s **Verifies** column, built beside it —
 * `test-cases.html`'s panel foot says so out loud: *"A case shows which stories it verifies, and a
 * story shows the latest result of each case. Same fact, both directions."*
 *
 * <h2>The `Run` column is the release version, because there is no run on the payload</h2>
 *
 * The mockup's fourth column reads `2.4 regression`, a run's name. `IssueTestCase` carries
 * `lastReleaseVersion` and no run id or name at all — it is served from `case_health`, the same read
 * model `TestCaseSummary.lastResult` comes from, so a case cannot show one result on its own screen
 * and another here. The column is therefore **Version**, named for what it holds. A `Run` header
 * over a version string would be the wrong label on a right value, which is worse than a narrower
 * true one.
 *
 * <h2>The latest result is the case's ANYWHERE, not this release's</h2>
 *
 * Deliberately unscoped (`useIssueTestCases`' own note): this panel asks *does this case pass*, and
 * readiness asks *did it pass for the version we are shipping*. The version column is what makes the
 * difference visible on the row — a `Passed` against `2.3` is not evidence about `2.4`.
 *
 * <h2>`PermissionGate` wraps the BUTTON, never the list</h2>
 *
 * Its denied branch renders the child inside a tooltip-triggering `<span role="none">`; around one
 * control that is right, and around a `<table>` it is both invalid nesting and a tooltip that opens
 * over every row. The same rule `BacklogView` states for its `<ol>`.
 */
export function LinkedCasesPanel({ issue }: { issue: Issue }) {
  const cases = useIssueTestCases(issue.key);
  const [linking, setLinking] = useState(false);

  return (
    <Panel
      title="Linked test cases"
      testId={TEST_ID}
      actions={
        <span className="flex items-center gap-2">
          <span className="text-[11.5px] text-ink-4">latest result per case</span>
          <PermissionGate projectId={issue.projectId} permission="issue:edit">
            {({ allowed, reason }) => (
              <Button
                size="sm"
                disabled={!allowed}
                title={allowed ? undefined : reason}
                onClick={() => setLinking(true)}
                className="min-h-6 px-2 py-0.5 text-[11px]"
                data-testid={`${TEST_ID}.Link`}
              >
                <Link2 aria-hidden className="size-3" />
                Link test cases
              </Button>
            )}
          </PermissionGate>
        </span>
      }
    >
      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...cases, refetch: () => void cases.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No cases linked',
          body: 'A release measures readiness over the cases linked to its stories, so a story with none reads as untested rather than as passing. Link the cases that prove this one.',
        }}
      >
        {(rows) => (
          <>
            <ScrollX>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Case</TableHeaderCell>
                    <TableHeaderCell>Title</TableHeaderCell>
                    <TableHeaderCell>Latest result</TableHeaderCell>
                    <TableHeaderCell>Version</TableHeaderCell>
                    <TableHeaderCell>When</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((row) => (
                    <CaseRow key={row.caseId} row={row} projectId={issue.projectId} />
                  ))}
                </TableBody>
              </Table>
            </ScrollX>
            <FailingFoot rows={rows} />
          </>
        )}
      </DataRegion>

      {linking && (
        <LinkCasesDialog
          issue={issue}
          linked={cases.data ?? []}
          onClose={() => setLinking(false)}
        />
      )}
    </Panel>
  );
}

/**
 * One case, and the three `last*` fields that are **required and nullable together**.
 *
 * A case that has never been executed carries three nulls, and that is a different statement from
 * "this endpoint does not serve them" — which is why the contract requires all three rather than
 * omitting them. `Never run` is the row's answer, not a blank: a blank cell here reads as a value
 * that failed to load, and this is the panel whose whole subject is whether proof exists.
 */
function CaseRow({ row, projectId }: { row: IssueTestCase; projectId: string }) {
  return (
    <TableRow data-testid={`${TEST_ID}.Row`}>
      <TableCell>
        <Identifier variant="muted">{row.caseKey}</Identifier>
      </TableCell>
      <TableCell variant="title">
        <RouterLink
          to={`/projects/${projectId}/cases/${row.caseId}`}
          data-testid={`${TEST_ID}.Row.Title`}
          className="text-inherit no-underline hover:text-steel hover:underline"
        >
          {row.title}
        </RouterLink>
      </TableCell>
      <TableCell>
        {row.lastResultKind ? (
          <StatusDot kind={row.lastResultKind} testId={`${TEST_ID}.Row.Result`} />
        ) : (
          <span className="text-ink-3" data-testid={`${TEST_ID}.Row.Result`}>
            Never run
          </span>
        )}
      </TableCell>
      <TableCell>
        {/*
          Not omitted when null: a result recorded against no release is an ordinary thing — an ad-hoc
          run — and an empty cell beside a green Passed reads as a version that failed to render.
        */}
        {row.lastReleaseVersion ? (
          <Identifier variant="muted">{row.lastReleaseVersion}</Identifier>
        ) : (
          <span className="text-ink-4">no release</span>
        )}
      </TableCell>
      <TableCell>
        <span className="text-ink-3" title={formatDateTime(row.lastExecutedAt)}>
          {formatAge(row.lastExecutedAt)}
        </span>
      </TableCell>
    </TableRow>
  );
}

/**
 * The mockup's red foot — *"TC-311 failed, so 2.4 is not ready … 'Done' and 'proven' are the same
 * screen."*
 *
 * **What it does not claim is which release is not ready.** The mockup's sentence names `2.4`, and
 * this panel cannot: the result on the row is the case's latest **anywhere**, and the release it ran
 * against is not necessarily the release this story targets. So the sentence stops at the failure and
 * the sentiment, and the readiness screen — which does know — is where the verdict lives. Asserting a
 * version here would be the one wrong number on a panel built to keep two of them honest.
 *
 * Absent rather than replaced by a green line when nothing failed: the result column already says
 * where each case stands, and a foot on every visit is chrome the reader learns to skip past the one
 * time it matters (`ReleaseDetailPage`'s banner takes the same decision for the same reason).
 */
function FailingFoot({ rows }: { rows: readonly IssueTestCase[] }) {
  const failed = rows.filter((row) => row.lastResultKind === 'FAILED');
  if (failed.length === 0) return null;

  return (
    <p
      className="m-0 mt-2 text-[11.5px] leading-[1.55] text-[color:var(--color-failed)]"
      data-testid={`${TEST_ID}.Failing`}
    >
      <span className="identifier">{failed.map((row) => row.caseKey).join(', ')}</span>
      {failed.length === 1 ? ' failed in its latest run' : ' failed in their latest runs'}
      {', so a release measured over this story is not ready even though the story would otherwise be done. "Done" and "proven" are the same screen.'}
    </p>
  );
}

/**
 * Picking the cases — a **`PUT` of the whole set**, not add/remove pairs.
 *
 * The dialog opens holding what is linked and saves what is in the picker, which is the shape the
 * endpoint declares and `IssueLabelsInput`'s reason for it: the control is a multi-select whose state
 * *is* the set, and pairs let a stale client unlink a case somebody else had just linked while this
 * dialog was open.
 *
 * The option list is `useCases` with the typed term — server-filtered, twenty at a time, and queried
 * with no term as well as with one for `PlanCasesTab`'s reason: the first twenty cases are a useful
 * thing for a picker to open on, and a control that stays empty until two characters are typed reads
 * as broken to somebody who does not yet know a key.
 *
 * **The linked set is merged into the options.** A case that is linked and outside the current page
 * of search results would otherwise vanish from its own chip the moment somebody typed — `MultiPicker`
 * renders `selected` from what it was given, and `serverFiltered` means the list is not the whole
 * catalogue.
 */
function LinkCasesDialog({
  issue,
  linked,
  onClose,
}: {
  issue: Issue;
  linked: readonly IssueTestCase[];
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState<Pick<IssueTestCase, 'caseId' | 'caseKey' | 'title'>[]>(() =>
    linked.map((row) => ({ caseId: row.caseId, caseKey: row.caseKey, title: row.title })),
  );
  const search = useCases(issue.projectId, { q: query.trim() || undefined, size: 20 }, 'key,asc');
  const write = usePutIssueTestCases(issue.projectId);

  const held = new Set(chosen.map((row) => row.caseId));
  const options = [
    ...chosen,
    ...(search.data?.items ?? [])
      .filter((row) => !held.has(row.id))
      .map((row) => ({ caseId: row.id, caseKey: row.key, title: row.title })),
  ];

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={`${TEST_ID}.Dialog`}>
        <DialogHeader>
          <DialogTitle>Link test cases</DialogTitle>
        </DialogHeader>

        <Field
          label="Cases that prove this story"
          htmlFor="link-test-cases"
          help="By key or title. The whole set is saved, so removing a chip unlinks that case."
        >
          <MultiPicker
            id="link-test-cases"
            options={options}
            selected={chosen}
            onChange={setChosen}
            optionKey={(option) => option.caseId}
            optionLabel={(option) => `${option.caseKey} ${option.title}`}
            optionText={(option) => `${option.caseKey} ${option.title}`}
            query={query}
            onQueryChange={setQuery}
            serverFiltered
            emptyLabel={search.isPending ? 'Searching…' : 'No case matches.'}
            data-testid={`${TEST_ID}.Dialog.Picker`}
          />
        </Field>

        <p className="m-0 text-[11.5px] leading-[1.5] text-ink-4">
          A release measures readiness over these cases. Linking one does not run it — what counts is
          the latest recorded result.
        </p>

        {write.error && (
          <Banner tone="bad" data-testid={`${TEST_ID}.Dialog.Error`}>
            <span>{describeError(write.error).title}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TEST_ID}.Dialog.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={write.isPending}
            onClick={() =>
              write.mutate(
                { key: issue.key, input: { caseIds: chosen.map((row) => row.caseId) } },
                { onSuccess: onClose },
              )
            }
            data-testid={`${TEST_ID}.Dialog.Save`}
          >
            {write.isPending ? 'Saving…' : `Save ${plural(chosen.length, 'case')}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
