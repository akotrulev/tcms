import {
  Alert,
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { ApiError } from '../../api/client';
import type { Role, RoleAssignment, Team } from '../../api/types';
import { useRevokeRole, useRoleAssignments, useRoles, useTeams, useUsers } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatAge, formatCount, formatDateTime } from '../../lib/format';
import { allPermissions, groupByArea, holdsEveryPermission } from '../../lib/permissions';
import { GrantRoleDialog } from './GrantRoleDialog';

/**
 * Teams, roles and the org chart (plan §6b, §6k).
 *
 * Three regions, each its own data query, so a failure in one leaves the others usable — the
 * role reference is worth reading even if the assignment list is down.
 *
 * Roles are shown with their **permissions expanded**. An admin deciding whether to grant
 * someone Test Engineer needs to see that it excludes final approval; a name alone doesn't
 * say that, and the resulting guess is how people end up over-privileged.
 *
 * ## This page became a write surface, and the assignment list is what makes it one
 *
 * The list is not "every grant in the company" — the server returns exactly the grants the caller
 * could revoke, which is a Test Lead's own project and nothing else. So every row here carries a
 * Revoke the server will accept, and the gate on it is scope-dependent for the same reason the
 * grant's is: `project:members` for a row naming a project, `company:members` for one that does
 * not.
 */
export function TeamsPage() {
  const teams = useTeams();
  const roles = useRoles();
  const assignments = useRoleAssignments();
  const users = useUsers();
  const [granting, setGranting] = useState(false);

  return (
    <Box>
      <PageHeader
        testId="Admin.Header"
        title="Teams & roles"
        documentTitle="Teams and roles"
        crumbs={[{ label: 'Administration' }, { label: 'Teams & roles' }]}
        subtitle="Team structure, who holds which role on which project, and what each role can do."
        actions={
          /*
            Deliberately UNGATED, and the one place in the app that is. The permission the server
            will check is chosen by a field inside the dialog — `project:members` on the project
            picked, or `company:members` for a company-wide grant — so the gate lives on the
            dialog's Grant button, where it can follow that choice. Gating this one would need the
            answer before the question had been asked, and would disable the whole feature for a
            Test Lead who may staff exactly one project. Opening a form refuses nothing; §31's rule
            is about the action, and the action is Grant.
          */
          <Button
            variant="contained"
            onClick={() => setGranting(true)}
            data-testid="Admin.Grant.Open"
          >
            Grant a role
          </Button>
        }
      />

      {granting && (
        <GrantRoleDialog open onClose={() => setGranting(false)} onGranted={() => setGranting(false)} />
      )}

      <Stack sx={{ gap: 3 }}>
        <Panel title="Team structure" testId="Admin.Teams">
          <DataRegion
            testId="Admin.Teams.List"
            state={{ ...teams, refetch: () => void teams.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{ kind: 'no-records', title: 'No teams', body: 'Create a team to group people and grant roles by team.' }}
          >
            {(rows) => <TeamTree teams={rows} />}
          </DataRegion>
        </Panel>

        <Panel title="Role assignments" testId="Admin.Assignments" dense>
          <DataRegion
            testId="Admin.Assignments.List"
            state={{ ...assignments, refetch: () => void assignments.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{ kind: 'no-records', title: 'No assignments', body: 'Nobody holds a project role yet.' }}
          >
            {(rows) => <AssignmentTable rows={rows} />}
          </DataRegion>
        </Panel>

        <Panel title="People" testId="Admin.Users" dense>
          <DataRegion
            testId="Admin.Users.List"
            state={{ ...users, refetch: () => void users.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{ kind: 'no-records', title: 'No accounts', body: 'No user accounts exist yet.' }}
          >
            {(rows) => (
              <Table size="small" data-testid="Admin.Users.Grid">
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ color: 'text.secondary' }}>Name</TableCell>
                    <TableCell sx={{ color: 'text.secondary' }}>Email</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 120 }}>Status</TableCell>
                    <TableCell sx={{ color: 'text.secondary' }}>Teams</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 140 }}>Last signed in</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {[...rows]
                    .sort(
                      (a, b) =>
                        // Deactivated accounts last — they're reference, not the working set.
                        Number(a.status === 'DEACTIVATED') - Number(b.status === 'DEACTIVATED') ||
                        a.displayName.localeCompare(b.displayName),
                    )
                    .map((user) => (
                      <TableRow key={user.id} data-testid="Admin.Users.Row" hover>
                        <TableCell sx={{ fontWeight: 500 }}>{user.displayName}</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>{user.email}</TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            label={user.status.toLowerCase()}
                            data-testid="Admin.Users.Row.Status"
                            sx={{
                              bgcolor: 'transparent',
                              color:
                                user.status === 'ACTIVE' ? 'var(--color-passed)' : 'var(--color-deprecated)',
                              border: `1px solid color-mix(in srgb, ${
                                user.status === 'ACTIVE' ? 'var(--color-passed)' : 'var(--color-deprecated)'
                              } 45%, transparent)`,
                            }}
                          />
                        </TableCell>
                        <TableCell sx={{ fontSize: '0.75rem' }}>
                          {user.teams.length === 0 ? (
                            <Box sx={{ color: 'text.secondary' }}>None</Box>
                          ) : (
                            user.teams.join(', ')
                          )}
                        </TableCell>
                        <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                          <Tooltip title={formatDateTime(user.lastLoginAt)}>
                            <Box component="span">{formatAge(user.lastLoginAt)}</Box>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                    ))}
                </TableBody>
              </Table>
            )}
          </DataRegion>
        </Panel>

        <Panel title="What each role can do" testId="Admin.Roles">
          <DataRegion
            testId="Admin.Roles.List"
            state={{ ...roles, refetch: () => void roles.refetch() }}
            isEmpty={(rows) => rows.length === 0}
          >
            {(rows) => <RoleList roles={rows} />}
          </DataRegion>
        </Panel>
      </Stack>
    </Box>
  );
}

