import {
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
import { useAuth } from '../../auth/useAuth';
import type { Team, User } from '../../api/types';
import { useRoleAssignments, useRoles, useTeams, useUsers } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { formatAge, formatCount, formatDateTime } from '../../lib/format';
import { GrantRoleDialog } from './GrantRoleDialog';
import { InviteUserDialog } from './InviteUserDialog';
import { RemoveUserDialog } from './RemoveUserDialog';
import { RoleAssignmentTable } from './RoleAssignmentTable';
import { RoleReference } from './RoleReference';
import { ScrollX } from '../../components/ScrollX';

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
 *
 * This is the **company** view of that list, deliberately unfiltered. The per-project view of the
 * same data — with the roles a project inherits from the company, and which of them a project
 * grant has replaced — is `ProjectAccessPage`, reached from a project's own rail. Both render
 * through `RoleAssignmentTable` so the two cannot drift about what a Revoke means.
 */
export function TeamsPage() {
  const teams = useTeams();
  const roles = useRoles();
  const assignments = useRoleAssignments();
  const users = useUsers();
  const [granting, setGranting] = useState(false);
  const [inviting, setInviting] = useState(false);
  /*
    The person being removed, not a boolean: the dialog names them, so it needs the row. Held here
    rather than per row so exactly one confirmation can be open, and cleared on close so the next
    one mounts fresh (§54's dialog-state lesson, same reason `inviting` mounts conditionally).
  */
  const [removing, setRemoving] = useState<User | null>(null);
  const { user: signedIn } = useAuth();

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

      {/* Conditionally mounted, like every other create dialog here: a form mounted behind
          `open={false}` keeps one component instance — and therefore one set of field state and
          one mutation result — across every use of it (§54). */}
      {inviting && <InviteUserDialog open onClose={() => setInviting(false)} />}

      {removing && (
        <RemoveUserDialog open user={removing} onClose={() => setRemoving(null)} />
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
            {(rows) => <RoleAssignmentTable rows={rows} testId="Admin.Assignments" />}
          </DataRegion>
        </Panel>

        <Panel
          title="People"
          testId="Admin.Users"
          dense
          actions={
            /*
              GATED, unlike "Grant a role" above — and the contrast is the point. That button is
              the app's one deliberately ungated opener because the permission its form will ask
              for is chosen by a field inside the form. This one is known before anything renders:
              `company:members`, company scope, always. So §31's disable-don't-hide rule applies in
              its ordinary form, and a Test Lead sees the capability exists and is worth asking
              for rather than a product that appears unable to add people.
            */
            <PermissionGate projectId="company" permission="company:members">
              {({ allowed }) => (
                <Button
                  size="small"
                  variant="contained"
                  disabled={!allowed}
                  onClick={() => setInviting(true)}
                  data-testid="Admin.Invite.Open"
                >
                  Invite a person
                </Button>
              )}
            </PermissionGate>
          }
        >
          <DataRegion
            testId="Admin.Users.List"
            state={{ ...users, refetch: () => void users.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{ kind: 'no-records', title: 'No accounts', body: 'No user accounts exist yet.' }}
          >
            {(rows) => (
              <ScrollX>
                <Table size="small" data-testid="Admin.Users.Grid">
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ color: 'text.secondary' }}>Name</TableCell>
                      <TableCell sx={{ color: 'text.secondary' }}>Email</TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 120 }}>Status</TableCell>
                      <TableCell sx={{ color: 'text.secondary' }}>Teams</TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 140 }}>Last signed in</TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 110 }} align="right">
                        {/* One action per row; the header names the column, not the verb. */}
                        Membership
                      </TableCell>
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
                          <TableCell align="right">
                            {/*
                              Two independent reasons this can be disabled, and they are disabled
                              with DIFFERENT sentences on purpose (§31 is about the reason as much
                              as the state): not holding `company:members`, which `PermissionGate`
                              answers, and naming yourself, which only the session can answer. The
                              server refuses the second with a 400 either way — the dialog renders
                              it — but a button that invites a refusal the client could have
                              predicted is the thing §31 exists to stop.
                            */}
                            <PermissionGate projectId="company" permission="company:members">
                              {({ allowed }) => {
                                const self = user.id === signedIn?.id;
                                return (
                                  <Tooltip
                                    title={
                                      self
                                        ? 'You cannot remove yourself. Ask another administrator.'
                                        : ''
                                    }
                                  >
                                    {/* A disabled button fires no events, so the tooltip needs a
                                        wrapper that does — MUI's documented workaround. */}
                                    <Box component="span">
                                      <Button
                                        size="small"
                                        color="error"
                                        disabled={!allowed || self}
                                        onClick={() => setRemoving(user)}
                                        data-testid="Admin.Users.Row.Remove"
                                      >
                                        Remove
                                      </Button>
                                    </Box>
                                  </Tooltip>
                                );
                              }}
                            </PermissionGate>
                          </TableCell>
                        </TableRow>
                      ))}
                  </TableBody>
                </Table>
              </ScrollX>
            )}
          </DataRegion>
        </Panel>

        <Panel title="What each role can do" testId="Admin.Roles">
          <DataRegion
            testId="Admin.Roles.List"
            state={{ ...roles, refetch: () => void roles.refetch() }}
            isEmpty={(rows) => rows.length === 0}
          >
            {(rows) => <RoleReference roles={rows} testId="Admin.Roles" />}
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
