import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { UsersRound } from 'lucide-react';
import { useProject, useProjectStaffing } from '../../api/queries';
import type { Allocation } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableFoot,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';
import { AllocationDialog } from './AllocationDialog';
import { AllocationRow, DeleteAllocationDialog } from './AllocationRow';
import { NO_PROFILE, allocationLabel, capacityLabel } from './capacityCopy';

/**
 * `/projects/:projectId/staffing` — story S1's *"I open a project's staffing"*.
 *
 * <h2>This screen names no other project, and that is the whole of 017 D-f</h2>
 *
 * Each person's row carries their **company-wide total** percentage and the over-allocation flag,
 * and nothing else about where the rest of that percentage went. A Project Manager holds
 * `staffing:read` on their own projects and no company-wide key, so listing *"Acme Loyalty 50% +
 * Bergstrom Portal 30%"* here would disclose, through a project-scope grant, the existence and
 * staffing of projects they cannot see.
 *
 * The number on its own is what makes the flag actionable — *this person is already at 130%, do not
 * add to them* — and the breakdown is `/staffing`'s, under `staffing:directory`. The sentence under
 * the table says the number spans projects the reader may not be able to open, because a total that
 * does not add up to the rows above it is otherwise a figure that looks wrong.
 *
 * <h2>The read is not pre-gated, and the two company screens are, and the difference is the copy</h2>
 *
 * `/staffing` and `/directory` check `staffing:directory` before they render, because their refusal
 * has something bespoke to say — *open that project's Staffing* — that no generic refusal can. Here
 * there is nothing bespoke: a caller without `staffing:read` gets `DataRegion`'s Refused state,
 * which names the permission the server refused on and offers the org chart to find somebody who
 * can grant it. That is strictly better copy than a hand-written panel, and it is this file's
 * standing shape for a project screen — routed for everybody, gated on the controls (§31).
 *
 * <h2>There is no range picker, and the window is the server's</h2>
 *
 * `from`/`to` are omitted, so `GET /projects/{id}/staffing` applies its own default — the first of
 * this month, three months on — and the subtitle names the window out of the *answer*. A picker
 * here would be a second copy of `StaffingPage`'s private range arithmetic, and the question this
 * screen answers is *who is on this project now*; the dialog's dates are free-form either way, so
 * nothing about allocating is reachable only through a wider window.
 */

const TEST_ID = 'ProjectStaffing';

