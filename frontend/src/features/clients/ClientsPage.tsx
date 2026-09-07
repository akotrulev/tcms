import { useState } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { Building2 } from 'lucide-react';
import { useClients } from '../../api/queries';
import type { ClientStatus } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { Button } from '../../components/ui';
import { clientStatusLabels, clientStatusTones } from '../../lib/labels';
import { plural } from '../../lib/format';
import { ClientCreateDialog } from './ClientCreateDialog';

/**
 * `clients.html`, and `clients-empty.html` — which is the same screen and the more important half.
 *
 * <h2>The empty state IS the module</h2>
 *
 * Zero clients is a **first-class, complete state** (scope D1 rule 2), not a list waiting to be
 * filled. A company building its own products never records one, and this page is *the only trace*
 * of clients such a company ever sees — every other client surface in the product renders nothing
 * at all. So the empty arm carries `clients-empty.html`'s copy rather than a generic "no records":
 * it has to say what a client is *for*, and then say that never needing one is fine. A reader who
 * lands here and leaves without creating anything has used this screen correctly.
 *
 * That is also why the page is reachable rather than hidden. `solo-overview.html` states it from
 * the other end: *"The Clients entry stays in the sidebar so the first one can still be created —
 * that is the only trace."*
 *
 * <h2>What this screen does NOT draw, and both absences are decisions</h2>
 *
 * The mockup's **Hours to date** and **Last invoice** columns are M8's (018 D-g). Hours are
 * reachable now that M6 has landed, but the two columns sit in one table of which M8 owns the
 * money half, and a table built with two of its four columns reads as a defect rather than as a
 * boundary. Story C3 words the deferral itself: *"and — once time and billing exist — hours and
 * invoices to date."*
 *
 * The **Account manager** filter the mockup draws is absent for a different reason: the endpoint
 * takes `accountManagerId`, but nothing on this screen can turn a name into one — the company
 * member list is `staffing:directory`'s, which a Company Admin holding `client:read` need not have.
 * A picker that could not be populated is worse than none.
 */

const TEST_ID = 'Clients';

const STATUSES: readonly (ClientStatus | 'ALL')[] = ['ACTIVE', 'ARCHIVED', 'ALL'];

export function ClientsPage() {
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);

  /*
    In the URL rather than in `useState`, this codebase's rule for filter state: somebody who
    switched to Archived to find a client they retired last spring sends the link on, and component
    state sends a list that client is not in.
  */
  const asked = params.get('status');
  const status = STATUSES.find((value) => value === asked) ?? 'ACTIVE';

  const gate = useCanInCompany();
  const allowed = gate.can('client:read');
  const clients = useClients({ status, enabled: allowed });

  const setStatus = (next: ClientStatus | 'ALL') =>
    setParams((current) => {
      const updated = new URLSearchParams(current);
      if (next === 'ACTIVE') updated.delete('status');
      else updated.set('status', next);
      return updated;
    });

  const total = clients.data?.length;
  const projects = clients.data?.reduce((sum, client) => sum + client.projectCount, 0);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Clients"
        documentTitle="Clients"
        subtitle={
          total === undefined || total === 0 ?
            'Organisations this company delivers software for.'
          : `${plural(total, 'client')}, ${plural(projects ?? 0, 'project')} between them.`
        }
        actions={
          allowed && gate.can('client:create') && total !== undefined && total > 0 ?
            <Button size="sm" onClick={() => setCreating(true)} data-testid={`${TEST_ID}.Add`}>
              Add client
            </Button>
          : undefined
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
            : 'Clients are recorded by Account Managers and Company Admins. Ask one of them if a project you run should be attached to a customer.'
            }
          </p>
        </Panel>
      : <>
          {/*
            Above the region, so a failed read leaves the control on screen and the reader can
            switch or retry without a reload. Only rendered once a client exists: a status filter
            over an empty list is a control that can only ever return to where it started.
          */}
          {total !== undefined && total > 0 ?
            <div className="mb-3 flex items-center gap-2" data-testid={`${TEST_ID}.Filters`}>
              <span className="text-[12.5px] text-ink-3">Status</span>
              {STATUSES.map((value) => (
                <Button
                  key={value}
                  size="sm"
                  variant={value === status ? 'primary' : 'quiet'}
                  onClick={() => setStatus(value)}
                  data-testid={`${TEST_ID}.Status.${value}`}
                >
                  {value === 'ALL' ? 'All' : clientStatusLabels[value]}
                </Button>
              ))}
            </div>
          : null}

          <DataRegion
            testId={`${TEST_ID}.Region`}
            state={{ ...clients, refetch: () => void clients.refetch() }}
            isEmpty={(data) => data.length === 0}
            skeleton={<GridSkeleton rows={3} />}
            empty={
              status === 'ACTIVE' ?
                {
                  icon: Building2,
                  kind: 'no-records',
                  title: 'No clients yet',
                  body: 'A client is an organisation your company delivers software for. Recording one lets a project say who it is for, so hours, budgets and — later — invoices roll up to the right customer. A client never signs in and never sees anything here. If you build your own products, you may never need one — nothing in the product will ask you again.',
                  action:
                    gate.can('client:create') ?
                      <Button size="sm" onClick={() => setCreating(true)} data-testid={`${TEST_ID}.AddFirst`}>
                        Add client
                      </Button>
                    : undefined,
                }
              : {
                  kind: 'no-filter-match',
                  title: 'No clients with that status',
                  body: 'Switch back to Active, or to All, to see the rest.',
                  onClearFilters: () => setStatus('ACTIVE'),
                }
            }
          >
            {(rows) => (
              <Table data-testid={`${TEST_ID}.Table`}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Client</TableHeaderCell>
                    <TableHeaderCell>Projects</TableHeaderCell>
                    <TableHeaderCell>Contacts</TableHeaderCell>
                    <TableHeaderCell>Account manager</TableHeaderCell>
                    <TableHeaderCell>Status</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((client) => (
                    <TableRow key={client.id} data-testid={`${TEST_ID}.Row.${client.id}`}>
                      <TableCell>
                        <RouterLink
                          className="font-medium text-ink underline-offset-2 hover:underline"
                          to={`/clients/${client.id}`}
                          data-testid={`${TEST_ID}.Name.${client.id}`}
                        >
                          {client.name}
                        </RouterLink>
                        {client.location !== null || client.industry !== null ?
                          <p className="m-0 mt-0.5 text-[12px] text-ink-4">
                            {[client.location, client.industry].filter(Boolean).join(' · ')}
                          </p>
                        : null}
                      </TableCell>
                      <TableCell className="tabular-nums">{client.projectCount}</TableCell>
                      <TableCell className="tabular-nums">{client.contactCount}</TableCell>
                      <TableCell>{client.accountManager?.displayName ?? '—'}</TableCell>
                      <TableCell>
                        <Badge tone={clientStatusTones[client.status]}>
                          {clientStatusLabels[client.status]}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </DataRegion>
        </>
      }

      <ClientCreateDialog open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}
