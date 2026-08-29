import { useMemo, useState, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import type { Team, User } from '../../api/types';
import { useRoleAssignments, useRoles, useTeams, useUsers } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
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
    <div>
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
            variant="primary"
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

      <div className="flex flex-col gap-7">
        <Section
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
                  size="sm"
                  variant="primary"
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
        </Section>

        <Section title="Role assignments" testId="Admin.Assignments">
          <DataRegion
            testId="Admin.Assignments.List"
            state={{ ...assignments, refetch: () => void assignments.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{ kind: 'no-records', title: 'No assignments', body: 'Nobody holds a project role yet.' }}
          >
            {(rows) => <RoleAssignmentTable rows={rows} testId="Admin.Assignments" />}
          </DataRegion>
        </Section>

        <Section
          title="People"
          testId="Admin.Users"
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
                  size="sm"
                  variant="primary"
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
              <Table data-testid="Admin.Users.Grid">
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Name</TableHeaderCell>
                    <TableHeaderCell>Email</TableHeaderCell>
                    <TableHeaderCell className="w-[120px]">Status</TableHeaderCell>
                    <TableHeaderCell>Teams</TableHeaderCell>
                    <TableHeaderCell className="w-[140px]">Last signed in</TableHeaderCell>
                    <TableHeaderCell className="w-[110px]" align="right">
                      {/* One action per row; the header names the column, not the verb. */}
                      Membership
                    </TableHeaderCell>
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
                      <TableRow
                        key={user.id}
                        data-testid="Admin.Users.Row"
                        /*
                          Rule 7, disable don't hide: a deactivated account stays listed and reads
                          as retired rather than being dropped. Somebody asking "who had access in
                          March" cannot be answered by a list that removes its own history.
                        */
                        className={user.status === 'ACTIVE' ? undefined : 'opacity-60'}
                      >
                        <TableCell variant="title">{user.displayName}</TableCell>
                        <TableCell className="text-ink-3">{user.email}</TableCell>
                        <TableCell>
                          <Badge
                            tone={user.status === 'ACTIVE' ? 'passed' : 'plain'}
                            data-testid="Admin.Users.Row.Status"
                          >
                            {user.status.toLowerCase()}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-[11.5px]">
                          {user.teams.length === 0 ? (
                            <span className="text-ink-4">None</span>
                          ) : (
                            user.teams.join(', ')
                          )}
                        </TableCell>
                        <TableCell className="text-[11.5px] text-ink-4">
                          <span title={formatDateTime(user.lastLoginAt)}>
                            {formatAge(user.lastLoginAt)}
                          </span>
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
                            {({ allowed, reason }) => {
                              const self = user.id === signedIn?.id;
                              return (
                                <Button
                                  size="sm"
                                  variant="danger"
                                  disabled={!allowed || self}
                                  title={
                                    self
                                      ? 'You cannot remove yourself. Ask another administrator.'
                                      : allowed
                                        ? undefined
                                        : reason
                                  }
                                  onClick={() => setRemoving(user)}
                                  data-testid="Admin.Users.Row.Remove"
                                >
                                  Remove
                                </Button>
                              );
                            }}
                          </PermissionGate>
                        </TableCell>
                      </TableRow>
                    ))}
                </TableBody>
              </Table>
            )}
          </DataRegion>
        </Section>

        <Section title="What each role can do" testId="Admin.Roles">
          <DataRegion
            testId="Admin.Roles.List"
            state={{ ...roles, refetch: () => void roles.refetch() }}
            isEmpty={(rows) => rows.length === 0}
          >
            {(rows) => <RoleReference roles={rows} testId="Admin.Roles" />}
          </DataRegion>
        </Section>
      </div>
    </div>
  );
}

/**
 * A region heading in the render's uppercase mono, with this region's one action beside it.
 *
 * Rule 1 again: the four regions of this page were bordered panels around tables and a tree, and
 * the border was doing the heading's job.
 */
function Section({
  title,
  actions,
  children,
  testId,
}: {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section data-testid={testId}>
      <div className="mb-2.5 flex items-center justify-between gap-3">
        <h2 className="font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
          {title}
        </h2>
        {actions}
      </div>
      {children}
    </section>
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
    <div className="flex flex-col">
      {tree.map(({ team, depth }) => (
        <div
          key={team.id}
          data-testid="Admin.Teams.Item"
          style={{ marginLeft: depth * 22 }}
          className={
            depth > 0
              ? 'border-b border-l-2 border-line py-2.5 pl-3 last:border-b-0'
              : 'border-b border-line py-2.5 last:border-b-0'
          }
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[13px] font-semibold text-ink">{team.name}</span>
            <span className="text-[11.5px] text-ink-4">
              <Identifier variant="muted">{formatCount(team.members.length)}</Identifier>{' '}
              {team.members.length === 1 ? 'member' : 'members'}
            </span>
            {team.leads.map((lead) => (
              <Badge key={lead.id} tone="plain">
                lead: {lead.displayName}
              </Badge>
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
            <Button asChild size="sm" className="ml-auto" data-testid="Admin.Teams.Item.Board">
              <RouterLink to={`/teams/${team.id}/board`}>Board</RouterLink>
            </Button>

            {/*
              One gate wrapping all three, rather than three: they answer to the same permission at
              the same scope, and a per-button copy is three places for the key to be mistyped.
              Pushed right so the tree still reads as a tree.
            */}
            <PermissionGate projectId="company" permission="company:teams">
              {({ allowed, reason }) => (
                <span className="flex gap-1">
                  <Button
                    size="sm"
                    disabled={!allowed}
                    title={allowed ? undefined : reason}
                    onClick={() => onManage(team)}
                    data-testid="Admin.Teams.Item.Members"
                  >
                    Members
                  </Button>
                  <Button
                    size="sm"
                    disabled={!allowed}
                    title={allowed ? undefined : reason}
                    onClick={() => onEdit(team)}
                    data-testid="Admin.Teams.Item.Edit"
                  >
                    Edit
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    disabled={!allowed}
                    title={allowed ? undefined : reason}
                    onClick={() => onDelete(team)}
                    data-testid="Admin.Teams.Item.Delete"
                  >
                    Delete
                  </Button>
                </span>
              )}
            </PermissionGate>
          </div>

          {team.description && (
            <p className="mt-0.5 max-w-[74ch] text-[12px] leading-[1.5] text-ink-4">{team.description}</p>
          )}

          {team.projects.length > 0 && (
            <div className="mt-1 flex flex-wrap gap-1">
              {team.projects.map((project) => (
                <span
                  key={`${project.projectId}-${project.roleName}`}
                  title={`Everyone in ${team.name} holds ${project.roleName} on ${project.projectName}`}
                >
                  <Badge tone="steel" data-testid="Admin.Teams.Item.ProjectRole">
                    {project.projectName}: {project.roleName}
                  </Badge>
                </span>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