/** The org chart, flattened into an indented list — depth reads as hierarchy without a diagram. */
function TeamTree({ teams }: { teams: Team[] }) {
  const tree = useMemo(() => {
    const byParent = new Map<string | null, Team[]>();
    for (const team of teams) {
      const key = team.parentTeamId ?? null;
      const bucket = byParent.get(key);
      if (bucket) bucket.push(team);
      else byParent.set(key, [team]);
    }
    const build = (parentId: string | null, depth: number): { team: Team; depth: number }[] =>
      [...(byParent.get(parentId) ?? [])]
        .sort((a, b) => a.name.localeCompare(b.name))
        .flatMap((team) => [{ team, depth }, ...build(team.id, depth + 1)]);
    return build(null, 0);
  }, [teams]);

  return (
    <Stack sx={{ gap: 1.5 }}>
      {tree.map(({ team, depth }) => (
        <Box
          key={team.id}
          data-testid="Admin.Teams.Item"
          sx={{
            ml: depth * 3,
            pl: 1.5,
            borderLeft: depth > 0 ? '2px solid' : 'none',
            borderColor: 'divider',
          }}
        >
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {team.name}
            </Typography>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              <Identifier variant="muted">{formatCount(team.members.length)}</Identifier>{' '}
              {team.members.length === 1 ? 'member' : 'members'}
            </Typography>
            {team.leads.map((lead) => (
              <Chip key={lead.id} size="small" variant="outlined" label={`lead: ${lead.displayName}`} />
            ))}
          </Stack>

          {team.description && (
            <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
              {team.description}
            </Typography>
          )}

          {team.projects.length > 0 && (
            <Stack direction="row" sx={{ gap: 0.5, mt: 0.5, flexWrap: 'wrap' }}>
              {team.projects.map((project) => (
                <Tooltip
                  key={`${project.projectId}-${project.roleName}`}
                  title={`Everyone in ${team.name} holds ${project.roleName} on ${project.projectName}`}
                >
                  <Chip
                    size="small"
                    label={`${project.projectName}: ${project.roleName}`}
                    data-testid="Admin.Teams.Item.ProjectRole"
                    sx={{
                      bgcolor: 'transparent',
                      color: 'var(--color-info)',
                      border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
                    }}
                  />
                </Tooltip>
              ))}
            </Stack>
          )}
        </Box>
      ))}
    </Stack>
  );
}

