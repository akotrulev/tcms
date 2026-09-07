import { Link as RouterLink, useParams } from 'react-router-dom';
import { useClient } from '../../api/queries';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { clientStatusLabels, clientStatusTones } from '../../lib/labels';
import { BillingSlot } from './BillingSlot';
import { ClientRateCardPanel, ClientRateCardRefusal } from './ClientRateCardPanel';
import { ClientContactsPanel } from './ClientContactsPanel';

/**
 * `client.html` — the client, the projects attached to it, its contacts, and what its hours are
 * worth.
 *
 * <h2>~~No tile row, where the mockup draws three~~ → two of the three landed with M8</h2>
 *
 * ~~Two of the three are *Approved hours to date* and *Invoices*, both M8's (018 D-g) — a headline
 * number this page cannot source is the defect plan 009 §9.3 names. That leaves Projects alone, and
 * a row of one tile reads as two that failed to load rather than as one that is all there is.~~ →
 * **superseded 2026-09-05 (019 T6).** `GET /clients/{clientId}/billing` serves both, so
 * {@link BillingSlot} draws them **above its own table** rather than in a page-wide row: they are
 * that read's answer and share its failure, and a tile that blanked while the panel beneath it
 * rendered would be the two-contradictory-numbers defect one screen further on. The Projects count
 * stays in that panel's own title, where it is a fact about the list under it.
 *
 * <h2>The projects table is four columns, and the shortfall is the contract's</h2>
 *
 * The mockup draws Lead, Next release, Hours and Completion beside them. None is on any project
 * read shape today — they sit on plan 008 T19's deferred list — so `ClientProject` carries what
 * `Project` already serves and no more. The same decision `ProjectsPage` records for its two
 * undrawn bars.
 *
 * <h2>The contracts panel is absent and belongs to no module</h2>
 *
 * `client.html` draws an MSA and a SOW with signed dates. There is no table for them in Mc's
 * schema and none in M8's declared six, so it is a mockup element nobody owns (018 D-h) — recorded
 * here rather than left to read as an omission.
 */

const TEST_ID = 'Client';

export function ClientPage() {
  const { clientId } = useParams<{ clientId: string }>();
  const gate = useCanInCompany();
  const allowed = gate.can('client:read');
  const client = useClient(allowed ? clientId : undefined);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={client.data?.client.name ?? 'Client'}
        documentTitle={client.data?.client.name}
        subtitle={
          client.data === undefined ? undefined : (
            [client.data.client.location, client.data.client.industry].filter(Boolean).join(' · ') ||
            undefined
          )
        }
        meta={
          client.data === undefined ? undefined : (
            <span className="flex items-center gap-2">
              <Badge tone={clientStatusTones[client.data.client.status]}>
                {clientStatusLabels[client.data.client.status]}
              </Badge>
              <Identifier variant="muted">{client.data.client.id}</Identifier>
            </span>
          )
        }
      />

      {!allowed ?
        <Panel testId={`${TEST_ID}.Denied`}>
          <p className="m-0 text-[13px] text-ink-3" data-testid={`${TEST_ID}.DeniedText`}>
            {gate.pending ? 'Checking your permissions…' : 'Clients are visible to Account Managers, Finance and Company Admins.'}
          </p>
        </Panel>
      : <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...client, refetch: () => void client.refetch() }}
        >
          {(detail) => (
            /*
              The mockup's two-column grid, arrived at with M8: the rate card is a narrow panel
              beside the tall ones rather than a fourth block under them, which is where
              `client.html` draws it and where `billing.html` draws its own side column.
            */
            <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
              <div className="flex flex-col gap-4">
                <Panel title={`Projects (${detail.projects.length})`} testId={`${TEST_ID}.Projects`}>
                  {detail.projects.length === 0 ?
                    <p className="m-0 px-3.5 py-3 text-[12.5px] text-ink-3" data-testid={`${TEST_ID}.Projects.None`}>
                      No project names this client yet. The Client field is on a project’s General
                      settings, and setting it needs permission to change that project.
                    </p>
                  : <Table data-testid={`${TEST_ID}.Projects.Table`}>
                      <TableHead>
                        <TableRow>
                          <TableHeaderCell>Project</TableHeaderCell>
                          <TableHeaderCell>Key</TableHeaderCell>
                          <TableHeaderCell>State</TableHeaderCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {detail.projects.map((project) => (
                          <TableRow key={project.id} data-testid={`${TEST_ID}.Project.${project.id}`}>
                            <TableCell>
                              <RouterLink
                                className="text-ink underline-offset-2 hover:underline"
                                to={`/projects/${project.id}`}
                              >
                                {project.name}
                              </RouterLink>
                            </TableCell>
                            <TableCell>
                              <Identifier variant="strong">{project.key}</Identifier>
                            </TableCell>
                            <TableCell>{project.archived ? 'Archived' : 'Active'}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  }
                  <p
                    className="m-0 border-t border-line px-3.5 py-2.5 text-[11.5px] leading-[1.55] text-ink-4"
                    data-testid={`${TEST_ID}.Projects.Note`}
                  >
                    One client, many projects. A project has at most one client — or none.
                  </p>
                </Panel>

                <ClientContactsPanel
                  clientId={detail.client.id}
                  contacts={detail.contacts}
                  canEdit={gate.can('client:edit')}
                />

                <BillingSlot clientId={detail.client.id} />
              </div>

              <div className="flex flex-col gap-4">
                {/*
                  Two components rather than one with a `canRead` prop, so the refusal fires no
                  request at all — every billing read is `enabled`-gated on its key rather than
                  allowed to 403 (`api/queries/billing.ts`' header). The panel and the refusal are
                  the same heading either way, which is what makes the boundary legible: an Account
                  Manager is told rates exist and are not theirs, not that the product has none.
                */}
                {gate.can('ratecard:read') ?
                  <ClientRateCardPanel clientId={detail.client.id} />
                : <ClientRateCardRefusal />}
              </div>
            </div>
          )}
        </DataRegion>
      }
    </div>
  );
}