export function ProjectStaffingPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const view = useProjectStaffing(projectId, '', '');

  /** `'new'` opens an empty dialog; an allocation opens it on that row. */
  const [editing, setEditing] = useState<Allocation | 'new' | null>(null);
  const [removing, setRemoving] = useState<Allocation | null>(null);

  const window = view.data ? { from: view.data.from, to: view.data.to } : null;

  const allocate = (label: string, testId: string) => (
    <PermissionGate projectId={projectId} permission="staffing:allocate">
      {({ allowed }) => (
        <Button
          size="sm"
          variant="primary"
          disabled={!allowed}
          onClick={() => setEditing('new')}
          data-testid={testId}
        >
          {label}
        </Button>
      )}
    </PermissionGate>
  );

  return (
    <div>
      {/*
        ~~`Staffing`~~ → **`Team`** (021 §8.1). The rail carried that one word twice, at two scopes,
        for two different screens — this one and `/staffing`, the company view — which is the
        duplicate-label defect the brief found. This half is *who is on this project*; the company
        half is `Capacity`. Neither route moves.
      */}
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Team"
        documentTitle={project.data ? `Team · ${project.data.name}` : 'Team'}
        subtitle={
          window ?
            `This project · ${formatDate(window.from)} – ${formatDate(window.to)}`
          : 'Who is on this project, at what percentage, and for how long.'
        }
        actions={allocate('+ Allocate', `${TEST_ID}.Allocate`)}
      />

      <Panel testId={`${TEST_ID}.Allocations`} title="Allocations" dense>
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...view, refetch: () => void view.refetch() }}
          isEmpty={(data) => data.allocations.length === 0}
          empty={{
            kind: 'no-records',
            icon: UsersRound,
            title: 'Nobody is allocated to this project',
            body:
              window ?
                `No allocation covers ${formatDate(window.from)} – ${formatDate(window.to)}. Allocate somebody at the percentage of their week this project should have.`
              : 'Allocate somebody at the percentage of their week this project should have.',
            action: allocate('Allocate somebody', `${TEST_ID}.Empty.Allocate`),
          }}
          skeleton={<GridSkeleton rows={4} />}
        >
          {(loaded) => (
            <>
              <ScrollX>
                <Table data-testid={`${TEST_ID}.Table`}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Person</TableHeaderCell>
                      <TableHeaderCell>Share</TableHeaderCell>
                      <TableHeaderCell>From</TableHeaderCell>
                      <TableHeaderCell>To</TableHeaderCell>
                      <TableHeaderCell>Note</TableHeaderCell>
                      <TableHeaderCell> </TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {loaded.allocations.map((allocation) => (
                      <AllocationRow
                        key={allocation.id}
                        projectId={projectId}
                        allocation={allocation}
                        onEdit={() => setEditing(allocation)}
                        onRemove={() => setRemoving(allocation)}
                        testId={`${TEST_ID}.Row.${allocation.id}`}
                      />
                    ))}
                  </TableBody>
                </Table>
              </ScrollX>

              <TableFoot
                data-testid={`${TEST_ID}.Foot`}
                summary={plural(loaded.allocations.length, 'allocation')}
              />
            </>
          )}
        </DataRegion>
      </Panel>

      {/*
        The summary, and it is a SECOND rendering of the same answer rather than a second read: the
        endpoint serves `people` beside `allocations` precisely so the total and the flag arrive
        together with the rows they are about. Rendered only when there is somebody to summarise —
        an empty panel under an empty table would be the same nothing said twice.
      */}
      {view.data && view.data.people.length > 0 && (
        <div className="mt-4">
          <Panel
            testId={`${TEST_ID}.Load`}
            title="Company-wide load"
            actions={
              <span className="text-[11.5px] text-ink-4">this project, and everything else</span>
            }
          >
            <ScrollX>
              <Table data-testid={`${TEST_ID}.Load.Table`}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Person</TableHeaderCell>
                    <TableHeaderCell>Here</TableHeaderCell>
                    <TableHeaderCell>Everywhere</TableHeaderCell>
                    <TableHeaderCell>Capacity in this range</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {view.data.people.map((person) => (
                    <TableRow key={person.person.id} data-testid={`${TEST_ID}.Load.${person.person.id}`}>
                      <TableCell variant="title">{person.person.displayName}</TableCell>
                      <TableCell>{allocationLabel(person.percentOnThisProject)}</TableCell>
                      <TableCell>
                        {/*
                          `overAllocated` is SERVED, computed from the peak day (017 D-e). A
                          client-side `totalPercent > 100` here would be a second rule that silently
                          overrides the server's the day the threshold moves — `overAllocationLabel`
                          refuses to re-derive it for the same reason one file over.
                        */}
                        <span title="Across every project, including ones you cannot see">
                          <Badge
                            tone={person.overAllocated ? 'failed' : 'plain'}
                            data-testid={`${TEST_ID}.Load.${person.person.id}.Total`}
                          >
                            {`${person.totalPercent}% allocated`}
                          </Badge>
                        </span>
                      </TableCell>
                      <TableCell>
                        {/*
                          `ProjectStaffingPerson` carries no `profile`, so the null in `capacityHours`
                          is the only evidence there is one — which is exactly the state `capacityLabel`
                          exists to keep out of `0h`. Routed through it rather than through
                          `formatHours` directly so the em dash is one character in one place.
                        */}
                        <span title={person.capacityHours === null ? NO_PROFILE : undefined}>
                          {capacityLabel(person.capacityHours, person.capacityHours !== null)}
                        </span>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollX>

            <p
              className="mt-3 mb-0 text-[11.5px] leading-[1.55] text-ink-3"
              data-testid={`${TEST_ID}.Load.Basis`}
            >
              <strong className="font-semibold">Everywhere</strong> is the whole company. It counts
              projects you cannot open, so a total larger than the share above it is the number
              working, not a mistake — and it is the number to look at before adding to somebody.
            </p>
          </Panel>
        </div>
      )}

      {editing !== null && (
        <AllocationDialog
          projectId={projectId}
          allocation={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {removing !== null && (
        <DeleteAllocationDialog
          projectId={projectId}
          allocation={removing}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}
