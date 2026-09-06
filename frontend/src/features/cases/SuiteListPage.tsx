import { Link as RouterLink, useParams } from 'react-router-dom';
import { useProject, useSuites } from '../../api/queries';
import { TABS } from '../../app/nav';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { TabStrip } from '../../components/TabStrip';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { formatCount, plural } from '../../lib/format';

/**
 * `/projects/:projectId/suites` — **the Suites tab**, and the repair of brief finding §8.6.
 *
 * Until now a suite had *no navigation presence at all*: no rail entry, no route, no address. It
 * was a panel on the left of the case list, so the only way to talk about one was to send somebody
 * a filtered case URL and hope they noticed which row was selected — and `…/suites/:id/history`
 * existed the whole time, a detail route under a list route that did not.
 *
 * Two routes fix it and neither is new machinery:
 *
 *   - **this page**, the list, at `…/suites`; and
 *   - `…/suites/:suiteId`, which is `CaseListPage` with that suite selected — *the same screen*,
 *     addressed. A suite is a named collection of cases, so its "detail view" is the case list
 *     scoped to it; a second grid rendering the same rows would be a second place for the filters,
 *     the bulk actions and the empty states to drift.
 *
 * ## The three counts, and why they do not add up
 *
 * A suite's membership is three arms unioned — cases **filed** in it, cases **added** to it
 * individually, and every live case carrying one of its **tags**. A case can be in all three, so
 * the arms overlap and `caseCount` is smaller than their sum. The contract says a client showing
 * both must say so rather than leave a reader to discover it by subtraction, which is what the
 * foot line and the arm chips are for.
 */
export function SuiteListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const suites = useSuites(projectId);

  return (
    <div>
      <PageHeader
        testId="Suites.Header"
        title="Test cases"
        documentTitle={project.data ? `Suites · ${project.data.name}` : 'Suites'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          { label: 'Suites' },
        ]}
        subtitle="A suite is a flat, named collection — not a folder in a tree. Its members are the cases filed in it, added to it, or carrying one of its tags."
      />

      <TabStrip tabs={TABS.cases} projectId={projectId} label="Test case views" testId="Cases.Tabs" />

      <section data-testid="Suites.Panel">
        <DataRegion
          testId="Suites.List"
          state={{ ...suites, refetch: () => void suites.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No suites yet',
            /*
              The create control lives on the case list's own suite panel, which owns the suite
              writes because that is where they are visible. Naming it here rather than duplicating
              the dialog keeps one writer for the three operations.
            */
            body: 'Suites are created from the suite panel on the Cases tab. A project with none is not broken — every case is simply unfiled.',
          }}
        >
          {(rows) => (
            <>
              <ScrollX>
                <Table data-testid="Suites.Grid">
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell className="w-[110px]">Key</TableHeaderCell>
                      <TableHeaderCell>Suite</TableHeaderCell>
                      <TableHeaderCell align="right" className="w-[90px]">
                        Cases
                      </TableHeaderCell>
                      <TableHeaderCell>Members</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((suite) => (
                      <TableRow key={suite.id} data-testid="Suites.Row">
                        <TableCell>
                          <Identifier variant="muted">{suite.key}</Identifier>
                        </TableCell>
                        <TableCell>
                          <RouterLink
                            to={`/projects/${projectId}/suites/${suite.id}`}
                            data-testid="Suites.Row.Link"
                            className="text-ink no-underline hover:underline"
                          >
                            {suite.name}
                          </RouterLink>
                        </TableCell>
                        <TableCell align="right">
                          <span className="font-mono tabular-nums">
                            {formatCount(suite.caseCount)}
                          </span>
                        </TableCell>
                        <TableCell>
                          {/*
                            The three arms, drawn only where they are non-zero. A row of
                            `filed 0 · added 0 · tagged 0` is three facts nobody asked for; the
                            arms that carry cases are the ones that explain the total.
                          */}
                          <span className="flex flex-wrap gap-1">
                            {/*
                              `Badge`, not `Chip`: a chip is a value the reader OWNS and the kit's
                              type makes that unrepresentable otherwise — `onClick` is required on
                              it. These three are read-only facts about the row.
                            */}
                            {suite.filedCount > 0 && (
                              <Badge tone="plain">filed {formatCount(suite.filedCount)}</Badge>
                            )}
                            {suite.addedCount > 0 && (
                              <Badge tone="plain">added {formatCount(suite.addedCount)}</Badge>
                            )}
                            {suite.taggedCount > 0 && (
                              <Badge tone="plain">tagged {formatCount(suite.taggedCount)}</Badge>
                            )}
                          </span>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </ScrollX>
              <p className="mt-2 mb-0 max-w-[74ch] text-[12px] text-ink-3">
                {plural(rows.length, 'suite')}. The three member counts <strong>overlap</strong> —
                a case filed in one suite can be tagged into another — so they do not sum to the
                project&rsquo;s case count and are not meant to.
              </p>
            </>
          )}
        </DataRegion>
      </section>
    </div>
  );
}
