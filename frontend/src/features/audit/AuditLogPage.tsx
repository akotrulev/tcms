import {
  Box,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined';
import { useParams } from 'react-router-dom';
import { useCompanyAuditEvents, useProject, useProjectAuditEvents } from '../../api/queries';
import type { AuditEvent } from '../../api/types';
import { useCan, useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { formatAge, formatDateTime } from '../../lib/format';
import { auditActionLabel, auditTargetTypeLabel } from '../../lib/labels';
import { useAuditFilters } from './useAuditFilters';

/**
 * **The audit trail** (§242) — what happened here, and who did it.
 *
 * One component for two routes, because the two trails differ in their *gate* and in nothing else.
 * `/projects/:projectId/audit` costs `project:audit` and shows that project's acts;
 * `/admin/audit` costs `audit:view` at company scope and shows every act in the company,
 * project-scoped rows included. Two components would be two renderings of the same twelve columns
 * with nothing keeping them identical, which is what the shared-layer rule exists to prevent.
 *
 * ## A refusal is shown, not hidden
 *
 * Both endpoints answer 403 rather than an empty page, and this screen says so in words. That is
 * the whole reason the server was built that way: an empty audit trail reads as *"nothing
 * happened"*, which is a false statement about the past that the reader cannot detect, on the one
 * screen whose entire purpose is to be trusted about the past. So the fetch is gated on the
 * permission and the denial renders as a sentence naming who to ask.
 *
 * ## Both actors are rendered, always
 *
 * A token-driven act carries **both** `actorUserId` and `actorTokenId` — the person answerable for
 * the credential, and the credential. Showing one and hiding the other loses half of who did it,
 * so the person is named and the credential is a marker beside them. `actorUserId` is null only
 * for an unattended job, and a null *name* on a non-null id means the membership is gone entirely
 * — not that the person left, which does not take the name away.
 *
 * ## What this screen is not
 *
 * It does not read the **instance** trail (`instance_audit_event`, `instance:audit`). That table
 * records what the *operator* did across companies, has no company on it, and belongs to the M6
 * operator console. There is no export either — `report:export` is a different permission and a
 * report type is a different slice.
 */
export function AuditLogPage() {
  const { projectId } = useParams();
  const scope = projectId ? 'project' : 'company';

  const { filters, hasActiveFilters, setFilter, setPage, setSize, clearFilters } = useAuditFilters();

  const projectGate = useCan(projectId);
  const companyGate = useCanInCompany(scope === 'company');
  const allowed =
    scope === 'project' ? projectGate.can('project:audit') : companyGate.can('audit:view');
  const pending = scope === 'project' ? projectGate.pending : companyGate.pending;

  // Undefined on `/admin/audit`, where the hook's own `enabled` gate keeps it from firing.
  const project = useProject(projectId);

  // Exactly one of these ever runs: the other's `enabled` is false for the whole life of the
  // screen, because `scope` is decided by the route. Both hooks are called unconditionally —
  // that is the rule about hooks, not a preference — and `enabled` is what makes only one fetch.
  const projectTrail = useProjectAuditEvents(
    projectId ?? '',
    filters,
    scope === 'project' && allowed,
  );
  const companyTrail = useCompanyAuditEvents(filters, scope === 'company' && allowed);
  const trail = scope === 'project' ? projectTrail : companyTrail;

  const title = scope === 'project' ? 'Project audit trail' : 'Company audit trail';

  return (
    <Box>
      <PageHeader
        testId="Audit.Header"
        title={title}
        documentTitle="Audit trail"
        crumbs={
          scope === 'project'
            ? [
                { label: 'Projects', to: '/projects' },
                { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
                { label: 'Audit' },
              ]
            : [{ label: 'Administration' }, { label: 'Audit' }]
        }
        subtitle={
          scope === 'project'
            ? 'Every recorded act in this project, newest first. Company-wide acts appear on the company trail.'
            : 'Every recorded act in this company, newest first — this project’s and every other’s.'
        }
      />

      {!allowed ? (
        <Panel testId="Audit.Denied">
          <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="Audit.DeniedText">
            {pending
              ? 'Checking your permissions…'
              : scope === 'project'
                ? 'Reading this project’s audit trail is part of administering the project. Ask a test lead or a company administrator.'
                : 'The company audit trail is available to company administrators. Ask one of them.'}
          </Typography>
        </Panel>
      ) : (
        <>
          {/*
            The filters sit ABOVE the region rather than inside it, so a failed load leaves them
            usable — the reader can narrow or clear and try again without a reload. DataRegion's
            own header states the rule.
          */}
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            sx={{ gap: 2, mb: 2 }}
            data-testid="Audit.Filters"
          >
            <TextField
              size="small"
              label="Action"
              placeholder="case.updated"
              value={filters.action ?? ''}
              onChange={(event) => setFilter('action', event.target.value || undefined)}
              sx={{ minWidth: 220 }}
              slotProps={{ htmlInput: { 'data-testid': 'Audit.Filter.Action' } }}
            />
            <TextField
              size="small"
              type="date"
              label="From"
              value={(filters.occurredAfter ?? '').slice(0, 10)}
              onChange={(event) =>
                // A date control gives a day; the contract wants an instant. Widen to the whole
                // day at both ends so "4th to 9th" includes both, which is what a person means.
                setFilter(
                  'occurredAfter',
                  event.target.value ? `${event.target.value}T00:00:00Z` : undefined,
                )
              }
              slotProps={{
                inputLabel: { shrink: true },
                htmlInput: { 'data-testid': 'Audit.Filter.After' },
              }}
            />
            <TextField
              size="small"
              type="date"
              label="To"
              value={(filters.occurredBefore ?? '').slice(0, 10)}
              onChange={(event) =>
                setFilter(
                  'occurredBefore',
                  event.target.value ? `${event.target.value}T23:59:59Z` : undefined,
                )
              }
              slotProps={{
                inputLabel: { shrink: true },
                htmlInput: { 'data-testid': 'Audit.Filter.Before' },
              }}
            />
          </Stack>

          <Panel testId="Audit.Panel" dense>
            <DataRegion
              testId="Audit.List"
              state={{ ...trail, refetch: () => void trail.refetch() }}
              isEmpty={(page) => page.items.length === 0}
              skeleton={<GridSkeleton />}
              empty={
                hasActiveFilters
                  ? {
                      kind: 'no-filter-match',
                      title: 'No acts match',
                      body: 'Nothing recorded here matches the current filters.',
                      onClearFilters: clearFilters,
                    }
                  : {
                      kind: 'no-records',
                      title: 'Nothing recorded yet',
                      body:
                        scope === 'project'
                          ? 'Acts against this project will appear here as they happen.'
                          : 'Acts in this company will appear here as they happen.',
                    }
              }
            >
              {(page) => (
                <>
                  <ScrollX>
                    <Table size="small" data-testid="Audit.Grid">
                      <TableHead>
                        <TableRow>
                          <TableCell sx={{ color: 'text.secondary', width: 180 }}>When</TableCell>
                          <TableCell sx={{ color: 'text.secondary', width: 240 }}>Who</TableCell>
                          <TableCell sx={{ color: 'text.secondary' }}>Act</TableCell>
                          <TableCell sx={{ color: 'text.secondary', width: 160 }}>On</TableCell>
                          <TableCell sx={{ color: 'text.secondary', width: 220 }}>Targets</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {page.items.map((row) => (
                          <TableRow key={row.id} data-testid="Audit.Row" hover>
                            <TableCell>
                              {/*
                                The age reads at a glance and the exact instant is a hover away —
                                CaseGrid's pairing. Seconds are shown because two acts a second
                                apart are a different story from two acts a minute apart, and this
                                is the screen where that difference is the question.
                              */}
                              <Tooltip title={formatDateTime(row.occurredAt, true)}>
                                <Box
                                  sx={{ color: 'text.secondary' }}
                                  data-testid="Audit.Row.When"
                                >
                                  {formatAge(row.occurredAt)}
                                </Box>
                              </Tooltip>
                            </TableCell>
                            <TableCell>
                              <Actor row={row} />
                            </TableCell>
                            <TableCell data-testid="Audit.Row.Action">
                              {auditActionLabel(row.action)}
                            </TableCell>
                            <TableCell data-testid="Audit.Row.TargetType">
                              {auditTargetTypeLabel(row.targetType)}
                            </TableCell>
                            <TableCell>
                              <Targets ids={row.targetIds} />
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </ScrollX>
                  <TablePagination
                    component="div"
                    count={page.total}
                    page={page.page}
                    rowsPerPage={page.size}
                    rowsPerPageOptions={[25, 50, 100]}
                    onPageChange={(_, next) => setPage(next)}
                    onRowsPerPageChange={(event) => setSize(Number(event.target.value))}
                    data-testid="Audit.Pager"
                  />
                </>
              )}
            </DataRegion>
          </Panel>
        </>
      )}
    </Box>
  );
}

/**
 * Who acted — the person, the credential, or neither.
 *
 * All three shapes are real and each renders differently. A person alone is a session. A person
 * *with* the robot marker is a pipeline acting under that person's credential, which reads
 * differently from "they typed it" and is exactly the distinction this table exists to preserve.
 * Neither is an unattended job, and it says so rather than blaming a person.
 */
function Actor({ row }: { row: AuditEvent }) {
  if (!row.actorUserId && !row.actorTokenId) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="Audit.Row.Actor">
        Automatic
      </Typography>
    );
  }

  return (
    <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75 }} data-testid="Audit.Row.Actor">
      {row.actorTokenId && (
        <Tooltip
          title={
            row.actorTokenName
              ? `Performed by the API token “${row.actorTokenName}”, not typed by this person.`
              : 'Performed by an API token this person owns, not typed by them.'
          }
        >
          <SmartToyOutlinedIcon
            fontSize="small"
            sx={{ color: 'text.secondary' }}
            data-testid="Audit.Row.ByToken"
          />
        </Tooltip>
      )}
      <Typography
        variant="body2"
        sx={{ color: row.actorUserName ? 'text.primary' : 'text.secondary' }}
      >
        {/*
          A null name on a non-null id means the membership no longer exists at all. Offboarding
          does NOT do that — a departed colleague still resolves — so this branch is rare and must
          not pretend to know who it was.
        */}
        {row.actorUserName ?? (row.actorUserId ? 'Former member' : 'API token')}
      </Typography>
    </Stack>
  );
}

/**
 * What was acted on.
 *
 * `targetIds` is an array because **one act may cover many** — a bulk operation over 143 cases is
 * one row reading "143 cases" rather than 143 lines (plan §6f). So the count is the headline and
 * the single-target case shows its id, which is the one a reader can actually do something with.
 */
function Targets({ ids }: { ids: string[] }) {
  if (ids.length === 0) {
    return <Box sx={{ color: 'text.secondary' }}>—</Box>;
  }
  if (ids.length === 1) {
    return (
      <Identifier variant="muted" testId="Audit.Row.Target">
        {ids[0]}
      </Identifier>
    );
  }
  return (
    <Typography variant="body2" data-testid="Audit.Row.Target">
      {ids.length} items
    </Typography>
  );
}
