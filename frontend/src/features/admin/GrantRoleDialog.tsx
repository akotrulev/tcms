import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { ApiError } from '../../api/client';
import { useGrantRole, useProjects, useRoles, useTeams, useUsers } from '../../api/queries';
import type { RoleAssignment } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';

/**
 * Granting a role — the affordance that ends "a provisioned company plus hand-written SQL" for
 * *staffing*, the way `ProjectCreateDialog` ended it for projects.
 *
 * Until this existed, `provision-company` produced exactly one usable account and there was no way
 * over HTTP to give a colleague sight of a project: company membership grants authentication and
 * **zero project visibility** (roles spec §A1), and a grant could only be written in psql.
 *
 * ## The submit button is the gated affordance, not the button that opens this dialog
 *
 * Everywhere else in the app the gate is known before the control is rendered — `case:edit` on
 * *this* project. Here the permission the server will check is chosen by a field **inside this
 * form**: `project:members` on the project the user picks, or `company:members` if they pick
 * "the whole company". So the gate moves onto Grant, where it can follow the choice, and it
 * re-renders as the scope select changes. Gating the opener instead would need the answer before
 * the question had been asked, and a Test Lead — who may staff exactly one project — would find
 * the whole feature disabled.
 *
 * That is a deliberate reading of §31's disable-don't-hide rule rather than an exception to it:
 * the *action* is Grant, and Grant is disabled with a reason. Opening a form refuses nothing.
 *
 * ## Two selects and no free text, because every field here is an id
 *
 * `react-hook-form` + `zod` carry the other dialogs because they have strings to validate. Every
 * field here is a choice from a list the server just supplied, so the only client-side rule is
 * "pick something" — and the interesting refusals (a scope the role disagrees with, a person who
 * is not a member) are facts only the server holds. They arrive as `errors.roleId` /
 * `errors.userId` and land on the control that caused them.
 */

const TestId = 'Admin.Grant';

/** "The whole company" as a select value — `''` would be indistinguishable from "not chosen". */
const COMPANY_SCOPE = 'company';

