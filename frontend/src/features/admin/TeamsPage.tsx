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
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import type { Team, User } from '../../api/types';
import { useRoleAssignments, useRoles, useTeams, useUsers } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { formatAge, formatCount, formatDateTime } from '../../lib/format';
import { DeleteTeamDialog } from './DeleteTeamDialog';
import { GrantRoleDialog } from './GrantRoleDialog';
import { InviteUserDialog } from './InviteUserDialog';
import { RemoveUserDialog } from './RemoveUserDialog';
import { RoleAssignmentTable } from './RoleAssignmentTable';
import { RoleReference } from './RoleReference';
import { flattenTeamTree } from './teamTree';
import { TeamDialog } from './TeamDialog';
import { TeamMembersDialog } from './TeamMembersDialog';
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
  /*
    Four org-chart dialogs, each holding the TEAM rather than a boolean, for the reason `removing`
    gives: they name the team, so they need the row. `editing` holds `null` for "create", which is
    distinct from `undefined` for "closed" — one dialog serves both writes (see `TeamDialog`), and
    collapsing the two would make New team un-openable once a team had been edited.
  */
  const [editing, setEditing] = useState<Team | null | undefined>(undefined);
  const [managing, setManaging] = useState<Team | null>(null);
  const [deleting, setDeleting] = useState<Team | null>(null);
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

      {editing !== undefined && (
        <TeamDialog
          open
          team={editing ?? undefined}
          teams={teams.data ?? []}
          onClose={() => setEditing(undefined)}
        />
      )}

      {managing && (
        <TeamMembersDialog
          open
          /* Re-read from the list each render, so the checkboxes follow the mutation's own
             invalidation rather than a snapshot taken when the dialog opened. */
          team={teams.data?.find((t) => t.id === managing.id) ?? managing}
          onClose={() => setManaging(null)}
        />
      )}

      {deleting && (
        <DeleteTeamDialog open team={deleting} onClose={() => setDeleting(null)} />
      )}

      <Stack sx={{ gap: 3 }}>
        <Panel
          title="Team structure"
          testId="Admin.Teams"
          actions={
            /*
              GATED like "Invite a person" and unlike "Grant a role": the permission is known
              before anything renders — `company:teams`, company scope, always — so §31's
              disable-don't-hide applies in its ordinary form.
            */
            <PermissionGate projectId="company" permission="company:teams">
              {({ allowed }) => (
                <Button
                  size="small"
                  variant="contained"
                  disabled={!allowed}
                  onClick={() => setEditing(null)}
                  data-testid="Admin.Teams.New"
                >
                  New team
                </Button>
              )}
            </PermissionGate>
          }
        >
          <DataRegion
            testId="Admin.Teams.List"
            state={{ ...teams, refetch: () => void teams.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{
              kind: 'no-records',
              title: 'No teams',
              body: 'Create a team to group people and grant roles by team.',
            }}
          >
            {(rows) => (
              <TeamTree
                teams={rows}
                onEdit={setEditing}
                onManage={setManaging}
                onDelete={setDeleting}
              />
            )}
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
                                  user.status === 'ACTIVE' ? 'var(--color-success)' : 'var(--color-text-faint)',
                                border: `1px solid color-mix(in srgb, ${
                                  user.status === 'ACTIVE' ? 'var(--color-success)' : 'var(--color-text-faint)'
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
function TeamTree({
  teams,
  onEdit,
  onManage,
  onDelete,
}: {
  teams: Team[];
  onEdit: (team: Team) => void;
  onManage: (team: Team) => void;
  onDelete: (team: Team) => void;
}) {
  const tree = useMemo(() => flattenTeamTree(teams), [teams]);

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

            {/*
              §6l's team board (§84), and **the one affordance on this page with no gate around
              it** — which is the reason it sits outside the `PermissionGate` below rather than
              inside it as a fourth button.

              There is no permission key for a team board and §84 declined to invent one: every card
              it can return is one the reader could already see on a project board, and a key seeded
              to nobody would be a 403 no administrator could grant their way out of. Nor must the
              reader be *on* the team — §6k makes the org chart readable by any member of the
              company, and a team board a lead's manager cannot open is a board for nobody. Wrapping
              this in `company:teams` would hand a work view to the two roles that administer the
              org chart, which is not who the board is for.

              It is the entry point to that screen: `/teams/:teamId/board` has no rail item, because
              a rail entry would need a team chosen before there is one, and this list is where a
              team is chosen.
            */}
            <Button
              component={RouterLink}
              to={`/teams/${team.id}/board`}
              size="small"
              data-testid="Admin.Teams.Item.Board"
              sx={{ ml: 'auto' }}
            >
              Board
            </Button>

            {/*
              One gate wrapping all three, rather than three: they answer to the same permission at
              the same scope, and a per-button copy is three places for the key to be mistyped.
              Pushed right so the tree still reads as a tree.
            */}
            <PermissionGate projectId="company" permission="company:teams">
              {({ allowed, reason }) => (
                <Stack direction="row" sx={{ gap: 0.5 }}>
                  <Tooltip title={allowed ? '' : reason}>
                    <span>
                      <Button
                        size="small"
                        disabled={!allowed}
                        onClick={() => onManage(team)}
                        data-testid="Admin.Teams.Item.Members"
                      >
                        Members
                      </Button>
                    </span>
                  </Tooltip>
                  <Tooltip title={allowed ? '' : reason}>
                    <span>
                      <Button
                        size="small"
                        disabled={!allowed}
                        onClick={() => onEdit(team)}
                        data-testid="Admin.Teams.Item.Edit"
                      >
                        Edit
                      </Button>
                    </span>
                  </Tooltip>
                  <Tooltip title={allowed ? '' : reason}>
                    <span>
                      <Button
                        size="small"
                        color="error"
                        disabled={!allowed}
                        onClick={() => onDelete(team)}
                        data-testid="Admin.Teams.Item.Delete"
                      >
                        Delete
                      </Button>
                    </span>
                  </Tooltip>
                </Stack>
              )}
            </PermissionGate>
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