function AssignmentTable({ rows }: { rows: RoleAssignment[] }) {
  const revoke = useRevokeRole();
  /*
    Keyed by assignment id rather than held as one string. The self-lockout refusal belongs to the
    row that caused it — a single `error` would move the message onto whichever row was clicked
    next, which is how a warning about YOUR grant ends up under somebody else's name.
  */
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});

  const onRevoke = async (assignment: RoleAssignment) => {
    setRowErrors((current) => ({ ...current, [assignment.id]: '' }));
    try {
      await revoke.mutateAsync(assignment.id);
    } catch (error) {
      // The one refusal `PermissionGate` cannot predict: "you may not revoke the grant carrying
      // your own ability to revoke" is a fact about the ROW, not about the caller's permissions,
      // so it can only be surfaced where it happens.
      const message =
        error instanceof ApiError && error.fieldErrors?.assignmentId
          ? error.fieldErrors.assignmentId.join(' ')
          : describeError(error).body;
      setRowErrors((current) => ({ ...current, [assignment.id]: message }));
    }
  };

  return (
    <Table size="small" data-testid="Admin.Assignments.Grid">
      <TableHead>
        <TableRow>
          <TableCell sx={{ color: 'text.secondary' }}>Who</TableCell>
          <TableCell sx={{ color: 'text.secondary', width: 140 }}>Role</TableCell>
          <TableCell sx={{ color: 'text.secondary', width: 180 }}>Project</TableCell>
          <TableCell sx={{ color: 'text.secondary', width: 160 }}>Granted via</TableCell>
          <TableCell sx={{ color: 'text.secondary', width: 130 }}>Granted</TableCell>
          <TableCell sx={{ color: 'text.secondary', width: 110 }} align="right">
            {/* Header intentionally blank-labelled: the column holds one action per row. */}
            Access
          </TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {[...rows]
          // `projectName` is null for a system-scoped role (an Admin holds it instance-wide,
          // not on a project), so those sort first under an explicit label rather than
          // crashing the comparator or drifting to the bottom unexplained.
          .sort(
            (a, b) =>
              (a.projectName ?? '').localeCompare(b.projectName ?? '') ||
              (a.user?.displayName ?? '').localeCompare(b.user?.displayName ?? ''),
          )
          .map((assignment) => (
            <TableRow key={assignment.id} data-testid="Admin.Assignments.Row" hover>
              <TableCell sx={{ fontWeight: 500 }}>{assignment.user?.displayName ?? '—'}</TableCell>
              <TableCell>
                <Chip size="small" variant="outlined" label={assignment.role.name} />
              </TableCell>
              <TableCell>
                {assignment.projectName ?? (
                  <Tooltip title="A system-scoped role applies across every project.">
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      Instance-wide
                    </Box>
                  </Tooltip>
                )}
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                {/* Whether a role came through a team or directly determines how you revoke
                    it — removing a person from a project won't help if the team grants it. */}
                {assignment.team ? `${assignment.team.name} (team)` : 'granted directly'}
              </TableCell>
              <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                {formatDateTime(assignment.grantedAt)}
                {rowErrors[assignment.id] && (
                  <Alert
                    severity="error"
                    role="alert"
                    sx={{ mt: 0.5, py: 0, fontSize: '0.7rem' }}
                    data-testid="Admin.Assignments.Row.Error"
                  >
                    {rowErrors[assignment.id]}
                  </Alert>
                )}
              </TableCell>
              <TableCell align="right">
                {/*
                  The same scope-dependent gate the server applies, and the same one the grant
                  dialog applies — a row naming a project asks that project's `project:members`, a
                  company-wide row asks `company:members`. Disabled with a reason rather than
                  hidden: an action cell that empties on some rows shifts the whole column.
                */}
                <PermissionGate
                  projectId={assignment.projectId ?? 'company'}
                  permission={assignment.projectId ? 'project:members' : 'company:members'}
                >
                  {({ allowed }) => (
                    <Button
                      size="small"
                      color="error"
                      disabled={!allowed || revoke.isPending}
                      onClick={() => void onRevoke(assignment)}
                      data-testid="Admin.Assignments.Row.Revoke"
                    >
                      Revoke
                    </Button>
                  )}
                </PermissionGate>
              </TableCell>
            </TableRow>
          ))}
      </TableBody>
    </Table>
  );
}

function RoleList({ roles }: { roles: Role[] }) {
  return (
    <Stack sx={{ gap: 2 }}>
      {roles.map((role) => (
        <Box key={role.id} data-testid="Admin.Roles.Item">
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {role.name}
            </Typography>
            <Chip size="small" variant="outlined" label={role.scope.toLowerCase()} />
            {role.builtIn && (
              <Tooltip title="Built in. Its permissions can't be edited, so behaviour stays predictable across projects.">
                <Chip size="small" variant="outlined" label="built in" />
              </Tooltip>
            )}
          </Stack>

          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 0.75 }}>
            {role.description}
          </Typography>

          {/*
            Coverage is DERIVED from the catalogue, not read from a sentinel. The previous pass
            looked for a '*' permission; no such permission exists in the model, and a sentinel
            would keep reading as full coverage after a new permission was added that the role
            does not hold. Now a gap renders as a gap (lib/permissions.ts).
          */}
          {holdsEveryPermission(role.permissions) ? (
            <Chip
              size="small"
              label={`every permission (${allPermissions.length})`}
              data-testid="Admin.Roles.AllPermissions"
              sx={{
                bgcolor: 'color-mix(in srgb, var(--color-failed) 12%, transparent)',
                color: 'var(--color-failed)',
                border: '1px solid color-mix(in srgb, var(--color-failed) 40%, transparent)',
              }}
            />
          ) : (
            <Stack sx={{ gap: 0.75 }} data-testid="Admin.Roles.Permissions">
              {groupByArea(role.permissions).map((group) => (
                <Stack key={group.area} direction="row" sx={{ gap: 0.75, alignItems: 'baseline' }}>
                  <Typography
                    variant="caption"
                    sx={{ color: 'text.secondary', minWidth: 96, flexShrink: 0 }}
                  >
                    {group.label}
                  </Typography>
                  <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                    {group.permissions.map((permission) => (
                      <Identifier key={permission} variant="muted">
                        {permission}
                      </Identifier>
                    ))}
                  </Stack>
                </Stack>
              ))}
            </Stack>
          )}
        </Box>
      ))}
    </Stack>
  );
}