export function GrantRoleDialog({
  open,
  onClose,
  onGranted,
  lockedProjectId,
}: {
  open: boolean;
  onClose: () => void;
  onGranted: (assignment: RoleAssignment) => void;
  /**
   * Pins the grant to one project and renders the scope as a fact rather than a question.
   *
   * Set by `ProjectAccessPage`, which is *about* one project: a scope select there would offer to
   * do something the page did not claim to be for, and the obvious mis-click — leaving it on "the
   * whole company" — is now the one choice with company-wide blast radius (see the scope select's
   * own copy). Unset on the company screen, where choosing the scope is the whole question.
   */
  lockedProjectId?: string;
}) {
  const roles = useRoles();
  const users = useUsers();
  const teams = useTeams();
  const projects = useProjects();
  const grant = useGrantRole();

  const [scope, setScope] = useState<string>(lockedProjectId ?? COMPANY_SCOPE);
  const [roleId, setRoleId] = useState('');
  const [subject, setSubject] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | undefined>();

  const projectId = scope === COMPANY_SCOPE ? null : scope;

  /*
    The role list is filtered to the roles that can be granted AT ALL, and SYSTEM is the one that
    matters: Instance Admin's grants live in `instance_role_assignment`, a separate instance-level
    table, because a nullable `company_id` under RLS is permanently unreachable. The server refuses
    it with a 400 naming `roleId`; offering it here would be offering a choice we know is refused.

    Filtered further by the CHOSEN SCOPE, and the two branches are no longer symmetric:

      - **A project** takes PROJECT-scope roles only. A COMPANY-scope role pinned to one project is
        still a 400 on `roleId` — Company Admin is authority over the company, and letting a
        project name it would let anyone holding `project:members` redefine it (roles spec §A1.1,
        exemption 2).
      - **The whole company** takes BOTH, and that changed on 2026-08-02. It used to take
        COMPANY-scope only, and this filter still said so after the server stopped agreeing: roles
        spec §A1.1 made a PROJECT-scope role grantable with no project, meaning *this role on every
        project in the company, unless that project says otherwise*. The old refusal's fear — "this
        hands somebody every project present and future" — was correct and is now the stated
        intent, made governable by the per-project override rather than by the refusal. A form that
        kept refusing it would be the §50 shape in the client's direction: the server accepts an
        input no screen can produce.
  */
  const grantableRoles = useMemo(
    () =>
      (roles.data ?? []).filter((role) =>
        projectId ? role.scope === 'PROJECT' : role.scope !== 'SYSTEM',
      ),
    [roles.data, projectId],
  );

  const selectedRole = grantableRoles.find((role) => role.id === roleId);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFieldErrors({});
    setFormError(undefined);

    const [kind, id] = subject.split(':');
    try {
      const created = await grant.mutateAsync({
        roleId: selectedRole?.id ?? '',
        userId: kind === 'user' ? id : null,
        teamId: kind === 'team' ? id : null,
        projectId,
      });
      onGranted(created);
      onClose();
    } catch (error) {
      // The server keys its messages by the INPUT schema's field names, so `roleId` lands on the
      // role select and `userId`/`teamId` both land on the one subject control the form has. A 403
      // has no field to land on and goes to the form-level alert through `describeError`, the one
      // place an error becomes user-facing copy.
      if (error instanceof ApiError && error.fieldErrors) {
        const landed: Record<string, string> = {};
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          landed[field === 'teamId' ? 'userId' : field] = messages.join(' ');
        }
        if (Object.keys(landed).length > 0) {
          setFieldErrors(landed);
          return;
        }
      }
      setFormError(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={grant.isPending}>
        <DialogTitle>Grant a role</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {formError && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {formError}
              </Alert>
            )}

            <TextField
              select
              label="Where"
              value={scope}
              // Locked, not hidden: the project screen's grant is still a scoped act and the field
              // is what says which scope. Removing it would make the one company-wide-capable
              // control on that page invisible rather than fixed.
              disabled={!!lockedProjectId}
              // Clearing the role is the point: the role list is scope-dependent, so a role chosen
              // under the old scope is one the server would refuse with a 400 on a select the user
              // did not touch.
              onChange={(event) => {
                setScope(event.target.value);
                setRoleId('');
              }}
              helperText={
                projectId
                  ? 'The role applies to this project only.'
                  : 'A company role applies everywhere. A project role granted here becomes the default on every project — including ones created later.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Scope` } }}
            >
              <MenuItem value={COMPANY_SCOPE}>The whole company</MenuItem>
              {(projects.data ?? []).map((project) => (
                <MenuItem key={project.id} value={project.id}>
                  {project.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              label="Role"
              value={roleId}
              onChange={(event) => setRoleId(event.target.value)}
              error={!!fieldErrors.roleId}
              helperText={
                fieldErrors.roleId ??
                /*
                  The inheritance warning wins over the role's own description, because it is the
                  consequence the administrator cannot see from the two selects: at company scope a
                  PROJECT-scope role is not "this role, here" but "this role on everything that
                  does not say otherwise", and the projects it reaches are not on this screen.
                */
                (!projectId && selectedRole?.scope === 'PROJECT'
                  ? `Applies to every project that has not granted them a role of its own — a project grant replaces this one there, even when it confers less. ${selectedRole.description}`
                  : (selectedRole?.description ??
                    'Only the roles that fit the scope above are offered.'))
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Role` } }}
            >
              {grantableRoles.map((role) => (
                <MenuItem key={role.id} value={role.id}>
                  {role.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              label="To"
              value={subject}
              onChange={(event) => setSubject(event.target.value)}
              error={!!fieldErrors.userId}
              helperText={
                fieldErrors.userId ??
                'A team grant reaches everyone in it — including people who join later.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Subject` } }}
            >
              {(users.data ?? []).map((user) => (
                <MenuItem key={user.id} value={`user:${user.id}`}>
                  {user.displayName}
                </MenuItem>
              ))}
              {(teams.data ?? []).map((team) => (
                <MenuItem key={team.id} value={`team:${team.id}`}>
                  {team.name} (team)
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          {/*
            The gate follows the scope select: `company` asks the company-wide question, a project
            id asks that project's. This is the only `PermissionGate` in the app whose `projectId`
            is a piece of form state, and it is why the gate is on Grant rather than on the button
            that opened this dialog.
          */}
          <PermissionGate
            projectId={projectId ?? 'company'}
            permission={projectId ? 'project:members' : 'company:members'}
          >
            {({ allowed }) => (
              <Button
                type="submit"
                variant="contained"
                disabled={!allowed || grant.isPending || !roleId || !subject}
                data-testid={`${TestId}.Submit`}
              >
                {grant.isPending ? 'Granting…' : 'Grant role'}
              </Button>
            )}
          </PermissionGate>
        </DialogActions>
      </form>
    </Dialog>
  );
}
